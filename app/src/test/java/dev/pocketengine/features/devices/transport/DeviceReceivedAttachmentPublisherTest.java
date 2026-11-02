package dev.pocketengine.features.devices.transport;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.devices.DeviceProtocol;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual private bytes and ORIGINAL Book; native Current is a revocable unit fixture,
 * not proof of integrated TaskStore audience / real Android viewer execution. */
public final class DeviceReceivedAttachmentPublisherTest {
    @Test public void receivedBytesPublishOnceUnderFrozenOriginalRunAndRevocationStillWins() throws Exception {
        var root = Files.createTempDirectory("received-native-book-");
        try {
            var cacheRoot = Files.createDirectory(root.resolve("cache"));
            var cache = new DeviceArtifactTransfer(cacheRoot.toFile());
            var book = new AttachmentBook(root.resolve("book").toFile());
            var publisher = new DeviceReceivedAttachmentPublisher(cache, book);
            byte[] raw = "真实跨端成果 📄\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var exact = new DeviceArtifactTransfer.Reference(new JSONObject().put("transferId", UUID.randomUUID().toString()).put("sourceDeviceId", "pw1_" + "a".repeat(64))
                .put("artifactId", "original-file").put("versionId", "fixed-v1").put("taskId", "remote-original-task").put("runId", "remote-original-run").put("operationId", "remote-original-operation")
                .put("sha256", DeviceProtocol.sha256(raw)).put("bytes", raw.length).put("kind", "file").put("mimeType", "text/plain").put("name", "结果📄.txt"));
            boolean[] allowed = {true}; int[] checks = {0};
            DeviceArtifactTransfer.Current current = () -> { checks[0]++; if (!allowed[0]) throw new SecurityException("Native receive approval revoked"); };
            cache.freeze(exact, output -> output.write(raw), current);
            String task = "dt1_" + "b".repeat(64), originalRun = UUID.randomUUID().toString(), op = UUID.randomUUID().toString();
            RunControl first = new RunControl(); first.bindHostRun(task, originalRun, 1);
            JSONObject published = publisher.publish(exact, op, task, originalRun, 1, first, current);
            assertTrue(published.getBoolean("attachmentPublished")); assertFalse(published.getBoolean("previewOpened")); assertFalse(published.getBoolean("businessVerified"));
            String id = published.getJSONObject("attachment").getString("id");
            assertArrayEquals(raw, Files.readAllBytes(book.file(id).toPath()));
            // A resumed run queries the SAME original operation publication, not a new id.
            RunControl resumed = new RunControl(); resumed.bindHostRun(task, UUID.randomUUID().toString(), 2);
            cache.remove(exact, current);
            JSONObject prior = publisher.publish(exact, op, task, originalRun, 1, resumed, current);
            assertTrue(prior.getBoolean("originalPublication")); assertEquals(id, prior.getJSONObject("attachment").getString("id")); assertEquals(1, book.list().length());
            allowed[0] = false;
            try { publisher.publish(exact, op, task, originalRun, 1, resumed, current); fail("Revocation bypassed by cached publication"); } catch (SecurityException expected) { }
            try { book.file("dt1_" + "b".repeat(64)); fail("Task scope syntax widened blob paths"); } catch (IllegalArgumentException expected) { }
            allowed[0] = true;
            try { publisher.publish(exact, op, "../escape", originalRun, 1, resumed, current); fail("Path-like scope accepted"); } catch (DeviceProtocol.ProtocolException | SecurityException expected) { }
            assertTrue(checks[0] > 10);
        } finally { try (var files = Files.walk(root)) { for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
    }
}
