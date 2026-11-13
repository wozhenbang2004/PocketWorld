import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconArrowLeft, IconBook, IconFileText, IconLayers, IconRefresh } from './icons';
import { bridge, type PocketSnapshot } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, RadioOptions, SettingsDialog, SearchField, TextAreaField, TextField } from './forms-library';
import { usePocketAppearance } from './usePocketAppearance';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { browserDraftAfterWindow, browserScopeOptions, browserWorkspaceActionForFrame, browserWorkspaceContext, browserWorkspaceDraftKey, browserWorkspaceObject as object, browserWorkspacePayload, browserWorkspaceRows as rows, browserWorkspaceState, emptyBrowserScope, type BrowserScopeDraft } from './browser-workspace-contract';
import './browser-workspace.css';
import './browser-depth.css';
import './browser-workspace-manga.css';

type Data = Record<string, any>;
const value = (input: any, fallback = ''): string => typeof input === 'string' ? input : typeof input === 'number' || typeof input === 'boolean' ? String(input) : fallback;
const status = (input: any) => ({ running: '执行中', queued: '等待执行', preparing: '准备中', completed: '已完成', failed: '未完成', stopped: '已停止', interrupted: '已中断', paused: '已暂停', unknown: '结果待核对', awaiting_user: '等待你处理', needs_confirmation: '需要确认', owner_control: '由你操作', active: '进行中', downloaded: '已存入附件库', cancelled: '已停止', stopping: '正在停止' } as Data)[value(input)] || value(input, '尚未核实');
const json = (input: any) => JSON.stringify(input ?? {}, null, 2);
function ReadDetails({ data, label = '查看原记录' }: { data: any; label?: string }) { return <Accordion variant="divided"><AccordionItem id="original" title={label}><pre className="pw-browser-workspace-code">{typeof data === 'string' ? data : json(data)}</pre></AccordionItem></Accordion>; }

