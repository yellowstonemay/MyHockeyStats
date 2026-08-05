#!/usr/bin/env python3
"""NJ.com High School Ice Hockey deep-dive for registered users.

Scrapes highschoolsports.nj.com (NJ Advance Media / StackSports) for New Jersey
high school boys ice hockey. Pages are server-rendered HTML, so plain HTTP +
BeautifulSoup is sufficient (no browser needed).

Modes:
    python njhs_deep.py --roster            # all conferences -> teams -> rosters -> njhs_rosters
    python njhs_deep.py --stats             # all teams -> per-player season stats -> njhs_player_stats (rankings)
    python njhs_deep.py                     # all NJHS-linked users: career + game log (latest season)
    python njhs_deep.py --player-slug ethan-yan
    python njhs_deep.py --all-seasons       # career + game log for every season in the career table
    python njhs_deep.py --dry-run
"""
from __future__ import annotations

import argparse
import os
import re
from datetime import date, datetime, timezone
from uuid import uuid4

import psycopg2
import psycopg2.extras
import requests
import time
from bs4 import BeautifulSoup

import njhs_guard

BASE = "https://highschoolsports.nj.com"
SPORT = "boysicehockey"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
}

CONFERENCES = ["Big North", "CVC", "GMC", "Gordon", "MCSSIHL", "NJIIHL", "Shore", "Skyland"]


def current_season_year() -> int:
    """NJ HS hockey runs Dec-Mar. In Aug 2026 the latest completed season is
    2025-2026 (year 2025); the next season starts Dec 2026 (year 2026)."""
    today = date.today()
    return today.year if today.month >= 12 else today.year - 1


def season_label(year: int) -> str:
    return f"{year}-{year + 1}"


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


# Be polite to nj.com: keep at least this many seconds between page fetches so
# bursts of requests don't trip bot protection (esp. during --roster/--stats).
_FETCH_GAP_SECONDS = 1.0
_LAST_FETCH_AT = 0.0


def fetch_soup(url: str) -> BeautifulSoup:
    global _LAST_FETCH_AT
    gap = time.time() - _LAST_FETCH_AT
    if gap < _FETCH_GAP_SECONDS:
        time.sleep(_FETCH_GAP_SECONDS - gap)
    resp = requests.get(url, headers=HEADERS, timeout=30)
    resp.raise_for_status()
    _LAST_FETCH_AT = time.time()
    return BeautifulSoup(resp.text, "html.parser")


CREATE_TABLES_SQL = """
CREATE TABLE IF NOT EXISTS njhs_rosters (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    season_year  INTEGER NOT NULL,
    season_label VARCHAR(32) NOT NULL,
    school_slug  VARCHAR(255),
    team_name    VARCHAR(255),
    conference   VARCHAR(64),
    player_id    VARCHAR(255) NOT NULL,
    player_name  VARCHAR(255),
    jersey       VARCHAR(16),
    position     VARCHAR(16),
    player_class VARCHAR(32),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (season_year, player_id)
);
CREATE TABLE IF NOT EXISTS njhs_player_career (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_player_id VARCHAR(255) NOT NULL,
    player_name      VARCHAR(255),
    season_label     VARCHAR(32) NOT NULL,
    season_year      INTEGER,
    league_name      VARCHAR(64) DEFAULT 'NJHS',
    team_name        VARCHAR(255),
    games_played     INTEGER,
    goals            INTEGER,
    assists          INTEGER,
    points           INTEGER,
    is_user_modified BOOLEAN DEFAULT FALSE,
    last_scraped_at  TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (source_player_id, season_label)
);
CREATE TABLE IF NOT EXISTS njhs_player_games (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id    VARCHAR(255) NOT NULL,
    player_name  VARCHAR(255),
    season_year  INTEGER NOT NULL,
    season_label VARCHAR(32) NOT NULL,
    game_id      VARCHAR(64) NOT NULL,
    game_date    DATE,
    game_type    VARCHAR(128),
    league       VARCHAR(255) DEFAULT 'NJHS',
    team_for     VARCHAR(255),
    team_against VARCHAR(255),
    opponent     VARCHAR(255),
    result       VARCHAR(64),
    goals        INTEGER,
    assists      INTEGER,
    points       INTEGER,
    gwg          INTEGER,
    evg          INTEGER,
    ppg          INTEGER,
    shg          INTEGER,
    otg          INTEGER,
    pim          INTEGER,
    scraped_at   TIMESTAMP NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (player_id, season_year, game_id)
);
CREATE TABLE IF NOT EXISTS njhs_change_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_player_id VARCHAR(255) NOT NULL,
    player_name      VARCHAR(255),
    season_label     VARCHAR(32) NOT NULL,
    field_name       VARCHAR(64) NOT NULL,
    old_value        TEXT,
    new_value        TEXT,
    detected_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    event_type       VARCHAR(32) DEFAULT 'STAT_CHANGE'
);
CREATE TABLE IF NOT EXISTS njhs_player_stats (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    season_year  INTEGER NOT NULL,
    season_label VARCHAR(32) NOT NULL,
    school_slug  VARCHAR(255),
    team_name    VARCHAR(255),
    conference   VARCHAR(64),
    player_id    VARCHAR(255) NOT NULL,
    player_name  VARCHAR(255),
    jersey       VARCHAR(16),
    position     VARCHAR(16),
    player_class VARCHAR(32),
    goals        INTEGER,
    assists      INTEGER,
    points       INTEGER,
    gwg          INTEGER,
    evg          INTEGER,
    ppg          INTEGER,
    shg          INTEGER,
    otg          INTEGER,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (season_year, player_id)
);
"""


