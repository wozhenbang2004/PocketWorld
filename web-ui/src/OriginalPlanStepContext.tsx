import { useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog } from './forms-library';
import { registerOverlayBack } from './overlay-back';

type Data = Record<string, any>;
type Work = { id: string; raw?: Data };
type PlanStep = { taskId: string; runId: string; epoch: number; planId: string; stepId: string; occurrenceId: string };
type Visit = { step: PlanStep; document: number; resume: number };
type Context = { title: string; goal: string; stepTitle: string; stepNumber: number; stepCount: number };
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const sameStep = (expected: PlanStep, value: Data | null | undefined) => !!value &&
  Object.entries(expected).every(([key, field]) => value[key] === field);

function nativeStep(task: Work): PlanStep | null {
  const raw = task.raw, origin = raw?.origin, scope = raw?.automationScope;
  if (!raw || raw.id !== task.id || !uuid(task.id) || !uuid(raw.runId) ||
      !Number.isSafeInteger(raw.coordinatorEpoch) || raw.coordinatorEpoch < 1 || raw.actorId !== 'owner' || raw.spaceId !== 'private' ||
      raw.taskProjection !== 'detail_window' || raw.taskCoreReadLimited !== false || raw.auditBodyIncluded !== true ||
      raw.contextAvailable === false || raw.contextWithdrawn === true || raw.sourceCurrentReadRequired === true || raw.conversationId ||
      origin?.kind !== 'automation' || Object.hasOwn(origin, 'nativeOrigin') ||
      !(raw.kind === 'assistant' && raw.executionMode === 'capabilities' ||
        raw.kind === 'phone' && ['background', 'main_screen'].includes(raw.executionMode)) ||
      !uuid(origin.planId) || !uuid(origin.commitmentId) || !uuid(origin.occurrenceId) ||
      origin.ruleId !== origin.planId || raw.automationRuleId !== origin.planId ||
      !Number.isSafeInteger(origin.ruleRevision) || origin.ruleRevision < 1 || raw.automationRevision !== origin.ruleRevision ||
      !scope || scope.planId !== origin.planId || scope.commitmentId !== origin.commitmentId ||
      Object.hasOwn(scope, 'remoteWait') || Object.hasOwn(scope, 'businessWait')) return null;
  return { taskId: task.id, runId: raw.runId, epoch: raw.coordinatorEpoch,
    planId: origin.planId, stepId: origin.commitmentId, occurrenceId: origin.occurrenceId };
}

function current(visit: Visit) {
  const live = bridge.getState(), view = live.view as Data | undefined, owner = live.state;
  if (document.hidden || bridge.getDocumentEpoch() !== visit.document || view?.section !== null ||
      view.taskId !== visit.step.taskId || view.resumeRevision !== visit.resume ||
      owner.conversationReader || owner.queuedInputsPage || owner.filePath || owner.chatAttachmentPickerOpen || owner.attachmentWorkspace?.open ||
      !live.capabilities.commands.includes('plans.read') || !Array.isArray(owner.tasks)) return false;
  const rows = owner.tasks.filter((row: Work) => row.id === visit.step.taskId);
  return rows.length === 1 && sameStep(visit.step, nativeStep(rows[0]));
}

function checkedContext(value: Data | undefined, step: PlanStep): Context | null {
  if (!value || value.format !== 'pocketworld.original-plan-step-context.v1' || value.referenceOnly !== true || value.executionAuthorized !== false || !sameStep(step, value) ||
      typeof value.title !== 'string' || !value.title.trim() || value.title.length > 200 ||
      typeof value.goal !== 'string' || !value.goal.trim() || value.goal.length > 4000 ||
      typeof value.stepTitle !== 'string' || !value.stepTitle.trim() || value.stepTitle.length > 200 ||
      !Number.isSafeInteger(value.stepNumber) || !Number.isSafeInteger(value.stepCount) ||
      value.stepNumber < 1 || value.stepNumber > value.stepCount || value.stepCount > 50) return null;
  return { title: value.title, goal: value.goal, stepTitle: value.stepTitle, stepNumber: value.stepNumber, stepCount: value.stepCount };
}

/** Reads the saved objective beside the original step, never starts or selects a Plan. */
export function OriginalPlanStepContext({ task }: { task: Work }) {
  const step = nativeStep(task), held = useRef<Visit | null>(null);
  const [busy, setBusy] = useState(false), [shown, setShown] = useState<Context | null>(null), [unavailable, setUnavailable] = useState(false);
  const close = () => { held.current = null; setBusy(false); setShown(null); setUnavailable(false); };
  useLayoutEffect(() => {
    const retire = () => { if (held.current && !current(held.current)) close(); };
    const unsubscribe = bridge.subscribe(retire);
    document.addEventListener('visibilitychange', retire);
    return () => { held.current = null; unsubscribe(); document.removeEventListener('visibilitychange', retire); };
  }, []);
  const displayed = shown && step && held.current && sameStep(held.current.step, step) && current(held.current) ? shown : null;
  useLayoutEffect(() => displayed ? registerOverlayBack(close) : undefined, [displayed]);
  if (!step) return null;
  const open = async () => {
    const view = bridge.getState().view as Data | undefined, resume = view?.resumeRevision;
    if (held.current || !Number.isSafeInteger(resume)) return;
    const visit = { step, document: bridge.getDocumentEpoch(), resume: Number(resume) };
    if (!current(visit)) return;
    held.current = visit; setBusy(true); setUnavailable(false);
    let value: Context | null = null;
    try { value = checkedContext(await bridge.command('plans.read', { originalStep: true }), step); } catch { /* Keep the original Work. */ }
    if (held.current !== visit) return;
    if (!current(visit)) { close(); return; }
    setBusy(false);
    if (value) setShown(value);
    else { held.current = null; setUnavailable(true); }
  };
  return <div>
    <Button variant="ghost" disabled={busy} onClick={() => void open()}>{busy ? '正在核对原目标…' : '查看所属持续目标'}</Button>
    {unavailable && <p className="pw-field-hint" role="status">暂未核对到这一步所属的原目标。没有打开其他目标。</p>}
    {displayed && <SettingsDialog isOpen title="所属持续目标" closeLabel="返回这一步的工作" onClose={close}>
      <div className="pw-knowledge-dialog"><h3 dir="auto">{displayed.title}</h3>
        {displayed.goal !== displayed.title && <p className="pw-user-text" dir="auto">{displayed.goal}</p>}
        <p dir="auto">第 {displayed.stepNumber}/{displayed.stepCount} 步 · {displayed.stepTitle}</p>
        <p className="pw-field-hint">这是保存的目标。这一步是否执行、交付，以原工作记录为准。</p>
      </div>
    </SettingsDialog>}
  </div>;
}
