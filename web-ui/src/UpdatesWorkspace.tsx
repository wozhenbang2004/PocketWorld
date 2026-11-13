import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconArrowLeft, IconDownload, IconRefresh } from './icons';
import { bridge, type PocketSnapshot } from './bridge';
import { Accordion, AccordionItem, Button, IconButton, StretchSwitch } from './forms-library';
import { ProgressBar } from './library';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { usePocketAppearance } from './usePocketAppearance';

type UpdatesState = {
  windowId: string; generation: number; ready: boolean; configured: boolean;
  installedVersion: string; phase: string; message: string; delivery: string;
  busy: boolean; packageReady: boolean; automatic: boolean;
  downloadedBytes: number; totalBytes: number; notificationPermissionRequired: boolean; awaitingSystem: boolean;
  release: { versionName: string; versionCode: number; notes: string; bytes: number } | null;
};
type UpdateAction = 'updates.read' | 'updates.check' | 'updates.automatic' | 'updates.download'
  | 'updates.cancel' | 'updates.install' | 'updates.notifications' | 'updates.close';
const megabytes = (value: number) => `${(Math.max(0, value) / 1048576).toFixed(1)} MB`;

/** One bundled settings page; the original Activity still owns all update and system handoffs. */
export default function UpdatesWorkspace({ snapshot }: { snapshot: PocketSnapshot }) {
  usePocketAppearance(snapshot.host?.appearance);
  const data = snapshot.updates as UpdatesState | undefined;
  const epoch = bridge.getDocumentEpoch();
  const [pending, setPending] = useState<UpdateAction | null>(null);
  const [error, setError] = useState('');
  const [needsRead, setNeedsRead] = useState(false);
  const [suspended, setSuspended] = useState(document.visibilityState === 'hidden');
  const version = useRef<HTMLElement>(null);
  const mounted = useRef(true), sequence = useRef(0), lastEpoch = useRef(epoch);
  const operation = useRef<UpdateAction | null>(null);
  const live = useRef({ data, epoch }); live.current = { data, epoch };
  const connected = !suspended && snapshot.host?.ready === true && data?.ready === true;

  useEffect(() => {
    mounted.current = true;
    const hide = () => {
      if (document.visibilityState !== 'hidden') return;
      sequence.current++;
      if (operation.current && operation.current !== 'updates.read' && operation.current !== 'updates.close') {
        setNeedsRead(true); setError('更新操作的回执尚未确认，请读取当前状态后再继续。');
      }
      operation.current = null; setPending(null); setSuspended(true);
    };
    document.addEventListener('visibilitychange', hide);
    return () => { mounted.current = false; sequence.current++; document.removeEventListener('visibilitychange', hide); };
  }, []);
  useLayoutEffect(() => {
    if (lastEpoch.current === epoch) return;
    const previous = lastEpoch.current; lastEpoch.current = epoch;
    if (previous === 0) return; // MountedApp owns the first handshake.
    sequence.current++;
    if (operation.current && operation.current !== 'updates.read' && operation.current !== 'updates.close') {
      setNeedsRead(true); setError('更新操作的回执尚未确认，请读取当前状态后再继续。');
    }
    operation.current = null; setPending(null);
    void bridge.bindHostDocument().then(() => {
      if (mounted.current && bridge.getDocumentEpoch() === epoch) setSuspended(document.visibilityState === 'hidden');
    }).catch(() => {
      if (mounted.current && bridge.getDocumentEpoch() === epoch) setError('更新页面尚未重新连接，请使用系统返回键后重新打开。');
    });
  }, [epoch]);
  useEffect(() => {
    document.documentElement.dataset.pocketUi = connected ? 'ready' : 'loading';
    return () => { delete document.documentElement.dataset.pocketUi; };
  }, [connected]);

  const run = async (action: UpdateAction, values: Record<string, unknown> = {}) => {
    const original = live.current;
    if (!connected || pending || operation.current || !original.data || !snapshot.capabilities.commands.includes(action)) return;
    const request = ++sequence.current;
    operation.current = action; setPending(action); setError('');
    const args = action === 'updates.read' ? {} : { windowId: original.data.windowId, generation: original.data.generation, ...values };
    try {
      await bridge.command(action, args);
      if (!mounted.current || request !== sequence.current || original.epoch !== bridge.getDocumentEpoch()) return;
      if (action === 'updates.read') setNeedsRead(false);
    } catch (failure) {
      if (!mounted.current || request !== sequence.current || original.epoch !== bridge.getDocumentEpoch()) return;
      const code = failure && typeof failure === 'object' && 'code' in failure ? String(failure.code) : '';
      setError(failure instanceof Error ? failure.message : '更新操作回执尚未确认，请重新读取当前状态。');
      if (action !== 'updates.read' && code !== 'not_sent') setNeedsRead(true);
    } finally {
      if (mounted.current && request === sequence.current) { operation.current = null; setPending(null); }
    }
  };
  const working = data?.busy === true;
  const cancellable = working && ['downloading', 'applying', 'verifying'].includes(data?.phase || '');
  const locked = !connected || pending !== null;
  const primary: { action: UpdateAction; label: string } | null = needsRead
    ? { action: 'updates.read', label: '读取当前状态' }
    : cancellable ? { action: 'updates.cancel', label: '暂停下载' }
    : working ? null
    : data?.packageReady ? { action: 'updates.install', label: '安装新版本' }
    : data?.release ? { action: 'updates.download', label: ['paused', 'failed'].includes(data.phase) ? '继续下载' : '下载更新' }
    : data?.configured ? { action: 'updates.check', label: '检查新版本' } : null;
  const measured = working && data?.phase === 'downloading' && data.totalBytes > 0;
  const progress = measured ? Math.min(100, Math.max(0, data.downloadedBytes * 100 / data.totalBytes)) : undefined;
  const transfer = data?.release && data.delivery ? `${data.delivery}${data.totalBytes > 0 ? ` · ${megabytes(data.downloadedBytes)} / ${megabytes(data.totalBytes)}` : ''}` : '';

  useCollectionMotion(version, JSON.stringify([epoch, data?.windowId]),
    JSON.stringify([data?.release?.versionCode, data?.phase, !!error, data?.awaitingSystem, primary?.action, pending, !!transfer]),
    { active: connected, enter: false });

  return <main className="pw-app pw-secondary pw-updates" aria-label="应用更新">
    <header className="pw-secondary-header pw-updates-header">
      <IconButton icon={IconArrowLeft} aria-label="返回设置" disabled={locked} onClick={() => void run('updates.close')} />
      <h1>应用更新</h1>
    </header>
    <div className="pw-updates-scroll">
      <div className="pw-updates-content">
        <section ref={version} className="pw-updates-group pw-updates-version" aria-label="版本与更新状态">
          <p className="pw-updates-caption">当前版本</p>
          <p className="pw-updates-installed">{data?.installedVersion || '正在读取当前版本…'}</p>
          {data?.release && <div className="pw-updates-release" data-motion-key="release"><h2>新版本 {data.release.versionName}</h2><p className="pw-updates-caption">完整安装包 {megabytes(data.release.bytes)}</p>{data.release.notes && <Accordion variant="divided"><AccordionItem id="update-release-notes" title="本次更新内容"><p className="pw-updates-notes">{data.release.notes}</p></AccordionItem></Accordion>}</div>}
          <MotionFeedback identity={data?.phase || 'reading'} initial={false} data-motion-key="message" className={`pw-updates-message${data?.phase === 'failed' ? ' pw-field-error' : ''}`} role={data?.phase === 'failed' ? 'alert' : 'status'}>{data?.message || '正在读取更新状态…'}</MotionFeedback>
          {error && <MotionFeedback identity={error} className="pw-field-error pw-updates-error" role="alert" data-motion-key="error">{error}</MotionFeedback>}
          {data?.awaitingSystem && <MotionFeedback identity="system-handoff" className="pw-updates-caption" role="status" data-motion-key="system-handoff">请完成或取消原系统窗口，返回后再核对当前状态。</MotionFeedback>}
          {(working || !data) && <div data-motion-key="progress"><ProgressBar aria-label={data?.message || '读取更新状态'} value={progress} isIndeterminate={!measured} showValueLabel={measured} className="pw-updates-progress" /></div>}
          {transfer && <p className="pw-updates-caption pw-updates-transfer" data-motion-key="transfer">{transfer}</p>}
          {primary && <MotionFeedback identity={`${primary.action}:${pending === primary.action}`} initial={false} className="pw-updates-actions" data-motion-key="actions" aria-busy={pending !== null}>
            <Button disabled={locked} variant={cancellable && !needsRead ? 'secondary' : 'primary'} leadingIcon={primary.action === 'updates.download' ? IconDownload : primary.action === 'updates.check' || primary.action === 'updates.read' ? IconRefresh : undefined} onClick={() => void run(primary.action)}>{pending === primary.action ? primary.action === 'updates.check' ? '正在检查…' : primary.action === 'updates.read' ? '正在读取…' : primary.action === 'updates.install' ? '正在打开安装确认…' : primary.action === 'updates.cancel' ? '正在暂停…' : '正在开始下载…' : primary.label}</Button>
            {data?.release && !working && !needsRead && <Button variant="ghost" disabled={locked} onClick={() => void run('updates.check')}>检查新版本</Button>}
          </MotionFeedback>}
          {data?.packageReady && <p className="pw-updates-caption">安装仍需你在 Android 系统窗口确认；返回本页不会自动安装。</p>}
        </section>

        <section className="pw-updates-group pw-updates-preferences" aria-label="更新偏好">
          <StretchSwitch label="自动检查新版本" description="只检查并通知，不会自动下载或安装。" checked={data?.automatic === true} disabled={!connected || !data?.configured || needsRead || pending !== null} busy={pending === 'updates.automatic'} onChange={enabled => void run('updates.automatic', { enabled })} />
          {data?.configured && data.notificationPermissionRequired && <div className="pw-updates-notification"><p className="pw-updates-caption">允许通知后，发现新版本时可以提醒你。</p><Button variant="secondary" disabled={locked || needsRead} onClick={() => void run('updates.notifications')}>允许更新通知</Button></div>}
        </section>

        <section className="pw-updates-group pw-updates-details" aria-label="更新说明">
          <Accordion variant="divided">
            <AccordionItem id="update-check-schedule" title="自动检查如何运行"><p>应用启动时及 Android 允许的约 6 小时间隔检查。这是定期检查，不是实时服务端推送。</p></AccordionItem>
            <AccordionItem id="update-verification" title="下载与安装安全"><p>更新包先验证发布清单签名、文件完整性、应用包名、版本和当前安装签名。增量不可用时改用完整包。</p><p>最终安装由 Android 提示确认，不会为绕过签名而卸载应用。此页面和系统安装确认由你亲手操作；手机任务会在敏感步骤交还控制。</p></AccordionItem>
          </Accordion>
        </section>
      </div>
    </div>
  </main>;
}
