import { useEffect, useRef, useState } from 'react';
import './collaboration-depth.css';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { emptyTeamEvidence, teamEvidenceSelection, TeamEvidenceConsent, type TeamEvidenceDraft } from './team-evidence';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];

export function TeamEvidenceWorkspace({ runId, members, run, disabled = false, onBackChange }: {
  runId: string; members: Data[]; run: Run; disabled?: boolean; onBackChange?: (handler: (() => boolean) | null) => void;
}) {
  const activeMembers = members.filter(member => !member.exited);
  const [expanded, setExpanded] = useState(false), [memberId, setMemberId] = useState(activeMembers[0]?.id || '');
  const [page, setPage] = useState<Data | null>(null), [files, setFiles] = useState<Data[]>([]), [project, setProject] = useState('');
  const [fileChoices, setFileChoices] = useState<Record<string, Data>>({});
  const [draft, setDraft] = useState<TeamEvidenceDraft>(emptyTeamEvidence), [review, setReview] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [acknowledged, setAcknowledged] = useState(false), [notice, setNotice] = useState(''), [error, setError] = useState('');
  const pageHandle = useRef(''), reviewHandle = useRef(''), generation = useRef(0), alive = useRef(true), entered = useRef(false);
  const consent = useRef(new TeamEvidenceConsent()), shownReview = useRef<Data | null>(null);
  shownReview.current = review;
  const version = generation.current, confirmTicket = consent.current.capture();
  const closeReview = () => {
    const handle = reviewHandle.current; reviewHandle.current = ''; consent.current.clear(); shownReview.current = null;
    setReview(null); setAcknowledged(false);
    if (handle) void run('teamEvidence.cancelReview', { reviewHandle: handle }).catch(() => {});
  };
  const closePage = () => {
    generation.current++; entered.current = false; closeReview(); const handle = pageHandle.current; pageHandle.current = '';
    if (handle) void run('teamEvidence.close', { pageHandle: handle }).catch(() => {});
    setPage(null); setFiles([]); setFileChoices({}); setProject(''); setDraft(emptyTeamEvidence()); setBusy(false);
  };
  const invoke = async (action: string, args: Data, accept?: (value: Data) => void) => {
    const ticket = version;
    if (!alive.current || ticket !== generation.current || entered.current || disabled) return;
    entered.current = true; setBusy(true); setError('');
    try {
      const result = await run(action, args);
      if (alive.current && ticket === generation.current) { accept?.(result); return result; }
      if (result?.pageHandle) void run('teamEvidence.close', { pageHandle: result.pageHandle }).catch(() => {});
      if (result?.reviewHandle) void run('teamEvidence.cancelReview', { reviewHandle: result.reviewHandle }).catch(() => {});
    } catch (failure) { if (alive.current && ticket === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (ticket === generation.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const load = () => {
    if (entered.current || !memberId || disabled || version !== generation.current) return;
    const previous = pageHandle.current; pageHandle.current = ''; if (previous) void run('teamEvidence.close', { pageHandle: previous }).catch(() => {});
    closeReview(); setPage(null); setFiles([]); setFileChoices({}); setProject(''); setDraft(emptyTeamEvidence());
    void invoke('teamEvidence.open', { runId, memberId }, value => { pageHandle.current = value.pageHandle; setPage(value); setNotice(''); });
  };
  const edit = (next: TeamEvidenceDraft) => { if (entered.current || disabled || version !== generation.current) return; generation.current++; closeReview(); setDraft(next); };
  const toggle = (key: 'records' | 'projectFiles', handle: string, checked: boolean) => edit({ ...draft, [key]: checked ? [...draft[key], handle] : draft[key].filter(value => value !== handle) });
  const toggleObject = (key: 'sources' | 'skills' | 'resources', handle: string, checked: boolean) => edit({ ...draft, [key]: checked ? [...draft[key], { choiceHandle: handle, ...(key === 'skills' ? { paths: 'SKILL.md' } : { offset: '0', chars: key === 'sources' ? '24000' : '12000' }) }] : draft[key].filter(value => value.choiceHandle !== handle) });
  const range = (key: 'sources' | 'skills' | 'resources', handle: string, field: string, value: string) => edit({ ...draft, [key]: draft[key].map(row => row.choiceHandle === handle ? { ...row, [field]: value } : row) });
  const showReview = (value: Data) => { reviewHandle.current = value.reviewHandle; consent.current.show(value.reviewHandle); setReview(value); setAcknowledged(false); };
  const prepare = (scope: 'member' | 'current_members') => {
    if (!pageHandle.current || entered.current || version !== generation.current) return;
    try { const selection = teamEvidenceSelection(draft); closeReview(); void invoke('teamEvidence.review', { pageHandle: pageHandle.current, scope, ...selection }, showReview); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const acknowledge = (accepted: boolean) => {
    if (!review || reviewHandle.current !== review.reviewHandle || entered.current) return;
    consent.current.change(accepted); const ticket = consent.current.capture(); setAcknowledged(false);
    void invoke('teamEvidence.consent', { reviewHandle: review.reviewHandle, accepted }, value => { if (consent.current.current(ticket)) setAcknowledged(value.accepted === true); });
  };
  const confirm = () => {
    if (!review || shownReview.current !== review || !acknowledged || !consent.current.confirm(confirmTicket)) return;
    const handle = review.reviewHandle;
    void invoke('teamEvidence.confirm', { reviewHandle: handle }, value => {
      if (value.scopeSaved !== true) throw new Error('原范围保存尚未核对，请重新读取原成员。');
      reviewHandle.current = ''; consent.current.clear(); setReview(null); setAcknowledged(false);
      setPage(previous => previous ? { ...previous, current: value.current } : previous);
      setNotice(review.operation === 'revoke' ? '已撤回后续资料读取。此前已经提供给模型的内容仍保留真实历史。' : '已收到原范围保存回执。新增成员不会继承；没有开始研究或追加执行预算。');
    });
  };
  const identities = activeMembers.map(member => [member.id, member.identityRevision, member.runner, member.model, member.memberModelSelection]);
  useEffect(() => { closePage(); setMemberId(activeMembers.some(member => member.id === memberId) ? memberId : activeMembers[0]?.id || ''); }, [runId, JSON.stringify(identities)]);
  useEffect(() => {
    alive.current = true;
    const hidden = () => { if (document.visibilityState !== 'visible') closePage(); };
    document.addEventListener('visibilitychange', hidden);
    return () => { alive.current = false; generation.current++; const page = pageHandle.current, review = reviewHandle.current; pageHandle.current = ''; reviewHandle.current = ''; consent.current.clear(); if (review) void run('teamEvidence.cancelReview', { reviewHandle: review }).catch(() => {}); if (page) void run('teamEvidence.close', { pageHandle: page }).catch(() => {}); document.removeEventListener('visibilitychange', hidden); };
  }, []);
  useEffect(() => { onBackChange?.(() => { if (busy) return true; if (review) { closeReview(); return true; } if (expanded) { closePage(); setExpanded(false); return true; } return false; }); return () => onBackChange?.(null); }, [busy, review, expanded, onBackChange]);
  const catalogue = page?.catalogue || {};
  const selectedCount = draft.records.length + draft.sources.length + draft.skills.length + draft.resources.length + draft.projectFiles.length + draft.webOrigins.split('\n').filter(site => site.trim()).length;
  return <section className="pw-team-overview pw-team-evidence" aria-label="成员资料范围">
    <Button variant="ghost" aria-expanded={expanded} disabled={busy || disabled} onClick={() => { if (expanded) closePage(); setExpanded(!expanded); }}>{expanded ? '收起成员资料范围' : '成员能读哪些资料'}</Button>
    {expanded && <div className="pw-team-evidence-content">
      <p className="pw-field-hint">共同目标与发给团队的消息沿原协作共享。个人记忆、原文、技能、附件与项目文件在这里逐项选择，再确认给所列成员。</p>
      <section className="pw-team-evidence-recipient" aria-label="资料接收成员"><SelectField label="核对哪名成员" value={memberId} options={activeMembers.map(member => ({ id: member.id, label: member.name || member.id }))} isDisabled={busy || disabled} onChange={value => { closePage(); setMemberId(value); }} />
      <Button variant="secondary" disabled={busy || disabled || !memberId} onClick={load}>{page ? '重新读取原目录与范围' : '读取原成员资料目录'}</Button></section>
      {busy && <p className="pw-muted" role="status">正在核对原目录与范围…</p>}{error && <p className="pw-secondary-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
      {page && <>
        <section className="pw-team-evidence-current-state" aria-label="当前资料范围"><header className="pw-collaboration-group-heading"><h3>当前范围</h3><span>修订 {page.current?.revision ?? '未知'}</span></header><p>{page.current?.state === 'active' ? '已分享给这名成员' : page.current?.state === 'revoked' ? '后续读取已撤回' : '尚未分享资料'}</p>
        <Accordion className="pw-team-evidence-current"><AccordionItem title="已分享的准确版本与来源"><EvidenceScope scope={page.current} />{page.current?.state === 'active' && <div className="pw-form-actions"><Button variant="ghost" disabled={busy || disabled} onClick={() => { closeReview(); void invoke('teamEvidence.renew', { pageHandle: page.pageHandle }, showReview); }}>续审相同材料与检查点</Button><Button variant="ghost" disabled={busy || disabled} onClick={() => { closeReview(); void invoke('teamEvidence.revoke.review', { pageHandle: page.pageHandle }, showReview); }}>核对撤回此成员资料</Button></div>}</AccordionItem></Accordion></section>
        <header className="pw-collaboration-group-heading"><h3>待核对清单</h3><span>已选择 {selectedCount} 项</span></header><p className="pw-field-hint">下面的选择尚未生效。</p>
        <Accordion variant="divided" className="pw-compact-disclosures" allowsMultipleExpanded>
          <AccordionItem id="records" title="正式记忆" description={`已选 ${draft.records.length} · 可选 ${rows(catalogue.records).length} 个版本`}>
            {rows(catalogue.records).map(row => <CheckboxField key={row.choiceHandle} isSelected={draft.records.includes(row.choiceHandle)} isDisabled={busy || disabled} onChange={checked => toggle('records', row.choiceHandle, checked)}>{row.text} · 修订 {row.revision}</CheckboxField>)}
            {!rows(catalogue.records).length && <p className="pw-muted">当前目录没有可选的有效正式记忆。</p>}
          </AccordionItem>
          <AccordionItem id="sources" title="原文的确切范围" description={`已选 ${draft.sources.length} · 可选 ${rows(catalogue.sources).length} 项`}>
            {rows(catalogue.sources).map(row => { const choice = draft.sources.find(value => value.choiceHandle === row.choiceHandle); return <section key={row.choiceHandle}><CheckboxField isSelected={!!choice} isDisabled={busy || disabled} onChange={checked => toggleObject('sources', row.choiceHandle, checked)}>{row.sourceRef?.kind} · {row.sourceRef?.sourceId} · 修订 {row.sourceRef?.revision}</CheckboxField>{choice && <SourceRange value={choice} disabled={busy || disabled} onChange={(key, value) => range('sources', row.choiceHandle, key, value)} />}</section>; })}
          </AccordionItem>
          <AccordionItem id="skills" title="技能资料" description={`已选 ${draft.skills.length} · 可选 ${rows(catalogue.skills).length} 个版本`}>
            <p className="pw-field-hint">选择文档资源不授权脚本执行。</p>
            {rows(catalogue.skills).map(row => { const choice = draft.skills.find(value => value.choiceHandle === row.choiceHandle); return <section key={row.choiceHandle}><CheckboxField isSelected={!!choice} isDisabled={busy || disabled} onChange={checked => toggleObject('skills', row.choiceHandle, checked)}>{row.name || row.id} · 修订 {row.revision}</CheckboxField>{choice && <TextAreaField label="只允许这些准确资源路径，每行一个" value={choice.paths} onChange={value => range('skills', row.choiceHandle, 'paths', value)} isDisabled={busy || disabled} />}</section>; })}
          </AccordionItem>
          <AccordionItem id="resources" title="附件与冻结文件" description={`已选 ${draft.resources.length} · 可选 ${rows(catalogue.resources).length} 项`}>
            {rows(catalogue.resources).map(row => { const choice = draft.resources.find(value => value.choiceHandle === row.choiceHandle); return <section key={row.choiceHandle}><CheckboxField isSelected={!!choice} isDisabled={busy || disabled} onChange={checked => toggleObject('resources', row.choiceHandle, checked)}>{row.name || row.id}</CheckboxField>{choice && <SourceRange value={choice} disabled={busy || disabled} onChange={(key, value) => range('resources', row.choiceHandle, key, value)} />}</section>; })}
          </AccordionItem>
          {catalogue.researchReadAvailable && <AccordionItem id="research" title="持续研究可读的站点与项目文件">
            <TextAreaField label="公共 HTTPS 站点，每行一个，最多四个" value={draft.webOrigins} onChange={value => edit({ ...draft, webOrigins: value })} placeholder="https://example.org" maxLength={8192} isDisabled={busy || disabled} />
            <p className="pw-field-hint">按明确站点匿名读取；不继承浏览器登录、Cookie 或账号凭据。</p>
            <SelectField label="从原项目选择文件" value={project} options={rows(catalogue.researchProjects).map(row => ({ id: row.choiceHandle, label: row.name || row.id }))} isDisabled={busy || disabled} onChange={value => { closeReview(); setProject(value); setFiles([]); void invoke('teamEvidence.files', { pageHandle: page.pageHandle, projectHandle: value }, result => { const listed = rows(result.files); setFiles(listed); setFileChoices(old => ({ ...old, ...Object.fromEntries(listed.map(file => [file.choiceHandle, file])) })); }); }} />
            {files.map(file => { const selected = draft.projectFiles.find(handle => fileChoices[handle]?.projectId === file.projectId && fileChoices[handle]?.path === file.path); return <CheckboxField key={file.choiceHandle} isSelected={!!selected} isDisabled={busy || disabled} onChange={checked => toggle('projectFiles', selected || file.choiceHandle, checked)}>{file.path} · 版本 {file.version}</CheckboxField>; })}
            <p className="pw-muted">已选择 {draft.projectFiles.length} 个项目文件。核对时固定原版本和字节指纹；换版后不会自动扩大读取。</p>
            {draft.projectFiles.map(handle => <div className="pw-form-actions" key={handle}><span>{fileChoices[handle]?.path || '原项目文件'} · 版本 {fileChoices[handle]?.version ?? '待核对'}</span><Button variant="ghost" disabled={busy || disabled} onClick={() => toggle('projectFiles', handle, false)}>移出此文件</Button></div>)}
          </AccordionItem>}
        </Accordion>
        <footer className="pw-team-evidence-actions"><Button variant="secondary" disabled={busy || disabled} onClick={() => prepare('member')}>核对本成员的准确范围</Button><Button variant="ghost" disabled={busy || disabled} onClick={() => prepare('current_members')}>核对给当前成员共享</Button></footer>

      </>}
    </div>}
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={review?.operation === 'revoke' ? '撤回后续资料读取' : review?.scope === 'current_members' ? '核对本次当前成员与同一资料范围' : '核对本成员读取范围'}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamEvidenceWorkspace">
      {review?.scope === 'current_members' ? <>{rows(review.review?.members).map(target => <section key={target.memberId}><h3>{members.find(member => member.id === target.memberId)?.name || target.memberId}</h3><EvidenceScope scope={target.review} /></section>)}<p>仅这次列出的成员收到同一显选范围，未来新增身份不会继承。</p></> : <><h3>{members.find(member => member.id === memberId)?.name || memberId}</h3><EvidenceScope scope={review?.review} /></>}
      <p>{review?.operation === 'revoke' ? '撤回后续读取与答案接纳资格。已经提供给模型的历史内容如实保留。' : review?.review?.reviewMode === 'renew_identical_materials' ? '重新核对相同材料，保留原研究检查点与已用次数；旧票据失效，没有自动继续。' : '只分享所示准确资料，不增加写入、执行或模型与工具预算；空集合不会回退为默认私人范围。'}</p>
      <CheckboxField isSelected={acknowledged} onChange={acknowledge} isDisabled={busy}>我确认这次所列成员与准确资料范围</CheckboxField>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回核对</Button><Button disabled={busy || !acknowledged || disabled} onClick={confirm}>{review?.operation === 'revoke' ? '确认撤回' : '确认这一次范围'}</Button></div>
    </div></SettingsDialog>
  </section>;
}

function SourceRange({ value, disabled, onChange }: { value: Data; disabled: boolean; onChange: (key: string, value: string) => void }) {
  return <div className="pw-evidence-source-range"><TextField label="UTF-16 字符起点" value={value.offset} onChange={value => onChange('offset', value)} isDisabled={disabled} /><TextField label="范围字符数，最多 24000" value={value.chars} onChange={value => onChange('chars', value)} isDisabled={disabled} /></div>;
}
function EvidenceScope({ scope }: { scope: Data | null | undefined }) {
  if (!scope) return null;
  const research = scope.researchReadAccess;
  return <div className="pw-field-hint"><p>记忆 {rows(scope.allowedRecords).length} 个版本 · 原文 {rows(scope.allowedSources).length} 个范围 · 技能 {rows(scope.skills).length} 个版本 · 附件或冻结文件 {rows(scope.resources).length} 个范围</p>
    {rows(scope.allowedRecords).map(row => <p key={row.id}>{row.id} · 修订 {row.revision}</p>)}{rows(scope.allowedSources).map((row, index) => <p key={index}>{row.sourceRef?.sourceId} · 修订 {row.sourceRef?.revision} · 从 {row.offset} 起 {row.chars} 字符</p>)}
    {rows(scope.skills).map(row => <p key={row.id}>{row.id} · 修订 {row.revision} · {Array.isArray(row.paths) ? row.paths.join('、') : ''}</p>)}{rows(scope.resources).map(row => <p key={row.id}>{row.kind} · {row.id} · 从 {row.offset} 起 {row.chars} 字符</p>)}
    {Array.isArray(research?.webOrigins) && research.webOrigins.map((site: string) => <p key={site}>公共站点：{site}</p>)}{rows(research?.projectFiles).map(file => <p key={file.projectId + ':' + file.path}>{file.path} · 版本 {file.version} · SHA-256 {file.sha256}</p>)}
    {scope.expiresAt && <p>有效至 {new Date(scope.expiresAt).toLocaleString('zh-CN')}</p>}
  </div>;
}
