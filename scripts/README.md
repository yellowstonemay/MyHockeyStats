# Test Data Setup Instructions

## Overview

This directory contains scripts to populate the MyHockeyStats database with test data for Ethan Yan's profile.

## Test Account Credentials

- **Email**: ethan.yan@example.com
- **Password**: TestPassword123
- **Player**: Ethan Yan (born 2011-01-15)
- **Location**: New Jersey, USA
- **Team**: New Jersey Devils Youth AAA (AYHL)
- **Season**: 2025-2026
- **Sample Games**: 10 games with performance statistics

## Setup Methods

### Method 1: SQL Script (Recommended for Production-like Setups)

If Docker Compose is running and the database schema is already created:

```bash
# Connect to the database and load the seed data
psql -h localhost -p 5432 -U postgres -d myhockeystats -f scripts/seed_data.sql
```

### Method 2: Python Script (For Development)

If you prefer to use Python and have the database running:

```bash
# Basic usage with defaults
python scripts/seed_test_data.py

# Custom test player
python scripts/seed_test_data.py \
  --user-email john.doe@example.com \
  --user-password MyPassword123 \
  --full-name "John Doe" \
  --birthdate "2010-05-20" \
  --location "Boston, MA" \
  --team "Boston Bruins Youth AAA" \
  --club "USHL" \
  --num-games 15
```

### Method 3: Using connect_db.py

Test your database connection:

```bash
python scripts/connect_db.py
```

This will verify that your database is accessible and running.

## Database Requirements

Before running either script, ensure:

1. **Docker Compose is running**:
   ```bash
   docker compose up -d
   ```

2. **Database schema is created**: The backend application should have run migrations automatically. If not, manually apply schema via Spring Boot's Flyway or Liquibase.

3. **Required tables exist**:
   - `users`
   - `player_profiles`
   - `seasons`
   - `games`
   - `game_performances`

## What Gets Created

Running the seed script creates:

1. **User Account** - Authentication entry
2. **Player Profile** - Full name, birthdate, location
3. **2025-2026 Season** - New Jersey Devils Youth AAA with 10 games
4. **Sample Games** - Distributed from Oct 2025 through Dec 2025
5. **Performance Stats** - Goals and assists per game (realistic distribution)

## Verification

After seeding, verify the data:

```bash
# List all users
python scripts/connect_db.py

# Check seasons for Ethan
psql -h localhost -U postgres -d myhockeystats -c \
  "SELECT * FROM seasons WHERE player_profile_id IN 
   (SELECT id FROM player_profiles WHERE full_name = 'Ethan Yan')"

# Check games and stats
psql -h localhost -U postgres -d myhockeystats -c \
  "SELECT g.*, gp.goals, gp.assists FROM games g 
   JOIN seasons s ON s.id = g.season_id 
   JOIN player_profiles pp ON pp.id = s.player_profile_id 
   JOIN game_performances gp ON gp.game_id = g.id 
   WHERE pp.full_name = 'Ethan Yan' ORDER BY g.date"
```

## Testing the Full Flow

1. **Seed the data** using one of the methods above
2. **Start the backend**:
   ```bash
   docker compose up -d
   ```
3. **Start the frontend** (in `frontend/web/`):
   ```bash
   npm run dev
   ```
4. **Log in** at http://localhost with:
   - Email: ethan.yan@example.com
   - Password: TestPassword123
5. **Verify data** appears in Dashboard → Seasons and Game History

## Notes

- The SQL script uses `ON CONFLICT ... DO NOTHING` to safely handle re-runs
- Test passwords are **NOT hashed** in the SQL seed; only use for development!
- For production, integrate bcrypt password hashing before inserting users
- Sample game stats use a deterministic formula based on game number for reproducibility
