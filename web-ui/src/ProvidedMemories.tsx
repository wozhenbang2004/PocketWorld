import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconChevronLeft, IconChevronRight, IconClose, IconRefresh } from './icons';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import { Button, IconButton } from './forms-library';
import { memoryHistoryStatus, taskMemoryVisibleRecords } from './memory-history';
import './source-reading.css';
import {AnswerMemoryCorrection,canCorrectProvided,type AnswerMemoryCorrectionOutcome} from './AnswerMemoryCorrection';
import type {HistoricalAnswerSource} from './AnswerSources';
import {originalMemoryWindow} from './OriginalSavedMemory';

type Data = Record<string, any>;
type SourceCursor = { index: number; offset: number; previous: number[] };
type RevisedVersion = { id: string; originalRevision: number; currentRevision: number };
type ReadingVisit = { presentation: string; evidence: string };
type CurrentRead = { expires: number } & (
  { status: 'read'; text: string; target: RevisedVersion; visit: ReadingVisit; ticket: number }
  | { status: 'not_available' });
export type CurrentProvidedMemoryTarget = RevisedVersion & {
  expires: number;
  taskId: string; runId: string; requestId: string; answerId: string; receiptId: string; coordinatorEpoch: number;
};
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const statusLabel = (status: string) => ({ current: '当前仍有效', record_changed: '这条记忆后来已修改', record_removed: '这条记忆已删除', source_forgotten: '原来源已遗忘', superseded: '已被新记忆替代', expired: '已到期', revoked: '原引用已撤回', run_changed: '原执行已变化' } as Record<string, string>)[status] || '原版本当前不可读';
const selectionReason = (row: Data) => row.selectionReason==='task_query_match'?'当时与原问题相关':
  row.selectionReason==='owner_core'?'当时的常用记忆':row.selectionReason==='owner_current'?'当时的个人背景':undefined;
const revised = (row: Data): RevisedVersion | null => row.status==='record_changed' && typeof row.currentReadHandle==='string' && !!row.currentReadHandle &&
  typeof row.id==='string' && !!row.id && Number.isSafeInteger(row.revision) && row.revision>0 && Number.isSafeInteger(row.currentRevision) && row.currentRevision>row.revision
  ? {id:row.id,originalRevision:row.revision,currentRevision:row.currentRevision} : null;
const proofKeys = ['taskId','runId','requestId','answerId','receiptId','coordinatorEpoch','ownerId','spaceId'] as const;
const answerPresentation = () => {
  const snapshot = bridge.getState(), view = snapshot.view as Record<string, unknown> | undefined;
  return JSON.stringify([bridge.getDocumentEpoch(), snapshot.state.conversationId, view?.section, view?.taskId, view?.resumeRevision]);
};

