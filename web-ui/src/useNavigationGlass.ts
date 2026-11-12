import {useCallback,useRef} from 'react';
import {liquidGlass} from './shell-library/upstream/glasswork/lens.js';

/** Attach the retained Glasswork lens to one existing navigation surface. */
export function useNavigationGlass<T extends HTMLElement>(){
 const attached=useRef<T|null>(null);
 const binding=useRef<{element:T;dispose:()=>void}|null>(null);
 return useCallback((element:T|null)=>{
  attached.current=element;
  // Radix Slot can replace its composed ref while retaining the same button.
  // Let that commit finish before treating a null ref as an actual removal.
  if(!element){
   queueMicrotask(()=>{
    if(attached.current)return;
    binding.current?.dispose();
    binding.current=null;
   });
   return;
  }
  if(binding.current?.element===element)return;
  binding.current?.dispose();
  binding.current=null;
  const opaque=window.matchMedia('(prefers-reduced-transparency: reduce), (prefers-contrast: more), (forced-colors: active)');
  // A single displacement pass keeps the small controls neutral and avoids
  // three color-channel filters. Geometry and rim falloff remain upstream.
  const lens=liquidGlass(element,{dispersion:0,enabled:!opaque.matches});
  const update=()=>lens.update({enabled:!opaque.matches});
  opaque.addEventListener('change',update);
  binding.current={element,dispose:()=>{
   opaque.removeEventListener('change',update);
   lens.destroy();
  }};
 },[]);
}
