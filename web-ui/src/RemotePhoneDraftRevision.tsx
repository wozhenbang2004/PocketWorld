import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,TextAreaField} from './forms-library';
import {remoteObject as object,type RemoteData as Data} from './remote-task-workspace';

type Pin={id:string;revision:number;scopeHash:string;localState:'draft'|'queued'|'withdrawn'|'claimed'};
type Saved={revision:number;updatedAt:number};
type Props={task:string;run:string;epoch:number;row:Pin;original:string;onSaved:(stamp:Saved)=>void;onCancel:()=>void};
const op='remoteTask.phoneDrafts.';
const digest=async(v:string)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(v))),n=>n.toString(16).padStart(2,'0')).join('');

/** Review is local. Only the native one-use handle can save these exact bytes back to the unclaimed row. */
export function RemotePhoneDraftRevision({task,run,epoch,row,original,onSaved,onCancel}:Props){
  const [text,edit]=useState(original),[held,hold]=useState<{handle:string;sha:string;body:string}|null>(null),[busy,spin]=useState(false),[error,fail]=useState('');
  const alive=useRef(true),flight=useRef(false);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false}},[]);
  const stamp=()=>bridge.getDocumentEpoch()+':'+String(object(bridge.getState().view).resumeRevision);
  const active=()=>!document.hidden&&object(bridge.getState().view).taskId===task;
  async function act(save:boolean){
    if(flight.current||!active()||save&&!held||!save&&(text===original||!text.trim()))return;
    flight.current=true;spin(true);fail('');const window=stamp(),choice=held,proposed=text,same=()=>alive.current&&active()&&stamp()===window;
    try{
      const sha=save?choice!.sha:await digest(proposed),old=await digest(original);if(!same())return;
      const base={taskId:task,runId:run,coordinatorEpoch:epoch};
      const r=await bridge.command<Data>(op+(save?'saveEdit':'reviewEdit'),save?{...base,changeHandle:choice!.handle}:
        {...base,id:row.id,revision:row.revision,scopeHash:row.scopeHash,oldSha256:old,replacement:proposed});
      if(!same())return;
      if(r.taskId!==task||r.runId!==run||r.coordinatorEpoch!==epoch||r.id!==row.id||r.scopeHash!==row.scopeHash||
           r.textSha256!==sha||r.previousLocalState!==row.localState||r.networkUsed!==false||r.sent!==false)throw Error('没有取得这份原稿的本机回执。');
      if(!save){
        if(r.oldSha256!==old||r.revision!==row.revision||r.saved!==false||r.willQueue!==false||typeof r.changeHandle!=='string'||!r.changeHandle)throw Error('原稿、新文字或发送状态已变化，未保存。');
        hold({handle:r.changeHandle,sha,body:proposed});
      }else{
        if(r.queued!==false||r.localState!=='draft'||r.previousRevision!==row.revision||r.revision!==row.revision+1||!Number.isSafeInteger(r.updatedAt)||r.updatedAt<=0)
          throw Error('本机保存回执未确认。请回原清单检查版本，不会自动重试。');
        onSaved({revision:r.revision,updatedAt:r.updatedAt});
      }
    }catch(e){if(same()){hold(null);fail(e instanceof Error?e.message:'原稿版本不能核对。未自动保存或发送。')}}
    finally{flight.current=false;if(alive.current)spin(false)}
  }
  return <><p className="pw-field-hint">修改这份本机原稿 v{row.revision}。{row.localState==='queued'?'原版目前待发；保存新文字会把它退回草稿。':'保存后仍是本机草稿。'}不会联系主机。</p>
    {held?<><h4>将保存在手机的新文字</h4><pre className="pw-file-revision-content">{held.body}</pre>
      <details><summary>对照原稿 v{row.revision}</summary><pre className="pw-file-revision-content">{original}</pre></details>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={()=>hold(null)}>继续编辑</Button><Button disabled={busy} onClick={()=>void act(true)}>只保存新版本，不排队</Button></div></>:
      <><TextAreaField label="修改后的说明" value={text} isDisabled={busy} onChange={v=>{if(v.length<=12000){edit(v);fail('')}}} hint="改动仅留在本页，核对并保存后才会替换原版本。"/>
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={onCancel}>放弃本次编辑</Button>
          <Button disabled={busy||!text.trim()||text===original} onClick={()=>void act(false)}>核对新旧文字</Button></div></>}
    {error&&<p role="alert">{error} 本页输入保留；如果未收到保存回执，请先重读本机原稿。</p>}
  </>;
}
