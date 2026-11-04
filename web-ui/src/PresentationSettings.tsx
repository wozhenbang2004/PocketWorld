import { useState } from 'react';
import { Accordion, AccordionItem, Button, RadioOptions } from './forms-library';

type Data = Record<string, unknown>;
type Props = { state: Data; supported: (action: string) => boolean; run: (action: string, args?: Record<string, unknown>) => Promise<unknown> };
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};

/** Existing BoardUI radio field, backed by the original native appearance preference. */
export function AppearanceSettings({ state, supported, run }: Props) {
  const appearance = object(state.appearance);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const mode = ['system', 'light', 'dark'].includes(String(appearance.mode)) ? String(appearance.mode) : '';
  return <>
    <RadioOptions label="界面外观" value={mode} options={[
      { id: 'system', label: '跟随系统' }, { id: 'light', label: '浅色' }, { id: 'dark', label: '深色' },
    ]} isDisabled={pending || appearance.saving === true || !mode || !supported('settings.appearance')}
      onChange={value => {
        if (value === mode || pending) return;
        setPending(true); setError('');
        void run('settings.appearance', { mode: value })
          .catch(failure => setError(failure instanceof Error ? failure.message : '外观保存未确认，请重试'))
          .finally(() => setPending(false));
      }} />
    {(error || typeof appearance.error === 'string') && <p className="pw-error" role="alert">{error || String(appearance.error)}</p>}
  </>;
}

export function systemSpeechSummary(speech: Data): string {
  switch (speech.ttsStatus) {
    case 'ready': return '系统中文朗读已就绪';
    case 'initializing': return '正在准备系统中文朗读';
    case 'language_data_missing': return '系统朗读缺少中文语音资源，请在系统设置中安装';
    case 'language_unsupported': return '当前系统引擎不支持中文朗读';
    case 'unavailable': return '系统朗读引擎暂不可用，可重试准备';
    case 'closed': return '原朗读窗口已关闭';
    default: return '尚未准备系统中文朗读';
  }
}

/** Capability metadata is not an offline guarantee or a new speech provider. */
export function SystemSpeechSettings({ state, supported, run }: Props) {
  const speech = object(state.systemSpeech);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const perform = async (action: string) => {
    setPending(true); setMessage(''); setError('');
    try {
      const result = object(await run(action));
      if (result.opened === 'system_settings') setMessage('已打开系统设置，请查找“文字转语音”或“语言与输入”。');
    } catch (failure) { setError(failure instanceof Error ? failure.message : '系统语音操作未确认，请重试'); }
    finally { setPending(false); }
  };
  const network = speech.ttsVoiceNetworkUse === 'requires_network'
    ? '当前系统声音声明需要网络。'
    : speech.ttsVoiceNetworkUse === 'declares_no_network_requirement'
      ? '当前系统声音声明无需网络，实际行为由系统引擎决定。'
      : '当前声音的联网属性尚未确认。';
  const needsData = speech.ttsStatus === 'language_data_missing' || speech.ttsStatus === 'language_unsupported';
  return <>
    <p className="pw-field-hint" role="status">{systemSpeechSummary(speech)}。{network}</p>
    {typeof speech.ttsEnginePackage === 'string' && speech.ttsEnginePackage && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="speech-engine" title="语音引擎"><p className="pw-field-hint">{speech.ttsEnginePackage}{typeof speech.ttsVoiceName === 'string' && speech.ttsVoiceName ? ` · ${speech.ttsVoiceName}` : ''}</p></AccordionItem></Accordion>}
    <div className="pw-form-actions">
      <Button variant="secondary" disabled={pending || speech.ttsStatus === 'initializing' || needsData || !supported('speech.prepare')} onClick={() => { void perform('speech.prepare'); }}>准备中文朗读</Button>
      <Button variant="secondary" disabled={pending || !supported('speech.openSettings')} onClick={() => { void perform('speech.openSettings'); }}>系统语音设置</Button>
    </div>
    {needsData && <p className="pw-field-hint">在系统中选择支持中文的引擎或安装中文资源后，重新打开应用再准备朗读。</p>}
    {message && <p className="pw-field-hint" role="status">{message}</p>}
    {error && <p className="pw-error" role="alert">{error}</p>}
  </>;
}
