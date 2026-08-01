const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());
const fs = require('fs');
const path = require('path');
const { Pool } = require('pg');
const { parse } = require('csv-parse/sync');

// THF/AHF season year -> TimeToScore season ID (from the schedule page Season dropdown)
const SEASON_TO_ID = {
    '2026': 190,
    '2025': 131
};

const CSV_COLUMNS = [
    'TEAM_ID',
    'TEAM_NAME',
    'PLAYER_ID',
    'NAME',
    'JERSEY',
    'POSITION',
    'BIRTHDATE',
    'GP',
    'G',
    'A',
    'PTS',
    'PIMS',
    'PPG',
    'SOG',
    'SCRAPED_AT'
];

const pool = new Pool({
    host: process.env.DB_HOST || 'localhost',
    port: parseInt(process.env.DB_PORT || '5432', 10),
    database: process.env.DB_NAME || 'myhockeystats',
    user: process.env.DB_USER || 'postgres',
    password: process.env.DB_PASSWORD || 'postgres',
    connectionTimeoutMillis: 30000,
    idleTimeoutMillis: 60000,
    max: 5,
    allowExitOnIdle: true
});

// Retry a DB operation a few times to ride out transient connection drops
async function withRetry(fn, attempts = 4, delayMs = 3000) {
    let lastErr;
    for (let i = 0; i < attempts; i++) {
        try {
            return await fn();
        } catch (err) {
            lastErr = err;
            console.error(`DB op failed (attempt ${i + 1}/${attempts}): ${err.message}. Retrying in ${delayMs}ms...`);
            await new Promise((r) => setTimeout(r, delayMs));
            delayMs *= 2;
        }
    }
    throw lastErr;
}

function toInt(value, fallback = 0) {
    const n = Number.parseInt(String(value ?? '').trim(), 10);
    return Number.isFinite(n) ? n : fallback;
}

function toNumericString(value, fallback = '0.0') {
    const n = Number.parseFloat(String(value ?? '').trim());
    return Number.isFinite(n) ? n.toFixed(1) : fallback;
}

async function ensureTables(settings) {
    const client = await pool.connect();
    try {
        await client.query(
            `CREATE TABLE IF NOT EXISTS ${settings.rosterTable} (
                season_year  INTEGER       NOT NULL,
                team_id      VARCHAR(128)  NOT NULL,
                team_name    VARCHAR(255),
                player_id    VARCHAR(128)  NOT NULL,
                player_name  VARCHAR(255),
                jersey       VARCHAR(16),
                position     VARCHAR(32),
                birthdate    VARCHAR(32),
                gp           INTEGER,
                goals        INTEGER,
                assists      INTEGER,
                points       INTEGER,
                pims         NUMERIC(10,1),
                ppg          NUMERIC(10,1),
                sog          INTEGER,
                scraped_at   TIMESTAMP     NOT NULL,
                created_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
                updated_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
                PRIMARY KEY (season_year, team_id, player_id)
            )`
        );

        await client.query(
            `CREATE INDEX IF NOT EXISTS ${settings.rosterTable}_season_team_idx
             ON ${settings.rosterTable} (season_year, team_id)`
        );
    } finally {
        client.release();
    }
}

async function upsertTeamRecordsToDb(settings, seasonYear, teamRecords) {
    if (!teamRecords || teamRecords.length === 0) {
        return;
    }

    return withRetry(async () => {
    const client = await pool.connect();
    try {
        await client.query('BEGIN');

        for (const row of teamRecords) {
            await client.query(
                `INSERT INTO ${settings.rosterTable} (
                    season_year, team_id, team_name, player_id, player_name, jersey, position,
                    birthdate, gp, goals, assists, points, pims, ppg, sog, scraped_at, created_at, updated_at
                ) VALUES (
                    $1, $2, $3, $4, $5, $6, $7,
                    $8, $9, $10, $11, $12, $13::NUMERIC(10,1), $14::NUMERIC(10,1), $15, $16, NOW(), NOW()
                )
                ON CONFLICT (season_year, team_id, player_id) DO UPDATE SET
                    team_name  = EXCLUDED.team_name,
                    player_name = EXCLUDED.player_name,
                    jersey = EXCLUDED.jersey,
                    position = EXCLUDED.position,
                    birthdate = EXCLUDED.birthdate,
                    gp = EXCLUDED.gp,
                    goals = EXCLUDED.goals,
                    assists = EXCLUDED.assists,
                    points = EXCLUDED.points,
                    pims = EXCLUDED.pims,
                    ppg = EXCLUDED.ppg,
                    sog = EXCLUDED.sog,
                    scraped_at = EXCLUDED.scraped_at,
                    updated_at = NOW()`,
                [
                    row.season_year,
                    row.team_id,
                    row.team_name,
                    row.player_id,
                    row.name,
                    row.jersey,
                    row.position,
                    row.birthdate,
                    row.gp,
                    row.goals,
                    row.assists,
                    row.points,
                    row.pims,
                    row.ppg,
                    row.sog,
                    row.scraped_at
                ]
            );
        }

        await client.query('COMMIT');
    } catch (err) {
        try { await client.query('ROLLBACK'); } catch (_) { /* ignore */ }
        throw err;
    } finally {
        client.release();
    }
    });
}

