const puppeteer = require('puppeteer');
const { createObjectCsvWriter } = require('csv-writer');

async function scrapeTeams(league) {
    const isTHF = league.toLowerCase() === 'thf';
    
    // Configuration object for dynamic settings
    const config = {
        thf: {
            url: 'https://www.tier1hockeyfederation.com/team-pages/',
            output: '2025-thf-teams.csv',
            apiKeyword: 'get_teams'
        },
        ahf: {
            url: 'https://atlantichockeyfederation.com/team-page/',
            output: '2025-ahf-teams.csv',
            apiKeyword: 'get_teams' // Verify if AHF uses the same API endpoint
        }
    };

    const settings = config[league.toLowerCase()];
    if (!settings) {
        console.error("Invalid league! Please use 'thf' or 'ahf'.");
        return;
    }

    const browser = await puppeteer.launch({ headless: true });
    const page = await browser.newPage();

    const csvWriter = createObjectCsvWriter({
        path: settings.output,
        header: [
            { id: 'team_id', title: 'team_id' },
            { id: 'team_name', title: 'team_name' }
        ]
    });

    console.log(`Navigating to ${league.toUpperCase()} teams page...`);

    const apiResponsePromise = page.waitForResponse(response => 
        response.url().includes(settings.apiKeyword) && response.status() === 200
    );

    await page.goto(settings.url, { waitUntil: 'networkidle2', timeout: 60000 });

    try {
        const response = await apiResponsePromise;
        const data = await response.json();

        // Data mapping (Handle if AHF returns a different structure)
        const teamRecords = (data.teams || data).map(t => ({
            team_id: t.team_id,
            team_name: t.team_name
        }));

        await csvWriter.writeRecords(teamRecords);
        console.log(`Successfully saved ${teamRecords.length} teams to ${settings.output}.`);
    } catch (err) {
        console.error('Error fetching data:', err);
    } finally {
        await browser.close();
    }
}

// Get league from command line argument (e.g., node scrape_teams.js thf)
const league = process.argv[2];
if (!league) {
    console.log("Usage: node scrape_teams.js [thf|ahf]");
} else {
    scrapeTeams(league);
}