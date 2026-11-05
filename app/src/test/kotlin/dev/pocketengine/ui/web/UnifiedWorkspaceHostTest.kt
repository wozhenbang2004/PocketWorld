package dev.pocketengine.ui.web

import android.os.Bundle
import android.view.ViewGroup
import dev.pocketengine.EngineApplication
import dev.pocketengine.testutil.ReadyApplicationFixture
import dev.pocketengine.ui.ComposeActivity
import java.security.Provider
import java.security.Security
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Restoring an older installation must not restore a second presentation or replay its request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UnifiedWorkspaceHostTest {
    private val keys = object : Provider("UnifiedWorkspace-${UUID.randomUUID()}", 1.0, "Explicit test-only AndroidKeyStore") {
        init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
    }

    @Before fun installKeys() { assertTrue(Security.insertProviderAt(keys, 1) > 0) }
    @After fun removeKeys() { Security.removeProvider(keys.name) }

    @Test fun oldNativeWorkspaceSelectionRestoresTheSingleWebHostAndItsOriginalDraft() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        ReadyApplicationFixture.initializeHost(app)
        val saved = Bundle().apply {
            putBoolean("nativeWorkspace", true)
            putString("libraryWebPresentation", JSONObject()
                .put("phoneDraft", "保留原手机任务草稿")
                .put("phoneDraftRevision", 7)
                .put("phoneIntent", true)
                .put("phoneExecutionMode", "background")
                .put("phoneStartReceipt", JSONObject().put("requestId", "original-request").put("status", "pending"))
                .toString())
        }
        val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        try {
            val activity = owner.create(saved).get()
            val host = ComposeActivity::class.java.getDeclaredField("webHost").apply { isAccessible = true }.get(activity) as PocketWebHost
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertEquals(1, content.childCount)
            assertSame("Legacy native selection must mount the same Web host", host.view, content.getChildAt(0))

            val restored = Bundle()
            owner.saveInstanceState(restored)
            assertFalse("Removed presentation choices must not be saved again", restored.containsKey("nativeWorkspace"))
            val presentation = JSONObject(requireNotNull(restored.getString("libraryWebPresentation")))
            assertEquals("保留原手机任务草稿", presentation.getString("phoneDraft"))
            assertEquals(7L, presentation.getLong("phoneDraftRevision"))
            assertTrue(presentation.getBoolean("phoneIntent"))
            assertEquals("background", presentation.getString("phoneExecutionMode"))
            val receipt = presentation.getJSONObject("phoneStartReceipt")
            assertEquals("original-request", receipt.getString("requestId"))
            assertEquals("An interrupted original request requires a query, not automatic resubmission", "unknown", receipt.getString("status"))
        } finally {
            try { owner.close() } finally { ReadyApplicationFixture.closeHost(app) }
        }
    }
    @Test fun workspaceConsoleNeverExportsPrivateBodiesOrSourceUrls() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        ReadyApplicationFixture.initializeHost(app)
        val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        val diagnostics = mutableListOf<String>()
        try {
            val activity = owner.create().get()
            val host = ComposeActivity::class.java.getDeclaredField("webHost").apply { isAccessible = true }.get(activity) as PocketWebHost
            val web = PocketWebHost::class.java.getDeclaredField("web").apply { isAccessible = true }.get(host) as android.webkit.WebView
            val client = requireNotNull(web.webChromeClient)
            dev.pocketengine.core.RuntimeDiagnostics.install(null) { diagnostics.add(it) }
            org.robolectric.shadows.ShadowLog.clear()
            val secret = "prompt-private Bearer sk-private https://private.example/account"
            assertTrue(client.onConsoleMessage(android.webkit.ConsoleMessage(secret, secret, 42, android.webkit.ConsoleMessage.MessageLevel.ERROR)))
            assertTrue(client.onConsoleMessage(android.webkit.ConsoleMessage("PocketWebUi $secret", secret, 43, android.webkit.ConsoleMessage.MessageLevel.LOG)))
            val encoded = diagnostics.joinToString() + org.robolectric.shadows.ShadowLog.getLogs().joinToString { it.msg }
            assertFalse(encoded.contains("prompt-private"))
            assertFalse(encoded.contains("sk-private"))
            assertFalse(encoded.contains("private.example"))
            assertEquals(1, diagnostics.size)
            assertEquals("CONSOLE_ERROR", JSONObject(diagnostics.single()).getString("code"))
        } finally {
            dev.pocketengine.core.RuntimeDiagnostics.install(null, null)
            try { owner.close() } finally { ReadyApplicationFixture.closeHost(app) }
        }
    }
}
