package dev.pocketengine.ui

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RuntimeDiagnostics
import java.util.concurrent.Executors
import dev.pocketengine.media.MediaController
import dev.pocketengine.features.attachments.OwnerAttachmentImport

/** Compatibility Activity identity; all business UI is rendered by the bundled library Web interface. */
class ComposeActivity : ComponentActivity() {
    private lateinit var controller: PocketWorldController
    private lateinit var webHost: dev.pocketengine.ui.web.PocketWebHost
    private var webHostIsBusiness = false
    private var businessMounted = false
    private var businessGeneration = -1L
    private var crossedBootstrap = false
    private var ignoredBusinessResult = false
    private var startupSaved: Bundle? = null
    // Only a new content selection received by this live Activity may wait for startup.
    // Saved picker, voice and continue operations never enter this slot.
    private var freshBootstrapContent: Intent? = null
    private var bootstrapPresentation: org.json.JSONObject? = null
    private val pickerOwners = mutableMapOf<String, Long>()
    private val functionalPackDocumentSlot = lazy { FunctionalPackDocumentPicker(activityResultRegistry, contentResolver, ::businessCurrent) { businessGeneration } }
    internal val functionalPackDocuments: FunctionalPackDocumentPicker get() = functionalPackDocumentSlot.value
    private fun businessCurrent(): Boolean = businessMounted && ::controller.isInitialized &&
        !isFinishing && !isDestroyed && (application as EngineApplication).businessReady() &&
        runCatching { (application as EngineApplication).bootstrapState().optLong("generation", -2L) == businessGeneration }.getOrDefault(false)
    private fun armBusinessPicker(key: String) { check(businessCurrent()); pickerOwners[key] = businessGeneration }
    private fun acceptBusinessResult(key: String? = null): Boolean {
        val owner = key?.let { pickerOwners.remove(it) }
        return (businessCurrent() && (key == null || !crossedBootstrap || owner == businessGeneration)).also { if (!it) ignoredBusinessResult = true }
    }
    internal lateinit var media: MediaController
        private set
    private var legacyImportTarget: OwnerAttachmentImport.Target? = null
    private var exportPath: String? = null
    private var mcpPickRequestId: String? = null
    private val mcpPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (acceptBusinessResult("mcp")) controller.completeMcpConfig(mcpPickRequestId, uri)
        mcpPickRequestId = null
    }
    // Preserve the old static registration slot. An old result cannot consume a new nonce's target.
    @Suppress("unused")
    private val legacyAttachmentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val original = legacyImportTarget; legacyImportTarget = null
        if (!acceptBusinessResult("legacyAttachment")) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) controller.importAttachments(Intent(result.data!!), original)
        else controller.attachmentImportSelectionEnded(result.resultCode == Activity.RESULT_CANCELED)
    }
    private val attachmentPicker = AttachmentImportPicker(activityResultRegistry, { files, original -> if (acceptBusinessResult("attachment")) controller.importAttachments(files, original) }, { cancelled -> if (acceptBusinessResult("attachment")) controller.attachmentImportSelectionEnded(cancelled) })
    // Reserve the former static registration position so other saved picker keys do not shift.
    // A legacy result has no request nonce and cannot authorize any current export.
    @Suppress("unused")
    private val legacyOutcomeExportPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val outcomeExportPicker = AttachmentExportPicker(activityResultRegistry){requestId,uri->if (acceptBusinessResult("outcome:$requestId")) controller.attachmentOutcomes.exportResult(requestId,uri)}
    private fun restoreOutcomeDocument(requestId:String){
        if (!businessCurrent()) return
        val flow=controller.attachmentOutcomes
        try{outcomeExportPicker.restore(requestId)}catch(_:RuntimeException){flow.exportLaunchResult(requestId,OutcomeExportLaunch.UNKNOWN)}
    }
    internal fun createOutcomeDocument(mime:String,name:String){
        check(businessCurrent()) { "原业务窗口尚未就绪，请核对原导出。" }
        val flow=controller.attachmentOutcomes;val requestId=flow.exportRequestId?:return
        armBusinessPicker("outcome:$requestId")
        flow.exportLaunchResult(requestId,outcomeExportPicker.launch(requestId,mime,name))
    }
    private val ownerArchiveImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (acceptBusinessResult("archiveImport")) controller.ownerArchives.importResult(uri) }
    private val ownerArchiveExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (acceptBusinessResult("archiveExport")) controller.ownerArchives.exportResult(uri) }
    internal fun pickOwnerDataArchive() { armBusinessPicker("archiveImport"); try { ownerArchiveImportPicker.launch(arrayOf("application/zip", "application/octet-stream")) } catch (_: RuntimeException) { pickerOwners.remove("archiveImport"); controller.ownerArchives.importResult(null) } }
    internal fun createOwnerDataArchive(name: String) { armBusinessPicker("archiveExport"); try { ownerArchiveExportPicker.launch(name) } catch (_: RuntimeException) { pickerOwners.remove("archiveExport"); controller.ownerArchives.exportResult(null) } }
    private val developerLogExporter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "developer-log-export").apply { isDaemon = true }
    }
    private val developerLogPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (acceptBusinessResult("developerLogExport") && uri != null) {
            developerLogExporter.execute {
                try {
                    val stream = contentResolver.openOutputStream(uri, "w") ?: error("output unavailable")
                    stream.use { RuntimeDiagnostics.exportDeveloperLogs(it) }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed)
                            android.widget.Toast.makeText(this, "开发诊断日志已保存", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (failure: Exception) {
                    RuntimeDiagnostics.event("diagnostics", "failed", "DEVELOPER_LOG_EXPORT_FAILED", null, failure)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed)
                            android.widget.Toast.makeText(this, "日志导出失败，请检查剩余空间", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    internal fun createDeveloperLogExport() {
        armBusinessPicker("developerLogExport")
        try { developerLogPicker.launch("pocketworld-developer-logs.jsonl") }
        catch (failure: Exception) { pickerOwners.remove("developerLogExport"); throw failure }
    }

    private var storageDiagnosticTicket: dev.pocketengine.features.local.runtime.NativeDiagnosticWindow.Ticket? = null
    private val storageDiagnosticPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val original = storageDiagnosticTicket; storageDiagnosticTicket = null
        if (acceptBusinessResult("storageDiagnostic")) controller.projectStorage.diagnosticExportResult(original, uri)
    }
    internal fun createStorageDiagnostic(ticket: dev.pocketengine.features.local.runtime.NativeDiagnosticWindow.Ticket) {
        check(businessCurrent())
        check(storageDiagnosticTicket == null) { "原诊断文件选择尚未结束" }
        storageDiagnosticTicket = ticket
        armBusinessPicker("storageDiagnostic")
        try { storageDiagnosticPicker.launch("pocketworld-diagnostic-metadata.json") }
        catch (_: RuntimeException) { storageDiagnosticTicket = null; controller.projectStorage.diagnosticPickerUnavailable(ticket) }
    }
    private val projectPackageImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (acceptBusinessResult("packageImport")) controller.projectPackages.importResult(uri) }
    private val projectPackageExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (acceptBusinessResult("packageExport")) controller.projectPackages.exportResult(uri) }
    internal fun pickProjectPackageDocument() {
        check(businessCurrent())
        armBusinessPicker("packageImport")
        try { projectPackageImportPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
        catch (_: RuntimeException) { controller.projectPackages.importResult(null) }
    }
    internal fun createProjectPackageDocument(name: String) {
        check(businessCurrent())
        armBusinessPicker("packageExport")
        try { projectPackageExportPicker.launch(name) }
        catch (_: RuntimeException) { controller.projectPackages.exportResult(null) }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (acceptBusinessResult("export")) controller.completeExport(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null, exportPath)
        exportPath = null
    }
    private val workingFilePickers = linkedMapOf<String, androidx.activity.result.ActivityResultLauncher<Array<String>>>()
    internal fun cancelProjectWorkingFile(nonce: String) { workingFilePickers.remove(nonce)?.unregister() }
    internal fun pickProjectWorkingFile(nonce: String) {
        check(businessCurrent())
        require(nonce.matches(Regex("[a-f0-9-]{36}")))
        armBusinessPicker("working:$nonce")
        var delivered = false
        val launcher = activityResultRegistry.register("owner_working_file_$nonce", ActivityResultContracts.OpenDocument()) { uri ->
            delivered = true
            workingFilePickers.remove(nonce)?.unregister()
            if (acceptBusinessResult("working:$nonce")) controller.projectFileImports.result(nonce, uri)
        }
        if (delivered) { launcher.unregister(); return }
        workingFilePickers[nonce] = launcher
        try { launcher.launch(arrayOf("*/*")) } catch (failed: RuntimeException) { workingFilePickers.remove(nonce)?.unregister(); throw failed }
    }
    private val projectSourceTreePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (acceptBusinessResult("sourceTree")) controller.projectPackages.importResult(uri) }
    internal fun pickProjectSourceDirectory() {
        check(businessCurrent())
        armBusinessPicker("sourceTree")
        try { projectSourceTreePicker.launch(null) }
        catch (_: RuntimeException) { controller.projectPackages.importResult(null) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(pocketWindowTheme(this))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = application as EngineApplication
        startupSaved = savedInstanceState?.let { Bundle(it) }
        crossedBootstrap = savedInstanceState?.getBoolean("bootstrapWasShown") == true || !engine.businessReady()
        if (savedInstanceState == null && crossedBootstrap) retainFreshBootstrapContent(intent)
        bootstrapPresentation = savedInstanceState?.getString("bootstrapPresentation")?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webHost.isInitialized) webHost.handleBack { moveTaskToBack(true) }
                else if (!businessMounted) moveTaskToBack(true)
            }
        })
        if (engine.businessReady() && !crossedBootstrap) mountBusiness(savedInstanceState)
        else showBootstrap()
    }
    private fun showBootstrap() {
        crossedBootstrap = true
        webHostIsBusiness = false
        webHost = dev.pocketengine.ui.web.PocketWebHost.bootstrap(this, application as EngineApplication, bootstrapPresentation) {
            if (!isFinishing && !isDestroyed && !businessMounted) mountBusiness(null)
        }
        setContentView(webHost.view)
        webHost.start()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) webHost.resume()
    }
    private fun mountBusiness(savedInstanceState: Bundle?) {
        val engine = application as EngineApplication
        if (businessMounted || ::controller.isInitialized || !engine.businessReady() || isFinishing || isDestroyed) return
        val startup = runCatching { engine.bootstrapState() }.getOrNull()
        if (startup == null) { if (!::webHost.isInitialized) showBootstrap(); return }
        if (::webHost.isInitialized) webHost.close()
        bootstrapPresentation = null
        businessGeneration = startup.optLong("generation", -1L)
        media = MediaController(this, engine::publish)
        controller = PocketWorldController(this, engine)
        businessMounted = true
        controller.attachmentOutcomes.restore(savedInstanceState)
        controller.attachmentOutcomes.exportRequestId?.let(::restoreOutcomeDocument)
        controller.projectPackages.restore(savedInstanceState)
        controller.ownerArchives.restore(savedInstanceState)
        controller.projectStorage.restore(savedInstanceState)
        controller.projectGitImports.restore(savedInstanceState)
        savedInstanceState?.getString("channelPhoneEventId")?.let { controller.prepareChannelPhone(it) }
        val legacyTask = savedInstanceState?.getString("ownerImportTask")
        val legacyProject = savedInstanceState?.getString("ownerImportProject")
        legacyImportTarget = if (legacyTask != null || legacyProject != null) runCatching { OwnerAttachmentImport.Target(legacyTask, legacyProject) }.getOrNull() else null
        if (!attachmentPicker.restore(savedInstanceState)) controller.attachmentImportContextUnavailable()
        exportPath = savedInstanceState?.getString("ownerExportPath")
        if (exportPath != null) controller.exportRestored()
        val presentation = savedInstanceState?.getString("libraryWebPresentation")?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        showWorkspace(presentation)
        controller.attach()
        engine.publish(media.state())
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) resumeBusiness()
        if (crossedBootstrap) {
            // Navigation can be revalidated. Old picker/voice/import/continue mutations cannot be replayed into a fresh Controller.
            val pending = intent
            val freshContent = freshBootstrapContent
            freshBootstrapContent = null
            val contentSelection = pending?.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)
            val needsNewRequest = (contentSelection && freshContent == null) || pending?.action == dev.pocketengine.phone.QuickPanelVoiceHandoff.ACTION ||
                pending?.hasExtra(dev.pocketengine.features.assistant.CompatibilityChatHandoff.EXTRA) == true || ignoredBusinessResult
            if (contentSelection || needsNewRequest) {
                pending?.action = null
                if (pending?.data?.host !in setOf("owner-request", "automation")) pending?.data = null
                pending?.clipData = null
                pending?.removeExtra(Intent.EXTRA_STREAM)
                pending?.removeExtra(dev.pocketengine.phone.QuickPanelVoiceHandoff.TOKEN_EXTRA)
                pending?.removeExtra(dev.pocketengine.features.assistant.CompatibilityChatHandoff.EXTRA)
            }
            if (needsNewRequest) engine.error("此前文件选择、听写或继续请求没有重放。请回原入口核对后重新选择；原操作结果仍需查询。")
            deliverPhoneTask(intent); deliverRemoteAttention(intent); deliverGoal(intent); deliverRemoteInbox(intent); deliverAccountEntry(intent); deliverLegacyPanel(intent)
            // Original content-only import/inspection paths; no old task target, grant or continue token.
            deliverSharedFiles(freshContent); deliverSkillDocument(freshContent)
            startupSaved?.getString("channelPhoneEventId")?.let { controller.prepareChannelPhone(it) }
            startupSaved = null
            return
        }
        deliverPhoneTask(intent)
        deliverRemoteAttention(intent)
        deliverGoal(intent)
        deliverRemoteInbox(intent)
        deliverSharedFiles(intent)
        deliverSkillDocument(intent)
        deliverAccountEntry(intent)
        deliverLegacyPanel(intent)
        deliverCompatibilityChat(intent)
    }
    private fun showWorkspace(presentation: org.json.JSONObject?) {
        if (!businessCurrent()) return
        if (::webHost.isInitialized && webHostIsBusiness) return
        webHost = dev.pocketengine.ui.web.PocketWebHost(this, controller, presentation)
        webHostIsBusiness = true
        setContentView(webHost.view)
        webHost.start()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) webHost.resume()
    }

    private fun resumeBusiness() {
        if (!businessCurrent()) return
        controller.projectLivePreviewForeground(true); controller.notificationPreviewsForeground(true)
        val engine = application as EngineApplication
        engine.remoteOwnerWorkflows?.setForeground(this, true); engine.refreshRemoteAttention()
        controller.realtimeForeground(true); controller.refresh(); controller.backgroundSetupResumed()
        controller.attachmentOutcomes.exportRequestId?.let(::restoreOutcomeDocument); attachmentPicker.resume()
        controller.attachmentOutcomes.foreground(true); controller.projectFileImports.foreground(true)
        controller.projectPackages.foreground(true); controller.ownerArchives.foreground(true)
        controller.projectStorage.foreground(true); controller.projectGitImports.foreground(true)
    }
    override fun onResume() {
        super.onResume()
        if (businessMounted && !businessCurrent()) {
            crossedBootstrap = true
            if (::webHost.isInitialized) webHost.close()
            // A retired business Controller is never rebound to another database generation.
            recreate(); return
        }
        if (::webHost.isInitialized) webHost.resume()
        resumeBusiness()
    }
    override fun onPause() {
        if (::webHost.isInitialized) webHost.pause()
        if (::controller.isInitialized) {
            // Revoke every owner window even when persistence is no longer available.
            listOf<() -> Unit>(
                { controller.projectLivePreviewForeground(false) }, { controller.notificationPreviewsForeground(false) },
                { (application as EngineApplication).remoteOwnerWorkflows?.setForeground(this, false) },
                { if (businessCurrent()) controller.persistOwnerComposer() }, { controller.projectFileImports.foreground(false) },
                { controller.projectPackages.foreground(false) }, { controller.ownerArchives.foreground(false) },
                { controller.projectStorage.foreground(false) }, { controller.projectGitImports.foreground(false) },
                { controller.attachmentOutcomes.foreground(false) }, { controller.realtimeForeground(false) }
            ).forEach { runCatching(it) }
        }
        if (::media.isInitialized) media.stopListening()
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (::controller.isInitialized) controller.notificationPreviewsFocus(hasFocus && businessCurrent()) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (!businessCurrent()) { retainFreshBootstrapContent(intent); return }
        deliverPhoneTask(intent); deliverRemoteAttention(intent); deliverGoal(intent); deliverRemoteInbox(intent); deliverSharedFiles(intent)
        deliverSkillDocument(intent); deliverAccountEntry(intent); deliverLegacyPanel(intent); deliverCompatibilityChat(intent)
    }
    private fun retainFreshBootstrapContent(incoming: Intent?) {
        val content = incoming?.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) ||
            incoming?.action == Intent.ACTION_VIEW && incoming.data?.scheme == "content"
        freshBootstrapContent = if (incoming != null && content && (incoming.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) Intent(incoming) else null
    }
    private fun deliverCompatibilityChat(intent: Intent?) {
        val id = intent?.getStringExtra(dev.pocketengine.features.assistant.CompatibilityChatHandoff.EXTRA) ?: return
        intent.removeExtra(dev.pocketengine.features.assistant.CompatibilityChatHandoff.EXTRA)
        controller.continueCompatibilityChat(id)
    }
    private fun deliverRemoteInbox(intent: Intent?) {
        if (intent?.action != dev.pocketengine.features.remoteinbox.RemoteInboxNotifications.OPEN) return
        intent.action = null
        dev.pocketengine.features.remoteinbox.RemoteInboxStore.get(this).signalOpen()
        if (::controller.isInitialized) controller.refresh()
    }
    private fun deliverRemoteAttention(intent: Intent?) {
        dev.pocketengine.features.remote.RemoteAttentionNotifications.openRequestedTask((application as EngineApplication).tasks, intent) { controller.showRemoteAttention(it) }
    }
    private fun deliverAccountEntry(intent: Intent?) {
        if (intent?.getBooleanExtra("openChatGptAccounts", false) != true) return
        intent.removeExtra("openChatGptAccounts")
        controller.openConnection("accounts")
    }
    private fun deliverLegacyPanel(intent: Intent?) {
        if (intent?.hasExtra("openPanel") != true) return
        val panel = runCatching { intent.getStringExtra("openPanel") }.getOrNull()
        intent.removeExtra("openPanel")
        val section = runCatching { dev.pocketengine.MainActivity.sectionForPanel(panel) }.getOrElse {
            (application as EngineApplication).error("不支持的设置页面")
            return
        }
        controller.openConnection(section)
    }
    private fun deliverPhoneTask(intent: Intent?) {
        val app = application as EngineApplication
        if (intent?.action == dev.pocketengine.phone.QuickPanelVoiceHandoff.ACTION) {
            val token = intent.getStringExtra(dev.pocketengine.phone.QuickPanelVoiceHandoff.TOKEN_EXTRA)
            intent.action = null; intent.removeExtra(dev.pocketengine.phone.QuickPanelVoiceHandoff.TOKEN_EXTRA)
            if (!token.isNullOrBlank()) controller.openQuickPanelVoice(token)
            return
        }
        if (dev.pocketengine.features.assistant.notifications.ImmediateTaskNotifications.openRequestedTask(intent,
                app.tasks::openOriginalTaskNotice, controller::showOriginalTaskNotice, controller::showTaskNoticeWarning)) return
        if (dev.pocketengine.features.assistant.notifications.AssistantTaskNotifications.openRequestedTask(app.tasks, intent, { current -> controller.focusTask(current.getString("taskId")) }, app::error)) return
        dev.pocketengine.phone.PhoneNotificationControls.openRequestedTask(application as EngineApplication, intent) { controller.focusTask(it) }
        intent?.getStringExtra("quickPanelTaskId")?.takeIf { it.isNotBlank() }?.let {
            intent.removeExtra("quickPanelTaskId"); controller.focusTask(it)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!acceptBusinessResult()) return
        media.onRequestPermissionsResult(requestCode, permissions, grantResults)
        controller.realtimePermissionsChanged()
        controller.refresh()
    }
    private fun deliverGoal(intent: Intent?) {
        if (intent?.hasExtra("channelEventId") == true || intent?.data?.host == "owner-request") {
            val eventId = intent?.getStringExtra("channelEventId")
            intent?.removeExtra("channelEventId"); intent?.removeExtra("automationGoal")
            if (!eventId.isNullOrBlank()) controller.prepareChannelPhone(eventId)
            return
        }
        val commitment = intent?.getStringExtra("commitmentId")?.takeIf { it.isNotBlank() }
        if (commitment != null) {
            val claim = intent.getStringExtra("followUpClaimId")?.takeIf { it.isNotBlank() }
            intent.removeExtra("commitmentId"); intent.removeExtra("followUpClaimId")
            controller.openCommitment(commitment, claim)
            return
        }
        val occurrence = intent?.getStringExtra("automationOccurrence")?.takeIf { it.isNotBlank() }
        if (occurrence != null) {
            intent.removeExtra("automationOccurrence"); intent.removeExtra("automationGoal"); intent.removeExtra("automationRuleId")
            controller.prepareReminderOccurrence(occurrence)
            return
        }
        if (intent?.hasExtra("automationRuleId") == true || intent?.data?.host == "automation") {
            intent?.removeExtra("automationGoal"); intent?.removeExtra("automationRuleId")
            if (intent?.data?.host == "automation") intent.data = null
            controller.openConnection("automation")
            return
        }
        // Old/foreign PendingIntent body is not source authority. Only the original
        // occurrence route above may retrieve a current, still-authorized goal.
        if (intent?.hasExtra("automationGoal") == true) {
            intent.removeExtra("automationGoal")
            controller.openConnection("automation")
        }
    }
    internal fun pickMcpConfig(requestId: String) {
        check(businessCurrent())
        if (mcpPickRequestId != null) { controller.completeMcpConfig(requestId, null); return }
        mcpPickRequestId = requestId
        armBusinessPicker("mcp")
        try { mcpPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        catch (_: RuntimeException) { mcpPickRequestId = null; controller.completeMcpConfig(requestId, null) }
    }
    internal fun pickAttachments(taskId: String?, projectId: String?) {
        check(businessCurrent())
        if (controller.state.attachmentImporting) { controller.attachmentImportPickerStatus(AttachmentImportPicker.Launch.BUSY); return }
        armBusinessPicker("attachment")
        controller.attachmentImportPickerStatus(attachmentPicker.launch(taskId, projectId))
    }
    internal fun offerExport(name: String, mimeType: String, path: String) {
        check(businessCurrent())
        if (exportPath != null) { controller.completeExport(null, path); return }
        exportPath = path
        armBusinessPicker("export")
        try { exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mimeType).putExtra(Intent.EXTRA_TITLE, name)) }
        catch (_: RuntimeException) { exportPath = null; controller.completeExport(null, path) }
    }
    private fun deliverSkillDocument(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        try {
            if (controller.skillDocuments.acceptView(intent)) controller.openConnection("skills")
        } catch (failure: Exception) {
            android.widget.Toast.makeText(this, failure.message ?: "无法接收这份待检查文件", android.widget.Toast.LENGTH_LONG).show()
        } finally { intent.action = null }
    }
    private fun deliverSharedFiles(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            controller.importAttachments(Intent(intent), null, null)
            intent?.action = null
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("bootstrapWasShown", crossedBootstrap && !businessCurrent())
        if (!businessCurrent()) {
            if (::webHost.isInitialized && !webHostIsBusiness) outState.putString("bootstrapPresentation", webHost.savePresentationState())
            startupSaved?.getString("channelPhoneEventId")?.let { outState.putString("channelPhoneEventId", it) }
            super.onSaveInstanceState(outState)
            return
        }
        controller.persistOwnerComposer()
        outState.putString("channelPhoneEventId", controller.channelPhoneEventId)
        if (::webHost.isInitialized) outState.putString("libraryWebPresentation", webHost.savePresentationState())
        outState.putString("ownerImportTask", legacyImportTarget?.taskId); outState.putString("ownerImportProject", legacyImportTarget?.projectId); outState.putString("ownerExportPath", exportPath)
        attachmentPicker.save(outState)
        controller.attachmentOutcomes.save(outState)
        controller.projectPackages.save(outState)
        controller.ownerArchives.save(outState)
        controller.projectStorage.save(outState)
        controller.projectGitImports.save(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { developerLogExporter.shutdown(); if (::webHost.isInitialized) webHost.close(); attachmentPicker.close(); outcomeExportPicker.close(); if (functionalPackDocumentSlot.isInitialized()) functionalPackDocuments.close(); workingFilePickers.values.toList().forEach { it.unregister() };workingFilePickers.clear();if (::controller.isInitialized) runCatching { controller.detach() }; businessMounted=false; if (::media.isInitialized) media.close(); super.onDestroy() }
}
