import {useEffect,useRef,useState} from 'react';
import {Button,TextField,RadioOptions,Accordion,AccordionItem} from './forms-library';

type Policy={version:1;mode:'ask'|'controlled'|'auto';tools:Record<string,'allow'|'ask'|'deny'>};
type Snapshot={revision:number;conversationId:string;global:Policy;conversation:Policy|null;effective:Policy};
type Props={conversationId:string;act:(action:string,args?:Record<string,any>)=>Promise<any>;onPendingChange?:(pending:boolean)=>void};
const clone=(p:Policy):Policy=>({...p,tools:{...p.tools}});
const conversationDraft=(s:Snapshot):Policy=>s.conversation?clone(s.conversation):{...s.effective,tools:{}};
const modes=[
 {id:'ask',label:'每次询问',description:'读取自动，写入前询问'},
 {id:'controlled',label:'受控自动',description:'授权内新建本地附件自动，其余写入询问'},
 {id:'auto',label:'完全自动',description:'已挂载且获授权的工具不再逐项询问'},
];
export function PermissionPolicySettings({conversationId,act,onPendingChange}:Props){
 const actRef=useRef(act);actRef.current=act;
 const generation=useRef(0),pendingRef=useRef(false),pendingChangeRef=useRef(onPendingChange);pendingChangeRef.current=onPendingChange;
 const [snapshot,setSnapshot]=useState<Snapshot|null>(null),[scope,setScope]=useState('conversation');
 const [draft,setDraft]=useState<Policy|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[tool,setTool]=useState('');
 useEffect(()=>{let live=true;++generation.current;pendingRef.current=false;pendingChangeRef.current?.(false);setBusy(false);setSnapshot(null);setDraft(null);setError('');setNotice('');
  actRef.current('permissions.snapshot').then((value:Snapshot)=>{if(live){setSnapshot(value);setScope(value.conversationId?'conversation':'global');setDraft(value.conversationId?conversationDraft(value):clone(value.global));}}).catch(e=>{if(live)setError(String(e.message||e));});
  return()=>{live=false;++generation.current;pendingRef.current=false;pendingChangeRef.current?.(false)};
 },[conversationId]);
 const selectScope=(next:string)=>{if(!snapshot||pendingRef.current)return;setScope(next);setDraft(next==='global'?clone(snapshot.global):conversationDraft(snapshot));setNotice('');};
 const edit=(next:Policy)=>{if(pendingRef.current)return;setDraft(next);setNotice('');};
 const save=async(reset=false)=>{if(!snapshot||!draft||pendingRef.current)return;const saveGeneration=generation.current;pendingRef.current=true;pendingChangeRef.current?.(true);setBusy(true);setError('');setNotice('');
  try{const result:Snapshot=await act('permissions.save',{conversationId:snapshot.conversationId,global:scope==='global',policy:reset?null:draft,expectedRevision:snapshot.revision});
   if(generation.current!==saveGeneration)return;
   setSnapshot(result);setDraft(scope==='global'?clone(result.global):conversationDraft(result));setNotice(reset?'已恢复继承全局设置':'权限设置已保存；已有任务不在此重新派发');
  }catch(e){if(generation.current===saveGeneration)setError(e instanceof Error?e.message:String(e));}finally{if(generation.current===saveGeneration){pendingRef.current=false;setBusy(false);pendingChangeRef.current?.(false)}}
 };
 if(!snapshot||!draft)return <p className="pw-permission-read-status" role={error?'alert':'status'}>{error||'读取权限设置…'}</p>;
 const add=()=>{if(pendingRef.current)return;const name=tool.trim();if(!/^(browser|ssh|attachments)_[A-Za-z0-9_]+$/.test(name)){setError('填写精确工具名，例如 attachments_create_document');return;}edit({...draft,tools:{...draft.tools,[name]:'ask'}});setTool('');setError('');};
 return <section className="pw-permission-policy" aria-label="审批策略" aria-busy={busy}>
  <div className="pw-permission-scope">
   <RadioOptions label="应用范围" value={scope} isDisabled={busy} options={[{id:'conversation',label:'此对话',disabled:!snapshot.conversationId},{id:'global',label:'全局默认'}]} onChange={selectScope}/>
   {scope==='conversation'&&<p className="pw-permission-hint">{snapshot.conversation?'此对话使用独立模式，工具规则覆盖全局':'此对话当前继承全局设置'}</p>}
  </div>
  <div className="pw-permission-modes">
   <RadioOptions label="审批模式" value={draft.mode} options={modes} isDisabled={busy} onChange={mode=>edit({...draft,mode:mode as Policy['mode']})}/>
   <p className="pw-permission-hint">不会扩大系统权限或任务范围。</p>
  </div>
  <Accordion variant="divided" className="pw-permission-rules" defaultExpandedKeys={[]}><AccordionItem id="permission-rules" title="工具规则">
   <p className="pw-permission-hint">仅支持浏览器、SSH、附件工具的精确名称。规则优先于模式，未挂载工具不会因此启用。</p>
   {scope==='conversation'&&Object.entries(snapshot.global.tools).filter(([name])=>!(name in draft.tools)).map(([name,value])=><p className="pw-permission-inherited" key={name}>{name} · 继承全局：{({allow:'允许',ask:'询问',deny:'禁止'})[value]}</p>)}
   {Object.entries(draft.tools).map(([name,decision])=><div className="pw-permission-tool-rule" key={name}>
    <RadioOptions label={name} value={decision} isDisabled={busy} options={[{id:'allow',label:'允许'},{id:'ask',label:'询问'},{id:'deny',label:'禁止'}]} onChange={value=>edit({...draft,tools:{...draft.tools,[name]:value as Policy['tools'][string]}})}/>
    <Button variant="ghost" disabled={busy} onClick={()=>{const rules={...draft.tools};delete rules[name];edit({...draft,tools:rules});}}>移除此规则</Button>
   </div>)}
   <div className="pw-permission-add-rule">
    <TextField label="精确工具名" value={tool} isDisabled={busy} onChange={value=>{if(!pendingRef.current)setTool(value)}} placeholder="attachments_create_document"/>
    <Button variant="secondary" disabled={busy||!tool.trim()} onClick={add}>添加规则</Button>
   </div>
   {scope==='conversation'&&<p className="pw-permission-hint">移除会话规则后沿用同名全局规则；没有全局规则时按模式执行。</p>}
  </AccordionItem></Accordion>
  <footer className="pw-permission-actions">
   {error&&<p className="pw-permission-error" role="alert">{error}</p>}{notice&&<p className="pw-permission-notice" role="status">{notice}</p>}
   <div className="pw-permission-action-row">
    {scope==='conversation'&&snapshot.conversation&&<Button variant="ghost" disabled={busy} onClick={()=>void save(true)}>继承全局</Button>}
    <Button disabled={busy} onClick={()=>void save()}>{busy?'保存中…':'保存权限'}</Button>
   </div>
  </footer>
 </section>;
}
