import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { IconAdd, IconArrowLeft, IconChevronRight, IconEdit, IconExternalLink, IconPause, IconPlay, IconRefresh } from './icons';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { AIAnswer, PressButton, ProgressBar } from './library';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { teamMessageConfirmed, teamMessageDelivery, teamMessageRecorded } from './team-message';
import { teamResearchRecords, type TeamResearchRecord } from './team-research';
import { teamPhoneChoice, teamPhoneDevice, teamPhoneScopeLabel, teamPhoneSelections, teamPhoneTargetKey, teamPhoneTargetLabel, type TeamPhoneChoice } from './team-phone';
import { TeamHarnessFields } from './TeamHarnessFields';
import { TeamProductionFields, TeamProductionReview } from './TeamProductionFields';
import { TeamEvidenceWorkspace } from './TeamEvidenceWorkspace';
import { resetTeamProductionAcknowledgments, teamDefinitionPayload, teamProductionDraft, teamProductionEditorMember } from './team-production';
import { TeamMemberAvatar, TeamMemberIdentity } from './TeamMemberIdentity';
import { teamAvatarShape, teamAvatarShapes, teamMemberLabel, teamMessageIdentity, teamMessageAuthor } from './team-identity';
import { HarnessEvidence } from './HarnessEvidence';
import { harnessWorkerLabel, isTeamHarness } from './harness-capabilities';
import './team-continuity.css';
import './collaboration-depth.css';
import './team-navigation.css';
import './team-workbench.css';
import { FormDraftNotice, useFormDrafts, type RegisterBeforeLeave } from './form-drafts';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type PendingMessage = { id: string; memberId: string; runId: string; key: string; text: string; questionId?: string };
type TeamTab = 'runs' | 'teams';
type RunSection = 'progress' | 'messages' | 'materials';
type MessageInput = { recipient: string; replyId: string; mode: string };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const label = (value: unknown): string => typeof value === 'string' ? value : '';
const status = (value: string) => ({ queued: '等待工作', running: '工作中', completed: '本轮已有产出', partial: '部分分工未完成', paused: '已暂停',
  awaiting_review: '成果待审阅', awaiting_input: '等待回答', awaiting_user: '等待处理', waiting_dependency: '等待前置分工', blocked_dependency: '前置工作未完成',
  blocked_unknown: '原操作结果待核对', failed: '本轮未完成', exited: '已退出', cancelled: '已停止', interrupted: '已中断', awaiting_approval: '等待授权', needs_confirmation: '成果待确认', reconciling: '核对原操作', stopping: '正在停止' } as Record<string, string>)[value] || value;
