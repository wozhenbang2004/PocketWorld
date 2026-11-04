package dev.pocketengine.store;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONObject;
import org.junit.Test;

public final class TaskScreenshotStorageTest {
    private static final byte[] JPEG=new byte[]{(byte)255,(byte)216,(byte)255,42,17,89,12,61};
    private static SecretKey key()throws Exception {KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);return generator.generateKey();}
    private static TaskScreenshotStorage storage(Path root,SecretKey key){return new TaskScreenshotStorage(root,new TaskScreenshotStorage.Crypto(){
        public byte[] seal(byte[] plain,byte[] aad)throws Exception{return TaskScreenshotStorage.seal(key,plain,aad);}
        public byte[] open(byte[] encrypted,byte[] aad)throws Exception{return TaskScreenshotStorage.open(key,encrypted,aad);}
    });}
    private static JSONObject record(String task,String image)throws Exception {return TaskScreenshotPolicy.record(task,"run-1",1,image,1,1+TaskScreenshotPolicy.DAY,JPEG.length,"image/jpeg",false);}
    private interface Attempt{void run()throws Exception;}
    private static void refused(Attempt attempt)throws Exception {try{attempt.run();fail("Expected rejection");}catch(AssertionError failure){throw failure;}catch(Exception expected){}}

    @Test public void aSubstitutedScreenshotDirectoryCannotAuthorizeCleanup()throws Exception {
        Path parent=Files.createTempDirectory("screenshot-root-substitution-"),outside=Files.createDirectory(parent.resolve("outside"));
        Path sentinel=Files.write(outside.resolve("owner-export.jpg"),JPEG),alias=Files.createSymbolicLink(parent.resolve("artifacts"),outside);
        TaskScreenshotStorage files=storage(alias,key());
        assertThrows(SecurityException.class,()->files.removeExpired(Long.MAX_VALUE));
        assertThrows(SecurityException.class,files::nextExpiry);
        assertArrayEquals(JPEG,Files.readAllBytes(sentinel));
    }
    @Test public void encryptedRoundTripBindsTheExactOriginalMetadata()throws Exception {
        Path root=Files.createTempDirectory("screenshots-test-");SecretKey key=key();TaskScreenshotStorage storage=storage(root,key);
        String image=UUID.randomUUID().toString();JSONObject record=record("task-1",image);byte[] aad=TaskScreenshotPolicy.aad(record);
        storage.write("task-1",image,JPEG,aad);Path file=root.resolve(storage.fileName("task-1",image));byte[] encrypted=Files.readAllBytes(file);
        assertFalse(Arrays.equals(JPEG,encrypted));assertFalse(Files.exists(root.resolve("task-1.jpg")));
        assertArrayEquals(JPEG,storage.read("task-1",image,aad));
        JSONObject changed=new JSONObject(record.toString()).put("expiresAt",record.getLong("expiresAt")+1);
        refused(()->storage.read("task-1",image,TaskScreenshotPolicy.aad(changed)));
        refused(()->storage(root,key()).read("task-1",image,aad));
        encrypted[encrypted.length-1]^=1;Files.write(file,encrypted);refused(()->storage.read("task-1",image,aad));
    }

    @Test public void uniqueNoncesAndOriginalTaskBindingCannotBeReused()throws Exception {
        SecretKey key=key();byte[] aad="original".getBytes(StandardCharsets.UTF_8);
        assertFalse(Arrays.equals(TaskScreenshotStorage.seal(key,JPEG,aad),TaskScreenshotStorage.seal(key,JPEG,aad)));
        Path root=Files.createTempDirectory("screenshots-test-");TaskScreenshotStorage storage=storage(root,key);String image=UUID.randomUUID().toString();
        storage.write("task-1",image,JPEG,TaskScreenshotPolicy.aad(record("task-1",image)));
        Files.copy(root.resolve(storage.fileName("task-1",image)),root.resolve(storage.fileName("task-2",image)));
        refused(()->storage.read("task-2",image,TaskScreenshotPolicy.aad(record("task-2",image))));
        refused(()->storage.write("task-1",image,JPEG,TaskScreenshotPolicy.aad(record("task-1",image))));
    }

    @Test public void deletionIncludesLegacyAtomicFilesButPreservesOtherResultsAndExports()throws Exception {
        Path root=Files.createTempDirectory("screenshots-test-");TaskScreenshotStorage storage=storage(root,key());String image=UUID.randomUUID().toString();
        storage.write("task-1",image,JPEG,TaskScreenshotPolicy.aad(record("task-1",image)));
        String other=UUID.randomUUID().toString();storage.write("task-2",other,JPEG,TaskScreenshotPolicy.aad(record("task-2",other)));
        for(String name:new String[]{"task-1.jpg","task-1.jpg.bak","task-1.jpg.new","task-2.jpg","owner-export.jpg","skill-result.bin"})Files.write(root.resolve(name),JPEG);
        storage.deleteTask("task-1");
        for(String name:new String[]{"task-1.jpg","task-1.jpg.bak","task-1.jpg.new",storage.fileName("task-1",image)})assertFalse(name,Files.exists(root.resolve(name)));
        for(String name:new String[]{"task-2.jpg","owner-export.jpg","skill-result.bin",storage.fileName("task-2",other)})assertTrue(name,Files.exists(root.resolve(name)));
    }

    @Test public void legacyReadUsesTheOriginalAtomicBackupAndNeverTheUncommittedStage()throws Exception {
        Path root=Files.createTempDirectory("screenshots-test-");TaskScreenshotStorage storage=storage(root,key());
        Files.write(root.resolve("task-1.jpg.bak"),JPEG);Files.write(root.resolve("task-1.jpg.new"),new byte[]{9});
        assertTrue(storage.legacyExists("task-1"));assertArrayEquals(JPEG,storage.readLegacy("task-1"));
        storage.deleteLegacy("task-1");Files.write(root.resolve("task-1.jpg.new"),JPEG);assertFalse(storage.legacyExists("task-1"));refused(()->storage.readLegacy("task-1"));
    }

    @Test public void noPlaintextFallbackWhenKeyIsUnavailableAndNoSymlinkRead()throws Exception {
        Path root=Files.createTempDirectory("screenshots-test-");TaskScreenshotStorage broken=new TaskScreenshotStorage(root,new TaskScreenshotStorage.Crypto(){
            public byte[] seal(byte[] plain,byte[] aad)throws Exception{throw new java.security.KeyStoreException("unavailable");}
            public byte[] open(byte[] encrypted,byte[] aad)throws Exception{throw new java.security.KeyStoreException("unavailable");}
        });
        String image=UUID.randomUUID().toString();refused(()->broken.write("task-1",image,JPEG,TaskScreenshotPolicy.aad(record("task-1",image))));
        try(var files=Files.list(root)){assertEquals(0,files.count());}
        Path outside=Files.createTempFile("private-image-",".jpg");Files.write(outside,JPEG);Files.createSymbolicLink(root.resolve("task-1.jpg"),outside);
        TaskScreenshotStorage storage=storage(root,key());refused(()->storage.readLegacy("task-1"));storage.deleteLegacy("task-1");assertArrayEquals(JPEG,Files.readAllBytes(outside));
    }
    @Test public void authenticatedExpiryRemovesAnOrphanWithoutACommittedTaskPointer()throws Exception {
        Path root=Files.createTempDirectory("screenshots-test-");TaskScreenshotStorage storage=storage(root,key());
        String orphan=UUID.randomUUID().toString(),future=UUID.randomUUID().toString(),legacy=UUID.randomUUID().toString();
        JSONObject due=TaskScreenshotPolicy.record("uncommitted-task","run",1,orphan,1,2,JPEG.length,"image/jpeg",false);
        storage.write("uncommitted-task",orphan,JPEG,TaskScreenshotPolicy.aad(due));
        storage.write("future",future,JPEG,TaskScreenshotPolicy.aad(record("future",future)));
        storage.write("legacy",legacy,JPEG,TaskScreenshotPolicy.aad(TaskScreenshotPolicy.record("legacy","",0,legacy,1,0,JPEG.length,"image/jpeg",true)));
        Files.write(root.resolve("owner-export.jpg"),JPEG);assertEquals(2,storage.nextExpiry());
        assertEquals(1,storage.removeExpired(3).getInt("deleted"));assertFalse(Files.exists(root.resolve(storage.fileName("uncommitted-task",orphan))));
        assertTrue(Files.exists(root.resolve(storage.fileName("future",future))));assertTrue(Files.exists(root.resolve(storage.fileName("legacy",legacy))));assertTrue(Files.exists(root.resolve("owner-export.jpg")));
    }
    @Test public void aChangedExpiryHeaderCannotAuthorizeDeletingCiphertext()throws Exception {
        Path root=Files.createTempDirectory("screenshots-test-");TaskScreenshotStorage storage=storage(root,key());String image=UUID.randomUUID().toString();
        JSONObject metadata=TaskScreenshotPolicy.record("task","run",1,image,1,9,JPEG.length,"image/jpeg",false);byte[] aad=TaskScreenshotPolicy.aad(metadata);
        storage.write("task",image,JPEG,aad);Path path=root.resolve(storage.fileName("task",image));byte[] encrypted=Files.readAllBytes(path);
        byte[] changed=TaskScreenshotPolicy.aad(new JSONObject(metadata.toString()).put("expiresAt",2L));assertEquals(aad.length,changed.length);System.arraycopy(changed,0,encrypted,12,changed.length);Files.write(path,encrypted);
        JSONObject result=storage.removeExpired(3);assertEquals(0,result.getInt("deleted"));assertEquals(1,result.getInt("unconfirmed"));assertTrue(Files.exists(path));
    }
    @Test public void interruptedNativeStagesExpireButUnknownArtifactsRemain()throws Exception {
        Path root=Files.createTempDirectory("screenshots-test-");TaskScreenshotStorage storage=storage(root,key());String image=UUID.randomUUID().toString();
        JSONObject metadata=TaskScreenshotPolicy.record("task","run",1,image,1,2,JPEG.length,"image/jpeg",false);storage.write("task",image,JPEG,TaskScreenshotPolicy.aad(metadata));
        String base=storage.fileName("task",image).replace(".pws","");Path complete=root.resolve(base+"-123.pending");Files.move(root.resolve(storage.fileName("task",image)),complete);
        assertEquals(1,storage.removeExpired(3).getInt("deleted"));assertFalse(Files.exists(complete));
        Path partial=root.resolve(base+"-124.pending"),unknown=root.resolve(base+"-125.pending");
        Files.write(partial,"PWSC0001partial".getBytes(StandardCharsets.US_ASCII));Files.write(unknown,"user result".getBytes(StandardCharsets.US_ASCII));
        Files.setLastModifiedTime(partial,java.nio.file.attribute.FileTime.fromMillis(1));Files.setLastModifiedTime(unknown,java.nio.file.attribute.FileTime.fromMillis(1));
        assertEquals(1,storage.removeExpired(TaskScreenshotPolicy.DAY+2).getInt("deleted"));assertFalse(Files.exists(partial));assertTrue(Files.exists(unknown));
    }
}
