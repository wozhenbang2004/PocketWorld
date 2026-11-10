/* Controlled BoardUI slider adaptation. React Aria retains the real value model;
 * Ramotion's ValueView tracking elevation / .22s return informs the bubble.
 * Exact originals and MIT licenses are retained in ../upstream/uiux. */
import {useId} from 'react';
import {motion} from 'motion/react';
import {Label,Slider,SliderThumb,SliderTrack} from 'react-aria-components';
import {useMotionAllowed} from '../../library/core';
import './value-controls.css';
export type FluidValueSliderProps={
 label:string;value:number;onChange:(value:number)=>void;minValue:number;maxValue:number;
 step?:number;disabled?:boolean;unit?:string;hint?:string;
};
/** Companion to exact numeric entry; changes the local draft only. */
export function FluidValueSlider({label,value,onChange,minValue,maxValue,step=1,disabled=false,unit='',hint}:FluidValueSliderProps){
 const motionAllowed=useMotionAllowed();const id=useId();
 const valid=Number.isFinite(value)&&value>=minValue&&value<=maxValue;
 return <Slider className="pw-fluid-slider" value={valid?value:minValue} minValue={minValue} maxValue={maxValue} step={step} isDisabled={disabled||!valid}
 onChange={next=>{if(!disabled&&valid)onChange(next)}} aria-describedby={hint?`${id}-hint`:undefined}>
 {({state})=><><div className="pw-fluid-label"><Label>{label}</Label><output>{valid?`${value}${unit}`:'请先输入有效数值'}</output></div>
 <SliderTrack className="pw-fluid-track"><span className="pw-fluid-track-base" aria-hidden/><span className="pw-fluid-track-fill" aria-hidden style={{insetInlineEnd:`${(1-state.getThumbPercent(0))*100}%`}}/>
 <SliderThumb className="pw-fluid-thumb" aria-label={label}>{({isDragging,isFocusVisible})=><>
 {/* Ramotion tracking ends on release; retained pointer focus is not ongoing contact.
     Keyboard focus keeps the exact value available while adjusting with the keys. */}
 <motion.span key={motionAllowed?'moving':'settled'} className="pw-fluid-bubble" aria-hidden initial={false} animate={{y:isDragging||isFocusVisible?-32:0,scale:isDragging||isFocusVisible?1:.6,opacity:isDragging||isFocusVisible?1:0}}
 transition={!motionAllowed?{duration:0}:isDragging||isFocusVisible?{type:'spring',stiffness:420,damping:24,mass:.6}:{duration:.22,ease:[.16,1,.3,1]}}>{state.getThumbValueLabel(0)}{unit}</motion.span>
 <span className="pw-fluid-thumb-core" aria-hidden/></>}</SliderThumb></SliderTrack>
 {hint&&<p className="pw-value-hint" id={`${id}-hint`}>{hint}</p>}</>}
 </Slider>
}
