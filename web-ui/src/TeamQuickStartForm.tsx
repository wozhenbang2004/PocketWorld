import { Accordion, AccordionItem, Button, TextAreaField } from './forms-library';
import { IconChevronRight } from './icons';
import { TeamMemberIdentity } from './TeamMemberIdentity';
import { teamMemberLabel } from './team-identity';
import { teamPlannedModel } from './team-preparation';

type Data = Record<string, any>;

/** Read the old quick-start draft without creating a second work intake. */
export function TeamQuickStartForm({ editor, disabled, held, onGoalChange, onAdvanced }: {
  editor: Data; disabled: boolean; held: boolean;
  onGoalChange: (goal: string) => void;
  onAdvanced: () => void;
}) {
  const members: Data[] = Array.isArray(editor.members) ? editor.members : [];
  if (!members.length && !editor.goal) return <p className="pw-field-hint">没有找到未完成的旧协作草稿。新的目标可以直接交给管家。</p>;
  return <>
    <section className="pw-team-start-goal">
      <p className="pw-field-hint">{held ? '上次启动结果待核对。这里仅展示原草稿，不会重新发起。' :
        '这里保留的是原草稿，不是待批准的分工。带回管家只带目标；原成员配置仍留在这里。'}</p>
      <TextAreaField label="原目标草稿" value={editor.goal || ''} onChange={onGoalChange} maxLength={8000} rows={3} autoResize maxRows={8} isDisabled={disabled} />
    </section>
    {members.length > 0 && <Accordion variant="divided" className="pw-team-legacy-roster" defaultExpandedKeys={['legacy-roster']}><AccordionItem id="legacy-roster" title="原草稿里的成员与分工" description={`${editor.name || '旧协作'} · ${members.length} 名成员`}>
      <div className="pw-team-legacy-members">{members.map(member => <section key={member.id} className="pw-team-legacy-member">
        <TeamMemberIdentity member={member} name={teamMemberLabel(member, members)} detail={member.id === editor.leaderId ? '负责汇总' : undefined} size={32} />
        <p dir="auto">{member.role || '职责未填写'}</p>{member.assignment && member.assignment !== member.role && <p className="pw-field-hint" dir="auto">{member.assignment}</p>}
        {(!member.runner || member.runner === 'model') && <small className="pw-field-hint">草稿模型：<bdi>{teamPlannedModel(member,'未指定型号')}</bdi></small>}
      </section>)}</div>
      <Button variant="ghost" trailingIcon={IconChevronRight} disabled={disabled} onClick={onAdvanced}>把原分工另存为编组</Button>
    </AccordionItem></Accordion>}
  </>;
}
