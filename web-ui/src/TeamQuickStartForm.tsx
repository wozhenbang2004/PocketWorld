import { Accordion, AccordionItem, Button, TextAreaField } from './forms-library';
import { IconChevronRight } from './icons';
import { TeamMemberIdentity } from './TeamMemberIdentity';
import { teamMemberLabel } from './team-identity';

type Data = Record<string, any>;

/** Read the old quick-start draft without creating a second work intake. */
export function TeamQuickStartForm({ editor, disabled, onGoalChange, onAdvanced }: {
  editor: Data; disabled: boolean;
  onGoalChange: (goal: string) => void;
  onAdvanced: () => void;
}) {
  const members: Data[] = Array.isArray(editor.members) ? editor.members : [];
  if (!members.length && !editor.goal) return <p className="pw-field-hint">没有找到未完成的旧协作草稿。新的目标可以直接交给管家。</p>;
  return <>
    <section className="pw-team-start-goal">
      <p className="pw-field-hint">原目标与分工仍保留。尚未提交的目标可以带回管家输入区，由你继续交办。</p>
      <TextAreaField label="原目标草稿" value={editor.goal || ''} onChange={onGoalChange} maxLength={8000} rows={3} autoResize maxRows={8} isDisabled={disabled} />
    </section>
    {members.length > 0 && <Accordion variant="divided" className="pw-team-legacy-roster"><AccordionItem id="legacy-roster" title="保留的成员与分工" description={`${editor.name || '旧协作'} · ${members.length} 名成员`}>
      <div className="pw-team-legacy-members">{members.map(member => <section key={member.id} className="pw-team-legacy-member">
        <TeamMemberIdentity member={member} name={teamMemberLabel(member, members)} detail={member.id === editor.leaderId ? '负责汇总' : undefined} size={32} />
        <p>{member.role}</p>{member.assignment && member.assignment !== member.role && <p className="pw-field-hint">{member.assignment}</p>}
      </section>)}</div>
      <Button variant="ghost" trailingIcon={IconChevronRight} disabled={disabled} onClick={onAdvanced}>把原分工另存为编组</Button>
    </AccordionItem></Accordion>}
  </>;
}
