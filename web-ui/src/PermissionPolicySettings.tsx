import {useEffect,useRef,useState,type ReactNode} from 'react';
import {Button,TextField,TextAreaField,RadioOptions,Accordion,AccordionItem} from './forms-library';

type Decision='allow'|'ask'|'deny'|'review';
type Policy={version:2;mode:'custom'|'auto_review'|'full_access';tools:Record<string,Decision>;instructions:string};
type Snapshot={revision:number;conversationId:string;global:Policy;conversation:Policy|null;effective:Policy};
type CatalogTool={name:string;category:string;description:string;source:string;configurable:boolean};
type Catalog={tools:CatalogTool[];notices:string[]};
type Props={conversationId:string;act:(action:string,args?:Record<string,any>)=>Promise<any>;onPendingChange?:(pending:boolean)=>void;children?:ReactNode};
const instructionLimit=8000;
const decisionLabels:Record<Decision,string>={allow:'直接允许',ask:'询问我',deny:'禁止',review:'AI 审批'};
const decisions=Object.entries(decisionLabels).map(([id,label])=>({id,label}));
const categoryLabels:Record<string,string>={phone:'手机操作',browser:'浏览器',ssh:'SSH 连接',attachments:'附件与文档',memory:'记忆与历史',skills:'技能',workspace:'工作区与成果',project:'项目开发',remote:'远程电脑',media:'图片、音视频',semantic:'App 入口',teams:'团队协作',automation:'计划与自动化',assistant:'私人管家与偏好',plugins:'已安装插件',run:'任务流程'};
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
 const [catalog,setCatalog]=useState<Catalog|null>(null),[catalogError,setCatalogError]=useState(''),[query,setQuery]=useState('');
 useEffect(()=>{let live=true;++generation.current;pendingRef.current=false;pendingChangeRef.current?.(false);setBusy(false);setSnapshot(null);setDraft(null);setError('');setNotice('');setTool('');setCatalog(null);setCatalogError('');setQuery('');
  actRef.current('permissions.snapshot').then((value:Snapshot)=>{if(live){setSnapshot(value);setScope(value.conversationId?'conversation':'global');setDraft(value.conversationId?conversationDraft(value):clone(value.global));}}).catch(e=>{if(live)setError(String(e.message||e));});
  actRef.current('permissions.catalog').then((value:Catalog)=>{if(live)setCatalog(value);}).catch(e=>{if(live)setCatalogError(String(e.message||e));});
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
 const add=(name=tool.trim(),decision:Decision='ask')=>{if(pendingRef.current)return;if(!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(name)){setError('工具名须以英文字母开头，仅含字母、数字、下划线或连字符，最多 64 字符');return;}if(Object.prototype.hasOwnProperty.call(draft.tools,name)){setError('已有此工具规则，请在已配置规则中修改');return;}if(Object.keys(draft.tools).length>=512){setError('工具规则最多 512 项');return;}edit({...draft,tools:{...draft.tools,[name]:decision}});setTool('');setError('');setNotice(`已添加 ${name} 的规则，请在“已配置规则”中选择审批方式并保存。`);};
 const effectiveTools=scope==='conversation'?{...snapshot.global.tools,...draft.tools}:draft.tools;
 const usesAiReview=draft.mode==='auto_review'||Object.values(effectiveTools).includes('review');
 const search=query.trim().toLowerCase();
 const listed=(catalog?.tools||[]).filter(item=>!search||`${item.name} ${item.description} ${categoryLabels[item.category]||item.category}`.toLowerCase().includes(search));
 const groups:Record<string,CatalogTool[]>={};for(const item of listed)(groups[item.category]??=[]).push(item);
 const renderTool=(item:CatalogTool)=>{const own=Object.prototype.hasOwnProperty.call(draft.tools,item.name),decision=effectiveTools[item.name];return <div className="pw-permission-tool-rule" key={item.name}>
  <Button variant="ghost" disabled={busy||own||!item.configurable} onClick={()=>add(item.name,decision||'ask')}>{item.name}</Button>
  <p className="pw-permission-hint">{item.description.length>180?`${item.description.slice(0,180)}…`:item.description}</p>
  <p className="pw-permission-inherited">{!item.configurable?'原生流程控制，不由这里的规则覆盖':own?`已配置：${decisionLabels[draft.tools[item.name]]}`:decision?`继承全局：${decisionLabels[decision]} · 点选单独设置`:'尚未单独设置 · 点选添加规则'}{item.source==='installed_plugin'?' · 已注册插件':''}</p>
 </div>;};
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
  <Accordion variant="divided" className="pw-permission-rules" defaultExpandedKeys={[]}>
  <AccordionItem id="permission-catalog" title={`工具目录${catalog?` · ${catalog.tools.length} 项`:''}`} description={catalog?'按名称或用途查找，按需设置独立规则':catalogError?'目录暂不可用，已配置规则仍可编辑':'正在读取本机工具目录…'}>
   <p className="pw-permission-hint">按名称或用途找工具，点选后设置规则。目录不是本次任务的可用工具清单，设置不会启用未挂载能力。</p>
   <TextField label="搜索工具" value={query} onChange={setQuery} placeholder="手机、记忆、搜索、browser…"/>
   {!catalog&&<p className="pw-permission-hint" role={catalogError?'alert':'status'}>{catalogError?`目录暂不可用：${catalogError}。仍可查看或手动添加规则。`:'读取本机工具目录…'}</p>}
   {catalog&&(search?<><p className="pw-permission-hint">找到 {listed.length} 项</p>{listed.map(renderTool)}</>:<Accordion variant="divided" defaultExpandedKeys={[]}>
    {Object.entries(groups).sort(([a],[b])=>a==='phone'?-1:b==='phone'?1:(categoryLabels[a]||a).localeCompare(categoryLabels[b]||b,'zh')).map(([category,items])=><AccordionItem key={category} id={category} title={`${categoryLabels[category]||category} · ${items.length}`} >{items.map(renderTool)}</AccordionItem>)}
   </Accordion>)}
   {catalog?.notices.map((text,index)=><p className="pw-permission-hint" key={index}>{text}</p>)}
  </AccordionItem>
  <AccordionItem id="permission-rules" title={`已配置规则 · ${Object.keys(effectiveTools).length} 项`}>
   <p className="pw-permission-hint">这里只列已配置规则，不是全部工具。规则优先于模式；未配置的工具按模式或原生要求处理。</p>
   {scope==='conversation'&&Object.entries(snapshot.global.tools).filter(([name])=>!Object.prototype.hasOwnProperty.call(draft.tools,name)).map(([name,value])=><div className="pw-permission-tool-rule" key={name}><p className="pw-permission-inherited">{name} · 继承全局：{decisionLabels[value]}</p><Button variant="ghost" disabled={busy} onClick={()=>add(name,value)}>对此对话单独设置</Button></div>)}
   {Object.entries(draft.tools).map(([name,decision])=><div className="pw-permission-tool-rule" key={name}>
    <RadioOptions label={name} value={decision} isDisabled={busy} options={decisions} onChange={value=>edit({...draft,tools:{...draft.tools,[name]:value as Decision}})}/>
    <Button variant="ghost" disabled={busy} onClick={()=>{const rules={...draft.tools};delete rules[name];edit({...draft,tools:rules});}}>移除此规则</Button>
   </div>)}
   <div className="pw-permission-add-rule">
    <TextField label="精确工具名" value={tool} isDisabled={busy} onChange={value=>{if(!pendingRef.current)setTool(value)}} placeholder="attachments_create_document"/>
    <Button variant="secondary" disabled={busy||!tool.trim()} onClick={()=>add()}>添加规则</Button>
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
