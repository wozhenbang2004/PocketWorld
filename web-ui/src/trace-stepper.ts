import { durationLabel, traceDuration, traceFailed, traceRows, traceStatus, traceRoleLabel, type TraceFilter, type TraceNode } from './execution-trace.ts';
import type { Step } from './library/types';

/** Glyphs describe observed outcomes; a returned handler never confirms its action effect. */
function stepState(node: TraceNode): string {
  const status = node.status.toUpperCase();
  if (node.effectPending) return 'returned';
  if (['COMPLETED', 'COMPLETE', 'SUCCESS', 'SUCCEEDED', 'DONE'].includes(status)) return 'complete';
  if (['FAILED', 'ERROR', 'TIMED_OUT', 'TIMEOUT', 'REJECTED'].includes(status)) return 'error';
  if (['CANCELLED', 'CANCELED', 'STOPPED', 'INTERRUPTED', 'ABANDONED'].includes(status)) return 'stopped';
  if (['WAITING_APPROVAL', 'NEEDS_CONFIRMATION'].includes(status)) return 'needs_approval';
  if (status.startsWith('WAITING') || status === 'AWAITING_USER') return 'waiting';
  if (['QUEUED', 'ACCEPTED'].includes(status)) return 'pending';
  if (['STARTED', 'RUNNING', 'WORKING', 'RECOVERING', 'STOPPING'].includes(status)) return 'running';
  return 'unknown';
}

/** One row per real node, with native parent depth and the existing trace filter semantics.
 * No synthetic plan, completion estimate, merged request or clock-derived latency.
 * Native validation rejects cycles; the defensive fallback keeps malformed relations
 * visible as unverified roots rather than dropping an entire component of the graph. */
export function traceSteps(nodes: readonly TraceNode[], folded: ReadonlySet<string>, filter: TraceFilter = 'all', query = ''): Step[] {
  const byId = new Map(nodes.map(node => [node.id, node]));
  if (byId.size !== nodes.length) throw new TypeError('Duplicate trace step ID');
  const checked = new Set<string>(), cyclic = new Set<string>();
  for (const node of nodes) {
    const path: string[] = [], positions = new Map<string, number>();
    let current: TraceNode | undefined = node;
    while (current && !checked.has(current.id)) {
      const position = positions.get(current.id);
      if (position !== undefined) { path.slice(position).forEach(id => cyclic.add(id)); break; }
      positions.set(current.id, path.length); path.push(current.id);
      current = current.parentId ? byId.get(current.parentId) : undefined;
    }
    path.forEach(id => checked.add(id));
  }
  const safe = nodes.map(node => cyclic.has(node.id) ? { ...node, parentId: null } : node);
  return traceRows(safe, folded, filter, query).map(row => {
    const node = byId.get(row.node.id)!, duration = traceDuration(node);
    const label = [traceRoleLabel(node), traceStatus(node.status),
      node.effectPending ? '动作效果待确认' : null,
      traceFailed(node) && !['FAILED', 'ERROR', 'TIMED_OUT', 'TIMEOUT', 'REJECTED'].includes(node.status.toUpperCase()) ? '有错误记录' : null,
      duration === null ? '耗时未记录' : null,
      cyclic.has(node.id) ? '父子关系待核对' : row.missingParent ? `父记录未读入：${node.parentId}` : null,
      row.context ? '筛选上下文' : null].filter(Boolean).join(' · ');
    return { id: node.id, title: node.name, status: stepState(node), statusLabel: label,
      ...(duration === null ? {} : { durationMs: duration, durationLabel: durationLabel(duration) }),
      depth: row.level, hasChildren: row.hasChildren, expanded: row.expanded, canToggle: filter === 'all' && !query.trim(), context: row.context };
  });
}
