import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge, type PocketSnapshot } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, SettingsDialog } from './forms-library';
import { ProgressBar } from './library';
import { MotionFeedback } from './library/journey-motion';
import { IconArrowLeft, IconRefresh } from './icons';
import { usePocketAppearance } from './usePocketAppearance';

type Review = { packageName: string; versionCode: string; bytes: number; minSdk: number; artifactSha256: string; signerSha256: string[]; beforeInstalled: { observed: boolean } };
type Receipt = { requestId: string; state: string; review: Review; observedAt?: number; observation?: { installedIdentityMatches: boolean; matchedBeforeHandoff: boolean } };
type Data = { windowId: string; generation: number; ready: boolean; busy: boolean; message: string; sourcePresent: boolean; pendingHandoff: boolean; canRequestInstalls: boolean; review: Review | null; reviewId: string; receipt: Receipt | null };
type Action = 'projectApk.read' | 'projectApk.inspect' | 'projectApk.install' | 'projectApk.settings' | 'projectApk.reconcile' | 'projectApk.closeUnknown' | 'projectApk.close';
type Confirmation = { action: 'projectApk.install' | 'projectApk.settings' | 'projectApk.closeUnknown'; reviewId: string; requestId: string; review: Review | null };
const stateLabels: Record<string, string> = { awaiting_system: '等待或回执未知，不自动重投', not_started: '系统安装器没有启动', system_reported_success: '系统报告成功，实际身份另行核对', system_returned_without_success: '系统未返回成功', owner_closed_unknown: '已结束等待，安装结果仍未知' };

