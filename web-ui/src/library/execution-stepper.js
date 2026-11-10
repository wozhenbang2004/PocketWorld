/** PW-09. Controlled product adapter of the saved Nick-reference Web Component.
 * Source: library independent reproduction 2026-10-30, not Nick author's source.
 * Retains the source layout and measured curved spine with keyed nested controls. Removes DEMO_STEPS,
 * autoplay, play/seek clock and automatic completion; every state/duration is provided.
 */
const SVG_NS='http://www.w3.org/2000/svg';
const glyphs={pending:'',complete:'<svg aria-hidden="true" viewBox="0 0 16 16"><path d="m3.4 8.2 3 3.1 6.2-6.4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',running:'<svg aria-hidden="true" viewBox="0 0 16 16"><path class="clock-sweep" d="M8 2v6l-3.5 4A5.4 5.4 0 0 0 8 2Z" fill="currentColor"/><path d="M8 3v5H5" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>',locked:'<svg aria-hidden="true" viewBox="0 0 16 16"><rect x="4" y="7" width="8" height="6.5" rx="1" fill="currentColor"/><path d="M5.6 7V5a2.4 2.4 0 1 1 4.8 0v2" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>',returned:'<span aria-hidden="true">↩</span>',unknown:'<span class="node-question" aria-hidden="true">?</span>',error:'<span class="node-question" aria-hidden="true">!</span>'};
const labels={pending:'待执行',running:'执行中',complete:'已完成',returned:'已返回',unknown:'待核对',waiting:'等待中',needs_approval:'需授权',error:'失败',stopped:'已停止'};
const normalize=s=>({success:'complete',completed:'complete',failed:'error',working:'running',waiting_approval:'needs_approval',locked:'needs_approval',cancelled:'stopped'}[s]||s||'unknown');
export const AGENT_STEPPER_CSS = "\n:host{--stepper-background:#131313;--stepper-border:#292929;--stepper-text:#e8e8e8;--stepper-muted:#828282;--stepper-pending:#4d4d4d;--stepper-line:#383838;--stepper-blue:#909090;--stepper-gold:#b2b2b2;--stepper-duration:340ms;display:block;width:352px;max-width:100%;font-family:var(--pw-font-ui,Inter,sans-serif);color:var(--stepper-text);color-scheme:dark;box-sizing:border-box}\n*,*::before,*::after{box-sizing:border-box}.panel{position:relative;padding:12px 16px;border:1px solid var(--stepper-border);border-radius:19px;background:var(--stepper-background);isolation:isolate;box-shadow:inset 0 1px 0 #ffffff03;overflow:hidden}.spine{position:absolute;inset:0;pointer-events:none;z-index:-1;width:100%;height:100%;overflow:visible}.spine path{fill:none;stroke:var(--stepper-line);stroke-width:1.3;stroke-linecap:round;stroke-linejoin:round}.spine path.complete{stroke:var(--stepper-blue);stroke-width:1.7}.step{position:relative}.row{display:grid;grid-template-columns:16px minmax(0,1fr) auto;column-gap:20px;align-items:center;min-height:32px}.label{font-size:14px;font-weight:400;line-height:20px;white-space:normal;overflow-wrap:anywhere;color:var(--stepper-pending);transition:color 220ms ease}.step[data-status=running]>.row .label,.step[data-status=locked]>.row .label{color:var(--stepper-text)}.step[data-status=complete]>.row .label{color:var(--stepper-muted)}.node{width:16px;height:16px;display:grid;place-items:center;position:relative;border:1.4px solid #414141;border-radius:50%;background:var(--stepper-background);color:white;transition:background 180ms ease,border-color 180ms ease;flex-shrink:0}.node svg{width:11px;height:11px;display:block}.step[data-status=complete]>.row .node{background:var(--stepper-blue);border-color:var(--stepper-blue)}.step[data-status=running]>.row .node{border-color:var(--stepper-gold);color:var(--stepper-gold)}.step[data-status=locked]>.row .node{border-color:var(--stepper-blue);color:var(--stepper-blue);box-shadow:0 0 0 1px #9090901c}.step[data-status=pending]>.row .node svg{display:none}.clock-sweep{transform-origin:8px 8px;animation:clock-turn 3s linear infinite}.step[data-status=running] .clock-sweep{animation-play-state:running}:host([paused]) .clock-sweep{animation-play-state:paused}.time{font-size:13px;color:#717171;font-variant-numeric:tabular-nums;line-height:18px;display:flex;align-items:center;gap:1px;min-width:23px;justify-content:flex-end}.step[data-status=pending] .time{visibility:hidden}.step[data-status=locked] .time{display:none}.digit-window{height:18px;min-width:9px;position:relative;overflow:hidden;text-align:right}.digit{display:block;line-height:18px;animation:digit-enter 260ms cubic-bezier(.2,.75,.25,1) both}.digit.out{position:absolute;inset:0;animation:digit-leave 260ms cubic-bezier(.2,.75,.25,1) both}.badge{color:#b2b2b2;background:#262626;border-radius:20px;padding:2px 7px;font-size:10px;font-weight:500;line-height:14px;white-space:nowrap}.subtasks{display:grid;grid-template-rows:0fr;transition:grid-template-rows var(--stepper-duration) cubic-bezier(.22,.8,.26,1);opacity:.15}.subtasks.open{grid-template-rows:1fr;opacity:1}.subtasks>.clip{min-height:0;overflow:hidden}.subtask{display:grid;grid-template-rows:0fr;opacity:0;transition:grid-template-rows 280ms cubic-bezier(.22,.8,.26,1),opacity 200ms ease}.subtask.shown{grid-template-rows:1fr;opacity:1}.subtask>.clip{min-height:0;overflow:hidden}.subrow{display:grid;grid-template-columns:16px minmax(0,1fr);column-gap:20px;min-height:24px;align-items:center}.subnode{width:5px;height:5px;margin-left:18.5px;border-radius:50%;background:#606060;box-shadow:0 0 0 4px var(--stepper-background)}.sublabel{font-size:12px;line-height:18px;color:#777777;overflow-wrap:anywhere;padding:3px 0}.subtask.current .subnode{background:var(--stepper-gold);box-shadow:0 0 0 4px var(--stepper-background),0 0 9px #b2b2b220}.subtask.current .sublabel{color:#b6b6b6}.spacer{height:5px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap;border:0}@keyframes digit-enter{from{transform:translateY(110%);opacity:0}to{transform:translateY(0);opacity:1}}@keyframes digit-leave{from{transform:translateY(0);opacity:1}to{transform:translateY(-110%);opacity:0}}@keyframes clock-turn{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}.digit.out{display:none}}\n\n:host{--stepper-background:#f5f5f5;--stepper-border:#ebebeb;--stepper-text:#242424;--stepper-muted:#727272;--stepper-pending:#858585;--stepper-line:#b8b8b8;--stepper-blue:#303030;--stepper-gold:#444444;width:100%;color-scheme:light;font-family:inherit}.panel{padding:10px 14px;border:0;border-radius:12px;box-shadow:none}.row{width:100%;min-height:44px;background:none;border:0;padding:0;text-align:start;color:inherit;font:inherit}.row:focus-visible{outline:2px solid #666;outline-offset:2px;border-radius:7px}.row[aria-expanded]{cursor:pointer}.label{display:flex;flex-direction:column;font-size:14px;line-height:20px;font-weight:400}.status{font-size:12px;line-height:17px;color:#777}.node{color:#fff;background:var(--stepper-background)}.step[data-status=running]>.row .node,.step[data-status=locked]>.row .node{color:#444}.step[data-status=complete]>.row .node{color:white}.subrow{min-height:30px}.sublabel{font-size:13px;line-height:20px}.time{font-size:12px}.time[hidden]{display:none!important}.badge{color:#444;background:#e4e4e4}.step[data-status=error]>.row .node,.step[data-status=unknown]>.row .node,.step[data-status=returned]>.row .node{color:#444;border-color:#777}.node-question{font-size:11px}.subtask.current .subnode{box-shadow:0 0 0 4px var(--stepper-background)}.subtasks{transition:grid-template-rows 180ms cubic-bezier(.22,.8,.26,1),opacity 150ms;opacity:1}.subtask{transition:grid-template-rows 180ms cubic-bezier(.22,.8,.26,1),opacity 150ms}.step[data-status=pending] .time{visibility:visible}\n";

