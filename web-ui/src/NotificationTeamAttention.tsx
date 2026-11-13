import './notification-attention.css';
import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import {DecisionInbox} from './shell-library/DecisionInbox';
import {IconRefresh,IconChevronRight} from './icons';
import type {NotificationViewMemory} from './RemoteInboxWorkspace';

export type TeamAttentionLocator={runId:string;memberId:string;generation:number;memberGeneration:number;identityRevision?:number;assignmentId?:string;questionId?:string;workItemId?:string;childKind?:'research'|'phone';round?:number;taskId?:string};
type Item=TeamAttentionLocator&{id?:string;kind:string;memberName?:string;title?:string;status?:string};
type Page={items:Item[];hasMore:boolean;nextAfterRunId:string;nextAfterItemId:string;scannedRuns:number};
const key=(item:Item)=>[item.runId,item.id||[item.memberId,item.workItemId||'',item.assignmentId||'',item.questionId||'',item.childKind||'',item.round??'',item.taskId||''].join(':')].join(':');
export function NotificationTeamAttention({enabled,onOpen,memory}:{enabled:boolean;onOpen:(locator:TeamAttentionLocator)=>void;memory:NotificationViewMemory}) {
  const [managing,setManaging]=useState(false);
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
  useEffect(()=>{if(!page||!memory.teamFocus)return;const target=Array.from(root.current?.querySelectorAll<HTMLElement>('[data-task-id]')||[]).find(node=>node.dataset.taskId===memory.teamFocus);if(target){target.scrollIntoView({block:'center'});(target.matches('button')?target:target.querySelector<HTMLElement>('button'))?.focus({preventScroll:true});memory.teamFocus=undefined}},[page]);
  const openItem=(item:Item)=>{memory.teamFocus=key(item);onOpen({runId:item.runId,memberId:item.memberId,generation:item.generation,memberGeneration:item.memberGeneration,...(typeof item.identityRevision==='number'?{identityRevision:item.identityRevision}:{}),...(item.assignmentId?{assignmentId:item.assignmentId}:{}),...(item.questionId?{questionId:item.questionId}:{}),...(item.workItemId?{workItemId:item.workItemId}:{}),...(item.childKind?{childKind:item.childKind}:{}),...(typeof item.round==='number'?{round:item.round}:{}),...(item.taskId?{taskId:item.taskId}:{})})};
  const groups=[{title:'待回答的问题',items:(page?.items||[]).filter(item=>item.kind==='question')},{title:'待处理的分工',items:(page?.items||[]).filter(item=>item.kind!=='question')}];
  return <section ref={root} className="pw-notification-attention" aria-label="团队待处理">
    <header className="pw-remote-section-title"><h2>协作正在等你</h2><IconButton icon={IconRefresh} aria-label="重新读取协作待处理" disabled={!enabled||busy} onClick={()=>void read('','',[])}/></header>
    <p className="pw-remote-footnote">查看成员的问题，回到对应分工处理。</p>
    {page&&page.items.length>0&&<div className="pw-notification-view-tools"><span>本页 {page.items.length} 项</span><Button variant="ghost" aria-pressed={managing} onClick={()=>setManaging(value=>!value)}>{managing?'完成管理':'管理待办'}</Button></div>}
    {groups.filter(group=>group.items.length>0).map(group=><section className="pw-notification-group" key={group.title} aria-label={group.title}>{managing?<DecisionInbox compact heading={group.title} decisions={group.items.map(item=>({id:key(item),title:item.title||'协作待处理',detail:[item.memberName,item.kind==='question'?'等待你回答':'成员任务需要处理'].filter(Boolean).join(' · '),primary:item.kind==='question'?'回到问题':'查看分工',secondary:'查看协作'}))} onDecide={decision=>{const item=group.items.find(item=>key(item)===decision.id);if(item)openItem(item)}}/>:<><header className="pw-notification-group-heading"><h3>{group.title}</h3><span>{group.items.length}</span></header><div className="pw-task-list">{group.items.map(item=><Button key={key(item)} variant="ghost" className="pw-task-row" data-task-id={key(item)} onClick={()=>openItem(item)}><span className="pw-task-row-copy"><b>{item.title||'协作待处理'}</b><span className="pw-task-row-meta">{item.memberName||'成员'} · {item.kind==='question'?'等待你回答':'需要查看分工'}</span></span><IconChevronRight size={18} aria-hidden/></Button>)}</div></>}</section>)}
    {!enabled&&<p className="pw-muted">当前主人界面尚不可读取团队待处理，请重新打开通知中心。</p>}
    {busy&&<p className="pw-muted" role="status">正在读取原团队的问题与成员状态…</p>}
    {error&&<p className="pw-error" role="alert">{error}</p>}
    {!busy&&!error&&page?.items.length===0&&<p className="pw-muted">{page.hasMore?'本次读取的运行没有待处理项，仍可继续查找。':prior.length?'已到团队记录末尾，本页没有待处理项。':'原团队记录中没有待答问题或待核对成员。'}</p>}
    <div className="pw-remote-buttons">{prior.length>0&&<Button variant="ghost" disabled={busy} onClick={()=>void read(prior[prior.length-1].run,prior[prior.length-1].item,prior.slice(0,-1))}>上一页团队</Button>}{page?.hasMore&&<Button variant="secondary" disabled={busy} onClick={()=>void read(page.nextAfterRunId,page.nextAfterItemId,[...prior,{run:cursor,item:itemCursor}])}>继续查找团队</Button>}{error&&<Button variant="ghost" disabled={busy||!enabled} onClick={()=>void read(cursor,itemCursor,prior)}>重新读取</Button>}</div>
  </section>;
}
