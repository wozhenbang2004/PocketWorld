import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog } from './forms-library';
import { TaskCapabilityConsent, taskCapabilityArtifactStatus, taskCapabilityDecision } from './task-capabilities';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const names = (value: any) => Array.isArray(value) ? value.join('、') || '无' : '尚未取得';

export function TaskCapabilityWorkspace({ taskId, run, exportState, onBackChange }: { taskId: string; run: Run; exportState?: Data | null; onBackChange?: (handler: (() => boolean) | null) => void }) {
  const [expanded, setExpanded] = useState(false), [view, setView] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null);
  const [script, setScript] = useState<Data | null>(null), [file, setFile] = useState<Data | null>(null), [busy, setBusy] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const generation = useRef(0), alive = useRef(true), working = useRef(false), page = useRef(''), reviewId = useRef('');
  const consent = useRef(new TaskCapabilityConsent()), shown = useRef<Data | null>(null); shown.current = review;
  const version = generation.current, confirmTicket = consent.current.capture();
  const closeReview = () => { const handle = reviewId.current; reviewId.current = ''; consent.current.clear(); shown.current = null; setReview(null); setScript(null); setAcknowledged(false); if (handle) void run('taskCapabilities.dismissReview', { reviewHandle: handle }).catch(() => {}); };
  const close = () => { generation.current++; working.current = false; closeReview(); const handle = page.current; page.current = ''; if (handle) void run('taskCapabilities.close', { pageHandle: handle }).catch(() => {}); setView(null); setFile(null); setBusy(false); };
  const invoke = async (action: string, args: Data, accept?: (value: Data) => void) => {
    if (!alive.current || version !== generation.current || working.current) return;
    const ticket = generation.current; working.current = true; setBusy(true); setError('');
    try { const value = await run(action, args); if (alive.current && ticket === generation.current) { accept?.(value); return value; }
      if (value?.pageHandle) void run('taskCapabilities.close', { pageHandle: value.pageHandle }).catch(() => {});
      if (value?.reviewHandle) void run('taskCapabilities.dismissReview', { reviewHandle: value.reviewHandle }).catch(() => {});
    } catch (failure) { if (alive.current && ticket === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (ticket === generation.current) { working.current = false; if (alive.current) setBusy(false); } }
  };
  const acceptView = (value: Data) => { page.current = value.pageHandle; setView(value); setFile(null); };
  const load = () => { if (version !== generation.current) return; void invoke(page.current ? 'taskCapabilities.refresh' : 'taskCapabilities.open', page.current ? { pageHandle: page.current } : { taskId }, acceptView); };
  const showReview = (value: Data) => { reviewId.current = value.reviewHandle; consent.current.show(value.reviewHandle); setReview(value); setScript(null); setAcknowledged(false); };
  const acknowledge = (accepted: boolean) => {
    if (!review || review !== shown.current || working.current) return;
    consent.current.change(accepted); const ticket = consent.current.capture(); setAcknowledged(false);
    void invoke('taskCapabilities.consent', { reviewHandle: review.reviewHandle, accepted }, value => { if (consent.current.current(ticket)) setAcknowledged(value.accepted === true); });
  };
  const decide = (approved: boolean) => {
    if (!review || review !== shown.current || !consent.current.confirm(confirmTicket, approved) || working.current) return;
    void invoke(review.kind === 'script' ? 'taskCapabilities.decide' : 'taskCapabilities.mcp.confirm', review.kind === 'script' ? { reviewHandle: review.reviewHandle, approved } : { reviewHandle: review.reviewHandle }, value => {
      if (!consent.current.current(confirmTicket)) return;
      setNotice(review.kind === 'script' ? taskCapabilityDecision(value) : value.saved === true ? '这项原安装的设置已保存，没有调用工具。' : '原安装设置尚未确认。');
      if (value.view) acceptView(value.view); closeReview();
    });
  };
  const original = (action: string, operation: Data) => { if (!page.current || version !== generation.current) return; void invoke(action, { pageHandle: page.current, operationHandle: operation.operationHandle }, value => { acceptView(value.view); setNotice(value.retrieved === true ? '已按原回执取回成果，没有重新提交脚本。' : '已核对原操作，没有重新提交。'); }); };
  const exported = exportState?.taskId === taskId ? exportState : null;
  useEffect(() => { alive.current = true; return () => { alive.current = false; close(); }; }, [taskId]);
  useEffect(() => { const hidden = () => { if (document.visibilityState !== 'visible') close(); }; document.addEventListener('visibilitychange', hidden); return () => document.removeEventListener('visibilitychange', hidden); }, []);
  useEffect(() => { onBackChange?.(() => { if (reviewId.current) { closeReview(); return true; } if (expanded) { close(); setExpanded(false); return true; } return false; }); return () => onBackChange?.(null); }, [expanded, onBackChange]);

  return <section className="pw-task-capabilities"><div className="pw-section-heading"><div><h3>任务脚本与所需工具</h3><p className="pw-muted">查看本次任务的准确计划、原执行回执和成果。</p></div><Button variant="secondary" disabled={busy} onClick={() => { if (expanded) { close(); setExpanded(false); } else { setExpanded(true); load(); } }}>{expanded ? '收起' : '查看'}</Button></div>
    {expanded && <><div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={load}>刷新原任务能力</Button>{view?.reviewPending && <Button disabled={busy} onClick={() => void invoke('taskCapabilities.review', { pageHandle: page.current }, showReview)}>审阅本次执行</Button>}</div>
      {view?.reviewPending && <p>当前任务正在等你审阅脚本。打开此页不会批准执行。</p>}{view?.capabilityPreparationMessage && <p>{view.capabilityPreparationMessage}</p>}
      {rows(view?.executions).map(operation => <section key={operation.operationId}><h4>原任务脚本回执</h4><p>{operation.receipt?.status || operation.state || '等待原回执'} · {taskCapabilityArtifactStatus(operation)}</p><p className="pw-field-hint">原操作 {operation.operationId}</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => original('taskCapabilities.receipt', operation)}>核对原回执</Button>{operation.artifactAvailability?.canRecheck === true && <Button variant="secondary" disabled={busy} onClick={() => original('taskCapabilities.retrieve', operation)}>取回原文件成果</Button>}</div>{operation.receipt?.output && <pre className="pw-source-code">{String(operation.receipt.output)}</pre>}{rows(operation.receipt?.artifacts).map(artifact => <div key={artifact.artifactId}><p>{artifact.path} · {artifact.bytes} 字节</p><Button variant="ghost" disabled={busy} onClick={() => void invoke('taskCapabilities.artifact', { pageHandle: page.current, artifactHandle: artifact.artifactHandle }, value => setFile(value))}>读取原成果</Button><Button variant="secondary" disabled={busy || !!exported && ['waiting', 'selected', 'writing'].includes(exported.phase)} onClick={() => void invoke('taskCapabilities.export', { pageHandle: page.current, artifactHandle: artifact.artifactHandle }, () => setNotice('系统保存请求已发出，请完成或取消原文件选择。'))}>导出原成果</Button></div>)}</section>)}
      {!!file && <section><h4>{file.path}</h4><p className="pw-field-hint" style={{ overflowWrap: 'anywhere' }}>SHA-256：{file.sha256}</p><p>{file.bytes} 字节。文件内容来自原脚本，尚未导入项目或执行。</p></section>}
      {rows(view?.pluginNeeds).map(need => <section key={need.needHandle}><h4>{need.pluginId} · {need.tool}</h4>{need.installation ? <><p>复用已安装的服务 · {need.available ? '当前可用' : '按原安装核对所需工具'}</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => void invoke('taskCapabilities.mcp.review', { pageHandle: page.current, needHandle: need.needHandle, change: 'grant', value: !(need.installation.grants || []).includes(`tool:${need.tool}`) }, showReview)}>{(need.installation.grants || []).includes(`tool:${need.tool}`) ? '审阅并撤销这个工具' : '审阅并允许这个工具'}</Button><Button variant="ghost" disabled={busy} onClick={() => void invoke('taskCapabilities.mcp.review', { pageHandle: page.current, needHandle: need.needHandle, change: 'enabled', value: need.installation.enabled !== true }, showReview)}>{need.installation.enabled ? '审阅并停用这个安装' : '审阅并启用这个安装'}</Button></div></> : <p>这项服务尚未安装。请先到工具与服务页核对所需配置。</p>}</section>)}
      {view && !view.reviewPending && !rows(view.executions).length && !rows(view.pluginNeeds).length && <p className="pw-muted">当前没有待审阅脚本、原执行记录或缺少的工具。</p>}
    </>}{error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{exported && <p role="status">{exported.error || (exported.phase === "exported" && exported.receipt?.status === "exported" ? `已按原 SHA-256 导出 ${exported.receipt.bytes} 字节，请在文件应用中查看。` : exported.phase === "cancelled" ? "原保存选择已取消，没有写入其他位置。" : exported.phase === "writing" ? "正在写入所选的原文件位置…" : "等待原系统保存选择；尚未确认文件已导出。")}</p>}
    <SettingsDialog isOpen={!!review} onClose={closeReview} title={review?.kind === 'script' ? '审阅本次脚本执行' : '审阅任务所需工具'}>{review && <>
      {review.kind === 'script' ? <><p>本次批准只属于当前原任务、脚本版本、执行地点和参数。脚本内容与结果均需独立核对。</p><dl className="pw-facts"><div><dt>技能</dt><dd>{review.package?.name || review.package?.id} · 修订 {review.package?.revision}</dd></div><div><dt>脚本</dt><dd>{review.request?.scriptPath}</dd></div><div><dt>执行地点</dt><dd>{review.executionLocation === 'this_phone' ? '这台手机的原托管环境' : '原配置的执行端'}</dd></div><div><dt>最长时间</dt><dd>{review.request?.timeoutSeconds} 秒</dd></div><div><dt>读取范围</dt><dd>{names(review.request?.readPaths)}</dd></div><div><dt>写入范围</dt><dd>{names(review.request?.writePaths)}</dd></div><div><dt>成果范围</dt><dd>{names(review.request?.outputPaths)}</dd></div><div><dt>到期时间</dt><dd>{typeof review.expiresAt === 'number' ? new Date(review.expiresAt).toLocaleString() : '尚未取得'}</dd></div></dl><Accordion><AccordionItem title="准确参数与内容标识"><pre className="pw-source-code">{JSON.stringify({ argv: review.argv, cwd: review.cwd, planDigest: review.planDigest, scriptSha256: review.scriptSha256, resources: review.resources, request: review.request, backend: review.backend }, null, 2)}</pre></AccordionItem></Accordion><Button variant="secondary" disabled={busy} onClick={() => void invoke('taskCapabilities.script', { reviewHandle: review.reviewHandle }, value => { if (shown.current === review) setScript(value); })}>读取当前版本的脚本内容</Button>{script && <pre className="pw-source-code">{script.content}</pre>}</> : <><p>{review.pluginId} · {review.tool?.name}</p><p>{review.change === 'grant' ? review.value ? '只允许原安装的这一个工具。实际任务调用仍走原任务授权。' : '撤销原安装的这一个工具。' : review.value ? '启用此原安装；不会授予其他工具。' : '停用此原安装。'}</p><p>{review.tool?.description}</p><pre className="pw-source-code">{JSON.stringify(review.tool?.schema || review.tool?.inputSchema || {}, null, 2)}</pre></>}
      <CheckboxField isSelected={acknowledged} isDisabled={busy} onChange={acknowledge}>我已核对本次准确范围，确认这次决定</CheckboxField><div className="pw-form-actions"><Button disabled={busy || !acknowledged || review.kind === 'script' && review.expiresAt <= Date.now()} onClick={() => decide(true)}>{review.kind === 'script' ? '批准本次执行' : '确认本次设置'}</Button>{review.kind === 'script' && <Button variant="secondary" disabled={busy} onClick={() => decide(false)}>拒绝本次执行</Button>}<Button variant="ghost" onClick={closeReview}>关闭审阅</Button></div>
    </>}</SettingsDialog>
  </section>;
}
