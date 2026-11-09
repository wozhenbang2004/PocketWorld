package dev.pocketengine.core;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class DeveloperLogArchiveTest {
    private static JSONObject safe() {
        RuntimeDiagnostics.Recorder producer = new RuntimeDiagnostics.Recorder(null, null);
        producer.record("model_request", "failed", "MODEL_UNAVAILABLE", null, null,
            UUID.randomUUID().toString(), 17, new IllegalStateException("Bearer sk-NEVER-STORE"));
        return producer.snapshot().getJSONArray("events").getJSONObject(0);
    }

    @Test public void unlimitedUiHistoryIsPagedWithoutGrowingItsResult() throws Exception {
        File folder = Files.createTempDirectory("pw-dev-log").toFile();
        String first = "", last = "";
        try (DeveloperLogArchive logs = new DeveloperLogArchive(folder)) {
            JSONObject prototype = safe();
            for (int i = 0; i < 1800; i++) {
                JSONObject row = new JSONObject(prototype.toString())
                    .put("eventId", UUID.randomUUID().toString()).put("time", 1700000000000L + i);
                if (i == 0) first = row.getString("eventId");
                if (i == 1799) last = row.getString("eventId");
                logs.append(row.toString());
            }
            JSONObject tail = logs.page("tail", "", 150);
            assertEquals(150, tail.getJSONArray("entries").length());
            assertEquals(last, tail.getJSONArray("entries").getJSONObject(149).getString("eventId"));
            assertTrue(tail.getBoolean("hasOlder"));
            JSONObject older = logs.page("before", tail.getString("older"), 150);
            assertEquals(150, older.getJSONArray("entries").length());
            assertEquals(1650, tail.getJSONArray("entries").getJSONObject(0).getLong("time") - 1700000000000L);
            assertEquals(1500, older.getJSONArray("entries").getJSONObject(0).getLong("time") - 1700000000000L);
            assertEquals(0, logs.page("after", tail.getString("next"), 150).getJSONArray("entries").length());
        }
        try (DeveloperLogArchive restored = new DeveloperLogArchive(folder)) {
            JSONObject page = restored.page("tail", "", 1);
            assertEquals(last, page.getJSONArray("entries").getJSONObject(0).getString("eventId"));
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            restored.export(destination);
            String archive = destination.toString(StandardCharsets.UTF_8);
            assertTrue(archive.contains(first));
            assertTrue(archive.contains(last));
            assertFalse(archive.contains("sk-NEVER-STORE"));
        }
    }

    @Test public void unknownFieldsAndSecretsAreNotWrittenOrExported() throws Exception {
        File folder = Files.createTempDirectory("pw-dev-log-private").toFile();
        try (DeveloperLogArchive logs = new DeveloperLogArchive(folder)) {
            JSONObject safe = safe();
            logs.append(safe.toString());
            logs.append(new JSONObject(safe.toString()).put("prompt", "very-private-secret").toString());
            JSONArray rows = logs.page("tail", "", 100).getJSONArray("entries");
            assertEquals(1, rows.length());
            assertEquals(1, logs.stats().getLong("rejectedRows"));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            logs.export(output);
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("very-private-secret"));
            logs.capacity(2048);
        }
        try (DeveloperLogArchive restored = new DeveloperLogArchive(folder)) {
            assertEquals(2048, restored.stats().getInt("capacityMiB"));
        }
    }
}
