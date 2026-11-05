import assert from 'node:assert/strict';
import test from 'node:test';
import { emptyTeamEvidence, teamEvidenceOrigins, teamEvidenceSelection, TeamEvidenceConsent } from '../src/team-evidence.ts';

test('research sites remain explicit canonical public HTTPS origins, bounded to four', () => {
  assert.deepEqual(teamEvidenceOrigins('https://example.org/\nhttps://docs.example.org'), ['https://example.org', 'https://docs.example.org']);
  for (const value of ['http://example.org', 'https://example.org/path', 'https://example.org?q=1', 'https://user:pass@example.org', 'https://127.0.0.1', 'https://[::1]', 'https://localhost', 'https://x.localhost', 'https://EXAMPLE.org', 'https://example.org\nhttps://example.org', Array.from({ length: 5 }, (_, i) => `https://s${i}.example.org`).join('\n')]) assert.throws(() => teamEvidenceOrigins(value));
});
test('only original choice handles and exact bounded source/skill ranges cross the bridge', () => {
  const draft = emptyTeamEvidence(); draft.records = ['record-native-handle']; draft.projectFiles = ['file-native-handle'];
  draft.sources = [{ choiceHandle: 'source-native-handle', offset: '4', chars: '24', sourceRef: { forged: true } }];
  draft.skills = [{ choiceHandle: 'skill-native-handle', paths: 'SKILL.md\nreferences/read.md', digest: 'forged' }];
  const selection = teamEvidenceSelection(draft);
  assert.deepEqual(selection.sources, [{ choiceHandle: 'source-native-handle', offset: 4, chars: 24 }]);
  assert.deepEqual(selection.skills, [{ choiceHandle: 'skill-native-handle', paths: ['SKILL.md', 'references/read.md'] }]);
  for (const invalid of ['-1', '1e3', '0.5', '24001']) assert.throws(() => teamEvidenceSelection({ ...draft, sources: [{ choiceHandle: 'source', offset: '0', chars: invalid }] }));
  assert.throws(() => teamEvidenceSelection({ ...draft, skills: [{ choiceHandle: 'skill', paths: '../secret' }] }));
  assert.throws(() => teamEvidenceSelection({ ...draft, projectFiles: ['file', 'file'] }));
});
test('changing a draft or replacing a review cannot reuse an old single-member or shared confirmation', () => {
  for (const handle of ['member-review', 'current-members-review']) {
    const consent = new TeamEvidenceConsent(); consent.show(handle); consent.change(true); const old = consent.capture();
    consent.clear(); assert.equal(consent.confirm(old), false); consent.show(handle + '-next'); consent.change(true);
    assert.equal(consent.confirm(old), false); assert.equal(consent.confirm(consent.capture()), true); assert.equal(consent.confirm(consent.capture()), false);
  }
});
test('withdrawing and then restoring consent still retires the already captured callback', () => {
  const consent = new TeamEvidenceConsent(); consent.show('exact-review'); consent.change(true); const old = consent.capture();
  consent.change(false); assert.equal(consent.confirm(old), false); consent.change(true); assert.equal(consent.confirm(old), false);
  assert.equal(consent.confirm(consent.capture()), true);
});
