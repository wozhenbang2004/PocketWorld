import { useEffect, useRef, useState } from 'react';
import './ecosystem-depth.css';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog } from './forms-library';
import { packConfirmInput, packConsentKey, packIdentity, packReceipt, packReview, packRows, packSelectionMatches, type PackData as Data } from './functional-packs';

type Props = { state: Data; run: (action: string, args?: Data) => Promise<any>; supported: (action: string) => boolean;
  registerTransientBack: (handler: (() => boolean) | null) => void; onNavigate?: (domain: 'plugins' | 'skills') => void };
const json = (value: unknown) => JSON.stringify(value, null, 2);
const status = (value: string) => ({ installed: '组件已安装', partial: '部分完成', unknown: '回执待核对', installing: '正在安装', removing: '正在卸载', removed: '原组件已处理' } as Record<string, string>)[value] || value;
const actionLabel = (value: string) => ({ install: '安装并保持停用', remove: '移除原安装', retain_changed: '保留手工更新的安装', already_absent: '原安装已不存在', blocked: '当前受阻' } as Record<string, string>)[value] || value;
function Detail({ title, value }: { title: string; value: unknown }) { return <Accordion><AccordionItem title={title}><pre className="pw-plugin-definition">{typeof value === 'string' ? value : json(value)}</pre></AccordionItem></Accordion>; }

