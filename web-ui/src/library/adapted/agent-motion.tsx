// Actual library v3 0e69029 layout connectors / FLIP. No demo state.
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';

export const motionEase='cubic-bezier(0.32, 0.72, 0.24, 1)';
type Box={left:number;top:number;width:number;height:number};
const box=(el:Element):Box=>{const r=el.getBoundingClientRect();return{left:r.left,top:r.top,width:r.width,height:r.height}};
const reduced=()=>typeof window!=='undefined'&&window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Actual FLIP: last layout rects are inverted, then play to their new positions. */
export function useFlipLayout(root:RefObject<HTMLElement|null>,revision:string,duration=420){
  const previous=useRef(new Map<string,Box>());
  const animations=useRef<Animation[]>([]);
  const capture=()=>{const el=root.current;if(!el)return;const map=new Map<string,Box>();el.querySelectorAll<HTMLElement>('[data-flip-id]').forEach(node=>{const key=node.dataset.flipId;if(key)map.set(key,box(node))});previous.current=map};
  useLayoutEffect(()=>{
    const el=root.current;if(!el)return;
    animations.current.forEach(a=>a.cancel());animations.current=[];
    const next=new Map<string,Box>();
    el.querySelectorAll<HTMLElement>('[data-flip-id]').forEach(node=>{
      const key=node.dataset.flipId;if(!key)return;const last=box(node);next.set(key,last);const first=previous.current.get(key);
      if(!reduced()&&first){const x=first.left-last.left,y=first.top-last.top;if(Math.abs(x)>.4||Math.abs(y)>.4){const a=node.animate([{transform:`translate(${x}px, ${y}px)`},{transform:'translate(0px, 0px)'}],{duration,easing:motionEase});animations.current.push(a)}}
    });previous.current=next;
  },[root,revision,duration]);
  useEffect(()=>()=>animations.current.forEach(a=>a.cancel()),[]);
  return capture;
}

export type Connector={id:string;from:string;to:string;kind?:'horizontal'|'tree'|'deep'|'flow'|'vertical';tone?:'muted'|'blue'|'green'|'red';active?:boolean;exiting?:boolean;delay?:number};
type Drawn=Connector&{d:string};
function roundedOrthogonal(a:{x:number;y:number},b:{x:number;y:number},vertical=false){
  const r=Math.min(8,Math.abs(b.x-a.x)/2,Math.abs(b.y-a.y)/2);
  if(Math.abs(a.y-b.y)<1)return`M${a.x} ${a.y}H${b.x}`;
  if(vertical){const mid=(a.y+b.y)/2,sign=b.x>=a.x?1:-1;return`M${a.x} ${a.y}V${mid-r}Q${a.x} ${mid} ${a.x+sign*r} ${mid}H${b.x-sign*r}Q${b.x} ${mid} ${b.x} ${mid+r}V${b.y}`}
  const mid=(a.x+b.x)/2,sign=b.y>=a.y?1:-1;return`M${a.x} ${a.y}H${mid-r}Q${mid} ${a.y} ${mid} ${a.y+sign*r}V${b.y-sign*r}Q${mid} ${b.y} ${mid+r} ${b.y}H${b.x}`;
}
/** The endpoints come from live DOM rectangles, including the current FLIP frame. */
export function MeasuredConnectors({root,edges,revision,className=''}:{root:RefObject<HTMLElement|null>;edges:Connector[];revision:string;className?:string}){
  const[paths,setPaths]=useState<Drawn[]>([]);const currentEdges=useRef(edges);useEffect(()=>{currentEdges.current=edges},[edges]);const signature=edges.map(x=>`${x.id}:${x.from}:${x.to}:${x.kind}:${x.tone}:${x.active}:${x.exiting}`).join('|');
  useEffect(()=>{
    const container=root.current;if(!container)return;
    let raf=0,until=performance.now()+760;let cancelled=false;
    const measure=()=>{if(cancelled)return;const base=container.getBoundingClientRect();const nodes=new Map<string,DOMRect>();container.querySelectorAll<HTMLElement>('[data-link-node]').forEach(n=>{if(n.dataset.linkNode)nodes.set(n.dataset.linkNode,n.getBoundingClientRect())});
      const next:Drawn[]=[];for(const edge of currentEdges.current){const from=nodes.get(edge.from),to=nodes.get(edge.to);if(!from||!to||!from.width||!to.width)continue;const f={left:from.left-base.left,top:from.top-base.top,width:from.width,height:from.height},t={left:to.left-base.left,top:to.top-base.top,width:to.width,height:to.height};let d='';
        if(edge.kind==='tree'&&base.width>=560){const a={x:f.left+f.width/2,y:f.top+f.height},b={x:t.left+t.width/2,y:t.top};d=roundedOrthogonal(a,b,true)}
        else if(edge.kind==='deep'||edge.kind==='tree'&&base.width<560){const a={x:f.left+21,y:f.top+f.height},b={x:t.left,y:t.top+20};const rail=Math.min(a.x,b.x-10);d=`M${a.x} ${a.y}V${b.y-7}Q${a.x} ${b.y} ${a.x+7} ${b.y}H${b.x}`;if(b.x<a.x)d=`M${a.x} ${a.y}H${rail+7}Q${rail} ${a.y} ${rail} ${a.y+7}V${b.y-7}Q${rail} ${b.y} ${rail+7} ${b.y}H${b.x}`}
        else if(edge.kind==='vertical'){d=roundedOrthogonal({x:f.left+f.width/2,y:f.top+f.height},{x:t.left,y:t.top+t.height/2},true)}
        else{const a={x:f.left+f.width,y:f.top+f.height/2},b={x:t.left,y:t.top+t.height/2};if(edge.kind==='flow'){const c=Math.max(16,(b.x-a.x)*.55);d=`M${a.x} ${a.y}C${a.x+c} ${a.y} ${b.x-c} ${b.y} ${b.x} ${b.y}`}else d=roundedOrthogonal(a,b)}
        next.push({...edge,d});
      }setPaths(old=>JSON.stringify(old)===JSON.stringify(next)?old:next);if(!reduced()&&performance.now()<until)raf=requestAnimationFrame(measure);
    };
    measure();const observer=new ResizeObserver(()=>{until=performance.now()+760;cancelAnimationFrame(raf);raf=requestAnimationFrame(measure)});observer.observe(container);container.querySelectorAll<HTMLElement>('[data-link-node]').forEach(n=>observer.observe(n));
    return()=>{cancelled=true;cancelAnimationFrame(raf);observer.disconnect()};
  },[root,signature,revision]);
  return <svg className={'cb-measured-links '+className} aria-hidden="true">{paths.map(edge=><g key={edge.id} data-tone={edge.tone||'muted'} data-active={edge.active||undefined} data-exiting={edge.exiting||undefined}><path key={edge.kind==='flow'?edge.id+edge.tone+edge.active:edge.id} pathLength="1" d={edge.d} className="cb-link-base" style={{animationDelay:`${edge.delay||0}ms`}}/>{edge.active&&!edge.exiting&&<path pathLength="100" d={edge.d} className="cb-link-beam"/>}</g>)}</svg>;
}
