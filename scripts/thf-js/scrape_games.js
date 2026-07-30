const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const { spawnSync } = require('child_process');
const { parse } = require('csv-parse/sync');
const { Pool } = require('pg');

const CSV_COLUMNS = [
    'TEAM_ID',
    'TEAM_NAME',
    'GAME_ID',
    'GAME_DATE',
    'GAME_TIME',
    'LOCATION',
    'HOME_ID',
    'HOME_TEAM',
    'AWAY_ID',
    'AWAY_TEAM',
    'HOME_GOALS',
    'AWAY_GOALS',
    'SEASON_ID',
    'LEAGUE_ID',
    'LEAGUE_NAME',
    'GAME_STATUS',
    'RESULT_STRING',
    'SCORESHEET_LINK',
    'SCORESHEET_PDF_PATH',
    'SCRAPED_AT'
];

const pool = new Pool({
    host: process.env.DB_HOST || 'localhost',
    port: parseInt(process.env.DB_PORT || '5432', 10),
    database: process.env.DB_NAME || 'myhockeystats',
    user: process.env.DB_USER || 'postgres',
    password: process.env.DB_PASSWORD || 'postgres',
    allowExitOnIdle: true
});

function toInt(value, fallback = 0) {
    const n = Number.parseInt(String(value ?? '').trim(), 10);
    return Number.isFinite(n) ? n : fallback;
}

async function ensureGamesTable(settings) {
    const client = await pool.connect();
    try {
        await client.query(
            `CREATE TABLE IF NOT EXISTS ${settings.gamesTable} (
                season_year             INTEGER       NOT NULL,
                game_id                 VARCHAR(128)  NOT NULL,
                source_team_id          VARCHAR(128)  NOT NULL,
                source_team_name        VARCHAR(255),
                game_date               DATE,
                game_time               VARCHAR(32),
                location                VARCHAR(255),
                home_id                 VARCHAR(128),
                home_team               VARCHAR(255),
                away_id                 VARCHAR(128),
                away_team               VARCHAR(255),
                home_goals              INTEGER,
                away_goals              INTEGER,
                season_id               VARCHAR(64),
                league_id               VARCHAR(64),
                league_name             VARCHAR(64),
                game_status             VARCHAR(64),
                result_string           VARCHAR(64),
                scoresheet_link         TEXT,
                scoresheet_json         JSONB,
                scraped_at              TIMESTAMP     NOT NULL,
                created_at              TIMESTAMP     NOT NULL DEFAULT NOW(),
                updated_at              TIMESTAMP     NOT NULL DEFAULT NOW(),
                PRIMARY KEY (season_year, game_id)
            )`
        );

        await client.query(
            `CREATE TABLE IF NOT EXISTS ${settings.newEventsTable} (
                game_id      VARCHAR(128) PRIMARY KEY,
                detected_at  TIMESTAMP NOT NULL DEFAULT NOW()
            )`
        );

        await client.query(
            `CREATE INDEX IF NOT EXISTS ${settings.gamesTable}_season_game_idx
             ON ${settings.gamesTable} (season_year, game_id)`
        );
    } finally {
        client.release();
    }
}

async function getExistingGameIdSet(settings, seasonYear, gameIds) {
    const normalized = [...new Set((gameIds || []).map((x) => String(x || '').trim()).filter(Boolean))];
    if (normalized.length === 0) {
        return new Set();
    }

    const client = await pool.connect();
    try {
        const result = await client.query(
            `SELECT game_id
             FROM ${settings.gamesTable}
             WHERE season_year = $1
               AND game_id = ANY($2::VARCHAR[])`,
            [toInt(seasonYear), normalized]
        );

        return new Set(result.rows.map((r) => String(r.game_id || '').trim()).filter(Boolean));
    } finally {
        client.release();
    }
}

async function getExistingGameJsonState(settings, seasonYear, gameIds) {
    const normalized = [...new Set((gameIds || []).map((x) => String(x || '').trim()).filter(Boolean))];
    if (normalized.length === 0) {
        return new Map();
    }

    const client = await pool.connect();
    try {
        const result = await client.query(
            `SELECT game_id, (scoresheet_json IS NOT NULL) AS has_json
             FROM ${settings.gamesTable}
             WHERE season_year = $1
               AND game_id = ANY($2::VARCHAR[])`,
            [toInt(seasonYear), normalized]
        );

        const map = new Map();
        for (const row of result.rows) {
            map.set(String(row.game_id || '').trim(), Boolean(row.has_json));
        }
        return map;
    } finally {
        client.release();
    }
}

