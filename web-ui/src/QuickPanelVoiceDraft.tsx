import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, SettingsDialog, TextAreaField } from './forms-library';
import './composer-context.css';

type Data = Record<string, any>;

/** The same native handoff progresses from capture to review to a saved receipt.
 * Disclosure and returning from the exit question never discard or re-read its draft. */
export function QuickPanelVoiceDraft({ handoff, input, run }: { handoff: Data; input: Data; run: (action: string, args?: Data) => Promise<any> }) {
  const same = input.target?.conversationId === 'quick-panel:' + handoff.handoffId;
  const final = same && input.reviewAvailable === true;
  const saved = handoff.receipt?.saved === true;
  const recording = same && input.active === true;
  const stopping = recording && input.phase === 'stopping';
  const identity = JSON.stringify([handoff.handoffId, input.inputId]);
  const documentEpoch = bridge.getDocumentEpoch();
  const current = useRef(identity), alive = useRef(true), generation = useRef(0), entered = useRef(false), saveEntered = useRef(false), awaiting = useRef(false);
  const [text, setText] = useState(final ? String(input.finalText || '') : '');
  const [error, setError] = useState(''), [pending, setPending] = useState(false), [waiting, setWaiting] = useState(false);
  const [attempted, setAttempted] = useState(false), [unknown, setUnknown] = useState(false), [invalidated, setInvalidated] = useState(false), [confirmClose, setConfirmClose] = useState(false);
  const pendingStart = useRef<Data | null>(null);
  const [startFeedback, setStartFeedback] = useState('');
  const editedInput = useRef(final ? identity : ''), owner = useRef(handoff.handoffId), lastAction = useRef(''), currentPolicy = useRef<Data>({});
  current.current = identity;
  useEffect(() => {
    if (owner.current !== handoff.handoffId) {
      owner.current = handoff.handoffId; editedInput.current = ''; saveEntered.current = false;
      setText(''); setAttempted(false); setUnknown(false); setInvalidated(false); setConfirmClose(false);
    }
    if (editedInput.current && editedInput.current !== identity) { editedInput.current = ''; setText(''); }
    pendingStart.current = null; setStartFeedback('');
    generation.current++; entered.current = false; awaiting.current = false; setPending(false); setWaiting(false); setError('');
  }, [identity]);
  useEffect(() => {
    if (final && editedInput.current !== identity) {
      editedInput.current = identity; setText(String(input.finalText || ''));
    }
  }, [identity, final]);
  useEffect(() => {
    awaiting.current = false; setWaiting(false);
  }, [input.phase, input.active, input.reviewAvailable, input.message, handoff.saving, handoff.closed, handoff.message, saved]);
  // A successful command receipt can mean only a permission request. Native reports
  // that branch (and caught start failures) in state, without changing voiceInput.
  const checkStartOutcome = () => {
    const original = pendingStart.current, snapshot = bridge.getState();
    if (!original?.accepted || !alive.current || original.request !== generation.current || original.identity !== current.current ||
        original.documentEpoch !== bridge.getDocumentEpoch() || Number(snapshot.revision) <= original.revision) return;
    const state = snapshot.state || {}, speech = state.systemSpeech || {}, actual = state.voiceInput || {};
    if (actual.active || actual.reviewAvailable || actual.inputId !== original.inputId) return;
    const failure = typeof state.error === 'string' && state.error !== original.error ? state.error : '';
    const unavailable = speech.recognitionAvailable === false, permission = speech.microphoneGranted === false;
    if (!unavailable && !permission && !failure) return;
    pendingStart.current = null; awaiting.current = false; setWaiting(false);
    setStartFeedback(unavailable ? failure || '这台设备没有可用系统语音服务，请继续文字输入。'
      : permission ? state.notice || '请在系统窗口授权；授权后再明确点击系统听写，当前没有录音。' : failure);
  };
  useEffect(() => {
    alive.current = true; let epoch = bridge.getDocumentEpoch();
    const unsubscribe = bridge.subscribe(() => {
      if (epoch === bridge.getDocumentEpoch()) { checkStartOutcome(); return; }
      epoch = bridge.getDocumentEpoch(); pendingStart.current = null; generation.current++; entered.current = false; awaiting.current = false;
      setPending(false); setWaiting(false); setInvalidated(true);
      setError('原听写窗口已变化，请回原浮层核对；没有重放录音或追加。');
    });
    return () => { alive.current = false; generation.current++; unsubscribe(); };
  }, []);
  const busy = pending || handoff.saving === true;
  const saveAttempted = attempted || handoff.saveAttempted === true;
  const resourceUnknown = input.resourceReleaseUnknown === true;
  const otherInput = !same && (input.active === true || input.reviewAvailable === true || input.leaseHeld === true);
  const locked = busy || waiting || otherInput || unknown || invalidated || resourceUnknown || handoff.closed === true || saveAttempted || saved;
  currentPolicy.current = { busy, locked, final, recording, stopping, otherInput };
  const action = async (name: string, args: Data = {}) => {
    if (!alive.current || current.current !== identity || bridge.getDocumentEpoch() !== documentEpoch || entered.current || currentPolicy.current.busy) return;
    if (name === 'quickVoice.close' && awaiting.current && lastAction.current === name) return;
    if (name !== 'quickVoice.close' && (currentPolicy.current.locked || awaiting.current)) return;
    if (name === 'quickVoice.save' && (!currentPolicy.current.final || saveEntered.current || !text.trim() || text.length > 4000)) return;
    if (name === 'voice.stop' && (!currentPolicy.current.recording || currentPolicy.current.stopping)) return;
    if (name === 'quickVoice.start' && (currentPolicy.current.recording || currentPolicy.current.final || currentPolicy.current.otherInput)) return;
    if (name === 'quickVoice.save') { saveEntered.current = true; setAttempted(true); }
    const request = ++generation.current;
    if (name === 'quickVoice.start') {
      const snapshot = bridge.getState(); setStartFeedback('');
      pendingStart.current = { request, identity, documentEpoch, inputId: input.inputId, revision: Number(snapshot.revision ?? -1), error: snapshot.state?.error, accepted: false };
    }
    const accepts = () => alive.current && generation.current === request && current.current === identity && bridge.getDocumentEpoch() === documentEpoch;
    entered.current = true; awaiting.current = true; lastAction.current = name; setPending(true); setWaiting(true); setError('');
    try {
      await run(name, args);
      if (accepts() && name === 'quickVoice.start' && pendingStart.current?.request === request) {
        pendingStart.current.accepted = true; checkStartOutcome();
      }
    }
    catch (failure) {
      if (!accepts()) return;
      if (name === 'quickVoice.start') pendingStart.current = null;
      setError(failure instanceof Error ? failure.message : String(failure));
      setUnknown((failure as Data)?.code === 'outcome_unknown'); awaiting.current = false; setWaiting(false);
    } finally {
      if (accepts()) { entered.current = false; setPending(false); }
    }
  };
  const close = () => {
    if (busy || current.current !== identity || bridge.getDocumentEpoch() !== documentEpoch) return;
    if (!saved && (recording || final || text.trim() || saveAttempted || waiting || unknown)) setConfirmClose(true);
    else void action('quickVoice.close');
  };
  const endStartWait = () => {
    if (!alive.current || current.current !== identity || bridge.getDocumentEpoch() !== documentEpoch ||
        currentPolicy.current.busy || currentPolicy.current.recording || currentPolicy.current.final || !pendingStart.current?.accepted) return;
    pendingStart.current = null; awaiting.current = false; setWaiting(false);
    setStartFeedback('已结束本次状态等待，没有再次开始录音。需要听写时，请核对系统状态后再明确点击开始。');
  };
  const needsReview = !saved && (final || !!text);
  const title = saved ? '已追加到原草稿' : saveAttempted ? (busy ? '正在追加本次文字' : '追加结果待核对')
    : resourceUnknown || unknown || invalidated ? '听写状态待核对' : handoff.closed ? '本次听写已关闭'
    : final ? '核对本次文字' : stopping ? '正在结束听写' : recording ? '正在听写' : '补充原浮层说明';
  return <SettingsDialog isOpen title="原浮层语音补充" onClose={close}>
    <div className="pw-voice-draft">
      {confirmClose ? <>
        <div className="pw-voice-stage"><h3>结束本次听写？</h3><p>{saveAttempted ? '追加结果请回原浮层核对。关闭不会重试追加。' : '关闭会舍弃尚未追加的本次听写文字。交接前的原说明仍保留，原任务不会取消。'}</p></div>
        <div className="pw-voice-draft-actions">
          <Button variant="secondary" disabled={busy} onClick={() => setConfirmClose(false)}>{final || text ? '返回核对' : '返回本次听写'}</Button>
          <Button disabled={busy} onClick={() => { void action('quickVoice.close'); }}>{saveAttempted ? '关闭并回原浮层核对' : '舍弃本次听写并关闭'}</Button>
        </div>
      </> : <>
        <div className="pw-voice-stage" role="status"><h3>{title}</h3>
          <p>{saved ? '尚未发送或继续任务。' : final ? '核对姓名、数字和目标，修改后只追加到原草稿。' : '最终文字核对后只追加到原任务的浮层草稿，再由你发送。'}</p>
        </div>
        {saved ? <div className="pw-voice-receipt">
          <p>{handoff.receipt.requiresEditingBeforeSend ? '合计超过1500字，发送前请回原浮层编辑。' : '本次文字已保存在原浮层草稿中。'}</p>
          <Accordion><AccordionItem id="voice-save-receipt" title="查看保存回执" headingLevel={4}>
            <dl className="pw-composer-context-metadata">
              <dt>原任务</dt><dd>{String(handoff.receipt.taskId || handoff.taskId || '')}</dd>
              <dt>原运行</dt><dd>{String(handoff.receipt.runId || '')}</dd>
              <dt>运行代次</dt><dd>{String(handoff.receipt.epoch ?? '')}</dd>
              <dt>草稿修订</dt><dd>{String(handoff.receipt.previousRevision ?? '')} → {String(handoff.receipt.draftRevision ?? '')}</dd>
            </dl>
          </AccordionItem></Accordion>
        </div> : <>
          {startFeedback && <p className="pw-voice-policy" role="status">{startFeedback}</p>}
          {handoff.message && <p className="pw-voice-policy">{handoff.message}</p>}
          {same && input.message && !final && <p className="pw-voice-policy">{input.message}</p>}
          {!same && otherInput && <p className="pw-error" role="alert">另一份听写尚未处理，不能从这里开始或停止它。</p>}
          {resourceUnknown && <p className="pw-error" role="alert">音频资源释放尚未确认，不能再次录音或追加。请核对系统采集状态。</p>}
          {needsReview && <TextAreaField label="本次最终文字，可修改" value={text} onChange={value => { if (!locked && value.length <= 4000) setText(value); }} isDisabled={locked} maxLength={4000} rows={4} autoResize maxRows={8} resize="none" />}
          {saveAttempted && !busy && <p className="pw-voice-policy">请回原浮层核对是否已追加；这里不会重复保存。</p>}
          {!final && !recording && !handoff.closed && !saveAttempted && !resourceUnknown && !otherInput && <p className="pw-voice-policy">使用设备系统听写，可能联网；权限和识别结果以系统回执为准。</p>}
          {waiting && !busy && !saveAttempted && <p className="pw-voice-policy" role="status">已提交本次操作，等待系统状态更新；没有重发。</p>}
          {waiting && !busy && pendingStart.current?.accepted && !recording && !final && !unknown && !invalidated && !resourceUnknown && !handoff.closed && <div className="pw-voice-stage">
            <p>系统尚未给出新的听写状态。请先核对是否仍在录音，再结束本次等待。</p>
            <Button variant="secondary" onClick={endStartWait}>已核对系统状态，结束等待</Button>
          </div>}
        </>}
        <div className="pw-voice-draft-actions">
          <Button variant="ghost" disabled={busy} onClick={close}>{saved ? '完成' : '关闭听写'}</Button>
          {!saved && !final && !handoff.closed && !saveAttempted && !otherInput && <Button disabled={locked || stopping} onClick={() => { void action(recording ? 'voice.stop' : 'quickVoice.start', recording ? {} : { handoffId: handoff.handoffId }); }}>{stopping ? '等待音频释放' : recording ? '结束本次听写' : '开始系统听写'}</Button>}
          {!saved && final && !saveAttempted && <Button disabled={locked || !text.trim()} onClick={() => { void action('quickVoice.save', { handoffId: handoff.handoffId, inputId: input.inputId, text }); }}>追加到原浮层草稿</Button>}
        </div>
      </>}
      {error && <p className="pw-error" role="alert">{error}</p>}
      {unknown && <p className="pw-voice-policy">操作结果尚未确定，不会自动重试。请核对原浮层与系统听写状态。</p>}
      {handoff.note && <Accordion className="pw-voice-note"><AccordionItem id="voice-original-note" title="交接时保留的原说明" headingLevel={4}>
        <div className="pw-voice-original-note" tabIndex={0}>{handoff.note}</div>
      </AccordionItem></Accordion>}
    </div>
  </SettingsDialog>;
}
