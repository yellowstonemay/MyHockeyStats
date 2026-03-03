# Manual Season Entry Feature

## Overview
Instead of scraping league websites (which is unreliable), users can now manually enter their season and team information directly into the app.

## How It Works

### 1. Create a Season (for an existing player)
**Endpoint:** `POST /api/seasons`

**Request:**
```json
{
  "playerProfileId": 2,
  "yearStart": 2024,
  "yearEnd": 2025,
  "teamName": "New Jersey Devils Youth AAA",
  "clubName": "AYHL"
}
```

**Response:**
```json
{
  "id": 3,
  "yearStart": 2024,
  "yearEnd": 2025,
  "teamName": "New Jersey Devils Youth AAA",
  "clubName": "AYHL",
  "totalGames": 0
}
```

### 2. Test with cURL
```bash
curl -X POST http://localhost:8080/api/seasons \
  -H "Content-Type: application/json" \
  -d '{
    "playerProfileId": 2,
    "yearStart": 2024,
    "yearEnd": 2025,
    "teamName": "New Jersey Devils Youth AAA",
    "clubName": "AYHL"
  }'
```

### 3. Test with Python
```python
import requests

url = 'http://localhost:8080/api/seasons'
payload = {
    'playerProfileId': 2,
    'yearStart': 2024,
    'yearEnd': 2025,
    'teamName': 'New Jersey Devils Youth AAA',
    'clubName': 'AYHL'
}

response = requests.post(url, json=payload)
print(response.json())
```

## Frontend Integration
The frontend should have a form where users:
1. Select/enter their team name
2. Select/enter their league/club
3. Select the season year range
4. Submit to create the season

## Why Manual Entry Instead of Scraping?

### Advantages:
✅ **Reliable** - No dependency on website structure changes  
✅ **Accurate** - Users provide their own verified data  
✅ **Simple** - No complex web scraping required  
✅ **Fast** - Direct form submission is quicker  
✅ **User Data** - Users control what data is stored  

### When Web Scraping Makes Sense:
- If you partner with a league for API access
- If you have official data feeds (CSV, XML)
- For read-only public stats (not player registration)

## Next Steps
1. Add a "Create Season" form to the frontend dashboard
2. Connect it to the new POST /api/seasons endpoint
3. Test with the existing Ethan Yan player profile
