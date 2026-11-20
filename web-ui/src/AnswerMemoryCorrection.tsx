import {useEffect,useRef,useState,type MutableRefObject} from 'react';
import {bridge} from './bridge';
import {Button,CheckboxField,SettingsDialog,TextAreaField} from './forms-library';

type Data=Record<string,any>;
const keys=['taskId','runId','requestId','answerId','receiptId'] as const;
const personal=(row:Data)=>row.ownerId==='owner'&&row.spaceId==='private';
export function canCorrectProvided(receipt:Data|null,row:Data|null):boolean {
  return !!receipt&&!!row&&receipt.phase==='provided_to_request'&&['current','invalidated'].includes(receipt.status)&&
    receipt.modelUseVerified===false&&receipt.executionAuthorized===false&&row.personalOwnerReview===true&&!receipt.memoryNamespace&&personal(receipt)&&personal(row)&&
    Number.isSafeInteger(receipt.answerReadExpiresAt)&&receipt.answerReadExpiresAt>Date.now()&&
    keys.every(key=>typeof receipt[key]==='string'&&receipt[key])&&row.status==='current'&&typeof row.readHandle==='string'&&!!row.readHandle&&
    typeof row.id==='string'&&!!row.id&&Number.isSafeInteger(row.revision)&&row.revision>0&&typeof row.text==='string'&&!!row.text;
}
const closePin=(handle:string)=>{if(handle)void bridge.command('memoryHistory.close',{handle}).catch(()=>{})};

export type AnswerMemoryCorrectionOutcome = {kind:'saved'; revision:number; feedback:string} |
  {kind:'not_submitted'|'unconfirmed'; message:string};

/** An owner's correction of one proven record, never a retry of the answer or a model mutation. */
export function AnswerMemoryCorrection({receipt,row,backRef,onClose,onSaving,onOutcome}:{receipt:Data;row:Data;
  backRef:MutableRefObject<(()=>boolean)|null>;onClose:()=>void;onSaving:()=>void;onOutcome:(outcome:AnswerMemoryCorrectionOutcome)=>void}) {
  const [draft,setDraft]=useState(String(row.text)),[review,setReview]=useState<Data|null>(null),[ack,setAck]=useState(false);
  const [busy,setBusy]=useState(false),[leaving,setLeaving]=useState(false);
  const live=useRef(true),pending=useRef(false),sent=useRef(false),pin=useRef('');
  const dropReview=()=>{closePin(pin.current);pin.current='';setReview(null);setAck(false)};
  const back=()=>{if(pending.current||sent.current)return true;if(leaving)setLeaving(false);else if(review)dropReview();
    else if(draft!==row.text)setLeaving(true);else onClose();return true};
  useEffect(()=>{backRef.current=back;return()=>{backRef.current=null}});
  useEffect(()=>{live.current=true;return()=>{live.current=false;closePin(pin.current);pin.current=''}},[]);
  const propose=async()=>{
    const text=draft.trim();if(!canCorrectProvided(receipt,row)||!text||text.length>4000||text===row.text.trim()||pending.current||sent.current)return;
    pending.current=true;setBusy(true);
    try{
      const value=await bridge.command('taskMemory.review',{readHandle:row.readHandle,operation:'correct',text});
      const handle=typeof value?.reviewHandle==='string'?value.reviewHandle:'';
      if(!live.current){closePin(handle);return}
      if(!handle||value.operation!=='correct'||value.executionAuthorized!==false||value.before?.id!==row.id||value.before.revision!==row.revision||
        value.before.text!==row.text||!personal(value.before)||value.afterText!==text){closePin(handle);throw new Error('原记忆或核对范围已变化，没有提交纠正。')}
      pin.current=handle;setReview(value);setAck(false);
    }catch{if(live.current)onOutcome({kind:'not_submitted',message:'原回答或记忆未能核对，没有提交纠正。请重新核对原回执。'})}
    finally{pending.current=false;if(live.current)setBusy(false)}
  };
  const confirm=async()=>{
    const handle=pin.current;if(!handle||!review||review.reviewHandle!==handle||!ack||pending.current||sent.current)return;
    pending.current=true;sent.current=true;pin.current='';setBusy(true);onSaving();
    try{
      const result=await bridge.command('taskMemory.confirm',{reviewHandle:handle}),record=result?.record;
      if(!record||!personal(record)||record.id!==row.id||!Number.isSafeInteger(record.revision)||record.revision!==row.revision+1||record.text!==review.afterText||result.executionAuthorized!==false||
        !keys.every(key=>result[key]===receipt[key])||typeof result.feedback!=='string'||!result.feedback)throw new Error('没有收到原纠正的完整回执');
      if(live.current)onOutcome({kind:'saved',revision:record.revision,feedback:result.feedback});
    }catch{if(live.current)onOutcome({kind:'unconfirmed',message:'未收到可确认的保存回执，没有重发。可在记忆管理查看目前保存的版本。'})}
    finally{pending.current=false;if(live.current)setBusy(false)}
  };
  return <SettingsDialog isOpen onClose={back} title={sent.current?'正在确认保存':leaving?'放弃未提交的改动？':review?'核对这次纠正':'纠正这条已保存记忆'} className="pw-memory-modal">
    {sent.current?<p className="pw-muted" role="status">正在等待这次保存回执，不会重发。</p>:leaving?<div className="pw-form-actions"><Button onClick={()=>setLeaving(false)}>继续编辑</Button><Button variant="ghost" onClick={onClose}>放弃并返回</Button></div>:
      <div className="pw-knowledge-review">{review?<><p>先前保存</p><p className="pw-reading-copy">{row.text}</p><p>改为</p><p className="pw-reading-copy">{review.afterText}</p>
        <p className="pw-muted">会更新保存的记忆，并清理旧版本的派生引用。原对话和这条回答不会被改写或重跑。</p>
        <CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>我已核对这次内容与影响</CheckboxField></>:
        <><p className="pw-muted">只改这条已保存记忆，不会让模型重新作答。</p><TextAreaField label="纠正后的内容" value={draft} onChange={setDraft} rows={5} maxLength={4000} isDisabled={busy}/></>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={back}>返回{review?'编辑':'原条目'}</Button>
        <Button disabled={busy||sent.current||(review?!ack:!draft.trim()||draft.trim()===row.text.trim())} onClick={()=>void(review?confirm():propose())}>{busy?'正在核对…':review?'确认这一次纠正':'核对纠正内容'}</Button></div></div>}
  </SettingsDialog>;
}
