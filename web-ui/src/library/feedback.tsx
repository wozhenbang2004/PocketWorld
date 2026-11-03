// PW-38/39/40/41/42: source components, controlled product adapters.
import {useEffect,useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import {Toaster as SourceToaster,toast,type ToasterProps} from 'sonner';
import {ThinkingLoader as SourceThinkingLoader} from './adapted/thinking-loader-clean';
import {Skeleton,ShimmerText} from './adapted/shimmer-loader';
import {useReducedMotion} from './core';
export {toast,Skeleton,ShimmerText};
export {ProgressBar,ProgressCircle,ProgressTrack} from './adapted/boardui-progress';
export function Toaster(props:ToasterProps){return <SourceToaster {...props} theme="light" richColors={false} position={props.position||'bottom-center'} offset={props.offset??'calc(env(safe-area-inset-bottom) + 112px)'} closeButton toastOptions={{...props.toastOptions,style:{background:'#ffffff',color:'#252525',border:'1px solid #dedede',boxShadow:'0 1px 2px #0000000a, 0 8px 24px #00000014',...props.toastOptions?.style}}}/>}
export function ThinkingLoader({phase='正在处理',done=false,doneLabel='处理已结束',durationMs,className=''}:{phase?:string|number;phases?:string[];done?:boolean;doneLabel?:string;durationMs?:number;className?:string}){
 const text=typeof phase==='string'?phase:'正在处理';
 return <SourceThinkingLoader phases={[text]} phase={0} done={done} doneLabel={doneLabel} durationMs={durationMs} size="sm" variant="plain" className={`pw-thinking-loader ${className}`}/>
}
export function ShimmerLoader({children,loading=true,width='100%',height=14,className=''}:{children?:ReactNode;loading?:boolean;width?:number|string;height?:number;className?:string}){return loading?<div className={`pw-shimmer ${className}`} aria-busy="true" aria-label="正在载入"><Skeleton width={width} height={height}/></div>:<>{children}</>}
/** Pure presentation only: keep editors, WebViews and executing owners outside this transition. */
export function BlurCrossfade({identity,children,className=''}:{identity:string|number;children:ReactNode;className?:string}){
 const reduce=useReducedMotion();const content=useRef<HTMLDivElement>(null);const last=useRef({identity,children});const[shown,setShown]=useState(identity);const[previous,setPrevious]=useState<{identity:string|number;children:ReactNode}|null>(null);const[height,setHeight]=useState<number>();
 if(shown!==identity){setShown(identity);setPrevious(reduce?null:last.current)}last.current={identity,children};
 useEffect(()=>{if(!previous)return;const timer=setTimeout(()=>setPrevious(null),420);return()=>clearTimeout(timer)},[previous]);
 useLayoutEffect(()=>{const el=content.current;if(!el)return;const measure=()=>setHeight(el.getBoundingClientRect().height);measure();const observer=new ResizeObserver(measure);observer.observe(el);return()=>observer.disconnect()},[identity]);
 return <div className={`pw-crossfade bmx-crossfade-size ${className}`} style={{height}}>{previous&&<div className="bmx-crossfade-content bmx-crossfade-out" aria-hidden="true" inert key={`out-${previous.identity}`}>{previous.children}</div>}<div className="bmx-crossfade-content bmx-crossfade-in" key={identity} ref={content}>{children}</div></div>
}
