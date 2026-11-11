import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { IconAccount, IconAdd, IconArrowLeft, IconArrowUpRight, IconCalendarCheck, IconChat, IconCheck, IconChevronRight, IconControls, IconGoal, IconHistory, IconRefresh, IconSettings, IconShieldCheck, IconSparkles, IconTrash, IconUndo } from './icons';
import { Accordion, AccordionItem, Button, IconButton, SearchField, SelectField, SettingsDialog, StretchSwitch, TextAreaField, TextField } from './forms-library';
import { Radio, RadioGroup } from './forms-library/boardui/components/base/radio/radio';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, WorkingStatus, useCollectionMotion } from './library/journey-motion';
import './profile.css';

type Data = Record<string, any>;
type Section = 'identity' | 'communication' | 'behavior';
type View = 'home' | Section | 'memories' | 'memory' | 'memory-new' | 'memory-settings' | 'memory-history';
type Run = (action: string, args?: Record<string, unknown>) => Promise<any>;
type LeaveGuard = (next: () => void) => boolean;
export type PersonalTarget = { view: 'home' | 'memories' | 'memory-settings' } | { view: 'memory'; id: string; candidate: boolean };
export type PersonalProfile = {
  revision: number;
  nickname: string;
  addressAs: string;
  about: string;
  avatarDataUrl: string;
  language: string;
  response_detail: string;
  conclusionFirst: boolean;
  includeExamples: boolean;
  instructions: string;
  operation_pace: string;
  initiative: string;
};
type Props = {
  state: Data;
  supported: (action: string) => boolean;
  run: Run;
  onBack: () => void;
  onSettings: () => void;
  registerBack: (handler: (() => boolean) | null) => void;
  registerBeforeLeave: (guard: LeaveGuard | null) => void;
  presentationKey?: string;
  initialTarget?: PersonalTarget;
  targetRequestKey?: string;
  onOpenAdvancedMemory?: () => void;
  onOpenCommitments?: () => void;
  onOpenReminders?: () => void;
};
type Memories = { current: Data[]; candidates: Data[]; pendingConflicts: Data[]; history: Data[]; complete: boolean };
type MemoryPolicy = { reading: { enabled: boolean; revision: number }; learning: { revision: number; enabled: boolean; maxUserSourcesPerTask: number; candidateAcceptance: string } };
type AgentPreferenceControl = { enabled: boolean; revision: number };
type Editor = { section: Section; original: PersonalProfile; draft: PersonalProfile };
type MemorySelection = { id: string; candidate: boolean; value: Data; record: Data; originalText: string; draft: string; historical?: boolean };
type MemoryReview = { value: Data; operation: 'forget' | 'accept' | 'reject'; id: string; candidate: boolean };
type MutationOperation = 'create' | 'correct' | MemoryReview['operation'];
type MemoryFeedback = { value: Data; operation: MutationOperation; needsQuery: boolean; targetId?: string; submittedText?: string };
type AvatarPicker = { id: string; editor: number };
type AvatarPoll = { sequence: number; key: string | undefined; visibility: number; picker: AvatarPicker };
const labels: Record<View, string> = { home: '我与管家', identity: '编辑资料', communication: '交流偏好', behavior: '做事偏好', memories: '个人记忆', memory: '编辑记忆', 'memory-new': '记住一件事', 'memory-settings': '记忆设置', 'memory-history': '过去的记忆' };
const languageOptions = [{ id: 'auto', label: '跟随对话' }, { id: 'zh-CN', label: '中文' }, { id: 'en', label: 'English' }, { id: 'ja', label: '日本語' }];
const detailOptions = [{ id: 'concise', label: '简短' }, { id: 'standard', label: '跟随问题' }, { id: 'detailed', label: '详细' }];
const paceOptions = [{ id: 'fast', label: '高效' }, { id: 'standard', label: '均衡' }, { id: 'careful', label: '细致' }];
const initiativeOptions = [{ id: 'requested', label: '按需' }, { id: 'balanced', label: '适度' }, { id: 'proactive', label: '主动建议' }];
const expiryOptions = [{ id: 'none', label: '不设到期时间' }, { id: '7', label: '7 天后到期' }, { id: '30', label: '30 天后到期' }];
const emptyMemories = (): Memories => ({ current: [], candidates: [], pendingConflicts: [], history: [], complete: false });
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const rows = (value: any): Data[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const string = (value: any) => typeof value === 'string' ? value : '';
const option = (value: any, choices: Array<{ id: string }>, fallback: string) => choices.some(choice => choice.id === value) ? value : fallback;
const avatarUrl = (value: any) => typeof value === 'string' && value.length <= 300000 && /^data:image\/(?:jpeg|png|webp);base64,[a-z\d+/=\s]+$/i.test(value) ? value : '';
function policyOf(value: any): MemoryPolicy | null {
  const reading = object(value?.reading), learning = object(value?.learning);
  if (typeof reading.enabled !== 'boolean' || !Number.isSafeInteger(reading.revision) || reading.revision < 0 || typeof learning.enabled !== 'boolean' || !Number.isSafeInteger(learning.revision) || learning.revision < 0 || !Number.isSafeInteger(learning.maxUserSourcesPerTask) || learning.maxUserSourcesPerTask < 1) return null;
  return { reading: { enabled: reading.enabled, revision: reading.revision }, learning: { enabled: learning.enabled, revision: learning.revision, maxUserSourcesPerTask: learning.maxUserSourcesPerTask, candidateAcceptance: string(learning.candidateAcceptance) } };
}
const agentPreferenceControlOf = (value: any): AgentPreferenceControl | null => typeof value?.enabled === 'boolean' && Number.isSafeInteger(value?.revision) && value.revision > 0 ? { enabled: value.enabled, revision: value.revision } : null;
function preferenceSectionOf(value: Data): 'communication' | 'behavior' | null {
  if (value.status !== 'settings_required' || value.saved !== false || value.undoAvailable !== false) return null;
  if (value.settingsSection === 'communication' && ['language', 'response_detail', 'conclusionFirst', 'includeExamples'].includes(value.preferenceKey)) return 'communication';
  if (value.settingsSection === 'work' && ['operation_pace', 'initiative'].includes(value.preferenceKey)) return 'behavior';
  return null;
}
const settingsRequiredFor = (feedback: MemoryFeedback | null, operation: MutationOperation, targetId?: string, text?: string) => !!feedback && !feedback.needsQuery && !!preferenceSectionOf(feedback.value) && feedback.operation === operation && feedback.targetId === targetId && (text === undefined || feedback.submittedText === text);
const timeValue = (value: any) => typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : typeof value === 'string' ? Date.parse(value) : NaN;
const dateLabel = (value: any) => Number.isFinite(timeValue(value)) ? new Date(timeValue(value)).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '';
const expiredMemory = (row: Data) => Number.isFinite(timeValue(row.expiresAt)) && timeValue(row.expiresAt) <= Date.now();
const historicalMemory = (row: Data) => row.active === false || expiredMemory(row) || ['expired', 'superseded', 'archived'].includes(row.status);
const topicOf = (row: Data) => ({ id: string(row.topic?.id) || 'other', label: string(row.topic?.label) || '其他' });
function memoryDates(row: Data): string {
  const expiry = dateLabel(row.expiresAt), updated = dateLabel(row.updatedAt || row.createdAt);
  return [updated ? `${updated} 更新` : '', expiry ? `${expiredMemory(row) ? '已到期' : '有效至'} ${expiry}` : ''].filter(Boolean).join(' · ');
}
function feedbackLabel(feedback: MemoryFeedback): string {
  if (feedback.needsQuery || feedback.value.status === 'unknown') return '这次修改的结果待核对';
  if (feedback.value.status === 'settings_required') return string(feedback.value.message) || '这项默认偏好请在偏好设置中修改，本次没有写入记忆。';
  if (feedback.value.status === 'not_submitted') return '这次修改尚未提交，输入已保留，可以重新保存';
  if (feedback.value.duplicate === true || feedback.value.status === 'already_saved') return string(feedback.value.message) || (feedback.value.candidate ? '已有相同待确认内容' : '已经记住这条内容');
  if (feedback.value.status === 'reverted') return '已撤销这次保存';
  if (feedback.value.status === 'conflict') return '内容已有变化，请查看最新记忆';
  return feedback.operation === 'forget' ? '已忘记这条记忆' : feedback.operation === 'reject' ? '已忽略这条候选' : feedback.operation === 'correct' ? '已更新记忆' : '已记住';
}
function profileOf(value: any): PersonalProfile | null {
  const row = object(value);
  if (!Number.isSafeInteger(row.revision) || row.revision <= 0) return null;
  return {
    revision: row.revision, nickname: string(row.nickname), addressAs: string(row.addressAs), about: string(row.about), avatarDataUrl: avatarUrl(row.avatarDataUrl),
    language: option(row.language, languageOptions, 'auto'), response_detail: option(row.response_detail, detailOptions, 'standard'),
    conclusionFirst: row.conclusionFirst === true, includeExamples: row.includeExamples === true, instructions: string(row.instructions),
    operation_pace: option(row.operation_pace, paceOptions, 'standard'), initiative: option(row.initiative, initiativeOptions, 'balanced'),
  };
}
function sectionValues(profile: PersonalProfile, section: Section): Record<string, unknown> {
  if (section === 'identity') return { nickname: profile.nickname, addressAs: profile.addressAs, about: profile.about, avatarDataUrl: profile.avatarDataUrl };
  if (section === 'communication') return { language: profile.language, response_detail: profile.response_detail, conclusionFirst: profile.conclusionFirst, includeExamples: profile.includeExamples, instructions: profile.instructions };
  return { operation_pace: profile.operation_pace, initiative: profile.initiative };
}
function sameSection(a: PersonalProfile, b: PersonalProfile, section: Section): boolean {
  if (section === 'identity') return a.nickname === b.nickname && a.addressAs === b.addressAs && a.about === b.about && a.avatarDataUrl === b.avatarDataUrl;
  if (section === 'communication') return a.language === b.language && a.response_detail === b.response_detail && a.conclusionFirst === b.conclusionFirst && a.includeExamples === b.includeExamples && a.instructions === b.instructions;
  return a.operation_pace === b.operation_pace && a.initiative === b.initiative;
}
const profileDirty = (editor: Editor | null) => !!editor && !sameSection(editor.original, editor.draft, editor.section);
const memoryDirty = (selection: MemorySelection | null) => !!selection && selection.draft !== selection.originalText;
function retainLocalChanges(editor: Editor, latest: PersonalProfile): PersonalProfile {
  const next = { ...latest }, original = sectionValues(editor.original, editor.section);
  for (const [key, value] of Object.entries(sectionValues(editor.draft, editor.section))) {
    if (value !== original[key]) Object.assign(next, { [key]: value });
  }
  return next;
}
const selectedLabel = (choices: Array<{ id: string; label: string }>, value: string) => choices.find(choice => choice.id === value)?.label || '';
function memoryOrigin(row: Data, candidate = false): string {
  if (candidate || row.status === 'pending') return '待我确认';
  if (row.source === '主人主动添加' || row.sourceSummary === '主人主动添加' || row.formation === 'owner_added') return '你主动记录';
  if (historicalMemory(row)) return expiredMemory(row) ? '已到期' : '已退出当前记忆';
  if (row.assertionReview?.revision === row.revision || ['reviewed_learning', 'reviewed_consolidation'].includes(row.formation)) return '已确认';
  if (row.assertion?.kind === 'user_said') return '来自我的表达';
  return '已保存';
}
function Avatar({ profile, large = false }: { profile: PersonalProfile | null; large?: boolean }) {
  const nickname = profile?.nickname.trim() || profile?.addressAs.trim() || '';
  return <span className={`pw-profile-avatar${large ? ' is-large' : ''}`} aria-hidden="true">{profile?.avatarDataUrl ? <img src={profile.avatarDataUrl} alt="" /> : nickname ? <span>{Array.from(nickname)[0]}</span> : <IconAccount strokeWidth={1.65} />}</span>;
}
function Segmented({ label, value, options, disabled, onChange }: { label: string; value: string; options: Array<{ id: string; label: string }>; disabled: boolean; onChange: (value: string) => void }) {
  return <div className="pw-profile-choice"><h2>{label}</h2><RadioGroup aria-label={label} value={value} onChange={onChange} isDisabled={disabled} className="pw-profile-segments">{options.map(item => <Radio key={item.id} value={item.id} className="pw-profile-segment"><span>{item.label}</span></Radio>)}</RadioGroup></div>;
}
/** Personal views share the existing host-owned profile and memory records. */
export function ProfileWorkspace({ state, supported, run, onBack, onSettings, registerBack, registerBeforeLeave, presentationKey, initialTarget, targetRequestKey, onOpenAdvancedMemory, onOpenCommitments, onOpenReminders }: Props) {
  const [profile, setProfile] = useState<PersonalProfile | null>(null);
  const [memories, setMemories] = useState<Memories>(emptyMemories);
  const [memoryError, setMemoryError] = useState(''), [memoryAvailable, setMemoryAvailable] = useState(false);
  const [loaded, setLoaded] = useState(false), [view, setView] = useState<View>('home');
  const [enteredProfile, setEnteredProfile] = useState(false), [entryAttempt, setEntryAttempt] = useState(0);
  const [editor, setEditor] = useState<Editor | null>(null), [selection, setSelection] = useState<MemorySelection | null>(null);
  const [review, setReview] = useState<MemoryReview | null>(null), [source, setSource] = useState<Data | null>(null), [sourceOpen, setSourceOpen] = useState(false);
  const [pending, setPending] = useState(''), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [avatarPicking, setAvatarPicking] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false), [memoryConflict, setMemoryConflict] = useState(false), [memoryUnknown, setMemoryUnknown] = useState(false);
  const [latestMemory, setLatestMemory] = useState<Data | null>(null);
  const [query, setQuery] = useState(''), [topic, setTopic] = useState('all'), [showCandidates, setShowCandidates] = useState(false);
  const [newMemoryText, setNewMemoryText] = useState(''), [newMemoryExpiry, setNewMemoryExpiry] = useState('none'), [createUnknown, setCreateUnknown] = useState(false);
  const [policy, setPolicy] = useState<MemoryPolicy | null>(null), [controlsError, setControlsError] = useState('');
  const [agentPreferences, setAgentPreferences] = useState<AgentPreferenceControl | null>(null);
  const [feedback, setFeedback] = useState<MemoryFeedback | null>(null);
  const [duties, setDuties] = useState<Data | null>(null), [dutiesReading, setDutiesReading] = useState(false), [dutiesError, setDutiesError] = useState('');
  const [dutiesAttempt, setDutiesAttempt] = useState(0);
  const alive = useRef(true), busy = useRef(false), runRef = useRef(run), supportedRef = useRef(supported);
  const readHandle = useRef(''), reviewHandle = useRef(''), pendingLeave = useRef<(() => void) | null>(null);
  const avatarPicker = useRef<AvatarPicker | null>(null), editorInstance = useRef(0);
  const avatarPoll = useRef<AvatarPoll | null>(null), avatarPollSequence = useRef(0), avatarVisibility = useRef(0);
  const avatarWaitingForPresentation = useRef<{ key: string } | null>(null), wakeAvatar = useRef<(() => void) | null>(null);
  const current = useRef({ view, editor, selection, profile, review, leaveOpen, memoryUnknown, newMemoryText, newMemoryExpiry, createUnknown, feedback });
  current.current = { view, editor, selection, profile, review, leaveOpen, memoryUnknown, newMemoryText, newMemoryExpiry, createUnknown, feedback };
  runRef.current = run; supportedRef.current = supported;
  const scroller = useRef<HTMLDivElement>(null), heading = useRef<HTMLHeadingElement>(null);
  const positions = useRef(new Map<string, number>()), returnMemory = useRef(''), previousView = useRef<View>('home');
  const memoryParent = useRef<'home' | 'memories' | 'memory-history'>('memories');
  const handledTarget = useRef('');
  const previousPresentation = useRef(presentationKey);
  const livePresentation = useRef(presentationKey);
  livePresentation.current = presentationKey;
  useSurfaceEnter(scroller, JSON.stringify([view, view === 'memory' ? selection?.id : null]), {selector: ':scope > .pw-profile-content', depth: view === 'home' ? 0 : ['memory', 'memory-new', 'memory-settings'].includes(view) ? 2 : 1, active: loaded && enteredProfile});
  const canEnter = supported('connections.enter'), canRead = supported('profile.snapshot'), canSave = supported('profile.save');
  const locked = !!pending;
  const formLocked = locked || avatarPicking || !canSave;
  const profileConflict = !!editor && !!profile && editor.original.revision !== profile.revision && !sameSection(editor.original, profile, editor.section);
  const pendingCandidates = memories.candidates.filter(row => !row.status || row.status === 'pending');
  const activeMemories = memories.current.filter(row => !historicalMemory(row));
  const searchText = query.trim().toLocaleLowerCase();
  const searched = (row: Data) => !searchText || [row.text, row.title, row.sourceSummary, topicOf(row).label].filter(Boolean).join(' ').toLocaleLowerCase().includes(searchText);
  const visibleMemories = activeMemories.filter(row => searched(row) && (topic === 'all' || topicOf(row).id === topic));
  const topics = [...new Map(activeMemories.map(row => [topicOf(row).id, topicOf(row)])).values()];
  const visibleCandidates = pendingCandidates.filter(searched);
  const historyRows = memories.history.filter(searched);
  useCollectionMotion(scroller, JSON.stringify([presentationKey, view, searchText, topic, showCandidates]), JSON.stringify([
    visibleMemories.map(row => [row.id, row.revision, topicOf(row).id]),
    visibleCandidates.map(row => [row.id, row.status]), historyRows.map(row => [row.id, row.revision]),
    notice, error, feedback?.value.status, feedback?.needsQuery,
  ]), { active: loaded && enteredProfile && memoryAvailable });

  const closeHandle = (handle: string) => { if (handle && supportedRef.current('profile.memory.close')) void runRef.current('profile.memory.close', { handle }).catch(() => {}); };
  const closeAvatar = () => {
    const picker = avatarPicker.current; avatarPicker.current = null;
    avatarPoll.current = null; avatarWaitingForPresentation.current = null;
    if (alive.current) setAvatarPicking(false);
    if (picker) void runRef.current('profile.avatar.close', { pickerId: picker.id }).catch(() => {});
  };
  const clearReview = () => { closeHandle(reviewHandle.current); reviewHandle.current = ''; setReview(null); };
  const clearSelection = () => { clearReview(); closeHandle(readHandle.current); readHandle.current = ''; setSelection(null); setSource(null); setSourceOpen(false); setMemoryConflict(false); setMemoryUnknown(false); setLatestMemory(null); };
  const action = async (name: string, body: () => Promise<void>) => {
    if (busy.current || !alive.current) return;
    busy.current = true; setPending(name); setError(''); setNotice('');
    try { await body(); }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : '操作未完成，请重试。'); }
    finally { busy.current = false; if (alive.current) setPending(''); }
  };
  const call = async (name: string, args: Record<string, unknown> = {}) => {
    if (!supportedRef.current(name)) throw new Error('当前版本暂不支持此操作，请更新应用后重试。');
    return runRef.current(name, args);
  };
  const acceptProfile = (next: PersonalProfile) => {
    if (!alive.current) return;
    setProfile(next);
    setEditor(old => {
      if (!old) return old;
      if (!profileDirty(old)) return { ...old, original: next, draft: { ...next } };
      // A change to a different section may advance the shared revision safely.
      if (sameSection(old.original, next, old.section)) return { ...old, original: next, draft: { ...old.draft, revision: next.revision } };
      return old;
    });
  };
  const readSnapshot = async () => {
    const result = object(await call('profile.snapshot'));
    const next = profileOf(result.profile);
    if (!next) throw new Error('个人资料尚未读取完整，请重试。');
    if (!alive.current) return result;
    acceptProfile(next);
    setAgentPreferences(agentPreferenceControlOf(result.agentPreferences));
    const data = object(result.memories);
    setMemories({ current: rows(data.current), candidates: rows(data.candidates), pendingConflicts: rows(data.pendingConflicts), history: rows(data.history), complete: data.complete === true });
    const controls = policyOf(result.policy);
    if (controls) { setPolicy(controls); setControlsError(''); }
    const unavailable = string(result.memoryError) || (data.available === false ? '个人记忆暂时无法读取，请重试。' : '');
    setMemoryError(unavailable); setMemoryAvailable(!unavailable);
    setLoaded(true);
    return result;
  };
  const readControls = async () => {
    const result = object(await call('profile.memory.controls'));
    const controls = policyOf(result.controls || result.policy || result);
    if (!controls) throw new Error('记忆设置尚未读取完整，请重新读取。');
    if (alive.current) { setPolicy(controls); setControlsError(''); }
    return controls;
  };
  const rememberReceipt = (value: Data, operation: MutationOperation, needsQuery = false, targetId?: string, submittedText?: string) => {
    if (!alive.current || !string(value.receiptHandle)) return;
    setFeedback({ value, operation, needsQuery, targetId, submittedText });
  };
  const settlePreferenceReceipt = (value: Data, receiptHandle: string, operation: MutationOperation, targetId?: string, submittedText?: string) => {
    if (value.status !== 'settings_required') return false;
    if (value.receiptHandle !== receiptHandle || !preferenceSectionOf(value)) throw new Error('偏好设置提示的原回执尚未完整核对。');
    if (alive.current) {
      rememberReceipt(value, operation, false, targetId, submittedText);
      if (operation === 'create') setCreateUnknown(false);
      if (current.current.selection?.id === targetId) setMemoryUnknown(false);
    }
    return true;
  };
  const refresh = () => {
    if (!enteredProfile || !canRead) { setEntryAttempt(value => value + 1); return; }
    if (canRead) void action('正在读取资料', async () => { await readSnapshot(); });
  };
  useEffect(() => {
    alive.current = true;
    let active = true;
    setEnteredProfile(false);
    if (canEnter) void action('正在打开个人资料', async () => {
      await runRef.current('connections.enter', { section: 'profile' });
      if (active && alive.current) setEnteredProfile(true);
    });
    return () => {
      active = false; alive.current = false;
      closeAvatar(); closeHandle(readHandle.current); closeHandle(reviewHandle.current); readHandle.current = ''; reviewHandle.current = ''; pendingLeave.current = null;
      if (canEnter) void runRef.current('connections.leave', { section: 'profile' }).catch(() => {});
    };
  }, [canEnter, entryAttempt]);
  useEffect(() => {
    if (enteredProfile && canRead && !loaded && !busy.current) void action('正在读取资料', async () => { await readSnapshot(); });
  }, [enteredProfile, canRead, loaded]);
  useEffect(() => {
    if (!enteredProfile || !loaded || view !== 'home') return;
    let active = true; const original = livePresentation.current;
    setDutiesReading(true); setDutiesError('');
    const commands = ['commitments.snapshot', 'automation.list', 'automation.receipts'].filter(name => supportedRef.current(name));
    void Promise.allSettled(commands.map(name => runRef.current(name))).then(results => {
      if (!active || !alive.current || livePresentation.current !== original) return;
      const failures: string[] = [];
      results.forEach((result, index) => {
        if (result.status === 'rejected') failures.push(result.reason instanceof Error ? result.reason.message : '职责状态尚未完整读取。');
        else if (commands[index] === 'commitments.snapshot') {
          const value = object(result.value);
          if (typeof value.policy?.enabled === 'boolean' && Array.isArray(value.commitments)) setDuties(value);
          else failures.push('跟进状态尚未完整读取。');
        }
      });
      setDutiesError(failures.join(' ')); setDutiesReading(false);
    });
    return () => { active = false; };
  }, [enteredProfile, loaded, view, presentationKey, dutiesAttempt]);
  const hostRevision = object(state.personalProfile).revision;
  useEffect(() => {
    // Streaming snapshots deliberately carry identity metadata only. Fetch the
    // full owner profile when its revision changes; never turn metadata into defaults.
    if (enteredProfile && canRead && loaded && !busy.current && Number.isSafeInteger(hostRevision) && current.current.profile && hostRevision > current.current.profile.revision) void action('正在核对最新资料', async () => { await readSnapshot(); });
  }, [hostRevision, pending, enteredProfile, canRead, loaded]);
  useEffect(() => {
    const previous = previousPresentation.current;
    previousPresentation.current = presentationKey;
    if (previous === undefined || previous === presentationKey) return;
    setFeedback(old => old ? { ...old, needsQuery: true } : old);
    if (!current.current.selection) return;
    // Native background/task transitions retire these exact read/review handles.
    // Keep the user's text, but require a fresh read before any later mutation.
    clearReview(); closeHandle(readHandle.current); readHandle.current = '';
    setSource(null); setSourceOpen(false); setLatestMemory(null); setMemoryConflict(true);
    setNotice('页面状态已变化。输入已保留，请重新读取这条记忆后继续。');
  }, [presentationKey]);
  useEffect(() => {
    if (!avatarPicking) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const waitingForWindow = () => avatarWaitingForPresentation.current?.key === livePresentation.current && avatarWaitingForPresentation.current !== null;
    const schedule = () => { if (timer) clearTimeout(timer); if (active && avatarPicker.current && !document.hidden && !waitingForWindow()) timer = setTimeout(() => { timer = undefined; void poll(); }, 500); };
    const poll = async () => {
      if (!active || !alive.current || !avatarPicker.current) return;
      const picker = avatarPicker.current;
      if (document.hidden) return;
      if (waitingForWindow()) return;
      if (busy.current || !supportedRef.current('profile.avatar.result')) { schedule(); return; }
      if (current.current.editor?.section !== 'identity' || picker.editor !== editorInstance.current) { closeAvatar(); return; }
      const key = livePresentation.current, visibility = avatarVisibility.current;
      const prior = avatarPoll.current;
      if (prior && prior.key === key && prior.visibility === visibility && prior.picker === picker) return;
      const request: AvatarPoll = { sequence: ++avatarPollSequence.current, key, visibility, picker };
      avatarPoll.current = request;
      const sameWindow = () => active && alive.current && avatarPicker.current === picker && avatarPoll.current === request && livePresentation.current === key && avatarVisibility.current === visibility;
      try {
        const result = object(await runRef.current('profile.avatar.result', { pickerId: picker.id }));
        if (!sameWindow()) return;
        if (string(result.pickerId) !== picker.id) throw new Error('头像选择回执不一致，请重新选择。');
        if (result.pending === true) return;
        if (result.cancelled === true) { closeAvatar(); return; }
        const data = avatarUrl(result.avatarDataUrl);
        if (result.cancelled !== false || !data) throw new Error('头像尚未读取完整，请重新选择。');
        if (current.current.editor?.section === 'identity' && picker.editor === editorInstance.current) setEditor(old => old?.section === 'identity' ? { ...old, draft: { ...old.draft, avatarDataUrl: data } } : old);
        closeAvatar();
      } catch (failure) {
        if (!sameWindow() || document.hidden) return;
        const code = string(object(failure).code), message = failure instanceof Error ? failure.message : '';
        if (code === 'not_sent' || /正在重新绑定|页面已恢复|等待页面恢复/.test(message)) {
          if (key !== undefined) avatarWaitingForPresentation.current = { key };
          return;
        }
        if (code === 'outcome_unknown') return;
        closeAvatar(); setError(message || '头像选择未完成，请重新选择。');
      } finally {
        // An obsolete promise may settle after a new foreground request. It
        // cannot unlock, cancel, or reschedule the newer request.
        if (avatarPoll.current === request) { avatarPoll.current = null; schedule(); }
      }
    };
    const wake = () => {
      if (avatarWaitingForPresentation.current && avatarWaitingForPresentation.current.key !== livePresentation.current) avatarWaitingForPresentation.current = null;
      if (timer) { clearTimeout(timer); timer = undefined; }
      if (!document.hidden) void poll();
    };
    const onVisible = () => {
      avatarVisibility.current++;
      if (timer) { clearTimeout(timer); timer = undefined; }
      if (document.hidden) {
        if (livePresentation.current !== undefined) avatarWaitingForPresentation.current = { key: livePresentation.current };
        avatarPoll.current = null;
      } else wake();
    };
    wakeAvatar.current = wake;
    if (document.hidden && livePresentation.current !== undefined) avatarWaitingForPresentation.current = { key: livePresentation.current };
    document.addEventListener('visibilitychange', onVisible); schedule();
    return () => { active = false; if (wakeAvatar.current === wake) wakeAvatar.current = null; if (timer) clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible); };
  }, [avatarPicking]);
  useEffect(() => { wakeAvatar.current?.(); }, [presentationKey]);
  const go = (next: View) => {
    positions.current.set(current.current.view, scroller.current?.scrollTop || 0);
    setView(next); setError(''); setNotice('');
  };
  useLayoutEffect(() => {
    if (previousView.current === view) return;
    previousView.current = view;
    const node = scroller.current;
    if (node) node.scrollTop = positions.current.get(view) || 0;
    const returnTarget = (view === 'memories' || view === 'memory-history') && returnMemory.current ? Array.from(node?.querySelectorAll<HTMLElement>('[data-personal-memory]') || []).find(item => item.dataset.personalMemory === returnMemory.current) : null;
    (returnTarget || heading.current)?.focus({ preventScroll: true });
  }, [view]);

  const beforeLeave: LeaveGuard = next => {
    if (busy.current) { setNotice('正在保存或读取，请稍候。'); return true; }
    closeAvatar();
    if (current.current.review) { clearReview(); current.current = { ...current.current, review: null }; }
    if (profileDirty(current.current.editor) || memoryDirty(current.current.selection) || current.current.newMemoryText.trim() || current.current.newMemoryExpiry !== 'none') { pendingLeave.current = next; setLeaveOpen(true); return true; }
    return false;
  };
  const guarded = (next: () => void) => { if (!beforeLeave(next)) next(); };
  const leaveToParent = () => {
    if (current.current.view === 'memory') { clearSelection(); go(memoryParent.current); }
    else if (['memory-new', 'memory-settings', 'memory-history'].includes(current.current.view)) { setNewMemoryText(''); setNewMemoryExpiry('none'); setCreateUnknown(false); go('memories'); }
    else if (current.current.view !== 'home') { setEditor(null); go('home'); }
    else onBack();
  };
  const back = () => {
    if (busy.current) return true;
    if (current.current.leaveOpen) { pendingLeave.current = null; setLeaveOpen(false); return true; }
    if (current.current.review) { clearReview(); return true; }
    if (beforeLeave(leaveToParent)) return true;
    if (current.current.view === 'home') return false;
    leaveToParent(); return true;
  };
  const backRef = useRef(back), leaveRef = useRef(beforeLeave);
  backRef.current = back; leaveRef.current = beforeLeave;
  useEffect(() => { registerBack(() => backRef.current()); registerBeforeLeave(next => leaveRef.current(next)); return () => { registerBack(null); registerBeforeLeave(null); }; }, [registerBack, registerBeforeLeave]);
  const requestBack = () => { if (!backRef.current()) onBack(); };
  const openEditor = (section: Section) => {
    if (!profile || !loaded || busy.current) return;
    guarded(() => { editorInstance.current++; clearSelection(); setEditor({ section, original: profile, draft: { ...profile } }); go(section); });
  };
  const change = (key: keyof PersonalProfile, value: string | boolean) => {
    if (busy.current) return;
    setEditor(old => old ? { ...old, draft: { ...old.draft, [key]: value } } : old); setNotice(''); setError('');
  };
  const saveProfile = () => {
    const frozen = current.current.editor;
    if (!frozen || !profileDirty(frozen) || !canSave || profileConflict || avatarPicker.current) return;
    const values = sectionValues(frozen.draft, frozen.section);
    void action('正在保存', async () => {
      const result = object(await call('profile.save', { expectedRevision: frozen.original.revision, section: frozen.section, values }));
      if (!alive.current) return;
      const saved = profileOf(result.profile);
      if (result.saved !== true || result.persisted !== true || !saved || saved.revision !== frozen.original.revision + 1) {
        if (saved) acceptProfile(saved);
        throw new Error(string(result.message || result.error) || '保存尚未确认，当前输入已保留。请读取最新资料后核对。');
      }
      setProfile(saved); setEditor({ section: frozen.section, original: saved, draft: { ...saved } }); setNotice(frozen.section === 'identity' ? '已保存个人资料' : '已保存，后续私人对话会参考这些偏好');
      try { await readSnapshot(); } catch { if (alive.current) setError('资料已保存，最新设置暂未完整读取，请刷新后继续。'); }
    });
  };
  const saveAgentPreferenceControl = (enabled: boolean) => {
    const original = agentPreferences;
    if (!original || original.enabled === enabled || busy.current) return;
    void action('正在保存偏好权限', async () => {
      try {
        const result = object(await call('profile.preferences.control', { expectedRevision: original.revision, enabled }));
        const saved = profileOf(result.profile), control = agentPreferenceControlOf(result.agentPreferences);
        if (result.saved !== true || result.persisted !== true || !saved || saved.revision !== original.revision + 1 || !control || control.revision !== saved.revision || control.enabled !== enabled) throw new Error('偏好权限的保存结果尚未确认。');
        if (!alive.current) return;
        acceptProfile(saved); setAgentPreferences(control);
        setNotice(enabled ? '已允许通过聊天保存明确提出的默认偏好' : '已关闭通过聊天保存默认偏好');
      } catch (failure) {
        if (alive.current) setAgentPreferences(null);
        let refreshed = false;
        try { await readSnapshot(); refreshed = true; } catch { /* Keep the unavailable control disabled until a fresh snapshot succeeds. */ }
        throw new Error((failure instanceof Error ? failure.message : '偏好权限保存结果待核对。') + (refreshed ? ' 已重新读取当前设置，没有重复保存。' : ' 请重新读取设置后继续，没有重复保存。'));
      }
    });
  };
  const pickAvatar = () => {
    if (busy.current || avatarPicker.current || current.current.editor?.section !== 'identity') return;
    const owner = editorInstance.current;
    void action('正在打开相册', async () => {
      const result = object(await call('profile.avatar.pick'));
      const id = string(result.pickerId);
      if (result.pending !== true || !id) throw new Error('相册尚未打开，请重新选择。');
      if (!alive.current || current.current.editor?.section !== 'identity' || owner !== editorInstance.current) { void runRef.current('profile.avatar.close', { pickerId: id }).catch(() => {}); return; }
      avatarPicker.current = { id, editor: owner }; setAvatarPicking(true);
    });
  };

  const readMemory = async (id: string, candidate: boolean) => {
    const value = object(await call('profile.memory.get', { id, candidate }));
    const row = object(candidate ? value.candidate : value.record);
    if (row.id !== id || !string(value.readHandle) || (candidate && value.candidateSelected !== true)) {
      closeHandle(string(value.readHandle));
      throw new Error('这条记忆已经变化，请返回列表后重新打开。');
    }
    return { value, row, handle: string(value.readHandle) };
  };
  const openMemory = (row: Data, candidate: boolean) => {
    if (!string(row.id)) return;
    void action('正在读取记忆', async () => {
      const result = await readMemory(row.id, candidate);
      if (!alive.current) { closeHandle(result.handle); return; }
      clearSelection(); readHandle.current = result.handle; returnMemory.current = row.id;
      memoryParent.current = current.current.view === 'home' ? 'home' : current.current.view === 'memory-history' ? 'memory-history' : 'memories';
      const historical = !candidate && (historicalMemory(result.row) || memories.history.some(item => item.id === row.id) && !memories.current.some(item => item.id === row.id));
      setSelection({ id: row.id, candidate, value: result.value, record: result.row, originalText: string(result.row.text), draft: string(result.row.text), historical });
      setEditor(null); go('memory');
    });
  };
  const reloadMemory = () => {
    const frozen = current.current.selection;
    if (!frozen) return;
    void action('正在核对最新记忆', async () => {
      const snapshot = await readSnapshot();
      if (!alive.current) return;
      if (string(snapshot.memoryError) || object(snapshot.memories).available === false) throw new Error(string(snapshot.memoryError) || '个人记忆暂时无法读取，请重试。');
      const latestRows = frozen.candidate ? rows(object(snapshot.memories).candidates) : [...rows(object(snapshot.memories).current), ...rows(object(snapshot.memories).history)];
      if (!latestRows.some(row => row.id === frozen.id) && !memoryDirty(frozen)) {
        clearSelection(); go('memories'); setNotice('这条内容已不在当前个人记忆列表中。'); return;
      }
      const result = await readMemory(frozen.id, frozen.candidate);
      if (!alive.current) { closeHandle(result.handle); return; }
      closeHandle(readHandle.current); readHandle.current = result.handle;
      const nextText = string(result.row.text);
      if (memoryDirty(frozen) && nextText !== frozen.originalText && nextText !== frozen.draft.trim()) {
        setLatestMemory({ value: result.value, record: result.row }); setMemoryConflict(true); setMemoryUnknown(false);
        setNotice('已读到最新内容。你的输入仍保留，请选择如何继续。');
      } else {
        const draft = nextText === frozen.draft.trim() || !memoryDirty(frozen) ? nextText : frozen.draft;
        setSelection({ ...frozen, value: result.value, record: result.row, originalText: nextText, draft, historical: !frozen.candidate && historicalMemory(result.row) });
        setMemoryConflict(false); setMemoryUnknown(false); setLatestMemory(null);
        setNotice(nextText === frozen.draft.trim() ? '当前内容已核对' : '已刷新，未保存的输入已保留');
      }
    });
  };
  const openMemorySettings = () => guarded(() => {
    clearSelection(); setEditor(null); go('memory-settings');
    void action('正在读取记忆设置', async () => {
      try { await readControls(); }
      catch (failure) { if (alive.current) setControlsError(failure instanceof Error ? failure.message : '记忆设置暂不可用。'); throw failure; }
    });
  });
  const saveControl = (kind: 'reading' | 'learning', enabled: boolean) => {
    const original = policy;
    if (!original || original[kind].enabled === enabled || busy.current) return;
    void action('正在保存记忆设置', async () => {
      try {
        const args = kind === 'reading' ? { expectedRevision: original.reading.revision, enabled }
          : { expectedRevision: original.learning.revision, enabled, maxUserSourcesPerTask: original.learning.maxUserSourcesPerTask, confirmedModelUse: true };
        const result = object(await call(`profile.memory.${kind}`, args));
        const controls = policyOf(result.controls);
        if (result.saved !== true || result.persisted !== true || !controls || controls[kind].enabled !== enabled || controls[kind].revision !== original[kind].revision + 1) throw new Error('设置保存回执尚未确认。');
        if (!alive.current) return;
        setPolicy(controls); setControlsError('');
        setNotice(kind === 'reading' ? enabled ? '已开启，后续私人回答会按相关性参考个人记忆' : '已暂停参考个人记忆，保存的内容仍可查看和编辑' : enabled ? '已开启，后续对话可整理出待确认的个人记忆' : '已暂停从对话整理新候选');
      } catch (failure) {
        try { await readControls(); } catch { if (alive.current) setControlsError('请重新读取记忆设置后继续。'); }
        throw new Error((failure instanceof Error ? failure.message : '设置保存结果待核对。') + ' 已尝试核对当前状态，没有重复保存。');
      }
    });
  };
  const openNewMemory = () => guarded(() => {
    clearSelection(); setEditor(null); setNewMemoryText(''); setNewMemoryExpiry('none'); setCreateUnknown(false); setFeedback(null); go('memory-new');
  });
  const saveNewMemory = () => {
    const text = current.current.newMemoryText.trim(), expiry = current.current.newMemoryExpiry;
    if (!text || current.current.createUnknown || busy.current || settingsRequiredFor(current.current.feedback, 'create', undefined, text)) return;
    void action('正在保存记忆', async () => {
      let sent = false, saved = false, duplicate = false;
      try {
        const expiresAt = expiry === 'none' ? null : Date.now() + Number(expiry) * 86_400_000;
        const prepared = object(await call('profile.memory.createReview', { text, expiresAt }));
        if (!string(prepared.reviewHandle) || !string(prepared.receiptHandle) || prepared.operation !== 'create' || prepared.afterText !== text) { closeHandle(string(prepared.reviewHandle)); throw new Error('待保存内容尚未完整核对，输入已保留。'); }
        if (!alive.current) { closeHandle(prepared.reviewHandle); return; }
        rememberReceipt({ receiptHandle: prepared.receiptHandle, undoAvailable: false }, 'create', true, undefined, text);
        sent = true;
        const result = object(await call('profile.memory.confirm', { reviewHandle: prepared.reviewHandle }));
        if (settlePreferenceReceipt(result, prepared.receiptHandle, 'create', undefined, text)) return;
        if (result.duplicate === true && result.status === 'already_saved') {
          if (result.receiptHandle !== prepared.receiptHandle || !string((result.record || result.candidate)?.id)) throw new Error('相同内容的原回执尚未完整核对。');
          saved = true; duplicate = true;
          if (!alive.current) return;
          rememberReceipt({ ...result, receiptHandle: result.receiptHandle || prepared.receiptHandle, undoAvailable: false }, 'create');
          setNewMemoryText(''); setNewMemoryExpiry('none'); setCreateUnknown(false); go('memories');
          await readSnapshot(); return;
        }
        if (result.status !== 'saved' || result.receiptHandle !== prepared.receiptHandle || !string(result.record?.id) || result.record?.text !== text || !Number.isSafeInteger(result.record?.revision) || result.record.revision < 1) throw new Error('记忆保存结果尚未确认。');
        saved = true;
        if (!alive.current) return;
        rememberReceipt(result, 'create');
        setNewMemoryText(''); setNewMemoryExpiry('none'); setCreateUnknown(false); go('memories');
        await readSnapshot();
      } catch (failure) {
        if (alive.current && sent && !saved) setCreateUnknown(true);
        throw new Error(saved ? duplicate ? '已有相同内容，列表暂未完整刷新。' : '记忆已保存，列表暂未完整刷新。' : (failure instanceof Error ? failure.message : '保存暂未完成。') + (sent ? ' 请查询这次保存的结果。' : ''));
      }
    });
  };
  const checkedReview = async (operation: string, frozen: MemorySelection, extra: Record<string, unknown> = {}) => {
    const value = object(await call('profile.memory.review', { readHandle: readHandle.current, operation, ...extra }));
    const handle = string(value.reviewHandle);
    if (!handle || !string(value.receiptHandle) || value.before?.id !== frozen.id || value.before?.revision !== frozen.record.revision || value.operation !== operation) {
      closeHandle(handle); throw new Error('记忆修改范围已变化，请重新读取后继续。');
    }
    return { value, handle };
  };
  const saveMemory = () => {
    const frozen = current.current.selection;
    if (!frozen || frozen.candidate || frozen.historical || !memoryDirty(frozen) || !frozen.draft.trim() || memoryConflict || memoryUnknown || !readHandle.current || settingsRequiredFor(current.current.feedback, 'correct', frozen.id, frozen.draft.trim())) return;
    const text = frozen.draft.trim();
    void action('正在保存记忆', async () => {
      let sent = false, saved = false;
      try {
        const prepared = await checkedReview('correct', frozen, { text });
        if (typeof prepared.value.afterText === 'string' && prepared.value.afterText !== text) { closeHandle(prepared.handle); throw new Error('待保存内容与当前输入不同，请重新读取这条记忆。'); }
        if (!alive.current) { closeHandle(prepared.handle); return; }
        rememberReceipt({ receiptHandle: prepared.value.receiptHandle, undoAvailable: false }, 'correct', true, frozen.id, text);
        sent = true;
        const receipt = object(await call('profile.memory.confirm', { reviewHandle: prepared.handle }));
        if (settlePreferenceReceipt(receipt, prepared.value.receiptHandle, 'correct', frozen.id, text)) return;
        if (receipt.status !== 'saved' || receipt.receiptHandle !== prepared.value.receiptHandle || receipt.record?.id !== frozen.id || receipt.record?.text !== text || !(receipt.record?.revision > frozen.record.revision)) throw new Error('记忆保存回执尚未确认。');
        saved = true;
        if (!alive.current) return;
        rememberReceipt(receipt, 'correct', false, frozen.id);
        setSelection({ ...frozen, record: receipt.record, originalText: text, draft: text });
        const latest = await readMemory(frozen.id, frozen.candidate);
        if (!alive.current) { closeHandle(latest.handle); return; }
        closeHandle(readHandle.current); readHandle.current = latest.handle;
        if (string(latest.row.text) !== text) { setLatestMemory({ value: latest.value, record: latest.row }); setMemoryConflict(true); throw new Error('最新记忆与本次输入不同，输入已保留，请核对。'); }
        setSelection({ ...frozen, value: latest.value, record: latest.row, originalText: text, draft: text });
        setMemoryConflict(false); setMemoryUnknown(false); setLatestMemory(null);
        await readSnapshot();
      } catch (failure) {
        if (alive.current) { if (sent && !saved) setMemoryUnknown(true); else setMemoryConflict(true); }
        throw new Error(saved ? '记忆已保存，最新状态暂未完整读取。请刷新后继续。' : (failure instanceof Error ? failure.message : '记忆保存未完成。') + (sent ? ' 请核对最新内容后继续。' : ' 当前输入已保留。'));
      }
    });
  };
  const prepareReview = (operation: MemoryReview['operation']) => {
    const frozen = current.current.selection;
    if (!frozen || memoryConflict || memoryUnknown || !readHandle.current || (operation !== 'forget' && memoryDirty(frozen))) return;
    if (operation === 'accept' && settingsRequiredFor(current.current.feedback, operation, frozen.id)) return;
    void action('正在读取修改范围', async () => {
      const prepared = await checkedReview(operation, frozen, operation === 'reject' ? { reason: '用户在个人中心选择忽略这条候选。' } : {});
      if (!alive.current) { closeHandle(prepared.handle); return; }
      const preparedReview = { value: prepared.value, operation, id: frozen.id, candidate: frozen.candidate };
      if (operation === 'forget') {
        clearReview(); reviewHandle.current = prepared.handle; setReview(preparedReview);
      } else await applyMemoryReview(preparedReview, prepared.handle);
    });
  };
  const applyMemoryReview = async (frozen: MemoryReview, handle: string) => {
      reviewHandle.current = ''; setReview(null);
      rememberReceipt({ receiptHandle: frozen.value.receiptHandle, undoAvailable: false }, frozen.operation, true, frozen.id);
      let completed = false;
      try {
        const receipt = object(await call('profile.memory.confirm', { reviewHandle: handle }));
        if (settlePreferenceReceipt(receipt, frozen.value.receiptHandle, frozen.operation, frozen.id)) return;
        if (frozen.operation === 'accept' && receipt.duplicate === true && receipt.status === 'already_saved') {
          if (receipt.receiptHandle !== frozen.value.receiptHandle || !string((receipt.record || receipt.candidate)?.id)) throw new Error('相同内容的原回执尚未完整核对。');
          completed = true;
          if (!alive.current) return;
          rememberReceipt({ ...receipt, receiptHandle: receipt.receiptHandle || frozen.value.receiptHandle, undoAvailable: false }, 'accept', false, frozen.id);
          clearSelection(); setShowCandidates(false); go('memories');
          await readSnapshot(); return;
        }
        const valid = frozen.operation === 'forget' ? receipt.deleted >= 1 : receipt.candidate?.id === frozen.id && receipt.candidate?.status === (frozen.operation === 'accept' ? 'accepted' : 'rejected') && receipt.candidate?.revision > frozen.value.before.revision;
        if (!valid || receipt.status !== 'saved' || receipt.receiptHandle !== frozen.value.receiptHandle || (frozen.operation === 'accept' && !string(receipt.record?.id))) throw new Error('记忆处理回执尚未确认。');
        completed = true;
        if (!alive.current) return;
        rememberReceipt(receipt, frozen.operation, false, frozen.id);
        setMemories(old => ({ ...old, current: frozen.operation === 'forget' ? old.current.filter(row => row.id !== frozen.id) : frozen.operation === 'accept' && receipt.record?.id ? [...old.current.filter(row => row.id !== receipt.record.id), receipt.record] : old.current, candidates: old.candidates.filter(row => row.id !== frozen.id), pendingConflicts: old.pendingConflicts.filter(row => row.id !== frozen.id) }));
        clearSelection(); setShowCandidates(false); go('memories');
        await readSnapshot();
      } catch (failure) {
        if (alive.current && !completed) setMemoryUnknown(true);
        throw new Error(completed ? '操作已完成，列表暂未完整刷新，请重新读取记忆。' : (failure instanceof Error ? failure.message : '处理结果尚未确认。') + ' 请核对最新记忆，不要重复提交。');
      }
  };
  const confirmReview = () => {
    const frozen = current.current.review, handle = reviewHandle.current;
    if (!frozen || !handle) return;
    void action('正在更新记忆', () => applyMemoryReview(frozen, handle));
  };
  const adoptLatestMemory = (keepDraft: boolean) => {
    if (!selection || !latestMemory) return;
    const record = object(latestMemory.record);
    setSelection({ ...selection, value: object(latestMemory.value), record, originalText: string(record.text), draft: keepDraft ? selection.draft : string(record.text), historical: historicalMemory(record) });
    setLatestMemory(null); setMemoryConflict(false); setMemoryUnknown(false); setError(''); setNotice(keepDraft ? '输入已保留，请核对后保存' : '已使用最新内容');
  };
  const readSource = (index: number, offset = 0) => {
    if (!readHandle.current) return;
    void action('正在读取来源', async () => {
      const value = object(await call('profile.memory.source', { readHandle: readHandle.current, index, offset }));
      if (alive.current) setSource(old => offset > 0 && old?.index === index ? { ...value, index, text: string(old.text) + string(value.text) } : { ...value, index });
    });
  };
  const readReceipt = (undo = false) => {
    const frozen = current.current.feedback;
    if (!frozen || !string(frozen.value.receiptHandle) || (undo && (frozen.needsQuery || frozen.value.status !== 'saved' || frozen.value.undoAvailable !== true || !['create', 'accept'].includes(frozen.operation)))) return;
    void action(undo ? '正在撤销这次保存' : '正在核对这次修改', async () => {
      if (undo) setFeedback({ ...frozen, needsQuery: true });
      let settled = false;
      try {
        let result = object(await call(undo ? 'profile.memory.undo' : 'profile.memory.query', { receiptHandle: frozen.value.receiptHandle }));
        if (result.receiptHandle === frozen.value.receiptHandle && result.status === 'not_submitted') {
          const closed = object(await call('profile.memory.close', { handle: frozen.value.receiptHandle }));
          if (closed.closed !== true) throw new Error('尚未提交的审阅未确认关闭，请继续核对原操作。');
          // Re-read after retiring the original review so a delayed confirm cannot
          // arrive after the editor has been unlocked for a new explicit save.
          result = object(await call('profile.memory.query', { receiptHandle: frozen.value.receiptHandle }));
        }
        if (result.receiptHandle !== frozen.value.receiptHandle || !['not_submitted', 'settings_required', 'saved', 'already_saved', 'reverted', 'unknown', 'conflict'].includes(result.status) || result.status === 'settings_required' && !preferenceSectionOf(result)) throw new Error('这次修改的回执尚未完整读取。');
        if (!alive.current) return;
        settled = true;
        setFeedback({ ...frozen, value: result, needsQuery: false });
        if (result.status === 'settings_required') {
          if (frozen.operation === 'create') setCreateUnknown(false);
          if (current.current.selection?.id === frozen.targetId) setMemoryUnknown(false);
          return;
        }
        if (result.status === 'not_submitted') {
          if (frozen.operation === 'create') setCreateUnknown(false);
          if (current.current.selection?.id === frozen.targetId) setMemoryUnknown(false);
          setNotice('这次修改尚未提交，输入已保留。你可以继续处理，没有自动重新保存。');
          return;
        }
        if (result.status === 'saved' || result.status === 'reverted' || result.status === 'already_saved') {
          if (current.current.view === 'memory-new' && current.current.createUnknown && frozen.operation === 'create') {
            setNewMemoryText(''); setNewMemoryExpiry('none'); setCreateUnknown(false); go('memories');
          }
          const selected = current.current.selection;
          if (selected && selected.id === frozen.targetId) {
            if (frozen.operation === 'correct') { setMemoryUnknown(false); setMemoryConflict(true); setNotice('结果已核对。请读取最新记忆，当前输入仍保留。'); }
            else if (!memoryDirty(selected)) { clearSelection(); go('memories'); }
          }
          if (result.status === 'reverted' && selected?.id === frozen.value.record?.id && !memoryDirty(selected)) { clearSelection(); go('memories'); }
          await readSnapshot();
        }
      } catch (failure) {
        if (alive.current && undo && !settled) setFeedback({ ...frozen, needsQuery: true });
        throw new Error(settled ? '操作结果已核对，列表暂未完整读取，请刷新记忆。' : (failure instanceof Error ? failure.message : '结果暂未读取。') + ' 请查询原操作，不要重复提交。');
      }
    });
  };
  const targetKey = initialTarget ? `${targetRequestKey || ''}:${initialTarget.view}:${initialTarget.view === 'memory' ? `${initialTarget.id}:${initialTarget.candidate}` : ''}` : '';
  useEffect(() => {
    if (!initialTarget || !targetKey || targetKey === handledTarget.current || !loaded || !enteredProfile || busy.current) return;
    handledTarget.current = targetKey;
    if (initialTarget.view === 'memory-settings') { openMemorySettings(); return; }
    const target = initialTarget;
    guarded(() => {
      if (target.view === 'memory') openMemory({ id: target.id }, target.candidate);
      else { clearSelection(); setEditor(null); go(target.view); }
    });
  }, [targetKey, loaded, enteredProfile, pending]);
  const currentSection = editor?.draft;
  const memorySources = rows(selection?.record.sourceRefs);
  const relatedRecords = rows(selection?.value.relatedRecords);
  const feedbackVisible = !!feedback && (['memories', 'memory', 'memory-history'].includes(view) || view === 'memory-new' && (createUnknown || feedback.value.status === 'settings_required'));
  const preferenceSection = feedback && !feedback.needsQuery ? preferenceSectionOf(feedback.value) : null;
  const headingLabel = view === 'memory' && selection?.candidate ? '确认记忆' : view === 'memory' && selection?.historical ? '记忆详情' : labels[view];
  const headerSave = editor ? <Button className="pw-profile-save" size="small" disabled={locked || avatarPicking || !profileDirty(editor) || profileConflict || !canSave} onClick={saveProfile}>{pending === '正在保存' ? '保存中' : '保存'}</Button>
    : view === 'memory-new' ? <Button className="pw-profile-save" size="small" disabled={locked || createUnknown || !newMemoryText.trim() || settingsRequiredFor(feedback, 'create', undefined, newMemoryText.trim()) || !supported('profile.memory.createReview') || !supported('profile.memory.confirm')} onClick={saveNewMemory}>{pending === '正在保存记忆' ? '保存中' : '保存'}</Button>
      : view === 'memory' && selection && !selection.candidate && !selection.historical ? <Button className="pw-profile-save" size="small" disabled={locked || !memoryDirty(selection) || !selection.draft.trim() || memoryConflict || memoryUnknown || settingsRequiredFor(feedback, 'correct', selection.id, selection.draft.trim()) || !supported('profile.memory.confirm')} onClick={saveMemory}>{pending === '正在保存记忆' ? '保存中' : '保存'}</Button>
        : view === 'memories' || view === 'memory-history' ? <IconButton icon={IconSettings} aria-label="记忆设置" title="记忆设置" disabled={locked} onClick={openMemorySettings} />
          : view === 'home' ? <IconButton icon={IconSettings} aria-label="隐私与数据设置" title="隐私与数据" onClick={() => guarded(onSettings)} /> : <span />;
  const entry = (id: string, title: string, description: string, icon: typeof IconChat, open: () => void, badge?: ReactNode) => <Button variant="ghost" className="pw-profile-entry" leadingIcon={icon} trailingIcon={IconChevronRight} onClick={open} disabled={!loaded || locked} data-profile-entry={id}><span className="pw-profile-entry-copy"><strong>{title}</strong><small>{description}</small></span>{badge}</Button>;
  const memoryRow = (row: Data, candidate: boolean) => <Button key={(candidate ? 'candidate:' : 'memory:') + row.id} variant="ghost" className="pw-profile-memory-row" trailingIcon={IconChevronRight} data-personal-memory={string(row.id)} data-motion-key={(candidate ? 'candidate:' : 'memory:') + row.id} disabled={locked || !supported('profile.memory.get')} onClick={() => openMemory(row, candidate)}><span><strong>{string(row.text || row.title) || '未命名记忆'}</strong><small>{[memoryOrigin(row, candidate), memoryDates(row)].filter(Boolean).join(' · ')}</small></span></Button>;
  const followUp = object(duties?.policy), quietHours = object(followUp.quietHours);
  const followUpLabel = dutiesReading ? '正在核对跟进设置' : dutiesError ? '跟进设置待重新核对' : !duties ? '查看委托与跟进设置' : [followUp.enabled !== true ? '自动跟进已关闭' : Number(followUp.pausedUntil) > Date.now() ? '自动跟进已暂停' : '自动跟进已开启', quietHours.start && quietHours.end ? `安静时段 ${quietHours.start}–${quietHours.end}` : ''].filter(Boolean).join(' · ');
  const reminderData = object(state.features?.automation), reminderReady = Array.isArray(reminderData.rules);
  const reminderBusy = (state.moduleBusy || []).some((name: string) => ['automation.list', 'automation.receipts'].includes(name));
  const reminderError = string(state.moduleErrors?.['automation.list']) || string(state.moduleErrors?.['automation.receipts']);
  const reminderLabel = dutiesReading || reminderBusy ? '正在核对已保存提醒' : dutiesError || reminderError ? '提醒状态待重新核对' : !reminderReady ? '查看已保存的定时提醒' : rows(reminderData.rules).length ? `${rows(reminderData.rules).filter(rule => rule.enabled === true).length} 条已开启 · 查看范围与发生记录` : '还没有定时提醒';
  const recentReceipt = !dutiesReading && !dutiesError && !reminderBusy && !reminderError ? rows(state.moduleResults?.['automation.receipts']?.receipts).slice().reverse()[0] : undefined;
  const receiptLabel = recentReceipt ? ({ delivery_unknown: '投递待核对', awaiting_user: '等待你查看', blocked_notifications: '通知受阻', notified: '已通知', delivered: '已投递', cancelled: '已取消', failed: '未完成' }[string(recentReceipt.status)] || '查看原发生记录') : '';

  return <section className="pw-profile-workspace pw-route-workspace" data-profile-view={view} aria-label={headingLabel}>
    <header className="pw-profile-header"><IconButton icon={IconArrowLeft} aria-label={view === 'home' ? '返回' : '返回上一级'} onClick={requestBack} /><div className="pw-profile-header-copy"><h1 ref={heading} tabIndex={-1}>{headingLabel}</h1><WorkingStatus active={locked && loaded || avatarPicking && !locked}>{locked ? `${pending}…` : '正在等候所选头像…'}</WorkingStatus></div>{headerSave}</header>
    <div className="pw-profile-scroll" ref={scroller}>
      <div className="pw-profile-content">
        {!canEnter && <div className="pw-profile-empty"><IconAccount aria-hidden="true" /><h2>个人资料暂不可用</h2><p>请更新应用后重新打开。</p></div>}
        {canEnter && !loaded && <div className="pw-profile-loading" role="status">{locked || !enteredProfile || !canRead ? '正在打开个人资料…' : '个人资料尚未读取。'}{!locked && <Button variant="ghost" leadingIcon={IconRefresh} onClick={refresh}>重新读取</Button>}</div>}
        {error && <MotionFeedback identity={error} className="pw-profile-feedback is-error" role="alert"><p>{error}</p>{!locked && <Button variant="ghost" size="small" leadingIcon={IconRefresh} onClick={view === 'memory' ? reloadMemory : view === 'memory-settings' ? openMemorySettings : refresh}>读取最新{view === 'memory' ? '记忆' : view === 'memory-settings' ? '设置' : '资料'}</Button>}</MotionFeedback>}
        {notice && <MotionFeedback identity={notice} className="pw-profile-feedback" role="status">{['已保存', '记忆已删除', '已记住', '已忽略', '当前内容已核对'].includes(notice) && <IconCheck size={15} aria-hidden="true" />}{notice}</MotionFeedback>}
        {feedbackVisible && feedback && <MotionFeedback identity={JSON.stringify([feedback.operation, feedback.targetId, feedback.value.status, feedback.needsQuery, feedback.value.undoAvailable])} className="pw-profile-feedback pw-profile-memory-feedback" role="status">
          <div className="pw-profile-receipt-heading">{!feedback.needsQuery && ['saved', 'reverted'].includes(feedback.value.status) && <IconCheck size={16} aria-hidden="true" />}<span>{feedbackLabel(feedback)}</span></div>
          {feedback.value.undoUnavailableReason && <p>{string(feedback.value.undoUnavailableReason)}</p>}
          <div className="pw-profile-actions">
            {preferenceSection && <Button variant="secondary" size="small" trailingIcon={IconChevronRight} disabled={locked} onClick={() => openEditor(preferenceSection)}>去{preferenceSection === 'communication' ? '交流偏好' : '做事偏好'}</Button>}
            {!feedback.needsQuery && ['saved', 'already_saved', 'conflict'].includes(feedback.value.status) && string((feedback.value.record || feedback.value.candidate)?.id) && feedback.operation !== 'forget' && <Button variant="ghost" size="small" disabled={locked} onClick={() => guarded(() => openMemory(feedback.value.record || feedback.value.candidate, !feedback.value.record))}>查看{feedback.value.status === 'conflict' ? '最新记忆' : feedback.value.candidate && !feedback.value.record ? '待确认内容' : ''}</Button>}
            {!feedback.needsQuery && feedback.value.status === 'saved' && feedback.value.undoAvailable === true && ['create', 'accept'].includes(feedback.operation) && supported('profile.memory.undo') && <Button variant="ghost" size="small" leadingIcon={IconUndo} disabled={locked} onClick={() => guarded(() => readReceipt(true))}>撤销这次保存</Button>}
            {(feedback.needsQuery || ['unknown', 'conflict'].includes(feedback.value.status)) && <Button variant="secondary" size="small" leadingIcon={IconRefresh} disabled={locked || !supported('profile.memory.query')} onClick={() => readReceipt()}>查询这次修改</Button>}
          </div>
        </MotionFeedback>}
        {loaded && profile && view === 'home' && <>
          <section className="pw-profile-identity" aria-label="个人资料"><Avatar profile={profile} large /><div><h2>{profile.nickname || '我的资料'}</h2>{profile.addressAs && profile.addressAs !== profile.nickname && <p>称呼我为 {profile.addressAs}</p>}<Button variant="ghost" size="small" className="pw-profile-edit-link" onClick={() => openEditor('identity')}>编辑资料<IconChevronRight size={15} aria-hidden="true" /></Button></div></section>
          <p className="pw-profile-scope">这里的偏好用于私人管家的交流与做事方式；每次工作的明确要求和已确认范围优先。</p>
          <div className="pw-profile-menu">
            {entry('communication', '交流偏好', [selectedLabel(languageOptions, profile.language), selectedLabel(detailOptions, profile.response_detail)].filter(Boolean).join(' · '), IconChat, () => openEditor('communication'))}
            {entry('behavior', '做事偏好', [selectedLabel(paceOptions, profile.operation_pace), selectedLabel(initiativeOptions, profile.initiative)].filter(Boolean).join(' · '), IconControls, () => openEditor('behavior'))}
            {entry('memories', '个人记忆', pendingCandidates.length ? `${pendingCandidates.length} 条待确认 · 查看和修正` : 'AI 对我的了解', IconSparkles, () => { setEditor(null); go('memories'); }, pendingCandidates.length > 0 ? <span className="pw-profile-unread" aria-label={`${pendingCandidates.length} 条待确认`} /> : undefined)}
          </div>
          {memoryAvailable && activeMemories.length > 0 && <section className="pw-profile-home-memories" aria-label="个人记忆预览">{activeMemories.slice(0, 2).map(row => <Button key={row.id} data-motion-key={`memory:${row.id}`} variant="ghost" className="pw-profile-home-memory" trailingIcon={IconChevronRight} disabled={locked || !supported('profile.memory.get')} onClick={() => openMemory(row, false)}><span><strong>{string(row.text || row.title) || '未命名记忆'}</strong><small>{memoryOrigin(row)}</small></span></Button>)}</section>}
          {(onOpenCommitments || onOpenReminders) && <section className="pw-profile-duties" aria-label="管家的持续职责">
            <header><h2>管家的持续职责</h2><IconButton icon={IconRefresh} aria-label="核对职责状态" disabled={locked || dutiesReading || reminderBusy} onClick={() => setDutiesAttempt(value => value + 1)} /></header>
            <div className="pw-profile-menu">
              {onOpenCommitments && entry('commitments', '委托与目标', followUpLabel, IconGoal, () => guarded(onOpenCommitments))}
              {onOpenReminders && entry('reminders', '定时提醒', reminderLabel, IconCalendarCheck, () => guarded(onOpenReminders))}
            </div>
            {(dutiesError || reminderError) && <p className="pw-error" role="alert">{dutiesError || reminderError}</p>}
            {recentReceipt && onOpenReminders && <Button variant="ghost" className="pw-profile-home-memory" trailingIcon={IconChevronRight} disabled={locked} onClick={() => guarded(onOpenReminders)}><span><strong>{string(recentReceipt.title) || '最近一次提醒'}</strong><small>{receiptLabel}{dateLabel(recentReceipt.occurrenceAt) ? ` · ${dateLabel(recentReceipt.occurrenceAt)}` : ''}</small></span></Button>}
          </section>}
          <Button variant="ghost" className="pw-profile-data-entry" leadingIcon={IconShieldCheck} trailingIcon={IconChevronRight} disabled={locked} onClick={() => guarded(onSettings)}>隐私与数据</Button>
        </>}
        {editor && currentSection && <>
          {profileConflict && <section className="pw-profile-conflict" role="alert"><h2>这组偏好已有更新</h2><p>你的输入已保留。核对最新内容后，再选择如何继续。</p><div className="pw-profile-latest">{editor.section === 'identity' ? <><Avatar profile={profile} /><p>昵称：{profile?.nickname || '未填写'}</p><p>称呼：{profile?.addressAs || '未填写'}</p><p>{profile?.about || '未填写个人介绍'}</p></> : editor.section === 'communication' ? <><p>{selectedLabel(languageOptions, profile?.language || '')} · {selectedLabel(detailOptions, profile?.response_detail || '')}</p><p>{profile?.conclusionFirst ? '先给结论' : '自然组织回答'} · {profile?.includeExamples ? '需要时结合例子' : '不特别要求例子'}</p>{profile?.instructions && <p>{profile.instructions}</p>}</> : <p>{selectedLabel(paceOptions, profile?.operation_pace || '')} · {selectedLabel(initiativeOptions, profile?.initiative || '')}</p>}</div><div className="pw-profile-actions"><Button variant="secondary" size="small" disabled={locked} onClick={() => { if (profile) { setEditor(old => old ? { ...old, original: profile, draft: retainLocalChanges(old, profile) } : old); setError(''); } }}>保留我的输入</Button><Button variant="ghost" size="small" disabled={locked} onClick={() => { if (profile) { setEditor(old => old ? { ...old, original: profile, draft: { ...profile } } : old); setError(''); } }}>使用最新内容</Button></div></section>}
          {view === 'identity' && <div className="pw-profile-form">
            <div className="pw-profile-avatar-editor"><Avatar profile={currentSection} large /><div><Button variant="secondary" size="small" disabled={formLocked || !supported('profile.avatar.pick')} onClick={pickAvatar}>更换头像</Button>{currentSection.avatarDataUrl && <Button variant="ghost" size="small" disabled={formLocked} onClick={() => change('avatarDataUrl', '')}>移除</Button>}</div></div>
            <TextField className="pw-profile-field" fieldClassName="pw-profile-input" label="昵称" placeholder="你的昵称" value={currentSection.nickname} onChange={value => change('nickname', value)} maxLength={60} isDisabled={formLocked} />
            <TextField className="pw-profile-field" fieldClassName="pw-profile-input" label="怎么称呼你" placeholder="希望 AI 使用的称呼" value={currentSection.addressAs} onChange={value => change('addressAs', value)} maxLength={60} isDisabled={formLocked} />
            <TextAreaField className="pw-profile-field" fieldClassName="pw-profile-input" label="关于我" placeholder="可以分享你的背景、兴趣或正在做的事" value={currentSection.about} onChange={value => change('about', value)} rows={4} maxLength={2000} resize="none" isDisabled={formLocked} />
          </div>}
          {view === 'communication' && <div className="pw-profile-form">
            <SelectField className="pw-profile-language" label="回答语言" value={currentSection.language} options={languageOptions} onChange={value => change('language', value)} isDisabled={formLocked} />
            <Segmented label="回答详略" value={currentSection.response_detail} options={detailOptions} disabled={formLocked} onChange={value => change('response_detail', value)} />
            <section className="pw-profile-switches" aria-label="表达习惯"><h2>表达习惯</h2><StretchSwitch label="先给结论" checked={currentSection.conclusionFirst} onChange={value => change('conclusionFirst', value)} disabled={formLocked} /><StretchSwitch label="需要时结合例子" checked={currentSection.includeExamples} onChange={value => change('includeExamples', value)} disabled={formLocked} /></section>
            <TextAreaField className="pw-profile-field" fieldClassName="pw-profile-input" label="补充偏好" placeholder="还有哪些希望我注意的表达习惯？" value={currentSection.instructions} onChange={value => change('instructions', value)} rows={4} maxLength={4000} resize="none" isDisabled={formLocked} />
            <p className="pw-profile-preference-priority">你在这里明确填写的偏好优先于推断的记忆。某次对话有新的要求时，以那次要求为准。</p>
            {supported('profile.preferences.control') && <Accordion className="pw-profile-disclosure"><AccordionItem id="personal-chat-preferences" title="通过聊天修改默认偏好" description={agentPreferences ? agentPreferences.enabled ? '已允许' : '当前关闭' : '状态尚未读取'}><div className="pw-profile-memory-controls pw-profile-switches">{agentPreferences ? <StretchSwitch label="允许通过聊天保存默认偏好" description="仅在你明确要求长期修改时，保存语言、详略、先结论、举例、节奏和主动建议；不会修改身份或执行权限。" checked={agentPreferences.enabled} onChange={saveAgentPreferenceControl} disabled={locked} busy={pending === '正在保存偏好权限'} /> : <Button variant="ghost" disabled={locked} onClick={refresh}>重新读取设置</Button>}</div></AccordionItem></Accordion>}
          </div>}
          {view === 'behavior' && <div className="pw-profile-form">
            <Segmented label="做事节奏" value={currentSection.operation_pace} options={paceOptions} disabled={formLocked} onChange={value => change('operation_pace', value)} />
            <p className="pw-profile-help">{currentSection.operation_pace === 'fast' ? '优先推进重点，减少不必要的说明。' : currentSection.operation_pace === 'careful' ? '多说明依据与取舍，细致处理重要步骤。' : '根据事情的复杂度安排节奏。'}</p>
            <Segmented label="主动建议" value={currentSection.initiative} options={initiativeOptions} disabled={formLocked} onChange={value => change('initiative', value)} />
            <p className="pw-profile-help">{currentSection.initiative === 'requested' ? '围绕你提出的事情回应。' : currentSection.initiative === 'proactive' ? '在相关时主动提出下一步或改进建议。' : '有帮助时补充相关建议。'}</p>
            <p className="pw-profile-footnote">这些偏好影响协作方式。执行范围与确认要求仍按任务设置。</p>
          </div>}
          <p className="pw-profile-save-hint">{!canSave ? '当前窗口暂不支持保存个人资料' : profileDirty(editor) ? '修改后点右上角保存' : '当前内容已保存'}</p>
        </>}
        {loaded && view === 'memories' && <>
          <p className="pw-profile-intro">让下次对话少一些重复解释。这里记录关于你的信息，随时可以纠正或忘记。</p>
          {memoryError && <div className="pw-profile-feedback is-error" role="alert"><p>{memoryError}</p><Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={locked} onClick={refresh}>重新读取记忆</Button></div>}
          {(activeMemories.length > 0 || pendingCandidates.length > 0 || !!query) && <div className="pw-profile-collection-tools">
            <SearchField className="pw-profile-memory-search" fieldClassName="pw-profile-input" aria-label={memories.complete ? '搜索个人记忆' : '搜索已读取的个人记忆'} placeholder="搜索个人记忆" clearLabel="清除搜索" value={query} onChange={setQuery} shape="rounded" isDisabled={locked} />
            <div className="pw-profile-collection-nav">
              <Button variant="secondary" leadingIcon={IconAdd} disabled={locked || !supported('profile.memory.createReview')} onClick={openNewMemory}>记住一件事</Button>
              {(pendingCandidates.length > 0 || showCandidates) && <Button variant="ghost" aria-pressed={showCandidates} disabled={locked} onClick={() => setShowCandidates(value => !value)}>{showCandidates ? '查看已保存' : `待确认 ${pendingCandidates.length} 条`}</Button>}
            </div>
            {!showCandidates && topics.length > 1 && <SelectField className="pw-profile-topic-select" label="主题" value={topic} options={[{ id: 'all', label: '所有主题' }, ...topics]} onChange={setTopic} isDisabled={locked} />}
            {!memories.complete && <p className="pw-profile-list-hint">当前搜索范围为已读取的个人记忆。</p>}
          </div>}
          {showCandidates ? <section className="pw-profile-memory-group"><h2>待你确认<span>{pendingCandidates.length}</span></h2><p className="pw-profile-list-hint">确认内容准确后再采纳。候选不会直接用于回答。</p><div className="pw-profile-memory-list">{visibleCandidates.map(row => memoryRow(row, true))}</div>{!visibleCandidates.length && <p className="pw-profile-list-hint">{query ? '没有匹配的候选。' : '目前没有待确认的内容。'}</p>}</section>
            : topics.filter(item => visibleMemories.some(row => topicOf(row).id === item.id)).map(item => <section className="pw-profile-memory-group" key={item.id}><h2>{item.label}</h2><div className="pw-profile-memory-list">{visibleMemories.filter(row => topicOf(row).id === item.id).map(row => memoryRow(row, false))}</div></section>)}
          {memoryAvailable && !showCandidates && !visibleMemories.length && (query || topic !== 'all') && <div className="pw-profile-empty"><h2>没有找到匹配的记忆</h2><p>试试其他关键词，或查看所有主题。</p><Button variant="ghost" disabled={locked} onClick={() => { setQuery(''); setTopic('all'); }}>清除搜索与筛选</Button></div>}
          {memoryAvailable && !activeMemories.length && !pendingCandidates.length && !query && topic === 'all' && <div className="pw-profile-empty"><IconSparkles aria-hidden="true" /><h2>还没有个人记忆</h2><p>告诉我一件你希望下次不用再解释的事。</p><Button leadingIcon={IconAdd} disabled={locked || !supported('profile.memory.createReview')} onClick={openNewMemory}>记住一件事</Button><p>也可以在对话中直接说“记住这件事”。</p></div>}
          {memories.pendingConflicts.length > 0 && <Accordion className="pw-profile-disclosure"><AccordionItem id="personal-conflicts" title={`有 ${memories.pendingConflicts.length} 项信息需要核对`}><p className="pw-profile-help">这些信息存在差异，尚未自动覆盖已保存内容。</p>{memories.pendingConflicts.map((row, index) => <p key={string(row.id) || index} className="pw-profile-conflict-text">{string(row.text || row.summary || row.reason) || '请核对相关个人记忆的来源与内容。'}</p>)}</AccordionItem></Accordion>}
          <div className="pw-profile-collection-footer"><Button variant="ghost" leadingIcon={IconHistory} trailingIcon={IconChevronRight} disabled={locked} onClick={() => go('memory-history')}>过去的记忆</Button>{onOpenAdvancedMemory && <Button variant="ghost" leadingIcon={IconArrowUpRight} disabled={locked} onClick={() => guarded(onOpenAdvancedMemory)}>项目记忆与更多管理</Button>}<Button variant="ghost" className="pw-profile-refresh" leadingIcon={IconRefresh} disabled={locked} onClick={refresh}>刷新记忆</Button></div>
        </>}
        {loaded && view === 'memory-new' && <div className="pw-profile-form">
          <p className="pw-profile-intro">写下一条关于你的背景、兴趣或近况。保存后可以查看、纠正或撤销这次记录。</p>
          <TextAreaField className="pw-profile-field" fieldClassName="pw-profile-input pw-profile-memory-input" label="希望记住什么" placeholder="一件你希望下次不用再解释的事" value={newMemoryText} onChange={setNewMemoryText} rows={5} maxLength={4000} resize="none" isDisabled={locked || createUnknown} />
          <SelectField className="pw-profile-language" label="有效期" value={newMemoryExpiry} options={expiryOptions} onChange={setNewMemoryExpiry} isDisabled={locked || createUnknown} />
          <p className="pw-profile-preference-priority">到期后会移入过去的记忆，不再作为当前背景。回答风格与做事方式可以在明确偏好中设置。</p>
          <Button variant="ghost" className="pw-profile-inline-link" trailingIcon={IconChevronRight} disabled={locked || createUnknown} onClick={() => openEditor('communication')}>设置交流偏好</Button>
        </div>}
        {loaded && view === 'memory-settings' && <div className="pw-profile-form pw-profile-memory-controls">
          <p className="pw-profile-intro">分别决定什么时候参考已有记忆，以及是否从对话整理新的候选。</p>
          {policy ? <>
            <section className="pw-profile-switches"><StretchSwitch label="回答参考个人记忆" description="后续私人回答按相关性参考已保存内容。暂停后，记忆仍保留在这里。" checked={policy.reading.enabled} onChange={enabled => saveControl('reading', enabled)} disabled={locked || !!controlsError || !supported('profile.memory.reading')} busy={pending === '正在保存记忆设置'} /></section>
            <section className="pw-profile-switches"><StretchSwitch label="从对话整理记忆候选" description="会使用当前模型整理近期的本人表达，可能产生模型用量。整理出的内容仍由你确认是否记住。" checked={policy.learning.enabled} onChange={enabled => saveControl('learning', enabled)} disabled={locked || !!controlsError || !supported('profile.memory.learning')} busy={pending === '正在保存记忆设置'} /></section>
            <p className="pw-profile-preference-priority">明确填写的交流与做事偏好优先于推断的记忆。项目资料在各自项目范围内管理。</p>
          </> : !locked && <p className="pw-profile-list-hint">设置尚未完整读取。</p>}
          {controlsError && <p className="pw-profile-list-hint">{controlsError}</p>}
          <div className="pw-profile-collection-footer"><Button variant="ghost" leadingIcon={IconRefresh} disabled={locked} onClick={openMemorySettings}>重新读取设置</Button>{onOpenAdvancedMemory && <Button variant="ghost" leadingIcon={IconArrowUpRight} disabled={locked} onClick={() => guarded(onOpenAdvancedMemory)}>项目记忆与更多管理</Button>}</div>
        </div>}
        {loaded && view === 'memory-history' && <>
          <p className="pw-profile-intro">已到期或已退出当前背景的内容保留在这里，方便核对过去的变化。</p>
          {(memories.history.length > 0 || !!query) && <SearchField className="pw-profile-memory-search" fieldClassName="pw-profile-input" aria-label="搜索过去的记忆" placeholder="搜索过去的记忆" clearLabel="清除搜索" value={query} onChange={setQuery} shape="rounded" isDisabled={locked} />}
          <div className="pw-profile-memory-list">{historyRows.map(row => memoryRow(row, false))}</div>
          {memoryAvailable && !historyRows.length && <div className="pw-profile-empty"><IconHistory aria-hidden="true" /><h2>{query ? '没有匹配的过去记忆' : '还没有过去的记忆'}</h2>{query ? <Button variant="ghost" onClick={() => setQuery('')}>清除搜索</Button> : <p>到期或被替代的内容会在这里显示。</p>}</div>}
          {memoryError && <p className="pw-profile-list-hint" role="alert">{memoryError}</p>}
          <Button variant="ghost" className="pw-profile-refresh" leadingIcon={IconRefresh} disabled={locked} onClick={refresh}>刷新记忆</Button>
        </>}
        {view === 'memory' && selection && <div className="pw-profile-memory-editor">
          <span className="pw-profile-memory-status">{memoryOrigin(selection.record, selection.candidate)}</span>
          {selection.candidate ? <>
            <p className="pw-profile-intro">确认这条信息准确后，再把它加入个人记忆。</p>
            {relatedRecords.length > 0 && <section className="pw-profile-memory-comparison"><h2>同项已有记录</h2>{relatedRecords.map(row => <p className="pw-profile-candidate-copy" key={row.id}>{string(row.text)}</p>)}<p className="pw-profile-list-hint">采纳会新增这条记忆。需要纠正已有内容时，请修改对应记录。</p><h2>待确认内容</h2></section>}
            <p className="pw-profile-candidate-copy">{selection.originalText}</p>
          </> : selection.historical ? <><p className="pw-profile-intro">这条内容已退出当前背景。</p><p className="pw-profile-candidate-copy">{selection.originalText}</p></> : <TextAreaField className="pw-profile-field" fieldClassName="pw-profile-input pw-profile-memory-input" label="记忆内容" value={selection.draft} onChange={value => { if (!busy.current) { setSelection(old => old ? { ...old, draft: value } : old); setNotice(''); } }} rows={5} maxLength={4000} resize="none" isDisabled={locked} />}
          <p className="pw-profile-memory-meta">私人对话 · 关于你{memoryDates(selection.record) ? ` · ${memoryDates(selection.record)}` : ''}</p>
          {!selection.candidate && !selection.historical && memoryDirty(selection) && <Accordion className="pw-profile-disclosure"><AccordionItem id="personal-changes" title="查看修改前后"><div className="pw-profile-memory-comparison"><section><h3>原来记住的内容</h3><p>{selection.originalText}</p></section><section><h3>准备更新为</h3><p>{selection.draft}</p></section></div></AccordionItem></Accordion>}
          {(memoryConflict || memoryUnknown) && <section className="pw-profile-conflict" role="alert"><h2>{memoryUnknown ? '请先核对保存结果' : '这条记忆已有变化'}</h2><p>当前输入已保留，重新读取不会自动覆盖。</p>{latestMemory ? <><div className="pw-profile-latest"><h3>最新内容</h3><p>{string(latestMemory.record?.text)}</p></div><div className="pw-profile-actions"><Button variant="secondary" size="small" disabled={locked} onClick={() => adoptLatestMemory(true)}>保留我的输入</Button><Button variant="ghost" size="small" disabled={locked} onClick={() => adoptLatestMemory(false)}>使用最新内容</Button></div></> : <Button variant="secondary" size="small" leadingIcon={IconRefresh} disabled={locked} onClick={reloadMemory}>读取最新记忆</Button>}</section>}
          <Accordion className="pw-profile-disclosure" expandedKeys={sourceOpen ? ['personal-source'] : []} onExpandedChange={keys => { setSourceOpen(keys.has('personal-source')); }}><AccordionItem id="personal-source" title="来源"><div className="pw-profile-sources">{memorySources.length ? memorySources.map((row, index) => <Button key={string(row.sourceId) + ':' + index} variant="ghost" size="small" leadingIcon={IconArrowUpRight} disabled={locked || memoryConflict || memoryUnknown || !readHandle.current} onClick={() => readSource(index)}>{string(row.title || row.name) || `查看来源 ${index + 1}`}</Button>) : <p className="pw-profile-help">{memoryOrigin(selection.record) === '你主动记录' ? '你主动记录的内容，没有关联聊天原文。' : '这条记忆没有附带可查看的来源。'}</p>}{source && <section className="pw-profile-source"><h3>{string(source.title) || '原始内容'}</h3><p>{string(source.text)}</p>{source.hasMore === true && <Button variant="ghost" size="small" disabled={locked || memoryConflict || memoryUnknown || !readHandle.current} onClick={() => readSource(Number(source.index) || 0, Number(source.nextOffset) || 0)}>继续读取</Button>}</section>}</div></AccordionItem></Accordion>
          {selection.candidate ? <div className="pw-profile-actions pw-profile-candidate-actions"><Button disabled={locked || memoryConflict || memoryUnknown || settingsRequiredFor(feedback, 'accept', selection.id) || !supported('profile.memory.confirm')} onClick={() => prepareReview('accept')}>记住</Button><Button variant="ghost" disabled={locked || memoryConflict || memoryUnknown || !supported('profile.memory.confirm')} onClick={() => prepareReview('reject')}>忽略</Button></div> : <Button variant="ghost" className="pw-profile-delete" leadingIcon={IconTrash} disabled={locked || memoryConflict || memoryUnknown || !supported('profile.memory.confirm')} onClick={() => prepareReview('forget')}>忘记这条记忆</Button>}
        </div>}
      </div>
    </div>
    <SettingsDialog isOpen={leaveOpen} title={createUnknown ? '保存结果尚未核对，仍要离开？' : '放弃未保存的修改？'} onClose={() => { if (!busy.current) { pendingLeave.current = null; setLeaveOpen(false); } }}><div className="pw-profile-dialog"><p>{createUnknown ? '建议先查询这次保存。离开前请保留需要的正文，并在个人记忆中核对，避免重复添加。' : '当前修改还没有保存，离开后将恢复已保存的内容。'}</p><div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={() => { pendingLeave.current = null; setLeaveOpen(false); }}>{createUnknown ? '返回核对' : '继续编辑'}</Button><Button disabled={locked} onClick={() => { const next = pendingLeave.current; pendingLeave.current = null; setLeaveOpen(false); clearReview(); setEditor(null); setNewMemoryText(''); setNewMemoryExpiry('none'); setCreateUnknown(false); const cleanSelection = current.current.selection ? { ...current.current.selection, draft: current.current.selection.originalText } : null; setSelection(cleanSelection); current.current = { ...current.current, editor: null, selection: cleanSelection, review: null, leaveOpen: false, newMemoryText: '', newMemoryExpiry: 'none', createUnknown: false }; next?.(); }}>{createUnknown ? '离开' : '放弃修改'}</Button></div></div></SettingsDialog>
    <SettingsDialog isOpen={!!review} title="忘记这条记忆？" onClose={() => { if (!busy.current) clearReview(); }}><div className="pw-profile-dialog"><p className="pw-profile-review-text">{string(review?.value.before?.text) || selection?.originalText}</p><p>以后不再参考这条记忆，也会处理依赖它的旧内容。原聊天保留。这次遗忘不能撤销。</p><div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={clearReview}>取消</Button><Button disabled={locked} onClick={confirmReview}>忘记</Button></div></div></SettingsDialog>
  </section>;
}

export default ProfileWorkspace;