/** Uses the original answer's native request receipt, with the existing bounded source reader. */
export function ProvidedMemories({ messageId, revision, history, onClose, onCloseAutoFocus, onManage, onManageCurrent, registerBack }: {
  messageId: string; revision: number; history?: HistoricalAnswerSource; onClose: () => void;
  onManage?: (original?: {id:string;revision:number}) => void; onCloseAutoFocus?: (event:Event) => void;
  onManageCurrent?: (target:CurrentProvidedMemoryTarget) => boolean;
  registerBack: (handler: (() => boolean) | null) => void;
}) {
  const [receipt, setReceipt] = useState<Data | null>(null), [selection, setSelected] = useState<Data | null>(null);
  const [source, setSource] = useState<Data | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(true);
  const [currentRead,setCurrentRead]=useState<CurrentRead|null>(null);
  const [sourceCursor, setSourceCursor] = useState<SourceCursor | null>(null);
  const [refresh, setRefresh] = useState(0), [correcting,setCorrecting]=useState(false), [outcome,setOutcome]=useState<AnswerMemoryCorrectionOutcome|null>(null);
  const correctionBack=useRef<(()=>boolean)|null>(null), attention=useRef(0), savingCorrection=useRef(false), seenRevision=useRef(revision);
  const session = useRef(''), generation = useRef(0), live = useRef(true), reading = useRef<number | null>(null);
  const readWindow=useRef<ReadingVisit|null>(null), savedVersion=useRef<RevisedVersion|null>(null), requestedVersion=useRef<RevisedVersion|null>(null);
  const sameWindow = useCallback((visit: ReadingVisit | null) => !!visit && live.current && !document.hidden && readWindow.current === visit
    && visit.presentation === answerPresentation() && visit.evidence === originalMemoryWindow(), []);
  const awaitingSave = savingCorrection.current;
  const receiptVisible = !!receipt && !awaitingSave && sameWindow(readWindow.current) && receipt.answerReadExpiresAt > Date.now();
  const provided = receiptVisible && receipt?.phase === 'provided_to_request';
  const records = provided ? taskMemoryVisibleRecords(receipt) : [];
  const commands = bridge.getState().capabilities.commands;
  const correctionSupported = ['taskMemory.review','taskMemory.confirm'].every(action=>commands.includes(action));
  const only = records.length === 1 ? records[0] : null;
  // The owner already opened this answer's receipt. With exactly one proven,
  // correctable preference, show that record rather than a one-row chooser.
  const directPreference = !history && receipt?.status==='current' && correctionSupported && only && only.layer==='preference' &&
    selectionReason(only) && canCorrectProvided(receipt,only) ? only : null;
  const selected = receiptVisible ? selection ?? directPreference : null;
  const backToReceipt = !!selected && (!directPreference || !!sourceCursor);
  const closeSession = useCallback(() => { const id = session.current; session.current = ''; if (id) void bridge.command('memoryHistory.close', { handle: id }).catch(() => {}); }, []);
  const clearReading = useCallback(() => {
    generation.current++; reading.current = null; closeSession(); setCorrecting(false); setReceipt(null);
    readWindow.current=null; setSelected(null); setSource(null); setCurrentRead(null); setSourceCursor(null); setBusy(false);
  }, [closeSession]);
  const leaveReading = useCallback((message: string) => {
    attention.current++; savingCorrection.current=false; savedVersion.current=null; requestedVersion.current=null; clearReading(); setOutcome(null); setError(message);
  }, [clearReading]);
  const readCurrentVersion=useCallback(async (original:Data,row:Data,version:number)=>{
    const target=revised(row), visit=readWindow.current;
    if(!target||!visit||!session.current||!sameWindow(visit)||original.answerReadExpiresAt<=Date.now()||!bridge.getState().capabilities.commands.includes('taskMemory.currentRevision'))return;
    reading.current=version;
    const current=()=>generation.current===version&&sameWindow(visit)&&original.answerReadExpiresAt>Date.now();
    setSelected(row); setCurrentRead(null); setBusy(true); setError('');
    try{
      const result=await bridge.command('taskMemory.currentRevision',{currentReadHandle:row.currentReadHandle});
      if(!current())return;
      if(result.format!=='pocketworld.current-provided-memory-version.v1'||result.providedToOriginalRequest!==false||result.executionAuthorized!==false||result.modelUseVerified!==false||
        !proofKeys.every(key=>result.original?.[key]===original[key])||result.id!==target.id||result.originalRevision!==target.originalRevision||result.currentRevision!==target.currentRevision||
        !Number.isSafeInteger(result.readExpiresAt)||result.readExpiresAt<=Date.now()||result.readExpiresAt>original.answerReadExpiresAt||
        (result.status==='read'?typeof result.text!=='string'||!result.text.trim()||result.text.length>4000:result.status!=='not_available'||result.text!==undefined))throw new Error();
      setCurrentRead(result.status==='read'
        ? {status:'read',expires:result.readExpiresAt,text:result.text,target,visit,ticket:version}
        : {status:'not_available',expires:result.readExpiresAt});
    }catch{if(current())setError('没能核对这条记忆的所选版本。请重新核对原回执；没有显示其他版本。');}
    finally{if(reading.current===version)reading.current=null;if(current())setBusy(false);}
  },[sameWindow]);
  useEffect(() => { live.current = true; return () => { live.current = false; generation.current++; closeSession(); }; }, [closeSession]);
  useEffect(() => {
    const wanted=requestedVersion.current; requestedVersion.current=null;
    clearReading(); const version = generation.current; setOutcome(null); setError(''); setBusy(true);
    if (document.hidden) { setBusy(false); return; }
    const visit = { presentation: answerPresentation(), evidence: originalMemoryWindow() };
    readWindow.current = visit;
    const current = () => generation.current === version && sameWindow(visit);
    void (async () => {
      try {
        const opened = await bridge.command('memoryHistory.open', { view: 'answer', ...(history||{}), messageId });
        if (!current()) { void bridge.command('memoryHistory.close', { handle: opened.sessionHandle }).catch(() => {}); return; }
        session.current = opened.sessionHandle;
        const answer = await bridge.command('taskMemory.answer', { sessionHandle: session.current, messageId });
        if (!current()) return;
        const result = await bridge.command('taskMemory.receipt', { taskHandle: answer.taskHandle });
        if (!current()) return;
        if (result.modelUseVerified !== false || result.executionAuthorized !== false || !Array.isArray(result.records)
          || !Number.isSafeInteger(result.answerReadExpiresAt) || result.answerReadExpiresAt <= Date.now()) throw new Error('原记忆提供回执不完整或已过期，请重新核对。');
        setReceipt(result);
        if(wanted){
          const same=taskMemoryVisibleRecords(result).find(row=>revised(row)&&row.id===wanted.id&&row.revision===wanted.originalRevision&&row.currentRevision===wanted.currentRevision);
          if(same)await readCurrentVersion(result,same,version);
          else setError(`无法从原回执再核对刚保存的第 ${wanted.currentRevision} 版；没有打开别的版本。`);
        }
      } catch (failure) { if (current()) setError(failure instanceof Error ? failure.message : String(failure)); }
      finally { if (current()) setBusy(false); }
    })();
  }, [messageId, history, refresh, clearReading, readCurrentVersion, sameWindow]);
  useLayoutEffect(() => {
    if(seenRevision.current===revision)return;
    seenRevision.current=revision;
    // The native write signals invalidation before its own confirmation arrives.
    // Keep that one session until the reply lands, but do not paint its old rows.
    if(savingCorrection.current)return;
    attention.current++; clearReading(); setError('记忆或来源已变化，请重新核对原回执。');
  }, [revision, clearReading]);
  useEffect(() => {
    let documentEpoch=bridge.getDocumentEpoch();
    const retire=()=>leaveReading('已离开原阅读窗口，返回后请重新核对这条回答。');
    const hidden=()=>{if(document.hidden)retire()};
    const off=bridge.subscribe(()=>{
      const next=bridge.getDocumentEpoch(), visit=readWindow.current;
      if(next!==documentEpoch){documentEpoch=next;retire();}
      else if(visit&&visit.presentation!==answerPresentation())retire();
      // A correction's own revision event can precede its receipt. Hide the old rows,
      // but keep only that submitted operation alive in the same foreground presentation.
      else if(visit&&!savingCorrection.current&&visit.evidence!==originalMemoryWindow())leaveReading('原回答或记忆已变化，请重新核对原回执。');
    });
    document.addEventListener('visibilitychange',hidden);window.addEventListener('pagehide',retire);
    return()=>{off();document.removeEventListener('visibilitychange',hidden);window.removeEventListener('pagehide',retire);};
  }, [leaveReading]);
  useEffect(()=>{
    const expires=currentRead?.expires??receipt?.answerReadExpiresAt;if(!Number.isSafeInteger(expires))return;
    const timer=setTimeout(()=>leaveReading('原回答阅读窗口已过期，请重新核对。'),Math.max(0,expires-Date.now()));
    return()=>clearTimeout(timer);
  },[receipt,currentRead,leaveReading]);
  const returnToMemories = () => {
    generation.current++; reading.current = null; setSelected(null); setSource(null); setCurrentRead(null); setSourceCursor(null); setError(''); setBusy(false);
  };
  useEffect(() => {
    registerBack(() => { if(correctionBack.current?.()) return true; if (backToReceipt) returnToMemories(); else onClose(); return true; });
    return () => registerBack(null);
  }, [backToReceipt, registerBack, onClose]);
  const readSource = async (index: number, offset = 0, previous: number[] = []) => {
    const visit = readWindow.current;
    if (!selected?.readHandle || reading.current !== null || !session.current || !sameWindow(visit) || receipt?.answerReadExpiresAt <= Date.now()) return;
    const version = ++generation.current; reading.current = version;
    const current = () => version === generation.current && sameWindow(visit) && receipt?.answerReadExpiresAt > Date.now();
    // Keep the attempted range even if its read fails; retry must not restart at page one.
    setSourceCursor({ index, offset, previous }); setBusy(true); setSource(null); setError('');
    try {
      const value = await bridge.command('taskMemory.source', { readHandle: selected.readHandle, index, offset });
      if (value.offset !== offset || value.hasMore && (!Number.isSafeInteger(value.nextOffset) || value.nextOffset <= offset)) throw new Error('原来源没有返回可核对的分段位置，请重试本段。');
      if (current()) setSource({ ...value, index, previous });
    } catch (failure) { if (current()) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (reading.current === version) reading.current = null; if (current()) setBusy(false); }
  };
  // Layer is returned only for the exact still-readable revision. Neither the
  // source text nor today's profile can prove a missing historical category.
  const isPreference = (row: Data) => provided && (receipt?.status === 'current' || receipt?.status === 'invalidated')
    && row.status === 'current' && row.layer === 'preference' && typeof row.text === 'string';
  const preferences = records.filter(isPreference);
  const groups = preferences.length
    ? [{ title: '偏好类记忆', items: preferences }, { title: '同次请求的其他条目', items: records.filter(row => !isPreference(row)) }]
    : [{ title: '', items: records }];
  const selectedLater=selected&&revised(selected), canReadCurrent=commands.includes('taskMemory.currentRevision');
  const personalCurrent=!history&&canCorrectProvided(receipt,selected);
  const canCorrect = personalCurrent && correctionSupported;
  const canManage = !!onManage && personalCurrent && commands.includes('profile.memory.get');
  const currentManagementTarget=():CurrentProvidedMemoryTarget|undefined=>{
    if(!onManageCurrent||history||!provided||receipt?.status!=='invalidated'||receipt.ownerId!=='owner'||receipt.spaceId!=='private'||
      'memoryNamespace'in receipt||!selectedLater||currentRead?.status!=='read'||currentRead.expires<=Date.now()||
      currentRead.ticket!==generation.current||!sameWindow(currentRead.visit)||!bridge.getState().capabilities.commands.includes('profile.memory.get'))return;
    const {target,expires}=currentRead, {taskId,runId,requestId,answerId,receiptId,coordinatorEpoch}=receipt;
    if(target.id!==selectedLater.id||target.originalRevision!==selectedLater.originalRevision||target.currentRevision!==selectedLater.currentRevision||
      ![taskId,runId,requestId,answerId,receiptId].every(value=>typeof value==='string'&&!!value)||
      !Number.isSafeInteger(coordinatorEpoch)||coordinatorEpoch<0)return;
    return {...target,expires,taskId,runId,requestId,answerId,receiptId,coordinatorEpoch};
  };
  const preferenceReason = selected && selected.layer==='preference' && personalCurrent ? selectionReason(selected) : undefined;
  const attended=attention.current;
  const finishCorrection=(result:AnswerMemoryCorrectionOutcome)=>{
    if(!live.current||document.hidden||attention.current!==attended||readWindow.current?.presentation!==answerPresentation())return;
    savedVersion.current=result.kind==='saved'&&selection?{id:selection.id,originalRevision:selection.revision,currentRevision:result.revision}:null;
    savingCorrection.current=false; clearReading(); setError(''); setOutcome(result);
  };
  const refreshOriginal=(version:RevisedVersion|null=null)=>{requestedVersion.current=version;setRefresh(value=>value+1);};
  const close=()=>{if(!correctionBack.current?.())onClose();};
  return <Drawer.Root open onOpenChange={open => { if (!open) close(); }} shouldScaleBackground={false}><Drawer.Portal>
    <Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-answer-sources" aria-describedby="provided-memory-description" onCloseAutoFocus={onCloseAutoFocus}>
      <div className="pw-drawer-handle"/><header className="pw-drawer-header">
        {backToReceipt && <IconButton icon={IconChevronLeft} aria-label={directPreference?'收起原来源':'返回本次记忆列表'} disabled={busy} onClick={returnToMemories}/>}<div><Drawer.Title>{outcome?'这次纠正':selectedLater?'这条记忆的现行版':selected?'这条记忆':provided?'本次提供的记忆':'核对本次记忆'}</Drawer.Title></div>
        <IconButton icon={IconRefresh} aria-label="重新核对原记忆回执" disabled={busy} onClick={() => refreshOriginal()}/><IconButton icon={IconClose} aria-label="返回原回答" onClick={close}/>
      </header>
      <Drawer.Description id="provided-memory-description" className="pw-source-description">{outcome?.kind==='saved'?'这次纠正只更新已保存的记忆；不会改写原回答或当时的提供记录。':selectedLater?`当时提供的是第 ${selectedLater.originalRevision} 版，下面只核对后来保存的同一条。`:!provided?'在这里核对这条回答的原请求，不用现在的设置或新的检索代替。':selected?'这条记忆曾提供给模型。可以对照原来源，但不能据此断定模型读过原文。':'下列记忆条目有原请求的提供回执，不代表模型采用了它们。'}</Drawer.Description>
      <div className="pw-drawer-body">
        {outcome?.kind==='saved'?<><section className="pw-source-record" role="status"><h3>本次已保存为第 {outcome.revision} 版</h3><p className="pw-muted">{outcome.feedback}</p></section>
          {canReadCurrent&&savedVersion.current&&<div className="pw-action-row"><Button variant="secondary" onClick={()=>refreshOriginal(savedVersion.current)}>核对刚保存的记忆</Button></div>}</>:
          outcome&&<><p className="pw-muted" role="status">{outcome.message}</p>{outcome.kind==='unconfirmed'&&onManage&&<Button variant="ghost" onClick={()=>onManage()}>管理我的记忆</Button>}</>}
        {busy && !awaitingSave && <p className="pw-muted" role="status">{selectedLater?'正在核对选定版本…':'正在核对原请求与记忆版本…'}</p>}{error && !outcome && <p className="pw-error" role="alert">{error}</p>}
        {receipt && !provided && !awaitingSave && <p className="pw-muted">这条回答的原生提供回执当前不可读，未用新的检索结果代替。</p>}
        {provided && !selected && <>
          {!records.length && <p className="pw-muted">这份回执没有列出记忆条目。</p>}
          {groups.filter(group => group.items.length > 0).map(group => <section key={group.title} aria-label={group.title || '原请求提供的记忆条目'}>
            {group.title && <header className="pw-knowledge-group-heading"><h3>{group.title}</h3><span>{group.items.length}</span></header>}
            <div className="pw-source-list">{group.items.map(row => {
              const later=canReadCurrent?revised(row):null, available=row.status==='current'&&!!row.readHandle;
              return <Button key={row.id} variant="ghost" className="pw-source-row" trailingIcon={IconChevronRight} disabled={busy||!later&&!available} onClick={()=>{
                if(!sameWindow(readWindow.current)||receipt?.answerReadExpiresAt<=Date.now())return;
                if(later&&receipt&&reading.current===null)void readCurrentVersion(receipt,row,++generation.current);
                else if(available){setSelected(row);setSource(null);setError('');}
              }}><span className="pw-source-row-copy"><b>{available?row.text:statusLabel(row.status)}</b><small>{later?`当时第 ${later.originalRevision} 版 · 核对现行第 ${later.currentRevision} 版`:
                `${selectionReason(row)??'原回执未保留选入原因'} · ${statusLabel(row.status)}`}</small></span></Button>;
            })}</div>
          </section>)}
          <p className="pw-muted">这份回执不列出语言、详略等默认设置。聊天里说“这次简短些”，也不等于改了长期偏好。</p>
          {onManage&&<div className="pw-action-row"><Button variant="ghost" onClick={()=>onManage()}>管理我的记忆</Button></div>}
        </>}
        {selectedLater&&currentRead?.status==='read'&&currentRead.expires>Date.now()&&<section className="pw-source-record"><h3>目前保存 · 第 {selectedLater.currentRevision} 版</h3>
          <p className="pw-source-copy" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{currentRead.text}</p>
          <p className="pw-muted">这不是原回答收到的内容。后来哪一轮实际收到哪一版，要看那条回答自己的「本次记忆」。</p>
          {currentManagementTarget()&&<div className="pw-action-row"><Button variant="ghost" size="small" disabled={busy} onClick={()=>{
            const target=currentManagementTarget();if(!busy&&target&&!onManageCurrent?.(target))
              setError('原回答或草稿尚未核对，未打开其他记忆。请回原回复后再试。');
          }}>管理这条记忆</Button></div>}</section>}
        {selectedLater&&currentRead?.status==='not_available'&&<p className="pw-muted" role="status">第 {selectedLater.currentRevision} 版目前不在可用范围，未显示旧版或新版正文。</p>}
        {selected && !selectedLater && <>
          <p className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{selected.text}</p>
          <p className="pw-muted">{selected.source || '原记忆记录'} · {statusLabel(selected.status)}{selected.expiresAt ? ` · 有效至 ${new Date(selected.expiresAt).toLocaleDateString()}` : ''}</p>
          {preferenceReason && <p className="pw-muted">为何提供：{preferenceReason}</p>}
          {(canCorrect||canManage)&&<div className="pw-action-row">
            {canCorrect&&<Button variant={directPreference?'secondary':'ghost'} size="small" disabled={busy} onClick={()=>{if(sameWindow(readWindow.current)&&canCorrectProvided(receipt,selected)){setSelected(selected);setSource(null);setSourceCursor(null);setCorrecting(true);}}}>纠正这条已保存记忆</Button>}
            {canManage&&onManage&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>{if(sameWindow(readWindow.current)&&canCorrectProvided(receipt,selected))onManage({id:selected.id,revision:selected.revision});}}>管理这条记忆</Button>}
          </div>}
          {rows(selected.sourceRefs).length ? <div className="pw-action-row">{rows(selected.sourceRefs).map((_, index) => <Button key={index} variant="secondary" disabled={busy} onClick={() => void readSource(index)}>查看原来源 {index + 1}</Button>)}</div> : <p className="pw-muted">这条记录未附原文来源；显示的内容是保存的记忆。</p>}
          {source && <section className="pw-source-record"><h3>{source.title || '原来源'}</h3>{source.status && source.status !== 'current' ? <p className="pw-muted">{memoryHistoryStatus(source.status)}，未显示旧正文。</p> : <p className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</p>}
            <div className="pw-action-row">{source.previous.length > 0 && <Button variant="ghost" disabled={busy} onClick={() => void readSource(source.index, source.previous.at(-1), source.previous.slice(0, -1))}>上一段原文</Button>}{source.hasMore && <Button variant="ghost" disabled={busy} onClick={() => void readSource(source.index, source.nextOffset, [...source.previous, source.offset])}>继续读取原文</Button>}</div>
          </section>}
          {error && sourceCursor && <Button variant="secondary" disabled={busy} onClick={() => void readSource(sourceCursor.index, sourceCursor.offset, sourceCursor.previous)}>重试读取本段</Button>}
        </>}
      </div>
      {correcting&&(canCorrect||awaitingSave)&&receipt&&selection&&<AnswerMemoryCorrection key={selection.readHandle} receipt={receipt} row={selection} backRef={correctionBack} onClose={()=>setCorrecting(false)}
        onSaving={()=>{savingCorrection.current=true;setBusy(true);}} onOutcome={finishCorrection}/>}
    </Drawer.Content>
  </Drawer.Portal></Drawer.Root>;
}
