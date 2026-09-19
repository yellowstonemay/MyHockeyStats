#!/usr/bin/env python3
"""Resolve registered players to their MYHockeyRankings (MHR) team.

MHR publishes team-level pages only (no rosters, no player stats), so a player's
games are read from the schedule page of the team they played for.  This script
works out *which* MHR team that is for a season and stores the mapping in
``mhr_team_links``; :mod:`mhr_ingest` then copies the games.

Resolution ladder:
1. the club name and age group come from the player's own career rows
   (``ayhl_player_career`` / ``thf_player_career`` / ``ahf_player_career``) for
   that season only - plus the birth date (MHR age groups are ``season_year -
   birth_year``).  Profiles are grouped by name + birth date, because the
   database holds duplicates for the same player;
2. MHR's team search ANDs every query token against the team name, so tokens are
   dropped from the end of the club name until the search returns hits
   ("New Jersey Devils Youth Premier 15U" -> nothing, "New Jersey Devils 15U"
   -> the team);
3. hits are scored on club overlap, age group and league token, and the best
   one's team page is loaded for its division.  A link is auto-confirmed when
   the division names the same league (AYHL/THF/AHF) and the club name
   overlaps; everything else is stored as a candidate for manual review.

``team_links.json`` next to this script holds hand-verified overrides:
``{"<profile id>": {"<season year>": {"team_id": "1205"}}}``.  Those are always
written as confirmed and are never overwritten by an automatic result.

Usage:
    python mhr_resolve.py --list
    python mhr_resolve.py --season 2026
    python mhr_resolve.py --season 2026 --player 8 --dry-run
"""
from __future__ import annotations

import argparse
import json
import os
from datetime import date, datetime, timezone
from pathlib import Path
from uuid import uuid4

import psycopg2
import psycopg2.extras
from bs4 import BeautifulSoup

from mhr_client import MhrClient, MhrError
from mhr_parse import (
    GENERIC_CLUB_TOKENS,
    age_group_tokens,
    club_tokens,
    name_tokens,
    parse_division_name,
    parse_team_name,
    season_label,
    tier_tokens,
)

OVERRIDES_FILE = Path(__file__).with_name("team_links.json")

CAREER_TABLES = {
    "AYHL": "ayhl_player_career",
    "THF": "thf_player_career",
    "AHF": "ahf_player_career",
}

MAX_SEARCHES_PER_PLAYER = 12
MAX_DIVISION_FETCHES = 2
AUTO_CONFIRM_MIN_OVERLAP = 0.6

CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS mhr_team_links (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id        BIGINT NOT NULL,
    player_name      VARCHAR(255),
    season_year      INTEGER NOT NULL,
    season_label     VARCHAR(32) NOT NULL,
    team_id          VARCHAR(32) NOT NULL,
    team_name        VARCHAR(255),
    division         VARCHAR(255),
    source_league    VARCHAR(16),
    team_url         TEXT,
    match_method     VARCHAR(32) NOT NULL DEFAULT 'auto',
    confidence_score NUMERIC(4,2),
    link_state       VARCHAR(16) NOT NULL DEFAULT 'candidate',
    resolved_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (player_id, season_year, team_id)
);
"""


def current_season_year() -> int:
    """MHR labels a season by the year it starts in (2026 = "2026-27") and youth
    seasons start in the fall, so before September we are still in the old one."""
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


def ensure_table(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(CREATE_TABLE_SQL)
    conn.commit()


def table_exists(conn, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) FROM information_schema.tables "
            "WHERE table_schema = current_schema() AND table_name = %s",
            (table,),
        )
        return cur.fetchone()[0] > 0


def load_overrides() -> dict:
    if not OVERRIDES_FILE.exists():
        return {}
    try:
        return json.loads(OVERRIDES_FILE.read_text())
    except ValueError as exc:
        print(f"  WARNING: ignoring {OVERRIDES_FILE.name}: {exc}")
        return {}


# -- candidate scoring -------------------------------------------------------

def club_overlap(club: str, other: str) -> float:
    """Fraction of the club's distinctive words that appear in ``other``."""
    words = club_tokens(club)
    if not words:
        return 0.0
    return len(words & club_tokens(other)) / len(words)


