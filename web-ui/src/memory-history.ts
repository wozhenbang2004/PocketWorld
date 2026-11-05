type Data = Record<string, any>;

/** Local presentation lifetime; native handles remain the authority for every read and write. */
export class MemoryReadingWindow {
  private revision = 0;
  private alive = true;
  private sending = false;
  capture() { return this.revision; }
  current(revision: number) { return this.alive && revision === this.revision; }
  invalidate() { this.revision++; this.sending = false; }
  close() { this.invalidate(); this.alive = false; }
  reopen() { this.invalidate(); this.alive = true; }
  enter(revision: number) { if (!this.current(revision) || this.sending) return false; this.sending = true; return true; }
  finish(revision: number) { if (this.current(revision)) this.sending = false; }
}

export function memoryHistoryStatus(value: string) {
  return ({ historical: '历史版本，不代表现在的事实', current: '当前记录，只读查阅', pending_conflict: '冲突待核，尚未确定当前事实' } as Record<string, string>)[value] || '主体或发生时间未记载';
}

export function taskMemorySummary(receipt: Data | null) {
  if (!receipt || receipt.phase === 'unavailable' || receipt.status === 'unavailable') return '原生提供凭据不可用；不会用当前检索补写当时的来源。';
  if (receipt.phase !== 'provided_to_request') return '尚未证明这批记忆已经提供给模型。';
  if (receipt.status !== 'current' || (receipt.records || []).some((row: Data) => row.status !== 'current')) return '部分来源已失效，请重新核对原任务；旧正文不再显示。';
  return '本次提供给模型的记忆来源。提供记录不证明模型实际采用，也不授予执行权。';
}

export function taskMemoryVisibleRecords(receipt: Data | null): Data[] {
  if (!receipt || !Array.isArray(receipt.records)) return [];
  const readable = receipt.phase === 'provided_to_request' && ['current', 'invalidated'].includes(receipt.status);
  return receipt.records.map((row: Data) => {
    const copy = { ...row };
    if (!readable || row.status !== 'current') { delete copy.text; delete copy.readHandle; }
    return copy;
  });
}

export function taskMemorySelectionDetails(receipt: Data | null) {
  if (!receipt || receipt.selectionDiagnosticsAvailable !== true || !receipt.selectionDiagnostics) return null;
  const value = receipt.selectionDiagnostics;
  return { core: value.coreSelected, retrieved: value.retrievedSelected, chars: value.charsUsed,
    omitted: (Array.isArray(value.omitted) ? value.omitted : []).map((row: Data) => ({ id: row.id, revision: row.revision, reason: row.reason })),
    counts: value.omissionCounts || {} };
}
