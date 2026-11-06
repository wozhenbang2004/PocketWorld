import { useEffect, useRef, useState } from 'react';
import { PressButton } from './library/press';
import { WheelTimeField } from './forms-library/uiux/WheelTimeField';
import { Button, CheckboxField, RadioOptions, SelectField, SettingsDialog, StretchSwitch, TextAreaField, TextField } from './forms-library';
import { reminderDeleteWarning, reminderDraft, reminderReviewPayload, reminderRuleState, reminderSubmissionResult, reminderTiming, type ReminderDraft, type ReminderIntent, type ReminderSubmission } from './reminder-contract';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type Editor = { intent: ReminderIntent; original: Data | null; draft: ReminderDraft; initialTiming: string };
const array = (value: any): Data[] => Array.isArray(value) ? value.filter(x => x && typeof x === 'object') : [];
const date = (value: any) => Number.isFinite(value) && value > 0 ? new Date(value).toLocaleString() : '';
const types = [{ id: 'once', label: '一次' }, { id: 'interval', label: '固定间隔' }, { id: 'daily', label: '每天' }, { id: 'weekly', label: '每周' }, { id: 'monthly', label: '每月' }, { id: 'cron', label: 'Cron 表达式' }];
const goalState = (state: string) => ({ succeeded: '已由原结果确认完成', running: '执行中', paused: '等待你核对或授权', waiting_user: '等待你核对或授权', awaiting_user: '等待你核对或授权', awaiting_authorization: '等待你核对或授权', failed: '执行失败，请查看原任务', cancelled: '已取消', stopping: '正在停止，尚未确认释放', expired: '有效时间已过', missed_window: '有效时间已过' } as Data)[state] || '仍需核对，尚无完成确认';

