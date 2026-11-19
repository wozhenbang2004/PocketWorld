import { useLayoutEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button } from './forms-library';
import { IconArrowUpRight } from './icons';

type Row = Record<string, any>;
type Location = { index: number; offset: number; previous: number[] };
type Reading = Location & (
  | { state: 'loading' }
  | { state: 'failed'; message: string }
  | { state: 'shown'; title: string; text: string; next: number; more: boolean; currentSummary: boolean; expires: number }
);
const kinds: Record<string, string> = { message: '原对话消息', task_evidence: '原任务记录', workspace_file: '原文件片段' };
const refKeys = ['kind', 'sourceId', 'revision', 'ownerId', 'spaceId'];
const fileKeys = ['projectId', 'path', 'revisionId', 'sha256', 'offset', 'chars'];
const isRow = (value: unknown): value is Row => typeof value === 'object' && value !== null && !Array.isArray(value);
function eligible(ref: unknown): ref is Row {
  if (!isRow(ref) || !Object.hasOwn(kinds, ref.kind) || typeof ref.sourceId !== 'string' || !ref.sourceId || !Number.isSafeInteger(ref.revision) || ref.revision < 1 || ref.ownerId !== 'owner' || ref.spaceId !== 'private') return false;
  if (ref.kind !== 'workspace_file') return true;
  const file = ref.workspace;
  return isRow(file) && Object.keys(file).length === fileKeys.length && fileKeys.every(key =>
    key === 'offset' || key === 'chars' ? Number.isSafeInteger(file[key]) && file[key] >= (key === 'offset' ? 0 : 1) : typeof file[key] === 'string' && !!file[key]);
}
function exactPage(expected: Row, index: number, offset: number, value: unknown): value is Row {
  if (!isRow(value) || !eligible(value.sourceRef)) return false;
  const ref = value.sourceRef;
  return refKeys.every(key => ref[key] === expected[key])
    && (ref.kind !== 'workspace_file' || fileKeys.every(key => ref.workspace[key] === expected.workspace[key]))
    && value.kind === expected.kind && (value.status === undefined || value.status === 'current')
    && (ref.kind !== 'task_evidence' || typeof value.historicalBodySnapshot === 'boolean')
    && value.index === index && value.offset === offset && typeof value.text === 'string' && value.text.length <= 8000
    && Number.isSafeInteger(value.nextOffset) && Number.isSafeInteger(value.totalChars) && value.totalChars >= value.nextOffset
    && value.nextOffset === offset + value.text.length && value.hasMore === (value.nextOffset < value.totalChars)
    && (!value.hasMore || value.nextOffset > offset) && Number.isSafeInteger(value.readExpiresAt);
}
const expired = '原阅读窗口已过期，请先重新读取这条记忆。';

