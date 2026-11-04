import {useEffect,useState} from 'react';
import {Accordion,AccordionItem,Button,CheckboxField} from './forms-library';
import {PressButton} from './library';
import './task-decisions.css';

type Json=Record<string,any>;
const object=(value:unknown):Json=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Json:{};
const list=(value:unknown):Json[]=>Array.isArray(value)?value:[];
const labels:Record<string,string>={text:'内容',goal:'目标',url:'网址',path:'文件',packageName:'应用',name:'名称',prompt:'目标',command:'命令',recipient:'接收方',target:'目标对象',amount:'金额',total:'总额',currency:'币种',budget:'预算',maxAmount:'金额上限'};

/** Composes the adopted BoardUI disclosure/checkbox and Josh press button sources.
 * Decision facts stay visible; only exact technical identities and raw payloads fold away. */
export function TaskApproval({approval,executionScope={},disabled,onDecision}:{approval:Json;executionScope?:Json;disabled:boolean;onDecision:(args:Json)=>void}) {
 const [scopeReviewed,setScopeReviewed]=useState(false);
 const id=String(approval.approvalId||approval.id||''),capability=object(approval.capability),args=object(approval.arguments),view=object(approval.webReview);
 const team=view.kind==='team_plan',live=view.kind==='live_scope',execution=view.kind==='execution_plan',scope=object(capability.ownedService);
 const executionOriginal=object(view.executionPlan),executionPlan=object(executionOriginal.plan);
 const original=object(view.teamPlan),plan=object(original.plan),source=object(plan.sourceParent),previous=object(source.parentBudget),budget=object(plan.budgetPlan);
 const items=list(plan.items),members=list(object(plan.definition).members);
 const planMatches=!team||original.planRef===capability.planRef&&original.planHash===capability.planHash;
 const executionMatches=executionOriginal.planRevision===capability.planRevision&&executionOriginal.planArtifactRef===capability.planArtifactRef&&executionOriginal.planHash===capability.planHash&&executionOriginal.scopeRevision===capability.scopeRevision;
 useEffect(()=>setScopeReviewed(false),[id,executionOriginal.planRevision,executionOriginal.planArtifactRef,executionOriginal.planHash,executionOriginal.scopeRevision]);
 const canApprove=view.canApprove===true&&planMatches&&(!execution||executionMatches)&&(!(team||live||execution)||scopeReviewed);
 const decide=(approved:boolean)=>onDecision({approvalId:id,approved,...(approved&&(team||live||execution)?{scopeReviewed}:{}),...(approved&&team?{planRef:original.planRef,planHash:original.planHash}:{}),...(approved&&execution?{planRevision:executionOriginal.planRevision,planArtifactRef:executionOriginal.planArtifactRef,planHash:executionOriginal.planHash}:{})});
 const argumentFacts=Object.entries(args).filter(([key,value])=>key in labels&&['string','number','boolean'].includes(typeof value));
 return <section className="pw-approval-item pw-task-decision" aria-label="本次操作授权">
  <header className="pw-decision-heading">
   <span className="pw-decision-eyebrow">{execution?'审阅原任务计划':team?'确认工作计划':live?'确认服务范围':'需要你的确认'}</span>
   <h4>{String(approval.message||approval.title||(execution?'计划审阅':approval.toolName||approval.tool||'这次操作授权'))}</h4>
   {approval.reason&&<p className="pw-decision-context">{String(approval.reason)}</p>}
   {approval.description&&<p className="pw-decision-context">{String(approval.description)}</p>}
  </header>
  {(view.semanticDescription||view.programRisk)&&<div className="pw-decision-notice">
   <h5>{view.programRisk?'本机程序风险':'操作影响'}</h5>
   {view.semanticDescription&&<p className="pw-approval-copy">{String(view.semanticDescription)}</p>}
   {view.programRisk&&<p className="pw-approval-copy">{String(view.programRisk)}</p>}
  </div>}
  {argumentFacts.length>0&&<dl className="pw-decision-facts" aria-label="本次操作内容">
   {argumentFacts.map(([key,value])=><div key={key}><dt>{labels[key]}</dt><dd className="pw-approval-copy">{String(value)}</dd></div>)}
  </dl>}
  {capability.pluginId&&<p className="pw-decision-meta">插件 {String(capability.pluginId)} · v{String(capability.version||'')} · 安装 {String(capability.installationId||'').slice(-8)}</p>}
  {live&&<section className="pw-decision-section" aria-label="本次启动范围">
   <h5>本次启动与后续源码执行</h5>
   <dl className="pw-decision-facts">
    <div><dt>允许修改</dt><dd>仅原项目本轮的既有文件：
     <ul className="pw-decision-paths">{Array.isArray(scope.editablePaths)&&scope.editablePaths.map((path:unknown,index:number)=><li key={index}>{String(path)}</li>)}</ul>
    </dd></div>
    <div><dt>执行条件</dt><dd>每次仍须当前 SHA、原操作预算和真实服务写租约。配置、锁、工具脚本、环境和新文件不在此许可内。</dd></div>
    <div><dt>保留期限</dt><dd>{scope.retainAfterTurn?`本次明确保留：本轮答复后仍可试用和编辑，最多 ${Number(scope.timeoutMillis)/1000} 秒，原任务期限更早时优先停止。`:'未选择轮次后保留，最长 5 分钟，模型轮次结束即收尾。'}</dd></div>
   </dl>
   <p className="pw-decision-boundary">原任务停止、授权到期或撤销必须停止并排空。不是冷重启持久后台或公网服务。</p>
  </section>}
  {execution&&(view.canApprove===true&&executionMatches?<section className="pw-decision-section" aria-label="这版原任务计划与范围">
   <h5>核对原任务的计划 · 版本 {String(executionOriginal.planRevision)}</h5>
   <p className="pw-decision-goal pw-approval-copy">{String(executionPlan.goal||'')}</p>
   <p className="pw-decision-boundary">当前仍是只读规划。确认这版计划后，只能在原任务已有的工具权限、截止时间与剩余预算内继续；具体外部操作仍须原授权。</p>
   <ol className="pw-decision-worklist" aria-label="这版计划的完整步骤">{list(executionPlan.steps).map((step,index)=><li key={String(step.id)}>
    <div className="pw-decision-work-heading"><h6>{index+1}. {String(step.title)}</h6></div>
    <p className="pw-approval-copy">{String(step.detail)}</p>
   </li>)}</ol>
   {Array.isArray(executionPlan.sources)&&executionPlan.sources.length>0&&<Accordion className="pw-decision-disclosure" variant="divided" aria-label="这版计划的参考来源"><AccordionItem id={`${id}:sources`} headingLevel={5} title="查看计划引用的来源">{executionPlan.sources.map((source:string,index:number)=><p className="pw-approval-copy" key={index}>{source}</p>)}</AccordionItem></Accordion>}
   <h5>沿用原任务范围</h5>
   <dl className="pw-decision-facts">
    <div><dt>工具调用</dt><dd>已用 {String(executionScope.toolsUsed??'未读取')} / 总上限 {String(executionScope.toolLimit??'未读取')}</dd></div>
    <div><dt>模型调用</dt><dd>已用 {String(executionScope.modelCallsUsed??'未读取')} / 总上限 {String(executionScope.modelCallLimit??'未读取')}</dd></div>
    <div><dt>原截止时间</dt><dd>{executionScope.deadlineAt===0?'原任务未设置截止时间，仍受原预算与停止条件约束。':typeof executionScope.deadlineAt==='number'&&Number.isFinite(executionScope.deadlineAt)?new Date(executionScope.deadlineAt).toLocaleString('zh-CN'):'未读取'}</dd></div>
   </dl>
   <p className="pw-decision-boundary">本次确认不续期、不增加 token 或调用额度，也不清除已预留与未知用量。</p>
  </section>:<p role="alert">{String(view.readError||'尚未读取这版计划的完整原文，请重新核对；仍可拒绝这次申请。')}</p>)}
  {team&&(view.canApprove===true?<>
   <section className="pw-decision-section" aria-label="主代理提出的文件工作计划">
    <h5>主代理提出的文件工作计划</h5>
    <p className="pw-decision-goal pw-approval-copy">{String(plan.goal||'')}</p>
    <dl className="pw-decision-facts"><div><dt>原项目</dt><dd>{String(source.targetProjectId||'')}</dd></div></dl>
    <p className="pw-decision-boundary">只允许所选文件的隔离草稿制作和列明协调动作。父代理不能自批原项目写入、命令或额外预算。</p>
    <ol className="pw-decision-worklist" aria-label="原计划的文件与工作要求">{items.map(item=>{
     const member=members.find(row=>row.id===item.memberId);return <li key={item.workItemId}>
      <div className="pw-decision-work-heading"><h6>{String(item.title||'工作项')}</h6><span>成员 {String(member?.name||item.memberId||'')} · {list(item.files).length} 个文件</span></div>
      <dl className="pw-decision-facts">
       {Array.isArray(item.dependsOn)&&item.dependsOn.length>0&&<div><dt>前置冻结</dt><dd>{item.dependsOn.map((dep:string)=>items.find(row=>row.workItemId===dep)?.title||dep).join('、')}</dd></div>}
       <div><dt>文件范围</dt><dd><ul className="pw-decision-paths">{list(item.files).map(file=><li key={file.path}><span>{String(file.path)}</span><small>原基线 v{String(file.baseVersion)}</small></li>)}</ul></dd></div>
       <div><dt>要求</dt><dd className="pw-approval-copy">{String(item.instruction||'')}</dd></div>
       <div><dt>验收</dt><dd className="pw-approval-copy">{String(item.acceptance||'')}</dd></div>
      </dl>
     </li>;
    })}</ol>
   </section>
   <section className="pw-decision-section" aria-label="本次原父池累计预算">
    <h5>本次累计预算</h5>
    <dl className="pw-decision-budget">
     <div><dt>模型请求总上限</dt><dd><span>{String(previous.modelRequestLimit??0)}</span><span aria-label="调整为">→</span><strong>{String(plan.modelRequestLimit)}</strong></dd></div>
     <div><dt>工具操作总上限</dt><dd><span>{String(previous.toolOperationLimit??0)}</span><span aria-label="调整为">→</span><strong>{String(plan.toolOperationLimit)}</strong></dd></div>
    </dl>
    <p className="pw-decision-boundary">既有预留、实耗和未知账目保留。内部 token 与费用未知。</p>
    <dl className="pw-decision-facts pw-decision-budget-detail">
     <div><dt>文件链工具步骤</dt><dd>{String(budget.selectedFileToolSteps)}</dd></div>
     <div><dt>父协调计划</dt><dd>{String(budget.parentOrchestrationToolSteps)}</dd></div>
     <div><dt>规划与审批原操作</dt><dd>{String(budget.priorParentTeamToolOperationsNotYetInPool)}</dd></div>
     <div><dt>工具余量</dt><dd>{String(budget.toolRetryMargin)}</dd></div>
     <div><dt>父模型新增估计</dt><dd>{String(budget.estimatedParentModelRequests)}</dd></div>
     <div><dt>成员及审查估计</dt><dd>{String(budget.estimatedModelRequests)}</dd></div>
     <div><dt>模型余量</dt><dd>{String(budget.modelRetryMargin)}</dd></div>
     <div><dt>捕获时父授权剩余</dt><dd>{String(source.remainingOperations)} 次操作</dd></div>
    </dl>
    <p className="pw-decision-boundary">批准计划不补父授权，启动时仍核真实剩余。最终原项目仍须独审、你的准确计划批准及唯一整合者提交；停止回执不代表排空，未知外效不能重派或退款。</p>
    <dl className="pw-decision-facts"><div><dt>期限</dt><dd>{Number.isFinite(Number(plan.expiresAt))?new Date(Number(plan.expiresAt)).toLocaleString('zh-CN'):'未读取'}；回答不续期。</dd></div></dl>
   </section>
  </>:<p className="pw-decision-notice" role="alert">{String(view.readError||'尚未读取完整原计划，请重新核对后再批准。')}</p>)}
  {view.canApprove===true&&!planMatches&&<p className="pw-decision-notice" role="alert">原计划与当前审批不一致，请重新核对后再批准。</p>}
  <Accordion className="pw-decision-disclosure" aria-label="完整操作参数" variant="divided"><AccordionItem id={`${id}:details`} headingLevel={5} title={team?'核对完整参数与文件指纹':'查看完整操作参数与范围'} description="原始参数与准确标识">
   {team&&view.canApprove===true&&<dl className="pw-decision-facts pw-decision-fingerprints">{items.flatMap(item=>list(item.files).map(file=><div key={`${item.workItemId}:${file.path}`}><dt>{String(file.path)} · v{String(file.baseVersion)}</dt><dd><code>{String(file.baseHash)}</code></dd></div>))}</dl>}
   <pre className="pw-approval-json" tabIndex={0} aria-label="完整原始操作参数">{JSON.stringify({tool:approval.tool,arguments:args,capability,...(team&&view.canApprove===true?{originalPlan:original}:{}),...(execution&&view.canApprove===true&&executionMatches?{originalPlan:executionOriginal}:{})},null,2)}</pre>
  </AccordionItem></Accordion>
  <footer className="pw-decision-footer">
   {(team||live||execution)&&view.canApprove===true&&(!execution||executionMatches)&&<CheckboxField className="pw-decision-reviewed" isSelected={scopeReviewed} onChange={setScopeReviewed} isDisabled={disabled}>
    {execution?`我已核对计划版本 ${String(executionOriginal.planRevision)} 的完整步骤和原任务范围`:team?'我已核对原成员、依赖、文件基线、范围与累计预算':'我已核对原文件范围，并允许这些源码后续修改在本轮服务中执行'}
   </CheckboxField>}
   <div className="pw-decision-actions"><PressButton label={execution?'确认这版计划，在原范围内继续':team?'批准此计划与可见预算':live?'允许启动与限定热改':view.programRisk?'允许运行本机程序':'允许这次操作'} disabled={disabled||!id||!canApprove} onClick={()=>decide(true)}/><Button variant="secondary" disabled={disabled||!id} onClick={()=>decide(false)}>拒绝</Button></div>
  </footer>
 </section>;
}
