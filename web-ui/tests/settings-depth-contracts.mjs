import {expect} from '@playwright/test';
/** A capture must prove its intended live page/layer, not just a mounted shell. */
export async function assertSettingsScene(page,scene){
 const id=scene.id;
 const visible=async locator=>{await expect(locator).toBeVisible();return locator};
 const text=name=>page.getByText(name,{exact:true});
 const button=name=>page.getByRole('button',{name,exact:true});
 const dialog=async name=>{const target=page.locator('.pw-settings-layer[data-visible="true"] .pw-settings-dialog').filter({has:page.getByRole('heading',{name,exact:true})});await expect(target).toHaveCount(1);await expect(target).toHaveCSS('opacity','1');return visible(target)};
 let anchor;
 const dialogs={
 'model-switch-review':'切换工作模型','model-diagnostics-review':'确认模型能力实测','token-budget-editor':'新任务默认预算',
 'accounts-add':'添加 ChatGPT 账号','accounts-model-review':'账号工作模型','accounts-logout':'退出这个账号？',
 'mcp-tools':'当前安装的工具与权限','mcp-enable':'停用这项安装？','mcp-grant':'撤销工具权限？','mcp-install':'核对这次发现并安装',
 'archive-webdav-edit':'WebDAV 备份目录','archive-password':'设置备份口令','archive-review':'确认恢复这个分区？','archive-cache-discard':'移除本机备份缓存？','archive-upload-review':'确认上传这个新版本？',
 'storage-policy':'保留建议','storage-review':'仅清理这些固定范围？','remote-editor':'电脑环境连接','remote-new':'电脑环境连接','remote-remove':'移除这个连接？','ssh-editor':'SSH 连接','ssh-new':'SSH 连接','remote-task-review':'确认在所选电脑创建工作','ssh-command-review':'核对这次 SSH 操作','ssh-terminal-review':'核对这次 SSH 操作',
 'profiles-create':'新建工作配置','profiles-edit':'编辑 · 日常研究','profiles-sources':'编辑 · 日常研究','profiles-selected':'编辑 · 日常研究','background-audio-review':'按原记录设置声音？','background-practice-review':'明确开始手机演练？',
 };
 if(dialogs[id])anchor=await dialog(dialogs[id]);
 else if(id==='settings-home')anchor=await visible(page.locator('.pw-settings-frequent'));
 else if(id==='settings-search'){await expect(page.getByRole('searchbox',{name:'搜索设置'})).toHaveValue('账号');anchor=await visible(page.locator('.pw-settings-results'));await expect(anchor).toContainText('ChatGPT 账号')}
 else if(id.startsWith('settings-group-')){anchor=await visible(page.locator('.pw-settings-group-page'));const title={models:'模型与连接',devices:'设备与执行',work:'工作与数据',memory:'记忆与主动任务',extensions:'协作与扩展',media:'语音与媒体'}[id.slice('settings-group-'.length)];await expect(page.getByRole('heading',{name:title,exact:true})).toBeVisible()}
 else if(id==='model-catalog')anchor=await visible(page.locator('.pw-model-catalog:not([hidden])'));
 else if(id==='model-detail'){anchor=await visible(page.locator('.pw-model-detail[aria-label="模型能力"]'));await expect(page.getByRole('heading',{name:'research-model',exact:true})).toBeVisible()}
 else if(id==='model-connections'||id==='token-budget-empty')anchor=await visible(page.locator('[data-model-layer="connections"]'));
 else if(id==='model-connection-detail')anchor=await visible(page.locator('[data-model-layer="connection"]'));
 else if(id==='model-api-edit'||id==='model-api-add'){anchor=await visible(page.locator('[data-model-layer="edit"]'));await expect(page.getByRole('textbox',{name:'接口地址',exact:true})).toBeVisible()}
 else if(id==='model-api-options')anchor=await visible(page.getByRole('textbox',{name:'每次回复的输出上限',exact:true}));
 else if(id==='model-api-compatibility')anchor=await visible(page.getByRole('checkbox',{name:'使用连接兼容档案',exact:true}));
 else if(id==='model-api-cache')anchor=await visible(page.getByText('当前协议尚未接入显式缓存提示，保持关闭。服务商若返回缓存用量，仍按原调用回执显示。',{exact:true}));
 else if(id==='model-bedrock-edit')anchor=await visible(page.getByRole('textbox',{name:'AWS 区域',exact:true}));
 else if(id==='model-diagnostics')anchor=await visible(page.locator('.pw-model-diagnostics-body'));
 else if(id==='model-diagnostics-logs')anchor=await visible(page.locator('section[aria-label="运行阶段与请求记录"]'));
 else if(id==='token-budget-read')anchor=await visible(button('设置新任务默认值'));
 else if(id.startsWith('phone-')){
  const view=id==='phone-home'?'list':id==='phone-appearance-error'?'appearance':id==='phone-speech-missing'?'speech':id.slice(6);
  anchor=await visible(page.locator(`[data-phone-view="${view}"]`));
  if(view==='appearance')await expect(page.locator('.pw-typography-specimens:not([hidden])')).toBeVisible();
  if(id==='phone-appearance-error'){await expect(page.locator('.pw-typography-settings').getByRole('alert').filter({hasText:'字体偏好写入未完成'})).toBeVisible();await expect(button('重试保存')).toBeEnabled()}
  if(id==='phone-speech-missing')await expect(page.getByText('系统朗读缺少中文语音资源，请在系统设置中安装',{exact:false})).toBeVisible();
 }
 else if(id==='background-pair'||id==='background-manual'){anchor=await visible(page.locator('[data-background-panel="setup"]'));await expect(page.getByRole('heading',{name:'与这台手机配对',exact:true})).toBeVisible();if(id.endsWith('manual'))await expect(page.getByRole('textbox',{name:'当前配对窗口的端口',exact:true})).toBeVisible()}
 else if(id==='background-connect')anchor=await visible(page.getByRole('heading',{name:'助手连接已保留',exact:true}));
 else if(id==='background-failure')anchor=await visible(page.getByRole('alert').filter({hasText:'连接未确认，请核对当前系统端口。'}));
 else if(id==='background-checks'){anchor=await visible(page.locator('[data-background-panel="checks"]'));await expect(page.getByRole('heading',{name:'本机能力检查',exact:true})).toBeVisible()}
 else if(id==='background-diagnostic')anchor=await visible(button('预览脱敏诊断报告'));
 else if(id==='background-practice')anchor=await visible(button('自有测试页后台演练…'));
 else if(id==='background-clipboard')anchor=await visible(page.getByRole('switch',{name:'允许可恢复的剪贴板粘贴',exact:true}));
 else if(['background-apps','background-recovery','background-advanced'].includes(id)){
  const labels={'background-apps':'应用分享限制与可选粘贴','background-recovery':'断连与重启后的恢复','background-advanced':'高级兼容与原连接状态'};
  anchor=page.getByRole('button',{name:labels[id]}).first();await expect(anchor).toHaveAttribute('aria-expanded','true');
 }
 else if(id==='task-history'||id==='model-usage'){
  const records=page.locator('.pw-settings-record-list');
  if(await records.count()){anchor=await visible(records.getByRole('button',{name:/核对资料来源并整理测试报告/}));await expect(anchor).toBeEnabled()}
  else anchor=await visible(text('核对资料来源并整理测试报告'));
  await expect(anchor).toContainText('核对资料来源并整理测试报告');
 }
 else if(id==='accounts-home')anchor=await visible(text('私人 ChatGPT'));
 else if(id==='accounts-model-catalog')anchor=await visible(button('核对能力与选项'));
 else if(id==='accounts-expired')anchor=await visible(page.getByRole('status').filter({hasText:'这次登录授权已过期'}));
 else if(id==='mcp-home'){anchor=await visible(text('资料检索服务'));await expect(button('查看工具与权限')).toBeVisible()}
 else if(id.startsWith('archive-')){
  const view={'archive-home':'list','archive-create':'create','archive-import':'import','archive-snapshot-export':'snapshot','archive-snapshot-restore':'snapshot','archive-restore-settings':'snapshot','archive-webdav':'webdav','archive-results':'result'}[id];
  if(!view)throw new Error('Missing archive scene contract: '+id);
  anchor=await visible(page.locator(`.pw-archive-workspace[data-data-view="${view}"]`));
  if(id==='archive-snapshot-export')await expect(button('设置口令并导出…')).toBeVisible();
  if(id==='archive-snapshot-restore'||id==='archive-restore-settings')await expect(page.getByRole('tab',{name:'恢复分区',exact:true})).toHaveAttribute('aria-selected','true');
  if(id==='archive-restore-settings')await expect(button('审阅恢复此分区').first()).toBeVisible();
 }
 else if(id.startsWith('storage-')){
  const view={'storage-home':'list','storage-cleanup':'cleanup','storage-caches':'caches','storage-diagnostics':'diagnostics','storage-results':'result','storage-backup-error':'list'}[id];
  if(!view)throw new Error('Missing storage scene contract: '+id);
  anchor=await visible(page.locator(`.pw-storage-workspace[data-data-view="${view}"]`));
  if(id==='storage-diagnostics')await expect(text('PocketWorld — 脱敏诊断测试快照\n应用版本: fixture\n连接状态: 待核对\n不会自动上传')).toBeVisible();
  if(id==='storage-backup-error')await expect(page.getByRole('alert').filter({hasText:'本机备份缓存读取失败'})).toBeVisible();
 }
 else if(id==='remote-home'||id==='ssh-home')anchor=await visible(page.locator('[data-connection-view="list"]'));
 else if(id==='remote-overview'||id==='ssh-overview')anchor=await visible(page.locator(`[data-connection-view="profile:${id.startsWith('remote')?'remote-a':'ssh-a'}"]`));
 else if(id==='remote-jobs')anchor=await visible(page.locator('[data-connection-view="all-jobs"]'));
 else if(id==='remote-work')anchor=await visible(page.locator('.pw-remote-work'));
 else if(id==='remote-work-ready'){anchor=await visible(page.getByRole('textbox',{name:'这次要完成什么',exact:true}));await expect(anchor).toHaveValue('整理原项目的测试报告')}
 else if(id==='ssh-work')anchor=await visible(page.locator('[data-ssh-view="list"]'));
 else if(id.startsWith('ssh-')){
  const view={'ssh-agent':'agent','ssh-files':'files','ssh-files-loaded':'files','ssh-file-editor':'files','ssh-command':'command','ssh-terminal':'terminal','ssh-doctor':'doctor'}[id];
  if(!view)throw new Error('Missing SSH scene contract: '+id);
  anchor=await visible(page.locator(`[data-ssh-view="${view}"]`));
  if(id==='ssh-files-loaded')await expect(page.locator('[data-operation-entry="README.md"]')).toBeVisible();
  if(id==='ssh-file-editor')anchor=await visible(page.getByRole('textbox',{name:'原文件内容',exact:true}));
 }
 else if(id==='profiles-home')anchor=await visible(button('打开工作配置：日常研究'));
 else throw new Error('No intended-scene contract: '+id);
 if(id.startsWith('phone-'))anchor=id==='phone-home'?page.locator('.pw-phone-overview'):page.locator('.pw-phone-workspace .pw-data-operation-heading h2');
 if(!dialogs[id]&&id.startsWith('archive-')&&id!=='archive-home')anchor=page.locator('.pw-archive-workspace .pw-data-operation-heading h2');
 if(!dialogs[id]&&id.startsWith('storage-')&&!['storage-home','storage-backup-error'].includes(id))anchor=page.locator('.pw-storage-workspace .pw-data-operation-heading h2');
 if(id==='background-pair')anchor=page.locator('.pw-background-heading');
 if(id==='profiles-sources'||id==='profiles-selected')await expect(page.getByRole('tab',{name:id.endsWith('sources')?'资料与工具':'已选引用',exact:true})).toHaveAttribute('aria-selected','true');
 await anchor.scrollIntoViewIfNeeded();await expect(anchor).toBeVisible();
 await expect.poll(()=>anchor.evaluate(node=>!node.closest('[hidden],[inert],[aria-hidden="true"]'))).toBe(true);
 // Two stable layout frames after the target layer becomes live.
 await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 const first=await anchor.boundingBox();await page.waitForTimeout(80);const second=await anchor.boundingBox();
 expect(first).not.toBeNull();expect(second).not.toBeNull();expect(Math.abs(first.y-second.y)).toBeLessThan(1);
 return anchor;
}