async function getProcessedTeamIdSet(settings, seasonYear) {
    const client = await pool.connect();
    try {
        const result = await client.query(
            `SELECT DISTINCT source_team_id
             FROM ${settings.gamesTable}
             WHERE season_year = $1
               AND source_team_id IS NOT NULL`,
            [toInt(seasonYear)]
        );

        return new Set(result.rows.map((r) => String(r.source_team_id || '').trim()).filter(Boolean));
    } finally {
        client.release();
    }
}

async function upsertGameRecordsToDb(settings, seasonYear, gameRecords) {
    if (!gameRecords || gameRecords.length === 0) {
        return;
    }

    const client = await pool.connect();
    try {
        await client.query('BEGIN');

        for (const row of gameRecords) {
            if (!row.game_id) {
                continue;
            }

            const upsertResult = await client.query(
                `INSERT INTO ${settings.gamesTable} (
                    season_year, game_id, source_team_id, source_team_name,
                    game_date, game_time, location, home_id, home_team, away_id, away_team,
                    home_goals, away_goals, season_id, league_id, league_name,
                    game_status, result_string, scoresheet_link,
                    scoresheet_json, scraped_at, created_at, updated_at
                ) VALUES (
                    $1, $2, $3, $4,
                    NULLIF($5, '')::DATE, $6, $7, $8, $9, $10, $11,
                    $12, $13, $14, $15, $16,
                    $17, $18, $19,
                    NULLIF($20, '')::JSONB, $21, NOW(), NOW()
                )
                ON CONFLICT (season_year, game_id) DO UPDATE SET
                    source_team_id = EXCLUDED.source_team_id,
                    source_team_name = EXCLUDED.source_team_name,
                    game_date = EXCLUDED.game_date,
                    game_time = EXCLUDED.game_time,
                    location = EXCLUDED.location,
                    home_id = EXCLUDED.home_id,
                    home_team = EXCLUDED.home_team,
                    away_id = EXCLUDED.away_id,
                    away_team = EXCLUDED.away_team,
                    home_goals = EXCLUDED.home_goals,
                    away_goals = EXCLUDED.away_goals,
                    season_id = EXCLUDED.season_id,
                    league_id = EXCLUDED.league_id,
                    league_name = EXCLUDED.league_name,
                    game_status = EXCLUDED.game_status,
                    result_string = EXCLUDED.result_string,
                    scoresheet_link = EXCLUDED.scoresheet_link,
                    scoresheet_json = EXCLUDED.scoresheet_json,
                    scraped_at = EXCLUDED.scraped_at,
                    updated_at = NOW()
                RETURNING (xmax = 0) AS inserted`,
                [
                    toInt(seasonYear),
                    row.game_id,
                    row.team_id,
                    row.team_name,
                    row.game_date,
                    row.game_time,
                    row.location,
                    row.home_id,
                    row.home_team,
                    row.away_id,
                    row.away_team,
                    toInt(row.home_goals),
                    toInt(row.away_goals),
                    row.season_id,
                    row.league_id,
                    row.league_name,
                    row.game_status,
                    row.result_string,
                    row.scoresheet_link,
                    row.scoresheet_json ? JSON.stringify(row.scoresheet_json) : '',
                    row.scraped_at
                ]
            );

            if (upsertResult.rows[0] && upsertResult.rows[0].inserted) {
                await client.query(
                    `INSERT INTO ${settings.newEventsTable} (game_id, detected_at)
                     VALUES ($1, NOW())
                     ON CONFLICT (game_id) DO NOTHING`,
                    [row.game_id]
                );
            }
        }

        await client.query('COMMIT');
    } catch (err) {
        await client.query('ROLLBACK');
        throw err;
    } finally {
        client.release();
    }
}

