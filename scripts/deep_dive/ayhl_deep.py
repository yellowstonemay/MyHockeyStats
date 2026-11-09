#!/usr/bin/env python3
"""AYHL deep-dive for registered users.

For each AYHL identity link (or a single --player-id), fetch the player's FULL
career from atlantichockey.org (all seasons, not just current), upsert every
season into `ayhl_player_career`, record any stat changes into
`ayhl_change_event` (per-field old -> new), and upsert the player's game-by-game
history into `ayhl_player_games` (current season by default; every season with
--all-seasons). Game rows are UPSERTed on (player_id, season_year, game_id) —
existing rows are never deleted.

The AYHL site became a WordPress app in 2026-27, so the player's career and
per-game history come from its REST API (wp-json/ayhl/v1/players/<id>/) — one
JSON call per player, no browser. The old playerpage.php answers 404 for
everyone, and the deep dive used to report SUCCESS after parsing that 404 page.

Usage:
    python ayhl_deep.py                       # all AYHL-linked users
    python ayhl_deep.py --player-id 125307    # single player
    python ayhl_deep.py --all-seasons         # career + games for all seasons
    python ayhl_deep.py --dry-run             # fetch + show, no DB writes
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import datetime, timezone, date
from uuid import uuid4

import psycopg2
import psycopg2.extras
import requests

import deep_dive_status

BASE_URL = "https://atlantichockey.org/playerpage.php?playerid={pid}"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Accept": "application/json, text/plain, */*",
}

# 2026-27 relaunch: atlantichockey.org became a WordPress app whose data comes
# from /wp-json/ayhl/v1/. playerpage.php now answers 404 for EVERY player, which
# used to look like a successful deep-dive: the parser read the 404 page's title
# ("This page has left the ice") as the player name and found 0 seasons, 0 games.
API_BASE = "https://atlantichockey.org/wp-json/ayhl/v1"

# atlantichockey.org labels a season by the year it starts in, but the schedule
# runs Aug..Mar, so a season's games are windowed Apr..Mar (same rule as the
# other scrapers and games_from_payload below).

STAT_FIELDS = [
    ("games_played", "Games"),
    ("goals", "Goals"),
    ("assists", "Assists"),
    ("points", "Points"),
    ("penalties", "Penalties"),
    ("pim", "PIM"),
]


def current_season_year() -> int:
    """Current AYHL season start year (April–March cycle)."""
    today = date.today()
    return today.year if today.month >= 4 else today.year - 1


def season_label_for(year: int) -> str:
    return f"{year}-{year + 1} Season"


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


def load_ayhl_linked_players(conn) -> list[tuple[str, str]]:
    """Return [(source_player_id, player_name_or_empty)] from the AYHL links."""
    sql = """
        SELECT psl.source_player_id, pp.full_name
        FROM player_source_links psl
        JOIN player_profiles pp ON pp.id = psl.player_id
        WHERE psl.source = 'AYHL' AND psl.link_state = 'CONFIRMED'
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        rows = cur.fetchall()
    # Dedupe by player id (multiple users can link to the same source player)
    seen = {}
    for pid, name in rows:
        if pid not in seen:
            seen[pid] = name or ""
    return list(seen.items())


def fetch_player_payload(pid: str, season_id: int | None = None, timeout: int = 30) -> dict:
    """Fetch a player's JSON payload: profile, career rows and every game.

    Raises on HTTP/parse errors so the caller can record a real failure instead
    of treating an empty result as success.
    """
    params = {"season": str(season_id)} if season_id is not None else None
    resp = requests.get(f"{API_BASE}/players/{pid}/", params=params,
                        headers=HEADERS, timeout=timeout)
    resp.raise_for_status()
    payload = resp.json()
    if not isinstance(payload, dict) or ("career" not in payload and "games" not in payload):
        raise ValueError(f"unexpected payload for player {pid}")
    return payload


