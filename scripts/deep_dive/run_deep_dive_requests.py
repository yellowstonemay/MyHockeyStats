#!/usr/bin/env python3
"""Poll deep_dive_requests and run FULL (all-seasons) deep-dives for pending users.

The admin page enqueues a request via POST /api/admin/deep-dive/{userId}; this
poller (run frequently via cron on the Mac mini) picks it up and runs the
per-user full-career scrape for each of the user's identity links.

Schedule (Mac mini cron — every 5 minutes):
    */5 * * * * ~/hockey-server/scripts/deep_dive/run_deep_dive_requests.py >> ~/hockey-server/logs/deep-dive-requests.log 2>&1

Note: should be run with the OpenSSL venv python (see run_deep_dive.sh) so the
AYHL scrape passes Cloudflare.
"""
from __future__ import annotations

import os
import subprocess
import sys

import psycopg2

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_VENV_PY = os.path.join(SCRIPT_DIR, ".venv", "bin", "python")
PYTHON = os.environ.get("PYTHON", _VENV_PY if os.path.exists(_VENV_PY) else sys.executable)


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


def main() -> None:
    conn = get_conn()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT id, user_id FROM deep_dive_requests WHERE status='PENDING' ORDER BY requested_at")
        pending = cur.fetchall()
        if not pending:
            return
        print(f"[{datetime_now()}] {len(pending)} pending deep-dive request(s)", flush=True)
        # Auto-link new/updated users first so queued requests (e.g. from signup)
        # actually have identity links to deep-dive.
        run([PYTHON, os.path.join(SCRIPT_DIR, "identity_link.py")])
        for req_id, user_id in pending:
            cur.execute(
                "UPDATE deep_dive_requests SET status='RUNNING', started_at=NOW() WHERE id=%s",
                (req_id,))
            conn.commit()
            error = None
            try:
                cur.execute(
                    "SELECT source, source_player_id FROM player_identity_map "
                    "WHERE user_id=%s AND link_state='CONFIRMED'", (user_id,))
                links = cur.fetchall()
                if not links:
                    print(f"[{req_id}] user {user_id}: no identity links", flush=True)
                for source, spid in links:
                    if source == "AYHL":
                        cmd = [PYTHON, os.path.join(SCRIPT_DIR, "ayhl_deep.py"),
                               "--player-id", str(spid), "--all-seasons"]
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
            conn.commit()
            print(f"[{req_id}] user {user_id}: {'FAILED - ' + error if error else 'COMPLETED'}", flush=True)
    finally:
        conn.close()


def datetime_now():
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


if __name__ == "__main__":
    main()
