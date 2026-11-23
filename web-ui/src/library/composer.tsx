// PW-02/03/04/05: controlled ports of actual saved Affect library v3 JSX/CSS.
// Source controls retained; the host separates full-width editing from its toolbar.
import {forwardRef,useContext,useEffect,useLayoutEffect,useRef,useState,type ReactElement,type ReactNode,type RefObject} from 'react';
import {AnimatePresence,PresenceContext,animate,motion,useMotionValue,usePresence} from 'motion/react';
import {IconCheck,IconCircle,IconClose,IconCode,IconImage,IconInfo,IconReply,IconStop} from '../icons';
import type {ChatComposerProps,ModelOption,DataPanelProps,Step} from './types';
import {FrameA,FrameB,Card,Icon,ToolButton,statusLabel,records,textOf,useMotionAllowed} from './core';
import {PressButton} from './press';
import {MessageActions} from './message';
import {CitedAnswer} from './context';
import {MarkdownBody} from './MarkdownBody';
import {ProgressCircle} from './adapted/boardui-progress';
import {ComposerContextMeter} from '../ComposerContextMeter';
import {SmoothComposerSurface} from './SmoothComposerSurface';
import {createTextareaMeasure} from './adapted/textarea-measure';
import {composerHasPayload} from '../composer-send-policy';
import {exactFileTarget} from '../attachment-workspace';
import {registerOverlayBack} from '../overlay-back';
import {Button} from '../forms-library/boardui/components/base/buttons/button';
import {Popover,PopoverHeader,PopoverTrigger} from '../shell-library/adapted/boardui/components/base/popover/popover';
import {cssEase,motionDuration,motionEase,motionTransition} from './motion-tokens';

// Each retained exit reads the live motion preference itself: its last parent
// props are deliberately frozen by AnimatePresence until the exit completes.
function useComposerPresence(lift=0){
 const allowed=useMotionAllowed(),initial=useContext(PresenceContext)?.initial!==false;
 const [present,safeToRemove]=usePresence(),remove=useRef(safeToRemove);
 const opacity=useMotionValue(allowed&&initial?0:1),y=useMotionValue(allowed&&initial?lift:0);
 useLayoutEffect(()=>{remove.current=safeToRemove},[safeToRemove]);
 useLayoutEffect(()=>{
  if(!allowed){opacity.jump(present?1:0);y.jump(0);if(!present)remove.current?.();return}
  let cancelled=false;
  const transition=present?motionTransition.enter:motionTransition.exit;
  const animations=[animate(opacity,present?1:0,transition),animate(y,present?0:-lift,transition)];
  void Promise.all(animations).then(()=>{if(!cancelled&&!present)remove.current?.()});
  return()=>{cancelled=true;animations.forEach(animation=>animation.stop())};
 },[allowed,present,lift,opacity,y]);
 return {allowed,present,style:{opacity,y}};
}

const ComposerControl=forwardRef<HTMLSpanElement,{children:ReactNode}>(function ComposerControl({children},ref){
 const {allowed,present,style}=useComposerPresence();
 return <motion.span ref={ref} className="pw-composer-motion-control" layout={allowed?'position':false}
  style={style} transition={allowed?motionTransition.layout:motionTransition.instant}
  inert={!present} aria-hidden={!present||undefined}
  onClickCapture={event=>{if(!present){event.preventDefault();event.stopPropagation()}}}>{children}</motion.span>;
});

type ComposerFile = NonNullable<ChatComposerProps['attachments']>[number];
type AttachmentInformation = {pin:string;kind:string;bytes:number;position?:number};
function attachmentInformation(file:ComposerFile,scope:string|undefined,position:number|undefined):AttachmentInformation|undefined {
 const target=exactFileTarget(file),bytes=file.size;
 const kind=file.mimeType==='application/pdf'?'PDF':
  file.mimeType==='application/vnd.openxmlformats-officedocument.wordprocessingml.document'?'Word 文档':'';
 if(!scope||!target||!kind||typeof file.name!=='string'||!file.name.trim()||typeof bytes!=='number'||!Number.isSafeInteger(bytes)||bytes<1)return;
 return {pin:JSON.stringify([scope,target.fileId,target.sha256,file.name,kind,bytes,position]),kind,bytes,position};
}

