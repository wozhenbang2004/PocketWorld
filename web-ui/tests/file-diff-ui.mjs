// Production file editor and adopted diff surface; synthetic file session, no host writes.
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';
const root = fileURLToPath(new URL('..', import.meta.url));
const server = await createServer({ root, logLevel: 'error', server: { host: '127.0.0.1', port: 0 } });
await server.listen();
const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--disable-background-networking'] });
const before = '开头保持不变\n状态：旧内容\n结尾保持不变\n', after = '开头保持不变\n状态：新内容\n结尾保持不变\n';
const observations = [];
try {
  for (const [width, theme] of [[900, 'light'], [430, 'light'], [320, 'dark']]) {
    const page = await browser.newPage({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    await page.goto(server.resolvedUrls.local[0] + 'tests/file-diff-fixture.html');
    await page.getByRole('region', { name: '当前文件差异', exact: true }).waitFor();
    await page.evaluate(dark => document.documentElement.classList.toggle('dark', dark), theme === 'dark');
    const original = page.locator('.pw-diff-columns > section').nth(0), draft = page.locator('.pw-diff-columns > section').nth(1);
    assert.equal(await original.locator('pre').textContent(), before);
    assert.equal(await draft.locator('pre').textContent(), after);
    assert.equal(await original.locator('del').allTextContents().then(parts => parts.join('')), '旧', 'Only the removed fragment should be marked, leaving unchanged lines neutral');
    assert.equal(await draft.locator('ins').allTextContents().then(parts => parts.join('')), '新');
    const paint = await page.locator('.pw-inline-diff').evaluate(card => {
      const parts = [...card.querySelectorAll('.pw-diff-columns > section')];
      return parts.map(section => ({ foreground: getComputedStyle(section.querySelector('pre')).color, background: getComputedStyle(section).backgroundColor === 'rgba(0, 0, 0, 0)' ? getComputedStyle(card).backgroundColor : getComputedStyle(section).backgroundColor }));
    });
    const luminance = color => color.match(/[\d.]+/g).slice(0, 3).map(Number).map(value => value / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
    for (const surface of paint) {
      const levels = [luminance(surface.foreground), luminance(surface.background)].sort((a, b) => a - b);
      assert.ok((levels[1] + .05) / (levels[0] + .05) >= 4.5, 'Comparison text must stay readable on its actual surface in both themes');
    }
    assert.deepEqual(await page.evaluate(() => window.fileDiffFixture.calls), [], 'Comparing never saves or adopts a revision');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.getByRole('button', { name: '返回编辑', exact: true }).click();
    assert.equal(await page.getByRole('textbox', { name: '文件内容', exact: true }).inputValue(), after);
    await page.getByRole('tab', { name: '比较修改', exact: true }).click();
    await page.evaluate(() => window.fileDiffFixture.back());
    await page.getByRole('textbox', { name: '文件内容', exact: true }).waitFor();
    await page.evaluate(() => window.fileDiffFixture.set('', '新增\n'));
    await page.waitForFunction(() => document.querySelector('.pw-diff-columns ins')?.textContent === '新增\n');
    assert.equal(await page.locator('.pw-diff-columns del').count(), 0, 'An empty-file placeholder is not deleted content');
    await page.evaluate(() => window.fileDiffFixture.set('末尾\n', '末尾'));
    await page.getByText('标记中包含空白或换行变化。', { exact: true }).waitFor();
    assert.equal(await original.locator('del').textContent(), '\n');
    await page.evaluate(() => window.fileDiffFixture.set('<img src=x onerror=alert(1)>原文', '<img src=x onerror=alert(1)>草稿'));
    await page.waitForFunction(() => document.querySelector('.pw-diff-columns pre')?.textContent?.includes('<img'));
    assert.equal(await page.locator('.pw-diff-columns img').count(), 0, 'File content remains escaped text');
    await page.evaluate(() => window.fileDiffFixture.set('相同\n', '相同\n'));
    await page.getByText('当前内容与原版本一致。', { exact: true }).waitFor();
    assert.equal(await page.locator('.pw-diff-columns').count(), 0);
    assert.equal(await page.getByRole('button', { name: '保存修改', exact: true }).isDisabled(), true);
    observations.push({ width, theme, exactFragments: 'passed', sourceText: 'preserved', returnAndBack: 'passed', emptyAndNewline: 'passed', htmlEscaping: 'passed' });
    await page.close();
  }
  console.log(JSON.stringify({ browser: 'Edge', transport: 'synthetic file session', observations }, null, 2));
} finally { await browser.close(); await server.close(); }
