/**
 * Adapted from CopilotKit/openmuse ComputerEntry, DesktopToolCard and ComputerSheet.
 * Copyright (c) 2026 OpenMuse contributors. MIT.
 * License notice: ../THIRD-PARTY-NOTICES.md
 */
import { useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore, type RefObject } from 'react';
import { IconArrowUpRight, IconClose, IconError, IconExpand, IconLoading, IconMonitor, IconPause, IconPlay, IconRefresh, IconSettings, IconStop } from './icons';
import * as Dialog from '@radix-ui/react-dialog';
import { Drawer } from './shell-library';
import { Select, SelectItem } from './forms-library/boardui/components/base/select/select';
import { bridge } from './bridge';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { PhoneViewerConnection, phoneViewerKey, type PhonePreviewFrame, type PhoneViewerIdentity, type PhoneViewerState, type PhoneViewerTask } from './phone-viewer-protocol';
import './phone-viewer.css';
import './browser-depth.css';

export type { PhoneViewerIdentity, PhoneViewerTask } from './phone-viewer-protocol';
export type PhoneViewerProps = {
  task: PhoneViewerTask;
  expanded?: boolean;
  onExpandedChange?: (expanded: boolean) => void;
  card?: boolean;
  entry?: boolean;
  nativeVisible?: boolean;
  onPause?: (identity: PhoneViewerIdentity) => void | Promise<unknown>;
  onResume?: (identity: PhoneViewerIdentity) => void | Promise<unknown>;
  onStop?: (identity: PhoneViewerIdentity) => void | Promise<unknown>;
  onOpenTask?: (identity: PhoneViewerIdentity) => void;
  onOpenSetup?: (identity: PhoneViewerIdentity) => void;
  tasks?: readonly PhoneViewerTask[];
  onSelectTask?: (task: PhoneViewerTask) => void;
};

const empty: PhoneViewerState = { phase: 'idle', frame: null, error: '', verifiedAt: 0 };
const terminal = (task: PhoneViewerTask) => task.status === 'ended' || task.status === 'error';
const identityOf = ({ taskId, runId, epoch }: PhoneViewerIdentity): PhoneViewerIdentity => ({ taskId, runId, epoch });

function useDocumentVisible() {
  const [visible, setVisible] = useState(() => typeof document !== 'undefined' && document.visibilityState !== 'hidden');
  useEffect(() => {
    const update = () => setVisible(document.visibilityState !== 'hidden');
    const hide = () => setVisible(false);
    document.addEventListener('visibilitychange', update);
    window.addEventListener('pagehide', hide);
    window.addEventListener('pageshow', update);
    return () => { document.removeEventListener('visibilitychange', update); window.removeEventListener('pagehide', hide); window.removeEventListener('pageshow', update); };
  }, []);
  return visible;
}

