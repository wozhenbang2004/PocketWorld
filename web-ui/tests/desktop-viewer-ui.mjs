/** Mounted production components. Default MOCK RTC/real MediaStream; DESKTOP_VIEWER_RTC=1 requires real browser loopback. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));
const realRtc = process.env.DESKTOP_VIEWER_RTC === '1';
const output = process.env.DESKTOP_VIEWER_EVIDENCE || path.resolve(root, `../build-artifacts/desktop-viewer-ui${realRtc ? '-rtc' : '-fixture'}`);
await fs.mkdir(output, { recursive: true });
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
await server.listen();
// This isolated fixture has no STUN/TURN and needs only localhost candidates.
// Chromium deliberately excludes loopback interfaces unless this test switch is present.
const browser = await chromium.launch({ headless: true, args: ['--allow-loopback-in-peer-connection'] });
const results = [];
async function check(name, work, viewport = { width: 430, height: 900 }) {
  const page = await browser.newPage({ viewport, deviceScaleFactor: 1, reducedMotion: 'reduce', hasTouch: true });
  page.setDefaultTimeout(10_000);
  const errors = []; page.on('pageerror', error => errors.push(String(error)));
  try {
    await page.goto(server.resolvedUrls.local[0] + 'tests/desktop-viewer-fixture.html' + (realRtc ? '?transport=rtc' : ''));
    await page.getByRole('button', { name: '查看设备与授权', exact: true }).click();
    await work(page);
    assert.deepEqual(errors, [], 'No uncaught UI/runtime errors');
    results.push({ name, passed: true }); process.stdout.write(`PASS ${name}\n`);
  } catch (error) { results.push({ name, passed: false, error: String(error.stack || error) }); process.stdout.write(`FAIL ${name}: ${error.message}\n`); }
  finally { await page.screenshot({ path: path.join(output, name + '.png'), fullPage: true }); await page.close(); }
}
async function watch(page) {
  await page.getByRole('button', { name: '查看桌面', exact: true }).click();
  await page.getByRole('button', { name: '我来操作', exact: true }).waitFor();
  await page.waitForFunction(() => { const video = document.querySelector('video'); return video?.videoWidth === 1280 && document.querySelector('.pw-desktop-viewer')?.getAttribute('data-phase') === 'live'; }, null, { timeout: 15_000 });
  await page.waitForFunction(() => !Array.from(document.querySelectorAll('button')).find(b => b.textContent === '我来操作')?.disabled);
}
async function take(page) { await page.getByRole('button', { name: '我来操作', exact: true }).click(); await page.getByRole('button', { name: '交还 AI', exact: true }).waitFor(); await page.getByText('你正在操作', { exact: true }).waitFor(); }
try {
  await check('phone-watch-control-and-ime', async page => {
    await watch(page);
    assert.equal((await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input').length)), 0);
    const video = page.locator('video'); const box = await video.boundingBox(); assert.ok(box && box.width > 200 && box.height > 100);
    await video.click();
    assert.equal((await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input').length)), 0, 'Watch mode sends no input');
    await take(page); await video.click();
    await page.waitForFunction(() => window.desktopFixture.calls.some(c => c.action === 'devices.desktop.input'));
    const input = await page.evaluate(() => window.desktopFixture.calls.find(c => c.action === 'devices.desktop.input').args);
    assert.equal(input.input.kind, 'click'); assert.ok(Math.abs(input.input.x - 640) <= 2 && Math.abs(input.input.y - 400) <= 2);
    assert.equal(input.frameRef.streamId, 'fixture-stream-fixture-view-1');
    await page.getByRole('button', { name: '打开远程键盘', exact: true }).click();
    await page.getByRole('textbox', { name: '发送到电脑的文字', exact: true }).fill('中文输入已到电脑 😀');
    await page.getByRole('textbox', { name: '发送到电脑的文字', exact: true }).dispatchEvent('compositionstart', { data: '中文' });
    await page.getByRole('textbox', { name: '发送到电脑的文字', exact: true }).dispatchEvent('keydown', { key: 'Enter', code: 'Enter', ctrlKey: true, isComposing: true });
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.args?.input?.kind === 'insert_text').length), 0, 'Composition candidates cannot become remote input');
    await page.getByRole('textbox', { name: '发送到电脑的文字', exact: true }).dispatchEvent('compositionend', { data: '中文' });
    await page.getByRole('button', { name: '发送文字到电脑', exact: true }).click();
    await page.waitForFunction(() => window.desktopFixture.calls.some(c => c.args?.input?.kind === 'insert_text'));
    assert.equal(await page.getByRole('textbox', { name: '发送到电脑的文字', exact: true }).inputValue(), '');
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.find(c => c.args?.input?.kind === 'insert_text').args.input.text), '中文输入已到电脑 😀');
    await page.getByText('你正在操作', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(output, 'phone-control-and-ime-live.png'), fullPage: true });
    await page.getByRole('button', { name: '交还 AI', exact: true }).click();
    await page.getByRole('button', { name: '我来操作', exact: true }).waitFor();
    await page.getByText('正在观看', { exact: true }).waitFor();
  });
  await check('phone-unknown-input-keeps-draft-and-stops-replay', async page => {
    await watch(page); await take(page);
    await page.getByRole('button', { name: '打开远程键盘', exact: true }).click();
    await page.getByRole('textbox', { name: '发送到电脑的文字', exact: true }).fill('保留未确认的输入');
    await page.evaluate(() => window.desktopFixture.setUnknown(true));
    await page.getByRole('button', { name: '发送文字到电脑', exact: true }).click();
    await page.getByText('输入结果尚未确认，请查看桌面后重新连接核对。', { exact: true }).waitFor();
    assert.equal(await page.getByRole('textbox', { name: '发送到电脑的文字', exact: true }).inputValue(), '保留未确认的输入');
    assert.equal(await page.getByRole('button', { name: '发送文字到电脑', exact: true }).isDisabled(), true);
    await page.locator('video').click();
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input').length), 1);
  });
  await check('landscape-letterbox-and-live-fullscreen', async page => {
    await watch(page); await take(page); await page.getByRole('button', { name: '全屏查看桌面', exact: true }).click();
    await page.getByRole('button', { name: '更多桌面操作', exact: true }).click();
    const video = page.locator('video'), box = await video.boundingBox(); assert.ok(box);
    assert.ok(box.width / box.height > 1.6, 'Landscape canvas leaves left/right letterbox margins');
    await video.click({ position: { x: 2, y: box.height / 2 } });
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input').length), 0);
    await video.click({ position: { x: box.width / 2, y: box.height / 2 } });
    await page.waitForFunction(() => window.desktopFixture.calls.some(c => c.action === 'devices.desktop.input'));
    const point = await page.evaluate(() => window.desktopFixture.calls.find(c => c.action === 'devices.desktop.input').args.input);
    assert.ok(Math.abs(point.x - 640) <= 2 && Math.abs(point.y - 400) <= 2);
    await page.getByText('你正在操作', { exact: true }).waitFor();
  }, { width: 900, height: 430 });
  await check('stalled-video-disables-control-until-new-frame', async page => {
    await watch(page); await take(page);
    await page.evaluate(() => window.desktopFixture.freeze(true));
    await page.getByText('等待新画面', { exact: true }).waitFor({ timeout: 5000 });
    await page.locator('video').click();
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input').length), 0);
    await page.evaluate(() => window.desktopFixture.freeze(false));
    await page.getByText('你正在操作', { exact: true }).waitFor();
  });
  await check('close-and-native-back-release-the-exact-viewer', async page => {
    await watch(page); await take(page);
    await page.evaluate(() => window.desktopFixture.back());
    await page.getByRole('button', { name: '查看桌面', exact: true }).waitFor();
    await page.waitForFunction(() => window.desktopFixture.active() === 0);
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.close').length), 1);
    await watch(page); await page.getByRole('button', { name: '关闭桌面观看', exact: true }).click();
    await page.waitForFunction(() => window.desktopFixture.active() === 0);
    const closed = await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.close').map(c => c.args.sessionId));
    assert.deepEqual(closed, ['fixture-view-1', 'fixture-view-2']);
  });
  await check('late-open-after-unmount-is-closed', async page => {
    await page.evaluate(() => window.desktopFixture.delayOpen(true));
    await page.getByRole('button', { name: '查看桌面', exact: true }).click();
    await page.waitForFunction(() => window.desktopFixture.calls.some(c => c.action === 'devices.desktop.open'));
    await page.evaluate(() => window.desktopFixture.unmount());
    await page.evaluate(() => window.desktopFixture.settleOpen());
    await page.waitForFunction(() => window.desktopFixture.calls.some(c => c.action === 'devices.desktop.close'));
    assert.equal(await page.evaluate(() => window.desktopFixture.active()), 0);
    assert.equal(await page.locator('video').count(), 0);
  });
} finally { await browser.close(); await server.close(); }
await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({ identity: realRtc ? 'Mounted production React viewer; browser-loopback WebRTC with labelled synthetic canvas; not Windows or Android' : 'Mounted production React viewer; explicitly MOCKED RTC and native host, real canvas MediaStream and video decode; not transport, Windows or Android acceptance', mode: realRtc ? 'rtc' : 'mock-rtc-real-mediastream', results }, null, 2));
for (const result of results) process.stdout.write(`${result.passed ? 'PASS' : 'FAIL'} ${result.name}${result.error ? ': ' + result.error : ''}\n`);
if (results.some(result => !result.passed)) process.exitCode = 1;