const blankMember = (id: string, index: number) => teamProductionEditorMember({ id, name: `成员 ${index + 1}`, role: '', model: '', runner: 'model' });
const starterMembers = (template: string): Data[] => [
  { ...blankMember('member-0', 0), name: template === 'options' ? '方案设计' : '资料研究', role: template === 'options' ? '围绕共同目标提出不同方案，说明依据与限制。' : '围绕共同目标搜集和整理信息，标明来源与不确定性。' },
  { ...blankMember('member-1', 1), name: template === 'options' ? '评估与建议' : '核对与整合', role: template === 'options' ? '比较方案的成本、收益与风险，整理可执行建议。' : '核对信息与推理，指出矛盾和缺口，整合结论与下一步。' },
];
const safeTeamDraft = (editor: Data): Data => ({ name: editor.name, ...(typeof editor.nameEdited === 'boolean' ? { nameEdited: editor.nameEdited } : {}), mode: editor.mode, leaderId: editor.leaderId, goal: editor.goal || '', members: rows(editor.members).map(member => ({ ...Object.fromEntries(['id', 'name', 'role', 'model', 'runner', 'modeId', 'avatarShape', 'assignment', 'dependsOn', 'device', 'writeFiles'].filter(key => member[key] !== undefined).map(key => [key, member[key]])), production: Object.fromEntries(['sourceMode', 'sourceTaskId', 'snapshotId', 'packageId', 'packageSha', 'packageManifest', 'wallSeconds', 'outputBytes', 'contributionBytes'].filter(key => member.production?.[key] !== undefined).map(key => [key, member.production[key]])) })) });
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
export function TeamsWorkspace({ state, run, registerTransientBack, registerBeforeLeave, alreadyShownError = '', attentionFocus, onAttentionConsumed }: { state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; registerBeforeLeave?: RegisterBeforeLeave; alreadyShownError?: string; attentionFocus?: Data | null; onAttentionConsumed?: (requestId: string) => void }) {
  const formDraft = useFormDrafts(run, 'teams');
  const startDraft = useFormDrafts(run, 'teams');
  const messageDraft = useFormDrafts(run, 'teams');
  const memberFormDraft = useFormDrafts(run, 'teams');
  const messageDraftId = useRef('');
  const [draftCatalogChecked, setDraftCatalogChecked] = useState(false), [messageChecked, setMessageChecked] = useState(false);
  const startDraftId = useRef('');
  const [catalog, setCatalog] = useState<Data>({}), [tab, setTab] = useState<TeamTab>('teams');
  const directoryInitialized = useRef(false);
  const [catalogReady, setCatalogReady] = useState(false), [editorExpanded, setEditorExpanded] = useState<string[]>([]);
  const [queries, setQueries] = useState<Record<TeamTab, string>>({ runs: '', teams: '' });
  const query = queries[tab], setQuery = (value: string) => setQueries(old => ({ ...old, [tab]: value }));
  const [reading, setReading] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [editor, setEditor] = useState<Data | null>(null);
  const [memberDraft, setMemberDraft] = useState<Data | null>(null);
  const pendingMemberChange = useRef<string | null>(null);
  const [phoneSelection, setPhoneSelection] = useState<Data | null>(null), [phoneChoices, setPhoneChoices] = useState<Record<string, TeamPhoneChoice>>({});
  const [externalReady, setExternalReady] = useState<Record<string, boolean>>({}), [memberProbes, setMemberProbes] = useState<Record<string, Data>>({});
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [acknowledged, setAcknowledged] = useState(false);
  const [goal, setGoal] = useState(''), [rounds, setRounds] = useState('1'), [recipient, setRecipient] = useState(''), [replyId, setReplyId] = useState(''), [mode, setMode] = useState('discussion');
  const [composeOpen, setComposeOpen] = useState(false);
  const [runSections, setRunSections] = useState<Record<string, RunSection>>({});
  const [memberFilters, setMemberFilters] = useState<Record<string, string>>({});
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
  const materialBeforeLeave = useRef<((leave: () => void) => void) | null>(null);
  useEffect(() => { registerBeforeLeave?.(leave => formDraft.guard(() => startDraft.guard(() => messageDraft.guard(() => memberFormDraft.guard(() => { if (materialBeforeLeave.current) materialBeforeLeave.current(leave); else leave(); }))))); return () => registerBeforeLeave?.(null); }, [registerBeforeLeave, formDraft.guard, startDraft.guard, messageDraft.guard, memberFormDraft.guard]);
  const [materialBusy, setMaterialBusy] = useState(false);
  const handledAttention = useRef('');
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
  useSurfaceEnter(root, viewKey, { selector: ':scope > .pw-team-reading, :scope > .pw-team-tabs, :scope > .pw-collaboration-directory, :scope > .pw-team-empty', depth: reading?.kind === 'run' ? 2 : reading ? 1 : 0, active: workspaceActive });
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
    if (next.kind !== 'run' || next.run?.id !== currentReading.current?.run?.id) setMemberDraft(null);
    release(currentReading.current); setReading(next);
    if (next.kind === 'run' && pendingMemberChange.current === next.run?.id) {
      pendingMemberChange.current = null; setMemberDraft(null);
      setNotice('已重新读取当前成员状态，请核对刚才的改动；没有重发旧操作。');
    }
    if (next.kind === 'run' && next.run?.id) listSelection.current.runs = next.run.id;
    if (next.kind === 'definition' && next.definition?.id) listSelection.current.teams = next.definition.id;
    if (next.kind === 'run' && next.run?.id) setCatalog(old => ({ ...old, runs: [next.run, ...rows(old.runs).filter(value => value.id !== next.run.id)] }));
    if (next.kind === 'definition' && next.definition?.id) setCatalog(old => ({ ...old, teams: [next.definition, ...rows(old.teams).filter(value => value.id !== next.definition.id)] }));
    if (next.kind === 'run' && next.run?.id !== currentReading.current?.run?.id) {
      const saved = messageInputs.current.get(next.run.id);
      setComposeOpen(!!saved?.replyId); setRecipient(saved?.recipient ?? next.run.leaderId ?? ''); setReplyId(saved?.replyId || ''); setMode(saved?.mode || 'discussion');
    } else if (next.kind === 'definition' && next.definition?.id !== currentReading.current?.definition?.id) {
      const saved = startInputs.current.get(next.definition.id);
      startDraftId.current = '';
      void startDraft.open('owner', 'start:' + next.definition.id, String(next.definition.revision || ''), { goal: saved?.goal || '', rounds: saved?.rounds || '1' }, !!saved?.goal).then(value => { if (value && alive.current && currentReading.current?.definition?.id === next.definition.id) { startDraftId.current = next.definition.id; setGoal(value.goal); setRounds(value.rounds); } });
      setGoal(saved?.goal || ''); setRounds(saved?.rounds || '1');
    }
    const pending = uncertainByRun[String(next.run?.id || '')];
    if (pending && teamMessageRecorded(next, pending)) {
      setDrafts(old => old[pending.key] === pending.text ? { ...old, [pending.key]: '' } : old); rememberUncertain(pending.runId, null); setNotice('已从原运行记录核对消息已保存，消费状态见原记录。'); if (messageDraftId.current === pending.key) messageDraft.discard(() => { void messageDraft.open('owner', 'message:' + pending.key, pending.key, { text: '' }); });
    }
  };
  useEffect(() => {
    if (!composeOpen) return;
    const frame = requestAnimationFrame(() => root.current?.querySelector<HTMLTextAreaElement>('.pw-team-compose textarea')?.focus());
    return () => cancelAnimationFrame(frame);
  }, [composeOpen, replyId]);
  const acceptCatalog = (value: Data) => {
    setCatalog(value); setCatalogReady(true);
    if (!directoryInitialized.current) { directoryInitialized.current = true; if (rows(value.runs).some(row => !['completed', 'cancelled'].includes(row.status))) setTab('runs'); }
  };
  const load = () => { void invoke('teams.snapshot', {}, value => { acceptCatalog(value); if (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) setDraftCatalogChecked(true); }); };
  useEffect(() => { if (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) setDraftCatalogChecked(false); }, [formDraft.requiresRecheck, startDraft.requiresRecheck, memberFormDraft.requiresRecheck]);
  const readRun = (id: string) => { void invoke('teams.readRun', { runId: id }, result => { if (result.reading?.run?.id !== id) throw new Error('原团队运行身份不匹配'); acceptReading(result.reading); }); };
  const readDefinition = (id: string) => { void invoke('teams.readDefinition', { id }, value => { if (value.definition?.id !== id) throw new Error('团队配置身份不匹配，请刷新目录'); acceptReading(value); }); };
  useEffect(() => {
    const requestId = label(attentionFocus?.requestId);
    if (!requestId || requestId === handledAttention.current || !workspaceActive || !catalogReady || busy || entered.current || backgrounded.current || !ownsNativeWindow()) return;
    handledAttention.current = requestId;
    const locator = Object.fromEntries(['runId', 'memberId', 'generation', 'memberGeneration', 'workItemId', 'assignmentId', 'questionId']
      .filter(key => attentionFocus?.[key] !== undefined).map(key => [key, attentionFocus![key]]));
    void invoke('teams.readAttention', locator, result => {
      if (result.reading?.run?.id !== locator.runId) throw new Error('原团队待处理身份不匹配，请刷新通知。');
      acceptReading(result.reading);
      const focus = object(result.focus), id = result.reading.run.id;
      setRunSections(old => ({ ...old, [id]: focus.kind === 'question' ? 'messages' : 'progress' }));
      setMemberFilters(old => ({ ...old, [id]: '' }));
      if (focus.kind === 'workItem') setExpandedItems(old => ({ ...old, [id]: [...new Set([...(old[id] || []), focus.workItemId])] }));
      if (focus.kind === 'member') setExpandedMembers(old => ({ ...old, [id]: [...new Set([...(old[id] || []), focus.memberId])] }));
      if (focus.kind === 'changed') setNotice(label(focus.message));
      requestAnimationFrame(() => {
        const selector = focus.kind === 'workItem' ? `[data-work-item-id="${CSS.escape(String(focus.workItemId))}"]` : focus.kind === 'question' ? `[data-message-id="${CSS.escape(String(focus.messageId))}"]`
          : focus.kind === 'member' ? `[data-team-member-id="${CSS.escape(String(focus.memberId))}"]` : '.pw-team-run h2';
        const target = root.current?.querySelector<HTMLElement>(selector);
        (['member', 'workItem'].includes(focus.kind) ? target?.querySelector<HTMLElement>('button') : target)?.focus({ preventScroll: true });
        target?.scrollIntoView({ block: 'start' });
      });
      onAttentionConsumed?.(requestId);
    }, () => onAttentionConsumed?.(requestId));
  }, [attentionFocus?.requestId, workspaceActive, catalogReady, busy]);
  const back = () => { const leave = () => { rememberView(); release(reading); setTab(reading?.kind === 'run' ? 'runs' : 'teams'); setReading(null); setComposeOpen(false); setReplyId(''); setEditor(null); setMemberDraft(null); setError(''); setNotice(''); }; formDraft.guard(() => startDraft.guard(() => messageDraft.guard(() => memberFormDraft.guard(() => { if (materialBeforeLeave.current) materialBeforeLeave.current(leave); else leave(); })))); };
  const openRelatedTask = (id: string) => {
    const open = () => { void run('tasks.open', { id }).catch(failure => setError(failure instanceof Error ? failure.message : String(failure))); };
    if (materialBeforeLeave.current) materialBeforeLeave.current(open); else open();
  };
  const openResearchTask = (memberId: string, record: TeamResearchRecord) => {
    const original = currentReading.current;
    if (busy || materialBusy || backgrounded.current || !ownsNativeWindow() || !original?.viewHandle || original.kind !== 'run') return;
    const open = () => { void invoke('teams.openResearchTask', { viewHandle: original.viewHandle, memberId, round: record.round, taskId: record.taskId }); };
    if (materialBeforeLeave.current) materialBeforeLeave.current(open); else open();
  };
  const switchTab = (value: TeamTab) => { if (busy) return; rememberView(); setTab(value); setError(''); setNotice(''); };
  const closeReview = () => { const original = currentReview.current; currentReview.current = null; release(original, 'reviewHandle'); setReview(null); setAcknowledged(false); };
  const closePhoneSelection = () => { const original = currentPhoneSelection.current; currentPhoneSelection.current = null; release(original, 'phoneHandle'); setPhoneSelection(null); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; requestGeneration.current++; release(currentReading.current); release(currentReview.current, 'reviewHandle'); release(currentPhoneSelection.current, 'phoneHandle'); }; }, []);
  const restoreWorkspace = () => {
    if (backgrounded.current || !ownsNativeWindow()) return;
    // A retired read did not consume its notification; a fresh owner window may locate it again.
    handledAttention.current = '';
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
  useEffect(() => { registerTransientBack(() => { if (materialBack.current?.()) return true; if (busy) return true; if (review) { closeReview(); return true; } if (phoneSelection) { closePhoneSelection(); return true; } if (memberDraft) { memberFormDraft.guard(() => setMemberDraft(null)); return true; } if (editor) { formDraft.guard(() => setEditor(null)); return true; } if (reading) { back(); return true; } return false; }); return () => registerTransientBack(null); }, [reading, review, phoneSelection, memberDraft, editor, busy, registerTransientBack]);
  const definition = reading?.kind === 'definition' ? object(reading.definition) : null;
  const definitionReady = workspaceActive && (!definition || !!reading?.viewHandle);
  const current = reading?.kind === 'run' ? object(reading.run) : null;
  const latest = current ? object(object(state.teamRuns)[current.id]) : {};
  const changed = !!current && latest.id === current.id && (latest.generation > current.generation
    || latest.generation === current.generation && latest.revision > current.revision);
  useEffect(() => {
    if (!changed || !workspaceActive || busy || materialBusy || editor || memberDraft || review || phoneSelection || backgrounded.current) return;
    const runId = current!.id, originalHandle = reading?.viewHandle, generation = requestGeneration.current;
    let retired = false;
    const stillCurrent = () => !retired && alive.current && !backgrounded.current && ownsNativeWindow()
      && generation === requestGeneration.current && currentReading.current?.run?.id === runId
      && currentReading.current?.viewHandle === originalHandle;
    // Coalesce progress events into one read without locking the user's draft.
    // A foreground action/navigation retires this read, never the action itself.
    const timer = setTimeout(() => {
      if (!stillCurrent()) return;
      void bridge.command('teams.readRun', { runId }).then(result => {
        const next = object(object(result).reading);
        if (!stillCurrent()) { release(next); return; }
        if (next.run?.id !== runId || !next.viewHandle) {
          release(next); throw new Error('原团队运行身份不匹配，请刷新记录');
        }
        acceptReading(next);
      }).catch(failure => {
        if (stillCurrent()) setError(failure instanceof Error ? failure.message : String(failure));
      });
    }, 250);
    return () => { retired = true; clearTimeout(timer); };
  }, [changed, current?.id, reading?.viewHandle, workspaceActive, busy, materialBusy, editor, memberDraft, review, phoneSelection]);
  const members = rows(current?.members), messages = rows(reading?.messages);
  const workItems = rows(object(current?.workPlan).items);
  const runSection = runSections[current?.id || ''] || 'progress';
  const memberFilter = memberFilters[current?.id || ''] || '';
  const visibleMessages = memberFilter ? messages.filter(message => [message.memberId, message.recipientMemberId, message.targetMemberId].includes(memberFilter)) : messages;
  const chooseMemberFilter = (id: string) => { if (current) setMemberFilters(old => ({ ...old, [current.id]: id })); };
  const chooseRunSection = (section: RunSection, onShown?: () => void) => {
    if (!current) return;
    const select = () => {
      const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
      if (scroll && section !== runSection) positions.current.set(`run:${current.id}:${runSection}`, scroll.scrollTop);
      setRunSections(old => ({ ...old, [current.id]: section }));
      requestAnimationFrame(() => { if (scroll && section !== runSection) scroll.scrollTop = positions.current.get(`run:${current.id}:${section}`) || 0; onShown?.(); });
    };
    if (runSection === 'materials' && section !== runSection && materialBeforeLeave.current) materialBeforeLeave.current(select);
    else select();
  };
  const focusMessage = (id: string) => {
    chooseRunSection('messages', () => {
      chooseMemberFilter('');
      requestAnimationFrame(() => { const target = root.current?.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(id)}"]`); target?.focus({ preventScroll: true }); target?.scrollIntoView({ block: 'center' }); });
    });
  };
  const hasWorkPlan = !!current?.workPlan;
  const reply = messages.find(message => message.messageId === replyId);
  const draftKey = `${current?.id || ''}:${recipient}:${replyId}:${mode}`, text = drafts[draftKey] || '';
  useEffect(() => {
    if (!current?.id || !recipient || !composeOpen) return;
    messageDraftId.current = '';
    void messageDraft.open('owner', 'message:' + draftKey, draftKey, { text: drafts[draftKey] || '' }).then(saved => { if (saved && alive.current) { messageDraftId.current = draftKey; setDrafts(old => ({ ...old, [draftKey]: saved.text })); } });
  }, [draftKey, composeOpen]);
  useEffect(() => { if (messageDraftId.current === draftKey) messageDraft.update({ text }); }, [draftKey, text, messageDraft.update]);
  useEffect(() => { if (messageDraft.requiresRecheck) setMessageChecked(false); }, [messageDraft.requiresRecheck]);
  const canControl = workspaceActive && !!current && !!reading?.viewHandle && !busy && !materialBusy && !changed && current.status !== 'cancelled';
  const canMutate = canControl && !uncertain;
  const canChangeRequirements = !hasWorkPlan && current?.mode !== 'produce' && !members.some(member => member.runner === 'phone_agent');
  const changeMessageMode = (nextMode: string) => {
    const nextRecipient = nextMode !== 'feedback' && recipient === '*' ? current?.leaderId || members.find(member => !member.exited)?.id || '' : recipient;
    const nextKey = `${current?.id || ''}:${nextRecipient}:${replyId}:${nextMode}`;
    setDrafts(old => old[nextKey] || !text ? old : { ...old, [nextKey]: text });
    setRecipient(nextRecipient); setMode(nextMode);
  };
  const name = (id: string) => { const member = members.find(value => value.id === id); return member ? teamMemberLabel(member, members) : id; };
  const simpleRoster = current?.mode === 'collaborate' && !hasWorkPlan && (!current.researchExecutionFormat || current.researchExecutionFormat === catalog.continuousResearchFormat) && !current.nativeWorkProfileModels
    && members.every(member => (member.runner || 'model') === 'model' && (!member.device || member.device === 'phone'));
  const rosterPaused = current?.status === 'paused' || current?.phase === 'awaiting_member';
  const currentMembers = members.filter(member => !member.exited);
  const beginMemberChange = (action: 'add' | 'rename' | 'exit', member?: Data) => {
    if (!canMutate || !current) return;
    setError('');
    const initial = { action, runId: current.id, memberId: member?.id || `member-${crypto.randomUUID().slice(0, 12)}`, name: member?.name || '', avatarShape: teamAvatarShape(member || { id: `new-${members.length}` }), role: '', assignment: '', model: '', newLeaderId: '' };
    if (action === 'exit') { setMemberDraft(initial); return; }
    void memberFormDraft.open('owner', 'member:' + current.id + ':' + action + ':' + (member?.id || 'new'), String(current.revision || ''), { members: [{ id: initial.memberId, name: initial.name, avatarShape: initial.avatarShape, role: initial.role, assignment: initial.assignment, model: initial.model }] }).then(saved => { if (saved && alive.current) { const value = saved.members[0]; setMemberDraft({ ...initial, ...value, memberId: value.id }); } });
  };
  useEffect(() => { if (memberDraft && memberDraft.action !== 'exit') memberFormDraft.update({ members: [{ id: memberDraft.memberId, name: memberDraft.name, avatarShape: memberDraft.avatarShape, role: memberDraft.role, assignment: memberDraft.assignment, model: memberDraft.model }] }); }, [memberDraft, memberFormDraft.update]);
  const reviewMemberChange = () => {
    if (!memberDraft || memberDraft.action !== 'exit' && (memberFormDraft.stale || !!memberFormDraft.conflict || memberFormDraft.requiresRecheck) || !canMutate || memberDraft.runId !== current?.id) return;
    const { action, memberId, name, avatarShape, role, assignment, model, newLeaderId } = memberDraft;
    void invoke('teams.reviewMember', { viewHandle: reading!.viewHandle, action, memberId,
      ...(action === 'exit' ? { newLeaderId } : { name, avatarShape }), ...(action === 'add' ? { role, assignment, model } : {}) }, reviewResult);
  };
  const pendingCount = (member: Data) => Number(member.unreadOwnerMessages || 0) + Number(member.unreadPeerMessages || 0);
  const pendingMessageBusy = (member: Data) => ['running', 'awaiting_approval'].includes(member.status);
  const recipientMember = members.find(member => member.id === recipient);
  const recipientPending = recipientMember && !hasWorkPlan && !recipientMember.exited ? pendingCount(recipientMember) : 0;
  const reviewResult = (value: Data) => {
    if (value.operation === 'phone_scope') { setPhoneSelection(value); setPhoneChoices(Object.fromEntries(rows(value.view?.members).map(member => [member.memberId, teamPhoneChoice()]))); }
    else { setReview(value); setAcknowledged(false); }
  };
  const control = (command: string, memberId?: string) => {
    if (!reading || !canControl || entered.current || backgrounded.current || !ownsNativeWindow() || uncertain && !['pause', 'cancel'].includes(command)) return;
    const viewHandle = reading.viewHandle;
    // Native control consumes this reading before it checks the current run.
    // A rejected action must not leave enabled controls with a spent handle.
    setReading(value => value ? { ...value, viewHandle: '' } : null);
    void invoke('teams.control', { viewHandle, command, ...(memberId ? { memberId } : {}) }, result => {
      if (result.confirmed !== true) throw new Error('原操作尚未确认');
      if (result.reading) acceptReading(result.reading);
      setNotice('已收到原运行的操作回执。');
    });
  };
  const send = async () => {
    // React's busy state can lag behind a second click. Reserve no message id
    // until invoke can actually admit this request in the original window.
    if (!canMutate || messageDraft.loading || !!messageDraft.conflict || messageDraft.requiresRecheck || entered.current || backgrounded.current || !ownsNativeWindow() || !reading || !text.trim() || text.length > 4000 || !(mode === 'feedback' && recipient === '*' || members.some(member => member.id === recipient && !member.exited))) return;
    if (mode === 'feedback') {
      if (!canChangeRequirements || replyId) return;
      reviewResult({ operation: 'feedback', viewHandle: reading.viewHandle, runId: current!.id, memberId: recipient === '*' ? undefined : recipient,
        recipientName: recipient === '*' ? '全队' : name(recipient), text, draftKey });
      return;
    }
    const action = reply?.kind === 'assignment_question' ? 'teams.answerAssignment' : 'teams.sendMessage';
    if (!bridge.getState().capabilities.commands.includes(action)) { setError('当前团队页面尚未接通发送能力，草稿保留，请重新进入。'); return; }
    setBusy(true); try { await messageDraft.markSubmitted(); } catch (failure) { setBusy(false); setError((failure as Error).message); return; }
    if (!alive.current || !ownsNativeWindow()) return;
    const viewHandle = reading.viewHandle;
    const original = { id: crypto.randomUUID(), memberId: recipient, runId: current!.id, key: draftKey, text, ...(reply?.kind === 'assignment_question' ? { questionId: reply.questionId } : {}) };
    rememberUncertain(original.runId, original);
    // The native message path consumes this one-shot reading even on a rejected
    // stale snapshot. Only a fresh read or the exact receipt enables controls.
    setReading(value => value?.run?.id === original.runId ? { ...value, viewHandle: '' } : value);
    void invoke(action, { viewHandle, memberId: recipient, messageId: original.id, text: text.trim(), mode, quoteMessageId: replyId }, result => {
      if (!teamMessageConfirmed(result, original)) throw new Error('原消息保存回执尚待核对，请刷新原记录。');
      setDrafts(old => old[original.key] === original.text ? { ...old, [original.key]: '' } : old); rememberUncertain(original.runId, null); setNotice('已保存到原运行；进入模型与实际消费分别查看记录。'); messageDraft.discard(() => { void messageDraft.open('owner', 'message:' + original.key, original.key, { text: '' }); });
      if (result.reading) acceptReading(result.reading); else { setReading(value => value ? { ...value, viewHandle: '' } : null); setNotice('原回答已保存，请刷新原运行查看后续状态。'); }
    }, failure => { if (['not_sent', 'not_accepted', 'invalid_request'].includes(object(failure).code)) { rememberUncertain(original.runId, null); messageDraft.clearSubmitted(); setNotice('这次输入未提交，草稿保留；请刷新原运行后再操作。'); } });
  };
  const beginEditor = (team: Data | null) => {
    if (formDraft.loading) return;
    const members = team ? rows(team.members).map(teamProductionEditorMember) : starterMembers('research');
    setError(''); setNotice(''); setExternalReady({}); setEditorExpanded(team ? members.slice(0, 1).map(member => member.id) : []);
    const initial = { name: team?.name || '共同研究', nameEdited: team ? true : undefined, mode: team?.mode || 'collaborate', leaderId: team?.leaderId || members[members.length - 1].id, members, goal: team ? goal : '' };
    void formDraft.open('owner', team ? 'definition:' + team.id : 'definition:new', String(team?.revision || 'new'), safeTeamDraft(initial)).then(saved => { if (saved && alive.current) setEditor({ ...initial, ...saved, members: rows(saved.members).map(member => ({ ...member, externalExecutionRiskAcknowledged: false, production: resetTeamProductionAcknowledgments({ ...teamProductionDraft(), ...member.production }) })) }); });
  };
  const changeTeamGoal = (goal: string) => setEditor(previous => {
    if (!previous) return previous;
    // Older drafts have no flag. Keep their non-default names rather than infer a rename.
    const nameEdited = typeof previous.nameEdited === 'boolean' ? previous.nameEdited : !!previous.name && previous.name !== '共同研究';
    return { ...previous, goal, nameEdited, name: nameEdited ? previous.name : goal.trim().split('\n')[0].slice(0, 40) || '共同研究' };
  });
  useEffect(() => { if (editor) formDraft.update(safeTeamDraft(editor)); }, [editor, formDraft.update]);
  useEffect(() => { if (reading?.kind === 'definition' && startDraftId.current === reading.definition.id) startDraft.update({ goal, rounds }); }, [goal, rounds, reading?.definition?.id, startDraft.update]);
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
  const catalogTeams = rows(catalog.teams), catalogRuns = rows(catalog.runs);
  const catalogEntries = tab === 'runs' ? catalogRuns : catalogTeams, hasQuery = !!query.trim();
  const showDirectoryTools = catalogTeams.length > 0 || catalogRuns.length > 0 || !!queries.teams.trim() || !!queries.runs.trim() || tab === 'runs';
  const showCollectionTools = catalogEntries.length > 0 || hasQuery;
  const directoryState = !workspaceActive || busy ? 'loading' : error ? 'unconfirmed' : !catalogReady ? 'unread' : hasQuery ? 'filtered' : 'empty';
  const entries = catalogEntries.map(row => {
    const live = object(object(state.teamRuns)[row.id]);
    return tab === 'runs' && live.id === row.id && (live.generation > row.generation
      || live.generation === row.generation && live.revision > row.revision) ? { ...row, ...live } : row;
  }).filter(row => `${row.goal || ''} ${row.name || ''} ${row.teamName || ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const showDirectoryActions = showCollectionTools && (entries.length > 0 || catalogReady && !error);
  const attentionStates = new Set(['awaiting_review', 'awaiting_input', 'awaiting_user', 'awaiting_approval', 'needs_confirmation', 'blocked_unknown', 'unknown', 'reconciling', 'blocked_dependency', 'failed', 'partial', 'interrupted']);
  const finishedStates = new Set(['completed', 'cancelled']);
  const directoryGroups = tab === 'teams' ? [{ id: 'definitions', title: '选择团队，开始一次协作', entries }] : [
    { id: 'attention', title: '需要处理', entries: entries.filter(row => attentionStates.has(row.status)) },
    { id: 'active', title: '当前工作', entries: entries.filter(row => !attentionStates.has(row.status) && !finishedStates.has(row.status)) },
    { id: 'history', title: '已结束的工作', entries: entries.filter(row => finishedStates.has(row.status)) },
  ];
  useCollectionMotion(root, JSON.stringify([viewKey, runSection, memberFilter, query.trim(), state.webWorkspaceResumeRevision]), JSON.stringify([
    entries.map(row => [row.id, row.status, row.revision]),
    messages.map(message => [message.messageId, message.state, !!message.answer]),
    members.map(member => [member.id, member.status, member.exited]),
    workItems.map(item => [item.workItemId, item.status]), !!current?.summary,
    expandedMembers[String(current?.id || '')] || [], expandedItems[String(current?.id || '')] || [],
  ]), { active: workspaceActive && catalogReady && (!reading || !!reading.viewHandle) && !editor && !memberDraft && !review && !phoneSelection });
  const pendingQuestions = messages.filter(message => message.kind === 'assignment_question' && message.state === 'pending' && current?.status !== 'cancelled' && members.some(member => member.id === message.memberId && !member.exited));
  const jumpToRunSection = (section: 'messages' | 'members' | 'materials' | 'compose') => {
    chooseRunSection(section === 'members' ? 'progress' : section === 'materials' ? 'materials' : 'messages', () => {
      if (section === 'compose') setComposeOpen(true);
      requestAnimationFrame(() => {
        const target = root.current?.querySelector<HTMLElement>(`[data-team-section="${section}"]`);
        target?.scrollIntoView({ block: 'start' });
        if (section === 'compose') target?.querySelector<HTMLTextAreaElement>('textarea')?.focus({ preventScroll: true });
        else target?.focus({ preventScroll: true });
      });
    });
  };
  const editorMemberIssue = (member: Data): string => {
    if (!label(member.name).trim()) return '填写成员名称';
    if (member.name.length > 48) return '成员名称最多 48 字';
    if (!label(member.role).trim()) return '填写成员职责';
    if (member.role.length > 1200) return '成员职责最多 1200 字';
    if (label(member.model).length > 200) return '模型 ID 最多 200 字';
    if (label(member.assignment).length > 2000) return '具体分工最多 2000 字';
    if (member.runner === 'phone_agent') return !label(member.assignment).trim() ? '填写手机具体分工'
      : member.assignment.length > 2000 ? '手机分工最多 2000 字' : !teamPhoneTargetKey(member.device) ? '选择工作手机' : '';
    if (member.runner === 'model') return '';
    if (!member.device?.profileId || !member.device?.environmentId) return '选择电脑与工作环境';
    if (!isTeamHarness(member.runner)) return '选择成员执行器';
    if (member.runner !== 'codex_app_server' && member.externalExecutionRiskAcknowledged !== true) return '确认电脑执行权限与费用边界';
    return externalReady[member.id] ? '' : '核对这名成员的执行器';
  };
  const incompleteMembers = rows(editor?.members).filter(member => !!editorMemberIssue(member));
  const evidenceDebate = editor?.mode === 'debate' && !(definition?.mode === 'debate' && definition.discussionFormat === 'classic');
  const discussionError = evidenceDebate && (rows(editor?.members).length < 2 || rows(editor?.members).some(member => member.runner !== 'model' || rows(member.dependsOn).length > 0))
    ? '取证与讨论需要至少两名独立的分析成员。电脑、手机或有前置依赖的分工请使用“共同工作”。' : '';
  let editorPayload: Data | null = null, productionError = '';
  if (editor) try { const { goal: firstGoal, nameEdited: _nameEdited, ...definitionDraft } = editor; editorPayload = teamDefinitionPayload(definitionDraft, catalog); }
  catch (failure) { productionError = failure instanceof Error ? failure.message : String(failure); }
  const refreshReading = <IconButton className="pw-utility-icon" icon={IconRefresh} aria-label="刷新团队记录" title="刷新团队记录" disabled={busy || materialBusy} onClick={() => current ? readRun(current.id) : definition ? void invoke('teams.readDefinition', { id: definition.id }, acceptReading) : load()} />;
  const draftRecovery = (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) && <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { load(); formDraft.guard(() => memberFormDraft.guard(() => { setEditor(null); setMemberDraft(null); })); }}>读取团队与协作核对结果</Button><Button variant="secondary" disabled={busy || !draftCatalogChecked} onClick={() => { formDraft.clearSubmitted(); startDraft.clearSubmitted(); memberFormDraft.clearSubmitted(); setDraftCatalogChecked(false); }}>已核对原记录，继续编辑</Button></div>;
  const directoryContent = <>
    {showCollectionTools && <SearchField fieldClassName="pw-collaboration-search" shape="rounded" className="pw-secondary-search" data-input-size="medium" aria-label={tab === 'runs' ? '搜索协作记录' : '搜索我的团队'} placeholder={tab === 'runs' ? '搜索目标或团队' : '搜索团队'} value={query} onChange={setQuery} />}
    {entries.length > 0 ? <div className="pw-collaboration-directory">{directoryGroups.filter(group => group.entries.length > 0).map(group => <section className="pw-collaboration-group" key={group.id} aria-label={group.title}>
      {tab === 'runs' && <header className="pw-collaboration-group-heading"><h3>{group.title}</h3><span>{group.entries.length}</span></header>}
      <div className="pw-object-list">{group.entries.map(row => <Button key={row.id} data-team-list-id={row.id} data-motion-key={`directory:${row.id}`} variant="ghost" trailingIcon={IconChevronRight} className="pw-object-row h-auto whitespace-normal" disabled={busy} onClick={() => { listSelection.current[tab] = row.id; if (tab === 'runs') readRun(row.id); else readDefinition(row.id); }}><span className="pw-object-row-copy"><span className="pw-object-row-title">{row.goal || row.name || row.teamName || '团队工作'}</span><small>{[tab === 'runs' ? row.teamName : undefined, row.status ? status(row.status) : `${rows(row.members).length} 名成员`, tab === 'teams' ? row.mode === 'produce' ? '项目文件协作' : row.mode === 'debate' ? '取证与讨论' : '共同工作' : undefined].filter(Boolean).join(' · ')}</small>{tab === 'teams' && <small className="pw-team-roster-preview">{rows(row.members).map(member => <span key={member.id}><TeamMemberAvatar member={member} size={24} />{teamMemberLabel(member, rows(row.members))}</span>)}</small>}</span></Button>)}</div>
    </section>)}</div> : <section className="pw-team-empty" aria-label="团队目录提示" role={directoryState === 'loading' ? 'status' : undefined}>
      <h2>{directoryState === 'loading' ? '正在读取团队与协作' : directoryState === 'unconfirmed' ? '团队状态需要重新核对' : directoryState === 'unread' ? '团队目录尚未读取' : directoryState === 'filtered' ? '没有匹配的记录' : tab === 'teams' ? '让成员一起完成一个目标' : '还没有协作记录'}</h2>
      <p>{directoryState === 'loading' || directoryState === 'unread' ? '读取后，会在这里显示已保存的团队和协作。' : directoryState === 'unconfirmed' ? '重新读取团队与协作记录后，再继续操作。' : directoryState === 'filtered' ? '换一个名称或清除搜索，查看当前目录。' : tab === 'teams' ? '先说想得到什么结果，再核对建议分工。团队保存后，由你确认开始。' : catalogTeams.length ? '选择一个团队，填写目标后开始协作。' : '从目标开始，采用可调整的分工，再核对并开始协作。'}</p>
      {!showDirectoryTools && (directoryState === 'unread' || directoryState === 'unconfirmed') && <Button variant="secondary" disabled={busy} onClick={load}>重新读取</Button>}
      {directoryState === 'filtered' && <Button variant="secondary" onClick={() => setQuery('')}>清除搜索</Button>}
      {directoryState === 'empty' && <div className="pw-team-empty-actions">{tab === 'runs' && catalogTeams.length ? <Button leadingIcon={IconPlay} onClick={() => { switchTab('teams'); setQueries(old => ({ ...old, teams: '' })); }}>选择团队开始</Button> : <Button leadingIcon={IconAdd} onClick={() => beginEditor(null)}>从目标开始</Button>}{!showDirectoryTools && <Button variant="ghost" disabled={busy} onClick={load}>重新读取</Button>}</div>}
    </section>}
  </>;
  return <section ref={root} className="pw-team-workspace pw-depth-collaboration" data-team-layer={reading ? 'detail' : 'list'} data-team-view={viewKey} inert={!workspaceActive} aria-busy={!workspaceActive || busy}>
    <div className="pw-team-loading">{(!workspaceActive || busy) && <ProgressBar aria-label={workspaceActive ? '正在读取原团队回执' : '正在进入团队工作区'} isIndeterminate showValueLabel={false} size="sm" />}</div>
    {error && error !== alreadyShownError && !editor && !memberDraft && !review && !phoneSelection && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}{notice && <MotionFeedback identity={notice} className="pw-field-hint" role="status">{notice}</MotionFeedback>}
    {reading && !definition && <nav className="pw-team-location" aria-label="团队内导航"><Button variant="ghost" leadingIcon={IconArrowLeft} disabled={busy} onClick={back}>{current ? '返回协作记录' : '返回我的团队'}</Button><span>{current ? current.teamName || '这次协作' : '成员与启动'}</span></nav>}
    {!reading && (showDirectoryTools ? <Tabs selectedKey={tab} onSelectionChange={value => { if (value === 'runs' || value === 'teams') switchTab(value); }} className="pw-team-tabs">
      <div className="pw-object-toolbar"><TabList aria-label="团队视图"><Tab id="teams">我的团队{catalogTeams.length > 0 ? ` · ${catalogTeams.length}` : ''}</Tab><Tab id="runs">协作记录{catalogRuns.length > 0 ? ` · ${catalogRuns.length}` : ''}</Tab></TabList>
        <div className="pw-team-directory-actions">{showDirectoryActions && (tab === 'runs' && catalogTeams.length > 0 ? <Button variant="secondary" leadingIcon={IconPlay} disabled={busy || !catalogReady} onClick={() => { switchTab('teams'); setQueries(old => ({ ...old, teams: '' })); }}>选择团队开始</Button> : <Button leadingIcon={IconAdd} disabled={busy || !catalogReady} onClick={() => beginEditor(null)}>从目标开始</Button>)}<IconButton className="pw-utility-icon" icon={IconRefresh} aria-label="刷新团队与协作记录" title="刷新团队与协作记录" disabled={busy} onClick={load} /></div>
      </div>
      <TabPanel id={tab}>{directoryContent}</TabPanel>
    </Tabs> : directoryContent)}
    {draftRecovery}
    {!editor && !definition && <FormDraftNotice draft={formDraft} />}
    {definition && <article className="pw-team-reading pw-team-definition"><header className="pw-object-toolbar pw-team-definition-heading"><div><h2 tabIndex={-1}>{definition.name}</h2><p>{definition.mode === 'produce' ? '原项目文件协作' : definition.mode === 'debate' ? '取证与讨论' : hasPhoneMembers ? '手机分工与共同工作' : '共同分析'} · {rows(definition.members).length} 名成员</p><div className="pw-team-roster-summary" aria-label="参与成员">{rows(definition.members).map(member => <TeamMemberIdentity key={member.id} member={member} name={teamMemberLabel(member, rows(definition.members))} size={28} />)}</div></div>{refreshReading}</header>
      <FormDraftNotice draft={startDraft} /><section className="pw-team-start" aria-label="开始下一次协作">{definition.mode === 'produce' ? <><h3>下一次共同工作</h3><p className="pw-muted">文件团队沿原项目任务的计划、文件范围和预算启动；这里保留其成员与既有工作记录。</p></> : <>
        <TextAreaField label="这次共同目标" value={goal} onChange={setGoal} maxLength={8000} showCount rows={3} autoResize maxRows={8} resize="none" isDisabled={busy} />
        {fixedDiscussionRounds && <p className="pw-field-hint">取证与讨论使用固定流程：独立观点、相互质疑、回应与综合结论。启动时按两轮额度核对。</p>}
        {hasPhoneMembers && <p className="pw-field-hint">手机成员按各自分工与前置依赖工作。本机可同时使用至多四个后台屏幕；启动前逐成员核对应用范围与预算。</p>}
        <div className="pw-team-start-actions">
          {!hasPhoneMembers && !fixedDiscussionRounds && <SelectField label="讨论轮数" value={rounds} options={[{ id: '1', label: '一轮' }, { id: '2', label: '两轮' }]} onChange={setRounds} isDisabled={busy} />}
          <PressButton label={hasPhoneMembers ? '选择手机范围并开始' : '核对并开始协作'} disabled={busy || startDraft.loading || startDraft.stale || !!startDraft.conflict || startDraft.requiresRecheck || !definitionReady || !goal.trim() || goal.length > 8000 || !!state.modelSelectionBusy} onClick={() => void invoke('teams.reviewStart', { viewHandle: reading!.viewHandle, goal, rounds: hasPhoneMembers ? 1 : fixedDiscussionRounds ? 2 : Number(rounds) }, reviewResult)} />
        </div>
      </>}</section>
      <section className="pw-team-members"><header className="pw-team-members-heading"><h3>成员与职责</h3><Button variant="ghost" leadingIcon={IconEdit} disabled={busy || !definitionReady} onClick={() => beginEditor(definition)}>编辑团队</Button></header><div className="pw-team-definition-member-list">{rows(definition.members).map(member => <div key={member.id} data-motion-key={`definition-member:${member.id}`} className="pw-source-record"><h4><TeamMemberIdentity member={member} name={teamMemberLabel(member, rows(definition.members))} detail={member.id === definition.leaderId ? '负责人' : undefined} size={40} /></h4><p>{member.role}</p>
        {member.runner === 'phone_agent' ? <><small>{teamPhoneTargetLabel(member.device, phoneTargets)}</small><p>{member.assignment}</p></>
          : member.runner === 'model' || !member.runner ? <small>模型：{memberModelLabel(member) || '沿用实际启动时的主模型'} · 本机模型连接</small>
          : <><small>执行器：{harnessWorkerLabel(undefined, member.runner)} · 模型：{member.model || '执行器默认'}</small><p className="pw-field-hint">电脑：{computerLabel(member)}</p>
            <p className="pw-field-hint">开始协作前，核对这份已保存配置的实际执行器。</p><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.probeMember', { viewHandle: reading!.viewHandle, memberId: member.id }, value => setMemberProbes(old => ({ ...old, [`${definition.id}:${member.id}`]: { definition: JSON.stringify(member), value: object(value.result || value) } })))}>核对原执行器</Button>
            {memberProbes[`${definition.id}:${member.id}`]?.definition === JSON.stringify(member) && <HarnessEvidence probe={memberProbes[`${definition.id}:${member.id}`].value} id={`saved-member-evidence-${member.id}`} />}
          </>}
      </div>)}</div></section>
      <div className="pw-team-definition-settings"><Accordion variant="divided"><AccordionItem id="definition-management" title="管理团队配置"><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.reviewRemove', { viewHandle: reading!.viewHandle }, reviewResult)}>移除团队配置</Button></AccordionItem></Accordion></div>

    </article>}
    {current && <article className="pw-team-reading pw-team-run"><header className="pw-object-toolbar"><div><h2 tabIndex={-1}>{current.goal || current.teamName}</h2><p className="pw-detail-status">{status(current.status)}{current.phase ? ` · ${current.phase}` : ''}</p></div>{refreshReading}</header>
      {changed && <p role="status">有新的运行记录，当前阅读位置和未发送内容保留。</p>}
      {workspaceActive && !busy && !reading?.viewHandle && <p role="status">请刷新运行记录后继续操作，未发送的补充保留。</p>}
      <div className="pw-team-run-controls">{reading?.taskId && <Button variant="secondary" trailingIcon={IconExternalLink} disabled={materialBusy} onClick={() => openRelatedTask(reading.taskId)}>查看原任务与审批</Button>}<div className="pw-form-actions"><IconButton className="pw-utility-icon" icon={IconPause} aria-label="暂停全队" title="暂停全队" disabled={!canControl} onClick={() => control('pause')} /><IconButton className="pw-utility-icon" icon={IconPlay} aria-label="沿原额度继续" title="沿原额度继续" disabled={!canMutate || hasWorkPlan} onClick={() => control('resume')} /><Button variant="ghost" disabled={!canControl} onClick={() => reviewResult({ operation: 'cancel', viewHandle: reading!.viewHandle, runId: current.id })}>停止这次协作</Button></div></div>
      {reading?.taskLookupNotice && <p className="pw-field-hint" role="status">{reading.taskLookupNotice}</p>}
      {current.phase === 'intervention' && !hasWorkPlan && <div className="pw-collaboration-attention" role="status"><div><strong>新要求已保存，等待继续</strong><span>受影响成员已暂停；继续时使用新要求，沿用原剩余额度。</span></div><Button variant="secondary" disabled={!canMutate} onClick={() => control('resume')}>继续全队</Button></div>}
      {hasWorkPlan && <p className="pw-field-hint">继续工作或使用待处理消息时，请在原任务中核对具体工作项；不会按成员猜测要继续的分工。</p>}
      <Tabs selectedKey={runSection} onSelectionChange={value => { if (value === 'progress' || value === 'messages' || value === 'materials') chooseRunSection(value); }} className="pw-team-workbench">
        <div className="pw-team-workbench-bar"><TabList aria-label="这次协作的工作区"><Tab id="progress" isDisabled={materialBusy}>进展</Tab><Tab id="messages" count={messages.length} isDisabled={materialBusy}>沟通</Tab><Tab id="materials">资料</Tab></TabList><Button variant="secondary" disabled={!canMutate || materialBusy} onClick={() => jumpToRunSection('compose')}>给成员补充</Button></div>
        <TabPanel id="progress">
      {pendingQuestions.length > 0 && <section className="pw-team-action-list" aria-label="等待你的回答"><header className="pw-collaboration-group-heading"><h3>需要你回答</h3><span>{pendingQuestions.length} 条</span></header>{pendingQuestions.map(question => <Button key={question.messageId} variant="ghost" className="pw-team-question-row h-auto whitespace-normal" trailingIcon={IconChevronRight} onClick={() => focusMessage(question.messageId)}><TeamMemberIdentity member={teamMessageIdentity(question, members) || { id: question.memberId, name: name(question.memberId) }} name={teamMessageAuthor(question, members)} /><span className="pw-team-question-preview">{String(question.content || '查看原问题')}</span></Button>)}</section>}
          <div className="pw-team-progress-layout"><div className="pw-team-progress-main">
      <section className="pw-team-overview" data-team-section="members" tabIndex={-1} aria-label="成员与当前工作">
        <header className="pw-team-members-heading"><h3 className="pw-team-section-title">成员 <span>{currentMembers.length} 名参与{members.length > currentMembers.length ? ` · ${members.length - currentMembers.length} 名已退出` : ''}</span></h3>{simpleRoster && <Button variant="ghost" leadingIcon={IconAdd} disabled={!canMutate || !rosterPaused || currentMembers.length >= 4 || members.length >= 16} onClick={() => beginMemberChange('add')}>加入成员</Button>}</header>
        {simpleRoster && current.researchExecutionFormat && <p className="pw-field-hint">退出只停止这名成员后续参与，不转移资料或原成果作者。已完成成果仍受原资料范围与到期限制；可在“资料”中核对并撤回。新成员需另选自己的范围，再明确继续。</p>}
        {simpleRoster && !rosterPaused && current.status !== 'cancelled' && <p className="pw-field-hint">需要调整本次参与者时，先暂停全队；新成员加入后由你明确继续，不增加额度。</p>}
        <Accordion key={`${current.id}:members`} variant="divided" className="pw-team-disclosures" allowsMultipleExpanded expandedKeys={new Set(expandedMembers[current.id] || [])}
          onExpandedChange={keys => setExpandedMembers(old => ({ ...old, [current.id]: Array.from(keys, String) }))}>
          {members.map(member => {
            const model = memberModelLabel(member), activeItems = workItems.filter(item => item.memberId === member.id && item.status === 'running');
            const latestRound = rows(member.roundStates).find(round => round.round === current.round) || rows(member.roundStates).at(-1), memberTaskId = member.phoneTask?.taskId || (!hasWorkPlan ? member.assignmentTask?.taskId : undefined);
            const outputMessage = [...messages].reverse().find(message => message.memberId === member.id && ['answer', 'work_item_completed'].includes(message.kind) && message.content === member.text);
            const outputIdentity = outputMessage ? teamMessageIdentity(outputMessage, members) : null;
            const researchRecords = current.researchExecutionFormat ? teamResearchRecords(current.id, member) : [];
            const assignment = activeItems.length ? `正在做：${activeItems.map(item => label(item.title)).filter(Boolean).join('、')}` : label(member.assignment || member.role);
            return <AccordionItem key={member.id} id={member.id} className="pw-team-member" data-team-member-id={member.id} data-motion-key={`member:${member.id}`}
              title={<TeamMemberIdentity member={member} name={name(member.id)} detail={member.id === current.leaderId ? '负责人' : undefined} size={40} />}
              description={<><span className="pw-team-member-meta" title={model || undefined}>{[model, member.exited ? '已退出' : status(member.status), !hasWorkPlan && !member.exited && pendingCount(member) > 0 ? `${pendingCount(member)} 条待处理消息` : ''].filter(Boolean).join(' · ')}</span>{assignment && <span className="pw-team-member-task">{assignment}</span>}</>}>
              <div className="pw-team-expanded-copy">
                <p>{member.assignment || member.role}</p><p className="pw-muted">成员标识：{member.id}</p>
                {member.runner === 'phone_agent' && <p className="pw-muted">{teamPhoneTargetLabel(member.device, phoneTargets)}</p>}
                {isTeamHarness(member.runner || '') && <><p className="pw-muted">执行器：{harnessWorkerLabel(undefined, member.runner)} · 电脑：{computerLabel(member)}</p>{member.externalSession?.sessionId && <p className="pw-muted">原会话：{member.externalSession.sessionId}</p>}</>}
                {model && <p className="pw-muted">模型：{model}</p>}
                {latestRound?.message && <p className="pw-team-member-receipt">{latestRound.message}</p>}
                {latestRound?.nativeErrorMessage && <p className="pw-field-hint">{latestRound.nativeErrorMessage}</p>}
                {researchRecords.length > 0 && <section aria-label="原研究记录"><h4>原研究记录</h4><p className="pw-field-hint">查看完整产出、待处理事项和执行过程；打开不会继续工作，最新状态以原任务为准。</p>
                  <div className="pw-form-actions">{researchRecords.filter(record => !record.historical).map(record => <Button key={`${record.round}:${record.taskId}`} variant="ghost" trailingIcon={IconExternalLink} disabled={!workspaceActive || !reading?.viewHandle || busy || materialBusy || changed} onClick={() => openResearchTask(member.id, record)}>第 {record.round} 阶段记录{record.recordedStatus ? ` · 记录时${status(record.recordedStatus)}` : ''}</Button>)}</div>
                  {researchRecords.some(record => record.historical) && <Accordion variant="divided"><AccordionItem id="research-history" title="历史研究版本"><p className="pw-field-hint">这些版本已被后续输入替代，不代表当前成果，也不会转移给新成员。</p><div className="pw-form-actions">{researchRecords.filter(record => record.historical).map(record => <Button key={`${record.round}:${record.taskId}:${record.version}`} variant="ghost" trailingIcon={IconExternalLink} disabled={!workspaceActive || !reading?.viewHandle || busy || materialBusy || changed} onClick={() => openResearchTask(member.id, record)}>第 {record.round} 阶段 · 旧版本 {record.version}{record.recordedStatus ? ` · 记录时${status(record.recordedStatus)}` : ''}</Button>)}</div></AccordionItem></Accordion>}
                </section>}
                {member.text && <section className="pw-team-member-output" aria-label="已保存的成员产出"><h4>已保存的成员产出</h4>{outputIdentity && <TeamMemberIdentity member={outputIdentity} name={teamMessageAuthor(outputMessage!, members)} detail={outputIdentity.name !== member.name ? `原名称 · ${member.id}` : undefined} size={24} />}<AIAnswer text={String(member.text)} /></section>}
                {member.acceptance && <p>验收：{member.acceptance}</p>}
                {!!rows(member.dependsOn).length && <p>前置：{member.dependsOn.map(name).join('、')}</p>}
                {!member.exited && <div className="pw-form-actions"><Button variant="ghost" disabled={!canMutate} onClick={() => beginMemberChange('rename', member)}>名称与头像</Button>{simpleRoster && <Button variant="ghost" disabled={!canMutate || !rosterPaused} onClick={() => beginMemberChange('exit', member)}>退出本次协作</Button>}</div>}
                <div className="pw-form-actions"><Button variant="secondary" onClick={() => { chooseMemberFilter(member.id); jumpToRunSection('messages'); }}>查看成员沟通</Button>{!member.exited && <Button variant="ghost" disabled={!canMutate || !!uncertain} onClick={() => { setRecipient(member.id); setReplyId(''); if (mode === 'feedback') setMode('discussion'); jumpToRunSection('compose'); }}>补充分工</Button>}{memberTaskId && <Button variant="ghost" trailingIcon={IconExternalLink} onClick={() => openRelatedTask(memberTaskId)}>{member.runner === 'phone_agent' ? '查看手机任务与结果' : '查看原分工与审批'}</Button>}<Button variant="ghost" disabled={!canControl || member.exited} onClick={() => control('pause', member.id)}>暂停该成员</Button><Button variant="ghost" disabled={!canMutate || member.exited || hasWorkPlan} onClick={() => control('resume', member.id)}>继续该成员</Button>{!hasWorkPlan && !member.exited && pendingCount(member) > 0 && <Button variant="ghost" disabled={!canMutate || pendingMessageBusy(member)} onClick={() => control('continueMessages', member.id)}>使用 {pendingCount(member)} 条待处理消息继续</Button>}</div>
              </div>
            </AccordionItem>;
          })}
        </Accordion>
      </section>
      {!!workItems.length && <section className="pw-team-overview" aria-label="这次协作的工作项">
        <h3 className="pw-team-section-title">工作项 <span>{workItems.length}</span></h3>
        <Accordion key={`${current.id}:items`} variant="divided" className="pw-team-disclosures" allowsMultipleExpanded expandedKeys={new Set(expandedItems[current.id] || [])}
          onExpandedChange={keys => setExpandedItems(old => ({ ...old, [current.id]: Array.from(keys, String) }))}>
          {workItems.map(item => <AccordionItem key={item.workItemId} id={item.workItemId} className="pw-team-work-item" data-work-item-id={item.workItemId} data-motion-key={`work-item:${item.workItemId}`}
            title={<span className="pw-team-item-heading"><span>{item.title}</span><small>{status(item.status)}</small></span>}
            description={<TeamMemberIdentity member={members.find(member => member.id === item.memberId) || { id: item.memberId }} name={name(item.memberId)} size={24} />}>
            <div className="pw-team-expanded-copy"><p>{item.instruction}</p>{item.message && <p>{item.message}</p>}{item.nativeErrorMessage && <p className="pw-field-hint">{item.nativeErrorMessage}</p>}{item.assignmentTask?.taskId && <Button variant="secondary" trailingIcon={IconExternalLink} onClick={() => openRelatedTask(item.assignmentTask.taskId)}>查看此分工与审批</Button>}{item.acceptance && <p>验收：{item.acceptance}</p>}
              {!!rows(item.dependsOn).length && <p>前置：{item.dependsOn.map((id: string) => workItems.find(prior => prior.workItemId === id)?.title || id).join('、')}</p>}
            </div>
          </AccordionItem>)}
        </Accordion>
      </section>}
          </div><div className="pw-team-progress-summary">
      {current.summary && <section className="pw-team-summary" data-motion-key="summary" aria-label="本次协作小结"><h3 className="pw-team-section-title">本次小结</h3>{current.summaryStale && <p className="pw-muted">这份总结待更新</p>}<AIAnswer text={current.summary} /></section>}
          {!current.summary && <p className="pw-team-progress-empty">{current.status === 'cancelled' ? '这次协作已停止，已有消息和资料仍可查看。' : '成员产出后会在这里汇总。先查看各自的分工与当前进展。'}</p>}
          </div></div>
        </TabPanel>
        <TabPanel id="messages">
          <div className="pw-team-member-filter" aria-label="按成员查看沟通"><Button variant="ghost" aria-pressed={!memberFilter} onClick={() => chooseMemberFilter('')}>全队</Button>{members.map(member => <Button key={member.id} variant="ghost" aria-pressed={memberFilter === member.id} onClick={() => chooseMemberFilter(member.id)}><TeamMemberIdentity member={member} name={name(member.id)} size={28} /></Button>)}</div>
          <p className="pw-team-conversation-hint">{memberFilter ? `只看与 ${name(memberFilter)} 有关的共享记录；补充仍发送给下方明确选择的成员。` : '这次协作的共享记录。选成员可聚焦其消息，不会建立私聊或改变收件人。'}</p>
      <section className="pw-team-messages" data-team-section="messages" tabIndex={-1} aria-label="原团队消息"><header className="pw-collaboration-group-heading"><h3>团队沟通</h3><span>{visibleMessages.length} 条消息</span></header>{visibleMessages.map(message => {
        const identity = teamMessageIdentity(message, members), sender = teamMessageAuthor(message, members);
        const quoted = message.quoteMessageId ? object(message.quotedMessage || messages.find(row => row.messageId === message.quoteMessageId)) : null;
        const quotedText = String(quoted?.content || ''), quoteIsExcerpt = quoted?.truncated || quotedText.length > 4000;
        return <article className="pw-source-record pw-team-message" key={message.messageId} data-message-id={message.messageId} data-motion-key={`message:${message.messageId}`} data-reply-selected={replyId === message.messageId || undefined} tabIndex={-1}>
        <header className="pw-team-message-heading"><h3>{identity ? <TeamMemberIdentity member={identity} name={sender} detail={members.some(member => member.id === identity.id && member.name !== identity.name) ? `原名称 · ${identity.id}` : members.filter(member => member.name === identity.name).length > 1 ? identity.id : undefined} /> : sender}{(message.recipientMemberId || message.targetMemberId) && <small>→ {name(message.recipientMemberId || message.targetMemberId)}</small>}</h3>{message.kind === 'assignment_question' && message.state === 'pending' ? <span>等待你的回答</span> : message.role === 'tool' ? <span>资料回执</span> : message.role === 'system' ? <span>执行记录</span> : null}</header>
        {quoted && quoted.messageId && <div className="pw-team-reply-context"><header><span>{quoteIsExcerpt ? '引用节选' : '引用原消息'} · {teamMessageIdentity(quoted, members) ? <TeamMemberIdentity member={teamMessageIdentity(quoted, members)!} name={teamMessageAuthor(quoted, members)} size={24} /> : teamMessageAuthor(quoted, members)}</span><Button variant="ghost" onClick={() => focusMessage(String(message.quoteMessageId))}>查看原消息</Button></header><AIAnswer text={quotedText.slice(0, 4000)} showActions={false} /></div>}
        <AIAnswer text={String(message.content || '')} actions={(message.kind !== 'assignment_question' || message.state === 'pending') && <Button variant="ghost" disabled={!canMutate || message.kind === 'assignment_question' && !members.some(member => member.id === message.memberId && !member.exited)} aria-pressed={replyId === message.messageId} onClick={() => { setReplyId(message.messageId); if (mode === 'feedback') setMode('discussion'); if (members.some(member => member.id === message.memberId && !member.exited)) setRecipient(message.memberId); jumpToRunSection('compose'); }}>{message.kind === 'assignment_question' ? '回答原问题' : '引用回复'}</Button>} />
        <MotionFeedback initial={false} identity={teamMessageDelivery(current, message)} className="pw-muted">{teamMessageDelivery(current, message)}</MotionFeedback>{message.answer && <div className="pw-team-saved-answer"><span>已保存的回答</span><p>{message.answer}</p></div>}
      </article>; })}{!visibleMessages.length && <p className="pw-secondary-empty">{memberFilter ? '这名成员还没有可显示的沟通记录。' : '这次协作还没有消息。'}</p>}</section>
      <Accordion variant="divided" className="pw-team-compose-disclosure" expandedKeys={composeOpen || !!uncertain ? ['team-compose'] : []} onExpandedChange={keys => setComposeOpen(keys.has('team-compose'))}><AccordionItem id="team-compose" title="补充给成员" description={reply ? '正在回复所选消息' : text ? '有未发送内容' : undefined}>
      <section className="pw-team-compose" data-team-section="compose" aria-label="给成员补充">
      {reply && <div className="pw-team-reply-context"><header><span>{reply.kind === 'assignment_question' ? '回答原问题' : '引用原消息'} · {teamMessageIdentity(reply, members) ? <TeamMemberIdentity member={teamMessageIdentity(reply, members)!} name={teamMessageAuthor(reply, members)} size={24} /> : teamMessageAuthor(reply, members)}</span><Button variant="ghost" disabled={busy || !!uncertain} onClick={() => setReplyId('')}>取消引用</Button></header><AIAnswer text={String(reply.content || '')} showActions={false} /></div>}
      <SelectField label="收件成员" value={recipient} options={[...(mode === 'feedback' ? [{ id: '*', label: '全队' }] : []), ...members.filter(member => !member.exited).map(member => ({ id: member.id, label: name(member.id) }))]} onChange={setRecipient} isDisabled={busy || !!uncertain || reply?.kind === 'assignment_question'} />
      {reply?.kind !== 'assignment_question' && <SelectField label="补充方式" value={mode} options={[{ id: 'discussion', label: '讨论补充' }, { id: 'queue', label: '排入下一安全工作点' }, ...(canChangeRequirements && !replyId ? [{ id: 'feedback', label: '修改要求并暂停' }] : [])]} onChange={changeMessageMode} isDisabled={busy || !!uncertain} />}
      <FormDraftNotice draft={messageDraft} />{messageDraft.requiresRecheck && <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { void invoke('teams.readRun', { runId: current.id }, value => { if (value.reading?.run?.id !== current.id) throw new Error('原协作身份不匹配'); acceptReading(value.reading); setMessageChecked(true); }); }}>读取原消息核对结果</Button><Button variant="secondary" disabled={busy || !messageChecked || !!uncertain} onClick={() => { messageDraft.clearSubmitted(); setMessageChecked(false); }}>已核对原消息，继续编辑</Button></div>}
      <TextAreaField label={reply?.kind === 'assignment_question' ? '回答这条原问题' : '发给所选成员'} value={text} onChange={value => setDrafts(old => ({ ...old, [draftKey]: value }))} maxLength={4000} showCount isDisabled={busy || messageDraft.loading || !!uncertain} hint={mode === 'feedback' ? '保存新要求并暂停受影响成员，确认后再继续。不会扩大权限或追加预算。' : '消息会排队，不打断成员。已结束的成员需在成员详情中选择“使用待处理消息”。'} />
      <PressButton label={reply?.kind === 'assignment_question' ? '保存原回答' : mode === 'feedback' ? '核对并暂停补充' : '发送补充'} disabled={!canMutate || messageDraft.loading || !!messageDraft.conflict || messageDraft.requiresRecheck || !text.trim() || text.length > 4000 || !recipient} onClick={send} />
      {mode !== 'feedback' && !replyId && recipientPending > 0 && <div className="pw-form-actions"><p className="pw-field-hint">{name(recipient)}有 {recipientPending} 条已排队消息尚未确认消费。{recipientMember && !pendingMessageBusy(recipientMember) ? '继续只使用已保存的消息，不提交当前草稿或追加额度。' : '成员正在工作或等待授权，消息保留在原队列。'}</p>{recipientMember && !pendingMessageBusy(recipientMember) && <Button variant="secondary" disabled={!canMutate} onClick={() => control('continueMessages', recipient)}>使用已排队消息继续</Button>}</div>}
      {uncertain && <p role="status">这条原消息仍待核对，草稿保留，不会重复发送。刷新原记录后仍可暂停或停止团队。</p>}
      </section>
      </AccordionItem></Accordion>

        </TabPanel>
        <TabPanel id="materials" shouldForceMount data-team-materials-active={runSection === 'materials'}>
      <div data-team-section="materials" tabIndex={-1}><TeamEvidenceWorkspace key={current.id} runId={current.id} members={members} run={run} disabled={!canMutate} active={workspaceActive && runSection === 'materials'} windowRevision={Number(state.webWorkspaceResumeRevision || 0)} onBackChange={handler => { materialBack.current = handler; }} onBeforeLeaveChange={handler => { materialBeforeLeave.current = handler; }} onBusyChange={setMaterialBusy} /></div>
        </TabPanel>
      </Tabs>
    </article>}
    <SettingsDialog isOpen={!!memberDraft && !review} title={memberDraft?.action === 'add' ? '加入本次协作' : memberDraft?.action === 'exit' ? '退出本次协作' : '本次协作中的名称与头像'} onClose={() => { if (!busy) memberFormDraft.guard(() => setMemberDraft(null)); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">{memberDraft && <>
      {error && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}
      <FormDraftNotice draft={memberFormDraft} />{draftRecovery}
      <TeamMemberIdentity member={{ ...memberDraft, id: memberDraft.memberId }} size={40} />
      {memberDraft.action === 'exit' ? <>
        <p>保留这名成员的历史消息和产出，不把旧消息、资料或分工转给同名新成员。退出不会撤销已经发生的效果。</p>
        {current?.researchExecutionFormat && <p>退出研究后，已完成成果仍以原作者与原资料授权核验，范围撤回、换版或到期后不能继续用于新回答。退出成员的“资料”页只保留核对与撤回，不自动续权；未回答问题、待批或未知操作会阻止退出。</p>}
        {current?.leaderId === memberDraft.memberId && currentMembers.length > 1 && <SelectField label="由谁接任负责人" value={memberDraft.newLeaderId} options={[{ id: '', label: '选择接任成员' }, ...currentMembers.filter(member => member.id !== memberDraft.memberId).map(member => ({ id: member.id, label: name(member.id) }))]} onChange={value => setMemberDraft({ ...memberDraft, newLeaderId: value })} isDisabled={busy} />}
        {currentMembers.length === 1 && <p className="pw-field-hint">最后一名成员退出后，协作将等待你加入新成员，不会自行恢复。</p>}
        {members.some(member => !member.exited && rows(member.dependsOn).includes(memberDraft.memberId)) && <p className="pw-field-hint">仍有成员依赖这份产出。核对时会阻止遗留未完成前置分工，不会静默重派。</p>}
      </> : <>
        <TextField label="成员名称" value={memberDraft.name} onChange={value => setMemberDraft({ ...memberDraft, name: value })} maxLength={48} isRequired isDisabled={busy} />
        <div className="pw-team-avatar-picker" role="group" aria-label="成员头像"><span className="pw-field-hint">头像形态</span><div>{teamAvatarShapes.map((shape, index) => <Button key={shape} variant="ghost" aria-label={`选择${['柔团', '卵石', '圆方', '蛋形'][index]}头像`} aria-pressed={memberDraft.avatarShape === shape} disabled={busy} onClick={() => setMemberDraft({ ...memberDraft, avatarShape: shape })}><TeamMemberAvatar member={{ id: memberDraft.memberId, avatarShape: shape }} size={40} /><span>{['柔团', '卵石', '圆方', '蛋形'][index]}</span></Button>)}</div></div>
        {memberDraft.action === 'add' ? <>
          <TextAreaField label="职责" value={memberDraft.role} onChange={value => setMemberDraft({ ...memberDraft, role: value })} maxLength={1200} showCount isRequired isDisabled={busy} />
          <TextAreaField label="这次具体分工" value={memberDraft.assignment} onChange={value => setMemberDraft({ ...memberDraft, assignment: value })} maxLength={2000} showCount isRequired isDisabled={busy} />
          <TextField label="模型 ID，留空使用确认加入时的主模型" value={memberDraft.model} onChange={value => setMemberDraft({ ...memberDraft, model: value })} maxLength={200} isDisabled={busy} />
          <p className="pw-field-hint">新增独立的本机分析成员，以暂停状态加入；不会继承退出成员的独立资料范围、定向消息或审批，也不增加预算。核对后从成员详情明确继续。</p>
        </> : <p className="pw-field-hint">只改这次协作的显示身份，不改变职责、模型、权限或暂停状态；历史消息与正在执行的原轮次保留原名字和头像。下次工作的团队配置不变。</p>}
      </>}
      {(!reading?.viewHandle || changed) && <div className="pw-form-actions"><p className="pw-field-hint" role="status">当前记录需重新核对，编辑不会自动提交。若上次改动回执未知，会先关闭旧编辑并显示实际成员状态。</p><Button variant="secondary" disabled={busy || !current} onClick={() => { if (current) readRun(current.id); }}>重读原记录</Button></div>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => memberFormDraft.guard(() => setMemberDraft(null))}>返回并保留草稿</Button>{memberDraft.action !== 'exit' && <Button variant="ghost" disabled={busy || memberFormDraft.requiresRecheck} onClick={() => memberFormDraft.discard(() => setMemberDraft(null))}>放弃草稿</Button>}<Button disabled={!canMutate || memberDraft.action !== 'exit' && !label(memberDraft.name).trim() || memberDraft.action === 'add' && (!label(memberDraft.role).trim() || !label(memberDraft.assignment).trim()) || memberDraft.action === 'exit' && current?.leaderId === memberDraft.memberId && currentMembers.length > 1 && !memberDraft.newLeaderId} onClick={reviewMemberChange}>核对这次改动</Button></div>
    </>}</div></SettingsDialog>
    <SettingsDialog isOpen={!!phoneSelection} title="这次手机分工的范围" onClose={() => { if (!busy) closePhoneSelection(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">{phoneSelection && <>
      {error && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}
      <p>{phoneSelection.view?.goal}</p>
      <p className="pw-field-hint">每名成员选择自己的手机与应用范围；同一应用、同一 Android 用户仍会协调占用。</p>
      {phoneMembers.map(member => {
        const choice = phoneChoices[member.memberId] || teamPhoneChoice(), selected = rows(member.options).find(option => option.optionId === choice.optionId);
        return <section className="pw-source-record" key={member.memberId}>
          <h3><TeamMemberIdentity member={rows(definition?.members).find(row => row.id === member.memberId) || { ...member, id: member.memberId }} name={teamMemberLabel({ ...member, id: member.memberId }, phoneMembers)} /></h3><p className="pw-muted">{teamPhoneTargetLabel(member.device, phoneTargets)}</p>
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
    <SettingsDialog isOpen={!!editor && !review} title={definition ? '编辑下次工作的团队' : '新建团队'} onClose={() => { if (!busy) formDraft.guard(() => setEditor(null)); }}><div className="pw-collaboration-dialog pw-team-editor-dialog" data-collaboration-kind="TeamsWorkspace">{editor && <><div className="pw-team-editor-scroll">
      <FormDraftNotice draft={formDraft} />{draftRecovery}
      {!definition && <section className="pw-team-start"><TextAreaField label="这次想一起完成什么" value={editor.goal || ''} onChange={changeTeamGoal} maxLength={8000} rows={3} autoResize maxRows={8} isDisabled={busy} hint="例如：比较两种出行方案，给出费用、取舍与建议。" /><p className="pw-field-hint">采用两人起步分工，你可以继续调整；这里还没有请求模型或启动任务。</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setEditor({ ...editor, members: starterMembers('research'), leaderId: 'member-1', mode: 'collaborate' })}>研究与核对</Button><Button variant="secondary" disabled={busy} onClick={() => setEditor({ ...editor, members: starterMembers('options'), leaderId: 'member-1', mode: 'collaborate' })}>提出方案与评估</Button></div><div className="pw-team-roster-summary">{rows(editor.members).map(member => <TeamMemberIdentity key={member.id} member={member} name={member.name} detail={member.role} size={32} />)}</div></section>}
      <Accordion defaultExpandedKeys={definition ? ['team-details'] : []}><AccordionItem id="team-details" title="调整团队与成员" description={`${editor.name} · ${editor.members.length} 名成员`}>
      <TextField label="团队名称" value={editor.name} onChange={value => setEditor({ ...editor, name: value, nameEdited: true })} maxLength={80} isRequired isDisabled={busy} />
      <SelectField label="协作方式" value={editor.mode} options={[{ id: 'collaborate', label: '共同工作' }, ...(!rows(editor.members).some(member => member.runner === 'phone_agent') ? [{ id: 'debate', label: '取证与讨论' }, ...(catalog.writeAssignmentsConfigured === true || editor.mode === 'produce' ? [{ id: 'produce', label: '项目文件协作' }] : [])] : [])]} onChange={value => setEditor({ ...editor, mode: value })} isDisabled={busy} />
      {editor.mode === 'produce' && <p className="pw-field-hint">每名成员只修改各自负责的文件。从原项目任务交给这个团队时，仍需核对文件范围、独立远端任务与本机整合检查；保存配置不会启动工作。</p>}
      {discussionError && <p className="pw-field-error" role="status">{discussionError}</p>}
      <Accordion variant="divided" className="pw-team-editor-members" allowsMultipleExpanded expandedKeys={new Set(editorExpanded)} onExpandedChange={keys => setEditorExpanded(Array.from(keys, String))}>{rows(editor.members).map((member, index) => {
        const original = rows(definition?.members).find(row => row.id === member.id), external = !['model', 'phone_agent'].includes(member.runner || 'model');
        const targetKey = teamPhoneTargetKey(member.device), targetOptions = phoneTargets.map(target => ({ id: teamPhoneTargetKey(target), label: `${target.label}${target.available === false ? ' · 暂不可用' : ''}` }));
        if (targetKey && !targetOptions.some(target => target.id === targetKey)) targetOptions.push({ id: targetKey, label: '原手机目标 · 当前不可用' });
        return <AccordionItem key={member.id} id={member.id} title={<TeamMemberIdentity member={member} name={teamMemberLabel(member, rows(editor.members))} />} description={editorMemberIssue(member) || member.role || undefined}><section id={`team-editor-member-${member.id}`} className="pw-source-record pw-team-editor-member">
          <TextField label={`成员 ${index + 1} 名称`} value={member.name} onChange={value => updateMember(member.id, { name: value })} maxLength={48} isRequired isDisabled={busy} />
          <div className="pw-team-avatar-picker" role="group" aria-label="成员头像"><span className="pw-field-hint">头像形态</span><div>{teamAvatarShapes.map((shape, shapeIndex) => <Button key={shape} variant="ghost" aria-label={`选择${['柔团', '卵石', '圆方', '蛋形'][shapeIndex]}头像`} aria-pressed={teamAvatarShape(member) === shape} disabled={busy} onClick={() => updateMember(member.id, { avatarShape: shape })}><TeamMemberAvatar member={{ ...member, avatarShape: shape }} size={40} /><span>{['柔团', '卵石', '圆方', '蛋形'][shapeIndex]}</span></Button>)}</div></div>
          <TextAreaField label="职责" value={member.role} onChange={value => updateMember(member.id, { role: value })} maxLength={1200} showCount isRequired isDisabled={busy} />
          <SelectField label="成员做什么" value={external ? 'computer' : member.runner || 'model'} options={[{ id: 'model', label: '分析与讨论' }, { id: 'computer', label: '在电脑上使用执行器工作' }, ...(editor.mode === 'collaborate' && !original?.writeFiles ? [{ id: 'phone_agent', label: '操作手机完成分工' }] : [])]}
            isDisabled={busy || !!original?.modelTarget} onChange={runner => updateMember(member.id, runner === 'phone_agent'
              ? { runner, model: '', modeId: undefined, externalExecutionRiskAcknowledged: undefined, device: { kind: 'local_phone', screenMode: 'background' }, assignment: member.assignment || member.role, dependsOn: member.dependsOn || [] }
              : runner === 'computer' ? { runner, model: '', modeId: '', device: { profileId: '', environmentId: '' }, externalExecutionRiskAcknowledged: false }
              : { runner, device: undefined, modeId: undefined, externalExecutionRiskAcknowledged: undefined })} />
          {member.runner === 'phone_agent' ? <>
            <SelectField label="在哪台手机上工作" value={targetKey} options={targetOptions} isDisabled={busy} onChange={key => { const target = phoneTargets.find(value => teamPhoneTargetKey(value) === key); if (target) updateMember(member.id, { device: teamPhoneDevice(target) }); }} hint="应用范围与预算在每次启动前核对。" />
          </> : external ? <TeamHarnessFields member={member} profiles={computerProfiles} run={run} disabled={busy} productionMode={editor.mode === 'produce' ? member.production?.sourceMode || 'snapshot' : undefined} onChange={changes => updateMember(member.id, changes)} onReady={ready => setExternalReady(old => old[member.id] === ready ? old : { ...old, [member.id]: ready })} />
            : <TextField label="模型 ID，留空沿用主模型" value={member.model} onChange={value => updateMember(member.id, { model: value })} maxLength={200} isDisabled={busy || !!original?.modelTarget} />}
          <TextAreaField label="具体分工" value={member.assignment || ''} onChange={value => updateMember(member.id, { assignment: value })} maxLength={2000} showCount isRequired={member.runner === 'phone_agent'} isDisabled={busy} hint={member.runner === 'phone_agent' ? '手机成员按这份具体分工执行。' : '留空沿用上面的职责；填写后按这份具体分工执行。'} />
          <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id={`member-dependencies-${member.id}`} title="先等待哪些成员完成">{rows(editor.members).filter(row => row.id !== member.id).map(row => <CheckboxField key={row.id} isSelected={(member.dependsOn || []).includes(row.id)} isDisabled={busy || evidenceDebate && !(member.dependsOn || []).includes(row.id)} onChange={selected => updateMember(member.id, { dependsOn: selected ? [...(member.dependsOn || []), row.id] : (member.dependsOn || []).filter((id: string) => id !== row.id) })}>{row.name || row.id}</CheckboxField>)}<p className="pw-field-hint">{evidenceDebate ? '取证与讨论首轮必须独立进行，请清除已有前置成员。' : '不选择时可同时开始；前置关系不能互相循环。'}</p></AccordionItem></Accordion>
          {editor.mode === 'produce' && <TeamProductionFields member={member} sources={object(catalog.productionSources)} disabled={busy} onChange={changes => updateMember(member.id, changes)} />}
          <Button variant="ghost" disabled={busy || editor.members.length <= 1} onClick={() => { const next = rows(editor.members).filter(row => row.id !== member.id).map(row => Array.isArray(row.dependsOn) ? { ...row, dependsOn: row.dependsOn.filter((id: string) => id !== member.id) } : row); setEditorExpanded(old => old.filter(id => id !== member.id)); setEditor({ ...editor, members: next, leaderId: editor.leaderId === member.id ? next[0].id : editor.leaderId }); }}>移除此成员</Button>
        </section></AccordionItem>;
      })}</Accordion>
      <Button variant="ghost" leadingIcon={IconAdd} disabled={busy || editor.members.length >= 4} onClick={addMember}>添加成员 · {editor.members.length}/4</Button>
      <SelectField label="负责人" value={editor.leaderId} options={rows(editor.members).map(member => ({ id: member.id, label: member.name || member.id }))} onChange={value => setEditor({ ...editor, leaderId: value })} isDisabled={busy} />
      <p className="pw-field-hint">保存只影响后续新工作。</p></AccordionItem></Accordion>
      {productionError && <p className="pw-field-hint" role="status">{productionError}</p>}
      </div><footer className="pw-team-editor-footer">{error && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}{incompleteMembers.length > 0 && <div className="pw-team-editor-missing" role="status"><span>还有 {incompleteMembers.length} 名成员需要补充</span><Button variant="ghost" disabled={busy} onClick={() => revealMember(incompleteMembers[0].id)}>{incompleteMembers[0].name || '未命名成员'}：{editorMemberIssue(incompleteMembers[0])}</Button></div>}{!editor.name.trim() && <p className="pw-field-hint">填写团队名称后保存。</p>}{busy && <p className="pw-field-hint" role="status">正在核对这次配置，请等待回执。</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => formDraft.guard(() => setEditor(null))}>返回并保留草稿</Button><Button variant="ghost" disabled={busy || formDraft.requiresRecheck} onClick={() => formDraft.discard(() => setEditor(null))}>放弃草稿</Button><Button disabled={busy || formDraft.stale || !!formDraft.conflict || formDraft.requiresRecheck || (!definition && !editor.goal?.trim()) || !definitionReady || !editorPayload || !editor.name.trim() || editor.name.length > 80 || incompleteMembers.length > 0 || !!discussionError} onClick={() => { if (editorPayload) void invoke('teams.reviewDefinition', { ...editorPayload, ...(definition ? { viewHandle: reading!.viewHandle } : {}) }, reviewResult); }}>{definition ? '核对并保存' : '核对团队并继续'}</Button></div></footer>
    </>}</div></SettingsDialog>
    <SettingsDialog isOpen={!!review} title={review?.operation === 'configureMember' ? '核对本次成员改动' : review?.operation === 'start' ? '开始这次原团队协作' : review?.operation === 'cancel' ? '停止这次协作' : review?.operation === 'feedback' ? '修改要求并暂停' : review?.operation === 'remove' ? '移除团队配置' : '保存团队配置'} onClose={() => { if (!busy) closeReview(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">
      {review?.operation === 'configureMember' ? <>
        <TeamMemberIdentity member={review.member || {}} name={teamMemberLabel(review.member || {}, rows(review.roster))} size={40} />
        {review.input?.action === 'rename' ? <><p>改为：</p><TeamMemberIdentity member={{ ...review.member, name: review.input.name, avatarShape: review.input.avatarShape }} size={40} /><p>历史消息和已开始的轮次保留原身份；不改职责、权限、额度或执行状态。</p></>
          : review.input?.action === 'add' ? <><p>职责：{review.member?.role}</p><p>具体分工：{review.member?.assignment}</p><p>本机模型：{review.member?.model || '确认加入时的主模型'}</p><p>以暂停状态加入当前运行，保留独立新身份；未开始工作、未继承旧成员的独立资料范围或定向消息、未增加额度。同队共享目标和公开协作记录仍可用于这次分工。回到进展页后明确继续。</p></>
          : <><p>退出后保留已有产出和历史消息，未消费的补充不再唤醒这名成员；不会把任务交给其他成员。</p>{review.researchExit && <p>仅停止这名成员后续执行。旧成果按冻结作者与原资料范围核验，接任者不继承其资料读取权；到期、换版或撤回后不可继续用于新回答，可在“资料”中核对与撤回。</p>}{review.input?.newLeaderId && <p>接任负责人：{teamMemberLabel(rows(review.roster).find(member => member.id === review.input.newLeaderId) || { id: review.input.newLeaderId }, rows(review.roster))}</p>}<p>尚未完成的前置依赖会阻止退出。暂停和退出不承诺撤销已发生的效果。</p></>}
      </> : review?.operation === 'start' ? <><p>{review.input?.goal}</p><p>{review.definition?.name}</p><div className="pw-team-roster-summary">{rows(review.definition?.members).map(member => <TeamMemberIdentity key={member.id} member={member} name={teamMemberLabel(member, rows(review.definition?.members))} size={32} />)}</div>{review.phoneScope ? <><p>手机成员将使用下列已核对的范围，模型调用会计入对应任务的额度。</p>{rows(review.phoneScope.members).map(member => <section className="pw-source-record" key={member.memberId}><h3><TeamMemberIdentity member={rows(review.definition?.members).find(value => value.id === member.memberId) || { ...member, id: member.memberId }} name={teamMemberLabel(rows(review.definition?.members).find(value => value.id === member.memberId) || { ...member, id: member.memberId }, rows(review.definition?.members))} /></h3><p>{teamPhoneTargetLabel(member.device, phoneTargets)}{teamPhoneScopeLabel(member.resourceBinding) ? ` · ${teamPhoneScopeLabel(member.resourceBinding)}` : ''}</p><p>最多 {member.toolBudget} 次操作 · {member.modelBudget} 次模型调用</p>{member.sourceQueryToolReserve > 0 && <p className="pw-field-hint">另预留 {member.sourceQueryToolReserve} 次目标任务状态查询。</p>}<p className="pw-muted">有效至 {new Date(member.expiresAt).toLocaleString('zh-CN')}</p></section>)}</> : <p>沿原团队与实际启动时的模型配置开始，可能消耗模型额度。文件写入与额外预算继续使用原任务的独立审批。</p>}</> : review?.operation === 'feedback' ? <><p>发给{review.recipientName}的新要求：</p><AIAnswer text={review.text} showActions={false} /><p>相关成员会暂停；已有成果保留，受影响的总结待更新。你选择继续后才会处理新要求，不自动扩大权限或预算。</p></> : review?.operation === 'cancel' ? <p>停止这个原运行后续工作，保留消息、证据和已有成果。停止请求不代表未知外部操作已完成清理。</p> : review?.operation === 'remove' ? <p>移除“{review.definition?.name}”的后续启动配置，已有运行和成果保留。</p> : <><p>{review?.input?.name}</p>{rows(review?.input?.members).map(member => <section className="pw-source-record" key={member.id}><h3><TeamMemberIdentity member={member} name={teamMemberLabel(member, rows(review?.input?.members))} size={40} /></h3><p>{member.role}</p>{member.assignment && member.assignment !== member.role && member.runner !== 'phone_agent' && <p>具体分工：{member.assignment}</p>}{rows(member.dependsOn).length > 0 && <p>前置：{member.dependsOn.map((id: string) => rows(review?.input?.members).find(row => row.id === id)?.name || id).join('、')}</p>}{member.runner === 'phone_agent' ? <p>{teamPhoneTargetLabel(member.device, phoneTargets)} · {member.assignment}</p> : isTeamHarness(member.runner) ? <><p>执行器：{harnessWorkerLabel(undefined, member.runner)} · 电脑：{computerLabel(member)}</p><p>模型：{member.model || '执行器默认'}{member.modeId ? ` · 模式：${member.modeId}` : ''}</p></> : <p>模型：{member.model || '实际启动时的主模型'} · 本机模型连接</p>}{review?.input?.mode === 'produce' && <TeamProductionReview member={member} sources={object(catalog.productionSources)} />}</section>)}</>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>已核对这次操作与原目标</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={async () => {
        const original = review; if (!original || entered.current || backgrounded.current || !ownsNativeWindow()) return; setReview(null); setAcknowledged(false);
        setBusy(true);
        try { if (original.operation === 'save') await formDraft.markSubmitted(); else if (original.operation === 'start') await startDraft.markSubmitted(); else if (original.operation === 'configureMember' && memberDraft?.action !== 'exit') await memberFormDraft.markSubmitted(); else if (original.operation === 'feedback') await messageDraft.markSubmitted(); } catch (failure) { setBusy(false); setError((failure as Error).message); return; }
        if (!alive.current || !ownsNativeWindow()) return;
        const directControl = original.operation === 'cancel' || original.operation === 'feedback';
        if (original.operation === 'configureMember') { pendingMemberChange.current = original.input.runId; setReading(value => value ? { ...value, viewHandle: '' } : null); }
        if (directControl) setReading(value => value ? { ...value, viewHandle: '' } : null);
        void invoke(directControl ? 'teams.control' : 'teams.confirm', directControl ? { command: original.operation, viewHandle: original.viewHandle,
          ...(original.operation === 'feedback' ? { memberId: original.memberId, text: original.text.trim() } : {}) } : { reviewHandle: original.reviewHandle }, result => {
          if (result.confirmed !== true) throw new Error('原团队操作尚未确认，请刷新核对。');
          if (original.operation === 'configureMember') {
            if (result.command !== 'configureMember' || result.reading?.run?.id !== original.input.runId) throw new Error('成员改动回执待核对，请刷新原协作，没有自动重发。');
            pendingMemberChange.current = null; if (original.input.action !== 'exit') memberFormDraft.discard(() => setMemberDraft(null)); else setMemberDraft(null); acceptReading(result.reading);
            if (original.input.action === 'add') setExpandedMembers(old => ({ ...old, [original.input.runId]: [...new Set([...(old[original.input.runId] || []), original.member.id])] }));
            if (original.input.action === 'exit' && recipient === original.input.memberId) { setRecipient(result.reading.run.leaderId || ''); setReplyId(''); }
            setNotice(original.input.action === 'add' ? '新成员已加入并暂停。核对分工后，明确继续该成员；没有追加额度。' : original.input.action === 'exit' ? '成员已退出本次协作，历史与成果保留，没有自动重派。' : '本次协作的名称与头像已更新，历史身份和团队配置保留。');
            return;
          }
          if (result.command === 'save') { const nextId = result.reading?.definition?.id || result.result?.id; if (nextId && editor?.goal) startInputs.current.set(nextId, { goal: editor.goal, rounds: '1' }); formDraft.discard(() => setEditor(null)); } else setEditor(null); setNotice(result.command === 'save' ? '团队已保存，目标已带入。核对目标与分工后，明确开始这次协作。' : '已收到原操作回执。');
          if (original.operation === 'feedback') {
            if (result.command !== 'feedback' || result.reading?.run?.id !== original.runId) throw new Error('补充结果待核对，请刷新原协作记录；不会自动重发。');
            setDrafts(old => old[original.draftKey] === original.text ? { ...old, [original.draftKey]: '' } : old);
            messageDraft.discard(() => { void messageDraft.open('owner', 'message:' + original.draftKey, original.draftKey, { text: '' }); });
            setNotice('新要求已保存，受影响成员已暂停。选择继续后处理，未自动重跑。');
          }
          if (result.reading) { acceptReading(result.reading); if (original.operation === 'start') { startDraft.discard(); startInputs.current.delete(original.definition?.id || original.input?.teamId); setGoal(''); } }
          else { rememberView(); release(reading); setReading(null); setTab('teams'); setCatalog(old => ({ ...old, teams: result.command === 'save' ? [...rows(old.teams).filter(team => team.id !== result.result.id), result.result] : rows(old.teams).filter(team => team.id !== original.input?.id) })); }
        });
      }}>确认这一次操作</Button></div>
    </div></SettingsDialog>
  </section>;
}



