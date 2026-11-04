import { useEffect, useRef, useState } from 'react';
import { RiAddLine, RiArrowRightSLine, RiEditLine, RiExternalLinkLine, RiPauseLine, RiPlayLine, RiRefreshLine } from '@remixicon/react';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { AIAnswer, PressButton, ProgressBar } from './library';
import { teamMessageConfirmed, teamMessageDelivery, teamMessageRecorded } from './team-message';
import { teamEditorMember, teamPhoneChoice, teamPhoneDevice, teamPhoneScopeLabel, teamPhoneSelections, teamPhoneTargetKey, teamPhoneTargetLabel, type TeamPhoneChoice } from './team-phone';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type PendingMessage = { id: string; memberId: string; runId: string; key: string; text: string; questionId?: string };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const status = (value: string) => ({ queued: '等待工作', running: '工作中', completed: '本轮已有产出', partial: '部分分工未完成', paused: '已暂停',
  awaiting_review: '成果待审阅', awaiting_input: '等待回答', awaiting_user: '等待处理', waiting_dependency: '等待前置分工', blocked_dependency: '前置工作未完成',
  blocked_unknown: '原操作结果待核对', failed: '本轮未完成', cancelled: '已停止', interrupted: '已中断', awaiting_approval: '等待授权', needs_confirmation: '成果待确认', reconciling: '核对原操作', stopping: '正在停止' } as Record<string, string>)[value] || value;
const blankMember = (id: string, index: number) => ({ id, name: `成员 ${index + 1}`, role: '', model: '', runner: 'model' });

