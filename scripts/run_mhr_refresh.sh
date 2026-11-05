#!/usr/bin/env bash
# ============================================================================
# MyHockeyStats — MHR games/rankings refresh (evening)
# ============================================================================
# run_daily.sh imports MHR at ~03:00, so a game played in the afternoon does
# not appear until the next morning. This light refresh only re-reads the
# team pages for the (few) confirmed MHR team links and takes well under a
# minute, so evening games and their rankings show up the same day.
#
# Usage:   ./scripts/run_mhr_refresh.sh [--season 2026]
# Cron:    0 20 * * * /bin/bash .../scripts/run_mhr_refresh.sh >> .../logs/mhr-refresh.log 2>&1
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$PROJECT_DIR/logs"
mkdir -p "$LOG_DIR"

export PATH="$HOME/.local/bin:$PATH"

export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-hockey_stats}"
export DB_USER="${DB_USER:-admin}"
export DB_PASSWORD="${DB_PASSWORD:-your_secure_password}"

PYTHON="$PROJECT_DIR/scripts/deep_dive/.venv/bin/python"
if [ ! -x "$PYTHON" ]; then
    PYTHON="python3"
fi

SEASON_ARG=""
if [ "${1:-}" = "--season" ]; then
    SEASON_ARG="--season $2"
fi

echo "─────────────────────────────────────────────────────────────────────"
echo " MHR refresh $(date '+%Y-%m-%d %H:%M:%S')"
echo "─────────────────────────────────────────────────────────────────────"
cd "$PROJECT_DIR"
"$PYTHON" scripts/mhr/mhr_ingest.py $SEASON_ARG 2>&1 | tee -a "$LOG_DIR/mhr-ingest.log"
echo "  done $(date '+%H:%M:%S')"
