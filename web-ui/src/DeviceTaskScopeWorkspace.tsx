import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog, TextField } from './forms-library';
import { bridge } from './bridge';
import { materialPath, routeDraft, routeTitle, scopeRows as rows, scopeSelection, type RouteDraft, type ScopeData as Data } from './device-task-scope';

type Run = (action: string, args?: Data) => Promise<any>;
const time = (value: any) => Number.isSafeInteger(Number(value)) ? new Date(Number(value)).toLocaleString() : '尚未确认';
const deviceName = (id: string, peers: Data[]) => peers.find(peer => peer.deviceId === id)?.label || '设备 ' + id.slice(-8);
const tools = (value: any): string => Array.isArray(value) ? value.map(row => row.toolName).join('、') : value && typeof value === 'object' ? Object.keys(value).join('、') : '未提供';

function ScopeFacts({ value, peers, human = false }: { value: Data; peers: Data[]; human?: boolean }) {
  const resource = value.resourceBinding || {};
  const declarations = Array.isArray(value.toolDeclarations) ? value.toolDeclarations : Object.entries(value.toolDeclarations || {}).map(([toolName, effect]) => ({ toolName, effect }));
  const writes = declarations.filter(row => row.effect === 'write').length, external = declarations.filter(row => row.effect === 'external').length;
  const target = resource.kind === 'desktop' ? `电脑屏幕 ${resource.displayId}${resource.windowId ? ' · 指定窗口 ' + resource.windowId : ''}`
    : resource.screenMode ? `${resource.screenMode === 'background' ? '独立后台屏' : '手机主屏'} · ${(resource.packageNames || []).join('、') || '按原授权应用范围'}`
    : resource.projectId ? `项目 ${resource.projectId}${resource.checkoutId ? ' · 工作副本 ' + resource.checkoutId : ''}` : '对端明确授权的工作区';
  return <>
    <dl className="pw-facts">
      <div><dt>接收设备</dt><dd>{deviceName(value.targetDeviceId || '', peers)}</dd></div>
      <div><dt>使用方式</dt><dd>{routeTitle(value)}</dd></div>
      <div><dt>作用范围</dt><dd>{target}</dd></div>
      <div><dt>操作影响</dt><dd>{human ? '你逐次进行全屏输入；不调用模型，不继承 AI 自动操作权限' : value.kind === 'goal' ? '对端模型在原授权工具范围内处理目标' : writes || external ? `含 ${writes} 项写入操作、${external} 项外部影响能力，执行时仍受原工具审批约束` : declarations.length && declarations.every(row => row.effect === 'read') ? '当前列出的工具仅报告读取能力' : '尚未取得完整操作影响，不能推定只读'}</dd></div>
      {value.toolBudget !== undefined && <div><dt>累计份额</dt><dd>工具 {value.toolBudget} 次 · 模型 {value.modelBudget} 次</dd></div>}
      {value.deadline && <div><dt>截止</dt><dd>{time(value.deadline)}</dd></div>}
    </dl>
    {!human && resource.screenMode && resource.allowWholeDisplayCapture === true && <p>画面留存：从“新设备工作”建立的任务，已验证的手机观察帧会按本机“截图留存”设置加密保存为历史快照，默认 1 天；关闭留存则不会在任务中显示新图。只保留最近一张，单张不超过 4 MB，不是实时视频或点图控制。</p>}
    <Accordion><AccordionItem id="exact-resource" title="核对准确身份与工具"><dl className="pw-facts"><div><dt>设备身份</dt><dd>{value.targetDeviceId}</dd></div><div><dt>准确资源</dt><dd>{JSON.stringify(resource)}</dd></div><div><dt>明确工具</dt><dd>{tools(value.toolDeclarations)}</dd></div></dl></AccordionItem></Accordion>
  </>;
}

