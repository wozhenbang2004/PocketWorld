import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import './ecosystem-depth.css';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog } from './forms-library';
import { PermissionPolicySettings, PermissionTargetRetired, type PermissionLeaveGuard } from './PermissionPolicySettings';
import { TaskToolInventory } from './TaskToolInventory';
import { TaskCapabilityConsent, taskCapabilityArtifactStatus, taskCapabilityDecision } from './task-capabilities';
import { profileDependencyLabel } from './profile-contract';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const receiptStatus = (value: string) => ({unknown:'结果待核对',pending:'等待原回执',running:'执行中',failed:'执行失败',rejected:'本次已拒绝',completed:'执行回执已返回'} as Record<string,string>)[value] || value;
const names = (value: any) => Array.isArray(value) ? value.join('、') || '无' : '尚未取得';
const capabilityState = (state?: string): string => ({
  available: '配置已就绪，执行时仍核对本次授权', missing_installation: '尚未登记这个插件', source_unavailable: '当前来源暂不可核对',
  disabled: '这个插件已停用', missing_tool: '当前来源没有这项工具', grant_required: '这项工具尚未获准',
  needs_authorization: '需要在原连接完成授权', needs_discovery: '需要在原连接重新发现工具', not_available: '来源已准备，当前宿主仍未提供这项能力',
  not_selected: '原工作配置没有选入这项工具', selection_changed: '原工作配置固定的来源版本已变化', host_capability_missing: '当前宿主尚未提供这项能力',
  source_shadowed: profileDependencyLabel('source_shadowed'),
  unsupported_reference: '能力标识暂不受支持', revision_changed: '原技能版本或启用状态已变化', skill_not_active: '原技能未启用、已到期或待重新审阅', source_missing: '此范围没有原技能', scope_unavailable: '原任务的资料范围暂不可核对',
} as Record<string, string>)[state || ''] || '当前状态暂不可核对';
const requiredBy = (need: Data) => rows(need.requiredBy).map(skill => `${skill.name || skill.id} · 修订 ${skill.revision}`).join('；');
const toolMaintenanceRoute = (need: Data): 'plugins' | 'mcp' | null => {
  const installation = need.installation, definitions = installation?.manifest?.tools;
  if (!installation) return null;
  const missingDefinition = Array.isArray(definitions) && !definitions.some((tool: Data) => tool?.name === need.tool);
  const needsReconnection = ['needs_discovery', 'needs_authorization'].includes(installation.mcpStatus) || installation.credentialNeedsReentry === true;
  if (!missingDefinition && !needsReconnection) return null;
  return installation.manifest?.mcpSource || Array.isArray(definitions) && definitions.some((tool: Data) => tool?.kind === 'mcp') ? 'mcp' : 'plugins';
};

