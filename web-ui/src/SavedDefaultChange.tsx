const fields: Record<string, [string, Record<string, string>]> = {
  language: ['回答语言', { auto: '跟随对话', 'zh-CN': '中文', en: 'English', ja: '日本語' }],
  response_detail: ['回答详略', { concise: '简短', standard: '跟随问题', detailed: '详细' }],
  conclusionFirst: ['先给结论', { true: '开启', false: '关闭' }], includeExamples: ['举例说明', { true: '开启', false: '关闭' }],
  operation_pace: ['做事节奏', { fast: '高效', standard: '均衡', careful: '细致' }],
  initiative: ['主动建议', { requested: '按需', balanced: '适度', proactive: '主动建议' }],
};

/** Only the original native one-field projection, never raw arguments or the assistant's answer. */
export function SavedDefaultChange({ value }: { value: unknown }) {
  const row = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
  const key = row.key, field = typeof key === 'string' && Object.hasOwn(fields, key) ? fields[key] : null;
  const label = (v: unknown) => field && (typeof v === 'boolean' ? key === 'conclusionFirst' || key === 'includeExamples' : typeof v === 'string' && key !== 'conclusionFirst' && key !== 'includeExamples') &&
    Object.hasOwn(field[1], String(v)) ? field[1][String(v)] : '';
  const before = label(row.before), after = label(row.after);
  if (!field || row.format !== 'pocketworld.owner-default-change.v1' || !before || !after || !Number.isSafeInteger(row.beforeRevision) || Number(row.beforeRevision) < 1 ||
      !Number.isSafeInteger(row.afterRevision) || row.afterRevision !== Number(row.beforeRevision) + 1 || row.changed !== (row.before !== row.after))
    return <p className="pw-trace-note">没有读到能证明这项默认偏好如何变化的原回执。</p>;
  return <section className="pw-trace-detail-source" aria-label="这次保存的默认偏好"><h3>{field[0]}</h3>
    <p>{row.changed ? `${before} → ${after}` : `当时已经是「${after}」，默认值没有改变。`}</p>
    <small>这是这次操作的结果，不代表现在的设置。</small>
  </section>;
}
