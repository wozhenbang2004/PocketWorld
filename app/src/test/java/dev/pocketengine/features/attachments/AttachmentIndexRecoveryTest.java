package dev.pocketengine.features.attachments;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original Book/Module/OutcomeHost with real JSON, private files and SQLite.
 * No mounted UI, Android process-death, provider or device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AttachmentIndexRecoveryTest {
    @Rule public TemporaryFolder directory=new TemporaryFolder();
    private Context context()throws Exception {
        File files=directory.newFolder("files"),cache=directory.newFolder("cache");
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
            @Override public File getCacheDir(){return cache;}
        };
    }
    private static JSONObject save(AttachmentBook book,String name,JSONObject source)throws Exception {
        return book.importBytes(new ByteArrayInputStream(name.getBytes(StandardCharsets.UTF_8)),name,"text/plain",source);
    }
    private static File index(File root,JSONObject record)throws Exception{return new File(root,record.getString("id")+".json");}
    private static Map<String,byte[]> retained(File root)throws Exception {
        Map<String,byte[]> original=new LinkedHashMap<>();for(File file:root.listFiles())if(file.isFile())original.put(file.getName(),Files.readAllBytes(file.toPath()));return original;
    }
    private static void unchanged(File root,Map<String,byte[]> original)throws Exception {
        for(var item:original.entrySet())assertArrayEquals(item.getKey(),item.getValue(),Files.readAllBytes(new File(root,item.getKey()).toPath()));
    }
    // Reflection lets the same regression source compile against the pre-fix original Book.
    private static JSONObject inspect(AttachmentBook book)throws Exception {
        try{return (JSONObject)AttachmentBook.class.getMethod("inspectIndex").invoke(book);}
        catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}
    }
    @Test public void ownerListsAndNewImportSurviveCorruptionButArchiveAndRemoveStayClosed()throws Exception {
        Context android=context();AttachmentRepository repository=AttachmentRepository.get(android);File root=new File(android.getFilesDir(),"attachments");
        JSONObject good=save(repository.book,"readable",new JSONObject()),bad=save(repository.book,"unknown-original",new JSONObject());
        save(repository.book,"group-only",new JSONObject().put("groupSpaceId","group-private"));JSONObject revoked=save(repository.book,"revoked",new JSONObject());
        Files.writeString(index(root,bad).toPath(),"{\"source\":{\"uri\":\"content://secret/unknown-publication\"",StandardCharsets.UTF_8);
        Map<String,byte[]> original=retained(root);AttachmentsModule module=new AttachmentsModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{}));
        module.setNativeRecordRead(record->{if(revoked.getString("id").equals(record.getString("id")))throw new SecurityException("original native read withdrawn");});
        for(JSONObject listed:new JSONObject[]{module.handle("list",new JSONObject()),module.snapshot()}){
            assertEquals(1,listed.getJSONArray("attachments").length());assertEquals(good.getString("id"),listed.getJSONArray("attachments").getJSONObject(0).getString("id"));
            JSONObject diagnostic=listed.getJSONObject("indexDiagnostics");assertTrue(diagnostic.getBoolean("incomplete"));assertEquals(1,diagnostic.getInt("unreadableRecords"));assertTrue(diagnostic.getBoolean("originalsPreserved"));assertFalse(diagnostic.getBoolean("automaticCleanup"));
            assertFalse(diagnostic.toString().contains(bad.getString("id")));assertFalse(listed.toString().contains("content://secret"));assertFalse(listed.toString().contains("unknown-publication"));
        }
        JSONObject created=module.handle("createText",new JSONObject().put("name","new.txt").put("content","new permitted import"));assertEquals("new permitted import",repository.book.readText(created.getString("id")).getString("text"));
        assertThrows(IllegalStateException.class,repository.book::list);assertThrows(IllegalStateException.class,module::ownerArchiveAttachmentsForHost);
        assertThrows(Exception.class,()->module.handle("remove",new JSONObject().put("id",bad.getString("id"))));unchanged(root,original);
    }
    @Test public void nativeOwnerSnapshotUsesTheSameScopedIncompleteProjection()throws Exception {
        Context android=context();AttachmentRepository repository=AttachmentRepository.get(android);JSONObject good=save(repository.book,"good",new JSONObject()),bad=save(repository.book,"bad",new JSONObject());
        File root=new File(android.getFilesDir(),"attachments");Files.writeString(index(root,bad).toPath(),"{broken",StandardCharsets.UTF_8);Map<String,byte[]> original=retained(root);
        AttachmentsModule module=new AttachmentsModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{}));TaskStore tasks=new TaskStore(android);AttachmentOutcomeHost host=new AttachmentOutcomeHost(tasks,repository,module,(a,b,c)->{throw new AssertionError("No provider action");});
        try{JSONObject snapshot=host.snapshot(null,new RunControl());assertTrue(snapshot.getJSONObject("indexDiagnostics").getBoolean("incomplete"));assertEquals(1,snapshot.getJSONArray("sources").length());assertEquals(good.getString("id"),snapshot.getJSONArray("sources").getJSONObject(0).getString("id"));unchanged(root,original);}
        finally{org.robolectric.util.ReflectionHelpers.<android.database.sqlite.SQLiteDatabase>getField(tasks,"db").close();}
    }
    @Test public void corruptFixedPublicationIsNeverReplayedOrOverwritten()throws Exception {
        File root=directory.newFolder("book");AttachmentBook book=new AttachmentBook(root);String operation=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),plan="a".repeat(64);
        JSONObject nativeFile=book.importNativeResult(new ByteArrayInputStream(new byte[]{1,2,3}),"fixed","text/plain",new JSONObject(),operation,"document_output",task,run,plan,new RunControl());String id=nativeFile.getString("id");
        Files.writeString(index(root,nativeFile).toPath(),"{unknown-original-publication",StandardCharsets.UTF_8);Map<String,byte[]> original=retained(root);AtomicInteger reads=new AtomicInteger();InputStream neverRead=new InputStream(){@Override public int read(){reads.incrementAndGet();return -1;}};
        assertThrows(Exception.class,()->book.findNativeResult(operation,"document_output",task,run,plan,new RunControl()));assertThrows(Exception.class,()->book.importNativeResult(neverRead,"retry","text/plain",new JSONObject(),operation,"document_output",task,run,plan,new RunControl()));assertEquals(0,reads.get());
        save(book,"unrelated-new",new JSONObject());assertTrue(inspect(book).getJSONObject("indexDiagnostics").getBoolean("incomplete"));assertNull(book.outputSourceEligibility(id,nativeFile.getString("sha256")));
        Files.writeString(new File(root,id+".json.pending").toPath(),"unknown-pending");Files.write(new File(root,id+".part").toPath(),new byte[]{9});Map<String,byte[]> pending=retained(root);
        assertThrows(IllegalStateException.class,()->book.findNativeResult(operation,"document_output",task,run,plan,new RunControl()));assertEquals(2,inspect(book).getJSONObject("indexDiagnostics").getInt("retainedSlots"));unchanged(root,original);unchanged(root,pending);
    }
    @Test public void corruptOrOversizedIndexesCannotReleasePhysicalByteQuota()throws Exception {
        File root=directory.newFolder("quota");AttachmentBook book=new AttachmentBook(root,8,8);JSONObject file=save(book,"123456",new JSONObject());Files.write(index(root,file).toPath(),new byte[256*1024+1]);Map<String,byte[]> original=retained(root);
        JSONObject diagnostic=inspect(book).getJSONObject("indexDiagnostics");assertEquals(1,diagnostic.getInt("oversizedRecords"));assertEquals(6,diagnostic.getLong("storedBytes"));assertTrue(diagnostic.getBoolean("quotaKnown"));
        assertThrows(IllegalStateException.class,()->book.get(file.getString("id")));assertThrows(IllegalStateException.class,()->book.remove(file.getString("id")));
        assertThrows(IllegalArgumentException.class,()->book.importBytes(new ByteArrayInputStream(new byte[3]),"over","text/plain",null));JSONObject saved=book.importBytes(new ByteArrayInputStream(new byte[2]),"fits","text/plain",null);assertEquals(2,saved.getLong("size"));unchanged(root,original);
    }
    @Test public void allFourPublicationArtifactsKeepOneSlotAndOrphansKeepTheirOwnSlots()throws Exception {
        File root=directory.newFolder("slots");String same=UUID.randomUUID().toString();for(String suffix:new String[]{".json",".json.pending",".blob",".part"})Files.writeString(new File(root,same+suffix).toPath(),"?");
        for(int i=0;i<198;i++)Files.write(new File(root,UUID.randomUUID()+".json.pending").toPath(),new byte[0]);AttachmentBook book=new AttachmentBook(root,8,32);Map<String,byte[]> original=retained(root);
        assertEquals(199,inspect(book).getJSONObject("indexDiagnostics").getInt("retainedSlots"));book.importBytes(new ByteArrayInputStream(new byte[1]),"last-slot","text/plain",null);
        AtomicInteger reads=new AtomicInteger();InputStream source=new InputStream(){@Override public int read(){reads.incrementAndGet();return -1;}};assertThrows(IllegalStateException.class,()->book.importBytes(source,"slot-201","text/plain",null));assertEquals(0,reads.get());unchanged(root,original);
    }
    @Test public void orphanBlobAndPartBytesRemainChargedAndOverQuotaEmptyImportIsDenied()throws Exception {
        File root=directory.newFolder("orphan");Files.write(new File(root,UUID.randomUUID()+".blob").toPath(),new byte[5]);Files.write(new File(root,UUID.randomUUID()+".part").toPath(),new byte[4]);AttachmentBook book=new AttachmentBook(root,8,8);Map<String,byte[]> original=retained(root);
        assertThrows(IllegalArgumentException.class,()->book.importBytes(new ByteArrayInputStream(new byte[0]),"empty","text/plain",null));assertEquals(9,inspect(book).getJSONObject("indexDiagnostics").getLong("storedBytes"));unchanged(root,original);
    }
    @Test public void enumerationBudgetAndUnknownArtifactTypeDenyAdmissionWithoutCleanup()throws Exception {
        File crowded=directory.newFolder("crowded");for(int i=0;i<1025;i++)Files.write(new File(crowded,"unrecognized-"+i).toPath(),new byte[0]);AttachmentBook book=new AttachmentBook(crowded);JSONObject diagnostic=inspect(book).getJSONObject("indexDiagnostics");
        assertFalse(diagnostic.getBoolean("enumerationComplete"));assertFalse(diagnostic.getBoolean("quotaKnown"));assertThrows(IllegalStateException.class,()->book.importBytes(new ByteArrayInputStream(new byte[0]),"new","text/plain",null));assertEquals(1025,crowded.listFiles().length);
        File unknown=directory.newFolder("unknown");File part=new File(unknown,UUID.randomUUID()+".part");assertTrue(part.mkdir());AttachmentBook uncertain=new AttachmentBook(unknown);assertFalse(inspect(uncertain).getJSONObject("indexDiagnostics").getBoolean("quotaKnown"));assertThrows(IllegalStateException.class,()->uncertain.importBytes(new ByteArrayInputStream(new byte[0]),"new","text/plain",null));assertTrue(part.isDirectory());
    }
    @Test public void aggregateIndexReadBudgetIsExplicitAndDoesNotChangeAnyOriginal()throws Exception {
        File root=directory.newFolder("read-budget");AttachmentBook book=new AttachmentBook(root);for(int i=0;i<17;i++){JSONObject file=book.importBytes(new ByteArrayInputStream(new byte[0]),"large-index-"+i,"text/plain",new JSONObject());JSONObject metadata=new JSONObject(Files.readString(index(root,file).toPath()));metadata.getJSONObject("source").put("retainedUnknownEvidence","x".repeat(250000));Files.writeString(index(root,file).toPath(),metadata.toString());}
        Map<String,byte[]> original=retained(root);JSONObject inventory=inspect(book);assertTrue(inventory.getJSONObject("indexDiagnostics").getInt("readBudgetExceededRecords")>0);assertTrue(inventory.getJSONArray("attachments").length()>0);assertThrows(IllegalStateException.class,book::list);unchanged(root,original);
    }
    @Test public void newImportCannotCommitMetadataBeyondTheSameReadBound()throws Exception {
        File root=directory.newFolder("metadata-limit");AttachmentBook book=new AttachmentBook(root);save(book,"original",new JSONObject());Map<String,byte[]> original=retained(root);
        assertThrows(IllegalArgumentException.class,()->save(book,"too-large",new JSONObject().put("large","x".repeat(256*1024))));assertEquals(original.size(),root.listFiles().length);unchanged(root,original);
        String operation=UUID.randomUUID().toString(),digest="a".repeat(64);assertThrows(IllegalArgumentException.class,()->book.importNativeResult(new ByteArrayInputStream(new byte[]{1}),"large-native","text/plain",new JSONObject().put("large","x".repeat(256*1024)),operation,"document_output","","",digest,new RunControl()));
        assertFalse(book.findNativeResult(operation,"document_output","","",digest,new RunControl()).getBoolean("publicationEntered"));assertEquals(original.size(),root.listFiles().length);unchanged(root,original);
    }
    @Test public void filenameIdentityMismatchIsIsolatedAndFreshBookInspectionIsNonMutating()throws Exception {
        File missing=new File(directory.getRoot(),"not-created");AttachmentBook fresh=new AttachmentBook(missing);assertFalse(inspect(fresh).getJSONObject("indexDiagnostics").getBoolean("incomplete"));assertFalse(missing.exists());
        File root=directory.newFolder("identity");AttachmentBook book=new AttachmentBook(root);JSONObject good=save(book,"good",new JSONObject()),bad=save(book,"bad",new JSONObject());JSONObject wrong=new JSONObject(Files.readString(index(root,bad).toPath())).put("id",good.getString("id"));Files.writeString(index(root,bad).toPath(),wrong.toString());
        String deep=UUID.randomUUID().toString();Files.write(new File(root,deep+".blob").toPath(),new byte[0]);Files.writeString(new File(root,deep+".json").toPath(),"{\"source\":"+"[".repeat(5000)+"0"+"]".repeat(5000)+"}");
        Map<String,byte[]> original=retained(root);JSONObject inventory=inspect(book);assertEquals(1,inventory.getJSONArray("attachments").length());assertEquals(2,inventory.getJSONObject("indexDiagnostics").getInt("unreadableRecords"));unchanged(root,original);
    }
}
