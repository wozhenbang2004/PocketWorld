import { useEffect, useRef, useState, type ReactNode } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField, ObjectTable, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { PressButton } from './library';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { IconAdd, IconArrowLeft, IconChevronRight, IconFileSettings, IconRefresh } from './icons';
import './work-profiles-workspace.css';
import './capability-management-manga.css';
import { newProfile, profileAddRequiredTools, profileAsset, profileDependencyLabel, profileModel, profileMutationReceipt, profilePluginTool, profileReferenceKey, profileRows, profileSaveInput, profileSkillDependencies, profileStartInput, profileStartReceipt, ProfileRequestGate, type ProfileData as Data } from './profile-contract';

type Props = { state: Data; supported: (action: string) => boolean; run: (action: string, args?: Data) => Promise<any>; registerTransientBack: (handler: (() => boolean) | null) => void };
type Editor = { original: Data | null; definition: Data; viewHandle: string; useCurrentProject: boolean };
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));
const profileFormLayout = { gridTemplateColumns: 'minmax(0, 1fr)', minWidth: 0, overflowWrap: 'anywhere' as const };
let retained: { editor: Editor | null; unknown: Data | null } = { editor: null, unknown: null };
const sourceLabel: Data = { instructionRefs: '指令资料', skillRefs: '技能', pluginRefs: '插件工具', extensionRefs: '扩展', targetBindings: '执行目标', teamTemplateRef: '团队模板' };
const actionLabel: Data = { create: '新建工作配置', save: '保存引用修改', remove: '删除工作配置', rebind: '保存重新绑定的配置', start: '开始这次原任务' };
const modelLabel = (purpose: string) => ({ MAIN: '主任务模型', SUMMARY: '摘要模型', RELAY: '外部网关模型', MEMBER: '成员默认模型' } as Data)[purpose] || `成员 ${purpose.slice(7)} 的模型`;
const referenceText = (ref: Data | null | undefined): string => !ref ? '未限定' : ref.kind === 'exact' ? `${ref.modelId} · ${ref.connectionId}` : ref.kind === 'group' ? `模型组 ${ref.groupId} · 修订 ${ref.groupRevision}` : `${ref.id || ref.profileId || ref.packageId || '原引用'}${ref.revision ? ` · 修订 ${ref.revision}` : ''}${ref.worker ? ` · ${ref.worker}` : ''}${ref.tools?.length ? ` · ${ref.tools.join('、')}` : ''}`;

function ProfileContents({ value }: { value: Data }) {
  return <div className="pw-profile-contents pw-form-grid" style={profileFormLayout}><h3>{value.name || '未命名工作配置'}</h3>
    <dl className="pw-facts">{Object.entries(value.modelPolicies || {}).map(([purpose, ref]) => <div key={purpose}><dt>{modelLabel(purpose)}</dt><dd>{referenceText(ref as Data)}</dd></div>)}
      <div><dt>模型并发</dt><dd>{value.resourcePreferences?.maxConcurrentModelCalls ? `最多 ${value.resourcePreferences.maxConcurrentModelCalls} 路，仍遵守原限制` : '沿用原宿主限制'}</dd></div>
      <div><dt>恢复状态</dt><dd>{value.restoredPendingReview ? '待在本机重新绑定，不能执行' : '引用定义；实际使用前仍核对原来源'}</dd></div></dl>
    {['instructionRefs', 'skillRefs', 'pluginRefs', 'extensionRefs', 'targetBindings'].map(field => profileRows(value[field]).length > 0 && <div key={field}><h4>{sourceLabel[field]}</h4>{profileRows(value[field]).map((ref, at) => <p className="pw-reading-copy" key={at}>{referenceText(ref)} · {ref.optional ? '明确可选' : '必需'}</p>)}</div>)}
    {value.teamTemplateRef && <p>团队模板：{referenceText(value.teamTemplateRef)} · {value.teamTemplateRef.optional ? '明确可选' : '必需'}</p>}
    {profileRows(value.restoredReferences).length > 0 && <Accordion><AccordionItem id="history" title={`恢复时保留的原引用 · ${value.restoredReferences.length} 项`}><p>这是旧设备的核对清单。原安装审批、设备信任和执行授权没有恢复。</p>{profileRows(value.restoredReferences).map((row, at) => <p className="pw-reading-copy" key={at}>{row.kind.startsWith('model:') ? modelLabel(row.kind.slice(6)) : sourceLabel[row.kind] || row.kind}：{referenceText(row.reference)}</p>)}</AccordionItem></Accordion>}
  </div>;
}
function SourceStatus({ section, children }: { section?: Data; children: ReactNode }) {
  return section?.available === true ? <>{children}</> : <p className="pw-muted">此原生来源尚未成功读取。请刷新来源，或先回到相应来源页处理。</p>;
}