def parse_player(payload: dict, pid: str) -> dict:
    """Normalise the API payload into the shape the upserts expect.

    rows        career rows, one dict per season (league/team/GP/G/A/P/PIM)
    game_rows   the same games as flat dicts (season bucketed later)
    """
    player = payload.get("player") or {}
    name = (player.get("name") or "").strip()
    jersey = str(player.get("jersey") or "").strip()

    rows: list[dict] = []
    for entry in payload.get("career") or []:
        label = ((entry.get("season") or {}).get("label") or "").strip()
        if not label:
            continue
        rows.append({
            "season_label": label,
            "league": (entry.get("league") or "").strip() or "AYHL",
            "team": ((entry.get("team") or {}).get("name") or "").strip(),
            "games_played": to_int(entry.get("gp")),
            "goals": to_int(entry.get("goals")),
            "assists": to_int(entry.get("assists")),
            "points": to_int(entry.get("points")),
            "penalties": to_int(entry.get("penalties")),
            "pim": to_int(entry.get("pim")),
        })

    return {"pid": pid, "name": name, "jersey": jersey, "rows": rows,
            "game_rows": payload.get("games") or []}


def to_int(v) -> int | None:
    """Parse a stat value; 0 is a real number, not "missing".

    The old version used `str(v or "")`, which turned every 0 into "" and then
    into None — so every zero-goal/zero-PIM game was stored as NULL.
    """
    if v is None:
        return None
    text = str(v).strip()
    if text == "":
        return None
    try:
        return int(text)
    except (TypeError, ValueError):
        return None


def upsert_career(conn, entry: dict, all_seasons: bool = False) -> list[dict]:
    """Upsert one player's career rows; return list of change events."""
    changes: list[dict] = []
    pid = entry["pid"]
    name = entry["name"] or "N/A"
    jersey = entry["jersey"] or None
    now = datetime.now(timezone.utc)

    rows = entry["rows"]
    if not all_seasons:
        cur_label = season_label_for(current_season_year())
        rows = [r for r in rows if r["season_label"] == cur_label]

    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        for row in rows:
            season_label = row["season_label"]
            # The API publishes the division ("15 Pure 11"); older rows stored
            # just the league, so keep AYHL as the fallback.
            league_name = row.get("league") or "AYHL"
            team_name = row.get("team") or None
            vals = {
                "games_played": row.get("games_played"),
                "goals": row.get("goals"),
                "assists": row.get("assists"),
                "points": row.get("points"),
                "penalties": row.get("penalties"),
                "pim": row.get("pim"),
            }

            # Fetch existing row for change detection
            cur.execute(
                "SELECT games_played, goals, assists, points, penalties, pim "
                "FROM ayhl_player_career WHERE source_player_id=%s AND season_label=%s",
                (pid, season_label),
            )
            old = cur.fetchone()

            if old:
                for col in STAT_FIELDS:
                    o, n = old[col[0]], vals[col[0]]
                    if (o or 0) != (n or 0):
                        changes.append({
                            "source_player_id": pid,
                            "player_name": name,
                            "season_label": season_label,
                            "field_name": col[0],
                            "old_value": str(o) if o is not None else "",
                            "new_value": str(n) if n is not None else "",
                        })

            cur.execute(
                """INSERT INTO ayhl_player_career
                   (id, source_player_id, player_name, season_label, league_name,
                    team_name, jersey_number, games_played, goals, assists, points,
                    penalties, pim, last_scraped_at, created_at, updated_at)
                   VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW())
                   ON CONFLICT (source_player_id, season_label) DO UPDATE SET
                     player_name = EXCLUDED.player_name,
                     league_name = EXCLUDED.league_name,
                     team_name = EXCLUDED.team_name,
                     jersey_number = EXCLUDED.jersey_number,
                     games_played = EXCLUDED.games_played,
                     goals = EXCLUDED.goals,
                     assists = EXCLUDED.assists,
                     points = EXCLUDED.points,
                     penalties = EXCLUDED.penalties,
                     pim = EXCLUDED.pim,
                     last_scraped_at = EXCLUDED.last_scraped_at,
                     updated_at = NOW()""",
                (pid, name, season_label, league_name, team_name, jersey,
                 vals["games_played"], vals["goals"], vals["assists"], vals["points"],
                 vals["penalties"], vals["pim"], now),
            )

        for ch in changes:
            cur.execute(
                """INSERT INTO ayhl_change_event
                   (id, source_player_id, player_name, season_label, field_name,
                    old_value, new_value, detected_at, event_type)
                   VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, NOW(), 'STAT_CHANGE')""",
                (ch["source_player_id"], ch["player_name"], ch["season_label"],
                 ch["field_name"], ch["old_value"], ch["new_value"]),
            )
    conn.commit()
    return changes


