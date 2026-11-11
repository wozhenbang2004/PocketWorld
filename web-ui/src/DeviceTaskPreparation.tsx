import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';

type Data = Record<string, any>;
type PreparedRequest = { requestId: string; goal: string; deadlineAt: number; toolLimit: number; modelCallLimit: number };

/** Preparation is local only. The original task must separately select and confirm its remote scope. */
export function DeviceTaskPreparation({ open, run, onClose, onPendingChange }: { open: boolean; run: (action: string, args?: Data) => Promise<any>; onClose: () => void; onPendingChange?: (pending: boolean) => void }) {
  const [goal, setGoal] = useState(''), [tools, setTools] = useState('40'), [models, setModels] = useState('48'), [minutes, setMinutes] = useState('30');
  const [pending, setPending] = useState(false), [unknown, setUnknown] = useState(false), [error, setError] = useState('');
  const request = useRef<PreparedRequest | null>(null), working = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const prepare = async () => {
    if (working.current || document.visibilityState !== 'visible') return;
    try {
      if (!request.current) {
        if (!goal.trim() || goal.trim().length > 8000) throw new Error('请填写 1–8000 字的设备工作目标。');
        const integer = (value: string, max: number) => { const n = Number(value); if (!/^\d+$/.test(value) || !Number.isSafeInteger(n) || n < 1 || n > max) throw new Error('请填写范围内的整数额度。'); return n; };
        request.current = { requestId: crypto.randomUUID(), goal: goal.trim(), deadlineAt: Date.now() + Number(minutes) * 60_000, toolLimit: integer(tools, 400), modelCallLimit: integer(models, 512) };
      }
      const original = request.current;
      working.current = true; setPending(true); onPendingChange?.(true); setError('');
      const value = await run('deviceTaskScope.prepare', original);
      if (!mounted.current) return;
      if (value?.taskId !== original.requestId) throw new Error('尚未取得原准备任务的打开回执。');
      request.current = null; setUnknown(false); setGoal(''); onClose();
    } catch (failure) {
      if (mounted.current) { setError(failure instanceof Error ? failure.message : String(failure)); setUnknown(!!request.current); }
    } finally { working.current = false; if (mounted.current) { setPending(false); onPendingChange?.(false); } }
  };
  return <SettingsDialog isOpen={open} onClose={() => { if (!pending) onClose(); }} title="让设备完成一件事">
    <div className="pw-collaboration-dialog">
      <p>先写目标，再为这份原任务选择手机、电脑窗口或工作区。确认范围后才开始，不必先发送普通聊天。</p>
      <TextAreaField label="要完成什么" value={goal} onChange={setGoal} isDisabled={pending || unknown} placeholder="例如：在另一台手机的已授权应用中找到订单，整理状态" />
      <p>由本机当前所选模型协调，只使用你选择的远端设备工具，不挂本机通用工具或预读私人记忆。纯电脑操作不需要安装 Codex 或 Claude。</p>
      <Accordion><AccordionItem id="budget" title="本次额度与期限" description={`工具 ${tools} 次 · 模型 ${models} 次 · ${minutes} 分钟`}>
        <TextField label="工具调用总上限" value={tools} onChange={setTools} inputMode="numeric" isDisabled={pending || unknown} hint="1–400，含本机发起调用及分给对端的额度，不会自动补满。" />
        <TextField label="模型调用总上限" value={models} onChange={setModels} inputMode="numeric" isDisabled={pending || unknown} hint="1–512，包含原任务模型与工具审批推理。" />
        <SelectField label="从本次准备起截止" value={minutes} onChange={setMinutes} isDisabled={pending || unknown} options={[{ id: '15', label: '15 分钟' }, { id: '30', label: '30 分钟' }, { id: '60', label: '1 小时' }, { id: '120', label: '2 小时' }]} />
      </AccordionItem></Accordion>
      {error && <p role="alert">{error}</p>}
      {unknown && <p role="status">原任务可能已经保存。下方只核对同一编号与原期限，不会创建第二份任务、补满预算或启动设备。编号：{request.current?.requestId}</p>}
      <div className="pw-form-actions"><Button variant="ghost" disabled={pending} onClick={onClose}>返回设备</Button><Button disabled={pending || !goal.trim()} onClick={() => void prepare()}>{pending ? '正在准备原任务…' : unknown ? '核对原准备并打开' : '准备目标并选择设备'}</Button></div>
    </div>
  </SettingsDialog>;
}
