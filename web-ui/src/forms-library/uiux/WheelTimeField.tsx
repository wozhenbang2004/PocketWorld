/* ncdai/react-wheel-picker fbe21d7: actual wheel geometry, inertia, keyboard,
 * typeahead and grouped focus. PocketWorld supplies a controlled reminder draft. */
import {useRef} from 'react';
import {TextField} from '../index';
import {WheelPicker,WheelPickerWrapper,type WheelPickerOption} from './wheel';
import './value-controls.css';
const pad=(value:number)=>String(value).padStart(2,'0');
const hours=Array.from({length:24},(_,value)=>({value:pad(value),label:pad(value)}));
const minutes=Array.from({length:60},(_,value)=>({value:pad(value),label:pad(value)}));
export type WheelValuePickerProps<T extends string|number>={label:string;value:T;options:WheelPickerOption<T>[];onChange:(value:T)=>void;disabled?:boolean};
export function WheelValuePicker<T extends string|number>({label,value,options,onChange,disabled=false}:WheelValuePickerProps<T>){
 const live=useRef({disabled,onChange});live.current={disabled,onChange};
 return <WheelPicker ariaLabel={label} value={value} options={options} disabled={disabled} optionItemHeight={32} visibleCount={12}
 onValueChange={next=>{if(!live.current.disabled)live.current.onChange(next)}}/>
}
export function WheelTimeField({label,value,onChange,isDisabled=false}:{label:string;value:string;onChange:(value:string)=>void;isDisabled?:boolean}){
 const latest=useRef({value,onChange,isDisabled});latest.current={value,onChange,isDisabled};
 const updatePart=(part:0|1,next:string)=>{
  const current=latest.current;if(current.isDisabled)return;
  const parts=current.value.split(':');parts[part]=next;
  const nextTime=parts.join(':');latest.current={...current,value:nextTime};current.onChange(nextTime);
 };
 const valid=/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value);const [hour,minute]=value.split(':');
 return <div className="pw-time-field">
 <TextField label={label} value={value} onChange={next=>onChange(next.slice(0,5))} isDisabled={isDisabled} hint="直接输入精确时间，或滑动下方时、分。修改后仍需核对提醒计划。"/>
 {valid&&<div className="pw-time-wheel" aria-label="调整提醒当地时间" aria-disabled={isDisabled||undefined}>
 <div className="pw-time-wheel-labels" aria-hidden><span>时</span><span>分</span></div>
 <WheelPickerWrapper>
 <WheelValuePicker label="提醒小时" value={hour} options={hours} disabled={isDisabled} onChange={next=>updatePart(0,next)}/>
 <span className="pw-time-separator" aria-hidden>:</span>
 <WheelValuePicker label="提醒分钟" value={minute} options={minutes} disabled={isDisabled} onChange={next=>updatePart(1,next)}/>
 </WheelPickerWrapper></div>}
 </div>
}
