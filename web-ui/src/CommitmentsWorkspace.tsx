import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { RiAddLine, RiArrowLeftSLine, RiArrowRightSLine, RiRefreshLine, RiSettings3Line } from '@remixicon/react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { PressButton } from './library';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { PlansWorkspace } from './PlansWorkspace';
import { commitmentDraft, commitmentMutationReceipt, commitmentPolicyReview, commitmentReview, OwnerWorkspaceRequest, type CommitmentDraft } from './commitment-contract';
import './followup-reading.css';
import './knowledge-depth.css';

type Data = Record<string, any>;
type Props = { state: Data; supported: (action: string) => boolean; run: (action: string, args?: Data) => Promise<any>; registerTransientBack: (handler: (() => boolean) | null) => void };
const rows = (value: any): Data[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object') : [];
const time = (value: any) => Number.isFinite(value) && value > 0 ? new Date(value).toLocaleString() : '尚未确定';
const status = (value: string) => ({ pending: '待跟进', snoozed: '已暂缓', completed: '已确认完成', cancelled: '已取消' } as Data)[value] || value;
const operation = (value: string) => ({ createCommitment: '保存新委托', updateCommitment: '修改当前委托', snoozeCommitment: '暂缓跟进', cancelCommitment: '取消委托', completeCommitment: '确认已经完成', setFollowUpPolicy: '保存跟进设置', confirmFollowUpReceipt: '记录通知核对结果' } as Data)[value] || value;

/** Uses the adopted forms and original owner receipts. Saving a commitment never starts a task. */
export function CommitmentsWorkspace(props: Props) {
  const [tab, setTab] = useState('commitments');
  const [childBusy, setChildBusy] = useState(false);
  return <Tabs className="pw-followup-tabs" selectedKey={tab} onSelectionChange={key => { if (!childBusy) setTab(String(key)); }}>
    <TabList aria-label="委托类型"><Tab id="commitments" isDisabled={childBusy && tab !== 'commitments'}>单项委托</Tab><Tab id="plans" isDisabled={childBusy && tab !== 'plans'}>持续目标</Tab></TabList>
    {/* Inactive workspaces must unmount: each owns requests and one back handler. */}
    <TabPanel id={tab}>{tab === 'plans' ? <PlansWorkspace {...props} onEditingChange={setChildBusy} /> : <CommitmentList {...props} onEditingChange={setChildBusy} />}</TabPanel>
  </Tabs>;
}

function CommitmentList({ state, supported, run, registerTransientBack, onEditingChange }: Props & { onEditingChange: (value: boolean) => void }) {
  const [snapshot, setSnapshot] = useState<Data>({}), [reading, setReading] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null);
  const [editor, setEditor] = useState<{ draft: CommitmentDraft; original: Data | null } | null>(null), [policy, setPolicy] = useState<Data | null>(null);
  const [receiptRows, setReceiptRows] = useState<Data[]>([]), [receiptsLoaded, setReceiptsLoaded] = useState(false), [dueOnly, setDueOnly] = useState(false), [busy, setBusy] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [ack, setAck] = useState(false), [uncertain, setUncertain] = useState(false), [readAfterUnknown, setReadAfterUnknown] = useState(false);
  const mutationPending = useRef(false), taskOpen = useRef(state.webWorkspaceTaskOpen);
  const openedNotification = useRef('');
  const collectionPanel = useRef<HTMLDivElement>(null), detailHeading = useRef<HTMLHeadingElement>(null);
  const listPosition = useRef(0), openedId = useRef(''), presentedId = useRef('');
  const alive = useRef(true), scope = useRef(new OwnerWorkspaceRequest()), selected = useRef<Data | null>(null), reviewed = useRef<Data | null>(null), resumed = useRef(state.webWorkspaceResumeRevision);
  selected.current = reading; reviewed.current = review;
  const release = (value: Data | null, key: string) => { if (value?.[key]) void run('commitments.discard', { [key]: value[key] }).catch(() => {}); };
  const closeReview = () => { release(reviewed.current, 'reviewHandle'); setReview(null); setAck(false); };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void | Promise<void>, mutation = false) => {
    if (document.visibilityState !== 'visible' || state.webWorkspaceTaskOpen) return;
    const ticket = scope.current.begin(); if (ticket === null) return;
    setBusy(true); setError(''); mutationPending.current = mutation;
    try { if (!supported(action)) throw new Error('当前宿主尚未接通这项委托操作。'); const value = await run(action, args); if (alive.current && scope.current.owns(ticket)) await accept?.(value); }
    catch (failure) { if (alive.current && scope.current.owns(ticket)) { setError(failure instanceof Error ? failure.message : String(failure)); if (mutation) { setUncertain(true); setReadAfterUnknown(false); } } }
    finally { if (scope.current.finish(ticket) && alive.current) { mutationPending.current = false; setBusy(false); } }
  };
  const acceptReading = (value: Data) => { if (!value?.viewHandle || !value.commitment?.id) throw new Error('未取得原承诺读取。'); release(selected.current, 'viewHandle'); setReading(value); setReceiptRows([]); setReceiptsLoaded(false); };
  const refresh = () => { void invoke('commitments.snapshot', {}, value => { if (!Array.isArray(value?.commitments) || !value.policy) throw new Error('原承诺清单没有完整返回。'); setSnapshot(value); if (uncertain) setReadAfterUnknown(true); }); };
  const read = (id: string) => { if (!selected.current) { listPosition.current = collectionPanel.current?.closest('.pw-secondary-scroll')?.scrollTop || 0; openedId.current = id; } closeReview(); void invoke('commitments.read', { id }, acceptReading); };
  const returnToList = () => { if (busy) return; openedId.current = selected.current?.commitment?.id || openedId.current; release(selected.current, 'viewHandle'); setReading(null); setReceiptRows([]); setReceiptsLoaded(false); };
  useEffect(() => {
    const id = state.requestedCommitmentId, revision = String(state.requestedCommitmentRevision || 0), key = id + ':' + revision;
    if (!id || !snapshot.commitments || busy || editor || policy || review || openedNotification.current === key) return;
    openedNotification.current = key;
    if (rows(snapshot.commitments).some(row => row.id === id)) read(id);
    else setNotice('通知对应的原委托暂不在当前清单，请刷新后核对；未打开其它委托。');
  }, [state.requestedCommitmentId, state.requestedCommitmentRevision, snapshot.commitments, busy, editor, policy, review]);
  const prepare = (action: string, input: Data) => { if (uncertain) return; void invoke(action, input, value => { if (!value?.reviewHandle || !value.command) throw new Error('未取得原操作审阅。'); closeReview(); setReview(value); setAck(false); }); };
  const hide = (active: boolean) => { if (mutationPending.current) { setUncertain(true); setReadAfterUnknown(false); mutationPending.current = false; } scope.current.invalidate(active); setBusy(false); closeReview(); setReading(old => old ? { ...old, viewHandle: '' } : null); setEditor(old => old?.original ? { ...old, original: { ...old.original, viewHandle: '' } } : old); setReceiptRows([]); setReceiptsLoaded(false); void run('commitments.discard').catch(() => {}); };
  useEffect(() => { alive.current = true; scope.current.invalidate(document.visibilityState === 'visible'); refresh(); return () => { alive.current = false; scope.current.invalidate(false); release(selected.current, 'viewHandle'); release(reviewed.current, 'reviewHandle'); onEditingChange(false); }; }, []);
  useEffect(() => { onEditingChange(busy || !!editor || !!policy || !!review); }, [busy, editor, policy, review]);
  useEffect(() => {
    const changed = () => { const active = document.visibilityState === 'visible'; hide(active); if (active) refresh(); };
    const hidden = () => hide(false);
    document.addEventListener('visibilitychange', changed); window.addEventListener('pagehide', hidden);
    return () => { document.removeEventListener('visibilitychange', changed); window.removeEventListener('pagehide', hidden); };
  });
  useEffect(() => { if (resumed.current !== state.webWorkspaceResumeRevision) { resumed.current = state.webWorkspaceResumeRevision; hide(document.visibilityState === 'visible'); refresh(); } }, [state.webWorkspaceResumeRevision]);
  useEffect(() => { if (taskOpen.current === state.webWorkspaceTaskOpen) return; taskOpen.current = state.webWorkspaceTaskOpen; hide(!state.webWorkspaceTaskOpen && document.visibilityState === 'visible'); if (!state.webWorkspaceTaskOpen) refresh(); }, [state.webWorkspaceTaskOpen]);
  useEffect(() => { registerTransientBack(() => { if (busy) return true; if (review) { closeReview(); return true; } if (editor) { setEditor(null); return true; } if (policy) { setPolicy(null); return true; } if (reading) { returnToList(); return true; } return false; }); return () => registerTransientBack(null); }, [busy, review, editor, policy, reading, registerTransientBack]);
  useLayoutEffect(() => {
    const id = reading?.commitment?.id || '', scroller = collectionPanel.current?.closest('.pw-secondary-scroll');
    if (id) {
      if (presentedId.current !== id) { if (scroller) scroller.scrollTop = 0; detailHeading.current?.focus({ preventScroll: true }); }
      presentedId.current = id;
    } else if (presentedId.current && !busy) {
      const row = Array.from(collectionPanel.current?.querySelectorAll<HTMLButtonElement>('[data-commitment-id]') || []).find(node => node.dataset.commitmentId === openedId.current);
      (row || collectionPanel.current)?.focus({ preventScroll: true }); if (scroller) scroller.scrollTop = listPosition.current;
      presentedId.current = '';
    }
  }, [reading?.commitment?.id, busy]);
  const confirm = () => {
    if (!review || !ack || busy || uncertain) return;
    const original = review; setReview(null); setAck(false);
    void invoke('commitments.confirm', { reviewHandle: original.reviewHandle }, value => {
      const result = commitmentMutationReceipt(value, original.command);
      setNotice(operation(original.command) + '：已收到原生保存回执。'); setEditor(null); setPolicy(null);
      if (result.commitment) { const next = rows(snapshot.commitments).filter(row => row.id !== result.commitment.id); next.push(result.commitment); setSnapshot(old => ({ ...old, commitments: next })); setReading({ commitment: result.commitment, viewHandle: '' }); }
      if (result.policy) setSnapshot(old => ({ ...old, policy: result.policy }));
      if (result.receipt) setReceiptRows(old => old.map(row => row.claimId === result.receipt.claimId ? result.receipt : row));
    }, true);
  };
  const current = reading?.commitment, records = rows(snapshot.commitments).filter(row => !dueOnly || row.followUpDue === true), terminal = ['completed', 'cancelled'].includes(current?.status);
  const openEditor = (original: Data | null) => { if (!busy && !uncertain) setEditor({ draft: commitmentDraft(original?.commitment), original: original ? structuredClone(original) : null }); };
  const openPolicy = () => { const p = snapshot.policy; if (p) setPolicy({ revision: p.revision, enabled: p.enabled === true, zoneId: p.zoneId || 'UTC', quiet: !!p.quietHours, start: p.quietHours?.start || '22:00', end: p.quietHours?.end || '08:00', cooldownMinutes: String((p.cooldownMillis || 0) / 60000), pausedUntil: p.pausedUntil > Date.now() ? p.pausedUntil : null, maxNotificationsPerDay: p.maxNotificationsPerDay == null ? '' : String(p.maxNotificationsPerDay) }); };
  const recheckEditor = () => { const original = editor?.original?.commitment; if (!original) return; void invoke('commitments.read', { id: original.id }, value => { if (value.commitment?.id !== original.id || value.commitment?.revision !== original.revision) { release(value, 'viewHandle'); throw new Error('原委托版本已变化，草稿保留。请返回详情核对新版本。'); } acceptReading(value); setEditor(old => old ? { ...old, original: value } : null); }); };
  const showReceipts = () => { if (reading?.viewHandle) void invoke('commitments.receipts', { viewHandle: reading.viewHandle }, value => { if (!Array.isArray(value?.receipts)) throw new Error('未取得原跟进通知记录。'); setReceiptRows(value.receipts); setReceiptsLoaded(true); }); };
  const unknownControls = uncertain && <div><p>这次写入尚未确认。草稿保留，请核对原清单与回执；不会自动重试。</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={refresh}>读取原记录核对结果</Button><Button variant="secondary" disabled={busy || !readAfterUnknown} onClick={() => { setUncertain(false); setNotice('已由你确认核对原记录，可重新编辑。'); }}>原记录已核对，返回编辑</Button></div></div>;
  return <section className="pw-followup-workspace pw-knowledge-workspace" data-followup-view={current ? 'detail' : 'list'} aria-label="单项委托工作区">
    {notice && <p className="pw-followup-feedback" role="status">{notice}</p>}
    {error && <p className="pw-followup-feedback pw-field-error" role="alert">{error}</p>}
    {uncertain && <div className="pw-followup-attention">{unknownControls}</div>}
    <div ref={collectionPanel} tabIndex={-1} className="pw-followup-collection" hidden={!!current}>
      <header className="pw-followup-heading"><div><h2>单项委托</h2></div><div className="pw-followup-heading-actions">
        <Button variant="ghost" iconOnly leadingIcon={RiRefreshLine} aria-label="刷新原记录" disabled={busy} onClick={refresh} />
        <Button variant="secondary" leadingIcon={RiAddLine} disabled={busy || uncertain || !snapshot.commitments || !supported('commitments.review')} onClick={() => openEditor(null)}>新增委托</Button>
      </div></header>
      <div className="pw-followup-list-heading"><CheckboxField isSelected={dueOnly} onChange={setDueOnly}>只看已到跟进时间</CheckboxField>{snapshot.commitments && <span>{records.length} 项</span>}</div>
      {!snapshot.commitments ? <p className="pw-followup-empty" role="status">正在读取原委托…</p> : !records.length ? <div className="pw-followup-empty"><h3>{dueOnly ? '还没有到期的委托' : '把需要跟进的事记在这里'}</h3><p>{dueOnly ? '其他委托仍保留在完整清单中。' : '设定目标和期限。保存后只跟进，不会自动执行手机任务。'}</p></div> : <div className="pw-followup-list" aria-label="原委托清单">{records.map(row => <Button className="pw-followup-row" variant="ghost" key={row.id} data-commitment-id={row.id} trailingIcon={RiArrowRightSLine} aria-label={'查看目标与跟进：' + row.title} aria-description={[status(row.status), row.followUpDue === true ? '已到跟进时间' : '', '下次跟进 ' + time(row.followUpAt || row.dueAt)].filter(Boolean).join('；')} disabled={busy} onClick={() => read(row.id)}>
        <span className="pw-followup-row-copy"><span className="pw-followup-row-title">{row.title}</span><span className="pw-followup-row-meta">{status(row.status)}{row.followUpDue === true ? ' · 已到跟进时间' : ''}</span><small>下次跟进 {time(row.followUpAt || row.dueAt)}</small></span>
      </Button>)}</div>}
      <Button className="pw-followup-settings-link" variant="ghost" leadingIcon={RiSettings3Line} trailingIcon={RiArrowRightSLine} disabled={busy || uncertain || !snapshot.policy} onClick={openPolicy}><span>跟进设置<small>{snapshot.policy ? snapshot.policy.enabled ? '通知已启用' : '通知关闭' : '等待读取'}</small></span></Button>

    </div>
    {current && <section className="pw-followup-detail" aria-label="当前委托详情">
      <Button className="pw-followup-back" variant="ghost" leadingIcon={RiArrowLeftSLine} disabled={busy} onClick={returnToList}>返回委托列表</Button>
      <header className="pw-followup-heading"><div><p className="pw-followup-eyebrow">{status(current.status)}</p><h2 ref={detailHeading} tabIndex={-1}>{current.title}</h2></div></header>
      <dl className="pw-followup-facts"><div><dt>期限</dt><dd>{time(current.dueAt)}</dd></div><div><dt>跟进时间</dt><dd>{time(current.followUpAt || current.dueAt)}</dd></div>{current.taskState && <div><dt>原任务状态</dt><dd>{current.taskState}</dd></div>}</dl>
      <div className="pw-followup-primary-actions">
        {current.taskId && <Button variant="secondary" disabled={busy || !supported('tasks.open')} onClick={() => { void run('tasks.open', { id: current.taskId }); }}>查看原任务</Button>}
        {!terminal && <><Button disabled={busy || uncertain || !reading?.viewHandle || !snapshot.completionEvidenceAvailable} onClick={() => prepare('commitments.review', { operation: 'complete', viewHandle: reading?.viewHandle })}>确认已完成</Button><Button variant="secondary" disabled={busy || uncertain || !reading?.viewHandle} onClick={() => prepare('commitments.review', { operation: 'snooze', viewHandle: reading?.viewHandle, hours: 1 })}>暂缓一小时</Button></>}
      </div>
      {current.details && <p className="pw-user-text pw-followup-goal">{current.details}</p>}
      {state.requestedCommitmentId === current.id && state.requestedFollowUpClaimId && <p className="pw-followup-context">从这项委托的跟进通知打开，可在下方核对原通知记录。</p>}
      {current.followUpDue && <p className="pw-followup-context">已到跟进时间，等待核对。</p>}
      {!reading?.viewHandle && <div className="pw-followup-attention"><p>详情已保留，操作前需要重新读取原版本。</p><Button variant="secondary" disabled={busy} onClick={() => read(current.id)}>重新核对这项委托</Button></div>}

      {!terminal && !snapshot.completionEvidenceAvailable && <p className="pw-followup-footnote">当前宿主尚未提供完成核对依据，不能直接确认完成。</p>}
      <Accordion className="pw-followup-disclosures" key={current.id}>
        {!terminal && <AccordionItem id="edit" title="调整委托" description="修改目标、期限或关联任务"><Button variant="secondary" disabled={busy || uncertain || !reading?.viewHandle} onClick={() => openEditor(reading)}>编辑当前委托</Button><div className="pw-form-actions"><Button variant="ghost" disabled={busy || uncertain || !reading?.viewHandle} onClick={() => prepare('commitments.review', { operation: 'cancel', viewHandle: reading?.viewHandle })}>取消委托</Button></div></AccordionItem>}
        <AccordionItem id="receipts" title="跟进通知记录" description="核对发送回执，不把已提交视为已阅读">
          <Button variant="secondary" disabled={busy || !reading?.viewHandle} onClick={showReceipts}>读取原回执</Button>
          {receiptsLoaded && receiptRows.length === 0 && <p role="status">当前原记录中没有跟进通知回执。</p>}
          {receiptRows.map(row => <article className="pw-followup-receipt" key={row.claimId}><h4>{row.status === 'delivery_unknown' ? '通知结果待核对' : row.status === 'notified' ? '已提交本机通知，尚未证明已阅读' : row.status === 'not_sent' ? '已核对未发送' : row.status}</h4><p>{time(row.recordedAt || row.createdAt)}</p>{row.status === 'delivery_unknown' && <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { void invoke('commitments.reconcileReceipt', { claimId: row.claimId }, () => setNotice('已查询本机通知，请重新读取回执。')); }}>查询本机通知</Button><Button variant="secondary" disabled={busy || uncertain} onClick={() => prepare('commitments.reviewReceipt', { claimId: row.claimId, outcome: 'notified' })}>我已看到通知</Button><Button variant="secondary" disabled={busy || uncertain} onClick={() => prepare('commitments.reviewReceipt', { claimId: row.claimId, outcome: 'not_sent' })}>我已核对未发送</Button></div>}</article>)}
        </AccordionItem>
      </Accordion>
    </section>}
    <SettingsDialog isOpen={!!editor && !review} onClose={() => { if (!busy) setEditor(null); }} title={editor?.original ? '编辑当前委托' : '新增明确委托'}><div className="pw-knowledge-dialog pw-knowledge-edit-dialog"><p>保存后不执行任务；修改时间留空会保留原期限。</p>{editor && <><div className="pw-form-grid">{unknownControls}<h3 className="pw-knowledge-section-heading">要跟进什么</h3><TextAreaField label="承诺目标" value={editor.draft.title} onChange={value => setEditor(old => old && ({ ...old, draft: { ...old.draft, title: value } }))} isDisabled={busy} /><TextAreaField label="具体要求" value={editor.draft.details} onChange={value => setEditor(old => old && ({ ...old, draft: { ...old.draft, details: value } }))} isDisabled={busy} /><h3 className="pw-knowledge-section-heading">何时回来核对</h3><TextField label={editor.original ? '改为多少小时后跟进（留空保留）' : '多少小时后跟进'} value={editor.draft.hours} onChange={value => setEditor(old => old && ({ ...old, draft: { ...old.draft, hours: value } }))} isDisabled={busy} /><SelectField label="关联现有任务" value={editor.draft.taskId} options={[{ id: '', label: '暂不关联' }, ...(editor.draft.taskId && !rows(state.tasks).some(task => task.id === editor.draft.taskId) ? [{ id: editor.draft.taskId, label: '原关联任务 · ' + editor.draft.taskId }] : []), ...rows(state.tasks).map(task => ({ id: task.id, label: task.goal || task.id }))]} onChange={value => setEditor(old => old && ({ ...old, draft: { ...old.draft, taskId: value } }))} isDisabled={busy} />{editor.original && !editor.original.viewHandle && <Button variant="secondary" disabled={busy} onClick={recheckEditor}>核对原版本后继续编辑</Button>}{error && <p role="alert">{error}</p>}</div><div className="pw-form-actions pw-knowledge-edit-actions"><Button variant="secondary" disabled={busy} onClick={() => setEditor(null)}>取消</Button><PressButton compact disabled={busy || uncertain || !!editor.original && !editor.original.viewHandle} onClick={() => { try { prepare('commitments.review', commitmentReview(editor.draft, editor.original ? editor.original.viewHandle || '' : undefined)); } catch (failure) { setError(String((failure as Error).message)); } }}>核对保存内容</PressButton></div></>}</div></SettingsDialog>
    <SettingsDialog isOpen={!!policy && !review} onClose={() => { if (!busy) setPolicy(null); }} title="承诺跟进设置"><div className="pw-knowledge-dialog pw-knowledge-edit-dialog"><p>仅发送本机通知。</p>{policy && <><div className="pw-form-grid">{unknownControls}<CheckboxField isSelected={policy.enabled} onChange={enabled => setPolicy(p => ({ ...p, enabled }))}>启用跟进通知</CheckboxField><Accordion className="pw-followup-disclosures"><AccordionItem id="followup-frequency" title="频率与时区" description={`${policy.zoneId} · 至少间隔 ${policy.cooldownMinutes} 分钟${policy.maxNotificationsPerDay ? ' · 每日最多 ' + policy.maxNotificationsPerDay + ' 次' : ''}`}>
<TextField label="时区" value={policy.zoneId} onChange={zoneId => setPolicy(p => ({ ...p, zoneId }))} /><TextField label="两次跟进至少相隔几分钟" value={policy.cooldownMinutes} onChange={cooldownMinutes => setPolicy(p => ({ ...p, cooldownMinutes }))} /><TextField label="每日最多通知次数（留空不额外限制）" value={policy.maxNotificationsPerDay} onChange={maxNotificationsPerDay => setPolicy(p => ({ ...p, maxNotificationsPerDay }))} /></AccordionItem></Accordion><CheckboxField isSelected={policy.quiet} onChange={quiet => setPolicy(p => ({ ...p, quiet }))}>使用安静时段</CheckboxField>{policy.quiet && <><TextField label="开始 HH:mm" value={policy.start} onChange={start => setPolicy(p => ({ ...p, start }))} /><TextField label="结束 HH:mm" value={policy.end} onChange={end => setPolicy(p => ({ ...p, end }))} /></>}<Button variant="secondary" onClick={() => setPolicy(p => ({ ...p, pausedUntil: p?.pausedUntil ? null : Date.now() + 86400000 }))}>{policy.pausedUntil ? '解除暂停（当前至 ' + time(policy.pausedUntil) + '）' : '暂停跟进一天'}</Button>{error && <p role="alert">{error}</p>}</div><div className="pw-form-actions pw-knowledge-edit-actions"><Button variant="secondary" disabled={busy} onClick={() => setPolicy(null)}>取消</Button><PressButton compact disabled={busy || uncertain} onClick={() => { try { prepare('commitments.reviewPolicy', commitmentPolicyReview(policy)); } catch (failure) { setError((failure as Error).message); } }}>核对设置</PressButton></div></>}</div></SettingsDialog>
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={operation(review?.command || '')}><div className="pw-knowledge-dialog pw-knowledge-review"><p>只有明确确认后才交给原生保存。</p>{review && <><p>{review.input.commitment?.title || review.input.patch?.title || review.details?.title}</p><p>{review.input.commitment?.details || review.input.patch?.details}</p>{(review.input.commitment?.dueAt || review.input.patch?.dueAt || review.input.until) && <p>跟进时间：{time(review.input.commitment?.dueAt || review.input.patch?.dueAt || review.input.until)}</p>}{['createCommitment', 'updateCommitment'].includes(review.command) && <p>关联任务：{review.input.commitment?.taskId || review.input.patch?.taskId ? (rows(state.tasks).find(task => task.id === (review.input.commitment?.taskId || review.input.patch?.taskId))?.goal || '原任务') + ' · ' + (review.input.commitment?.taskId || review.input.patch?.taskId) : '暂不关联'}</p>}{review.command === 'completeCommitment' && <p>这会记录你对当前版本的真实完成确认。模型声称完成不代替你的核对。</p>}{review.command === 'cancelCommitment' && <p>保留历史，不再作为待跟进候选。</p>}{review.command === 'setFollowUpPolicy' && <dl className="pw-facts"><div><dt>通知</dt><dd>{review.input.policy.enabled ? '启用' : '关闭'}</dd></div><div><dt>时区</dt><dd>{review.input.policy.zoneId}</dd></div><div><dt>间隔</dt><dd>{review.input.policy.cooldownMillis / 60000} 分钟</dd></div><div><dt>每日上限</dt><dd>{review.input.policy.maxNotificationsPerDay ?? '未设置'}</dd></div><div><dt>安静时段</dt><dd>{review.input.policy.quietHours ? `${review.input.policy.quietHours.start}–${review.input.policy.quietHours.end}` : '未设置'}</dd></div><div><dt>暂停至</dt><dd>{review.input.policy.pausedUntil ? time(review.input.policy.pausedUntil) : '未暂停'}</dd></div></dl>}{review.command === 'confirmFollowUpReceipt' && <p>{review.input.outcome === 'notified' ? '你已亲自看到这次原通知。' : '你已亲自核对这次原通知没有发送。'}不会重发通知或启动任务。</p>}<CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>我已核对这次原记录与操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><PressButton compact disabled={busy || !ack} onClick={confirm}>{review.command === 'completeCommitment' ? '确认完成' : '确认' + operation(review.command)}</PressButton></div></>}</div></SettingsDialog>
  </section>;
}
