// PW-37: port of the user's approved Josh neutral adaptation, not an unrelated button.
// Original tutorial and approved source retained in upstream/josh. No MIT claim.
import {useEffect,useRef} from 'react';
import {animate,useMotionValue} from 'motion/react';
import type {PressButtonProps} from './types';

// Keep the approved quick contact / slower release and separate material strain.
// Physical springs retain current velocity when a press interrupts a release.
const contact = {type:'spring',stiffness:1800,damping:58,mass:.7,restDelta:.001,restSpeed:.01} as const;
const recovery = {type:'spring',stiffness:520,damping:30,mass:.8,restDelta:.001,restSpeed:.01} as const;

export function PressButton({label,icon,compact=false,children,className='',disabled,...props}:PressButtonProps){
 const ref=useRef<HTMLButtonElement>(null);
 const press=useMotionValue(0);
 const strain=useMotionValue(0);

 useEffect(()=>{
  const button=ref.current;
  if(!button)return;
  const reduced=window.matchMedia('(prefers-reduced-motion: reduce)');
  let owner:number|string|null=null,inside=false,suppressClick=false,down=false,revision=0;
  const stopPress=press.on('change',value=>button.style.setProperty('--press',String(value)));
  const stopStrain=strain.on('change',value=>button.style.setProperty('--strain',String(value)));
  const reset=()=>{
   revision++;
   down=false;
   // Cancellation ends the force; it must not leave a release playing in the background.
   press.jump(0);
   strain.jump(0);
   button.dataset.state='idle';
  };
  const move=(pressed:boolean)=>{
   if(down===pressed)return;
   down=pressed;
   const run=++revision;
   button.dataset.state=pressed?'pressed':'released';
   if(reduced.matches||document.hidden){
    press.jump(0);
    strain.jump(0);
    button.dataset.state=pressed?'pressed':'idle';
    return;
   }
   const transition=pressed?contact:recovery;
   // animate(MotionValue) takes over from its current value and velocity. Do not
   // reset either value or inject a new release kick when the pointer reverses.
   animate(press,pressed?1:0,{
    ...transition,
    onComplete:()=>{if(run===revision)button.dataset.state=down?'pressed':'idle'},
   });
   animate(strain,pressed ? .58 : 0,transition);
  };
  const contains=(e:PointerEvent)=>{
   const b=button.getBoundingClientRect();
   return e.clientX>=b.left&&e.clientX<=b.right&&e.clientY>=b.top&&e.clientY<=b.bottom;
  };
  const cancel=()=>{
   if(owner!==null)suppressClick=true;
   const previous=owner;
   owner=null;
   inside=false;
   reset();
   if(typeof previous==='number'&&button.hasPointerCapture(previous))button.releasePointerCapture(previous);
  };
  const downPointer=(e:PointerEvent)=>{
   if(disabled||!e.isPrimary||e.button!==0||owner!==null)return;
   suppressClick=false;
   owner=e.pointerId;
   inside=true;
   button.setPointerCapture(e.pointerId);
   move(true);
  };
  const movePointer=(e:PointerEvent)=>{
   if(owner!==e.pointerId)return;
   inside=contains(e);
   move(inside);
  };
  const up=(e:PointerEvent)=>{
   if(owner!==e.pointerId)return;
   inside=contains(e);
   suppressClick=!inside;
   owner=null;
   move(false);
   if(button.hasPointerCapture(e.pointerId))button.releasePointerCapture(e.pointerId);
  };
  const cancelPointer=(e:PointerEvent)=>{if(owner===e.pointerId)cancel()};
  const keydown=(e:KeyboardEvent)=>{
   if(e.key==='Escape'){cancel();return}
   if(disabled||![' ','Enter'].includes(e.key))return;
   e.preventDefault();
   if(e.repeat||owner!==null)return;
   suppressClick=false;
   owner=e.key;
   move(true);
  };
  const keyup=(e:KeyboardEvent)=>{
   if(![' ','Enter'].includes(e.key)||owner!==e.key)return;
   e.preventDefault();
   owner=null;
   move(false);
   // The business action is immediate; visual recovery never gates the click.
   button.click();
  };
  const click=(e:MouseEvent)=>{
   if(suppressClick){e.preventDefault();e.stopImmediatePropagation();suppressClick=false}
  };
  const visibility=()=>{if(document.hidden)cancel()};
  const changed=()=>cancel();
  reset();
  button.addEventListener('pointerdown',downPointer);
  button.addEventListener('pointermove',movePointer);
  button.addEventListener('pointerup',up);
  button.addEventListener('pointercancel',cancelPointer);
  button.addEventListener('lostpointercapture',cancelPointer);
  button.addEventListener('keydown',keydown);
  button.addEventListener('keyup',keyup);
  button.addEventListener('click',click,true);
  button.addEventListener('blur',cancel);
  window.addEventListener('blur',cancel);
  document.addEventListener('visibilitychange',visibility);
  reduced.addEventListener('change',changed);
  return()=>{
   cancel();
   stopPress();
   stopStrain();
   button.removeEventListener('pointerdown',downPointer);
   button.removeEventListener('pointermove',movePointer);
   button.removeEventListener('pointerup',up);
   button.removeEventListener('pointercancel',cancelPointer);
   button.removeEventListener('lostpointercapture',cancelPointer);
   button.removeEventListener('keydown',keydown);
   button.removeEventListener('keyup',keyup);
   button.removeEventListener('click',click,true);
   button.removeEventListener('blur',cancel);
   window.removeEventListener('blur',cancel);
   document.removeEventListener('visibilitychange',visibility);
   reduced.removeEventListener('change',changed);
  };
 },[disabled,press,strain]);

 return <button {...props} ref={ref} type={props.type||'button'} disabled={disabled} className={`pw-press ${compact?'pw-press-compact':''} ${className}`}><span className="pw-press-shadow" aria-hidden="true"/><span className="pw-press-base" aria-hidden="true"/><span className="pw-press-edge" aria-hidden="true"/><span className="pw-press-front"><span className="pw-press-material" aria-hidden="true"/><span className="pw-press-label" data-label>{icon}{children??label}</span></span></button>
}
