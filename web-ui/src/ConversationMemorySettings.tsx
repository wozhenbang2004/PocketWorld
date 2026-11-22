import {useCallback,useEffect,useRef,useState} from 'react';
import {Button,SettingsDialog,StretchSwitch} from './forms-library';
import {ShimmerText} from './library';
import {bridge} from './bridge';
import {ConversationReferenceShelf} from './ConversationReferenceShelf';

export type ConversationMemoryControls={conversationId:string;enabled:boolean;excludeNewInputs?:boolean;revision:number};
function controlsOf(value:unknown,conversationId:string):ConversationMemoryControls {
  const row=value as Partial<ConversationMemoryControls>;
  if(!row||row.conversationId!==conversationId||typeof row.enabled!=='boolean'||('excludeNewInputs'in row&&typeof row.excludeNewInputs!=='boolean')||!Number.isSafeInteger(row.revision)||Number(row.revision)<0)throw new Error('没有收到此对话的真实记忆设置，请重新读取。');
  return row as ConversationMemoryControls;
}

/** Independent, owner-reviewed choices on the same original header. This sheet owns no saved mode. */
export function ConversationMemorySettings({conversationId,controls,composer,isOpen,onClose,onChanged,onManage,registerBack,showReferenceShelf=true}:{
  conversationId:string;controls:ConversationMemoryControls|null;composer?:Record<string,any>;isOpen:boolean;
  onClose:()=>void;onChanged:(value:ConversationMemoryControls|null)=>void;onManage:()=>void;
  registerBack:(handler:(()=>boolean)|null)=>void;showReferenceShelf?:boolean;
}) {
  const [pending,setPending]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const [shelf,setShelf]=useState<string|null>(null);
  const shelfVisit=JSON.stringify([conversationId,composer?.key,composer?.epoch,bridge.getDocumentEpoch()]);
  const request=useRef(0),alive=useRef(true),writing=useRef(false);
  const load=useCallback(async()=>{
    if(!conversationId||writing.current)return;
    const serial=++request.current;setPending(true);setError('');setNotice('');
    try {const result=controlsOf(await bridge.command('profile.conversation.read',{conversationId}),conversationId);if(alive.current&&serial===request.current)onChanged(result);}
    catch(failure){if(alive.current&&serial===request.current){onChanged(null);setError(failure instanceof Error?failure.message:String(failure));}}
    finally{if(alive.current&&serial===request.current)setPending(false);}
  },[conversationId,onChanged]);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;request.current++;};},[]);
  useEffect(()=>{void load();},[load]);
  useEffect(()=>{if(isOpen)void load();},[isOpen,load]);
  const close=useCallback(()=>{if(!writing.current)onClose();},[onClose]);
  useEffect(()=>{if(!isOpen)setShelf(null);},[isOpen,conversationId]);
  useEffect(()=>{registerBack(isOpen?()=>{if(shelf)setShelf(null);else close();return true;}:null);return()=>registerBack(null);},[isOpen,shelf,close,registerBack]);
  const save=async(field:'enabled'|'excludeNewInputs',value:boolean)=>{
    if(!controls||writing.current)return;writing.current=true;const serial=++request.current;
    setPending(true);setError('');setNotice('');
    try {
      const receipt=await bridge.command('profile.conversation.save',{conversationId,expectedRevision:controls.revision,[field]:value});
      if(receipt.saved!==true||receipt.persisted!==true)throw new Error('保存结果尚未确认，请读取当前设置；没有自动重发。');
      const result=controlsOf(receipt,conversationId);
      const other=field==='enabled'?'excludeNewInputs':'enabled';
      if(result.revision!==controls.revision+1||result[field]!==value||result[other]!==controls[other])throw new Error('设置版本已变化，请读取当前状态。');
      if(alive.current&&serial===request.current){onChanged(result);setNotice(field==='enabled'?(value?'已解除本对话的暂停，未改变总设置或新原话的选择。':'已暂停本对话的个人记忆。'):
        value?'从现在起，本对话新发的原话不会作为自动整理来源。':'新原话回到原有整理规则。以前已排除的原话仍然排除。');}
    }catch(failure){if(alive.current&&serial===request.current){onChanged(null);setError(failure instanceof Error?failure.message:String(failure));}}
    finally{writing.current=false;if(alive.current&&serial===request.current)setPending(false);}
  };
  const single=composer?.conversationId===conversationId&&composer.key==='conversation:'+conversationId?composer.oneMessageLearning:null;
  const setSingle=async(excluded:boolean)=>{
    if(!isOpen||writing.current||!composer||!single||single.pending||single.saving)return;
    const target={key:composer.key,epoch:composer.epoch,revision:composer.revision};
    const expected=target.revision+(single.excluded===excluded?0:1);
    const doc=bridge.getDocumentEpoch(),serial=++request.current;writing.current=true;setPending(true);setError('');setNotice('');
    try{
      const result=await bridge.command('composer.oneLearning.save',{...target,excluded});
      if(!alive.current||serial!==request.current||doc!==bridge.getDocumentEpoch())return;
      if(result.key!==target.key||result.epoch!==target.epoch||result.revision!==expected||result.excluded!==excluded||result.persisted!==true)throw new Error('原草稿的单条选择尚未确认保存，请核对后再发送。');
      setNotice(excluded?'已保存在原草稿。本条私聊输入将排除自动整理。':'已撤销单条限制，这份草稿跟随本对话的现有规则。');
    }catch(failure){if(alive.current&&serial===request.current)setError(failure instanceof Error?failure.message:String(failure));}
    finally{writing.current=false;if(alive.current&&serial===request.current)setPending(false);}
  };
  const canShelf=!!single&&!!composer&&Number.isSafeInteger(composer.epoch)&&['list','read','remove','close'].every(name=>bridge.getState().capabilities.commands.includes('messageShelf.'+name));
  if(isOpen&&shelf===shelfVisit&&canShelf&&composer)return <ConversationReferenceShelf key={conversationId+':'+composer.epoch} conversationId={conversationId} draftKey={composer.key} epoch={composer.epoch} onClose={()=>setShelf(null)} onReturnDraft={()=>{setShelf(null);close();}}/>;
  return <SettingsDialog isOpen={isOpen} title="本对话的个人记忆" onClose={close}>
    <p className="pw-muted">这里只显示对话设置，不是这次请求的已选记忆。对应回答若有「本次记忆」，可在那里核对实际提供的条目。</p>
    {pending&&!controls&&<ShimmerText>正在读取此对话设置…</ShimmerText>}
    {error&&<p className="pw-error" role="alert">{error}</p>}
    {controls&&<StretchSwitch label="允许此对话参考我的资料、偏好和记忆" description="关闭后，此对话不引用个人资料与记忆，也不自动整理记忆候选。开启只解除本对话的暂停，不改变总设置。" checked={controls.enabled} disabled={pending} busy={pending} onChange={enabled=>{void save('enabled',enabled);}}/>}
    <p className="pw-muted">关闭后，后续请求不再带上过去查询或修改默认偏好时读到的设置值。你自己写的消息仍会保留，也仍可用于对话。已经发出的请求不能撤回。明确选用的项目资料保持原范围。</p>
    {typeof controls?.excludeNewInputs==='boolean'&&<StretchSwitch label="从现在起，新原话不参与自动整理" description={controls.enabled?
      '适合临时项目：仍可按原规则参考已有偏好，但此对话之后发的原话不用于后台提炼或记忆建议。关闭不会补整理，既有记忆和手动设置不变。':
      '整段暂停时新原话已经排除。打开此项，可在以后恢复个人记忆时继续排除新原话。'} checked={controls.excludeNewInputs} disabled={pending} busy={pending} onChange={v=>{void save('excludeNewInputs',v);}}/>}
    {single&&(controls?.excludeNewInputs!==true||single.excluded||single.pending||single.confirmed===false)&&<><StretchSwitch label="只让下一条不参与自动整理" description="这条原话不作为后台提炼或对话记忆建议的来源；发送前可撤销，受理后用完。原话仍留在对话，不关闭已有偏好，也不撤回另外保存的内容。" checked={single.excluded===true} disabled={pending||single.saving||single.pending} busy={single.saving} onChange={value=>{void setSingle(value);}}/>
      {single.pending&&single.excluded&&<p className="pw-muted">正在核对原输入，未把单条限制挪给另一条消息。</p>}
      {single.confirmed===false&&!single.saving&&!single.pending&&<Button variant="secondary" disabled={pending} onClick={()=>void setSingle(single.excluded===true)}>重新保存本条选择</Button>}</>}
    {showReferenceShelf&&canShelf&&<Button variant="secondary" disabled={pending||single.pending} onClick={()=>setShelf(shelfVisit)}>本对话备忘</Button>}
    {notice&&<p role="status">{notice}</p>}
    <div className="pw-action-row"><Button variant="ghost" disabled={pending} onClick={()=>{void load();}}>读取当前设置</Button><Button variant="ghost" disabled={pending} onClick={()=>{close();onManage();}}>管理我的记忆</Button></div>
  </SettingsDialog>;
}