function escapeCsvValue(value) {
    const text = value == null ? '' : String(value);
    if (text.includes('"')) {
        return `"${text.replace(/"/g, '""')}"`;
    }
    if (text.includes(',') || text.includes('\n') || text.includes('\r')) {
        return `"${text}"`;
    }
    return text;
}

function initializeOutputFile(outputPath) {
    fs.writeFileSync(outputPath, `${CSV_COLUMNS.join(',')}\n`, 'utf8');
}

function appendRecordsToCsv(outputPath, records) {
    if (!records || records.length === 0) {
        return;
    }

    const lines = records.map((r) => ([
        r.team_id,
        r.team_name,
        r.player_id,
        r.name,
        r.jersey,
        r.position,
        r.birthdate,
        r.gp,
        r.goals,
        r.assists,
        r.points,
        r.pims,
        r.ppg,
        r.sog,
        r.scraped_at
    ]).map(escapeCsvValue).join(','));

    fs.appendFileSync(outputPath, `${lines.join('\n')}\n`, 'utf8');
}

function buildQueueFromTeams(teams, baseUrl, seasonId) {
    return teams.map((t) => ({
        url: `${baseUrl}${t.team_id}&season=${seasonId}&tab=roster`,
        team_id: t.team_id,
        team_name: t.team_name
    }));
}

function loadFailedTeamQueue(failedPath, baseUrl, seasonId) {
    if (!fs.existsSync(failedPath)) {
        return [];
    }

    const failedCsv = fs.readFileSync(failedPath, 'utf8');
    const failedRows = parse(failedCsv, {
        columns: true,
        skip_empty_lines: true
    });

    const uniqueByTeamId = new Map();
    failedRows.forEach((row) => {
        const teamId = String(row.team_id || '').trim();
        if (!teamId || uniqueByTeamId.has(teamId)) {
            return;
        }

        const teamName = String(row.team_name || '').trim() || `Team ${teamId}`;
        uniqueByTeamId.set(teamId, {
            url: `${baseUrl}${teamId}&season=${seasonId}&tab=roster`,
            team_id: teamId,
            team_name: teamName
        });
    });

    return [...uniqueByTeamId.values()];
}

function getLastTeamIdFromOutput(outputPath) {
    if (!fs.existsSync(outputPath)) {
        return null;
    }

    const content = fs.readFileSync(outputPath, 'utf8');
    const lines = content
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l.length > 0);

    if (lines.length <= 1) {
        return null;
    }

    const lastLine = lines[lines.length - 1];
    const [row] = parse(lastLine, {
        columns: false,
        skip_empty_lines: true
    });

    if (!row || row.length === 0) {
        return null;
    }
    return String(row[0] || '').trim() || null;
}

function loadRosterRecordsFromCsv(csvPath, seasonYear) {
    const csvContent = fs.readFileSync(csvPath, 'utf8');
    const rows = parse(csvContent, { columns: true, skip_empty_lines: true });

    return rows.map((r) => ({
        season_year: toInt(r.SEASON_YEAR || seasonYear),
        team_id: String(r.TEAM_ID || '').trim(),
        team_name: String(r.TEAM_NAME || '').trim(),
        player_id: String(r.PLAYER_ID || '').trim(),
        name: String(r.NAME || '').trim(),
        jersey: String(r.JERSEY || '0').trim() || '0',
        position: String(r.POSITION || 'N/A').trim() || 'N/A',
        birthdate: String(r.BIRTHDATE || 'N/A').trim() || 'N/A',
        gp: toInt(r.GP),
        goals: toInt(r.G),
        assists: toInt(r.A),
        points: toInt(r.PTS),
        pims: toNumericString(r.PIMS),
        ppg: toNumericString(r.PPG),
        sog: toInt(r.SOG),
        scraped_at: r.SCRAPED_AT || new Date().toISOString()
    })).filter((r) => r.team_id && r.player_id);
}