def ensure_tables(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(CREATE_TABLES_SQL)
    conn.commit()


def _to_int(v) -> int | None:
    if v is None or str(v).strip() in ("", "-"):
        return None
    try:
        return int(str(v).strip())
    except (TypeError, ValueError):
        return None


def _parse_date(val: str) -> str | None:
    """MM/DD/YYYY -> ISO date."""
    m = re.fullmatch(r"(\d{1,2})/(\d{1,2})/(\d{4})", (val or "").strip())
    if m:
        return f"{m.group(3)}-{int(m.group(1)):02d}-{int(m.group(2)):02d}"
    return None


# ---------------------------------------------------------------------------
# Roster scrape: conferences -> teams -> rosters
# ---------------------------------------------------------------------------

def scrape_rosters(conn, season_year: int, dry_run: bool = False) -> int:
    label = season_label(season_year)
    total = 0
    for conf in CONFERENCES:
        try:
            soup = fetch_soup(f"{BASE}/{SPORT}/standings/season/{label}?conference={conf}")
        except Exception as e:
            print(f"  [WARN] standings {conf}: {e}")
            continue
        teams = []
        for a in soup.find_all("a", href=True):
            m = re.search(rf"/school/([^/]+)/{SPORT}/season/{label}", a["href"])
            if m:
                teams.append((m.group(1), a.get_text(" ", strip=True)))
        seen = set()
        for school_slug, team_name in teams:
            if school_slug in seen:
                continue
            seen.add(school_slug)
            try:
                rsoup = fetch_soup(f"{BASE}/school/{school_slug}/{SPORT}/season/{label}/roster")
            except Exception as e:
                print(f"  [WARN] roster {school_slug}: {e}")
                continue
            tbl = rsoup.find("table")
            if not tbl:
                continue
            for tr in tbl.find_all("tr"):
                cells = [td.get_text(" ", strip=True) for td in tr.find_all("td")]
                a = tr.find("a", href=True)
                if not a or len(cells) < 2:
                    continue
                m = re.search(rf"/player/([^/]+)/{SPORT}", a["href"])
                if not m:
                    continue
                slug = m.group(1)
                if dry_run:
                    print(f"  {conf} | {team_name} | {cells[1]} ({slug})")
                    total += 1
                    continue
                with conn.cursor() as cur:
                    cur.execute(
                        """INSERT INTO njhs_rosters
                           (id, season_year, season_label, school_slug, team_name,
                            conference, player_id, player_name, jersey, position, player_class)
                           VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                           ON CONFLICT (season_year, player_id) DO UPDATE SET
                             school_slug = EXCLUDED.school_slug,
                             team_name = EXCLUDED.team_name,
                             conference = EXCLUDED.conference,
                             player_name = EXCLUDED.player_name,
                             jersey = EXCLUDED.jersey,
                             position = EXCLUDED.position,
                             player_class = EXCLUDED.player_class,
                             updated_at = NOW()""",
                        (season_year, label, school_slug, team_name, conf, slug,
                         cells[1], cells[0] if cells[0] else None,
                         cells[2] if len(cells) > 2 else None,
                         cells[3] if len(cells) > 3 else None),
                    )
                total += 1
        if not dry_run:
            conn.commit()
        print(f"  [{conf}] {len(seen)} team(s)")
    return total


# ---------------------------------------------------------------------------
# Team stats scrape: per-player season totals for EVERY team (for rankings)
# ---------------------------------------------------------------------------

def parse_team_stats_table(soup: BeautifulSoup) -> list[dict]:
    """Skaters table: player (name #jersey • class • pos) | G | A | P | GWG | EVG | PPG | SHG | OTG."""
    out: list[dict] = []
    for t in soup.find_all("table"):
        heads = [th.get_text(" ", strip=True) for th in t.find_all("th")]
        if "G" not in heads or "P" not in heads:
            continue
        for tr in t.find_all("tr"):
            cells = [td.get_text(" ", strip=True) for td in tr.find_all("td")]
            if len(cells) < 4:
                continue
            a = tr.find("a", href=True)
            if not a:
                continue
            m = re.search(rf"/player/([^/]+)/{SPORT}", a["href"])
            if not m:
                continue
            slug = m.group(1)
            name = a.get_text(" ", strip=True) or ""
            # header cell like "Lex Assante #11 • Senior • D"
            header = cells[0]
            jersey, pclass, pos = None, None, None
            hm = re.search(r"#(\d+)", header)
            if hm:
                jersey = hm.group(1)
            pm = re.search(r"•\s*(\w+)\s*•\s*([FGD])\s*$", header)
            if pm:
                pclass, pos = pm.group(1), pm.group(2)
            out.append({
                "player_id": slug,
                "player_name": name,
                "jersey": jersey,
                "player_class": pclass,
                "position": pos,
                "goals": _to_int(cells[1]) if len(cells) > 1 else None,
                "assists": _to_int(cells[2]) if len(cells) > 2 else None,
                "points": _to_int(cells[3]) if len(cells) > 3 else None,
                "gwg": _to_int(cells[4]) if len(cells) > 4 else None,
                "evg": _to_int(cells[5]) if len(cells) > 5 else None,
                "ppg": _to_int(cells[6]) if len(cells) > 6 else None,
                "shg": _to_int(cells[7]) if len(cells) > 7 else None,
                "otg": _to_int(cells[8]) if len(cells) > 8 else None,
            })
    return out


def scrape_team_stats(conn, season_year: int, dry_run: bool = False) -> int:
    """Scrape per-player season stats for every team (from njhs_rosters) into
    njhs_player_stats — the full league universe needed for rankings."""
    label = season_label(season_year)
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            "SELECT DISTINCT school_slug, team_name, conference FROM njhs_rosters "
            "WHERE season_year = %s ORDER BY team_name", (season_year,))
        teams = [dict(r) for r in cur.fetchall()]
    total = 0
    for t in teams:
        school_slug = t["school_slug"]
        if not school_slug:
            continue
        try:
            soup = fetch_soup(f"{BASE}/school/{school_slug}/{SPORT}/season/{label}/stats")
        except Exception as e:
            print(f"  [WARN] stats {school_slug}: {e}")
            continue
        rows = parse_team_stats_table(soup)
        if dry_run:
            print(f"  {t['team_name']}: {len(rows)} skaters")
            total += len(rows)
            continue
        with conn.cursor() as cur:
            for r in rows:
                cur.execute(
                    """INSERT INTO njhs_player_stats
                       (id, season_year, season_label, school_slug, team_name, conference,
                        player_id, player_name, jersey, position, player_class,
                        goals, assists, points, gwg, evg, ppg, shg, otg)
                       VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                               %s, %s, %s, %s, %s, %s, %s, %s)
                       ON CONFLICT (season_year, player_id) DO UPDATE SET
                         school_slug = EXCLUDED.school_slug,
                         team_name = EXCLUDED.team_name,
                         conference = EXCLUDED.conference,
                         player_name = EXCLUDED.player_name,
                         jersey = EXCLUDED.jersey,
                         position = EXCLUDED.position,
                         player_class = EXCLUDED.player_class,
                         goals = EXCLUDED.goals,
                         assists = EXCLUDED.assists,
                         points = EXCLUDED.points,
                         gwg = EXCLUDED.gwg,
                         evg = EXCLUDED.evg,
                         ppg = EXCLUDED.ppg,
                         shg = EXCLUDED.shg,
                         otg = EXCLUDED.otg,
                         updated_at = NOW()""",
                    (season_year, label, school_slug, t["team_name"], t["conference"],
                     r["player_id"], r["player_name"], r["jersey"], r["position"],
                     r["player_class"], r["goals"], r["assists"], r["points"],
                     r["gwg"], r["evg"], r["ppg"], r["shg"], r["otg"]),
                )
        conn.commit()
        print(f"  {t['team_name']}: {len(rows)} skaters")
        total += len(rows)
    return total


