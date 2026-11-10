// Actual library v3 0e69029 layout connectors / FLIP. No demo state.
// Product handoff routes retain measured endpoints; a narrow layout uses an
// outside gutter so a connection never runs through another agent card.
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import {useMotionAllowed} from '../core';

export const motionEase='cubic-bezier(0.32, 0.72, 0.24, 1)';
type Box={left:number;top:number;width:number;height:number;node:HTMLElement};

/** Affect FLIP with the current visible frame retained when a new fact arrives. */
export function useFlipLayout(root:RefObject<HTMLElement|null>,revision:string,duration=420){
  const allowed=useMotionAllowed(),previous=useRef(new Map<string,Box>()),captured=useRef(false);
  const animations=useRef(new Map<HTMLElement,Animation>());
  const cancel=()=>{animations.current.forEach(animation=>animation.cancel());animations.current.clear()};
  const measure=()=>{
    const el=root.current,map=new Map<string,Box>();if(!el)return map;const origin=el.getBoundingClientRect();
    el.querySelectorAll<HTMLElement>('[data-flip-id]').forEach(node=>{
      const key=node.dataset.flipId;if(!key||node.closest('[inert],[hidden]'))return;const rect=node.getBoundingClientRect();
      if(rect.width&&rect.height)map.set(key,{left:rect.left-origin.left+el.scrollLeft,top:rect.top-origin.top+el.scrollTop,width:rect.width,height:rect.height,node});
    });return map;
  };
  const capture=()=>{previous.current=measure();captured.current=true};
  useLayoutEffect(()=>{
    const first=previous.current,offsets=new Map<HTMLElement,{x:number;y:number}>();
    if(!captured.current)animations.current.forEach((animation,node)=>{
      if(animation.playState!=='running'||!node.isConnected)return;const style=getComputedStyle(node).transform;
      if(style&&style!=='none'){const matrix=new DOMMatrixReadOnly(style);offsets.set(node,{x:matrix.m41,y:matrix.m42})}
    });
    cancel();const next=measure();previous.current=next;captured.current=false;
    if(!allowed)return;
    next.forEach((last,key)=>{
      const before=first.get(key);if(!before)return;const offset=offsets.get(before.node);
      const x=before.left+(offset?.x||0)-last.left,y=before.top+(offset?.y||0)-last.top;
      if(Math.abs(x)>.4||Math.abs(y)>.4){
        const animation=last.node.animate([{transform:`translate(${x}px,${y}px)`},{transform:'translate(0,0)'}],{duration,easing:motionEase});
        animations.current.set(last.node,animation);
        animation.onfinish=()=>{if(animations.current.get(last.node)===animation){animations.current.delete(last.node);animation.cancel();if(!animations.current.size)previous.current=measure()}};
      }
    });
  },[root,revision,duration,allowed]);
  useEffect(()=>{
    const el=root.current;if(!el)return;
    const settle=()=>{cancel();previous.current=measure();captured.current=false};
    const observer=new ResizeObserver(()=>{if(!animations.current.size)previous.current=measure()});observer.observe(el);
    el.addEventListener('scroll',settle,true);window.addEventListener('resize',settle);
    return()=>{observer.disconnect();el.removeEventListener('scroll',settle,true);window.removeEventListener('resize',settle);cancel()};
  },[root]);
  return capture;
}

