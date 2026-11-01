package dev.pocketengine.vision.ocr;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** JNI-independent lifecycle tests; these deliberately do not assert OCR recognition quality. */
public final class OcrJobRunnerTest {
    private static OcrPage page() { return new OcrPage(1,1,1,1,Collections.singletonList(new OcrPage.Line(1,"fixture",0,0,1,1))); }
    @Test public void realRunCancellationReturnsBeforeNativeExitAndKeepsAdmissionBusy() throws Exception {
        OcrJobRunner jobs = new OcrJobRunner(); ExecutorService caller = Executors.newSingleThreadExecutor();
        RunControl control = new RunControl(); CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1), recycled = new CountDownLatch(1);
        AtomicInteger stops = new AtomicInteger(); AtomicBoolean useAfterRecycle = new AtomicBoolean();
        try {
            Future<Object> result = caller.submit(()->{
                try { return jobs.run(signal->{
                    signal.installStop(()->{if(recycled.getCount()==0)useAfterRecycle.set(true);stops.incrementAndGet();}); entered.countDown();
                    try { finish.await(); return page(); } finally { signal.release(recycled::countDown); }
                },control,5000); } catch(Exception stopped) { return stopped; }
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));control.cancel();
            assertTrue(result.get(1,TimeUnit.SECONDS) instanceof InterruptedException);
            assertTrue(stops.get()>0);assertEquals(1,recycled.getCount());
            assertThrows(DocumentReader.EngineUnavailableException.class,()->jobs.run(signal->page(),new RunControl(),1000));
            finish.countDown(); assertTrue(recycled.await(2,TimeUnit.SECONDS));
            jobs.close(); assertFalse(useAfterRecycle.get());
        } finally { finish.countDown(); jobs.close(); caller.shutdownNow(); }
    }
    @Test public void timeoutDoesNotPublishLateResultOrRecycleLiveNativeHandle() throws Exception {
        OcrJobRunner jobs = new OcrJobRunner(); CountDownLatch finish = new CountDownLatch(1), recycled = new CountDownLatch(1);
        try {
            assertThrows(DocumentReader.EngineUnavailableException.class,()->jobs.run(signal->{
                try { finish.await();return page(); } finally { signal.release(recycled::countDown); }
            },new RunControl(),40));
            assertEquals(1,recycled.getCount());
            assertThrows(DocumentReader.EngineUnavailableException.class,()->jobs.run(signal->page(),new RunControl(),1000));
            finish.countDown(); assertTrue(recycled.await(2,TimeUnit.SECONDS));
        } finally { finish.countDown(); jobs.close(); }
    }
    @Test public void closeRevokesDeliveryAndDoesNotReuseEngine() throws Exception {
        OcrJobRunner jobs = new OcrJobRunner(); ExecutorService caller=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        try {
            Future<Object> result=caller.submit(()->{try{return jobs.run(signal->{entered.countDown();finish.await();return page();},new RunControl(),5000);}catch(Exception e){return e;}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));jobs.close();
            assertTrue(result.get(1,TimeUnit.SECONDS) instanceof InterruptedException);
            assertThrows(DocumentReader.EngineUnavailableException.class,()->jobs.run(signal->page(),new RunControl(),1000));
        } finally {finish.countDown();jobs.close();caller.shutdownNow();}
    }
    @Test public void authorityRevocationAndAlreadyCancelledControlNeverBecomeResults() throws Exception {
        OcrJobRunner jobs=new OcrJobRunner();RunControl cancelled=new RunControl();cancelled.cancel();AtomicInteger calls=new AtomicInteger();
        try {
            assertThrows(InterruptedException.class,()->jobs.run(signal->{calls.incrementAndGet();return page();},cancelled,1000));assertEquals(0,calls.get());
            RunControl revoked=new RunControl();revoked.setAuthorityCheck(()->{throw new SecurityException("revoked");});
            assertThrows(InterruptedException.class,()->jobs.run(signal->page(),revoked,1000));assertTrue(revoked.isCancelled());
        } finally {jobs.close();}
    }
    @Test public void nativeFailureIsNotReplacedByEmptyOrInventedText() throws Exception {
        OcrJobRunner jobs=new OcrJobRunner();
        try {
            assertThrows(DocumentReader.EngineUnavailableException.class,()->jobs.run(signal->{throw new DocumentReader.EngineUnavailableException("missing");},new RunControl(),1000));
            assertThrows(DocumentReader.EngineUnavailableException.class,()->jobs.run(signal->null,new RunControl(),1000));
            assertEquals("fixture",jobs.run(signal->page(),new RunControl(),1000).lines.get(0).text);
        } finally {jobs.close();}
    }
    @Test public void engineCloseAlsoStopsPausedWaitersWithoutCancellingTheHostRun() throws Exception {
        OcrJobRunner jobs=new OcrJobRunner();ExecutorService caller=Executors.newSingleThreadExecutor();RunControl control=new RunControl();
        CountDownLatch started=new CountDownLatch(1),checkAfterPause=new CountDownLatch(1);
        try {
            Future<Object> result=caller.submit(()->{try{return jobs.run(signal->{started.countDown();checkAfterPause.await();signal.check();return page();},control,5000);}catch(Exception failure){return failure;}});
            assertTrue(started.await(2,TimeUnit.SECONDS));control.pause();checkAfterPause.countDown();
            jobs.close();assertTrue(result.get(1,TimeUnit.SECONDS) instanceof InterruptedException);assertFalse(control.isCancelled());
        } finally {checkAfterPause.countDown();control.resume();jobs.close();caller.shutdownNow();}
    }
}
