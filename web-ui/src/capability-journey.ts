import type { SetupCapability } from './setup-contract';

export const capabilityPurposes = ['research', 'phone.main_screen', 'phone.background', 'project', 'remote', 'team', 'schedule'] as const;
export type CapabilityPurpose = typeof capabilityPurposes[number];
export const isCapabilityPurpose = (value: string): value is CapabilityPurpose => capabilityPurposes.some(purpose => purpose === value);

export const capabilityStartLabels: Record<CapabilityPurpose, string> = {
  research: '交代资料工作',
  'phone.main_screen': '交代手机任务',
  'phone.background': '交代手机任务',
  project: '选择项目',
  remote: '选择设备',
  team: '交给管家协作',
  schedule: '安排后续工作',
};

export type CapabilityCheck = { id?: string; label?: string; title?: string; state?: string; ready?: boolean; code?: string; action?: string; detail?: string; message?: string };
export type CapabilityPreparationAction =
  | { kind: 'prepare'; capability: SetupCapability; label: string }
  | { kind: 'navigate'; route: 'background'; label: string }
  | { kind: 'purpose'; purpose: 'phone.main_screen'; label: string }
  | { kind: 'refresh'; label: string };

/** A native check can guide navigation, but never grants or starts an operation. */
export function capabilityCheckAction(check: CapabilityCheck): CapabilityPreparationAction {
  if (check.state === 'unknown') return { kind: 'refresh', label: '重新核对' };
  if (check.code === 'accessibility_not_connected') return { kind: 'prepare', capability: 'phone', label: '查看服务连接' };
  switch (check.action) {
    case 'accessibility': return { kind: 'prepare', capability: 'phone', label: '检查手机权限' };
    case 'notifications': return { kind: 'prepare', capability: 'background', label: '检查任务通知' };
    case 'background_setup': return { kind: 'navigate', route: 'background', label: '检查后台连接' };
    case 'main_screen_choice': return { kind: 'purpose', purpose: 'phone.main_screen', label: '查看主屏操作' };
    default: return { kind: 'refresh', label: check.action === 'unlock' ? '解锁后重新核对' : check.action === 'wake_screen' ? '亮屏后重新核对' : '重新核对' };
  }
}

export function capabilityCheckReady(check: CapabilityCheck): boolean {
  return check.state === 'ready' || !check.state && check.ready === true;
}

export function capabilityChecks(...groups: unknown[]): CapabilityCheck[] {
  const checks = new Map<string, CapabilityCheck>();
  for (const group of groups) {
    if (!Array.isArray(group)) continue;
    for (const value of group) {
      if (!value || typeof value !== 'object' || Array.isArray(value)) continue;
      const check: CapabilityCheck = {};
      for (const key of ['id', 'label', 'title', 'state', 'code', 'action', 'detail', 'message'] as const) {
        if (typeof value[key] === 'string') check[key] = value[key];
      }
      if (typeof value.ready === 'boolean') check.ready = value.ready;
      checks.set(check.id || `unidentified:${checks.size}`, check);
    }
  }
  return [...checks.values()];
}

export function capabilityCheckDescription(check: CapabilityCheck): string {
  if (capabilityCheckReady(check)) return check.detail || check.message || '已准备';
  const detail = check.detail || check.message || '';
  return check.state === 'blocked' || !check.state && check.ready === false
    ? detail || '需要准备'
    : detail ? `状态待核对 · ${detail}` : '状态待核对，请重新检查';
}

export const capabilityActionSections = [
  { id: 'ready', title: '可以交代的工作' }, { id: 'permission', title: '需要核对权限' },
  { id: 'setup', title: '先完成准备' }, { id: 'wait', title: '暂不能开始' },
] as const;
type PurposeNext = { group: typeof capabilityActionSections[number]['id']; summary: string };
type ObservedPurpose = { id: string; state: string; installed: boolean; checks?: unknown; notifications?: { checks?: unknown } };
const permissionCodes = new Set(['accessibility_disabled', 'window_content_permission_missing', 'interactive_windows_permission_missing',
  'gesture_permission_missing', 'screenshot_permission_missing', 'notification_permission_missing']);

/** Native observation only. A ready work entry is never authority to execute. */
export function capabilityNextStep(item: ObservedPurpose): PurposeNext {
  const checks = capabilityChecks(item.checks, item.notifications?.checks).filter(check => !capabilityCheckReady(check));
  const blocked = checks.filter(check => check.state === 'blocked');
  if (!item.installed || item.state === 'unavailable') return { group: 'wait', summary: '这项工作尚未在当前设备上准备好。' };
  const unsupported = blocked.find(check => check.code === 'background_android_unsupported');
  if (unsupported) return { group: 'wait', summary: '这台手机不支持独立后台屏，可改用主屏。' };
  if (item.state === 'busy') return { group: 'wait', summary: '原任务仍在使用手机或浏览器，请稍后核对。' };
  // When the enabled service has not bound yet its dependent window/gesture checks can also be false.
  if (blocked.some(check => check.code === 'accessibility_not_connected')) return { group: 'setup', summary: '系统服务已开启，正在等待手机操作服务连接。' };
  const permission = blocked.find(check => !!check.code && permissionCodes.has(check.code));
  if (permission) return { group: 'permission', summary: permission.action === 'notifications' ? '需要核对系统通知权限。' : '需要核对手机操作的系统权限。' };
  if (blocked.length) return { group: 'setup', summary: capabilityCheckDescription(blocked[0]) };
  if (checks.length) return { group: 'wait', summary: '还有准备项读不到，请重新核对。' };
  switch (item.state) {
    case 'preparation_required': return { group: 'setup', summary: item.id === 'project' ? '开发环境还没准备好，可查看准备项。' : '需要先完成准备，再核对工作范围。' };
    case 'selection_required': return { group: 'ready', summary: item.id === 'remote' ? '先选择设备，再检查连接和权限。' : item.id === 'project' ? '先选择项目，再核对可使用的文件。' : item.id === 'schedule' ? '先查看现有安排，新增安排需确认。' : '先选择这次工作的对象。' };
    case 'scope_required': return { group: 'ready', summary: item.id === 'research' ? '可交代要查什么、整理成什么。' : item.id === 'team' ? '可交代目标，再核对分工和范围。' : '可交代目标，再核对工作范围。' };
    case 'review_required': return { group: 'ready', summary: '准备项已满足，开始前仍会核对范围。' };
    default: return { group: 'wait', summary: '状态待核对，暂不判断能否开始。' };
  }
}
