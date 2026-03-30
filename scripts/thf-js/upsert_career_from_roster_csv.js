const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { Pool } = require('pg');
const { parse } = require('csv-parse/sync');

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

function toNumericString(value, fallback = '0.0') {
    const n = Number.parseFloat(String(value ?? '').trim());
    return Number.isFinite(n) ? n.toFixed(1) : fallback;
}

function normalizeText(value, fallback = null) {
    const text = String(value ?? '').trim();
    return text.length > 0 ? text : fallback;
}

function buildSeasonLabel(seasonYear) {
    const startYear = toInt(seasonYear);
    return `${startYear}-${startYear + 1} Season`;
}

function getSettings(league) {
    const normalized = String(league || '').toLowerCase();
    const config = {
        thf: {
            leagueCode: 'THF',
            careerTable: 'thf_player_career',
            changeEventTable: 'thf_change_event',
            careerIndex: 'idx_thf_career_player_season',
            changeEventIndex: 'idx_thf_change_event_detection',
            careerUniqueIndex: 'uq_thf_player_career_player_season_team',
            careerLegacyUniqueConstraint: 'thf_player_career_source_player_id_season_label_key'
        },
        ahf: {
            leagueCode: 'AHF',
            careerTable: 'ahf_player_career',
            changeEventTable: 'ahf_change_event',
            careerIndex: 'idx_ahf_career_player_season',
            changeEventIndex: 'idx_ahf_change_event_detection',
            careerUniqueIndex: 'uq_ahf_player_career_player_season_team',
            careerLegacyUniqueConstraint: 'ahf_player_career_source_player_id_season_label_key'
        }
    };

    return config[normalized] || null;
}

function getDefaultRosterCsv(league, seasonYear) {
    return path.join(__dirname, 'data', `${seasonYear}-${String(league).toLowerCase()}-rosters.csv`);
}

function readRosterRows(csvPath) {
    const csvText = fs.readFileSync(csvPath, 'utf8');
    const rows = parse(csvText, {
        columns: true,
        skip_empty_lines: true
    });

    return rows.map((r) => ({
        team_id: normalizeText(r.TEAM_ID),
        player_id: normalizeText(r.PLAYER_ID),
        player_name: normalizeText(r.NAME),
        team_name: normalizeText(r.TEAM_NAME),
        jersey_number: normalizeText(r.JERSEY),
        games_played: toInt(r.GP),
        goals: toInt(r.G),
        assists: toInt(r.A),
        points: toInt(r.PTS),
        pim: toNumericString(r.PIMS),
        scraped_at: normalizeText(r.SCRAPED_AT)
    })).filter((r) => r.player_id && r.team_id);
}

async function ensureTables(settings) {
    const client = await pool.connect();
    try {
        await client.query(
            `CREATE TABLE IF NOT EXISTS ${settings.careerTable} (
                id               UUID         PRIMARY KEY,
                source_player_id VARCHAR(128) NOT NULL,
                team_id          VARCHAR(128) NOT NULL,
                player_name      VARCHAR(255),
                season_label     VARCHAR(255) NOT NULL,
                league_name      VARCHAR(255),
                team_name        VARCHAR(1024),
                jersey_number    VARCHAR(16),
                games_played     INTEGER,
                goals            INTEGER,
                assists          INTEGER,
                points           INTEGER,
                penalties        INTEGER,
                pim              NUMERIC(10,1),
                last_scraped_at  TIMESTAMP,
                created_at       TIMESTAMP    NOT NULL,
                updated_at       TIMESTAMP    NOT NULL,
                UNIQUE (source_player_id, season_label, team_id)
            )`
        );

        await client.query(
            `ALTER TABLE ${settings.careerTable}
             ADD COLUMN IF NOT EXISTS team_id VARCHAR(128)`
        );

        await client.query(
            `UPDATE ${settings.careerTable}
             SET team_id = 'UNKNOWN'
             WHERE team_id IS NULL`
        );

        await client.query(
            `ALTER TABLE ${settings.careerTable}
             ALTER COLUMN team_id SET NOT NULL`
        );

        await client.query(
            `ALTER TABLE ${settings.careerTable}
             DROP CONSTRAINT IF EXISTS ${settings.careerLegacyUniqueConstraint}`
        );

        await client.query(
            `CREATE UNIQUE INDEX IF NOT EXISTS ${settings.careerUniqueIndex}
             ON ${settings.careerTable} (source_player_id, season_label, team_id)`
        );

        await client.query(
            `CREATE INDEX IF NOT EXISTS ${settings.careerIndex}
             ON ${settings.careerTable} (source_player_id, season_label)`
        );

        await client.query(
            `CREATE TABLE IF NOT EXISTS ${settings.changeEventTable} (
                id               UUID         PRIMARY KEY,
                source_player_id VARCHAR(128) NOT NULL,
                player_name      VARCHAR(255),
                season_label     VARCHAR(255) NOT NULL,
                field_name       VARCHAR(128) NOT NULL,
                old_value        TEXT,
                new_value        TEXT,
                detected_at      TIMESTAMP    NOT NULL,
                event_type       VARCHAR(32)  NOT NULL
            )`
        );

        await client.query(
            `CREATE INDEX IF NOT EXISTS ${settings.changeEventIndex}
             ON ${settings.changeEventTable} (source_player_id, season_label, detected_at)`
        );
    } finally {
        client.release();
    }
}

