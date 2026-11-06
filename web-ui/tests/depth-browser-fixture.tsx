/** Real React surfaces; synthetic native snapshots, never device evidence. */
import { useState, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import BrowserWorkspace from '../src/BrowserWorkspace';
import BrowserChrome from '../src/BrowserChrome';
import BrowserSourceReader from '../src/BrowserSourceReader';
import BootstrapWorkspace from '../src/BootstrapWorkspace';
import { RealtimeWorkspace } from '../src/RealtimeWorkspace';
import { PhoneViewerCard, PhoneViewerSheet } from '../src/phone-viewer';
import { QuickPanelVoiceDraft } from '../src/QuickPanelVoiceDraft';
import { bridge } from '../src/bridge';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';
const calls: any[] = [];
const task = { id: 'research-a', runId: 'run-a', goal: '比较远程协作工具，保留价格与数据导出依据', status: 'running', operations: [{ id: 'read-a', tool: '读取产品文档', state: 'completed' }, { id: 'read-b', tool: '核对定价页面', state: 'running' }] };
const source = { title: '导出你的团队数据', safeUrl: 'https://example.test/docs/export', excerpt: '管理员可以导出项目、任务与评论。\n\n导出文件保留原记录的创建时间；成员资料由管理员单独决定是否包含。\n\n这份文档仅说明网页当时展示的能力，实际操作仍需在原任务核对。', capturedAt: 1793962800000, sourceId: 's1', sourceFingerprint: 'fp', runId: 'run-a', epoch: 1, untrusted: true, verified: false, urlQueryOmitted: false, coverage: { kind: 'semantic', partial: true, wholePage: false, embeddedFrame: false, inlineHostPage: false, continuationAvailable: false } };
const base = { revision: 1, host: { ready: true, connected: true, bridgeAvailable: true, appearance: { mode: 'light', dark: false } }, state: {}, capabilities: { commands: ['browserWorkspace.action', 'browserChrome.action', 'browserSources.field', 'browserSources.refresh', 'browserSources.close', 'bootstrap.read', 'bootstrap.retry', 'repair.preserve', 'repair.read', 'repair.review', 'repair.confirm', 'bootstrap.received'] } };
const rtBase = { snapshotRevision: 1, sessionId: 'session-a', foregroundOwner: true, captureCoordinatorReady: true, microphonePermission: false, cameraPermission: false, naturalVoiceConfigured: true, naturalVoiceSupported: true, naturalVoiceConfiguration: { model: 'realtime-model', voice: 'calm', endpointUrl: 'wss://example.test/realtime' }, capabilities: { voiceTaskHost: true }, voiceTask: { available: true }, session: { state: 'idle' } };
function scenario(name: string): any { const snap: any = structuredClone(base); if (name.startsWith('browser-')) {
    const native: any = { profileId: 'profile-a', open: true, profiles: { profiles: [{ id: 'profile-a', label: '我的浏览资料', status: 'active', selected: true, available: true }] }, pages: { tabs: [{ tabId: 'p1', title: '产品 A · 团队协作与数据导出', url: 'https://example.test/docs/export', openedBy: 'task', selected: true }, { tabId: 'p2', title: '产品 B · 价格与计划', url: 'https://example.test/pricing', openedBy: 'owner' }], taskSpace: { status: 'active' } }, activeResearchTask: task, researchTask: { ...task, summary: '产品 A 提供数据导出；产品 B 的能力仍需核对。' }, researchControl: {}, readScope: { state: 'active', remainingReads: 12 }, taskActionScope: { state: 'active', remainingActions: 8 }, sources: [{ sourceId: 's1', taskId: 'research-a', profileId: 'profile-a', title: source.title, url: source.safeUrl, excerpt: '管理员可以导出项目、任务与评论；保留原记录的创建时间。', scopeNote: '保留了文档中的导出说明。' }, { sourceId: 's2', taskId: 'research-a', profileId: 'profile-a', title: '计划与定价', url: 'https://example.test/pricing', excerpt: '团队计划按成员计费，升级需要明确选择。' }], downloads: [{ id: 'd1', taskId: 'research-a', name: '协作工具对照.csv', status: 'downloaded', attachment: { id: 'a1' } }] };
    snap.browserWorkspace = { windowId: 'workspace-a', generation: 1, snapshotId: 'frame-a', ready: true, view: name === 'browser-pages' ? 'pages' : name === 'browser-sources' || name === 'browser-recipe-form' ? 'sources' : 'research', native, draft: { ready: true, text: task.goal, contextTaskId: 'research-a' } };
    if (name === 'browser-new') {
        native.pages.taskSpace.status = 'idle';
        native.activeResearchTask = {};
    }
    if (name === 'browser-handoff')
        native.pages.taskSpace = { status: 'owner_control', handoffReason: '请在原网页完成登录，核对后明确交还当前研究。' };
    if (name === 'browser-error')
        snap.browserWorkspace.error = '当前网页读取失败，请刷新核对同一研究。';
    if (name === 'browser-review')
        snap.browserWorkspace.review = { id: 'review-a', kind: 'approval', title: '核对这次网页提交', detail: '向当前站点提交已核对的团队设置。', request: { tool: 'submit', arguments: { source: '当前资料 / 产品 A' } } };
    if (name === 'browser-profiles') { native.profiles.multiProfileSupported = true; native.profiles.profiles.push({ id: 'profile-b', label: '团队资料', status: 'active', available: true, deletable: true }); }
    if (name === 'browser-read-scope') native.readScopeIdentity = { taskId: task.id };
    if (name === 'browser-recipe-form') native.sources[0].structuredProjection = { partial: false };
    if (name === 'browser-scope-review') snap.browserWorkspace.review = { id: 'scope-review-a', kind: 'scope', title: '核对本次浏览范围', goal: task.goal, profileLabel: '我的浏览资料', executionChoice: { toolLimit: 40, modelCallLimit: 12, deadlineAt: 0 }, read: { enabled: true, allowSourceRetention: true, publicSeeds: ['https://example.test/docs/export'] }, actions: { allowedOrigins: ['https://example.test'], maxActions: 16, ordinaryInput: false }, frames: [] };
    if (name === 'browser-recipe-review') snap.browserWorkspace.review = { id: 'recipe-review-a', kind: 'recipe', title: '核对这份网站只读配方', detail: '从当前来源保存已核对的只读字段。', recipe: { sourceId: 's1', fields: ['产品', '数据导出'], enabled: false } };
    if (name === 'browser-draft-conflict') snap.browserWorkspace.draft.conflict = { text: '另一窗口保留的研究目标：比较团队计划与导出范围。' };
    if (name === 'browser-upload-review') snap.browserWorkspace.review = { id: 'upload-review-a', kind: 'upload', title: '核对站点上传结果', detail: '请在原网页核对这次上传的实际站点回执。', data: { attemptId: 'upload-a', file: '协作工具对照.csv', state: 'unknown' } };
    return snap;
} if (name.startsWith('chrome')) {
    snap.surface = 'browser_chrome';
    snap.browserChrome = { ready: true, windowId: 'chrome-a', generation: 1, contextToken: 'page-a', address: source.safeUrl, title: source.title, status: '由你操作 · 自动浏览暂停', loading: false, progress: 0, canBack: true, canForward: false, pageCount: 2, researchAvailable: true, pageAvailable: true, navigationUnconfirmed: name === 'chrome-error', navigationReviewRevision: 1 };
    return snap;
} if (name.startsWith('reader')) {
    snap.browserSources = { windowId: 'reader-a', generation: 1, sourceFingerprint: 'fp', ready: true, loading: false, error: name === 'reader-error' ? '保存的原件暂时无法读取，请重读同一原件。' : '', source: structuredClone(source), field: null };
    if (name === 'reader-table' || name === 'reader-field') {
        snap.browserSources.source.projection = { mode: 'table', revision: 'v1', partial: false, omittedRows: 0, rows: [{ index: 0, cells: [{ column: 0, role: 'columnheader', text: '产品', fieldRef: 'f0', colSpan: 1, rowSpan: 1 }, { column: 1, role: 'columnheader', text: '数据导出', fieldRef: 'f1', colSpan: 1, rowSpan: 1 }] }, { index: 1, cells: [{ column: 0, role: 'cell', text: '产品 A', fieldRef: 'f2', colSpan: 1, rowSpan: 1 }, { column: 1, role: 'cell', text: '项目、任务与评论', fieldRef: 'f3', colSpan: 1, rowSpan: 1 }] }] };
        if (name === 'reader-field')
            snap.browserSources.field = { fieldRef: 'f3', revision: 'v1', mode: 'table', text: '项目、任务与评论', rowIndex: 1, columnIndex: 1, headers: ['产品', '数据导出'], partial: false, untrusted: true, verified: false };
    }
    return snap;
} if (name.startsWith('bootstrap')) {
    snap.bootstrap = { surface: 'bootstrap', revision: 1, generation: 7, state: name === 'bootstrap-loading' ? 'initializing' : 'failed', activity: 'opening_store', stage: 'configured', recovery: 'not_started', reason: { code: 'storage_full', message: name === 'bootstrap-loading' ? '正在核对本机安装、数据和原任务。' : '本机存储空间不足，尚未打开工作记录。', nextStep: name === 'bootstrap-loading' ? '请等待当前初始化结束。' : '释放本机存储后，重新核对并启动。请勿清除应用数据。' }, retryAvailable: name !== 'bootstrap-loading', installedVersion: '0.4.0', requiredSchema: 10, optionalUnavailable: [] };
    if (name === 'bootstrap-recovery' || name.startsWith('bootstrap-review-')) {
        snap.bootstrap.state = 'maintenance';
        snap.bootstrap.reason = { code: 'suspected_corruption', message: '本机数据读取未通过核对。先保全原件，再决定恢复方式。' };
        snap.bootstrap.maintenance = { repairId: 'repair-a', phase: 'preserved', sourceFingerprint: 'a'.repeat(64), backupFingerprint: 'a'.repeat(64), preservationVerified: true, reviewModes: ['verified_copy', 'empty_database'], allowedCommands: ['repair.read', 'repair.review'], files: [], reason: { message: '原件副本已核验。保全本身不代表历史已经恢复。' } };
    }
    return snap;
} return snap; }
function Fixture() {
    const [name, setName] = useState('browser-new'), [snap, setSnap] = useState<any>(() => scenario('browser-new')), [rt, setRt] = useState<any>(rtBase), [expanded, setExpanded] = useState(false);
    const focus = useRef<HTMLElement | null>(null);
    const controls = (window as any).depthBrowser ||= { calls };
    controls.scene = (n: string) => { calls.length = 0; setName(n); setSnap(scenario(n)); setExpanded(n === 'phone-sheet'); const next: any = structuredClone(rtBase); if (n === 'realtime-invitation' || n === 'realtime-review') {
        next.invitationState = 'awaiting_owner';
        next.invitation = { id: 'invite-a', expiresAt: Date.now() + 600000, displayName: '和我的助手通话', source: 'owner_prepared_natural_voice', model: 'realtime-model', voice: 'calm', serverUrl: 'wss://example.test/realtime' };
    } if (n === 'realtime-live' || n === 'realtime-transcript') {
        next.session = { state: 'connected', transport: 'openai_realtime_ga_websocket', microphoneEnabled: true, cameraEnabled: false };
        next.microphonePermission = true;
    } if (n === 'realtime-transcript') {
        next.transcription = { active: true, turns: [{ id: 'turn-a', revision: 1, text: '请整理今天的讨论，列出明天需要继续的三个事项。', isFinal: true, canReview: true, sourceKind: 'reported_local_microphone' }] };
        next.voiceTask = { available: true, task: { taskId: 'task-a', goal: '整理今天的讨论', status: 'awaiting_user', summary: '已保存目标，等待你明确开始。' }, canStart: true, bindingId: 'b1', viewToken: 'view-a' };
    } if (n === 'realtime-room-live') { next.session = { state: 'connected', transport: 'livekit', microphoneEnabled: false, cameraEnabled: false }; next.microphonePermission = true; }
    if (n === 'realtime-voice-turn') next.voiceTurn = { id: 'voice-turn-a', active: true, portReady: true, status: 'listening', recognizedText: '整理今天的讨论。', replyText: '请先核对最终文字。' };
    if (n === 'realtime-task-recovery') next.voiceTask.recentTasks = [{ taskId: 'saved-a', goal: '整理昨天保留的语音目标', status: 'awaiting_user' }, { taskId: 'saved-b', goal: '复核团队设置', status: 'paused' }];
    if (n === 'realtime-settings-provider') next.capabilities.endpointProviderSupported = true;
    if (n === 'realtime-error')
        next.session = { state: 'idle', resourceReleaseUnknown: true }; setRt(next); };
    (bridge as any).getState = () => snap;
    (bridge as any).getDocumentEpoch = () => 1;
    (bridge as any).command = async (action: string, args: any = {}) => { calls.push({ action, args });
    if (action === 'repair.review') {
        const view = structuredClone(snap.bootstrap); view.revision++; view.maintenance.phase = 'reviewed'; view.maintenance.allowedCommands = ['repair.read', 'repair.review', 'repair.confirm'];
        view.maintenance.review = { reviewId: 'repair-review-a', mode: args.mode, summary: '建立新空库，原历史未恢复。' };
        if (args.mode === 'verified_copy') view.maintenance.review.restoration = { section: 'conversations', sourceConversationCount: 2, conversationCount: 1, messageCount: 2, omittedCount: 1, unreadableConversationCount: 1, partial: true, contentUtf8Bytes: 400, sourceGeneration: 'fixture-backup', manifestSha256: 'c'.repeat(64), manifestBytes: 400, conversations: [{ sourceConversationId: 'source-a', restoredConversationId: 'restored-a', messageCount: 2, omittedCount: 1, exportSha256: 'd'.repeat(64), exportBytes: 400 }], gaps: [{ sourceConversationId: 'source-a', reason: 'message_deleted', recordCount: 1 }, { sourceConversationId: 'source-b', reason: 'source_integrity_unavailable', recordCount: null }], sourceFingerprint: view.maintenance.backupFingerprint, sourceReadOnly: true, restoresExistingIds: false, restoresActiveGrants: false, restoresTaskHistory: false, restoresAttachmentBytes: false, restoresCredentialsOrPairings: false, omissionMeaning: '删除记录明确省略，另一份原件未通过读取核验。' };
        return view;
    }
    if (action === 'bootstrap.read') return snap.bootstrap;
    if (action === 'browserWorkspace.action') {
        if (args.command === 'setView')
            setSnap((s: any) => ({ ...s, browserWorkspace: { ...s.browserWorkspace, view: args.view } }));
        return { accepted: true };
    } if (action === 'browserSources.field')
        setSnap((s: any) => ({ ...s, browserSources: { ...s.browserSources, field: { fieldRef: args.fieldRef, revision: 'v1', mode: 'table', text: '项目、任务与评论', rowIndex: 1, columnIndex: 1, headers: ['产品', '数据导出'], partial: false, untrusted: true, verified: false } } })); return { accepted: true }; };
    const run = async (action: string, args: any = {}) => { calls.push({ action, args });
    if (action === 'realtime.reviewConfiguration') { const { authenticationToken, participantToken, ...safe } = args; return { reviewHandle: 'voice-config-review-a', command: args.kind === 'natural' ? 'configureNaturalVoice' : args.kind === 'provider' ? 'configureEndpointProvider' : 'configure', expiresAt: Date.now() + 600000, details: { ...safe, configurationRevision: 0 } }; }
    if (action === 'realtime.reviewInvitation')
        return { reviewHandle: 'voice-review-a', command: 'acceptInvitation', expiresAt: Date.now() + 600000, details: { ...rt.invitation, microphone: args.microphone, camera: args.camera } }; return action === 'realtime.control' ? { ...rt, result: { message: '已请求原控制，请核对实际状态。' } } : rt; };
    const feed: any = { phase: name === 'phone-error' ? 'error' : 'waiting', visible: true, frame: null, verifiedAt: 0, error: name === 'phone-error' ? '原画面读取未完成，保留当前任务身份。' : '', retry: () => calls.push({ action: 'phone.retry' }) };
    const phoneTask: any = { taskId: 'phone-a', runId: 'run-a', epoch: 1, title: '核对手机上的团队设置', status: 'running' };
    const phoneControls: any = { busy: false, error: '', pause: () => calls.push({ action: 'phone.pause' }) };
    const handoff: any = { handoffId: 'handoff-a', taskId: 'phone-a', note: '对照团队设置，保存前检查姓名与日期。' };
    const input: any = { target: { conversationId: 'quick-panel:handoff-a' }, inputId: 'input-a', active: false, phase: 'idle' };
    if (name === 'quick-recording' || name === 'quick-stopping') { input.active = true; input.phase = name === 'quick-stopping' ? 'stopping' : 'recording'; }
    if (name === 'quick-review' || name === 'quick-close' || name === 'quick-save-wait') { input.reviewAvailable = true; input.finalText = '请保留周六上午的安排，确认后再发送。'; }
    if (name === 'quick-unknown') input.resourceReleaseUnknown = true;
    if (name === 'quick-other') { input.active = true; input.target.conversationId = 'conversation-other'; }
    if (name === 'quick-receipt') handoff.receipt = { saved: true, taskId: 'phone-a', runId: 'run-a', epoch: 1, previousRevision: 4, draftRevision: 5 };
    return name.startsWith('quick-') ? <QuickPanelVoiceDraft key={name} handoff={handoff} input={input} run={run}/> : name.startsWith('browser-') ? <BrowserWorkspace key={name} snapshot={snap}/> : name.startsWith('chrome') ? <BrowserChrome key={name} snapshot={snap}/> : name.startsWith('reader') ? <BrowserSourceReader key={name} snapshot={snap}/> : name.startsWith('bootstrap') ? <BootstrapWorkspace key={name} snapshot={snap}/> : name.startsWith('realtime') ? <div className="pw-app pw-redesign"><main style={{ width: '100%', maxWidth: 960, margin: '0 auto', padding: 20 }}><RealtimeWorkspace key={name} state={{ features: { realtime: rt } }} run={run} registerTransientBack={() => { }}/></main></div> : <div className="pw-phone-viewer" style={{ maxWidth: 640, margin: '24px auto', padding: 16 }}><PhoneViewerCard task={phoneTask} feed={feed} controls={phoneControls} onExpand={() => setExpanded(true)}/><PhoneViewerSheet task={phoneTask} feed={feed} controls={phoneControls} expanded={expanded} onExpandedChange={setExpanded} returnFocus={focus}/></div>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
