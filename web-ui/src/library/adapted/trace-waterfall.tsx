/** Controlled port of the approved library TraceWaterfallDemo, independent v3
 * 0e69029b. This is not Affect UI Pro source. See ../TRACE-PROVENANCE.json.
 * Retains the tree, roving keyboard navigation and positioned duration track;
 * removes demo spans, simulated completion, cost arithmetic and timers. */
import { useRef, useState } from 'react';
import { RiArrowDownSLine, RiArrowRightSLine, RiCheckLine, RiCloseLine, RiCpuLine, RiInformationLine, RiLoader4Line, RiToolsLine } from '@remixicon/react';
import { IconButton } from '../../forms-library';
import { durationLabel, nodeDurationLabel, traceBar, traceFailed, traceInProgress, traceKind, traceStatus, type TraceBounds, type TraceNode, type TraceRow } from '../../execution-trace';

export function TraceWaterfall({ rows, bounds, mode, selectedId, filtering, onToggle, onSelect }: {
  rows: TraceRow[]; bounds: TraceBounds; mode: 'timeline' | 'tree'; selectedId: string | null; filtering: boolean;
  onToggle: (id: string) => void; onSelect: (node: TraceNode) => void;
}) {
  const [focusId, setFocusId] = useState<string | null>(null);
  const rowRefs = useRef(new Map<string, HTMLDivElement>());
  const activeId = rows.some(row => row.node.id === focusId) ? focusId : rows[0]?.node.id;
  const focusRow = (index: number) => {
    const row = rows[Math.max(0, Math.min(rows.length - 1, index))];
    if (row) { setFocusId(row.node.id); rowRefs.current.get(row.node.id)?.focus({ preventScroll: true }); rowRefs.current.get(row.node.id)?.scrollIntoView({ block: 'nearest' }); }
  };
  return <div className="pw-trace-waterfall" data-view={mode}>
    {mode === 'timeline' && bounds && bounds.duration > 0 && <div className="pw-trace-axis" aria-hidden="true"><span>此页时间轴</span><div>{[0, .5, 1].map(fraction => <span key={fraction}>{durationLabel(bounds.duration * fraction)}</span>)}</div></div>}
    <div role="tree" aria-label={mode === 'timeline' ? '执行轨迹时间轴' : '执行调用树'} className="pw-trace-tree">
      {rows.map((row, index) => {
        const node = row.node, bar = traceBar(node, bounds), ongoing = traceInProgress(node.status), failed = traceFailed(node);
        const Kind = node.kind === 'model' ? RiCpuLine : node.kind === 'tool' ? RiToolsLine : RiInformationLine;
        const State = failed ? RiCloseLine : ongoing ? RiLoader4Line : ['COMPLETED', 'SUCCESS', 'SUCCEEDED', 'DONE'].includes(node.status.toUpperCase()) ? RiCheckLine : RiInformationLine;
        return <div key={node.id} ref={element => { if (element) rowRefs.current.set(node.id, element); else rowRefs.current.delete(node.id); }}
          className="pw-trace-row" role="treeitem" aria-level={row.level + 1} aria-expanded={row.hasChildren ? row.expanded : undefined}
          aria-selected={selectedId === node.id} tabIndex={activeId === node.id ? 0 : -1} data-node-id={node.id}
          data-kind={node.kind} data-failed={failed || undefined} data-context={row.context || undefined} data-running={ongoing || undefined}
          onFocus={() => setFocusId(node.id)} aria-label={`${node.name}，${traceKind(node.kind)}，${traceStatus(node.status)}，${nodeDurationLabel(node)}`}
          onKeyDown={event => {
            if (event.target !== event.currentTarget) return;
            if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End', 'Enter', ' '].includes(event.key)) event.preventDefault();
            if (event.key === 'ArrowDown') focusRow(index + 1);
            if (event.key === 'ArrowUp') focusRow(index - 1);
            if (event.key === 'Home') focusRow(0);
            if (event.key === 'End') focusRow(rows.length - 1);
            if (event.key === 'ArrowRight') { if (row.hasChildren && !row.expanded && !filtering) onToggle(node.id); else if (row.hasChildren) focusRow(index + 1); }
            if (event.key === 'ArrowLeft') { if (row.hasChildren && row.expanded && !filtering) onToggle(node.id); else { const parent = rows.findIndex(value => value.node.id === node.parentId); if (parent >= 0) focusRow(parent); } }
            if (event.key === 'Enter' || event.key === ' ') onSelect(node);
          }}>
          <div className="pw-trace-name" style={{ paddingInlineStart: Math.min(row.level, 5) * 12 }}>
            {row.hasChildren && !filtering ? <IconButton className="pw-trace-fold" size="small" tabIndex={-1}
              icon={row.expanded ? RiArrowDownSLine : RiArrowRightSLine} aria-label={`${row.expanded ? '折叠' : '展开'} ${node.name}`}
              aria-expanded={row.expanded} onClick={() => onToggle(node.id)}/> : <span className="pw-trace-kind" aria-hidden="true"><Kind size={16}/></span>}
            <button type="button" tabIndex={-1} className="pw-trace-title" onClick={() => onSelect(node)} aria-label={`查看 ${node.name} 的调用详情`}>
              <span>{node.name}</span><small>{[node.kind === 'model' && node.model !== node.name ? node.model : null,
                ['UNKNOWN', 'AWAITING_USER', 'NEEDS_CONFIRMATION', 'REJECTED', 'CANCELLED', 'CANCELED'].includes(node.status.toUpperCase()) ? traceStatus(node.status) : null, row.missingParent ? '上级调用在其他页' : null,
                node.attemptNo && node.attemptNo > 1 ? `第 ${node.attemptNo} 次尝试` : null].filter(Boolean).join(' · ')}</small>
            </button>
            <span className="pw-trace-duration"><State aria-hidden="true" size={13}/>{nodeDurationLabel(node)}</span>
          </div>
          {mode === 'timeline' && <button type="button" tabIndex={-1} className="pw-trace-timeline" onClick={() => onSelect(node)} aria-label={`查看 ${node.name} 的时间与用量`}>
            {bar ? <i className={bar.width === null ? 'pw-trace-start-marker' : 'pw-trace-duration-bar'} data-timing={bar.width === null ? 'start-only' : 'elapsed'}
              style={{ left: `min(${bar.left}%, calc(100% - 2px))`, ...(bar.width === null ? {} : { width: `${bar.width}%` }) }}/>
              : <span className="pw-trace-unplaced">开始时间未记录</span>}
          </button>}
        </div>;
      })}
    </div>
  </div>;
}
