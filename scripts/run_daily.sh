#!/usr/bin/env bash
# ============================================================================
# MyHockeyStats — Daily Data Pipeline
# ============================================================================
# Runs all daily scraping/import tasks in order.
# Designed to run on the Mac mini via cron (or SSH from Windows).
#
# Usage:
#   ./scripts/run_daily.sh                  # auto-detect season
#   ./scripts/run_daily.sh --season 2025    # explicit season
#   ./scripts/run_daily.sh --dry-run        # skip DB writes
#
# Schedule (Mac mini cron):
#   crontab -e
#   0 6 * * * ~/hockey-server/scripts/run_daily.sh >> ~/hockey-server/logs/daily.log 2>&1
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
echo "║   MyHockeyStats — Daily Update    $TIMESTAMP   ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# ============================================================================
# STEP 1 — AYHL Career Stats (daily)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [1/5] AYHL — Daily career stats update"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$PROJECT_DIR"
$PYTHON scripts/ayhl/daily_update.py $SEASON $DRY_RUN 2>&1 | tee -a "$LOG_DIR/ayhl-daily.log"
echo ""

# ============================================================================
# STEP 2 — AYHL Per-Game Stats (daily)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [2/5] AYHL — Per-game stats scrape"
echo "─────────────────────────────────────────────────────────────────────────"
$PYTHON scripts/ayhl/scrape_player_games.py $SEASON $DRY_RUN 2>&1 | tee -a "$LOG_DIR/ayhl-games.log"
echo ""

# ============================================================================
# STEP 3 — THF Rosters (daily)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [3/5] THF — Scrape rosters"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$SCRIPT_DIR/thf-js"
$NODE scrape_rosters.js 2>&1 | tee -a "$LOG_DIR/thf-rosters.log"
echo ""

# ============================================================================
# STEP 4 — THF Career from Rosters (daily)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [4/5] THF — Upsert career from roster CSV"
echo "─────────────────────────────────────────────────────────────────────────"
$NODE upsert_career_from_roster_csv.js 2>&1 | tee -a "$LOG_DIR/thf-career.log"
echo ""

# ============================================================================
# STEP 5 — Gamesheet Stats (daily, only if CSV files exist)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [5/5] Gamesheet — Fetch and load career stats"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$SCRIPT_DIR/gamesheet"
if [ -f "6579_players.csv" ] && [ -f "6579_players_stats.csv" ]; then
    $PYTHON load_gamesheet_career_to_db.py $DRY_RUN 2>&1 | tee -a "$LOG_DIR/gamesheet.log"
else
    echo "  ⚠  Gamesheet CSV files not found — skipping (run fetch_player_career_stats.py first)"
fi
echo ""

# ============================================================================
# Summary
# ============================================================================
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   ✅ Daily update complete — $(date '+%Y-%m-%d %H:%M:%S')         ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""
echo "Logs: $LOG_DIR/"
echo ""
