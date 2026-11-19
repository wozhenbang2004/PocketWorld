import { useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import './knowledge-depth.css';
import { Accordion, AccordionItem, Button, SelectField, TextAreaField, TextField } from './forms-library';
import { assertionEvidenceText } from './memory-assertion-evidence';
import { assertionTime, assertionTimes, displayAssertionTime, type TimeKey } from './memory-assertion-time';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;

type EditableAssertionProps = { readOnly?: false; readHandle: string; readExpiresAt: number; candidate: Data; run: Run; onReview: (value: Data) => void };
type AssertionProps = EditableAssertionProps | { readOnly: true; candidate: Data; title?: string };

/** Both surfaces read the same assertion. Draft review never supplies a knowledge edit handle. */
export function MemoryAssertionFields(props: AssertionProps) {
  if (!props.readOnly) return <EditableAssertionFields {...props} />;
  const value = props.candidate.assertion;
  if (!value || typeof value !== 'object') return null;
  const kinds: Data = { user_said: '你的原话', observed: '直接观察', inferred: '推测', told: '转述', imagined: '计划或想象' };
  const presentTimes = assertionTimes.filter(([key]) => assertionTime(value[key]).kind !== 'unknown');
  const spans: Data[] = Array.isArray(value.evidenceSpans) ? value.evidenceSpans : [];
  return <section className="pw-source-record pw-memory-assertion pw-knowledge-assertion" aria-label={props.title || '这条记忆的主体与依据'}><h3>{props.title || '主体与依据'}</h3>
    <dl className="pw-facts"><div><dt>主体</dt><dd>{value.subjectId === 'owner' ? '你' : value.subjectLabel || '尚未明确'}</dd></div>
      <div><dt>陈述</dt><dd>{value.predicate} · {value.object}</dd></div><div><dt>类型</dt><dd>{kinds[value.kind] || value.kind}</dd></div>
    </dl>
    {(value.condition || presentTimes.length > 0) && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="assertion-context" title="适用条件与时间" description={value.condition || '原记录的发生、生效与核对时点'}><dl className="pw-facts">
      {value.condition && <div><dt>适用条件</dt><dd>{value.condition}</dd></div>}
      {presentTimes.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{displayAssertionTime(assertionTime(value[key]))}</dd></div>)}
    </dl></AccordionItem></Accordion>}
    {spans.map((span, index) => <blockquote className="pw-reading-copy" key={`${span.sourceRef?.sourceId || ''}:${span.offset}:${index}`}><p>{assertionEvidenceText(span, props.candidate.assertionEvidence) ?? '原句当前不可读取'}</p><footer className="pw-muted">原句 · 来源修订 {span.sourceRef?.revision ?? '待核对'}</footer></blockquote>)}
  </section>;
}

