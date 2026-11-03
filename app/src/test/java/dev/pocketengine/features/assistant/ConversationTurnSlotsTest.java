package dev.pocketengine.features.assistant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConversationTurnSlotsTest {
    private static final class Manual implements Executor {
        final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        boolean reject;
        public void execute(Runnable action) { if (reject) throw new RejectedExecutionException(); scheduled.add(action); }
        void next() { scheduled.remove().run(); }
    }

    @Test public void differentConversationsActuallyOverlapAndThirdWaitsForAReleasedSlot() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var slots = new ConversationTurnSlots<String>(2, 8, workers);
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1), third = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            var a = slots.reserve("a", "original-a"); var b = slots.reserve("b", "original-b"); var c = slots.reserve("c", "original-c");
            Runnable work = () -> { entered.countDown(); try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Workers did not overlap"); } catch (Throwable value) { failure.set(value); } };
            slots.submit(a, work, () -> fail("a rejected")); slots.submit(b, work, () -> fail("b rejected")); slots.submit(c, third::countDown, () -> fail("c rejected"));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(1, third.getCount());
            assertEquals(2, slots.activeCount()); assertEquals(1, slots.queuedCount());
            assertEquals("running", a.resourceState()); assertEquals("running", b.resourceState()); assertEquals("queued", c.resourceState());
            release.countDown(); assertTrue(third.await(2, TimeUnit.SECONDS)); assertNull(failure.get());
        } finally { release.countDown(); slots.close(); workers.shutdownNow(); assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test public void sameConversationCannotReserveASecondRunner() {
        Manual executor = new Manual(); var slots = new ConversationTurnSlots<Object>(2, 8, executor);
        Object original = new Object(); var first = slots.reserve("same", original);
        assertThrows(IllegalStateException.class, () -> slots.reserve("same", new Object()));
        assertSame(original, slots.get("same").original()); assertEquals(1, slots.snapshot().size());
        assertTrue(slots.release(first)); assertNotSame(first, slots.reserve("same", new Object()));
    }

    @Test public void capacityIsBoundedBeforeAnyExtraOriginalWorkIsAdmitted() {
        Manual executor = new Manual(); var slots = new ConversationTurnSlots<Integer>(2, 8, executor); AtomicInteger calls = new AtomicInteger();
        for (int index = 0; index < 10; index++) { var original = slots.reserve("conversation-" + index, index); slots.submit(original, calls::incrementAndGet, () -> fail("not rejected")); }
        assertThrows(IllegalStateException.class, () -> slots.reserve("eleventh", 11));
        assertEquals(2, slots.activeCount()); assertEquals(8, slots.queuedCount()); assertEquals(0, slots.availableCount());
        assertEquals(0, calls.get()); assertEquals(2, executor.scheduled.size());
        while (!executor.scheduled.isEmpty()) executor.next();
        assertEquals(10, calls.get()); assertEquals(10, slots.availableCount());
    }

    @Test public void queuedCancellationNeverInvokesItsWorkAndDoesNotTouchTheOtherOriginal() {
        Manual executor = new Manual(); var slots = new ConversationTurnSlots<String>(1, 2, executor); List<String> calls = new ArrayList<>();
        var a = slots.reserve("a", "run-a"); var b = slots.reserve("b", "run-b"); var c = slots.reserve("c", "run-c");
        slots.submit(a, () -> calls.add(a.original()), () -> fail("a rejected"));
        slots.submit(b, () -> calls.add(b.original()), () -> fail("b rejected"));
        slots.submit(c, () -> calls.add(c.original()), () -> fail("c rejected"));
        assertTrue(slots.discardQueued(b)); assertSame(a, slots.get("a")); assertSame(c, slots.get("c"));
        executor.next(); executor.next(); assertEquals(List.of("run-a", "run-c"), calls); assertTrue(slots.snapshot().isEmpty());
    }

    @Test public void runningCancellationKeepsTheSlotUntilThatWorkersFinally() {
        Manual executor = new Manual(); var slots = new ConversationTurnSlots<String>(1, 1, executor); AtomicInteger second = new AtomicInteger();
        var a = slots.reserve("a", "run-a"); var b = slots.reserve("b", "run-b");
        slots.submit(a, () -> { assertFalse(slots.discardQueued(a)); assertEquals(1, slots.activeCount()); assertEquals(1, slots.queuedCount()); assertEquals(0, second.get()); }, () -> fail("a rejected"));
        slots.submit(b, second::incrementAndGet, () -> fail("b rejected"));
        executor.next(); assertEquals(0, second.get()); assertEquals(1, executor.scheduled.size()); executor.next(); assertEquals(1, second.get());
    }

    @Test public void oldFinallyAndOldCancellationCannotReleaseReplacementOrAnotherConversation() {
        Manual executor = new Manual(); var slots = new ConversationTurnSlots<String>(2, 2, executor);
        var old = slots.reserve("a", "old-a"); var b = slots.reserve("b", "run-b");
        AtomicReference<ConversationTurnSlots.Lease<String>> replacement = new AtomicReference<>();
        slots.submit(old, () -> {
            assertTrue(slots.release(old));
            var next = slots.reserve("a", "new-a"); replacement.set(next); slots.submit(next, () -> {}, () -> fail("new a rejected"));
        }, () -> fail("old a rejected"));
        slots.submit(b, () -> {}, () -> fail("b rejected"));
        executor.next(); // The wrapper now runs the old worker's late finally.
        assertSame(replacement.get(), slots.get("a")); assertSame(b, slots.get("b"));
        assertFalse(slots.discardQueued(old)); assertFalse(slots.release(old)); assertEquals(2, slots.activeCount());
        executor.next(); assertSame(replacement.get(), slots.get("a")); executor.next(); assertTrue(slots.snapshot().isEmpty());
    }

    @Test public void closePreventsBothScheduledAndWaitingCallbacksFromStarting() {
        Manual executor = new Manual(); var slots = new ConversationTurnSlots<String>(1, 2, executor); AtomicInteger calls = new AtomicInteger();
        var a = slots.reserve("a", "run-a"); var b = slots.reserve("b", "run-b");
        slots.submit(a, calls::incrementAndGet, () -> fail("a rejected")); slots.submit(b, calls::incrementAndGet, () -> fail("b rejected"));
        assertEquals(List.of("run-a", "run-b"), slots.close()); assertEquals(0, slots.availableCount());
        executor.next(); assertEquals(0, calls.get()); assertTrue(executor.scheduled.isEmpty());
        assertThrows(IllegalStateException.class, () -> slots.reserve("later", "new-run")); assertTrue(slots.close().isEmpty());
    }

    @Test public void executorRejectionReportsEachOriginalOnceEvenIfOneReporterFails() {
        Manual executor = new Manual(); var slots = new ConversationTurnSlots<String>(1, 2, executor); List<String> rejected = new ArrayList<>();
        var a = slots.reserve("a", "run-a"); var b = slots.reserve("b", "run-b"); var c = slots.reserve("c", "run-c");
        slots.submit(a, () -> executor.reject = true, () -> fail("already started"));
        slots.submit(b, () -> fail("must not run"), () -> { rejected.add("run-b"); throw new IllegalStateException("receipt reporting unavailable"); });
        slots.submit(c, () -> fail("must not run"), () -> rejected.add("run-c"));
        executor.next(); assertEquals(List.of("run-b", "run-c"), rejected); assertTrue(slots.snapshot().isEmpty()); assertEquals(0, slots.activeCount());
    }

    @Test public void inlineLegacyHostCannotCreateAnUnjournaledWaitQueue() {
        Manual executor = new Manual(); var slots = new ConversationTurnSlots<String>(1, 1, executor);
        var first = slots.reserve("a", "run-a"); var second = slots.reserve("b", "run-b"); slots.beginInline(first);
        assertThrows(IllegalStateException.class, () -> slots.beginInline(second)); assertTrue(slots.release(second));
        assertSame(first, slots.get("a")); assertEquals(1, slots.activeCount()); assertTrue(executor.scheduled.isEmpty());
    }
}
