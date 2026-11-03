package dev.pocketengine.features.workspace;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real native archive preparation and TaskStore SQL. No model/JSON-issued confirmation or fake DB. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class OwnerBackupWebDavJournalTest {
    @Test public void originalNativeRunExistsAndOnlyOneDispatchCanConsumePrepared()throws Exception {
        try(Fixture f=new Fixture();var review=f.review()){
            var confirmed=review.confirm();JSONObject registered=f.tasks.beginOwnerBackupUploadForOwner(confirmed);String id=confirmed.actionId();
            assertFalse(registered.getBoolean("knownOperation"));assertEquals("prepared",registered.getString("operationState"));assertEquals(1,registered.getInt("epoch"));
            RunControl original=confirmed.original();original.bindHostRun(id,id,1);f.tasks.requireOwnerBackupUploadCurrent(id,original);
            assertTrue(f.tasks.beginOwnerBackupUploadPostForOwner(id,original));assertFalse(f.tasks.beginOwnerBackupUploadPostForOwner(id,original));
            assertEquals("unknown",new TaskStore(f.android).ownerBackupUploadForOwner(id).getString("operationState"));
            assertThrows(SecurityException.class,()->f.tasks.requireOwnerArchiveCacheUnusedForOwner(f.archive.metadata().getString("selectionId")));
            assertThrows(SecurityException.class,()->f.tasks.resolveOperation(id,id,"not_occurred"));
            assertThrows(SecurityException.class,()->f.tasks.reconcileExternalTask(id,"not_occurred","synthetic check"));
            assertFalse(f.tasks.get(id,true).toString().contains(Fixture.PASSWORD));
        }
    }
    @Test public void cancelledPreparedUploadCannotEnterTheNetworkOrHoldItsArchive()throws Exception {
        try(Fixture f=new Fixture();var review=f.review()){
            var confirmed=review.confirm();String id=confirmed.actionId();f.tasks.beginOwnerBackupUploadForOwner(confirmed);confirmed.original().bindHostRun(id,id,1);
            JSONObject stopped=f.host.cancelUpload(id);assertEquals("receipt",stopped.getString("operationState"));assertEquals("cancelled",stopped.getJSONObject("receipt").getString("status"));
            assertFalse(f.tasks.beginOwnerBackupUploadPostForOwner(id,confirmed.original()));f.tasks.requireOwnerArchiveCacheUnusedForOwner(f.archive.metadata().getString("selectionId"));
        }
    }
    @Test public void coldPreparedUploadRetainsDefiniteNonDispatchAndCanBeCancelled()throws Exception {
        try(Fixture f=new Fixture();var review=f.review()){
            var confirmed=review.confirm();String id=confirmed.actionId();f.tasks.beginOwnerBackupUploadForOwner(confirmed);confirmed.original().bindHostRun(id,id,1);
            TaskStore cold=new TaskStore(f.android);assertEquals("prepared",cold.ownerBackupUploadForOwner(id).getString("operationState"));
            JSONObject stopped=cold.cancelOwnerBackupUploadForOwner(id);assertEquals("cancelled",stopped.getJSONObject("receipt").getString("status"));assertFalse(cold.beginOwnerBackupUploadPostForOwner(id,confirmed.original()));
            assertFalse(stopped.getJSONObject("receipt").getBoolean("remoteBytesVerified"));cold.requireOwnerArchiveCacheUnusedForOwner(f.archive.metadata().getString("selectionId"));
        }
    }
    @Test public void wrongTupleAndPrematureCompletionCannotSettleTheOriginalIntent()throws Exception {
        try(Fixture f=new Fixture();var review=f.review()){
            var confirmed=review.confirm();String id=confirmed.actionId();f.tasks.beginOwnerBackupUploadForOwner(confirmed);confirmed.original().bindHostRun(id,id,1);
            JSONObject exact=confirmed.metadata().put("status","completed").put("remoteBytesVerified",true);
            assertThrows(SecurityException.class,()->f.tasks.recordOwnerBackupUploadReceiptForOwner(id,exact));
            assertTrue(f.tasks.beginOwnerBackupUploadPostForOwner(id,confirmed.original()));
            for(String field:List.of("actionId","archiveSha256","encryptedSha256","targetIdentity")){
                JSONObject changed=new JSONObject(exact.toString()).put(field,"incorrect-original-identity");
                assertThrows(field,SecurityException.class,()->f.tasks.recordOwnerBackupUploadReceiptForOwner(id,changed));
                assertEquals("unknown",f.tasks.ownerBackupUploadForOwner(id).getString("operationState"));
            }
            assertThrows(SecurityException.class,()->f.tasks.recordOwnerBackupUploadReceiptForOwner(id,new JSONObject(exact.toString()).put("encryptedBytes",1)));
            f.tasks.recordOwnerBackupUploadReceiptForOwner(id,exact);JSONObject complete=f.tasks.ownerBackupUploadForOwner(id);
            assertEquals("completed",complete.getString("status"));assertTrue(complete.getJSONObject("receipt").getBoolean("remoteBytesVerified"));assertFalse(complete.getJSONObject("receipt").getBoolean("restoreVerified"));
            f.tasks.recordOwnerBackupUploadReceiptForOwner(id,new JSONObject(exact.toString()).put("status","unknown").put("remoteBytesVerified",false));
            assertEquals("completed",f.tasks.ownerBackupUploadForOwner(id).getString("status"));
        }
    }
    @Test public void aColdUnknownUploadBlocksAnotherUploadOfTheSameArchive()throws Exception {
        try(Fixture f=new Fixture()){
            String id;
            try(var first=f.review()){var confirmed=first.confirm();id=confirmed.actionId();f.tasks.beginOwnerBackupUploadForOwner(confirmed);confirmed.original().bindHostRun(id,id,1);assertTrue(f.tasks.beginOwnerBackupUploadPostForOwner(id,confirmed.original()));}
            try(var second=f.review()){var confirmed=second.confirm();assertThrows(SecurityException.class,()->new TaskStore(f.android).beginOwnerBackupUploadForOwner(confirmed));}
            assertEquals("unknown",f.host.uploadRecord(id).getString("operationState"));assertFalse(f.host.uploadRecord(id).getBoolean("replayAllowed"));
        }
    }
    @Test public void changingTheConnectionInvalidatesThePreparedNativeReview()throws Exception {
        try(Fixture f=new Fixture();var review=f.review()){
            var confirmed=review.confirm();f.host.saveConfiguration(1,"https://backup.example/other/","",new char[0],true);
            assertThrows(Exception.class,()->f.tasks.beginOwnerBackupUploadForOwner(confirmed));
            assertThrows(Exception.class,review::confirm);
        }
    }
    private static final class Fixture implements AutoCloseable {
        static final String PASSWORD="synthetic independent backup passphrase";
        final Context android=RuntimeEnvironment.getApplication();final TaskStore tasks=new TaskStore(android);
        final LocalModule local;final OwnerDataArchives archives;final OwnerDataArchives.Selection archive;final OwnerBackupWebDavHost host;
        Fixture()throws Exception {
            FeatureContext context=new FeatureContext(android,null,null,event->{});WorkspaceModule workspace=new WorkspaceModule(context);
            workspace.handle("create",new JSONObject().put("name","Synthetic backup fixture"));local=new LocalModule(context,workspace);
            archives=new OwnerDataArchives(Files.createTempDirectory("webdav-native-archives-").toFile(),workspace,local,new AttachmentsModule(context),Map.of());
            archive=archives.create(Set.of("projects"),new RunControl());
            host=new OwnerBackupWebDavHost(Files.createTempDirectory("webdav-native-transfers-").toFile(),archives,tasks,new SecretVault(android),id->{});
            host.saveConfiguration(0,"https://backup.example/backups/","",new char[0],true);
        }
        OwnerBackupWebDavHost.Review review()throws Exception {char[] password=PASSWORD.toCharArray();var review=host.reviewUpload(archive,password,1,new RunControl());assertArrayEquals(new char[password.length],password);return review;}
        @Override public void close()throws Exception {host.close();local.close();}
    }
}
