import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import type {RemoteData as Data} from './remote-task-workspace';
import {exactLivePhoneDraft,type PhoneDraftPin} from './remote-draft-continuity';

const hash=async(body:string)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(body))),v=>v.toString(16).padStart(2,'0')).join('');
/** No tool invocation. Match local pin to this native observation; entering its editor still needs an owner click. */
export function RemotePhoneDraftFollow({pin,snapshot,active,wouldReplace,open}:{pin:PhoneDraftPin;snapshot:Data;active:boolean;wouldReplace:boolean;open:(row:Data)=>void}){
  const row=exactLivePhoneDraft(snapshot,pin),body=row?.text,turn=String(snapshot.windowHandle??'');
  const heading=useRef<HTMLHeadingElement>(null);
  useEffect(()=>{heading.current?.focus({preventScroll:true});heading.current?.scrollIntoView({block:'nearest'})},[pin.id,pin.revision]);
  const [verified,confirm]=useState<{body:string;sha:string;window:string}|null>(null),[replace,ask]=useState(false),live=useRef(snapshot);live.current=snapshot;
  useEffect(()=>{let current=true;confirm(null);ask(false);if(typeof body==='string')void hash(body).then(sha=>{if(current&&sha===pin.textSha256)confirm({body,sha,window:turn})}).catch(()=>{});return()=>{current=false}},[body,pin.textSha256,turn]);
  const ok=!!row&&!!verified&&verified.body===body&&verified.sha===pin.textSha256&&verified.window===turn;
  return <div className="pw-local-inline-notice" role="status"><h4 ref={heading} tabIndex={-1}>手机所选原稿 v{pin.revision}</h4>
    <p>{ok?'已在原主机会话核对同一份手机草稿，没有排队或发送。':'这次观察尚未核对同一版本、正文与会话。可以查询原作业；不会改开相似草稿。'}</p>
    {ok&&<>{wouldReplace&&replace&&<p>切换会替换此页另一份尚未保存的输入；不想丢失请先保存它。</p>}
      <Button size="small" variant="secondary" disabled={!active} onClick={()=>{
        const now=exactLivePhoneDraft(live.current,pin);if(!now||now.text!==verified?.body||live.current.windowHandle!==verified.window)return;
        if(wouldReplace&&!replace){ask(true);return}ask(false);open(now)
      }}>{wouldReplace&&replace?'放弃此页未保存输入，打开所选原稿':'打开核对过的版本'}</Button></>}
  </div>;
}
