#!/usr/bin/env bash
# ============================================================================
# MyHockeyStats — Weekly THF/AHF Roster Update (rosters + live stats)
# ============================================================================
# Runs on the Mac mini via cron every Monday at 8:00 AM.
# Refreshes THF + AHF 2026 rosters, capturing each player's current stats.
#
# Usage:
#   ./scripts/run_rosters_weekly.sh            # season 2026 (default)
#   ./scripts/run_rosters_weekly.sh 2025       # explicit season
#
# Schedule (Mac mini cron — every Monday at 8:00 AM):
#   crontab -e
#   0 8 * * 1 ~/hockey-server/scripts/run_rosters_weekly.sh >> ~/hockey-server/logs/rosters-weekly.log 2>&1
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
THF_DIR="$SCRIPT_DIR/thf-js"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$PROJECT_DIR/logs"
TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"

mkdir -p "$LOG_DIR"

# ─── Season (default 2026) ─────────────────────────────────────────────────
SEASON="${1:-2026}"

# ─── DB connection (Mac mini local Postgres in Docker) ────────────────────
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-hockey_stats}"
export DB_USER="${DB_USER:-admin}"
export DB_PASSWORD="${DB_PASSWORD:-your_secure_password}"

# ─── Node (installed at ~/.local/bin on the Mac mini) ──────────────────────
export PATH="$HOME/.local/bin:$PATH"
NODE="${NODE:-node}"

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   MyHockeyStats — Weekly Roster Update   $TIMESTAMP   ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# ============================================================================
# STEP 1 — THF rosters (season $SEASON)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [1/2] THF — scrape rosters (season $SEASON)"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$THF_DIR"
$NODE scrape_rosters.js thf "$SEASON" 2>&1 | tee -a "$LOG_DIR/thf-rosters.log"
echo ""

# ============================================================================
# STEP 2 — AHF rosters (season $SEASON)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [2/2] AHF — scrape rosters (season $SEASON)"
echo "─────────────────────────────────────────────────────────────────────────"
$NODE scrape_rosters.js ahf "$SEASON" 2>&1 | tee -a "$LOG_DIR/ahf-rosters.log"
echo ""

# ============================================================================
# Summary
# ============================================================================
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   ✅ Weekly roster update complete — $(date '+%Y-%m-%d %H:%M:%S')   ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""
echo "Logs: $LOG_DIR/rosters-weekly.log"
echo "      $LOG_DIR/thf-rosters.log"
echo "      $LOG_DIR/ahf-rosters.log"
echo ""
