import type { ReactNode } from 'react';
import { Accordion, AccordionItem } from './forms-library';
import { setupObject } from './setup-contract';

export function PhoneAccessibilitySetupHelp({ readiness, action }: { readiness: unknown; action: ReactNode }) {
  const observed = setupObject(readiness);
  if (!Number.isSafeInteger(observed.checkedAtUptimeMs) || observed.checkedAtUptimeMs < 0 || !Array.isArray(observed.groups)) return null;
  const groups = observed.groups.map(setupObject).filter(group => group.id === 'mainObservation');
  const checks = groups.length === 1 && Array.isArray(groups[0].checks)
    ? groups[0].checks.map(setupObject).filter(check => check.id === 'serviceConnected') : [];
  if (checks.length !== 1 || checks[0].state !== 'blocked' || checks[0].code !== 'accessibility_disabled') return null;
  return <Accordion>
    <AccordionItem id="android-accessibility-restricted-settings" title="系统提示「受限设置」？">
      <p>仅在确认安装包可信，并愿意让口袋世界读取和操作界面时继续。在应用信息右上角菜单中手动选择「允许受限设置」（如果系统提供），再返回这里开启无障碍。</p>
      <p>如果系统没有这个选项或仍不允许，手机操作暂不能用，其他功能不受影响。</p>
      {action}
    </AccordionItem>
  </Accordion>;
}
