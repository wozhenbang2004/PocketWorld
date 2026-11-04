/**
 * Mounted production components. Default: MOCK RTC/native host/frame callbacks;
 * canvas pixels test layout only. DESKTOP_VIEWER_RTC=1 tests real browser-loopback
 * playback and watch-only gating because this sender has no RTP/capture mapping.
 * Neither mode proves real video-to-metadata alignment or Windows/Android input.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));
const realRtc = process.env.DESKTOP_VIEWER_RTC === '1';
const output = process.env.DESKTOP_VIEWER_EVIDENCE || path.resolve(root, `../build-artifacts/desktop-viewer-ui${realRtc ? '-rtc-watch-only' : '-mock-frame-callbacks'}`);
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
    assert.equal(await page.evaluate(() => window.desktopFixture.contract.mode), realRtc ? 'rtc-watch-only' : 'mock-rtc-and-frame-callbacks');
    assert.equal(await page.evaluate(() => window.desktopFixture.contract.realFrameAssociation), false);
    await work(page);
    assert.deepEqual(errors, [], 'No uncaught UI/runtime errors');
    results.push({ name, passed: true }); process.stdout.write(`PASS ${name}\n`);
  } catch (error) { results.push({ name, passed: false, error: String(error.stack || error) }); process.stdout.write(`FAIL ${name}: ${error.message}\n`); }
  finally { await page.screenshot({ path: path.join(output, name + '.png'), fullPage: true }); await page.close(); }
}
async function controlCheck(name, work, viewport) {
  if (realRtc) {
    const reason = 'Real canvas sender has no RTP-to-capture mapping; control is deliberately disabled. This journey remains covered by the explicitly mocked callback fixture.';
    results.push({ name, passed: null, skipped: true, reason }); process.stdout.write(`SKIP ${name}: ${reason}\n`); return;
  }
  await check(name, work, viewport);
}
async function watch(page, interactive = !realRtc) {
  await page.getByRole('button', { name: '查看桌面', exact: true }).click();
  await page.getByRole('button', { name: '我来操作', exact: true }).waitFor();
  await page.waitForFunction(() => { const video = document.querySelector('video'); return video?.videoWidth === 1280 && video.readyState >= 2 && video.currentTime > 0 && document.querySelector('.pw-desktop-viewer')?.getAttribute('data-phase') === 'live'; }, null, { timeout: 15_000 });
  if (interactive) await page.waitForFunction(() => !Array.from(document.querySelectorAll('button')).find(b => b.textContent === '我来操作')?.disabled);
}
async function take(page) { await page.getByRole('button', { name: '我来操作', exact: true }).click(); await page.getByRole('button', { name: '交还控制', exact: true }).waitFor(); await page.getByText('你正在操作', { exact: true }).waitFor(); }
try {
  await controlCheck('phone-watch-control-and-ime', async page => {
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
    assert.ok(Number.isInteger(input.frameRef.rtpTimestamp) && input.frameRef.rtpTimestamp >= 0 && input.frameRef.rtpTimestamp <= 0xffffffff);
    const trace = await page.evaluate(() => window.desktopFixture.frameTrace);
    for (const kind of ['metadata', 'decoded']) assert.ok(trace.some(frame => frame.kind === kind && frame.frameId === input.frameRef.frameId && frame.rtpTimestamp === input.frameRef.rtpTimestamp), 'Input uses the exact synthetic metadata/callback pair');
    assert.equal(input.controlLease.resource, 'windows-session:1:primary'); assert.ok(input.controlLease.expiresAt > Date.now());
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
    await page.getByRole('button', { name: '更多桌面操作', exact: true }).click();
    await page.getByRole('button', { name: '向下滚动电脑', exact: true }).click();
    await page.waitForFunction(() => window.desktopFixture.calls.some(c => c.args?.input?.kind === 'scroll'));
    assert.deepEqual(await page.evaluate(() => window.desktopFixture.calls.find(c => c.args?.input?.kind === 'scroll').args.input), { kind: 'scroll', direction: 'down', amount: 3, by: 'line' });
    assert.equal(await page.evaluate(() => window.desktopFixture.contract.scrollTarget), 'native-authorized-foreground-window');
    await page.getByText('你正在操作', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(output, 'phone-control-and-ime-live.png'), fullPage: true });
    await page.getByRole('button', { name: '交还控制', exact: true }).click();
    await page.getByRole('button', { name: '我来操作', exact: true }).waitFor();
    await page.getByText('正在观看', { exact: true }).waitFor();
  });
  await controlCheck('phone-unknown-input-keeps-draft-and-stops-replay', async page => {
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
    await page.getByRole('button', { name: '交还控制', exact: true }).click();
    await page.getByRole('button', { name: '我来操作', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '我来操作', exact: true }).isDisabled(), true, 'Release exits control without clearing needsReconcile');
  });
  await controlCheck('landscape-letterbox-and-live-fullscreen', async page => {
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
  await controlCheck('stalled-video-disables-control-until-new-frame', async page => {
    await watch(page); await take(page);
    await page.evaluate(() => window.desktopFixture.freeze(true));
    await page.getByText('等待新画面', { exact: true }).waitFor({ timeout: 5000 });
    await page.locator('video').click();
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input').length), 0);
    await page.evaluate(() => window.desktopFixture.freeze(false));
    await page.getByText('你正在操作', { exact: true }).waitFor();
  });
  await check('unverifiable-media-stays-watch-only', async page => {
    if (!realRtc) await page.evaluate(() => window.desktopFixture.setFrameIdentity('missing-metadata'));
    await watch(page, false);
    await page.getByText('仅可观看', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '我来操作', exact: true }).isDisabled(), true);
    await page.locator('video').click();
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input' || c.action === 'devices.desktop.control').length), 0);
    await page.screenshot({ path: path.join(output, 'unverifiable-media-watch-only-live.png'), fullPage: true });
    if (!realRtc) {
      await page.evaluate(() => window.desktopFixture.setFrameIdentity('exact'));
      await page.getByText('正在观看', { exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: '我来操作', exact: true }).isEnabled(), true);
    }
  });
  if (!realRtc) await Promise.all([false, true].map(noPlaybackQuality => check(noPlaybackQuality
    ? 'playback-without-frame-apis-stays-watch-only' : 'playback-without-frame-callback-stays-watch-only', async page => {
    await page.evaluate(noPlaybackQuality => {
      Object.defineProperty(HTMLVideoElement.prototype, 'requestVideoFrameCallback', { configurable: true, value: undefined });
      if (noPlaybackQuality) Object.defineProperty(HTMLVideoElement.prototype, 'getVideoPlaybackQuality', { configurable: true, value: undefined });
    }, noPlaybackQuality);
    await watch(page, false);
    await page.getByText('仅可观看', { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => typeof document.querySelector('video').getVideoPlaybackQuality === 'function'), !noPlaybackQuality);
    const start = await page.evaluate(() => {
      const video = document.querySelector('video');
      return { at: Date.now(), frames: video.getVideoPlaybackQuality?.().totalVideoFrames ?? null, time: video.currentTime };
    });
    // Cross the real startup deadline using actual canvas playback progress.
    // No mocked video-frame callback participates in this watch-only journey.
    await page.waitForFunction(start => {
      const phase = document.querySelector('.pw-desktop-viewer')?.getAttribute('data-phase');
      if (phase !== 'live') throw new Error('Healthy watch-only playback unexpectedly left the live phase: ' + phase);
      const video = document.querySelector('video');
      return Date.now() - start.at >= 46_000 && video.currentTime > start.time
        && (start.frames === null || video.getVideoPlaybackQuality().totalVideoFrames > start.frames);
    }, start, { timeout: 52_000, polling: 1000 });
    assert.equal(await page.getByRole('button', { name: '我来操作', exact: true }).isDisabled(), true);
    await page.locator('video').click();
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input' || c.action === 'devices.desktop.control').length), 0);
  })));
  await controlCheck('same-size-mismatched-rtp-never-unlocks-input', async page => {
    await watch(page); await take(page);
    await page.evaluate(() => window.desktopFixture.setFrameIdentity('mismatch'));
    await page.waitForFunction(() => !document.querySelector('.pw-desktop-screen')?.classList.contains('has-control'));
    await page.locator('video').click();
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input').length), 0);
    await page.getByText('仅可观看', { exact: true }).waitFor({ timeout: 8000 });
    await page.evaluate(() => window.desktopFixture.setFrameIdentity('exact'));
    await page.getByText('你正在操作', { exact: true }).waitFor();
  });
  await controlCheck('other-window-control-remains-read-only-until-release', async page => {
    await watch(page);
    await page.evaluate(() => window.desktopFixture.setOwner('other_human'));
    await page.getByText('另一窗口正在控制', { exact: true }).waitFor({ timeout: 12_000 });
    assert.equal(await page.getByRole('button', { name: '我来操作', exact: true }).isDisabled(), true);
    await page.locator('video').click();
    assert.equal(await page.evaluate(() => window.desktopFixture.calls.filter(c => c.action === 'devices.desktop.input' || c.action === 'devices.desktop.control').length), 0);
    await page.evaluate(() => window.desktopFixture.setOwner('none'));
    await page.getByText('正在观看', { exact: true }).waitFor({ timeout: 12_000 });
    await take(page);
  });
  await check('close-and-native-back-release-the-exact-viewer', async page => {
    await watch(page); if (!realRtc) await take(page);
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
await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({ identity: realRtc
  ? 'Mounted production React viewer; real browser-loopback playback with synthetic canvas; WATCH ONLY because no sender RTP/capture mapping exists. Control journeys are skipped. Not Windows or Android acceptance.'
  : 'Mounted production React viewer; explicitly MOCKED RTC, native host and video-frame callbacks with exact synthetic RTP pairs. Real canvas pixels are for layout only. Not real frame association, transport, Windows or Android acceptance.',
  mode: realRtc ? 'rtc-watch-only' : 'mock-rtc-and-frame-callbacks', realFrameAssociation: false, scrollTarget: 'native-authorized-foreground-window', results }, null, 2));
for (const result of results) process.stdout.write(`${result.skipped ? 'SKIP' : result.passed ? 'PASS' : 'FAIL'} ${result.name}${result.error ? ': ' + result.error : ''}\n`);
if (results.some(result => result.passed === false)) process.exitCode = 1;
