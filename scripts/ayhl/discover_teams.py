"""
Phase 1: Discover leagues and teams from Atlantic Hockey (atlantichockey.org).

This script:
1. Accepts a season year (e.g. `--season 2025` for 2025-2026 season)
2. Maps it to site's seasonid
3. Fetches the season page and parses the league dropdown (select[name="league"])
4. For each league, discovers teams from the team dropdown (select[name="team"])
5. Saves league-team list to CSV (e.g., 2025-ayhl-teams.csv)

This CSV is then used by scrape_rosters.py to fetch actual roster data.

Usage:
  python discover_teams.py --season 2025
  python discover_teams.py --season 2025 --sample
"""

import argparse
import csv
import os
import sys
import time
from playwright.sync_api import sync_playwright

try:
    from playwright_stealth import Stealth
    _stealth = Stealth()
    HAS_STEALTH = True
except ImportError:
    HAS_STEALTH = False

try:
    import psycopg2
    HAS_PSYCOPG2 = True
except ImportError:
    HAS_PSYCOPG2 = False

# Mapping from provided season year to site seasonid
SEASON_TO_ID = {
    2026: 34,
    2025: 33,
    2024: 32,
    2023: 31,
    2022: 30,
    2021: 28,
    2020: 27,
    2019: 26,
    2018: 25,
    2017: 24,
    2016: 23,
    2015: 22,
    2014: 21,
    2013: 20,
    2012: 19,
    2011: 17,
    2010: 16,
    2009: 15,
    2008: 14,
    2007: 13,
    2006: 12,
    2005: 11,
    2004: 10,
}


# ─── Database helpers ──────────────────────────────────────────────────────

def ensure_teams_table(conn):
    """Create ayhl_teams table if it doesn't exist."""
    sql = """
    CREATE TABLE IF NOT EXISTS ayhl_teams (
        id              SERIAL PRIMARY KEY,
        season_year     INTEGER NOT NULL,
        seasonid        INTEGER NOT NULL,
        leagueid        INTEGER NOT NULL,
        league_name     VARCHAR(255) NOT NULL,
        team_name       VARCHAR(255) NOT NULL,
        team_params     VARCHAR(512),
        created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
        updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
        UNIQUE (season_year, leagueid, team_name)
    );
    """
    with conn.cursor() as cur:
        cur.execute(sql)
    conn.commit()


def upsert_teams_to_db(conn, teams):
    """Upsert league-team rows into ayhl_teams. Returns count."""
    sql = """
    INSERT INTO ayhl_teams (season_year, seasonid, leagueid, league_name, team_name, team_params)
    VALUES (%s, %s, %s, %s, %s, %s)
    ON CONFLICT (season_year, leagueid, team_name)
    DO UPDATE SET
        seasonid    = EXCLUDED.seasonid,
        team_params = EXCLUDED.team_params,
        updated_at  = NOW()
    """
    with conn.cursor() as cur:
        for t in teams:
            cur.execute(sql, (
                t['season_year'], t['seasonid'], t['leagueid'],
                t['league_name'], t['team_name'], t['team_params'],
            ))
    conn.commit()
    return len(teams)


def load_teams_from_db(conn, season_year):
    """Load teams from ayhl_teams for a given season. Returns list of dicts."""
    sql = """
    SELECT season_year, seasonid, leagueid, league_name, team_name, team_params
    FROM ayhl_teams
    WHERE season_year = %s
    ORDER BY league_name, team_name
    """
    with conn.cursor() as cur:
        cur.execute(sql, (season_year,))
        cols = [desc[0] for desc in cur.description]
        return [dict(zip(cols, row)) for row in cur.fetchall()]


def db_connect_from_args(args):
    """Connect to PostgreSQL using parsed args (or return None)."""
    if not HAS_PSYCOPG2:
        return None
    try:
        return psycopg2.connect(
            host=args.host, port=args.port,
            dbname=args.dbname, user=args.user,
            password=args.password,
        )
    except Exception as e:
        print(f"  ⚠ DB connection failed: {e} (proceeding with CSV only)")
        return None