/** Foreground only. Offsets, never source bodies, are retained for backward paging. */
export function PersonalMemorySources({ references: attached, disabled, pending, manual, read, start, reload }: {
  references: unknown[] | null; disabled: boolean; pending: boolean; manual: boolean;
  read: (index: number, offset: number) => Promise<unknown>; start: (work: () => Promise<void>) => void; reload: () => void;
}) {
  const references = attached ?? [];
  const [open, setOpen] = useState(false), [reading, setReading] = useState<Reading | null>(null);
  const generation = useRef(0), alive = useRef(false);
  const retire = () => { ++generation.current; setReading(null); setOpen(false); };
  useLayoutEffect(() => {
    alive.current = true;
    const hide = () => { if (document.hidden) retire(); };
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', retire);
    return () => { alive.current = false; ++generation.current; document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', retire); };
  }, []);
  useLayoutEffect(() => { if (disabled) retire(); }, [disabled]);
  useLayoutEffect(() => {
    if (reading?.state !== 'shown') return;
    const ticket = generation.current, { index, offset, previous, expires } = reading;
    const expire = () => {
      if (alive.current && ticket === generation.current) {
        ++generation.current; setReading({ index, offset, previous, state: 'failed', message: expired });
      }
    };
    const remaining = expires - Date.now();
    if (remaining <= 0) { expire(); return; }
    const timer = window.setTimeout(expire, remaining);
    return () => window.clearTimeout(timer);
  }, [reading]);

  const load = (location: Location) => {
    const { index, offset } = location, ref = references[index];
    if (!open || !alive.current || document.hidden || disabled || pending || !eligible(ref)) return;
    // The parent owns the exclusive native-action gate. A second tap that its gate declines
    // must not retire the first read or leave a loading indicator without a request.
    start(async () => {
      const ticket = ++generation.current;
      const current = () => alive.current && !document.hidden && ticket === generation.current;
      setReading({ ...location, state: 'loading' });
      try {
        const value = await read(index, offset);
        if (!current()) return;
        if (!exactPage(ref, index, offset, value)) throw new Error('原来源或分段回执不完整，请重新读取这条记忆。');
        if (value.readExpiresAt <= Date.now()) throw new Error(expired);
        setReading({ ...location, state: 'shown', title: typeof value.title === 'string' && value.title.trim() ? value.title : kinds[ref.kind],
          text: value.text, currentSummary: ref.kind === 'task_evidence' && value.historicalBodySnapshot === false,
          more: value.hasMore, next: value.nextOffset, expires: value.readExpiresAt });
      } catch (failure) {
        if (current()) setReading({ ...location, state: 'failed', message: failure instanceof Error ? failure.message : '原来源暂不可读。' });
      }
    });
  };

  const unavailable = references.length > 0 && !references.some(eligible);
  const active = !disabled && open && !document.hidden ? reading : null;
  const expiredPage = active?.state === 'shown' && active.expires <= Date.now();
  const shown = active?.state === 'shown' && !expiredPage ? active : null;
  const failure = active?.state === 'failed' ? active.message : expiredPage ? expired : '';
  const evidence = attached === null ? '来源清单未提供' : !references.length ? (manual ? '你直接记录，未附原文' : '未附原文')
    : disabled || unavailable ? '原来源暂不可读' : failure ? '原来源未能核对' : active?.state === 'loading' ? '正在读取原来源'
      : shown ? (shown.text.trim() ? `已读取来源 ${shown.index + 1} 的这一段` : '这段原来源没有正文') : `${references.length} 个来源，原文待查看`;
  return <Accordion className="pw-profile-disclosure" expandedKeys={open ? ['personal-source'] : []} onExpandedChange={keys => { if (keys.has('personal-source')) setOpen(true); else retire(); }}>
    <AccordionItem id="personal-source" title="来源" description={evidence}>
      <div className="pw-profile-sources">
        {references.map((ref, index) => <Button key={index} variant="ghost" size="small" leadingIcon={IconArrowUpRight} disabled={disabled || pending || !eligible(ref)} onClick={() => load({ index, offset: 0, previous: [] })}>
          来源 {index + 1} · {eligible(ref) ? kinds[ref.kind] : '不可读取'}
        </Button>)}
        {!references.length && <p className="pw-profile-help">{attached === null ? '未取得这条记录的来源清单，不能判断是否有原文。' : manual ? '这条内容由你直接记录，没有关联的聊天原文。' : '这条记录没有附带可查看的原文。'}</p>}
        {(disabled || unavailable) && references.length > 0 && <p className="pw-profile-help">当前记录没有可读取的来源正文。</p>}
        {active?.state === 'loading' && <p role="status" className="pw-profile-help">正在读取来源 {active.index + 1} 的所选片段…</p>}
        {failure && active && <div role="alert" className="pw-profile-help">
          <p>来源 {active.index + 1} 未核对：{failure}</p>
          <div className="pw-profile-actions">
            {failure !== expired && <Button variant="ghost" size="small" disabled={pending} onClick={() => load({ index: active.index, offset: active.offset, previous: active.previous })}>重试这一段</Button>}
            <Button variant="ghost" size="small" disabled={pending} onClick={reload}>重新读取记忆</Button>
          </div>
        </div>}
        {shown && <section className="pw-profile-source"><h3>{shown.title}</h3>
          <p className="pw-profile-help">{shown.currentSummary ? '其中的任务摘要是当前版本，不是确认当时的正文快照。' : '原记录可以帮助核对，不代表其中的说法已经证实。'}</p>
          <p>{shown.text.trim() ? shown.text : '这段原来源没有可显示的正文。'}</p>
          <div className="pw-profile-actions">
            {shown.previous.length > 0 && <Button variant="ghost" size="small" disabled={pending} onClick={() => load({ index: shown.index, offset: shown.previous[shown.previous.length - 1], previous: shown.previous.slice(0, -1) })}>上一段原文</Button>}
            {shown.more && <Button variant="ghost" size="small" disabled={pending} onClick={() => load({ index: shown.index, offset: shown.next, previous: [...shown.previous, shown.offset] })}>下一段原文</Button>}
          </div>
        </section>}
      </div>
    </AccordionItem>
  </Accordion>;
}
