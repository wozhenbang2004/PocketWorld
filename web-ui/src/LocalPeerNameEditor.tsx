import { useState } from 'react';
import { Button, TextField } from './forms-library';

export function LocalPeerNameEditor({current,retired,busy,save}:{
  current:string;retired:boolean;busy:boolean;save:(expected:string,next:string,done:()=>void)=>void;
}) {
  const [draft,setDraft]=useState<string|null>(null),[base,setBase]=useState(''),[error,setError]=useState('');
  const clear=() => { setDraft(null); setBase(''); setError(''); };
  if(draft===null)return <Button variant="ghost" disabled={busy} onClick={() => {setBase(current);setDraft(current);setError('');}}>{retired?'清除原设备备注':current?'修改本机备注':'给设备加本机备注'}</Button>;
  const submit=(value:string) => {
    const normalized=value.normalize('NFC').trim();
    if([...normalized].length>32 || /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u.test(normalized)) {setError('请使用最多32个可见字符，不含换行或方向标记。');return;}
    save(base,normalized,clear);
  };
  return <div>{!retired && <TextField label="本机备注" value={draft} onChange={setDraft} isDisabled={busy} placeholder="如：备用手机" />}
    <p>{retired?'只清除本机对原身份的备注，不删除配对历史。':'只有这台设备会显示此备注。身份仍按原设备编号和证书核对。'}</p>
    {error && <p role="alert">{error}</p>}
    <div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={clear}>取消</Button>
      {!!base && <Button variant="ghost" disabled={busy} onClick={() => submit('')}>清除备注</Button>}
      {!retired && <Button disabled={busy || !draft.trim() || draft.normalize('NFC').trim()===base} onClick={()=>submit(draft)}>保存到本机</Button>}
    </div></div>;
}
