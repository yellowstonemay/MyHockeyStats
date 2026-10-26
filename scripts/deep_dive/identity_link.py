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
  - Multiple distinct player_ids in a source           -> link the most recent,
    and fold the older ones in when they are clearly the SAME player (their
    seasons do not overlap and they share a club name). Sources renumber
    players from time to time, so a 2024-25 record can sit under a different id
    than the 2025-26 one; skipping those used to lose whole seasons.
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

# Season column per roster table, used to decide which duplicate id is current
# (ayhl_roster / njhs_rosters label seasons, thf / ahf store a year).
ROSTER_SEASON_COLUMN = {
    "AYHL": "season_label",
    "THF": "season_year::text",
    "AHF": "season_year::text",
    "NJHS": "season_label",
}


def latest_year_expr(column: str) -> str:
    """SQL aggregate for the newest 4-digit year found in a season column."""
    return f"max(NULLIF(substring({column} from '[0-9]{{4}}'), '')::int)"


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
               {latest_year_expr('season_label')} AS latest_year,
               string_agg(DISTINCT team_name, '; ') AS teams,
               string_agg(DISTINCT season_label, ';') AS season_labels
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
    season_col = ROSTER_SEASON_COLUMN[source]
    placeholders = ", ".join(["%s"] * len(keys))
    sql = f"""
        SELECT player_id AS source_player_id,
               min({name_col}) AS player_name,
               count(*) AS seasons,
               {latest_year_expr(season_col)} AS latest_year,
               string_agg(DISTINCT team_name, '; ') AS teams,
               string_agg(DISTINCT {season_col}, ';') AS season_labels
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


def pick_candidate(cands: list[dict]) -> Optional[dict]:
    """Choose the id to link when a name matches more than one source record.

    The same kid often exists under two id schemes in one source — a roster id
    and a career id — which used to make the matcher bail out as "ambiguous"
    and leave the player with no games. Prefer the candidate whose newest season
    is the most recent, and only give up when they are equally recent (then the
    choice is genuinely unknowable from the data).
    """
    if not cands:
        return None
    if len(cands) == 1:
        return cands[0]
    ranked = sorted(cands, key=lambda c: (c.get("latest_year") or 0), reverse=True)
    best = ranked[0].get("latest_year") or 0
    runner_up = ranked[1].get("latest_year") or 0
    if best > runner_up:
        return ranked[0]
    return None


# Words that say nothing about which club a team belongs to, so they must not
# count as evidence that two source records are the same player.
_CLUB_STOPWORDS = {
    "hockey", "club", "academy", "elite", "premier", "select", "travel",
    "ice", "team", "junior", "youth", "squirt", "peewee", "bantam", "mite",
}


def season_years(labels: Optional[str]) -> set[int]:
    """Start years in a ';'-joined season list.

    Only the FIRST 4-digit group of each label counts: "2024-2025 Season" is the
    2024 season, and reading the end year too would make consecutive seasons look
    like they overlap (2024-25 vs 2025-26).
    """
    out: set[int] = set()
    for label in (labels or "").split(";"):
        m = re.search(r"(\d{4})", label)
        if m:
            out.add(int(m.group(1)))
    return out


def club_tokens(teams: Optional[str]) -> set[str]:
    """Distinctive words of a ';'-joined team list (drops level/generic words)."""
    out: set[str] = set()
    for team in (teams or "").split(";"):
        for word in re.split(r"[^a-z0-9]+", team.lower()):
            if len(word) >= 4 and not word.isdigit() and word not in _CLUB_STOPWORDS:
                out.add(word)
    return out


def extra_ids_to_fold(chosen: dict, cands: list[dict]) -> tuple[list[dict], list[str]]:
    """Ids of the same player under a retired number, plus why others are left out.

    A source renumbers players occasionally, so one kid can hold an old id
    (2023-24 / 2024-25) and a current one (2025-26 onwards). Those records must
    be folded into the linked id, otherwise their seasons stay invisible.
    Only fold when the evidence is unambiguous: no shared season (two live
    records with the same name are two people) and at least one shared club.
    """
    chosen_years = season_years(chosen.get("season_labels"))
    chosen_clubs = club_tokens(chosen.get("teams"))
    extras: list[dict] = []
    notes: list[str] = []
    used = set(chosen_years)
    for cand in sorted(cands, key=lambda c: (c.get("latest_year") or 0), reverse=True):
        if cand is chosen:
            continue
        cid = cand["source_player_id"]
        years = season_years(cand.get("season_labels"))
        clubs = club_tokens(cand.get("teams"))
        if not years:
            notes.append(f"{cid}: no season info, left alone")
            continue
        if years & used:
            notes.append(f"{cid}: same seasons, looks like another player, left alone")
            continue
        if not (clubs & chosen_clubs):
            notes.append(f"{cid}: no club in common "
                         f"({', '.join(sorted(clubs)) or 'unknown'}), left alone")
            continue
        extras.append(cand)
        used |= years
    return extras, notes


# Columns copied when folding a retired id's career rows (only those the table
# actually has are used).
_CAREER_COPY_COLUMNS = [
    "source_player_id", "player_name", "season_label", "season_year", "league_name",
    "team_name", "team_id", "jersey_number", "games_played", "goals", "assists",
    "points", "penalties", "pim", "is_user_modified", "last_scraped_at",
]


def fold_legacy_seasons(conn, source: str, legacy_id: str, target_id: str,
                        season_labels: list[str], dry_run: bool) -> str:
    """Copy a retired id's career seasons onto the id we link to.

    Career rows are read by source_player_id everywhere downstream and a player
    holds ONE id per source, so an id the source no longer uses has to be folded
    into the current one or its seasons stay invisible. Rows already present on
    the target are left untouched.
    """
    table = SOURCE_TABLES[source]
    with conn.cursor() as cur:
        cur.execute(
            "SELECT column_name FROM information_schema.columns WHERE table_name = %s",
            (table,))
        present = {row[0] for row in cur.fetchall()}
    cols = [c for c in _CAREER_COPY_COLUMNS if c in present]
    if "source_player_id" not in cols or "season_label" not in cols:
        return f"cannot fold {source}:{legacy_id} (unexpected {table} shape)"
    extra = [c for c in ("created_at", "updated_at") if c in present]

    select_list = ", ".join("%s" if c == "source_player_id" else "src." + c for c in cols)
    insert_cols = ", ".join(cols + extra)
    insert_values = ", ".join(["gen_random_uuid()"] + [select_list] + ["NOW()"] * len(extra))
    sql = f"""
        INSERT INTO {table} (id, {insert_cols})
        SELECT {insert_values}
        FROM {table} src
        WHERE src.source_player_id = %s
          AND src.season_label = ANY(%s)
          AND NOT EXISTS (
                SELECT 1 FROM {table} t
                WHERE t.source_player_id = %s AND t.season_label = src.season_label)
    """

    if dry_run:
        with conn.cursor() as cur:
            cur.execute(
                f"SELECT COUNT(*) FROM {table} src WHERE src.source_player_id = %s "
                "AND src.season_label = ANY(%s)", (legacy_id, season_labels))
            n = cur.fetchone()[0]
        return (f"[dry-run] would fold {n} season(s) {season_labels} from "
                f"{source}:{legacy_id} into {source}:{target_id}")

    with conn.cursor() as cur:
        cur.execute(sql, (target_id, legacy_id, season_labels, target_id))
        n = cur.rowcount
    conn.commit()
    if not n:
        return f"{source}:{legacy_id}: nothing left to fold"
    return (f"folded {n} season(s) {', '.join(sorted(season_labels))} from "
            f"{source}:{legacy_id} into {source}:{target_id}")


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
                chosen = pick_candidate(cands)
                if chosen is not None:
                    reason = "" if len(cands) == 1 else (
                        f" (most recent of {len(cands)}, others: "
                        + ", ".join(str(c["source_player_id"]) for c in cands
                                    if c is not chosen) + ")")
                    print("    " + upsert_link(conn, p["player_id"], source,
                                               chosen["source_player_id"], chosen["player_name"],
                                               args.dry_run, p["user_emails"]) + reason)
                    extras, notes = extra_ids_to_fold(chosen, cands)
                    for note in notes:
                        print(f"      {note}")
                    for extra in extras:
                        labels = [lbl.strip() for lbl in
                                  (extra.get("season_labels") or "").split(";") if lbl.strip()]
                        print("      " + fold_legacy_seasons(
                            conn, source, extra["source_player_id"],
                            chosen["source_player_id"], labels, args.dry_run))
                else:
                    ids = ", ".join(f"{c['source_player_id']} (latest {c.get('latest_year')})"
                                    for c in cands)
                    print(f"    {source}: AMBIGUOUS ({len(cands)} players: {ids}) - skipped")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
