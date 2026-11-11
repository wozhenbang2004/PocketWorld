import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { IconAdd, IconArrowLeft, IconChevronRight, IconEdit, IconExternalLink, IconPause, IconPlay, IconRefresh, IconMore, IconStop } from './icons';
import { Tabs, TabList, Tab, TabPanel, ContextMenuTrigger, ContextMenu, ContextMenuItem } from './shell-library';
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
import { TeamQuickStartForm } from './TeamQuickStartForm';
import { TeamStartReview } from './TeamStartReview';
import { resetTeamProductionAcknowledgments, teamDefinitionPayload, teamProductionDraft, teamProductionEditorMember } from './team-production';
import { TeamMemberAvatar, TeamMemberIdentity } from './TeamMemberIdentity';
import { teamAvatarShape, teamAvatarShapes, teamMemberLabel, teamMessageIdentity, teamMessageAuthor } from './team-identity';
import { teamAttentionItems, teamCanPause, teamCanResume, teamGoalTitle, teamHasEnded, teamNeedsAttention, teamPhaseLabel, teamProgressLabel, teamRoleLabel, teamStatusLabel as status, teamTimeLabel, type TeamAttentionItem } from './team-presentation';
import type { TeamButlerRequest, TeamOwnerWorkLink, TeamRunFocus } from './team-navigation';
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
type RunSection = 'progress' | 'result' | 'details';
type EditorPurpose = 'start' | 'definition';
type MessageInput = { recipient: string; replyId: string; mode: string };
type PendingStart = { teamId: string; since: number; goal: string };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const label = (value: unknown): string => typeof value === 'string' ? value : '';
const questionKey = (value: Data): string => ['memberId', 'workItemId', 'assignmentId', 'questionId'].map(key => label(value[key])).join(':');
const blankMember = (id: string, index: number) => teamProductionEditorMember({ id, name: `成员 ${index + 1}`, role: '', model: '', runner: 'model' });
const starterMembers = (template: string): Data[] => [
  { ...blankMember('member-0', 0), name: template === 'options' ? '方案设计' : '资料研究', role: template === 'options' ? '围绕共同目标提出不同方案，说明依据与限制。' : '围绕共同目标搜集和整理信息，标明来源与不确定性。' },
  { ...blankMember('member-1', 1), name: template === 'options' ? '评估与建议' : '核对与整合', role: template === 'options' ? '比较方案的成本、收益与风险，整理可执行建议。' : '核对信息与推理，指出矛盾和缺口，整合结论与下一步。' },
];
const safeTeamDraft = (editor: Data): Data => ({ name: editor.name, ...(typeof editor.nameEdited === 'boolean' ? { nameEdited: editor.nameEdited } : {}), mode: editor.mode, leaderId: editor.leaderId, goal: editor.goal || '', rounds: editor.rounds || '1', ...(editor.pendingStartTeamId ? { pendingStartTeamId: editor.pendingStartTeamId, pendingStartSince: editor.pendingStartSince } : {}), members: rows(editor.members).map(member => ({ ...Object.fromEntries(['id', 'name', 'role', 'model', 'runner', 'modeId', 'avatarShape', 'assignment', 'dependsOn', 'device', 'writeFiles'].filter(key => member[key] !== undefined).map(key => [key, member[key]])), production: Object.fromEntries(['sourceMode', 'sourceTaskId', 'snapshotId', 'packageId', 'packageSha', 'packageManifest', 'wallSeconds', 'outputBytes', 'contributionBytes'].filter(key => member.production?.[key] !== undefined).map(key => [key, member.production[key]])) })) });
const pendingStart = (draft: Data): PendingStart | null => typeof draft.pendingStartTeamId === 'string' && typeof draft.pendingStartSince === 'number'
  ? { teamId: draft.pendingStartTeamId, since: draft.pendingStartSince, goal: label(draft.goal) } : null;
