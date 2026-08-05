#!/usr/bin/env python3
"""Batch identity auto-linker for registered MyHockeyStats users.

For every registered user (with a player profile or user full name), find their
source player record(s) across AYHL / THF / AHF career tables using the same
canonical name-normalization logic as the backend (CareerLookupService):

  - normalize: lowercase, trim, strip ' - . , collapse whitespace
  - canonical: strip whitespace/commas/quotes/dots/dashes -> contiguous key
  - keys for "Ethan Cai" = { "ethancai", "caiethan" } (handles "Last,First")

Rules:
  - Exactly ONE distinct source_player_id in a source  -> auto-link (CONFIRMED)
  - Multiple distinct player_ids in a source           -> ambiguous, skip (report)
  - Zero matches                                       -> no link (report)

Idempotent: existing links are refreshed via ON CONFLICT (user_id, source).

Usage:
    python identity_link.py                  # all registered users
    python identity_link.py --dry-run        # show what would be linked
    python identity_link.py --email x@y.z    # just one user
"""
from __future__ import annotations

import argparse
import os
import re
import sys
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


def load_users(conn, email_filter: Optional[str] = None) -> list[dict]:
    sql = """
        SELECT u.id AS user_id, u.email, u.full_name AS user_name,
               pp.full_name AS profile_name, pp.birthdate, pp.location
        FROM users u
        LEFT JOIN player_profiles pp ON pp.user_id = u.id
    """
    params = []
    if email_filter:
        sql += " WHERE u.email = %s"
        params.append(email_filter)
    sql += " ORDER BY u.id"
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(sql, params)
        rows = cur.fetchall()
    out = []
    for r in rows:
        full_name = (r["profile_name"] or r["user_name"] or "").strip()
        out.append({
            "user_id": r["user_id"],
            "email": r["email"],
            "full_name": full_name,
            "birthdate": r["birthdate"],
            "location": r["location"],
        })
    return out


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


def upsert_link(conn, user_id: int, source: str, player_id: str, player_name: str,
                dry_run: bool) -> str:
    sql = """
        INSERT INTO player_identity_map
            (id, user_id, source, source_player_id, link_state, match_method,
             confidence_score, confirmed_at, last_verified_at, created_at, updated_at)
        VALUES (gen_random_uuid(), %s, %s, %s, 'CONFIRMED', 'AUTO_NAME_MATCH',
                1.0, NOW(), NOW(), NOW(), NOW())
        ON CONFLICT (user_id, source) DO UPDATE SET
            source_player_id = EXCLUDED.source_player_id,
            link_state       = 'CONFIRMED',
            match_method     = 'AUTO_NAME_MATCH',
            confidence_score = 1.0,
            last_verified_at = NOW(),
            updated_at       = NOW()
    """
    if dry_run:
        return f"[dry-run] would link user {user_id} {source}->{player_id} ({player_name})"
    with conn.cursor() as cur:
        cur.execute(sql, (user_id, source, player_id))
    conn.commit()
    return f"linked user {user_id} {source}->{player_id} ({player_name})"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--email", help="only process this user email")
    ap.add_argument("--dry-run", action="store_true", help="show actions without writing")
    args = ap.parse_args()

    conn = get_conn()
    try:
        users = load_users(conn, args.email)
        if not users:
            print("No registered users found.")
            return
        print(f"Processing {len(users)} registered user(s)...")
        for u in users:
            if not u["full_name"]:
                print(f"  user {u['user_id']} ({u['email']}): no name, skipping")
                continue
            keys = canonical_keys(u["full_name"])
            if not keys:
                print(f"  user {u['user_id']} ({u['email']}): could not normalize name, skipping")
                continue
            print(f"  user {u['user_id']} ({u['email']}) '{u['full_name']}' keys={keys}")
            for source in SOURCE_TABLES:
                # NJ HS deep-dive is only for high-school-age players who live
                # in New Jersey (avoid matching same-name players elsewhere).
                if source == "NJHS":
                    ok, reason = njhs_guard.qualifies_for_njhs(u["birthdate"], u["location"])
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
                    print(f"    {source}: {upsert_link(conn, u['user_id'], source, c['source_player_id'], c['player_name'], args.dry_run)}")
                else:
                    ids = ", ".join(c["source_player_id"] for c in cands)
                    print(f"    {source}: AMBIGUOUS ({len(cands)} players: {ids}) - skipped")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
