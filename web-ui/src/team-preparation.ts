type Data = Record<string, unknown>;
const record = (value: unknown): Data => value != null && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const label = (value: unknown, limit: number) => typeof value === 'string' && value.trim().length <= limit ? value.trim() : '';

/** Presentation of the selected configuration only. Never resolves a model,
 * uses the current default as a historical fact, or verifies an API route. */
export function teamPlannedModel(member: unknown, inherited: string): string {
  const source = record(member), target = record(source.modelTarget);
  if (source.modelTarget != null) {
    const model = target.kind === 'exact' && label(target.connectionId, 200) && label(target.modelId, 200);
    if (model) return '指定 ' + model;
    if (target.kind === 'group' && label(target.groupId, 100) && Number.isSafeInteger(target.groupRevision) && Number(target.groupRevision) > 0)
      return '使用原编组指定的模型组，具体型号待核对';
    return '原模型设置待核对';
  }
  const model = label(source.model, 200);
  return model ? '指定 ' + model : source.model == null || source.model === '' ? inherited : '原模型设置待核对';
}

/** Only explains why the existing one-time approval has no complete roster
 * to display. These descriptions never take part in the decision gate. */
export function missingOneTimeTeamReview(definition: Data, args: Data): string {
  if (!label(args.goal, 8000)) return '未核对到本次任务的原目标。';
  if (definition.definitionKind !== 'one_time' || !label(definition.id, 80) || !label(definition.name, 80))
    return '未核对到这次申请保存的原分工身份。';
  if (!Array.isArray(definition.members) || !Array.isArray(args.members) ||
      !definition.members.length || definition.members.length > 4 || definition.members.length !== args.members.length)
    return '未核对到这次申请的完整成员名单。';
  const members = definition.members.map(record);
  if (members.some(member => !label(member.name, 48) || !label(member.role, 1200)))
    return '原名单缺少成员名称或职责。';
  const ids = new Set(members.map(member => label(member.id, 64)));
  if (!ids.has(label(definition.leaderId, 64)) || ids.has('') || ids.size !== members.length)
    return '未能唯一辨认原成员或这次负责汇总的人。';
  return '本次申请和已保存的分工、轮次或依赖尚不能一一对应。';
}