# ---------------------------------------------------------------------------
# Player scrape: career table + game log
# ---------------------------------------------------------------------------

def parse_career_table(soup: BeautifulSoup) -> list[dict]:
    """Career stats table: Season | G | A | P (skip the 'Career Totals:' row).
    The table can appear more than once on the page, so dedupe by season."""
    out: list[dict] = []
    seen: set[str] = set()
    for t in soup.find_all("table"):
        heads = [th.get_text(" ", strip=True) for th in t.find_all("th")]
        if "Season" not in heads or "G" not in heads:
            continue
        for tr in t.find_all("tr"):
            cells = [td.get_text(" ", strip=True) for td in tr.find_all("td")]
            if len(cells) >= 4 and re.fullmatch(r"\d{4}-\d{4}", cells[0] or ""):
                if cells[0] in seen:
                    continue
                seen.add(cells[0])
                out.append({
                    "season_label": cells[0],
                    "goals": _to_int(cells[1]),
                    "assists": _to_int(cells[2]),
                    "points": _to_int(cells[3]),
                })
    return out


def parse_game_log(soup: BeautifulSoup) -> list[dict]:
    """Game log table: Date | Opponent | Result | G | A | P | GWG | EVG | PPG | SHG | OTG."""
    out: list[dict] = []
    for t in soup.find_all("table"):
        heads = [th.get_text(" ", strip=True) for th in t.find_all("th")]
        if "Date" not in heads or "Opponent" not in heads:
            continue
        for tr in t.find_all("tr"):
            cells = [td.get_text(" ", strip=True) for td in tr.find_all("td")]
            if len(cells) < 6 or not re.fullmatch(r"\d{1,2}/\d{1,2}/\d{4}", cells[0] or ""):
                continue
            game_id = ""
            res_link = tr.find("a", href=True)
            if res_link:
                gm = re.search(r"/game/(\d+)", res_link["href"])
                if gm:
                    game_id = gm.group(1)
            if not game_id:
                game_id = f"{cells[0]}|{cells[1]}"
            out.append({
                "game_id": game_id,
                "game_date": _parse_date(cells[0]),
                "opponent": cells[1],
                "result": cells[2],
                "goals": _to_int(cells[3]),
                "assists": _to_int(cells[4]),
                "points": _to_int(cells[5]),
                "gwg": _to_int(cells[6]) if len(cells) > 6 else None,
                "evg": _to_int(cells[7]) if len(cells) > 7 else None,
                "ppg": _to_int(cells[8]) if len(cells) > 8 else None,
                "shg": _to_int(cells[9]) if len(cells) > 9 else None,
                "otg": _to_int(cells[10]) if len(cells) > 10 else None,
            })
    return out