function ComposerAttachment({file,onRemove,info,showInfo,onInfoChange}:{file:ComposerFile;onRemove?:ChatComposerProps['onRemoveAttachment'];
 info?:AttachmentInformation;showInfo:boolean;onInfoChange:(open:boolean)=>void}){
 const {allowed,present,style}=useComposerPresence(6);
 const originalPreview=typeof file.thumbnailDataUrl==='string'?file.thumbnailDataUrl:typeof file.thumbnail==='string'?file.thumbnail:'';
 const thumbnail=/^(data:image\/(?:jpeg|png|webp|gif);base64,|blob:)/i.test(originalPreview)?originalPreview:'';
 const kind=String(file.mimeType||'').startsWith('image/')?'image':/\.(?:tsx?|jsx?|java|kt|py|go|rs|css|html|json|xml|yaml|yml|sh)$/i.test(file.name)?'code':'file';
 const progress=typeof file.progressPercent==='number'&&Number.isFinite(file.progressPercent)&&file.progressPercent>=0&&file.progressPercent<100?Math.round(file.progressPercent):null;
 const information=present?info:undefined;
 const position=information?.position===undefined?'':` · 草稿中第 ${information.position} 项`;
 return <motion.span role="listitem" className="pw-composer-attachment" data-kind={kind} data-attachment-info={!!information||undefined} layout={allowed?'position':false}
  style={style} transition={allowed?motionTransition.layout:motionTransition.instant} inert={!present} aria-hidden={!present||undefined}>
  <span className="pw-composer-file-preview">{thumbnail?<img src={thumbnail} alt="" loading="lazy"/>:kind==='image'?<IconImage size={21} aria-hidden="true"/>:kind==='code'?<IconCode size={21} aria-hidden="true"/>:<Icon name="file" size={21}/>}
   {progress!==null&&<svg viewBox="0 0 48 48" className="pw-composer-file-progress" aria-label={'上传 '+progress+'%'}><circle cx="24" cy="24" r="21" className="pw-composer-file-track"/><circle cx="24" cy="24" r="21" className="pw-composer-file-value" strokeDasharray={String(2*Math.PI*21)} strokeDashoffset={String((1-progress/100)*2*Math.PI*21)}/></svg>}
  </span>
  {information?<PopoverTrigger key={information.pin} isOpen={showInfo} onOpenChange={onInfoChange}>
   <button type="button" className="pw-composer-file-info-trigger" aria-label={`附件信息：${file.name}${position}`}>
    <span className="pw-composer-file-name">{file.name}</span><IconInfo size={13} aria-hidden="true"/>
   </button>
   <Popover placement="top" motionProfile="quick" dialogClassName="pw-composer-file-info-dialog">
    {({close})=><>
     <PopoverHeader title="附件信息"/>
     <bdi className="pw-composer-file-info-name">{file.name}</bdi>
     <p className="pw-composer-file-info-meta">{information.kind} · {information.bytes.toLocaleString('zh-CN')} 字节{position}</p>
     <Button type="button" variant="ghost" size="small" className="self-end" onClick={close}>关闭</Button>
    </>}
   </Popover>
  </PopoverTrigger>:<span className="pw-composer-file-name" title={file.name}>{file.name}</span>}
  {information?.position!==undefined&&<span className="pw-composer-file-position" aria-hidden="true">{information.position}</span>}
  {onRemove&&<button type="button" className="pw-composer-attachment-remove" disabled={!present} aria-label={'移除 '+file.name+position} onClick={()=>{if(present)onRemove(file.id)}}><IconClose size={12} aria-hidden="true"/></button>}
 </motion.span>;
}

