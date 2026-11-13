// Product wiring for the retained Ovio stage easing and Affect arrival/disclosure
// patterns. Animate the current DOM only; never keep a second live workspace.
import {useEffect,useLayoutEffect,useRef,useState,type RefObject} from 'react';
import {useMotionAllowed} from './core';
import {cssEase,motionDuration,motionEase} from './motion-tokens';

const arrivalTiming:KeyframeAnimationOptions={
 duration:motionDuration.enter,
 easing:cssEase(motionEase.enter),
};
const arrivalFrames:Keyframe[]=[{opacity:0,transform:'translateY(10px)'},{opacity:1,transform:'translateY(0)'}];
type SurfaceMotionOwner={animation:Animation;cancel:()=>void};
const surfaceOwners=new Map<HTMLElement,SurfaceMotionOwner>();

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

export function useSurfaceEnter(root:RefObject<HTMLElement|null>,identity:string,{selector=':scope > .pw-route-workspace, :scope > .pw-secondary',depth=0,active=true}:{selector?:string;depth?:number;active?:boolean}={}){
 const allowed=useMotionAllowed();
 const previous=useRef<{identity:string;depth:number;enabled:boolean}|null>(null);
 const playing=useRef<{surface:HTMLElement;owner:SurfaceMotionOwner}|null>(null);
 const cancel=()=>{
  const current=playing.current;if(!current)return;
  playing.current=null;
  // A retiring callback must not release a newer owner's marker on this node.
  if(surfaceOwners.get(current.surface)===current.owner){
   surfaceOwners.delete(current.surface);current.surface.removeAttribute('data-pw-surface-enter');
  }
  current.owner.animation.cancel();
 };
 useLayoutEffect(()=>{
  const enabled=active&&allowed,before=previous.current;
  previous.current={identity,depth,enabled};
  // Mounting, restoring a surface and changing peer/data identities keep their
  // settled geometry. Only an actual hierarchy change has a travel direction.
  if(!enabled||!before?.enabled||before.identity===identity||before.depth===depth){cancel();return}
  const surface=selector?root.current?.querySelector<HTMLElement>(selector):root.current;
  if(!surface){cancel();return}
  const interrupted=surfaceOwners.get(surface)?.animation.playState==='running';
  // MangaFlow MotionTabContent mirrors hierarchy travel for RTL. Retain
  // only the current page: no outgoing clone can expose stale actions.
  const direction=(depth<before.depth?-1:1)*(getComputedStyle(surface).direction==='rtl'?-1:1);
  const transform=interrupted?getComputedStyle(surface).transform:`translateX(${direction*10}px)`;
  cancel();
  for(const [node,owner] of surfaceOwners){
   if(!node.isConnected||owner.animation.playState==='finished'||owner.animation.playState==='idle')owner.cancel();
  }
  // Layout effects run child first. A parent cancels a child's earlier entrance;
  // an existing parent also prevents a child from adding another displacement.
  for(const node of surfaceOwners.keys())if(node!==surface&&node.contains(surface))return;
  for(const [node,owner] of surfaceOwners)if(surface.contains(node))owner.cancel();
  surface.setAttribute('data-pw-surface-enter','');
  // Keep text fully visible; a rapid reverse continues this same node's frame.
  const animation=surface.animate([{transform},{transform:'translateX(0)'}],arrivalTiming);
  const owner:SurfaceMotionOwner={animation,cancel:()=>{if(playing.current?.owner===owner)cancel()}};
  animation.id='pw-surface-enter';playing.current={surface,owner};surfaceOwners.set(surface,owner);
  animation.onfinish=owner.cancel;animation.oncancel=owner.cancel;
 },[root,identity,selector,depth,active,allowed]);
 useLayoutEffect(()=>cancel,[]);
}

/** New tail IDs enter once; streaming updates, restored history and returning to
 * a conversation retain the same readable nodes and existing scroll authority. */
