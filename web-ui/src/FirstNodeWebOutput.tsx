import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Button, SettingsDialog } from './forms-library';
import { bridge } from './bridge';
import { localWebWorkPreview, type PackagePreviewTarget, type ProjectPreviewState } from './project-preview';
import { originalDirectViteResult, type OriginalBuildWebGap } from './project-execution';
import { registerOverlayBack } from './overlay-back';
import { useProjectPreviewViewport } from './ProjectPreviewPane';

const read = 'projects.firstWebOutput.read';
const review = 'projects.firstWebOutput.review';
const save = 'projects.firstWebOutput.save';
const commands = [read, review, save];
type Command = typeof read | typeof review | typeof save;
type Position = {taskId: string; runId: string; epoch: number};
type BuildPosition = Position & {projectId: string; checkoutId: string};
type FileFact = {path: string; bytes: number; sha256: string};
type Fact = Partial<BuildPosition> & {
  eligible: boolean; message?: string; state?: 'ready'|'review'|'saved'|'pending'|'not_published'; buildOperationId?: string; operationId?: string;
  packageOperationId?: string; reviewHandle?: string; manifestIdentity?: string; entryPath?: string; outputProfile?: string;
  sourceDigest?: string; artifactId?: string; archiveSha256?: string; archiveBytes?: number; files?: FileFact[];
  reason?: 'artifact_count_limit'|'project_package_bytes_limit'; reviewedArchiveSha256?: string;
};
const hash = /^[0-9a-f]{64}$/;
const id = /^[A-Za-z0-9_-]{1,80}$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
type SavedTarget = PackagePreviewTarget & {savedBuildWeb: NonNullable<PackagePreviewTarget['savedBuildWeb']>};
type SaveIdentity = {buildOperationId: string; operationId: string; sourceDigest: string; archiveSha256: string};
type DirectVite = NonNullable<ReturnType<typeof originalDirectViteResult>>;
const localOpen='projects.firstWebOutput.open', localClose='projects.preview.close';
const supports=(action:string)=>bridge.getState().capabilities.commands.includes(action);
const canOpenLocal=()=>[localOpen,localClose,'projects.preview.viewport'].every(supports);

/** Presentation equality only; opening still uses the original package verifier. */
export function savedBuildWebOpenKey(target: PackagePreviewTarget) {
  const pick = target.savedBuildWeb;
  return pick ? JSON.stringify([target.taskId,target.operationId,pick.projectId,pick.checkoutId,pick.runId,pick.epoch,
    pick.buildOperationId,pick.sourceDigest,pick.artifactId,pick.artifactSha256]) : '';
}

export function firstNodeWebOwnerWindow(taskId: string): string {
  const {view, capabilities} = bridge.getState();
  const v = view as {taskId?: string; section?: string | null; resumeRevision?: number}|undefined;
  return !document.hidden && v?.taskId===taskId && v.section==null && commands.every(k=>capabilities.commands.includes(k))
    ? JSON.stringify([bridge.getDocumentEpoch(),taskId,v.resumeRevision]) : '';
}

function currentDirectVite(target:DirectVite,ownerWindow:string) {
  if(!ownerWindow||firstNodeWebOwnerWindow(target.taskId)!==ownerWindow)return false;
  const state=bridge.getState().state, tasks:unknown=state.tasks;
  if(state.filePath||state.attachmentWorkspace?.open===true||state.conversationReader||!Array.isArray(tasks))return false;
  const actual=originalDirectViteResult(tasks.find(task=>task?.id===target.taskId));
  return !!actual&&Object.entries(target).every(([key,value])=>actual[key as keyof DirectVite]===value);
}

type LocalWebVisit = {target:SavedTarget; session?:string; published:boolean; dismissed:boolean; closing:boolean};

/** The existing native viewport, in an adopted dialog above this same Work.
 * Never selects a project/conversation or changes the Work behind the reader. */
