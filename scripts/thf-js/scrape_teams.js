const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');
const { createObjectCsvWriter } = require('csv-writer');
const { parse } = require('csv-parse/sync');
const { Pool } = require('pg');

// DB connection uses env vars with docker-compose defaults
const pool = new Pool({
    host:     process.env.DB_HOST     || 'localhost',
    port:     parseInt(process.env.DB_PORT || '5432'),
    database: process.env.DB_NAME     || 'myhockeystats',
    user:     process.env.DB_USER     || 'postgres',
    password: process.env.DB_PASSWORD || 'postgres',
});

async function upsertTeams(tableName, seasonYear, teamRecords) {
    const client = await pool.connect();
    try {
        let inserted = 0;
        let updated = 0;
        for (const t of teamRecords) {
            const result = await client.query(
                `INSERT INTO ${tableName} (season_year, team_id, team_name, created_at, updated_at)
                 VALUES ($1, $2, $3, NOW(), NOW())
                 ON CONFLICT (season_year, team_id)
                 DO UPDATE SET team_name  = EXCLUDED.team_name,
                               updated_at = NOW()
                 RETURNING (xmax = 0) AS inserted`,
                [seasonYear, t.team_id, t.team_name]
            );
            if (result.rows[0].inserted) {
                inserted++;
            } else {
                updated++;
            }
        }
        console.log(`DB: ${inserted} inserted, ${updated} updated in ${tableName}.`);
    } finally {
        client.release();
    }
}

function loadTeamsFromCsv(csvPath, seasonYear) {
    const csvContent = fs.readFileSync(csvPath, 'utf8');
    const rows = parse(csvContent, { columns: true, skip_empty_lines: true });
    return rows
        .map((r) => ({
            season_year: Number.parseInt(String(r.season_year ?? seasonYear), 10),
            team_id: String(r.team_id ?? '').trim(),
            team_name: String(r.team_name ?? '').trim()
        }))
        .filter((r) => r.team_id && r.team_name && Number.isFinite(r.season_year));
}

async function scrapeTeams(league, seasonYear, options = {}) {
    const { csvSync = false, csvPath = null } = options;

    // Configuration object for dynamic settings
    const config = {
        thf: {
            url: 'https://www.tier1hockeyfederation.com/team-pages/',
            output: `data/${seasonYear}-thf-teams.csv`,
            apiKeyword: 'get_teams',
            table: 'thf_teams'
        },
        ahf: {
            url: 'https://atlantichockeyfederation.com/team-page/',
            output: `data/${seasonYear}-ahf-teams.csv`,
            apiKeyword: 'get_teams', // Verify if AHF uses the same API endpoint
            table: 'ahf_teams'
        }
    };

    const settings = config[league.toLowerCase()];
    if (!settings) {
        console.error("Invalid league! Please use 'thf' or 'ahf'.");
        return;
    }

    const outputPath = path.join(__dirname, settings.output);

    if (csvSync) {
        const sourceCsvPath = csvPath ? path.resolve(process.cwd(), csvPath) : outputPath;
        if (!fs.existsSync(sourceCsvPath)) {
            console.error(`CSV sync mode: file not found at ${sourceCsvPath}`);
            return;
        }

        const teamRecords = loadTeamsFromCsv(sourceCsvPath, seasonYear);
        await upsertTeams(settings.table, parseInt(seasonYear, 10), teamRecords);
        console.log(`CSV sync complete: ${teamRecords.length} team rows from ${sourceCsvPath}`);
        return;
    }

    const browser = await puppeteer.launch({ headless: true });
    const page = await browser.newPage();

    const csvWriter = createObjectCsvWriter({
        path: outputPath,
        header: [
            { id: 'season_year', title: 'season_year' },
            { id: 'team_id',     title: 'team_id' },
            { id: 'team_name',   title: 'team_name' }
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
            season_year: parseInt(seasonYear),
            team_id:     t.team_id,
            team_name:   t.team_name
        }));

        // Write to CSV
        await csvWriter.writeRecords(teamRecords);
        console.log(`CSV: saved ${teamRecords.length} teams to ${outputPath}.`);

        // Upsert into database
        await upsertTeams(settings.table, parseInt(seasonYear), teamRecords);
    } catch (err) {
        console.error('Error fetching data:', err);
    } finally {
        await browser.close();
        await pool.end();
    }
}

// Get league from command line argument (e.g., node scrape_teams.js thf 2025)
const league = process.argv[2];
const seasonYear = process.argv[3];
const modes = process.argv.slice(4);
const csvSync = modes.includes('--csv-sync');
const csvArg = modes.find((m) => m.startsWith('--csv='));
const csvPath = csvArg ? csvArg.slice('--csv='.length) : null;
if (!league || !seasonYear) {
    console.log("Usage: node scrape_teams.js [thf|ahf] [seasonYear] [--csv-sync] [--csv=path/to/file.csv]");
} else {
    scrapeTeams(league, seasonYear, { csvSync, csvPath });
}