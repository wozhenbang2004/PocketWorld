import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createServer } from 'vite';
import { chromium } from '@playwright/test';

const phase = process.env.PW_DEPTH_PHASE || 'after';
console.log(`Knowledge ${phase}: runner started after external UI lock acquisition.`);
const output = process.env.PW_DEPTH_OUTPUT || `/workspace/scratch/a7233424b5cb/depth-knowledge-evidence/${phase}`;
await fs.mkdir(output, { recursive: true });
const baselineFiles = ['MemoryWorkspace.tsx', 'MemoryHistoryWorkspace.tsx', 'LearningWorkspace.tsx', 'ExperienceWorkspace.tsx', 'PlansWorkspace.tsx', 'CommitmentsWorkspace.tsx', 'ReminderWorkspace.tsx', 'MemoryAssertionFields.tsx', 'MemoryDraftReview.tsx', 'memory-reading.css', 'learning-workspace.css'];
const baseline = new Map(phase === 'before' ? baselineFiles.map(file => [path.resolve('src', file), execFileSync('git', ['show', `379911a:web-ui/src/${file}`], { encoding: 'utf8' })]) : []);
const server = await createServer({ cacheDir: '/dev/shm/pw-depth-knowledge-cache', optimizeDeps: { entries: ['tests/depth-knowledge.html'], esbuildOptions: { sourcemap: false } }, plugins: [{ name: 'knowledge-published-baseline', enforce: 'pre', load(id) { return baseline.get(id.split('?')[0]); } }], server: { host: '127.0.0.1', port: 0, watch: null, hmr: false } });
await server.listen();
process.env.TMPDIR = '/dev/shm/pw-depth-knowledge-browser';
await fs.mkdir(process.env.TMPDIR, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.PW_CHROMIUM || '/workspace/scratch/af478521f69c/pocketworld-browser-cache/chromium_headless_shell-1194/chrome-linux/headless_shell' });
const shots = [], checks = [], errors = [];
const areas = new Set((process.env.PW_DEPTH_AREAS || 'memory,history,learning,plans,commitments,reminders').split(','));
const page = await browser.newPage({ viewport: { width: 360, height: 800 }, reducedMotion: 'reduce' });
page.setDefaultTimeout(12000);
page.on('pageerror', error => errors.push(error.message));
const click = (name, scope = page) => scope.getByRole('button', { name, exact: false }).first().click();
const select = async (label, option) => { await page.getByRole('button', { name: label, exact: false }).first().click(); await page.getByRole('option', { name: option, exact: false }).first().click(); };
// A screenshot is accepted only after its named product scene is present.
// SettingsDialog mounts first, then becomes visible after two animation frames;
// fonts.ready alone can capture the previous list while the dialog is transparent.
const sceneExpectations = {
  'memory-list': { button: '选择记忆归纳', rows: ['[data-memory-id]', 2] },
  'memory-detail': { heading: '这条记忆', button: '返回列表' },
  'memory-source': { heading: '原复盘记录', pre: /主人：先复现问题/ },
  'memory-edit': { textbox: '纠正后的正文', value: '先保存原始报错，再修复并验证。' },
  'memory-review': { dialog: '保存纠正', button: '确认这次修改', checkbox: '我已阅读内容和影响，确认这一次操作' },
  'memory-candidates': { tab: '待审候选', rows: ['[data-memory-id]', 1] },
  'memory-candidate-detail': { heading: '候选审阅', button: '核对并采用候选' },
  'memory-candidate-review': { dialog: '采用这条候选', button: '确认这次修改' },
  'history-search': { searchbox: '旧事关键词或记载日期', button: '查旧事' },
  'history-results': { button: /曾采用逐项核对/, searchbox: '旧事关键词或记载日期' },
  'history-detail': { heading: '这份已存记忆', button: '查看原来源 1' },
  'history-source': { heading: '原复盘记录', pre: /先复现，再修复/ },
  'history-edit': { textbox: '正确的内容', button: '核对纠正' },
  'history-cognition': { heading: '核对这组冲突', button: /保留的当前事实/ },
  'task-memory-results': { button: /排查项目预览空白/, searchbox: '搜索原任务' },
  'task-memory-receipt': { heading: '排查项目预览空白', button: '读取本轮记忆凭据', text: /本次提供给模型的记忆来源/ },
  'learning-jobs': { heading: '学习任务', tab: '学习任务', button: '整理新来源' },
  'learning-job': { heading: '排查项目预览空白', button: '核对并继续原批次', rows: ['.pw-learning-batches > li', 3] },
  'learning-review': { dialog: '继续这个原学习任务', button: '发送并继续原任务' },
  'learning-sources': { heading: '整理新来源', button: '读取任务经过' },
  'learning-episode': { heading: '排查项目预览空白', button: '保留来源并加入清单' },
  'learning-selection': { heading: '本次所选来源', button: '建立学习任务，暂不发送模型' },
  'experience-methods': { heading: '方法', tab: '方法', button: /打开：先复现，再判断原因/ },
  'experience-method': { heading: '先复现，再判断原因。', button: '核对并起草技能' },
  'experience-draft-review': { dialog: '从原方法记忆起草技能', button: '确认这一次操作' },
  'experience-draft': { heading: '技能草稿', textbox: '完整 SKILL.md', value: '# 项目周报整理\n\n按真实来源整理进展，保留待核对结果。' },
  'experience-import-review': { dialog: '保存为停用的技能', button: '确认这一次操作' },
  'experience-skills': { heading: '来源技能', tab: '来源技能', button: '打开：项目周报整理' },
  'experience-skill': { heading: '项目周报整理', button: '读取原正文' },
  'experience-skill-read': { heading: '项目周报整理', button: '核对并批准此原版本', text: /当前文件已完整读取/ },
  'experience-approve-review': { dialog: '批准这个原版本的技能', button: '确认这一次操作' },
  'plans-list': { heading: '持续目标', button: '建立持续目标', rows: ['[data-plan-id]', 1] },
  'plans-detail': { heading: '整理本周项目交付', button: '审阅目标与执行范围' },
  'plans-authorization': { dialog: '审阅执行范围', button: /触发方式/, checkbox: '同意将本目标需要的项目文件、手机界面与可见文字发送给已配置的云端模型' },
  'plans-review': { dialog: '批准目标范围', button: '确认批准目标范围', text: 'reports/week.md' },
  'plans-create': { dialog: '建立持续目标', textbox: '最终要得到什么结果', value: '', button: '核对保存内容' },
  'commitments-list': { heading: '单项委托', button: '新增委托', rows: ['[data-commitment-id]', 1] },
  'commitment-detail': { heading: '确认项目发布结果', button: '确认已完成' },
  'commitment-receipts': { heading: '通知结果待核对', button: '查询本机通知' },
  'commitment-review': { dialog: '确认已经完成', button: '确认确认已经完成', checkbox: '我已核对这次原记录与操作' },
  'commitment-edit': { dialog: '编辑当前委托', textbox: '承诺目标', value: '确认项目发布结果', button: '核对保存内容' },
  'commitment-policy': { dialog: '承诺跟进设置', checkbox: '启用跟进通知', button: '核对设置' },
  'commitment-create': { dialog: '新增明确委托', textbox: '承诺目标', value: '', button: '核对保存内容' },
  'reminder-list': { heading: '提醒与主动任务', button: '新建提醒' },
  'reminder-detail': { region: '提醒规则', heading: '晚间检查项目进度', button: '编辑' },
  'reminder-edit': { dialog: '编辑提醒', textbox: '提醒内容', value: '晚间检查项目进度', button: '核对提醒计划' },
  'reminder-advanced': { dialog: '编辑提醒', text: '不存在的当地时间跳过', button: '核对提醒计划' },
  'reminder-review': { dialog: '核对提醒计划', button: '保存修改', text: '晚间检查项目进度' },
  'reminder-create': { dialog: '新建提醒', textbox: '提醒内容', value: '', button: '核对提醒计划' },
};
const uniqueVisible = async (locator, label) => { await locator.waitFor({ state: 'visible' }); assert.equal(await locator.count(), 1, `${label}: unique visible control`); };
const assertScene = async scene => {
  const expected = sceneExpectations[scene]; assert.ok(expected, `Expected scene registered: ${scene}`);
  let scope = page;
  if (expected.dialog) {
    const dialog = page.locator('.pw-settings-layer:not([inert])[data-visible=true] [role=dialog]');
    await uniqueVisible(dialog, `${scene} active visible dialog`);
    await uniqueVisible(dialog.getByRole('heading', { name: expected.dialog, exact: true }), `${scene} dialog heading`);
    scope = dialog;
  } else {
    assert.equal(await page.locator('.pw-settings-layer:not([inert])[data-visible=true] [role=dialog]').count(), 0, `${scene} must be outside a modal`);
  }
  if (expected.region) { scope = page.locator(`section[aria-label="${expected.region}"]`); await uniqueVisible(scope, `${scene} object region`); }
  if (expected.heading) await uniqueVisible(scope.getByRole('heading', { name: expected.heading, exact: true }), `${scene} heading`);
  if (expected.button) {
    const button = scope.getByRole('button', { name: expected.button, exact: typeof expected.button === 'string' });
    // Published reminders had inline rules, and no separate detail scene.
    if (phase === 'before' && scene === 'reminder-detail') { assert.equal(await button.count(), 2); }
    else await uniqueVisible(button, `${scene} action`);
  }
  if (expected.checkbox) await uniqueVisible(scope.getByRole('checkbox', { name: expected.checkbox, exact: true }), `${scene} acknowledgement`);
  if (expected.textbox) {
    const field = scope.getByRole('textbox', { name: expected.textbox, exact: true }); await uniqueVisible(field, `${scene} input`);
    if (expected.value !== undefined) assert.equal(await field.inputValue(), expected.value, `${scene} correct object/draft`);
  }
  if (expected.searchbox) await uniqueVisible(scope.getByRole('searchbox', { name: expected.searchbox, exact: true }), `${scene} search`);
  if (expected.tab) assert.equal(await scope.getByRole('tab', { name: expected.tab, exact: true }).getAttribute('aria-selected'), 'true', `${scene} selected tab`);
  if (expected.rows) assert.equal(await scope.locator(expected.rows[0]).count(), expected.rows[1], `${scene} original collection`);
  if (expected.pre) { const text = scope.locator('.pw-source-copy'); await uniqueVisible(text, `${scene} source body`); assert.match(await text.innerText(), expected.pre); }
  if (expected.text) await uniqueVisible(scope.getByText(expected.text, { exact: typeof expected.text === 'string' }).first(), `${scene} expected content`);
  return { dialog: expected.dialog || null, heading: expected.heading || null, input: expected.textbox || null, action: String(expected.button || ''), sceneAsserted: true, baselineInlineRule: phase === 'before' && scene === 'reminder-detail' };
};
const capture = async (scene, wide = false) => {
  for (const width of wide ? [360, 1280] : [360]) {
    await page.setViewportSize({ width, height: 800 });
    const identity = await assertScene(scene);
    await page.waitForFunction(() => document.fonts.status === 'loaded', undefined, { timeout: 12000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    const file = `${scene}-${width}.png`;
    await page.screenshot({ path: path.join(output, file), fullPage: true, animations: 'disabled', timeout: 15000 });
    shots.push({ scene, width, file, horizontalOverflow: overflow, ...identity });
    if (phase === 'after') assert.equal(overflow, false, `${scene} at ${width}px`);
  }
  await page.setViewportSize({ width: 360, height: 800 });
  console.log(`captured ${scene}`);
};
const open = async area => { await page.goto(`${server.resolvedUrls.local[0]}tests/depth-knowledge.html?area=${area}`, { waitUntil: 'networkidle' }); };
try {
  if (areas.has('memory')) {
  await open('memory'); await page.locator('[data-memory-id]').first().waitFor(); await capture('memory-list', true);
  await page.locator('[data-memory-id]').first().click(); await capture('memory-detail', true);
  await click('查看来源 1'); await capture('memory-source');
  await click('纠正这条记忆'); await page.getByRole('textbox', { name: '纠正后的正文', exact: true }).fill('先保存原始报错，再修复并验证。'); await capture('memory-edit');
  await click('核对内容修改'); await capture('memory-review');
  assert.equal(await page.getByRole('dialog').getByRole('button', { name: '确认这次修改', exact: true }).isDisabled(), true);
  await click('返回', page.getByRole('dialog')); await click('返回列表');
  await page.getByRole('tab', { name: '待审候选', exact: true }).click(); await capture('memory-candidates');
  await page.locator('[data-memory-id]').first().click(); await capture('memory-candidate-detail');
  await click('核对并采用候选'); await capture('memory-candidate-review');
  checks.push('Memory selection/source/edit preserve original read handle; no confirm without acknowledgement.');

  }
  if (areas.has('history')) {
  await open('history'); await capture('history-search'); await page.getByRole('searchbox').fill('排查'); await click('查旧事'); await capture('history-results');
  await click(/曾采用逐项核对/); await capture('history-detail'); await click('查看原来源 1'); await capture('history-source');
  await click('纠正或遗忘'); await capture('history-edit'); await click('返回来源列表');
  await click('主体当前认知与冲突'); await click('读取当前认知'); await capture('history-cognition', true);
  await select('查阅内容', '原任务与本轮记忆来源'); await page.getByRole('searchbox').fill('预览'); await click('搜索任务记录'); await capture('task-memory-results');
  await click(/排查项目预览空白/); await click('读取本轮记忆凭据'); await capture('task-memory-receipt');
  checks.push('History/source and task-memory evidence stay separate; original receipt is requested explicitly.');

  }
  if (areas.has('learning')) {
  await open('learning'); await page.getByRole('button', { name: /打开：排查项目预览空白/ }).waitFor(); await capture('learning-jobs', true);
  await click(/打开：排查项目预览空白/); await capture('learning-job', true);
  await click('核对并继续原批次'); await capture('learning-review');
  assert.equal(await page.getByRole('dialog').getByRole('button', { name: '发送并继续原任务', exact: true }).isDisabled(), true);
  await click('返回核对', page.getByRole('dialog')); await click('返回学习任务'); await click('整理新来源'); await capture('learning-sources');
  await select('选择原任务', '排查项目预览空白'); await click('读取任务经过'); await capture('learning-episode');
  await click('保留来源并加入清单'); await capture('learning-selection', true);
  await page.getByRole('tab', { name: '方法', exact: true }).click(); await capture('experience-methods', true);
  await click(/打开：先复现，再判断原因/); await capture('experience-method', true);
  await click('核对并起草技能'); await capture('experience-draft-review');
  await page.getByText('我已核对原内容、版本和影响，确认这一次操作', { exact: true }).click(); await click('确认这一次操作', page.getByRole('dialog'));
  await page.getByRole('textbox', { name: '完整 SKILL.md', exact: true }).waitFor(); await capture('experience-draft', true);
  await click('核对并保存为停用技能'); await capture('experience-import-review'); await click('返回', page.getByRole('dialog'));
  await click('返回方法'); await page.getByRole('tab', { name: '来源技能', exact: true }).click(); await capture('experience-skills');
  await click('打开：项目周报整理'); await capture('experience-skill');
  assert.equal(await page.getByRole('button', { name: '核对并批准此原版本', exact: true }).isDisabled(), true);
  await click('读取原正文'); await capture('experience-skill-read', true); await click('核对并批准此原版本'); await capture('experience-approve-review');
  checks.push('Learning and experience require explicit model review; skill approval remains disabled until current manifest is fully read.');

  }
  if (areas.has('plans')) {
  await open('plans'); await page.locator('[data-plan-id]').waitFor(); await capture('plans-list', true);
  await page.locator('[data-plan-id]').click(); await capture('plans-detail', true); await click('审阅目标与执行范围'); await capture('plans-authorization', true);
  await page.getByText('同意将本目标需要的项目文件、手机界面与可见文字发送给已配置的云端模型', { exact: true }).click(); await click('核对准确授权'); await capture('plans-review');
  assert.equal(await page.getByRole('dialog').getByRole('button', { name: /确认批准目标范围/ }).isDisabled(), true);
  await click('返回', page.getByRole('dialog')); await page.getByRole('dialog').getByRole('button', { name: /关闭/ }).first().click();
  await click('返回目标列表'); await click('建立持续目标'); await capture('plans-create');
  checks.push('Plan authorization remains a distinct native review; cloud observation consent and final acknowledgement are separate.');

  }
  if (areas.has('commitments')) {
  await open('commitments'); await page.locator('[data-commitment-id]').waitFor(); await capture('commitments-list', true);
  await page.locator('[data-commitment-id]').click(); await capture('commitment-detail', true);
  await click('跟进通知记录'); await click('读取原回执'); await capture('commitment-receipts');
  await click('确认已完成'); await capture('commitment-review'); await click('返回', page.getByRole('dialog'));
  await click('调整委托'); await click('编辑当前委托'); await capture('commitment-edit'); await page.getByRole('dialog').getByRole('button', { name: /关闭/ }).first().click();
  await click('返回委托列表'); await click(/跟进设置/); await capture('commitment-policy', true);
  await page.getByRole('dialog').getByRole('button', { name: /关闭/ }).first().click(); await click('新增委托'); await capture('commitment-create');
  checks.push('Commitment details, notification receipt, edit and follow-up policy use separate scenes; completion opens review.');

  }
  if (areas.has('reminders')) {
  await open('reminders'); await capture('reminder-list', true);
  if (await page.locator('[data-reminder-id]').count()) { await page.locator('[data-reminder-id]').first().click(); }
  await capture('reminder-detail');
  await click('编辑'); await capture('reminder-edit', true);
  assert.equal(await page.evaluate(() => window.knowledgeFixture.calls.filter(call => call.action.startsWith('automation.')).length), 0, 'selection and local editor must not call native mutations');
  await click('高级选项'); await capture('reminder-advanced'); await click('核对提醒计划'); await capture('reminder-review');
  const reminderInput = await page.evaluate(() => window.knowledgeFixture.calls.findLast(call => call.action === 'automation.reviewSchedule')?.args);
  assert.equal(reminderInput.id, 'reminder-a'); assert.equal(reminderInput.revision, 3); assert.equal(reminderInput.draft.preserveSchedule, true);
  assert.equal(await page.evaluate(() => window.knowledgeFixture.calls.some(call => call.action === 'automation.confirmReview')), false);
  await click('返回修改', page.getByRole('dialog')); await click('取消', page.getByRole('dialog'));
  if (await page.getByRole('button', { name: '返回提醒列表', exact: true }).count()) await click('返回提醒列表');
  await click('新建提醒'); await capture('reminder-create');
  checks.push('Reminder selection/edit performs no write; changes still require native calendar preview and explicit confirm.');
  }
  assert.deepEqual(errors, []);
  await fs.writeFile(path.join(output, 'receipt.json'), JSON.stringify({ phase, scope: 'Actual production React components and simulated native command receipts; no Android device or native persistence verification.', shots, checks, errors }, null, 2));
  console.log(JSON.stringify({ phase, uniqueScenes: new Set(shots.map(shot => shot.scene)).size, screenshots: shots.length, checks, errors }, null, 2));
} catch (error) { await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true, animations: 'disabled', timeout: 15000 }); await fs.writeFile(path.join(output, 'failure.json'), JSON.stringify({ error: String(error), shots, errors }, null, 2)); throw error; }
finally { await browser.close(); await server.close(); }
