import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { ArrowLeft, ArrowUpRight, Check, ChevronRight, MessageCircle, RefreshCw, Settings, ShieldCheck, SlidersHorizontal, Sparkles, Trash2, UserRound } from 'lucide-react';
import { Accordion, AccordionItem, Button, IconButton, SelectField, SettingsDialog, StretchSwitch, TextAreaField, TextField } from './forms-library';
import { Radio, RadioGroup } from './forms-library/boardui/components/base/radio/radio';
import { useReducedMotion } from './library/core';
import { EASE_OUT } from './shell-library/beui-adapted/ease';
import './profile.css';

type Data = Record<string, any>;
type Section = 'identity' | 'communication' | 'behavior';
type View = 'home' | Section | 'memories' | 'memory';
type Run = (action: string, args?: Record<string, unknown>) => Promise<any>;
type LeaveGuard = (next: () => void) => boolean;
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
};
type Memories = { current: Data[]; candidates: Data[]; pendingConflicts: Data[] };
type Editor = { section: Section; original: PersonalProfile; draft: PersonalProfile };
type MemorySelection = { id: string; candidate: boolean; value: Data; record: Data; originalText: string; draft: string };
type MemoryReview = { value: Data; operation: 'forget' | 'accept' | 'reject'; id: string; candidate: boolean };
type AvatarPicker = { id: string; editor: number };
type AvatarPoll = { sequence: number; key: string | undefined; visibility: number; picker: AvatarPicker };
const labels: Record<View, string> = { home: '我的', identity: '编辑资料', communication: '交流偏好', behavior: '做事偏好', memories: 'AI 对我的了解', memory: '编辑记忆' };
const languageOptions = [{ id: 'auto', label: '跟随对话' }, { id: 'zh-CN', label: '中文' }, { id: 'en', label: 'English' }, { id: 'ja', label: '日本語' }];
const detailOptions = [{ id: 'concise', label: '简短' }, { id: 'standard', label: '跟随问题' }, { id: 'detailed', label: '详细' }];
const paceOptions = [{ id: 'fast', label: '高效' }, { id: 'standard', label: '均衡' }, { id: 'careful', label: '细致' }];
const initiativeOptions = [{ id: 'requested', label: '按需' }, { id: 'balanced', label: '适度' }, { id: 'proactive', label: '主动建议' }];
const emptyMemories = (): Memories => ({ current: [], candidates: [], pendingConflicts: [] });
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const rows = (value: any): Data[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const string = (value: any) => typeof value === 'string' ? value : '';
const option = (value: any, choices: Array<{ id: string }>, fallback: string) => choices.some(choice => choice.id === value) ? value : fallback;
const avatarUrl = (value: any) => typeof value === 'string' && value.length <= 300000 && /^data:image\/(?:jpeg|png|webp);base64,[a-z\d+/=\s]+$/i.test(value) ? value : '';
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
  if (row.assertionReview?.revision === row.revision || ['reviewed_learning', 'reviewed_consolidation'].includes(row.formation)) return '已确认';
  if (row.assertion?.kind === 'user_said') return '来自我的表达';
  return '已保存';
}
function Avatar({ profile, large = false }: { profile: PersonalProfile | null; large?: boolean }) {
  const nickname = profile?.nickname.trim() || profile?.addressAs.trim() || '';
  return <span className={`pw-profile-avatar${large ? ' is-large' : ''}`} aria-hidden="true">{profile?.avatarDataUrl ? <img src={profile.avatarDataUrl} alt="" /> : nickname ? <span>{Array.from(nickname)[0]}</span> : <UserRound strokeWidth={1.65} />}</span>;
}
function Segmented({ label, value, options, disabled, onChange }: { label: string; value: string; options: Array<{ id: string; label: string }>; disabled: boolean; onChange: (value: string) => void }) {
  return <div className="pw-profile-choice"><h2>{label}</h2><RadioGroup aria-label={label} value={value} onChange={onChange} isDisabled={disabled} className="pw-profile-segments">{options.map(item => <Radio key={item.id} value={item.id} className="pw-profile-segment"><span>{item.label}</span></Radio>)}</RadioGroup></div>;
}
/** Personal views share the existing host-owned profile and memory records. */
export function ProfileWorkspace({ state, supported, run, onBack, onSettings, registerBack, registerBeforeLeave, presentationKey }: Props) {
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
  const alive = useRef(true), busy = useRef(false), runRef = useRef(run), supportedRef = useRef(supported);
  const readHandle = useRef(''), reviewHandle = useRef(''), pendingLeave = useRef<(() => void) | null>(null);
  const avatarPicker = useRef<AvatarPicker | null>(null), editorInstance = useRef(0);
  const avatarPoll = useRef<AvatarPoll | null>(null), avatarPollSequence = useRef(0), avatarVisibility = useRef(0);
  const avatarWaitingForPresentation = useRef<{ key: string } | null>(null), wakeAvatar = useRef<(() => void) | null>(null);
  const current = useRef({ view, editor, selection, profile, review, leaveOpen, memoryUnknown });
  current.current = { view, editor, selection, profile, review, leaveOpen, memoryUnknown };
  runRef.current = run; supportedRef.current = supported;
  const scroller = useRef<HTMLDivElement>(null), heading = useRef<HTMLHeadingElement>(null);
  const positions = useRef(new Map<string, number>()), returnMemory = useRef(''), previousView = useRef<View>('home');
  const memoryParent = useRef<'home' | 'memories'>('memories');
  const previousPresentation = useRef(presentationKey);
  const livePresentation = useRef(presentationKey);
  livePresentation.current = presentationKey;
  const reducedMotion = useReducedMotion();
  const canEnter = supported('connections.enter'), canRead = supported('profile.snapshot'), canSave = supported('profile.save');
  const locked = !!pending;
  const formLocked = locked || avatarPicking || !canSave;
  const profileConflict = !!editor && !!profile && editor.original.revision !== profile.revision && !sameSection(editor.original, profile, editor.section);
  const pendingCandidates = memories.candidates.filter(row => !row.status || row.status === 'pending');
  const activeMemories = memories.current.filter(row => row.active !== false);

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
    const data = object(result.memories);
    setMemories({ current: rows(data.current), candidates: rows(data.candidates), pendingConflicts: rows(data.pendingConflicts) });
    const unavailable = string(result.memoryError) || (data.available === false ? '个人记忆暂时无法读取，请重试。' : '');
    setMemoryError(unavailable); setMemoryAvailable(!unavailable);
    setLoaded(true);
    return result;
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
  const hostRevision = object(state.personalProfile).revision;
  useEffect(() => {
    // Streaming snapshots deliberately carry identity metadata only. Fetch the
    // full owner profile when its revision changes; never turn metadata into defaults.
    if (enteredProfile && canRead && loaded && !busy.current && Number.isSafeInteger(hostRevision) && current.current.profile && hostRevision > current.current.profile.revision) void action('正在核对最新资料', async () => { await readSnapshot(); });
  }, [hostRevision, pending, enteredProfile, canRead, loaded]);
  useEffect(() => {
    const previous = previousPresentation.current;
    previousPresentation.current = presentationKey;
    if (previous === undefined || previous === presentationKey || !current.current.selection) return;
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
    const returnTarget = view === 'memories' && returnMemory.current ? Array.from(node?.querySelectorAll<HTMLElement>('[data-personal-memory]') || []).find(item => item.dataset.personalMemory === returnMemory.current) : null;
    (returnTarget || heading.current)?.focus({ preventScroll: true });
    if (!node || reducedMotion || document.hidden) return;
    const animation = node.animate([{ opacity: .4, transform: 'translateY(5px)' }, { opacity: 1, transform: 'translateY(0)' }], { duration: 180, easing: `cubic-bezier(${EASE_OUT.join(',')})` });
    return () => animation.cancel();
  }, [view, reducedMotion]);

  const beforeLeave: LeaveGuard = next => {
    if (busy.current) { setNotice('正在保存或读取，请稍候。'); return true; }
    closeAvatar();
    if (current.current.review) { clearReview(); current.current = { ...current.current, review: null }; }
    if (profileDirty(current.current.editor) || memoryDirty(current.current.selection)) { pendingLeave.current = next; setLeaveOpen(true); return true; }
    return false;
  };
  const guarded = (next: () => void) => { if (!beforeLeave(next)) next(); };
  const leaveToParent = () => {
    if (current.current.view === 'memory') { clearSelection(); go(memoryParent.current); }
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
      setProfile(saved); setEditor({ section: frozen.section, original: saved, draft: { ...saved } }); setNotice('已保存');
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
      memoryParent.current = current.current.view === 'home' ? 'home' : 'memories';
      setSelection({ id: row.id, candidate, value: result.value, record: result.row, originalText: string(result.row.text), draft: string(result.row.text) });
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
      const latestRows = rows(object(snapshot.memories)[frozen.candidate ? 'candidates' : 'current']);
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
        setSelection({ ...frozen, value: result.value, record: result.row, originalText: nextText, draft });
        setMemoryConflict(false); setMemoryUnknown(false); setLatestMemory(null);
        setNotice(nextText === frozen.draft.trim() ? '当前内容已核对' : '已刷新，未保存的输入已保留');
      }
    });
  };
  const checkedReview = async (operation: string, frozen: MemorySelection, extra: Record<string, unknown> = {}) => {
    const value = object(await call('profile.memory.review', { readHandle: readHandle.current, operation, ...extra }));
    const handle = string(value.reviewHandle);
    if (!handle || value.before?.id !== frozen.id || value.before?.revision !== frozen.record.revision || value.operation !== operation) {
      closeHandle(handle); throw new Error('记忆修改范围已变化，请重新读取后继续。');
    }
    return { value, handle };
  };
  const saveMemory = () => {
    const frozen = current.current.selection;
    if (!frozen || frozen.candidate || !memoryDirty(frozen) || !frozen.draft.trim() || memoryConflict || memoryUnknown || !readHandle.current) return;
    const text = frozen.draft.trim();
    void action('正在保存记忆', async () => {
      let sent = false, saved = false;
      try {
        const prepared = await checkedReview('correct', frozen, { text });
        if (typeof prepared.value.afterText === 'string' && prepared.value.afterText !== text) { closeHandle(prepared.handle); throw new Error('待保存内容与当前输入不同，请重新读取这条记忆。'); }
        if (!alive.current) { closeHandle(prepared.handle); return; }
        sent = true;
        const receipt = object(await call('profile.memory.confirm', { reviewHandle: prepared.handle }));
        if (receipt.record?.id !== frozen.id || receipt.record?.text !== text || !(receipt.record?.revision > frozen.record.revision)) throw new Error('记忆保存回执尚未确认。');
        saved = true;
        if (!alive.current) return;
        setSelection({ ...frozen, record: receipt.record, originalText: text, draft: text });
        const latest = await readMemory(frozen.id, frozen.candidate);
        if (!alive.current) { closeHandle(latest.handle); return; }
        closeHandle(readHandle.current); readHandle.current = latest.handle;
        if (string(latest.row.text) !== text) { setLatestMemory({ value: latest.value, record: latest.row }); setMemoryConflict(true); throw new Error('最新记忆与本次输入不同，输入已保留，请核对。'); }
        setSelection({ ...frozen, value: latest.value, record: latest.row, originalText: text, draft: text });
        setMemoryConflict(false); setMemoryUnknown(false); setLatestMemory(null);
        await readSnapshot();
        if (alive.current) setNotice('已保存');
      } catch (failure) {
        if (alive.current) { if (sent && !saved) setMemoryUnknown(true); else setMemoryConflict(true); }
        throw new Error(saved ? '记忆已保存，最新状态暂未完整读取。请刷新后继续。' : (failure instanceof Error ? failure.message : '记忆保存未完成。') + (sent ? ' 请核对最新内容后继续。' : ' 当前输入已保留。'));
      }
    });
  };
  const prepareReview = (operation: MemoryReview['operation']) => {
    const frozen = current.current.selection;
    if (!frozen || memoryConflict || memoryUnknown || !readHandle.current || (operation !== 'forget' && memoryDirty(frozen))) return;
    void action('正在读取修改范围', async () => {
      const prepared = await checkedReview(operation, frozen, operation === 'reject' ? { reason: '用户在个人中心选择忽略这条候选。' } : {});
      if (!alive.current) { closeHandle(prepared.handle); return; }
      clearReview(); reviewHandle.current = prepared.handle;
      setReview({ value: prepared.value, operation, id: frozen.id, candidate: frozen.candidate });
    });
  };
  const confirmReview = () => {
    const frozen = current.current.review, handle = reviewHandle.current;
    if (!frozen || !handle) return;
    void action('正在更新记忆', async () => {
      reviewHandle.current = ''; setReview(null);
      let completed = false;
      try {
        const receipt = object(await call('profile.memory.confirm', { reviewHandle: handle }));
        const valid = frozen.operation === 'forget' ? receipt.deleted >= 1 : receipt.candidate?.id === frozen.id && receipt.candidate?.status === (frozen.operation === 'accept' ? 'accepted' : 'rejected') && receipt.candidate?.revision > frozen.value.before.revision;
        if (!valid) throw new Error('记忆处理回执尚未确认。');
        completed = true;
        if (!alive.current) return;
        setMemories(old => ({ ...old, current: frozen.operation === 'forget' ? old.current.filter(row => row.id !== frozen.id) : frozen.operation === 'accept' && receipt.record?.id ? [...old.current.filter(row => row.id !== receipt.record.id), receipt.record] : old.current, candidates: old.candidates.filter(row => row.id !== frozen.id), pendingConflicts: old.pendingConflicts.filter(row => row.id !== frozen.id) }));
        clearSelection(); go('memories');
        await readSnapshot();
        if (!alive.current) return;
        setNotice(frozen.operation === 'forget' ? '记忆已删除' : frozen.operation === 'accept' ? '已记住' : '已忽略');
      } catch (failure) {
        if (alive.current && !completed) setMemoryUnknown(true);
        throw new Error(completed ? '操作已完成，列表暂未完整刷新，请重新读取记忆。' : (failure instanceof Error ? failure.message : '处理结果尚未确认。') + ' 请核对最新记忆，不要重复提交。');
      }
    });
  };
  const adoptLatestMemory = (keepDraft: boolean) => {
    if (!selection || !latestMemory) return;
    const record = object(latestMemory.record);
    setSelection({ ...selection, value: object(latestMemory.value), record, originalText: string(record.text), draft: keepDraft ? selection.draft : string(record.text) });
    setLatestMemory(null); setMemoryConflict(false); setMemoryUnknown(false); setError(''); setNotice(keepDraft ? '输入已保留，请核对后保存' : '已使用最新内容');
  };
  const readSource = (index: number, offset = 0) => {
    if (!readHandle.current) return;
    void action('正在读取来源', async () => {
      const value = object(await call('profile.memory.source', { readHandle: readHandle.current, index, offset }));
      if (alive.current) setSource(old => offset > 0 && old?.index === index ? { ...value, index, text: string(old.text) + string(value.text) } : { ...value, index });
    });
  };
  const currentSection = editor?.draft;
  const memorySources = rows(selection?.record.sourceRefs);
  const headerSave = editor ? <Button className="pw-profile-save" size="small" disabled={locked || avatarPicking || !profileDirty(editor) || profileConflict || !canSave} onClick={saveProfile}>{pending === '正在保存' ? '保存中' : '保存'}</Button>
    : view === 'memory' && selection && !selection.candidate ? <Button className="pw-profile-save" size="small" disabled={locked || !memoryDirty(selection) || !selection.draft.trim() || memoryConflict || memoryUnknown || !supported('profile.memory.confirm')} onClick={saveMemory}>{pending === '正在保存记忆' ? '保存中' : '保存'}</Button>
      : <IconButton icon={Settings} aria-label="设置" title="设置" onClick={() => guarded(onSettings)} />;
  const entry = (id: string, title: string, description: string, icon: typeof MessageCircle, open: () => void, badge?: ReactNode) => <Button variant="ghost" className="pw-profile-entry" leadingIcon={icon} trailingIcon={ChevronRight} onClick={open} disabled={!loaded || locked} data-profile-entry={id}><span className="pw-profile-entry-copy"><strong>{title}</strong><small>{description}</small></span>{badge}</Button>;
  const memoryRow = (row: Data, candidate: boolean) => <Button key={(candidate ? 'candidate:' : 'memory:') + row.id} variant="ghost" className="pw-profile-memory-row" trailingIcon={ChevronRight} data-personal-memory={string(row.id)} disabled={locked || !supported('profile.memory.get')} onClick={() => openMemory(row, candidate)}><span><strong>{string(row.text || row.title) || '未命名记忆'}</strong><small>{memoryOrigin(row, candidate)}</small></span></Button>;

  return <section className="pw-profile-workspace pw-route-workspace" aria-label={view === 'memory' && selection?.candidate ? '确认记忆' : labels[view]}>
    <header className="pw-profile-header"><IconButton icon={ArrowLeft} aria-label={view === 'home' ? '返回' : '返回上一级'} onClick={requestBack} /><h1 ref={heading} tabIndex={-1}>{view === 'memory' && selection?.candidate ? '确认记忆' : labels[view]}</h1>{headerSave}</header>
    <div className="pw-profile-scroll" ref={scroller}>
      <div className="pw-profile-content">
        {!canEnter && <div className="pw-profile-empty"><UserRound aria-hidden="true" /><h2>个人资料暂不可用</h2><p>请更新应用后重新打开。</p></div>}
        {canEnter && !loaded && <div className="pw-profile-loading" role="status">{locked || !enteredProfile || !canRead ? '正在打开个人资料…' : '个人资料尚未读取。'}{!locked && <Button variant="ghost" leadingIcon={RefreshCw} onClick={refresh}>重新读取</Button>}</div>}
        {error && <div className="pw-profile-feedback is-error" role="alert"><p>{error}</p>{!locked && <Button variant="ghost" size="small" leadingIcon={RefreshCw} onClick={view === 'memory' ? reloadMemory : refresh}>读取最新{view === 'memory' ? '记忆' : '资料'}</Button>}</div>}
        {notice && <p className="pw-profile-feedback" role="status">{['已保存', '记忆已删除', '已记住', '已忽略', '当前内容已核对'].includes(notice) && <Check size={15} aria-hidden="true" />}{notice}</p>}
        {locked && loaded && <p className="pw-profile-working" role="status">{pending}…</p>}
        {avatarPicking && !locked && <p className="pw-profile-working" role="status">正在等候所选头像…</p>}
        {loaded && profile && view === 'home' && <>
          <section className="pw-profile-identity" aria-label="个人资料"><Avatar profile={profile} large /><div><h2>{profile.nickname || '我的资料'}</h2>{profile.addressAs && profile.addressAs !== profile.nickname && <p>称呼我为 {profile.addressAs}</p>}<Button variant="ghost" size="small" className="pw-profile-edit-link" onClick={() => openEditor('identity')}>编辑资料<ChevronRight size={15} aria-hidden="true" /></Button></div></section>
          <div className="pw-profile-menu">
            {entry('communication', '交流偏好', [selectedLabel(languageOptions, profile.language), selectedLabel(detailOptions, profile.response_detail)].filter(Boolean).join(' · '), MessageCircle, () => openEditor('communication'))}
            {entry('behavior', '做事偏好', [selectedLabel(paceOptions, profile.operation_pace), selectedLabel(initiativeOptions, profile.initiative)].filter(Boolean).join(' · '), SlidersHorizontal, () => openEditor('behavior'))}
            {entry('memories', 'AI 对我的了解', '查看和修正个人记忆', Sparkles, () => { setEditor(null); go('memories'); }, pendingCandidates.length > 0 ? <span className="pw-profile-unread" aria-label={`${pendingCandidates.length} 条待确认`} /> : undefined)}
          </div>
          {memoryAvailable && activeMemories.length > 0 && <section className="pw-profile-home-memories" aria-label="个人记忆预览">{activeMemories.slice(0, 2).map(row => <Button key={row.id} variant="ghost" className="pw-profile-home-memory" trailingIcon={ChevronRight} disabled={locked || !supported('profile.memory.get')} onClick={() => openMemory(row, false)}><span><strong>{string(row.text || row.title) || '未命名记忆'}</strong><small>{memoryOrigin(row)}</small></span></Button>)}</section>}
          <Button variant="ghost" className="pw-profile-data-entry" leadingIcon={ShieldCheck} trailingIcon={ChevronRight} disabled={locked} onClick={() => guarded(onSettings)}>隐私与数据</Button>
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
          <p className="pw-profile-intro">你可以随时修改或删除。待确认的内容，采纳后才会用于个性化回答。</p>
          {memoryError && <div className="pw-profile-feedback is-error" role="alert"><p>{memoryError}</p><Button variant="ghost" size="small" leadingIcon={RefreshCw} disabled={locked} onClick={refresh}>重新读取记忆</Button></div>}
          {pendingCandidates.length > 0 && <section className="pw-profile-memory-group"><h2>待我确认<span>{pendingCandidates.length}</span></h2><div className="pw-profile-memory-list">{pendingCandidates.map(row => memoryRow(row, true))}</div></section>}
          {activeMemories.length > 0 && <section className="pw-profile-memory-group"><h2>关于我</h2><div className="pw-profile-memory-list">{activeMemories.map(row => memoryRow(row, false))}</div></section>}
          {memoryAvailable && !activeMemories.length && !pendingCandidates.length && <div className="pw-profile-empty"><Sparkles aria-hidden="true" /><h2>还没有个人记忆</h2><p>有了关于你的记忆，会在这里显示。</p></div>}
          {memories.pendingConflicts.length > 0 && <Accordion className="pw-profile-disclosure"><AccordionItem id="personal-conflicts" title={`有 ${memories.pendingConflicts.length} 项信息需要核对`}><p className="pw-profile-help">这些信息存在差异，尚未自动覆盖已保存内容。</p>{memories.pendingConflicts.map((row, index) => <p key={string(row.id) || index} className="pw-profile-conflict-text">{string(row.text || row.summary || row.reason) || '请核对相关个人记忆的来源与内容。'}</p>)}</AccordionItem></Accordion>}
          <Button variant="ghost" className="pw-profile-refresh" leadingIcon={RefreshCw} disabled={locked} onClick={refresh}>刷新记忆</Button>
        </>}
        {view === 'memory' && selection && <div className="pw-profile-memory-editor">
          <span className="pw-profile-memory-status">{memoryOrigin(selection.record, selection.candidate)}</span>
          {selection.candidate ? <><p className="pw-profile-intro">确认这条信息准确后，再把它加入个人记忆。</p><p className="pw-profile-candidate-copy">{selection.originalText}</p></> : <TextAreaField className="pw-profile-field" fieldClassName="pw-profile-input pw-profile-memory-input" label="记忆内容" value={selection.draft} onChange={value => { if (!busy.current) { setSelection(old => old ? { ...old, draft: value } : old); setNotice(''); } }} rows={5} maxLength={4000} resize="none" isDisabled={locked} />}
          {(memoryConflict || memoryUnknown) && <section className="pw-profile-conflict" role="alert"><h2>{memoryUnknown ? '请先核对保存结果' : '这条记忆已有变化'}</h2><p>当前输入已保留，重新读取不会自动覆盖。</p>{latestMemory ? <><div className="pw-profile-latest"><h3>最新内容</h3><p>{string(latestMemory.record?.text)}</p></div><div className="pw-profile-actions"><Button variant="secondary" size="small" disabled={locked} onClick={() => adoptLatestMemory(true)}>保留我的输入</Button><Button variant="ghost" size="small" disabled={locked} onClick={() => adoptLatestMemory(false)}>使用最新内容</Button></div></> : <Button variant="secondary" size="small" leadingIcon={RefreshCw} disabled={locked} onClick={reloadMemory}>读取最新记忆</Button>}</section>}
          <Accordion className="pw-profile-disclosure" expandedKeys={sourceOpen ? ['personal-source'] : []} onExpandedChange={keys => { setSourceOpen(keys.has('personal-source')); }}><AccordionItem id="personal-source" title="来源"><div className="pw-profile-sources">{memorySources.length ? memorySources.map((row, index) => <Button key={string(row.sourceId) + ':' + index} variant="ghost" size="small" leadingIcon={ArrowUpRight} disabled={locked || memoryConflict || memoryUnknown || !readHandle.current} onClick={() => readSource(index)}>{string(row.title || row.name) || `查看来源 ${index + 1}`}</Button>) : <p className="pw-profile-help">这条记忆没有附带可查看的来源。</p>}{source && <section className="pw-profile-source"><h3>{string(source.title) || '原始内容'}</h3><p>{string(source.text)}</p>{source.hasMore === true && <Button variant="ghost" size="small" disabled={locked || memoryConflict || memoryUnknown || !readHandle.current} onClick={() => readSource(Number(source.index) || 0, Number(source.nextOffset) || 0)}>继续读取</Button>}</section>}</div></AccordionItem></Accordion>
          {selection.candidate ? <div className="pw-profile-actions pw-profile-candidate-actions"><Button disabled={locked || memoryConflict || memoryUnknown} onClick={() => prepareReview('accept')}>记住</Button><Button variant="ghost" disabled={locked || memoryConflict || memoryUnknown} onClick={() => prepareReview('reject')}>忽略</Button></div> : <Button variant="ghost" className="pw-profile-delete" leadingIcon={Trash2} disabled={locked || memoryConflict || memoryUnknown} onClick={() => prepareReview('forget')}>删除这条记忆</Button>}
        </div>}
      </div>
    </div>
    <SettingsDialog isOpen={leaveOpen} title="放弃未保存的修改？" onClose={() => { if (!busy.current) { pendingLeave.current = null; setLeaveOpen(false); } }}><div className="pw-profile-dialog"><p>当前修改还没有保存，离开后将恢复已保存的内容。</p><div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={() => { pendingLeave.current = null; setLeaveOpen(false); }}>继续编辑</Button><Button disabled={locked} onClick={() => { const next = pendingLeave.current; pendingLeave.current = null; setLeaveOpen(false); clearReview(); setEditor(null); const cleanSelection = current.current.selection ? { ...current.current.selection, draft: current.current.selection.originalText } : null; setSelection(cleanSelection); current.current = { ...current.current, editor: null, selection: cleanSelection, review: null, leaveOpen: false }; next?.(); }}>放弃修改</Button></div></div></SettingsDialog>
    <SettingsDialog isOpen={!!review} title={review?.operation === 'forget' ? '删除这条记忆？' : review?.operation === 'accept' ? '记住这条信息？' : '忽略这条候选？'} onClose={() => { if (!busy.current) clearReview(); }}><div className="pw-profile-dialog"><p className="pw-profile-review-text">{string(review?.value.before?.text) || selection?.originalText}</p><p>{review?.operation === 'forget' ? '删除后不再用它个性化回答。原始对话保留。' : review?.operation === 'accept' ? '确认后，它将成为关于你的个人记忆。' : '这条内容不会加入个人记忆。'}</p><div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={clearReview}>取消</Button><Button disabled={locked} onClick={confirmReview}>{review?.operation === 'forget' ? '删除' : review?.operation === 'accept' ? '记住' : '忽略'}</Button></div></div></SettingsDialog>
  </section>;
}

export default ProfileWorkspace;
