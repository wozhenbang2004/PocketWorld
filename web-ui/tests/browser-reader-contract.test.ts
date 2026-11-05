import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { browserReaderSurface, captureReaderPosition, restoreReaderPosition, readerCoverage, readerDomain, readerFieldCurrent, readerFieldPayload, readerPin, readerTime, sameReader, sourceFieldRefs, type ReaderState, type ReaderField } from '../src/reader-contract.ts';

const original = (): ReaderState => ({ windowId: 'native-window', generation: 3, sourceFingerprint: 'historical-fingerprint', ready: true, loading: false, error: '', field: null,
  source: { title: '原保存来源', safeUrl: 'https://example.com/original', excerpt: '<script>untrusted</script>', capturedAt: 1234, sourceId: 'original-source', sourceFingerprint: 'historical-fingerprint', runId: 'old-run', epoch: 1, untrusted: true, verified: false, urlQueryOmitted: true,
    coverage: { kind: 'semantic', partial: true, wholePage: false, embeddedFrame: false, inlineHostPage: false, continuationAvailable: true },
    projection: { mode: 'list', revision: 'original-revision', partial: true, omittedRows: 0, rows: [{ index: 3, text: '原第四项', fieldRef: 'original-field' }, { index: 7, text: '原第八项', fieldRef: 'second-field' }] } } });
const selected: ReaderField = { fieldRef: 'original-field', revision: 'original-revision', mode: 'list', text: '原第四项', rowIndex: 3, columnIndex: -1, headers: [], partial: true, untrusted: true, verified: false };

