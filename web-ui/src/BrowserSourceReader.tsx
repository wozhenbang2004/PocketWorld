import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { RiArrowLeftLine, RiArrowRightSLine, RiCloseLine, RiFileTextLine, RiRefreshLine } from '@remixicon/react';
import { bridge, type PocketSnapshot } from './bridge';
import { usePocketAppearance } from './usePocketAppearance';
import { Accordion, AccordionItem, Button } from './forms-library';
import { Drawer } from './shell-library';
import { Skeleton, ShimmerText } from './library';
import { captureReaderPosition, readerCoverage, readerDomain, readerIdentity, restoreReaderPosition, readerFieldCurrent, readerFieldPayload, readerPin, readerTime, sameReader, type ReaderField, type ReaderPosition, type ReaderState } from './reader-contract';
import './browser-source-reader.css';
import './browser-depth.css';

/** One native-pinned saved source, with a reversible field view in the same reading window. */
export default function BrowserSourceReader({ snapshot }: { snapshot: PocketSnapshot }) {
  usePocketAppearance(snapshot.host?.appearance);
  const state = snapshot.browserSources as ReaderState | undefined;
  const [field, setField] = useState<ReaderField | null>(null);
  const [error, setError] = useState('');
  const body = useRef<HTMLDivElement>(null), table = useRef<HTMLDivElement>(null), title = useRef<HTMLHeadingElement>(null);
  const readingPosition = useRef<ReaderPosition | null>(null);
  const fieldFocus = useRef<{ identity: string; key: string } | null>(null);
  const request = useRef(0), mounted = useRef(true), stateRef = useRef(state);
  const seenField = useRef<ReaderField | null>(null);
  const epoch = bridge.getDocumentEpoch(), previousEpoch = useRef(epoch);
  stateRef.current = state;
  const source = state?.ready ? state.source : null;
  const activeField = source && field && readerFieldCurrent(field, source) ? field : null;
  const busy = !state?.ready || state.loading;

  useEffect(() => {
    mounted.current = true;
    document.documentElement.dataset.pocketUi = 'ready';
    return () => { mounted.current = false; request.current++; delete document.documentElement.dataset.pocketUi; };
  }, []);
  useEffect(() => {
    if (previousEpoch.current === epoch) return;
    previousEpoch.current = epoch;
    request.current++; setField(null); setError(''); seenField.current = null;
    // Only a newly issued native document token can rebind after focus/background loss.
    void bridge.bindHostDocument().catch(() => { if (mounted.current) setError('阅读窗口尚未重新连接，请返回原结果后重新打开。'); });
  }, [epoch]);
  useEffect(() => {
    if (!state?.ready) { request.current++; setField(null); seenField.current = null; setError(''); }
  }, [state?.ready, state?.generation]);
  useEffect(() => {
    const next = state?.field;
    if (!next || !source || next === seenField.current || !readerFieldCurrent(next, source)) return;
    seenField.current = next;
    setField(next);
  }, [state?.field, source]);

  function rememberPosition() {
    if (!activeField && source && !state?.loading && body.current)
      readingPosition.current = captureReaderPosition(source, body.current.scrollTop, table.current?.scrollLeft ?? 0);
  }
  function backToSource() {
    request.current++; setField(null); setError('');
  }
  function close() {
    const current = stateRef.current;
    if (!current?.ready) return;
    request.current++;
    void bridge.command('browserSources.close', readerPin(current)).catch(() => {
      if (mounted.current) setError('阅读窗口尚未关闭，请使用系统返回键回到原结果。');
    });
  }
  useEffect(() => bridge.setBackHandler(() => {
    if (activeField) { backToSource(); return true; }
    if (!stateRef.current?.ready) return false; // The host closes only this dialog.
    close(); return true;
  }), [activeField]);

  useLayoutEffect(() => {
    if (!body.current || !source) return;
    const position = restoreReaderPosition(source, readingPosition.current);
    body.current.scrollTop = activeField ? 0 : position.scrollTop;
    if (!activeField && table.current) table.current.scrollLeft = position.tableScrollLeft;
    if (!activeField && fieldFocus.current?.identity === readerIdentity(source)) {
      const target = Array.from(body.current.querySelectorAll<HTMLButtonElement>('[data-reader-key]'))
        .find(node => node.dataset.readerKey === fieldFocus.current?.key);
      target?.focus({ preventScroll: true });
      if (target) fieldFocus.current = null;
    } else title.current?.focus({ preventScroll: true });
  }, [activeField?.fieldRef, source?.sourceFingerprint, state?.generation, !!source]);

  async function readField(ref: string, focusKey: string) {
    const current = stateRef.current;
    if (!current || busy) return;
    rememberPosition(); fieldFocus.current = current.source ? { identity: readerIdentity(current.source), key: focusKey } : null; setError('');
    const original = readerPin(current), operation = ++request.current;
    try { await bridge.command('browserSources.field', readerFieldPayload(current, ref)); }
    catch (failure) {
      if (mounted.current && operation === request.current && sameReader(original, stateRef.current))
        setError(failure instanceof Error ? failure.message : '字段读取未能开始，请重读原件。');
    }
  }
  async function refresh() {
    const current = stateRef.current;
    if (!current || busy) return;
    rememberPosition(); setField(null); seenField.current = null; setError('');
    const original = readerPin(current), operation = ++request.current;
    try { await bridge.command('browserSources.refresh', original); }
    catch (failure) {
      if (mounted.current && operation === request.current && sameReader(original, stateRef.current))
        setError(failure instanceof Error ? failure.message : '原件读取未能开始，请稍后重试。');
    }
  }

  const heading = activeField ? (activeField.mode === 'table' ? `第 ${activeField.rowIndex + 1} 行 · 第 ${activeField.columnIndex + 1} 列` : `第 ${activeField.rowIndex + 1} 项`) : source?.title || '当时保存的原文';
  const notice = error || state?.error;
  const captured = source ? readerTime(source.capturedAt) : null;
  return <Drawer.Root open shouldScaleBackground={false} dismissible={false}>
    <Drawer.Portal>
      <Drawer.Content className="browser-reader" aria-describedby="browser-reader-description" onEscapeKeyDown={event => { event.preventDefault(); if (activeField) backToSource(); else close(); }}>
        <header className="browser-reader-toolbar">
          <Button variant="ghost" iconOnly leadingIcon={RiArrowLeftLine} className="browser-reader-control" aria-label={activeField ? '返回原文' : '返回研究结果'} onClick={activeField ? backToSource : close} disabled={!state?.ready} />
          <div className="browser-reader-location"><RiFileTextLine aria-hidden /><span>{activeField ? '字段依据' : '原文依据'}</span></div>
          <Button variant="ghost" iconOnly leadingIcon={RiRefreshLine} className="browser-reader-control" aria-label="重读同一份保存原件" onClick={() => void refresh()} disabled={busy} />
          <Button variant="ghost" iconOnly leadingIcon={RiCloseLine} className="browser-reader-control" aria-label="关闭原文，返回研究结果" onClick={close} disabled={!state?.ready} />
        </header>
        <div className="browser-reader-scroll" ref={body} data-reader-view={activeField ? 'field' : 'source'} onScroll={rememberPosition}>
          <div className="browser-reader-page">
            <div className="browser-reader-heading">
              {source && <p className="browser-reader-domain">{readerDomain(source.safeUrl)}{captured && <><span aria-hidden> · </span><time dateTime={captured.iso}>{captured.label}</time></>}</p>}
              <Drawer.Title asChild><h1 ref={title} tabIndex={-1}>{heading}</h1></Drawer.Title>
              <Drawer.Description id="browser-reader-description">当时保存的{source?.coverage.partial ? '部分' : '所选范围'}原文 · 尚未经核实</Drawer.Description>
            </div>
            {notice && <div className="browser-reader-error" role="alert"><p>{notice}</p><Button variant="secondary" className="browser-reader-control" onClick={() => void refresh()} disabled={busy}>重读原件</Button></div>}
            {busy && <div className="browser-reader-loading" role="status"><ShimmerText>正在核对当时保存的原件…</ShimmerText>{!source && <div aria-hidden="true"><Skeleton height={18} width="87%" /><Skeleton height={18} width="100%" /><Skeleton height={18} width="94%" /><Skeleton height={18} width="62%" /></div>}</div>}
            {source && (activeField ? <article className="browser-reader-field">
              <p className="browser-reader-parent-title">{source.title || '原始来源'}</p>
              {source.urlQueryOmitted && <p className="browser-reader-note">地址中的查询参数和片段已省略，正文与表头中的同一精确地址也以安全地址显示。</p>}
              <div className="browser-reader-field-text">{activeField.text || '（原字段为空）'}</div>
              {activeField.headers.length > 0 && <section className="browser-reader-context"><h2>同时保存的表头</h2><p>{activeField.headers.join(' · ')}</p></section>}
              <p className="browser-reader-note">{activeField.partial ? '来自当时保存的内容片段，未自动补读网页。' : '来自当时交付模型并保存的范围，不代表全页或已核实。'}</p>
              <Button variant="secondary" className="browser-reader-control" leadingIcon={RiArrowLeftLine} onClick={backToSource}>返回原文位置</Button>
            </article> : <article className="browser-reader-content" aria-label="保存的原文">
              {source.projection?.mode === 'list' ? <ol className="browser-reader-list">{source.projection.rows.map(row => <li key={row.index} value={row.index + 1}>
                <p>{row.text || '（原项为空）'}</p><Button variant="ghost" className="browser-reader-control browser-reader-field-link" trailingIcon={RiArrowRightSLine} data-reader-key={`item-${row.index}`} aria-label={`查看第 ${row.index + 1} 项的原字段`} onClick={() => row.fieldRef && void readField(row.fieldRef, `item-${row.index}`)} disabled={busy}>查看这一项</Button>
              </li>)}</ol> : source.projection?.mode === 'table' ? <div className="browser-reader-table-scroll" ref={table} onScroll={rememberPosition} role="region" aria-label="当时保存的表格，可横向滚动" tabIndex={0}><table><caption>按原行列顺序保存的字段</caption><tbody>{source.projection.rows.map(row => <tr key={row.index}>{row.cells?.map(cell => {
                const Cell = cell.role === 'columnheader' || cell.role === 'rowheader' ? 'th' : 'td';
                return <Cell key={cell.column} scope={cell.role === 'columnheader' ? 'col' : cell.role === 'rowheader' ? 'row' : undefined} colSpan={cell.colSpan} rowSpan={cell.rowSpan}>
                  <p>{cell.text || '（空）'}</p><Button variant="ghost" className="browser-reader-control browser-reader-field-link" data-reader-key={`row-${row.index}-column-${cell.column}`} aria-label={`查看第 ${row.index + 1} 行第 ${cell.column + 1} 列原字段`} onClick={() => void readField(cell.fieldRef, `row-${row.index}-column-${cell.column}`)} disabled={busy}>字段依据<RiArrowRightSLine aria-hidden /></Button>
                </Cell>;
              })}</tr>)}</tbody></table></div> : <div className="browser-reader-prose">{source.excerpt || '这份来源没有保存正文摘录。'}</div>}
              <footer className="browser-reader-source-note"><Accordion variant="divided"><AccordionItem id="capture-scope" title="这次保存的范围与出处" description="查看采集范围与安全地址"><p>{readerCoverage(source)}</p>{!!source.projection?.omittedRows && <p>已交付的行中另有 {source.projection.omittedRows} 行未保留在这份投影内。</p>}<p className="browser-reader-url">{source.safeUrl}</p>{source.urlQueryOmitted && <p>地址中的查询参数和片段已省略，正文与表头中的同一精确地址也以安全地址显示。</p>}<p>{source.projection && !source.projection.partial ? '另看现网页和制作只读配方仍在原结果面板中。' : '另看现网页仍在原结果面板中。'}</p></AccordionItem></Accordion></footer>
            </article>)}
          </div>
        </div>
      </Drawer.Content>
    </Drawer.Portal>
  </Drawer.Root>;
}
