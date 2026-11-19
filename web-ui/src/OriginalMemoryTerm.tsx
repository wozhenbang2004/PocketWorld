import { useState } from 'react';
import { Accordion, AccordionItem, Button } from './forms-library';
import { CalendarDateTimeFields } from './CalendarDateTimeFields';
import { calendarTimeAt, validCalendarTime, type CalendarTime } from './calendar-time';

export function termInZone(at: number | null, zone: string): string {
  if (at === null) return '不设到期时间';
  if (!Number.isFinite(new Date(at).getTime())) return '期限未核对';
  return new Intl.DateTimeFormat(undefined, { timeZone: zone, year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short' }).format(at);
}

/** Per-read-handle input only. No draft is sent or kept after leaving this original note. */
export function OriginalMemoryTerm({ prior, disabled, onReview }: { prior: number | null; disabled: boolean; onReview: (value: CalendarTime) => void }) {
  const [value, setValue] = useState(() => {
    const now = Date.now(), next = Math.ceil((now + 1) / 60000) * 60000;
    return calendarTimeAt(prior === null ? now + 7 * 86400000 : Math.max(next, Math.min(now + 7 * 86400000, now + (prior - now) / 2)));
  });
  return <Accordion className="pw-profile-disclosure"><AccordionItem id="personal-original-term" title="缩短有效期" description={prior === null ? '当前不设到期时间' : '当前至 ' + termInZone(prior, Intl.DateTimeFormat().resolvedOptions().timeZone)}>
    <p className="pw-profile-list-hint">选一个更早的时间。核对前不会更改原期限。</p>
    <CalendarDateTimeFields label="到期时间" value={value} onChange={setValue} disabled={disabled} />
    <Button variant="secondary" size="small" disabled={disabled || !validCalendarTime(value)} onClick={() => onReview(value)}>核对新期限</Button>
  </AccordionItem></Accordion>;
}