function loadGameRecordsFromCsv(csvPath, seasonYear) {
    const csvContent = fs.readFileSync(csvPath, 'utf8');
    const rows = parse(csvContent, { columns: true, skip_empty_lines: true });

    return rows.map((r) => ({
        team_id: String(r.TEAM_ID || '').trim(),
        team_name: String(r.TEAM_NAME || '').trim(),
        game_id: String(r.GAME_ID || '').trim(),
        game_date: String(r.GAME_DATE || '').trim(),
        game_time: String(r.GAME_TIME || '').trim(),
        location: String(r.LOCATION || '').trim(),
        home_id: String(r.HOME_ID || '').trim(),
        home_team: String(r.HOME_TEAM || '').trim(),
        away_id: String(r.AWAY_ID || '').trim(),
        away_team: String(r.AWAY_TEAM || '').trim(),
        home_goals: toInt(r.HOME_GOALS),
        away_goals: toInt(r.AWAY_GOALS),
        season_id: String(r.SEASON_ID || '').trim(),
        league_id: String(r.LEAGUE_ID || '').trim(),
        league_name: String(r.LEAGUE_NAME || '').trim(),
        game_status: String(r.GAME_STATUS || '').trim(),
        result_string: String(r.RESULT_STRING || '').trim(),
        scoresheet_link: String(r.SCORESHEET_LINK || '').trim(),
        scoresheet_pdf_path: String(r.SCORESHEET_PDF_PATH || '').trim(),
        scraped_at: String(r.SCRAPED_AT || '').trim() || new Date().toISOString(),
        season_year: toInt(r.SEASON_YEAR || seasonYear)
    })).filter((r) => r.game_id);
}

function parseScoresheetPdfToJson(pdfPath) {
    const parserScript = path.join(__dirname, 'parse_scoresheet.py');
    if (!fs.existsSync(parserScript) || !fs.existsSync(pdfPath)) {
        return null;
    }

    const pythonExe = process.env.PYTHON_PATH || process.env.PYTHON || 'python';
    const result = spawnSync(
        pythonExe,
        [parserScript, pdfPath, '--stdout'],
        {
            encoding: 'utf8',
            maxBuffer: 10 * 1024 * 1024
        }
    );

    if (result.status !== 0) {
        const stderr = (result.stderr || '').trim();
        throw new Error(stderr || `parse_scoresheet.py failed for ${pdfPath}`);
    }

    const stdout = String(result.stdout || '').trim();
    if (!stdout) {
        return null;
    }

    return JSON.parse(stdout);
}

function resolveScoresheetPdfPath(row, leagueKey, seasonYear) {
    const gameId = String(row.game_id || '').trim();
    const candidates = [];

    const csvPath = String(row.scoresheet_pdf_path || '').trim();
    if (csvPath) {
        candidates.push(path.resolve(__dirname, csvPath));

        const normalizedCsvPath = csvPath.replace(/\\/g, '/');
        const legacyPrefix = 'data/scoresheet/';
        const withLeaguePrefix = `data/scoresheet/${leagueKey}/`;
        if (normalizedCsvPath.startsWith(legacyPrefix) && !normalizedCsvPath.startsWith(withLeaguePrefix)) {
            const legacySuffix = normalizedCsvPath.slice(legacyPrefix.length);
            candidates.push(path.resolve(__dirname, withLeaguePrefix + legacySuffix));
        }
    }

    if (gameId) {
        const normalizedSeasonYear = String(row.season_year || seasonYear || '').trim();
        if (normalizedSeasonYear) {
            candidates.push(path.resolve(__dirname, 'data', 'scoresheet', leagueKey, normalizedSeasonYear, `${gameId}.pdf`));
        }
        candidates.push(path.resolve(__dirname, 'data', 'scoresheet', String(seasonYear), `${gameId}.pdf`));
    }

    for (const candidate of candidates) {
        if (candidate && fs.existsSync(candidate)) {
            return candidate;
        }
    }

    return null;
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
        r.game_id,
        r.game_date,
        r.game_time,
        r.location,
        r.home_id,
        r.home_team,
        r.away_id,
        r.away_team,
        r.home_goals,
        r.away_goals,
        r.season_id,
        r.league_id,
        r.league_name,
        r.game_status,
        r.result_string,
        r.scoresheet_link,
        r.scoresheet_pdf_path,
        r.scraped_at
    ]).map(escapeCsvValue).join(','));

    fs.appendFileSync(outputPath, `${lines.join('\n')}\n`, 'utf8');
}

