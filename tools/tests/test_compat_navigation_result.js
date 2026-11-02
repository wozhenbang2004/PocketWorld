// Executes the shipped compatibility handlers with a minimal DOM boundary, not a second router.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/index.html'), 'utf8');
const between = (start, end) => html.slice(html.indexOf(start), html.indexOf(end, html.indexOf(start)));
function element() { return { children: [], hidden: true, scrollTop: 0, style: {}, value: '', append(...nodes) { this.children.push(...nodes); }, replaceChildren() { this.children = []; }, querySelector() { return null; }, focus() {}, setAttribute() {}, addEventListener() {} }; }
const elements = new Map(), requests = [], buttons = [], textareas = [];
const context = vm.createContext({ console, Map, Set, JSON, document: { activeElement: {} }, window: {}, centerOpen: false, centerOrigin: null, activeFeature: '', renderCenter() {}, native: (...args) => requests.push(args), $: id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); } });
vm.runInContext(html.match(/function refresh\(\)\{[^\n]+/)[0], context);
vm.runInContext(between('function enterFeature(feature){', 'function closeCenter(){') + between('function handleEvent(raw){', 'const previousReceive='), context);
for (const target of ['channels', 'plugins', 'proxy']) {
  context.handleEvent(JSON.stringify({ type: 'open_features', feature: target }));
  assert.equal(context.activeFeature, target);
  assert.equal(context.centerOpen, true);
  assert.equal(elements.get('capShade').hidden, false);
}
assert.deepEqual(requests, [['getFeatures'], ['getFeatures'], ['getFeatures']]);
Object.assign(context, { state: { tasks: [] }, selectedTaskId: 'task', feedbackExpanded: new Set(), feedbackDrafts: new Map(), activeTask: () => null, phoneTask: () => true, statusNode: element, latestMessage: t => t.summary, imageUrl: () => '', node(tag) { const e = element(); if (tag === 'textarea') textareas.push(e); return e; }, button(label, style, click) { const e = element(); e.click = click; buttons.push({ label, click }); return e; }, send: (...args) => requests.push(args) });
vm.runInContext(between('function renderTaskDetail(){', 'function prepareFollowup('), context);
const reviewed = { id: 'task', status: 'needs_confirmation', summary: 'R1', resultReviewDigest: 'reviewed-R1' };
context.state.tasks = [reviewed]; context.renderTaskDetail();
context.state.tasks = [{ ...reviewed, summary: 'R2', resultReviewDigest: 'current-R2' }];
buttons.find(b => b.label === '已完成').click();
assert.equal(requests.at(-1)[1].resultReviewDigest, 'reviewed-R1');
buttons.find(b => b.label === '还没完成').click(); textareas.at(-1).value = '还需处理';
buttons.find(b => b.label === '保存反馈').click();
assert.equal(requests.at(-1)[1].resultReviewDigest, 'reviewed-R1'); assert.equal(requests.at(-1)[1].completed, false);
console.log('PASS: three original feature routes are read-only; both compatibility confirmations preserve displayed result digest');
