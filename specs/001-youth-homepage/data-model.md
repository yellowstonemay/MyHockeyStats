# data-model.md

## Entities

1. User
- id: UUID
- email: string (unique, required)
- password_hash: string
- role: enum [PLAYER, PARENT, ADMIN]
- created_at, updated_at

2. PlayerProfile
- id: UUID
- user_id: UUID (FK -> User)
- full_name: string
- birthdate: date
- location: string
- position: string (optional)
- photo_url: string (optional)
- created_at, updated_at

3. Season
- id: UUID
- player_profile_id: UUID (FK -> PlayerProfile)
- year_start: integer (e.g., 2024)
- year_end: integer (e.g., 2025)
- team_name: string
- club_name: string
- total_games: integer (denormalized, optional)

4. Game
- id: UUID
- season_id: UUID (FK -> Season)
- date: date
- opponent: string
- venue: string (optional)
- final_score: string (e.g., "3-2")

5. GamePerformance
- id: UUID
- game_id: UUID (FK -> Game)
- player_profile_id: UUID (FK -> PlayerProfile)
- goals: integer
- assists: integer
- points: computed (goals + assists)
- notes: text (optional)

## Relationships
- User 1:1 PlayerProfile (a player user owns a profile)
- PlayerProfile 1:N Season
- Season 1:N Game
- Game 1:N GamePerformance
- PlayerProfile 1:N GamePerformance (direct relation for queries)

## Validation Rules
- Email must be unique and valid format.
- Birthdate required for player profiles; used for league lookup.
- Season year ranges must be contiguous (year_end = year_start + 1).
- Game date must fall within season timeframe.

## State transitions
- PlayerProfile: DRAFT -> ACTIVE -> ARCHIVED
- Export job: QUEUED -> PROCESSING -> COMPLETED -> AVAILABLE (with link) or FAILED

