import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconChevronLeft, IconChevronRight, IconNotification, IconRefresh } from './icons';
import './knowledge-depth.css';
import './reminder-workspace.css';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { PressButton } from './library/press';
import { CalendarDateTimeFields } from './CalendarDateTimeFields';
import { FormDraftNotice, useFormDrafts, type RegisterBeforeLeave } from './form-drafts';
import { WheelTimeField } from './forms-library/uiux/WheelTimeField';
import { Accordion, AccordionItem, Button, CheckboxField, SearchField, SelectField, SettingsDialog, StretchSwitch, TextAreaField, TextField } from './forms-library';
import { reminderDeleteWarning, reminderDraft, reminderReviewPayload, reminderRuleState, reminderSubmissionResult, reminderTiming, type ReminderDraft, type ReminderIntent, type ReminderSubmission } from './reminder-contract';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type Editor = { intent: ReminderIntent; original: Data | null; draft: ReminderDraft; initialTiming: string };
const array = (value: any): Data[] => Array.isArray(value) ? value.filter(x => x && typeof x === 'object') : [];
const date = (value: any) => Number.isFinite(value) && value > 0 ? new Date(value).toLocaleString() : '';
const types = [{ id: 'once', label: '一次' }, { id: 'interval', label: '固定间隔' }, { id: 'daily', label: '每天' }, { id: 'weekly', label: '每周' }, { id: 'monthly', label: '每月' }, { id: 'cron', label: 'Cron 表达式' }];
const goalState = (state: string) => ({ succeeded: '已由原结果确认完成', running: '执行中', paused: '等待你核对或授权', waiting_user: '等待你核对或授权', awaiting_user: '等待你核对或授权', awaiting_authorization: '等待你核对或授权', failed: '执行失败，请查看原任务', cancelled: '已取消', stopping: '正在停止，尚未确认释放', expired: '有效时间已过', missed_window: '有效时间已过' } as Data)[state] || '仍需核对，尚无完成确认';

