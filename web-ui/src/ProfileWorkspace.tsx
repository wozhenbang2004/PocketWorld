import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { bridge } from './bridge';
import { IconAccount, IconAdd, IconArrowLeft, IconArrowUpRight, IconCalendarCheck, IconChat, IconCheck, IconChevronRight, IconControls, IconGoal, IconHistory, IconRefresh, IconSettings, IconShieldCheck, IconSparkles, IconTrash, IconUndo } from './icons';
import { Accordion, AccordionItem, Button, IconButton, SearchField, SelectField, SettingsDialog, StretchSwitch, TextAreaField, TextField } from './forms-library';
import { Radio, RadioGroup } from './forms-library/boardui/components/base/radio/radio';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, WorkingStatus, useCollectionMotion } from './library/journey-motion';
import { usePendingCandidateDeadline } from './usePendingCandidateDeadline';
import { PersonalMemorySources } from './PersonalMemorySources';
import { OriginalMemoryTerm, termInZone } from './OriginalMemoryTerm';
import { calendarTimeAt, validCalendarTime, type CalendarTime } from './calendar-time';
import { CalendarDateTimeFields } from './CalendarDateTimeFields';
import './profile.css';

type Data = Record<string, any>;
type Section = 'identity' | 'communication' | 'behavior';
type View = 'home' | Section | 'memories' | 'memory' | 'memory-new' | 'memory-settings' | 'memory-history';
type Run = (action: string, args?: Record<string, unknown>) => Promise<any>;
type LeaveGuard = (next: () => void) => boolean;
export type OriginalMemoryConversation = { id: string; key: string; epoch: number; revision: number; resumeRevision: number };
type OriginalMemoryEntry = { conversation: OriginalMemoryConversation; current: () => boolean };
export type AnswerMemoryForgetReceipt = { id: string; revision: number };
type AnswerMemoryForgetEntry = AnswerMemoryForgetReceipt & { current: () => boolean };
export type PersonalTarget = { view: 'home' | 'memories' | 'memory-settings' } | {
  view: 'memory'; id: string; candidate: boolean; expectedRevision?: number; originalAnswer?: OriginalMemoryEntry; originalForget?: AnswerMemoryForgetEntry
} | { view: 'memory-new'; fromChat: { enter: () => Promise<void>; dispose: () => void; current: () => boolean; back: (record?: ConfirmedManualSave) => void } };
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
  onBack: (forget?: AnswerMemoryForgetReceipt) => void;
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
type SuggestionMemoryVisit = { candidateId: string; revision: number; relatedId: string; presentation?: string; conversation: string; scrollTop: number };
type MemorySelection = { id: string; candidate: boolean; value: Data; record: Data; originalText: string; draft: string; historical?: boolean; fromSuggestion?: SuggestionMemoryVisit };
type MemoryReview = { value: Data; id: string } & (
  { operation: 'forget' | 'accept' | 'reject' | 'shorten'; candidate: boolean } |
  { operation: 'core'; candidate: false; presentation?: string; readHandle: string });
type CreateTermReview = { operation: 'createAt'; value: Data; calendar: CalendarTime; presentation?: string };
type MutationOperation = 'create' | 'correct' | MemoryReview['operation'];
type Removal = 'forget' | 'reject';
type MemoryDirectory = 'memories' | 'memory-history';
export type ConfirmedManualSave = { id: string; revision: 1 };
type CorrectionSubmission = { beforeRevision: number; draft: string };
type MemoryFeedback = { value: Data; operation: MutationOperation; needsQuery: boolean; targetId?: string; submittedText?: string; directory?: MemoryDirectory; candidateTerm?: { revision: number; expiresAt: number }; term?: { id: string; revision: number; until: number }; priority?: { revision: number; core: boolean }; priorSave?: ConfirmedManualSave; correction?: CorrectionSubmission; answerForget?: AnswerMemoryForgetEntry };
type MemoryOpenPin = { kind: 'answer'; revision: number; conversation: string; originalAnswer?: OriginalMemoryEntry }
  | { kind: 'receipt'; revision: number; feedback: MemoryFeedback };
type AvatarPicker = { id: string; editor: number };
type AvatarPoll = { sequence: number; key: string | undefined; visibility: number; picker: AvatarPicker };
const labels: Record<View, string> = { home: '我与管家', identity: '编辑资料', communication: '交流偏好', behavior: '做事偏好', memories: '个人记忆', memory: '编辑记忆', 'memory-new': '记住一件事', 'memory-settings': '全局记忆设置', 'memory-history': '过去的记忆' };
const languageOptions = [{ id: 'auto', label: '跟随对话' }, { id: 'zh-CN', label: '中文' }, { id: 'en', label: 'English' }, { id: 'ja', label: '日本語' }];
const detailOptions = [{ id: 'concise', label: '简短' }, { id: 'standard', label: '跟随问题' }, { id: 'detailed', label: '详细' }];
const paceOptions = [{ id: 'fast', label: '高效' }, { id: 'standard', label: '均衡' }, { id: 'careful', label: '细致' }];
const initiativeOptions = [{ id: 'requested', label: '按需' }, { id: 'balanced', label: '适度' }, { id: 'proactive', label: '主动建议' }];
const expiryOptions = [{ id: 'none', label: '不设到期时间' }, { id: '7', label: '7 天后到期' }, { id: '30', label: '30 天后到期' }, { id: 'custom', label: '选择日期与时间' }];
const emptyMemories = (): Memories => ({ current: [], candidates: [], pendingConflicts: [], history: [], complete: false });
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const rows = (value: any): Data[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const string = (value: any) => typeof value === 'string' ? value : '';
const privateProfileReadWindow = () => {
  const snapshot = bridge.getState(), view = object(snapshot.view), doc = bridge.getDocumentEpoch();
  return !document.hidden && doc > 0 && snapshot.host?.ready === true && view.section === 'profile' && view.taskId === null &&
    Number.isSafeInteger(view.resumeRevision) && Number.isSafeInteger(snapshot.state.memoryRefreshRevision)
    ? JSON.stringify([doc, view.resumeRevision, snapshot.state.memoryRefreshRevision]) : null;
};
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
const pendingSuggestion = (row: Data) => row.status === 'pending' && !expiredMemory(row);
const ownerMessageSources = (value: unknown): Data[] | null => Array.isArray(value) && value.length <= 20 &&
  value.every(ref => ref?.kind === 'message' && ref.ownerId === 'owner' && ref.spaceId === 'private' &&
    !!string(ref.sourceId) && Number.isSafeInteger(ref.revision) && ref.revision > 0) ? value : null;
function directSuggestionMessages(candidate: Data) {
  const sources = ownerMessageSources(candidate.sourceRefs);
  // The native getCandidate receipt preserves recordRefs for consolidations and assertion
  // reviews. Only the separate, original own-message family can take this shortcut.
  return pendingSuggestion(candidate) && candidate.assertion?.kind === 'user_said' && sources?.length &&
    !['recordRefs', 'identityParentIds', 'appKnowledge', 'archiveOnly'].some(key => Object.hasOwn(candidate, key)) ? sources : null;
}
function relatedPersonalMemory(candidate: Data, saved: Data) {
  const a = candidate.assertion, b = saved.assertion;
  const inputs = directSuggestionMessages(candidate), prior = ownerMessageSources(saved.sourceRefs);
  return inputs !== null && prior !== null && !inputs.some(ref => prior.some(other => other.sourceId === ref.sourceId)) &&
    Array.isArray(saved.sourceIds) && saved.sourceIds.length === 0 &&
    !['parentRevisions', 'identityParentIds', 'appKnowledge'].some(key => Object.hasOwn(saved, key)) &&
    !!string(saved.id) && Number.isSafeInteger(saved.revision) && saved.revision > 0 && !!string(saved.text).trim() &&
    saved.ownerId === 'owner' && saved.spaceId === 'private' && b?.subjectId === 'owner' && b?.subjectType === 'owner' &&
    saved.assertionStatus === 'current' && saved.archiveOnly !== true && !historicalMemory(saved) &&
    !!string(a?.predicate) && a.predicate === b.predicate && a.condition === b.condition;
}
function canSetPriority(selection: MemorySelection | null): selection is MemorySelection {
  const row = selection?.record;
  return !!selection && !selection.candidate && !selection.historical && row?.id === selection.id && row.ownerId === 'owner' && row.spaceId === 'private' &&
    row.assertion?.subjectId === 'owner' && row.assertion?.subjectType === 'owner' && row.assertionStatus === 'current' &&
    row.active === true && row.archiveOnly !== true && !historicalMemory(row) && typeof row.core === 'boolean';
}
const topicOf = (row: Data) => ({ id: string(row.topic?.id) || 'other', label: string(row.topic?.label) || '其他' });
type MemoryFindHit = { body: RegExpExecArray | null; via: string[] };
const memoryTerms = (query: string) => [...new Set(query.trim().split(/\s+/u).filter(Boolean))]
  .map(term => new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'iu'));
