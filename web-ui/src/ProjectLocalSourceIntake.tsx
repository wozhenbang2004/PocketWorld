import {useEffect, useId, useRef, useState} from 'react';
import {bridge} from './bridge';
import {Button, SettingsDialog, TextField} from './forms-library';
import './project-git-intake.css';

type Source = {retainedArchiveRoot?:{directory:string;descriptor:string};strippedArchiveRoot?:string;firstWebFile?:string;id:string;sha:string;manifest:string;kind:'saf_tree'|'external_zip'|'host_saved_snapshot'|'task_source_package'|'builtin_vite_starter';hostVersion?:string;excludedDirectory?:string;fixedPackage?:{manifest:string;archive:string;omitted:number};label:string;files:number;bytes:number;omitted:number;paths:string[]};
export type PhoneImportFiles = {window:string;project:string;checkout:string};
type OpenFiles = (target:PhoneImportFiles)=>Promise<void>;
type Intake = {window:string;busy:boolean;picking:boolean;recoveryOnly?:boolean;notice?:string;error?:string;suggestedSkip?:string;staged?:Source;saved:Source[];
  reviewId?:string;reviewName?:string;pendingId?:string;pendingName?:string;otherPending?:boolean;mayRetry?:boolean;mayRelease?:boolean;project?:string;checkout?:string;name?:string;openSourceFiles?:boolean};
const prefix='projects.localSource.', number=(n:number)=>new Intl.NumberFormat('zh-CN').format(n);
const sourceKind=(s:Source)=>s.kind==='builtin_vite_starter'?'内置小网页模板':s.kind==='task_source_package'?'原私人任务的固定源码':s.kind==='host_saved_snapshot'?'原主机任务已存手机的文件':s.kind==='saf_tree'?'文件夹':s.fixedPackage?'PocketWorld 源项目包':'源码 ZIP';

