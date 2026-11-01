package dev.pocketengine.ui

import android.content.Intent
import android.os.Looper
import dev.pocketengine.EngineApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

/** Actual Activity notification entry; it opens the existing account page and starts no login/task. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, shadows = [OfficialAarResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountNotificationEntryUiTest {
    @Before fun setup() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        java.security.Security.addProvider(object : java.security.Provider("AccountEntryKeys", 1.0, "Explicit local fixture key") {
            init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
        })
    }
    @After fun cleanup() { java.security.Security.removeProvider("AccountEntryKeys") }
    @Test fun fixedNotificationExtraIsConsumedAndOnlyNavigatesToExistingAccounts() {
        val intent = Intent().putExtra("openChatGptAccounts", true)
        val pending = Robolectric.buildActivity(ComposeActivity::class.java, intent)
        pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible()
        val field = ComposeActivity::class.java.getDeclaredField("controller"); field.isAccessible = true
        val controller = field.get(activity.get()) as PocketWorldController
        val app = activity.get().application as EngineApplication
        try {
            assertEquals("accounts", controller.state.requestedConnectionSection)
            assertEquals(1, controller.state.requestedConnectionRevision)
            val consumed = activity.get().intent
            assertFalse(consumed.hasExtra("openChatGptAccounts")); assertEquals(0, app.tasks.list().length())
            activity.newIntent(consumed)
            assertEquals("A consumed old notification does not replay navigation", 1, controller.state.requestedConnectionRevision)
            val next = Intent().putExtra("openChatGptAccounts", true)
            activity.newIntent(next)
            assertEquals(2, controller.state.requestedConnectionRevision)
            assertFalse(activity.get().intent.hasExtra("openChatGptAccounts")); assertEquals(0, app.tasks.list().length())
            assertFalse(app.nativeOwnerWorkflows.accountSnapshot().optJSONObject("login")?.optString("status") in setOf("pending", "exchanging"))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        } finally { activity.pause().stop().destroy(); app.features.close() }
    }
}
