/** PW-09. Controlled product adapter of the saved Nick-reference Web Component.
 * Source: library independent reproduction 2026-10-30, not Nick author's source.
 * Retains original CSS, DOM builder and measured curved spine. Removes DEMO_STEPS,
 * autoplay, play/seek clock and automatic completion; every state/duration is provided.
 */
const SVG_NS='http://www.w3.org/2000/svg';
const glyphs={pending:'',complete:'<svg aria-hidden="true" viewBox="0 0 16 16"><path d="m3.4 8.2 3 3.1 6.2-6.4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',running:'<svg aria-hidden="true" viewBox="0 0 16 16"><path class="clock-sweep" d="M8 2v6l-3.5 4A5.4 5.4 0 0 0 8 2Z" fill="currentColor"/><path d="M8 3v5H5" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>',locked:'<svg aria-hidden="true" viewBox="0 0 16 16"><rect x="4" y="7" width="8" height="6.5" rx="1" fill="currentColor"/><path d="M5.6 7V5a2.4 2.4 0 1 1 4.8 0v2" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>',returned:'<span aria-hidden="true">↩</span>',unknown:'<span class="node-question" aria-hidden="true">?</span>',error:'<span class="node-question" aria-hidden="true">!</span>'};
const labels={pending:'待执行',running:'执行中',complete:'已完成',returned:'已返回',unknown:'待核对',waiting:'等待中',needs_approval:'需授权',error:'失败',stopped:'已停止'};
const normalize=s=>({success:'complete',completed:'complete',failed:'error',working:'running',waiting_approval:'needs_approval',locked:'needs_approval',cancelled:'stopped'}[s]||s||'unknown');
export const AGENT_STEPPER_CSS = "\n:host{--stepper-background:#131313;--stepper-border:#292929;--stepper-text:#e8e8e8;--stepper-muted:#828282;--stepper-pending:#4d4d4d;--stepper-line:#383838;--stepper-blue:#909090;--stepper-gold:#b2b2b2;--stepper-duration:340ms;display:block;width:352px;max-width:100%;font-family:var(--pw-font-ui,Inter,sans-serif);color:var(--stepper-text);color-scheme:dark;box-sizing:border-box}\n*,*::before,*::after{box-sizing:border-box}.panel{position:relative;padding:12px 16px;border:1px solid var(--stepper-border);border-radius:19px;background:var(--stepper-background);isolation:isolate;box-shadow:inset 0 1px 0 #ffffff03;overflow:hidden}.spine{position:absolute;inset:0;pointer-events:none;z-index:-1;width:100%;height:100%;overflow:visible}.spine path{fill:none;stroke:var(--stepper-line);stroke-width:1.3;stroke-linecap:round;stroke-linejoin:round}.spine path.complete{stroke:var(--stepper-blue);stroke-width:1.7}.step{position:relative}.row{display:grid;grid-template-columns:16px minmax(0,1fr) auto;column-gap:20px;align-items:center;min-height:32px}.label{font-size:14px;font-weight:400;line-height:20px;white-space:normal;overflow-wrap:anywhere;color:var(--stepper-pending);transition:color 220ms ease}.step[data-status=running]>.row .label,.step[data-status=locked]>.row .label{color:var(--stepper-text)}.step[data-status=complete]>.row .label{color:var(--stepper-muted)}.node{width:16px;height:16px;display:grid;place-items:center;position:relative;border:1.4px solid #414141;border-radius:50%;background:var(--stepper-background);color:white;transition:background 180ms ease,border-color 180ms ease;flex-shrink:0}.node svg{width:11px;height:11px;display:block}.step[data-status=complete]>.row .node{background:var(--stepper-blue);border-color:var(--stepper-blue)}.step[data-status=running]>.row .node{border-color:var(--stepper-gold);color:var(--stepper-gold)}.step[data-status=locked]>.row .node{border-color:var(--stepper-blue);color:var(--stepper-blue);box-shadow:0 0 0 1px #9090901c}.step[data-status=pending]>.row .node svg{display:none}.clock-sweep{transform-origin:8px 8px;animation:clock-turn 3s linear infinite}.step[data-status=running] .clock-sweep{animation-play-state:running}:host([paused]) .clock-sweep{animation-play-state:paused}.time{font-size:13px;color:#717171;font-variant-numeric:tabular-nums;line-height:18px;display:flex;align-items:center;gap:1px;min-width:23px;justify-content:flex-end}.step[data-status=pending] .time{visibility:hidden}.step[data-status=locked] .time{display:none}.digit-window{height:18px;min-width:9px;position:relative;overflow:hidden;text-align:right}.digit{display:block;line-height:18px;animation:digit-enter 260ms cubic-bezier(.2,.75,.25,1) both}.digit.out{position:absolute;inset:0;animation:digit-leave 260ms cubic-bezier(.2,.75,.25,1) both}.badge{color:#b2b2b2;background:#262626;border-radius:20px;padding:2px 7px;font-size:10px;font-weight:500;line-height:14px;white-space:nowrap}.subtasks{display:grid;grid-template-rows:0fr;transition:grid-template-rows var(--stepper-duration) cubic-bezier(.22,.8,.26,1);opacity:.15}.subtasks.open{grid-template-rows:1fr;opacity:1}.subtasks>.clip{min-height:0;overflow:hidden}.subtask{display:grid;grid-template-rows:0fr;opacity:0;transition:grid-template-rows 280ms cubic-bezier(.22,.8,.26,1),opacity 200ms ease}.subtask.shown{grid-template-rows:1fr;opacity:1}.subtask>.clip{min-height:0;overflow:hidden}.subrow{display:grid;grid-template-columns:16px minmax(0,1fr);column-gap:20px;min-height:24px;align-items:center}.subnode{width:5px;height:5px;margin-left:18.5px;border-radius:50%;background:#606060;box-shadow:0 0 0 4px var(--stepper-background)}.sublabel{font-size:12px;line-height:18px;color:#777777;overflow-wrap:anywhere;padding:3px 0}.subtask.current .subnode{background:var(--stepper-gold);box-shadow:0 0 0 4px var(--stepper-background),0 0 9px #b2b2b220}.subtask.current .sublabel{color:#b6b6b6}.spacer{height:5px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap;border:0}@keyframes digit-enter{from{transform:translateY(110%);opacity:0}to{transform:translateY(0);opacity:1}}@keyframes digit-leave{from{transform:translateY(0);opacity:1}to{transform:translateY(-110%);opacity:0}}@keyframes clock-turn{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}.digit.out{display:none}}\n\n:host{--stepper-background:#f5f5f5;--stepper-border:#ebebeb;--stepper-text:#242424;--stepper-muted:#727272;--stepper-pending:#858585;--stepper-line:#b8b8b8;--stepper-blue:#303030;--stepper-gold:#444444;width:100%;color-scheme:light;font-family:inherit}.panel{padding:10px 14px;border:0;border-radius:12px;box-shadow:none}.row{width:100%;min-height:44px;background:none;border:0;padding:0;text-align:start;color:inherit;font:inherit}.row:focus-visible{outline:2px solid #666;outline-offset:2px;border-radius:7px}.row[aria-expanded]{cursor:pointer}.label{display:flex;flex-direction:column;font-size:14px;line-height:20px;font-weight:400}.status{font-size:12px;line-height:17px;color:#777}.node{color:#fff;background:var(--stepper-background)}.step[data-status=running]>.row .node,.step[data-status=locked]>.row .node{color:#444}.step[data-status=complete]>.row .node{color:white}.subrow{min-height:30px}.sublabel{font-size:13px;line-height:20px}.time{font-size:12px}.time[hidden]{display:none!important}.badge{color:#444;background:#e4e4e4}.step[data-status=error]>.row .node,.step[data-status=unknown]>.row .node,.step[data-status=returned]>.row .node{color:#444;border-color:#777}.node-question{font-size:11px}.subtask.current .subnode{box-shadow:0 0 0 4px var(--stepper-background)}.subtasks{transition:grid-template-rows 180ms cubic-bezier(.22,.8,.26,1),opacity 150ms;opacity:1}.subtask{transition:grid-template-rows 180ms cubic-bezier(.22,.8,.26,1),opacity 150ms}.step[data-status=pending] .time{visibility:visible}\n";

