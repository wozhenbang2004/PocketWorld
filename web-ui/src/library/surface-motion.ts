// Product wiring for the retained Ovio stage easing and Affect arrival/disclosure
// patterns. Animate the current DOM only; never keep a second live workspace.
import {useLayoutEffect,useRef,useState,type RefObject} from 'react';
import {ease,motionTokens} from '../shell-library/adapted/ovio/lib/motion';
import {useReducedMotion} from './core';

const arrivalTiming:KeyframeAnimationOptions={
 duration:motionTokens.minimal.slow.duration*1000,
 easing:`cubic-bezier(${ease.stage.join(',')})`,
};
const arrivalFrames:Keyframe[]=[{opacity:0,transform:'translateY(6px)'},{opacity:1,transform:'translateY(0)'}];

/** Suspend decorative CSS loops while the host document is in the background. */
export function useMotionLifecycle(){
 useLayoutEffect(()=>{
  const root=document.documentElement;
  const paused=new Set<Element>();
  const pauseLoops=()=>{
   if(!document.hidden)return;
   for(const animation of document.getAnimations()){
    if(animation instanceof CSSAnimation&&animation.effect instanceof KeyframeEffect&&animation.effect.getTiming().iterations===Infinity&&animation.playState==='running'){
     const target=animation.effect.target;
     if(target){target.setAttribute('data-pw-loop-paused','');paused.add(target)}
    }
   }
  };
  // Keep CSS in charge of timing: imperatively replaying a CSSAnimation can
  // retain its old infinite timing after a reduced-motion media change.
  const resumeLoops=()=>{for(const target of paused)target.removeAttribute('data-pw-loop-paused');paused.clear()};
  const update=()=>{root.dataset.pwMotionPaused=String(document.hidden);if(document.hidden)pauseLoops();else resumeLoops()};
  update();document.addEventListener('visibilitychange',update);document.addEventListener('animationstart',pauseLoops);
  return()=>{document.removeEventListener('visibilitychange',update);document.removeEventListener('animationstart',pauseLoops);resumeLoops();delete root.dataset.pwMotionPaused};
 },[]);
}

export function useSurfaceEnter(root:RefObject<HTMLElement|null>,identity:string){
 const reduce=useReducedMotion();const previous=useRef(identity);
 useLayoutEffect(()=>{
  const changed=previous.current!==identity;previous.current=identity;
  if(!changed||reduce||document.hidden)return;
  const surface=root.current?.querySelector<HTMLElement>(':scope > .pw-route-workspace, :scope > .pw-secondary');
  if(!surface)return;
  const animation=surface.animate(arrivalFrames,arrivalTiming);animation.id='pw-surface-enter';
  return()=>animation.cancel();
 },[root,identity,reduce]);
}

/** New tail IDs enter once; streaming updates, restored history and returning to
 * a conversation retain the same readable nodes and existing scroll authority. */
export function useMessageArrivals(root:RefObject<HTMLElement|null>,conversation:string,ids:readonly string[],active:boolean){
 const reduce=useReducedMotion();const signature=JSON.stringify(ids);
 const previous=useRef<{conversation:string;ids:string[];active:boolean}|null>(null);
 useLayoutEffect(()=>{
  const currentIds=JSON.parse(signature) as string[];
  const before=previous.current;previous.current={conversation,ids:currentIds,active};
  // Empty -> populated can be native history hydration, not a newly sent reply.
  if(!before||!before.ids.length||before.conversation!==conversation||!before.active||!active||reduce||document.hidden)return;
  const tail=before.ids.at(-1);
  const tailIndex=tail?currentIds.indexOf(tail):-1;
  // A replaced or paged window is not a new reply.
  if(before.ids.length&&tailIndex<0)return;
  const known=new Set(before.ids);
  const arrivals=new Set(currentIds.slice(tailIndex+1).filter(id=>id&&!known.has(id)));
  const animations:Animation[]=[];
  root.current?.querySelectorAll<HTMLElement>('[data-message-id]').forEach(node=>{
   if(!arrivals.has(node.dataset.messageId||''))return;
   const animation=node.animate(arrivalFrames,arrivalTiming);animation.id='pw-message-enter';animations.push(animation);
  });
  return()=>animations.forEach(animation=>animation.cancel());
 },[root,conversation,signature,active,reduce]);
}

/** Keep display fields until Radix actually unmounts the closing Vaul content.
 * The caller clears live authority immediately and makes closing content inert.
 * No animation timer can clear a new review or restore focus over a reopened one. */
export function useClosingSnapshot<T>(value:T|null){
 const [snapshot,setSnapshot]=useState<T|null>(value);
 const current=useRef(value),generation=useRef(0);
 if(value!==null&&current.current===null)generation.current++;
 current.current=value;
 const token=generation.current;
 useLayoutEffect(()=>{if(value!==null)setSnapshot(value)},[value]);
 const onCloseAutoFocus=(event:Event)=>{
  if(current.current!==null||generation.current!==token){event.preventDefault();return;}
  setSnapshot(null);
 };
 return {display:value??snapshot,generation:token,onCloseAutoFocus};
}
