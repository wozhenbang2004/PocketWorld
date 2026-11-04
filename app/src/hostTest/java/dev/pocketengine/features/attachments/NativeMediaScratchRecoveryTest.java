package dev.pocketengine.features.attachments;

import android.app.Application;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.processing.NativeMediaTrim;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.media.NativeMediaScratch;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Production copy/cleanup and real hard-killed host children. The codec-only worker
 * shadows extractor/muxer boundaries, with a fixed track shape solely to reach the
 * release-failure branch. No real container parsing or encoding occurs; these tests do
 * not establish Android codec, device death, or cross-process live-lease safety. */
public final class NativeMediaScratchRecoveryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final Map<Long,Path> workerTemporaryDirectories=new java.util.HashMap<>();
    private static byte[] mp4(){byte[] bytes=new byte[65536];bytes[4]='f';bytes[5]='t';bytes[6]='y';bytes[7]='p';return bytes;}
    private static OwnerFilePreviewSource source(byte[] bytes,OwnerFilePreviewSource.Current current)throws Exception {
        JSONObject metadata=new JSONObject().put("id","original").put("name","source.mp4").put("mimeType","video/mp4").put("sizeBytes",bytes.length).put("sha256",ProjectSources.sha256(bytes));
        return new OwnerFilePreviewSource("attachments",metadata,c->bytes.clone(),current);
    }
    private static AutoCloseable original(File cache,OwnerFilePreviewSource source,RunControl control)throws Exception {
        Class<?> type=Class.forName(NativeMediaTrim.class.getName()+"$Original");var constructor=type.getDeclaredConstructor(OwnerFilePreviewSource.class,File.class,RunControl.class);constructor.setAccessible(true);
        try{return (AutoCloseable)constructor.newInstance(source,cache,control);}catch(InvocationTargetException failure){throw cause(failure);}
    }
    private static void validate(byte[] bytes,File cache,RunControl control)throws Exception {
        var method=Class.forName("dev.pocketengine.media.NativeMediaValidation").getDeclaredMethod("mp4",byte[].class,File.class,RunControl.class);method.setAccessible(true);
        try{method.invoke(null,bytes,cache,control);}catch(InvocationTargetException failure){throw cause(failure);}
    }
    private static Exception cause(InvocationTargetException failure){if(failure.getCause() instanceof Error error)throw error;return (Exception)failure.getCause();}
    private static Path[] files(File cache,String root)throws Exception {Path directory=cache.toPath().resolve(root);if(!Files.isDirectory(directory))return new Path[0];try(var files=Files.list(directory)){return files.toArray(Path[]::new);}}
    private static Path only(File cache,String root,String prefix)throws Exception {Path[] all=Arrays.stream(files(cache,root)).filter(p->p.getFileName().toString().startsWith(prefix)).toArray(Path[]::new);assertEquals(1,all.length);return all[0];}
    private static void seed(File cache,String root,String prefix,int count)throws Exception {Path directory=cache.toPath().resolve(root);Files.createDirectories(directory);for(int i=0;i<count;i++)Files.write(directory.resolve(prefix+(100+i)+".mp4"),new byte[]{(byte)i});}
    private static void await(CountDownLatch latch)throws Exception {assertTrue("Worker gate not reached",latch.await(20,TimeUnit.SECONDS));}
    private static final class CopyReached extends RuntimeException {}
    private static final class ExtractorReleaseFailure extends RuntimeException {}
    private static final class MuxerReleaseFailure extends RuntimeException {}

    private Process worker(File cache,String action,Path marker,Path log)throws Exception {
        long minimum=Long.getLong("pocketworldMediaMinimumFreeBytes",0L),free=temporary.getRoot().getUsableSpace();
        if(minimum>0){assertTrue("Setup: no child JVM started; free "+free+" below "+minimum,free>=minimum);System.out.println("Child space gate action="+action+" free="+free);}
        Path nativeTmp=temporary.getRoot().toPath().resolve("worker-"+UUID.randomUUID());Files.createDirectories(nativeTmp);
        var command=new java.util.ArrayList<String>(Arrays.asList(new File(System.getProperty("java.home"),"bin/java").getPath(),"-Xmx192m","-XX:ActiveProcessorCount=2","-XX:MaxMetaspaceSize=160m","-XX:ReservedCodeCacheSize=32m","-Djava.io.tmpdir="+nativeTmp,"-Dpocketworld.media.cache="+cache.getAbsolutePath(),"-Dpocketworld.media.action="+action,"-Dpocketworld.media.marker="+marker,"-Dpocketworld.media.workerTmp="+nativeTmp));
        for(String key:new String[]{"robolectric.offline","robolectric.dependency.dir"})if(System.getProperty(key)!=null)command.add("-D"+key+"="+System.getProperty(key));
        command.addAll(Arrays.asList("-cp",System.getProperty("pocketworldHostClasspath",System.getProperty("java.class.path")),"org.junit.runner.JUnitCore",CodecWorker.class.getName()));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();workerTemporaryDirectories.put(process.pid(),nativeTmp);return process;
    }
    private void removeWorkerTmp(Process process)throws Exception {
        Path root=workerTemporaryDirectories.remove(process.pid());if(root==null)return;
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private Path crash(File cache,String action)throws Exception {
        Path marker=temporary.getRoot().toPath().resolve(action+".ready"),log=Path.of(marker+".log");Process child=worker(cache,action,marker,log);
        try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!Files.isRegularFile(marker)&&child.isAlive()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertTrue("Crash point not reached: "+Files.readString(log),Files.isRegularFile(marker));Path retained=Path.of(Files.readString(marker));assertTrue(Files.isRegularFile(retained));
            child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertNotEquals(0,child.exitValue());System.out.println("Hard-killed media writer pid="+child.pid()+" boundary="+action+" bytes="+Files.size(retained));return retained;
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}assertFalse(child.isAlive());removeWorkerTmp(child);}
    }
    private void runWorker(File cache,String action)throws Exception {
        Path marker=temporary.getRoot().toPath().resolve(action+"-"+UUID.randomUUID()+".ready"),log=Path.of(marker+".log");Process child=worker(cache,action,marker,log);
        try{assertTrue("Worker timed out",child.waitFor(60,TimeUnit.SECONDS));assertEquals(Files.readString(log),0,child.exitValue());System.out.println("Fresh media worker pid="+child.pid()+" action="+action+" exit="+child.exitValue());}
        finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}assertFalse(child.isAlive());removeWorkerTmp(child);}
    }
    @Test public void hardKilledTrimSourceDoesNotPermanentlyConsumeTheOriginalQuota()throws Exception {
        File cache=temporary.newFolder();Path retained=crash(cache,"crash-source");assertEquals(mp4().length,Files.size(retained));seed(cache,"original-media-processing","trim-source-",8);
        runWorker(cache,"recover-source");assertFalse(Files.exists(retained));assertEquals(0,files(cache,"original-media-processing").length);
    }
    @Test public void hardKilledTrimOutputAndItsInputAreReclaimedBeforeTheNextOriginal()throws Exception {
        File cache=temporary.newFolder();Path output=crash(cache,"crash-output");assertEquals(0,Files.size(output));seed(cache,"original-media-processing","trim-output-",7);
        runWorker(cache,"recover-source");assertFalse(Files.exists(output));assertEquals(0,files(cache,"original-media-processing").length);
    }
    @Test public void hardKilledProviderPartialCopyRecoversOnAColdValidationInvocation()throws Exception {
        File cache=temporary.newFolder();Path retained=crash(cache,"crash-validation-partial");assertEquals(32768,Files.size(retained));seed(cache,"provider-media-validation","original-provider-",4);runWorker(cache,"recover-validation");assertFalse(Files.exists(retained));
    }
    @Test public void hardKilledProviderCompleteCopyRecoversOnAColdValidationInvocation()throws Exception {
        File cache=temporary.newFolder();Path retained=crash(cache,"crash-validation-complete");assertEquals(mp4().length,Files.size(retained));seed(cache,"provider-media-validation","original-provider-",4);runWorker(cache,"recover-validation");assertFalse(Files.exists(retained));
    }
    @Test public void aConcurrentOriginalCannotSweepTheLiveSourceAfterItsWrite()throws Exception {
        // Isolate scratch ownership from the separate public trim semaphore. This
        // reflected contender is not another owner-authorized execution entry.
        File cache=temporary.newFolder();CountDownLatch copied=new CountDownLatch(1),release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();byte[] bytes=mp4();
        var first=pool.submit(()->original(cache,source(bytes,c->{if(files(cache,"original-media-processing").length==1){copied.countDown();await(release);}}),new RunControl()));
        try{await(copied);Path live=only(cache,"original-media-processing","trim-source-");Files.setLastModifiedTime(live,java.nio.file.attribute.FileTime.fromMillis(1));
            try(var second=original(cache,source(bytes,c->{}),new RunControl())){assertArrayEquals(bytes,Files.readAllBytes(live));}release.countDown();first.get(10,TimeUnit.SECONDS).close();assertEquals(0,files(cache,"original-media-processing").length);
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test public void codecReleaseStillOwnsTheTrimOutputLease()throws Exception {runWorker(temporary.newFolder(),"live-output-release");}
    @Test public void anErrorAfterTheSourceCopyStillClosesItsNewLease()throws Exception {
        File cache=temporary.newFolder();assertThrows(AssertionError.class,()->original(cache,source(mp4(),c->{if(files(cache,"original-media-processing").length>0)throw new AssertionError("Controlled source rejection after copy");}),new RunControl()));assertEquals(0,files(cache,"original-media-processing").length);
        try(var next=original(cache,source(mp4(),c->{}),new RunControl())){assertEquals(1,files(cache,"original-media-processing").length);}assertEquals(0,files(cache,"original-media-processing").length);
    }
    @Test public void validationCopyKeepsItsLeaseDuringAnotherValidation()throws Exception {runWorker(temporary.newFolder(),"live-validation-copy");}
    @Test public void codecReleaseStillOwnsTheValidationLease()throws Exception {runWorker(temporary.newFolder(),"live-validation-release");}
    @Test public void cancellingReleaseMustFinishBeforeFinallyCanDropTheValidationLease()throws Exception {runWorker(temporary.newFolder(),"live-validation-cancel-release");}
    @Test public void aLateSnapshottedCancelCallbackCannotReleaseAfterFinally()throws Exception {runWorker(temporary.newFolder(),"late-validation-cancel-release");}
    @Test public void extractorConstructorFailuresDoNotLeaveValidationOrOutputCopies()throws Exception {runWorker(temporary.newFolder(),"constructor-failure");}
    @Test public void releaseFailuresStillReleaseTheOtherCodecAndRemoveBothCopies()throws Exception {runWorker(temporary.newFolder(),"release-failure");}
    @Test public void unknownEntriesStillRefuseQuotaAndAreNeverReclassifiedAsScratch()throws Exception {
        File cache=temporary.newFolder();for(var entry:Map.of("original-media-processing",9,"provider-media-validation",5).entrySet()){Path root=cache.toPath().resolve(entry.getKey());Files.createDirectories(root);for(int i=0;i<entry.getValue();i++)Files.write(root.resolve("unknown-"+i+".mp4"),new byte[]{42});}
        assertThrows(java.io.IOException.class,()->original(cache,source(mp4(),c->{}),new RunControl()));assertThrows(java.io.IOException.class,()->validate(mp4(),cache,new RunControl()));
        assertEquals(9,files(cache,"original-media-processing").length);assertEquals(5,files(cache,"provider-media-validation").length);
    }
    @Test public void onlyOrdinaryExactScratchNamesAreReclaimedAndLinkedRootsAreRefused()throws Exception {
        File cache=temporary.newFolder();Path root=cache.toPath().resolve("original-media-processing");Files.createDirectories(root);Path evidence=temporary.newFile().toPath();Files.write(evidence,new byte[]{51});
        Path link=root.resolve("trim-source-123.mp4");Files.createSymbolicLink(link,evidence);Path nested=root.resolve("trim-output-124.mp4");Files.createDirectory(nested);Path unknown=root.resolve("trim-source-private.mp4");Files.write(unknown,new byte[]{52});Path part=root.resolve("trim-source-125.mp4.part");Files.write(part,new byte[]{53});seed(cache,"original-media-processing","trim-source-",1);
        try(var lease=NativeMediaScratch.trimSource(cache)){assertTrue(Files.isSymbolicLink(link));assertTrue(Files.isDirectory(nested));assertEquals(52,Files.readAllBytes(unknown)[0]);assertEquals(53,Files.readAllBytes(part)[0]);assertArrayEquals(new byte[]{51},Files.readAllBytes(evidence));assertFalse(Files.exists(root.resolve("trim-source-100.mp4")));}
        Path provider=cache.toPath().resolve("provider-media-validation");Files.createDirectories(provider);Path providerLink=provider.resolve("original-provider-123.mp4");Files.createSymbolicLink(providerLink,evidence);seed(cache,"provider-media-validation","original-provider-",1);
        try(var lease=NativeMediaScratch.providerValidation(cache)){assertTrue(Files.isSymbolicLink(providerLink));assertArrayEquals(new byte[]{51},Files.readAllBytes(evidence));assertFalse(Files.exists(provider.resolve("original-provider-100.mp4")));}
        File linked=temporary.newFolder();Files.createSymbolicLink(linked.toPath().resolve("provider-media-validation"),root);assertThrows(java.io.IOException.class,()->NativeMediaScratch.providerValidation(linked));assertTrue(Files.exists(unknown));
    }
    @Test public void separateDurableBookAndUncertainNativeEvidenceAreUnchanged()throws Exception {
        File durable=temporary.newFolder(),cache=temporary.newFolder();AttachmentBook book=new AttachmentBook(durable);JSONObject saved=book.importBytes(new ByteArrayInputStream(new byte[]{61}),"original.mp4","video/mp4",new JSONObject());String id=book.findNativeResult(UUID.randomUUID().toString(),"document_output","","","a".repeat(64),new RunControl()).getString("id");
        for(String suffix:new String[]{".blob",".part",".json.pending"})Files.write(durable.toPath().resolve(id+suffix),new byte[]{62});var before=new java.util.HashMap<Path,byte[]>();for(File file:durable.listFiles())before.put(file.toPath(),Files.readAllBytes(file.toPath()));
        seed(cache,"original-media-processing","trim-source-",9);seed(cache,"provider-media-validation","original-provider-",5);try(var trim=NativeMediaScratch.trimSource(cache);var validation=NativeMediaScratch.providerValidation(cache)){}
        for(var entry:before.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()));assertArrayEquals(new byte[]{61},book.readVerifiedSnapshot(saved.getString("id"),saved.getString("sha256"),new RunControl()));
    }

    @RunWith(RobolectricTestRunner.class)
    @Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows={CodecBoundary.class,MuxerBoundary.class})
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @SQLiteMode(SQLiteMode.Mode.LEGACY)
    public static final class CodecWorker {
        static volatile CountDownLatch releasing,release;
        static volatile boolean extractorReleased,muxerReleased;
        static final java.util.concurrent.atomic.AtomicBoolean releaseClaimed=new java.util.concurrent.atomic.AtomicBoolean();
        static final java.util.concurrent.atomic.AtomicBoolean cancelReleaseClaimed=new java.util.concurrent.atomic.AtomicBoolean();
        static final java.util.concurrent.atomic.AtomicInteger releaseCalls=new java.util.concurrent.atomic.AtomicInteger();
        static String action(){return System.getProperty("pocketworld.media.action");}
        static File cache(){return new File(System.getProperty("pocketworld.media.cache"));}
        private static void park(Path path)throws Exception {
            Path marker=Path.of(System.getProperty("pocketworld.media.marker")),pending=Path.of(marker+".pending");Files.writeString(pending,path.toString());Files.move(pending,marker,java.nio.file.StandardCopyOption.ATOMIC_MOVE);for(;;)Thread.sleep(1000);
        }
        private static void copyBoundary(RunControl control,boolean partial)throws Exception {
            control.setAuthorityCheck(()->{Path[] files=files(cache(),"provider-media-validation");if(files.length==1&&Files.size(files[0])==(partial?32768:mp4().length))park(files[0]);});
        }
        private static void validationUntilCopied()throws Exception {assertThrows(CopyReached.class,()->validate(mp4(),cache(),new RunControl()));}
        private static void executeUntilCodec()throws Exception {
            OwnerFilePreviewSource source=source(mp4(),c->{});JSONObject track=new JSONObject().put("index",0).put("mimeType","video/avc").put("durationUs",1000000);if(action().equals("release-failure"))track.put("width",16).put("height",16);JSONArray tracks=new JSONArray().put(track);var constructor=NativeMediaTrim.Review.class.getDeclaredConstructor(OwnerFilePreviewSource.class,long.class,long.class,long.class,long.class,JSONArray.class);constructor.setAccessible(true);
            NativeMediaTrim.execute(constructor.newInstance(source,0L,1000000L,0L,1000000L,tracks),cache(),new RunControl());
        }
        private static void cancellationReleaseRace()throws Exception {
            RunControl control=new RunControl();CountDownLatch copied=new CountDownLatch(1),finishCopy=new CountDownLatch(1);releasing=new CountDownLatch(1);release=new CountDownLatch(1);var owner=new java.util.concurrent.atomic.AtomicReference<Thread>();var pool=Executors.newFixedThreadPool(2);
            control.setAuthorityCheck(()->{Path[] files=files(cache(),"provider-media-validation");if(files.length==1&&Files.size(files[0])==mp4().length){copied.countDown();await(finishCopy);}});
            var validation=pool.submit(()->{owner.set(Thread.currentThread());assertThrows(InterruptedException.class,()->validate(mp4(),cache(),control));return null;});
            try{await(copied);Path live=only(cache(),"provider-media-validation","original-provider-");var cancelling=pool.submit(()->{control.cancel();return null;});await(releasing);finishCopy.countDown();
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!validation.isDone()&&owner.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<until)Thread.sleep(10);
                assertFalse("Validation finally returned while cancellation release was still in progress",validation.isDone());assertEquals(Thread.State.BLOCKED,owner.get().getState());assertTrue(Arrays.stream(owner.get().getStackTrace()).anyMatch(frame->frame.getClassName().equals("dev.pocketengine.media.NativeMediaValidation")&&frame.getMethodName().equals("mp4")));
                assertArrayEquals(mp4(),Files.readAllBytes(live));validationUntilCopied();assertArrayEquals(mp4(),Files.readAllBytes(live));
                release.countDown();cancelling.get(10,TimeUnit.SECONDS);validation.get(10,TimeUnit.SECONDS);assertEquals(0,files(cache(),"provider-media-validation").length);
            }finally{finishCopy.countDown();release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));releasing=null;release=null;}
        }
        private static void lateCancellationRelease()throws Exception {
            RunControl control=new RunControl();CountDownLatch copied=new CountDownLatch(1),finishCopy=new CountDownLatch(1),snapshotEntered=new CountDownLatch(1),resumeCancellation=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            // RunControl's iterator has captured the validation callback before this
            // earlier registration blocks. Removing it later cannot retract that snapshot.
            control.onCancel(()->{snapshotEntered.countDown();try{await(resumeCancellation);}catch(Exception failure){throw new AssertionError(failure);}});
            control.setAuthorityCheck(()->{Path[] files=files(cache(),"provider-media-validation");if(files.length==1&&Files.size(files[0])==mp4().length){copied.countDown();await(finishCopy);}});
            var validation=pool.submit(()->{assertThrows(InterruptedException.class,()->validate(mp4(),cache(),control));return null;});
            try{await(copied);var cancelling=pool.submit(()->{control.cancel();return null;});await(snapshotEntered);finishCopy.countDown();validation.get(10,TimeUnit.SECONDS);assertEquals(0,files(cache(),"provider-media-validation").length);assertEquals(1,releaseCalls.get());
                resumeCancellation.countDown();cancelling.get(10,TimeUnit.SECONDS);assertEquals("Late callback must not enter actual extractor release",1,releaseCalls.get());
            }finally{finishCopy.countDown();resumeCancellation.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        }
        @Test public void scenario()throws Exception {
            String action=action();
            if(action.equals("live-validation-cancel-release")){cancellationReleaseRace();return;}
            if(action.equals("late-validation-cancel-release")){lateCancellationRelease();return;}
            if(action.equals("crash-source")){try(var original=original(cache(),source(mp4(),c->{Path[] files=files(cache(),"original-media-processing");if(files.length==1&&Files.size(files[0])==mp4().length)park(files[0]);}),new RunControl())){}throw new AssertionError("Crash source returned");}
            if(action.equals("crash-output")){executeUntilCodec();throw new AssertionError("Crash output returned");}
            if(action.startsWith("crash-validation")){RunControl control=new RunControl();copyBoundary(control,action.endsWith("partial"));validate(mp4(),cache(),control);throw new AssertionError("Crash validation returned");}
            if(action.equals("recover-source")){try(var current=original(cache(),source(mp4(),c->{}),new RunControl())){assertEquals(1,files(cache(),"original-media-processing").length);}assertEquals(0,files(cache(),"original-media-processing").length);return;}
            if(action.equals("recover-validation")){validationUntilCopied();assertEquals(0,files(cache(),"provider-media-validation").length);return;}
            if(action.equals("constructor-failure")){assertThrows(CopyReached.class,()->validate(mp4(),cache(),new RunControl()));assertEquals(0,files(cache(),"provider-media-validation").length);assertThrows(CopyReached.class,CodecWorker::executeUntilCodec);assertEquals(0,files(cache(),"original-media-processing").length);return;}
            if(action.equals("release-failure")){assertThrows(ExtractorReleaseFailure.class,()->validate(mp4(),cache(),new RunControl()));assertEquals(0,files(cache(),"provider-media-validation").length);extractorReleased=false;assertThrows(MuxerReleaseFailure.class,CodecWorker::executeUntilCodec);assertTrue(extractorReleased);assertTrue(muxerReleased);assertEquals(0,files(cache(),"original-media-processing").length);return;}
            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();String folder=action.equals("live-output-release")?"original-media-processing":"provider-media-validation";
            if(action.endsWith("release")){releasing=entered;release=resume;releaseClaimed.set(false);}
            var first=pool.submit(()->{if(action.equals("live-output-release")){assertThrows(CopyReached.class,CodecWorker::executeUntilCodec);}else{RunControl control=new RunControl();if(action.endsWith("copy"))control.setAuthorityCheck(()->{Path[] files=files(cache(),folder);if(files.length==1&&Files.size(files[0])==32768){entered.countDown();await(resume);}});assertThrows(CopyReached.class,()->validate(mp4(),cache(),control));}return null;});
            try{await(entered);Path[] active=files(cache(),folder);assertTrue(active.length>0);var bytes=new java.util.HashMap<Path,byte[]>();for(Path path:active)bytes.put(path,Files.readAllBytes(path));
                if(action.equals("live-output-release")){try(var second=original(cache(),source(mp4(),c->{}),new RunControl())){for(var entry:bytes.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()));}}
                else{validationUntilCopied();for(var entry:bytes.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()));}
                resume.countDown();first.get(10,TimeUnit.SECONDS);assertEquals(0,files(cache(),folder).length);
            }finally{resume.countDown();releasing=null;release=null;pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }
    @Implements(MediaExtractor.class)
    public static final class CodecBoundary {
        private final boolean firstRelease=CodecWorker.releasing!=null&&CodecWorker.releaseClaimed.compareAndSet(false,true);
        @Implementation protected static void __staticInitializer__(){}
        @Implementation protected void __constructor__(){if(CodecWorker.action().equals("constructor-failure"))throw new CopyReached();}
        @Implementation protected void setDataSource(String path)throws Exception {
            assertArrayEquals(mp4(),Files.readAllBytes(Path.of(path)));
            if(CodecWorker.action().equals("crash-output"))CodecWorker.park(only(CodecWorker.cache(),"original-media-processing","trim-output-"));if(!CodecWorker.action().equals("release-failure")||path.contains("provider-media-validation"))throw new CopyReached();
        }
        @Implementation protected android.media.DrmInitData getDrmInitData(){return null;}
        @Implementation protected Map<UUID,byte[]> getPsshInfo(){return null;}
        @Implementation protected int getTrackCount(){return 1;}
        @Implementation protected MediaFormat getTrackFormat(int index){MediaFormat format=MediaFormat.createVideoFormat("video/avc",16,16);format.setLong(MediaFormat.KEY_DURATION,1000000L);return format;}
        @Implementation protected void selectTrack(int index){}
        @Implementation protected void release()throws Exception {CodecWorker.releaseCalls.incrementAndGet();if(CodecWorker.action().equals("release-failure")){CodecWorker.extractorReleased=true;throw new ExtractorReleaseFailure();}if(CodecWorker.action().equals("live-validation-cancel-release")){if(CodecWorker.cancelReleaseClaimed.compareAndSet(false,true)){CodecWorker.releasing.countDown();await(CodecWorker.release);}return;}if(firstRelease){CodecWorker.releasing.countDown();await(CodecWorker.release);}}
    }
    @Implements(MediaMuxer.class)
    public static final class MuxerBoundary {
        @Implementation protected static void __staticInitializer__(){}
        @Implementation protected void __constructor__(String path,int format){}
        @Implementation protected int addTrack(MediaFormat format){return 0;}
        @Implementation protected void start(){throw new CopyReached();}
        @Implementation protected void release(){CodecWorker.muxerReleased=true;throw new MuxerReleaseFailure();}
    }
}
