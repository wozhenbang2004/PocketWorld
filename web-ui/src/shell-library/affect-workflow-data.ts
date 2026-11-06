/** Native evidence projections for the retained Affect reference adaptations. */
export type WorkflowData = Record<string, any>;
const object = (value: unknown): WorkflowData | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
const text = (value: unknown): string => typeof value === 'string' ? value : '';
const finite = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
export const skillMatches = (skill: WorkflowData, query: string): boolean => {
  const content = [skill.name, skill.description, skill.source, skill.sourceVersion, ...(Array.isArray(skill.requiredCapabilities) ? skill.requiredCapabilities : [])].map(text).join(' ').toLocaleLowerCase();
  return query.trim().toLocaleLowerCase().split(/\s+/).every(part => content.includes(part));
};

export const probeStatus = (value: unknown): { label: string; tone: string } => {
  if (value === 'passed') return { label: '本次通过', tone: 'passed' };
  if (value === 'unconfirmed' || value === 'failed') return { label: '未证实', tone: 'uncertain' };
  if (value === 'not_requested') return { label: '未选择', tone: 'neutral' };
  if (value === 'not_run') return { label: '未执行', tone: 'neutral' };
  return { label: '尚无观察', tone: 'neutral' };
};
export const probeObservations = (probe: WorkflowData) => (['text', 'tools', 'image'] as const).map(stage => ({
  stage,
  title: { text: '文本校验', tools: '合成函数声明', image: '合成图片' }[stage],
  ...probeStatus(probe[stage]),
  diagnostics: (Array.isArray(probe.diagnostics) ? probe.diagnostics : []).filter((row: unknown) => object(row)?.stage === stage).map((row: WorkflowData) => ({
    reason: text(row.reason), message: text(row.message), httpStatus: finite(row.httpStatus),
  })),
}));

export const stageLabel = (stage: string): string => ({ bootstrap: '启动', login: '登录', account_refresh: '刷新账号', model_catalog: '模型目录', model_prepare: '准备模型', message_admission: '接收消息', model_request: '模型请求', tool_execution: '工具执行', run_settlement: '运行结算', webview: '页面', diagnostics: '诊断' }[stage] || stage || '未注明阶段');
export const outcomeLabel = (outcome: string): string => ({ started: '已开始', succeeded: '阶段成功', failed: '阶段失败', rejected: '被拒绝', cancelled: '已取消', unconfirmed: '未证实', accepted: '已接收', queued: '排队中', running: '进行中', completed: '阶段完成', awaiting_user: '等待主人', paused: '已暂停', skipped: '已跳过' }[outcome] || '未注明结果');
export const failureOutcome = (outcome: string): boolean => ['failed', 'rejected', 'unconfirmed'].includes(outcome);
export type RuntimeAttempt = { key: string; operationId: string; sessionId: string; stage: string; outcome: string; code: string; time: number | null; durationMs: number | null; events: WorkflowData[] };

/** A started/settled pair is one observed operation. Never infer retries, routes or recovery. */
export function runtimeAttempts(value: unknown): RuntimeAttempt[] {
  if (!Array.isArray(value)) return [];
  const attempts = new Map<string, RuntimeAttempt>();
  value.forEach((entry, index) => {
    const row = object(entry); if (!row) return;
    const operationId = text(row.operationId), sessionId = text(row.sessionId), stage = text(row.stage);
    // Without both identities we cannot establish that two events are the same operation.
    const key = operationId && sessionId && stage ? JSON.stringify([sessionId, operationId, stage]) : `event:${index}`;
    const prior = attempts.get(key), time = finite(row.time);
    const latest = !prior || prior.time === null || (time !== null && time >= prior.time);
    const events = [...(prior?.events || []), row];
    attempts.set(key, latest ? { key, operationId, sessionId, stage, outcome: text(row.outcome), code: text(row.code), time, durationMs: finite(row.durationMs), events } : { ...prior!, events });
  });
  // Map insertion order retains the first observation of each operation in native log order.
  return [...attempts.values()];
}
