import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';

type Data = Record<string, any>;
export type PreparedPhone = { deviceId: string; instanceId: string; certificateFingerprint: string; label: string; kind?: 'windows-workspace' };
type PhoneIdentity = Omit<PreparedPhone, 'label' | 'kind'>;
type PreparedRequest = { directPhone?: PhoneIdentity; directWorkspace?: PhoneIdentity; requestId: string; goal: string; deadlineAt: number; toolLimit: number; modelCallLimit: number };

/** Preparation is local only. The original task must separately select and confirm its remote scope. */
export function DeviceTaskPreparation({ open, run, onClose, onPendingChange, phone }: { phone?: PreparedPhone; open: boolean; run: (action: string, args?: Data) => Promise<any>; onClose: () => void; onPendingChange?: (pending: boolean) => void }) {
  const [goal, setGoal] = useState(''), [tools, setTools] = useState('40'), [models, setModels] = useState('48'), [minutes, setMinutes] = useState('30');
  const [pending, setPending] = useState(false), [unknown, setUnknown] = useState(false), [error, setError] = useState('');
  const request = useRef<PreparedRequest | null>(null), working = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const workspace = phone?.kind === 'windows-workspace';
  const phoneKey = phone ? JSON.stringify([workspace, phone.deviceId, phone.instanceId, phone.certificateFingerprint]) : 'generic';
  const editorKey = useRef(phoneKey), unresolved = useRef(new Map<string, PreparedRequest>());
  useEffect(() => {
    if (!open || editorKey.current === phoneKey || working.current) return;
    editorKey.current = phoneKey;
    const saved = unresolved.current.get(phoneKey); request.current = saved || null;
    setGoal(saved?.goal || ''); setTools(String(saved?.toolLimit ?? 40)); setModels(String(saved?.modelCallLimit ?? 48)); setMinutes('30'); setUnknown(!!saved); setError('');
  }, [open, phoneKey]);
  const prepare = async () => {
    if (working.current || !open || editorKey.current !== phoneKey || document.visibilityState !== 'visible') return;
    try {
      if (!request.current) {
        if (!goal.trim() || goal.trim().length > 8000) throw new Error('请填写 1–8000 字的设备工作目标。');
        const integer = (value: string, max: number) => { const n = Number(value); if (!/^\d+$/.test(value) || !Number.isSafeInteger(n) || n < 1 || n > max) throw new Error('请填写范围内的整数额度。'); return n; };
        if (unresolved.current.size >= 8) throw new Error('还有原准备回执未核对，请先回到对应设备打开原编号。');
        const target = phone ? { deviceId: phone.deviceId, instanceId: phone.instanceId, certificateFingerprint: phone.certificateFingerprint } : undefined;
        request.current = { ...(target ? workspace ? { directWorkspace: target } : { directPhone: target } : {}), requestId: crypto.randomUUID(), goal: goal.trim(), deadlineAt: Date.now() + Number(minutes) * 60_000, toolLimit: integer(tools, 400), modelCallLimit: integer(models, 512) };
        unresolved.current.set(phoneKey, request.current);
      }
      const original = request.current;
      working.current = true; setPending(true); onPendingChange?.(true); setError('');
      const value = await run('deviceTaskScope.prepare', original);
      if (!mounted.current) return;
      if (value?.taskId !== original.requestId) throw new Error('尚未取得原准备任务的打开回执。');
      unresolved.current.delete(phoneKey); request.current = null; setUnknown(false); setGoal(''); onClose();
    } catch (failure) {
      if (mounted.current) { setError(failure instanceof Error ? failure.message : String(failure)); setUnknown(!!request.current); }
    } finally { working.current = false; if (mounted.current) { setPending(false); onPendingChange?.(false); } }
  };
  return <SettingsDialog isOpen={open} onClose={() => { if (!pending) onClose(); }} title="让设备完成一件事">
    <div className="pw-collaboration-dialog">
      <p>{phone ? <>只给 <strong>{phone.label}</strong> 准备这份工作。先存本机，不联系它；在原任务中单独读取并核对可用范围，不会改用其他设备。</> : '先写目标，再为这份原任务选择手机、电脑窗口或工作区。确认范围后才开始，不必先发送普通聊天。'}</p>
      <TextAreaField label="要完成什么" value={goal} onChange={setGoal} isDisabled={pending || unknown} placeholder={workspace ? '例如：整理这个已授权工作区中的项目结构' : '例如：在另一台手机的已授权应用中找到订单，整理状态'} />
      <p>{workspace ? '仅可列目录、读你核准的文件；启动后这些读取结果会交给本机选定的模型。不改文件、不运行命令、不看屏幕。' : phone ? '接下来可以核对它允许本机助手使用哪些工具，或它是否能用自己的模型接手。' : '由本机当前所选模型协调，只使用你选择的远端设备工具，不挂本机通用工具或预读私人记忆。纯电脑操作不需要安装 Codex 或 Claude。'}</p>
      <Accordion><AccordionItem id="budget" title="本次额度与期限" description={`工具 ${tools} 次 · 模型 ${models} 次 · ${minutes} 分钟`}>
        <TextField label="工具调用总上限" value={tools} onChange={setTools} inputMode="numeric" isDisabled={pending || unknown} hint="1–400，含本机发起调用及分给对端的额度，不会自动补满。" />
        <TextField label="模型调用总上限" value={models} onChange={setModels} inputMode="numeric" isDisabled={pending || unknown} hint="1–512，包含原任务模型与工具审批推理。" />
        <SelectField label="从本次准备起截止" value={minutes} onChange={setMinutes} isDisabled={pending || unknown} options={[{ id: '15', label: '15 分钟' }, { id: '30', label: '30 分钟' }, { id: '60', label: '1 小时' }, { id: '120', label: '2 小时' }]} />
      </AccordionItem></Accordion>
      {error && <p role="alert">{error}</p>}
      {unknown && <p role="status">原任务可能已经保存。下方只核对同一编号与原期限，不会创建第二份任务、补满预算或启动设备。原期限：{request.current && new Date(request.current.deadlineAt).toLocaleString()}。编号：{request.current?.requestId}</p>}
      <div className="pw-form-actions"><Button variant="ghost" disabled={pending} onClick={onClose}>返回设备</Button><Button disabled={pending || !goal.trim()} onClick={() => void prepare()}>{pending ? '正在准备原任务…' : unknown ? '核对原准备并打开' : workspace ? '保存这台电脑的只读目标' : phone ? '保存给这部手机的目标' : '准备目标并选择设备'}</Button></div>
    </div>
  </SettingsDialog>;
}
