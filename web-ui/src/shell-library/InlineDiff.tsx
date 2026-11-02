/* PW-24: controlled file adaptation of library Affect InlineDiffDemo v3.
 * Original pro-more.tsx is retained. Original inline del/ins suggestion structure remains;
 * real file baseline/draft replace the four fixture edits. No local fake accept/undo.
 */
import {RiCheckLine,RiCloseLine,RiCodeSSlashLine} from '@remixicon/react';
export function InlineDiff({before,after,versionLabel,onApply,onCancel,pending=false}:{before:string;after:string;versionLabel:string;onApply?:()=>void;onCancel?:()=>void;pending?:boolean}){
 const same=before===after;
 return <section className="acc-inline acc-card pw-inline-diff" aria-label="当前文件差异"><header className="acc-head"><span><RiCodeSSlashLine size={17}/> 当前修改 <span className="acc-muted">· {versionLabel}</span></span></header>{same?<p className="pw-empty-small">当前内容与原版本一致。</p>:<div className="pw-diff-columns"><section><h4>原版本</h4><pre><del>{before||'（空文件）'}</del></pre></section><section><h4>当前草稿</h4><pre><ins>{after||'（空文件）'}</ins></pre></section></div>}<footer><span className="acc-muted">保存仍以原文件版本核对</span><span className="acc-spacer"/>{onCancel&&<button type="button" className="acc-btn" onClick={onCancel} disabled={pending}><RiCloseLine size={15}/>返回编辑</button>}{onApply&&<button type="button" className="acc-btn acc-btn-primary" onClick={onApply} disabled={pending||same}><RiCheckLine size={15}/>{pending?'正在保存…':'保存这份修改'}</button>}</footer></section>
}
