// Runs the shipped compatibility editor callbacks; it does not render a browser or run agents.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const html=fs.readFileSync(path.join(__dirname,'../../main/assets/index.html'),'utf8');
const at=html.indexOf('function renderTeams(host){'),end=html.indexOf('function renderPlugins(host){',at);
assert.ok(at>=0&&end>at);const source=html.slice(at,end);
const original={id:'team-fixture',name:'saved team',mode:'collaborate',leaderId:'member-7',members:[
 {id:'member-1',name:'First',role:'read',model:'model-1',runner:'model',device:'phone'},
 {id:'member-7',name:'Leader',role:'coordinate',model:'model-7',runner:'model',device:'phone'},
 {id:'member-9',name:'Remote',role:'inspect',model:'model-9',runner:'codex_app_server',device:{profileId:'profile-9',environmentId:'environment-9'},slotHint:'retain at UI boundary'}
]};
function fixture(teams){
 const drafts={},selected={},cache={},controls={},buttons=[],records=[],calls=[];
 const node=()=>({append(){}});const control=(host,key,label,type,initial)=>{const field={value:Object.prototype.hasOwnProperty.call(drafts,key)?drafts[key]:initial};controls[key]=field;return field;};
 const button=(label,callback)=>({label,callback});
 const render=new Function('intro','section','field','select','actions','action','btn','record','el','empty','data','selected','drafts','cache','run','renderCenter',source+';return renderTeams;')(
  ()=>{},()=>node(),control,(host,key,label,options,initial)=>control(host,key,label,null,initial),(host,...items)=>buttons.push(...items),button,button,(host,title,copy,items)=>{records.push({title,items});return node();},()=>node(),()=>{},()=>({teams,runs:[]}),selected,drafts,cache,
  (feature,command,payload)=>{calls.push({feature,command,payload:JSON.parse(JSON.stringify(payload))});return Promise.resolve({});},()=>{}
 );
 return {render:()=>{buttons.length=0;records.length=0;render(node());},drafts,selected,controls,buttons,records,calls};
}
async function checks(){
 const edit=fixture([original]);edit.render();edit.records.find(row=>row.title===original.name).items.find(item=>item.label==='编辑').callback();edit.render();
 assert.deepEqual(JSON.parse(edit.controls.teamMembers.value),original.members);assert.equal(edit.controls.teamMode.value,'collaborate');assert.equal(edit.controls.teamLeaderId.value,'member-7');
 const retainedRemote=JSON.parse(JSON.stringify(original.members[2])),retainedLeader={...original.members[1],role:'edited role'};
 const newMember={id:'member-11',name:'New',role:'review',model:'new-model',runner:'kimi',device:{profileId:'new-profile',environmentId:'new-env'}};
 edit.controls.teamMembers.value=JSON.stringify([retainedRemote,newMember,retainedLeader]);edit.controls.teamName.value='edited team';await edit.buttons.find(item=>item.label==='保存团队').callback();
 const save=edit.calls[0];assert.equal(save.feature,'teams');assert.equal(save.command,'save');assert.equal(save.payload.id,original.id);assert.equal(save.payload.mode,'collaborate');assert.equal(save.payload.leaderId,'member-7');assert.deepEqual(save.payload.members,[retainedRemote,newMember,retainedLeader]);assert.equal(save.payload.members[0].device.profileId,'profile-9');assert.equal(save.payload.members[2].model,'model-7');assert.equal(save.payload.members[0].slotHint,'retain at UI boundary','The UI must not silently drop member fields; the native backend validates supported fields.');
 const fresh=fixture([]);fresh.render();fresh.controls.teamName.value='new team';await fresh.buttons.find(item=>item.label==='保存团队').callback();const created=fresh.calls[0].payload;assert.equal(created.mode,'debate');assert.equal(created.leaderId,'member-0');assert.equal(created.members.length,2);assert.equal(created.members[0].id,'member-0');assert.ok(!Object.prototype.hasOwnProperty.call(created,'id'));
 fresh.controls.teamMembers.value='{"not":"an array"}';assert.throws(()=>fresh.buttons.find(item=>item.label==='保存团队').callback(),/JSON 数组/);assert.equal(fresh.calls.length,1);
 assert.match(html,/后台屏的界面节点读取、节点点击与文字输入依赖本应用的无障碍服务/);
 console.log('Compatibility team callbacks preserve full member identity/model/runner/device and mode/leader through reorder, insertion and removal. No agent execution claim.');
}
checks().catch(error=>{console.error(error);process.exitCode=1;});
