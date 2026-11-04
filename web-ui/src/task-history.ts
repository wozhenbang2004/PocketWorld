/** Read-only windows from the existing native TaskStore. Cursors stay opaque. */
type Data = Record<string, any>;
export type AuditSection = 'events' | 'operations';
export type TaskHeader = { id: string; goal: string; status: string; summary?: string; executionMode?: string; updatedAt?: number; raw?: Data };
export type AuditPage = Data & { taskId: string; section: AuditSection; entries: Data[]; nextCursor: string; hasMore: boolean; upperPosition: number };
export type TaskHeaderPage = { tasks: TaskHeader[]; nextCursor: string; hasMore: boolean };

const object = (value: unknown): Data | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : null;
const string = (value: unknown): string => typeof value === 'string' ? value : '';
const position = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

function continuation(value: Data, cursor: string, prior: readonly string[]) {
  if (typeof value.hasMore !== 'boolean' || typeof value.nextCursor !== 'string' || value.nextCursor.length > 2048)
    throw new Error('原记录的分页回执不完整，请刷新后重读。');
  const nextCursor = value.nextCursor;
  if (value.hasMore ? !nextCursor || nextCursor === cursor || prior.includes(nextCursor) : nextCursor !== '')
    throw new Error('原记录的分页位置没有前进，请刷新后重读。');
  return { nextCursor, hasMore: value.hasMore };
}

/** Reject an unrelated task/section/window rather than mixing its rows into the open task. */
export function taskAuditPage(value: unknown, taskId: string, section: AuditSection, cursor = '', prior: readonly string[] = [], upper?: number): AuditPage {
  const page = object(value);
  if (!page || page.format !== 'pocketworld.task-audit-page.v1' || page.taskId !== taskId || page.section !== section ||
      page.executionAuthorized !== false || !Array.isArray(page.entries) || page.entries.length > 40 || !position(page.upperPosition) ||
      upper !== undefined && page.upperPosition !== upper)
    throw new Error('返回的记录不属于这个原任务窗口，请重新读取。');
  const entries: Data[] = [];
  let previous = Number.POSITIVE_INFINITY;
  const ids = new Set<string>();
  for (const input of page.entries) {
    const row = object(input), id = row && string(section === 'events' ? row.eventKey : row.operationId);
    if (!row || !id || ids.has(id) || !position(row.position) || row.position === 0 || row.position > page.upperPosition || row.position >= previous ||
        row.taskId !== undefined && row.taskId !== taskId || !['available', 'read_limited'].includes(row.status))
      throw new Error('原记录的顺序或身份不可核对，请重新读取。');
    previous = row.position; ids.add(id); entries.push(row);
  }
  return { ...page, ...continuation(page, cursor, prior), taskId, section, entries } as AuditPage;
}

export function taskHeaderPage(value: unknown, cursor = '', prior: readonly string[] = []): TaskHeaderPage {
  const page = object(value);
  if (!page || page.auditBodyIncluded !== false || !Array.isArray(page.tasks) || page.tasks.length > 40)
    throw new Error('尚未取得原任务的摘要窗口，请重新读取。');
  const tasks: TaskHeader[] = [];
  const ids = new Set<string>();
  for (const input of page.tasks) {
    const raw = object(input), id = string(raw?.id);
    if (!raw || !id || id.length > 240 || ids.has(id) || typeof raw.goal !== 'string' || typeof raw.status !== 'string' || !position(raw.updatedAt))
      throw new Error('原任务摘要的身份不可核对，请重新读取。');
    ids.add(id);
    tasks.push({ id, goal: raw.goal, status: raw.status, summary: string(raw.summary), executionMode: string(raw.executionMode), updatedAt: raw.updatedAt, raw });
  }
  return { tasks, ...continuation(page, cursor, prior) };
}

/** A newer native projection wins, including a newer summary that retires old source text. */
export function currentTaskHeaders(window: readonly TaskHeader[], current: readonly TaskHeader[]): TaskHeader[] {
  const latest = new Map(current.map(task => [task.id, task]));
  const rows = new Map<string, TaskHeader>();
  for (const original of window) {
    const live = latest.get(original.id);
    const selected = live && Number(live.updatedAt || 0) >= Number(original.updatedAt || 0) ? live : original;
    const previous = rows.get(selected.id);
    if (!previous || Number(selected.updatedAt || 0) > Number(previous.updatedAt || 0)) rows.set(selected.id, selected);
  }
  return [...rows.values()].sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0) || (a.id === b.id ? 0 : a.id < b.id ? 1 : -1));
}

export function taskHistoryIdentity(task: TaskHeader): string {
  return JSON.stringify([task.id, string(task.raw?.runId), task.raw?.coordinatorEpoch ?? null,
    task.raw?.sourceCurrentReadRequired === true || task.raw?.taskProjection === 'source_unavailable']);
}
