/** Real overlay components with a complete synthetic document handshake.
 * Transport receipts are isolated fixtures, never Android/native acceptance. */
import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { bridge } from '../src/bridge';
import { ConversationOrganization } from '../src/ConversationOrganization';
import { MessageQuote } from '../src/MessageQuote';
import { MessageOriginReader } from '../src/MessageOriginReader';
import { AnswerSources } from '../src/AnswerSources';
import { TaskApproval } from '../src/TaskApproval';
import { TaskInference } from '../src/TaskInference';
import { OwnerTaskQuestion } from '../src/OwnerTaskQuestion';
import { usePocketAppearance } from '../src/usePocketAppearance';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';
import '../src/uiux-surfaces.css';
import '../src/uiux-page-depth.css';

const scene = new URLSearchParams(location.search).get('scene') || 'conversation';
const events: any[] = [], fixtureErrors: string[] = [];
const close = () => events.push({ closed: true });
const answer = '请先核对周末出行时间和路线，涉及预订或付款时先交给我确认。';
const documentToken = 'overlay-depth-document';
const commands = ['conversation.organization.read', 'conversation.organization.save', 'conversation.organization.close',
  'messageQuote.open', 'messageQuote.stage', 'messageQuote.close', 'message.origin',
  'sources.open', 'sources.read', 'sources.close', 'usage.task', 'tasks.answerQuestion', 'tasks.answerQuestionChoice'];
let boundDocument = '', quoteStaged = false, questionAnswered = false, sourceGeneration = 0;
const sourceList = () => ({ windowId: 'source-window-1', status: 'current', nextCursor: -1,
  entries: [{ status: 'available', sourceHandle: `source-handle-${++sourceGeneration}`, title: '出行安排原始说明',
    label: '已保留的原文', pin: { kind: 'message', sourceId: 'original-1', sourceRevision: 2 } }] });
const requireFixture = (condition: unknown, message: string) => { if (!condition) throw new Error(message); };
const probe = (window as any).overlayDepth = { events, fixtureErrors, scene, ready: false };
window.PocketNative = { postMessage(raw) {
  const request = JSON.parse(raw); events.push(request);
  queueMicrotask(() => {
    try {
      requireFixture(request.v === 1 && typeof request.id === 'string' && request.hostDocumentToken === documentToken, 'fixture document envelope mismatch');
      let value: any;
      if (request.action === 'app.ready') {
        requireFixture(/^[a-f0-9]{32}$/.test(request.documentId) && request.args.documentId === request.documentId, 'fixture handshake identity mismatch');
        boundDocument = request.documentId; value = { accepted: true };
      } else {
        requireFixture(boundDocument && request.documentId === boundDocument && commands.includes(request.action), 'fixture command before bound document or outside advertised capabilities');
        const args = request.args;
        switch (request.action) {
          case 'conversation.organization.read':
            requireFixture(args.conversationId === 'conversation-1', 'fixture conversation mismatch');
            value = { selectionHandle: 'org-selection-1', conversationId: 'conversation-1', title: '周末出行安排', pinned: true }; break;
          case 'conversation.organization.save':
          case 'conversation.organization.close':
            requireFixture(args.selectionHandle === 'org-selection-1', 'fixture selection handle mismatch');
            value = { accepted: true }; break;
          case 'messageQuote.open':
            requireFixture(args.messageId === 'answer-1' && args.sourceRevision === 1 && args.key === 'draft-1' && args.epoch === 1, 'fixture quote target mismatch');
            value = { windowId: 'quote-window-1', text: answer, maxChars: 4000 }; break;
          case 'messageQuote.stage':
            requireFixture(args.windowId === 'quote-window-1' && !quoteStaged && args.offset >= 0 && args.chars > 0 && args.offset + args.chars <= answer.length, 'fixture quote selection mismatch');
            quoteStaged = true; value = { selectionValidated: true, draftStaged: true }; break;
          case 'messageQuote.close':
            requireFixture(args.windowId === 'quote-window-1', 'fixture quote window mismatch'); value = { accepted: true }; break;
          case 'message.origin':
            requireFixture(args.messageId === 'answer-1' && args.sourceMessageId === 'request-1' && args.offset === 0, 'fixture origin target mismatch');
            value = { available: true, text: answer, offset: 0, nextOffset: answer.length, totalChars: answer.length, hasMore: false }; break;
          case 'sources.open':
            requireFixture(args.messageId === 'answer-1', 'fixture source target mismatch'); value = sourceList(); break;
          case 'sources.read':
            requireFixture(args.windowId === 'source-window-1', 'fixture source window mismatch');
            if (!args.sourceHandle) { value = sourceList(); break; }
            requireFixture(args.sourceHandle === `source-handle-${sourceGeneration}` && args.offset === 0, 'fixture source handle mismatch');
            value = { windowId: 'source-window-1', status: 'current', entries: [], detail: {
              context: { title: '出行安排原始说明', url: 'https://example.invalid/original' }, body: answer,
              readRange: { offset: 0, chars: answer.length, totalChars: answer.length }, nextOffset: -1 } }; break;
          case 'sources.close':
            requireFixture(args.windowId === 'source-window-1', 'fixture source close mismatch'); value = { accepted: true }; break;
          case 'usage.task':
            requireFixture(args.taskId === 'task-1', 'fixture usage task mismatch');
            value = { taskId: 'task-1', readOnly: true, executionAuthorized: false, headline: '本任务的累计模型调用与用量',
              coverage: '仅展示本次保留的调用记录；不代表完整账单。', warnings: ['实际账单与套餐剩余量未观察。'],
              money: ['费用尚未取得可核对的估算。'], context: { title: '原上下文信息', lines: ['沿用原任务保留的请求检查记录。'] },
              groups: [], calls: [], cursor: 0, nextCursor: -1 }; break;
          case 'tasks.answerQuestion':
          case 'tasks.answerQuestionChoice':
            requireFixture(!questionAnswered && (request.action === 'tasks.answerQuestionChoice' ? ['fast', 'simple'].includes(args.optionId) : typeof args.text === 'string' && args.text.trim()), 'fixture question answer mismatch');
            questionAnswered = true; value = { accepted: true, answerSaved: true, questionKey: 'owner-question-1', messageId: 'question-message-1',
              taskId: 'task-1', runId: 'run-1', coordinatorEpoch: 1, requestId: 'question-request-1', waitRevision: 1 }; break;
          default: throw new Error('unimplemented fixture action');
        }
      }
      window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
    } catch (error) {
      const message = String(error); fixtureErrors.push(message);
      window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: false, error: { code: 'not_sent', message } });
    }
  });
} };
window.PocketWeb.hostConfiguration?.({ documentToken });
// State advertises capabilities, but bridge intentionally withholds them until
// its real app.ready result binds this document. Do not override that gate.
window.PocketWeb.receive({ v: 1, kind: 'state', revision: 1, snapshot: {
  state: {}, composer: null, capabilities: { commands }, host: { connected: true } } });
