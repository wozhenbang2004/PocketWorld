// PW-38/39/40/41/42: source components, controlled product adapters.
import {useEffect,useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import {Toaster as SourceToaster,toast,type ToasterProps} from 'sonner';
import {ThinkingLoader as SourceThinkingLoader} from './adapted/thinking-loader-clean';
import {Skeleton,ShimmerText} from './adapted/shimmer-loader';
import {useMotionAllowed} from './core';
export {toast,Skeleton,ShimmerText};
export {ProgressBar,ProgressCircle,ProgressTrack} from './adapted/boardui-progress';
export function Toaster(props:ToasterProps){return <SourceToaster {...props} className={`pw-toast-host ${props.className||''}`} richColors={false} position={props.position||'bottom-center'} offset={props.offset??'calc(env(safe-area-inset-bottom) + 112px)'} closeButton/>}
export function ThinkingLoader({phase='正在处理',done=false,doneLabel='处理已结束',durationMs,className=''}:{phase?:string|number;phases?:string[];done?:boolean;doneLabel?:string;durationMs?:number;className?:string}){
 const text=typeof phase==='string'?phase:'正在处理';
 return <SourceThinkingLoader phases={[text]} phase={0} done={done} doneLabel={doneLabel} durationMs={durationMs} size="sm" variant="plain" className={`pw-thinking-loader ${className}`}/>
}
export function ShimmerLoader({children,loading=true,width='100%',height=14,className=''}:{children?:ReactNode;loading?:boolean;width?:number|string;height?:number;className?:string}){return loading?<div className={`pw-shimmer ${className}`} aria-busy="true" aria-label="正在载入"><Skeleton width={width} height={height}/></div>:<>{children}</>}
/** Pure presentation only: keep editors, WebViews and executing owners outside this transition. */
export function BlurCrossfade({identity,children,className=''}:{identity:string|number;children:ReactNode;className?:string}){
 const allowed=useMotionAllowed(),content=useRef<HTMLDivElement>(null),outgoing=useRef<HTMLDivElement>(null);
 const last=useRef({identity,children}),lastIdentity=useRef(identity),playing=useRef<Animation[]>([]);
 const[shown,setShown]=useState(identity),[previous,setPrevious]=useState<{identity:string|number;children:ReactNode}|null>(null),[height,setHeight]=useState<number>();
 if(shown!==identity){setShown(identity);setPrevious(allowed?last.current:null)}last.current={identity,children};
 useLayoutEffect(()=>{
  const node=content.current,changed=lastIdentity.current!==identity;lastIdentity.current=identity;
  const interrupted=node&&playing.current.some(animation=>animation.playState==='running');
  const frame=interrupted&&node?getComputedStyle(node):null;
  const start=frame?{opacity:frame.opacity,filter:frame.filter,transform:frame.transform}:{opacity:0,filter:'blur(8px)',transform:'translateY(8px)'};
  playing.current.forEach(animation=>animation.cancel());playing.current=[];
  if(!allowed){setPrevious(null);return}
  if(!node||!changed)return;
  const incoming=node.animate([start,{opacity:1,filter:'blur(0px)',transform:'translateY(0px)'}],{duration:280,easing:'cubic-bezier(.22,.8,.2,1)'});
  playing.current.push(incoming);incoming.onfinish=()=>{incoming.cancel();playing.current=playing.current.filter(animation=>animation!==incoming)};
  const old=outgoing.current;
  if(old){
   const leaving=old.animate([{opacity:interrupted?start.opacity:1,filter:interrupted?start.filter:'blur(0px)',transform:interrupted?start.transform:'translateY(0px)'},{opacity:0,filter:'blur(7px)',transform:'translateY(-6px)'}],{duration:220,easing:'cubic-bezier(.22,.8,.2,1)'});
   playing.current.push(leaving);
   leaving.onfinish=()=>{leaving.cancel();playing.current=playing.current.filter(animation=>animation!==leaving);setPrevious(value=>lastIdentity.current===identity?null:value)};
  }
 },[identity,allowed]);
 useLayoutEffect(()=>{
  const node=content.current;if(!node)return;const measure=()=>setHeight(node.offsetHeight);measure();
  const observer=new ResizeObserver(measure);observer.observe(node);return()=>observer.disconnect();
 },[]);
 useEffect(()=>()=>playing.current.forEach(animation=>animation.cancel()),[]);
 return <div className={`pw-crossfade bmx-crossfade-size ${className}`} style={{height,transition:allowed?undefined:'none'}}>
  {previous&&<div className="bmx-crossfade-content bmx-crossfade-out" style={{animation:'none'}} aria-hidden="true" inert ref={outgoing}>{previous.children}</div>}
  <div className="bmx-crossfade-content" style={{animation:'none'}} ref={content}>{children}</div>
 </div>;
}
