import type { ReactNode } from 'react';
import { Button } from './forms-library';
import { IconChevronRight } from './icons';
import { AIAnswer } from './library';
import type { teamPartialReading, teamResultReading, TeamFollowUp } from './team-result-reading';

type Actions = { items: (TeamFollowUp & { originalQuestion?: boolean })[]; disabled: boolean; read: (item: TeamFollowUp) => void;
  expanded?: boolean; toggle?: () => void; parentSupplement?: { open: () => void; disabled: boolean } };
export function TeamFollowUps({ items, disabled, read, expanded = true, toggle, parentSupplement }: Actions) {
  if (!items.length) return null;
  const visible = !toggle || expanded ? items : items.slice(0, 2);
  return <section className="pw-team-action-list" aria-label="这次协作需要你处理的原事项">
    <header className="pw-collaboration-group-heading"><h3>需要你处理</h3><span>{items.length} 项</span></header>
    {visible.map(item => <Button key={item.key} className="pw-team-question-row h-auto whitespace-normal" variant="ghost"
      trailingIcon={IconChevronRight} data-team-follow-up={item.key} disabled={disabled} onClick={() => read(item)}>
      <span className="pw-team-attention-copy"><strong dir={item.originalQuestion ? 'auto' : undefined}
        style={item.originalQuestion ? { whiteSpace: 'pre-wrap' } : undefined}>{item.title}</strong><small>{item.detail}</small></span></Button>)}
    {parentSupplement && visible.some(item => item.originalQuestion) && <div className="pw-team-message-entry">
      <Button variant="secondary" size="small" trailingIcon={IconChevronRight} disabled={disabled || parentSupplement.disabled}
        onClick={parentSupplement.open}>到原工作补充信息</Button>
      <p className="pw-field-hint">只打开原工作输入，不是直接回答成员。</p>
    </div>}
    {toggle && items.length > 2 && <Button variant="ghost" size="small" aria-expanded={expanded} onClick={toggle}>
      {expanded ? '收起其余事项' : `查看其余 ${items.length - 2} 项`}</Button>}
  </section>;
}

export function TeamResultHandoff({ result, partial, memberName, actions, sources, showSourcesFirst = false, archivedSources, frozenFiles, finalPackageReader, revisionOffers, stopped, openMembers, openMessage, openOriginalReview, nextRequest }: {
  result: ReturnType<typeof teamResultReading>; actions: Actions; sources?:ReactNode; archivedSources?:ReactNode; frozenFiles?:ReactNode; finalPackageReader?:ReactNode; revisionOffers?:ReactNode; stopped: boolean;
  showSourcesFirst?: boolean;
  partial: ReturnType<typeof teamPartialReading>; memberName: (id: string) => string;
  openMembers: () => void; openMessage: (id: string) => void; openOriginalReview?: () => void;
  nextRequest?: { open: () => void; disabled: boolean };
}) {
  const summary = result.body ? <><AIAnswer text={result.body}/>
    {result.excerpt && <p className="pw-field-hint">这里只保留了原汇总的节选。</p>}
    {result.messageId && <Button variant="ghost" size="small" data-team-follow-up="summary-original" disabled={actions.disabled} onClick={() => openMessage(result.messageId)}>查看汇总原消息</Button>}</> : null;
  const memberLink = !sources && !frozenFiles && !finalPackageReader && <Button variant="ghost" trailingIcon={IconChevronRight}
    data-team-follow-up="members" disabled={actions.disabled} onClick={openMembers}>查看分工与产出</Button>;
  const summaryTitle = result.previous ? '上次保存的共同汇总' : result.partial ? '本次的部分汇总' : '本轮共同汇总';
  const summaryFirst = result.original && !partial;
  const summaryByline = result.author && <span className="pw-team-result-caption">由 <bdi>{result.author}</bdi> 汇总</span>;
  const leadingSources = showSourcesFirst && !!sources;
  const summaryHeading = <header className="pw-team-result-heading"><h4>{summaryTitle}</h4>{summaryByline}</header>;
  return <section className="pw-team-result" aria-label="本次协作成果">
    <header className="pw-team-result-heading"><h3 data-team-result-heading tabIndex={-1}>{partial ? '本次已交回的成果' : leadingSources || !result.body ? '本次协作成果' : summaryTitle}</h3>
      {!partial && result.previous && <span className="pw-team-result-stale">历史版本</span>}
      {!partial && !leadingSources && summaryByline}</header>
    <TeamFollowUps {...actions}/>
    {revisionOffers}
    {finalPackageReader}
    {frozenFiles}
    {openOriginalReview && <Button variant="secondary" size="small" disabled={actions.disabled} trailingIcon={IconChevronRight}
      onClick={openOriginalReview}>查看待审阅的原协作任务</Button>}
    {leadingSources && sources}
    {summaryFirst && summary && <>{leadingSources && summaryHeading}{summary}</>}
    {result.original && nextRequest && <div>
      <Button variant="secondary" trailingIcon={IconChevronRight} disabled={actions.disabled || nextRequest.disabled}
        onClick={nextRequest.open}>在原对话写后续要求</Button>
      <p className="pw-field-hint">只带回草稿，不会发送或继续协作。</p>
    </div>}
    {!leadingSources && sources}
    {partial && (partial.remaining.length > 0 || partial.synthesizing || !summary || result.previous) && <div className="pw-team-result-remainder">
      {partial.remaining.length > 0 && <><h4>尚未完成的分工</h4><ul role="list">
        {partial.remaining.map(item => <li key={item.memberId}><span className="pw-team-attention-copy"><strong><bdi>{memberName(item.memberId)}</bdi></strong>
          {item.assignment && <small dir="auto">{item.assignment}</small>}</span>
          <span className="pw-team-result-caption">{item.states}</span>
        </li>)}
      </ul></>}
      {partial.synthesizing ? <p className="pw-team-result-caption">正在整理共同汇总。</p>
        : (!summary || result.previous) && <p className="pw-team-result-caption">还没有本次的共同汇总。</p>}
    </div>}
    {archivedSources}
    {result.statusNote && <div className="pw-team-result-caption"><strong>原协作记录</strong><p dir="auto" style={{ whiteSpace: 'pre-wrap' }}>{result.statusNote}</p></div>}
    {!summaryFirst && summary && <>{(partial || leadingSources) && summaryHeading}
      {result.previous && <p className="pw-team-result-caption">这是当时保存的汇总，不代表当前进展或授权。</p>}
      {summary}</>}
    {result.original && memberLink}
    {!summary && !partial && <div className="pw-team-result-empty"><p>{stopped ? '协作已停止后续派发，成员已有的产出和记录仍可查看。' : archivedSources && !sources ? '尚未读到本版可核对的成员答复或共同汇总。' : '还没有能核对来源的共同汇总。各成员已保存的产出可以单独查看。'}</p>
      {memberLink}</div>}
  </section>;
}