/** Composition of the same BoardUI controls, SettingsDialog and update-page layout. Native owns effects. */
export default function ProjectApkWorkspace({ snapshot }: { snapshot: PocketSnapshot }) {
  usePocketAppearance(snapshot.host?.appearance);
  const data = snapshot.projectApk as Data | undefined;
  const epoch = bridge.getDocumentEpoch();
  const [pending, setPending] = useState<Action | null>(null), [error, setError] = useState('');
  const [needsRead, setNeedsRead] = useState(false), [suspended, setSuspended] = useState(document.visibilityState === 'hidden');
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null), [acknowledged, setAcknowledged] = useState(false);
  const mounted = useRef(true), sequence = useRef(0), operation = useRef<Action | null>(null), lastEpoch = useRef(epoch);
  const live = useRef({ data, epoch }); live.current = { data, epoch };
  const connected = !suspended && snapshot.host?.ready === true && data?.ready === true;
  useEffect(() => {
    mounted.current = true;
    const retire = () => {
      sequence.current++;if (mounted.current && operation.current && operation.current !== 'projectApk.read') setNeedsRead(true);
      operation.current = null;if (mounted.current) { setPending(null);setConfirmation(null);setAcknowledged(false); }
    };
    const visibility = () => { if (document.visibilityState === 'hidden') { retire();setSuspended(true); } else setSuspended(false); };
    document.addEventListener('visibilitychange', visibility);window.addEventListener('pagehide', retire);
    return () => { mounted.current = false;retire();document.removeEventListener('visibilitychange', visibility);window.removeEventListener('pagehide', retire); };
  }, []);
  useLayoutEffect(() => {
    if (lastEpoch.current === epoch) return;
    const previous = lastEpoch.current;lastEpoch.current = epoch;if (previous === 0) return;
    sequence.current++;operation.current = null;setPending(null);setConfirmation(null);setAcknowledged(false);setNeedsRead(true);
    void bridge.bindHostDocument().then(() => { if (mounted.current && bridge.getDocumentEpoch() === epoch) setSuspended(document.visibilityState === 'hidden'); })
      .catch(() => { if (mounted.current && bridge.getDocumentEpoch() === epoch) setError('主人页面尚未重新连接，请用系统返回后从原成果打开。'); });
  }, [epoch]);
  useEffect(() => { document.documentElement.dataset.pocketUi = connected ? 'ready' : 'loading';return () => { delete document.documentElement.dataset.pocketUi; }; }, [connected]);
  useEffect(() => {
    bridge.setBackHandler(() => { if (!confirmation) return false;if (!pending) setConfirmation(null);return true; });
    return () => bridge.setBackHandler(null);
  }, [confirmation, pending]);
  const run = async (action: Action, values: Record<string, unknown> = {}) => {
    const original = live.current;
    if (!connected || operation.current || !original.data || !snapshot.capabilities.commands.includes(action)) return;
    const request = ++sequence.current;operation.current = action;setPending(action);setError('');
    try {
      await bridge.command(action, action === 'projectApk.read' ? {} : { windowId: original.data.windowId, generation: original.data.generation, ...values });
      if (!mounted.current || request !== sequence.current || original.epoch !== bridge.getDocumentEpoch()) return;
      if (action === 'projectApk.read') setNeedsRead(false);
      setConfirmation(null);setAcknowledged(false);
    } catch (failure) {
      if (!mounted.current || request !== sequence.current || original.epoch !== bridge.getDocumentEpoch()) return;
      const code = failure && typeof failure === 'object' && 'code' in failure ? String(failure.code) : '';
      setError(failure instanceof Error ? failure.message : '原交接回执未知，请读取原状态，不要重复提交。');
      if (action !== 'projectApk.read' && code !== 'not_sent') setNeedsRead(true);
      setConfirmation(null);setAcknowledged(false);
    } finally { if (mounted.current && request === sequence.current) { operation.current = null;setPending(null); } }
  };
  const locked = !connected || pending !== null;
  const effectLocked = locked || needsRead || data?.busy === true;
  const confirm = (action: Confirmation['action']) => { if (!data || effectLocked) return;setAcknowledged(false);setConfirmation({ action, reviewId: data.reviewId, requestId: data.receipt?.requestId || '', review: data.review }); };
  const confirmationCurrent = confirmation && (confirmation.action === 'projectApk.install' ? confirmation.reviewId === data?.reviewId && !data?.pendingHandoff : confirmation.action === 'projectApk.closeUnknown' ? confirmation.requestId === data?.receipt?.requestId && data?.pendingHandoff : !data?.pendingHandoff);
  const receipt = data?.receipt;
  return <main className="pw-app pw-secondary pw-updates" aria-label="私人项目 APK 安装">
    <header className="pw-secondary-header pw-updates-header"><IconButton icon={IconArrowLeft} aria-label="返回原成果" disabled={locked} onClick={() => void run('projectApk.close')} /><h1>安装私人项目 APK</h1></header>
    <div className="pw-updates-scroll"><div className="pw-updates-content">
      <section className="pw-updates-group pw-updates-version" aria-label="固定成果审阅">
        <MotionFeedback identity={data?.message || 'reading'} role="status" className="pw-updates-message">{data?.message || '正在读取原构建成果…'}</MotionFeedback>
        {error && <MotionFeedback identity={error} role="alert" className="pw-field-error">{error}</MotionFeedback>}
        {(data?.busy || !data) && <ProgressBar aria-label="读取原 APK 身份" isIndeterminate showValueLabel={false} />}
        {(needsRead || data?.pendingHandoff) && <Button variant="secondary" disabled={locked} leadingIcon={IconRefresh} onClick={() => void run('projectApk.read')}>读取原状态，不重投</Button>}
        {data?.review && <ApkIdentity review={data.review} />}
        {data?.sourcePresent && !data.pendingHandoff && <div className="pw-form-actions">
          <Button variant="secondary" disabled={effectLocked} leadingIcon={IconRefresh} onClick={() => void run('projectApk.inspect')}>重新核对 APK</Button>
          {!data.canRequestInstalls ? <Button disabled={effectLocked} onClick={() => confirm('projectApk.settings')}>由我设置安装来源</Button>
            : <Button disabled={effectLocked || !data.review} onClick={() => confirm('projectApk.install')}>审阅后交给系统安装器</Button>}
        </div>}
      </section>
      {receipt && <section className="pw-updates-group" aria-label="原安装交接回执">
        <h2>原交接</h2><p>{stateLabels[receipt.state] || '结果未知，只核对'}</p><ApkIdentity review={receipt.review} />
        {receipt.observation && <p className="pw-updates-caption">上次只读核对 {receipt.observedAt ? new Date(receipt.observedAt).toLocaleString() : ''}：{receipt.observation.installedIdentityMatches ? `当时包名、版本与证书匹配。${receipt.observation.matchedBeforeHandoff ? '交接前已匹配，不能据此确认本次发生安装。' : '没有逐字节核验安装内容或证明安装归因。'}` : '当时未能确认匹配，可能不可见、未安装或身份不同；查不到不等于未安装。'}</p>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={effectLocked} onClick={() => void run('projectApk.reconcile', { requestId: receipt.requestId })}>核对原安装状态（只读）</Button>
          {data?.pendingHandoff && <Button variant="ghost" disabled={effectLocked} onClick={() => confirm('projectApk.closeUnknown')}>结束等待，不判定成败</Button>}</div>
      </section>}
      <section className="pw-updates-group pw-updates-details" aria-label="安装范围"><Accordion variant="divided">
        <AccordionItem id="apk-owner-boundary" title="哪些事情仍由我确认"><p>仅原私人成果中不超过 20 MiB、带可解析签名信息的完整 APK。证书指纹是解析结果，不等于独立验签；最终由 Android 系统校验并由你确认安装。</p><p>不会自动签名、导出密钥、卸载、降级、替换不同证书或运行应用。PocketWorld 自身升级仍走原更新入口。开启安装来源设置后，返回不会自动安装。</p></AccordionItem>
        <AccordionItem id="apk-result-boundary" title="结果未知时如何处理"><p>恢复和核对只读原交接，不重投。系统成功信号与包身份观察分开，既有同版本不算作这次新安装已证实。这里只保留当前及上一条交接引用，不是完整安装历史。</p></AccordionItem>
        <AccordionItem id="apk-toolchain-boundary" title="这不等于开发环境已配好"><p>SDK/JDK/Gradle 安装、签名密钥管理、超过 20 MiB 或分包产物、标准模块生成目录仍有未完成工作。此入口不承诺 APK 已构建、已运行或已测试。</p></AccordionItem>
      </Accordion></section>
    </div></div>
    <SettingsDialog isOpen={confirmation !== null} title={confirmation?.action === 'projectApk.install' ? '交给系统安装器前核对' : confirmation?.action === 'projectApk.settings' ? '由我设置安装来源' : '结束等待，不判断安装结果'} closeLabel="返回核对" onClose={() => { if (!pending) setConfirmation(null); }}>
      {confirmation && <div className="pw-collaboration-dialog">
        {confirmation.action === 'projectApk.install' ? <><ApkIdentity review={confirmation.review!} /><p>应用安装后可能运行代码和请求权限。仅把这份固定成果交给系统，最终仍需你亲自确认；不会跳过确认或自动卸载旧应用。</p></>
          : confirmation.action === 'projectApk.settings' ? <p>仅打开 PocketWorld 的系统安装来源设置，不替你操作开关。返回后仍需重新核对并明确请求安装。</p>
            : <p>请先退出或完成原系统安装页面。这只结束应用内等待，不取消可能已发生的系统安装，不把未知记为失败；原引用保留，另一次提交必须重新审阅。</p>}
        {!confirmationCurrent && <p role="alert">原对象或窗口已变化，请返回重新读取。</p>}
        <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={effectLocked || !confirmationCurrent}>我已核对这次原对象和操作范围</CheckboxField>
        <div className="pw-form-actions"><Button variant="secondary" disabled={pending !== null} onClick={() => setConfirmation(null)}>返回核对</Button>
          <Button disabled={effectLocked || !confirmationCurrent || !acknowledged} onClick={() => void run(confirmation.action, { confirmed: true, ...(confirmation.action === 'projectApk.install' ? { reviewId: confirmation.reviewId } : confirmation.action === 'projectApk.closeUnknown' ? { requestId: confirmation.requestId } : {}) })}>{confirmation.action === 'projectApk.install' ? '交给系统审阅' : confirmation.action === 'projectApk.settings' ? '打开系统设置' : '已离开原安装页面，结束等待'}</Button></div>
      </div>}
    </SettingsDialog>
  </main>;
}
function ApkIdentity({ review }: { review: Review }) {
  return <div className="pw-updates-release"><h2>{review.packageName}</h2><p className="pw-updates-caption">版本代码 {review.versionCode} · {(review.bytes / 1048576).toFixed(1)} MiB · 最低 API {review.minSdk}</p>
    <p className="pw-updates-caption">成果 SHA256</p><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{review.artifactSha256}</pre>
    <p className="pw-updates-caption">签名证书 SHA256（解析值）</p><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{review.signerSha256.join('\n')}</pre>
    {review.beforeInstalled.observed && <p className="pw-updates-caption">交接前已观察到这个包，请留意安装可能覆盖其程序。</p>}
  </div>;
}
