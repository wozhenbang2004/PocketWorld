/** Mounted production components, deterministic native-state fixtures. No
 * Android process, native preview renderer, real provider or user file claim. */
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ProjectFileEditor } from '../src/ProjectFileEditor';
import { ProjectPreviewPane } from '../src/ProjectPreviewPane';
import { ProjectExecutionWorkspace } from '../src/ProjectExecutionWorkspace';
import { AttachmentWorkspace } from '../src/AttachmentWorkspace';
import { AttachmentOriginalReader } from '../src/AttachmentOriginalReader';
import { MediaWorkspace } from '../src/MediaWorkspace';
import { LocalWorkspace } from '../src/LocalWorkspace';
import { ProxyWorkspace } from '../src/ProxyWorkspace';
import { AttachmentDocumentEditor, AttachmentProcessingEditor } from '../src/AttachmentDocumentEditor';
import { ProjectLivePreview } from '../src/ProjectLivePreview';
import { ProjectRuntimeHealth } from '../src/ProjectRuntimeHealth';
import { bridge } from '../src/bridge';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

const calls: any[] = [], scene = new URLSearchParams(location.search).get('scene') || 'file-edit';
const png = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"><rect width="800" height="600" fill="#d8d9dc"/><rect x="180" y="150" width="440" height="300" rx="26" fill="#fafafa"/><text x="400" y="310" font-size="28" text-anchor="middle" fill="#505055">TEST ORIGINAL IMAGE</text></svg>');
const file = { id: 'file-fixture', sha256: 'a'.repeat(64), name: '产品设计审阅.png', mimeType: 'image/png', size: 1048576, parentArtifact: 'parent-fixture', source: { taskId: 'task-fixture', artifactId: 'artifact-fixture', sha256: 'a'.repeat(64), provider: 'provider-fixture' } };
const original = { id: 'pin-fixture', kind: 'image', name: file.name, mimeType: file.mimeType, url: png, information: { width: 800, height: 600, sampled: false } };
const task = { id: 'task-fixture', goal: '为产品说明制作一张展示图片', summary: '图片已保存，请核对内容后选择下一步。', status: 'needs_confirmation', runId: 'run-fixture', executionId: 'execution-fixture', epoch: 1, raw: { feature: 'media', actorId: 'owner', spaceId: 'private', runId: 'run-fixture', developmentContext: { projectId: 'project-fixture' }, operations: [{ operationId: 'execution-fixture', tool: 'project_execute', runId: 'run-fixture', receipt: { executionReceipt: { terminalMode: 'pty' } } }] } };
const shellFrame = { revision: 1, generation: 1, cols: 80, rows: 14, packets: [btoa('> npm run build\r\n\r\nChecking source files...\r\nBuild completed.\r\n')] };
const execution = { handle: 'execution-view-fixture', taskId: task.id, executionId: 'execution-fixture', metadata: { taskId: task.id, executionId: 'execution-fixture', runId: 'run-fixture', projectId: 'project-fixture', checkoutId: 'checkout-fixture', workingDirectory: '/private/project-fixture', executable: 'sh', epoch: 1, observingOnly: false, terminalMode: 'pty', columns: 80, rows: 14, inputByteLimit: 65536, inputRequestLimit: 30, timeoutMillis: 300000 }, output: { executionId: 'execution-fixture', nativeStatus: 'completed', terminal: true, resourcesReleased: true, outputCursor: 4, screen: shellFrame } };
const mediaSnapshot = { configured: true, configurationRevision: 'config-fixture', configuration: { provider: 'openai_compatible', baseUrl: 'https://media.example.test/v1', imageModel: 'image-fixture', ttsModel: 'speech-fixture', voice: 'calm' }, imageEditingSupported: true, inputLimits: { imagePromptChars: 2000, speechTextChars: 4000 } };
const readMedia = { taskId: task.id, task, viewHandle: 'media-view-fixture', operation: { operationId: 'media-operation-fixture' }, receipt: { status: 'response_received', providerStatus: '生成响应已收到', message: '原服务返回一份已保存图片，请打开文件核对实际内容。', providerJobId: 'provider-job-fixture', attachments: [file] } };
const attachment = { open: true, ready: true, windowId: 'window-fixture', frameId: 'frame-fixture', taskId: task.id, file: scene === 'attachment-library' ? {} : file, snapshot: { sources: [file], artifacts: [file], media: [{ operationId: 'media-operation-fixture', status: 'completed', message: '图片已生成并保存到当前任务。' }] }, canRequestOcr: true, ...(scene === 'attachment-compare' ? { comparison: [original, { ...original, id: 'pin-current-fixture', name: '产品设计修订.png' }] } : {}) };
const state: any = { tasks: [task], projects: [{ id: 'project-fixture', name: '产品演示项目' }], moduleResults: {}, attachmentWorkspace: attachment, webWorkspaceResumeRevision: 1, features: { attachments: { attachments: [file] } } };
const proxy = { running: true, sessionId: 'proxy-fixture', endpoint: 'http://127.0.0.1:8765/v1', model: '工作模型 / API 连接', protocol: 'OpenAI-compatible', externalRelayAllowed: true, selectionRevision: 1, endpoints: ['/v1/chat/completions', '/v1/models'], recentInferenceReferences: [{ taskId: 'call-fixture', model: '工作模型' }] };
let liveMounted=false, documentSnapshot:any=null;
const respond = (action: string, args: any = {}) => { calls.push({ action, args }); if(action==='projectLive.show')liveMounted=true;return action === 'media.snapshot' ? mediaSnapshot : action === 'media.readTask' ? readMedia : action === 'proxy.snapshot' ? { ...proxy, ...(scene === 'proxy-settings' ? { running: false } : {}) } : action === 'local.terminal.query' ? { status: 'completed', message: '进程已结束，现有输出保留。' } : action === 'local.terminal.open' ? execution : action === 'local.terminal.read' ? execution.output : action.startsWith('local.execution.') ? execution : action === 'attachmentWorkspace.viewer' ? original.information : action.startsWith('projectLive.') ? {taskId:task.id,pageHandle:'live-page-fixture',services:[{serviceHandle:'service-fixture',executionId:'execution-fixture',sourceGeneration:3}],mounted:liveMounted,observed:liveMounted?{sourceDigest:'b'.repeat(64)}:undefined} : action==='projectRuntime.read'?{taskId:task.id,readOnly:true,headline:'正在准备项目工具',caption:'只读观察当前项目登记的工具状态。',phase:'downloading',fraction:.65,executable:false,resumableDownload:true,admissionMessage:'原下载可恢复，实际执行仍由任务审阅。',details:['Node 22.22.0 · 项目固定工具版本','来源和保留范围：原任务、原私人副本']}:{}; };
window.PocketNative = { postMessage: bytes => { const request = JSON.parse(bytes); queueMicrotask(() => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value: respond(request.action, request.args) })); } };
window.PocketWeb.hostConfiguration?.({ documentToken: 'artifact-fixture-token' });
window.PocketWeb.receive({ v: 1, kind: 'state', revision: 1, snapshot: { state, view: { taskId: task.id, section: 'projects', resumeRevision: 1 }, composer: {}, capabilities: { commands: ['attachmentWorkspace.viewer', 'attachmentWorkspace.action', 'projects.preview.viewport'] } } });
void bridge.bindHostDocument();
let back: (() => boolean) | null = null;
const register = (handler: (() => boolean) | null) => { back = handler; };
(window as any).artifactFixture = { calls, back: () => back?.(), scene };

