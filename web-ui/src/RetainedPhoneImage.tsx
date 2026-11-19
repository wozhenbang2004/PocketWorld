import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore, type KeyboardEvent, type PointerEvent } from 'react';
import { Button, IconButton } from './forms-library';
import { IconAdd, IconArrowDown, IconArrowLeft, IconArrowRight, IconArrowUp, IconCollapse, IconExpand, IconMinus } from './icons';
import { registerOverlayBack } from './overlay-back';
import { currentImageWindow, retainedImageProof, subscribeImageWindow, type ImageProof } from './retained-phone-image';
import './retained-phone-image.css';

type Props = { task: Record<string, any>; src: string; label: string; active: boolean; readIdentity: string; motionKey?: string };
type Point = { x: number; y: number };
type Pose = Point & { z: number };
const start = (): Pose => ({ x: 0, y: 0, z: 1 });
const clamp = (n: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, n));
const mid = (a: Point, b: Point): Point => ({x: (a.x+b.x)/2, y: (a.y+b.y)/2});
const distance = (a: Point, b: Point) => Math.hypot(a.x-b.x,a.y-b.y);
const stops = [1, 1.5, 2, 3, 4, 6, 8, 12, 16], smaller = [...stops].reverse();

export function RetainedPhoneImage({task, src, label, active, readIdentity, motionKey}: Props) {
  const proof = active && readIdentity ? retainedImageProof(task,src) : null;
  if (!proof) return <figure className="pw-outcome-image" data-motion-key={motionKey}>
    <img src={src} alt={label} loading="lazy" decoding="async"/><figcaption>{label}</figcaption></figure>;
  // Changing results, privacy scope, task layer or read window discards all zoom and focus tickets.
  return <InspectImage key={JSON.stringify([readIdentity,proof])} proof={proof} src={src} label={label} motionKey={motionKey}/>;
}

function rememberScroll(trigger: HTMLElement) {
  const parents: {el: Element; x: number; y: number}[] = [];
  for (let el: Element|null = trigger.parentElement; el; el=el.parentElement) {
    if (el===document.scrollingElement || /auto|scroll/.test(getComputedStyle(el).overflowY+' '+getComputedStyle(el).overflowX))
      parents.push({el,x:el.scrollLeft,y:el.scrollTop});
  }
  return () => { for (const {el,x,y} of parents) if (el.isConnected) { el.scrollLeft=x; el.scrollTop=y; } };
}

