import { useCallback, useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { IconChevronRight, IconFile, IconHistory } from './icons';
type Data = Record<string, any>;
type Item = { taskId: string; runId: string; epoch: number; kind: string; goal?: string; command?: string; status: string; updatedAt?: number; operationId?: string; exitCode?: number; savedFile?: { name: string; bytes: number } };
type Page = { scope: string; items: Item[]; hasMore: boolean; nextCursor: string };
const actions: Record<string,string> = { exec: '运行命令', writeFile: '保存远端文件', inspectFile: '核对远端文件版本', downloadFile: '把文件保存到手机', uploadFile: '传送私人附件', openPreviewForward: '开发预览' };
const states: Record<string,string> = { queued:'等待执行', preparing:'准备中', running:'执行中', needs_confirmation:'有回执待确认', awaiting_user:'等待你处理', reconciling:'原结果待核对', unknown:'原结果待核对', failed:'本次未完成', interrupted:'原执行已中断', completed:'已完成', cancelled:'已停止', stopped:'已停止', paused:'已暂停', stopping:'正在停止' };
const windowKey = () => { const view = bridge.getState().view as Data | undefined; return [bridge.getDocumentEpoch(), view?.section, view?.taskId, view?.resumeRevision].join('|'); };

export function SshOriginalWork({ profile, supported, run }: { profile: Data; supported: boolean; run: (action: string, args: Data) => Promise<any> }) {
  const [page, setPage] = useState<Page | null>(null), [pending, setPending] = useState(false), [error, setError] = useState('');
  const alive = useRef(false), waiting = useRef(false), generation = useRef(0), last = useRef<Page | null>(null);
  const scope = JSON.stringify([profile.id, profile.bindingHash]);
  const live = () => { const host = bridge.getState(), view = host.view as Data | undefined, rows = (host.state as Data)?.features?.ssh?.profiles; return alive.current && !document.hidden && view?.section === 'ssh' && !view.taskId && Array.isArray(rows) && rows.some(p => p?.id === profile.id && p.bindingHash === profile.bindingHash); };
  const read = useCallback(async (cursor = '') => {
    if (!supported || !live() || waiting.current || cursor && (last.current?.scope !== scope || last.current.nextCursor !== cursor)) return;
    const n = ++generation.current, owner = windowKey(); waiting.current = true; setPending(true); setError('');
    if (!cursor) { last.current = null; setPage(null); }
    const current = () => live() && generation.current === n && windowKey() === owner;
    try {
      const value = await run('ssh.workHistory', { profileId: profile.id, bindingHash: profile.bindingHash, cursor });
      if (!current()) return;
      if (value?.format !== 'pocketworld.ssh-work-page.v1' || value.readOnly !== true || value.executionAuthorized !== false || value.networkUsed !== false
        || value.profileId !== profile.id || value.bindingHash !== profile.bindingHash || typeof value.hasMore !== 'boolean' || typeof value.nextCursor !== 'string'
        || value.hasMore !== !!value.nextCursor || !Array.isArray(value.items) || value.items.length > 8 || value.items.some((r: Item) =>
          !r || !['agent','operation'].includes(r.kind) || !r.taskId || !r.runId || !Number.isSafeInteger(r.epoch) || r.epoch < 0)) throw new Error('本机没有返回属于这项连接的完整工作页。');
      const result = { scope, items: value.items, hasMore: value.hasMore, nextCursor: value.nextCursor }; last.current = result; setPage(result);
    } catch (e) { if (current()) { last.current = null; setPage(null); setError(e instanceof Error ? e.message : '原主机工作暂不可读取'); } }
    finally { if (current()) { waiting.current = false; setPending(false); } }
  }, [scope, supported, run]);
  const readRef = useRef(read); readRef.current = read;
  useEffect(() => {
    alive.current = true; let owner = windowKey();
    const retire = () => { generation.current++; waiting.current = false; last.current = null; setPage(null); setPending(false); setError(''); };
    const changed = () => { const next = windowKey(); if (next !== owner || !live()) { owner = next; retire(); } };
    const unsubscribe = bridge.subscribe(changed); document.addEventListener('visibilitychange', changed); window.addEventListener('pagehide', retire); void readRef.current();
    return () => { alive.current = false; generation.current++; waiting.current = false; last.current = null; unsubscribe(); document.removeEventListener('visibilitychange',changed); window.removeEventListener('pagehide',retire); };
  }, [scope, supported]);
  const open = async (row: Item) => {
    if (!live() || waiting.current || last.current?.scope !== scope || !last.current.items.includes(row)) return;
    const n = ++generation.current, owner = windowKey(); waiting.current = true; setPending(true); setError('');
    try { await run('ssh.openOriginalWork', { profileId:profile.id, bindingHash:profile.bindingHash, taskId:row.taskId, runId:row.runId, epoch:row.epoch }); }
    catch(e) { if (live() && generation.current === n && owner === windowKey()) setError(e instanceof Error ? e.message : '原工作已变化，请刷新。'); }
    finally { if (live() && generation.current === n && owner === windowKey()) { waiting.current = false; setPending(false); } }
  };
  const shown = page?.scope === scope ? page : null;
  return <section className="pw-connection-jobs" aria-label="这项 SSH 连接的原工作">
    <h3>这项连接的原工作</h3><p className="pw-field-hint">{profile.username}@{profile.host}:{profile.port} · {profile.workspace}</p>
    <p className="pw-field-hint">读取手机上的原运行记录，不连接服务器。只有同一配置版本会列在这里；其他记录仍可在总工作记录中查找。</p>
    {!supported && <p>当前版本未提供按原主机核对的历史。</p>}{pending && <p role="status">正在读取本机原工作…</p>}{error && <p role="alert" className="pw-field-error">{error}</p>}
    {shown && !shown.items.length && <p>{shown.hasMore ? '本页没有匹配的工作，可以继续查找更早记录。' : '在已读取的范围内，未找到可核对的原工作。'}</p>}
    {shown?.items.map(row => <article key={row.taskId + ':' + row.runId} className="pw-connection-job">
      <header><h3>{row.kind === 'agent' ? row.goal || 'SSH Agent 委托' : actions[row.command || ''] || 'SSH 原操作'}</h3><span>{states[row.status] || row.status}</span></header>
      {row.updatedAt != null && row.updatedAt > 0 && row.updatedAt <= 8.64e15 && <small><time dateTime={new Date(row.updatedAt).toISOString()}>{new Date(row.updatedAt).toLocaleString()}</time></small>}
      {row.kind === 'agent' && <p>原 Agent 委托 · 已登记所选主机，不代表已执行远端命令。</p>}
      {['reconciling','unknown'].includes(row.status) && <p>远端结果未确认，不等于失败或已停止。</p>}
      {Number.isInteger(row.exitCode) && <p>命令退出码：{row.exitCode}。这不是构建成果证明。</p>}
      {row.savedFile && <p><IconFile size={15} aria-hidden /> 传输回执：{row.savedFile.name} · {row.savedFile.bytes.toLocaleString()} 字节已存入私人附件。本页未重读原件。</p>}
      <details><summary>本机记录的任务与运行编号</summary><p className="pw-source-copy">任务：{row.taskId}<br/>执行：{row.runId}{row.operationId && <><br/>原操作：{row.operationId === row.runId ? '与上述执行同号' : row.operationId}</>}</p></details>
      <Button variant="secondary" leadingIcon={IconChevronRight} disabled={pending} onClick={() => void open(row)}>查看原工作</Button>
    </article>)}
    {supported && <div className="pw-form-actions">{shown?.hasMore && <Button variant="secondary" disabled={pending} onClick={() => void read(shown.nextCursor)}>读更早的记录</Button>}<Button variant="ghost" leadingIcon={IconHistory} disabled={pending} onClick={() => void read()}>重读最近记录</Button></div>}
  </section>;
}
