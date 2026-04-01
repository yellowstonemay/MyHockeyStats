# API Contracts: Integrate Player Data Sources

## Base URL
- `/api/integrations`

## Authentication and Authorization
- All endpoints require authenticated JWT except health checks.
- Player endpoints are scoped to authenticated player identity.
- Parent reads require verified linkage to target player account.
- Operator import endpoints require operator/admin role.

## 1. Trigger import run
### `POST /api/integrations/imports/run`
Start on-demand import for one or more sources.

Request:
```json
{
  "sources": ["THF", "AYHL", "GAMESHEET"],
  "triggerType": "OPERATOR_MANUAL"
}
```

Response `202 Accepted`:
```json
{
  "runId": "8d8607ab-6d11-4ea4-a4ba-4e17fb3dcffd",
  "status": "COMPLETED",
  "startedAt": "2026-03-12T16:45:00Z",
  "endedAt": "2026-03-12T16:45:10Z",
  "sourceSummaries": [
    {
      "source": "THF",
      "processed": 100,
      "accepted": 100,
      "rejected": 0,
      "duplicateSkipped": 0,
      "status": "COMPLETED",
      "errorSummary": null
    }
  ]
}
```

## 2. Get import run summary
### `GET /api/integrations/imports/{runId}`
Response `200 OK`:
```json
{
  "runId": "8d8607ab-6d11-4ea4-a4ba-4e17fb3dcffd",
  "status": "PARTIAL",
  "startedAt": "2026-03-12T16:45:00Z",
  "endedAt": "2026-03-12T16:46:12Z",
  "sourceSummaries": [
    {
      "source": "THF",
      "processed": 1200,
      "accepted": 1180,
      "rejected": 20,
      "duplicateSkipped": 0,
      "status": "COMPLETED"
    },
    {
      "source": "AYHL",
      "processed": 900,
      "accepted": 900,
      "rejected": 0,
      "duplicateSkipped": 35,
      "status": "COMPLETED"
    },
    {
      "source": "GAMESHEET",
      "processed": 300,
      "accepted": 0,
      "rejected": 300,
      "duplicateSkipped": 0,
      "status": "FAILED",
      "errorSummary": "Missing required birth month-year column"
    }
  ]
}
```

## 3. Login-time match status
### `GET /api/integrations/me/match-status`
Returns whether account is linked, needs selection, or no match.

Response `200 OK` (ambiguous):
```json
{
  "status": "AMBIGUOUS_SELECTION_REQUIRED",
  "birthMonthYear": "2012-07",
  "candidates": [
    {
      "candidateId": "5e31ec70-b2ab-4709-9d14-969fd3ba165b",
      "source": "THF",
      "displayName": "Ethan Smith",
      "seasonLabel": "2024-2025",
      "matchMethod": "FUZZY",
      "score": 0.92,
      "reasons": ["nickname_similarity", "punctuation_normalization"]
    }
  ]
}
```

Response `200 OK` (linked):
```json
{
  "status": "LINKED",
  "links": [
    {
      "source": "THF",
      "importedPlayerRecordId": "5e31ec70-b2ab-4709-9d14-969fd3ba165b",
      "linkState": "CONFIRMED",
      "lastVerifiedAt": "2026-03-11T12:00:00Z"
    }
  ]
}
```

## 4. Confirm candidate selection
### `POST /api/integrations/me/matches/confirm`
Creates or updates persistent link after explicit user confirmation.

Request:
```json
{
  "selectedCandidates": [
    {
      "source": "THF",
      "candidateId": "5e31ec70-b2ab-4709-9d14-969fd3ba165b"
    },
    {
      "source": "AYHL",
      "candidateId": "3b4fc759-5547-42f8-9560-ccdc1955d15f"
    }
  ]
}
```

Response `200 OK`:
```json
{
  "status": "CONFIRMED",
  "updatedLinks": 2
}
```

## 5. Get unified player history
### `GET /api/integrations/me/history?season=2024-2025`
Returns source-attributed team/game data, conflicts, and missing sources.

Response `200 OK`:
```json
{
  "season": "2024-2025",
  "sources": ["THF", "AYHL", "GAMESHEET"],
  "missingSources": ["GAMESHEET"],
  "teamHistory": [
    {
      "source": "THF",
      "club": "AAA Club",
      "team": "U14 A"
    }
  ],
  "games": [
    {
      "logicalGameKey": "2025-01-18|Rangers|home",
      "date": "2025-01-18",
      "opponent": "Rangers",
      "sourceValues": {
        "THF": { "goals": 1, "assists": 0, "finalScore": "3-2" },
        "AYHL": { "goals": 0, "assists": 1, "finalScore": "3-2" }
      },
      "hasConflict": true,
      "conflicts": [
        {
          "field": "goals",
          "values": { "THF": 1, "AYHL": 0 }
        }
      ]
    }
  ]
}
```

## 6. Parent read-only player history
### `GET /api/integrations/players/{playerUserId}/history?season=2024-2025&linkedPlayerUserId=2`
- Same payload shape as `/me/history`.
- Authorization: only linked parent or admin can access.

## 7. Scheduled daily run status
### `GET /api/integrations/imports/daily/latest`
Returns latest scheduled run summary per source.

Response `200 OK`:
```json
{
  "schedule": "daily",
  "latestBySource": [
    {
      "source": "THF",
      "runId": "f5df4a84-c267-4a50-9b16-8ba17ff813e8",
      "status": "COMPLETED",
      "endedAt": "2026-03-12T03:02:00Z"
    }
  ]
}
```

## Error Envelope
All non-2xx responses:
```json
{
  "code": "INTEGRATION_MATCH_CONFLICT",
  "message": "Candidate selection is required before linking.",
  "details": {
    "status": "AMBIGUOUS_SELECTION_REQUIRED"
  },
  "correlationId": "req-9f81af"
}
```

## Contract Test Expectations
- Import run endpoints verify per-source count fields and status values.
- Match-status endpoint verifies ambiguous list and linked responses.
- Confirm endpoint verifies persistence behavior and idempotent updates.
- History endpoint verifies source attribution, conflict flags, and missing-source indicators.
- Parent history endpoint verifies authorization boundaries.