export function TaskCapabilityWorkspace({ taskId, run, exportState, onBackChange, onOpenTools, registerBeforeLeave }: { taskId: string; run: Run; exportState?: Data | null; onBackChange?: (handler: (() => boolean) | null) => void; onOpenTools?: (route?: 'skills' | 'plugins' | 'mcp' | 'workProfiles') => void; registerBeforeLeave?: (guard: PermissionLeaveGuard | null) => void }) {
  const [expanded, setExpanded] = useState(false), [view, setView] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null);
  const [script, setScript] = useState<Data | null>(null), [file, setFile] = useState<Data | null>(null), [busy, setBusy] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const root = useRef<HTMLElement>(null), policyPosition = useRef(0), policyWasOpen = useRef(false);
  const [policy, setPolicy] = useState<Data | null>(null), [policyRetired, setPolicyRetired] = useState('');
  const policyCurrent = useRef<Data | null>(null), policyGuard = useRef<PermissionLeaveGuard | null>(null), policyWriting = useRef(false), policyDead = useRef(false), policyQueue = useRef<Promise<unknown>>(Promise.resolve());
  const registerPolicyGuard = useCallback((guard: PermissionLeaveGuard | null) => { policyGuard.current = guard; }, []);
  const policyPending = useCallback((pending: boolean) => { policyWriting.current = pending; }, []);
  const inventoryBack = useRef<(() => boolean) | null>(null);
  const registerInventoryBack = useCallback((back: (() => boolean) | null) => { inventoryBack.current = back; }, []);
  const generation = useRef(0), alive = useRef(true), working = useRef(false), page = useRef(''), reviewId = useRef('');
  const consent = useRef(new TaskCapabilityConsent()), shown = useRef<Data | null>(null); shown.current = review;
  const version = generation.current, confirmTicket = consent.current.capture();
  const closeReview = () => { const handle = reviewId.current; reviewId.current = ''; consent.current.clear(); shown.current = null; setReview(null); setScript(null); setAcknowledged(false); if (handle) void run('taskCapabilities.dismissReview', { reviewHandle: handle }).catch(() => {}); };
  const close = () => { if (policyCurrent.current) { policyDead.current = true; setPolicyRetired('原任务能力窗口已关闭，这份草稿不能再提交。'); } generation.current++; working.current = false; closeReview(); const handle = page.current; page.current = ''; if (handle) void run('taskCapabilities.close', { pageHandle: handle }).catch(() => {}); setView(null); setFile(null); setBusy(false); };
  const invoke = async (action: string, args: Data, accept?: (value: Data) => void) => {
    if (!alive.current || version !== generation.current || working.current) return;
    const ticket = generation.current; working.current = true; setBusy(true); setError('');
    try { const value = await run(action, args); if (alive.current && ticket === generation.current) { accept?.(value); return value; }
      if (value?.pageHandle) void run('taskCapabilities.close', { pageHandle: value.pageHandle }).catch(() => {});
      if (value?.reviewHandle) void run('taskCapabilities.dismissReview', { reviewHandle: value.reviewHandle }).catch(() => {});
      if (value?.policyHandle) void run('taskCapabilities.policy.close', { policyHandle: value.policyHandle }).catch(() => {});
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
      setNotice(review.kind === 'script' ? taskCapabilityDecision(value) : value.saved === true ? '这项原安装的设置已保存。请回原任务重新核对可用工具与原选择，再继续工作。' : '原安装设置尚未确认。');
      if (value.view) acceptView(value.view); closeReview();
    });
  };
  const closePolicy = () => {
    const selected = policyCurrent.current; policyCurrent.current = null; policyGuard.current = null; policyDead.current = false; setPolicy(null); setPolicyRetired('');
    if (selected?.policyHandle) void run('taskCapabilities.policy.close', { policyHandle: selected.policyHandle }).catch(() => {});
    if (selected && page.current) { const handle = page.current, ticket = generation.current; void policyQueue.current.then(() => { if (alive.current && page.current === handle && ticket === generation.current && !policyCurrent.current) load(); }); }
  };
  const leavePolicy = (next: () => void = closePolicy) => { if (policyWriting.current) return; if (!policyGuard.current?.(next)) next(); };
  const openPolicy = (tool: string) => {
    if (!page.current || policyCurrent.current) return;
    void invoke('taskCapabilities.policy.open', { pageHandle: page.current, tool }, value => { policyPosition.current = root.current?.closest('.pw-task-body')?.scrollTop || 0; policyCurrent.current = value; policyDead.current = false; setPolicyRetired(''); setPolicy(value); });
  };
  const policyAct = (action: string, args: Data = {}): Promise<Data> => {
    const selected = policyCurrent.current, ticket = generation.current;
    const execute = async () => {
      if (!selected || policyCurrent.current !== selected || ticket !== generation.current || !page.current || policyDead.current) throw new PermissionTargetRetired('原任务审批编辑窗口已关闭，不能继续提交。');
      let result: Data;
      if (action === 'permissions.catalog') result = await run('taskCapabilities.policy.catalog', { policyHandle: selected.policyHandle });
      else if (action === 'permissions.snapshot') result = await run('taskCapabilities.policy.snapshot', { policyHandle: selected.policyHandle });
      else if (action === 'permissions.save') result = await run('taskCapabilities.policy.save', { policyHandle: selected.policyHandle, global: args.global, policy: args.policy, expectedRevision: args.expectedRevision });
      else throw new Error('此编辑页只处理原任务适用的审批规则。');
      if (!alive.current || ticket !== generation.current || policyCurrent.current !== selected) throw new PermissionTargetRetired('原窗口已变化，保存结果请回原任务核对；没有自动再次提交。');
      if (result.policyRetired === true) { const reason = String(result.message || '原任务编辑目标已失效，这份草稿不能再提交。'); policyDead.current = true; setPolicyRetired(reason); throw new PermissionTargetRetired(reason); }
      if (action !== 'permissions.catalog' && ['taskId', 'runId', 'epoch', 'tool', 'conversationId'].some(key => result[key] !== selected[key])) { const reason = '原任务审批目标已变化，未采用这次读取结果。'; policyDead.current = true; setPolicyRetired(reason); throw new PermissionTargetRetired(reason); }
      return result;
    };
    // Native task capabilities admit one read/write at a time. Queue reads, never retry writes.
    const result = policyQueue.current.then(execute, execute); policyQueue.current = result.catch(() => undefined); return result;
  };
  const original = (action: string, operation: Data) => { if (!page.current || version !== generation.current) return; void invoke(action, { pageHandle: page.current, operationHandle: operation.operationHandle }, value => { acceptView(value.view); setNotice(value.retrieved === true ? '已按原回执取回成果，没有重新提交脚本。' : '已核对原操作，没有重新提交。'); }); };
  const exported = exportState?.taskId === taskId ? exportState : null;
  useLayoutEffect(() => {
    if (policyWasOpen.current === !!policy) return; policyWasOpen.current = !!policy;
    const node = root.current, scroller = node?.closest('.pw-task-body');
    if (node && scroller) scroller.scrollTop = policy ? Math.max(0, scroller.scrollTop + node.getBoundingClientRect().top - scroller.getBoundingClientRect().top - 8) : policyPosition.current;
    node?.querySelector<HTMLElement>(policy ? '[data-task-policy-heading]' : '[data-task-policy-entry]')?.focus({ preventScroll: true });
  }, [policy]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; close(); }; }, [taskId]);
  useEffect(() => { const hidden = () => { if (document.visibilityState !== 'visible') close(); }; document.addEventListener('visibilitychange', hidden); return () => document.removeEventListener('visibilitychange', hidden); }, []);
  useEffect(() => {
    registerBeforeLeave?.(next => policyWriting.current || (policyCurrent.current ? policyGuard.current?.(next) || false : false));
    return () => registerBeforeLeave?.(null);
  }, [registerBeforeLeave]);
  useEffect(() => { onBackChange?.(() => { if (reviewId.current) { closeReview(); return true; } if (policyCurrent.current) { leavePolicy(); return true; } if (inventoryBack.current?.()) return true; if (expanded) { close(); setExpanded(false); return true; } return false; }); return () => onBackChange?.(null); }, [expanded, onBackChange]);

  return <section ref={root} aria-busy={busy} className="pw-task-capabilities pw-ecosystem-depth"><div className="pw-section-heading pw-eco-workspace-heading"><div><h3>任务脚本与工具</h3><p>核对这次执行，查看原回执与文件成果。</p></div><Button variant="secondary" disabled={busy} onClick={() => { if (expanded) { const collapse = () => { closePolicy(); close(); setExpanded(false); }; if (policyCurrent.current) leavePolicy(collapse); else collapse(); } else { setExpanded(true); load(); } }}>{expanded ? '收起' : '查看'}</Button></div>
    {expanded && <>
      {policy && <section className="pw-task-policy-editor" aria-label="原任务适用的审批规则">
        <Button variant="ghost" onClick={() => leavePolicy()}>返回本轮工具</Button>
        <h4 tabIndex={-1} data-task-policy-heading>{policy.conversationId ? '原任务所属对话的审批设置' : '原任务适用的全局审批设置'}</h4>
        <p>{policy.tool} · 原执行 {policy.runId} / {policy.epoch}</p>
        <p>这不是只给本任务的授权。保存所属对话规则会影响该对话的其他适用任务；保存全局规则会影响所有适用范围。已有原生限制不变，旧策略决策须重新核对，不会自动重跑。</p>
        <PermissionPolicySettings conversationId={String(policy.conversationId || '')} conversationLabel="原任务所属对话" initialScope={policy.conversationId ? 'conversation' : 'global'} initialTool={String(policy.tool)} initialToolFromTask act={policyAct} registerBeforeLeave={registerPolicyGuard} onPendingChange={policyPending} unavailableReason={policyRetired}/>
      </section>}
      <div hidden={!!policy} style={policy ? { display: 'none' } : undefined}><div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={load}>刷新原任务能力</Button></div>
      {view?.reviewPending && <section className="pw-eco-pending-review"><div><h4>待你核对本次脚本</h4><p>当前任务正在等你审阅脚本。打开此页不会批准执行。</p></div><Button disabled={busy} onClick={() => void invoke('taskCapabilities.review', { pageHandle: page.current }, showReview)}>审阅本次执行</Button></section>}{view?.capabilityPreparationMessage && <p>{view.capabilityPreparationMessage}</p>}
      <TaskToolInventory key={`${taskId}:${view?.toolInventory?.runId || ''}`} inventory={view?.toolInventory} registerBack={registerInventoryBack} onEditPolicy={openPolicy} onOpenCatalog={onOpenTools ? () => onOpenTools() : undefined} onOpenProfiles={onOpenTools ? () => onOpenTools('workProfiles') : undefined} />
      <div className="pw-eco-capability-layout"><section className="pw-eco-executions"><div className="pw-eco-section-heading"><h4>执行与成果</h4><p>原回执和文件分开核对，取回不会重跑脚本。</p></div>{rows(view?.executions).map(operation => <section className="pw-eco-operation" key={operation.operationId}><h4>原任务脚本回执</h4><p>{receiptStatus(operation.receipt?.status || operation.state || '等待原回执')} · {taskCapabilityArtifactStatus(operation)}</p><p className="pw-field-hint">原操作 {operation.operationId}</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => original('taskCapabilities.receipt', operation)}>核对原回执</Button>{operation.artifactAvailability?.canRecheck === true && <Button variant="secondary" disabled={busy} onClick={() => original('taskCapabilities.retrieve', operation)}>取回原文件成果</Button>}</div>{operation.receipt?.output && <Accordion variant="divided"><AccordionItem title="读取原始输出"><pre className="pw-source-code">{String(operation.receipt.output)}</pre></AccordionItem></Accordion>}{rows(operation.receipt?.artifacts).map(artifact => <div className="pw-eco-artifact" key={artifact.artifactId}><p>{artifact.path} · {artifact.bytes} 字节</p><Button variant="ghost" disabled={busy} onClick={() => void invoke('taskCapabilities.artifact', { pageHandle: page.current, artifactHandle: artifact.artifactHandle }, value => setFile(value))}>读取原成果</Button><Button variant="secondary" disabled={busy || !!exported && ['waiting', 'selected', 'writing'].includes(exported.phase)} onClick={() => void invoke('taskCapabilities.export', { pageHandle: page.current, artifactHandle: artifact.artifactHandle }, () => setNotice('系统保存请求已发出，请完成或取消原文件选择。'))}>导出原成果</Button></div>)}</section>)}
      {!!file && <section><h4>{file.path}</h4><p className="pw-field-hint" style={{ overflowWrap: 'anywhere' }}>SHA-256：{file.sha256}</p><p>{file.bytes} 字节。文件内容来自原脚本，尚未导入项目或执行。</p></section>}
      </section><aside className="pw-eco-tool-needs"><div className="pw-eco-section-heading"><h4>{view?.skillPreparation?.selectionSource === 'original_work_profile' ? '所选技能的能力准备' : '相关技能的能力准备'}</h4><p>{view?.skillPreparation?.selectionSource === 'original_work_profile' ? `按原工作配置${view.skillPreparation.profileRevision ? `修订 ${view.skillPreparation.profileRevision}` : ''}固定的技能版本核对。准备后仍需回原任务检查原选择。` : '按任务描述提示相关技能，列出它们需要的连接、工具与宿主能力。是否使用以本次执行为准。'}</p></div>
      {view?.skillPreparation && view.skillPreparation.state !== 'ready' && <p role="status">{view.skillPreparation.state === 'profile_unavailable' ? '原工作配置暂不可核对，请回原任务重新读取。' : view.skillPreparation.state === 'selection_changed' ? '读取期间原任务选择已变化，请刷新后核对。' : view.skillPreparation.state === 'skills_unavailable' ? '技能来源暂未读到，请刷新后核对；原脚本回执仍可查看。' : '原任务的资料范围暂不可核对，请先检查原项目。'}</p>}
      {rows(view?.skillPreparation?.unresolvedSkills).map(skill => <p className="pw-field-hint" key={skill.id}>原技能 {skill.id} · 修订 {skill.revision}：{capabilityState(skill.status)}</p>)}
      {rows(view?.pluginNeeds).map(need => {
        const installation = need.installation, maintenanceRoute = toolMaintenanceRoute(need);
        const knownTool = rows(installation?.manifest?.tools).some(tool => tool.name === need.tool), knownGrants = Array.isArray(installation?.grants);
        const granted = knownGrants && installation.grants.includes(`tool:${need.tool}`), canReviewGrant = knownTool && knownGrants && !['source_unavailable', 'needs_authorization', 'needs_discovery'].includes(need.status);
        return <section className="pw-eco-tool" key={need.needHandle || need.capability}><h4>{installation?.manifest?.name || need.pluginId} · {need.tool}</h4><p>{capabilityState(need.status)}</p>{requiredBy(need) && <p className="pw-field-hint">所需技能：{requiredBy(need)}</p>}
          <div className="pw-form-actions">{installation && <>
            {canReviewGrant && <Button variant="secondary" disabled={busy} onClick={() => void invoke('taskCapabilities.mcp.review', { pageHandle: page.current, needHandle: need.needHandle, change: 'grant', value: !granted }, showReview)}>{granted ? '审阅并撤销这个工具' : '审阅并允许这个工具'}</Button>}
            {typeof installation.enabled === 'boolean' && need.status !== 'source_unavailable' && <Button variant="ghost" disabled={busy} onClick={() => void invoke('taskCapabilities.mcp.review', { pageHandle: page.current, needHandle: need.needHandle, change: 'enabled', value: !installation.enabled }, showReview)}>{installation.enabled ? '审阅并停用这个安装' : '审阅并启用这个安装'}</Button>}
            {maintenanceRoute && onOpenTools && <Button variant="ghost" disabled={busy} onClick={() => onOpenTools(maintenanceRoute)}>管理这项工具的来源</Button>}
          </>}{!installation && need.status === 'missing_installation' && onOpenTools && <Button variant="secondary" disabled={busy} onClick={() => onOpenTools('plugins')}>查看插件与连接</Button>}
          {['not_selected', 'selection_changed'].includes(need.status) && onOpenTools && <Button variant="ghost" disabled={busy} onClick={() => onOpenTools('workProfiles')}>查看工作配置</Button>}</div>
        </section>;
      })}
      {rows(view?.capabilityNeeds).map(need => <section className="pw-eco-tool" key={need.capability}><h4>{need.capability}</h4><p>{capabilityState(need.status)}</p><p className="pw-field-hint">所需技能：{requiredBy(need)}</p></section>)}
      </aside></div>{view && !view.reviewPending && !rows(view.executions).length && !rows(view.pluginNeeds).length && !rows(view.capabilityNeeds).length && !rows(view.skillPreparation?.unresolvedSkills).length && (!view.skillPreparation || view.skillPreparation.state === 'ready') && <p className="pw-muted">当前没有待审阅脚本、原执行记录或需要准备的技能能力。</p>}
      </div>
    </>}{error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{exported && <p role="status">{exported.error || (exported.phase === "exported" && exported.receipt?.status === "exported" ? `已按原 SHA-256 导出 ${exported.receipt.bytes} 字节，请在文件应用中查看。` : exported.phase === "cancelled" ? "原保存选择已取消，没有写入其他位置。" : exported.phase === "writing" ? "正在写入所选的原文件位置…" : "等待原系统保存选择；尚未确认文件已导出。")}</p>}
    <SettingsDialog isOpen={!!review} onClose={closeReview} title={review?.kind === 'script' ? '审阅本次脚本执行' : '审阅工具设置'}>{review && <div className="pw-ecosystem-depth pw-eco-review-body">
      {review.kind === 'script' ? <><p>本次批准只属于当前原任务、脚本版本、执行地点和参数。脚本内容与结果均需独立核对。</p><dl className="pw-facts"><div><dt>技能</dt><dd>{review.package?.name || review.package?.id} · 修订 {review.package?.revision}</dd></div><div><dt>脚本</dt><dd>{review.request?.scriptPath}</dd></div><div><dt>执行地点</dt><dd>{review.executionLocation === 'this_phone' ? '这台手机的原托管环境' : '原配置的执行端'}</dd></div><div><dt>最长时间</dt><dd>{review.request?.timeoutSeconds} 秒</dd></div><div><dt>读取范围</dt><dd>{names(review.request?.readPaths)}</dd></div><div><dt>写入范围</dt><dd>{names(review.request?.writePaths)}</dd></div><div><dt>成果范围</dt><dd>{names(review.request?.outputPaths)}</dd></div><div><dt>到期时间</dt><dd>{typeof review.expiresAt === 'number' ? new Date(review.expiresAt).toLocaleString() : '尚未取得'}</dd></div></dl><Accordion><AccordionItem title="准确参数与内容标识"><pre className="pw-source-code">{JSON.stringify({ argv: review.argv, cwd: review.cwd, planDigest: review.planDigest, scriptSha256: review.scriptSha256, resources: review.resources, request: review.request, backend: review.backend }, null, 2)}</pre></AccordionItem></Accordion><Button variant="secondary" disabled={busy} onClick={() => void invoke('taskCapabilities.script', { reviewHandle: review.reviewHandle }, value => { if (shown.current === review) setScript(value); })}>读取当前版本的脚本内容</Button>{script && <pre className="pw-source-code">{script.content}</pre>}</> : <><p>{review.pluginId} · {review.tool?.name}</p><p>{review.change === 'grant' ? review.value ? '只允许原安装的这一个工具。实际任务调用仍走原任务授权。' : '撤销原安装的这一个工具。' : review.value ? '启用此原安装；不会授予其他工具。' : '停用此原安装。'}</p><p>{review.tool?.description}</p><Accordion variant="divided"><AccordionItem title="工具参数与原始定义"><pre className="pw-source-code">{JSON.stringify(review.tool?.schema || review.tool?.inputSchema || {}, null, 2)}</pre></AccordionItem></Accordion></>}
      <CheckboxField isSelected={acknowledged} isDisabled={busy} onChange={acknowledge}>我已核对本次准确范围，确认这次决定</CheckboxField><div className="pw-form-actions pw-eco-review-confirm"><Button disabled={busy || !acknowledged || review.kind === 'script' && review.expiresAt <= Date.now()} onClick={() => decide(true)}>{review.kind === 'script' ? '批准本次执行' : '确认本次设置'}</Button>{review.kind === 'script' && <Button variant="secondary" disabled={busy} onClick={() => decide(false)}>拒绝本次执行</Button>}<Button variant="ghost" onClick={closeReview}>关闭审阅</Button></div>
    </div>}</SettingsDialog>
  </section>;
}
