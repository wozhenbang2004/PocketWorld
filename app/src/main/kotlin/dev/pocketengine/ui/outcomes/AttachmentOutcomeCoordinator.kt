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
import dev.pocketengine.features.attachments.AttachmentBook
import dev.pocketengine.features.attachments.OwnerFilePreviewSource
import dev.pocketengine.features.attachments.documents.DocumentContent
import dev.pocketengine.features.attachments.TeamWorkspaceOutcomeHost
import dev.pocketengine.features.attachments.AttachmentRepository
import dev.pocketengine.features.attachments.OwnerAttachmentImport
import dev.pocketengine.features.attachments.creation.DocumentDraft
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.RevisionSource
import dev.pocketengine.features.attachments.creation.StructuredOfficeDraft
import dev.pocketengine.media.jobs.results.GroupResultAccess
import dev.pocketengine.ui.groups.GroupArtifactSelection
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

internal data class AttachmentResultRun(val taskId: String, val runId: String, val epoch: Int, val report: Report? = null) {
    data class Files(val entries: List<JSONObject>, val readLimited: Boolean)
    data class Report(val conversationId: String, val messageId: String, val deliveryId: String) {
        companion object {
            fun current(task: JSONObject): Report? {
                val native = task.optJSONObject("resultHandling")?.takeIf {
                    task.opt("nativeResultHandling") == true && it.optString("format") == "pocketworld.owner-result-handling.v1" &&
                        it.optString("taskId") == task.optString("id") && it.optString("runId") == task.optString("runId") &&
                        it.optInt("epoch") == task.optInt("coordinatorEpoch")
                }
                val result = native ?: task.optJSONObject("ownerWork")?.takeIf { task.optString("executionRole") == "owner_work" } ?: return null
                val handling = result.optJSONObject("handling") ?: return null
                if (handling.optString("state") != "reported") return null
                val conversation = result.optString("conversationId").ifBlank { task.optString("conversationId") }
                val message = handling.optString("reportMessageId")
                val delivery = handling.optString("deliveryId")
                return if (conversation.isNotBlank() && message.isNotBlank() && delivery.isNotBlank()) Report(conversation, message, delivery) else null
            }
        }
    }

    fun matchesCompletedResult(task: JSONObject): Boolean =
        task.optString("id") == taskId && task.optString("actorId") == "owner" && task.optString("spaceId") == "private" &&
            task.optString("kind") != "inference" && task.optJSONObject("origin")?.optString("kind") != "group" &&
            task.optString("taskProjection") != "source_unavailable" && task.optBoolean("contextAvailable", true) &&
            task.optString("runId") == runId && task.optInt("coordinatorEpoch") == epoch && task.optString("status") == "completed" &&
            (report == null || report == Report.current(task))

    fun files(snapshot: JSONObject): Files {
        check(snapshot.optString("taskId") == taskId && snapshot.optString("taskRunId") == runId &&
            snapshot.optInt("coordinatorEpoch") == epoch) { "原任务状态已变化，请打开当前工作核对成果" }
        val artifacts = snapshot.getJSONArray("artifacts")
        val files = artifacts.objects()
        var limited = snapshot.optJSONObject("indexDiagnostics")?.opt("incomplete") != false || files.size != artifacts.length()
        val exact = files.filter { file ->
            val sourceRun = file.opt("sourceTaskRunId") as? String ?: ""
            val epochMatches = when (val sourceEpoch = file.opt("sourceCoordinatorEpoch")) {
                null -> true // Ordinary local outputs store the original run ID; remote capture can also prove the epoch.
                is Int -> sourceEpoch == epoch
                is Long -> sourceEpoch == epoch.toLong()
                else -> false
            }
            val sameTask = file.optString("sourceTaskId") == taskId
            when {
                sameTask && file.optString("runProvenance") == "earlier" && sourceRun.isNotBlank() &&
                    (sourceRun != runId || !epochMatches) -> false
                sameTask && file.optString("runProvenance") == "current" && sourceRun == runId && epochMatches &&
                    file.optString("availability") == "available" -> true
                else -> { limited = true; false }
            }
        }
        return Files(exact, limited)
    }
}

internal data class OwnerAttachmentCopyReceipt(val requestId:String,val taskId:String,val fileId:String,val sha256:String,val result:OwnerCopyResult) {
    fun toJson()=JSONObject().put("requestId",requestId).put("taskId",taskId).put("fileId",fileId).put("sha256",sha256)
        .put("status",result.status.value).put("displayName",result.displayName).put("verifiedBytes",result.verifiedBytes)
}

internal enum class AttachmentOriginalReturn(val value:String) { DETAILS("details"), LIST("list"), ENTRY("entry") }
internal data class AttachmentPdfPageText(val viewerId: String, val rasterId: String, val page: Int)
internal data class AttachmentWordExcerpt(val viewerId:String,val unit:Int,val chunk:Int,val original:JSONObject,
                                          val blocks:List<JSONObject>) {
    fun matchesPreview(value:JSONObject):Boolean {
        val parts=value.optJSONArray("chunks").objects()
        return value.opt("format")=="docx"&&value.opt("readContext")=="native_owner_preview"&&
            value.opt("coverage")=="body_paragraphs_and_tables"&&value.opt("extraction")=="text"&&value.opt("taskId")==""&&
            !value.optString("readReceiptId").isNullOrBlank()&&parts.size==blocks.size&&parts.indices.all{index->
                val a=blocks[index];val b=parts[index]
                val from=a.optJSONObject("locator")?:return@all false
                val to=b.optJSONObject("locator")?:return@all false
                listOf("unit","chunk","kind","text").all{a.opt(it)==b.opt(it)}&&
                    from.length()==to.length()&&from.keys().asSequence().all{from.opt(it)==to.opt(it)}
            }
    }
    fun view()=JSONObject().put("viewerId",viewerId).put("unit",unit).put("chunk",chunk).put("parts",blocks.size)
}
internal data class AttachmentWordOrigin(val fileId:String,val sha256:String,val unit:Int,val chunk:Int,val parts:Int) {
    companion object {
        const val FORMAT="pocketworld.original-word-excerpt.v1"
        const val MIME="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        fun from(value:JSONObject?):AttachmentWordOrigin? {
            value?:return null
            val id=value.opt("fileId") as? String?:return null
            val sha=value.opt("sha256") as? String?:return null
            val unit=value.opt("unit") as? Int?:return null
            val chunk=value.opt("chunk") as? Int?:return null
            val parts=value.opt("parts") as? Int?:return null
            return AttachmentWordOrigin(id,sha,unit,chunk,parts).takeIf{value.length()==6&&value.opt("format")==FORMAT&&
                id.matches(Regex("[a-f0-9-]{36}"))&&sha.matches(Regex("[a-f0-9]{64}"))&&
                unit in 1..1_000_000&&chunk in 0..1_000_000&&parts in 1..2}
        }
    }
    fun matchesSource(file:JSONObject)=file.opt("id")==fileId&&file.opt("sha256")==sha256&&file.opt("mimeType")==MIME&&
        file.opt("role")=="source"&&file.opt("availability")=="available"&&file.opt("sourceKind")=="content_uri"&&
        file.isNull("sourceTaskId")&&file.optLong("size",0)>0
}
internal data class AttachmentWordSourceVisit(val origin:AttachmentWordOrigin,
    val source:OwnerFilePreviewSource,val excerpt:JSONObject)
/** A saveable navigation hint for the work behind an already-opened original, never a read grant. */
internal data class AttachmentWorkReturn(val run:AttachmentResultRun,val fileId:String,val sha256:String,
                                         private val recordedDocument:Boolean=false) {
    fun matchesFile(file:JSONObject?)=file!=null&&file.optString("id")==fileId&&file.optString("sha256")==sha256
    fun matchesTask(task:JSONObject)=!task.optBoolean("contextWithdrawn")&&!task.optBoolean("sourceCurrentReadRequired")&&
        (run.matchesCompletedResult(task)||recordedDocument&&matchesRecordedDocumentTask(task))
    fun matchesResult(snapshot:JSONObject,file:JSONObject?):Boolean=matchesFile(file)&&file?.optString("role")=="result"&&
        runCatching{run.files(snapshot).entries.count{matchesFile(it)}==1}.getOrDefault(false)

    private fun matchesRecordedDocumentRow(file:JSONObject?):Boolean= file!=null&&matchesFile(file)&&
        file.opt("role")=="result"&&file.opt("availability")=="available"&&file.opt("recordedDocumentOriginal")==true&&
        file.opt("sourceKind")=="created_document"&&file.opt("sourceTaskId")==run.taskId&&
        file.opt("sourceTaskRunId")==run.runId&&file.opt("sourceOperationId")==run.taskId&&
        file.opt("mimeType") in setOf("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document")

    /** Select the stricter check from the current native file catalogue, never from the Bundle. */
    fun forRecordedDocument(snapshot:JSONObject,file:JSONObject?):AttachmentWorkReturn?=
        takeIf{run.report==null&&run.taskId==run.runId&&run.epoch==1&&snapshot.opt("taskStatus")=="needs_confirmation"&&
            snapshot.opt("originalDocumentActionId")==run.taskId&&matchesRecordedDocumentRow(file)&&
            runCatching{run.files(snapshot).entries.count(::matchesRecordedDocumentRow)==1}.getOrDefault(false)}
            ?.copy(recordedDocument=true)

    /** A native original returned only after the same Book ID and complete digest have been read. */
    fun matchesVerifiedDocument(file:JSONObject,original:OwnerFilePreviewSource):Boolean=recordedDocument&&runCatching{
        val meta=original.metadata()
        val source=meta.getJSONObject("source");val publication=source.getJSONObject("nativePublication")
        val plan=source.getString("planDigest");val action=run.taskId
        val format=if(original.mimeType=="application/pdf")"pdf" else "docx"
        matchesRecordedDocumentRow(file)&&matchesFile(meta)&&!original.tombstone&&original.sha256==sha256&&
            original.mimeType==file.opt("mimeType")&&original.sizeBytes==file.optLong("size",-1L)&&original.sizeBytes>0&&
            meta.opt("principalId")=="owner"&&meta.opt("spaceId")=="private"&&!meta.optBoolean("outputSourceRetired")&&
            source.opt("kind")=="created_document"&&!source.has("groupSpaceId")&&source.opt("documentFormat")==format&&
            publication.opt("role")=="document_output"&&publication.opt("planDigest")==plan&&
            listOf("taskId","runId","operationId").all{source.opt(it)==action&&publication.opt(it)==action}&&
            AttachmentBook.isOriginalPublicationId(fileId,action,"document_output",action,action,plan)
    }.getOrDefault(false)

    private fun matchesRecordedDocumentTask(task:JSONObject):Boolean {
        val action=run.taskId
        val producer=task.optJSONObject("externalRefs")?.optJSONObject("documentCreation")?:return false
        val evidence=task.optJSONObject("evidence")?:return false
        return run.report==null&&action==run.runId&&run.epoch==1&&task.opt("id")==action&&task.opt("runId")==action&&
            task.opt("operationId")==action&&task.optInt("coordinatorEpoch")==1&&task.opt("actorId")=="owner"&&
            task.opt("spaceId")=="private"&&task.opt("kind")=="attachments"&&task.opt("feature")=="attachments"&&task.opt("command")=="createDocument"&&
            listOf("origin","conversationId","parentTaskId","teamRunId","memberId").all{task.isNull(it)}&&
            task.opt("taskProjection")=="detail_window"&&task.opt("taskCoreReadLimited")==false&&task.optBoolean("contextAvailable",true)&&
            task.opt("status")=="needs_confirmation"&&task.opt("executionStatus")=="completed"&&task.opt("externalId")==fileId&&
            task.opt("externalOperationId")==action&&evidence.opt("id")==fileId&&evidence.opt("operationId")==action&&
            evidence.opt("executorStatus")=="completed"&&producer.opt("format") in setOf("docx","pdf")&&
            runCatching{AttachmentBook.isOriginalPublicationId(fileId,action,"document_output",action,action,producer.getString("planDigest"))}.getOrDefault(false)
    }

    fun toJson()=JSONObject().put("taskId",run.taskId).put("runId",run.runId).put("epoch",run.epoch)
        .put("fileId",fileId).put("sha256",sha256)
    companion object {
        fun from(saved:JSONObject):AttachmentWorkReturn {
            val task=saved.getString("taskId");val run=saved.getString("runId");val epoch=saved.getInt("epoch")
            val file=saved.getString("fileId");val sha=saved.getString("sha256")
            check(listOf(task,run,file).all{it.isNotBlank()&&it.length<=240&&'\u0000' !in it}&&
                epoch>0&&sha.matches(Regex("[a-f0-9]{64}"))){"原工作回程身份无效"}
            return AttachmentWorkReturn(AttachmentResultRun(task,run,epoch),file,sha)
        }
        fun capture(snapshot:JSONObject,file:JSONObject):AttachmentWorkReturn?=runCatching{
            from(JSONObject().put("taskId",snapshot.getString("taskId")).put("runId",snapshot.getString("taskRunId"))
                .put("epoch",snapshot.getInt("coordinatorEpoch")).put("fileId",file.getString("id")).put("sha256",file.getString("sha256")))
                .takeIf{it.matchesResult(snapshot,file)&&(snapshot.opt("taskStatus")=="completed"||it.forRecordedDocument(snapshot,file)!=null)}
        }.getOrNull()
    }
}
internal data class AttachmentLibraryImport(val requestId:String,val phase:String,val registration:JSONObject?=null){
    fun toJson()=JSONObject().put("requestId",requestId).put("phase",phase).put("registration",registration)
}

/** The same-window link back to the already shown library details; no reader, body, or grant. */
internal data class AttachmentLibraryDocumentReturn(
    val action:String,val snapshot:JSONObject,val file:JSONObject,val libraryImport:AttachmentLibraryImport?,
    val copyReceipt:OwnerAttachmentCopyReceipt?,val previousCopy:OwnerAttachmentCopyReceipt?
)

