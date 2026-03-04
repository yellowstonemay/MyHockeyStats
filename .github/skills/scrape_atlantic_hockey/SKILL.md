name: scrape_atlantic_hockey
description: this skills is to scrape roster data from atlantichockey.org.

## Instructions
you need below inputs:
- Season (2025 for the 2025-2026 Season)

steps to scrape the roster data from atlantichockey.org:
1. comvert Season to seasonid from below table:
2025-2026 season -> seasonid 33
2024-2025 season -> seasonid 32
2023-2024 season -> seasonid 31 
2022-2023 season -> seasonid 30
2021-2022 season -> seasonid 28
2020-2021 season -> seasonid 27 
2019-2020 season -> seasonid 26
2018-2019 season -> seasonid 25
2017-2018 season -> seasonid 24 
2016-2017 season -> seasonid 23
2015-2016 season -> seasonid 22
2014-2015 season -> seasonid 21
2013-2014 season -> seasonid 20
2012-2013 season -> seasonid 19
2011-2012 season -> seasonid 17
2010-2011 season -> seasonid 16 
2009-2010 season -> seasonid 15 
2008-2009 season -> seasonid 14
2007-2008 season -> seasonid 13
2006-2007 season -> seasonid 12
2005-2006 season -> seasonid 11
2004-2005 season -> seasonid 10

2. use https://atlantichockey.org/teamroster.php?seasonid=33 to get all the leagueid in the given season. the leagueid is in the return as a select option with name "league". below is an example of the select option, by pass the first option, and loop through the rest of the options to get league name and value (use as leagure_url for next step).
<select name="league" onchange="MM_jumpMenu('parent',this,0)" style="width: 90%;" class="w3-input w3-border">
		 <option value="?seasonid=33">- Leagues-</option><option value="?leagueid=279&amp;leaguetypeid=2&amp;seasonid=33">10U Major 15</option><option selected="" value="?leagueid=278&amp;leaguetypeid=2&amp;seasonid=33">10U Minor 16</option><option value="?leagueid=281&amp;leaguetypeid=2&amp;seasonid=33">12U Major 13</option><option value="?leagueid=280&amp;leaguetypeid=2&amp;seasonid=33">12U Minor 14</option><option value="?leagueid=282&amp;leaguetypeid=2&amp;seasonid=33">13 Pure 12</option><option value="?leagueid=283&amp;leaguetypeid=2&amp;seasonid=33">14U Major 11</option><option value="?leagueid=284&amp;leaguetypeid=2&amp;seasonid=33">15 Pure</option><option value="?leagueid=285&amp;leaguetypeid=2&amp;seasonid=33">16U 08/09</option><option value="?leagueid=286&amp;leaguetypeid=2&amp;seasonid=33">18U 07/08</option>         </select>

3. loop through each leagure_url (https://atlantichockey.org/teamroster.php+leagure_url) to get all the teams in the given season and leagueid. the teamid is in the return as a select option with name "team". below is an example of the select option, by pass the first option, and loop through the rest of the options to get team name and value (use as team_url for next step).
<select name="team" onchange="MM_jumpMenu('parent',this,0)" style="width: 90%;" class="w3-input w3-border">
		  <option value="?seasonid=33&amp;leagueid=281">- Teams-</option><option value="?seasonid=33&amp;leaguetypeid=2&amp;leagueid=281&amp;teamid=3301">Long Island Gulls </option>
<option value="?seasonid=33&amp;leaguetypeid=2&amp;leagueid=281&amp;teamid=3302">Long Island Royals </option>
<option value="?seasonid=33&amp;leaguetypeid=2&amp;leagueid=281&amp;teamid=3303">New Jersey Colonials </option>
<option value="?seasonid=33&amp;leaguetypeid=2&amp;leagueid=281&amp;teamid=3304">New Jersey Jr. Titans </option>
<option value="?seasonid=33&amp;leaguetypeid=2&amp;leagueid=281&amp;teamid=3305">North Jersey Avalanche </option>
          </select>

4. loop each team_url (https://atlantichockey.org/teamroster.php+team_url) to get the team roster data. if the roster data is not found, skip to the next teamid. if the roster data is found, store the player name, position, bd, number(#), and other relevant information in a structured format (e.g., CSV, JSON) for further processing and analysis.