/** Test-only mount of production TaskHistory + bridge. The explicit fake host
 * supplies compact pages and separate source details; it cannot execute tasks. */
import { createRoot } from 'react-dom/client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { bridge } from '../src/bridge';
import { TaskHistory } from '../src/TaskHistory';
import { Drawer } from '../src/shell-library';
import { IconButton } from '../src/forms-library';
import { RiArrowLeftLine } from '@remixicon/react';
import { taskHistoryIdentity, type TaskHeader } from '../src/task-history';
import type { TraceNode } from '../src/execution-trace';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

const START = 1_793_838_600_000, PAGE_SIZE = 40;
const counter = (value: number | null, stage = 'FINAL') => ({ value, source: value === null ? 'unknown' : 'provider_reported', sourcePath: 'usage.fixture', stage });
const usage = { counters: { INPUT: counter(1000), OUTPUT: counter(120), TOTAL: counter(1120), CACHE_READ: counter(700), CACHE_WRITE: counter(0), REASONING: counter(80) },
  inputIncludesCacheRead: true, inputIncludesCacheWrite: true, outputIncludesReasoning: true };
const partialUsage = { ...usage, counters: { INPUT: counter(1000, 'PARTIAL'), OUTPUT: counter(24, 'PARTIAL'), CACHE_READ: counter(700, 'PARTIAL'), REASONING: counter(null), CACHE_WRITE: counter(0) } };
const base = (patch: Partial<TraceNode>): TraceNode => ({ id: 'event:fixture', kind: 'event', role: 'event', parentId: null, name: '原执行记录', status: 'OBSERVED',
  startedAt: START, finishedAt: START, elapsedNanos: null, durationMs: null, timingKnown: false, legacy: false, sequence: 0, source: 'task_event', ...patch });
let complete = false, variant: 'default' | 'empty' | 'limited' = 'default', failNext = false, failDetail = false, revision = 0;
let holdReads = false, holdDetails = false, holdOlder = false, missingDetails = false, mismatchDetail = false;
let sourceRevision = 0;
const calls: { action: string; args: Record<string, unknown> }[] = [], held: { action: string; response: Record<string, unknown> }[] = [];
const runs = [{ runId: 'run-now', epoch: 3, status: 'RUNNING', startedAt: START, updatedAt: START + 220_000 },
  { runId: 'run-old', epoch: 2, status: 'FAILED', startedAt: START - 86_400_000, updatedAt: START - 86_390_000 }];
