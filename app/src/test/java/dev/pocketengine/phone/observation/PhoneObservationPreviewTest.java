package dev.pocketengine.phone.observation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneObservationPreviewTest {
    private static JSONObject j(Object... pairs) throws Exception {
        JSONObject value = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }
    private static PhoneObservationPreview.Identity identity() { return new PhoneObservationPreview.Identity("task", "run", 3); }
    private static JSONObject observation() throws Exception {
        // A real one-pixel PNG fixture; these host tests validate evidence classification, not Android rendering.
        return j("taskId", "task", "runId", "run", "coordinatorEpoch", 3, "ok", true,
                "executionMode", "background", "displayId", 8, "sessionId", "original-display", "observationId", "original-observation",
                "packageName", "dev.pocketengine", "observedAtUptimeMs", 1000L, "screenWidth", 1, "screenHeight", 1,
                "imageWidth", 1, "imageHeight", 1, "imageMimeType", "image/png",
                "imageBase64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9WQAAAAASUVORK5CYII=");
    }
    @Test public void optionalOriginalOwnerCallIsKeptButPartialAndCoercedNewIdentitiesAreRejected() throws Exception {
        assertNull(PhoneObservationPreview.expected(j("taskId", "task")));
        assertEquals(3, PhoneObservationPreview.expected(identity().request()).epoch);
        for (Object invalid : new Object[]{"3", 3.0, 3.5, -1, (long) Integer.MAX_VALUE + 1, true, JSONObject.NULL}) {
            JSONObject input = identity().request().put("expectedEpoch", invalid);
            assertThrows(IllegalArgumentException.class, () -> PhoneObservationPreview.expected(input));
        }
        for (String missing : new String[]{"expectedRunId", "expectedEpoch", "taskId"}) {
            JSONObject input = identity().request(); input.remove(missing);
            assertThrows(IllegalArgumentException.class, () -> PhoneObservationPreview.expected(input));
        }
        assertThrows(IllegalArgumentException.class, () -> PhoneObservationPreview.expected(identity().request().put("expectedRunId", "")));
        assertThrows(IllegalArgumentException.class, () -> PhoneObservationPreview.expected(identity().request().put("expectedRunId", 42)));
    }
    @Test public void onlyTheOriginalTaskRunAndEpochMayDisplayTheFrame() throws Exception {
        JSONObject task = j("id", "task", "runId", "run", "coordinatorEpoch", 3);
        assertTrue(identity().matchesTask(task));
        assertFalse(identity().matchesTask(new JSONObject(task.toString()).put("coordinatorEpoch", "3")));
        assertFalse(identity().matchesTask(new JSONObject(task.toString()).put("runId", "next")));
        for (String key : new String[]{"taskId", "runId", "coordinatorEpoch"}) {
            JSONObject frame = observation().put(key, key.equals("coordinatorEpoch") ? 4 : "other");
            var result = PhoneObservationPreview.present(identity(), frame, 1100);
            assertFalse(result.hasFrame()); assertEquals("original_run_changed", result.reason());
        }
        var result = PhoneObservationPreview.present(identity(), observation(), 1100);
        assertTrue(result.hasFrame()); assertTrue(result.fresh()); assertEquals("dev.pocketengine", result.packageName());
    }
    @Test public void emptyUnavailableMainScreenAndHandedOffContentNeverCreateAPreviewWindow() throws Exception {
        assertFalse(PhoneObservationPreview.present(identity(), null, 1100).hasFrame());
        for (JSONObject frame : new JSONObject[]{
                observation().put("packageName", ""), observation().put("frameEmpty", true),
                observation().put("screenState", "display_has_no_frame"), observation().put("imageUnavailable", true),
                observation().put("imageBase64", "AAAA"), observation().put("executionMode", "main_screen"),
                observation().put("displayId", 0), observation().put("sessionId", ""),
                observation().put("imageWidth", 2), observation().put("screenWidth", 2),
                observation().put("ownerHandoff", j("state", "main")), observation().put("ownerHandoff", j("state", "unknown"))}) {
            assertFalse(frame.toString(), PhoneObservationPreview.present(identity(), frame, 1100).hasFrame());
        }
    }
    @Test public void oldFramesAndUnverifiableClocksStayExplicitlyStaleWithoutChangingTheEvidence() throws Exception {
        JSONObject frame = observation(); String original = frame.toString();
        assertTrue(PhoneObservationPreview.present(identity(), frame, 121_000).fresh());
        var expired = PhoneObservationPreview.present(identity(), frame, 121_001);
        assertTrue(expired.hasFrame()); assertFalse(expired.fresh()); assertEquals("stale_observation", expired.reason());
        assertFalse(PhoneObservationPreview.present(identity(), frame, 999).fresh());
        assertFalse(PhoneObservationPreview.present(identity(), new JSONObject(frame.toString()).put("observedAtUptimeMs", "1000"), 1100).fresh());
        assertEquals(original, frame.toString());
    }
}
