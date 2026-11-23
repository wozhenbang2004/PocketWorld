import { useId, useLayoutEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, TextField } from './forms-library';
import { InlineDiff } from './shell-library';

export type OfflinePageFields = { title: string; intro: string };
const titleLimit=80, introLimit=240;

// The exact built-in saved-page v2 wrapper in ProjectLiveProjectTemplate.
// Keep every non-text byte, including the final LF. This is not an HTML parser.
const start='<!doctype html>\n<html lang="zh-CN">\n  <head>\n    <meta charset="UTF-8">\n'+
  '    <meta name="viewport" content="width=device-width, initial-scale=1.0">\n    <title>';
const titleEnd='</title>\n    <link rel="stylesheet" href="/src/style.css">\n  </head>\n  <body>\n    <main>\n      <h1>';
const headingEnd='</h1>\n      <p>';
const end='</p>\n      <button id="counter" type="button">已点击 0 次</button>\n    </main>\n'+
  '    <script type="module" src="/src/main.js"></script>\n  </body>\n</html>\n';
const escaped:Readonly<Record<string,string>>={'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'};
const unescaped:Readonly<Record<string,string>>={'&amp;':'&','&lt;':'<','&gt;':'>','&quot;':'"','&#39;':"'"};
const encode=(text:string)=>text.replace(/[&<>"']/g,value=>escaped[value]??value);
const decode=(text:string)=>text.replace(/&(?:amp|lt|gt|quot|#39);/g,value=>unescaped[value]??value);

function singleLine(text:string,limit:number):boolean {
  if(text.length>limit*2)return false;
  let count=0;
  for(const char of text){
    const point=char.codePointAt(0)!;
    if(++count>limit||point<32||point>=127&&point<=159||point===0x2028||point===0x2029||
      point>=0xd800&&point<=0xdfff)return false;
  }
  return true;
}

export function offlinePageDraftText(fields:OfflinePageFields):string|null {
  if(!fields.title.trim()||!singleLine(fields.title,titleLimit)||!singleLine(fields.intro,introLimit))return null;
  const title=encode(fields.title);
  return start+title+titleEnd+title+headingEnd+encode(fields.intro)+end;
}

export function readOfflinePageFields(text:string):OfflinePageFields|null {
  if(text.length>4096||!text.startsWith(start)||!text.endsWith(end))return null;
  const first=text.indexOf(titleEnd,start.length);
  if(first<0)return null;
  const next=first+titleEnd.length, second=text.indexOf(headingEnd,next), endIndex=text.length-end.length;
  if(second<0||second+headingEnd.length>endIndex)return null;
  const title=text.slice(start.length,first);
  if(text.slice(next,second)!==title)return null;
  const fields={title:decode(title),intro:decode(text.slice(second+headingEnd.length,endIndex))};
  return offlinePageDraftText(fields)===text?fields:null;
}

export function ProjectOfflinePageFields({initial,source,visible,busy,back,sync}:{
  initial:OfflinePageFields;source:string;visible:boolean;busy:boolean;back:()=>void;sync:(value:OfflinePageFields)=>boolean;
}) {
  const [fields,setFields]=useState(initial);
  const [declined,setDeclined]=useState(false);
  const id=useId(), heading=useRef<HTMLHeadingElement>(null);
  useLayoutEffect(()=>{if(visible)heading.current?.focus()},[visible]);
  if(!visible)return null;
  const changed=fields.title!==initial.title||fields.intro!==initial.intro;
  const proposal=changed?offlinePageDraftText(fields):null;
  const update=(key:keyof OfflinePageFields,value:string)=>{
    setDeclined(false);setFields(previous=>({...previous,[key]:value}));
  };
  return <section className="pw-form-flow" aria-labelledby={id}>
    <h3 id={id} ref={heading} tabIndex={-1}>网页文字</h3>
    <p className="pw-field-hint">只修改标题和简介。同步只放进编辑草稿，保存和预览仍由你决定。</p>
    <TextField label="页面标题" inputDir="auto" value={fields.title} onChange={value=>update('title',value)} />
    <TextField label="一句简介" inputDir="auto" value={fields.intro} onChange={value=>update('intro',value)} />
    {changed&&!proposal&&<p role="status">请填写标题（最多 {titleLimit} 字符），简介可留空（最多 {introLimit} 字符）。两项都只接受单行文字。</p>}
    {proposal&&<Accordion><AccordionItem id="offline-page-text-diff" title="核对源码改动" headingLevel={4}>
      <InlineDiff before={source} after={proposal} versionLabel="源文件 → 编辑草稿" beforeLabel="已读取的源码" afterLabel="拟同步的草稿" readOnly />
    </AccordionItem></Accordion>}
    {declined&&<p role="alert">未同步这次表单。可返回源码编辑器核对，未保存文件。</p>}
    <div className="pw-form-actions">
      <Button variant="secondary" onClick={()=>{setDeclined(false);back()}}>返回源码编辑器</Button>
      <Button disabled={busy||!proposal} onClick={()=>{if(proposal&&!sync(fields))setDeclined(true)}}>同步到编辑草稿</Button>
    </div>
    {changed&&<p className="pw-field-hint">未同步的表单文字只在当前文件版本保留。</p>}
  </section>;
}
