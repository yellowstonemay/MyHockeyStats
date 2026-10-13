#!/usr/bin/env python3
"""Fill AYHL game RESULTS (team scores) into ``ayhl_player_games``.

The AYHL "Statistics By Game" table on a player page carries per-player stats but
**no score**, which is why the Result column was blank for every AYHL row. The
league scoreboard does publish scores:

    https://atlantichockey.org/scores.php?seasonid=<seasonid>

and its ``GAME#`` column is the SAME id we already store in
``ayhl_player_games.game_id`` (e.g. 46108), so this needs no fuzzy matching.

The page lists at most ~100 games (the most recent). That is the whole current
season early on; for older seasons only the most recent ~100 games get scores.

Score direction: the scoreboard gives AWAY/GF and HOME/GF. A stored row's
``team_for`` tells us which side the player's team was on, so ``score_for`` /
``score_against`` are derived per row.

Usage:
    python scrape_scores.py                 # current season
    python scrape_scores.py --season 34     # explicit seasonid
    python scrape_scores.py --dry-run
"""
from __future__ import annotations

import argparse
import re
import time
from datetime import datetime, timezone

import psycopg2

SEASON_URL = "https://atlantichockey.org/scores.php?seasonid={season}"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

# seasonid values we know about (mirrors scripts/ayhl/discover_teams.py)
SEASON_TO_ID = {
    2010: 16, 2011: 17, 2012: 19, 2013: 20, 2014: 21, 2015: 22, 2016: 23,
    2017: 24, 2018: 25, 2019: 26, 2020: 27, 2021: 28, 2022: 30, 2023: 31,
    2024: 32, 2025: 33, 2026: 34,
}


def current_season_year() -> int:
    """APRIL cutover — the same rule as every other scraper and the Dashboard."""
    today = datetime.now()
    return today.year if today.month >= 4 else today.year - 1


def get_conn():
    import os
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


def fetch_html(url: str, timeout: int = 45000) -> str:
    """Fetch with playwright+stealth (atlantichockey.org is Cloudflare-protected)."""
    try:
        from playwright.sync_api import sync_playwright
        from playwright_stealth import Stealth

        stealth = Stealth()
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            ctx = browser.new_context(user_agent=UA, viewport={"width": 1400, "height": 900})
            page = ctx.new_page()
            stealth.apply_stealth_sync(page)
            page.set_default_navigation_timeout(timeout)
            page.goto(url, wait_until="load")
            page.wait_for_timeout(2000)
            html = page.content()
            browser.close()
            return html
    except Exception as exc:  # noqa: BLE001
        print(f"  (playwright failed: {exc}; trying requests)")
        import requests

        resp = requests.get(url, headers={"User-Agent": UA}, timeout=30)
        resp.raise_for_status()
        return resp.text


def _text(cell_html: str) -> str:
    txt = re.sub(r"<[^>]+>", " ", cell_html)
    txt = txt.replace("&nbsp;", " ").replace("&amp;", "&")
    return re.sub(r"\s+", " ", txt).strip()


def parse_scoreboard(html: str) -> list[dict]:
    """Parse the structured 'GAME# | LEAGUE | AWAY | GF | HOME | GF | DATE | SCORE' table."""
    tables = re.findall(r"<table\b.*?</table>", html, re.IGNORECASE | re.DOTALL)
    structured = [t for t in tables if "GAME#" in t]
    if not structured:
        return []

    games: list[dict] = []
    rows = re.findall(r"<tr\b.*?</tr>", structured[-1], re.IGNORECASE | re.DOTALL)
    for row in rows:
        cells = [_text(c) for c in re.findall(r"<td\b[^>]*>(.*?)</td>", row, re.IGNORECASE | re.DOTALL)]
        if len(cells) < 8 or not cells[0].isdigit():
            continue
        game_id, league, away, away_gf, home, home_gf, date_txt, score_txt = cells[:8]
        if not (away_gf.isdigit() and home_gf.isdigit()):
            continue
        games.append({
            "game_id": game_id,
            "league": league or None,
            "away": away or None,
            "away_gf": int(away_gf),
            "home": home or None,
            "home_gf": int(home_gf),
            "game_date": date_txt or None,
            "score": score_txt or None,
        })
    return games