# ---------------------------------------------------------------------------
# Game-by-game history (upsert into ayhl_player_games — never deletes rows)
# ---------------------------------------------------------------------------

def games_from_payload(payload: dict, player_id: str, player_name: str) -> dict[int, list[dict]]:
    """Per-game rows from the API payload, bucketed by season year.

    The API returns every game of the player's career in one call, each with an
    ISO date, so the season is derived from the date (Apr..Mar window) instead of
    trusting a season id - the old per-seasonid fetch filed 11 seasons of the
    same game because the site always rendered the current season.
    """
    scraped_at = datetime.now(timezone.utc)
    buckets: dict[int, list[dict]] = {}

    for row in payload.get("games") or []:
        if not isinstance(row, dict):
            continue
        raw_date = str(row.get("date") or "").strip()
        try:
            game_date = date.fromisoformat(raw_date)
        except ValueError:
            continue
        season_year = game_date.year if game_date.month >= 4 else game_date.year - 1
        game_id = str(row.get("id") or "").strip()
        if not game_id:
            continue
        buckets.setdefault(season_year, []).append({
            "player_id": player_id,
            "player_name": player_name or "N/A",
            "season_year": season_year,
            "season_label": season_label_for(season_year),
            "season_id": str((payload.get("season") or {}).get("id") or ""),
            "game_id": game_id,
            "game_date": game_date.isoformat(),
            "game_type": row.get("type") or None,
            "league": row.get("league") or None,
            "team_for": (row.get("team") or {}).get("name") or None,
            "team_against": (row.get("opponent") or {}).get("name") or None,
            "goals": to_int(row.get("goals")),
            "goals_pp": to_int(row.get("goals_pp")),
            "goals_sh": to_int(row.get("goals_sh")),
            "goals_so": to_int(row.get("goals_so")),
            "assists": to_int(row.get("assists")),
            "points": to_int(row.get("points")),
            "pim": to_int(row.get("pim")),
            "scraped_at": scraped_at,
        })
    return buckets


UPSERT_GAMES_SQL = """
INSERT INTO ayhl_player_games (
    id, player_id, player_name, season_year, season_label, season_id,
    game_id, game_date, game_type, league,
    team_for, team_against,
    goals, goals_pp, goals_sh, goals_so,
    assists, points, pim, scraped_at
) VALUES (
    %s, %s, %s, %s, %s, %s,
    %s, %s, %s, %s,
    %s, %s,
    %s, %s, %s, %s,
    %s, %s, %s, %s
)
ON CONFLICT (player_id, season_year, game_id) DO UPDATE SET
    player_name   = EXCLUDED.player_name,
    season_label  = EXCLUDED.season_label,
    season_id     = EXCLUDED.season_id,
    game_date     = EXCLUDED.game_date,
    game_type     = EXCLUDED.game_type,
    league        = EXCLUDED.league,
    team_for      = EXCLUDED.team_for,
    team_against  = EXCLUDED.team_against,
    goals         = EXCLUDED.goals,
    goals_pp      = EXCLUDED.goals_pp,
    goals_sh      = EXCLUDED.goals_sh,
    goals_so      = EXCLUDED.goals_so,
    assists       = EXCLUDED.assists,
    points        = EXCLUDED.points,
    pim           = EXCLUDED.pim,
    scraped_at    = EXCLUDED.scraped_at
"""


