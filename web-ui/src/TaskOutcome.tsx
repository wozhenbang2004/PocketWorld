import { AIAnswer } from './library';
import { Button, Accordion, AccordionItem } from './forms-library';
import { RiFileLine } from '@remixicon/react';
import { getTaskOutcome } from './task-outcome';
import { useEffect, useState } from 'react';

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
  useEffect(() => {
    const expiry = screenshot?.expiresAt;
    if (!expiry || expiry <= Date.now()) return;
    const timer = window.setTimeout(() => setClock(Date.now()), Math.min(expiry - Date.now() + 1, 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [screenshot?.imageId, screenshot?.expiresAt, clock]);
  const imageLabel = screenshot?.currentRun ? '本轮结果截图' : '任务保留的较早截图';
  return <section className="pw-task-outcome" aria-label="任务结果依据" data-result-source={outcome.source}>
    {outcome.text ? <div className="pw-outcome-document">
      <div className="pw-outcome-heading"><h3>{outcome.source === 'task-summary' ? '当前摘要' : outcome.source === 'evidence' && !outcome.evidenceRunBound ? '任务保留的结果' : '结果内容'}</h3><span>可核对的内容</span></div>
      <AIAnswer text={outcome.text} onCopy={() => onCopy(outcome.text)} onCopyCode={onCopy}/>
    </div> : <div className="pw-outcome-empty"><h3>还没有可读取的结果正文</h3><p>可以先查看执行过程，或等待任务返回结果。</p></div>}
    {outcome.image && <figure className="pw-outcome-image"><img src={outcome.image} alt={imageLabel} loading="lazy"/><figcaption>{imageLabel}</figcaption></figure>}
    {outcome.retained && <Accordion variant="divided" aria-label="任务保留的结果"><AccordionItem id="retained-result" title="任务保留的结果" description="展开查看保留的内容与截图"><AIAnswer text={outcome.retained.text} onCopy={() => onCopy(outcome.retained!.text)} onCopyCode={onCopy}/>{outcome.retained.image && <figure className="pw-outcome-image"><img src={outcome.retained.image} alt="任务保留的截图" loading="lazy"/></figure>}</AccordionItem></Accordion>}
    {outcome.reply && <Accordion variant="divided" className="pw-outcome-related" aria-label="关联答复"><AccordionItem id="related-reply" title={outcome.reply.text ? '对话中的相关答复' : '相关答复中的附件'} description="需要时展开，保留原文与附件"><div className="pw-outcome-conversation">
      {outcome.reply.text && <AIAnswer text={outcome.reply.text} onCopy={() => onCopy(outcome.reply!.text)} onCopyCode={onCopy}/>}
      {outcome.reply.id && outcome.reply.attachments.length > 0 && <div className="pw-outcome-files" aria-label="关联答复中的附件">
        {outcome.reply.attachments.map(file => <Button key={String(file.id)} variant="secondary" leadingIcon={RiFileLine}
          disabled={!onOpenAttachment || typeof file.sha256 !== 'string'} onClick={() => onOpenAttachment?.(outcome.reply!.id, String(file.id), String(file.sha256))}>
          {typeof file.name === 'string' ? file.name : '查看附件'}
        </Button>)}
      </div>}
    </div></AccordionItem></Accordion>}
    {outcome.partial && <p className="pw-outcome-limit">这里展示的是已读取的结果，部分记录尚未载入。</p>}
    {screenshot && <p className="pw-outcome-limit">{screenshot.state === 'deleted' ? '这张本机结果截图已删除。' : screenshot.state === 'expired' ? '截图已到留存期限，不再显示。' : screenshot.readState === 'unreadable' ? '原截图暂不可读取，没有使用明文替代。' : screenshot.state.startsWith('migration_') ? '旧截图仍保留，正在等待本机加密迁移核对。' : screenshot.expiresAt ? '本机加密截图保留至 ' + new Date(screenshot.expiresAt).toLocaleString() + '。' : '旧截图继续保留，可在原任务核对中主动删除。'}{screenshot.deletionPending ? ' 文件删除尚待核对。' : ''}</p>}
  </section>;
}