function buildQueueFromTeams(teams, baseUrl) {
    return teams.map((t) => ({
        url: `${baseUrl}${t.team_id}&tab=1`,
        team_id: String(t.team_id || '').trim(),
        team_name: String(t.team_name || '').trim() || `Team ${t.team_id}`
    })).filter((t) => t.team_id);
}

function loadFailedTeamQueue(failedPath, baseUrl) {
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
            url: `${baseUrl}${teamId}&tab=1`,
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

function fetchBufferWithRedirects(url, redirectsLeft = 5) {
    return new Promise((resolve, reject) => {
        let parsed;
        try {
            parsed = new URL(url);
        } catch (err) {
            reject(new Error(`Invalid URL: ${url}`));
            return;
        }

        const client = parsed.protocol === 'https:' ? https : http;
        const req = client.get(
            {
                hostname: parsed.hostname,
                port: parsed.port || undefined,
                path: `${parsed.pathname}${parsed.search}`,
                protocol: parsed.protocol,
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
                }
            },
            (res) => {
                const status = res.statusCode || 0;
                const location = res.headers.location;

                if ([301, 302, 303, 307, 308].includes(status) && location) {
                    if (redirectsLeft <= 0) {
                        reject(new Error(`Too many redirects for ${url}`));
                        return;
                    }
                    const nextUrl = new URL(location, url).toString();
                    res.resume();
                    resolve(fetchBufferWithRedirects(nextUrl, redirectsLeft - 1));
                    return;
                }

                if (status < 200 || status >= 300) {
                    res.resume();
                    reject(new Error(`HTTP ${status} while downloading ${url}`));
                    return;
                }

                const chunks = [];
                res.on('data', (chunk) => chunks.push(chunk));
                res.on('end', () => resolve(Buffer.concat(chunks)));
            }
        );

        req.setTimeout(45000, () => {
            req.destroy(new Error(`Download timeout for ${url}`));
        });

        req.on('error', reject);
    });
}

async function downloadScoresheetPdf(link, outputPath) {
    if (!link) {
        return false;
    }

    if (fs.existsSync(outputPath) && fs.statSync(outputPath).size > 0) {
        return true;
    }

    const buffer = await fetchBufferWithRedirects(link);
    if (!buffer || buffer.length === 0) {
        throw new Error(`Empty PDF download: ${link}`);
    }

    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    const tmpPath = `${outputPath}.tmp`;
    fs.writeFileSync(tmpPath, buffer);
    fs.renameSync(tmpPath, outputPath);
    return true;
}

