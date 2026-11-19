import { useState } from 'react';
import {FormalHistoryComparison} from './FormalHistoryComparison';
import { Accordion, AccordionItem, Button } from './forms-library';

type Row = Record<string, any>;
type Draft = {text:string; version:number; dirty:boolean};
export function FileObservation({value,older=false}:{value:Row;older?:boolean}) {
  return <section aria-label={older?'上次核对的正式文件':'正式文件的原始记录'} className="pw-file-read-error">
    <p role="status">{older?'这是上次核对的文件身份，不是本次重新读取成功。':value.representation==='binary'?'这是二进制原件，没有文字草稿可编辑。':
      value.representation==='text_limit'?'正式文字超过此编辑器的完整读取上限，没有截断或当成空文件。':
      value.representation==='text'?'这是已核对的正式文字版本。':'原版本有记录，但没有取得完整、可验证的正文。'}</p>
    <p>版本 {value.version} · {value.bytes} 字节 · {value.kind==='binary'?'二进制':'UTF-8 文字'}</p>
    <Accordion><AccordionItem id={'formal-read-id-'+value.revisionId} title="核对原项目和版本"><p>
      <bdi>{value.path}</bdi><br/>项目 {value.projectId}<br/>修订 {value.revisionId}<br/>SHA-256 {value.sha256}
    </p></AccordionItem></Accordion>
  </section>;
}
export function RetainedFormalDraft({state,draft}:{state:Row;draft?:Draft}) {
  const saved=state.heldFormalDraft;
  const retained = saved && saved.projectId===state.projectId && saved.path===state.filePath && typeof saved.text==='string' ? saved : null;
  const entries = [...(draft?.dirty?[{name:'界面草稿',text:draft.text,version:draft.version}]:[]),
    ...(retained&&(!draft?.dirty||draft.text!==retained.text)?[{name:'手机保留的原稿',text:retained.text as string,version:retained.version}]:[])];
  return entries.length?<Accordion><AccordionItem id="formal-retained-draft" title="查看保留的文字草稿" description="没有写入或替换正式文件">
    {entries.map(entry=><div key={entry.name}><p>{entry.name} · 来自版本 {entry.version}</p><pre className="pw-file-revision-content">{entry.text||'（原草稿为空）'}</pre></div>)}
  </AccordionItem></Accordion>:null;
}
export function ProjectFormalReadOnly({state,draft,can,retry,history,revision}:{state:Row;draft?:Draft;can:(action:string)=>boolean;retry:()=>void;history:()=>void;revision:(v:number)=>void}) {
  const [showHistory,setShowHistory]=useState(false),[selected,setSelected]=useState<number|null>(null);
  const position=state.fileObservation, hasCurrent=position&&position.projectId===state.projectId&&position.path===state.filePath;
  const past=state.revisionObservation, shown=selected!==null&&selected===state.revisionNumber&&past?.version===selected;
  return <div>
    {hasCurrent&&<FileObservation value={position} older={!!state.fileError||position.representation==='text'}/>}
    {state.fileError&&<p className="pw-field-error" role="alert">{state.fileError}</p>}
    {!hasCurrent&&!state.fileError&&<p role="status">尚未取得原文件可核对的正文，不能使用文字保存。</p>}
    <RetainedFormalDraft state={state} draft={draft}/>
    <div className="pw-form-actions">
      {can('projects.retry')&&<Button variant="secondary" onClick={retry}>重新读取原文件</Button>}
      {hasCurrent&&can('projects.history')&&<Button variant="ghost" onClick={()=>{setSelected(null);setShowHistory(true);history()}}>查看版本记录</Button>}
    </div>
    {showHistory&&<section aria-label="只读版本记录">
      <FormalHistoryComparison version={state.fileVersion} rows={state.fileHistory} project={state.projectId} path={state.filePath}/>
      {(Array.isArray(state.fileHistory)?state.fileHistory:[]).map((row:Row)=><Button key={row.version} variant="ghost"
        disabled={!can('projects.revision')||row.deleted===true} onClick={()=>{setSelected(row.version);revision(row.version)}}>版本 {row.version}{row.deleted?' · 已删除':''}</Button>)}
      {state.revisionLoading&&selected===state.revisionNumber?<p role="status">正在读取所选正式版本…</p>:shown?<><FileObservation value={past}/>
        {past.representation==='text'&&typeof state.revisionContent==='string'&&<pre className="pw-file-revision-content">{state.revisionContent||'（空文件）'}</pre>}</>:null}
    </section>}
  </div>;
}
