import { useEffect, useRef, useState } from 'react';
import { IconClose } from './icons';
import { bridge } from './bridge';
import { usePocketSnapshot } from './usePocketSnapshot';
import { Drawer } from './shell-library';
import { Accordion, AccordionItem, Button, CheckboxField, HostFileUpload, IconButton } from './forms-library';
import { ShimmerText } from './library';
import './collaboration-depth.css';

type Data = Record<string, any>;
export type RouteReferenceTarget = { taskId: string; runId: string; epoch: number; namespace: 'owner/private' };
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const intent: Record<string, string> = { home: '首页', search: '搜索', settings: '设置', back: '返回', next: '下一页', previous: '上一页', expand: '展开', collapse: '收起', details: '详情', menu: '菜单', notifications: '通知', messages: '消息', profile: '我的', scroll: '滚动' };

/** The same approved Vaul source drawer and BoardUI controls; all bytes and source eligibility
 * remain in the native owner flow. There is no execute/install/schedule action in this view. */
export function RouteReference({ target, onClose }: { target: RouteReferenceTarget; onClose: () => void }) {
  const snapshot = usePocketSnapshot();
  const selected = snapshot.state.routeReference || {};
  const state: Data = selected.open && selected.target?.taskId === target.taskId && selected.target?.runId === target.runId && selected.target?.epoch === target.epoch && selected.target?.namespace === target.namespace ? selected : {};
  const [opening, setOpening] = useState(true), [requestBusy, setRequestBusy] = useState(false), [error, setError] = useState(''), [acknowledged, setAcknowledged] = useState(false);
  const alive = useRef(true), entered = useRef(false), handle = useRef(''), generation = useRef(0);
  if (state.handle) handle.current = state.handle;
  const original = { taskId: target.taskId, runId: target.runId, epoch: target.epoch, namespace: target.namespace };
  useEffect(() => {
    const request = ++generation.current; alive.current = true; entered.current = false; setOpening(true); setRequestBusy(false); setError(''); setAcknowledged(false);
    void bridge.command('routeReference.open', original).then(result => {
      if (alive.current && generation.current === request) handle.current = result.handle || '';
      else if (result.handle) void bridge.command('routeReference.close', { handle: result.handle }).catch(() => {});
    }).catch(failure => { if (alive.current && generation.current === request) setError(failure.message); }).finally(() => { if (alive.current && generation.current === request) setOpening(false); });
    return () => { alive.current = false; const id = handle.current; handle.current = ''; if (id) void bridge.command('routeReference.close', { handle: id }).catch(() => {}); };
  }, [target.taskId, target.runId, target.epoch, target.namespace]);
  const busy = opening || requestBusy || state.busy === true;
  const run = async (action: string, input: Data = {}) => {
    if (entered.current || busy || !state.handle) return;
    const request = generation.current; entered.current = true; setRequestBusy(true); setError('');
    try { await bridge.command(action, { handle: state.handle, ...input }); }
    catch (failure) { if (alive.current && generation.current === request) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (generation.current === request) { entered.current = false; if (alive.current) setRequestBusy(false); } }
  };
  const review = state.review, incoming = state.incoming, picking = !!state.selectedRequestId;
  const reference = (view: Data, imported: boolean) => <section className="pw-source-record">
    <h3>{imported ? '所选文件的参考内容' : '原任务的可分享参考'}</h3>
    <dl className="pw-facts"><div><dt>App 包名</dt><dd>{view.reference?.application?.packageName}</dd></div><div><dt>记录的版本</dt><dd>{view.reference?.application?.versionCode}</dd></div><div><dt>导航步骤</dt><dd>{rows(view.reference?.steps).length} 步</dd></div></dl>
    <p className="pw-route-file-identity">文件 {view.bytes} 字节 · SHA-256 {view.sha256}</p><p className="pw-route-file-identity">内容指纹 {view.digest}</p>
    <p>{view.message}</p><p className="pw-field-hint">参考不带执行授权；每步的目标与条件仍需在本机重新观察和绑定。</p>
    <Accordion variant="divided" allowsMultipleExpanded>
      <AccordionItem title="逐步导航参考" description="核对导航结构与未绑定条件"><ol className="pw-route-steps">{rows(view.reference?.steps).map((step, index) => <li key={index}><strong>{index + 1}. {intent[step.navigation] || step.navigation} · {step.role}{step.direction ? ` · ${step.direction === 'forward' ? '向前' : '向后'}` : ''}</strong><p className="pw-muted">目标 {step.targetParameter}，前置条件 {step.beforeParameter}，结束条件 {step.afterParameter}：均未绑定。</p></li>)}</ol></AccordionItem>
      <AccordionItem title={imported ? '查看完整已校验文件内容' : '核对将实际分享的完整文件'}><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{view.text}</pre></AccordionItem>
      <AccordionItem title="适用边界说明"><p>相同指纹只表示导航参考内容相同。没有跨设备或其他 App 版本验证，也不带原任务的确认、来源身份、节点或页面文字。</p></AccordionItem>
    </Accordion>
    {imported && incoming?.matchesCurrentReference && <p>这份文件与上方原路线参考的内容指纹相同；没有因此提高其信任级别。</p>}
  </section>;
  return <Drawer.Root open onOpenChange={open => { if (!open && !busy) onClose(); }} shouldScaleBackground={false}><Drawer.Portal>
    <Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer pw-route-reference" aria-describedby="route-reference-description">
      <div className="pw-drawer-handle" /><header className="pw-drawer-header"><div><Drawer.Title>路线参考</Drawer.Title><Drawer.Description id="route-reference-description">核对并交换导航结构。目标与每步条件需要在本机重新观察，这里不会执行路线。</Drawer.Description></div><IconButton icon={IconClose} aria-label="返回原任务" disabled={busy} onClick={onClose} /></header>
      <div className="pw-drawer-body pw-route-reference-body">
        {busy && <ShimmerText>正在核对原路线参考…</ShimmerText>}{(error || state.error) && <p className="pw-error" role="alert">{error || state.error}</p>}{state.notice && <p role="status">{state.notice}</p>}
        {!opening && !state.open && <p>原参考窗口已关闭，请返回任务后重新打开。</p>}
        {review && <section className="pw-route-export">{reference(review, false)}<CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy || picking}>我已核对将导出的完整文件，理解它只供参考</CheckboxField><div className="pw-action-row"><Button disabled={busy || picking || !acknowledged} onClick={() => { void run('routeReference.chooseExport', { confirmed: true }); }}>选择保存位置</Button><Button variant="secondary" disabled={busy || picking || !acknowledged} onClick={() => { void run('routeReference.share', { confirmed: true }); }}>打开系统分享</Button></div></section>}
        {state.open && state.checked && !state.available && !busy && <p className="pw-muted">这次原任务没有可导出的已确认路线。只有原任务实际留下的有限导航，并通过主人结果确认与当前来源核对后，才能生成参考。</p>}
        <aside className="pw-route-import">{state.open && <section className="pw-source-record"><h3>检查别人分享的参考</h3><HostFileUpload disabled={busy || picking} onChoose={() => { void run('routeReference.chooseImport'); }} label="选择路线参考 JSON" status="idle" statusDescription="使用系统文件选择器。只校验与预览，不登记、安装或执行。" /></section>}
        {picking && <section className="pw-source-record"><h3>{state.selectionKind === 'export' ? '本次保存位置' : '本次所选文件'}</h3><p>{state.pickerPending ? '请先完成系统文件选择。' : state.selectionKind === 'export' ? '保存前会再次核对原任务、路线与确认来源。文件可能已由系统建立为空文件。' : '尚未读取正文。点击检查后会验证大小、格式、白名单与摘要。'}</p><div className="pw-action-row">{!state.pickerPending && <Button disabled={busy} onClick={() => { void run(state.selectionKind === 'export' ? 'routeReference.writeSelected' : 'routeReference.inspectSelected', { requestId: state.selectedRequestId }); }}>{state.selectionKind === 'export' ? '保存已审阅的参考' : '检查所选文件'}</Button>}<Button variant="ghost" disabled={busy} onClick={() => { void run('routeReference.discardSelection', { requestId: state.selectedRequestId }); }}>放弃这次选择</Button></div></section>}
        {incoming && reference(incoming, true)}</aside>
        {state.receipt?.saved && <p>已保存原参考，字节数 {state.receipt.bytes}。内容不带执行授权。</p>}
        {state.receipt?.shareSheetOpened && <p>已打开系统分享面板，尚未确认发送结果。</p>}
      </div>
    </Drawer.Content>
  </Drawer.Portal></Drawer.Root>;
}
