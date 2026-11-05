import {test} from 'node:test';
import assert from 'node:assert/strict';
import {attachmentActionPayload,attachmentDocumentKey,attachmentFrame,documentTable,editSlideText,exactFileTarget,processingDefaults} from '../src/attachment-workspace.ts';

test('queued attachment actions retain the displayed frame and reviewed operation identity',()=>{
 const visible={ready:true,windowId:'window-a',frameId:'source-a'},captured=attachmentFrame(visible);
 const command=attachmentActionPayload(captured,'applyProcessing',{reviewId:'review-a',acknowledge:true,frameId:'untrusted-new-frame',windowId:'untrusted-window'});
 visible.frameId='source-b';visible.windowId='window-b';
 assert.deepEqual(command,{reviewId:'review-a',acknowledge:true,frameId:'source-a',windowId:'window-a',command:'applyProcessing'});
 assert.equal(attachmentFrame({ready:false,windowId:'window-a',frameId:'source-a'}),undefined);
 assert.throws(()=>attachmentActionPayload(undefined,'applyProcessing',{reviewId:'review-a'}));
 const acknowledged=attachmentFrame({ready:true,windowId:'window-a',frameId:'updated-draft'});
 assert.equal(attachmentActionPayload(acknowledged,'reviewDocument').frameId,'updated-draft');
});
test('foreground renewal retains only the editor for the same task and fixed document sources',()=>{
 const view={taskId:'task-a',windowId:'before',documentDraft:{parentArtifactId:'original',parentSha256:'a'.repeat(64),editableSourceId:'edit',editableSourceSha256:'b'.repeat(64)}};
 assert.equal(attachmentDocumentKey(view),attachmentDocumentKey({...view,windowId:'after',documentDraft:{...view.documentDraft,body:'retained local text'}}));
 assert.notEqual(attachmentDocumentKey(view),attachmentDocumentKey({...view,taskId:'task-b'}));
 assert.notEqual(attachmentDocumentKey(view),attachmentDocumentKey({...view,documentDraft:{...view.documentDraft,editableSourceSha256:'c'.repeat(64)}}));
});

test('PPTX text edits preserve source pins, original page order and exact object geometry',()=>{
 const input={format:'pptx',parentArtifactId:'original',parentSha256:'a'.repeat(64),editableSourceId:'source',editableSourceSha256:'b'.repeat(64),presentation:{version:1,slides:[{id:'one',title:'Keep',paragraphs:['','first'],notes:'original'},{id:'two',title:'Edit',paragraphs:['','line one\nline two'],notes:'notes',shapes:[{id:'object',text:'before',x:1.25,y:5.5,width:3.5,height:.75}]}]}};
 let output=editSlideText(input,1,'paragraph','same paragraph\nnew second line',1);output=editSlideText(output,1,'shape','<text> & 😀',0);output=editSlideText(output,1,'notes','notes\n\nlast\n');
 assert.deepEqual(output.presentation.slides[0],input.presentation.slides[0]);assert.equal(output.presentation.slides[1].paragraphs[0],'');assert.equal(output.presentation.slides[1].paragraphs[1],'same paragraph\nnew second line');assert.equal(output.presentation.slides[1].notes,'notes\n\nlast\n');assert.deepEqual({...output.presentation.slides[1].shapes[0],text:'before'},input.presentation.slides[1].shapes[0]);
 for(const field of ['parentArtifactId','parentSha256','editableSourceId','editableSourceSha256'])assert.equal(output[field],input[field as keyof typeof input]);assert.equal(input.presentation.slides[1].shapes[0].text,'before');
 assert.throws(()=>editSlideText(input,2,'title','wrong page'));assert.throws(()=>editSlideText(input,1,'paragraph','new paragraph',2));
});
test('exact attachment version and availability are required for selection',()=>{
 assert.deepEqual(exactFileTarget({id:'a',sha256:'b'.repeat(64)}),{fileId:'a',sha256:'b'.repeat(64)});
 assert.equal(exactFileTarget({id:'a',sha256:'b'.repeat(64),availability:'missing'}),null);assert.equal(exactFileTarget({id:'a'}),null);assert.equal(exactFileTarget({id:'',sha256:'b'.repeat(64)}),null);
});
test('processing defaults respect real source ratio and bounded media duration',()=>{
 const image=processingDefaults({width:6000,height:2000});assert.equal(image.right,6000);assert.equal(image.bottom,2000);assert.equal(image.width,2000);assert.equal(image.height,666);assert.equal(image.rotation,0);
 assert.deepEqual(processingDefaults({durationMillis:300000}),{startMillis:0,endMillis:120000});assert.deepEqual(processingDefaults({durationMillis:31000}),{startMillis:0,endMillis:31000});
});
test('table projection joins chunks within the same paragraph but keeps paragraph and cell boundaries',()=>{
 const table=documentTable([{unit:1,text:'same ',locator:{cell:'B2',paragraph:'1'}},{unit:1,text:'paragraph',locator:{cell:'B2',paragraph:'1'}},{unit:2,text:'next',locator:{cell:'B2',paragraph:'2'}},{unit:2,text:'separate',locator:{cell:'AA10',paragraph:'1'}}]);
 assert.deepEqual(table.rows,['2','10']);assert.deepEqual(table.columns,['B','AA']);assert.equal(table.text('2','B'),'same paragraph\nnext');assert.equal(table.text('10','AA'),'separate');assert.equal(table.text('2','AA'),'');
});
