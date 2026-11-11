// Affect / Reaction Bar collection geometry combined with Ovio's retained
// spring-generator -> WAAPI handoff. Data and current DOM remain authoritative.
import {useEffect,useLayoutEffect,useRef,type HTMLAttributes,type ReactNode,type RefObject} from 'react';
import {calcGeneratorDuration,spring} from 'motion';
import {useMotionAllowed} from './core';
import {cssEase,motionDuration,motionEase,motionTransition} from './motion-tokens';
export {MotionDisclosure} from './motion-disclosure';

type ItemBox={node:HTMLElement;left:number;top:number;width:number;height:number};
type CollectionSnapshot={identity:string;enabled:boolean;items:Map<string,ItemBox>};
type CollectionOptions={selector?:string;active?:boolean;enter?:boolean;layout?:boolean};
type CollectionRun={animation:Animation;frames:Keyframe[];x?:ReturnType<typeof spring>;y?:ReturnType<typeof spring>};
type VisibleOffset={x:number;y:number;vx:number;vy:number;opacity?:string};

/** getComputedTiming().progress includes the effect easing. These locally
 * authored frames use equal offsets and linear interpolation between frames,
 * so they remain sampleable even after React replaces a cross-group DOM node. */
function detachedFrame(run:CollectionRun){
 const progress=run.animation.effect?.getComputedTiming().progress??0;
 const position=Math.max(0,Math.min(1,progress))*(run.frames.length-1);
 const first=run.frames[Math.floor(position)],last=run.frames[Math.ceil(position)],mix=position-Math.floor(position);
 const from=String(first.translate||'0 0').split(/\s+/),to=String(last.translate||'0 0').split(/\s+/);
 const coordinate=(axis:number)=>{const a=parseFloat(from[axis])||0,b=parseFloat(to[axis])||0;return a+(b-a)*mix};
 return {x:coordinate(0),y:coordinate(1),opacity:first.opacity===undefined?undefined:String(Number(first.opacity)+(Number(last.opacity)-Number(first.opacity))*mix)};
}

/** Ovio use-liquid.ts samples physical generators for compositor playback and
 * carries their instantaneous velocity into the next target. Keep that mechanism
 * here, without Ovio's capsule squash. Individual translate leaves the source
 * button's press/scale transform in its own control. */
function collectionMove(x:number,y:number,vx:number,vy:number){
 const options={...motionTransition.layout,restDelta:.3,restSpeed:8};
 const gx=spring({...options,keyframes:[x,0],velocity:vx});
 const gy=spring({...options,keyframes:[y,0],velocity:vy});
 const duration=Math.min(650,Math.max(calcGeneratorDuration(gx),calcGeneratorDuration(gy)));
 const step=1000/60,count=Math.max(2,Math.ceil(duration/step)+1);
 const frames:Keyframe[]=Array.from({length:count},(_,index)=>{
  const last=index===count-1,time=Math.min(duration,index*step);
  return {translate:`${last?0:gx.next(time).value}px ${last?0:gy.next(time).value}px`};
 });
 return {frames,duration:(count-1)*step,x:gx,y:gy};
}

/** Stable business IDs connect groups as well as neighbors. Owner, query, page
 * window and history changes belong in identity; use active=false while reading
 * a new window. Streamed text is not a collection revision. No live exit clones. */
