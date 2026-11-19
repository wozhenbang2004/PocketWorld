import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SettingsDialog} from './forms-library';
import {RemovedOriginalText} from './RemovedOriginalText';
import {ObservedFormalHistoryPair} from './FormalHistoryComparison';
import {originalHistoryPins,type RevisionPin} from './formal-history-compare';
type Data=Record<string,any>;
type Pin={index:number;path:string;version:number;revisionId:string;at:number};
type Page={start:number;next:number;count:number;handle:string;files:Pin[]};
const uuid=(s:unknown)=>typeof s==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(s);
const cmd='projects.removedFiles.';
const issued=(v:Data,i:number)=>v&&v.index===i&&typeof v.path==='string'&&!!v.path&&v.path.length<=240&&Number.isSafeInteger(v.version)&&v.version>0&&uuid(v.revisionId)&&Number.isSafeInteger(v.at)&&v.at>=0;
export function ProjectRemovedFiles({project}:{project:string}){
  const [open,show]=useState(false),[page,setPage]=useState<Page|null>(null),[chosen,select]=useState<{pin:Pin;versions:RevisionPin[]}|null>(null);
  const [busy,spin]=useState(false),[error,tell]=useState(''),serial=useRef(0),choice=useRef(''),flight=useRef(false),mounted=useRef(true);
  const visible=()=>{const s=bridge.getState(),v=(s.view||{}) as Data;return !document.hidden&&!v.section&&!v.taskId&&s.state.projectId===project&&!s.state.filePath};
  const windowKey=()=>bridge.getDocumentEpoch()+':'+String(((bridge.getState().view||{}) as Data).resumeRevision);
  const release=()=>{const prior=choice.current;choice.current='';++serial.current;flight.current=false;
    if(prior)void bridge.command(cmd+'close',{projectId:project,choice:prior}).catch(()=>{});};
  const close=()=>{release();show(false);setPage(null);select(null);tell('');spin(false)};
  useEffect(()=>{mounted.current=true;let ticket=windowKey();const check=()=>{const next=windowKey();if((next!==ticket||!visible())&&choice.current)close();ticket=next};
    const off=bridge.subscribe(check);document.addEventListener('visibilitychange',check);
    return()=>{mounted.current=false;off();document.removeEventListener('visibilitychange',check);release()}},[project]);
  async function read(start?:number,pin?:Pin){
    if(flight.current||!visible()||typeof start==='number'&&!page||pin&&!page)return;
    if(start===undefined&&!pin){release();choice.current=crypto.randomUUID();setPage(null);select(null)}
    const token=choice.current;if(!token)return;const n=++serial.current,ticket=windowKey();flight.current=true;show(true);spin(true);tell('');
    if(pin)select(null);
    const current=()=>mounted.current&&n===serial.current&&choice.current===token&&visible()&&ticket===windowKey();
    try{const r=await bridge.command<Data>(cmd+(pin?'history':'list'),{projectId:project,choice:token,...(pin?{handle:page!.handle,index:pin.index}:start!==undefined?{handle:page!.handle,start}:{})});
      if(!current())return;
      if(!r||r.choice!==token||r.projectId!==project||!uuid(r.handle)||page&&(pin||start!==undefined)&&r.handle!==page.handle)throw Error('未取得原项目的这次删除记录。');
      if(pin){
        const s=r.selected;if(r.format!=='removed_formal_file_history_v1'||!issued(s,pin.index)||s.path!==pin.path||s.revisionId!==pin.revisionId||s.version!==pin.version||s.at!==pin.at||!Array.isArray(r.versions)||r.versions.length<1||r.versions.length>8||r.versions.some((x:Data)=>!x||typeof x.deleted!=='boolean'||x.deleted!==x.tombstone))throw Error('保留版本不属于所选删除记录。');
        const items=r.versions.map((x:Data)=>({version:x.version,deleted:x.deleted,original:{projectId:x.projectId,path:x.path,version:x.version,revisionId:x.revisionId,kind:x.kind,bytes:x.sizeBytes,...(x.deleted===true&&x.tombstone===true&&x.sha256==='absent'?{}:{sha256:x.sha256})}}));
        const versions=originalHistoryPins(items,project,pin.path),end=versions.at(-1);
        if(!end||end.revisionId!==pin.revisionId||end.version!==pin.version||end.kind!=='tombstone')throw Error('未取得可核对的较早版本和原删除修订。');
        select({pin,versions});
      }else{
        const offset=start??0;if(r.format!=='removed_formal_files_v1'||r.start!==offset||!Number.isSafeInteger(r.count)||r.count<0||r.count>4096||
            !Array.isArray(r.files)||r.files.length!==Math.min(20,r.count-offset)||r.files.some((v:Data,i:number)=>!issued(v,offset+i))||
            r.next!==(r.count>offset+20?offset+20:-1))throw Error('本页删除清单无法完整核对。');
        setPage(r as Page);
      }
    }catch(e){if(current())tell(e instanceof Error?e.message:'尚未取得原记录；没有修改任何文件。')}
    finally{if(current()){flight.current=false;spin(false)}}
  }
  return <><Button size="small" variant="ghost" onClick={()=>void read()}>已删除的文件</Button>
    {open&&<SettingsDialog isOpen title={chosen?'删除前的正式版本':'项目中已删除的文件'} onClose={close}>
      {chosen?<><Button size="small" variant="ghost" onClick={()=>select(null)}>返回删除记录</Button><h3><bdi>{chosen.pin.path}</bdi></h3>
        <p>删除记录 v{chosen.pin.version}。两版对照只读，不恢复或改写文件。</p><ObservedFormalHistoryPair pins={chosen.versions}/>
        {page&&choice.current&&<RemovedOriginalText key={chosen.pin.revisionId} versions={chosen.versions} removed={chosen.pin} choice={choice.current} handle={page.handle} project={project}/>}</>:
        <><p className="pw-field-hint">只列这个私人项目仍保留的删除记录，不读取其他项目，也不把它们列为当前文件。</p>
          {page&&<>{page.count===0?<p role="status">没有保留的删除记录。</p>:<p className="pw-field-hint">本次找到 {page.count} 个，显示 {page.start+1}–{page.start+page.files.length}。</p>}
            <div className="pw-file-version-list">{page.files.map(p=><Button key={p.revisionId} variant="ghost" disabled={busy} onClick={()=>void read(undefined,p)}><bdi>{p.path}</bdi><small>删除于 v{p.version} · 查看保留版本</small></Button>)}</div>
            <div className="pw-form-actions">{page.start>0&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>void read(page.start-20)}>上一页</Button>}
            {page.next>=0&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>void read(page.next)}>下一页</Button>}</div></>}</>}
      {busy&&<p role="status">正在读取所选项目的原记录…</p>}{error&&<p role="alert" className="pw-field-error">{error}</p>}
      {!chosen&&!busy&&<Button variant="secondary" size="small" onClick={()=>void read()}>重新读取这个项目</Button>}
    </SettingsDialog>}</>;
}
