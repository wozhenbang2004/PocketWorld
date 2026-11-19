import { isButlerHomeWork, workCollectionPresentation } from './work-collection';
import type { WorkCollectionTask } from './work-collection';
import { isPrivateLocalPhone } from './PhonePauseInstruction';
import { projectPackageOutcome } from './project-preview';

const nonempty = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;
export type HomeDomain = 'butler' | 'project' | 'phone' | 'teams' | 'devices' | 'ssh' | 'remote';
export type ExactHomeWork = { runId: string; epoch: number; domain: HomeDomain; direct?: 'teams' | 'ssh' | 'remote' };

export function homeWorkIdentity(task: WorkCollectionTask): ExactHomeWork | null {
  const r = task.raw;
  if (!r || !nonempty(task.id) || r.id !== task.id || r.actorId !== 'owner' || r.spaceId !== 'private'
      || r.contextAvailable === false || r.taskProjection === 'source_unavailable' || r.origin != null || r.parentTaskId != null || r.metadataLimited === true
      || r.sourceCurrentReadRequired === true || !nonempty(r.runId) || !Number.isSafeInteger(r.coordinatorEpoch) || r.coordinatorEpoch < 0) return null;
  const direct = r.operationId === task.id && r.runId === task.id && r.kind === r.feature ?
    r.kind === 'remote' && r.command === 'submitJob' && nonempty(r.externalRefs?.profileId) ? 'remote'
      : r.kind === 'teams' ? 'teams'
      : r.kind === 'ssh' && r.externalRefs?.sshNativeRequest?.format === 'pocketworld.ssh.native-request.v1' ? 'ssh' : undefined
    : undefined;
  if (r.coordinatorEpoch === 0) return direct ? {runId:r.runId,epoch:0,domain:direct,direct} : null;

  // Independent domain work has concrete native scope, never inferred from the goal or a model's text.
  const device = r.ownerDeviceWork;
  const domain: HomeDomain | null = direct || (device?.format === 'pocketworld.owner-device-work.v1' && device.requestId === task.id ?
    'devices' : r.kind === 'assistant' && nonempty(r.developmentContext?.projectId) && nonempty(r.developmentContext?.checkoutId) ? 'project'
    : isPrivateLocalPhone(task) ? 'phone'
    : r.kind === 'teams' && (r.feature === 'teams' || nonempty(r.externalId)) ? 'teams'
    : r.kind === 'ssh' && r.feature === 'ssh' && r.externalRefs?.sshNativeRequest?.format === 'pocketworld.ssh.native-request.v1' ? 'ssh'
    : isButlerHomeWork(task) ? 'butler' : null);
  return domain ? {runId: r.runId, epoch: r.coordinatorEpoch, domain} : null;
}

/** Only already projected results. No probing or reopening external work to
 * fill the Home dashboard, and a model's summary is not a result record. */
export function homeDomainResult(task: WorkCollectionTask, domain: HomeDomain): string {
  if (domain === 'butler' || !workCollectionPresentation(task).completed) return '';
  const r = task.raw || {};
  if (domain === 'project') {
    const p = r.projectPackageOutcomes;
    if (p?.taskId === task.id && Array.isArray(p.items) && p.items.some((item: unknown) => !!projectPackageOutcome(task, item))) return '项目包已保存';
  }
  const evidence = r.evidence, original = evidence?.nativeFinalAnswer;
  if (domain === 'remote' && r.operationId === r.runId && evidence?.operationId === r.runId &&
      evidence?.profileId === r.externalRefs?.profileId && nonempty(r.externalId) && evidence?.id === r.externalId &&
      nonempty(evidence?.resultSnapshotId) && nonempty(evidence?.resultSnapshotVersion)) return '远端原成果已记录';
  if (domain === 'ssh' && r.command === 'downloadFile' && evidence?.operationId === r.runId &&
      evidence?.profileId === r.externalRefs?.profileId && evidence?.artifactSaved === true && evidence?.verifiedBytes === true &&
      nonempty(evidence?.attachment?.id)) return '文件已保存到本机';
  if (original?.format === 'pocketworld.runner-final-answer.v1' && original.taskId === task.id &&
      original.runId === r.runId && original.coordinatorEpoch === r.coordinatorEpoch && original.inputBoundaryEmpty === true &&
      [evidence?.modelSummary, evidence?.summary, evidence?.message].some(nonempty)) return '原任务已记录结果';
  return '';
}

export const domainLabel: Record<HomeDomain, string> = { butler: '', project: '本机项目', phone: '这台手机', teams: '团队协作', devices: '配对设备', ssh: 'SSH 主机', remote: '远端运行' };
