import { useEffect, useRef, useState } from 'react';
import { IconArrowLeft, IconCopy, IconRefresh } from './icons';
import { Button, TextAreaField } from './forms-library';
import { Tabs, TabList, Tab, TabPanel, InlineDiff } from './shell-library';
import { ProgressBar, Skeleton, ShimmerText } from './library';
import type { FileLayer } from './project-file';
import './project-workspace.css';
type Data = Record<string, any>;
type Props = { path: string; version: number; draft?: { text: string; baseline: string; version: number; dirty: boolean }; state: Data; conflict: boolean; mode: FileLayer; setMode: (value: FileLayer) => void; can: (action: string) => boolean; edit: (value: string) => void; save: () => void; readHistory: () => void; readRevision: (revision: number) => void; retry: () => void; copy: () => void; adoption: { pending: boolean; unknown: boolean; message: string; adopt: () => void }; registerBack: (handler: (() => boolean) | null) => void };
export function ProjectFileEditor({ path, version, draft, state, conflict, mode, setMode, can, edit, save, readHistory, readRevision, retry, copy, adoption, registerBack }: Props) {
  const [selected, setSelected] = useState<number | null>(null), [reviewing, setReviewing] = useState(false);
  const historyScroll = useRef<HTMLDivElement>(null), historyTop = useRef(0), lastRevision = useRef<number | null>(null);
  const back = () => { if (adoption.pending || state.fileSaving) return true; if (reviewing) { setReviewing(false); return true; } if (mode === 'history' && selected !== null) { setSelected(null); return true; } if (mode !== 'edit') { setMode('edit'); return true; } return false; };
  useEffect(() => { registerBack(back); return () => registerBack(null); }, [mode, selected, reviewing, adoption.pending, state.fileSaving, registerBack]);
  useEffect(() => {
    if (mode !== 'history' || selected !== null) return;
    const frame = requestAnimationFrame(() => { if (historyScroll.current) { historyScroll.current.scrollTop = historyTop.current; historyScroll.current.querySelector<HTMLButtonElement>(`[data-revision="${lastRevision.current}"]`)?.focus({ preventScroll: true }); } });
    return () => cancelAnimationFrame(frame);
  }, [mode, selected]);
  const history = Array.isArray(state.fileHistory) ? state.fileHistory : [];
  const selectedReady = selected !== null && state.revisionNumber === selected && typeof state.revisionContent === 'string' && !state.revisionLoading;
  const locked = !!state.fileSaving || adoption.pending;
  const switchMode = (value: FileLayer) => { if (locked) return; setReviewing(false); setMode(value); if (value === 'history') readHistory(); };
  return <section className="pw-file-editor pw-file-workspace pw-project-file-editor" aria-label="文件工作区" aria-busy={!!state.fileLoading || locked}>
    <header className="pw-file-heading"><div><h2 className="pw-file-name">{path.split('/').at(-1)}</h2><p className="pw-file-path">{path.includes('/')?path.slice(0,path.lastIndexOf('/')):'项目根目录'}</p><p>版本 {draft?.version ?? version}<span className={draft?.dirty ? 'pw-file-dirty' : ''}>{draft?.dirty ? ' · 未保存' : ' · 当前文件'}</span></p></div>{draft?.dirty && <span className="pw-file-state-dot" aria-hidden="true"/>}</header>
    {state.fileLoading ? <div className="pw-loading-file" aria-busy="true"><ShimmerText>正在读取文件</ShimmerText><Skeleton width="85%" height={13}/><Skeleton width="65%" height={13}/><Skeleton width="94%" height={13}/></div> : state.fileReady && draft ? <>
      {state.fileSaving && <ProgressBar label="正在保存当前文件" isIndeterminate showValueLabel={false} size="sm"/>}
      {conflict && <div className="pw-file-notice" role="alert"><p>原文件版本已变化。当前草稿仍保留，请比较后再决定。</p><Button variant="ghost" leadingIcon={IconCopy} onClick={copy}>复制保留的草稿</Button></div>}
      {state.fileError && <p className="pw-error" role="alert">{state.fileError}</p>}
      {adoption.message && <p className={adoption.unknown ? 'pw-error' : 'pw-muted'} role={adoption.unknown ? 'alert' : 'status'}>{adoption.message}</p>}
      <Tabs selectedKey={mode} onSelectionChange={key => switchMode(key as FileLayer)} className="pw-file-tabs">
        <TabList aria-label="文件工作方式"><Tab id="edit" isDisabled={locked}>编辑</Tab><Tab id="diff" isDisabled={locked}>比较修改</Tab><Tab id="history" isDisabled={locked || !can('projects.history')}>版本记录</Tab></TabList>
        <TabPanel id="edit"><TextAreaField label="文件内容" value={draft.text} onChange={edit} isReadOnly={locked} className="pw-code-editor"/></TabPanel>
        <TabPanel id="diff"><InlineDiff before={draft.baseline} after={draft.text} versionLabel={`原版本 ${draft.version}`} onCancel={() => setMode('edit')} pending={locked}/></TabPanel>
        <TabPanel id="history" className="pw-file-history-panel">
          {selected === null ? <><div className="pw-file-subheading"><p>已保存的版本</p><Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={locked} onClick={readHistory}>重新读取</Button></div><div ref={historyScroll} className="pw-file-version-list" onScroll={event => { historyTop.current = event.currentTarget.scrollTop; }}>{history.map((item: Data) => <Button key={item.version} data-revision={item.version} variant="ghost" className="pw-file-version-row" disabled={locked || !can('projects.revision')} onClick={() => { lastRevision.current = item.version; setSelected(item.version); readRevision(item.version); }}><span className="pw-version-number">版本 {item.version}</span><small>{typeof item.at === 'number' && item.at > 0 ? new Date(item.at).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '保存时间未提供'}</small></Button>)}</div>{!history.length && <p className="pw-empty-small">尚未读到这个文件的版本记录。可重新读取原文件历史。</p>}</> : <section className="pw-file-revision">
            <div className="pw-file-subheading"><Button variant="ghost" size="small" leadingIcon={IconArrowLeft} disabled={adoption.pending} onClick={() => { setReviewing(false); setSelected(null); }}>版本记录</Button><span>版本 {selected}</span></div>
            {state.revisionLoading ? <ProgressBar label="正在读取所选原版本" isIndeterminate showValueLabel={false} size="sm"/> : selectedReady ? <><pre className="pw-file-revision-content">{state.revisionContent || '（空文件）'}</pre>{reviewing ? <div className="pw-file-adoption-review"><h3>替换当前编辑草稿？</h3><p>{draft.dirty ? '当前有未保存的修改。采用后会被所选历史内容替换，可先复制保留。' : '所选历史内容将放入当前编辑草稿。'}保存后才会生成新版本。</p>{draft.dirty && <Button variant="ghost" leadingIcon={IconCopy} onClick={copy}>复制当前草稿</Button>}<div className="pw-action-row"><Button variant="ghost" disabled={adoption.pending} onClick={() => setReviewing(false)}>取消</Button><Button variant="primary" disabled={locked || conflict || adoption.unknown || !can('projects.useRevision')} onClick={adoption.adopt}>{adoption.pending ? '正在核对原内容…' : '采用到编辑草稿'}</Button></div></div> : <Button variant="secondary" disabled={locked || conflict || adoption.unknown || !can('projects.useRevision')} onClick={() => setReviewing(true)}>替换编辑草稿…</Button>}</> : <p className="pw-muted">尚未读到所选版本的内容。返回版本记录后可重新选择。</p>}
          </section>}
        </TabPanel>
      </Tabs>
      <footer className="pw-file-save-bar"><span>{adoption.pending ? '正在核对原版本' : draft.dirty ? '草稿未保存到文件' : '没有未保存的修改'}</span>{can('projects.save') && <Button variant="primary" disabled={!draft.dirty || conflict || locked} onClick={save}>{state.fileSaving ? '正在保存…' : '保存修改'}</Button>}</footer>
    </> : <div className="pw-file-read-error"><p className={state.fileError ? 'pw-error' : 'pw-muted'} role={state.fileError ? 'alert' : 'status'}>{state.fileError || '正在读取原文件状态。'}</p>{state.fileError && can('projects.retry') && <Button variant="secondary" leadingIcon={IconRefresh} onClick={retry}>重新读取原文件</Button>}</div>}
  </section>;
}
