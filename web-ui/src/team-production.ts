import { teamEditorMember } from './team-phone.ts';
import { isConfiguredHarness } from './harness-capabilities.ts';

type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const text = (value: unknown): string => typeof value === 'string' ? value : '';
export const TEAM_PROJECT_PRODUCTION_MODE = 'phone_project_package_independent_git';
export type TeamProductionMode = 'snapshot' | 'project';
export type TeamProductionDraft = {
  sourceMode: TeamProductionMode;
  sourceTaskId: string; snapshotId: string;
  packageId: string; packageSha: string; packageManifest: string;
  wallSeconds: string; outputBytes: string; contributionBytes: string;
  billingAcknowledged: boolean; commandsAcknowledged: boolean; sourceTransferAcknowledged: boolean;
};

/** These are editable source references and limits, never an execution grant. */
export function teamProductionDraft(member: Data = {}): TeamProductionDraft {
  const source = member.remoteProduction || {}, limits = source.limits || {}, pin = source.packageReference || {};
  return {
    sourceMode: source.mode === TEAM_PROJECT_PRODUCTION_MODE ? 'project' : 'snapshot',
    sourceTaskId: text(source.sourceTaskId), snapshotId: text(source.snapshotId),
    packageId: text(pin.id), packageSha: text(pin.sha256), packageManifest: text(pin.manifestIdentity),
    wallSeconds: String(limits.maxWallTimeSeconds ?? 120), outputBytes: String(limits.maxOutputBytes ?? 65536),
    contributionBytes: String(limits.maxContributionBytes ?? 131072),
    billingAcknowledged: source.internalInferenceBillingUnboundedAcknowledged === true,
    commandsAcknowledged: source.allowCommands === true && source.sameUserExecutionRiskAcknowledged === true,
    sourceTransferAcknowledged: source.privateSourceTransferAcknowledged === true,
  };
}

export function teamProductionEditorMember(member: Data): Data {
  return { ...teamEditorMember(member), writeFiles: Array.isArray(member.writeFiles) ? member.writeFiles.join('\n') : '', production: teamProductionDraft(member) };
}

export function resetTeamProductionAcknowledgments(value: TeamProductionDraft): TeamProductionDraft {
  return { ...value, billingAcknowledged: false, commandsAcknowledged: false, sourceTransferAcknowledged: false };
}

export function teamProductionHarnessReady(worker: Data | undefined, mode: TeamProductionMode): boolean {
  return !!worker && (['codex_app_server', 'claude'].includes(worker.id) || isConfiguredHarness(worker.id))
    && worker.capabilities?.[mode === 'project' ? 'projectProduction' : 'assignmentWorkspace'] === true;
}

export function teamProductionSnapshots(member: Data, sources: Data): Data[] {
  return rows(sources.snapshots).filter(source => source.profileId === member.device?.profileId
    && source.environmentId === member.device?.environmentId);
}
export const teamProductionSnapshotKey = (source: Data): string => `${source.sourceTaskId || ''}:${source.snapshotId || ''}`;
export const teamProductionPackageKey = (source: Data): string => `${source.id || ''}:${source.sha256 || ''}:${source.manifestIdentity || ''}`;
export function teamProductionSourceLabel(source: Data, fullProject = false): string {
  return fullProject ? `项目 ${text(source.projectId).slice(0, 8)} · 版本 ${source.versionSequence} · ${source.fileCount} 个文件 · ${text(source.sha256).slice(0, 12)}`
    : `${text(source.goal).slice(0, 80) || '原电脑任务'} · ${(Array.isArray(source.files) ? source.files : []).join('、')}`;
}

