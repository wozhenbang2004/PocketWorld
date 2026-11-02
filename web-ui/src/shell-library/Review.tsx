/* PW-25: controlled adapters of library Affect PermissionRequestDemo and ReviewCenterDemo v3.
 * Originals retained upstream/affect. Preserve the request heading, reason, concrete scope,
 * queue/detail relation and action area; remove seed requests, confidence scores, auto decisions,
 * timed grants, implicit Enter approval and fake Undo. Host review handles own every decision.
 */
import {useState,type ReactNode} from 'react';
import {RiShieldCheckLine,RiArrowRightSLine} from '@remixicon/react';
import {PressButton} from '../library';
import {Button} from '../forms-library';
export type ReviewItem={id:string;title:string;description?:string;status?:string};
export function ReviewCenter({items,selectedId,onSelect,children}:{items:ReviewItem[];selectedId?:string|null;onSelect:(id:string)=>void;children:ReactNode}){
 const [showAll,setShowAll]=useState(true);
 return <section className="acc-review acc-card pw-review-center" aria-label="待处理审阅"><header className="acc-head"><span><RiShieldCheckLine size={17}/> 待处理 <span className="acc-muted">{items.length} 项</span></span><button className="acc-btn" type="button" onClick={()=>setShowAll(!showAll)} aria-expanded={showAll}>{showAll?'收起目录':'展开目录'}</button></header><div className="acc-review-layout">{showAll&&<div className="acc-review-queue" role="list" aria-label="待处理任务">{items.map(item=><button type="button" key={item.id} aria-current={selectedId===item.id?'true':undefined} onClick={()=>onSelect(item.id)}><span>{item.title}<small>{item.description}</small></span><small>{item.status}</small><RiArrowRightSLine size={15}/></button>)}{!items.length&&<p className="acc-empty">当前没有待处理事项。</p>}</div>}<div className="acc-review-detail">{children}</div></div></section>
}
export function PermissionReview({title,context,reason,fields,children,onApprove,onReject,approveLabel='确认并开始',rejectLabel='返回修改',pending=false,error}:{title:string;context?:string;reason?:string;fields?:{label:string;value:ReactNode}[];children?:ReactNode;onApprove?:()=>void;onReject?:()=>void;approveLabel?:string;rejectLabel?:string;pending?:boolean;error?:string}){
 return <section className="cb-permissions2 pw-permission"><div className="cb-card cb-permission2-request"><div><div className="cb-permission2-heading"><RiShieldCheckLine size={24}/><div className="cb-grow"><strong>{title}</strong>{context&&<small>{context}</small>}</div></div>{reason&&<blockquote>{reason}</blockquote>}<div className="cb-permission2-content"><dl className="pw-scope-fields">{fields?.map(field=><div key={field.label}><dt>{field.label}</dt><dd>{field.value}</dd></div>)}</dl>{children}</div>{error&&<p className="pw-error" role="alert">{error}</p>}<div className="cb-permission2-actions"><div className="cb-row"><span className="cb-grow"/>{onReject&&<Button variant="secondary" onClick={onReject} disabled={pending}>{rejectLabel}</Button>}{onApprove&&<PressButton onClick={onApprove} disabled={pending} label={pending?'正在提交…':approveLabel}/>}</div></div></div></div></section>
}
