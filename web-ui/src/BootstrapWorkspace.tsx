import { useEffect, useRef, useState } from 'react';
import { RiArrowRightLine, RiRefreshLine } from '@remixicon/react';
import { bridge, type PocketSnapshot } from './bridge';
import { usePocketAppearance } from './usePocketAppearance';
import { Accordion, AccordionItem, Button, CheckboxField, ObjectTable, SettingsDialog } from './forms-library';
import { Spinner } from './forms-library/boardui/components/base/spinner/spinner';
import { bootstrapView, bootstrapRetryPayload, currentBootstrapView, retainedBootstrapOperation, captureRepairReview, repairAllowed, repairModeAllowed, repairConsistencyText, repairConfirmPayload, repairOutcome, repairRestoration, repairReviewPayload, repairReviewCurrent,
  type BootstrapOperation, type BootstrapView, type RepairFile, type RepairGap, type RepairMode, type RepairRestoration, type RepairReview } from './bootstrap-contract';
import './bootstrap.css';
import './browser-depth.css';

const moduleLabels: Record<string, string> = { memory: '记忆', assistant: '对话', workspace: '工作区', display: '屏幕', phone_setup: '手机设置', notifications: '通知', automation: '提醒与跟进', remote: '远程连接', channels: '消息渠道', teams: '团队', plugins: '插件', local: '本地工具', skills: '技能', attachments: '附件', media: '媒体', realtime: '语音', ssh: 'SSH 连接', proxy: '账号连接', browser: '浏览器', semantic: '界面理解', groups: '群组' };
const activities: Record<string, string> = { installation: '检查安装', storage: '检查存储', ui_resources: '核验界面资源', opening_store: '打开本机数据', models: '准备模型连接', features: '准备工作能力', devices: '准备设备连接', attachments_recovery: '核对附件状态', channels_recovery: '核对渠道任务', source_sync: '核对内容来源', final_checks: '完成启动检查', ready: '启动已完成' };
const stages = { configured: '检查安装与配置', data_opened: '打开本机记录', migrated: '核对数据格式', features_ready: '准备工作能力' };
const recoveries = { not_started: '尚未开始核对原任务', running: '正在核对原任务与原回执', completed: '原任务核对已完成', failed: '原任务核对尚未完成' };
const phaseLabels: Record<string, string> = { not_started: '尚未保全原件', detected: '已识别原件', inspecting: '核对原件', preserving: '正在保全原件', preserved: '原件副本已核验', preservation_failed: '保全尚未完成', quiescence_unconfirmed: '尚未确认原资源已静止', reviewing: '核验所选隔离方案', review_failed: '所选方案尚未通过核验', staging: '准备隔离暂存', staged: '隔离暂存已核验', reviewed: '等待本次确认', switching: '正在切换本机数据', verifying: '核对本次切换结果', completed: '本次维修已完成', unknown: '操作结果待核对', original_retry_prepared: '原库重新打开已登记', original_retry_completed: '原库重新核对已完成' };
const newRequest = () => crypto.randomUUID();
const fileSize = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? `${value.toLocaleString('zh-CN')} 字节` : '尚未取得';
const modeLabel = (mode: RepairMode) => mode === 'verified_copy' ? '恢复对话文字' : '建立空库';
function RestorationSummary({ value }: { value: RepairRestoration }) {
  const gapReasons: Record<string, string> = { deleted_or_unavailable: '原消息已删除或不可用', unsupported_historical_record_role: '原记录不属于可恢复的对话角色', attachment_bytes_and_grants_not_exported: '附件原件及授权不恢复', external_source_grants_not_exported: '外部来源授权不恢复', message_deleted: '原消息已删除', source_deleted: '原来源已删除', role_not_exportable: '不属于可导出的对话文字', role_not_conversation_content: '不属于可导出的对话文字', source_integrity_unavailable: '原内容完整性未确认', source_revision_or_role_unavailable: '原记录版本或角色无法核对', message_read_limited: '原记录读取受限', conversation_unreadable: '原对话无法完整读取', source_conversation_corrupt: '原对话数据损坏，未能读取', source_conversation_read_limited: '原对话读取受限', source_conversation_validation_failed: '原对话完整性核对未通过', source_conversation_unreadable: '原对话无法完整读取', retired_for_derived_context: '原记录已退出派生上下文' };
  return <section className="pw-bootstrap-restoration" aria-label="本次对话文字恢复范围">
    <h3>{value.partial ? '本次可恢复部分对话文字' : '本次仅恢复对话文字'}</h3>
    <dl className="pw-bootstrap-facts"><div><dt>对话 / 消息</dt><dd>{value.conversationCount} 段 / {value.messageCount} 条</dd></div><div><dt>明确省略</dt><dd>{value.omittedCount} 条原记录</dd></div><div><dt>未能读取</dt><dd>{value.unreadableConversationCount} 段对话</dd></div><div><dt>内容包大小</dt><dd>{fileSize(value.contentUtf8Bytes)}</dd></div><div><dt>内容身份</dt><dd>以新对话身份导入，原对话 ID 不继承</dd></div></dl>
    <p>{value.omissionMeaning}</p><p>原任务历史、附件原件及授权、密钥和设备配对不恢复；旧运行与执行授权保持停用。</p>
    {value.gaps.length > 0 && <Accordion><AccordionItem title={`查看 ${value.gaps.length} 项遗漏说明`}><ObjectTable<RepairGap> label="本次恢复遗漏清单" rows={value.gaps} getRowId={gap => `${gap.sourceConversationId}:${gap.reason}`} empty="没有已记录的遗漏" columns={[
      { id: 'source', label: '原对话', render: gap => <span className="pw-bootstrap-code">{gap.sourceConversationId}</span> },
      { id: 'reason', label: '遗漏原因', render: gap => gapReasons[gap.reason] || `原记录未恢复（${gap.reason}）` },
      { id: 'count', label: '记录数', render: gap => gap.recordCount === null ? '无法确认' : gap.recordCount },
    ]} /></AccordionItem></Accordion>}
  </section>;
}

