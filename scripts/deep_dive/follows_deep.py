#!/usr/bin/env python3
"""Scrape followed players (daily) so the "Following" feature stays fresh.

Reads all rows in the `follows` table, resolves each followed player's source,
and runs the same per-player scrapers used for registered-user identity links
(ayhl_deep / thf_ahf_deep / njhs_deep). Runs as a step in run_deep_dive.sh
(after identity-linked users are processed). Uses the OpenSSL venv python so
the AYHL scrape passes Cloudflare.

Current-season only (default scraper mode) — followed players don't need the
full all-seasons backfill.
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


def main() -> None:
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT DISTINCT source, source_player_id FROM follows "
                "ORDER BY source, source_player_id")
            rows = cur.fetchall()
        if not rows:
            print("[follows_deep] no followed players", flush=True)
            return
        print(f"[follows_deep] {len(rows)} followed player(s)", flush=True)
        for source, spid in rows:
            if source == "AYHL":
                cmd = [PYTHON, os.path.join(SCRIPT_DIR, "ayhl_deep.py"),
                       "--player-id", str(spid)]
            elif source == "NJHS":
                cmd = [PYTHON, os.path.join(SCRIPT_DIR, "njhs_deep.py"),
                       "--player-slug", str(spid)]
            else:  # THF / AHF
                cmd = [PYTHON, os.path.join(SCRIPT_DIR, "thf_ahf_deep.py"),
                       "--player-id", str(spid), "--source", source]
            print(f">>> {' '.join(cmd)}", flush=True)
            rc = subprocess.run(cmd).returncode
            if rc != 0:
                print(f"[follows_deep] {source}:{spid} failed", flush=True)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
