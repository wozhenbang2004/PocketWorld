import { assertionTimes, assertionTime, displayAssertionTime, type AssertionTime } from './memory-assertion-time';
type Row = Record<string, any>;
type End = { mode: string; label: string; type: string; id?: string; previous?: string; donor?: string; donorRevision?: number; donorRole?: string };
type TimeReview = { label: string; before: AssertionTime; after: AssertionTime; same: boolean };
type Decision = { subject: End; target: End | null; kind: string; predicate: string; object: string; condition: string; dates: TimeReview[]; removesQuote: boolean; removesAssertedTime: boolean };
const text = (v: any): v is string => typeof v === 'string' && !!v.trim();
const rev = (v: any): v is number => Number.isSafeInteger(v) && v > 0;
const kinds: Record<string, string> = { inferred: '推测', told: '转述', imagined: '计划或设想，未证实发生' };

function end(choice: Row, proof: Row, before: Row, role: 'subject' | 'object'): End | null {
  if (!choice || !proof || proof.mode !== choice.mode) return null;
  const { mode, type, id, originalLabel } = proof;
  if (mode === 'owner') return type === 'owner' && id === 'owner' && text(originalLabel) ? { mode, label: originalLabel, type, id } : null;
  if (!['person', 'entity'].includes(type) || !text(choice.label)) return null;
  if (mode === 'new') return id === undefined && choice.type === type ? { mode, label: choice.label, type } : null;
  if (!text(id) || id === 'owner' || !text(originalLabel)) return null;
  if (mode === 'candidate') {
    const a = before.assertion, subject = role === 'subject';
    if (choice.role !== role || !a || a[subject ? 'subjectId' : 'objectId'] !== id || a[subject ? 'subjectType' : 'objectType'] !== type ||
        a[subject ? 'subjectLabel' : 'object'] !== originalLabel) return null;
  } else if (mode !== 'existing' || !text(choice.recordId) || !rev(choice.recordRevision) || !['subject', 'object'].includes(choice.role)) return null;
  return { mode, label: choice.label, previous: originalLabel, type, id, ...(mode === 'existing' ? { donor: choice.recordId, donorRevision: choice.recordRevision, donorRole: choice.role } : {}) };
}

export function reviewedCandidateIdentity(v: Row | null): Decision | null {
  if (!v || v.operation !== 'assertion' || !text(v.reviewHandle) || v.executionAuthorized !== false) return null;
  const a = v.assertion, b = v.before, proof = v.endpoints;
  if (!a || !b || !proof || b.status !== 'pending' || !text(b.ownerId) || !text(b.spaceId) || !text(b.id) || !rev(b.revision) || a.id !== b.id || a.revision !== b.revision ||
      !Object.hasOwn(kinds, a.kind) || !text(a.predicate) || !text(a.object) || typeof a.condition !== 'string') return null;
  const subject = end(a.subject, proof.subject, b, 'subject');
  const target = a.relationTarget === null ? null : end(a.relationTarget, proof.relationTarget, b, 'object');
  if (!subject || (a.relationTarget === null ? proof.relationTarget !== null : !target || target.label !== a.object)) return null;
  const dates: TimeReview[] = [];
  for (const [key, label] of assertionTimes) {
    if (!Object.hasOwn(a, key)) return null;
    const raw = a[key], before = assertionTime(b.assertion?.[key]);
    if (raw && typeof raw === 'object' && !Array.isArray(raw) && Object.keys(raw).length === 1 && raw.keepOriginalOutOfRange === true) {
      if (before.kind !== 'outside') return null;
      dates.push({ label, before, after: before, same: true }); continue;
    }
    const after = assertionTime(raw);
    if (raw !== null && after.kind !== 'date') return null;
    if (before.kind === 'unknown' && after.kind === 'unknown') continue;
    dates.push({ label, before, after, same: before.kind === 'date' && after.kind === 'date' && before.ms === after.ms });
  }
  return { subject, target, kind: kinds[a.kind], predicate: a.predicate, object: a.object, condition: a.condition, dates,
    removesAssertedTime: typeof b.assertion?.assertedAt === 'number', removesQuote: b.assertion?.kind === 'user_said' || (Array.isArray(b.assertion?.evidenceSpans) && b.assertion.evidenceSpans.length > 0) };
}

function Endpoint({ heading, value }: { heading: string; value: End }) {
  const noun = value.type === 'person' ? '人物' : '实体';
  return <section className="pw-memory-review-version"><h3>{heading}：{value.label}</h3>
    <p>{value.mode === 'owner' ? '明确指向你本人。' : value.mode === 'new' ? `新建独立${noun}，即使同名也不会合并。此时尚未创建身份。` :
      value.mode === 'candidate' ? `保留这条候选原有的${noun}身份，不另建。` : `复用已保存记忆里的${noun}身份，不按名字匹配。该原记录的修订将作为这条候选的依赖。`}</p>
    {value.previous && value.previous !== value.label && <p className="pw-muted">该身份原显示名：{value.previous}。这条候选将使用「{value.label}」显示。</p>}
    {value.id && value.mode !== 'owner' && <details><summary>核对身份定位</summary><p className="pw-muted" style={{ overflowWrap: 'anywhere' }}>稳定身份：{value.id}{value.donor && <><br />来自原记录 {value.donor} · 修订 {value.donorRevision} · {value.donorRole === 'subject' ? '主体' : '关系目标'}</>}</p></details>}
  </section>;
}

export function CandidateIdentityDecision({ decision }: { decision: Decision | null }) {
  if (!decision) return <p className="pw-error" role="alert">未能对齐原候选、主体或关系的确切选择。请返回重新核对，未提交。</p>;
  return <><Endpoint heading="这条说的是谁" value={decision.subject} />
    {decision.target ? <Endpoint heading="明确关联到" value={decision.target} /> : <p className="pw-muted">只保留描述文字，不关联到另一个人物或实体。</p>}
    <section className="pw-memory-review-version"><h3>核对后的陈述</h3><p>{decision.kind} · {decision.predicate} · {decision.object}</p>
      {decision.condition && <p>适用条件：{decision.condition}</p>}{decision.dates.map(t => <div key={t.label}><strong>{t.label}</strong>{t.same
        ? <p className="pw-muted">保持不变：{t.before.kind === 'outside' ? '原数值由本机原样保留，不换算日期。' : displayAssertionTime(t.after)}</p>
        : <p>原来：{displayAssertionTime(t.before)}<br />本次：{t.after.kind === 'unknown' ? '清除这项时间，改为未知' : displayAssertionTime(t.after)}</p>}</div>)}</section>
    <p className="pw-muted">{decision.removesQuote ? '旧的逐字引文不作为修改后陈述的原话证明；原来源不删除。' : '这次核对不签发原话证明。'}{decision.removesAssertedTime && '旧的陈述时刻也不沿用。'}只更新待审候选，不会采用为正式记忆。</p></>;
}
