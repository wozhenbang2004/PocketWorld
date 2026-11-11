import { AIAnswer } from './library';
import { Button, Accordion, AccordionItem } from './forms-library';
import { IconChevronRight, IconFile } from './icons';
import { getTaskOutcome } from './task-outcome';
import { useEffect, useRef, useState } from 'react';
import { useCollectionMotion, MotionFeedback } from './library/journey-motion';
import './task-reading.css';

/** Composition of PW-05 Answer and PW-22 file actions around host-owned evidence. */
export function TaskOutcome({ task, messages, onCopy, onOpenAttachment }: {
  task: unknown;
  messages: unknown;
  onCopy: (text: string) => Promise<unknown>;
  onOpenAttachment?: (messageId: string, id: string, sha256: string) => void;
}) {
  const [clock, setClock] = useState(Date.now);
  const outcome = getTaskOutcome(task, messages, Math.max(clock, Date.now()));
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
  const title = outcome.source === 'task-summary' ? '当前摘要' : outcome.source === 'evidence' && !outcome.evidenceRunBound ? '任务保留的结果' : '结果内容';
  const reply = outcome.reply;
  const files = reply?.attachments || [];
  const root = useRef<HTMLElement>(null);
  const record = task && typeof task === 'object' ? task as Record<string, any> : {};
  const owner = JSON.stringify([record.id, record.raw?.runId, record.raw?.coordinatorEpoch]);
  const revision = JSON.stringify([!!outcome.text, !!outcome.image, !!remotePhone, screenshot?.imageId, reply?.id, files.map(file => [file.id, file.sha256])]);
  useCollectionMotion(root, owner, revision, {layout: false});
  return <section ref={root} className="pw-task-outcome pw-task-reading" aria-label="任务结果依据" data-result-source={outcome.source}>
    <div className="pw-outcome-document">
      <header className="pw-outcome-heading"><h3>{outcome.text ? title : '暂无可读取的结果'}</h3></header>
      {outcome.text ? <div data-motion-key="result-body"><AIAnswer text={outcome.text} onCopy={() => onCopy(outcome.text)} onCopyCode={onCopy}/></div>
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
    </div>

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
