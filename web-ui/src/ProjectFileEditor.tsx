import { ProjectKeptFileDrafts } from './ProjectKeptDrafts';
import {FormalHistoryComparison} from './FormalHistoryComparison';
import { FileObservation, ProjectFormalReadOnly, RetainedFormalDraft } from './ProjectFormalReadOnly';
import { ProjectFormalFileDelivery } from './ProjectFormalFileDelivery';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { IconArrowLeft, IconCopy, IconRefresh } from './icons';
import { Button, TextAreaField } from './forms-library';
import { Tabs, TabList, Tab, TabPanel, InlineDiff } from './shell-library';
import { ProgressBar, Skeleton, ShimmerText } from './library';
import type { FileLayer } from './project-file';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionDisclosure, MotionFeedback, useCollectionMotion } from './library/journey-motion';
import './project-workspace.css';
import './project-workspace-manga.css';
type Data = Record<string, any>;
type Props = { path: string; version: number; draft?: { text: string; baseline: string; version: number; dirty: boolean }; state: Data; conflict: boolean; mode: FileLayer; setMode: (value: FileLayer) => void; can: (action: string) => boolean; edit: (value: string) => void; save: () => void; readHistory: () => void; readRevision: (revision: number) => void; retry: () => void; copy: () => void; copyRevision: (text: string) => void; adoption: { pending: boolean; unknown: boolean; message: string; adopt: () => void }; registerBack: (handler: (() => boolean) | null) => void };

function observedEarlierText(state: Data, path: string, head: number, selected: number) {
  const source = state.revisionObservation, text = state.revisionContent;
  if (!Number.isSafeInteger(selected) || selected < 1 || selected >= head || state.fileReady !== true ||
      state.fileLoading !== false || state.fileSaving !== false || state.fileVersion !== head || state.filePath !== path ||
      state.revisionLoading !== false || state.revisionNumber !== selected || !source ||
      typeof state.projectId !== 'string' || !state.projectId || source.projectId !== state.projectId ||
      source.path !== path || source.version !== selected || source.kind !== 'text' || source.representation !== 'text' ||
      typeof source.revisionId !== 'string' || !/^[a-f0-9-]{36}$/.test(source.revisionId) ||
      typeof source.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(source.sha256) ||
      !Number.isSafeInteger(source.bytes) || source.bytes <= 0 || source.bytes > 262144 ||
      typeof text !== 'string' || !text.length || text.length > 65536) return;
  return { project: state.projectId, revision: source.revisionId, sha: source.sha256, bytes: source.bytes, text };
}

function observedAdoptionText(state: Data, path: string, version: number) {
  const source = state.adoptedFormalComparison;
  // This pin accompanies the already loaded formal bytes, not the old
  // checkout draft, a known latest project row, or authority to edit again.
  return source?.projectVersionCommitted === true && source.evidence === 'import_receipt' &&
    state.fileReady === true && !state.fileLoading && !state.fileSaving && source.projectId === state.projectId &&
    state.filePath === path && source.path === path && source.version === version && state.fileVersion === version &&
    typeof source.checkoutId === 'string' && /^[a-f0-9-]{36}$/.test(source.checkoutId) &&
    typeof source.revisionId === 'string' && /^[a-f0-9-]{36}$/.test(source.revisionId) &&
    typeof source.sha256 === 'string' && /^[a-f0-9]{64}$/.test(source.sha256) &&
    Number.isSafeInteger(source.bytes) && source.bytes >= 0 && source.bytes <= 262144 &&
    typeof state.fileBaseline === 'string' && state.fileContent === state.fileBaseline ? state.fileBaseline as string : undefined;
}

