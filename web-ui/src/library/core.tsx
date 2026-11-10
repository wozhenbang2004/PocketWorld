// Adapted from the retained Affect library v3 common.tsx / ui.tsx primitives.
import {useEffect,useRef,useState,useSyncExternalStore,type ReactNode} from 'react';
import {IconSparkles,IconCheck,IconClose,IconChevronDown,IconSend,IconSearch,IconCopy,IconRefresh,IconMicrophone,IconAttach,IconPause,IconPlay,IconClock,IconFile,IconAdd,IconTrash,IconEdit,IconWarning} from '../icons';
export function FrameA({children,className=''}:{children:ReactNode;className?:string}) {return <div className={`afa afa-clean pw-library ${className}`} data-theme="light">{children}</div>}
export function FrameB({children,className=''}:{children:ReactNode;className?:string}) {return <div className={`cb-demo pw-library ${className}`} data-theme="light" data-variant="clean">{children}</div>}
export function Card({children,className=''}:{children:ReactNode;className?:string}) {return <section className={`cb-card ${className}`}>{children}</section>}
const libraryIcons:Record<string,typeof IconSparkles>={spark:IconSparkles,check:IconCheck,close:IconClose,chevron:IconChevronDown,arrow:IconSend,search:IconSearch,copy:IconCopy,refresh:IconRefresh,mic:IconMicrophone,attach:IconAttach,pause:IconPause,play:IconPlay,clock:IconClock,file:IconFile,plus:IconAdd,trash:IconTrash,edit:IconEdit,warn:IconWarning};
export function Icon({name='spark',size=16}:{name?:string;size?:number}) {
 const Glyph=libraryIcons[name]||IconSparkles;
 return <Glyph size={size} aria-hidden="true"/>;
}
export function ToolButton({label,icon,onClick,disabled,active}:{label:string;icon?:string;onClick?:()=>void;disabled?:boolean;active?:boolean}) {return <button type="button" className={`afa-iconbtn ${active?'afa-active':''}`} aria-label={label} title={label} aria-pressed={active} onClick={onClick} disabled={disabled}><Icon name={icon}/></button>}
export function Button({children,onClick,disabled,primary=false}:{children:ReactNode;onClick?:()=>void;disabled?:boolean;primary?:boolean}) {return <button type="button" className={`cb-button ${primary?'cb-primary':''}`} onClick={onClick} disabled={disabled}>{children}</button>}
export function useReducedMotion(){const[reduce,setReduce]=useState(()=>typeof window!=='undefined'&&window.matchMedia('(prefers-reduced-motion: reduce)').matches);useEffect(()=>{const m=window.matchMedia('(prefers-reduced-motion: reduce)');const changed=()=>setReduce(m.matches);changed();m.addEventListener('change',changed);return()=>m.removeEventListener('change',changed)},[]);return reduce}
const subscribeVisibility=(changed:()=>void)=>{document.addEventListener('visibilitychange',changed);return()=>document.removeEventListener('visibilitychange',changed)};
const documentVisible=()=>!document.hidden;
export function useDocumentVisible(){return useSyncExternalStore(subscribeVisibility,documentVisible,()=>true)}
export function useMotionAllowed(){const reduce=useReducedMotion(),visible=useDocumentVisible();return visible&&!reduce}
export function useVisible(ref:{current:HTMLElement|null}){const visible=useDocumentVisible();const[intersecting,setIntersecting]=useState(true);useEffect(()=>{const o=typeof IntersectionObserver==='undefined'?null:new IntersectionObserver(es=>setIntersecting(!!es[0]?.isIntersecting));if(ref.current)o?.observe(ref.current);return()=>o?.disconnect()},[ref]);return visible&&intersecting}
export const statusLabel=(value:string)=>({pending:'待执行',running:'执行中',working:'执行中',complete:'已完成',completed:'已完成',success:'已完成',returned:'已返回',unknown:'待核对',waiting:'等待中',needs_approval:'需授权',waiting_approval:'需授权',error:'失败',failed:'失败',stopped:'已停止',idle:'待命',listening:'正在听写',speaking:'正在朗读',thinking:'正在思考'} as Record<string,string>)[value]||value||'状态未知';
export const records=(data:any):any[]=>Array.isArray(data)?data:Array.isArray(data?.items)?data.items:Array.isArray(data?.records)?data.records:[];
export const labelOf=(x:any)=>String(x?.title??x?.name??x?.label??x?.text??'');
export const textOf=(x:any)=>typeof x==='string'?x:String(x?.text??x?.content??x?.summary??'');
export function useLatest<T>(value:T){const ref=useRef(value);ref.current=value;return ref}