export function ProjectLocalSourceIntake({state,canCreateStarter=false,onOpenSourceFiles}:{state?:Intake;canCreateStarter?:boolean;onOpenSourceFiles?:OpenFiles}) {
  // A system picker pauses this document. Close only when the project surface actually unmounts,
  // not when its private snapshot is intentionally hidden in the background.
  const lastWindow=useRef('');
  if(state?.window)lastWindow.current=state.window;
  useEffect(()=>()=>{if(lastWindow.current)void bridge.command(prefix+'close',{window:lastWindow.current}).catch(()=>{});},[]);
  return state?.window?<SourcePanel key={state.window} state={state} canCreateStarter={canCreateStarter} onOpenSourceFiles={onOpenSourceFiles}/>:null;
}
function SourcePanel({state,canCreateStarter,onOpenSourceFiles}:{state:Intake;canCreateStarter:boolean;onOpenSourceFiles?:OpenFiles}){
  const source=state.staged, inflight=useRef(false), zipRootHint=useId();
  const starterAvailable=canCreateStarter&&!state.recoveryOnly, starter=source?.kind==='builtin_vite_starter';
  const root=source?.kind==='external_zip'&&!source.fixedPackage&&!source.strippedArchiveRoot?source.retainedArchiveRoot:undefined;
  const [name,setName]=useState(''),[discard,setDiscard]=useState(false);
  const [pending,setPending]=useState(false),[error,setError]=useState(''),[excluded,setExcluded]=useState('');
  useEffect(()=>{setName((source?.label||'').slice(0,80));setDiscard(false);setError('');if(source?.id)setExcluded('');},[source?.id]);
  const busy=pending||state.busy||state.picking;
  async function perform(request:()=>Promise<unknown>){
    if(inflight.current)return;
    inflight.current=true;setPending(true);setError('');
    try{await request();}
    catch(err){setError(err instanceof Error?err.message:'原操作尚未确认，请返回核对。');}
    finally{inflight.current=false;setPending(false);}
  }
  const act=(op:string, extra:Record<string,unknown>={})=>perform(()=>bridge.command(prefix+op,{window:state.window,...extra}));
  const sourceFiles=state.openSourceFiles===true&&onOpenSourceFiles;
  const published=state.project&&state.checkout?{window:state.window,project:state.project,checkout:state.checkout}:null;
  const pin=source?{id:source.id,sha:source.sha}:{};
  const close=()=>{void act('close');};
  return <SettingsDialog isOpen title={state.recoveryOnly?'核对原导入':starterAvailable?'新建或导入本机项目':'导入手机上的工程'} closeLabel="返回项目" onClose={close}>
    <div className="pw-project-git-body" aria-busy={busy}>
      {(error||state.error)&&<p className="pw-error" role="alert">{error||state.error}</p>}
      {state.picking?<p role="status">等待系统文件选择器返回…</p>:state.busy?<p role="status">正在核对本机原件…</p>:state.notice&&<p role="status">{state.notice}</p>}
      {state.otherPending?<><h3>原项目包有待核对记录</h3><p>没有把其他来源当作本次导入。</p></>
      :state.pendingId?<><h3>{state.pendingName||'新项目'} · 原创建记录</h3><p>先按原操作核对这台手机是否已有副本，不另起一次创建。</p>
        <div className="pw-action-row"><Button variant="primary" disabled={busy} onClick={()=>void act('query',{actionId:state.pendingId})}>核对原结果</Button>
        {state.mayRetry&&<Button variant="secondary" disabled={busy} onClick={()=>void act('retry',{actionId:state.pendingId})}>沿同一方案重试</Button>}
        {state.mayRelease&&<Button variant="ghost" disabled={busy} onClick={()=>void act('releaseMissing',{actionId:state.pendingId})}>放下这次创建定位</Button>}</div></>
      :published?<><h3>{state.name} · 本机私人项目</h3><p>{sourceFiles?'私人副本已登记，可以直接查看目录并选择要编辑的文件。没有启动任务。':'新工作副本已登记。打开项目后，可从「工作」编辑这份副本。没有启动任务。'}</p>
        <div className="pw-action-row">
          {sourceFiles&&<Button variant="primary" disabled={busy} onClick={()=>void perform(()=>sourceFiles(published))}>打开导入的源码</Button>}
          <Button variant={sourceFiles?'secondary':'primary'} disabled={busy}
            onClick={()=>void act('openProject',{project:published.project,checkout:published.checkout})}>打开这个项目</Button>
          {!state.recoveryOnly&&<Button variant="ghost" disabled={busy} onClick={()=>void act('another')}>{starterAvailable?'另选项目来源':'导入另一份源码'}</Button>}</div></>
      :state.recoveryOnly?(!busy&&!state.notice&&!state.error&&!error&&<p>原导入定位尚未载入，请返回项目后重新核对。</p>):source?<><h3>{state.reviewId?'核对新项目':root&&!discard?'选择工程根':starter?'小网页模板已准备':'已固定本次源码'}</h3>
        {starter&&<p>这是内置的小网页源码。创建私人项目后可编辑；准备和创建都不会安装依赖、构建或调用模型。</p>}
        {source.kind==='task_source_package'&&<p>从原任务保存的这版源码另建项目，不复制现在的工作目录，也不覆盖原副本。</p>}
        {source.kind==='host_saved_snapshot'&&<p>只包含在原任务中选定的 UTF-8 文件，不是整份主机仓库，也没有授予运行权限。</p>}
        {source.excludedDirectory&&<p>按你的选择跳过目录 <bdi>{source.excludedDirectory}/</bdi>。其中的文件没有读取，也不会进入新项目。</p>}
        {source.fixedPackage&&<p>已核对包内文件，按原相对路径放进新项目，不带外层 project/。{source.fixedPackage.omitted>0&&`原包另有 ${number(source.fixedPackage.omitted)} 项未收录。`}不会恢复旧任务、工具环境或执行权限。</p>}
        {root&&<><p>工程入口或配置在 <bdi>{root.descriptor}</bdi>。保留外层目录时，本机快捷构建不会自动进入 <bdi>{root.directory}/</bdi>。</p>
          {!state.reviewId&&!discard&&<div className="pw-action-row"><Button variant="primary" disabled={busy}
            onClick={()=>void act('zip',{stripRoot:true})}>重选同一 ZIP，尝试去掉外层</Button></div>}</>}
        <dl><div><dt>来源</dt><dd dir="auto">{source.label} · {sourceKind(source)}</dd></div>
          {source.kind==='saf_tree'&&<div><dt>项目根</dt><dd>所选文件夹的顶层。</dd></div>}
          {source.kind==='external_zip'&&!source.fixedPackage&&<div><dt>项目根</dt><dd>{source.strippedArchiveRoot?
            <>已核对去掉 ZIP 唯一外层 <bdi>{source.strippedArchiveRoot}/</bdi>，其中的文件将放在项目顶层。</>:
            <>ZIP 的顶层，保留原相对路径。{root&&<>外层 <bdi>{root.directory}/</bdi> 仍会保留。</>}</>}</dd></div>}
          {source.firstWebFile&&<div><dt>可先编辑</dt><dd dir="auto">{source.firstWebFile}</dd></div>}
          {state.reviewId&&<div><dt>去向</dt><dd dir="auto">此手机 · 新私人项目「{state.reviewName}」</dd></div>}
          <div><dt>已选范围</dt><dd>{number(source.files)} 个文件 · {number(source.bytes)} 字节{source.omitted>0&&` · 跳过 ${number(source.omitted)} 项`}</dd></div></dl>
        <details><summary>查看已选文件与固定版本</summary>
          {source.paths?.length>0&&<ul>{source.paths.map(path=><li key={path}><bdi>{path}</bdi></li>)}</ul>}
          {source.files>source.paths.length&&<p>仅列出前 {source.paths.length} 个文件。</p>}
          {source.hostVersion&&<p>原冻结版本：<bdi>{source.hostVersion}</bdi></p>}
          {source.fixedPackage&&<><p>所选原 ZIP 的 SHA-256：<bdi>{source.fixedPackage.archive}</bdi></p><p>原包清单摘要：<bdi>{source.fixedPackage.manifest}</bdi></p></>}
          <p>临时包 SHA-256：<bdi>{source.sha}</bdi></p></details>
        {state.reviewId?<><p>只复制这份固定源码。原目录、其他项目和运行授权都不变。</p>
          <div className="pw-action-row"><Button variant="primary" disabled={busy} onClick={()=>void act('confirm',{actionId:state.reviewId})}>确认创建新项目</Button>
            <Button variant="ghost" disabled={busy} onClick={()=>void act('back')}>返回修改名称</Button></div></>
        :discard?<><p>只清理这份未发布的临时包，不删除手机原件或任何已创建的项目。</p>
          <div className="pw-action-row"><Button variant="secondary" disabled={busy} onClick={()=>void act('discard',pin)}>确认清理临时包</Button>
            <Button variant="ghost" disabled={busy} onClick={()=>setDiscard(false)}>保留</Button></div></>
        :<><TextField label="新项目名称" maxLength={80} value={name} onChange={setName} isDisabled={busy}/>
          <div className="pw-action-row"><Button variant={root?'secondary':'primary'} disabled={busy||!name.trim()} onClick={()=>void act('review',{...pin,name:name.trim()})}>
              {root?'保留外层目录，继续核对':'核对新项目'}
            </Button>
            <Button variant="ghost" disabled={busy} onClick={()=>void act('another')}>换一份源码</Button>
            <Button variant="ghost" disabled={busy} onClick={()=>setDiscard(true)}>清理临时包</Button></div></>}
      </>:<><p>{starterAvailable?'从内置小网页开始，或导入已有工程。先核对文件，再创建私人项目。':'选择手机上一份已有工程，先读进私人临时区。看到实际清单后，再决定是否创建新项目。'}</p>
        {state.suggestedSkip&&<section aria-label="按刚才的文件路径调整范围"><p>如果不需要 <bdi>{state.suggestedSkip}/</bdi> 中的全部文件，可以只导入其余源码。</p>
          <Button variant="secondary" disabled={busy} onClick={()=>void act('folder',{excludedDirectory:state.suggestedSkip})}>跳过这个目录，重选文件夹</Button></section>}
        <div className="pw-action-row">
          {starterAvailable&&<Button variant={state.suggestedSkip?'secondary':'primary'} disabled={busy} onClick={()=>void act('starter')}>新建小网页</Button>}
          <Button variant={starterAvailable&&!state.suggestedSkip?'secondary':'primary'} disabled={busy} onClick={()=>void act('folder',excluded?{excludedDirectory:excluded}:{})}>选择文件夹</Button>
          <Button variant="secondary" disabled={busy} onClick={()=>void act('zip',{stripRoot:false})}>选择源码 ZIP</Button>
          <Button variant="ghost" disabled={busy} aria-describedby={zipRootHint} onClick={()=>void act('zip',{stripRoot:true})}>选择 ZIP，尝试去掉外层</Button></div>
        <p className="pw-field-hint" id={zipRootHint}>普通选择保留原路径。去掉外层的选项只在核对到唯一外层目录时移除一层。</p>
        {excluded&&<p className="pw-field-hint">下次选择文件夹会跳过 <bdi>{excluded}/</bdi>；ZIP 不受影响。</p>}
        <details><summary>导入范围与选项</summary>
          <p>默认跳过依赖、常见凭据文件和构建输出。最多 2,000 个文件，单文件 2 MiB，解包合计 16 MiB，原 ZIP 最多 20 MiB。不联网、不执行代码。</p>
          <TextField label="文件夹内要跳过的目录（可选）" value={excluded} onChange={setExcluded} maxLength={240} isDisabled={busy}
            placeholder="例如 app/build" hint="只限这次选择的文件夹。写一个完整相对路径，不使用通配符；被跳过的内容不会删除。"/></details>
        {state.saved?.length>0&&<><h3>已固定、可继续的源码</h3>{state.saved.map(s=>
          <Button key={s.id} variant="ghost" disabled={busy} onClick={()=>void act('select',{id:s.id,sha:s.sha})}>
            {s.label} · {sourceKind(s)} · {number(s.files)} 个文件</Button>)}</>}
      </>}
    </div>
  </SettingsDialog>;
}