export type MessageArrivalDelivery={
 owner:string;
 /** Native pending IDs belonging to this currently selected composer draft. */
 pendingClients:readonly string[];
 receipts:readonly {clientMessageId:string;conversationId:string;status:string}[];
 /** Native user-message IDs paired with their submission.clientMessageId. */
 submissions:readonly {id:string;clientMessageId:string}[];
};
export function useMessageArrivals(root:RefObject<HTMLElement|null>,conversation:string,ids:readonly string[],active:boolean,delivery?:MessageArrivalDelivery){
 const allowed=useMotionAllowed();const signature=JSON.stringify(ids);
 const owner=delivery?.owner??conversation;
 const deliverySignature=JSON.stringify(delivery?{pendingClients:delivery.pendingClients,receipts:delivery.receipts,submissions:delivery.submissions}:null);
 const previous=useRef<{conversation:string;owner:string;ids:string[];active:boolean}|null>(null);
 const requested=useRef<{conversation:string;owner:string;knownClients:Set<string>;client:string|null;migrated:boolean}|null>(null),playing=useRef(new Set<Animation>());
 const cancelPlaying=()=>{playing.current.forEach(animation=>animation.cancel());playing.current.clear()};
 useLayoutEffect(()=>{
  const currentIds=JSON.parse(signature) as string[];
  const currentDelivery=JSON.parse(deliverySignature) as Omit<MessageArrivalDelivery,'owner'>|null;
  const before=previous.current;previous.current={conversation,owner,ids:currentIds,active};
  if(!before||!active||!allowed){requested.current=null;cancelPlaying();return}
  if(!before.active)return;
  const intent=requested.current;
  // composer.send returns only a bridge acceptance, not the native message ACK.
  // Bind a fresh native pending ID while its original draft still owns the UI.
  if(intent&&!intent.client&&intent.owner===owner&&intent.conversation===conversation&&currentDelivery){
   const clients=currentDelivery.pendingClients.filter(client=>client&&!intent.knownClients.has(client));
   if(clients.length===1)intent.client=clients[0];
   else if(clients.length>1)requested.current=null;
  }
  const request=requested.current;
  const receipt=request?.client?currentDelivery?.receipts.find(receipt=>receipt.clientMessageId===request.client&&['accepted','queued','consumed'].includes(receipt.status)):undefined;
  const changed=before.conversation!==conversation||before.owner!==owner;
  if(changed){
   cancelPlaying();
   // A new draft's key AND epoch change after its first accepted send. Only the
   // exact observed pending ID's receipt may carry presentation intent across it.
   if(!request||request.migrated||request.conversation!==''||before.owner!==request.owner||before.conversation!==''||!conversation||receipt?.conversationId!==conversation){requested.current=null;return}
   request.owner=owner;request.conversation=conversation;request.migrated=true;
  }
  const baseline=changed?[]:before.ids;
  let arrivals:Set<string>;
  if(!baseline.length){
   // History hydration can race an explicit send. Animate only its real user row,
   // never every row in an empty -> populated snapshot or an arbitrary new owner.
   if(!request?.client||receipt?.conversationId!==conversation)return;
   const submitted=new Set(currentDelivery?.submissions.filter(message=>message.clientMessageId===request.client).map(message=>message.id));
   arrivals=new Set(currentIds.filter(id=>id&&submitted.has(id)));
  }else{
   const tail=baseline.at(-1),tailIndex=tail?currentIds.indexOf(tail):-1;
   // A replaced or paged window is not a new reply.
   if(tailIndex<0)return;
   const known=new Set(baseline);
   arrivals=new Set(currentIds.slice(tailIndex+1).filter(id=>id&&!known.has(id)));
  }
  if(!arrivals.size)return;
  requested.current=null;
  const viewport=root.current?.getBoundingClientRect();
  root.current?.querySelectorAll<HTMLElement>('[data-message-id]').forEach(node=>{
   if(!arrivals.has(node.dataset.messageId||'')||node.dataset.replyContinuing==='true'||node.closest('[data-pw-surface-enter]'))return;
   const bounds=node.getBoundingClientRect();
   if(viewport&&(bounds.bottom<viewport.top||bounds.top>viewport.bottom))return;
   const animation=node.animate(arrivalFrames,arrivalTiming);animation.id='pw-message-enter';playing.current.add(animation);
   animation.onfinish=()=>{playing.current.delete(animation);animation.cancel()};
  });
 },[root,conversation,owner,signature,deliverySignature,active,allowed]);
 useEffect(()=>()=>cancelPlaying(),[]);
 return ()=>{requested.current=delivery?{conversation,owner,knownClients:new Set(delivery.pendingClients),client:null,migrated:false}:null};
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
