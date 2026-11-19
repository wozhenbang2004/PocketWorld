import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import {registerOverlayBack} from './overlay-back';
import './phone-original-action-review.css';

type Row=Record<string,any>;
export const PHONE_RECORD='pocketworld.owner-phone-unconfirmed.v1';
const verbs:Record<string,string>={phone_launch_app:'打开应用',phone_click_node:'点击控件',phone_click_text:'点击文字目标',phone_tap:'点按屏幕',phone_type:'输入文字',
  phone_swipe:'滑动屏幕',phone_scroll_node:'滚动控件',phone_scroll_region:'滚动区域',phone_back:'返回',phone_home:'回到桌面',phone_gesture:'执行手势',
  phone_press_key:'按键',phone_set_wheel:'调整滚轮',phone_force_stop_app:'停止应用',phone_restart_app:'重新打开应用'};
const positive=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>0;
const id=(v:unknown):v is string=>typeof v==='string'&&v.length>0&&v.length<=240;
const stamp=(n:unknown)=>positive(n)&&n<=8.64e15?new Date(n).toLocaleString(undefined,{year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit'}):'时间未确认';
function held(r:Row,task:string){return r.format===PHONE_RECORD&&r.taskId===task&&id(r.operationId)&&typeof r.tool==='string'&&Object.hasOwn(verbs,r.tool)&&r.automaticReplay===false;}
function neighbor(r:unknown){const n=r as Row;if(!n||!Object.hasOwn(verbs,n.tool)||!positive(n.createdAt))return null;return verbs[n.tool]+' · '+stamp(n.createdAt);}
export function PhoneOriginalActionReview({rows,taskId,more,disabled,allowRecord,submit}:{rows:Row[];taskId:string;more:boolean;disabled:boolean;allowRecord:boolean;submit:(args:Row)=>void}){
  const [selected,setSelected]=useState(''),[outcome,setOutcome]=useState(''),[notice,setNotice]=useState('');
  const triggers=useRef(new Map<string,HTMLButtonElement>()),submitting=useRef(false);
  const choices=rows.filter(r=>held(r,taskId)),r=choices.find(x=>x.operationId===selected);
  const ready=!!r&&allowRecord&&!disabled&&r.canRecord===true&&r.state==='unknown'&&id(r.runId)&&positive(r.epoch)&&positive(r.order)&&positive(r.createdAt);
  function dismiss(later=false){setOutcome('');setSelected('');if(later)setNotice('仍保留为待核对，没有改动动作记录。');requestAnimationFrame(()=>triggers.current.get(selected)?.focus({preventScroll:true}));}
  useEffect(()=>{if(selected)return registerOverlayBack(()=>dismiss());},[selected]);
  if(!choices.length)return null;
  return <section className="pw-phone-unknown" aria-label="核对中断前的手机动作"><h3>待核对的手机动作</h3>
    <p className="pw-muted">只有动作记录，还不能说明应用里的结果。只登记你自己核实过的事实，不会重做动作。</p>
    <ol>{choices.map(x=><li key={x.operationId}><Button ref={node=>{if(node)triggers.current.set(x.operationId,node);else triggers.current.delete(x.operationId);}}
      variant="ghost" aria-expanded={selected===x.operationId} disabled={disabled} onClick={()=>{if(selected===x.operationId){dismiss();return;}setSelected(x.operationId);setOutcome('');setNotice('');}}>
      <span>{verbs[x.tool]}</span><small>第 {positive(x.epoch)?x.epoch:'?'} 轮 · {stamp(x.createdAt)}{positive(x.order)?' · 记录 '+x.order:''}</small></Button>
      {selected===x.operationId&&r&&<div className="pw-phone-unknown-detail">
        {(neighbor(r.before)||neighbor(r.after))&&<dl>{neighbor(r.before)&&<><dt>此前同轮记录</dt><dd>{neighbor(r.before)}</dd></>}
          {neighbor(r.after)&&<><dt>随后同轮记录</dt><dd>{neighbor(r.after)}</dd></>}</dl>}
        {!ready?<p role="status">{r.state==='prepared'?'仍可能在途。等待原回执，不用猜测有没有发生。':'原执行或记录未完整核对，不能登记结论。'}</p>:<>
          <p>记的是这笔动作有没有发生，不是整件事是否办成。</p>
          <div className="pw-action-row" role="group" aria-label="实际看到的结果">
            <Button variant="secondary" aria-pressed={outcome==='occurred'} onClick={()=>setOutcome('occurred')}>我核实已发生</Button>
            <Button variant="secondary" aria-pressed={outcome==='not_occurred'} onClick={()=>setOutcome('not_occurred')}>我核实未发生</Button>
          </div>{outcome&&<Button variant="primary" onClick={()=>{if(submitting.current||!ready)return;submitting.current=true;
            submit({operationId:r.operationId,outcome,checkedOriginal:true,operationRun:r.runId,operationEpoch:r.epoch,operationTool:r.tool});}}>
            记录为{outcome==='occurred'?'已发生':'未发生'}</Button>}</>}
        <Button variant="ghost" onClick={()=>dismiss(true)}>我还不能判断</Button>
      </div>}</li>)}</ol>
    {more&&<p className="pw-muted">还有更后的未确认动作；处理并重新核对后可继续查看。</p>}
    {notice&&<p role="status">{notice}</p>}
  </section>;
}
