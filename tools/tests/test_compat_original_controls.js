// Executes shipped compatibility handlers. No copied controller or Android acceptance claim.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/index.html'), 'utf8');
const between = (start, end) => html.slice(html.indexOf(start), html.indexOf(end, html.indexOf(start)));
function element() {
  return { children: [], hidden: true, value: '', style: {}, attributes: {}, append(...items) { this.children.push(...items); },
    replaceChildren() { this.children = []; }, setAttribute(name, value) { this.attributes[name] = value; },
    getAttribute(name) { return this.attributes[name]; }, addEventListener() {}, querySelector() { return null; }, focus() {} };
}
function fixture() {
  const elements = new Map(), requests = [], notices = [], buttons = [];
  const original = { id: 'task-A', runId: 'run-A', coordinatorEpoch: 7, kind: 'phone', status: 'running', goal: '原手机目标' };
  const state = { config: { baseUrl: 'https://example.test/v1', model: 'model-A' }, tasks: [original], activeTaskId: original.id,
    modelSelectionRevision: 12, modelSelectionPending: false, modelSelectionAvailable: true };
  const context = vm.createContext({ console, Map, Set, Number, JSON, window: {}, document: { activeElement: {} },
    state, tab: 'chat', selectedTaskId: original.id, settingsRevision: 12, settingsDirty: true, clearApiKey: false, pendingSave: null,
    pendingStarting: false, testing: false, feedbackExpanded: new Set(), feedbackDrafts: new Map(), pendingEvidence: new Set(),
    $: id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); },
    activeTask: () => state.tasks.find(task => task.id === state.activeTaskId), tasks: () => state.tasks,
    phoneTask: task => task.kind === 'phone', node: element, icon: element, statusNode: element,
    latestMessage: task => task.goal, imageUrl: () => '', showToast: message => notices.push(message),
    send: (action, payload) => { requests.push({ action, payload }); return true; },
    button(label, variant, click) { const button = element(); button.click = click; buttons.push({ label, click }); return button; },
    configFromForm: () => ({ baseUrl: 'https://example.test/v1', model: 'edited-model', apiKey: 'synthetic-unsaved-draft' }),
    validateConfig: () => '', settingsResult: message => notices.push(message), setBusy() {},
    openSheet() {}, closeSheet() {}, showError: message => notices.push(message), updateKeyHint() {}, updateHttpHint() {}, render() {}, acknowledgeStarted() {} });
  vm.runInContext(between('function runAction(', 'function openTask(') + between('function taskControl(', 'function renderTaskDetail('), context);
  return { context, state, original, elements, requests, notices, buttons };
}

test('a rendered phone control retains the exact run after the same task is replaced', () => {
  for (const status of ['running', 'paused']) {
    const f = fixture(); f.original.status = status;
    vm.runInContext(between('function renderChat(){', 'function renderTasks(){'), f.context);
    f.context.renderChat();
    f.state.tasks = [{ ...f.original, runId: 'replacement-run', coordinatorEpoch: 8 }];
    for (const label of [status === 'paused' ? '继续' : '暂停', '停止']) {
      f.buttons.find(button => button.label === label).click();
      assert.deepEqual(JSON.parse(JSON.stringify(f.requests.at(-1).payload)), { taskId: 'task-A', runId: 'run-A', epoch: 7 });
    }
  }
});

test('detail controls never resolve a replacement from the current global active task', () => {
  const f = fixture();
  vm.runInContext(between('function renderTaskDetail(){', 'function prepareFollowup('), f.context);
  f.context.renderTaskDetail(); f.state.tasks = [{ ...f.original, runId: 'replacement-run', coordinatorEpoch: 8 }];
  f.buttons.find(button => button.label === '暂停并接管').click();
  assert.deepEqual(JSON.parse(JSON.stringify(f.requests.at(-1).payload)), { taskId: 'task-A', runId: 'run-A', epoch: 7 });
  f.buttons.find(button => button.label === '停止后续操作').click();
  assert.equal(f.requests.at(-1).payload.runId, 'run-A');
});

