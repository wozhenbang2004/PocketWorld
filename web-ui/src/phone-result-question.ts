import {bridge} from './bridge';
import type {MessageQuoteTarget} from './MessageQuote';
import {originalPhoneReplyText} from './phone-reply-text';
import type {PhoneReportTarget} from './PhoneReturnedReport';

type Draft={key:string;epoch:number;revision:number;phoneEpoch:number;phoneRevision:number;phone:boolean};
const whole=(n:unknown):n is number=>typeof n==='number'&&Number.isSafeInteger(n)&&n>=0;
/** Read only the same editable private chat's saved receipt. Reader/history/notifications are not substitute draft destinations. */
export function discussionDraft(t:PhoneReportTarget,current:()=>boolean):Draft|null {
  const n=bridge.getState(),s=n.state,c=n.composer,p=n.phoneDraft as Record<string,any>|null,voice=s.voiceInput;
  if(t.readerWindow!==0||t.noticeOrigin||t.sourceRevision!==1||t.phoneResult.summaryChars<1||!current()||
    !['messageQuote.open','messageQuote.stage','messageQuote.close'].every(a=>n.capabilities.commands.includes(a))||
    c?.ready!==true||c.saving===true||s.chatSubmitting||s.phoneSubmitting||s.listening||s.quickPanelVoice||
    ['active','leaseHeld','resourceReleaseUnknown','reviewAvailable','staged'].some(k=>voice?.[k]===true)||
    c.conversationId!==t.conversationId||c.key!=='conversation:'+t.conversationId||!whole(c.epoch)||!whole(c.revision)||
    !p||typeof p.phone!=='boolean'||p.channelOrigin!==false||!whole(p.epoch)||!whole(p.revision)||
    p.phone&&(!n.capabilities.commands.includes('composer.intent')||!['none','current'].includes(p.sourceState)||p.firstCheckOnly===true||['pending','unknown'].includes(p.startReceipt?.status)))return null;
  if(!sourceMessage(t))return null;
  return {key:c.key,epoch:c.epoch,revision:c.revision,phoneEpoch:p.epoch,phoneRevision:p.revision,phone:p.phone};
}
function sourceMessage(t:PhoneReportTarget):boolean{
  const messages=bridge.getState().state.messages;
  const rows=Array.isArray(messages)?messages.filter(m=>m?.id===t.messageId):[],m=rows.length===1?rows[0]:null;
  if(!m||m.quoteAvailable!==true||m.role!=='assistant'||m.sourceRevision!==t.sourceRevision||m.taskId!==t.phoneResult.taskId||
    m.phoneResult?.deliveryId!==t.phoneResult.deliveryId||typeof m.content!=='string')return false;
  const text=originalPhoneReplyText(m.content,t.phoneResult.summaryTruncated);
  return !!text.trim()&&(t.phoneResult.summaryTruncated||text.length===t.phoneResult.summaryChars);
}
/** During the acknowledged draft write, saving and revision can legitimately change. Only leave it on a real destination/source change. */
export function questionStillHere(t:PhoneReportTarget,q:MessageQuoteTarget,current:()=>boolean):boolean{
  const n=bridge.getState(),c=n.composer,p=n.phoneDraft as Record<string,unknown>|null;
  return current()&&t.readerWindow===0&&!t.noticeOrigin&&q.messageId===t.messageId&&q.sourceRevision===t.sourceRevision&&c?.ready===true&&c.conversationId===t.conversationId&&
    c.key===q.key&&c.epoch===q.epoch&&p?.phone===false&&p.channelOrigin===false&&sourceMessage(t);
}
/** Called only from the explicit reader button. Changing composer mode is not a phone action;
 * wait for the real host's new epoch before opening the ordinary original-message quote window. */
export async function preparePhoneQuestion(t:PhoneReportTarget,current:()=>boolean):Promise<MessageQuoteTarget>{
  const before=discussionDraft(t,current);
  if(!before)throw Error('原回执或聊天草稿尚不可关联。');
  if(before.phone){
    await bridge.command('composer.intent',{phone:false,phoneResultDiscussion:{conversation:t.conversationId,message:t.messageId,
      delivery:t.phoneResult.deliveryId,draftEpoch:before.epoch,draftRevision:before.revision,phoneEpoch:before.phoneEpoch,phoneRevision:before.phoneRevision}});
  }
  const expected=before.epoch+(before.phone?1:0);
  const read=()=>{
    const d=discussionDraft(t,current);
    if(!d||d.key!==before.key||d.revision!==before.revision||d.phoneEpoch!==before.phoneEpoch||d.phoneRevision!==before.phoneRevision||
      d.epoch!==expected&&!(before.phone&&d.epoch===before.epoch&&d.phone))throw Error('原输入已变化，没有把回执交给另一份草稿。');
    return !d.phone&&d.epoch===expected?{messageId:t.messageId,sourceRevision:t.sourceRevision,key:d.key,epoch:d.epoch}:null;
  };
  const admitted=read();if(admitted)return admitted;
  return await new Promise<MessageQuoteTarget>((resolve,reject)=>{
    let done=false;const end=()=>{done=true;off();clearTimeout(timeout);document.removeEventListener('visibilitychange',check)};
    const check=()=>{if(done)return;try{const d=read();if(d){end();resolve(d)}}catch(e){end();reject(e)}};
    const off=bridge.subscribe(check),timeout=setTimeout(()=>{if(!done){end();reject(Error('还没有确认普通聊天输入。请核对原草稿；没有重提任务。'))}},1800);
    document.addEventListener('visibilitychange',check);check();
  });
}