function Fixture() {
  const [mode, setMode] = useState<any>(scene === 'file-history' ? 'history' : 'edit'), [text, setText] = useState('export const product = {\n  name: "PocketWorld",\n  description: "让任务、文件与成果保持连贯。",\n};\n'), [revision, setRevision] = useState<number | null>(null);
  const [closed, setClosed] = useState(false);
  const documentDraft:any=scene==='slides-edit'?{name:'产品方案演示',format:'pptx',parentArtifactId:'parent-fixture',presentation:{slides:[{id:'slide-fixture',title:'产品方案',paragraphs:['围绕当前任务组织界面。','保留原文件、版本和来源。'],notes:'用于复核的测试讲稿。',shapes:[{id:'shape-1',text:'第一版方案',x:10,y:10,width:200,height:50}]}]}}:{name:'产品方案说明',format:'docx',title:'PocketWorld 产品方案',fontPoints:12,body:'# 当前任务\n\n让任务、文件与成果保持连贯。\n\n保存为独立新文件，并保留原版本。'};
  if(!documentSnapshot)documentSnapshot=structuredClone(documentDraft);
  const documentAct=async(command:string,args:any={})=>{calls.push({action:command,args});if(command==='updateDocument')documentSnapshot=structuredClone(args.draft);return command==='updateDocument'?{workspace:{ready:true,windowId:'window-fixture',frameId:'updated-document-fixture'}}:command==='reviewDocument'?{reviewId:'review-document-fixture',draft:documentSnapshot}:{};};
  const control: any = { expanded: !closed, view: { sessionId: 'preview-fixture', ready: false, taskId: task.id, selecting: false, selectionAvailable: false, selectionUnavailableReason: '测试宿主没有原生渲染器。', metadata: { projectId: 'project-fixture', previewId: 'preview-fixture', entryPath: 'dist/index.html', origin: 'fixed_workspace_project_artifact', artifactId: 'artifact-fixture', artifactSha256: 'a'.repeat(64) } }, busy: '', error: scene === 'preview-error' ? '当前固定预览无法加载，请重新核对入口文件。' : '', unknown: false, blocked: false, can: () => true, action: (action: string) => calls.push({ action }), hide: () => setClosed(true), returnToPackageTask: () => calls.push({ action: 'tasks.open' }) };
  const run = async (action: string, args?: any) => respond(action, args);
  return <main className="pw-redesign pw-secondary-scroll" style={{ height: '100dvh', overflow: 'auto', padding: '24px 20px', maxWidth: 1100, margin: '0 auto' }}>
    {scene.startsWith('file-') ? <ProjectFileEditor path="src/product.ts" version={4} draft={{ text, baseline: 'export const product = {};\n', version: 4, dirty: true }} state={{ fileReady: true, fileHistory: [{ version: 4, at: 1800000000000 }, { version: 3, at: 1799900000000 }], revisionNumber: revision, revisionContent: revision === null ? undefined : 'export const product = { name: "原版本" };', ...(scene === 'file-conflict' ? { fileError: '保存前发现版本已变化，请比较当前文件与保留的草稿。' } : {}) }} conflict={scene === 'file-conflict'} mode={mode} setMode={setMode} can={() => true} edit={setText} save={() => calls.push({ action: 'projects.save' })} readHistory={() => calls.push({ action: 'projects.history' })} readRevision={value => { calls.push({ action: 'projects.revision', args: { revision: value } }); setRevision(value); }} retry={() => {}} copy={() => calls.push({ action: 'copy' })} adoption={{ pending: false, unknown: false, message: '', adopt: () => calls.push({ action: 'projects.adopt' }) }} registerBack={register}/> :
    scene.startsWith('preview') ? <div style={{ display: 'flex', height: 'calc(100dvh - 48px)' }}><ProjectPreviewPane control={control} composer={{ previewRefs: [] }}/></div> :
    scene.startsWith('attachment-') ? scene === 'attachment-original' ? <AttachmentOriginalReader source={original} windowId="window-fixture"/> : <AttachmentWorkspace state={state} registerBack={register}/> :
    scene.startsWith('media-') ? <MediaWorkspace state={state} run={run} registerTransientBack={register}/> :
    scene.startsWith('local-') ? <LocalWorkspace state={{ ...state, tasks: [{ ...task, raw: { ownerLocalTerminal: true, runId: 'run-fixture', coordinatorEpoch: 1 }, goal: '检查项目构建输出', status: 'completed' }] }} data={{ checkouts: [{ id: 'checkout-fixture', projectId: 'project-fixture', name: '设计工作副本', createdAt: 1800000000000 }], jobs: [{ id: 'job-fixture', operationId: 'operation-fixture', status: 'completed', exitCode: 0, message: '构建已结束，请检查原文件成果。' }] }} run={run} supported={() => true} registerTransientBack={register}/> :
    (scene==='document-edit'||scene==='slides-edit') ? <AttachmentDocumentEditor original={documentDraft} windowId="window-fixture" foregroundReady={true} busy={false} act={documentAct} beforeBack={()=>{}}/> :
    scene==='image-processing' ? <AttachmentProcessingEditor info={{width:800,height:600,editable:true}} review={{}} busy={false} act={documentAct}/> :
    scene==='project-live' ? <ProjectLivePreview taskId={task.id} run={run} onBackChange={register}/> :
    scene==='runtime-health' ? <ProjectRuntimeHealth taskId={task.id} run={run}/> :
    scene.startsWith('proxy-') ? <ProxyWorkspace state={state} run={run} registerTransientBack={register}/> :
    <ProjectExecutionWorkspace task={task} run={run}/>}
  </main>;
}
createRoot(document.getElementById('root')!).render(<Fixture/>);