await bridge.bindHostDocument();
probe.ready = bridge.getState().host?.ready === true;
probe.documentEpoch = bridge.getDocumentEpoch();
probe.commands = bridge.getState().capabilities.commands;

function Fixture() {
  usePocketAppearance({ mode: 'light', dark: false });
  const [text, setText] = useState('');
  const approval = { approvalId: 'approval-1', toolName: 'submit_form', arguments: {
    url: 'https://example.invalid/form', recipient: '原申请中的接收方', text: '核对完成后再提交这段内容。' },
    webReview: { canApprove: true, kind: 'tool', title: '提交前核对', summary: '核对原申请对象与内容后，再决定是否允许。' } };
  return <div className="pw-app pw-redesign" style={{ minHeight: '100dvh', height: 'auto', background: 'var(--pw-background)' }}>
    <main style={{ width: '100%', maxWidth: 760, margin: '0 auto', padding: 20 }}>
      {scene === 'conversation' ? <ConversationOrganization conversationId="conversation-1" onClose={close} />
        : scene === 'quote' ? <MessageQuote target={{ messageId: 'answer-1', sourceRevision: 1, key: 'draft-1', epoch: 1 }} onClose={close} />
        : scene === 'origin' ? <MessageOriginReader target={{ messageId: 'answer-1', sourceMessageId: 'request-1' }} onClose={close} />
        : scene.startsWith('sources') ? <AnswerSources target={{ messageId: 'answer-1' }} revision={1} onClose={close} />
        : scene.startsWith('approval') ? <TaskApproval approval={approval} disabled={scene === 'approval-pending'} onDecision={data => events.push(data)} />
        : scene === 'usage' ? <TaskInference taskId="task-1" />
        : <OwnerTaskQuestion question={{ questionKey: 'owner-question-1', kind: 'phone', question: '这次行程优先考虑哪一种方案？', answered: false,
          options: [{ id: 'fast', label: '优先节省时间' }, { id: 'simple', label: '优先减少换乘' }] }} text={text} onTextChange={setText}
          disabled={false} canAnswer canChoose submit={bridge.command} />}
    </main>
  </div>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
