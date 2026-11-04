package dev.pocketengine.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ExecutionResourcesTest {
    @Test public void phoneAndBrowserExcludeEachOtherEvenForTheSameToken() {
        AtomicLong now = new AtomicLong(100);
        ExecutionResources fence = new ExecutionResources(now::get);
        assertTrue(fence.acquirePhone("phone"));
        assertTrue(fence.acquirePhone("phone"));
        assertFalse(fence.acquirePhone("other-phone"));
        assertFalse(fence.acquireBrowser("phone", 200));
        fence.releasePhone("phone");
        assertTrue(fence.acquireBrowser("browser", 200));
        assertTrue(fence.acquireBrowser("browser", 200));
        assertFalse(fence.acquirePhone("browser"));
        assertFalse(fence.acquireBrowser("other-browser", 200));
    }

    @Test public void oldTokensCannotReleaseNewOwners() {
        ExecutionResources fence = new ExecutionResources(() -> 100);
        assertTrue(fence.acquirePhone("old-phone"));
        fence.releasePhone("old-phone");
        assertTrue(fence.acquirePhone("new-phone"));
        fence.releasePhone("old-phone");
        assertFalse(fence.acquireBrowser("browser", 200));
        fence.releasePhone("new-phone");
        assertTrue(fence.acquireBrowser("old-browser", 200));
        fence.releaseBrowser("old-browser");
        assertTrue(fence.acquireBrowser("new-browser", 200));
        fence.releaseBrowser("old-browser");
        assertFalse(fence.acquirePhone("phone"));
        fence.releaseBrowser("new-browser");
        assertTrue(fence.acquirePhone("phone"));
    }

    @Test public void expiredBrowserCannotBlockPhoneAndStaleReleaseCannotFreeItsReplacement() {
        AtomicLong now = new AtomicLong(100);
        ExecutionResources fence = new ExecutionResources(now::get);
        assertFalse(fence.acquireBrowser("expired", 100));
        assertTrue(fence.acquireBrowser("old", 200));
        now.set(199);
        assertFalse(fence.acquirePhone("phone"));
        now.set(200);
        assertTrue(fence.acquireBrowser("new", 300));
        fence.releaseBrowser("old");
        assertFalse(fence.acquirePhone("phone"));
        now.set(300);
        assertTrue(fence.acquirePhone("phone"));
    }

    @Test public void repeatedAcquisitionDoesNotExtendBrowserDeadline() {
        AtomicLong now = new AtomicLong(100);
        ExecutionResources fence = new ExecutionResources(now::get);
        assertTrue(fence.acquireBrowser("browser", 200));
        assertTrue(fence.acquireBrowser("browser", 500));
        now.set(200);
        assertTrue(fence.acquirePhone("phone"));
    }

    @Test public void simultaneousPhoneAndBrowserAcquisitionHasOnlyOneWinner() throws Exception {
        ExecutionResources fence = new ExecutionResources(() -> 100);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Thread phone = new Thread(() -> attempt(ready, start, winners, () -> fence.acquirePhone("phone")));
        Thread browser = new Thread(() -> attempt(ready, start, winners, () -> fence.acquireBrowser("browser", 200)));
        phone.start();browser.start();ready.await();start.countDown();phone.join(2000);browser.join(2000);
        assertFalse(phone.isAlive());assertFalse(browser.isAlive());assertEquals(1, winners.get());
    }

    @Test public void fourBackgroundOwnersRemainIndependentAcrossReleaseAndReadmission() {
        ExecutionResources fence = new ExecutionResources(() -> 100);
        for (int i = 0; i < 4; i++) assertTrue(fence.acquireBackgroundPhone("run-" + i));
        assertEquals(4, fence.backgroundPhoneCount());
        assertTrue(fence.acquireBackgroundPhone("run-0"));
        assertFalse(fence.acquireBackgroundPhone("run-4"));
        assertFalse(fence.acquirePhone("main"));
        assertFalse(fence.acquireBrowser("browser", 200));
        fence.releasePhone("run-0");
        assertTrue(fence.acquireBackgroundPhone("run-4"));
        fence.releasePhone("run-0"); // A late old cleanup cannot free the replacement.
        assertEquals(4, fence.backgroundPhoneCount());
        for (int i = 1; i <= 4; i++) assertTrue(fence.ownsBackgroundPhone("run-" + i));
        for (int i = 1; i <= 4; i++) fence.releasePhone("run-" + i);
        assertTrue(fence.acquirePhone("main"));
        assertFalse(fence.acquireBackgroundPhone("main"));
        assertFalse(fence.acquireBackgroundPhone("next-background"));
    }

    @Test public void backgroundAndBrowserKeepTheirOriginalExclusiveBoundary() {
        AtomicLong now = new AtomicLong(100);
        ExecutionResources fence = new ExecutionResources(now::get);
        assertTrue(fence.acquireBrowser("browser", 200));
        assertFalse(fence.acquireBackgroundPhone("browser"));
        assertFalse(fence.acquireBackgroundPhone("phone"));
        now.set(200);
        assertTrue(fence.acquireBackgroundPhone("phone"));
        assertFalse(fence.renewBrowser("browser", 300));
        fence.releaseBrowser("browser");
        assertTrue(fence.ownsPhone("phone"));
        assertFalse(fence.acquireBrowser("next-browser", 300));
        fence.releasePhone("phone");
        assertTrue(fence.acquireBrowser("next-browser", 300));
    }

    @Test public void simultaneousBackgroundAdmissionsCannotOverbookFourSlots() throws Exception {
        ExecutionResources fence = new ExecutionResources(() -> 100);
        CountDownLatch ready = new CountDownLatch(12), start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        java.util.List<Thread> attempts = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String token = "run-" + i;
            Thread contender = new Thread(() -> attempt(ready, start, winners, () -> fence.acquireBackgroundPhone(token)));
            attempts.add(contender);contender.start();
        }
        ready.await();start.countDown();
        for (Thread contender : attempts) { contender.join(2000);assertFalse(contender.isAlive()); }
        assertEquals(4, winners.get());assertEquals(4, fence.backgroundPhoneCount());
        assertFalse(fence.acquirePhone("main"));
    }

    @Test public void concurrentScreensStillShareOneAppAndAndroidUserConflictRegistry() throws Exception {
        ExecutionResources fence = new ExecutionResources(() -> 100);
        assertTrue(fence.acquireBackgroundPhone("run-a"));assertTrue(fence.acquireBackgroundPhone("run-b"));
        var sameCopy = new dev.pocketengine.phone.access.AppCopyKey("dev.example.chat", 0);
        var workCopy = new dev.pocketengine.phone.access.AppCopyKey("dev.example.chat", 10);
        var apps = fence.phoneApps();
        assertEquals(dev.pocketengine.phone.access.PhoneAppResources.Result.ACQUIRED, apps.reserve("task-a/run-a/1", sameCopy, (key, owner) -> true));
        assertEquals(dev.pocketengine.phone.access.PhoneAppResources.Result.BUSY, apps.reserve("task-b/run-b/1", sameCopy, (key, owner) -> true));
        assertEquals(dev.pocketengine.phone.access.PhoneAppResources.Result.ACQUIRED, apps.reserve("task-b/run-b/1", workCopy, (key, owner) -> true));
        apps.releaseOwner("task-a/run-a/1");
        assertNull(apps.holder(sameCopy));assertEquals("task-b/run-b/1", apps.holder(workCopy));
        assertTrue(fence.ownsBackgroundPhone("run-b"));
    }

    @Test public void invalidTokensAreRefused() {
        ExecutionResources fence = new ExecutionResources(() -> 100);
        for (String token : new String[]{null, "", "   ", "x".repeat(201)}) {
            assertThrows(IllegalArgumentException.class, () -> fence.acquirePhone(token));
            assertThrows(IllegalArgumentException.class, () -> fence.acquireBackgroundPhone(token));
            assertThrows(IllegalArgumentException.class, () -> fence.acquireBrowser(token, 200));
            assertThrows(IllegalArgumentException.class, () -> fence.releasePhone(token));
            assertThrows(IllegalArgumentException.class, () -> fence.releaseBrowser(token));
        }
    }

    private static void attempt(CountDownLatch ready, CountDownLatch start, AtomicInteger winners,
                                java.util.function.BooleanSupplier acquisition) {
        ready.countDown();
        try { start.await();if (acquisition.getAsBoolean()) winners.incrementAndGet(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
