package dev.pocketengine.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost as Host
import dev.pocketengine.features.devices.NativeDeviceTaskScopeHost
import java.io.File
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog



/** EXPLICIT SYNTHETIC NATIVE-PRODUCER FIXTURE. Actual production MyDevicesPanel renders and
 * handles Android semantics/back/lifecycle. No TLS/device/model/account/key/installation is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w393dp-h852dp-mdpi", shadows = [OfficialAarResources::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class DeviceScopeMaterialUiTest {
    private val output get() = File(requireNotNull(System.getProperty("c2.captureDirectory")))
    private val localId = "pw1_" + "a".repeat(64)
    private val peerId = "pw1_" + "b".repeat(64)
    private fun obj(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().also { json -> pairs.forEach { json.put(it.first, it.second) } }
    private fun composeViews(view: View): List<View> = buildList { if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(composeViews(view.getChildAt(i))) }
    private fun nodes(root: View) = composeViews(root).flatMap { (it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).getAllSemanticsNodes(mergingEnabled = true) }
    private fun texts(root: View) = nodes(root).flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text }.orEmpty() }
    private fun tagged(root: View, tag: String) = nodes(root).first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun clickTag(root: View, tag: String) { val node = tagged(root, tag); assertFalse("disabled $tag; now=${System.currentTimeMillis()}; text=${texts(root)}", node.config.contains(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); settle(root) }
    private fun clickText(root: View, text: String) { val node = nodes(root).first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true && it.config.contains(SemanticsActions.OnClick) }; assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); settle(root) }
    private fun setText(root: View, tag: String, value: String) { val node = tagged(root, tag); assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(value))); settle(root) }
    private fun settle(root: View) {
        fun layout(view: View) { if (view is androidx.compose.ui.node.RootForTest) view.measureAndLayoutForTest(); if (view is ViewGroup) for (i in 0 until view.childCount) layout(view.getChildAt(i)) }
        repeat(6) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)); root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 393, 852); layout(root); Thread.sleep(12) }
    }
    private fun finishActivity(pending: org.robolectric.android.controller.ActivityController<DeviceWorkspaceFixtureActivity>) {
        pending.pause().stop().destroy()
        // Compose posts dispatcher cancellation from Activity destruction. Complete it while
        // this test's Looper/vsync queue still exists, before Robolectric resets that queue.
        // Otherwise AndroidUiDispatcher can retain scheduled flags for discarded callbacks,
        // so the following test renders its first frame but cannot apply checkbox edits.
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(org.robolectric.shadows.ShadowChoreographer.getFrameDelay().plusMillis(16))
    }
    private fun awaitDialog(root: View): android.app.Dialog {
        val until = System.nanoTime() + 6_000_000_000L
        while (System.nanoTime() < until) { settle(root); ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.let { return it } }
        error("Native dialog did not appear; actual source: ${texts(root)}")
    }
    private fun capture(root: View, name: String) {
        settle(root); output.mkdirs(); val bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap)); val pixels = IntArray(393 * 852); bitmap.getPixels(pixels, 0, 393, 0, 0, 393, 852)
        assertTrue("Current source render is not blank", pixels.toSet().size > 16)
        File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        File(output, "$name-semantics.txt").writeText(texts(root).joinToString("\n")); println("DEVICE_UI_NATIVE_FIXTURE $name 393x852 API35; NOT REAL PAIRING")
    }
    @Test fun alternatingFileAndProjectJourneysKeepTheirOwnSelectionAndConsent() {
        repeat(2) {
            explicitFilesKeepBudgetAndRequireExactReviewConsent()
            projectSelectionAndStagedImportNeedSeparateExplicitConsent()
        }
    }
    @Test fun explicitFilesKeepBudgetAndRequireExactReviewConsent() {
        val now = System.currentTimeMillis()
        val resource = obj("screenMode" to "capabilities", "projectId" to "p", "checkoutId" to "c", "runtimeId" to "r")
        val material = obj("id" to "book-file", "name" to "brief.txt", "mimeType" to "text/plain", "size" to 12, "sha256" to "a".repeat(64), "available" to true, "maximumBytes" to 262144)
        var nativeView by mutableStateOf(obj("goal" to "将明确文件交给项目", "toolsAvailable" to 100, "modelsAvailable" to 20, "deadlineMaximum" to now + 86400000,
            "materialOptions" to JSONArray().put(material).put(obj("id" to "deleted", "name" to "已删除.txt", "size" to 8, "available" to false)),
            "options" to JSONArray().put(obj("optionId" to "route", "sourceKey" to "route", "targetDeviceId" to peerId, "kind" to "goal", "resourceBinding" to resource, "toolBudgetMaximum" to 100, "modelBudgetMaximum" to 20, "deadlineMaximum" to now + 86400000, "toolDeclarations" to JSONArray().put(obj("toolName" to "workspace.receive", "effect" to "write"))))))
        var chosen: NativeDeviceTaskScopeHost.Selection? = null
        var reviewView by mutableStateOf<JSONObject?>(null); var confirmed = 0
        val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val root = activity.window.decorView
        activity.setContent { PocketWorldTheme {
            if (reviewView == null) DeviceScopeSelection(nativeView, false, Modifier.fillMaxSize()) { chosen = it }
            else DeviceScopeReview(reviewView!!, nativeView, false, Modifier.fillMaxSize(), {}) { confirmed++ }
        } }
        try {
            settle(root); clickTag(root,"device_scope_option_route"); clickTag(root,"device_scope_review")
            assertTrue(chosen!!.routes().single().files().isEmpty())
            assertTrue(tagged(root,"device_scope_file_route_deleted").config.contains(SemanticsProperties.Disabled))
            clickTag(root,"device_scope_file_route_book-file")
            assertTrue(tagged(root,"device_scope_review").config.contains(SemanticsProperties.Disabled))
            setText(root,"device_scope_file_path_route_book-file","docs/brief.txt")
            setText(root,"device_scope_file_version_route_book-file","b".repeat(64))
            clickText(root,"使用本次余量"); clickText(root,"均衡分配"); clickTag(root,"device_scope_review")
            val route = chosen!!.routes().single(); val file = route.files().single()
            assertEquals("book-file",file.attachmentId()); assertEquals("a".repeat(64),file.sha256()); assertEquals("docs/brief.txt",file.path()); assertEquals("b".repeat(64),file.expectedVersion())
            assertEquals(NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY,route.context())
            nodes(root).firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, 620f); settle(root); capture(root,"material-selection")
            clickTag(root,"device_scope_file_route_book-file"); clickTag(root,"device_scope_review"); assertTrue(chosen!!.routes().single().files().isEmpty())
            // Display-only native read model, never a fabricated native approval proof.
            reviewView = obj("goal" to "核对文件与目标", "routes" to JSONArray().put(obj("targetDeviceId" to peerId, "kind" to "goal", "resourceBinding" to resource, "toolBudget" to route.toolBudget(), "modelBudget" to route.modelBudget(), "deadline" to route.deadline(), "files" to JSONArray().put(JSONObject(material.toString()).put("path",file.path()).put("expectedVersion",file.expectedVersion())))))
            settle(root); assertTrue(tagged(root,"device_scope_confirm").config.contains(SemanticsProperties.Disabled)); assertEquals(0,confirmed)
            assertTrue(texts(root).any { it.contains("brief.txt") && it.contains("12 字节") }); assertTrue(texts(root).any { it.contains("SHA-256："+"a".repeat(64)) }); assertTrue(texts(root).any { it.contains("目标版本："+"b".repeat(64)) }); assertTrue(texts(root).any { it.contains("docs/brief.txt") })
            capture(root,"material-review"); clickTag(root,"device_scope_consent"); clickTag(root,"device_scope_confirm"); assertEquals(1,confirmed)
            // A new native view (e.g. reopening after deletion) must not retain selected materials.
            nativeView = JSONObject(nativeView.toString()).also { it.getJSONArray("materialOptions").getJSONObject(0).put("available", false) }
            reviewView = null; settle(root); clickTag(root,"device_scope_option_route")
            assertTrue(tagged(root,"device_scope_file_route_book-file").config.contains(SemanticsProperties.Disabled))
            clickTag(root,"device_scope_review"); assertTrue(chosen!!.routes().single().files().isEmpty())
            // Non-receive routes must not expose attachment selection at all.
            nativeView = JSONObject(nativeView.toString()).also { it.getJSONArray("options").getJSONObject(0).put("toolDeclarations", JSONArray().put(obj("toolName" to "project_read_file", "effect" to "read"))) }
            settle(root); clickTag(root,"device_scope_option_route")
            assertTrue(nodes(root).none { it.config.getOrNull(SemanticsProperties.TestTag) == "device_scope_file_route_book-file" })
        } finally { finishActivity(pending) }
    }
    @Test fun projectSelectionAndStagedImportNeedSeparateExplicitConsent() {
        val now = System.currentTimeMillis(); val digest = "c".repeat(64)
        val resource = obj("projectId" to "target-project", "checkoutId" to "target-checkout", "rootId" to "target-workspace")
        val manifest = obj("mode" to "project", "bytes" to 12, "project" to obj("name" to "示例项目"), "files" to JSONArray().put(obj("path" to "src/main.txt", "bytes" to 12, "sha256" to "e".repeat(64))), "directories" to JSONArray().put("src"), "omitted" to JSONArray().put(obj("path" to ".env", "reason" to "private")))
        val project = obj("id" to "original-artifact", "name" to "示例固定项目包", "sha256" to "d".repeat(64), "size" to 456, "manifestDigest" to digest, "manifest" to manifest, "sourceTaskId" to "original-task", "sourceOperationId" to "original-freeze", "available" to true, "maximumBytes" to 20971520)
        val pendingImport = JSONObject(project.toString()).put("artifactId", "original-artifact").put("receiveOperationId", "original-receive").put("targetDeviceId", peerId).put("resourceBinding", resource).put("path", "imports/demo").put("expectedVersion", "absent").put("summary", obj("fileCount" to 1, "expandedBytes" to 12)).put("imported", false).put("executionAuthorized", false)
        val nativeView = obj("goal" to "发送固定项目包但不直接导入", "toolsAvailable" to 100, "modelsAvailable" to 20, "deadlineMaximum" to now + 86400000,
            "projectOptions" to JSONArray().put(project), "pendingProjectImports" to JSONArray().put(pendingImport),
            "options" to JSONArray().put(obj("optionId" to "project-route", "sourceKey" to "project-route", "targetDeviceId" to peerId, "kind" to "goal", "resourceBinding" to resource, "toolBudgetMaximum" to 100, "modelBudgetMaximum" to 20, "deadlineMaximum" to now + 86400000, "toolDeclarations" to JSONArray().put(obj("toolName" to "workspace.project.receive", "effect" to "write")))))
        var chosen: NativeDeviceTaskScopeHost.Selection? = null
        var stage by mutableStateOf(0); var scopeView by mutableStateOf<JSONObject?>(null)
        var importView by mutableStateOf(pendingImport); var receivedId: String? = null; var confirmations = 0
        val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val root = activity.window.decorView
        activity.setContent { PocketWorldTheme {
            when (stage) {
                0 -> DeviceScopeSelection(nativeView, false, Modifier.fillMaxSize(), { receivedId = it; stage = 2 }) { chosen = it }
                1 -> DeviceScopeReview(scopeView!!, nativeView, false, Modifier.fillMaxSize(), {}) { stage = 0 }
                else -> DeviceScopeProjectImportReview(importView, false, Modifier.fillMaxSize(), {}) { confirmations++ }
            }
        } }
        try {
            settle(root); assertEquals(0,confirmations); clickTag(root,"device_scope_option_project-route"); clickTag(root,"device_scope_review")
            assertTrue(chosen!!.routes().single().projects().isEmpty())
            clickTag(root,"device_scope_project_project-route_original-artifact"); assertTrue(tagged(root,"device_scope_review").config.contains(SemanticsProperties.Disabled))
            setText(root,"device_scope_project_path_project-route_original-artifact","imports/demo")
            clickText(root,"使用本次余量"); clickText(root,"均衡分配"); clickTag(root,"device_scope_review")
            val selected = chosen!!.routes().single().projects().single()
            assertEquals("original-artifact",selected.artifactId()); assertEquals("d".repeat(64),selected.sha256()); assertEquals("imports/demo",selected.path())
            assertTrue(chosen!!.routes().single().files().isEmpty()); assertEquals(0,confirmations)
            nodes(root).firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, 620f); settle(root); capture(root,"project-selection")
            scopeView = obj("goal" to "仅接收暂存", "routes" to JSONArray().put(obj("targetDeviceId" to peerId, "resourceBinding" to resource, "projects" to JSONArray().put(JSONObject(project.toString()).put("path",selected.path())))))
            stage = 1; settle(root); assertTrue(texts(root).any { it.contains("本次仅允许接收暂存") }); assertTrue(tagged(root,"device_scope_confirm").config.contains(SemanticsProperties.Disabled))
            clickTag(root,"device_scope_consent"); clickTag(root,"device_scope_confirm"); assertEquals(0,confirmations)
            clickTag(root,"device_scope_project_import_original-receive"); assertEquals("original-receive",receivedId)
            assertTrue(tagged(root,"device_scope_project_import_confirm").config.contains(SemanticsProperties.Disabled))
            assertTrue(texts(root).any { it.contains("original-receive") }); assertTrue(texts(root).any { it.contains("target-workspace") }); assertTrue(texts(root).any { it.contains("absent") }); assertTrue(texts(root).any { it.contains("456 字节") })
            capture(root,"project-import-review")
            clickTag(root,"device_scope_manifest_$digest"); assertTrue(texts(root).contains("src/main.txt")); assertTrue(texts(root).any { it.contains("e".repeat(64)) })
            nodes(root).firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, 480f); settle(root); capture(root,"project-full-manifest")
            clickText(root,"排除项"); assertTrue(texts(root).contains(".env")); assertTrue(texts(root).any { it.contains("private") })
            clickText(root,"目录"); assertTrue(texts(root).contains("src"))
            clickTag(root,"device_scope_project_import_consent")
            // Replacing a native review resets consent, even when the manifest identity is unchanged.
            importView = JSONObject(pendingImport.toString()).put("reviewId","fresh-review"); settle(root)
            assertTrue(tagged(root,"device_scope_project_import_confirm").config.contains(SemanticsProperties.Disabled)); assertEquals(0,confirmations)
            clickTag(root,"device_scope_project_import_consent"); clickTag(root,"device_scope_project_import_confirm"); assertEquals(1,confirmations)
        } finally { finishActivity(pending) }
    }

}
