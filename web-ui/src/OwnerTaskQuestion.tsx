import {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react';
import {Button, TextAreaField} from './forms-library';
import {PressButton} from './library';
import {bridge} from './bridge';
import {isPrivateLocalPhone} from './PhonePauseInstruction';
import {phoneConversationId, phoneRunIdentity} from './phone-start';
import {privatePhoneSurface} from './private-phone-surface';
import './task-decisions.css';
import {ownerQuestionSavedMessage, submitOwnerQuestion, type OwnerQuestionAnswer, type OwnerQuestionCommand,
  type OwnerQuestionReceipt, type OwnerQuestionView} from './owner-task-question';

type MainPhoneAnswerSource = NonNullable<ReturnType<typeof phoneRunIdentity>> & {
  document: number; conversation: string; composerKey: string; composerEpoch: number; deadline: number;
};
type QuestionDraft = {key: string; text: string; phone?: MainPhoneAnswerSource; needsReview?: true};
const emptyDraft: QuestionDraft = {key: '', text: ''};

function mainPhoneQuestion(task?: Record<string, any>) {
  const raw=task?.raw;
  return !!task && isPrivateLocalPhone(task) && raw.executionMode==='main_screen' && task.status==='awaiting_user' &&
    raw.status===task.status && raw.contextAvailable!==false && raw.contextWithdrawn!==true &&
    raw.sourceCurrentReadRequired!==true && raw.taskProjection!=='source_unavailable' && raw.cancelRequested!==true;
}

function observeMainPhoneAnswer(source: MainPhoneAnswerSource, inDrawer=false): 'current'|'unconfirmed'|'changed' {
  const snapshot=bridge.getState(), {state,composer}=snapshot;
  const view=snapshot.view as {section?:string|null;taskId?:string|null}|undefined;
  if (source.document!==bridge.getDocumentEpoch() || source.deadline>0 && Date.now()>=source.deadline ||
      view?.section!=null || view?.taskId!=null && view.taskId!==source.taskId ||
      (state.conversationId||'')!==source.conversation) return 'changed';
  if (document.hidden || !view || inDrawer && view.taskId!==source.taskId || state.conversationReader ||
      composer?.ready!==true) return 'unconfirmed';
  if (composer.key!==source.composerKey || composer.epoch!==source.composerEpoch ||
      composer.conversationId!==source.conversation) return 'changed';
  const rows=Array.isArray(state.tasks)?state.tasks.filter(row=>row?.id===source.taskId):[];
  if (rows.length!==1 || !rows[0]?.raw) return 'unconfirmed';
  const row=rows[0], run=phoneRunIdentity(row), scope=row.raw.executionScope;
  if (!mainPhoneQuestion(row) || run?.runId!==source.runId || run.epoch!==source.epoch ||
      (phoneConversationId(row)||'')!==source.conversation) return 'changed';
  if (!scope) return 'unconfirmed';
  if (scope.state!=='active' || scope.deadlineAt!==source.deadline) return 'changed';
  const owned=privatePhoneSurface(row,state.livePrivatePhones);
  return !owned?'unconfirmed':owned.paused && !owned.stopRequested?'current':'changed';
}

/** Only the just-read native question may make a textbox eligible for keeping its local text on Back. */
export function mainPhoneAnswerSource(task: Record<string, any> | undefined, question: OwnerQuestionView | null) {
  const run=phoneRunIdentity(task), deadline=task?.raw?.executionScope?.deadlineAt, {state,composer}=bridge.getState();
  if (!run || !mainPhoneQuestion(task) || question?.kind!=='phone' || question.answered!==false ||
      typeof question.questionKey!=='string' || !/^[a-f0-9]{64}$/.test(question.questionKey) ||
      !Number.isSafeInteger(deadline) || deadline<0 || typeof composer?.key!=='string' ||
      !composer.key || !Number.isSafeInteger(composer.epoch) || composer.epoch<0) return;
  const conversation=phoneConversationId(task)||'';
  if (conversation!==(state.conversationId||'')) return;
  const source={...run,document:bridge.getDocumentEpoch(),conversation,composerKey:composer.key,
    composerEpoch:composer.epoch,deadline};
  return observeMainPhoneAnswer(source,true)==='current'?source:undefined;
}

/** Keep this hook in the task surface, outside the refreshable review panel. Missing reviews
 * do not erase a failed answer. Only the same main-screen question can survive closing. */
export function useOwnerQuestionDraft(question: OwnerQuestionView | null, phone: MainPhoneAnswerSource | undefined,
                                      sameOwnerPage: boolean, freshReview: boolean, refreshing: boolean) {
  const questionKey=question?.questionKey;
  const lastKey = useRef('');
  const verified = useRef<{key:string;source:MainPhoneAnswerSource}|null>(null);
  if (questionKey) lastKey.current = questionKey;
  const key = questionKey || lastKey.current;
  const [draft, setDraft] = useState<QuestionDraft>(emptyDraft);
  // A refresh leaves the old question painted and editable. Its exact pin can
  // bind new local typing, but it cannot resurrect an input after closing.
  useLayoutEffect(()=>{
    if (freshReview || !sameOwnerPage) verified.current=phone && questionKey?{key:questionKey,source:phone}:null;
    if (draft.needsReview && draft.phone && draft.key===questionKey && phone && freshReview &&
        observeMainPhoneAnswer(draft.phone,true)==='current') {
      // Once the exact fresh review has restored this text to the current panel,
      // another same-window refresh should not momentarily blank it again.
      setDraft(old=>old===draft?{...old,needsReview:undefined}:old);
    }
  }, [draft,phone,questionKey,freshReview,sameOwnerPage]);
  // Closing retires the review, not the current wait. Missing publications may
  // preserve the hidden slot, but only a fresh match can show it again.
  const close = useCallback(() => setDraft(old => old.phone && old.text && sameOwnerPage &&
    observeMainPhoneAnswer(old.phone)!=='changed' ? {...old,needsReview:true} : emptyDraft), [sameOwnerPage]);
  const saved = useCallback((receipt: OwnerQuestionReceipt) => setDraft(old => old.phone && old.key===receipt.questionKey &&
    old.phone.taskId===receipt.taskId && old.phone.runId===receipt.runId && old.phone.epoch===receipt.coordinatorEpoch ? emptyDraft : old), []);
  useEffect(() => {
    if (!draft.phone) return;
    const source=draft.phone;
    const retire=()=>setDraft(old=>old===draft?emptyDraft:old);
    const check=()=>{if (!sameOwnerPage || observeMainPhoneAnswer(source)==='changed' ||
      freshReview && (questionKey!==draft.key || question?.answered===true)) retire();};
    check();
    const unsubscribe=bridge.subscribe(check), epoch=bridge.subscribeDocumentEpoch(check);
    document.addEventListener('visibilitychange',check);
    const until=source.deadline-Date.now();
    const timer=source.deadline && until>0 && until<2_147_483_647 ? window.setTimeout(retire,until) : undefined;
    return ()=>{unsubscribe();epoch();document.removeEventListener('visibilitychange',check);window.clearTimeout(timer);};
  }, [draft,sameOwnerPage,freshReview,questionKey,question?.answered]);
  const text=draft.key===key && (!draft.phone || sameOwnerPage && observeMainPhoneAnswer(draft.phone,true)==='current' &&
    (!draft.needsReview || phone && freshReview)) ? draft.text : '';
  return {text,edit:(text:string)=>{
    const observed=verified.current;
    const capture=phone || (sameOwnerPage && refreshing && questionKey && observed?.key===questionKey?observed.source:undefined);
    const source=capture || (draft.key===key?draft.phone:undefined);
    if (source && (draft.needsReview&&!capture || observeMainPhoneAnswer(source,true)!=='current')) return;
    setDraft(text?{key,text,phone:source}:emptyDraft);
  },close,saved};
}

/** Uses the existing BoardUI controls and reviewed task surface; no parallel dialog/design. */
export function OwnerTaskQuestion({question, text, onTextChange, disabled, canAnswer, canChoose, submit, onSaved}: {
  question: OwnerQuestionView;
  text: string;
  onTextChange: (text: string) => void;
  disabled: boolean;
  canAnswer: boolean;
  canChoose: boolean;
  submit: OwnerQuestionCommand;
  onSaved?: (receipt: OwnerQuestionReceipt) => void;
}) {
  const [pending, setPending] = useState(false);
  const [receipt, setReceipt] = useState<OwnerQuestionReceipt | null>(null);
  const [error, setError] = useState('');
  const sent = useRef(false);
  const send = async (answer: OwnerQuestionAnswer) => {
    if (disabled || sent.current || question.answered) return;
    sent.current = true; setPending(true); setError('');
    try { const result=await submitOwnerQuestion(question, answer, submit); setReceipt(result); onSaved?.(result); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '回答保存回执未确认，请重新核对原任务。'); }
    finally { setPending(false); }
    // Never automatically replay after an unknown save. A fresh native review is required.
  };
  const saved = question.answered || receipt !== null;
  const locked = disabled || pending || sent.current || saved;
  return <section className="pw-approval-item pw-task-decision pw-task-question" aria-label="回答原问题" aria-busy={pending}>
    <header className="pw-decision-heading">
      <span className="pw-decision-eyebrow">{saved ? '已回答' : '需要你的回答'}</span>
      <h4 className="pw-question-title">{question.question}</h4>
    </header>
    {saved ? <p className="pw-decision-notice" role="status">{ownerQuestionSavedMessage(question, receipt)}</p> : <>
      <p className="pw-decision-caveat">回答只进入这个原等待项，不代表批准敏感操作。</p>
      {canChoose && question.options?.length ? <div className="pw-question-choices" aria-label="原问题选项">
        <p className="pw-decision-meta">选择一项即发送回答</p>
        {question.options.map((option, index) => <Button key={option.id} variant="secondary" className="pw-question-choice" disabled={locked}
          onClick={() => void send({optionId: option.id})}><span className="pw-question-choice-number" aria-hidden="true">{index + 1}</span><span className="pw-question-choice-label">{option.label}</span></Button>)}
      </div> : null}
      <div className="pw-question-compose">
        <TextAreaField label={question.options?.length ? '也可以填写回答' : '你的回答'} value={text}
          onChange={value => onTextChange(value.slice(0, 4000))} maxLength={4000} rows={2} autoResize maxRows={8}
          isDisabled={pending} placeholder="输入回答"/>
        {canAnswer && <div className="pw-decision-actions"><PressButton label={pending ? '正在保存回答' : '发给原任务'}
          disabled={locked || !text.trim() || text.length > 4000} onClick={() => void send({text})}/></div>}
      </div>
    </>}
    {pending && <p className="pw-decision-caveat" role="status">正在核对原回答保存回执…</p>}
    {error && <div className="pw-decision-notice" role="alert"><h5>回答尚未确认保存</h5><p>{error}</p></div>}
  </section>;
}
