import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog, StretchSwitch } from './forms-library';

type Props = { composer: Record<string, any>; allowSingle: boolean; isOpen: boolean; onClose: () => void; registerBack: (handler: (() => boolean) | null) => void };

/** These are original-draft restrictions, not a new conversation, a grant to read, or a send. */
export function NewConversationMemory({ composer, allowSingle, isOpen, onClose, registerBack }: Props) {
  const mode=composer.firstMessageMemory;
  const one=allowSingle?composer.oneMessageLearning:null;
  const [writing, setWriting] = useState(false), [notice, setNotice] = useState('');
  const live=useRef(false),generation=useRef(0),started=useRef(false),close=useRef(onClose);close.current=onClose;
  useEffect(()=>{
    if(!isOpen)return;generation.current++;live.current=true;setWriting(started.current);setNotice('');
    const doc=bridge.getDocumentEpoch(),key=composer.key,epoch=composer.epoch;
    const gone=()=>{if(live.current){live.current=false;close.current();}};
    const check=()=>{const s=bridge.getState();if(document.hidden||bridge.getDocumentEpoch()!==doc||s.composer?.key!==key||s.composer?.epoch!==epoch||s.state.conversationId)gone();};
    const off=bridge.subscribe(check);document.addEventListener('visibilitychange',check);window.addEventListener('pagehide',gone);check();
    return ()=>{live.current=false;off();document.removeEventListener('visibilitychange',check);window.removeEventListener('pagehide',gone);};
  },[isOpen,composer.key,composer.epoch,allowSingle]);
  useEffect(()=>{registerBack(isOpen?()=>{if(!started.current)close.current();return true;}:null);return()=>registerBack(null);},[isOpen,registerBack]);
  const save=async(field:'paused'|'excluded'|'protectDefaults',value:boolean)=>{
    const prior=field==='paused'?mode:one;
    if(!isOpen||!live.current||!prior||prior.saving||one?.pending||started.current)return;
    const scope={key:composer.key,epoch:composer.epoch,revision:composer.revision},doc=bridge.getDocumentEpoch(),opened=generation.current;
    const current=()=>live.current&&generation.current===opened&&!document.hidden&&bridge.getDocumentEpoch()===doc&&bridge.getState().composer?.key===scope.key&&bridge.getState().composer?.epoch===scope.epoch;
    started.current=true;setWriting(true);setNotice('');
    try{
      const payload=field==='protectDefaults'?{...scope,excluded:true,protectDefaults:value}:{...scope,[field]:value};
      const result=await bridge.command(field==='paused'?'composer.firstMemory.save':'composer.oneLearning.save',payload);
      if(!current())return;
      if(result?.key!==scope.key||result?.epoch!==scope.epoch||!Number.isSafeInteger(result.revision)||result.revision!==scope.revision+(prior[field]===value?0:1)||result.persisted!==true||result[field]!==value||field==='protectDefaults'&&result.excluded!==true)
        throw new Error('没有确认这份新草稿的记忆选择已保存。请核对后再发送。');
      setNotice(field==='paused'?(value?'已保存。这个对话从首条起暂停个人记忆。':'已恢复这个对话的默认规则，不改全局或单条选择。'):
        field==='protectDefaults'?(value?'已保存。发出后，原任务不能更改六项默认偏好。':'已撤销原任务的偏好写入限制；第一条仍不参与自动整理。'):
        value?'已保存。第一条不作为自动整理来源，尚未发送。':'已撤销第一条及其委托限制；不改整个对话的记忆设置。');
    }catch(e){if(current())setNotice(e instanceof Error?e.message:String(e));}
    finally{started.current=false;if(live.current)setWriting(false);}
  };
  const busy=writing||mode?.saving===true||one?.saving===true||one?.pending===true;
  return <SettingsDialog isOpen={isOpen} title="新对话的个人记忆" onClose={()=>{if(!started.current)onClose();}}>
    <p className="pw-muted">这里只显示草稿设置，不是已选记忆。发送后若回答有「本次记忆」，可在那里核对实际提供的条目。</p>
    {one&&<StretchSwitch label="只让第一条不参与自动整理" checked={one.excluded===true} busy={writing||one.saving} disabled={busy}
      description={one.protectDefaults===true?'原话仍保留。关闭此项也会撤销下方的原任务写入限制。':'原话仍保留，但不能作为自动整理的来源。已有偏好仍按原规则使用；原输入受理后，这个单条选择就用完。'} onChange={v=>{void save('excluded',v);}}/>}
    {one?.excluded===true&&typeof one.protectDefaults==='boolean'&&<StretchSwitch label="这项委托不能改默认偏好" checked={one.protectDefaults} busy={writing||one.saving} disabled={busy}
      description="适合“这次答简短些”。原任务不能改语言、详略、先给结论、举例、操作节奏或主动建议；仍按原规则参考已有偏好。你手动修改不受影响。" onChange={v=>{void save('protectDefaults',v);}}/>}
    {mode&&<StretchSwitch label="从第一条消息起暂停" checked={mode.paused===true} busy={writing||mode.saving} disabled={busy}
      description="整个新对话不引用个人资料与记忆，也不自动整理记忆候选。其他对话不变。" onChange={v=>{void save('paused',v);}}/>}
    <p className="pw-muted">选择只保存在这份草稿里，不会发送消息。第一条的选择只绑定原输入及其任务，不传给下一项独立委托。整段暂停优先。你自己写的原话和主动附上的资料不会因此被删除。</p>
    {one?.pending&&<p className="pw-muted">正在核对原输入是否受理。这项选择不会挪给新的消息。</p>}
    {(mode?.confirmed===false||one?.confirmed===false)&&!busy&&<p className="pw-error" role="alert">本机保存尚未确认，当前草稿不能发送。请先重新保存这份草稿的选择。</p>}
    {notice&&<p role="status">{notice}</p>}
    <div className="pw-form-actions">{(mode?.confirmed===false||one?.confirmed===false)&&!busy&&<Button variant="secondary" onClick={()=>{
        if(one?.confirmed===false){const strong=one.excluded===true&&typeof one.protectDefaults==='boolean';void save(strong?'protectDefaults':'excluded',strong?one.protectDefaults:one.excluded===true);}
        else if(mode)void save('paused',mode.paused===true);
      }}>重新保存草稿选择</Button>}
      <Button variant="ghost" disabled={writing} onClick={onClose}>返回草稿</Button></div>
  </SettingsDialog>;
}
