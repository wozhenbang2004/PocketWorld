import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
const dark = process.env.PW_DEPTH_DARK === '1';
const phase = process.env.PW_DEPTH_PHASE || 'before', out = process.env.PW_DEPTH_OUTPUT || path.resolve('test-results/depth-browser');
await fs.mkdir(out, { recursive: true });
const cacheRoot = process.env.PW_DEPTH_CACHE || '/dev/shm/pw-depth-browser-cache';
await fs.mkdir(path.join(cacheRoot, 'browser'), { recursive: true });
process.env.TMPDIR = path.join(cacheRoot, 'browser');
const server = await createServer({ cacheDir: path.join(cacheRoot, 'vite'), optimizeDeps: { entries: ['tests/depth-browser.html'] }, server: { host: '127.0.0.1', port: 0 } });
await server.listen();
const browser = await chromium.launch({ executablePath: process.env.PW_CHROMIUM || chromium.executablePath() });
const errors = [], records = [];
// Each screenshot must prove its requested surface, object and foreground layer.
// A fixed delay or a zero-error page is not evidence that a dialog has opened.
async function assertScene(page, scene) {
    const selectors = {
        'browser-new': '.pw-browser-workspace textarea',
        'browser-research': '.pw-browser-workspace h2',
        'browser-handoff': '.pw-browser-workspace button',
        'browser-sources': '.pw-browser-workspace h2',
        'browser-pages': '.pw-browser-workspace h2',
        'browser-error': '.pw-browser-workspace [role=alert]',
        'browser-review': '[role=dialog] h2',
        'chrome': '.browser-chrome input',
        'chrome-error': '.browser-chrome-status',
        'reader': '.browser-reader-prose',
        'reader-table': '.browser-reader-content caption',
        'reader-field': '.browser-reader-field-text',
        'reader-error': '.browser-reader-error',
        'bootstrap-loading': '.pw-bootstrap h1',
        'bootstrap-failure': '.pw-bootstrap h1',
        'bootstrap-recovery': '.pw-bootstrap h1',
        'realtime-start': '.pw-realtime-start-copy h3',
        'realtime-invitation': '.pw-realtime-invitation h3',
        'realtime-live': '.pw-realtime-live h3',
        'realtime-transcript': '.pw-realtime-turn',
        'realtime-error': '.pw-realtime-alert',
        'phone-card': '.pw-phone-viewer-card-title strong',
        'phone-sheet': '.pw-phone-viewer-sheet-title',
        'phone-error': '.pw-phone-viewer-placeholder strong',
        'browser-profiles': '[role=dialog] h2',
        'browser-read-scope': '[role=dialog] h2',
        'browser-action-scope': '[role=dialog] h2',
        'browser-scope-review': '[role=dialog] h2',
        'browser-recipe-form': '[role=dialog] h2',
        'browser-recipe-review': '[role=dialog] h2',
        'browser-draft-conflict': '[role=dialog] h2',
        'browser-upload-review': '[role=dialog] h2',
        'bootstrap-review-empty': '[role=dialog] h2',
        'bootstrap-review-copy': '[role=dialog] h2',
        'realtime-settings-natural': '[role=dialog] h2',
        'realtime-settings-provider': '[role=dialog] h2',
        'realtime-settings-room': '[role=dialog] h2',
        'realtime-review': '[role=dialog] h2',
        'realtime-voice-turn': '.pw-realtime-voice h3',
        'realtime-room-live': '.pw-realtime-live h3',
        'realtime-task-recovery': '.pw-realtime-saved h4',
        'quick-start': '.pw-voice-stage h3',
        'quick-recording': '.pw-voice-stage h3',
        'quick-stopping': '.pw-voice-stage h3',
        'quick-review': '.pw-voice-stage h3',
        'quick-close': '.pw-voice-stage h3',
        'quick-receipt': '.pw-voice-stage h3',
        'quick-unknown': '.pw-voice-draft [role=alert]',
        'quick-other': '.pw-voice-draft [role=alert]',
        'quick-start-wait': '.pw-voice-draft [role=status]',
        'quick-save-wait': '.pw-voice-stage h3',
    };
    const expected = {
        'browser-research': '比较远程协作工具，保留价格与数据导出依据',
        'browser-handoff': '交还当前页并继续',
        'browser-sources': '保留的依据 · 2',
        'browser-pages': '浏览页面 · 2',
        'browser-error': '当前网页读取失败，请刷新核对同一研究。',
        'browser-review': '核对这次网页提交',
        'reader': '管理员可以导出项目、任务与评论。',
        'reader-table': '按原行列顺序保存的字段',
        'reader-field': '项目、任务与评论',
        'reader-error': '保存的原件暂时无法读取，请重读同一原件。',
        'bootstrap-loading': '正在准备 PocketWorld',
        'bootstrap-failure': '需要处理启动问题',
        'bootstrap-recovery': '核对本机数据与保全副本',
        'realtime-start': '开始一次自然语音对话',
        'realtime-invitation': '和我的助手通话',
        'realtime-live': '当前自然语音',
        'realtime-transcript': '请整理今天的讨论，列出明天需要继续的三个事项。',
        'realtime-error': '媒体资源释放未确认。',
        'phone-card': '手机独立屏',
        'phone-sheet': '手机独立屏',
        'phone-error': '暂时无法读取画面',
        'browser-profiles': '登录资料',
        'browser-read-scope': '本轮只读范围',
        'browser-action-scope': '当前任务的连续浏览',
        'browser-scope-review': '核对本次浏览范围',
        'browser-recipe-form': '制作网站只读配方',
        'browser-recipe-review': '核对这份网站只读配方',
        'browser-draft-conflict': '核对这个原研究草稿',
        'browser-upload-review': '核对站点上传结果',
        'bootstrap-review-empty': '核对原件保全后，建立空库？',
        'bootstrap-review-copy': '核对本次对话文字恢复',
        'realtime-settings-natural': '通话连接设置',
        'realtime-settings-provider': '通话连接设置',
        'realtime-settings-room': '通话连接设置',
        'realtime-review': '接受并连接本次通话',
        'realtime-voice-turn': '本轮听写与回复',
        'realtime-room-live': '当前房间通话',
        'realtime-task-recovery': '整理昨天保留的语音目标',
        'quick-start': '补充原浮层说明',
        'quick-recording': '正在听写',
        'quick-stopping': '正在结束听写',
        'quick-review': '核对本次文字',
        'quick-close': '结束本次听写？',
        'quick-receipt': '已追加到原草稿',
        'quick-unknown': '音频资源释放尚未确认，不能再次录音或追加。',
        'quick-other': '另一份听写尚未处理，不能从这里开始或停止它。',
        'quick-start-wait': '已提交本次操作，等待系统状态更新；没有重发。',
        'quick-save-wait': '追加结果待核对',
    };
    let target = scene === 'browser-new' ? page.getByRole('textbox', { name: '研究目标', exact: true }) : page.locator(selectors[scene]);
    if (expected[scene]) target = target.filter({ hasText: expected[scene] });
    await target.first().waitFor({ state: 'visible' });
    assert.equal(await target.count(), 1, `${scene}: one expected object`);
    assert.equal(await target.evaluate(el => !!el.closest('[inert],[aria-hidden=true]')), false, `${scene}: current foreground, not inert/hidden`);
    if (scene === 'browser-new') assert.equal(await target.inputValue(), '比较远程协作工具，保留价格与数据导出依据');
    if (scene === 'chrome') assert.equal(await target.inputValue(), 'https://example.test/docs/export');
    if (scene === 'chrome-error') assert.equal(await target.getAttribute('data-notice'), 'unconfirmed');
    if (scene === 'browser-review') assert.equal(await page.getByRole('button', { name: '确认这次操作', exact: true }).isDisabled(), true);
    if (scene === 'realtime-invitation') assert.equal(await page.getByRole('checkbox', { name: '发送本机麦克风声音', exact: true }).isChecked(), false);
    const visible = async locator => { await locator.waitFor({ state: 'visible' }); assert.equal(await locator.isVisible(), true); };
    if (scene === 'browser-profiles') await visible(page.getByRole('heading', { name: '团队资料', exact: true }));
    if (scene === 'browser-read-scope') {
        assert.equal(await page.getByRole('checkbox', { name: '本轮自动只读范围', exact: true }).isChecked(), false);
        assert.equal(await page.getByRole('button', { name: '核对实际范围', exact: true }).isDisabled(), true);
    }
    if (scene === 'browser-action-scope') {
        assert.equal(await page.getByRole('checkbox', { name: '在明确网站内连续完成日常浏览', exact: true }).isChecked(), true);
        await visible(page.getByRole('textbox', { name: '允许的网站来源 · 每行一个', exact: true }));
    }
    if (scene === 'browser-scope-review') {
        await visible(page.getByRole('dialog').getByText('https://example.test/docs/export', { exact: true }));
        assert.equal(await page.getByRole('button', { name: '授予这轮范围', exact: true }).isDisabled(), true);
    }
    if (scene === 'browser-recipe-form') {
        await visible(page.getByRole('textbox', { name: '区域识别词，如表格标题', exact: true }));
        assert.equal(await page.getByRole('button', { name: '核对读取字段', exact: true }).isDisabled(), true);
    }
    if (scene === 'browser-recipe-review') assert.equal(await page.getByRole('button', { name: '保存为停用配方', exact: true }).isDisabled(), true);
    if (scene === 'browser-draft-conflict') {
        await visible(page.getByText('另一窗口保留的研究目标：比较团队计划与导出范围。', { exact: true }));
        await visible(page.getByRole('button', { name: '保留当前输入并保存', exact: true }));
    }
    if (scene === 'browser-upload-review') {
        await visible(page.getByRole('textbox', { name: '站点提示与实际结果', exact: true }));
        for (const name of ['站点已接收', '站点已拒绝', '仍未知']) assert.equal(await page.getByRole('button', { name, exact: true }).isDisabled(), true);
    }
    if (scene.startsWith('bootstrap-review-')) {
        assert.equal(await page.getByRole('button', { name: scene.endsWith('copy') ? '确认本次恢复对话文字' : '确认本次建立空库', exact: true }).isDisabled(), true);
        if (scene.endsWith('copy')) await visible(page.getByRole('heading', { name: '本次可恢复部分对话文字', exact: true }));
    }
    if (scene.startsWith('realtime-settings-')) {
        await visible(page.getByRole('textbox', { name: scene.endsWith('natural') ? '实时语音 WSS 端点' : scene.endsWith('provider') ? '凭据服务 HTTPS 端点' : 'LiveKit 房间 WSS 地址', exact: true }));
        if (scene.endsWith('provider')) await visible(page.getByRole('textbox', { name: '真实部署的 agent 名称', exact: true }));
        if (scene.endsWith('natural')) assert.equal(await page.getByRole('textbox', { name: '服务实际支持的语音模型', exact: true }).inputValue(), 'realtime-model');
    }
    if (scene === 'realtime-review') assert.equal(await page.getByRole('button', { name: '确认接受并连接本次通话', exact: true }).isDisabled(), true);
    if (scene === 'realtime-voice-turn') assert.equal(await page.getByRole('button', { name: '停止本轮听写与朗读', exact: true }).isDisabled(), false);
    if (scene === 'realtime-task-recovery') assert.equal(await page.getByRole('button', { name: '恢复这项原任务视图', exact: true }).count(), 2);
    // Wait through two browser paint opportunities after fonts and state settle.
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    return { selector: selectors[scene], expectedText: expected[scene] || null, box: await target.boundingBox() };
}
const scenes = process.env.PW_DEPTH_SCENES?.split(',') || ['browser-new', 'browser-research', 'browser-handoff', 'browser-sources', 'browser-pages', 'browser-error', 'browser-review', 'chrome', 'chrome-error', 'reader', 'reader-table', 'reader-field', 'reader-error', 'bootstrap-loading', 'bootstrap-failure', 'bootstrap-recovery', 'realtime-start', 'realtime-invitation', 'realtime-live', 'realtime-transcript', 'realtime-error', 'phone-card', 'phone-sheet', 'phone-error'];
try {
    for (const width of [360, 1280]) {
        const page = await browser.newPage({ viewport: { width, height: 900 }, locale: 'zh-CN', reducedMotion: 'reduce' });
        page.setDefaultTimeout(15000);
        page.on('pageerror', e => errors.push(e.message));
        await page.goto(server.resolvedUrls.local[0] + 'tests/depth-browser.html', { waitUntil: 'networkidle' });
        for (const scene of scenes) {
            await page.evaluate(s => window.depthBrowser.scene(s), scene);
            await page.waitForTimeout(100);
            if (scene === 'browser-profiles') await page.getByRole('button', { name: '登录资料', exact: true }).click();
            if (scene === 'browser-read-scope' || scene === 'browser-action-scope') {
                await page.getByRole('button', { name: '浏览范围与执行设置', exact: true }).click();
                await page.getByRole('button', { name: scene === 'browser-read-scope' ? '授予本轮只读范围…' : '授予连续浏览范围…', exact: true }).click();
            }
            if (scene === 'browser-recipe-form') await page.getByRole('button', { name: '制作网站只读配方…', exact: true }).click();
            if (scene.startsWith('bootstrap-review-')) {
                if (scene === 'bootstrap-review-copy') await page.getByText('包含原对话文字，在本机读取并准备恢复审阅', { exact: true }).click();
                await page.getByRole('button', { name: scene === 'bootstrap-review-copy' ? '审阅恢复对话文字' : '审阅建立空库', exact: true }).click();
            }
            if (scene.startsWith('realtime-settings-')) {
                const natural = scene === 'realtime-settings-natural';
                await page.getByRole('button', { name: natural ? /语音服务与单轮听写/ : /房间通话与高级连接/ }).click();
                await page.getByRole('button', { name: natural ? '自然语音设置' : scene === 'realtime-settings-provider' ? '通话服务设置' : '手动房间设置', exact: true }).click();
            }
            if (scene === 'realtime-review') await page.getByRole('button', { name: '核对并接受邀请', exact: true }).click();
            if (scene === 'realtime-task-recovery') await page.getByRole('button', { name: /找回已保存的语音任务/ }).click();
            if (scene === 'quick-close') await page.getByRole('button', { name: '关闭听写', exact: true }).click();
            if (scene === 'quick-start-wait') await page.getByRole('button', { name: '开始系统听写', exact: true }).click();
            if (scene === 'quick-save-wait') await page.getByRole('button', { name: '追加到原浮层草稿', exact: true }).click();
            if (dark) await page.evaluate(() => document.documentElement.classList.add('dark'));
            await page.evaluate(() => document.fonts.ready);
            const assertion = await assertScene(page, scene);
            const screenshot = `${phase}-${dark ? 'dark-' : ''}${scene}-${width}.png`;
            await page.screenshot({ path: path.join(out, screenshot), fullPage: !scene.startsWith('chrome'), animations: 'disabled', timeout: 15000 });
            if (process.env.PW_DEPTH_PROGRESS) console.log(`${phase} ${width} ${scene} verified`);
            records.push({ scene, width, screenshot, assertion, overflow: await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), calls: await page.evaluate(() => window.depthBrowser.calls) });
            if (phase === 'after' && scene === 'browser-sources') {
                const search = page.getByRole('searchbox', { name: '搜索已保留来源', exact: true });
                await search.fill('计划与定价');
                assert.equal(await page.locator('.pw-browser-source-record').count(), 1);
                assert.equal(await page.locator('.pw-browser-source-record .pw-model-eyebrow').textContent(), '来源 2');
                await page.getByRole('button', { name: '查看当时原文', exact: true }).click();
                const read = await page.evaluate(() => window.depthBrowser.calls.filter(call => call.args?.command === 'openSource').at(-1));
                assert.equal(read.args.sourceId, 's2');
                assert.equal(read.args.taskId, 'research-a');
                await search.fill('不存在的来源');
                assert.equal(await page.locator('.pw-browser-source-record').count(), 0);
                await page.getByText('没有匹配的已保留来源。', { exact: true }).waitFor();
                await search.fill('');
                assert.equal(await page.locator('.pw-browser-source-record').count(), 2);
            }
        }
        if (phase === 'after' && !dark && scenes.includes('browser-action-scope')) {
            const enter = async name => { await page.evaluate(s => window.depthBrowser.scene(s), name); await page.waitForTimeout(100); };
            const calls = () => page.evaluate(() => window.depthBrowser.calls);
            await enter('browser-action-scope');
            await page.getByRole('button', { name: '浏览范围与执行设置', exact: true }).click();
            await page.getByRole('button', { name: '授予连续浏览范围…', exact: true }).click();
            await page.getByRole('textbox', { name: '允许的网站来源 · 每行一个', exact: true }).fill('https://example.test');
            await page.getByText('允许普通字段输入（可能触发联动或自动保存）', { exact: true }).click();
            assert.equal(await page.getByRole('checkbox', { name: '允许普通字段输入（可能触发联动或自动保存）', exact: true }).isChecked(), true);
            await page.getByRole('button', { name: /额外嵌入来源（可选）/ }).click();
            await page.getByRole('textbox', { name: '可选：本任务额外嵌入来源', exact: true }).fill('https://embed.example.test');
            await page.getByRole('button', { name: /额外嵌入来源（可选）/ }).click();
            await page.getByRole('button', { name: '核对实际范围', exact: true }).click();
            let sent = (await calls()).at(-1);
            assert.equal(sent.args.command, 'reviewScope');
            assert.equal(sent.args.target, 'renewActions');
            assert.equal(sent.args.options.actionOrigins, 'https://example.test');
            assert.equal(sent.args.options.extraFrameOrigins, 'https://embed.example.test');
            assert.equal(sent.args.options.ordinaryInput, true);
            await enter('realtime-settings-natural');
            await page.getByRole('button', { name: /语音服务与单轮听写/ }).click();
            await page.getByRole('button', { name: '自然语音设置', exact: true }).click();
            await page.getByRole('button', { name: /输入转录（可选）/ }).click();
            await page.getByRole('textbox', { name: '输入转录模型（可选）', exact: true }).fill('transcription-fixture');
            await page.getByRole('button', { name: /输入转录（可选）/ }).click();
            await page.getByLabel('服务认证凭据', { exact: true }).fill('fixture-only-credential');
            await page.getByRole('button', { name: '核对并保存设置', exact: true }).click();
            sent = (await calls()).at(-1);
            assert.equal(sent.action, 'realtime.reviewConfiguration');
            assert.equal(sent.args.transcriptionModel, 'transcription-fixture');
            assert.equal(sent.args.endpointUrl, 'wss://example.test/realtime');
            assert.equal(await page.getByRole('button', { name: '确认保存自然语音服务', exact: true }).isDisabled(), true);
            assert.equal((await page.getByRole('dialog', { name: '保存自然语音服务', exact: true }).textContent()).includes('fixture-only-credential'), false);
            await page.getByRole('button', { name: '返回', exact: true }).click();
            assert.equal(await page.getByLabel('服务认证凭据', { exact: true }).inputValue(), '');
            assert.equal((await calls()).some(x => x.action === 'realtime.confirm'), false);
        }
        if (phase === 'after' && !dark && scenes.some(s => s.startsWith('quick-'))) {
            const enter = async (name) => { await page.evaluate(s => window.depthBrowser.scene(s), name); await page.getByRole('heading', { name: '原浮层语音补充', exact: true }).waitFor(); };
            const calls = () => page.evaluate(() => window.depthBrowser.calls);
            await enter('quick-start');
            assert.equal((await calls()).length, 0, 'mount does not start capture');
            await page.getByRole('button', { name: '开始系统听写', exact: true }).click();
            assert.equal((await calls()).at(-1).action, 'quickVoice.start');
            assert.equal((await calls()).at(-1).args.handoffId, 'handoff-a');
            assert.equal(await page.getByRole('button', { name: '开始系统听写', exact: true }).isDisabled(), true);
            await enter('quick-recording');
            await page.getByRole('button', { name: '结束本次听写', exact: true }).click();
            assert.equal((await calls()).at(-1).action, 'voice.stop');
            await enter('quick-review');
            const text = page.getByRole('textbox', { name: '本次最终文字，可修改', exact: true });
            await text.fill('请保留周日上午的安排，姓名与数字已核对。');
            await page.getByRole('button', { name: '交接时保留的原说明', exact: true }).click();
            assert.equal(await text.inputValue(), '请保留周日上午的安排，姓名与数字已核对。');
            await page.getByRole('button', { name: '关闭听写', exact: true }).click();
            assert.equal((await calls()).length, 0, 'exit question does not discard');
            await page.getByRole('button', { name: '返回核对', exact: true }).click();
            assert.equal(await text.inputValue(), '请保留周日上午的安排，姓名与数字已核对。');
            await page.getByRole('button', { name: '追加到原浮层草稿', exact: true }).click();
            assert.deepEqual((await calls()).at(-1), { action: 'quickVoice.save', args: { handoffId: 'handoff-a', inputId: 'input-a', text: '请保留周日上午的安排，姓名与数字已核对。' } });
            assert.equal(await page.getByRole('button', { name: '追加到原浮层草稿', exact: true }).count(), 0, 'one-use save is not offered again');
            await enter('quick-unknown');
            assert.equal(await page.getByRole('button', { name: '开始系统听写', exact: true }).isDisabled(), true);
            await enter('quick-other');
            assert.equal(await page.getByRole('button', { name: '开始系统听写', exact: true }).count(), 0);
            await enter('quick-receipt');
            await page.getByRole('button', { name: '查看保存回执', exact: true }).click();
            assert.equal(await page.getByText('4 → 5', { exact: true }).isVisible(), true);
            await page.getByRole('button', { name: '完成', exact: true }).click();
            assert.equal((await calls()).at(-1).action, 'quickVoice.close');
        }
        if (phase === 'after' && !dark) {
            const scene = async (n) => { await page.evaluate(s => window.depthBrowser.scene(s), n); await page.waitForTimeout(50); };
            const calls = () => page.evaluate(() => window.depthBrowser.calls);
            await scene('browser-research');
            assert.equal((await calls()).length, 0);
            await page.getByRole('button', { name: '结束当前研究', exact: true }).click();
            let sent = (await calls()).at(-1);
            assert.equal(sent.args.command, 'stop');
            assert.equal(sent.args.snapshotId, 'frame-a');
            await scene('browser-handoff');
            await page.getByRole('button', { name: '交还当前页并继续', exact: true }).click();
            assert.equal((await calls()).at(-1).args.command, 'returnForOwner');
            await scene('browser-new');
            assert.equal(await page.getByRole('button', { name: '核对并开始研究', exact: true }).isDisabled(), true);
            await page.getByRole('button', { name: /本次范围与时限/ }).click();
            await page.getByText('30 分钟', { exact: true }).click();
            await page.getByRole('button', { name: /本次范围与时限/ }).click();
            await page.getByText('把本次网页观察交给当前模型；可以随时接管或停止。', { exact: true }).click();
            await page.getByRole('button', { name: '核对并开始研究', exact: true }).click();
            sent = (await calls()).at(-1);
            assert.equal(sent.args.command, 'reviewScope');
            assert.equal(sent.args.options.scopeMinutes, 30);
            assert.equal((await calls()).some(x => x.action.includes('start')), false);
            await scene('browser-review');
            const confirm = page.getByRole('button', { name: '确认这次操作', exact: true });
            assert.equal(await confirm.isDisabled(), true);
            await page.getByText('已核对本次完整内容与范围。', { exact: true }).click();
            await confirm.click();
            sent = (await calls()).at(-1);
            assert.equal(sent.args.reviewId, 'review-a');
            assert.equal(sent.args.command, 'decideApproval');
            assert.equal(sent.args.allow, true);
            await scene('reader-table');
            await page.getByRole('button', { name: '查看第 2 行第 2 列原字段', exact: true }).click();
            await page.getByRole('button', { name: '返回原文位置', exact: true }).click();
            assert.equal(await page.locator('[data-reader-view=source]').count(), 1);
            assert.equal((await calls()).at(-1).args.fieldRef, 'f3');
            await scene('bootstrap-failure');
            assert.equal((await calls()).length, 0);
            await page.getByRole('button', { name: '读取当前状态', exact: true }).click();
            assert.equal((await calls()).at(-1).action, 'bootstrap.read');
            assert.equal((await calls()).some(x => x.action === 'repair.confirm'), false);
            await scene('realtime-invitation');
            assert.equal(await page.getByRole('checkbox', { name: '发送本机麦克风声音', exact: true }).isChecked(), false);
            assert.deepEqual((await calls()).map(x => x.action), ['realtime.snapshot']);
            await page.getByRole('button', { name: '核对并接受邀请', exact: true }).click();
            const voiceConfirm = page.getByRole('button', { name: '确认接受并连接本次通话', exact: true });
            assert.equal(await voiceConfirm.isDisabled(), true);
            await page.getByText('我已核对本次范围，确认执行上述操作', { exact: true }).click();
            await voiceConfirm.click();
            assert.equal((await calls()).at(-1).action, 'realtime.confirm');
            assert.equal((await calls()).at(-1).args.reviewHandle, 'voice-review-a');
            await scene('realtime-live');
            await page.getByRole('button', { name: '关闭麦克风发送', exact: true }).click();
            assert.equal((await calls()).at(-1).args.command, 'mute');
            await page.getByRole('button', { name: '挂断当前通话', exact: true }).click();
            assert.equal((await calls()).at(-1).args.command, 'disconnect');
            await scene('phone-card');
            await page.getByRole('button', { name: '展开独立屏', exact: true }).click();
            await page.getByRole('button', { name: '收起独立屏', exact: true }).click();
            assert.equal((await calls()).some(x => x.action === 'phone.pause'), false);
        }
        await page.close();
    }
}
finally {
    await browser.close();
    await server.close();
    await fs.writeFile(path.join(out, process.env.PW_DEPTH_REPORT || `${phase}${dark ? '-dark' : ''}.json`), JSON.stringify({ scope: 'Actual React components with synthetic native state; no Android WebView, real call, media feed or disk claim.', records, errors }, null, 2));
    console.log(JSON.stringify({ out, count: records.length, errors, overflow: records.filter(x => x.overflow).map(x => [x.scene, x.width]) }));
}
if (errors.length)
    process.exitCode = 1;

assert.equal(records.filter(x => x.overflow).length, 0, 'document must not overflow at the tested viewport');
