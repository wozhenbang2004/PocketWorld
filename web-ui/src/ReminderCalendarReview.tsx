import type { ordinaryReminderReview } from './plan-contract';

/** Shared display of the native calendar preview for an ordinary reminder. */
export function ReminderCalendarReview({ original }: { original: ReturnType<typeof ordinaryReminderReview> }) {
  if (!original) return <p role="alert">未能核对这份提醒的原时间。请返回重新核对，这次没有发出保存请求。</p>;
  return <>
    <dl className="pw-facts"><div><dt>提醒内容</dt><dd>{original.title}</dd></div><div><dt>重复安排</dt><dd>{original.repeat}</dd></div></dl>
    {original.times.length ? <section className="pw-knowledge-section" aria-label="原生日历预览">
      <h3>{original.repeat === '仅一次' ? '计划时间' : '接下来的计划时间'}</h3>
      {original.times.map(row => <div className="pw-selected-object" key={row.at}>
        <p>{row.local.replace('T', ' ')} · {row.zone} ({row.offset === 'Z' ? 'UTC' : 'UTC' + row.offset})</p>
        {row.dstResolution === 'gap_shifted_forward' && <p className="pw-field-hint">原当地时间不存在，按规则向后移。</p>}
        {row.dstResolution === 'overlap_earlier' && <p className="pw-field-hint">当地时间重复，使用较早一次。</p>}
        {row.dstResolution === 'overlap_later' && <p className="pw-field-hint">当地时间重复，使用较晚一次。</p>}
      </div>)}
      {original.absolute && <p className="pw-field-hint">按手机当前时区显示固定时刻，不改变原时间。</p>}
    </section> : <p className="pw-field-hint">{original.checkOnly ? '没有可核对的未来时间，原保存仍未确认。请到「我与管家 → 定时提醒」查看已有提醒和发生记录，不要重复新建。' : '当前没有可核对的未来时间。请在原对话明确提出新的时间。'}</p>}
    {original.times.length > 0 && <p className="pw-field-hint">{original.checkOnly ? '只核对原安排，不会重新创建或重发。' : '只保存本机提醒，不创建或启动工作。实际通知可能延后。'}</p>}
  </>;
}
