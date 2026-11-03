import { useEffect, useRef, useState } from 'react';
import { Button, SettingsDialog, TextAreaField } from './forms-library';

type Data = Record<string, any>;
export function QuickPanelVoiceDraft({ handoff, input, run }: { handoff: Data; input: Data; run: (action: string, args?: Data) => Promise<any> }) {
  const [text, setText] = useState(''), [error, setError] = useState(''), [pending, setPending] = useState(false), entered = useRef(false);
  const same = input.target?.conversationId === 'quick-panel:' + handoff.handoffId;
  const final = same && input.reviewAvailable === true;
  const busy = pending || handoff.saving === true;
  useEffect(() => { setText(final ? input.finalText || '' : ''); }, [input.inputId, final]);
  const action = async (name: string, args: Data = {}) => {
    if (entered.current) return; entered.current = true; setPending(true); setError('');
    try { await run(name, args); } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; setPending(false); }
  };
  const close = () => { if (!busy) void action('quickVoice.close'); };
  return <SettingsDialog isOpen title="原浮层语音补充" onClose={close}>
    <p>最终文字核对后只追加到原任务的浮层草稿，再由你发送。</p>
    {handoff.note && <details><summary>交接时保留的原说明</summary><p style={{ whiteSpace: 'pre-wrap' }}>{handoff.note}</p></details>}
    {handoff.message && <p>{handoff.message}</p>}
    {handoff.receipt?.saved ? <p>{handoff.receipt.requiresEditingBeforeSend ? '已追加到原草稿；合计超过1500字，发送前请回浮层编辑。' : '已追加到原浮层草稿，尚未发送或继续任务。'}</p> : <>
      {!handoff.closed && <p className="pw-field-hint">{same && input.message || '使用设备系统听写，可能联网；麦克风权限和识别结果以系统回执为准。'}</p>}
      {final && <TextAreaField label="本次最终文字，可修改" value={text} onChange={value => { if (!busy && value.length <= 4000) setText(value); }} />}
      <div className="pw-form-actions">
        {!final && !handoff.closed && !handoff.saveAttempted && <Button disabled={busy || input.resourceReleaseUnknown} onClick={() => { void action(same && input.active ? 'voice.stop' : 'quickVoice.start', same && input.active ? {} : { handoffId: handoff.handoffId }); }}>{same && input.active ? '结束本次听写' : '开始系统听写'}</Button>}
        {final && <Button disabled={busy || handoff.closed || handoff.saveAttempted || !text.trim() || input.resourceReleaseUnknown} onClick={() => { void action('quickVoice.save', { handoffId: handoff.handoffId, inputId: input.inputId, text }); }}>追加到原浮层草稿</Button>}
      </div>
    </>}
    {error && <p role="alert">{error}</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={close}>{handoff.receipt?.saved ? '关闭' : '关闭听写，保留原说明'}</Button></div>
  </SettingsDialog>;
}