function findInMemoryRow(row: Data, terms: RegExp[]): MemoryFindHit | null {
  const body = string(row.text) || string(row.title);
  // Only the fields already in the personal directory. Never read an original to find a match.
  const details = [['标题', string(row.title)], ['主题', topicOf(row).label], ['来源说明', string(row.sourceSummary)]];
  let anchor: RegExpExecArray | null = null;
  const via = new Set<string>();
  for (const term of terms) {
    const match = term.exec(body);
    if (match) {
      if (!anchor || match[0].length > anchor[0].length) anchor = match;
    } else {
      const field = details.find(([, value]) => term.test(value));
      if (!field) return null;
      via.add(field[0]);
    }
  }
  return { body: anchor, via: [...via] };
}
function matchExcerpt(text: string, at: RegExpExecArray) {
  const match = at[0], end = at.index + match.length;
  // The match offsets refer to the original text, not a lowercased copy. Keep the excerpt short on phones.
  const before = Array.from(text.slice(Math.max(0, at.index - 26), at.index)).slice(-12).join('');
  const shown = Array.from(match.slice(0, 82)).slice(0, 40).join('');
  const after = Array.from(text.slice(end, end + 50)).slice(0, 24).join('');
  return <>{before.length < at.index ? '…' : ''}{before}<mark>{shown}{shown.length < match.length ? '…' : ''}</mark>{after}{end + after.length < text.length ? '…' : ''}</>;
}
function memoryDates(row: Data): string {
  const expiry = dateLabel(row.expiresAt), updated = dateLabel(row.updatedAt || row.createdAt);
  return [updated ? `${updated} 更新` : '', expiry ? `${expiredMemory(row) ? '已到期' : '有效至'} ${expiry}` : ''].filter(Boolean).join(' · ');
}
const retentionLabel = (value: unknown): string => value === null ? '不设到期时间' : typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && Number.isFinite(new Date(value).getTime()) ? new Date(value).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short' }) : '期限未核对';
function duplicateRetention(feedback: MemoryFeedback): string {
  const receipt = feedback.value;
  if (feedback.needsQuery || feedback.operation !== 'create' || receipt.status !== 'already_saved' || receipt.duplicate !== true || receipt.saved !== false) return '';
  const original = receipt.record || receipt.candidate;
  if (!string(original?.id)) return '';
  return `没有新建或更改期限。提交时${receipt.record ? '原记忆' : '待确认内容'}：${retentionLabel(original.expiresAt)}；本次选择：${retentionLabel(receipt.requestedExpiresAt)}。`;
}
function validShortenReceipt(receipt: Data, original: MemoryFeedback): boolean {
  const term = original.term;
  if (!term || receipt.receiptHandle !== original.value.receiptHandle || receipt.undoAvailable !== false) return false;
  if (receipt.status === 'unchanged') return receipt.saved === false && !!string(receipt.message);
  return receipt.status === 'saved' && receipt.saved === true && receipt.record?.id === term.id && receipt.record.revision === term.revision + 1 && receipt.record.expiresAt === term.until;
}
function validPriorityReceipt(receipt: Data, original: MemoryFeedback): boolean {
  const row = receipt.record, choice = original.priority;
  return original.operation === 'core' && !!choice && !!original.targetId && receipt.status === 'saved' && receipt.receiptHandle === original.value.receiptHandle && receipt.undoAvailable === false &&
    row?.id === original.targetId && row.ownerId === 'owner' && row.spaceId === 'private' && row.assertion?.subjectId === 'owner' &&
    row.assertion?.subjectType === 'owner' && row.revision === choice.revision + 1 && row.core === choice.core;
}
function validExpiredCandidateReceipt(receipt: Data, original: MemoryFeedback): boolean {
  const term = original.candidateTerm;
  return original.operation === 'accept' && !!original.targetId && !!term && receipt.status === 'expired_not_saved' &&
    receipt.receiptHandle === original.value.receiptHandle && receipt.saved === false && receipt.undoAvailable === false &&
    receipt.candidateId === original.targetId && receipt.candidateRevision === term.revision && receipt.expiresAt === term.expiresAt &&
    Number.isSafeInteger(receipt.checkedAt) && receipt.checkedAt >= term.expiresAt;
}
const removesFromList = (operation: MutationOperation): operation is Removal => operation === 'forget' || operation === 'reject';
function memoryReceiptPin(feedback: MemoryFeedback | null) {
  if (!feedback || feedback.needsQuery || !['create', 'accept', 'correct'].includes(feedback.operation)) return null;
  if (feedback.priorSave) return { ...feedback.priorSave, candidate: false };
  const receipt = feedback.value;
  const duplicate = feedback.operation !== 'correct' && receipt.status === 'already_saved' && receipt.duplicate === true && receipt.saved === false;
  if (!string(receipt.receiptHandle) || (receipt.status !== 'saved' && !duplicate)) return null;
  const candidate = duplicate && receipt.record == null;
  const row = object(candidate ? receipt.candidate : receipt.record);
  if (!string(row.id) || !Number.isSafeInteger(row.revision) || row.revision < 1 || row.ownerId !== 'owner' || row.spaceId !== 'private'
    || row.assertion?.subjectId !== 'owner' || row.assertion?.subjectType !== 'owner' || candidate && row.status !== 'pending'
    || feedback.operation === 'correct' && row.id !== feedback.targetId) return null;
  return { id: string(row.id), revision: Number(row.revision), candidate };
}
function confirmedManualSave(feedback: MemoryFeedback | null): ConfirmedManualSave | null {
  if (feedback?.operation !== 'create' || feedback.needsQuery || feedback.value.status !== 'saved' ||
      feedback.value.duplicate === true || feedback.value.saved === false) return null;
  const pin = memoryReceiptPin(feedback);
  return pin && !pin.candidate && pin.revision === 1 && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(pin.id)
    ? { id: pin.id, revision: 1 } : null;
}
function recoveredCorrectionPin(feedback: MemoryFeedback | null) {
  const submitted = feedback?.correction;
  if (!submitted || feedback?.operation !== 'correct' || feedback.value.status !== 'saved' ||
      feedback.value.undoAvailable !== false || !Number.isSafeInteger(submitted.beforeRevision) || submitted.beforeRevision < 1 ||
      !feedback.submittedText || submitted.draft.trim() !== feedback.submittedText || feedback.value.record?.text !== feedback.submittedText) return null;
  const pin = memoryReceiptPin(feedback);
  return pin && !pin.candidate && pin.revision === submitted.beforeRevision + 1 ? pin : null;
}
function confirmedAnswerForget(feedback: MemoryFeedback | null, entry?: AnswerMemoryForgetEntry): AnswerMemoryForgetReceipt | undefined {
  if (!entry || !feedback || feedback.answerForget !== entry || feedback.operation !== 'forget' || feedback.needsQuery ||
      feedback.targetId !== entry.id || !entry.current()) return;
  const receipt = feedback.value, removed = rows(receipt.removedRefs), ref = removed[0];
  if (receipt.status !== 'saved' || receipt.undoAvailable !== false || !string(receipt.receiptHandle) ||
      !Number.isSafeInteger(receipt.deleted) || receipt.deleted < 1 || removed.length !== 1 ||
      ref.recordId !== entry.id || ref.revision !== entry.revision || ref.ownerId !== 'owner' || ref.spaceId !== 'private') return;
  // The native receipt handle and original source refs stay on the Profile side.
  return { id: entry.id, revision: entry.revision };
}
function feedbackLabel(feedback: MemoryFeedback): string {
  if (feedback.priorSave) return '这条记忆先前已保存';
  if (feedback.needsQuery || feedback.value.status === 'unknown') return feedback.operation === 'forget' ? '这次遗忘的结果待核对' : '这次修改的结果待核对';
  if (feedback.value.status === 'expired_not_saved') return '这条建议已到期，本次没有保存为个人记忆';
  if (feedback.operation === 'create' && feedback.value.status === 'not_submitted' && feedback.value.reason === 'deadline_passed' && feedback.value.saved === false) return '所选期限已经过去，没有创建这条记忆';
  if (feedback.operation === 'shorten' && feedback.value.status === 'not_submitted') return '这次缩短期限尚未提交，原期限未改';
  if (feedback.operation === 'shorten' && feedback.value.status === 'unchanged') return string(feedback.value.message) || '原期限未改';
  if (feedback.operation === 'shorten' && feedback.value.status === 'saved') return `原记忆的有效期已缩短至 ${retentionLabel(feedback.value.record?.expiresAt)}`;
  if (feedback.operation === 'core' && feedback.value.status === 'saved') return feedback.value.record?.core === true ? '已设为优先参考' : '已恢复按相关性参考';
  if (feedback.value.status === 'settings_required') return string(feedback.value.message) || '这项默认偏好请在偏好设置中修改，本次没有写入记忆。';
  if (feedback.value.status === 'not_submitted') return '这次修改尚未提交，输入已保留，可以重新保存';
  if (feedback.value.status === 'already_saved' && feedback.value.duplicate === true && feedback.value.saved === false) {
    if (string(feedback.value.record?.id)) return '已有相同的个人记忆，本次没有重复保存';
    if (string(feedback.value.candidate?.id)) return '已有相同的待确认建议，本次没有保存为个人记忆';
  }
  if (feedback.value.status === 'reverted') return '已撤销这次保存';
  if (feedback.value.status === 'conflict') return '内容已有变化，请查看最新记忆';
  if (feedback.value.status !== 'saved') return '这次修改的结果待核对';
  if (feedback.operation === 'correct') {
    const pin = memoryReceiptPin(feedback);
    return pin ? `已更新这条记忆 · 第 ${pin.revision} 版` : '已更新这条记忆';
  }
  return feedback.operation === 'forget' ? '已忘记这条记忆' : feedback.operation === 'reject' ? '已忽略这条建议，本次未保存为记忆' : '已记住';
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
const manualMemory = (row: Data) => row.source === '主人主动添加' || row.sourceSummary === '主人主动添加' || row.formation === 'owner_added';
function memoryOrigin(row: Data, candidate = false): string {
  if (candidate) {
    switch (row.status) {
      case 'pending': return expiredMemory(row) ? '建议已到期' : '建议 · 待你决定是否保存';
      case 'accepted': return '建议已采纳';
      case 'rejected': return '建议已忽略';
      case 'invalidated': return '建议已失效';
      case 'archived': return '建议已归档';
      default: return '建议状态待核对';
    }
  }
  if (historicalMemory(row)) return expiredMemory(row) ? '已到期' : '已退出当前记忆';
  if (manualMemory(row)) return '你主动记录';
  if (['reviewed_learning', 'reviewed_consolidation'].includes(row.formation)) return '已采纳并保存';
  if (Number.isSafeInteger(row.revision) && row.revision > 0 && row.assertionReview?.revision === row.revision) return '经审阅保存';
  if (row.assertion?.kind === 'user_said') return '来自你的表达';
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
  const manualEntry = initialTarget?.view === 'memory-new' ? initialTarget.fromChat : undefined;
  const directVersion = initialTarget?.view === 'memory' && initialTarget.expectedRevision !== undefined;
  const originalAnswer = initialTarget?.view === 'memory' && initialTarget.expectedRevision !== undefined ? initialTarget.originalAnswer : undefined;
  const originalForget = initialTarget?.view === 'memory' ? initialTarget.originalForget : undefined;
  const [profile, setProfile] = useState<PersonalProfile | null>(null);
  const [savedReceipt, setSavedReceipt] = useState<{section: Section; revision: number} | null>(null);
  const [memories, setMemories] = useState<Memories>(emptyMemories);
  const [memoryError, setMemoryError] = useState(''), [memoryAvailable, setMemoryAvailable] = useState(false);
  const [loaded, setLoaded] = useState(false), [view, setView] = useState<View>(() => manualEntry ? 'memory-new' : directVersion ? 'memory' : 'home');
  const [enteredProfile, setEnteredProfile] = useState(false), [entryAttempt, setEntryAttempt] = useState(0);
  const [editor, setEditor] = useState<Editor | null>(null), [selection, setSelection] = useState<MemorySelection | null>(null);
  const [review, setReview] = useState<MemoryReview | CreateTermReview | null>(null);
  const [pending, setPending] = useState(''), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [avatarPicking, setAvatarPicking] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false), [memoryConflict, setMemoryConflict] = useState(false), [uncertainMemory, setMemoryUnknown] = useState(false);
  const [latestMemory, setLatestMemory] = useState<Data | null>(null);
  const [query, setQuery] = useState(''), [topic, setTopic] = useState('all'), [showCandidates, setShowCandidates] = useState(false);
  const [newMemoryText, setNewMemoryText] = useState(''), [newMemoryExpiry, setNewMemoryExpiry] = useState('none'), [createUnknown, setCreateUnknown] = useState(false);
  const [newMemoryCalendar, setNewMemoryCalendar] = useState<CalendarTime | null>(null);
  const [policy, setPolicy] = useState<MemoryPolicy | null>(null), [controlsError, setControlsError] = useState('');
  const [agentPreferences, setAgentPreferences] = useState<AgentPreferenceControl | null>(null);
  const [feedback, setFeedback] = useState<MemoryFeedback | null>(null);
  const pendingForget = !!selection && !selection.candidate && feedback?.operation === 'forget' && feedback.targetId === selection.id &&
    (feedback.needsQuery || feedback.value.status === 'unknown');
  const memoryUnknown = uncertainMemory || pendingForget;
  const [duties, setDuties] = useState<Data | null>(null), [dutiesReading, setDutiesReading] = useState(false), [dutiesError, setDutiesError] = useState('');
  const [dutiesAttempt, setDutiesAttempt] = useState(0);
  const alive = useRef(true), busy = useRef(false), runRef = useRef(run), supportedRef = useRef(supported);
  const readHandle = useRef(''), reviewHandle = useRef(''), pendingLeave = useRef<(() => void) | null>(null);
  const avatarPicker = useRef<AvatarPicker | null>(null), editorInstance = useRef(0);
  const avatarPoll = useRef<AvatarPoll | null>(null), avatarPollSequence = useRef(0), avatarVisibility = useRef(0);
  const avatarWaitingForPresentation = useRef<{ key: string } | null>(null), wakeAvatar = useRef<(() => void) | null>(null);
  const current = useRef({ view, editor, selection, profile, review, leaveOpen, memoryUnknown, newMemoryText, newMemoryExpiry, newMemoryCalendar, createUnknown, feedback, conversation: string(state.conversationId) });
  current.current = { view, editor, selection, profile, review, leaveOpen, memoryUnknown, newMemoryText, newMemoryExpiry, newMemoryCalendar, createUnknown, feedback, conversation: string(state.conversationId) };
  runRef.current = run; supportedRef.current = supported;
  const scroller = useRef<HTMLDivElement>(null), heading = useRef<HTMLHeadingElement>(null);
  const positions = useRef(new Map<string, number>()), returnMemory = useRef('');
  const previousScrollLocation = useRef<{ view: View; savedId: string }>({ view: 'home', savedId: '' });
  const memoryScrollOwner = useRef('');
  const relatedFocus = useRef<{ id: string; scrollTop: number; relatedId?: string } | null>(null);
  const memoryParent = useRef<'home' | 'memories' | 'memory-history'>('memories');
  const handledTarget = useRef(''), targetArrival = useRef({ key: '', conversation: '' });
  const directEntry = useRef<{ target: PersonalTarget; conversation: string } | null>(null), preferenceFromMemory = useRef(false);
  const previousPresentation = useRef(presentationKey);
  const livePresentation = useRef(presentationKey);
  livePresentation.current = presentationKey;
  const liveMemoryRevision = useRef(state.memoryRefreshRevision);
  liveMemoryRevision.current = state.memoryRefreshRevision;
  useSurfaceEnter(scroller, JSON.stringify([view, view === 'memory' ? selection?.id : null]), {selector: ':scope > .pw-profile-content', depth: view === 'home' ? 0 : ['memory', 'memory-new', 'memory-settings'].includes(view) ? 2 : 1, active: loaded && enteredProfile});
  const canEnter = supported('connections.enter'), canRead = supported('profile.snapshot'), canSave = supported('profile.save');
  const locked = !!pending;
  const formLocked = locked || avatarPicking || !canSave;
  const pendingSavedRead = !!editor && !!savedReceipt && editor.section===savedReceipt.section && !!profile && profile.revision < savedReceipt.revision;
  const profileConflict = !!editor && !!profile && editor.original.revision !== profile.revision && !sameSection(editor.original, profile, editor.section);
  const pendingUntil = selection?.candidate && selection.record.status === 'pending' ? selection.record.expiresAt : null;
  const expiredCandidate = usePendingCandidateDeadline(Number.isSafeInteger(pendingUntil) && pendingUntil > 0 ? pendingUntil : 0);
  const pendingCandidates = memories.candidates.filter(row => row.status === 'pending');
  const activeMemories = memories.current.filter(row => !historicalMemory(row));
  const searchText = query.trim();
  const terms = useMemo(() => memoryTerms(query), [query]);
  const hits = new Map<Data, MemoryFindHit>();
  const searched = (row: Data) => {
    if (!terms.length) return true;
    const hit = findInMemoryRow(row, terms);
    if (hit) hits.set(row, hit);
    return hit !== null;
  };
  const matchingMemories = activeMemories.filter(searched);
  const visibleMemories = topic === 'all' ? matchingMemories : matchingMemories.filter(row => topicOf(row).id === topic);
  const topics = [...new Map(activeMemories.map(row => [topicOf(row).id, topicOf(row)])).values()];
  const visibleCandidates = pendingCandidates.filter(searched);
  const historyRows = memories.history.filter(searched);
  const hasMemoryRows = activeMemories.length + pendingCandidates.length + memories.history.length > 0;
  const savedMiss = memoryAvailable && !showCandidates && !visibleMemories.length && (!!searchText || topic !== 'all');
  const candidateMatchLink = savedMiss && !!searchText && visibleCandidates.length > 0;
  const historyMatchLink = savedMiss && !!searchText && historyRows.length > 0;
  useCollectionMotion(scroller, JSON.stringify([presentationKey, view, searchText, topic, showCandidates]), JSON.stringify([
    visibleMemories.map(row => [row.id, row.revision, topicOf(row).id]),
    visibleCandidates.map(row => [row.id, row.status]), historyRows.map(row => [row.id, row.revision]),
    notice, error, feedback?.value.status, feedback?.needsQuery,
  ]), { active: loaded && enteredProfile && memoryAvailable });

  const closeHandle = (handle: string) => { if (handle && (!manualEntry || manualEntry.current()) && supportedRef.current('profile.memory.close')) void runRef.current('profile.memory.close', { handle }).catch(() => {}); };
  const closeAvatar = () => {
    const picker = avatarPicker.current; avatarPicker.current = null;
    avatarPoll.current = null; avatarWaitingForPresentation.current = null;
    if (alive.current) setAvatarPicking(false);
    if (picker) void runRef.current('profile.avatar.close', { pickerId: picker.id }).catch(() => {});
  };
  const clearReview = () => { closeHandle(reviewHandle.current); reviewHandle.current = ''; setReview(null); };
  const clearSelection = () => { clearReview(); closeHandle(readHandle.current); readHandle.current = ''; setSelection(null); setMemoryConflict(false); setMemoryUnknown(false); setLatestMemory(null); };
  const action = async (name: string, body: () => Promise<void>) => {
    if (busy.current || !alive.current) return;
    busy.current = true; setPending(name); setError(''); setNotice('');
    try { await body(); }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : '操作未完成，请重试。'); }
    finally { busy.current = false; if (alive.current) setPending(''); }
  };
  const call = async (name: string, args: Record<string, unknown> = {}) => {
    if (manualEntry && (!manualEntry.current() || !['profile.memory.createReview', 'profile.memory.confirm', 'profile.memory.close', 'profile.memory.query'].includes(name)))
      throw new Error('原私人对话窗口已变化。没有继续保存，也没有打开其他记忆。');
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
  const rememberReceipt = (value: Data, operation: MutationOperation, needsQuery = false, targetId?: string, submittedText?: string, correction?: CorrectionSubmission) => {
    if (!alive.current || !string(value.receiptHandle)) return;
    setFeedback({ value, operation, needsQuery, targetId, submittedText, correction });
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
    if (canEnter) void action(manualEntry ? '正在打开记忆表单' : '正在打开个人资料', async () => {
      if (manualEntry) await manualEntry.enter();
      else await runRef.current('connections.enter', { section: 'profile' });
      if (active && alive.current) setEnteredProfile(true);
    });
    return () => {
      active = false; alive.current = false;
      if (manualEntry) manualEntry.dispose();
      else {
        closeAvatar(); closeHandle(readHandle.current); closeHandle(reviewHandle.current);
        if (canEnter) void runRef.current('connections.leave', { section: 'profile' }).catch(() => {});
      }
      readHandle.current = ''; reviewHandle.current = ''; pendingLeave.current = null;
    };
  }, [canEnter, entryAttempt, manualEntry]);
  useEffect(() => {
    // Scoped entries never open the memory catalogue.
    if (!manualEntry && !originalAnswer && enteredProfile && canRead && !loaded && !busy.current) void action('正在读取资料', async () => { await readSnapshot(); });
  }, [manualEntry, originalAnswer, enteredProfile, canRead, loaded]);
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
  useLayoutEffect(() => {
    const previous = previousPresentation.current;
    previousPresentation.current = presentationKey;
    if (previous === undefined || previous === presentationKey) return;
    const saved = confirmedManualSave(current.current.feedback);
    setFeedback(old => {
      if (!old || old.priorSave) return old;
      const pin = confirmedManualSave(old);
      // A native window change invalidates query and undo handles, not an already confirmed write.
      // Keep only the exact ID and revision from the receipt, never its old body or lease.
      return pin ? { operation: 'create', value: { status: 'saved', undoAvailable: false }, needsQuery: false, priorSave: pin }
        : { ...old, needsQuery: true };
    });
    const selected = current.current.selection;
    if (saved) {
      const different = (row: Data) => row.id !== saved.id || row.revision !== saved.revision;
      setMemories(old => ({ ...old, complete: false, current: old.current.filter(different), history: old.history.filter(different), pendingConflicts: old.pendingConflicts.filter(different) }));
      if (current.current.view === 'memory' && selected && !selected.candidate && selected.id === saved.id &&
          selected.record.revision === saved.revision && !memoryDirty(selected)) {
        readHandle.current = ''; reviewHandle.current = '';
        clearSelection(); go('memories'); return;
      }
    }
    if (!selected) { if (current.current.review?.operation === 'createAt') clearReview(); return; }
    // Native background/task transitions retire these exact read/review handles.
    // Keep the user's text, but require a fresh read before any later mutation.
    clearReview(); closeHandle(readHandle.current); readHandle.current = '';
    setLatestMemory(null); setMemoryConflict(true);
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
    const { view: from, selection: selected } = current.current;
    positions.current.set(from, scroller.current?.scrollTop || 0);
    if (from === 'memory') memoryScrollOwner.current = selected && !selected.candidate ? selected.id : '';
    setView(next); setError(''); setNotice('');
  };
  const savedScrollId = view === 'memory' && selection && !selection.candidate ? selection.id : '';
  useLayoutEffect(() => {
    const previous = previousScrollLocation.current;
    previousScrollLocation.current = { view, savedId: savedScrollId };
    const focus = relatedFocus.current;
    if (focus && view === 'memory' && selection?.id === focus.id) {
      if (locked) return;
      relatedFocus.current = null;
      const node = scroller.current;
      if (node) node.scrollTop = focus.scrollTop;
      const trigger = focus.relatedId ? Array.from(node?.querySelectorAll<HTMLElement>('[data-related-memory]') || [])
        .find(item => item.dataset.relatedMemory === focus.relatedId) : null;
      (trigger || heading.current)?.focus({ preventScroll: true });
      return;
    }
    if (previous.view === view && (!savedScrollId || previous.savedId === savedScrollId)) return;
    const node = scroller.current;
    // A saved detail never inherits the scroll offset of a different record or suggestion.
    if (node) node.scrollTop = savedScrollId && memoryScrollOwner.current !== savedScrollId ? 0 : positions.current.get(view) || 0;
    const returnTarget = (view === 'memories' || view === 'memory-history') && returnMemory.current ? Array.from(node?.querySelectorAll<HTMLElement>('[data-personal-memory]') || []).find(item => item.dataset.personalMemory === returnMemory.current) : null;
    (returnTarget || heading.current)?.focus({ preventScroll: true });
  }, [view, savedScrollId, selection?.id, locked]);

  const beforeLeave: LeaveGuard = next => {
    if (busy.current) { setNotice('正在保存或读取，请稍候。'); return true; }
    closeAvatar();
    if (current.current.review) { clearReview(); current.current = { ...current.current, review: null }; }
    const { selection: currentSelection, feedback: currentFeedback } = current.current;
    const recovered = recoveredCorrectionPin(currentFeedback);
    const alreadySavedDraft = !!recovered && !!currentSelection && currentSelection.id === recovered.id && !currentSelection.candidate &&
      currentSelection.record.revision === currentFeedback?.correction?.beforeRevision && currentSelection.draft === currentFeedback?.correction?.draft;
    if (profileDirty(current.current.editor) || (memoryDirty(currentSelection) && !alreadySavedDraft) || current.current.newMemoryText.trim() || current.current.newMemoryExpiry !== 'none') { pendingLeave.current = next; setLeaveOpen(true); return true; }
    return false;
  };
  const guarded = (next: () => void) => { if (!beforeLeave(next)) next(); };
  const atDirectEntry = () => {
    const target = directEntry.current?.target ?? (directVersion || manualEntry ? initialTarget : undefined), here = current.current;
    if (!target || target.view === 'home') return false;
    if (target.view !== 'memory') return here.view === target.view;
    // A completed review may already have returned to the saved list.
    return here.view === 'memories' || here.view === 'memory' && (here.selection
      ? here.selection.id === target.id && here.selection.candidate === target.candidate : target.expectedRevision !== undefined);
  };
  const unresolvedDirectExit = () => {
    const { selection, memoryUnknown, feedback } = current.current;
    if (selection?.fromSuggestion) return memoryUnknown || (!!feedback && feedback.targetId === selection.id &&
      (feedback.needsQuery || feedback.value.status === 'unknown'));
    return atDirectEntry() && (memoryUnknown || feedback?.needsQuery || feedback?.value.status === 'unknown');
  };
  const leaveToParent = () => {
    const selected = current.current.selection;
    if (selected?.fromSuggestion) { returnToSuggestion(selected, selected.fromSuggestion); return; }
    if (atDirectEntry()) {
      // The shell owns the originating route. Never search for or select another conversation.
      if (directEntry.current && directEntry.current.conversation !== current.current.conversation) {
        directEntry.current = null; clearSelection(); setEditor(null); go('home'); setNotice('进入时的对话已变化，未打开其他对话。'); return;
      }
      if (manualEntry) manualEntry.back(confirmedManualSave(current.current.feedback) ?? undefined);
      else onBack(confirmedAnswerForget(current.current.feedback, originalForget));
      return;
    }
    if (current.current.editor && preferenceFromMemory.current) { preferenceFromMemory.current = false; setEditor(null); go('memories'); return; }
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
    if (current.current.view === 'memories' && showCandidates) { setShowCandidates(false); return true; }
    if (unresolvedDirectExit()) { pendingLeave.current = leaveToParent; setLeaveOpen(true); return true; }
    if (current.current.view === 'home') return false;
    leaveToParent(); return true;
  };
  const backRef = useRef(back), leaveRef = useRef(beforeLeave);
  backRef.current = back; leaveRef.current = beforeLeave;
  useEffect(() => { registerBack(() => backRef.current()); registerBeforeLeave(next => leaveRef.current(next)); return () => { registerBack(null); registerBeforeLeave(null); }; }, [registerBack, registerBeforeLeave]);
  const requestBack = () => { if (!backRef.current()) onBack(); };
  const openEditor = (section: Section) => {
    if (!profile || !loaded || busy.current) return;
    guarded(() => {
      preferenceFromMemory.current = ['memories', 'memory', 'memory-new', 'memory-history'].includes(current.current.view);
      editorInstance.current++; clearSelection(); setEditor({ section, original: profile, draft: { ...profile } }); go(section);
    });
  };
  const change = (key: keyof PersonalProfile, value: string | boolean) => {
    if (busy.current) return;
    setEditor(old => old ? { ...old, draft: { ...old.draft, [key]: value } } : old); setNotice(''); setError('');
  };
  const saveProfile = () => {
    const frozen = current.current.editor;
    if (!frozen || !profileDirty(frozen) || !canSave || profileConflict || pendingSavedRead || avatarPicker.current) return;
    const values = sectionValues(frozen.draft, frozen.section);
    void action('正在保存', async () => {
      const result = object(await call('profile.save', { expectedRevision: frozen.original.revision, section: frozen.section, values }));
      if (!alive.current) return;
      if (result.format==='pocketworld.profile-save-receipt.v1' && result.profileIncluded===false && !('profile' in result) &&
          result.saved===true && result.persisted===true && Number.isSafeInteger(result.profileRevision) && result.profileRevision===frozen.original.revision+1) {
        setSavedReceipt({section:frozen.section,revision:result.profileRevision});
        setNotice('原请求已保存，没有重复写入。这张回执不含当前资料。');return;
      }
      const saved = profileOf(result.profile);
      if (result.saved !== true || result.persisted !== true || !saved || saved.revision !== frozen.original.revision + 1) {
        if (saved) acceptProfile(saved);
        throw new Error(string(result.message || result.error) || '保存尚未确认，当前输入已保留。请读取最新资料后核对。');
      }
      setSavedReceipt(null); setProfile(saved); setEditor({ section: frozen.section, original: saved, draft: { ...saved } });
      // The settings store wrote only this section under its original revision; its chat-write toggle did not change.
      setAgentPreferences(prior=>prior?.revision===frozen.original.revision?{...prior,revision:saved.revision}:null);
      setNotice(frozen.section==='identity'?'已保存个人资料':'已保存为默认偏好；允许参考这些设置的私人对话可使用。');
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

  const readMemory = async (id: string, candidate: boolean, exact?: { revision: number; conversation: OriginalMemoryConversation }) => {
    const value = object(await call('profile.memory.get', { id, candidate,
      ...(exact ? { expectedRevision: exact.revision, originalConversation: exact.conversation } : {}) }));
    const row = object(candidate ? value.candidate : value.record);
    if (row.id !== id || !string(value.readHandle) || value.candidateSelected !== candidate || row.ownerId !== 'owner' || row.spaceId !== 'private'
      || row.assertion?.subjectId !== 'owner' || row.assertion?.subjectType !== 'owner' || !Number.isSafeInteger(row.revision) || row.revision < 1) {
      closeHandle(string(value.readHandle));
      throw new Error('这条记忆已经变化，请返回列表后重新打开。');
    }
    return { value, row, handle: string(value.readHandle) };
  };
  const suggestionWindow = (visit: SuggestionMemoryVisit, from: MemorySelection) => alive.current && !document.hidden &&
    current.current.view === 'memory' && current.current.selection === from &&
    livePresentation.current === visit.presentation && current.current.conversation === visit.conversation;
  const showComparisonRead = (result: Awaited<ReturnType<typeof readMemory>>, candidate: boolean, fromSuggestion?: SuggestionMemoryVisit) => {
    clearSelection(); readHandle.current = result.handle;
    const text = string(result.row.text);
    setSelection({ id: result.row.id, candidate, value: result.value, record: result.row, originalText: text, draft: text, fromSuggestion });
  };
  const openRelatedMemory = (row: Data) => {
    const from = current.current.selection, originalHandle = readHandle.current;
    if (!from?.candidate || !originalHandle || !pendingSuggestion(from.record) || memoryConflict || memoryUnknown ||
        candidateResultUnclear || !relatedPersonalMemory(from.record, row) ||
        rows(from.value.relatedRecords).filter(item => item.id === row.id).length !== 1) return;
    const visit: SuggestionMemoryVisit = {
      candidateId: from.id, revision: from.record.revision, relatedId: row.id, presentation: livePresentation.current,
      conversation: current.current.conversation, scrollTop: scroller.current?.scrollTop || 0,
    };
    const readRevision = liveMemoryRevision.current;
    const still = () => suggestionWindow(visit, from) && readHandle.current === originalHandle &&
      liveMemoryRevision.current === readRevision && pendingSuggestion(from.record);
    void action('正在核对已保存的记忆', async () => {
      if (!still()) return;
      const result = await readMemory(row.id, false).catch(() => null);
      if (!still() || !result || result.row.revision !== row.revision || result.row.text !== row.text ||
          result.row.active !== true || !relatedPersonalMemory(from.record, result.row)) {
        if (result) closeHandle(result.handle);
        if (suggestionWindow(visit, from)) throw new Error('暂不能核对这条已保存的记忆，未打开其他版本。请重新读取建议。');
        return;
      }
      relatedFocus.current = { id: row.id, scrollTop: 0 };
      showComparisonRead(result, false, visit);
    });
  };
  const returnToSuggestion = (from: MemorySelection, visit: SuggestionMemoryVisit) => {
    const list = () => {
      clearSelection(); returnMemory.current = visit.candidateId; setShowCandidates(true); go('memories');
      // Do not redisplay the unchecked old suggestion in the cached list either.
      setMemories(old => ({ ...old, complete: false, candidates: old.candidates.filter(row => row.id !== visit.candidateId) }));
      setNotice('原建议已变化或暂不能核对，没有打开其他建议。可刷新待确认清单。');
    };
    if (!suggestionWindow(visit, from)) { list(); return; }
    const readRevision = liveMemoryRevision.current;
    void action('正在重新核对这条记忆建议', async () => {
      const result = await readMemory(visit.candidateId, true).catch(() => null);
      if (!suggestionWindow(visit, from)) { if (result) closeHandle(result.handle); return; }
      if (!result || liveMemoryRevision.current !== readRevision || result.row.revision !== visit.revision || !directSuggestionMessages(result.row)) {
        if (result) closeHandle(result.handle);
        list(); return;
      }
      relatedFocus.current = { id: visit.candidateId, relatedId: visit.relatedId, scrollTop: visit.scrollTop };
      showComparisonRead(result, true);
    });
  };
  const openMemory = (row: Data, candidate: boolean, expected?: MemoryOpenPin) => {
    if (!string(row.id)) return;
    const receipt = expected?.kind === 'receipt' ? expected.feedback : null;
    const exact = expected?.kind === 'answer' && expected.originalAnswer
      ? { revision: expected.revision, conversation: expected.originalAnswer.conversation, current: expected.originalAnswer.current } : undefined;
    const presentation = livePresentation.current, memoryRevision = liveMemoryRevision.current;
    const conversation = current.current.conversation, origin = current.current.view;
    const priorSave = receipt?.priorSave, privateWindow = priorSave ? privateProfileReadWindow() : null;
    const unavailable = receipt ? '回执指向的原版本已经变化或暂时不可读，没有打开其他记忆。可从列表查看当前内容。'
      : '未能核对原回答选中的记忆版本，没有打开其他内容。请返回原回答。';
    const sameWindow = () => !document.hidden && (expected?.kind === 'receipt'
      ? previousPresentation.current === presentation && livePresentation.current === presentation
        && liveMemoryRevision.current === memoryRevision && current.current.conversation === conversation && current.current.feedback === receipt
        && (!priorSave || privateWindow !== null && privateProfileReadWindow() === privateWindow && current.current.view === origin)
      : expected?.conversation === current.current.conversation && (!exact || exact.current()));
    void action('正在读取记忆', async () => {
      if (expected?.kind === 'answer') {
        clearSelection(); setEditor(null); go('memory');
        if (candidate || !Number.isSafeInteger(expected.revision) || expected.revision < 1 || !expected.conversation || !sameWindow()) throw new Error(unavailable);
      } else if (receipt) {
        const pin = memoryReceiptPin(receipt);
        if (!pin || pin.id !== row.id || pin.candidate !== candidate || pin.revision !== expected?.revision || !sameWindow()) throw new Error(unavailable);
      }
      const result = await readMemory(row.id, candidate, exact).catch(failure => { throw expected ? new Error(unavailable) : failure; });
      if (!alive.current) { closeHandle(result.handle); return; }
      // Neither a later revision nor a retired row may substitute for an original receipt or answer.
      if (expected && (result.row.revision !== expected.revision || !sameWindow() || (candidate
        ? result.row.status !== 'pending' || expiredMemory(result.row)
        : result.row.active !== true || historicalMemory(result.row) ||
          !!priorSave && (result.row.archiveOnly === true || result.row.assertionStatus !== 'current')))) {
        closeHandle(result.handle); throw new Error(unavailable);
      }
      clearSelection(); readHandle.current = result.handle; returnMemory.current = row.id;
      if (current.current.view !== 'memory') memoryParent.current = current.current.view === 'home' ? 'home' : current.current.view === 'memory-history' ? 'memory-history' : 'memories';
      const historical = !expected && !candidate && (historicalMemory(result.row) || memories.history.some(item => item.id === row.id) && !memories.current.some(item => item.id === row.id));
      setSelection({ id: row.id, candidate, value: result.value, record: result.row, originalText: string(result.row.text), draft: string(result.row.text), historical });
      if (exact) setMemoryAvailable(true);
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
  const enterMemorySettings = () => {
    clearSelection(); setEditor(null); go('memory-settings');
    void action('正在读取记忆设置', async () => {
      try { await readControls(); }
      catch (failure) { if (alive.current) setControlsError(failure instanceof Error ? failure.message : '记忆设置暂不可用。'); throw failure; }
    });
  };
  const openMemorySettings = () => guarded(enterMemorySettings);
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
        setNotice(kind === 'reading'
          ? enabled ? '全局已允许参考已保存的记忆。已暂停的对话仍保持暂停。' : '全局已暂停参考已保存的记忆。保存的内容仍可查看和编辑。'
          : enabled ? '全局已允许整理待确认候选。各对话的暂停和原话排除设置不变。' : '全局已暂停整理新的记忆候选。');
      } catch (failure) {
        try { await readControls(); } catch { if (alive.current) setControlsError('请重新读取记忆设置后继续。'); }
        throw new Error((failure instanceof Error ? failure.message : '设置保存结果待核对。') + ' 已尝试核对当前状态，没有重复保存。');
      }
    });
  };
  const openNewMemory = () => guarded(() => {
    clearSelection(); setEditor(null); setNewMemoryText(''); setNewMemoryExpiry('none'); setNewMemoryCalendar(null); setCreateUnknown(false); setFeedback(null); go('memory-new');
  });
  const returnFromCreate = (receipt: Data) => {
    setNewMemoryText(''); setNewMemoryExpiry('none'); setCreateUnknown(false);
    if (manualEntry) return;
    // Saving from the suggestions tab must return to formal memory, not the old tab.
    setShowCandidates(receipt.status === 'already_saved' && receipt.duplicate === true && receipt.saved === false
      && !receipt.record && !!string(receipt.candidate?.id) && receipt.candidate.status === 'pending');
    go('memories');
  };
  const submitNewMemory = async (text: string, expiresAt: number | null, prepared: Data) => {
      let sent = false, saved = false, duplicate = false;
      try {
        if (!alive.current) { closeHandle(prepared.reviewHandle); return; }
        if (manualEntry && !manualEntry.current()) {
          closeHandle(prepared.reviewHandle);
          throw new Error('原私人对话窗口已变化。输入保留，没有提交。');
        }
        rememberReceipt({ receiptHandle: prepared.receiptHandle, undoAvailable: false }, 'create', true, undefined, text);
        sent = true;
        const result = object(await call('profile.memory.confirm', { reviewHandle: prepared.reviewHandle }));
        if (manualEntry && !manualEntry.current()) throw new Error('原窗口已结束，不能在这里核对这次保存。');
        if (result.status === 'not_submitted' && result.receiptHandle === prepared.receiptHandle && result.saved === false && result.undoAvailable === false && result.reason === 'deadline_passed' && result.expiresAt === expiresAt) {
          if (alive.current) { rememberReceipt(result, 'create', false, undefined, text); setCreateUnknown(false); setNotice('选择的时刻已过，没有保存，请重新选择。'); }
          return;
        }
        if (settlePreferenceReceipt(result, prepared.receiptHandle, 'create', undefined, text)) return;
        if (result.duplicate === true && result.status === 'already_saved') {
          if (result.receiptHandle !== prepared.receiptHandle || result.saved !== false || result.requestedExpiresAt !== expiresAt || !string((result.record || result.candidate)?.id)) throw new Error('相同内容的原回执尚未完整核对。');
          saved = true; duplicate = true;
          if (!alive.current) return;
          rememberReceipt({ ...result, receiptHandle: result.receiptHandle || prepared.receiptHandle, undoAvailable: false }, 'create');
          returnFromCreate(result);
          if (!manualEntry) await readSnapshot();
          return;
        }
        if (result.status !== 'saved' || result.receiptHandle !== prepared.receiptHandle || !string(result.record?.id) || result.record?.text !== text || result.record?.expiresAt !== expiresAt || !Number.isSafeInteger(result.record?.revision) || result.record.revision < 1) throw new Error('记忆保存结果尚未确认。');
        if (manualEntry && !confirmedManualSave({ value: result, operation: 'create', needsQuery: false }))
          throw new Error('尚未核对到这次保存的原记忆。');
        saved = true;
        if (!alive.current) return;
        rememberReceipt(result, 'create');
        returnFromCreate(result);
        if (!manualEntry) await readSnapshot();
      } catch (failure) {
        if (alive.current && sent && !saved) setCreateUnknown(true);
        throw new Error(saved ? duplicate ? '已有相同内容，列表暂未完整刷新。' : '记忆已保存，列表暂未完整刷新。' : (failure instanceof Error ? failure.message : '保存暂未完成。') + (sent ? ' 请查询这次保存的结果。' : ''));
      }
  };
  const saveNewMemory = () => {
    const original = current.current, text = original.newMemoryText.trim(), expiry = original.newMemoryExpiry, calendar = original.newMemoryCalendar, window = livePresentation.current;
    if (original.view !== 'memory-new' || manualEntry && (!manualEntry.current() || !!confirmedManualSave(original.feedback)) ||
        !text || original.createUnknown || busy.current || settingsRequiredFor(original.feedback, 'create', undefined, text) ||
        !expiryOptions.some(x => x.id === expiry) || expiry === 'custom' && (!calendar || !validCalendarTime(calendar))) return;
    void action(expiry === 'custom' ? '正在核对保存期限' : '正在保存记忆', async () => {
      const relative = expiry === '7' || expiry === '30' ? Date.now() + Number(expiry) * 86400000 : null;
      const prepared = object(await call('profile.memory.createReview', expiry === 'custom' ? { text, calendar } : { text, expiresAt: relative }));
      const handle = string(prepared.reviewHandle), latest = current.current;
      if (!alive.current || manualEntry && !manualEntry.current() || livePresentation.current !== window || latest.view !== 'memory-new' || latest.newMemoryText.trim() !== text || latest.newMemoryExpiry !== expiry || expiry === 'custom' && latest.newMemoryCalendar !== calendar) { closeHandle(handle); return; }
      if (!handle || prepared.receiptHandle !== handle || prepared.operation !== 'create' || prepared.afterText !== text) { closeHandle(handle); throw new Error('待保存内容尚未完整核对，输入已保留。'); }
      if (expiry === 'custom' && calendar) {
        try {
          if (prepared.zone !== calendar.zone || !Number.isSafeInteger(prepared.expiresAt) || !(Math.min(prepared.expiresAt, prepared.reviewExpiresAt) > Date.now())) throw new Error();
          const actual = calendarTimeAt(prepared.expiresAt, prepared.zone);
          if (actual.date !== calendar.date || actual.time !== calendar.time) throw new Error();
        } catch { closeHandle(handle); throw new Error('选定的日期或时区没有核对一致，没有保存。'); }
        clearReview(); reviewHandle.current = handle; setReview({ value: prepared, operation: 'createAt', calendar, presentation: window }); return;
      }
      if (prepared.expiresAt !== relative) { closeHandle(handle); throw new Error('原期限没有核对一致，没有保存。'); }
      await submitNewMemory(text, relative, prepared);
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
    const text = frozen.draft.trim(), presentation = livePresentation.current;
    const sameEditor = () => alive.current && !document.hidden && livePresentation.current === presentation && current.current.selection?.id === frozen.id;
    const holdOriginal = () => { if (alive.current && current.current.selection?.id === frozen.id) setMemoryUnknown(true); };
    void action('正在保存记忆', async () => {
      let sent = false, saved = false, readBack = false;
      try {
        const prepared = await checkedReview('correct', frozen, { text });
        if (typeof prepared.value.afterText === 'string' && prepared.value.afterText !== text) { closeHandle(prepared.handle); throw new Error('待保存内容与当前输入不同，请重新读取这条记忆。'); }
        if (!alive.current) { closeHandle(prepared.handle); return; }
        rememberReceipt({ receiptHandle: prepared.value.receiptHandle, undoAvailable: false }, 'correct', true, frozen.id, text,
          { beforeRevision: frozen.record.revision, draft: frozen.draft });
        sent = true;
        const receipt = object(await call('profile.memory.confirm', { reviewHandle: prepared.handle }));
        if (settlePreferenceReceipt(receipt, prepared.value.receiptHandle, 'correct', frozen.id, text)) return;
        if (receipt.status !== 'saved' || receipt.receiptHandle !== prepared.value.receiptHandle || receipt.record?.id !== frozen.id || receipt.record?.text !== text ||
            !Number.isSafeInteger(receipt.record?.revision) || receipt.record.revision !== frozen.record.revision + 1) throw new Error('记忆保存回执尚未确认。');
        saved = true;
        if (!sameEditor()) { holdOriginal(); return; } // Query the submitted operation rather than reading into a new presentation.
        rememberReceipt(receipt, 'correct', false, frozen.id);
        hideObsoleteMemory(frozen.id, receipt.record.revision);
        const prior = readHandle.current; readHandle.current = ''; closeHandle(prior);
        setSelection({ ...frozen, record: receipt.record, originalText: text, draft: text });
        const latest = await readMemory(frozen.id, frozen.candidate);
        if (!sameEditor()) { closeHandle(latest.handle); holdOriginal(); return; }
        if (latest.row.revision < receipt.record.revision || latest.row.revision === receipt.record.revision && latest.row.text !== text) {
          closeHandle(latest.handle); throw new Error('未核对到回执对应的版本。');
        }
        readHandle.current = latest.handle;
        if (latest.row.revision > receipt.record.revision) {
          setLatestMemory({ value: latest.value, record: latest.row }); setMemoryConflict(true);
          setNotice(`这次已保存第 ${receipt.record.revision} 版；同一条记忆现在有第 ${latest.row.revision} 版，没有自动采用。`); return;
        }
        setSelection({ ...frozen, value: latest.value, record: latest.row, originalText: text, draft: text, historical: historicalMemory(latest.row) });
        setMemoryConflict(false); setMemoryUnknown(false); setLatestMemory(null);
        readBack = true;
        await readSnapshot();
      } catch (failure) {
        if (!sameEditor()) { if (sent && !readBack) holdOriginal(); return; }
        if (sent && !saved) setMemoryUnknown(true); else if (!readBack) setMemoryConflict(true);
        throw new Error(readBack ? '这条记忆的更新已核对；搜索列表暂未完整刷新。' : saved ? '记忆已保存，最新状态暂未完整读取。请刷新后继续。'
          : (failure instanceof Error ? failure.message : '记忆保存未完成。') + (sent ? ' 请核对最新内容后继续。' : ' 当前输入已保留。'));
      }
    });
  };
  const hideObsoleteMemory = (id: string, revision: number) => {
    const stillCurrent = (row: Data) => row.id !== id || Number.isSafeInteger(row.revision) && row.revision >= revision;
    setMemories(old => ({ ...old, complete: false, current: old.current.filter(stillCurrent), history: old.history.filter(stillCurrent), pendingConflicts: old.pendingConflicts.filter(stillCurrent) }));
  };
  const dropHandledEntry = (operation: Removal, id: string) => {
    if (operation === 'forget') {
      // A confirmed forget can retire siblings and suggestions, not just this id.
      // The next native projection decides which rows may be shown again.
      setMemories(emptyMemories()); setMemoryAvailable(false);
      return;
    }
    const others = (row: Data) => row.id !== id;
    setMemories(old => ({ ...old, complete: false, candidates: old.candidates.filter(others), pendingConflicts: old.pendingConflicts.filter(others) }));
  };
  const returnAfterRemoval = (operation: Removal, directory: MemoryDirectory = 'memories') => {
    // Preserve the list's existing query, topic and scroll while storage is read again.
    clearSelection();
    if (directory === 'memories') setShowCandidates(operation === 'reject');
    go(directory);
  };
  const prepareReview = (operation: Exclude<MemoryReview['operation'], 'shorten' | 'core'>) => {
    const frozen = current.current.selection;
    if (!frozen || memoryConflict || memoryUnknown || !readHandle.current || (operation !== 'forget' && memoryDirty(frozen))) return;
    if (operation === 'forget' ? frozen.candidate : !frozen.candidate || frozen.record.status !== 'pending') return;
    if (operation === 'accept' && (expiredMemory(frozen.record) || settingsRequiredFor(current.current.feedback, operation, frozen.id))) return;
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
      const before = frozen.value.before;
      const pending: MemoryFeedback = { value: { receiptHandle: frozen.value.receiptHandle, undoAvailable: false }, operation: frozen.operation, targetId: frozen.id, needsQuery: true };
      if (frozen.operation === 'forget' && frozen.value.operation === 'forget' && frozen.value.reviewHandle === handle &&
          frozen.value.receiptHandle === handle && originalForget && originalForget.id === frozen.id &&
          originalForget.revision === before?.revision && before.id === frozen.id && before.ownerId === 'owner' && before.spaceId === 'private' &&
          before.assertion?.subjectId === 'owner' && before.assertion?.subjectType === 'owner' && originalForget.current()) pending.answerForget = originalForget;
      if (removesFromList(frozen.operation)) pending.directory = frozen.operation === 'forget' && memoryParent.current === 'memory-history' ? 'memory-history' : 'memories';
      if (frozen.operation === 'accept' && before?.id === frozen.id && before.status === 'pending' && Number.isSafeInteger(before.revision) && before.revision > 0 && Number.isSafeInteger(before.expiresAt) && before.expiresAt > 0)
        pending.candidateTerm = { revision: before.revision, expiresAt: before.expiresAt };
      if (frozen.operation === 'core') pending.priority = { revision: before.revision, core: frozen.value.core };
      if (alive.current) setFeedback(pending);
      let completed = false;
      try {
        const receipt = object(await call('profile.memory.confirm', { reviewHandle: handle }));
        if (frozen.operation === 'core') {
          if (!validPriorityReceipt(receipt, pending) || receipt.record.text !== before.text || receipt.record.expiresAt !== before.expiresAt) throw new Error('原记忆的参考优先级尚未确认。');
          completed = true;
          if (!alive.current) return;
          setFeedback({ ...pending, value: receipt, needsQuery: false });
          if (!document.hidden && frozen.presentation === livePresentation.current && frozen.readHandle === readHandle.current)
            await refreshOpenMemoryChange(pending, receipt);
          return;
        }
        if (receipt.status === 'expired_not_saved') {
          if (!validExpiredCandidateReceipt(receipt, pending)) throw new Error('这条建议的期限回执尚未核对。');
          if (alive.current) { setFeedback({ ...pending, value: receipt, needsQuery: false }); if (current.current.selection?.id === frozen.id) setMemoryUnknown(false); }
          return;
        }
        if (settlePreferenceReceipt(receipt, frozen.value.receiptHandle, frozen.operation, frozen.id)) return;
        if (frozen.operation === 'accept' && receipt.duplicate === true && receipt.status === 'already_saved') {
          if (receipt.receiptHandle !== frozen.value.receiptHandle || !string((receipt.record || receipt.candidate)?.id)) throw new Error('相同内容的原回执尚未完整核对。');
          completed = true;
          if (!alive.current) return;
          rememberReceipt({ ...receipt, receiptHandle: receipt.receiptHandle || frozen.value.receiptHandle, undoAvailable: false }, 'accept', false, frozen.id);
          clearSelection(); setShowCandidates(false); go('memories');
          await readSnapshot(); return;
        }
        const valid = frozen.operation === 'forget' ? Number.isSafeInteger(receipt.deleted) && receipt.deleted >= 1 : receipt.candidate?.id === frozen.id && receipt.candidate?.status === (frozen.operation === 'accept' ? 'accepted' : 'rejected') && receipt.candidate?.revision > frozen.value.before.revision;
        if (!valid || receipt.status !== 'saved' || receipt.receiptHandle !== frozen.value.receiptHandle || (frozen.operation === 'accept' && !string(receipt.record?.id))) throw new Error('记忆处理回执尚未确认。');
        completed = true;
        if (!alive.current) return;
        setFeedback({ ...pending, value: receipt, needsQuery: false });
        if (removesFromList(frozen.operation)) {
          dropHandledEntry(frozen.operation, frozen.id); returnAfterRemoval(frozen.operation, pending.directory);
        } else {
          setMemories(old => ({ ...old, current: frozen.operation === 'accept' && receipt.record?.id ? [...old.current.filter(row => row.id !== receipt.record.id), receipt.record] : old.current, candidates: old.candidates.filter(row => row.id !== frozen.id), pendingConflicts: old.pendingConflicts.filter(row => row.id !== frozen.id) }));
          clearSelection(); setShowCandidates(false); go('memories');
        }
        await readSnapshot();
      } catch (failure) {
        if (alive.current && !completed) setMemoryUnknown(true);
        throw new Error(completed ? '操作已完成，列表暂未完整刷新，请重新读取记忆。' : (failure instanceof Error ? failure.message : '处理结果尚未确认。') + ' 请核对最新记忆，不要重复提交。');
      }
  };
  const prepareShorterTerm = (calendar: CalendarTime) => {
    const frozen = current.current.selection, originalHandle = readHandle.current, window = livePresentation.current;
    if (!frozen || frozen.candidate || frozen.historical || frozen.value.canReviewShorterTerm !== true || !originalHandle || memoryDirty(frozen) || memoryConflict || memoryUnknown || unresolvedTerm(frozen.id)) return;
    void action('正在核对原记忆期限', async () => {
      const { value, handle } = await checkedReview('shorten', frozen, { calendar });
      try {
        const prior = frozen.record.expiresAt, until = value.expiresAt;
        if (!alive.current || window !== livePresentation.current || originalHandle !== readHandle.current || current.current.selection?.id !== frozen.id) { closeHandle(handle); return; }
        if (handle !== value.receiptHandle || value.zone !== calendar.zone || value.before.expiresAt !== prior || value.before.text !== frozen.originalText ||
            !Number.isSafeInteger(until) || until <= Date.now() || !(prior === null || Number.isSafeInteger(prior) && Number.isFinite(new Date(prior).getTime()) && until < prior) || !(value.reviewExpiresAt > Date.now())) throw new Error();
        const instant = calendarTimeAt(until, value.zone);
        if (instant.date !== calendar.date || instant.time !== calendar.time) throw new Error();
        clearReview(); reviewHandle.current = handle; setReview({ value, operation: 'shorten', id: frozen.id, candidate: false });
      } catch { closeHandle(handle); throw new Error('原条目或选定时间没有核对一致，原期限未改。'); }
    });
  };
  const preparePriority = () => {
    const frozen = current.current.selection, originalHandle = readHandle.current, presentation = livePresentation.current;
    if (document.hidden || current.current.view !== 'memory' || !canSetPriority(frozen) || !originalHandle || memoryConflict || memoryUnknown || memoryDirty(frozen) || unresolvedTerm(frozen.id)) return;
    const core = !frozen.record.core, revision = liveMemoryRevision.current;
    void action('正在核对这条记忆', async () => {
      const { value, handle } = await checkedReview('core', frozen, { core });
      if (!alive.current || document.hidden || presentation !== livePresentation.current || revision !== liveMemoryRevision.current ||
          originalHandle !== readHandle.current || current.current.selection !== frozen) { closeHandle(handle); return; }
      if (value.receiptHandle !== handle || value.executionAuthorized !== false || value.core !== core || value.before.core !== frozen.record.core || value.before.text !== frozen.originalText) {
        closeHandle(handle); throw new Error('原记忆或参考优先级没有核对一致，没有保存。');
      }
      clearReview(); reviewHandle.current = handle; setReview({ value, operation: 'core', id: frozen.id, candidate: false, presentation, readHandle: originalHandle });
    });
  };
  const unresolvedTerm = (id: string) => {
    const f = current.current.feedback;
    return f?.operation === 'shorten' && f.targetId === id && (f.needsQuery || f.value.status === 'unknown');
  };
  const confirmReview = () => {
    const frozen = current.current.review, handle = reviewHandle.current;
    if (!frozen || !handle) return;
    if (frozen.operation === 'createAt') {
      const source = current.current, value = frozen.value, text = source.newMemoryText.trim();
      if (source.view !== 'memory-new' || source.createUnknown || frozen.presentation !== livePresentation.current || source.newMemoryExpiry !== 'custom' || source.newMemoryCalendar !== frozen.calendar || value.afterText !== text ||
          value.reviewHandle !== handle || !(Math.min(value.reviewExpiresAt, value.expiresAt) > Date.now())) { clearReview(); setError('输入或核对窗口已变化，没有保存。'); return; }
      void action('正在保存记忆', async () => { reviewHandle.current = ''; setReview(null); await submitNewMemory(text, value.expiresAt, value); });
      return;
    }
    if (frozen.operation === 'shorten') {
      const selection = current.current.selection, value = frozen.value;
      if (!selection || memoryConflict || memoryUnknown || unresolvedTerm(frozen.id) || memoryDirty(selection) ||
          selection.id !== frozen.id || value.before.revision !== selection.record.revision || value.before.expiresAt !== selection.record.expiresAt ||
          !(Math.min(value.reviewExpiresAt, value.expiresAt) > Date.now())) { clearReview(); setError('原期限或核对窗口已变化，请重新核对。'); return; }
      void action('正在缩短原记忆有效期', async () => {
        reviewHandle.current = ''; setReview(null);
        await submitShorterTerm(handle, frozen.id, value.before.revision, value.expiresAt);
      });
    } else {
      if (frozen.operation === 'core') {
        const selection = current.current.selection, before = frozen.value.before;
        if (document.hidden || frozen.presentation !== livePresentation.current || frozen.readHandle !== readHandle.current ||
            !canSetPriority(selection) || memoryConflict || memoryUnknown || memoryDirty(selection) || unresolvedTerm(selection.id) || current.current.view !== 'memory' ||
            selection.id !== frozen.id || selection.record.revision !== before.revision || selection.originalText !== before.text || selection.record.core !== before.core) {
          clearReview(); setError('原记忆已变化，没有更改参考优先级。请重新读取。'); return;
        }
      }
      void action('正在更新记忆', () => applyMemoryReview(frozen, handle));
    }
  };
  const adoptLatestMemory = (keepDraft: boolean) => {
    if (!selection || !latestMemory) return;
    const record = object(latestMemory.record);
    setSelection({ ...selection, value: object(latestMemory.value), record, originalText: string(record.text), draft: keepDraft ? selection.draft : string(record.text), historical: historicalMemory(record) });
    setLatestMemory(null); setMemoryConflict(false); setMemoryUnknown(false); setError(''); setNotice(keepDraft ? '输入已保留，请核对后保存' : '已使用最新内容');
  };
  const refreshOpenMemoryChange = async (change: MemoryFeedback, priorityReceipt?: Data) => {
    const selected = current.current.selection, original = readHandle.current, window = livePresentation.current;
    if (!selected || selected.candidate || current.current.view !== 'memory' || selected.id !== change.targetId) return false;
    setMemoryUnknown(false);
    const priority = change.priority;
    if (priority && selected.record.revision !== priority.revision) return true; // do not replace a later observed revision
    if (!priority || !original || memoryDirty(selected)) setMemoryConflict(true);
    if (!original || memoryDirty(selected)) return true; // never replace unrelated unsaved text
    try {
      const latest = await readMemory(selected.id, false);
      if (!alive.current || original !== readHandle.current || window !== livePresentation.current || current.current.selection?.id !== selected.id || priority && document.hidden) { closeHandle(latest.handle); return true; }
      if (priority && (!priorityReceipt || latest.row.revision !== priorityReceipt.record.revision || latest.row.core !== priority.core || latest.row.text !== priorityReceipt.record.text || latest.row.expiresAt !== priorityReceipt.record.expiresAt)) {
        closeHandle(latest.handle); throw new Error();
      }
      closeHandle(original); readHandle.current = latest.handle;
      const text = string(latest.row.text);
      const historical = historicalMemory(latest.row) || latest.row.assertionStatus !== 'current';
      setSelection({ ...selected, value: latest.value, record: latest.row, originalText: text, draft: text, historical });
      const update = (row: Data) => row.id === selected.id ? { ...row, ...latest.row } : row;
      setMemories(old => ({ ...old, current: historical ? old.current.filter(row => row.id !== selected.id) : old.current.map(update), history: old.history.map(update) }));
      setLatestMemory(null); setMemoryConflict(false);
    } catch {
      if (priority && alive.current && window === livePresentation.current && current.current.selection?.id === selected.id) setMemoryConflict(true);
      throw new Error(priority ? '参考优先级已保存，当前状态暂未读到。请重新读取，不要重复提交。' : '期限回执已核对，原条目的最新状态暂未读到。请重新读取，不要重复提交。');
    }
    return true;
  };
  const submitShorterTerm = async (handle: string, id: string, revision: number, until: number) => {
    const pending: MemoryFeedback = { value: { receiptHandle: handle, undoAvailable: false }, operation: 'shorten', needsQuery: true, targetId: id, term: { id, revision, until } };
    setFeedback(pending);
    if (current.current.selection?.id === id) setMemoryUnknown(true);
    const result = object(await call('profile.memory.confirm', { reviewHandle: handle }));
    if (!validShortenReceipt(result, pending)) throw new Error('有效期结果尚未核对，请查询这次修改，不要重复提交。');
    if (!alive.current) return;
    setFeedback({ ...pending, value: result, needsQuery: false });
    if (!await refreshOpenMemoryChange(pending) && result.status === 'saved') try { await readSnapshot(); } catch { throw new Error('原记忆的有效期已缩短；列表暂未刷新。'); }
  };
  const shortenDuplicate = () => {
    const frozen = current.current.feedback;
    if (!frozen || current.current.view !== 'memories' || !duplicateRetention(frozen) || busy.current) return;
    const receipt = frozen.value, handle = string(receipt.shortenReviewHandle), record = receipt.record, until = receipt.requestedExpiresAt;
    if (!handle || !record || !string(record.id) || !Number.isSafeInteger(record.revision) || record.revision < 1 || !Number.isSafeInteger(until)) return;
    if (!(receipt.shortenReviewExpiresAt > Date.now()) || until <= Date.now()) {
      setFeedback({ ...frozen, value: { ...receipt, shortenReviewHandle: '' } }); setNotice('原期限未改。核对窗口已过期，请重新选择期限。'); return;
    }
    void action('正在缩短原记忆有效期', () => submitShorterTerm(handle, record.id, record.revision, until));
  };
  const readReceipt = (undo = false) => {
    const frozen = current.current.feedback;
    if (!frozen || !string(frozen.value.receiptHandle) || (undo && (frozen.needsQuery || frozen.value.status !== 'saved' || frozen.value.undoAvailable !== true || !['create', 'accept'].includes(frozen.operation)))) return;
    const submission = !undo && frozen.operation === 'correct' ? frozen.correction : undefined;
    const origin = current.current, presentation = livePresentation.current, doc = bridge.getDocumentEpoch();
    const correctionCurrent = () => alive.current && !!privateProfileReadWindow() && bridge.getDocumentEpoch() === doc &&
      previousPresentation.current === presentation && livePresentation.current === presentation && current.current.view === origin.view &&
      current.current.conversation === origin.conversation && current.current.feedback?.value.receiptHandle === frozen.value.receiptHandle;
    if (submission && !correctionCurrent()) {
      if (!document.hidden) setError('请回原个人记忆窗口核对，这次结果仍待确认。');
      return;
    }
    void action(undo ? '正在撤销这次保存' : '正在核对这次修改', async () => {
      if (undo) setFeedback({ ...frozen, needsQuery: true });
      let settled = false;
      try {
        let result = object(await call(undo ? 'profile.memory.undo' : 'profile.memory.query', { receiptHandle: frozen.value.receiptHandle }));
        if (manualEntry && !manualEntry.current() || submission && !correctionCurrent()) return;
        if (result.receiptHandle === frozen.value.receiptHandle && result.status === 'not_submitted') {
          const closed = object(await call('profile.memory.close', { handle: frozen.value.receiptHandle }));
          if (closed.closed !== true) throw new Error('尚未提交的审阅未确认关闭，请继续核对原操作。');
          if (submission && !correctionCurrent()) return;
          // Re-read after retiring the original review so a delayed confirm cannot
          // arrive after the editor has been unlocked for a new explicit save.
          result = object(await call('profile.memory.query', { receiptHandle: frozen.value.receiptHandle }));
        }
        if (result.status === 'expired_not_saved' && !validExpiredCandidateReceipt(result, frozen)) throw new Error('这条建议的期限回执尚未核对。');
        if (result.receiptHandle !== frozen.value.receiptHandle || !['not_submitted', 'settings_required', 'saved', 'already_saved', 'reverted', 'unknown', 'conflict', 'unchanged', 'expired_not_saved'].includes(result.status) || (result.status === 'unchanged' || frozen.operation === 'shorten' && result.status === 'saved') && !validShortenReceipt(result, frozen) || result.status === 'settings_required' && !preferenceSectionOf(result)) throw new Error('这次修改的回执尚未完整读取。');
        if (result.status === 'saved' && removesFromList(frozen.operation) && (!frozen.targetId || (frozen.operation === 'forget'
          ? !Number.isSafeInteger(result.deleted) || result.deleted < 1
          : result.candidate?.id !== frozen.targetId || result.candidate?.status !== 'rejected'))) throw new Error('这条记录的原处理回执尚未完整核对。');
        if (frozen.operation === 'core' && result.status === 'saved' && !validPriorityReceipt(result, frozen)) throw new Error('原记忆的参考优先级尚未确认。');
        if (!alive.current || manualEntry && !manualEntry.current() || submission && !correctionCurrent()) return;
        const checked = { ...frozen, value: result, needsQuery: false };
        if (manualEntry && result.status === 'saved' && (!confirmedManualSave(checked) || result.record?.text !== frozen.submittedText))
          throw new Error('尚未核对到这次保存的原记忆。');
        const recovered = submission && result.status === 'saved' ? recoveredCorrectionPin(checked) : null;
        if (submission && result.status === 'saved' && !recovered) throw new Error('尚未核对到这次修改的原保存版本。');
        settled = true;
        setFeedback(checked);
        if (submission && recovered) {
          hideObsoleteMemory(recovered.id, recovered.revision);
          const original = origin.selection;
          const sameEditor = () => correctionCurrent() && origin.view === 'memory' && current.current.selection === original;
          // A saved receipt is history, not proof that this version is still active.
          if (!original || original.candidate || original.historical || original.id !== recovered.id ||
              original.record.revision !== submission.beforeRevision || !sameEditor()) return;
          const prior = readHandle.current; readHandle.current = ''; closeHandle(prior);
          setMemoryUnknown(false); setMemoryConflict(true); setLatestMemory(null);
          const evidence = privateProfileReadWindow();
          if (!evidence) return;
          const found = await readMemory(recovered.id, false).catch(() => null);
          if (!sameEditor()) { if (found) closeHandle(found.handle); return; }
          if (!found || privateProfileReadWindow() !== evidence || found.row.revision !== recovered.revision ||
              found.row.text !== frozen.submittedText || found.row.active !== true || historicalMemory(found.row) ||
              found.row.archiveOnly === true || found.row.assertionStatus !== 'current') {
            if (found) closeHandle(found.handle);
            setNotice('已确认这次修改保存过，当前未核对到原版本。没有打开其他版本。');
            return;
          }
          const text = string(found.row.text), laterDraft = original.draft !== submission.draft && original.draft !== text;
          readHandle.current = found.handle;
          setSelection({ ...original, value: found.value, record: found.row, originalText: text, draft: laterDraft ? original.draft : text });
          setFeedback({ ...checked, correction: undefined });
          setMemoryConflict(false);
          setNotice(`已核对这次保存的第 ${recovered.revision} 版${laterDraft ? '。后续输入仍保留，尚未保存。' : '。'}`);
          return;
        }
        if (result.status === 'saved' && removesFromList(frozen.operation) && frozen.targetId) dropHandledEntry(frozen.operation, frozen.targetId);
        if (frozen.operation === 'correct') {
          const pin = memoryReceiptPin(checked);
          if (pin) hideObsoleteMemory(pin.id, pin.revision);
        }
        if (frozen.operation === 'shorten' && ['saved', 'unchanged'].includes(result.status) && await refreshOpenMemoryChange(frozen)) return;
        if (frozen.operation === 'core' && result.status === 'saved') { await refreshOpenMemoryChange(frozen, result); return; }
        if (result.status === 'expired_not_saved') {
          if (current.current.selection?.id === frozen.targetId) setMemoryUnknown(false);
          return;
        }
        if (result.status === 'settings_required') {
          if (frozen.operation === 'create') setCreateUnknown(false);
          if (current.current.selection?.id === frozen.targetId) setMemoryUnknown(false);
          return;
        }
        if (result.status === 'not_submitted') {
          if (frozen.operation === 'create') setCreateUnknown(false);
          if (current.current.selection?.id === frozen.targetId) setMemoryUnknown(false);
          setNotice(result.reason === 'deadline_passed' && frozen.operation === 'create' ? '选择的时刻已过，没有保存。你的输入仍在，请重新选择期限。' : '这次修改尚未提交，输入已保留。你可以继续处理，没有自动重新保存。');
          return;
        }
        if (result.status === 'saved' || result.status === 'reverted' || result.status === 'already_saved') {
          if (current.current.view === 'memory-new' && current.current.createUnknown && frozen.operation === 'create') {
            returnFromCreate(result);
          }
          const selected = current.current.selection;
          if (selected && selected.id === frozen.targetId) {
            if (frozen.operation === 'correct') { setMemoryUnknown(false); setMemoryConflict(true); setNotice('结果已核对。请读取最新记忆，当前输入仍保留。'); }
            else if (result.status === 'saved' && removesFromList(frozen.operation) && selected.candidate === (frozen.operation === 'reject')) {
              // The original removal is now confirmed, including when this editor had an unsaved draft.
              returnAfterRemoval(frozen.operation, frozen.directory);
            } else if (!memoryDirty(selected)) {
              if (frozen.operation === 'accept' && ['saved', 'already_saved'].includes(result.status) && result.record) setShowCandidates(false);
              clearSelection(); go('memories');
            }
          }
          if (result.status === 'reverted' && selected?.id === frozen.value.record?.id && !memoryDirty(selected)) { clearSelection(); go('memories'); }
          if (!manualEntry) await readSnapshot();
        }
      } catch (failure) {
        if (alive.current && undo && !settled) setFeedback({ ...frozen, needsQuery: true });
        throw new Error(settled ? '操作结果已核对，列表暂未完整读取，请刷新记忆。' : (failure instanceof Error ? failure.message : '结果暂未读取。') + ' 请查询原操作，不要重复提交。');
      }
    });
  };
  const targetKey = initialTarget ? `${targetRequestKey || ''}:${initialTarget.view}:${initialTarget.view === 'memory' ? `${initialTarget.id}:${initialTarget.candidate}:${initialTarget.expectedRevision ?? ''}` : ''}` : '';
  useEffect(() => {
    if (!initialTarget || !targetKey || manualEntry) return;
    if (targetArrival.current.key !== targetKey)
      targetArrival.current = { key: targetKey, conversation: originalAnswer?.conversation.id ?? current.current.conversation };
    if (targetKey === handledTarget.current || (!loaded && !originalAnswer) || !enteredProfile || busy.current) return;
    handledTarget.current = targetKey;
    const target = initialTarget, conversation = targetArrival.current.conversation;
    guarded(() => {
      directEntry.current = target.view === 'home' ? null : { target, conversation };
      if (target.view === 'memory-settings') enterMemorySettings();
      else if (target.view === 'memory') openMemory({ id: target.id }, target.candidate, target.expectedRevision === undefined ? undefined
        : { kind: 'answer', revision: target.expectedRevision, conversation, originalAnswer });
      else { clearSelection(); setEditor(null); go(target.view); }
    });
  }, [targetKey, loaded, enteredProfile, pending, originalAnswer, manualEntry]);
  const confirmUnknownExit = createUnknown || unresolvedDirectExit();
  const expiredTermReview = usePendingCandidateDeadline(review?.operation === 'shorten' || review?.operation === 'createAt' ? Math.min(review.value.expiresAt, review.value.reviewExpiresAt) : 0);
  const currentSection = editor?.draft;
  // Preserve native array indices, even if one entry cannot be displayed.
  const memorySources: unknown[] | null = Array.isArray(selection?.record.sourceRefs) ? selection.record.sourceRefs : null;
  const sourceHandle = readHandle.current;
  const retiredSuggestion = !!selection?.candidate && !['pending', 'accepted', 'rejected'].includes(selection.record.status);
  const candidateResultUnclear = !!selection?.candidate && feedback?.targetId === selection.id
    && (feedback.needsQuery || feedback.value.status === 'unknown');
  const relatedRecords = rows(selection?.value.relatedRecords);
  const comparedMemoryEditable = (row: Data) => !!selection?.candidate && !!sourceHandle &&
    ['profile.memory.get', 'profile.memory.review', 'profile.memory.confirm'].every(supported) &&
    relatedPersonalMemory(selection.record, row) && relatedRecords.filter(item => item.id === row.id).length === 1;
  const retention = feedback ? duplicateRetention(feedback) : '';
  const exactRecordFeedback = feedback?.operation === 'create' || feedback?.operation === 'accept' || feedback?.operation === 'correct';
  const receiptPin = memoryReceiptPin(feedback);
  const atReceiptRecord = !!selection && !!receiptPin && selection.id === receiptPin.id
    && selection.candidate === receiptPin.candidate && selection.record.revision === receiptPin.revision
    && (!feedback?.priorSave || !!readHandle.current && !memoryConflict);
  const atOriginSuggestion = !!selection?.candidate && feedback?.operation === 'accept' && selection.id === feedback.targetId
    && (feedback.needsQuery || ['unknown', 'settings_required', 'not_submitted', 'conflict'].includes(feedback.value.status)
      || feedback.value.status === 'expired_not_saved' && feedback.value.candidateRevision === selection.record.revision);
  const atOriginCorrection = !!selection && !selection.candidate && feedback?.operation === 'correct' && selection.id === feedback.targetId
    && (!receiptPin || memoryUnknown || memoryConflict);
  const removalFeedback = !!feedback && removesFromList(feedback.operation);
  const removed = removalFeedback && !feedback.needsQuery && feedback.value.status === 'saved';
  const manualReady = !!manualEntry && enteredProfile && manualEntry.current();
  const manualRetired = !!manualEntry && !manualReady && !locked && (enteredProfile || !!error || !canEnter);
  const feedbackVisible = !!feedback && (!manualEntry || manualReady) && (view === 'memories' ? (!receiptPin || showCandidates === receiptPin.candidate)
    && (!removed || feedback.directory !== 'memory-history' && showCandidates === (feedback.operation === 'reject'))
    : view === 'memory-history' ? feedback.operation !== 'core' && !exactRecordFeedback && (!removed || feedback.directory === 'memory-history')
    : view === 'memory' ? exactRecordFeedback ? atReceiptRecord || atOriginSuggestion || atOriginCorrection
      : removalFeedback ? selection?.id === feedback.targetId && selection?.candidate === (feedback.operation === 'reject')
      : !['shorten', 'core'].includes(feedback.operation) || selection?.id === feedback.targetId
    : view === 'memory-new' && (!!manualEntry || createUnknown || feedback.value.status === 'settings_required'));
  const returnForgottenToAnswer = feedbackVisible && atDirectEntry() && !!confirmedAnswerForget(feedback, originalForget);
  const preferenceSection = feedback && !feedback.needsQuery ? preferenceSectionOf(feedback.value) : null;
  const manualSaved = manualEntry ? confirmedManualSave(feedback) : null;
  const headingLabel = view === 'memory' && selection?.candidate ? '记忆建议' : view === 'memory' && selection?.historical ? '记忆详情' : labels[view];
  const missingDirectVersion = directVersion && view === 'memory' && !selection;
  const headerSave = editor ? <Button className="pw-profile-save" size="small" disabled={locked || avatarPicking || !profileDirty(editor) || profileConflict || pendingSavedRead || !canSave} onClick={saveProfile}>{pending === '正在保存' ? '保存中' : '保存'}</Button>
    : view === 'memory-new' && !manualSaved && !manualRetired ? <Button className="pw-profile-save" size="small" disabled={locked || !!manualEntry && !manualReady || createUnknown || !newMemoryText.trim() || newMemoryExpiry === 'custom' && (!newMemoryCalendar || !validCalendarTime(newMemoryCalendar)) || settingsRequiredFor(feedback, 'create', undefined, newMemoryText.trim()) || !supported('profile.memory.createReview') || !supported('profile.memory.confirm')} onClick={saveNewMemory}>{pending === '正在保存记忆' ? '保存中' : newMemoryExpiry === 'custom' ? '核对' : '保存'}</Button>
      : view === 'memory' && selection && !selection.candidate && !selection.historical ? <Button className="pw-profile-save" size="small" disabled={locked || !memoryDirty(selection) || !selection.draft.trim() || memoryConflict || memoryUnknown || settingsRequiredFor(feedback, 'correct', selection.id, selection.draft.trim()) || !supported('profile.memory.confirm')} onClick={saveMemory}>{pending === '正在保存记忆' ? '保存中' : '保存'}</Button>
        : view === 'memories' || view === 'memory-history' ? <IconButton icon={IconSettings} aria-label={labels['memory-settings']} title={labels['memory-settings']} disabled={locked} onClick={openMemorySettings} />
          : view === 'home' ? <IconButton icon={IconSettings} aria-label="隐私与数据设置" title="隐私与数据" onClick={() => guarded(onSettings)} /> : <span />;
  const entry = (id: string, title: string, description: string, icon: typeof IconChat, open: () => void, badge?: ReactNode) => <Button variant="ghost" className="pw-profile-entry" leadingIcon={icon} trailingIcon={IconChevronRight} onClick={open} disabled={!loaded || locked} data-profile-entry={id}><span className="pw-profile-entry-copy"><strong>{title}</strong><small>{description}</small></span>{badge}</Button>;
  const memoryRow = (row: Data, candidate: boolean) => {
    const hit = hits.get(row), text = string(row.text) || string(row.title) || '未命名记忆';
    return <Button key={(candidate ? 'candidate:' : 'memory:') + row.id} variant="ghost" className="pw-profile-memory-row" trailingIcon={IconChevronRight}
      data-personal-memory={string(row.id)} data-motion-key={(candidate ? 'candidate:' : 'memory:') + row.id} disabled={locked || !supported('profile.memory.get')} onClick={() => openMemory(row, candidate)}>
      <span><strong>{hit?.body ? matchExcerpt(text, hit.body) : text}</strong><small>{[hit?.via.length ? '匹配' + hit.via.join('、') : '', memoryOrigin(row, candidate), memoryDates(row)].filter(Boolean).join(' · ')}</small></span>
    </Button>;
  };
  const followUp = object(duties?.policy), quietHours = object(followUp.quietHours);
  const followUpLabel = dutiesReading ? '正在核对跟进设置' : dutiesError ? '跟进设置待重新核对' : !duties ? '查看委托与跟进设置' : [followUp.enabled !== true ? '自动跟进已关闭' : Number(followUp.pausedUntil) > Date.now() ? '自动跟进已暂停' : '自动跟进已开启', quietHours.start && quietHours.end ? `安静时段 ${quietHours.start}–${quietHours.end}` : ''].filter(Boolean).join(' · ');
  const reminderData = object(state.features?.automation), reminderReady = Array.isArray(reminderData.rules);
  const reminderBusy = (state.moduleBusy || []).some((name: string) => ['automation.list', 'automation.receipts'].includes(name));
  const reminderError = string(state.moduleErrors?.['automation.list']) || string(state.moduleErrors?.['automation.receipts']);
  const reminderLabel = dutiesReading || reminderBusy ? '正在核对已保存提醒' : dutiesError || reminderError ? '提醒状态待重新核对' : !reminderReady ? '查看已保存的定时提醒' : rows(reminderData.rules).length ? `${rows(reminderData.rules).filter(rule => rule.enabled === true).length} 条已开启 · 查看范围与发生记录` : '还没有定时提醒';
  const recentReceipt = !dutiesReading && !dutiesError && !reminderBusy && !reminderError ? rows(state.moduleResults?.['automation.receipts']?.receipts).slice().reverse()[0] : undefined;
  const receiptLabel = recentReceipt ? ({ delivery_unknown: '投递待核对', awaiting_user: '等待你查看', blocked_notifications: '通知受阻', notified: '已通知', delivered: '已投递', cancelled: '已取消', failed: '未完成' }[string(recentReceipt.status)] || '查看原发生记录') : '';

  return <section className="pw-profile-workspace pw-route-workspace" data-profile-view={view} aria-label={headingLabel}>
    <header className="pw-profile-header"><IconButton icon={IconArrowLeft} aria-label={manualEntry ? manualRetired ? '返回管家' : '返回原对话' : view === 'memory' && selection?.fromSuggestion ? '返回这条记忆建议' : view === 'memories' && showCandidates ? '返回已保存的记忆' : atDirectEntry() ? originalAnswer ? '返回原回复' : '返回进入时的页面' : view === 'home' ? '返回' : '返回上一级'} onClick={requestBack} /><div className="pw-profile-header-copy"><h1 ref={heading} tabIndex={-1}>{headingLabel}</h1><WorkingStatus active={locked && (loaded || !!originalAnswer || !!manualEntry) || avatarPicking && !locked}>{locked ? `${pending}…` : '正在等候所选头像…'}</WorkingStatus></div>{headerSave}</header>
    <div className="pw-profile-scroll" ref={scroller}>
      <div className="pw-profile-content">
        {!canEnter && <div className="pw-profile-empty"><IconAccount aria-hidden="true" /><h2>个人资料暂不可用</h2><p>请更新应用后重新打开。</p></div>}
        {manualEntry && !enteredProfile && !manualRetired && <p className="pw-profile-loading" role="status">正在打开记忆表单…</p>}
        {manualRetired && <div className="pw-profile-feedback" role="status">
          <p>原记忆表单的会话窗口已变化，不能从这里继续保存或返回原对话。</p>
          {manualSaved ? <p>这次保存已确认。可在个人记忆中查找。</p> :
            (createUnknown || unresolvedDirectExit()) && <p>保存结果尚未核对。请先到个人记忆确认，不要重复保存。</p>}
          {!manualSaved && !!newMemoryText.trim() && <p>原输入仍留在下面；离开前可自行复制。</p>}
          <Button variant="secondary" size="small" onClick={requestBack}>返回管家</Button>
        </div>}
        {canEnter && !loaded && !originalAnswer && !manualEntry && <div className="pw-profile-loading" role="status">{locked || !enteredProfile || !canRead ? '正在打开个人资料…' : '个人资料尚未读取。'}{!locked && <Button variant="ghost" leadingIcon={IconRefresh} onClick={refresh}>重新读取</Button>}</div>}
        {error && <MotionFeedback identity={error} className="pw-profile-feedback is-error" role="alert"><p>{error}</p>{!manualEntry && !locked && !missingDirectVersion && <Button variant="ghost" size="small" leadingIcon={IconRefresh} onClick={view === 'memory' ? reloadMemory : view === 'memory-settings' ? openMemorySettings : refresh}>读取最新{view === 'memory' ? '记忆' : view === 'memory-settings' ? '设置' : '资料'}</Button>}</MotionFeedback>}
        {notice && <MotionFeedback identity={notice} className="pw-profile-feedback" role="status">{['已保存', '记忆已删除', '已记住', '已忽略', '当前内容已核对'].includes(notice) && <IconCheck size={15} aria-hidden="true" />}{notice}</MotionFeedback>}
        {feedbackVisible && feedback && <MotionFeedback identity={JSON.stringify([feedback.operation, feedback.targetId, feedback.value.status, feedback.needsQuery, feedback.value.undoAvailable])} className="pw-profile-feedback pw-profile-memory-feedback" role="status">
          <div className="pw-profile-receipt-heading">{!feedback.needsQuery && ['saved', 'reverted'].includes(feedback.value.status) && <IconCheck size={16} aria-hidden="true" />}<span>{feedbackLabel(feedback)}</span></div>
          {!feedback.needsQuery && feedback.value.status === 'saved' && (feedback.operation === 'correct' || feedback.operation === 'forget') && <p>不会重新生成以前的回答。原回答若有「本次记忆」，可在那里核对当时的提供回执。</p>}
          {pendingForget && <p>先查询原回执，再决定下一步。查询不会再次提交遗忘。</p>}
          {retention && <p>{retention}</p>}
          {feedback.value.undoUnavailableReason && <p>{string(feedback.value.undoUnavailableReason)}</p>}
          <div className="pw-profile-actions">
            {returnForgottenToAnswer && <Button variant="secondary" size="small" leadingIcon={IconArrowLeft} disabled={locked} onClick={requestBack}>返回原回答</Button>}
            {manualSaved && <Button variant="secondary" size="small" disabled={locked} onClick={requestBack}>完成，返回原对话</Button>}
            {view === 'memories' && retention && string(feedback.value.shortenReviewHandle) && <Button variant="secondary" size="small" disabled={locked || !supported('profile.memory.confirm')} onClick={shortenDuplicate}>仅缩短这条原记忆</Button>}
            {preferenceSection && manualEntry && <p>这类偏好请在对话的默认偏好设置中修改。本次没有保存为记忆。</p>}
            {!manualEntry && preferenceSection && <Button variant="secondary" size="small" trailingIcon={IconChevronRight} disabled={locked} onClick={() => openEditor(preferenceSection)}>去{preferenceSection === 'communication' ? '交流偏好' : '做事偏好'}</Button>}
            {!manualEntry && receiptPin && !atReceiptRecord && <Button variant="secondary" size="small" disabled={locked || !supported('profile.memory.get') || !!feedback.priorSave && !privateProfileReadWindow()}
              onClick={() => guarded(() => openMemory({ id: receiptPin.id }, receiptPin.candidate, { kind: 'receipt', revision: receiptPin.revision, feedback }))}>
              {feedback.priorSave ? '重新核对当时保存的版本' : receiptPin.candidate ? '查看原待确认建议' : feedback.operation === 'correct' ? '查看这次更新的记忆' : feedback.value.status === 'saved' ? '查看这次保存的记忆' : '查看已有的记忆'}
            </Button>}
            {!manualEntry && !removalFeedback && feedback.operation !== 'core' && !feedback.needsQuery && (!exactRecordFeedback || feedback.value.status === 'conflict') && ['saved', 'already_saved', 'conflict'].includes(feedback.value.status) && string((feedback.value.record || feedback.value.candidate)?.id) && <Button variant="ghost" size="small" disabled={locked} onClick={() => guarded(() => openMemory(feedback.value.record || feedback.value.candidate, !feedback.value.record))}>查看{feedback.value.status === 'conflict' ? '最新记忆' : feedback.value.candidate && !feedback.value.record ? '待确认内容' : ''}</Button>}
            {!manualEntry && !feedback.needsQuery && feedback.value.status === 'saved' && feedback.value.undoAvailable === true && ['create', 'accept'].includes(feedback.operation) && supported('profile.memory.undo') && <Button variant="ghost" size="small" leadingIcon={IconUndo} disabled={locked} onClick={() => guarded(() => readReceipt(true))}>撤销这次保存</Button>}
            {(feedback.needsQuery || ['unknown', 'conflict'].includes(feedback.value.status)) && <Button variant="secondary" size="small" leadingIcon={IconRefresh} disabled={locked || !!manualEntry && !manualReady || !supported('profile.memory.query')} onClick={() => readReceipt()}>查询这次修改</Button>}
          </div>
        </MotionFeedback>}
        {pendingSavedRead && <Button variant="secondary" size="small" disabled={locked} onClick={()=>{void action('正在核对当前资料',async()=>{await readSnapshot();setSavedReceipt(null);setNotice('当前设置已重新读取。');});}}>核对当前设置</Button>}
        {loaded && profile && view === 'home' && <>
          <section className="pw-profile-identity" aria-label="个人资料"><Avatar profile={profile} large /><div><h2>{profile.nickname || '我的资料'}</h2>{profile.addressAs && profile.addressAs !== profile.nickname && <p>称呼我为 {profile.addressAs}</p>}<Button variant="ghost" size="small" className="pw-profile-edit-link" onClick={() => openEditor('identity')}>编辑资料<IconChevronRight size={15} aria-hidden="true" /></Button></div></section>
          <p className="pw-profile-scope">这里的偏好用于私人管家的交流与做事方式；每次工作的明确要求和已确认范围优先。</p>
          <div className="pw-profile-menu">
            {entry('communication', '交流偏好', [selectedLabel(languageOptions, profile.language), selectedLabel(detailOptions, profile.response_detail)].filter(Boolean).join(' · '), IconChat, () => openEditor('communication'))}
            {entry('behavior', '做事偏好', [selectedLabel(paceOptions, profile.operation_pace), selectedLabel(initiativeOptions, profile.initiative)].filter(Boolean).join(' · '), IconControls, () => openEditor('behavior'))}
            {entry('memories', '个人记忆', pendingCandidates.length ? `${pendingCandidates.length} 条待确认 · 已保存内容与来源` : '查找已保存的内容与来源', IconSparkles, () => { setEditor(null); go('memories'); }, pendingCandidates.length > 0 ? <span className="pw-profile-unread" aria-label={`${pendingCandidates.length} 条待确认`} /> : undefined)}
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
          <p className="pw-profile-intro">这里是已保存的个人记忆，可以纠正或忘记。待选建议单独显示，不算已保存。</p>
          {memoryError && <div className="pw-profile-feedback is-error" role="alert"><p>{memoryError}</p><Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={locked} onClick={refresh}>重新读取记忆</Button></div>}
          {(hasMemoryRows || !!query || showCandidates) && <div className="pw-profile-collection-tools">
            <SearchField className="pw-profile-memory-search" fieldClassName="pw-profile-input" aria-label={showCandidates ? '搜索待确认建议' : '搜索已读取的个人记忆'} placeholder={showCandidates ? '搜索待确认建议，空格分词' : '搜索个人记忆，空格分词'} clearLabel="清除搜索" maxLength={256} value={query} onChange={setQuery} shape="rounded" isDisabled={locked} />
            <div className="pw-profile-collection-nav">
              <Button variant="secondary" leadingIcon={IconAdd} disabled={locked || !supported('profile.memory.createReview')} onClick={openNewMemory}>记住一件事</Button>
              {!candidateMatchLink && (pendingCandidates.length > 0 || showCandidates) && <Button variant="ghost" aria-pressed={showCandidates} disabled={locked} onClick={() => setShowCandidates(value => !value)}>{showCandidates ? '查看已保存' : `待确认 ${pendingCandidates.length} 条`}</Button>}
            </div>
            {!showCandidates && topics.length > 1 && <SelectField className="pw-profile-topic-select" label="主题" value={topic} options={[{ id: 'all', label: '所有主题' }, ...topics]} onChange={setTopic} isDisabled={locked} />}
            {memoryAvailable && !showCandidates && searchText && visibleMemories.length > 0 && <p className="pw-profile-list-hint" role="status">已保存 · 当前筛选匹配 {visibleMemories.length} 条</p>}
            {!memories.complete && memoryAvailable && <p className="pw-profile-list-hint">当前搜索范围为已读取的个人记忆。</p>}
          </div>}
          {showCandidates ? memoryAvailable && <section className="pw-profile-memory-group"><h2>待你确认<span>{searchText ? `${visibleCandidates.length} 条匹配` : pendingCandidates.length}</span></h2><p className="pw-profile-list-hint">这些建议还不是已保存的个人记忆。有原文时可先核对，不必现在决定。</p><div className="pw-profile-memory-list">{visibleCandidates.map(row => memoryRow(row, true))}</div>{!visibleCandidates.length && <p className="pw-profile-list-hint">{searchText ? '已读取的建议中没有匹配项。' : '暂未读到待确认的内容。'}</p>}</section>
            : topics.filter(item => visibleMemories.some(row => topicOf(row).id === item.id)).map(item => <section className="pw-profile-memory-group" key={item.id}><h2>{item.label}</h2><div className="pw-profile-memory-list">{visibleMemories.filter(row => topicOf(row).id === item.id).map(row => memoryRow(row, false))}</div></section>)}
          {savedMiss && <section className="pw-profile-empty">
            <h2>{topic === 'all' ? '当前已保存列表没有匹配项' : '所选主题没有匹配的记忆'}</h2>
            {topic !== 'all' && <Button variant="secondary" disabled={locked} onClick={() => setTopic('all')}>{matchingMemories.length ? `查看其他主题的 ${matchingMemories.length} 条记忆` : '取消主题筛选'}</Button>}
            {candidateMatchLink && <Button variant="ghost" disabled={locked} onClick={() => setShowCandidates(true)}>查看 {visibleCandidates.length} 条匹配的待确认建议</Button>}
            {historyMatchLink && <Button variant="ghost" disabled={locked} onClick={() => go('memory-history')}>查看 {historyRows.length} 条匹配的过去记忆</Button>}
            {searchText && !matchingMemories.length && !candidateMatchLink && !historyMatchLink && <p>试试其他关键词。</p>}
          </section>}
          {memoryAvailable && !showCandidates && !activeMemories.length && !searchText && topic === 'all' && <div className="pw-profile-empty"><IconSparkles aria-hidden="true" /><h2>当前列表里没有已保存的记忆</h2>
            {hasMemoryRows ? <p>待确认建议和过去的记忆不计入当前列表。</p> : !memories.complete ? <p>清单尚未完整读取，可先刷新记忆。</p> : <><p>告诉我一件你希望下次不用再解释的事。</p><Button leadingIcon={IconAdd} disabled={locked || !supported('profile.memory.createReview')} onClick={openNewMemory}>记住一件事</Button><p>也可以在对话中直接说“记住这件事”。</p></>}
          </div>}
          {memories.pendingConflicts.length > 0 && <Accordion className="pw-profile-disclosure"><AccordionItem id="personal-conflicts" title={`有 ${memories.pendingConflicts.length} 项信息需要核对`}><p className="pw-profile-help">这些信息存在差异，尚未自动覆盖已保存内容。</p>{memories.pendingConflicts.map((row, index) => <p key={string(row.id) || index} className="pw-profile-conflict-text">{string(row.text || row.summary || row.reason) || '请核对相关个人记忆的来源与内容。'}</p>)}</AccordionItem></Accordion>}
          <div className="pw-profile-collection-footer">{!historyMatchLink && <Button variant="ghost" leadingIcon={IconHistory} trailingIcon={IconChevronRight} disabled={locked} onClick={() => go('memory-history')}>过去的记忆</Button>}{onOpenAdvancedMemory && <Button variant="ghost" leadingIcon={IconArrowUpRight} disabled={locked} onClick={() => guarded(onOpenAdvancedMemory)}>项目记忆与更多管理</Button>}<Button variant="ghost" className="pw-profile-refresh" leadingIcon={IconRefresh} disabled={locked} onClick={refresh}>刷新记忆</Button></div>
        </>}
        {(loaded || !!manualEntry && enteredProfile) && view === 'memory-new' && !manualSaved && <div className="pw-profile-form">
          <p className="pw-profile-intro">{manualEntry ? '写一件之后可能会用到的事。只有点保存才会写入你的个人记忆，不会发送消息或调用模型。' : '手动保存的背景、兴趣或偏好可供以后参考。只对这次任务有效的要求，留在原对话即可。'}</p>
          {manualRetired ? !!newMemoryText.trim() && <p className="pw-profile-candidate-copy">{newMemoryText}</p> : <>
            <TextAreaField className="pw-profile-field" fieldClassName="pw-profile-input pw-profile-memory-input" label="希望记住什么" placeholder="一件你希望下次不用再解释的事" value={newMemoryText} onChange={setNewMemoryText} rows={5} maxLength={4000} resize="none" isDisabled={locked || createUnknown || !!manualEntry && !manualReady} />
            <SelectField className="pw-profile-language" label="有效期" value={newMemoryExpiry} options={expiryOptions} onChange={next => { if (next === 'custom' && !newMemoryCalendar) setNewMemoryCalendar(calendarTimeAt(Date.now() + 86400000)); setNewMemoryExpiry(next); }} isDisabled={locked || createUnknown || !!manualEntry && !manualReady} />
            {newMemoryExpiry === 'custom' && newMemoryCalendar && <CalendarDateTimeFields value={newMemoryCalendar} onChange={setNewMemoryCalendar} disabled={locked || createUnknown || !!manualEntry && !manualReady} label="到期时间" />}
            <p className="pw-profile-preference-priority">到期后不再作为当前背景。回答风格与做事方式可以在明确偏好中设置。</p>
            {!manualEntry && <Button variant="ghost" className="pw-profile-inline-link" trailingIcon={IconChevronRight} disabled={locked || createUnknown} onClick={() => openEditor('communication')}>设置交流偏好</Button>}
          </>}
        </div>}
        {loaded && view === 'memory-settings' && <div className="pw-profile-form pw-profile-memory-controls">
          <p className="pw-profile-intro">这两项适用于符合条件的私人对话，不修改各对话的暂停或新原话排除设置。</p>
          {policy ? <>
            <section className="pw-profile-switches"><StretchSwitch label="允许回答参考已保存的记忆" description="允许参考不代表每次都会用到。暂停参考不会删除已保存的记忆，也不会修改你填写的资料和偏好。" checked={policy.reading.enabled} onChange={enabled => saveControl('reading', enabled)} disabled={locked || !!controlsError || !supported('profile.memory.reading')} busy={pending === '正在保存记忆设置'} /></section>
            <section className="pw-profile-switches"><StretchSwitch label="允许从对话整理记忆候选" description="符合条件时会使用当前模型整理近期的本人原话，可能产生模型用量。候选仍由你确认是否记住。" checked={policy.learning.enabled} onChange={enabled => saveControl('learning', enabled)} disabled={locked || !!controlsError || !supported('profile.memory.learning')} busy={pending === '正在保存记忆设置'} /></section>
            <p className="pw-profile-preference-priority">明确填写的交流与做事偏好优先于推断的记忆。项目资料在各自项目范围内管理。</p>
          </> : !locked && <p className="pw-profile-list-hint">设置尚未完整读取。</p>}
          {controlsError && <p className="pw-profile-list-hint">{controlsError}</p>}
          <div className="pw-profile-collection-footer"><Button variant="ghost" leadingIcon={IconRefresh} disabled={locked} onClick={openMemorySettings}>重新读取设置</Button>{onOpenAdvancedMemory && <Button variant="ghost" leadingIcon={IconArrowUpRight} disabled={locked} onClick={() => guarded(onOpenAdvancedMemory)}>项目记忆与更多管理</Button>}</div>
        </div>}
        {loaded && view === 'memory-history' && <>
          <p className="pw-profile-intro">这里显示仍可查看、但不在当前背景中的个人记忆。</p>
          {(memories.history.length > 0 || !!query) && <SearchField className="pw-profile-memory-search" fieldClassName="pw-profile-input" aria-label="搜索过去的记忆" placeholder="搜索过去的记忆，空格分词" clearLabel="清除搜索" maxLength={256} value={query} onChange={setQuery} shape="rounded" isDisabled={locked} />}
          <div className="pw-profile-memory-list">{historyRows.map(row => memoryRow(row, false))}</div>
          {memoryAvailable && !historyRows.length && <div className="pw-profile-empty"><IconHistory aria-hidden="true" /><h2>{searchText ? '已读取的过去记忆中没有匹配项' : '暂未读到过去的记忆'}</h2>{searchText && <Button variant="ghost" onClick={() => setQuery('')}>清除搜索</Button>}</div>}
          {memoryError && <p className="pw-profile-list-hint" role="alert">{memoryError}</p>}
          <Button variant="ghost" className="pw-profile-refresh" leadingIcon={IconRefresh} disabled={locked} onClick={refresh}>刷新记忆</Button>
        </>}
        {view === 'memory' && selection && <div className="pw-profile-memory-editor">
          <span className="pw-profile-memory-status">{memoryOrigin(selection.record, selection.candidate)}</span>
          {selection.candidate ? <>
            <p className="pw-profile-intro">{selection.record.status === 'pending' ? expiredCandidate ? '已过这条建议的采纳期限。你仍可核对来源、暂不处理或忽略它。' : '这是一条建议，还没有作为个人记忆保存。采纳只是决定记住，不是核实为事实。' : retiredSuggestion ? '这条建议当前不可处理，不显示旧内容或原文。' : '这条建议已经处理，不能再次采纳或忽略。'}</p>
            {selection.record.status === 'pending' && !expiredCandidate && relatedRecords.length > 0 && <section className="pw-profile-memory-comparison"><h2>同项已有记录</h2>{relatedRecords.map(row => <div key={row.id}>
              <p className="pw-profile-candidate-copy">{string(row.text)}</p>
              {comparedMemoryEditable(row) &&
                <Button variant="ghost" size="small" trailingIcon={IconChevronRight} data-related-memory={row.id}
                  aria-label={'查看并编辑已保存的记忆：' + string(row.text).slice(0, 80)}
                  disabled={locked || memoryConflict || memoryUnknown || candidateResultUnclear} onClick={() => openRelatedMemory(row)}>查看并编辑</Button>}
            </div>)}<p className="pw-profile-list-hint">采纳会新增记忆。{relatedRecords.some(comparedMemoryEditable)
              ? '只提交这条已保存记忆的修改。返回时会重新核对原建议是否仍可查看。'
              : '如需修改已有记忆，请从已保存列表单独打开。'}</p><h2>待确认内容</h2></section>}
            {!retiredSuggestion && <p className="pw-profile-candidate-copy">{selection.originalText}</p>}
          </> : selection.historical ? <><p className="pw-profile-intro">这条内容已退出当前背景。</p><p className="pw-profile-candidate-copy">{selection.originalText}</p></> : <TextAreaField className="pw-profile-field" fieldClassName="pw-profile-input pw-profile-memory-input" label="记忆内容" value={selection.draft} onChange={value => { if (!busy.current) { setSelection(old => old ? { ...old, draft: value } : old); setNotice(''); } }} rows={5} maxLength={4000} resize="none" isDisabled={locked || pendingForget} />}
          {!selection.candidate && !selection.historical && <p className="pw-profile-list-hint">保存表示可以作为个人记忆参考，不代表内容已经核实。</p>}
          <p className="pw-profile-memory-meta">私人对话 · 关于你{memoryDates(selection.record) ? ` · ${memoryDates(selection.record)}` : ''}</p>
          {sourceHandle && canSetPriority(selection) && supported('profile.memory.review') && supported('profile.memory.confirm') && <div className="pw-profile-actions">
            <Button variant="ghost" size="small" disabled={locked || memoryConflict || memoryUnknown || memoryDirty(selection) || unresolvedTerm(selection.id)} onClick={preparePriority}>{selection.record.core ? '取消优先参考' : '让后续回答优先参考'}</Button>
          </div>}
          {!selection.candidate && !selection.historical && selection.value.canReviewShorterTerm === true && sourceHandle && supported('profile.memory.review') && supported('profile.memory.confirm') &&
            <OriginalMemoryTerm key={sourceHandle} prior={selection.record.expiresAt} disabled={locked || memoryConflict || memoryUnknown || memoryDirty(selection) || !!unresolvedTerm(selection.id)} onReview={prepareShorterTerm} />}
          {!selection.candidate && !selection.historical && memoryDirty(selection) && <Accordion className="pw-profile-disclosure"><AccordionItem id="personal-changes" title="查看修改前后"><div className="pw-profile-memory-comparison"><section><h3>原来记住的内容</h3><p>{selection.originalText}</p></section><section><h3>准备更新为</h3><p>{selection.draft}</p></section></div></AccordionItem></Accordion>}
          {!pendingForget && (memoryConflict || memoryUnknown) && <section className="pw-profile-conflict" role="alert"><h2>{memoryUnknown ? '请先核对保存结果' : '这条记忆已有变化'}</h2><p>当前输入已保留，重新读取不会自动覆盖。</p>{latestMemory ? <><div className="pw-profile-latest"><h3>最新内容</h3><p>{string(latestMemory.record?.text)}</p></div><div className="pw-profile-actions"><Button variant="secondary" size="small" disabled={locked} onClick={() => adoptLatestMemory(true)}>保留我的输入</Button><Button variant="ghost" size="small" disabled={locked} onClick={() => adoptLatestMemory(false)}>使用最新内容</Button></div></> : <Button variant="secondary" size="small" leadingIcon={IconRefresh} disabled={locked} onClick={reloadMemory}>读取最新记忆</Button>}</section>}
          {!retiredSuggestion && <PersonalMemorySources key={JSON.stringify([selection.id, selection.candidate, selection.record.revision, sourceHandle, presentationKey, state.memoryRefreshRevision, state.conversationId])}
            references={memorySources} manual={manualMemory(selection.record)} pending={locked} reload={reloadMemory}
            disabled={!memoryAvailable || memoryConflict || memoryUnknown || !sourceHandle || !supported('profile.memory.source') || locked && pending !== '正在读取来源'}
            start={work => { void action('正在读取来源', work); }}
            read={(index, offset) => call('profile.memory.source', { readHandle: sourceHandle, index, offset })} />}
          {selection.candidate ? selection.record.status === 'pending' && <>
            <p className="pw-profile-list-hint" id="personal-candidate-choice">暂不保存只返回，不改变建议状态。忽略建议会将这条移出待确认。</p>
            <div className="pw-profile-actions pw-profile-candidate-actions" role="group" aria-label="处理这条建议" aria-describedby="personal-candidate-choice">
              <Button disabled={locked || expiredCandidate || memoryConflict || memoryUnknown || candidateResultUnclear || settingsRequiredFor(feedback, 'accept', selection.id) || !supported('profile.memory.confirm')} onClick={() => prepareReview('accept')}>记住</Button>
              <Button variant="secondary" disabled={locked || memoryUnknown || candidateResultUnclear} onClick={back}>暂不保存</Button>
              <Button variant="ghost" disabled={locked || memoryConflict || memoryUnknown || candidateResultUnclear || !supported('profile.memory.confirm')} onClick={() => prepareReview('reject')}>忽略建议</Button>
            </div>
          </> : <Button variant="ghost" className="pw-profile-delete" leadingIcon={IconTrash} disabled={locked || memoryConflict || memoryUnknown || !supported('profile.memory.confirm')} onClick={() => prepareReview('forget')}>忘记这条记忆</Button>}
        </div>}
      </div>
    </div>
    <SettingsDialog isOpen={leaveOpen} title={confirmUnknownExit ? '这次修改的结果待核对，仍要离开？' : '放弃未保存的修改？'} onClose={() => { if (!busy.current) { pendingLeave.current = null; setLeaveOpen(false); } }}><div className="pw-profile-dialog"><p>{confirmUnknownExit ? '建议先查看这次修改的回执。离开不会撤销已经提交的操作，请勿重复提交。' : '当前修改还没有保存，离开后将恢复已保存的内容。'}</p><div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={() => { pendingLeave.current = null; setLeaveOpen(false); }}>{confirmUnknownExit ? '返回核对' : '继续编辑'}</Button><Button disabled={locked} onClick={() => { const next = pendingLeave.current; pendingLeave.current = null; setLeaveOpen(false); clearReview(); setEditor(null); setNewMemoryText(''); setNewMemoryExpiry('none'); setCreateUnknown(false); const cleanSelection = current.current.selection ? { ...current.current.selection, draft: current.current.selection.originalText } : null; setSelection(cleanSelection); current.current = { ...current.current, editor: null, selection: cleanSelection, review: null, leaveOpen: false, newMemoryText: '', newMemoryExpiry: 'none', createUnknown: false }; next?.(); }}>{confirmUnknownExit ? '离开' : '放弃修改'}</Button></div></div></SettingsDialog>
    <SettingsDialog isOpen={review?.operation === 'createAt'} title="记住到这个时间？" onClose={() => { if (!busy.current) clearReview(); }}>
      {review?.operation === 'createAt' && <div className="pw-profile-dialog"><p className="pw-profile-review-text">{string(review.value.afterText)}</p>
        <p>有效至 {termInZone(review.value.expiresAt, review.value.zone)}（{review.value.zone}）</p>
        <p>{expiredTermReview ? '本次核对已失效，没有保存。请返回重新选择。' : '可供以后的私人任务参考，到期后不再作为当前背景。已有相同内容时不会替换原期限。'}</p>
        <div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={clearReview}>返回编辑</Button><Button disabled={locked || expiredTermReview} onClick={confirmReview}>按此期限记住</Button></div>
      </div>}
    </SettingsDialog>
    <SettingsDialog isOpen={review?.operation === 'shorten'} title="只缩短这条记忆的期限？" onClose={() => { if (!busy.current) clearReview(); }}>
      {review?.operation === 'shorten' && <div className="pw-profile-dialog"><p className="pw-profile-review-text">{string(review.value.before.text)}</p>
        <p>原期限：{termInZone(review.value.before.expiresAt, review.value.zone)}<br />新期限：{termInZone(review.value.expiresAt, review.value.zone)}（{review.value.zone}）</p>
        <p>{expiredTermReview ? '本次核对已失效，请返回重新选择。' : '到新期限后，这条记忆不再作为个人背景。原文保留，本次更改不能撤销。'}</p>
        <div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={clearReview}>返回</Button><Button disabled={locked || expiredTermReview} onClick={confirmReview}>确认缩短</Button></div>
      </div>}
    </SettingsDialog>
    <SettingsDialog isOpen={review?.operation === 'core'} title={review?.value.core ? '优先参考这条记忆？' : '取消优先参考？'} onClose={() => { if (!busy.current) clearReview(); }}>
      {review?.operation === 'core' && <div className="pw-profile-dialog"><p className="pw-profile-review-text">{string(review.value.before.text)}</p>
        <p>{review.value.core ? '后续允许参考个人记忆的私人对话会优先考虑这条，仍受上下文限额。不代表每个回答都会采用。' : '保留这条记忆，以后仍可按问题相关性参考。'}</p>
        <div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={clearReview}>返回</Button><Button disabled={locked} onClick={confirmReview}>{review.value.core ? '设为优先参考' : '取消优先参考'}</Button></div>
      </div>}
    </SettingsDialog>
    <SettingsDialog isOpen={review?.operation === 'forget'} title="忘记这条记忆？" onClose={() => { if (!busy.current) clearReview(); }}><div className="pw-profile-dialog"><p className="pw-profile-review-text">{string(review?.value.before?.text) || selection?.originalText}</p><p>以后不再参考这条记忆，其他依赖同一来源的记忆和建议也可能退出列表。原聊天保留。这次遗忘不能撤销。</p>{memoryDirty(selection) && <p>当前未保存的编辑也会放弃。</p>}<div className="pw-profile-actions"><Button variant="secondary" disabled={locked} onClick={clearReview}>取消</Button><Button disabled={locked} onClick={confirmReview}>忘记</Button></div></div></SettingsDialog>
  </section>;
}

export default ProfileWorkspace;