/** Adapts the original candidate identity review into the existing BoardUI form components. */
const identityWindow = () => { const s = bridge.getState().state; return JSON.stringify([bridge.getDocumentEpoch(), s.memoryRefreshRevision, s.traceSourceRevision]); };
function EditableAssertionFields({ readHandle, readExpiresAt, candidate, run, onReview }: EditableAssertionProps) {
  const old = candidate.assertion || {};
  const [subject, setSubject] = useState(old.subjectId === 'owner' ? 'owner' : old.subjectId ? 'candidate' : '');
  const [subjectLabel, setSubjectLabel] = useState(old.subjectLabel || ''), [target, setTarget] = useState(old.objectId === 'owner' ? 'owner' : old.objectId ? 'candidate' : '');
  const [kind, setKind] = useState(['inferred', 'told', 'imagined'].includes(old.kind) ? old.kind : 'inferred');
  const [predicate, setPredicate] = useState(old.predicate || ''), [object, setObject] = useState(old.object || candidate.text || ''), [condition, setCondition] = useState(old.condition || '');
  const [times, setTimes] = useState<Record<string, string>>(() => Object.fromEntries(assertionTimes.map(([key]) => {
    const t = assertionTime(old[key]); return [key, t.kind === 'date' ? new Date(t.ms).toISOString() : ''];
  })));
  const [repairs, setRepairs] = useState<Partial<Record<TimeKey, 'replace' | 'clear'>>>({});
  const unusual = assertionTimes.filter(([key]) => ['outside', 'invalid'].includes(assertionTime(old[key]).kind));
  const readyTimes = unusual.every(([key]) => repairs[key] === 'clear' || (repairs[key] === 'replace' ? !!times[key].trim() : assertionTime(old[key]).kind === 'outside'));
  const dateInputs = () => Object.fromEntries(assertionTimes.map(([key]) => [key, assertionTime(old[key]).kind === 'outside' && !repairs[key]
    ? { keepOriginalOutOfRange: true } : repairs[key] === 'clear' ? '' : times[key]]));
  const [query, setQuery] = useState(''), [identities, setIdentities] = useState<Data[]>([]), [searched, setSearched] = useState<string | null>(null), [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [closed, setClosed] = useState(false);
  const life = useRef({ active: false, busy: false, seq: 0, scope: '' });
  const current = () => life.current.active && !document.hidden && identityWindow() === life.current.scope && Date.now() < readExpiresAt;
  useLayoutEffect(() => {
    const s = life.current; s.active = true; s.busy = false; s.scope = identityWindow(); setClosed(false);
    const retire = () => { if (!s.active) return; s.active = false; s.busy = false; s.seq++; setBusy(false); setIdentities([]); setSearched(null); setMore(false); setError(''); setClosed(true); };
    const check = () => { if (!current()) retire(); };
    const left = Number.isSafeInteger(readExpiresAt) ? readExpiresAt - Date.now() : 0;
    const timer = left > 0 ? globalThis.setTimeout(retire, left) : undefined;
    if (left <= 0 || document.hidden) retire();
    const off = bridge.subscribe(check); document.addEventListener('visibilitychange', check); globalThis.addEventListener('pagehide', retire);
    return () => { s.active = false; s.seq++; clearTimeout(timer); off(); document.removeEventListener('visibilitychange', check); globalThis.removeEventListener('pagehide', retire); };
  }, [readHandle, readExpiresAt]);
  const known = (v: string, role: 'subject' | 'object') => {
    if (v === 'candidate') { const id = old[role === 'subject' ? 'subjectId' : 'objectId']; return typeof id === 'string' && !!id.trim() && id !== 'owner'; }
    return ['owner', 'new-person', 'new-entity'].includes(v) || identities.some(r => r.choiceHandle === v);
  };
  const endpointsReady = !!subject && known(subject, 'subject') && (subject === 'owner' || !!subjectLabel.trim()) && (!target || known(target, 'object'));
  // Retaining an expressly chosen endpoint is not permission to show an earlier donor excerpt again.
  const retained = (): Data[] => identities.filter(r => r.choiceHandle === subject || r.choiceHandle === target).map(r => ({ ...r, recordPreview: '' }));
  const load = async () => {
    const s = life.current; if (!current() || s.busy) return;
    s.busy = true; const seq = ++s.seq, selected = retained(), requested = query.trim();
    setBusy(true); setIdentities([]); setSearched(null); setMore(false); setError('');
    try {
      const result = await run('knowledge.assertion.prepare', { readHandle, query: requested, retainHandles: selected.map(row => row.choiceHandle) });
      if (!current() || seq !== s.seq) return;
      const c = result?.candidate, hits = result?.identities;
      if (!c || ['id', 'revision', 'ownerId', 'spaceId'].some(k => c[k] !== candidate[k]) || c.status !== 'pending' || result.query !== requested ||
          result.formalMemorySaved !== false || result.sourceProofIssued !== false || result.executionAuthorized !== false || !Array.isArray(hits) || hits.length > 64 ||
          !hits.every((r: Data) => r && typeof r.choiceHandle === 'string' && r.choiceHandle && typeof r.id === 'string' && r.id && r.id !== 'owner' &&
            ['person','entity'].includes(r.type) && typeof r.label === 'string' && r.label.trim() && typeof r.recordPreview === 'string' && r.recordPreview.length <= 160)) throw new Error('没有取得这条候选在当前范围内的身份选择。');
      // Only a successful same-candidate search re-admits the originally selected endpoints.
      const combined = [...selected, ...hits];
      setIdentities(combined.filter((row, i) => combined.findIndex(other => other.id === row.id && other.type === row.type && other.label === row.label) === i));
      setMore(result.hasMoreIdentities === true); setSearched(requested);
    } catch (failure) { if (current() && seq === s.seq) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (seq === s.seq) { s.busy = false; setBusy(false); } }
  };
  const choices = [{ id: 'owner', label: '主人' }, { id: 'new-person', label: '新的独立人物' }, { id: 'new-entity', label: '新的独立实体' }, ...identities.map(row => ({ id: row.choiceHandle, label: row.label, description: row.recordPreview || row.id }))];
  const selection = (value: string, label: string): Data => {
    if (value === 'owner') return { mode: 'owner' };
    if (value === 'candidate') return { mode: 'candidate', label };
    if (value === 'new-person' || value === 'new-entity') return { mode: 'new', type: value.slice(4), label };
    const identity = identities.find(row => row.choiceHandle === value);
    if (!identity) throw new Error('请明确选择当前列出的身份。');
    return { mode: 'existing', choiceHandle: identity.choiceHandle, label };
  };
  const prepare = async () => {
    const s = life.current; if (!current() || s.busy || !endpointsReady || !readyTimes) return;
    s.busy = true; const seq = ++s.seq; setBusy(true); setError('');
    try { const result = await run('knowledge.assertion.review', { readHandle, subject: selection(subject, subjectLabel), relationTarget: target ? selection(target, object) : null, kind, predicate, object, condition, ...dateInputs() }); if (current() && s.seq === seq) onReview(result); }
    catch (failure) { if (current() && s.seq === seq) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (s.seq === seq) { s.busy = false; setBusy(false); } }
  };
  if (closed) return <p className="pw-muted" role="status">这次候选阅读窗口已关闭，未保留其他身份的预览。请回到原候选重新读取。</p>;
  return <section className="pw-source-record pw-knowledge-assertion-editor"><h3>主体与关系</h3>
    <p className="pw-muted">只修改这条待审候选，原来源继续保留。请明确选择主体；同名不会自动合并。保留原主体、选择本人或新建都不需读取其他记忆。采用候选仍需另行确认。</p>
    <SelectField label="这条陈述的主体" value={subject} isDisabled={busy} options={[...(old.subjectId && old.subjectId !== 'owner' ? [{ id: 'candidate', label: `保留原主体：${old.subjectLabel}` }] : []), ...choices]} onChange={value => { setSubject(value); const identity = identities.find(row => row.choiceHandle === value); setSubjectLabel(value === 'owner' ? '主人' : identity?.label || (value === 'candidate' ? old.subjectLabel : '')); }} />
    {subject && subject !== 'owner' && <TextField label="主体显示名" value={subjectLabel} onChange={setSubjectLabel} maxLength={160} isDisabled={busy} />}
    <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="assertion-identities" title="查找已有主体" description="同名不会自动合并；只使用当前读取的身份">
    <TextField label="查找已有身份" value={query} onChange={v => { setQuery(v); setIdentities(retained()); setMore(false); setSearched(null); }} maxLength={160} isDisabled={busy} /><Button variant="ghost" disabled={busy} onClick={() => { void load(); }}>{query.trim() ? '查找匹配的已有身份' : '浏览当前范围的已有身份'}</Button>
    {searched !== null && <p className="pw-muted" role="status">{searched ? `刚才查找「${searched}」` : '已按你的选择浏览本范围'}；当前可选 {identities.length} 个身份，包含保留的已选项。</p>}
    {more && <p className="pw-muted">本次最多显示 64 个身份，请缩小查询范围。</p>}
    </AccordionItem></Accordion>
    <h4 className="pw-knowledge-section-heading">这条陈述</h4>
    <TextField label="描述哪件事，例如偏好或负责项目" value={predicate} onChange={setPredicate} maxLength={160} isDisabled={busy} />
    <TextAreaField label="事实值或关系目标名称" value={object} onChange={setObject} maxLength={4000} isDisabled={busy} />
    <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="assertion-relation" title="关联另一身份" description={target ? `当前关联：${target === 'owner' ? '主人' : identities.find(row => row.choiceHandle === target)?.label || old.object || '原关系目标'}` : '当前仅保留文字，不关联身份'}>
    <SelectField label="关系目标（可选）" value={target} isDisabled={busy} options={[{ id: '', label: '仅保留文字，不关联身份' }, ...(old.objectId && old.objectId !== 'owner' ? [{ id: 'candidate', label: '保留原关系目标' }] : []), ...choices]} onChange={value => { setTarget(value); const identity = identities.find(row => row.choiceHandle === value); if (value === 'owner') setObject('主人'); else if (identity) setObject(identity.label); else if (value === 'candidate') setObject(old.object || ''); }} />
    </AccordionItem></Accordion>
    <SelectField label="陈述类型" value={kind} onChange={setKind} isDisabled={busy} options={[{ id: 'inferred', label: '推测' }, { id: 'told', label: '转述' }, { id: 'imagined', label: '计划或想象，未证实发生' }]} />
    <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="assertion-times" title="条件与时间" description={unusual.length ? '旧记录里有不能换算的时间，可保留原数值或逐项修改' : condition || '未填写的日期保持未知；计划不会自动成为事实'}>
    <TextField label="适用条件（可选）" value={condition} onChange={setCondition} maxLength={1000} isDisabled={busy} />
    <p className="pw-muted">未知请留空。YYYY-MM-DD 按手机时区解释，也可保留完整时间。计划不会因为日期已过而变成现实事实。</p>
    {assertionTimes.map(([key, label]) => {
      const previous = assertionTime(old[key]), special = previous.kind === 'outside' || previous.kind === 'invalid', repair = repairs[key];
      const input = <TextField label={label} value={times[key]} onChange={value => setTimes(prev => ({ ...prev, [key]: value }))} maxLength={64} isDisabled={busy} />;
      if (!special) return <div key={key}>{input}</div>;
      return <section className="pw-memory-review-version" key={key} aria-label={label}><h4>{label}</h4><p>{displayAssertionTime(previous)}。{repair === 'clear' ? '本次将改为未知。' : repair === 'replace' ? '请填入你能核对的新日期。' : previous.kind === 'outside' ? '未改动，将完整保留原数值；不猜日期或单位。' : '请明确改填日期或改为未知。'}</p>
        {repair === 'replace' && input}<div className="pw-form-actions">
          {repair !== 'replace' && <Button variant="ghost" disabled={busy} onClick={() => setRepairs(prev => ({ ...prev, [key]: 'replace' }))}>改填日期</Button>}
          {repair !== 'clear' && <Button variant="ghost" disabled={busy} onClick={() => setRepairs(prev => ({ ...prev, [key]: 'clear' }))}>这项改为未知</Button>}
          {repair && previous.kind === 'outside' && <Button variant="ghost" disabled={busy} onClick={() => setRepairs(prev => ({ ...prev, [key]: undefined }))}>仍保留原数值</Button>}
        </div></section>;
    })}
    </AccordionItem></Accordion>
    {((subject && !known(subject, 'subject')) || (target && !known(target, 'object'))) && <p className="pw-muted">先前选的已有身份目前不能核对。请重新查找并明确选择；没有自动改成主人或取消关联。</p>}
    {error && <div className="pw-knowledge-notice"><p role="alert">{error}</p></div>}<Button variant="secondary" disabled={busy || !endpointsReady || !readyTimes || !predicate.trim() || !object.trim()} onClick={() => { void prepare(); }}>核对本次主体与关系修改</Button>
  </section>;
}
