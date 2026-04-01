-- Seed test data for MyHockeyStats
-- This SQL file creates test data for Ethan Yan with a complete 2025-2026 season record
-- Load this with: psql -U postgres -d myhockeystats -f scripts/seed_data.sql

-- Create test user (Ethan Yan)
INSERT INTO users (email, password, full_name, created_at) 
VALUES (
  'ethan.yan@example.com',
  '$2a$10$mShMM5jife8qFUwfWXRD8.3uDPwTn9z2oexqKtxfEaLAalIo/Euo6',  -- BCrypt hash for TestPassword123
  'Ethan Yan',
  NOW()
) ON CONFLICT (email) DO NOTHING;

-- Get the user ID dynamically 
WITH user_data AS (
  SELECT id FROM users WHERE email = 'ethan.yan@example.com'
)
INSERT INTO player_profiles (user_id, full_name, birthdate, location, created_at, updated_at)
SELECT 
  u.id,
  'Ethan Yan',
  '2011-01-15'::date,
  'New Jersey, USA',
  NOW(),
  NOW()
FROM user_data u
WHERE NOT EXISTS (
  SELECT 1 FROM player_profiles WHERE user_id = u.id
);

-- Create 2025-2026 season
WITH profile_data AS (
  SELECT pp.id FROM player_profiles pp
  INNER JOIN users u ON u.id = pp.user_id
  WHERE u.email = 'ethan.yan@example.com'
)
INSERT INTO seasons (player_profile_id, year_start, year_end, team_name, club_name, total_games, created_at)
SELECT 
  p.id,
  2025,
  2026,
  'New Jersey Devils Youth AAA',
  'AYHL',
  10,
  NOW()
FROM profile_data p
WHERE NOT EXISTS (
  SELECT 1 FROM seasons 
  WHERE player_profile_id = p.id 
  AND year_start = 2025 
  AND year_end = 2026
);

-- Create sample games and performance stats
WITH season_data AS (
  SELECT s.id, s.player_profile_id 
  FROM seasons s
  INNER JOIN player_profiles pp ON pp.id = s.player_profile_id
  INNER JOIN users u ON u.id = pp.user_id
  WHERE u.email = 'ethan.yan@example.com'
  AND s.year_start = 2025
  AND s.year_end = 2026
),
game_opponents AS (
  SELECT 'Boston Bruins' as opponent UNION ALL
  SELECT 'New York Rangers' UNION ALL
  SELECT 'Philadelphia Flyers' UNION ALL
  SELECT 'Washington Capitals' UNION ALL
  SELECT 'Toronto Maple Leafs' UNION ALL
  SELECT 'Montreal Canadiens' UNION ALL
  SELECT 'Detroit Red Wings' UNION ALL
  SELECT 'New Jersey Devils' UNION ALL
  SELECT 'Pittsburgh Penguins' UNION ALL
  SELECT 'Tampa Bay Lightning'
),
game_data AS (
  SELECT 
    s.id as season_id,
    s.player_profile_id,
    row_number() OVER (ORDER BY g.opponent) as game_num,
    (DATE '2025-10-01'::date + (row_number() OVER (ORDER BY g.opponent) - 1) * INTERVAL '6 days')::date as game_date,
    g.opponent,
    CASE 
      WHEN (row_number() OVER (ORDER BY g.opponent)) % 3 = 0 THEN '3-2'
      WHEN (row_number() OVER (ORDER BY g.opponent)) % 3 = 1 THEN '4-1'
      ELSE '2-2'
    END as final_score,
    (row_number() OVER (ORDER BY g.opponent) * 17) % 3 as goals,
    (row_number() OVER (ORDER BY g.opponent) * 13) % 2 as assists
  FROM season_data s, game_opponents g
)
INSERT INTO games (season_id, date, opponent, final_score)
SELECT season_id, game_date, opponent, final_score
FROM game_data
WHERE NOT EXISTS (
  SELECT 1 FROM games g2
  INNER JOIN seasons s2 ON s2.id = g2.season_id
  INNER JOIN player_profiles pp2 ON pp2.id = s2.player_profile_id
  INNER JOIN users u2 ON u2.id = pp2.user_id
  WHERE u2.email = 'ethan.yan@example.com'
  AND s2.year_start = 2025
  AND g2.opponent = game_data.opponent
  AND g2.date = game_data.game_date
);

-- Insert performance stats for each game
WITH season_data AS (
  SELECT s.id, s.player_profile_id
  FROM seasons s
  INNER JOIN player_profiles pp ON pp.id = s.player_profile_id
  INNER JOIN users u ON u.id = pp.user_id
  WHERE u.email = 'ethan.yan@example.com'
  AND s.year_start = 2025
  AND s.year_end = 2026
),
game_perf_data AS (
  SELECT 
    g.id as game_id,
    s.player_profile_id,
    row_number() OVER (ORDER BY g.date) as game_num,
    (row_number() OVER (ORDER BY g.date) * 17) % 3 as goals,
    (row_number() OVER (ORDER BY g.date) * 13) % 2 as assists
  FROM season_data s
  INNER JOIN games g ON g.season_id = s.id
)
INSERT INTO game_performances (game_id, player_profile_id, goals, assists, notes)
SELECT game_id, player_profile_id, goals, assists, 'Game ' || game_num || ' stats'
FROM game_perf_data
WHERE NOT EXISTS (
  SELECT 1 FROM game_performances gp 
  WHERE gp.game_id = game_perf_data.game_id
);

-- Create second test user (Paden Zhou) for integration history testing
INSERT INTO users (email, password, full_name, created_at)
VALUES (
  'paden.zhou@example.com',
  '$2a$10$mShMM5jife8qFUwfWXRD8.3uDPwTn9z2oexqKtxfEaLAalIo/Euo6',  -- BCrypt hash for TestPassword123
  'Paden Zhou',
  NOW()
) ON CONFLICT (email) DO NOTHING;

WITH user_data AS (
  SELECT id FROM users WHERE email = 'paden.zhou@example.com'
)
INSERT INTO player_profiles (user_id, full_name, birthdate, location, created_at, updated_at)
SELECT
  u.id,
  'Paden Zhou',
  '2011-05-01'::date,
  'New Jersey, USA',
  NOW(),
  NOW()
FROM user_data u
WHERE NOT EXISTS (
  SELECT 1 FROM player_profiles WHERE user_id = u.id
);


-- Confirm insertion
SELECT COUNT(*) as total_games FROM games
WHERE season_id IN (
  SELECT s.id FROM seasons s
  INNER JOIN player_profiles pp ON pp.id = s.player_profile_id
  INNER JOIN users u ON u.id = pp.user_id
  WHERE u.email = 'ethan.yan@example.com'
  AND s.year_start = 2025
  AND s.year_end = 2026
);

SELECT 'Test data seeded successfully! Login with ethan.yan@example.com or paden.zhou@example.com / TestPassword123' as message;