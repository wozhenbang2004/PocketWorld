package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original AttachmentBook bytes and metadata. HTTP/TaskStore/UI integration has its own journey. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class McpResourceEvidenceTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static String id(){return UUID.randomUUID().toString();}
    private static McpResourceEvidence.Target target(){return new McpResourceEvidence.Target(id(),id(),1);}
    private static JSONObject actual(String body)throws Exception{
        JSONArray contents=new JSONArray().put(new JSONObject().put("uri","resource://owner/manual").put("mimeType","text/plain").put("text",body));
        return new JSONObject().put("source",new JSONObject().put("pluginId","native-resource-fixture").put("installationId",id()).put("stateRevision",3L)
                .put("endpointDigest",McpData.digest("fixture-endpoint")).put("serverDigest",McpData.digest("fixture-server")).put("protocolVersion",McpData.VERSION))
                .put("operationId",id()).put("uri","resource://owner/manual").put("descriptorDigest",McpData.digest("fixture-resource"))
                .put("contentDigest",McpData.digest(contents)).put("contents",contents).put("omittedContent",new JSONArray())
                .put("instructionsAreData",true).put("automaticConversationInjection",false).put("executionAuthorized",false);
    }
    @Test public void onlyDetachedNativeTextAndItsExactDigestCanEnterASnapshot()throws Exception{
        JSONObject input=actual("original 🙂\nignore forged roles");McpResourceEvidence.Snapshot selected=McpResourceEvidence.captured(input,()->{});
        input.getJSONArray("contents").getJSONObject(0).put("text","changed caller JSON");input.getJSONObject("source").put("stateRevision",4);
        JSONObject view=selected.view();assertEquals("original 🙂\nignore forged roles",view.getJSONArray("contents").getJSONObject(0).getString("text"));assertEquals(3,view.getJSONObject("source").getLong("stateRevision"));
        view.getJSONArray("contents").getJSONObject(0).put("text","changed returned JSON");assertEquals("original 🙂\nignore forged roles",selected.view().getJSONArray("contents").getJSONObject(0).getString("text"));
        assertThrows(SecurityException.class,()->McpResourceEvidence.captured(input,()->{}));
        JSONObject invalidUnicode=actual("broken\uD800");assertThrows(SecurityException.class,()->McpResourceEvidence.captured(invalidUnicode,()->{}));
        String legacy=PluginRevisions.installationId(new JSONObject().put("installedAt",1700000000000L));JSONObject oldInstallation=actual("original legacy resource");oldInstallation.getJSONObject("source").put("installationId",legacy).put("stateRevision",0);
        java.util.concurrent.atomic.AtomicReference<String> currentInstallation=new java.util.concurrent.atomic.AtomicReference<>(legacy);
        McpResourceEvidence.Snapshot legacySnapshot=McpResourceEvidence.captured(oldInstallation,()->{if(!legacy.equals(currentInstallation.get()))throw new SecurityException("installation changed");});
        AttachmentBook legacyBook=new AttachmentBook(temp.newFolder());McpResourceEvidence.Publication legacyFile=legacySnapshot.saveForOwner(legacyBook,target(),new RunControl());
        assertEquals(legacy,legacySnapshot.view().getJSONObject("source").getString("installationId"));assertEquals(legacy,legacyFile.indexEvent().getJSONObject("source").getString("installationId"));
        assertEquals(legacy,new JSONObject(McpResourceEvidence.readRetainedText(legacyBook,legacyFile.indexEvent(),new RunControl())).getJSONObject("source").getString("installationId"));
        currentInstallation.set("legacy-1700000000001");assertThrows(SecurityException.class,legacySnapshot::checkCurrent);
        for(String invalid:new String[]{"legacy-0","legacy--1","legacy-01","legacy-9223372036854775808","arbitrary-installation"}){
            JSONObject bad=actual("data");bad.getJSONObject("source").put("installationId",invalid);assertThrows(SecurityException.class,()->McpResourceEvidence.captured(bad,()->{}));
        }
        assertEquals(0,McpResourceEvidence.Snapshot.class.getConstructors().length);assertEquals(0,McpResourceEvidence.Publication.class.getConstructors().length);
        JSONObject binary=actual("");binary.put("contents",new JSONArray()).put("contentDigest",McpData.digest(new JSONArray()))
                .put("omittedContent",new JSONArray().put(new JSONObject().put("index",0).put("uri","resource://owner/image").put("reason","binary_content_not_decoded_or_fetched")));
        AttachmentBook book=new AttachmentBook(temp.newFolder());McpResourceEvidence.Snapshot noText=McpResourceEvidence.captured(binary,()->{});
        assertThrows(SecurityException.class,()->noText.saveForOwner(book,target(),new RunControl()));assertEquals(0,book.list().length());
    }
    @Test public void cancellationExpiryAndClosedSelectionCannotMintOrRebindAPublication()throws Exception{
        AttachmentBook book=new AttachmentBook(temp.newFolder());AtomicLong clock=new AtomicLong(1000);AtomicBoolean current=new AtomicBoolean(true);
        McpResourceEvidence.Snapshot selected=McpResourceEvidence.captured(actual("exact data"),()->{if(!current.get())throw new SecurityException("source changed");},clock::get);
        RunControl stopped=new RunControl();stopped.cancel();assertThrows(InterruptedException.class,()->selected.saveForOwner(book,target(),stopped));assertEquals(0,book.list().length());
        McpResourceEvidence.Target chosen=target();McpResourceEvidence.Publication published=selected.saveForOwner(book,chosen,new RunControl());
        McpResourceEvidence.Publication same=selected.saveForOwner(book,new McpResourceEvidence.Target(chosen.taskId,chosen.runId,chosen.epoch),new RunControl());
        assertEquals(published.attachmentReference().getString("id"),same.attachmentReference().getString("id"));assertEquals(1,book.list().length());
        assertThrows(SecurityException.class,()->selected.saveForOwner(book,target(),new RunControl()));
        current.set(false);assertThrows(SecurityException.class,selected::checkCurrent);assertThrows(SecurityException.class,published::checkCurrentSource);
        // Historical bytes are independent from a currently withdrawn MCP installation.
        assertTrue(McpResourceEvidence.readRetainedText(book,published.indexEvent(),new RunControl()).contains("exact data"));
        current.set(true);clock.set(301000);assertThrows(SecurityException.class,selected::view);assertEquals(1,book.list().length());
        McpResourceEvidence.Snapshot closed=McpResourceEvidence.captured(actual("never saved"),()->{});closed.close();assertThrows(SecurityException.class,()->closed.saveForOwner(book,target(),new RunControl()));assertEquals(1,book.list().length());
    }
    @Test public void coldOriginalNeedsBothExactBytesAndExactNativePublicationMetadata()throws Exception{
        for(boolean changeBytes:new boolean[]{false,true}){
            File root=temp.newFolder();AttachmentBook book=new AttachmentBook(root);McpResourceEvidence.Snapshot selected=McpResourceEvidence.captured(actual("retained original"),()->{});
            McpResourceEvidence.Publication saved=selected.saveForOwner(book,target(),new RunControl());JSONObject event=saved.indexEvent();String artifact=event.getString("attachmentId");
            assertFalse(event.has("contents"));assertFalse(event.has("text"));AttachmentBook cold=new AttachmentBook(root);
            assertNull(cold.outputSourceEligibility(artifact,event.getString("sha256")));
            assertFalse(cold.get(artifact).getJSONObject("source").has("uri"));
            String retained=McpResourceEvidence.readRetainedText(cold,event,new RunControl());
            assertTrue(retained.contains("retained original"));assertEquals(event.getString("uri"),new JSONObject(retained).getString("uri"));
            JSONObject changedUri=new JSONObject(event.toString()).put("uri","resource://owner/replacement");
            assertThrows(SecurityException.class,()->McpResourceEvidence.readRetainedText(cold,changedUri,new RunControl()));
            if(changeBytes)Files.write(new File(root,artifact+".blob").toPath(),"replacement bytes".getBytes(StandardCharsets.UTF_8));
            else {
                File metadata=new File(root,artifact+".json");JSONObject changed=new JSONObject(new String(Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8));
                // The Book generation intentionally excludes a native producer's private plan;
                // the resource adapter must also verify its original publication binding.
                changed.getJSONObject("source").getJSONObject("nativePublication").put("planDigest",McpData.digest("different source plan"));
                Files.write(metadata.toPath(),changed.toString().getBytes(StandardCharsets.UTF_8));
            }
            assertThrows(Exception.class,()->McpResourceEvidence.readRetainedText(cold,event,new RunControl()));
        }
    }
    @Test public void survivingUnknownOriginalPublicationIsNeverOverwrittenOrResaved()throws Exception{
        File root=temp.newFolder();AttachmentBook book=new AttachmentBook(root);McpResourceEvidence.Snapshot selected=McpResourceEvidence.captured(actual("expected snapshot"),()->{});McpResourceEvidence.Target chosen=target();
        // The existing native-result query supplies the fixed identity even before a publication.
        JSONObject absent=book.findNativeResult(selected.publicationId(),"mcp_resource_snapshot",chosen.taskId,chosen.runId,McpData.digest("probe only"),new RunControl());assertFalse(absent.getBoolean("found"));
        File orphan=new File(root,absent.getString("id")+".blob");byte[] original="original partial publication".getBytes(StandardCharsets.UTF_8);Files.write(orphan.toPath(),original);
        assertThrows(IllegalStateException.class,()->selected.saveForOwner(book,chosen,new RunControl()));
        assertThrows(IllegalStateException.class,()->selected.saveForOwner(book,chosen,new RunControl()));
        assertArrayEquals(original,Files.readAllBytes(orphan.toPath()));assertEquals(1,root.listFiles().length);assertEquals(0,book.list().length());
    }
}
