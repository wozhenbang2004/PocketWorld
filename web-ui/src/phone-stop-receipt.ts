import type { AuditPage, TaskHeader } from './task-history';
import type { ProcessRecord } from './task-process-checkpoint';
import { presentOperation } from './task-presentation';
import { phoneRunIdentity } from './phone-start';
import { isPrivateLocalPhone } from './PhonePauseInstruction';

export type PhoneStopReceipt = { lines: string[]; record?: ProcessRecord };
type Row = Record<string, any>;
const object = (value: unknown): Row | undefined => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Row : undefined;
const id = (v: unknown): v is string => typeof v === 'string' && !!v.trim() && v.length <= 240;
const actions = new Map(Object.entries({
  phone_launch_app:'打开应用', phone_click_node:'点击控件', phone_click_text:'点击文字目标', phone_tap:'点按屏幕', phone_type:'输入文字',
  phone_swipe:'滑动', phone_scroll_node:'滚动控件', phone_scroll_region:'滚动指定区域', phone_back:'返回', phone_home:'回到桌面',
  phone_gesture:'执行手势', phone_press_key:'按键', phone_set_wheel:'调整滚轮', phone_force_stop_app:'停止指定应用', phone_restart_app:'重新打开应用'
}));
const plans = new Set(['phone_prepare_sequence','phone_replay_route','phone_prepare_cold_start']);
const naturals = (...n:unknown[]) => n.every(value => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0);

// Interpret only fixed native facts. Never echo arguments, editor contents, screen labels, model
// plans or an alleged success embedded in page text. A missing or rejected receipt is not a replay.
function actionResult(entry: Row): string {
  const r = object(entry.receipt);
  if (entry.status !== 'available') return '回执正文未读到，效果待核对';
  if (entry.state === 'resolved' && r?.source === 'owner_reconciliation') {
    if (r.outcome === 'occurred') return '你已核对动作发生';
    if (r.outcome === 'not_occurred') return '你已核对动作未发生';
  }
  if (!r || entry.state === 'prepared') return '只有登记，没有确定的动作回执';
  if (presentOperation(entry).status === 'unknown') return r.accepted === true ? '已接纳请求，效果待核对' : '结果待核对，不要重复操作';
  if (r.actionDispatched === false && r.postconditionAlreadySatisfied === true && r.processingConfirmed === true) return '原状态已满足，没有派发新动作';
  if (r.accepted === true && (r.nativeDispatched === false || r.actionDispatched === false || r.effectStatus === 'not_dispatched')) return '回执不一致，效果待核对';
  if (r.accepted === false && (r.nativeDispatched === false || r.effectStatus === 'not_dispatched')) return '该次请求未派发';
  if (r.accepted === false) return '该次请求未获接纳';
  if (r.accepted !== true) return '已有原回执，未核实应用内效果';
  if (r.ok === false) return '请求已接纳，后续回执有误，效果待核对';
  const read = object(r.delayedTextReadback) ?? object(r.nativeTextReadback);
  if (entry.tool === 'phone_type' && read?.status === 'exact_match' && read.exactMatch === true && read.verifiedBusinessSuccess === false)
    return '同一输入框已回读，文字与输入一致';
  const effects = object(r.actionEffects), observed = object(effects?.observed);
  if (effects?.verifiedBusinessSuccess === false && effects.automaticReplayAllowed === false &&
      observed?.verifiedBusinessSuccess === false && id(observed.beforeObservationId) && id(observed.afterObservationId) &&
      observed.beforeObservationId !== observed.afterObservationId && ['stable_observed_scope','stable_visual_and_tree_scope','deadline'].includes(effects.stability) &&
      typeof effects.treeStableForMs === 'number' && effects.treeStableForMs >= 250) {
    if (observed.status === 'change_observed') return '请求已接纳，随后观察到界面变化';
    if (observed.status === 'no_change_in_readable_tree_not_no_effect') return '请求已接纳，可读界面未见变化，效果待核对';
  }
  return '请求已接纳，未核实应用内效果';
}

