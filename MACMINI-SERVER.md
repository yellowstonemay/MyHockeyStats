# MyHockeyStats — Mac Mini M4 Server Operations Guide

> **Server**: Apple Mac Mini M4 — `ethan-macmini`  
> **IP**: `192.168.1.156`  
> **OS**: macOS  
> **Purpose**: Hosts PostgreSQL database + MyHockeyStats web app, runs daily data scrapers

---

## Table of Contents

1. [SSH Access](#1-ssh-access)
2. [Database](#2-database)
3. [Web Application](#3-web-application)
4. [Deployment](#4-deployment)
5. [Scraping Pipeline](#5-scraping-pipeline)
6. [Cloudflare Tunnel](#6-cloudflare-tunnel)
7. [Local Development](#7-local-development)
8. [Troubleshooting](#8-troubleshooting)
9. [File Reference](#9-file-reference)

---

## 1. SSH Access

### From Windows (PowerShell)

```powershell
ssh ethan-macmini@192.168.1.156
```

### First-time setup (SSH keys — optional, skips password prompt)

```powershell
# On Windows, generate a key if you don't have one:
ssh-keygen -t ed25519

# Copy to Mac mini (will prompt for password):
ssh-copy-id ethan-macmini@192.168.1.156
```

---

## 2. Database

### Connection Details

| Parameter | Value |
|-----------|-------|
| **Host** | `192.168.1.156` |
| **Port** | `5432` |
| **Database** | `hockey_stats` |
| **Username** | `admin` |
| **Password** | `your_secure_password` |

### Quick Status Check

```bash
# SSH into Mac mini, then:
docker ps                     # Should see hockey-postgres running
docker logs hockey-postgres   # View DB logs
```

### Start / Stop Database

```bash
# Start everything (Postgres + Backend + Frontend):
cd ~/hockey-server
docker compose -f docker-compose.macmini.yml up -d

# Stop everything:
docker compose -f docker-compose.macmini.yml down

# Database only:
docker compose -f docker-compose.macmini.yml up -d postgres-db
```

### Restore Database from Recovered Backup

If the Mac mini database is empty and you have a recovered database container locally:

```powershell
# On Windows with Docker running:
# 1. Start the recovered DB container
docker start recovered-hockey-db

# 2. Dump and pipe directly to Mac mini:
docker exec recovered-hockey-db pg_dump -U postgres -d myhockeystats --no-owner --no-acl -f /tmp/dump.sql
docker exec recovered-hockey-db cat /tmp/dump.sql | docker run -i --rm --network host -e PGPASSWORD=your_secure_password postgres:15 psql -h 192.168.1.156 -U admin -d hockey_stats
```

---

## 3. Web Application

When running on the Mac mini:

| Service | URL (LAN) | URL (via Cloudflare) |
|---------|-----------|----------------------|
| **Frontend** | `http://192.168.1.156:80` | `https://www.your-domain.com` |
| **Backend API** | `http://192.168.1.156:8080` | `https://api.your-domain.com` |
| **Health Check** | `http://192.168.1.156:8080/health` | — |

### Test Accounts

| Email | Password |
|-------|----------|
| `ethan.yan@example.com` | `TestPassword123` |
| `paden.zhou@example.com` | `TestPassword123` |
| `zhoupeng.hust@gmail.com` | (user-set) |

---

## 4. Deployment

### One-Click Deploy (from Windows)

```powershell
cd C:\ethan-ai\MyHockeyStats
.\scripts\deploy-to-macmini.ps1
```

**What it does:**
1. Archives the project (excludes `node_modules`, `target`, `.git`, etc.)
2. Copies to Mac mini via SCP (`~/hockey-server/`)
3. SSHs in, extracts, builds Docker images natively on ARM64
4. Starts all services via `docker compose -f docker-compose.macmini.yml up -d`

### Manual Deploy (step by step)

```bash
# On Windows — package and copy:
cd C:\ethan-ai\MyHockeyStats
tar czf deploy.tar.gz --exclude=node_modules --exclude=target --exclude=.git --exclude=__pycache__ --exclude=.venv docker-compose.macmini.yml backend/ frontend/ scripts/
scp deploy.tar.gz ethan-macmini@192.168.1.156:~/hockey-server/

# On Mac mini — extract, build, start:
ssh ethan-macmini@192.168.1.156
cd ~/hockey-server
tar xzf deploy.tar.gz
rm deploy.tar.gz
docker compose -f docker-compose.macmini.yml up -d --build
```

### Rebuild After Code Changes

```bash
ssh ethan-macmini@192.168.1.156
cd ~/hockey-server
docker compose -f docker-compose.macmini.yml up -d --build
```

### View Logs

```bash
ssh ethan-macmini@192.168.1.156
docker logs -f myhockeystats-backend     # Backend (Spring Boot)
docker logs -f myhockeystats-frontend    # Frontend (Nginx)
docker logs -f hockey-postgres           # Database (PostgreSQL)
```

---

## 5. Scraping Pipeline

### Data Sources

| Source | What it scrapes | Frequency | Script(s) |
|--------|----------------|-----------|-----------|
| **AYHL** (atlantichockey.org) | Player career stats | Daily | `scripts/ayhl/daily_update.py` |
| **AYHL** (atlantichockey.org) | Per-game stats | Daily | `scripts/ayhl/scrape_player_games.py` |
| **AYHL** (atlantichockey.org) | Teams & rosters | Weekly | `scripts/ayhl/weekly_update.py` |
| **THF** (thfhockey.com) | Rosters → Career upsert | Daily | `scripts/thf-js/scrape_rosters.js`, `upsert_career_from_roster_csv.js` |
| **THF** (thfhockey.com) | Teams, Games | Weekly | `scripts/thf-js/scrape_teams.js`, `scrape_games.js` |
| **Gamesheet** (gamesheetstats.com) | Career stats from CSV | On-demand | `scripts/gamesheet/fetch_player_career_stats.py`, `load_gamesheet_career_to_db.py` |

### One-Time Mac Mini Setup

```bash
ssh ethan-macmini@192.168.1.156
cd ~/hockey-server
bash scripts/setup-macmini.sh
```

This installs Python packages, Node modules, creates log directories, and sets up cron.

### Automated Schedule (cron)

| Schedule | Script | When |
|----------|--------|------|
| **Daily** 🕐 | `run_daily.sh` | 6:00 AM every day |
| **Weekly** 🕐 | `run_weekly.sh` | 7:00 AM every Monday |

### Manual Run

```bash
ssh ethan-macmini@192.168.1.156
cd ~/hockey-server

# Full daily pipeline:
bash scripts/run_daily.sh

# With specific season:
bash scripts/run_daily.sh --season 2025

# Dry run (scrape only, no DB writes):
bash scripts/run_daily.sh --dry-run

# Weekly pipeline:
bash scripts/run_weekly.sh
```

### Run from Windows (against remote Mac mini DB)

```powershell
# AYHL daily update:
python scripts/ayhl/daily_update.py --host 192.168.1.156 --port 5432 --dbname hockey_stats --user admin --password your_secure_password

# THF rosters (using env vars):
$env:DB_HOST="192.168.1.156"; $env:DB_NAME="hockey_stats"; $env:DB_USER="admin"; $env:DB_PASSWORD="your_secure_password"
node scripts/thf-js/scrape_rosters.js
```

### Logs

All on the Mac mini at `~/hockey-server/logs/`:

```bash
# Check latest daily run:
tail -f ~/hockey-server/logs/daily.log

# Check specific source:
tail -f ~/hockey-server/logs/ayhl-daily.log
tail -f ~/hockey-server/logs/thf-rosters.log
tail -f ~/hockey-server/logs/gamesheet.log

# Check cron schedule:
crontab -l
```

---

## 6. Cloudflare Tunnel

Expose the web app publicly through Cloudflare without opening firewall ports.

### Prerequisites
- A domain registered with Cloudflare (or bought through Cloudflare)

### Setup Steps

```bash
# 1. SSH into Mac mini and install cloudflared:
ssh ethan-macmini@192.168.1.156
brew install cloudflared

# 2. Authenticate (opens a browser URL — copy into Windows browser):
cloudflared tunnel login

# 3. Create the tunnel:
cd ~/hockey-server
cloudflared tunnel create myhockeystats
#   → saves credentials to ~/.cloudflared/myhockeystats.json

# 4. Get the tunnel token (needed for Docker):
cloudflared tunnel token myhockeystats

# 5. Create config file:
cat > ~/hockey-server/config.yml << 'EOF'
tunnel: myhockeystats
credentials-file: /Users/ethan-macmini/.cloudflared/myhockeystats.json

ingress:
  - hostname: api.yourdomain.com
    service: http://localhost:8080
  - hostname: www.yourdomain.com
    service: http://localhost:80
  - service: http_status:404
EOF

# 6. Route DNS:
cloudflared tunnel route dns myhockeystats www.yourdomain.com
cloudflared tunnel route dns myhockeystats api.yourdomain.com

# 7. Run via Docker (uncomment cloudflared in docker-compose.macmini.yml):
export CLOUDFLARE_TUNNEL_TOKEN=your_token_here
docker compose -f docker-compose.macmini.yml up -d cloudflared
```

### Quick Tunnel (for testing, no domain needed)

```bash
cloudflared tunnel --url http://localhost:80
# Gives you a temporary https://random-name.trycloudflare.com URL
```

---

## 7. Local Development

### Run Locally with Remote Mac Mini DB

```powershell
# From C:\ethan-ai\MyHockeyStats:
# Uses docker-compose.remote.yml (no local DB, connects to Mac mini)
docker compose -f docker-compose.remote.yml up -d
```

### Files for Local vs Production

| File | Purpose | When to use |
|------|---------|-------------|
| `docker-compose.yml` | Full local stack with local DB | Development |
| `docker-compose.remote.yml` | Backend + Frontend only, connects to Mac mini DB | Debugging with remote DB |
| `docker-compose.macmini.yml` | Full production stack for Mac mini | Deploy to Mac mini |
| `.env` | Remote DB credentials | Local connection to Mac mini |

---

## 8. Troubleshooting

### Database connection fails

```bash
# On Windows — test connectivity:
Test-NetConnection 192.168.1.156 -Port 5432

# On Mac mini — check Postgres is running:
ssh ethan-macmini@192.168.1.156
docker ps | grep hockey-postgres
```

### Password authentication failed

On the Mac mini, check the actual credentials in the compose file:
```bash
ssh ethan-macmini@192.168.1.156
cat ~/hockey-server/docker-compose.macmini.yml
# Look for POSTGRES_USER, POSTGRES_PASSWORD
```

### Docker not running on Mac mini

```bash
ssh ethan-macmini@192.168.1.156
open /Applications/Docker.app
# Wait 10-15 seconds, then:
docker ps
```

### Flyway migration errors (new/empty database)

If the remote database is fresh with no schema:
1. Restore from the recovered backup (see [section 2](#restore-database-from-recovered-backup))
2. Or restart with `SPRING_FLYWAY_ENABLED=false` to let Hibernate create tables first

### Scripts fail — missing dependencies

```bash
ssh ethan-macmini@192.168.1.156
cd ~/hockey-server
bash scripts/setup-macmini.sh
```

### Permission denied when running scripts

```bash
ssh ethan-macmini@192.168.1.156
chmod +x ~/hockey-server/scripts/*.sh
```

---

## 9. File Reference

### On This Repo (Windows)

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Full local stack (development) |
| `docker-compose.remote.yml` | Local stack with remote Mac mini DB |
| `docker-compose.macmini.yml` | Full production stack for Mac mini |
| `.env` | Remote DB credentials (gitignored) |
| `.github/agents/db-server.agent.md` | VS Code custom agent for Mac mini |
| `scripts/deploy-to-macmini.ps1` | One-click deploy to Mac mini |
| `scripts/setup-macmini.sh` | One-time Mac mini setup (deps + cron) |
| `scripts/run_daily.sh` | Daily scraping pipeline |
| `scripts/run_weekly.sh` | Weekly scraping pipeline |
| `scripts/ayhl/` | AYHL scraper scripts (Python) |
| `scripts/thf-js/` | THF scraper scripts (Node.js) |
| `scripts/gamesheet/` | Gamesheet scraper scripts (Python) |

### On Mac Mini (`~/hockey-server/`)

| File | Purpose |
|------|---------|
| `docker-compose.macmini.yml` | Production docker-compose |
| `config.yml` | Cloudflare tunnel config |
| `logs/` | Scraping pipeline logs |
| `backend/`, `frontend/`, `scripts/` | Deployed source code |
