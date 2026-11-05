// The old HTML chat sender is retired. Native saved-ticket/draft transfer stays covered by
// CompatibilityChatHandoffTest; these are the one frontend's original-run cancel and
// explicit continuation/no-composer-ack regressions, with no automatic send path.
require('./run-unified-frontend-regressions.cjs')([
  'tests/chat-cancel.test.ts',
  'tests/task-continuation.test.ts',
  'tests/composer-context-journeys.test.ts',
]);
