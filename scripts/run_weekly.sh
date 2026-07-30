#!/usr/bin/env bash
# ============================================================================
# MyHockeyStats — Weekly Data Pipeline
# ============================================================================
# Runs all weekly scraping/import tasks (team discovery, roster refresh).
# Designed to run on the Mac mini via cron.
#
# Usage:
#   ./scripts/run_weekly.sh                  # auto-detect season
#   ./scripts/run_weekly.sh --season 2025    # explicit season
#   ./scripts/run_weekly.sh --dry-run        # skip DB writes
#
# Schedule (Mac mini cron — every Monday at 7 AM):
#   crontab -e
#   0 7 * * 1 ~/hockey-server/scripts/run_weekly.sh >> ~/hockey-server/logs/weekly.log 2>&1
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$PROJECT_DIR/logs"
TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"

mkdir -p "$LOG_DIR"

# ─── Parse args ────────────────────────────────────────────────────────────
SEASON=""
DRY_RUN=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --season)    SEASON="--season $2"; shift 2 ;;
        --dry-run)   DRY_RUN="--dry-run"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

PYTHON="${PYTHON:-python3}"
NODE="${NODE:-node}"

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   MyHockeyStats — Weekly Update   $TIMESTAMP   ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# ============================================================================
# STEP 1 — AYHL Team Discovery & Rosters (weekly)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [1/3] AYHL — Weekly team/roster update"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$PROJECT_DIR"
$PYTHON scripts/ayhl/weekly_update.py $SEASON $DRY_RUN 2>&1 | tee -a "$LOG_DIR/ayhl-weekly.log"
echo ""

# ============================================================================
# STEP 2 — THF Teams (weekly)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [2/3] THF — Scrape teams"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$SCRIPT_DIR/thf-js"
$NODE scrape_teams.js 2>&1 | tee -a "$LOG_DIR/thf-teams.log"
echo ""

# ============================================================================
# STEP 3 — THF Games (weekly)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [3/3] THF — Scrape games"
echo "─────────────────────────────────────────────────────────────────────────"
$NODE scrape_games.js 2>&1 | tee -a "$LOG_DIR/thf-games.log"
echo ""

# ============================================================================
# Summary
# ============================================================================
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   ✅ Weekly update complete — $(date '+%Y-%m-%d %H:%M:%S')        ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""
echo "Logs: $LOG_DIR/"
echo ""
