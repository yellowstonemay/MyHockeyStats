# contracts/api-contracts.md

## Base URL
- `/api`

## Auth

POST /api/auth/signup
- Request: { email, password, role }
- Response: 201 { id, email }

POST /api/auth/login
- Request: { email, password }
- Response: 200 { accessToken, tokenType: "Bearer", expiresIn }

## Player Profile

GET /api/players/{playerId}
- Response: 200 { id, fullName, birthdate, location, position, photoUrl }

POST /api/players
- Request: { fullName, birthdate, location, position }
- Response: 201 { id }

PUT /api/players/{playerId}
- Request: partial update allowed
- Response: 200 { id }

## Season & Game

GET /api/players/{playerId}/seasons?year=2024
- Response: 200 [ { seasonId, yearStart, yearEnd, teamName, clubName, totalGames } ]

GET /api/seasons/{seasonId}/games
- Response: 200 [ { gameId, date, opponent, finalScore, performance: { goals, assists } } ]

GET /api/games/{gameId}
- Response: 200 { game details, performances[] }

## Export

POST /api/players/{playerId}/seasons/{seasonId}/export
- Request: { format: "pdf" }
- Response: 202 { jobId }

GET /api/exports/{jobId}
- Response: 200 { status: "COMPLETED", downloadUrl }

## Errors
- Standard error envelope: { code, message, details? }

