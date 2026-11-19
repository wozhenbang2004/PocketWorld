import {bridge} from './bridge';
import {Button} from './forms-library';
import {isPrivateLocalPhone} from './PhonePauseInstruction';
import {messagePhoneResult,phoneConversationId,phoneRunIdentity} from './phone-start';

type Row=Record<string,any>;
type PhoneView={taskId?:string|null;section?:string|null};
export type PhoneConversationOrigin={taskId:string;runId:string;epoch:number;conversationId:string};
const returnedStatuses=new Set(['completed','needs_confirmation','awaiting_user','failed','cancelled','interrupted','reconciling','paused']);
function source(task:Row|undefined):PhoneConversationOrigin|null{
  if(!task||!isPrivateLocalPhone(task)||!returnedStatuses.has(task.status))return null;
  const conversationId=phoneConversationId(task),id=phoneRunIdentity(task);
  return conversationId&&id?{...id,conversationId}:null;
}
export function phoneSourceStillOpen(t:PhoneConversationOrigin){
  const {state,view}=bridge.getState();
  if(document.hidden||(view as PhoneView|undefined)?.taskId!==t.taskId||state.chatSubmitting||state.conversationReader||!Array.isArray(state.tasks))return false;
  const s=source(state.tasks.find(row=>row?.id===t.taskId));
  return !!s&&s.runId===t.runId&&s.epoch===t.epoch&&s.conversationId===t.conversationId;
}
export function PhoneTaskConversationReturn({task,onRead}:{task:Row;onRead:(origin:PhoneConversationOrigin)=>void}){
  const target=source(task);if(!target)return null;
  return <div className="pw-action-row"><Button variant="ghost" size="medium" className="h-auto min-h-11 max-w-full whitespace-normal"
    onClick={()=>onRead(target)}>去原私聊核对第 {target.epoch} 轮回执</Button></div>;
}

type Location='found'|'absent'|'not_mounted'|'left';
/** Locate only a pre-existing authentic message in the original visible conversation.
 * Wait briefly for React's row, not for a future delivery. Never select a different message or a device. */
export function locatePhoneReturn(t:PhoneConversationOrigin,list:()=>HTMLElement|null,current:()=>boolean,
  stopFollowing:()=>void,save:()=>void):Promise<Location>{
  const active=()=>{const s=bridge.getState(),view=s.view as PhoneView|undefined;return current()&&!document.hidden&&!view?.section&&!view?.taskId&&
    s.state.conversationId===t.conversationId&&!s.state.conversationReader};
  const marker=()=>{
    const rows=bridge.getState().state.messages;if(!Array.isArray(rows))return null;
    const matches=rows.filter(m=>{if(!m?.id||m.sourceRevision!==1||m.taskId!==t.taskId)return false;
      const r=messagePhoneResult(m);return r?.runId===t.runId&&r.epoch===t.epoch});
    return matches.length===1&&typeof matches[0].id==='string'?matches[0].id:null;
  };
  if(!active())return Promise.resolve('left');
  const id=marker();if(!id)return Promise.resolve('absent');
  return new Promise(resolve=>{
    let frame=0,done=false;let observer:MutationObserver|undefined,observed:HTMLElement|null=null;
    const finish=(outcome:Location)=>{if(done)return;done=true;clearTimeout(timeout);cancelAnimationFrame(frame);observer?.disconnect();
      document.removeEventListener('visibilitychange',leave);for(const type of inputs)document.removeEventListener(type,leave,true);resolve(outcome)};
    const leave=()=>finish('left'),inputs=['pointerdown','touchstart','wheel','keydown'];
    const timeout=setTimeout(()=>finish(active()?'not_mounted':'left'),1100);
    const check=()=>{
      if(done)return;if(!active()){finish('left');return}if(marker()!==id){finish('absent');return}
      const container=list(),scroll=container?.closest<HTMLElement>('[data-conversation-id]');
      if(!container?.isConnected||!scroll||scroll.dataset.conversationId!==t.conversationId||container.closest('[inert],[aria-hidden="true"]')||!scroll.clientHeight){frame=requestAnimationFrame(check);return}
      const matches=Array.from(container.querySelectorAll<HTMLElement>('[data-message-id]')).filter(n=>n.dataset.messageId===id);
      if(matches.length===1){
        const row=matches[0],inset=parseFloat(getComputedStyle(scroll).scrollPaddingTop)||0;
        stopFollowing();row.focus({preventScroll:true});scroll.scrollTo({top:scroll.scrollTop+row.getBoundingClientRect().top-scroll.getBoundingClientRect().top-scroll.clientTop-inset,behavior:'instant'});
        save();finish('found');return
      }
      if(observed!==container){observer?.disconnect();observed=container;observer=new MutationObserver(check);observer.observe(container,{childList:true,subtree:true})}
    };
    document.addEventListener('visibilitychange',leave);for(const type of inputs)document.addEventListener(type,leave,true);
    frame=requestAnimationFrame(check);
  });
}