async function upsertFromRosterCsv(settings, seasonYear, rows) {
    const client = await pool.connect();
    const seasonLabel = buildSeasonLabel(seasonYear);

    let upserted = 0;
    let changeEvents = 0;

    try {
        await client.query('BEGIN');

        for (const row of rows) {
            const existingCareer = await client.query(
                `SELECT team_name, games_played, goals, assists, points, pim
                 FROM ${settings.careerTable}
                 WHERE source_player_id = $1 AND season_label = $2 AND team_id = $3`,
                [row.player_id, seasonLabel, row.team_id]
            );

            if (existingCareer.rowCount > 0) {
                const prev = existingCareer.rows[0];
                const fieldPairs = [
                    ['team_name', prev.team_name, row.team_name],
                    ['games_played', prev.games_played, row.games_played],
                    ['goals', prev.goals, row.goals],
                    ['assists', prev.assists, row.assists],
                    ['points', prev.points, row.points],
                    ['pim', prev.pim, row.pim]
                ];

                for (const [fieldName, oldValue, newValue] of fieldPairs) {
                    const oldText = oldValue == null ? '' : String(oldValue);
                    const newText = newValue == null ? '' : String(newValue);
                    if (oldText === newText) {
                        continue;
                    }

                    await client.query(
                        `INSERT INTO ${settings.changeEventTable} (
                            id, source_player_id, player_name, season_label,
                            field_name, old_value, new_value, detected_at, event_type
                        ) VALUES ($1,$2,$3,$4,$5,$6,$7,NOW(),$8)`,
                        [
                            crypto.randomUUID(),
                            row.player_id,
                            row.player_name,
                            seasonLabel,
                            fieldName,
                            oldText || null,
                            newText || null,
                            fieldName === 'team_name' ? 'TEAM_CHANGE' : 'STAT_CHANGE'
                        ]
                    );
                    changeEvents += 1;
                }
            }

            await client.query(
                `INSERT INTO ${settings.careerTable} (
                    id, source_player_id, team_id, player_name, season_label,
                    league_name, team_name, jersey_number,
                    games_played, goals, assists, points, penalties, pim,
                    last_scraped_at, created_at, updated_at
                ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14::NUMERIC(10,1),$15::TIMESTAMP,NOW(),NOW())
                ON CONFLICT (source_player_id, season_label, team_id) DO UPDATE SET
                    player_name = EXCLUDED.player_name,
                    league_name = EXCLUDED.league_name,
                    team_name = EXCLUDED.team_name,
                    jersey_number = EXCLUDED.jersey_number,
                    games_played = EXCLUDED.games_played,
                    goals = EXCLUDED.goals,
                    assists = EXCLUDED.assists,
                    points = EXCLUDED.points,
                    penalties = EXCLUDED.penalties,
                    pim = EXCLUDED.pim,
                    last_scraped_at = EXCLUDED.last_scraped_at,
                    updated_at = NOW()`,
                [
                    crypto.randomUUID(),
                    row.player_id,
                    row.team_id,
                    row.player_name,
                    seasonLabel,
                    settings.leagueCode,
                    row.team_name,
                    row.jersey_number,
                    row.games_played,
                    row.goals,
                    row.assists,
                    row.points,
                    null,
                    row.pim,
                    row.scraped_at
                ]
            );
            upserted += 1;
        }

        await client.query('COMMIT');
    } catch (err) {
        await client.query('ROLLBACK');
        throw err;
    } finally {
        client.release();
    }

    return { upserted, changeEvents };
}

async function main() {
    const league = process.argv[2];
    const seasonYear = process.argv[3];
    const rosterCsvArg = process.argv[4];

    if (!league || !seasonYear) {
        console.log('Usage: node upsert_career_from_roster_csv.js [thf|ahf] [seasonYear] [rosterCsvPath]');
        process.exitCode = 1;
        return;
    }

    const settings = getSettings(league);
    if (!settings) {
        console.error("Invalid league! Please use 'thf' or 'ahf'.");
        process.exitCode = 1;
        return;
    }

    const rosterCsvPath = rosterCsvArg
        ? path.resolve(process.cwd(), rosterCsvArg)
        : getDefaultRosterCsv(league, seasonYear);

    if (!fs.existsSync(rosterCsvPath)) {
        console.error(`Roster CSV not found: ${rosterCsvPath}`);
        process.exitCode = 1;
        return;
    }

    const rows = readRosterRows(rosterCsvPath);
    console.log(`Loaded ${rows.length} row(s) from ${rosterCsvPath}`);

    await ensureTables(settings);
    const result = await upsertFromRosterCsv(settings, seasonYear, rows);
    console.log(`Career upserts complete: ${result.upserted} row(s)`);
    console.log(`Change events inserted: ${result.changeEvents}`);
}

main()
    .catch((err) => {
        console.error('Career upsert failed:', err);
        process.exitCode = 1;
    })
    .finally(async () => {
        await pool.end();
    });
