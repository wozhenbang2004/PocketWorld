package dev.pocketengine.ui.memory

import android.app.Application
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.memory.FinalAnswerSources
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class FinalAnswerSourceReadCancellationTest {
    private fun request() = JSONObject().put("readId", UUID.randomUUID().toString()).put("answer", JSONObject()
        .put("taskId", UUID.randomUUID().toString()).put("runId", UUID.randomUUID().toString())
        .put("epoch", 1).put("answerFingerprint", "a".repeat(64)))
    private fun response(request: JSONObject) = JSONObject().put("format", FinalAnswerSources.FORMAT)
        .put("readId", request.getString("readId")).put("answer", JSONObject(request.getJSONObject("answer").toString()))
        .put("status", "current")

    @Test fun closingOrChangingSelectionCancelsTheNativeReadAndDropsItsLateReturn() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CompletableDeferred<RunControl>()
        val release = CountDownLatch(1)
        val returned = AtomicInteger()
        try {
            val request = request()
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                readFinalAnswerSourceRequest(request, executor::execute) { actual, control ->
                    entered.complete(control)
                    check(release.await(3, TimeUnit.SECONDS))
                    returned.incrementAndGet()
                    response(actual)
                }
            }
            val original = withTimeout(2000) { entered.await() }
            old.cancelAndJoin()
            assertTrue(original.isCancelled)
            val next = request()
            val current = readFinalAnswerSourceRequest(next, Runnable::run) { actual, _ -> response(actual) }
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(1, returned.get())
            assertTrue(old.isCancelled)
            assertEquals(next.getString("readId"), current.getString("readId"))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun oldReadIdOrAnotherRunResponseCannotReplaceTheSelectedAnswer() = runBlocking {
        val request = request()
        for (wrongReadId in listOf(true, false)) {
            val failed = runCatching {
                readFinalAnswerSourceRequest(request, Runnable::run) { actual, _ ->
                    response(actual).also {
                        if (wrongReadId) it.put("readId", UUID.randomUUID().toString())
                        else it.getJSONObject("answer").put("runId", UUID.randomUUID().toString())
                    }
                }
            }
            assertTrue(failed.exceptionOrNull() is IllegalStateException)
        }
    }
}