function RouteMaterials({ kind, option, view, draft, disabled, update }: { kind: 'files' | 'projects'; option: Data; view: Data; draft: RouteDraft; disabled: boolean; update: (next: RouteDraft) => void }) {
  const allowed = rows(option.toolDeclarations).some(tool => tool.toolName === (kind === 'files' ? 'workspace.receive' : 'workspace.project.receive') && tool.effect === 'write');
  const catalog = rows(view[kind === 'files' ? 'materialOptions' : 'projectOptions']);
  const selected = draft[kind], max = kind === 'files' ? 4 : 1;
  if (!allowed && !selected.length) return null;
  const missing = selected.filter(item => !catalog.some(row => row.id === item.id && row.sha256 === item.sha256 && row.available === true));
  const change = (items: RouteDraft['files']) => update({ ...draft, [kind]: items });
  return <AccordionItem id={kind} title={kind === 'files' ? '发送明确原件' : '发送已核实项目包'} description={selected.length ? '已选 ' + selected.length + ' 项' : '默认不发送'}>
    <p>只分享你在这里选择的原件，不包含私人记忆或整份工作区。{kind === 'files' ? '每项上限 256 KiB，最多 4 项。' : '最多 1 个固定项目包；普通 ZIP 不作为项目。接收与导入为两次独立确认。'}</p>
    {!allowed && <p role="alert">当前范围未报告接收权限；请移除旧选择，或回设备端明确授权。</p>}
    {missing.map(item => <div key={item.id}><p role="alert">原选择 {item.path} 已无法在当前原件目录中核对。</p><Button variant="ghost" disabled={disabled} onClick={() => change(selected.filter(row => row.id !== item.id))}>移除此项选择</Button></div>)}
    {allowed && catalog.map(item => {
      const chosen = selected.find(row => row.id === item.id && row.sha256 === item.sha256);
      const patch = (key: 'path' | 'expectedVersion', value: string) => change(selected.map(row => row === chosen ? { ...row, [key]: value } : row));
      return <div key={item.id}>
        <CheckboxField isSelected={!!chosen} isDisabled={disabled || item.available !== true || !chosen && selected.length >= max} onChange={checked => change(checked ? [...selected, { id: item.id, sha256: item.sha256, path: materialPath(item.name || 'project-source.zip'), expectedVersion: 'absent' }] : selected.filter(row => row !== chosen))}>
          {item.name || item.id} · {item.size} 字节{item.available === false ? ' · 当前不可发送' : ''}
        </CheckboxField>
        {chosen && <><TextField label="对端工作区中的相对路径" value={chosen.path} isDisabled={disabled} onChange={value => patch('path', value)} />
          {kind === 'files' ? <TextField label="目标文件的预期版本" value={chosen.expectedVersion} isDisabled={disabled} onChange={value => patch('expectedVersion', value)} hint="新建填 absent；覆盖必须填写你已核对的原版本，不做盲覆盖。" /> : <p>仅写入不存在的新路径，不覆盖已有项目。清单 {item.manifestDigest}</p>}
        </>}
      </div>;
    })}
    {allowed && !catalog.length && <p>当前没有可选择的私人原件。没有自动导入其他附件或目录。</p>}
  </AccordionItem>;
}