export type Connector={id:string;from:string;to:string;kind?:'horizontal'|'tree'|'deep'|'flow'|'vertical'|'handoff';tone?:'muted'|'blue'|'green'|'red';active?:boolean;exiting?:boolean;delay?:number;lane?:number};
type Drawn=Connector&{d:string};
function roundedOrthogonal(a:{x:number;y:number},b:{x:number;y:number},vertical=false){
  const r=Math.min(8,Math.abs(b.x-a.x)/2,Math.abs(b.y-a.y)/2);
  if(Math.abs(a.y-b.y)<1)return`M${a.x} ${a.y}H${b.x}`;
  if(vertical){const mid=(a.y+b.y)/2,sign=b.x>=a.x?1:-1;return`M${a.x} ${a.y}V${mid-r}Q${a.x} ${mid} ${a.x+sign*r} ${mid}H${b.x-sign*r}Q${b.x} ${mid} ${b.x} ${mid+r}V${b.y}`}
  const mid=(a.x+b.x)/2,sign=b.y>=a.y?1:-1;return`M${a.x} ${a.y}H${mid-r}Q${mid} ${a.y} ${mid} ${a.y+sign*r}V${b.y-sign*r}Q${mid} ${b.y} ${mid+r} ${b.y}H${b.x}`;
}
/** The endpoints come from live DOM rectangles, including the current FLIP frame. */
export function MeasuredConnectors({root,edges,revision,className=''}:{root:RefObject<HTMLElement|null>;edges:Connector[];revision:string;className?:string}){
  const allowed=useMotionAllowed();const[paths,setPaths]=useState<Drawn[]>([]);const currentEdges=useRef(edges);useEffect(()=>{currentEdges.current=edges},[edges]);const signature=edges.map(x=>`${x.id}:${x.from}:${x.to}:${x.kind}:${x.tone}:${x.active}:${x.exiting}`).join('|');
  useEffect(()=>{
    const container=root.current;if(!container)return;
    let raf=0,until=performance.now()+760;let cancelled=false;
    const measure=()=>{if(cancelled)return;const base=container.getBoundingClientRect();const nodes=new Map<string,DOMRect>();container.querySelectorAll<HTMLElement>('[data-link-node]').forEach(n=>{if(n.dataset.linkNode)nodes.set(n.dataset.linkNode,n.getBoundingClientRect())});
      const handoffs=currentEdges.current.filter(edge=>edge.kind==='handoff');
      const spansRows=(ids:string[])=>{const tops=ids.map(id=>nodes.get(id)?.top).filter((top):top is number=>top!==undefined);return tops.length>1&&Math.max(...tops)-Math.min(...tops)>2};
      const outsideHandoff=base.width<560||spansRows(handoffs.map(edge=>edge.from))||spansRows(handoffs.map(edge=>edge.to));
      const next:Drawn[]=[];for(const edge of currentEdges.current){const from=nodes.get(edge.from),to=nodes.get(edge.to);if(!from||!to||!from.width||!to.width)continue;const f={left:from.left-base.left,top:from.top-base.top,width:from.width,height:from.height},t={left:to.left-base.left,top:to.top-base.top,width:to.width,height:to.height};let d='';
        if(edge.kind==='handoff'){
          if(!outsideHandoff){
            d=roundedOrthogonal({x:f.left+f.width/2,y:f.top+f.height},{x:t.left+t.width/2,y:t.top},true);
          }else{
            // Source wire bands become a measured outside rail when cards stack.
            // The host reserves 24px beyond all source/route card edges.
            const a={x:f.left+f.width,y:f.top+f.height/2};
            const b={x:t.left+t.width,y:t.top+Math.min(22,t.height/2)};
            const rail=base.width-4-Math.min(3,Math.max(0,edge.lane||0)%4)*4;
            const sign=b.y>=a.y?1:-1;
            const radius=Math.min(7,Math.max(0,(rail-Math.max(a.x,b.x))/2),Math.abs(b.y-a.y)/2);
            d=`M${a.x} ${a.y}H${rail-radius}Q${rail} ${a.y} ${rail} ${a.y+sign*radius}V${b.y-sign*radius}Q${rail} ${b.y} ${rail-radius} ${b.y}H${b.x}`;
          }
        }
        else if(edge.kind==='tree'&&base.width>=560){const a={x:f.left+f.width/2,y:f.top+f.height},b={x:t.left+t.width/2,y:t.top};d=roundedOrthogonal(a,b,true)}
        else if(edge.kind==='deep'||edge.kind==='tree'&&base.width<560){const a={x:f.left+21,y:f.top+f.height},b={x:t.left,y:t.top+20};const rail=Math.min(a.x,b.x-10);d=`M${a.x} ${a.y}V${b.y-7}Q${a.x} ${b.y} ${a.x+7} ${b.y}H${b.x}`;if(b.x<a.x)d=`M${a.x} ${a.y}H${rail+7}Q${rail} ${a.y} ${rail} ${a.y+7}V${b.y-7}Q${rail} ${b.y} ${rail+7} ${b.y}H${b.x}`}
        else if(edge.kind==='vertical'){d=roundedOrthogonal({x:f.left+f.width/2,y:f.top+f.height},{x:t.left,y:t.top+t.height/2},true)}
        else{const a={x:f.left+f.width,y:f.top+f.height/2},b={x:t.left,y:t.top+t.height/2};if(edge.kind==='flow'){const c=Math.max(16,(b.x-a.x)*.55);d=`M${a.x} ${a.y}C${a.x+c} ${a.y} ${b.x-c} ${b.y} ${b.x} ${b.y}`}else d=roundedOrthogonal(a,b)}
        next.push({...edge,d});
      }setPaths(old=>JSON.stringify(old)===JSON.stringify(next)?old:next);if(allowed&&performance.now()<until)raf=requestAnimationFrame(measure);
    };
    measure();const observer=new ResizeObserver(()=>{until=performance.now()+760;cancelAnimationFrame(raf);raf=requestAnimationFrame(measure)});observer.observe(container);container.querySelectorAll<HTMLElement>('[data-link-node]').forEach(n=>observer.observe(n));
    return()=>{cancelled=true;cancelAnimationFrame(raf);observer.disconnect()};
  },[root,signature,revision,allowed]);
  return <svg className={'cb-measured-links '+className} aria-hidden="true">{paths.map(edge=><g key={edge.id} data-tone={edge.tone||'muted'} data-active={edge.active||undefined} data-exiting={edge.exiting||undefined}><path key={edge.kind==='flow'?edge.id+edge.tone+edge.active:edge.id} pathLength="1" d={edge.d} className="cb-link-base" style={{animationDelay:`${edge.delay||0}ms`}}/>{edge.active&&!edge.exiting&&<path pathLength="100" d={edge.d} className="cb-link-beam"/>}</g>)}</svg>;
}