def upsert_player_games(conn, games: list[dict]) -> int:
    """Upsert game rows into ayhl_player_games. Existing rows (same player_id +
    season_year + game_id) are UPDATEd, never deleted."""
    with conn.cursor() as cur:
        for g in games:
            cur.execute(UPSERT_GAMES_SQL, (
                str(uuid4()),
                g["player_id"], g["player_name"], g["season_year"], g["season_label"],
                g["season_id"], g["game_id"], g["game_date"], g["game_type"], g["league"],
                g["team_for"], g["team_against"],
                g["goals"], g["goals_pp"], g["goals_sh"], g["goals_so"],
                g["assists"], g["points"], g["pim"], g["scraped_at"],
            ))
    conn.commit()
    return len(games)


def _roster_season_ids(conn) -> list[tuple[int, int]]:
    """(season_year, season_id) pairs from ayhl_roster, newest first.

    Only used for the season picker: the API hands over every season's games at
    once, so it no longer drives the scraping.
    """
    with conn.cursor() as cur:
        cur.execute("SELECT DISTINCT season_id, season_label FROM ayhl_roster")
        out: list[tuple[int, int]] = []
        for sid, label in cur.fetchall():
            m = re.match(r"(\d{4})-", label or "")
            if m:
                try:
                    out.append((int(m.group(1)), int(sid)))
                except (TypeError, ValueError):
                    pass
    return sorted(out, key=lambda x: -x[0])


def game_history(conn, pid: str, name: str, payload: dict, all_seasons: bool = False,
                 dry_run: bool = False) -> tuple[int, int]:
    """Upsert the player's game-by-game history from the API payload.

    Returns (rows_written, seasons_seen). The API hands over every season at
    once; by default only the current season and the newest season we already
    hold are refreshed (late-added games), --all-seasons takes everything.
    Rows are UPSERTed on (player_id, season_year, game_id) — existing rows are
    refreshed, never deleted.
    """
    with conn.cursor() as cur:
        cur.execute("SELECT COALESCE(MAX(season_year), 0) FROM ayhl_player_games")
        max_game_year = cur.fetchone()[0] or 0

    buckets = games_from_payload(payload, pid, name)
    if not all_seasons:
        want = {current_season_year()}
        if max_game_year:
            want.add(max_game_year)
        buckets = {year: rows for year, rows in buckets.items() if year in want}

    total = 0
    for season_year in sorted(buckets):
        games = buckets[season_year]
        label = season_label_for(season_year)
        if not games:
            continue
        if dry_run:
            print(f"    games[{label}]: {len(games)} row(s) (dry-run)")
            total += len(games)
            continue
        upsert_player_games(conn, games)
        print(f"    games[{label}]: upserted {len(games)} row(s)")
        total += len(games)
    return total, len(buckets)


def count_stored_games(conn, pid: str) -> int:
    """How many AYHL game rows we hold for a player (all seasons)."""
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM ayhl_player_games WHERE player_id = %s", (pid,))
        return int(cur.fetchone()[0] or 0)