# ─── Discovery functions ───────────────────────────────────────────────────

def discover_leagues_from_season(page, seasonid):
    """
    Discover all available leagues for a season by parsing the league dropdown.
    Returns list of dicts: [{'name': 'League Name', 'leagueid': 279, 'params': '?leagueid=279&leaguetypeid=2&seasonid=33'}, ...]
    """
    season_page_url = f"https://atlantichockey.org/teamroster.php?seasonid={seasonid}"
    
    try:
        page.goto(season_page_url, timeout=15000)
    except Exception as e:
        print(f"Error loading season page: {e}")
        return []
    
    leagues = []
    
    # Parse the league dropdown (select[name="league"])
    league_options = page.query_selector_all('select[name="league"] option')
    
    for opt in league_options:
        opt_value = opt.get_attribute('value')
        opt_text = opt.inner_text().strip()
        
        # Skip empty or default option (usually the first one)
        if not opt_value or opt_value.startswith('?seasonid=') and '&leagueid=' not in opt_value:
            continue
        
        # Parse leagueid from value (e.g., "?leagueid=279&leaguetypeid=2&seasonid=33")
        leagueid = None
        if opt_value.startswith('?'):
            params = opt_value[1:]  # remove leading '?'
            for param in params.split('&'):
                if param.startswith('leagueid='):
                    leagueid = int(param.split('=')[1])
                    break
        
        if leagueid is not None:
            leagues.append({
                'name': opt_text,
                'leagueid': leagueid,
                'params': opt_value,
            })
    
    print(f"Discovered {len(leagues)} leagues for season {seasonid}: {[l['name'] for l in leagues]}")
    return leagues


def discover_teams_from_league(page, league_params):
    """
    Discover all teams for a league by parsing the team dropdown.
    Reuses the same page (navigates in-place) to preserve session & avoid Cloudflare.
    league_params is like: "?leagueid=279&leaguetypeid=2&seasonid=33"
    Returns list of dicts: [{'name': 'Team Name', 'params': '?seasonid=33&leaguetypeid=2&leagueid=281&teamid=3301'}, ...]
    """
    league_page_url = f"https://atlantichockey.org/teamroster.php{league_params}"
    
    try:
        page.goto(league_page_url, timeout=30000, wait_until='load')
        # Wait for the select element itself
        page.wait_for_selector('select[name="team"]', state='attached', timeout=15000)
        # Small pause for JS to populate options
        page.wait_for_timeout(3000)
    except Exception as e:
        print(f"Error loading league page: {e}")
        return []
    
    teams = []
    
    # Parse the team dropdown (select[name="team"])
    team_options = page.query_selector_all('select[name="team"] option')
    
    for opt in team_options:
        opt_value = opt.get_attribute('value')
        opt_text = opt.inner_text().strip()
        
        if not opt_value or not opt_text or 'teamid=' not in opt_value:
            continue
        
        teams.append({
            'name': opt_text,
            'params': opt_value,
        })
    
    return teams


