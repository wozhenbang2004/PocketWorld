import { useEffect, useRef, useState } from 'react';
import { Button, SelectField, TextAreaField, TextField } from './forms-library';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const date = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? new Date(value).toISOString() : '';

/** Adapts the original candidate identity review into the existing BoardUI form components. */
export function MemoryAssertionFields({ readHandle, candidate, run, onReview }: { readHandle: string; candidate: Data; run: Run; onReview: (value: Data) => void }) {
  const old = candidate.assertion || {};
  const [subject, setSubject] = useState(old.subjectId === 'owner' ? 'owner' : old.subjectId ? 'candidate' : '');
  const [subjectLabel, setSubjectLabel] = useState(old.subjectLabel || ''), [target, setTarget] = useState(old.objectId === 'owner' ? 'owner' : old.objectId ? 'candidate' : '');
  const [kind, setKind] = useState(['inferred', 'told', 'imagined'].includes(old.kind) ? old.kind : 'inferred');
  const [predicate, setPredicate] = useState(old.predicate || ''), [object, setObject] = useState(old.object || candidate.text || ''), [condition, setCondition] = useState(old.condition || '');
  const [times, setTimes] = useState<Data>(Object.fromEntries(['eventTime', 'validFrom', 'validTo', 'reviewAfter'].map(key => [key, date(old[key])])));
  const [query, setQuery] = useState(''), [identities, setIdentities] = useState<Data[]>([]), [prepared, setPrepared] = useState(false), [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true);
  const load = async () => {
    setBusy(true); setPrepared(false); setError('');
    try { const result = await run('knowledge.assertion.prepare', { readHandle, query }); if (alive.current) { setIdentities(result.identities || []); setMore(result.hasMoreIdentities === true); setPrepared(true); } }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (alive.current) setBusy(false); }
  };
  useEffect(() => { alive.current = true; void load(); return () => { alive.current = false; }; }, []);
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
    if (busy || !prepared) return; setBusy(true); setError('');
    try { const result = await run('knowledge.assertion.review', { readHandle, subject: selection(subject, subjectLabel), relationTarget: target ? selection(target, object) : null, kind, predicate, object, condition, ...times }); if (alive.current) onReview(result); else void run('knowledge.close', { handle: result.reviewHandle }).catch(() => {}); }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (alive.current) setBusy(false); }
  };
  return <section className="pw-source-record"><h3>主体与关系</h3>
    <p className="pw-muted">只修改这条待审候选，原来源继续保留。请明确选择主体；同名不会自动合并。采用候选仍需另行确认。</p>
    <TextField label="查找已有身份" value={query} onChange={setQuery} maxLength={160} isDisabled={busy} /><Button variant="ghost" disabled={busy} onClick={() => { void load(); }}>读取匹配的原身份</Button>
    {more && <p className="pw-muted">本次最多显示 64 个身份，请缩小查询范围。</p>}
    <SelectField label="这条陈述的主体" value={subject} isDisabled={busy || !prepared} options={[...(old.subjectId && old.subjectId !== 'owner' ? [{ id: 'candidate', label: `保留原主体：${old.subjectLabel}` }] : []), ...choices]} onChange={value => { setSubject(value); const identity = identities.find(row => row.choiceHandle === value); setSubjectLabel(value === 'owner' ? '主人' : identity?.label || (value === 'candidate' ? old.subjectLabel : '')); }} />
    {subject && subject !== 'owner' && <TextField label="主体显示名" value={subjectLabel} onChange={setSubjectLabel} maxLength={160} isDisabled={busy} />}
    <TextField label="描述哪件事，例如偏好或负责项目" value={predicate} onChange={setPredicate} maxLength={160} isDisabled={busy} />
    <TextAreaField label="事实值或关系目标名称" value={object} onChange={setObject} maxLength={4000} isDisabled={busy} />
    <SelectField label="关系目标（可选）" value={target} isDisabled={busy || !prepared} options={[{ id: '', label: '仅保留文字，不关联身份' }, ...(old.objectId && old.objectId !== 'owner' ? [{ id: 'candidate', label: '保留原关系目标' }] : []), ...choices]} onChange={value => { setTarget(value); const identity = identities.find(row => row.choiceHandle === value); if (value === 'owner') setObject('主人'); else if (identity) setObject(identity.label); else if (value === 'candidate') setObject(old.object || ''); }} />
    <SelectField label="陈述类型" value={kind} onChange={setKind} isDisabled={busy} options={[{ id: 'inferred', label: '推测' }, { id: 'told', label: '转述' }, { id: 'imagined', label: '计划或想象，未证实发生' }]} />
    <TextField label="适用条件（可选）" value={condition} onChange={setCondition} maxLength={1000} isDisabled={busy} />
    <details><summary>核对时间</summary><p className="pw-muted">未知请留空。YYYY-MM-DD 按手机时区解释，也可保留完整时间。计划不会因为日期已过而变成现实事实。</p>{[['eventTime', '发生或计划时间'], ['validFrom', '生效时间'], ['validTo', '结束时间'], ['reviewAfter', '下次核对时间']].map(([key, label]) => <TextField key={key} label={label} value={times[key]} onChange={value => setTimes(old => ({ ...old, [key]: value }))} isDisabled={busy} />)}</details>
    {error && <p role="alert">{error}</p>}<Button variant="secondary" disabled={busy || !prepared || !subject || !predicate.trim() || !object.trim()} onClick={() => { void prepare(); }}>核对本次主体与关系修改</Button>
  </section>;
}
