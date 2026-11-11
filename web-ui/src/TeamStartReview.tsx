import { TeamMemberIdentity } from './TeamMemberIdentity';
import { teamMemberLabel } from './team-identity';
import { teamPhoneScopeLabel, teamPhoneTargetLabel } from './team-phone';
import { teamRoleLabel } from './team-presentation';

type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];

/** Reads only the captured native review, never a live editor draft. */
export function TeamStartReview({ review, phoneTargets }: { review: Data; phoneTargets: Data[] }) {
  const definition = review.definition || {}, members = rows(definition.members);
  return <div className="pw-team-start-review">
    <section className="pw-team-review-goal"><h3>这次目标</h3><p>{review.input?.goal}</p></section>
    <dl className="pw-team-facts">
      <div><dt>{review.draft ? '本次分工' : '使用编组'}</dt><dd>{definition.name}</dd></div>
      <div><dt>协作方式</dt><dd>{definition.mode === 'produce' ? '项目文件协作' : definition.mode === 'debate' ? '取证与讨论' : '共同工作'}</dd></div>
      <div><dt>推进轮数</dt><dd>{review.input?.rounds} 轮</dd></div>
      {typeof review.budget?.callLimit === 'number' && <div><dt>模型调用上限</dt><dd>{review.budget.callLimit} 次</dd></div>}
      {typeof review.budget?.toolCallLimit === 'number' && <div><dt>工具操作上限</dt><dd>{review.budget.toolCallLimit} 次</dd></div>}
    </dl>
    <section className="pw-team-review-members" aria-label="即将开始的成员与分工"><h3>成员与分工</h3>{members.map(member => <div key={member.id} className="pw-team-review-member">
      <TeamMemberIdentity member={member} name={teamMemberLabel(member, members)} detail={member.id === definition.leaderId ? teamRoleLabel(definition) : undefined} size={32} />
      <p>{member.assignment || member.role}</p>
      {Array.isArray(member.dependsOn) && member.dependsOn.length > 0 && <p className="pw-field-hint">等待：{member.dependsOn.map((id: string) => members.find(value => value.id === id)?.name || id).join('、')}</p>}
      {(!member.runner || member.runner === 'model') && <p className="pw-field-hint">模型：{member.model || '开始时的主模型'}</p>}
    </div>)}</section>
    {review.phoneScope ? <section className="pw-team-review-phone" aria-label="已核对的手机范围"><h3>本次手机范围与额度</h3>{rows(review.phoneScope.members).map(member => <section className="pw-source-record" key={member.memberId}>
      <h4><TeamMemberIdentity member={members.find(value => value.id === member.memberId) || { ...member, id: member.memberId }} name={teamMemberLabel(members.find(value => value.id === member.memberId) || { ...member, id: member.memberId }, members)} /></h4>
      <p>{teamPhoneTargetLabel(member.device, phoneTargets)}{teamPhoneScopeLabel(member.resourceBinding) ? ` · ${teamPhoneScopeLabel(member.resourceBinding)}` : ''}</p>
      <p>最多 {member.toolBudget} 次操作 · {member.modelBudget} 次模型调用</p>
      {member.sourceQueryToolReserve > 0 && <p className="pw-field-hint">另预留 {member.sourceQueryToolReserve} 次目标任务状态查询。</p>}
      <p className="pw-field-hint">有效至 {new Date(member.expiresAt).toLocaleString('zh-CN')}</p>
    </section>)}</section> : <p className="pw-field-hint">开始后会使用模型额度，沿用本次核对的成员分工。{review.draft ? '这些角色只用于本次协作。' : '已保存的编组保持不变。'}</p>}
  </div>;
}