export class PocketExecutionStepper extends HTMLElement {
 constructor(){
  super();this.attachShadow({mode:'open',delegatesFocus:true});this._steps=[];this._rows=[];
  this._byId=new Map();this._disclosure=new Map();this._animations=new Set();this._interrupted=new Map();this._active=true;this._baseline=true;this._raf=0;this._until=0;
 }
 connectedCallback(){
  if(!this.hasAttribute('tabindex'))this.tabIndex=0;
  this._motionQuery=window.matchMedia('(prefers-reduced-motion: reduce)');
  this._visibility=()=>{
   const paused=!this._active||document.hidden||this._motionQuery.matches;this.toggleAttribute('paused',paused);
   if(paused){this._settle();this._baseline=true}else this._scheduleSpine();
  };
  this._keyDirection=event=>{if(event.key==='Tab')this._backward=event.shiftKey};
  this._resize=()=>{this._settle();this._scheduleSpine()};
  document.addEventListener('visibilitychange',this._visibility);document.addEventListener('keydown',this._keyDirection,true);
  window.addEventListener('resize',this._resize);
  this._motionQuery.addEventListener('change',this._visibility);
  if(!this._panel)this._build();
  this._observer=new ResizeObserver(()=>this._scheduleSpine());this._observer.observe(this._panel);this._rows.forEach(record=>this._observer.observe(record.group));
  this._visibility();this._reconcile();
 }
 disconnectedCallback(){
  this._observer?.disconnect();this._settle();this._baseline=true;
  document.removeEventListener('visibilitychange',this._visibility);document.removeEventListener('keydown',this._keyDirection,true);
  window.removeEventListener('resize',this._resize);
  this._motionQuery?.removeEventListener('change',this._visibility);
 }
 _allowed(){return this.isConnected&&this._active&&!document.hidden&&!this._motionQuery?.matches}
 _settle(){
  this._animations.forEach(animation=>animation.cancel());this._animations.clear();this._interrupted.clear();
  cancelAnimationFrame(this._raf);this._raf=0;this._until=0;
  if(this.isConnected)this._drawSpine();
 }
 _tabStops(){return Array.from(this.shadowRoot.querySelectorAll('button')).filter(button=>!button.disabled&&!button.hidden&&!button.closest('[inert]')&&button.getClientRects().length>0)}
 // Dialog focus scopes enter the same retained controls in either direction.
 focus(options){const stops=this._tabStops(),target=this._backward?stops.at(-1):stops[0];if(target)target.focus(options);else super.focus(options)}
 set active(value){this._active=value!==false;this._visibility?.()}
 set steps(value){
  const ids=new Set();
  const normalizeSteps=(list,depth=0)=>list.map((step,index)=>{
   const id=String(step.id??`${depth}:${index}`);if(ids.has(id))throw new TypeError('Duplicate step ID');ids.add(id);
   return {...step,id,label:String(step.title??step.label??''),status:normalize(step.status??step.state),depth,
    tasks:normalizeSteps(Array.isArray(step.children)?step.children:Array.isArray(step.tasks)?step.tasks:[],depth+1)};
  });
  this._steps=normalizeSteps(Array.isArray(value)?value:[]);
  for(const id of this._disclosure.keys())if(!ids.has(id))this._disclosure.delete(id);
  if(this.isConnected)this._reconcile();
 }
 resetDisclosure(){this._disclosure.clear();this._baseline=true;this._settle();if(this.isConnected)this._render()}
 _expanded(step){return typeof step.expanded==='boolean'?step.expanded:this._disclosure.has(step.id)?this._disclosure.get(step.id):['running','needs_approval','waiting','error','unknown'].includes(step.status)}
 _toggle(id){
  const record=this._byId.get(id);if(!record)return;const step=record.step;
  if(typeof step.expanded!=='boolean'){this._disclosure.set(id,!this._expanded(step));this._render()}
  this.dispatchEvent(new CustomEvent('steptoggle',{detail:{id},bubbles:true,composed:true}));
 }
 _build(){
  const style=document.createElement('style');
  style.textContent=AGENT_STEPPER_CSS+`
   .row{grid-template-columns:16px minmax(0,1fr) auto auto;column-gap:12px}
   .detail,.branch{appearance:none;border:0;background:none;color:inherit;font:inherit;text-align:start;padding:0;cursor:pointer}
   .detail{min-height:44px;min-width:0;display:flex;align-items:center}
   .detail:focus-visible,.branch:focus-visible{outline:2px solid var(--pw-focus,#666);outline-offset:2px;border-radius:7px}
   .branch{width:32px;min-height:44px;display:grid;place-items:center}.branch[hidden]{display:none}
   .branch>span{display:block;transform:rotate(0deg);transition:transform 180ms cubic-bezier(.22,.8,.26,1)}
   .branch[aria-expanded=true]>span{transform:rotate(90deg)}
   .step[data-context=true]>.row .label{opacity:.65}.label,.step[data-status=complete]>.row .label{color:var(--stepper-text)}
   .status{font-size:11px;line-height:17px}.children-list{padding-inline-start:12px}
   .step[data-deep=true]>.subtasks>.clip>.children-list{padding-inline-start:0}
   .subtasks>.clip{min-width:0}.digit{animation:none}.row{position:relative}
   :host([paused]) *, :host([paused]) *::before, :host([paused]) *::after{animation:none!important;transition:none!important}
  `;
  this._panel=document.createElement('div');this._panel.className='panel';this._panel.setAttribute('role','list');this._panel.setAttribute('aria-label','任务执行过程');
  // Keep the drawer from capturing presses aimed at Shadow DOM buttons.
  this._panel.addEventListener('pointerdown',event=>event.stopPropagation());
  this._panel.addEventListener('keydown',event=>{
   if(event.key!=='Tab')return;const stops=this._tabStops(),current=this.shadowRoot.activeElement;
   if(current!==(event.shiftKey?stops[0]:stops.at(-1)))event.stopPropagation();
  });
  this._spine=document.createElementNS(SVG_NS,'svg');this._spine.classList.add('spine');this._spine.setAttribute('aria-hidden','true');
  this._list=document.createElement('div');this._panel.append(this._spine,this._list);
  this._live=document.createElement('div');this._live.className='sr-only';this._live.setAttribute('role','status');this._live.setAttribute('aria-live','polite');
  this.shadowRoot.append(style,this._panel,this._live);
 }
 _createRow(step){
  const group=document.createElement('div');group.className='step';group.dataset.stepId=step.id;group.setAttribute('role','listitem');
  const row=document.createElement('div');row.className='row';
  const node=document.createElement('span');node.className='node';node.setAttribute('aria-hidden','true');
  const label=document.createElement('span');label.className='label';
  const title=document.createElement('span'),statusLabel=document.createElement('span');statusLabel.className='status';label.append(title,statusLabel);
  const detail=document.createElement('button');detail.type='button';detail.className='detail';detail.dataset.stepId=step.id;detail.dataset.action='select';detail.append(label);
  detail.addEventListener('click',()=>this.dispatchEvent(new CustomEvent('stepselect',{detail:{id:step.id},bubbles:true,composed:true})));
  const branch=document.createElement('button');branch.type='button';branch.className='branch';branch.dataset.stepId=step.id;branch.dataset.action='toggle';
  const chevron=document.createElement('span');chevron.textContent='›';chevron.setAttribute('aria-hidden','true');branch.append(chevron);branch.addEventListener('click',()=>this._toggle(step.id));
  const time=document.createElement('span');time.className='time';time.setAttribute('aria-hidden','true');
  const digitWindow=document.createElement('span');digitWindow.className='digit-window';const digit=document.createElement('span');digit.className='digit';digitWindow.append(digit);
  const unit=document.createElement('span');time.append(digitWindow,unit);row.append(node,detail,time,branch);
  const children=document.createElement('div');children.className='subtasks';children.id='children-'+encodeURIComponent(step.id);children.setAttribute('role','list');branch.setAttribute('aria-controls',children.id);
  const clip=document.createElement('div');clip.className='clip';const childList=document.createElement('div');childList.className='children-list';clip.append(childList);children.append(clip);
  group.append(row,children);
  return {id:step.id,step,group,row,node,title,statusLabel,detail,branch,time,digitWindow,digit,unit,children,childList,status:'',open:false};
 }
 _animate(node,frames,duration=220){
  if(!this._allowed())return;
  const animation=node.animate(frames,{duration,easing:'cubic-bezier(.22,.8,.26,1)'});this._animations.add(animation);
  animation.onfinish=()=>{this._animations.delete(animation);animation.cancel()};
  this._until=Math.max(this._until,performance.now()+duration);this._scheduleSpine();return animation;
 }
 _reconcile(){
  const focused=this.shadowRoot.activeElement,focusId=focused?.dataset.stepId,action=focused?.dataset.action;
  const oldRows=this._rows,before=new Map(),oldIndex=oldRows.findIndex(row=>row.id===focusId);
  const base=this._panel.getBoundingClientRect();
  if(this._allowed()&&!this._baseline)for(const record of oldRows){
   if(record.row.closest('[inert]'))continue;const rect=record.row.getBoundingClientRect();
   if(rect.width&&rect.height)before.set(record.id,{x:rect.left-base.left,y:rect.top-base.top});
  }
  this._interrupted.clear();
  this._animations.forEach(animation=>{
   const target=animation.effect?.target;
   if(animation.playState==='running'&&target?.isConnected){const style=getComputedStyle(target);this._interrupted.set(target,{opacity:style.opacity,transform:style.transform})}
   animation.cancel();
  });this._animations.clear();
  const known=this._byId,next=new Map(),rows=[],fresh=new Set();
  const reconcile=(parent,steps)=>{
   let cursor=parent.firstElementChild;
   for(const step of steps){
    const record=known.get(step.id)||this._createRow(step);if(!known.has(step.id))fresh.add(step.id);
    record.step=step;next.set(step.id,record);rows.push(record);
    if(record.group!==cursor)parent.insertBefore(record.group,cursor);cursor=record.group.nextElementSibling;
    reconcile(record.childList,step.tasks);
   }
  };
  reconcile(this._list,this._steps);
  for(const [id,record] of known)if(!next.has(id)){this._observer?.unobserve(record.group);record.group.remove()}
  this._byId=next;this._rows=rows;
  for(const id of fresh)this._observer?.observe(next.get(id).group);
  const disclosureChanged=this._render();
  if(before.size){
   const panel=this._panel.getBoundingClientRect();
   for(const record of rows){
    if(record.row.closest('[inert]'))continue;const rect=record.row.getBoundingClientRect();if(!rect.width||!rect.height)continue;
    const previous=before.get(record.id);
    // CSS grid owns disclosure height. Retained rows only FLIP when its layout
    // is settled; two owners must not animate the same displacement.
    if(previous&&!disclosureChanged){const x=previous.x-(rect.left-panel.left),y=previous.y-(rect.top-panel.top);
     if(Math.abs(x)>.5||Math.abs(y)>.5)this._animate(record.row,[{transform:`translate(${x}px,${y}px)`},{transform:'translate(0,0)'}],280);
    }else if(fresh.has(record.id))this._animate(record.row,[{opacity:0,transform:'translateY(5px)'},{opacity:1,transform:'translateY(0)'}]);
   }
  }
  if(focusId&&(!focused?.isConnected||focused.hidden||focused.closest('[inert]')||this.shadowRoot.activeElement!==focused)){
   const same=next.get(focusId);let ancestor=same?.group.parentElement?.closest('.step');
   while(ancestor&&ancestor.closest('[inert]'))ancestor=ancestor.parentElement?.closest('.step');
   const target=ancestor?next.get(ancestor.dataset.stepId)?.branch:same&&!same.row.closest('[inert]')?(action==='toggle'&&!same.branch.hidden?same.branch:same.detail):rows[Math.min(Math.max(oldIndex,0),rows.length-1)]?.detail;
   if(target&&!target.hidden&&!target.closest('[inert]'))target.focus({preventScroll:true});else super.focus({preventScroll:true});
  }
  if(rows.length)this._baseline=false;
  this._scheduleSpine();
 }
 _render(){
  let disclosureChanged=false;const animate=this._allowed()&&!this._baseline;
  for(const record of this._rows){
   const step=record.step,status=step.status,visual=status==='needs_approval'?'locked':status;
   const hasChildren=step.tasks.length>0,open=hasChildren&&this._expanded(step);
   if(record.status!==visual){record.node.innerHTML=glyphs[visual]||glyphs.pending;record.status=visual;record.group.dataset.status=visual}
   record.group.dataset.context=String(step.context===true);record.group.dataset.deep=String(step.depth>=5);
   record.group.setAttribute('aria-level',String(step.depth+1));
   const statusText=step.statusLabel||labels[status]||status;
   if(record.title.textContent!==step.label)record.title.textContent=step.label;
   if(record.statusLabel.textContent!==statusText){
    const previous=record.statusLabel.textContent;record.statusLabel.textContent=statusText;
    if(previous&&animate)this._animate(record.statusLabel,[this._interrupted.get(record.statusLabel)||{opacity:.35},{opacity:1,transform:'none'}],140);
   }
   record.detail.setAttribute('aria-label',`查看 ${step.label} 的调用详情`);
   const duration=typeof step.durationMs==='number'&&Number.isFinite(step.durationMs)&&step.durationMs>=0?step.durationMs:null;
   record.time.hidden=duration===null;
   if(duration!==null){
    const text=step.durationLabel||(duration/1000).toFixed(1);
    if(record.digit.textContent!==text){const previous=record.digit.textContent;record.digit.textContent=text;if(previous&&animate)this._animate(record.digit,[this._interrupted.get(record.digit)||{opacity:.35,transform:'translateY(5px)'},{opacity:1,transform:'translateY(0)'}],180)}
    record.unit.textContent=step.durationLabel?'':'s';
   }
   record.group.setAttribute('aria-label',step.label+'，'+statusText+(duration===null?'':`，${step.durationLabel||`${duration}毫秒`}`));
   record.branch.hidden=!hasChildren||step.canToggle===false;record.branch.setAttribute('aria-label',`${open?'折叠':'展开'} ${step.label}`);
   if(hasChildren)record.branch.setAttribute('aria-expanded',String(open));else record.branch.removeAttribute('aria-expanded');
   if(record.open!==open){disclosureChanged=true;record.open=open;this._until=Math.max(this._until,performance.now()+340)}
   record.children.classList.toggle('open',open);record.children.setAttribute('aria-hidden',String(!open));record.children.inert=!open;
  }
  const current=this._rows.find(record=>!record.row.closest('[inert]')&&record.status==='running')||this._rows.find(record=>!record.row.closest('[inert]')&&record.status==='locked')||this._byId.get(this._currentId);
  const announcement=current&&!current.row.closest('[inert]')?`${current.step.label}，${current.step.statusLabel||labels[current.step.status]||current.step.status}`:'';
  if(current)this._currentId=current.id;if(this._live.textContent!==announcement)this._live.textContent=announcement;
  this._scheduleSpine();return disclosureChanged;
 }
 _scheduleSpine(){
  if(!this.isConnected||this._raf)return;
  this._raf=requestAnimationFrame(()=>{this._raf=0;this._drawSpine();if(this._allowed()&&performance.now()<this._until)this._scheduleSpine()});
 }
 _drawSpine(){
  if(!this.isConnected||!this._panel)return;
  const panel=this._panel.getBoundingClientRect();if(!panel.width||!panel.height)return;
  const rows=this._rows.filter(record=>!record.row.closest('[inert]'));
  const centers=rows.map(record=>{const rect=record.node.getBoundingClientRect();return{x:rect.left-panel.left+rect.width/2,y:rect.top-panel.top+rect.height/2}});
  this._spine.setAttribute('viewBox',`0 0 ${panel.width} ${panel.height}`);
  while(this._spine.children.length>Math.max(0,centers.length-1))this._spine.lastElementChild.remove();
  for(let index=0;index<centers.length-1;index++){
   const a=centers[index],b=centers[index+1],record=rows[index];
   const childHeight=record.children.getBoundingClientRect().height,offset=Math.min(13,Math.max(0,childHeight/2));
   const x=a.x,inset=x+offset,top=a.y+15,bottom=b.y-15,bend=Math.min(9,Math.max(0,(bottom-top)/3)),middle=(a.y+b.y)/2;
   const d=Math.abs(a.x-b.x)>.1?`M ${a.x} ${a.y} V ${middle-6} C ${a.x} ${middle+6} ${b.x} ${middle-6} ${b.x} ${middle+6} V ${b.y}`:offset>.1&&bottom>top?`M ${x} ${a.y} V ${top-bend} C ${x} ${top+2} ${inset} ${top-2} ${inset} ${top+bend} V ${bottom-bend} C ${inset} ${bottom+2} ${x} ${bottom-2} ${x} ${bottom+bend} V ${b.y}`:`M ${x} ${a.y} V ${b.y}`;
   let path=this._spine.children[index];if(!path){path=document.createElementNS(SVG_NS,'path');this._spine.append(path)}
   if(path.getAttribute('d')!==d)path.setAttribute('d',d);path.classList.toggle('complete',record.status==='complete');
  }
 }
}
if(!customElements.get('pocket-execution-steps'))customElements.define('pocket-execution-steps',PocketExecutionStepper);
