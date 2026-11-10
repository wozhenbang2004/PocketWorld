/** Adapted from Whaleness TrajectoryRow / TrajectoryTurnHeader (MIT, Chaofan Li).
 * The assistant's request entry remains separate from its message detail. */
import { useEffect, useLayoutEffect, useRef } from 'react';
import { IconChevronRight, IconError } from '../../icons';
import { nodeDurationLabel, traceFailed, traceInProgress, traceRole, traceRoleLabel, traceStatus, traceTurns, type TraceEventRow, type TraceNode } from '../../execution-trace';

export function TraceEventList({ rows, selectedId, highlightedId, locateTicket, onSelect, onHighlightEnd, complete }: {
  rows: TraceEventRow[]; selectedId: string | null; highlightedId: string | null; locateTicket: number;
  onSelect: (node: TraceNode) => void; onHighlightEnd: () => void; complete: boolean;
}) {
  const root = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    if (!highlightedId) return;
    const row = [...(root.current?.querySelectorAll<HTMLElement>('[data-node-id]') || [])].find(element => element.dataset.nodeId === highlightedId);
    row?.scrollIntoView({ block: 'center', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  }, [locateTicket, highlightedId]);
  useEffect(() => {
    if (!highlightedId) return;
    const timer = setTimeout(onHighlightEnd, 1_600); return () => clearTimeout(timer);
  }, [highlightedId, locateTicket, onHighlightEnd]);
  return <div ref={root} className="pw-trace-events" aria-label="按轮次排列的轨迹">
    {traceTurns(rows).map(group => <section key={group.id} className="pw-trace-turn" data-turn-id={group.turnId} data-turn-index={group.turnIndex}>
      <h4 className="pw-trace-turn-header"><span>{group.turnIndex != null ? `Turn ${group.turnIndex}` : group.turnId ? '已记录轮次' : '未关联轮次的记录'}</span><i aria-hidden="true"/></h4>
      {group.rows.map(({ node, request }) => {
        const role = traceRole(node), failed = traceFailed(node) || !!request && traceFailed(request);
        const measured = request || node;
        return <div className="pw-trace-event-row" key={node.id} data-node-id={node.id} data-role={role} data-kind={node.kind}
          data-highlighted={highlightedId === node.id || undefined} data-selected={selectedId === node.id || undefined}
          data-failed={failed || undefined} data-running={traceInProgress(node.status) || undefined}>
          <div className="pw-trace-event-spine">
            {role === 'assistant' && node.requestId ? <button className="pw-trace-request-entry" type="button" disabled={!request}
              aria-label={request ? `打开 Request ${request.callId || request.name}` : complete ? '关联请求未保留' : '关联请求补齐中'}
              title={request ? '查看这次模型请求' : complete ? '关联请求未保留' : '正在补齐关联请求'} onClick={() => request && onSelect(request)}><i aria-hidden="true"/></button>
              : <span className="pw-trace-event-dot" aria-hidden="true"><i/></span>}
          </div>
          <button type="button" className="pw-trace-event-open" aria-label={`打开 ${traceRoleLabel(node)} ${node.name}`} onClick={() => onSelect(node)}>
            <span className="pw-trace-role-tag">{traceRoleLabel(node)}</span>
            <span className="pw-trace-event-name">{node.name}</span>
            <span className="pw-trace-event-sequence">#{node.sequence}</span>
            <span className="pw-trace-event-meta">{failed && <IconError size={11} aria-hidden="true"/>}{traceStatus(node.status)}<i aria-hidden="true">·</i>{nodeDurationLabel(measured)}{node.legacy && <><i aria-hidden="true">·</i>早先记录</>}</span>
            <IconChevronRight className="pw-trace-event-chevron" size={14} aria-hidden="true"/>
          </button>
        </div>;
      })}
    </section>)}
  </div>;
}
