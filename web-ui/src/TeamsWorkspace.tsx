import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { RiAddLine, RiArrowLeftLine, RiArrowRightSLine, RiEditLine, RiExternalLinkLine, RiPauseLine, RiPlayLine, RiRefreshLine } from '@remixicon/react';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { AIAnswer, PressButton, ProgressBar } from './library';
import { teamMessageConfirmed, teamMessageDelivery, teamMessageRecorded } from './team-message';
import { teamPhoneChoice, teamPhoneDevice, teamPhoneScopeLabel, teamPhoneSelections, teamPhoneTargetKey, teamPhoneTargetLabel, type TeamPhoneChoice } from './team-phone';
import { TeamHarnessFields } from './TeamHarnessFields';
import { TeamProductionFields, TeamProductionReview } from './TeamProductionFields';
import { TeamEvidenceWorkspace } from './TeamEvidenceWorkspace';
import { resetTeamProductionAcknowledgments, teamDefinitionPayload, teamProductionDraft, teamProductionEditorMember } from './team-production';
import { HarnessEvidence } from './HarnessEvidence';
import { harnessWorkerLabel, isTeamHarness } from './harness-capabilities';
import './team-continuity.css';
import './collaboration-depth.css';
import './team-navigation.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type PendingMessage = { id: string; memberId: string; runId: string; key: string; text: string; questionId?: string };
type TeamTab = 'runs' | 'teams';
type MessageInput = { recipient: string; replyId: string; mode: string };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const label = (value: unknown): string => typeof value === 'string' ? value : '';
const status = (value: string) => ({ queued: '等待工作', running: '工作中', completed: '本轮已有产出', partial: '部分分工未完成', paused: '已暂停',
  awaiting_review: '成果待审阅', awaiting_input: '等待回答', awaiting_user: '等待处理', waiting_dependency: '等待前置分工', blocked_dependency: '前置工作未完成',
  blocked_unknown: '原操作结果待核对', failed: '本轮未完成', cancelled: '已停止', interrupted: '已中断', awaiting_approval: '等待授权', needs_confirmation: '成果待确认', reconciling: '核对原操作', stopping: '正在停止' } as Record<string, string>)[value] || value;
const blankMember = (id: string, index: number) => teamProductionEditorMember({ id, name: `成员 ${index + 1}`, role: '', model: '', runner: 'model' });
const readingKey = (value: Data | null, tab: TeamTab) => value?.kind === 'run' ? `run:${value.run.id}`
  : value?.kind === 'definition' ? `definition:${value.definition.id}` : `list:${tab}`;
const memberModelLabel = (member: Data): string => {
  const selection = object(member.memberModelSelection), route = object(selection.memberRoute);
  if (label(route.requestedAlias)) return route.requestedAlias;
  const target = object(selection.memberTarget || member.modelTarget);
  if (target.kind === 'exact') return label(target.modelId);
  if (target.kind === 'group') return '模型组';
  return selection.memberTarget || member.modelTarget ? '' : label(member.model);
};

