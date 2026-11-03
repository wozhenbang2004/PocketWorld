import {useRef, useState} from 'react';
import {Button, TextAreaField} from './forms-library';
import {PressButton} from './library';
import {ownerQuestionSavedMessage, submitOwnerQuestion, type OwnerQuestionAnswer, type OwnerQuestionCommand,
  type OwnerQuestionReceipt, type OwnerQuestionView} from './owner-task-question';

/** Keep this hook in the task surface, outside the refreshable review panel. Missing reviews
 * do not erase a failed answer; a different native question never inherits the draft. */
export function useOwnerQuestionDraft(questionKey?: string): [string, (text: string) => void] {
  const lastKey = useRef('');
  if (questionKey) lastKey.current = questionKey;
  const key = questionKey || lastKey.current;
  const [draft, setDraft] = useState({key: '', text: ''});
  return [draft.key === key ? draft.text : '', text => setDraft({key, text})];
}

/** Uses the existing BoardUI controls and reviewed task surface; no parallel dialog/design. */
export function OwnerTaskQuestion({question, text, onTextChange, disabled, canAnswer, canChoose, submit}: {
  question: OwnerQuestionView;
  text: string;
  onTextChange: (text: string) => void;
  disabled: boolean;
  canAnswer: boolean;
  canChoose: boolean;
  submit: OwnerQuestionCommand;
}) {
  const [pending, setPending] = useState(false);
  const [receipt, setReceipt] = useState<OwnerQuestionReceipt | null>(null);
  const [error, setError] = useState('');
  const sent = useRef(false);
  const send = async (answer: OwnerQuestionAnswer) => {
    if (disabled || sent.current || question.answered) return;
    sent.current = true; setPending(true); setError('');
    try { setReceipt(await submitOwnerQuestion(question, answer, submit)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '回答保存回执未确认，请重新核对原任务。'); }
    finally { setPending(false); }
    // Never automatically replay after an unknown save. A fresh native review is required.
  };
  const saved = question.answered || receipt !== null;
  const locked = disabled || pending || sent.current || saved;
  return <section className="pw-approval-item" aria-label="回答原问题" aria-busy={pending}>
    <h4>回答原问题</h4>
    <p>{question.question}</p>
    {saved ? <p className="pw-muted" role="status">{ownerQuestionSavedMessage(question, receipt)}</p> : <>
      <p className="pw-muted">回答只进入这个原等待项，不代表批准敏感操作。</p>
      {canChoose && question.options?.length ? <div className="pw-protected-actions" aria-label="原问题选项">
        {question.options.map(option => <Button key={option.id} variant="secondary" disabled={locked}
          onClick={() => void send({optionId: option.id})}>{option.label}</Button>)}
      </div> : null}
      <TextAreaField label={question.options?.length ? '也可以填写回答' : '你的回答'} value={text}
        onChange={value => onTextChange(value.slice(0, 4000))} maxLength={4000} rows={2}
        isDisabled={pending} placeholder="输入回答"/>
      {canAnswer && <PressButton label={pending ? '正在保存回答' : '发给原任务'}
        disabled={locked || !text.trim() || text.length > 4000} onClick={() => void send({text})}/>}
    </>}
    {pending && <p className="pw-muted" role="status">正在核对原回答保存回执…</p>}
    {error && <p className="pw-error" role="alert">{error}</p>}
  </section>;
}
