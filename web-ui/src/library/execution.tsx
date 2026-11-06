// Actual saved Web Component adapted to controlled facts; no playback timeline.
import {createElement,useLayoutEffect,useRef} from 'react';
import type {Step} from './types';
import './execution-stepper.js';
export function ExecutionSteps({steps,scope,className='',active=true,onSelect,onToggle}:{steps:Step[];scope?:string;className?:string;active?:boolean;onSelect?:(id:string)=>void;onToggle?:(id:string)=>void}){
 const element=useRef<any>(null);const signature=JSON.stringify(steps);
 useLayoutEffect(()=>{element.current?.resetDisclosure()},[scope]);
 useLayoutEffect(()=>{if(element.current)element.current.steps=steps},[signature]);
 useLayoutEffect(()=>{const node=element.current;if(!node)return;const select=(event:CustomEvent<{id:string}>)=>onSelect?.(event.detail.id);const toggle=(event:CustomEvent<{id:string}>)=>onToggle?.(event.detail.id);node.addEventListener('stepselect',select);node.addEventListener('steptoggle',toggle);return()=>{node.removeEventListener('stepselect',select);node.removeEventListener('steptoggle',toggle)}},[onSelect,onToggle]);
 useLayoutEffect(()=>{if(element.current)element.current.active=active;return()=>{if(element.current)element.current.active=false}},[active]);
 return createElement('pocket-execution-steps',{ref:element,className,language:'zh','data-vaul-no-drag':''});
}
