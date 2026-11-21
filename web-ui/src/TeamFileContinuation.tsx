type Data = Record<string, any>;

// A candidate for a read-only review, not a claim that it can be dispatched.
export function canInspectFileContinuation(run: Data, item: Data, member?: Data): boolean {
  const child = item.assignmentTask;
  return !!member && !member.exited && (member.runner || 'model') === 'model' && member.device === 'phone'
    && !member.remoteProduction && !item.remoteSelection && !item.projectSelection && !run.productionScope?.projectSelection
    && !['stopping','cancelled','completed'].includes(run.status) && ['paused','interrupted','failed'].includes(child?.status)
    && !!child.runId && child.taskId === item.assignmentId && child.assignmentId === item.assignmentId && child.memberId === member.id;
}

export function TeamFileContinuationReview({ facts }: { facts: Data }) {
  const original = facts.originalInputFile;
  return <><h3>{facts.title}</h3><p>{facts.memberName}会从这份原分工继续，已有成果保留。仍只可处理原来选定的隔离草稿文件，不直接写回原项目，不替你批准其他操作。</p>
    {original?.readOnly === true && <p>原分工已绑定只读前置文件 <code>{original.path}</code>（冻结第 {original.version} 版，来自「{original.title}」）。没有附加新文件，也不表示成员已经读过它。</p>}
    <ul aria-label="原分工文件">{(facts.files as string[]).map(path => <li key={path}><code>{path}</code></li>)}</ul>
    <p className="pw-field-hint">确认会从原预算预留 1 次模型请求。核对时尚余 {facts.remainingModelRequests} 次模型请求、{facts.remainingToolOperations} 次团队工具操作；此分工草稿还可操作 {facts.draftOperationsRemaining} 次。本次不增加额度。</p></>;
}
