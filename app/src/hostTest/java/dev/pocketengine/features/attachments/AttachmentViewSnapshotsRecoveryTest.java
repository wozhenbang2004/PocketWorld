package dev.pocketengine.features.attachments;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.Permission;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual prepare/fsync, private files and forcibly terminated host JVMs, not a device
 * process-death or Android grant-lifetime acceptance test. No production crash hooks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows={LegacyAttachmentIntegrityTest.PosixDupShadow.class,LegacyAttachmentIntegrityTest.PosixSeekShadow.class})
public final class AttachmentViewSnapshotsRecoveryTest {
    private static final long TTL_MS=10*60*1000L;
    @Rule public TemporaryFolder directory=new TemporaryFolder();
    private Context context(File cache) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getCacheDir(){return cache;}
        };
    }
    private static String digest(byte[] bytes)throws Exception {
        StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();
    }
    private static Intent prepare(AttachmentViewSnapshots snapshots,byte[] bytes)throws Exception {
        return snapshots.prepare("original.txt","text/plain",digest(bytes),bytes,new RunControl());
    }
    private static String token(Intent prepared){return prepared.getData().getLastPathSegment();}
    private static Path blob(File cache,String token){return cache.toPath().resolve("outcome-views").resolve(token+".blob");}
    private static Path meta(File cache,String token){return cache.toPath().resolve("outcome-views").resolve(token+".json");}
    private static void old(Path path)throws Exception{Files.setLastModifiedTime(path,FileTime.fromMillis(System.currentTimeMillis()-TTL_MS-10000));}
    private static Path seed(Path root,String token,byte[] bytes)throws Exception{Files.createDirectories(root);Path path=root.resolve(token+".blob");Files.write(path,bytes);old(path);return path;}
    private static void deleteTree(Path root)throws Exception {
        if(!Files.exists(root))return;try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private String crash(File cache,String boundary,int size)throws Exception {
        File marker=new File(directory.getRoot(),"crash-"+UUID.randomUUID()+".ready"),log=new File(directory.getRoot(),marker.getName()+".log");Path workerTmp=directory.getRoot().toPath().resolve(marker.getName()+"-tmp");Files.createDirectories(workerTmp);
        var command=new java.util.ArrayList<String>(Arrays.asList(new File(System.getProperty("java.home"),"bin/java").getPath(),"-Xmx192m","-XX:ActiveProcessorCount=2","-XX:MaxMetaspaceSize=160m","-XX:ReservedCodeCacheSize=32m","-Djava.io.tmpdir="+workerTmp,"-Dpocketworld.snapshot.crash.cache="+cache.getAbsolutePath(),"-Dpocketworld.snapshot.crash.marker="+marker.getAbsolutePath(),"-Dpocketworld.snapshot.crash.boundary="+boundary,"-Dpocketworld.snapshot.crash.size="+size));
        for(String key:new String[]{"robolectric.offline","robolectric.dependency.dir"})if(System.getProperty(key)!=null)command.add("-D"+key+"="+System.getProperty(key));
        command.addAll(Arrays.asList("-cp",System.getProperty("pocketworldHostClasspath",System.getProperty("java.class.path")),"org.junit.runner.JUnitCore",CrashWriter.class.getName()));
        Process child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();System.out.println("Owned crash writer pid="+child.pid()+" boundary="+boundary);
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(!marker.isFile()&&child.isAlive()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertTrue("Crash boundary not reached: "+Files.readString(log.toPath()),marker.isFile());String id=Files.readString(marker.toPath());assertTrue(Files.isRegularFile(blob(cache,id)));
            child.destroyForcibly();assertTrue("Crash writer did not terminate",child.waitFor(10,TimeUnit.SECONDS));assertNotEquals("Writer exited normally",0,child.exitValue());return id;
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}assertFalse("Owned writer still alive; retain its temporary files",child.isAlive());deleteTree(workerTmp);}
    }
    @Test public void killedFsyncedBlobRetainsTheOriginalTtlThenReleasesTheFourthSlot()throws Exception {
        File cache=directory.newFolder("quota-cache");AttachmentViewSnapshots snapshots=new AttachmentViewSnapshots(context(cache));byte[] bytes="issued original".getBytes(StandardCharsets.UTF_8);Intent[] issued=new Intent[3];
        for(int i=0;i<issued.length;i++){issued[i]=prepare(snapshots,bytes);issued[i].putExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,true);}
        String orphan=crash(cache,"blob",bytes.length);assertFalse(Files.exists(meta(cache,orphan)));assertEquals(bytes.length,Files.size(blob(cache,orphan)));
        assertThrows(IllegalStateException.class,()->prepare(new AttachmentViewSnapshots(context(cache)),bytes));assertTrue(Files.exists(blob(cache,orphan)));
        old(blob(cache,orphan));Intent replacement=prepare(new AttachmentViewSnapshots(context(cache)),bytes);assertFalse(Files.exists(blob(cache,orphan)));assertNotNull(snapshots.metadata(token(replacement)));
        for(Intent prior:issued){assertArrayEquals(bytes,Files.readAllBytes(snapshots.file(token(prior)).toPath()));assertThrows(SecurityException.class,()->snapshots.discardUnissuedView(prior));}
    }
    @Test public void killedPartialCopyIsReclaimedAfterTheOriginalTtl()throws Exception {
        File partialCache=directory.newFolder("partial-cache");String partial=crash(partialCache,"partial",65536);assertEquals(32768,Files.size(blob(partialCache,partial)));assertFalse(Files.exists(meta(partialCache,partial)));old(blob(partialCache,partial));prepare(new AttachmentViewSnapshots(context(partialCache)),new byte[]{1});assertFalse(Files.exists(blob(partialCache,partial)));
    }
    @Test public void killedFsyncedLargeCopyCannotPermanentlyConsumeTheByteQuota()throws Exception {
        File cache=directory.newFolder("byte-cache");String orphan=crash(cache,"blob",(int)AttachmentBook.MAX_FILE_BYTES);assertEquals(AttachmentBook.MAX_FILE_BYTES,Files.size(blob(cache,orphan)));assertFalse(Files.exists(meta(cache,orphan)));
        AttachmentViewSnapshots snapshots=new AttachmentViewSnapshots(context(cache));byte[] large=new byte[(int)AttachmentBook.MAX_FILE_BYTES];Intent valid=prepare(snapshots,large);
        assertThrows(IllegalStateException.class,()->prepare(snapshots,new byte[]{2}));old(blob(cache,orphan));prepare(new AttachmentViewSnapshots(context(cache)),new byte[]{2});assertFalse(Files.exists(blob(cache,orphan)));assertEquals(large.length,snapshots.metadata(token(valid)).getLong("size"));assertEquals(large.length,Files.size(blob(cache,token(valid))));
    }
    @Test public void deathAfterMetadataSyncDoesNotTurnAPublishedSnapshotIntoAnOrphan()throws Exception {
        File cache=directory.newFolder("published-cache");String id=crash(cache,"metadata",31);assertTrue(Files.isRegularFile(meta(cache,id)));byte[] original=Files.readAllBytes(blob(cache,id));byte[] index=Files.readAllBytes(meta(cache,id));old(blob(cache,id));
        AttachmentViewSnapshots cold=new AttachmentViewSnapshots(context(cache));prepare(cold,new byte[]{3});assertArrayEquals(original,Files.readAllBytes(cold.file(id).toPath()));assertArrayEquals(index,Files.readAllBytes(meta(cache,id)));
    }
    @Test public void anotherInstanceCannotPruneAnOldTimestampWhileTheOriginalPrepareStillHoldsTheDiskLock()throws Exception {
        File cache=directory.newFolder("live-cache");AttachmentViewSnapshots writer=new AttachmentViewSnapshots(context(cache)),other=new AttachmentViewSnapshots(context(cache));RunControl control=new RunControl();CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1),contending=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        control.setAuthorityCheck(()->{File root=new File(cache,"outcome-views");File[] files=root.listFiles((d,n)->n.endsWith(".blob"));if(files!=null&&files.length==1&&files[0].length()>0&&writing.getCount()!=0){old(files[0].toPath());writing.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Writer gate timed out");}});
        try {
            byte[] bytes=new byte[65536];var first=pool.submit(()->writer.prepare("live.txt","text/plain",digest(bytes),bytes,control));assertTrue(writing.await(10,TimeUnit.SECONDS));
            var second=pool.submit(()->{contending.countDown();return prepare(other,new byte[]{7});});assertTrue(contending.await(5,TimeUnit.SECONDS));Thread.sleep(100);assertFalse("Second preparation entered a live writer's disk critical section",second.isDone());release.countDown();Intent a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);
            assertArrayEquals(bytes,Files.readAllBytes(writer.file(token(a)).toPath()));assertNotNull(other.metadata(token(b)));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test public void missingOrUnreadableMetadataIsNotPermissionToRemoveUnknownOrOutOfNamespaceFiles()throws Exception {
        File cache=directory.newFolder("unknown-cache");Path root=cache.toPath().resolve("outcome-views");String broken=UUID.randomUUID().toString(),directoryIndex=UUID.randomUUID().toString(),foreign="11111111-1111-1111-8111-111111111111";
        Path brokenBlob=seed(root,broken,new byte[]{10}),directoryBlob=seed(root,directoryIndex,new byte[]{11}),foreignBlob=seed(root,foreign,new byte[]{12});Files.writeString(root.resolve(broken+".json"),"{unreadable");Files.createDirectory(root.resolve(directoryIndex+".json"));
        prepare(new AttachmentViewSnapshots(context(cache)),new byte[]{1});assertArrayEquals(new byte[]{10},Files.readAllBytes(brokenBlob));assertArrayEquals(new byte[]{11},Files.readAllBytes(directoryBlob));assertArrayEquals(new byte[]{12},Files.readAllBytes(foreignBlob));assertTrue(Files.isDirectory(root.resolve(directoryIndex+".json")));assertEquals("{unreadable",Files.readString(root.resolve(broken+".json")));
    }
    @Test public void sameTokenIncompleteRecordsAndCoercibleExpiryAreUnknownRatherThanExpired()throws Exception {
        File cache=directory.newFolder("metadata-contract-cache");AttachmentViewSnapshots snapshots=new AttachmentViewSnapshots(context(cache));Intent stringExpiry=prepare(snapshots,new byte[]{14});String first=token(stringExpiry);JSONObject coercible=new JSONObject(Files.readString(meta(cache,first))).put("expiresAt","1");Files.writeString(meta(cache,first),coercible.toString());old(blob(cache,first));
        String second=UUID.randomUUID().toString();Path incomplete=seed(cache.toPath().resolve("outcome-views"),second,new byte[]{15});String record=new JSONObject().put("id",second).put("expiresAt",1).toString();Files.writeString(meta(cache,second),record);
        prepare(new AttachmentViewSnapshots(context(cache)),new byte[]{1});assertArrayEquals(new byte[]{14},Files.readAllBytes(blob(cache,first)));assertEquals(coercible.toString(),Files.readString(meta(cache,first)));assertArrayEquals(new byte[]{15},Files.readAllBytes(incomplete));assertEquals(record,Files.readString(meta(cache,second)));
    }
    @Test public void futureUnknownAndLinkedEntriesArePreservedAndCannotEscapeThePrivateSnapshotRoot()throws Exception {
        File cache=directory.newFolder("namespace-cache");Path root=cache.toPath().resolve("outcome-views");String future=UUID.randomUUID().toString(),unknown=UUID.randomUUID().toString(),linked=UUID.randomUUID().toString();Path futureBlob=seed(root,future,new byte[]{20}),unknownBlob=seed(root,unknown,new byte[]{21});Files.setLastModifiedTime(futureBlob,FileTime.fromMillis(System.currentTimeMillis()+TTL_MS));Files.setLastModifiedTime(unknownBlob,FileTime.fromMillis(0));
        Path original=directory.getRoot().toPath().resolve("native-publication-original.blob");Files.write(original,new byte[]{22});Files.createSymbolicLink(root.resolve(linked+".blob"),original);Path reader=root.resolve("reader-"+UUID.randomUUID()+".part");Files.write(reader,new byte[]{23});old(reader);
        prepare(new AttachmentViewSnapshots(context(cache)),new byte[]{1});assertArrayEquals(new byte[]{20},Files.readAllBytes(futureBlob));assertArrayEquals(new byte[]{21},Files.readAllBytes(unknownBlob));assertTrue(Files.isSymbolicLink(root.resolve(linked+".blob")));assertArrayEquals(new byte[]{22},Files.readAllBytes(original));assertArrayEquals(new byte[]{23},Files.readAllBytes(reader));
    }
    @Test public void recoveryNeverTouchesTheSeparateBookOrUnknownFixedNativePublicationEvidence()throws Exception {
        File originalRoot=directory.newFolder("original-book");AttachmentBook book=new AttachmentBook(originalRoot);JSONObject original=book.importBytes(new ByteArrayInputStream(new byte[]{31}),"original.txt","text/plain",new JSONObject());String operation=UUID.randomUUID().toString(),plan="a".repeat(64),nativeId=book.findNativeResult(operation,"document_output","","",plan,new RunControl()).getString("id");
        for(String suffix:new String[]{".blob",".part",".json.pending"})Files.write(new File(originalRoot,nativeId+suffix).toPath(),new byte[]{32});var retained=new java.util.LinkedHashMap<Path,byte[]>();for(File file:originalRoot.listFiles())retained.put(file.toPath(),Files.readAllBytes(file.toPath()));
        File cache=directory.newFolder("separate-cache");Path orphan=seed(cache.toPath().resolve("outcome-views"),UUID.randomUUID().toString(),new byte[]{33});prepare(new AttachmentViewSnapshots(context(cache)),new byte[]{1});assertFalse(Files.exists(orphan));for(var entry:retained.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()));assertArrayEquals(new byte[]{31},book.readVerifiedSnapshot(original.getString("id"),original.getString("sha256"),new RunControl()));assertThrows(IllegalStateException.class,()->book.findNativeResult(operation,"document_output","","",plan,new RunControl()));
    }
    @Test public void aLinkedSnapshotRootCannotTurnAnOrdinaryBookOrphanIntoACleanupTarget()throws Exception {
        File originalRoot=directory.newFolder("linked-book"),cache=directory.newFolder("linked-cache");String id=UUID.randomUUID().toString();Path original=seed(originalRoot.toPath(),id,new byte[]{41});Files.createSymbolicLink(cache.toPath().resolve("outcome-views"),originalRoot.toPath());
        assertThrows(Exception.class,()->prepare(new AttachmentViewSnapshots(context(cache)),new byte[]{1}));assertArrayEquals(new byte[]{41},Files.readAllBytes(original));assertEquals(1,originalRoot.listFiles().length);
    }
    @Test public void onlyTheExistingMetadataExpiryRetiresAnIndexedSnapshotAndDetachedReadersStayReadable()throws Exception {
        File cache=directory.newFolder("ttl-cache");AttachmentViewSnapshots snapshots=new AttachmentViewSnapshots(context(cache));byte[] bytes="detached issued bytes".getBytes(StandardCharsets.UTF_8);Intent issued=prepare(snapshots,bytes);String id=token(issued);issued.putExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,true);old(blob(cache,id));
        try(ParcelFileDescriptor opened=snapshots.openReadOnly(id)) {
            prepare(new AttachmentViewSnapshots(context(cache)),new byte[]{1});assertArrayEquals(bytes,Files.readAllBytes(snapshots.file(id).toPath()));JSONObject expired=new JSONObject(Files.readString(meta(cache,id))).put("expiresAt",System.currentTimeMillis()-1);Files.writeString(meta(cache,id),expired.toString());
            prepare(new AttachmentViewSnapshots(context(cache)),new byte[]{2});assertFalse(Files.exists(blob(cache,id)));assertFalse(Files.exists(meta(cache,id)));try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(opened.getFileDescriptor()))){assertArrayEquals(bytes,input.readAllBytes());}
        }
    }
    /** Plain JDK child, not another Robolectric/native-runtime extraction. Only the
     * context's cache-directory/application getters are a host fixture; the production
     * prepare, lock, real JSON, FileOutputStream and FD sync are unchanged. The child
     * stops before Android URI construction. Its JDK17 security check pauses immediately
     * before metadata open, after production blob write/FD sync has returned. */
    public static final class CrashWriter {
        private static final class CrashContext extends ContextWrapper {
            File cache;
            private CrashContext(){super(null);throw new AssertionError("Do not invoke the Android SDK stub constructor");}
            @Override public Context getApplicationContext(){return this;}
            @Override public File getCacheDir(){return cache;}
            @Override public String getPackageName(){throw new AssertionError("Crash child must stop before Android URI construction");}
        }
        private static Context context(File cache)throws Exception {
            // Allocate only this getter-only host context around the SDK's throwing
            // constructor. No production class, IO, JSON, lock or crash action is mocked.
            Class<?> type=Class.forName("sun.misc.Unsafe");var field=type.getDeclaredField("theUnsafe");field.setAccessible(true);Object allocator=field.get(null);
            CrashContext context=(CrashContext)type.getMethod("allocateInstance",Class.class).invoke(allocator,CrashContext.class);context.cache=cache;return context;
        }
        private static void boundary(File root,File marker) {
            try{File[] blobs=root.listFiles((d,n)->n.endsWith(".blob"));if(blobs==null||blobs.length==0)throw new AssertionError("No real blob at crash boundary");File newest=null;for(File blob:blobs)if(!new File(root,blob.getName().replace(".blob",".json")).exists()&&(newest==null||blob.lastModified()>newest.lastModified()))newest=blob;if(newest==null){File[] indices=root.listFiles((d,n)->n.endsWith(".json"));newest=new File(root,indices[indices.length-1].getName().replace(".json",".blob"));}Path pending=marker.toPath().resolveSibling(marker.getName()+".pending");Files.writeString(pending,newest.getName().replace(".blob",""));Files.move(pending,marker.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);for(;;)Thread.sleep(1000);}
            catch(Exception failed){throw new AssertionError(failed);}
        }
        @SuppressWarnings("removal")
        @Test public void writeUntilKilled()throws Exception {
            File cache=new File(System.getProperty("pocketworld.snapshot.crash.cache")),root=new File(cache,"outcome-views"),marker=new File(System.getProperty("pocketworld.snapshot.crash.marker"));String stage=System.getProperty("pocketworld.snapshot.crash.boundary");
            Context context=context(cache);
            if("blob".equals(stage))System.setSecurityManager(new SecurityManager(){@Override public void checkPermission(Permission permission){}@Override public void checkWrite(String file){if(root.equals(new File(file).getParentFile())&&file.endsWith(".json"))boundary(root,marker);}});
            RunControl control=new RunControl();control.setAuthorityCheck(()->{File[] blobs=root.listFiles((d,n)->n.endsWith(".blob"));if(blobs==null)return;for(File blob:blobs){File index=new File(root,blob.getName().replace(".blob",".json"));if("partial".equals(stage)&&blob.length()==32768&&!index.exists()||"metadata".equals(stage)&&index.isFile()&&index.length()>0)boundary(root,marker);}});
            byte[] bytes=new byte[Integer.parseInt(System.getProperty("pocketworld.snapshot.crash.size"))];prepare(new AttachmentViewSnapshots(context),bytes,control);fail("Worker must be killed at the requested boundary");
        }
        private static void prepare(AttachmentViewSnapshots snapshots,byte[] bytes,RunControl control)throws Exception{snapshots.prepare("crash.txt","text/plain",digest(bytes),bytes,control);}
    }
}
