"""Seed test data into the database for MyHockeyStats.

This script directly inserts test player data, seasons, games, and performance stats
into the PostgreSQL database. Use this to populate the database with realistic test
scenarios without relying on web scrapers.

Usage:
    python scripts/seed_test_data.py --user-email ethan@example.com --user-password password123
"""

import argparse
import json
import sys
from datetime import datetime, timedelta
import psycopg2

def get_connection():
    """Connect to the Postgres instance used by Docker Compose."""
    params = {
        "host": "localhost",
        "port": 5432,
        "dbname": "myhockeystats",
        "user": "postgres",
        "password": "postgres",
    }
    return psycopg2.connect(**params)

def create_test_player(connection, email, password_hash, full_name, birthdate, location):
    """Create a test user and player profile."""
    with connection.cursor() as cur:
        # Check if user exists
        cur.execute("SELECT id FROM users WHERE email = %s", (email,))
        user = cur.fetchone()
        
        if user:
            user_id = user[0]
            print(f"✓ User already exists: {email} (id={user_id})")
        else:
            # Create user
            cur.execute(
                "INSERT INTO users (email, password, full_name, created_at) VALUES (%s, %s, %s, NOW()) RETURNING id",
                (email, password_hash, full_name)
            )
            user_id = cur.fetchone()[0]
            print(f"✓ Created user: {email} (id={user_id})")
        
        # Create or get player profile
        cur.execute("SELECT id FROM player_profiles WHERE user_id = %s", (user_id,))
        profile = cur.fetchone()
        
        if profile:
            profile_id = profile[0]
            print(f"✓ Player profile already exists (id={profile_id})")
        else:
            cur.execute(
                "INSERT INTO player_profiles (user_id, full_name, birthdate, location, created_at, updated_at) VALUES (%s, %s, %s, %s, NOW(), NOW()) RETURNING id",
                (user_id, full_name, birthdate, location)
            )
            profile_id = cur.fetchone()[0]
            print(f"✓ Created player profile: {full_name} (id={profile_id})")
        
        connection.commit()
        return user_id, profile_id


def create_season(connection, profile_id, year_start, year_end, team_name, club_name):
    """Create a season for the player."""
    with connection.cursor() as cur:
        # Check if season exists
        cur.execute(
            "SELECT id FROM seasons WHERE player_profile_id = %s AND year_start = %s AND year_end = %s",
            (profile_id, year_start, year_end)
        )
        season = cur.fetchone()
        
        if season:
            season_id = season[0]
            print(f"✓ Season already exists: {year_start}-{year_end} (id={season_id})")
        else:
            cur.execute(
                "INSERT INTO seasons (player_profile_id, year_start, year_end, team_name, club_name, total_games, created_at) VALUES (%s, %s, %s, %s, %s, %s, NOW()) RETURNING id",
                (profile_id, year_start, year_end, team_name, club_name, 0)
            )
            season_id = cur.fetchone()[0]
            print(f"✓ Created season: {team_name} ({year_start}-{year_end}) (id={season_id})")
        
        connection.commit()
        return season_id


def create_games_and_stats(connection, profile_id, season_id, num_games=10):
    """Create sample games and performance stats."""
    with connection.cursor() as cur:
        opponents = [
            "Boston Bruins", "New York Rangers", "Philadelphia Flyers",
            "Washington Capitals", "Toronto Maple Leafs", "Montreal Canadiens",
            "Detroit Red Wings", "New Jersey Devils", "Pittsburgh Penguins",
            "Tampa Bay Lightning"
        ]
        
        start_date = datetime(2025, 10, 1)
        games_created = 0
        
        for i in range(num_games):
            game_date = start_date + timedelta(days=i*6)  # Games roughly every 6 days
            opponent = opponents[i % len(opponents)]
            
            # Create game
            cur.execute(
                "INSERT INTO games (season_id, date, opponent, final_score, created_at) VALUES (%s, %s, %s, %s, NOW()) RETURNING id",
                (season_id, game_date.date(), opponent, f"{3 + (i % 3)}-{2 + (i % 2)}")
            )
            game_id = cur.fetchone()[0]
            
            # Create performance stat
            goals = (i * 17) % 3  # Simple formula: 0, 1, or 2 goals per game
            assists = (i * 13) % 2  # 0 or 1 assists per game
            
            cur.execute(
                "INSERT INTO game_performances (game_id, player_profile_id, goals, assists, notes, created_at) VALUES (%s, %s, %s, %s, %s, NOW())",
                (game_id, profile_id, goals, assists, f"Game {i+1} stats")
            )
            games_created += 1
        
        # Update season total games
        cur.execute(
            "UPDATE seasons SET total_games = %s WHERE id = %s",
            (games_created, season_id)
        )
        
        connection.commit()
        print(f"✓ Created {games_created} games with performance stats")


def main():
    parser = argparse.ArgumentParser(description="Seed test data into MyHockeyStats database")
    parser.add_argument('--user-email', default='ethan.yan@example.com', help='User email for test account')
    parser.add_argument('--user-password', default='TestPassword123', help='Password for test account (will be hashed)')
    parser.add_argument('--full-name', default='Ethan Yan', help='Player full name')
    parser.add_argument('--birthdate', default='2011-01-15', help='Player birthdate (YYYY-MM-DD)')
    parser.add_argument('--location', default='New Jersey, USA', help='Player location')
    parser.add_argument('--team', default='New Jersey Devils Youth AAA', help='Team name')
    parser.add_argument('--club', default='AYHL', help='League/club name')
    parser.add_argument('--num-games', type=int, default=10, help='Number of games to generate')
    
    args = parser.parse_args()
    
    try:
        connection = get_connection()
        print("✓ Connected to database\n")
        
        # For testing, we'll use a simple hash (in production, use bcrypt)
        # This is a plain password for demo purposes
        password_hash = args.user_password
        
        # Create user and player profile
        user_id, profile_id = create_test_player(
            connection, 
            args.user_email,
            password_hash,
            args.full_name,
            args.birthdate,
            args.location
        )
        print()
        
        # Create 2025-2026 season
        season_id = create_season(
            connection,
            profile_id,
            2025, 2026,
            args.team,
            args.club
        )
        print()
        
        # Create sample games
        create_games_and_stats(connection, profile_id, season_id, args.num_games)
        print()
        
        print("=" * 50)
        print("✅ Test data seeded successfully!")
        print("=" * 50)
        print(f"\nTest Account Details:")
        print(f"  Email: {args.user_email}")
        print(f"  Password: {args.user_password}")
        print(f"  Player: {args.full_name} (born {args.birthdate})")
        print(f"  Season: {args.team} (2025-2026)")
        print(f"  Games: {args.num_games}")
        print()
        print("You can now log in with these credentials to test the app!")
        
    except Exception as e:
        print(f"❌ Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
