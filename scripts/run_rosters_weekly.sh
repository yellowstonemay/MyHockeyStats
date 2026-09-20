#!/usr/bin/env bash
# ============================================================================
# MyHockeyStats — Weekly AHF Roster Update (rosters + live stats)
# ============================================================================
# Runs on the Mac mini via cron every Monday at 8:00 AM.
# Refreshes AHF 2026 rosters, capturing each player's current stats, then
# derives the career / change-event rows from the resulting CSV.
#
# WHY AHF STAYS WEEKLY WHILE AYHL ROSTERS MOVED TO MONTHLY:
#   scrape_rosters.js captures roster membership *and* per-player season stats
#   (gp, goals, assists, points, pims, ppg, sog). Those stats change after
#   every game and `ahf_rosters` is their only source — nothing else derives
#   an AHF stat row. AYHL's `ayhl_roster` holds membership only, so monthly
#   is enough there (see run_monthly.sh).
#
# NOTE: the THF half of this script was deliberately removed. run_daily.sh
#   already refreshes THF rosters every morning, and the duplicated THF scrape
#   is what killed this job on 2026-09-14: a transient DNS error
#   (net::ERR_NAME_NOT_RESOLVED) raised an unhandled puppeteer
#   `TargetCloseError`, and `set -e` aborted the script before the AHF step
#   ever ran — which is why ahf_rosters sat stale from 2026-09-07.
#   This script therefore does NOT use `set -e`; every step guards itself so
#   one failing source cannot suppress the others.
#
# Usage:
#   ./scripts/run_rosters_weekly.sh            # season 2026 (default)
#   ./scripts/run_rosters_weekly.sh 2025       # explicit season
#
# Schedule (Mac mini cron — every Monday at 8:00 AM):
#   crontab -e
#   0 8 * * 1 ~/hockey-server/scripts/run_rosters_weekly.sh >> ~/hockey-server/logs/rosters-weekly.log 2>&1
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
THF_DIR="$SCRIPT_DIR/thf-js"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$PROJECT_DIR/logs"
TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"

mkdir -p "$LOG_DIR"

# ─── Season (default 2026) ─────────────────────────────────────────────────
SEASON="${1:-2026}"
AHF_ROSTER_CSV="$THF_DIR/$SEASON-ahf-rosters.csv"

# ─── DB connection (Mac mini local Postgres in Docker) ────────────────────
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-hockey_stats}"
export DB_USER="${DB_USER:-admin}"
export DB_PASSWORD="${DB_PASSWORD:-your_secure_password}"

# ─── Node (installed at ~/.local/bin on the Mac mini) ──────────────────────
export PATH="$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:$PATH"
NODE="${NODE:-node}"

FAILED=0

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   MyHockeyStats — Weekly AHF Update   $TIMESTAMP   ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# ============================================================================
# STEP 1 — AHF rosters + live per-player stats (season $SEASON)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [1/2] AHF — scrape rosters + stats (season $SEASON)"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$THF_DIR"
$NODE scrape_rosters.js ahf "$SEASON" 2>&1 | tee -a "$LOG_DIR/ahf-rosters.log" \
    || { echo "  ❌ Step 1 failed (AHF roster scrape) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 2 — Derive AHF career + change events from the fresh CSV
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [2/2] AHF — upsert career from roster CSV"
echo "─────────────────────────────────────────────────────────────────────────"
if [[ -f "$AHF_ROSTER_CSV" ]]; then
    $NODE upsert_career_from_roster_csv.js ahf "$SEASON" "$AHF_ROSTER_CSV" 2>&1 \
        | tee -a "$LOG_DIR/ahf-career.log" \
        || { echo "  ❌ Step 2 failed (AHF career) — continuing"; FAILED=1; }
else
    echo "  ⚠  Roster CSV not found, skipping career upsert: $AHF_ROSTER_CSV"
    FAILED=1
fi
echo ""

# ============================================================================
# Summary
# ============================================================================
if [[ "$FAILED" -eq 0 ]]; then
    echo "╔═══════════════════════════════════════════════════════════════╗"
    echo "║   ✅ Weekly AHF update complete — $(date '+%Y-%m-%d %H:%M:%S')   ║"
    echo "╚═══════════════════════════════════════════════════════════════╝"
else
    echo "╔═══════════════════════════════════════════════════════════════╗"
    echo "║   ⚠  Weekly AHF update finished with errors — $(date '+%Y-%m-%d %H:%M:%S')   ║"
    echo "╚═══════════════════════════════════════════════════════════════╝"
fi
echo ""
echo "Logs: $LOG_DIR/rosters-weekly.log"
echo "      $LOG_DIR/ahf-rosters.log"
echo "      $LOG_DIR/ahf-career.log"
echo ""

exit "$FAILED"
