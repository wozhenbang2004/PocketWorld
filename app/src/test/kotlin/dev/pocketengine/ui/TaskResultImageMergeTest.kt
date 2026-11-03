package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskResultImageMergeTest {
    private val imageA = "data:image/jpeg;base64,QQ=="
    private val imageB = "data:image/jpeg;base64,Qg=="
    private fun task(
        at: Long = 100,
        image: String? = null,
        result: String = "1".repeat(64),
        sha: String = "a".repeat(64),
        revision: Long = 1,
        run: String = "run-a",
        epoch: Int = 1,
        projection: String = "summary"
    ): UiTask = taskFromJson(JSONObject().put("id", "task-a").put("runId", run)
        .put("coordinatorEpoch", epoch).put("updatedAt", at).put("status", "needs_confirmation")
        .put("taskProjection", projection).put("resultRevision", revision).put("resultImageDigest", result)
        .put("evidence", JSONObject().put("imageFile", "task-a.jpg").put("imageSha256", sha).apply {
            if (image != null) put("imageDataUrl", image)
        }))

    private fun image(task: UiTask): String = task.raw.optJSONObject("evidence")?.optString("imageDataUrl") ?: ""

    @Test fun freshIncomingImageReplacesCachedImageAtTheSameReusablePath() {
        val old = task(image = imageA, projection = "detail_window")
        val incoming = task(at = 101, image = imageB, result = "2".repeat(64), sha = "b".repeat(64), revision = 2, projection = "detail_window")
        assertEquals(imageB, image(mergeTaskSummary(incoming, old)))
        assertEquals(imageA, image(old))
    }

    @Test fun incomingImageIsNeverOverwrittenEvenWhenAllIdentityFieldsMatch() {
        assertEquals(imageB, image(mergeTaskSummary(task(image = imageB), task(image = imageA))))
    }

    @Test fun exactNativeResultSummaryCanRetainItsLoadedImageAcrossAuditUpdates() {
        val incoming = task(at = 101)
        assertEquals(imageA, image(mergeTaskSummary(incoming, task(image = imageA, projection = "detail_window"))))
        assertFalse(incoming.raw.getJSONObject("evidence").has("imageDataUrl"))
    }

    @Test fun filenameAndTimestampAloneCannotRetainAnImage() {
        val incoming = task(at = 101).apply { raw.remove("resultImageDigest") }
        assertEquals("", image(mergeTaskSummary(incoming, task(image = imageA))))
    }

    @Test fun differentResultDigestOrRevisionClearsTheImageEvenIfItsBytesAreUnchanged() {
        val old = task(image = imageA)
        assertEquals("", image(mergeTaskSummary(task(at = 101, result = "2".repeat(64)), old)))
        assertEquals("", image(mergeTaskSummary(task(at = 101, revision = 2), old)))
    }

    @Test fun differentScreenshotDigestCannotBorrowTheCachedImage() {
        assertEquals("", image(mergeTaskSummary(task(at = 101, sha = "b".repeat(64)), task(image = imageA))))
    }

    @Test fun changedRunOrEpochCannotBorrowAnImageEvenWithMatchingProjectedDigests() {
        val old = task(image = imageA)
        assertEquals("", image(mergeTaskSummary(task(at = 101, run = "run-b"), old)))
        assertEquals("", image(mergeTaskSummary(task(at = 101, epoch = 2), old)))
    }

    @Test fun noScreenshotClearsTheImageIncludingTheEqualTimeRetainedDetailBranch() {
        val old = task(image = imageA, projection = "detail_window")
        for (at in listOf(100L, 101L)) {
            val incoming = task(at = at).apply { raw.put("evidence", JSONObject()).remove("resultImageDigest") }
            assertEquals("", image(mergeTaskSummary(incoming, old)))
            val noEvidence = task(at = at).apply { raw.remove("evidence"); raw.remove("resultImageDigest") }
            val merged = mergeTaskSummary(noEvidence, old)
            assertEquals("", image(merged))
            assertEquals("", merged.raw.optJSONObject("evidence")?.optString("imageFile") ?: "")
        }
    }

    @Test fun equalTimeSummaryRetainsOnlyTheExactResultsImageAndBoundedDetailWindow() {
        val old = task(image = imageA, projection = "detail_window").apply {
            raw.put("events", JSONArray().put(JSONObject().put("type", "tool")))
            raw.getJSONObject("evidence").put("nativeResultReceipt", "original")
        }
        val exact = mergeTaskSummary(task(), old)
        assertEquals(imageA, image(exact))
        assertEquals("detail_window", exact.raw.getString("taskProjection"))
        assertEquals(1, exact.raw.getJSONArray("events").length())
        assertEquals("original", exact.raw.getJSONObject("evidence").getString("nativeResultReceipt"))
        val changed = mergeTaskSummary(task(result = "2".repeat(64), revision = 2), old)
        assertEquals("", image(changed))
        assertFalse(changed.raw.getJSONObject("evidence").has("nativeResultReceipt"))
    }

    @Test fun detailReadWithoutBytesDoesNotRestoreAnOldImageFromSummaryIdentity() {
        assertEquals("", image(mergeTaskSummary(task(at = 101, projection = "detail_window"), task(image = imageA))))
    }

    @Test fun missingOrMalformedNativeIdentityDoesNotAuthorizeCacheReuse() {
        val old = task(image = imageA)
        for (key in listOf("resultRevision", "resultImageDigest")) {
            val incoming = task(at = 101).apply { raw.remove(key) }
            assertEquals("", image(mergeTaskSummary(incoming, old)))
        }
        assertEquals("", image(mergeTaskSummary(task(at = 101, result = "same filename"), old)))
        assertEquals("", image(mergeTaskSummary(task(at = 101, sha = ""), old)))
    }

    @Test fun freshImageForANewRunIsAcceptedAndAnOlderTaskCannotReplaceCurrentState() {
        val old = task(image = imageA)
        assertEquals(imageB, image(mergeTaskSummary(task(at = 101, image = imageB, run = "run-b", epoch = 2), old)))
        assertSame(old, mergeTaskSummary(task(at = 99, image = imageB), old))
    }
}