def score_candidate(club: str, league: str, name: str, division: str) -> tuple[float, float]:
    """Return ``(confidence, club_overlap)`` for an MHR team matching ``club``."""
    overlap = club_overlap(club, name)
    text = f"{name} {division or ''}".lower()
    score = 4.0 * overlap + 1.0  # age group is a hard filter, already matched
    if league.lower() in text:
        score += 3.0
    if age_group_tokens(name) & age_group_tokens(division or ""):
        score += 1.0
    if tier_tokens(club) & (tier_tokens(name) | tier_tokens(division or "")):
        score += 1.0
    return round(min(score, 10.0) / 10.0, 2), overlap


def is_confident(club: str, league: str, name: str, division: str) -> bool:
    return (
        club_overlap(club, name) >= AUTO_CONFIRM_MIN_OVERLAP
        and league.lower() in f"{name} {division or ''}".lower()
    )


# -- player clubs ------------------------------------------------------------

def load_players(conn, player_ids: list[int] | None) -> list[dict]:
    """Every profile that belongs to a user account, grouped by identity.

    The database holds duplicate profiles for the same player (same name and
    birth date), so profiles are grouped by name+birth date and resolved once;
    the resolved link is then stored for each profile id in the group.
    """
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            """SELECT DISTINCT pp.id, pp.full_name, pp.birthdate
               FROM player_profiles pp
               JOIN user_players up ON up.player_id = pp.id
               WHERE (%s IS NULL OR pp.id = ANY(%s))
               ORDER BY pp.id""",
            (player_ids, player_ids),
        )
        rows = [dict(row) for row in cur.fetchall()]

    groups: dict[tuple, dict] = {}
    for row in rows:
        key = ((row["full_name"] or "").strip().lower(), row["birthdate"])
        group = groups.setdefault(key, {
            "full_name": row["full_name"],
            "birthdate": row["birthdate"],
            "player_ids": [],
        })
        group["player_ids"].append(row["id"])
    return list(groups.values())


def load_clubs(conn, player_ids: list[int], season_year: int) -> list[dict]:
    """Clubs the player is registered with for a season, from the career tables.

    Only the requested season counts: an older season would name a team the
    player has since left.
    """
    label = season_label(season_year)
    clubs: list[dict] = []
    for player_id in player_ids:
        with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
            cur.execute(
                "SELECT source, source_player_id FROM player_source_links WHERE player_id = %s",
                (player_id,),
            )
            links = [dict(row) for row in cur.fetchall()]

        for link in links:
            table = CAREER_TABLES.get((link["source"] or "").upper())
            if not table or not table_exists(conn, table):
                continue
            with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
                cur.execute(
                    f"SELECT team_name, league_name FROM {table} "
                    "WHERE source_player_id = %s AND season_label = %s AND team_name IS NOT NULL",
                    (link["source_player_id"], label),
                )
                rows = [dict(row) for row in cur.fetchall()]
            for row in rows:
                clubs.append({
                    "club": row["team_name"],
                    "league": (row["league_name"] or link["source"] or "").upper(),
                    "source": (link["source"] or "").upper(),
                })
    unique: dict[str, dict] = {}
    for club in clubs:
        unique.setdefault(club["club"].lower(), club)
    return list(unique.values())


# -- MHR lookup --------------------------------------------------------------