function useInView(ref: RefObject<HTMLElement | null>, enabled: boolean) {
  const [visible, setVisible] = useState(true);
  useEffect(() => {
    if (!enabled || !ref.current || typeof IntersectionObserver === 'undefined') { setVisible(true); return; }
    const observer = new IntersectionObserver(entries => setVisible(entries.some(item => item.isIntersecting)), { threshold: 0 });
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, [enabled, ref]);
  return visible;
}

/** Exactly one native handle and timer; neither the card nor the sheet starts a feed. */
export function usePhoneViewerFeed(task: PhoneViewerTask, enabled: boolean, nativeVisible = true) {
  const key = phoneViewerKey(task);
  const [published, setPublished] = useState<{ key: string; value: PhoneViewerState }>({ key, value: empty });
  const [decoded, setDecoded] = useState<{ key: string; frame: PhonePreviewFrame } | null>(null);
  const [decodeError, setDecodeError] = useState<{ key: string; message: string } | null>(null);
  const connection = useRef<PhoneViewerConnection | null>(null);
  const documentVisible = useDocumentVisible();
  const documentEpoch = useSyncExternalStore(bridge.subscribe, bridge.getDocumentEpoch, () => 0);
  const visible = enabled && nativeVisible && documentVisible;
  const state = published.key === key ? published.value : empty;
  useLayoutEffect(() => {
    const owner = new PhoneViewerConnection(identityOf(task), (action, args) => bridge.command(action, args), value => setPublished({ key, value }), { documentEpoch: bridge.getDocumentEpoch });
    connection.current = owner;
    setPublished({ key, value: owner.getState() });
    return () => { owner.dispose(); if (connection.current === owner) connection.current = null; };
  }, [key]);
  useEffect(() => { connection.current?.setVisible(visible && task.preparation !== 'blocked', terminal(task)); }, [key, visible, task.status, task.preparation, documentEpoch]);

  // Decode a candidate once for both surfaces. Keep the prior displayed frame on failure.
  useEffect(() => {
    if (!state.frame) return;
    const candidate = state.frame;
    let alive = true;
    const image = new Image();
    image.onload = () => {
      if (!alive) return;
      if (image.naturalWidth !== candidate.previewWidth || image.naturalHeight !== candidate.previewHeight) {
        setDecodeError({ key, message: '画面尺寸未能核对，保留上一帧。' }); return;
      }
      setDecoded({ key, frame: candidate }); setDecodeError(null);
    };
    image.onerror = () => { if (alive) setDecodeError({ key, message: '这帧画面暂时无法显示，保留上一帧。' }); };
    image.src = candidate.src;
    return () => { alive = false; image.onload = null; image.onerror = null; };
  }, [key, state.frame]);
  const frame = decoded?.key === key ? decoded.frame : null;
  const decodeIssue = decodeError?.key === key ? decodeError.message : '';
  const value: PhoneViewerState = {
    ...state, frame,
    phase: decodeIssue && state.phase !== 'ended' && state.phase !== 'hidden' ? 'error' : state.phase === 'live' && !frame ? 'opening' : state.phase,
    error: decodeIssue || state.error,
  };
  return { ...value, visible, retry: () => connection.current?.retry() };
}

type Feed = ReturnType<typeof usePhoneViewerFeed>;
function statusLabel(task: PhoneViewerTask, feed: Feed): string {
  if (task.preparation === 'blocked') return '准备受阻';
  if (terminal(task)) return task.statusLabel || (task.status === 'error' ? '任务出现问题' : '任务已结束');
  if (task.status === 'stopping') return task.statusLabel || '正在停止';
  if (task.status === 'waiting') return task.statusLabel || '待你处理';
  if (task.status === 'paused') return task.statusLabel || '执行已暂停';
  if (task.preparation === 'pending') return task.statusLabel || '准备中';
  if (feed.phase === 'ended') return '本次画面已结束';
  if (feed.phase === 'error') return `${task.statusLabel || '任务执行中'} · 预览中断`;
  if (!feed.visible || feed.phase === 'idle' || feed.phase === 'hidden') return '点击查看';
  if (feed.phase === 'opening' || feed.phase === 'waiting') return '正在读取画面';
  return task.statusLabel || '连续预览';
}

/** OpenMuse ComputerEntry: quiet centered capsule with a text-labelled status dot. */
export function PhoneComputerEntry({ task, feed, expanded, onOpen, available = true }: { task: PhoneViewerTask; feed: Feed; expanded: boolean; onOpen: () => void; available?: boolean }) {
  const label = statusLabel(task, feed);
  return <button type="button" className="pw-phone-viewer-entry" aria-label={`手机独立屏 · ${label}`} aria-haspopup={available ? 'dialog' : undefined} aria-expanded={available ? expanded : undefined} disabled={!available} onClick={onOpen}>
    <IconMonitor size={13} aria-hidden="true" /><MotionFeedback as="span" identity={JSON.stringify([phoneViewerKey(task), task.status, task.preparation, feed.phase, feed.visible])} initial={false}>独立屏 · {label}</MotionFeedback><span className="pw-phone-viewer-dot" aria-hidden="true" data-live={feed.phase === 'live' && feed.visible} />
  </button>;
}

function PhoneFrame({ task, feed, large = false }: { task: PhoneViewerTask; feed: Feed; large?: boolean }) {
  const [now, setNow] = useState(Date.now);
  const hasFrame = !!feed.frame;
  useEffect(() => {
    if (!feed.visible || !hasFrame || terminal(task)) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [feed.visible, hasFrame, task.status]);
  const quiet = !feed.visible || feed.phase === 'hidden';
  const ended = terminal(task) || feed.phase === 'ended';
  const blocked = task.preparation === 'blocked';
  const failed = task.status === 'error';
  const needsOwner = blocked || task.status === 'waiting' || task.status === 'paused';
  const delayed = feed.phase !== 'live' || quiet || ended || (feed.verifiedAt > 0 && now - feed.verifiedAt > 3000);
  const waiting = !ended && !needsOwner && (feed.phase === 'opening' || feed.phase === 'waiting');
  const title = blocked ? '手机准备未完成' : failed ? '任务未完成' : task.status === 'waiting' ? '任务等待处理' : task.status === 'paused' ? '任务已暂停' : ended ? task.statusLabel || '本次画面已结束' : feed.phase === 'error' ? '暂时无法读取画面' : quiet ? '预览已收起' : task.preparation === 'pending' ? '正在准备独立屏' : '等待独立屏画面';
  const description = blocked ? task.detail || '手机准备条件尚未满足，请先处理原任务的连接与权限。' : failed ? task.detail || '这次任务没有完成，请查看执行过程了解原因。' : needsOwner ? task.detail || '请先处理原任务的待办，再继续执行。' : ended ? '本次查看没有保留画面，可查看原任务的结果与过程。' : feed.phase === 'error' ? feed.error || '预览暂时中断，可以重新读取。' : quiet ? '打开查看器后继续读取这次任务的画面。' : '收到这次任务的第一帧画面后，会显示在这里。';
  const retained = failed ? '任务未完成 · 最后画面' : ended ? '任务已结束 · 最后画面' : quiet ? '预览已暂停 · 上一帧' : '画面暂未更新 · 上一帧';
  const time = feed.frame ? new Date(feed.frame.receivedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '';
  return <figure className={`pw-phone-viewer-frame${large ? ' pw-phone-viewer-frame-large' : ''}`} data-retained={!!feed.frame && delayed} data-compact-empty={!feed.frame && (ended || needsOwner) || undefined}>
    <div className="pw-phone-viewer-image">
      {feed.frame ? <img src={feed.frame.src} width={feed.frame.previewWidth} height={feed.frame.previewHeight} alt={`${task.title || '这次手机任务'}的独立屏画面`} draggable={false} /> :
        <MotionFeedback identity={title} initial={false} className="pw-phone-viewer-placeholder" role="status">
          {waiting && feed.visible ? <IconLoading size={26} className="pw-phone-viewer-spinner" aria-hidden="true" /> : blocked || failed || feed.phase === 'error' ? <IconError size={26} aria-hidden="true" /> : <IconMonitor size={28} aria-hidden="true" />}
          <strong>{title}</strong><p>{description}</p>
        </MotionFeedback>}
    </div>
    <figcaption aria-hidden={!feed.frame} data-empty={!feed.frame || undefined}>{feed.frame && <>
      <span>{delayed ? retained : task.status === 'paused' ? '执行已暂停 · 画面持续更新' : '画面持续更新'}</span><time dateTime={new Date(feed.frame.receivedAt).toISOString()}>收到于 {time}</time>
    </>}</figcaption>
  </figure>;
}

function TaskSelector({ task, tasks, onSelectTask }: Pick<PhoneViewerProps, 'task' | 'tasks' | 'onSelectTask'>) {
  const root = useRef<HTMLDivElement>(null), [container, setContainer] = useState<Element>();
  useLayoutEffect(() => { setContainer(root.current?.closest('[role="dialog"]') || undefined); }, [tasks?.length]);
  if (!onSelectTask || !tasks || tasks.length < 2) return null;
  return <div ref={root} className="pw-phone-viewer-select"><span className="pw-field-label">查看任务</span><Select aria-label="查看任务" selectedKey={phoneViewerKey(task)} popoverContainer={container} triggerClassName="pw-select-trigger" onSelectionChange={key => { const next = tasks.find(item => phoneViewerKey(item) === key); if (next) onSelectTask(next); }}>{tasks.map(item => <SelectItem key={phoneViewerKey(item)} id={phoneViewerKey(item)} textValue={item.title || '手机任务'}><span>{item.title || '手机任务'}{item.statusLabel ? ` · ${item.statusLabel}` : item.status === 'paused' ? ' · 已暂停' : item.status === 'ended' ? ' · 已结束' : ''}</span></SelectItem>)}</Select></div>;
}

type Controls = { busy: boolean; error: string; pause?: () => void; resume?: () => void; stop?: () => void; openTask?: () => void; openSetup?: () => void };
function PhoneControls({ task, feed, controls }: { task: PhoneViewerTask; feed: Feed; controls: Controls }) {
  const root = useRef<HTMLDivElement>(null);
  const execution = !terminal(task) && feed.phase !== 'ended' ? task.status === 'running' && controls.pause
    ? { action: controls.pause, label: '暂停执行', Icon: IconPause } : task.status === 'paused' && controls.resume
    ? { action: controls.resume, label: '继续执行', Icon: IconPlay } : null : null;
  useCollectionMotion(root, phoneViewerKey(task), JSON.stringify([task.status, feed.phase, !!execution, !!controls.stop, !!controls.openTask, !!controls.openSetup]), { active: feed.visible });
  return <>
    <div ref={root} className="pw-phone-viewer-controls">
      {execution && <button type="button" data-motion-key="execution" className={`pw-phone-viewer-button${task.status === 'paused' ? ' pw-phone-viewer-button-primary' : ''}`} disabled={controls.busy || !feed.visible} onClick={execution.action}>{controls.busy ? <IconLoading size={16} className="pw-phone-viewer-spinner" aria-hidden="true" /> : <execution.Icon size={16} aria-hidden="true" />}{execution.label}</button>}
      {!terminal(task) && task.status !== 'stopping' && controls.stop && <button type="button" data-motion-key="stop" className="pw-phone-viewer-button" disabled={controls.busy || !feed.visible} onClick={controls.stop}><IconStop size={16} aria-hidden="true" />停止任务</button>}
      {task.preparation === 'blocked' && controls.openSetup && <button type="button" data-motion-key="setup" className="pw-phone-viewer-button pw-phone-viewer-button-primary" disabled={controls.busy} onClick={controls.openSetup}><IconSettings size={16} aria-hidden="true" />检查连接</button>}
      {controls.openTask && <button type="button" data-motion-key="task" className="pw-phone-viewer-button" disabled={controls.busy} onClick={controls.openTask}><IconArrowUpRight size={16} aria-hidden="true" />{task.status === 'waiting' ? '处理待办' : task.status === 'error' ? '查看失败原因' : '查看任务'}</button>}
      {task.preparation !== 'blocked' && !terminal(task) && feed.phase !== 'ended' && (feed.phase === 'error' || feed.phase === 'hidden' && feed.visible) && <button type="button" data-motion-key="retry" className="pw-phone-viewer-button" disabled={!feed.visible} onClick={feed.retry}><IconRefresh size={16} aria-hidden="true" />重新读取</button>}
    </div>
    {(controls.error || feed.frame && (feed.error || (task.status === 'error' || task.status === 'waiting') && task.detail)) && <MotionFeedback identity={controls.error || feed.error || task.detail || ''} className="pw-phone-viewer-error" role="status">{controls.error || feed.error || task.detail}</MotionFeedback>}
  </>;
}

/** OpenMuse DesktopToolCard, adapted to the original phone run and a portrait-safe image. */
export function PhoneViewerCard({ task, feed, controls, onExpand, tasks, onSelectTask }: Pick<PhoneViewerProps, 'task' | 'tasks' | 'onSelectTask'> & { feed: Feed; controls: Controls; onExpand: () => void }) {
  return <section className="pw-phone-viewer-card" aria-label="手机独立屏预览">
    <header className="pw-phone-viewer-card-header"><span className="pw-phone-viewer-icon"><IconMonitor size={21} aria-hidden="true" /></span><div className="pw-phone-viewer-card-title"><strong>手机独立屏</strong><span title={task.title}>{task.title || '这次手机任务'}</span></div><MotionFeedback as="span" identity={JSON.stringify([phoneViewerKey(task), task.status, task.preparation, feed.phase, feed.visible])} initial={false} className="pw-phone-viewer-status" role="status">{statusLabel(task, feed)}</MotionFeedback></header>
    <TaskSelector task={task} tasks={tasks} onSelectTask={onSelectTask} />
    <PhoneFrame task={task} feed={feed} />
    <div className="pw-phone-viewer-card-actions"><PhoneControls task={task} feed={feed} controls={controls} />{(feed.frame || !terminal(task) && task.preparation !== 'blocked') && <button type="button" className="pw-phone-viewer-button pw-phone-viewer-expand" aria-haspopup="dialog" onClick={onExpand}><IconExpand size={16} aria-hidden="true" />展开独立屏</button>}</div>
  </section>;
}

function useCompactSheet() {
  const [compact, setCompact] = useState(() => typeof window !== 'undefined' && window.matchMedia('(max-width: 599px)').matches);
  useEffect(() => { const media = window.matchMedia('(max-width: 599px)'); const update = () => setCompact(media.matches); media.addEventListener('change', update); update(); return () => media.removeEventListener('change', update); }, []);
  return compact;
}

/** OpenMuse Sheet geometry, with existing Vaul/Radix focus, escape and touch behavior. */
export function PhoneViewerSheet({ task, feed, controls, expanded, onExpandedChange, tasks, onSelectTask, returnFocus }: Pick<PhoneViewerProps, 'task' | 'tasks' | 'onSelectTask'> & { feed: Feed; controls: Controls; expanded: boolean; onExpandedChange: (value: boolean) => void; returnFocus: RefObject<HTMLElement | null> }) {
  const compact = useCompactSheet(), descriptionId = useId();
  const Title = compact ? Drawer.Title : Dialog.Title, Description = compact ? Drawer.Description : Dialog.Description;
  const contents = <>
    {compact && <Drawer.Handle className="pw-phone-viewer-handle" aria-label="拖动收起独立屏" />}
    <header className="pw-phone-viewer-sheet-header"><div><Title className="pw-phone-viewer-sheet-title">手机独立屏</Title><Description id={descriptionId} className="pw-phone-viewer-description">{task.title || '查看这次手机任务的执行画面'}</Description></div><button type="button" className="pw-phone-viewer-close" aria-label="收起独立屏" onClick={() => onExpandedChange(false)}><IconClose size={20} aria-hidden="true" /></button></header>
    <div className="pw-phone-viewer-sheet-body">
      <TaskSelector task={task} tasks={tasks} onSelectTask={onSelectTask} />
      <div className="pw-phone-viewer-sheet-status"><span className="pw-phone-viewer-icon"><IconMonitor size={24} aria-hidden="true" /></span><MotionFeedback identity={JSON.stringify([phoneViewerKey(task), task.status, task.preparation, feed.phase, feed.visible])} initial={false}><strong role="status">{statusLabel(task, feed)}</strong><p>{task.preparation === 'blocked' ? '请先处理手机准备条件，任务尚未继续。' : task.status === 'error' ? '这次任务未完成，请查看原因后处理。' : terminal(task) || feed.phase === 'ended' ? '画面仅保留于本次查看中，任务结果请以原记录为准。' : '画面来自这次任务的后台独立屏。'}</p></MotionFeedback></div>
      <div className="pw-phone-viewer-sheet-control-rail"><PhoneControls task={task} feed={feed} controls={controls} /></div>
      <PhoneFrame task={task} feed={feed} large />
      {!terminal(task) && feed.phase !== 'ended' && <p className="pw-phone-viewer-note">{task.status === 'waiting' ? '请回到任务处理待办，查看画面不会继续执行。' : task.status === 'paused' ? '任务保持暂停，点击继续执行才会恢复。' : task.status === 'stopping' ? '正在停止任务，保留最后收到的画面。' : '收起画面后，任务仍会在后台继续执行。'}</p>}
    </div>
  </>;
  const restoreFocus = (event: Event) => { if (returnFocus.current?.isConnected) { event.preventDefault(); returnFocus.current.focus(); } };
  return compact ? <Drawer.Root open={expanded} onOpenChange={onExpandedChange} shouldScaleBackground={false} handleOnly autoFocus><Drawer.Portal><Drawer.Overlay className="pw-phone-viewer-overlay" /><Drawer.Content className="pw-phone-viewer-sheet pw-phone-viewer-sheet-compact" aria-describedby={descriptionId} onCloseAutoFocus={restoreFocus}>{contents}</Drawer.Content></Drawer.Portal></Drawer.Root>
    : <Dialog.Root open={expanded} onOpenChange={onExpandedChange}><Dialog.Portal><Dialog.Overlay className="pw-phone-viewer-overlay" /><Dialog.Content className="pw-phone-viewer-sheet" aria-describedby={descriptionId} onCloseAutoFocus={restoreFocus}>{contents}</Dialog.Content></Dialog.Portal></Dialog.Root>;
}

export function PhoneViewer({ task, expanded: controlledExpanded, onExpandedChange, card = true, entry = true, nativeVisible = true, onPause, onResume, onStop, onOpenTask, onOpenSetup, tasks, onSelectTask }: PhoneViewerProps) {
  const [localExpanded, setLocalExpanded] = useState(false);
  const expanded = controlledExpanded ?? localExpanded;
  const changeExpanded = (value: boolean) => { if (controlledExpanded === undefined) setLocalExpanded(value); onExpandedChange?.(value); };
  const cardElement = useRef<HTMLDivElement>(null), returnFocus = useRef<HTMLElement | null>(null);
  const cardVisible = useInView(cardElement, card);
  const feed = usePhoneViewerFeed(task, expanded || card && cardVisible, nativeVisible);
  const key = phoneViewerKey(task), current = useRef({ key, documentEpoch: bridge.getDocumentEpoch(), mounted: true });
  current.current.key = key; current.current.documentEpoch = bridge.getDocumentEpoch();
  const [action, setAction] = useState<{ key: string; busy: boolean; error: string }>({ key, busy: false, error: '' });
  const actionInFlight = useRef<{ key: string; documentEpoch: number } | null>(null);
  useEffect(() => { current.current.mounted = true; return () => { current.current.mounted = false; }; }, []);
  const act = async (callback: NonNullable<PhoneViewerProps['onPause']>) => {
    if (actionInFlight.current?.key === key || !feed.visible || terminal(task)) return;
    const expected = { key, documentEpoch: bridge.getDocumentEpoch() };
    actionInFlight.current = expected; setAction({ key, busy: true, error: '' });
    const valid = () => current.current.mounted && current.current.key === expected.key && bridge.getDocumentEpoch() === expected.documentEpoch;
    try { await callback(identityOf(task)); if (valid()) setAction({ key, busy: false, error: '' }); }
    catch (error) { if (valid()) setAction({ key, busy: false, error: error instanceof Error ? error.message : '任务操作未能确认，请核对原任务。' }); }
    finally { if (actionInFlight.current === expected) actionInFlight.current = null; }
  };
  const controls: Controls = { busy: action.key === key && action.busy, error: action.key === key ? action.error : '', pause: onPause ? () => void act(onPause) : undefined, resume: onResume ? () => void act(onResume) : undefined, stop: onStop ? () => void act(onStop) : undefined, openTask: onOpenTask ? () => { changeExpanded(false); onOpenTask(identityOf(task)); } : undefined, openSetup: onOpenSetup ? () => { changeExpanded(false); onOpenSetup(identityOf(task)); } : undefined };
  const open = () => { if (document.activeElement instanceof HTMLElement) returnFocus.current = document.activeElement; changeExpanded(true); };
  return <div className="pw-phone-viewer" data-phone-viewer-task={task.taskId} data-phone-viewer-run={task.runId} data-phone-viewer-epoch={task.epoch}>
    {entry && <PhoneComputerEntry task={task} feed={feed} expanded={expanded} onOpen={open} available={!!feed.frame || !terminal(task) && task.preparation !== 'blocked'} />}
    {card && <div ref={cardElement}><PhoneViewerCard task={task} feed={feed} controls={controls} onExpand={open} tasks={tasks} onSelectTask={onSelectTask} /></div>}
    <PhoneViewerSheet task={task} feed={feed} controls={controls} expanded={expanded} onExpandedChange={changeExpanded} tasks={tasks} onSelectTask={onSelectTask} returnFocus={returnFocus} />
  </div>;
}