export function FunctionalPacksWorkspace({ state, run, supported, registerTransientBack, onNavigate }: Props) {
  const [view, setView] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [consent, setConsent] = useState('');
  const [journal, setJournal] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const alive = useRef(false), sequence = useRef(0), entered = useRef(false), page = useRef(''), active = useRef<Data | null>(null), acknowledgement = useRef('');
  const resume = useRef(state.webWorkspaceResumeRevision);
  const dropReview = () => {
    const old = active.current; active.current = null; acknowledgement.current = ''; setReview(null); setConsent('');
    if (old) void run('functionalPacks.closeReview', { pageId: old.pageId, reviewHandle: old.reviewHandle }).catch(() => {});
  };
  const receive = (value: Data, fresh = false) => {
    if (!value?.pageId || !Array.isArray(value.snapshot?.packs) || !fresh && value.pageId !== page.current) throw new Error('原功能包页面已变化，请重新读取。');
    page.current = value.pageId; setView(value);
  };
  const receiveReview = (value: Data) => {
    const exact = packReview(value, page.current); active.current = exact; acknowledgement.current = ''; setReview(exact); setConsent('');
  };
  const invoke = async (action: string, args: Data, accept: (value: Data) => void) => {
    if (entered.current) return;
    entered.current = true; const ticket = ++sequence.current; setBusy(true); setError('');
    try {
      if (!supported(action)) throw new Error('当前宿主尚未接通这项功能包操作。');
      const value = await run(action, args);
      if (alive.current && ticket === sequence.current) accept(value);
      else if (value?.reviewHandle) void run('functionalPacks.closeReview', { pageId: value.pageId, reviewHandle: value.reviewHandle }).catch(() => {});
    } catch (failure) { if (alive.current && ticket === sequence.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (ticket === sequence.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const open = () => {
    dropReview(); setJournal(null); sequence.current++; entered.current = false; page.current = ''; setView(null);
    if (document.visibilityState === 'visible') void invoke('functionalPacks.open', {}, value => receive(value, true));
  };
  useEffect(() => {
    alive.current = true; open();
    return () => {
      alive.current = false; sequence.current++; entered.current = false; const old = page.current; page.current = ''; active.current = null; acknowledgement.current = '';
      if (old) void run('functionalPacks.close', { pageId: old }).catch(() => {});
    };
  }, []);
  useEffect(() => { if (resume.current !== state.webWorkspaceResumeRevision) { resume.current = state.webWorkspaceResumeRevision; open(); } }, [state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const visibility = () => {
      if (document.visibilityState === 'visible') open();
      else { sequence.current++; entered.current = false; setBusy(false); dropReview(); setJournal(null); }
    };
    document.addEventListener('visibilitychange', visibility); return () => document.removeEventListener('visibilitychange', visibility);
  }, []);
  useEffect(() => {
    registerTransientBack(() => { if (busy) return true; if (review) { dropReview(); return true; } if (journal) { setJournal(null); return true; } return false; });
    return () => registerTransientBack(null);
  }, [busy, review, journal, registerTransientBack]);

  const choose = () => { dropReview(); setReceipt(null); void invoke('functionalPacks.chooseFile', { pageId: page.current }, value => { setView(old => old && ({ ...old, document: value.document })); setNotice('返回后可核对刚选定文件的准确组件。'); }); };
  const refresh = () => { dropReview(); void invoke('functionalPacks.snapshot', { pageId: page.current }, receive); };
  const select = (key: string, selected: boolean) => {
    const exact = active.current; if (!exact || entered.current) return;
    const next = exact.selectedKeys.filter((item: string) => item !== key); if (selected) next.push(key);
    // Clear live consent before the asynchronous native selection reply can arrive.
    acknowledgement.current = ''; setConsent(''); active.current = { ...exact, selectedKeys: next, selectionMatches: false }; setReview(active.current);
    void invoke('functionalPacks.select', { pageId: exact.pageId, reviewHandle: exact.reviewHandle, selectionRevision: exact.selectionRevision, selectedKeys: next }, receiveReview);
  };
  const confirm = (displayed: Data) => {
    if (entered.current) return;
    try {
      const input = packConfirmInput(active.current, displayed, acknowledgement.current), originalPage = page.current;
      active.current = null; acknowledgement.current = ''; setReview(null); setConsent('');
      void invoke('functionalPacks.confirm', input, value => {
        receive(value); const actual = packReceipt(value, originalPage); setReceipt(actual); setNotice(actual.message || '已读取本次原组件回执。');
      });
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const task = (id: string) => { void run('tasks.open', { id }).catch(failure => setError(failure instanceof Error ? failure.message : String(failure))); };
  const stop = () => { dropReview(); void run('functionalPacks.cancel', { pageId: page.current }).then(() => setNotice('已请求停止后续组件；已完成的组件与原任务记录保留。请等待原回执后重新读取。')).catch(failure => setError(String(failure))); };
  const selectedMatches = !!review && review.selectionMatches === true && packSelectionMatches(review), actionable = !!review && review.data.ready === true && selectedMatches;
  const doc = view?.document || {}, rows = packRows(view?.snapshot?.packs);
  return <div className="pw-functional-packs pw-ecosystem-depth">
    <header className="pw-eco-workspace-heading"><div><h3>完整功能包</h3><p>一起核对相关组件，安装后仍分别配置和启用。</p></div></header>
    {error && <p className="pw-field-error" role="alert">{error}</p>}{notice && <p className="pw-field-hint" role="status">{notice}</p>}
    <div className="pw-form-actions"><Button disabled={busy || !view || doc.status === 'pending'} onClick={choose}>选择准确功能包</Button><Button variant="secondary" disabled={busy} onClick={view ? refresh : open}>读取当前安装</Button>{busy && <Button variant="ghost" onClick={stop}>停止后续组件</Button>}</div>
    {busy && <p className="pw-field-hint" role="status">正在读取原包或处理原组件…</p>}
    {doc.status === 'selected' && <div className="pw-selected-object"><h4>文件已选择</h4><p>读取该文件并核对摘要、组件内容与依赖；这一步不会安装或运行。</p><div className="pw-form-actions"><Button disabled={busy} onClick={() => { dropReview(); void invoke('functionalPacks.inspect', { pageId: page.current, requestId: doc.requestId }, receiveReview); }}>核对所选文件</Button><Button variant="ghost" disabled={busy} onClick={() => { void invoke('functionalPacks.discardFile', { pageId: page.current, requestId: doc.requestId }, receive); }}>放下这次文件</Button></div></div>}
    {doc.status === 'pending' && <p className="pw-field-hint">文件选择尚未结束，请返回系统文件选择器。</p>}{doc.status === 'cancelled' && <p className="pw-field-hint">已取消这次文件选择。</p>}{doc.status === 'unavailable' && <p className="pw-field-error">无法读取这次选择，请重新选择本机文档。</p>}
    {receipt && <div className="pw-selected-object"><h4>本次原操作 · {receipt.status === 'completed' ? '已收到组件回执' : receipt.status === 'partial' ? '部分完成' : '仍待核对'}</h4><p>{receipt.message}</p><Detail title="逐组件实际回执" value={receipt} />{receipt.taskId && <Button variant="secondary" disabled={!supported('tasks.open')} onClick={() => task(receipt.taskId)}>打开原任务</Button>}</div>}
    {!rows.length && !busy && <p className="pw-secondary-empty">还没有功能包安装记录。</p>}
    <div className="pw-eco-record-list" aria-label="功能包安装记录">{rows.map(row => <article className="pw-source-record pw-eco-record" key={row.installationId}><header><h3>{row.name || row.id}</h3><span>{row.version} · {status(row.state)}</span></header><p className="pw-eco-record-meta">{packRows(row.components).length} 项已确认组件{packRows(row.excludedComponents).length ? ` · ${packRows(row.excludedComponents).length} 项未选择或受阻` : ''}</p>
      <Detail title={`已确认组件 · ${packRows(row.components).length} 项`} value={row.components} />{packRows(row.excludedComponents).length > 0 && <Detail title="未选择或受阻的组件" value={row.excludedComponents} />}
      <div className="pw-form-actions"><Button disabled={busy} onClick={() => { dropReview(); void invoke('functionalPacks.reconcile', { pageId: page.current, ...packIdentity(row) }, value => { receive(value); setNotice(value.receipt?.reconciled ? '已核对原操作回执，没有重复提交。' : '原操作仍有未确认回执，请查看原任务。'); }); }}>核对原操作</Button><Button variant="ghost" disabled={busy} onClick={() => { void invoke('functionalPacks.journal', { pageId: page.current, ...packIdentity(row) }, value => { receive(value); setJournal(value.journal); }); }}>查看原组件记录</Button>{row.taskId && <Button variant="ghost" disabled={busy || !supported('tasks.open')} onClick={() => task(row.taskId)}>打开原任务</Button>}{['installed', 'partial'].includes(row.state) && <Button variant="ghost" disabled={busy} onClick={() => { dropReview(); void invoke('functionalPacks.reviewRemove', { pageId: page.current, ...packIdentity(row) }, receiveReview); }}>核对卸载与保留项</Button>}</div>
      {packRows(row.history).length > 0 && <Accordion><AccordionItem title="保留的原包版本">{packRows(row.history).map(version => <div className="pw-selected-object" key={version.installationId}><h4>{version.version}</h4><p className="pw-field-hint">{version.installationId}</p><Button variant="secondary" disabled={busy || !['installed', 'partial', 'removed'].includes(row.state)} onClick={() => { dropReview(); void invoke('functionalPacks.reviewRestore', { pageId: page.current, ...packIdentity(row), targetInstallationId: version.installationId }, receiveReview); }}>核对这个历史原包</Button></div>)}</AccordionItem></Accordion>}
    </article>)}</div><Accordion variant="divided"><AccordionItem id="pack-behavior" title="组件安装与权限说明"><p className="pw-field-hint">每个组件保留自己的安装与权限。组件按依赖逐项处理，可能部分完成；不会自动运行配方、恢复授权或自动回滚。</p></AccordionItem></Accordion>
    {onNavigate && <div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={() => onNavigate('plugins')}>前往插件管理</Button><Button variant="ghost" disabled={busy} onClick={() => onNavigate('skills')}>前往技能管理</Button></div>}
    <SettingsDialog isOpen={!!review} title={review?.data.removing ? '核对卸载与保留项' : review?.data.restoring ? '核对历史原包' : '核对准确组件'} onClose={() => { if (!busy) dropReview(); }}>
      {review && <div className="pw-plugins-workspace pw-plugin-dialog-body pw-ecosystem-depth pw-eco-review-body"><h3>{review.data.package?.name || review.data.current?.name || review.data.id}</h3>
        <p className="pw-muted">{review.data.removing ? '只处理此功能包原先确认的组件。手工更新或替换的安装会保留，已发生的效果与原任务回执也保留。' : '组件按依赖逐项处理，可能部分完成。不会自动运行配方、恢复授权或自动回滚。源码和声明仅通过当前读取核对。'}</p>
        {review.data.package && <Detail title="版本、来源、许可与整包摘要" value={review.data.package} />}
        {['issues', 'globalIssues', 'selectionProblems'].map(field => (review.data[field] || []).map((message: string, at: number) => <p key={`${field}:${at}`} className="pw-field-error">{message}</p>))}
        {packRows(review.data.components).map(component => <article className="pw-source-record pw-eco-component" key={component.key}><header><h4>{component.key}</h4><span>{actionLabel(component.action)}</span></header><p className="pw-field-hint">{component.kind}{component.component?.requires?.length ? ` · 需要 ${component.component.requires.join('、')}` : ''}</p>
          {!review.data.removing && <CheckboxField isSelected={review.selectedKeys.includes(component.key)} onChange={checked => select(component.key, checked)} isDisabled={busy || component.selectable !== true}>选择这个准确组件</CheckboxField>}
          {component.inspection?.source && <><h4>完整源码</h4><pre className="pw-plugin-source" tabIndex={0} aria-label={`${component.key} 的完整源码`}>{component.inspection.source}</pre></>}
          <Detail title="完整声明、检查内容与当前安装" value={component.inspectionText || component.inspection} />{component.component && <Detail title="字节摘要与所需能力" value={component.component} />}{component.current && Object.keys(component.current).length > 0 && <Detail title="准确当前安装" value={component.current} />}
        </article>)}
        {!review.data.removing && <><p className="pw-field-hint">{selectedMatches ? review.data.wholePackageSelected ? '已审阅本包全部可用组件。' : '只选择了部分组件；整包与配方仍不能据此视为可用。' : '组件选择已经改变，需要重新核对当前选择与依赖。'}</p><Button variant="secondary" disabled={busy || !review.selectedKeys.length} onClick={() => { const exact = active.current; if (!exact) return; acknowledgement.current = ''; setConsent(''); void invoke('functionalPacks.reviewSelection', { pageId: exact.pageId, reviewHandle: exact.reviewHandle, selectionRevision: exact.selectionRevision }, receiveReview); }}>重新核对所选组件与依赖</Button></>}
        <CheckboxField isSelected={consent === packConsentKey(review)} onChange={checked => { const exact = active.current; const key = checked && actionable && exact && packSelectionMatches(exact) ? packConsentKey(exact) : ''; acknowledgement.current = key; setConsent(key); }} isDisabled={busy || !actionable}>我已核对当前组件、源码、依赖与本次实际变化</CheckboxField>
        <div className="pw-form-actions pw-eco-review-confirm"><Button disabled={busy || !actionable || consent !== packConsentKey(review)} onClick={() => confirm(review)}>{review.data.removing ? '确认处理原包组件' : review.data.wholePackageSelected ? '安装组件并保持停用' : '只安装已审阅组件'}</Button><Button variant="ghost" disabled={busy} onClick={dropReview}>返回</Button></div>
      </div>}
    </SettingsDialog>
    <SettingsDialog isOpen={!!journal} title="原任务的组件记录" onClose={() => setJournal(null)}>{journal && <div className="pw-plugins-workspace pw-plugin-dialog-body pw-ecosystem-depth pw-eco-review-body"><p>{journal.status} · {journal.summary || journal.goal}</p>{packRows(journal.operations).map(operation => <article className="pw-source-record" key={operation.id}><h3>{operation.arguments?.componentKey || operation.tool}</h3><p>{operation.state === 'receipt' ? '已记录原回执' : '尚未取得确定回执'}</p><Detail title="实际请求与原回执" value={operation} /></article>)}<Button variant="secondary" disabled={!supported('tasks.open')} onClick={() => task(journal.id)}>打开这次原任务</Button></div>}</SettingsDialog>
  </div>;
}
