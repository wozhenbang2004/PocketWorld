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

export type CapabilityCheck = { id?: string; label?: string; title?: string; state?: string; ready?: boolean; action?: string; detail?: string; message?: string };
export type CapabilityPreparationAction =
  | { kind: 'prepare'; capability: SetupCapability; label: string }
  | { kind: 'navigate'; route: 'background'; label: string }
  | { kind: 'purpose'; purpose: 'phone.main_screen'; label: string }
  | { kind: 'refresh'; label: string };

/** A native check can guide navigation, but never grants or starts an operation. */
export function capabilityCheckAction(check: CapabilityCheck): CapabilityPreparationAction {
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
      for (const key of ['id', 'label', 'title', 'state', 'action', 'detail', 'message'] as const) {
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
