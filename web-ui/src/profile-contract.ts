export type ProfileData = Record<string, any>;
export const profileRows = (value: any): ProfileData[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value));
export function newProfile(): ProfileData {
  return { format: 'pocketworld.work-profile.v1', profileId: 'draft-profile', revision: 1, name: '', modelPolicies: {}, instructionRefs: [], skillRefs: [], pluginRefs: [], extensionRefs: [], targetBindings: [], teamTemplateRef: null, resourcePreferences: {} };
}
export function profileReferenceKey(value?: ProfileData | null): string {
  if (!value) return '';
  if (value.kind === 'exact') return JSON.stringify(['exact', value.connectionId, value.modelId]);
  if (value.kind === 'group') return JSON.stringify(['group', value.groupId, value.groupRevision]);
  return JSON.stringify(Object.keys(value).filter(key => key !== 'optional').sort().map(key => [key, value[key]]));
}
export function profileModel(draft: ProfileData, purpose: string, reference: ProfileData | null): ProfileData {
  if (!/^(MAIN|SUMMARY|RELAY|MEMBER(?:\.[A-Za-z0-9_-]{1,60})?)$/.test(purpose)) throw new Error('请选择原主任务、摘要、网关或实际成员用途。');
  if (purpose === 'RELAY' && reference?.kind === 'exact' && String(reference.connectionId).startsWith('account:')) throw new Error('ChatGPT 账号模型仅供本应用使用，不能作为外部网关模型。');
  const next = copy(draft); if (reference) next.modelPolicies[purpose] = copy(reference); else delete next.modelPolicies[purpose]; return next;
}
export function profileAsset(draft: ProfileData, field: string, source: ProfileData, selected: boolean): ProfileData {
  if (!['instructionRefs', 'skillRefs', 'extensionRefs'].includes(field)) throw new Error('此来源类型不可直接选用。');
  const next = copy(draft), items = profileRows(next[field]).filter(row => row.id !== source.id);
  if (selected) {
    const skill = field !== 'extensionRefs';
    if (skill ? source.active !== true || source.contentVerified !== true : source.enabled !== true || source.approved !== true) throw new Error('请先在原来源页启用并完成审阅。');
    const ref: ProfileData = { id: source.id, optional: false };
    for (const key of skill ? ['revision', 'digest'] : ['installationId', 'version', 'approvalRevision', 'sourceSha256']) ref[key] = source[key];
    items.push(ref);
  }
  next[field] = items; return next;
}
export function profilePluginTool(draft: ProfileData, plugin: ProfileData, tool: string, selected: boolean): ProfileData {
  const next = copy(draft), old = profileRows(next.pluginRefs).find(row => row.id === plugin.id);
  const binding = (value: ProfileData) => profileReferenceKey(Object.fromEntries(['id', 'installationId', 'stateRevision', 'manifestDigest'].map(key => [key, value[key]])));
  if (selected && (plugin.enabled !== true || !profileRows(plugin.tools).some(row => row.name === tool && row.granted === true))) throw new Error('请先在原插件页审阅这项工具的权限。');
  let tools: string[] = old && binding(old) === binding(plugin) ? [...old.tools] : [];
  tools = tools.filter(name => name !== tool); if (selected) tools.push(tool);
  next.pluginRefs = profileRows(next.pluginRefs).filter(row => row.id !== plugin.id);
  if (tools.length) next.pluginRefs.push({ id: plugin.id, installationId: plugin.installationId, stateRevision: plugin.stateRevision, manifestDigest: plugin.manifestDigest, tools, optional: old?.optional === true });
  return next;
}
export function profileSaveInput(draft: ProfileData, original: ProfileData | null, viewHandle: string): ProfileData {
  if (!draft.name?.trim() || draft.name.length > 80 || /[\u0000-\u001f\u007f]/.test(draft.name)) throw new Error('名称需要 1–80 个可见字符。');
  if (original && !viewHandle) throw new Error('请先重新核对原工作配置版本；草稿仍保留。');
  const definition = copy(draft);
  if (!original) { delete definition.restoredPendingReview; delete definition.restoredReferences; }
  else {
    definition.profileId = original.profileId; definition.revision = original.revision;
    if ('restoredPendingReview' in original) definition.restoredPendingReview = original.restoredPendingReview;
    if ('restoredReferences' in original) definition.restoredReferences = copy(original.restoredReferences);
  }
  return { definition, ...(original ? { viewHandle } : {}) };
}
export function profileMutationReceipt(value: ProfileData, expected: ProfileData): ProfileData {
  if (value?.confirmed !== true || value.operationId !== expected.operationId || value.command !== expected.command || !value.record || typeof value.record !== 'object' || Array.isArray(value.record)) throw new Error('未收到这次原工作配置操作的匹配回执。');
  const record = value.record, original = expected.original;
  if (expected.command === 'remove') {
    if (record.removed !== true || record.profileId !== original?.profileId) throw new Error('删除回执未指向原工作配置。');
  } else {
    if (!record.profileId || !Number.isSafeInteger(record.revision) || record.revision !== (original ? original.revision + 1 : 1) || original && record.profileId !== original.profileId) throw new Error('保存回执的配置身份或修订不匹配。');
    if (expected.command === 'rebind' && record.restoredPendingReview !== false) throw new Error('恢复配置尚未完成原生重绑审阅。');
  }
  return record;
}

export function profileStartInput(inspection: ProfileData | null, goal: string): ProfileData {
  const wanted = goal.trim();
  if (!inspection?.preflightHandle || inspection.runnable !== true) throw new Error('请重新预检这份已保存修订，并核对准确项目范围。');
  if (!wanted || wanted.length > 4000 || wanted.includes('\u0000')) throw new Error('请填写 1–4000 字的本次任务目标。');
  return { preflightHandle: inspection.preflightHandle, goal: wanted };
}
export function profileStartReceipt(value: ProfileData, expected: ProfileData): ProfileData {
  if (value?.confirmed !== true || value.command !== 'start' || expected.command !== 'start' || value.operationId !== expected.operationId ||
      value.profileId !== expected.original?.profileId || value.revision !== expected.original?.revision ||
      !value.result || typeof value.result.taskId !== 'string' || !/^[a-f0-9-]{36}$/.test(value.result.taskId)) {
    throw new Error('本次原任务身份尚未确认，请核对原回执；不会重新启动。');
  }
  return value.result;
}

/** A lost foreground mutation remains unknown across later reads until the owner acknowledges its original result. */
export class ProfileRequestGate {
  private revision = 0;
  private active = true;
  private pending: { ticket: number; operation: string | null } | null = null;
  private unknown: string | null = null;
  private inspected = false;
  begin(operation: string | null = null): number | null {
    if (!this.active || this.pending || operation && this.unknown) return null;
    const ticket = ++this.revision; this.pending = { ticket, operation }; return ticket;
  }
  owns(ticket: number): boolean { return this.active && this.pending?.ticket === ticket && this.revision === ticket; }
  finish(ticket: number): boolean { if (!this.owns(ticket)) return false; this.pending = null; return true; }
  markUnknown(operation: string): void { this.unknown = operation; this.inspected = false; }
  invalidate(active: boolean): string | null {
    if (this.pending?.operation) this.markUnknown(this.pending.operation);
    this.pending = null; this.inspected = false; this.revision++; this.active = active; return this.unknown;
  }
  inspectOriginal(operation: string): void { if (operation === this.unknown) this.inspected = true; }
  acknowledge(): boolean { if (!this.unknown || !this.inspected) return false; this.unknown = null; this.inspected = false; return true; }
}
