import { Accordion, AccordionItem, Button, SelectField, TextAreaField, TextField } from './forms-library';
import { IconAdd, IconChevronRight } from './icons';
import { TeamMemberIdentity } from './TeamMemberIdentity';
import { teamMemberLabel } from './team-identity';

type Data = Record<string, any>;

/** Presentation for a temporary roster; native review owns normalization and admission. */
export function TeamQuickStartForm({ editor, savedTeams, disabled, onChange, onGoalChange, onMemberChange, onChooseSaved, onTemplate, onAddMember, onAdvanced }: {
  editor: Data; savedTeams: Data[]; disabled: boolean;
  onChange: (changes: Data) => void; onGoalChange: (goal: string) => void;
  onMemberChange: (memberId: string, changes: Data) => void;
  onChooseSaved: (id: string) => void; onTemplate: (template: string) => void;
  onAddMember: () => void; onAdvanced: () => void;
}) {
  const members: Data[] = Array.isArray(editor.members) ? editor.members : [];
  return <>
    <section className="pw-team-start-goal">
      <TextAreaField label="这次想一起完成什么" value={editor.goal || ''} onChange={onGoalChange} maxLength={8000} rows={3} autoResize maxRows={8} isDisabled={disabled} isRequired placeholder="说明你想得到的结果，以及需要考虑的条件。" />
      {savedTeams.length > 0 && <SelectField label="使用已有编组" value="" options={[{ id: '', label: '选择已保存的成员与分工' }, ...savedTeams.map(team => ({ id: team.id, label: `${team.name} · ${Array.isArray(team.members) ? team.members.length : 0} 名成员` }))]} onChange={onChooseSaved} isDisabled={disabled} hint="目标会带入所选编组，设备与资料范围在启动前核对。" />}
    </section>
    <section className="pw-team-quick-roster" aria-label="本次参与分工">
      <header className="pw-team-quick-heading"><div><h3>这次怎么分工</h3><p>先用两个角色起步，可以直接调整职责。</p></div></header>
      <div className="pw-team-template-options"><Button variant="secondary" disabled={disabled} onClick={() => onTemplate('research')}>研究与核对</Button><Button variant="secondary" disabled={disabled} onClick={() => onTemplate('options')}>方案与评估</Button></div>
      <div className="pw-team-quick-members">{members.map((member, index) => <section key={member.id} id={`team-editor-member-${member.id}`} className="pw-team-quick-member">
        <header><TeamMemberIdentity member={member} name={teamMemberLabel(member, members)} detail={member.id === editor.leaderId ? '负责汇总' : undefined} size={32} />{members.length > 1 && <Button variant="ghost" disabled={disabled} onClick={() => { const next: Data[] = members.filter(value => value.id !== member.id).map(value => ({ ...value, dependsOn: (value.dependsOn || []).filter((id: string) => id !== member.id) })); onChange({ members: next, leaderId: editor.leaderId === member.id ? next[0].id : editor.leaderId }); }}>移除</Button>}</header>
        <TextAreaField label={`${member.name || `成员 ${index + 1}`}的职责`} value={member.role || ''} onChange={role => onMemberChange(member.id, { role })} rows={2} autoResize maxRows={5} maxLength={1200} isRequired isDisabled={disabled} />
        <Accordion variant="divided" className="pw-team-quick-member-settings"><AccordionItem id={`quick-member-${member.id}`} title="名称与模型"><TextField label="角色名称" value={member.name || ''} onChange={name => onMemberChange(member.id, { name })} maxLength={48} isRequired isDisabled={disabled} /><TextField label="使用模型" value={member.model || ''} onChange={model => onMemberChange(member.id, { model })} maxLength={200} isDisabled={disabled} placeholder="留空，使用开始时的主模型" /></AccordionItem></Accordion>
      </section>)}</div>
      <Button variant="ghost" leadingIcon={IconAdd} disabled={disabled || members.length >= 4} onClick={onAddMember}>添加角色{members.length >= 4 ? ' · 最多四名' : ''}</Button>
    </section>
    <section className="pw-team-quick-options" aria-label="协作方式">
      <SelectField label="协作方式" value={editor.mode} options={[{ id: 'collaborate', label: '共同研究' }, { id: 'debate', label: '取证与讨论' }]} onChange={mode => onChange({ mode, ...(mode === 'debate' ? { rounds: '2' } : {}) })} isDisabled={disabled} />
      <SelectField label="负责汇总" value={editor.leaderId} options={members.map(member => ({ id: member.id, label: member.name || member.id }))} onChange={leaderId => onChange({ leaderId })} isDisabled={disabled} />
      <p className="pw-field-hint">成员按各自职责推进，所选成员负责整理共同结论。</p>
      {editor.mode === 'debate' ? <p className="pw-field-hint">先各自形成观点，再相互质疑、回应与综合，按两轮额度核对。</p> : <SelectField label="推进轮数" value={editor.rounds || '1'} options={[{ id: '1', label: '一轮' }, { id: '2', label: '两轮' }]} onChange={rounds => onChange({ rounds })} isDisabled={disabled} />}
      <Accordion variant="divided" className="pw-team-quick-name"><AccordionItem id="quick-name" title="协作名称" description={editor.name}><TextField label="本次名称" value={editor.name || ''} onChange={name => onChange({ name, nameEdited: true })} maxLength={80} isRequired isDisabled={disabled} /></AccordionItem></Accordion>
    </section>
    <div className="pw-team-quick-advanced"><p>需要手机、电脑执行器或项目文件分工？</p><Button variant="ghost" trailingIcon={IconChevronRight} disabled={disabled} onClick={onAdvanced}>配置更多执行方式并保存编组</Button></div>
  </>;
}
