type Data = Record<string, any>;
type Version = { id: string; revision: number; text: string; protected: boolean };
type Plan = { decision: 'replace' | 'coexist'; selectedId: string; reason: string; versions: Version[] };
const whole = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n > 0;

/** Decode the native review, never the mutable selections underneath the confirmation dialog. */
export function reviewedConflict(value: Data | null): Plan | null {
  if (value?.operation !== 'conflict' || typeof value.reviewHandle !== 'string' || !value.reviewHandle ||
      value.executionAuthorized !== false || !Array.isArray(value.beforeRecords) ||
      value.beforeRecords.length < 1 || value.beforeRecords.length > 20 || typeof value.reason !== 'string' || !value.reason.trim()) return null;
  const versions: Version[] = [], ids = new Set<string>();
  let group = '';
  for (const row of value.beforeRecords) {
    if (!row || typeof row.id !== 'string' || !row.id || ids.has(row.id) || !whole(row.revision) || row.revision >= Number.MAX_SAFE_INTEGER ||
        typeof row.text !== 'string' || !row.text.trim() || (row.reviewRole === undefined ? row.assertionStatus !== 'pending_conflict' : row.reviewRole !== 'protected_current' || row.assertionStatus !== 'current')) return null;
    const a = row.assertion;
    const fields = [row.ownerId, row.spaceId, a?.subjectId, a?.predicate, a?.condition];
    if (!fields.every(x => typeof x === 'string') || fields.slice(0, 4).some(x => !x)) return null;
    const key = JSON.stringify(fields);
    if (group && group !== key) return null;
    group = key; ids.add(row.id);
    versions.push({ id: row.id, revision: row.revision, text: row.text, protected: row.reviewRole === 'protected_current' });
  }
  const { decision, selectedId, reason } = value;
  if (!(decision === 'replace' && ids.has(selectedId) || decision === 'coexist' && selectedId === '' && versions.length >= 2)) return null;
  return { decision, selectedId, reason, versions };
}

export function conflictReceiptMatches(result: Data, plan: Plan): boolean {
  const actual = result?.resolution;
  if (result?.originalEvidenceRetained !== true || result.executionAuthorized !== false || !actual ||
      actual.decision !== plan.decision || actual.selectedId !== plan.selectedId || actual.reason !== plan.reason ||
      !whole(actual.at) || !Array.isArray(actual.records) || actual.records.length !== plan.versions.length) return false;
  const expected = new Map(plan.versions.map(row => [row.id, row.revision + 1]));
  return actual.records.every((row: Data) => row && expected.has(row.id) && expected.get(row.id) === row.revision && expected.delete(row.id)) && expected.size === 0;
}

export function MemoryConflictDecision({ plan }: { plan: Plan | null }) {
  if (!plan) return <p className="pw-error" role="alert">没能核对本次要保留的版本或并存范围。请返回重新选择，没有提交。</p>;
  const render = (v: Version) => <div key={v.id}><p className="pw-reading-copy">{v.text}</p><p className="pw-muted">修订 {v.revision}{v.protected ? ' · 此前受保护的当前版本' : ''}</p></div>;
  if (plan.decision === 'coexist') return <section className="pw-memory-review-version">
    <h3>这 {plan.versions.length} 条将并存</h3><p className="pw-muted">不挑选唯一版本。是否适用于下一次回答，仍取决于当时的来源、条件和权限。</p>
    {plan.versions.map(render)}
  </section>;
  const selected = plan.versions.find(v => v.id === plan.selectedId)!, others = plan.versions.filter(v => v !== selected);
  return <><section className="pw-memory-review-version"><h3>这次只保留这一条作为当前版本</h3>{render(selected)}</section>
    {others.length > 0 && <section className="pw-memory-review-version"><h3>不再作为这组的当前版本</h3>
      <p className="pw-muted">原记录和证据保留，不是删除原话。</p>{others.map(render)}</section>}</>;
}
