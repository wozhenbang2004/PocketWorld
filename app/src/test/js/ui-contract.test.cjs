// Legacy HTML admission/token/redaction code was removed with its renderer. Its active
// business routes use the original native phone setup and reviewed proxy host; retain the
// exact current frontend settings, admission, secret-clearing and explicit decision gates.
require('../../../../tools/tests/run-unified-frontend-regressions.cjs')([
  'tests/background-settings.test.ts',
  'tests/phone-start.test.ts',
  'tests/phone-workspace-journeys.test.ts',
  'tests/proxy-workspace-journeys.test.ts',
]);
