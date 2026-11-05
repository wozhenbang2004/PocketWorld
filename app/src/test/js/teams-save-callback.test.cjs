// The JSON-text legacy team editor is retired. The one editor retains supported member,
// source and host identities through its real draft helpers; runtime grants are not drafts.
require('../../../../tools/tests/run-unified-frontend-regressions.cjs')([
  'tests/team-phone.test.ts',
  'tests/harness-capabilities.test.ts',
  'tests/team-production.test.ts',
  'tests/team-evidence.test.ts',
]);
