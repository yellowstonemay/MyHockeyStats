"""
Smart roster scraper for Atlantic Hockey (atlantichockey.org).

This script:
1. Accepts a season year (e.g. `--season 2025` for 2025-2026 season)
2. Maps it to site's seasonid
3. Fetches the season page and parses the league dropdown (select[name="league"])
4. Loops through discovered leagues to fetch actual team rosters
5. Saves structured CSV with player data

Much faster than blind iteration - only scrapes leagues that exist in the season.

Usage:
  python scripts/scrape_atlantic_hockey.py --season 2025 --output rosters.csv
"""

import argparse
import csv
import json
import time
from playwright.sync_api import sync_playwright

# Mapping from provided season year to site seasonid
SEASON_TO_ID = {
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


def parse_roster_table(table):
    """Parse a Playwright table element into header and row texts."""
    rows = table.query_selector_all('tbody tr')
    parsed = []
    header = []
    if not rows:
        return header, parsed

    first_cols = rows[0].query_selector_all('td')
    first_texts = [c.inner_text().strip() for c in first_cols] if first_cols else []
    is_header = any('PLAYER' in t.upper() or 'BD' in t.upper() or 'BIRTH' in t.upper() for t in first_texts)
    start_idx = 0
    if is_header:
        header = first_texts
        start_idx = 1

    for r in rows[start_idx:]:
        cols = r.query_selector_all('td')
        texts = [c.inner_text().strip() for c in cols]
        if texts:
            parsed.append(texts)

    return header, parsed


def scrape_league_rosters(page, seasonid, league_name, league_params):
    """
    Fetch rosters for a league.
    league_params is like: "?leagueid=279&leaguetypeid=2&seasonid=33"
    Returns list of teams with their player rosters.
    """
    url = f"https://atlantichockey.org/teamroster.php{league_params}"
    
    try:
        page.goto(url, timeout=15000)
    except Exception:
        return []
    
    teams_rosters = []
    
    # Extract team name from pageHeader
    team_name = None
    header_el = page.query_selector('div.pageHeader')
    if header_el:
        team_name = header_el.inner_text().strip()
    
    # Extract season::league text visible on page
    season_text = None
    league_text = None
    body = page.query_selector('body')
    visible_text = body.inner_text() if body else ''
    for line in visible_text.split('\n'):
        if '::' in line:
            parts = line.split('::')
            if len(parts) == 2:
                season_text = parts[0].strip()
                league_text = parts[1].strip()
                break
    
    # Find and parse roster tables
    tables = page.query_selector_all('table')
    for t in tables:
        header, rows = parse_roster_table(t)
        if rows:
            hdr_upper = [h.upper() for h in header]
            # Check if this looks like a player roster table
            if header and (any('PLAYER' in h for h in hdr_upper) or any('BD' in h or 'BIRTH' in h for h in hdr_upper)):
                player_dicts = []
                for texts in rows:
                    d = {}
                    if header and len(header) <= len(texts):
                        for i, col_name in enumerate(header):
                            key = col_name.strip().lower()
                            d[key] = texts[i] if i < len(texts) else ''
                    else:
                        # fallback mapping
                        d['#'] = texts[0] if len(texts) > 0 else ''
                        d['player'] = texts[1] if len(texts) > 1 else ''
                        d['pos'] = texts[2] if len(texts) > 2 else ''
                        d['ht'] = texts[3] if len(texts) > 3 else ''
                        d['wt'] = texts[4] if len(texts) > 4 else ''
                        d['sh'] = texts[5] if len(texts) > 5 else ''
                        d['bd'] = texts[6] if len(texts) > 6 else ''
                        d['hometown'] = texts[7] if len(texts) > 7 else ''
                    if d.get('player') or d.get('player name') or d.get('player_name'):
                        player_dicts.append(d)
                
                if player_dicts:
                    teams_rosters.append({
                        'team_name': team_name,
                        'season': season_text,
                        'league': league_text or league_name,
                        'players': player_dicts,
                        'url': url,
                    })
    
    return teams_rosters


def scrape_roster_page(page, url):
    """Load a single roster URL and return parsed roster dict or None."""
    try:
        page.goto(url, timeout=15000)
    except Exception:
        return None

    team_name = None
    header_el = page.query_selector('div.pageHeader')
    if header_el:
        team_name = header_el.inner_text().strip()

    # Extract season/league visible text with '::'
    season = None
    league = None
    body = page.query_selector('body')
    visible_text = body.inner_text() if body else ''
    for line in visible_text.split('\n'):
        if '::' in line:
            parts = line.split('::')
            if len(parts) == 2:
                season = parts[0].strip()
                league = parts[1].strip()
                break

    # Find roster table
    tables = page.query_selector_all('table')
    for t in tables:
        header, rows = parse_roster_table(t)
        if rows:
            hdr_upper = [h.upper() for h in header]
            if header and (any('PLAYER' in h for h in hdr_upper) or any('BD' in h or 'BIRTH' in h for h in hdr_upper)):
                player_dicts = []
                for texts in rows:
                    d = {}
                    if header and len(header) <= len(texts):
                        for i, col_name in enumerate(header):
                            key = col_name.strip().lower()
                            d[key] = texts[i] if i < len(texts) else ''
                    else:
                        d['number'] = texts[0] if len(texts) > 0 else ''
                        d['player'] = texts[1] if len(texts) > 1 else ''
                        d['pos'] = texts[2] if len(texts) > 2 else ''
                        d['ht'] = texts[3] if len(texts) > 3 else ''
                        d['wt'] = texts[4] if len(texts) > 4 else ''
                        d['shot'] = texts[5] if len(texts) > 5 else ''
                        d['bd'] = texts[6] if len(texts) > 6 else ''
                        d['hometown'] = texts[7] if len(texts) > 7 else ''
                    player_dicts.append(d)

                return {
                    'team_name': team_name,
                    'season': season,
                    'league': league,
                    'players': player_dicts,
                    'header': header,
                }

    return None


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
    league_params is like: "?leagueid=279&leaguetypeid=2&seasonid=33"
    Returns list of dicts: [{'name': 'Team Name', 'params': '?seasonid=33&leaguetypeid=2&leagueid=281&teamid=3301'}, ...]
    """
    league_page_url = f"https://atlantichockey.org/teamroster.php{league_params}"
    
    try:
        page.goto(league_page_url, timeout=15000)
    except Exception as e:
        print(f"Error loading league page: {e}")
        return []
    
    teams = []
    
    # Parse the team dropdown (select[name="team"])
    team_options = page.query_selector_all('select[name="team"] option')
    
    for i, opt in enumerate(team_options):
        # Skip the first option (default "- Teams-")
        if i == 0:
            continue
        
        opt_value = opt.get_attribute('value')
        opt_text = opt.inner_text().strip()
        
        if not opt_value or not opt_text:
            continue
        
        teams.append({
            'name': opt_text,
            'params': opt_value,
        })
    
    return teams


def main():
    parser = argparse.ArgumentParser(description="Smart scrape Atlantic Hockey rosters in two phases: discovery, then scraping")
    parser.add_argument('--season', type=int, required=True, help='Season year (e.g. 2025 for 2025-2026)')
    parser.add_argument('--output', default='rosters.csv', help='Output CSV file for roster data')
    parser.add_argument('--discovered-teams', default='discovered_teams.csv', help='CSV file to save discovered league-team list')
    parser.add_argument('--delay', type=float, default=0.02, help='Delay between requests (seconds)')
    parser.add_argument('--sample', action='store_true', help='Sample mode (limit to 2 leagues, 3 teams each)')
    args = parser.parse_args()

    seasonid = SEASON_TO_ID.get(args.season)
    if seasonid is None:
        print(f"Unknown season {args.season}. Please provide a supported season year.")
        return

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        # =============================================================
        # PHASE 1: DISCOVERY - Discover all leagues and teams
        # =============================================================
        print(f"\n{'='*70}")
        print(f"PHASE 1: DISCOVERY")
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
        
        # Discover all teams and save to CSV
        league_team_list = []
        
        for league in leagues:
            print(f"  Discovering teams in '{league['name']}'...")
            teams = discover_teams_from_league(page, league['params'])
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
        
        print(f"\n✅ Discovery complete: {len(league_team_list)} league-team pairs found")
        
        # Save discovered league-team list to CSV
        print(f"\nSaving discovered teams to '{args.discovered_teams}'...")
        with open(args.discovered_teams, 'w', newline='', encoding='utf-8') as csvfile:
            fieldnames = ['season_year', 'seasonid', 'leagueid', 'league_name', 'team_name', 'team_params']
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(league_team_list)
        print(f"✅ Saved {len(league_team_list)} league-team pairs to '{args.discovered_teams}'")
        
        # =============================================================
        # PHASE 2: SCRAPING - Scrape rosters for each discovered team
        # =============================================================
        print(f"\n{'='*70}")
        print(f"PHASE 2: SCRAPING")
        print(f"{'='*70}\n")
        
        fieldnames = ['season_year', 'seasonid', 'leagueid', 'teamid', 'team', 'number', 'player', 'pos', 'ht', 'wt', 'shot', 'birthdate', 'hometown', 'source_url']

        print(f"Scraping rosters for {len(league_team_list)} teams...")
        
        with open(args.output, 'w', newline='', encoding='utf-8') as csvfile:
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()

            total_found = 0
            for idx, item in enumerate(league_team_list, 1):
                league_name = item['league_name']
                team_name = item['team_name']
                team_params = item['team_params']
                leagueid = item['leagueid']
                
                url = f"https://atlantichockey.org/teamroster.php{team_params}"
                
                try:
                    res = scrape_roster_page(page, url)
                except Exception as e:
                    res = None
                
                # Extract teamid from team params
                teamid = None
                if team_params.startswith('?'):
                    params_str = team_params[1:]
                    for param in params_str.split('&'):
                        if param.startswith('teamid='):
                            teamid = int(param.split('=')[1])
                            break
                
                if res and res.get('players'):
                    roster_team_name = res.get('team_name') or team_name or ''
                    
                    for pd in res['players']:
                        row = {
                            'season_year': item['season_year'],
                            'seasonid': item['seasonid'],
                            'leagueid': leagueid,
                            'teamid': teamid or '',
                            'team': roster_team_name,
                            'number': pd.get('#') or pd.get('number') or '',
                            'player': pd.get('player') or pd.get('player name') or pd.get('player_name') or '',
                            'pos': pd.get('pos') or pd.get('position') or '',
                            'ht': pd.get('ht') or '',
                            'wt': pd.get('wt') or '',
                            'shot': pd.get('sh') or pd.get('shot') or '',
                            'birthdate': pd.get('bd') or pd.get('birthdate') or '',
                            'hometown': pd.get('hometown') or '',
                            'source_url': url,
                        }
                        writer.writerow(row)
                        total_found += 1
                    
                    print(f"  [{idx}/{len(league_team_list)}] ✅ {league_name} → {team_name}: {len(res['players'])} players")
                else:
                    print(f"  [{idx}/{len(league_team_list)}] ⏭️  {league_name} → {team_name}: no roster found")
                
                # Polite delay
                time.sleep(args.delay)

        print(f"\n✅ DONE. Total player rows written: {total_found} to '{args.output}'")
        browser.close()


if __name__ == '__main__':
    main()
