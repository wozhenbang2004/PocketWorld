/** Adapted from Whaleness TrajectoryView.swift (MIT, Chaofan Li), pinned source
 * in library/upstream/whaleness. Retains the three lanes, order/duration weights,
 * horizontal scrolling and nearest-in-lane navigation; adds keyboard access. */
import { useRef, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { IconClock } from '../../icons';
import { Button } from '../../forms-library';
import { durationLabel, traceFailed, traceInProgress, traceOverviewLayout, traceRoleLabel, traceStatus, type TraceEventRow, type TraceNode } from '../../execution-trace';

export function TraceOverview({ rows, mode, onModeChange, highlightedId, turnCount, toolCount, onLocate }: {
  rows: TraceEventRow[]; mode: 'order' | 'duration'; onModeChange: (mode: 'order' | 'duration') => void;
  highlightedId: string | null; turnCount: number; toolCount: number; onLocate: (node: TraceNode) => void;
}) {
  const { entries, duration, unknown } = traceOverviewLayout(rows, mode);
  const [focusId, setFocusId] = useState<string | null>(null);
  const svg = useRef<SVGSVGElement>(null);
  const focused = entries.some(entry => entry.node.id === focusId) ? focusId : entries[0]?.node.id;
  function locateNearest(event: MouseEvent<SVGSVGElement>) {
    if (!svg.current || !entries.length) return;
    const bounds = svg.current.getBoundingClientRect();
    const position = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width));
    const lane = Math.max(0, Math.min(2, Math.floor((event.clientY - bounds.top) / 28)));
    const candidates = entries.filter(entry => entry.lane === lane);
    const nearest = (candidates.length ? candidates : entries).reduce((best, entry) => {
      const distance = (value: typeof entry) => Math.max(0, value.start - position, position - value.end);
      return distance(entry) < distance(best) ? entry : best;
    });
    setFocusId(nearest.node.id); onLocate(nearest.node);
  }
  function onKey(event: KeyboardEvent<SVGGElement>, index: number) {
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onLocate(entries[index].node); return; }
    const next = event.key === 'ArrowRight' ? Math.min(entries.length - 1, index + 1)
      : event.key === 'ArrowLeft' ? Math.max(0, index - 1) : event.key === 'Home' ? 0 : event.key === 'End' ? entries.length - 1 : -1;
    if (next < 0) return;
    event.preventDefault(); setFocusId(entries[next].node.id);
    const element = svg.current?.querySelectorAll<SVGGElement>('[data-overview-node]')[next];
    element?.focus(); element?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
  return <section className="pw-trace-lanes" aria-label="轨迹总览" data-overview-mode={mode}>
    <header className="pw-trace-lanes-header">
      <Button variant="ghost" leadingIcon={IconClock} aria-pressed={mode === 'duration'} aria-label="按实际耗时展示总览" onClick={() => onModeChange(mode === 'order' ? 'duration' : 'order')}>{mode === 'duration' ? '时长' : '顺序'}</Button>
      <span><b>{turnCount}</b> Turns</span><span><b>{toolCount}</b> Calls</span>
      <span className="pw-trace-measured" title="已记录调用耗时之和；并行调用可能重叠，不等同于执行总历时">{durationLabel(entries.some(entry => entry.duration !== null) ? duration : null)}{unknown > 0 && entries.some(entry => entry.duration !== null) && <small>已测</small>}</span>
    </header>
    <div className="pw-trace-lane-plot">
      <div className="pw-trace-lane-labels" aria-hidden="true"><span>Input</span><span>Model</span><span>Tools</span></div>
      <div className="pw-trace-lane-scroll" data-vaul-no-drag>
        <svg ref={svg} role="group" aria-label="三泳道轨迹。左右方向键选择，回车定位到轮次列表" width="100%" height="84" style={{ minWidth: `${Math.max(240, entries.length * 8)}px` }} onClick={locateNearest}>
          {[14, 42, 70].map(y => <line key={y} x1="0" x2="100%" y1={y} y2={y} className="pw-trace-lane-guide"/>)}
          {entries.map((entry, index) => <g key={entry.node.id} role="button" tabIndex={entry.node.id === focused ? 0 : -1}
            aria-label={`定位 ${traceRoleLabel(entry.node)} ${entry.node.name}，${entry.node.turnIndex == null ? '轮次未记录' : `Turn ${entry.node.turnIndex}`}，${traceStatus(entry.node.status)}，${durationLabel(entry.duration)}`}
            data-overview-node={entry.node.id} data-lane={entry.lane} data-highlighted={entry.node.id === highlightedId || undefined}
            data-failed={traceFailed(entry.node) || entry.request && traceFailed(entry.request) || undefined}
            data-running={traceInProgress(entry.node.status) || undefined} data-timing={entry.duration === null ? 'unknown' : 'observed'}
            onFocus={() => setFocusId(entry.node.id)} onKeyDown={event => onKey(event, index)}
            onClick={event => { event.stopPropagation(); setFocusId(entry.node.id); onLocate(entry.node); }}>
            <title>{`${traceRoleLabel(entry.node)} · ${entry.node.name} · ${durationLabel(entry.duration)}`}</title>
            <rect className="pw-trace-lane-hit" x={`${entry.start * 100}%`} y={entry.lane * 28} width={`${(entry.end - entry.start) * 100}%`} height="28"/>
            <rect className="pw-trace-lane-bar" x={`${(entry.start + (entry.end - entry.start) * .08) * 100}%`} y={entry.lane * 28 + (entry.lane === 1 ? 9 : 10)}
              width={`${Math.max(.08, (entry.end - entry.start) * .84 * 100)}%`} height={entry.lane === 1 ? 10 : 8} rx="1.5"/>
          </g>)}
        </svg>
      </div>
    </div>
    {mode === 'duration' && <div className="pw-trace-overview-scale" aria-label="累计已测耗时刻度"><span>0 秒</span><span>{durationLabel(entries.some(entry => entry.duration !== null) ? duration : null)}</span></div>}
    <p className="pw-trace-lane-caption">{mode === 'duration' ? '宽度按已测耗时 · 虚线表示耗时缺失' : '按原执行顺序 · 点选条块定位记录'}<span>横向滑动查看</span></p>
  </section>;
}