/** Only accepted, already-open audit windows. No bridge, persistence, image decoding, model or device call. */
export function phoneStopReceipt(task:TaskHeader, eventPage?:AuditPage, operationPage?:AuditPage):PhoneStopReceipt | undefined {
  if (!['cancelled','canceled','stopped'].includes(task.status) || task.raw?.status !== task.status || !isPrivateLocalPhone(task) ||
      task.raw?.contextAvailable === false || task.raw?.sourceCurrentReadRequired === true || task.raw?.taskProjection === 'source_unavailable') return;
  const owner = phoneRunIdentity(task);
  if (!owner) return;
  const {taskId,runId,epoch} = owner;
  const match = (r?:Row) => r?.taskId === taskId && r.runId === runId && r.epoch === epoch;
  const eventRows = eventPage?.section === 'events' && eventPage.taskId === taskId ? eventPage.entries : [];
  const originals = eventRows.filter(entry => {
    const e = object(entry.event);
    return entry.status === 'available' && id(entry.eventKey) && e?.taskId === taskId && e.runId === runId &&
      e.coordinatorEpoch === epoch && (e.epoch === undefined || e.epoch === epoch) && e.type === entry.type;
  });
  const ops = operationPage?.section === 'operations' && operationPage.taskId === taskId ? operationPage.entries : [];
  const latest = ops.filter(op => id(op.operationId) && actions.has(op.tool) && op.runId === runId && op.runEpoch === epoch).slice(0,3);
  const lines: string[] = [];
  let record:ProcessRecord | undefined;
  let screenRead = false, suffixRead = false;
  for (const op of latest) {
    const r = op.status === 'available' ? object(op.receipt) : undefined;
    const wrongScope = r && ['observationScope','observationBinding'].some(key => r[key] !== undefined && !match(object(r[key])));
    const result = wrongScope ? '回执归属无法核对' : actionResult(op);
    const body = actions.get(op.tool) + '：' + result;
    if (!record) record = {section:'operations', id:op.operationId, runId, locatable:!wrongScope, label:'最近读到的手机操作', body};
    else lines.push('此前记录 · '+body);
  }

  // One historical observation, not a current phone screen. Do not infer the place from the
  // goal, the launch arguments or an earlier readable screen when a later observation failed.
  const where = originals.find(entry => entry.type === 'observation' && entry.event.tool === 'phone_observe');
  const seen = object(where?.event.receipt), binding = object(seen?.observationBinding);
  if (where?.event.status === 'completed' && seen?.ok === true && seen.changedDuringRead !== true && binding && match(binding) &&
      id(seen.observationId) && seen.observationId === binding.observationId && id(seen.sessionId) && seen.sessionId === binding.sessionId &&
      naturals(seen.displayId) && seen.displayId === binding.displayId && naturals(seen.pauseRevision) && seen.pauseRevision === binding.pauseRevision) {
    screenRead = true;
    const name = seen.packageName, app = object(seen.installedApp);
    const known = app?.source === 'android_package_manager' && app.packageName === name && typeof name === 'string' && name.length <= 255 && /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(name);
    const screen = seen.displayId === 0 ? '主屏' : '独立屏 '+seen.displayId;
    const place = screen + (seen.displayId > 0 && seen.screenState === 'display_has_no_app' && seen.emptyDisplayVerified === true ? ' · 屏内没有应用' : known ? ' · '+name : ' · 应用未核实');
    if (record) lines.push('停前读到的现场：'+place);
    else record = {section:'events', id:where.eventKey, runId, locatable:true, label:'停前读到的现场', body:place};
  }

  // NativeSequenceProgress is an exact unissued suffix of one bounded plan, never a count of
  // the owner's remaining work. Cross-check its *finished* original planning call in this page.
  // The cancellation fence removes non-cancelled status strings from recorded events. Use
  // the original typed suffix receipt and its actual parent, not message prose, even in that case.
  const tail = originals.find(entry => entry.type === 'native_sequence' && plans.has(entry.event.tool) &&
    (entry.event.status === 'not_dispatched' || entry.event.status === undefined && object(entry.event.receipt)?.firstNotDispatchedStep !== undefined));
  const unissued = object(tail?.event.receipt);
  if (tail && unissued && unissued.automaticReplay === false && unissued.executionAuthorized === false &&
      id(unissued.parentOperationId) && id(unissued.traceParentCallId) && naturals(unissued.totalSteps,unissued.dispatchEnteredSteps,unissued.firstNotDispatchedStep,unissued.remainingSteps) &&
      unissued.totalSteps > 0 && unissued.totalSteps <= 4 && unissued.remainingSteps > 0 && unissued.firstNotDispatchedStep === unissued.dispatchEnteredSteps+1 &&
      unissued.totalSteps === unissued.dispatchEnteredSteps+unissued.remainingSteps) {
    const parents = originals.filter(entry => entry.position < tail.position && entry.type === 'tool_trace' && entry.event.stage === 'finished' &&
      entry.event.evidenceOnly === true && entry.event.traceVersion === 1 && entry.event.source === 'native_runner' && entry.event.dispatchEntered === true &&
      entry.event.outcome === 'COMPLETED' && entry.event.tool === tail.event.tool && entry.event.callId === unissued.traceParentCallId &&
      (entry.event.operationId ?? entry.event.providerToolCallId) === unissued.parentOperationId);
    if (parents.length === 1) {
      suffixRead = true;
      const body = '这段 '+unissued.totalSteps+' 步操作中，从第 '+unissued.firstNotDispatchedStep+' 步起的 '+unissued.remainingSteps+' 步未派发。';
      if (record) lines.push(body);
      else record = {section:'events', id:tail.eventKey, runId, locatable:true, label:'原操作序列停点', body};
    }
  }
  if (!latest.length && !record) return {lines:['当前窗口没有能核对的本轮手机动作、现场或余步回执，不能据此判断什么都没做。']};
  if (!latest.length) lines.unshift('当前窗口没有能核对的手机动作回执。这不代表没有执行过动作。');
  const gaps = [!screenRead && '停前画面', !suffixRead && '哪些后续步骤未派发'].filter(Boolean);
  if (gaps.length) lines.push(gaps.join('、')+'：本轮已读记录未能核实。');
  return {lines,record};
}
