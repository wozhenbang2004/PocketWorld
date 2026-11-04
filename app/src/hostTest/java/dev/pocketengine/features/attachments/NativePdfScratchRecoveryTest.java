package dev.pocketengine.features.attachments;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.AndroidPdfDocumentPort;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real production PDF copy and cleanup, hard-killed host JVMs, and fresh-JVM recovery.
 * The renderer boundary is shadowed with zero pages to exercise construction/release
 * without parsing or rasterization. This is not Android device/process-death evidence,
 * a PDF correctness test, or evidence of cross-process live-reader exclusion. */
public final class NativePdfScratchRecoveryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String FACTORY="attachment-document-[0-9]{1,20}\\.pdf";
    private static byte[] pdf(){byte[] bytes=new byte[65536];System.arraycopy(new byte[]{'%','P','D','F','-'},0,bytes,0,5);return bytes;}
    private static Path[] scratch(File cache)throws Exception {
        if(!cache.isDirectory())return new Path[0];
        try(var files=Files.list(cache.toPath())){return files.filter(p->p.getFileName().toString().matches(FACTORY)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toArray(Path[]::new);}
    }
    private static void await(CountDownLatch latch)throws Exception {assertTrue("PDF worker gate not reached",latch.await(20,TimeUnit.SECONDS));}
    private Process worker(File cache,String action,Path marker,Path log,Path nativeTmp)throws Exception {
        long minimum=Long.getLong("pocketworld.pdf.minimumFreeBytes",0L),free=Files.getFileStore(temporary.getRoot().toPath()).getUsableSpace();
        assertTrue("PDF child requires "+minimum+" free bytes before native extraction; available="+free,free>=minimum);
        Files.createDirectories(nativeTmp);
        var command=new java.util.ArrayList<String>(Arrays.asList(new File(System.getProperty("java.home"),"bin/java").getPath(),"-Xmx192m","-XX:ActiveProcessorCount=2","-XX:MaxMetaspaceSize=160m","-XX:ReservedCodeCacheSize=32m","--add-opens=java.base/java.io=ALL-UNNAMED","-Djava.io.tmpdir="+nativeTmp,"-Dpocketworld.pdf.cache="+cache.getAbsolutePath(),"-Dpocketworld.pdf.action="+action,"-Dpocketworld.pdf.marker="+marker));
        for(String key:new String[]{"robolectric.offline","robolectric.dependency.dir"})if(System.getProperty(key)!=null)command.add("-D"+key+"="+System.getProperty(key));
        command.addAll(Arrays.asList("-cp",System.getProperty("pocketworldHostClasspath",System.getProperty("java.class.path")),"org.junit.runner.JUnitCore",PdfWorker.class.getName()));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void removeOwnWorkerTmp(Path root)throws Exception {try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private Path crash(File cache,String action)throws Exception {
        Path marker=temporary.getRoot().toPath().resolve(action+".ready"),log=Path.of(marker+".log"),nativeTmp=temporary.getRoot().toPath().resolve("worker-"+UUID.randomUUID());
        Process child=worker(cache,action,marker,log,nativeTmp);
        try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!Files.isRegularFile(marker)&&child.isAlive()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertTrue("PDF crash gate not reached: "+Files.readString(log),Files.isRegularFile(marker));Path retained=Path.of(Files.readString(marker));assertTrue(Files.isRegularFile(retained));
            child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertNotEquals(0,child.exitValue());System.out.println("Hard-killed production withPdf host pid="+child.pid()+" boundary="+action+" retainedBytes="+Files.size(retained));return retained;
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}assertFalse(child.isAlive());removeOwnWorkerTmp(nativeTmp);}
    }
    private void runWorker(File cache,String action)throws Exception {
        Path marker=temporary.getRoot().toPath().resolve(action+"-"+UUID.randomUUID()+".ready"),log=Path.of(marker+".log"),nativeTmp=temporary.getRoot().toPath().resolve("worker-"+UUID.randomUUID());
        Process child=worker(cache,action,marker,log,nativeTmp);
        try{assertTrue("PDF worker timed out",child.waitFor(60,TimeUnit.SECONDS));assertEquals(Files.readString(log),0,child.exitValue());System.out.println("Fresh PDF host pid="+child.pid()+" action="+action+" exit="+child.exitValue());}
        finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}assertFalse(child.isAlive());removeOwnWorkerTmp(nativeTmp);}
    }
    @Test public void aHardKilledTextPartialCopyRecoversOnTheNextColdOcr()throws Exception {
        File cache=temporary.newFolder();Path retained=crash(cache,"crash-text-partial");assertEquals(32768,Files.size(retained));runWorker(cache,"recover-ocr");assertFalse(Files.exists(retained));assertEquals(0,scratch(cache).length);
    }
    @Test public void aHardKilledOcrCompleteCopyRecoversOnTheNextColdTextRead()throws Exception {
        File cache=temporary.newFolder();Path retained=crash(cache,"crash-ocr-complete");assertArrayEquals(pdf(),Files.readAllBytes(retained));runWorker(cache,"recover-text");assertFalse(Files.exists(retained));assertEquals(0,scratch(cache).length);
    }
    @Test public void originalEntryPointsPreserveOnlyTheirOwnLiveFilesAndCloseEveryUnwind()throws Exception {runWorker(temporary.newFolder(),"contract");}

    private static final class PdfConstructionFailure extends RuntimeException {}
    private static final class PdfReleaseFailure extends RuntimeException {}
    private static final class PdfActionFailure extends Error {}

    @RunWith(RobolectricTestRunner.class)
    @Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows=RendererBoundary.class)
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @SQLiteMode(SQLiteMode.Mode.LEGACY)
    public static final class PdfWorker {
        static volatile CountDownLatch releasing,release;
        static final AtomicBoolean releaseClaimed=new AtomicBoolean();
        static volatile boolean constructionFailure,encrypted,releaseFailure;
        static File cache(){return new File(System.getProperty("pocketworld.pdf.cache"));}
        static String action(){return System.getProperty("pocketworld.pdf.action");}
        static Context context(File cache){return new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public File getCacheDir(){return cache;}
            @Override public Context getApplicationContext(){return this;}
        };}
        static <T>T onWorker(Callable<T> action)throws Exception {
            var pool=Executors.newSingleThreadExecutor();
            try{return pool.submit(action).get(30,TimeUnit.SECONDS);}catch(ExecutionException failure){if(failure.getCause() instanceof Error error)throw error;throw (Exception)failure.getCause();}
            finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        }
        static void read(File cache,boolean ocr,RunControl control,boolean actionFailure)throws Exception {
            var port=new AndroidPdfDocumentPort(context(cache));
            DocumentReader.RequestSink sink=new DocumentReader.RequestSink(){
                public DocumentContent.Request request(){return new DocumentContent.Request(1,0,1,2048,1,ocr?"ocr":"text");}
                public void totalUnits(int count){if(actionFailure)throw new PdfActionFailure();}
                public void text(int unit,String kind,Map<String,String> locator,String text){}
                public void warning(String warning){}
            };
            if(ocr)port.ocrPages().recognizePages(pdf(),null,sink,control);else port.read(pdf(),sink,control);
        }
        static void normal(File cache,boolean ocr)throws Exception {onWorker(()->{read(cache,ocr,new RunControl(),false);return null;});}
        static void park(Path path)throws Exception {Path marker=Path.of(System.getProperty("pocketworld.pdf.marker")),pending=Path.of(marker+".writing");Files.writeString(pending,path.toString());Files.move(pending,marker,java.nio.file.StandardCopyOption.ATOMIC_MOVE);Thread.sleep(Long.MAX_VALUE);throw new AssertionError("PDF crash gate returned");}
        static void crashBoundary(RunControl control,boolean partial){control.setAuthorityCheck(()->{Path[] files=scratch(cache());if(files.length==1&&Files.size(files[0])==(partial?32768:pdf().length))park(files[0]);});}
        static void live(String boundary)throws Exception {
            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();RunControl control=new RunControl();AtomicBoolean parked=new AtomicBoolean();
            if(boundary.equals("release")){releasing=entered;release=resume;releaseClaimed.set(false);}
            else control.setAuthorityCheck(()->{Path[] files=scratch(cache());if(files.length==1&&Files.size(files[0])==(boundary.equals("partial")?32768:pdf().length)&&parked.compareAndSet(false,true)){entered.countDown();await(resume);}});
            var first=pool.submit(()->{read(cache(),false,control,false);return null;});
            try{await(entered);Path[] files=scratch(cache());assertEquals(1,files.length);Path active=files[0];byte[] before=Files.readAllBytes(active);Files.setLastModifiedTime(active,FileTime.fromMillis(1));normal(cache(),true);assertArrayEquals("A live text reader must survive another port's OCR sweep: "+boundary,before,Files.readAllBytes(active));resume.countDown();first.get(10,TimeUnit.SECONDS);assertEquals(0,scratch(cache()).length);System.out.println("Live production PDF retained through "+boundary);}
            finally{resume.countDown();releasing=null;release=null;pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        }
        static void namespaceAndUnwind()throws Exception {
            Path root=cache().toPath();Path stale=root.resolve("attachment-document-123.pdf");Files.write(stale,pdf());
            Path unknown=root.resolve("attachment-document-manual.pdf"),longName=root.resolve("attachment-document-123456789012345678901.pdf"),book=root.resolve("book"),directory=root.resolve("attachment-document-456.pdf"),target=root.resolve("private-original.bin"),link=root.resolve("attachment-document-789.pdf");
            Files.write(unknown,new byte[]{1});Files.write(longName,new byte[]{2});Files.createDirectories(book);Files.createDirectories(directory);Path nested=book.resolve("attachment-document-100.pdf");Files.write(nested,new byte[]{3});Files.write(target,new byte[]{4});Files.createSymbolicLink(link,target);
            normal(cache(),false);assertFalse("Factory-named orphan must be reclaimed on first use",Files.exists(stale));assertArrayEquals(new byte[]{1},Files.readAllBytes(unknown));assertArrayEquals(new byte[]{2},Files.readAllBytes(longName));assertArrayEquals(new byte[]{3},Files.readAllBytes(nested));assertArrayEquals(new byte[]{4},Files.readAllBytes(target));assertTrue(Files.isSymbolicLink(link));assertTrue(Files.isDirectory(directory));assertEquals(0,scratch(cache()).length);
            normal(cache(),true);assertEquals(0,scratch(cache()).length);
            constructionFailure=true;try{assertThrows(PdfConstructionFailure.class,()->normal(cache(),false));}finally{constructionFailure=false;}assertEquals(0,scratch(cache()).length);
            encrypted=true;try{assertThrows(DocumentReader.EncryptedDocumentException.class,()->normal(cache(),true));}finally{encrypted=false;}assertEquals(0,scratch(cache()).length);
            assertThrows(PdfActionFailure.class,()->onWorker(()->{read(cache(),true,new RunControl(),true);return null;}));assertEquals(0,scratch(cache()).length);
            releaseFailure=true;try{assertThrows(PdfReleaseFailure.class,()->normal(cache(),false));}finally{releaseFailure=false;}assertEquals(0,scratch(cache()).length);
            RunControl cancelled=new RunControl();cancelled.setAuthorityCheck(()->{if(scratch(cache()).length>0)cancelled.cancel();});assertThrows(InterruptedException.class,()->onWorker(()->{read(cache(),false,cancelled,false);return null;}));assertEquals(0,scratch(cache()).length);
            Path alias=root.resolve("linked-cache");Files.createSymbolicLink(alias,book);assertThrows(java.io.IOException.class,()->normal(alias.toFile(),false));assertArrayEquals(new byte[]{3},Files.readAllBytes(nested));
        }
        @Test public void scenario()throws Exception {
            if(action().startsWith("crash-")){RunControl control=new RunControl();crashBoundary(control,action().endsWith("partial"));onWorker(()->{read(cache(),action().contains("ocr"),control,false);return null;});throw new AssertionError("PDF crash returned");}
            if(action().startsWith("recover-")){normal(cache(),action().endsWith("ocr"));assertEquals("Cold invocation must remove the prior process's unpublished factory copy",0,scratch(cache()).length);return;}
            live("partial");live("complete");live("release");namespaceAndUnwind();
        }
    }
    @Implements(PdfRenderer.class)
    public static final class RendererBoundary {
        private final boolean firstRelease=PdfWorker.releasing!=null&&PdfWorker.releaseClaimed.compareAndSet(false,true);
        @Implementation protected static void __staticInitializer__(){}
        @Implementation protected void __constructor__(ParcelFileDescriptor descriptor){if(PdfWorker.constructionFailure)throw new PdfConstructionFailure();if(PdfWorker.encrypted)throw new SecurityException("Controlled encrypted-PDF boundary");}
        @Implementation protected int getPageCount(){return 0;}
        @Implementation protected void close()throws Exception {if(firstRelease){PdfWorker.releasing.countDown();await(PdfWorker.release);}if(PdfWorker.releaseFailure)throw new PdfReleaseFailure();}
    }
}
