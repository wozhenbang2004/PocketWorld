import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createServer } from 'vite';
import { chromium } from '@playwright/test';

const phase = process.env.PW_DEPTH_PHASE || 'after';
console.log(`Knowledge ${phase}: runner started after external UI lock acquisition.`);
const output = `/workspace/scratch/a7233424b5cb/depth-knowledge-evidence/${phase}`;
await fs.mkdir(output, { recursive: true });
const baseline = new Map(phase === 'before' ? ['MemoryWorkspace.tsx', 'MemoryAssertionFields.tsx', 'MemoryDraftReview.tsx', 'MemoryHistoryWorkspace.tsx', 'LearningWorkspace.tsx', 'ExperienceWorkspace.tsx', 'PlansWorkspace.tsx', 'CommitmentsWorkspace.tsx', 'ReminderWorkspace.tsx', 'memory-reading.css', 'learning-workspace.css'].map(file => [path.resolve('src', file), execFileSync('git', ['show', `379911a:web-ui/src/${file}`], { encoding: 'utf8' })]) : []);
const server = await createServer({ cacheDir: '/dev/shm/pw-depth-memory-review-cache', optimizeDeps: { entries: ['tests/depth-knowledge.html'], esbuildOptions: { sourcemap: false } }, plugins: [{ name: 'published-memory-review-baseline', enforce: 'pre', load(id) { return baseline.get(id.split('?')[0]); } }], server: { host: '127.0.0.1', port: 0, watch: null, hmr: false } });
await server.listen();
process.env.TMPDIR = '/dev/shm/pw-depth-knowledge-browser';
await fs.mkdir(process.env.TMPDIR, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: '/workspace/scratch/af478521f69c/pocketworld-browser-cache/chromium_headless_shell-1194/chrome-linux/headless_shell' });
const page = await browser.newPage({ viewport: { width: 360, height: 800 }, reducedMotion: 'reduce' });
page.setDefaultTimeout(12000);
const shots = [], errors = [], checks = [];
page.on('pageerror', error => errors.push(error.message));
const click = name => page.getByRole('button', { name, exact: false }).first().click();
const assertScene = async scene => {
  if (scene === 'assertion-review') {
    const dialog = page.locator('.pw-settings-layer:not([inert])[data-visible=true] [role=dialog]');
    await dialog.getByRole('heading', { name: '核对主体与关系', exact: true }).waitFor({ state: 'visible' });
    assert.equal(await dialog.count(), 1);
    await dialog.getByRole('button', { name: '确认这次修改', exact: true }).waitFor({ state: 'visible' });
    return { heading: '核对主体与关系', control: '确认这次修改' };
  }
  if (scene.startsWith('assertion-')) {
    await page.getByRole('button', { name: /这条陈述的主体/ }).waitFor({ state: 'visible' });
    await page.getByRole('textbox', { name: '事实值或关系目标名称', exact: true }).waitFor({ state: 'visible' });
    if (scene === 'assertion-times') await page.getByRole('textbox', { name: '生效时间', exact: true }).waitFor({ state: 'visible' });
    if (scene === 'assertion-load-error') await page.getByRole('alert').filter({ hasText: '原版本已变化' }).waitFor({ state: 'visible' });
    return { heading: '主体与关系', control: scene === 'assertion-times' ? '生效时间' : '事实值或关系目标名称' };
  }
  const drawer = page.getByRole('dialog');
  await drawer.waitFor({ state: 'visible' });
  assert.equal(await drawer.count(), 1);
  if (scene === 'memory-draft-unknown') {
    await drawer.getByRole('button', { name: '查询原修改结果', exact: true }).waitFor({ state: 'visible' });
    assert.match(await drawer.innerText(), /原修改结果待核对/);
    return { heading: '原修改结果待核对', control: '查询原修改结果' };
  }
  const intent = scene.split('-')[2], title = { create: '保存这条记忆', update: '纠正这条记忆', forget: '遗忘这条记忆' }[intent];
  await drawer.getByRole('heading', { name: title, exact: true }).waitFor({ state: 'visible' });
  await drawer.getByRole('heading', { name: '确认后的影响', exact: true }).waitFor({ state: 'visible' });
  if (phase === 'after') { const identity = drawer.locator('.pw-drawer-body > p.pw-knowledge-status'); await identity.waitFor({ state: 'visible' }); assert.match(await identity.innerText(), intent === 'create' ? /主体：项目同事/ : /主体：你/); assert.match(await identity.innerText(), /推测.*未独立核实/); }
  if (scene.endsWith('-source')) {
    await drawer.getByRole('heading', { name: '原主人消息', exact: true }).waitFor({ state: 'visible' });
    assert.match(await drawer.locator('.pw-source-copy').innerText(), /先复现问题/);
  }
  if (scene.endsWith('-assertion')) {
    const assertion = drawer.locator('.pw-memory-assertion'); await assertion.waitFor({ state: 'visible' });
    assert.match(await assertion.innerText(), /推测/); assert.match(await assertion.innerText(), /先复现问题，再使用相同输入验证/);
  }
  await drawer.getByRole('checkbox', { name: '我已核对正文、来源与以上影响', exact: true }).waitFor({ state: 'visible' });
  return { heading: title, control: scene.endsWith('-source') ? '原主人消息' : '我已核对正文、来源与以上影响' };
};
const capture = async (scene, wide = false) => {
  for (const width of wide ? [360, 1280] : [360]) {
    await page.setViewportSize({ width, height: 800 });
    const identity = await assertScene(scene);
    await page.waitForFunction(() => document.fonts.status === 'loaded', undefined, { timeout: 12000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    if (phase === 'after') assert.equal(overflow, false, `${scene} width`);
    const file = `${scene}-${width}.png`; await page.screenshot({ path: path.join(output, file), fullPage: true, animations: 'disabled', timeout: 15000 }); shots.push({ scene, width, file, horizontalOverflow: overflow, sceneAsserted: true, ...identity });
  }
  await page.setViewportSize({ width: 360, height: 800 });
};
try {
  await page.goto(`${server.resolvedUrls.local[0]}tests/depth-knowledge.html?area=memory`, { waitUntil: 'networkidle' });
  await page.getByRole('tab', { name: '待审候选', exact: true }).click(); await page.locator('[data-memory-id]').first().click();
  await click('主体、关系与时间'); await page.getByRole('button', { name: /这条陈述的主体/ }).waitFor(); await capture('assertion-edit', true);
  await page.getByRole('button', { name: /这条陈述的主体/ }).click(); await page.getByRole('option', { name: '主人', exact: true }).click();
  await page.getByRole('textbox', { name: '描述哪件事，例如偏好或负责项目', exact: true }).fill('整理项目进展');
  if (await page.getByRole('button', { name: /条件与时间/ }).count()) await click('条件与时间'); else await page.getByText('核对时间', { exact: true }).click();
  await capture('assertion-times'); await click('核对本次主体与关系修改'); await capture('assertion-review');
  const input = await page.evaluate(() => window.knowledgeFixture.calls.findLast(row => row.action === 'knowledge.assertion.review')?.args);
  assert.equal(input.readHandle, 'memory-read'); assert.deepEqual(input.subject, { mode: 'owner' }); assert.equal(input.kind, 'inferred');
  assert.equal(await page.getByRole('dialog').getByRole('button', { name: '确认这次修改', exact: true }).isDisabled(), true);
  checks.push('Assertion UI only reviews the exact read handle; unknown subject is not silently owner, and adoption remains a separate acknowledged operation.');

  await page.goto(`${server.resolvedUrls.local[0]}tests/depth-knowledge.html?area=memory&fail=knowledge.assertion.prepare`, { waitUntil: 'networkidle' });
  await page.getByRole('tab', { name: '待审候选', exact: true }).click(); await page.locator('[data-memory-id]').first().click(); await click('主体、关系与时间');
  await page.getByRole('alert').filter({ hasText: '原版本已变化' }).waitFor({ state: 'visible' }); await capture('assertion-load-error');
  await click(phase === 'after' ? '重新读取原身份' : '读取匹配的原身份');
  await page.getByRole('button', { name: /这条陈述的主体/ }).waitFor({ state: 'visible' });
  assert.equal(await page.getByRole('button', { name: /这条陈述的主体/ }).isDisabled(), false);
  assert.equal(await page.getByRole('button', { name: '核对本次主体与关系修改', exact: true }).isDisabled(), true, 'retry must not choose owner or a predicate');
  await capture('assertion-recovered');

  for (const intent of ['create', 'update', 'forget']) {
    await page.goto(`${server.resolvedUrls.local[0]}tests/depth-knowledge.html?area=draft&intent=${intent}`, { waitUntil: 'networkidle' });
    await page.getByRole('button', { name: `确认${intent === 'create' ? '保存' : intent === 'update' ? '纠正' : '遗忘'}`, exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: `确认${intent === 'create' ? '保存' : intent === 'update' ? '纠正' : '遗忘'}`, exact: true }).isDisabled(), true);
    await capture(`memory-draft-${intent}`, true);
    const assertionButton = page.getByRole('button', { name: intent === 'forget' ? /将遗忘的主体与依据/ : /^主体与依据/ });
    if (await assertionButton.count()) await assertionButton.first().click();
    await capture(`memory-draft-${intent}-assertion`);
    await click('来源 1'); await capture(`memory-draft-${intent}-source`);
    const sourceRead = await page.evaluate(() => window.knowledgeFixture.calls.findLast(row => row.action === 'memoryDraft.source')?.args);
    assert.deepEqual(sourceRead, { handle: 'draft-memory-handle', index: 0, offset: 0 });
    if (intent === 'update') {
      await page.getByText('我已核对正文、来源与以上影响', { exact: true }).click(); await click('确认纠正');
      await page.getByRole('button', { name: '查询原修改结果', exact: true }).waitFor(); await capture('memory-draft-unknown');
      const commands = await page.evaluate(() => window.knowledgeFixture.calls);
      assert.equal(commands.filter(row => row.action === 'memoryDraft.confirm').length, 1);
      assert.deepEqual(commands.find(row => row.action === 'memoryDraft.confirm').args, { handle: 'draft-memory-handle' });
      await click('查询原修改结果');
      assert.equal(await page.getByRole('button', { name: '确认纠正', exact: true }).count(), 0);
      const afterQuery = await page.evaluate(() => window.knowledgeFixture.calls);
      assert.deepEqual(afterQuery.findLast(row => row.action === 'memoryDraft.query')?.args, { taskId: 'task-a', runId: 'run-a', epoch: 1, operationId: 'proposal-a', proposalDigest: 'original-proposal-digest' });
      assert.equal(afterQuery.filter(row => row.action === 'memoryDraft.confirm').length, 1, 'query cannot replay the mutation');
    }
  }
  checks.push('Create/update/forget review compares original content and impact; source reading uses the original proposal handle; unknown confirmation is queried without replay.');
  assert.deepEqual(errors, []);
  await fs.writeFile(path.join(output, 'memory-review-receipt.json'), JSON.stringify({ phase, scope: 'Production MemoryAssertionFields and MemoryDraftReview with synthetic native receipts; not Android or native disk verification.', shots, checks, errors }, null, 2));
  console.log(JSON.stringify({ phase, shots: shots.length, checks, errors }, null, 2));
} catch (error) { await page.screenshot({ path: path.join(output, 'memory-review-failure.png'), fullPage: true, animations: 'disabled', timeout: 15000 }); throw error; }
finally { await browser.close(); await server.close(); }