def parse_player_header(soup: BeautifulSoup) -> tuple[str, str]:
    """Return (school_name, player_name) from the player page header."""
    school = ""
    a = soup.find("a", href=re.compile(r"/school/"))
    if a:
        school = a.get_text(" ", strip=True)
    name = ""
    h1 = soup.find("h1")
    if h1:
        name = h1.get_text(" ", strip=True).split("#")[0].strip()
    return school, name


def upsert_career(conn, slug: str, name: str, school: str,
                  season_label_val: str, season_year: int,
                  games_played: int, stats: dict, dry_run: bool) -> None:
    if dry_run:
        print(f"    career[{season_label_val}]: G={stats.get('goals')} A={stats.get('assists')} "
              f"P={stats.get('points')} GP={games_played}")
        return
    now = datetime.now(timezone.utc)
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            "SELECT games_played, goals, assists, points FROM njhs_player_career "
            "WHERE source_player_id=%s AND season_label=%s",
            (slug, season_label_val),
        )
        old = cur.fetchone()
        if old:
            for field, nv in (("games_played", games_played), ("goals", stats.get("goals")),
                              ("assists", stats.get("assists")), ("points", stats.get("points"))):
                ov = old[field]
                if (ov or 0) != (nv or 0):
                    cur.execute(
                        """INSERT INTO njhs_change_event
                           (id, source_player_id, player_name, season_label, field_name,
                            old_value, new_value, detected_at, event_type)
                           VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, NOW(), 'STAT_CHANGE')""",
                        (slug, name or "N/A", season_label_val, field,
                         str(ov) if ov is not None else "", str(nv) if nv is not None else ""),
                    )
        cur.execute(
            """INSERT INTO njhs_player_career
               (id, source_player_id, player_name, season_label, season_year, league_name,
                team_name, games_played, goals, assists, points, last_scraped_at, created_at, updated_at)
               VALUES (gen_random_uuid(), %s, %s, %s, %s, 'NJHS', %s, %s, %s, %s, %s, %s, NOW(), NOW())
               ON CONFLICT (source_player_id, season_label) DO UPDATE SET
                 player_name = EXCLUDED.player_name,
                 season_year = EXCLUDED.season_year,
                 team_name = EXCLUDED.team_name,
                 games_played = EXCLUDED.games_played,
                 goals = EXCLUDED.goals,
                 assists = EXCLUDED.assists,
                 points = EXCLUDED.points,
                 last_scraped_at = EXCLUDED.last_scraped_at,
                 updated_at = NOW()""",
            (slug, name or "N/A", season_label_val, season_year, school,
             games_played, stats.get("goals"), stats.get("assists"), stats.get("points"), now),
        )
    conn.commit()