def ensure_columns(conn) -> None:
    with conn.cursor() as cur:
        cur.execute("ALTER TABLE ayhl_player_games ADD COLUMN IF NOT EXISTS score_for INTEGER")
        cur.execute("ALTER TABLE ayhl_player_games ADD COLUMN IF NOT EXISTS score_against INTEGER")
    conn.commit()


def _norm(name: str) -> str:
    """Loose team-name key: lowercase, drop punctuation/age tokens and 'premier/elite' suffixes."""
    txt = (name or "").lower()
    txt = re.sub(r"[^a-z0-9 ]", " ", txt)
    txt = re.sub(r"\b\d{1,2}u\b|\bpure\b|\bmajor\b|\bminor\b|\bpremier\b|\belite\b|\bprep\b", " ", txt)
    return re.sub(r"\s+", " ", txt).strip()


def _same_team(a: str, b: str) -> bool:
    na, nb = _norm(a), _norm(b)
    if not na or not nb:
        return False
    return na == nb or na in nb or nb in na


def apply_scores(conn, games: list[dict], dry_run: bool = False) -> tuple[int, int]:
    """Write score_for/score_against onto stored game rows, matched by game_id."""
    matched_games = 0
    matched_rows = 0
    for game in games:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, team_for FROM ayhl_player_games WHERE game_id = %s",
                (game["game_id"],),
            )
            rows = cur.fetchall()
        if not rows:
            continue
        matched_games += 1
        for row_id, team_for in rows:
            if _same_team(team_for, game["away"]):
                score_for, score_against = game["away_gf"], game["home_gf"]
            elif _same_team(team_for, game["home"]):
                score_for, score_against = game["home_gf"], game["away_gf"]
            else:
                # Unknown side (renamed team) — fall back to away/home orientation.
                score_for, score_against = game["away_gf"], game["home_gf"]
            if dry_run:
                matched_rows += 1
                continue
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE ayhl_player_games SET score_for = %s, score_against = %s WHERE id = %s",
                    (score_for, score_against, row_id),
                )
            matched_rows += 1
    if not dry_run:
        conn.commit()
    return matched_games, matched_rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--season", type=int, default=None,
                        help="AYHL seasonid (default: the current season)")
    parser.add_argument("--season-year", type=int, default=None,
                        help="season start year (e.g. 2026) — converted to a seasonid")
    parser.add_argument("--delay", type=float, default=2.0, help="seconds between fetches")
    parser.add_argument("--dry-run", action="store_true", help="parse but do not write")
    args = parser.parse_args()

    season_id = args.season
    if season_id is None:
        year = args.season_year or current_season_year()
        season_id = SEASON_TO_ID.get(year)
        if season_id is None:
            raise SystemExit(f"unknown season year {year} — pass --season <seasonid>")

    url = SEASON_URL.format(season=season_id)
    print(f"Fetching AYHL scoreboard: {url}")
    html = fetch_html(url)
    games = parse_scoreboard(html)
    print(f"  parsed {len(games)} scoreboard row(s)")
    if not games:
        raise SystemExit("no scoreboard rows parsed — page layout may have changed")

    conn = get_conn()
    try:
        if not args.dry_run:
            ensure_columns(conn)
        matched_games, matched_rows = apply_scores(conn, games, args.dry_run)
        print(f"  matched {matched_games} game(s) already in ayhl_player_games "
              f"-> {matched_rows} row(s) {'would be ' if args.dry_run else ''}updated")
    finally:
        conn.close()

    time.sleep(args.delay)


if __name__ == "__main__":
    main()
