package dev.pocketengine.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.features.proxy.providers.ChatGptProvider
import dev.pocketengine.features.proxy.accounts.ChatGptRecovery
import dev.pocketengine.features.proxy.accounts.GatewayCall
import dev.pocketengine.features.proxy.providers.ResponsesRequest
import dev.pocketengine.runtime.ModelGateway
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real composer/Settings selection; the explicitly installed native metadata-port fixture
 * verifies UI interpretation only. AccountGateway's persisted hold proof is tested in A21. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, qualifiers = "w393dp-h852dp-mdpi", shadows = [OfficialAarResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class AccountComposerSourceUiTest {
    @Before fun setup() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        java.security.Security.addProvider(object : java.security.Provider("AccountComposerKeys", 1.0, "Explicit local fixture key") {
            init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
        })
    }
    @After fun cleanup() { ModelGateway.installAccountInferencePort(null); java.security.Security.removeProvider("AccountComposerKeys") }
    private fun root(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) root(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun nodes(activity: ComposeActivity): List<SemanticsNode> = root(activity.window.decorView)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true).orEmpty()
    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text } else ""
    private fun frame(activity: ComposeActivity) {
        fun layout(view: View) { if (view is RootForTest) view.measureAndLayoutForTest(); if (view is ViewGroup) for (i in 0 until view.childCount) layout(view.getChildAt(i)) }
        repeat(4) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)); layout(activity.window.decorView) }
    }
    private fun await(activity: ComposeActivity, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) frame(activity)
        frame(activity); assertTrue("Actual account composer state", condition())
    }
    private fun draft(activity: ComposeActivity, value: String) {
        val composer = nodes(activity).first { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "conversation_composer" }
        assertTrue(composer.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame(activity)
    }
    @Test fun typedExactUsageHoldMakesManageUsagePrimaryWithoutStartingOrSwitchingInference() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val reference = ModelConfig.AccountModelRef.fromJson(JSONObject().put("provider", ModelConfig.ACCOUNT_PROVIDER).put("accountId", UUID.randomUUID().toString()).put("accountVersion", 1).put("modelId", "explicit-account-ui-fixture"))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        ModelGateway.installAccountInferencePort(object : ModelGateway.AccountInferencePort {
            override fun planUsageLimited(selected: ModelConfig.AccountModelRef) = reference.matches(selected)
            override fun stream(selected: ModelConfig.AccountModelRef, request: ResponsesRequest, operation: GatewayCall, sink: ChatGptProvider.EventSink) { calls.incrementAndGet(); throw AssertionError("This UI journey authorizes no inference request") }
        })
        app.settings.selectAccountModel(reference.toJson())
        val pending = Robolectric.buildActivity(ComposeActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val control = pending.setup().visible().windowFocusChanged(true); val activity = control.get()
        try {
            await(activity) { nodes(activity).any { text(it) == "使用 ChatGPT 套餐" } }
            draft(activity, "保留这段尚未发送的草稿")
            assertTrue(nodes(activity).any { text(it) == "当前用量受限" })
            val send = nodes(activity).first { text(it) == "发送" && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(send.config.contains(SemanticsProperties.Disabled))
            val usage = nodes(activity).first { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "composer_chatgpt_usage" }
            assertFalse(usage.config.contains(SemanticsProperties.Disabled)); assertTrue(usage.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(ChatGptRecovery.MANAGE_USAGE_URL, Shadows.shadowOf(activity).nextStartedActivity.data.toString())
            assertEquals(0, calls.get()); assertEquals(0, app.tasks.list().length())
            assertTrue(app.settings.advanceAccountModel(reference, 2))
            draft(activity, "原草稿仍未发送；精确授权版本已由本机推进")
            assertFalse("An old exact-version hold is not inferred for a successor", nodes(activity).any { text(it) == "当前用量受限" })
            assertTrue(nodes(activity).any { text(it) == "使用 ChatGPT 套餐" }); assertEquals(0, calls.get()); assertEquals(0, app.tasks.list().length())
            assertEquals(2L, app.settings.selectedAccountModel().accountVersion)
        } finally { control.pause().stop().destroy(); app.features.close() }
    }
}