/** Real native owner definitions, using the already adopted BoardUI controls and modal. */
export function WorkProfilesWorkspace({ state, supported, run, registerTransientBack }: Props) {
  const [snapshot, setSnapshot] = useState<Data>({ profiles: [] }), [editor, setEditor] = useState<Editor | null>(() => retained.editor ? { ...clone(retained.editor), viewHandle: '' } : null);
  const [snapshotStatus, setSnapshotStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [choices, setChoices] = useState<Data>({}), [review, setReview] = useState<Data | null>(null), [inspection, setInspection] = useState<Data | null>(null), [conflict, setConflict] = useState<Data | null>(null);
  const [unknown, setUnknown] = useState<Data | null>(() => retained.unknown), [unknownRead, setUnknownRead] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [ack, setAck] = useState(false), [restoreAck, setRestoreAck] = useState(false), [member, setMember] = useState(''), [memberModel, setMemberModel] = useState('');
  const [goal, setGoal] = useState(''), [startedTask, setStartedTask] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(() => !!retained.editor), [panel, setPanel] = useState('models');
  const editorVisible = useRef(editorOpen), editorEpoch = useRef(0);
  editorVisible.current = editorOpen;
  const renderEpoch = editorEpoch.current;
    const gate = useRef(new ProfileRequestGate()), alive = useRef(true), mutation = useRef<Data | null>(null), currentEditor = useRef(editor), currentReview = useRef(review), currentUnknown = useRef(unknown);
  const currentState = useRef(state); currentState.current = state;
  currentEditor.current = editor; currentReview.current = review; currentUnknown.current = unknown;
  const nativeActive = () => { const view = bridge.getState().view as Data | undefined; return document.visibilityState === 'visible' && view?.section === 'workProfiles' && !view.taskId && !currentState.current.webWorkspaceTaskOpen; };
  const editorCurrent = () => editorVisible.current && editorEpoch.current === renderEpoch && !!currentEditor.current && nativeActive();
  const ownerTicket = () => { const view = bridge.getState().view as Data | undefined; return JSON.stringify([view?.section, view?.taskId, view?.resumeRevision, bridge.getDocumentEpoch()]); };
  const release = (key: string, value?: string) => { if (value) void bridge.command('profiles.discard', { [key]: value }).catch(() => {}); };
  const closeInspection = () => { release('preflightHandle', inspection?.preflightHandle); setInspection(null); };
  const closeReview = () => { release('reviewHandle', currentReview.current?.reviewHandle); currentReview.current = null; setReview(null); setAck(false); };
  const acceptSnapshot = (value: Data) => { if (!Array.isArray(value?.profiles)) throw new Error('原工作配置清单未完整返回。'); setSnapshot(value); setSnapshotStatus('ready'); };
  const invoke = async (action: string, input: Data, accept: (value: Data) => void, originalMutation: Data | null = null) => {
    if (!nativeActive()) return false;
    const ticket = gate.current.begin(originalMutation?.operationId || null); if (ticket === null) return false;
    setBusy(true); setError(''); if (action === 'profiles.snapshot') setSnapshotStatus('loading'); if (originalMutation) mutation.current = originalMutation;
    try {
      if (!supported(action)) throw new Error('当前宿主尚未接通这项工作配置操作。');
      const value = await run(action, input);
      if (alive.current && gate.current.owns(ticket)) { accept(value); return true; }
      for (const key of ['viewHandle', 'choicesHandle', 'reviewHandle', 'preflightHandle']) release(key, value?.[key]);
    } catch (failure) {
      if (alive.current && gate.current.owns(ticket)) {
        setError(failure instanceof Error ? failure.message : String(failure));
        if (action === 'profiles.snapshot') setSnapshotStatus('error');
        if (originalMutation) { gate.current.markUnknown(originalMutation.operationId); currentUnknown.current = originalMutation; setUnknown(originalMutation); setUnknownRead(null); }
      }
    } finally { if (gate.current.finish(ticket) && alive.current) { mutation.current = null; setBusy(false); } }
    return false;
  };
  const refresh = () => { void invoke('profiles.snapshot', {}, acceptSnapshot); };
  const loadChoices = (project: boolean) => { if (!editorCurrent()) return; closeInspection(); closeReview(); void invoke('profiles.choices', { useCurrentProject: project }, value => {
    if (!value?.choicesHandle || !value.sections || value.useCurrentProject !== project) throw new Error('未取得所选范围的原生来源窗口。');
    release('choicesHandle', choices.choicesHandle); setChoices(value);
  }); };
  const invalidate = (active: boolean) => {
    const pending = mutation.current; editorEpoch.current++; gate.current.invalidate(active);
    if (pending) { setUnknown(pending); currentUnknown.current = pending; }
    mutation.current = null; setBusy(false); closeReview(); setInspection(null); setConflict(null); setRestoreAck(false); setUnknownRead(null); setMemberModel('');
    setEditor(old => old ? { ...old, viewHandle: '' } : null); setChoices({});
    void bridge.command('profiles.discard').catch(() => {});
  };
  useEffect(() => {
    alive.current = true; if (retained.unknown) gate.current.markUnknown(retained.unknown.operationId);
    gate.current.invalidate(nativeActive()); refresh();
    return () => {
      const pending = mutation.current; gate.current.invalidate(false); alive.current = false;
      retained = { editor: currentEditor.current ? { ...clone(currentEditor.current), viewHandle: '' } : null, unknown: pending || currentUnknown.current };
      void bridge.command('profiles.discard').catch(() => {});
    };
  }, []);
  useEffect(() => { retained = { editor: editor ? { ...clone(editor), viewHandle: '' } : null, unknown }; }, [editor, unknown]);
  useEffect(() => {
    let owner = ownerTicket();
    const changed = () => { const active = nativeActive(); invalidate(active); if (active) refresh(); };
    const hidden = () => invalidate(false); document.addEventListener('visibilitychange', changed); window.addEventListener('pagehide', hidden);
    const unsubscribe = bridge.subscribe(() => { const incoming = ownerTicket(); if (incoming !== owner) { owner = incoming; const active = nativeActive(); invalidate(active); if (active) refresh(); } });
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', changed); window.removeEventListener('pagehide', hidden); };
  }, []);
  useEffect(() => { registerTransientBack(() => {
    if (busy) return true; if (review) { closeReview(); return true; } if (inspection) { closeInspection(); return true; }
    if (editor && editorOpen) { pauseEditor(); return true; } return false;
  }); return () => registerTransientBack(null); }, [busy, review, inspection, editor, editorOpen, registerTransientBack]);
  const safely = (work: () => void) => { try { work(); setError(''); } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); } };
  const change = (definition: Data) => { if (!editorCurrent() || busy || currentUnknown.current) return; setEditor(old => old ? { ...old, definition } : null); setRestoreAck(false); };
  const pauseEditor = () => {
    if (busy) return;
    editorVisible.current = false; invalidate(nativeActive()); setEditorOpen(false);
    setNotice('草稿已保留。继续编辑时重新读取来源与原修订。');
  };
  const resumeEditor = () => { if (busy) return; editorVisible.current = true; setEditorOpen(true); };
  const open = (profile?: Data) => {
    const prior = currentEditor.current;
    if (busy || currentUnknown.current || prior && (!prior.original || JSON.stringify(prior.definition) !== JSON.stringify(prior.original)) && (!profile || prior.original?.profileId !== profile.profileId)) return;
    if (profile && editor?.original?.profileId === profile.profileId) { resumeEditor(); return; }
    closeReview(); release('viewHandle', prior?.viewHandle); release('choicesHandle', choices.choicesHandle); setInspection(null); setConflict(null); setRestoreAck(false); setError('');
    if (!profile) { const fresh = { original: null, definition: newProfile(), viewHandle: '', useCurrentProject: false }; currentEditor.current = fresh; setEditor(fresh); editorVisible.current = true; setEditorOpen(true); setPanel('models'); setChoices({}); loadChoices(false); return; }
    void invoke('profiles.read', { id: profile.profileId }, value => {
      if (!value?.viewHandle || value.profile?.profileId !== profile.profileId) throw new Error('未取得原工作配置。');
      const incoming = { original: value.profile, definition: clone(value.profile), viewHandle: value.viewHandle, useCurrentProject: false }; currentEditor.current = incoming; setEditor(incoming); editorVisible.current = true; setEditorOpen(true); setPanel('models'); setChoices({});
    }).then(accepted => { if (accepted) loadChoices(false); });
  };
  const recheck = () => {
    const prior = editor?.original; if (!editorCurrent() || !prior) return;
    void invoke('profiles.read', { id: prior.profileId }, value => {
      if (!value?.viewHandle || value.profile?.profileId !== prior.profileId) throw new Error('原工作配置读取不完整。');
      if (value.profile.revision !== prior.revision) { setConflict(value); setError('原记录已有新修订。草稿已保留，请核对当前版本后明确合并。'); return; }
      setEditor(old => old ? { ...old, viewHandle: value.viewHandle } : null); setConflict(null);
    });
  };
  const prepare = (action: string) => safely(() => {
    if (!editorCurrent() || !editor || unknown) return;
    const input: Data = action === 'profiles.reviewRemove' ? { viewHandle: editor.viewHandle } : profileSaveInput(editor.definition, editor.original, editor.viewHandle);
    if (action === 'profiles.reviewRebind') { if (!restoreAck || !choices.choicesHandle) throw new Error('请读取本机来源，并明确核对恢复时移除的引用。'); input.choicesHandle = choices.choicesHandle; }
    void invoke(action, input, value => { if (!value?.reviewHandle || !value.operationId || !actionLabel[value.command]) throw new Error('未取得本次原生审阅。'); setReview(value); setAck(false); });
  });
  const confirm = () => {
    if (!review || currentReview.current !== review || !ack || busy || currentUnknown.current || !editorCurrent()) return; const original = review; currentReview.current = null; setReview(null); setAck(false);
    void invoke('profiles.confirm', { reviewHandle: original.reviewHandle, ...(original.command === 'start' ? { consent: true } : {}) }, value => {
      if (original.command === 'start') { const result = profileStartReceipt(value, original); setStartedTask(result.taskId); setGoal(''); setNotice('原任务已返回：' + (result.status || '请查看原回执') + '。'); return; }
      const record = profileMutationReceipt(value, original); if (value.snapshot) acceptSnapshot(value.snapshot);
      if (original.command === 'remove') { setEditor(null); setEditorOpen(false); }
      else setEditor(old => old ? { ...old, original: record, definition: clone(record), viewHandle: '' } : null);
      setNotice(actionLabel[original.command] + '：已收到原生保存回执。'); setRestoreAck(false);
    }, original);
  };
  const readUnknown = () => {
    const expected = currentUnknown.current; if (!expected) return;
    void invoke('profiles.result', { operationId: expected.operationId }, value => {
      if (value.operationId !== expected.operationId || value.readOnly !== true || value.replayAllowed !== false) throw new Error('尚未取得原操作的只读核对。');
      acceptSnapshot(value.snapshot); if (value.status === 'completed') { if (expected.command === 'start') profileStartReceipt(value.receipt, expected); else profileMutationReceipt(value.receipt, expected); }
      gate.current.inspectOriginal(expected.operationId); setUnknownRead(value);
    });
  };
  const acknowledgeUnknown = () => {
    if (!unknownRead || !gate.current.acknowledge()) return;
    if (unknownRead.status === 'completed' && unknown?.command === 'start') { setStartedTask(profileStartReceipt(unknownRead.receipt, unknown).taskId); setGoal(''); }
    else if (unknownRead.status === 'completed' && unknown) {
      const record = profileMutationReceipt(unknownRead.receipt, unknown);
      if (unknown.command === 'remove') { setEditor(null); setEditorOpen(false); }
      else setEditor(old => old ? { ...old, original: record, definition: { ...old.definition, profileId: record.profileId, revision: record.revision, ...(record.restoredReferences ? { restoredReferences: record.restoredReferences, restoredPendingReview: record.restoredPendingReview } : {}) }, viewHandle: '' } : null);
    }
    currentUnknown.current = null; setUnknown(null); setUnknownRead(null); setNotice('已核对原操作。草稿保留；下一次保存需要重新读取与审阅。');
  };
  const unknownControls = unknown && <div className="pw-profile-attention" data-profile-state="unknown"><p role="status">“{actionLabel[unknown.command]}”的原回执待核对。草稿保留，不能重复确认。{unknown.original ? `原修订 ${unknown.original.revision}。` : '请核对清单是否已经产生新配置。'}{unknown.command === 'start' && '已进入的原任务可能仍在执行，请先核对，勿重复启动。'}</p>
    {unknownRead && <p>{unknownRead.status === 'completed' ? '已找到这次原操作的完成回执。' : unknownRead.status === 'not_entered' ? '原窗口关闭前请求尚未进入。' : '仍未确认原操作结果，请核对当前记录；此读取没有重复执行。'}</p>}
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={readUnknown}>核对原操作与当前清单</Button><Button variant="secondary" disabled={busy || !unknownRead} onClick={acknowledgeUnknown}>我已核对，允许重新准备</Button></div>
    {unknownRead?.taskId && <Button variant="secondary" onClick={() => { void run('tasks.open', { id: unknownRead.taskId }); }}>打开这次原任务</Button>}{unknownRead?.error && <p role="alert">{unknownRead.error}</p>}
    {unknownRead && <p className="pw-muted">当前记录：{profileRows(snapshot.profiles).map(row => `${row.name}（修订 ${row.revision}）`).join('；') || '无已保存记录'}</p>}</div>;
  const draft = editor?.definition, sections = choices.sections || {}, sourcesReady = !!choices.choicesHandle && choices.useCurrentProject === editor?.useCurrentProject, locked = busy || !!unknown;
  const dependencies = draft ? profileSkillDependencies(draft, sourcesReady ? sections : {}) : [], readyDependencies = dependencies.filter(need => need.status === 'ready_to_add');
  const selectedRefs = (field: string, row: Data) => profileRows(draft?.[field]).find(ref => ref.id === row.id);
  const modelField = (purpose: string) => {
    if (!draft) return null; const offered = profileRows(purpose === 'RELAY' ? choices.relayModelChoices : choices.modelChoices), current = draft.modelPolicies[purpose], key = profileReferenceKey(current);
    const options = [{ id: '__none', label: purpose === 'MAIN' || purpose === 'SUMMARY' ? '不限定；使用时核对原宿主选择' : '不限定此用途' }, ...offered.map(row => ({ id: profileReferenceKey(row.reference), label: `${row.label} · ${row.detail}` }))];
    if (key && !options.some(option => option.id === key)) options.push({ id: key, label: `已保存旧引用：${referenceText(current)}` });
    return <SelectField key={purpose} label={modelLabel(purpose)} value={key || '__none'} options={options} isDisabled={locked || !sourcesReady} onChange={value => safely(() => change(profileModel(draft, purpose, value === '__none' ? null : offered.find(row => profileReferenceKey(row.reference) === value)?.reference || current)))} />;
  };
  const updateRef = (field: string, index: number, optional: boolean | null) => { if (!draft) return; const next = clone(draft); if (optional === null) next[field].splice(index, 1); else next[field][index].optional = optional; change(next); };
  const dirty = !!editor && (!editor.original || JSON.stringify(editor.definition) !== JSON.stringify(editor.original));
  const referenceCount = draft ? ['instructionRefs', 'skillRefs', 'pluginRefs', 'extensionRefs', 'targetBindings'].reduce((count, field) => count + profileRows(draft[field]).length, 0) : 0;
  const sourceNames: Data = { skills: '指令与技能', plugins: '插件', extensions: '扩展', teams: '团队模板' };
  const unavailableSources = Object.keys(sourceNames).filter(key => sections[key]?.available !== true).map(key => sourceNames[key]);
  const profiles = profileRows(snapshot.profiles);
  const firstConfiguration = snapshotStatus === 'ready' && !profiles.length && !editor && !unknown;
  return <section className="pw-settings-section pw-profiles-workspace">
    <header className="pw-profile-heading"><div><h2>工作配置</h2></div><div className="pw-form-actions">
      <Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="刷新原记录" disabled={busy} onClick={refresh} />
      {!firstConfiguration && <PressButton compact disabled={busy || !!unknown || !!editor && dirty} onClick={() => open()}>新建配置</PressButton>}
    </div></header>
    {editor && <div className="pw-profile-draft" aria-label="保留的工作配置草稿"><IconFileSettings size={20} aria-hidden /><div><strong>{draft?.name || '未命名配置'}</strong><p>{dirty ? '有未保存修改' : '编辑内容已保留'} · {editor.original ? `原修订 ${editor.original.revision}` : '新建草稿'}{draft?.restoredPendingReview ? ' · 恢复后待重新绑定' : ''}</p></div><Button variant="ghost" trailingIcon={IconChevronRight} disabled={busy} onClick={resumeEditor}>继续编辑草稿</Button></div>}
    {startedTask && <Button variant="secondary" disabled={busy || !supported('tasks.open')} onClick={() => { void run('tasks.open', { id: startedTask }); }}>查看这次原任务与回执</Button>}{busy && mutation.current?.command === 'start' && <Button variant="secondary" onClick={() => { const operationId = mutation.current?.operationId; if (operationId) void run('profiles.stop', { operationId }).then(() => setNotice('已请求停止这次原任务，等待原结果。')).catch(failure => setError(String(failure))); }}>请求停止这次原任务</Button>}
    {unknownControls}{error && <p className="pw-profile-feedback" role="alert">{error}</p>}{notice && <p className="pw-profile-feedback" role="status">{notice}</p>}
    {profiles.length > 0 ? <ObjectTable label="已保存工作配置" rows={profiles} getRowId={row => row.profileId} columns={[
      { id: 'name', label: '已保存配置', render: row => <div className="pw-object-title"><strong>{row.name}</strong><small>修订 {row.revision} · {row.restoredPendingReview ? '恢复后待重新绑定，不能执行' : `${Object.keys(row.modelPolicies || {}).length} 项模型用途`}</small></div>, sortValue: row => row.name },
      { id: 'open', label: '编辑', render: row => <Button variant="ghost" trailingIcon={IconChevronRight} aria-label={`打开工作配置：${row.name}`} disabled={busy || !!unknown || !!editor && dirty && editor.original?.profileId !== row.profileId} onClick={() => open(row)}>打开</Button> },
    ]} /> : snapshotStatus === 'loading' ? <p className="pw-profile-list-status" role="status">正在读取工作配置…</p>
      : snapshotStatus === 'error' ? <p className="pw-profile-list-status">配置清单暂未读到，请刷新后重试。</p>
      : firstConfiguration && <div className="pw-profile-directory-empty">
        <span className="pw-profile-directory-icon"><IconFileSettings size={26} aria-hidden /></span>
        <div><h3>还没有工作配置</h3><p>保存常用模型、资料与工具的组合，下次开始任务时直接选用。</p></div>
        <Button leadingIcon={IconAdd} disabled={busy} onClick={() => open()}>新建配置</Button>
      </div>}
    {editor && !editorOpen && dirty && <p className="pw-profile-caption">先继续上方草稿，或在编辑器中明确舍弃，再新建与切换。</p>}
    <SettingsDialog isOpen={editorOpen && !!editor && !review && !inspection} title={editor?.original ? `编辑 · ${editor.original.name}` : '新建工作配置'} onClose={pauseEditor}>
      {editor && draft && <div className="pw-profile-editor">
        <div className="pw-work-profile-identity"><TextField label="配置名称" value={draft.name} onChange={name => change({ ...draft, name })} isDisabled={locked} /></div>
        <div className="pw-profile-source-bar" aria-label="当前来源窗口"><div><strong>{sourcesReady ? '本次来源窗口已读取' : '来源窗口待读取'}</strong><p>{editor.useCurrentProject ? '当前已选项目范围' : '默认资料范围'} · {editor.original ? `原修订 ${editor.original.revision}${editor.viewHandle ? ' 已核对' : ' 待重新核对'}` : '新建配置'}</p></div><Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="读取当前可用来源" title="读取当前可用来源" disabled={locked} onClick={() => loadChoices(editor.useCurrentProject)}/></div>
        <div className="pw-profile-editor-scroll">
          {unknownControls}
          <div className="pw-profile-scope"><CheckboxField isSelected={editor.useCurrentProject} isDisabled={locked} onChange={useCurrentProject => { if (!editorCurrent()) return; release('choicesHandle', choices.choicesHandle); closeInspection(); closeReview(); setChoices({}); setEditor({ ...editor, useCurrentProject }); setRestoreAck(false); setMemberModel(''); }}>使用当前已选项目的资料范围</CheckboxField>
            {!sourcesReady ? <p className="pw-profile-caption">来源窗口尚未读取或已关闭。原引用和编辑内容保留；重新选择来源前请先读取。</p> : unavailableSources.length > 0 && <p className="pw-profile-caption" role="status">尚未成功读取：{unavailableSources.join('、')}。已保存引用仍保留。</p>}
          </div>
          {draft.restoredPendingReview && <div className="pw-profile-attention" data-profile-state="restored"><strong>恢复后待重新绑定，当前不能执行</strong><p>旧设备授权未恢复。先读取本机来源、核对原引用，再明确审阅重新绑定。</p><ProfileContents value={draft} /><CheckboxField isSelected={restoreAck} isDisabled={locked} onChange={setRestoreAck}>我已核对旧引用清单，明确选择了新设备的模型与保留的资料；未重新选择的旧权限和目标继续排除</CheckboxField></div>}
        {conflict && <div className="pw-profile-attention" role="alert" data-profile-state="conflict"><p>当前原记录：{conflict.profile.name} · 修订 {conflict.profile.revision}。采用新修订后仍保留你的字段修改，最终保存前需要重新审阅。</p><ProfileContents value={conflict.profile} /><Button variant="secondary" disabled={locked} onClick={() => { if (!editorCurrent()) return; setEditor({ ...editor, original: conflict.profile, viewHandle: conflict.viewHandle, definition: { ...draft, profileId: conflict.profile.profileId, revision: conflict.profile.revision, ...(conflict.profile.restoredReferences ? { restoredReferences: conflict.profile.restoredReferences, restoredPendingReview: conflict.profile.restoredPendingReview } : {}) } }); setConflict(null); setError(''); setRestoreAck(false); }}>采用当前修订作为保存依据，保留我的修改</Button></div>}

          <Tabs selectedKey={panel} onSelectionChange={key => { if (editorCurrent() && !busy) setPanel(String(key)); }} className="pw-profile-tabs">
            <TabList aria-label="工作配置编辑分区"><Tab id="models">模型</Tab><Tab id="sources">资料与工具</Tab><Tab id="selected">已选引用{referenceCount > 0 ? ` · ${referenceCount}` : ''}</Tab></TabList>
            <TabPanel id="models" shouldForceMount hidden={panel !== 'models'}>
              <div className="pw-profile-main-model">{modelField('MAIN')}</div>
              <Accordion className="pw-profile-disclosures" allowsMultipleExpanded>
                <AccordionItem id="other-models" title="摘要与外部网关模型" description={`摘要：${referenceText(draft.modelPolicies.SUMMARY)} · 网关：${referenceText(draft.modelPolicies.RELAY)}`}><div className="pw-profile-fields">{modelField('SUMMARY')}{modelField('RELAY')}</div></AccordionItem>
                <AccordionItem id="members" title="成员模型与并发限制" description={`${Object.keys(draft.modelPolicies).filter(key => key.startsWith('MEMBER')).length} 项成员用途 · ${draft.resourcePreferences.maxConcurrentModelCalls ? `最多 ${draft.resourcePreferences.maxConcurrentModelCalls} 路` : '沿用原宿主限制'}`}><div className="pw-profile-fields">{Object.keys(draft.modelPolicies).filter(key => key.startsWith('MEMBER')).map(purpose => <div key={purpose}>{modelField(purpose)}<Button variant="ghost" disabled={locked} onClick={() => change(profileModel(draft, purpose, null))}>移除此成员用途</Button></div>)}
          <TextField label="成员标识（留空表示成员默认模型）" value={member} onChange={setMember} isDisabled={locked} /><SelectField label="新增成员用途的模型" value={memberModel || '__none'} options={[{ id: '__none', label: '请明确选择本次模型' }, ...profileRows(choices.modelChoices).map(row => ({ id: profileReferenceKey(row.reference), label: `${row.label} · ${row.detail}` }))]} isDisabled={locked || !sourcesReady} onChange={value => setMemberModel(value === '__none' ? '' : value)} /><Button variant="secondary" disabled={locked || !sourcesReady || !memberModel} onClick={() => safely(() => { const selected = profileRows(choices.modelChoices).find(row => profileReferenceKey(row.reference) === memberModel); if (!selected) throw new Error('请重新选择当前来源中的成员模型。'); const purpose = member.trim() ? `MEMBER.${member.trim()}` : 'MEMBER'; change(profileModel(draft, purpose, selected.reference)); setMember(''); setMemberModel(''); })}>添加所选成员用途</Button>
          <SelectField label="本任务模型并发上限" value={String(draft.resourcePreferences.maxConcurrentModelCalls || 'inherit')} options={[{ id: 'inherit', label: '沿用原宿主限制' }, { id: '1', label: '最多 1 路' }, { id: '2', label: '最多 2 路' }]} isDisabled={locked} onChange={value => change({ ...draft, resourcePreferences: value === 'inherit' ? {} : { maxConcurrentModelCalls: Number(value) } })} /></div></AccordionItem>
              </Accordion>
            </TabPanel>
            <TabPanel id="sources" shouldForceMount hidden={panel !== 'sources'}><p className="pw-profile-caption">从已启用、已审阅的原来源中选择。这里不会安装插件或授予权限。</p><Accordion className="pw-profile-disclosures" allowsMultipleExpanded>
          {['instructionRefs', 'skillRefs'].map(field => <AccordionItem key={field} id={field} title={`选择${sourceLabel[field]}`} description={`${profileRows(draft[field]).length} 项已选 · ${sections.skills?.available === true ? "来源已读取" : "来源尚未读取"}`}><SourceStatus section={sections.skills}>{profileRows(sections.skills?.items).map(row => { const selected = selectedRefs(field, row), exact = selected?.revision === row.revision && selected?.digest === row.digest; return <CheckboxField key={row.id} isSelected={!!exact} isDisabled={locked || !sourcesReady || !exact && (!row.active || !row.contentVerified)} onChange={checked => safely(() => change(profileAsset(draft, field, row, checked)))}>{row.name} · 修订 {row.revision}{!row.active ? ' · 尚未启用/审阅' : ''}{selected && !exact ? ' · 已保存另一修订' : ''}</CheckboxField>; })}{!profileRows(sections.skills?.items).length && <p>此范围没有可读取的技能。请先在技能页导入、启用并审阅原内容。</p>}</SourceStatus></AccordionItem>)}
          {dependencies.length > 0 && <AccordionItem id="skill-dependencies" title="所选技能的能力准备" description={`${dependencies.length} 项要求${readyDependencies.length ? ` · ${readyDependencies.length} 项工具可以加入` : ''}`}>
            <p className="pw-profile-caption">按上方已选技能的准确修订核对。先在原来源完成安装、连接或授权，再刷新这里的来源。</p>
            {dependencies.map((need, at) => <div className="pw-profile-reference" key={need.capability || `source-${at}`}>
              <p className="pw-reading-copy">{need.tool ? `${need.plugin?.name || need.pluginId} · ${need.tool}` : need.capability || '原技能来源'}：{profileDependencyLabel(need.status)}</p>
              <p className="pw-profile-caption">所需技能：{profileRows(need.requiredBy).map(source => `${source.name} · 修订 ${source.revision}${source.optional ? '（可选）' : ''}`).join('；')}</p>
            </div>)}
            <Button variant="secondary" disabled={locked || !sourcesReady || !readyDependencies.length} onClick={() => safely(() => { change(profileAddRequiredTools(draft, sections)); setNotice('已将当前就绪的所需工具加入草稿。请核对保存，再预检已保存修订。'); })}>加入已就绪的所需工具</Button>
            <p className="pw-profile-caption">此操作只修改草稿；已有任务继续保留原配置。</p>
          </AccordionItem>}
          <AccordionItem id="plugins" title="选择已有插件工具" description={`${profileRows(draft.pluginRefs).length} 个插件引用 · 保留原工具权限`}><SourceStatus section={sections.plugins}>{profileRows(sections.plugins?.items).map(plugin => <div key={plugin.id}><h4>{plugin.name}</h4>{profileRows(plugin.tools).map(tool => { const selected = selectedRefs('pluginRefs', plugin), exact = selected?.installationId === plugin.installationId && selected?.stateRevision === plugin.stateRevision && selected?.manifestDigest === plugin.manifestDigest, checked = !!exact && selected?.tools?.includes(tool.name); return <CheckboxField key={tool.name} isSelected={!!checked} isDisabled={locked || !sourcesReady || !checked && (!plugin.enabled || !tool.granted)} onChange={value => safely(() => change(profilePluginTool(draft, plugin, tool.name, value)))}>{tool.name}{!tool.granted ? ' · 原权限尚未批准' : ''}</CheckboxField>; })}</div>)}{!profileRows(sections.plugins?.items).length && <p>尚无已登记插件。这里不会安装插件或授予工具权限。</p>}</SourceStatus></AccordionItem>
          <AccordionItem id="extensions" title="选择已有扩展" description={`${profileRows(draft.extensionRefs).length} 项已选`}><SourceStatus section={sections.extensions}>{profileRows(sections.extensions?.items).map(row => { const selected = selectedRefs('extensionRefs', row), exact = selected?.installationId === row.installationId && selected?.approvalRevision === row.approvalRevision && selected?.sourceSha256 === row.sourceSha256; return <CheckboxField key={row.id} isSelected={!!exact} isDisabled={locked || !sourcesReady || !exact && (!row.enabled || !row.approved)} onChange={checked => safely(() => change(profileAsset(draft, 'extensionRefs', row, checked)))}>{row.name} · {row.version}{!row.approved ? ' · 尚未审阅' : ''}</CheckboxField>; })}{!profileRows(sections.extensions?.items).length && <p>尚无可选扩展。</p>}</SourceStatus></AccordionItem>
          <AccordionItem id="team" title="团队模板" description={draft.teamTemplateRef ? referenceText(draft.teamTemplateRef) : "未限定团队模板"}><SourceStatus section={sections.teams}><SelectField label="已有团队模板" value={draft.teamTemplateRef ? profileReferenceKey(draft.teamTemplateRef) : '__none'} isDisabled={locked || !sourcesReady} options={[{ id: '__none', label: '不限定团队模板' }, ...profileRows(sections.teams?.items).map(row => ({ id: profileReferenceKey({ id: row.id, digest: row.digest }), label: row.name })), ...(draft.teamTemplateRef && !profileRows(sections.teams?.items).some(row => row.id === draft.teamTemplateRef.id && row.digest === draft.teamTemplateRef.digest) ? [{ id: profileReferenceKey(draft.teamTemplateRef), label: '已保存的旧团队引用' }] : [])]} onChange={value => { const row = profileRows(sections.teams.items).find(item => profileReferenceKey({ id: item.id, digest: item.digest }) === value); change({ ...draft, teamTemplateRef: value === '__none' ? null : row ? { id: row.id, digest: row.digest, optional: false } : draft.teamTemplateRef }); }} /></SourceStatus><p>保存模板引用不会开始团队，也不会替代原计划与本人授权。</p></AccordionItem>

            </Accordion></TabPanel>
            <TabPanel id="selected" shouldForceMount hidden={panel !== 'selected'}><h3>检查已选资料与目标</h3><p className="pw-profile-caption">引用保留准确版本。来源不可用时，只省略明确标记为可选的引用，不替换成其他来源。</p>
              {!referenceCount && <p className="pw-profile-empty">还没有资料、工具或目标引用。可前往“资料与工具”选择已审阅的来源。</p>}
              {['instructionRefs', 'skillRefs', 'pluginRefs', 'extensionRefs', 'targetBindings'].map(field => profileRows(draft[field]).map((ref, at) => <div className="pw-profile-reference" key={`${field}-${at}`}><p className="pw-reading-copy">{sourceLabel[field]}：{referenceText(ref)}</p><CheckboxField isSelected={ref.optional === true} isDisabled={locked} onChange={value => updateRef(field, at, value)}>明确可选：来源不可用时省略，不替换</CheckboxField><Button variant="ghost" disabled={locked} onClick={() => updateRef(field, at, null)}>移除此引用</Button></div>))}<p>电脑、SSH 与运行环境的新增绑定通过原任务目标主人流程产生。此处可保留或移除已有准确引用。</p>
              {draft.teamTemplateRef && <p className="pw-profile-caption">团队模板：{referenceText(draft.teamTemplateRef)} · {draft.teamTemplateRef.optional ? '明确可选' : '必需'}。在“资料与工具”中修改。</p>}
            </TabPanel>
          </Tabs>
          {error && <p className="pw-profile-feedback" role="alert">{error}</p>}{notice && <p className="pw-profile-feedback" role="status">{notice}</p>}
          {editor.original && !draft.restoredPendingReview && <Accordion className="pw-profile-disclosures"><AccordionItem id="profile-start" title="用这份配置开始任务"><section className="pw-profile-start"><p>先核对已保存修订、模型与已有权限。含成员模型、团队或项目资料时，使用上方明确选择的原项目副本。</p>{choices.project && <p className="pw-reading-copy">原项目 {choices.project.projectId} · 副本 {choices.project.checkoutId} · {choices.project.runtime}</p>}<Button variant="secondary" disabled={busy || !!unknown || dirty || !editor.viewHandle || !sourcesReady} onClick={() => { if (editorCurrent()) void invoke('profiles.preflight', { viewHandle: editor.viewHandle, choicesHandle: choices.choicesHandle }, value => { if (!value?.preflightHandle) throw new Error('原预检身份未返回。'); setInspection(value); }); }}>预检已保存修订</Button>{dirty && <p className="pw-profile-caption">保存并核对当前修改后，才能按新修订开始。</p>}</section></AccordionItem></Accordion>}
          <Accordion className="pw-profile-disclosures pw-profile-management"><AccordionItem id="profile-management" title="配置管理" description="删除配置或舍弃草稿"><div className="pw-form-actions">

            {editor.original && <Button variant="ghost" disabled={busy || !!unknown || !editor.viewHandle} onClick={() => prepare('profiles.reviewRemove')}>删除此配置</Button>}
            <Button variant="ghost" disabled={busy || !!unknown} onClick={() => { if (!editorCurrent()) return; pauseEditor(); currentEditor.current = null; setEditor(null); retained.editor = null; setNotice('已舍弃这份编辑草稿，已保存配置未改变。'); }}>舍弃此编辑草稿</Button>
          </div></AccordionItem></Accordion>
        </div>
        <footer className="pw-profile-editor-footer">
          <p className="pw-profile-save-state" role="status">{unknown ? '原回执待核对，不能重复保存。' : conflict ? '原记录已有新修订，请先核对并明确合并。' : draft.restoredPendingReview ? '恢复后待重新绑定；普通保存不会解除此状态。' : dirty ? '有未保存修改' : '当前引用已保存'}</p>
          <div className="pw-profile-primary-actions"><Button variant="ghost" leadingIcon={IconArrowLeft} disabled={busy} onClick={pauseEditor}>返回配置列表</Button>
            {editor.original && !editor.viewHandle && <Button variant="secondary" disabled={busy} onClick={recheck}>重新核对原修订</Button>}
            <PressButton compact disabled={busy || !!unknown || !draft.name.trim() || !!editor.original && !editor.viewHandle} onClick={() => prepare('profiles.reviewSave')}>核对保存引用</PressButton>
            {draft.restoredPendingReview && <Button variant="secondary" disabled={busy || !!unknown || !editor.viewHandle || !sourcesReady || !restoreAck || !draft.modelPolicies.MAIN} onClick={() => prepare('profiles.reviewRebind')}>预检并审阅重新绑定</Button>}
          </div><p className="pw-profile-caption">保存不启动任务。旧任务保留原配置与权限。</p>
        </footer>
      </div>}
    </SettingsDialog>
    <SettingsDialog isOpen={!!review} title={actionLabel[review?.command || ''] || '核对工作配置'} onClose={() => { if (!busy) closeReview(); }}>{review && <div className="pw-profile-review pw-form-grid" style={profileFormLayout}><ProfileContents value={review.definition} />
      {review.command === 'start' ? <><h3>本次原任务目标</h3><p className="pw-reading-copy">{review.goal}</p><p>{review.project ? `原项目 ${review.project.projectId} · 副本 ${review.project.checkoutId} · ${review.project.runtime}` : '本次不关联项目副本。'}</p><p>按已核对的准确模型与来源创建这一次任务；外部效果仍逐项使用原授权。离开页面不会停止已经开始的原任务。</p></> : review.command === 'remove' ? <p>只删除这份可复用定义；已有任务的原配置快照与记录继续保留。</p> : review.command === 'rebind' ? <p>原生已对本次新引用检查当前模型与来源。确认只保存重绑后的定义，不启动任务，也不恢复旧设备授权。</p> : <p>本次保存引用定义；实际执行仍需检查原模型、内容修订与已有权限。恢复后的待审阅状态由原生保留。</p>}
      {review.nativeReview && <p>本次核对：{profileRows(review.nativeReview.review?.items).filter(item => item.included).length} 项来源可用；明确省略 {profileRows(review.nativeReview.review?.items).filter(item => !item.included).length} 项。</p>}
      <CheckboxField isSelected={ack} isDisabled={locked} onChange={setAck}>我已核对上方配置、来源与本次操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回编辑</Button><PressButton compact disabled={busy || !ack || !!unknown} onClick={confirm}>确认{actionLabel[review.command]}</PressButton></div></div>}</SettingsDialog>
    <SettingsDialog isOpen={!!inspection} title="原工作配置预检" onClose={() => { if (!busy) closeInspection(); }}>{inspection && <div className="pw-profile-review pw-form-grid" style={profileFormLayout}><p>{inspection.ready ? '这次原生预检来源可用。尚未启动任务。' : '存在尚未满足的来源；原引用保留，未替换成其他模型或设备。'}</p>{profileRows(inspection.items).map((item, at) => <div key={at}><p className="pw-reading-copy">{item.kind === 'model' ? modelLabel(item.id) : item.kind} · {item.id}：{item.included ? '可用' : item.optional ? '明确可选，当前省略' : '尚不可用'}{!item.included ? `（${profileDependencyLabel(item.status)}）` : ''}</p>{profileRows(item.dependencies).filter(need => need.status !== 'ready').map(need => <p className="pw-profile-caption" key={need.capability}>{need.tool ? `${need.pluginId} · ${need.tool}` : need.capability}：{profileDependencyLabel(need.status)}</p>)}</div>)}<p>{inspection.project ? `原项目 ${inspection.project.projectId} · 副本 ${inspection.project.checkoutId} · ${inspection.project.runtime}` : '未关联项目副本。'}</p>{!inspection.runnable && <p role="alert">本入口需要准确的主任务与摘要模型；成员模型、团队与项目资料还需要原项目副本。执行目标仍在各自原任务中核对。</p>}<TextAreaField label="本次原任务目标" value={goal} onChange={setGoal} isDisabled={locked} /><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeInspection}>返回原配置</Button><PressButton compact disabled={locked || !inspection.runnable || !goal.trim()} onClick={() => safely(() => { if (!editorCurrent()) return; const input = profileStartInput(inspection, goal); void invoke('profiles.reviewStart', input, value => { if (value?.command !== 'start' || !value.reviewHandle || !value.operationId) throw new Error('原任务审阅未返回。'); setInspection(null); setReview(value); setAck(false); }); })}>核对这次任务</PressButton></div>{error && <p role="alert">{error}</p>}</div>}</SettingsDialog>
  </section>;
}
