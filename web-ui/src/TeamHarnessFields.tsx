import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, TextField } from './forms-library';
import { HarnessEvidence } from './HarnessEvidence';
import { harnessOptions, harnessProbeReady, harnessReason, harnessWorkerLabel, isConfiguredHarness, isTeamHarness } from './harness-capabilities';

type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};

/** A member stores a computer/environment/worker binding, never a probe token or execution authority. */
export function TeamHarnessFields({ member, profiles, run, disabled, onChange, onReady }: {
  member: Data; profiles: Data[]; run: (action: string, args?: Data) => Promise<any>; disabled: boolean;
  onChange: (changes: Data) => void; onReady: (ready: boolean) => void;
}) {
  const profile = profiles.find(value => value.id === member.device?.profileId);
  const [directory, setDirectory] = useState<Data | null>(null), [probe, setProbe] = useState<Data | null>(null), [options, setOptions] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), generation = useRef(0);
  const environments = directory?.profileId === profile?.id && directory?.profileBinding === profile?.bindingHash ? rows(directory?.environments) : [];
  const environment = environments.find(value => value.id === member.device?.environmentId);
  const workers = rows(environment?.workers).filter(value => isTeamHarness(value.id)), worker = workers.find(value => value.id === member.runner);
  const bindingKey = JSON.stringify([profile?.id, profile?.bindingHash, environment?.id, environment?.bindingHash, member.runner]);
  const requestKey = JSON.stringify([bindingKey, member.model || '', member.modeId || '']);
  const currentKey = useRef(requestKey); currentKey.current = requestKey;
  const currentProbe = probe?.requestKey === requestKey ? probe.value as Data : null;
  const ready = !!worker && worker.available !== false && currentProbe?.available === true
    && (!isConfiguredHarness(member.runner) || harnessProbeReady(currentProbe, worker));
  const locked = disabled || busy;
  useEffect(() => { onReady(ready); }, [ready, requestKey]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; }; }, []);
  useEffect(() => { setProbe(null); setOptions(null); setError(''); }, [bindingKey]);
  useEffect(() => {
    const invalidate = () => { generation.current++; setBusy(false); setProbe(null); setDirectory(null); };
    const visibility = () => { if (document.visibilityState !== 'visible') invalidate(); };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', invalidate);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', invalidate); };
  }, []);
  const load = () => {
    if (!profile || disabled || busy || document.visibilityState !== 'visible') return;
    const ticket = ++generation.current;
    setBusy(true); setError(''); setProbe(null); setOptions(null); setDirectory(null);
    void run('remote.environments', { profileId: profile.id, _profileBinding: profile.bindingHash }).then(value => {
      if (alive.current && ticket === generation.current) setDirectory({ ...object(value), profileId: profile.id, profileBinding: profile.bindingHash });
    }).catch(failure => { if (alive.current && ticket === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (alive.current && ticket === generation.current) setBusy(false); });
  };
  useEffect(() => { setDirectory(null); load(); }, [profile?.id, profile?.bindingHash]);
  const check = () => {
    if (!profile || !environment || !worker || locked || document.visibilityState !== 'visible') return;
    const ticket = ++generation.current, originalKey = requestKey;
    setBusy(true); setError(''); setProbe(null);
    void run('remote.probeWorker', { profileId: profile.id, _profileBinding: profile.bindingHash,
      environmentId: environment.id, environmentBinding: environment.bindingHash, worker: member.runner,
      ...(member.model?.trim() ? { model: member.model.trim() } : {}), ...(member.modeId ? { modeId: member.modeId } : {}) }).then(value => {
      if (alive.current && ticket === generation.current && currentKey.current === originalKey) { setProbe({ requestKey: originalKey, value: object(value) }); setOptions(object(value)); }
    }).catch(failure => { if (alive.current && ticket === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (alive.current && ticket === generation.current) setBusy(false); });
  };
  const modelOptions = harnessOptions(options, 'models'), modes = harnessOptions(options, 'modes');
  const selectedModel = member.model || '', selectedMode = member.modeId || '';
  return <div className="pw-team-harness-fields">
    <SelectField label="在哪台电脑上工作" value={member.device?.profileId || ''} options={[{ id: '', label: '选择已连接的电脑' }, ...profiles.map(value => ({ id: value.id, label: value.name || value.id }))]}
      isDisabled={locked} onChange={profileId => onChange({ runner: 'computer', device: { profileId, environmentId: '' }, model: '', modeId: '', externalExecutionRiskAcknowledged: false })} />
    {!profiles.length && <p className="pw-field-hint">请先在“电脑环境”添加一台电脑，返回后可将其中的执行器作为成员。</p>}
    {member.device?.profileId && !profile && <p role="status">原电脑连接已不可用，请重新选择。</p>}
    {profile && <>
      <Button variant="ghost" disabled={locked} onClick={load}>{busy ? '正在核对…' : '刷新这台电脑的环境'}</Button>
      <SelectField label="工作环境与目录" value={member.device?.environmentId || ''} options={[{ id: '', label: '选择实际工作环境' }, ...environments.map(value => ({ id: value.id, label: value.label || value.id }))]}
        isDisabled={locked || !directory} onChange={environmentId => onChange({ runner: 'computer', device: { profileId: profile.id, environmentId }, model: '', modeId: '', externalExecutionRiskAcknowledged: false })} />
      {directory && !environments.length && <p role="status">这台电脑没有返回可用环境，请在电脑端核对配置。</p>}
    </>}
    {environment && <>
      <p className="pw-field-hint">工作目录：{environment.workspace || '执行端未报告'}</p>
      <SelectField label="执行器" value={member.runner === 'computer' ? '' : member.runner}
        options={[{ id: '', label: '选择这台电脑已配置的执行器' }, ...workers.map(value => ({ id: value.id, label: harnessWorkerLabel(value) + (value.available === false ? ' · 当前不可用' : '') }))]}
        isDisabled={locked} onChange={runner => onChange({ runner: runner || 'computer', model: '', modeId: '', externalExecutionRiskAcknowledged: false })} />
      {!workers.length && <p role="status">此环境没有返回可作为团队成员的执行器。</p>}
      {member.runner !== 'computer' && !worker && <p role="status">原执行器不在这次环境目录中，请重新选择或核对电脑配置。</p>}
    </>}
    {worker && <>
      <p className="pw-field-hint">{harnessWorkerLabel(worker)}负责调用模型与工具，运行在{profile?.name || '所选电脑'}上。</p>
      {worker.available === false && <p role="status">{harnessReason(worker) || '此执行器当前报告不可用；可核对具体原因。'}</p>}
      <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id={`member-options-${member.id}`} title="模型与执行模式" description={selectedModel || '使用执行器默认模型'}>
        {modelOptions.length ? <SelectField label="执行器使用的模型" value={selectedModel}
          options={[{ id: '', label: '使用执行器默认模型' }, ...(selectedModel && !modelOptions.some(value => value.id === selectedModel) ? [{ id: selectedModel, label: selectedModel + ' · 待核对' }] : []), ...modelOptions]}
          isDisabled={locked} onChange={model => onChange({ model })} />
          : <TextField label="执行器使用的模型 ID（可留空）" value={selectedModel} onChange={model => onChange({ model })} isDisabled={locked} hint="目录尚未返回；填写后须让执行器实际核对，留空沿用它的默认模型。" />}
        {(modes.length > 0 || selectedMode) && <SelectField label="执行模式" value={selectedMode}
          options={[{ id: '', label: '使用执行器默认模式' }, ...(selectedMode && !modes.some(value => value.id === selectedMode) ? [{ id: selectedMode, label: selectedMode + ' · 待核对' }] : []), ...modes]}
          isDisabled={locked} onChange={modeId => onChange({ modeId })} />}
      </AccordionItem></Accordion>
      {member.runner !== 'codex_app_server' && <CheckboxField isSelected={member.externalExecutionRiskAcknowledged === true} isDisabled={locked} onChange={externalExecutionRiskAcknowledged => onChange({ externalExecutionRiskAcknowledged })}>我了解此成员使用电脑端已配置的工具权限；工作目录不是隔离沙箱，内部模型费用不能硬限</CheckboxField>}
      <Button variant="secondary" disabled={locked} onClick={check}>核对这名成员的执行器</Button>
      {currentProbe ? <HarnessEvidence probe={currentProbe} id={`member-evidence-${member.id}`} /> : <p className="pw-field-hint">先核对当前电脑、执行器和模型；保存后，每次启动仍会重新检查实际能力。</p>}
    </>}
    {error && <p className="pw-field-error" role="alert">{error}</p>}
  </div>;
}