function ScopeChoices({ draft, setDraft, candidates, disabled, readOnly = false }: { draft: BrowserScopeDraft; setDraft: (next: BrowserScopeDraft) => void; candidates: Data[]; disabled: boolean; readOnly?: boolean }) {
  const set = <K extends keyof BrowserScopeDraft>(key: K, next: BrowserScopeDraft[K]) => setDraft({ ...draft, [key]: next });
  return <div className="pw-browser-scope-fields">
    <section className="pw-browser-scope-group"><CheckboxField isSelected={draft.autoRead} isDisabled={disabled} onChange={next => set('autoRead', next)}>本轮自动只读范围</CheckboxField>
    {draft.autoRead && <><CheckboxField isSelected={draft.saveReadSources} isDisabled={disabled} onChange={next => set('saveReadSources', next)}>保留实际已读摘录到原任务</CheckboxField><TextAreaField label="明确公共种子 URL · 最多 8 个，每行一个" value={draft.publicSeeds} isDisabled={disabled} onChange={next => set('publicSeeds', next.slice(0, 16400))} /><p className="pw-field-hint">原任务页只读及明确种子，最长 5 分钟 / 32 次 / 匿名正文累计 1 MiB。实际地址会在下一步逐条核对。</p></>}
    </section>{!readOnly && <><section className="pw-browser-scope-group"><CheckboxField isSelected={draft.autoActions} isDisabled={disabled} onChange={next => set('autoActions', next)}>在明确网站内连续完成日常浏览</CheckboxField>
      {draft.autoActions && <><TextAreaField label="允许的网站来源 · 每行一个" value={draft.actionOrigins} isDisabled={disabled} onChange={next => set('actionOrigins', next.slice(0, 4096))} /><TextField label="日常浏览次数上限 · 1–100" inputMode="numeric" value={draft.actionBudget} isDisabled={disabled} onChange={next => set('actionBudget', next.replace(/\D/g, '').slice(0, 3))} />
        <div className="pw-browser-scope-capabilities"><h4>允许的页面交互</h4><CheckboxField isSelected={draft.interactionClicks} isDisabled={disabled} onChange={next => set('interactionClicks', next)}>重复点击我指定的翻页、展开或切换控件</CheckboxField>
        <CheckboxField isSelected={draft.interactionSelections} isDisabled={disabled} onChange={next => set('interactionSelections', next)}>选择我指定的下拉控件</CheckboxField>
        <CheckboxField isSelected={draft.interactionChecks} isDisabled={disabled} onChange={next => set('interactionChecks', next)}>勾选或取消我指定的筛选控件</CheckboxField>
        {(draft.interactionClicks || draft.interactionSelections || draft.interactionChecks) && <><p className="pw-field-hint">选择当前文档实际观察过的控件。刷新或跳转后重新核对，页面处理器仍可能联动或自动保存。</p>{!candidates.length && <p className="pw-field-hint">当前尚无已观察控件，请先让原任务读取网页。</p>}{candidates.map((candidate, index) => { const key = json(candidate), pin = object(candidate.pin), preview = object(pin.targetPreview); return <CheckboxField key={index} isDisabled={disabled} isSelected={draft.selectedInteractionCandidates.some(item => json(item) === key)} onChange={selected => set('selectedInteractionCandidates', selected ? [...draft.selectedInteractionCandidates, candidate] : draft.selectedInteractionCandidates.filter(item => json(item) !== key))}>{value(preview.text) || value(preview.id, '原控件')} · {value(pin.pageUrl).split(/[?#]/)[0]}</CheckboxField>; })}<Accordion variant="divided"><AccordionItem id="manual-targets" title="高级：跨文档的明确控件边界"><TextAreaField label="完整控件名称或 #id · 每行一个" value={draft.interactionTargets} isDisabled={disabled} onChange={next => set('interactionTargets', next.slice(0, 4096))} /><p className="pw-field-hint">只授权明确指定的控件。发送、提交、删除、付款及已知敏感控件仍需单独确认。</p></AccordionItem></Accordion></>}
        <CheckboxField isSelected={draft.ordinaryInput} isDisabled={disabled} onChange={next => set('ordinaryInput', next)}>允许普通字段输入（可能触发联动或自动保存）</CheckboxField></div>
      </>}
      </section><Accordion variant="divided"><AccordionItem id="extra-frame-origins" title="额外嵌入来源（可选）" description={draft.extraFrameOrigins.trim() ? '已填写额外来源，下一步逐条核对' : '默认只读顶层同源'}><TextAreaField label="可选：本任务额外嵌入来源" value={draft.extraFrameOrigins} isDisabled={disabled} onChange={next => set('extraFrameOrigins', next.slice(0, 4200))} /><p className="pw-field-hint">默认只读顶层同源。每行一个完整 origin，最多 8 个，不支持通配。</p></AccordionItem></Accordion>
    </>}
  </div>;
}

function ScopeReview({ review }: { review: Data }) {
  const read = object(review.read), actions = object(review.actions), execution = object(review.executionChoice);
  const stringRows = (values: any) => Array.isArray(values) ? values : [];
  return <><p>{value(review.goal)}</p><p className="pw-field-hint">登录资料：{value(review.profileLabel, '原资料')}</p>
    {execution.toolLimit != null && <p>原任务累计最多 {execution.toolLimit} 次工具 / {execution.modelCallLimit} 次模型；{execution.deadlineAt === 0 ? '至完成、停止或预算用完。' : `到期 ${new Date(execution.deadlineAt).toLocaleString()}。`}</p>}
    {read.enabled === true && <><h3>只读范围</h3><p>最长 5 分钟、最多 32 次，匿名正文累计最多 1 MiB；暂停、接管或资料变化后须重新授权。</p><p>{read.allowSourceRetention === true ? '保留实际已读摘录。' : '来源保留仍逐次确认。'}</p><ul>{stringRows(read.publicSeeds).map((url: string, index: number) => <li key={index}>{url}</li>)}</ul></>}
    {Array.isArray(actions.allowedOrigins) && actions.allowedOrigins.length > 0 && <><h3>连续浏览范围</h3><p>最多 {actions.maxActions} 次，仍受原任务期限与累计预算限制。</p><ul>{actions.allowedOrigins.map((origin: string, index: number) => <li key={index}>{origin}</li>)}</ul><p>{actions.ordinaryInput ? '包含普通字段输入，可能触发页面联动或自动保存。' : '普通输入仍逐次确认。'}</p>{stringRows(actions.interactionTargets).map((target: string, index: number) => <p key={index}>指定控件：{target}</p>)}{rows(actions.observedTargets).map((target, index) => <p key={index}>当前文档控件：{value(object(object(target.pin).targetPreview).text) || value(object(object(target.pin).targetPreview).id)}</p>)}</>}
    {Array.isArray(review.frames) && review.frames.length > 0 && <><h3>额外嵌入来源</h3><ul>{review.frames.map((origin: string, index: number) => <li key={index}>{origin}</li>)}</ul></>}
    <p className="pw-field-hint">发送、提交、删除、付款、页面脚本、下载和越界操作仍需精确审批。未知结果不会自动重发。</p><ReadDetails data={{ read, actions, frames: review.frames, execution }} label="完整授权范围" />
  </>;
}

/** Browser research is another route in the same trusted bundle, alongside its existing toolbar and source reader. */
export default function BrowserWorkspace({ snapshot }: { snapshot: PocketSnapshot }) {
  usePocketAppearance(snapshot.host?.appearance);
  const state = browserWorkspaceState(snapshot), native = object(state?.native), draft = object(state?.draft);
  const epoch = bridge.getDocumentEpoch(), context = browserWorkspaceContext(state, epoch);
  const [goal, setGoal] = useState(value(draft.text)), [scope, setScope] = useState(emptyBrowserScope), [consent, setConsent] = useState(false);
  const [pending, setPending] = useState(''), [error, setError] = useState(''), [editing, setEditing] = useState(false);
  const [profilesOpen, setProfilesOpen] = useState(false), [profileLabel, setProfileLabel] = useState(''), [sourceQuery, setSourceQuery] = useState('');
  const [scopeTarget, setScopeTarget] = useState<'renew' | 'renewActions' | null>(null), [recipeSource, setRecipeSource] = useState<Data | null>(null), [recipeQuery, setRecipeQuery] = useState('');
  const [acknowledge, setAcknowledge] = useState(false), [recipeEnabled, setRecipeEnabled] = useState(false), [uploadNote, setUploadNote] = useState('');
  const [suspended, setSuspended] = useState(document.visibilityState !== 'visible');
  const alive = useRef(true), sequence = useRef(0), editingSequence = useRef(0), dirty = useRef(false), textDraft = useRef(goal), previousContext = useRef(context), lastEpoch = useRef(epoch);
  const editTail = useRef<Promise<void>>(Promise.resolve()), priorDraftKey = useRef(browserWorkspaceDraftKey(state));
  const inFlight = useRef<{ id: number; context: string } | null>(null), goalInFlight = useRef<object | null>(null);
  const draftKey = browserWorkspaceDraftKey(state);
  const live = useRef({ state, context, suspended }); live.current = { state, context, suspended };
  const actionForFrame = browserWorkspaceActionForFrame(state, epoch, () => {
    const actual = bridge.getState();
    return { state: browserWorkspaceState(actual), epoch: bridge.getDocumentEpoch(), mounted: alive.current,
      visible: document.visibilityState === 'visible' && !live.current.suspended,
      connected: actual.host?.ready === true && actual.capabilities.commands.includes('browserWorkspace.action'), inFlight: inFlight.current !== null || goalInFlight.current !== null };
  });
  const connected = !!state?.ready && snapshot.host?.ready === true && snapshot.capabilities.commands.includes('browserWorkspace.action') && !suspended;
  const busy = !!pending || state?.busy === true;
  const profiles = object(native.profiles), pages = rows(object(native.pages).tabs), space = object(object(native.pages).taskSpace), active = object(native.activeResearchTask), viewed = object(native.researchTask), control = object(native.researchControl);
  const running = ['active', 'owner_control'].includes(space.status), review = object(state?.review), reviewId = value(review.id);
  const currentView = ['research', 'sources', 'pages'].includes(value(state?.view)) ? value(state?.view) : 'research';
  const currentProfile = rows(profiles.profiles).find(row => row.id === native.profileId);
  const content = useRef<HTMLDivElement>(null);
  useSurfaceEnter(content, `${state?.windowId || ''}:${native.profileId || ''}:${currentView}`, { selector: '', active: connected });
  const approvals = rows(active.approvals).filter(row => row.state === 'pending' && row.runId === active.runId && row.expiresAt > Date.now());
  const candidates = rows(space.interactionCandidates), sources = rows(native.sources), downloads = rows(native.downloads), readScope = object(native.readScope), actions = object(native.taskActionScope);
  const matchingSources = sources.map((source, index) => ({source, index})).filter(({source}) => [source.title, source.url, source.displayUrl, source.excerpt].map(item => value(item)).join(' ').toLocaleLowerCase().includes(sourceQuery.trim().toLocaleLowerCase()));
  useCollectionMotion(content, JSON.stringify([context, currentView, viewed.id, sourceQuery.trim()]),
    JSON.stringify([pages.map(tab => [tab.tabId, tab.openedBy]), matchingSources.map(({ source }) => source.sourceId), downloads.map(item => [item.id, item.status]), approvals.map(item => item.id), rows(active.operations).slice(-40).map(item => [item.id, item.state, item.status]), !!viewed.summary || !!object(viewed.evidence).modelSummary]),
    { active: connected && !state?.snapshotError });
  useEffect(() => { setSourceQuery(''); }, [context]);

  useEffect(() => {
    alive.current = true; document.documentElement.dataset.pocketUi = 'ready';
    const visibility = () => { const hidden = document.visibilityState !== 'visible'; setSuspended(hidden); if (hidden) { sequence.current++; setConsent(false); setAcknowledge(false); setScope(emptyBrowserScope()); setScopeTarget(null); setRecipeSource(null); setUploadNote(''); } };
    document.addEventListener('visibilitychange', visibility);
    return () => { alive.current = false; sequence.current++; document.removeEventListener('visibilitychange', visibility); delete document.documentElement.dataset.pocketUi; };
  }, []);
  useLayoutEffect(() => {
    if (lastEpoch.current === epoch) return;
    lastEpoch.current = epoch; sequence.current++; setPending(''); setConsent(false); setScope(emptyBrowserScope());
    void bridge.bindHostDocument().then(() => { if (alive.current && bridge.getDocumentEpoch() === epoch) setSuspended(document.visibilityState !== 'visible'); }).catch(failure => { if (alive.current) setError(failure instanceof Error ? failure.message : '工作区尚未重新连接'); });
  }, [epoch]);
  useLayoutEffect(() => {
    if (previousContext.current !== context || draftKey && priorDraftKey.current !== draftKey) {
      const retained = browserDraftAfterWindow({ key: priorDraftKey.current, text: textDraft.current, dirty: dirty.current }, state);
      previousContext.current = context; priorDraftKey.current = retained.key; sequence.current++; editingSequence.current++; inFlight.current = null; goalInFlight.current = null; dirty.current = retained.dirty;
      textDraft.current = retained.text; setGoal(retained.text); setEditing(false); setPending(''); setError(retained.dirty ? '当前输入尚未取得保存回执，文字仍保留。请保存当前输入后继续。' : '');
      setConsent(false); setScope(emptyBrowserScope()); setScopeTarget(null); setRecipeSource(null); setProfilesOpen(false);
    } else if (!dirty.current && !editing) { textDraft.current = value(draft.text); setGoal(textDraft.current); }
  }, [context, draftKey, draft.text, editing]);
  useEffect(() => { setAcknowledge(false); setRecipeEnabled(false); setUploadNote(''); }, [reviewId]);

  async function act(command: string, fields: Data = {}) {
    let request: { id: number; context: string } | null = null;
    try {
      const payload = actionForFrame(command, fields);
      request = { id: ++sequence.current, context }; inFlight.current = request;
      setPending(command); setError('');
      return await bridge.command('browserWorkspace.action', payload);
    } catch (failure) { if (alive.current && live.current.context === context && (!request || sequence.current === request.id)) setError(failure instanceof Error ? failure.message : '操作结果尚未确认，请刷新原窗口。'); }
    finally {
      // Backgrounding invalidates result rendering, not cleanup owned by this request.
      if (request && inFlight.current === request) { inFlight.current = null; if (alive.current && live.current.context === context) setPending(''); }
    }
  }
  const sameDraftWindow = () => {
    const snapshot = bridge.getState(), actual = browserWorkspaceState(snapshot);
    return alive.current && snapshot.host?.ready === true && snapshot.capabilities.commands.includes('browserWorkspace.action') && actual?.ready === true && actual.draft.ready === true &&
      context !== '' && context === browserWorkspaceContext(actual, bridge.getDocumentEpoch()) &&
      draftKey !== '' && draftKey === browserWorkspaceDraftKey(actual) && !live.current.suspended && document.visibilityState === 'visible';
  };
  async function saveGoal() {
    if (!sameDraftWindow()) return;
    if (!dirty.current) return editTail.current;
    const originalContext = context, text = textDraft.current, edit = ++editingSequence.current;
    setEditing(true);
    const save = async () => {
      if (!sameDraftWindow() || editingSequence.current !== edit) { if (alive.current && live.current.context === originalContext && editingSequence.current === edit) setEditing(false); return; }
      const request = {};
      try {
        if (inFlight.current) throw new Error('请等待本次原操作的受理回执，当前文字仍保留。');
        goalInFlight.current = request;
        await bridge.command('browserWorkspace.action', browserWorkspacePayload(state, 'editGoal', { text }));
        if (sameDraftWindow() && editingSequence.current === edit) dirty.current = false;
      } catch (failure) { if (alive.current && live.current.context === originalContext) setError(failure instanceof Error ? failure.message : '草稿保存尚未确认，当前输入仍保留。'); }
      finally { if (goalInFlight.current === request) goalInFlight.current = null; if (alive.current && live.current.context === originalContext && editingSequence.current === edit) setEditing(false); }
    };
    editTail.current = editTail.current.then(save, save); return editTail.current;
  }
  function editGoal(text: string) { if (!connected || !draft.ready || !sameDraftWindow()) return; textDraft.current = text.slice(0, 4000); dirty.current = true; setGoal(textDraft.current); void saveGoal(); }
  async function returnToPage() { if (dirty.current) { if (!sameDraftWindow()) return; await saveGoal(); if (dirty.current || !sameDraftWindow()) return; } await act('backToPage'); }
  useEffect(() => bridge.setBackHandler(() => {
    if (reviewId) { void act('dismissReview'); return true; }
    if (draft.conflict) { void act('dismissDraftConflict'); return true; }
    if (scopeTarget) { setScopeTarget(null); return true; }
    if (recipeSource) { setRecipeSource(null); return true; }
    if (profilesOpen) { setProfilesOpen(false); return true; }
    void returnToPage(); return true;
  }), [reviewId, draft.conflict, scopeTarget, recipeSource, profilesOpen, context, state?.snapshotId]);
  const button = (command: string, label: string, fields: Data = {}, disabled = false, primary = false) => <Button variant={primary ? 'primary' : 'secondary'} disabled={!connected || busy || disabled} onClick={() => { void act(command, fields); }}>{label}</Button>;
  const reviewScope = async (target: 'start' | 'existing' | 'renew' | 'renewActions', taskId?: string) => { if (!sameDraftWindow()) return; await saveGoal(); if (dirty.current || !connected || !sameDraftWindow()) return; await act('reviewScope', { target, ...(taskId ? { taskId } : {}), consent: true, options: browserScopeOptions(scope) }); };
  const draftLocked = !draft.ready || editing || dirty.current || busy;
  const sourceButtons = (record: Data, taskId: string) => <div className="pw-form-actions">{(Array.isArray(record.sourceIds) ? record.sourceIds : []).map((id: string) => <Button variant="ghost" key={id} disabled={!connected || busy} onClick={() => { void act('openSource', { taskId, sourceId: id }); }}>查看依据</Button>)}{(Array.isArray(record.fieldRefs) ? record.fieldRefs : []).map((ref: string) => <Button variant="ghost" key={ref} disabled={!connected || busy} onClick={() => { void act('openField', { taskId, fieldRef: ref }); }}>查看当时字段</Button>)}</div>;

  return <main className="pw-browser-workspace">
    <header className="pw-browser-workspace-header"><IconButton aria-label="返回网页" icon={IconArrowLeft} onClick={() => { void returnToPage(); }} disabled={!connected || editing} /><div><h1>浏览与研究</h1><p>{value(currentProfile?.label, '独立登录资料')}</p></div><Button variant="ghost" disabled={!connected || busy} onClick={() => { const originalFrame = state?.snapshotId; void saveGoal().then(() => { const actual = browserWorkspaceState(bridge.getState()); if (alive.current && !dirty.current && context === browserWorkspaceContext(actual, bridge.getDocumentEpoch()) && originalFrame === actual?.snapshotId) setProfilesOpen(true); }); }}>登录资料</Button><IconButton icon={IconRefresh} aria-label="刷新" disabled={!connected || busy} onClick={() => { void act('refresh'); }}/></header>
    <div className="pw-browser-workspace-body"><nav className="pw-browser-workspace-nav" aria-label="浏览工作区内容">{[{ id: 'research', label: '研究', Icon: IconBook }, { id: 'sources', label: '来源', Icon: IconFileText }, { id: 'pages', label: '页面', Icon: IconLayers }].map(({ id, label, Icon }) => <Button key={id} variant="ghost" leadingIcon={Icon} aria-current={currentView === id ? 'page' : undefined} disabled={!connected || busy} onClick={() => { void act('setView', { view: id }); }}>{label}</Button>)}</nav>
      <div ref={content} className="pw-browser-workspace-scroll" key={currentView} data-browser-view={currentView}>
        <MotionFeedback identity={JSON.stringify([connected, error, state?.error, draft.error, state?.snapshotError, pending, state?.busy])} initial={false}>
        {!connected && <p className="pw-field-hint" role="status">正在连接原浏览工作区…</p>}
        {(error || state?.error || draft.error) && <p className="pw-field-error" role="alert">{error || value(state?.error) || value(draft.error)}</p>}
        {state?.snapshotError && <p className="pw-field-error">上次已知状态；请刷新核对当前任务。</p>}
        {busy && <p className="pw-field-hint" role="status">正在处理原操作，请等待实际回执…</p>}
        </MotionFeedback>
        {approvals.length > 0 && <section className="pw-browser-attention"><h2>需要你的确认 · {approvals.length} 项</h2><p>核对原网页、内容与操作范围后再决定。</p>{approvals.map(request => <div key={request.id} data-motion-key={`approval:${request.id}`}>{button('reviewApproval', value(request.title) || value(request.tool, '核对这次操作'), { requestId: request.id }, false, true)}</div>)}</section>}

        {currentView === 'research' && <>
          {native.entryTask?.id && <section><h2>{value(native.entryTask.goal, '原任务')}</h2>{button('returnToTask', '回原任务', { taskId: native.entryTask.id })}</section>}
          {control.unresolved && <section className="pw-browser-attention"><h2>结果尚未核实</h2><p>请先核对原操作，保留本次结果后再继续。</p>{button('returnToTask', '回原任务核对', { taskId: active.id }, !active.id)}</section>}
          {control.cancelRequested && <p className="pw-field-hint" role="status">已请求停止，正在等待原任务结束与页面资源释放。</p>}
          {running ? <section className="pw-browser-current"><p className="pw-model-eyebrow">当前研究</p><h2>{value(active.goal, '原浏览任务')}</h2><p className="pw-browser-current-status" role="status"><MotionFeedback as="span" identity={JSON.stringify([active.id, space.status, object(native.pageDialog).status, active.status])} initial={false}>{space.status === 'owner_control' ? '由你操作 · 自动浏览暂停' : object(native.pageDialog).status === 'pending' ? '网页提示等你处理' : status(active.status)}</MotionFeedback></p>
            {space.status === 'owner_control' && <><p>{value(space.handoffReason, '在网页中完成登录或检查结果，再明确交还原任务。')}</p>{button('returnForOwner', '交还当前页并继续', {}, control.unresolved || control.cancelRequested || native.fileChooserPending || native.uploadInProgress || object(native.pageDialog).status === 'pending', true)}</>}
            {object(native.pageDialog).status === 'pending' && button('showPagePrompt', '查看网页提示')}
            <div className="pw-browser-action-rail"><div className="pw-form-actions"><Button variant="secondary" disabled={!connected || control.cancelRequested || pending === 'stop'} onClick={() => { void act('stop'); }}>结束当前研究</Button></div></div>{rows(active.operations).length > 0 && <MotionFeedback identity={JSON.stringify([active.id, rows(active.operations).at(-1)?.id, rows(active.operations).at(-1)?.state, rows(active.operations).at(-1)?.status])} className="pw-browser-current-activity"><span className="pw-model-eyebrow">最近活动</span><p>{value(rows(active.operations).at(-1)?.tool || rows(active.operations).at(-1)?.name, '原调用')}</p><span>{status(rows(active.operations).at(-1)?.state || rows(active.operations).at(-1)?.status)}</span></MotionFeedback>}<Accordion variant="divided"><AccordionItem id="activity" title="执行活动">{rows(active.operations).length === 0 ? <p>尚无工具调用记录。</p> : rows(active.operations).slice(-40).map((record, index) => <section className="pw-browser-record" key={record.id || index} data-motion-key={record.id ? `activity:${record.id}` : undefined}><h3>{value(record.tool || record.name, '原调用')}</h3><p>{status(record.state || record.status)}</p><ReadDetails data={record} label="原参数与回执" /></section>)}</AccordionItem>
              <AccordionItem id="scopes" title="浏览范围与执行设置"><p>连续浏览：{status(actions.state)}{typeof actions.remainingActions === 'number' ? ` · 余 ${actions.remainingActions} 次` : ''}</p>{actions.state === 'active' && button('revokeTaskScope', '撤销连续浏览范围')}<Button variant="secondary" disabled={!connected || busy || control.unresolved && !control.scopeConfigurationWhilePending} onClick={() => { setScope({ ...emptyBrowserScope(), autoActions: true }); setScopeTarget('renewActions'); }}>授予连续浏览范围…</Button><p>只读范围：{status(readScope.state)}{typeof readScope.remainingReads === 'number' ? ` · 余 ${readScope.remainingReads} 次` : ''}</p>{['active', 'exhausted'].includes(readScope.state) && button('revokeReadScope', '撤销只读范围')}<Button variant="secondary" disabled={!connected || busy || object(native.readScopeIdentity).taskId !== active.id} onClick={() => { setScope(emptyBrowserScope()); setScopeTarget('renew'); }}>授予本轮只读范围…</Button></AccordionItem></Accordion>

          </section> : native.pendingBrowserTask?.id ? <section><h2>等待原任务接入网页</h2><p>{value(native.pendingBrowserTask.goal)}</p><p className="pw-field-hint">沿用原任务和执行控制，当前请求结束后取得本资料的任务页。</p>{button('stop', '停止此浏览请求')}</section> : <section>
            <h2>新建研究</h2>{native.model?.model && <p className="pw-field-hint">模型：{native.model.model}</p>}
            <TextAreaField label="研究目标" value={goal} isDisabled={!connected || busy || !draft.ready} onChange={editGoal} rows={4} />
            <p className="pw-field-hint" role="status">{!draft.ready ? '正在读取原文字草稿…' : editing || dirty.current || draft.saving ? '正在保存本机文字草稿…' : draft.savedAt > 0 ? '文字草稿已保存。开始研究前需重新确认本次范围。' : '只保存文字，授权与登录凭据不会随草稿恢复。'}</p>
            {dirty.current && !editing && <Button variant="secondary" disabled={!connected || busy || !draft.ready} onClick={() => { void saveGoal(); }}>保存当前输入</Button>}
            {draft.error && button(draft.ready ? 'reviewDraftConflict' : 'retryDraft', draft.ready ? '核对已保存草稿' : '重新读取原草稿')}{draft.ready && goal && button('clearGoal', '清空文字草稿', {}, draftLocked)}
            <Accordion variant="divided" className="pw-browser-task-options"><AccordionItem id="new-scope" title="本次范围与时限" description={scope.scopeMinutes ? `${scope.scopeMinutes} 分钟 · 额外范围需单独核对` : '完成、停止或预算用完 · 额外范围需单独核对'}>
              <RadioOptions label="本次任务时限" value={String(scope.scopeMinutes)} isDisabled={busy} onChange={next => setScope({ ...scope, scopeMinutes: Number(next) })} options={[{ id: '0', label: '完成、停止或预算用完' }, { id: '30', label: '30 分钟' }, { id: '120', label: '2 小时' }]} />
              <h3>可选连续浏览</h3><ScopeChoices draft={scope} setDraft={setScope} candidates={candidates} disabled={busy || !connected} />
            </AccordionItem></Accordion>
            <CheckboxField isSelected={consent} isDisabled={!connected || busy} onChange={setConsent}>把本次网页观察交给当前模型；可以随时接管或停止。</CheckboxField>
            <div className="pw-form-actions"><Button disabled={!connected || draftLocked || !consent || !goal.trim() || native.open !== true} onClick={() => { void reviewScope('start'); }}>{native.entryTask?.id ? '核对并另开研究' : '核对并开始研究'}</Button></div>
            {rows(native.currentAssistantTasks).length > 0 && <Accordion variant="divided"><AccordionItem id="existing-tasks" title="为正在进行的原任务启用网页">{rows(native.currentAssistantTasks).map(task => <div className="pw-browser-record" key={task.taskId}><p>{task.goal}</p><Button variant="secondary" disabled={!connected || draftLocked || !consent} onClick={() => { void reviewScope('existing', task.taskId); }}>核对这个原任务的网页范围</Button></div>)}</AccordionItem></Accordion>}
          </section>}
          {rows(viewed.browserUploadAttempts).length > 0 && <Accordion variant="divided"><AccordionItem id="uploads" title="上传尝试">{rows(viewed.browserUploadAttempts).map(attempt => <section className="pw-browser-record" key={attempt.attemptId}><p>{rows(object(attempt.pin).attachments).map(file => value(file.name)).join('、')}</p><p>本地：{status(attempt.deliveryState)} · 站点：{status(attempt.siteState)}</p><div className="pw-form-actions">{button('reviewUpload', '核对站点结果', { attemptId: attempt.attemptId })}{button('cancelUpload', '尝试停止原上传', { attemptId: attempt.attemptId })}</div></section>)}</AccordionItem></Accordion>}
        </>}

        {currentView === 'pages' && <section className="pw-browser-page-collection"><h2>浏览页面 · {pages.length}</h2><p className="pw-field-hint">任务页面接管后自动浏览暂停；我的页面由你明确选择是否交给任务。</p>{!pages.length && <p>当前没有页面。</p>}{[{id:'task',label:'本任务页面',items:pages.filter(tab=>tab.openedBy==='task')},{id:'owner',label:'我的页面',items:pages.filter(tab=>tab.openedBy!=='task')}].filter(group=>group.items.length>0).map(group=><section className="pw-browser-page-group" key={group.id} aria-label={group.label}><h3 className="pw-browser-group-label">{group.label}<span>{group.items.length}</span></h3>{group.items.map(tab => <article className="pw-browser-record pw-browser-page-record" key={tab.tabId} data-motion-key={`page:${tab.tabId}`} data-selected={tab.selected || undefined}><div className="pw-browser-page-title"><h3>{value(tab.title, '未命名页面')}</h3>{tab.selected&&<span className="pw-browser-current-badge">当前</span>}</div><p className="pw-field-hint">{value(tab.url)}</p><div className="pw-form-actions">{button('selectPage', tab.openedBy === 'task' ? '接管保留' : '查看', { tabId: tab.tabId }, false, true)}{tab.openedBy === 'owner' && space.status === 'active' && button('adoptPage', '交给当前任务', { tabId: tab.tabId }, control.unresolved === true)}{tab.openedBy === 'owner' && button('closePage', '关闭页面', { tabId: tab.tabId })}</div></article>)}</section>)}</section>}

        {currentView === 'sources' && <section><div className="pw-browser-section-heading"><h2>保留的依据 · {sources.length}</h2>{button('readHistory', '研究记录')}</div>{viewed.goal && <h3>{viewed.goal}</h3>}
          {(viewed.summary || object(viewed.evidence).modelSummary) && <section className="pw-browser-conclusion" data-motion-key={`conclusion:${viewed.id}`}><h3>研究结论</h3><p className="pw-field-hint">模型整理，尚未独立核实。依据可回看当时保存的原文。</p>{rows(object(native.researchResult).blocks).length ? rows(object(native.researchResult).blocks).map((block, index) => block.kind === 'table' ? <div className="pw-browser-table-wrap" key={index}><table><thead><tr>{(Array.isArray(block.headers) ? block.headers : []).map((header: string, i: number) => <th key={i}>{header}</th>)}</tr></thead><tbody>{(Array.isArray(block.rows) ? block.rows : []).map((row: Data[], ri: number) => <tr key={ri}>{row.map((cell, ci) => <td key={ci}>{value(cell.text)}{sourceButtons(cell, viewed.id)}</td>)}</tr>)}</tbody></table></div> : <div key={index}><p className="pw-reading-copy">{value(block.text)}</p>{sourceButtons(block, viewed.id)}</div>) : <p className="pw-reading-copy">{value(object(viewed.evidence).modelSummary) || value(viewed.summary)}</p>}<ReadDetails data={value(object(viewed.evidence).modelSummary) || value(viewed.summary)} label="完整原回答" /></section>}
          {rows(object(native.researchPages).pages).length > 0 && <Accordion variant="divided"><AccordionItem id="saved-pages" title="研究页面与恢复"><p className="pw-field-hint">保存的是原页面位置。重开后重新读取，不恢复旧表单或授权。</p>{rows(object(native.researchPages).pages).map(saved => <div className="pw-browser-record" key={saved.logicalPageId}><h3>{value(saved.title) || value(saved.url, '地址未保存的页面')}</h3><p>{status(saved.state)}</p>{button('reopenPage', '重开页面', { logicalPageId: saved.logicalPageId }, !saved.url || saved.urlQueryOmitted && !saved.privateNavigationId || native.profileId !== object(native.researchPages).profileId)}{saved.urlQueryOmitted && !saved.privateNavigationId && <p className="pw-field-hint">原地址参数未保存，请手动重新选择。</p>}</div>)}{native.researchRecoveryAvailable && button('reviewRecovery', '恢复这个研究任务…', {}, false, true)}{native.researchRecoveryUnresolved && button('returnToTask', '回原任务核对上次结果', { taskId: viewed.id })}</AccordionItem></Accordion>}
          {!sources.length && <p className="pw-field-hint">尚无保存的来源。读取后可保留原文摘录与出处。</p>}
          {sources.length>0&&<div className="pw-browser-source-tools"><SearchField aria-label="搜索已保留来源" placeholder="搜索标题、网址或摘录" value={sourceQuery} onChange={setSourceQuery}/>{sourceQuery&&<p className="pw-field-hint">{matchingSources.length} 条匹配来源</p>}</div>}
          {sources.length>0&&matchingSources.length===0&&<p className="pw-field-hint" role="status">没有匹配的已保留来源。</p>}
          {matchingSources.map(({source, index}) => <article className="pw-browser-record pw-browser-source-record" key={source.sourceId} data-motion-key={`source:${source.sourceId}`}><p className="pw-model-eyebrow">来源 {index + 1}</p><h3>{value(source.title, '网页来源')}</h3><p className="pw-field-hint">{value(source.url) || value(source.displayUrl)}</p><p className="pw-reading-copy">{value(source.excerpt)}</p>{source.profileId !== native.profileId && <p className="pw-field-hint">来自另一个登录资料，当时原文仍可回查。</p>}{source.scopeNote && <p className="pw-field-hint">{value(source.scopeNote)}</p>}<div className="pw-form-actions">{button('openSource', '查看当时原文', { taskId: source.taskId, sourceId: source.sourceId }, false, true)}{button('openSourcePage', '另看现网页', { taskId: source.taskId, sourceId: source.sourceId })}{source.structuredProjection && source.structuredProjection.partial === false && <Button variant="ghost" disabled={!connected || busy} onClick={() => { setRecipeSource(source); setRecipeQuery(''); }}>制作网站只读配方…</Button>}</div></article>)}
          {downloads.length > 0 && <section className="pw-browser-downloads"><h3>下载资料</h3>{downloads.map(download => <div className="pw-browser-record" key={download.id} data-motion-key={`download:${download.id}`}><p>{value(download.name) || value(download.displayName, '文件')} · {status(download.status)}</p>{download.attachment && download.status === 'downloaded' && download.taskId === viewed.id && button('openDownload', '打开已保存文件', { taskId: viewed.id, downloadId: download.id })}</div>)}</section>}
          {Array.isArray(state?.history) && <section><h3>研究记录</h3>{state.history.length === 0 ? <p>还没有研究记录。</p> : state.history.map((record: Data) => <article className="pw-browser-record" key={record.taskId}><h3>{record.goal}</h3><p>{status(record.status)}</p>{button('selectHistory', '查看原记录', { taskId: record.taskId })}</article>)}</section>}
        </section>}
      </div>
    </div>

    <SettingsDialog isOpen={profilesOpen && !reviewId} title="登录资料" onClose={() => setProfilesOpen(false)}><p>网页与任务使用所选登录身份。</p>{rows(profiles.profiles).filter(profile => profile.status === 'active').map(profile => <section className="pw-browser-record" key={profile.id}><h3>{profile.label}{profile.selected ? ' · 当前' : ''}</h3><div className="pw-form-actions">{button('reviewProfile', '切换资料…', { profileId: profile.id, operation: 'switch' }, !profile.available)}{profile.deletable && button('reviewProfile', '删除资料…', { profileId: profile.id, operation: 'delete' })}</div></section>)}{profiles.multiProfileSupported && <><TextField label="新登录资料名称" value={profileLabel} isDisabled={busy} onChange={next => setProfileLabel(next.slice(0, 40))} />{button('createProfile', '创建资料', { label: profileLabel }, !profileLabel.trim())}</>}</SettingsDialog>
    <SettingsDialog isOpen={scopeTarget !== null && !reviewId} title={scopeTarget === 'renew' ? '本轮只读范围' : '当前任务的连续浏览'} onClose={() => setScopeTarget(null)}><ScopeChoices draft={scope} setDraft={setScope} candidates={candidates} disabled={busy || !connected} readOnly={scopeTarget === 'renew'} /><p className="pw-field-hint">只调整当前执行范围，累计任务预算不增加，旧未知效果不会重发。</p><Button disabled={!connected || busy || (scopeTarget === 'renew' ? !scope.autoRead : !scope.autoActions)} onClick={() => { if (scopeTarget) void reviewScope(scopeTarget); }}>核对实际范围</Button></SettingsDialog>
    <SettingsDialog isOpen={recipeSource !== null && !reviewId} title="制作网站只读配方" onClose={() => setRecipeSource(null)}><p>按同一网页结构整理字段；页面变化后回普通读取。</p><p className="pw-field-hint">{value(recipeSource?.url)}</p><TextField label="区域识别词，如表格标题" value={recipeQuery} isDisabled={busy} onChange={next => setRecipeQuery(next.slice(0, 160))} /><Button disabled={!connected || busy || !recipeSource || !recipeQuery.trim()} onClick={() => { if (recipeSource) void act('prepareRecipe', { taskId: recipeSource.taskId, sourceId: recipeSource.sourceId, query: recipeQuery }); }}>核对读取字段</Button></SettingsDialog>
    <SettingsDialog isOpen={!!draft.conflict} title="核对这个原研究草稿" onClose={() => { void act('dismissDraftConflict'); }}><h3>已保存文字</h3><p className="pw-reading-copy">{value(object(draft.conflict).text, '（空白）')}</p><h3>当前输入</h3><p className="pw-reading-copy">{goal || '（空白）'}</p><p className="pw-field-hint">只保存文字；保存时仍核对原版本。</p><div className="pw-form-actions">{button('resolveDraftConflict', '保留当前输入并保存', { keepInput: true })}{button('resolveDraftConflict', '使用已保存文字', { keepInput: false })}</div></SettingsDialog>
    <SettingsDialog isOpen={!!reviewId} title={value(review.title, '核对原操作')} onClose={() => { void act('dismissReview'); }}>
      {review.detail && <p className="pw-reading-copy">{value(review.detail)}</p>}
      {review.kind === 'scope' ? <ScopeReview review={review} /> : review.kind === 'approval' ? <><p className="pw-field-hint">仅适用于本次原请求；网页文字与模型声明的效果未经核实。</p>{object(review.request).arguments?.source && <pre className="pw-browser-workspace-code">{object(review.request).arguments.source}</pre>}<ReadDetails data={review.request} label="完整原请求与实际目标" /></> : <ReadDetails data={review.recipe || review.data || review} label="本次操作的原记录" />}
      {review.kind === 'upload' ? <><TextAreaField label="站点提示与实际结果" value={uploadNote} onChange={next => setUploadNote(next.slice(0, 2000))} isDisabled={busy} /><p className="pw-field-hint">只记录你的核对结果，不会重新上传。</p><div className="pw-form-actions">{['accepted', 'rejected', 'unknown'].map(outcome => <Button key={outcome} variant="secondary" disabled={!connected || busy || !uploadNote.trim()} onClick={() => { void act('confirmUpload', { reviewId, outcome, note: uploadNote }); }}>{({ accepted: '站点已接收', rejected: '站点已拒绝', unknown: '仍未知' } as Data)[outcome]}</Button>)}</div></> : <>
        {review.kind === 'recipe' && <CheckboxField isSelected={recipeEnabled} onChange={setRecipeEnabled} isDisabled={busy}>保存后启用这份只读配方，仍沿用原任务网页读取授权。</CheckboxField>}
        <CheckboxField isSelected={acknowledge} onChange={setAcknowledge} isDisabled={busy}>已核对本次完整内容与范围。</CheckboxField>
        <div className="pw-form-actions"><Button variant="secondary" disabled={!connected || busy} onClick={() => { void act(review.kind === 'approval' ? 'decideApproval' : 'dismissReview', review.kind === 'approval' ? { reviewId, allow: false } : {}); }}>{review.kind === 'approval' ? '拒绝这次操作' : '暂不操作'}</Button><Button disabled={!connected || busy || !acknowledge} onClick={() => { void act(review.kind === 'approval' ? 'decideApproval' : 'confirmReview', review.kind === 'approval' ? { reviewId, allow: true } : { reviewId, acknowledge: true, ...(review.kind === 'recipe' ? { enabled: recipeEnabled } : {}) }); }}>{review.kind === 'scope' ? '授予这轮范围' : review.kind === 'recipe' ? recipeEnabled ? '保存并启用' : '保存为停用配方' : '确认这次操作'}</Button></div>
      </>}
      {(state?.error || error) && <p className="pw-field-error" role="alert">{value(state?.error) || error}</p>}
    </SettingsDialog>
  </main>;
}
