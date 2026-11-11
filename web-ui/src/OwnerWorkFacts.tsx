import { Button } from './forms-library';
import { IconChevronRight } from './icons';
import { ProgressCircle } from './library';
import { ownerWorkPresentation, type OwnerWorkTeam, type ReportReconciliationTarget } from './owner-work-presentation';
import { OwnerWorkTeams } from './OwnerWorkTeams';
import './owner-work.css';

/** The Brief/Assets relationship from Affect AgentChat, rendered with the
 * retained BoardUI controls and host-owned facts instead of demo data. */
export function OwnerWorkFacts({ task, onReport, onReconcile, onTeam }: { task: unknown; onReport?: () => void; onReconcile?: (target: ReportReconciliationTarget) => void; onTeam?: (team: OwnerWorkTeam) => void }) {
  const work = ownerWorkPresentation(task);
  if (!work) return null;
  return <section className="pw-owner-work-facts" aria-label="成果与管家处理" data-handling={work.state}>
    <div className="pw-owner-work-handling">
      {work.waitingForButler && work.state === 'running' && <ProgressCircle isIndeterminate size="sm" aria-label="管家正在核对结果" />}
      <div><strong>{work.handlingLabel}</strong>{work.reason && <p>{work.reason}</p>}</div>
      {work.delivered && onReport && <Button variant="ghost" trailingIcon={IconChevronRight} onClick={onReport}>回到汇报话题</Button>}
      {work.receiptReconciliation && onReconcile && <Button variant="ghost" trailingIcon={IconChevronRight} onClick={() => onReconcile(work.receiptReconciliation!)}>查看整理记录</Button>}
    </div>
    {(work.resultAvailable || !work.teams.length) && <p className="pw-owner-work-result-state">{work.resultState}</p>}
    <OwnerWorkTeams teams={work.teams} onOpen={onTeam} />
    {work.teamsUnavailable && <p className="pw-owner-work-result-state" role="status">协作进展待读取，请稍后重新打开这件工作。</p>}
  </section>;
}

export function WorkGoal({ task }: { task: { goal: string; raw?: Record<string, any> } }) {
  const work = ownerWorkPresentation(task), contract = work?.contract;
  return <WorkContract goal={task.goal} contract={contract} />;
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