async function scrapeRosters(league, seasonYear, options = {}) {
    const { failedOnly = false, resume = false, csvSync = false, csvPath = null } = options;

    // Configuration for dynamic file paths and URLs
    const config = {
        thf: {
            inputCsv: `data/${seasonYear}-thf-teams.csv`,
            outputCsv: `${seasonYear}-thf-rosters.csv`,
            baseUrl: 'https://www.tier1hockeyfederation.com/team-pages/?team=',
            leagueCode: 'THF',
            rosterTable: 'thf_rosters',
            seasonId: SEASON_TO_ID[String(seasonYear)] || null
        },
        ahf: {
            inputCsv: `data/${seasonYear}-ahf-teams.csv`,
            outputCsv: `${seasonYear}-ahf-rosters.csv`,
            baseUrl: 'https://atlantichockeyfederation.com/team-page/?team=',
            leagueCode: 'AHF',
            rosterTable: 'ahf_rosters',
            seasonId: SEASON_TO_ID[String(seasonYear)] || null
        }
    };

    const settings = config[league.toLowerCase()];
    if (!settings) {
        console.error("Invalid league! Please use 'thf' or 'ahf'.");
        return;
    }

    const outputPath = path.join(__dirname, settings.outputCsv);
    const failedPath = path.join(__dirname, 'failed_rosters.txt');

    if (csvSync) {
        const sourceCsvPath = csvPath ? path.resolve(process.cwd(), csvPath) : outputPath;
        if (!fs.existsSync(sourceCsvPath)) {
            console.error(`CSV sync mode: file not found at ${sourceCsvPath}`);
            return;
        }

        await ensureTables(settings);
        const records = loadRosterRecordsFromCsv(sourceCsvPath, seasonYear);
        await upsertTeamRecordsToDb(settings, seasonYear, records);
        console.log(`CSV sync complete: ${records.length} roster rows from ${sourceCsvPath}`);
        return;
    }

    let queue = [];
    if (failedOnly) {
        queue = loadFailedTeamQueue(failedPath, settings.baseUrl, settings.seasonId);
        if (queue.length === 0) {
            console.log(`No failed teams found in ${failedPath}. Nothing to re-scan.`);
            return;
        }
        console.log(`Loaded ${queue.length} failed team(s) from ${failedPath}.`);
    } else {
        // 1. Read teams
        const csvContent = fs.readFileSync(path.join(__dirname, settings.inputCsv), 'utf8');
        const teams = parse(csvContent, { columns: true, skip_empty_lines: true });
        queue = buildQueueFromTeams(teams, settings.baseUrl, settings.seasonId);

        if (resume) {
            const lastTeamId = getLastTeamIdFromOutput(outputPath);
            if (!lastTeamId) {
                console.log('Resume mode enabled but no previous output rows found. Starting from first team.');
            } else {
                const lastIdx = queue.findIndex((t) => String(t.team_id) === lastTeamId);
                if (lastIdx === -1) {
                    console.log(`Resume mode: last team_id ${lastTeamId} not found in input team list. Starting from first team.`);
                } else {
                    queue = queue.slice(lastIdx + 1);
                    console.log(`Resume mode: last team_id ${lastTeamId}. Remaining teams to scrape: ${queue.length}.`);
                    if (queue.length === 0) {
                        console.log('No remaining teams to scrape.');
                        return;
                    }
                }
            }
        }
    }

    await ensureTables(settings);

    const browser = await puppeteer.launch({
        headless: true,
        args: ['--disable-blink-features=AutomationControlled', '--no-sandbox']
    });

    try {

    if ((!failedOnly && !resume) || !fs.existsSync(outputPath)) {
        initializeOutputFile(outputPath);
    }
    if (!failedOnly && !resume && fs.existsSync(failedPath)) {
        fs.unlinkSync(failedPath);
    }

    let timeoutFailedQueue = [];
    let hardFailedQueue = [];

    async function processTeam(team, options = {}) {
        const {
            isRetry = false,
            responseTimeoutMs = 60000,
            pageTimeoutMs = 60000
        } = options;

        const page = await browser.newPage();
        await page.setUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36');
        console.log(`Scraping: ${team.team_name} ${isRetry ? '(Retry)' : ''}`);

        try {
            // 1. Set a longer timeout (e.g., 60 seconds) for the API response
            const rosterPromise = page.waitForResponse(response => 
                response.url().includes('get_roster') && response.status() === 200,
                { timeout: responseTimeoutMs }
            );

            // 2. Add an optional small delay before navigation to stagger requests
            // This prevents "burst" traffic that sometimes causes 403s or timeouts
            await new Promise(r => setTimeout(r, 2000)); 

            // 3. Navigate with a longer overall page timeout
            await page.goto(team.url, { waitUntil: 'domcontentloaded', timeout: pageTimeoutMs });

            const response = await rosterPromise;
            const data = await response.json();

            // 4. Extract data and log the specific player count
            const players = data.players || [];
            const teamRecords = [];
            players.forEach(p => {
                const birthdate = p.birthdate || p.birth_date || p.birthDate || 'N/A';
                teamRecords.push({
                    season_year: toInt(seasonYear),
                    team_id: team.team_id,
                    team_name: team.team_name,
                    player_id: p.player_id || p.id || 'N/A',
                    name: p.player_name || 'N/A',
                    jersey: p.jersey || '0',
                    position: p.position || p.plays || 'N/A',
                    birthdate,
                    gp: toInt(p.games_played),
                    goals: toInt(p.goals),
                    assists: toInt(p.assists),
                    points: toInt(p.points),
                    pims: toNumericString(p.pims ?? p.pim),
                    ppg: toNumericString(p.ppg),
                    sog: toInt(p.sog),
                    scraped_at: new Date().toISOString() // Helps with time-series analysis
                });
            });

            appendRecordsToCsv(outputPath, teamRecords);
            await upsertTeamRecordsToDb(settings, seasonYear, teamRecords);
            console.log(`Captured ${players.length} players for ${team.team_name}`);
            return true;
        } catch (err) {
            console.error(`Failed to process ${team.team_name}: ${err.message}`);
            const isTimeout = /timed out|timeout/i.test(err.message);
            if (!isRetry && isTimeout) {
                timeoutFailedQueue.push(team);
            } else {
                hardFailedQueue.push({
                    ...team,
                    reason: err.message,
                    phase: isRetry ? 'retry' : 'initial'
                });
            }
            return false;
        } finally {
            await page.close();
        }
    }

    for (const team of queue) {
        await processTeam(team);
    }

    if (timeoutFailedQueue.length > 0) {
        console.log(`Retrying ${timeoutFailedQueue.length} timeout failure(s) with longer timeout...`);
    }

    for (const team of timeoutFailedQueue) {
        await processTeam(team, {
            isRetry: true,
            responseTimeoutMs: 120000,
            pageTimeoutMs: 120000
        });
    }

    if (hardFailedQueue.length > 0) {
        const failedLines = hardFailedQueue.map(
            (f) => `${f.team_id},${f.team_name},${f.phase},${(f.reason || '').replace(/[\r\n]/g, ' ')}`
        );
        fs.writeFileSync(failedPath, `team_id,team_name,phase,reason\n${failedLines.join('\n')}\n`, 'utf8');
        console.log(`Wrote ${hardFailedQueue.length} failed team(s) to ${failedPath}`);
    }

        console.log(`Final roster CSV saved to ${outputPath}`);
    } finally {
        await browser.close();
    }
}

const league = process.argv[2];
const seasonYear = process.argv[3];
const modes = process.argv.slice(4);
const failedOnly = modes.includes('--failed-only');
const resume = modes.includes('--resume');
const csvSync = modes.includes('--csv-sync');
const csvArg = modes.find((m) => m.startsWith('--csv='));
const csvPath = csvArg ? csvArg.slice('--csv='.length) : null;

if (!league || !seasonYear) {
    console.log("Usage: node scrape_rosters.js [thf|ahf] [seasonYear] [--failed-only] [--resume] [--csv-sync] [--csv=path/to/file.csv]");
} else if (failedOnly && resume) {
    console.error('Invalid options: use either --failed-only or --resume, not both.');
} else if (csvSync && (failedOnly || resume)) {
    console.error('Invalid options: --csv-sync cannot be combined with --failed-only or --resume.');
} else {
    scrapeRosters(league, seasonYear, { failedOnly, resume, csvSync, csvPath })
        .catch((err) => {
            console.error('Roster scrape failed:', err);
            process.exitCode = 1;
        })
        .finally(async () => {
            await pool.end();
        });
}