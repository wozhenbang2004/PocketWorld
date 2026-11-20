import type { ReactNode } from 'react';
import { Button } from './forms-library';
import { IconChevronRight } from './icons';
import { AIAnswer } from './library';
import { teamResultReading, type TeamFollowUp } from './team-result-reading';

type Actions = { items: TeamFollowUp[]; disabled: boolean; read: (item: TeamFollowUp) => void; expanded?: boolean; toggle?: () => void };
export function TeamFollowUps({ items, disabled, read, expanded = true, toggle }: Actions) {
  if (!items.length) return null;
  const visible = !toggle || expanded ? items : items.slice(0, 2);
  return <section className="pw-team-action-list" aria-label="这次协作需要你处理的原事项">
    <header className="pw-collaboration-group-heading"><h3>需要你处理</h3><span>{items.length} 项</span></header>
    {visible.map(item => <Button key={item.key} className="pw-team-question-row h-auto whitespace-normal" variant="ghost"
      trailingIcon={IconChevronRight} data-team-follow-up={item.key} disabled={disabled} onClick={() => read(item)}>
      <span className="pw-team-attention-copy"><strong>{item.title}</strong><small>{item.detail}</small></span></Button>)}
    {toggle && items.length > 2 && <Button variant="ghost" size="small" aria-expanded={expanded} onClick={toggle}>
      {expanded ? '收起其余事项' : `查看其余 ${items.length - 2} 项`}</Button>}
  </section>;
}

export function TeamResultHandoff({ result, actions, sources, frozenFiles, finalPackageReader, revisionOffers, stopped, openMembers, openMessage, openOriginalReview, nextRequest }: {
  result: ReturnType<typeof teamResultReading>; actions: Actions; sources?:ReactNode; frozenFiles?:ReactNode; finalPackageReader?:ReactNode; revisionOffers?:ReactNode; stopped: boolean;
  openMembers: () => void; openMessage: (id: string) => void; openOriginalReview?: () => void;
  nextRequest?: { open: () => void; disabled: boolean };
}) {
  const summary = result.body ? <><AIAnswer text={result.body}/>
    {result.excerpt && <p className="pw-field-hint">这里只保留了原汇总的节选。</p>}
    {result.messageId && <Button variant="ghost" size="small" data-team-follow-up="summary-original" disabled={actions.disabled} onClick={() => openMessage(result.messageId)}>查看汇总原消息</Button>}</> : null;
  const memberLink = !sources && !frozenFiles && !finalPackageReader && <Button variant="ghost" trailingIcon={IconChevronRight}
    data-team-follow-up="members" disabled={actions.disabled} onClick={openMembers}>查看分工与产出</Button>;
  return <section className="pw-team-result" aria-label="本次协作成果">
    <header className="pw-team-result-heading"><h3 data-team-result-heading tabIndex={-1}>{result.previous ? '上次保存的共同汇总' : result.body ? '本轮共同汇总' : '本次协作成果'}</h3>
      {result.previous && <span className="pw-team-result-stale">历史版本</span>}
      {result.author && <span className="pw-team-result-caption">由 <bdi>{result.author}</bdi> 汇总</span>}</header>
    <TeamFollowUps {...actions}/>
    {revisionOffers}
    {finalPackageReader}
    {frozenFiles}
    {openOriginalReview && <Button variant="secondary" size="small" disabled={actions.disabled} trailingIcon={IconChevronRight}
      onClick={openOriginalReview}>查看待审阅的原协作任务</Button>}
    {result.original && summary}
    {result.original && nextRequest && <Button variant="secondary" trailingIcon={IconChevronRight} disabled={actions.disabled || nextRequest.disabled}
      onClick={nextRequest.open}>在原对话写后续要求</Button>}
    {sources}
    {result.statusNote && <div className="pw-team-result-caption"><strong>原协作记录</strong><p dir="auto" style={{ whiteSpace: 'pre-wrap' }}>{result.statusNote}</p></div>}
    {result.previous && <p className="pw-team-result-caption">这是当时保存的汇总，不代表当前进展或授权。</p>}
    {!result.original && summary}
    {result.original && memberLink}
    {!summary && <div className="pw-team-result-empty"><p>{stopped ? '协作已停止后续派发，成员已有的产出和记录仍可查看。' : '还没有能核对来源的共同汇总。各成员已保存的产出可以单独查看。'}</p>
      {memberLink}</div>}
  </section>;
}