function ComposerAttachments({files,onRemove,informationScope}:{files:ComposerFile[];onRemove?:ChatComposerProps['onRemoveAttachment'];informationScope?:string}){
 const allowed=useMotionAllowed(),initial=useContext(PresenceContext)?.initial!==false;
 const [present,safeToRemove]=usePresence(),remove=useRef(safeToRemove),content=useRef<HTMLDivElement>(null);
 const [height,setHeight]=useState(0),animatedHeight=useMotionValue(0),opacity=useMotionValue(allowed&&initial?0:1);
 const [reviewPin,setReviewPin]=useState<string|null>(null);
 const rows=files.map((file,index)=>({file,info:attachmentInformation(file,present?informationScope:undefined,
  files.some(other=>other.id!==file.id&&other.name===file.name)?index+1:undefined)}));
 const shownPin=reviewPin&&rows.some(row=>row.info?.pin===reviewPin)?reviewPin:null;
 useLayoutEffect(()=>{
  if(reviewPin&&!shownPin)setReviewPin(current=>current===reviewPin?null:current);
 },[reviewPin,shownPin]);
 useLayoutEffect(()=>{
  if(!shownPin)return;
  const close=()=>setReviewPin(current=>current===shownPin?null:current);
  const hidden=()=>{if(document.hidden)close();};
  const releaseBack=registerOverlayBack(close);
  document.addEventListener('visibilitychange',hidden);
  return()=>{releaseBack();document.removeEventListener('visibilitychange',hidden);};
 },[shownPin]);
 const measured=useRef(false);
 useLayoutEffect(()=>{remove.current=safeToRemove},[safeToRemove]);
 useLayoutEffect(()=>{
  const node=content.current;if(!node)return;
  const measure=()=>{
   const next=node.getBoundingClientRect().height+(parseFloat(getComputedStyle(node).marginBottom)||0);
   if(!measured.current){measured.current=true;if(!initial||!allowed)animatedHeight.jump(next)}
   setHeight(next);
  };
  measure();const observer=new ResizeObserver(measure);observer.observe(node);return()=>observer.disconnect();
 },[]);
 useLayoutEffect(()=>{
  const target=present?height:0;
  if(!allowed){animatedHeight.jump(target);opacity.jump(present?1:0);if(!present)remove.current?.();return}
  let cancelled=false;
  const transition=present?motionTransition.settle:motionTransition.exit;
  const animations=[animate(animatedHeight,target,transition),animate(opacity,present?1:0,transition)];
  void Promise.all(animations).then(()=>{if(!cancelled&&!present)remove.current?.()});
  return()=>{cancelled=true;animations.forEach(animation=>animation.stop())};
 },[height,present,allowed,animatedHeight,opacity]);
 return <motion.div className="pw-composer-attachments-reveal" inert={!present} aria-hidden={!present||undefined} style={{height:animatedHeight,opacity}}>
  <div ref={content} className="afa-filechips" aria-label="已附加文件" role="list"><AnimatePresence initial={false}>
   {rows.map(({file,info})=><ComposerAttachment key={file.id} file={file} onRemove={present?onRemove:undefined}
    info={info} showInfo={!!info&&info.pin===shownPin} onInfoChange={open=>{
     if(info)setReviewPin(current=>open&&!document.hidden?info.pin:current===info.pin?null:current);
    }}/>)}
  </AnimatePresence></div>
 </motion.div>;
}

function sizeComposerEditor(measurement:ReturnType<typeof createTextareaMeasure>,allowed:boolean,animation:RefObject<Animation|null>,lastTarget:RefObject<number>){
 const size=measurement.measure();
 if(!size){if(!allowed){animation.current?.cancel();animation.current=null}return}
 const {target,overflow,currentHeight}=size,editor=measurement.editor;
 const overflowY=overflow?'auto':'hidden';
 if(editor.style.overflowY!==overflowY)editor.style.overflowY=overflowY;
 if(lastTarget.current!==target)editor.style.setProperty('height',`${target}px`);
 if(!allowed){animation.current?.cancel();animation.current=null;lastTarget.current=target;return}
 if(lastTarget.current===target)return;
 const hadLayout=lastTarget.current>0;lastTarget.current=target;
 animation.current?.cancel();animation.current=null;
 if(!hadLayout||Math.abs(currentHeight-target)<1)return;
 const next=editor.animate([{height:`${currentHeight}px`},{height:`${target}px`}],{duration:motionDuration.feedback,easing:cssEase(motionEase.settle)});
 next.id='pw-composer-resize';animation.current=next;
 next.onfinish=()=>{if(animation.current===next){animation.current=null;next.cancel()}};
}