def main():
    parser = argparse.ArgumentParser(description="Discover leagues and teams from Atlantic Hockey")
    parser.add_argument('--season', type=int, required=True, help='Season year (e.g. 2025 for 2025-2026)')
    parser.add_argument('--delay', type=float, default=0.02, help='Delay between requests (seconds)')
    parser.add_argument('--sample', action='store_true', help='Sample mode (limit to 2 leagues, 3 teams each)')
    # DB connection (optional — falls back to CSV-only if omitted)
    parser.add_argument('--host', default=None, help='PostgreSQL host (skip = CSV only)')
    parser.add_argument('--port', type=int, default=5432)
    parser.add_argument('--dbname', default='myhockeystats')
    parser.add_argument('--user', default='postgres')
    parser.add_argument('--password', default='postgres')
    args = parser.parse_args()

    seasonid = SEASON_TO_ID.get(args.season)
    if seasonid is None:
        print(f"Unknown season {args.season}. Please provide a supported season year.")
        return

    # Write teams CSV into the data/teams subdirectory
    output_dir = os.path.join(os.path.dirname(__file__), 'data', 'teams')
    if not os.path.exists(output_dir):
        os.makedirs(output_dir, exist_ok=True)
    output_file = os.path.join(output_dir, f"{args.season}-ayhl-teams.csv")

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=True,
            args=[
                '--disable-blink-features=AutomationControlled',
                '--disable-dev-shm-usage',
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-web-security',
                '--disable-features=IsolateOrigins,site-per-process',
            ],
        )
        context = browser.new_context(
            user_agent='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
            viewport={'width': 1280, 'height': 800},
            locale='en-US',
            timezone_id='America/New_York',
        )
        page = context.new_page()
        if HAS_STEALTH:
            _stealth.apply_stealth_sync(page)

        print(f"\n{'='*70}")
        print(f"PHASE 1: DISCOVER LEAGUES AND TEAMS")
        print(f"{'='*70}\n")
        
        print(f"Step 1: Discovering leagues for season {args.season} (seasonid={seasonid})...")
        leagues = discover_leagues_from_season(page, seasonid)
        
        if not leagues:
            print("No leagues found. Exiting.")
            browser.close()
            return
        
        if args.sample:
            leagues = leagues[:2]
            print(f"Sample mode: limiting to first 2 leagues\n")
        
        # Season page is no longer needed — close it and its context
        page.close()
        context.close()
        
        # Discover all teams (fresh context + page per league to avoid Cloudflare)
        league_team_list = []
        
        for league in leagues:
            print(f"  Discovering teams in '{league['name']}'...")
            # Create a brand new context and page for each league
            ctx = browser.new_context(
                user_agent='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
                viewport={'width': 1280, 'height': 800},
                locale='en-US',
                timezone_id='America/New_York',
            )
            page = ctx.new_page()
            if HAS_STEALTH:
                _stealth.apply_stealth_sync(page)
            teams = discover_teams_from_league(page, league['params'])
            page.close()
            ctx.close()
            print(f"    Found {len(teams)} teams")
            
            if args.sample:
                teams = teams[:3]
                print(f"    Sample mode: limiting to first 3 teams")
            
            for team in teams:
                league_team_list.append({
                    'season_year': args.season,
                    'seasonid': seasonid,
                    'leagueid': league['leagueid'],
                    'league_name': league['name'],
                    'team_name': team['name'],
                    'team_params': team['params'],
                })
            
            # Polite delay between leagues (longer to avoid Cloudflare rate limits)
            time.sleep(max(args.delay, 3.0))
        
        print(f"\n✅ Discovery complete: {len(league_team_list)} league-team pairs found")
        
        # Save discovered league-team list to CSV
        print(f"\nSaving discovered teams to '{output_file}'...")
        with open(output_file, 'w', newline='', encoding='utf-8') as csvfile:
            fieldnames = ['season_year', 'seasonid', 'leagueid', 'league_name', 'team_name', 'team_params']
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(league_team_list)
        print(f"✅ Saved {len(league_team_list)} league-team pairs to '{output_file}'")

        # Also save to database if DB args provided
        if args.host:
            conn = db_connect_from_args(args)
            if conn:
                try:
                    ensure_teams_table(conn)
                    count = upsert_teams_to_db(conn, league_team_list)
                    print(f"✅ Upserted {count} teams into database (ayhl_teams)")
                finally:
                    conn.close()
        else:
            print(f"  ℹ  DB host not specified — teams saved to CSV only.")
            print(f"     Pass --host <db_host> to also save to database.")

        print(f"\nNext step: Run 'python scrape_rosters.py --season {args.season}'")
        
        browser.close()


if __name__ == '__main__':
    main()