export function teamProductionFiles(value: unknown): string[] {
  const files = text(value).split('\n').map(path => path.trim()).filter(Boolean);
  if (files.length < 1 || files.length > 8) throw new Error('每名成员需要选择 1 至 8 个项目文件，每行一个。');
  if (new Set(files).size !== files.length) throw new Error('同一成员的负责文件不能重复。');
  if (files.some(path => path.length > 240 || /[\\:\u0000-\u001f]/.test(path)
    || path.split('/').some(part => !part || part === '.' || part === '..'))) throw new Error('负责文件须为项目内相对路径，不能越过项目目录。');
  return files;
}

function integer(value: string, min: number, max: number, label: string): number {
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) < min || Number(value) > max)
    throw new Error(`${label}须为 ${min} 至 ${max} 之间的整数。`);
  return Number(value);
}

export function teamProductionSelection(member: Data, sources: Data): Data {
  const draft: TeamProductionDraft = member.production || teamProductionDraft(), full = draft.sourceMode === 'project';
  const limits = {
    maxWallTimeSeconds: integer(draft.wallSeconds, 10, 900, '最长运行秒数'),
    maxOutputBytes: integer(draft.outputBytes, 1024, 196608, '输出字节上限'),
    maxContributionBytes: integer(draft.contributionBytes, 1024, full ? 16777216 : 524288, '冻结贡献字节上限'),
  };
  if (!draft.billingAcknowledged) throw new Error('请确认远端执行器内部模型次数、token 和费用不能硬限。');
  if (full) {
    const source = rows(sources.packages).find(candidate => candidate.id === draft.packageId && candidate.sha256 === draft.packageSha
      && candidate.manifestIdentity === draft.packageManifest);
    if (!source) throw new Error('请选择当前已冻结的准确完整源项目包。');
    if (!draft.commandsAcknowledged || !draft.sourceTransferAcknowledged) throw new Error('请分别确认完整源码外传与同用户命令风险。');
    return { mode: TEAM_PROJECT_PRODUCTION_MODE, packageReference: { id: source.id, sha256: source.sha256, manifestIdentity: source.manifestIdentity }, limits,
      allowCommands: true, sameUserExecutionRiskAcknowledged: true, privateSourceTransferAcknowledged: true, internalInferenceBillingUnboundedAcknowledged: true };
  }
  const source = teamProductionSnapshots(member, sources).find(candidate => candidate.sourceTaskId === draft.sourceTaskId && candidate.snapshotId === draft.snapshotId);
  if (!source) throw new Error('请选择当前电脑、当前环境已完成的原任务文件快照。');
  return { sourceTaskId: source.sourceTaskId, snapshotId: source.snapshotId, limits, internalInferenceBillingUnboundedAcknowledged: true };
}

/** Strip draft-only fields before the existing native review and exact save receipt path. */
export function teamDefinitionPayload(editor: Data, catalog: Data): Data {
  const producing = editor.mode === 'produce', sources = catalog.productionSources || {}, used = new Set<string>();
  if (producing && catalog.writeAssignmentsConfigured !== true) throw new Error('当前宿主尚未接入项目文件分工，请刷新团队页后核对。');
  const members = rows(editor.members).map(member => {
    const { production, writeFiles, ...ordinary } = member;
    if (!producing) return ordinary;
    if (member.runner === 'phone_agent') throw new Error('操作手机的成员不能承担项目文件分工。');
    const files = teamProductionFiles(writeFiles);
    files.forEach(path => { if (used.has(path)) throw new Error(`不同成员不能同时负责 ${path}。`); used.add(path); });
    if (member.runner === 'model') return { ...ordinary, writeFiles: files };
    if (catalog.selectedFileRemoteProductionConfigured !== true) throw new Error('当前宿主尚未接入电脑文件制作，请核对连接后刷新。');
    if (!['codex_app_server', 'claude'].includes(member.runner) && !isConfiguredHarness(member.runner)) throw new Error('请选择支持文件制作的实际执行器。');
    return { ...ordinary, writeFiles: files, remoteProduction: teamProductionSelection(member, sources) };
  });
  return { ...editor, members };
}
