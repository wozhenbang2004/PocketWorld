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

/** Controlled reviews open after a host response, without a Radix Trigger.
 * Capture their origin before that request; restore on the real exit only. */
export function useReviewFocus(open:boolean){
 const current=useRef(open),generation=useRef(0);
 const requested=useRef<{origin:HTMLElement|null;fallback:HTMLElement|null}|null>(null);
 const presented=useRef<typeof requested.current>(null);
 if(open&&!current.current){generation.current++;presented.current=requested.current;requested.current=null}
 current.current=open;
 const token=generation.current;
 const captureReturnFocus=(input:HTMLElement|null)=>{
  const active=document.activeElement;
  const origin=active instanceof HTMLElement&&active!==document.body&&active!==document.documentElement&&!active.closest('[inert]')?active:requested.current?.origin||presented.current?.origin||null;
  requested.current={origin,fallback:input};
 };
 const onCloseAutoFocus=(event:Event)=>{
  event.preventDefault();
  if(current.current||generation.current!==token)return false;
  // The next host response can arrive after this portal exits. Its captured
  // origin belongs to that pending request, not to the sheet being removed.
  const targets=[presented.current?.origin,presented.current?.fallback];presented.current=null;
  const active=document.activeElement;
  // Another live sheet or a deliberate focus change owns the next interaction.
  if(document.querySelector('[role="dialog"][data-state="open"]')||
   active instanceof HTMLElement&&active!==document.body&&active!==document.documentElement&&active.isConnected&&!active.closest('[inert]'))return true;
  const target=targets.find(node=>node?.isConnected&&!node.matches(':disabled')&&!node.closest('[inert]')&&node.getClientRects().length);
  target?.focus({preventScroll:true});
  return true;
 };
 return {generation:token,captureReturnFocus,onCloseAutoFocus};
}

/** Keep display fields until Radix actually unmounts the closing Vaul content.
 * The caller clears live authority immediately and makes closing content inert.
 * No animation timer can clear a new review or restore focus over a reopened one. */
export function useClosingSnapshot<T>(value:T|null){
 const [snapshot,setSnapshot]=useState<T|null>(value);
 const focus=useReviewFocus(value!==null);
 useLayoutEffect(()=>{if(value!==null)setSnapshot(value)},[value]);
 const onCloseAutoFocus=(event:Event)=>{if(focus.onCloseAutoFocus(event))setSnapshot(null)};
 return {display:value??snapshot,generation:focus.generation,captureReturnFocus:focus.captureReturnFocus,onCloseAutoFocus};
}
