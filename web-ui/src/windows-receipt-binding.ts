import type { DeviceData } from './devices-workspace';

// The native owner-history window already checked its direct original wire,
// mutual-TLS reply, acceptance digest and bound child job. Never accept a
// target TaskStore run in place of the Windows job.
export function windowsJobRead(original: DeviceData, read: DeviceData, field: string, profile: string) {
  if (read.readOnly !== true || read.newRun !== false || read.retrySubmitted !== false || read.managementOnly === true) return null;
  const r = read.operationReceipt, w = r?.payload?.[field], a = r?.audience;
  if (!w || w.profile !== profile || r.accepted !== true || r.messageType !== 'OperationReceipt'
    || r.operationId !== original.operationId || r.executorDeviceId !== original.targetDeviceId || r.instanceId !== original.instanceId
    || !/^[a-f0-9]{64}$/.test(original.argumentsDigest || '') || r.argumentsDigest !== original.argumentsDigest
    || a?.rootTaskId !== original.taskId || a.callerDeviceId !== a.originDeviceId || a.resourceBinding?.kind !== 'workspace'
    || original.resourceBinding?.kind !== 'workspace' || a.resourceBinding.binding !== original.resourceBinding.binding
    || w.environmentId !== a.resourceBinding.environmentId || w.environmentId !== original.resourceBinding.environmentId
    || a.targetDeviceId !== r.executorDeviceId || a.instanceId !== r.instanceId || a.objectId !== r.operationId
    || !r.jobId || w.jobId !== r.jobId || w.operationId !== r.operationId || w.argumentsDigest !== r.argumentsDigest
    || r.localTaskId != null || r.runId != null || r.executorEpoch != null
    || !Number.isSafeInteger(w.updatedAt) || w.updatedAt < 1) return null;
  return { r, w };
}
