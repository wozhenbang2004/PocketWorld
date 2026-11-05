type Data = Record<string, any>;
export const backgroundObject = (value: unknown): Data => value != null && typeof value === 'object' && !Array.isArray(value) ? value : {};
export function audioSettingReceipt(value: unknown, expectedEnabled?: boolean): string {
  const receipt = backgroundObject(value);
  if (receipt.persisted !== true || typeof receipt.enabled !== 'boolean' || expectedEnabled !== undefined && receipt.enabled !== expectedEnabled)
    throw new Error('音频策略保存尚未确认，请刷新核对实际设置。');
  return receipt.enabled ? '已保存：后续打开目标应用前静音。' : '已停止后续静音；已有声音记录仍需单独处理。';
}
export function audioRecoveryReceipt(value: unknown): string {
  const receipt = backgroundObject(value);
  for (const field of ['restored', 'supersededByOwner', 'pending']) {
    if (!Number.isSafeInteger(receipt[field]) || receipt[field] < 0) throw new Error('声音处理回执不完整，请刷新核对原记录，勿重复提交。');
  }
  const pending = receipt.pending > 0;
  if (receipt.ownerRequestedWrite !== true || receipt.status !== (pending ? 'audio_owner_action_required' : 'audio_reconciled') ||
      receipt.needsOwnerAction !== pending || receipt.mayRemainMuted !== pending)
    throw new Error('声音处理结果尚未一致确认，请刷新核对原记录，勿重复提交。');
  return `已核对 ${receipt.restored} 项原模式，保留 ${receipt.supersededByOwner} 项后来的模式。` +
    (receipt.pending > 0 ? `仍有 ${receipt.pending} 项待处理，原 App 可能仍静音。` : '当前原声音记录已结算。');
}

export function ownerActionReceipt(value: unknown): string {
  if (backgroundObject(value).accepted !== true) throw new Error('未取得本次请求的登记回执，请刷新核对原操作，勿重复提交。');
  return '请求已登记，请核对实际状态与回执。';
}

export function diagnosticPreview(value: unknown): { reportHandle: string; text: string } {
  const receipt = backgroundObject(value);
  if (typeof receipt.reportHandle !== 'string' || !receipt.reportHandle.trim() || typeof receipt.text !== 'string' || !receipt.text.trim())
    throw new Error('诊断预览回执不完整，请重新读取原报告。');
  return { reportHandle: receipt.reportHandle, text: receipt.text };
}

export function ownerWindowAvailable(state: Data): boolean {
  return state.backgroundSetupOpen === true && state.webWorkspaceTaskOpen !== true &&
    state.backgroundOwner != null && typeof state.backgroundOwner === 'object' && !Array.isArray(state.backgroundOwner);
}

export function ownerAppCopies(owner: Data): Data[] {
  return (Array.isArray(owner.apps) ? owner.apps : []).filter((copy: unknown) => {
    const row = backgroundObject(copy);
    return typeof row.selectionHandle === 'string' && row.selectionHandle.trim() !== '' &&
      typeof row.packageName === 'string' && row.packageName.trim() !== '' && Number.isSafeInteger(row.userId) && row.userId >= 0;
  });
}

export function ownerAppAccess(policy: Data, copy?: Data): { automation: boolean; ownerShare: boolean; revision: number | null } {
  const rule = (Array.isArray(policy.rules) ? policy.rules : []).map(backgroundObject)
    .find(row => row.packageName === copy?.packageName && row.userId === copy?.userId);
  return {
    automation: rule?.automation !== false, ownerShare: rule?.ownerShare !== false,
    revision: policy.available === true && Number.isSafeInteger(policy.revision) && policy.revision >= 0 ? policy.revision : null,
  };
}
export function recoveryOutcome(value: unknown): string {
  const messages: Record<string, string> = {
    already_connected: '界面读取服务当前已连接。', registered_waiting_for_binding: '已核对服务登记，等待系统实际绑定。',
    owner_settings_required: '服务登记仍在，系统尚未连接。请在无障碍设置中核对本应用。',
    secure_settings_permission_missing: '系统未授予直接恢复权限，请在系统设置中重新启用界面读取。',
    initial_accessibility_authorization_required: '请先在系统设置中明确启用界面读取。', owner_accessibility_disabled: '请先在系统设置中明确启用界面读取。',
    owner_unlock_required: '设备仍未解锁，恢复尚未执行。', consent_required: '恢复尚未获得当前同意。', recovery_consent_revoked: '已撤销同意，后续恢复已停止。',
    recovery_pending_owner_action: '旧恢复记录待处理，请先在系统设置中核对，再回来读取结果。', compensation_pending: '旧恢复记录待处理，请先在系统设置中核对。',
    original_registration_restored: '已读回原服务登记，实际绑定仍需系统确认。', recovery_cancelled: '本次恢复已取消，原登记的复原状态另行保留。',
    platform_refused: '系统未确认恢复，请使用系统设置核对。', restore_unconfirmed: '恢复尚未确认，请使用系统设置核对。', recovery_busy: '正在核对原恢复请求。',
  };
  return messages[String(value)] || '恢复默认关闭，可随时撤销。';
}
export function connectionRecoveryOutcome(value: unknown): string {
  const messages: Record<string, string> = {
    original_helper_authenticated: '原后台助手已通过认证核对，任务由你另行开始。',
    original_session_requires_owner_review: '已找到原助手，遗留会话仍需核对。', original_task_owns_connection: '原任务仍持有连接。',
    owner_unlock_required: '解锁后才能核对连接。', consent_required: '恢复同意已失效。', owner_setup_required: '尚无原连接配置，请先完成连接设置。',
  };
  return messages[String(value)] || '原连接尚未确认，请从已选连接方式核对。';
}
