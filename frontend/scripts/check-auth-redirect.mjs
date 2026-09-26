/**
 * Checks that signing in starts a correct Authorization Code + PKCE round trip.
 *
 * It stops at the credential prompt — no password is typed here — but
 * everything before that point is verifiable, and it is where the mistakes
 * live: a missing audience gives an opaque token the gateway will reject, a
 * missing code_challenge is not PKCE at all, and an unregistered callback is
 * refused by Auth0 with an error page rather than a login form.
 *
 *   node scripts/check-auth-redirect.mjs
 */
import puppeteer from 'puppeteer-core';

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:5173';
const CHROME =
  process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true });
const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 900 });

/*
 * The parameters have to be read from the /authorize request as it goes out.
 * Once the tenant accepts it, it redirects to its own login page and the query
 * string is gone — so inspecting where the browser ends up reports nothing and
 * looks like failure.
 */
let authorizeUrl = null;
page.on('request', (request) => {
  const url = request.url();
  if (!authorizeUrl && url.includes('/authorize?')) authorizeUrl = url;
});

await page.goto(`${ORIGIN}/login`, { waitUntil: 'networkidle0' });

const buttons = await page.$$('button');
let signIn = null;
for (const button of buttons) {
  const label = await page.evaluate((el) => el.textContent.trim(), button);
  if (label === 'Sign in') signIn = button;
}

if (!signIn) {
  console.error('FAIL: no "Sign in" button on /login');
  await browser.close();
  process.exit(1);
}

await Promise.all([
  page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => null),
  signIn.click(),
]);

const landed = new URL(page.url());

if (!authorizeUrl) {
  console.error('FAIL: nothing was sent to /authorize — sign in did not start');
  await browser.close();
  process.exit(1);
}

const params = new URL(authorizeUrl).searchParams;

/*
 * Auth0 reports a tenant misconfiguration by redirecting back to the callback
 * with an error, rather than by refusing the redirect. Landing here means the
 * request was well formed and the callback URL is registered — the application
 * did its part — so say what the tenant objected to instead of reporting every
 * parameter check as failed against our own origin.
 */
if (landed.origin === ORIGIN && params.get('error')) {
  console.log(`ok    request left the application and the tenant answered`);
  console.log(`ok    callback URL is registered  (the tenant redirected to it)`);
  console.log(`\nFAIL  the tenant refused the request:`);
  console.log(`      ${params.get('error')}: ${params.get('error_description')}`);
  console.log(`\nThis is Auth0 configuration, not application code.`);
  await browser.close();
  process.exit(1);
}

const checks = [
  ['leaves the application', landed.origin !== ORIGIN, landed.origin],
  ['reaches the tenant', landed.host.endsWith('auth0.com'), landed.host],
  ['asks for a code', params.get('response_type') === 'code', params.get('response_type')],
  ['sends a PKCE challenge', !!params.get('code_challenge'), params.get('code_challenge')?.slice(0, 12)],
  ['challenge is S256', params.get('code_challenge_method') === 'S256', params.get('code_challenge_method')],
  ['names this client', !!params.get('client_id'), params.get('client_id')],
  ['asks for the API audience', params.get('audience') === 'https://mockbank/api', params.get('audience')],
  ['returns to the callback', params.get('redirect_uri') === `${ORIGIN}/callback`, params.get('redirect_uri')],
  ['carries no client secret', !params.get('client_secret'), 'absent'],
];

const scope = params.get('scope') ?? '';
const wanted = ['fdx:accounts.read', 'fdx:transactions.read', 'fdx:bill.read', 'fdx:bill.write'];
for (const s of wanted) checks.push([`requests ${s}`, scope.includes(s), scope.includes(s)]);
checks.push(['does not request accounts.write', !scope.includes('fdx:accounts.write'), 'absent']);

// Auth0 refuses a bad callback or client with its own error page rather than a
// form, so what the page says is part of the result.
const body = await page.evaluate(() => document.body.innerText.slice(0, 400));
const refused = /callback url mismatch|unauthorized|is not allowed|error/i.test(body);
checks.push(['tenant accepted the request', !refused, refused ? body.split('\n')[0] : 'no error shown']);

let failed = 0;
for (const [label, ok, detail] of checks) {
  if (!ok) failed += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${label.padEnd(32)} ${detail ?? ''}`);
}

console.log(`\nstopped at: ${landed.origin}${landed.pathname}`);
if (refused) console.log(`tenant said: ${body.split('\n').slice(0, 3).join(' | ')}`);

await browser.close();
process.exit(failed > 0 ? 1 : 0);