export class PocketExecutionStepper extends HTMLElement {
 constructor(){super();this.attachShadow({mode:'open',delegatesFocus:true});this._steps=[];this._rows=[];this._disclosure=new Map();this._structure='';this._active=true}
 connectedCallback(){if(!this.hasAttribute('tabindex'))this.tabIndex=0;this._build();this._observe();this._visibility=()=>this.toggleAttribute('paused',!this._active||document.hidden);document.addEventListener('visibilitychange',this._visibility);this._keyDirection=event=>{if(event.key==='Tab')this._backward=event.shiftKey};document.addEventListener('keydown',this._keyDirection,true);this._visibility()}
 disconnectedCallback(){this._observer?.disconnect();document.removeEventListener('visibilitychange',this._visibility);document.removeEventListener('keydown',this._keyDirection,true)}
 _tabStops(){return Array.from(this.shadowRoot.querySelectorAll('button')).filter(button=>!button.disabled&&!button.hidden&&button.getClientRects().length>0)}
 // FocusScope calls host.focus() when wrapping. Preserve backward entry at
 // the last inner control rather than delegating every entry to the first.
 focus(options){const stops=this._tabStops(),target=this._backward?stops.at(-1):stops[0];if(target)target.focus(options);else super.focus(options)}
 set active(value){this._active=value!==false;this.toggleAttribute('paused',!this._active||document.hidden)}
 _observe(){this._observer?.disconnect();if(!this._panel)return;this._observer=new ResizeObserver(()=>this._drawSpine());this._observer.observe(this._panel);this._rows.forEach(r=>this._observer.observe(r.group))}
 set steps(value){
  const list=Array.isArray(value)?value:[];const ids=new Set();const next=list.map((s,i)=>{const id=String(s.id??i);if(ids.has(id))throw new TypeError('Duplicate step ID');ids.add(id);const tasks=(s.children||s.tasks||[]).map((c,j)=>({...c,id:String(c.id??j),label:String(c.title??c.label??''),status:normalize(c.status??c.state)}));return {...s,id,label:String(s.title??s.label??''),status:normalize(s.status??s.state),tasks}});
  const structure=JSON.stringify(next.map(s=>[s.id,s.tasks.map(t=>t.id),s.depth,s.hasChildren]));this._steps=next;for(const key of this._disclosure.keys())if(!ids.has(key))this._disclosure.delete(key);
  if(this.isConnected){if(structure!==this._structure){const focused=this.shadowRoot.activeElement;const focusId=focused?.dataset.stepId,action=focused?.dataset.action;this._structure=structure;this._build();this._observe();if(focusId)Array.from(this.shadowRoot.querySelectorAll('button')).find(button=>button.dataset.stepId===focusId&&button.dataset.action===action)?.focus({preventScroll:true})}else this._render(true)}
 }
 resetDisclosure(){this._disclosure.clear();if(this.isConnected)this._render(true)}
 _expanded(step){return typeof step.expanded==='boolean'?step.expanded:this._disclosure.has(step.id)?this._disclosure.get(step.id):['running','needs_approval','waiting','error','unknown'].includes(step.status)}
 _toggle(step){if(typeof step.expanded!=='boolean'){this._disclosure.set(step.id,!this._expanded(step));this._render()}this.dispatchEvent(new CustomEvent('steptoggle',{detail:{id:step.id},bubbles:true,composed:true}))}
  _build() {
    this.shadowRoot.replaceChildren();
    const style = document.createElement('style');
    style.textContent = AGENT_STEPPER_CSS + '\n.row{grid-template-columns:16px minmax(0,1fr) auto auto;column-gap:12px}.detail,.branch{appearance:none;border:0;background:none;color:inherit;font:inherit;text-align:start;padding:0;cursor:pointer}.detail{min-height:44px;min-width:0;display:flex;align-items:center}.detail:focus-visible,.branch:focus-visible{outline:2px solid #666;outline-offset:2px;border-radius:7px}.branch{width:32px;min-height:44px;text-align:center;font-size:18px}.branch[hidden]{display:none}.step[data-context=true]>.row .label{opacity:.65}.label{color:var(--stepper-text)}.status{font-size:11px;line-height:17px}.step[data-status=complete]>.row .label{color:var(--stepper-text)}';
    this._panel = document.createElement('div');
    this._panel.className = 'panel';
    this._panel.setAttribute('role','list');
    // Vaul sees the retargeted host as pointerdown.target and captures it,
    // which otherwise redirects pointerup/click away from Shadow DOM buttons.
    this._panel.addEventListener('pointerdown',event=>event.stopPropagation());
    // Dialog focus scopes see the host as one tab stop. Let their boundary
    // handling run only when Tab actually leaves this component; internal
    // navigation remains the browser's native button order.
    this._panel.addEventListener('keydown',event=>{
      if(event.key!=='Tab')return;
      const stops=this._tabStops();
      const current=this.shadowRoot.activeElement;
      if(current!==(event.shiftKey?stops[0]:stops.at(-1)))event.stopPropagation();
    });
    this._spine = document.createElementNS(SVG_NS,'svg');
    this._spine.classList.add('spine');
    this._spine.setAttribute('aria-hidden','true');
    this._panel.append(this._spine);
    this._rows = this._steps.map(step => {
      const group = document.createElement('div'); group.className = 'step'; group.setAttribute('role','listitem');
      const row = document.createElement('div'); row.className = 'row';
      const node = document.createElement('span'); node.className = 'node'; node.setAttribute('aria-hidden','true');
      const label = document.createElement('span'); label.className = 'label';
      const detail = document.createElement('button'); detail.type='button'; detail.className='detail'; detail.dataset.stepId=step.id; detail.dataset.action='select'; detail.append(label);
      detail.addEventListener('click',()=>this.dispatchEvent(new CustomEvent('stepselect',{detail:{id:step.id},bubbles:true,composed:true})));
      const branch = document.createElement('button'); branch.type='button'; branch.className='branch'; branch.dataset.stepId=step.id; branch.dataset.action='toggle'; branch.addEventListener('click',()=>this._toggle(this._steps.find(value=>value.id===step.id)||step));
      const time = document.createElement('span'); time.className = 'time'; time.setAttribute('aria-hidden','true');
      const digitWindow = document.createElement('span'); digitWindow.className = 'digit-window';
      const unit = document.createElement('span'); unit.textContent = 's';
      time.append(digitWindow,unit);
      row.append(node,detail,time,branch);
      const badge = document.createElement('span'); badge.className='badge'; badge.hidden=true; row.append(badge);
      const children = document.createElement('div'); children.className='subtasks'; children.setAttribute('aria-hidden','true');
      const clip = document.createElement('div'); clip.className='clip';
      const top = document.createElement('div'); top.className='spacer'; clip.append(top);
      const tasks = step.tasks.map(() => {
        const item = document.createElement('div'); item.className='subtask';
        const inner = document.createElement('div'); inner.className='clip';
        const subrow = document.createElement('div'); subrow.className='subrow';
        const subnode = document.createElement('span'); subnode.className='subnode';
        const sublabel = document.createElement('span'); sublabel.className='sublabel';
        subrow.append(subnode,sublabel); inner.append(subrow); item.append(inner); clip.append(item);
        return {item,subnode,sublabel};
      });
      const bottom = document.createElement('div'); bottom.className='spacer'; clip.append(bottom); children.append(clip);
      group.append(row,children); this._panel.append(group);
      return {group,row,node,label,detail,branch,badge,time,digitWindow,unit,children,tasks,status:'',second:-1};
    });
    this._live = document.createElement('div'); this._live.className='sr-only'; this._live.setAttribute('role','status'); this._live.setAttribute('aria-live','polite');
    this.shadowRoot.append(style,this._panel,this._live);
    this._render(true);
    requestAnimationFrame(() => this._drawSpine());
  }

