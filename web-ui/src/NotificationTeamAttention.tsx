import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import {DecisionInbox} from './shell-library/DecisionInbox';
import {IconRefresh} from './icons';
import type {NotificationViewMemory} from './RemoteInboxWorkspace';

export type TeamAttentionLocator={runId:string;memberId:string;generation:number;memberGeneration:number;assignmentId?:string;questionId?:string;workItemId?:string};
type Item=TeamAttentionLocator&{id?:string;kind:string;memberName?:string;title?:string;status?:string};
type Page={items:Item[];hasMore:boolean;nextAfterRunId:string;nextAfterItemId:string;scannedRuns:number};
const key=(item:Item)=>[item.runId,item.memberId,item.workItemId||'',item.assignmentId||'',item.questionId||''].join(':');
export function NotificationTeamAttention({enabled,onOpen,memory}:{enabled:boolean;onOpen:(locator:TeamAttentionLocator)=>void;memory:NotificationViewMemory}) {
  const [page,setPage]=useState<Page|null>(null),[cursor,setCursor]=useState(memory.teamCursor||''),[itemCursor,setItemCursor]=useState(memory.teamItemCursor||''),[prior,setPrior]=useState<{run:string;item:string}[]>(memory.teamPrior||[]);
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const request=useRef(0),entering=useRef(false),alive=useRef(true),root=useRef<HTMLElement>(null);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;request.current++}},[]);
  const read=async(after:string,afterItem:string,history:{run:string;item:string}[])=>{
    if(!enabled||entering.current)return;entering.current=true;const ticket=++request.current;setBusy(true);setError('');
    try{const result=await bridge.command('teams.attention',{afterRunId:after,afterItemId:afterItem,limit:8}) as Page;
      if(alive.current&&ticket===request.current){setPage(result);setCursor(after);setItemCursor(afterItem);setPrior(history);memory.teamCursor=after;memory.teamItemCursor=afterItem;memory.teamPrior=history;}
    }catch(failure){if(alive.current&&ticket===request.current)setError(failure instanceof Error?failure.message:String(failure));}
    finally{if(alive.current&&ticket===request.current){entering.current=false;setBusy(false);}}
  };
  useEffect(()=>{request.current++;entering.current=false;setPage(null);setBusy(false);if(enabled)void read(cursor,itemCursor,prior)},[enabled]);
  useEffect(()=>{if(!page||!memory.teamFocus)return;const target=Array.from(root.current?.querySelectorAll<HTMLElement>('[data-task-id]')||[]).find(node=>node.dataset.taskId===memory.teamFocus);if(target){target.scrollIntoView({block:'center'});target.querySelector<HTMLElement>('button')?.focus({preventScroll:true});memory.teamFocus=undefined}},[page]);
  return <section ref={root} className="pw-notification-attention" aria-label="团队待处理">
    <header className="pw-remote-section-title"><h2>团队正在等你</h2><IconButton icon={IconRefresh} aria-label="重新读取团队待处理" disabled={!enabled||busy} onClick={()=>void read('','',[])}/></header>
    <p className="pw-remote-footnote">按协调器保留的运行记录查找，打开后核对原成员、工作项和问题。</p>
    {page&&page.items.length>0&&<DecisionInbox compact heading="本页团队待处理" decisions={page.items.map(item=>({id:key(item),title:item.title||'原团队待处理',detail:[item.memberName,item.kind==='question'?'等待你回答':'需要你核对原成员任务'].filter(Boolean).join(' · '),primary:item.kind==='question'?'回到原问题':'核对原成员',secondary:'查看原团队'}))} onDecide={decision=>{const item=page.items.find(item=>key(item)===decision.id);if(!item)return;memory.teamFocus=decision.id;onOpen({runId:item.runId,memberId:item.memberId,generation:item.generation,memberGeneration:item.memberGeneration,...(item.assignmentId?{assignmentId:item.assignmentId}:{}),...(item.questionId?{questionId:item.questionId}:{}),...(item.workItemId?{workItemId:item.workItemId}:{})})}}/>}
    {!enabled&&<p className="pw-muted">当前主人界面尚不可读取团队待处理，请重新打开通知中心。</p>}
    {busy&&<p className="pw-muted" role="status">正在读取原团队的问题与成员状态…</p>}
    {error&&<p className="pw-error" role="alert">{error}</p>}
    {!busy&&!error&&page?.items.length===0&&<p className="pw-muted">{page.hasMore?'本次读取的运行没有待处理项，仍可继续查找。':prior.length?'已到团队记录末尾，本页没有待处理项。':'原团队记录中没有待答问题或待核对成员。'}</p>}
    <div className="pw-remote-buttons">{prior.length>0&&<Button variant="ghost" disabled={busy} onClick={()=>void read(prior[prior.length-1].run,prior[prior.length-1].item,prior.slice(0,-1))}>上一页团队</Button>}{page?.hasMore&&<Button variant="secondary" disabled={busy} onClick={()=>void read(page.nextAfterRunId,page.nextAfterItemId,[...prior,{run:cursor,item:itemCursor}])}>继续查找团队</Button>}{error&&<Button variant="ghost" disabled={busy||!enabled} onClick={()=>void read(cursor,itemCursor,prior)}>重新读取</Button>}</div>
  </section>;
}
