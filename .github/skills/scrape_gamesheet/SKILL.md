name: scrape_gamesheet
description: this skills is to define the steps to scrape data from gamesheetstats (https://gamesheetstats.com/)
which seems has many history data of hockey players, but the data is not in a structured format, so we need to scrape the data and structure it in a way that we can use it for our analysis.

## Instructions
first, we can get all players and their stats by https://gamesheetstats.com/api/usePlayers/getPlayerStandings/6579, 
which return a large json file with all players and their stats, we can then filter the data to get the players we are interested in, and then we can use the player id to get more detailed stats for each player by https://gamesheetstats.com/api/usePlayers/getPlayerStats/{player_id}, which will return a json file with the player's stats for each season, we can then structure the data. or we can use https://gamesheetstats.com/seasons/6579/players/{player_id} to get the html page. 
6579 is the season id for 2023-2024 season, we can change it to get data for different seasons. but we need figure out how to get the season id for different seasons.

reference\6579_players_sample.json is an example json file we get from the first api, which contains some players and their stats for the 2023-2024 season. the data structure is column-major format (vertical arrays), which means IDS[0] is the player id for NAMES[0], and TEAMNAMES[0] is the team name for NAMES[0], and so on.

please follow the pattern to convert input json file to pivot it to a row-major format (list of player dictionaries) which means each row for a player.

with player_id and https://gamesheetstats.com/api/usePlayerCareer/6579/getStats/3777419, we can json like below, which we will get years for this player.
{
    "playerCareerStats": {
      ...
    },
    "playerYearStats": {
      ...
    },
    "years": [
        "2022-2023",
        "2023-2024",
        "2024-2025",
        "2025-2026"
    ]
}

then loop through the years to get the stats for each year, for example, for 2025-2026 season,
we can get detial by url https://gamesheetstats.com/api/usePlayerCareer/6579/getStats/4117310?year=2025-2026
which will return a json file with the player's stats (season title, team title, gp and others) for that season inside "playerYearStats", for example:
{
    "playerCareerStats": {
        ...
    },
    "playerYearStats": {
        "season": [
            {
                "data": {
                  ...,
                    "title": "Morris County Youth Hockey League - 2025-2026"
                },
            },
            {
                "data": {
                    ...,
                    "title": "Discovery Cup - Oct 11-13, 2025"
                },
            },
            {
                "data": "Total",
            }
        ],
        "team": [
            {
                "data": [
                    {
                        "title": "Morristown Jr. Colonials Squirt White",
                        "id": 378580,
                        "seasonId": 10690
                    }
                ],
            },
            {
                "data": [
                    {
                        "title": "New Jersey Colonials",
                        "id": 419154,
                        "seasonId": 11552
                    }
                ],
            },
            {
                "data": "",
            }
        ],
        "gp": [
            {
                "data": 11,
            },
            {
                "data": 6,
            },
            {
                "data": 17,
            }
        ],...
    },
    "years": [
      ...
    ]
}

we can create csv file with columns like player_id, season_title, team_title, gp and other stats for each player for each season. we can also add a column for the season year to make it easier to filter the data later on.
