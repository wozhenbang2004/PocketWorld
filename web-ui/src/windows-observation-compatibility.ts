import type { DeviceData } from './devices-workspace';

const originalReadProfile = 'windows-retained-observation-v1';

/** Read-only UI text from the native, authenticated device.describe projection.
 * Never infer support from a pairing, desktop-v2, APK version or a viewer lease. */
export function windowsObservationCompatibility(peer: DeviceData | null | undefined, now: number): string | null {
  if (!peer) return null;
  const snapshot = peer.capabilitySnapshot, meta = snapshot?.metadata;
  const windows = meta?.kind === 'lightweight_companion' && meta.platform?.platform === 'windows';
  if (!windows && !/^windows(?:$|[ _-])/i.test(String(peer.platform || ''))) return null;
  const identified = windows && snapshot?.messageType === 'DeviceDescription' && snapshot.executionAuthorized === false
    && typeof peer.deviceId === 'string' && snapshot.deviceId === peer.deviceId && meta.deviceId === peer.deviceId
    && typeof snapshot.instanceId === 'string' && snapshot.instanceId.length > 0 && meta.instanceId === snapshot.instanceId;
  const at = snapshot?.capturedAt, until = snapshot?.validUntil;
  const recorded = identified && Number.isSafeInteger(at) && at > 0 && at <= now && Number.isSafeInteger(until)
    && until > at && until - at <= 30_000;
  if (!recorded || !meta?.desktop || typeof meta.desktop !== 'object' || Array.isArray(meta.desktop)) {
    return 'Windows 原观察能力尚未核实。已配对不代表支持；连接后可在「能力与两端权限」更新记录。';
  }
  const compatible = meta.desktop.originalObservationReadbackProfile === originalReadProfile;
  if (until <= now || !['ONLINE', 'DEGRADED'].includes(peer.connection?.state)) {
    const previous = compatible ? '电脑上次声明支持原桌面观察回读' : '电脑上次未声明支持原桌面观察回读';
    return previous + '。这不是本次有效记录；连接后可在「能力与两端权限」重新核对。';
  }
  if (compatible) {
    return '电脑上次声明支持找回当时的桌面观察。能否打开取决于原件和当前读取权限；它不是实时屏幕，也不能用于点按。';
  }
  return '已配对，但这台电脑未声明支持找回原桌面观察。有读取权限时仍可查原操作状态。更新手机 APK 不会升级电脑上的 Companion。';
}
