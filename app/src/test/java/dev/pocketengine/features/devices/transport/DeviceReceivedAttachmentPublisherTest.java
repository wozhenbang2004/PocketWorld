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
    @Test public void reentrantSqlStyleAuthorityStillRevokesAtBookCommitAndGuardIsReleased() throws Exception { commitBoundary(false); }
    @Test public void cancellationAtBookCommitNeverPublishes() throws Exception { commitBoundary(true); }
    private void commitBoundary(boolean cancel) throws Exception {
        var root=Files.createTempDirectory("received-commit-revocation-");
        try {
            var cache=new DeviceArtifactTransfer(Files.createDirectory(root.resolve("cache")).toFile());
            var book=new AttachmentBook(root.resolve("book").toFile());var publisher=new DeviceReceivedAttachmentPublisher(cache,book);
            byte[] raw="binary-file-at-original-book-commit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var exact=new DeviceArtifactTransfer.Reference(new JSONObject().put("transferId",UUID.randomUUID().toString()).put("sourceDeviceId","pw1_"+"a".repeat(64)).put("artifactId","fixed-file").put("versionId","v1").put("taskId","remote-task").put("requestRunId","remote-request-run").put("requestExecutorEpoch",1).put("operationId","remote-operation").put("sha256",DeviceProtocol.sha256(raw)).put("bytes",raw.length).put("kind","file").put("mimeType","application/octet-stream").put("name","file.bin"));
            cache.freeze(exact,out->out.write(raw),()->{});
            String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
            RunControl original=new RunControl();original.bindHostRun(task,run,1);boolean[] atCommit={false};int[] calls={0};
            DeviceArtifactTransfer.Current sqlStyle=()->{
                calls[0]++;
                boolean committing=java.util.Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame->frame.getClassName().equals(RunControl.class.getName())&&frame.getMethodName().equals("dispatchAtPauseState"));
                if(committing){atCommit[0]=true;if(cancel)original.cancel();else throw new SecurityException("Exact native receive approval revoked before Book commit");}
                original.check(); // Same reentrant shape as the production SQL evidence.
            };
            try {publisher.publish(exact,operation,task,run,1,original,sqlStyle);fail("Changed authority published bytes");}catch(InterruptedException|SecurityException expected){}
            assertTrue("test must reach actual Book commit",atCommit[0]);assertTrue(calls[0]>2);assertEquals(0,book.list().length());
            try(var paths=Files.list(root.resolve("book"))){assertEquals(0,paths.filter(path->path.toString().endsWith(".blob")||path.toString().endsWith(".part")).count());}
            // The failed scope must remove its callback and thread guard. A NEW live native
            // control may reconcile the same original publication; no id/version replacement.
            RunControl resumed=new RunControl();resumed.bindHostRun(task,UUID.randomUUID().toString(),2);int[] checks={0};
            JSONObject result=publisher.publish(exact,operation,task,run,1,resumed,()->{checks[0]++;resumed.check();});
            assertTrue(checks[0]>2);assertTrue(result.getBoolean("attachmentPublished"));assertArrayEquals(raw,Files.readAllBytes(book.file(result.getJSONObject("attachment").getString("id")).toPath()));
            resumed.check();assertEquals(1,book.list().length());
        } finally {try(var files=Files.walk(root)){for(var path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
    }

}
