import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, SettingsDialog } from './forms-library';
type Data = Record<string, any>;
type Who = { projectId: string; projectName: string; path: string; version: number; dirty: boolean };
const pending = new Set(['choosing', 'target_selected', 'writing']);
const unresolved = new Set(['unknown', 'write_unknown', 'written_unverified']);
const label: Record<string,string> = {
  choosing: '等待系统保存位置，尚未写入', target_selected: '已选位置，尚未写入', writing: '正在写入并重读目标',
  verified: '已核实目标文件', written_unverified: '写出已返回，但目标未核实', write_unknown: '写入结果不确定',
  unknown: '上次导出没有确定结果', cancelled: '已取消选取', invalid_target: '所选文档无法核对，未写入',
  target_changed: '目标文档名称或提供方已变化，未写入', source_changed: '原版本或窗口已变化，未写入', not_started: '系统选择器未启动', not_written: '上次已停止，没有写入正文', released: '已结束上次定位'
};
export function ProjectFormalFileDelivery({ projectId, projectName, path, version, dirty }: Who) {
  const [position,setPosition]=useState<Data|null>(null),[review,setReview]=useState<Data|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const alive=useRef(false),flight=useRef<object|null>(null),scope=useRef(0),snapshot=useRef<Data|null>(null);
  const here=()=>{const state=bridge.getState().state;return alive.current&&!document.hidden&&state.projectId===projectId&&state.filePath===path&&state.fileVersion===version&&state.fileReady;};
  const apply=(value:Data)=>{
    if(value.projectId!==projectId||value.path!==path||typeof value.phase!=='string')throw new Error('这不是原项目文件的导出回执。');
    if(value.phase!=='none'&&(!Number.isInteger(value.version)||value.version<1||typeof value.revisionId!=='string'||!value.revisionId||
        !/^[a-f0-9]{64}$/.test(value.sha256)||!Number.isSafeInteger(value.bytes)||value.bytes<0||value.bytes>262144))throw new Error('没有核对到原正式文件版本、字节数或摘要。');
    if(value.phase==='review'){if(value.version!==version||typeof value.reviewHandle!=='string')throw new Error('原正式版本已变化，请重新核对。');setReview(value);return;}
    if(value.phase!=='none'&&(!Object.hasOwn(label,value.phase)||typeof value.requestId!=='string'))throw new Error('原导出状态暂不能核对。');
    snapshot.current=value;setPosition(value);
  };
  const act=async(name:string,args:Data={})=>{
    if(!here()||flight.current)return;const token={},seq=scope.current;flight.current=token;setBusy(true);setError('');
    try{const value=await bridge.command<Data>('projects.fileExport.'+name,{projectId,path,version,...args});if(here()&&scope.current===seq&&flight.current===token)apply(value);}
    catch(e){if(here()&&scope.current===seq)setError(e instanceof Error?e.message:'原导出结果待核对。');}
    finally{if(flight.current===token){flight.current=null;if(alive.current)setBusy(false);}}
  };
  useEffect(()=>{
    alive.current=true;scope.current++;snapshot.current=null;setReview(null);setPosition(null);setError('');
    const refresh=()=>{if(document.hidden){scope.current++;flight.current=null;setBusy(false);setReview(null);return;}
      void act('status');};
    refresh();document.addEventListener('visibilitychange',refresh);window.addEventListener('focus',refresh);
    return()=>{alive.current=false;scope.current++;flight.current=null;document.removeEventListener('visibilitychange',refresh);window.removeEventListener('focus',refresh);};
  },[projectId,path,version]);
  useEffect(()=>{if(!position||!pending.has(position.phase))return;const timer=window.setInterval(()=>{if(here())void act('status');},1800);
    return()=>window.clearInterval(timer);},[projectId,path,version,position?.phase]);
  const phase=position?.phase||'none',locked=pending.has(phase)||unresolved.has(phase);
  return <section className="pw-formal-file-delivery" aria-label="另存这一份正式版本">
    <div className="pw-form-actions"><Button variant="ghost" size="small" disabled={busy||locked||!position} onClick={()=>void act('review')}>导出已保存的正式版本</Button>
      {(phase!=='none'||!position&&error)&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>void act('status')}>核对原导出回执</Button>}</div>
    {dirty&&<p className="pw-field-hint">导出不包含未保存草稿。</p>}
    {position&&phase!=='none'&&<><p role="status">{label[phase]} · 版本 {position.version}{position.sameVersion===false?'（不是当前版本）':''}</p>
      {phase==='target_selected'&&<><p>系统文档：{position.destinationName?<bdi>{position.destinationName}</bdi>:'等待文件服务返回实际名称'} · <bdi>{position.provider}</bdi></p>
        <Button variant="secondary" disabled={busy||!position.canWrite||position.sameVersion!==true} onClick={()=>void act('write',{requestId:position.requestId})}>确认写入这一个文档</Button></>}
      {phase==='verified'&&<p className="pw-field-hint">已重新打开所选文档，实际字节数与 SHA-256 均相同。</p>}
      {unresolved.has(phase)&&<p className="pw-field-hint">请先在所选位置检查文件；目标可能已有全部或部分内容，不会自动重写。</p>}
      {phase==='not_written'&&<p className="pw-field-hint">系统提供方可能保留选取时创建的空文档。</p>}
      {(unresolved.has(phase)||phase==='choosing'||phase==='target_selected')&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>void act('release',{requestId:position.requestId})}>结束这次定位，不重试或删除文件</Button>}
      <Accordion><AccordionItem id="formal-file-delivery-proof" title="原项目、版本与目标"><p>{projectName} · <bdi>{position.path}</bdi><br/>项目 {position.projectId}<br/>版本 {position.version} · {position.bytes} 字节<br/>修订 {position.revisionId}<br/>SHA-256 {position.sha256}</p>
        {position.provider&&<p>系统文档服务：<bdi>{position.provider}</bdi>{position.destinationName&&<> · <bdi>{position.destinationName}</bdi></>}</p>}</AccordionItem></Accordion></>}
    {error&&<p role="alert" className="pw-field-error">{error}</p>}
    {review&&<SettingsDialog isOpen title="只导出这一份正式版本" onClose={()=>setReview(null)} closeLabel="返回文件">
      <p>{projectName} · <bdi>{path}</bdi><br/>版本 {review.version} · {review.bytes} 字节</p><p className="pw-field-hint">SHA-256 {review.sha256}</p>
      <p>不包含未保存草稿、其他文件或整个项目。下一步只打开 Android 保存位置；返回后核对文件名和提供方再写入。若选云盘或替换已有文档，取决于你在系统里选定的目标。</p>
      <div className="pw-form-actions"><Button onClick={()=>{const r=review;setReview(null);void act('choose',{reviewHandle:r.reviewHandle});}}>选择系统保存位置</Button><Button variant="ghost" onClick={()=>setReview(null)}>返回</Button></div>
    </SettingsDialog>}
  </section>;
}
