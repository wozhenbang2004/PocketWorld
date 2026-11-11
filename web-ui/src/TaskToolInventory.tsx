import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SearchField } from './forms-library';
import { DataOperationHeading, DataOperationRow, useDataSettingsJourney } from './DataSettingsJourney';
import { decisionLabels, toolTitle, type ToolDecision } from './tool-catalog';
import './tool-catalog.css';

type CurrentPolicy = { appliesToTask?: boolean; mode?: string; modeSource?: string; revision?: number; observedAt?: number; rules?: Record<string, { value: string; source: string }>; message?: string };
type Inventory = { state?: string; taskId?: string; runId?: string; epoch?: number; names?: string[]; taskStatus?: string; observedAt?: number; message?: string; policy?: CurrentPolicy; selection?: { kind: string; profileId?: string; revision?: number } };
const ruleText = (name: string, policy?: CurrentPolicy) => {
  if (!policy) return '当前审批策略尚未核对';
  if (!policy.appliesToTask) return '专用原生范围，不套用个人策略';
  const rule = policy.rules?.[name];
  if (rule?.value && decisionLabels[rule.value as ToolDecision]) return `${rule.source === 'conversation' ? '任务所属对话' : '全局'}规则 · ${decisionLabels[rule.value as ToolDecision]}`;
  return `${policy.modeSource === 'conversation' ? '任务所属对话' : '全局'}默认 · ${policy.mode === 'full_access' ? '直接允许可配置审批' : policy.mode === 'auto_review' ? '更改类动作由 AI 审批' : '沿用原生要求'}`;
};
/** Original SQL run names plus native current-rule provenance, never a call or permission. */
export function TaskToolInventory({ inventory, onOpenCatalog, onOpenProfiles, onEditPolicy, registerBack }: { inventory?: Inventory; onOpenCatalog?: () => void; onOpenProfiles?: () => void; onEditPolicy?: (tool: string) => void; registerBack?: (back: (() => boolean) | null) => void }) {
  const [query, setQuery] = useState('');
  const journey = useDataSettingsJourney();
  const selected = journey.view;
  const listPosition = useRef(0), previous = useRef(selected);
  const title = (name: string) => toolTitle({ name, category: '', description: '', source: '', configurable: false });
  const openTool = (name: string) => { listPosition.current = journey.root.current?.closest('.pw-task-body')?.scrollTop || 0; journey.open(name); };
  useLayoutEffect(() => {
    if (previous.current === selected) return; previous.current = selected;
    const root = journey.root.current, scroller = root?.closest('.pw-task-body');
    if (root && scroller) scroller.scrollTop = selected === 'list' ? listPosition.current : Math.max(0, scroller.scrollTop + root.getBoundingClientRect().top - scroller.getBoundingClientRect().top - 8);
  }, [selected]);
  useEffect(() => { registerBack?.(journey.back); return () => registerBack?.(null); }, [registerBack, journey.view]);
  const names = Array.isArray(inventory?.names) ? inventory.names.filter(name => typeof name === 'string') : [];
  const matches = names.filter(name => `${name} ${title(name)}`.toLowerCase().includes(query.trim().toLowerCase()));
  return <div className="pw-task-tool-inventory" ref={journey.root} tabIndex={-1} aria-label="原任务工具记录">
    <Accordion variant="divided" defaultExpandedKeys={[]} onExpandedChange={keys => { if (!keys.has('task-tools')) journey.open('list'); }}><AccordionItem id="task-tools" title={inventory?.state === 'recorded' ? `本轮登记的工具 · ${names.length}` : '本轮工具记录'} description="核对本轮工具和它实际适用的审批策略">
      <p className="pw-tool-boundary">{inventory?.message || '当前版本尚未提供原任务工具盘点，不能由技能引用或已安装插件推定本次范围。'}</p>
      {inventory?.state === 'recorded' && <>
        <p className="pw-tool-boundary">工具选择来源：{inventory.selection?.kind === 'work_profile' ? `原工作配置 ${inventory.selection.profileId} · 修订 ${inventory.selection.revision}` : inventory.selection?.kind === 'work_profile_pending' ? '原任务待核对的工作配置' : inventory.selection?.kind === 'native_source' ? '原任务专用来源范围' : inventory.selection?.kind === 'owner_work' ? '原后台工作接纳范围' : '原任务本轮登记'}。安装或调整其他来源不会在这里自动追加本轮工具。</p>
        {inventory.selection?.kind === 'work_profile' && onOpenProfiles && <Button variant="ghost" onClick={onOpenProfiles}>查看工作配置列表，不改变本轮选择</Button>}
        <p className="pw-tool-boundary">{inventory.policy?.message || '没有读取到当前审批依据，不能把通用设置推定为这次任务的策略。'}</p>
        {selected && names.includes(selected) ? <div className="pw-tool-detail"><DataOperationHeading title={title(selected)} parent="本轮工具" description={selected} onBack={() => journey.open('list')} /><p className="pw-policy-saved-rule">{ruleText(selected, inventory.policy)}</p>{inventory.policy?.appliesToTask && onEditPolicy && <Button variant="secondary" data-task-policy-entry={selected} onClick={() => onEditPolicy(selected)}>编辑原任务适用的审批规则</Button>}<p className="pw-tool-boundary">具体调用还要满足原任务范围、剩余预算、工具自身检查和必要的主人确认。查阅或修改规则不会重放原动作。</p></div>
          : names.length > 0 ? <><SearchField aria-label="搜索本轮工具" placeholder="搜索用途或本轮工具名…" value={query} onChange={setQuery} /><div className="pw-tool-list">{matches.map(name => <DataOperationRow key={name} id={name} title={title(name)} detail={`${name} · ${ruleText(name, inventory.policy)}`} onOpen={openTool} />)}</div>{matches.length === 0 && <p className="pw-tool-status">本轮记录中没有匹配工具。这不表示工具未安装，请分开核对来源与本次范围。</p>}</> : <p className="pw-tool-status">这次执行记录的通用工具集合为空。</p>}
        <Accordion variant="divided" defaultExpandedKeys={[]}><AccordionItem id="task-tools-identity" title="核对原执行与读取时点"><dl className="pw-facts"><div><dt>任务</dt><dd>{inventory.taskId}</dd></div><div><dt>执行代次</dt><dd>{inventory.runId} · {inventory.epoch}</dd></div><div><dt>登记记录读取</dt><dd>{inventory.observedAt ? new Date(inventory.observedAt).toLocaleString() : '未提供'}</dd></div>{inventory.policy?.appliesToTask && <><div><dt>当前策略读取</dt><dd>{inventory.policy.observedAt ? new Date(inventory.policy.observedAt).toLocaleString() : '未提供'}</dd></div><div><dt>当前策略修订</dt><dd>{inventory.policy.revision}</dd></div></>}</dl></AccordionItem></Accordion>
      </>}
      {onOpenCatalog && <Button variant="ghost" onClick={onOpenCatalog}>查找工具用途与管理来源</Button>}
    </AccordionItem></Accordion>
  </div>;
}
