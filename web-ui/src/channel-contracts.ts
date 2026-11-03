export type ChannelData = Record<string, any>;
export const channelObjects = (value: any): ChannelData[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
export const channelText = (value: any): string => typeof value === 'string' ? value : value == null ? '' : String(value);
export const channelIdentity = (profile: ChannelData) => ({ profileId: profile.id, expectedTransportEpoch: profile.transportEpoch });
export const channelOwnerIdentity = (profile: ChannelData, binding?: ChannelData) => ({
  ...channelIdentity(profile), expectedBindingEpoch: binding?.bindingEpoch || '', expectedTaskGrantEpoch: binding?.taskGrantEpoch || '',
});

/** The form never supplies an edited profile id, provider, AppID or revision for an existing connection. */
export function channelProfilePayload(original: ChannelData, form: ChannelData): ChannelData {
  const provider = original.provider, adding = original.adding === true;
  const payload: ChannelData = {
    id: original.id, expectedTransportEpoch: adding ? '' : original.transportEpoch,
    provider, appId: adding ? channelText(form.appId).trim() : original.appId,
    name: channelText(form.name).trim(), enabled: form.enabled === true, autoReply: form.autoReply === true,
    qqGuildEnabled: provider === 'qq' && form.qqGuildEnabled === true,
    qqStreamRepliesEnabled: provider === 'qq' && form.qqStreamRepliesEnabled === true,
    ownerImagesEnabled: form.ownerImagesEnabled === true, ownerMediaEnabled: form.ownerMediaEnabled === true,
  };
  if (provider === 'qq' && typeof form.secret === 'string' && form.secret.length) payload.secret = form.secret;
  return payload;
}

export function channelCanStop(background: ChannelData): boolean {
  return typeof background.sessionId === 'string' && !!background.sessionId && (
    ['starting', 'foreground'].includes(background.status) || background.foregroundServicePresent === true ||
    background.cleanupPending === true || Number(background.runningReceivers) > 0);
}
export function channelReceivingStatus(background: ChannelData): string {
  if (background.cleanupFailed || background.persistenceFailed) return '接收清理或保存尚未完全确认';
  if (background.cleanupPending) return '正在结束原接收，等待清理回执';
  if (background.status === 'foreground' && background.foregroundActive === true && background.foregroundServicePresent === true) return '前台接收服务已建立';
  return ({ starting: '已请求开启，尚未建立服务', interrupted: '原接收已中断，未自动恢复', blocked: '接收未能继续，请核对原因' } as ChannelData)[background.status] || '未开启前台持续接收';
}
export function channelConnectionStatus(provider: string, connection?: ChannelData): string {
  if (!connection) return '尚未开始接收';
  if (connection.status === 'login_required') return '微信授权已失效，请重新扫码';
  if (connection.monitorRunning !== true) return connection.status === 'synchronized' ? '最近完成一次同步，当前未持续接收' : connection.status === 'authorization_required' ? '需要核对平台凭据和权限' : '接收已停止';
  if (connection.status === 'listening') return provider === 'qq' ? '官方网关已认证，正在接收' : 'iLink 已同步，正在接收';
  if (connection.status === 'retry_wait') return '连接中断，等待恢复原接收位置';
  return '正在核对连接，尚未确认在线';
}
export function channelOwnerCandidates(data: ChannelData, profile: ChannelData, now: number): ChannelData[] {
  return channelObjects(data.inbox).filter(event => event.profileId === profile.id && event.kind === 'dm' &&
    event.transportEpoch === profile.transportEpoch && ['qq_signature', 'qq_gateway', 'weixin_session', 'trusted_relay'].includes(event.authenticatedBy) &&
    typeof event.receivedAt === 'number' && now - event.receivedAt >= 0 && now - event.receivedAt <= 86_400_000).reverse().slice(0, 10);
}
export function channelOriginalCurrent(original: ChannelData, data: ChannelData): boolean {
  const profile = channelObjects(data.profiles).find(row => row.id === original.profileId);
  if (!profile || profile.transportEpoch !== original.expectedTransportEpoch) return false;
  const bound = channelObjects(data.bindings).find(row => row.profileId === original.profileId);
  return (bound?.bindingEpoch || '') === original.expectedBindingEpoch && (bound?.taskGrantEpoch || '') === original.expectedTaskGrantEpoch;
}
