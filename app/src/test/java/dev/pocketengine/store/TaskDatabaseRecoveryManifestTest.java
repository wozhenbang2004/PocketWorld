package dev.pocketengine.store;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real private files and JSON, without Android substitutes or an in-memory SQLite surrogate. */
public class TaskDatabaseRecoveryManifestTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String REPAIR="d27d608c-f4af-4bfd-b9dd-9e6219a85147",SOURCE="a".repeat(64),GENERATION="9007199254740993";

    private final class Fixture {
        final File directory=temporary.newFolder().getCanonicalFile();
        final byte[] export;
        final JSONObject summary;
        Fixture(boolean omission)throws Exception {
            String body="原会话中可验证的文字";
            JSONArray omissions=new JSONArray();if(omission)omissions.put(new JSONObject().put("sourceRecordId","message-1").put("reason","attachment_bytes_and_grants_not_exported"));
            export=new JSONObject().put("schema","pocketworld_conversation_export").put("schemaVersion",1).put("exportId",UUID.randomUUID().toString()).put("sourceConversationId","source-one").put("sourceGeneration",GENERATION).put("historyWatermark",1).put("exportedAt",1).put("title","会话")
                .put("messages",new JSONArray().put(new JSONObject().put("sourceRecordId","message-1").put("sourceRecordVersion",1).put("sequence",1).put("role","user").put("content",body).put("contentSha256",DatabaseFilePreservation.sha256(body.getBytes(StandardCharsets.UTF_8))).put("sourceKind","native_original")))
                .put("omitted",omissions).put("attachmentReferences",new JSONArray()).toString().getBytes(StandardCharsets.UTF_8);
            JSONObject row=new JSONObject().put("sourceConversationId","source-one").put("restoredConversationId","new-conversation-one").put("messageCount",1).put("omittedCount",omission?1:0).put("exportSha256",DatabaseFilePreservation.sha256(export)).put("exportBytes",export.length);
            JSONArray gaps=new JSONArray();if(omission)gaps.put(new JSONObject().put("sourceConversationId","source-one").put("reason","attachment_bytes_and_grants_not_exported").put("recordCount",1));
            summary=new JSONObject().put("section","conversations").put("sourceConversationCount",1).put("conversationCount",1).put("messageCount",1).put("omittedCount",omission?1:0).put("unreadableConversationCount",0).put("partial",omission).put("contentUtf8Bytes",export.length).put("sourceFingerprint",SOURCE).put("sourceGeneration",GENERATION).put("conversations",new JSONArray().put(row)).put("gaps",gaps)
                .put("sourceReadOnly",true).put("preservedSourceOpenedInSQLite",false).put("restoresExistingIds",false).put("restoresActiveGrants",false).put("restoresTaskHistory",false).put("restoresAttachmentBytes",false).put("restoresCredentialsOrPairings",false).put("omissionMeaning",TaskDatabaseRecoveryManifest.OMISSION_MEANING);
            TaskDatabaseRecoveryManifest.writeExport(directory,0,export,()->true);
        }
        JSONObject seal()throws Exception{return TaskDatabaseRecoveryManifest.seal(directory,REPAIR,summary,()->true);}
        File exportFile(){return new File(directory,TaskDatabaseRecoveryManifest.exportName(0));}
        File manifestFile(){return new File(directory,TaskDatabaseRecoveryManifest.FILE_NAME);}
    }

    @Test public void sealsExactExportsAndKeepsSourceGenerationAsString()throws Exception {
        Fixture f=new Fixture(true);JSONObject sealed=f.seal();
        assertEquals(GENERATION,sealed.get("sourceGeneration"));assertEquals(1,sealed.getLong("messageCount"));assertTrue(sealed.getBoolean("partial"));
        assertArrayEquals(f.export,Files.readAllBytes(f.exportFile().toPath()));
        assertEquals(f.manifestFile().length(),sealed.getLong("manifestBytes"));assertEquals(DatabaseFilePreservation.sha256(f.manifestFile()),sealed.getString("manifestSha256"));
        TaskDatabaseRecoveryManifest.verify(f.directory,new JSONObject(sealed.toString()),REPAIR,SOURCE,()->true);
        assertFalse(sealed.toString().contains("原会话中可验证的文字"));
    }

    @Test public void unreadableConversationIsAnExplicitUnknownCountGap()throws Exception {
        Fixture f=new Fixture(false);f.summary.put("sourceConversationCount",2).put("unreadableConversationCount",1).put("partial",true);
        f.summary.getJSONArray("gaps").put(new JSONObject().put("sourceConversationId","source-unreadable").put("reason","source_conversation_corrupt").put("recordCount",JSONObject.NULL));
        JSONObject sealed=f.seal();assertTrue(sealed.getJSONArray("gaps").getJSONObject(0).isNull("recordCount"));
        TaskDatabaseRecoveryManifest.verify(f.directory,sealed,REPAIR,SOURCE,()->true);
    }

    @Test public void anotherRepairOrSourceCannotReuseASealedExtraction()throws Exception {
        Fixture f=new Fixture(false);JSONObject sealed=f.seal();
        assertThrows(SecurityException.class,()->TaskDatabaseRecoveryManifest.verify(f.directory,sealed,UUID.randomUUID().toString(),SOURCE,()->true));
        assertThrows(SecurityException.class,()->TaskDatabaseRecoveryManifest.verify(f.directory,sealed,REPAIR,"b".repeat(64),()->true));
    }

    @Test public void modifiedExportBytesAreDetectedEvenWhenLengthIsUnchanged()throws Exception {
        Fixture f=new Fixture(false);JSONObject sealed=f.seal();byte[] changed=f.export.clone();changed[changed.length/2]^=1;Files.write(f.exportFile().toPath(),changed);
        assertThrows(SecurityException.class,()->TaskDatabaseRecoveryManifest.verify(f.directory,sealed,REPAIR,SOURCE,()->true));
    }

    @Test public void modifiedManifestCannotMatchTheOriginalNativeReview()throws Exception {
        Fixture f=new Fixture(false);JSONObject sealed=f.seal();byte[] changed=Files.readAllBytes(f.manifestFile().toPath());changed[changed.length/2]^=1;Files.write(f.manifestFile().toPath(),changed);
        assertThrows(SecurityException.class,()->TaskDatabaseRecoveryManifest.verify(f.directory,sealed,REPAIR,SOURCE,()->true));
    }

    @Test public void reviewCountsCannotBeChangedWhileKeepingTheSameManifestDigest()throws Exception {
        Fixture f=new Fixture(false);JSONObject sealed=f.seal();sealed.put("messageCount",2);sealed.getJSONArray("conversations").getJSONObject(0).put("messageCount",2);
        assertThrows(SecurityException.class,()->TaskDatabaseRecoveryManifest.verify(f.directory,sealed,REPAIR,SOURCE,()->true));
    }

    @Test public void omissionReasonsMustMatchTheActualExport()throws Exception {
        Fixture f=new Fixture(true);f.summary.getJSONArray("gaps").getJSONObject(0).put("reason","source_integrity_unavailable");
        assertThrows(SecurityException.class,f::seal);assertFalse(f.manifestFile().exists());
    }

    @Test public void omissionCountsAndPartialFlagCannotHideAnExportGap()throws Exception {
        Fixture f=new Fixture(true);f.summary.put("partial",false);
        assertThrows(SecurityException.class,f::seal);assertFalse(f.manifestFile().exists());
    }

    @Test public void zeroVerifiedMessagesCannotPublishAnEmptyRecovery()throws Exception {
        Fixture f=new Fixture(false);f.summary.put("messageCount",0);f.summary.getJSONArray("conversations").getJSONObject(0).put("messageCount",0);
        assertThrows(SecurityException.class,f::seal);assertFalse(f.manifestFile().exists());
    }

    @Test public void oldConversationIdentityCannotBeInheritedIncludingAnUnreadableOne()throws Exception {
        Fixture f=new Fixture(false);f.summary.put("sourceConversationCount",2).put("unreadableConversationCount",1).put("partial",true);
        f.summary.getJSONArray("gaps").put(new JSONObject().put("sourceConversationId","new-conversation-one").put("reason","source_conversation_unreadable").put("recordCount",JSONObject.NULL));
        assertThrows(SecurityException.class,f::seal);
    }

    @Test public void countStringsAndNumericGenerationAreRejected()throws Exception {
        Fixture f=new Fixture(false);f.summary.put("messageCount","1");assertThrows(SecurityException.class,f::seal);
        f.summary.put("messageCount",1).put("sourceGeneration",9007199254740993L);assertThrows(SecurityException.class,f::seal);
    }

    @Test public void restoreAuthorityFlagOrUnexpectedFieldsCannotBeAdded()throws Exception {
        Fixture f=new Fixture(false);f.summary.put("restoresActiveGrants",true);assertThrows(SecurityException.class,f::seal);
        f.summary.put("restoresActiveGrants",false).put("ownerGrant","old-grant");assertThrows(SecurityException.class,f::seal);
    }

    @Test public void missingOrSymbolicExportIsNotReadAsVerifiedContent()throws Exception {
        Fixture f=new Fixture(false);JSONObject sealed=f.seal();File outside=temporary.newFile();Files.write(outside.toPath(),f.export);Files.delete(f.exportFile().toPath());
        assertThrows(Exception.class,()->TaskDatabaseRecoveryManifest.verify(f.directory,sealed,REPAIR,SOURCE,()->true));
        Files.createSymbolicLink(f.exportFile().toPath(),outside.toPath());
        assertThrows(Exception.class,()->TaskDatabaseRecoveryManifest.verify(f.directory,sealed,REPAIR,SOURCE,()->true));assertArrayEquals(f.export,Files.readAllBytes(outside.toPath()));
    }

    @Test public void extractionFilesAreNeverOverwrittenAndClosedOwnerCannotSeal()throws Exception {
        Fixture f=new Fixture(false);assertThrows(Exception.class,()->TaskDatabaseRecoveryManifest.writeExport(f.directory,0,new byte[]{1},()->true));
        assertThrows(SecurityException.class,()->TaskDatabaseRecoveryManifest.seal(f.directory,REPAIR,f.summary,()->false));
        assertArrayEquals(f.export,Files.readAllBytes(f.exportFile().toPath()));assertFalse(f.manifestFile().exists());
    }

    @Test public void symbolicParentIsRejectedBeforeCreatingExport()throws Exception {
        File outside=temporary.newFolder(),link=new File(temporary.getRoot(),"linked");Files.createSymbolicLink(link.toPath(),outside.toPath());
        assertThrows(Exception.class,()->TaskDatabaseRecoveryManifest.writeExport(link,0,new byte[]{1},()->true));assertFalse(new File(outside,TaskDatabaseRecoveryManifest.exportName(0)).exists());
    }

    @Test public void recordCountNullIsOnlyForAnEntireUnreadableConversation()throws Exception {
        Fixture f=new Fixture(true);f.summary.getJSONArray("gaps").getJSONObject(0).put("recordCount",JSONObject.NULL);assertThrows(SecurityException.class,f::seal);
    }
}
