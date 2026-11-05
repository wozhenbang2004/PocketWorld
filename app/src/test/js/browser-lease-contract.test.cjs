// Browser authority remains in the original native browser host. The retired page's
// automatic acquire/observe/release loop is not reimplemented in JavaScript. Exercise the
// production original-window/task controls, bounded reader, stale callback and return gates.
require('../../../../tools/tests/run-unified-frontend-regressions.cjs')([
  'tests/browser-workspace-contract.test.ts',
  'tests/browser-reader-contract.test.ts',
  'tests/browser-chrome-contract.test.ts',
  'tests/conversation-organization.test.ts',
]);