function sourceNodes(old = false): TraceNode[] {
  if (variant === 'empty' && !old) return [];
  if (old) return [base({ id: 'model:historic', kind: 'model', role: 'request', name: '早先执行的模型请求', status: 'FAILED', hasError: true,
    startedAt: START - 86_400_000, finishedAt: START - 86_398_000, timingKnown: true, elapsedNanos: '2000000000', durationMs: 2000, sequence: 1,
    error: '原服务返回 503', options: { model: 'fixture-model', max_output_tokens: 2048 }, source: 'native_inference_receipt', turnId: 'historic-turn-1', turnIndex: 1, usage })];
  const nodes: TraceNode[] = [];
  let sequence = 0;
  for (let turn = 1; turn <= 25; turn++) {
    const start = START + (turn - 1) * 8_000, id = turn === 1 ? 'model:first' : turn === 25 ? 'model:live' : `model:turn-${turn}`;
    const turnFields = { turnId: `turn-${turn}`, turnIndex: turn, stepId: `step-${turn}-1`, stepIndex: 1 };
    if (turn === 1) nodes.push(base({ id: 'event:user', role: 'input', name: '请核对资料来源并整理完整报告', sequence: ++sequence, ...turnFields,
      message: '请核对 **25 轮资料**，保留原始来源，并整理一份可追溯的报告。', preview: '请核对 **25 轮资料**，保留原始来源，并整理一份可追溯的报告。', raw: { role: 'user', content: '请核对25轮资料' } }));
    nodes.push(base({ id: `event:context-${turn}`, role: 'context', name: `第 ${turn} 轮上下文与可用工具`, sequence: ++sequence, ...turnFields,
      startedAt: start, finishedAt: start, message: '已整理上一轮结果和可用工具定义。', raw: { type: 'context', turn, tokenBudget: 32000 } }));
    const running = turn === 25 && !complete;
    nodes.push(base({ id, kind: 'model', role: 'request', name: turn === 1 ? '整理资料并选择工具' : `第 ${turn} 轮资料分析请求`, model: 'fixture-model', source: 'native_inference_receipt',
      status: running ? 'STARTED' : 'COMPLETED', startedAt: start + 100, finishedAt: running ? null : start + 2500,
      elapsedNanos: running ? null : '2400000000', durationMs: running ? null : 2400, timingKnown: !running, sequence: ++sequence, ...turnFields,
      callId: turn === 1 ? 'first' : `turn-${turn}`, memberId: '资料查询', logicalRequestId: `logical-${turn}`, attemptNo: turn === 1 ? 2 : 1,
      usage: running ? partialUsage : turn === 24 ? undefined : usage,
      input: { meaning: 'retained_original_checkpoint_context_not_final_wire', messages: [{ role: 'user', content: '查阅原资料' }] }, contextMeaning: 'original_checkpoint_context_not_final_wire',
      options: { model: 'fixture-model', temperature: 0.2, max_output_tokens: 2048, stream: true }, optionsSource: 'final_wire_allowlist',
      output: running ? null : { content: '已完成本轮资料分析。', toolCalls: ['search_web', 'read_file'] },
      firstVisibleTextAt: start + 450, firstVisibleTextElapsedNanos: '350123456', timingEvidence: 'provider_text_delta',
      generationDurationMs: running ? null : 1800, generationTimingMeaning: running ? null : 'client_first_to_last_visible_text_delta' }));
    // Ordinary run-bound audit events occur between context and assistant and
    // have no turn metadata. They must not split a genuine turn or become Input.
    if (turn % 5 === 0) nodes.push(base({ id: `event:audit-${turn}`, name: '上下文预算与执行核验', sequence: ++sequence, startedAt: start + 220,
      finishedAt: start + 220, message: '预算核验通过；此审计事件没有原始轮次关联。', raw: { type: 'context_budget', status: 'passed' } }));
    nodes.push(base({ id: `event:assistant-${turn}`, role: 'assistant', name: running ? '正在汇总查阅结果' : `已找到第 ${turn} 轮资料线索`, sequence: ++sequence, ...turnFields,
      requestId: id, parentId: id, startedAt: start + 450, finishedAt: running ? null : start + 2300, status: running ? 'WORKING' : 'COMPLETED',
      message: running ? '正在整理最后一轮结果…' : ['已完成第 **' + turn + '** 轮分析。', '', '- 保留原始来源', '- 继续核对工具返回', '', '`<script>this is text</script>`'].join('\n'),
      preview: `## 第 ${turn} 轮\n\n来源已保留，正在核对。`, raw: { type: 'assistant', turn, content: `第 ${turn} 轮回复`, requestId: id } }));
    const searchId = turn === 1 ? 'tool:search' : `tool:lookup-${turn}`;
    for (let tool = 0; tool < (running ? 0 : 3); tool++) {
      const failed = turn === 1 && tool === 1, subtool = tool === 2;
      nodes.push(base({ id: failed ? 'tool:error' : subtool ? `tool:sub-${turn}` : tool === 0 ? searchId : `tool:verify-${turn}`,
        kind: 'tool', role: subtool ? 'subtool' : 'tool', parentId: subtool ? searchId : id, parentVisible: true,
        name: failed ? 'read_file' : subtool ? '解析并交叉核对文件内容' : tool === 0 ? 'search_web' : 'verify_sources',
        status: failed ? 'UNKNOWN' : 'COMPLETED', hasError: failed, sequence: ++sequence, ...turnFields, source: 'native_tool_trace',
        startedAt: start + 2600 + tool * 100, finishedAt: start + 2600 + tool * 100 + (failed ? 125 : 800),
        elapsedNanos: failed ? '125000000' : '800000000', durationMs: failed ? 125 : 800, timingKnown: true,
        callId: `call-tool-${turn}-${tool}`, operationId: `op-${turn}-${tool}`, providerToolCallId: `provider-tool-${turn}-${tool}`,
        input: { query: '原始资料来源', round: turn, file: '/workspace/reference/source.md' },
        output: failed ? null : { results: ['原始来源 A', '原始来源 B'], verified: true },
        schema: subtool ? undefined : { type: 'object', properties: { query: { type: 'string', description: '原资料查询' }, round: { type: 'integer' } }, required: ['query'] },
        error: failed ? '原工具抛错：connection reset；执行效果待核对' : undefined,
        timingMeaning: 'native_tool_handling_interval_including_checks_and_receipt_persistence', effectPending: failed }));
    }
  }
  nodes.push(base({ id: 'tool:legacy', kind: 'tool', role: 'tool', name: '早先工具记录', legacy: true, sequence: ++sequence, status: 'COMPLETED', startedAt: null, finishedAt: null }));
  nodes.push(base({ id: 'tool:confirm', kind: 'tool', role: 'tool', name: 'review_action', status: 'NEEDS_CONFIRMATION', sequence: ++sequence,
    parentId: 'model:first', turnId: 'turn-1', turnIndex: 1, startedAt: START + 3400, finishedAt: START + 3400, elapsedNanos: '0', durationMs: 0, timingKnown: true, effectPending: true }));
  return nodes;
}
function compactNode(node: TraceNode) {
  const { message, preview, raw, input, output, options, schema, error, sourceRefs, ...metadata } = node;
  return { ...metadata, hasError: node.hasError || typeof error === 'string' && !!error.trim() };
}
function result(args: Record<string, unknown>) {
  const runId = String(args.runId || 'run-now'), old = runId === 'run-old', epoch = old ? 2 : 3;
  const offset = args.cursor ? Number(String(args.cursor).split(':').at(-1)) : 0;
  const ordered = sourceNodes(old).reverse(), nodes = ordered.slice(offset, offset + PAGE_SIZE), hasMore = offset + PAGE_SIZE < ordered.length;
  return { format: 'pocketworld.execution-trace.v1', taskId: args.taskId, runId, epoch, capturedAt: START + 250_000,
    runs, nodes: nodes.map(node => ({ ...compactNode(node), runId, epoch })), cursor: String(args.cursor || `${runId}:${offset}`),
    nextCursor: hasMore ? `${runId}:${offset + PAGE_SIZE}` : '', hasMore, limited: variant === 'limited', readOnly: true, executionAuthorized: false };
}
function detailResult(args: Record<string, unknown>) {
  const runId = String(args.runId), old = runId === 'run-old', source = sourceNodes(old).find(node => node.id === args.nodeId);
  if (!source) throw new Error('Detail fixture node is absent');
  return { format: 'pocketworld.execution-trace-detail.v1', taskId: args.taskId, runId, epoch: args.epoch, capturedAt: START + 250_000,
    node: { ...(missingDetails ? compactNode(source) : source), id: mismatchDetail ? 'other:unrelated' : source.id, runId, epoch: args.epoch },
    sourceState: missingDetails ? 'unavailable' : source.legacy ? 'metadata_only' : 'current',
    sourceReason: missingDetails ? '原正文已清理；保留调用标识、状态和已测用量。' : source.legacy ? '早先记录只保存了调用元数据。' : '', readOnly: true, executionAuthorized: false };
}
const respond = (message: Record<string, unknown>) => window.PocketWeb.receive(message as never);
window.PocketNative = { postMessage(raw) {
  const request = JSON.parse(raw); calls.push({ action: request.action, args: structuredClone(request.args) });
  let value: unknown;
  if (request.action === 'app.ready') value = { accepted: true };
  else if (request.action === 'trace.close') value = { closed: true, taskCancelled: false };
  else if (request.action === 'trace.read') value = result(request.args);
  else if (request.action === 'trace.detail') value = detailResult(request.args);
  else throw new Error(`Fixture rejects non-read command: ${request.action}`);
  const failed = request.action === 'trace.read' && failNext || request.action === 'trace.detail' && failDetail;
  if (request.action === 'trace.read' && failNext) failNext = false;
  if (request.action === 'trace.detail' && failDetail) failDetail = false;
  const response = { v: 1, kind: 'result', id: request.id, ok: !failed, value,
    error: failed ? { code: 'fixture_read_error', message: '这次轨迹读取未完成，请刷新后重读。' } : undefined };
  if (request.action === 'trace.read' && (holdReads || holdOlder && !!request.args.cursor) || request.action === 'trace.detail' && holdDetails) held.push({ action: request.action, response });
  else queueMicrotask(() => respond(response));
} };
window.PocketWeb.hostConfiguration?.({ documentToken: 'trace-fixture-document', bridgeAvailable: true });
window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: { state: {}, composer: null, capabilities: { commands: ['trace.read', 'trace.detail', 'trace.close'] } } });
const fixture = {
  snapshot: () => structuredClone({ calls, held: held.length, complete, variant, nodeCount: sourceNodes().length }),
  hold: (value = true) => { holdReads = value; },
  holdOlder: (value = true) => { holdOlder = value; },
  holdDetails: (value = true) => { holdDetails = value; },
  release: (action?: string) => {
    if (!action || action === 'trace.read') { holdReads = false; holdOlder = false; }
    if (!action || action === 'trace.detail') holdDetails = false;
    const release = held.filter(item => !action || item.action === action), keep = held.filter(item => action && item.action !== action);
    held.splice(0, held.length, ...keep); release.forEach(item => respond(item.response));
  },
  failNext: () => { failNext = true; }, failDetail: () => { failDetail = true; },
  missingDetails: (value = true) => { missingDetails = value; }, mismatchDetail: (value = true) => { mismatchDetail = value; },
  complete: () => { complete = true; }, empty: () => { variant = 'empty'; }, limited: () => { variant = 'limited'; },
  setActive: (_value: boolean) => {}, setTask: (_value: string) => {}, setStatus: (_value: string) => {}, back: () => false,
  rotateDocument: () => { window.PocketWeb.hostConfiguration?.({ documentToken: `trace-fixture-document-${++revision}` }); },
  invalidateSource: () => {
    sourceRevision++;
    window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: { state: { traceSourceRevision: sourceRevision }, composer: null,
      capabilities: { commands: ['trace.read', 'trace.detail', 'trace.close'] } } });
  },
};
declare global { interface Window { executionTraceFixture: typeof fixture; } }
window.executionTraceFixture = fixture;