export function DeviceTaskScopeWorkspace({ taskId, peers = [], run, onBackChange, onSaved, onOpenDevices, autoOpen = false }: { autoOpen?: boolean; taskId: string; peers?: Data[]; run: Run; onBackChange?: (handler: (() => boolean) | null) => void; onSaved?: () => void; onOpenDevices?: () => void }) {
  const [open, setOpen] = useState(false), [view, setView] = useState<Data | null>(null), [drafts, setDrafts] = useState<Record<string, RouteDraft>>({});
  const [review, setReview] = useState<Data | null>(null), [ack, setAck] = useState(false), [removeUnavailable, setRemoveUnavailable] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const mounted = useRef(true), working = useRef(false), generation = useRef(0), page = useRef(''), reviewHandle = useRef(''), openedAutomatically = useRef(false);
  const pendingSave = useRef(false), initialDraft = useRef('');
  const [discard, setDiscard] = useState<'close' | 'reload' | 'devices' | null>(null);
  const dirty = !!view && (JSON.stringify(drafts) !== initialDraft.current || removeUnavailable);
  const requestExit = (next: 'close' | 'reload' | 'devices') => { if (working.current) return; if (dirty) setDiscard(next); else exit(next); };
  const exit = (next: 'close' | 'reload' | 'devices') => { setDiscard(null); if (next === 'reload') doRead(); else { close(); if (next === 'devices') onOpenDevices?.(); } };
  const dismissReview = () => { const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setAck(false); if (handle) void run('deviceTaskScope.dismissReview', { reviewHandle: handle }).catch(() => {}); };
  const close = () => {
    generation.current++; working.current = false; setBusy(false); setDiscard(null);
    if (pendingSave.current) { setNotice('范围保存结果尚未确认。请重新读取原任务的已保存范围，不要重复确认。'); pendingSave.current = false; }
    dismissReview(); const handle = page.current; page.current = ''; setOpen(false); setView(null);
    if (handle) void run('deviceTaskScope.close', { pageHandle: handle }).catch(() => {});
  };
  const liveClose = useRef(close); liveClose.current = close;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; liveClose.current(); }; }, [taskId]);
  useEffect(() => {
    const hide = () => { if (document.visibilityState !== 'visible') liveClose.current(); };
    const owner = () => JSON.stringify([bridge.getDocumentEpoch(), bridge.getState().view]);
    let identity = owner();
    const unsubscribe = bridge.subscribe(() => { const next = owner(); if (next !== identity) { identity = next; liveClose.current(); } });
    const pageHidden = () => liveClose.current();
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', pageHidden);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', pageHidden); };
  }, []);
  useEffect(() => { onBackChange?.(() => { if (!open) return false; if (busy) return true; if (discard) setDiscard(null); else if (review) dismissReview(); else requestExit('close'); return true; }); return () => onBackChange?.(null); }, [open, busy, review, discard, dirty, onBackChange]);
  useEffect(() => {
    if (!review) return;
    const timer = window.setTimeout(() => { dismissReview(); setError('这次范围审阅已到期，请重新核对。'); }, Math.max(0, Number(review.reviewExpiresAt || Date.now() + 120_000) - Date.now()));
    return () => window.clearTimeout(timer);
  }, [review]);
  const invoke = async (action: string, args: Data, accepted: (value: Data) => void) => {
    if (working.current || !mounted.current || document.visibilityState !== 'visible') return;
    const ticket = generation.current; working.current = true; setBusy(true); setError('');
    try {
      const value = await run(action, args);
      if (ticket !== generation.current || !mounted.current) {
        if (value?.pageHandle) void run('deviceTaskScope.close', { pageHandle: value.pageHandle }).catch(() => {});
        return;
      }
      accepted(value);
    } catch (failure) { if (ticket === generation.current && mounted.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (ticket === generation.current && mounted.current) { working.current = false; setBusy(false); } }
  };
  const read = () => { if (view && dirty) { setDiscard('reload'); return; } doRead(); };
  const doRead = () => {
    if (working.current) return;
    dismissReview(); setOpen(true);
    void invoke('deviceTaskScope.open', { taskId }, value => {
      if (!value || value.taskId !== taskId || typeof value.pageHandle !== 'string' || !Array.isArray(value.options)) throw new Error('没有收到本任务的设备范围。');
      page.current = value.pageHandle; const next = Object.fromEntries(rows(value.options).map(option => [option.optionId, routeDraft(option, value)])); initialDraft.current = JSON.stringify(next); setView(value); setDrafts(next); setRemoveUnavailable(false);
    });
  };
  useEffect(() => { if (autoOpen && !openedAutomatically.current) { openedAutomatically.current = true; read(); } }, [autoOpen]);
  const acceptReview = (value: Data) => {
    if (value?.pageHandle !== page.current || value?.taskId !== taskId || !value.reviewHandle) throw new Error('原设备范围审阅不完整，请重新读取。');
    reviewHandle.current = value.reviewHandle; setReview(value); setAck(false);
  };
  const unavailable = rows(view?.selected?.routes).filter(old => !rows(view?.options).some(option => option.optionId === old.optionId));
  const prepare = () => {
    if (!view || unavailable.length && !removeUnavailable) return;
    try { const routes = scopeSelection(view, drafts); void invoke('deviceTaskScope.review', { pageHandle: page.current, routes }, acceptReview); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const confirm = () => {
    if (!review || !ack || working.current) return;
    const captured = review; reviewHandle.current = ''; setReview(null); setAck(false); pendingSave.current = true;
    void invoke(captured.kind === 'import' ? 'deviceTaskScope.confirmImport' : 'deviceTaskScope.confirm', { pageHandle: page.current, reviewHandle: captured.reviewHandle, acknowledged: true }, value => {
      if (value?.saved !== true || value?.taskId !== taskId || value?.reviewHandle !== captured.reviewHandle) throw new Error('保存回执尚未匹配，请重新读取已保存范围。');
      pendingSave.current = false; setNotice(captured.kind === 'import' ? '已保存这份原项目包的导入确认；实际导入仍由原任务执行。' : '设备范围已保存。尚未开始或重启任务；请在原任务明确开始或继续。'); setView(null); close(); onSaved?.();
    }).finally(() => {
      if (pendingSave.current && mounted.current) { pendingSave.current = false; setNotice('保存结果尚未确认。请重新读取已保存范围，没有自动重做。'); setView(null); }
    });
  };
  const peerIds = [...new Set(rows(view?.options).map(option => option.targetDeviceId))];
  return <section className="pw-task-capabilities" aria-label="本任务的远端设备范围">
    <Button variant="secondary" onClick={read} disabled={busy}>选择本任务的远端设备</Button>
    {notice && <p role="status">{notice}</p>}
    <SettingsDialog isOpen={open} onClose={() => requestExit('close')} title={review ? review.kind === 'import' ? '核对原项目包导入' : '核对本任务设备范围' : view?.humanInputOnly ? '选择一个手控手机屏幕' : '选择远端手机或电脑'}>
      <div className="pw-collaboration-dialog">
        {busy && <p role="status">正在读取原设备范围回执…</p>}
        {error && <p role="alert">{error}</p>}
        {!review && !view && !busy && <><p>{notice || '按原任务读取真实授权与可用资源。读取和保存范围不会发送目标或启动模型。'}</p><Button onClick={read}>重新读取已保存范围</Button></>}
        {view && !review && <>
          <h3>{view.goal}</h3>
          <p>{view.humanInputOnly ? '只选择一个手机屏幕。需要对端主人已明确授予全屏捕获、输入及准确应用范围。你逐次操作，不调用模型，不发送材料或转派。' : '只选择这项原任务可以用的设备。工具份额来自本任务剩余预算，设备已配对不等于已授权。默认不发送文件、项目或私人记忆。'}</p>
          <dl className="pw-facts"><div><dt>当前可分配</dt><dd>工具 {view.toolsAvailable} 次 · 模型 {view.modelsAvailable} 次</dd></div><div><dt>原任务最晚截止</dt><dd>{time(view.deadlineMaximum)}</dd></div></dl>
          <p>{view.humanInputOnly ? '每次对端操作同时占用一次本机派发额度；对端份额至少 2 次，最多分配当前总额的一半。每次刷新也占用调用。' : '发起设备操作本身还至少占用 1 次本机工具调用；不要把全部剩余额度分给对端。'}已执行和未知结果的占用不会因改范围退回。</p>
          {unavailable.length > 0 && <><p role="alert">有 {unavailable.length} 项已保存范围当前未能读取。可能是离线、授权到期或资源变化，未替换成其他设备。</p><CheckboxField isSelected={removeUnavailable} isDisabled={busy} onChange={setRemoveUnavailable}>这次保存明确移除当前不可读取的旧选择（不会撤回已发生的操作）</CheckboxField></>}
          {peerIds.map(id => <section key={id}><h3>{deviceName(id, peers)}</h3><Accordion>
            {rows(view.options).filter(option => option.targetDeviceId === id).map(option => {
              const draft = drafts[option.optionId]; if (!draft) return null;
              const update = (next: RouteDraft) => { dismissReview(); setDrafts(old => ({ ...(view.humanInputOnly && next.selected ? Object.fromEntries(Object.entries(old).map(([id, value]) => [id, { ...value, selected: false }])) : old), [option.optionId]: next })); };
              const shareCount = Object.values(draft.shares).filter(row => row.selected).length;
              return <AccordionItem key={option.optionId} id={option.optionId} title={routeTitle(option)} description={draft.selected ? '已选择 · 尚未执行' : '未选择'}>
                <CheckboxField isSelected={draft.selected} isDisabled={busy} onChange={selected => update({ ...draft, selected })}>将这个准确范围用于本任务</CheckboxField>
                <ScopeFacts value={option} peers={peers} human={view.humanInputOnly} />
                {draft.selected && <>
                  {!!draft.missingShares.length && <><p role="alert">有 {draft.missingShares.length} 项原下游范围当前无法核对，未自动替换或移除。</p><Button variant="secondary" disabled={busy} onClick={() => update({ ...draft, missingShares: [] })}>明确移除这些旧下游选择</Button></>}
                  <TextField label={'工具总份额（最多 ' + option.toolBudgetMaximum + ' 次）'} value={draft.tools} inputMode="numeric" isDisabled={busy} onChange={tools => update({ ...draft, tools })} />
                  {option.kind === 'goal' && <TextField label={'模型总份额（最多 ' + option.modelBudgetMaximum + ' 次）'} value={draft.models} inputMode="numeric" isDisabled={busy} onChange={models => update({ ...draft, models })} />}
                  <p>本次截止：{time(draft.deadline)}</p><div className="pw-form-actions">
                    {(view.humanInputOnly ? [1,5] : [15,60]).map(minutes => <Button key={minutes} variant="ghost" disabled={busy} onClick={() => update({ ...draft, deadline: String(Math.min(Date.now() + minutes * 60_000, Number(view.deadlineMaximum), Number(option.deadlineMaximum))) })}>从现在起 {minutes} 分钟内</Button>)}
                  </div>
                  {view.humanInputOnly ? <p>只接收你在原手控页的每次明确操作，不启动代理；换设备或屏幕必须重新准备和审阅。画面仅在本页短时显示，不自动刷新，不是实时视频。</p> : option.kind === 'goal' ? <p>只将本次目标交给对端已授权模型；对端仍核对本机策略，不继承本机账号或私人记忆。</p> : <p>由本任务模型调用对端明确工具。手机主屏与独立屏不互相兜底；电脑工具也不依赖 Codex 或 Claude 安装。</p>}
                  <Accordion>
                    <RouteMaterials kind="files" option={option} view={view} draft={draft} disabled={busy} update={update} />
                    <RouteMaterials kind="projects" option={option} view={view} draft={draft} disabled={busy} update={update} />
                    {rows(option.downstreamOptions).length > 0 && <AccordionItem id="shares" title="继续转交到其他设备" description={shareCount ? '已选 ' + shareCount + ' 个准确范围' : '默认不转派'}>
                      <p>对端本机与每个下游使用互不重复的份额，总和不得超过上方总份额。</p>
                      {rows(option.downstreamOptions).map(item => { const chosen = draft.shares[item.optionId]; return <div key={item.optionId}>
                        <CheckboxField isSelected={chosen?.selected || false} isDisabled={busy} onChange={selected => update({ ...draft, shares: { ...draft.shares, [item.optionId]: { ...chosen, selected } } })}>{deviceName(item.targetDeviceId, peers)} · {JSON.stringify(item.resourceBinding)}</CheckboxField>
                        {chosen?.selected && <><TextField label="此下游工具份额" value={chosen.tools} inputMode="numeric" isDisabled={busy} onChange={tools => update({ ...draft, shares: { ...draft.shares, [item.optionId]: { ...chosen, tools } } })} /><TextField label="此下游模型份额" value={chosen.models} inputMode="numeric" isDisabled={busy} onChange={models => update({ ...draft, shares: { ...draft.shares, [item.optionId]: { ...chosen, models } } })} /></>}
                      </div>; })}
                      {shareCount > 0 && <><TextField label="留给对端本机的工具份额" value={draft.localTools} inputMode="numeric" isDisabled={busy} onChange={localTools => update({ ...draft, localTools })} /><TextField label="留给对端本机的模型份额" value={draft.localModels} inputMode="numeric" isDisabled={busy} onChange={localModels => update({ ...draft, localModels })} /></>}
                    </AccordionItem>}
                  </Accordion>
                </>}
              </AccordionItem>;
            })}
          </Accordion></section>)}
          {rows(view.unavailableOptions).length > 0 && <Accordion><AccordionItem id="unavailable" title={`有 ${rows(view.unavailableOptions).length} 项范围暂不可用`}><p>这些范围没有替换成其他设备，也不阻止你选择已核对的设备。</p>{rows(view.unavailableOptions).map((item,index) => <p key={index}>{deviceName(item.targetDeviceId, peers)}：{item.reason === 'grant_unavailable' ? '原授权到期、撤销或无法核对' : item.reason === 'capability_read_unavailable' ? '连接或能力读取未完成，请核对对端接收服务与当前网络' : '设备身份或能力时效不匹配，需要重新读取'}</p>)}</AccordionItem></Accordion>}
          {!peerIds.length && <p>{view.humanInputOnly ? '没有可手控的手机范围。请先连接对端，并由对端主人授权主屏或独立屏、准确应用、全屏捕获与输入，再重新读取。不会改用电脑或模型代理。' : '未读取到可选择的设备范围。请在设备页连接对端，并由对端明确授权工具或目标范围，再回来重新读取。'}</p>}
          {view.hasMoreOptions && <p>还有未读取的授权记录。未显示的设备不会自动加入本任务。</p>}
          <div className="pw-form-actions"><Button disabled={busy || unavailable.length > 0 && !removeUnavailable} onClick={prepare}>核对这次范围修改</Button><Button variant="ghost" disabled={busy} onClick={read}>重新读取</Button>{onOpenDevices && <Button variant="ghost" disabled={busy} onClick={() => requestExit('devices')}>管理设备与连接</Button>}</div>
          {rows(view.pendingProjectImports).map(item => <div key={item.operationId || item.receiveOperationId}><p>原项目包等待独立导入确认</p><Button variant="secondary" disabled={busy} onClick={() => { void invoke('deviceTaskScope.reviewImport', { pageHandle: page.current, operationId: item.receiveOperationId || item.operationId }, acceptReview); }}>核对这份原项目包</Button></div>)}
        </>}
        {review && <>
          <h3>{review.goal || view?.goal}</h3><p>{review.kind === 'scope' ? '下面是原生解析后的准确选择。保存不发送目标、不开始任务，不为对端扩大授权。' : '确认只允许原任务导入这份已经接收的项目包；不会立即执行、覆盖已有项目或创建第二项任务。'}</p>
          {rows(review.routes).map(route => <div key={route.sourceKey}><ScopeFacts value={route} peers={peers} human={view?.humanInputOnly} />
            <p>上下文：仅本次目标（goal_only）；私人记忆与整份工作区不提供。</p>
            {['files','projects'].map(kind => rows(route[kind]).map(item => <p key={kind + item.id}>{kind === 'files' ? '原件' : '项目包'}：{item.name || item.id} → {item.path} · {item.size} 字节 · {item.expectedVersion === 'absent' ? '仅新建' : '覆盖已核对版本 ' + item.expectedVersion}<br />SHA-256：{item.sha256}</p>))}
            {rows(route.downstreamShares).length > 0 && <><p>对端本机：工具 {route.localToolShare} 次 · 模型 {route.localModelShare} 次</p>{rows(route.downstreamShares).map((item,index) => <p key={index}>下游 {deviceName(item.targetDeviceId, peers)} · {JSON.stringify(item.resourceBinding)} · 工具 {item.toolBudget} 次 · 模型 {item.modelBudget} 次</p>)}</>}
          </div>)}
          {review.kind === 'scope' && rows(review.routes).length === 0 && <p role="alert">本次将清空本任务的设备选择；不会取消已经发生的对端操作。</p>}
          {review.kind === 'import' && <><dl className="pw-facts"><div><dt>接收设备</dt><dd>{deviceName(review.targetDeviceId || '', peers)}</dd></div><div><dt>原项目包</dt><dd>{review.name || review.artifactId}</dd></div><div><dt>新副本路径</dt><dd>{review.path}</dd></div><div><dt>内容范围</dt><dd>{review.summary?.fileCount} 个文件 · {review.summary?.directoryCount} 个目录 · {review.summary?.bytes} 字节</dd></div></dl><p>仅导入新的副本，不覆盖现有项目，不恢复项目历史、运行环境或执行权限。确认后仍由原任务执行。</p><Accordion><AccordionItem id="import-exact" title="完整项目清单与原接收回执"><pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{JSON.stringify(review, null, 2)}</pre></AccordionItem></Accordion></>}
          <CheckboxField isSelected={ack} isDisabled={busy} onChange={setAck}>{review.kind === 'import' ? '我已核对接收设备、原项目包、准确的新副本路径与完整清单' : '我已核对接收设备、目标、准确资源、预算期限，以及上方明确选择的文件和项目路径'}</CheckboxField>
          <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={dismissReview}>返回修改</Button><Button disabled={busy || !ack} onClick={confirm}>确认保存一次</Button></div>
        </>}
        {!review && <Button variant="ghost" disabled={busy} onClick={() => requestExit('close')}>返回原任务</Button>}
      </div>
    </SettingsDialog>
    <SettingsDialog isOpen={!!discard} title="保留还是放弃当前选择？" onClose={() => setDiscard(null)}>
      <div className="pw-collaboration-dialog"><p>当前设备、份额或文件路径有未保存修改。返回编辑可以继续核对；放弃不会更改已经保存的原任务范围。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setDiscard(null)}>保留并继续编辑</Button><Button onClick={() => { if (discard) exit(discard); }}>放弃这些修改{discard === 'reload' ? '并重新读取' : ''}</Button></div></div>
    </SettingsDialog>
  </section>;
}
