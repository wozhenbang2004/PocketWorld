import {useEffect,useRef,useState,type ReactNode} from 'react';
import {Button,TextField,TextAreaField,RadioOptions,Accordion,AccordionItem} from './forms-library';

type Decision='allow'|'ask'|'deny'|'review';
type Policy={version:2;mode:'custom'|'auto_review'|'full_access';tools:Record<string,Decision>;instructions:string};
type Snapshot={revision:number;conversationId:string;global:Policy;conversation:Policy|null;effective:Policy};
type Props={conversationId:string;act:(action:string,args?:Record<string,any>)=>Promise<any>;onPendingChange?:(pending:boolean)=>void;children?:ReactNode};
const instructionLimit=8000;
const decisionLabels:Record<Decision,string>={allow:'直接允许',ask:'询问我',deny:'禁止',review:'AI 审批'};
const decisions=Object.entries(decisionLabels).map(([id,label])=>({id,label}));
const clone=(p:Policy):Policy=>({...p,tools:{...p.tools}});
const conversationDraft=(s:Snapshot):Policy=>s.conversation?clone(s.conversation):{...s.effective,tools:{}};
const modes=[
 {id:'custom',label:'自定义',description:'按工具规则审批，未设置时沿用原有要求'},
 {id:'auto_review',label:'AI 自动审批',description:'需要审批或会产生更改的动作，交给 AI 判断'},
 {id:'full_access',label:'完全放开',description:'默认允许可配置的审批，工具规则仍优先'},
];
export function PermissionPolicySettings({conversationId,act,onPendingChange,children}:Props){
 const actRef=useRef(act);actRef.current=act;
 const generation=useRef(0),pendingRef=useRef(false),pendingChangeRef=useRef(onPendingChange);pendingChangeRef.current=onPendingChange;
 const [snapshot,setSnapshot]=useState<Snapshot|null>(null),[scope,setScope]=useState('conversation');
 const [draft,setDraft]=useState<Policy|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[tool,setTool]=useState('');
 useEffect(()=>{let live=true;++generation.current;pendingRef.current=false;pendingChangeRef.current?.(false);setBusy(false);setSnapshot(null);setDraft(null);setError('');setNotice('');setTool('');
  actRef.current('permissions.snapshot').then((value:Snapshot)=>{if(live){setSnapshot(value);setScope(value.conversationId?'conversation':'global');setDraft(value.conversationId?conversationDraft(value):clone(value.global));}}).catch(e=>{if(live)setError(String(e.message||e));});
  return()=>{live=false;++generation.current;pendingRef.current=false;pendingChangeRef.current?.(false)};
 },[conversationId]);
 const selectScope=(next:string)=>{if(!snapshot||pendingRef.current)return;setScope(next);setDraft(next==='global'?clone(snapshot.global):conversationDraft(snapshot));setTool('');setError('');setNotice('');};
 const edit=(next:Policy)=>{if(pendingRef.current)return;setDraft(next);setNotice('');};
 const save=async(reset=false)=>{if(!snapshot||!draft||pendingRef.current)return;if(!reset&&draft.instructions.length>instructionLimit){setError('AI 审批指令最多 8,000 字符');return;}const saveGeneration=generation.current;pendingRef.current=true;pendingChangeRef.current?.(true);setBusy(true);setError('');setNotice('');
  try{const result:Snapshot=await act('permissions.save',{conversationId:snapshot.conversationId,global:scope==='global',policy:reset?null:draft,expectedRevision:snapshot.revision});
   if(generation.current!==saveGeneration)return;
   setSnapshot(result);setDraft(scope==='global'?clone(result.global):conversationDraft(result));setNotice(reset?'已恢复继承全局设置':'权限设置已保存；已有任务不在此重新派发');
  }catch(e){if(generation.current===saveGeneration)setError(e instanceof Error?e.message:String(e));}finally{if(generation.current===saveGeneration){pendingRef.current=false;setBusy(false);pendingChangeRef.current?.(false)}}
 };
 if(!snapshot||!draft)return <><p className="pw-permission-read-status" role={error?'alert':'status'}>{error||'读取权限设置…'}</p>{children}</>;
 const add=()=>{if(pendingRef.current)return;const name=tool.trim();if(!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(name)){setError('工具名须以英文字母开头，仅含字母、数字或下划线，最多 64 字符');return;}if(Object.prototype.hasOwnProperty.call(draft.tools,name)){setError('已有此工具规则，请在上方修改');return;}edit({...draft,tools:{...draft.tools,[name]:'ask'}});setTool('');setError('');};
 const effectiveTools=scope==='conversation'?{...snapshot.global.tools,...draft.tools}:draft.tools;
 const usesAiReview=draft.mode==='auto_review'||Object.values(effectiveTools).includes('review');
 return <section className="pw-permission-policy" aria-label="审批策略" aria-busy={busy}>
  <div className="pw-permission-scope">
   <RadioOptions label="应用范围" value={scope} isDisabled={busy} options={[{id:'conversation',label:'此对话',disabled:!snapshot.conversationId},{id:'global',label:'全局默认'}]} onChange={selectScope}/>
   <p className="pw-permission-hint">{scope==='global'?'作为未单独设置对话的默认策略':snapshot.conversation?'此对话的模式与 AI 指令独立设置，同名工具规则覆盖全局':'当前继承全局；保存后，此对话的模式与 AI 指令将独立设置'}</p>
  </div>
  <div className="pw-permission-modes">
   <RadioOptions label="审批模式" value={draft.mode} options={modes} isDisabled={busy} onChange={mode=>edit({...draft,mode:mode as Policy['mode']})}/>
   {draft.mode==='full_access'&&<p className="pw-permission-hint" role="note">保存后，未被工具规则限制的可配置操作将直接执行，可能修改或删除数据、访问外部服务。</p>}
   {usesAiReview&&<p className="pw-permission-hint">AI 会独立判断操作是否符合当前要求，可能误判，并产生额外模型调用。</p>}
   <p className="pw-permission-hint">所有模式均不扩大系统权限或任务范围，原生高风险接管要求仍有效。</p>
  </div>
  <Accordion variant="divided" className="pw-permission-rules" defaultExpandedKeys={[]}><AccordionItem id="permission-rules" title="工具规则">
   <p className="pw-permission-hint">填写手机、浏览器、插件等工具的精确名称，不支持通配符。规则优先于模式，未挂载工具不会因此启用。</p>
   {scope==='conversation'&&Object.entries(snapshot.global.tools).filter(([name])=>!Object.prototype.hasOwnProperty.call(draft.tools,name)).map(([name,value])=><p className="pw-permission-inherited" key={name}>{name} · 继承全局：{decisionLabels[value]}</p>)}
   {Object.entries(draft.tools).map(([name,decision])=><div className="pw-permission-tool-rule" key={name}>
    <RadioOptions label={name} value={decision} isDisabled={busy} options={decisions} onChange={value=>edit({...draft,tools:{...draft.tools,[name]:value as Decision}})}/>
    <Button variant="ghost" disabled={busy} onClick={()=>{const rules={...draft.tools};delete rules[name];edit({...draft,tools:rules});}}>移除此规则</Button>
   </div>)}
   <div className="pw-permission-add-rule">
    <TextField label="精确工具名" value={tool} isDisabled={busy} onChange={value=>{if(!pendingRef.current)setTool(value)}} placeholder="attachments_create_document"/>
    <Button variant="secondary" disabled={busy||!tool.trim()} onClick={add}>添加规则</Button>
   </div>
   {scope==='conversation'&&<p className="pw-permission-hint">移除会话规则后沿用同名全局规则；没有全局规则时按模式执行。</p>}
  </AccordionItem>
  <AccordionItem id="permission-instructions" title="AI 审批指令">
   <TextAreaField label="审批时参考的要求" value={draft.instructions} onChange={instructions=>edit({...draft,instructions})} isDisabled={busy} rows={3} autoResize maxRows={6} resize="none" maxLength={instructionLimit} showCount placeholder="例如：涉及对外发送或删除原始文件时，先询问我。" hint="仅供 AI 审批参考，不新增执行授权。AI 可能误判，并产生额外模型调用。"/>
   {scope==='conversation'&&<p className="pw-permission-hint">保存后仅使用此处指令，不再叠加全局指令；留空表示不附加指令。恢复继承可重新跟随全局。</p>}
  </AccordionItem></Accordion>
  {children}
  <footer className="pw-permission-actions">
   {error&&<p className="pw-permission-error" role="alert">{error}</p>}{notice&&<p className="pw-permission-notice" role="status">{notice}</p>}
   <div className="pw-permission-action-row">
    {scope==='conversation'&&snapshot.conversation&&<Button variant="ghost" disabled={busy} onClick={()=>void save(true)}>继承全局</Button>}
    <Button disabled={busy} onClick={()=>void save()}>{busy?'保存中…':draft.mode==='full_access'?'保存完全放开':'保存权限'}</Button>
   </div>
  </footer>
 </section>;
}
