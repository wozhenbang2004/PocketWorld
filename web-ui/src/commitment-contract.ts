type Data = Record<string, any>;
export type CommitmentDraft = { title: string; details: string; taskId: string; hours: string };
export function commitmentDraft(record?: Data): CommitmentDraft {
  return { title: String(record?.title || ''), details: String(record?.details || ''), taskId: String(record?.taskId || ''), hours: record ? '' : '24' };
}
export function commitmentReview(draft: CommitmentDraft, viewHandle?: string): Data {
  if (viewHandle !== undefined && !viewHandle.trim()) throw new Error('请重新核对原委托版本，尚未建立新的委托。');
  if (!draft.title.trim() || draft.title.length > 200 || draft.details.length > 4000) throw new Error('请填写 1–200 字目标，具体要求不超过 4000 字。');
  const input: Data = { operation: viewHandle ? 'update' : 'create', title: draft.title, details: draft.details, taskId: draft.taskId };
  if (viewHandle) input.viewHandle = viewHandle;
  if (draft.hours !== '' || !viewHandle) {
    const hours = Number(draft.hours);
    if (!Number.isInteger(hours) || hours < 1 || hours > 8760) throw new Error('跟进时间应为 1–8760 小时之后。');
    input.hours = hours;
  }
  return input;
}
export function commitmentMutationReceipt(value: Data, command: string): Data {
  const key = command === 'setFollowUpPolicy' ? 'policy' : command === 'confirmFollowUpReceipt' ? 'receipt' : 'commitment';
  if (value?.confirmed !== true || value.command !== command || !value.result?.[key] || typeof value.result[key] !== 'object' || Array.isArray(value.result[key])) throw new Error('尚未取得这次操作的原生回执，请先核对原记录。');
  return value.result;
}
export function commitmentPolicyReview(policy: Data): Data {
  const integer = (value: string, min: number, max: number) => {
    const parsed = Number(value);
    if (typeof value !== 'string' || !value.trim() || !Number.isSafeInteger(parsed) || parsed < min || parsed > max) throw new Error('请核对通知次数与冷却分钟；无效数字不会取消原有限制。');
    return parsed;
  };
  if (!Number.isSafeInteger(policy.revision) || policy.revision < 0) throw new Error('请重新读取原跟进设置版本。');
  return { revision: policy.revision, enabled: policy.enabled, zoneId: policy.zoneId, cooldownMinutes: integer(policy.cooldownMinutes, 0, 44640), quietHours: policy.quiet ? { start: policy.start, end: policy.end } : null, pausedUntil: policy.pausedUntil, maxNotificationsPerDay: policy.maxNotificationsPerDay === '' ? null : integer(policy.maxNotificationsPerDay, 0, 1000) };
}

/** A visible workspace owns one pending request; resuming cannot accept an earlier document's result. */
export class OwnerWorkspaceRequest {
  private revision = 0;
  private pending: number | null = null;
  private active = true;
  begin(): number | null { if (!this.active || this.pending !== null) return null; this.pending = ++this.revision; return this.pending; }
  owns(ticket: number): boolean { return this.active && this.revision === ticket && this.pending === ticket; }
  finish(ticket: number): boolean { if (!this.owns(ticket)) return false; this.pending = null; return true; }
  invalidate(active: boolean): void { this.revision++; this.pending = null; this.active = active; }
}
