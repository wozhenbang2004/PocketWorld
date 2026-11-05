type Data = Record<string, any>;

/** Local event identity only. Native host still owns the exact pending review and permission. */
export class TaskCapabilityConsent {
  private handle = ''; private revision = 0; private accepted = false; private attempted = false;
  show(handle: string) { this.handle = handle; this.revision++; this.accepted = false; this.attempted = false; }
  change(accepted: boolean) { this.revision++; this.accepted = accepted; }
  clear() { this.show(''); }
  capture() { return { handle: this.handle, revision: this.revision }; }
  current(ticket: { handle: string; revision: number }) { return !!ticket.handle && ticket.handle === this.handle && ticket.revision === this.revision; }
  confirm(ticket: { handle: string; revision: number }, requiresConsent = true) { if (!this.current(ticket) || this.attempted || requiresConsent && !this.accepted) return false; this.attempted = true; return true; }
}

export function taskCapabilityDecision(result: Data) {
  if (result.decisionRecorded !== true) return '本次决定尚未确认，请核对原任务。';
  return result.approved === true ? '批准已记录，原任务会继续处理；执行结果仍以原回执为准。' : '已拒绝这次原脚本执行。';
}

export function taskCapabilityArtifactStatus(value: Data) {
  const availability = value.artifactAvailability || {};
  if (availability.state === 'available') return '原文件成果已取回';
  if (availability.canRecheck === true) return '原文件成果尚未完整取回';
  if (value.receipt?.status === 'rejected') return '本次执行已拒绝';
  return '原成果状态待核对';
}
