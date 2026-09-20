#!/usr/bin/env python3
"""Batch identity auto-linker: player profiles -> source player records.

Links are stored per PLAYER (player_source_links), not per login. A player can
be attached to several logins, so a scrape is only ever needed once per player —
that is what lets the deep-dive refresh "a player who may belong to 1-many
logins".

For every player profile, find their source player record(s) across
AYHL / THF / AHF / NJHS career tables using the same canonical name-normalization
logic as the backend (CareerLookupService):

  - normalize: lowercase, trim, strip ' - . , collapse whitespace
  - canonical: strip whitespace/commas/quotes/dots/dashes -> contiguous key
  - keys for "Ethan Cai" = { "ethancai", "caiethan" } (handles "Last,First")

Rules:
  - Exactly ONE distinct source_player_id in a source  -> auto-link (CONFIRMED)
  - Multiple distinct player_ids in a source           -> ambiguous, skip (report)
  - Zero matches                                       -> no link (report)

Idempotent: existing links are refreshed via ON CONFLICT (player_id, source).

Usage:
    python identity_link.py                  # all player profiles
    python identity_link.py --dry-run        # show what would be linked
    python identity_link.py --email x@y.z    # only players owned by that login
    python identity_link.py --player-id 12   # only one player
"""
from __future__ import annotations

import argparse
import os
import re
from typing import Optional

import psycopg2
import psycopg2.extras

import njhs_guard

SOURCE_TABLES = {
    "AYHL": "ayhl_player_career",
    "THF": "thf_player_career",
    "AHF": "ahf_player_career",
    "NJHS": "njhs_player_career",
}

# Raw roster tables + their player-name column. Used as a fallback so players
# who are in rosters but have no career record (no stats on the source) still
# get identity-linked and can receive skeleton season entries.
ROSTER_TABLES = {
    "AYHL": ("ayhl_roster", "player_name_raw"),
    "THF": ("thf_rosters", "player_name"),
    "AHF": ("ahf_rosters", "player_name"),
    "NJHS": ("njhs_rosters", "player_name"),
}

_PUNCT = re.compile(r"['\-.]+")
_WS = re.compile(r"\s+")
_CANON = re.compile(r"[\s,.'\-]+")


def normalize_name(name: Optional[str]) -> str:
    if not name:
        return ""
    return _WS.sub(" ", _PUNCT.sub("", name.lower())).strip()


def canonical(name: Optional[str]) -> str:
    if not name:
        return ""
    return _CANON.sub("", name.lower()).strip()


def canonical_keys(full_name: str) -> list[str]:
    norm = normalize_name(full_name)
    keys = [canonical(norm)]
    parts = norm.split()
    if len(parts) == 2:
        keys.append(canonical(f"{parts[1]},{parts[0]}"))
    return list(dict.fromkeys(k for k in keys if k))


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


def load_players(conn, email_filter: Optional[str] = None,
                 player_id: Optional[int] = None) -> list[dict]:
    """Every player profile, with the logins attached to it (for reporting)."""
    sql = """
        SELECT pp.id AS player_id, pp.full_name, pp.birthdate, pp.location,
               string_agg(DISTINCT u.email, '; ') AS user_emails
        FROM player_profiles pp
        LEFT JOIN user_players up ON up.player_id = pp.id
        LEFT JOIN users u ON u.id = up.user_id
    """
    params: list = []
    where = []
    if player_id is not None:
        where.append("pp.id = %s")
        params.append(player_id)
    if email_filter:
        where.append(
            "EXISTS (SELECT 1 FROM user_players up2 JOIN users u2 ON u2.id = up2.user_id "
            "WHERE up2.player_id = pp.id AND u2.email = %s)")
        params.append(email_filter)
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " GROUP BY pp.id, pp.full_name, pp.birthdate, pp.location ORDER BY pp.id"

    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(sql, params)
        return [dict(r) for r in cur.fetchall()]


def find_candidates(conn, source: str, keys: list[str]) -> list[dict]:
    table = SOURCE_TABLES[source]
    placeholders = ", ".join(["%s"] * len(keys))
    sql = f"""
        SELECT source_player_id,
               min(player_name)            AS player_name,
               count(DISTINCT season_label) AS seasons,
               string_agg(DISTINCT team_name, '; ') AS teams
        FROM {table}
        WHERE regexp_replace(lower(player_name), '[\\s,.''-]', '', 'g') IN ({placeholders})
        GROUP BY source_player_id
        ORDER BY source_player_id
    """
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(sql, keys)
        return [dict(r) for r in cur.fetchall()]