test('native surface routes before ordinary workspace without using URL parameters', () => {
  assert.equal(browserReaderSurface({ host: { surface: 'browser_sources' } }), true);
  assert.equal(browserReaderSurface({ surface: 'browser_sources' }), true);
  assert.equal(browserReaderSurface({ surface: 'business' }), false);
});
test('late callbacks and equal source revisions are fenced by original window and generation', () => {
  const current = original(), pin = readerPin(current);
  assert.equal(sameReader(pin, current), true);
  for (const patch of [{ generation: 4 }, { windowId: 'successor' }, { sourceFingerprint: 'replacement' }, { ready: false }])
    assert.equal(sameReader(pin, { ...current, ...patch }), false);
  assert.throws(() => readerPin({ ...current, ready: false }));
});
test('field requests contain only original native window pin and a currently offered field', () => {
  const state = original();
  assert.deepEqual(readerFieldPayload(state, 'original-field'), { windowId: 'native-window', generation: 3, sourceFingerprint: 'historical-fingerprint', fieldRef: 'original-field' });
  assert.throws(() => readerFieldPayload(state, 'another-task-field'));
  assert.throws(() => readerFieldPayload({ ...state, loading: true }, 'original-field'));
  assert.throws(() => readerFieldPayload({ ...state, source: null }, 'original-field'));
  assert.deepEqual(sourceFieldRefs(state.source!), ['original-field', 'second-field']);
});
test('historical fields remain bound to their saved projection, not current task run', () => {
  const state = original();
  assert.equal(readerFieldCurrent(selected, state.source!), true);
  assert.equal(readerFieldCurrent({ ...selected, revision: 'new-revision' }, state.source!), false);
  assert.equal(readerFieldCurrent({ ...selected, fieldRef: 'another-field' }, state.source!), false);
  assert.equal(state.source!.runId, 'old-run');
});
test('coverage does not upgrade retained excerpt or complete selected range into whole-page evidence', () => {
  const source = original().source!;
  assert.match(readerCoverage(source), /只保存了部分内容/);
  source.coverage.partial = false;
  assert.match(readerCoverage(source), /不代表整页/);
  source.coverage.kind = 'http';
  assert.match(readerCoverage(source), /不包含渲染后的网页/);
  for (const value of ['javascript:alert(1)', 'https://a.test/?private=1', 'https://a:b@a.test/', 'https://a.test/#secret']) assert.equal(readerDomain(value), '已保存来源');
});
test('production reader uses retained controls, full reading window, and safe text only', () => {
  const code = readFileSync(new URL('../src/BrowserSourceReader.tsx', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../src/browser-source-reader.css', import.meta.url), 'utf8');
  assert.match(code, /from '\.\/forms-library'/);
  assert.match(code, /shouldScaleBackground=\{false\}/);
  assert.match(code, /<Skeleton/);
  assert.match(code, /readerFieldPayload\(current, ref\)/);
  assert.match(code, /readingPosition\.current/);
  assert.match(code, /focus\(\{ preventScroll: true \}\)/);
  assert.doesNotMatch(code, /dangerouslySetInnerHTML|<iframe|<img|<a\s|href=|sources\.(open|read)|taskId:/);
  assert.match(css, /min-width: 44px; min-height: 44px/);
});
test('native endpoint exposes a dedicated bounded read catalogue and no business bridge', () => {
  const code = readFileSync(new URL('../../app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebBrowserSources.kt', import.meta.url), 'utf8');
  assert.match(code, /setOf\("app.ready", "browserSources.read", "browserSources.field", "browserSources.refresh", "browserSources.close"\)/);
  assert.match(code, /originalBinding == null \|\| originalBinding == actualBinding/);
  assert.match(code, /current\(original, originalDocument\).*operation != request/);
  assert.match(code, /source = null; field = null; allowedFields = emptySet\(\)/);
  assert.doesNotMatch(code, /PocketWebBridge|taskForOwner|snapshotWithStop|openSourceForOwner|startForOwner|prepareRecipe|saveRecipe|profileId/);
});

test('invalid saved timestamps do not crash the reading surface', () => {
  assert.equal(readerTime(9e18).label, '保存时间待核对');
  assert.equal(readerTime(NaN).iso, undefined);
  assert.equal(readerTime(1234).iso, '1970-01-01T00:00:01.234Z');
});

test('wide-table return restores both axes only for the same original source identity', () => {
  const source = original().source!;
  const saved = captureReaderPosition(source, 620, 860);
  assert.equal(restoreReaderPosition(source, saved).scrollTop, 620);
  assert.equal(restoreReaderPosition(source, saved).tableScrollLeft, 860);
  for (const change of [{ sourceId: 'other' }, { sourceFingerprint: 'new' }, { runId: 'new' }, { epoch: 2 }])
    assert.equal(restoreReaderPosition({ ...source, ...change }, saved).tableScrollLeft, 0);
  assert.equal(restoreReaderPosition({ ...source, projection: { ...source.projection!, revision: 'changed' } }, saved).scrollTop, 0);
  assert.deepEqual(Object.keys(saved).sort(), ['identity', 'scrollTop', 'tableScrollLeft']);
  assert.equal(captureReaderPosition(source, NaN, -1).tableScrollLeft, 0);
});

test('real native entries replace the old nested readers while preserving the original recipe chain', () => {
  const panel = readFileSync(new URL('../../app/src/main/kotlin/dev/pocketengine/browser/BrowserTaskOwnerPanel.kt', import.meta.url), 'utf8');
  const endpoint = readFileSync(new URL('../../app/src/main/kotlin/dev/pocketengine/browser/BrowserWorkspaceEndpoint.kt', import.meta.url), 'utf8');
  const workspace = readFileSync(new URL('../src/BrowserWorkspace.tsx', import.meta.url), 'utf8');
  assert.match(panel, /fun obscuresPage\(\): Boolean = expanded \|\| sourceReader != null/);
  assert.match(panel, /sourceReader = reader\s+reader\.open\(\)/);
  assert.match(panel, /onDismissed = \{[\s\S]*?sourceReader === reader[\s\S]*?sourceReader = null/);
  assert.match(panel, /sourceReader\?\.let \{ it\.handleBack\(\); return true \}/);
  assert.match(endpoint, /owner\.openSavedSource\(source\.getString\("taskId"\), source\.getString\("sourceId"\)\)/);
  assert.match(endpoint, /owner\.openSavedField\(taskId, field\)/);
  assert.match(endpoint, /source\(frame, taskId, BrowserStructuredSource\.sourceId\(taskId, field\)\)/);
  assert.doesNotMatch(panel, /sourceDetails\?\.let|fieldDetails\?\.let|SavedStructuredSource/);
  assert.match(workspace, /prepareRecipe/);
  assert.match(panel, /PocketWebHost\.browserWorkspace/);
  assert.doesNotMatch(panel, /ComposeView|@Composable/);
  assert.match(endpoint, /owner\.host\.prepareRecipeForOwner\(source\.getString\("taskId"\), source\.getString\("sourceId"\), query\)/);
  assert.match(endpoint, /owner\.host\.saveRecipeForOwner\(display\.getString\("taskId"\), display\.getString\("sourceId"\), display\.getString\("query"\), display\.getString\("fingerprint"\), enabled\)/);
});
test('reader host is an explicit fixed-origin factory with reader-only failure exits', () => {
  const host = readFileSync(new URL('../../app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebHost.kt', import.meta.url), 'utf8');
  const window = readFileSync(new URL('../../app/src/main/kotlin/dev/pocketengine/browser/BrowserSourceReaderWindow.kt', import.meta.url), 'utf8');
  const main = readFileSync(new URL('../src/main.tsx', import.meta.url), 'utf8');
  assert.match(host, /class PocketWebHost private constructor/);
  assert.match(host, /fun browserSourceReader\(activity: dev\.pocketengine\.BrowserActivity/);
  assert.match(host, /if \(readerFailure != null\) \{ if \(request\.isForMainFrame\) failReader\(\); return true \}/);
  assert.match(host, /onRenderProcessGone[\s\S]*?if \(failReader\(\)\) return true[\s\S]*?showReopenPrompt\(\)/);
  assert.match(host, /private fun showReopenPrompt\(\) \{\s+if \(closed \|\| reopenShown\) return\s+if \(failReader\(\)\) return/);
  assert.match(host, /documentFence\.accepts\(ticket, token, bridge\.canInteract\(\)\)/);
  assert.match(window, /ComponentDialog\(activity/);
  assert.match(window, /onWindowFocusChanged\(hasFocus: Boolean\)/);
  assert.doesNotMatch(window, /activity\.hasWindowFocus|activity\.finish|activity\.recreate|AlertDialog|ComposeView/);
  assert.ok(main.indexOf('if (browserReaderSurface(snapshot))') < main.indexOf('return !snapshot.host?.connected'));
});
