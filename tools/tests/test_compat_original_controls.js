// The original global-form/phone bridge is retired. CompatibilityOwnerControlsTest keeps
// all five native queue/CAS/lifecycle cases through PocketWorldController and SettingsStore.
// These production helpers and surfaces fence the displayed phone and model identities.
require('./run-unified-frontend-regressions.cjs')([
  'tests/phone-start.test.ts',
  'tests/model-connection.test.ts',
  'tests/model-connection-policy.test.ts',
  'tests/composer-api-models.test.ts',
  'tests/model-data-settings-journeys.test.ts',
]);