def derive_career_from_games(conn, pid: str, name: str, dry_run: bool = False) -> int:
    """Fill career rows that have NO source stats from the game rows we hold.

    atlantichockey.org sits behind Cloudflare, so the player page often comes
    back as a challenge (the parser then sees 0 tables) even though
    ayhl_player_games already has that player's games. GP and G/A/P/PIM are just
    sums of those game rows, so derive them instead of leaving the season blank.

    Only rows with games_played IS NULL are touched — a scraped value (or a
    user-edited row) is never overwritten. Returns the number of rows filled.
    """
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            """SELECT season_year,
                      COUNT(*)                    AS gp,
                      COALESCE(SUM(goals), 0)     AS goals,
                      COALESCE(SUM(assists), 0)   AS assists,
                      COALESCE(SUM(points), 0)    AS points,
                      COALESCE(SUM(pim), 0)       AS pim
               FROM ayhl_player_games
               WHERE player_id = %s AND season_year IS NOT NULL
               GROUP BY season_year ORDER BY season_year""",
            (pid,),
        )
        seasons = [dict(r) for r in cur.fetchall()]

    filled = 0
    for row in seasons:
        year = row["season_year"]
        label = season_label_for(year)
        with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
            cur.execute(
                "SELECT games_played, team_name, jersey_number, is_user_modified "
                "FROM ayhl_player_career WHERE source_player_id = %s AND season_label = %s",
                (pid, label),
            )
            old = cur.fetchone()
            if old and (old["games_played"] is not None or old["is_user_modified"]):
                continue  # source (or the user) already has numbers for this season
            if dry_run:
                print(f"    derived[{label}]: GP={row['gp']} G={row['goals']} A={row['assists']} "
                      f"P={row['points']} PIM={row['pim']} (dry-run)")
                filled += 1
                continue
            team = old["team_name"] if old else None
            if not team:
                cur.execute(
                    "SELECT team_for FROM ayhl_player_games "
                    "WHERE player_id = %s AND season_year = %s AND team_for IS NOT NULL "
                    "ORDER BY game_date DESC LIMIT 1",
                    (pid, year),
                )
                t = cur.fetchone()
                team = t["team_for"] if t else None
            cur.execute(
                """INSERT INTO ayhl_player_career
                   (id, source_player_id, player_name, season_label, league_name,
                    team_name, jersey_number, games_played, goals, assists, points,
                    pim, last_scraped_at, created_at, updated_at)
                   VALUES (gen_random_uuid(), %s, %s, %s, 'AYHL', %s, %s, %s, %s, %s, %s, %s, NOW(), NOW(), NOW())
                   ON CONFLICT (source_player_id, season_label) DO UPDATE SET
                     games_played = EXCLUDED.games_played,
                     goals = EXCLUDED.goals,
                     assists = EXCLUDED.assists,
                     points = EXCLUDED.points,
                     pim = EXCLUDED.pim,
                     team_name = COALESCE(ayhl_player_career.team_name, EXCLUDED.team_name),
                     jersey_number = COALESCE(ayhl_player_career.jersey_number, EXCLUDED.jersey_number),
                     last_scraped_at = EXCLUDED.last_scraped_at,
                     updated_at = NOW()""",
                (pid, name or "N/A", label, team,
                 old["jersey_number"] if old else None,
                 row["gp"], row["goals"], row["assists"], row["points"], row["pim"]),
            )
        filled += 1
    if filled and not dry_run:
        conn.commit()
        print(f"    derived[{pid}]: filled {filled} season row(s) from game data")
    return filled