export function DirectViteWebOutput({target,ownerWindow}:{target:DirectVite;ownerWindow:string}) {
  const [opened,setOpened]=useState<ProjectPreviewState|null>(null), [dialog,setDialog]=useState(false);
  const [busy,setBusy]=useState(false), [error,setError]=useState('');
  const visit=useRef<LocalWebVisit|null>(null);
  const current=()=>currentDirectVite(target,ownerWindow);
  const accepts=(original:LocalWebVisit)=>visit.current===original&&current();
  const finish=(original:LocalWebVisit)=>{
    if(visit.current!==original)return;
    visit.current=null;setOpened(null);setDialog(false);setBusy(false);
  };
  const sync=()=>{
    const original=visit.current;
    if(!original)return;
    if(!current()){finish(original);return;}
    if(!original.session)return;
    const view=localWebWorkPreview(bridge.getState().state.projectPreview,original.target);
    if(view?.sessionId===original.session){
      original.published=true;
      if(!original.dismissed&&!original.closing)setOpened(view);
    }else if(original.published&&!original.closing)finish(original);
  };
  useEffect(()=>{
    const unsubscribe=bridge.subscribe(sync),epoch=bridge.subscribeDocumentEpoch(sync);
    document.addEventListener('visibilitychange',sync);
    return ()=>{visit.current=null;unsubscribe();epoch();document.removeEventListener('visibilitychange',sync);};
  },[ownerWindow,target.taskId,target.runId,target.epoch,target.sourceDigest]);
  const close=(original:LocalWebVisit)=>{
    if(!accepts(original)){finish(original);return;}
    if(original.closing)return;
    if(!original.session){original.dismissed=true;setDialog(false);return;}
    const sessionId=original.session;
    original.closing=true;setOpened(null);setBusy(true);setError('');
    void bridge.command(localClose,{sessionId}).then(value=>{
      if(!accepts(original))return;
      if(value?.closed!==true||value.sessionId!==sessionId)throw Error('无法核对原预览是否关闭。');
      finish(original);
    }).catch(()=>{
      if(!accepts(original))return;
      original.closing=false;
      if(original.dismissed||localWebWorkPreview(bridge.getState().state.projectPreview,original.target)?.sessionId!==sessionId){
        finish(original);return;
      }
      setError('尚未确认关闭这版网页。原工作和固定包仍保留。');setBusy(false);sync();
    });
  };
  const dismiss=()=>{if(visit.current)close(visit.current);};
  useLayoutEffect(()=>dialog?registerOverlayBack(dismiss):undefined,[dialog,ownerWindow]);
  const open=(value:PackagePreviewTarget)=>{
    if(visit.current||!current()||!canOpenLocal()||!value.savedBuildWeb||value.taskId!==target.taskId||
      value.savedBuildWeb.runId!==target.runId||value.savedBuildWeb.epoch!==target.epoch||
      value.savedBuildWeb.projectId!==target.projectId||value.savedBuildWeb.checkoutId!==target.checkoutId||
      value.savedBuildWeb.sourceDigest!==target.sourceDigest||value.savedBuildWeb.buildOperationId!==target.buildOperationId)return;
    const original:LocalWebVisit={target:{...value,savedBuildWeb:value.savedBuildWeb},published:false,dismissed:false,closing:false};
    visit.current=original;setOpened(null);setDialog(true);setBusy(true);setError('');
    void bridge.command(localOpen,{taskId:value.taskId,operationId:value.operationId,entryPath:'dist/index.html'}).then(reply=>{
      if(!accepts(original))return;
      const exact=localWebWorkPreview(reply,original.target);
      if(!exact)throw Error('未取得这一版归档的原预览。');
      original.session=exact.sessionId;
      if(original.dismissed){close(original);return;}
      setBusy(false);sync();
    }).catch(()=>{
      if(!accepts(original))return;
      if(!original.dismissed)setError('未能打开这一版固定网页。没有改用其他版本，原工作仍保留。');
      finish(original);
    });
  };
  const viewport=useProjectPreviewViewport(dialog,opened?.sessionId||'',busy||!current(),supports,ownerWindow,'.pw-settings-dialog-content');
  return <>
    <FirstNodeWebOutput target={target} ownerWindow={ownerWindow} directVite={target}
      open={!busy&&canOpenLocal()?open:undefined} onSaved={()=>{}}/>
    {!dialog&&error&&<p className="pw-error" role="alert">{error}</p>}
    <SettingsDialog isOpen={dialog} title="固定网页" closeLabel="返回原工作" onClose={dismiss}>
      <section className="pw-preview-pane pw-original-local-web" aria-label="原工作保存的固定网页" aria-busy={busy||!opened?.ready&&!opened?.error}>
        <p className="pw-reading-note">查看这次工作已保存的版本，不重新构建或切换对话。</p>
        <div ref={viewport} className="pw-preview-viewport" aria-label="原生隔离预览画面" data-preview-session={opened?.sessionId}>
          {!opened?.ready&&<p className="pw-preview-placeholder" role="status">{opened?.error?'原网页画面暂不可读。':'正在打开原固定网页…'}</p>}
        </div>
        {(error||opened?.error)&&<p className="pw-error" role="alert">{error||opened?.error}</p>}
        {opened&&<AccordionWebVersion target={visit.current?.target}/>}
      </section>
    </SettingsDialog>
  </>;
}

