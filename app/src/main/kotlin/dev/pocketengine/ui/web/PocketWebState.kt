package dev.pocketengine.ui.web

import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiChatAttachment
import dev.pocketengine.ui.UiMessage
import dev.pocketengine.ui.UiTask
import org.json.JSONArray
import org.json.JSONObject

/** Explicit read model for the trusted local Web UI. No host authority is serialized. */
internal object PocketWebState {
    private val hidden = setOf(
        "apikey", "password", "passphrase", "privatekey", "clientsecret", "accesstoken",
        "refreshtoken", "idtoken", "bearer", "bearertoken", "authorization", "secret",
        "token", "invitationtoken", "invitationuri", "loginchallenge", "signedurl"
    )
    private val imageFields = setOf("imagedataurl", "screenshotdataurl", "screenshotbase64")

    /** Preserve public object structure, but never return credential values or hidden host objects. */
    fun publicValue(value: Any?, images: Boolean = false, depth: Int = 0): Any {
        if (value == null || value === JSONObject.NULL) return JSONObject.NULL
        if (depth > 32) return JSONObject().put("unavailable", "nested_data_requires_original_source")
        return when (value) {
            is JSONObject -> JSONObject().also { out ->
                value.keys().forEach { key ->
                    val normalized = key.lowercase().filter(Char::isLetterOrDigit)
                    if (normalized !in hidden && (images || normalized !in imageFields)) {
                        out.put(key, publicValue(value.opt(key), images, depth + 1))
                    }
                }
            }
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until value.length()) out.put(publicValue(value.opt(index), images, depth + 1))
            }
            is String, is Number, is Boolean -> value
            else -> JSONObject.NULL
        }
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
            row.phoneResult?.let { value.put("phoneResult", it.toJson()) }
            row.replyContext?.let { value.put("replyContext", it.toJson()) }
            row.taskResult?.let { value.put("taskResult", it.toJson()) }
        }

    fun task(row: UiTask, images: Boolean = false): JSONObject = JSONObject()
        .put("id", row.id).put("goal", row.goal).put("status", row.status)
        .put("summary", row.summary).put("executionMode", row.executionMode).put("updatedAt", row.updatedAt)
        .put("raw", publicValue(row.raw, images))

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

    fun snapshot(controller: PocketWorldController): JSONObject {
        val state = controller.state
        // Both lists expose the same task fields. Project each task once per
        // snapshot, without retaining mutable source JSON between publications.
        val tasks = JSONArray()
        val ownerWork = JSONArray()
        state.tasks.forEach { row ->
            val projected = task(row)
            tasks.put(projected)
            if (homeWork(row)) {
                ownerWork.put(projected)
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
            .put("activePhoneTaskIds", JSONArray(state.activePhoneTaskIds))
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
            .put("messages", JSONArray().also { out -> state.messages.forEach { out.put(message(it)) } })
            .put("chatBusy", state.chatBusy).put("chatSubmitting", state.chatSubmitting)
            .put("chatActiveTurn", publicValue(state.chatActiveTurn)).put("chatSendMode", state.chatSendMode)
            .put("foregroundTurn", publicValue(state.foregroundTurn))
            .put("originalTaskNotice", publicValue(controller.originalTaskNotice))
            .put("chatReceipts", JSONObject().also { out -> state.chatReceipts.forEach { (key, value) -> out.put(key, publicValue(value)) } })
            .put("selectedChatAttachments", JSONArray().also { out -> state.selectedChatAttachments.forEach { out.put(attachment(it)) } })
            .put("chatAttachmentPickerOpen", state.chatAttachmentPickerOpen)
            .put("chatAttachmentModel", publicValue(state.chatAttachmentModel))
            .put("attachmentImporting", state.attachmentImporting).put("phoneSubmitting", state.phoneSubmitting)
            .put("attachmentOpens", JSONObject().also { out -> state.attachmentOpens.forEach { (key, value) -> out.put(key, JSONObject().put("checking", value.checking).put("requested", value.requested).put("error", value.error ?: JSONObject.NULL)) } })
            .put("phoneDraftAcceptedRevision", controller.phoneDraftAcceptedRevision)
            .put("phoneDraftAcceptedText", controller.phoneDraftAcceptedText)
            .put("listening", state.listening).put("speaking", state.speaking).put("speechAvailable", state.speechAvailable)
            .put("voiceInput", publicValue(state.voiceInput)).put("systemSpeech", publicValue(state.systemSpeech))
            .put("projects", JSONArray().also { out -> state.projects.forEach { out.put(JSONObject().put("id", it.id).put("name", it.name)) } })
            .put("projectId", state.projectId ?: JSONObject.NULL)
            .put("files", JSONArray().also { out -> state.files.forEach { out.put(JSONObject().put("path", it.path).put("version", it.version)) } })
            .put("filePath", state.filePath ?: JSONObject.NULL).put("fileContent", state.fileContent)
            .put("fileBaseline", state.fileBaseline).put("fileVersion", state.fileVersion)
            .put("fileLoading", state.fileLoading).put("fileSaving", state.fileSaving).put("fileReady", state.fileReady)
            .put("fileError", state.fileError ?: JSONObject.NULL).put("lastSavedVersion", state.lastSavedVersion ?: JSONObject.NULL)
            .put("fileHistory", JSONArray().also { out -> state.fileHistory.forEach { out.put(JSONObject()
                .put("version", it.version).put("at", it.at).put("characters", it.characters).put("deleted", it.deleted)) } })
            .put("revisionContent", state.revisionContent ?: JSONObject.NULL).put("revisionNumber", state.revisionNumber ?: JSONObject.NULL)
            .put("revisionLoading", state.revisionLoading).put("requestedProjectId", state.requestedProjectId ?: JSONObject.NULL)
            .put("requestedProjectRevision", state.requestedProjectRevision)
            .put("features", publicValue(state.features))
            .put("ownerSnapshots", JSONObject().also { out -> state.ownerSnapshots.forEach { (key, value) -> out.put(key, publicValue(value)) } })
            .put("ownerResponses", JSONObject().also { out -> state.ownerResponses.forEach { (domain, responses) ->
                out.put(domain, JSONObject().also { result -> responses.forEach { (key, value) -> result.put(key, publicValue(value)) } })
            } })
            .put("ownerPending", JSONArray(state.ownerPending.toList())).put("ownerErrors", JSONObject(state.ownerErrors))
            .put("ownerSource", publicValue(state.ownerSource))
            .put("moduleResults", JSONObject().also { out -> state.moduleResults.forEach { (key, value) -> out.put(key, publicValue(value)) } })
            .put("moduleResultInputs", JSONObject().also { out -> state.moduleResultInputs.forEach { (key, value) -> out.put(key, publicValue(value)) } })
            .put("moduleBusy", JSONArray(state.moduleBusy.toList())).put("moduleErrors", JSONObject(state.moduleErrors))
            .put("taskActionErrors", JSONObject(state.taskActionErrors)).put("backgroundSetupOpen", state.backgroundSetupOpen)
            .put("requestedTaskId", state.requestedTaskId ?: JSONObject.NULL).put("requestedTaskRevision", state.requestedTaskRevision)
            .put("requestedConnectionSection", state.requestedConnectionSection ?: JSONObject.NULL)
            .put("requestedConnectionRevision", state.requestedConnectionRevision)
            .put("requestedCommitmentId", state.requestedCommitmentId ?: JSONObject.NULL)
            .put("requestedCommitmentRevision", state.requestedCommitmentRevision)
            .put("requestedFollowUpClaimId", state.requestedFollowUpClaimId ?: JSONObject.NULL)
            .put("teamRunId", state.teamRunId ?: JSONObject.NULL)
            .put("teamRuns", JSONObject().also { out -> state.teamRuns.forEach { (key, value) -> out.put(key, publicValue(value)) } })
            .put("modelTest", state.modelTest ?: JSONObject.NULL).put("modelTestTaskId", state.modelTestTaskId ?: JSONObject.NULL)
            .put("modelSaving", state.modelSaving).put("modelTesting", state.modelTesting).put("modelSavedRevision", state.modelSavedRevision)
            .put("error", state.error ?: JSONObject.NULL).put("notice", state.notice ?: JSONObject.NULL)
            .put("newTaskScopeMinutes", controller.newTaskScopeMinutes)
            .put("newTaskToolBudget", controller.displayNewTaskToolBudget).put("newTaskModelBudget", controller.displayNewTaskModelBudget)
        data.put("phoneReadiness", JSONObject().put("checkedAtUptimeMs", state.phoneReadiness.checkedAtUptimeMs)
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
        data.put("conversationReader", controller.conversationReader?.let { page -> JSONObject()
            .put("window", page.window).put("conversation", page.conversation).put("title", page.title)
            .put("messages", JSONArray().also { out -> page.messages.forEach { out.put(message(it)) } })
            .put("cursor", page.cursor).put("hasMore", page.hasMore).put("loading", page.loading)
            .put("error", page.error ?: JSONObject.NULL).put("notice", page.notice ?: JSONObject.NULL)
            .put("limited", page.limited).put("index", page.index).put("offset", page.offset).put("anchor", page.anchor)
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
            .put("conversationId", draft?.conversationId ?: "").put("saving", controller.composerSaving)
            .put("scrollIndex", draft?.scrollIndex ?: 0).put("scrollOffset", draft?.scrollOffset ?: 0)
            .put("anchorMessage", draft?.anchorMessage ?: "").put("observedMessage", draft?.observedMessage ?: "")
            .put("newReplies", draft?.newReplies ?: false).put("sourceRefs", publicValue(draft?.sourceRefs()))
            .put("previewRefs", publicValue(draft?.previewRefs() ?: JSONArray())).put("previewRefsAvailable", controller.composerPreviewRefsAvailable())
            .put("voiceText", draft?.voiceText ?: "").put("voiceInputId", draft?.voiceInputId ?: "")
            .put("pending", JSONArray().also { out -> controller.composerPending.forEach { pending ->
                out.put(JSONObject().put("clientMessageId", pending.clientId).put("draftKey", pending.draftKey)
                    .put("draftRevision", pending.draftRevision).put("conversationId", pending.conversationId).put("mode", pending.mode)
                    .put("originalTarget", pending.originalTarget())
                    .put("status", state.chatReceipts[pending.clientId]?.opt("status") ?: JSONObject.NULL)
                    .put("receipt", publicValue(state.chatReceipts[pending.clientId]))
                    .put("canRetryLocallyUnsent", controller.canRetryLocallyUnsent(pending.clientId)))
            } })
        return JSONObject().put("state", data).put("composer", composer)
    }
}
