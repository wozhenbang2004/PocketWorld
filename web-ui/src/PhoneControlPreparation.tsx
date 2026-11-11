import { useEffect, useRef, useState } from 'react';
import { Button, SelectField, SettingsDialog } from './forms-library';

type Data = Record<string, any>;
/** Same preparation/recovery interaction as DeviceTaskPreparation, without a model goal. */
export function PhoneControlPreparation({ open, run, onClose, onPendingChange }: { open: boolean; run: (action: string, args?: Data) => Promise<any>; onClose: () => void; onPendingChange: (value: boolean) => void }) {
  const [minutes, setMinutes] = useState('5'), [tools, setTools] = useState('80');
  const [busy, setBusy] = useState(false), [unknown, setUnknown] = useState(false), [error, setError] = useState('');
  const original = useRef<Data | null>(null), working = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const prepare = async () => {
    if (working.current || document.visibilityState !== 'visible') return;
    if (!original.current) original.current = { requestId: crypto.randomUUID(), deadlineAt: Date.now() + Number(minutes) * 60_000, toolLimit: Number(tools) };
    working.current = true; setBusy(true); onPendingChange(true); setError('');
    const request = original.current;
    try {
      const value = await run('phoneControl.prepare', request);
      if (!mounted.current) return;
      if (value?.taskId !== request.requestId) throw new Error('原手控准备的打开回执尚未匹配。');
      original.current = null; setUnknown(false); onClose();
    } catch (failure) { if (mounted.current) { setUnknown(true); setError(failure instanceof Error ? failure.message : String(failure)); } }
    finally { working.current = false; if (mounted.current) { setBusy(false); onPendingChange(false); } }
  };
  return <SettingsDialog isOpen={open} onClose={() => { if (!busy) onClose(); }} title="直接手动控制手机"><div className="pw-collaboration-dialog">
    <p>选择一台已获授权的手机与一个屏幕，核对后由你刷新画面、点击、滑动或输入。无需模型或电脑代理，不会让助手自行操作。</p>
    <SelectField label="从本次准备起截止" value={minutes} onChange={setMinutes} isDisabled={busy || unknown} options={[{ id: '1', label: '1 分钟' }, { id: '5', label: '5 分钟' }, { id: '10', label: '10 分钟' }]} />
    <SelectField label="本次总调用额度" value={tools} onChange={setTools} isDisabled={busy || unknown} options={[{ id: '20', label: '20 次' }, { id: '80', label: '80 次' }, { id: '200', label: '200 次' }]} />
    <p>总额度同时包含本机发起与分给对端的调用；每次刷新和输入都计入，不自动补满。下一页再选择对端份额。离页、切后台、停止或到期会阻止后续输入，但不能撤回已发送的动作。</p>
    {error && <p role="alert">{error}</p>}{unknown && <p role="status">原任务可能已保存。只核对同一个编号与原期限，不再创建：{original.current?.requestId}</p>}
    <div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={onClose}>返回设备</Button><Button disabled={busy} onClick={() => void prepare()}>{busy ? '正在准备…' : unknown ? '核对原准备并打开' : '选择手机与范围'}</Button></div>
  </div></SettingsDialog>;
}