export function useCollectionMotion(root:RefObject<HTMLElement|null>,identity:string,revision:string,{selector='[data-motion-key]',active=true,enter=true,layout=true}:CollectionOptions={}){
 const allowed=useMotionAllowed();
 const previous=useRef<CollectionSnapshot|null>(null);
 const playing=useRef(new Map<HTMLElement,CollectionRun>());
 const geometry=useRef({dirty:false,epoch:0,frame:0});
 const current=useRef({identity,enabled:active&&allowed,selector});
 current.current={identity,enabled:active&&allowed,selector};
 const cancel=()=>{playing.current.forEach(run=>run.animation.cancel());playing.current.clear()};
 const cancelBaseline=()=>{cancelAnimationFrame(geometry.current.frame);geometry.current.frame=0};
 const measure=(container:HTMLElement)=>{
  const items=new Map<string,ItemBox>(),bounds=container.getBoundingClientRect();
  const scrollLeft=container.scrollLeft,scrollTop=container.scrollTop;
  container.querySelectorAll<HTMLElement>(current.current.selector).forEach(node=>{
   const id=node.dataset.motionKey;
   if(!id||items.has(id)||node.closest('[inert],[hidden]'))return;
   const rect=node.getBoundingClientRect();
   if(!rect.width||!rect.height)return;
   items.set(id,{node,left:rect.left-bounds.left+scrollLeft,top:rect.top-bounds.top+scrollTop,width:rect.width,height:rect.height});
  });
  return {items,bounds,scrollTop};
 };
 const queueBaseline=()=>{
  const state=geometry.current;state.dirty=true;state.epoch++;
  if(state.frame||!current.current.enabled)return;
  let epoch=state.epoch,quietFrames=0;
  const settle=()=>{
   state.frame=0;
   const container=root.current;
   if(!container||!current.current.enabled)return;
   if(epoch!==state.epoch){epoch=state.epoch;quietFrames=0}else quietFrames++;
   // RO delivers after animation-frame callbacks. Two quiet frames let a
   // continuing height animation invalidate this probe before any item reads.
   if(quietFrames<2){state.frame=requestAnimationFrame(settle);return}
   // The final collection animation schedules another probe. Never capture
   // its visual translate as the next natural layout baseline.
   if(playing.current.size)return;
   previous.current={identity:current.current.identity,enabled:true,items:measure(container).items};
   state.dirty=false;
  };
  state.frame=requestAnimationFrame(settle);
 };
 useLayoutEffect(()=>{
  const container=root.current,before=previous.current;
  if(!container||!active||!allowed){
   cancel();cancelBaseline();geometry.current.dirty=false;previous.current=null;return;
  }
  // A disclosure, viewport resize or nested scroll owns these changing boxes.
  // Real revisions remain immediate; motion resumes from a settled baseline.
  if(geometry.current.dirty){cancel();queueBaseline();return}
  // Read the visible translation before cancelling an interrupted FLIP. Its
  // recorded natural box supplies the old position even after React relayout.
  const offsets=new Map<HTMLElement,VisibleOffset>();
  playing.current.forEach((run,node)=>{
   const {animation}=run;
   if(animation.playState!=='running')return;
   const time=typeof animation.currentTime==='number'?animation.currentTime:0;
   const style=node.isConnected?getComputedStyle(node):null;
   const sampled=style?null:detachedFrame(run);
   const opacity=style?(run.frames[0].opacity===undefined?undefined:style.opacity):sampled?.opacity;
   const translate=style?.translate.split(/\s+/);
   // Moving to another real group can replace this DOM node. Its retained
   // generator still describes the same object's frame and momentum.
   const x=translate?parseFloat(translate[0])||0:sampled?.x||0;
   const y=translate?parseFloat(translate[1])||0:sampled?.y||0;
   offsets.set(node,{x,y,vx:run.x?.velocity?.(time)??0,vy:run.y?.velocity?.(time)??0,opacity});
  });
  cancel();
  const {items,bounds,scrollTop}=measure(container),viewportHeight=window.innerHeight;
  previous.current={identity,enabled:true,items};
  if(!before?.enabled||before.identity!==identity||container.closest('[data-pw-surface-enter]'))return;
  const viewportTop=Math.max(0,bounds.top),viewportBottom=Math.min(viewportHeight,bounds.bottom);
  const finish=(node:HTMLElement,run:CollectionRun)=>{
   if(playing.current.get(node)!==run)return;
   playing.current.delete(node);run.animation.cancel();
   if(!playing.current.size)queueBaseline();
  };
  const plans:Array<{node:HTMLElement;frames:Keyframe[];duration:number;physical:boolean}&Pick<CollectionRun,'x'|'y'>>=[];
  items.forEach((last,id)=>{
   const top=last.top+bounds.top-scrollTop;
   if(top+last.height<viewportTop||top>viewportBottom)return;
   const first=before.items.get(id);
   let frames:Keyframe[]|null=null,duration:number=motionDuration.enter;
   let generators:Pick<CollectionRun,'x'|'y'>={},physical=false;
   if(first&&layout){
    const offset=offsets.get(first.node);
    const x=first.left+(offset?.x||0)-last.left,y=first.top+(offset?.y||0)-last.top;
    // Paging and distant/offscreen objects do not fly through the viewport.
    if((Math.abs(x)>.5||Math.abs(y)>.5||Math.abs(offset?.vx||0)>8||Math.abs(offset?.vy||0)>8)&&Math.abs(x)<Math.max(80,bounds.width)&&Math.abs(y)<Math.min(420,viewportHeight*.7)){
     const move=collectionMove(x,y,offset?.vx||0,offset?.vy||0);
     frames=move.frames;duration=move.duration;generators={x:move.x,y:move.y};physical=true;
    }
   }else if(!first&&enter){
    frames=[{opacity:0,translate:'0 5px'},{opacity:1,translate:'0 0'}];
   }
   const opacity=first?offsets.get(first.node)?.opacity:undefined;
   if(opacity!==undefined&&Number(opacity)<1){
    if(!frames){frames=[{},{}];duration=motionDuration.enter}
    frames.forEach((frame,index)=>{const progress=Math.min(1,duration*index/(frames!.length-1)/motionDuration.feedback);frame.opacity=Number(opacity)+(1-Number(opacity))*progress});
   }
   if(!frames)return;
   plans.push({node:last.node,frames,duration,physical,...generators});
  });
  // All geometry and interrupted values are read before starting new effects.
  plans.forEach(({node,frames,duration,physical,...generators})=>{
   const animation=node.animate(frames,{duration,easing:physical?'linear':cssEase(motionEase.settle)});
   const run:CollectionRun={animation,frames,...generators};
   animation.id='pw-collection-change';playing.current.set(node,run);
   animation.onfinish=()=>finish(node,run);
  });
 },[root,identity,revision,selector,active,allowed,enter,layout]);
 useEffect(()=>{
  const container=root.current;if(!container||!active||!allowed)return;
  const settle=()=>{if(!current.current.enabled)return;cancel();queueBaseline()};
  const scroll=(event:Event)=>{
   if(!current.current.enabled)return;
   const target=event.target;
   if(target!==document&&(!(target instanceof Node)||!container.contains(target)&&!target.contains(container)))return;
   if(playing.current.size){settle();return}
   // Own/ancestor scrolling cancels out in root-relative coordinates. A nested
   // scroller can move descendants within the root: invalidate without scanning
   // on idle scroll; the next real revision will establish a quiet baseline.
   if(target instanceof Node&&target!==container&&container.contains(target)){
    geometry.current.dirty=true;geometry.current.epoch++;
   }
  };
  // Scrolling has its own owner. Never counter-animate an intentional scroll or
  // a keyboard/viewport resize with a stale collection position.
  document.addEventListener('scroll',scroll,true);
  window.addEventListener('resize',settle);
  let width:number|undefined,height:number|undefined;
  const observer=new ResizeObserver(entries=>{
   if(!current.current.enabled)return;
   const size=entries[0]?.contentRect;
   if(!size||size.width===width&&size.height===height)return;
   width=size.width;height=size.height;queueBaseline();
  });
  observer.observe(container);
  return()=>{document.removeEventListener('scroll',scroll,true);window.removeEventListener('resize',settle);observer.disconnect();cancelBaseline();cancel()};
 },[root,identity,selector,active,allowed]);
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

/** Keep routine work in one reading line without moving the content below it. */
export function WorkingStatus({active,children,className=''}:{active:boolean;children:string;className?:string}){
 return <MotionFeedback identity={active?children:''} initial={false} className={`pw-working-status ${className}`} role="status" aria-live="polite" aria-atomic="true" title={active?children:undefined}>{active?children:''}</MotionFeedback>;
}