/** Uses the adopted BoardUI fields/dialogs and Affect answer presentation on the original team worksite. */
export function TeamsWorkspace({ state, run, registerTransientBack, alreadyShownError = '' }: { state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; alreadyShownError?: string }) {
  const [catalog, setCatalog] = useState<Data>({}), [tab, setTab] = useState('runs'), [query, setQuery] = useState('');
  const [reading, setReading] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [editor, setEditor] = useState<Data | null>(null);
  const [phoneSelection, setPhoneSelection] = useState<Data | null>(null), [phoneChoices, setPhoneChoices] = useState<Record<string, TeamPhoneChoice>>({});
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [acknowledged, setAcknowledged] = useState(false);
  const [goal, setGoal] = useState(''), [rounds, setRounds] = useState('1'), [recipient, setRecipient] = useState(''), [replyId, setReplyId] = useState(''), [mode, setMode] = useState('discussion');
  const [drafts, setDrafts] = useState<Record<string, string>>({}), [uncertainByRun, setUncertainByRun] = useState<Record<string, PendingMessage>>({});
  const uncertain = uncertainByRun[String(reading?.run?.id || '')] || null;
  const rememberUncertain = (id: string, value: PendingMessage | null) => setUncertainByRun(old => {
    const next = { ...old }; if (value) next[id] = value; else delete next[id]; return next;
  });
  const alive = useRef(true), requestGeneration = useRef(0), entered = useRef(false), currentReading = useRef<Data | null>(null);
  const backgrounded = useRef(document.visibilityState !== 'visible'), currentReview = useRef<Data | null>(null);
  const currentPhoneSelection = useRef<Data | null>(null);
  const lastResumeRevision = useRef(Number(state.webWorkspaceResumeRevision || 0));
  currentReading.current = reading; currentReview.current = review; currentPhoneSelection.current = phoneSelection;
  const invoke = async (action: string, args: Data = {}, accept?: (result: Data) => void, rejected?: (failure: unknown) => void) => {
    if (entered.current || backgrounded.current) return; entered.current = true; const generation = ++requestGeneration.current; setBusy(true); setError('');
    try { const result = object(await run(action, args)); if (alive.current && generation === requestGeneration.current) accept?.(result); return result; }
    catch (failure) { if (alive.current && generation === requestGeneration.current) { setError(failure instanceof Error ? failure.message : String(failure)); rejected?.(failure); } }
    finally { if (generation === requestGeneration.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const release = (value: Data | null, kind = 'viewHandle') => { if (value?.[kind]) void run('teams.closeReading', { [kind]: value[kind] }).catch(() => {}); };
  const acceptReading = (next: Data) => {
    release(currentReading.current); setReading(next);
    if (next.kind === 'run' && next.run?.id !== currentReading.current?.run?.id) { setRecipient(next.run.leaderId || ''); setReplyId(''); setMode('discussion'); }
    const pending = uncertainByRun[String(next.run?.id || '')];
    if (pending && teamMessageRecorded(next, pending)) {
      setDrafts(old => old[pending.key] === pending.text ? { ...old, [pending.key]: '' } : old); rememberUncertain(pending.runId, null); setNotice('已从原运行记录核对消息已保存，消费状态见原记录。');
    }
  };
  const load = () => { void invoke('teams.snapshot', {}, setCatalog); };
  const readRun = (id: string) => { void invoke('teams.readRun', { runId: id }, result => { if (result.reading?.run?.id !== id) throw new Error('原团队运行身份不匹配'); acceptReading(result.reading); }); };
  const back = () => { release(reading); setReading(null); setReplyId(''); setEditor(null); setError(''); };
  const closeReview = () => { const original = currentReview.current; currentReview.current = null; release(original, 'reviewHandle'); setReview(null); setAcknowledged(false); };
  const closePhoneSelection = () => { const original = currentPhoneSelection.current; currentPhoneSelection.current = null; release(original, 'phoneHandle'); setPhoneSelection(null); };
  useEffect(() => { alive.current = true; load(); return () => { alive.current = false; requestGeneration.current++; release(currentReading.current); release(currentReview.current, 'reviewHandle'); release(currentPhoneSelection.current, 'phoneHandle'); }; }, []);
  const restoreWorkspace = () => {
    const original = currentReading.current, generation = ++requestGeneration.current;
    closeReview(); closePhoneSelection();
    entered.current = true; setBusy(true); setReview(null); setAcknowledged(false); setError('');
    setReading(original ? { ...original, viewHandle: '' } : null);
    void (async () => {
      const fresh = object(await run('teams.snapshot'));
      if (!alive.current || generation !== requestGeneration.current) return;
      setCatalog(fresh);
      if (original?.kind === 'run') {
        const value = object(await run('teams.readRun', { runId: original.run.id }));
        if (alive.current && generation === requestGeneration.current && value.reading?.run?.id === original.run.id) acceptReading(value.reading);
      } else if (original?.kind === 'definition') {
        const value = object(await run('teams.readDefinition', { id: original.definition.id }));
        if (alive.current && generation === requestGeneration.current && value.definition?.id === original.definition.id) acceptReading(value);
      }
    })().catch(failure => { if (alive.current && generation === requestGeneration.current) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (generation === requestGeneration.current) { entered.current = false; if (alive.current) setBusy(false); } });
  };
  useEffect(() => {
    const revision = Number(state.webWorkspaceResumeRevision || 0);
    if (revision === lastResumeRevision.current) return;
    lastResumeRevision.current = revision;
    if (!backgrounded.current) restoreWorkspace();
  }, [state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const hide = () => {
      backgrounded.current = true; requestGeneration.current++; entered.current = false;
      setBusy(false); closeReview(); closePhoneSelection();
      setReading(value => value ? { ...value, viewHandle: '' } : null);
    };
    const visibility = () => {
      if (document.visibilityState !== 'visible') hide();
      else if (backgrounded.current) { backgrounded.current = false; restoreWorkspace(); }
    };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  useEffect(() => { registerTransientBack(() => { if (busy) return true; if (review) { closeReview(); return true; } if (phoneSelection) { closePhoneSelection(); return true; } if (editor) { setEditor(null); return true; } if (reading) { back(); return true; } return false; }); return () => registerTransientBack(null); }, [reading, review, phoneSelection, editor, busy, registerTransientBack]);
  const definition = reading?.kind === 'definition' ? object(reading.definition) : null;
  const definitionReady = !definition || !!reading?.viewHandle;
  const current = reading?.kind === 'run' ? object(reading.run) : null;
  const latest = current ? object(object(state.teamRuns)[current.id]) : {};
  const changed = !!current && latest.id === current.id && (latest.generation !== current.generation || latest.revision > current.revision);
  const members = rows(current?.members), messages = rows(reading?.messages);
  const reply = messages.find(message => message.messageId === replyId);
  const draftKey = `${current?.id || ''}:${recipient}:${replyId}:${mode}`, text = drafts[draftKey] || '';
  const canMutate = !!current && !!reading?.viewHandle && !busy && !changed && current.status !== 'cancelled' && !uncertain;
  const name = (id: string) => members.find(member => member.id === id)?.name || id;
  const reviewResult = (value: Data) => {
    if (value.operation === 'phone_scope') { setPhoneSelection(value); setPhoneChoices(Object.fromEntries(rows(value.view?.members).map(member => [member.memberId, teamPhoneChoice()]))); }
    else { setReview(value); setAcknowledged(false); }
  };
  const control = (command: string, memberId?: string) => { if (!reading || !canMutate) return; void invoke('teams.control', { viewHandle: reading.viewHandle, command, ...(memberId ? { memberId } : {}) }, result => { if (result.confirmed !== true) throw new Error('原操作尚未确认'); if (result.reading) acceptReading(result.reading); setNotice('已收到原运行的操作回执。'); }); };
  const send = () => {
    if (!canMutate || !reading || !text.trim() || text.length > 4000 || !members.some(member => member.id === recipient && !member.exited)) return;
    const original = { id: crypto.randomUUID(), memberId: recipient, runId: current!.id, key: draftKey, text, ...(reply?.kind === 'assignment_question' ? { questionId: reply.questionId } : {}) };
    rememberUncertain(original.runId, original);
    void invoke(reply?.kind === 'assignment_question' ? 'teams.answerAssignment' : 'teams.sendMessage', { viewHandle: reading.viewHandle, memberId: recipient, messageId: original.id, text: text.trim(), mode, quoteMessageId: replyId }, result => {
      if (!teamMessageConfirmed(result, original)) throw new Error('原消息保存回执尚待核对，请刷新原记录。');
      setDrafts(old => old[original.key] === original.text ? { ...old, [original.key]: '' } : old); rememberUncertain(original.runId, null); setNotice('已保存到原运行；进入模型与实际消费分别查看记录。');
      if (result.reading) acceptReading(result.reading); else { setReading(value => value ? { ...value, viewHandle: '' } : null); setNotice('原回答已保存，请刷新原运行查看后续状态。'); }
    }, failure => { if (['not_sent', 'not_accepted'].includes(object(failure).code)) { rememberUncertain(original.runId, null); setNotice('这次输入未提交，草稿保留；请刷新原运行后再操作。'); } });
  };
  const beginEditor = (team: Data | null) => setEditor(team ? { name: team.name, mode: team.mode, leaderId: team.leaderId, members: rows(team.members).map(teamEditorMember) } : { name: '', mode: 'collaborate', leaderId: 'member-0', members: [blankMember('member-0', 0), blankMember('member-1', 1)] });
  const updateMember = (id: string, changes: Data) => setEditor(value => value ? { ...value, members: rows(value.members).map(member => member.id === id ? { ...member, ...changes } : member) } : null);
  const phoneTargets = rows(catalog.phoneTargets);
  const phoneMembers = rows(phoneSelection?.view?.members);
  const hasPhoneMembers = rows(definition?.members).some(member => member.runner === 'phone_agent');
  const choosePhoneScope = (member: Data, id: string) => setPhoneChoices(value => ({ ...value, [member.memberId]: teamPhoneChoice(rows(member.options).find(option => option.optionId === id)) }));
  const changePhoneBudget = (memberId: string, field: keyof TeamPhoneChoice, value: string) => setPhoneChoices(old => ({ ...old, [memberId]: { ...old[memberId], [field]: value } }));
  const reviewPhoneStart = () => {
    if (!phoneSelection || busy) return;
    try {
      const members = teamPhoneSelections(object(phoneSelection.view), phoneChoices);
      const phoneHandle = phoneSelection.phoneHandle; currentPhoneSelection.current = null; setPhoneSelection(null);
      void invoke('teams.reviewPhoneStart', { phoneHandle, members }, reviewResult);
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const entries = rows(catalog[tab === 'runs' ? 'runs' : 'teams']).filter(row => `${row.goal || ''} ${row.name || ''} ${row.teamName || ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const refreshReading = <IconButton className="pw-utility-icon" icon={RiRefreshLine} aria-label="刷新团队记录" title="刷新团队记录" disabled={busy} onClick={() => current ? readRun(current.id) : definition ? void invoke('teams.readDefinition', { id: definition.id }, acceptReading) : load()} />;
  return <section className="pw-team-workspace" data-team-layer={reading ? 'detail' : 'list'}>
    {busy && <ProgressBar label="正在读取原团队回执" isIndeterminate showValueLabel={false} size="sm" />}
    {error && error !== alreadyShownError && <p className="pw-secondary-error" role="alert">{error}</p>}{notice && <p className="pw-field-hint" role="status">{notice}</p>}
    {!reading && <Tabs selectedKey={tab} onSelectionChange={value => { setTab(String(value)); setQuery(''); }} className="pw-team-tabs"><div className="pw-object-toolbar"><TabList aria-label="团队视图"><Tab id="runs">共同工作</Tab><Tab id="teams">团队配置</Tab></TabList><IconButton className="pw-utility-icon" icon={RiRefreshLine} aria-label="刷新团队记录" title="刷新团队记录" disabled={busy} onClick={load} /></div><TabPanel id={tab}><SearchField className="pw-secondary-search" data-input-size="medium" aria-label="搜索当前团队记录" placeholder="查找团队" value={query} onChange={setQuery} shape="rounded" />
      <div className="pw-object-list">{entries.map(row => <Button key={row.id} variant="ghost" trailingIcon={RiArrowRightSLine} className="pw-object-row h-auto whitespace-normal" disabled={busy} onClick={() => tab === 'runs' ? readRun(row.id) : void invoke('teams.readDefinition', { id: row.id }, acceptReading)}><span className="pw-object-row-copy"><span className="pw-object-row-title">{row.goal || row.name || row.teamName || '团队工作'}</span><small>{row.status ? status(row.status) : `${rows(row.members).length} 名成员`}</small></span></Button>)}</div>
      {!entries.length && <p className="pw-secondary-empty">当前读取窗口没有匹配记录。</p>}{tab === 'teams' && <Button variant="secondary" leadingIcon={RiAddLine} disabled={busy} onClick={() => beginEditor(null)}>新建团队</Button>}
    </TabPanel></Tabs>}
    {definition && <article className="pw-team-reading"><header className="pw-object-toolbar"><div><h2>{definition.name}</h2><p>{definition.mode === 'produce' ? '原项目文件协作' : definition.mode === 'debate' ? '取证与讨论' : hasPhoneMembers ? '手机分工与共同工作' : '共同分析'}</p></div>{refreshReading}</header>
      <section className="pw-team-members"><h3>成员与职责</h3>{rows(definition.members).map(member => <div key={member.id} className="pw-source-record"><h4>{member.name}{member.id === definition.leaderId ? ' · 负责人' : ''}</h4><p>{member.role}</p><small>{member.runner === 'phone_agent' ? teamPhoneTargetLabel(member.device, phoneTargets) : `${member.model || '沿用实际启动时的主模型'} · ${member.runner === 'model' ? '本机模型连接' : member.runner}`}</small>{member.runner === 'phone_agent' && <p>{member.assignment}</p>}{member.runner !== 'model' && member.runner !== 'phone_agent' && <Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.probeMember', { viewHandle: reading!.viewHandle, memberId: member.id }, value => setNotice(value.result?.available === true ? '原执行器本次探测可用。' : value.result?.message || '本次未确认执行器可用。'))}>核对原执行器</Button>}</div>)}</section>
      <div className="pw-form-actions"><IconButton className="pw-utility-icon" icon={RiEditLine} aria-label="编辑下次工作的团队" title="编辑下次工作的团队" disabled={busy || !definitionReady} onClick={() => beginEditor(definition)} /><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.reviewRemove', { viewHandle: reading!.viewHandle }, reviewResult)}>移除团队配置</Button></div>
      {definition.mode === 'produce' ? <p className="pw-muted">文件团队沿原项目任务的计划、文件范围和预算启动；这里保留其成员与既有工作记录。</p> : <><TextAreaField label="这次共同目标" value={goal} onChange={setGoal} isDisabled={busy} />{!hasPhoneMembers && <SelectField label="讨论轮数" value={rounds} options={[{ id: '1', label: '一轮' }, { id: '2', label: '两轮' }]} onChange={setRounds} isDisabled={busy} hint="结构化证据讨论沿原生协议展开；实际调用与累计预算由原任务控制。" />}{hasPhoneMembers && <p className="pw-field-hint">手机成员按各自分工与前置依赖工作。本机可同时使用至多四个后台屏幕；启动前逐成员核对应用范围与预算。</p>}<PressButton label={hasPhoneMembers ? '选择手机范围并开始' : '核对并开始协作'} disabled={busy || !definitionReady || !goal.trim() || goal.length > 8000 || !!state.modelSelectionBusy} onClick={() => void invoke('teams.reviewStart', { viewHandle: reading!.viewHandle, goal, rounds: hasPhoneMembers ? 1 : Number(rounds) }, reviewResult)} /></>}
    </article>}
    {current && <article className="pw-team-reading"><header className="pw-object-toolbar"><div><h2>{current.goal || current.teamName}</h2><p className="pw-detail-status">{status(current.status)}{current.phase ? ` · ${current.phase}` : ''}</p></div>{refreshReading}</header>
      {changed && <p role="status">有新的运行记录。刷新后再发送补充或操作，当前阅读位置保留。</p>}
      <div className="pw-form-actions"><IconButton className="pw-utility-icon" icon={RiPauseLine} aria-label="暂停全队" title="暂停全队" disabled={!canMutate} onClick={() => control('pause')} /><IconButton className="pw-utility-icon" icon={RiPlayLine} aria-label="沿原额度继续" title="沿原额度继续" disabled={!canMutate} onClick={() => control('resume')} /><Button variant="ghost" disabled={!canMutate} onClick={() => reviewResult({ operation: 'cancel', viewHandle: reading!.viewHandle, runId: current.id })}>停止这次协作</Button>{reading?.taskId && <IconButton className="pw-utility-icon" icon={RiExternalLinkLine} aria-label="查看原任务与审批" title="查看原任务与审批" onClick={() => void run('tasks.open', { id: reading.taskId }).catch(() => {})} />}</div>
      <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="team-members" title="成员、分工与成果">{members.map(member => <section className="pw-source-record" key={member.id}><h3>{member.name} · {status(member.status)}</h3><p>{member.assignment || member.role}</p>{member.runner === 'phone_agent' && <p className="pw-muted">{teamPhoneTargetLabel(member.device, phoneTargets)}</p>}{member.acceptance && <p>验收：{member.acceptance}</p>}<p>{rows(member.dependsOn).length ? `前置：${member.dependsOn.map(name).join('、')}` : ''}</p><div className="pw-form-actions">{member.phoneTask?.taskId && <Button variant="ghost" onClick={() => void run('tasks.open', { id: member.phoneTask.taskId }).catch(() => {})}>查看手机任务与结果</Button>}<Button variant="ghost" disabled={!canMutate || member.exited} onClick={() => control('pause', member.id)}>暂停该成员</Button><Button variant="ghost" disabled={!canMutate || member.exited} onClick={() => control('resume', member.id)}>继续该成员</Button><Button variant="ghost" disabled={!canMutate || member.exited} onClick={() => control('continueMessages', member.id)}>使用该成员的待处理消息</Button></div></section>)}{current.summary && <AIAnswer text={current.summary} />}</AccordionItem></Accordion>
      <section aria-label="原团队消息">{messages.map(message => <article className="pw-source-record" key={message.messageId}><header><h3>{message.role === 'user' ? '你' : message.memberSnapshot?.name || name(message.memberId || '') || '团队'}{message.recipientMemberId ? ` → ${name(message.recipientMemberId)}` : ''}</h3></header><AIAnswer text={String(message.content || '')} /><p className="pw-muted">{teamMessageDelivery(current, message)}</p>{message.answer && <p>已保存的回答：{message.answer}</p>}{(message.kind !== 'assignment_question' || message.state === 'pending') && <Button variant="ghost" disabled={!canMutate} onClick={() => { setReplyId(message.messageId); if (members.some(member => member.id === message.memberId && !member.exited)) setRecipient(message.memberId); }}>{message.kind === 'assignment_question' ? '回答原问题' : '引用回复'}</Button>}</article>)}</section>
      {reply && <div className="pw-form-actions"><p>回复：{String(reply.content || '').slice(0, 160)}</p><Button variant="ghost" disabled={busy || !!uncertain} onClick={() => setReplyId('')}>取消引用</Button></div>}
      <SelectField label="收件成员" value={recipient} options={members.filter(member => !member.exited).map(member => ({ id: member.id, label: member.name }))} onChange={setRecipient} isDisabled={busy || !!uncertain || reply?.kind === 'assignment_question'} />
      {reply?.kind !== 'assignment_question' && <SelectField label="补充方式" value={mode} options={[{ id: 'discussion', label: '讨论补充' }, { id: 'queue', label: '排入下一安全工作点' }]} onChange={setMode} isDisabled={busy || !!uncertain} />}
      <TextAreaField label={reply?.kind === 'assignment_question' ? '回答这条原问题' : '发给所选成员'} value={text} onChange={value => setDrafts(old => ({ ...old, [draftKey]: value }))} isDisabled={busy || !!uncertain} hint="消息保存、进入模型与实际消费分别显示；不会通过普通消息追加授权或预算。" />
      <PressButton label={reply?.kind === 'assignment_question' ? '保存原回答' : '发送补充'} disabled={!canMutate || !text.trim() || text.length > 4000 || !recipient} onClick={send} />
      {uncertain && <p role="status">这条原消息仍待核对，草稿保留。刷新原记录核对保存结果，没有自动重复发送。</p>}
    </article>}
    <SettingsDialog isOpen={!!phoneSelection} title="这次手机分工的范围" onClose={() => { if (!busy) closePhoneSelection(); }}>{phoneSelection && <>
      <p>{phoneSelection.view?.goal}</p>
      <p className="pw-field-hint">每名成员选择自己的手机与应用范围；同一应用、同一 Android 用户仍会协调占用。</p>
      {phoneMembers.map(member => {
        const choice = phoneChoices[member.memberId] || teamPhoneChoice(), selected = rows(member.options).find(option => option.optionId === choice.optionId);
        return <section className="pw-source-record" key={member.memberId}>
          <h3>{member.name}</h3><p className="pw-muted">{teamPhoneTargetLabel(member.device, phoneTargets)}</p>
          <SelectField label="应用范围" value={choice.optionId} options={[{ id: '', label: '选择这名成员可使用的范围' }, ...rows(member.options).filter(option => option.available === true).map(option => ({ id: option.optionId, label: option.label }))]} onChange={id => choosePhoneScope(member, id)} isDisabled={busy} />
          {!rows(member.options).some(option => option.available === true) && <p role="status">{rows(member.options).find(option => option.reason)?.reason || '当前手机没有可用范围，请在设备页核对连接与应用权限后重新读取。'}</p>}
          {selected && <><p className="pw-field-hint">{teamPhoneScopeLabel(selected.resourceBinding)}</p>
            <TextField label="最多操作次数" value={choice.toolBudget} onChange={value => changePhoneBudget(member.memberId, 'toolBudget', value)} isDisabled={busy} hint={`原范围最多 ${selected.toolBudgetMax} 次`} />
            <TextField label="最多模型调用次数" value={choice.modelBudget} onChange={value => changePhoneBudget(member.memberId, 'modelBudget', value)} isDisabled={busy} hint={`原范围最多 ${selected.modelBudgetMax} 次；${member.device?.kind === 'paired_android' ? '由目标手机确认执行模型' : '使用本机当前已选的 API 模型'}`} />
            <TextField label="本次有效分钟数" value={choice.durationMinutes} onChange={value => changePhoneBudget(member.memberId, 'durationMinutes', value)} isDisabled={busy} hint={`不能晚于 ${new Date(selected.expiresAtMax).toLocaleString('zh-CN')}`} />
          </>}
        </section>;
      })}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closePhoneSelection}>返回</Button><Button disabled={busy || !phoneMembers.length || phoneMembers.some(member => !phoneChoices[member.memberId]?.optionId)} onClick={reviewPhoneStart}>核对本次范围</Button></div>
    </>}</SettingsDialog>
    <SettingsDialog isOpen={!!editor} title={definition ? '编辑下次工作的团队' : '新建团队'} onClose={() => { if (!busy) setEditor(null); }}>{editor && <>
      <TextField label="团队名称" value={editor.name} onChange={value => setEditor({ ...editor, name: value })} isDisabled={busy} />
      <SelectField label="协作方式" value={editor.mode} options={[{ id: 'collaborate', label: '共同工作' }, ...(!rows(editor.members).some(member => member.runner === 'phone_agent') ? [{ id: 'debate', label: '取证与讨论' }] : []), ...(editor.mode === 'produce' ? [{ id: 'produce', label: '原项目文件协作' }] : [])]} onChange={value => setEditor({ ...editor, mode: value })} isDisabled={busy || definition?.mode === 'produce'} />
      {rows(editor.members).map((member, index) => {
        const original = rows(definition?.members).find(row => row.id === member.id), external = !['model', 'phone_agent'].includes(member.runner || 'model');
        const targetKey = teamPhoneTargetKey(member.device), targetOptions = phoneTargets.map(target => ({ id: teamPhoneTargetKey(target), label: `${target.label}${target.available === false ? ' · 暂不可用' : ''}` }));
        if (targetKey && !targetOptions.some(target => target.id === targetKey)) targetOptions.push({ id: targetKey, label: '原手机目标 · 当前不可用' });
        return <section className="pw-source-record" key={member.id}>
          <TextField label={`成员 ${index + 1} 名称`} value={member.name} onChange={value => updateMember(member.id, { name: value })} isDisabled={busy} />
          <TextAreaField label="职责" value={member.role} onChange={value => updateMember(member.id, { role: value })} isDisabled={busy} />
          <SelectField label="成员做什么" value={member.runner || 'model'} options={external ? [{ id: member.runner, label: `原电脑执行器 · ${member.runner}` }] : [{ id: 'model', label: '分析与讨论' }, ...(editor.mode === 'collaborate' ? [{ id: 'phone_agent', label: '操作手机完成分工' }] : [])]} isDisabled={busy || external || !!original?.modelTarget} onChange={runner => updateMember(member.id, runner === 'phone_agent' ? { runner, model: '', device: { kind: 'local_phone', screenMode: 'background' }, assignment: member.assignment || member.role, dependsOn: member.dependsOn || [] } : { runner, device: undefined, assignment: undefined, dependsOn: undefined })} />
          {member.runner === 'phone_agent' ? <>
            <SelectField label="在哪台手机上工作" value={targetKey} options={targetOptions} isDisabled={busy} onChange={key => { const target = phoneTargets.find(value => teamPhoneTargetKey(value) === key); if (target) updateMember(member.id, { device: teamPhoneDevice(target) }); }} hint="应用范围与预算在每次启动前核对。" />
            <TextAreaField label="具体分工" value={member.assignment || ''} onChange={value => updateMember(member.id, { assignment: value })} isDisabled={busy} />
            <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id={`phone-dependencies-${member.id}`} title="先等待哪些成员完成">{rows(editor.members).filter(row => row.id !== member.id).map(row => <CheckboxField key={row.id} isSelected={(member.dependsOn || []).includes(row.id)} isDisabled={busy} onChange={selected => updateMember(member.id, { dependsOn: selected ? [...(member.dependsOn || []), row.id] : (member.dependsOn || []).filter((id: string) => id !== row.id) })}>{row.name || row.id}</CheckboxField>)}<p className="pw-field-hint">不选择前置成员时，可与其他独立分工同时开始。</p></AccordionItem></Accordion>
          </> : <TextField label="模型 ID，留空沿用主模型" value={member.model} onChange={value => updateMember(member.id, { model: value })} isDisabled={busy || !!original?.modelTarget} />}
          {!definition && <Button variant="ghost" disabled={busy || editor.members.length <= 1} onClick={() => { const next = rows(editor.members).filter(row => row.id !== member.id); setEditor({ ...editor, members: next, leaderId: editor.leaderId === member.id ? next[0].id : editor.leaderId }); }}>移除此成员</Button>}
        </section>;
      })}
      {!definition && <Button variant="ghost" disabled={busy || editor.members.length >= 4} onClick={() => setEditor({ ...editor, members: [...editor.members, blankMember(`member-${crypto.randomUUID().slice(0, 8)}`, editor.members.length)] })}>添加成员</Button>}
      <SelectField label="负责人" value={editor.leaderId} options={rows(editor.members).map(member => ({ id: member.id, label: member.name || member.id }))} onChange={value => setEditor({ ...editor, leaderId: value })} isDisabled={busy} />
      <p className="pw-muted">保存只影响后续新工作；已有运行记录保留。选择手机成员后，每次启动会再次核对它的应用范围。</p><Button disabled={busy || !definitionReady || !editor.name.trim() || rows(editor.members).some(member => !member.name.trim() || !member.role.trim() || member.runner === 'phone_agent' && (!member.assignment?.trim() || !teamPhoneTargetKey(member.device)))} onClick={() => void invoke('teams.reviewDefinition', { ...editor, ...(definition ? { viewHandle: reading!.viewHandle } : {}) }, reviewResult)}>核对本次配置</Button>
    </>}</SettingsDialog>
    <SettingsDialog isOpen={!!review} title={review?.operation === 'start' ? '开始这次原团队协作' : review?.operation === 'cancel' ? '停止这次协作' : review?.operation === 'remove' ? '移除团队配置' : '保存团队配置'} onClose={() => { if (!busy) closeReview(); }}>
      {review?.operation === 'start' ? <><p>{review.input?.goal}</p><p>{review.definition?.name} · {rows(review.definition?.members).map(member => member.name).join('、')}</p>{review.phoneScope ? <><p>手机成员将使用下列已核对的范围，模型调用会计入对应任务的额度。</p>{rows(review.phoneScope.members).map(member => <section className="pw-source-record" key={member.memberId}><h3>{member.name || rows(review.definition?.members).find(value => value.id === member.memberId)?.name}</h3><p>{teamPhoneTargetLabel(member.device, phoneTargets)}{teamPhoneScopeLabel(member.resourceBinding) ? ` · ${teamPhoneScopeLabel(member.resourceBinding)}` : ''}</p><p>最多 {member.toolBudget} 次操作 · {member.modelBudget} 次模型调用</p>{member.sourceQueryToolReserve > 0 && <p className="pw-field-hint">另预留 {member.sourceQueryToolReserve} 次目标任务状态查询。</p>}<p className="pw-muted">有效至 {new Date(member.expiresAt).toLocaleString('zh-CN')}</p></section>)}</> : <p>沿原团队与实际启动时的模型配置开始，可能消耗模型额度。文件写入与额外预算继续使用原任务的独立审批。</p>}</> : review?.operation === 'cancel' ? <p>停止这个原运行后续工作，保留消息、证据和已有成果。停止请求不代表未知外部操作已完成清理。</p> : review?.operation === 'remove' ? <p>移除“{review.definition?.name}”的后续启动配置，已有运行和成果保留。</p> : <><p>{review?.input?.name}</p>{rows(review?.input?.members).map(member => <p key={member.id}>{member.name}：{member.role}</p>)}</>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>已核对这次操作与原目标</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={() => {
        const original = review; if (!original) return; setReview(null); setAcknowledged(false);
        void invoke(original.operation === 'cancel' ? 'teams.control' : 'teams.confirm', original.operation === 'cancel' ? { command: 'cancel', viewHandle: original.viewHandle } : { reviewHandle: original.reviewHandle }, result => {
          if (result.confirmed !== true) throw new Error('原团队操作尚未确认，请刷新核对。'); setEditor(null); setNotice('已收到原操作回执。');
          if (result.reading) { acceptReading(result.reading); if (original.operation === 'start') setGoal(''); }
          else { release(reading); setReading(null); setTab('teams'); setCatalog(old => ({ ...old, teams: result.command === 'save' ? [...rows(old.teams).filter(team => team.id !== result.result.id), result.result] : rows(old.teams).filter(team => team.id !== original.input?.id) })); }
        });
      }}>确认这一次操作</Button></div>
    </SettingsDialog>
  </section>;
}
