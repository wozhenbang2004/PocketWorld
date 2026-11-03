package dev.pocketengine.runtime.models;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ModelSelectionCoordinatorTest {
    @Test public void queueReservationBlocksOtherSurfacesBeforeTheWorkerStarts()throws Exception {
        var selection=new ModelSelectionCoordinator();var mutation=selection.beginMutation();
        assertTrue(selection.status().pending());
        assertThrows(IllegalStateException.class,selection::beginMutation);
        assertThrows(IllegalStateException.class,selection::requireAvailable);
        assertEquals("saved",mutation.run(()->{selection.requireAvailable();selection.changed();return "saved";}));
        assertFalse(selection.status().pending());selection.requireAvailable();
    }
    @Test public void rejectedQueueCannotLaterWriteOrReleaseANewerMutation()throws Exception {
        var selection=new ModelSelectionCoordinator();var rejected=selection.beginMutation();rejected.close();
        var next=selection.beginMutation();AtomicInteger writes=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->rejected.run(writes::incrementAndGet));
        rejected.close();assertTrue(selection.status().pending());
        next.run(writes::incrementAndGet);assertEquals(1,writes.get());assertFalse(selection.status().pending());
    }
    @Test public void aDuplicateWorkerCannotReplayACompletedWrite()throws Exception {
        var selection=new ModelSelectionCoordinator();var mutation=selection.beginMutation();AtomicInteger writes=new AtomicInteger();
        mutation.run(writes::incrementAndGet);
        assertThrows(IllegalStateException.class,()->mutation.run(writes::incrementAndGet));
        assertEquals(1,writes.get());
    }
    @Test public void disposalCannotPretendAnExecutingWriteWasCancelled()throws Exception {
        var selection=new ModelSelectionCoordinator();var mutation=selection.beginMutation();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
        try {
            var result=worker.submit(()->mutation.run(()->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "committed";}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));mutation.close();
            assertTrue(selection.status().pending());assertThrows(IllegalStateException.class,selection::requireAvailable);
            release.countDown();assertEquals("committed",result.get(5,TimeUnit.SECONDS));assertFalse(selection.status().pending());
        } finally { release.countDown();worker.shutdownNow(); }
    }
    @Test public void failureReleasesAdmissionAndDoesNotClaimACommit()throws Exception {
        var selection=new ModelSelectionCoordinator();var mutation=selection.beginMutation();
        assertThrows(java.io.IOException.class,()->mutation.run(()->{throw new java.io.IOException("fixture failure");}));
        assertFalse(selection.status().pending());selection.requireAvailable();
        try(var next=selection.beginMutation()){assertEquals(2,(int)next.run(()->2));}
    }
    @Test public void destroyedSurfaceCannotSuppressAnotherSurfacesInvalidation()throws Exception {
        var selection=new ModelSelectionCoordinator();AtomicInteger closed=new AtomicInteger(),live=new AtomicInteger();
        var old=selection.observe(closed::incrementAndGet);var current=selection.observe(live::incrementAndGet);
        selection.observe(()->{throw new IllegalStateException("closed observer");});old.close();
        long before=selection.status().revision();selection.changed();
        assertEquals(0,closed.get());assertEquals(1,live.get());assertTrue(selection.status().revision()>before);
        current.close();selection.changed();assertEquals(1,live.get());
    }
    @Test public void repeatedSelectionsHaveNoPersistentOperationCountCeiling()throws Exception {
        var selection=new ModelSelectionCoordinator();
        for(int i=0;i<300;i++){var mutation=selection.beginMutation();mutation.run(()->{selection.changed();return null;});}
        assertFalse(selection.status().pending());assertEquals(900,selection.status().revision());
    }
    @Test public void staleVisibleRowsCannotOverwriteAChangedSelectionEvenAfterPendingClears()throws Exception {
        var selection=new ModelSelectionCoordinator();long old=selection.status().revision();selection.changed();
        assertThrows(IllegalStateException.class,()->selection.beginMutation(old));assertFalse(selection.status().pending());
        var current=selection.beginMutation(selection.status().revision());assertEquals("accepted",current.run(()->"accepted"));
    }
    @Test public void aReadCannotStampOldValuesWithANewerRevision()throws Exception {
        var selection=new ModelSelectionCoordinator();AtomicInteger reads=new AtomicInteger();
        var projection=selection.readStable(()->{if(reads.incrementAndGet()==1){selection.changed();return "old";}return "new";});
        assertEquals("new",projection.value());assertEquals(selection.status().revision(),projection.revision());assertEquals(2,reads.get());
    }
    @Test public void unstableReadsAreBoundedAndNeverBorrowAnInProgressMutation()throws Exception {
        var selection=new ModelSelectionCoordinator();AtomicInteger reads=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->selection.readStable(()->{reads.incrementAndGet();selection.changed();return "moving";}));assertEquals(3,reads.get());
        try(var mutation=selection.beginMutation()){
            assertThrows(IllegalStateException.class,()->selection.readStable(()->{reads.incrementAndGet();return "old";}));assertEquals(3,reads.get());
        }
    }
}
