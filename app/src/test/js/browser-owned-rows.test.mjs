// Exact production programs on explicit composed DOM interfaces, NOT real-browser evidence.
import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';import {randomUUID} from 'node:crypto';
const scripts=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),baseline=process.argv.includes('--expect-owner-failure'),samples=[];
class TextNode{constructor(text){this.nodeType=3;this.nodeValue=text}}
class Element{
 constructor(tag,attrs={},children=[]){this.nodeType=1;this.tagName=tag;this.attrs=attrs;this.childNodes=[];this.isConnected=true;for(const child of children)this.append(child)}
 append(child){if(typeof child==='string')child=new TextNode(child);this.childNodes.push(child);child.parentElement=this;child.parentNode=this;return child}
 get children(){return this.childNodes.filter(x=>x.nodeType===1)}getAttribute(k){return this.attrs[k]??null}hasAttribute(k){return k in this.attrs}getClientRects(){return[{}]}getRootNode(){return this.domRoot}get href(){return''}assignedNodes(){return this.assigned||[]}
}
const el=(role,children=[],attrs={})=>new Element('DIV',{role,...attrs},children),row=text=>el('row',[el('gridcell',[text])]);
function shadow(host,children){const root={host,childNodes:children,addEventListener(){},removeEventListener(){}};host.shadowRoot=root;for(const c of children){c.parentElement=null;c.parentNode=root}return root}
function scene(target){
 const body=new Element('BODY',{},[target]),document={body,documentElement:body,title:'composed ownership interface',getElementById(){return null}},observers=[];
 function bind(node,tree=document){node.ownerDocument=document;node.domRoot=tree;for(const c of node.childNodes||[])bind(c,tree);if(node.shadowRoot)for(const c of node.shadowRoot.childNodes)bind(c,node.shadowRoot)}bind(body);
 const c={document,location:{href:'https://fixture.invalid/read'},URL,Date,MutationObserver:class{constructor(cb){this.cb=cb;this.active=true;observers.push(this)}observe(){}disconnect(){this.active=false}takeRecords(){return[]}},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};c.window=c;vm.createContext(c);return c;
}
function run(kind,c,cursor=null,region=null){
 let code=scripts[kind].replaceAll('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',randomUUID());
 if(cursor)code=code.replaceAll('cccccccc-cccc-4ccc-8ccc-cccccccccccc:c0',cursor);if(region)code=code.replaceAll('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb:r1',region);
 const raw=JSON.parse(vm.runInContext(code,c,{timeout:2000}));samples.push({kind,raw});
 if(raw.read){const f=raw.read.facts,w=f.captureWork;assert.equal(raw.read.data.length,f.returnedItems);assert(w.nodesVisited<=6000&&w.textCharsVisited<=180000&&w.layoutChecks<=6000&&w.queuedPeak<=6000)}return raw;
}
function selected(mode,c){const outline=run('outline',c),region=outline.read.data.find(x=>x.name==='Selected')?.regionRef;assert(region);return run('region_'+mode,c,null,region)}
function complete(raw){assert.equal(raw.status,'observed');assert.equal(raw.read.cursor,null);assert.equal(raw.read.facts.partial,false,JSON.stringify(raw.read.facts))}
const texts=raw=>raw.read.data.map(x=>x.cells.map(c=>c.text).join(' | '));let passed=0;
function check(name,fn){fn();passed++;console.log('PASS '+name)}
for(const mode of ['table','list']){
 const target=el(mode==='table'?'grid':'list',[],{'aria-label':'Selected'});shadow(target,mode==='table'?[row('Shadow 0'),row('Shadow 1')]:[el('listitem',['Shadow 0']),el('listitem',['Shadow 1'])]);const raw=selected(mode,scene(target));complete(raw);
 if(baseline)assert.deepEqual(raw.read.data,[],'baseline no longer reproduces direct-shadow row loss');else check('direct open-shadow '+mode+' rows belong to the selected host',()=>assert.deepEqual(mode==='table'?texts(raw):raw.read.data.map(x=>x.text),['Shadow 0','Shadow 1']));
}
if(baseline){if(process.argv[3])fs.writeFileSync(process.argv[3],JSON.stringify({status:'EXPECTED_SHADOW_OWNER_FAILURE',platform:'synthetic_composed_dom_not_browser',missingRowsWithPartialFalse:true,samples}));console.error('EXPECTED_SHADOW_OWNER_FAILURE: direct shadow rows/items disappeared with partial=false');process.exit(3)}
check('slot and shadow rowgroup ancestry reaches the selected grid',()=>{const item=row('Slotted'),slot=new Element('SLOT'),target=el('grid',[item],{'aria-label':'Selected'});item.assignedSlot=slot;slot.assigned=[item];shadow(target,[el('rowgroup',[slot])]);const raw=selected('table',scene(target));complete(raw);assert.deepEqual(texts(raw),['Slotted'])});
check('nested shadow grids cannot donate rows to the outer selected grid',()=>{const nested=el('grid');shadow(nested,[row('Nested')]);const target=el('grid',[],{'aria-label':'Selected'});shadow(target,[row('Outer'),nested]);const raw=selected('table',scene(target));complete(raw);assert.deepEqual(texts(raw),['Outer'])});
check('a row assigned under an inner grid is not owned by its light-DOM parent',()=>{const own=row('Outer'),borrowed=row('Nested'),ownSlot=new Element('SLOT'),innerSlot=new Element('SLOT'),target=el('grid',[own,borrowed],{'aria-label':'Selected'});own.assignedSlot=ownSlot;ownSlot.assigned=[own];borrowed.assignedSlot=innerSlot;innerSlot.assigned=[borrowed];shadow(target,[ownSlot,el('grid',[innerSlot])]);const raw=selected('table',scene(target));complete(raw);assert.deepEqual(texts(raw),['Outer'])});
check('nested lists cannot donate listitems to the outer selected list',()=>{const target=el('list',[],{'aria-label':'Selected'});shadow(target,[el('listitem',['Outer']),el('list',[el('listitem',['Nested'])])]);const raw=selected('list',scene(target));complete(raw);assert.deepEqual(raw.read.data.map(x=>x.text),['Outer'])});
check('native table rowgroups keep their existing ownership and exclude nested rows',()=>{const nested=new Element('TABLE',{},[new Element('TR',{},[new Element('TD',{},['Nested'])])]),target=new Element('TABLE',{'aria-label':'Selected'},[new Element('TBODY',{},[new Element('TR',{},[new Element('TD',{},['Outer',nested])])])]);const raw=selected('table',scene(target));complete(raw);assert.equal(raw.read.data.length,1);assert(raw.read.data[0].cells[0].text.startsWith('Outer'))});
check('hidden ancestors remain excluded and virtual-row partial facts remain explicit',()=>{const hidden=el('rowgroup',[row('Hidden')],{hidden:''}),target=el('grid',[],{'aria-label':'Selected','aria-rowcount':'3'});shadow(target,[hidden,row('Visible')]);const raw=selected('table',scene(target));assert.deepEqual(texts(raw),['Visible']);assert(raw.read.facts.partial);assert(raw.read.facts.reasons.includes('virtual_rows_not_in_dom'))});
check('a composed subtree past the depth boundary is partial rather than complete empty data',()=>{const target=el('grid',[],{'aria-label':'Selected'});let parent=el('rowgroup');shadow(target,[parent]);for(let i=0;i<140;i++)parent=parent.append(new Element('DIV'));parent.append(row('Beyond depth'));const raw=selected('table',scene(target));assert.deepEqual(raw.read.data,[]);assert(raw.read.facts.partial);assert(raw.read.facts.reasons.some(x=>['depth_budget','layout_budget'].includes(x)))});
check('shadow rows continue without duplicate rows or reset indices',()=>{const target=el('grid',[],{'aria-label':'Selected'});shadow(target,Array.from({length:55},(_,i)=>row('Row '+i)));const c=scene(target);let raw=selected('table',c),rows=[...raw.read.data],count=1;assert(raw.read.cursor);while(raw.read.cursor){assert(raw.read.facts.partial);assert(raw.read.facts.reasons.includes('structured_row_range_continuation'));raw=run('cursor_table',c,raw.read.cursor);assert.equal(raw.status,'observed');rows.push(...raw.read.data);assert(++count<10)}assert.deepEqual(rows.map(x=>x.index),Array.from({length:55},(_,i)=>i));assert.equal(rows.at(-1).cells[0].text,'Row 54')});
check('the exact production owner predicate bounds ancestry and marks incomplete ownership',()=>{
 const code=scripts.table,from=code.indexOf('const ownedRow='),to=code.indexOf('\nfunction partFor',from),ownerCode=code.slice(from,to);assert(from>=0&&to>from);
 for(const wrappers of [127,128]){const target=el('grid');let parent=target;for(let i=0;i<wrappers;i++)parent=parent.append(new Element('DIV'));const candidate=parent.append(row('Depth'));const session={target,reasons:new Set()},c={session,expected:new Set(['table','grid']),composedParent:n=>n.parentElement,role:n=>n.attrs.role||'',candidate};vm.createContext(c);const owned=vm.runInContext(ownerCode+';ownedRow(candidate)',c,{timeout:100});assert.equal(owned,wrappers===127);assert.equal(session.reasons.has('depth_budget'),wrappers===128)}
});
if(process.argv[3])fs.writeFileSync(process.argv[3],JSON.stringify({status:'PASS',platform:'synthetic_composed_dom_not_browser',samples}));console.log(JSON.stringify({passed,actualProductionScripts:true,realDOM:false}));
