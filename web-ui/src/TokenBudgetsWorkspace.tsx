import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog, TextField } from './forms-library';
import { OwnerWorkspaceRequest } from './commitment-contract';
import { tokenBudgetCount, tokenBudgetCurrentPolicy, tokenBudgetDraft, tokenBudgetRebasedDraft, tokenBudgetReview, tokenBudgetReviewInput, tokenBudgetSaved, tokenBudgetSnapshot, type TokenBudgetDraft } from './token-budget-contract';

type Data = Record<string, any>;
type Props = { taskId?: string; supported?: (action: string) => boolean; registerTransientBack?: (handler: (() => boolean) | null) => void; onEditingChange?: (value: boolean) => void };
const time = (value: any) => Number.isSafeInteger(value) && value > 0 ? new Date(value).toLocaleString() : '尚未核对';

function BudgetFacts({ budget, daily = false }: { budget: Data; daily?: boolean }) {
  return <><dl className="pw-facts"><div><dt>本机 token 上限</dt><dd>{budget.enabled ? tokenBudgetCount(budget.limit) : '未启用'}</dd></div><div><dt>已报告实际用量</dt><dd>{tokenBudgetCount(budget.knownTokens)}</dd></div><div><dt>仍占用的预留</dt><dd>{tokenBudgetCount(budget.heldTokens)}</dd></div><div><dt>尚待返回 / 用量未知</dt><dd>{tokenBudgetCount(budget.pendingCalls)} / {tokenBudgetCount(budget.unknownCalls)} 次</dd></div>{budget.enabled && <div><dt>可供后续准入</dt><dd>{tokenBudgetCount(budget.remainingTokens)}</dd></div>}{daily && <><div><dt>当前计算区间</dt><dd>{time(budget.windowStart)} 至 {time(budget.windowEnd)}</dd></div><div><dt>区间时区</dt><dd>{budget.zoneId}{budget.zoneId !== budget.nextZoneId ? `；下次起使用 ${budget.nextZoneId}` : ''}</dd></div></>}</dl>
    {budget.overLimit && <p role="status">真实回执已超过设置的上限。超额完整保留，后续调用{daily ? '需等待新日区间或由你明确调整上限。' : '需要你明确调整原任务上限。'}</p>}
    {budget.blockedByUnknown && <p role="status">原历史尚未完整核对，或 {tokenBudgetCount(budget.unboundedCalls)} 次调用没有可界定的预留；不能把这些用量当作 0 启用上限。</p>}</>;
}