/** Existing forms, list hierarchy and host authority. No second scheduler or local saved records. */
export default function ReminderWorkspace({ state, data, supported, run, registerTransientBack, registerBeforeLeave }: {
  state: Data; data: Data; supported: (action: string) => boolean; run: Run;
  registerTransientBack: (handler: (() => boolean) | null) => void; registerBeforeLeave?: RegisterBeforeLeave;
}) {
  const formDraft = useFormDrafts(run, 'reminders', registerBeforeLeave);
  const rules = array(data.rules);
  const hasRules = Array.isArray(data.rules);
  const [editor, setEditor] = useState<Editor | null>(null), [review, setReview] = useState<Data | null>(null);
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [requesting, setRequesting] = useState(false);
  const [submission, setSubmission] = useState<ReminderSubmission | null>(null), [uncertain, setUncertain] = useState(false);
  const [checkRequests, setCheckRequests] = useState<{ list: string; receipts: string } | null>(null);
  const [selectedId, setSelectedId] = useState('');
  const [query, setQuery] = useState(''), [filter, setFilter] = useState('all');
  const selectedRule = rules.find(rule => rule.id === selectedId) || null;
  const root = useRef<HTMLDivElement | null>(null), heading = useRef<HTMLHeadingElement | null>(null);
  const listPosition = useRef(0), returnRule = useRef(''), returnIndex = useRef(0), previousRule = useRef<string | undefined>(undefined);
  const chooseRule = (id: string) => { listPosition.current = root.current?.closest('.pw-secondary-scroll')?.scrollTop || 0; returnRule.current = id; returnIndex.current = Array.from(root.current?.querySelectorAll<HTMLElement>('[data-reminder-id]') || []).findIndex(row => row.dataset.reminderId === id); setSelectedId(id); };
  useSurfaceEnter(root, selectedRule ? `rule:${selectedRule.id}` : 'list', { selector: ':scope > .pw-settings-section', depth: selectedRule ? 1 : 0, active: hasRules });
  const [advanced, setAdvanced] = useState(false), [receiptLimit, setReceiptLimit] = useState(10);
  const generation = useRef(0), mounted = useRef(true), locked = useRef(false);
  const busy = ['automation.save', 'automation.setEnabled', 'automation.delete'].some(key => (state.moduleBusy || []).includes(key));
  const reading = (state.moduleBusy || []).includes('automation.list');
  const readingError = Boolean(state.moduleErrors?.['automation.list']);
  const pending = requesting || busy || Boolean(submission) || uncertain;
  useLayoutEffect(() => {
    const changed = previousRule.current !== selectedRule?.id; previousRule.current = selectedRule?.id;
    const scroller = root.current?.closest('.pw-secondary-scroll');
    if (selectedRule) { if (!changed) return; if (scroller) scroller.scrollTop = 0; heading.current?.focus({ preventScroll: true }); }
    else if (returnRule.current && !reading) {
      if (scroller) scroller.scrollTop = listPosition.current;
      const entries = Array.from(root.current?.querySelectorAll<HTMLElement>('[data-reminder-id]:not(:disabled)') || []);
      const target = entries.find(row => row.dataset.reminderId === returnRule.current) || entries[Math.min(Math.max(0, returnIndex.current), entries.length - 1)];
      (target || root.current)?.focus({ preventScroll: true }); returnRule.current = '';
    }
  }, [selectedRule?.id, reading]);
  const refresh = async () => { const [list, receipts] = await Promise.all([run('automation.list'), run('automation.receipts')]); if (uncertain && list?.requestId && receipts?.requestId) setCheckRequests({ list: list.requestId, receipts: receipts.requestId }); };
  const close = () => { generation.current++; setEditor(null); setReview(null); setError(''); setAdvanced(false); };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  useEffect(() => {
    registerTransientBack(review ? () => { generation.current++; setReview(null); return true; } : editor ? () => { formDraft.guard(close); return true; } : selectedRule ? () => { setSelectedId(''); return true; } : null);
    return () => registerTransientBack(null);
  }, [Boolean(review), Boolean(editor), selectedRule?.id, registerTransientBack]);
  useEffect(() => {
    if (!submission) return;
    const result = reminderSubmissionResult(state, submission);
    if (result.kind === 'waiting') return;
    setSubmission(null); setNotice(result.message);
    if (result.kind === 'failed') { setUncertain(true); setError(result.message); }
    else { setUncertain(false); if (submission.command === 'save') formDraft.discard(close); else close(); void refresh().catch(() => {}); }
  }, [submission, state]);
  const open = (intent: ReminderIntent, original: Data | null = null) => {
    if (pending || locked.current || formDraft.loading) return;
    generation.current++; setError(''); setReview(null); setAdvanced(false);
    const draft = reminderDraft(original, intent, Intl.DateTimeFormat().resolvedOptions().timeZone);
    void formDraft.open('owner', intent + ':' + (original?.id || 'new'), String(original?.revision || 'new'), draft).then(saved => { if (saved && mounted.current) setEditor({ intent, original: original ? structuredClone(original) : null, draft: saved, initialTiming: reminderTiming(draft) }); });
  };
  useEffect(() => { if (editor) formDraft.update(editor.draft); }, [editor, formDraft.update]);
  useEffect(() => { if (formDraft.requiresRecheck) setUncertain(true); }, [formDraft.requiresRecheck]);
  const change = <K extends keyof ReminderDraft>(key: K, value: ReminderDraft[K]) => {
    generation.current++; setReview(null); setError('');
    setEditor(current => current ? { ...current, draft: { ...current.draft, [key]: value } } : null);
  };
  const prepare = async (action: string, args: Data) => {
    if (locked.current || pending || reading || readingError || !supported(action)) return;
    const current = ++generation.current; locked.current = true; setRequesting(true); setError('');
    try { const result = await run(action, args); if (mounted.current && current === generation.current) setReview(result); }
    catch (failure) { if (mounted.current && current === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { locked.current = false; if (mounted.current) setRequesting(false); }
  };
  const preview = () => {
    if (!editor || formDraft.stale || !!formDraft.conflict || formDraft.requiresRecheck) return;
    try { void prepare('automation.reviewSchedule', reminderReviewPayload(editor.draft, editor.intent, editor.original, editor.initialTiming)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const confirm = async () => {
    if (!review?.reviewHandle || locked.current || pending || !supported('automation.confirmReview')) return;
    // Clear before calling the bridge. An ambiguous acknowledgement cannot replay this review.
    const handle = review.reviewHandle; setReview(null); locked.current = true; setRequesting(true); setError('');
    try {
      if (editor) await formDraft.markSubmitted();
      if (!mounted.current || document.visibilityState !== 'visible') return;
      const result = await run('automation.confirmReview', { reviewHandle: handle });
      if (!mounted.current) return;
      if (!result?.requestId || !['save', 'setEnabled', 'delete'].includes(result.command)) throw new Error('原操作登记结果尚未确认。');
      setSubmission({ requestId: result.requestId, command: result.command });
    } catch (failure) {
      if (mounted.current) { setUncertain(true); setError((failure instanceof Error ? failure.message : String(failure)) + ' 请刷新核对原记录，勿重复提交。'); }
    } finally { locked.current = false; if (mounted.current) setRequesting(false); }
  };
  const originalCurrent = !editor?.original || rules.some(rule => rule.id === editor.original!.id && rule.revision === editor.original!.revision && rule.sourceInvalidated !== true && rule.sourceCheckPending !== true);
  const receiptsBusy = (state.moduleBusy || []).includes('automation.receipts');
  const receiptsError = state.moduleErrors?.['automation.receipts'];
  const receiptsReady = Array.isArray(state.moduleResults?.['automation.receipts']?.receipts);
  const receipts = array(state.moduleResults?.['automation.receipts']?.receipts).slice().reverse();
  const draft = editor?.draft;
  const calendar = draft && ['daily', 'weekly', 'monthly', 'cron'].includes(draft.type);
  const receiptTiming = (receipt: Data) => <dl className="pw-reminder-receipt-facts">
    {receipt.occurrenceAt > 0 && <><div><dt>计划时间</dt><dd>{date(receipt.occurrenceAt)}</dd></div><div><dt>领取延后</dt><dd>{Math.max(0, (receipt.claimedAt || receipt.occurrenceAt) - receipt.occurrenceAt) / 1000} 秒</dd></div></>}
  </dl>;
  const unknownRead = !!checkRequests && !reading && !readingError && !receiptsBusy && !receiptsError && hasRules && receiptsReady && state.moduleResultInputs?.['automation.list']?._requestId === checkRequests.list && state.moduleResultInputs?.['automation.receipts']?._requestId === checkRequests.receipts;
  const unknownControls = uncertain && <div className="pw-field-hint"><p>提交结果尚待核对。先读取已有提醒与投递记录；不会自动重试。</p><div className="pw-form-actions"><Button variant="secondary" disabled={reading || receiptsBusy} onClick={() => { void refresh().catch(failure => setError((failure as Error).message)); }}>读取原记录核对结果</Button><Button variant="secondary" disabled={!unknownRead} onClick={() => { formDraft.clearSubmitted(); setUncertain(false); setCheckRequests(null); setError(''); }}>已核对原记录，继续编辑</Button></div></div>;
  const receiptRow = (receipt: Data, showTiming = true) => <article key={receipt.id} className="pw-reminder-record pw-reminder-result" data-reminder-receipt-id={receipt.id} data-motion-key={`${showTiming ? 'history' : 'attention'}:${receipt.id}`}>
    <header><h3>{receipt.title || '原发生记录'}</h3><span className="pw-reminder-result-state">{receipt.status === 'delivery_unknown' ? '投递未知' : receipt.status === 'awaiting_user' ? '等待你查看' : receipt.status === 'blocked_notifications' ? '通知受阻' : receipt.status}</span></header>
    {receipt.message && <p className="pw-reminder-result-message">{receipt.message}</p>}
    {receipt.goalState && <p className="pw-reminder-result-goal">原任务：{goalState(receipt.goalState.state)}</p>}
    {showTiming && receiptTiming(receipt)}
    {receipt.kind === 'phone_goal' && ['awaiting_user', 'blocked_notifications'].includes(receipt.status) && <div className="pw-form-actions"><Button variant="secondary" disabled={receiptsBusy || !!receiptsError || !supported('automation.prepareOccurrence')} onClick={() => { void run('automation.prepareOccurrence', { occurrenceId: receipt.id }).catch(failure => setError(failure instanceof Error ? failure.message : String(failure))); }}>准备原手机任务（不执行）</Button></div>}
  </article>;
  const attentionReceipts = receipts.filter(receipt => ['delivery_unknown', 'blocked_notifications', 'awaiting_user'].includes(receipt.status));
  const visibleRules = rules.filter(rule => [rule.title, rule.goal, rule.schedule?.zone].join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
    .filter(rule => filter === 'all' || (filter === 'enabled' ? rule.enabled === true : rule.enabled !== true));
  const hasFilters = Boolean(query.trim()) || filter !== 'all';
  const showDirectoryTools = rules.length > 0 || hasFilters;
  const emptyDirectory = hasRules && !rules.length && !hasFilters && !reading && !readingError && !pending && !error && !receiptsBusy && !receiptsError;
  const showReceiptHistory = receipts.length > 0 || receiptsBusy || Boolean(receiptsError) || !receiptsReady || uncertain || Boolean(submission);
  const clearFilters = () => { setQuery(''); setFilter('all'); };
  const newReminder = <PressButton compact disabled={pending || reading || readingError || !hasRules || !supported('automation.reviewSchedule')} onClick={() => open('create')}>新建提醒</PressButton>;
  const refreshButton = <Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="刷新提醒与发生记录" disabled={reading || receiptsBusy || !supported('automation.list')} onClick={() => { void refresh().catch(() => {}); }} />;
  const emptyResults = <div className="pw-reminder-no-results" role="status"><p>没有符合条件的提醒。</p><Button variant="secondary" onClick={clearFilters}>清除筛选</Button></div>;
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const tomorrow = new Date(today); tomorrow.setDate(today.getDate() + 1);
  const afterTomorrow = new Date(tomorrow); afterTomorrow.setDate(tomorrow.getDate() + 1);
  const ruleGroup = (rule: Data) => {
    const availability = reminderRuleState(rule, Date.now());
    return !availability.sourceCurrent ? 'source' : availability.exhausted ? 'ended' : !rule.enabled ? 'paused' : !(rule.nextAt > 0) ? 'unknown' : rule.nextAt < tomorrow.getTime() ? 'today' : rule.nextAt < afterTomorrow.getTime() ? 'tomorrow' : 'later';
  };
  const ruleGroups = [{ id: 'source', label: '来源需核对' }, { id: 'today', label: '今天与待发生' }, { id: 'tomorrow', label: '明天' }, { id: 'later', label: '之后' }, { id: 'unknown', label: '下次时间待核对' }, { id: 'paused', label: '已暂停' }, { id: 'ended', label: '安排已结束' }]
    .map(group => ({ ...group, rules: visibleRules.filter(rule => ruleGroup(rule) === group.id).sort((a, b) => (a.nextAt || 0) - (b.nextAt || 0)) })).filter(group => group.rules.length > 0);
  const collectionActive = !editor && !review && !state.webWorkspaceTaskOpen;
  useCollectionMotion(root, JSON.stringify([selectedRule?.id || 'list', query.trim(), filter, state.webWorkspaceResumeRevision]), JSON.stringify([
    ruleGroups.map(group => [group.id, group.rules.map(rule => [rule.id, rule.revision, rule.nextAt])]), notice, error,
  ]), { selector: '[data-reminder-id]', active: hasRules && collectionActive });
  useCollectionMotion(root, JSON.stringify([selectedRule?.id || 'list', receiptLimit, state.webWorkspaceResumeRevision]), JSON.stringify(receipts.map(receipt => [receipt.id, receipt.status])), {
    selector: '[data-reminder-receipt-id]', active: receiptsReady && !receiptsBusy && collectionActive,
  });
  return <div ref={root} className="pw-reminder-workspace pw-knowledge-workspace" tabIndex={-1}>
    <section className="pw-settings-section" aria-label="提醒规则">
      {selectedRule && <Button variant="ghost" leadingIcon={IconChevronLeft} disabled={pending} onClick={() => setSelectedId('')}>返回提醒列表</Button>}
      {selectedRule ? <header className="pw-followup-heading"><div><h2 ref={heading} tabIndex={-1}>{selectedRule.title || '未命名提醒'}</h2></div>{refreshButton}</header> : !emptyDirectory && <div className="pw-reminder-directory-toolbar">{hasRules && rules.length > 0 && <p>{rules.length} 条提醒</p>}<div className="pw-reminder-directory-actions">{refreshButton}{newReminder}</div></div>}
      {!editor && <FormDraftNotice draft={formDraft} />}
    {notice && <MotionFeedback identity={notice} className="pw-field-hint" role="status">{notice}</MotionFeedback>}
      {unknownControls}
      {error && <MotionFeedback identity={error} className="pw-field-error" role="alert">{error}</MotionFeedback>}
      {!selectedRule && receiptsReady && attentionReceipts.length > 0 && <section className="pw-reminder-attention" aria-label="需要核对的提醒结果">
        <header className="pw-reminder-attention-heading"><div><h3>需要你核对</h3><span>{attentionReceipts.length}</span></div><Button variant="ghost" disabled={receiptsBusy || !supported('automation.receipts')} onClick={() => { void run('automation.receipts').catch(failure => setError(failure instanceof Error ? failure.message : String(failure))); }}>{receiptsBusy ? '核对中…' : '核对结果'}</Button></header>
        {receiptsError && <p className="pw-field-error" role="alert">本次核对未成功，以下保留上次读取的结果。</p>}
        {attentionReceipts.map(receipt => receiptRow(receipt, false))}
        {attentionReceipts.some(receipt => receipt.status === 'delivery_unknown') && <p className="pw-reminder-query-note">投递未知只查询原记录，不会重新发送。</p>}
        <Accordion variant="divided" className="pw-compact-disclosures pw-reminder-delivery-details"><AccordionItem id="reminder-attention-timing" title="投递明细">
          {attentionReceipts.map(receipt => <section key={receipt.id}><h4>{receipt.title || '原发生记录'}</h4>{receiptTiming(receipt)}</section>)}
        </AccordionItem></Accordion>
      </section>}
      {!selectedRule && showDirectoryTools && <div className="pw-reminder-plan-tools"><SearchField className="pw-secondary-search" aria-label="查找已读取的提醒" placeholder="搜索提醒" value={query} onChange={setQuery} /><div className="pw-knowledge-filters" role="group" aria-label="提醒状态筛选">{[{ id: 'all', label: '全部' }, { id: 'enabled', label: '已启用' }, { id: 'disabled', label: '暂停与结束' }].map(item => <Button key={item.id} variant="ghost" aria-pressed={filter === item.id} onClick={() => setFilter(item.id)}>{item.label}</Button>)}</div></div>}
      {reading && hasRules && <MotionFeedback identity="refreshing" className="pw-field-hint" role="status">正在更新提醒清单，保留已读取的内容…</MotionFeedback>}
      {readingError && hasRules && <MotionFeedback identity={String(state.moduleErrors['automation.list'])} className="pw-field-error" role="alert">这次读取未完成。保留上次内容，请刷新核对后再操作。</MotionFeedback>}
      {!hasRules ? reading ? <p className="pw-secondary-empty" role="status">正在读取原提醒清单…</p> : readingError ? <p className="pw-field-error" role="alert">原提醒清单未读成功，请刷新核对。{String(state.moduleErrors['automation.list'])}</p> : <p className="pw-secondary-empty">尚未读取原提醒清单，请刷新核对。</p> : rules.length ? selectedRule ? [selectedRule].map(rule => {
        const availability = reminderRuleState(rule, Date.now());
        return <article key={rule.id} className="pw-reminder-record"><header className="pw-knowledge-object-state"><span>{rule.enabled ? '已启用' : availability.exhausted ? '安排已结束' : '已暂停'}</span><span>{types.find(type => type.id === rule.schedule?.type)?.label || '原时间安排'}{rule.schedule?.zone ? ' · ' + rule.schedule.zone : ''}</span></header>
          <p className="pw-knowledge-next-time">{availability.exhausted ? '这次安排已到期或已有发生记录；原结果仍以回执为准。' : rule.nextAt > 0 ? '计划时间：' + date(rule.scheduledAt || rule.nextAt) : '已暂停或安排已结束'}</p>
          {rule.kind === 'phone_goal' && <section className="pw-knowledge-section"><h3>到时提醒你开始</h3><p className="pw-user-text">{rule.goal || '原记录未提供任务目标，请核对后操作。'}</p><p className="pw-field-hint">只提醒查看目标，仍需你明确开始手机任务。</p></section>}

          {!availability.sourceCurrent && <p>原提醒来源已变化或仍待核对。请刷新后操作。</p>}
          {availability.exhausted && Object.hasOwn(rule, 'nativeOrigin') && <p>此提醒来自原对话；请在原对话重新明确提出安排，未复制旧来源。</p>}
          {availability.exhausted && !Object.hasOwn(rule, 'nativeOrigin') && rule.manualOriginVersion !== 1 && <p>此旧提醒的手建来源尚未确认，请从空白明确新建，未复制旧目标。</p>}
          {!availability.editable && <p>此日历格式或版本暂不支持编辑；可暂停保留原记录，或核对后删除。</p>}
          <div className="pw-form-actions">{availability.canReschedule && <Button variant="secondary" disabled={pending || reading || readingError} onClick={() => open('reschedule', rule)}>重新安排</Button>}<Button disabled={pending || reading || readingError || !availability.canEdit} onClick={() => open('edit', rule)}>编辑</Button><Button variant="secondary" disabled={pending || reading || readingError || !availability.canToggle || !supported('automation.reviewRule')} onClick={() => { void prepare('automation.reviewRule', { id: rule.id, revision: rule.revision, command: 'setEnabled', enabled: !rule.enabled }); }}>{rule.enabled ? '暂停提醒' : '启用提醒'}</Button></div>
          <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="reminder-metadata" title="调度详情与版本" description={`${types.find(type => type.id === rule.schedule?.type)?.label || '原时间安排'} · ${rule.schedule?.zone || '以手机原记录为准'}`}>
          <dl className="pw-facts"><div><dt>时间安排</dt><dd>{types.find(type => type.id === rule.schedule?.type)?.label || '暂不支持的格式'}{rule.schedule?.expression ? ' · ' + rule.schedule.expression : ''}{rule.schedule?.zone ? ' · ' + rule.schedule.zone : ''}</dd></div><div><dt>规则版本</dt><dd>{rule.revision}</dd></div>{rule.nextAt > 0 && rule.nextAt !== rule.scheduledAt && <div><dt>下次检查</dt><dd>{date(rule.nextAt)}</dd></div>}</dl><Button variant="ghost" disabled={pending || reading || readingError || !supported('automation.reviewRule')} onClick={() => { void prepare('automation.reviewRule', { id: rule.id, revision: rule.revision, command: 'delete' }); }}>删除</Button></AccordionItem></Accordion>
        </article>;
      }) : <>{!visibleRules.length && emptyResults}{ruleGroups.map(group => <section key={group.id} className="pw-knowledge-directory-group pw-reminder-plan-group" aria-label={group.label}><header className="pw-knowledge-group-heading"><h3>{group.label}</h3><span>{group.rules.length}</span></header><div className="pw-reminder-rules pw-followup-list">{group.rules.map(rule => {
        const availability = reminderRuleState(rule, Date.now());
        const plannedAt = rule.scheduledAt || rule.nextAt;
        const plannedDate = rule.enabled && availability.sourceCurrent && !availability.exhausted && Number.isFinite(plannedAt) && plannedAt > 0 ? new Date(plannedAt) : null;
        const status = rule.enabled ? '已启用' : availability.exhausted ? '安排已结束' : '已暂停';
        const schedule = types.find(type => type.id === rule.schedule?.type)?.label || '原时间安排';
        return <Button key={rule.id} variant="ghost" className="pw-reminder-rule-row" data-reminder-id={rule.id} data-motion-key={`rule:${rule.id}`} trailingIcon={IconChevronRight} disabled={pending || reading || readingError} onClick={() => chooseRule(rule.id)} aria-label={`查看提醒：${rule.title || '未命名提醒'}`} aria-description={`${status} · ${schedule} · ${!availability.sourceCurrent ? '来源有变化，需核对' : rule.nextAt > 0 ? `计划 ${date(plannedAt)}` : '没有下一次安排'}`}>
          <span className="pw-reminder-rule-layout"><span className="pw-reminder-rule-time" aria-hidden="true"><strong>{plannedDate ? plannedDate.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : '—'}</strong><small>{plannedDate ? plannedDate.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', ...(plannedDate.getFullYear() !== new Date().getFullYear() ? { year: 'numeric' } : {}) }) : !availability.sourceCurrent ? '待核对' : availability.exhausted ? '已结束' : '无安排'}</small></span>
          <span className="pw-reminder-rule-row-copy"><strong>{rule.title || '未命名提醒'}</strong><small>{schedule} · {status}</small>{!availability.sourceCurrent && <small className="pw-reminder-rule-warning">来源有变化，需核对</small>}{availability.sourceCurrent && rule.enabled && !availability.exhausted && !plannedDate && <small>下次时间待核对</small>}</span></span>
        </Button>;
      })}</div></section>)}</> : emptyDirectory ? <div className="pw-reminder-directory-empty"><IconNotification size={28} aria-hidden="true"/><h2>还没有提醒</h2><p>安排要记得的事，创建前会先核对时间。</p><div className="pw-form-actions">{newReminder}<Button variant="ghost" leadingIcon={IconRefresh} disabled={reading || receiptsBusy || !supported('automation.list')} onClick={() => { void refresh().catch(() => {}); }}>重新读取</Button></div></div> : pending ? <p className="pw-field-hint" role="status">{uncertain ? '原提交结果尚待核对，请刷新已有提醒与发生记录。' : '正在处理原提醒请求，请等待原操作回执。'}</p> : hasFilters && !error && !receiptsBusy && !receiptsError ? emptyResults : null}
    </section>
    {receiptsError && <p className="pw-field-error" role="alert">{String(receiptsError)}</p>}
    {showReceiptHistory && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="reminder-receipts" title="最近投递" description={receiptsBusy ? '正在读取发生记录…' : receiptsError ? '本次读取未完成' : receipts.length ? `${receipts.length} 条原发生记录` : '核对原投递与任务结果'}>
    <section className="pw-settings-section" aria-label="原发生与投递回执"><header className="pw-section-heading"><p>发生、投递与原任务完成分开。准备会建立或定位原手机目标，仍须你确认执行。未知结果只核对原记录，不会重发。</p></header>
      <Button variant="ghost" leadingIcon={IconRefresh} disabled={receiptsBusy || !supported('automation.receipts')} onClick={() => { void run('automation.receipts').catch(failure => setError(failure instanceof Error ? failure.message : String(failure))); }}>重新读取发生记录</Button>
      {receiptsBusy ? <p className="pw-secondary-empty" role="status">正在读取原发生记录…</p> : receiptsError ? <p className="pw-field-hint">本次读取未完成，请重新核对原记录。</p> : !receiptsReady ? <p className="pw-secondary-empty">尚未读取原发生记录，请刷新核对。</p> : receipts.length ? receipts.slice(0, receiptLimit).map(receipt => receiptRow(receipt)) : uncertain || submission ? <p className="pw-field-hint">原提交尚待核对，请先重新读取记录。</p> : <p className="pw-secondary-empty">暂无发生记录。</p>}
      {!receiptsBusy && !receiptsError && receipts.length > receiptLimit && <Button variant="secondary" onClick={() => setReceiptLimit(value => value + 10)}>显示更早记录</Button>}
    </section>
    </AccordionItem></Accordion>}
    <SettingsDialog isOpen={Boolean(editor || review)} title={review ? review.command === 'save' ? '核对提醒计划' : review.command === 'delete' ? '删除这个提醒？' : review.enabled ? '启用这个提醒？' : '暂停这个提醒？' : editor?.intent === 'edit' ? '编辑提醒' : editor?.intent === 'reschedule' ? '重新安排提醒' : '新建提醒'} onClose={() => { if (review) { generation.current++; setReview(null); } else formDraft.guard(close); }}><div className={'pw-knowledge-dialog' + (review ? '' : ' pw-knowledge-edit-dialog')}>
      {review ? <>
        <p>{review.rule?.title}</p><p className="pw-field-hint">{review.intent === 'reschedule' ? '来源规则版本：' + editor?.original?.revision + '，将新建独立提醒。' : review.rule?.revision ? '原规则版本：' + review.rule.revision : '将新建独立提醒。'}</p>{review.rule?.kind === 'phone_goal' && <p className="pw-receipt-copy">手机任务目标：{review.rule.goal}</p>}
        {array(review.preview?.occurrences).map((occurrence, index) => <div key={index} className="pw-selected-object"><p>预计：{String(occurrence.local || '').replace('T', ' ')} · {occurrence.zone} · {occurrence.offset}</p>{occurrence.dstResolution === 'gap_shifted_forward' && <p className="pw-field-hint">原当地时间不存在，已按策略向后移。</p>}{occurrence.dstResolution === 'overlap_earlier' && <p className="pw-field-hint">当地时间重复，使用较早一次。</p>}{occurrence.dstResolution === 'overlap_later' && <p className="pw-field-hint">当地时间重复，使用较晚一次。</p>}</div>)}
        <p className="pw-field-hint">{review.command === 'delete' ? reminderDeleteWarning : review.command === 'setEnabled' ? '只改变此提醒的后续调度，不启动或停止原任务。' : review.intent === 'edit' ? '保留原启停状态、已用次数和其他策略。旧版本未投递的提醒会取消。' : review.intent === 'reschedule' ? '这是新的未来提醒。原发生、任务和未知结果保留，不会重放或替换。' : '只保存提醒计划。手机任务仍需要你查看并明确开始。'}</p>
        {review.command === 'save' && <p className="pw-field-hint">以上由手机原日历计算。固定时间以此预览为准；系统可能延后，不保证秒级准点。</p>}
        <div className="pw-form-actions"><Button variant="secondary" onClick={() => { generation.current++; setReview(null); }}>{editor ? '返回修改' : '取消'}</Button><PressButton compact disabled={pending || !supported('automation.confirmReview')} onClick={() => { void confirm(); }}>{review.command === 'delete' ? '确认删除' : review.command === 'setEnabled' ? review.enabled ? '确认启用' : '确认暂停' : editor?.intent === 'edit' ? '保存修改' : '创建提醒'}</PressButton></div>
      </> : draft && <>
        {editor!.intent === 'reschedule' && <p className="pw-field-hint">新建一个未来提醒，原发生与结果保留。请核对目标和新时间。</p>}
        {editor!.intent === 'edit' && <p className="pw-field-hint">正在编辑原规则版本 {editor!.original?.revision}。仅改内容时保留原安排与固定起点。</p>}
        {!originalCurrent && <p className="pw-field-error" role="alert">原提醒已变化或来源暂不可用，请关闭后刷新核对。</p>}
        <FormDraftNotice draft={formDraft} />{unknownControls}
        <div className="pw-form-grid"><section className="pw-knowledge-form-section"><h3>内容与时间</h3><TextAreaField label="提醒内容" value={draft.title} onChange={value => change('title', value.slice(0, 200))} isDisabled={pending} />
          <SelectField label="时间安排" value={draft.type} options={types} onChange={value => change('type', value)} isDisabled={pending} />
          {draft.type === 'once' ? <CalendarDateTimeFields value={draft} disabled={pending} onChange={value => { generation.current++; setReview(null); setError(''); setEditor(current => current ? { ...current, draft: { ...current.draft, ...value } } : null); }} /> : calendar ? <>{draft.type === 'cron' ? <TextField label="Cron：分 时 日 月 周" value={draft.cron} onChange={value => change('cron', value.slice(0, 512))} isDisabled={pending} hint="例如 0 9 * * 1-5 为工作日 09:00。支持数字、*、逗号、递增范围和步长；周日为 0。日与周至少一项不限制。" /> : <WheelTimeField label="当地时间 HH:mm" value={draft.time} onChange={value => change('time', value.slice(0, 5))} isDisabled={pending} />}<TextField label="时区" value={draft.zone} onChange={value => change('zone', value.slice(0, 80))} isDisabled={pending} hint="例如 Asia/Shanghai。预览由手机核对时区与夏令时。" /></> : <TextField label={draft.type === 'interval' ? '每隔多少分钟' : '多少分钟后'} inputMode="numeric" value={draft.minutes} onChange={value => change('minutes', value.replace(/\D/g, '').slice(0, 6))} isDisabled={pending} hint="1 至 525600 分钟。新建或修改时间后，从本次核对时计算首次时间。" />}
          {draft.type === 'weekly' && <fieldset disabled={pending}><legend className="pw-field-label">每周日期</legend><div className="pw-form-actions">{['周一', '周二', '周三', '周四', '周五', '周六', '周日'].map((label, index) => <CheckboxField key={label} isSelected={draft.weekdays.includes(index + 1)} onChange={checked => change('weekdays', checked ? [...draft.weekdays, index + 1] : draft.weekdays.filter(value => value !== index + 1))}>{label}</CheckboxField>)}</div></fieldset>}
          {draft.type === 'monthly' && <><TextField label="每月几日（1–31）" inputMode="numeric" value={draft.monthDay} onChange={value => change('monthDay', value.replace(/\D/g, '').slice(0, 2))} isDisabled={pending} /><StretchSwitch label="短月使用最后一天" description={draft.lastDay ? '例如 31 日遇到 2 月，使用 2 月最后一天。' : '当月没有所选日期时，跳过当月。'} checked={draft.lastDay} onChange={value => change('lastDay', value)} disabled={pending} /></>}
          </section><section className="pw-knowledge-form-section"><h3>到时做什么</h3><SelectField label="职责类型" value={draft.phone ? 'phone' : 'notice'} options={[{ id: 'notice', label: '提醒我 · 发送本机通知', description: '适合记得一件事或一个时间。' }, { id: 'phone', label: '提醒我开始手机任务', description: '到时提醒查看目标，由你明确开始。' }]} onChange={value => change('phone', value === 'phone')} isDisabled={pending} /><p className="pw-field-hint">保存只安排提醒，不会自动操作手机。</p>{draft.phone && <TextAreaField label="手机任务目标" value={draft.goal} onChange={value => change('goal', value.slice(0, 4000))} isDisabled={pending} />}
          </section><Accordion variant="divided" expandedKeys={advanced ? ['advanced'] : []} onExpandedChange={keys => setAdvanced(keys.has('advanced'))}><AccordionItem id="advanced" title="高级选项" description="错过安排、任务重叠与夏令时策略">
          {advanced && <>{calendar && <><StretchSwitch label="不存在的当地时间跳过" checked={draft.skipGap} onChange={value => change('skipGap', value)} disabled={pending} /><StretchSwitch label="重复时间选择较晚一次" checked={draft.laterOverlap} onChange={value => change('laterOverlap', value)} disabled={pending} /></>}{draft.type !== 'once' && <><SelectField label="错过安排时间时" value={draft.misfire} onChange={value => change('misfire', value)} isDisabled={pending} options={[{ id: 'skip', label: '跳过旧实例，只处理最新一次' }, { id: 'coalesce', label: '合并错过的实例为一次' }, { id: 'catch_up', label: '按原安排逐次补处理' }]} />{draft.misfire === 'catch_up' && <TextField label="本轮最多补处理次数（1–20）" inputMode="numeric" value={draft.maxCatchUp} onChange={value => change('maxCatchUp', value.replace(/\D/g, '').slice(0, 2))} isDisabled={pending} />}<SelectField label="原任务仍未结束时" value={draft.overlap} onChange={value => change('overlap', value)} isDisabled={pending} options={[{ id: 'buffer_one', label: '保留一个待处理实例' }, { id: 'skip', label: '跳过新实例' }, { id: 'allow', label: '允许并行，仍各自需要原任务授权' }]} /></>}</>}
          </AccordionItem></Accordion>
        </div>
        {error && <MotionFeedback identity={error} className="pw-field-error" role="alert">{error}</MotionFeedback>}
        {(submission || requesting) && <p className="pw-field-hint" role="status">{requesting ? '正在核对或登记请求…' : '请求已登记，等待原操作回执。'}</p>}
        <div className="pw-form-actions pw-knowledge-edit-actions"><Button variant="secondary" onClick={() => formDraft.guard(close)}>{pending ? '返回并核对记录' : '返回并保留草稿'}</Button><Button variant="ghost" disabled={pending} onClick={() => formDraft.discard(close)}>放弃草稿</Button><PressButton compact disabled={pending || formDraft.stale || !!formDraft.conflict || formDraft.requiresRecheck || reading || readingError || !originalCurrent || !supported('automation.reviewSchedule')} onClick={preview}>核对提醒计划</PressButton></div>
      </>}
    </div></SettingsDialog>
  </div>;
}