/** Existing BoardUI controls, with the same local host receipt rules as the normal owner workspaces. */
export default function BootstrapWorkspace({ snapshot }: { snapshot: PocketSnapshot }) {
  usePocketAppearance(snapshot.host?.appearance);
  const native = bootstrapView(snapshot.bootstrap);
  const [received, setReceived] = useState<BootstrapView | null>(null);
  const view = currentBootstrapView(native, received);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [review, setReview] = useState<RepairReview | null>(null), [checked, setChecked] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [includeContent, setIncludeContent] = useState(false);
  const [localOperation, setLocalOperation] = useState<BootstrapOperation | null>(null);
  const request = useRef(0), pending = useRef<BootstrapOperation | null>(null);
  const [queryReady, setQueryReady] = useState(false);
  const savedOperation = snapshot.bootstrapOperation as BootstrapOperation | undefined;
  // A native receipt may learn the repair ID after the UI has hidden. Keep that
  // exact identity while preserving the UI's unresolved outcome.
  const operation = retainedBootstrapOperation(localOperation, savedOperation);
  const unknown = operation?.status === 'unknown';
  const supported = (action: string) => snapshot.capabilities.commands.includes(action);
  const connected = snapshot.host?.ready === true && snapshot.host?.bridgeAvailable !== false;
  const unavailable = typeof snapshot.host?.bridgeUnavailableReason === 'string' ? snapshot.host.bridgeUnavailableReason : '';
  const readFailure = typeof snapshot.host?.bootstrapReadError === 'string' ? snapshot.host.bootstrapReadError : '';
  const locked = busy || unknown || operation?.status === 'pending' || !connected || Boolean(readFailure);
  const maintenance = view?.maintenance;
  const outcome = repairOutcome(view);
  const restored = maintenance?.result?.mode === 'verified_copy' ? repairRestoration(maintenance.result.restoration, maintenance.backupFingerprint) : null;

  useEffect(() => {
    let epoch = bridge.getDocumentEpoch();
    const invalidate = () => {
      request.current++;
      if (pending.current) setLocalOperation({ ...pending.current, status: 'unknown' });
      pending.current = null; setBusy(false); setReview(null); setReviewOpen(false); setChecked(false); setIncludeContent(false); setQueryReady(false); setReceived(null);
    };
    const unsubscribe = bridge.subscribe(() => { const next = bridge.getDocumentEpoch(); if (next !== epoch) { epoch = next; invalidate(); } });
    const hide = () => { if (document.visibilityState !== 'visible') invalidate(); };
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', invalidate);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', invalidate); };
  }, []);
  useEffect(() => { if (review && !repairReviewCurrent(view, review)) { setReview(null); setReviewOpen(false); setChecked(false); } }, [view, review]);
  useEffect(() => { setIncludeContent(false); }, [maintenance?.repairId, maintenance?.backupFingerprint]);
  useEffect(() => { document.documentElement.dataset.pocketUi = connected ? 'maintenance' : 'connecting'; }, [connected]);
  useEffect(() => bridge.setBackHandler(() => { if (!reviewOpen) return false; setReviewOpen(false); setChecked(false); return true; }), [reviewOpen]);

  function markReceived(action: string, args: Record<string, unknown>, outcome: 'success' | 'not_sent') {
    // A display acknowledgement follows an actually received current-window
    // result. It cannot acknowledge an unknown result or replay the native action.
    void bridge.command('bootstrap.received', { requestId: args.requestId, command: action, outcome }).catch(() => {});
  }
  async function run(action: string, args: Record<string, unknown>, mutating = false): Promise<BootstrapView | null> {
    const ticket = ++request.current, epoch = bridge.getDocumentEpoch();
    setBusy(true); setError('');
    if (mutating) {
      const original: BootstrapOperation = { command: action, requestId: String(args.requestId), generation: Number(args.expectedGeneration), repairId: String(args.repairId || ''), status: 'pending' };
      pending.current = original; setLocalOperation(original); setQueryReady(false);
    }
    try {
      const result = bootstrapView(await bridge.command(action, args));
      if (ticket !== request.current || epoch !== bridge.getDocumentEpoch()) return null;
      if (!result) throw new Error('没有取得完整的原生状态，请保留原请求并只读核对。');
      setReceived(result);
      if (mutating) { setLocalOperation(null); pending.current = null; markReceived(action, args, 'success'); }
      return result;
    } catch (failure) {
      if (ticket !== request.current || epoch !== bridge.getDocumentEpoch()) return null;
      const notSent = failure && typeof failure === 'object' && 'code' in failure && failure.code === 'not_sent';
      if (mutating && pending.current) setLocalOperation(notSent ? null : { ...pending.current, status: 'unknown' });
      if (mutating && notSent) markReceived(action, args, 'not_sent');
      pending.current = null;
      setError(failure instanceof Error ? failure.message : '操作结果尚未确认，请核对原请求。');
      return null;
    } finally { if (ticket === request.current) setBusy(false); }
  }
  async function readOriginal() {
    const id = (unknown ? operation?.repairId : '') || maintenance?.repairId || '';
    const result = await run(id ? 'repair.read' : 'bootstrap.read', id ? { repairId: id } : {});
    if (result && unknown) setQueryReady(true);
  }
  async function acknowledge() {
    const queryId = bridge.getState().bootstrapQueryId;
    if (!operation || !queryReady || typeof queryId !== 'string') { setError('尚未取得原请求的只读核对回执，请再次读取现状。'); return; }
    const result = await run('bootstrap.acknowledge', { requestId: operation.requestId, queryId });
    if (result) { setLocalOperation(null); setQueryReady(false); setError(''); }
  }
  async function openReview(mode: RepairMode) {
    if (!view) return;
    try {
      const result = await run('repair.review', repairReviewPayload(view, mode, newRequest(), includeContent));
      if (result) { setReview(captureRepairReview(result, mode)); setReviewOpen(true); setChecked(false); }
    } catch (failure) { setError(failure instanceof Error ? failure.message : '本次维修暂不可审阅。'); }
  }
  async function retryBootstrap() {
    if (!view) return;
    try { await run('bootstrap.retry', bootstrapRetryPayload(view, newRequest()), true); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '原启动身份已变化，请重新读取。'); }
  }
  async function confirm() {
    if (!review) return;
    try {
      const payload = repairConfirmPayload(view, review, checked, newRequest());
      setReview(null); setReviewOpen(false); setChecked(false);
      await run('repair.confirm', payload, true);
    } catch (failure) { setError(failure instanceof Error ? failure.message : '原审阅已变化，请重新读取。'); }
  }
  const preparationFailed = view?.state === 'failed' && view.reason?.code === 'initialization_failed' && !maintenance?.repairId;
  let title = preparationFailed ? '启动准备未完成' : '需要处理启动问题';
  if (unavailable) title = '当前界面无法安全通信';
  else if (readFailure) title = '本机启动状态暂时无法读取';
  else if (outcome && restored) title = restored.messageCount > 0 ? '原件已保全，对话文字已恢复' : '原件已保全，本次未恢复文字消息';
  else if (outcome) title = maintenance?.result?.mode === 'empty_database' ? '原件已保全，新空库已建立' : '核对本次维修结果';
  else if (!view) title = '正在连接本机启动状态';
  else if (view.state === 'initializing') title = '正在准备 PocketWorld';
  else if (view.state === 'ready') title = unknown ? '启动已完成，先核对原回执' : '本机数据已就绪';
  else if (maintenance?.repairId) title = '核对本机数据与保全副本';
  return <main className="pw-bootstrap" aria-label="启动与数据维护" data-bootstrap-state={view?.state || 'connecting'}>
    <header className="pw-bootstrap-header"><span className="pw-bootstrap-brand">PocketWorld</span><span className="pw-bootstrap-context">启动与数据维护</span></header>
    <section className="pw-bootstrap-summary" aria-live="polite"><p className="pw-bootstrap-eyebrow">{view?.state === 'initializing' ? '本机启动' : maintenance ? '保留原件，再决定下一步' : '启动状态'}</p>
      <div className="pw-bootstrap-heading">{view?.state === 'initializing' && <Spinner />}<h1>{title}</h1></div>
      {outcome ? <p>{outcome}</p> : preparationFailed ? <p>启动尚未完成，暂时无法进入工作区。请保留当前安装，展开下方详情查看具体失败位置。</p> : <p>{view?.reason?.message || (view?.state === 'initializing' ? '正在核对本机安装、数据和原任务。期间不会接收新的模型、工具或渠道执行。' : '正在读取原宿主状态，不会另建数据库或重放旧操作。')}</p>}
      {view?.reason?.nextStep && <p className="pw-bootstrap-next">{preparationFailed ? '可在系统设置中强行停止 PocketWorld 后重新打开一次；若仍失败，请保留诊断详情。不要清除应用数据或建立空库。' : view.reason.nextStep}</p>}
      {unavailable && <p className="pw-field-error" role="alert">{unavailable}</p>}
      {readFailure && <p className="pw-field-error" role="alert">{readFailure}</p>}
      {view && <dl className="pw-bootstrap-facts"><div><dt>当前阶段</dt><dd>{activities[view.activity || ''] || stages[view.stage]}{view.module && ` · ${moduleLabels[view.module] || '组件'}`}</dd></div><div><dt>{preparationFailed ? '启动检查' : '任务状态核对'}</dt><dd>{preparationFailed && view.recovery === 'failed' ? '尚未完成' : recoveries[view.recovery]}</dd></div></dl>}
      {outcome && restored && <RestorationSummary value={restored} />}
      <div className="pw-form-actions">
        {view?.retryAvailable && supported('bootstrap.retry') && <Button disabled={locked || view.state === 'initializing'} onClick={retryBootstrap} leadingIcon={RiRefreshLine}>{maintenance?.originalRetryAvailable ? '重新核对原数据库' : '重新核对并启动'}</Button>}
        {repairAllowed(view, 'repair.preserve') && supported('repair.preserve') && <Button disabled={locked} onClick={() => run('repair.preserve', { expectedGeneration: view!.generation, requestId: newRequest(), repairId: maintenance?.repairId ?? null }, true)}>先保全原件</Button>}
        <Button variant="secondary" disabled={!connected || busy || !supported('bootstrap.read')} onClick={readOriginal}>{busy ? '正在读取原回执…' : unknown ? '只读核对原操作' : '读取当前状态'}</Button>
      </div>
      {view?.state === 'initializing' && <p className="pw-muted">初始化仍在进行时不会重复启动。用时较长不表示失败。</p>}
      {error && <p className="pw-field-error" role="alert">{error}</p>}
    </section>

    {unknown && operation && <section className="pw-bootstrap-receipt" aria-label="原维修操作回执待核对"><h2>原操作回执尚未确认</h2><p>已保留原请求。切到后台、关闭页面或等待超时，都不能证明操作没有发生；这里不会自动重发。</p><dl className="pw-bootstrap-facts"><div><dt>原请求</dt><dd className="pw-bootstrap-code">{operation.requestId}</dd></div>{operation.repairId && <div><dt>原维修记录</dt><dd className="pw-bootstrap-code">{operation.repairId}</dd></div>}</dl><Button variant="secondary" disabled={busy || !queryReady || !connected} onClick={acknowledge}>我已核对现状，继续审阅</Button><p className="pw-muted">这只关闭当前提示。可执行的下一步仍由原生维护记录决定。</p></section>}

    {maintenance && <section className="pw-bootstrap-maintenance" aria-label="原件保全与维修">
      <h2>原件保全与维修</h2>
      <p>{maintenance.reason?.message || '保全副本只保存原件；它不证明历史已恢复，也不会继续原任务。'}</p>
      {maintenance.reason?.nextStep && <p className="pw-bootstrap-next">{maintenance.reason.nextStep}</p>}
      <dl className="pw-bootstrap-facts"><div><dt>保全校验</dt><dd>{maintenance.preservationVerified ? '已通过本次原生校验' : '尚未确认，不能替换'}</dd></div><div><dt>当前记录</dt><dd>{phaseLabels[maintenance.phase] || '请按原记录的下一步核对'}</dd></div></dl>
      {repairModeAllowed(view, 'verified_copy') && <CheckboxField isSelected={includeContent} isDisabled={locked} onChange={setIncludeContent}>包含原对话文字，在本机读取并准备恢复审阅</CheckboxField>}
      {repairAllowed(view, 'repair.review') && <div className="pw-form-actions">
        {repairModeAllowed(view, 'verified_copy') && <Button disabled={locked || !includeContent || !maintenance.preservationVerified || !supported('repair.review')} onClick={() => openReview('verified_copy')} trailingIcon={RiArrowRightLine}>审阅恢复对话文字</Button>}
        {repairModeAllowed(view, 'empty_database') && <Button variant="secondary" disabled={locked || !maintenance.preservationVerified || !supported('repair.review')} onClick={() => openReview('empty_database')}>审阅建立空库</Button>}
      </div>}
      {repairModeAllowed(view, 'verified_copy') && <p className="pw-muted">先在隔离副本中核验所选对话文字。超过当前恢复上限或校验失败时，原件与保全副本仍保留，不会自动改为建立空库。</p>}
      {review && !reviewOpen && repairReviewCurrent(view, review) && <div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => { setChecked(false); setReviewOpen(true); }}>继续核对本次{modeLabel(review.mode)}</Button></div>}
      {maintenance.repairId && <Accordion><AccordionItem title="核对本次维修身份与文件">
        <dl className="pw-bootstrap-facts"><div><dt>维修编号</dt><dd className="pw-bootstrap-code">{maintenance.repairId}</dd></div><div><dt>原件指纹</dt><dd className="pw-bootstrap-code">{maintenance.sourceFingerprint || '尚未取得'}</dd></div><div><dt>保全指纹</dt><dd className="pw-bootstrap-code">{maintenance.backupFingerprint || '尚未取得'}</dd></div><div><dt>一致性前提</dt><dd>{repairConsistencyText(maintenance.consistency)}</dd></div><div><dt>原生阶段记录</dt><dd>{maintenance.phase}</dd></div></dl>
        <ObjectTable<RepairFile> label="原数据库文件集合" rows={Array.isArray(maintenance.files) ? maintenance.files : []} getRowId={file => file.name} empty="尚未取得原文件集合" columns={[
          { id: 'file', label: '原件', render: file => <span className="pw-bootstrap-code">{file.name}</span> },
          { id: 'length', label: '长度', render: file => file.exists === false ? '原集合不存在' : fileSize(file.length) },
          { id: 'checked', label: '校验', render: file => file.verified === true ? '副本已核验' : '尚未确认' },
        ]} />
        {(maintenance.files || []).filter(file => file.exists !== false).map(file => <dl className="pw-bootstrap-file-proof" key={file.name}><dt>{file.name} · 原件 SHA-256</dt><dd className="pw-bootstrap-code">{file.sha256 || '尚未取得'}</dd><dt>副本 SHA-256 / 长度</dt><dd className="pw-bootstrap-code">{file.copiedSha256 || '尚未取得'}<br />{fileSize(file.copiedLength)}</dd></dl>)}
        {Array.isArray(maintenance.originalDirectoryReferences) && maintenance.originalDirectoryReferences.length > 0 && <div className="pw-bootstrap-directory-proof"><h3>相关原件目录引用</h3><p className="pw-muted">以下只保留原目录引用，不表示目录内容已随数据库恢复。</p>{maintenance.originalDirectoryReferences.map(reference => <p className="pw-bootstrap-code" key={reference}>{reference}</p>)}</div>}
        {maintenance.reason?.code && <p className="pw-muted">原维护原因编号：{maintenance.reason.code}</p>}
        {repairAllowed(view, 'repair.discard') && <Button variant="ghost" disabled={locked || !supported('repair.discard')} onClick={() => run('repair.discard', { expectedGeneration: view!.generation, requestId: newRequest(), repairId: maintenance.repairId }, true)}>取消本次审阅（保全原件保留）</Button>}
      </AccordionItem></Accordion>}
    </section>}

    <Accordion><AccordionItem title="数据恢复与备份的区别"><div className="pw-bootstrap-explanations"><div><h3>保全损坏原件</h3><p>保存实际存在的数据库、WAL、SHM 与 journal 等原文件，并逐项核对长度与 SHA。校验未完成时，替换入口保持关闭。</p></div><div><h3>从已核副本恢复</h3><p>当前支持范围是经过只读核验的对话文字，以新对话身份导入隔离暂存。审阅会列出真实数量与省略项；任务历史、附件原件及授权、密钥和配对不迁入。未通过核验时保留原件，不自动改为空库。</p></div><div><h3>建立空库</h3><p>只有本次原生允许且完成二次审阅后才可进行。原历史未恢复；执行保持停用，不继承旧运行、租约、授权或设备配对。</p></div><div><h3>可移植分区备份</h3><p>本机数据恢复可用后，可在正常工作台的“备份与迁移”审阅所选分区。当前数据库不可用时，此处不能导出这些分区；分区恢复也不代表恢复全 App。</p></div></div></AccordionItem>
      <AccordionItem title="安装、数据格式与能力详情"><dl className="pw-bootstrap-facts"><div><dt>安装版本</dt><dd>{view?.installedVersion || '尚未取得'}</dd></div><div><dt>需要的数据格式</dt><dd>{view?.requiredSchema ?? '尚未取得'}</dd></div><div><dt>检测到的数据格式</dt><dd>{view?.observedSchema ?? '尚未取得'}</dd></div><div><dt>启动代次</dt><dd>{view?.generation ?? '尚未取得'}</dd></div>{view?.reason?.code && <div><dt>原因编号</dt><dd className="pw-bootstrap-code">{view.reason.code}</dd></div>}</dl>{(view?.optionalUnavailable || []).map((item, index) => <p key={`${item.capability}:${index}`}>{item.capability}：{item.reason}</p>)}{(view?.diagnostics?.causes || []).slice(0, 4).map((cause, index) => <div key={index}><p className="pw-bootstrap-code">{cause.type}</p>{(cause.frames || []).slice(0, 5).map((frame, frameIndex) => <p className="pw-bootstrap-code" key={frameIndex}>{frame.class}.{frame.method}:{frame.line}</p>)}</div>)}<p className="pw-muted">可选能力不可用只影响对应功能。诊断仅含程序位置，不含错误消息或文件路径，也不会自动外发。</p></AccordionItem>
    </Accordion>

    <SettingsDialog isOpen={review !== null && reviewOpen} title={review?.mode === 'verified_copy' ? '核对本次对话文字恢复' : '核对原件保全后，建立空库？'} onClose={() => { setReviewOpen(false); setChecked(false); }}>
      {review && <div className="pw-bootstrap-review">{review.restoration ? <><p>保全原件将继续保留。请核对本次可恢复的内容与遗漏，再决定是否替换本机数据库。</p><RestorationSummary value={review.restoration} /></> : <><p>{review.summary}</p><p>所有保全副本保留；旧任务、执行租约、授权和配对不会进入可执行状态。</p></>}<Accordion><AccordionItem title="查看原件与本次审阅身份"><dl className="pw-bootstrap-facts"><div><dt>维修编号</dt><dd className="pw-bootstrap-code">{review.repairId}</dd></div><div><dt>原件指纹</dt><dd className="pw-bootstrap-code">{review.sourceFingerprint}</dd></div><div><dt>已核保全指纹</dt><dd className="pw-bootstrap-code">{review.backupFingerprint}</dd></div></dl></AccordionItem></Accordion><CheckboxField isSelected={checked} onChange={setChecked}>{review.mode === 'verified_copy' ? '我已核对所选对话文字、明确省略和原件保全身份，理解这不会恢复原任务历史或执行授权。' : '我已核对本次原件与保全身份，理解建立空库不代表历史恢复。'}</CheckboxField><div className="pw-form-actions"><Button variant="secondary" onClick={() => { setReviewOpen(false); setChecked(false); }}>保留现状</Button><Button variant="danger" disabled={locked || !checked || !repairReviewCurrent(view, review)} onClick={confirm}>确认本次{modeLabel(review.mode)}</Button></div></div>}
    </SettingsDialog>
  </main>;
}
