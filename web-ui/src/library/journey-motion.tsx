// Business wiring for the retained Affect FLIP / disclosure and Reaction Bar
// item-arrival patterns. The current data and DOM remain the only authority.
import {useEffect,useLayoutEffect,useRef,type HTMLAttributes,type ReactNode,type RefObject} from 'react';
import {useMotionAllowed} from './core';
import {cssEase,motionDuration,motionEase} from './motion-tokens';

type ItemBox={node:HTMLElement;left:number;top:number;width:number;height:number};
type CollectionSnapshot={identity:string;enabled:boolean;items:Map<string,ItemBox>};
type CollectionOptions={selector?:string;active?:boolean;enter?:boolean;layout?:boolean};

/** Stable business IDs connect groups as well as neighbors. Owner, query, page
 * window and history changes belong in identity; use active=false while reading
 * a new window. Streamed text is not a collection revision. No live exit clones. */
export function useCollectionMotion(root:RefObject<HTMLElement|null>,identity:string,revision:string,{selector='[data-motion-key]',active=true,enter=true,layout=true}:CollectionOptions={}){
 const allowed=useMotionAllowed();
 const previous=useRef<CollectionSnapshot|null>(null);
 const playing=useRef(new Map<HTMLElement,Animation>());
 const current=useRef({identity,enabled:active&&allowed,selector});
 current.current={identity,enabled:active&&allowed,selector};
 const cancel=()=>{playing.current.forEach(animation=>animation.cancel());playing.current.clear()};
 const measure=()=>{
  const container=root.current,items=new Map<string,ItemBox>();
  if(!container)return items;
  const origin=container.getBoundingClientRect();
  container.querySelectorAll<HTMLElement>(current.current.selector).forEach(node=>{
   const id=node.dataset.motionKey;
   if(!id||items.has(id)||node.closest('[inert],[hidden]'))return;
   const rect=node.getBoundingClientRect();
   if(!rect.width||!rect.height)return;
   items.set(id,{node,left:rect.left-origin.left+container.scrollLeft,top:rect.top-origin.top+container.scrollTop,width:rect.width,height:rect.height});
  });
  return items;
 };
 const remember=()=>{previous.current={identity:current.current.identity,enabled:current.current.enabled,items:measure()}};
 useLayoutEffect(()=>{
  const container=root.current,before=previous.current;
  if(!container){cancel();previous.current=null;return}
  // Read the visible translation before cancelling an interrupted FLIP. Its
  // recorded natural box supplies the old position even after React relayout.
  const offsets=new Map<HTMLElement,{x:number;y:number;opacity?:string}>();
  playing.current.forEach((animation,node)=>{
   if(animation.playState!=='running'||!node.isConnected)return;
   const style=getComputedStyle(node),transform=style.transform;
   const opacity=(animation.effect as KeyframeEffect|null)?.getKeyframes().some(frame=>frame.opacity!==undefined)?style.opacity:undefined;
   let x=0,y=0;
   if(transform&&transform!=='none'){
    const matrix=new DOMMatrixReadOnly(transform);x=matrix.m41;y=matrix.m42;
   }
   offsets.set(node,{x,y,opacity});
  });
  cancel();
  const items=measure();
  previous.current={identity,enabled:active&&allowed,items};
  if(!active||!allowed||!before?.enabled||before.identity!==identity)return;
  const bounds=container.getBoundingClientRect();
  const viewportTop=Math.max(0,bounds.top),viewportBottom=Math.min(window.innerHeight,bounds.bottom);
  const finish=(node:HTMLElement,animation:Animation)=>{
   if(playing.current.get(node)!==animation)return;
   playing.current.delete(node);animation.cancel();
   if(!playing.current.size)remember();
  };
  items.forEach((last,id)=>{
   const rect=last.node.getBoundingClientRect();
   if(rect.bottom<viewportTop||rect.top>viewportBottom)return;
   const first=before.items.get(id);
   let frames:Keyframe[]|null=null,duration:number=motionDuration.layout;
   if(first&&layout){
    const offset=offsets.get(first.node);
    const x=first.left+(offset?.x||0)-last.left,y=first.top+(offset?.y||0)-last.top;
    // Paging and distant/offscreen objects do not fly through the viewport.
    if((Math.abs(x)>.5||Math.abs(y)>.5)&&Math.abs(x)<Math.max(80,bounds.width)&&Math.abs(y)<Math.min(420,window.innerHeight*.7)){
     frames=[{transform:`translate(${x}px,${y}px)`},{transform:'translate(0,0)'}];
    }
   }else if(!first&&enter){
    frames=[{opacity:0,transform:'translateY(5px)'},{opacity:1,transform:'translateY(0)'}];duration=motionDuration.enter;
   }
   const opacity=first?offsets.get(first.node)?.opacity:undefined;
   if(opacity!==undefined&&Number(opacity)<1){
    if(!frames){frames=[{},{}];duration=motionDuration.enter}
    frames[0].opacity=opacity;frames[1].opacity=1;
   }
   if(!frames)return;
   const animation=last.node.animate(frames,{duration,easing:cssEase(motionEase.settle)});
   animation.id='pw-collection-change';playing.current.set(last.node,animation);
   animation.onfinish=()=>finish(last.node,animation);
  });
 },[root,identity,revision,selector,active,allowed,enter,layout]);
 useEffect(()=>{
  const container=root.current;if(!container)return;
  const settle=()=>{cancel();remember()};
  const scroll=(event:Event)=>{const target=event.target;if(target===document||target instanceof Node&&(container.contains(target)||target.contains(container)))settle()};
  // Scrolling has its own owner. Never counter-animate an intentional scroll or
  // a keyboard/viewport resize with a stale collection position.
  document.addEventListener('scroll',scroll,true);
  window.addEventListener('resize',settle);
  const observer=new ResizeObserver(()=>{if(!playing.current.size)remember()});
  observer.observe(container);
  return()=>{document.removeEventListener('scroll',scroll,true);window.removeEventListener('resize',settle);observer.disconnect();cancel()};
 },[root,identity,selector,active]);
}