function AccordionWebVersion({target}:{target:SavedTarget|undefined}) {
  return target&&<details><summary>核对固定版本</summary><dl className="pw-preview-source-details">
    <dt>版本</dt><dd><bdi>{target.savedBuildWeb.artifactId}</bdi></dd>
    <dt>归档摘要</dt><dd><bdi>{target.savedBuildWeb.artifactSha256}</bdi></dd>
  </dl></details>;
}

/** Same original result page; native persisted archive and the existing package preview stay authoritative. */
export function FirstNodeWebOutput({target, ownerWindow, open, onSaved, hideIneligible, checkBuildAfterFailure, beforeGoalAcceptance, buildWebGap, canEditBuildCopy, listedOpenKey,directVite}: {
  target: BuildPosition; ownerWindow: string; hideIneligible?: boolean; checkBuildAfterFailure?: boolean; beforeGoalAcceptance?: boolean;
  directVite?: DirectVite;
  buildWebGap?: OriginalBuildWebGap; canEditBuildCopy?: boolean; listedOpenKey?: string;
  open?: (p: PackagePreviewTarget)=>void; onSaved: (operationId: string, sha256: string) => void}) {
  const [fact, setFact] = useState<Fact|null>(null);
  const [busy, setBusy] = useState('');
  const [failure, setFailure] = useState('');
  const [saveUnconfirmed, setSaveUnconfirmed] = useState(false);
  const saveRequest = useRef<SaveIdentity|null>(null);
  const sequence = useRef(0), flight = useRef(false), alive = useRef(false);
  const panel = useRef<HTMLElement>(null), savedOpen = useRef<HTMLButtonElement>(null);
  const [listedOpenReady, setListedOpenReady] = useState('');
  const available = () => directVite?currentDirectVite(directVite,ownerWindow):
    !!ownerWindow && firstNodeWebOwnerWindow(target.taskId)===ownerWindow;
  async function call(command: Command) {
    let attempt = saveRequest.current;
    if(!alive.current || !available() || flight.current || attempt&&command!==read ||
        command===review&&fact?.state!=='ready' || command===save&&fact?.state!=='review') return;
    if(command===save){
      if(!fact?.reviewHandle || !fact.buildOperationId || !fact.operationId || !fact.sourceDigest || !fact.archiveSha256) return;
      // A lost reply can hide a native save. This specific review is not a
      // second chance to save; only a fresh read can settle its original action.
      attempt={buildOperationId:fact.buildOperationId,operationId:fact.operationId,sourceDigest:fact.sourceDigest,archiveSha256:fact.archiveSha256};
      saveRequest.current=attempt; setSaveUnconfirmed(true);
    } else if(command===read&&fact?.state==='review'&&!attempt){
      setFact(null); // The explicit “不保存” retires the old sign-off even if the read fails.
    }
    const generation = ++sequence.current;
    const current = () => alive.current && sequence.current===generation && available();
    flight.current = true; setBusy(command); setFailure('');
    try {
      const data = await bridge.command<Fact>(command,{taskId:target.taskId,runId:target.runId,epoch:target.epoch,
        ...(command!==read?{buildOperationId:fact!.buildOperationId}:{}),
        ...(command===save?{reviewHandle:fact!.reviewHandle}:{})});
      if(!current()) return;
      if(data.eligible===false&&command===read&&!attempt){setFact(data);return;}
      if(data.eligible!==true || data.taskId!==target.taskId || data.runId!==target.runId || data.epoch!==target.epoch ||
          data.projectId!==target.projectId || data.checkoutId!==target.checkoutId || !data.sourceDigest || !hash.test(data.sourceDigest) ||
          !data.buildOperationId || !id.test(data.buildOperationId) || data.entryPath!=='dist/index.html' ||
          !data.operationId || !id.test(data.operationId) || data.operationId===data.buildOperationId ||
          !['ready','review','pending','saved','not_published'].includes(data.state||'')) throw Error('未核对到这一轮原网页成果。');
      if(directVite&&(data.buildOperationId!==directVite.buildOperationId||data.sourceDigest!==directVite.sourceDigest||
        data.state==='review'&&data.outputProfile!=='vite_fresh_dist_v1'))throw Error('原网页回执不属于这一次本机构建。');
      if(command!==read && data.buildOperationId!==fact?.buildOperationId) throw Error('没有采用另一轮构建。');
      if(attempt && (data.buildOperationId!==attempt.buildOperationId || data.operationId!==attempt.operationId || data.sourceDigest!==attempt.sourceDigest ||
          (command===save ? data.state!=='saved'&&data.state!=='not_published' : data.state==='review') ||
          data.state==='saved'&&data.archiveSha256!==attempt.archiveSha256 ||
          data.state==='not_published'&&data.reviewedArchiveSha256!==attempt.archiveSha256))
        throw Error('未取得同一笔原保存的回执，没有改用其他网页。');
      if(data.state==='not_published'&&(command===review||(data.reason!=='artifact_count_limit'&&data.reason!=='project_package_bytes_limit')||
          !data.reviewedArchiveSha256||!hash.test(data.reviewedArchiveSha256)||data.packageOperationId!==undefined||data.artifactId!==undefined||
          data.archiveSha256!==undefined||data.archiveBytes!==undefined||data.reviewHandle!==undefined))
        throw Error('未取得这一笔保存未发布的完整回执。');
      if(data.state==='review'&&(!data.reviewHandle||!data.manifestIdentity||!hash.test(data.manifestIdentity)||
          !data.archiveSha256||!hash.test(data.archiveSha256)||!Array.isArray(data.files)||
          (data.outputProfile==='stock_web_v1'?data.files.length!==4:data.outputProfile!=='vite_fresh_dist_v1'||data.files.length<2||data.files.length>64)||
          new Set(data.files.map(f=>f.path)).size!==data.files.length||!data.files.some(f=>f.path===data.entryPath)||
          !data.files.every(f=>f.path.startsWith('dist/')&&Number.isSafeInteger(f.bytes)&&f.bytes>0&&hash.test(f.sha256))))
        throw Error('完整网页清单未核对，未保存。');
      if(data.state==='saved'){
        if(data.packageOperationId!==data.operationId||
            !data.artifactId||!uuid.test(data.artifactId)||!data.archiveSha256||
            !hash.test(data.archiveSha256)||!Number.isSafeInteger(data.archiveBytes)||Number(data.archiveBytes)<=0)
          throw Error('没有取得固定包的完整原回执。');
        onSaved(data.packageOperationId,data.archiveSha256);
      }
      setFact(data);
      if(attempt){
        if(data.state!=='pending')saveRequest.current=null;
        setSaveUnconfirmed(false);
      }
    } catch(error) { if(current())setFailure(error instanceof Error?error.message:'原网页请求未得到确认。'); }
    finally{if(sequence.current===generation){flight.current=false;setBusy('');}}
  }
  useEffect(()=>{
    alive.current = true;
    saveRequest.current=null;
    setFact(null);setFailure('');setBusy('');setSaveUnconfirmed(false);
    if(available())void call(read);
    return ()=>{alive.current=false;flight.current=false;++sequence.current;};
    // The shared owner-window projection can arrive after the navigation acknowledgement.
  },[target.taskId,target.runId,target.epoch,target.projectId,target.checkoutId,ownerWindow,directVite?.sourceDigest,directVite?.buildOperationId]);
  const noSavedWeb=fact?.eligible===false&&buildWebGap!==undefined&&!failure;
  const visible=available() && (!!fact||!!failure) && !(hideIneligible&&fact?.eligible===false&&!noSavedWeb&&!failure);
  const savedTarget: SavedTarget | undefined = fact?.state==='saved' && fact.projectId===target.projectId && fact.checkoutId===target.checkoutId &&
    fact.runId===target.runId && fact.epoch===target.epoch && fact.packageOperationId && fact.sourceDigest &&
    fact.artifactId && fact.archiveSha256 && fact.buildOperationId ? {taskId:target.taskId,operationId:fact.packageOperationId,
      savedBuildWeb:{projectId:target.projectId,checkoutId:target.checkoutId,sourceDigest:fact.sourceDigest,artifactId:fact.artifactId,
        artifactSha256:fact.archiveSha256,runId:target.runId,epoch:target.epoch,buildOperationId:fact.buildOperationId}} : undefined;
  const exactListedOpen=visible&&savedTarget&&open&&!busy&&!failure&&listedOpenKey===savedBuildWebOpenKey(savedTarget)?listedOpenKey:'';
  useLayoutEffect(()=>{
    if(!exactListedOpen){setListedOpenReady('');return;}
    // The confirmed save action stays put until the exact second action really
    // exists in this outcome. Move focus before retiring the now-duplicate action.
    const buttons=panel.current?.closest('.pw-task-outcome')?.querySelectorAll<HTMLButtonElement>('button[data-saved-build-web-open]');
    const exact=Array.from(buttons??[]).filter(button=>button.dataset.savedBuildWebOpen===exactListedOpen);
    const replacement=exact.length===1?exact[0]:null;
    if(!replacement?.isConnected||replacement.disabled||replacement.getAttribute('aria-disabled')==='true'||
        replacement.closest('[hidden],[inert],[aria-hidden="true"]')||!replacement.getClientRects().length){
      setListedOpenReady('');return;
    }
    if(savedOpen.current&&document.activeElement===savedOpen.current){
      replacement.focus({preventScroll:true});
      if(document.activeElement!==replacement)return;
    }
    setListedOpenReady(exactListedOpen);
  },[exactListedOpen]);
  if(!visible)return null;
  const openListed=!!exactListedOpen&&listedOpenReady===exactListedOpen;
  return <section ref={panel} className="pw-outcome-deliverables" aria-label="原构建网页" aria-busy={!!busy}>
    <div className="pw-reading-section-heading"><h4>原构建网页</h4></div>
    {checkBuildAfterFailure && fact?.eligible===true && <p className="pw-reading-note">这项工作没有完成，但本机构建步骤已核对为成功退出。下面只处理原构建的网页。</p>}
    {beforeGoalAcceptance && fact?.eligible===true && <p className="pw-reading-note">本机构建已核对为成功退出。核对或保存网页不会替你验收目标。</p>}
    {noSavedWeb&&buildWebGap==='nested_vite_output'?<p className="pw-reading-note" role="status"><strong>构建通过，固定网页未保存。</strong>
      {' '}输出含额外子目录，不在本机固定网页支持范围。{canEditBuildCopy?'可在下方继续编辑构建所用的副本。':'可在「过程」查看本次构建记录。'}</p>:
      noSavedWeb?<p className="pw-reading-note" role="status"><strong>构建通过（退出码 0），固定网页未保存。</strong> 可在「过程」查看本次构建记录。</p>:
      fact?.eligible===false&&fact.message&&<p className="pw-reading-note">{fact.message}</p>}
    {fact?.state==='ready'&&<><p className="pw-reading-note">这次构建的网页包还未固定保存。请先核对退出时看到的文件，不重新构建。</p>
      <Button size="small" variant="secondary" disabled={!!busy} onClick={()=>void call(review)}>{busy===review?'正在核对原网页…':'核对网页包'}</Button></>}
    {fact?.state==='review'&&!saveUnconfirmed&&<><p className="pw-reading-note">{fact.entryPath} 与同一副本的其余 {(fact.files?.length ?? 1)-1} 项资源会一起固定在本机。不启动程序、导出或分享。</p>
      <details><summary>核对文件清单与版本</summary><ul>{fact.files?.map(f=><li key={f.path}><span dir="auto">{f.path}</span> · {f.bytes} 字节 · {f.sha256.slice(0,12)}</li>)}</ul></details>
      <div className="pw-action-row">
        <Button size="small" variant="primary" disabled={!!busy} onClick={()=>void call(save)}>确认保存网页包</Button>
        <Button size="small" variant="ghost" disabled={!!busy} onClick={()=>void call(read)}>不保存</Button>
      </div></>}
    {saveUnconfirmed&&<><p className="pw-reading-note" role="status">{busy===save?'正在等待原保存回执，尚未确认归档。':
      busy===read?'正在核对这笔原保存…':'这笔保存尚未核对，不能判断是否已经归档。只核对原保存，不会重复提交。'}</p>
      {!busy&&<Button size="small" variant="secondary" onClick={()=>void call(read)}>核对原保存</Button>}</>}
    {fact?.state==='pending'&&<><p className="pw-reading-note" role="status">这一笔固定结果尚未核对。只查询原包，不会再存一份或重跑。</p>
      <Button size="small" variant="secondary" disabled={!!busy} onClick={()=>void call(read)}>核对原保存</Button></>}
    {fact?.state==='not_published'&&<p className="pw-reading-note" role="status">这次构建成功，但网页包没有保存。
      {fact.reason==='artifact_count_limit'?'保存时文件成果已达 200 项上限。':'当时存入这份网页包会超过固定项目包共用的 64 MiB 上限。'}
      没有改动已有成果，这笔保存不会重试。</p>}
    {fact?.state==='saved'&&<><p className="pw-reading-note" role="status">网页已固定保存。打开这一版不会联网、重新构建或读取当前同名文件。</p>
      {savedTarget&&!openListed&&<Button ref={savedOpen} size="small" variant="primary" disabled={!!busy||!open} onClick={()=>{if(available())open?.(savedTarget)}}>打开固定网页</Button>}</>}
    {failure&&<><p className="pw-error" role="alert">{failure}</p>
      {!saveUnconfirmed&&fact?.state!=='pending'&&<Button size="small" variant="ghost" disabled={!!busy} onClick={()=>void call(read)}>核对原结果</Button>}</>}
  </section>;
}