function InspectImage({proof: initial,src,label,motionKey}: Pick<Props,'src'|'label'|'motionKey'> & {proof:ImageProof}) {
  const proof = useRef(initial).current; // The parent keys this instance to the entire original proof.
  const read = useCallback(() => currentImageWindow(proof), [proof]);
  const windowId = useSyncExternalStore(subscribeImageWindow, read);
  const [held,setHeld] = useState(''), [decoded,setDecoded] = useState<'pending'|'ready'|'failed'>('pending');
  const [meter,setMeter] = useState({percent:100,min:true,max:false,edges:0}), [arrows,setArrows]=useState(false);
  const opened = !!held && held===windowId && decoded==='ready';
  const stage = useRef<HTMLDivElement>(null), img = useRef<HTMLImageElement>(null), trigger = useRef<HTMLButtonElement>(null);
  const ticket = useRef(''), returnAt = useRef<{stamp:string; restore:()=>void} | null>(null);
  const rect=useRef<DOMRect|null>(null);
  const restore = useRef(false), points = useRef(new Map<number,Point>()), pose = useRef(start()), drawFrame = useRef(0);
  const fit = useRef({w:0,h:0,cw:0,ch:0,max:4}), panel = useId(), hint = useId();

  const release=useCallback(()=>{
    const box=stage.current; for(const id of points.current.keys()) if(box?.hasPointerCapture(id)) box.releasePointerCapture(id);
    points.current.clear(); rect.current=null;
  },[]);
  const stop = useCallback((back: boolean) => {
    restore.current = back && !!ticket.current;
    if (!restore.current) returnAt.current=null;
    ticket.current=''; release(); pose.current=start();
    cancelAnimationFrame(drawFrame.current); drawFrame.current=0; setHeld(''); setArrows(false);
  },[release]);
  const open = () => {
    const stamp=read(), button=trigger.current, image=img.current;
    if (!button || !stamp || stamp!==windowId || !image?.complete || !image.naturalWidth || decoded!=='ready') return;
    returnAt.current={stamp,restore:rememberScroll(button)}; restore.current=false;
    pose.current=start(); ticket.current=stamp; setHeld(stamp);
  };

  // Mutation-free native subscription, including token-only replacement preceding a new task snapshot.
  // Close on the FIRST invalidation, even if React batches a second valid push before painting.
  useLayoutEffect(() => {
    if (!held) return;
    const reconcile=()=>{ if(ticket.current && (read()!==ticket.current || stage.current?.closest('[hidden],[inert],[aria-hidden="true"]'))) stop(false); };
    const off=subscribeImageWindow(reconcile);
    const observed=new MutationObserver(reconcile);
    for(let el=stage.current?.parentElement;el;el=el.parentElement) observed.observe(el,{attributes:true,attributeFilter:['hidden','inert','aria-hidden']});
    let timer=0;
    const atExpiry=()=>{ reconcile(); if(ticket.current) timer=window.setTimeout(atExpiry,Math.min(2_147_483_647,Math.max(1,proof.expires-Date.now()+1))); };
    atExpiry();
    return ()=>{off();observed.disconnect();clearTimeout(timer)};
  },[held,read,stop,proof.expires]);
  useLayoutEffect(() => {
    if (opened) {
      const box=stage.current; box?.closest('figure')?.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});
      box?.focus({preventScroll:true}); return registerOverlayBack(()=>stop(true));
    }
    if (!restore.current) return;
    restore.current=false;
    const origin=returnAt.current; returnAt.current=null;
    if (origin && read()===origin.stamp && trigger.current?.isConnected && !trigger.current.closest('[hidden],[inert]')) {
      origin.restore(); trigger.current.focus({preventScroll:true});
    }
  },[opened,stop,read]);
  useEffect(()=>()=>{ticket.current=''; returnAt.current=null; cancelAnimationFrame(drawFrame.current); release();},[release]);

  const paint=useCallback(()=>{
    drawFrame.current=0;
    const image=img.current, box=stage.current, f=fit.current, p=pose.current;
    if (!ticket.current || !image || !box || !f.cw || !f.ch) return;
    p.z=clamp(p.z,1,f.max);
    const edgeX=Math.max(0,(f.w*p.z-f.cw)/2), edgeY=Math.max(0,(f.h*p.z-f.ch)/2);
    p.x=clamp(p.x,-edgeX,edgeX); p.y=clamp(p.y,-edgeY,edgeY);
    image.style.setProperty('--pw-proof-x',p.x+'px'); image.style.setProperty('--pw-proof-y',p.y+'px');
    image.style.setProperty('--pw-proof-zoom',String(p.z));
    const percent=Math.round(p.z*100), min=p.z<=1, max=p.z>=f.max;
    const edges=(p.x<edgeX-.5?1:0)|(p.x>-edgeX+.5?2:0)|(p.y<edgeY-.5?4:0)|(p.y>-edgeY+.5?8:0);
    if(!edges)setArrows(false);
    setMeter(old=>old.percent===percent&&old.min===min&&old.max===max&&old.edges===edges?old:{percent,min,max,edges});
  },[]);
  const schedule=()=>{ if(!drawFrame.current) drawFrame.current=requestAnimationFrame(paint); };
  useLayoutEffect(()=>{
    const box=stage.current, image=img.current;
    if (!opened || !box || !image) return;
    const measure=()=>{
      const cw=box.clientWidth, ch=box.clientHeight, nw=image.naturalWidth, nh=image.naturalHeight;
      if (!cw||!ch||!nw||!nh) {stop(false);return;}
      const changed=fit.current.cw!==cw||fit.current.ch!==ch, scale=Math.min(cw/nw,ch/nh);
      // At least four useful steps; tall originals can reach intrinsic size up to the local 16× ceiling.
      fit.current={cw,ch,w:nw*scale,h:nh*scale,max:clamp(1/scale,4,16)};
      image.style.setProperty('--pw-proof-w',nw*scale+'px'); image.style.setProperty('--pw-proof-h',nh*scale+'px');
      if(changed) release(); paint();
    };
    measure();
    const observer=new ResizeObserver(measure); observer.observe(box);
    return ()=>{observer.disconnect();cancelAnimationFrame(drawFrame.current);drawFrame.current=0;};
  },[opened,paint,release,stop]);

  const adjust=(target:number,from:Point={x:0,y:0},to:Point=from)=>{
    const p=pose.current, z=clamp(target,1,fit.current.max), ratio=z/p.z;
    pose.current={z,x:to.x-(from.x-p.x)*ratio,y:to.y-(from.y-p.y)*ratio}; schedule();
  };
  const step=(up:boolean)=>{ const z=pose.current.z;
    adjust(up ? stops.find(n=>n>z+.001)??fit.current.max : smaller.find(n=>n<z-.001)??1);
  };
  const nudge=(x:number,y:number)=>{const f=fit.current,p=pose.current; p.x+=x*Math.max(48,f.cw/3);p.y+=y*Math.max(48,f.ch/3);schedule();};
  const centered=(e:PointerEvent<HTMLDivElement>,box:DOMRect):Point=>({x:e.clientX-box.left-box.width/2,y:e.clientY-box.top-box.height/2});
  const down=(e:PointerEvent<HTMLDivElement>)=>{
    if (!ticket.current || e.button!==0 || points.current.size>=2) return;
    const box=e.currentTarget; if(!points.current.size || !rect.current) rect.current=box.getBoundingClientRect();
    box.setPointerCapture(e.pointerId); points.current.set(e.pointerId,centered(e,rect.current));
  };
  const move=(e:PointerEvent<HTMLDivElement>)=>{
    const ids=points.current, before=ids.get(e.pointerId);
    if (!ticket.current||!before||!rect.current) return;
    const after=centered(e,rect.current), other=[...ids].find(([id])=>id!==e.pointerId)?.[1];
    ids.set(e.pointerId,after);
    if (other) {
      const span=distance(before,other);
      if (span>=2) adjust(pose.current.z*distance(after,other)/span,mid(before,other),mid(after,other));
    } else adjust(pose.current.z,before,after);
  };
  const done=(e:PointerEvent<HTMLDivElement>)=>{points.current.delete(e.pointerId); if(!points.current.size)rect.current=null;};
  const keys=(e:KeyboardEvent<HTMLDivElement>)=>{
    if (!ticket.current || e.target!==e.currentTarget || e.altKey || e.ctrlKey || e.metaKey) return;
    switch(e.key) {
      case '+': case '=': step(true);break; case '-': step(false);break; case '0': adjust(1);break;
      case 'ArrowLeft': nudge(1,0);break; case 'ArrowRight': nudge(-1,0);break;
      case 'ArrowUp': nudge(0,1);break; case 'ArrowDown': nudge(0,-1);break;
      default:return;
    } e.preventDefault();e.stopPropagation();
  };

  return <figure className="pw-outcome-image pw-retained-proof" data-open={opened||undefined} data-motion-key={motionKey}
    onKeyDown={e=>{if(opened&&e.key==='Escape'){e.preventDefault();e.stopPropagation();stop(true);}}}>
    <div id={panel} ref={stage} className="pw-proof-stage" role={opened?'region':undefined} tabIndex={opened?0:undefined}
      aria-label={opened?'查看已保留的静态截图':undefined} aria-describedby={opened?hint:undefined}
      onKeyDown={opened?keys:undefined} onPointerDown={opened?down:undefined} onPointerMove={opened?move:undefined}
      onPointerUp={opened?done:undefined} onPointerCancel={opened?done:undefined} onLostPointerCapture={opened?done:undefined}>
      <img ref={img} src={src} alt={label} loading="lazy" decoding="async" draggable={false}
        onLoad={e=>{if(e.currentTarget.naturalWidth>0)setDecoded('ready');}}
        onError={()=>{setDecoded('failed');stop(false);}}/>
    </div>
    <figcaption><div className="pw-proof-bar"><span>{label}</span>
      {windowId && decoded!=='failed' && <Button ref={trigger} type="button" variant="ghost" size="small" leadingIcon={opened?IconCollapse:IconExpand}
        disabled={decoded!=='ready'} aria-controls={panel} aria-expanded={opened} onClick={opened?()=>stop(true):open}>{opened?'收起':'放大查看'}</Button>}
    </div>
    {opened && <><div className="pw-proof-tools" role="group" aria-label="截图显示比例">
      <IconButton type="button" icon={IconMinus} aria-label="缩小截图" disabled={meter.min} onClick={()=>step(false)}/>
      <span className="pw-proof-percent" aria-label={'相对适合窗口的比例 '+meter.percent+'%'}>{meter.percent}%</span>
      <IconButton type="button" icon={IconAdd} aria-label="放大截图" disabled={meter.max} onClick={()=>step(true)}/>
      <Button type="button" variant="ghost" size="small" disabled={meter.min} onClick={()=>adjust(1)}>适合窗口</Button>
      {!!meter.edges && <Button type="button" variant="ghost" size="small" aria-expanded={arrows} aria-controls={panel+'-pan'} onClick={()=>setArrows(!arrows)}>移动位置</Button>}
    </div>{arrows && !!meter.edges && <div id={panel+'-pan'} className="pw-proof-tools" role="group" aria-label="不用拖动，分段查看画面">
      {!!(meter.edges&3) && <><IconButton type="button" icon={IconArrowLeft} aria-label="查看左边" disabled={!(meter.edges&1)} onClick={()=>nudge(1,0)}/>
        <IconButton type="button" icon={IconArrowRight} aria-label="查看右边" disabled={!(meter.edges&2)} onClick={()=>nudge(-1,0)}/></>}
      {!!(meter.edges&12) && <><IconButton type="button" icon={IconArrowUp} aria-label="查看上方" disabled={!(meter.edges&4)} onClick={()=>nudge(0,1)}/>
        <IconButton type="button" icon={IconArrowDown} aria-label="查看下方" disabled={!(meter.edges&8)} onClick={()=>nudge(0,-1)}/></>}
    </div>}<p id={hint}>这是保留的静态图，不会操作手机。双指缩放，拖动或用方向键移动。</p></>}
    {decoded==='failed'&&<p role="status">这张保留的截图未能显示。</p>}</figcaption>
  </figure>;
}