def search_ladder(client, club: str, age: int, budget: dict) -> list[dict]:
    """Search MHR for ``<club prefix> <age>U``, dropping trailing club words
    while the search stays empty (MHR requires every query token in the name).

    The ladder runs down to the single most distinctive word: clubs are often
    indexed under a shorter name than the league registers them, e.g. "Rockets
    Hockey Club 11U" is "New Jersey Rockets 11U AAA" on MHR.
    """
    tokens = [tok for tok in name_tokens(club) if tok not in age_group_tokens(club)]
    age_token = f"{age}u"
    for end in range(len(tokens), 0, -1):
        prefix = tokens[:end]
        if len(prefix) == 1 and prefix[0] in GENERIC_CLUB_TOKENS:
            continue
        if budget["searches"] <= 0:
            print("    search budget exhausted")
            return []
        query = f"{' '.join(prefix)} {age_token}"
        budget["searches"] -= 1
        try:
            hits = [
                hit for hit in client.search_teams(query)
                if age_token in age_group_tokens(hit["name"])
            ]
        except MhrError as exc:
            print(f"    search '{query}': ERROR {exc}")
            continue
        print(f"    search '{query}': {len(hits)} hit(s)")
        if hits:
            return hits
    return []


def fetch_team_facts(client, season_year: int, team_id: str, budget: dict) -> tuple[str, str]:
    """Return ``(team_name, division)`` from the team page (empty strings on error)."""
    budget["fetches"] -= 1
    try:
        html = client.fetch_team_page(season_year, team_id)
    except MhrError as exc:
        print(f"      team {team_id}: ERROR {exc}")
        return "", ""
    soup = BeautifulSoup(html, "html.parser")
    return parse_team_name(html, soup) or "", parse_division_name(soup) or ""


def resolve_player(client, conn, player: dict, season_year: int, overrides: dict,
                   dry_run: bool) -> list[dict]:
    """Return the link records resolved for one player identity (0, 1 or more)."""
    label = season_label(season_year)
    override = (overrides.get(str(player["player_ids"][0])) or {}).get(str(season_year))
    if override and override.get("team_id"):
        division = ""
        if not dry_run and override.get("verify", True):
            _, division = fetch_team_facts(
                client, season_year, str(override["team_id"]),
                {"fetches": MAX_DIVISION_FETCHES, "searches": MAX_SEARCHES_PER_PLAYER},
            )
        print(f"  {player['full_name']}: manual override t={override['team_id']} "
              f"({division or override.get('division') or 'division unknown'})")
        return [{
            "team_id": str(override["team_id"]),
            "team_name": override.get("team_name"),
            "division": division or override.get("division"),
            "source_league": override.get("league"),
            "match_method": "manual",
            "confidence_score": 1.0,
            "link_state": "confirmed",
        }]

    birthdate = player["birthdate"]
    if not birthdate:
        print(f"  {player['full_name']}: no birth date, skipping")
        return []
    age = season_year - birthdate.year
    clubs = load_clubs(conn, player["player_ids"], season_year)
    if not clubs:
        print(f"  {player['full_name']}: no career club for {label}, skipping "
              f"(the {label} career rows must be scraped first)")
        return []

    budget = {"searches": MAX_SEARCHES_PER_PLAYER, "fetches": MAX_DIVISION_FETCHES}
    best: dict | None = None
    for club in clubs:
        print(f"  {player['full_name']}: club '{club['club']}' ({club['source']}) -> {age}U")
        for age_try in (age, age + 1, age - 1):
            hits = search_ladder(client, club["club"], age_try, budget)
            if not hits:
                continue
            ranked = sorted(
                hits,
                key=lambda h: score_candidate(club["club"], club["league"], h["name"], "")[0],
                reverse=True,
            )
            for hit in ranked:
                if budget["fetches"] <= 0:
                    break
                name, division = fetch_team_facts(client, season_year, hit["team_id"], budget)
                name = name or hit["name"]
                confidence, overlap = score_candidate(club["club"], club["league"], name, division)
                record = {
                    "team_id": hit["team_id"],
                    "team_name": name,
                    "division": division,
                    "source_league": club["source"],
                    "match_method": "auto",
                    "confidence_score": confidence,
                    "link_state": "confirmed" if is_confident(
                        club["club"], club["league"], name, division
                    ) else "candidate",
                }
                print(f"    t={hit['team_id']} {name} | {division or '?'} | "
                      f"overlap={overlap:.2f} confidence={confidence} -> {record['link_state']}")
                if record["link_state"] == "confirmed":
                    return [record]
                if best is None or confidence > best["confidence_score"]:
                    best = record
            if best and best["confidence_score"] >= 0.8:
                break
    return [best] if best else []


