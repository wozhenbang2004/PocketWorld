// PW-02/03/04/05: controlled ports of actual saved Affect library v3 JSX/CSS.
// Source DOM classes and hierarchy retained; all sample data and business timers removed.
import {useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import type {ChatComposerProps,ModelOption,DataPanelProps,Step} from './types';
import {FrameA,FrameB,Card,Icon,ToolButton,statusLabel,records,textOf} from './core';
import {PressButton} from './press';
import {MessageActions} from './message';
import {CitedAnswer} from './context';
import {MarkdownBody} from './MarkdownBody';
import {ProgressCircle} from './adapted/boardui-progress';
import {ComposerContextMeter} from '../ComposerContextMeter';
import {SmoothComposerSurface} from './SmoothComposerSurface';
import {composerHasPayload} from '../composer-send-policy';
function sizeComposerEditor(editor:HTMLTextAreaElement|null){
 if(!editor)return;
 editor.style.height='0px';
 const height=editor.value?editor.scrollHeight:48;
 editor.style.height=`${Math.min(120,Math.max(48,height))}px`;
 editor.style.overflowY=height>120?'auto':'hidden';
}

export function ChatComposer({value,onChange,onSend,onStop,busy,submitting,disabled,placeholder='输入目标，或补充上下文…',attachments=[],onRemoveAttachment,onAttach,onVoice,listening,voiceStatus,onDiscardVoice,modelName,onSelectModel,toolbar,controls,contextStatus,contextPercent,children,status,label='工作输入',composerKey,className='',sendDisabled=false,sendLabel}:ChatComposerProps&{sendDisabled?:boolean;sendLabel?:string}){
 const editor=useRef<HTMLTextAreaElement>(null);
 useLayoutEffect(()=>{sizeComposerEditor(editor.current)},[value,listening,composerKey]);
 useLayoutEffect(()=>{
  const el=editor.current;
  if(!el||typeof ResizeObserver==='undefined')return;
  let width=el.clientWidth;
  const observer=new ResizeObserver(()=>{
   if(el.clientWidth===width)return;
   width=el.clientWidth;
   sizeComposerEditor(el);
  });
  observer.observe(el);
  return()=>observer.disconnect();
 },[listening,composerKey]);
 const hasContent=composerHasPayload(value,attachments.length);
 const canSend=!disabled&&!submitting&&!sendDisabled&&hasContent;
 const hasText=!!value.trim();
 const measuredContext=typeof contextPercent==='number'&&Number.isFinite(contextPercent)&&contextPercent>=0&&contextPercent<=100?Math.round(contextPercent):null;
 return <FrameA className={`pw-composer-host pw-compact-composer pw-squircle-composer ${className}`}>
  <section className="afa-composer" aria-label={label} data-composer-key={composerKey}>
   <div className="pw-composer-context-rail" aria-label="当前对话上下文">
    <SmoothComposerSurface radius={24} tone="context"/>
    <span className="pw-composer-context-icons">{contextStatus}</span>
    {contextStatus!==undefined&&contextPercent===undefined?<ComposerContextMeter composerKey={composerKey} draftText={value} attachmentCount={attachments.length}/>:<span className="pw-composer-context-usage" role="status" aria-label={measuredContext===null?'上下文占用暂无可核对数据':'上下文已使用 '+measuredContext+'%'} title={measuredContext===null?'上下文占用暂无可核对数据':'上下文占用 '+measuredContext+'%'}>
     <i className="pw-composer-context-ring" aria-hidden="true"/>{measuredContext!==null&&<span>{measuredContext}%</span>}
    </span>}
   </div>
   <div className="afa-card pw-composer-card" data-has-text={hasText} data-has-attachments={attachments.length>0}>
    <SmoothComposerSurface/>
    {attachments.length>0&&<div className="afa-filechips" aria-label="已附加文件" role="list">{attachments.map(f=>{
     const originalPreview=typeof f.thumbnailDataUrl==='string'?f.thumbnailDataUrl:typeof f.thumbnail==='string'?f.thumbnail:'';
     const thumbnail=/^(data:image\/(?:jpeg|png|webp|gif);base64,|blob:)/i.test(originalPreview)?originalPreview:'';
     const kind=String(f.mimeType||'').startsWith('image/')?'image':/\.(?:tsx?|jsx?|java|kt|py|go|rs|css|html|json|xml|yaml|yml|sh)$/i.test(f.name)?'code':'file';
     const progress=typeof f.progressPercent==='number'&&Number.isFinite(f.progressPercent)&&f.progressPercent>=0&&f.progressPercent<100?Math.round(f.progressPercent):null;
     return <span key={f.id} role="listitem" className="pw-composer-attachment" data-kind={kind}>
      <span className="pw-composer-file-preview">{thumbnail?<img src={thumbnail} alt="" loading="lazy"/>:<Icon name="file" size={21}/>}
       {progress!==null&&<svg viewBox="0 0 48 48" className="pw-composer-file-progress" aria-label={'上传 '+progress+'%'}><circle cx="24" cy="24" r="21" className="pw-composer-file-track"/><circle cx="24" cy="24" r="21" className="pw-composer-file-value" strokeDasharray={String(2*Math.PI*21)} strokeDashoffset={String((1-progress/100)*2*Math.PI*21)}/></svg>}
      </span>
      <span className="pw-composer-file-name" title={f.name}>{f.name}</span>
      {onRemoveAttachment&&<button type="button" aria-label={'移除 '+f.name} onClick={()=>onRemoveAttachment(f.id)}>×</button>}
     </span>;
    })}</div>}
    {onSelectModel&&<div className="pw-composer-model"><button type="button" className="afa-btn pw-model-trigger" onClick={onSelectModel}><Icon name="spark"/><span>{modelName||'选择模型'}</span><Icon name="chevron" size={12}/></button></div>}
    <div className={`pw-composer-row${controls?' pw-composer-with-controls':''}`}>
     {listening?<div className="afa-recording" role="status"><i aria-hidden="true"/><span>{voiceStatus||'正在听写…'}</span>{onDiscardVoice&&<ToolButton label="取消本次听写" icon="close" onClick={onDiscardVoice}/>}</div>:<textarea ref={editor} rows={1} aria-label={label} enterKeyHint="enter" placeholder={placeholder} value={value} disabled={disabled} onChange={e=>onChange(e.target.value)} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing&&e.nativeEvent.keyCode!==229&&(!matchMedia('(pointer: coarse)').matches||e.ctrlKey||e.metaKey)){e.preventDefault();if(canSend)onSend()}}}/>}
     {(toolbar||onAttach)&&<div className="pw-composer-leading">{toolbar}{onAttach&&<ToolButton label="添加附件" icon="plus" onClick={onAttach} disabled={submitting}/>}</div>}
     {controls&&<div className="pw-composer-controls">{controls}</div>}
     <div className="afa-toolbar">
      {onVoice&&!busy&&<ToolButton label={listening?'结束听写':'语音输入'} icon="mic" onClick={onVoice} disabled={disabled||submitting}/>}
      {hasContent&&(!busy||!onStop||hasContent)&&!listening&&<PressButton compact aria-label={submitting?'正在登记':sendLabel||(busy?'补充给当前工作':'发送')} disabled={!canSend} onClick={onSend}><Icon name="arrow" size={18}/></PressButton>}
      {busy&&onStop&&<button type="button" className="afa-stop pw-stop-motion" onClick={onStop} aria-label="停止当前回复"><span className="pw-stop-ring" aria-hidden="true"><ProgressCircle isIndeterminate size="md" aria-label="当前回复状态"/></span><span className="pw-stop-mark" aria-hidden="true">■</span></button>}
     </div>
    </div>
   </div>
   {children&&<div className="afa-composerbottom">{children}</div>}
   {status&&<div className="afa-muted pw-composer-status" role="status">{status}</div>}
  </section>
 </FrameA>
}
export function ModelPicker({models,value,onChange,open=true,onOpenChange,label='选择模型',toolbar}:{models:ModelOption[];value?:string;onChange:(id:string)=>void;open?:boolean;onOpenChange?:(open:boolean)=>void;label?:string;toolbar?:ReactNode}){
 const list=useRef<HTMLDivElement>(null);const[query,setQuery]=useState('');const shown=models.filter(x=>`${x.name} ${x.description||''}`.toLowerCase().includes(query.toLowerCase()));
 if(!open)return null;
 return <FrameB><div className="cb-model2"><Card className="cb-model2-menu"><div aria-label={label} onKeyDown={e=>{if(e.key==='Escape')onOpenChange?.(false)}}><div className="cb-model2-labels"><strong>{label}</strong></div><div className="cb-search"><Icon name="search"/><input aria-label="搜索模型" value={query} onChange={e=>setQuery(e.target.value)} placeholder="模型或来源"/></div>{toolbar}<div ref={list} className="cb-model2-options" role="listbox" aria-label={label} onKeyDown={e=>{if(!['ArrowUp','ArrowDown','Home','End'].includes(e.key))return;const buttons=Array.from(list.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')||[]);if(!buttons.length)return;e.preventDefault();const i=buttons.indexOf(document.activeElement as HTMLButtonElement);buttons[e.key==='Home'?0:e.key==='End'?buttons.length-1:(i+(e.key==='ArrowUp'?-1:1)+buttons.length)%buttons.length]?.focus()}}>{shown.map(x=><button key={x.id} type="button" role="option" aria-selected={value===x.id} disabled={x.disabled} onClick={()=>{onChange(x.id);onOpenChange?.(false)}}><span className="cb-model2-name"><span className="cb-model-initial">{x.name.slice(0,1)}</span><span><strong>{x.name}{value===x.id&&<Icon name="check" size={12}/>}</strong>{(x.description||x.reason)&&<small>{x.reason||x.description}</small>}</span></span></button>)}{!shown.length&&<p className="cb-empty">没有匹配的可用模型</p>}</div></div></Card></div></FrameB>
}
export type SteeringItem={id:string;text:string;status?:string;step?:string;editable?:boolean};
export function AgentSteering({data,title,onAction,value,onChange,onSubmit}:DataPanelProps&{value?:string;onChange?:(value:string)=>void;onSubmit?:(mode:'queue'|'steer')=>void}){
 const steps:Step[]=Array.isArray(data?.steps)?data.steps:[];const queued:SteeringItem[]=Array.isArray(data?.queued)?data.queued:records(data);const applied:SteeringItem[]=Array.isArray(data?.applied)?data.applied:[];
 return <FrameA><section className="afa-card afa-steering"><header><Icon/><span><strong>{title||data?.title||'补充给当前工作'}</strong>{data?.status&&<small>{statusLabel(String(data.status))}</small>}</span></header>{steps.length>0&&<ol className="afa-steerplan" aria-label="执行阶段">{steps.map(s=><li key={s.id} className={s.status==='running'?'afa-currentstep':s.status==='complete'?'afa-completedstep':''}><span className="afa-stepmark">{s.status==='complete'?'✓':s.status==='running'?<i/>:'○'}</span><div>{s.title||s.label}{applied.filter(x=>x.step===s.id).map(x=><small key={x.id} className="afa-appliednote">↳ {x.text}</small>)}</div></li>)}</ol>}<div className="afa-steerqueued"><div className="afa-label">待采用 <span>{data===undefined?'未载入':queued.length}</span></div>{queued.map(n=><div className="afa-queuednote" key={n.id}><span>{n.text}{n.status&&<small>{statusLabel(n.status)}</small>}</span>{onAction&&n.editable!==false&&<><button type="button" className="afa-minilink" onClick={()=>onAction('edit',n)}>编辑</button><ToolButton label="撤回这条补充" icon="close" onClick={()=>onAction('remove',n)}/></>}</div>)}{!queued.length&&<p className="afa-muted">{data===undefined?'补充队列尚未载入':'暂无待采用补充'}</p>}</div>{onSubmit&&onChange&&<footer><div className="afa-steerinput"><input aria-label="补充当前工作" value={value||''} onChange={e=>onChange(e.target.value)} onKeyDown={e=>{if(e.key==='Enter'&&!e.nativeEvent.isComposing&&value?.trim()){e.preventDefault();onSubmit(e.ctrlKey||e.metaKey?'steer':'queue')}}}/><PressButton compact disabled={!value?.trim()} aria-label="加入补充队列" onClick={()=>onSubmit('queue')}><Icon name="arrow"/></PressButton></div></footer>}</section></FrameA>
}
export function AIAnswer({text='',children,sources=[],onOpenSource,onCopy,onSpeak,speaking,busy,actions,showActions=true,onOpenLink,onCopyCode}:{text?:string;children?:ReactNode;sources?:any[];onOpenSource?:(source:any)=>void;onCopy?:(text?:string)=>void|Promise<unknown>;onSpeak?:(speaking:boolean)=>void;speaking?:boolean;busy?:boolean;actions?:ReactNode;showActions?:boolean;onOpenLink?:(url:string)=>void|Promise<unknown>;onCopyCode?:(text:string)=>void|Promise<unknown>}){
 return <FrameA><article className="afa-card afa-answer" aria-busy={busy}><div className="afa-answerbody">{sources.length?<CitedAnswer text={text} sources={sources} onOpenSource={onOpenSource} onOpenLink={onOpenLink} onCopyCode={onCopyCode}/>:children||<><MarkdownBody text={text} onOpenLink={onOpenLink} onCopyCode={onCopyCode}/>{busy&&<i className="afa-cursor"/>}</>}</div>{showActions&&<footer className="afa-actions"><MessageActions text={text} onCopy={onCopy} onSpeak={onSpeak} speaking={speaking}/>{actions}</footer>}</article></FrameA>
}