internal data class AttachmentOutcomeState(
    val open:Boolean=false,val taskId:String?=null,val deviceOriginalOnly:Boolean=false,val group:GroupResultAccess.Selection?=null,
    val snapshot:JSONObject?=null,val file:JSONObject?=null,val document:JSONObject?=null,
    val documentOpen:Boolean=false,val choosingSource:Boolean=false,val busy:Boolean=false,
    val error:String?=null,val progress:JSONObject?=null,val observing:Boolean=false,val notice:String?=null,val thumbnail:android.graphics.Bitmap?=null,
    val team:TeamWorkspaceOutcomeHost.Selection?=null,val teamDifference:JSONObject?=null,val teamPrevious:TeamWorkspaceOutcomeHost.Selection?=null,
    val originalFile:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null,
    val originalReturn:AttachmentOriginalReturn=AttachmentOriginalReturn.DETAILS,
    val documentDraft:JSONObject?=null,val documentDraftEdited:Boolean=false,val pendingDocumentTask:String?=null,
    val processingSource:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null,val processingInfo:JSONObject?=null,
    val processingReview:dev.pocketengine.features.attachments.processing.NativeFileProcess.Review?=null,val pendingProcessingTask:String?=null,
    val comparison:Pair<dev.pocketengine.features.attachments.OwnerFilePreviewSource,dev.pocketengine.features.attachments.OwnerFilePreviewSource>?=null,
    val fromWorkResult:Boolean=false,val resultRun:AttachmentResultRun?=null,val externalViewerResult:String?=null,
    val copyReceipt:OwnerAttachmentCopyReceipt?=null,val previousCopyInFile:OwnerAttachmentCopyReceipt?=null,
    val libraryImport:AttachmentLibraryImport?=null,
    val workReturn:AttachmentWorkReturn?=null,val restoredWorkReturn:Boolean=false,
    val libraryDocumentReturn:AttachmentLibraryDocumentReturn?=null,
    val globalStandaloneDraftVisit:String?=null, // Native in-session provenance, never restored from Bundle.
    val pdfPageText:AttachmentPdfPageText?=null, // The still-open original display, never restored.
    val wordExcerpt:AttachmentWordExcerpt?=null,val wordSaveAttempted:Boolean=false,
    val wordSourceVisit:AttachmentWordSourceVisit?=null, // One explicit origin lookup; never restored from Bundle.
    val focusedStandaloneDraft:Boolean=false // In-session exact draft editor, not an attachment catalogue.
)

