import { Button } from './forms-library';
import { IconChevronRight, IconInfo, IconTeam } from './icons';
import { ProgressCircle } from './library';
import { teamPhaseLabel, teamStatusLabel } from './team-presentation';
import { settledTeamResultEntry } from './team-result-reading';
import { acceptedWorkTeamReport, teamFileEntryKey, workTeamResultEntryKey, type AcceptedWorkTeamReport, type WorkFileSource } from './team-navigation';
import type { OwnerWorkTeam } from './owner-work-presentation';

export type OpenOwnerWorkTeam = (team: OwnerWorkTeam, openPackage?: boolean, accepted?: AcceptedWorkTeamReport, files?: WorkFileSource) => void;

export function ownerWorkTeamFileSource(team: OwnerWorkTeam, teams: OwnerWorkTeam[]): WorkFileSource | undefined {
  const { fileReview, parentTaskId, parentRunId, parentEpoch, conversationId, teamRunId } = team;
  if (!fileReview || !team.active || !team.needsAttention || team.cancelRequested || team.status !== 'awaiting_review' || team.phase !== 'review' ||
      team.attentionCount !== 0 || team.pendingQuestionCount !== 0 || team.pendingDecisionCount !== 0 || team.pendingParentRevisionCount !== 0 ||
      team.pendingFileOperation || team.deliveredPackage || teams.some(row => row.teamRunId !== teamRunId && row.needsAttention)) return undefined;
  return { parentTaskId, parentRunId, parentEpoch, conversationId, teamRunId, fileReview };
}

/** The retained AgentRun progress-row composition, driven only by the
 * original work's native related-team projection. */
export function OwnerWorkTeams({ task, teams, onOpen, canOpenFiles }: { task:unknown; teams: OwnerWorkTeam[]; onOpen?: OpenOwnerWorkTeam; canOpenFiles?: boolean }) {
  if (!teams.length) return null;
  return <section className="pw-owner-work-teams" aria-label="协作进展">
    <h3>协作进展</h3>
    <ol>{teams.map(team => {
      const phase = teamPhaseLabel(team.phase);
      const busy = !team.needsAttention && ['queued', 'running', 'preparing', 'stopping'].includes(team.status);
      const stopping = team.active && (team.cancelRequested || ['cancelled', 'cancel', 'stopping', 'taken_over', 'takeover'].includes(team.status));
      const files = canOpenFiles && onOpen ? ownerWorkTeamFileSource(team, teams) : undefined;
      const primaryPackage=!!team.deliveredPackage&&!team.needsAttention&&!stopping;
      const lookForResults = settledTeamResultEntry(team);
      const accepted = teams.length===1&&lookForResults&&team.status==='completed'&&!team.deliveredPackage?acceptedWorkTeamReport(task,team):undefined;
      const resultsAction = accepted ? '查看验收时选用的原汇总' : '查看分工与成果';
      const status = files ? '原独审意见已保存' : primaryPackage ? '完整项目包已保存' : stopping ? '停止状态待核对' : team.needsAttention ? '有问题需要处理' : teamStatusLabel(team.status);
      const filesAction = files ? `核对独审交回的 ${files.fileReview.fileCount} 份冻结文件` : '';
      const detail = files ? filesAction : primaryPackage ? `查看固定版本 · ${team.deliveredPackage!.fileCount} 个文件` : lookForResults ? resultsAction : [phase,
        typeof team.memberCount === 'number' && team.memberCount > 0
          ? typeof team.completedMemberCount === 'number' && team.completedMemberCount <= team.memberCount
            ? `在队成员：${team.completedMemberCount}/${team.memberCount} 已记为完成` : `${team.memberCount} 名在队成员`
          : '',
      ].filter(Boolean).join(' · ');
      return <li key={team.teamRunId}>
        <Button variant="ghost" className="pw-owner-work-team-row" disabled={!onOpen} data-workspace-entry={files?teamFileEntryKey(files):primaryPackage?`team-package:${team.teamRunId}:${team.deliveredPackage!.operationId}`:lookForResults&&team.status==='completed'?workTeamResultEntryKey(team):undefined}
          onClick={() => onOpen?.(team,primaryPackage,accepted,files)}
          aria-label={`${filesAction || (primaryPackage ? '打开原团队的完整项目包' : team.needsAttention ? '处理团队问题' : lookForResults ? resultsAction : '查看协作进展')}：${team.goal || '这件工作'}`}>
          <span className="pw-owner-work-team-mark" data-attention={team.needsAttention || undefined} aria-hidden="true">
            {busy ? <ProgressCircle isIndeterminate size="sm" aria-label={status} /> : team.needsAttention ? <IconInfo size={19} /> : <IconTeam size={19} />}
          </span>
          <span className="pw-owner-work-team-copy">
            <strong>{status}</strong>
            {teams.length > 1 && <span dir="auto">{team.goal || '团队协作'}</span>}
            {detail && <small>{detail}</small>}
          </span>
          <IconChevronRight size={17} aria-hidden="true" />
        </Button>
        {team.deliveredPackage&&!primaryPackage&&!stopping&&<Button size="small" variant="ghost" trailingIcon={IconChevronRight}
          data-workspace-entry={`team-package:${team.teamRunId}:${team.deliveredPackage.operationId}`} disabled={!onOpen}
          onClick={()=>onOpen?.(team,true)}>另看这组已保存的完整项目包</Button>}
      </li>;
    })}</ol>
  </section>;
}