export function ProjectFileEditor({ path, version, draft, state, conflict, mode, setMode, can, edit, save, readHistory, readRevision, retry, copy, copyRevision, adoption, registerBack }: Props) {
  const [selected, setSelected] = useState<number | null>(null), [reviewing, setReviewing] = useState(false);
  const root = useRef<HTMLElement>(null);
  const adoptionTrigger = useRef<HTMLButtonElement>(null), adoptionReview = useRef<HTMLDivElement>(null), adoptionHeading = useRef<HTMLHeadingElement>(null), reviewWasOpen = useRef(false);
  const historyScroll = useRef<HTMLDivElement>(null), historyTop = useRef(0), lastRevision = useRef<number | null>(null);
  const back = () => { if (adoption.pending || state.fileSaving) return true; if (reviewing) { setReviewing(false); return true; } if (mode === 'history' && selected !== null) { setSelected(null); return true; } if (mode !== 'edit') { setMode('edit'); return true; } return false; };
  useEffect(() => { registerBack(back); return () => registerBack(null); }, [mode, selected, reviewing, adoption.pending, state.fileSaving, registerBack]);
  useEffect(() => {
    if (mode !== 'history' || selected !== null) return;
    const frame = requestAnimationFrame(() => { if (historyScroll.current) { historyScroll.current.scrollTop = historyTop.current; historyScroll.current.querySelector<HTMLButtonElement>(`[data-revision="${lastRevision.current}"]`)?.focus({ preventScroll: true }); } });
    return () => cancelAnimationFrame(frame);
  }, [mode, selected]);
  const history = Array.isArray(state.fileHistory) ? state.fileHistory : [];
  const selectedReady = selected !== null && state.revisionNumber === selected && typeof state.revisionContent === 'string' && state.revisionObservation?.representation === 'text' && state.revisionObservation?.version === selected && !state.revisionLoading;
  const locked = !!state.fileSaving || adoption.pending;
  const adoptedText = conflict && draft?.dirty === true && draft.text !== draft.baseline && draft.version > 0 && draft.version < version
    ? observedAdoptionText(state, path, version) : undefined;
  const earlierText = mode === 'history' && selected !== null && !reviewing && can('projects.revision') &&
    typeof navigator.clipboard?.writeText === 'function' ? observedEarlierText(state, path, version, selected) : undefined;
  const shownDocument = bridge.getDocumentEpoch(), shownView = bridge.getState().view as Data | undefined;
  const copyEarlier = (button: HTMLButtonElement) => {
    if (!earlierText || selected === null || locked || document.hidden || !button.isConnected ||
        button.closest('[hidden],[inert],[aria-hidden="true"]') || bridge.getDocumentEpoch() !== shownDocument) return;
    const live = bridge.getState(), view = live.view as Data | undefined;
    if (live.host?.ready !== true || view?.section !== null || view?.taskId !== null ||
        !Number.isSafeInteger(view.resumeRevision) || view.resumeRevision !== shownView?.resumeRevision ||
        !live.capabilities.commands.includes('projects.revision') || live.state.attachmentWorkspace?.open || live.state.conversationReader) return;
    const actual = observedEarlierText(live.state, path, version, selected);
    // Native verifies the complete revision bytes when opening this version. Copy only
    // the same text that is still shown, never the head or its edited draft.
    if (actual?.project === earlierText.project && actual.revision === earlierText.revision && actual.sha === earlierText.sha &&
        actual.bytes === earlierText.bytes && actual.text === earlierText.text) copyRevision(actual.text);
  };
  useLayoutEffect(() => {
    const wasOpen = reviewWasOpen.current; reviewWasOpen.current = reviewing;
    if (wasOpen === reviewing || mode !== 'history' || !selectedReady) return;
    const focused = document.activeElement;
    if (reviewing) adoptionHeading.current?.focus({ preventScroll: true });
    else if (focused === document.body || adoptionReview.current?.contains(focused)) adoptionTrigger.current?.focus({ preventScroll: true });
  }, [reviewing, mode, selectedReady]);
  const surfaceIdentity = JSON.stringify([path, mode, mode === 'history' ? selected : null]);
  useSurfaceEnter(root, surfaceIdentity, { selector: '.pw-file-tabs [role="tabpanel"]:not([hidden])', depth: mode === 'history' && selected !== null ? 1 : 0, active: !!state.fileReady && !!draft });
  useCollectionMotion(root, surfaceIdentity, JSON.stringify(history.map((item: Data) => [item.version, item.at])), { active: !!state.fileReady && !state.fileLoading && Array.isArray(state.fileHistory) && mode === 'history' && selected === null, enter: false });
  const switchMode = (value: FileLayer) => { if (locked) return; setReviewing(false); setMode(value); if (value === 'history') readHistory(); };
  return <section ref={root} className="pw-file-editor pw-file-workspace pw-project-file-editor" aria-label="文件工作区" aria-busy={!!state.fileLoading || locked}>
    <header className="pw-file-heading"><div><h2 className="pw-file-name">{path.split('/').at(-1)}</h2><p className="pw-file-path">{path.includes('/')?path.slice(0,path.lastIndexOf('/')):'项目根目录'}</p><p>{adoptedText !== undefined ? '正式版本 '+version : state.fileReady || version>0 ? '版本 '+(state.fileReady ? draft?.version ?? version : version) : '版本尚未核对'}<span className={draft?.dirty ? 'pw-file-dirty' : ''}>{draft?.dirty ? ' · 有保留的修改' : state.fileReady ? ' · 当前文件' : ' · 只读'}</span></p></div>{draft?.dirty && <span className="pw-file-state-dot" aria-hidden="true"/>}</header>
    {!state.fileLoading && can('projects.keptDrafts.forFile') && <ProjectKeptFileDrafts state={state} draft={draft} edit={edit} disabled={!!state.fileSaving || adoption.pending}/>}
    {state.fileLoading ? <div className="pw-loading-file" aria-busy="true"><ShimmerText>正在读取文件</ShimmerText><Skeleton width="85%" height={13}/><Skeleton width="65%" height={13}/><Skeleton width="94%" height={13}/></div> : state.fileReady && draft ? <>
      {state.heldFormalDraft && <RetainedFormalDraft state={state}/>}
      {state.fileSaving && <ProgressBar label="正在保存当前文件" isIndeterminate showValueLabel={false} size="sm"/>}
      {state.fileReady && !state.fileSaving && version>0 && can('projects.fileExport.review') && <ProjectFormalFileDelivery
      key={String(state.projectId)+':'+path+':'+version} projectId={state.projectId} projectName={state.projects?.find((row:Data)=>row.id===state.projectId)?.name||'当前项目'}
      path={path} version={version} dirty={draft?.dirty===true}/>}
    {conflict && <div className="pw-file-notice" role="alert">
      <p>{adoptedText !== undefined ? `已核对采纳后的正式 v${version}。基于 v${draft.version} 的草稿仍保留。` : '原文件版本已变化。当前草稿仍保留，请比较后再决定。'}</p>
      <Button variant="ghost" leadingIcon={IconCopy} onClick={copy}>复制保留的草稿</Button>
    </div>}
      {state.fileError && <MotionFeedback identity={state.fileError} className="pw-error" role="alert">{state.fileError}</MotionFeedback>}
      {adoption.message && <MotionFeedback identity={adoption.message} className={adoption.unknown ? 'pw-error' : 'pw-muted'} role={adoption.unknown ? 'alert' : 'status'}>{adoption.message}</MotionFeedback>}
      <Tabs selectedKey={mode} onSelectionChange={key => switchMode(key as FileLayer)} className="pw-file-tabs">
        <TabList aria-label="文件工作方式"><Tab id="edit" isDisabled={locked}>编辑</Tab><Tab id="diff" isDisabled={locked}>{adoptedText !== undefined ? '与正式版本比较' : '比较修改'}</Tab><Tab id="history" isDisabled={locked || !can('projects.history')}>版本记录</Tab></TabList>
        <TabPanel id="edit"><TextAreaField label="文件内容" value={draft.text} onChange={edit} isReadOnly={locked} className="pw-code-editor"/></TabPanel>
        <TabPanel id="diff">{adoptedText === undefined
          ? <InlineDiff before={draft.baseline} after={draft.text} versionLabel={`原版本 ${draft.version}`} onCancel={() => setMode('edit')} pending={locked}/>
          : adoptedText === draft.text ? <><p role="status">正式 v{version} 与保留草稿的文字相同，草稿没有被替换。</p><pre className="pw-file-revision-content">{adoptedText}</pre></>
            : <InlineDiff before={adoptedText} after={draft.text} versionLabel="只读比较" beforeLabel={`已采纳的正式 v${version}`}
              afterLabel={`保留草稿（基于 v${draft.version}）`} readOnly/>}</TabPanel>
        <TabPanel id="history" className="pw-file-history-panel">
          {selected === null ? <><FormalHistoryComparison version={state.fileVersion} rows={history} project={state.projectId} path={path}/><div className="pw-file-subheading"><p>已保存的版本</p><Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={locked} onClick={readHistory}>重新读取</Button></div><div ref={historyScroll} className="pw-file-version-list" onScroll={event => { historyTop.current = event.currentTarget.scrollTop; }}>{history.map((item: Data) => <Button key={item.version} data-revision={item.version} data-motion-key={`revision:${item.version}`} variant="ghost" className="pw-file-version-row" disabled={locked || !can('projects.revision')} onClick={() => { lastRevision.current = item.version; setSelected(item.version); readRevision(item.version); }}><span className="pw-version-number">版本 {item.version}</span><small>{typeof item.at === 'number' && item.at > 0 ? new Date(item.at).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '保存时间未提供'}</small></Button>)}</div>{!history.length && <p className="pw-empty-small">尚未读到这个文件的版本记录。可重新读取原文件历史。</p>}</> : <section className="pw-file-revision">
            <div className="pw-file-subheading"><Button variant="ghost" size="small" leadingIcon={IconArrowLeft} disabled={adoption.pending} onClick={() => { setReviewing(false); setSelected(null); }}>版本记录</Button><span>版本 {selected}</span>{earlierText && <Button variant="ghost" size="small" leadingIcon={IconCopy} disabled={locked} onClick={event => copyEarlier(event.currentTarget)}>复制此版正文</Button>}</div>
            {state.revisionLoading ? <ProgressBar label="正在读取所选原版本" isIndeterminate showValueLabel={false} size="sm"/> : selectedReady ? <><pre className="pw-file-revision-content">{state.revisionContent || '（空文件）'}</pre><div className="pw-file-adoption"><MotionDisclosure open={reviewing}><div ref={adoptionReview} className="pw-file-adoption-review"><h3 ref={adoptionHeading} tabIndex={-1}>替换当前编辑草稿？</h3><p>{draft.dirty ? '当前有未保存的修改。采用后会被所选历史内容替换，可先复制保留。' : '所选历史内容将放入当前编辑草稿。'}保存后才会生成新版本。</p>{draft.dirty && <Button variant="ghost" leadingIcon={IconCopy} onClick={copy}>复制当前草稿</Button>}<div className="pw-action-row"><Button variant="ghost" disabled={adoption.pending} onClick={() => setReviewing(false)}>取消</Button><Button variant="primary" disabled={locked || conflict || adoption.unknown || !can('projects.useRevision')} onClick={adoption.adopt}>{adoption.pending ? '正在核对原内容…' : '采用到编辑草稿'}</Button></div></div></MotionDisclosure>{!reviewing && <Button ref={adoptionTrigger} variant="secondary" disabled={locked || conflict || adoption.unknown || !can('projects.useRevision')} onClick={() => setReviewing(true)}>替换编辑草稿…</Button>}</div></> : state.revisionObservation?.version===selected && state.revisionObservation.path===path ? <FileObservation value={state.revisionObservation} older={!!state.fileError}/> : <p className="pw-muted">没有取得所选版本的完整文字。未把空白当成原稿。</p>}
          </section>}
        </TabPanel>
      </Tabs>
      <footer className="pw-file-save-bar"><MotionFeedback initial={false} identity={`${adoption.pending}:${!!state.fileSaving}:${draft.dirty}`} className="pw-file-save-state">{adoption.pending ? '正在核对原版本' : state.fileSaving ? '正在保存当前文件' : draft.dirty ? '草稿未保存到文件' : '没有未保存的修改'}</MotionFeedback>{can('projects.save') && <Button variant="primary" disabled={!draft.dirty || conflict || locked} onClick={save}>{state.fileSaving ? '正在保存…' : '保存修改'}</Button>}</footer>
    </> : <ProjectFormalReadOnly state={state} draft={draft} can={can} retry={retry} history={readHistory} revision={readRevision}/>}
  </section>;
}