def create_skeletons(conn, pid: str, name: str, all_seasons: bool = False) -> int:
    """For a player who appears in ayhl_roster but has NO career record for
    those seasons, create 'skeleton' season rows with empty stats and
    is_user_modified = TRUE so the user can fill them in from the GUI.
    By default only the current season is considered."""
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            "SELECT DISTINCT season_label, team_name, jersey_number, player_name_raw "
            "FROM ayhl_roster WHERE player_id = %s ORDER BY season_label",
            (pid,),
        )
        roster_rows = [dict(r) for r in cur.fetchall()]
        if not roster_rows:
            return 0
        if not all_seasons:
            cur_label = season_label_for(current_season_year())
            roster_rows = [r for r in roster_rows if r["season_label"] == cur_label]
        if not roster_rows:
            return 0
        cur.execute(
            "SELECT season_label FROM ayhl_player_career WHERE source_player_id = %s",
            (pid,),
        )
        existing = {r[0] for r in cur.fetchall()}
        created = 0
        for row in roster_rows:
            label = row["season_label"]
            if label in existing:
                continue
            player_name = name or (row["player_name_raw"] or "") or "N/A"
            cur.execute(
                """INSERT INTO ayhl_player_career
                   (id, source_player_id, player_name, season_label, league_name,
                    team_name, jersey_number, games_played, goals, assists, points,
                    penalties, pim, is_user_modified, last_scraped_at, created_at, updated_at)
                   VALUES (gen_random_uuid(), %s, %s, %s, 'AYHL', %s, %s,
                           0, 0, 0, 0, 0, 0, TRUE, NOW(), NOW(), NOW())
                   ON CONFLICT (source_player_id, season_label) DO NOTHING""",
                (pid, player_name, label, row["team_name"], row["jersey_number"]),
            )
            created += 1
        conn.commit()
        return created


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--player-id", help="scrape a single player id")
    ap.add_argument("--all-seasons", action="store_true",
                    help="process ALL seasons (default: current season only)")
    ap.add_argument("--dry-run", action="store_true", help="fetch + show, no DB writes")
    args = ap.parse_args()

    conn = get_conn()
    try:
        if args.player_id:
            players = [(args.player_id, "")]
        else:
            players = load_ayhl_linked_players(conn)
        if not players:
            print("No AYHL-linked users to deep-dive.")
            return
        print(f"Deep-diving {len(players)} AYHL player(s)...")
        capture = deep_dive_status.install_capture()
        games_written = 0
        failed: list[tuple[str, str]] = []
        for pid, _label in players:
            entry = None
            payload = None
            fail_reason = None
            capture.mark()
            try:
                payload = fetch_player_payload(pid)
                entry = parse_player(payload, pid)
                print(f"  {pid}: {entry['name'] or '(no name)'} jersey={entry['jersey'] or '?'} "
                      f"seasons={len(entry['rows'])} gameRows={len(entry['game_rows'])}")
                if not entry["name"]:
                    raise ValueError("source returned no player name (unexpected payload)")
                if not entry["rows"]:
                    print("    (source lists no career rows for this player)")
                if args.dry_run:
                    for row in entry["rows"]:
                        print("    ", row)
                else:
                    changes = upsert_career(conn, entry, all_seasons=args.all_seasons)
                    skeletons = create_skeletons(conn, pid, entry["name"], all_seasons=args.all_seasons)
                    if changes:
                        print(f"    -> {len(changes)} change(s) detected & logged")
                    else:
                        print("    -> no stat changes")
                    if skeletons:
                        print(f"    -> created {skeletons} skeleton season(s) (empty stats, user-editable)")
                    # Game-by-game history (upsert into ayhl_player_games)
                    written, seasons = game_history(conn, pid, entry["name"], payload,
                                                    all_seasons=args.all_seasons, dry_run=False)
                    games_written += written
                    # Honest accounting: the source says this player HAS games,
                    # so a run that stores none must not be reported as success.
                    stored = count_stored_games(conn, pid)
                    if stored == 0 and entry["game_rows"]:
                        fail_reason = (f"source lists {len(entry['game_rows'])} game(s) but "
                                       f"ayhl_player_games has none for this player")
                    elif entry["game_rows"] and stored < len(entry["game_rows"]):
                        print(f"    (note: source lists {len(entry['game_rows'])} game(s), "
                              f"we hold {stored} — older seasons are only refreshed with --all-seasons)")
            except Exception as e:
                print(f"  {pid}: ERROR {e}")
                fail_reason = f"{type(e).__name__}: {e}"
            # Runs even when the fetch failed: fill any career row that still has
            # NO stats from the game rows we already hold.
            try:
                derived = derive_career_from_games(conn, pid, (entry or {}).get("name", ""),
                                                   dry_run=args.dry_run)
                if derived and args.dry_run:
                    print(f"    -> would derive {derived} season row(s) from game data")
            except Exception as e:
                print(f"    (derive from games failed: {e})")
            if not args.dry_run:
                deep_dive_status.record_outcome(conn, "AYHL", pid, ok=fail_reason is None,
                                                error=fail_reason,
                                                log_text=capture.since_mark())
                if fail_reason:
                    failed.append((pid, fail_reason))

        # A summary the cron log (and the admin page) can be judged by: the old
        # code printed nothing here, so "0 games for everyone" looked like a
        # normal run.
        print(f"\nAYHL deep-dive done: {len(players)} player(s), "
              f"{games_written} game row(s) written, {len(failed)} failure(s)")
        for pid, reason in failed:
            print(f"  FAILED {pid}: {reason}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
