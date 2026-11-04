import { useEffect, useRef, useState } from 'react';
import { RiCloseLine } from '@remixicon/react';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import { Button, CheckboxField, IconButton } from './forms-library';
import { ShimmerText } from './library';

type Data = Record<string, any>;
export type ConversationDraftTarget = { taskId: string; runId: string; epoch: number; operationId: string; proposalDigest: string };
type FileSelection = { side: 'before' | 'after'; path: string };

/** Uses the approved source drawer and native SkillBook review; no browser import authority. */
export function SkillDraftReview({ target, onClose }: { target: ConversationDraftTarget; onClose: () => void }) {
  const [value, setValue] = useState<Data | null>(null), [page, setPage] = useState<Data | null>(null), [selected, setSelected] = useState<FileSelection | null>(null);
  const [source, setSource] = useState<Data | null>(null);
  const [error, setError] = useState(''), [busy, setBusy] = useState(true), [allRead, setAllRead] = useState(false), [acknowledged, setAcknowledged] = useState(false), [attempted, setAttempted] = useState(false);
  const [previous, setPrevious] = useState<number[]>([]), [provided, setProvided] = useState<Set<string>>(new Set());
  const handle = useRef(''), alive = useRef(true), entered = useRef(false);
  const original = { taskId: target.taskId, runId: target.runId, epoch: target.epoch, operationId: target.operationId, proposalDigest: target.proposalDigest };
  const applyResult = (result: Data) => {
    setValue(result); handle.current = result.handle || ''; setAllRead(result.review?.allFilePagesProvided === true);
  };
  useEffect(() => {
    alive.current = true;
    void bridge.command('skillDraft.open', original).then(result => {
      if (alive.current) applyResult(result);
      else if (result?.handle) void bridge.command('skillDraft.close', { handle: result.handle }).catch(() => {});
    }).catch(failure => { if (alive.current) setError(failure.message); }).finally(() => { if (alive.current) setBusy(false); });
    return () => { alive.current = false; const id = handle.current; handle.current = ''; if (id) void bridge.command('skillDraft.close', { handle: id }).catch(() => {}); };
  }, []);
  const request = async (name: string, args: Data, accept: (result: Data) => void) => {
    if (entered.current || busy) return; entered.current = true; setBusy(true); setError('');
    try { const result = await bridge.command(name, args); if (alive.current) accept(result); }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const read = (file: FileSelection, offset = 0, back = false) => {
    if (!handle.current || busy) return;
    const same = selected?.side === file.side && selected?.path === file.path;
    void request('skillDraft.read', { handle: handle.current, ...file, offset }, result => {
      setSelected(file); setPage(result); setAllRead(result.allFilePagesProvided === true); setAcknowledged(false);
      setPrevious(old => !same || offset === 0 ? [] : back ? old.slice(0, -1) : [...old, Number(page?.offset || 0)]);
      if (!result.hasMore) setProvided(old => new Set([...old, file.side + ':' + file.path]));
    });
  };
  const close = () => { if (!busy) onClose(); };
  const review = value?.review, proposal = value?.proposal || review?.proposal || {}, apply = value?.apply || (value?.status ? value : {});
  const pending = apply.status === 'pending_owner' && !!review && !!handle.current && !attempted;
  const status: Record<string, string> = { pending_owner: '待你审阅', saved: '已保存为独立技能', cancelled: '已放弃原提案', rejected: '本次未保存', unknown: '保存结果待核对', applying_unknown: '原保存结果待核对' };
  const files = (side: 'before' | 'after'): Data[] => Array.isArray(review?.[side + 'Files']) ? review[side + 'Files'] : [];
  return <Drawer.Root open onOpenChange={open => { if (!open) close(); }} shouldScaleBackground={false}><Drawer.Portal>
    <Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer" aria-describedby="skill-draft-description">
      <div className="pw-drawer-handle" /><header className="pw-drawer-header"><div><Drawer.Title>{proposal.name || '原对话技能提案'}</Drawer.Title><Drawer.Description id="skill-draft-description">核对原文件与候选文件后保存；保存后的技能保持停用，启用另行决定。</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="返回原回答" disabled={busy} onClick={close} /></header>
      <div className="pw-drawer-body">
        {busy && <ShimmerText>正在核对原提案…</ShimmerText>}{error && <p className="pw-error" role="alert">{error}</p>}
        {apply.status && <p role="status">{status[apply.status] || '原提案状态待核对'}</p>}{apply.message && <p>{apply.message}</p>}
        {proposal.reason && <section className="pw-source-record"><h3>提案理由</h3><p>{proposal.reason}</p></section>}
        {review && <>
          <p className="pw-muted">{proposal.action === 'update' ? '更新已读取的这个技能版本。原文件、启停或来源改变时，需要重新提案。' : '保存为新的独立本机技能。'} 源消息后续被编辑或删除，不会自动撤销已保存的独立技能。</p>
          <details><summary>提案来源与保存范围</summary><p>提案正文由助手整理；来源限于这次原执行已准备并核对的主人消息，不代表整个会话。</p><p>原始来源条数：{Array.isArray(review.sourceRefs) ? review.sourceRefs.length : 0}。{review.skillScope?.projectId ? '仅在原项目范围。' : '私人主人范围。'}</p><p>保存不授予能力权限。所需权限保持原技能策略；后续执行仍单独核对。</p></details>
          <section className="pw-source-record"><h3>原提案消息</h3><div className="pw-action-row">{(Array.isArray(review.sourceRefs) ? review.sourceRefs : []).map((ref: Data, index: number) => <Button key={ref.sourceId + ':' + ref.revision} variant="ghost" disabled={busy || attempted || !handle.current} onClick={() => { void request('skillDraft.source', { handle: handle.current, index, offset: 0 }, setSource); }}>来源 {index + 1} · 修订 {ref.revision}</Button>)}</div>{source && <><h4>{source.title}</h4><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</pre><p className="pw-muted">原文 {source.offset}–{source.nextOffset} / {source.totalChars} 字符</p>{source.hasMore && <Button variant="ghost" disabled={busy || attempted} onClick={() => { void request('skillDraft.source', { handle: handle.current, index: source.index, offset: source.nextOffset }, setSource); }}>继续读取原文</Button>}</>}</section>
          {(['before', 'after'] as const).map(side => <section className="pw-source-record" key={side}><h3>{side === 'before' ? '原文件' : '候选文件'}</h3>{!files(side).length ? <p className="pw-muted">此侧没有文件。</p> : <div className="pw-action-row">{files(side).map(file => <Button key={file.path} variant="ghost" disabled={busy || attempted || !handle.current} onClick={() => read({ side, path: file.path })}>{file.path} · {file.chars} 字符{provided.has(side + ':' + file.path) ? ' · 已提供到末页' : ''}</Button>)}</div>}</section>)}
          {page && selected && <section className="pw-source-record"><h3>{selected.side === 'before' ? '原文件' : '候选文件'} · {selected.path}</h3><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{page.content || '（空文件）'}</pre><p className="pw-muted">原文件字符 {page.offset}–{page.nextOffset} / {page.totalChars}</p><div className="pw-action-row">{previous.length > 0 && <Button variant="ghost" disabled={busy || attempted} onClick={() => read(selected, previous.at(-1)!, true)}>上一段</Button>}{page.hasMore && <Button variant="secondary" disabled={busy || attempted} onClick={() => read(selected, page.nextOffset)}>下一段</Button>}</div></section>}
          {pending && <><p className="pw-muted">{allRead ? '前后全部文件已按页提供；请核对内容后作出决定。' : '请逐份展开前后文件并读到末页，之后才能保存。'}</p><CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已核对全部文件，理解这是独立保存并保持停用的技能</CheckboxField><div className="pw-action-row"><Button disabled={busy || !allRead || !acknowledged} onClick={() => { const id = handle.current; setAttempted(true); void request('skillDraft.confirm', { handle: id }, result => { handle.current = ''; setValue({ proposal, apply: result }); }); }}>保存这份技能，保持停用</Button><Button variant="secondary" disabled={busy} onClick={() => { const id = handle.current; setAttempted(true); void request('skillDraft.cancel', { handle: id }, result => { handle.current = ''; setValue(result); }); }}>放弃这个原提案</Button></div></>}
        </>}
        {(!pending || attempted || error) && <Button variant="ghost" disabled={busy} onClick={() => { void request('skillDraft.query', original, result => { handle.current = ''; setValue(result); setSelected(null); setPage(null); }); }}>查询原保存结果</Button>}
      </div>
    </Drawer.Content>
  </Drawer.Portal></Drawer.Root>;
}
