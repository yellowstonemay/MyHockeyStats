#!/usr/bin/env bash
# ============================================================================
# MyHockeyStats — Registered-User Deep-Dive (daily)
# ============================================================================
# Digs deeper for REGISTERED USERS on top of the regular bulk AYHL/THF/AHF
# scrape pipeline:
#
#   1. identity_link.py   -> auto-link registered users to their source
#                            players (AYHL/THF/AHF) using canonical-name match
#                            (unambiguous -> CONFIRMED, ambiguous -> reported)
#   2. ayhl_deep.py       -> for AYHL-linked users, scrape FULL career history
#                            (all seasons) from atlantichockey.org, upsert to
#                            ayhl_player_career, log stat changes
#   3. thf_ahf_deep.py    -> for THF/AHF-linked users, roll latest roster stats
#                            up into the career tables with change detection
#
# Schedule (Mac mini cron — daily at 9:00 AM):
#   0 9 * * * ~/hockey-server/scripts/deep_dive/run_deep_dive.sh >> ~/hockey-server/logs/deep-dive.log 2>&1
#
# Usage:
#   bash run_deep_dive.sh             # full run
#   bash run_deep_dive.sh --dry-run   # no DB writes
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$PROJECT_DIR/logs"
TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"
mkdir -p "$LOG_DIR"

export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-hockey_stats}"
export DB_USER="${DB_USER:-admin}"
export DB_PASSWORD="${DB_PASSWORD:-your_secure_password}"

PYTHON="${PYTHON:-}"
if [[ -z "$PYTHON" ]]; then
    # Prefer the venv built with the Homebrew (OpenSSL) Python — its TLS
    # fingerprint passes Cloudflare, unlike the system LibreSSL python3.
    if [[ -x "$SCRIPT_DIR/.venv/bin/python" ]]; then
        PYTHON="$SCRIPT_DIR/.venv/bin/python"
    else
        PYTHON="python3"
    fi
fi
DRY=""
if [[ "${1:-}" == "--dry-run" ]]; then DRY="--dry-run"; fi

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   MyHockeyStats — Registered-User Deep-Dive   $TIMESTAMP  ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# ─── Step 1: Identity auto-link ───────────────────────────────────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [1/4] Identity auto-link (registered users -> source players)"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$SCRIPT_DIR"
$PYTHON identity_link.py $DRY 2>&1 | tee -a "$LOG_DIR/deep-dive.log"
echo ""

# ─── Step 2: AYHL deep dive (full career backfill) ────────────────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [2/4] AYHL deep dive (full career history + change detection)"
echo "─────────────────────────────────────────────────────────────────────────"
$PYTHON ayhl_deep.py $DRY 2>&1 | tee -a "$LOG_DIR/deep-dive.log"
echo ""

# ─── Step 3: THF/AHF deep dive (latest roster -> career) ──────────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [3/4] THF/AHF deep dive (latest roster stats -> career)"
echo "─────────────────────────────────────────────────────────────────────────"
$PYTHON thf_ahf_deep.py $DRY 2>&1 | tee -a "$LOG_DIR/deep-dive.log"
echo ""

# ─── Step 4: NJ.com HS hockey (roster refresh + career/games) ─────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [4/4] NJ.com high school hockey (roster refresh + career/games)"
echo "─────────────────────────────────────────────────────────────────────────"
$PYTHON njhs_deep.py --roster $DRY 2>&1 | tee -a "$LOG_DIR/deep-dive.log"
$PYTHON njhs_deep.py $DRY 2>&1 | tee -a "$LOG_DIR/deep-dive.log"
echo ""

echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   ✅ Deep-dive complete — $(date '+%Y-%m-%d %H:%M:%S')   ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo "Log: $LOG_DIR/deep-dive.log"
echo ""
