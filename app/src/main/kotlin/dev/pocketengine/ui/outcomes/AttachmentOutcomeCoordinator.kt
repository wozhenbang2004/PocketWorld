package dev.pocketengine.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.documents.DocumentContent
import dev.pocketengine.features.attachments.TeamWorkspaceOutcomeHost
import dev.pocketengine.features.attachments.AttachmentRepository
import dev.pocketengine.features.attachments.creation.DocumentDraft
import dev.pocketengine.features.attachments.creation.StructuredOfficeDraft
import dev.pocketengine.media.jobs.results.GroupResultAccess
import dev.pocketengine.ui.groups.GroupArtifactSelection
import org.json.JSONObject
import java.util.concurrent.Executor

internal data class AttachmentOutcomeState(
    val open:Boolean=false,val taskId:String?=null,val group:GroupResultAccess.Selection?=null,
    val snapshot:JSONObject?=null,val file:JSONObject?=null,val document:JSONObject?=null,
    val documentOpen:Boolean=false,val choosingSource:Boolean=false,val busy:Boolean=false,
    val error:String?=null,val progress:JSONObject?=null,val observing:Boolean=false,val notice:String?=null,val thumbnail:android.graphics.Bitmap?=null,
    val team:TeamWorkspaceOutcomeHost.Selection?=null,val teamDifference:JSONObject?=null,val teamPrevious:TeamWorkspaceOutcomeHost.Selection?=null,
    val originalFile:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null,
    val documentDraft:JSONObject?=null,val pendingDocumentTask:String?=null,
    val processingSource:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null,val processingInfo:JSONObject?=null,
    val processingReview:dev.pocketengine.features.attachments.processing.NativeFileProcess.Review?=null,val pendingProcessingTask:String?=null,
    val comparison:Pair<dev.pocketengine.features.attachments.OwnerFilePreviewSource,dev.pocketengine.features.attachments.OwnerFilePreviewSource>?=null
)

