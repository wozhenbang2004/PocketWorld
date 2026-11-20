import { useEffect, useRef, useState } from 'react';
import { Button } from './forms-library';
import { bridge } from './bridge';
import type { PackagePreviewTarget } from './project-preview';

const read = 'projects.firstWebOutput.read';
const review = 'projects.firstWebOutput.review';
const save = 'projects.firstWebOutput.save';
const commands = [read, review, save];
type Position = {taskId: string; runId: string; epoch: number};
type BuildPosition = Position & {projectId: string; checkoutId: string};
type FileFact = {path: string; bytes: number; sha256: string};
type Fact = Partial<BuildPosition> & {
  eligible: boolean; message?: string; state?: 'ready'|'review'|'saved'|'pending'; buildOperationId?: string;
  packageOperationId?: string; reviewHandle?: string; manifestIdentity?: string; entryPath?: string; outputProfile?: string;
  sourceDigest?: string; artifactId?: string; archiveSha256?: string; archiveBytes?: number; files?: FileFact[];
};
const hash = /^[0-9a-f]{64}$/;
const id = /^[A-Za-z0-9_-]{1,80}$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
type SavedTarget = PackagePreviewTarget & {savedBuildWeb: NonNullable<PackagePreviewTarget['savedBuildWeb']>};

export function firstNodeWebOwnerWindow(taskId: string): string {
  const {view, capabilities} = bridge.getState();
  const v = view as {taskId?: string; section?: string | null; resumeRevision?: number}|undefined;
  return !document.hidden && v?.taskId===taskId && v.section==null && commands.every(k=>capabilities.commands.includes(k))
    ? JSON.stringify([bridge.getDocumentEpoch(),taskId,v.resumeRevision]) : '';
}

