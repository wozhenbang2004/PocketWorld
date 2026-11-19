import {useLayoutEffect,useRef} from 'react';

type Place={scope:object|null;read?:number;target?:number;points:Map<number,number>};
const empty=(scope:object|null):Place=>({scope,points:new Map()});
function viewport(node:HTMLElement){
  const dialog=node.closest('[role="dialog"],dialog');
  for(let p=node.parentElement;p&&(!dialog||dialog.contains(p));p=p.parentElement){
    if(p.scrollHeight>p.clientHeight+1&&/^(auto|scroll|overlay)$/.test(getComputedStyle(p).overflowY))return p;
  }
  // Never move the conversation behind a drawer merely because its short text needs no scrolling.
  return dialog?null:document.scrollingElement instanceof HTMLElement?document.scrollingElement:null;
}
function top(host:HTMLElement){
  return host===document.scrollingElement?0:host.getBoundingClientRect().top+host.clientTop;
}

/** Ephemeral offsets only. Reading any page, including a previous one, still goes through
 * the caller's original source/digest check. No body, DOM snapshot, asynchronous scroll or retry. */
export function usePhoneOriginalReadingPlace(scope:object|null,available:boolean,offset:number|undefined,check:()=>boolean){
  const body=useRef<HTMLParagraphElement>(null),saved=useRef<Place|null>(null);
  const active=scope&&available&&!document.hidden?scope:null;
  if(!saved.current||saved.current.scope!==active)saved.current=empty(active);
  const place=saved.current;
  useLayoutEffect(()=>{
    if(!active||!check()||saved.current!==place||offset===undefined)return;
    place.read=offset;
    const node=body.current;
    if(place.target!==offset||!node?.isConnected||node.closest('[inert],[hidden]'))return;
    place.target=undefined;
    // If the reader moved to another real control during the read, do not steal its focus or scroll.
    const focused=document.activeElement;
    if(focused instanceof HTMLElement&&focused!==node&&focused.matches('button,a[href],input,textarea,select,[contenteditable="true"],[tabindex="0"]'))return;
    const host=viewport(node),rect=node.getBoundingClientRect();
    node.focus({preventScroll:true});
    if(host){const delta=Math.min(place.points.get(offset)||0,Math.max(0,rect.height-20));
      host.scrollTop+=rect.top-top(host)+delta;}
  },[active,offset,place]); // No movement on original opening or on ordinary native state publications.
  function leaving(to:number){
    if(!active||saved.current!==place||document.hidden||!check())return;
    const node=body.current,host=node?.isConnected?viewport(node):null;
    if(offset!==undefined&&place.read===offset&&host&&node){
      const r=node.getBoundingClientRect(),point=Math.max(0,Math.min(top(host)-r.top,r.height-20));
      place.points.delete(offset);place.points.set(offset,point);
      if(place.points.size>12){const first=place.points.keys().next();if(!first.done)place.points.delete(first.value);}
    }
    place.target=to;
  }
  return {body,leaving,prior:active?place.read:undefined};
}