export function ChatComposer({value,onChange,onSend,onStop,busy,submitting,disabled,placeholder='输入目标，或补充上下文…',attachments=[],onRemoveAttachment,attachmentInformationScope,onAttach,onVoice,listening,voiceStatus,onDiscardVoice,modelName,onSelectModel,toolbar,controls,contextStatus,contextPercent,children,status,continuationMode,label='工作输入',composerKey,className='',sendDisabled=false,sendLabel,visibleSendLabel,primaryInputAction,readOnly=false}:ChatComposerProps&{sendDisabled?:boolean;sendLabel?:string;visibleSendLabel?:string;primaryInputAction?:ReactElement;readOnly?:boolean}){
 const editor=useRef<HTMLTextAreaElement>(null);
 const allowed=useMotionAllowed(),resizeAnimation=useRef<Animation|null>(null),editorHeight=useRef(0);
 const editorOwner=useRef(composerKey),measurement=useRef<ReturnType<typeof createTextareaMeasure>|null>(null);
 useLayoutEffect(()=>{
  if(editorOwner.current!==composerKey||measurement.current?.editor!==editor.current){
   editorOwner.current=composerKey;
   resizeAnimation.current?.cancel();resizeAnimation.current=null;editorHeight.current=0;
   measurement.current?.destroy();measurement.current=null;
  }
  if(!editor.current)return;
  measurement.current??=createTextareaMeasure(editor.current);
  sizeComposerEditor(measurement.current,allowed,resizeAnimation,editorHeight);
 },[value,listening,composerKey,allowed]);
 useEffect(()=>()=>{resizeAnimation.current?.cancel();measurement.current?.destroy();measurement.current=null},[]);
 useLayoutEffect(()=>{
  const el=editor.current;
  if(!el)return;
  let width=-1,disposed=false;
  const resize=()=>{if(!disposed&&measurement.current?.editor===el)sizeComposerEditor(measurement.current,false,resizeAnimation,editorHeight)};
  const observer=typeof ResizeObserver==='undefined'?null:new ResizeObserver(entries=>{
   const entry=entries.find(entry=>entry.target===el);if(!entry)return;
   const nextWidth=entry.borderBoxSize[0]?.inlineSize??entry.contentRect.width;
   if(nextWidth===width)return;
   width=nextWidth;
   resize();
  });
  observer?.observe(el);
  const fonts=el.ownerDocument.fonts;
  fonts?.addEventListener('loadingdone',resize);
  void fonts?.ready.then(resize);
  return()=>{disposed=true;observer?.disconnect();fonts?.removeEventListener('loadingdone',resize)};
 },[listening,composerKey]);
 const hasContent=composerHasPayload(value,attachments.length);
 // An exact local intake action replaces Send, including its keyboard shortcut.
 const canSend=!primaryInputAction&&!disabled&&!readOnly&&!submitting&&!sendDisabled&&hasContent;
 const hasText=!!value.trim();
 const measuredContext=typeof contextPercent==='number'&&Number.isFinite(contextPercent)&&contextPercent>=0&&contextPercent<=100?Math.round(contextPercent):null;
 return <FrameA className={`pw-composer-host pw-squircle-composer ${className}`}>
  <section className="afa-composer" aria-label={label} data-composer-key={composerKey}>
   <div className="afa-card pw-composer-card" data-has-text={hasText} data-has-attachments={attachments.length>0}>
    <SmoothComposerSurface radius={24}/>
    <AnimatePresence key={`attachments:${composerKey}`} initial={false}>{attachments.length>0&&<ComposerAttachments key="attachments" files={attachments} onRemove={onRemoveAttachment}
     informationScope={!disabled&&!submitting&&!readOnly?attachmentInformationScope:undefined}/>}</AnimatePresence>
    {onSelectModel&&<div className="pw-composer-model"><button type="button" className="afa-btn pw-model-trigger" onClick={onSelectModel}><Icon name="spark"/><span>{modelName||'选择模型'}</span><Icon name="chevron" size={12}/></button></div>}
    <div className="pw-composer-row">
     {listening?<div className="afa-recording" role="status"><i aria-hidden="true"/><span>{voiceStatus||'正在听写…'}</span>{onDiscardVoice&&<ToolButton label="取消本次听写" icon="close" onClick={onDiscardVoice}/>}</div>:<textarea key={composerKey} ref={editor} rows={1} aria-label={label} enterKeyHint="enter" placeholder={placeholder} value={value} disabled={disabled} readOnly={readOnly} onChange={e=>onChange(e.target.value)} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing&&e.nativeEvent.keyCode!==229&&(!matchMedia('(pointer: coarse)').matches||e.ctrlKey||e.metaKey)){e.preventDefault();if(!e.repeat&&canSend)onSend()}}}/>}
    </div>
    <div className="pw-composer-toolbar">
     <div className="pw-composer-leading">{toolbar}{onAttach&&<ToolButton label="添加附件" icon="plus" onClick={onAttach} disabled={submitting}/>}
      <div className="pw-composer-context-slot" aria-label="当前对话上下文">
       {contextStatus!==undefined&&contextPercent===undefined?<ComposerContextMeter composerKey={composerKey} draftText={value} attachmentCount={attachments.length} contextStatus={contextStatus}/>:<span className="pw-composer-context-usage" data-available={measuredContext!==null} role="status" aria-label={measuredContext===null?'上下文占用暂无可核对数据':'上下文已使用 '+measuredContext+'%'} title={measuredContext===null?'上下文占用暂无可核对数据':'上下文占用 '+measuredContext+'%'}>
        <svg className="pw-context-meter-ring" width="16" height="16" viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="7.5" className="pw-context-meter-track"/>{measuredContext!==null&&measuredContext>0&&<circle cx="10" cy="10" r="7.5" className="pw-context-meter-value" pathLength="100" strokeDasharray={`${measuredContext} 100`} transform="rotate(-90 10 10)"/>}</svg>
       </span>}
      </div>
     </div>
     <div className="pw-composer-trailing">
      {controls&&<motion.div layout={allowed?'position':false} transition={allowed?motionTransition.layout:motionTransition.instant} className="pw-composer-controls">{controls}</motion.div>}
      <div className="afa-toolbar">
       {primaryInputAction??<AnimatePresence key={`controls:${composerKey}`} initial={false} mode="popLayout">
        {onVoice&&!busy&&(!hasContent||listening)&&<ComposerControl key="voice"><ToolButton label={listening?'结束听写':'语音输入'} icon="mic" onClick={onVoice} disabled={disabled||submitting}/></ComposerControl>}
        {hasContent&&!listening&&<ComposerControl key="send"><PressButton compact={!visibleSendLabel} aria-label={submitting?(visibleSendLabel?'正在核对':'正在登记'):sendLabel||visibleSendLabel||(busy?'补充给当前工作':'发送')} disabled={!canSend} onClick={onSend}>{submitting?<ProgressCircle isIndeterminate size="sm" aria-label={visibleSendLabel?'正在核对原目标':'正在登记输入'}/>:visibleSendLabel||<Icon name="arrow" size={18}/>}</PressButton></ComposerControl>}
        {busy&&onStop&&<ComposerControl key="stop"><button type="button" className="afa-stop pw-stop-motion" onClick={onStop} aria-label="停止当前回复"><span className="pw-stop-ring" aria-hidden="true"><ProgressCircle isIndeterminate size="md" aria-label="当前回复状态"/></span><span className="pw-stop-mark" aria-hidden="true"><IconStop size={12}/></span></button></ComposerControl>}
       </AnimatePresence>}
      </div>
     </div>
    </div>
   </div>
   {busy&&hasContent&&!listening&&!submitting&&!status&&continuationMode&&<p className="pw-composer-continuation">{continuationMode==='steer'?'补充会在下个安全步骤加入。':'补充会在本轮结束后处理。'}</p>}
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
 return <FrameA><section className="afa-card afa-steering"><header><Icon/><span><strong>{title||data?.title||'补充给当前工作'}</strong>{data?.status&&<small>{statusLabel(String(data.status))}</small>}</span></header>{steps.length>0&&<ol className="afa-steerplan" aria-label="执行阶段">{steps.map(s=><li key={s.id} className={s.status==='running'?'afa-currentstep':s.status==='complete'?'afa-completedstep':''}><span className="afa-stepmark">{s.status==='complete'?<IconCheck size={10} aria-hidden="true"/>:s.status==='running'?<i/>:<IconCircle size={7} aria-hidden="true"/>}</span><div>{s.title||s.label}{applied.filter(x=>x.step===s.id).map(x=><small key={x.id} className="afa-appliednote"><IconReply size={12} aria-hidden="true" style={{display:'inline-block',verticalAlign:'text-bottom',marginRight:4}}/>{x.text}</small>)}</div></li>)}</ol>}<div className="afa-steerqueued"><div className="afa-label">待采用 <span>{data===undefined?'未载入':queued.length}</span></div>{queued.map(n=><div className="afa-queuednote" key={n.id}><span>{n.text}{n.status&&<small>{statusLabel(n.status)}</small>}</span>{onAction&&n.editable!==false&&<><button type="button" className="afa-minilink" onClick={()=>onAction('edit',n)}>编辑</button><ToolButton label="撤回这条补充" icon="close" onClick={()=>onAction('remove',n)}/></>}</div>)}{!queued.length&&<p className="afa-muted">{data===undefined?'补充队列尚未载入':'暂无待采用补充'}</p>}</div>{onSubmit&&onChange&&<footer><div className="afa-steerinput"><input aria-label="补充当前工作" value={value||''} onChange={e=>onChange(e.target.value)} onKeyDown={e=>{if(e.key==='Enter'&&!e.nativeEvent.isComposing&&value?.trim()){e.preventDefault();onSubmit(e.ctrlKey||e.metaKey?'steer':'queue')}}}/><PressButton compact disabled={!value?.trim()} aria-label="加入补充队列" onClick={()=>onSubmit('queue')}><Icon name="arrow"/></PressButton></div></footer>}</section></FrameA>
}
export function AIAnswer({text='',children,sources=[],onOpenSource,onCopy,onSpeak,speaking,busy,actions,showActions=true,onOpenLink,onCopyCode}:{text?:string;children?:ReactNode;sources?:any[];onOpenSource?:(source:any)=>void;onCopy?:(text?:string)=>void|Promise<unknown>;onSpeak?:(speaking:boolean)=>void;speaking?:boolean;busy?:boolean;actions?:ReactNode;showActions?:boolean;onOpenLink?:(url:string)=>void|Promise<unknown>;onCopyCode?:(text:string)=>void|Promise<unknown>}){
 return <FrameA><article className="afa-card afa-answer" aria-busy={busy}><div className="afa-answerbody">{sources.length?<CitedAnswer text={text} sources={sources} onOpenSource={onOpenSource} onOpenLink={onOpenLink} onCopyCode={onCopyCode}/>:children||<><MarkdownBody text={text} onOpenLink={onOpenLink} onCopyCode={onCopyCode}/>{busy&&<i className="afa-cursor"/>}</>}</div>{showActions&&<footer className="afa-actions"><MessageActions text={text} onCopy={onCopy} onSpeak={onSpeak} speaking={speaking}/>{actions}</footer>}</article></FrameA>
}
