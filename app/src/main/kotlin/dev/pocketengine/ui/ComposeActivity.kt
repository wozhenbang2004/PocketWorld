package dev.pocketengine.ui

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import dev.pocketengine.EngineApplication
import dev.pocketengine.media.MediaController

/** Pocket World's native product UI; Java engine modules retain their existing identity and data. */
class ComposeActivity : ComponentActivity() {
    private lateinit var controller: PocketWorldController
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
    private val ownerArchiveExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> controller.ownerArchives.exportResult(uri) }
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
    private val projectSourceTreePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> controller.projectPackages.importResult(uri) }
    internal fun pickProjectSourceDirectory() {
        try { projectSourceTreePicker.launch(null) }
        catch (_: RuntimeException) { controller.projectPackages.importResult(null) }
    }
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(dev.pocketengine.features.operations.AppLanguage.wrap(base)) }
    override fun onCreate(savedInstanceState: Bundle?) {
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
        importTaskId = savedInstanceState?.getString("ownerImportTask")
        importProjectId = savedInstanceState?.getString("ownerImportProject")
        exportPath = savedInstanceState?.getString("ownerExportPath")
        if (exportPath != null) controller.exportRestored()
        setContent { PocketWorldApp(controller) }
        controller.attach()
        engine.publish(media.state())
        deliverPhoneTask(intent)
        deliverRemoteAttention(intent)
        deliverGoal(intent)
        deliverSharedFiles(intent)
        deliverAccountEntry(intent)
    }
    override fun onResume() { super.onResume(); (application as EngineApplication).remoteOwnerWorkflows?.setForeground(this, true); (application as EngineApplication).refreshRemoteAttention(); if (::controller.isInitialized) { controller.realtimeForeground(true); controller.refresh(); controller.backgroundSetupResumed(); controller.attachmentOutcomes.foreground(true); controller.projectPackages.foreground(true); controller.ownerArchives.foreground(true); controller.projectStorage.foreground(true); controller.projectGitImports.foreground(true) } }
    override fun onPause() { (application as EngineApplication).remoteOwnerWorkflows?.setForeground(this, false); if (::controller.isInitialized) { controller.persistOwnerComposer(); controller.projectPackages.foreground(false); controller.ownerArchives.foreground(false); controller.projectStorage.foreground(false); controller.projectGitImports.foreground(false); controller.attachmentOutcomes.foreground(false); controller.realtimeForeground(false) }; if (::media.isInitialized) media.stopListening(); super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); deliverPhoneTask(intent); deliverRemoteAttention(intent); deliverGoal(intent); deliverSharedFiles(intent); deliverAccountEntry(intent) }
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
        intent?.getStringExtra("automationGoal")?.takeIf { it.isNotBlank() }?.let {
            controller.suggestInput(it, true)
            intent.removeExtra("automationGoal")
        }
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
        outState.putString("ownerImportTask", importTaskId); outState.putString("ownerImportProject", importProjectId); outState.putString("ownerExportPath", exportPath)
        controller.attachmentOutcomes.save(outState)
        controller.projectPackages.save(outState)
        controller.ownerArchives.save(outState)
        controller.projectStorage.save(outState)
        controller.projectGitImports.save(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if (::controller.isInitialized) controller.detach(); if (::media.isInitialized) media.close(); super.onDestroy() }
}
