import { useEffect, useRef, useState, type MutableRefObject } from 'react';
import { Button, CheckboxField, SettingsDialog } from './forms-library';
import { bridge } from './bridge';
import type {WorkFileSource} from './team-navigation';

type Data = Record<string, any>;
type FileRow = {artifactId:string;assignmentId:string;memberId:string;path:string;kind:'text'|'deletion'|'binary_or_large';sha256:string;retainedChars?:number;currentVersion:number;currentExists:boolean;matchesOwnerBase:boolean;retainedApplied:boolean;truncated:boolean;beforeBytes?:number;afterBytes?:number};
type Approval = {teamRunId:string;planHash:string;projectId:string;files:{path:string;existing:boolean}[];approvalHandle:string;startsIntegration:false};
type IntegrationCapture={integrationTaskId:string;reviewId:string;planHash:string;matchingFiles:number;total:number;active:boolean;unresolved:boolean;status:string;businessVerified:false};
type Reading = {integrationCapture?:IntegrationCapture;ownerDecision?:{state:string;reviewId?:string};fileHandle:string;teamRunId:string;planHash:string;files:FileRow[];incompleteCount:number;capturedAt:number;readOnly:true};
type Page = {id:string;body:string;end:number;size:number};

export function TeamFrozenFiles({team, view, active, disabled, run, onOpen, back, memberName, entry}:{
  team:string;view:string;active:boolean;disabled:boolean;run:(action:string,args:Data)=>Promise<any>;
  entry?:{requestId:string;source:WorkFileSource;returnToWork?:(reason?:string)=>void};memberName:(id:string)=>string;onOpen:(shown:boolean)=>void;back:MutableRefObject<(()=>boolean)|null>;
}) {
  const [shown,setShown]=useState(false),[source,setSource]=useState<Reading|null>(null),[selected,setSelected]=useState(''),[page,setPage]=useState<Page|null>(null);
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const [allLoaded,setAllLoaded]=useState(false),[approval,setApproval]=useState<Approval|null>(null),[ack,setAck]=useState(false);
  const [registered,setRegistered]=useState(''),[unknown,setUnknown]=useState(false);
  const generation=useRef(0),current=useRef<Reading|null>(null),pending=useRef(false),opened=useRef(false);
  const release=(handle?:string)=>{if(handle)void bridge.command('teams.closeReading',{fileHandle:handle}).catch(()=>{});};
  const close=()=>{generation.current++;pending.current=false;opened.current=false;release(current.current?.fileHandle);current.current=null;
    setAllLoaded(false);setApproval(null);setAck(false);setRegistered('');setUnknown(false);setBusy(false);setShown(false);setSource(null);setSelected('');setPage(null);setError('');onOpen(false);};
  const dismiss=()=>{close();if(entry&&!document.hidden)entry.returnToWork?.()};
  useEffect(()=>()=>{generation.current++;opened.current=false;pending.current=false;release(current.current?.fileHandle);current.current=null;onOpen(false);},[view,team,onOpen]);
  useEffect(()=>{if(!active&&opened.current)close();},[active]);
  useEffect(()=>{
    if(!shown)return;const handler=()=>{dismiss();return true;};back.current=handler;
    const hide=()=>{if(document.visibilityState!=='visible')close();};
    document.addEventListener('visibilitychange',hide);
    return()=>{if(back.current===handler)back.current=null;document.removeEventListener('visibilitychange',hide);};
  },[shown,back]);
  const open=async(fromWork?:WorkFileSource)=>{
    if(disabled||!active||opened.current||!view)return;
    const seq=++generation.current;opened.current=true;pending.current=true;onOpen(true);setShown(true);setBusy(true);setError('');
    try{
      const result=await run('teams.readFrozenOutputs',{viewHandle:view,...(fromWork?{fromWork}:{})}) as Reading;
      if(seq!==generation.current){release(result?.fileHandle);return;}
      if(!result?.fileHandle||result.readOnly!==true||result.teamRunId!==team||!Array.isArray(result.files)||!/^[a-f0-9]{64}$/.test(result.planHash)){
        release(result?.fileHandle);throw new Error('这份目录不属于原协作，请返回重新读取。');
      }
      const integration=result.integrationCapture;
      if(integration&&(integration.businessVerified!==false||!integration.integrationTaskId||!integration.reviewId||!/^[a-f0-9]{64}$/.test(integration.planHash)
          ||integration.total!==result.files.length||integration.matchingFiles!==result.files.filter(file=>file.retainedApplied).length
          ||typeof integration.active!=='boolean'||typeof integration.unresolved!=='boolean')){release(result.fileHandle);throw new Error('原整合回执与这份目录不匹配，没有把它算作已写回。');}
      if(fromWork&&(result.planHash!==fromWork.fileReview.planHash||result.files.length!==fromWork.fileReview.fileCount||result.ownerDecision?.state!=='eligible')){
        release(result.fileHandle);throw new Error('原文件不再等待这次核对，没有展示其他版本。');
      }
      current.current=result;setSource(result);
    }catch(e){if(seq===generation.current){const reason=e instanceof Error?e.message:String(e);if(fromWork&&entry?.returnToWork&&!document.hidden){close();entry.returnToWork(reason)}else setError(reason);}}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };
  const usedEntry=useRef('');
  useEffect(()=>{
    if(!entry||usedEntry.current===entry.requestId||disabled||!active||!view||opened.current)return;
    usedEntry.current=entry.requestId;void open(entry.source);
  },[entry?.requestId,disabled,active,view]);
  const read=async(row:FileRow,offset:number)=>{
    const original=current.current;if(!original||pending.current||!opened.current)return;
    const seq=++generation.current;pending.current=true;setBusy(true);setError('');setSelected(row.artifactId);if(!offset)setPage(null);
    try{
      const part=await run('teams.readFrozenDiff',{viewHandle:view,fileHandle:original.fileHandle,artifactId:row.artifactId,offset});
      if(seq!==generation.current)return;
      if(part?.readOnly!==true||part.fileHandle!==original.fileHandle||part.teamRunId!==team||part.planHash!==original.planHash||part.artifactId!==row.artifactId||part.assignmentId!==row.assignmentId
         ||part.offset!==offset||part.retainedChars!==row.retainedChars||typeof part.diff!=='string'||part.endOffset!==offset+part.diff.length
         ||part.endOffset>part.retainedChars||(part.endOffset===offset&&offset<part.retainedChars))throw new Error('差异片段身份或范围不符，没有替换成其他文件。');
      if(part.allTextRangesReturned===true)setAllLoaded(true);
      setPage(old=>({id:row.artifactId,body:(offset&&old?.id===row.artifactId&&old.end===offset?old.body:'')+part.diff,end:part.endOffset,size:part.retainedChars}));
    }catch(e){if(seq===generation.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };

  const reviewApproval=async()=>{
    const original=current.current;if(!original||original.ownerDecision?.state!=='eligible'||!allLoaded||pending.current||!opened.current||unknown||registered)return;
    const seq=++generation.current;pending.current=true;setBusy(true);setError('');setAck(false);
    try{
      const next=await run('teams.reviewFrozenTextApproval',{viewHandle:view,fileHandle:original.fileHandle});
      if(seq!==generation.current){if(next?.approvalHandle)void bridge.command('teams.closeReading',{approvalHandle:next.approvalHandle}).catch(()=>{});return;}
      if(next?.teamRunId!==team||next.fileHandle!==original.fileHandle||next.planHash!==original.planHash||!next.approvalHandle||!next.projectId||next.startsIntegration!==false
        ||!Array.isArray(next.files)||next.files.length!==original.files.length||next.files.some((f:{path?:string;existing?:boolean},i:number)=>f.path!==original.files[i].path||f.existing!==original.files[i].currentExists))
        throw new Error('写回许可的文件或项目身份待核对，没有提交。');
      setApproval(next);
    }catch(e){if(seq===generation.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };
  const cancelApproval=()=>{if(approval)void bridge.command('teams.closeReading',{approvalHandle:approval.approvalHandle}).catch(()=>{});setApproval(null);setAck(false);setError('');};
  const confirmApproval=async()=>{
    const original=current.current,exact=approval;if(!original||!exact||!ack||pending.current||!opened.current||unknown||registered)return;
    const seq=++generation.current;pending.current=true;setBusy(true);setError('');setApproval(null);setAck(false);
    try{
      const receipt=await run('teams.approveFrozenText',{viewHandle:view,fileHandle:original.fileHandle,approvalHandle:exact.approvalHandle});
      if(seq!==generation.current)return;
      if(receipt?.fileHandle!==original.fileHandle||receipt.teamRunId!==team||receipt.planHash!==original.planHash||receipt.state!=='approved_current'||!receipt.reviewId||receipt.startedByThisAction!==false)throw new Error('写回许可的结果未确认。');
      setRegistered(receipt.reviewId);
    }catch(e){if(seq===generation.current){setUnknown(true);setError((e instanceof Error?e.message:String(e))+' 请关闭并重新读取这份原文件目录核对登记，不重复提交。');}}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };

  return <><Button variant="secondary" size="small" disabled={disabled||!active||!view} onClick={()=>void open()}>查看冻结文件与原项目差异</Button>
    <SettingsDialog isOpen={shown} title={approval?"核对这份写回许可":"原协作的冻结文件"} onClose={dismiss} className="pw-team-modal">
      <div className="pw-form-stack">{approval?<><p>这份许可只适用于下列原冻结文本。批准后，管家可在原授权内请求唯一整合者将它们写回原项目；本次不会启动整合或写入文件。</p>
        <p className="pw-field-hint">原项目：<code>{approval.projectId}</code> · 计划 {approval.planHash.slice(0,12)}</p>
        <ul>{approval.files.map(file=><li key={file.path}>{file.existing?'修改':'新增'}：<code>{file.path}</code></li>)}</ul>
        <CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>我已核对这些文件的完整差异和写回目标</CheckboxField>
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={cancelApproval}>返回文件，不批准</Button><Button disabled={busy||!ack} onClick={()=>void confirmApproval()}>仅批准这份写回许可</Button></div></>:
        <p className="pw-field-hint">对比打开时的项目版本和成员交回的文件。打开或读取文件不会批准、写回或启动工作。</p>}
        {error&&<p role="alert">{error}</p>}{busy&&!source&&<p role="status">正在读取原项目与冻结成果…</p>}
        {source&&!approval&&<><p>{source.files.length} 份冻结文件{source.incompleteCount? ` · ${source.incompleteCount} 项分工在原审阅中仍未交齐`:''}</p>
          {source.integrationCapture&&<p role="status">打开时，{source.integrationCapture.matchingFiles}/{source.integrationCapture.total} 份与原写回回执及当前项目版本一致。
            {source.integrationCapture.unresolved?'原整合仍有未确定效果，不要重复写入。':source.integrationCapture.active?'原整合者仍在处理。':source.integrationCapture.total===0?'没有可逐份对照的文件，不判定写回。':source.integrationCapture.matchingFiles<source.integrationCapture.total?'未匹配的文件不一定从未写过，请逐份核对。':'文件版本相符不等于项目包交付或业务验收。'}</p>}
          {!source.files.length&&<p>没有读到这次协作的冻结文件。</p>}
          {source.files.map(file=><section className="pw-team-expanded-copy" key={file.artifactId}><h3>{file.path}</h3>
            <p className="pw-field-hint">贡献成员：{memberName(file.memberId) || file.memberId} · {file.retainedApplied?'与原整合回执及当前项目一致':file.matchesOwnerBase?'项目基线仍与原分工一致':'项目基线已改变'} · {file.currentExists?`项目版本 ${file.currentVersion}`:'打开时项目中无此文件'}</p>
            {file.kind!=='text'?<p>{file.kind==='deletion'?'这是删除记录。':'这是二进制或超出文本比较范围的文件。'}没有文本差异可展示。{typeof file.beforeBytes==='number'&&typeof file.afterBytes==='number'?` 原 ${file.beforeBytes} 字节，成果 ${file.afterBytes} 字节。`:''}</p>:
              <>{selected!==file.artifactId&&<Button variant="ghost" disabled={busy} onClick={()=>void read(file,0)}>查看这份差异</Button>}
                {selected===file.artifactId&&<>{busy&&!page&&<p role="status">正在读取差异…</p>}
                  {page?.id===file.artifactId&&<>{page.body?<pre className="pw-team-file-diff" tabIndex={0} aria-label={`${file.path} 的原始文本差异`}>{page.body}</pre>:<p>打开时两份文本没有行差异。</p>}
                    {page.end<page.size&&<Button variant="ghost" disabled={busy} onClick={()=>void read(file,page.end)}>继续读取差异（{page.end}/{page.size}）</Button>}</>}
                  {file.truncated&&<p className="pw-field-hint">原生比较只保留了一段差异，不能当作完整文件审阅。</p>}
                  <Button variant="ghost" disabled={busy} onClick={()=>{setSelected('');setPage(null);setError('');}}>收起差异</Button></>}</>}
          </section>)}
          {registered||source.ownerDecision?.state==='approved_current'?<p role="status">这份计划的写回许可已登记。批准记录不是写入回执；实际整合请看原任务。</p>
            :source.ownerDecision?.state==='eligible'&&!unknown?<><p className="pw-field-hint">{allLoaded?'所有文件的原始文本差异已加载，当前独审没有阻断项。':'完整加载每份原始差异后，才可单独核对写回许可。'}</p>
              <Button variant="secondary" disabled={!allLoaded||busy} onClick={()=>void reviewApproval()}>核对写回许可</Button></>
              :source.ownerDecision?.state==='independent_review_required'?<p className="pw-field-hint">没有与这版文件匹配的完整无阻断独审，此处不能批准。</p>:null}</>}
      </div>
    </SettingsDialog></>;
}
