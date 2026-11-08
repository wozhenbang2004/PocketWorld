import assert from 'node:assert/strict';
const visible=async locator=>{await locator.waitFor({state:'visible'});return locator};
export async function stabilizeSceneTarget(page,target,label){
 await visible(target);await target.scrollIntoViewIfNeeded();
 const state=await target.evaluate(el=>({inert:!!el.closest('[inert]'),hidden:!!el.closest('[aria-hidden="true"]')}));
 assert.deepEqual(state,{inert:false,hidden:false},`${label}: current active layer`);
 let first=await target.boundingBox(),second=first,stable=0;
 for(let attempt=0;attempt<90&&stable<2;attempt++){
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));second=await target.boundingBox();
  const same=first&&second&&['x','y','width','height'].every(key=>Math.abs(first[key]-second[key])<.5);stable=same?stable+1:0;first=second;
 }
 assert.ok(second&&stable===2,`${label}: four stable animation frames`);
 assert.ok(second.x<page.viewportSize().width&&second.y<page.viewportSize().height&&second.x+second.width>0&&second.y+second.height>0,`${label}: target intersects viewport`);
 return{target:label,activeLayer:true,stableFrames:4};
}
export async function assertEcosystemScene(page,workspace,scene){
 const button=name=>page.getByRole('button',{name,exact:true}),heading=name=>page.getByRole('heading',{name,exact:true}),text=value=>page.getByText(value,{exact:true});
 const dialog=page.getByRole('dialog');let target,label;const set=(locator,name)=>{target=locator;label=name};
 const modal=workspace==='skills'&&['enable-review','import','git-import'].includes(scene)||workspace==='plugins'&&['grant-review','enable-review','manifest-editor','js-review'].includes(scene)||workspace==='catalog'&&['source-editor','source-save-error','install-review'].includes(scene)||workspace==='packs'&&['remove-review','journal','install-review'].includes(scene)||workspace==='task'&&['script-review','script-source','tool-review'].includes(scene)||workspace==='draft';
 assert.equal(await dialog.count(),modal?1:0,`${workspace}/${scene}: expected current modal`);
 if(workspace==='skills'){
  if(scene==='collection'){await visible(button('阅读技能：资料核对与报告'));set(text('技能库'),'current skill collection');}
  if(scene==='reading'){set(page.getByRole('region',{name:'当前已读取资源正文'}),'read original resource');await visible(heading('核对顺序'));}
  if(scene==='policy-history'){await visible(text('修订 3 · v1.3'));set(button('读取保留的修订'),'original revision history');}
  if(scene==='enable-review'){set(dialog.getByRole('heading',{name:'核对本次技能操作',exact:true}),'skill enable review');assert.equal(await button('确认这次原操作').isDisabled(),true);}
  if(scene==='import'){set(page.getByRole('button',{name:/^选择完整技能 ZIP/}),'expanded file import');}
  if(scene==='git-import'){set(page.getByRole('textbox',{name:'仓库或 SKILL.md 链接',exact:true}),'explicit Git source');}
 }
 if(workspace==='plugins'){
  if(scene==='installed'){await visible(button('管理工具').first());set(heading('已安装插件'),'installed declaration list');}
  if(scene==='tools'){await visible(button('收起工具'));set(heading('forecast'),'current installation tools');}
  if(scene==='grant-review')set(dialog.getByRole('heading',{name:'收回工具权限',exact:true}),'exact tool grant review');
  if(scene==='enable-review')set(dialog.getByRole('heading',{name:'启用这个安装',exact:true}),'exact installation enable review');
  if(scene==='manifest-editor')set(page.getByRole('textbox',{name:'插件声明 JSON',exact:true}),'manifest editor');
  if(scene==='history')set(heading('天气与出行 · 版本历史'),'original installation history');
  if(scene==='js-installed'){set(button('阅读源码与权限'),'installed executable source');assert.equal(await page.getByRole('tab',{name:/^JS 扩展/}).getAttribute('aria-selected'),'true');}
  if(scene==='js-review'){set(dialog.getByRole('heading',{name:'源码与运行权限',exact:true}),'JS source approval review');await visible(page.locator('pre[aria-label="文本整理扩展 的完整源码"]'));assert.equal(await button('启用扩展').isDisabled(),true);}
  if(scene==='js-history')set(heading('保留的源码版本'),'retained executable versions');
 }
 if(workspace==='catalog'){
  if(scene==='versions'){set(button('读取可选版本'),'catalog source and versions');await visible(heading('weather'));}
  if(scene==='source-editor'){set(page.getByRole('textbox',{name:'目录地址',exact:true}),'source editor fields');await visible(dialog.getByRole('heading',{name:'添加目录来源',exact:true}));}
  if(scene==='source-save-error'){set(dialog.getByRole('alert'),'source save failure');assert.equal(await page.getByRole('textbox',{name:'显示名称',exact:true}).inputValue(),'另一份可信目录');}
  if(scene==='install-review'){set(dialog.getByRole('heading',{name:'核对目录安装差异',exact:true}),'catalog exact install review');assert.equal(await button('确认安装并保持停用').isDisabled(),true);}
 }
 if(workspace==='packs'){
  if(scene==='installed'){set(heading('研究与整理'),'installed pack');await visible(text('1.2 · 部分完成'));}
  if(scene==='remove-review'){set(dialog.getByRole('heading',{name:'核对卸载与保留项',exact:true}),'pack removal and retention');await visible(text('保留手工更新的安装'));}
  if(scene==='journal')set(dialog.getByRole('heading',{name:'原任务的组件记录',exact:true}),'original component journal');
  if(scene==='selected-file')set(heading('文件已选择'),'selected native document');
  if(scene==='install-review'){set(dialog.getByRole('heading',{name:'核对准确组件',exact:true}),'pack component install review');assert.equal(await button('安装组件并保持停用').isDisabled(),true);}
 }
 if(workspace==='task'){
  if(scene==='closed'){set(button('查看'),'collapsed task capability entry');assert.equal(await button('审阅本次执行').count(),0);}
  if(scene==='receipts'){set(button('审阅本次执行'),'pending task script review');await visible(text('原操作 operation-original'));}
  if(scene==='script-review')set(dialog.getByRole('heading',{name:'审阅本次脚本执行',exact:true}),'exact task script scope');
  if(scene==='script-source')set(page.locator('pre').filter({hasText:'// Synthetic review sample'}),'current script version body');
  if(scene==='read-error')set(page.getByRole('alert'),'original task read error');
  if(scene==='tool-review')set(dialog.getByRole('heading',{name:'审阅任务所需工具',exact:true}),'single original tool review');
  if(scene==='artifact')set(heading('outputs/report.md'),'retrieved artifact identity');
 }
 if(workspace==='draft'){
  if(scene==='proposal'){set(heading('资料核对方法'),'current conversation proposal');assert.equal(await button('保存这份技能，保持停用').isDisabled(),true);}
  if(scene==='original-file')set(heading('原文件 · SKILL.md'),'original proposal file');
  if(scene==='candidate-first-page'){set(heading('候选文件 · SKILL.md'),'candidate first page');await visible(button('下一段'));}
  if(scene==='candidate-complete'){set(heading('候选文件 · SKILL.md'),'candidate complete pages');assert.equal(await button('下一段').count(),0);assert.equal(await button('保存这份技能，保持停用').isDisabled(),true);}
  if(scene==='proposal-source')set(heading('主人明确确认的方法'),'read source message');
  if(scene==='ready-to-save'){set(button('保存这份技能，保持停用'),'explicit file-read acknowledgement');assert.equal(await target.isDisabled(),false);}
  if(scene==='unknown-save'){set(text('保存结果待核对'),'unknown native save receipt');assert.equal(await button('保存这份技能，保持停用').count(),0);}
  if(scene==='query-original')set(text('原保存结果仍待核对，没有重新提交。'),'original result recheck');
 }
 assert.ok(target,`Scene must have a unique arrival assertion: ${workspace}/${scene}`);return stabilizeSceneTarget(page,target,`${workspace}/${scene}: ${label}`);
}
export async function assertModelChildScene(page,workspace,scene){
 const button=name=>page.getByRole('button',{name,exact:true}),text=value=>page.getByText(value,{exact:true});let target;
 if(workspace==='api'){
  if(scene==='connections-models'){await visible(page.getByRole('option').filter({hasText:'备用连接'}));target=page.getByRole('listbox',{name:'API 连接与模型'});}
  if(scene==='unavailable-review'){target=button('取消收藏');assert.equal(await button('确认切换主模型').count(),0);}
  if(scene==='exact-connection-review'){target=text('API 连接：备用连接');await visible(button('确认切换主模型'));}
  if(scene==='selection-unknown'){target=page.getByRole('alert');await visible(button('重新读取模型'));}
 }
 if(workspace==='accounts'){
  if(scene==='account-recipient'){target=page.getByRole('listbox',{name:'选择 ChatGPT 账号'});assert.equal(await page.getByRole('option').filter({hasText:'尚未授权用量的账号'}).isDisabled(),true);}
  if(scene==='account-catalog'){target=page.getByRole('option').filter({hasText:'长文分析模型'});await visible(button('返回账号列表'));}
  if(scene==='model-options-review'){target=page.getByRole('textbox',{name:'本地输出预留',exact:true});assert.equal(await target.inputValue(),'4096');await visible(text('ChatGPT 账号：主人日常账号'));}
  if(scene==='selection-unknown'){target=page.getByRole('alert');await visible(button('重新读取账号状态'));}
 }
 if(workspace==='wire'){
  if(scene==='request-policy')target=page.getByRole('textbox',{name:'每次最多携带的工具数',exact:true});
  if(scene==='output-field-rules')target=page.getByText(/^Chat 的输出上限只有在模型目录已明确字段/);
 }
 if(workspace==='cache'){
  if(scene==='unconfirmed-cost'){target=page.getByText(/缓存读取、写入和费用缺失时显示未知，不算免费/);assert.equal(await page.getByRole('switch',{name:'请求临时前缀缓存'}).isDisabled(),true);}
  if(scene==='explicit-cache-draft'){target=page.getByRole('switch',{name:'请求临时前缀缓存'});assert.equal(await target.isChecked(),true);}
 }
 assert.ok(target,`Unique model scene target: ${workspace}/${scene}`);return stabilizeSceneTarget(page,target,`${workspace}/${scene}`);
}