test('a missing run identity cannot fall back to whichever task happens to be active', () => {
  const f = fixture();
  f.context.runAction('stopRun', { id: 'task-A', coordinatorEpoch: 7 });
  assert.equal(f.requests.length, 0); assert.equal(f.notices.length, 1);
});

function settingsFixture() {
  const f = fixture();
  vm.runInContext(between('function updateSettingsAvailability(', 'function sameModelOrigin(') + between('function settingsSelectionReady(', 'function testConnection('), f.context);
  return f;
}
test('a settings write carries the revision belonging to its displayed draft', () => {
  const f = settingsFixture(); f.context.saveConfig();
  assert.equal(f.requests.length, 1); assert.equal(f.requests[0].payload.modelSelectionRevision, 12);
  assert.equal(f.requests[0].payload.config.model, 'edited-model');
});
test('pending or changed selections retain the draft and cannot submit over another surface', () => {
  for (const change of [{ modelSelectionPending: true }, { modelSelectionRevision: 13 }, { modelSelectionAvailable: false }]) {
    const f = settingsFixture(); Object.assign(f.state, change);
    f.context.saveConfig();
    assert.equal(f.requests.length, 0); assert.equal(f.context.pendingSave, null);
    assert.equal(f.context.configFromForm().apiKey, 'synthetic-unsaved-draft');
  }
});

function receivingFixture() {
  const f = settingsFixture();
  vm.runInContext(between('function loadSettingsDraft(', 'function sameModelOrigin(') + between('function receive(raw)', 'window.Pocket='), f.context);
  return f;
}
const projection = (revision, model) => ({ type: 'state', payload: { config: { baseUrl: 'https://example.test/v1', model },
  modelSelectionRevision: revision, modelSelectionPending: false, modelSelectionAvailable: true } });

test('cross-Activity invalidation keeps the secret draft until the explicit replace action', () => {
  const f = receivingFixture(); f.context.$('apiKey').value = 'synthetic-unsaved-draft';
  f.context.receive({ type: 'model_selection', revision: 13, pending: true });
  assert.equal(f.context.$('saveButton').disabled, true);
  f.context.receive(projection(15, 'model-B'));
  assert.equal(f.context.$('apiKey').value, 'synthetic-unsaved-draft');
  assert.equal(f.context.settingsRevision, 12); assert.equal(f.context.settingsDirty, true);
  assert.equal(f.context.$('saveButton').disabled, true);
  assert.equal(f.context.$('reloadConfig').textContent, '用最新配置替换草稿');
  f.context.reloadSettings();
  assert.equal(f.context.$('apiKey').value, ''); assert.equal(f.context.$('model').value, 'model-B');
  assert.equal(f.context.settingsRevision, 15); assert.equal(f.context.settingsDirty, false);
  assert.equal(f.context.$('saveButton').disabled, false);
});

test('late projections cannot replace a newer selection or reopen an in-flight write', () => {
  const f = receivingFixture();
  f.context.receive(projection(15, 'model-B'));
  f.context.receive(projection(12, 'model-A'));
  assert.equal(f.state.config.model, 'model-B'); assert.equal(f.state.modelSelectionRevision, 15);
  f.context.receive({ type: 'model_selection', revision: 16, pending: true });
  f.context.receive(projection(15, 'model-B'));
  assert.equal(f.state.modelSelectionPending, true); assert.equal(f.state.modelSelectionAvailable, false);
  f.context.saveConfig(); assert.equal(f.requests.length, 0);
});

test('an unrelated native error cannot release a queued save or erase subsequent edits', () => {
  const f = receivingFixture(); f.context.saveConfig();
  f.context.receive({ type: 'error', message: '另一操作尚待核对' });
  assert.notEqual(f.context.pendingSave, null);
  f.context.saveConfig(); assert.equal(f.requests.length, 1);
  f.context.configFromForm = () => ({ model: 'later-edit', apiKey: 'synthetic-later-draft' });
  f.context.receive({ type: 'config_saved', ok: true });
  assert.equal(f.context.pendingSave, null); assert.equal(f.context.settingsDirty, true);
  assert.equal(f.context.configFromForm().model, 'later-edit');
  assert.match(f.notices.at(-1), /后续修改的草稿已保留/);
});