/** Same original result page; native persisted archive and the existing package preview stay authoritative. */
export function FirstNodeWebOutput({target, ownerWindow, open, onSaved, hideIneligible, checkBuildAfterFailure, listedSavedVersion}: {target: BuildPosition; ownerWindow: string; hideIneligible?: boolean; checkBuildAfterFailure?: boolean; open?: (p: PackagePreviewTarget)=>void;
  onSaved: (operationId: string, sha256: string) => void;
  listedSavedVersion?: (saved: SavedTarget) => boolean}) {
  const [fact, setFact] = useState<Fact|null>(null);
  const [busy, setBusy] = useState('');
  const [failure, setFailure] = useState('');
  const sequence = useRef(0), flight = useRef(false), alive = useRef(false);
  const available = () => !!ownerWindow && firstNodeWebOwnerWindow(target.taskId)===ownerWindow;
  async function call(command: string) {
    if(!alive.current || !available() || flight.current || command!==read&&!fact?.buildOperationId || command===save&&!fact?.reviewHandle) return;
    const generation = ++sequence.current;
    const current = () => alive.current && sequence.current===generation && available();
    flight.current = true; setBusy(command); setFailure('');
    try {
      const data = await bridge.command<Fact>(command,{taskId:target.taskId,runId:target.runId,epoch:target.epoch,
        ...(command!==read?{buildOperationId:fact!.buildOperationId}:{}),
        ...(command===save?{reviewHandle:fact!.reviewHandle}:{})});
      if(!current()) return;
      if(data.eligible===false){setFact(data);return;}
      if(data.eligible!==true || data.taskId!==target.taskId || data.runId!==target.runId || data.epoch!==target.epoch ||
          !data.buildOperationId || !id.test(data.buildOperationId) || data.entryPath!=='dist/index.html' ||
          !['ready','review','pending','saved'].includes(data.state||'')) throw Error('未核对到这一轮原网页成果。');
      if(command!==read && data.buildOperationId!==fact?.buildOperationId) throw Error('没有采用另一轮构建。');
      if(data.state==='review'&&(!data.reviewHandle||!data.manifestIdentity||!hash.test(data.manifestIdentity)||
          !data.archiveSha256||!hash.test(data.archiveSha256)||!Array.isArray(data.files)||
          (data.outputProfile==='stock_web_v1'?data.files.length!==4:data.outputProfile!=='vite_fresh_dist_v1'||data.files.length<2||data.files.length>64)||
          new Set(data.files.map(f=>f.path)).size!==data.files.length||!data.files.some(f=>f.path===data.entryPath)||
          !data.files.every(f=>f.path.startsWith('dist/')&&Number.isSafeInteger(f.bytes)&&f.bytes>0&&hash.test(f.sha256))))
        throw Error('完整网页清单未核对，未保存。');
      if(data.state==='saved'){
        if(!data.packageOperationId||!id.test(data.packageOperationId)||data.packageOperationId===data.buildOperationId||
            data.projectId!==target.projectId||data.checkoutId!==target.checkoutId||!data.sourceDigest||!hash.test(data.sourceDigest)||
            !data.artifactId||!uuid.test(data.artifactId)||!data.archiveSha256||
            !hash.test(data.archiveSha256)||!Number.isSafeInteger(data.archiveBytes)||Number(data.archiveBytes)<=0)
          throw Error('没有取得固定包的完整原回执。');
        onSaved(data.packageOperationId,data.archiveSha256);
      }
      setFact(data);
    } catch(error) { if(current())setFailure(error instanceof Error?error.message:'原网页请求未得到确认。'); }
    finally{if(sequence.current===generation){flight.current=false;setBusy('');}}
  }
  useEffect(()=>{
    alive.current = true;
    setFact(null);setFailure('');setBusy('');
    if(available())void call(read);
    return ()=>{alive.current=false;flight.current=false;++sequence.current;};
    // The shared owner-window projection can arrive after the navigation acknowledgement.
  },[target.taskId,target.runId,target.epoch,target.projectId,target.checkoutId,ownerWindow]);
  if(!available()||(!fact&&!failure)||hideIneligible&&fact?.eligible===false&&!failure)return null;
  const savedTarget: SavedTarget | undefined = fact?.state==='saved' && fact.projectId===target.projectId && fact.checkoutId===target.checkoutId &&
    fact.runId===target.runId && fact.epoch===target.epoch && fact.packageOperationId && fact.sourceDigest &&
    fact.artifactId && fact.archiveSha256 && fact.buildOperationId ? {taskId:target.taskId,operationId:fact.packageOperationId,
      savedBuildWeb:{projectId:target.projectId,checkoutId:target.checkoutId,sourceDigest:fact.sourceDigest,artifactId:fact.artifactId,
        artifactSha256:fact.archiveSha256,runId:target.runId,epoch:target.epoch,buildOperationId:fact.buildOperationId}} : undefined;
  const listed = !!savedTarget && listedSavedVersion?.(savedTarget)===true;
  return <section className="pw-outcome-deliverables" aria-label="原构建网页" aria-busy={!!busy}>
    <div className="pw-reading-section-heading"><h4>原构建网页</h4></div>
    {checkBuildAfterFailure && fact?.eligible===true && <p className="pw-reading-note">这项工作没有完成，但本机构建步骤已核对为成功退出。下面只处理原构建的网页。</p>}
    {fact?.eligible===false&&fact.message&&<p className="pw-reading-note">{fact.message}</p>}
    {fact?.state==='ready'&&<><p className="pw-reading-note">核对原构建退出时看到的网页文件，再决定是否保存。不重新构建。</p>
      <Button size="small" variant="secondary" disabled={!!busy} onClick={()=>void call(review)}>{busy===review?'正在核对原网页…':'核对网页包'}</Button></>}
    {fact?.state==='review'&&<><p className="pw-reading-note">{fact.entryPath} 与同一副本的其余 {(fact.files?.length ?? 1)-1} 项资源会一起固定在本机。不启动程序、导出或分享。</p>
      <details><summary>核对文件清单与版本</summary><ul>{fact.files?.map(f=><li key={f.path}><span dir="auto">{f.path}</span> · {f.bytes} 字节 · {f.sha256.slice(0,12)}</li>)}</ul></details>
      <div className="pw-action-row">
        <Button size="small" variant="primary" disabled={!!busy} onClick={()=>void call(save)}>{busy===save?'正在固定并回读…':'确认保存网页包'}</Button>
        <Button size="small" variant="ghost" disabled={!!busy} onClick={()=>void call(read)}>不保存</Button>
      </div></>}
    {fact?.state==='pending'&&<><p className="pw-reading-note" role="status">这一笔固定结果尚未核对。只查询原包，不会再存一份或重跑。</p>
      <Button size="small" variant="secondary" disabled={!!busy} onClick={()=>void call(read)}>核对原保存</Button></>}
    {fact?.state==='saved'&&<><p className="pw-reading-note" role="status">{listed?'已保存，在下方「项目固定成果」打开这次网页。':'已经保留原构建退出时核对的网页文件。打开此固定版不联网、不重跑，也不会读取当前同名文件。'}</p>
      {!listed&&savedTarget&&<Button size="small" variant="secondary" disabled={!!busy||!open} onClick={()=>{if(available())open?.(savedTarget)}}>打开固定网页</Button>}</>}
    {failure&&<><p className="pw-error" role="alert">{failure}</p><Button size="small" variant="ghost" disabled={!!busy} onClick={()=>void call(read)}>核对原结果</Button></>}
  </section>;
}
