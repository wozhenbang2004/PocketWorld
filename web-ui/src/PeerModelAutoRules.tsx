import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from './forms-library';
import type { DeviceData as Data } from './devices-workspace';

type Action = (action:string,args:Data,done:(value:Data)=>void)=>void;
const when=(stamp:number)=>new Date(stamp).toLocaleString();
const id=(v:unknown):v is string=>typeof v==='string'&&v.length>0;
const positive=(v:unknown):v is number=>Number.isSafeInteger(v)&&(v as number)>0;
const budget=(v:unknown):v is number=>Number.isSafeInteger(v)&&(v as number)>=0;
const pointer=(v:number)=>v===-1||(Number.isSafeInteger(v)&&v>=0&&v<128&&v%12===0);

export function PeerModelAutoRules({here,peer,busy,act,discard,resource}:{
  here:Data;peer:Data;busy:boolean;act:Action;discard:(handle:string)=>void;resource:(scope:Data)=>ReactNode;
}) {
  const [page,setPage]=useState<Data|null>(null),[choice,setChoice]=useState(''),[notice,setNotice]=useState('');
  const seq=useRef(0),held=useRef(''),release=useRef(discard),timer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);
  release.current=discard;
  const forget=()=>{++seq.current;clearTimeout(timer.current);if(held.current)release.current(held.current);held.current='';};
  const close=()=>{forget();setChoice('');setPage(null);setNotice('');};
  useEffect(()=>{
    const hide=()=>{if(document.visibilityState!=='visible')close();};
    document.addEventListener('visibilitychange',hide);return()=>{forget();document.removeEventListener('visibilitychange',hide);};
  },[]);
  const read=(offset:number)=>{
    close();const original=seq.current;
    act('devices.readOwnerModelRules',{here:here.deviceId,hereInstance:here.instanceId,hereFingerprint:here.certificateFingerprint,peer:peer.deviceId,
      instance:peer.instanceId,fingerprint:peer.certificateFingerprint,epoch:peer.authorizationEpoch,offset},out=>{
      const v=out.view,handle=out.rulesHandle;
      if(original!==seq.current||document.visibilityState!=='visible'){if(id(handle))release.current(handle);return;}
      if(out.kind!=='ownerModelRules'||!id(handle)||!v||v.here!==here.deviceId||v.hereInstance!==here.instanceId||v.hereFingerprint!==here.certificateFingerprint||
        v.peer!==peer.deviceId||v.instance!==peer.instanceId||v.fingerprint!==peer.certificateFingerprint||v.epoch!==peer.authorizationEpoch||
        v.offset!==offset||!pointer(v.previous)||!pointer(v.next)||!positive(v.until)||v.until<=Date.now()||typeof v.hasCurrentGrant!=='boolean'||
        !Array.isArray(v.rows)||v.rows.length>12||!v.rows.every((r:Data)=>r&&id(r.model)&&r.resource&&typeof r.resource==='object'&&!Array.isArray(r.resource)&&
          budget(r.tools)&&budget(r.models)&&positive(r.expiresAt)&&(positive(r.disabledAt)?!r.choice:id(r.choice)))) {
        if(id(handle))release.current(handle);setNotice('无法核对当前规则，请重新打开。');return;
      }
      held.current=handle;setPage(v);
      timer.current=setTimeout(()=>{close();setNotice('查看时间已到，请重新核对。');},Math.min(90_000,v.until-Date.now()));
    });
  };
  const confirm=(r:Data)=>{
    if(!page||page.until<=Date.now()||!held.current||choice!==r.choice||r.disabledAt)return;
    const handle=held.current,original=seq.current;
    act('devices.disableOwnerModelRule',{rulesHandle:handle,choice:r.choice,acknowledged:true},out=>{
      if(original!==seq.current)return;
      const result=out.modelRuleDisabled;held.current='';close();
      setNotice(result?.peer===peer.deviceId&&result?.choice===r.choice&&positive(result?.disabledAt)?
        '本机已改为逐单确认。下次收到这个范围的新工作时，会先问你。':'未核对到本机更改记录，请重读原规则。');
    });
  };
  return <div className="pw-source-copy"><Button variant="secondary" disabled={busy} onClick={()=>page?close():read(0)}>
    {page?'收起本机模型规则':'查看本机的自动处理规则'}</Button>
    {notice&&<p role="status">{notice}</p>}
    {page&&<><p>只看这台伙伴直接交给本机的工作。不会连接伙伴或读取它的任务。</p>
      {!page.hasCurrentGrant?<p>本机没有给这台伙伴仍有效的原授权。</p>:page.rows.length===0&&<p>{page.next!==-1||page.previous!==-1?'这页资源没有保存过自动处理规则。':'本次授权下，没有保存过自动处理规则。'}</p>}
      {page.rows.map((r:Data,index:number)=><div key={r.choice||index}>
        <h4>{r.model} · {r.disabledAt?'逐单确认':'允许自动处理'}</h4>{resource(r.resource)}
        <p>原授权上限：模型 {r.models} 次、工具 {r.tools} 次，至 {when(r.expiresAt)}。不是当前剩余次数。</p>
        {r.disabledAt?<p>本机于 {when(r.disabledAt)} 关闭了这条规则。</p>:choice===r.choice?
          <div role="group" aria-label="确认停止自动处理"><p>以后相同范围的新工作会先问你。已经依赖此规则的工作，后续核对也不能再沿用它。已经发出的请求不会撤回；不撤销设备授权，也不向伙伴发停止命令。</p>
            <Button variant="secondary" disabled={busy} onClick={()=>confirm(r)}>确认改为逐单确认</Button>
            <Button variant="ghost" disabled={busy} onClick={()=>setChoice('')}>保留原规则</Button></div>:
          <Button variant="secondary" disabled={busy} onClick={()=>setChoice(r.choice)}>改为逐单确认</Button>}
      </div>)}
      <div className="pw-form-actions">{page.previous!==-1&&<Button variant="ghost" disabled={busy} onClick={()=>read(page.previous)}>上一组范围</Button>}
        {page.next!==-1&&<Button variant="ghost" disabled={busy} onClick={()=>read(page.next)}>下一组范围</Button>}</div>
    </>}
  </div>;
}
