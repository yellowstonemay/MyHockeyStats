#!/usr/bin/env python3
"""THF / AHF deep-dive for registered users.

For each THF/AHF identity link:
  1. Roll the player's latest season stats from the raw roster tables
     (`thf_rosters` / `ahf_rosters`, populated by the weekly roster scrape) up
     into the career tables (`thf_player_career` / `ahf_player_career`), and
     record stat changes into the corresponding change-event table.
  2. Build the player's game-by-game history from the raw games tables
     (`thf_games` / `ahf_games`, populated by scrape_games.js) into
     `thf_player_games` / `ahf_player_games` (created on demand). Rows are
     UPSERTed on (player_id, season_year, game_id) — existing rows are never
     deleted.

The TimeToScore roster scrape is per-team, so for an individual linked player we
refresh from the already-scraped roster/games rows (no extra browser needed).

Usage:
    python thf_ahf_deep.py                   # all THF/AHF-linked users
    python thf_ahf_deep.py --source THF      # just one source
    python thf_ahf_deep.py --all-seasons     # career + games for all seasons
    python thf_ahf_deep.py --dry-run
"""
from __future__ import annotations

import argparse
import json
import os
import re
from datetime import date, datetime
from uuid import uuid4

import psycopg2
import psycopg2.extras

SEASON_LABEL_BY_YEAR = {
    2026: "2026-2027 Season",
    2025: "2025-2026 Season",
}


def current_season_year() -> int:
    """Current season start year (April–March cycle)."""
    today = date.today()
    return today.year if today.month >= 4 else today.year - 1

CONFIG = {
    "THF": {
        "roster_table": "thf_rosters",
        "career_table": "thf_player_career",
        "change_table": "thf_change_event",
        "games_table": "thf_games",
        "player_games_table": "thf_player_games",
    },
    "AHF": {
        "roster_table": "ahf_rosters",
        "career_table": "ahf_player_career",
        "change_table": "ahf_change_event",
        "games_table": "ahf_games",
        "player_games_table": "ahf_player_games",
    },
}

STAT_FIELDS = ["games_played", "goals", "assists", "points", "pim"]


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


def load_linked(conn, source: str, player_id: str | None = None) -> list[tuple[str, str]]:
    sql = """
        SELECT pim.source_player_id, COALESCE(pp.full_name, u.full_name, '')
        FROM player_identity_map pim
        LEFT JOIN users u ON u.id = pim.user_id
        LEFT JOIN player_profiles pp ON pp.user_id = pim.user_id
        WHERE pim.source = %s AND pim.link_state = 'CONFIRMED'
    """
    params: list = [source]
    if player_id:
        sql += " AND pim.source_player_id = %s"
        params.append(player_id)
    with conn.cursor() as cur:
        cur.execute(sql, params)
        rows = cur.fetchall()
    # Dedupe by player id (multiple users can link to the same source player)
    seen = {}
    for pid, name in rows:
        if pid not in seen:
            seen[pid] = name or ""
    return list(seen.items())


def latest_roster_stats(conn, source: str, player_id: str,
                        season_year: int | None = None) -> list[dict]:
    """Roster rows for a player from the raw roster table, optionally filtered
    to one season_year."""
    cfg = CONFIG[source]
    table = cfg["roster_table"]
    sql = f"""
        SELECT season_year, team_id, team_name, player_name, jersey, position,
               gp, goals, assists, points, pims, ppg, sog
        FROM {table}
        WHERE player_id = %s
    """
    params: list = [player_id]
    if season_year is not None:
        sql += " AND season_year = %s"
        params.append(season_year)
    sql += " ORDER BY season_year DESC"
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(sql, params)
        return [dict(r) for r in cur.fetchall()]


