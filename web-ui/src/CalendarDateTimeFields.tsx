import { Accordion, AccordionItem, Button, CheckboxField, TextField } from './forms-library';
import { WheelTimeField, WheelValuePicker } from './forms-library/uiux/WheelTimeField';
import { WheelPickerWrapper } from './forms-library/uiux/wheel';

import { calendarTimeAt, type CalendarTime } from './calendar-time';
const pad = (value: number) => String(value).padStart(2, '0');

/** Business composition of the adopted BoardUI fields and ncdai fbe21d7 wheel source. */
export function CalendarDateTimeFields({ value, onChange, disabled = false, label = '提醒时间' }: { value: CalendarTime; onChange: (value: CalendarTime) => void; disabled?: boolean; label?: string }) {
  const [year, month, day] = value.date.split('-').map(Number), valid = /^\d{4}-\d{2}-\d{2}$/.test(value.date) && month >= 1 && month <= 12 && day >= 1 && day <= new Date(Date.UTC(year, month, 0)).getUTCDate();
  let validZone = true; try { new Intl.DateTimeFormat('en', { timeZone: value.zone }).format(); } catch { validZone = false; }
  const startYear = new Date().getFullYear(), years = Array.from({ length: 3 }, (_, index) => startYear + index);
  if (Number.isInteger(year) && !years.includes(year)) years.unshift(year);
  const changeDate = (nextYear: number, nextMonth: number, nextDay: number) => onChange({ ...value, date: `${nextYear}-${pad(nextMonth)}-${pad(Math.min(nextDay, new Date(Date.UTC(nextYear, nextMonth, 0)).getUTCDate()))}` });
  const quick = (days: number) => {
    const today = calendarTimeAt(Date.now(), value.zone).date;
    const next = new Date(today + 'T12:00:00Z'); next.setUTCDate(next.getUTCDate() + days);
    onChange({ ...value, date: next.toISOString().slice(0, 10) });
  };
  return <div className="pw-form-grid">
    <TextField label="日期" value={value.date} onChange={date => onChange({ ...value, date })} maxLength={10} isDisabled={disabled} hint="年-月-日，例如 2026-11-12" />
    <div className="pw-form-actions">{[{ days: 0, label: '今天' }, { days: 1, label: '明天' }, { days: 7, label: '一周后' }].map(item => <Button key={item.days} variant="ghost" disabled={disabled || !validZone} onClick={() => quick(item.days)}>{item.label}</Button>)}</div>
    {!validZone && <p className="pw-field-error" role="alert">请先在“时区与夏令时”中填写有效时区。</p>}
    {valid && <Accordion><AccordionItem id="calendar-date" title="滚动选择日期" description={value.date}><WheelPickerWrapper><WheelValuePicker label="年份" value={year} options={years.map(value => ({ value, label: String(value) + '年' }))} disabled={disabled} onChange={next => changeDate(next, month, day)} /><WheelValuePicker label="月份" value={month} options={Array.from({ length: 12 }, (_, index) => ({ value: index + 1, label: `${index + 1}月` }))} disabled={disabled} onChange={next => changeDate(year, next, day)} /><WheelValuePicker label="日期" value={day} options={Array.from({ length: new Date(Date.UTC(year, month, 0)).getUTCDate() }, (_, index) => ({ value: index + 1, label: `${index + 1}日` }))} disabled={disabled} onChange={next => changeDate(year, month, next)} /></WheelPickerWrapper></AccordionItem></Accordion>}
    <WheelTimeField label={label} value={value.time} onChange={time => onChange({ ...value, time })} isDisabled={disabled} />
    <Accordion><AccordionItem id="calendar-zone" title="时区与夏令时" description={value.zone}><TextField label="时区" value={value.zone} onChange={zone => onChange({ ...value, zone })} isDisabled={disabled} hint="例如 Asia/Shanghai；核对时由手机计算准确时刻。" /><CheckboxField isSelected={value.laterOverlap} onChange={laterOverlap => onChange({ ...value, laterOverlap })} isDisabled={disabled}>夏令时结束导致时间重复时，使用较晚一次</CheckboxField><p className="pw-field-hint">不存在的当地时间会要求重新选择。最终日期、时间与时区将在保存前显示。</p></AccordionItem></Accordion>
  </div>;
}
