import { deviceRows as rows, deviceStrings as strings, type DeviceData as Data } from './devices-workspace';

const deviceId = (v: unknown): v is string => typeof v === 'string' && /^pw1_[a-f0-9]{64}$/.test(v);
const identifier = (v: unknown): v is string => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(v);
const positive = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0;
const screenModes = { main_screen: '主屏', background: '独立后台屏' } as const;
const textTools = new Set(['phone_finish', 'phone_request_user']);
const standaloneTools = new Set(['phone_observe','phone_list_apps','phone_launch_app']);
const screenFields = new Set(['screenMode','androidUserId','packageNames','allowWholeDisplayInput','allowWholeDisplayCapture']);
function originalPhoneScreen(resource: Data) {
  const packages = resource.packageNames;
  return Number.isSafeInteger(resource.androidUserId) && resource.androidUserId >= 0 && resource.androidUserId <= 21474 &&
    Object.keys(resource).every(key => screenFields.has(key)) &&
    ['allowWholeDisplayInput','allowWholeDisplayCapture'].every(key => !(key in resource) || typeof resource[key] === 'boolean') &&
    Array.isArray(packages) && packages.length > 0 && packages.length <= 128 && new Set(packages).size === packages.length &&
    packages.every(p => typeof p === 'string' && p.length <= 255 && /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/.test(p));
}

/** Presentation of already-received grants. No proof to execute, no connection, and no capability read.
 * Match the original receiver/resource before offering to save a local directed draft. */
export function recordedPhoneWork(peer: Data | undefined, local: Data, now: number) {
  const labels = new Set<string>();
  const work = { labels, textStatus: '' };
  if (!peer || peer.revoked === true || peer.connection?.state === 'REVOKED' ||
    !deviceId(peer.deviceId) || !deviceId(local.deviceId) || peer.deviceId === local.deviceId ||
    !identifier(peer.instanceId) || typeof peer.certificateFingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(peer.certificateFingerprint)) return work;

  const manifest = peer.capabilitySnapshot;
  const capabilities = manifest?.deviceId === peer.deviceId && manifest?.instanceId === peer.instanceId ? rows(manifest.capabilities) : [];
  // The existing native prepare entry requires a phone platform or one of
  // these known phone profiles. A retained resource cannot replace that pin.
  if (!/android|phone/i.test(peer.platform || '') &&
    !capabilities.some(item => ['phone.tools.main','phone.tools.background'].includes(item.id))) return work;
  const goals = capabilities.filter(item => item.id === 'agent.goal');
  const goal = goals.length === 1 ? goals[0] : undefined;
  const originalText = goal?.version === '1.0' && goal.androidOriginalTextContract === 'pocketworld.original-phone-allocation.owner-reviewed-text.v1' &&
    goal.zeroToolPhoneContract === 'pocketworld.zero-tool-phone.no-screen-reviewed.v2' &&
    goal.implemented === true && goal.installed === true && strings(goal.supportedExecutionModes).includes('capabilities') ? goal : undefined;
  for (const grant of rows(peer.receivedGrantScopes)) {
    // These are only live native OutgoingGrant projections for this peer.
    // Its grant epoch is in the issuing device's namespace, not our inbound grant epoch.
    if (grant.receivedScope !== true || !identifier(grant.grantId) || !positive(grant.authorizationEpoch) ||
      !positive(grant.issuedAt) || grant.issuedAt > now || !positive(grant.expiresAt) || grant.expiresAt <= now ||
      !strings(grant.allowedOrigins).includes(local.deviceId)) continue;
    const methods = strings(grant.capabilities), tools = strings(grant.toolNames);
    for (const resource of rows(grant.resources)) {
      const mode: unknown = resource.screenMode;
      if (mode === 'main_screen' || mode === 'background') {
        if (!originalPhoneScreen(resource)) continue;
        const hasScreenTool = positive(grant.toolBudget) && tools.some(t => standaloneTools.has(t));
        if (methods.includes('tool.invoke') && hasScreenTool)
          labels.add('在指定' + screenModes[mode] + '应用范围使用手机工具');
        if (methods.includes('task.submitGoal') && positive(grant.modelBudget))
          labels.add(hasScreenTool ? '将指定' + screenModes[mode] + '目标交给对方模型审阅'
            : '将目标交给对方模型审阅（未核对到可用的屏幕工具）');
      } else if (originalText && mode === 'capabilities' && Object.keys(resource).length === 1 &&
        grant.allowDelegation === false && !grant.forwardingScope && positive(grant.modelBudget) &&
        ['task.submitGoal','task.get','operation.get'].every(method => methods.includes(method)) && tools.every(t => textTools.has(t))) {
        labels.add('只交目标文字，由对方模型处理（不用屏幕）');
        if (positive(manifest.capturedAt) && manifest.capturedAt >= grant.issuedAt && manifest.capturedAt <= now &&
          positive(manifest.validUntil) && manifest.validUntil > now) {
          work.textStatus = originalText.busy === true || originalText.blocked === true ? '上次检查，文字目标暂时不能接单。'
            : originalText.ready === true ? '对方上次报告可以接收目标。'
            : originalText.canSubmitForLocalReview === true ? '对方上次报告可接收目标，仍需机主审阅。' : '';
        }
      }
    }
  }
  return work;
}
