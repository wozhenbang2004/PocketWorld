// Adapted from Affect UI MessageText's natural inner / measured outer height.
// MIT source: Affect UI official@37b91fa302dea59e28ce1eb2ba7acd7ce23296be,
// retained at upstream/affect-official/components/message-actions-clean.tsx.source.txt.
// Unlike its text-version Swap, this keeps one content tree and no exit timer.
import {useEffect,useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import {animate,motion,useMotionValue,useTransform,type HTMLMotionProps} from 'motion/react';
import {useMotionAllowed} from './core';
import {motionTransition} from './motion-tokens';
import './motion-disclosure.css';

type MotionDisclosureProps=Omit<HTMLMotionProps<'div'>,'children'|'initial'|'animate'|'exit'> & {
 open:boolean;
 children:ReactNode;
 /** An open ancestor of other disclosures uses natural flow, so it does not
  * spring after every frame of a descendant's own height animation. */
 resize?:boolean;
};

/** Natural content supplies the destination; only the outer height animates.
 * Closed content is inert immediately. Its visual lifetime never owns a task,
 * permission, focus return or business cleanup. Streaming bodies stay outside. */
export function MotionDisclosure({open,children,resize=true,className='',style,inert,onFocusCapture,...props}:MotionDisclosureProps){
 const allowed=useMotionAllowed();
 const root=useRef<HTMLDivElement>(null),content=useRef<HTMLDivElement>(null);
 const height=useMotionValue(0),opacity=useMotionValue(open?1:0);
 const visibleHeight=useTransform(height,value=>Math.max(0,value));
 const [ready,setReady]=useState(false),[natural,setNatural]=useState(false),[moving,setMoving]=useState(false);
 const initialized=useRef(false),naturalMode=useRef(false),generation=useRef(0);
 const previous=useRef({open,resize}),destination=useRef(0),width=useRef<number|null>(null);
 const current=useRef({open,allowed,resize});current.current={open,allowed,resize};

 const setNaturalHeight=(value:boolean)=>{naturalMode.current=value;setNatural(value)};
 const update=(instant=false)=>{
  const node=content.current;if(!node)return;
  const state=current.current,changed=previous.current.open!==state.open||previous.current.resize!==state.resize;
  previous.current={open:state.open,resize:state.resize};
  // offsetHeight is a layout measurement, unaffected by an ancestor's visual
  // scale. The inner has no animated height and contains its child margins.
  const target=state.open?node.offsetHeight:0;
  if(!initialized.current||!state.allowed||instant){
   initialized.current=true;generation.current++;destination.current=target;
   height.jump(target);opacity.jump(state.open?1:0);
   setNaturalHeight(state.open&&!state.resize);setMoving(false);setReady(true);
   return;
  }
  if(!changed&&target===destination.current)return;
  destination.current=target;
  if(!changed&&naturalMode.current){
   // A child already owns this change. Natural flow follows it without a
   // second spring, while retaining the height needed for a later close.
   height.jump(target);
   return;
  }
  if(naturalMode.current){
   height.jump(root.current?.offsetHeight??target);
   setNaturalHeight(false);
  }
  const token=++generation.current;
  setMoving(true);
  // animate(MotionValue) replaces its prior animation while retaining velocity.
  // Do not jump/set a new origin or inject velocity:0 on a normal retarget.
  const size=animate(height,target,motionTransition.layout);
  animate(opacity,state.open?1:0,state.open?motionTransition.enter:motionTransition.exit);
  void size.then(()=>{
   if(generation.current!==token)return;
   setMoving(false);
   setNaturalHeight(current.current.open&&!current.current.resize);
  });
 };
 const updateRef=useRef(update);updateRef.current=update;

 useLayoutEffect(()=>{updateRef.current()},[open,allowed,resize]);
 useLayoutEffect(()=>{
  const node=content.current;if(!node)return;
  width.current=node.offsetWidth;
  const observer=new ResizeObserver(()=>{
   const next=node.offsetWidth,resized=width.current!==null&&next!==width.current;
   width.current=next;
   // A viewport/keyboard reflow has its own owner. Only content-height changes
   // animate; width changes settle immediately at the new readable geometry.
   updateRef.current(resized);
  });
  observer.observe(node);
  return()=>observer.disconnect();
 },[]);
 useEffect(()=>()=>{generation.current++;height.stop();opacity.stop()},[height,opacity]);

 return <motion.div {...props} ref={root} initial={false}
  className={`pw-motion-disclosure ${className}`} data-open={open} data-moving={moving||undefined}
  aria-hidden={!open} inert={!open||inert}
  style={{...style,height:ready?(natural?'auto':visibleHeight):(open?'auto':0),opacity}}
  onFocusCapture={event=>{
   // Keyboard focus is usable now, even if the surrounding reveal is midway.
   if(current.current.open&&event.target!==event.currentTarget)updateRef.current(true);
   onFocusCapture?.(event);
  }}>
  <div ref={content} className="pw-motion-disclosure-content">{children}</div>
 </motion.div>;
}
