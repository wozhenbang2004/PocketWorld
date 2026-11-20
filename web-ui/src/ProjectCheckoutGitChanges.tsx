import { Button } from './forms-library';

type Row = { path: string; indexStatus: string; worktreeStatus: string; conflict: boolean; canDiff: boolean; canOpen: boolean };
type Status = { operationId: string; repositoryPresent: boolean; entries: Row[]; more: boolean };
type Diff = { operationId: string; path: string; text: string; more: boolean };
export type CheckoutGitView = { status?: Status; diff?: Diff; pendingOperation?: string; message?: string };
type Input = { kind: 'status'|'query' } | { kind: 'nextStatus'; statusOperation: string } |
  { kind: 'diff'; statusOperation: string; path: string } | { kind: 'nextDiff'; statusOperation: string; diffOperation: string };
const operation = (value: unknown): value is string => typeof value==='string' && /^[a-f0-9-]{36}$/.test(value);
const statuses: Record<string,string> = {
  '.':'无变化', 'M':'修改', 'A':'新增', 'D':'删除', 'R':'重命名', 'C':'复制', 'U':'冲突', 'T':'类型改变', '?':'未跟踪',
};
const formatStatus = (value: string) => statuses[value] || value;

export function checkoutGitView(value: Record<string, any>, project: string, checkout: string, files: string): CheckoutGitView {
  const status = value.status, diff = value.diff;
  if (value.projectId!==project || value.checkoutId!==checkout || value.filesHandle!==files || value.readOnly!==true || value.executionAuthorized!==false ||
      value.pendingOperation!==undefined && !operation(value.pendingOperation) ||
      status && (!operation(status.operationId) || typeof status.repositoryPresent!=='boolean' || typeof status.more!=='boolean' ||
        !Array.isArray(status.entries) || status.entries.length>40 || status.entries.some((row: Row)=>!row || typeof row.path!=='string' ||
          !row.path || row.path.length>240 || !/^[.MADRCUT?]$/.test(row.indexStatus) || !/^[.MADRCUT?]$/.test(row.worktreeStatus) ||
          typeof row.conflict!=='boolean' || typeof row.canOpen!=='boolean' || typeof row.canDiff!=='boolean') ||
        new Set(status.entries.map((row: Row)=>row.path)).size!==status.entries.length) ||
      diff && (!status || !operation(diff.operationId) || typeof diff.more!=='boolean' || typeof diff.text!=='string' ||
        new TextEncoder().encode(diff.text).length>16000 || !status.entries.some((row: Row)=>row.path===diff.path && row.canDiff)))
    throw new Error('Git 回执不属于这份工作副本或原路径，未读取替代文件。');
  return {status,diff,pendingOperation:value.pendingOperation,message:typeof value.message==='string'?value.message:undefined};
}

export function ProjectCheckoutGitChanges({ value, busy, inspect, open }: {
  value: CheckoutGitView | null; busy: boolean;
  inspect: (input: Input) => void; open: (path: string, statusOperation: string) => void;
}) {
  const status = value?.status, diff = value?.diff, locked = busy || !!value?.pendingOperation;
  return <section className="pw-form-flow" aria-label="这个副本的本地 Git 变更" aria-busy={busy}>
    <div className="pw-form-actions">
      {value?.pendingOperation ? <Button size="small" variant="secondary" disabled={busy} onClick={()=>inspect({kind:'query'})}>核对同一笔 Git 读取</Button> :
        <Button size="small" variant="secondary" disabled={busy} onClick={()=>inspect({kind:'status'})}>{status?'重新读取 Git 变更':'读取 Git 变更'}</Button>}
    </div>
    <p className="pw-field-hint">只读这个副本的 Git 状态。差异对比暂存区与已保存的副本文件，未保存的草稿不在其中。</p>
    {value?.message && <p role="status">{value.message}</p>}
    {status?.repositoryPresent===false && <p role="status">这个副本当前没有可读取的 Git 仓库。</p>}
    {status?.repositoryPresent && <>
      {!status.entries.length && <p role="status">已读取的这一页没有此处可显示的源码变更。</p>}
      <ul className="pw-form-flow">
        {status.entries.map(row=><li key={row.path}>
          <span dir="auto">{row.path}</span>
          <div className="pw-field-hint">{row.conflict?'冲突 · ':''}暂存：{formatStatus(row.indexStatus)} · 工作树：{formatStatus(row.worktreeStatus)}</div>
          {(row.canDiff || row.canOpen) && <div className="pw-form-actions">
            {row.canOpen && <Button variant="secondary" size="small" disabled={locked} onClick={()=>open(row.path,status.operationId)}>打开副本文件</Button>}
            {row.canDiff && <Button variant="ghost" size="small" disabled={locked} aria-expanded={diff?.path===row.path}
              onClick={()=>inspect({kind:'diff',path:row.path,statusOperation:status.operationId})}>看工作树差异</Button>}
          </div>}
          {diff?.path===row.path && <div className="pw-form-flow">
            {diff.text ? <pre className="pw-checkout-review-diff" aria-label="原工作树 Git 差异">{diff.text}</pre> : <p>这一段没有可显示的差异文字。</p>}
            {diff.more && <Button variant="ghost" size="small" disabled={locked} onClick={()=>inspect({kind:'nextDiff',statusOperation:status.operationId,diffOperation:diff.operationId})}>读取差异下一段</Button>}
          </div>}
        </li>)}
      </ul>
      {status.more && <Button variant="ghost" size="small" disabled={locked} onClick={()=>inspect({kind:'nextStatus',statusOperation:status.operationId})}>读取下一页变更</Button>}
    </>}
  </section>;
}
