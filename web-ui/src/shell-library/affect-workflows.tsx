import { useId, useState } from 'react';
import { RiArrowRightSLine, RiBookOpenLine, RiCheckLine, RiCloseLine, RiFileList3Line } from '@remixicon/react';
import { Button, CheckboxField, SearchField } from '../forms-library';
import { failureOutcome, outcomeLabel, probeObservations, runtimeAttempts, skillMatches, stageLabel } from './affect-workflow-data';
import type { WorkflowData } from './affect-workflow-data';
import './affect-workflows.css';

/** PromptLibraryDemo list/search/detail composition; all entries and actions come from SkillBook. */
export function SkillLibrary({ skills, query, onQuery, disabled, loaded, onOpen, checkingSources, selectedIds, onToggle }: {
  skills: WorkflowData[]; query: string; onQuery: (query: string) => void; disabled: boolean; loaded: boolean;
  onOpen: (id: string) => void; checkingSources: boolean; selectedIds: string[]; onToggle: (skill: WorkflowData, selected: boolean) => void;
}) {
  const [activeId, setActiveId] = useState('');
  const filtered = skills.filter(row => skillMatches(row, query));
  const active = filtered.find(row => row.id === activeId) || filtered[0];
  return <div className="pw-affect-workflow pw-affect-library">
    <div className="pw-affect-library-layout">
      <aside className="pw-affect-library-list">
        <div className="pw-affect-library-heading"><strong>技能库</strong><span>{skills.length} 项</span></div>
        <SearchField aria-label="查找已登记技能" value={query} onChange={onQuery} placeholder="名称、来源或能力" />
        <div className="pw-affect-library-options" aria-label="已登记技能">
          {filtered.map(row => <div className="pw-affect-library-entry" key={row.id}>
            <button type="button" data-skill-id={row.id} aria-pressed={active?.id === row.id} disabled={disabled} onClick={() => setActiveId(row.id)}>
              <RiBookOpenLine size={18} aria-hidden="true"/><span><strong>{row.name || '未命名技能'}</strong>{row.description && <small>{row.description}</small>}</span>
              <span className="pw-affect-tag" data-tone={row.enabled ? 'passed' : 'neutral'}>{row.enabled ? '启用' : '停用'}</span>
            </button>
            {checkingSources && row.gitSource && !row.origin && <CheckboxField isSelected={selectedIds.includes(row.id)} isDisabled={disabled || (!selectedIds.includes(row.id) && selectedIds.length >= 8)} onChange={checked => onToggle(row, checked)}>加入来源检查</CheckboxField>}
          </div>)}
          {loaded && !filtered.length && <p className="pw-affect-empty">{skills.length ? '没有匹配的技能，试试名称或来源。' : '尚未登记技能。可从上方导入。'}</p>}
          {!loaded && <p className="pw-affect-empty">正在读取技能清单…</p>}
        </div>
      </aside>
      <div className="pw-affect-library-detail" aria-label="技能摘要">
        {active ? <><div className="pw-affect-library-heading"><RiBookOpenLine size={20} aria-hidden="true"/><h3>{active.name || '未命名技能'}</h3><span className="pw-affect-tag" data-tone={active.enabled ? 'passed' : 'neutral'}>{active.enabled ? '已启用' : '停用'}</span></div>
          <footer>{query.trim() && <span>{filtered.length} 项匹配</span>}<Button disabled={disabled} trailingIcon={RiArrowRightSLine} onClick={() => onOpen(active.id)}>阅读这份技能</Button></footer>
          <p>{active.description || '这份技能尚未提供说明。'}</p>
          <dl className="pw-affect-library-facts"><div><dt>来源</dt><dd>{active.source || '未注明来源'}</dd></div><div><dt>修订</dt><dd>{active.revision ?? '未注明'}{active.sourceVersion ? ` · ${active.sourceVersion}` : ''}</dd></div>{active.enabled && active.active === false && <div><dt>可用性</dt><dd>当前不可用，阅读详情核对</dd></div>}</dl>
        </> : <div className="pw-affect-library-placeholder"><RiBookOpenLine size={28} aria-hidden="true"/><p>选择一份技能，查看真实登记信息。</p></div>}
      </div>
    </div>
  </div>;
}

/** StepperForm progress vocabulary; native review/start receipts govern the active stage. */
export function DiagnosticWorkflowSteps({ stage }: { stage: 'select' | 'review' | 'observe' }) {
  const current = ['select', 'review', 'observe'].indexOf(stage);
  return <ol className="pw-affect-workflow pw-affect-stepnav" aria-label="模型实测步骤">{['选择范围', '核对并批准', '观察结果'].map((label, index) => <li key={label} data-reached={index <= current} aria-current={index === current ? 'step' : undefined}><span>{index < current ? <RiCheckLine size={14} aria-hidden="true"/> : index + 1}</span>{label}</li>)}</ol>;
}