def upsert_games(conn, slug: str, name: str, school: str,
                 season_year: int, games: list[dict], dry_run: bool) -> None:
    if dry_run:
        print(f"    games: {len(games)} row(s) (dry-run)")
        return
    now = datetime.now(timezone.utc)
    with conn.cursor() as cur:
        for g in games:
            cur.execute(
                """INSERT INTO njhs_player_games
                   (id, player_id, player_name, season_year, season_label, game_id,
                    game_date, game_type, league, team_for, team_against, opponent, result,
                    goals, assists, points, gwg, evg, ppg, shg, otg, pim, scraped_at)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'NJHS', %s, %s, %s, %s,
                           %s, %s, %s, %s, %s, %s, %s, %s, NULL, %s)
                   ON CONFLICT (player_id, season_year, game_id) DO UPDATE SET
                     player_name = EXCLUDED.player_name,
                     season_label = EXCLUDED.season_label,
                     game_date = EXCLUDED.game_date,
                     game_type = EXCLUDED.game_type,
                     team_for = EXCLUDED.team_for,
                     team_against = EXCLUDED.team_against,
                     opponent = EXCLUDED.opponent,
                     result = EXCLUDED.result,
                     goals = EXCLUDED.goals,
                     assists = EXCLUDED.assists,
                     points = EXCLUDED.points,
                     gwg = EXCLUDED.gwg,
                     evg = EXCLUDED.evg,
                     ppg = EXCLUDED.ppg,
                     shg = EXCLUDED.shg,
                     otg = EXCLUDED.otg,
                     scraped_at = EXCLUDED.scraped_at,
                     updated_at = NOW()""",
                (str(uuid4()), slug, name or "N/A", season_year,
                 season_label(season_year), g["game_id"], g["game_date"], None,
                 school, g["opponent"], g["opponent"], g["result"],
                 g["goals"], g["assists"], g["points"], g["gwg"], g["evg"],
                 g["ppg"], g["shg"], g["otg"], now),
            )
    conn.commit()


