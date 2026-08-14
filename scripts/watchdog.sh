#!/bin/bash
# MyHockeyStats watchdog — keep the public site (youthhockeystats.us) up.
#
# Runs every 2 minutes via cron. If the public URL stops returning 200 it:
#   1. starts Docker Desktop (if the daemon is down),
#   2. brings the containers back up,
#   3. restarts the cloudflared tunnel (via launchd KeepAlive) if its
#      Cloudflare connections dropped to zero.
#
# Requires a passwordless sudo rule so it can kill the root cloudflared
# daemon (see /etc/sudoers.d/cloudflared-watchdog). All other steps run as the
# normal user.
LOG="$HOME/hockey-server/logs/watchdog.log"
URL="https://youthhockeystats.us/"
CLOUDFLARED="/opt/homebrew/bin/cloudflared"
export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

stamp() { date '+%Y-%m-%d %H:%M:%S'; }

CODE=$(curl -s -o /dev/null --max-time 20 -w '%{http_code}' "$URL" 2>/dev/null)
if [ "$CODE" = "200" ]; then
  exit 0
fi
echo "$(stamp) SITE DOWN (HTTP ${CODE:-none}) - attempting recovery" >> "$LOG"
mkdir -p "$HOME/hockey-server/logs"

# 1. Docker daemon up?
if ! docker info >/dev/null 2>&1; then
  echo "$(stamp) Docker daemon down - launching Docker Desktop" >> "$LOG"
  open -a Docker 2>/dev/null
  for _ in $(seq 1 40); do
    docker info >/dev/null 2>&1 && break
    sleep 3
  done
fi

# 2. Containers up (idempotent - only recreates what's missing)
if docker info >/dev/null 2>&1; then
  docker compose -f "$HOME/hockey-server/docker-compose.macmini.yml" up -d >> "$LOG" 2>&1
else
  echo "$(stamp) Docker still not available after launch attempt" >> "$LOG"
fi

# 3. cloudflared tunnel has connections? (or daemon not running)
CONN=$("$CLOUDFLARED" tunnel list 2>/dev/null | grep 'mac-server' | grep -oE '[0-9]+x[a-zA-Z0-9]+' | head -1)
if [ -z "$CONN" ] || ! pgrep -x cloudflared >/dev/null 2>&1; then
  echo "$(stamp) cloudflared down/no connections - kickstarting launchd daemon" >> "$LOG"
  sudo -n /bin/launchctl kickstart -k system/com.cloudflare.cloudflared >> "$LOG" 2>&1 \
    || echo "$(stamp) kickstart failed - check /etc/sudoers.d/cloudflared-watchdog" >> "$LOG"
  sleep 15
fi

# 4. Final check
CODE2=$(curl -s -o /dev/null --max-time 20 -w '%{http_code}' "$URL" 2>/dev/null)
echo "$(stamp) after recovery: HTTP ${CODE2:-none}" >> "$LOG"