/** ExperimentCardDemo unfold interaction; rows represent observations, never A/B variants. */
export function ProbeObservationCard({ probe }: { probe: WorkflowData }) {
  const [expanded, setExpanded] = useState(false), detailsId = useId();
  const observations = probeObservations(probe);
  return <section className="pw-affect-workflow pw-affect-observation">
    <button type="button" className="pw-affect-experiment-summary" aria-expanded={expanded} aria-controls={detailsId} onClick={() => setExpanded(value => !value)}>
      <span className="pw-affect-experiment-mark" aria-hidden="true">{observations.map(row => <i key={row.stage} data-tone={row.tone}/>)}</span>
      <span><strong>本次合成实测</strong><small>{probe.model} · 精确连接的独立观察</small></span><RiArrowRightSLine className="pw-affect-unfold-icon" size={20} aria-hidden="true"/>
    </button>
    <div className="pw-affect-observation-overview">{observations.map(row => <span key={row.stage}><span>{row.title}</span><b className="pw-affect-tag" data-tone={row.tone}>{row.label}</b></span>)}</div>
    <div id={detailsId} className="pw-affect-experiment-unfold" data-open={expanded} inert={!expanded}><div className="pw-affect-experiment-inner">
      <div className="pw-affect-experiment-observations">{observations.map(row => <div key={row.stage}><span className="pw-affect-observation-letter" data-tone={row.tone}>{row.tone === 'passed' ? <RiCheckLine size={14} aria-hidden="true"/> : row.stage === 'tools' ? 'ƒ' : row.stage === 'image' ? '▧' : 'T'}</span><div><strong>{row.title}</strong><span className="pw-affect-tag" data-tone={row.tone}>{row.label}</span>{row.diagnostics.map((diagnostic, index) => <p key={index}>{diagnostic.message || diagnostic.reason || '没有补充说明'}{diagnostic.httpStatus ? ` · HTTP ${diagnostic.httpStatus}` : ''}{diagnostic.reason && <code>{diagnostic.reason}</code>}</p>)}</div></div>)}</div>
      <p className="pw-affect-evidence-note">仅反映本次合成结构。未证实不表示永久不支持，单个函数声明通过也不代表执行了真实工具。</p>
      <details className="pw-affect-raw"><summary>原始观察与用量引用</summary><pre className="pw-source-code" tabIndex={0}>{JSON.stringify(probe, null, 2)}</pre></details>
    </div></div>
  </section>;
}

/** FallbackChainDemo inspectable nodes, projected from actual native log operations only. */
export function RuntimeAttemptChain({ events }: { events: unknown }) {
  const [filter, setFilter] = useState('all'), [inspectedKey, setInspectedKey] = useState('');
  const attempts = runtimeAttempts(events);
  const filtered = attempts.filter(row => filter === 'requests' ? row.stage === 'model_request' : filter === 'failures' ? failureOutcome(row.outcome) : true);
  const inspected = filtered.find(row => row.key === inspectedKey);
  return <section className="pw-affect-workflow pw-affect-attempts" aria-label="运行阶段与请求记录">
    <header><RiFileList3Line size={18} aria-hidden="true"/><h4>运行阶段与请求记录</h4><span>{attempts.length} 项观察</span></header>
    <div className="pw-affect-attempt-filters" aria-label="筛选运行记录">{[['all', '全部阶段'], ['requests', '模型请求'], ['failures', '失败与未证实']].map(([id, title]) => <button key={id} type="button" aria-pressed={filter === id} onClick={() => { setFilter(id); setInspectedKey(''); }}>{title}</button>)}</div>
    <p className="pw-affect-evidence-note">按本次读取的日志顺序展示。连线仅表示先后；日志未提供模型回退策略。</p>
    <ol className="pw-affect-flow-models">{filtered.map((row, index) => <li key={row.key}><button type="button" className="pw-affect-flow-model" data-tone={failureOutcome(row.outcome) ? 'uncertain' : ['succeeded', 'completed'].includes(row.outcome) ? 'passed' : 'neutral'} aria-expanded={inspected?.key === row.key} onClick={() => setInspectedKey(value => value === row.key ? '' : row.key)}>
      <span className="pw-affect-flow-number">{index + 1}</span><span className="pw-affect-flow-copy"><span><strong>{stageLabel(row.stage)}</strong>{row.outcome !== 'started' && row.durationMs !== null && <small>{row.durationMs} ms</small>}</span><span><b>{outcomeLabel(row.outcome)}</b><code>{row.code || '没有结果码'}</code></span></span><RiArrowRightSLine size={18} aria-hidden="true"/>
    </button>{inspected?.key === row.key && <div className="pw-affect-attempt-inspector"><div><strong>这项原始阶段记录</strong><button type="button" aria-label="关闭阶段详情" onClick={() => setInspectedKey('')}><RiCloseLine size={18} aria-hidden="true"/></button></div><pre className="pw-source-code" tabIndex={0}>{JSON.stringify(row.events, null, 2)}</pre></div>}</li>)}</ol>
    {!filtered.length && <p className="pw-affect-empty">本次读取没有符合筛选的记录。</p>}
  </section>;
}
