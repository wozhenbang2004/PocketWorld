import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog, StretchSwitch } from './forms-library';

type Props = { composer: Record<string, any>; isOpen: boolean; onClose: () => void; registerBack: (handler: (() => boolean) | null) => void };

/** A local restriction on the first send, not an invented conversation or memory read. */
export function NewConversationMemory({ composer, isOpen, onClose, registerBack }: Props) {
  const mode = composer.firstMessageMemory;
  const [writing, setWriting] = useState(false), [notice, setNotice] = useState('');
  const live = useRef(true), started = useRef(false), closedWindow = useRef(false);
  const close = useRef(onClose); close.current = onClose;
  useEffect(() => {
    live.current = true;
    const epoch = bridge.getDocumentEpoch();
    const off = bridge.subscribe(() => { if (bridge.getDocumentEpoch() !== epoch) { closedWindow.current = true; close.current(); } });
    return () => { live.current = false; off(); };
  }, []);
  useEffect(() => { if (isOpen) { closedWindow.current = false; setNotice(''); } }, [isOpen]);
  useEffect(() => { registerBack(isOpen ? () => { if (!started.current) close.current(); return true; } : null); return () => registerBack(null); }, [isOpen, registerBack]);
  const save = async (paused: boolean) => {
    if (!isOpen || !mode || mode.saving || started.current) return;
    const scope = { key: composer.key, epoch: composer.epoch, revision: composer.revision };
    const document = bridge.getDocumentEpoch(); started.current = true; setWriting(true); setNotice('');
    try {
      const value = await bridge.command('composer.firstMemory.save', { ...scope, paused });
      if (!live.current || closedWindow.current || bridge.getDocumentEpoch() !== document) return;
      if (value?.key !== scope.key || value?.epoch !== scope.epoch || !Number.isSafeInteger(value?.revision) || value.revision !== scope.revision + (mode.paused === paused ? 0 : 1) || value.persisted !== true || value.paused !== paused)
        throw new Error('没有确认这份新草稿的记忆选择已保存。请核对后再发送。');
      setNotice(paused ? '已保存。从首条消息起，这个对话暂停个人记忆。' : '已恢复此草稿的默认规则，未更改全局设置。');
    } catch (failure) { if (live.current && !closedWindow.current && bridge.getDocumentEpoch() === document) setNotice(failure instanceof Error ? failure.message : String(failure)); }
    finally { started.current = false; if (live.current) setWriting(false); }
  };
  const busy = writing || mode?.saving === true;
  return <SettingsDialog isOpen={isOpen} title="新对话的个人记忆" onClose={() => { if (!busy) onClose(); }}>
    {mode && <StretchSwitch label="从第一条消息起暂停" checked={mode.paused === true} busy={busy} disabled={busy}
      description="这份新对话不引用个人资料与记忆，也不自动整理记忆候选。其他对话不变。" onChange={paused => { void save(paused); }} />}
    <p className="pw-muted">这个选择只保存在原草稿里，不会发送消息。对话建立后，仍可在本对话的记忆设置中更改。你自己写的内容和主动附上的资料不属于这项限制。</p>
    {mode?.confirmed === false && !busy && <p className="pw-error" role="alert">本机保存尚未确认，当前草稿不能发送。可重新保存当前选择；若仍失败，重开原草稿核对。</p>}
    {notice && <p role="status">{notice}</p>}
    <div className="pw-form-actions">{mode?.confirmed === false && !busy && <Button variant="secondary" onClick={() => { void save(mode.paused === true); }}>重新保存当前选择</Button>}
      <Button variant="ghost" disabled={busy} onClick={onClose}>返回草稿</Button></div>
  </SettingsDialog>;
}
