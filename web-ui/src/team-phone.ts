type Data = Record<string, any>;
export type TeamPhoneChoice = { optionId: string; toolBudget: string; modelBudget: string; durationMinutes: string };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];

/** Presentation keys only. The native page owns every target, scope and execution decision. */
export function teamPhoneTargetKey(device: Data | undefined): string {
  if (device?.kind === 'local_phone' && device.screenMode === 'background') return 'local_phone:background';
  if (device?.kind === 'paired_android' && typeof device.targetDeviceId === 'string' && device.targetDeviceId) return `paired_android:${device.targetDeviceId}`;
  return '';
}
export function teamPhoneDevice(target: Data): Data {
  return target.kind === 'local_phone' ? { kind: 'local_phone', screenMode: 'background' } : { kind: 'paired_android', targetDeviceId: target.targetDeviceId };
}
export function teamPhoneTargetLabel(device: Data | undefined, targets: unknown): string {
  const key = teamPhoneTargetKey(device), target = rows(targets).find(value => teamPhoneTargetKey(value) === key);
  return target?.label || (device?.kind === 'local_phone' ? '本机 · 独立后台屏幕' : '已配对 Android 手机');
}
export function teamEditorMember(member: Data): Data {
  const runner = member.runner || 'model';
  return { id: member.id, name: member.name, role: member.role, model: member.model || '', runner,
    ...(runner === 'phone_agent' ? { device: { ...member.device }, assignment: member.assignment || '', dependsOn: [...(member.dependsOn || [])] } : {}) };
}
export function teamPhoneChoice(option?: Data, now = Date.now()): TeamPhoneChoice {
  if (!option) return { optionId: '', toolBudget: '', modelBudget: '', durationMinutes: '' };
  return { optionId: option.optionId, toolBudget: String(Math.min(20, Number(option.toolBudgetMax))),
    modelBudget: String(Math.min(12, Number(option.modelBudgetMax))), durationMinutes: String(Math.min(10, Math.floor((Number(option.expiresAtMax) - now) / 60_000))) };
}
function positiveInteger(value: string, label: string): number {
  if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) throw new Error(`请填写有效的${label}`);
  return Number(value);
}
export function teamPhoneSelections(view: Data, choices: Record<string, TeamPhoneChoice>, now = Date.now()): Data[] {
  const members = rows(view.members);
  if (!members.length || members.length > 4 || new Set(members.map(member => member.memberId)).size !== members.length) throw new Error('手机成员范围已变化，请重新读取');
  return members.map(member => {
    const choice = choices[member.memberId], option = rows(member.options).find(value => value.optionId === choice?.optionId);
    if (!choice || !option || option.available !== true) throw new Error(`请为${member.name || '这名成员'}选择可用的应用范围`);
    const toolBudget = positiveInteger(choice.toolBudget, '操作次数'), modelBudget = positiveInteger(choice.modelBudget, '模型调用次数');
    const minutes = positiveInteger(choice.durationMinutes, '有效分钟数');
    if (toolBudget > Number(option.toolBudgetMax) || modelBudget > Number(option.modelBudgetMax)) throw new Error('本次预算超过原手机范围，请核对');
    const expiresAt = now + minutes * 60_000;
    if (!Number.isSafeInteger(expiresAt) || expiresAt > Number(option.expiresAtMax)) throw new Error('本次有效期超过原手机范围，请缩短后核对');
    return { memberId: member.memberId, optionId: choice.optionId, toolBudget, modelBudget, expiresAt };
  });
}
export function teamPhoneScopeLabel(binding: Data | undefined): string {
  if (!binding) return '';
  const mode = binding.screenMode || binding.mode;
  const screen = mode === 'background' ? '独立后台屏幕' : mode === 'main_screen' ? '目标手机主屏' : '';
  const packages = Array.isArray(binding.packageNames) ? binding.packageNames.filter((name: unknown) => typeof name === 'string') : [];
  const user = Number.isInteger(binding.androidUserId) ? binding.androidUserId === 0 ? 'Android 主用户' : `Android 用户 ${binding.androidUserId}` : '';
  return [screen, user, packages.join('、'), binding.allowWholeDisplayInput === true ? '整屏输入' : '应用节点操作', binding.allowWholeDisplayCapture === true ? '整屏截图' : ''].filter(Boolean).join(' · ');
}
