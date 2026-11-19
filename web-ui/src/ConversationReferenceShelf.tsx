import {useCallback,useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SettingsDialog} from './forms-library';
import {ShimmerText} from './library';

type Entry={id:string;label:string;createdAt:number;chars:number};
type Page={entries:Entry[];window:string};
type Preview={id:string;text:string;expires:number;token:string;revision:number};
function row(value:Partial<Entry>):value is Entry{
  return !!value&&typeof value.id==='string'&&value.id.length>0&&typeof value.label==='string'&&value.label.length<=60&&
    typeof value.createdAt==='number'&&Number.isSafeInteger(value.createdAt)&&value.createdAt>0&&typeof value.chars==='number'&&Number.isInteger(value.chars)&&value.chars>=1&&value.chars<=4000;
}
const when=(n:number)=>new Date(n).toLocaleString(undefined,{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});

/** No polling, stored original text or global lookup. The native list issues this view's only row handles. */
export function ConversationReferenceShelf({conversationId,draftKey,epoch,onClose,onReturnDraft}:{conversationId:string;draftKey:string;epoch:number;onClose:()=>void;onReturnDraft:()=>void}){
  const [page,setPage]=useState<Page|null>(null),[preview,setPreview]=useState<Preview|null>(null),[removing,setRemoving]=useState(''),[busy,setBusy]=useState(true),
    [error,setError]=useState(''),[notice,setNotice]=useState('');
  const [stageStatus,setStageStatus]=useState<''|'busy'|'saved'|'unknown'>('');
  const consumed=useRef(''),expectedEdit=useRef<number|null>(null),supportsDraft=bridge.getState().capabilities.commands.includes('messageShelf.stage');
  const alive=useRef(false),serial=useRef(0),windowId=useRef(''),reading=useRef(false),close=useRef(onClose);close.current=onClose;
  const closeWindow=useCallback(()=>{const id=windowId.current;windowId.current='';if(id)void bridge.command('messageShelf.close',{shelfWindow:id}).catch(()=>{});},[]);
  const load=useCallback(async()=>{
    if(!alive.current||reading.current)return;
    const ticket=++serial.current;reading.current=true;setBusy(true);setPreview(null);setRemoving('');setPage(null);setError('');setNotice('');
    try{
      const res=await bridge.command('messageShelf.list',{conversationId,key:draftKey,epoch});
      if(!alive.current||serial.current!==ticket){if(typeof res?.shelfWindow==='string')void bridge.command('messageShelf.close',{shelfWindow:res.shelfWindow}).catch(()=>{});return;}
      if(typeof res?.shelfWindow==='string'&&res.shelfWindow)windowId.current=res.shelfWindow;
      if(!windowId.current||res.conversationId!==conversationId||res.bodyIncluded!==false||res.limit!==24||!Array.isArray(res.entries)||res.entries.length>24||
        !res.entries.every(row)||new Set(res.entries.map((v:Entry)=>v.id)).size!==res.entries.length)throw new Error('原对话备忘列表未能完整核对。');
      setPage({window:windowId.current,entries:res.entries});
    }catch(e){if(alive.current&&serial.current===ticket){closeWindow();setPage(null);setError(e instanceof Error?e.message:String(e));}}
    finally{if(alive.current&&serial.current===ticket){setBusy(false);reading.current=false;}}
  },[conversationId,draftKey,epoch,closeWindow]);
  useEffect(()=>{
    alive.current=true;const doc=bridge.getDocumentEpoch(),draft=bridge.getState().composer?.revision;
    const original=()=>{const s=bridge.getState(),v=s.view as {section?:string;taskId?:string}|undefined;return document.visibilityState!=='hidden'&&doc===bridge.getDocumentEpoch()&&s.host?.connected!==false&&
      !v?.section&&!v?.taskId&&!s.state.chatSubmitting&&s.state.conversationId===conversationId&&s.composer?.key===draftKey&&s.composer?.epoch===epoch&&!!s.composer?.oneMessageLearning&&
      (s.composer.revision===draft||(expectedEdit.current===draft&&s.composer.revision===draft+1));};
    // Clear retained display when any visible original version, source management or host visit changes.
    const stamp=()=>{const s=bridge.getState();return JSON.stringify([(s.view as {resumeRevision?:number}|undefined)?.resumeRevision,s.state.memoryRefreshRevision,
      (Array.isArray(s.state.messages)?s.state.messages:[]).map((m:{id?:string;sourceRevision?:number})=>[m.id,m.sourceRevision])]);};
    const visit=stamp();
    const leave=()=>{if(!alive.current)return;alive.current=false;reading.current=false;serial.current++;setPreview(null);setPage(null);setRemoving('');closeWindow();close.current();};
    const changed=()=>{if(!original()||visit!==stamp())leave();};
    const off=bridge.subscribe(changed);
    document.addEventListener('visibilitychange',changed);window.addEventListener('pagehide',leave);
    if(original())void load();else leave();
    return()=>{alive.current=false;reading.current=false;serial.current++;off();document.removeEventListener('visibilitychange',changed);window.removeEventListener('pagehide',leave);closeWindow();};
  },[conversationId,draftKey,epoch,load,closeWindow]);
  useEffect(()=>{if(!preview)return;const timer=window.setTimeout(()=>{setPreview(null);setNotice('原文预览已到期。需要时可重新核对这一段。');},Math.max(0,preview.expires-Date.now()));return()=>{window.clearTimeout(timer);const id=windowId.current;if(id&&preview.token&&consumed.current!==preview.token)void bridge.command('messageShelf.dismiss',{shelfWindow:id,readWindow:preview.token}).catch(()=>{});};},[preview]);
  const select=async(entry:Entry,remove=false)=>{
    if(!alive.current||reading.current||!page||!page.entries.some(v=>v.id===entry.id))return;
    const ticket=++serial.current;reading.current=true;setBusy(true);setPreview(null);setRemoving('');setError('');setNotice('');
    try{
      const r=await bridge.command(remove?'messageShelf.remove':'messageShelf.read',{shelfWindow:page.window,id:entry.id});
      if(!alive.current||serial.current!==ticket)return;
      if(r?.shelfWindow!==page.window||r.id!==entry.id||r.conversationId!==conversationId)throw new Error('这次回执不属于当前备忘。');
      if(remove){if(r.removed!==true)throw new Error('未确认这条备忘已移除。');setPage({...page,entries:page.entries.filter(v=>v.id!==entry.id)});setNotice('已从本对话备忘移除。原消息没有删除。');}
      else{if(r.formalMemorySaved!==false||r.modelCalled!==false||r.chars!==entry.chars||typeof r.text!=='string'||r.text.length!==entry.chars||
        !Number.isSafeInteger(r.readExpiresAt)||r.readExpiresAt<=Date.now()||r.readExpiresAt>Date.now()+125000)throw new Error('原话已过期或不能按原范围核对。');
        if(supportsDraft&&(typeof r.readWindow!=='string'||!r.readWindow||!Number.isSafeInteger(r.draftRevision)||r.draftRevision!==bridge.getState().composer?.revision))throw new Error('原草稿或阅读证明已变化，请重新核对原话。');
        setPreview({id:entry.id,text:r.text,expires:r.readExpiresAt,token:supportsDraft?r.readWindow:'',revision:supportsDraft?r.draftRevision:-1});}
    }catch(e){if(alive.current&&serial.current===ticket)setError(e instanceof Error?e.message:String(e));}
    finally{if(alive.current&&serial.current===ticket){reading.current=false;setBusy(false);}}
  };
  const stage=async()=>{
    if(!page||!preview?.token||!alive.current||reading.current||preview.expires<=Date.now()||!supportsDraft||stageStatus)return;
    const ticket=++serial.current,selected=preview,window=page.window;
    reading.current=true;consumed.current=selected.token;expectedEdit.current=selected.revision;setBusy(true);setError('');setNotice('');setStageStatus('busy');
    // Post the affirmative action before hiding the preview; its later dismissal cannot start or repeat a stage.
    const pending=bridge.command('messageShelf.stage',{shelfWindow:window,readWindow:selected.token});setPreview(null);
    try{
      const result=await pending;if(!alive.current||serial.current!==ticket)return;
      if(result?.shelfWindow!==window||result.readWindow!==selected.token||result.key!==draftKey||result.epoch!==epoch)throw new Error('回执不属于打开的原话或原草稿。');
      const existing=result.alreadyInDraft===true&&result.changed===false&&result.revision===selected.revision;
      if(!existing&&(result.selectionValidated!==true||result.persisted!==true||result.draftStaged!==true||result.fromRevision!==selected.revision||result.revision!==selected.revision+1))
        throw new Error('没有收到原草稿的完整保存回执。');
      windowId.current='';setPage(null);setStageStatus('saved');setNotice(existing?'这段已经在原草稿里。本次没有改写或发送。':'已加入这次草稿，尚未发送。发送前仍可移除。');
    }catch(e){if(alive.current&&serial.current===ticket){closeWindow();setPage(null);setStageStatus('unknown');setError((e instanceof Error?e.message:String(e))+' 请先核对原草稿，未自动重试。');}}
    finally{if(alive.current&&serial.current===ticket){reading.current=false;setBusy(false);}}
  };
  return <SettingsDialog isOpen title="本对话备忘" onClose={onClose}>
    <p>只列出你从这段私聊亲自保留的原话位置。保留不会把它加入请求或个人记忆；原消息仍按对话本身的规则处理。</p>
    {busy&&!page&&!stageStatus&&<ShimmerText>正在读取这段对话的备忘…</ShimmerText>}
    {page&&!page.entries.length&&<p className="pw-muted">这里还没有备忘。在你发过的消息中打开“引用”，选中原话，再选“留在本对话”。</p>}
    {page&&page.entries.map((item,index)=><section key={item.id} aria-label={item.label||'对话备忘 '+(index+1)}>
      <strong>{item.label||when(item.createdAt)+' 的原话'}</strong><p className="pw-muted">{item.label?when(item.createdAt)+' · ':''}{item.chars} 个字符</p>
      {preview?.id===item.id&&<><blockquote className="pw-source-excerpt" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{preview.text}</blockquote>
        {preview.token&&<><p className="pw-muted">仅加入本次草稿；只有你之后发送，这次请求才会按原规则核对并尝试引用。</p><Button disabled={busy||!!stageStatus} onClick={()=>void stage()}>加入这次草稿</Button></>}</>}
      {removing===item.id?<><p>只移除这个原话位置。已独立加入的草稿或发出的请求不受影响。</p><div className="pw-action-row"><Button variant="secondary" disabled={busy} onClick={()=>void select(item,true)}>确认移除</Button><Button variant="ghost" onClick={()=>setRemoving('')}>取消</Button></div></>:
        <div className="pw-action-row"><Button variant="secondary" disabled={busy} onClick={()=>preview?.id===item.id?setPreview(null):void select(item)}>{preview?.id===item.id?'收起原话':'查看原话'}</Button><Button variant="ghost" disabled={busy} onClick={()=>{setPreview(null);setError('');setNotice('');setRemoving(item.id);}}>移除</Button></div>}
    </section>)}
    {stageStatus==='busy'&&<p role="status">正在核对原选区并保存至原草稿…</p>}
    {error&&<p role="alert" className="pw-error">{error}</p>}{notice&&<p role="status">{notice}</p>}
    <div className="pw-form-actions">{(stageStatus==='saved'||stageStatus==='unknown')&&<Button onClick={onReturnDraft}>返回原草稿</Button>}{error&&!stageStatus&&<Button variant="secondary" disabled={busy} onClick={()=>void load()}>重新读取本对话备忘</Button>}<Button variant="ghost" onClick={onClose}>返回本对话设置</Button></div>
  </SettingsDialog>;
}
