// PW-37: port of the user's approved Josh neutral adaptation, not an unrelated button.
// Original tutorial and approved source retained in upstream/josh. No MIT claim.
import {useEffect,useRef} from 'react';
import type {PressButtonProps} from './types';
import {motionDuration} from './motion-tokens';
export function PressButton({label,icon,compact=false,children,className='',disabled,...props}:PressButtonProps){
 const ref=useRef<HTMLButtonElement>(null);
 useEffect(()=>{const button=ref.current;if(!button)return;
  const reduced=window.matchMedia('(prefers-reduced-motion: reduce)');let press=0,strain=0,frame=0,owner:number|string|null=null,inside=false,suppressClick=false,down=false;
  const paint=(value:number,compression:number)=>{press=value;strain=compression;button.style.setProperty('--press',String(value));button.style.setProperty('--strain',String(compression))};
  const stop=()=>{if(frame)cancelAnimationFrame(frame);frame=0};const easeOut=(t:number)=>1-Math.pow(1-t,3);
  const move=(pressed:boolean)=>{if(down===pressed)return;down=pressed;button.dataset.state=pressed?'pressed':'released';stop();if(reduced.matches||document.hidden){paint(pressed?1:0,pressed ? .58:0);button.dataset.state=pressed?'pressed':'idle';return}const from=press,fromStrain=strain,start=performance.now(),duration=pressed?motionDuration.press:motionDuration.release;const tick=(now:number)=>{const t=Math.min(1,(now-start)/duration);let value:number,compression:number;if(pressed){value=t<.68?from+(1.03-from)*easeOut(t/.68):1.03-.03*easeOut((t-.68)/.32);compression=t<.68?fromStrain+(1-fromStrain)*easeOut(t/.68):1-.42*easeOut((t-.68)/.32)}else{value=t<.72?from+(-.065-from)*easeOut(t/.72):-.065+.065*easeOut((t-.72)/.28);compression=t<.72?fromStrain+(-.08-fromStrain)*easeOut(t/.72):-.08+.08*easeOut((t-.72)/.28)}paint(value,compression);if(t<1)frame=requestAnimationFrame(tick);else{frame=0;paint(pressed?1:0,pressed ? .58:0);button.dataset.state=pressed?'pressed':'idle'}};frame=requestAnimationFrame(tick)};
  const contains=(e:PointerEvent)=>{const b=button.getBoundingClientRect();return e.clientX>=b.left&&e.clientX<=b.right&&e.clientY>=b.top&&e.clientY<=b.bottom};
  const cancel=()=>{if(owner!==null)suppressClick=true;const previous=owner;owner=null;inside=false;move(false);if(typeof previous==='number'&&button.hasPointerCapture(previous))button.releasePointerCapture(previous)};
  const downPointer=(e:PointerEvent)=>{if(disabled||!e.isPrimary||e.button!==0||owner!==null)return;suppressClick=false;owner=e.pointerId;inside=true;button.setPointerCapture(e.pointerId);move(true)};
  const movePointer=(e:PointerEvent)=>{if(owner!==e.pointerId)return;inside=contains(e);move(inside)};
  const up=(e:PointerEvent)=>{if(owner!==e.pointerId)return;inside=contains(e);suppressClick=!inside;owner=null;move(false);if(button.hasPointerCapture(e.pointerId))button.releasePointerCapture(e.pointerId)};
  const cancelPointer=(e:PointerEvent)=>{if(owner===e.pointerId)cancel()};
  const keydown=(e:KeyboardEvent)=>{if(e.key==='Escape'){cancel();return}if(disabled||![' ','Enter'].includes(e.key))return;e.preventDefault();if(e.repeat||owner!==null)return;suppressClick=false;owner=e.key;move(true)};
  const keyup=(e:KeyboardEvent)=>{if(![' ','Enter'].includes(e.key)||owner!==e.key)return;e.preventDefault();owner=null;move(false);button.click()};
  const click=(e:MouseEvent)=>{if(suppressClick){e.preventDefault();e.stopImmediatePropagation();suppressClick=false}};
  const visibility=()=>{if(document.hidden){cancel();stop();paint(0,0);button.dataset.state='idle'}};const changed=()=>{stop();paint(down?1:0,down ? .58:0);button.dataset.state=down?'pressed':'idle'};
  button.addEventListener('pointerdown',downPointer);button.addEventListener('pointermove',movePointer);button.addEventListener('pointerup',up);button.addEventListener('pointercancel',cancelPointer);button.addEventListener('lostpointercapture',cancelPointer);button.addEventListener('keydown',keydown);button.addEventListener('keyup',keyup);button.addEventListener('click',click,true);button.addEventListener('blur',cancel);window.addEventListener('blur',cancel);document.addEventListener('visibilitychange',visibility);reduced.addEventListener('change',changed);
  return()=>{stop();paint(0,0);button.removeEventListener('pointerdown',downPointer);button.removeEventListener('pointermove',movePointer);button.removeEventListener('pointerup',up);button.removeEventListener('pointercancel',cancelPointer);button.removeEventListener('lostpointercapture',cancelPointer);button.removeEventListener('keydown',keydown);button.removeEventListener('keyup',keyup);button.removeEventListener('click',click,true);button.removeEventListener('blur',cancel);window.removeEventListener('blur',cancel);document.removeEventListener('visibilitychange',visibility);reduced.removeEventListener('change',changed)};
 },[disabled]);
 return <button {...props} ref={ref} type={props.type||'button'} disabled={disabled} className={`pw-press ${compact?'pw-press-compact':''} ${className}`}><span className="pw-press-shadow" aria-hidden="true"/><span className="pw-press-base" aria-hidden="true"/><span className="pw-press-edge" aria-hidden="true"/><span className="pw-press-front"><span className="pw-press-material" aria-hidden="true"/><span className="pw-press-label" data-label>{icon}{children??label}</span></span></button>
}