def find_roster_candidates(conn, source: str, keys: list[str]) -> list[dict]:
    """Match against the raw roster table (fallback when no career record).
    Note: roster tables differ — ayhl_roster has season_label, thf/ahf_rosters
    use season_year — so we avoid depending on a specific season column."""
    table, name_col = ROSTER_TABLES[source]
    placeholders = ", ".join(["%s"] * len(keys))
    sql = f"""
        SELECT player_id AS source_player_id,
               min({name_col}) AS player_name,
               count(*) AS seasons,
               string_agg(DISTINCT team_name, '; ') AS teams
        FROM {table}
        WHERE regexp_replace(lower({name_col}), '[\\s,.''-]', '', 'g') IN ({placeholders})
        GROUP BY player_id
        ORDER BY player_id
    """
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(sql, keys)
        return [dict(r) for r in cur.fetchall()]


def upsert_link(conn, player_id: int, source: str, source_player_id: str,
                player_name: str, dry_run: bool, owner_emails: Optional[str]) -> str:
    sql = """
        INSERT INTO player_source_links
            (id, player_id, source, source_player_id, link_state, match_method,
             confidence_score, confirmed_at, last_verified_at, created_at, updated_at)
        VALUES (gen_random_uuid(), %s, %s, %s, 'CONFIRMED', 'AUTO_NAME_MATCH',
                1.0, NOW(), NOW(), NOW(), NOW())
        ON CONFLICT (player_id, source) DO UPDATE SET
            source_player_id = EXCLUDED.source_player_id,
            link_state       = 'CONFIRMED',
            match_method     = 'AUTO_NAME_MATCH',
            confidence_score = 1.0,
            last_verified_at = NOW(),
            updated_at       = NOW()
    """
    who = f", logins: {owner_emails}" if owner_emails else ""
    if dry_run:
        return (f"[dry-run] would link player {player_id} {source}->{source_player_id} "
                f"({player_name}){who}")
    with conn.cursor() as cur:
        cur.execute(sql, (player_id, source, source_player_id))
    conn.commit()
    return f"linked player {player_id} {source}->{source_player_id} ({player_name}){who}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--email", help="only players attached to this login email")
    ap.add_argument("--player-id", type=int, help="only this player profile id")
    ap.add_argument("--dry-run", action="store_true", help="show actions without writing")
    args = ap.parse_args()

    conn = get_conn()
    try:
        players = load_players(conn, args.email, args.player_id)
        if not players:
            print("No player profiles found.")
            return
        print(f"Processing {len(players)} player profile(s)...")
        # AYHL hometowns carry the state ("Basking Ridge, NJ"), so they can
        # resolve a bare-town profile location for the NJ HS guard.
        nj_towns = njhs_guard.load_nj_towns(conn)
        hometowns = njhs_guard.load_hometowns(conn, [p["player_id"] for p in players])
        for p in players:
            name = (p["full_name"] or "").strip()
            if not name:
                print(f"  player {p['player_id']}: no name, skipping")
                continue
            keys = canonical_keys(name)
            if not keys:
                print(f"  player {p['player_id']}: could not normalize name, skipping")
                continue
            print(f"  player {p['player_id']} '{name}' "
                  f"(logins: {p['user_emails'] or 'none'}) keys={keys}")
            for source in SOURCE_TABLES:
                # NJ HS deep-dive is only for high-school-age players who live
                # in New Jersey (avoid matching same-name players elsewhere).
                if source == "NJHS":
                    ok, reason = njhs_guard.qualifies_for_njhs(
                        p["birthdate"], p["location"],
                        extra_locations=hometowns.get(p["player_id"], ()),
                        nj_towns=nj_towns)
                    if not ok:
                        print(f"    NJHS: skipped ({reason})")
                        continue
                cands = find_candidates(conn, source, keys)
                if not cands:
                    # No career record — try the raw roster (players without stats).
                    cands = find_roster_candidates(conn, source, keys)
                if not cands:
                    print(f"    {source}: no match")
                    continue
                if len(cands) == 1:
                    c = cands[0]
                    print("    " + upsert_link(conn, p["player_id"], source,
                                               c["source_player_id"], c["player_name"],
                                               args.dry_run, p["user_emails"]))
                else:
                    ids = ", ".join(c["source_player_id"] for c in cands)
                    print(f"    {source}: AMBIGUOUS ({len(cands)} players: {ids}) - skipped")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
