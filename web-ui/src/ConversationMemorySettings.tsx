import {useCallback,useEffect,useRef,useState} from 'react';
import {Button,SettingsDialog,StretchSwitch} from './forms-library';
import {ShimmerText} from './library';
import {bridge} from './bridge';

type Controls={conversationId:string;enabled:boolean;revision:number};
function controlsOf(value:unknown,conversationId:string):Controls {
  const row=value as Partial<Controls>;
  if(!row||row.conversationId!==conversationId||typeof row.enabled!=='boolean'||!Number.isSafeInteger(row.revision)||Number(row.revision)<0)throw new Error('没有收到此对话的真实记忆设置，请重新读取。');
  return row as Controls;
}

/** The same conversation header controls context and learning; this sheet owns no saved mode. */
export function ConversationMemorySettings({conversationId,isOpen,onClose,onChanged,onManage,registerBack}:{
  conversationId:string;isOpen:boolean;onClose:()=>void;onChanged:(value:Controls)=>void;onManage:()=>void;
  registerBack:(handler:(()=>boolean)|null)=>void;
}) {
  const [controls,setControls]=useState<Controls|null>(null),[pending,setPending]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const request=useRef(0),alive=useRef(true),writing=useRef(false);
  const changed=useRef(onChanged);changed.current=onChanged;
  const load=useCallback(async()=>{
    if(!conversationId||writing.current)return;
    const serial=++request.current;setPending(true);setError('');
    try {const result=controlsOf(await bridge.command('profile.conversation.read',{conversationId}),conversationId);if(alive.current&&serial===request.current){setControls(result);changed.current(result);}}
    catch(failure){if(alive.current&&serial===request.current){setControls(null);setError(failure instanceof Error?failure.message:String(failure));}}
    finally{if(alive.current&&serial===request.current)setPending(false);}
  },[conversationId]);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;request.current++;};},[]);
  useEffect(()=>{setControls(null);setNotice('');if(conversationId)void load();},[conversationId,load]);
  useEffect(()=>{if(isOpen)void load();},[isOpen,load]);
  const close=useCallback(()=>{if(!writing.current)onClose();},[onClose]);
  useEffect(()=>{registerBack(isOpen?()=>{close();return true;}:null);return()=>registerBack(null);},[isOpen,close,registerBack]);
  const save=async(enabled:boolean)=>{
    if(!controls||writing.current)return;writing.current=true;const serial=++request.current;
    setPending(true);setError('');setNotice('');
    try {
      const receipt=await bridge.command('profile.conversation.save',{conversationId,expectedRevision:controls.revision,enabled});
      if(receipt.saved!==true||receipt.persisted!==true)throw new Error('保存结果尚未确认，请读取当前设置；没有自动重发。');
      const result=controlsOf(receipt,conversationId);
      if(result.revision!==controls.revision+1||result.enabled!==enabled)throw new Error('设置版本已变化，请读取当前状态。');
      if(alive.current&&serial===request.current){setControls(result);changed.current(result);setNotice(enabled?'本对话已开启个人记忆。':'本对话已关闭个人记忆。下一次模型请求会核对新设置。');}
    }catch(failure){if(alive.current&&serial===request.current){setControls(null);setError(failure instanceof Error?failure.message:String(failure));}}
    finally{writing.current=false;if(alive.current&&serial===request.current)setPending(false);}
  };
  return <SettingsDialog isOpen={isOpen} title="本对话的个人记忆" onClose={close}>
    {pending&&!controls&&<ShimmerText>正在读取此对话设置…</ShimmerText>}
    {error&&<p className="pw-error" role="alert">{error}</p>}
    {controls&&<StretchSwitch label="使用我的资料、偏好和记忆" description="关闭后，此对话不引用个人资料与记忆，也不自动整理记忆候选。" checked={controls.enabled} disabled={pending} busy={pending} onChange={enabled=>{void save(enabled);}}/>}
    <p className="pw-muted">对话消息仍按正常方式保存。已发出的请求不能撤回；新设置在后续请求中生效。项目明确选用的资料保持原范围。</p>
    {notice&&<p role="status">{notice}</p>}
    <div className="pw-action-row"><Button variant="ghost" disabled={pending} onClick={()=>{void load();}}>读取当前设置</Button><Button variant="ghost" disabled={pending} onClick={()=>{close();onManage();}}>管理我的记忆</Button></div>
  </SettingsDialog>;
}
