#!/usr/bin/env bash
# ============================================================================
# MyHockeyStats — Monthly Roster Refresh (low-volatility sources)
# ============================================================================
# Runs on the Mac mini via cron on the 1st of each month at 7:00 AM.
#
# WHAT LIVES HERE AND WHY:
#   This script holds only the sources whose data is *membership* rather than
#   live stats, so refresh frequency has no effect on what parents see.
#
#   * AYHL team discovery + rosters (ayhl_roster) — ayhl_roster stores roster
#     membership only (name, jersey, position, height, weight, birth year,
#     hometown). It has no gp/goals/assists columns, so nothing in it goes
#     stale mid-month. Measured churn confirms it: ayhl_change_event is
#     dominated by membership/team churn that clusters at the season boundary.
#
#   * THF + AHF team lists — the *list of teams* in each league, which only
#     changes when a league adds or drops a program. These feed the daily
#     (THF) and weekly (AHF) roster scrapes, which iterate the team list, so
#     a new team would otherwise never be picked up.
#
# WHAT DOES *NOT* BELONG HERE:
#   AHF rosters. They carry live per-player stats (gp/goals/assists/points/
#   pims/ppg/sog) that change after every game, and nothing else derives those
#   numbers — ahf_rosters is the only source. They stay weekly in
#   run_rosters_weekly.sh. THF rosters stay daily in run_daily.sh for the same
#   reason.
#
# Usage:
#   ./scripts/run_monthly.sh            # season 2026 (default)
#   ./scripts/run_monthly.sh 2025       # explicit season
#
# Schedule (Mac mini cron — 1st of each month at 7:00 AM):
#   crontab -e
#   0 7 1 * * /bin/bash /Users/ethan-macmini/hockey-server/scripts/run_monthly.sh >> /Users/ethan-macmini/hockey-server/logs/monthly.log 2>&1
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
THF_DIR="$SCRIPT_DIR/thf-js"
AYHL_DIR="$SCRIPT_DIR/ayhl"
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

DB_ARGS=(--host "$DB_HOST" --port "$DB_PORT" --dbname "$DB_NAME" --user "$DB_USER" --password "$DB_PASSWORD")

# ─── Node (installed at ~/.local/bin on the Mac mini) ──────────────────────
export PATH="$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:$PATH"
NODE="${NODE:-node}"
PYTHON="${PYTHON:-python3}"

FAILED=0

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   MyHockeyStats — Monthly Roster Refresh   $TIMESTAMP   ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# ============================================================================
# STEP 1 — Discover AYHL teams/leagues
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [1/5] AYHL — discover teams (season $SEASON)"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$PROJECT_DIR"
$PYTHON "$AYHL_DIR/discover_teams.py" --season "$SEASON" "${DB_ARGS[@]}" 2>&1 \
    | tee -a "$LOG_DIR/monthly.log" \
    || { echo "  ❌ Step 1 failed (AYHL discovery) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 2 — Scrape AYHL rosters (membership only, no stats)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [2/5] AYHL — scrape rosters (season $SEASON)"
echo "─────────────────────────────────────────────────────────────────────────"
$PYTHON "$AYHL_DIR/scrape_rosters.py" --season "$SEASON" "${DB_ARGS[@]}" 2>&1 \
    | tee -a "$LOG_DIR/monthly.log" \
    || { echo "  ❌ Step 2 failed (AYHL roster scrape) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 3 — Load AYHL rosters into ayhl_roster
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [3/5] AYHL — load rosters into DB"
echo "─────────────────────────────────────────────────────────────────────────"
$PYTHON "$AYHL_DIR/load_rosters_to_db.py" "${DB_ARGS[@]}" 2>&1 \
    | tee -a "$LOG_DIR/monthly.log" \
    || { echo "  ❌ Step 3 failed (AYHL DB load) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 4 — Refresh the THF team list (consumed by run_daily.sh)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [4/5] THF — refresh team list (season $SEASON)"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$THF_DIR"
$NODE scrape_teams.js thf "$SEASON" 2>&1 \
    | tee -a "$LOG_DIR/monthly.log" \
    || { echo "  ❌ Step 4 failed (THF team discovery) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 5 — Refresh the AHF team list (consumed by run_rosters_weekly.sh)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [5/5] AHF — refresh team list (season $SEASON)"
echo "─────────────────────────────────────────────────────────────────────────"
$NODE scrape_teams.js ahf "$SEASON" 2>&1 \
    | tee -a "$LOG_DIR/monthly.log" \
    || { echo "  ❌ Step 5 failed (AHF team discovery) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# Summary
# ============================================================================
if [[ "$FAILED" -eq 0 ]]; then
    echo "╔═══════════════════════════════════════════════════════════════╗"
    echo "║   ✅ Monthly roster refresh complete — $(date '+%Y-%m-%d %H:%M:%S')   ║"
    echo "╚═══════════════════════════════════════════════════════════════╝"
else
    echo "╔═══════════════════════════════════════════════════════════════╗"
    echo "║   ⚠  Monthly roster refresh finished with errors — $(date '+%Y-%m-%d %H:%M:%S')   ║"
    echo "╚═══════════════════════════════════════════════════════════════╝"
fi
echo ""
echo "Log: $LOG_DIR/monthly.log"
echo ""

exit "$FAILED"
