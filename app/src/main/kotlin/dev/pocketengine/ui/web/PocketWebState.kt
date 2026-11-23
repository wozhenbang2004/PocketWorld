package dev.pocketengine.ui.web

import dev.pocketengine.features.assistant.AssistantModule
import dev.pocketengine.ui.AttachmentMessageInput
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiChatAttachment
import dev.pocketengine.ui.UiMessage
import dev.pocketengine.ui.UiTask
import org.json.JSONArray
import org.json.JSONObject

/** Explicit read model for the trusted local Web UI. No host authority is serialized. */
internal object PocketWebState {
    private val hidden = setOf(
        "remoteinputdrafts", "hasphonedraftrows", "ordinaryturnoutcome", "nativeordinaryfirstturnfailure", "ownerretainedworkspan",
        "apikey", "password", "passphrase", "privatekey", "clientsecret", "accesstoken",
        "refreshtoken", "idtoken", "bearer", "bearertoken", "authorization", "secret",
        "token", "invitationtoken", "invitationuri", "loginchallenge", "signedurl", "localphonepauseinput",
        // The project run selection holds private file paths, range fingerprints and record revisions. It is native audit, not Web state.
        // Only the separately verified boolean is public; private reviewer-route fingerprints stay native.
        "teamreviewerroutecapture", "revisionrecipient", "projectmemoryread", "nativeprojectconventionreceipt", "nativeprojectconventiondraftseal",
        "ownerdirectviteeditorresume", "ownerpythonoutputtextpublication", "nativeoriginalmemorydraft",
        "nativeoriginalreplydocument", "originalgenerateddocument", "originalgenerateddocuments", "originalgenerateddocumentscheck",
        "nativeoriginalreplytext", "originalgeneratedtext", "originalgeneratedtextcheck"
    )
    private val contractFields = setOf("deliverycontract","deliverycontractdigest")
    private val imageFields = setOf("imagedataurl", "screenshotdataurl", "screenshotbase64")
    private val unscopedMemoryReads = setOf("memory.list", "memory.search")
    private val scopedMemoryMirrors = setOf("memoryReview", "taskMemory")