def scrape_player(conn, slug: str, season_year: int, all_seasons: bool = False,
                  dry_run: bool = False) -> None:
    """Scrape one player's career + game log. Default = the season's page (career
    table shows all seasons, game log shows the requested season). With
    --all-seasons, also fetch each season's page for its own game log."""
    label = season_label(season_year)
    url = f"{BASE}/player/{slug}/{SPORT}/season/{label}"
    try:
        soup = fetch_soup(url)
    except Exception as e:
        print(f"  {slug}: ERROR {e}")
        return
    school, name = parse_player_header(soup)
    career = parse_career_table(soup)
    games = parse_game_log(soup)
    print(f"  {slug}: {name or '?'} school={school or '?'} seasons={len(career)} games={len(games)}")

    # Career for the requested season, with games_played = this season's game log
    season_stats = next((c for c in career if c["season_label"] == label), {})
    upsert_career(conn, slug, name, school, label, season_year, len(games), season_stats, dry_run)
    if games:
        upsert_games(conn, slug, name, school, season_year, games, dry_run)

    if all_seasons:
        for c in career:
            sy = _to_int(c["season_label"][:4])
            if not sy or sy == season_year:
                continue
            try:
                csoup = fetch_soup(f"{BASE}/player/{slug}/{SPORT}/season/{season_label(sy)}")
            except Exception as e:
                print(f"    games[{c['season_label']}]: ERROR {e}")
                continue
            cgames = parse_game_log(csoup)
            upsert_career(conn, slug, name, school, c["season_label"], sy, len(cgames), c, dry_run)
            if cgames:
                upsert_games(conn, slug, name, school, sy, cgames, dry_run)
            print(f"    {c['season_label']}: G={c.get('goals')} A={c.get('assists')} P={c.get('points')} "
                  f"games={len(cgames)}")


def load_linked(conn) -> list[str]:
    """NJHS-linked users whose profile is high-school age + lives in NJ."""
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            """SELECT DISTINCT pim.source_player_id, pp.birthdate, pp.location
               FROM player_identity_map pim
               LEFT JOIN player_profiles pp ON pp.user_id = pim.user_id
               WHERE pim.source = 'NJHS' AND pim.link_state = 'CONFIRMED'""")
        rows = cur.fetchall()
    out = []
    for r in rows:
        ok, reason = njhs_guard.qualifies_for_njhs(r["birthdate"], r["location"])
        if ok:
            out.append(r["source_player_id"])
        else:
            print(f"  skipping {r['source_player_id']}: {reason}")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--roster", action="store_true", help="scrape all rosters into njhs_rosters")
    ap.add_argument("--stats", action="store_true", help="scrape all team stats into njhs_player_stats (for rankings)")
    ap.add_argument("--player-slug", help="scrape a single player slug")
    ap.add_argument("--season", type=int, help="season start year (default: latest)")
    ap.add_argument("--all-seasons", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    # Dry-run never touches the database.
    conn = None if args.dry_run else get_conn()
    try:
        if conn:
            ensure_tables(conn)
        season_year = args.season if args.season else current_season_year()
        if args.roster:
            total = scrape_rosters(conn, season_year, dry_run=args.dry_run)
            print(f"Roster scrape done: {total} player rows")
            return
        if args.stats:
            total = scrape_team_stats(conn, season_year, dry_run=args.dry_run)
            print(f"Team stats scrape done: {total} player rows")
            return
        if args.player_slug:
            scrape_player(conn, args.player_slug, season_year,
                          all_seasons=args.all_seasons, dry_run=args.dry_run)
            return
        slugs = load_linked(conn)
        if not slugs:
            print("No NJHS-linked users to deep-dive.")
            return
        print(f"Deep-diving {len(slugs)} NJHS player(s)...")
        for slug in slugs:
            scrape_player(conn, slug, season_year,
                          all_seasons=args.all_seasons, dry_run=args.dry_run)
    finally:
        if conn:
            conn.close()


if __name__ == "__main__":
    main()