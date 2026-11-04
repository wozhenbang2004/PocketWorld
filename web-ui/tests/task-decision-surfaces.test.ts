import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import ts from 'typescript';
import {ownerQuestionSavedMessage, submitOwnerQuestion} from '../src/owner-task-question.ts';

// Execute production component trees and handlers using explicit hook/control doubles.
// These checks cover visible decision facts and callback boundaries, not rendered layout,
// React Aria behavior, focus, animation, screen readers or visual acceptance.
type Data=Record<string,any>;
function load(name:string, dependencies:Data) {
  const source=ts.createSourceFile(`${name}.tsx`,readFileSync(new URL(`../src/${name}.tsx`,import.meta.url),'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
  const body=source.statements.filter(node=>!ts.isImportDeclaration(node)).map(node=>node.getText(source).replace(/^export /,'')).join('\n');
  const compiled=ts.transpileModule(body,{compilerOptions:{target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.React}}).outputText;
  return new Function(...Object.keys(dependencies),compiled+`\nreturn ${name};`)(...Object.values(dependencies));
}
function fixture(name:string,props:Data) {
  const slots:any[]=[];let index=0,tree:any,dirty=false;let effects:(()=>void)[]=[];
  const React={Fragment:'Fragment',createElement:(type:unknown,props:any,...children:any[])=>({type,props:props||{},children:children.flat(Infinity).filter(child=>child!==null&&child!==undefined&&child!==false)})};
  const useState=(initial:any)=>{const i=index++;slots[i]??={value:initial};return [slots[i].value,(value:any)=>{if(!Object.is(slots[i].value,value)){slots[i].value=value;dirty=true;}}];};
  const useRef=(initial:any)=>{const i=index++;return slots[i]??={current:initial};};
  const useEffect=(effect:()=>void,deps:any[])=>{const i=index++,old=slots[i];if(!old||deps.some((value,at)=>!Object.is(value,old.deps[at]))){slots[i]={deps:[...deps]};effects.push(effect);}};
  const Component=load(name,{React,useState,useRef,useEffect,Accordion:'Accordion',AccordionItem:'AccordionItem',Button:'Button',CheckboxField:'CheckboxField',PressButton:'PressButton',TextAreaField:'TextAreaField',ownerQuestionSavedMessage,submitOwnerQuestion});
  const nodes=(node:any):any[]=>node&&typeof node==='object'?[node,...node.children.flatMap(nodes)]:[];
  const content=(node:any,visible=false):string=>node&&typeof node==='object'?visible&&node.type==='AccordionItem'?'':node.children.map((child:any)=>content(child,visible)).join(''):String(node??'');
  const render=()=>{let passes=0;do{index=0;dirty=false;effects=[];tree=Component(props);effects.forEach(effect=>effect());assert.ok(++passes<8,'hook fixture must settle');}while(dirty);};
  render();
  return {render,update(next:Data){Object.assign(props,next);render();},get all(){return nodes(tree);},get text(){return content(tree);},get visibleText(){return content(tree,true);},
    find(predicate:(node:any)=>boolean){const node=nodes(tree).find(predicate);assert.ok(node,'expected a production element');return node;},
    async settle(){for(let i=0;i<6;i++)await Promise.resolve();render();}};
}
const teamApproval=()=>({approvalId:'approval-a',title:'安排两项文件工作',reason:'为原任务制作隔离草稿',description:'只覆盖指定文件',arguments:{recipient:'原项目协调者'},
  capability:{planRef:'native-plan',planHash:'plan-hash'},webReview:{kind:'team_plan',canApprove:true,programRisk:'不能运行额外本机命令',teamPlan:{planRef:'native-plan',planHash:'plan-hash',plan:{goal:'完成原目标',sourceParent:{targetProjectId:'original-project',remainingOperations:9,parentBudget:{modelRequestLimit:3,toolOperationLimit:4}},modelRequestLimit:8,toolOperationLimit:12,expiresAt:1900000000000,
    budgetPlan:{selectedFileToolSteps:2,parentOrchestrationToolSteps:3,priorParentTeamToolOperationsNotYetInPool:1,toolRetryMargin:4,estimatedParentModelRequests:2,estimatedModelRequests:5,modelRetryMargin:1},definition:{members:[{id:'member-a',name:'实现成员'}]},items:[{workItemId:'first',title:'实现原文件',memberId:'member-a',instruction:'保留原业务约束',acceptance:'原流程可用',files:[{path:'src/first.ts',baseVersion:7,baseHash:'a'.repeat(64)}]},{workItemId:'second',title:'复核原文件',memberId:'member-a',dependsOn:['first'],instruction:'独立核对草稿',acceptance:'不扩大范围',files:[{path:'src/second.ts',baseVersion:8,baseHash:'b'.repeat(64)}]}]}}}});

test('team decision facts stay outside collapsed diagnostics; exact file fingerprints remain available',()=>{
  const f=fixture('TaskApproval',{approval:teamApproval(),disabled:false,onDecision:()=>{}});
  for(const text of ['为原任务制作隔离草稿','不能运行额外本机命令','原项目协调者','original-project','实现成员','src/first.ts','原基线 v7','前置冻结','保留原业务约束','原流程可用','模型请求总上限','工具操作总上限','规划与审批原操作','捕获时父授权剩余','回答不续期','未知外效不能重派或退款']) assert.ok(f.visibleText.includes(text),`${text} must be visible before approval`);
  assert.equal(f.visibleText.includes('a'.repeat(64)),false,'full technical fingerprints must not dominate the normal summary');
  assert.ok(f.text.includes('a'.repeat(64)));
  const raw=f.find(node=>node.type==='pre').children.join('');
  assert.equal(JSON.parse(raw).originalPlan.plan.items[0].files[0].baseHash,'a'.repeat(64));
});

test('positive team decision still requires scope review and passes the original plan identity unchanged',()=>{
  const decisions:Data[]=[];
  const f=fixture('TaskApproval',{approval:teamApproval(),disabled:false,onDecision:(args:Data)=>decisions.push(args)});
  assert.equal(f.find(node=>node.type==='PressButton').props.disabled,true);
  f.find(node=>node.type==='CheckboxField').props.onChange(true);f.render();
  const approve=f.find(node=>node.type==='PressButton');assert.equal(approve.props.disabled,false);approve.props.onClick();
  assert.deepEqual(decisions,[{approvalId:'approval-a',approved:true,scopeReviewed:true,planRef:'native-plan',planHash:'plan-hash'}]);
});

test('a mismatched or unread plan stays blocked while explicit rejection remains available',()=>{
  for(const unread of [false,true]) {
    const approval=teamApproval();
    if(unread) approval.webReview.canApprove=false;else approval.webReview.teamPlan.planHash='changed';
    const decisions:Data[]=[];const f=fixture('TaskApproval',{approval,disabled:false,onDecision:(args:Data)=>decisions.push(args)});
    const checkbox=f.all.find(node=>node.type==='CheckboxField');checkbox?.props.onChange(true);f.render();
    assert.equal(f.find(node=>node.type==='PressButton').props.disabled,true);
    assert.ok(f.all.some(node=>node.props.role==='alert'));
    f.find(node=>node.type==='Button').props.onClick();
    assert.deepEqual(decisions,[{approvalId:'approval-a',approved:false}]);
  }
});

test('live approval keeps exact allowed paths, duration and stopping limits visible',()=>{
  const f=fixture('TaskApproval',{approval:{approvalId:'live-a',arguments:{command:'npm run serve'},capability:{ownedService:{editablePaths:['src/one.ts','src/two.ts'],retainAfterTurn:true,timeoutMillis:120000}},webReview:{kind:'live_scope',canApprove:true}},disabled:false,onDecision:()=>{}});
  for(const text of ['npm run serve','src/one.ts','src/two.ts','120 秒','当前 SHA','原操作预算','真实服务写租约','新文件不在此许可内','授权到期或撤销必须停止并排空','不是冷重启持久后台或公网服务']) assert.ok(f.visibleText.includes(text),`${text} remains visible`);
  assert.equal(f.find(node=>node.type==='PressButton').props.disabled,true);
});

test('question title and offered options are primary; unknown save locks actions without erasing the draft',async()=>{
  const calls:Data[]=[];const question={questionKey:'question-a',kind:'phone',question:'你希望保留哪个原方案？',answered:false,options:[{id:'second',label:'保留完整的第二个方案和它的所有条件'}]};
  const f=fixture('OwnerTaskQuestion',{question,text:'仍需保留的原回答草稿',onTextChange:()=>{},disabled:false,canChoose:true,canAnswer:true,submit:async(action:string,args:Data)=>{calls.push({action,args});return {accepted:true};}});
  assert.equal(f.find(node=>node.type==='h4').children.join(''),question.question);
  assert.ok(f.visibleText.includes('不代表批准敏感操作'));
  const choice=f.find(node=>node.type==='Button');choice.props.onClick();f.render();
  assert.equal(f.find(node=>node.type==='Button').props.disabled,true);
  await f.settle();
  assert.deepEqual(calls,[{action:'tasks.answerQuestionChoice',args:{optionId:'second'}}]);
  assert.ok(f.visibleText.includes('没有自动重发'));
  assert.equal(f.find(node=>node.type==='TextAreaField').props.value,'仍需保留的原回答草稿');
  assert.equal(f.find(node=>node.type==='PressButton').props.disabled,true);
  choice.props.onClick();await f.settle();assert.equal(calls.length,1,'an unknown outcome cannot be retried from the same review');
});


const executionApproval=()=>({approvalId:'execution-a',title:'核对原任务计划',capability:{planRevision:7,planArtifactRef:'native-plan-artifact',planHash:'execution-hash',scopeRevision:3},webReview:{kind:'execution_plan',canApprove:true,executionPlan:{planRevision:7,planArtifactRef:'native-plan-artifact',planHash:'execution-hash',scopeRevision:3,plan:{goal:'完成原始目标',steps:[{id:'s1',title:'读取原文件',detail:'只读核对当前内容'},{id:'s2',title:'按原范围处理',detail:'保留所有授权与停止条件'}],sources:['原来源引用']}}}});

test('execution-plan merge keeps complete steps and original limits visible, with exact approved identity',()=>{
  const decisions:Data[]=[];
  const f=fixture('TaskApproval',{approval:executionApproval(),executionScope:{toolsUsed:0,toolLimit:12,modelCallsUsed:2,modelCallLimit:7,deadlineAt:0},disabled:false,onDecision:(args:Data)=>decisions.push(args)});
  for(const value of ['完成原始目标','只读核对当前内容','保留所有授权与停止条件','已用 0 / 总上限 12','已用 2 / 总上限 7','原任务未设置截止时间','本次确认不续期','不清除已预留与未知用量']) assert.ok(f.visibleText.includes(value),value);
  assert.equal(f.find(node=>node.type==='PressButton').props.disabled,true);
  f.find(node=>node.type==='CheckboxField').props.onChange(true);f.render();
  const approve=f.find(node=>node.type==='PressButton');assert.equal(approve.props.disabled,false);approve.props.onClick();
  assert.deepEqual(decisions,[{approvalId:'execution-a',approved:true,scopeReviewed:true,planRevision:7,planArtifactRef:'native-plan-artifact',planHash:'execution-hash'}]);
  assert.equal(JSON.parse(f.find(node=>node.type==='pre').children.join('')).originalPlan.scopeRevision,3);
});

test('execution-plan mismatch or read failure cannot approve, while rejection remains available',()=>{
  for(const property of ['planRevision','planArtifactRef','planHash','scopeRevision','unread']){
    const approval:Data=executionApproval();if(property==='unread')approval.webReview.canApprove=false;else approval.webReview.executionPlan[property]='changed';
    const decisions:Data[]=[];const f=fixture('TaskApproval',{approval,disabled:false,onDecision:(args:Data)=>decisions.push(args)});
    assert.equal(f.find(node=>node.type==='PressButton').props.disabled,true);
    assert.ok(f.all.some(node=>node.props.role==='alert'));
    assert.equal(f.all.some(node=>node.type==='CheckboxField'),false);
    f.find(node=>node.type==='Button').props.onClick();assert.deepEqual(decisions,[{approvalId:'execution-a',approved:false}]);
  }
});

test('a fresh execution-plan revision clears prior scope review; missing limits never read as zero',()=>{
  const approval=executionApproval();const f=fixture('TaskApproval',{approval,disabled:false,onDecision:()=>{}});
  assert.ok(f.visibleText.includes('已用 未读取 / 总上限 未读取'));
  assert.equal(f.visibleText.includes('原任务未设置截止时间'),false);
  f.find(node=>node.type==='CheckboxField').props.onChange(true);f.render();
  assert.equal(f.find(node=>node.type==='PressButton').props.disabled,false);
  const next=structuredClone(approval);next.capability.planRevision=8;next.webReview.executionPlan.planRevision=8;
  f.update({approval:next});
  assert.equal(f.find(node=>node.type==='CheckboxField').props.isSelected,false);
  assert.equal(f.find(node=>node.type==='PressButton').props.disabled,true);
});
