import { useLayoutEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, TextAreaField } from './forms-library';
import type { DeviceData } from './devices-workspace';

type Matrix={ side:number; bits:Uint8Array };
export type ShownPairingInvitation={raw:string; device:string; instance:string; fingerprint:string; expiresAt:number; until:number; qr?:Matrix};
function matrix(value:DeviceData|undefined):Matrix|undefined{
  const side=value?.modules,bits=value?.bits;
  if(!Number.isInteger(side)||side<29||side>185||(side-29)%4!==0||typeof bits!=='string'||bits.length!==4*Math.ceil(Math.ceil(side*side/8)/3))return;
  try{
    const raw=atob(bits);if(raw.length!==Math.ceil(side*side/8))return;
    const bytes=Uint8Array.from(raw,c=>c.charCodeAt(0));
    for(let y=0;y<side;y++)for(let x=0;x<side;x++){
      if(x>=4&&x<side-4&&y>=4&&y<side-4)continue;
      const i=y*side+x;if(bytes[i>>>3]&(128>>>(i&7)))return;
    }
    return {side,bits:bytes};
  }catch{return;}
}
export function shownPairingInvitation(value:DeviceData,local:DeviceData):ShownPairingInvitation{
  const raw=value.invitation;
  if(value.kind!=='invitation'||value.displayOnce!==true||typeof raw!=='string'||!raw||raw.length>16384)throw Error('没有取得本次原邀请。');
  const source=JSON.parse(raw),left=Math.min(value.remainingMs,source.expiresAt-Date.now());
  if(source.messageType!=='PairingInvitation'||source.inviterDeviceId!==local.deviceId||source.instanceId!==local.instanceId
    ||!local.certificateFingerprint||source.certificateFingerprint!==local.certificateFingerprint)throw Error('邀请与当前本机身份不一致，未显示。');
  if(!Number.isSafeInteger(source.expiresAt)||source.expiresAt!==value.expiresAt||!Number.isSafeInteger(left)||left<=0||left>300000)throw Error('本次邀请已过期或无法核对时限。');
  return {raw,device:source.inviterDeviceId,instance:source.instanceId,fingerprint:source.certificateFingerprint,
    expiresAt:source.expiresAt,until:performance.now()+left,qr:matrix(value.qr)};
}

/** Paint directly on the present surface. No image URL, file, clipboard or share target is created. */
export function PairingInvitationDisplay({invite}:{invite:ShownPairingInvitation}){
  const available=useRef<HTMLDivElement>(null),surface=useRef<HTMLCanvasElement>(null);
  const [drawn,setDrawn]=useState(false);
  useLayoutEffect(()=>{
    const q=invite.qr,box=available.current,canvas=surface.current;
    if(!q||!box||!canvas){setDrawn(false);return;}
    const clear=()=>{canvas.width=canvas.height=0;};
    const paint=()=>{
      const ratio=window.devicePixelRatio||1;
      const space=Math.min(box.clientWidth,420,(window.visualViewport?.height||window.innerHeight)*0.62);
      const scale=Math.min(8,Math.floor(space*ratio/q.side));
      if(document.hidden||Date.now()>=invite.expiresAt||performance.now()>=invite.until||!Number.isFinite(scale)||scale<3){clear();setDrawn(false);return;}
      const size=q.side*scale;canvas.width=canvas.height=size;
      const ctx=canvas.getContext('2d',{alpha:false});if(!ctx){clear();setDrawn(false);return;}
      canvas.style.width=canvas.style.height=(size/ratio)+'px';ctx.fillStyle='#fff';ctx.fillRect(0,0,size,size);
      ctx.fillStyle='#000';
      for(let y=0;y<q.side;y++)for(let x=0;x<q.side;x++){
        const i=y*q.side+x;if(q.bits[i>>>3]&(128>>>(i&7)))ctx.fillRect(x*scale,y*scale,scale,scale);
      }
      setDrawn(true);
    };
    paint();const sizing=typeof ResizeObserver==='function'?new ResizeObserver(paint):null;sizing?.observe(box);
    window.addEventListener('resize',paint);window.visualViewport?.addEventListener('resize',paint);
    return ()=>{sizing?.disconnect();window.removeEventListener('resize',paint);window.visualViewport?.removeEventListener('resize',paint);clear();};
  },[invite]);
  const text=<TextAreaField label="完整短时邀请，选中后可复制" value={invite.raw} isReadOnly/>;
  return <>
    <div ref={available} style={{width:'100%',minWidth:0,textAlign:'center'}}>
      <canvas ref={surface} role="img" aria-label="本次设备配对邀请二维码" hidden={!drawn} style={{imageRendering:'pixelated',verticalAlign:'middle'}}/>
    </div>
    {drawn?<><p>只给你要配对的设备看。另一部手机可拍下此码，在「对方已发邀请」选择二维码图片。照片含短时邀请，勿转发。</p>
      <Accordion><AccordionItem id="original-pair-invitation" title="改用完整邀请文本">{text}</AccordionItem></Accordion></>
      :<><p>当前无法清晰展示单个完整码。请在另一台设备的「对方已发邀请」粘贴以下原文。</p>{text}</>}
    <p>有效至 {new Date(invite.expiresAt).toLocaleTimeString()}。两端仍须分别核对身份和权限。</p>
  </>;
}