    /** Preserve public object structure, but never return credential values or hidden host objects. */
    fun publicValue(value: Any?, images: Boolean = false, depth: Int = 0, omitProjectContract: Boolean = false): Any {
        if (value == null || value === JSONObject.NULL) return JSONObject.NULL
        if (depth > 32) return JSONObject().put("unavailable", "nested_data_requires_original_source")
        return when (value) {
            is JSONObject -> JSONObject().also { out ->
                value.keys().forEach { key ->
                    val normalized = key.lowercase().filter(Char::isLetterOrDigit)
                    if (normalized !in hidden && (images || normalized !in imageFields) &&
                        (!omitProjectContract || normalized !in contractFields)) {
                        out.put(key, publicValue(value.opt(key), images, depth + 1, omitProjectContract))
                    }
                }
            }
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until value.length()) out.put(publicValue(value.opt(index), images, depth + 1, omitProjectContract))
            }
            is String, is Number, is Boolean -> value
            else -> JSONObject.NULL
        }
    }

    /** Select one already-loaded original row, not a title, a search result, or caller-supplied identity. */
    private fun ordinaryFirstQuestion(row: UiMessage, conversation: String): AssistantModule.OrdinaryFirstQuestion? {
        val id = row.id ?: return null
        val locator = row.ownerOriginalLocator ?: return null
        val submission = row.submission ?: return null
        val generation = locator.opt("generation") as? String ?: return null
        val fingerprint = locator.opt("fingerprint") as? String ?: return null
        val client = submission.opt("clientMessageId") as? String ?: return null
        val task = submission.opt("taskId") as? String ?: return null
        val run = submission.opt("runId") as? String ?: return null
        val epoch = submission.optInt("epoch", -1)
        val revision = row.sourceRevision
        if (row.role != "user" || id.isBlank() || generation.isBlank() || client.isBlank() || task.isBlank() || run.isBlank() ||
            revision < 1 || epoch < 1 || !row.textOnlyHandoffEligible || locator.opt("kind") != "owner_native_original" ||
            locator.opt("conversationId") != conversation || locator.opt("recordId") != id ||
            locator.optLong("recordVersion", -1) != revision || submission.opt("conversationId") != conversation ||
            submission.opt("messageId") != id || submission.opt("status") != "accepted" || submission.opt("resourceType") != "assistant_text" ||
            submission.opt("queueRole") != "task_goal" || submission.optLong("ownerMessageRevision", -1) != revision ||
            submission.opt("notAdmitted") == true || submission.opt("reconciliationRequired") == true) return null
        return AssistantModule.OrdinaryFirstQuestion(conversation, id, revision, generation, fingerprint, client, task, run, epoch)
    }

    fun currentOrdinaryFirstQuestion(controller: PocketWorldController): AssistantModule.OrdinaryFirstQuestion? {
        val state = controller.state
        val conversation = state.conversationId ?: return null
        if (controller.loadingFirstConversationMessages() || controller.composerDraft?.let {
                it.conversationId == conversation && it.key == "conversation:$conversation"
            } != true) return null
        val row = state.messages.firstOrNull() ?: return null
        val question = ordinaryFirstQuestion(row, conversation) ?: return null
        if (state.messages.count { it.id == question.message() } != 1) return null
        val task = state.tasks.singleOrNull { it.id == question.task() } ?: return null
        val raw = task.raw
        return question.takeIf { task.status == "failed" && raw.opt("status") == "failed" && raw.opt("id") == question.task() &&
            raw.opt("actorId") == "owner" && raw.opt("spaceId") == "private" && raw.opt("kind") == "assistant" &&
            raw.opt("conversationId") == conversation && raw.opt("runId") == question.run() &&
            raw.optInt("coordinatorEpoch", -1) == question.epoch() }
    }

    private fun ordinaryFirstTurnOutcome(read: AssistantModule.OrdinaryFirstFailure): JSONObject {
        val original = read.question()
        val outcome = JSONObject().put("format", "pocketworld.ordinary-turn-outcome.v1").put("status", "failed")
            .put("ownerMessageId", original.message()).put("ownerMessageRevision", original.revision())
            .put("taskId", original.task()).put("runId", original.run()).put("epoch", original.epoch())
        val code = read.authStatus()
        if (code == 401 || code == 403)
            outcome.put("modelFailure", JSONObject().put("purpose", "MAIN").put("kind", "HTTP").put("httpStatus", code))
        return outcome
    }

    fun attachment(file: UiChatAttachment): JSONObject = JSONObject()
        .put("id", file.id).put("sha256", file.sha256).put("name", file.name)
        .put("mimeType", file.mimeType).put("size", file.size)

    fun message(row: UiMessage): JSONObject = JSONObject()
        .put("id", row.id ?: JSONObject.NULL).put("role", row.role).put("content", row.content)
        .put("at", row.at).put("taskId", row.taskId ?: JSONObject.NULL)
        .put("sourceRevision", row.sourceRevision).put("attachmentCount", row.attachmentCount)
        .put("quoteAvailable", row.ownerOriginalLocator != null && row.content.isNotEmpty())
        .put("retainedSources", dev.pocketengine.ui.memory.finalAnswerSourceSelection(row) != null)
        .put("attachments", JSONArray().also { out -> row.attachments.forEach { out.put(attachment(it)) } })
        .put("attachmentFiles", JSONArray().also { out -> row.attachmentFiles.forEach { out.put(publicValue(it)) } })
        .put("submission", publicValue(row.submission)).put("memoryReference", publicValue(row.memoryReference))
        .also { value ->
            if (row.projectConventionRecorded && row.role=="assistant") value.put("projectConventionRecorded",true)
            row.phoneResult?.let { value.put("phoneResult", it.toJson()) }
            row.replyContext?.let { value.put("replyContext", it.toJson()) }
            row.hostGoalTaskId?.let { value.put("hostGoalTaskId",it) }
            if(row.firstHostRequestLocal)value.put("firstHostRequestLocal",true)
            row.originalHostFinal?.let { view -> value.put("originalHostFinal",JSONObject().apply{view.text?.let{ put("text",it) }}) }
            row.readerInputWork?.let { value.put("readerInputTaskId", it.taskId) }
            if (row.learningExcluded && row.role == "user") {
                value.put("automaticLearningExcluded", true)
                if (row.defaultWritesBlocked) value.put("originalDefaultWritesBlocked", true)
            }
            row.taskResult?.let { value.put("taskResult", it.toJson()) }
        }

    fun task(row: UiTask, images: Boolean = false, originalProjectContract: Boolean = false,
        originalViteEditorResume: Boolean = false): JSONObject = JSONObject()
        .put("id", row.id).put("goal", row.goal).put("status", row.status)
        .put("summary", row.summary).put("executionMode", row.executionMode).put("updatedAt", row.updatedAt)
        .put("raw", publicValue(row.raw, images, omitProjectContract = !originalProjectContract)).also { value ->
            if (originalViteEditorResume) row.raw.optJSONObject("ownerDirectViteEditorResume")?.let {
                value.getJSONObject("raw").put("ownerDirectViteEditorResume", publicValue(it))
            }
            if (!originalProjectContract && row.raw.optJSONObject("developmentContext")?.has("deliveryContract") == true)
                value.getJSONObject("raw").getJSONObject("developmentContext").put("deliveryContractReadState","not_loaded")
            if(dev.pocketengine.features.remote.RemotePhoneDrafts.hasLocalRows(row.raw))value.getJSONObject("raw").put("hasPhoneDraftRows",true)
            runCatching { dev.pocketengine.store.CloudWorkspaceJobs.receiptForTask(row.raw) }.getOrNull()?.let { value.put("hostReceipt",it) }
        }

    /** Same private work relation as ownerWorkPresentation in the web view.
     * A foreground assistant reply cannot join the home merely by completing. */
    private fun homeWork(row: UiTask): Boolean {
        val raw = row.raw
        if (raw.optString("id") != row.id || raw.optString("actorId") != "owner" || raw.optString("spaceId") != "private") return false
        if (raw.optString("executionRole") == "owner_work") return true
        val result = raw.optJSONObject("resultHandling") ?: return false
        val epoch = raw.opt("coordinatorEpoch")
        val resultEpoch = result.opt("epoch")
        return raw.opt("nativeResultHandling") == true && result.optString("format") == "pocketworld.owner-result-handling.v1" &&
            result.optString("taskId") == row.id && raw.optString("runId").isNotBlank() && result.optString("runId") == raw.optString("runId") &&
            (epoch is Int || epoch is Long) && (resultEpoch is Int || resultEpoch is Long) &&
            raw.optLong("coordinatorEpoch") > 0 && result.optLong("epoch") == raw.optLong("coordinatorEpoch")
    }

    data class ReplyDocument(val file:UiChatAttachment,val checkFirst:Boolean)

    fun snapshot(controller: PocketWorldController, contractVisible: (UiTask) -> Boolean = { false },
        editorResumeVisible: (UiTask) -> Boolean = { false },
        ordinaryFirstTurnPresentation: AssistantModule.OrdinaryFirstFailure? = null,
        originalReplyDocuments: (UiMessage) -> List<ReplyDocument> = { emptyList() }): JSONObject {
        val raw = controller.state
        // An old unscoped local feature receipt is not part of the selected memory workspace.
        // Strip it before serializing, not from the owner's original native state.
        val state = if (unscopedMemoryReads.any { it in raw.moduleResults || it in raw.moduleResultInputs || it in raw.moduleErrors || it in raw.moduleBusy })
            raw.copy(moduleResults = raw.moduleResults - unscopedMemoryReads, moduleResultInputs = raw.moduleResultInputs - unscopedMemoryReads,
                moduleErrors = raw.moduleErrors - unscopedMemoryReads, moduleBusy = raw.moduleBusy - unscopedMemoryReads)
            else raw
        // Both lists expose the same task fields. Project each task once per
        // snapshot, without retaining mutable source JSON between publications.
        val tasks = JSONArray()
        val ownerWork = JSONArray()
        val livePrivatePhones = JSONArray()
        state.tasks.forEach { row ->
            val chosen = contractVisible(row)
            val resume = editorResumeVisible(row)
            val projected = task(row, originalProjectContract = chosen, originalViteEditorResume = resume)
            tasks.put(projected)
            controller.retainedPrivatePhoneSurface(row)?.let { livePrivatePhones.put(it) }
            if (homeWork(row)) {
                ownerWork.put(if (chosen || resume) task(row) else projected)
            }
        }
        val data = JSONObject()
            .put("personalProfile", controller.webPersonalProfileSummary() ?: JSONObject.NULL)
            .put("quickPanelVoice", publicValue(controller.quickPanelVoice))
            .put("taskAnnouncements", publicValue(controller.taskAnnouncementState))
            .put("taskAnnouncementSaving", controller.taskAnnouncementSaving)
            .put("ownerExecutionPolicy", publicValue(controller.ownerExecutionPolicy))
            .put("ownerExecutionSaving", controller.ownerExecutionSaving)
            .put("taskScreenshotPolicy", publicValue(controller.taskScreenshotPolicy))
            .put("taskScreenshotSaving", controller.taskScreenshotSaving)
            .put("memoryRefreshRevision", state.memoryRefreshRevision)
            .put("traceSourceRevision", controller.conversationSourceRevision)
            .put("deviceWorkspaceRevision", controller.deviceWorkspaceRevision)
            .put("modelName", state.modelName).put("baseUrl", state.baseUrl).put("hasApiKey", state.hasApiKey)
            .put("screenshots", state.screenshots).put("localCaptureAllowed", state.localCaptureAllowed)
            .put("localOcrAllowed", state.localOcrAllowed).put("supportsImageInput", state.supportsImageInput)
            .put("modelProvider", state.modelProvider).put("modelRoute", state.modelRoute)
            .put("modelCatalogEnabled", state.modelCatalogEnabled).put("maxOutputTokens", state.maxOutputTokens)
            .put("cleartextConsent", state.cleartextConsent).put("accessibilityEnabled", state.accessibilityEnabled)
            .put("notificationsEnabled", state.notificationsEnabled).put("activeTaskId", state.activeTaskId ?: JSONObject.NULL)
            .put("activePhoneTaskIds", JSONArray(state.activePhoneTaskIds)).put("livePrivatePhones", livePrivatePhones)
            .put("maximumActiveBackgroundPhoneRuns", state.maximumActiveBackgroundPhoneRuns)
            .put("foregroundTaskId", state.foregroundTaskId ?: JSONObject.NULL)
            .put("tasks", tasks)
            .put("ownerWork", ownerWork)
            .put("taskListNextCursor", controller.taskListNextCursor)
            .put("conversations", JSONArray().also { out -> state.conversations.forEach { row ->
                val original = controller.conversationOrganizationItem(row)
                out.put(JSONObject().put("id", row.id).put("title", row.title).put("updatedAt", row.updatedAt)
                    .put("pinOrder", original.pinOrder).put("organizationRevision", original.organizationRevision))
            } })
            .put("conversationId", state.conversationId ?: JSONObject.NULL)
            .put("messages", JSONArray().also { out -> state.messages.forEach { row ->
                val visible = message(row)
                ordinaryFirstTurnPresentation?.takeIf { read ->
                    val question = read.question()
                    state.conversationId == question.conversation() && row.id == question.message() &&
                        ordinaryFirstQuestion(row, question.conversation()) == question
                }?.let { visible.optJSONObject("submission")?.put("ordinaryTurnOutcome", ordinaryFirstTurnOutcome(it)) }
                if (row.attachmentFilesSourceRevision == controller.messageAttachmentSourceRevision) {
                    if(row.attachments.any { AttachmentMessageInput.PDF.supports(it.mimeType,it.name) })
                        visible.put("localMessagePdfSourceCurrent", true)
                    if(row.attachmentCount==1 && row.attachments.singleOrNull()?.let {
                            AttachmentMessageInput.WORD_OR_TEXT.supports(it.mimeType,it.name)
                        }==true) visible.put("localMessageInputDocumentSourceCurrent", true)
                }
                val originals=originalReplyDocuments(row)
                if(originals.size==1){
                    val original=originals.single()
                    if(original.file.mimeType=="text/plain")
                        visible.put("originalGeneratedTextCheck",JSONObject().put("id",original.file.id).put("sha256",original.file.sha256))
                    else if(original.checkFirst) visible.put("originalGeneratedDocumentCheck",JSONObject().put("id",original.file.id).put("sha256",original.file.sha256))
                    else visible.put("originalGeneratedDocument",attachment(original.file))
                }else if(originals.size==2&&originals[0].checkFirst==originals[1].checkFirst){
                    val checkFirst=originals[0].checkFirst
                    val files=JSONArray()
                    originals.forEach { original ->
                        val file=original.file
                        files.put(if(checkFirst) JSONObject().put("id",file.id).put("sha256",file.sha256).put("mimeType",file.mimeType)
                            else attachment(file))
                    }
                    visible.put(if(checkFirst) "originalGeneratedDocumentsCheck" else "originalGeneratedDocuments",files)
                }
                out.put(visible)
            } })
            .put("loadingFirstConversationMessages", controller.loadingFirstConversationMessages())
            .put("chatBusy", state.chatBusy).put("chatSubmitting", state.chatSubmitting)
            .put("chatActiveTurn", publicValue(state.chatActiveTurn)).put("chatSendMode", state.chatSendMode)
            .put("foregroundTurn", publicValue(state.foregroundTurn))
            .put("originalTaskNotice", publicValue(controller.originalTaskNotice))
            .put("originalPhoneNotification",publicValue(controller.originalPhoneNotification.takeIf {controller.webPrivateOwnerVisible()}))
            .put("chatReceipts", JSONObject().also { out -> state.chatReceipts.forEach { (key, value) -> out.put(key, publicValue(value, omitProjectContract = true)) } })
            .put("selectedChatAttachments", JSONArray().also { out -> state.selectedChatAttachments.forEach { out.put(attachment(it)) } })
            .put("chatAttachmentPickerOpen", state.chatAttachmentPickerOpen)
            .put("chatAttachmentPickerRequest", controller.chatAttachmentPickerRequest())
            .put("chatAttachmentModel", publicValue(state.chatAttachmentModel))
            .put("chatAttachmentPagingDenied", controller.webAttachmentPagingDenied())
            .put("attachmentImporting", state.attachmentImporting).put("phoneSubmitting", state.phoneSubmitting)
            .put("phoneDraftAcceptedRevision", controller.phoneDraftAcceptedRevision)
            .put("phoneDraftAcceptedText", controller.phoneDraftAcceptedText)
            .put("listening", state.listening).put("speaking", state.speaking).put("speechAvailable", state.speechAvailable)
            .put("voiceInput", publicValue(state.voiceInput)).put("systemSpeech", publicValue(state.systemSpeech))
            .put("projects", JSONArray().also { out -> state.projects.forEach { out.put(JSONObject().put("id", it.id).put("name", it.name)) } })
            .put("projectId", state.projectId ?: JSONObject.NULL)
            .put("files", JSONArray().also { out -> state.files.forEach { out.put(JSONObject().put("path", it.path).put("version", it.version)) } })
            .put("filePath", state.filePath ?: JSONObject.NULL).put("fileContent", state.fileContent)
            .put("fileObservation", state.fileObservation?.takeIf { it.matches(state.projectId, state.filePath) && controller.webPrivateOwnerVisible() }?.json() ?: JSONObject.NULL)
            .put("revisionObservation", state.revisionObservation?.takeIf { it.matches(state.projectId, state.filePath) && it.version == state.revisionNumber && controller.webPrivateOwnerVisible() }?.json() ?: JSONObject.NULL)
            .put("heldFormalDraft", state.heldFormalDraft?.takeIf { it.project == state.projectId && it.path == state.filePath && controller.webPrivateOwnerVisible() }?.let {
                JSONObject().put("projectId", it.project).put("path", it.path).put("version", it.version).put("text", it.text)
            } ?: JSONObject.NULL)
            .put("fileBaseline", state.fileBaseline).put("fileVersion", state.fileVersion)
            .put("fileLoading", state.fileLoading).put("fileSaving", state.fileSaving).put("fileReady", state.fileReady)
            .put("fileError", state.fileError ?: JSONObject.NULL).put("lastSavedVersion", state.lastSavedVersion ?: JSONObject.NULL)
            .put("fileHistory", JSONArray().also { out -> state.fileHistory.forEach { out.put(JSONObject()
                .put("version", it.version).put("at", it.at).put("characters", it.characters).put("deleted", it.deleted)) } })
            .put("revisionContent", state.revisionContent ?: JSONObject.NULL).put("revisionNumber", state.revisionNumber ?: JSONObject.NULL)
            .put("revisionLoading", state.revisionLoading).put("requestedProjectId", state.requestedProjectId ?: JSONObject.NULL)
            .put("requestedProjectRevision", state.requestedProjectRevision)
            .put("features", publicValue(state.features))
            // Only the current checked memory window gets its result. Native audit mirrors may include
            // full source bodies, even when the answer/history reader returned only one bounded page.
            .put("ownerSnapshots", JSONObject().also { out -> state.ownerSnapshots.forEach { (key, value) -> if (key !in scopedMemoryMirrors) out.put(key, publicValue(value, omitProjectContract = true)) } })
            .put("ownerResponses", JSONObject().also { out -> state.ownerResponses.forEach { (domain, responses) ->
                if (domain !in scopedMemoryMirrors) out.put(domain, JSONObject().also { result -> responses.forEach { (key, value) -> result.put(key, publicValue(value, omitProjectContract = true)) } })
            } })
            .put("ownerPending", JSONArray(state.ownerPending.toList())).put("ownerErrors", JSONObject(state.ownerErrors))
            .put("ownerSource", JSONObject.NULL)
            .put("moduleResults", JSONObject().also { out -> state.moduleResults.forEach { (key, value) -> out.put(key, publicValue(value, omitProjectContract = true)) } })
            .put("moduleResultInputs", JSONObject().also { out -> state.moduleResultInputs.forEach { (key, value) -> out.put(key, publicValue(value, omitProjectContract = true)) } })
            .put("moduleBusy", JSONArray(state.moduleBusy.toList())).put("moduleErrors", JSONObject(state.moduleErrors))
            .put("taskActionErrors", JSONObject(state.taskActionErrors)).put("backgroundSetupOpen", state.backgroundSetupOpen)
            .put("requestedTaskId", state.requestedTaskId ?: JSONObject.NULL).put("requestedTaskRevision", state.requestedTaskRevision)
            .put("requestedOriginalReportNotice", publicValue(controller.requestedOriginalReportNotice()))
            .put("requestedConnectionSection", state.requestedConnectionSection ?: JSONObject.NULL)
            .put("requestedConnectionRevision", state.requestedConnectionRevision)
            .put("requestedCommitmentId", state.requestedCommitmentId ?: JSONObject.NULL)
            .put("requestedCommitmentRevision", state.requestedCommitmentRevision)
            .put("requestedFollowUpClaimId", state.requestedFollowUpClaimId ?: JSONObject.NULL)
            .put("requestedExecutionPlanId", state.requestedExecutionPlanId ?: JSONObject.NULL)
            .put("requestedExecutionPlanRevision", state.requestedExecutionPlanRevision)
            .put("projectPackageExport", publicValue(controller.projectPackages.exportSnapshot()))
            .put("teamRunId", state.teamRunId ?: JSONObject.NULL)
            .put("teamRuns", JSONObject().also { out -> state.teamRuns.forEach { (key, value) -> out.put(key, publicValue(value)) } })
            .put("modelTest", state.modelTest ?: JSONObject.NULL).put("modelTestTaskId", state.modelTestTaskId ?: JSONObject.NULL)
            .put("modelSaving", state.modelSaving).put("modelTesting", state.modelTesting).put("modelSavedRevision", state.modelSavedRevision)
            .put("error", state.error ?: JSONObject.NULL).put("notice", state.notice ?: JSONObject.NULL)
            .put("newTaskScopeMinutes", controller.newTaskScopeMinutes)
            .put("newTaskToolBudget", controller.displayNewTaskToolBudget).put("newTaskModelBudget", controller.displayNewTaskModelBudget)
        data.put("phoneReadiness", JSONObject().put("checkedAtUptimeMs", state.phoneReadiness.checkedAtUptimeMs)
            .put("mainStartPreparation", state.phoneReadiness.mainStartPreparation.name.lowercase())
            .put("backgroundStartPreparation", state.phoneReadiness.backgroundStartPreparation.name.lowercase())
            .put("groups", JSONArray().also { groups -> state.phoneReadiness.groups.forEach { group ->
                groups.put(JSONObject().put("id", group.id).put("title", group.title).put("state", group.state.name.lowercase())
                    .put("checks", JSONArray().also { checks -> group.checks.forEach { check -> checks.put(JSONObject()
                        .put("id", check.id).put("state", check.state.name.lowercase()).put("code", check.code)
                        .put("action", check.action).put("message", check.message)) } }))
            } }))
        data.put("modelDrafts", JSONObject().also { drafts -> state.modelDrafts.forEach { (id, draft) ->
            drafts.put(id, JSONObject().put("taskId", draft.taskId).put("runId", draft.runId).put("epoch", draft.epoch)
                .put("requestId", draft.requestId).put("generation", draft.generation).put("sequence", draft.sequence)
                .put("provider", draft.provider).put("text", draft.text).put("turnEnded", draft.turnEnded)
                .put("failed", draft.failed).put("truncated", draft.truncated))
        } })
        data.put("conversationReader", controller.conversationReaderForWeb()?.let { page -> JSONObject()
            .put("window", page.window).put("conversation", page.conversation).put("title", page.title)
            .put("messages", JSONArray().also { out -> page.messages.forEach { row ->
                if (page.reminderSource != null) out.put(JSONObject().put("id", row.id).put("sourceRevision", row.sourceRevision)
                    .put("role", row.role).put("content", row.content))
                else {
                    val view = message(row)
                    if (page.searchAnchor == null && !page.loading && page.error == null && !page.limited) page.quoteTrail.ranges(row.id)?.let { view.put("registeredQuotes", it) }
                    out.put(view)
                }
            } })
            .put("cursor", page.cursor).put("hasMore", page.hasMore).put("loading", page.loading)
            .put("continueTargetReady", controller.readerContinueTargetReady(page))
            .put("error", page.error ?: JSONObject.NULL).put("notice", page.notice ?: JSONObject.NULL)
            .put("limited", page.limited).put("index", page.index).put("offset", page.offset).put("anchor", page.anchor)
            .put("originalReportMessageId",page.reportAnchor?.message ?: JSONObject.NULL)
            .also { out -> page.reminderSource?.let { out.put("reminderSource", JSONObject(it.marker.toString())) } }
            .also { out -> if(page.detachedHistoryEpoch!=null)out.put("detachedHistory",true)
                page.detachedHistoryResumeRevision?.let { out.put("detachedHistoryResumeRevision",it) } }
            .also { out -> page.searchAnchor?.let { search -> out.put("searchAnchor",JSONObject()
                .put("requestId",search.request).put("messageId",search.message).put("revision",search.revision)) } }
            .also { result -> page.workRequestAnchor?.let { request ->
                val view=JSONObject().put("taskId",request.task).put("runId",request.run).put("epoch",request.epoch)
                    .put("messageId",request.message).put("revision",request.revision)
                if (page.initialized && !page.loading && page.error==null && !page.limited && page.messages.singleOrNull()?.let {
                        it.role=="user" && it.id==request.message && it.sourceRevision==request.revision
                    }==true) {
                    if (request.attachmentsUnavailable) view.put("attachmentsUnavailable",true)
                    val namesCurrent=request.attachmentSourceRevision==controller.messageAttachmentSourceRevision
                    if (request.attachments.isNotEmpty()) view.put("attachedAtRequest",JSONArray().also { rows ->
                        request.attachments.forEach { file -> rows.put(JSONObject().put("mimeType",file.mimeType)
                            .put("size",file.size).putOpt("registeredName",file.registeredName.takeIf { namesCurrent })) }
                    })
                }
                result.put("workRequest",view)
            } }
        } ?: JSONObject.NULL)
        data.put("queuedInputsPage", controller.queuedInputsPage?.let { page -> JSONObject()
            .put("window", page.window).put("task", page.task).put("conversation", page.conversation)
            .put("viewEpoch", page.viewEpoch).put("items", publicValue(JSONArray(page.items)))
            .put("reading", page.reading).put("readingMessage", page.readingMessage ?: JSONObject.NULL)
            .put("saving", page.saving).put("error", page.error ?: JSONObject.NULL)
            .put("notice", page.notice ?: JSONObject.NULL).put("taskStatus", page.taskStatus ?: JSONObject.NULL)
            .put("current", controller.queuedInputWindowCurrent)
            .put("editor", page.editor?.let { edit -> JSONObject().put("message", edit.message)
                .put("queueRevision", edit.queueRevision).put("messageRevision", edit.messageRevision)
                .put("baseline", edit.baseline).put("originalMode", edit.originalMode).put("text", edit.text)
                .put("mode", edit.mode).put("attachmentCount", edit.attachmentCount)
                .put("referenceCount", edit.referenceCount).put("withdrawn", edit.withdrawn) } ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        data.put("projectPreview", controller.projectPreview?.let { preview ->
            publicValue(preview.metadata()).let { JSONObject().put("kind", "bundle").put("metadata", it).put("taskId", controller.projectPreviewTaskId) }
        } ?: JSONObject.NULL)
        data.put("projectLivePreview", controller.projectLivePreview?.let { preview ->
            // URLs are not delegated to the bridge-bearing document; only the isolated host uses them.
            val metadata = preview.metadata(); metadata.remove("baseUrl"); metadata.remove("entryUrl")
            JSONObject().put("kind", "live").put("metadata", publicValue(metadata)).put("taskId", controller.projectLivePreviewTaskId)
        } ?: JSONObject.NULL)
        data.put("projectLivePreviewChecking", controller.projectLivePreviewChecking)
        val draft = controller.composerDraft
        val composer = JSONObject().put("ready", draft != null).put("key", draft?.key ?: "")
            .put("epoch", controller.composerViewEpoch).put("revision", draft?.revision ?: 0L)
            .put("text", draft?.text ?: "").put("mode", draft?.mode ?: state.chatSendMode)
            .put("canOfferPrimaryDisplayExample", draft?.let(controller::canOfferPrimaryDisplayExample) == true)
            .put("primaryDisplayGate",draft?.takeIf { controller.webPrivateOwnerVisible() }?.let(controller::primaryDisplayDraftGate) ?: JSONObject.NULL)
            .put("conversationId", draft?.conversationId ?: "").put("saving", controller.composerSaving)
            .put("ordinaryNewAttachmentDraft", draft?.let(controller::ordinaryNewAttachmentDraft) == true)
            .put("firstMessageMemory", draft?.let { controller.firstMessageMemoryState(it.key) } ?: JSONObject.NULL)
            .put("oneMessageLearning", draft?.let { controller.oneMessageLearningState(it.key) } ?: JSONObject.NULL)
            .put("scrollIndex", draft?.scrollIndex ?: 0).put("scrollOffset", draft?.scrollOffset ?: 0)
            .put("anchorMessage", draft?.anchorMessage ?: "").put("observedMessage", draft?.observedMessage ?: "")
            .put("newReplies", draft?.newReplies ?: false).put("sourceRefs", publicValue(draft?.sourceRefs()))
            .put("independentReportDraftAvailable", controller.independentReportDraftAvailable())
            .put("independentReportDiscardEmpty", controller.canDiscardIndependentReportDraft())
            .put("independentReportSourceIndex", controller.independentReportSourceIndex())
            .put("previewRefs", publicValue(draft?.previewRefs() ?: JSONArray())).put("previewRefsAvailable", controller.composerPreviewRefsAvailable())
            .put("objectRefs", publicValue(draft?.objectRefs() ?: JSONArray()))
            .put("voiceText", draft?.voiceText ?: "").put("voiceInputId", draft?.voiceInputId ?: "")
            .put("pending", JSONArray().also { out -> controller.composerPending.forEach { pending ->
                out.put(JSONObject().put("clientMessageId", pending.clientId).put("draftKey", pending.draftKey)
                    .put("draftRevision", pending.draftRevision).put("conversationId", pending.conversationId).put("mode", pending.mode)
                    .put("originalTarget", pending.originalTarget())
                    .put("status", state.chatReceipts[pending.clientId]?.opt("status") ?: JSONObject.NULL)
                    .put("receipt", publicValue(state.chatReceipts[pending.clientId]))
                    .put("canRetryLocallyUnsent", controller.canRetryLocallyUnsent(pending.clientId))
                    .put("canReleasePreparedFirstChat", controller.canReleasePreparedFirstChat(pending.clientId))
                    .put("canReadSavedFirstChatSource", controller.canReadSavedFirstChatSource(pending.clientId)))
            } })
        return JSONObject().put("state", data).put("composer", composer)
    }
}
