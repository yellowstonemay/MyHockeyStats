name: scrape_mhr
description: this skills is to define the steps to scrape data from myhockeyrankings

## Instructions
you need below inputs:
- player name (Ethan Yan)
- birthdate (2011-01-15)
- location (New Jersey, USA)
- team (New Jersey Devils Youth AAA)
- season (2025)
- league (AYHL)

flow below steps to scrape the player's profile information from MyHockeyRankings.com:
1. if season is pnot provided, use the current season. season is in format of YYYY, which represents the starting year of the season (e.g., 2025 for the 2025-2026 season). season usually start in September and end in April. if current month is between August and December, use current year as season. if current month is between January and July, use previous year as season.
2. if league is not provided, use the league that the player's team is in for the given season. you can find the league from skill "find_league_by_team"

