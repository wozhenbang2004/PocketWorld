import { deviceChannelWasConnected, deviceRows, deviceStrings, type DeviceData } from './devices-workspace';

const positive = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const words: Record<string, string> = { ready: '条件已核对', blocked: '需对端准备', busy: '对端忙', unsupported: '该屏不支持', outside: '不在此次范围', unknown: '尚未核实',
  needs_capture_scope: '缺少整屏截图及回传范围', needs_observation_scope: '缺少读屏节点范围', needs_artifact_scope: '缺少原图回传范围' };
const actionNames: Record<string, string> = { launch: '打开应用', node: '节点操作', keys: '系统按键', gesture: '坐标手势' };
function label(value: unknown) { return words[String(value)] || words.unknown; }

// These rows exist only after the native host matches a live channel to the signed, exact resource.
// A historical list of supported tools is not evidence of permission, screen contents or a delivery.
export function phoneSurfaceStatus(peer: DeviceData, now: number) {
  const manifest = peer.capabilitySnapshot;
  const scopes = deviceRows(peer.receivedGrantScopes).filter(scope => scope.receivedScope === true && positive(scope.expiresAt) && scope.expiresAt > now);
  const hasScreenScope = scopes.some(scope => deviceStrings(scope.capabilities).includes('tool.invoke')
    && deviceRows(scope.resources).some(resource => ['main_screen', 'background'].includes(resource.screenMode)));
  const entries: { key: string; title: string; packages: string; read: string; control: string; capture: string; next: string }[] = [];
  let note = '';
  if (!deviceChannelWasConnected(peer)) note = '没有可核对的连接。需要这台手机时，查看下方「换了网络或连接不上」；不会自动重连。';
  else if (!manifest || manifest.deviceId !== peer.deviceId || !peer.instanceId || manifest.instanceId !== peer.instanceId) note = '只确认了连接，尚未核对到同一台手机的能力记录。';
  else if (!positive(manifest.capturedAt) || manifest.capturedAt > now || !positive(manifest.validUntil) || manifest.validUntil <= now) note = '上次能力记录已过期。重新核对前，不能判断能否读屏、截图或操作。';
  else {
    for (const scope of scopes) for (const item of deviceRows(scope.phoneSurfaceStatus)) {
      const resource = Number.isSafeInteger(item.resourceIndex) && item.resourceIndex >= 0 ? scope.resources?.[item.resourceIndex] : null;
      if (!resource || !['main_screen', 'background'].includes(item.screenMode) || resource.screenMode !== item.screenMode || !positive(item.validUntil)
        || item.validUntil > Math.min(manifest.validUntil, scope.expiresAt) || item.validUntil <= now) continue;
      const labels = Object.keys(actionNames).filter(key => item[key] === 'ready').map(key => actionNames[key]);
      const waiting = Object.keys(actionNames).filter(key => item[key] !== 'ready' && item[key] !== 'outside');
      const actionNote = waiting.length ? (labels.length ? '；' : '') + waiting.map(key => actionNames[key] + '：' + label(item[key])).join('、') : '';
      const values = [item.read, item.capture, ...Object.keys(actionNames).map(key => item[key])];
      const title = item.screenMode === 'main_screen' ? '主屏' : '独立后台屏';
      const packages = deviceStrings(resource.packageNames);
      const next = values.includes('busy') ? '对端正在使用手机执行能力，等原工作结束后再核对。'
        : values.some(value => value === 'blocked' || value === 'unknown' || value === 'unsupported') ? '请机主在那台手机打开「设备 → 本机设置与权限 → 主屏与独立屏准备」，查看' + title + '的具体原因。' : '';
      entries.push({ key: scope.grantId + ':' + item.resourceIndex, title, packages: packages.join('、'), read: label(item.read),
        control: labels.join('、') + actionNote || '不在此次范围', capture: label(item.capture), next });
    }
    if (!entries.length) note = hasScreenScope
      ? '已读到手机工具授权，但对端没有返回可与原应用逐项核对的记录。若对端仍是旧版，请先核对版本；机主可在那台手机的「本机设置与权限」查看屏幕准备情况。'
      : '只确认了连接，本机未读到这台手机给出的屏幕工具范围。需要时，请机主在对方设备页选中本机，核对「能力与两端权限」。';
  }
  return { entries, note };
}
