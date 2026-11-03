// Actual saved Web Component adapted to controlled facts; no playback timeline.
import {createElement,useLayoutEffect,useRef} from 'react';
import type {Step} from './types';
import './execution-stepper.js';
export function ExecutionSteps({steps,scope,className=''}:{steps:Step[];scope?:string;className?:string}){
 const element=useRef<any>(null);const signature=JSON.stringify(steps);
 useLayoutEffect(()=>{element.current?.resetDisclosure()},[scope]);
 useLayoutEffect(()=>{if(element.current)element.current.steps=steps},[signature]);
 return createElement('pocket-execution-steps',{ref:element,className,language:'zh'});
}
