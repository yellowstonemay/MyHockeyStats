#!/usr/bin/env python3
"""THF / AHF deep-dive for registered users.

For each THF/AHF identity link, roll the player's latest season stats from the
raw roster tables (`thf_rosters` / `ahf_rosters`, populated by the weekly roster
scrape) up into the career tables (`thf_player_career` / `ahf_player_career`),
and record stat changes into the corresponding change-event table.

The TimeToScore roster scrape is per-team, so for an individual linked player we
refresh from the already-scraped roster rows (no extra browser needed here).
Multi-season career + game-by-game history for THF/AHF is handled by the games
pipeline (scrape_games.js) as a separate step.

Usage:
    python thf_ahf_deep.py                   # all THF/AHF-linked users
    python thf_ahf_deep.py --source THF      # just one source
    python thf_ahf_deep.py --dry-run
"""
from __future__ import annotations

import argparse
import os
from datetime import datetime

import psycopg2
import psycopg2.extras

SEASON_LABEL_BY_YEAR = {
    2026: "2026-2027 Season",
    2025: "2025-2026 Season",
}

CONFIG = {
    "THF": {
        "roster_table": "thf_rosters",
        "career_table": "thf_player_career",
        "change_table": "thf_change_event",
    },
    "AHF": {
        "roster_table": "ahf_rosters",
        "career_table": "ahf_player_career",
        "change_table": "ahf_change_event",
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


def load_linked(conn, source: str) -> list[tuple[str, str]]:
    sql = """
        SELECT pim.source_player_id, COALESCE(pp.full_name, u.full_name, '')
        FROM player_identity_map pim
        LEFT JOIN users u ON u.id = pim.user_id
        LEFT JOIN player_profiles pp ON pp.user_id = pim.user_id
        WHERE pim.source = %s AND pim.link_state = 'CONFIRMED'
    """
    with conn.cursor() as cur:
        cur.execute(sql, (source,))
        rows = cur.fetchall()
    # Dedupe by player id (multiple users can link to the same source player)
    seen = {}
    for pid, name in rows:
        if pid not in seen:
            seen[pid] = name or ""
    return list(seen.items())


def latest_roster_stats(conn, source: str, player_id: str) -> list[dict]:
    """Latest-season roster rows for a player from the raw roster table."""
    cfg = CONFIG[source]
    table = cfg["roster_table"]
    sql = f"""
        SELECT season_year, team_id, team_name, player_name, jersey, position,
               gp, goals, assists, points, pims, ppg, sog
        FROM {table}
        WHERE player_id = %s
        ORDER BY season_year DESC
    """
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(sql, (player_id,))
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
                     points, pim, last_scraped_at, created_at, updated_at)
                    VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW(), NOW())""",
                (player_id, row.get("player_name") or "", label, source,
                 row.get("team_name"), str(row.get("team_id") or ""),
                 row.get("jersey"), vals["games_played"], vals["goals"],
                 vals["assists"], vals["points"], vals["pim"]),
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


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", choices=["THF", "AHF"], help="only this source")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    conn = get_conn()
    try:
        sources = ["THF", "AHF"] if not args.source else [args.source]
        for source in sources:
            links = load_linked(conn, source)
            print(f"[{source}] {len(links)} linked user(s)")
            for player_id, _label in links:
                rows = latest_roster_stats(conn, source, player_id)
                if not rows:
                    print(f"  {player_id}: no roster rows")
                    continue
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
    finally:
        conn.close()


if __name__ == "__main__":
    main()
