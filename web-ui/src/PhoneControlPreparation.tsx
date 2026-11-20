import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SelectField, SettingsDialog } from './forms-library';

import type { PreparedPhone } from './DeviceTaskPreparation';
import { deviceChoiceCode } from './device-task-scope';
import './device-control-reading.css';

type Data = Record<string, any>;
/** Same preparation/recovery interaction as DeviceTaskPreparation, without a model goal. */
export function PhoneControlPreparation({ open, run, onClose, onPendingChange, phone }: { phone?:PreparedPhone; open: boolean; run: (action: string, args?: Data) => Promise<any>; onClose: () => void; onPendingChange: (value: boolean) => void }) {
  const [minutes, setMinutes] = useState('5'), [tools, setTools] = useState('80');
  const [busy, setBusy] = useState(false), [unknown, setUnknown] = useState(false), [error, setError] = useState('');
  const original = useRef<Data | null>(null), working = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const targetKey = phone ? JSON.stringify([phone.deviceId,phone.instanceId,phone.certificateFingerprint]) : 'generic';
  const editorKey=useRef(targetKey), unresolved=useRef(new Map<string,{request:Data;minutes:string}>());
  useEffect(()=>{
    if(!open || editorKey.current===targetKey || working.current)return;
    editorKey.current=targetKey;const saved=unresolved.current.get(targetKey);
    original.current=saved?.request || null;setUnknown(!!saved);setError('');setTools(String(saved?.request.toolLimit ?? 80));setMinutes(saved?.minutes ?? '5');
  },[open,targetKey]);
  const prepare = async () => {
    if (working.current || !open || editorKey.current!==targetKey || document.visibilityState !== 'visible') return;
    if (!original.current) {
      if(unresolved.current.size>=8){setError('请先核对已有准备的原编号。');return;}
      const target=phone ? { deviceId:phone.deviceId,instanceId:phone.instanceId,certificateFingerprint:phone.certificateFingerprint } : undefined;
      original.current={ ...(target ? {manualPhoneTarget:target} : {}),requestId:crypto.randomUUID(),deadlineAt:Date.now()+Number(minutes)*60_000,toolLimit:Number(tools) };
      unresolved.current.set(targetKey,{request:original.current,minutes});
    }
    working.current = true; setBusy(true); onPendingChange(true); setError('');
    const request = original.current;
    try {
      const value = await run('phoneControl.prepare', request);
      if (!mounted.current) return;
      const target=request.manualPhoneTarget;
      if (value?.taskId !== request.requestId || value.prepared!==true || value.taskStarted!==false ||
          (target ? !['deviceId','instanceId','certificateFingerprint'].every(k=>value.manualPhoneTarget?.[k]===target[k]) : !!value.manualPhoneTarget))
        throw new Error('原手控准备的编号或手机尚未匹配。');
      unresolved.current.delete(targetKey);original.current = null; setUnknown(false); onClose();
    } catch (failure) { if (mounted.current) { setUnknown(true); setError(failure instanceof Error ? failure.message : String(failure)); } }
    finally { working.current = false; if (mounted.current) { setBusy(false); onPendingChange(false); } }
  };
  return <SettingsDialog isOpen={open} onClose={() => { if (!busy) onClose(); }} title="手动控制手机"><div className="pw-collaboration-dialog pw-phone-preparation">
    <p>{phone ? <><bdi>{phone.label}</bdi> · {deviceChoiceCode(phone.deviceId)}。只为这部原手机准备；在原任务中由你核对并选择它已授权的屏幕，不会改用其他设备。</> : '先在本机保存准备。然后从原任务核对授权，选一台手机和一个屏幕。'}准备不会联系对端、读取画面或发送输入。</p>
    <div className="pw-phone-preparation-options"><SelectField label="有效时长（首次准备起）" value={minutes} onChange={setMinutes} isDisabled={busy || unknown} options={[{ id: '1', label: '1 分钟' }, { id: '5', label: '5 分钟' }, { id: '10', label: '10 分钟' }]} />
    <SelectField label="总调用额度" value={tools} onChange={setTools} isDisabled={busy || unknown} options={[{ id: '20', label: '20 次' }, { id: '80', label: '80 次' }, { id: '200', label: '200 次' }]} /></div>
    <p className="pw-field-hint">刷新和输入均计入总额度，包含本机与对端调用。</p>
    <Accordion><AccordionItem id="phone-preparation-limits" title="额度与离开页面后的行为"><p>总额度同时包含本机发起与分给对端的调用；每次刷新和输入都计入，不自动补满。下一页再选择对端份额。离页、切后台、停止或到期会阻止后续输入，但不能撤回已发送的动作。</p></AccordionItem></Accordion>
    {error && <p role="alert">{error}</p>}{unknown && <p role="status">原任务可能已保存。只核对同一个编号与原期限，不再创建：{original.current?.requestId}</p>}
    <div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={onClose}>返回设备</Button><Button disabled={busy} onClick={() => void prepare()}>{busy ? '正在准备…' : unknown ? '核对原准备并打开' : phone ? '准备这部手机的手控范围' : '保存手控准备到本机'}</Button></div>
  </div></SettingsDialog>;
}
