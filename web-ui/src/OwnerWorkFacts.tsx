import { Button } from './forms-library';
import { IconChevronRight } from './icons';
import { ProgressCircle } from './library';
import { ownerWorkPresentation, type ReportReconciliationTarget } from './owner-work-presentation';
import { OwnerWorkTeams, type OpenOwnerWorkTeam } from './OwnerWorkTeams';
import { ApprovedSavedTeamRoster, originalTeamStartDecisionReadback } from './TaskApproval';
import './owner-work.css';

/** The Brief/Assets relationship from Affect AgentChat, rendered with the
 * retained BoardUI controls and host-owned facts instead of demo data. */
export function OwnerWorkFacts({ task, onReport, reportLabel, onReconcile, onTeam, canOpenTeamFiles, activeTeamRuns }: { task: unknown; onReport?: () => void; reportLabel?: string; onReconcile?: (target: ReportReconciliationTarget) => void; onTeam?: OpenOwnerWorkTeam; canOpenTeamFiles?: boolean; activeTeamRuns?:Record<string,unknown> }) {
  const work = ownerWorkPresentation(task);
  if (!work) return null;
  const showHandling = work.waitingForButler || work.waitingForChildren || work.delivered ||
    work.state === 'needs_review' || work.state === 'unavailable' || work.resultState === '来源不可用';
  const showResult = work.resultAvailable || work.resultState === '原结果不可用';
  const teamDecision = originalTeamStartDecisionReadback(task);
  if (!showHandling && !showResult && !work.teams.length && !work.teamsUnavailable && !teamDecision) return null;
  return <section className="pw-owner-work-facts" aria-label="成果与管家处理" data-handling={work.state}>
    {showHandling && <div className="pw-owner-work-handling">
      {work.waitingForButler && work.state === 'running' && <ProgressCircle isIndeterminate size="sm" aria-label="管家正在核对结果" />}
      <div><strong>{work.handlingLabel}</strong>{work.reason && <p>{work.reason}</p>}</div>
      {work.delivered && onReport && <Button variant="ghost" trailingIcon={IconChevronRight} onClick={onReport}>{reportLabel??'回到汇报话题'}</Button>}
      {work.receiptReconciliation && onReconcile && <Button variant="ghost" trailingIcon={IconChevronRight} onClick={() => onReconcile(work.receiptReconciliation!)}>查看整理记录</Button>}
    </div>}
    {showResult && <p className="pw-owner-work-result-state">{work.resultState}</p>}
    {teamDecision && <div className="pw-owner-work-handling" role="status" aria-label="原团队启动核对"><div>
      <strong>{teamDecision.used ? '原分工的批准已被原任务使用' : '原分工的批准已保存'}</strong>
      <p>{teamDecision.priorRun ? '原操作在等待这次批准时未派发，之后执行轮次已有变化。' : '原操作在等待这次批准时未派发。'}
        当前还没有核对到团队启动回执。请以这件工作的进展为准，不要重复批准或重新交办。</p>
      {teamDecision.anotherPending && <p>另一笔待审分工是不同申请，不代表这次批准没保存。</p>}
    </div></div>}
    {teamDecision?.savedPlan && <ApprovedSavedTeamRoster plan={teamDecision.savedPlan} />}
    <OwnerWorkTeams task={task} teams={work.teams} onOpen={onTeam} canOpenFiles={canOpenTeamFiles} activeTeamRuns={activeTeamRuns}/>
    {work.teamsUnavailable && <p className="pw-owner-work-result-state" role="status">协作进展待读取，请稍后重新打开这件工作。</p>}
  </section>;
}

export function WorkGoal({ task }: { task: { goal: string; raw?: Record<string, any> } }) {
  const work = ownerWorkPresentation(task), raw = task.raw, selected = raw?.developmentContext?.deliveryContract;
  const ordinary = raw?.kind === 'assistant' && raw.actorId === 'owner' && raw.spaceId === 'private' && raw.origin == null && raw.contextAvailable !== false &&
    selected?.format === 'pocketworld.owner-work-contract.v1' && Number.isSafeInteger(selected.revision) && selected.revision > 0 &&
    typeof selected.goal === 'string' && ['constraints', 'deliverable', 'completionCriteria'].every(key => selected[key] === undefined || typeof selected[key] === 'string')
    ? selected : undefined;
  const contract = work?.contract || ordinary;
  return <><WorkContract goal={task.goal} contract={contract} />
    {ordinary?.constraints && <p className="pw-muted">这是你在原项目提交的本次限制，不证明某次回答已收到或采用。</p>}
    {!contract && raw?.developmentContext?.deliveryContractReadState === 'not_loaded' && <p className="pw-muted" role="status">交付要求尚未完整载入，请重新打开原任务查看。</p>}
  </>;
}

export function WorkContract({ goal, contract }: { goal: string; contract?: { goal: string; deliverable?: string; completionCriteria?: string; constraints?: string } }) {
  return <section className="pw-work-goal" aria-label="这件工作的目标">
    <h3>要完成什么</h3><p className="pw-work-goal-text" dir="auto">{contract?.goal || goal}</p>
    {contract && <dl>
      {contract.deliverable && <div><dt>交付内容</dt><dd dir="auto">{contract.deliverable}</dd></div>}
      {contract.completionCriteria && <div><dt>完成条件</dt><dd dir="auto">{contract.completionCriteria}</dd></div>}
      {contract.constraints && <div><dt>已约定的范围</dt><dd dir="auto">{contract.constraints}</dd></div>}
    </dl>}
    {contract && !contract.deliverable && !contract.completionCriteria && !contract.constraints && <p className="pw-muted">更多要求仍按原请求与后续补充执行。</p>}
  </section>;
}
