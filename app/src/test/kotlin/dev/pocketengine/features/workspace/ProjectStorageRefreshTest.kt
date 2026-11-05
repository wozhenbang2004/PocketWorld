package dev.pocketengine.features.workspace

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.pocketengine.EngineApplication
import dev.pocketengine.ui.ProjectStorageCoordinator
import java.nio.file.Files
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** Actual coordinator, production modules and explicit fresh fixture storage; no device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectStorageRefreshTest {
    @Before fun vault() = OwnerDataArchiveFixture.installFixtureVault()
    @After fun release() = OwnerDataArchiveFixture.removeFixtureVault()
    private class QueuedExecutor : AbstractExecutorService() {
        private var stopped = false
        private val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { check(!stopped); queued.addLast(command) }
        fun runNext() = queued.removeFirst().run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return queued.toMutableList().also { queued.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queued.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private fun flow(fixture: OwnerDataArchiveFixture, io: QueuedExecutor): ProjectStorageCoordinator {
        // Attach the real Application host without invoking unrelated bootstrap/device work.
        val app = EngineApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext", from(Context::class.java, fixture.android))
        app.features = fixture.host
        return ProjectStorageCoordinator(app, io, Handler(Looper.getMainLooper()), { true }, {})
    }
    @Test fun backupFailureKeepsCompletedInventoryAndMarksBackupListUnknown() {
        OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(), Files.createTempDirectory("storage-refresh-failure-")).use { fixture ->
            Files.write(fixture.android.filesDir.toPath().resolve("owner-data-archives"), byteArrayOf(1,2,3))
            val io = QueuedExecutor(); val flow = flow(fixture, io)
            try {
                flow.open(); io.runNext(); shadowOf(Looper.getMainLooper()).idle()
                assertFalse(flow.state.busy)
                assertNotNull(flow.state.inventory)
                assertTrue(flow.state.inventory!!.has("filesystemUsableBytes"))
                assertNull(flow.state.caches)
                assertTrue(flow.state.backupError!!.contains("本机备份列表未能读取"))
                assertNull(flow.state.error)
                assertEquals(3L, Files.size(fixture.android.filesDir.toPath().resolve("owner-data-archives")))
            } finally { flow.detach(); io.shutdownNow() }
        }
    }
    @Test fun freshInstallReportsKnownEmptyBackupList() {
        OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(), Files.createTempDirectory("storage-refresh-empty-")).use { fixture ->
            val io = QueuedExecutor(); val flow = flow(fixture, io)
            try {
                flow.open(); io.runNext(); shadowOf(Looper.getMainLooper()).idle()
                assertNotNull(flow.state.inventory); assertEquals(emptyList<Any>(), flow.state.caches)
                assertNull(flow.state.backupError); assertNull(flow.state.error); assertFalse(flow.state.busy)
            } finally { flow.detach(); io.shutdownNow() }
        }
    }
    @Test fun closeAfterReadBeforeMainPublicationDiscardsResultAndReopenCanRefresh() {
        OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(), Files.createTempDirectory("storage-refresh-close-")).use { fixture ->
            val io = QueuedExecutor(); val flow = flow(fixture, io)
            try {
                flow.open(); io.runNext(); flow.closePage(); shadowOf(Looper.getMainLooper()).idle()
                assertFalse(flow.state.open); assertFalse(flow.state.busy)
                assertNull(flow.state.inventory); assertNull(flow.state.caches); assertNull(flow.state.backupError)
                flow.open(); io.runNext(); shadowOf(Looper.getMainLooper()).idle()
                assertTrue(flow.state.open); assertNotNull(flow.state.inventory); assertNotNull(flow.state.caches)
            } finally { flow.detach(); io.shutdownNow() }
        }
    }
    @Test fun backgroundAfterReadDiscardsBackupErrorAndInventoryPublication() {
        OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(), Files.createTempDirectory("storage-refresh-background-")).use { fixture ->
            Files.write(fixture.android.filesDir.toPath().resolve("owner-data-archives"), byteArrayOf(7))
            val io = QueuedExecutor(); val flow = flow(fixture, io)
            try {
                flow.open(); io.runNext(); flow.foreground(false); shadowOf(Looper.getMainLooper()).idle()
                assertFalse(flow.state.busy); assertNull(flow.state.inventory)
                assertNull(flow.state.caches); assertNull(flow.state.backupError); assertNull(flow.state.error)
            } finally { flow.detach(); io.shutdownNow() }
        }
    }
}
