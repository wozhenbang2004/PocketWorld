import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Production handlers and presentation with hook/control doubles only.
// These checks do not render graphics or claim DOM, visual or Android acceptance.
type Data = Record<string, any>;
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const secondary = read('../src/Secondary.tsx');
function fixture(name: string, initial: Data) {
  const source = ts.createSourceFile('Secondary.tsx', secondary, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const names = new Set(['object', 'list', 'text', 'first', 'recordId', 'statusText', 'timeText', 'modelConnectionRows', 'MODEL_PROVIDERS', 'ARCHIVE_SECTIONS', 'bytesText', 'Card', 'Facts', 'Empty', 'SourceRecord', name]);
  const body = source.statements.filter(node => ts.isFunctionDeclaration(node) ? names.has(node.name?.text || '') : ts.isVariableStatement(node) && node.declarationList.declarations.some(d => names.has(d.name.getText(source)))).map(node => node.getText(source)).join('\n');
  const journeySource = ts.createSourceFile('DataSettingsJourney.tsx', read('../src/DataSettingsJourney.tsx'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const journey = journeySource.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(journeySource).replace(/^export /, '')).join('\n');
  const compiled = ts.transpileModule(journey + '\n' + body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], opened: Data[] = [], focused: string[] = [];
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  const scroller = { scrollTop: 0 };
  const nodes = (node: any): any[] => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const root = { closest: () => scroller, focus: () => focused.push('root'),
    querySelector: () => ({ focus: () => focused.push('heading') }),
    querySelectorAll: () => nodes(tree).filter(node => node.props['data-operation-entry']).map(node => ({ dataset: { operationEntry: node.props['data-operation-entry'] }, focus: () => focused.push(node.props['data-operation-entry']) })) };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => void | (() => void), deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: Data | null, ...children: any[]): any => {
    const values = children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false);
    if (props?.ref && props.className?.includes('pw-data-workspace')) props.ref.current = root;
    return typeof type === 'function' ? type({ ...props, children: values }) : { type, props: props || {}, children: values };
  } };
  const controls = Object.fromEntries(['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'StretchSwitch', 'TextField', 'SelectField', 'SettingsDialog', 'PressButton', 'CapabilityAction', 'ArchivePassword', 'ArchiveConfigurationDetails', 'Tabs', 'TabList', 'Tab', 'TabPanel', 'RiArrowLeftLine', 'RiArrowRightSLine', 'RiLink', 'RiKey2Line', 'RiAddLine', 'RiSettings3Line', 'RiFileList3Line'].map(key => [key, key]));
  const dependencies = { React, useState, useRef, useEffect, useLayoutEffect: useEffect, ...controls,
    document: { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} }, window: { addEventListener() {}, removeEventListener() {} } };
  const Component = new Function(...Object.keys(dependencies), compiled + '\nreturn ' + name + ';')(...Object.values(dependencies));
  const props = { data: {}, state: {}, supported: () => true, busy: false, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); return {}; }, open: (value: Data) => opened.push(value), navigate: (page: string) => opened.push({ page }), registerTransientBack: (handler: any) => { back = handler; }, ...initial };
  const content = (node: any, visible = false): string => node && typeof node === 'object' ? visible && (node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen || node.type === 'AccordionItem') ? '' : (node.children || []).map((child: any) => content(child, visible)).join('') : String(node ?? '');
  const render = () => { do { dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected production element'); return node; };
  render();
  return { calls, opened, focused, scroller, render, find, get all() { return nodes(tree); }, get text() { return content(tree); }, get visibleText() { return content(tree, true); },
    get view() { return nodes(tree).find(node => node.props['data-data-view'])?.props['data-data-view']; },
    entry(id: string) { find(node => node.props['data-operation-entry'] === id).props.onClick(); render(); },
    back() { const result = back?.(); render(); return result; },
    change(next: Data) { Object.assign(props, next); render(); },
    click(label: string) { const node = find(node => ['Button', 'PressButton'].includes(node.type) && (node.props['aria-label'] === label || content(node) === label)); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    async action(action: string) { const node = find(node => node.type === 'CapabilityAction' && node.props.action === action); assert.ok(!node.props.disabled); await node.props.run(action, node.props.args); render(); },
  };
}
const connection = { id: 'connection-a', revision: 7, name: '原服务 A', provider: 'anthropic', baseUrl: 'https://a.invalid', model: 'model-a', enabled: true, hasApiKey: true };
const directory = { connections: { selectedConnectionId: connection.id, connections: [connection, { ...connection, id: 'connection-b', name: '原服务 B' }] } };

test('all connection rows open overview, and configuration is a separate explicit step', () => {
  const f = fixture('ModelConnections', { data: directory, state: { baseUrl: connection.baseUrl } });
  f.click('查看当前 API 连接'); assert.deepEqual(f.opened, [{ kind: 'connection', id: connection.id }]); assert.equal(f.calls.length, 0);
  const detail = fixture('ModelConnectionDetail', { connection, data: directory, state: {} });
  detail.click('配置与测试'); assert.deepEqual(detail.opened, [{ kind: 'edit', id: connection.id }]);
  assert.ok(detail.text.includes('修订 7')); assert.ok(detail.text.includes('凭据')); assert.ok(detail.text.includes('可用性需另行测试'));
});

test('catalog entry belongs only to the selected original connection and selection keeps its native revision', async () => {
  const other = { ...connection, id: 'connection-b', revision: 11 };
  const f = fixture('ModelConnectionDetail', { connection: other, data: directory, state: {} });
  assert.equal(f.text.includes('这项连接的模型目录'), false);
  await f.action('models.connection.select'); assert.deepEqual(f.calls, [{ action: 'models.connection.select', args: { id: other.id, expectedRevision: 11 } }]);
  const current = fixture('ModelConnectionDetail', { connection, data: directory, state: {} });
  assert.ok(current.text.includes('这项连接的模型目录'));
  assert.equal(current.find(node => node.props.action === 'models.connection.select').props.disabled, false, 'layout does not add a new selection gate');
  const account = fixture('ModelConnectionDetail', { connection, data: directory, state: { modelRoute: 'account' } });
  assert.equal(account.find(node => node.props.action === 'models.connection.select').props.disabled, false);
});

const archiveData = () => ({ open: true, inventory: { extraSectionsAvailable: { memory: true, experiences: true, remoteConnections: true, sshConnections: true, settings: true, skills: true, schedules: true } } });
test('archive discovery has three primary choices and preserves list position on operation return', () => {
  const f = fixture('ArchiveWorkspace', { data: archiveData() });
  assert.equal(f.view, 'list'); assert.ok(f.visibleText.includes('创建备份')); assert.ok(f.visibleText.includes('导入备份')); assert.ok(f.visibleText.includes('当前固定备份'));
  assert.equal(f.visibleText.includes('生成所选范围的备份'), false); assert.equal(f.calls.length, 0);
  f.scroller.scrollTop = 180; f.entry('create'); assert.equal(f.view, 'create'); assert.equal(f.scroller.scrollTop, 0); assert.equal(f.focused.at(-1), 'heading');
  assert.ok(f.visibleText.includes('本机审阅缓存未加密')); assert.equal(f.all.some(node => node.props.action === 'archives.chooseImport'), false);
  assert.equal(f.back(), true); assert.equal(f.view, 'list'); assert.equal(f.scroller.scrollTop, 180); assert.equal(f.focused.at(-1), 'create'); assert.equal(f.back(), undefined);
});

test('create retains all available default sections, import remains preview-only, and new selections move to the fixed object', async () => {
  const f = fixture('ArchiveWorkspace', { data: archiveData() }); f.entry('create');
  await f.action('archives.create');
  assert.deepEqual(f.calls[0].args.sections, ['projects', 'artifacts', 'memory', 'remoteConnections', 'sshConnections', 'settings', 'skills', 'schedules']);
  f.back(); f.entry('import'); await f.action('archives.chooseImport');
  assert.deepEqual(f.calls[1], { action: 'archives.chooseImport', args: { encrypted: true } }); assert.equal(f.calls.some(call => call.action.includes('confirm')), false);
  f.change({ data: { ...archiveData(), selection: { selectionId: 'native-selection', archiveSha256: 'a'.repeat(64), archiveBytes: 1024 }, preview: { review: { settings: { counts: { apiConnections: 1 } } } } } });
  assert.equal(f.view, 'snapshot'); assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'restore');
  assert.ok(f.visibleText.includes('a'.repeat(64))); assert.ok(f.visibleText.includes('native-selection'));
  await f.action('archives.reviewPhase'); assert.deepEqual(f.calls.at(-1), { action: 'archives.reviewPhase', args: { selectionId: 'native-selection', sha256: 'a'.repeat(64), phase: 'settings' } });
});

test('recovery confirmation exposes its original object and cannot confirm before scope review', async () => {
  const review = { actionId: 'native-recovery', selectionId: 'native-selection', archiveSha256: 'a'.repeat(64), phase: 'settings', nativeScope: {} };
  const f = fixture('ArchiveWorkspace', { data: { ...archiveData(), review } });
  const confirm = () => f.find(node => node.props.action === 'archives.confirmPhase');
  assert.equal(confirm().props.disabled, true); assert.ok(f.visibleText.includes(review.actionId)); assert.ok(f.visibleText.includes(review.archiveSha256));
  assert.ok(f.visibleText.includes('停用状态')); assert.equal(f.calls.length, 0);
  f.find(node => node.type === 'CheckboxField' && node.props.onChange).props.onChange(true); f.render();
  assert.equal(confirm().props.disabled, false); await f.action('archives.confirmPhase'); assert.deepEqual(f.calls[0].args, { actionId: review.actionId });
  f.change({ data: { ...archiveData(), review: { ...review, actionId: 'new-review' } } }); assert.equal(confirm().props.disabled, true);
});

test('unknown upload remains visible outside disclosures with its exact destination and no automatic resend', () => {
  const f = fixture('ArchiveWorkspace', { data: { ...archiveData(), webDavAction: 'original-upload', webDavUpload: { status: 'unknown', scope: { destination: 'https://backup.invalid/original.pwe' } } } });
  assert.equal(f.view, 'list'); assert.ok(f.visibleText.includes('待核对')); assert.ok(f.visibleText.includes('https://backup.invalid/original.pwe')); assert.ok(f.visibleText.includes('不会自动重传')); assert.equal(f.calls.length, 0);
});

const storageData = () => ({ inventory: { filesystemUsableBytes: 4096, groups: [
  { id: 'cache-a', label: '依赖缓存', cleanable: true, usage: { complete: true, logicalBytes: 1024, files: 12 } },
  { id: 'partial', label: '未完整计量目录', cleanable: true, usage: { complete: false, logicalBytes: 99 } },
], retentionPolicy: { revision: 'policy-7' } }, selected: ['cache-a'] });
test('storage separates operations and only measured native ranges can be selected for review', async () => {
  const f = fixture('StorageWorkspace', { data: storageData() }); assert.equal(f.view, 'list'); assert.equal(f.visibleText.includes('依赖缓存'), false);
  f.entry('cleanup'); assert.ok(f.visibleText.includes('依赖缓存')); assert.ok(f.visibleText.includes('1.0 KiB')); assert.ok(f.visibleText.includes('不能回滚')); assert.ok(f.visibleText.includes('不能据此清理'));
  const partial = f.find(node => node.props['data-storage-range'] === 'partial');
  assert.equal(partial.children[0].children[0].props.isDisabled, true);
  await f.action('storage.reviewSelected'); assert.deepEqual(f.calls, [{ action: 'storage.reviewSelected', args: { selection: ['cache-a'] } }]);
});

test('diagnostic preview retains full source and SHA while export stays gated by original review', async () => {
  const data = { ...storageData(), diagnosticText: 'exact native diagnostic', diagnosticSha: 'b'.repeat(64), diagnosticBytes: 23, diagnosticReviewed: false };
  const f = fixture('StorageWorkspace', { data }); f.entry('diagnostics');
  assert.ok(f.visibleText.includes(data.diagnosticText)); assert.ok(f.visibleText.includes(data.diagnosticSha));
  assert.equal(f.find(node => node.props.action === 'storage.diagnostics.export').props.disabled, true);
  const checkbox = f.find(node => node.type === 'CheckboxField'); checkbox.props.onChange(true); f.render();
  assert.deepEqual(f.calls, [{ action: 'storage.diagnostics.review', args: { sha256: data.diagnosticSha, accepted: true } }]);
  assert.equal(f.find(node => node.props.action === 'storage.diagnostics.export').props.disabled, true, 'UI checkbox cannot fabricate a native reviewed snapshot');
  f.change({ data: { ...data, diagnosticReviewed: true } }); await f.action('storage.diagnostics.export'); assert.deepEqual(f.calls.at(-1)?.args, { sha256: data.diagnosticSha });
});

test('bridge accepts exactly the native nine archive sections without weakening owner, identity, duplicate or size guards', () => {
  const bridge = read('../../app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebData.kt');
  const native = read('../../app/src/main/java/dev/pocketengine/features/workspace/OwnerDataArchives.java');
  const archive = read('../../app/src/main/java/dev/pocketengine/features/workspace/OwnerBackupArchive.java');
  const sections = (value: string) => [...value.match(/SECTIONS\s*=\s*Set\.of\(([^)]+)\)/)![1].matchAll(/"([^"]+)"/g)].map(match => match[1]).sort();
  const expected = ['projects', 'artifacts', 'memory', 'experiences', 'remoteConnections', 'sshConnections', 'settings', 'skills', 'schedules'].sort();
  assert.deepEqual(sections(native), expected); assert.deepEqual(sections(archive), expected);
  assert.match(bridge, /private val SECTIONS = dev\.pocketengine\.features\.workspace\.OwnerDataArchives\.SECTIONS/);
  assert.match(bridge, /strings\(args, "sections", SECTIONS\.size\); require\(selected\.isNotEmpty\(\) && SECTIONS\.containsAll\(selected\)\)/);
  assert.match(bridge, /require\(result\.size == value\.length\(\)\)/); assert.match(bridge, /require\(value\.length\(\) <= max\)/);
  assert.match(bridge, /controller\.webPrivateOwnerVisible\(\)/); assert.match(bridge, /same\(string\(args, "sha256"\), selected\.optString\("archiveSha256"\)/);
  assert.match(archive, /file\.length\(\)>MAX_BYTES/); assert.match(bridge, /archives\.confirmPhase" -> \{ fields\(args, "actionId"\); review\(args, archives\.state\.review\)/);
});


test('backup failure remains unknown while completed storage ranges stay usable', async () => {
  const data = { ...storageData(), caches: null, backupError: '本机备份列表未能读取：目录不可用' };
  const f = fixture('StorageWorkspace', { data });
  assert.ok(f.visibleText.includes('读取失败，数量未知'));
  assert.ok(f.visibleText.includes(data.backupError));
  assert.equal(f.visibleText.includes('尚无可读取的本机缓存'), false);
  f.entry('caches');
  assert.equal(f.visibleText.includes('尚无可读取的本机备份缓存'), false);
  assert.equal(f.all.some(node => node.props.action === 'storage.backupCache'), false);
  f.back(); f.entry('cleanup');
  assert.ok(f.visibleText.includes('依赖缓存'));
  await f.action('storage.reviewSelected');
  assert.deepEqual(f.calls[0], { action: 'storage.reviewSelected', args: { selection: ['cache-a'] } });
});

test('unread and successfully empty backup lists have different messages', () => {
  const unread = fixture('StorageWorkspace', { data: { ...storageData(), caches: null } });
  assert.ok(unread.visibleText.includes('尚未读取本机备份缓存'));
  unread.entry('caches'); assert.ok(unread.visibleText.includes('请重新计量'));
  const empty = fixture('StorageWorkspace', { data: { ...storageData(), caches: [] } });
  assert.ok(empty.visibleText.includes('尚无可读取的本机缓存'));
  empty.entry('caches'); assert.ok(empty.visibleText.includes('尚无可读取的本机备份缓存'));
  assert.equal(empty.visibleText.includes('读取失败'), false);
});


test('unverified storage cache rows open using only their current locator', async () => {
  const id = '11111111-1111-1111-1111-111111111111';
  const calls: Data[] = [];
  const f = fixture('StorageWorkspace', { data: { ...storageData(), caches: [{ selectionId: id, archiveBytes: 4096, modifiedAt: 123, verification: 'verify_all_bytes_on_explicit_open' }] }, run: async (action: string, args: Data) => { calls.push({ action, args }); return { navigate: 'archives' }; } });
  f.entry('caches'); await f.action('storage.backupCache');
  assert.deepEqual(calls, [{ action: 'storage.backupCache', args: { selectionId: id } }]);
  assert.deepEqual(f.opened, [{ page: 'archives' }]);
});
