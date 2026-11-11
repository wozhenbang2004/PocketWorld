export type CalendarTime = { date: string; time: string; zone: string; laterOverlap: boolean };
export function calendarTimeAt(at: number, zone = Intl.DateTimeFormat().resolvedOptions().timeZone): CalendarTime {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(at);
  const part = (type: string) => parts.find(value => value.type === type)?.value || '';
  return { date: `${part('year')}-${part('month')}-${part('day')}`, time: `${part('hour')}:${part('minute')}`, zone, laterOverlap: false };
}
export function validCalendarTime(value: CalendarTime): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value.date) && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value.time) && !!value.zone.trim();
}