/** Navigation and revocable waits only. TaskStore, canonical artifact stores and native owner gate retain truth. */
internal class AttachmentOutcomeCoordinator(
    private val activity:ComposeActivity,private val app:EngineApplication,private val io:Executor,
    private val main:Handler,private val ownerWindow:()->String,private val originalTask:(String)->Unit,private val releaseWindow:()->Unit
) {
    var state by mutableStateOf(AttachmentOutcomeState());private set
    private var pendingDocumentAction:String?=null;private var pendingProcessingAction:String?=null
    private var restoredDocumentDraft:JSONObject?=null
    private var generation=0L;private var wait:RunControl?=null;private var observation:RunControl?=null
    private var export:JSONObject?=null;private var restoreFile:JSONObject?=null;private var exportResult:Uri?=null;private var resumed=false;private var closed=false
    private var teamHost:TeamWorkspaceOutcomeHost?=null;private var originalAssignment:((JSONObject)->Unit)?=null
    fun bindTeamOutcomes(host:TeamWorkspaceOutcomeHost,onAssignment:(JSONObject)->Unit){check(!state.open&&wait==null&&export==null){"请先关闭当前成果或完成原导出"};teamHost=host;originalAssignment=onAssignment}
    fun openTeamArtifact(selected:TeamWorkspaceOutcomeHost.Selection)=openTeamArtifactInternal(selected,null)
    private fun openTeamArtifactInternal(selected:TeamWorkspaceOutcomeHost.Selection,previous:TeamWorkspaceOutcomeHost.Selection?){cancel();restoreFile=null;restoredDocumentDraft=null;state=AttachmentOutcomeState(open=true,taskId=selected.toJson().getString("taskId"),team=selected,teamPrevious=matchingPrevious(selected,previous));readTeamFile()}
    private fun matchingPrevious(selected:TeamWorkspaceOutcomeHost.Selection,previous:TeamWorkspaceOutcomeHost.Selection?):TeamWorkspaceOutcomeHost.Selection? {
        val old=previous?.toJson()?:return null;val current=selected.toJson();return previous.takeIf{listOf("teamRunId","assignmentId","memberId","taskId","targetProjectId","path").all{key->old.getString(key)==current.getString(key)}&&old.optInt("assignmentRevision",1)<current.optInt("assignmentRevision",1)}
    }
    fun readTeamFile(offset:Int=0){val selected=state.team?:return;work{c,_->requireTeamHost().preview(selected,offset,12000,c) to null}}
    fun readNewTeamArtifact(){val selected=state.team?:return;work(onSuccess={actual->val next=actual.optJSONObject("newerArtifact");if(next==null)state=state.copy(notice=actual.getString("message"))else openTeamArtifactInternal(TeamWorkspaceOutcomeHost.Selection(next),selected)}){c,_->requireTeamHost().newerArtifact(selected,c) to null}}
    fun readPreviousTeamArtifact(){state.teamPrevious?.let(::openTeamArtifact)}
    fun canReadTeamDifference():Boolean=teamHost?.supportsDifference()==true
    fun readTeamDifference(offset:Int=0){val selected=state.team?:return;val previous=state.teamDifference
        work(teamDifference=true){c,_->val actual=requireTeamHost().difference(selected,offset,12000,c);if(offset>0&&previous!=null&&(actual.getInt("currentVersion")!=previous.getInt("currentVersion")||actual.getString("currentHash")!=previous.getString("currentHash")))throw IllegalStateException("原项目在比较时已变化，请从头查看实际差异");actual to null}
    }
    private fun requireTeamHost():TeamWorkspaceOutcomeHost=teamHost?:throw IllegalStateException("原团队文件成果读取尚未挂载，请回原分工核对")
    fun openTask(task:String?) { cancel();restoreFile=null;restoredDocumentDraft=null;state=AttachmentOutcomeState(open=true,taskId=task,pendingDocumentTask=pendingDocumentAction,pendingProcessingTask=pendingProcessingAction);refresh() }
    fun openGroup(selection:GroupArtifactSelection) {
        cancel();restoreFile=null;restoredDocumentDraft=null;state=AttachmentOutcomeState(open=true,taskId=selection.taskId,group=GroupResultAccess.Selection(selection.requestId,selection.taskId,selection.spaceId,selection.outputId,selection.artifactId,selection.audience,selection.visibility,selection.sourceOperationId.orEmpty(),selection.sourceTaskRunId.orEmpty()))
        refresh()
    }
    fun openGroupOperation(task:UiTask,operation:String){
        val origin=task.raw.optJSONObject("origin")?:return;cancel();restoredDocumentDraft=null;state=AttachmentOutcomeState(open=true,taskId=task.id,group=GroupResultAccess.Selection(origin.getString("requestId"),task.id,origin.getString("spaceId"),"","",origin.getString("audience"),origin.getString("visibility"),operation,""));refresh()
    }
    fun openFile(meta:JSONObject) {
        cancel();restoredDocumentDraft=null;state=AttachmentOutcomeState(open=true,pendingDocumentTask=pendingDocumentAction,pendingProcessingTask=pendingProcessingAction);val id=meta.optString("id");val sha=meta.optString("sha256")
        work { control,_ ->
            val actual=app.ownerOutcomeSnapshot(null,control);val file=(actual.optJSONArray("sources").objects()+actual.optJSONArray("artifacts").objects()).firstOrNull{it.optString("id")==id&&it.optString("sha256")==sha}?:throw SecurityException("文件当前版本不可核对，请刷新附件库")
            actual to file
        }
    }
    fun close(){cancel();restoredDocumentDraft=null;releaseWindow();state=AttachmentOutcomeState()}
    fun detach(){closed=true;close()}
    fun cancel(){generation++;wait?.cancel();wait=null;observation?.cancel();observation=null;state=state.copy(busy=false,observing=false)}
    fun foreground(visible:Boolean){resumed=visible;if(!visible){cancel();return};if(exportResult!=null)finishExport()else if(state.open&&state.snapshot==null&&!state.busy)refresh()}
    fun refresh(){
        if(state.team!=null){readTeamFile();return}
        val task=state.taskId;val group=state.group;val pin=restoreFile?:state.file;val savedDraft=restoredDocumentDraft
        work(onSuccess={actual->
            if(group==null&&actual.optString("originalDocumentActionId").isNotEmpty()&&actual.optString("taskStatus")!in setOf("completed","failed")){pendingDocumentAction=actual.getString("originalDocumentActionId");state=state.copy(pendingDocumentTask=pendingDocumentAction)}
            if(savedDraft!=null&&restoredDocumentDraft===savedDraft){state=state.copy(documentDraft=JSONObject(savedDraft.toString()));restoredDocumentDraft=null}
        }){control,window -> if(group==null){
            val actual=app.ownerOutcomeSnapshot(task,control)
            val file=pin?.let{selected->(actual.optJSONArray("sources").objects()+actual.optJSONArray("artifacts").objects()).firstOrNull{it.optString("id")==selected.optString("id")&&it.optString("sha256")==selected.optString("sha256")&&it.optString("availability")=="available"}?:throw IllegalStateException("原文件版本当前不可读，请回原任务核对")}
            if(savedDraft!=null){
                DocumentDraft(savedDraft)
                if(savedDraft.has("parentArtifactId")){
                    check(pin!=null&&pin.optString("id")==savedDraft.getString("parentArtifactId")&&pin.optString("sha256")==savedDraft.getString("parentSha256")){"草稿不属于当前固定原文件，未换用其他来源"}
                    val current=app.ownerEditableDocument(task,savedDraft.getString("parentArtifactId"),savedDraft.getString("parentSha256"),control)
                    requireSameDocumentSelection(current,savedDraft)
                }
                control.check()
            }
            actual to file
        }else {check(savedDraft==null){"原主人文档草稿不能恢复到群成果"};groupCall(window){app.groupOutcome(group,control)} to null}}
    }
    fun selectFile(file:JSONObject){cancel();val group=state.group;val selected=if(group==null)null else GroupResultAccess.Selection(group.requestId,group.taskId,group.spaceId,file.getString("id"),file.getString("id"),group.audience,group.visibility,file.optString("sourceOperationId",group.sourceOperationHint),file.optString("sourceTaskRunId",group.sourceRunHint));state=state.copy(group=selected?:group,file=JSONObject(file.toString()),document=null,documentOpen=false,error=null,notice=null,thumbnail=null,originalFile=null);if(file.optString("mimeType").startsWith("image/"))loadThumbnail()}
    fun backFromFile(){cancel();state=state.copy(group=state.group?.withoutArtifact(),file=null,document=null,documentOpen=false,thumbnail=null,originalFile=null);refresh()}
    fun chooseSource(){if(state.team!=null||state.group!=null||state.taskId.isNullOrBlank())return;state=state.copy(choosingSource=true,file=null,originalFile=null);work{c,_->app.ownerOutcomeSnapshot(null,c) to null}}
    fun attach(file:JSONObject){val task=state.taskId?:return;if(state.group!=null)return
        work{c,_->app.selectOutcomeForTask(task,file.getString("id"),file.getString("sha256"),c);app.ownerOutcomeSnapshot(task,c) to null}
        state=state.copy(choosingSource=false)
    }
    private fun originalAttachments()=app.features.module("attachments") as dev.pocketengine.features.attachments.AttachmentsModule
    fun openProcessing(){val file=state.file?:return;if(state.busy||state.group!=null||state.team!=null||pendingProcessingAction!=null)return;val task=state.taskId;var source:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null;work(onSuccess={info->source?.let{state=state.copy(processingSource=it,processingInfo=info)}}){c,_->source=app.attachmentOutcomes.originalFile(task,file.getString("id"),file.getString("sha256"),c);originalAttachments().inspectProcessingForOwner(source!!,c) to null}}
    fun closeProcessing(){if(!state.busy)state=state.copy(processingSource=null,processingInfo=null,processingReview=null)}
    fun forgetProcessingReview(){if(!state.busy)state=state.copy(processingReview=null)}
    fun reviewProcessing(args:JSONObject){val source=state.processingSource?:return;if(state.busy||state.group!=null||state.team!=null)return;val pin=JSONObject(args.toString());var review:dev.pocketengine.features.attachments.processing.NativeFileProcess.Review?=null;work(onSuccess={state=state.copy(processingReview=review)}){c,_->review=originalAttachments().reviewProcessingForOwner(source,pin,c);review!!.metadata() to null}}
    fun applyProcessing(){val review=state.processingReview?:return;if(state.busy||state.group!=null||state.team!=null||pendingProcessingAction!=null)return;val action=java.util.UUID.randomUUID().toString();pendingProcessingAction=action;state=state.copy(pendingProcessingTask=action);work(onSuccess={result->if(result.optBoolean("outputNotPublished")){pendingProcessingAction=null;state=state.copy(pendingProcessingTask=null,error=result.optString("message"),processingReview=null)}else if(result.optBoolean("found")){pendingProcessingAction=null;state=state.copy(pendingProcessingTask=null,processingSource=null,processingInfo=null,processingReview=null,taskId=action,file=result,snapshot=null,notice="新的加工原件已保存，父原件保留。可打开原件或与原版本对照。")}}){c,_->app.features.processFileForOwner(review,c,action) to null}}
    fun queryProcessing(){val action=state.pendingProcessingTask?:return;if(state.busy||state.group!=null||state.team!=null)return;work(onSuccess={result->if(result.optBoolean("found")){pendingProcessingAction=null;state=state.copy(pendingProcessingTask=null,processingSource=null,processingInfo=null,processingReview=null,taskId=action,file=result,snapshot=null,notice="已找到原加工动作的固定产物，未再次加工")}else state=state.copy(notice="原加工动作尚无完整发布回执，保留原编号，不自动重复。")}){c,_->app.features.fileProcessResultForOwner(action,c) to null}}
    fun compareImageParent(){val file=state.file?:return;if(state.busy||state.group!=null||state.team!=null)return;val parent=file.optJSONObject("parentArtifact")?:file.optJSONObject("source")?.optJSONObject("parentArtifact")?:return;val task=state.taskId;var pair:Pair<dev.pocketengine.features.attachments.OwnerFilePreviewSource,dev.pocketengine.features.attachments.OwnerFilePreviewSource>?=null;work(onSuccess={state=state.copy(comparison=pair)}){c,_->val selected=app.attachmentOutcomes.originalFile(task,file.getString("id"),file.getString("sha256"),c);val before=app.attachmentOutcomes.originalFile(null,parent.getString("id"),parent.getString("sha256"),c);check(selected.mimeType.startsWith("image/")&&before.mimeType.startsWith("image/")){"原父版本不是图片，未换用其他来源"};pair=before to selected;JSONObject().put("outcomeNotice","对照的是两份固定原图，不是最新版本或缩略图替代。") to null}}
    fun closeComparison(){state=state.copy(comparison=null)}
    fun newDocument(){if(state.busy||state.team!=null||state.group!=null||pendingDocumentAction!=null)return;restoredDocumentDraft=null;state=state.copy(documentDraft=JSONObject().put("format","docx").put("name","文档").put("title","").put("body","").put("fontPoints",12),error=null)}
    fun continueDocument(){val file=state.file?:return;if(state.busy||state.team!=null||state.group!=null||pendingDocumentAction!=null)return;restoredDocumentDraft=null;val task=state.taskId;work(onSuccess={value->state=state.copy(documentDraft=value)}){c,_->app.ownerEditableDocument(task,file.getString("id"),file.getString("sha256"),c) to null}}
    fun closeDocumentDraft(){if(!state.busy){restoredDocumentDraft=null;state=state.copy(documentDraft=null)}}
    private fun requireSameDocumentSelection(current:JSONObject,draft:JSONObject){
        for(key in listOf("parentArtifactId","parentSha256","editableSourceId","editableSourceSha256"))check(current.has(key)==draft.has(key)&&current.optString(key)==draft.optString(key)){"草稿的原成果或可编辑源已变化，请从原版本重新打开"}
        val format=current.optString("format");check(if(format in setOf("docx","pdf"))draft.optString("format") in setOf("docx","pdf")else draft.optString("format")==format){"原文档格式不可在此编辑中替换"}
    }
    private fun checkedDocumentDraft(draft:JSONObject):JSONObject{
        val current=state.documentDraft?:error("原编辑页已关闭")
        val pin=JSONObject(draft.toString());requireSameDocumentSelection(current,pin);DocumentDraft(pin);return pin
    }
    fun updateDocumentDraft(draft:JSONObject){
        if(state.busy||state.team!=null||state.group!=null||state.documentDraft==null||pendingDocumentAction!=null)return
        try{state=state.copy(documentDraft=checkedDocumentDraft(draft),error=null)}catch(failure:Exception){state=state.copy(error=safe(failure))}
    }
    fun createDocument(draft:JSONObject){if(state.busy||state.team!=null||state.group!=null||state.documentDraft==null||pendingDocumentAction!=null)return;val pin=try{checkedDocumentDraft(draft)}catch(failure:Exception){state=state.copy(error=safe(failure));return};val action=java.util.UUID.randomUUID().toString();pendingDocumentAction=action;state=state.copy(documentDraft=pin,pendingDocumentTask=action);work(onSuccess={actual->if(actual.optBoolean("outputNotPublished")){pendingDocumentAction=null;state=state.copy(pendingDocumentTask=null,error=actual.optString("message"),notice="原可编辑源已保存，可修正当前稿后明确生成新版本。")}else if(actual.optBoolean("found",true)){pendingDocumentAction=null;state=state.copy(documentDraft=null,pendingDocumentTask=null,taskId=actual.optString("hostTaskId",action),file=actual,snapshot=null,notice="已保存实际文档和原可编辑源。可打开原件核对排版，或明确导出/分享。")}}){c,_->val actual=app.features.createDocumentForOwner(pin,c,action);actual to null}}
    fun queryDocument(){val action=state.pendingDocumentTask?:return;if(state.busy||state.group!=null||state.team!=null)return;work(onSuccess={actual->if(actual.optBoolean("found")){pendingDocumentAction=null;state=state.copy(documentDraft=null,pendingDocumentTask=null,taskId=action,file=actual,snapshot=null,notice="已找到原操作的实际固定文档，没有重新生成")}else state=state.copy(notice="原文档尚无完整发布回执。可查看原任务和已保留源，不自动重复生成。")}){c,_->app.features.documentResultForOwner(action,c) to null}}

    fun openDocumentAction(id:String){
        if(state.busy||state.group!=null||state.team!=null||state.taskId!=null||state.documentDraft!=null)return
        val actions=state.snapshot?.optJSONObject("documentActions")?.optJSONArray("actions").objects()
        if(actions.none{it.optString("taskId")==id}){state=state.copy(error="原文档动作不在本页原生记录中，请刷新后明确选择。");return}
        pendingDocumentAction=id;openTask(id)
    }
    fun olderDocumentActions(){
        if(state.busy||state.group!=null||state.team!=null||state.taskId!=null||state.documentDraft!=null)return
        val snapshot=state.snapshot?:return;val page=snapshot.optJSONObject("documentActions")?:return
        val cursor=page.optString("nextCursor");if(!page.optBoolean("hasMore")||cursor.isEmpty())return
        work(onSuccess={actual->state=state.copy(snapshot=JSONObject(snapshot.toString()).put("documentActions",actual))}){c,_->app.attachmentOutcomes.documentActions(cursor,c) to null}
    }

    fun importSource(){if(state.team==null&&state.group==null)activity.pickAttachments(state.taskId,null)}
    fun imported(){if(state.open&&state.team==null&&state.group==null)refresh()}
    fun read(range:DocumentContent.Request){val file=state.file?:return;val group=state.group;val task=state.taskId
        work(document=true){c,w->val result=if(group==null)app.ownerOutcomeDocument(task,file.getString("id"),file.getString("sha256"),range,c) else groupCall(w){app.groupOutcomeDocument(group,range,c)};result to null}
    }
    fun canRequestOcr():Boolean {
        val file=state.file?:return false;val caps=state.snapshot?.optJSONObject("documentCapabilities")?:return false
        val actual=state.document?.takeIf{it.optString("attachmentId")==file.optString("id")&&it.optString("sha256")==file.optString("sha256")}
        return when(actual?.optString("format")?:file.optString("mimeType")) {
            "png","jpeg","webp","image/png","image/jpeg","image/webp"->caps.optBoolean("imageOcr")
            "pdf","application/pdf"->caps.optBoolean("pdfOcr")
            else->false
        }
    }
    /** Explicit owner OCR choice; the existing host still verifies the pinned bytes, scope and live engine. */
    fun readImageText(){
        if(!canRequestOcr()){state=state.copy(error="当前设备尚未提供此文件所需的本机文字识别；原文件仍可打开或导出");return}
        read(DocumentContent.Request(1,0,1,12000,48,"ocr"))
    }
    fun canSaveRead():Boolean {
        val file=state.file?:return false;val read=state.document?:return false
        return state.group==null&&read.optString("attachmentId")==file.optString("id")&&read.optString("sha256")==file.optString("sha256")&&read.optString("readReceiptId").isNotBlank()&&read.optJSONArray("chunks")?.length()?.let{it>0}==true
    }
    fun saveReadAsFile(){
        if(state.busy||!canSaveRead())return;val task=state.taskId;val file=state.file?:return;val receipt=state.document?.getString("readReceiptId")?:return
        work(replaceDocumentWithFile=true){c,_->
            val saved=app.attachmentOutcomes.saveOwnerDocumentExtract(task,file.getString("id"),file.getString("sha256"),receipt,c)
            val snapshot=app.ownerOutcomeSnapshot(task,c);val selected=snapshot.optJSONArray("artifacts").objects().firstOrNull{it.optString("id")==saved.getString("id")&&it.optString("sha256")==saved.getString("sha256")}?:throw IllegalStateException("摘录文件已保存，请刷新原任务成果核对；没有再次生成")
            snapshot.put("outcomeNotice","已将当前已读范围保存为文字成果，可以打开或导出；原文件保留，后续内容仍需另行读取") to selected
        }
    }
    private fun loadThumbnail(){val file=state.file?:return;val group=state.group;val task=state.taskId
        work{c,w->val image=if(group==null)app.attachmentOutcomes.thumbnail(task,file.getString("id"),file.getString("sha256"),c)else{var image:android.graphics.Bitmap?=null;groupCall(w){image=app.groupOutcomeThumbnail(group,c);JSONObject()};image?:throw IllegalStateException("图片预览未取得");}
            main.post{if(!closed&&state.open&&wait===c&&!c.isCancelled)state=state.copy(thumbnail=image)else image.recycle()};JSONObject() to null}
    }
    fun continueFromVersion(change:String){val task=state.taskId?:return;val file=state.file?:return;if(state.group!=null||change.isBlank())return
        work{c,_->val pin=app.selectOutcomeForTask(task,file.getString("id"),file.getString("sha256"),c)
            app.dispatchOwnerAction("updateTask",JSONObject().put("taskId",task).put("feedback","继续同一目标，基于以下固定来源版本处理，不改写原件。来源引用："+pin.toString()+"。正文只在所需范围内通过 attachments_read_document 读取。新的要求："+change.take(2000)))
            JSONObject().put("outcomeNotice","固定来源和补充已保存到原任务；回原任务继续，原文件保留") to null}
    }
    fun backFromDocument(){cancel();state=state.copy(documentOpen=false)}
    fun openOriginalFile(){
        if(state.busy)return;val selectedTeam=state.team;val selectedGroup=state.group;val selectedFile=state.file;val task=state.taskId
        if(selectedTeam==null&&selectedFile==null)return
        var source:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null
        work(onSuccess={source?.let{state=state.copy(originalFile=it)}}){c,w->
            source=when{
                selectedTeam!=null->requireTeamHost().originalFile(selectedTeam,c)
                selectedGroup!=null->app.groupOriginalFilePreview(selectedGroup,w?:error("请回原群成果重新打开"),c)
                else->app.attachmentOutcomes.originalFile(task,selectedFile!!.getString("id"),selectedFile.getString("sha256"),c)
            };JSONObject() to null
        }
    }
    fun backFromOriginalFile(){state=state.copy(originalFile=null)}
    fun shareOriginalFile(){
        if(state.busy||state.group!=null||state.team!=null)return;val file=state.file?:return;val task=state.taskId
        var prepared:Intent?=null;val issued=java.util.concurrent.atomic.AtomicBoolean(false)
        work(onSuccess={
            val intent=prepared?:return@work
            try{
                issued.set(true);intent.putExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,true)
                activity.startActivity(Intent.createChooser(intent,"分享这份固定原件"))
                state=state.copy(notice="已请求系统分享选择器；未取得接收应用送达或读取回执。")
            }catch(_:Exception){issued.set(false);intent.removeExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED);runCatching{app.attachmentOutcomes.discardUnissuedView(intent)};state=state.copy(error="系统分享未启动；原件保留，没有宣称已送达。")}
        }){c,_->
            val intent=app.attachmentOutcomes.share(task,file.getString("id"),file.getString("sha256"),c);prepared=intent
            c.onCancel(Runnable{if(!issued.get())runCatching{app.attachmentOutcomes.discardUnissuedView(intent)}})
            JSONObject().put("shareSheetRequested",false) to null
        }
    }
    fun openExternal(){val file=state.file?:return;val group=state.group;val task=state.taskId
        work{c,w->
            // Only this preparation may release its copy. Entry into Android launch wins over later cancellation.
            val disposition=java.util.concurrent.atomic.AtomicInteger(0) // prepared / launch entered / discarded
            fun discard(intent:Intent){runCatching{io.execute{runCatching{
                if(group==null)app.attachmentOutcomes.discardUnissuedView(intent)
                else dev.pocketengine.features.attachments.AttachmentViewSnapshots(app).discardUnissuedView(intent)
            }}}} // A rejected cleanup retains the existing TTL; never wait for snapshot disk IO on main.
            fun retain(intent:Intent):Intent {
                c.onCancel(Runnable{if(disposition.compareAndSet(0,2))discard(intent)})
                return intent
            }
            try{
                val intent=if(group==null)retain(app.attachmentOutcomes.open(task,file.getString("id"),file.getString("sha256"),c))else{
                    var prepared:Intent?=null;groupCall(w){prepared=retain(app.openGroupOutcome(group,c));JSONObject()};prepared?:throw IllegalStateException("原群文件未准备好")
                }
                if(!main.post{
                    if(!closed&&state.open&&wait===c&&!c.isCancelled&&resumed&&disposition.compareAndSet(0,1)){
                        intent.putExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,true)
                        try{activity.startActivity(intent)}catch(_:android.content.ActivityNotFoundException){
                            // This Android result explicitly means there was no reader, even if cancel ran during entry.
                            intent.removeExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED)
                            if(disposition.compareAndSet(1,2))discard(intent)
                            state=state.copy(error="当前没有可打开此类型的应用；可以导出文件后查看")
                        }catch(_:Exception){
                            // Android may already have handed off the URI. Keep the reader copy until its normal TTL.
                            state=state.copy(error="外部查看是否启动尚未确认；原文件保留，可以导出后查看")
                        }
                    }else c.cancel()
                })c.cancel()
                JSONObject().put("externalViewPrepared",true) to null
            }catch(failure:Exception){c.cancel();throw failure}
        }
    }
    fun prepareExport(){
        if(state.team!=null){val actual=state.snapshot?.optJSONObject("artifact")?:return;if(actual.optString("kind")=="file_tombstone"){state=state.copy(notice="删除修订没有文件正文；历史与原分工保留。");return};export=locator().put("provider","teams");activity.createOutcomeDocument(actual.getString("mimeType"),actual.getString("path").substringAfterLast('/'));return}
        val file=state.file?:return;export=locator().put("file",JSONObject(file.toString()));activity.createOutcomeDocument(file.optString("mimeType","application/octet-stream"),file.optString("name","群成果"))
    }
    fun exportResult(uri:Uri?){if(uri==null){export=null;exportResult=null;return};exportResult=uri;if(resumed)finishExport()}
    private fun finishExport(){val pin=export?:return;val uri=exportResult?:return;exportResult=null
        if(pin.optString("provider")=="teams"){
            val selected=TeamWorkspaceOutcomeHost.Selection(pin.getJSONObject("team"));work{c,_->val host=requireTeamHost();val bytes=host.verifiedBytes(selected,c)
                try{host.checkRead(selected,c);val actual=AttachmentRepository.get(app).exportNativeSnapshot(bytes,selected.sha256(),uri,c);host.checkRead(selected,c);export=null;actual.put("outcomeNotice","已导出原成员冻结成果的真实文件；原分工和原项目保留") to null}catch(failed:Exception){throw IllegalStateException("导出未完整确认，目标可能已有部分内容；请核对所选文件和原分工",failed)}finally{java.util.Arrays.fill(bytes,0.toByte())}}
            return
        }
        val target=groupFrom(pin.optJSONObject("group"));val task=pin.optString("taskId").takeIf{it.isNotBlank()};val file=pin.getJSONObject("file")
        work{c,w->val result=if(target==null)app.attachmentOutcomes.export(task,file.getString("id"),file.getString("sha256"),uri,c)else groupCall(w){app.exportGroupOutcome(target,uri,c)};export=null;result.put("outcomeNotice","已导出真实文件；原任务与群投递状态分别保留") to null}
    }
    fun refreshMedia(operation:String,fetch:Boolean=false){val group=state.group;val task=state.taskId?:return
        work{c,w->if(group==null){app.attachmentOutcomes.originalMedia(task,operation,if(fetch)"fetchResults" else "getJob",c);app.ownerOutcomeSnapshot(task,c) to null}
            else {groupCall(w){app.refreshGroupOutcome(group,fetch,c)};groupCall(w){app.groupOutcome(group,c)} to null}}
    }
    fun cancelMedia(operation:String){val task=state.taskId?:return;val group=state.group
        work{c,w->if(group==null){app.attachmentOutcomes.originalMedia(task,operation,"cancelJob",c);app.ownerOutcomeSnapshot(task,c) to null}else{groupCall(w){app.cancelGroupOutcome(group,c)};groupCall(w){app.groupOutcome(group,c)} to null}}
    }
    fun observe(operation:String){val task=state.taskId?:return;val group=state.group;val window=if(group!=null)try{ownerWindow()}catch(e:Exception){state=state.copy(error=safe(e));return}else null
        observation?.cancel();val c=RunControl();observation=c;val token=generation;state=state.copy(observing=true,progress=null,error=null)
        io.execute{try{val listener=dev.pocketengine.media.jobs.ComfyUiProgressObserver.Listener{event->val value=JSONObject(event.toString());main.post{if(!closed&&state.open&&generation==token&&observation===c&&!c.isCancelled)state=state.copy(progress=value)}}
            val end=if(group==null)app.features.observeMediaForOwner(task,operation,c,listener)else groupCall(window){app.observeGroupOutcome(group,c,listener)}
            main.post{if(!closed&&generation==token&&observation===c){state=state.copy(observing=false,progress=JSONObject(end.toString()));observation=null};c.cancel()}}
            catch(error:Exception){main.post{if(!closed&&generation==token&&observation===c){state=state.copy(observing=false,error=if(c.isCancelled)null else safe(error));observation=null};c.cancel()}}}
    }
    fun stopObservation(){observation?.cancel();observation=null;state=state.copy(observing=false)}
    fun continueTask(){
        state.team?.let{selected->val navigate=originalAssignment;if(navigate==null){state=state.copy(error="原分工入口尚未挂载，请从原团队任务继续");return};work(onSuccess={actual->close();navigate(actual)}){c,_->requireTeamHost().assignmentLocator(selected,c) to null};return}
        state.taskId?.let{close();originalTask(it)}
    }
    private fun work(document:Boolean=false,replaceDocumentWithFile:Boolean=false,teamDifference:Boolean=false,onSuccess:((JSONObject)->Unit)?=null,call:(RunControl,String?)->Pair<JSONObject,JSONObject?>){
        wait?.cancel();observation?.cancel();observation=null;val token=++generation;val c=RunControl();wait=c
        val group=state.group;val window=if(group!=null||export?.optJSONObject("group")!=null)try{ownerWindow()}catch(e:Exception){state=state.copy(error=safe(e),busy=false);return}else null
        state=state.copy(busy=true,error=null,observing=false,documentOpen=document||state.documentOpen)
        io.execute{try{val(result,file)=call(c,window);c.check();main.post{if(!closed&&state.open&&generation==token&&wait===c&&!c.isCancelled){
            val actualFile=if(group!=null&&result.optJSONObject("artifact")!=null)result.getJSONObject("artifact").let{JSONObject(it.toString()).put("id",it.getString("artifactId")).put("size",it.getLong("bytes")).put("name","群任务成果")}else file
            state=if(teamDifference)state.copy(busy=false,teamDifference=result)else if(document)state.copy(busy=false,document=result)else state.copy(busy=false,snapshot=if(result.has("sources")||result.has("kind")||result.optString("provider")=="teams"&&result.has("artifact"))result else state.snapshot,file=actualFile?:state.file,notice=result.optString("outcomeNotice").takeIf{it.isNotBlank()},documentOpen=if(replaceDocumentWithFile)false else state.documentOpen,document=if(replaceDocumentWithFile)null else state.document,thumbnail=if(replaceDocumentWithFile)null else state.thumbnail)
            onSuccess?.invoke(result)
        };if(file!=null)restoreFile=null;val preview=state.file?.optString("mimeType")?.startsWith("image/")==true&&state.thumbnail==null&&file!=null;if(wait===c)wait=null;c.cancel();if(preview&&generation==token)loadThumbnail()}}catch(e:Exception){main.post{if(!closed&&state.open&&generation==token&&wait===c){state=state.copy(busy=false,error=if(c.isCancelled)"读取已停止；原文件与任务保留" else safe(e));wait=null};c.cancel()}}}
    }
    private fun groupCall(window:String?,call:()->JSONObject):JSONObject {val actual=window?:throw SecurityException("当前原生主人窗口不可用");return app.groupOwnerWorkflows.call(actual){call()}}
    fun save(bundle:Bundle){pendingProcessingAction?.let{bundle.putString("attachmentPendingProcessingTask",it)};pendingDocumentAction?.let{bundle.putString("attachmentPendingDocumentTask",it)};if(state.open)bundle.putString("attachmentOutcomeLocator",locator().toString());export?.let{bundle.putString("attachmentOutcomeExport",it.toString())};bundle.remove("attachmentDocumentDraft");(state.documentDraft?:restoredDocumentDraft)?.let{draft->runCatching{DocumentDraft(draft);draft.toString().takeIf{it.toByteArray(Charsets.UTF_8).size<=StructuredOfficeDraft.MAX_SOURCE_BYTES}?.let{bundle.putString("attachmentDocumentDraft",it)}}}}
    fun restore(bundle:Bundle?){bundle?.getString("attachmentPendingProcessingTask")?.takeIf{it.matches(Regex("[a-f0-9-]{36}"))}?.let{pendingProcessingAction=it;state=state.copy(pendingProcessingTask=it)};bundle?.getString("attachmentPendingDocumentTask")?.takeIf{it.matches(Regex("[a-f0-9-]{36}"))}?.let{pendingDocumentAction=it;state=state.copy(pendingDocumentTask=it)};try{bundle?.getString("attachmentOutcomeExport")?.let{export=JSONObject(it)};bundle?.getString("attachmentOutcomeLocator")?.let{val pin=JSONObject(it);val team=pin.optJSONObject("team")?.let{TeamWorkspaceOutcomeHost.Selection(it)};val previous=runCatching{pin.optJSONObject("teamPrevious")?.let{TeamWorkspaceOutcomeHost.Selection(it)}}.getOrNull();restoreFile=pin.optJSONObject("file");state=AttachmentOutcomeState(open=true,pendingDocumentTask=state.pendingDocumentTask,pendingProcessingTask=state.pendingProcessingTask,taskId=team?.toJson()?.getString("taskId")?:pin.optString("taskId").takeIf(String::isNotBlank),group=groupFrom(pin.optJSONObject("group")),team=team,teamPrevious=team?.let{matchingPrevious(it,previous)});bundle?.getString("attachmentDocumentDraft")?.let{saved->check(team==null&&state.group==null&&saved.toByteArray(Charsets.UTF_8).size<=StructuredOfficeDraft.MAX_SOURCE_BYTES){"原文档草稿超出可恢复范围"};val draft=JSONObject(saved);DocumentDraft(draft);restoredDocumentDraft=draft};main.post{if(!closed&&resumed)refresh()}}}catch(_:Exception){restoredDocumentDraft=null;state=AttachmentOutcomeState(open=true,pendingDocumentTask=pendingDocumentAction,pendingProcessingTask=pendingProcessingAction,error="原成果定位或文档草稿暂不可恢复，请从原任务重新打开；已有保存动作仍只核对原结果")}}
    private fun locator():JSONObject=JSONObject().put("taskId",state.taskId.orEmpty()).also{value->state.team?.let{value.put("team",it.toJson())};state.teamPrevious?.let{value.put("teamPrevious",it.toJson())};state.file?.let{value.put("file",JSONObject().put("id",it.optString("id")).put("sha256",it.optString("sha256")))};state.group?.let{s->value.put("group",JSONObject().put("requestId",s.requestId).put("taskId",s.taskId).put("spaceId",s.spaceId).put("outputId",s.outputId).put("artifactId",s.artifactId).put("audience",s.audience).put("visibility",s.visibility).put("operation",s.sourceOperationHint).put("sourceRun",s.sourceRunHint))}}
    private fun groupFrom(value:JSONObject?):GroupResultAccess.Selection?=value?.let{GroupResultAccess.Selection(it.getString("requestId"),it.getString("taskId"),it.getString("spaceId"),it.getString("outputId"),it.getString("artifactId"),it.getString("audience"),it.getString("visibility"),it.optString("operation"),it.optString("sourceRun"))}
    private fun safe(error:Exception):String {val message=error.message.orEmpty();return if(message.isBlank()||message.contains("content://")||message.contains("http"))"文件或原操作暂不可用，请核对原任务；没有重新提交或重发" else message.take(300)}
}
