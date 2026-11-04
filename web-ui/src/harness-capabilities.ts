type Data = Record<string, any>;
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const rows = (value: unknown): Data[] => Array.isArray(value) ? value.filter(item => item && typeof item === 'object') : [];
const text = (value: unknown): string => typeof value === 'string' ? value : '';

/** Only presentation eligibility. The native owner and Companion validate the actual binding. */
export function isConfiguredHarness(id: string): boolean {
  return /^acp_[a-z][a-z0-9_-]{0,64}$/.test(id) || id === 'muse_msp';
}
export function isTeamHarness(id: string): boolean {
  return ['codex_app_server', 'claude', 'pi'].includes(id) || isConfiguredHarness(id);
}
export function harnessWorkerLabel(worker: Data | undefined, id = ''): string {
  const key = text(worker?.id) || id;
  return text(worker?.label) || text(worker?.name) || ({ model: '模型服务', codex_app_server: 'Codex', claude: 'Claude Code', pi: 'Pi', command: '明确命令', gemini_acp: 'Gemini CLI · ACP' } as Record<string, string>)[key] || key;
}
export function harnessOptions(probe: Data | null | undefined, kind: 'models' | 'modes'): Array<{ id: string; label: string }> {
  const value = object(probe), session = object(value.sessionOptions);
  const source = kind === 'models'
    ? Array.isArray(value.models) ? value.models : object(value.models).availableModels || object(value.modelOptions).models
    : Array.isArray(value.modes) ? value.modes : object(value.modes).availableModes;
  let options = rows(source).map(item => ({ id: text(item.modelId || item.id || item.value), label: text(item.name || item.displayName || item.label || item.modelId || item.id || item.value) }));
  if (!options.length) {
    const config = rows(session.configOptions).find(item => item.category === (kind === 'models' ? 'model' : 'mode'));
    options = rows(config?.options).flatMap(item => item.options ? rows(item.options) : [item])
      .map(item => ({ id: text(item.value || item.id), label: text(item.name || item.label || item.value || item.id) }));
  }
  return options.filter((item, index) => item.id && options.findIndex(other => other.id === item.id) === index);
}
export function harnessProbeReady(probe: Data | null | undefined, worker: Data | undefined): boolean {
  if (!worker || !isConfiguredHarness(text(worker.id))) return false;
  const value = object(probe);
  return value.available === true && value.protocolVerified === true
    && (!value.worker || value.worker === worker.id)
    && /^[a-f0-9]{64}$/.test(text(value.configurationRevision))
    && value.configurationRevision === worker.configurationRevision
    && /^[a-f0-9]{64}$/.test(text(value.capabilityRevision));
}
export function harnessSelection(probe: Data | null | undefined, worker: Data | undefined): Data {
  if (!harnessProbeReady(probe, worker)) throw new Error('执行器配置或能力已变化，请重新读取环境并核对。');
  return { protocol: 'pocketworld.harness-selection.v1', workerId: worker!.id,
    configurationRevision: probe!.configurationRevision, capabilityRevision: probe!.capabilityRevision, ownerManagedExecution: true };
}
export function harnessReason(value: Data | null | undefined): string {
  return text(object(value?.reason).message) || text(value?.message) || text(value?.unavailableReason);
}
export function harnessCapabilityItems(probe: Data): Array<{ label: string; state: string }> {
  const caps = object(probe.capabilities);
  const definitions: Array<[string, string[]]> = [
    ['恢复原会话', ['supportsResume', 'threadResume', 'sessionResume']],
    ['运行中补充', ['supportsSteer', 'inputSteering']],
    ['请求停止', ['turnInterrupt', 'supportsCancel', 'interrupt']],
    ['工具审批', ['interactiveApprovals', 'supportsPermissions', 'toolApprovals', 'approvals']],
    ['向你提问', ['structuredUserInput', 'supportsQuestions', 'userQuestions', 'questions']],
    ['团队消息', ['teamTools', 'peerMessaging', 'teamMessages', 'supportsPeerMessages']],
    ['工具连接', ['supportsMcp', 'mcpServers']],
  ];
  return definitions.flatMap(([label, keys]) => {
    const key = keys.find(name => Object.hasOwn(caps, name));
    if (!key) return [];
    const value = caps[key], detail = object(value);
    const supported = typeof value === 'boolean' ? value : typeof detail.supported === 'boolean' ? detail.supported : undefined;
    return [{ label, state: (supported === true ? '本次已报告支持' : supported === false ? '当前不支持' : '尚未核实') + (text(detail.reason) ? ` · ${detail.reason}` : '') }];
  });
}
