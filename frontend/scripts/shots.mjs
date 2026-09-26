/**
 * Screenshots each breakpoint and reports horizontal overflow.
 *
 * A screenshot alone shows that something is clipped but not what: the width
 * reported here is the document's, so any element wider than the viewport shows
 * up as a number rather than as a guess. When there is overflow, the widest
 * offending elements are named.
 *
 * Uses the Chrome already installed on the machine, so nothing is downloaded.
 *
 *   node scripts/shots.mjs                      every route below
 *   node scripts/shots.mjs /login              one route
 */
import { mkdirSync } from 'node:fs';
import puppeteer from 'puppeteer-core';

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:5173';
const OUT = 'shots';

/** Named so the files say which screen they are. */
const ROUTES = process.argv[2]
  ? [{ path: process.argv[2], name: process.argv[2].replace(/\W+/g, '') || 'root' }]
  : [
      { path: '/', name: 'landing' },
      { path: '/login', name: 'login' },
      { path: '/foundation', name: 'foundation' },
    ];

const CHROME =
  process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const WIDTHS = [
  { name: 'mobile', width: 375, height: 812 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 900 },
];

mkdirSync(OUT, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--hide-scrollbars'],
});

let failures = 0;

for (const route of ROUTES) {
  for (const { name, width, height } of WIDTHS) {
  const page = await browser.newPage();
  await page.setViewport({ width, height, deviceScaleFactor: 2 });
  await page.goto(ORIGIN + route.path, { waitUntil: 'networkidle0' });

  // Fonts must be in before anything is measured or photographed, or the shot
  // is of a fallback face at the wrong widths.
  await page.evaluate(() => document.fonts.ready);

  const report = await page.evaluate(() => {
    const doc = document.documentElement;
    const overflow = doc.scrollWidth - doc.clientWidth;

    const culprits = [];
    if (overflow > 0) {
      for (const el of document.querySelectorAll('body *')) {
        const box = el.getBoundingClientRect();
        if (box.right > doc.clientWidth + 1 && box.width > 0) {
          culprits.push({
            tag: el.tagName.toLowerCase(),
            cls: (el.className?.toString?.() ?? '').slice(0, 70),
            right: Math.round(box.right),
            width: Math.round(box.width),
          });
        }
      }
    }

    return {
      clientWidth: doc.clientWidth,
      scrollWidth: doc.scrollWidth,
      overflow,
      // The outermost offenders first: an inner element overflowing is usually
      // a symptom of its parent doing so.
      culprits: culprits.slice(0, 6),
    };
  });

  await page.screenshot({ path: `${OUT}/${route.name}-${name}.png`, fullPage: true });

  const verdict = report.overflow > 0 ? `OVERFLOW +${report.overflow}px` : 'fits';
  console.log(`${`${route.name} ${name}`.padEnd(22)} ${report.clientWidth}px  ${verdict}`);
  if (report.overflow > 0) {
    failures += 1;
    for (const c of report.culprits) {
      console.log(`   ${c.tag} w=${c.width} right=${c.right}  ${c.cls}`);
    }
  }

    await page.close();
  }
}

await browser.close();
process.exit(failures > 0 ? 1 : 0);