def save_link(conn, player: dict, season_year: int, record: dict, dry_run: bool) -> None:
    for player_id in player["player_ids"]:
        save_link_for(conn, player_id, player["full_name"], season_year, record, dry_run)


def save_link_for(conn, player_id: int, player_name: str, season_year: int, record: dict,
                  dry_run: bool) -> None:
    if dry_run:
        print(f"    (dry-run) profile {player_id}: {record['link_state']} t={record['team_id']} "
              f"{record['team_name']} | {record['division']} | confidence={record['confidence_score']}")
        return
    with conn.cursor() as cur:
        cur.execute(
            """INSERT INTO mhr_team_links
               (id, player_id, player_name, season_year, season_label, team_id, team_name,
                division, source_league, team_url, match_method, confidence_score, link_state,
                resolved_at, created_at, updated_at)
               VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW(), NOW())
               ON CONFLICT (player_id, season_year, team_id) DO UPDATE SET
                 team_name = EXCLUDED.team_name,
                 division = EXCLUDED.division,
                 source_league = EXCLUDED.source_league,
                 team_url = EXCLUDED.team_url,
                 match_method = CASE WHEN mhr_team_links.match_method = 'manual'
                                     THEN mhr_team_links.match_method
                                     ELSE EXCLUDED.match_method END,
                 confidence_score = EXCLUDED.confidence_score,
                 link_state = CASE WHEN mhr_team_links.match_method = 'manual'
                                   THEN mhr_team_links.link_state
                                   ELSE EXCLUDED.link_state END,
                 resolved_at = EXCLUDED.resolved_at,
                 updated_at = NOW()""",
            (
                str(uuid4()), player_id, player_name, season_year,
                season_label(season_year), record["team_id"], record["team_name"],
                record["division"], record["source_league"],
                f"https://myhockeyrankings.com/team_info.php?y={season_year}&t={record['team_id']}",
                record["match_method"], record["confidence_score"], record["link_state"],
            ),
        )
    conn.commit()


def list_links(conn) -> None:
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            """SELECT l.player_id, l.player_name, l.season_label, l.team_id, l.team_name,
                      l.division, l.source_league, l.link_state, l.match_method,
                      l.confidence_score, l.resolved_at
               FROM mhr_team_links l
               ORDER BY l.player_name, l.season_year DESC"""
        )
        rows = cur.fetchall()
    if not rows:
        print("mhr_team_links is empty")
        return
    for row in rows:
        print(f"{row['player_id']:>3} {row['player_name']:<20} {row['season_label']:<18} "
              f"t={row['team_id']:<7} {row['link_state']:<9} {row['match_method']:<6} "
              f"{row['confidence_score']} {row['team_name']} | {row['division']}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--season", type=int, default=None, help="season start year (default: current)")
    parser.add_argument("--player", type=int, action="append", dest="players",
                        help="profile id (repeatable, default: all user profiles)")
    parser.add_argument("--list", action="store_true", help="show stored links and exit")
    parser.add_argument("--dry-run", action="store_true", help="resolve but do not write")
    args = parser.parse_args()

    season_year = args.season or current_season_year()
    conn = get_conn()
    try:
        if args.list:
            if not table_exists(conn, "mhr_team_links"):
                print("mhr_team_links does not exist yet")
                return
            list_links(conn)
            return

        ensure_table(conn)
        players = load_players(conn, args.players)
        if not players:
            print("no player profiles to resolve")
            return
        overrides = load_overrides()
        print(f"Season {season_label(season_year)} - {len(players)} profile(s)")
        if args.dry_run:
            print("(dry-run: nothing is written)")
        with MhrClient() as client:
            for player in players:
                records = resolve_player(client, conn, player, season_year, overrides, args.dry_run)
                for record in records:
                    save_link(conn, player, season_year, record, args.dry_run)
                if not records:
                    print(f"    nothing resolved for {player['full_name']}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
