import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, RadioOptions, SelectField, SettingsDialog, StretchSwitch, TextField } from './forms-library';
import { backgroundConnection } from './forms-library/host-contracts';
import { bridge } from './bridge';
import { audioRecoveryReceipt, audioSettingReceipt, backgroundObject as object, connectionRecoveryOutcome, diagnosticPreview, ownerActionReceipt, ownerAppAccess, ownerAppCopies, ownerWindowAvailable, recoveryOutcome } from './background-settings';

type Data = Record<string, any>;
type Props = { state: Data; supported: (action: string) => boolean; run: (action: string, args?: Data) => Promise<any>; registerBack: (handler: (() => boolean) | null) => void };
const items = (value: any): Data[] => Array.isArray(value) ? value.filter(row => row != null && typeof row === 'object' && !Array.isArray(row)) : [];
const failureText = (error: unknown) => error instanceof Error ? error.message : String(error);

/** A view change retires presentation of a result, never its original device action. */
function useOwnerAction({ run, state, supported }: Pick<Props, 'run' | 'state' | 'supported'>) {
  const [pending, setPending] = useState(''), [message, setMessage] = useState(''), [error, setError] = useState('');
  const [windowRevision, setWindowRevision] = useState(0);
  const mounted = useRef(true), active = useRef<object | null>(null), generation = useRef(0), current = useRef({ state, supported });
  current.current = { state, supported };
  const available = () => document.visibilityState === 'visible' && ownerWindowAvailable(current.current.state);
  const windowKey = () => {
    const view = object(bridge.getState().view), owner = current.current.state;
    return [bridge.getDocumentEpoch(), view.resumeRevision, view.section, view.taskId, owner.webWorkspaceResumeRevision, owner.webWorkspaceTaskOpen, owner.backgroundSetupOpen].join('|');
  };
  const observed = useRef({ key: windowKey(), visible: available() });
  const invalidate = () => {
    generation.current++;
    if (mounted.current) { setMessage(''); setError(''); setWindowRevision(value => value + 1); }
  };
  const inspect = () => {
    const next = { key: windowKey(), visible: available() };
    if (next.key !== observed.current.key || next.visible !== observed.current.visible) {
      observed.current = next; invalidate();
    }
  };
  useEffect(() => {
    mounted.current = true;
    const unsubscribe = bridge.subscribe(inspect);
    document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', invalidate);
    inspect();
    return () => { mounted.current = false; generation.current++; unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', invalidate); };
  }, []);
  useEffect(inspect, [state.backgroundSetupOpen, ownerWindowAvailable(state), state.webWorkspaceResumeRevision, state.webWorkspaceTaskOpen]);
  const act = async (action: string, args: Data = {}, receipt: (result: any) => string = ownerActionReceipt, apply?: (result: any) => void) => {
    if (active.current || !available() || !current.current.supported(action)) return;
    const request = {}, epoch = generation.current, originalWindow = windowKey();
    const isCurrent = () => mounted.current && active.current === request && generation.current === epoch && available() && windowKey() === originalWindow;
    active.current = request; setPending(action); setMessage(''); setError('');
    try {
      const result = await run(action, args);
      if (!isCurrent()) return;
      const copy = receipt(result);
      if (!isCurrent()) return;
      setMessage(copy); apply?.(result);
      return result;
    } catch (failure) { if (isCurrent()) setError(failureText(failure)); }
    finally { if (active.current === request) { active.current = null; if (mounted.current) setPending(''); } }
  };
  return { pending, act, available: available(), windowRevision, feedback: <>{message && <p className="pw-field-hint" role="status">{message}</p>}{error && <p className="pw-field-error" role="alert">{error}</p>}</> };
}

export function BackgroundAudioSettings({ state, supported, run, registerBack }: Props) {
  const display = object(object(state.features).display), owner = object(state.backgroundOwner);
  const connection = backgroundConnection(display, state.activeTaskId, Array.isArray(state.moduleBusy) ? state.moduleBusy : []);
  const [confirm, setConfirm] = useState(false);
  const { pending, act, feedback, available, windowRevision } = useOwnerAction({ run, state, supported });
  const busy = Boolean(pending || owner.audioBusy), enabled = display.audioSilenceEnabled === true;
  const canSet = available && !busy && typeof display.audioSilenceEnabled === 'boolean' && !connection.busy && (!connection.taskOwned || enabled) && supported('background.audio.set');
  const canRestore = available && !busy && !connection.taskOwned && !connection.busy && connection.helperConnected && supported('background.audio.restore');
  useEffect(() => { registerBack(confirm ? () => { setConfirm(false); return true; } : null); return () => registerBack(null); }, [confirm, registerBack]);
  useEffect(() => { setConfirm(false); }, [windowRevision]);
  useEffect(() => { if (!canRestore) setConfirm(false); }, [canRestore]);
  return <section className="pw-settings-section pw-background-audio-preference" aria-label="后台应用声音">
    <StretchSwitch label="打开应用前静音" checked={enabled} busy={busy}
      description="默认关闭。只影响目标 App 的后台声音，关闭开关停止后续静音，已有记录需单独处理。"
      disabled={!canSet}
      onChange={next => { if (canSet) void act('background.audio.set', { enabled: next }, value => audioSettingReceipt(value, next)); }} />
    <div className="pw-form-actions"><Button variant="secondary" disabled={!canRestore} onClick={() => setConfirm(true)}>处理原声音记录…</Button></div>
    {!connection.helperConnected && <p className="pw-field-hint">恢复声音前先核对原后台助手连接。</p>}
    {typeof display.audioSilenceEnabled !== 'boolean' && <p className="pw-field-hint">尚未读取到真实音频策略，请刷新后再修改。</p>}
    {busy && <p className="pw-field-hint" role="status">正在核对原音频回执…</p>}
    {feedback}
    <SettingsDialog isOpen={confirm} title="按原记录设置声音？" onClose={() => setConfirm(false)}>
      <p>将仍静音且留有记录的 App 设为任务前保存的声音模式。若其他程序同时更改，结果可能以这次设置为准。已读到不同模式的 App 会保留当前模式；未确认的记录继续保留。</p>
      <div className="pw-form-actions"><Button variant="secondary" onClick={() => setConfirm(false)}>暂不设置</Button><Button disabled={!canRestore} onClick={() => { if (!canRestore) return; setConfirm(false); void act('background.audio.restore', { confirmed: true }, audioRecoveryReceipt); }}>按记录设置声音</Button></div>
    </SettingsDialog>
  </section>;
}

export function BackgroundOwnerSettings({ state, supported, run, registerBack }: Props) {
  const display = object(object(state.features).display), setup = object(object(state.features).phone_setup), owner = object(state.backgroundOwner);
  const busyKeys = Array.isArray(state.moduleBusy) ? state.moduleBusy : [];
  const connection = backgroundConnection(display, state.activeTaskId, busyKeys), recovery = object(setup.recovery), shizuku = object(display.shizuku);
  const policy = object(display.appAccess), copies = ownerAppCopies(owner);
  const [selected, setSelected] = useState(''), [mode, setMode] = useState(connection.backend || 'wireless_adb'), [forget, setForget] = useState(false);
  const [query, setQuery] = useState('');
  const modeChosen = useRef(false);
  const { pending, act, feedback, available, windowRevision } = useOwnerAction({ run, state, supported });
  const copy = copies.find(row => row.selectionHandle === selected), access = ownerAppAccess(policy, copy);
  const shownCopies = copies.filter(row => row.selectionHandle === selected || `${row.label || ''} ${row.packageName}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const { automation, ownerShare } = access;
  const policyWritable = available && !!copy && access.revision !== null && !owner.appsLoading && !owner.policyBusy && !pending && supported('background.apps.set');
  const idle = available && !connection.taskOwned && !connection.busy && !pending;
  const canForget = connection.supported && connection.wirelessAvailable && connection.identityPresent && idle && !connection.displayBarrier && supported('background.adb.forget');
  const recoveryAvailable = available && typeof recovery.consent === 'boolean' && Number.isSafeInteger(recovery.revision) && recovery.revision >= 0;
  useEffect(() => { if (!copies.some(row => row.selectionHandle === selected)) setSelected(''); }, [owner.apps, selected]);
  useEffect(() => { if (!modeChosen.current) setMode(['wireless_adb', 'shizuku', 'adb'].includes(connection.backend) ? connection.backend : 'wireless_adb'); }, [connection.backend]);
  useEffect(() => { registerBack(forget ? () => { setForget(false); return true; } : null); return () => registerBack(null); }, [forget, registerBack]);
  useEffect(() => { setForget(false); setSelected(''); setQuery(''); }, [windowRevision]);
  useEffect(() => { if (!canForget) setForget(false); }, [canForget]);
  const button = (action: string, label: string, disabled = false, args: Data = {}) => <Button variant="secondary" disabled={!available || Boolean(pending) || disabled || !supported(action)} onClick={() => { if (!disabled) void act(action, args); }}>{label}</Button>;
  const setAccess = (nextAutomation: boolean, nextShare: boolean) => { if (copy && policyWritable) void act('background.apps.set', { selectionHandle: copy.selectionHandle, automation: nextAutomation, ownerShare: nextShare, expectedRevision: access.revision }); };
  return <section className="pw-settings-section pw-background-owner-preferences">
    <Accordion variant="divided">
      <AccordionItem id="background-app-policy" title="应用分享限制与可选粘贴">
        <p className="pw-field-hint">按系统可见的应用副本选择。读取、截图、动作和分享均会重新核对当前规则。</p>
        <div className="pw-form-actions">{button('background.apps.read', owner.appsLoading ? '正在读取应用…' : '读取应用副本', owner.appsLoading || owner.policyBusy)}</div>
        {owner.appsComplete === false && <p className="pw-field-hint">部分资料已锁定或安装身份不可读；最多显示 200 个可核对副本。</p>}
        {copies.length > 0 && <><TextField label="搜索应用名称或包名" value={query} onChange={value => setQuery(value.slice(0, 160))} isDisabled={!available || !!pending || owner.appsLoading || owner.policyBusy} />
          <SelectField label="应用副本" value={selected} onChange={setSelected} isDisabled={!available || !!pending || owner.appsLoading || owner.policyBusy || shownCopies.length === 0} hint={shownCopies.length === 0 ? '没有匹配的应用副本。' : undefined} options={shownCopies.map(row => ({ id: row.selectionHandle, label: `${row.label || row.packageName} · ${row.packageName}`, description: `${row.currentProfile ? '当前资料' : '关联资料或分身'} · 资料序号 ${row.profileSerial} · 用户 ${row.userId}` }))} /></>}
        {!owner.appsLoading && copies.length === 0 && <p className="pw-field-hint">尚无可核对的应用副本。</p>}
        {copy && <><p className="pw-field-hint">{copy.packageName} · 版本 {copy.versionCode} · 用户 {copy.userId} · UID {copy.uid}</p>
          <StretchSwitch label="允许自动读取和操作" checked={automation} disabled={!policyWritable} busy={owner.policyBusy === true || pending === 'background.apps.set'} onChange={next => setAccess(next, ownerShare)} />
          <StretchSwitch label="允许主人屏幕分享" checked={ownerShare} disabled={!policyWritable} busy={owner.policyBusy === true || pending === 'background.apps.set'} onChange={next => setAccess(automation, next)} />
          <p className="pw-field-hint">分享许可与自动操作相互独立。保存前复核资料、UID、版本和安装签名。</p></>}
        {owner.policyMessage && <p role="status" className="pw-field-hint">{owner.policyMessage}</p>}
        {access.revision === null && <p className="pw-field-hint">应用规则尚不可读，修改暂不可用。</p>}
      </AccordionItem>
      <AccordionItem id="background-clipboard" title="兼容粘贴与原记录" description="可选剪贴板输入、原记录核对与复原">
        <StretchSwitch label="允许可恢复的剪贴板粘贴" checked={display.clipboardPasteAllowed === true}
          description="默认关闭，优先使用界面树输入。兼容粘贴会临时占用系统剪贴板并尝试复原；你后来复制的内容会保留。"
          disabled={!available || typeof display.clipboardPasteAllowed !== 'boolean' || !!pending || busyKeys.includes('display.clipboard.consent') || !supported('background.clipboard.consent')}
          onChange={enabled => { void act('background.clipboard.consent', { enabled }); }} />
        <div className="pw-form-actions">{button('background.clipboard.restore', '核对并复原上次临时剪贴板', !idle || busyKeys.includes('display.clipboard.restore'))}</div>
        {object(state.moduleResults)['display.clipboard.restore'] && <p className="pw-field-hint" role="status">{object(object(state.moduleResults)['display.clipboard.restore']).clipboardRestored === true ? '原剪贴板记录已处理；后来复制的新内容会保留，本次没有重新粘贴。' : '尚未确认复原，请保持应用在前台后核对原记录。'}</p>}
      </AccordionItem>
      <AccordionItem id="background-recovery" title="断连与重启后的恢复">
        <p className="pw-field-hint">只核对已选连接。界面读取断连时保留系统登记，由你在系统设置中重新启用。</p>
        <StretchSwitch label="允许恢复原连接" checked={recovery.consent === true} disabled={!recoveryAvailable || !!pending || busyKeys.includes('phone_setup.recovery.consent') || !supported('background.recovery.consent')} onChange={enabled => { if (recoveryAvailable) void act('background.recovery.consent', { enabled, expectedRevision: recovery.revision }); }} />
        <p className="pw-field-hint" role="status">{recoveryOutcome(recovery.lastOutcome)}</p>
        {recovery.connectionOutcome && recovery.connectionOutcome !== 'not_requested' && <p className="pw-field-hint">{connectionRecoveryOutcome(recovery.connectionOutcome)}</p>}
        {recovery.unlockSignalsAvailable === false && <p className="pw-field-hint">系统未提供当前解锁监听，请在此手动核对。</p>}
        <div className="pw-form-actions">{button('background.recovery.restore', '现在核对原连接', !recoveryAvailable || recovery.consent !== true || recovery.busy)}{recovery.compensationPending === true && button('background.recovery.compensate', '核对旧恢复记录', !recoveryAvailable || recovery.busy)}{recovery.needsOwnerAction === true && button('settings.openSystem', '打开系统无障碍设置', recovery.busy, { section: 'accessibility' })}</div>
      </AccordionItem>
      <AccordionItem id="background-advanced" title="高级兼容与原连接状态">
        <p className="pw-field-hint">保留已选方式。使用已有 Shizuku 或受信任电脑时，从这里核对；切换前先释放原连接。</p>
        <RadioOptions label="连接方式" value={mode} isDisabled={!available || !connection.canChangeBackend || !!pending} onChange={value => { modeChosen.current = true; setMode(value); }} options={[{ id: 'wireless_adb', label: '内置手机引导' }, { id: 'shizuku', label: '已有 Shizuku' }, { id: 'adb', label: '受信任电脑 ADB' }]} />
        {mode === 'wireless_adb' && <p className="pw-field-hint">在上方无线调试引导中配对并连接；此选择不会自动创建连接。</p>}
        {mode === 'shizuku' && <><p className="pw-field-hint">{shizuku.message || '仅适用于已有的 Shizuku 服务。'}</p><div className="pw-form-actions">{shizuku.permissionGranted !== true ? button('background.shizuku.permission', '请求 Shizuku 授权', !idle || !connection.supported || !shizuku.binderAvailable || shizuku.permissionRequestPending) : button('background.shizuku.start', '启动后台助手', !idle || !connection.supported || connection.displayBarrier || shizuku.userServiceBound)}{shizuku.userServiceBound === true && button('background.shizuku.stop', '停止 Shizuku 助手', !idle || !connection.supported)}</div></>}
        {mode === 'adb' && <><p className="pw-field-hint">需要受信任电脑、debug 安装包和手机上的 ADB 授权。请只开启 USB 调试并授权这台电脑；不要改变 OEM 解锁、引导加载程序或清除数据选项。</p><div className="pw-form-actions">{button('background.adb.setup', '准备电脑命令', !connection.canChangeBackend)}{button('background.adb.check', '核对助手连接', !idle || connection.backend !== 'adb')}</div>{Array.isArray(owner.setupCommands) && owner.setupCommands.filter((command: unknown) => typeof command === 'string').map((command: string, index: number) => <pre className="pw-reading-copy" style={{ overflowX: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }} key={index}>{command}</pre>)}</>}
        <p className="pw-field-hint">当前方式：{({ wireless_adb: '手机内无线连接', shizuku: 'Shizuku', adb: '电脑 ADB' } as Data)[connection.backend] || '尚未读取'} · {connection.helperConnected ? '助手已连接' : '助手未连接'} · {connection.screenEstablished ? '独立屏已核验' : '独立屏尚未核验'}</p>
        <div className="pw-form-actions">{(display.sessionActive || display.releasePending) && button('background.release', '释放原独立屏', !idle)}{connection.identityPresent && <Button variant="secondary" disabled={!canForget || !supported('background.adb.forget')} onClick={() => setForget(true)}>遗忘本机无线配对身份…</Button>}</div>
      </AccordionItem>
    </Accordion>
    {feedback}
    <SettingsDialog isOpen={forget} title="遗忘本机无线配对身份？" onClose={() => setForget(false)}><p>先等待本应用启动进程实际退出，再删除本机身份。已发生的配对不会撤回；系统无线调试中的已配对设备仍需手动遗忘。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setForget(false)}>保留</Button><Button disabled={!canForget} onClick={() => { if (!canForget) return; setForget(false); void act('background.adb.forget', { confirmed: true }); }}>确认遗忘本机身份</Button></div></SettingsDialog>
  </section>;
}

export function BackgroundChecks({ state, supported, run, registerBack }: Props) {
  const display = object(object(state.features).display), owner = object(state.backgroundOwner), evidence = object(owner.practiceEvidence);
  const connection = backgroundConnection(display, state.activeTaskId, Array.isArray(state.moduleBusy) ? state.moduleBusy : []);
  const [report, setReport] = useState<ReturnType<typeof diagnosticPreview> | null>(null);
  const [practice, setPractice] = useState<{ mode: string; external: boolean; packageName?: string; field?: string } | null>(null);
  const [packageName, setPackageName] = useState(''), [field, setField] = useState('');
  const { pending, act, feedback, available, windowRevision } = useOwnerAction({ run, state, supported });
  useEffect(() => { setReport(null); setPractice(null); }, [windowRevision]);
  useEffect(() => { registerBack(practice ? () => { setPractice(null); return true; } : null); return () => registerBack(null); }, [practice, registerBack]);
  const idle = available && !connection.taskOwned && !connection.busy && !pending && !state.phoneSubmitting;
  const canPractice = (mode: string) => idle && (mode === 'main_screen' || mode === 'background' && connection.helperConnected && !connection.requiresConnectionCheck);
  const practiceAction = practice?.external ? 'background.practice.field' : 'background.practice.start';
  const canConfirmPractice = !!practice && canPractice(practice.mode) && supported(practiceAction);
  const validField = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(packageName) && field.trim().length > 0 && field.length <= 120;
  useEffect(() => { if (practice && !canConfirmPractice) setPractice(null); }, [canConfirmPractice]);
  const labels: Data = { helper: '助手认证', display: '独立显示会话', tree: '界面树', frame: '实际画面', click: '点击后的计数', ascii_input: '英文输入读回', chinese_input: '中文输入读回', scroll: '滚动后内容', back: '返回离开测试页', live_updates: '延时内容更新', main_screen_isolation: '主屏隔离', ime_isolation: '输入法隔离', rotation: '旋转后的画面与点击' };
  return <section className="pw-settings-section pw-background-checks"><header className="pw-preference-heading"><h3>排查与演练</h3><p>先读取已有证据，需要时再明确开始演练。</p></header><Accordion variant="divided">
    <AccordionItem id="background-diagnostics" title="脱敏诊断报告">
      <p className="pw-field-hint">预览版本、权限和连接事实。预览不会运行演练或申请新权限。</p>
      <Button variant="secondary" disabled={!available || !!pending || owner.diagnosticBusy || !supported('background.diagnostics.preview')} onClick={() => { setReport(null); void act('background.diagnostics.preview', {}, value => { diagnosticPreview(value); return '已读取诊断预览。'; }, value => setReport(diagnosticPreview(value))); }}>{owner.diagnosticBusy || pending === 'background.diagnostics.preview' ? '正在读取诊断…' : '预览脱敏诊断报告'}</Button>
      {report && <><pre className="pw-reading-copy" style={{ maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{report.text}</pre><p className="pw-field-hint">复制会替换系统剪贴板。分享只打开系统选择器，请先核对预览内容。</p><div className="pw-form-actions"><Button variant="secondary" disabled={!available || !!pending || !supported('background.diagnostics.copy')} onClick={() => { void act('background.diagnostics.copy', { reportHandle: report.reportHandle }, result => { if (result?.copied !== true) throw new Error('复制尚未确认'); return '已复制这份诊断。'; }); }}>复制这份诊断</Button><Button variant="secondary" disabled={!available || !!pending || !supported('background.diagnostics.share')} onClick={() => { void act('background.diagnostics.share', { reportHandle: report.reportHandle }, result => { if (result?.shareChooserRequested !== true) throw new Error('系统选择器尚未确认'); return '已请求系统分享选择器。'; }); }}>系统分享…</Button></div></>}
    </AccordionItem>
    <AccordionItem id="background-practice" title="手机演练与实际结果">
      <p className="pw-field-hint">在 PocketWorld 自有测试页实际点击、输入中英文、滚动和返回。演练是一条原手机任务，最多 5 分钟并受原预算限制。</p>
      <div className="pw-form-actions">{['background', 'main_screen'].map(mode => <Button key={mode} variant="secondary" disabled={!canPractice(mode) || !supported('background.practice.start')} onClick={() => setPractice({ mode, external: false })}>{mode === 'background' ? '自有测试页后台演练…' : '主屏演练（占用屏幕）…'}</Button>)}<Button variant="secondary" disabled={!available || !!pending || owner.practiceReading || !supported('background.practice.read')} onClick={() => { void act('background.practice.read'); }}>读取原演练结果</Button></div>
      {owner.practiceReading && <p role="status" className="pw-field-hint">正在读取原演练证据…</p>}{owner.practiceError && <p role="alert" className="pw-field-error">{owner.practiceError}</p>}
      {evidence.ok === true ? <><p className="pw-field-hint">{evidence.resultScope === 'current_original_phone_run' ? '当前原演练任务的实际证据' : '上次原演练任务保存的历史证据'}</p><Accordion variant="divided">{items(evidence.checks).filter(check => labels[check.id]).map(check => <AccordionItem key={check.id} id={check.id} title={`${labels[check.id]} · ${check.applicable === false ? '本模式不适用' : check.state === 'passed' ? '已实际核验' : check.state === 'failed' ? '未通过' : '未取得证据'}`}><p>{object(check.expected).description || '旧版结果未记录逐项预期。'}</p><p className="pw-field-hint">原因：{check.reasonCode || 'not_observed'} · 验收契约 {Number.isSafeInteger(check.contractVersion) ? check.contractVersion : 1}</p><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(check.observed || {}, null, 2)}</pre></AccordionItem>)}</Accordion><p className="pw-field-hint">仅保留这条原任务的前后证据。未知项仍待核验；停止和资源回收以原任务回执为准。</p>{typeof evidence.taskId === 'string' && evidence.taskId.trim() && <Button variant="secondary" disabled={!available || !!pending || !supported('background.practice.open')} onClick={() => { void act('background.practice.open', { taskId: evidence.taskId }); }}>查看原演练任务</Button>}</> : <p className="pw-field-hint">尚未取得原演练实际证据；请求已登记不表示操作通过。</p>}
      <Accordion variant="divided"><AccordionItem id="background-field-practice" title="其他测试应用的单字段演练"><p className="pw-field-hint">只选择可安全修改的普通测试字段，真实输入“中文输入演练”，不提交。</p><TextField label="目标测试应用包名" value={packageName} onChange={setPackageName} /><TextField label="可安全测试的输入框标签" value={field} onChange={value => setField(value.slice(0, 120))} /><div className="pw-form-actions">{['background', 'main_screen'].map(mode => <Button key={mode} variant="secondary" disabled={!canPractice(mode) || !validField || !supported('background.practice.field')} onClick={() => setPractice({ mode, external: true, packageName, field })}>{mode === 'background' ? '此字段后台演练…' : '此字段主屏演练…'}</Button>)}</div></AccordionItem></Accordion>
    </AccordionItem>
  </Accordion>{feedback}<SettingsDialog isOpen={practice !== null} title="明确开始手机演练？" onClose={() => setPractice(null)}><p>{practice?.external ? `将在 ${practice.packageName} 的“${practice.field}”普通字段输入测试文字，不提交。` : '将打开 PocketWorld 自有测试页，实际点击、输入、滚动和返回。'}{practice?.mode === 'main_screen' ? '这次会切换应用并占用主屏。' : '这次使用原后台独立屏。'}暂停、停止和清场回执在原任务中查看。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setPractice(null)}>暂不开始</Button><Button disabled={!canConfirmPractice} onClick={() => { if (!practice || !canConfirmPractice) return; const target = practice; setPractice(null); void act(target.external ? 'background.practice.field' : 'background.practice.start', { mode: target.mode, confirmed: true, ...(target.external ? { packageName: target.packageName, field: target.field } : {}) }, value => { ownerActionReceipt(value); return '演练请求已登记，请在原任务核对启动与实际结果。'; }); }}>开始这次演练</Button></div></SettingsDialog></section>;
}