 _render(){
  if(!this._rows)return;
  this._panel.setAttribute('aria-label','任务执行过程');
  this._steps.forEach((step,i)=>{
   const r=this._rows[i];if(!r)return;
   const status=step.status,visual=status==='needs_approval'?'locked':status;
   const hasChildren=typeof step.hasChildren==='boolean'?step.hasChildren:step.tasks.length>0,open=hasChildren&&this._expanded(step);
   r.status=visual;r.group.dataset.status=visual;r.group.dataset.stepId=step.id;
   r.group.dataset.context=String(step.context===true);
   const depth=Number.isSafeInteger(step.depth)&&step.depth>=0?step.depth:0;
   r.group.style.paddingInlineStart=`${Math.min(depth,5)*12}px`;r.group.setAttribute('aria-level',String(depth+1));
   r.node.innerHTML=glyphs[visual]||glyphs.pending;r.label.replaceChildren();
   const title=document.createElement('span');title.textContent=step.label;
   const statusNode=document.createElement('span');statusNode.className='status';statusNode.textContent=step.statusLabel||labels[status]||status;
   r.label.append(title,statusNode);
   r.detail.setAttribute('aria-label',`查看 ${step.label} 的调用详情`);
   const duration=typeof step.durationMs==='number'&&Number.isFinite(step.durationMs)&&step.durationMs>=0?step.durationMs:null;
   r.time.hidden=duration===null;if(duration!==null){r.digitWindow.textContent=step.durationLabel||(duration/1000).toFixed(1);r.unit.textContent=step.durationLabel?'':'s'}
   r.group.setAttribute('aria-label',step.label+'，'+(step.statusLabel||labels[status]||status)+(duration===null?'':`，${step.durationLabel||`${duration}毫秒`}`));
   r.branch.hidden=!hasChildren||step.canToggle===false;r.branch.textContent=open?'⌄':'›';r.branch.setAttribute('aria-label',`${open?'折叠':'展开'} ${step.label}`);
   if(hasChildren)r.branch.setAttribute('aria-expanded',String(open));else r.branch.removeAttribute('aria-expanded');
   const showTasks=open&&step.tasks.length>0;
   r.children.classList.toggle('open',showTasks);r.children.setAttribute('aria-hidden',String(!showTasks));r.children.inert=!showTasks;
   r.tasks.forEach((t,j)=>{const child=step.tasks[j];t.item.classList.add('shown');t.item.classList.toggle('current',child.status==='running');t.sublabel.textContent=child.label+' · '+(child.statusLabel||labels[child.status]||child.status);t.item.setAttribute('aria-hidden',String(!open))});
   r.badge.hidden=status!=='needs_approval';r.badge.textContent=status==='needs_approval'?'需确认':'';
  });
  requestAnimationFrame(()=>this._drawSpine());
 }
  _drawSpine() {
    if (!this.isConnected || !this._rows?.length) return;
    const panel=this._panel.getBoundingClientRect();
    const centers=this._rows.map(r=>{const b=r.node.getBoundingClientRect();return{x:b.left-panel.left+b.width/2,y:b.top-panel.top+b.height/2};});
    this._spine.setAttribute('viewBox',`0 0 ${panel.width} ${panel.height}`);
    this._spine.replaceChildren();
    for(let i=0;i<centers.length-1;i++) {
      const a=centers[i],b=centers[i+1],r=this._rows[i];
      const childHeight=r.children.getBoundingClientRect().height;
      const offset=Math.min(13,Math.max(0,childHeight/2));
      const x=a.x, inset=x+offset;
      const top=a.y+15, bottom=b.y-15;
      const bend=Math.min(9,Math.max(0,(bottom-top)/3));
      const middle=(a.y+b.y)/2;
      const d=Math.abs(a.x-b.x)>.1?`M ${a.x} ${a.y} V ${middle-6} C ${a.x} ${middle+6} ${b.x} ${middle-6} ${b.x} ${middle+6} V ${b.y}`:offset>.1 && bottom>top ? `M ${x} ${a.y} V ${top-bend} C ${x} ${top+2} ${inset} ${top-2} ${inset} ${top+bend} V ${bottom-bend} C ${inset} ${bottom+2} ${x} ${bottom-2} ${x} ${bottom+bend} V ${b.y}` : `M ${x} ${a.y} V ${b.y}`;
      const path=document.createElementNS(SVG_NS,'path'); path.setAttribute('d',d);
      if(r.status==='complete') path.classList.add('complete');
      this._spine.append(path);
    }
  }
}
if(!customElements.get("pocket-execution-steps"))customElements.define("pocket-execution-steps",PocketExecutionStepper);
