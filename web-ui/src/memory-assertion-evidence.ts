type Data = Record<string, any>;
const object = (value: unknown): Data | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : null;

/** Display the native bounded read only when it matches this exact formal evidence span. */
export function assertionEvidenceText(spanValue: unknown, evidenceValue: unknown): string | null {
  const span = object(spanValue), source = object(span?.sourceRef);
  if (!span || !source || !Number.isSafeInteger(source.revision) || source.revision < 1
      || !Number.isSafeInteger(span.offset) || span.offset < 0 || !Number.isSafeInteger(span.chars) || span.chars < 1
      || typeof span.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(span.sha256) || !Array.isArray(evidenceValue)) return null;
  const fields = ['kind', 'sourceId', 'revision', 'ownerId', 'spaceId'];
  if (fields.some(key => key !== 'revision' && (typeof source[key] !== 'string' || !source[key]))) return null;
  for (const value of evidenceValue) {
    const evidence = object(value), ref = object(evidence?.sourceRef);
    if (evidence && ref && fields.every(key => ref[key] === source[key]) && evidence.offset === span.offset
        && evidence.chars === span.chars && evidence.sha256 === span.sha256 && evidence.verifiedOriginalSpan === true
        && evidence.assertionTruthVerified === false && typeof evidence.text === 'string' && evidence.text.length === span.chars) return evidence.text;
  }
  return null;
}
