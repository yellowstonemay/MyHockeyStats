// Debug: capture get_roster API JSON for a team page
// Usage: node debug_rosters.js <teamId> [seasonId] [league] [teamPagePath]
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

const teamId = process.argv[2] || '10267';
const seasonId = process.argv[3] || '190';
const league = process.argv[4] || 'thf';
const teamPagePath = process.argv[5] || null; // override path, e.g. '/team-page/?team='

const BASE = {
    thf: 'https://www.tier1hockeyfederation.com',
    ahf: 'https://atlantichockeyfederation.com'
}[league];

const pathPart = teamPagePath || {
    thf: '/team-pages/?team=',
    ahf: '/team-pages/?team='
}[league];

(async () => {
    const browser = await puppeteer.launch({
        headless: true,
        args: ['--disable-blink-features=AutomationControlled', '--no-sandbox']
    });
    const page = await browser.newPage();
    await page.setUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36');
    await page.setViewport({ width: 1280, height: 800 });
    await page.setDefaultNavigationTimeout(60000);

    const url = `${BASE}${pathPart}${teamId}&season=${seasonId}&tab=roster`;
    console.log('URL:', url);

    const rosterPromise = page.waitForResponse(
        (response) => response.url().includes('get_roster') && response.status() === 200,
        { timeout: 60000 }
    );

    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await new Promise(r => setTimeout(r, 3000));

    try {
        const response = await rosterPromise;
        const data = await response.json();
        console.log('=== get_roster API URL ===');
        console.log(response.url());
        const players = data.players || [];
        console.log(`players count: ${players.length}`);
        if (players.length > 0) {
            console.log('=== first player object ===');
            console.log(JSON.stringify(players[0], null, 2));
        } else {
            console.log('No players. Full JSON:', JSON.stringify(data, null, 2).slice(0, 4000));
        }
    } catch (err) {
        console.error('get_roster not captured:', err.message);
        console.log('Page title:', await page.title());
        const bodyText = await page.evaluate(() => document.body.innerText.slice(0, 1500));
        console.log('Body text:', bodyText);
    }
    await browser.close();
})();
