import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button, TextField, TextAreaField, RadioOptions, SelectField, Accordion, AccordionItem, SettingsDialog } from './forms-library';
import { ToolCatalogBrowser } from './ToolCatalogBrowser';
import { decisionLabels, ruleSummary, type CatalogTool, type ToolCatalog, type ToolDecision, type ToolPolicy, type PolicySnapshot } from './tool-catalog';
import './permission-policy.css';

type Scope = 'global' | 'conversation';
type Drafts = { global: ToolPolicy; conversation: ToolPolicy | null };
export type PermissionLeaveGuard = (leave: () => void) => boolean;
/** Native lifecycle result, separate from an uncertain write to a still-live target. */
export class PermissionTargetRetired extends Error {}
type Props = {
  conversationId: string; act: (action: string, args?: Record<string, any>) => Promise<any>;
  onPendingChange?: (pending: boolean) => void; registerBeforeLeave?: (guard: PermissionLeaveGuard | null) => void;
  initialScope?: Scope; initialTool?: string; children?: ReactNode; conversationLabel?: string; initialToolFromTask?: boolean; unavailableReason?: string;
};
const clone = (policy: ToolPolicy): ToolPolicy => ({ ...policy, tools: { ...policy.tools } });
const draftsFrom = (snapshot: PolicySnapshot): Drafts => ({ global: clone(snapshot.global), conversation: snapshot.conversation ? clone(snapshot.conversation) : null });
const signature = (policy: ToolPolicy | null) => policy ? JSON.stringify([policy.mode, policy.instructions, Object.entries(policy.tools).sort(([a], [b]) => a.localeCompare(b))]) : 'inherit';
const differs = (left: ToolPolicy | null, right: ToolPolicy | null) => signature(left) !== signature(right);
const instructionLimit = 8000;
const modes = [
  { id: 'custom', label: '自定义', description: '工具规则优先，其余沿用原生要求' },
  { id: 'auto_review', label: 'AI 自动审批', description: '需要审批或产生更改的动作，由 AI 判断' },
  { id: 'full_access', label: '默认直接允许', description: '直接允许可配置审批，单独工具规则仍优先' },
];
export function PermissionPolicySettings({ conversationId, act, onPendingChange, registerBeforeLeave, initialScope, initialTool, children, conversationLabel = '此对话', initialToolFromTask = false, unavailableReason }: Props) {
  const actRef = useRef(act); actRef.current = act;
  const generation = useRef(0), pendingRef = useRef(false), dirtyRef = useRef(false), pendingChange = useRef(onPendingChange); pendingChange.current = onPendingChange;
  const [snapshot, setSnapshot] = useState<PolicySnapshot | null>(null), [drafts, setDrafts] = useState<Drafts | null>(null), [scope, setScope] = useState<Scope>(initialScope || 'conversation');
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [uncertain, setUncertain] = useState(false);
  const editingDisabled = busy || !!unavailableReason;
  const [catalog, setCatalog] = useState<ToolCatalog | null>(null), [catalogError, setCatalogError] = useState(''), [catalogLoading, setCatalogLoading] = useState(false);
  const [toolName, setToolName] = useState(''), [ruleFilter, setRuleFilter] = useState(initialTool ? 'all' : 'rules');
  const [leaveRequest, setLeaveRequest] = useState<(() => void) | null>(null), [readRevision, setReadRevision] = useState(0);
  const snapshotRef = useRef(snapshot); snapshotRef.current = snapshot;
  const dirtyScopes: Scope[] = snapshot && drafts ? (['conversation', 'global'] as Scope[]).filter(key => differs(drafts[key], snapshot[key])) : [];
  dirtyRef.current = dirtyScopes.length > 0;

  const readCatalog = async () => {
    const epoch = generation.current; setCatalogLoading(true); setCatalogError('');
    try { const value: ToolCatalog = await actRef.current('permissions.catalog'); if (generation.current === epoch) setCatalog(value); }
    catch (e) { if (generation.current === epoch) setCatalogError(`工具目录暂不可读取：${e instanceof Error ? e.message : String(e)}`); }
    finally { if (generation.current === epoch) setCatalogLoading(false); }
  };
  useEffect(() => {
    let live = true; ++generation.current; pendingRef.current = false; pendingChange.current?.(false);
    setSnapshot(null); setDrafts(null); setBusy(false); setError(''); setNotice(''); setUncertain(false); setLeaveRequest(null); setCatalog(null); setToolName('');
    actRef.current('permissions.snapshot').then((value: PolicySnapshot) => {
      if (!live) return; setSnapshot(value); setDrafts(draftsFrom(value)); setScope(value.conversationId ? initialScope || 'conversation' : 'global');
    }).catch(e => { if (live) setError(e instanceof Error ? e.message : String(e)); });
    void readCatalog();
    return () => { live = false; ++generation.current; pendingRef.current = false; pendingChange.current?.(false); };
  }, [conversationId, readRevision]);
  useEffect(() => {
    registerBeforeLeave?.(leave => {
      if (pendingRef.current) return true;
      if (dirtyRef.current) { setLeaveRequest(() => leave); return true; }
      return false;
    });
    return () => registerBeforeLeave?.(null);
  }, [registerBeforeLeave]);

  const edit = (next: ToolPolicy | null) => {
    if (pendingRef.current || !drafts) return;
    setDrafts({ ...drafts, [scope]: next }); setNotice(''); setError('');
  };
  const refreshAfterUnconfirmed = async () => {
    if (pendingRef.current) return;
    const epoch = generation.current; pendingRef.current = true; pendingChange.current?.(true); setBusy(true); setError('');
    try {
      const value: PolicySnapshot = await actRef.current('permissions.snapshot');
      if (epoch !== generation.current) return;
      if (value.conversationId !== snapshotRef.current?.conversationId) throw new Error('当前对话已改变，请关闭后重新进入。');
      // Keep the user's drafts. The newly read revision is the only valid base for another explicit save.
      setSnapshot(value); setUncertain(false); setNotice('已重新读取生效设置，草稿仍保留。请核对差异后决定是否再次保存。');
    } catch (e) { if (epoch === generation.current) { if (e instanceof PermissionTargetRetired) setUncertain(false); setError(e instanceof Error ? e.message : String(e)); } }
    finally { if (epoch === generation.current) { pendingRef.current = false; setBusy(false); pendingChange.current?.(false); } }
  };
  const save = async () => {
    if (!snapshot || !drafts || pendingRef.current || uncertain || unavailableReason) return;
    const saveScope = scope, submitted = drafts[saveScope], expectedRevision = snapshot.revision, epoch = generation.current;
    if (submitted && submitted.instructions.length > instructionLimit) { setError('AI 审批指令最多 8,000 字符'); return; }
    pendingRef.current = true; pendingChange.current?.(true); setBusy(true); setError(''); setNotice('');
    try {
      const value: PolicySnapshot = await actRef.current('permissions.save', { conversationId: snapshot.conversationId, global: saveScope === 'global', policy: submitted, expectedRevision });
      if (epoch !== generation.current) return;
      if (value.conversationId !== snapshot.conversationId) throw new Error('保存返回的对话身份已改变，需要重新核对。');
      const other: Scope = saveScope === 'global' ? 'conversation' : 'global';
      const keepOther = differs(drafts[other], snapshot[other]);
      setSnapshot(value); setDrafts({ ...draftsFrom(value), ...(keepOther ? { [other]: drafts[other] } : {}) });
      setNotice(`${saveScope === 'global' ? '全局默认' : conversationLabel}已保存${submitted ? '' : '，重新跟随全局'}。没有重新执行任何动作。`);
    } catch (e) {
      if (epoch === generation.current) { setUncertain(!(e instanceof PermissionTargetRetired)); setError(e instanceof PermissionTargetRetired ? e.message : `保存结果尚未确认：${e instanceof Error ? e.message : String(e)}。草稿已保留，请先重新读取生效设置。`); }
    } finally { if (epoch === generation.current) { pendingRef.current = false; setBusy(false); pendingChange.current?.(false); } }
  };
  if (!snapshot || !drafts) return <><p className="pw-permission-read-status" role={error ? 'alert' : 'status'}>{unavailableReason || error || '读取审批设置…'}</p>{error && !unavailableReason && <Button variant="secondary" onClick={() => setReadRevision(value => value + 1)}>重新读取</Button>}{children}</>;
  const own = drafts[scope], draft = own || { ...snapshot.global, tools: {} };
  const saved = snapshot[scope], current = saved || { ...snapshot.global, tools: {} };
  const effectiveTools = scope === 'conversation' ? { ...snapshot.global.tools, ...draft.tools } : draft.tools;
  const changedTools = new Set([...Object.keys(current.tools), ...Object.keys(draft.tools)].filter(name => current.tools[name] !== draft.tools[name]));
  const currentDirty = dirtyScopes.includes(scope);
  const allTools = [...(catalog?.tools || [])];
  for (const name of Object.keys(effectiveTools)) if (!allTools.some(tool => tool.name === name)) allTools.push({ name, category: 'uncatalogued', description: '此名称有保存或暂存的审批规则，但当前目录未提供其定义。规则不会安装或接入此工具。', source: 'saved_rule', configurable: true });
  if (initialToolFromTask && initialTool && !allTools.some(tool => tool.name === initialTool)) allTools.push({ name: initialTool, category: 'task_record', description: '此精确名称来自原任务本轮登记，当前目录未提供完整定义。规则仍作用于所选会话或全局，不会安装此工具、改变原任务范围或恢复旧执行权。', source: 'original_task', configurable: true });
  const catalogTools = ruleFilter === 'rules' ? allTools.filter(tool => Object.prototype.hasOwnProperty.call(effectiveTools, tool.name)) : allTools;
  const setDecision = (name: string, value: string) => {
    if (pendingRef.current) return;
    const rules = { ...draft.tools };
    if (value === 'inherit') delete rules[name];
    else {
      if (!Object.prototype.hasOwnProperty.call(rules, name) && Object.keys(rules).length >= 512) { setError('工具规则最多 512 项'); return; }
      rules[name] = value as ToolDecision;
    }
    edit({ ...draft, tools: rules });
  };
  const addExactRule = () => {
    const name = toolName.trim();
    if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(name)) { setError('请填写精确工具名：英文字母开头，仅含字母、数字、下划线或连字符，最多 64 字符。'); return; }
    if (Object.prototype.hasOwnProperty.call(draft.tools, name)) { setError('此范围已有同名规则，请在列表中修改。'); return; }
    if (catalog?.tools.some(tool => tool.name === name && !tool.configurable)) { setError('此工具由原生流程控制，不能在这里新建审批规则。'); return; }
    if (Object.keys(draft.tools).length >= 512) { setError('工具规则最多 512 项'); return; }
    setDecision(name, 'ask'); setToolName(''); setRuleFilter('rules');
  };
  const ruleDetail = (tool: CatalogTool) => <div className="pw-policy-tool-editor">
    <p className="pw-policy-saved-rule">当前生效：{ruleSummary(current, snapshot.global, scope, tool.name).replaceAll('此对话', conversationLabel)}</p>
    {tool.configurable ? <SelectField label="保存后使用的规则" value={draft.tools[tool.name] || 'inherit'} isDisabled={editingDisabled}
      options={[{ id: 'inherit', label: scope === 'conversation' && snapshot.global.tools[tool.name] ? `继承全局 · ${decisionLabels[snapshot.global.tools[tool.name]]}` : '不单独设置，按默认模式' }, ...Object.entries(decisionLabels).map(([id, label]) => ({ id, label }))]}
      onChange={value => setDecision(tool.name, value)} hint="这里只修改草稿，点击页面末尾的保存后才生效。精确工具规则优先于默认模式。" />
      : <><p className="pw-tool-boundary">此工具由原生流程控制，这里的审批规则不会覆盖它。</p>{draft.tools[tool.name] && <Button variant="ghost" disabled={editingDisabled} onClick={() => setDecision(tool.name, 'inherit')}>移除此范围的无效规则</Button>}</>}
    {draft.tools[tool.name] === 'allow' && <p className="pw-permission-hint">直接允许仅处理可配置审批，不代替工具自身要求的主人接管或准确范围确认。</p>}
  </div>;
  return <section className="pw-permission-policy pw-policy-workbench" aria-label="审批策略" aria-busy={busy}>
    {unavailableReason && <p className="pw-permission-error" role="alert">{unavailableReason} 草稿仍在此处，不会自动换目标或再次提交；可核对后放弃更改返回原任务。</p>}
    <div className="pw-permission-scope pw-material-content">
      <RadioOptions label="作用范围" value={scope} isDisabled={busy} options={[{ id: 'conversation', label: conversationLabel, disabled: !snapshot.conversationId }, { id: 'global', label: '全局默认' }]} onChange={value => { setScope(value as Scope); setError(''); setNotice(''); }} />
      <p className="pw-policy-origin">{scope === 'global' ? '全局默认用于未单独设置的对话。' : saved ? `当前：${conversationLabel}有独立模式与 AI 指令；未覆盖的工具规则继承全局。` : `当前：${conversationLabel}跟随全局默认。`}{scope === 'conversation' && own === null && currentDirty ? ' 待保存：恢复跟随全局，当前设置尚未改变。' : ''}</p>
      {dirtyScopes.some(key => key !== scope) && <p className="pw-permission-hint" role="status">{scope === 'global' ? conversationLabel : '全局默认'}还有未保存的草稿，切换范围不会丢失。</p>}
    </div>
    <div className="pw-permission-modes pw-material-content">
      <RadioOptions label="默认审批方式" value={draft.mode} options={modes} isDisabled={editingDisabled} onChange={mode => edit({ ...draft, mode: mode as ToolPolicy['mode'] })} />
      {draft.mode === 'full_access' && <p className="pw-policy-caution" role="note">保存后，可配置动作将默认不再询问，可能修改、删除数据或访问外部服务。已有禁止规则仍有效。</p>}
      {(draft.mode === 'auto_review' || Object.values(effectiveTools).includes('review')) && <p className="pw-permission-hint">AI 可能误判，并会产生额外模型调用。</p>}
      <p className="pw-permission-hint">这些设置用于普通私人任务，不覆盖团队、工作配置等专用范围。所有模式都不扩大系统权限、服务授权或任务范围，原生高风险确认仍有效。</p>
    </div>
    <section className="pw-policy-tools" aria-label="单独工具规则">
      <header className="pw-policy-section-heading"><h3>工具规则</h3><p>对个别工具另作安排，优先于默认方式。</p></header>
      <SelectField label="查看工具" compactValue value={ruleFilter} onChange={setRuleFilter} options={[{ id: 'rules', label: `已设置规则 · ${Object.keys(effectiveTools).length}` }, { id: 'all', label: '浏览全部工具定义' }]} />
      {ruleFilter === 'rules' && catalogTools.length === 0 ? <div className="pw-tool-status"><p>还没有单独工具规则，工具按当前默认模式或原生要求处理。</p><Button variant="secondary" onClick={() => setRuleFilter('all')}>查找工具并设置规则</Button></div> : <ToolCatalogBrowser key={ruleFilter} tools={catalogTools} loading={catalogLoading} error={catalogError} onReload={() => void readCatalog()} initialTool={initialTool} status={tool => ruleSummary(draft, snapshot.global, scope, tool.name).replaceAll('此对话', conversationLabel)} detail={ruleDetail} />}
    </section>
    <Accordion variant="divided" defaultExpandedKeys={[]} className="pw-permission-rules pw-material-content">
      <AccordionItem id="permission-manual" title="按精确工具名设置" description="用于目录尚未登记的动态工具，不支持通配符">
        <TextField label="精确工具名" value={toolName} isDisabled={editingDisabled} onChange={setToolName} placeholder="例如 attachments_create_document" />
        <Button variant="secondary" disabled={editingDisabled || !toolName.trim()} onClick={addExactRule}>暂存为“询问我”</Button>
      </AccordionItem>
      <AccordionItem id="permission-instructions" title="AI 审批参考指令" description="不是执行授权，也不代替单独工具规则">
        <TextAreaField label="审批时参考的要求" value={draft.instructions} onChange={instructions => edit({ ...draft, instructions })} isDisabled={editingDisabled} rows={3} autoResize maxRows={6} resize="none" maxLength={instructionLimit} showCount placeholder="例如：涉及对外发送或删除原始文件时，先询问我。" />
        {scope === 'conversation' && <p className="pw-permission-hint">单独保存后，只使用此处指令，不叠加全局指令。留空表示不附加指令；恢复继承后重新跟随全局。</p>}
      </AccordionItem>
    </Accordion>
    {scope === 'conversation' && <div className="pw-policy-inheritance">{own === null ? <><p className="pw-permission-hint">当前草稿跟随已保存的全局设置；修改任一项后将建立{conversationLabel}的独立设置。</p><Button variant="ghost" disabled={editingDisabled} onClick={() => edit(clone(draft))}>为{conversationLabel}单独设置</Button></> : <Button variant="ghost" disabled={editingDisabled} onClick={() => edit(null)}>暂存为继承全局</Button>}</div>}
    {children}
    <footer className="pw-permission-actions">
      {currentDirty && <div className="pw-policy-change-summary" role="status"><strong>{scope === 'global' ? '全局默认' : conversationLabel}有未保存更改</strong><span>{own === null ? '恢复继承全局' : [differs(saved, own) && !saved ? '建立独立设置' : '', current.mode !== draft.mode ? `模式：${modes.find(mode => mode.id === current.mode)?.label} → ${modes.find(mode => mode.id === draft.mode)?.label}` : '', changedTools.size ? `${changedTools.size} 项工具规则` : '', current.instructions !== draft.instructions ? 'AI 参考指令' : ''].filter(Boolean).join(' · ') || '审批设置'}</span></div>}
      <p className="pw-permission-hint">保存不会重跑已执行动作。进行中任务的旧策略调用会被阻止，需回原任务按新策略继续。</p>
      {error && <p className="pw-permission-error" role="alert">{error}</p>}{notice && <p className="pw-permission-notice" role="status">{notice}</p>}
      <div className="pw-permission-action-row">
        {currentDirty && !uncertain && <Button variant="ghost" disabled={editingDisabled} onClick={() => { setDrafts({ ...drafts, [scope]: saved ? clone(saved) : null }); setNotice('已还原此范围的草稿，未写入设置。'); setError(''); }}>撤销此范围更改</Button>}
        {uncertain ? <Button variant="secondary" disabled={editingDisabled} onClick={() => void refreshAfterUnconfirmed()}>重新读取生效设置</Button> : <Button disabled={editingDisabled || !currentDirty} onClick={() => void save()}>{busy ? '保存中…' : `保存${scope === 'global' ? '全局默认' : conversationLabel}`}</Button>}
      </div>
    </footer>
    <SettingsDialog isOpen={!!leaveRequest} title="还有未保存的审批设置" onClose={() => setLeaveRequest(null)} closeLabel="继续编辑">
      <div className="pw-policy-leave"><p>{dirtyScopes.map(key => key === 'global' ? '全局默认' : conversationLabel).join('、')}的更改尚未保存。离开会丢弃这些草稿，已生效设置不变。</p><div><Button variant="secondary" onClick={() => setLeaveRequest(null)}>继续编辑</Button><Button onClick={() => { const leave = leaveRequest; setLeaveRequest(null); setDrafts(draftsFrom(snapshot)); dirtyRef.current = false; leave?.(); }}>放弃更改并离开</Button></div></div>
    </SettingsDialog>
  </section>;
}