def upsert_career(conn, source: str, player_id: str, season_year: int,
                  label: str, row: dict, dry_run: bool) -> list[dict]:
    cfg = CONFIG[source]
    changes = []

    def as_int(v, fallback=0):
        try:
            return int(str(v).strip()) if str(v).strip() not in ("", "N/A") else fallback
        except (TypeError, ValueError):
            return fallback

    vals = {
        "games_played": as_int(row.get("gp")),
        "goals": as_int(row.get("goals")),
        "assists": as_int(row.get("assists")),
        "points": as_int(row.get("points")),
        "pim": as_int(row.get("pims")),
    }

    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            f"SELECT {', '.join(STAT_FIELDS)} FROM {cfg['career_table']} "
            "WHERE source_player_id=%s AND season_label=%s",
            (player_id, label),
        )
        old = cur.fetchone()
        if old:
            for col in STAT_FIELDS:
                o, n = (old[col] or 0), vals[col]
                if o != n:
                    changes.append({
                        "source_player_id": player_id,
                        "player_name": row.get("player_name") or "",
                        "season_label": label,
                        "field_name": col,
                        "old_value": str(old[col]) if old[col] is not None else "",
                        "new_value": str(n),
                    })

        if dry_run:
            return changes

        # Newly-created rows with all-zero stats have no real data yet — flag them
        # as user-editable so the GUI lets the player fill them in.
        is_empty = all(v == 0 for v in vals.values())

        # Manual upsert: thf/ahf_player_career do NOT have a unique constraint on
        # (source_player_id, season_label), so ON CONFLICT is unavailable.
        params = (
            row.get("player_name") or "", source,
            row.get("team_name"), str(row.get("team_id") or ""),
            row.get("jersey"), vals["games_played"], vals["goals"],
            vals["assists"], vals["points"], vals["pim"],
            player_id, label,
        )
        if old:
            cur.execute(
                f"""UPDATE {cfg['career_table']} SET
                      player_name = %s, league_name = %s, team_name = %s,
                      team_id = %s, jersey_number = %s,
                      games_played = %s, goals = %s, assists = %s,
                      points = %s, pim = %s,
                      last_scraped_at = NOW(), updated_at = NOW()
                    WHERE source_player_id = %s AND season_label = %s""",
                params,
            )
        else:
            cur.execute(
                f"""INSERT INTO {cfg['career_table']}
                    (id, source_player_id, player_name, season_label, league_name,
                     team_name, team_id, jersey_number, games_played, goals, assists,
                     points, pim, is_user_modified, last_scraped_at, created_at, updated_at)
                    VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW(), NOW())""",
                (player_id, row.get("player_name") or "", label, source,
                 row.get("team_name"), str(row.get("team_id") or ""),
                 row.get("jersey"), vals["games_played"], vals["goals"],
                 vals["assists"], vals["points"], vals["pim"], is_empty),
            )
        for ch in changes:
            cur.execute(
                f"""INSERT INTO {cfg['change_table']}
                    (id, source_player_id, player_name, season_label, field_name,
                     old_value, new_value, detected_at, event_type)
                    VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, NOW(), 'STAT_CHANGE')""",
                (ch["source_player_id"], ch["player_name"], ch["season_label"],
                 ch["field_name"], ch["old_value"], ch["new_value"]),
            )
    conn.commit()
    return changes


# ---------------------------------------------------------------------------
# Game-by-game history (build *_player_games from raw *_games via name match;
# UPSERT on (player_id, season_year, game_id) — never deletes existing rows)
# ---------------------------------------------------------------------------

CREATE_PLAYER_GAMES_SQL = """
CREATE TABLE IF NOT EXISTS {table} (
    id              UUID            PRIMARY KEY,
    player_id       VARCHAR(128)    NOT NULL,
    player_name     VARCHAR(255),
    season_year     INTEGER         NOT NULL,
    season_label    VARCHAR(32)     NOT NULL,
    game_id         VARCHAR(64)     NOT NULL,
    game_date       DATE,
    game_type       VARCHAR(128),
    league          VARCHAR(255),
    team_for        VARCHAR(255),
    team_against    VARCHAR(255),
    goals           INTEGER,
    assists         INTEGER,
    points          INTEGER,
    pim             INTEGER,
    scraped_at      TIMESTAMP       NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (player_id, season_year, game_id)
);
"""


def _canonical_name(value: str) -> str:
    """Lowercase, drop spaces/commas/dots/hyphens/apostrophes (mirrors the
    backend GameHistoryLookupService matching)."""
    return re.sub(r"[\s,.'-]", "", (value or "").lower())


def _canonical_keys(player_name: str) -> set[str]:
    base = _canonical_name(player_name)
    keys = {base} if base else set()
    parts = re.split(r"[\s,]+", (player_name or "").strip().lower())
    if len(parts) == 2 and all(parts):
        keys.add(_canonical_name(parts[1] + "," + parts[0]))  # "last,first"
        keys.add(_canonical_name(parts[1] + parts[0]))        # "lastfirst"
    return keys


def ensure_player_games_table(conn, source: str) -> None:
    cfg = CONFIG[source]
    with conn.cursor() as cur:
        cur.execute(CREATE_PLAYER_GAMES_SQL.format(table=cfg["player_games_table"]))
    conn.commit()


