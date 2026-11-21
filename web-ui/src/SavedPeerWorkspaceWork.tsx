import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { sameDeviceReadingPlace, type DeviceData, type DeviceReadingPlace } from './devices-workspace';

type Place = { identity: DeviceReadingPlace; authorizationEpoch: number };
type Work = { taskId: string; runId: string; epoch: number; lastSavedAt: number; status: string; retainedOperation: 'workspace.list' | 'workspace.read' };
type Window = { document: number; resume: number };
type Page = { window: Window; place: Place; handle: string; rows: Work[]; hasMore: boolean };
type Visit = { window: Window; place: Place; row: Work; ack?: true; opened?: true };
type Request = (action: 'devices.readSavedPeerWork' | 'devices.openTask', args: DeviceData, accept: (value: DeviceData) => void) => Promise<void>;

const localStatuses: Record<string, string> = {
  queued: '等待执行', preparing: '准备中', running: '执行中', paused: '已暂停', pausing: '正在暂停', stopping: '正在停止',
  verifying: '正在核对', awaiting_user: '待处理', needs_confirmation: '成果待确认', waiting_approval: '待审批', reconciling: '有操作待核对',
  completed: '原工作已结束', failed: '原工作未完成', cancelled: '已取消', interrupted: '已中断',
};
const identifier = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value);
const samePlace = (a: Place, b: Place) => a.authorizationEpoch === b.authorizationEpoch &&
  sameDeviceReadingPlace(a.identity, b.identity.here, b.identity.partner);
const sameWork = (a: Work, b: Work) => a.taskId === b.taskId && a.runId === b.runId && a.epoch === b.epoch;
const view = () => bridge.getState().view as DeviceData | undefined;
const devicesWindow = (): Window | undefined => {
  const current = view();
  if (document.hidden || current?.section !== 'devices' || current.taskId != null || !Number.isSafeInteger(current.resumeRevision)) return;
  return { document: bridge.getDocumentEpoch(), resume: current.resumeRevision };
};
const sameWindow = (a: Window, b: Window | undefined) => !!b && a.document === b.document && a.resume === b.resume;

function savedPage(value: DeviceData, place: Place, window: Window, exact?: Work): Page {
  const rows = value?.rows;
  if (value?.kind !== 'savedPeerWorkspaceWork' || value.readOnly !== true || !identifier(value.savedPeerWorkHandle)
    || value.authorizationEpoch !== place.authorizationEpoch || !sameDeviceReadingPlace(place.identity, value.local, value.peer)
    || typeof value.hasMore !== 'boolean' || !Array.isArray(rows) || rows.length > 12
    || rows.some(row => !row || !identifier(row.taskId) || !identifier(row.runId)
      || !Number.isSafeInteger(row.epoch) || row.epoch < 1 || !Object.hasOwn(localStatuses, row.status)
      || !['workspace.list', 'workspace.read'].includes(row.retainedOperation)
      || !Number.isSafeInteger(row.lastSavedAt) || row.lastSavedAt < 1 || row.lastSavedAt > 8640000000000000)
    || new Set(rows.map(row => row.taskId)).size !== rows.length
    || exact && (value.hasMore || rows.length > 1 || rows.length === 1 && !sameWork(rows[0], exact))) {
    throw new Error('本机工作目录不属于原配对设备或原执行。');
  }
  return { window, place, handle: value.savedPeerWorkHandle, rows, hasMore: value.hasMore };
}

