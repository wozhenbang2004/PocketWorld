/** Local draft lifetime follows the exact original ordinary wait, not incidental task updates. */
export function questionBinding(taskId: string | null, task: any): string {
  const raw = task?.raw;
  const question = raw?.evidence?.ownerQuestion;
  return JSON.stringify([taskId, raw?.runId, raw?.coordinatorEpoch, question?.requestId, question?.waitRevision]);
}
