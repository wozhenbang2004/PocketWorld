package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerBackupPendingFilesTest {
    private static final String ID="12345678-1234-4234-8234-123456789abc";
    private static final char[] PASSWORD="synthetic process-death passphrase".toCharArray();
    private static byte[] plain(){byte[] bytes=new byte[OwnerBackupEnvelope.CHUNK_BYTES*3+17];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31+i/7);return bytes;}
    private static void remove(Path root)throws Exception{if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}

    @Test public void liveImportCannotBeSweptOrReplacedByAnotherImporter()throws Exception{
        Path root=Files.createTempDirectory("backup-live-fixture-");
        try{
            try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
                Path part=owned.pending(ID).toPath();try(FileChannel output=owned.createPlain(part.toFile())){output.write(java.nio.ByteBuffer.wrap(new byte[]{1,2,3}));}
                assertFalse(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));
                assertThrows(IOException.class,()->OwnerBackupPendingFiles.begin(root.toFile()));
                assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(part));
            }
            assertTrue(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertFalse(Files.exists(root.resolve(ID+".pending")));
        }finally{remove(root);}
    }

    @Test public void ownershipLockAlsoProtectsAnImportInAnotherRealProcess()throws Exception{
        Path root=Files.createTempDirectory("backup-process-lock-fixture-");Process child=null;
        try{
            child=child("active",root);BufferedReader messages=new BufferedReader(new InputStreamReader(child.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("ready",messages.readLine());assertFalse(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));
            assertThrows(IOException.class,()->OwnerBackupPendingFiles.begin(root.toFile()));assertTrue(Files.exists(root.resolve(ID+".pending")));
            child.getOutputStream().write('\n');child.getOutputStream().flush();assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
            assertTrue(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertFalse(Files.exists(root.resolve(ID+".pending")));
        }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor();}remove(root);}
    }

    @Test public void sameProcessRecoveryProbeDoesNotReleaseTheActiveOperatingSystemLock()throws Exception{
        Path root=Files.createTempDirectory("backup-second-fd-fixture-");Process child=null;
        try{
            try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
                File part=owned.pending(ID);try(FileChannel output=owned.createPlain(part)){output.write(java.nio.ByteBuffer.wrap(new byte[]{9}));}
                assertFalse(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));
                child=child("probe",root);BufferedReader messages=new BufferedReader(new InputStreamReader(child.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("busy",messages.readLine());assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue());assertTrue(Files.exists(part.toPath()));
            }
        }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor();}remove(root);}
    }

    @Test public void realProcessDeathDuringDecryptionLeavesKnownPlaintextThenReopeningRecoversOnlyPending()throws Exception{crashRecovery("mid",73,OwnerBackupEnvelope.CHUNK_BYTES);}
    @Test public void realProcessDeathAfterAuthenticationBeforePublicationIsAlsoRecovered()throws Exception{crashRecovery("complete",74,plain().length);}
    private void crashRecovery(String mode,int exit,int expectedLength)throws Exception{
        Path directory=Files.createTempDirectory("backup-crash-fixture-"),root=Files.createDirectory(directory.resolve("cache"));Process child=null;
        try{
            byte[] bytes=plain();String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            try(OutputStream out=Files.newOutputStream(directory.resolve("fixture.pwbackup"))){OwnerBackupEnvelope.encrypt(new ByteArrayInputStream(bytes),out,bytes.length,sha,PASSWORD,new RunControl());}
            Path retained=root.resolve("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.zip");byte[] sentinel={7,8,9};Files.write(retained,sentinel);
            child=child(mode,root);assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(exit,child.exitValue());
            Path partial=root.resolve(ID+".pending");assertArrayEquals(Arrays.copyOf(bytes,expectedLength),Files.readAllBytes(partial));
            assertTrue(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertFalse(Files.exists(partial));assertArrayEquals(sentinel,Files.readAllBytes(retained));
        }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor();}remove(directory);}
    }

    @Test public void successfulPublicationAndForeignNamesAreNeverSwept()throws Exception{
        Path root=Files.createTempDirectory("backup-preserve-fixture-");
        try{
            Path foreign=root.resolve("foreign.pending"),retained=root.resolve(ID+".zip"),uppercase=root.resolve("ABCDEFAB-1234-4234-8234-123456789abc.pending");
            Files.write(foreign,new byte[]{4});Files.write(uppercase,new byte[]{5});
            try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
                File part=owned.pending(ID);try(FileChannel output=owned.createPlain(part)){output.write(java.nio.ByteBuffer.wrap(new byte[]{6}));output.force(true);}
                assertFalse(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));Files.move(part.toPath(),retained,StandardCopyOption.ATOMIC_MOVE);
            }
            assertTrue(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertArrayEquals(new byte[]{6},Files.readAllBytes(retained));
            assertArrayEquals(new byte[]{4},Files.readAllBytes(foreign));assertArrayEquals(new byte[]{5},Files.readAllBytes(uppercase));
        }finally{remove(root);}
    }

    @Test public void refusesPendingSymlinkAndDirectoryWithoutTouchingTheirTargets()throws Exception{
        Path root=Files.createTempDirectory("backup-no-follow-fixture-"),outside=Files.createTempFile("backup-sentinel-fixture-",".bin");
        try{
            Files.write(outside,new byte[]{7});Path link=root.resolve(ID+".pending");Files.createSymbolicLink(link,outside);
            assertThrows(IOException.class,()->OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertTrue(Files.isSymbolicLink(link));assertArrayEquals(new byte[]{7},Files.readAllBytes(outside));
            Files.delete(link);Files.createDirectory(link);Files.write(link.resolve("unrelated"),new byte[]{8});
            assertThrows(IOException.class,()->OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertArrayEquals(new byte[]{8},Files.readAllBytes(link.resolve("unrelated")));
        }finally{remove(root);Files.delete(outside);}
    }

    @Test public void privateRootAndPlainImportFilesUseOwnerOnlyModesAndExistingTargetsAreRefused()throws Exception{
        Path root=Files.createTempDirectory("backup-mode-fixture-");
        try{
            Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxrwxrwx"));
            try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
                assertEquals("rwx------",PosixFilePermissions.toString(Files.getPosixFilePermissions(root)));
                File part=owned.pending(ID);try(FileChannel ignored=owned.createPlain(part)){}
                assertEquals("rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(part.toPath())));
                assertThrows(IOException.class,()->owned.pending(ID));assertThrows(IOException.class,()->owned.pending("../elsewhere"));
                assertThrows(IOException.class,()->owned.createPlain(root.resolve("outside.zip").toFile()));
            }
        }finally{remove(root);}
    }

    @Test public void rootAndLockSymlinksAreRefusedAndDoNotChangeExternalFiles()throws Exception{
        Path directory=Files.createTempDirectory("backup-root-fixture-"),real=Files.createDirectory(directory.resolve("real")),link=directory.resolve("link"),outside=directory.resolve("sentinel");
        try{
            Files.createSymbolicLink(link,real);assertThrows(IOException.class,()->OwnerBackupPendingFiles.begin(link.toFile()));
            Files.write(outside,new byte[]{2});Path guard=real.resolve(".owner-archive-pending-v1.lock");Files.createSymbolicLink(guard,outside);
            assertThrows(IOException.class,()->OwnerBackupPendingFiles.begin(real.toFile()));assertTrue(Files.isSymbolicLink(guard));assertArrayEquals(new byte[]{2},Files.readAllBytes(outside));
        }finally{remove(directory);}
    }

    @Test public void legacyCapturePlaintextLeftByARealDeadProcessIsRecovered()throws Exception{
        Path root=Files.createTempDirectory("backup-legacy-capture-fixture-");Process child=null;
        try{
            child=child("legacy-capture",root);assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(75,child.exitValue());
            Path stage;try(var paths=Files.list(root)){stage=paths.filter(p->p.getFileName().toString().startsWith("capture-")).findFirst().orElseThrow();}
            assertArrayEquals(plain(),Files.readAllBytes(stage.resolve("a".repeat(64)+".json")));
            assertTrue(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertFalse(Files.exists(stage));
        }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor();}remove(root);}
    }

    @Test public void realDeathAfterProductionArchiveCreationRecoversCaptureAndUnpublishedZip()throws Exception{
        Path root=Files.createTempDirectory("backup-capture-zip-fixture-");Process child=null;
        try{
            child=child("capture-complete",root);assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(76,child.exitValue());
            Path stage=root.resolve("capture-"+ID),pending=root.resolve(ID+".pending");assertTrue(Files.isDirectory(stage));
            assertEquals("rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(pending)));
            assertEquals(1,OwnerBackupArchive.verify(pending.toFile(),new RunControl()).metadata().getInt("objectCount"));
            assertTrue(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertFalse(Files.exists(stage));assertFalse(Files.exists(pending));
            assertFalse(Files.exists(root.resolve(ID+".zip")));
        }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor();}remove(root);}
    }

    @Test public void liveCaptureAndSuccessfulOriginalArchivePublicationKeepTheirContract()throws Exception{
        Path root=Files.createTempDirectory("backup-capture-publish-fixture-");
        try{
            String sha;Path target=root.resolve(ID+".zip");
            try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
                File stage=owned.capture(ID);assertEquals("rwx------",PosixFilePermissions.toString(Files.getPosixFilePermissions(stage.toPath())));
                assertFalse(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertTrue(stage.isDirectory());
                var object=OwnerBackupArchive.ObjectBytes.json(new org.json.JSONObject().put("fixture","synthetic only"));
                File pending=owned.pending(ID);var original=OwnerBackupArchive.create(pending,Map.of(object.sha256,object),Map.of("memory",object.sha256),new RunControl());sha=original.sha256();
                owned.publish(pending,target.toFile(),original,new RunControl());owned.discardCapture(stage);
            }
            assertTrue(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));var retained=OwnerBackupArchive.verify(target.toFile(),new RunControl());
            assertEquals(sha,retained.sha256());assertEquals("synthetic only",retained.section("memory",new RunControl()).getString("fixture"));
        }finally{remove(root);}
    }

    @Test public void unknownCaptureContentsAndSymlinksAreRefusedBeforeDeletingKnownObjects()throws Exception{
        Path root=Files.createTempDirectory("backup-capture-unknown-fixture-"),outside=Files.createTempDirectory("backup-capture-outside-fixture-");
        try{
            Path stage=Files.createDirectory(root.resolve("capture-"+ID)),known=stage.resolve("a".repeat(64)+".json"),unknown=stage.resolve("unknown");Files.write(known,new byte[]{1});Files.createDirectory(unknown);
            assertThrows(IOException.class,()->OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertArrayEquals(new byte[]{1},Files.readAllBytes(known));assertTrue(Files.isDirectory(unknown));
            Files.delete(unknown);Path sentinel=outside.resolve("sentinel");Files.write(sentinel,new byte[]{2});Files.createSymbolicLink(stage.resolve(ID+".file"),sentinel);
            assertThrows(IOException.class,()->OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertArrayEquals(new byte[]{1},Files.readAllBytes(known));assertArrayEquals(new byte[]{2},Files.readAllBytes(sentinel));
        }finally{remove(root);remove(outside);}
    }

    private static OwnerBackupArchive.Verified makeArchive(File pending)throws Exception{
        var object=OwnerBackupArchive.ObjectBytes.json(new org.json.JSONObject().put("fixture","synthetic publication only"));
        return OwnerBackupArchive.create(pending,Map.of(object.sha256,object),Map.of("memory",object.sha256),new RunControl());
    }

    @Test public void publicationRefusesAnExistingZipWithoutChangingIt()throws Exception{
        Path root=Files.createTempDirectory("backup-no-replace-fixture-");
        try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
            File pending=owned.pending(ID);var exact=makeArchive(pending);Path target=root.resolve(ID+".zip");byte[] sentinel={3,4,5};Files.write(target,sentinel);
            assertThrows(FileAlreadyExistsException.class,()->owned.publish(pending,target.toFile(),exact,new RunControl()));
            assertArrayEquals(sentinel,Files.readAllBytes(target));assertTrue(pending.isFile());
        }finally{remove(root);}
    }

    @Test public void sameUidTargetReservationDuringPublicationCannotBeOverwritten()throws Exception{
        Path root=Files.createTempDirectory("backup-publication-race-fixture-");
        try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
            File pending=owned.pending(ID);var exact=makeArchive(pending);Path target=root.resolve(ID+".zip");byte[] sentinel={6,7,8};RunControl race=new RunControl();
            java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);
            // Reserve after publish starts, inside its original source checkpoint, before link(2).
            race.setAuthorityCheck(()->{if(first.getAndSet(false))Files.write(target,sentinel,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);});
            assertThrows(FileAlreadyExistsException.class,()->owned.publish(pending,target.toFile(),exact,race));
            assertArrayEquals(sentinel,Files.readAllBytes(target));assertTrue(pending.isFile());
        }finally{remove(root);}
    }

    @Test public void changedPendingInodeCannotBePublishedUnderTheOriginalVerification()throws Exception{
        Path root=Files.createTempDirectory("backup-publication-source-fixture-");
        try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
            File pending=owned.pending(ID);var exact=makeArchive(pending);Path retained=root.resolve("prior-source.fixture");Files.move(pending.toPath(),retained);
            try(FileChannel output=owned.createPlain(pending)){output.write(java.nio.ByteBuffer.wrap(new byte[]{9}));}
            assertThrows(SecurityException.class,()->owned.publish(pending,root.resolve(ID+".zip").toFile(),exact,new RunControl()));
            assertFalse(Files.exists(root.resolve(ID+".zip")));assertEquals(exact.sha256(),OwnerBackupArchive.verify(retained.toFile(),new RunControl()).sha256());
        }finally{remove(root);}
    }

    @Test public void realDeathAfterLinkBeforeUnlinkRetainsThePublishedZipWhenPendingIsRecovered()throws Exception{
        Path root=Files.createTempDirectory("backup-link-death-fixture-");Process child=null;
        try{
            child=child("after-link",root);assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(77,child.exitValue());
            Path pending=root.resolve(ID+".pending"),target=root.resolve(ID+".zip");assertTrue(Files.isSameFile(pending,target));String sha=OwnerBackupArchive.verify(target.toFile(),new RunControl()).sha256();
            assertTrue(OwnerBackupPendingFiles.recoverIfIdle(root.toFile()));assertFalse(Files.exists(pending));assertEquals(sha,OwnerBackupArchive.verify(target.toFile(),new RunControl()).sha256());
        }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor();}remove(root);}
    }

    @Test public void cleanupFailureAfterPublicationReportsKnownCacheAndDoesNotInviteReplay()throws Exception{
        Path root=Files.createTempDirectory("backup-published-cleanup-fixture-");
        try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
            File stage=owned.capture(ID),pending=owned.pending(ID);var exact=makeArchive(pending);Path target=root.resolve(ID+".zip");owned.publish(pending,target.toFile(),exact,new RunControl());
            Path unknown=Files.createDirectory(stage.toPath().resolve("unknown"));
            IOException failure=assertThrows(IOException.class,()->owned.finishCapture(stage,pending,true));
            assertTrue(failure.getMessage().contains("已保存为本机缓存"));assertTrue(failure.getMessage().contains("核对"));assertTrue(failure.getMessage().contains("不要自动重试"));
            assertNotNull(failure.getCause());assertTrue(Files.isDirectory(unknown));assertEquals(exact.sha256(),OwnerBackupArchive.verify(target.toFile(),new RunControl()).sha256());
        }finally{remove(root);}
    }

    @Test public void publicationAndCleanupRefuseOtherDestinations()throws Exception{
        Path root=Files.createTempDirectory("backup-publication-path-fixture-"),outside=Files.createTempFile("backup-outside-fixture-",".bin");
        try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
            File stage=owned.capture(ID),pending=owned.pending(ID);var exact=makeArchive(pending);Files.write(outside,new byte[]{7});
            assertThrows(IOException.class,()->owned.publish(pending,outside.toFile(),exact,new RunControl()));
            assertThrows(IOException.class,()->owned.finishCapture(stage,outside.toFile(),false));assertArrayEquals(new byte[]{7},Files.readAllBytes(outside));assertTrue(pending.isFile());
        }finally{remove(root);Files.delete(outside);}
    }

    private static Process child(String mode,Path root)throws IOException{
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),CrashChild.class.getName(),mode,root.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    public static final class CrashChild {
        public static void main(String[] args)throws Exception{
            String mode=args[0];Path root=Path.of(args[1]);
            if(mode.equals("probe")){System.out.println(OwnerBackupPendingFiles.recoverIfIdle(root.toFile())?"recovered":"busy");return;}
            if(mode.equals("legacy-capture")){
                Path stage=Files.createTempDirectory(root,"capture-");Files.write(stage.resolve("a".repeat(64)+".json"),plain());Runtime.getRuntime().halt(75);
            }
            try(OwnerBackupPendingFiles owned=OwnerBackupPendingFiles.begin(root.toFile())){
                File pending=owned.pending(ID);
                if(mode.equals("after-link")){
                    var exact=makeArchive(pending);Path target=root.resolve(ID+".zip");RunControl stop=new RunControl();
                    stop.setAuthorityCheck(()->{if(Files.exists(target))Runtime.getRuntime().halt(77);});owned.publish(pending,target.toFile(),exact,stop);throw new AssertionError("child should halt after link");
                }
                if(mode.equals("capture-complete")){
                    File stage=owned.capture(ID);try(FileChannel file=OwnerBackupPendingFiles.privateFile(new File(stage,"a".repeat(64)+".json"))){file.write(java.nio.ByteBuffer.wrap(plain()));file.force(true);}
                    var object=OwnerBackupArchive.ObjectBytes.json(new org.json.JSONObject().put("fixture","synthetic only"));
                    OwnerBackupArchive.create(pending,Map.of(object.sha256,object),Map.of("memory",object.sha256),new RunControl());Runtime.getRuntime().halt(76);
                }
                if(mode.equals("active")){
                    try(FileChannel output=owned.createPlain(pending)){output.write(java.nio.ByteBuffer.wrap(new byte[]{1}));output.force(true);}
                    System.out.println("ready");System.out.flush();System.in.read();return;
                }
                InputStream source=new FilterInputStream(Files.newInputStream(root.getParent().resolve("fixture.pwbackup"))){int calls;
                    @Override public int read(byte[] b,int off,int len)throws IOException{if(mode.equals("mid")&&++calls==3)Runtime.getRuntime().halt(73);return super.read(b,off,len);}
                };
                OwnerBackupEnvelope.decryptToNewFile(source,pending,PASSWORD,new RunControl());Runtime.getRuntime().halt(74);
            }
        }
    }
}