function Fixture() {
  const [active, setActive] = useState(true), [taskId, setTaskId] = useState('task-fixture'), [status, setStatus] = useState('RUNNING');
  const transient = useRef<(() => boolean) | null>(null);
  const register = useCallback((handler: (() => boolean) | null) => { transient.current = handler; }, []);
  useEffect(() => { fixture.setActive = setActive; fixture.setTask = setTaskId; fixture.setStatus = setStatus; fixture.back = () => transient.current?.() ?? false; }, []);
  const task: TaskHeader = { id: taskId, goal: '执行调用轨迹 · 假宿主样本', status, raw: { runId: 'run-now', coordinatorEpoch: 3 } };
  return <main className="pw-app"><Drawer.Root open shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/>
    <Drawer.Content className="pw-drawer pw-task-drawer" aria-describedby="trace-fixture-description">
      <header className="pw-drawer-header pw-task-header"><IconButton icon={RiArrowLeftLine} aria-label="返回测试上一层" onClick={() => fixture.back()}/><div><Drawer.Title>执行轨迹</Drawer.Title><Drawer.Description id="trace-fixture-description">界面测试 · 假宿主，不连接模型或执行任务</Drawer.Description></div></header>
      <div className="pw-drawer-body pw-task-body"><div className="pw-task-process-layer" hidden={!active}>
        <TaskHistory key={taskHistoryIdentity(task)} task={task} canRead={false} canReadTrace active={active} registerTransientBack={register} renderOperation={() => null}/>
      </div>{!active && <p>轨迹页已隐藏</p>}</div>
    </Drawer.Content></Drawer.Portal></Drawer.Root></main>;
}
void bridge.bindHostDocument().then(() => { createRoot(document.getElementById('root')!).render(<Fixture/>); });