/** Navigation and revocable waits only. TaskStore, canonical artifact stores and native owner gate retain truth. */
internal class AttachmentOutcomeCoordinator(
    private val activity:ComposeActivity,private val app:EngineApplication,private val io:Executor,
    private val main:Handler,private val ownerWindow:()->String,private val originalTask:(String)->Unit,private val releaseWindow:()->Unit
) {
    var state by mutableStateOf(AttachmentOutcomeState());private set
    private var pendingDocumentAction:String?=null;private var pendingProcessingAction:String?=null
    private var restoredDocumentDraft:JSONObject?=null
    private var restoredExportNotice:String?=null
    private var restoredCopyLocator:JSONObject?=null
    private data class CopySource(val task:String,val file:String,val sha:String)
    private val verifiedCopies=linkedMapOf<CopySource,OwnerAttachmentCopyReceipt>()
    private fun copySource(file:JSONObject)=CopySource(state.taskId.orEmpty(),file.optString("id"),file.optString("sha256"))
    private fun OwnerAttachmentCopyReceipt.source()=CopySource(taskId,fileId,sha256)
    private fun rememberVerifiedCopy(receipt:OwnerAttachmentCopyReceipt){
        if(receipt.result.status!=OwnerCopyStatus.VERIFIED)return
        val key=receipt.source()
        verifiedCopies.remove(key);verifiedCopies[key]=receipt
        while(verifiedCopies.size>8)verifiedCopies.remove(verifiedCopies.keys.first())
    }
    fun privateCopyReceipt(originalConfirmed:Boolean):JSONObject?{
        val key=state.file?.let(::copySource)?:return null
        if(privateCopyStage()!=null)return null
        val current=state.copyReceipt?.takeIf{it.source()==key}
        if(current?.result?.status==OwnerCopyStatus.VERIFIED)return current.toJson()
        // A receipt already verified here survives a cancelled attempt even in file details.
        // Other historical receipts still require re-admission of the exact original.
        val prior=(state.previousCopyInFile?.takeIf{it.source()==key}?:verifiedCopies[key]?.takeIf{
            originalConfirmed&&it.result.verifiedBytes==state.originalFile?.sizeBytes
        })?.toJson()?.put("historical",true)
        return current?.toJson()?.also{if(prior!=null)it.put("previousVerified",prior)}?:prior
    }
    private fun restoreVerifiedCopies(bundle:Bundle?){
        bundle?.getStringArrayList("attachmentOutcomeVerifiedCopies")?.takeLast(8)?.forEach { saved ->
            runCatching {
                check(saved.length<=1500)
                val proof=JSONObject(saved);val request=proof.getString("requestId");val task=proof.getString("taskId")
                val file=proof.getString("fileId");val sha=proof.getString("sha256");val bytes=proof.optLong("verifiedBytes",-1)
                check(proof.opt("status")==OwnerCopyStatus.VERIFIED.value&&java.util.UUID.fromString(request).toString()==request&&
                    task.length<=240&&'\u0000' !in task&&file.length in 1..240&&'\u0000' !in file&&sha.matches(Regex("[0-9a-f]{64}"))&&
                    proof.opt("verifiedBytes").let{it is Int||it is Long}&&bytes in 0..dev.pocketengine.features.attachments.AttachmentBook.MAX_FILE_BYTES)
                val name=(proof.opt("displayName") as? String)?.takeIf{it.length in 1..240&&it.none{ch->ch.isISOControl()||Character.getType(ch)==Character.FORMAT.toInt()}}
                rememberVerifiedCopy(OwnerAttachmentCopyReceipt(request,task,file,sha,OwnerCopyResult(OwnerCopyStatus.VERIFIED,name,bytes)))
            }
        }
    }
    private var generation=0L;private var wait:RunControl?=null;private var observation:RunControl?=null
    private var export:JSONObject?=null;private var restoreFile:JSONObject?=null;private var exportResult:Uri?=null;private var exportAbandoned=false;private var resumed=false;private var closed=false
    var exportPending by mutableStateOf(false);private set
    val exportRequestId:String? get()=export?.takeIf{it.optString("exportPhase","waiting")=="waiting"}?.optString("exportRequestId")
    private fun privateCopyFile(pin:JSONObject,shown:JSONObject?=state.file):JSONObject? {
        val run=state.resultRun
        if(!state.open||state.deviceOriginalOnly||state.group!=null||state.team!=null||pin.has("group")||pin.has("team")||
            pin.optString("taskId")!=state.taskId.orEmpty()||pin.optBoolean("fromWorkResult")!=state.fromWorkResult||
            run?.report!=null||run!=null&&run.taskId!=state.taskId||pin.optString("resultRunId")!=run?.runId.orEmpty()||
            pin.optInt("resultEpoch")!=(run?.epoch?:0)||shown==null)return null
        return pin.optJSONObject("file")?.takeIf {file->
            val id=file.optString("id");val sha=file.optString("sha256")
            id.isNotBlank()&&sha.length==64&&sha.all{it in '0'..'9'||it in 'a'..'f'}&&id==shown.optString("id")&&sha==shown.optString("sha256")
        }
    }
    private fun returningPrivateCopyFile(pin:JSONObject):JSONObject? {
        val locator=restoredCopyLocator?:return null
        if(export!==pin||exportAbandoned||closed||state.file!=null||restoreFile!==locator)return null
        return privateCopyFile(pin,locator)
    }
    private fun recordPrivateCopy(pin:JSONObject,result:OwnerCopyResult){
        if(export!==pin||closed||exportAbandoned)return
        // Keep the receipt in memory while the same restored original has not been read yet.
        // The Web view still exposes it only beside the freshly resolved file.
        val file=privateCopyFile(pin)?:returningPrivateCopyFile(pin)?:return
        val receipt=OwnerAttachmentCopyReceipt(pin.getString("exportRequestId"),pin.getString("taskId"),file.getString("id"),file.getString("sha256"),result)
        state=state.copy(copyReceipt=receipt,previousCopyInFile=state.previousCopyInFile.takeUnless{result.status==OwnerCopyStatus.VERIFIED})
        rememberVerifiedCopy(receipt)
    }
    fun privateCopyStage():String?=export?.takeIf {privateCopyFile(it)!=null||returningPrivateCopyFile(it)!=null}?.let {pin->
        val phase=pin.getString("exportPhase")
        if(phase=="waiting"&&pin.optBoolean("exportLaunchUnknown"))"picker_unconfirmed" else phase
    }
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
    fun openTask(task:String?,resultRun:AttachmentResultRun?=null,globalStandaloneDraftVisit:String?=null) {
        check(globalStandaloneDraftVisit==null||task==null&&resultRun==null)
        cancel();restoreFile=null;restoredDocumentDraft=null
        state=AttachmentOutcomeState(open=true,taskId=task,pendingDocumentTask=pendingDocumentAction,
            pendingProcessingTask=pendingProcessingAction,resultRun=resultRun,globalStandaloneDraftVisit=globalStandaloneDraftVisit)
        refresh()
    }
    fun openStandaloneDraftFocus(visit:String) {
        check(!state.open&&!exportPending&&pendingDocumentAction==null&&pendingProcessingAction==null){
            "请先核对原待办文档或文件；这份草稿仍保留在本机"
        }
        cancel();restoreFile=null;restoredDocumentDraft=null
        state=AttachmentOutcomeState(open=true,globalStandaloneDraftVisit=visit,focusedStandaloneDraft=true)
    }
    fun discardStandaloneDraftPresentation(){
        if(state.globalStandaloneDraftVisit!=null)state=state.copy(globalStandaloneDraftVisit=null)
    }
    fun openGroup(selection:GroupArtifactSelection) {
        cancel();restoreFile=null;restoredDocumentDraft=null;state=AttachmentOutcomeState(open=true,taskId=selection.taskId,group=GroupResultAccess.Selection(selection.requestId,selection.taskId,selection.spaceId,selection.outputId,selection.artifactId,selection.audience,selection.visibility,selection.sourceOperationId.orEmpty(),selection.sourceTaskRunId.orEmpty()))
        refresh()
    }
    fun openGroupOperation(task:UiTask,operation:String){
        val origin=task.raw.optJSONObject("origin")?:return;cancel();restoreFile=null;restoredDocumentDraft=null;state=AttachmentOutcomeState(open=true,taskId=task.id,group=GroupResultAccess.Selection(origin.getString("requestId"),task.id,origin.getString("spaceId"),"","",origin.getString("audience"),origin.getString("visibility"),operation,""));refresh()
    }
    /** Entered only after the local device receipt was opened into its exact original private task.
     * Use that task's normal original-file host, not a library inventory or a general-purpose path. */
    fun openReceivedOriginal(selected:ReceivedFileSelection, current:()->Boolean){
        require(current());cancel();restoreFile=null;restoredDocumentDraft=null
        state=AttachmentOutcomeState(open=true,taskId=selected.task,deviceOriginalOnly=true,fromWorkResult=true)
        var original:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null
        work(onSuccess={ if(!current())close() else state=state.copy(originalFile=original) }){control,_ ->
            require(current()){"原工作窗口已关闭"}
            val fixed=app.attachmentOutcomes.originalFile(selected.task,selected.id,selected.sha,control)
            selected.requireBookOrigin(fixed.metadata());control.check();require(current()){"原工作窗口已变化"}
            original=fixed
            val row=JSONObject().put("id",selected.id).put("sha256",selected.sha).put("name",fixed.name).put("size",fixed.sizeBytes)
                .put("mimeType",fixed.mimeType).put("availability","available").put("sourceKind","device_received_file")
            JSONObject().put("kind","only_this_device_file").put("taskId",selected.task) to row
        }
    }
    fun openFile(meta:JSONObject,fromTask:String?=null,showOriginal:Boolean=false,fromWorkResult:Boolean=showOriginal,
                 resultRun:AttachmentResultRun?=null,returnToEntry:Boolean=false) {
        cancel();restoredDocumentDraft=null
        // A pending locator is navigation intent, never an already-read file.
        // Preserve it across first-read failure, cancellation and foreground restore.
        restoreFile=JSONObject().put("id",meta.optString("id")).put("sha256",meta.optString("sha256"))
            .put("showOriginal",showOriginal)
        val originalReturn=if(showOriginal&&returnToEntry)AttachmentOriginalReturn.ENTRY else AttachmentOriginalReturn.DETAILS
        state=AttachmentOutcomeState(open=true,taskId=fromTask,pendingDocumentTask=pendingDocumentAction,pendingProcessingTask=pendingProcessingAction,fromWorkResult=fromWorkResult,resultRun=resultRun,originalReturn=originalReturn)
        refresh()
    }
    fun close(){if(export!=null)exportAbandoned=true;cancel();restoreFile=null;restoredDocumentDraft=null;releaseWindow();state=AttachmentOutcomeState()}
    fun detach(){closed=true;close()}
    fun cancel(){val pendingFile=state.open&&state.busy&&restoreFile!=null;generation++;wait?.cancel();wait=null;observation?.cancel();observation=null;state=state.copy(busy=false,observing=false,error=if(pendingFile)"已停止读取这份原文件；目标版本仍保留，可以重新读取。"else state.error)}
    fun foreground(visible:Boolean){resumed=visible;if(!visible){if(state.deviceOriginalOnly||state.focusedStandaloneDraft)close()else cancel();return};if(exportResult!=null)finishExport()else if(state.open&&state.snapshot==null&&!state.busy)refresh()}
    @JvmOverloads
    fun refresh(afterRefresh:((Boolean)->Unit)?=null){
        if(state.deviceOriginalOnly||state.focusedStandaloneDraft){afterRefresh?.invoke(false);return} // no general inventory in a focused view
        if(state.team!=null){readTeamFile();afterRefresh?.invoke(false);return}
        val task=state.taskId;val group=state.group;val pin=restoreFile?:state.file;val savedDraft=restoredDocumentDraft
        val showOriginal=restoreFile?.optBoolean("showOriginal")==true
        val directWorkOriginal=showOriginal&&directPrivateWorkEntry()
        var unpublishedEdits=savedDraft!=null
        val resultRun=state.resultRun?.takeIf{it.taskId==task}
        var original:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null
        var verifiedRecordedOriginal=false
        var refreshed=false
        work(onFinished=afterRefresh?.let{done->{current:Boolean->done(current&&refreshed)}},
            completionFailure=if(afterRefresh!=null)"私人文件库尚未刷新，未打开原草稿。" else null,onSuccess={actual->
            if(group==null&&pendingDocumentAction==null&&!verifiedRecordedOriginal&&actual.optString("taskStatus")!in setOf("completed","failed")){
                originalDocumentAction(task,actual)?.let{action->
                    pendingDocumentAction=action;state=state.copy(pendingDocumentTask=action)
                }
            }
            if(savedDraft!=null&&restoredDocumentDraft===savedDraft){state=state.copy(documentDraft=JSONObject(savedDraft.toString()),documentDraftEdited=unpublishedEdits);restoredDocumentDraft=null}
            if(original!=null){
                state=state.copy(originalFile=original)
                if(directPrivateWorkEntry()&&!state.restoredWorkReturn&&state.workReturn==null)
                    state.file?.let{file->state=state.copy(workReturn=AttachmentWorkReturn.capture(actual,file))}
            }
            refreshed=true
        }){control,window -> if(group==null){
            if(resultRun!=null)requireConversationResult(resultRun,control)
            val actual=app.ownerOutcomeSnapshot(task,control)
            val files=if(resultRun!=null){
                val fromRun=resultRun.files(actual)
                actual.put("artifacts",JSONArray(fromRun.entries)).put("sources",JSONArray()).put("media",JSONArray())
                    .put("resultFilesReadLimited",fromRun.readLimited)
                fromRun.entries
            }else actual.optJSONArray("sources").objects()+actual.optJSONArray("artifacts").objects()
            val file=pin?.let{selected->files.firstOrNull{it.optString("id")==selected.optString("id")&&it.optString("sha256")==selected.optString("sha256")&&it.optString("availability")=="available"}?:throw IllegalStateException("原文件版本当前不可读，请回原任务核对")}
            if(showOriginal&&file!=null){
                val verified=app.attachmentOutcomes.originalFile(task,file.getString("id"),file.getString("sha256"),control)
                original=verified
                // A locally verified file can still await owner acceptance. Don't install a publication-recovery latch for it.
                if(directWorkOriginal)verifiedRecordedOriginal=AttachmentWorkReturn.capture(actual,file)
                    ?.forRecordedDocument(actual,file)?.matchesVerifiedDocument(file,verified)==true
            }
            if(savedDraft!=null){
                DocumentDraft(savedDraft)
                if(savedDraft.has("parentArtifactId")){
                    check(pin!=null&&pin.optString("id")==savedDraft.getString("parentArtifactId")&&pin.optString("sha256")==savedDraft.getString("parentSha256")){"草稿不属于当前固定原文件，未换用其他来源"}
                    val current=app.ownerEditableDocument(task,savedDraft.getString("parentArtifactId"),savedDraft.getString("parentSha256"),control)
                    requireSameDocumentSelection(current,savedDraft)
                    unpublishedEdits=current.toString()!=savedDraft.toString()
                }
                control.check()
            }
            if(resultRun!=null)requireConversationResult(resultRun,control)
            actual to file
        }else {check(savedDraft==null){"原主人文档草稿不能恢复到群成果"};groupCall(window){app.groupOutcome(group,control)} to null}}
    }
    private fun requireConversationResult(run:AttachmentResultRun,control:RunControl){
        control.check()
        val current=app.tasks.getForUi(run.taskId,false)?:error("原工作已不可读取")
        check(run.matchesCompletedResult(current)){
            if(run.report==null)"原任务状态或来源已变化，请打开当前工作核对成果"
            else "原汇报或本轮文件来源已变化，没有打开其他成果"
        }
    }
    fun selectFile(file:JSONObject){cancel();restoreFile=null;val group=state.group;val selected=if(group==null)null else GroupResultAccess.Selection(group.requestId,group.taskId,group.spaceId,file.getString("id"),file.getString("id"),group.audience,group.visibility,file.optString("sourceOperationId",group.sourceOperationHint),file.optString("sourceTaskRunId",group.sourceRunHint));state=state.copy(group=selected?:group,file=JSONObject(file.toString()),document=null,documentOpen=false,error=null,notice=null,thumbnail=null,originalFile=null,originalReturn=AttachmentOriginalReturn.DETAILS,externalViewerResult=null,copyReceipt=null,previousCopyInFile=null);if(file.optString("mimeType").startsWith("image/"))loadThumbnail()}
    @JvmOverloads
    fun backFromFile(afterRefresh:((Boolean)->Unit)?=null){
        if(state.originalReturn==AttachmentOriginalReturn.ENTRY){close();afterRefresh?.invoke(false);return}
        cancel();restoreFile=null
        state=state.copy(group=state.group?.withoutArtifact(),file=null,document=null,documentOpen=false,thumbnail=null,originalFile=null,
            originalReturn=AttachmentOriginalReturn.DETAILS,copyReceipt=null,previousCopyInFile=null)
        refresh(afterRefresh)
    }
    fun chooseSource(){if(state.team!=null||state.group!=null||state.taskId.isNullOrBlank())return;restoreFile=null;state=state.copy(choosingSource=true,file=null,originalFile=null);work{c,_->app.ownerOutcomeSnapshot(null,c) to null}}
    fun attach(file:JSONObject){val task=state.taskId?:return;if(state.group!=null)return
        work{c,_->app.selectOutcomeForTask(task,file.getString("id"),file.getString("sha256"),c);app.ownerOutcomeSnapshot(task,c) to null}
        state=state.copy(choosingSource=false)
    }
    private fun originalAttachments()=app.features.module("attachments") as dev.pocketengine.features.attachments.AttachmentsModule
    fun openProcessing(){val file=state.file?:return;if(state.busy||state.group!=null||state.team!=null||pendingProcessingAction!=null)return;val task=state.taskId;var source:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null;work(onSuccess={info->source?.let{state=state.copy(processingSource=it,processingInfo=info)}}){c,_->source=app.attachmentOutcomes.originalFile(task,file.getString("id"),file.getString("sha256"),c);originalAttachments().inspectProcessingForOwner(source!!,c) to null}}
    fun closeProcessing(){if(!state.busy)state=state.copy(processingSource=null,processingInfo=null,processingReview=null)}
    fun forgetProcessingReview(){if(!state.busy)state=state.copy(processingReview=null)}
    fun reviewProcessing(args:JSONObject){val source=state.processingSource?:return;if(state.busy||state.group!=null||state.team!=null)return;val pin=JSONObject(args.toString());var review:dev.pocketengine.features.attachments.processing.NativeFileProcess.Review?=null;work(onSuccess={state=state.copy(processingReview=review)}){c,_->review=originalAttachments().reviewProcessingForOwner(source,pin,c);review!!.metadata() to null}}
    fun applyProcessing(){val review=state.processingReview?:return;if(state.busy||state.group!=null||state.team!=null||pendingProcessingAction!=null)return;val action=java.util.UUID.randomUUID().toString();pendingProcessingAction=action;state=state.copy(pendingProcessingTask=action);work(onSuccess={result->if(result.optBoolean("outputNotPublished")){pendingProcessingAction=null;state=state.copy(pendingProcessingTask=null,error=result.optString("message"),processingReview=null)}else if(result.optBoolean("found")){pendingProcessingAction=null;state=state.copy(pendingProcessingTask=null,processingSource=null,processingInfo=null,processingReview=null,taskId=action,file=result,snapshot=null,originalReturn=AttachmentOriginalReturn.DETAILS,notice="新的加工原件已保存，父原件保留。可打开原件或与原版本对照。")}}){c,_->app.features.processFileForOwner(review,c,action) to null}}
    fun queryProcessing(){val action=state.pendingProcessingTask?:return;if(state.busy||state.group!=null||state.team!=null)return;work(onSuccess={result->if(result.optBoolean("found")){pendingProcessingAction=null;state=state.copy(pendingProcessingTask=null,processingSource=null,processingInfo=null,processingReview=null,taskId=action,file=result,snapshot=null,originalReturn=AttachmentOriginalReturn.DETAILS,notice="已找到原加工动作的固定产物，未再次加工")}else state=state.copy(notice="原加工动作尚无完整发布回执，保留原编号，不自动重复。")}){c,_->app.features.fileProcessResultForOwner(action,c) to null}}
    fun compareImageParent(){val file=state.file?:return;if(state.busy||state.group!=null||state.team!=null)return;val parent=file.optJSONObject("parentArtifact")?:file.optJSONObject("source")?.optJSONObject("parentArtifact")?:return;val task=state.taskId;var pair:Pair<dev.pocketengine.features.attachments.OwnerFilePreviewSource,dev.pocketengine.features.attachments.OwnerFilePreviewSource>?=null;work(onSuccess={state=state.copy(comparison=pair)}){c,_->val selected=app.attachmentOutcomes.originalFile(task,file.getString("id"),file.getString("sha256"),c);val before=app.attachmentOutcomes.originalFile(null,parent.getString("id"),parent.getString("sha256"),c);check(selected.mimeType.startsWith("image/")&&before.mimeType.startsWith("image/")){"原父版本不是图片，未换用其他来源"};pair=before to selected;JSONObject().put("outcomeNotice","对照的是两份固定原图，不是最新版本或缩略图替代。") to null}}
    fun closeComparison(){state=state.copy(comparison=null)}
    fun newDocument(){if(state.busy||state.team!=null||state.group!=null||pendingDocumentAction!=null)return;restoreFile=null;restoredDocumentDraft=null;state=state.copy(documentDraft=JSONObject().put("format","docx").put("name","文档").put("title","").put("body","").put("fontPoints",12),documentDraftEdited=false,error=null)}
    fun continueDocument(){
        val file=state.file?:return
        if(state.busy||exportPending||state.team!=null||state.group!=null||state.deviceOriginalOnly||pendingDocumentAction!=null)return
        val editable=file.optJSONObject("editableSource")?:file.optJSONObject("source")?.optJSONObject("editableSource")
            ?:error("这份原件没有登记可编辑源")
        val task=state.taskId;val run=state.resultRun?.takeIf{it.taskId==task}
        val id=file.getString("id");val sha=file.getString("sha256")
        restoredDocumentDraft=null
        work(onSuccess={value->state=state.copy(documentDraft=value,documentDraftEdited=false)}){control,_->
            if(run!=null)requireConversationResult(run,control)
            val draft=app.ownerEditableDocument(task,id,sha,control)
            check(draft.getString("parentArtifactId")==id&&draft.getString("parentSha256")==sha&&
                draft.getString("editableSourceId")==editable.getString("id")&&draft.getString("editableSourceSha256")==editable.getString("sha256")){
                "可编辑源与所选文档版本不一致，没有改用其他稿件"
            }
            if(run!=null)requireConversationResult(run,control)
            draft to null
        }
    }
    fun closeDocumentDraft(){if(!state.busy){restoredDocumentDraft=null;state=state.copy(documentDraft=null,documentDraftEdited=false)}}
    /** A positive saved revision exits only this live editor, preserving the originally selected file. */
    fun finishLinkedDraftRetention(){
        if(state.busy||!privateLibrary()||state.file==null||state.documentDraft==null)return
        restoredDocumentDraft=null
        state=state.copy(documentDraft=null,documentDraftEdited=false,error=null,
            notice="本机修改草稿已保留，原文件不变。可在「成果 → 本机文档草稿」继续。")
    }
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
        try{val pin=checkedDocumentDraft(draft);state=state.copy(documentDraft=pin,documentDraftEdited=state.documentDraftEdited||pin.toString()!=state.documentDraft.toString(),error=null)}catch(failure:Exception){state=state.copy(error=safe(failure))}
    }
    /** Project only the standalone writer's actual Book publication, not a guessed available result row. */
    private fun publishedLibraryDocumentSource(action:String,file:JSONObject):JSONObject?=runCatching{
        val format=libraryDocumentFormat(file.getString("mimeType"))?:return@runCatching null
        check(file.opt("found")==true&&file.opt("originalPublication")==true&&file.opt("principalId")=="owner"&&
            file.opt("spaceId")=="private"&&!file.optBoolean("outputSourceRetired")&&
            file.getString("sha256").matches(Regex("[0-9a-f]{64}"))&&file.optLong("size",-1L) in 1L..AttachmentBook.MAX_FILE_BYTES)
        val source=file.getJSONObject("source");val publication=source.getJSONObject("nativePublication")
        val plan=source.getString("planDigest")
        check(!source.has("groupSpaceId")&&source.opt("kind")=="created_document"&&source.opt("documentFormat")==format&&
            publication.opt("role")=="document_output"&&publication.opt("planDigest")==plan&&
            listOf("taskId","runId","operationId").all{source.opt(it)==action&&publication.opt(it)==action}&&
            AttachmentBook.isOriginalPublicationId(file.getString("id"),action,"document_output",action,action,plan))
        val editable=source.getJSONObject("editableSource")
        check(editable.opt("mimeType")=="application/json"&&editable.optLong("size",-1L) in 1L..(256L*1024)&&
            editable.getString("sha256").matches(Regex("[0-9a-f]{64}"))&&
            AttachmentBook.isOriginalPublicationId(editable.getString("id"),action,"document_source",action,action,plan))
        JSONObject(editable.toString())
    }.getOrNull()

    private fun showPublishedDocument(action:String,file:JSONObject,notice:String,libraryVisit:String?){
        val focused=state.focusedStandaloneDraft
        val focusedEditable=if(focused)publishedLibraryDocumentSource(action,file)?:run {
            state=state.copy(notice="尚未核对到这次生成的原件；已保留草稿，没有打开其他成果。")
            return
        } else null
        val inLibrary=privateLibrary()&&!focused
        val catalog=state.snapshot?.takeIf{
            if(inLibrary)it.opt("taskId")==""
            else state.taskId==action&&state.documentDraft==null&&!state.fromWorkResult&&state.resultRun==null&&
                file.opt("found")==true&&originalDocumentAction(action,it)==action
        }
        // Rechecking the exact saved file must not discard the still-live way back to its Library details.
        val retainedReturn=catalog?.takeUnless{inLibrary}?.let{record->
            currentLibraryDocumentReturn()?.takeIf{origin->
                origin.action==action&&file.opt("originalPublication")==true&&
                    file.opt("principalId")=="owner"&&file.opt("spaceId")=="private"&&!file.optBoolean("outputSourceRetired")&&
                    matchesLibraryDocument(origin.file,file,action)&&
                    record.optJSONArray("artifacts").objects().count{matchesLibraryDocument(it,file,action)}==1
            }
        }
        val retainedImport=state.libraryImport.takeIf{inLibrary}
        val retainedVisit=libraryVisit?.takeIf{inLibrary&&catalog!=null&&state.globalStandaloneDraftVisit==it}
        val editable=focusedEditable?:retainedVisit?.let{publishedLibraryDocumentSource(action,file)}
        val published=if(inLibrary||focused)JSONObject(file.toString()).put("role","result")
            .put("sourceKind","created_document").put("sourceTaskId",action).apply{
                if(editable!=null)put("availability","available").put("sourceOperationId",file.getJSONObject("source").getString("operationId"))
                    .put("editableSource",editable)
            } else file
        if(pendingDocumentAction==action)pendingDocumentAction=null
        restoredDocumentDraft=null;restoreFile=null
        // Show only this operation's Book file; a separate pending save still needs its own receipt.
        // Only an actual produced file leaves the focused draft for the existing exact Work/result reader.
        state=AttachmentOutcomeState(open=true,taskId=action.takeUnless{inLibrary},snapshot=catalog,file=published,
            originalReturn=if(focused)AttachmentOriginalReturn.ENTRY else AttachmentOriginalReturn.DETAILS,
            pendingDocumentTask=pendingDocumentAction,pendingProcessingTask=pendingProcessingAction,notice=notice,
            libraryImport=retainedImport,libraryDocumentReturn=retainedReturn,globalStandaloneDraftVisit=retainedVisit?.takeIf{editable!=null})
    }
    private fun isPublishedDocument(action:String,file:JSONObject):Boolean{
        val source=file.optJSONObject("source")?:return false
        return file.opt("status")=="completed"&&file.opt("accepted")==true&&file.opt("receiptReceived")==true&&
            file.opt("taskId")==action&&source.opt("kind")=="created_document"&&source.opt("taskId")==action&&
            source.opt("operationId")==action&&file.optString("id").isNotBlank()&&file.optString("sha256").matches(Regex("[a-f0-9]{64}"))
    }
    @JvmOverloads
    fun createDocument(draft:JSONObject,keepLibraryVisit:(String)->Boolean={false}){
        if(state.busy||state.team!=null||state.group!=null||state.documentDraft==null||pendingDocumentAction!=null)return
        val pin=try{checkedDocumentDraft(draft)}catch(failure:Exception){state=state.copy(error=safe(failure));return}
        state=state.copy(documentDraft=pin)
        startDocumentCreation(pin,keepLibraryVisit)
    }
    /** Fresh, exact success already delivered by the existing standalone writer; no new Book read. */
    fun currentGeneratedDraftOutput(action:String,plan:String):Pair<String,String>? {
        val file=state.file?:return null
        if(!privateLibrary()||state.globalStandaloneDraftVisit==null||!isPublishedDocument(action,file)||
            file.optJSONObject("source")?.opt("planDigest")!=plan||publishedLibraryDocumentSource(action,file)==null)return null
        return file.getString("id") to file.getString("sha256")
    }

    /** The current private Library bridge has just revalidated the exact saved revision and consumed its review. */
    fun createDocumentFromSavedDraft(draft:DocumentDraft, source:RevisionSource?, keepLibraryVisit:(String)->Boolean):String {
        check(privateLibrary() && state.globalStandaloneDraftVisit!=null && !state.busy && !exportPending &&
            state.file==null && state.originalFile==null && state.documentDraft==null && !state.documentOpen &&
            state.processingSource==null && state.comparison==null && state.pendingDocumentTask==null && pendingDocumentAction==null){
            "请在当前私人文件库核对这份草稿，没有开始生成"
        }
        val input=DocumentDraft.sourceInput(draft.source())
        val fields=setOf("format","name","title","body","fontPoints")
        val linked=if(source==null) emptyMap() else mapOf("parentArtifactId" to source.parentArtifactId().toString(),
            "parentSha256" to source.parentSha256(),"editableSourceId" to source.editableSourceId().toString(),
            "editableSourceSha256" to source.editableSourceSha256())
        require(input.keys().asSequence().toSet()==fields+linked.keys && linked.all { (key, value) -> input.opt(key)==value })
        // The already saved draft stays solely in its own store, not in the legacy editor or Activity Bundle.
        return startDocumentCreation(input,keepLibraryVisit)
    }
    private fun startDocumentCreation(pin:JSONObject,keepLibraryVisit:(String)->Boolean):String {
        val libraryVisit=state.globalStandaloneDraftVisit?.takeIf(keepLibraryVisit)
        val action=java.util.UUID.randomUUID().toString();pendingDocumentAction=action
        state=state.copy(pendingDocumentTask=action)
        work(onSuccess={actual->when{
            actual.optBoolean("outputNotPublished")->{
                if(pendingDocumentAction==action)pendingDocumentAction=null
                val notice=if(state.documentDraft==null)"没有发布新文件。原草稿仍在本机，可重新打开修改。"
                    else "原可编辑源已保存，可修正当前稿后明确生成新版本。"
                state=state.copy(pendingDocumentTask=pendingDocumentAction,error=actual.optString("message"),notice=notice)
            }
            isPublishedDocument(action,actual)->showPublishedDocument(action,actual,"新文档已保存到私人文件库，可以打开核对。没有改动原件，也没有导出。",libraryVisit?.takeIf(keepLibraryVisit))
            else->state=state.copy(notice="尚未核对到这次生成的文件。请核对原文档结果，不会自动重新生成。")
        }}){c,_->app.features.createDocumentForOwner(pin,c,action) to null}
        return action
    }
    private fun originalDocumentAction(task:String?,snapshot:JSONObject?):String?{
        val actual=snapshot?:return null
        return task?.takeIf{it.isNotBlank()&&actual.opt("taskId")==it&&actual.opt("taskRunId")==it&&
            actual.opt("coordinatorEpoch")==1&&actual.opt("originalDocumentActionId")==it}
    }
    @JvmOverloads
    fun queryDocument(keepLibraryVisit:(String)->Boolean={false},samePendingLibrary:()->Boolean={false}){
        if(state.busy||state.group!=null||state.team!=null||state.deviceOriginalOnly)return
        // In a document record, check its own freshly read action, never an unrelated pending
        // save. A live editor still checks its pending save, even when editing from another file.
        val action=(if(state.documentDraft!=null||privateLibrary())pendingDocumentAction?.takeIf{it==state.pendingDocumentTask}
            else if(state.resultRun==null&&!state.choosingSource)originalDocumentAction(state.taskId,state.snapshot)
            else null)?:return
        val libraryVisit=state.globalStandaloneDraftVisit?.takeIf(keepLibraryVisit)
        val checkPendingHere=pendingDocumentAction==action&&state.pendingDocumentTask==action&&samePendingLibrary()
        work(onSuccess={actual->
            if(actual.opt("found")==true&&isPublishedDocument(action,actual)&&actual.optJSONObject("source")?.opt("runId")==action)
                showPublishedDocument(action,actual,"已找到原操作的实际固定文档，没有重新生成",libraryVisit?.takeIf(keepLibraryVisit))
            else if(checkPendingHere&&samePendingLibrary()&&pendingDocumentAction==action&&state.pendingDocumentTask==action&&
                actual.opt("found")==false&&actual.opt("publicationEntered")==false&&actual.opt("nativeNoPublication")==true&&
                actual.opt("status")=="failed"&&actual.opt("taskId")==action&&actual.opt("runId")==action&&
                actual.opt("operationId")==action&&actual.opt("coordinatorEpoch")==1){
                pendingDocumentAction=null
                state=state.copy(pendingDocumentTask=null,notice="已核对原生成：未发布新文件。本机草稿不变，没有重新生成。")
            } else state=state.copy(notice="原文档尚无完整发布回执。可查看原任务和已保留源，不自动重复生成。")
        }){c,_->app.features.documentResultForOwner(action,c) to null}
    }

    private val documentRecordUnavailable="暂不能核对这份原件的生成记录。仍保留这份文件，没有改用其他记录。"
    private fun libraryDocumentFormat(mime:String)=when(mime){
        "application/pdf"->"pdf"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"->"docx"
        else->null
    }
    private fun settledDocumentStatus(status:String)=status=="needs_confirmation"||status=="completed"
    private fun matchesLibraryDocument(row:JSONObject,selected:JSONObject,id:String)=
        row.opt("role")=="result"&&row.opt("availability")=="available"&&row.opt("sourceKind")=="created_document"&&
            row.opt("sourceTaskId")==id&&row.opt("sourceTaskRunId")==id&&row.opt("sourceOperationId")==id&&
            row.opt("id")==selected.opt("id")&&row.opt("sha256")==selected.opt("sha256")&&row.opt("mimeType")==selected.opt("mimeType")&&
            row.opt("name")==selected.opt("name")&&row.optLong("size",-1)==selected.optLong("size",-2)

    /** A link from the existing private library sources pane, not a current task or a filename guess. */
    fun libraryDocumentActionId():String?{
        val selected=state.file?:return null
        val snapshot=state.snapshot?:return null
        val id=selected.optString("sourceTaskId")
        val format=libraryDocumentFormat(selected.optString("mimeType"))?:return null
        if(!privateLibrary()||state.fromWorkResult||state.originalReturn!=AttachmentOriginalReturn.LIST||
            state.originalFile!=null||state.documentOpen||state.documentDraft!=null||state.processingSource!=null||state.comparison!=null||
            snapshot.opt("taskId")!=""||!id.matches(Regex("[a-f0-9-]{36}"))||
            !selected.optString("id").matches(Regex("[a-f0-9-]{36}"))||
            !selected.optString("sha256").matches(Regex("[a-f0-9]{64}"))||selected.optLong("size",-1)<=0||
            !matchesLibraryDocument(selected,selected,id))return null
        val exactActions=snapshot.optJSONObject("documentActions")?.optJSONArray("actions").objects().count{
            it.opt("taskId")==id&&it.opt("runId")==id&&it.opt("operationId")==id&&it.opt("coordinatorEpoch")==1&&
                it.opt("format")==format&&settledDocumentStatus(it.optString("status"))
        }
        return id.takeIf{exactActions==1&&snapshot.optJSONArray("artifacts").objects().count{matchesLibraryDocument(it,selected,id)}==1}
    }

    fun openDocumentAction(id:String,sourceCurrent:(()->Boolean)?=null){
        if(state.busy||state.group!=null||state.team!=null||state.taskId!=null||state.documentDraft!=null)return
        val actions=state.snapshot?.optJSONObject("documentActions")?.optJSONArray("actions").objects()
        if(actions.none{it.optString("taskId")==id}){state=state.copy(error="原文档动作不在本页原生记录中，请刷新后明确选择。");return}
        val selected=state.file
        if(selected==null){openTask(id);return}
        val current=checkNotNull(sourceCurrent){documentRecordUnavailable}
        check(current()&&!exportPending&&libraryDocumentActionId()==id){documentRecordUnavailable}
        val format=checkNotNull(libraryDocumentFormat(selected.getString("mimeType")))
        val origin=AttachmentLibraryDocumentReturn(id,checkNotNull(state.snapshot),selected,state.libraryImport,state.copyReceipt,state.previousCopyInFile)
        val fileId=selected.getString("id");val sha=selected.getString("sha256")
        fun sameSource()=current()&&state.file===selected&&state.snapshot===origin.snapshot
        work(completionFailure=documentRecordUnavailable,onSuccess={actual->
            if(sameSource()){
                // All we enter is the original record. Neither the pending document action nor the file is updated.
                restoredDocumentDraft=null;restoreFile=null
                state=AttachmentOutcomeState(open=true,taskId=id,snapshot=actual.getJSONObject("record"),
                    pendingDocumentTask=pendingDocumentAction,pendingProcessingTask=pendingProcessingAction,libraryDocumentReturn=origin)
            }else state=state.copy(error=documentRecordUnavailable)
        }){control,_->
            control.setAuthorityCheck{check(sameSource()){documentRecordUnavailable}}
            control.check()
            val task=app.tasks.getForUi(id,false)?:error(documentRecordUnavailable)
            val producer=task.optJSONObject("externalRefs")?.optJSONObject("documentCreation")?:error(documentRecordUnavailable)
            val evidence=task.optJSONObject("evidence")?:error(documentRecordUnavailable)
            val plan=producer.optString("planDigest")
            check(task.opt("id")==id&&task.opt("runId")==id&&task.opt("operationId")==id&&task.opt("coordinatorEpoch")==1&&
                task.opt("actorId")=="owner"&&task.opt("spaceId")=="private"&&task.isNull("origin")&&task.isNull("conversationId")&&
                task.isNull("parentTaskId")&&task.opt("feature")=="attachments"&&task.opt("command")=="createDocument"&&
                task.opt("taskProjection")=="detail_window"&&task.opt("taskCoreReadLimited")==false&&
                !task.optBoolean("contextWithdrawn")&&!task.optBoolean("sourceCurrentReadRequired")&&task.optBoolean("contextAvailable",true)&&
                settledDocumentStatus(task.optString("status"))&&task.opt("executionStatus")=="completed"&&
                task.opt("externalId")==fileId&&task.opt("externalOperationId")==id&&
                evidence.opt("id")==fileId&&evidence.opt("operationId")==id&&evidence.opt("executorStatus")=="completed"&&
                producer.opt("format")==format&&plan.matches(Regex("[a-f0-9]{64}"))){documentRecordUnavailable}
            val record=app.ownerOutcomeSnapshot(id,control)
            check(originalDocumentAction(id,record)==id&&settledDocumentStatus(record.optString("taskStatus"))&&
                record.optJSONArray("artifacts").objects().count{matchesLibraryDocument(it,selected,id)}==1){documentRecordUnavailable}
            // Same immutable native publication, not the FeatureHost query that can reconcile a lost producer callback.
            val saved=originalAttachments().documentResultForHost(id,id,id,plan,control)
            val source=saved.getJSONObject("source")
            check(saved.opt("found")==true&&saved.opt("originalPublication")==true&&saved.opt("id")==fileId&&saved.opt("sha256")==sha&&
                saved.opt("principalId")=="owner"&&saved.opt("spaceId")=="private"&&!saved.optBoolean("outputSourceRetired")&&
                saved.opt("name")==selected.opt("name")&&saved.opt("mimeType")==selected.opt("mimeType")&&
                saved.optLong("size",-1)==selected.optLong("size",-2)&&source.opt("kind")=="created_document"&&
                source.opt("taskId")==id&&source.opt("runId")==id&&source.opt("operationId")==id&&
                source.opt("documentFormat")==format&&source.opt("planDigest")==plan){documentRecordUnavailable}
            control.check()
            JSONObject().put("record",record) to null
        }
    }

    private fun currentLibraryDocumentReturn()=state.libraryDocumentReturn?.takeIf{
        state.open&&state.group==null&&state.team==null&&!state.deviceOriginalOnly&&state.resultRun==null&&
            !state.choosingSource&&state.taskId==it.action&&originalDocumentAction(it.action,state.snapshot)==it.action
    }
    fun canReturnToLibraryDocument()=currentLibraryDocumentReturn()!=null
    fun discardLibraryDocumentReturn(){if(state.libraryDocumentReturn!=null)state=state.copy(libraryDocumentReturn=null)}
    fun backToLibraryDocument():Boolean{
        val origin=currentLibraryDocumentReturn()?:return false
        check(!state.busy&&!exportPending&&state.file==null){documentRecordUnavailable}
        cancel();restoreFile=null;restoredDocumentDraft=null
        state=AttachmentOutcomeState(open=true,snapshot=origin.snapshot,file=origin.file,originalReturn=AttachmentOriginalReturn.LIST,
            pendingDocumentTask=pendingDocumentAction,pendingProcessingTask=pendingProcessingAction,libraryImport=origin.libraryImport,
            copyReceipt=origin.copyReceipt,previousCopyInFile=origin.previousCopy)
        return true
    }
    fun olderDocumentActions(){
        if(state.busy||state.group!=null||state.team!=null||state.taskId!=null||state.documentDraft!=null)return
        val snapshot=state.snapshot?:return;val page=snapshot.optJSONObject("documentActions")?:return
        val cursor=page.optString("nextCursor");if(!page.optBoolean("hasMore")||cursor.isEmpty())return
        work(onSuccess={actual->state=state.copy(snapshot=JSONObject(snapshot.toString()).put("documentActions",actual))}){c,_->app.attachmentOutcomes.documentActions(cursor,c) to null}
    }

    fun privateLibrary()=state.open&&state.taskId==null&&state.group==null&&state.team==null&&!state.deviceOriginalOnly&&
        state.resultRun==null&&!state.choosingSource
    private fun currentLibraryImport(original:OwnerAttachmentImport.Target?):AttachmentLibraryImport?=
        state.libraryImport?.takeIf{privateLibrary()&&original!=null&&original.taskId==null&&original.projectId==null&&it.requestId==original.requestId}
    fun importSource(trackLibrary:Boolean){
        if(state.team!=null||state.group!=null)return
        if(!trackLibrary||!privateLibrary()||state.file!=null){activity.pickAttachments(state.taskId,null);return}
        if(state.libraryImport?.phase in setOf("selecting","selection_unconfirmed","registering"))return
        val original=OwnerAttachmentImport.Target(null,null)
        state=state.copy(libraryImport=AttachmentLibraryImport(original.requestId,"selecting"))
        val launch=activity.pickAttachments(original)
        val pending=currentLibraryImport(original)?.takeIf{it.phase=="selecting"}?:return
        val phase=when(launch){
            AttachmentImportPicker.Launch.REQUESTED->"selecting"
            AttachmentImportPicker.Launch.UNKNOWN->"selection_unconfirmed"
            AttachmentImportPicker.Launch.BUSY->"busy_other_selection"
            AttachmentImportPicker.Launch.NOT_STARTED->"not_started"
        }
        if(phase!=pending.phase)state=state.copy(libraryImport=pending.copy(phase=phase))
    }
    fun libraryImportSelected(original:OwnerAttachmentImport.Target?){
        currentLibraryImport(original)?.let{state=state.copy(libraryImport=it.copy(phase="registering"))}
    }
    fun libraryImportSelectionEnded(original:OwnerAttachmentImport.Target?,cancelled:Boolean){
        currentLibraryImport(original)?.let{state=state.copy(libraryImport=it.copy(phase=if(cancelled)"cancelled" else "no_selection"))}
    }
    fun libraryImportUnconfirmed(original:OwnerAttachmentImport.Target?){
        currentLibraryImport(original)?.let{state=state.copy(libraryImport=it.copy(phase="unconfirmed"))}
    }
    fun libraryImportContextUnavailable(){
        if(privateLibrary())state.libraryImport?.takeIf{it.phase in setOf("selecting","selection_unconfirmed","registering")}
            ?.let{state=state.copy(libraryImport=it.copy(phase="unconfirmed"))}
    }
    fun imported(original:OwnerAttachmentImport.Target?,receipt:OwnerAttachmentImport.Receipt){
        currentLibraryImport(original)?.let{pending->
            val registration=runCatching{receipt.registration()}.getOrNull()
            state=state.copy(libraryImport=pending.copy(phase=if(registration!=null)"confirmed" else "unconfirmed",registration=registration))
        }
        if(state.open&&state.team==null&&state.group==null)refresh()
    }
    fun read(range:DocumentContent.Request){val file=state.file?:return;val group=state.group;val task=state.taskId
        work(document=true){c,w->val result=if(group==null)app.ownerOutcomeDocument(task,file.getString("id"),file.getString("sha256"),range,c) else groupCall(w){app.groupOutcomeDocument(group,range,c)};result to null}
    }
    /** Uses only the already displayed native raster. No neighboring page or OCR fallback. */
    fun readDisplayedPdfPage(page:AttachmentPdfPageText,current:()->Boolean){
        val file=checkNotNull(state.file)
        check(privateLibrary() && !state.busy && !exportPending && !state.documentOpen && state.originalFile!=null &&
            file.opt("role")=="source" && file.opt("mimeType")=="application/pdf" && current()){"请先显示这份 PDF 的原页"}
        state=state.copy(pdfPageText=page,document=null)
        val range=DocumentContent.Request(page.page,0,1,12000,48,"text")
        work(document=true){control,_->
            control.setAuthorityCheck { check(current()) { "原 PDF 阅读位置已变化，未显示其他页面的文字" } }
            control.check()
            app.ownerOutcomeDocument(null,file.getString("id"),file.getString("sha256"),range,control) to null
        }
    }
    /** One deliberate review of only the native Word fragments already displayed behind the review. */
    fun readDisplayedWordExcerpt(shown:AttachmentWordExcerpt,current:()->Boolean){
        val file=checkNotNull(state.file)
        check(privateLibrary()&&!state.busy&&!exportPending&&!state.documentOpen&&state.originalFile!=null&&
            file.opt("mimeType")==AttachmentWordOrigin.MIME&&current()){"请先定位到这份 Word 的原段落"}
        val range=DocumentContent.Request.originalWordExcerpt(shown.unit,shown.chunk,shown.blocks.size)
        state=state.copy(wordExcerpt=shown,wordSaveAttempted=false,document=null)
        work(document=true){control,_->
            control.setAuthorityCheck { check(current()){"原 Word 位置已变化，没有换读其他文字"} }
            control.check()
            val value=app.ownerOutcomeDocument(null,file.getString("id"),file.getString("sha256"),range,control)
            check(shown.matchesPreview(value)){"原段落片段无法核对，没有展示其他文字"}
            value to null
        }
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
        val chunks=read.optJSONArray("chunks")?:return false
        state.pdfPageText?.let{page->
            val shown=chunks.objects()
            if(!state.documentOpen||!privateLibrary()||state.originalFile==null||
                read.opt("taskId")!=""||read.opt("readContext")!="native_owner_preview"||read.opt("format")!="pdf"||
                read.opt("extraction")!="text"||read.opt("coverage")!="pdf_text_layer"||
                shown.size!=chunks.length()||shown.any{it.opt("unit")!=page.page})return false
        }
        state.wordExcerpt?.let{shown->
            if(state.wordSaveAttempted||!state.documentOpen||!privateLibrary()||state.originalFile==null||!shown.matchesPreview(read))return false
        }
        return state.group==null&&read.optString("attachmentId")==file.optString("id")&&read.optString("sha256")==file.optString("sha256")&&read.optString("readReceiptId").isNotBlank()&&chunks.length()>0
    }
    private fun listedWorkPdf(snapshot:JSONObject,file:JSONObject):Boolean =
        snapshot.optJSONArray("sources").objects().singleOrNull{it.opt("id")==file.opt("id")}?.let{
            it.opt("sha256")==file.opt("sha256")&&it.opt("role")=="source"&&it.opt("availability")=="available"&&
                it.opt("mimeType")=="application/pdf"&&it.opt("sourceKind")=="content_uri"&&it.isNull("sourceTaskId")&&
                it.optLong("size",-1)>0&&it.optLong("size",-1)==file.optLong("size",-2)
        }==true
    /** Actual saved TXT for this still-held page read, not a saved-file search or a reusable locator. */
    fun savedPdfPageExtract(page:AttachmentPdfPageText):JSONObject? {
        val source=state.file?:return null
        val read=state.document?:return null
        val catalog=state.snapshot?:return null
        val saved=catalog.optJSONObject("savedPdfPageExtractHere")?:return null
        val receipt=read.opt("readReceiptId") as? String?:return null
        if(!privateLibrary()||state.globalStandaloneDraftVisit==null||state.originalReturn!=AttachmentOriginalReturn.LIST||
            !state.documentOpen||state.originalFile==null||state.pdfPageText!=page||catalog.opt("taskId")!=""||
            receipt.isBlank()||receipt!=saved.opt("readReceiptId")||read.opt("attachmentId")!=source.opt("id")||
            read.opt("sha256")!=source.opt("sha256")||saved.opt("viewerId")!=page.viewerId||
            saved.opt("rasterId")!=page.rasterId||saved.opt("page")!=page.page||
            saved.opt("sourceAttachmentId")!=source.opt("id")||saved.opt("sourceSha256")!=source.opt("sha256"))return null
        return catalog.optJSONArray("artifacts").objects().singleOrNull{it.opt("id")==saved.opt("id")}?.takeIf{
            it.opt("sha256")==saved.opt("sha256")&&it.opt("role")=="result"&&it.opt("availability")=="available"&&
                it.opt("sourceKind")=="document_extract"&&it.opt("mimeType")=="text/plain"&&
                it.opt("sourceAttachmentId")==source.opt("id")&&it.opt("sourceSha256")==source.opt("sha256")
        }
    }
    fun saveReadAsFile(currentPdfPage:(()->Boolean)?=null,currentWord:(()->Boolean)?=null){
        if(state.busy||!canSaveRead())return;val task=state.taskId;val file=state.file?:return;val read=state.document?:return
        val page=state.pdfPageText
        val word=state.wordExcerpt
        check(page==null||currentPdfPage?.invoke()==true) { "原 PDF 阅读位置已变化，未保存摘录" }
        check(word==null||currentWord?.invoke()==true) { "原 Word 位置已变化，未保存摘录" }
        if(page!=null&&savedPdfPageExtract(page)!=null)return
        if(word!=null)state=state.copy(wordSaveAttempted=true)
        val receipt=read.getString("readReceiptId")
        val pdfWork=state.snapshot?.takeIf{task!=null&&it.opt("taskId")==task&&it.opt("taskStatus")=="completed"&&
            it.optString("taskRunId").isNotBlank()&&it.optInt("coordinatorEpoch")>0&&
            state.resultRun==null&&state.team==null&&!state.deviceOriginalOnly&&!state.choosingSource&&!state.restoredWorkReturn&&
            state.originalFile==null&&state.documentOpen&&file.opt("role")=="source"&&file.opt("mimeType")=="application/pdf"&&
            read.opt("readContext")=="native_owner_preview"&&read.opt("taskId")==task&&read.opt("format")=="pdf"&&
            read.opt("coverage")=="pdf_text_layer"&&listedWorkPdf(it,file)}
        work(replaceDocumentWithFile=page==null&&word==null,onSuccess={if(word!=null && state.wordExcerpt===word)
                state=state.copy(documentOpen=false,document=null,wordExcerpt=null,wordSaveAttempted=false)}){c,_->
            if(page!=null){
                c.setAuthorityCheck { check(currentPdfPage?.invoke()==true) { "原 PDF 阅读位置已变化，未确认保存" } }
                c.check()
            }
            if(word!=null){
                c.setAuthorityCheck { check(currentWord?.invoke()==true){"原 Word 位置已变化，未确认保存"} }
                c.check()
            }
            val saved=app.attachmentOutcomes.saveOwnerDocumentExtract(task,file.getString("id"),file.getString("sha256"),receipt,c)
            val snapshot=app.ownerOutcomeSnapshot(task,c);val artifacts=snapshot.optJSONArray("artifacts").objects()
            val selected=artifacts.firstOrNull{it.optString("id")==saved.getString("id")&&it.optString("sha256")==saved.getString("sha256")}?:throw IllegalStateException("摘录文件已保存，请刷新原任务成果核对；没有再次生成")
            val source=saved.optJSONObject("source")
            if(page!=null){
                check(task==null&&source!=null&&snapshot.opt("taskId")==""&&
                    saved.opt("principalId")=="owner"&&saved.opt("spaceId")=="private"&&saved.opt("mimeType")=="text/plain"&&
                    !saved.optBoolean("outputSourceRetired")&&source.opt("kind")=="document_extract"&&
                    source.isNull("taskId")&&source.isNull("runId")&&source.isNull("operationId")&&
                    source.opt("readReceiptId")==receipt&&source.opt("coverage")=="pdf_text_layer"&&
                    source.opt("sourceAttachmentId")==file.opt("id")&&source.opt("sourceSha256")==file.opt("sha256")&&
                    selected.opt("id")!=file.opt("id")&&selected.opt("role")=="result"&&selected.opt("availability")=="available"&&
                    selected.opt("sourceKind")=="document_extract"&&selected.opt("mimeType")=="text/plain"&&
                    selected.opt("sourceAttachmentId")==file.opt("id")&&selected.opt("sourceSha256")==file.opt("sha256")&&
                    artifacts.count{it.opt("id")==selected.opt("id")}==1){"本页摘录已写入，未核对到完整回执；可到成果清单核对。"}
                val verified=JSONObject().put("id",selected.getString("id")).put("sha256",selected.getString("sha256"))
                    .put("viewerId",page.viewerId).put("rasterId",page.rasterId).put("page",page.page).put("readReceiptId",receipt)
                    .put("sourceAttachmentId",file.getString("id")).put("sourceSha256",file.getString("sha256"))
                return@work snapshot.put("savedPdfPageExtractHere",verified) to null
            }
            if(word!=null){
                val expected=AttachmentWordOrigin(file.getString("id"),file.getString("sha256"),word.unit,word.chunk,word.blocks.size)
                check(source!=null&&source.opt("readReceiptId")==receipt&&source.opt("originalWordExcerpt")==AttachmentWordOrigin.FORMAT&&
                    saved.opt("principalId")=="owner"&&saved.opt("spaceId")=="private"&&selected.opt("mimeType")=="text/plain"&&
                    selected.opt("role")=="result"&&selected.opt("availability")=="available"&&
                    AttachmentWordOrigin.from(selected.optJSONObject("originalWordExcerpt"))==expected&&
                    artifacts.count{it.opt("id")==selected.opt("id")}==1){"摘录尚未核对，请回成果清单，不会自动重复保存"}
                val verified=JSONObject().put("id",selected.opt("id")).put("sha256",selected.opt("sha256"))
                    .put("viewerId",word.viewerId).put("unit",word.unit).put("chunk",word.chunk)
                return@work snapshot.put("savedWordExcerptHere",verified)
                    .put("outcomeNotice","已保存 Word 摘录，原文件和当前阅读位置保留。") to null
            }
            val samePdfSave=pdfWork!=null&&source!=null&&snapshot.opt("taskId")==task&&snapshot.opt("taskStatus")=="completed"&&
                snapshot.opt("taskRunId")==pdfWork.opt("taskRunId")&&snapshot.opt("coordinatorEpoch")==pdfWork.opt("coordinatorEpoch")&&
                saved.opt("principalId")=="owner"&&saved.opt("spaceId")=="private"&&saved.opt("mimeType")=="text/plain"&&
                !saved.optBoolean("outputSourceRetired")&&
                source.opt("kind")=="document_extract"&&source.opt("taskId")==task&&source.opt("readReceiptId")==receipt&&
                source.opt("sourceAttachmentId")==file.opt("id")&&source.opt("sourceSha256")==file.opt("sha256")&&
                source.opt("coverage")=="pdf_text_layer"&&source.isNull("runId")&&source.isNull("operationId")&&
                !source.has("archiveEvidence")&&selected.opt("id")!=file.opt("id")&&selected.opt("role")=="result"&&
                selected.opt("availability")=="available"&&selected.opt("runProvenance")=="unrecorded"&&
                artifacts.count{it.opt("id")==selected.opt("id")}==1&&listedWorkPdf(snapshot,file)
            // A presentation receipt for this one save; never part of a stored file, picker pin or normal file list.
            if(samePdfSave)snapshot.put("savedPdfExcerptHere",JSONObject().put("id",selected.opt("id")).put("sha256",selected.opt("sha256")))
            snapshot.put("outcomeNotice","已将当前已读范围保存为文字成果，可以打开或导出；原文件保留，后续内容仍需另行读取") to selected
        }
    }
    private fun loadThumbnail(){val file=state.file?:return;val group=state.group;val task=state.taskId
        work{c,w->val image=if(group==null)app.attachmentOutcomes.thumbnail(task,file.getString("id"),file.getString("sha256"),c)else{var image:android.graphics.Bitmap?=null;groupCall(w){image=app.groupOutcomeThumbnail(group,c);JSONObject()};image?:throw IllegalStateException("图片预览未取得");}
            main.post{if(!closed&&state.open&&wait===c&&!c.isCancelled)state=state.copy(thumbnail=image)else image.recycle()};JSONObject() to null}
    }
    fun backFromDocument(){cancel();state=if(state.pdfPageText==null&&state.wordExcerpt==null)state.copy(documentOpen=false)
        else state.copy(documentOpen=false,pdfPageText=null,wordExcerpt=null,wordSaveAttempted=false,document=null)}

    /** Current Library only: the saved artifact supplies the source reference, not the browser. */
    fun libraryWordExcerpt():Pair<AttachmentWordOrigin,JSONObject>? {
        val saved=state.file?:return null
        val snapshot=state.snapshot?:return null
        if(!privateLibrary()||state.globalStandaloneDraftVisit==null||snapshot.opt("taskId")!=""||
            state.wordSourceVisit!=null||state.documentOpen||state.documentDraft!=null||state.processingSource!=null||state.comparison!=null||
            saved.opt("sourceKind")!="document_extract"||saved.opt("mimeType")!="text/plain"||saved.opt("role")!="result")return null
        val row=snapshot.optJSONArray("artifacts").objects().singleOrNull{it.opt("id")==saved.opt("id")}?:return null
        val origin=AttachmentWordOrigin.from(row.optJSONObject("originalWordExcerpt"))?:return null
        if(row.opt("sha256")!=saved.opt("sha256")||row.opt("sourceKind")!="document_extract"||
            row.opt("mimeType")!="text/plain"||row.opt("role")!="result"||row.opt("availability")!="available"||
            saved.opt("id")==origin.fileId||saved.opt("sourceAttachmentId")!=origin.fileId||saved.opt("sourceSha256")!=origin.sha256)return null
        val source=snapshot.optJSONArray("sources").objects().singleOrNull{it.opt("id")==origin.fileId}
            ?.takeIf(origin::matchesSource)?:return null
        return origin to source
    }
    fun openLibraryWordExcerptSource(current:()->Boolean){
        val (origin,row)=checkNotNull(libraryWordExcerpt()){"摘录的原 Word 版本暂不可核对"}
        val excerpt=checkNotNull(state.file)
        check(!state.busy&&!exportPending&&current()){"摘录的原 Word 版本暂不可核对"}
        var verified:OwnerFilePreviewSource?=null
        fun sameExcerpt()=state.file===excerpt&&libraryWordExcerpt()?.first==origin&&current()
        work(onSuccess={
            if(sameExcerpt())verified?.let{source->
                state=state.copy(file=row,originalFile=source,originalReturn=AttachmentOriginalReturn.DETAILS,document=null,
                    copyReceipt=null,previousCopyInFile=null,wordSourceVisit=AttachmentWordSourceVisit(origin,source,excerpt))
            }
        }){control,_->
            control.setAuthorityCheck { check(sameExcerpt()){"原摘录窗口已变化，未打开其他文件"} }
            control.check()
            val actual=app.attachmentOutcomes.originalWordExcerptSource(excerpt.getString("id"),excerpt.getString("sha256"),
                origin.fileId,origin.sha256,origin.unit,origin.chunk,origin.parts,control)
            check(actual.mimeType==AttachmentWordOrigin.MIME&&actual.sha256==origin.sha256&&actual.name==row.opt("name")&&
                actual.sizeBytes==row.optLong("size",-1)){"摘录的原 Word 版本已变化"}
            verified=actual
            JSONObject() to null
        }
    }
    fun backFromWordExcerptSource(){
        val visit=state.wordSourceVisit?:return
        check(privateLibrary()&&state.originalFile===visit.source&&state.file?.let(visit.origin::matchesSource)==true)
        cancel()
        state=state.copy(file=visit.excerpt,originalFile=null,document=null,documentOpen=false,
            originalReturn=AttachmentOriginalReturn.DETAILS,copyReceipt=null,previousCopyInFile=null,wordSourceVisit=null)
    }
    fun discardWordExcerptSource(){
        val visit=state.wordSourceVisit?:return
        if(privateLibrary()&&state.originalFile===visit.source&&state.file?.let(visit.origin::matchesSource)==true)
            backFromWordExcerptSource()
        else state=state.copy(wordSourceVisit=null)
    }
    fun openOriginalFile(){
        if(state.busy)return;val selectedTeam=state.team;val selectedGroup=state.group;val selectedFile=state.file;val task=state.taskId
        if(selectedTeam==null&&selectedFile==null)return
        val resultRun=state.resultRun?.takeIf{it.taskId==task}
        var source:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null
        work(onSuccess={source?.let{state=state.copy(originalFile=it)}}){c,w->
            source=when{
                selectedTeam!=null->requireTeamHost().originalFile(selectedTeam,c)
                selectedGroup!=null->app.groupOriginalFilePreview(selectedGroup,w?:error("请回原群成果重新打开"),c)
                else->{
                    // Returning to file details retains the scoped result, not a general library selection.
                    if(resultRun!=null)requireConversationResult(resultRun,c)
                    val original=app.attachmentOutcomes.originalFile(task,selectedFile!!.getString("id"),selectedFile.getString("sha256"),c)
                    if(resultRun!=null)requireConversationResult(resultRun,c)
                    original
                }
            };JSONObject() to null
        }
    }
    fun backFromOriginalFile(){
        when(state.originalReturn){
            AttachmentOriginalReturn.ENTRY->close()
            AttachmentOriginalReturn.LIST->backFromFile()
            AttachmentOriginalReturn.DETAILS->state=state.copy(originalFile=null)
        }
    }
    /** Show the same file's existing actions, without adopting another source or reopening its bytes. */
    fun showOriginalDetails(){
        check(state.originalReturn!=AttachmentOriginalReturn.DETAILS&&state.originalFile!=null&&state.file!=null&&
            state.group==null&&state.team==null&&!state.deviceOriginalOnly){"请先打开这份固定原件"}
        state=state.copy(originalFile=null)
    }
    private fun directPrivateWorkEntry()=state.open&&state.fromWorkResult&&state.taskId!=null&&state.resultRun==null&&
        state.originalReturn==AttachmentOriginalReturn.ENTRY&&state.group==null&&state.team==null&&!state.deviceOriginalOnly
    fun needsRestoredWorkReturn()=state.restoredWorkReturn&&directPrivateWorkEntry()&&state.documentDraft==null&&
        state.comparison==null&&state.processingSource==null&&!state.documentOpen
    /** A deliberate Back can return only to the original completed run and exact same file. */
    fun verifyRestoredWorkReturn(windowCurrent:()->Boolean,ready:(AttachmentWorkReturn,()->Boolean)->Unit,failed:(String)->Unit) {
        val entry=state.workReturn
        val mismatch="无法核对原工作轮次和固定文件，未打开其他工作。可关闭文件，回原入口核对。"
        if(entry==null||!needsRestoredWorkReturn()||state.busy||exportPending||
            state.taskId!=entry.run.taskId||!entry.matchesFile(state.file)||!windowCurrent()){
            failed(mismatch);return
        }
        val generationAtRead=generation+1
        val snapshot=state.snapshot;val file=state.file
        val recordedDocument=snapshot?.let{entry.forRecordedDocument(it,file)}
        val checkedEntry=recordedDocument?:entry
        fun current()=!closed&&generation==generationAtRead&&needsRestoredWorkReturn()&&state.workReturn===entry&&state.taskId==entry.run.taskId&&
            entry.matchesFile(state.file)&&windowCurrent()
        var handedOff=false
        work(onFinished={same->
            if(!handedOff)failed(if(same)state.error?:mismatch else "原文件窗口已变化，未打开其他工作。")
        },onSuccess={
            if(current()){
                handedOff=true
                try{ready(checkedEntry,::current)}catch(failure:Exception){failed(safe(failure))}
            }
        },completionFailure=mismatch){control,_->
            check(current()){mismatch}
            val task=entry.run.taskId
            check(checkedEntry.matchesTask(app.tasks.getForUi(task,false)?:error(mismatch))){mismatch}
            check(snapshot!=null&&entry.matchesResult(snapshot,file)){mismatch}
            if(recordedDocument!=null)app.attachmentOutcomes.originalRecordedDocument(task,entry.fileId,entry.sha256,control)
            else app.attachmentOutcomes.originalFile(task,entry.fileId,entry.sha256,control)
            control.check()
            check(current()&&checkedEntry.matchesTask(app.tasks.getForUi(task,false)?:error(mismatch))){mismatch}
            JSONObject() to null
        }
    }
    /** Re-admit the displayed private report original in its already-open work, not as an export. */
    fun openReportOriginalInWork(current: () -> Boolean, entered: () -> Unit) {
        val reportRun = state.resultRun?.takeIf { it.report != null } ?: error("请先打开原汇报的本轮成果")
        val file = state.file?.takeIf { state.originalFile != null && it.optString("role") == "result" }
            ?: error("请先打开这份固定原件")
        openListedOriginal(file.getString("id"), file.getString("sha256"), onOpened = {
            // The exact run still constrains the normal work view. The old reader is retired by its owner.
            state = state.copy(resultRun = reportRun.copy(report = null), fromWorkResult = true)
            entered()
        }, current = current)
    }
    /** Read a saved item from its displayed library, native generation record or exact result run. */
    fun openListedOriginal(id: String, sha256: String, onOpened: (() -> Unit)? = null, librarySource: Boolean = false,
                           expectedLibraryPdfIdentity: String? = null, current: () -> Boolean) {
        val run = state.resultRun
        val listed = state.snapshot ?: error("文件清单尚未读取")
        val library = run == null && state.taskId == null && state.file == null && onOpened == null && listed.opt("taskId") == ""
        check(expectedLibraryPdfIdentity == null || library && librarySource) { "请从原私人文件库继续阅读" }
        val recordedDocument = if (run == null && state.file == null && onOpened == null && !state.fromWorkResult && !librarySource)
            originalDocumentAction(state.taskId, listed) else null
        check(library || recordedDocument != null || run != null && run.taskId == state.taskId) { "请在原文件清单中打开这份原件" }
        check(current() && state.open && !state.busy && !exportPending &&
            state.group == null && state.team == null && !state.deviceOriginalOnly && !state.choosingSource &&
            state.documentDraft == null && state.processingSource == null && state.comparison == null && !state.documentOpen &&
            (state.file == null || state.originalFile != null)) { "请先返回本轮成果或完成当前文件操作" }
        val entries = if(library&&librarySource)listed.getJSONArray("sources").objects()
            else run?.files(listed)?.entries ?: listed.getJSONArray("artifacts").objects()
        val selected = entries.singleOrNull { it.optString("id") == id && it.optString("sha256") == sha256 &&
            it.optString("role") == (if(library&&librarySource)"source" else "result") && it.optString("availability") == "available" &&
            (recordedDocument == null || it.opt("recordedDocumentOriginal") == true) }
            ?.let { JSONObject(it.toString()) } ?: error("所选原件不在这次已核对的成果中")
        val originalReturn = when {
            onOpened != null -> AttachmentOriginalReturn.ENTRY // Re-admitted inside the already-open original work.
            state.file == null -> AttachmentOriginalReturn.LIST
            else -> state.originalReturn
        }
        var original: dev.pocketengine.features.attachments.OwnerFilePreviewSource? = null
        fun sameRecord() = recordedDocument == null || state.taskId == recordedDocument && state.snapshot === listed
        work(onSuccess = {
            if (current() && state.resultRun == run && sameRecord()) original?.let { source ->
                restoreFile = null
                val same = state.file?.let(::copySource) == copySource(selected)
                // The recorded-document reader just rechecked this exact published Word/PDF and its original operation.
                val checkedPending = recordedDocument != null && pendingDocumentAction == recordedDocument && state.pendingDocumentTask == recordedDocument
                if (checkedPending) pendingDocumentAction = null
                state = state.copy(file = selected, originalFile = source, originalReturn = originalReturn, thumbnail = null, document = null,
                    copyReceipt = state.copyReceipt.takeIf { same }, previousCopyInFile = state.previousCopyInFile.takeIf { same },
                    pendingDocumentTask = if (checkedPending) null else state.pendingDocumentTask)
                onOpened?.invoke()
            }
        }) { control, _ ->
            if (run != null) requireConversationResult(run, control)
            val source = if (recordedDocument == null) app.attachmentOutcomes.originalFile(run?.taskId, id, sha256, control)
                else {
                    control.setAuthorityCheck { check(current() && sameRecord()) { "原生成记录窗口已变化" } }
                    control.check()
                    app.attachmentOutcomes.originalRecordedDocument(recordedDocument, id, sha256, control)
                }
            check(source.name == selected.getString("name") && source.mimeType == selected.getString("mimeType") &&
                source.sizeBytes == selected.getLong("size")) { "原文件信息已变化，请重新读取文件清单" }
            check(expectedLibraryPdfIdentity == null ||
                source.mimeType == "application/pdf" && source.identity == expectedLibraryPdfIdentity) {
                "上次阅读的原 PDF 版本已变化，没有打开其他文件"
            }
            if (run != null) requireConversationResult(run, control)
            original = source
            JSONObject() to null
        }
    }
    fun shareOriginalFile(readerCurrent:(()->Boolean)?=null){
        if(state.busy||state.group!=null||state.team!=null)return;val file=state.file?:return;val task=state.taskId
        var prepared:Intent?=null
        val disposition=java.util.concurrent.atomic.AtomicInteger(0) // Prepared, entered Android, or discarded.
        fun sameFile()=state.open&&state.taskId==task&&state.file===file
        fun discard(intent:Intent){runCatching{io.execute{runCatching{app.attachmentOutcomes.discardUnissuedView(intent)}}}}
        work(onSuccess={
            val intent=prepared?:return@work
            if(!sameFile())return@work
            if(readerCurrent!=null&&(!resumed||!readerCurrent())){
                state=state.copy(error="原工作或 PDF 页面已变化，没有打开系统分享。")
                return@work
            }
            if(!disposition.compareAndSet(0,1))return@work
            try{
                intent.putExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,true)
                activity.startActivity(Intent.createChooser(intent,"分享这份固定原件"))
                if(sameFile())state=state.copy(notice="已请求系统分享选择器；未取得接收应用送达或读取回执。")
            }catch(_:android.content.ActivityNotFoundException){
                intent.removeExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED)
                if(disposition.compareAndSet(1,2))discard(intent)
                if(sameFile())state=state.copy(error="系统分享选择器未启动，原件保留。")
            }catch(_:Exception){
                // Android may already have received the temporary URI. Leave only its existing expiry in force.
                if(sameFile())state=state.copy(error="无法确认系统分享选择器是否打开。原件保留，没有自动重试或确认送达。")
            }
        }){c,_->
            val intent=app.attachmentOutcomes.share(task,file.getString("id"),file.getString("sha256"),c);prepared=intent
            c.onCancel(Runnable{if(disposition.compareAndSet(0,2))discard(intent)})
            JSONObject() to null
        }
    }
    fun openExternal(reviewedWorkApk:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null){val file=state.file?:return;val group=state.group;val task=state.taskId
        if(group==null&&state.team==null&&file.optString("name").endsWith(".apk",ignoreCase=true)&&(file.optString("sourceKind")=="managed_build_output"||file.optJSONObject("source")?.optString("kind")=="managed_build_output")){
            var original:dev.pocketengine.features.attachments.OwnerFilePreviewSource?=null
            work(onSuccess={
                if(resumed)try{original?.let{dev.pocketengine.features.local.project.ProjectApkInstallActivity.open(activity,it)}}
                catch(error:Exception){state=state.copy(error=safe(error))}
            }){c,_->
                val actual=app.attachmentOutcomes.originalFile(task,file.getString("id"),file.getString("sha256"),c)
                check(reviewedWorkApk==null||reviewedWorkApk.identity==actual.identity){"原构建成果来源已变化，未打开 APK 核对"}
                original=actual;JSONObject() to null
            }
            return
        }
        check(reviewedWorkApk==null){"未核对到原固定 APK，没有交给其他应用"}
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
                            if(state.open && state.file === file && state.taskId == task && state.group == group)
                                state=state.copy(externalViewerResult="no_reader",error="系统没有找到能打开这份文件的应用。可以另存原件，自己选择保存位置。")
                        }catch(_:Exception){
                            // Android may already have handed off the URI. Keep the reader copy until its normal TTL.
                            if(state.open && state.file === file && state.taskId == task && state.group == group)
                                state=state.copy(externalViewerResult="launch_unknown",error="无法确认其他应用是否已经启动。请先查看最近使用的应用；这里的原件未改动。")
                        }
                    }else c.cancel()
                })c.cancel()
                JSONObject().put("externalViewPrepared",true) to null
            }catch(failure:Exception){c.cancel();throw failure}
        }
    }
    fun prepareExport(){
        // The Activity result launcher has one outstanding request. Never replace its original source pin.
        if(closed||!state.open||state.busy||export!=null)return
        val pin:JSONObject;val mime:String;val name:String
        if(state.team!=null){
            val actual=state.snapshot?.optJSONObject("artifact")?:return
            if(actual.optString("kind")=="file_tombstone"){state=state.copy(notice="删除修订没有文件正文；历史与原分工保留。");return}
            pin=locator().put("provider","teams");mime=actual.getString("mimeType");name=actual.getString("path").substringAfterLast('/')
        }else{
            val file=state.file?:return
            pin=locator().put("file",JSONObject(file.toString()));mime=file.optString("mimeType","application/octet-stream");name=file.optString("name","群成果")
        }
        pin.put("exportRequestId",java.util.UUID.randomUUID().toString()).put("exportPhase","waiting")
        export=pin;exportResult=null;exportAbandoned=false;exportPending=true
        // The new picker has no receipt yet. The old success is evidence about a different attempt.
        privateCopyFile(pin)?.let{file->
            val key=copySource(file)
            val prior=state.copyReceipt?.takeIf{it.source()==key&&it.result.status==OwnerCopyStatus.VERIFIED}
                ?:state.previousCopyInFile?.takeIf{it.source()==key}
            state=state.copy(copyReceipt=null,previousCopyInFile=prior,error=null)
        }
        val requestId=pin.getString("exportRequestId")
        try{activity.createOutcomeDocument(mime,name)}
        catch(_:android.content.ActivityNotFoundException){exportLaunchResult(requestId,OutcomeExportLaunch.NOT_STARTED)}
        catch(_:RuntimeException){exportLaunchResult(requestId,OutcomeExportLaunch.UNKNOWN)}
    }
    fun exportLaunchResult(requestId:String,result:OutcomeExportLaunch){
        val pin=export?:return
        if(pin.optString("exportRequestId")!=requestId||pin.optString("exportPhase","waiting")!="waiting")return
        when(result){
            OutcomeExportLaunch.NOT_STARTED->{clearExport(pin);state=state.copy(error="系统文件选择器未启动；原文件保留，可以重试导出。")}
            OutcomeExportLaunch.UNKNOWN->{pin.put("exportLaunchUnknown",true);state=state.copy(error="系统文件选择器是否启动尚未确认；请先返回原选择器完成或取消，没有重新请求导出。")}
            OutcomeExportLaunch.REQUESTED->Unit
        }
    }
    fun exportResult(requestId:String,uri:Uri?){
        val pin=export?:return
        if(pin.optString("exportRequestId")!=requestId||pin.optString("exportPhase","waiting")!="waiting")return
        pin.put("exportPhase","consumed")
        if(uri==null||exportAbandoned||closed){
            if(uri==null)recordPrivateCopy(pin,OwnerCopyResult(OwnerCopyStatus.NO_TARGET))
            clearExport(pin);return
        }
        exportResult=uri;if(resumed)finishExport()
    }
    private fun clearExport(pin:JSONObject){if(export!==pin)return;export=null;exportResult=null;exportAbandoned=false;exportPending=false;restoredCopyLocator=null}
    private fun finishPrivateCopy(pin:JSONObject,sameWork:Boolean){
        // The result may have taken the executor before the recreated Activity could read this file.
        val returnToOriginal=sameWork&&resumed&&!state.busy&&returningPrivateCopyFile(pin)!=null
        clearExport(pin)
        if(returnToOriginal)refresh() // Only the existing owner read. Never reuse the destination URI.
    }
    private fun finishExport(){
        val pin=export?:return;val uri=exportResult?:return
        exportResult=null
        if(exportAbandoned||closed){clearExport(pin);return}
        // Advance once on main before queuing IO. Late callbacks and old completions cannot consume another pin.
        try{if(pin.optString("provider")=="teams"){
            val selected=TeamWorkspaceOutcomeHost.Selection(pin.getJSONObject("team"));pin.put("exportPhase","writing");work(groupAuthority=false,onFinished={clearExport(pin)}){c,_->val host=requireTeamHost();val bytes=host.verifiedBytes(selected,c)
                try{host.checkRead(selected,c);val actual=AttachmentRepository.get(app).exportNativeSnapshot(bytes,selected.sha256(),uri,c);host.checkRead(selected,c);actual.put("outcomeNotice","已导出原成员冻结成果的真实文件；原分工和原项目保留") to null}catch(failed:Exception){throw IllegalStateException("导出未完整确认，目标可能已有部分内容；请核对所选文件和原分工",failed)}finally{java.util.Arrays.fill(bytes,0.toByte())}}
            return
        }
        val target=groupFrom(pin.optJSONObject("group"));val task=pin.optString("taskId").takeIf{it.isNotBlank()};val file=pin.getJSONObject("file")
        pin.put("exportPhase","writing")
        if(target==null){
            val entered=java.util.concurrent.atomic.AtomicBoolean(false)
            var settled:OwnerCopyResult?=null
            work(groupAuthority=false,onFinished={current->
                if(entered.get()&&state.copyReceipt?.requestId!=pin.optString("exportRequestId"))recordPrivateCopy(pin,OwnerCopyResult(OwnerCopyStatus.UNCONFIRMED))
                finishPrivateCopy(pin,current&&entered.get())
            },onSuccess={settled?.let{recordPrivateCopy(pin,it)}}){c,_->
                entered.set(true);settled=OriginalTaskOutputDelivery.write(app,app.attachmentOutcomes,task,file,uri,c)
                JSONObject() to null
            }
            return
        }
        work(groupAuthority=true,onFinished={clearExport(pin)}){c,w->val result=groupCall(w){app.exportGroupOutcome(target,uri,c)};result.put("outcomeNotice","已导出真实文件；原任务与群投递状态分别保留") to null}
        }catch(failed:Exception){if(pin.optString("exportPhase")!="writing")clearExport(pin);state=state.copy(error=if(pin.optString("exportPhase")=="writing")"导出是否开始尚未确认；请核对所选目标文件，没有自动重写。"else safe(failed))}
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
    private fun work(document:Boolean=false,replaceDocumentWithFile:Boolean=false,teamDifference:Boolean=false,groupAuthority:Boolean?=null,onFinished:((Boolean)->Unit)?=null,onSuccess:((JSONObject)->Unit)?=null,completionFailure:String?=null,call:(RunControl,String?)->Pair<JSONObject,JSONObject?>){
        wait?.cancel();observation?.cancel();observation=null;val token=++generation;val c=RunControl();wait=c
        fun finished(){onFinished?.invoke(!closed&&state.open&&generation==token)}
        val group=state.group;val window=if(groupAuthority?:(group!=null||export?.optJSONObject("group")!=null))try{ownerWindow()}catch(e:Exception){state=state.copy(error=listOfNotNull(restoredExportNotice,safe(e)).joinToString(" "),busy=false);restoredExportNotice=null;if(onFinished!=null){if(wait===c)wait=null;c.cancel();finished()};return}else null
        state=state.copy(busy=true,error=null,externalViewerResult=null,observing=false,documentOpen=document||state.documentOpen)
        try{io.execute{try{val(result,file)=call(c,window);c.check();main.post{if(!closed&&state.open&&generation==token&&wait===c&&!c.isCancelled){
            val actualFile=if(group!=null&&result.optJSONObject("artifact")!=null)result.getJSONObject("artifact").let{JSONObject(it.toString()).put("id",it.getString("artifactId")).put("size",it.getLong("bytes")).put("name","群任务成果")}else file
            state=if(teamDifference)state.copy(busy=false,teamDifference=result)else if(document)state.copy(busy=false,document=result)else state.copy(busy=false,snapshot=if(result.has("sources")||result.has("kind")||result.optString("provider")=="teams"&&result.has("artifact"))result else state.snapshot,file=actualFile?:state.file,notice=result.optString("outcomeNotice").takeIf{it.isNotBlank()},documentOpen=if(replaceDocumentWithFile)false else state.documentOpen,document=if(replaceDocumentWithFile)null else state.document,thumbnail=if(replaceDocumentWithFile)null else state.thumbnail,originalReturn=if(replaceDocumentWithFile)AttachmentOriginalReturn.DETAILS else state.originalReturn)
            if(file!=null)restoreFile=null
            restoredExportNotice?.let{state=state.copy(error=it);restoredExportNotice=null}
            onSuccess?.invoke(result)
        };val preview=!state.deviceOriginalOnly&&state.file?.optString("mimeType")?.startsWith("image/")==true&&state.thumbnail==null&&file!=null;if(wait===c)wait=null;c.cancel();if(preview&&generation==token)loadThumbnail()}}catch(e:Exception){main.post{if(!closed&&state.open&&generation==token&&wait===c){val message=completionFailure?:if(onFinished!=null)"导出未完整确认，目标可能已有部分内容；请核对所选文件，没有自动重写。"else if(c.isCancelled)"读取已停止；原文件与任务保留"else safe(e);state=state.copy(busy=false,error=listOfNotNull(restoredExportNotice,message).joinToString(" "));restoredExportNotice=null;wait=null};c.cancel()}}finally{if(onFinished!=null)main.post{finished()}}}}catch(rejected:java.util.concurrent.RejectedExecutionException){if(onFinished==null)throw rejected;state=state.copy(busy=false,error=completionFailure?:"导出未开始，原文件保留；请稍后明确重试");if(wait===c)wait=null;c.cancel();finished()}
    }
    private fun groupCall(window:String?,call:()->JSONObject):JSONObject {val actual=window?:throw SecurityException("当前原生主人窗口不可用");return app.groupOwnerWorkflows.call(actual){call()}}
    private fun restoreLibraryImport(bundle:Bundle?):AttachmentLibraryImport?=runCatching{
        val saved=bundle?.getString("attachmentLibraryImport")?.takeIf{it.length<=3500}?:return@runCatching null
        val value=JSONObject(saved);val request=value.getString("requestId");check(java.util.UUID.fromString(request).toString()==request)
        val phase=value.getString("phase")
        if(phase=="confirmed"){
            val result=value.getJSONObject("registration");val selected=result.getInt("selected");val registered=result.getInt("registered")
            val files=result.getJSONArray("files").objects()
            check(selected in 1..10&&registered in 0..selected&&files.size==registered&&files.all{
                it.optString("id").isNotBlank()&&it.optString("id").length<=240&&it.optString("sha256").matches(Regex("[0-9a-f]{64}"))
            })
            AttachmentLibraryImport(request,phase,JSONObject().put("selected",selected).put("registered",registered).put("files",JSONArray(files.map{
                JSONObject().put("id",it.getString("id")).put("sha256",it.getString("sha256"))
            })))
        }else AttachmentLibraryImport(request,if(phase in setOf("selecting","selection_unconfirmed","cancelled","no_selection","not_started","busy_other_selection"))phase else "unconfirmed")
    }.getOrNull()
    fun save(bundle:Bundle){
        bundle.remove("attachmentLibraryImport")
        if(privateLibrary())state.libraryImport?.let{bundle.putString("attachmentLibraryImport",it.toJson().toString())}
        bundle.remove("attachmentOutcomeVerifiedCopies")
        if(verifiedCopies.isNotEmpty())bundle.putStringArrayList("attachmentOutcomeVerifiedCopies",ArrayList(verifiedCopies.values.map{it.toJson().toString()}))
        pendingProcessingAction?.let{bundle.putString("attachmentPendingProcessingTask",it)};pendingDocumentAction?.let{bundle.putString("attachmentPendingDocumentTask",it)};if(state.deviceOriginalOnly||state.resultRun?.report!=null||state.focusedStandaloneDraft)bundle.remove("attachmentOutcomeLocator")else if(state.open)bundle.putString("attachmentOutcomeLocator",locator(savedNavigation=true).toString());bundle.remove("attachmentOutcomeExport");bundle.remove("attachmentOutcomeExportResult");bundle.remove("attachmentOutcomeExportAbandoned");export?.let{bundle.putString("attachmentOutcomeExport",it.toString());bundle.putBoolean("attachmentOutcomeExportAbandoned",exportAbandoned);};bundle.remove("attachmentDocumentDraft");(state.documentDraft?:restoredDocumentDraft)?.let{draft->runCatching{DocumentDraft(draft);draft.toString().takeIf{it.toByteArray(Charsets.UTF_8).size<=StructuredOfficeDraft.MAX_SOURCE_BYTES}?.let{bundle.putString("attachmentDocumentDraft",it)}}}}
    fun restore(bundle:Bundle?){
        restoreVerifiedCopies(bundle)
        bundle?.getString("attachmentPendingProcessingTask")?.takeIf{it.matches(Regex("[a-f0-9-]{36}"))}?.let{pendingProcessingAction=it;state=state.copy(pendingProcessingTask=it)};bundle?.getString("attachmentPendingDocumentTask")?.takeIf{it.matches(Regex("[a-f0-9-]{36}"))}?.let{pendingDocumentAction=it;state=state.copy(pendingDocumentTask=it)};try{bundle?.getString("attachmentOutcomeExport")?.let{val pin=JSONObject(it);val requestId=pin.optString("exportRequestId")
            if(requestId.matches(Regex("[a-f0-9-]{36}"))&&pin.optString("exportPhase","waiting")=="waiting"){
                export=pin;exportPending=true;exportAbandoned=bundle.getBoolean("attachmentOutcomeExportAbandoned");if(pin.optBoolean("exportLaunchUnknown"))restoredExportNotice="系统文件选择器是否启动尚未确认；请先返回原选择器完成或取消，没有重新请求导出。"
            }else {restoredExportNotice="原导出结果尚未确认；请核对所选目标文件，没有自动重写或重新打开选择器。";state=state.copy(error=restoredExportNotice)}};bundle?.getString("attachmentOutcomeLocator")?.let{val pin=JSONObject(it);val team=pin.optJSONObject("team")?.let{TeamWorkspaceOutcomeHost.Selection(it)};val previous=runCatching{pin.optJSONObject("teamPrevious")?.let{TeamWorkspaceOutcomeHost.Selection(it)}}.getOrNull();restoreFile=pin.optJSONObject("file");state=AttachmentOutcomeState(open=true,error=state.error,pendingDocumentTask=state.pendingDocumentTask,pendingProcessingTask=state.pendingProcessingTask,taskId=team?.toJson()?.getString("taskId")?:pin.optString("taskId").takeIf(String::isNotBlank),group=groupFrom(pin.optJSONObject("group")),team=team,teamPrevious=team?.let{matchingPrevious(it,previous)},fromWorkResult=pin.optBoolean("fromWorkResult"),resultRun=resultRunFrom(pin),
                originalReturn=if(restoreFile!=null&&team==null&&!pin.has("group"))AttachmentOriginalReturn.entries.firstOrNull{
                    it.value==pin.optString("originalReturn")
                }?:AttachmentOriginalReturn.DETAILS else AttachmentOriginalReturn.DETAILS);bundle?.getString("attachmentDocumentDraft")?.let{saved->check(team==null&&state.group==null&&saved.toByteArray(Charsets.UTF_8).size<=StructuredOfficeDraft.MAX_SOURCE_BYTES){"原文档草稿超出可恢复范围"};val draft=JSONObject(saved);DocumentDraft(draft);restoredDocumentDraft=draft}
            if(directPrivateWorkEntry()&&restoreFile!=null){
                val entry=pin.optJSONObject("workReturn")?.let{AttachmentWorkReturn.from(it)}
                check(entry==null||entry.run.taskId==state.taskId&&entry.matchesFile(restoreFile)){"原工作与文件返回身份不同"}
                // Even without a proven return pin, a restored entry must not fall back to a task-ID-only jump.
                state=state.copy(workReturn=entry,restoredWorkReturn=true)
            }
            // Pin only this restored navigation object, not a later opening of an identical file.
            restoredCopyLocator=restoreFile?.takeIf {file->export?.takeUnless{exportAbandoned}?.let{privateCopyFile(it,file)}!=null}
            if(privateLibrary())state=state.copy(libraryImport=restoreLibraryImport(bundle))
            main.post{if(!closed&&resumed&&!state.busy){if(exportResult!=null)finishExport()else refresh()}}}}catch(_:Exception){restoredDocumentDraft=null;restoredCopyLocator=null;restoreFile=null;state=AttachmentOutcomeState(open=true,pendingDocumentTask=pendingDocumentAction,pendingProcessingTask=pendingProcessingAction,error="原成果定位或文档草稿暂不可恢复，请从原任务重新打开；已有保存动作仍只核对原结果")}}
    private fun locator(savedNavigation:Boolean=false):JSONObject=JSONObject().put("taskId",state.taskId.orEmpty()).put("fromWorkResult",state.fromWorkResult).also{value->
        if(state.originalReturn!=AttachmentOriginalReturn.DETAILS)value.put("originalReturn",state.originalReturn.value)
        if(savedNavigation&&directPrivateWorkEntry())state.workReturn?.takeIf{it.matchesFile(restoreFile?:state.file)}
            ?.let{value.put("workReturn",it.toJson())}
        val result=state.resultRun?.takeIf{it.taskId==state.taskId}
        if(result!=null)value.put("resultRunId",result.runId).put("resultEpoch",result.epoch)
        state.team?.let{value.put("team",it.toJson())};state.teamPrevious?.let{value.put("teamPrevious",it.toJson())}
        (restoreFile?:state.file)?.let{file->
            val shown=state.originalFile
            val privateOriginal=state.group==null&&state.team==null&&!state.deviceOriginalOnly&&shown!=null&&
                shown.sha256==file.optString("sha256")&&shown.metadata().optString("id")==file.optString("id")
            val showOriginal=restoreFile?.optBoolean("showOriginal")==true || shown!=null&&(state.fromWorkResult||result!=null||privateOriginal)
            value.put("file",JSONObject().put("id",file.optString("id")).put("sha256",file.optString("sha256")).put("showOriginal",showOriginal))
        }
        state.group?.let{s->value.put("group",JSONObject().put("requestId",s.requestId).put("taskId",s.taskId).put("spaceId",s.spaceId).put("outputId",s.outputId).put("artifactId",s.artifactId).put("audience",s.audience).put("visibility",s.visibility).put("operation",s.sourceOperationHint).put("sourceRun",s.sourceRunHint))}
    }
    private fun resultRunFrom(pin:JSONObject):AttachmentResultRun? {
        if(!pin.has("resultRunId")&&!pin.has("resultEpoch"))return null
        val result=AttachmentResultRun(pin.getString("taskId"),pin.getString("resultRunId"),pin.getInt("resultEpoch"))
        check(result.taskId.isNotBlank()&&result.runId.isNotBlank()&&result.epoch>0){"原成果回执身份不可恢复"}
        return result
    }
    private fun groupFrom(value:JSONObject?):GroupResultAccess.Selection?=value?.let{GroupResultAccess.Selection(it.getString("requestId"),it.getString("taskId"),it.getString("spaceId"),it.getString("outputId"),it.getString("artifactId"),it.getString("audience"),it.getString("visibility"),it.optString("operation"),it.optString("sourceRun"))}
    private fun safe(error:Exception):String {val message=error.message.orEmpty();return if(message.isBlank()||message.contains("content://")||message.contains("http"))"文件或原操作暂不可用，请核对原任务；没有重新提交或重发" else message.take(300)}
}
