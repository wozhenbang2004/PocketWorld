/* Controlled Affect review + BeUI a866cb2 morphing-modal/sortable/swipe adapters.
 * Sorting changes this directory's display only. Swiping exposes “查看详情” only;
 * native approval authority, pending guards and real task actions remain with host. */
import {useState,type ReactNode} from 'react';
import {motion,useReducedMotion} from 'motion/react';
import { IconChevronRight, IconEye, IconShieldCheck } from '../icons';
import {PressButton} from '../library';
import {Button} from '../forms-library';
import {SortableList,SortableListGroup,SortableListItem,SortableListHandle,SortableListItemContent,SortableListUndo} from './beui-adapted/sortable-list';
import {SwipeableList,type SwipeableListValue} from './beui-adapted/swipeable-list';
import {SPRING_PANEL,SPRING_LAYOUT} from './beui-adapted/ease';
import './beui-adapted/interactions.css';
export type ReviewItem={id:string;title:string;description?:string;status?:string};
export function ReviewCenter({items,selectedId,onSelect,children}:{items:ReviewItem[];selectedId?:string|null;onSelect:(id:string)=>void;children:ReactNode}){
 const[showAll,setShowAll]=useState(true),[order,setOrder]=useState<string[]>([]),[openSwipe,setOpenSwipe]=useState<SwipeableListValue|null>(null);const reduce=useReducedMotion();
 const positions=new Map(order.map((id,index)=>[id,index]));const ordered=items.slice().sort((a,b)=>(positions.get(a.id)??order.length+items.indexOf(a))-(positions.get(b.id)??order.length+items.indexOf(b)));
 return <motion.section layout={!reduce} transition={reduce?{duration:0}:SPRING_LAYOUT} className="acc-review acc-card pw-review-center" aria-label="待处理审阅">
 <header className="acc-head"><span><IconShieldCheck size={17}/> 待处理 <span className="acc-muted">{items.length} 项</span></span><button className="acc-btn" type="button" onClick={()=>setShowAll(!showAll)} aria-expanded={showAll}>{showAll?'收起目录':'展开目录'}</button></header>
 <div className="acc-review-layout">
 {showAll&&<div className="acc-review-queue pw-interaction-queue"><p className="pw-queue-hint">拖动把手整理本页目录，侧滑查看详情。目录顺序不改变执行顺序。</p>
 <SortableList items={ordered} onItemsChange={next=>{setOrder(next.map(item=>item.id));setOpenSwipe(null)}} getItemLabel={item=>item.title} label="待处理任务目录" className="pw-review-sortable">
 {current=><><SortableListGroup className="pw-review-sort-group">{current.map(item=><SortableListItem key={item.id} id={item.id} className="pw-review-sort-row">
 <SortableListHandle aria-label={`调整目录顺序：${item.title}`} className="pw-review-sort-handle"/>
 <SortableListItemContent><SwipeableList items={[{id:item.id,title:item.title,rightActions:[{id:'view',label:'查看详情',icon:<IconEye size={16}/>,onClick:()=>onSelect(item.id)}]}]} value={openSwipe} onValueChange={setOpenSwipe} actionWidth={80} classNames={{surface:'pw-review-swipe-surface',item:'pw-review-swipe-item',action:'pw-review-swipe-action'}} renderItem={()=>
 <button type="button" className="pw-review-select" aria-current={selectedId===item.id?'true':undefined} onClick={()=>{setOpenSwipe(null);onSelect(item.id)}}><span className="pw-review-queue-copy"><strong>{item.title}</strong>{item.description&&<small>{item.description}</small>}</span>{item.status&&<small className="pw-review-queue-status">{item.status}</small>}<IconChevronRight className="pw-review-queue-arrow" size={15}/></button>}/></SortableListItemContent>
 </SortableListItem>)}</SortableListGroup><SortableListUndo className="pw-review-sort-undo"/></>}
 </SortableList>{!items.length&&<p className="acc-empty">当前没有待处理事项。</p>}
 </div>}<motion.div layout={reduce?false:'position'} className="acc-review-detail">{children}</motion.div>
 </div></motion.section>
}
export function PermissionReview({title,context,reason,fields,children,headerAction,onApprove,onReject,approveLabel='确认并开始',rejectLabel='返回修改',pending=false,error}:{title:string;context?:string;reason?:string;fields?:{label:string;value:ReactNode}[];children?:ReactNode;headerAction?:ReactNode;onApprove?:()=>void;onReject?:()=>void;approveLabel?:string;rejectLabel?:string;pending?:boolean;error?:string}){
 const reduce=useReducedMotion();
 // Animate only the current shell. No exit-retained approval or stale action surface.
 return <motion.section className="cb-permissions2 pw-permission pw-morph-review" layout={!reduce} transition={reduce?{duration:0}:SPRING_PANEL} aria-busy={pending}>
 <motion.div className="cb-card cb-permission2-request" layout={!reduce}><motion.div layout={reduce?false:'position'}>
 <div className="cb-permission2-heading"><IconShieldCheck size={24}/><div className="cb-grow"><strong>{title}</strong>{context&&<small>{context}</small>}</div>{headerAction}</div>
 {reason&&<blockquote>{reason}</blockquote>}<div className="cb-permission2-content"><dl className="pw-scope-fields">{fields?.map(field=><div key={field.label}><dt>{field.label}</dt><dd>{field.value}</dd></div>)}</dl>{children}</div>
 {error&&<p className="pw-error" role="alert">{error}</p>}<div className="cb-permission2-actions"><div className="cb-row"><span className="cb-grow"/>{onReject&&<Button variant="secondary" onClick={onReject} disabled={pending}>{rejectLabel}</Button>}{onApprove&&<PressButton onClick={onApprove} disabled={pending} label={pending?'正在提交…':approveLabel}/>}</div></div>
 </motion.div></motion.div></motion.section>
}
