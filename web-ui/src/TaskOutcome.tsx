import { AIAnswer } from './library';
import { Button, Accordion, AccordionItem } from './forms-library';
import { RiFileLine } from '@remixicon/react';
import { getTaskOutcome } from './task-outcome';

/** Composition of PW-05 Answer and PW-22 file actions around host-owned evidence. */
export function TaskOutcome({ task, messages, onCopy, onOpenAttachment }: {
  task: unknown;
  messages: unknown;
  onCopy: (text: string) => Promise<unknown>;
  onOpenAttachment?: (messageId: string, id: string) => void;
}) {
  const outcome = getTaskOutcome(task, messages);
  return <section className="pw-task-outcome" aria-label="任务结果依据" data-result-source={outcome.source}>
    {outcome.text ? <div className="pw-outcome-document">
      <div className="pw-outcome-heading"><h3>{outcome.source === 'task-summary' ? '当前摘要' : outcome.source === 'evidence' && !outcome.evidenceRunBound ? '任务保留的结果' : '结果内容'}</h3><span>可核对的内容</span></div>
      <AIAnswer text={outcome.text} onCopy={() => onCopy(outcome.text)} onCopyCode={onCopy}/>
    </div> : <div className="pw-outcome-empty"><h3>还没有可读取的结果正文</h3><p>可以先查看执行过程，或等待任务返回结果。</p></div>}
    {outcome.image && <figure className="pw-outcome-image"><img src={outcome.image} alt="任务保留的现场截图" loading="lazy"/><figcaption>任务现场截图</figcaption></figure>}
    {outcome.retained && <Accordion variant="divided" aria-label="任务保留的结果"><AccordionItem id="retained-result" title="任务保留的结果" description="展开查看保留的内容与截图"><AIAnswer text={outcome.retained.text} onCopy={() => onCopy(outcome.retained!.text)} onCopyCode={onCopy}/>{outcome.retained.image && <figure className="pw-outcome-image"><img src={outcome.retained.image} alt="任务保留的截图" loading="lazy"/></figure>}</AccordionItem></Accordion>}
    {outcome.reply && <div className="pw-outcome-conversation">
      <div className="pw-outcome-heading"><h3>{outcome.reply.text ? '对话中的相关答复' : '相关答复中的附件'}</h3></div>
      {outcome.reply.text && <AIAnswer text={outcome.reply.text} onCopy={() => onCopy(outcome.reply!.text)} onCopyCode={onCopy}/>}
      {outcome.reply.id && outcome.reply.attachments.length > 0 && <div className="pw-outcome-files" aria-label="关联答复中的附件">
        {outcome.reply.attachments.map(file => <Button key={String(file.id)} variant="secondary" leadingIcon={RiFileLine}
          disabled={!onOpenAttachment} onClick={() => onOpenAttachment?.(outcome.reply!.id, String(file.id))}>
          {typeof file.name === 'string' ? file.name : '查看附件'}
        </Button>)}
      </div>}
    </div>}
    {outcome.partial && <p className="pw-outcome-limit">这里展示的是已读取的结果，部分记录尚未载入。</p>}
  </section>;
}
