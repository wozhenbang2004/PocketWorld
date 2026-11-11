import { Button } from './forms-library';
import { IconChevronRight, IconInfo, IconTeam } from './icons';
import { ProgressCircle } from './library';
import { teamPhaseLabel, teamStatusLabel } from './team-presentation';
import type { OwnerWorkTeam } from './owner-work-presentation';

/** The retained AgentRun progress-row composition, driven only by the
 * original work's native related-team projection. */
export function OwnerWorkTeams({ teams, onOpen }: { teams: OwnerWorkTeam[]; onOpen?: (team: OwnerWorkTeam) => void }) {
  if (!teams.length) return null;
  return <section className="pw-owner-work-teams" aria-label="协作进展">
    <h3>协作进展</h3>
    <ol>{teams.map(team => {
      const phase = teamPhaseLabel(team.phase);
      const busy = !team.needsAttention && ['queued', 'running', 'preparing', 'stopping'].includes(team.status);
      const stopping = team.active && (team.cancelRequested || ['cancelled', 'cancel', 'stopping', 'taken_over', 'takeover'].includes(team.status));
      const status = stopping ? '停止状态待核对' : team.needsAttention ? '有问题需要处理' : teamStatusLabel(team.status);
      const detail = [phase,
        typeof team.memberCount === 'number' && team.memberCount > 0
          ? typeof team.completedMemberCount === 'number' ? `本阶段 ${team.completedMemberCount}/${team.memberCount} 名成员已返回` : `${team.memberCount} 名成员`
          : '',
      ].filter(Boolean).join(' · ');
      return <li key={team.teamRunId}>
        <Button variant="ghost" className="pw-owner-work-team-row" disabled={!onOpen} onClick={() => onOpen?.(team)}
          aria-label={`${team.needsAttention ? '处理团队问题' : '查看协作进展'}：${team.goal || '这件工作'}`}>
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
      </li>;
    })}</ol>
  </section>;
}