def upsert_player_games(conn, source: str, player_id: str, player_name: str,
                        season_year: int | None = None,
                        dry_run: bool = False) -> int:
    """Build a linked player's game-by-game history from the raw games table.

    Matches the player by canonical name inside each game's scoresheet_json
    (home_roster / visitor_roster). Rows are UPSERTed on
    (player_id, season_year, game_id); rows already present are updated, never
    deleted. season_year=None means all seasons.
    """
    cfg = CONFIG[source]
    games_table = cfg["games_table"]
    table = cfg["player_games_table"]

    keys = _canonical_keys(player_name)
    if not keys:
        print(f"    games: skipped (no player name to match)")
        return 0

    # In default (current-season) mode the games table may lag behind the
    # roster season — use the latest season_year that actually has games so the
    # daily run refreshes the most recent game history. None => all seasons.
    if season_year is not None:
        with conn.cursor() as cur:
            cur.execute(f"SELECT MAX(season_year) FROM {games_table}")
            max_year = cur.fetchone()[0]
        season_year = int(max_year) if max_year is not None else season_year

    sql = f"SELECT game_id, season_year, game_date, home_team, away_team, league_name, scoresheet_json FROM {games_table}"
    params: list = []
    if season_year is not None:
        sql += " WHERE season_year = %s"
        params.append(season_year)
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(sql, params)
        game_rows = [dict(r) for r in cur.fetchall()]

    now = datetime.now()
    matched: list[dict] = []
    for gr in game_rows:
        j = gr.get("scoresheet_json")
        if not j:
            continue
        try:
            data = json.loads(j) if isinstance(j, str) else j
        except (TypeError, ValueError):
            continue
        if not isinstance(data, dict):
            continue
        game_year = int(gr["season_year"])
        label = f"{game_year}-{game_year + 1} Season"
        found = False
        for side, team_key in (("home_roster", "home_team"), ("visitor_roster", "visitor_team")):
            if found:
                break
            roster = data.get(side) or {}
            team_name = data.get(team_key) or ""
            other_team = data.get("visitor_team" if side == "home_roster" else "home_team") or ""
            if not isinstance(roster, dict):
                continue
            for entry in roster.values():
                if not isinstance(entry, dict):
                    continue
                nm = entry.get("name") or ""
                if not nm or _canonical_name(nm) not in keys:
                    continue
                matched.append({
                    "player_id": player_id,
                    "player_name": nm,
                    "season_year": game_year,
                    "season_label": label,
                    "game_id": str(gr["game_id"]),
                    "game_date": gr.get("game_date"),
                    "game_type": None,
                    "league": gr.get("league_name") or source,
                    "team_for": team_name,
                    "team_against": other_team,
                    "goals": entry.get("goals"),
                    "assists": entry.get("assists"),
                    "points": entry.get("points"),
                    "pim": entry.get("pims"),
                })
                found = True
                break

    if dry_run:
        if matched:
            print(f"    games: {len(matched)} matching game(s) (dry-run)")
        return len(matched)

    with conn.cursor() as cur:
        for g in matched:
            cur.execute(
                f"""INSERT INTO {table}
                    (id, player_id, player_name, season_year, season_label, game_id,
                     game_date, game_type, league, team_for, team_against,
                     goals, assists, points, pim, scraped_at)
                    VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                    ON CONFLICT (player_id, season_year, game_id) DO UPDATE SET
                      player_name = EXCLUDED.player_name,
                      season_label = EXCLUDED.season_label,
                      game_date = EXCLUDED.game_date,
                      game_type = EXCLUDED.game_type,
                      league = EXCLUDED.league,
                      team_for = EXCLUDED.team_for,
                      team_against = EXCLUDED.team_against,
                      goals = EXCLUDED.goals,
                      assists = EXCLUDED.assists,
                      points = EXCLUDED.points,
                      pim = EXCLUDED.pim,
                      scraped_at = EXCLUDED.scraped_at,
                      updated_at = NOW()""",
                (str(uuid4()), g["player_id"], g["player_name"], g["season_year"],
                 g["season_label"], g["game_id"], g["game_date"], g["game_type"],
                 g["league"], g["team_for"], g["team_against"], g["goals"],
                 g["assists"], g["points"], g["pim"], now),
            )
    conn.commit()
    if matched:
        print(f"    games: upserted {len(matched)} row(s)")
    return len(matched)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", choices=["THF", "AHF"], help="only this source")
    ap.add_argument("--player-id", help="only this player id")
    ap.add_argument("--all-seasons", action="store_true",
                    help="process ALL seasons (default: current season only)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    conn = get_conn()
    try:
        sources = ["THF", "AHF"] if not args.source else [args.source]
        cur_year = current_season_year()
        scope_year = None if args.all_seasons else cur_year
        for source in sources:
            ensure_player_games_table(conn, source)
            links = load_linked(conn, source, player_id=args.player_id)
            print(f"[{source}] {len(links)} linked user(s)")
            for player_id, pname in links:
                season_year = scope_year
                rows = latest_roster_stats(conn, source, player_id, season_year=season_year)
                if not rows:
                    print(f"  {player_id}: no roster rows")
                total_changes = 0
                for row in rows:
                    season_year = int(row["season_year"])
                    label = SEASON_LABEL_BY_YEAR.get(season_year, f"{season_year}-{season_year + 1} Season")
                    changes = upsert_career(conn, source, player_id, season_year, label, row, args.dry_run)
                    total_changes += len(changes)
                    print(f"  {player_id}: {row.get('player_name')} {row.get('team_name')} {label} "
                          f"G={row.get('goals')} A={row.get('assists')} PTS={row.get('points')}"
                          f" -> {len(changes)} change(s){' (dry-run)' if args.dry_run else ''}")
                if total_changes == 0 and not args.dry_run:
                    print(f"  {player_id}: no stat changes")
                # Game-by-game history (upsert into *_player_games; never deletes)
                upsert_player_games(conn, source, player_id, pname,
                                    season_year=scope_year, dry_run=args.dry_run)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