/** Existing forms, list hierarchy and host authority. No second scheduler or local saved records. */
export default function ReminderWorkspace({ state, data, supported, run, registerTransientBack }: {
  state: Data; data: Data; supported: (action: string) => boolean; run: Run;
  registerTransientBack: (handler: (() => boolean) | null) => void;
}) {
  const rules = array(data.rules);
  const hasRules = Array.isArray(data.rules);
  const [editor, setEditor] = useState<Editor | null>(null), [review, setReview] = useState<Data | null>(null);
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [requesting, setRequesting] = useState(false);
  const [submission, setSubmission] = useState<ReminderSubmission | null>(null), [uncertain, setUncertain] = useState(false);
  const [advanced, setAdvanced] = useState(false), [receiptLimit, setReceiptLimit] = useState(10);
  const generation = useRef(0), mounted = useRef(true), locked = useRef(false);
  const busy = ['automation.save', 'automation.setEnabled', 'automation.delete'].some(key => (state.moduleBusy || []).includes(key));
  const reading = (state.moduleBusy || []).includes('automation.list');
  const readingError = Boolean(state.moduleErrors?.['automation.list']);
  const pending = requesting || busy || Boolean(submission) || uncertain;
  const refresh = async () => { await Promise.all([run('automation.list'), run('automation.receipts')]); };
  const close = () => { generation.current++; setEditor(null); setReview(null); setError(''); setAdvanced(false); };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  useEffect(() => {
    registerTransientBack(review ? () => { generation.current++; setReview(null); return true; } : editor ? () => { close(); return true; } : null);
    return () => registerTransientBack(null);
  }, [Boolean(review), Boolean(editor), registerTransientBack]);
  useEffect(() => {
    if (!submission) return;
    const result = reminderSubmissionResult(state, submission);
    if (result.kind === 'waiting') return;
    setSubmission(null); setNotice(result.message);
    if (result.kind === 'failed') { setUncertain(true); setError(result.message); }
    else { setUncertain(false); close(); void refresh().catch(() => {}); }
  }, [submission, state]);
  const open = (intent: ReminderIntent, original: Data | null = null) => {
    if (pending || locked.current) return;
    generation.current++; setError(''); setReview(null); setAdvanced(false);
    const draft = reminderDraft(original, intent, Intl.DateTimeFormat().resolvedOptions().timeZone);
    setEditor({ intent, original: original ? structuredClone(original) : null, draft, initialTiming: reminderTiming(draft) });
  };
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
    if (!editor) return;
    try { void prepare('automation.reviewSchedule', reminderReviewPayload(editor.draft, editor.intent, editor.original, editor.initialTiming)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const confirm = async () => {
    if (!review?.reviewHandle || locked.current || pending || !supported('automation.confirmReview')) return;
    // Clear before calling the bridge. An ambiguous acknowledgement cannot replay this review.
    const handle = review.reviewHandle; setReview(null); locked.current = true; setRequesting(true); setError('');
    try {
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
  return <>
    <section className="pw-settings-section" aria-label="提醒规则">
      <header className="pw-section-heading"><h2>提醒与主动任务</h2><p>按手机系统调度，可能延后。手机目标会提醒你查看并开始，不会擅自操作屏幕。</p></header>
      <div className="pw-form-actions"><PressButton compact disabled={pending || reading || readingError || !hasRules || !supported('automation.reviewSchedule')} onClick={() => open('create')}>新建提醒</PressButton><Button variant="secondary" disabled={reading || receiptsBusy || !supported('automation.list')} onClick={() => { void refresh().catch(() => {}); }}>刷新记录</Button></div>
      {notice && <p className="pw-field-hint" role="status">{notice}</p>}
      {uncertain && <p className="pw-field-hint">本页保留未确认提交，暂不开放再次写入。请核对已有提醒与原回执后重新进入页面。</p>}
      {error && <p className="pw-field-error" role="alert">{error}</p>}
      {reading ? <p className="pw-secondary-empty" role="status">正在读取原提醒清单…</p> : readingError ? <p className="pw-field-error" role="alert">原提醒清单未读成功，请刷新核对。{String(state.moduleErrors['automation.list'])}</p> : !hasRules ? <p className="pw-secondary-empty">尚未读取原提醒清单，请刷新核对。</p> : rules.length ? rules.map(rule => {
        const availability = reminderRuleState(rule, Date.now());
        return <article key={rule.id} className="pw-source-record"><header><h3>{rule.title || '未命名提醒'}</h3><span>{rule.enabled ? '已启用' : availability.exhausted ? '安排已结束' : '已暂停'}</span></header>
          <p>{availability.exhausted ? '这次安排已到期或已有发生记录；原结果仍以回执为准。' : rule.nextAt > 0 ? '计划时间：' + date(rule.scheduledAt || rule.nextAt) : '已暂停或安排已结束'}</p>
          <dl className="pw-facts"><div><dt>时间安排</dt><dd>{types.find(type => type.id === rule.schedule?.type)?.label || '暂不支持的格式'}{rule.schedule?.expression ? ' · ' + rule.schedule.expression : ''}{rule.schedule?.zone ? ' · ' + rule.schedule.zone : ''}</dd></div><div><dt>规则版本</dt><dd>{rule.revision}</dd></div>{rule.nextAt > 0 && rule.nextAt !== rule.scheduledAt && <div><dt>下次检查</dt><dd>{date(rule.nextAt)}</dd></div>}</dl>
          {!availability.sourceCurrent && <p>原提醒来源已变化或仍待核对。请刷新后操作。</p>}
          {availability.exhausted && Object.hasOwn(rule, 'nativeOrigin') && <p>此提醒来自原对话；请在原对话重新明确提出安排，未复制旧来源。</p>}
          {availability.exhausted && !Object.hasOwn(rule, 'nativeOrigin') && rule.manualOriginVersion !== 1 && <p>此旧提醒的手建来源尚未确认，请从空白明确新建，未复制旧目标。</p>}
          {!availability.editable && <p>此日历格式或版本暂不支持编辑；可暂停保留原记录，或核对后删除。</p>}
          <div className="pw-form-actions">{availability.canReschedule && <Button variant="secondary" disabled={pending} onClick={() => open('reschedule', rule)}>重新安排</Button>}<Button variant="secondary" disabled={pending || !availability.canEdit} onClick={() => open('edit', rule)}>编辑</Button><Button variant="secondary" disabled={pending || !availability.canToggle || !supported('automation.reviewRule')} onClick={() => { void prepare('automation.reviewRule', { id: rule.id, revision: rule.revision, command: 'setEnabled', enabled: !rule.enabled }); }}>{rule.enabled ? '暂停提醒' : '启用提醒'}</Button><Button variant="ghost" disabled={pending || !supported('automation.reviewRule')} onClick={() => { void prepare('automation.reviewRule', { id: rule.id, revision: rule.revision, command: 'delete' }); }}>删除</Button></div>
        </article>;
      }) : <p className="pw-secondary-empty">还没有提醒。新建后先核对手机提供的计划时间，再确认保存。</p>}
    </section>
    <section className="pw-settings-section" aria-label="原发生与投递回执"><header className="pw-section-heading"><h2>最近投递</h2><p>发生、投递与原任务完成分开。准备会建立或定位原手机目标，仍须你确认执行。未知结果只核对原记录，不会重发。</p></header>
      {receiptsBusy ? <p className="pw-secondary-empty" role="status">正在读取原发生记录…</p> : receiptsError ? <p className="pw-field-error" role="alert">{String(receiptsError)}</p> : !receiptsReady ? <p className="pw-secondary-empty">尚未读取原发生记录，请刷新核对。</p> : receipts.length ? receipts.slice(0, receiptLimit).map(receipt => <article key={receipt.id} className="pw-source-record"><header><h3>{receipt.title || '原发生记录'}</h3><span>{receipt.status === 'delivery_unknown' ? '投递未知' : receipt.status === 'awaiting_user' ? '等待你查看' : receipt.status === 'blocked_notifications' ? '通知受阻' : receipt.status}</span></header><p>{receipt.message}</p>{receipt.occurrenceAt > 0 && <p className="pw-field-hint">计划时间：{date(receipt.occurrenceAt)} · 领取延后 {Math.max(0, (receipt.claimedAt || receipt.occurrenceAt) - receipt.occurrenceAt) / 1000} 秒</p>}{receipt.goalState && <p>原任务：{goalState(receipt.goalState.state)}</p>}{receipt.status === 'delivery_unknown' && <p>投递情况仍未知；刷新只核对原记录，不会重发。</p>}{receipt.kind === 'phone_goal' && ['awaiting_user', 'blocked_notifications'].includes(receipt.status) && <div className="pw-form-actions"><Button variant="secondary" disabled={!supported('automation.prepareOccurrence')} onClick={() => { void run('automation.prepareOccurrence', { occurrenceId: receipt.id }).catch(failure => setError(failure instanceof Error ? failure.message : String(failure))); }}>准备原手机任务（不执行）</Button></div>}</article>) : <p className="pw-secondary-empty">暂无发生记录。</p>}
      {!receiptsBusy && !receiptsError && receipts.length > receiptLimit && <Button variant="secondary" onClick={() => setReceiptLimit(value => value + 10)}>显示更早记录</Button>}
    </section>
    <SettingsDialog isOpen={Boolean(editor || review)} title={review ? review.command === 'save' ? '核对提醒计划' : review.command === 'delete' ? '删除这个提醒？' : review.enabled ? '启用这个提醒？' : '暂停这个提醒？' : editor?.intent === 'edit' ? '编辑提醒' : editor?.intent === 'reschedule' ? '重新安排提醒' : '新建提醒'} onClose={() => { if (review) { generation.current++; setReview(null); } else close(); }}>
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
        <div className="pw-form-grid"><TextAreaField label="提醒内容" value={draft.title} onChange={value => change('title', value.slice(0, 200))} isDisabled={pending} />
          <SelectField label="时间安排" value={draft.type} options={types} onChange={value => change('type', value)} isDisabled={pending} />
          {calendar ? <>{draft.type === 'cron' ? <TextField label="Cron：分 时 日 月 周" value={draft.cron} onChange={value => change('cron', value.slice(0, 512))} isDisabled={pending} hint="例如 0 9 * * 1-5 为工作日 09:00。支持数字、*、逗号、递增范围和步长；周日为 0。日与周至少一项不限制。" /> : <WheelTimeField label="当地时间 HH:mm" value={draft.time} onChange={value => change('time', value.slice(0, 5))} isDisabled={pending} />}<TextField label="时区" value={draft.zone} onChange={value => change('zone', value.slice(0, 80))} isDisabled={pending} hint="例如 Asia/Shanghai。预览由手机核对时区与夏令时。" /></> : <TextField label={draft.type === 'interval' ? '每隔多少分钟' : '多少分钟后'} inputMode="numeric" value={draft.minutes} onChange={value => change('minutes', value.replace(/\D/g, '').slice(0, 6))} isDisabled={pending} hint="1 至 525600 分钟。新建或修改时间后，从本次核对时计算首次时间。" />}
          {draft.type === 'weekly' && <fieldset disabled={pending}><legend className="pw-field-label">每周日期</legend><div className="pw-form-actions">{['周一', '周二', '周三', '周四', '周五', '周六', '周日'].map((label, index) => <CheckboxField key={label} isSelected={draft.weekdays.includes(index + 1)} onChange={checked => change('weekdays', checked ? [...draft.weekdays, index + 1] : draft.weekdays.filter(value => value !== index + 1))}>{label}</CheckboxField>)}</div></fieldset>}
          {draft.type === 'monthly' && <><TextField label="每月几日（1–31）" inputMode="numeric" value={draft.monthDay} onChange={value => change('monthDay', value.replace(/\D/g, '').slice(0, 2))} isDisabled={pending} /><StretchSwitch label="短月使用最后一天" description={draft.lastDay ? '例如 31 日遇到 2 月，使用 2 月最后一天。' : '当月没有所选日期时，跳过当月。'} checked={draft.lastDay} onChange={value => change('lastDay', value)} disabled={pending} /></>}
          <StretchSwitch label="提醒我开始手机任务" description="只提醒查看目标，不会自动操作屏幕。" checked={draft.phone} onChange={value => change('phone', value)} disabled={pending} />{draft.phone && <TextAreaField label="手机任务目标" value={draft.goal} onChange={value => change('goal', value.slice(0, 4000))} isDisabled={pending} />}
          <Button variant="ghost" onClick={() => setAdvanced(value => !value)}>{advanced ? '收起高级选项' : '高级选项'}</Button>
          {advanced && <>{calendar && <><StretchSwitch label="不存在的当地时间跳过" checked={draft.skipGap} onChange={value => change('skipGap', value)} disabled={pending} /><StretchSwitch label="重复时间选择较晚一次" checked={draft.laterOverlap} onChange={value => change('laterOverlap', value)} disabled={pending} /></>}{draft.type !== 'once' && <><RadioOptions label="错过安排时间时" value={draft.misfire} onChange={value => change('misfire', value)} isDisabled={pending} options={[{ id: 'skip', label: '跳过旧实例，只处理最新一次' }, { id: 'coalesce', label: '合并错过的实例为一次' }, { id: 'catch_up', label: '按原安排逐次补处理' }]} />{draft.misfire === 'catch_up' && <TextField label="本轮最多补处理次数（1–20）" inputMode="numeric" value={draft.maxCatchUp} onChange={value => change('maxCatchUp', value.replace(/\D/g, '').slice(0, 2))} isDisabled={pending} />}<RadioOptions label="原任务仍未结束时" value={draft.overlap} onChange={value => change('overlap', value)} isDisabled={pending} options={[{ id: 'buffer_one', label: '保留一个待处理实例' }, { id: 'skip', label: '跳过新实例' }, { id: 'allow', label: '允许并行，仍各自需要原任务授权' }]} /></>}</>}
        </div>
        {error && <p className="pw-field-error" role="alert">{error}</p>}
        {(submission || requesting) && <p className="pw-field-hint" role="status">{requesting ? '正在核对或登记请求…' : '请求已登记，等待原操作回执。'}</p>}
        <div className="pw-form-actions"><Button variant="secondary" onClick={close}>{pending ? '关闭并核对记录' : '取消'}</Button><PressButton compact disabled={pending || reading || readingError || !originalCurrent || !supported('automation.reviewSchedule')} onClick={preview}>核对提醒计划</PressButton></div>
      </>}
    </SettingsDialog>
  </>;
}
