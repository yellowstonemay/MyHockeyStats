const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');
const { parse } = require('csv-parse/sync');
const { createObjectCsvWriter } = require('csv-writer');

async function scrapeRosters(league) {
    const isTHF = league.toLowerCase() === 'thf';
    
    // Configuration for dynamic file paths and URLs
    const config = {
        thf: {
            inputCsv: 'data/2025-thf-teams.csv',
            outputCsv: '2025-thf-rosters.csv',
            baseUrl: 'https://www.tier1hockeyfederation.com/team-pages/?team='
        },
        ahf: {
            inputCsv: 'data/2025-ahf-teams.csv',
            outputCsv: '2025-ahf-rosters.csv',
            baseUrl: 'https://atlantichockeyfederation.com/team-page/?team=' // Ensure this URL structure is correct for AHF
        }
    };

    const settings = config[league.toLowerCase()];
    if (!settings) {
        console.error("Invalid league! Please use 'thf' or 'ahf'.");
        return;
    }

    // 1. Read teams
    const csvContent = fs.readFileSync(path.join(__dirname, settings.inputCsv), 'utf8');
    const teams = parse(csvContent, { columns: true, skip_empty_lines: true });

    const browser = await puppeteer.launch({ headless: true });
    
    let queue = teams.map(t => ({
        url: `${settings.baseUrl}${t.team_id}&tab=3`,
        team_id: t.team_id,
        team_name: t.team_name
    }));

    let failedQueue = [];
    let allRecords = [];

    async function processTeam(team, isRetry = false) {
        const page = await browser.newPage();
        console.log(`Scraping: ${team.team_name} ${isRetry ? '(Retry)' : ''}`);

        try {
            // 1. Set a longer timeout (e.g., 60 seconds) for the API response
            const rosterPromise = page.waitForResponse(response => 
                response.url().includes('get_roster') && response.status() === 200,
                { timeout: 60000 }
            );

            // 2. Add an optional small delay before navigation to stagger requests
            // This prevents "burst" traffic that sometimes causes 403s or timeouts
            await new Promise(r => setTimeout(r, 2000)); 

            // 3. Navigate with a longer overall page timeout
            await page.goto(team.url, { waitUntil: 'networkidle0', timeout: 60000 });

            const response = await rosterPromise;
            const data = await response.json();

            // 4. Extract data and log the specific player count
            const players = data.players || [];
            players.forEach(p => {
                allRecords.push({
                    team_id: team.team_id,
                    team_name: team.team_name,
                    name: p.player_name || 'N/A',
                    jersey: p.jersey || '0',
                    position: p.position || 'N/A',
                    gp: p.games_played || 0,
                    goals: p.goals || 0,
                    assists: p.assists || 0,
                    points: p.points || 0,
                    scraped_at: new Date().toISOString() // Helps with time-series analysis
                });
            });
            console.log(`Captured ${players.length} players for ${team.team_name}`);
        } catch (err) {
            console.error(`Failed to process ${team.team_name}: ${err.message}`);
            if (!isRetry) failedQueue.push(team);
            else fs.appendFileSync('failed_rosters.txt', `${team.team_id},${team.team_name}\n`);
        } finally {
            await page.close();
        }
    }

    for (const team of queue) await processTeam(team);
    for (const team of failedQueue) await processTeam(team, true);

    const csvWriter = createObjectCsvWriter({
        path: settings.outputCsv,
        header: [
            { id: 'team_id', title: 'TEAM_ID' },
            { id: 'team_name', title: 'TEAM_NAME' },
            { id: 'name', title: 'NAME' },
            { id: 'jersey', title: 'JERSEY' },
            { id: 'position', title: 'POSITION' },
            { id: 'gp', title: 'GP' },
            { id: 'goals', title: 'G' },
            { id: 'assists', title: 'A' },
            { id: 'points', title: 'PTS' }
        ]
    });

    await csvWriter.writeRecords(allRecords);
    console.log(`Final roster CSV saved to ${settings.outputCsv}`);
    await browser.close();
}

const league = process.argv[2];
if (!league) console.log("Usage: node scrape_rosters.js [thf|ahf]");
else scrapeRosters(league);