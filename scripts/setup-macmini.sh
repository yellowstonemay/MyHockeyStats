#!/usr/bin/env bash
# ============================================================================
# MyHockeyStats — Mac Mini M4 One-Time Setup
# ============================================================================
# Run this ONCE on the Mac mini to install all scraping dependencies
# and set up cron jobs for daily/weekly updates.
#
# Usage:
#   ssh ethan-macmini@192.168.1.156
#   cd ~/hockey-server
#   bash scripts/setup-macmini.sh
# ============================================================================

set -euo pipefail

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   MyHockeyStats — Mac Mini M4 Setup                          ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

PROJECT_DIR="$HOME/hockey-server"
SCRIPTS_DIR="$PROJECT_DIR/scripts"

# ─── 1. Install Python dependencies ────────────────────────────────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [1/5] Installing Python packages..."
echo "─────────────────────────────────────────────────────────────────────────"
pip3 install --quiet --upgrade pip 2>&1 | tail -1
pip3 install --quiet \
    psycopg2-binary \
    requests \
    beautifulsoup4 \
    pandas \
    playwright \
    lxml 2>&1 | tail -5
# Install Playwright browsers (needed for scrape_league.py)
playwright install chromium 2>&1 | tail -3
echo "  ✓ Python packages installed"
echo ""

# ─── 2. Install Node.js dependencies ───────────────────────────────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [2/5] Installing Node.js packages..."
echo "─────────────────────────────────────────────────────────────────────────"
cd "$SCRIPTS_DIR/thf-js"
npm install 2>&1 | tail -3
echo "  ✓ Node.js packages installed"
echo ""

# ─── 3. Create log directory ───────────────────────────────────────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [3/5] Creating log directory..."
echo "─────────────────────────────────────────────────────────────────────────"
mkdir -p "$PROJECT_DIR/logs"
echo "  ✓ Logs: $PROJECT_DIR/logs/"
echo ""

# ─── 4. Make runner scripts executable ─────────────────────────────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [4/5] Making scripts executable..."
echo "─────────────────────────────────────────────────────────────────────────"
chmod +x "$SCRIPTS_DIR/run_daily.sh"
chmod +x "$SCRIPTS_DIR/run_weekly.sh"
echo "  ✓ Scripts ready"
echo ""

# ─── 5. Set up cron jobs ───────────────────────────────────────────────────
echo "─────────────────────────────────────────────────────────────────────────"
echo "  [5/5] Setting up cron schedule..."
echo "─────────────────────────────────────────────────────────────────────────"

CRON_DAILY="0 6 * * * $PROJECT_DIR/scripts/run_daily.sh >> $PROJECT_DIR/logs/daily.log 2>&1"
CRON_WEEKLY="0 7 * * 1 $PROJECT_DIR/scripts/run_weekly.sh >> $PROJECT_DIR/logs/weekly.log 2>&1"

# Add to crontab (skip if already present)
(crontab -l 2>/dev/null | grep -q "$CRON_DAILY") \
    || (crontab -l 2>/dev/null; echo "$CRON_DAILY") | crontab -

(crontab -l 2>/dev/null | grep -q "$CRON_WEEKLY") \
    || (crontab -l 2>/dev/null; echo "$CRON_WEEKLY") | crontab -

echo "  ✓ Cron jobs installed:"
echo "    Daily:   6:00 AM every day     — $PROJECT_DIR/scripts/run_daily.sh"
echo "    Weekly:  7:00 AM every Monday  — $PROJECT_DIR/scripts/run_weekly.sh"
echo ""

# ─── Summary ───────────────────────────────────────────────────────────────
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   ✅ Mac Mini M4 setup complete!                             ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Run manually anytime:"
echo "    bash ~/hockey-server/scripts/run_daily.sh"
echo "    bash ~/hockey-server/scripts/run_weekly.sh"
echo ""
echo "  View logs:"
echo "    tail -f ~/hockey-server/logs/daily.log"
echo "    tail -f ~/hockey-server/logs/weekly.log"
echo "    tail -f ~/hockey-server/logs/ayhl-daily.log"
echo "    tail -f ~/hockey-server/logs/thf-rosters.log"
echo ""
echo "  Check crontab:"
echo "    crontab -l"
echo ""
