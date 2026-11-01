'use strict';
// The exact product compiler/driver run here; no reimplementation, npm install or project JS execution.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../main/assets/project-language'),bundle=path.join(root,'typescript-5.9.3');
const context=vm.createContext(Object.create(null),{name:'pocketworld-fixed-ts-language-service'});
const lock=JSON.parse(fs.readFileSync(path.join(bundle,'source-lock.json'),'utf8'));
function sha(value){return crypto.createHash('sha256').update(value).digest('hex');}
for(const file of ['typescript.js','libraries.json'])assert.equal(sha(fs.readFileSync(path.join(bundle,file))),lock.bundledAssets[file].sha256);
vm.runInContext(fs.readFileSync(path.join(bundle,'typescript.js'),'utf8'),context,{timeout:20000});
vm.runInContext('globalThis.pwLanguageLibraries=JSON.parse('+JSON.stringify(fs.readFileSync(path.join(bundle,'libraries.json'),'utf8'))+');',context,{timeout:2000});
vm.runInContext(fs.readFileSync(path.join(root,'language-service.js'),'utf8'),context,{timeout:2000});
function query(files,selected,action,position=0,newName){
  const request={format:'pocketworld.typescript-language.v1',snapshotIdentity:sha(JSON.stringify(files)),files:Object.entries(files).map(([path,content])=>({path,content,sha256:sha(content)})),path:selected,action,position,newName};
  const result=JSON.parse(vm.runInContext('pwProjectLanguage.run(JSON.parse('+JSON.stringify(JSON.stringify(request))+'))',context,{timeout:20000}));
  assert.equal(result.compilerVersion,'5.9.3');assert.equal(result.snapshotIdentity,request.snapshotIdentity);assert.equal(result.sha256,sha(files[selected]));assert.equal(result.userCodeExecuted,false);assert.equal(result.projectPluginsLoaded,false);return result;
}
const only=new Set(process.argv.slice(2));let executed=0;
function test(name,body){if(only.size&&!only.has(name))return;const start=performance.now();body();executed++;console.log(JSON.stringify({method:name,status:'PASS',millis:Math.round(performance.now()-start),hostHeapUsedBytes:process.memoryUsage().heapUsed}));}
const library='export function calculate(amount: number): number { return amount + 5; }\n';
const main='import { calculate } from "./src.v2/invoice";\nexport const answer = calculate(40);\nconst literal = "calculate";\n';
const project={'src.v2/invoice.ts':library,'main.ts':main,'package.json':'{"type":"module"}'};
test('real-type-diagnostics-and-source-syntax',()=>{
  const files={...project,'broken.ts':'export const amount: number = "not-a-number";\n'};const result=query(files,'broken.ts','diagnostics');
  assert(result.rows.some(row=>row.code===2322&&row.path==='broken.ts'&&row.sha256===sha(files['broken.ts'])));assert(result.syntax.some(token=>token.kind==='keyword'));assert(result.rows.every(row=>row.start+row.length<=files[row.path].length));
});
test('actual-member-prefix-completion',()=>{
  const text='const invoice = { amount: 40, taxed: () => 45 };\ninvoice.ta';const result=query({'invoice.ts':text},'invoice.ts','complete',text.length);
  const found=result.rows.find(row=>row.name==='taxed');assert(found);assert.equal(found.replacementSpan.start,text.lastIndexOf('ta'));assert.equal(found.replacementSpan.length,2);assert(result.rows.every(row=>row.name.toLowerCase().startsWith('ta')));
});
test('cross-file-definition-and-references-with-dotted-directory',()=>{
  const at=main.indexOf('calculate(40)')+3;const definition=query(project,'main.ts','definition',at);assert(definition.rows.some(row=>row.path==='src.v2/invoice.ts'&&row.sha256===sha(library)));
  const refs=query(project,'main.ts','references',at);assert(refs.rows.some(row=>row.path==='main.ts'));assert(refs.rows.some(row=>row.path==='src.v2/invoice.ts'));
});
test('semantic-rename-does-not-rewrite-strings-and-is-complete',()=>{
  // An import binding is a different selected symbol from the exported declaration.
  // TS's real prefix/suffix rename preserves that distinction (compiler source 172749–172755).
  const aliased='import { calculate as callerTotal } from "./src.v2/invoice";\nexport const another = callerTotal(40);\n';
  const unrelated='export const calculate = 99;\n';const files={...project,'aliased.ts':aliased,'unrelated.ts':unrelated};
  const apply=result=>{const changed={...files};for(const file of Object.keys(files)){const rows=result.rows.filter(row=>row.path===file).sort((a,b)=>b.start-a.start);let right=files[file].length;for(const row of rows){assert.equal(sha(files[file]),row.sha256);assert(row.start+row.length<=right);right=row.start;changed[file]=changed[file].slice(0,row.start)+row.newText+changed[file].slice(row.start+row.length);}}return changed;};
  const precise=result=>result.rows.map(row=>({path:row.path,start:row.start,length:row.length,newText:row.newText})).sort((a,b)=>a.path.localeCompare(b.path)||a.start-b.start);
  const result=query(files,'src.v2/invoice.ts','rename',library.indexOf('calculate')+3,'totalWithTax');assert.equal(result.truncated,false);console.log(JSON.stringify({diagnostic:'actual-export-rename-locations',rows:precise(result)}));
  assert.deepEqual(precise(result),[
    {path:'aliased.ts',start:aliased.indexOf('calculate'),length:9,newText:'totalWithTax'},
    {path:'main.ts',start:main.indexOf('calculate'),length:9,newText:'totalWithTax'},
    {path:'main.ts',start:main.indexOf('calculate(40)'),length:9,newText:'totalWithTax'},
    {path:'src.v2/invoice.ts',start:library.indexOf('calculate'),length:9,newText:'totalWithTax'}
  ].sort((a,b)=>a.path.localeCompare(b.path)||a.start-b.start));
  const changed=apply(result);assert.equal(changed['src.v2/invoice.ts'],'export function totalWithTax(amount: number): number { return amount + 5; }\n');
  assert.equal(changed['main.ts'],'import { totalWithTax } from "./src.v2/invoice";\nexport const answer = totalWithTax(40);\nconst literal = "calculate";\n');
  assert.equal(changed['aliased.ts'],'import { totalWithTax as callerTotal } from "./src.v2/invoice";\nexport const another = callerTotal(40);\n');assert.equal(changed['unrelated.ts'],unrelated);
  for(const file of ['main.ts','src.v2/invoice.ts','aliased.ts','unrelated.ts'])assert.deepEqual(query(changed,file,'diagnostics').rows,[]);
  const alias=query(files,'main.ts','rename',main.indexOf('calculate(40)')+3,'localTotal');console.log(JSON.stringify({diagnostic:'actual-import-binding-rename-locations',rows:precise(alias)}));
  assert.deepEqual(precise(alias),[{path:'main.ts',start:main.indexOf('calculate'),length:9,newText:'calculate as localTotal'},{path:'main.ts',start:main.indexOf('calculate(40)'),length:9,newText:'localTotal'}]);
  const local=apply(alias);assert.equal(local['src.v2/invoice.ts'],library);assert.equal(local['aliased.ts'],aliased);assert.equal(local['unrelated.ts'],unrelated);
  assert.equal(local['main.ts'],'import { calculate as localTotal } from "./src.v2/invoice";\nexport const answer = localTotal(40);\nconst literal = "calculate";\n');assert.deepEqual(query(local,'main.ts','diagnostics').rows,[]);
});
test('real-formatting-and-analysis-never-evaluates-user-code',()=>{
  const text='globalThis.__pwUserCodeRan = true;\nexport function sum(a:number,b:number){return a+b}\n';const result=query({'format.ts':text},'format.ts','format');assert(result.rows.length>0);assert.equal(result.truncated,false);let formatted=text;
  for(const row of [...result.rows].sort((a,b)=>b.start-a.start))formatted=formatted.slice(0,row.start)+row.newText+formatted.slice(row.start+row.length);
  assert(formatted.includes('a: number'));assert.equal(vm.runInContext('globalThis.__pwUserCodeRan',context),undefined);
});
test('vfs-boundaries-and-no-host-authority',()=>{
  assert.throws(()=>query({'../outside.ts':'export const x=1;'},'../outside.ts','diagnostics'),/path/i);
  const many={};for(let i=0;i<129;i++)many['file'+i+'.ts']='export const x=1;';assert.throws(()=>query(many,'file0.ts','diagnostics'),/snapshot/i);
  assert.throws(()=>query(project,'main.ts','rename',main.indexOf('calculate(40)')+3,'class'),/identifier/i);
  const forbidden=vm.runInContext("JSON.stringify(['fetch','XMLHttpRequest','WebSocket','document','require','process','Java','Packages','readFile','writeFile'].map(n=>typeof globalThis[n]))",context);assert(JSON.parse(forbidden).every(value=>value==='undefined'));
});
assert(executed>0);console.log(JSON.stringify({summary:executed+' methods completed',compiler:'5.9.3',platform:process.platform,arch:process.arch,note:'Host Node V8 evidence, not Android JavaScriptSandbox or ARM'}));
