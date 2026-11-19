import { useEffect, useRef, useState, type MutableRefObject } from 'react';
import { Button, SettingsDialog } from './forms-library';
import { bridge } from './bridge';

type Data = Record<string, any>;
type FileRow = {artifactId:string;assignmentId:string;memberId:string;path:string;kind:'text'|'deletion'|'binary_or_large';sha256:string;retainedChars?:number;currentVersion:number;currentExists:boolean;matchesOwnerBase:boolean;retainedApplied:boolean;truncated:boolean;beforeBytes?:number;afterBytes?:number};
type Reading = {fileHandle:string;teamRunId:string;planHash:string;files:FileRow[];incompleteCount:number;capturedAt:number;readOnly:true};
type Page = {id:string;body:string;end:number;size:number};

export function TeamFrozenFiles({team, view, active, disabled, run, onOpen, back, memberName}:{
  team:string;view:string;active:boolean;disabled:boolean;run:(action:string,args:Data)=>Promise<any>;
  memberName:(id:string)=>string;onOpen:(shown:boolean)=>void;back:MutableRefObject<(()=>boolean)|null>;
}) {
  const [shown,setShown]=useState(false),[source,setSource]=useState<Reading|null>(null),[selected,setSelected]=useState(''),[page,setPage]=useState<Page|null>(null);
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const generation=useRef(0),current=useRef<Reading|null>(null),pending=useRef(false),opened=useRef(false);
  const release=(handle?:string)=>{if(handle)void bridge.command('teams.closeReading',{fileHandle:handle}).catch(()=>{});};
  const close=()=>{generation.current++;pending.current=false;opened.current=false;release(current.current?.fileHandle);current.current=null;
    setBusy(false);setShown(false);setSource(null);setSelected('');setPage(null);setError('');onOpen(false);};
  useEffect(()=>()=>{generation.current++;opened.current=false;pending.current=false;release(current.current?.fileHandle);current.current=null;onOpen(false);},[view,team,onOpen]);
  useEffect(()=>{if(!active&&opened.current)close();},[active]);
  useEffect(()=>{
    if(!shown)return;const handler=()=>{close();return true;};back.current=handler;
    const hide=()=>{if(document.visibilityState!=='visible')close();};
    document.addEventListener('visibilitychange',hide);
    return()=>{if(back.current===handler)back.current=null;document.removeEventListener('visibilitychange',hide);};
  },[shown,back]);
  const open=async()=>{
    if(disabled||!active||opened.current||!view)return;
    const seq=++generation.current;opened.current=true;pending.current=true;onOpen(true);setShown(true);setBusy(true);setError('');
    try{
      const result=await run('teams.readFrozenOutputs',{viewHandle:view}) as Reading;
      if(seq!==generation.current){release(result?.fileHandle);return;}
      if(!result?.fileHandle||result.readOnly!==true||result.teamRunId!==team||!Array.isArray(result.files)||!/^[a-f0-9]{64}$/.test(result.planHash)){
        release(result?.fileHandle);throw new Error('这份目录不属于原协作，请返回重新读取。');
      }
      current.current=result;setSource(result);
    }catch(e){if(seq===generation.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };
  const read=async(row:FileRow,offset:number)=>{
    const original=current.current;if(!original||pending.current||!opened.current)return;
    const seq=++generation.current;pending.current=true;setBusy(true);setError('');setSelected(row.artifactId);if(!offset)setPage(null);
    try{
      const part=await run('teams.readFrozenDiff',{viewHandle:view,fileHandle:original.fileHandle,artifactId:row.artifactId,offset});
      if(seq!==generation.current)return;
      if(part?.readOnly!==true||part.fileHandle!==original.fileHandle||part.teamRunId!==team||part.planHash!==original.planHash||part.artifactId!==row.artifactId||part.assignmentId!==row.assignmentId
         ||part.offset!==offset||part.retainedChars!==row.retainedChars||typeof part.diff!=='string'||part.endOffset!==offset+part.diff.length
         ||part.endOffset>part.retainedChars||(part.endOffset===offset&&offset<part.retainedChars))throw new Error('差异片段身份或范围不符，没有替换成其他文件。');
      setPage(old=>({id:row.artifactId,body:(offset&&old?.id===row.artifactId&&old.end===offset?old.body:'')+part.diff,end:part.endOffset,size:part.retainedChars}));
    }catch(e){if(seq===generation.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };

  return <><Button variant="secondary" size="small" disabled={disabled||!active||!view} onClick={()=>void open()}>查看冻结文件与原项目差异</Button>
    <SettingsDialog isOpen={shown} title="原协作的冻结文件" onClose={close} className="pw-team-modal">
      <div className="pw-form-stack"><p className="pw-field-hint">对比打开时的项目版本和成员交回的文件。这里不会批准、写回或启动工作。</p>
        {error&&<p role="alert">{error}</p>}{busy&&!source&&<p role="status">正在读取原项目与冻结成果…</p>}
        {source&&<><p>{source.files.length} 份冻结文件{source.incompleteCount? ` · ${source.incompleteCount} 项分工在原审阅中仍未交齐`:''}</p>
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
          </section>)}</>}
      </div>
    </SettingsDialog></>;
}
