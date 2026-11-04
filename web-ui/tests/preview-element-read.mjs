import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';

const at = process.argv.indexOf('--output');
if (at < 0 || !process.argv[at + 1]) throw new Error('Required: --output <evidence directory>');
const output = path.resolve(process.argv[at + 1]);
const source = fileURLToPath(new URL('../../app/src/main/assets/project-preview/element-read.js', import.meta.url));
const script = await fs.readFile(source, 'utf8');
const record = { scope: 'Actual production read-only element extractor on a local browser DOM. Does not execute Android native touch interception, native owner admission or composer CAS.', source, sha256: createHash('sha256').update(script).digest('hex'), startedAt: new Date().toISOString(), checks: [], errors: [] };
let browser, context, page;
const check = name => record.checks.push({ name, passed: true });
async function read(selector, scale = 1) {
  const point = await page.locator(selector).evaluate(element => { const rect = element.getBoundingClientRect(); return { x: rect.left + Math.min(8, rect.width / 2), y: rect.top + Math.min(8, rect.height / 2), width: innerWidth, height: innerHeight }; });
  return page.evaluate(`${script}(${point.x * scale},${point.y * scale},${point.width * scale},${point.height * scale})`);
}
try {
  await fs.mkdir(output, { recursive: true });
  browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
  context = await browser.newContext({ viewport: { width: 360, height: 800 }, serviceWorkers: 'block' });
  await context.route('**/*', route => route.abort());
  page = await context.newPage(); page.setDefaultTimeout(8000); page.on('pageerror', error => record.errors.push(error.message));
  await page.setContent('<!doctype html><html><head><meta charset="utf-8"></head><body style="margin:12px"><div style="height:850px"></div><form id="form"><button id="save" type="submit" onclick="window.events.clicks++"><span id="nested" class="actual child">保存原资料</span></button></form><div id="unicode" style="display:block;width:300px;height:60px;overflow:hidden;margin-top:20px"></div><script>window.events={clicks:0,capture:0,submits:0};window.addEventListener("click",()=>window.events.capture++,true);document.getElementById("form").addEventListener("submit",event=>{window.events.submits++;event.preventDefault()});</script></body></html>');
  await page.locator('#nested').scrollIntoViewIfNeeded();
  const nested = await read('#nested');
  assert.equal(nested.selectorHint, 'span#nested.actual'); assert.equal(nested.text, '保存原资料');
  assert.equal(nested.htmlSnippet, '<span id="nested" class="actual child">保存原资料</span>');
  assert.ok(await page.evaluate(() => scrollY > 0));
  assert.deepEqual(Object.keys(nested).sort(), ['selectorHint', 'text', 'htmlSnippet', 'bounds', 'truncated'].sort());
  assert.ok(Object.values(nested.bounds).every(Number.isFinite));
  assert.deepEqual(await page.evaluate(() => window.events), { clicks: 0, capture: 0, submits: 0 });
  check('fixed-coordinate read finds the actual nested element after scrolling and invokes no page input handlers');

  const scaled = await read('#nested', 2);
  assert.deepEqual(scaled, nested);
  for (const args of ['-1,2,360,800', '1,2,0,800', 'NaN,2,360,800', '360,2,360,800']) assert.equal(await page.evaluate(`${script}(${args})`), null);
  check('native coordinate scaling is consistent and invalid or outside coordinates cannot select');

  await page.locator('#unicode').evaluate(element => { element.textContent = 'a'.repeat(1199) + '😀tail'; element.setAttribute('data-large', 'x'.repeat(7000)); });
  const unicode = await read('#unicode');
  assert.equal(unicode.text.length, 1199); assert.equal(unicode.truncated.text, true);
  assert.ok(unicode.htmlSnippet.length <= 4000); assert.equal(unicode.truncated.htmlSnippet, true);
  assert.equal(unicode.truncated.selectorHint, false);
  assert.equal([...unicode.text].some(char => char.length === 1 && /[\ud800-\udfff]/.test(char)), false);
  check('actual large DOM metadata is bounded and truncation never cuts an astral character');

  await page.locator('#unicode').evaluate(element => { element.textContent = 'x\ud800y\0z\udc00'; element.id = 'id-' + 'q'.repeat(300); });
  const malformed = await read('[data-large]');
  assert.equal(malformed.text, 'x\ufffdy\ufffdz\ufffd'); assert.equal(malformed.truncated.text, true);
  assert.equal(malformed.selectorHint.length, 240); assert.equal(malformed.truncated.selectorHint, true);
  assert.equal(malformed.text.includes('\0'), false);
  check('NUL and malformed UTF-16 are explicit bounded replacements; long hints are not source locators');

  assert.equal(await page.locator('#inspect-mode-overlay,#inspect-mode-style,#inspect-mode-menu').count(), 0);
  assert.equal(await page.evaluate(() => Object.prototype.hasOwnProperty.call(window, '__inspectModeListeners')), false);
  await page.locator('#nested').click();
  assert.deepEqual(await page.evaluate(() => window.events), { clicks: 1, capture: 1, submits: 1 });
  check('extractor installs no page mode, bridge or visual layer and ordinary interaction remains available');
  assert.deepEqual(record.errors, []); record.passed = true;
} catch (error) {
  record.passed = false; record.failure = String(error.stack || error); process.exitCode = 1;
  if (page) await page.screenshot({ path: path.join(output, 'failure.png') }).catch(() => {});
} finally {
  record.finishedAt = new Date().toISOString(); await fs.mkdir(output, { recursive: true });
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(record, null, 2) + '\n');
  await context?.close(); await browser?.close();
}
console.log(JSON.stringify({ passed: record.passed, checks: record.checks, output }, null, 2));
