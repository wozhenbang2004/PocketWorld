/** Production React adapters with real synthetic-file playback. Does not
 * establish Android URL pinning, native authority or aesthetic acceptance. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = process.env.WORKSPACE_MEDIA_EVIDENCE || path.resolve(root, '../build-artifacts/workspace-media-ui');
const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-media-fixture-'));
await fs.mkdir(output, { recursive: true });
execFileSync('ffmpeg', ['-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=220:sample_rate=8000:duration=30', '-c:a', 'pcm_s16le', path.join(temporary, 'fixture-tone.wav')]);
execFileSync('ffmpeg', ['-loglevel', 'error', '-f', 'lavfi', '-i', 'color=c=gray:s=320x180:r=12:d=12', '-c:v', 'libvpx', '-b:v', '60k', path.join(temporary, 'fixture-video.webm')]);
const server = await createServer({ root, logLevel: 'error', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'workspace-real-media-fixtures', configureServer(server) { server.middlewares.use(async (request, response, next) => {
  if (!['/fixture-tone.wav', '/fixture-video.webm'].includes(request.url)) return next();
  const bytes = await fs.readFile(path.join(temporary, request.url.slice(1))), range = /^bytes=(\d+)-(\d*)$/.exec(request.headers.range || '');
  response.setHeader('Content-Type', request.url.endsWith('.wav') ? 'audio/wav' : 'video/webm'); response.setHeader('Accept-Ranges', 'bytes');
  const start = range ? Number(range[1]) : 0, end = range?.[2] ? Math.min(Number(range[2]), bytes.length - 1) : bytes.length - 1;
  response.statusCode = range ? 206 : 200; if (range) response.setHeader('Content-Range', `bytes ${start}-${end}/${bytes.length}`);
  response.setHeader('Content-Length', end - start + 1); response.end(bytes.subarray(start, end + 1));
}); } }] });
await server.listen();
const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}) });
const results = [];
try {
  for (const width of [1200, 390, 320]) {
    const page = await browser.newPage({ viewport: { width, height: 900 }, reducedMotion: width === 320 ? 'reduce' : 'no-preference' });
    const errors = []; page.on('pageerror', error => errors.push(String(error)));
    await page.goto(server.resolvedUrls.local[0] + 'tests/workspace-media-fixture.html');
    await page.evaluate(() => document.fonts.load('400 14px "Noto Sans SC"', '原图对照播放'));
    await page.waitForFunction(() => document.querySelector('audio')?.duration > 0);
    await page.getByRole('button', { name: '播放原件', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('audio')?.currentTime > .15 && !document.querySelector('audio').paused);
    await page.getByRole('button', { name: '静音', exact: true }).click();
    assert.equal(await page.locator('audio').evaluate(node => node.muted), true);
    await page.getByRole('button', { name: '前进 10 秒', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('audio').currentTime > 10);
    await page.getByRole('button', { name: '暂停原件', exact: true }).click();
    assert.equal(await page.locator('audio').evaluate(node => node.paused), true);
    const seek = page.getByRole('slider', { name: '播放进度' }); await seek.focus(); await seek.press('End');
    await page.waitForFunction(() => document.querySelector('audio').currentTime > 29);
    await seek.press('Home'); await page.getByRole('button', { name: '播放原件', exact: true }).click();
    await page.evaluate(() => window.dispatchEvent(new Event('pagehide')));
    assert.equal(await page.locator('audio').evaluate(node => node.paused), true);
    const comparison = page.getByRole('slider', { name: '移动分隔线比较父原图和当前版本' });
    await comparison.focus(); await comparison.press('ArrowLeft'); assert.equal(await comparison.getAttribute('aria-valuenow'), '45');
    await comparison.press('Home'); assert.equal(await comparison.getAttribute('aria-valuenow'), '0');
    await comparison.press('End'); assert.equal(await comparison.getAttribute('aria-valuenow'), '100');
    const bounds = await comparison.boundingBox();
    await page.mouse.move(bounds.x + bounds.width * .3, bounds.y + bounds.height * .5); await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width * .65, bounds.y + bounds.height * .5, { steps: 6 }); await page.mouse.up();
    assert.ok(Math.abs(Number(await comparison.getAttribute('aria-valuenow')) - 65) <= 1, 'Real pointer capture moves the original divider');
    await page.waitForTimeout(250);
    await page.screenshot({ path: path.join(output, `workspace-compare-${width}.png`), fullPage: true });
    await page.getByRole('button', { name: '逐张查看', exact: true }).click();
    assert.equal(await page.getByRole('img', { name: /测试父原图/ }).count(), 1);
    const carousel = page.getByRole('region', { name: '测试来源', exact: true });
    await carousel.getByRole('button', { name: '测试来源：下一项' }).click();
    const card = carousel.getByRole('button', { name: /测试图片来源 3/ }); await card.focus(); await card.press('Enter');
    assert.equal(await page.evaluate(() => window.workspaceMediaFixture.selected), 'fixture-file-2');
    await page.getByRole('button', { name: '网格', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '打开文件：src/example.ts', exact: true }).getAttribute('aria-current'), 'true');
    await page.screenshot({ path: path.join(output, `workspace-layout-${width}.png`), fullPage: true });
    assert.deepEqual(await page.evaluate(() => [...document.querySelectorAll('body *')].filter(node => { const r = node.getBoundingClientRect(); return r.width > 0 && (r.right > innerWidth + 1 || r.left < -1) && getComputedStyle(node).position !== 'absolute' && !node.closest('.pw-media-carousel-viewport'); }).map(node => ({ tag: node.tagName, class: node.className, width: node.getBoundingClientRect().width }))), [], 'No whole-page horizontal overflow');
    await page.evaluate(() => window.workspaceMediaFixture.video());
    await page.waitForFunction(() => document.querySelector('video')?.videoWidth === 320);
    await page.getByRole('button', { name: '播放原件', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('video')?.currentTime > .15 && !document.querySelector('video').paused);
    await page.evaluate(() => window.workspaceMediaFixture.hide());
    assert.equal(await page.locator('video').count(), 0, 'Revoked owner unmounts its actual media node');
    assert.deepEqual(errors, []);
    if (width === 320) { await page.evaluate(() => document.documentElement.classList.add('dark')); await page.screenshot({ path: path.join(output, 'workspace-dark-320.png'), fullPage: true }); }
    await page.screenshot({ path: path.join(output, `workspace-${width}.png`), fullPage: true });
    results.push({ width, reducedMotion: width === 320, passed: true });
    await page.close();
  }
  console.log(JSON.stringify({ boundary: 'Real Chromium WAV/WebM/SVG fixtures, no native host or user assets', results }));
} finally {
  await browser.close(); await server.close(); await fs.rm(temporary, { recursive: true, force: true });
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({ boundary: 'Real browser media; Android source pinning and aesthetic approval not verified', results }, null, 2));
}
