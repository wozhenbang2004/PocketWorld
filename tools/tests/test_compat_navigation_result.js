// Exact legacy route mapping is exercised by AdvancedPanelRouteTest. Result confirmation
// now uses the original native review handle, whose run/digest guards remain covered by
// PocketWebTaskReviewsTest; the frontend retains its original navigation/continuation chain.
require('./run-unified-frontend-regressions.cjs')([
  'tests/bridge-navigation.test.ts',
  'tests/task-continuation.test.ts',
  'tests/task-outcome.test.ts',
  'tests/task-decision-surfaces.test.ts',
]);