/** Ordinary personal-device return. A retired native page never survives behind Work. */
export function SavedPeerWorkspaceWork({ place, busy, request, release }: {
  place?: Place; busy: boolean; request: Request; release: (handle: string) => void;
}) {
  const [page, setPage] = useState<Page>();
  const [returned, setReturned] = useState<{ window: Window; place: Place; row: Work }>();
  const [error, setError] = useState('');
  const active = useRef<Visit | undefined>(undefined), heading = useRef<HTMLHeadingElement>(null), focused = useRef<Work | undefined>(undefined);
  const currentPlace = useRef(place); currentPlace.current = place;
  const reconcile = () => {
    const original = active.current;
    if (!original) return;
    const native = view();
    if (document.hidden || bridge.getDocumentEpoch() !== original.window.document || !native) {
      active.current = undefined; return;
    }
    if (native.resumeRevision === original.window.resume && native.section == null && native.taskId === original.row.taskId) {
      const row = bridge.getState().state.tasks?.filter((item: DeviceData) => item.id === original.row.taskId);
      if (row?.length === 1 && row[0].raw?.runId === original.row.runId && row[0].raw?.coordinatorEpoch === original.row.epoch) original.opened = true;
      else if (row?.length === 1 && row[0].raw?.runId && row[0].raw.runId !== original.row.runId) active.current = undefined;
    } else if (original.ack && original.opened && native.section === 'devices' && native.taskId == null && native.resumeRevision === original.window.resume + 1) {
      active.current = undefined;
      setPage(undefined); setError('');
      setReturned({ window: { document: original.window.document, resume: native.resumeRevision }, place: original.place, row: original.row });
    } else if (native.resumeRevision !== original.window.resume ||
      (native.section !== 'devices' && native.taskId !== original.row.taskId)) active.current = undefined;
  };
  useEffect(() => {
    const stop = bridge.subscribe(reconcile);
    const retire = () => { active.current = undefined; setPage(undefined); setReturned(undefined); setError(''); };
    const stopDoc = bridge.subscribeDocumentEpoch(retire);
    document.addEventListener('visibilitychange', retire);
    return () => { active.current = undefined; stop(); stopDoc(); document.removeEventListener('visibilitychange', retire); };
  }, []);
  const releasePage = useRef(release); releasePage.current=release;
  useEffect(() => () => {
    if (page && sameWindow(page.window,devicesWindow())) releasePage.current(page.handle);
  }, [page]);
  useEffect(() => {
    if (!place || !returned || !samePlace(place, returned.place) || focused.current === returned.row || !sameWindow(returned.window, devicesWindow())) return;
    let frame = 0;
    const priorFocus=document.activeElement, limit=performance.now()+1000;
    const restore = () => {
      const target=heading.current;
      if (!sameWindow(returned.window, devicesWindow()) || !currentPlace.current || !samePlace(returned.place,currentPlace.current)) return;
      if (!target?.isConnected || target.closest('[inert], [aria-hidden="true"]')) {
        if (performance.now() < limit) frame=window.requestAnimationFrame(restore);
        return;
      }
      if (document.activeElement!==document.body && document.activeElement!==priorFocus && document.activeElement!==target) return;
      focused.current=returned.row;
      target.focus({ preventScroll: true }); target.scrollIntoView({ block: 'nearest' });
    };
    frame=window.requestAnimationFrame(restore);
    return () => window.cancelAnimationFrame(frame);
  }, [place, returned]);
  if (!place) return null;
  const ownerWindow=devicesWindow();
  const listed=page && sameWindow(page.window,ownerWindow) && samePlace(page.place,place) ? page : undefined;
  const remembered=returned && sameWindow(returned.window,ownerWindow) && samePlace(returned.place,place) ? returned : undefined;
  const load = (from?: Page, exact?: Work) => {
    const original=devicesWindow();
    if (busy || !original || !currentPlace.current || !samePlace(place,currentPlace.current) || from && !sameWindow(from.window,original)) return;
    setError('');
    const args=from ? { savedPeerWorkHandle: from.handle } : {
      local: place.identity.here, peer: place.identity.partner, authorizationEpoch: place.authorizationEpoch,
      ...(exact ? { original: { taskId: exact.taskId, runId: exact.runId, epoch: exact.epoch } } : {}),
    };
    void request('devices.readSavedPeerWork',args,value=>{
      if (!sameWindow(original,devicesWindow()) || !currentPlace.current || !samePlace(place,currentPlace.current)) return;
      const next=savedPage(value,place,original,exact);
      setPage(next);
      if (exact && !next.rows.length) setError('本次未重新核对到这份原工作；没有打开别的记录。');
      else setReturned(undefined);
    });
  };
  const open = (entry: Work, original: Page) => {
    if (busy || !sameWindow(original.window,devicesWindow()) || !currentPlace.current || !samePlace(original.place,currentPlace.current) || !original.rows.includes(entry)) return;
    const visit: Visit={ window: original.window, place: original.place, row: entry };
    active.current=visit; setError('');
    void request('devices.openTask',{ savedPeerWorkHandle: original.handle, taskId: entry.taskId },reply=>{
      if (active.current !== visit || reply?.taskId !== entry.taskId || reply.readOnly !== true || reply.executionAuthorized !== false) return;
      visit.ack=true; reconcile();
    }).finally(()=>{ if (active.current===visit && !visit.ack) active.current=undefined; });
  };
  const workRow = (row: Work, isReturn = false) => <article className="pw-device-task" key={row.taskId}>
    <header><h4 ref={isReturn ? heading : undefined} tabIndex={isReturn ? -1 : undefined}>
      {isReturn ? '刚查看的原工作' : row.retainedOperation === 'workspace.list' ? '查看工作区目录' : '读取工作区文件'} · {row.taskId.slice(-6)}
    </h4></header>
    <p>本机留存：{localStatuses[row.status]} · {new Date(row.lastSavedAt).toLocaleString()}</p>
    <Button variant="secondary" disabled={busy} onClick={() => isReturn ? load(undefined,row) : listed && open(row,listed)}>
      {isReturn ? '重新核对这份原工作' : '打开这份原工作'}
    </Button>
  </article>;
  return <section className="pw-device-section pw-device-tasks" aria-label="这台设备保存在本机的工作区读取记录">
    <header className="pw-device-section-heading"><div><h3>本机工作区读取记录</h3>
      <p>查看这台原设备的本机留存，不需要对方在线，不会联系或重新执行。</p></div></header>
    {remembered && workRow(remembered.row,true)}
    {listed?.rows.map(row => workRow(row))}
    {listed && !listed.rows.length && !remembered && <p role="status">{listed.hasMore
      ? '这一页没有核对到原工作，可以继续读更早的本机记录。' : '本次没有核对到更多这台原设备的工作区读取记录。'}</p>}
    {error && <p role="status">{error}</p>}
    <div className="pw-form-actions">
      <Button variant={listed || remembered ? 'ghost' : 'secondary'} disabled={busy} onClick={() => load()}>
        {listed || remembered ? '从最近的本机记录读取' : '读取这台设备的本机记录'}
      </Button>
      {listed?.hasMore && <Button variant="secondary" disabled={busy} onClick={() => load(listed)}>更早记录</Button>}
    </div>
  </section>;
}
