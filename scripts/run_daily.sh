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
#   0 3 * * * /bin/bash /Users/ethan-macmini/hockey-server/scripts/run_daily.sh >> /Users/ethan-macmini/hockey-server/logs/daily.log 2>&1
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$PROJECT_DIR/logs"
TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"

mkdir -p "$LOG_DIR"

# ─── Node (installed at ~/.local/bin on the Mac mini) ──────────────────────
export PATH="$HOME/.local/bin:$PATH"

# ─── DB connection (Mac mini local Postgres in Docker) ─────────────────────
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-hockey_stats}"
export DB_USER="${DB_USER:-admin}"
export DB_PASSWORD="${DB_PASSWORD:-your_secure_password}"

# The AYHL/Gamesheet scripts take DB settings as flags (their built-in
# defaults point at a local dev database), while the node and MHR scripts
# read the DB_* variables exported above.
DB_ARGS=(--host "$DB_HOST" --port "$DB_PORT" --dbname "$DB_NAME" \
         --user "$DB_USER" --password "$DB_PASSWORD")

# Track failures per step: one broken source must not stop the rest of the
# pipeline. MHR runs last and used to be skipped entirely whenever an
# earlier step failed.
FAILED=0

# ─── Parse args ────────────────────────────────────────────────────────────
SEASON=""
SEASON_YEAR=""
DRY_RUN=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --season)    SEASON="--season $2"; SEASON_YEAR="$2"; shift 2 ;;
        --dry-run)   DRY_RUN="--dry-run"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

# The python scrapers take `--season <year>`; the node scripts take a bare
# year (same default as run_rosters_weekly.sh).
SEASON_YEAR="${SEASON_YEAR:-2026}"

THF_DIR="$SCRIPT_DIR/thf-js"
THF_ROSTER_CSV="$THF_DIR/$SEASON_YEAR-thf-rosters.csv"

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
echo "  [1/6] AYHL — Daily career stats update"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$PROJECT_DIR"
$PYTHON scripts/ayhl/daily_update.py $SEASON $DRY_RUN "${DB_ARGS[@]}" 2>&1 | tee -a "$LOG_DIR/ayhl-daily.log" \
    || { echo "  ❌ Step 1 failed (AYHL career) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 2 — AYHL Per-Game Stats (daily)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [2/6] AYHL — Per-game stats scrape"
echo "─────────────────────────────────────────────────────────────────────────"
$PYTHON scripts/ayhl/scrape_player_games.py $SEASON $DRY_RUN "${DB_ARGS[@]}" 2>&1 | tee -a "$LOG_DIR/ayhl-games.log" \
    || { echo "  ❌ Step 2 failed (AYHL per-game) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 3 — THF Rosters (daily)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [3/6] THF — Scrape rosters (season $SEASON_YEAR)"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$THF_DIR"
$NODE scrape_rosters.js thf "$SEASON_YEAR" 2>&1 | tee -a "$LOG_DIR/thf-rosters.log" \
    || { echo "  ❌ Step 3 failed (THF rosters) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 4 — THF Career from Rosters (daily)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [4/6] THF — Upsert career from roster CSV (season $SEASON_YEAR)"
echo "─────────────────────────────────────────────────────────────────────────"
$NODE upsert_career_from_roster_csv.js thf "$SEASON_YEAR" "$THF_ROSTER_CSV" 2>&1 | tee -a "$LOG_DIR/thf-career.log" \
    || { echo "  ❌ Step 4 failed (THF career) — continuing"; FAILED=1; }
echo ""

# ============================================================================
# STEP 5 — Gamesheet Stats (daily, only if CSV files exist)
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [5/6] Gamesheet — Fetch and load career stats"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$SCRIPT_DIR/gamesheet"
if [ -f "6579_players.csv" ] && [ -f "6579_players_stats.csv" ]; then
    $PYTHON load_gamesheet_career_to_db.py $DRY_RUN "${DB_ARGS[@]}" 2>&1 | tee -a "$LOG_DIR/gamesheet.log" \
        || { echo "  ❌ Step 5 failed (Gamesheet) — continuing"; FAILED=1; }
else
    echo "  ⚠  Gamesheet CSV files not found — skipping (run fetch_player_career_stats.py first)"
fi
echo ""

# ============================================================================
# STEP 6 — MYHockeyRankings non-league games (daily)
# Runs after the AYHL career scrape: team resolution needs the current-season
# career rows to know which club each player is on.
# ============================================================================
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [6/6] MHR — Resolve teams + import non-league games"
echo "─────────────────────────────────────────────────────────────────────────"
cd "$PROJECT_DIR"
$PYTHON scripts/mhr/mhr_resolve.py $SEASON $DRY_RUN 2>&1 | tee -a "$LOG_DIR/mhr-resolve.log" \
    || { echo "  ❌ MHR resolve failed — continuing (see $LOG_DIR/mhr-resolve.log)"; FAILED=1; }
$PYTHON scripts/mhr/mhr_ingest.py $SEASON $DRY_RUN 2>&1 | tee -a "$LOG_DIR/mhr-ingest.log" \
    || { echo "  ❌ MHR ingest failed — continuing (see $LOG_DIR/mhr-ingest.log)"; FAILED=1; }
echo ""

# ============================================================================
# Summary
# ============================================================================
echo "╔═══════════════════════════════════════════════════════════════╗"
if [ "$FAILED" -eq 0 ]; then
    echo "║   ✅ Daily update complete — $(date '+%Y-%m-%d %H:%M:%S')   ║"
else
    echo "║   ⚠  Daily update finished with errors — $(date '+%Y-%m-%d %H:%M:%S')   ║"
fi
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""
echo "Logs: $LOG_DIR/"
echo ""
exit "$FAILED"
