import { useEffect, useRef, useState } from 'react';
import { ChevronRight, Plus, Trash2 } from 'lucide-react';
import { Accordion, AccordionItem, Button, SelectField, SettingsDialog, StretchSwitch, TextField } from './forms-library';
import { SortableList } from './shell-library/beui-adapted/sortable-list';
import { apiModelProjectionCurrent, apiModelRows } from './composer-api-models';
import { aiApiCandidates, aiConnections, aiObject as object, aiRows as rows, aiText as text } from './ai-center-data';

type Data = Record<string, any>;
type Candidate = Data & { id: string; candidateId: string; target: { kind: 'exact'; connectionId: string; modelId: string }; enabled: boolean; allowedPurposes: string[]; allowedAudiences: string[] };
type Props = { state: Data; data: Data; supported: (action: string) => boolean; run: (action: string, args?: Data) => Promise<any>; registerTransientBack: (handler: (() => boolean) | null) => void };

/** Exact API model references are ordered with the retained BeUI sortable source. */
export function AIModelGroups({ state, data, supported, run, registerTransientBack }: Props) {
  const [snapshot, setSnapshot] = useState<Data | null>(null), [editor, setEditor] = useState<Data | null>(null), [removing, setRemoving] = useState<Data | null>(null);
  const [name, setName] = useState(''), [candidates, setCandidates] = useState<Candidate[]>([]), [available, setAvailable] = useState<Data[]>([]), [next, setNext] = useState('');
  const [switching, setSwitching] = useState(false), [attempts, setAttempts] = useState('2'), [busy, setBusy] = useState(false), [unknown, setUnknown] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [editorSelectionRevision, setEditorSelectionRevision] = useState(state.modelSelectionRevision);
  const alive = useRef(true), generation = useRef(0), pending = useRef(false), windowId = useRef('');
  const saved = object(snapshot?.modelGroups || snapshot || data.modelGroups), groups = rows(saved.groups), connections = aiConnections(data);
  const bindings = object(saved.purposeBindings), main = object(bindings.MAIN);
  const update = (value: Data) => {
    const root = object(value.snapshot || value), snapshot = object(root.modelGroups || root);
    if (snapshot.format !== 'pocketworld.model-groups.v1' || !Array.isArray(snapshot.groups) || !snapshot.groups.every((group: Data) => text(group.groupId) && Number.isSafeInteger(group.revision) && group.revision > 0) || !snapshot.purposeBindings || Array.isArray(snapshot.purposeBindings)) throw new Error('模型组回执不完整，请重新读取核对。');
    setSnapshot(root);
  };
  const read = async () => {
    if (pending.current || !supported('models.groups.snapshot')) return;
    const current = ++generation.current; pending.current = true; setBusy(true); setError('');
    try { const value = object(await run('models.groups.snapshot')); if (alive.current && current === generation.current) { update(value); setUnknown(false); } }
    catch (failure) { if (alive.current && current === generation.current) setError(failure instanceof Error ? failure.message : '模型组暂不可读取。'); }
    finally { if (current === generation.current) { pending.current = false; if (alive.current) setBusy(false); } }
  };
  useEffect(() => { alive.current = true; void read(); return () => { alive.current = false; generation.current++; if (windowId.current) void run('models.api.close', { windowId: windowId.current }).catch(() => {}); }; }, []);
  useEffect(() => { registerTransientBack(() => { if (pending.current) return true; if (removing) { setRemoving(null); return true; } if (editor) { setEditor(null); setError(''); return true; } return false; }); return () => registerTransientBack(null); }, [editor, removing, registerTransientBack]);
  const open = async (group: Data) => {
    if (pending.current) return;
    setEditorSelectionRevision(state.modelSelectionRevision);
    setEditor({ ...group }); setName(text(group.name)); setCandidates(rows(group.candidates).map(row => ({ ...row, id: row.candidateId }) as Candidate));
    setSwitching(object(group.attemptPolicy).maxRouteSwitches === 1); setAttempts(String(object(group.attemptPolicy).maxAttempts || 2)); setError(''); setNotice(''); setNext(''); setAvailable([]);
    if (!supported('models.api.open')) return;
    const current = ++generation.current; pending.current = true; setBusy(true);
    try {
      if (windowId.current) await run('models.api.close', { windowId: windowId.current });
      windowId.current = '';
      const value = await run('models.api.open');
      if (!alive.current || current !== generation.current) { if (value?.windowId) void run('models.api.close', { windowId: value.windowId }).catch(() => {}); return; }
      if (!apiModelProjectionCurrent(value, state.modelSelectionRevision)) throw new Error('模型目录版本已变化，请返回后重新打开。');
      windowId.current = value.windowId; setAvailable(aiApiCandidates(apiModelRows(value, state.modelSelectionRevision), connections));
    } catch (failure) { if (alive.current && current === generation.current) setError(failure instanceof Error ? failure.message : '候选模型暂不可读取。'); }
    finally { if (current === generation.current) { pending.current = false; if (alive.current) setBusy(false); } }
  };
  const mutate = async (action: string, args: Data, message: string) => {
    if (pending.current || unknown || !supported(action)) return;
    const current = ++generation.current; pending.current = true; setBusy(true); setError('');
    try {
      const value = object(await run(action, args));
      if (value.status !== 'saved' && value.status !== 'deleted') throw new Error('操作回执待核对，请重新读取模型组。');
      if (alive.current && current === generation.current) { update(value); setEditor(null); setRemoving(null); setNotice(message); }
    } catch (failure) { if (alive.current && current === generation.current) { setUnknown(true); setError(`${failure instanceof Error ? failure.message : '原操作结果待核对。'} 请重新读取，没有自动重试。`); } }
    finally { if (current === generation.current) { pending.current = false; if (alive.current) setBusy(false); } }
  };
  const nameFor = (candidate: Data) => connections.find(row => row.id === candidate.target?.connectionId)?.name || candidate.target?.connectionId || '原来源';
  const selected = available.find(row => JSON.stringify([row.connectionId, row.modelId]) === next);
  const existing = editor?.groupId ? groups.find(row => row.groupId === editor.groupId) : null;
  const stale = Boolean(editor && (editorSelectionRevision !== state.modelSelectionRevision || editor.groupId && (!existing || existing.revision !== editor.revision)));
  const invalidCandidates = candidates.some(candidate => !connections.some(connection => connection.id === candidate.target.connectionId && connection.provider !== 'bedrock_anthropic') || candidate.target.kind !== 'exact' || candidate.target.connectionId.startsWith('account:'));
  // Saving grants this group only the sources of its current enabled candidates.
  // Retained refs may be empty or name a source the owner has since removed.
  const allowedSources = [...new Set(candidates.filter(candidate => candidate.enabled === true).map(candidate => candidate.target.connectionId))];
  const boundPurposes = removing ? Object.entries(bindings).filter(([, ref]) => object(ref).groupId === removing.groupId).map(([purpose]) => purpose) : [];
  return <section className="pw-ai-groups">
    {editor ? <>
      <header className="pw-ai-detail-heading"><h2>{editor.groupId ? '编辑模型组' : '新建模型组'}</h2></header>
      <TextField label="模型组名称" value={name} maxLength={100} onChange={setName} isDisabled={busy} placeholder="例如：日常任务" />
      <section className="pw-ai-candidates"><header className="pw-ai-section-heading"><h3>候选顺序</h3><span>API 来源</span></header>
        {candidates.length ? <SortableList items={candidates} onItemsChange={setCandidates} getItemLabel={item => item.target.modelId} label="模型候选顺序" disabled={busy} className="pw-ai-candidate-order" itemClassName="pw-ai-candidate-row" renderItem={(candidate, index) => <><span className="pw-ai-candidate-index">{index + 1}</span><span className="pw-ai-row-copy"><strong>{candidate.target.modelId}</strong><small>{nameFor(candidate)}</small></span><Button variant="ghost" iconOnly leadingIcon={Trash2} aria-label={`移除候选 ${candidate.target.modelId}`} disabled={busy} onClick={() => setCandidates(old => old.filter(row => row.id !== candidate.id))} /></>} /> : <p className="pw-ai-muted">先添加一个已配置的 API 模型。</p>}
        <div className="pw-ai-add-candidate"><SelectField label="添加候选模型" value={next} onChange={setNext} isDisabled={busy || candidates.length >= 8} options={available.filter(row => !candidates.some(candidate => candidate.target.connectionId === row.connectionId && candidate.target.modelId === row.modelId)).map(row => ({ id: JSON.stringify([row.connectionId, row.modelId]), label: row.modelId, description: row.connectionName || row.connectionId }))} placeholder="选择 API 模型" /><Button variant="secondary" leadingIcon={Plus} disabled={busy || !selected || candidates.length >= 8} onClick={() => {
          if (!selected) return;
          const id = 'candidate-' + crypto.randomUUID(); setCandidates(old => [...old, { id, candidateId: id, target: { kind: 'exact', connectionId: selected.connectionId, modelId: selected.modelId }, enabled: true, allowedPurposes: ['MAIN'], allowedAudiences: ['APP_OWNER'] }]); setNext('');
        }}>添加</Button></div>
      </section>
      <StretchSwitch label="失败时切换候选" description="只有错误可重试、且下一候选满足当前请求能力时，才会尝试切换。" checked={switching} disabled={busy || candidates.filter(row => row.enabled).length < 2} onChange={setSwitching} />
      <Accordion><AccordionItem id="attempt-policy" title="高级调度"><SelectField label="本次调用最多尝试" options={[{ id: '1', label: '1 次' }, { id: '2', label: '2 次' }, { id: '3', label: '3 次' }]} value={switching ? attempts : '1'} isDisabled={busy || !switching} onChange={setAttempts} /><p className="pw-ai-muted">最多切换一次来源；不会重放已经发生的工具操作。</p></AccordionItem></Accordion>
      {invalidCandidates && <p className="pw-field-error" role="alert">此组含本界面暂不支持编辑的账号或 Bedrock 来源。保留原配置，请先核对用途。</p>}
      {stale && <p className="pw-field-error" role="alert">服务或模型组已在其他位置变化，请返回重新打开。</p>}
      <div className="pw-ai-picker-actions"><Button variant="ghost" disabled={busy} onClick={() => setEditor(null)}>取消</Button><Button disabled={busy || unknown || stale || invalidCandidates || !name.trim() || !candidates.length || !supported('models.groups.save')} onClick={() => void mutate('models.groups.save', {
        id: text(editor.groupId), expectedRevision: Number(editor.revision || 0), name: name.trim(), definition: { allowedAccounts: { mode: 'EXPLICIT', refs: allowedSources },
          candidates: candidates.map(({ id: _id, ...row }) => row), attemptPolicy: { maxAttempts: switching ? Number(attempts) : 1, maxRouteSwitches: switching ? 1 : 0 } },
      }, '模型组已保存，尚未更改默认模型。')}>保存模型组</Button></div>
    </> : <>
      <header className="pw-ai-section-heading"><h2>模型组</h2>{groups.length > 0 && <Button leadingIcon={Plus} disabled={busy || unknown || !supported('models.groups.save')} onClick={() => void open({})}>新建</Button>}</header>
      <p className="pw-ai-muted">按候选顺序处理新任务，可单独开启失败切换。</p>
      {groups.length ? <div className="pw-ai-group-list">{groups.map(group => <section className="pw-ai-group" key={group.groupId}><Button variant="ghost" className="pw-ai-service-row" disabled={busy} onClick={() => void open(group)}><span className="pw-ai-row-copy"><strong>{group.name}</strong><small>{rows(group.candidates).length} 个候选 · {object(group.attemptPolicy).maxRouteSwitches === 1 ? '已开启失败切换' : '只使用首个可用候选'}</small></span><ChevronRight size={17} aria-hidden /></Button><div className="pw-ai-group-actions"><Button variant="ghost" disabled={busy || unknown || !supported('models.groups.select') || main.groupId === group.groupId && main.groupRevision === group.revision} onClick={() => void mutate('models.groups.select', { groupId: group.groupId, expectedRevision: group.revision }, '已设为新任务的默认模型组。')}>{main.groupId === group.groupId && main.groupRevision === group.revision ? '当前默认模型组' : '设为默认'}</Button><Button variant="ghost" disabled={busy || !supported('models.groups.delete')} onClick={() => setRemoving(group)}>删除</Button></div></section>)}</div>
        : <div className="pw-ai-empty"><h3>按用途组合多个模型</h3><p>创建模型组，设置 API 候选顺序与失败切换。</p><Button disabled={busy || unknown || !supported('models.groups.save')} onClick={() => void open({})}>新建模型组</Button></div>}
    </>}
    {error && <p className="pw-field-error" role="alert">{error}</p>}{notice && <p className="pw-ai-feedback" role="status">{notice}</p>}
    {unknown && <Button variant="secondary" disabled={busy} onClick={() => void read()}>读取模型组，核对原结果</Button>}
    <SettingsDialog isOpen={!!removing} title="删除这项模型组？" onClose={() => { if (!busy) setRemoving(null); }}>{removing && <><p>{removing.name}</p>{boundPurposes.length > 0 ? <><p>这项模型组仍被 {boundPurposes.join('、')} 使用。解除对应用途后，后续新任务将恢复继承基础默认模型。</p><div className="pw-form-actions">{boundPurposes.map(purpose => <Button key={purpose} variant="secondary" disabled={busy || unknown || !supported('models.groups.unbind')} onClick={() => void mutate('models.groups.unbind', { purpose, groupId: removing.groupId, expectedRevision: removing.revision }, `已解除 ${purpose} 用途，后续新任务恢复继承基础默认模型。`)}>解除 {purpose} 用途</Button>)}</div></> : <p>只删除模型组，保留服务、凭据和已有任务。</p>}<div className="pw-ai-picker-actions"><Button variant="secondary" disabled={busy} onClick={() => setRemoving(null)}>取消</Button><Button disabled={busy || unknown || boundPurposes.length > 0} onClick={() => void mutate('models.groups.delete', { groupId: removing.groupId, expectedRevision: removing.revision }, '模型组已删除。')}>删除模型组</Button></div></>}</SettingsDialog>
  </section>;
}