async function scrapeGames(league, seasonYear, options = {}) {
    const { failedOnly = false, resume = false, dbSync = true, csvSync = false, csvPath = null, writeCsv = true } = options;

    const config = {
        thf: {
            inputCsv: `data/${seasonYear}-thf-teams.csv`,
            outputCsv: `${seasonYear}-thf-games.csv`,
            baseUrl: 'https://www.tier1hockeyfederation.com/team-pages/?team=',
            scheduleEndpointKeyword: 'get_schedule',
            gamesTable: 'thf_games',
            newEventsTable: 'thf_games_new_event'
        },
        ahf: {
            inputCsv: `data/${seasonYear}-ahf-teams.csv`,
            outputCsv: `${seasonYear}-ahf-games.csv`,
            baseUrl: 'https://atlantichockeyfederation.com/team-page/?team=',
            scheduleEndpointKeyword: 'get_schedule',
            gamesTable: 'ahf_games',
            newEventsTable: 'ahf_games_new_event'
        }
    };

    const settings = config[String(league || '').toLowerCase()];
    if (!settings) {
        console.error("Invalid league! Please use 'thf' or 'ahf'.");
        return;
    }

    const outputPath = path.join(__dirname, settings.outputCsv);
    const failedPath = path.join(__dirname, 'failed_games.txt');
    const leagueKey = String(league || '').toLowerCase();
    const scoresheetDir = path.join(__dirname, 'data', 'scoresheet', leagueKey, String(seasonYear));

    if (dbSync) {
        await ensureGamesTable(settings);
    }

    if (csvSync) {
        const sourceCsvPath = csvPath ? path.resolve(process.cwd(), csvPath) : outputPath;
        if (!fs.existsSync(sourceCsvPath)) {
            console.error(`CSV sync mode: file not found at ${sourceCsvPath}`);
            return;
        }

        const records = loadGameRecordsFromCsv(sourceCsvPath, seasonYear);
        const existingJsonState = dbSync
            ? await getExistingGameJsonState(settings, seasonYear, records.map((r) => r.game_id))
            : new Map();

        let synced = 0;
        const newRecords = [];
        for (const row of records) {
            const hasJsonInDb = existingJsonState.get(row.game_id);
            if (dbSync && hasJsonInDb === true) {
                continue;
            }

            const absPdfPath = resolveScoresheetPdfPath(row, leagueKey, seasonYear);
            if (absPdfPath) {
                try {
                    row.scoresheet_json = parseScoresheetPdfToJson(absPdfPath);
                } catch (err) {
                    console.warn(`Scoresheet parse failed for game ${row.game_id}: ${err.message}`);
                }
            }

            newRecords.push(row);
            synced += 1;
        }

        if (dbSync) {
            await upsertGameRecordsToDb(settings, seasonYear, newRecords);
        }

        console.log(`CSV sync complete: ${synced} new game row(s) from ${sourceCsvPath}`);
        return;
    }

    let queue = [];
    if (failedOnly) {
        queue = loadFailedTeamQueue(failedPath, settings.baseUrl);
        if (queue.length === 0) {
            console.log(`No failed teams found in ${failedPath}. Nothing to re-scan.`);
            return;
        }
        console.log(`Loaded ${queue.length} failed team(s) from ${failedPath}.`);
    } else {
        const inputPath = path.join(__dirname, settings.inputCsv);
        if (!fs.existsSync(inputPath)) {
            console.error(`Input teams file not found: ${inputPath}`);
            return;
        }

        const csvContent = fs.readFileSync(inputPath, 'utf8');
        const teams = parse(csvContent, { columns: true, skip_empty_lines: true });
        queue = buildQueueFromTeams(teams, settings.baseUrl);

        if (resume) {
            if (dbSync) {
                const processedTeamIds = await getProcessedTeamIdSet(settings, seasonYear);
                if (processedTeamIds.size === 0) {
                    console.log('Resume mode: no processed teams found in database. Starting from first team.');
                } else {
                    queue = queue.filter((t) => !processedTeamIds.has(String(t.team_id)));
                    console.log(`Resume mode (DB): skipped ${processedTeamIds.size} processed team(s). Remaining teams: ${queue.length}.`);
                    if (queue.length === 0) {
                        console.log('No remaining teams to scrape.');
                        return;
                    }
                }
            } else {
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
    }

    if (writeCsv && ((!failedOnly && !resume) || !fs.existsSync(outputPath))) {
        initializeOutputFile(outputPath);
    }
    if (!failedOnly && !resume && fs.existsSync(failedPath)) {
        fs.unlinkSync(failedPath);
    }
    fs.mkdirSync(scoresheetDir, { recursive: true });

    const browser = await puppeteer.launch({ headless: true });

    try {
        let timeoutFailedQueue = [];
        let hardFailedQueue = [];

        async function processTeam(team, processOptions = {}) {
            const {
                isRetry = false,
                responseTimeoutMs = 60000,
                pageTimeoutMs = 60000
            } = processOptions;

            const page = await browser.newPage();
            console.log(`Scraping schedule: ${team.team_name} ${isRetry ? '(Retry)' : ''}`);

            try {
                const schedulePromise = page.waitForResponse(
                    (response) => response.url().includes(settings.scheduleEndpointKeyword) && response.status() === 200,
                    { timeout: responseTimeoutMs }
                );

                await new Promise((r) => setTimeout(r, 2000));
                await page.goto(team.url, { waitUntil: 'networkidle0', timeout: pageTimeoutMs });

                const response = await schedulePromise;
                const data = await response.json();
                const games = Array.isArray(data.games) ? data.games : [];
                const existingSet = dbSync
                    ? await getExistingGameIdSet(settings, seasonYear, games.map((g) => g.game_id))
                    : new Set();

                const teamRecords = [];
                for (const g of games) {
                    const gameId = String(g.game_id || '').trim();
                    if (!gameId) {
                        continue;
                    }

                    if (dbSync && existingSet.has(gameId)) {
                        continue;
                    }

                    const scoresheetLink = String(g.scoresheet_link || '').trim();
                    let scoresheetPdfPath = '';
                    let scoresheetJson = null;

                    if (gameId && scoresheetLink) {
                        const pdfOutput = path.join(scoresheetDir, `${gameId}.pdf`);
                        try {
                            await downloadScoresheetPdf(scoresheetLink, pdfOutput);
                            scoresheetPdfPath = path.relative(__dirname, pdfOutput).replace(/\\/g, '/');
                            scoresheetJson = parseScoresheetPdfToJson(pdfOutput);
                        } catch (downloadErr) {
                            console.warn(`Scoresheet download/parse failed for game ${gameId}: ${downloadErr.message}`);
                        }
                    }

                    teamRecords.push({
                        team_id: team.team_id,
                        team_name: team.team_name,
                        game_id: gameId,
                        game_date: String(g.date || '').trim(),
                        game_time: String(g.time || g.formatted_time || '').trim(),
                        location: String(g.location || '').trim(),
                        home_id: String(g.home_id || '').trim(),
                        home_team: String(g.home_team || '').trim(),
                        away_id: String(g.away_id || '').trim(),
                        away_team: String(g.away_team || '').trim(),
                        home_goals: toInt(g.home_goals),
                        away_goals: toInt(g.away_goals),
                        season_id: String(g.season_id || '').trim(),
                        league_id: String(g.league_id || '').trim(),
                        league_name: String(g.league_name || '').trim(),
                        game_status: String(g.game_status || '').trim(),
                        result_string: String(g.result_string || '').trim(),
                        scoresheet_link: scoresheetLink,
                        scoresheet_pdf_path: scoresheetPdfPath,
                        scoresheet_json: scoresheetJson,
                        scraped_at: new Date().toISOString()
                    });
                }

                if (writeCsv) {
                    appendRecordsToCsv(outputPath, teamRecords);
                }
                if (dbSync) {
                    await upsertGameRecordsToDb(settings, seasonYear, teamRecords);
                }
                console.log(`Captured ${teamRecords.length} new game(s) for ${team.team_name}`);
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

        if (writeCsv) {
            console.log(`Final games CSV saved to ${outputPath}`);
        } else {
            console.log('CSV output disabled via --no-csv.');
        }
        if (dbSync) {
            console.log(`DB sync enabled: upserted game rows into ${settings.gamesTable}`);
        } else {
            console.log('DB sync disabled: CSV/PDF output only. Omit --no-db-sync to enable database upsert.');
        }
        console.log(`Scoresheets saved under ${scoresheetDir}`);
    } finally {
        await browser.close();
    }
}

const league = process.argv[2];
const seasonYear = process.argv[3];
const modes = process.argv.slice(4);
const failedOnly = modes.includes('--failed-only');
const resume = modes.includes('--resume');
const dbSync = !modes.includes('--no-db-sync');
const csvSync = modes.includes('--csv-sync');
const noCsv = modes.includes('--no-csv');
const csvArg = modes.find((m) => m.startsWith('--csv='));
const csvPath = csvArg ? csvArg.slice('--csv='.length) : null;

if (!league || !seasonYear) {
    console.log("Usage: node scrape_games.js [thf|ahf] [seasonYear] [--failed-only] [--resume] [--csv-sync] [--csv=path/to/file.csv] [--no-db-sync] [--no-csv]");
} else if (failedOnly && resume) {
    console.error('Invalid options: use either --failed-only or --resume, not both.');
} else if (csvSync && (failedOnly || resume)) {
    console.error('Invalid options: --csv-sync cannot be combined with --failed-only or --resume.');
} else {
    scrapeGames(league, seasonYear, { failedOnly, resume, dbSync, csvSync, csvPath, writeCsv: !noCsv })
        .catch((err) => {
            console.error('Games scrape failed:', err);
            process.exitCode = 1;
        })
        .finally(async () => {
            await pool.end();
        });
}
