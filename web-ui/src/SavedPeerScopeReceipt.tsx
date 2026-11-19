import { useEffect, useRef, useState } from 'react';
import { Button } from './forms-library';
type Data = Record<string, any>;
const when = (value:number) => new Date(value).toLocaleString();
const unavailable:Record<string,string> = {
  no_matching_record: '本机没有能与这次原配对同时核对的互授回执。仅有配对记录不等于对方给过工作权限。',
  not_single: '这里有多份记录，无法唯一核对；不合并或猜测授权。',
  expired_or_unverifiable: '本机原回执已到期、身份有变或不能核实，未显示授权范围。'
};

export function SavedPeerScopeReceipt({ here, peer, busy, read }: {
  here: Data; peer: Data; busy: boolean; read: (done:(row:Data | undefined)=>void)=>void;
}) {
  const [receipt,setReceipt]=useState<Data | null>(null),[notice,setNotice]=useState('');
  const generation=useRef(0), timer=useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const hide=() => { ++generation.current; clearTimeout(timer.current); setReceipt(null); setNotice(''); };
  useEffect(() => {
    const hidden=() => { if(document.visibilityState !== 'visible') hide(); };
    document.addEventListener('visibilitychange',hidden);
    return () => { ++generation.current; clearTimeout(timer.current); document.removeEventListener('visibilitychange',hidden); };
  },[]);
  const open=() => {
    hide(); const ticket=generation.current;
    read(row => {
      if(ticket !== generation.current || document.visibilityState !== 'visible') return;
      if(!row || row.localOnly !== true || row.readerDeviceId!==here.deviceId || row.readerInstanceId!==here.instanceId ||
        row.targetDeviceId!==peer.deviceId || row.targetInstanceId!==peer.instanceId || row.targetFingerprint!==peer.certificateFingerprint) {
        setNotice('本机没能核对到这两台原设备的回执。'); return;
      }
      const now=Date.now();
      if(row.state!=='original_checked' || !Number.isSafeInteger(row.viewUntil) || !Number.isSafeInteger(row.expiresAt) ||
        !Number.isSafeInteger(row.receivedAt) || row.receivedAt>now || row.viewUntil<=now || row.viewUntil>row.expiresAt) {
        setNotice(unavailable[row.state] || '本次查阅已过期，未显示授权范围。'); return;
      }
      setReceipt(row);
      timer.current=setTimeout(() => { hide(); setNotice('这次查阅已结束。'); },Math.min(30_000,row.viewUntil-now));
    });
  };
  const methods=receipt && [receipt.submitGoal && '提交目标',receipt.invokeSelectedTools && '调用选定工具',receipt.originalTaskRead && '读取原任务'].filter(Boolean);
  return <div><Button variant="ghost" disabled={busy} onClick={receipt?hide:open}>{receipt?'收起本机回执':'核对本机保存的授权回执'}</Button>
    {notice && <p role="status">{notice}</p>}
    {receipt && <div className="pw-source-copy" role="status"><p>{receipt.identityOnly ? '上次对方只签给本机设备身份和这一份回执，没有工作权限。' :
      `上次对方签过的范围：${methods?.length ? methods.join('、') : '其他受限方法'}（列明 ${receipt.selectedToolCount} 个工具）。`}</p>
      <p>本机收于 {when(receipt.receivedAt)} · 对方签发的第 {receipt.issuerRevision} 次授权，原期限至 {when(receipt.expiresAt)}。</p>
      <p>此次没有联系对方，不能证明对方在线、未撤销或现在能执行。</p></div>}
  </div>;
}
