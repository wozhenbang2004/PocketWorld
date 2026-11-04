import {useEffect,useState} from 'react';
import {Accordion,AccordionItem,Button,CheckboxField} from './forms-library';
import {PressButton} from './library';

type Json=Record<string,any>;
const object=(value:unknown):Json=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Json:{};
const list=(value:unknown):Json[]=>Array.isArray(value)?value:[];
const labels:Record<string,string>={text:'内容',goal:'目标',url:'网址',path:'文件',packageName:'应用',name:'名称',prompt:'目标',command:'命令',recipient:'接收方',target:'目标对象'};

/** Composes the adopted BoardUI disclosure/checkbox and Josh press button sources. */
export function TaskApproval({approval,executionScope={},disabled,onDecision}:{approval:Json;executionScope?:Json;disabled:boolean;onDecision:(args:Json)=>void}) {
 const [scopeReviewed,setScopeReviewed]=useState(false);
 const id=String(approval.approvalId||approval.id||''),capability=object(approval.capability),args=object(approval.arguments),view=object(approval.webReview);
 const team=view.kind==='team_plan',live=view.kind==='live_scope',execution=view.kind==='execution_plan',scope=object(capability.ownedService);
 const executionOriginal=object(view.executionPlan),executionPlan=object(executionOriginal.plan);
 const original=object(view.teamPlan),plan=object(original.plan),source=object(plan.sourceParent),previous=object(source.parentBudget),budget=object(plan.budgetPlan);
 const items=list(plan.items),members=list(object(plan.definition).members);
 const executionMatches=executionOriginal.planRevision===capability.planRevision&&executionOriginal.planArtifactRef===capability.planArtifactRef&&executionOriginal.planHash===capability.planHash&&executionOriginal.scopeRevision===capability.scopeRevision;
 useEffect(()=>setScopeReviewed(false),[id,executionOriginal.planRevision,executionOriginal.planArtifactRef,executionOriginal.planHash,executionOriginal.scopeRevision]);
 const canApprove=view.canApprove===true&&(!team||original.planRef===capability.planRef&&original.planHash===capability.planHash)&&(!execution||executionMatches)&&(!(team||live||execution)||scopeReviewed);
 const decide=(approved:boolean)=>onDecision({approvalId:id,approved,...(approved&&(team||live||execution)?{scopeReviewed}:{}),...(approved&&team?{planRef:original.planRef,planHash:original.planHash}:{}),...(approved&&execution?{planRevision:executionOriginal.planRevision,planArtifactRef:executionOriginal.planArtifactRef,planHash:executionOriginal.planHash}:{})});
 return <section className="pw-approval-item" aria-label="本次操作授权">
  <h4>{String(approval.message||approval.title||(execution?'计划审阅':approval.toolName||approval.tool||'这次操作授权'))}</h4>
  {approval.reason&&<p>{String(approval.reason)}</p>}{approval.description&&<p>{String(approval.description)}</p>}
  {view.semanticDescription&&<p className="pw-approval-copy">{String(view.semanticDescription)}</p>}
  {view.programRisk&&<p className="pw-approval-copy">{String(view.programRisk)}</p>}
  {capability.pluginId&&<p>插件 {String(capability.pluginId)} · v{String(capability.version||'')} · 安装 {String(capability.installationId||'').slice(-8)}</p>}
  {Object.entries(args).filter(([key,value])=>key in labels&&typeof value==='string').map(([key,value])=><p className="pw-approval-copy" key={key}><strong>{labels[key]}：</strong>{String(value)}</p>)}
  {live&&<>
   <h4>本次启动包含限定热改与后续源码执行</h4>
   <p className="pw-approval-copy">仅原项目本轮的既有文件：{Array.isArray(scope.editablePaths)?scope.editablePaths.join('、'):''}。每次仍须当前 SHA、原操作预算和真实服务写租约；配置、锁、工具脚本、环境和新文件不在此许可内。</p>
   <p>原任务停止、授权到期或撤销必须停止并排空。{scope.retainAfterTurn?`本次明确保留：本轮答复后仍可试用和编辑，最多 ${Number(scope.timeoutMillis)/1000} 秒，原任务期限更早时优先停止。`:'未选择轮次后保留，最长 5 分钟，模型轮次结束即收尾。'}不是冷重启持久后台或公网服务。</p>
  </>}
  {execution&&(view.canApprove===true&&executionMatches?<>
   <h4>核对原任务的计划 · 版本 {String(executionOriginal.planRevision)}</h4>
   <p className="pw-approval-copy">{String(executionPlan.goal||'')}</p>
   <p>当前仍是只读规划。确认这版计划后，只能在原任务已有的工具权限、截止时间与剩余预算内继续；具体外部操作仍须原授权。</p>
   <Accordion variant="divided" aria-label="这版计划的完整步骤">{list(executionPlan.steps).map((step,index)=><AccordionItem key={String(step.id)} id={`${id}:plan:${String(step.id)}`} headingLevel={5} title={`${index+1}. ${String(step.title)}`}>
    <p className="pw-approval-copy">{String(step.detail)}</p>
   </AccordionItem>)}</Accordion>
   {Array.isArray(executionPlan.sources)&&executionPlan.sources.length>0&&<Accordion variant="divided" aria-label="这版计划的参考来源"><AccordionItem id={`${id}:sources`} headingLevel={5} title="查看计划引用的来源">{executionPlan.sources.map((source:string,index:number)=><p className="pw-approval-copy" key={index}>{source}</p>)}</AccordionItem></Accordion>}
   <h4>沿用原任务范围</h4>
   <p>工具调用已用 {String(executionScope.toolsUsed??'未读取')} / 总上限 {String(executionScope.toolLimit??'未读取')}；模型调用已用 {String(executionScope.modelCallsUsed??'未读取')} / 总上限 {String(executionScope.modelCallLimit??'未读取')}。</p>
   <p>{executionScope.deadlineAt===0?'原任务未设置截止时间，仍受原预算与停止条件约束。':typeof executionScope.deadlineAt==='number'?`原截止时间：${new Date(executionScope.deadlineAt).toLocaleString('zh-CN')}。`:'原截止时间未读取。'} 本次确认不续期、不增加 token 或调用额度，也不清除已预留与未知用量。</p>
  </>:<p role="alert">{String(view.readError||'尚未读取这版计划的完整原文，请重新核对；仍可拒绝这次申请。')}</p>)}
  {team&&(view.canApprove===true?<>
   <h4>主代理提出的文件工作计划</h4><p className="pw-approval-copy">{String(plan.goal||'')}</p>
   <p>原项目 {String(source.targetProjectId||'')}；只允许所选文件的隔离草稿制作和列明协调动作。父代理不能自批原项目写入、命令或额外预算。</p>
   <Accordion variant="divided" aria-label="原计划的文件与工作要求">{items.map(item=>{
    const member=members.find(row=>row.id===item.memberId);return <AccordionItem key={item.workItemId} id={String(item.workItemId)} headingLevel={5} title={String(item.title||'工作项')} description={`成员 ${String(member?.name||item.memberId||'')} · ${list(item.files).length} 个文件`}>
     {Array.isArray(item.dependsOn)&&item.dependsOn.length>0&&<p>等待前置冻结：{item.dependsOn.map((dep:string)=>items.find(row=>row.workItemId===dep)?.title||dep).join('、')}</p>}
     {list(item.files).map(file=><p className="pw-approval-copy" key={file.path}>{String(file.path)} · 原基线 v{String(file.baseVersion)} / {String(file.baseHash)}</p>)}
     <p className="pw-approval-copy">要求：{String(item.instruction||'')}</p><p className="pw-approval-copy">验收：{String(item.acceptance||'')}</p>
    </AccordionItem>;
   })}</Accordion>
   <h4>本次原父池累计预算</h4>
   <p>模型请求总上限 {String(previous.modelRequestLimit??0)} → {String(plan.modelRequestLimit)}；工具操作总上限 {String(previous.toolOperationLimit??0)} → {String(plan.toolOperationLimit)}。既有预留、实耗和未知账目保留。</p>
   <p>文件链工具步骤 {String(budget.selectedFileToolSteps)}，父协调计划 {String(budget.parentOrchestrationToolSteps)}，规划与审批原操作 {String(budget.priorParentTeamToolOperationsNotYetInPool)}，工具余量 {String(budget.toolRetryMargin)}。父模型新增估计 {String(budget.estimatedParentModelRequests)}，成员及审查估计 {String(budget.estimatedModelRequests)}，模型余量 {String(budget.modelRetryMargin)}。</p>
   <p>捕获时父原授权操作剩余 {String(source.remainingOperations)}；批准计划不补父授权，启动时仍核真实剩余。内部 token 与费用未知。最终原项目仍须独审、你的准确计划批准及唯一整合者提交；停止回执不代表排空，未知外效不能重派或退款。</p>
   <p>期限：{Number.isFinite(Number(plan.expiresAt))?new Date(Number(plan.expiresAt)).toLocaleString('zh-CN'):'未读取'}；回答不续期。</p>
  </>:<p role="alert">{String(view.readError||'尚未读取完整原计划，请重新核对后再批准。')}</p>)}
  <Accordion aria-label="完整操作参数" variant="divided"><AccordionItem id={`${id}:details`} headingLevel={5} title="查看完整操作参数与范围">
   <pre className="pw-approval-json">{JSON.stringify({tool:approval.tool,arguments:args,capability,...(team&&view.canApprove===true?{originalPlan:original}:{}),...(execution&&view.canApprove===true&&executionMatches?{originalPlan:executionOriginal}:{})},null,2)}</pre>
  </AccordionItem></Accordion>
  {(team||live||execution)&&view.canApprove===true&&(!execution||executionMatches)&&<CheckboxField isSelected={scopeReviewed} onChange={setScopeReviewed} isDisabled={disabled}>
   {execution?`我已核对计划版本 ${String(executionOriginal.planRevision)} 的完整步骤和原任务范围`:team?'我已核对原成员、依赖、文件基线、范围与累计预算':'我已核对原文件范围，并允许这些源码后续修改在本轮服务中执行'}
  </CheckboxField>}
  <div className="pw-action-row"><PressButton label={execution?'确认这版计划，在原范围内继续':team?'批准此计划与可见预算':live?'允许启动与限定热改':view.programRisk?'允许运行本机程序':'允许这次操作'} disabled={disabled||!id||!canApprove} onClick={()=>decide(true)}/><Button variant="secondary" disabled={disabled||!id} onClick={()=>decide(false)}>拒绝</Button></div>
 </section>;
}