const matchesPendingStart = (run: Data, pending: PendingStart): boolean => run.teamId === pending.teamId
  && typeof run.createdAt === 'number' && run.createdAt >= pending.since;
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
export function TeamsWorkspace({ state, run, registerTransientBack, registerBeforeLeave, alreadyShownError = '', attentionFocus, onAttentionConsumed, runFocus, onRunFocusConsumed, onAskButler, onOpenWork }: {
  state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void;
  registerBeforeLeave?: RegisterBeforeLeave; alreadyShownError?: string; attentionFocus?: Data | null;
  onAttentionConsumed?: (requestId: string) => void;
  runFocus?: TeamRunFocus | null; onRunFocusConsumed?: (requestId: string) => void;
  onAskButler?: (request: TeamButlerRequest) => void;
  onOpenWork?: (link: TeamOwnerWorkLink, intent: 'overview' | 'supplement') => void;
}) {
  const formDraft = useFormDrafts(run, 'teams');
  const startDraft = useFormDrafts(run, 'teams');
  const messageDraft = useFormDrafts(run, 'teams');
  const memberFormDraft = useFormDrafts(run, 'teams');
  const messageDraftId = useRef('');
  const [draftCatalogChecked, setDraftCatalogChecked] = useState(false), [messageChecked, setMessageChecked] = useState(false);
  const startDraftId = useRef('');
  const [catalog, setCatalog] = useState<Data>({}), [tab, setTab] = useState<TeamTab>('runs');
  const [catalogReady, setCatalogReady] = useState(false), [editorExpanded, setEditorExpanded] = useState<string[]>([]);
  const [queries, setQueries] = useState<Record<TeamTab, string>>({ runs: '', teams: '' });
  const query = queries[tab], setQuery = (value: string) => setQueries(old => ({ ...old, [tab]: value }));
  const [reading, setReading] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [editor, setEditor] = useState<Data | null>(null);
  const [editorPurpose, setEditorPurpose] = useState<EditorPurpose>('start');
  const [quickStartPending, setQuickStartPending] = useState<PendingStart | null>(null), [savedStartPending, setSavedStartPending] = useState<PendingStart | null>(null);
  const [memberDraft, setMemberDraft] = useState<Data | null>(null);
  const pendingMemberChange = useRef<string | null>(null);
  const [phoneSelection, setPhoneSelection] = useState<Data | null>(null), [phoneChoices, setPhoneChoices] = useState<Record<string, TeamPhoneChoice>>({});
  const [externalReady, setExternalReady] = useState<Record<string, boolean>>({}), [memberProbes, setMemberProbes] = useState<Record<string, Data>>({});
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [acknowledged, setAcknowledged] = useState(false);
  const [goal, setGoal] = useState(''), [rounds, setRounds] = useState('1'), [recipient, setRecipient] = useState(''), [replyId, setReplyId] = useState(''), [mode, setMode] = useState('discussion');
  const [composeOpen, setComposeOpen] = useState(false);
  const [runSections, setRunSections] = useState<Record<string, RunSection>>({});
  const [detailSections, setDetailSections] = useState<Record<string, string[]>>({});
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
  const handledRunFocus = useRef('');
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
      startDraftId.current = ''; setSavedStartPending(null);
      void startDraft.open('owner', 'start:' + next.definition.id, String(next.definition.revision || ''), { goal: saved?.goal || '', rounds: saved?.rounds || '1' }, !!saved?.goal).then(value => { if (value && alive.current && currentReading.current?.definition?.id === next.definition.id) { startDraftId.current = next.definition.id; setGoal(value.goal); setRounds(value.rounds); setSavedStartPending(pendingStart(value)); } });
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
  };
  const load = () => { void invoke('teams.snapshot', {}, value => { acceptCatalog(value); if (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) setDraftCatalogChecked(true); }); };
  useEffect(() => { if (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) setDraftCatalogChecked(false); }, [formDraft.requiresRecheck, startDraft.requiresRecheck, memberFormDraft.requiresRecheck]);
  const readRun = (id: string) => { void invoke('teams.readRun', { runId: id }, result => { if (result.reading?.run?.id !== id) throw new Error('原团队运行身份不匹配'); acceptReading(result.reading); }); };
  const readDefinition = (id: string) => { void invoke('teams.readDefinition', { id }, value => { if (value.definition?.id !== id) throw new Error('团队配置身份不匹配，请刷新目录'); acceptReading(value); }); };
  useEffect(() => {
    const requestId = runFocus?.requestId, runId = runFocus?.runId;
    if (!requestId || !runId || requestId === handledRunFocus.current || !workspaceActive || !catalogReady || busy || entered.current || backgrounded.current || !ownsNativeWindow()) return;
    handledRunFocus.current = requestId;
    void invoke('teams.readRun', { runId }, result => {
      if (result.reading?.run?.id !== runId) throw new Error('原协作记录身份不匹配，请从原工作重新打开。');
      setEditor(null); setMemberDraft(null); closeReview(); closePhoneSelection();
      acceptReading(result.reading);
      setRunSections(old => ({ ...old, [runId]: 'progress' }));
      setComposeOpen(false);
      onRunFocusConsumed?.(requestId);
    }, () => onRunFocusConsumed?.(requestId));
  }, [runFocus?.requestId, workspaceActive, catalogReady, busy]);
  useEffect(() => {
    const requestId = label(attentionFocus?.requestId);
    if (!requestId || requestId === handledAttention.current || !workspaceActive || !catalogReady || busy || entered.current || backgrounded.current || !ownsNativeWindow()) return;
    handledAttention.current = requestId;
    const locator = Object.fromEntries(['runId', 'memberId', 'generation', 'memberGeneration', 'identityRevision', 'workItemId', 'assignmentId', 'questionId', 'childKind', 'round', 'taskId']
      .filter(key => attentionFocus?.[key] !== undefined).map(key => [key, attentionFocus![key]]));
    void invoke('teams.readAttention', locator, result => {
      if (result.reading?.run?.id !== locator.runId) throw new Error('原团队待处理身份不匹配，请刷新通知。');
      acceptReading(result.reading);
      const focus = object(result.focus), id = result.reading.run.id;
      setRunSections(old => ({ ...old, [id]: focus.kind === 'question' ? 'details' : 'progress' }));
      if (focus.kind === 'question') setDetailSections(old => ({ ...old, [id]: [...new Set([...(old[id] || []), 'conversation'])] }));
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
  const leaveWithDrafts = (leave: () => void) => formDraft.guard(() => startDraft.guard(() => messageDraft.guard(() => memberFormDraft.guard(() => {
    if (materialBeforeLeave.current) materialBeforeLeave.current(leave); else leave();
  }))));
  const askButler = (request: TeamButlerRequest = {}) => {
    if (!onAskButler || busy || materialBusy) return;
    leaveWithDrafts(() => onAskButler(request));
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
    handledRunFocus.current = '';
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
  // This relationship comes only from readRun's native parent-work check.
  const ownerWorkLink = reading?.kind === 'run' && reading.ownerWorkLink ? reading.ownerWorkLink as TeamOwnerWorkLink : null;
  const openOwnerWork = (intent: 'overview' | 'supplement') => {
    if (!onOpenWork || !ownerWorkLink || busy || materialBusy || intent === 'supplement' && (ownerWorkLink.currentRun !== true || changed || !reading?.viewHandle)) return;
    leaveWithDrafts(() => onOpenWork(ownerWorkLink, intent));
  };
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
  const activeQuestionKeys = new Set(rows(reading?.attention).filter(item => item.kind === 'question').map(questionKey));
  const isCurrentQuestion = (message: Data) => message.kind === 'assignment_question' && message.state === 'pending' && activeQuestionKeys.has(questionKey(message));
  const workItems = rows(object(current?.workPlan).items);
  const runSection = runSections[current?.id || ''] || 'progress';
  const expandedDetails = detailSections[current?.id || ''] || [];
  const materialsActive = runSection === 'details' && expandedDetails.includes('materials');
  const revealDetail = (section: string) => { if (current) setDetailSections(old => ({ ...old, [current.id]: [...new Set([...(old[current.id] || []), section])] })); };
  const chooseDetailSections = (sections: string[]) => {
    if (!current) return;
    const select = () => setDetailSections(old => ({ ...old, [current.id]: sections }));
    if (materialsActive && !sections.includes('materials') && materialBeforeLeave.current) materialBeforeLeave.current(select); else select();
  };
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
    if (materialsActive && section !== runSection && materialBeforeLeave.current) materialBeforeLeave.current(select);
    else select();
  };
  const focusMessage = (id: string) => {
    chooseRunSection('details', () => {
      revealDetail('conversation');
      chooseMemberFilter('');
      requestAnimationFrame(() => { const target = root.current?.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(id)}"]`); target?.focus({ preventScroll: true }); target?.scrollIntoView({ block: 'center' }); });
    });
  };
  const hasWorkPlan = !!current?.workPlan;
  const reply = messages.find(message => message.messageId === replyId);
  const replyCanReceive = !reply || reply.kind !== 'assignment_question' || isCurrentQuestion(reply);
  const draftKey = `${current?.id || ''}:${recipient}:${replyId}:${mode}`, text = drafts[draftKey] || '';
  useEffect(() => {
    if (!current?.id || !recipient || !composeOpen) return;
    messageDraftId.current = '';
    void messageDraft.open('owner', 'message:' + draftKey, draftKey, { text: drafts[draftKey] || '' }).then(saved => { if (saved && alive.current) { messageDraftId.current = draftKey; setDrafts(old => ({ ...old, [draftKey]: saved.text })); } });
  }, [draftKey, composeOpen]);
  useEffect(() => { if (messageDraftId.current === draftKey) messageDraft.update({ text }); }, [draftKey, text, messageDraft.update]);
  useEffect(() => { if (messageDraft.requiresRecheck) setMessageChecked(false); }, [messageDraft.requiresRecheck]);
  const canRead = workspaceActive && !!current && !!reading?.viewHandle && !busy && !changed;
  const canControl = canRead && !materialBusy && current?.status !== 'cancelled';
  const canMutate = canControl && !uncertain;
  const changeMessageMode = (nextMode: string) => messageDraft.guard(() => setMode(nextMode));
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
    if (!canMutate || reply?.kind !== 'assignment_question' || recipient !== reply.memberId || mode !== 'discussion' || !replyCanReceive || messageDraft.loading || !!messageDraft.conflict || messageDraft.requiresRecheck || entered.current || backgrounded.current || !ownsNativeWindow() || !reading || !text.trim() || text.length > 4000 || !members.some(member => member.id === recipient && !member.exited)) return;
    const action = 'teams.answerAssignment';
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
  const beginEditor = (team: Data | null, purpose: EditorPurpose = team ? 'definition' : 'start', fromDraft?: Data) => {
    if (formDraft.loading) return;
    const members = team ? rows(team.members).map(teamProductionEditorMember) : fromDraft ? rows(fromDraft.members) : purpose === 'start' ? [] : starterMembers('research');
    setError(''); setNotice(''); setExternalReady({}); setEditorExpanded(team ? members.slice(0, 1).map(member => member.id) : []);
    const initial = { name: team?.name || fromDraft?.name || (purpose === 'start' ? '' : '共同研究'), nameEdited: team ? true : fromDraft?.nameEdited, mode: team?.mode || fromDraft?.mode || 'collaborate', leaderId: team?.leaderId || fromDraft?.leaderId || members.at(-1)?.id || '', members, goal: team ? goal : fromDraft?.goal || '', rounds: fromDraft?.rounds || '1' };
    void formDraft.open('owner', team ? 'definition:' + team.id : purpose === 'start' ? 'collaboration:new' : 'definition:new', String(team?.revision || 'new'), safeTeamDraft(initial)).then(saved => { if (saved && alive.current) { setEditorPurpose(purpose); if (purpose === 'start') setQuickStartPending(pendingStart(saved)); setEditor({ ...initial, ...saved, members: rows(saved.members).map(member => ({ ...member, externalExecutionRiskAcknowledged: false, production: resetTeamProductionAcknowledgments({ ...teamProductionDraft(), ...member.production }) })) }); } });
  };
  const changeTeamGoal = (goal: string) => setEditor(previous => {
    if (!previous) return previous;
    // Older drafts have no flag. Keep their non-default names rather than infer a rename.
    const nameEdited = typeof previous.nameEdited === 'boolean' ? previous.nameEdited : !!previous.name && previous.name !== '共同研究';
    return { ...previous, goal, nameEdited, name: nameEdited ? previous.name : goal.trim().split('\n')[0].slice(0, 40) || '共同研究' };
  });
  useEffect(() => { if (editor) formDraft.update(safeTeamDraft(editor)); }, [editor, formDraft.update]);
  useEffect(() => { if (reading?.kind === 'definition' && startDraftId.current === reading.definition.id) startDraft.update({ goal, rounds, ...(savedStartPending ? { pendingStartTeamId: savedStartPending.teamId, pendingStartSince: savedStartPending.since } : {}) }); }, [goal, rounds, savedStartPending, reading?.definition?.id, startDraft.update]);
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
  const showDirectoryTools = catalogReady || catalogTeams.length > 0 || catalogRuns.length > 0;
  const showCollectionTools = catalogEntries.length > 0 || hasQuery;
  const directoryState = !workspaceActive || busy ? 'loading' : error ? 'unconfirmed' : !catalogReady ? 'unread' : hasQuery ? 'filtered' : 'empty';
  const entries = catalogEntries.map(row => {
    const live = object(object(state.teamRuns)[row.id]);
    return tab === 'runs' && live.id === row.id && (live.generation > row.generation
      || live.generation === row.generation && live.revision > row.revision) ? { ...row, ...live } : row;
  }).filter(row => `${row.goal || ''} ${row.name || ''} ${row.teamName || ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const showDirectoryActions = catalogReady && !error;
  const directoryGroups = tab === 'teams' ? [{ id: 'definitions', title: '可复用的成员与分工', entries }] : [
    { id: 'attention', title: '需要处理', entries: entries.filter(row => teamNeedsAttention(row.status)) },
    { id: 'active', title: '进行中与已暂停', entries: entries.filter(row => !teamNeedsAttention(row.status) && !teamHasEnded(row.status)) },
    { id: 'history', title: '已结束', entries: entries.filter(row => teamHasEnded(row.status)) },
  ];
  useCollectionMotion(root, JSON.stringify([viewKey, runSection, memberFilter, query.trim(), state.webWorkspaceResumeRevision]), JSON.stringify([
    entries.map(row => [row.id, row.status, row.revision]),
    messages.map(message => [message.messageId, message.state, !!message.answer]),
    members.map(member => [member.id, member.status, member.exited]),
    workItems.map(item => [item.workItemId, item.status]), !!current?.summary,
    expandedMembers[String(current?.id || '')] || [], expandedItems[String(current?.id || '')] || [],
  ]), { active: workspaceActive && catalogReady && (!reading || !!reading.viewHandle) && !editor && !memberDraft && !review && !phoneSelection });
  const pendingQuestions = messages.filter(isCurrentQuestion);
  const attentionItems = teamAttentionItems(current, reading?.attention);
  const openAssignment = (item: TeamAttentionItem) => {
    if (item.taskId) { openRelatedTask(item.taskId); return; }
    chooseRunSection('progress', () => {
      if (item.workItemId) setExpandedItems(old => ({ ...old, [current!.id]: [...new Set([...(old[current!.id] || []), item.workItemId!])] }));
      else setExpandedMembers(old => ({ ...old, [current!.id]: [...new Set([...(old[current!.id] || []), item.memberId])] }));
      requestAnimationFrame(() => {
        const selector = item.workItemId ? `[data-work-item-id="${CSS.escape(item.workItemId)}"]` : `[data-team-member-id="${CSS.escape(item.memberId)}"]`;
        const target = root.current?.querySelector<HTMLElement>(selector);
        target?.scrollIntoView({ block: 'start' }); target?.querySelector<HTMLElement>('button')?.focus({ preventScroll: true });
      });
    });
  };
  const jumpToRunSection = (section: 'messages' | 'members' | 'materials' | 'compose') => {
    chooseRunSection(section === 'members' ? 'progress' : 'details', () => {
      if (section !== 'members') revealDetail(section === 'materials' ? 'materials' : 'conversation');
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
  if (editor) try { const { goal: _goal, rounds: _rounds, nameEdited: _nameEdited, pendingStartTeamId: _pendingStartTeamId, pendingStartSince: _pendingStartSince, ...definitionDraft } = editor; editorPayload = teamDefinitionPayload(definitionDraft, catalog); }
  catch (failure) { productionError = failure instanceof Error ? failure.message : String(failure); }
  const refreshReading = <IconButton className="pw-utility-icon" icon={IconRefresh} aria-label="刷新当前记录" title="刷新当前记录" disabled={busy || materialBusy} onClick={() => current ? readRun(current.id) : definition ? void invoke('teams.readDefinition', { id: definition.id }, acceptReading) : load()} />;
  const startRecoveries = [
    ...(formDraft.requiresRecheck && quickStartPending ? [{ kind: 'quick' as const, pending: quickStartPending }] : []),
    ...(startDraft.requiresRecheck && savedStartPending ? [{ kind: 'saved' as const, pending: savedStartPending }] : []),
  ];
  const draftRecovery = (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) && <section className="pw-team-draft-recovery" aria-label="核对上次操作">
    <Button variant="secondary" disabled={busy} onClick={load}>读取协作与编组核对结果</Button>
    {startRecoveries.map(({ kind, pending }) => {
      const candidates = catalogRuns.filter(row => matchesPendingStart(row, pending));
      const exactReading = !!current && !!reading?.viewHandle && matchesPendingStart(current, pending) && candidates.length === 1;
      return <div className="pw-team-start-recovery" key={kind}><p className="pw-team-recovery-goal">原目标：{pending.goal}</p><p>刚才的启动结果待核对，目标与分工已保留。{draftCatalogChecked ? candidates.length === 0 ? '暂未找到对应协作，请重新读取；不会再次启动。' : candidates.length > 1 ? '找到多条可能相关的协作，需要继续核对，暂不解除待确认状态。' : '找到一条相关协作，请打开核对目标与分工。' : ''}</p>
        {candidates.map(row => <Button key={row.id} variant="ghost" trailingIcon={IconChevronRight} disabled={busy} onClick={() => (kind === 'quick' ? formDraft : startDraft).guard(() => { setEditor(null); readRun(row.id); })}>查看：{teamGoalTitle(row.goal, row.teamName)} · {teamTimeLabel(row.createdAt)}</Button>)}
        {exactReading && <Button variant="secondary" disabled={busy || changed} onClick={() => { (kind === 'quick' ? formDraft : startDraft).discard(() => { if (kind === 'quick') { setQuickStartPending(null); setEditor(null); } else { setSavedStartPending(null); startInputs.current.delete(pending.teamId); } setNotice('已核对并打开这次协作，未重新启动。'); }); }}>已核对，这是刚才开始的协作</Button>}
      </div>;
    })}
    {(formDraft.requiresRecheck && editorPurpose === 'definition' || memberFormDraft.requiresRecheck) && <Button variant="secondary" disabled={busy || !draftCatalogChecked} onClick={() => { if (editorPurpose === 'definition') formDraft.clearSubmitted(); memberFormDraft.clearSubmitted(); setDraftCatalogChecked(false); }}>已核对编组或成员改动，继续编辑</Button>}
    {(startDraft.requiresRecheck && !savedStartPending || formDraft.requiresRecheck && editorPurpose === 'start' && !quickStartPending) && <p className="pw-field-hint">这份旧草稿没有保存启动标识，请先查看协作记录；启动结果尚未确定。</p>}
  </section>;
  const directoryContent = <>
    {showCollectionTools && <SearchField fieldClassName="pw-collaboration-search" shape="rounded" className="pw-secondary-search" data-input-size="medium" aria-label={tab === 'runs' ? '搜索协作' : '搜索编组'} placeholder={tab === 'runs' ? '查找目标或编组' : '查找编组'} value={query} onChange={setQuery} />}
    {tab === 'teams' && entries.length > 0 && <p className="pw-team-directory-caption">保存常用成员与职责。交办工作时，管家会根据目标安排合适的分工。</p>}
    {entries.length > 0 ? <div className="pw-collaboration-directory">{directoryGroups.filter(group => group.entries.length > 0).map(group => <section className="pw-collaboration-group" key={group.id} aria-label={group.title}>
      {tab === 'runs' && <header className="pw-collaboration-group-heading"><h3>{group.title}</h3><span>{group.entries.length} 项已读取</span></header>}
      <div className="pw-object-list">{group.entries.map(row => <Button key={row.id} data-team-list-id={row.id} data-motion-key={`directory:${row.id}`} variant="ghost" trailingIcon={IconChevronRight} className="pw-object-row pw-team-directory-row h-auto whitespace-normal" disabled={busy} onClick={() => { listSelection.current[tab] = row.id; if (tab === 'runs') readRun(row.id); else readDefinition(row.id); }}>
        <span className="pw-object-row-copy"><span className="pw-object-row-title">{tab === 'runs' ? teamGoalTitle(row.goal, row.teamName || '这次协作') : row.name || '未命名编组'}</span>
          <small className="pw-team-directory-meta">{tab === 'runs' ? <><span className="pw-team-state" data-state={row.status}>{status(row.status)}</span>{row.teamName && <span>{row.teamName}</span>}{teamTimeLabel(row.updatedAt || row.createdAt) && <time>{teamTimeLabel(row.updatedAt || row.createdAt)}</time>}</> : `${rows(row.members).length} 名成员 · ${row.mode === 'produce' ? '项目文件协作' : row.mode === 'debate' ? '取证与讨论' : '共同工作'}`}</small>
          {tab === 'teams' && <span className="pw-team-roster-preview">{rows(row.members).map(member => <span key={member.id}><TeamMemberAvatar member={member} size={24} />{teamMemberLabel(member, rows(row.members))}</span>)}</span>}
        </span>
      </Button>)}</div>
    </section>)}</div> : <section className="pw-team-empty" aria-label="协作目录提示" role={directoryState === 'loading' ? 'status' : undefined}>
      <h2>{directoryState === 'loading' ? '正在读取协作' : directoryState === 'unconfirmed' ? '暂时未能读取协作' : directoryState === 'unread' ? '协作尚未读取' : directoryState === 'filtered' ? '没有匹配的记录' : tab === 'teams' ? '还没有保存编组' : '还没有协作记录'}</h2>
      <p>{directoryState === 'loading' || directoryState === 'unread' ? '已保存的目标、分工和成果会显示在这里。' : directoryState === 'unconfirmed' ? '重新读取后继续，已有工作与草稿保留。' : directoryState === 'filtered' ? '换一个关键词，或清除搜索查看已读取的记录。' : tab === 'teams' ? '常用分工可以保存在这里。直接把目标交给管家，也能按这次工作安排成员。' : '把想完成的事告诉管家。需要多人分工时，协作会留在那件工作里，记录也可以在这里查看。'}
      </p>
      {!showDirectoryTools && (directoryState === 'unread' || directoryState === 'unconfirmed') && <Button variant="secondary" disabled={busy} onClick={load}>重新读取</Button>}
      {directoryState === 'filtered' && <Button variant="secondary" onClick={() => setQuery('')}>清除搜索</Button>}
    </section>}
  </>;
  return <section ref={root} className="pw-team-workspace pw-depth-collaboration" data-team-layer={reading ? 'detail' : 'list'} data-team-view={viewKey} inert={!workspaceActive} aria-busy={!workspaceActive || busy}>
    <div className="pw-team-loading">{(!workspaceActive || busy) && <ProgressBar aria-label={workspaceActive ? '正在读取原团队回执' : '正在进入团队工作区'} isIndeterminate showValueLabel={false} size="sm" />}</div>
    {error && error !== alreadyShownError && !editor && !memberDraft && !review && !phoneSelection && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}{notice && <MotionFeedback identity={notice} className="pw-field-hint" role="status">{notice}</MotionFeedback>}
    {reading && <nav className="pw-team-location" aria-label="协作记录导航"><Button variant="ghost" leadingIcon={IconArrowLeft} disabled={busy} onClick={back}>{current ? '协作记录' : '全部编组'}</Button></nav>}
    {!reading && <div className="pw-team-directory-intro"><p>查看工作中的分工、进展与成果。</p>{onAskButler && <Button variant="secondary" trailingIcon={IconChevronRight} disabled={busy} onClick={() => askButler()}>交给管家</Button>}</div>}
    {!reading && (showDirectoryTools ? <Tabs selectedKey={tab} onSelectionChange={value => { if (value === 'runs' || value === 'teams') switchTab(value); }} className="pw-team-tabs">
      <div className="pw-object-toolbar"><TabList aria-label="协作记录与配置"><Tab id="runs">协作记录</Tab><Tab id="teams">编组</Tab></TabList>
        <div className="pw-team-directory-actions">{showDirectoryActions && tab === 'teams' && <IconButton className="pw-utility-icon" icon={IconAdd} aria-label="新建编组" title="新建编组" disabled={busy || !catalogReady} onClick={() => beginEditor(null, 'definition')} />}<IconButton className="pw-utility-icon" icon={IconRefresh} aria-label="刷新协作记录与编组" title="刷新协作记录与编组" disabled={busy} onClick={load} /></div>
      </div>
      <TabPanel id={tab}>{directoryContent}</TabPanel>
    </Tabs> : directoryContent)}
    {!reading && !editor && <Accordion variant="divided" className="pw-team-recovery-access"><AccordionItem id="old-drafts" title="旧草稿与启动核对"><p className="pw-field-hint">以前未发出的目标与分工仍可查看。启动结果未确认的协作，需要先核对原记录。</p><Button variant="ghost" trailingIcon={IconChevronRight} disabled={busy || formDraft.loading} onClick={() => beginEditor(null, 'start')}>查看旧协作草稿</Button></AccordionItem></Accordion>}
    {!editor && !memberDraft && draftRecovery}
    {!editor && !definition && <FormDraftNotice draft={formDraft} />}
    {definition && <article className="pw-team-reading pw-team-definition"><header className="pw-object-toolbar pw-team-definition-heading"><div><h2 tabIndex={-1}>{definition.name}</h2><p>{definition.mode === 'produce' ? '原项目文件协作' : definition.mode === 'debate' ? '取证与讨论' : hasPhoneMembers ? '手机分工与共同工作' : '共同分析'} · {rows(definition.members).length} 名成员</p></div>{refreshReading}</header>
      <section className="pw-team-handoff" aria-label="使用编组安排工作">
        <p>这份编组保存常用成员与职责。把目标交给管家后，再按实际工作安排分工与范围。</p>
        {onAskButler && <Button trailingIcon={IconChevronRight} disabled={busy || materialBusy || startDraft.loading || startDraft.requiresRecheck || !!startDraft.conflict || goal.length > 8000} onClick={() => askButler(goal.trim() ? { goal } : {})}>回管家安排协作</Button>}
        {(goal.trim() || startDraft.savedAt || startDraft.loading || startDraft.error || startDraft.conflict || startDraft.requiresRecheck) && <Accordion variant="divided" className="pw-team-saved-goal"><AccordionItem id="saved-goal" title="这份编组的旧目标草稿" description={goal.trim() ? teamGoalTitle(goal) : undefined}>
          <FormDraftNotice draft={startDraft} />
          <TextAreaField label="保留的目标" value={goal} onChange={setGoal} maxLength={8000} rows={3} autoResize maxRows={8} resize="none" isDisabled={busy || startDraft.requiresRecheck} />
          <p className="pw-field-hint">目标会带回管家输入区，由你继续交办；这份草稿与分工保留。</p>
        </AccordionItem></Accordion>}
      </section>
      <section className="pw-team-members"><header className="pw-team-members-heading"><h3>成员与职责</h3><Button variant="ghost" leadingIcon={IconEdit} disabled={busy || !definitionReady} onClick={() => beginEditor(definition)}>编辑编组</Button></header><Accordion variant="divided" className="pw-team-definition-member-list">{rows(definition.members).map(member => <AccordionItem key={member.id} id={member.id} data-motion-key={`definition-member:${member.id}`} title={<TeamMemberIdentity member={member} name={teamMemberLabel(member, rows(definition.members))} detail={member.id === definition.leaderId ? teamRoleLabel(definition) : undefined} size={32} />} description={<span className="pw-team-definition-duty">{member.role}</span>}><div className="pw-team-detail-body"><p>{member.role}</p>
        {member.runner === 'phone_agent' ? <><small>{teamPhoneTargetLabel(member.device, phoneTargets)}</small><p>{member.assignment}</p></>
          : member.runner === 'model' || !member.runner ? <small>模型：{memberModelLabel(member) || '沿用实际启动时的主模型'} · 本机模型连接</small>
          : <><small>执行器：{harnessWorkerLabel(undefined, member.runner)} · 模型：{member.model || '执行器默认'}</small><p className="pw-field-hint">电脑：{computerLabel(member)}</p>
            <p className="pw-field-hint">开始协作前，核对这份已保存配置的实际执行器。</p><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.probeMember', { viewHandle: reading!.viewHandle, memberId: member.id }, value => setMemberProbes(old => ({ ...old, [`${definition.id}:${member.id}`]: { definition: JSON.stringify(member), value: object(value.result || value) } })))}>核对原执行器</Button>
            {memberProbes[`${definition.id}:${member.id}`]?.definition === JSON.stringify(member) && <HarnessEvidence probe={memberProbes[`${definition.id}:${member.id}`].value} id={`saved-member-evidence-${member.id}`} />}
          </>}
      </div></AccordionItem>)}</Accordion></section>
      <div className="pw-team-definition-settings"><Accordion variant="divided"><AccordionItem id="definition-management" title="管理编组"><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.reviewRemove', { viewHandle: reading!.viewHandle }, reviewResult)}>移除编组</Button></AccordionItem></Accordion></div>

    </article>}
    {current && <article className="pw-team-reading pw-team-run">
      <header className="pw-object-toolbar pw-team-run-heading"><div><p className="pw-team-run-origin">{ownerWorkLink ? '管家工作中的协作' : '历史协作'}{current.teamName ? ` · ${current.teamName}` : ''}</p><h2 tabIndex={-1}>{teamGoalTitle(current.goal, current.teamName)}</h2><div className="pw-team-run-meta"><span className="pw-team-state" data-state={current.status}>{status(current.status)}</span>{teamPhaseLabel(current.phase) && <span>{teamPhaseLabel(current.phase)}</span>}{teamProgressLabel(current) && <span>{teamProgressLabel(current)}</span>}</div></div>{refreshReading}</header>
      {changed && <p className="pw-team-reading-notice" role="status">正在更新进展，阅读位置与未发送内容保留。</p>}
      {workspaceActive && !busy && !reading?.viewHandle && <p className="pw-team-reading-notice" role="status">请刷新记录后继续操作，未发送内容保留。</p>}
      <div className="pw-team-run-controls">
        <div className="pw-team-work-actions">{ownerWorkLink && onOpenWork ? <><Button variant="ghost" trailingIcon={IconChevronRight} disabled={busy || materialBusy} onClick={() => openOwnerWork('overview')}>查看原工作</Button>{ownerWorkLink.currentRun && <Button variant="secondary" disabled={busy || materialBusy || changed || !reading?.viewHandle} onClick={() => openOwnerWork('supplement')}>补充要求</Button>}</> : <span className="pw-team-history-label">保留原目标与执行记录</span>}</div>
        <div className="pw-team-current-actions">
          {!hasWorkPlan && (teamCanResume(current.status) || current.phase === 'intervention' || current.phase === 'awaiting_member' && currentMembers.length > 0) && <Button leadingIcon={IconPlay} disabled={!canMutate} onClick={() => control('resume')}>继续协作</Button>}
          {current.status !== 'cancelled' && <ContextMenuTrigger key={current.id}>{({ open }) => <><IconButton icon={IconMore} aria-label="暂停或停止协作" disabled={!canControl} onClick={event => open(event.currentTarget)} /><ContextMenu aria-label="本次协作操作" onAction={key => { if (key === 'pause' && canControl) control('pause'); if (key === 'stop' && canControl) reviewResult({ operation: 'cancel', viewHandle: reading!.viewHandle, runId: current.id }); }}>{teamCanPause(current.status) && <ContextMenuItem id="pause" icon={IconPause}>暂停协作</ContextMenuItem>}<ContextMenuItem id="stop" icon={IconStop}>停止这次协作</ContextMenuItem></ContextMenu></>}</ContextMenuTrigger>}
        </div>
      </div>
      {ownerWorkLink && !ownerWorkLink.currentRun && <p className="pw-team-context-note">这是原工作较早一次执行的协作记录，可查看当时进展与成果。</p>}
      {reading?.taskLookupNotice && <p className="pw-field-hint" role="status">{reading.taskLookupNotice}</p>}
      <Tabs selectedKey={runSection} onSelectionChange={value => { if (value === 'progress' || value === 'result' || value === 'details') chooseRunSection(value); }} className="pw-team-workbench">
        <div className="pw-team-workbench-bar"><TabList aria-label="这次协作"><Tab id="progress" isDisabled={materialBusy}>进展</Tab><Tab id="result" isDisabled={materialBusy}>成果</Tab><Tab id="details">详情</Tab></TabList></div>
        <TabPanel id="progress">
          {(pendingQuestions.length > 0 || attentionItems.length > 0) && <section className="pw-team-action-list" aria-label="需要你处理"><header className="pw-collaboration-group-heading"><h3>需要你处理</h3><span>{pendingQuestions.length + attentionItems.length} 项</span></header>
            {pendingQuestions.map(question => <Button key={question.messageId} variant="ghost" className="pw-team-question-row h-auto whitespace-normal" trailingIcon={IconChevronRight} disabled={materialBusy} onClick={() => focusMessage(question.messageId)}><span className="pw-team-attention-copy"><strong>{teamGoalTitle(question.content, '查看成员问题')}</strong><small>{teamMessageAuthor(question, members)} · 等待回答</small></span></Button>)}
            {attentionItems.map(item => <Button key={item.id} variant="ghost" className="pw-team-question-row h-auto whitespace-normal" trailingIcon={IconChevronRight} disabled={busy || materialBusy} onClick={() => openAssignment(item)}><span className="pw-team-attention-copy"><strong>{teamGoalTitle(item.title)}</strong><small>{item.detail}</small></span></Button>)}
          </section>}
          {current.phase === 'intervention' && !hasWorkPlan && <div className="pw-collaboration-attention" role="status"><div><strong>新要求已保存</strong><span>受影响成员已暂停。继续协作后处理，沿用剩余额度。</span></div></div>}
          {!!current.summary && <Button variant="ghost" className="pw-team-result-link" trailingIcon={IconChevronRight} onClick={() => chooseRunSection('result')}>{current.summaryStale ? '上一版成果待更新' : current.status === 'completed' ? '查看本轮成果' : '查看当前汇总'}</Button>}

          {hasWorkPlan && <p className="pw-team-panel-caption">分工按计划推进，展开可查看原任务、审批与已保存产出。</p>}
      {!!workItems.length && <section className="pw-team-overview" aria-label="这次协作的工作项">
        <h3 className="pw-team-section-title">工作项 <span>{workItems.length}</span></h3>
        <Accordion key={`${current.id}:items`} variant="divided" className="pw-team-disclosures" allowsMultipleExpanded expandedKeys={new Set(expandedItems[current.id] || [])}
          onExpandedChange={keys => setExpandedItems(old => ({ ...old, [current.id]: Array.from(keys, String) }))}>
          {workItems.map(item => <AccordionItem key={item.workItemId} id={item.workItemId} className="pw-team-work-item" data-work-item-id={item.workItemId} data-motion-key={`work-item:${item.workItemId}`}
            title={<span className="pw-team-item-heading"><span>{item.title}</span><small className="pw-team-state" data-state={item.status}>{status(item.status)}</small></span>}
            description={<TeamMemberIdentity member={members.find(member => member.id === item.memberId) || { id: item.memberId }} name={name(item.memberId)} size={24} />}>
            <div className="pw-team-expanded-copy"><p>{item.instruction}</p>{item.message && <p>{item.message}</p>}{item.nativeErrorMessage && <p className="pw-field-hint">{item.nativeErrorMessage}</p>}{item.assignmentTask?.taskId && <Button variant="secondary" trailingIcon={IconExternalLink} onClick={() => openRelatedTask(item.assignmentTask.taskId)}>查看此分工与审批</Button>}{item.acceptance && <p>验收：{item.acceptance}</p>}
              {!!rows(item.dependsOn).length && <p>前置：{item.dependsOn.map((id: string) => workItems.find(prior => prior.workItemId === id)?.title || id).join('、')}</p>}
            </div>
          </AccordionItem>)}
        </Accordion>
      </section>}
      <section className="pw-team-overview" data-team-section="members" tabIndex={-1} aria-label="成员与当前工作">
        <header className="pw-team-members-heading"><h3 className="pw-team-section-title">成员 <span>{currentMembers.length} 名参与{members.length > currentMembers.length ? ` · ${members.length - currentMembers.length} 名已退出` : ''}</span></h3></header>
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
              title={<span className="pw-team-progress-member-heading"><TeamMemberIdentity member={member} name={name(member.id)} size={32} /><span className="pw-team-state" data-state={member.exited ? 'exited' : member.status}>{member.exited ? '已退出' : status(member.status)}</span></span>}
              description={<>{assignment && <span className="pw-team-member-task">{assignment}</span>}{!hasWorkPlan && !member.exited && pendingCount(member) > 0 && <span className="pw-team-member-queue">{pendingCount(member)} 条已排队消息待处理</span>}</>}>
              <div className="pw-team-expanded-copy">
                {member.id === current.leaderId && <p className="pw-field-hint">{teamRoleLabel(current)}</p>}<p>{member.assignment || member.role}</p>
                {!!rows(member.dependsOn).length && <p className="pw-field-hint">等待前置分工：{member.dependsOn.map(name).join('、')}</p>}
                <Accordion variant="divided" className="pw-team-execution-details"><AccordionItem id={`execution:${member.id}`} title="模型与执行信息"><p className="pw-muted">成员标识：{member.id}</p>
                {member.runner === 'phone_agent' && <p className="pw-muted">{teamPhoneTargetLabel(member.device, phoneTargets)}</p>}
                {isTeamHarness(member.runner || '') && <><p className="pw-muted">执行器：{harnessWorkerLabel(undefined, member.runner)} · 电脑：{computerLabel(member)}</p>{member.externalSession?.sessionId && <p className="pw-muted">原会话：{member.externalSession.sessionId}</p>}</>}
                {model && <p className="pw-muted">模型：{model}</p>}</AccordionItem></Accordion>
                {latestRound?.message && <p className="pw-team-member-receipt">{latestRound.message}</p>}
                {latestRound?.nativeErrorMessage && <p className="pw-field-hint">{latestRound.nativeErrorMessage}</p>}
                {researchRecords.length > 0 && <section aria-label="原研究记录"><h4>原研究记录</h4><p className="pw-field-hint">查看完整产出、待处理事项和执行过程；打开不会继续工作，最新状态以原任务为准。</p>
                  <div className="pw-form-actions">{researchRecords.filter(record => !record.historical).map(record => <Button key={`${record.round}:${record.taskId}`} variant="ghost" trailingIcon={IconExternalLink} disabled={!workspaceActive || !reading?.viewHandle || busy || materialBusy || changed} onClick={() => openResearchTask(member.id, record)}>第 {record.round} 阶段记录{record.recordedStatus ? ` · 记录时${status(record.recordedStatus)}` : ''}</Button>)}</div>
                  {researchRecords.some(record => record.historical) && <Accordion variant="divided"><AccordionItem id="research-history" title="历史研究版本"><p className="pw-field-hint">这些版本已被后续输入替代，不代表当前成果，也不会转移给新成员。</p><div className="pw-form-actions">{researchRecords.filter(record => record.historical).map(record => <Button key={`${record.round}:${record.taskId}:${record.version}`} variant="ghost" trailingIcon={IconExternalLink} disabled={!workspaceActive || !reading?.viewHandle || busy || materialBusy || changed} onClick={() => openResearchTask(member.id, record)}>第 {record.round} 阶段 · 旧版本 {record.version}{record.recordedStatus ? ` · 记录时${status(record.recordedStatus)}` : ''}</Button>)}</div></AccordionItem></Accordion>}
                </section>}
                {member.text && <section className="pw-team-member-output" aria-label="已保存的成员产出"><h4>已保存的成员产出</h4>{outputIdentity && <TeamMemberIdentity member={outputIdentity} name={teamMessageAuthor(outputMessage!, members)} detail={outputIdentity.name !== member.name ? `原名称 · ${member.id}` : undefined} size={24} />}<AIAnswer text={String(member.text)} /></section>}
                {member.acceptance && <p>验收：{member.acceptance}</p>}
                {!member.exited && <div className="pw-form-actions"><Button variant="ghost" disabled={!canMutate} onClick={() => beginMemberChange('rename', member)}>名称与头像</Button>{simpleRoster && <Button variant="ghost" disabled={!canMutate || !rosterPaused} onClick={() => beginMemberChange('exit', member)}>退出本次协作</Button>}</div>}
                <div className="pw-form-actions"><Button variant="secondary" onClick={() => { chooseMemberFilter(member.id); jumpToRunSection('messages'); }}>查看成员沟通</Button>{memberTaskId && <Button variant="ghost" trailingIcon={IconExternalLink} onClick={() => openRelatedTask(memberTaskId)}>{member.runner === 'phone_agent' ? '查看手机任务与结果' : '查看原分工与审批'}</Button>}{!member.exited && teamCanPause(member.status) && <Button variant="ghost" disabled={!canControl} onClick={() => control('pause', member.id)}>暂停成员</Button>}{!member.exited && !hasWorkPlan && teamCanResume(member.status) && <Button variant="ghost" disabled={!canMutate} onClick={() => control('resume', member.id)}>继续成员</Button>}{!hasWorkPlan && !member.exited && pendingCount(member) > 0 && <Button variant="ghost" disabled={!canMutate || pendingMessageBusy(member)} onClick={() => control('continueMessages', member.id)}>使用 {pendingCount(member)} 条待处理消息继续</Button>}</div>
              </div>
            </AccordionItem>;
          })}
        </Accordion>
      </section>
        </TabPanel>
        <TabPanel id="result">
          <section className="pw-team-result" aria-label="本次协作成果">
            <header className="pw-team-result-heading"><h3>{current.summaryStale ? '上一版汇总' : current.status === 'completed' ? '本轮成果' : '当前汇总'}</h3>{current.summaryStale && <span className="pw-team-result-stale">待更新</span>}</header>
            {current.summaryStale && <p className="pw-team-result-caption">要求、成员或资料发生了变化，以下内容保留供核对，尚未反映最新情况。</p>}
            {current.summary ? <AIAnswer text={current.summary} /> : <div className="pw-team-result-empty"><p>{current.status === 'cancelled' ? '协作已停止，已有产出和沟通记录保留。' : '还没有共同汇总。可以先看成员分工、已保存产出与需要处理的事项。'}</p><Button variant="ghost" trailingIcon={IconChevronRight} onClick={() => jumpToRunSection('members')}>查看分工与产出</Button></div>}
          </section>
        </TabPanel>
        <TabPanel id="details" shouldForceMount data-team-details-active={runSection === 'details'}>
          <Accordion variant="divided" className="pw-team-detail-disclosures" allowsMultipleExpanded expandedKeys={new Set(expandedDetails)} onExpandedChange={keys => chooseDetailSections(Array.from(keys, String))}>
            <AccordionItem id="background" title="工作背景" description="完整目标与所属任务">
              <div className="pw-team-detail-body"><p className="pw-team-goal-copy">{current.goal}</p>
                {teamTimeLabel(current.createdAt) && <p className="pw-field-hint">开始于 {teamTimeLabel(current.createdAt)}</p>}
                {reading?.taskId && <Button variant="ghost" trailingIcon={IconExternalLink} disabled={busy || materialBusy} onClick={() => openRelatedTask(reading.taskId)}>查看协作任务与审批</Button>}
              </div>
            </AccordionItem>
            <AccordionItem id="materials" title="资料与授权" description="成员可使用的资料与范围">
              <div data-team-section="materials" tabIndex={-1}><TeamEvidenceWorkspace key={current.id} runId={current.id} members={members} run={run} disabled={!canRead} stopped={current.status === 'cancelled'} active={workspaceActive && materialsActive} windowRevision={Number(state.webWorkspaceResumeRevision || 0)} onBackChange={handler => { materialBack.current = handler; }} onBeforeLeaveChange={handler => { materialBeforeLeave.current = handler; }} onBusyChange={setMaterialBusy} /></div>
            </AccordionItem>
            <AccordionItem id="conversation" title="沟通记录" description={`${messages.length} 条已读取记录`}>
              <div className="pw-team-conversation-body">

      <Accordion variant="divided" className="pw-team-compose-disclosure" expandedKeys={composeOpen || !!uncertain ? ['team-compose'] : []} onExpandedChange={keys => setComposeOpen(keys.has('team-compose'))}><AccordionItem id="team-compose" title={reply?.kind === 'assignment_question' ? '回答原问题' : '旧消息草稿'} description={reply ? '保留原问题与收件成员' : text ? '有未发送内容' : '查看以前未发出的补充'}>
      <section className="pw-team-compose" data-team-section="compose" aria-label="原问题与消息草稿">
      {reply && <div className="pw-team-reply-context"><header><span>{reply.kind === 'assignment_question' ? '回答原问题' : '引用原消息'} · {teamMessageIdentity(reply, members) ? <TeamMemberIdentity member={teamMessageIdentity(reply, members)!} name={teamMessageAuthor(reply, members)} size={24} /> : teamMessageAuthor(reply, members)}</span><Button variant="ghost" disabled={busy || !!uncertain} onClick={() => setReplyId('')}>查看其他草稿</Button></header><p className="pw-team-quote-excerpt">{String(reply.content || '').slice(0, 360)}{String(reply.content || '').length > 360 ? '…' : ''}</p><Button variant="ghost" onClick={() => focusMessage(reply.messageId)}>查看完整原消息</Button></div>}
      <SelectField label="收件成员" value={recipient} options={[{ id: '*', label: '全队的旧草稿' }, ...members.map(member => ({ id: member.id, label: name(member.id) + (member.exited ? ' · 已退出' : member.id === current.leaderId ? ' · ' + teamRoleLabel(current) : '') }))]} onChange={value => messageDraft.guard(() => setRecipient(value))} isDisabled={busy || !!uncertain || reply?.kind === 'assignment_question'} />
      {reply?.kind !== 'assignment_question' && <>
        <SelectField label="原消息用途" value={mode} options={[{ id: 'discussion', label: '讨论补充' }, { id: 'queue', label: '补充下一工作点' }, { id: 'feedback', label: '调整要求' }]} onChange={changeMessageMode} isDisabled={busy || !!uncertain} />
        <SelectField label="草稿引用的原消息" value={replyId} options={[{ id: '', label: '未引用消息' }, ...messages.map(message => ({ id: message.messageId, label: `${teamMessageAuthor(message, members)} · ${teamGoalTitle(message.content, '原消息')}` }))]} onChange={value => messageDraft.guard(() => { const original = messages.find(message => message.messageId === value); setReplyId(value); if (original?.kind === 'assignment_question') { setRecipient(original.memberId); setMode('discussion'); } })} isDisabled={busy || !!uncertain} />
        <p className="pw-field-hint">旧草稿保留供核对。新的要求在原工作里交给管家，原问题仍在这里逐条回答。</p>
        {ownerWorkLink?.currentRun && onOpenWork && <Button variant="secondary" disabled={busy || materialBusy || messageDraft.requiresRecheck || !!uncertain} onClick={() => openOwnerWork('supplement')}>回原工作补充</Button>}
      </>}
      {!replyCanReceive && <p className="pw-team-reading-notice" role="status">这条原问题已不再等待回答，已有草稿保留供核对。</p>}
      {current.status === 'cancelled' && <p className="pw-team-reading-notice" role="status">协作已停止，消息与未发送草稿可继续查看。</p>}
      <FormDraftNotice draft={messageDraft} />{messageDraft.requiresRecheck && <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { void invoke('teams.readRun', { runId: current.id }, value => { if (value.reading?.run?.id !== current.id) throw new Error('原协作身份不匹配'); acceptReading(value.reading); setMessageChecked(true); }); }}>读取原消息核对结果</Button><Button variant="secondary" disabled={busy || !messageChecked || !!uncertain} onClick={() => { messageDraft.clearSubmitted(); setMessageChecked(false); }}>已核对原消息，继续编辑</Button></div>}
      <TextAreaField label={reply?.kind === 'assignment_question' ? '回答这条原问题' : '以前未发送的内容'} value={text} onChange={value => setDrafts(old => ({ ...old, [draftKey]: value }))} maxLength={4000} showCount isReadOnly={reply?.kind !== 'assignment_question'} isDisabled={reply?.kind === 'assignment_question' && (!canMutate || !replyCanReceive) || messageDraft.loading} hint={reply?.kind === 'assignment_question' ? '回答只回到这条原问题，收件成员与问题身份保持不变。' : '查看草稿不会发送消息或启动工作。'} />
      {reply?.kind === 'assignment_question' && <PressButton label="保存原回答" disabled={!canMutate || recipient !== reply.memberId || mode !== 'discussion' || !replyCanReceive || messageDraft.loading || !!messageDraft.conflict || messageDraft.requiresRecheck || !text.trim() || text.length > 4000 || !recipient} onClick={send} />}
      {mode !== 'feedback' && !replyId && recipientPending > 0 && <div className="pw-form-actions"><p className="pw-field-hint">{name(recipient)}有 {recipientPending} 条已排队消息尚未确认消费。{recipientMember && !pendingMessageBusy(recipientMember) ? '继续只使用已保存的消息，不提交当前草稿或追加额度。' : '成员正在工作或等待授权，消息保留在原队列。'}</p>{recipientMember && !pendingMessageBusy(recipientMember) && <Button variant="secondary" disabled={!canMutate} onClick={() => control('continueMessages', recipient)}>使用已排队消息继续</Button>}</div>}
      {uncertain && <p role="status">这条原消息仍待核对，草稿保留，不会重复发送。刷新原记录后仍可暂停或停止团队。</p>}
      </section>
      </AccordionItem></Accordion>

          <div className="pw-team-member-filter" aria-label="按成员查看沟通"><Button variant="ghost" aria-pressed={!memberFilter} onClick={() => chooseMemberFilter('')}>全队</Button>{members.map(member => <Button key={member.id} variant="ghost" aria-pressed={memberFilter === member.id} onClick={() => chooseMemberFilter(member.id)}><TeamMemberIdentity member={member} name={name(member.id)} size={28} /></Button>)}</div>
          <p className="pw-team-conversation-hint">{memberFilter ? `只看与 ${name(memberFilter)} 有关的共享记录。` : '可按成员查看沟通、问题与保存回执。'}</p>
      <section className="pw-team-messages" data-team-section="messages" tabIndex={-1} aria-label="原团队消息"><header className="pw-collaboration-group-heading"><h3>团队沟通</h3><span>{visibleMessages.length} 条消息</span></header>{visibleMessages.map(message => {
        const identity = teamMessageIdentity(message, members), sender = teamMessageAuthor(message, members);
        const quoted = message.quoteMessageId ? object(message.quotedMessage || messages.find(row => row.messageId === message.quoteMessageId)) : null;
        const quotedText = String(quoted?.content || ''), quoteIsExcerpt = quoted?.truncated || quotedText.length > 360;
        const eventRecord = message.role === 'tool' || message.role === 'system';
        const messageContent = <AIAnswer text={String(message.content || '')} actions={isCurrentQuestion(message) && <Button variant="ghost" disabled={!canMutate || !members.some(member => member.id === message.memberId && !member.exited)} aria-pressed={replyId === message.messageId} onClick={() => { messageDraft.guard(() => { setReplyId(message.messageId); setMode('discussion'); setRecipient(message.memberId); jumpToRunSection('compose'); }); }}>回答原问题</Button>} />;
        return <article className="pw-source-record pw-team-message" data-message-kind={eventRecord ? 'event' : message.kind} key={message.messageId} data-message-id={message.messageId} data-motion-key={`message:${message.messageId}`} data-reply-selected={replyId === message.messageId || undefined} tabIndex={-1}>
        <header className="pw-team-message-heading"><h3>{identity ? <TeamMemberIdentity member={identity} name={sender} detail={members.some(member => member.id === identity.id && member.name !== identity.name) ? `原名称 · ${identity.id}` : members.filter(member => member.name === identity.name).length > 1 ? identity.id : undefined} /> : sender}{(message.recipientMemberId || message.targetMemberId) && <small>→ {name(message.recipientMemberId || message.targetMemberId)}</small>}</h3>{message.kind === 'assignment_question' ? <span>{isCurrentQuestion(message) ? '等待你的回答' : message.state === 'pending' ? '历史问题' : '问题记录'}</span> : message.role === 'tool' ? <span>资料回执</span> : message.role === 'system' ? <span>执行记录</span> : null}</header>
        {quoted && quoted.messageId && <div className="pw-team-reply-context"><header><span>{quoteIsExcerpt ? '引用节选' : '引用原消息'} · {teamMessageIdentity(quoted, members) ? <TeamMemberIdentity member={teamMessageIdentity(quoted, members)!} name={teamMessageAuthor(quoted, members)} size={24} /> : teamMessageAuthor(quoted, members)}</span><Button variant="ghost" onClick={() => focusMessage(String(message.quoteMessageId))}>查看原消息</Button></header><p className="pw-team-quote-excerpt">{quotedText.slice(0, 360)}{quoteIsExcerpt ? '…' : ''}</p></div>}
        {eventRecord ? <Accordion variant="divided" className="pw-team-event-record"><AccordionItem id={`event:${message.messageId}`} title={teamGoalTitle(message.content, '查看执行记录')}>{messageContent}</AccordionItem></Accordion> : messageContent}
        <MotionFeedback initial={false} identity={teamMessageDelivery(current, message)} className="pw-muted">{message.kind === 'assignment_question' && message.state === 'pending' && !isCurrentQuestion(message) ? '保留原问题记录，当前不再等待这条回答。' : teamMessageDelivery(current, message)}</MotionFeedback>{message.answer && <div className="pw-team-saved-answer"><span>已保存的回答</span><p>{message.answer}</p></div>}
      </article>; })}{!visibleMessages.length && <p className="pw-secondary-empty">{memberFilter ? '这名成员还没有可显示的沟通记录。' : '这次协作还没有消息。'}</p>}</section>


              </div>
            </AccordionItem>
            {simpleRoster && <AccordionItem id="roster-management" title="调整参与成员" description={`${currentMembers.length} 名参与${members.length > currentMembers.length ? ` · ${members.length - currentMembers.length} 名已退出` : ''}`}>
              <div className="pw-team-detail-body"><p className="pw-field-hint">暂停协作后可加入新成员。已有成员的名称、头像与退出操作在各自进展详情中。</p><Button variant="ghost" leadingIcon={IconAdd} disabled={!canMutate || !rosterPaused || currentMembers.length >= 4 || members.length >= 16} onClick={() => beginMemberChange('add')}>加入成员</Button></div>
            </AccordionItem>}
          </Accordion>
          {(typeof current.callsUsed === 'number' || typeof current.toolCallsUsed === 'number') && <Accordion variant="divided" className="pw-team-run-facts"><AccordionItem id="run-usage" title="本次额度与执行范围"><dl className="pw-team-facts">{typeof current.callsUsed === 'number' && <div><dt>模型调用</dt><dd>{current.callsUsed}{typeof current.callLimit === 'number' ? ` / ${current.callLimit}` : ''} 次</dd></div>}{typeof current.toolCallsUsed === 'number' && <div><dt>工具操作</dt><dd>{current.toolCallsUsed}{typeof current.toolCallLimit === 'number' ? ` / ${current.toolCallLimit}` : ''} 次</dd></div>}{typeof current.externalTurnsUsed === 'number' && current.externalTurnLimit > 0 && <div><dt>电脑执行</dt><dd>{current.externalTurnsUsed} / {current.externalTurnLimit} 轮</dd></div>}</dl><p className="pw-field-hint">继续沿用本次剩余额度。额外范围与预算在所属任务中处理。</p></AccordionItem></Accordion>}
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
        {current?.leaderId === memberDraft.memberId && currentMembers.length > 1 && <SelectField label="由谁接任汇总" value={memberDraft.newLeaderId} options={[{ id: '', label: '选择接任成员' }, ...currentMembers.filter(member => member.id !== memberDraft.memberId).map(member => ({ id: member.id, label: name(member.id) }))]} onChange={value => setMemberDraft({ ...memberDraft, newLeaderId: value })} isDisabled={busy} />}
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
    <SettingsDialog isOpen={!!editor && !review} title={editorPurpose === 'start' ? '旧协作草稿' : definition ? '编辑编组' : '新建编组'} onClose={() => { if (!busy) formDraft.guard(() => setEditor(null)); }}><div className="pw-collaboration-dialog pw-team-editor-dialog" data-collaboration-kind="TeamsWorkspace">{editor && <><div className="pw-team-editor-scroll">
      <FormDraftNotice draft={formDraft} />{draftRecovery}
      {editorPurpose === 'start' ? <TeamQuickStartForm editor={editor} disabled={busy || formDraft.loading || formDraft.requiresRecheck} onGoalChange={changeTeamGoal} onAdvanced={() => formDraft.guard(() => beginEditor(null, 'definition', editor))} /> : <>
      <Accordion defaultExpandedKeys={['team-details']}><AccordionItem id="team-details" title="成员与分工" description={`${editor.name} · ${editor.members.length} 名成员`}>
      <TextField label="编组名称" value={editor.name} onChange={value => setEditor({ ...editor, name: value, nameEdited: true })} maxLength={80} isRequired isDisabled={busy} />
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
      <SelectField label={editor.mode === 'produce' ? '默认收件人' : '负责汇总'} value={editor.leaderId} options={rows(editor.members).map(member => ({ id: member.id, label: member.name || member.id }))} onChange={value => setEditor({ ...editor, leaderId: value })} isDisabled={busy} />
      <p className="pw-field-hint">保存编组供下次选用，不会启动协作或改变已有工作。</p></AccordionItem></Accordion>
      </>}
      {editorPurpose === 'start' && discussionError && <p className="pw-field-error" role="status">{discussionError}</p>}
      {editorPurpose === 'definition' && productionError && <p className="pw-field-hint" role="status">{productionError}</p>}
      </div><footer className="pw-team-editor-footer">
        {error && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}
        {editorPurpose === 'definition' && incompleteMembers.length > 0 && <div className="pw-team-editor-missing" role="status"><span>还有 {incompleteMembers.length} 名成员需要补充</span><Button variant="ghost" disabled={busy} onClick={() => revealMember(incompleteMembers[0].id)}>{incompleteMembers[0].name || '未命名成员'}：{editorMemberIssue(incompleteMembers[0])}</Button></div>}
        {editorPurpose === 'definition' && !editor.name.trim() && <p className="pw-field-hint">填写名称后继续。</p>}
        {busy && <p className="pw-field-hint" role="status">正在核对这次配置，请等待回执。</p>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => formDraft.guard(() => setEditor(null))}>返回并保留草稿</Button><Button variant="ghost" disabled={busy || formDraft.requiresRecheck} onClick={() => formDraft.discard(() => setEditor(null))}>放弃草稿</Button>
          {editorPurpose === 'start' ? onAskButler && <Button disabled={busy || formDraft.loading || !!formDraft.conflict || formDraft.requiresRecheck || !label(editor.goal).trim() || editor.goal.length > 8000} onClick={() => askButler({ goal: editor.goal })}>带目标回管家</Button> : <Button disabled={busy || formDraft.stale || !!formDraft.conflict || formDraft.requiresRecheck || !definitionReady || !editorPayload || !editor.name.trim() || editor.name.length > 80 || incompleteMembers.length > 0 || !!discussionError} onClick={() => { if (editorPayload) void invoke('teams.reviewDefinition', { ...editorPayload, ...(definition ? { viewHandle: reading!.viewHandle } : {}) }, reviewResult); }}>核对并保存编组</Button>}
        </div>
      </footer>
    </>}</div></SettingsDialog>
    <SettingsDialog isOpen={!!review} title={review?.operation === 'configureMember' ? '核对本次成员改动' : review?.operation === 'start' ? '开始这次协作' : review?.operation === 'cancel' ? '停止这次协作' : review?.operation === 'feedback' ? '修改要求并暂停' : review?.operation === 'remove' ? '移除编组' : '保存编组'} onClose={() => { if (!busy) closeReview(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">
      {review?.operation === 'configureMember' ? <>
        <TeamMemberIdentity member={review.member || {}} name={teamMemberLabel(review.member || {}, rows(review.roster))} size={40} />
        {review.input?.action === 'rename' ? <><p>改为：</p><TeamMemberIdentity member={{ ...review.member, name: review.input.name, avatarShape: review.input.avatarShape }} size={40} /><p>历史消息和已开始的轮次保留原身份；不改职责、权限、额度或执行状态。</p></>
          : review.input?.action === 'add' ? <><p>职责：{review.member?.role}</p><p>具体分工：{review.member?.assignment}</p><p>本机模型：{review.member?.model || '确认加入时的主模型'}</p><p>以暂停状态加入当前运行，保留独立新身份；未开始工作、未继承旧成员的独立资料范围或定向消息、未增加额度。同队共享目标和公开协作记录仍可用于这次分工。回到分工页后明确继续。</p></>
          : <><p>退出后保留已有产出和历史消息，未消费的补充不再唤醒这名成员；不会把任务交给其他成员。</p>{review.researchExit && <p>仅停止这名成员后续执行。旧成果按冻结作者与原资料范围核验，接任者不继承其资料读取权；到期、换版或撤回后不可继续用于新回答，可在“资料”中核对与撤回。</p>}{review.input?.newLeaderId && <p>接任汇总：{teamMemberLabel(rows(review.roster).find(member => member.id === review.input.newLeaderId) || { id: review.input.newLeaderId }, rows(review.roster))}</p>}<p>尚未完成的前置依赖会阻止退出。暂停和退出不承诺撤销已发生的效果。</p></>}
      </> : review?.operation === 'start' ? <TeamStartReview review={review} phoneTargets={phoneTargets} /> : review?.operation === 'feedback' ? <><p>发给{review.recipientName}的新要求：</p><AIAnswer text={review.text} showActions={false} /><p>相关成员会暂停；已有成果保留，受影响的总结待更新。你选择继续后才会处理新要求，不自动扩大权限或预算。</p></> : review?.operation === 'cancel' ? <p>停止这个原运行后续工作，保留消息、证据和已有成果。停止请求不代表未知外部操作已完成清理。</p> : review?.operation === 'remove' ? <p>移除“{review.definition?.name}”的后续启动配置，已有运行和成果保留。</p> : <><p>{review?.input?.name}</p>{rows(review?.input?.members).map(member => <section className="pw-source-record" key={member.id}><h3><TeamMemberIdentity member={member} name={teamMemberLabel(member, rows(review?.input?.members))} size={40} /></h3><p>{member.role}</p>{member.assignment && member.assignment !== member.role && member.runner !== 'phone_agent' && <p>具体分工：{member.assignment}</p>}{rows(member.dependsOn).length > 0 && <p>前置：{member.dependsOn.map((id: string) => rows(review?.input?.members).find(row => row.id === id)?.name || id).join('、')}</p>}{member.runner === 'phone_agent' ? <p>{teamPhoneTargetLabel(member.device, phoneTargets)} · {member.assignment}</p> : isTeamHarness(member.runner) ? <><p>执行器：{harnessWorkerLabel(undefined, member.runner)} · 电脑：{computerLabel(member)}</p><p>模型：{member.model || '执行器默认'}{member.modeId ? ` · 模式：${member.modeId}` : ''}</p></> : <p>模型：{member.model || '实际启动时的主模型'} · 本机模型连接</p>}{review?.input?.mode === 'produce' && <TeamProductionReview member={member} sources={object(catalog.productionSources)} />}</section>)}</>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>已核对这次操作与原目标</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={async () => {
        const original = review; if (!original || entered.current || backgrounded.current || !ownsNativeWindow()) return; setReview(null); setAcknowledged(false);
        setBusy(true);
        try { if (original.operation === 'save') await formDraft.markSubmitted(); else if (original.operation === 'start') { const pending = { pendingStartTeamId: original.input.teamId, pendingStartSince: Date.now() }; if (original.draft) { const next = { ...editor, ...pending }; setEditor(next); setQuickStartPending(pendingStart(next)); formDraft.update(safeTeamDraft(next)); await formDraft.markSubmitted(); } else { const next = { goal, rounds, ...pending }; setSavedStartPending(pendingStart(next)); startDraft.update(next); await startDraft.markSubmitted(); } } else if (original.operation === 'configureMember' && memberDraft?.action !== 'exit') await memberFormDraft.markSubmitted(); else if (original.operation === 'feedback') await messageDraft.markSubmitted(); } catch (failure) { setBusy(false); setError((failure as Error).message); return; }
        if (!alive.current || !ownsNativeWindow()) return;
        const directControl = original.operation === 'cancel' || original.operation === 'feedback';
        if (original.operation === 'configureMember') { pendingMemberChange.current = original.input.runId; setReading(value => value ? { ...value, viewHandle: '' } : null); }
        if (directControl) setReading(value => value ? { ...value, viewHandle: '' } : null);
        void invoke(directControl ? 'teams.control' : 'teams.confirm', directControl ? { command: original.operation, viewHandle: original.viewHandle,
          ...(original.operation === 'feedback' ? { memberId: original.memberId, text: original.text.trim() } : {}) } : { reviewHandle: original.reviewHandle }, result => {
          if (result.confirmed !== true) throw new Error('原团队操作尚未确认，请刷新核对。');
          if (original.operation === 'start' && (result.command !== 'start' || !result.reading?.run?.id || result.reading.run.teamId !== original.input.teamId)) throw new Error('启动回执身份待核对，请读取协作记录；不会再次启动。');
          if (original.operation === 'configureMember') {
            if (result.command !== 'configureMember' || result.reading?.run?.id !== original.input.runId) throw new Error('成员改动回执待核对，请刷新原协作，没有自动重发。');
            pendingMemberChange.current = null; if (original.input.action !== 'exit') memberFormDraft.discard(() => setMemberDraft(null)); else setMemberDraft(null); acceptReading(result.reading);
            if (original.input.action === 'add') setExpandedMembers(old => ({ ...old, [original.input.runId]: [...new Set([...(old[original.input.runId] || []), original.member.id])] }));
            if (original.input.action === 'exit' && recipient === original.input.memberId) { setRecipient(result.reading.run.leaderId || ''); setReplyId(''); }
            setNotice(original.input.action === 'add' ? '新成员已加入并暂停。核对分工后，明确继续该成员；没有追加额度。' : original.input.action === 'exit' ? '成员已退出本次协作，历史与成果保留，没有自动重派。' : '本次协作的名称与头像已更新，历史身份和团队配置保留。');
            return;
          }
          if (result.command === 'save') { const nextId = result.reading?.definition?.id || result.result?.id; if (nextId && editor?.goal) startInputs.current.set(nextId, { goal: editor.goal, rounds: editor.rounds || '1' }); formDraft.discard(() => setEditor(null)); } else setEditor(null); setNotice(result.command === 'save' ? '编组已保存，管家可按工作目标安排合适的分工。' : '已收到操作回执。');
          if (original.operation === 'feedback') {
            if (result.command !== 'feedback' || result.reading?.run?.id !== original.runId) throw new Error('补充结果待核对，请刷新原协作记录；不会自动重发。');
            setDrafts(old => old[original.draftKey] === original.text ? { ...old, [original.draftKey]: '' } : old);
            messageDraft.discard(() => { void messageDraft.open('owner', 'message:' + original.draftKey, original.draftKey, { text: '' }); });
            setNotice('新要求已保存，受影响成员已暂停。选择继续后处理，未自动重跑。');
          }
          if (result.reading) { acceptReading(result.reading); if (original.operation === 'start') { (original.draft ? formDraft : startDraft).discard(); if (original.draft) setQuickStartPending(null); else setSavedStartPending(null); if (!original.draft) { startInputs.current.delete(original.definition?.id || original.input?.teamId); setGoal(''); } } }
          else { rememberView(); release(reading); setReading(null); setTab('teams'); setCatalog(old => ({ ...old, teams: result.command === 'save' ? [...rows(old.teams).filter(team => team.id !== result.result.id), result.result] : rows(old.teams).filter(team => team.id !== original.input?.id) })); }
        }, failure => {
          // Only the bridge's explicit pre-admission refusal proves that no
          // start reached FeatureHost. Unknown receipts retain their draft lock.
          if (original.operation !== 'start' || !(failure instanceof Error) || object(failure).code !== 'not_accepted') return;
          if (original.draft && editor) {
            const { pendingStartTeamId: _team, pendingStartSince: _since, ...restored } = editor;
            setEditor(restored); setQuickStartPending(null); formDraft.update(safeTeamDraft(restored)); formDraft.clearSubmitted();
          } else if (!original.draft) {
            setSavedStartPending(null); startDraft.update({ goal, rounds }); startDraft.clearSubmitted();
          }
          setNotice('这次启动未被受理，目标与分工已保留。请重新核对后再确认开始。');
        });
      }}>{review?.operation === 'start' ? '开始协作' : review?.operation === 'save' ? '保存编组' : '确认这一次操作'}</Button></div>
    </div></SettingsDialog>
  </section>;
}



