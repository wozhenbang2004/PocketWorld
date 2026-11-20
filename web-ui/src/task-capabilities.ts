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
  if (availability.state === 'available') return '原文件清单已完整留存';
  if (availability.state === 'not_requested') return '本次未指定成果文件';
  if (availability.state === 'partial' && Number.isSafeInteger(availability.savedCount) && Number.isSafeInteger(availability.requestedCount))
    return `已留存 ${availability.savedCount} / ${availability.requestedCount} 份文件`;
  if (availability.canRecheck === true) return '原文件成果尚未完整取回';
  if (value.receipt?.status === 'rejected') return '本次执行已拒绝';
  return '原成果状态待核对';
}

/** The stored plan and the real operation share this identity. Do not look up
 * a current skill name or use discovery results to describe an earlier run. */
export function taskSkillPreparation(taskId: string, operation: Data) {
  const native = operation.arguments?.nativeSkill, ref = native?.reference, review = native?.review, packageRef = review?.package;
  const script = review?.request?.scriptPath, outputPaths = review?.request?.outputPaths;
  if (operation.tool !== 'skills_execute' || native?.format !== 'pocketworld.native-skill-operation.v1' ||
      review?.format !== 'pocketworld.skill-execution.v1' || ref?.taskId !== taskId || ref.operationId !== operation.operationId ||
      ref.runId !== operation.runId || !Number.isSafeInteger(ref.epoch) || ref.epoch < 1 ||
      review.run?.taskId !== taskId || review.run?.runId !== ref.runId || review.run?.epoch !== ref.epoch ||
      typeof ref.planDigest !== 'string' || !/^[a-f0-9]{64}$/.test(ref.planDigest) || review.planDigest !== ref.planDigest ||
      typeof packageRef?.id !== 'string' || !packageRef.id || !Number.isSafeInteger(packageRef.revision) || packageRef.revision < 1 ||
      typeof packageRef.digest !== 'string' || !/^[a-f0-9]{64}$/.test(packageRef.digest) || typeof script !== 'string' || !script.startsWith('scripts/') ||
      !Array.isArray(outputPaths) || !outputPaths.every(path => typeof path === 'string')) return null;
  return { script, outputPaths: outputPaths as string[], skillId: packageRef.id as string, revision: Number(packageRef.revision), epoch: Number(ref.epoch) };
}
