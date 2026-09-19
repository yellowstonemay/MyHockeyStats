#!/usr/bin/env python3
"""Poll deep_dive_requests and run FULL (all-seasons) deep-dives per PLAYER.

Requests are keyed by player, not by login: a player can be attached to several
logins (two parents, player + guardian, …), so a refresh runs ONCE per player
and every attached login gets the resulting notification cleared.

Sources come from player_source_links (player -> source_player_id), which is
maintained by identity_link.py.

Schedule (Mac mini cron — every 5 minutes):
    */5 * * * * ~/hockey-server/scripts/deep_dive/run_deep_dive_requests.py >> ~/hockey-server/logs/deep-dive-requests.log 2>&1

Note: should be run with the OpenSSL venv python (see run_deep_dive.sh) so the
AYHL scrape passes Cloudflare.
"""
from __future__ import annotations

import fcntl
import os
import subprocess
import sys

import psycopg2

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_VENV_PY = os.path.join(SCRIPT_DIR, ".venv", "bin", "python")
PYTHON = os.environ.get("PYTHON", _VENV_PY if os.path.exists(_VENV_PY) else sys.executable)

# Single-instance lock: the poller cron fires every 5 min but an all-seasons
# deep-dive can run longer. flock ensures only ONE poller scrapes at a time so
# overlapping cron ticks don't hammer the source sites concurrently.
_LOCK_FILE = os.path.join(SCRIPT_DIR, ".poller.lock")


def acquire_lock() -> bool:
    """Try to take the single-instance flock. False if another poller is running."""
    try:
        fd = open(_LOCK_FILE, "w")
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        acquire_lock.fd = fd  # keep a reference so the fd (and lock) stays alive
        return True
    except OSError:
        return False


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


def run(cmd) -> int:
    print(f">>> {' '.join(cmd)}", flush=True)
    return subprocess.run(cmd).returncode


def resolve_player_id(cur, player_id, user_id):
    """Legacy rows were queued per user; map them onto that login's player."""
    if player_id is not None:
        return player_id
    if user_id is None:
        return None
    cur.execute(
        "SELECT player_id FROM user_players WHERE user_id=%s "
        "ORDER BY is_primary DESC, created_at ASC LIMIT 1", (user_id,))
    row = cur.fetchone()
    if row is None or row[0] is None:
        return None
    cur.execute("UPDATE deep_dive_requests SET player_id=%s WHERE user_id=%s AND player_id IS NULL",
                (row[0], user_id))
    return row[0]


def main() -> None:
    if not acquire_lock():
        print(f"[{datetime_now()}] Another deep-dive poller is already running; skipping this tick",
              flush=True)
        return
    conn = get_conn()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT id, player_id, user_id FROM deep_dive_requests "
            "WHERE status='PENDING' ORDER BY requested_at")
        pending = cur.fetchall()
        if not pending:
            return
        print(f"[{datetime_now()}] {len(pending)} pending deep-dive request(s)", flush=True)
        # Auto-link players first so queued requests (e.g. from "add player")
        # actually have source identities to deep-dive.
        run([PYTHON, os.path.join(SCRIPT_DIR, "identity_link.py")])
        for req_id, player_id, user_id in pending:
            player_id = resolve_player_id(cur, player_id, user_id)
            cur.execute(
                "UPDATE deep_dive_requests SET status='RUNNING', started_at=NOW() WHERE id=%s",
                (req_id,))
            conn.commit()
            error = None
            try:
                if player_id is None:
                    error = "no player attached to this request"
                else:
                    cur.execute(
                        "SELECT source, source_player_id FROM player_source_links "
                        "WHERE player_id=%s AND link_state='CONFIRMED'", (player_id,))
                    links = cur.fetchall()
                    if not links:
                        print(f"[{req_id}] player {player_id}: no identity links", flush=True)
                    for source, spid in links:
                        if source == "AYHL":
                            cmd = [PYTHON, os.path.join(SCRIPT_DIR, "ayhl_deep.py"),
                                   "--player-id", str(spid), "--all-seasons"]
                        elif source == "NJHS":
                            cmd = [PYTHON, os.path.join(SCRIPT_DIR, "njhs_deep.py"),
                                   "--player-slug", str(spid), "--all-seasons"]
                        else:
                            cmd = [PYTHON, os.path.join(SCRIPT_DIR, "thf_ahf_deep.py"),
                                   "--player-id", str(spid), "--all-seasons", "--source", source]
                        if run(cmd) != 0:
                            error = f"{source}:{spid} failed"
            except Exception as e:  # noqa: BLE001
                error = str(e)
            if error:
                cur.execute(
                    "UPDATE deep_dive_requests SET status='FAILED', completed_at=NOW(), error=%s WHERE id=%s",
                    (error, req_id))
            else:
                cur.execute(
                    "UPDATE deep_dive_requests SET status='COMPLETED', completed_at=NOW() WHERE id=%s",
                    (req_id,))
            # Deep-dive finished — clear the "stats being retrieved" notification
            # for EVERY login attached to this player (they all got one).
            # DELETE (not UPDATE->RESOLVED) so repeat refreshes don't collide with
            # the (user_id, type, status) unique constraint.
            if player_id is not None:
                cur.execute(
                    "DELETE FROM notifications n USING user_players up "
                    "WHERE n.user_id = up.user_id AND up.player_id = %s "
                    "AND n.type='DEEP_DIVE' AND n.status='ACTIVE'",
                    (player_id,))
            conn.commit()
            print(f"[{req_id}] player {player_id}: "
                  f"{'FAILED - ' + error if error else 'COMPLETED'}", flush=True)
    finally:
        conn.close()


def datetime_now():
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


if __name__ == "__main__":
    main()