export function TokenBudgetsWorkspace({ taskId = '', supported, registerTransientBack, onEditingChange }: Props) {
  const [snapshot, setSnapshot] = useState<Data | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [editor, setEditor] = useState<TokenBudgetDraft | null>(null), [review, setReview] = useState<Data | null>(null), [ack, setAck] = useState(false), [uncertain, setUncertain] = useState(false), [readAfterUnknown, setReadAfterUnknown] = useState(false);
  const requests = useRef(new OwnerWorkspaceRequest()), windowId = useRef(''), currentTask = useRef(taskId);
  const mutationPending = useRef(false), unknownResult = useRef(false);
  const available = !supported || supported('tokenBudgets.open');
  const markUnknown = () => { unknownResult.current = true; setUncertain(true); setReadAfterUnknown(false); };
  const nativeActive = () => { const view = bridge.getState().view as Data | undefined; return document.visibilityState === 'visible' && (!view?.taskId || view.taskId === currentTask.current); };
  const ownerTicket = () => { const view = bridge.getState().view as Data | undefined; return `${view?.section || ''}:${view?.taskId || ''}:${view?.resumeRevision || 0}:${bridge.getDocumentEpoch()}`; };
  const invalidate = (active: boolean) => {
    if (mutationPending.current) { markUnknown(); setNotice('离开时原保存仍在等待回执。草稿已保留，请读取原设置并明确核对结果。'); }
    mutationPending.current = false; requests.current.invalidate(active); setBusy(false); setReview(null); setAck(false); setSnapshot(null); setReadAfterUnknown(false);
    const id = windowId.current; windowId.current = ''; if (id) void bridge.command('tokenBudgets.close', { windowId: id }).catch(() => {});
  };
  useEffect(() => {
    currentTask.current = taskId; requests.current.invalidate(nativeActive()); windowId.current = ''; mutationPending.current = false; unknownResult.current = false; setSnapshot(null); setEditor(null); setReview(null); setBusy(false); setUncertain(false); setReadAfterUnknown(false); setError(''); setNotice('');
    return () => { requests.current.invalidate(false); const id = windowId.current; windowId.current = ''; if (id) void bridge.command('tokenBudgets.close', { windowId: id }).catch(() => {}); };
  }, [taskId]);
  useEffect(() => {
    let owner = ownerTicket();
    const changed = () => invalidate(nativeActive());
    const hidden = () => invalidate(false);
    const unsubscribe = bridge.subscribe(() => { const incoming = ownerTicket(); if (incoming !== owner) { owner = incoming; invalidate(nativeActive()); } });
    document.addEventListener('visibilitychange', changed); window.addEventListener('pagehide', hidden);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', changed); window.removeEventListener('pagehide', hidden); };
  }, []);
  const discard = () => { setReview(null); setAck(false); if (windowId.current) void bridge.command('tokenBudgets.discard', { windowId: windowId.current }).catch(() => {}); };
  useEffect(() => { onEditingChange?.(busy || !!editor || !!review); return () => onEditingChange?.(false); }, [busy, editor, review, onEditingChange]);
  useEffect(() => {
    registerTransientBack?.(() => { if (busy) return true; if (review) { discard(); return true; } if (editor) { setEditor(null); return true; } return false; });
    return () => registerTransientBack?.(null);
  }, [busy, editor, review, registerTransientBack]);

  async function read() {
    const ticket = requests.current.begin(); if (ticket == null) return;
    const task = taskId; const oldWindow = windowId.current; setBusy(true); setError(''); setReview(null); setAck(false);
    try {
      const value = await bridge.command(oldWindow ? 'tokenBudgets.read' : 'tokenBudgets.open', oldWindow ? { windowId: oldWindow } : { taskId: task });
      if (!requests.current.owns(ticket) || currentTask.current !== task) { if (value?.windowId) void bridge.command('tokenBudgets.close', { windowId: value.windowId }).catch(() => {}); return; }
      const checked = tokenBudgetSnapshot(value, task, oldWindow || undefined); windowId.current = checked.windowId; setSnapshot(checked); if (unknownResult.current) setReadAfterUnknown(true);
    } catch (failure) { if (requests.current.owns(ticket)) { setError(failure instanceof Error ? failure.message : String(failure)); windowId.current = ''; setSnapshot(null); setReadAfterUnknown(false); } }
    finally { if (requests.current.finish(ticket)) setBusy(false); }
  }
  function edit(scope: TokenBudgetDraft['scope'], id: string, title: string, budget: Data) {
    try { setEditor(tokenBudgetDraft(scope, id, title, budget)); setError(''); setNotice(''); discard(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  }
  async function prepare() {
    if (!editor || uncertain || !snapshot || !windowId.current) return;
    let input: Data; try { input = tokenBudgetReviewInput(editor, windowId.current); } catch (failure) { setError((failure as Error).message); return; }
    const ticket = requests.current.begin(); if (ticket == null) return; setBusy(true); setError('');
    try { const value = await bridge.command('tokenBudgets.review', input); if (requests.current.owns(ticket)) { setReview(tokenBudgetReview(value, input)); setAck(false); } }
    catch (failure) { if (requests.current.owns(ticket)) { setError(failure instanceof Error ? failure.message : String(failure)); setReview(null); } }
    finally { if (requests.current.finish(ticket)) setBusy(false); }
  }
  async function save() {
    if (!review || !ack || uncertain) return;
    const original = review, ticket = requests.current.begin(); if (ticket == null) return; mutationPending.current = true; setBusy(true); setError(''); setReview(null); setAck(false);
    try {
      const value = await bridge.command('tokenBudgets.save', { windowId: original.windowId, reviewHandle: original.reviewHandle });
      if (requests.current.owns(ticket)) {
        tokenBudgetSaved(value, original); setEditor(null); setSnapshot(null); setNotice('已保存。用量与未知预留继续保留；请重新读取查看最新余额。'); unknownResult.current = false; setUncertain(false); setReadAfterUnknown(false);
      }
    } catch (failure) { if (requests.current.owns(ticket)) { setError(failure instanceof Error ? failure.message : String(failure)); markUnknown(); setSnapshot(null); } }
    finally { if (requests.current.finish(ticket)) { mutationPending.current = false; setBusy(false); } }
  }
  function acknowledgeRead() {
    if (!snapshot || !windowId.current || busy || uncertain && !readAfterUnknown) return;
    try { if (editor) setEditor(tokenBudgetRebasedDraft(editor, snapshot)); unknownResult.current = false; setUncertain(false); setReadAfterUnknown(false); setError(''); setNotice('已由你核对当前保存记录；编辑草稿保留，须重新审阅才能保存。'); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  }
  let actualPolicy: Data | null = null;
  if (editor && snapshot) { try { actualPolicy = tokenBudgetCurrentPolicy(snapshot, editor); } catch { /* Keep the exact old draft; never select a substitute. */ } }
  const editorCurrent = !!editor && !!actualPolicy && !!windowId.current && actualPolicy.revision === editor.revision;
  const unknownControls = uncertain && <div><p>原保存结果尚未确认。草稿保留，请读取当前设置并明确核对；不会自动重复保存。</p>{actualPolicy && <p>当前保存：{actualPolicy.enabled ? `${tokenBudgetCount(actualPolicy.limit)} token 上限` : '上限未启用'} · 版本 {actualPolicy.revision}</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { void read(); }}>读取原设置核对结果</Button><Button variant="secondary" disabled={busy || !readAfterUnknown} onClick={acknowledgeRead}>原设置已核对，返回编辑</Button></div></div>;

  return <section className="pw-token-budgets" aria-label={taskId ? '原任务 token 预算' : '本机 token 预算'}><header className="pw-budget-heading"><div><h3>{taskId ? '原任务 token 预算' : '本机 token 预算'}</h3></div>
    <Button variant="secondary" disabled={busy || !available} onClick={() => { void read(); }}>{busy ? '正在核对…' : snapshot ? '重新读取预算' : '读取原预算'}</Button></header>
    {!available && <p>当前原生版本尚未提供预算设置入口。</p>}{notice && <p role="status">{notice}</p>}{error && <p role="alert">{error}</p>}{unknownControls}
    {snapshot && <><p>{snapshot.unknownMeaning}</p>{taskId ? <div>{snapshot.task?.budget ? <><h4>{snapshot.task.goal || '原任务'}</h4><BudgetFacts budget={snapshot.task.budget} />{snapshot.task.editable ? <Button variant="secondary" disabled={busy || uncertain} onClick={() => edit('task', snapshot.task.ownerTaskId, '原任务累计预算', snapshot.task.budget)}>调整原任务累计上限</Button> : <p>此调用归原父任务预算，请回父任务调整。</p>}</> : <p>此任务尚无原累计执行范围。已有用量仍在原回执中；请先从任务范围设置核对原任务。</p>}</div> : <>
      <article className="pw-budget-default"><div><h4>后续新任务</h4><p>{snapshot.defaults.enabled ? `累计上限 ${tokenBudgetCount(snapshot.defaults.limit)} token` : '默认上限未启用'}</p><p className="pw-muted">仅影响之后创建的任务；已有用量与团队父预算保留。</p></div><Button variant="secondary" disabled={busy || uncertain} onClick={() => edit('default', '', '新任务默认预算', snapshot.defaults)}>设置新任务默认值</Button></article>
      <Accordion variant="divided">{snapshot.routes.map((route: Data, index: number) => <AccordionItem key={route.routeKey} id={`token-budget-route-${index}`} title={route.label} description={route.budget.enabled ? `每日 ${tokenBudgetCount(route.budget.limit)} token` : '每日上限未启用'}><BudgetFacts budget={route.budget} daily /><p className="pw-muted">这条本机保存连接或账号登记的模型调用共享额度。更换模型、更新密钥不会创建新余额；其它设备与另行登记的连接不在这一统计内。</p><Button variant="secondary" disabled={busy || uncertain} onClick={() => edit('daily', route.routeKey, `${route.label} · 每日预算`, route.budget)}>调整每日上限</Button></AccordionItem>)}</Accordion>{snapshot.routes.length === 0 && <p>尚无已保存的模型连接或账号登记，请先在模型设置中保存原连接。</p>}
    </>}<Accordion><AccordionItem id="token-budget-meaning" title="计算范围与未知用量"><p>{snapshot.coverage}</p><p>{snapshot.reserveMeaning}</p><p>{snapshot.historyMeaning}</p><p>这些数值用于本机后续请求准入，不表示账单金额、服务端余额或精确分词总数。缺少图像预留时，已启用预算的图像请求会在发送前停止。</p></AccordionItem></Accordion></>}
    <SettingsDialog isOpen={!!editor && !review} onClose={() => { if (!busy) setEditor(null); }} title={editor?.title || '调整 token 预算'}>{editor && <div className="pw-budget-editor"><div className="pw-form-grid pw-budget-editor-scroll">{unknownControls}<CheckboxField isSelected={editor.enabled} onChange={enabled => setEditor({ ...editor, enabled })} isDisabled={busy || uncertain}>启用这项本机 token 上限</CheckboxField><TextField label="累计 token 上限" value={editor.limit} onChange={limit => setEditor({ ...editor, limit })} isDisabled={busy || uncertain} inputMode="numeric" placeholder="填写整数" />{editor.scope === 'daily' && <><TextField label="每日区间时区" value={editor.zoneId} onChange={zoneId => setEditor({ ...editor, zoneId })} isDisabled={busy || uncertain} placeholder="Asia/Shanghai" /><p>改时区从当前区间结束后生效，不缩短现有区间或清除未知预留。</p></>}{editor.scope !== 'default' && <div><h4>修改前读取的预算</h4><BudgetFacts budget={editor.previous} daily={editor.scope === 'daily'} /></div>}<p>保存不会开始或恢复任务。关闭上限也不会删除历史用量和未知预留。</p>{!uncertain && !editorCurrent && <div><p>草稿保留。请重新读取并核对原预算版本后继续。</p><Button variant="secondary" disabled={busy} onClick={() => { void read(); }}>重新读取原设置</Button>{actualPolicy && <><p>当前保存：{actualPolicy.enabled ? `${tokenBudgetCount(actualPolicy.limit)} token 上限` : '上限未启用'} · 版本 {actualPolicy.revision}</p><Button variant="secondary" disabled={busy} onClick={acknowledgeRead}>已核对当前版本，保留草稿继续</Button></>}</div>}</div><footer className="pw-budget-editor-footer">{error && <p role="alert">{error}</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setEditor(null)}>返回</Button><Button disabled={busy || uncertain || !editorCurrent} onClick={() => { void prepare(); }}>核对这次修改</Button></div></footer></div>}</SettingsDialog>
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) discard(); }} title="核对 token 预算修改">{review && <><p>{editor?.title}</p><dl className="pw-facts"><div><dt>本次设置</dt><dd>{review.input.enabled ? `启用上限 ${tokenBudgetCount(review.input.limit)} token` : '关闭本机上限'}</dd></div>{review.input.scope === 'daily' && <div><dt>后续每日区间时区</dt><dd>{review.input.zoneId}</dd></div>}</dl>{review.input.scope !== 'default' && <BudgetFacts budget={review.previous} daily={review.input.scope === 'daily'} />}<p>实际回执、尚未结算与未知预留均保留。任务继续执行、工具操作和外部效果仍使用各自的原批准。</p><CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>我已核对这次预算范围与上限</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={discard}>返回修改</Button><Button disabled={busy || !ack} onClick={() => { void save(); }}>保存预算</Button></div></>}</SettingsDialog>
  </section>;
}
