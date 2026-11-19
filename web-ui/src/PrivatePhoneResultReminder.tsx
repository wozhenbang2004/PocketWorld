import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import {bridge} from './bridge';
type Target={taskId:string;runId:string;epoch:number};
type Status={watching:boolean;systemAvailable:boolean};
function checked(raw:any,target:Target):Status{
  if(!raw||raw.taskId!==target.taskId||raw.runId!==target.runId||raw.epoch!==target.epoch||raw.scope!=='this_run_this_phone'||raw.externalDelivery!==false||
      typeof raw.watching!=='boolean'||typeof raw.systemAvailable!=='boolean') throw Error('没有收到这轮提醒的明确回执。');
  return {watching:raw.watching,systemAvailable:raw.systemAvailable};
}
/** Only mounted inside this original live phone row's open actions menu. Reading never subscribes. */
export function PrivatePhoneResultReminder({target}:{target:Target}){
  const {taskId,runId,epoch}=target;
  const [status,setStatus]=useState<Status|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const turn=useRef(0),flight=useRef(false),alive=useRef(false);
  async function request(want?:boolean){
    if(flight.current||!alive.current||document.hidden)return;
    const id=++turn.current,doc=bridge.getDocumentEpoch();flight.current=true;setBusy(true);setError('');
    const valid=()=>alive.current&&turn.current===id&&!document.hidden&&bridge.getDocumentEpoch()===doc;
    try{const original={taskId,runId,epoch},next=checked(await bridge.command(want===undefined?'phone.resultNotice.read':'phone.resultNotice.set',want===undefined?original:{...original,watching:want}),original);
      if(want!==undefined&&next.watching!==want)throw Error('原选择尚未确认。请重读，不要重复登记。');if(valid())setStatus(next);
    }catch(failure){if(valid()){setStatus(null);setError(failure instanceof Error?failure.message:'未取得本轮提醒选择。')}}
    finally{if(alive.current&&turn.current===id){flight.current=false;setBusy(false)}}
  }
  useEffect(()=>{alive.current=true;setStatus(null);void request();
    function visibility(){++turn.current;flight.current=false;setBusy(false);setStatus(null);if(!document.hidden)void request();}
    document.addEventListener('visibilitychange',visibility);
    return()=>{alive.current=false;++turn.current;flight.current=false;document.removeEventListener('visibilitychange',visibility)};
  },[taskId,runId,epoch]);
  return <div aria-label="本轮结果提醒">
    {status?.watching?<Button variant="ghost" disabled={busy} onClick={()=>void request(false)}>取消本轮结果提醒</Button>:
      status?.systemAvailable?<Button variant="ghost" disabled={busy} onClick={()=>void request(true)}>这轮有结果时提醒我</Button>:
      error?<Button variant="ghost" disabled={busy} onClick={()=>void request()}>重读本轮提醒选择</Button>:null}
    <p className="pw-field-hint" role="status">{error||(!status?'正在核对本机提醒…':
      status.watching?(status.systemAvailable?'已登记，仅此轮。取消只阻止待发提醒；不会唤醒被强行停止的应用。':'本轮已登记，但系统通知当前不可用。结果仍保留在原任务。'):
      !status.systemAvailable?'本机任务通知不可用，不会申请权限。结果仍保留在原任务。':'仅此轮、本机。24 小时内有收尾回执才尝试通知；不含任务内容，不补发久远提醒。')}</p>
  </div>;
}
