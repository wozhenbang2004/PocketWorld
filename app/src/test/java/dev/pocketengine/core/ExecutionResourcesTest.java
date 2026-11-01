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

    @Test public void invalidTokensAreRefused() {
        ExecutionResources fence = new ExecutionResources(() -> 100);
        for (String token : new String[]{null, "", "   ", "x".repeat(201)}) {
            assertThrows(IllegalArgumentException.class, () -> fence.acquirePhone(token));
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
