import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,TextAreaField} from './forms-library';

type Data=Record<string,any>;
type Target={key:string;epoch:number;revision:number};
const currentTarget=(id:string):Target|null=>{
 const live=bridge.getState(),state=live.state as Data,view=live.view as Data|undefined,phone=live.phoneDraft as Data|undefined,d=live.composer as Data|undefined;
 if(document.hidden||view?.section||view?.taskId||phone?.phone===true||!live.capabilities.commands.includes('conversation.projectConvention.stageFeedback')||
   !d||d.ready!==true||!state.conversationId||d.conversationId!==state.conversationId||d.key!=='conversation:'+state.conversationId||
   state.chatSubmitting||state.phoneSubmitting||state.listening||state.voiceInput?.active||d.saving||d.text!==''||d.voiceText||d.voiceInputId||!Number.isSafeInteger(d.epoch)||!Number.isSafeInteger(d.revision)||
   !Array.isArray(state.messages)||!state.messages.some((m:Data)=>m.id===id&&m.role==='assistant'&&m.projectConventionRecorded===true)||
   ![state.selectedChatAttachments,d.sourceRefs,d.previewRefs,d.objectRefs].every(v=>Array.isArray(v)&&v.length===0)||
   !Array.isArray(d.pending)||d.pending.some((v:Data)=>v.draftKey===d.key))return null;
 return {key:d.key,epoch:d.epoch,revision:d.revision};
};
const same=(a:Target,b:Target|null)=>!!b&&a.key===b.key&&a.epoch===b.epoch&&a.revision===b.revision;

/** Editing a reply and correcting the long-lived project note are deliberately different actions. */
export function ProjectAnswerAdjustmentDraft({messageId,quote,sourceRevision,answerAt,onBusy,onReady}:{messageId:string;quote:string;sourceRevision:number;answerAt:number;onBusy:(v:boolean)=>void;onReady:()=>void}){
 const [open,setOpen]=useState(false),[feedback,setFeedback]=useState(''),[pending,setPending]=useState(false),[info,setInfo]=useState('');
 const anchored=Number.isSafeInteger(answerAt)&&answerAt>0&&answerAt<253402300800000;
 const time=anchored?new Date(answerAt).toISOString().substring(0,19).replace('T',' ')+' UTC':'';
 const active=useRef(false),busy=useRef(false),attempted=useRef(false),origin=useRef<Target|null>(null);
 useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
 const present=()=>{const dest=currentTarget(messageId);setInfo('');if(!dest){setInfo('请先回原私人对话核对输入框。已有内容或待发送事项不会被覆盖。');return;}
   origin.current=dest;setOpen(true);};
 const save=async()=>{
   const target=origin.current,comment=feedback.trim();
   if(!active.current||busy.current||attempted.current||!target||!comment||comment.length>600)return;
   if(!same(target,currentTarget(messageId))){setInfo('原草稿已变化，没有覆盖。');setOpen(false);return;}
   busy.current=true;onBusy(true);attempted.current=true;setPending(true);setInfo('');
   try{const v=await bridge.command('conversation.projectConvention.stageFeedback',{messageId,...target,sourceRevision,answerAt,feedback:comment});
     if(v?.messageId!==messageId||v.key!==target.key||!Number.isSafeInteger(v.revision)||v.revision<=target.revision||v.savedDraft!==true||
       v.sent!==false||v.projectMemoryChanged!==false||v.modelCalled!==false)throw new Error('原草稿保存回执不完整');
     if(active.current)onReady();
   }catch{if(active.current){setInfo('未确认保存结果，请返回原输入框核对。没有发送，也不会重复写入。');setOpen(false);}}
   finally{busy.current=false;onBusy(false);if(active.current)setPending(false);}
 };
 if(!anchored||!bridge.getState().capabilities.commands.includes('conversation.projectConvention.stageFeedback'))return null;
 return <section aria-label="这条答复的修改意见">{!open?<Button variant="secondary" size="small" disabled={attempted.current} onClick={present}>写修改意见</Button>:<>
   <TextAreaField label="这条答复哪里需要调整？" value={feedback} onChange={setFeedback} maxLength={600} isDisabled={pending}/>
   <p className="pw-muted">只放入这个对话的空输入框。不会发送、修改项目约定或重新启动项目；发送前仍可修改、移除或选择“仅本条不自动整理”。</p>
   {feedback.trim()&&<details><summary>查看将放入的完整草稿</summary><p className="pw-reading-copy" dir="auto">{'请调整本对话中 '+time+' 的那条答复。\n\n当时提供的项目约定（修订 '+sourceRevision+'）：\n'+quote+'\n\n我的修改意见：\n'+feedback.trim()}</p></details>}
   <Button variant="secondary" size="small" disabled={pending||!feedback.trim()||feedback.trim().length>600} onClick={()=>void save()}>放入原对话输入框</Button>
   <Button variant="ghost" size="small" disabled={pending} onClick={()=>{setFeedback('');setInfo('');setOpen(false);}}>取消</Button>
 </>}{pending&&<p role="status">正在核对原回答并保存未发送草稿…</p>}{info&&<p role="status" className="pw-muted">{info}</p>}</section>;
}