/** Affect-style measured disclosure using one mounted content tree. Inert and
 * accessibility state change immediately, independently of the visual exit. */
export function MotionDisclosure({open,children,className='',...props}:HTMLAttributes<HTMLDivElement>&{open:boolean;children:ReactNode}){
 const allowed=useMotionAllowed();
 return <div {...props} className={`pw-motion-disclosure ${className}`} data-open={open} data-motion-instant={!allowed||undefined} aria-hidden={!open} inert={!open}>
  <div className="pw-motion-disclosure-content">{children}</div>
 </div>;
}

/** Local receipts stay in their original place; only the current content fades.
 * Safe beside native viewports: no layout transform, duplicate owner or timer. */
export function MotionFeedback({identity,children,className='',initial=true,as:Tag='div',...props}:HTMLAttributes<HTMLDivElement>&{identity:string|number;children:ReactNode;initial?:boolean;as?:'div'|'span'}){
 const root=useRef<HTMLElement|null>(null),previous=useRef<string|number|null>(null),playing=useRef<Animation|null>(null);
 const allowed=useMotionAllowed();
 useLayoutEffect(()=>{
  const before=previous.current;previous.current=identity;
  const node=root.current;
  const opacity=node&&playing.current?.playState==='running'?getComputedStyle(node).opacity:null;
  playing.current?.cancel();playing.current=null;
  if(!allowed||!node||before===identity||before===null&&!initial)return;
  const animation=node.animate([{opacity:opacity??.35},{opacity:1}],{duration:motionDuration.feedback,easing:cssEase(motionEase.enter)});
  animation.id='pw-local-feedback';playing.current=animation;
  animation.onfinish=()=>{if(playing.current===animation){playing.current=null;animation.cancel()}};
 },[identity,allowed,initial]);
 useEffect(()=>()=>playing.current?.cancel(),[]);
 return <Tag {...props} className={className} ref={node=>{root.current=node}}>{children}</Tag>;
}
