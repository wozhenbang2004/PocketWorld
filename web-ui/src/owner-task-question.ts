export type OwnerQuestionOption = {id: string; label: string};
export type OwnerQuestionView = {
  questionKey: string;
  kind: 'phone' | 'capabilities';
  question: string;
  answered: boolean;
  options?: OwnerQuestionOption[];
  answerText?: string;
  continuationState?: string;
};
export type OwnerQuestionReceipt = {
  accepted: true;
  answerSaved: true;
  questionKey: string;
  messageId: string;
  taskId: string;
  runId: string;
  coordinatorEpoch: number;
  requestId: string;
  waitRevision: number;
  duplicate?: boolean;
  continuationRequested?: boolean;
  continuationState?: string;
};
export type OwnerQuestionAnswer = {text: string; optionId?: never} | {optionId: string; text?: never};
export type OwnerQuestionCommand = (action: string, args: Record<string, unknown>) => Promise<unknown>;

export function ownerQuestionReceipt(question: OwnerQuestionView, value: unknown): OwnerQuestionReceipt {
  const result = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  if (result.expired === true) throw new Error('这个原问题已过期。请重新核对原任务；回答草稿仍保留。');
  if (result.accepted !== true || result.answerSaved !== true || result.questionKey !== question.questionKey ||
      !['taskId', 'runId', 'requestId', 'messageId'].every(key => typeof result[key] === 'string' && (result[key] as string).length > 0) ||
      !['coordinatorEpoch', 'waitRevision'].every(key => Number.isSafeInteger(result[key]) && (result[key] as number) > 0)) {
    throw new Error('回答保存回执未确认。请重新核对原任务；草稿保留，没有自动重发。');
  }
  return result as OwnerQuestionReceipt;
}

/** The native bridge derives identity from its one-use review. Only answer data is sent. */
export async function submitOwnerQuestion(question: OwnerQuestionView, answer: OwnerQuestionAnswer,
                                          command: OwnerQuestionCommand): Promise<OwnerQuestionReceipt> {
  if (question.answered) throw new Error('原问题已经回答，请查看原任务回执。');
  if (Object.keys(answer).length !== 1) throw new Error('请只提交这次原问题的回答或一个选项。');
  let action: string;
  let args: Record<string, unknown>;
  if ('optionId' in answer) {
    if (typeof answer.optionId !== 'string' || !question.options?.some(option => option.id === answer.optionId))
      throw new Error('原问题中没有这个选项，请重新核对或填写回答。');
    action = 'tasks.answerQuestionChoice'; args = {optionId: answer.optionId};
  } else {
    if (!answer.text.trim() || answer.text.length > 4000) throw new Error('请输入1至4000字的回答。');
    action = 'tasks.answerQuestion'; args = {text: answer.text};
  }
  return ownerQuestionReceipt(question, await command(action, args));
}

export function ownerQuestionSavedMessage(question: OwnerQuestionView, receipt?: OwnerQuestionReceipt | null): string {
  const state = receipt?.continuationState || question.continuationState;
  if (state === 'saved_budget_exhausted') return '回答已保存，原任务剩余预算不足；请核对本次范围。';
  if (state === 'saved_scope_expired') return '回答已保存，原任务授权已到期；请核对本次范围。';
  if (receipt?.continuationRequested || state === 'original_worker_requested')
    return '回答已保存，已交回原执行；后续进展以原任务状态为准。';
  return question.kind === 'phone' ? '回答已保留在原手机任务；是否继续以原任务状态为准。' : '回答已保留在原对话；是否继续以原任务状态为准。';
}
