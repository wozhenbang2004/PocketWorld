import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { IconChevronRight, IconHistory } from './icons';
import {originalSeenHostEnd} from './original-seen-host-end';
import type { SavedCloudWorkspace } from './CloudWorkspaceChoice';

type Data = Record<string, any>;
type Row = { taskId: string; runId: string; epoch: number; goal: string; stage: 'phone_registered' | 'host_acknowledged'; executorStatus?: string; detailRetired?: boolean; jobId?:string; previouslySeenEnd?:unknown };
type Page = { items: Row[]; next: string };
const keys: (keyof SavedCloudWorkspace)[] = ['profileId','profileBinding','environmentId','worker','workspaceAnchor'];
const same = (a: Data | undefined, b: SavedCloudWorkspace) => !!a && keys.every(k => a[k] === b[k]);
const states: Record<string,string> = { running: '执行中', queued: '在队列中', completed: '已结束，成果需另行核对', failed: '执行失败', interrupted: '已中断', cancelled: '已停止', timed_out:'超时', stopping:'正在停止', awaiting_approval: '待确认', awaiting_input: '待补充', unknown: '未核实' };
const uuid = (s: unknown) => typeof s === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(s);

export function CloudWorkspaceJobs({ scope, run }: { scope: SavedCloudWorkspace; run: (action: string, args: Data) => Promise<any> }) {
  const [page, setPage] = useState<Page | null>(null), [pending, setPending] = useState(false), [error, setError] = useState('');
  const mounted = useRef(false), waiting = useRef(false), serial = useRef(0);
  const current = () => {
    const s = bridge.getState(), v = s.view as Data | undefined, remote = (s.state as Data)?.features?.remote;
    return mounted.current && !document.hidden && v?.section === 'remote' && !v.taskId && same(remote?.cloudWorkspace?.target,scope)
      && Array.isArray(remote.profiles) && remote.profiles.some((p: Data) => p.id === scope.profileId && p.bindingHash === scope.profileBinding);
  };
  useEffect(() => {
    mounted.current = true;
    const clear = () => { if (!document.hidden) return; ++serial.current; waiting.current = false; setPage(null); setPending(false); setError(''); };
    document.addEventListener('visibilitychange',clear);
    return () => { mounted.current = false; ++serial.current; document.removeEventListener('visibilitychange',clear); };
  }, []);
  const act = async (row?: Row, cursor = '') => {
    if (!current() || waiting.current || cursor && cursor !== page?.next) return;
    const v = bridge.getState().view as Data, ticket = [bridge.getDocumentEpoch(),v?.resumeRevision], n = ++serial.current;
    const visible = () => current() && serial.current === n && bridge.getDocumentEpoch() === ticket[0] && (bridge.getState().view as Data)?.resumeRevision === ticket[1];
    waiting.current = true; setPending(true); setError('');
    try {
      if (row) { await run('remote.openWorkspaceJob',{ ...scope, taskId:row.taskId, runId:row.runId, epoch:row.epoch }); return; }
      setPage(null);
      const x = await run('remote.workspaceJobs',{ ...scope, cursor });
      if (!visible()) return;
      if (x?.format !== 'pocketworld.cloud-jobs-page.v1' || !same(x.scope,scope) || x.networkUsed !== false || x.executionAuthorized !== false
        || typeof x.nextCursor !== 'string' || typeof x.hasMore !== 'boolean' || x.hasMore !== !!x.nextCursor || !Array.isArray(x.items) || x.items.length > 8
        || x.items.some((r: Row & { jobId?: string }) => !r || !uuid(r.taskId) || !uuid(r.runId) || !Number.isSafeInteger(r.epoch) || r.epoch < 0 || typeof r.goal !== 'string'
          || !['phone_registered','host_acknowledged'].includes(r.stage) || r.stage === 'host_acknowledged' && (!r.jobId || typeof r.executorStatus !== 'string'))) throw new Error('这一页不能证明来自原工作目录。');
      setPage({items:x.items,next:x.nextCursor});
    } catch (e) { if (visible()) setError(e instanceof Error ? e.message : '这一页没有读完整，请重新读取原记录。'); }
    finally { if (n === serial.current) { waiting.current = false; if (mounted.current) setPending(false); } }
  };
  return <section className="pw-connection-subsection" aria-label="原主机工作">
    <div className="pw-connection-section-toolbar"><h4>这个工作区的原工作</h4><Button size="small" variant="secondary" disabled={pending} onClick={() => void act()}><IconHistory size={16}/>{pending ? '正在读取' : page ? '重新读取' : '查看本机记录'}</Button></div>
    {(page || error) && <p className="pw-field-hint">只看手机保存的原委托。主机最后一次回执不代表现在在线；核对进度、处理确认或查看成果，请打开原任务。</p>}
    {page && !page.items.length && <p role="status">{page.next ? '这页没有能核对归属的任务，可继续查看更早记录。' : '这里没有能核对归属的原任务。旧委托仍可从工作记录打开。'}</p>}
    {page?.items.map(row => {const ending=originalSeenHostEnd(row);return <article key={row.taskId} className="pw-connection-job"><header><h3>{row.goal || '原主机委托'}</h3></header>
      <p>{row.detailRetired ? '主机曾接单 · 详细记录已归档' : ending || (row.stage === 'host_acknowledged' ? '主机已接单 · 最后回执：'+(states[row.executorStatus ?? ''] || '状态未核实') : '手机已登记 · 此回执不能确认主机已接单')}</p>
      {row.detailRetired&&ending&&<p className="pw-field-hint">{ending}</p>}
      <Button size="small" variant="ghost" disabled={pending} onClick={() => void act(row)}>打开原任务与成果<IconChevronRight size={16}/></Button></article>;})}
    {page?.next && <Button size="small" variant="secondary" disabled={pending} onClick={() => void act(undefined,page.next)}>查看更早记录</Button>}
    {error && <p role="alert">{error}</p>}
  </section>;
}
