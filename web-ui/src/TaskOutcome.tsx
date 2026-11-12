import { AIAnswer } from './library';
import { Button, Accordion, AccordionItem } from './forms-library';
import { IconChevronRight, IconFile } from './icons';
import { getTaskOutcome } from './task-outcome';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useCollectionMotion, MotionFeedback } from './library/journey-motion';
import { ownerWorkPresentation } from './owner-work-presentation';
import { OwnerWorkReport } from './OwnerWorkReport';
import { bridge } from './bridge';
import { projectPackageTaskIdentity, type PackagePreviewTarget } from './project-preview';
import './task-reading.css';

type PackageRead = { identity: string; taskId: string; items: unknown[]; hasMore: boolean; readLimited: boolean; nextCursor: string; unavailable: unknown[] };
const packageReadSupported = () => bridge.getState().capabilities.commands.includes('tasks.projectPackages');
const packageReadWindow = () => {
  const view = bridge.getState().view as { section?: string; taskId?: string; resumeRevision?: number };
  return JSON.stringify([bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision]);
};

/** This page holds only original-operation display metadata. Progress pushes
 * never rescan the plans; each additional history page is an explicit read. */
function useProjectPackages(task: unknown, active: boolean, identity: string) {
  const target = projectPackageTaskIdentity(task), taskId = target?.taskId || '';
  const scope = JSON.stringify([identity, taskId, target?.projectId, target?.checkoutId]);
  const [supported, setSupported] = useState(packageReadSupported);
  const [retained, setRetained] = useState<PackageRead | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const saved = useRef<PackageRead | null>(null), alive = useRef(false), pending = useRef(false), generation = useRef(0);
  const current = useRef({ scope, enabled: false }); current.current = { scope, enabled: active && supported && !!target };
  const read = useCallback(async (cursor = '') => {
    if (!alive.current || !current.current.enabled || current.current.scope !== scope || document.hidden || pending.current) return;
    const previous = saved.current?.identity === scope ? saved.current : null;
    if (cursor && previous?.nextCursor !== cursor) { setError('成果读取位置已变化，请重新读取固定成果。'); return; }
    const request = ++generation.current, windowIdentity = packageReadWindow();
    pending.current = true; setBusy(true); setError('');
    const accepts = () => alive.current && current.current.enabled && current.current.scope === scope && request === generation.current &&
      !document.hidden && packageReadWindow() === windowIdentity;
    try {
      const result = await bridge.command('tasks.projectPackages', { taskId, cursor });
      if (!accepts()) return;
      if (result?.taskId !== taskId || result.readOnly !== true || result.executionAuthorized !== false ||
          result.currentArtifactAvailabilityVerified !== false || !Array.isArray(result.items) || result.items.length > 8 ||
          typeof result.hasMore !== 'boolean' || typeof result.readLimited !== 'boolean' || typeof result.nextCursor !== 'string' || result.nextCursor.length > 2048 ||
          result.hasMore && (!result.nextCursor || result.nextCursor === cursor) || !result.hasMore && result.nextCursor !== '' ||
          !Array.isArray(result.unavailable) || result.unavailable.length > 32) throw new Error('原任务固定成果的读取范围未能核对，请重新读取。');
      const page: PackageRead = { identity: scope, taskId, items: cursor && previous ? [...previous.items, ...result.items] : result.items,
        hasMore: result.hasMore, readLimited: result.readLimited || !!(cursor && previous?.readLimited), nextCursor: result.nextCursor,
        unavailable: cursor && previous ? [...previous.unavailable, ...result.unavailable] : result.unavailable };
      saved.current = page; setRetained(page);
    } catch (failure) {
      if (accepts()) setError(failure instanceof Error ? failure.message : '原任务固定成果暂不可读取，请重试。');
    } finally {
      if (request === generation.current) { pending.current = false; if (alive.current) setBusy(false); }
    }
  }, [scope, taskId]);
  useEffect(() => {
    alive.current = true; setBusy(false); setError('');
    let windowIdentity = packageReadWindow();
    const invalidate = () => { generation.current++; pending.current = false; saved.current = null; setRetained(null); setBusy(false); };
    if (saved.current?.identity !== scope) invalidate();
    if (active && supported && taskId) void read();
    const inspect = () => {
      setSupported(packageReadSupported());
      const next = packageReadWindow();
      if (next === windowIdentity) return;
      windowIdentity = next; invalidate();
      if (!document.hidden && current.current.enabled) void read();
    };
    const visibility = () => { invalidate(); if (!document.hidden && current.current.enabled) void read(); };
    const unsubscribe = bridge.subscribe(inspect);
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', invalidate);
    return () => { alive.current = false; generation.current++; pending.current = false; unsubscribe(); document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', invalidate); };
  }, [active, supported, scope, taskId, read]);
  return { eligible: !!target, supported, page: retained?.identity === scope ? retained : null, busy, error, read };
}

/** Composition of PW-05 Answer and PW-22 file actions around host-owned evidence. */
export function TaskOutcome({ task, messages, active, canReadReport, readIdentity, onCopy, onOpenAttachment, onOpenProjectPackage }: {
  task: unknown;
  messages: unknown;
  active: boolean;
  canReadReport: boolean;
  readIdentity: string;
  onCopy: (text: string) => Promise<unknown>;
  onOpenAttachment?: (messageId: string, id: string, sha256: string) => void;
  onOpenProjectPackage?: (target: PackagePreviewTarget) => void;
}) {
  const [clock, setClock] = useState(Date.now);
  const packageRead = useProjectPackages(task, active, readIdentity);
  const outcome = getTaskOutcome(task, messages, Math.max(clock, Date.now()), packageRead.page ?? undefined);
  const screenshot = outcome.screenshot;
  const remotePhone = outcome.remotePhoneSnapshot;
  const phoneStatus = outcome.remotePhoneSnapshotStatus;
  useEffect(() => {
    const expiry = screenshot?.expiresAt;
    if (!expiry || expiry <= Date.now()) return;
    const timer = window.setTimeout(() => setClock(Date.now()), Math.min(expiry - Date.now() + 1, 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [screenshot?.imageId, screenshot?.expiresAt, clock]);
  const imageLabel = screenshot?.currentRun ? '本轮结果截图' : '任务保留的较早截图';
  const work = ownerWorkPresentation(task);
  const progress = outcome.source === 'result-event' && outcome.resultStatus !== 'completed';
  const title = progress ? '当前进展' : outcome.source === 'task-summary' ? '当前摘要' : outcome.source === 'evidence' && !outcome.evidenceRunBound ? '任务保留的结果' : work ? '执行结果' : '结果内容';
  const reply = outcome.reply;
  const files = reply?.attachments || [];
  const root = useRef<HTMLElement>(null);
  const record = task && typeof task === 'object' ? task as Record<string, any> : {};
  const report = work?.delivered && typeof record.raw?.runId === 'string' && Number.isSafeInteger(record.raw?.coordinatorEpoch) && record.raw.coordinatorEpoch > 0
    ? { taskId: String(record.id), runId: record.raw.runId as string, epoch: record.raw.coordinatorEpoch as number, reportMessageId: work.reportMessageId, conversationId: work.conversationId } : null;
  const owner = JSON.stringify([record.id, record.raw?.runId, record.raw?.coordinatorEpoch]);
  const revision = JSON.stringify([!!outcome.text, !!outcome.image, !!remotePhone, screenshot?.imageId, reply?.id, files.map(file => [file.id, file.sha256]), outcome.projectPackages.map(item => [item.operationId, item.archiveSha256])]);
  useCollectionMotion(root, owner, revision, {layout: false});
  const executionResult = <div className="pw-outcome-document">
      <header className="pw-outcome-heading"><h3>{outcome.text ? title : outcome.projectPackages.length ? '结果正文' : '暂无可读取的结果正文'}</h3></header>
      {outcome.text ? <div data-motion-key="result-body"><AIAnswer text={outcome.text} showActions={!progress} onCopy={progress ? undefined : () => onCopy(outcome.text)} onCopyCode={onCopy}/></div>
        : <p className="pw-reading-note">{files.length ? '可先查看下方关联答复中的附件。' : '当前记录未提供可读取的结果正文。'}</p>}
      {outcome.image && <figure className="pw-outcome-image" data-motion-key={`image:${screenshot?.imageId || 'retained'}`}><img src={outcome.image} alt={imageLabel} loading="lazy" decoding="async"/><figcaption>{imageLabel}</figcaption></figure>}
      {remotePhone && <figure className="pw-outcome-image" data-motion-key={`phone:${screenshot?.imageId}`}>
        <img src={remotePhone.image} alt={`设备 ${remotePhone.deviceId.slice(-4)} 的${remotePhone.screenMode === 'background' ? '独立屏' : '主屏'}观察快照`} loading="lazy" decoding="async"/>
        <figcaption>设备 {remotePhone.deviceId.slice(-4)} · {remotePhone.screenMode === 'background' ? '独立屏' : '主屏'} · {new Date(remotePhone.capturedAt).toLocaleString()} · {remotePhone.width} × {remotePhone.height}
          <br/>{remotePhone.currentRun ? '本轮任务观察到的静态画面' : '较早执行保留的静态画面'}。不是实时视频，也不能点图操作；后续动作仍由原任务按已确认范围执行，图片不代表目标完成。</figcaption>
      </figure>}
      {phoneStatus && (phoneStatus !== 'saved' || !remotePhone) && <p className="pw-reading-note" role="status">{
        phoneStatus === 'retention_disabled' ? '这次手机观察未保存：本机截图留存已关闭。已有历史图片不会因此变成实时画面。' :
        phoneStatus === 'too_large' ? '这次手机观察超过本机截图的 4 MB 留存上限，未保存；已有历史图片仍按原期限保留。' :
        phoneStatus === 'save_unconfirmed' ? '已取得手机观察，但本机加密保存尚未确认。请重新打开原任务核对；没有使用明文替代。' :
        phoneStatus === 'saved' ? screenshot?.state === 'expired' || screenshot?.state === 'deleted' || screenshot?.readState === 'unreadable' ? '这份手机画面当前不可显示，具体留存状态见下方。' : '手机观察已保存；重新打开原任务可读取图片。' :
        '本轮还没有可保存的手机观察画面。需要原任务执行带图观察、目标允许全屏捕获，并开启本机截图留存；不会为打开此页面额外抓图。'
      }</p>}
      {outcome.partial && <MotionFeedback identity={`${owner}:partial`} className="pw-outcome-limit" role="status">部分结果记录尚未载入。</MotionFeedback>}
    </div>;
  return <section ref={root} className="pw-task-outcome pw-task-reading" aria-label="任务结果依据" data-result-source={outcome.source}>
    {packageRead.eligible && <section className="pw-outcome-deliverables" aria-label="项目固定成果" aria-busy={packageRead.busy || undefined}>
      <div className="pw-reading-section-heading"><h4>项目固定成果</h4>{outcome.projectPackages.length > 0 && <span>{outcome.projectPackages.length} 个已读取版本</span>}</div>
      {outcome.projectPackages.length > 0 && <>
        <p className="pw-reading-note">这些版本已由原保存操作生成。任务完成、生成成果、发出与发布各有自己的回执；打开时会核对原文件。</p>
        <div className="pw-outcome-files">{outcome.projectPackages.map(item => <Button key={item.operationId} data-motion-key={`package:${item.operationId}:${item.archiveSha256}`}
          variant="secondary" className="pw-outcome-file" leadingIcon={IconFile} trailingIcon={IconChevronRight} disabled={!active || !onOpenProjectPackage}
          onClick={() => onOpenProjectPackage?.({ taskId: item.taskId, operationId: item.operationId })}>
          <span className="pw-outcome-file-copy"><span>{item.name}</span>
            <small>{item.currentRun ? '本轮生成' : '较早执行生成'} · {item.mode === 'web_output' ? '网页成果' : '源码项目包'}{item.fileCount === undefined ? '' : ` · ${item.fileCount} 个文件`} · {item.archiveBytes.toLocaleString()} 字节</small>
            {item.omittedCount !== undefined && item.omittedCount > 0 && <small>保存时有 {item.omittedCount} 项未收入包内，具体范围保留在原清单。</small>}
            <small>{onOpenProjectPackage ? '查看固定版本' : '当前无法打开原固定版本'}</small>
          </span>
        </Button>)}</div>
      </>}
      {packageRead.busy && <p className="pw-reading-note" role="status">正在读取原任务保存的固定成果…</p>}
      {packageRead.error && <p className="pw-reading-note pw-error" role="alert">{packageRead.error}</p>}
      {!packageRead.supported && <p className="pw-reading-note">当前连接尚不能读取项目固定成果。</p>}
      {packageRead.page && !outcome.projectPackages.length && !packageRead.busy && <p className="pw-reading-note">{outcome.projectPackagesPartial ? '这一段原记录尚未提供可核对的固定成果。' : '当前读取的原保存记录中没有固定成果。'}</p>}
      {outcome.projectPackagesPartial && <p className="pw-outcome-limit" role="status">{packageRead.page?.hasMore ? '还有原保存记录尚未读取。' : '部分原保存记录未能完整核对，当前列表不代表全部交付物。'}{packageRead.page?.readLimited ? ' 本次另有记录因读取限制或凭证不可用而保留缺口。' : ''}</p>}
      {active && packageRead.supported && <div className="pw-action-row">
        {packageRead.page?.hasMore && <Button variant="secondary" disabled={packageRead.busy} onClick={() => void packageRead.read(packageRead.page!.nextCursor)}>继续读取固定成果</Button>}
        <Button variant="ghost" disabled={packageRead.busy} onClick={() => void packageRead.read()}>重新读取固定成果</Button>
      </div>}
    </section>}
    {report && <OwnerWorkReport key={JSON.stringify(report)} target={report} active={active} supported={canReadReport} identity={readIdentity} onCopy={onCopy} />}
    {report ? <Accordion variant="divided" className="pw-reading-disclosures" aria-label="执行原始结果"><AccordionItem id="execution-result" title="查看执行原始结果" description="保留执行时的正文和画面">{executionResult}</AccordionItem></Accordion> : executionResult}

    {/* These files belong to the linked message, not necessarily to the current run. */}
    {files.length > 0 && <section className="pw-outcome-deliverables" aria-label="关联答复中的附件">
      <div className="pw-reading-section-heading"><h4>关联答复中的附件</h4><span>{files.length} 个文件</span></div>
      <div className="pw-outcome-files">
        {files.map(file => <Button key={String(file.id)} data-motion-key={`file:${reply?.id}:${file.id}`} variant="secondary" className="pw-outcome-file" leadingIcon={IconFile} trailingIcon={IconChevronRight}
          disabled={!reply?.id || !onOpenAttachment || typeof file.sha256 !== 'string'}
          onClick={() => onOpenAttachment?.(reply!.id, String(file.id), String(file.sha256))}>
          <span className="pw-outcome-file-copy"><span>{typeof file.name === 'string' && file.name ? file.name : '查看附件'}</span>
            <small>{!reply?.id || typeof file.sha256 !== 'string' ? '原文件身份待核对' : !onOpenAttachment ? '当前无法打开附件' : '打开原附件'}</small></span>
        </Button>)}
      </div>
    </section>}

    {(outcome.retained || reply?.text) && <Accordion variant="divided" className="pw-reading-disclosures pw-outcome-related" aria-label="其他结果记录">
      {outcome.retained && <AccordionItem id="retained-result" title="任务保留的结果" description="与当前内容分开保留">
        <div className="pw-outcome-conversation">
          {outcome.retained.text && <AIAnswer text={outcome.retained.text} onCopy={() => onCopy(outcome.retained!.text)} onCopyCode={onCopy}/>}
          {outcome.retained.image && <figure className="pw-outcome-image"><img src={outcome.retained.image} alt="任务保留的截图" loading="lazy"/><figcaption>{imageLabel}</figcaption></figure>}
        </div>
      </AccordionItem>}
      {reply?.text && <AccordionItem id="related-reply" title="对话中的相关答复" description="任务关联的对话内容">
        <div className="pw-outcome-conversation"><AIAnswer text={reply.text} onCopy={() => onCopy(reply.text)} onCopyCode={onCopy}/></div>
      </AccordionItem>}
    </Accordion>}
    {screenshot && <p className="pw-outcome-limit pw-outcome-retention">{screenshot.state === 'deleted' ? '这张本机结果截图已删除。' : screenshot.state === 'expired' ? '截图已到留存期限，不再显示。' : screenshot.readState === 'unreadable' ? '原截图暂不可读取，没有使用明文替代。' : screenshot.state.startsWith('migration_') ? '旧截图仍保留，正在等待本机加密迁移核对。' : screenshot.expiresAt ? '本机加密截图保留至 ' + new Date(screenshot.expiresAt).toLocaleString() + '。' : '旧截图继续保留，可在原任务核对中主动删除。'}{screenshot.deletionPending ? ' 文件删除尚待核对。' : ''}</p>}
  </section>;
}
