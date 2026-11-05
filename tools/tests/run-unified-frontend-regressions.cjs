// Retained legacy CI entry points now execute regressions for the one production frontend.
// No old page, copied business implementation or compatibility renderer is retained.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
module.exports = function runUnifiedFrontendRegressions(files) {
  const repository = path.resolve(__dirname, '../..');
  assert.equal(fs.existsSync(path.join(repository, 'app/src/main/assets/index.html')), false,
    'The retired root business page must not return as a second frontend');
  const entry = fs.readFileSync(path.join(repository, 'app/src/main/java/dev/pocketengine/MainActivity.java'), 'utf8');
  assert.doesNotMatch(entry, /\bWebView\b|addJavascriptInterface|loadDataWithBaseURL|setContentView/,
    'The retained Android component must remain navigation-only');
  assert.match(entry, /new Intent\(this, ComposeActivity\.class\)/);
  const nodeOptions = process.execArgv.filter(value => !value.startsWith('--test') && value !== '--experimental-strip-types');
  for (const file of files) assert.match(file, /^tests\/[A-Za-z0-9.-]+\.test\.ts$/);
  const result = spawnSync(process.execPath, [...nodeOptions, '--experimental-strip-types', '--test', ...files],
    { cwd: path.join(repository, 'web-ui'), stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exitCode = result.status || 1;
};
