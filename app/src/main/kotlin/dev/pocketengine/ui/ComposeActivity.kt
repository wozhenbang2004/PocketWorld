package dev.pocketengine.ui

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.view.View
import android.graphics.Insets
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.pocketengine.EngineApplication
import dev.pocketengine.media.MediaController

/** Compatibility Activity identity; all business UI is rendered by the bundled library Web interface. */
class ComposeActivity : ComponentActivity() {
    private lateinit var controller: PocketWorldController
    private lateinit var webHost: dev.pocketengine.ui.web.PocketWebHost
    private var nativeWorkspace = false
    private var nativeWorkspaceView: ComposeView? = null
    private var savedWebPresentation: org.json.JSONObject? = null
    internal lateinit var media: MediaController
        private set
    private var importTaskId: String? = null
    private var importProjectId: String? = null
    private var exportPath: String? = null
    private var mcpPickRequestId: String? = null
    private val mcpPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        controller.completeMcpConfig(mcpPickRequestId, uri); mcpPickRequestId = null
    }
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) controller.importAttachments(result.data!!, importTaskId, importProjectId)
        importTaskId = null; importProjectId = null
    }
    private val outcomeExportPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.attachmentOutcomes.exportResult(if(result.resultCode==Activity.RESULT_OK)result.data?.data else null)
    }
    internal fun createOutcomeDocument(mime:String,name:String){outcomeExportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name))}
    private val ownerArchiveImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> controller.ownerArchives.importResult(uri) }
    private val ownerArchiveExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> controller.ownerArchives.exportResult(uri) }
    internal fun pickOwnerDataArchive() { try { ownerArchiveImportPicker.launch(arrayOf("application/zip", "application/octet-stream")) } catch (_: RuntimeException) { controller.ownerArchives.importResult(null) } }
    internal fun createOwnerDataArchive(name: String) { try { ownerArchiveExportPicker.launch(name) } catch (_: RuntimeException) { controller.ownerArchives.exportResult(null) } }
    private val projectPackageImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> controller.projectPackages.importResult(uri) }
    private val projectPackageExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> controller.projectPackages.exportResult(uri) }
    internal fun pickProjectPackageDocument() {
        try { projectPackageImportPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
        catch (_: RuntimeException) { controller.projectPackages.importResult(null) }
    }
    internal fun createProjectPackageDocument(name: String) {
        try { projectPackageExportPicker.launch(name) }
        catch (_: RuntimeException) { controller.projectPackages.exportResult(null) }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.completeExport(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null, exportPath)
        exportPath = null
    }
    private val workingFilePickers = linkedMapOf<String, androidx.activity.result.ActivityResultLauncher<Array<String>>>()
    internal fun cancelProjectWorkingFile(nonce: String) { workingFilePickers.remove(nonce)?.unregister() }
    internal fun pickProjectWorkingFile(nonce: String) {
        require(nonce.matches(Regex("[a-f0-9-]{36}")))
        var delivered = false
        val launcher = activityResultRegistry.register("owner_working_file_$nonce", ActivityResultContracts.OpenDocument()) { uri ->
            delivered = true
            workingFilePickers.remove(nonce)?.unregister()
            controller.projectFileImports.result(nonce, uri)
        }
        if (delivered) { launcher.unregister(); return }
        workingFilePickers[nonce] = launcher
        try { launcher.launch(arrayOf("*/*")) } catch (failed: RuntimeException) { workingFilePickers.remove(nonce)?.unregister(); throw failed }
    }
    private val projectSourceTreePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> controller.projectPackages.importResult(uri) }
    internal fun pickProjectSourceDirectory() {
        try { projectSourceTreePicker.launch(null) }
        catch (_: RuntimeException) { controller.projectPackages.importResult(null) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(pocketWindowTheme(this))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = application as EngineApplication
        media = MediaController(this, engine::publish)
        controller = PocketWorldController(this, engine)
        controller.attachmentOutcomes.restore(savedInstanceState)
        controller.projectPackages.restore(savedInstanceState)
        controller.ownerArchives.restore(savedInstanceState)
        controller.projectStorage.restore(savedInstanceState)
        controller.projectGitImports.restore(savedInstanceState)
        savedInstanceState?.getString("channelPhoneEventId")?.let { controller.prepareChannelPhone(it) }
        importTaskId = savedInstanceState?.getString("ownerImportTask")
        importProjectId = savedInstanceState?.getString("ownerImportProject")
        exportPath = savedInstanceState?.getString("ownerExportPath")
        if (exportPath != null) controller.exportRestored()
        savedWebPresentation = savedInstanceState?.getString("libraryWebPresentation")?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        if (savedInstanceState?.getBoolean("nativeWorkspace") == true) showNativeWorkspace()
        else showWebWorkspace()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!nativeWorkspace) webHost.handleBack { moveTaskToBack(true) }
            }
        })
        controller.attach()
        engine.publish(media.state())
        deliverPhoneTask(intent)
        deliverRemoteAttention(intent)
        deliverGoal(intent)
        deliverSharedFiles(intent)
        deliverAccountEntry(intent)
        deliverCompatibilityChat(intent)
    }
    private fun confirmWorkspaceSwitch(toNative: Boolean, stillSafe: () -> Boolean = { true }) {
        if (isFinishing || isDestroyed || toNative == nativeWorkspace) return
        if (!stillSafe()) {
            android.widget.Toast.makeText(this, "当前工作正在处理，请结束当前页面后重试。", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (toNative) "切换到原生工作台？" else "返回网页工作台？")
            .setMessage("切换会关闭临时审阅，未提交或未保存的输入可能丢失；进行中的界面读取、登录或工具发现可能取消。执行中的任务不会停止或重新运行。")
            .setNegativeButton("留在当前界面", null)
            .setPositiveButton("切换") { _, _ ->
                if (stillSafe()) { if (toNative) showNativeWorkspace() else showWebWorkspace() }
                else android.widget.Toast.makeText(this, "当前工作正在处理，请结束当前页面后重试。", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Presentation only: both surfaces use the Activity's existing controller and engine. */
    private fun workspaceLayout(label: String, action: () -> Unit, body: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val switchButton = Button(this@ComposeActivity).apply { text = label; setOnClickListener { action() } }
            val originalPadding = intArrayOf(switchButton.paddingLeft, switchButton.paddingTop, switchButton.paddingRight, switchButton.paddingBottom)
            addView(switchButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                switchButton.setPadding(originalPadding[0] + bars.left, originalPadding[1] + bars.top,
                    originalPadding[2] + bars.right, originalPadding[3])
                val status = insets.getInsets(WindowInsets.Type.statusBars())
                WindowInsets.Builder(insets)
                    .setInsets(WindowInsets.Type.statusBars(), Insets.of(status.left, 0, status.right, status.bottom))
                    .build()
            }
        }

    private fun webWorkspaceCanSwitch(): Boolean {
        val state = controller.state
        return ::webHost.isInitialized && webHost.canSwitchPresentation() && state.ownerPending.isEmpty() && state.moduleBusy.isEmpty() && !state.chatSubmitting &&
            !state.phoneSubmitting && !state.fileSaving && !state.modelSaving && !state.modelTesting &&
            !state.agentPreferencesSaving && !state.inspectionBusy && !state.exportPending &&
            !controller.attachmentOutcomes.state.open && !controller.composerSaving && !controller.voicePresentationBusy()
    }

    private fun showNativeWorkspace() {
        if (nativeWorkspace && nativeWorkspaceView != null) return
        if (::webHost.isInitialized) {
            savedWebPresentation = org.json.JSONObject(webHost.savePresentationState())
            webHost.close()
        }
        nativeWorkspace = true
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { MaterialTheme { PocketWorldApp(controller) { stillSafe -> confirmWorkspaceSwitch(false, stillSafe) } } }
        }
        nativeWorkspaceView = compose
        setContentView(compose)
    }

    private fun showWebWorkspace() {
        if (!nativeWorkspace && ::webHost.isInitialized) return
        nativeWorkspaceView?.disposeComposition()
        nativeWorkspaceView = null
        if (nativeWorkspace) {
            controller.closeNativeWorkspacePresentation()
        }
        nativeWorkspace = false
        webHost = dev.pocketengine.ui.web.PocketWebHost(this, controller, savedWebPresentation)
        setContentView(workspaceLayout("打开原生工作台", { confirmWorkspaceSwitch(true, ::webWorkspaceCanSwitch) }, webHost.view))
        webHost.start()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) webHost.resume()
    }

    override fun onResume() { super.onResume(); if (!nativeWorkspace && ::webHost.isInitialized) webHost.resume(); if (::controller.isInitialized) controller.notificationPreviewsForeground(true); (application as EngineApplication).remoteOwnerWorkflows?.setForeground(this, true); (application as EngineApplication).refreshRemoteAttention(); if (::controller.isInitialized) { controller.realtimeForeground(true); controller.refresh(); controller.backgroundSetupResumed(); controller.attachmentOutcomes.foreground(true); controller.projectFileImports.foreground(true);controller.projectPackages.foreground(true); controller.ownerArchives.foreground(true); controller.projectStorage.foreground(true); controller.projectGitImports.foreground(true) } }
    override fun onPause() { if (!nativeWorkspace && ::webHost.isInitialized) webHost.pause(); if (::controller.isInitialized) controller.notificationPreviewsForeground(false); (application as EngineApplication).remoteOwnerWorkflows?.setForeground(this, false); if (::controller.isInitialized) { controller.persistOwnerComposer(); controller.projectFileImports.foreground(false);controller.projectPackages.foreground(false); controller.ownerArchives.foreground(false); controller.projectStorage.foreground(false); controller.projectGitImports.foreground(false); controller.attachmentOutcomes.foreground(false); controller.realtimeForeground(false) }; if (::media.isInitialized) media.stopListening(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (::controller.isInitialized) controller.notificationPreviewsFocus(hasFocus) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); deliverPhoneTask(intent); deliverRemoteAttention(intent); deliverGoal(intent); deliverSharedFiles(intent); deliverAccountEntry(intent); deliverCompatibilityChat(intent) }
    private fun deliverCompatibilityChat(intent: Intent?) {
        val id = intent?.getStringExtra(dev.pocketengine.features.assistant.CompatibilityChatHandoff.EXTRA) ?: return
        intent.removeExtra(dev.pocketengine.features.assistant.CompatibilityChatHandoff.EXTRA)
        controller.continueCompatibilityChat(id)
    }
    private fun deliverRemoteAttention(intent: Intent?) {
        dev.pocketengine.features.remote.RemoteAttentionNotifications.openRequestedTask((application as EngineApplication).tasks, intent) { controller.showRemoteAttention(it) }
    }
    private fun deliverAccountEntry(intent: Intent?) {
        if (intent?.getBooleanExtra("openChatGptAccounts", false) != true) return
        intent.removeExtra("openChatGptAccounts")
        controller.openConnection("accounts")
    }
    private fun deliverPhoneTask(intent: Intent?) {
        val app = application as EngineApplication
        if (dev.pocketengine.features.assistant.notifications.AssistantTaskNotifications.openRequestedTask(app.tasks, intent, { current -> controller.focusTask(current.getString("taskId")) }, app::error)) return
        dev.pocketengine.phone.PhoneNotificationControls.openRequestedTask(application as EngineApplication, intent) { controller.focusTask(it) }
        intent?.getStringExtra("quickPanelTaskId")?.takeIf { it.isNotBlank() }?.let {
            intent.removeExtra("quickPanelTaskId"); controller.focusTask(it)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
            intent?.removeExtra("automationGoal")
            return
        }
        // Old/foreign PendingIntent body is not source authority. Only the original
        // occurrence route above may retrieve a current, still-authorized goal.
        intent?.removeExtra("automationGoal")
    }
    internal fun pickMcpConfig(requestId: String) {
        if (mcpPickRequestId != null) { controller.completeMcpConfig(requestId, null); return }
        mcpPickRequestId = requestId
        try { mcpPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        catch (_: RuntimeException) { mcpPickRequestId = null; controller.completeMcpConfig(requestId, null) }
    }
    internal fun pickAttachments(taskId: String?, projectId: String?) {
        importTaskId = taskId; importProjectId = projectId
        attachmentPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }
    internal fun offerExport(name: String, mimeType: String, path: String) {
        if (exportPath != null) { controller.completeExport(null, path); return }
        exportPath = path
        try { exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mimeType).putExtra(Intent.EXTRA_TITLE, name)) }
        catch (_: RuntimeException) { exportPath = null; controller.completeExport(null, path) }
    }
    private fun deliverSharedFiles(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            controller.importAttachments(Intent(intent), null, null)
            intent?.action = null
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        controller.persistOwnerComposer()
        outState.putString("channelPhoneEventId", controller.channelPhoneEventId)
        outState.putBoolean("nativeWorkspace", nativeWorkspace)
        val presentation = if (!nativeWorkspace && ::webHost.isInitialized) webHost.savePresentationState() else savedWebPresentation?.toString()
        presentation?.let { outState.putString("libraryWebPresentation", it) }
        outState.putString("ownerImportTask", importTaskId); outState.putString("ownerImportProject", importProjectId); outState.putString("ownerExportPath", exportPath)
        controller.attachmentOutcomes.save(outState)
        controller.projectPackages.save(outState)
        controller.ownerArchives.save(outState)
        controller.projectStorage.save(outState)
        controller.projectGitImports.save(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { nativeWorkspaceView?.disposeComposition(); nativeWorkspaceView = null; if (::webHost.isInitialized) webHost.close(); workingFilePickers.values.toList().forEach { it.unregister() };workingFilePickers.clear();if (::controller.isInitialized) controller.detach(); if (::media.isInitialized) media.close(); super.onDestroy() }
}
