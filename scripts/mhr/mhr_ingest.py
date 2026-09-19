#!/usr/bin/env python3
"""Copy MYHockeyRankings (MHR) game results into ``mhr_player_games``.

MHR only publishes team pages, so the games come from the team a player is
linked to in ``mhr_team_links`` (see :mod:`mhr_resolve`).  Rows are keyed by
``player_id = player_profiles.id`` so two siblings can never collide on the
``UNIQUE (player_id, season_year, game_id)`` constraint.

MHR carries no per-player stats, so goals/assists/points/pim stay NULL and the
team score is stored in ``score_for``/``score_against`` for the dashboard to
render as a result.  Only finished games are stored, and by default only
non-league games (exhibition, tournament, state/district/national) because
league games already come from the AYHL/THF scrapers.  A game that another
source already recorded for the same player, date and opponent is skipped.

Usage:
    python mhr_ingest.py                      # current season, all confirmed links
    python mhr_ingest.py --season 2026
    python mhr_ingest.py --player 8 --include-league
    python mhr_ingest.py --dry-run
"""
from __future__ import annotations

import argparse
import os
import re
from datetime import date, datetime, timezone
from uuid import uuid4

import psycopg2
import psycopg2.extras

from mhr_client import MhrClient, MhrError
from mhr_parse import (
    age_group_tokens,
    club_tokens,
    parse_team_page,
    season_label,
)

OTHER_TABLES = ["ayhl_player_games", "thf_player_games", "ahf_player_games", "njhs_player_games"]

TEAM_URL = "https://myhockeyrankings.com/team_info.php?y={year}&t={team}"

CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS mhr_player_games (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id     VARCHAR(128) NOT NULL,
    player_name   VARCHAR(255),
    season_year   INTEGER NOT NULL,
    season_label  VARCHAR(32) NOT NULL,
    game_id       VARCHAR(64) NOT NULL,
    game_date     DATE,
    game_type     VARCHAR(128),
    league        VARCHAR(255),
    team_for      VARCHAR(255),
    team_against  VARCHAR(255),
    score_for     INTEGER,
    score_against INTEGER,
    goals         INTEGER,
    assists       INTEGER,
    points        INTEGER,
    pim           INTEGER,
    source_url    TEXT,
    scraped_at    TIMESTAMP NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (player_id, season_year, game_id)
);
"""

_CANONICAL_RE = re.compile(r"[\s,.'-]")


def current_season_year() -> int:
    today = date.today()
    return today.year if today.month >= 9 else today.year - 1


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


def canonical_names(name: str) -> list[str]:
    """Lookup keys matching the backend's canonical name form ("Ethan Yan"
    -> "ethanyan", plus the reversed "yanethan")."""
    parts = [p for p in re.split(r"\s+", (name or "").strip()) if p]
    keys = {_CANONICAL_RE.sub("", (name or "").lower())}
    if len(parts) >= 2:
        keys.add(_CANONICAL_RE.sub("", (parts[-1] + parts[0]).lower()))
    keys.discard("")
    return sorted(keys)


def table_exists(conn, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) FROM information_schema.tables "
            "WHERE table_schema = current_schema() AND table_name = %s",
            (table,),
        )
        return cur.fetchone()[0] > 0


def ensure_table(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(CREATE_TABLE_SQL)
    conn.commit()


# -- duplicate detection against the other sources ---------------------------

def same_club(left: str, right: str) -> bool:
    """True when two team names look like the same club at the same age group."""
    if not (club_tokens(left) & club_tokens(right)):
        return False
    ages_left, ages_right = age_group_tokens(left), age_group_tokens(right)
    if ages_left and ages_right and not (ages_left & ages_right):
        return False
    return True


def load_recorded_games(conn, season_year: int, names: list[str]) -> list[tuple]:
    """(game_date, opponent) recorded by the other sources for this player."""
    rows: list[tuple] = []
    for table in OTHER_TABLES:
        if not table_exists(conn, table):
            continue
        with conn.cursor() as cur:
            cur.execute(
                f"""SELECT game_date, COALESCE(team_against, team_for) FROM {table}
                    WHERE season_year = %s
                      AND LOWER(REGEXP_REPLACE(COALESCE(player_name, ''), '[[:space:],.''-]', '', 'g'))
                          = ANY(%s)""",
                (season_year, names),
            )
            rows.extend(cur.fetchall())
    return rows


def already_recorded(recorded: list[tuple], game: dict) -> bool:
    for game_date, opponent in recorded:
        if game_date and game_date == game["game_date"] and same_club(opponent or "", game["team_against"] or ""):
            return True
    return False


# -- ingestion ---------------------------------------------------------------

def confirmed_links(conn, season_year: int, player_ids: list[int] | None) -> list[dict]:
    """Confirmed links collapsed to one row per identity.

    Duplicate profiles for the same skater share a canonical name, and the
    backend matches game rows by player_name, so storing one row per profile
    would surface the same game several times. The lowest profile id stands in
    as the representative for its identity.
    """
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            """SELECT MIN(l.player_id) AS player_id,
                      MIN(pp.full_name) AS full_name,
                      l.team_id,
                      MIN(l.team_name) AS team_name,
                      MIN(l.division) AS division
               FROM mhr_team_links l
               JOIN player_profiles pp ON pp.id = l.player_id
               WHERE l.season_year = %s
                 AND l.link_state = 'confirmed'
                 AND (%s IS NULL OR l.player_id = ANY(%s))
               GROUP BY LOWER(REGEXP_REPLACE(COALESCE(pp.full_name, ''), '[[:space:],.''-]', '', 'g')),
                        l.team_id
               ORDER BY full_name, l.team_id""",
            (season_year, player_ids, player_ids),
        )
        return [dict(row) for row in cur.fetchall()]


def upsert_games(conn, link: dict, season_year: int, games: list[dict], dry_run: bool) -> int:
    if dry_run or not games:
        return len(games)
    now = datetime.now(timezone.utc)
    url = TEAM_URL.format(year=season_year, team=link["team_id"])
    with conn.cursor() as cur:
        for game in games:
            cur.execute(
                """INSERT INTO mhr_player_games
                   (id, player_id, player_name, season_year, season_label, game_id, game_date,
                    game_type, league, team_for, team_against, score_for, score_against,
                    scraped_at, source_url)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (player_id, season_year, game_id) DO UPDATE SET
                     player_name = EXCLUDED.player_name,
                     season_label = EXCLUDED.season_label,
                     game_date = EXCLUDED.game_date,
                     game_type = EXCLUDED.game_type,
                     league = EXCLUDED.league,
                     team_for = EXCLUDED.team_for,
                     team_against = EXCLUDED.team_against,
                     score_for = EXCLUDED.score_for,
                     score_against = EXCLUDED.score_against,
                     source_url = EXCLUDED.source_url,
                     scraped_at = EXCLUDED.scraped_at,
                     updated_at = NOW()""",
                (
                    str(uuid4()), str(link["player_id"]), link["full_name"], season_year,
                    season_label(season_year), game["game_id"], game["game_date"],
                    game["game_type"], game["league"], game["team_for"], game["team_against"],
                    game["score_for"], game["score_against"], now, url,
                ),
            )
    conn.commit()
    return len(games)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--season", type=int, default=None, help="season start year (default: current)")
    parser.add_argument("--player", type=int, action="append", dest="players",
                        help="profile id (repeatable, default: all confirmed links)")
    parser.add_argument("--include-league", action="store_true",
                        help="also store league games (already covered by the AYHL/THF scrapers)")
    parser.add_argument("--dry-run", action="store_true", help="parse but do not write")
    args = parser.parse_args()

    season_year = args.season or current_season_year()
    label = season_label(season_year)
    conn = get_conn()
    try:
        ensure_table(conn)
        links = confirmed_links(conn, season_year, args.players)
        if not links:
            print(f"no confirmed MHR team link for {label} - run mhr_resolve.py first")
            return
        if args.dry_run:
            print("(dry-run: nothing is written)")

        pages: dict[str, str | None] = {}
        total = 0
        with MhrClient() as client:
            for link in links:
                team_id = link["team_id"]
                if team_id not in pages:
                    try:
                        pages[team_id] = client.fetch_team_page(season_year, team_id)
                    except MhrError as exc:
                        print(f"  {link['full_name']}: team {team_id} ERROR {exc}")
                        pages[team_id] = None
                html = pages[team_id]
                if not html:
                    continue

                parsed = parse_team_page(html, season_year, team_id, team_name=link["team_name"])
                division = parsed["division"] or link["division"] or ""
                recorded = load_recorded_games(conn, season_year, canonical_names(link["full_name"]))
                selected, skipped = [], {"played": 0, "league": 0, "recorded": 0}
                for game in parsed["games"]:
                    if not game["played"]:
                        skipped["played"] += 1
                        continue
                    if game["is_league"] and not args.include_league:
                        skipped["league"] += 1
                        continue
                    if already_recorded(recorded, game):
                        skipped["recorded"] += 1
                        continue
                    selected.append({**game, "league": division})
                print(f"  {link['full_name']}: t={team_id} {parsed['team_name']} | {division or '?'} | "
                      f"{len(parsed['games'])} game(s) -> {len(selected)} new "
                      f"(skipped {skipped['league']} league, {skipped['recorded']} recorded, "
                      f"{skipped['played']} unplayed)")
                for game in selected:
                    print(f"    {game['game_date']} {game['game_type']:<24} "
                          f"vs {game['team_against']:<32} {game['result'] or '?'}")
                total += upsert_games(conn, link, season_year, selected, args.dry_run)
        print(f"{'would store' if args.dry_run else 'stored'} {total} MHR game(s) for {label}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
