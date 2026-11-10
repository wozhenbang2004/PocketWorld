// Adapted from Affect UI 37b91fa / MIT, components/thinking-loader-clean.tsx.
// Retains its fixed icon slot, incoming/outgoing labels and fitted label width.
// The native reply owns every phase: no demo clock, automatic advance or success.
import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {IconClock,IconError,IconPause} from '../../icons';
import {useMotionAllowed,useVisible} from '../core';
import {cssEase,motionDuration,motionEase} from '../motion-tokens';
import './reply-activity.css';

export type ReplyActivityState='thinking'|'replying'|'waiting'|'stopping'|'settling'|'error';
type Label={key:number;text:string};
const rays=Array.from({length:8},(_,index)=>index);
const iconFor=(state:ReplyActivityState)=>state==='thinking'||state==='replying'?'working':state==='stopping'?'stopping':state==='error'?'error':'waiting';

/** A persistent activity line. Only its presentation changes; text and status
 * always describe the current native receipt, including an empty failed draft. */
export function ReplyPhaseStatus({label,state,active=true}:{label:string;state:ReplyActivityState;active?:boolean}){
 const allowed=useMotionAllowed();
 const [shown,setShown]=useState<Label>({key:0,text:label}),[leaving,setLeaving]=useState<Label|null>(null);
 const root=useRef<HTMLSpanElement>(null),box=useRef<HTMLSpanElement>(null),current=useRef<HTMLSpanElement>(null),outgoing=useRef<HTMLSpanElement>(null),icon=useRef<HTMLSpanElement>(null);
 const visible=useVisible(root),enabled=allowed&&active&&visible;
 const previous=useRef<{label:string;icon:string;enabled:boolean}|null>(null),animations=useRef(new Map<string,Animation>()),generation=useRef(0),measured=useRef(false);
 const glyph=iconFor(state);
 // As in the source, capture the prior pure label before committing the next.
 // The current label node itself stays mounted across every streamed phase.
 if(shown.text!==label){setLeaving(enabled&&previous.current?.enabled?shown:null);setShown({key:shown.key+1,text:label})}

 const fit=useRef((animate:boolean)=>{});
 fit.current=(animate:boolean)=>{
  const r=root.current,b=box.current,c=current.current;if(!r||!b||!c)return;
  // Use layout pixels, including the currently visible interpolated width.
  // Restoring the old target width here would jump during a rapid phase change.
  const visible=parseFloat(getComputedStyle(b).width)||b.offsetWidth;
  const full=c.scrollWidth+1,parent=r.parentElement;
  b.style.transition='none';b.style.width=`${full}px`;
  const pad=parent?getComputedStyle(parent):null;
  const inner=parent&&pad?parent.clientWidth-parseFloat(pad.paddingLeft)-parseFloat(pad.paddingRight):0;
  const room=parent?Math.floor(inner-(r.offsetWidth-b.offsetWidth)):full;
  const cut=room>0&&full>room,target=cut?room:full;
  r.toggleAttribute('data-cut',cut);
  if(animate&&measured.current&&Math.abs(visible-target)>.5){
   b.style.width=`${visible}px`;b.getBoundingClientRect();
   b.style.transition=`width ${motionDuration.layout}ms ${cssEase(motionEase.settle)}`;
  }
  b.style.width=`${target}px`;measured.current=true;
 };

 useLayoutEffect(()=>{
  const before=previous.current;previous.current={label,icon:glyph,enabled};
  const ticket=++generation.current;
  const sample=(name:string,node:HTMLElement|null)=>{
   if(!node||animations.current.get(name)?.playState!=='running')return null;
   const style=getComputedStyle(node);return {opacity:style.opacity,transform:style.transform,filter:style.filter};
  };
  const labelFrame=sample('label',current.current),iconFrame=sample('icon',icon.current);
  animations.current.forEach(animation=>animation.cancel());animations.current.clear();
  const animate=enabled&&before?.enabled===true;
  fit.current(animate);
  if(!animate||!before){setLeaving(null);return}
  const play=(name:string,node:HTMLElement,frames:Keyframe[],duration:number,complete?:()=>void)=>{
   const animation=node.animate(frames,{duration,easing:cssEase(motionEase.settle)});
   animation.id=`pw-reply-phase-${name}`;animations.current.set(name,animation);
   animation.onfinish=()=>{
    if(generation.current!==ticket||animations.current.get(name)!==animation)return;
    animations.current.delete(name);animation.cancel();complete?.();
   };
  };
  if(before.label!==label&&current.current){
   play('label',current.current,[labelFrame||{opacity:0,transform:'translateY(70%)',filter:'blur(3px)'},{opacity:1,transform:'translateY(0)',filter:'blur(0px)'}],motionDuration.enter);
   if(outgoing.current)play('outgoing',outgoing.current,[labelFrame||{opacity:1,transform:'translateY(0)',filter:'blur(0px)'},{opacity:0,transform:'translateY(-70%)',filter:'blur(3px)'}],motionDuration.exit,()=>setLeaving(null));
  }else setLeaving(null);
  if(before.icon!==glyph&&icon.current)play('icon',icon.current,[iconFrame||{opacity:.35,transform:'scale(.75)'},{opacity:1,transform:'scale(1)'}],motionDuration.feedback);
 },[label,glyph,enabled]);
 useEffect(()=>{
  const parent=root.current?.parentElement;if(!parent)return;
  let mounted=true;
  const refit=()=>{if(mounted)fit.current(false)};
  const observer=new ResizeObserver(refit);observer.observe(parent);
  // A loaded local font can change the label without resizing its flex parent.
  // Refit its current text directly; font readiness never replays a phase.
  const fonts=document.fonts;
  fonts?.addEventListener('loadingdone',refit);
  void fonts?.ready.then(refit);
  return()=>{mounted=false;observer.disconnect();fonts?.removeEventListener('loadingdone',refit)};
 },[]);
 useEffect(()=>()=>{generation.current++;animations.current.forEach(animation=>animation.cancel());animations.current.clear()},[]);

 return <span ref={root} className="pw-reply-status" data-state={state} data-motion={enabled||undefined} data-active={active||undefined} role={active?'status':undefined} aria-live={active?'polite':undefined} aria-atomic={active||undefined}>
  <span ref={icon} className="pw-reply-status-icon" aria-hidden="true">
   {glyph==='working'?<svg className="pw-reply-status-spinner" viewBox="0 0 16 16">{rays.map(index=><line key={index} x1="8" y1="1.75" x2="8" y2="4.75" transform={`rotate(${index*45} 8 8)`} style={{animationDelay:`${(index-8)*100}ms`}}/>)}</svg>:glyph==='error'?<IconError size={14}/>:glyph==='stopping'?<IconPause size={14}/>:<IconClock size={14}/>}
  </span>
  <span ref={box} className="pw-reply-status-text">
   {leaving&&<span key={leaving.key} ref={outgoing} className="pw-reply-status-label pw-reply-status-outgoing" aria-hidden="true" inert dir="auto">{leaving.text}</span>}
   <span ref={current} className="pw-reply-status-label" title={label} dir="auto">{label}</span>
  </span>
 </span>;
}