/** Uses the adopted BoardUI fields/dialogs and Affect answer presentation on the original team worksite. */
export function TeamsWorkspace({ state, run, registerTransientBack, alreadyShownError = '' }: { state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; alreadyShownError?: string }) {
  const [catalog, setCatalog] = useState<Data>({}), [tab, setTab] = useState<TeamTab>('teams');
  const [catalogReady, setCatalogReady] = useState(false), [editorExpanded, setEditorExpanded] = useState<string[]>([]);
  const [queries, setQueries] = useState<Record<TeamTab, string>>({ runs: '', teams: '' });
  const query = queries[tab], setQuery = (value: string) => setQueries(old => ({ ...old, [tab]: value }));
  const [reading, setReading] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [editor, setEditor] = useState<Data | null>(null);
  const [phoneSelection, setPhoneSelection] = useState<Data | null>(null), [phoneChoices, setPhoneChoices] = useState<Record<string, TeamPhoneChoice>>({});
  const [externalReady, setExternalReady] = useState<Record<string, boolean>>({}), [memberProbes, setMemberProbes] = useState<Record<string, Data>>({});
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [acknowledged, setAcknowledged] = useState(false);
  const [goal, setGoal] = useState(''), [rounds, setRounds] = useState('1'), [recipient, setRecipient] = useState(''), [replyId, setReplyId] = useState(''), [mode, setMode] = useState('discussion');
  const [composeOpen, setComposeOpen] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, string>>({}), [uncertainByRun, setUncertainByRun] = useState<Record<string, PendingMessage>>({});
  const [expandedMembers, setExpandedMembers] = useState<Record<string, string[]>>({}), [expandedItems, setExpandedItems] = useState<Record<string, string[]>>({});
  const uncertain = uncertainByRun[String(reading?.run?.id || '')] || null;
  const rememberUncertain = (id: string, value: PendingMessage | null) => setUncertainByRun(old => {
    const next = { ...old }; if (value) next[id] = value; else delete next[id]; return next;
  });
  const alive = useRef(true), requestGeneration = useRef(0), entered = useRef(false), currentReading = useRef<Data | null>(null);
  const backgrounded = useRef(document.visibilityState !== 'visible'), currentReview = useRef<Data | null>(null);
  const currentPhoneSelection = useRef<Data | null>(null);
  const materialBack = useRef<(() => boolean) | null>(null);
  const workspaceActive = state.webWorkspaceSection === 'teams' && !state.webWorkspaceTaskOpen;
  const workspaceWindow = useRef({ active: false, revision: Number(state.webWorkspaceResumeRevision || 0) });
  const ownsNativeWindow = () => {
    const view = object(bridge.getState().view);
    return view.section === 'teams' && !view.taskId;
  };
  const root = useRef<HTMLElement>(null), positions = useRef(new Map<string, number>());
  const listSelection = useRef<Partial<Record<TeamTab, string>>>({});
  const messageInputs = useRef(new Map<string, MessageInput>()), startInputs = useRef(new Map<string, { goal: string; rounds: string }>());
  const currentInput = useRef({ recipient, replyId, mode, goal, rounds });
  const viewKey = readingKey(reading, tab), shownView = useRef(viewKey);
  currentReading.current = reading; currentReview.current = review; currentPhoneSelection.current = phoneSelection;
  currentInput.current = { recipient, replyId, mode, goal, rounds };
  const rememberView = () => {
    const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroll) positions.current.set(shownView.current, scroll.scrollTop);
    const previous = currentReading.current;
    const input = currentInput.current;
    if (previous?.kind === 'run') messageInputs.current.set(previous.run.id, { recipient: input.recipient, replyId: input.replyId, mode: input.mode });
    else if (previous?.kind === 'definition') startInputs.current.set(previous.definition.id, { goal: input.goal, rounds: input.rounds });
  };
  useLayoutEffect(() => {
    const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (!scroll) return;
    const previous = shownView.current, position = positions.current.get(viewKey) || 0;
    shownView.current = viewKey;
    const restore = () => { scroll.scrollTop = position; };
    restore();
    if (previous !== viewKey) {
      const target = reading ? root.current?.querySelector<HTMLElement>('.pw-team-reading h2')
        : !previous.startsWith('list:') ? Array.from(root.current?.querySelectorAll<HTMLButtonElement>('[data-team-list-id]') || [])
          .find(node => node.dataset.teamListId === listSelection.current[tab]) : undefined;
      target?.focus({ preventScroll: true });
    }
    const frame = requestAnimationFrame(restore);
    const remember = () => positions.current.set(viewKey, scroll.scrollTop);
    scroll.addEventListener('scroll', remember, { passive: true });
    return () => { cancelAnimationFrame(frame); scroll.removeEventListener('scroll', remember); };
  }, [viewKey]);
  const invoke = async (action: string, args: Data = {}, accept?: (result: Data) => void, rejected?: (failure: unknown) => void) => {
    if (entered.current || backgrounded.current || !ownsNativeWindow()) return; entered.current = true; const generation = ++requestGeneration.current; setBusy(true); setError(''); setNotice('');
    try { const result = object(await run(action, args)); if (alive.current && generation === requestGeneration.current) accept?.(result); return result; }
    catch (failure) { if (alive.current && generation === requestGeneration.current) { setError(failure instanceof Error ? failure.message : String(failure)); rejected?.(failure); } }
    finally { if (generation === requestGeneration.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const release = (value: Data | null, kind = 'viewHandle') => {
    // Native leaveConnection already retires these handles. Cleanup is not a
    // user operation and must not overwrite a successful read or an action error.
    if (value?.[kind] && ownsNativeWindow()) void bridge.command('teams.closeReading', { [kind]: value[kind] }).catch(() => {});
  };
  const acceptReading = (next: Data) => {
    rememberView();
    release(currentReading.current); setReading(next);
    if (next.kind === 'run' && next.run?.id) listSelection.current.runs = next.run.id;
    if (next.kind === 'definition' && next.definition?.id) listSelection.current.teams = next.definition.id;
    if (next.kind === 'run' && next.run?.id) setCatalog(old => ({ ...old, runs: [next.run, ...rows(old.runs).filter(value => value.id !== next.run.id)] }));
    if (next.kind === 'definition' && next.definition?.id) setCatalog(old => ({ ...old, teams: [next.definition, ...rows(old.teams).filter(value => value.id !== next.definition.id)] }));
    if (next.kind === 'run' && next.run?.id !== currentReading.current?.run?.id) {
      const saved = messageInputs.current.get(next.run.id);
      setComposeOpen(!!saved?.replyId); setRecipient(saved?.recipient ?? next.run.leaderId ?? ''); setReplyId(saved?.replyId || ''); setMode(saved?.mode || 'discussion');
    } else if (next.kind === 'definition' && next.definition?.id !== currentReading.current?.definition?.id) {
      const saved = startInputs.current.get(next.definition.id);
      setGoal(saved?.goal || ''); setRounds(saved?.rounds || '1');
    }
    const pending = uncertainByRun[String(next.run?.id || '')];
    if (pending && teamMessageRecorded(next, pending)) {
      setDrafts(old => old[pending.key] === pending.text ? { ...old, [pending.key]: '' } : old); rememberUncertain(pending.runId, null); setNotice('已从原运行记录核对消息已保存，消费状态见原记录。');
    }
  };
  useEffect(() => {
    if (!composeOpen) return;
    const frame = requestAnimationFrame(() => root.current?.querySelector<HTMLTextAreaElement>('.pw-team-compose textarea')?.focus());
    return () => cancelAnimationFrame(frame);
  }, [composeOpen, replyId]);
  const acceptCatalog = (value: Data) => { setCatalog(value); setCatalogReady(true); };
  const load = () => { void invoke('teams.snapshot', {}, acceptCatalog); };
  const readRun = (id: string) => { void invoke('teams.readRun', { runId: id }, result => { if (result.reading?.run?.id !== id) throw new Error('原团队运行身份不匹配'); acceptReading(result.reading); }); };
  const readDefinition = (id: string) => { void invoke('teams.readDefinition', { id }, value => { if (value.definition?.id !== id) throw new Error('团队配置身份不匹配，请刷新目录'); acceptReading(value); }); };
  const back = () => { rememberView(); release(reading); setTab(reading?.kind === 'run' ? 'runs' : 'teams'); setReading(null); setComposeOpen(false); setReplyId(''); setEditor(null); setError(''); setNotice(''); };
  const switchTab = (value: TeamTab) => { if (busy) return; rememberView(); setTab(value); setError(''); setNotice(''); };
  const closeReview = () => { const original = currentReview.current; currentReview.current = null; release(original, 'reviewHandle'); setReview(null); setAcknowledged(false); };
  const closePhoneSelection = () => { const original = currentPhoneSelection.current; currentPhoneSelection.current = null; release(original, 'phoneHandle'); setPhoneSelection(null); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; requestGeneration.current++; release(currentReading.current); release(currentReview.current, 'reviewHandle'); release(currentPhoneSelection.current, 'phoneHandle'); }; }, []);
  const restoreWorkspace = () => {
    if (backgrounded.current || !ownsNativeWindow()) return;
    const original = currentReading.current, generation = ++requestGeneration.current;
    closeReview(); closePhoneSelection();
    entered.current = true; setBusy(true); setReview(null); setAcknowledged(false); setError('');
    setReading(original ? { ...original, viewHandle: '' } : null);
    void (async () => {
      const fresh = object(await run('teams.snapshot'));
      if (!alive.current || generation !== requestGeneration.current) return;
      acceptCatalog(fresh);
      if (original?.kind === 'run') {
        const value = object(await run('teams.readRun', { runId: original.run.id }));
        if (alive.current && generation === requestGeneration.current && value.reading?.run?.id === original.run.id) acceptReading(value.reading);
      } else if (original?.kind === 'definition') {
        const value = object(await run('teams.readDefinition', { id: original.definition.id }));
        if (alive.current && generation === requestGeneration.current && value.definition?.id === original.definition.id) acceptReading(value);
      }
    })().catch(failure => { if (alive.current && generation === requestGeneration.current) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (generation === requestGeneration.current) { entered.current = false; if (alive.current) setBusy(false); } });
  };
  useEffect(() => {
    const revision = Number(state.webWorkspaceResumeRevision || 0);
    const previous = workspaceWindow.current;
    workspaceWindow.current = { active: workspaceActive, revision };
    if (!workspaceActive) {
      if (previous.active) {
        requestGeneration.current++; entered.current = false; setBusy(false);
        closeReview(); closePhoneSelection(); setMemberProbes({}); setExternalReady({});
        setReading(value => value ? { ...value, viewHandle: '' } : null);
      }
      return;
    }
    if (!previous.active || revision !== previous.revision) restoreWorkspace();
  }, [workspaceActive, state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const hide = () => {
      backgrounded.current = true; requestGeneration.current++; entered.current = false;
      setBusy(false); closeReview(); closePhoneSelection(); setMemberProbes({}); setExternalReady({});
      setReading(value => value ? { ...value, viewHandle: '' } : null);
    };
    const visibility = () => {
      if (document.visibilityState !== 'visible') hide();
      else if (backgrounded.current) { backgrounded.current = false; restoreWorkspace(); }
    };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  useEffect(() => { registerTransientBack(() => { if (materialBack.current?.()) return true; if (busy) return true; if (review) { closeReview(); return true; } if (phoneSelection) { closePhoneSelection(); return true; } if (editor) { setEditor(null); return true; } if (reading) { back(); return true; } return false; }); return () => registerTransientBack(null); }, [reading, review, phoneSelection, editor, busy, registerTransientBack]);
  const definition = reading?.kind === 'definition' ? object(reading.definition) : null;
  const definitionReady = workspaceActive && (!definition || !!reading?.viewHandle);
  const current = reading?.kind === 'run' ? object(reading.run) : null;
  const latest = current ? object(object(state.teamRuns)[current.id]) : {};
  const changed = !!current && latest.id === current.id && (latest.generation !== current.generation || latest.revision > current.revision);
  const members = rows(current?.members), messages = rows(reading?.messages);
  const workItems = rows(object(current?.workPlan).items);
  const hasWorkPlan = !!current?.workPlan;
  const reply = messages.find(message => message.messageId === replyId);
  const draftKey = `${current?.id || ''}:${recipient}:${replyId}:${mode}`, text = drafts[draftKey] || '';
  const canMutate = workspaceActive && !!current && !!reading?.viewHandle && !busy && !changed && current.status !== 'cancelled' && !uncertain;
  const name = (id: string) => members.find(member => member.id === id)?.name || id;
  const reviewResult = (value: Data) => {
    if (value.operation === 'phone_scope') { setPhoneSelection(value); setPhoneChoices(Object.fromEntries(rows(value.view?.members).map(member => [member.memberId, teamPhoneChoice()]))); }
    else { setReview(value); setAcknowledged(false); }
  };
  const control = (command: string, memberId?: string) => { if (!reading || !canMutate) return; void invoke('teams.control', { viewHandle: reading.viewHandle, command, ...(memberId ? { memberId } : {}) }, result => { if (result.confirmed !== true) throw new Error('原操作尚未确认'); if (result.reading) acceptReading(result.reading); setNotice('已收到原运行的操作回执。'); }); };
  const send = () => {
    if (!canMutate || !reading || !text.trim() || text.length > 4000 || !members.some(member => member.id === recipient && !member.exited)) return;
    const original = { id: crypto.randomUUID(), memberId: recipient, runId: current!.id, key: draftKey, text, ...(reply?.kind === 'assignment_question' ? { questionId: reply.questionId } : {}) };
    rememberUncertain(original.runId, original);
    void invoke(reply?.kind === 'assignment_question' ? 'teams.answerAssignment' : 'teams.sendMessage', { viewHandle: reading.viewHandle, memberId: recipient, messageId: original.id, text: text.trim(), mode, quoteMessageId: replyId }, result => {
      if (!teamMessageConfirmed(result, original)) throw new Error('原消息保存回执尚待核对，请刷新原记录。');
      setDrafts(old => old[original.key] === original.text ? { ...old, [original.key]: '' } : old); rememberUncertain(original.runId, null); setNotice('已保存到原运行；进入模型与实际消费分别查看记录。');
      if (result.reading) acceptReading(result.reading); else { setReading(value => value ? { ...value, viewHandle: '' } : null); setNotice('原回答已保存，请刷新原运行查看后续状态。'); }
    }, failure => { if (['not_sent', 'not_accepted'].includes(object(failure).code)) { rememberUncertain(original.runId, null); setNotice('这次输入未提交，草稿保留；请刷新原运行后再操作。'); } });
  };
  const beginEditor = (team: Data | null) => {
    const members = team ? rows(team.members).map(teamProductionEditorMember) : [blankMember('member-0', 0), blankMember('member-1', 1)];
    setError(''); setNotice(''); setExternalReady({}); setEditorExpanded(members.slice(0, 1).map(member => member.id));
    setEditor({ name: team?.name || '', mode: team?.mode || 'collaborate', leaderId: team?.leaderId || members[0].id, members });
  };
  const addMember = () => {
    if (!editor || busy || editor.members.length >= 4) return;
    const member = blankMember(`member-${crypto.randomUUID().slice(0, 8)}`, editor.members.length);
    setEditor({ ...editor, members: [...editor.members, member] }); setEditorExpanded(old => [...old, member.id]);
    requestAnimationFrame(() => document.getElementById(`team-editor-member-${member.id}`)?.scrollIntoView({ block: 'nearest' }));
  };
  const revealMember = (id: string) => {
    setEditorExpanded(old => old.includes(id) ? old : [...old, id]);
    requestAnimationFrame(() => {
      const section = document.getElementById(`team-editor-member-${id}`);
      section?.scrollIntoView({ block: 'nearest' });
      section?.querySelector<HTMLElement>('input:not(:disabled),textarea:not(:disabled),button:not(:disabled)')?.focus({ preventScroll: true });
    });
  };
  const updateMember = (id: string, changes: Data) => {
    if (['runner', 'device', 'model', 'modeId'].some(key => Object.hasOwn(changes, key))) setExternalReady(old => ({ ...old, [id]: false }));
    setEditor(value => value ? { ...value, members: rows(value.members).map(member => member.id === id ? { ...member, ...changes,
      ...(['runner', 'device'].some(key => Object.hasOwn(changes, key)) ? { production: resetTeamProductionAcknowledgments(member.production || teamProductionDraft()) } : {}) } : member) } : null);
  };
  const phoneTargets = rows(catalog.phoneTargets);
  const computerProfiles = rows({ ...object(object(state.features).remote), ...object(object(state.ownerSnapshots).remote) }.profiles);
  const computerLabel = (member: Data) => `${computerProfiles.find(profile => profile.id === member.device?.profileId)?.name || member.device?.profileId || '原电脑'} · ${member.device?.environmentId || '环境待选择'}`;
  const phoneMembers = rows(phoneSelection?.view?.members);
  const hasPhoneMembers = rows(definition?.members).some(member => member.runner === 'phone_agent');
  const fixedDiscussionRounds = definition?.mode === 'debate' && !!definition.discussionFormat && definition.discussionFormat !== 'classic';
  const choosePhoneScope = (member: Data, id: string) => setPhoneChoices(value => ({ ...value, [member.memberId]: teamPhoneChoice(rows(member.options).find(option => option.optionId === id)) }));
  const changePhoneBudget = (memberId: string, field: keyof TeamPhoneChoice, value: string) => setPhoneChoices(old => ({ ...old, [memberId]: { ...old[memberId], [field]: value } }));
  const reviewPhoneStart = () => {
    if (!phoneSelection || busy) return;
    try {
      const members = teamPhoneSelections(object(phoneSelection.view), phoneChoices);
      const phoneHandle = phoneSelection.phoneHandle; currentPhoneSelection.current = null; setPhoneSelection(null);
      void invoke('teams.reviewPhoneStart', { phoneHandle, members }, reviewResult);
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const entries = rows(catalog[tab === 'runs' ? 'runs' : 'teams']).filter(row => `${row.goal || ''} ${row.name || ''} ${row.teamName || ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const attentionStates = new Set(['awaiting_review', 'awaiting_input', 'awaiting_user', 'awaiting_approval', 'needs_confirmation', 'blocked_unknown', 'unknown', 'reconciling', 'blocked_dependency', 'failed', 'partial', 'interrupted']);
  const finishedStates = new Set(['completed', 'cancelled']);
  const directoryGroups = tab === 'teams' ? [{ id: 'definitions', title: '选择团队，开始一次协作', entries }] : [
    { id: 'attention', title: '需要处理', entries: entries.filter(row => attentionStates.has(row.status)) },
    { id: 'active', title: '当前工作', entries: entries.filter(row => !attentionStates.has(row.status) && !finishedStates.has(row.status)) },
    { id: 'history', title: '已结束的工作', entries: entries.filter(row => finishedStates.has(row.status)) },
  ];
  const pendingQuestions = messages.filter(message => message.kind === 'assignment_question' && message.state === 'pending');
  const jumpToRunSection = (section: 'messages' | 'members' | 'materials' | 'compose') => {
    if (section === 'compose') setComposeOpen(true);
    requestAnimationFrame(() => {
      const target = root.current?.querySelector<HTMLElement>(`[data-team-section="${section}"]`);
      target?.scrollIntoView({ block: 'start' });
      if (section === 'compose') target?.querySelector<HTMLTextAreaElement>('textarea')?.focus({ preventScroll: true });
      else target?.focus({ preventScroll: true });
    });
  };
  const editorMemberIssue = (member: Data): string => {
    if (!label(member.name).trim()) return '填写成员名称';
    if (member.name.length > 48) return '成员名称最多 48 字';
    if (!label(member.role).trim()) return '填写成员职责';
    if (member.role.length > 1200) return '成员职责最多 1200 字';
    if (label(member.model).length > 200) return '模型 ID 最多 200 字';
    if (member.runner === 'phone_agent') return !label(member.assignment).trim() ? '填写手机具体分工'
      : member.assignment.length > 2000 ? '手机分工最多 2000 字' : !teamPhoneTargetKey(member.device) ? '选择工作手机' : '';
    if (member.runner === 'model') return '';
    if (!member.device?.profileId || !member.device?.environmentId) return '选择电脑与工作环境';
    if (!isTeamHarness(member.runner)) return '选择成员执行器';
    if (member.runner !== 'codex_app_server' && member.externalExecutionRiskAcknowledged !== true) return '确认电脑执行权限与费用边界';
    return externalReady[member.id] ? '' : '核对这名成员的执行器';
  };
  const incompleteMembers = rows(editor?.members).filter(member => !!editorMemberIssue(member));
  const evidenceDebate = editor?.mode === 'debate' && (!definition?.discussionFormat || definition.discussionFormat !== 'classic');
  const discussionError = evidenceDebate && (rows(editor?.members).length < 2 || rows(editor?.members).some(member => member.runner !== 'model' || rows(member.dependsOn).length > 0))
    ? '取证与讨论需要至少两名独立的分析成员。电脑、手机或有前置依赖的分工请使用“共同工作”。' : '';
  let editorPayload: Data | null = null, productionError = '';
  if (editor) try { editorPayload = teamDefinitionPayload(editor, catalog); }
  catch (failure) { productionError = failure instanceof Error ? failure.message : String(failure); }
  const refreshReading = <IconButton className="pw-utility-icon" icon={RiRefreshLine} aria-label="刷新团队记录" title="刷新团队记录" disabled={busy} onClick={() => current ? readRun(current.id) : definition ? void invoke('teams.readDefinition', { id: definition.id }, acceptReading) : load()} />;
  return <section ref={root} className="pw-team-workspace pw-depth-collaboration" data-team-layer={reading ? 'detail' : 'list'} data-team-view={viewKey} inert={!workspaceActive} aria-busy={!workspaceActive || busy}>
    <div className="pw-team-loading">{(!workspaceActive || busy) && <ProgressBar aria-label={workspaceActive ? '正在读取原团队回执' : '正在进入团队工作区'} isIndeterminate showValueLabel={false} size="sm" />}</div>
    {error && error !== alreadyShownError && !editor && !review && !phoneSelection && <p className="pw-secondary-error" role="alert">{error}</p>}{notice && <p className="pw-field-hint" role="status">{notice}</p>}
    {reading && <nav className="pw-team-location" aria-label="团队内导航"><Button variant="ghost" leadingIcon={RiArrowLeftLine} disabled={busy} onClick={back}>{current ? '返回协作记录' : '返回我的团队'}</Button><span>{current ? current.teamName || '这次协作' : '成员与启动'}</span></nav>}
    {!reading && <>
      <header className="pw-team-directory-heading"><div><h2>{tab === 'teams' ? '我的团队' : '协作记录'}</h2><p>{tab === 'teams' ? '保存成员与分工，按不同目标重复使用。' : '跟进正在进行的协作，查看消息、分工和成果。'}</p></div><div className="pw-team-directory-actions"><Button leadingIcon={RiAddLine} disabled={busy || !catalogReady} onClick={() => beginEditor(null)}>新建团队</Button><IconButton className="pw-utility-icon" icon={RiRefreshLine} aria-label="刷新团队记录" title="刷新团队记录" disabled={busy} onClick={load} /></div></header>
      <Tabs selectedKey={tab} onSelectionChange={value => { if (value === 'runs' || value === 'teams') switchTab(value); }} className="pw-team-tabs"><div className="pw-object-toolbar"><TabList aria-label="团队视图"><Tab id="teams">我的团队{catalogReady ? ` · ${rows(catalog.teams).length}` : ''}</Tab><Tab id="runs">协作记录{catalogReady ? ` · ${rows(catalog.runs).length}` : ''}</Tab></TabList>{tab === 'runs' && <Button variant="secondary" leadingIcon={RiPlayLine} disabled={busy || !catalogReady} onClick={() => { switchTab('teams'); setQueries(old => ({ ...old, teams: '' })); }}>选择团队开始</Button>}</div><TabPanel id={tab}><SearchField fieldClassName="pw-collaboration-search" shape="rounded" className="pw-secondary-search" data-input-size="medium" aria-label={tab === 'runs' ? '搜索协作记录' : '搜索我的团队'} placeholder={tab === 'runs' ? '搜索目标或团队' : '搜索团队'} value={query} onChange={setQuery} />
      <div className="pw-collaboration-directory">{directoryGroups.filter(group => group.entries.length > 0).map(group => <section className="pw-collaboration-group" key={group.id} aria-label={group.title}>
        <header className="pw-collaboration-group-heading"><h3>{group.title}</h3><span>{group.entries.length}</span></header>
        <div className="pw-object-list">{group.entries.map(row => <Button key={row.id} data-team-list-id={row.id} variant="ghost" trailingIcon={RiArrowRightSLine} className="pw-object-row h-auto whitespace-normal" disabled={busy} onClick={() => { listSelection.current[tab] = row.id; if (tab === 'runs') readRun(row.id); else readDefinition(row.id); }}><span className="pw-object-row-copy"><span className="pw-object-row-title">{row.goal || row.name || row.teamName || '团队工作'}</span><small>{[tab === 'runs' ? row.teamName : undefined, row.status ? status(row.status) : `${rows(row.members).length} 名成员`, tab === 'teams' ? row.mode === 'produce' ? '项目文件协作' : row.mode === 'debate' ? '取证与讨论' : '共同工作' : undefined].filter(Boolean).join(' · ')}</small>{tab === 'teams' && <small className="pw-team-roster-preview">{rows(row.members).map(member => member.name).filter(Boolean).join(' · ')}</small>}</span></Button>)}</div>
      </section>)}</div>
      {!entries.length && <section className="pw-team-empty" aria-label="团队目录提示"><h3>{!catalogReady ? busy ? '正在读取团队' : '团队目录尚未读取' : query.trim() ? '没有匹配的记录' : tab === 'teams' ? '建立第一个团队' : '还没有协作记录'}</h3><p>{!catalogReady ? '读取成功后，会在这里显示已保存的团队和协作。' : query.trim() ? '换一个名称或清除搜索，查看当前目录。' : tab === 'teams' ? '为成员设置职责，保存后填写本次目标即可开始。' : rows(catalog.teams).length ? '选择一个已保存的团队，开始第一次协作。' : '先创建团队，再让成员围绕同一个目标分工。'}</p>{!busy && (!catalogReady ? <Button variant="secondary" onClick={load}>重新读取</Button> : query.trim() ? <Button variant="secondary" onClick={() => setQuery('')}>清除搜索</Button> : tab === 'runs' && rows(catalog.teams).length ? <Button onClick={() => { switchTab('teams'); setQueries(old => ({ ...old, teams: '' })); }}>选择团队开始</Button> : <Button leadingIcon={RiAddLine} onClick={() => beginEditor(null)}>新建团队</Button>)}</section>}
    </TabPanel></Tabs></>}
    {definition && <article className="pw-team-reading pw-team-definition"><header className="pw-object-toolbar"><div><h2 tabIndex={-1}>{definition.name}</h2><p>{definition.mode === 'produce' ? '原项目文件协作' : definition.mode === 'debate' ? '取证与讨论' : hasPhoneMembers ? '手机分工与共同工作' : '共同分析'}</p></div>{refreshReading}</header>
      <section className="pw-team-start" aria-label="开始下一次协作"><h3>下一次共同工作</h3>{definition.mode === 'produce' ? <p className="pw-muted">文件团队沿原项目任务的计划、文件范围和预算启动；这里保留其成员与既有工作记录。</p> : <><TextAreaField label="这次共同目标" value={goal} onChange={setGoal} maxLength={8000} showCount isDisabled={busy} />{!hasPhoneMembers && !fixedDiscussionRounds && <SelectField label="讨论轮数" value={rounds} options={[{ id: '1', label: '一轮' }, { id: '2', label: '两轮' }]} onChange={setRounds} isDisabled={busy}  />}{fixedDiscussionRounds && <p className="pw-field-hint">取证与讨论使用固定流程：独立观点、相互质疑、回应与综合结论。启动时按两轮额度核对。</p>}{hasPhoneMembers && <p className="pw-field-hint">手机成员按各自分工与前置依赖工作。本机可同时使用至多四个后台屏幕；启动前逐成员核对应用范围与预算。</p>}<PressButton label={hasPhoneMembers ? '选择手机范围并开始' : '核对并开始协作'} disabled={busy || !definitionReady || !goal.trim() || goal.length > 8000 || !!state.modelSelectionBusy} onClick={() => void invoke('teams.reviewStart', { viewHandle: reading!.viewHandle, goal, rounds: hasPhoneMembers ? 1 : fixedDiscussionRounds ? 2 : Number(rounds) }, reviewResult)} /></>}</section>
      <section className="pw-team-members"><h3>成员与职责</h3>{rows(definition.members).map(member => <div key={member.id} className="pw-source-record"><h4>{member.name}{member.id === definition.leaderId ? ' · 负责人' : ''}</h4><p>{member.role}</p>
        {member.runner === 'phone_agent' ? <><small>{teamPhoneTargetLabel(member.device, phoneTargets)}</small><p>{member.assignment}</p></>
          : member.runner === 'model' || !member.runner ? <small>模型：{memberModelLabel(member) || '沿用实际启动时的主模型'} · 本机模型连接</small>
          : <><small>执行器：{harnessWorkerLabel(undefined, member.runner)} · 模型：{member.model || '执行器默认'}</small><p className="pw-field-hint">电脑：{computerLabel(member)}</p>
            <p className="pw-field-hint">开始协作前，核对这份已保存配置的实际执行器。</p><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.probeMember', { viewHandle: reading!.viewHandle, memberId: member.id }, value => setMemberProbes(old => ({ ...old, [`${definition.id}:${member.id}`]: { definition: JSON.stringify(member), value: object(value.result || value) } })))}>核对原执行器</Button>
            {memberProbes[`${definition.id}:${member.id}`]?.definition === JSON.stringify(member) && <HarnessEvidence probe={memberProbes[`${definition.id}:${member.id}`].value} id={`saved-member-evidence-${member.id}`} />}
          </>}
      </div>)}</section>
      <div className="pw-team-definition-settings"><Button variant="secondary" leadingIcon={RiEditLine} disabled={busy || !definitionReady} onClick={() => beginEditor(definition)}>编辑下次工作的团队</Button><Accordion variant="divided"><AccordionItem id="definition-management" title="管理团队配置"><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.reviewRemove', { viewHandle: reading!.viewHandle }, reviewResult)}>移除团队配置</Button></AccordionItem></Accordion></div>

    </article>}
    {current && <article className="pw-team-reading pw-team-run"><header className="pw-object-toolbar"><div><h2 tabIndex={-1}>{current.goal || current.teamName}</h2><p className="pw-detail-status">{status(current.status)}{current.phase ? ` · ${current.phase}` : ''}</p></div>{refreshReading}</header>
      <nav className="pw-team-section-links" aria-label="这次协作的内容"><Button variant="ghost" onClick={() => jumpToRunSection('messages')}>沟通 · {messages.length}</Button><Button variant="ghost" onClick={() => jumpToRunSection('members')}>成员 · {members.length}</Button><Button variant="ghost" onClick={() => jumpToRunSection('materials')}>共享资料</Button><Button variant="secondary" disabled={!canMutate} onClick={() => jumpToRunSection('compose')}>给成员补充</Button></nav>
      {changed && <p role="status">有新的运行记录。刷新后再发送补充或操作，当前阅读位置保留。</p>}
      <div className="pw-team-run-controls">{reading?.taskId && <Button variant="secondary" trailingIcon={RiExternalLinkLine} onClick={() => void run('tasks.open', { id: reading.taskId }).catch(() => {})}>查看原任务与审批</Button>}<div className="pw-form-actions"><IconButton className="pw-utility-icon" icon={RiPauseLine} aria-label="暂停全队" title="暂停全队" disabled={!canMutate} onClick={() => control('pause')} /><IconButton className="pw-utility-icon" icon={RiPlayLine} aria-label="沿原额度继续" title="沿原额度继续" disabled={!canMutate || hasWorkPlan} onClick={() => control('resume')} /><Button variant="ghost" disabled={!canMutate} onClick={() => reviewResult({ operation: 'cancel', viewHandle: reading!.viewHandle, runId: current.id })}>停止这次协作</Button></div></div>
      {hasWorkPlan && <p className="pw-field-hint">继续工作或使用待处理消息时，请在原任务中核对具体工作项；不会按成员猜测要继续的分工。</p>}
      {pendingQuestions.length > 0 && <div className="pw-collaboration-attention" role="status"><div><strong>{pendingQuestions.length} 条问题等待你的回答</strong><span>回答会留在这次原协作中</span></div><Button variant="ghost" trailingIcon={RiArrowRightSLine} onClick={() => { const question = root.current?.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(pendingQuestions[0].messageId)}"]`); question?.focus({ preventScroll: true }); question?.scrollIntoView({ block: 'start' }); }}>查看原问题</Button></div>}
      <div className="pw-team-run-layout"><div className="pw-team-main">
      {current.summary && <section className="pw-team-summary" aria-label="团队当前结果"><h3 className="pw-team-section-title">当前成果</h3>{current.summaryStale && <p className="pw-muted">这份总结待更新</p>}<AIAnswer text={current.summary} /></section>}
      <section className="pw-team-messages" data-team-section="messages" tabIndex={-1} aria-label="原团队消息"><header className="pw-collaboration-group-heading"><h3>团队沟通</h3><span>{messages.length} 条消息</span></header>{messages.map(message => <article className="pw-source-record pw-team-message" key={message.messageId} data-message-id={message.messageId} data-reply-selected={replyId === message.messageId || undefined} tabIndex={-1}>
        <header className="pw-team-message-heading"><h3>{message.role === 'user' ? '你' : message.memberSnapshot?.name || name(message.memberId || '') || '团队'}{message.recipientMemberId ? ` → ${name(message.recipientMemberId)}` : ''}</h3>{message.kind === 'assignment_question' && message.state === 'pending' && <span>等待你的回答</span>}</header>
        <AIAnswer text={String(message.content || '')} actions={(message.kind !== 'assignment_question' || message.state === 'pending') && <Button variant="ghost" disabled={!canMutate} aria-pressed={replyId === message.messageId} onClick={() => { setComposeOpen(true); setReplyId(message.messageId); if (members.some(member => member.id === message.memberId && !member.exited)) setRecipient(message.memberId); }}>{message.kind === 'assignment_question' ? '回答原问题' : '引用回复'}</Button>} />
        <p className="pw-muted">{teamMessageDelivery(current, message)}</p>{message.answer && <div className="pw-team-saved-answer"><span>已保存的回答</span><p>{message.answer}</p></div>}
      </article>)}{!messages.length && <p className="pw-secondary-empty">这次协作还没有消息。</p>}</section>
      <Accordion variant="divided" className="pw-team-compose-disclosure" expandedKeys={composeOpen || !!uncertain ? ['team-compose'] : []} onExpandedChange={keys => setComposeOpen(keys.has('team-compose'))}><AccordionItem id="team-compose" title="补充给成员" description={reply ? '正在回复所选消息' : text ? '有未发送内容' : undefined}>
      <section className="pw-team-compose" data-team-section="compose" aria-label="给成员补充">
      {reply && <div className="pw-team-reply-context"><header><span>{reply.kind === 'assignment_question' ? '回答原问题' : '引用原消息'} · {reply.memberSnapshot?.name || name(reply.memberId || '') || '团队'}</span><Button variant="ghost" disabled={busy || !!uncertain} onClick={() => setReplyId('')}>取消引用</Button></header><AIAnswer text={String(reply.content || '')} showActions={false} /></div>}
      <SelectField label="收件成员" value={recipient} options={members.filter(member => !member.exited).map(member => ({ id: member.id, label: member.name }))} onChange={setRecipient} isDisabled={busy || !!uncertain || reply?.kind === 'assignment_question'} />
      {reply?.kind !== 'assignment_question' && <SelectField label="补充方式" value={mode} options={[{ id: 'discussion', label: '讨论补充' }, { id: 'queue', label: '排入下一安全工作点' }]} onChange={setMode} isDisabled={busy || !!uncertain} />}
      <TextAreaField label={reply?.kind === 'assignment_question' ? '回答这条原问题' : '发给所选成员'} value={text} onChange={value => setDrafts(old => ({ ...old, [draftKey]: value }))} maxLength={4000} showCount isDisabled={busy || !!uncertain} hint="补充消息不改变任务权限或预算。" />
      <PressButton label={reply?.kind === 'assignment_question' ? '保存原回答' : '发送补充'} disabled={!canMutate || !text.trim() || text.length > 4000 || !recipient} onClick={send} />
      {uncertain && <p role="status">这条原消息仍待核对，草稿保留。刷新原记录核对保存结果，没有自动重复发送。</p>}
      </section>
      </AccordionItem></Accordion>

      </div><aside className="pw-team-context" aria-label="分工与资料">
      <section className="pw-team-overview" data-team-section="members" tabIndex={-1} aria-label="成员与当前工作">
        <h3 className="pw-team-section-title">成员 <span>{members.length}</span></h3>
        <Accordion key={`${current.id}:members`} variant="divided" className="pw-team-disclosures" allowsMultipleExpanded expandedKeys={new Set(expandedMembers[current.id] || [])}
          onExpandedChange={keys => setExpandedMembers(old => ({ ...old, [current.id]: Array.from(keys, String) }))}>
          {members.map(member => {
            const model = memberModelLabel(member), activeItems = workItems.filter(item => item.memberId === member.id && item.status === 'running');
            const assignment = activeItems.length ? `正在做：${activeItems.map(item => label(item.title)).filter(Boolean).join('、')}` : label(member.assignment || member.role);
            return <AccordionItem key={member.id} id={member.id} className="pw-team-member" data-team-member-id={member.id}
              title={<span className="pw-team-member-name">{member.name}{member.id === current.leaderId && <small>负责人</small>}</span>}
              description={<><span className="pw-team-member-meta" title={model || undefined}>{[model, member.exited ? '已退出' : status(member.status)].filter(Boolean).join(' · ')}</span>{assignment && <span className="pw-team-member-task">{assignment}</span>}</>}>
              <div className="pw-team-expanded-copy">
                <p>{member.assignment || member.role}</p>
                {member.runner === 'phone_agent' && <p className="pw-muted">{teamPhoneTargetLabel(member.device, phoneTargets)}</p>}
                {isTeamHarness(member.runner || '') && <><p className="pw-muted">执行器：{harnessWorkerLabel(undefined, member.runner)} · 电脑：{computerLabel(member)}</p>{member.externalSession?.sessionId && <p className="pw-muted">原会话：{member.externalSession.sessionId}</p>}</>}
                {model && <p className="pw-muted">模型：{model}</p>}
                {member.acceptance && <p>验收：{member.acceptance}</p>}
                {!!rows(member.dependsOn).length && <p>前置：{member.dependsOn.map(name).join('、')}</p>}
                <div className="pw-form-actions">{member.phoneTask?.taskId && <Button variant="ghost" onClick={() => void run('tasks.open', { id: member.phoneTask.taskId }).catch(() => {})}>查看手机任务与结果</Button>}<Button variant="ghost" disabled={!canMutate || member.exited} onClick={() => control('pause', member.id)}>暂停该成员</Button><Button variant="ghost" disabled={!canMutate || member.exited || hasWorkPlan} onClick={() => control('resume', member.id)}>继续该成员</Button><Button variant="ghost" disabled={!canMutate || member.exited || hasWorkPlan} onClick={() => control('continueMessages', member.id)}>使用该成员的待处理消息</Button></div>
              </div>
            </AccordionItem>;
          })}
        </Accordion>
      </section>
      <div data-team-section="materials" tabIndex={-1}><TeamEvidenceWorkspace key={current.id} runId={current.id} members={members} run={run} disabled={!canMutate} onBackChange={handler => { materialBack.current = handler; }} /></div>
      {!!workItems.length && <section className="pw-team-overview" aria-label="这次协作的工作项">
        <h3 className="pw-team-section-title">工作项 <span>{workItems.length}</span></h3>
        <Accordion key={`${current.id}:items`} variant="divided" className="pw-team-disclosures" allowsMultipleExpanded expandedKeys={new Set(expandedItems[current.id] || [])}
          onExpandedChange={keys => setExpandedItems(old => ({ ...old, [current.id]: Array.from(keys, String) }))}>
          {workItems.map(item => <AccordionItem key={item.workItemId} id={item.workItemId} className="pw-team-work-item" data-work-item-id={item.workItemId}
            title={<span className="pw-team-item-heading"><span>{item.title}</span><small>{status(item.status)}</small></span>}
            description={<span className="pw-team-member-meta">{name(item.memberId)}</span>}>
            <div className="pw-team-expanded-copy"><p>{item.instruction}</p>{item.acceptance && <p>验收：{item.acceptance}</p>}
              {!!rows(item.dependsOn).length && <p>前置：{item.dependsOn.map((id: string) => workItems.find(prior => prior.workItemId === id)?.title || id).join('、')}</p>}
            </div>
          </AccordionItem>)}
        </Accordion>
      </section>}
      </aside></div>
    </article>}
    <SettingsDialog isOpen={!!phoneSelection} title="这次手机分工的范围" onClose={() => { if (!busy) closePhoneSelection(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">{phoneSelection && <>
      {error && <p className="pw-secondary-error" role="alert">{error}</p>}
      <p>{phoneSelection.view?.goal}</p>
      <p className="pw-field-hint">每名成员选择自己的手机与应用范围；同一应用、同一 Android 用户仍会协调占用。</p>
      {phoneMembers.map(member => {
        const choice = phoneChoices[member.memberId] || teamPhoneChoice(), selected = rows(member.options).find(option => option.optionId === choice.optionId);
        return <section className="pw-source-record" key={member.memberId}>
          <h3>{member.name}</h3><p className="pw-muted">{teamPhoneTargetLabel(member.device, phoneTargets)}</p>
          <SelectField label="应用范围" value={choice.optionId} options={[{ id: '', label: '选择这名成员可使用的范围' }, ...rows(member.options).filter(option => option.available === true).map(option => ({ id: option.optionId, label: option.label }))]} onChange={id => choosePhoneScope(member, id)} isDisabled={busy} />
          {!rows(member.options).some(option => option.available === true) && <p role="status">{rows(member.options).find(option => option.reason)?.reason || '当前手机没有可用范围，请在设备页核对连接与应用权限后重新读取。'}</p>}
          {selected && <><p className="pw-field-hint">{teamPhoneScopeLabel(selected.resourceBinding)}</p>
            <TextField label="最多操作次数" value={choice.toolBudget} onChange={value => changePhoneBudget(member.memberId, 'toolBudget', value)} isDisabled={busy} hint={`原范围最多 ${selected.toolBudgetMax} 次`} />
            <TextField label="最多模型调用次数" value={choice.modelBudget} onChange={value => changePhoneBudget(member.memberId, 'modelBudget', value)} isDisabled={busy} hint={`原范围最多 ${selected.modelBudgetMax} 次；${member.device?.kind === 'paired_android' ? '由目标手机确认执行模型' : '使用本机当前已选的 API 模型'}`} />
            <TextField label="本次有效分钟数" value={choice.durationMinutes} onChange={value => changePhoneBudget(member.memberId, 'durationMinutes', value)} isDisabled={busy} hint={`不能晚于 ${new Date(selected.expiresAtMax).toLocaleString('zh-CN')}`} />
          </>}
        </section>;
      })}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closePhoneSelection}>返回</Button><Button disabled={busy || !phoneMembers.length || phoneMembers.some(member => !phoneChoices[member.memberId]?.optionId)} onClick={reviewPhoneStart}>核对本次范围</Button></div>
    </>}</div></SettingsDialog>
    <SettingsDialog isOpen={!!editor && !review} title={definition ? '编辑下次工作的团队' : '新建团队'} onClose={() => { if (!busy) setEditor(null); }}><div className="pw-collaboration-dialog pw-team-editor-dialog" data-collaboration-kind="TeamsWorkspace">{editor && <><div className="pw-team-editor-scroll">
      <TextField label="团队名称" value={editor.name} onChange={value => setEditor({ ...editor, name: value })} maxLength={80} isRequired isDisabled={busy} />
      <SelectField label="协作方式" value={editor.mode} options={[{ id: 'collaborate', label: '共同工作' }, ...(!rows(editor.members).some(member => member.runner === 'phone_agent') ? [{ id: 'debate', label: '取证与讨论' }, ...(catalog.writeAssignmentsConfigured === true || editor.mode === 'produce' ? [{ id: 'produce', label: '项目文件协作' }] : [])] : [])]} onChange={value => setEditor({ ...editor, mode: value })} isDisabled={busy} />
      {editor.mode === 'produce' && <p className="pw-field-hint">每名成员只修改各自负责的文件。从原项目任务交给这个团队时，仍需核对文件范围、独立远端任务与本机整合检查；保存配置不会启动工作。</p>}
      {discussionError && <p className="pw-field-error" role="status">{discussionError}</p>}
      <Accordion variant="divided" className="pw-team-editor-members" allowsMultipleExpanded expandedKeys={new Set(editorExpanded)} onExpandedChange={keys => setEditorExpanded(Array.from(keys, String))}>{rows(editor.members).map((member, index) => {
        const original = rows(definition?.members).find(row => row.id === member.id), external = !['model', 'phone_agent'].includes(member.runner || 'model');
        const targetKey = teamPhoneTargetKey(member.device), targetOptions = phoneTargets.map(target => ({ id: teamPhoneTargetKey(target), label: `${target.label}${target.available === false ? ' · 暂不可用' : ''}` }));
        if (targetKey && !targetOptions.some(target => target.id === targetKey)) targetOptions.push({ id: targetKey, label: '原手机目标 · 当前不可用' });
        return <AccordionItem key={member.id} id={member.id} title={member.name || `成员 ${index + 1}`} description={editorMemberIssue(member) || member.role || undefined}><section id={`team-editor-member-${member.id}`} className="pw-source-record pw-team-editor-member">
          <TextField label={`成员 ${index + 1} 名称`} value={member.name} onChange={value => updateMember(member.id, { name: value })} maxLength={48} isRequired isDisabled={busy} />
          <TextAreaField label="职责" value={member.role} onChange={value => updateMember(member.id, { role: value })} maxLength={1200} showCount isRequired isDisabled={busy} />
          <SelectField label="成员做什么" value={external ? 'computer' : member.runner || 'model'} options={[{ id: 'model', label: '分析与讨论' }, { id: 'computer', label: '在电脑上使用执行器工作' }, ...(editor.mode === 'collaborate' && !original?.writeFiles ? [{ id: 'phone_agent', label: '操作手机完成分工' }] : [])]}
            isDisabled={busy || !!original?.modelTarget} onChange={runner => updateMember(member.id, runner === 'phone_agent'
              ? { runner, model: '', modeId: undefined, externalExecutionRiskAcknowledged: undefined, device: { kind: 'local_phone', screenMode: 'background' }, assignment: member.assignment || member.role, dependsOn: member.dependsOn || [] }
              : runner === 'computer' ? { runner, model: '', modeId: '', device: { profileId: '', environmentId: '' }, externalExecutionRiskAcknowledged: false, assignment: undefined, dependsOn: undefined }
              : { runner, device: undefined, assignment: undefined, dependsOn: undefined, modeId: undefined, externalExecutionRiskAcknowledged: undefined })} />
          {member.runner === 'phone_agent' ? <>
            <SelectField label="在哪台手机上工作" value={targetKey} options={targetOptions} isDisabled={busy} onChange={key => { const target = phoneTargets.find(value => teamPhoneTargetKey(value) === key); if (target) updateMember(member.id, { device: teamPhoneDevice(target) }); }} hint="应用范围与预算在每次启动前核对。" />
            <TextAreaField label="具体分工" value={member.assignment || ''} onChange={value => updateMember(member.id, { assignment: value })} maxLength={2000} isDisabled={busy} />
            <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id={`phone-dependencies-${member.id}`} title="先等待哪些成员完成">{rows(editor.members).filter(row => row.id !== member.id).map(row => <CheckboxField key={row.id} isSelected={(member.dependsOn || []).includes(row.id)} isDisabled={busy} onChange={selected => updateMember(member.id, { dependsOn: selected ? [...(member.dependsOn || []), row.id] : (member.dependsOn || []).filter((id: string) => id !== row.id) })}>{row.name || row.id}</CheckboxField>)}<p className="pw-field-hint">不选择前置成员时，可与其他独立分工同时开始。</p></AccordionItem></Accordion>
          </> : external ? <TeamHarnessFields member={member} profiles={computerProfiles} run={run} disabled={busy} productionMode={editor.mode === 'produce' ? member.production?.sourceMode || 'snapshot' : undefined} onChange={changes => updateMember(member.id, changes)} onReady={ready => setExternalReady(old => old[member.id] === ready ? old : { ...old, [member.id]: ready })} />
            : <TextField label="模型 ID，留空沿用主模型" value={member.model} onChange={value => updateMember(member.id, { model: value })} maxLength={200} isDisabled={busy || !!original?.modelTarget} />}
          {editor.mode === 'produce' && <TeamProductionFields member={member} sources={object(catalog.productionSources)} disabled={busy} onChange={changes => updateMember(member.id, changes)} />}
          <Button variant="ghost" disabled={busy || editor.members.length <= 1} onClick={() => { const next = rows(editor.members).filter(row => row.id !== member.id).map(row => Array.isArray(row.dependsOn) ? { ...row, dependsOn: row.dependsOn.filter((id: string) => id !== member.id) } : row); setEditorExpanded(old => old.filter(id => id !== member.id)); setEditor({ ...editor, members: next, leaderId: editor.leaderId === member.id ? next[0].id : editor.leaderId }); }}>移除此成员</Button>
        </section></AccordionItem>;
      })}</Accordion>
      <Button variant="ghost" leadingIcon={RiAddLine} disabled={busy || editor.members.length >= 4} onClick={addMember}>添加成员 · {editor.members.length}/4</Button>
      <SelectField label="负责人" value={editor.leaderId} options={rows(editor.members).map(member => ({ id: member.id, label: member.name || member.id }))} onChange={value => setEditor({ ...editor, leaderId: value })} isDisabled={busy} />
      <p className="pw-field-hint">保存只影响后续新工作。</p>
      {productionError && <p className="pw-field-hint" role="status">{productionError}</p>}
      </div><footer className="pw-team-editor-footer">{error && <p className="pw-secondary-error" role="alert">{error}</p>}{incompleteMembers.length > 0 && <div className="pw-team-editor-missing" role="status"><span>还有 {incompleteMembers.length} 名成员需要补充</span><Button variant="ghost" disabled={busy} onClick={() => revealMember(incompleteMembers[0].id)}>{incompleteMembers[0].name || '未命名成员'}：{editorMemberIssue(incompleteMembers[0])}</Button></div>}{!editor.name.trim() && <p className="pw-field-hint">填写团队名称后保存。</p>}{busy && <p className="pw-field-hint" role="status">正在核对这次配置，请等待回执。</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setEditor(null)}>取消</Button><Button disabled={busy || !definitionReady || !editorPayload || !editor.name.trim() || editor.name.length > 80 || incompleteMembers.length > 0 || !!discussionError} onClick={() => { if (editorPayload) void invoke('teams.reviewDefinition', { ...editorPayload, ...(definition ? { viewHandle: reading!.viewHandle } : {}) }, reviewResult); }}>核对并保存</Button></div></footer>
    </>}</div></SettingsDialog>
    <SettingsDialog isOpen={!!review} title={review?.operation === 'start' ? '开始这次原团队协作' : review?.operation === 'cancel' ? '停止这次协作' : review?.operation === 'remove' ? '移除团队配置' : '保存团队配置'} onClose={() => { if (!busy) closeReview(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">
      {review?.operation === 'start' ? <><p>{review.input?.goal}</p><p>{review.definition?.name} · {rows(review.definition?.members).map(member => member.name).join('、')}</p>{review.phoneScope ? <><p>手机成员将使用下列已核对的范围，模型调用会计入对应任务的额度。</p>{rows(review.phoneScope.members).map(member => <section className="pw-source-record" key={member.memberId}><h3>{member.name || rows(review.definition?.members).find(value => value.id === member.memberId)?.name}</h3><p>{teamPhoneTargetLabel(member.device, phoneTargets)}{teamPhoneScopeLabel(member.resourceBinding) ? ` · ${teamPhoneScopeLabel(member.resourceBinding)}` : ''}</p><p>最多 {member.toolBudget} 次操作 · {member.modelBudget} 次模型调用</p>{member.sourceQueryToolReserve > 0 && <p className="pw-field-hint">另预留 {member.sourceQueryToolReserve} 次目标任务状态查询。</p>}<p className="pw-muted">有效至 {new Date(member.expiresAt).toLocaleString('zh-CN')}</p></section>)}</> : <p>沿原团队与实际启动时的模型配置开始，可能消耗模型额度。文件写入与额外预算继续使用原任务的独立审批。</p>}</> : review?.operation === 'cancel' ? <p>停止这个原运行后续工作，保留消息、证据和已有成果。停止请求不代表未知外部操作已完成清理。</p> : review?.operation === 'remove' ? <p>移除“{review.definition?.name}”的后续启动配置，已有运行和成果保留。</p> : <><p>{review?.input?.name}</p>{rows(review?.input?.members).map(member => <section className="pw-source-record" key={member.id}><h3>{member.name}</h3><p>{member.role}</p>{member.runner === 'phone_agent' ? <p>{teamPhoneTargetLabel(member.device, phoneTargets)} · {member.assignment}</p> : isTeamHarness(member.runner) ? <><p>执行器：{harnessWorkerLabel(undefined, member.runner)} · 电脑：{computerLabel(member)}</p><p>模型：{member.model || '执行器默认'}{member.modeId ? ` · 模式：${member.modeId}` : ''}</p></> : <p>模型：{member.model || '实际启动时的主模型'} · 本机模型连接</p>}{review?.input?.mode === 'produce' && <TeamProductionReview member={member} sources={object(catalog.productionSources)} />}</section>)}</>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>已核对这次操作与原目标</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={() => {
        const original = review; if (!original) return; setReview(null); setAcknowledged(false);
        void invoke(original.operation === 'cancel' ? 'teams.control' : 'teams.confirm', original.operation === 'cancel' ? { command: 'cancel', viewHandle: original.viewHandle } : { reviewHandle: original.reviewHandle }, result => {
          if (result.confirmed !== true) throw new Error('原团队操作尚未确认，请刷新核对。'); setEditor(null); setNotice(result.command === 'save' ? '团队已保存。填写本次目标即可开始协作。' : '已收到原操作回执。');
          if (result.reading) { acceptReading(result.reading); if (original.operation === 'start') { startInputs.current.delete(original.definition?.id || original.input?.teamId); setGoal(''); } }
          else { rememberView(); release(reading); setReading(null); setTab('teams'); setCatalog(old => ({ ...old, teams: result.command === 'save' ? [...rows(old.teams).filter(team => team.id !== result.result.id), result.result] : rows(old.teams).filter(team => team.id !== original.input?.id) })); }
        });
      }}>确认这一次操作</Button></div>
    </div></SettingsDialog>
  </section>;
}
