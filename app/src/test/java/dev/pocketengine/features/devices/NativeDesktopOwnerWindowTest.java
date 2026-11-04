package dev.pocketengine.features.devices;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Executes the real native owner-page lifecycle with explicit DesktopAction producers.
 * These fixtures exercise publication/cancellation and copies, not SQL, TLS or real desktop input. */
public final class NativeDesktopOwnerWindowTest {
    private static final String DEVICE = "pw1_" + "b".repeat(64);
    private final ExecutorService workers = Executors.newFixedThreadPool(3);

    @After public void stopWorkers() throws Exception {
        workers.shutdownNow();
        assertTrue("Native-window test worker did not exit", workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static JSONObject binding() throws org.json.JSONException {
        return new JSONObject().put("kind", "desktop").put("sessionId", 1)
                .put("displayId", "primary").put("windowId", 42);
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void block() throws Exception {
            entered.countDown();
            assertTrue("Fixture operation was never released", release.await(5, TimeUnit.SECONDS));
        }
        void awaitEntry() throws Exception {
            assertTrue("Fixture operation did not start", entered.await(5, TimeUnit.SECONDS));
        }
        void open() { release.countDown(); }
    }

    private static final class Desktop implements NativeDeviceWorkspaceHost.DesktopAction {
        final String sessionId;
        final JSONObject state;
        final AtomicInteger closes = new AtomicInteger(), requests = new AtomicInteger();
        volatile NativeDeviceWorkspaceHost.DesktopCurrent current;
        volatile Gate openGate, viewGate, requestGate, closeGate;
        volatile JSONObject received;
        volatile Exception viewFailure;

        Desktop(String id) throws org.json.JSONException {
            sessionId = id;
            state = new JSONObject().put("sessionId", id).put("deviceId", DEVICE)
                    .put("resourceBinding", binding()).put("detail", new JSONObject().put("value", "native"));
        }
        public String id() { return sessionId; }
        public JSONObject view() throws Exception {
            Gate gate = viewGate;
            if (gate != null) gate.block();
            if (viewFailure != null) throw viewFailure;
            return state;
        }
        public JSONObject request(String action, JSONObject args) throws Exception {
            requests.incrementAndGet();
            received = args;
            Gate gate = requestGate;
            if (gate != null) gate.block();
            return state;
        }
        public void close() {
            closes.incrementAndGet();
            Gate gate = closeGate;
            if (gate != null) try { gate.block(); }
            catch (Exception failed) { throw new AssertionError(failed); }
        }
    }

    /** Unused injected interfaces fail if the desktop path accidentally calls another owner. */
    @SuppressWarnings("unchecked") private static <T> T unused(Class<T> contract) {
        return (T) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, arguments) -> { throw new AssertionError("Unexpected owner call: " + method); });
    }
    private static NativeDeviceWorkspaceHost host(Map<String, Desktop> sources) {
        return new NativeDeviceWorkspaceHost(() -> { throw new AssertionError("Desktop open requested a global snapshot"); },
                unused(NativeDeviceWorkspaceHost.ReceiverOwner.class),
                unused(NativeDeviceWorkspaceHost.PairingOwner.class),
                unused(NativeDeviceWorkspaceHost.GoalOwner.class),
                unused(NativeDeviceWorkspaceHost.TaskControl.class),
                (device, grant, resource, current) -> {
                    assertEquals(DEVICE, device);
                    assertTrue(DeviceProtocol.resourceEquals(binding(), resource));
                    Desktop source = sources.get(grant);
                    assertNotNull("Unexpected native grant", source);
                    source.current = current;
                    if (source.openGate != null) source.openGate.block();
                    return source;
                });
    }
    private static NativeDeviceWorkspaceHost.DesktopWindow open(NativeDeviceWorkspaceHost.Window window, String grant) throws Exception {
        return window.openDesktop(DEVICE, grant, binding());
    }
    private static void fails(Future<?> pending, Class<? extends Throwable> expected) throws Exception {
        try { pending.get(5, TimeUnit.SECONDS); fail("Obsolete native operation returned a successful result"); }
        catch (ExecutionException rejected) {
            assertTrue("Unexpected failure: " + rejected.getCause(), expected.isInstance(rejected.getCause()));
        }
    }

    @Test public void replacingDesktopInvalidatesOldHandleAndOldCloseLeavesNewSessionAlive() throws Exception {
        Desktop first = new Desktop("session-first"), second = new Desktop("session-second");
        try (var host = host(Map.of("grant-first", first, "grant-second", second)); var page = host.openOwnerWindow()) {
            var old = open(page, "grant-first");
            var current = open(page, "grant-second");
            assertTrue(first.closes.get() > 0);
            assertThrows(SecurityException.class, () -> old.request("status", new JSONObject()));
            assertThrows(SecurityException.class, first.current::require);
            old.close();
            assertEquals(0, second.closes.get());
            assertEquals("session-second", current.request("status", new JSONObject()).getString("sessionId"));
            assertEquals(0, first.requests.get());
        }
    }

    @Test public void lateOlderOpenIsClosedWithoutReplacingTheNewerSession() throws Exception {
        Desktop first = new Desktop("session-first"), second = new Desktop("session-second");
        Gate pendingOpen = first.openGate = new Gate();
        try (var host = host(Map.of("grant-first", first, "grant-second", second)); var page = host.openOwnerWindow()) {
            Future<?> pending = workers.submit(() -> open(page, "grant-first"));
            try {
                pendingOpen.awaitEntry();
                var current = open(page, "grant-second");
                pendingOpen.open();
                fails(pending, SecurityException.class);
                assertTrue(first.closes.get() > 0);
                assertEquals(0, second.closes.get());
                second.current.require();
                assertEquals("session-second", current.view().getString("sessionId"));
            } finally { pendingOpen.open(); }
        }
    }

    @Test public void staleCallerAfterPreflightCannotDetachTheNewlyOpenedDesktop() throws Exception {
        Desktop first = new Desktop("session-first"), second = new Desktop("session-second");
        AtomicLong callerEpoch = new AtomicLong(1);
        AtomicInteger oldChecks = new AtomicInteger();
        Gate oldPreflight = new Gate();
        NativeDeviceWorkspaceHost.DesktopCurrent oldCaller = () -> {
            if (callerEpoch.get() != 1) throw new SecurityException("Old UI request epoch");
            // The old worker has passed its earlier UI check but is delayed before
            // the native generation/detach critical section.
            if (oldChecks.incrementAndGet() == 1) oldPreflight.block();
        };
        try (var host = host(Map.of("grant-first", first, "grant-second", second)); var page = host.openOwnerWindow()) {
            Future<?> pending = workers.submit(() -> page.openDesktop(DEVICE, "grant-first", binding(), oldCaller));
            try {
                oldPreflight.awaitEntry();
                callerEpoch.set(2);
                var current = page.openDesktop(DEVICE, "grant-second", binding(), () -> {
                    if (callerEpoch.get() != 2) throw new SecurityException("Replaced UI request epoch");
                });
                oldPreflight.open();
                fails(pending, SecurityException.class);
                assertNull("Stale caller reached the native source", first.current);
                assertEquals(0, first.closes.get());
                assertEquals(0, second.closes.get());
                second.current.require();
                assertEquals("session-second", current.view().getString("sessionId"));
            } finally { oldPreflight.open(); }
        }
    }

    @Test public void callerInvalidatedWhileOpeningClosesTheUnpublishedSource() throws Exception {
        Desktop first = new Desktop("session-first");
        AtomicLong callerEpoch = new AtomicLong(1);
        Gate pendingOpen = first.openGate = new Gate();
        try (var host = host(Map.of("grant-first", first)); var page = host.openOwnerWindow()) {
            Future<?> pending = workers.submit(() -> page.openDesktop(DEVICE, "grant-first", binding(), () -> {
                if (callerEpoch.get() != 1) throw new SecurityException("Original UI request invalidated");
            }));
            try {
                pendingOpen.awaitEntry();
                callerEpoch.set(2);
                assertThrows(SecurityException.class, first.current::require);
                pendingOpen.open();
                fails(pending, SecurityException.class);
                assertEquals(1, first.closes.get());
            } finally { pendingOpen.open(); }
        }
    }

    @Test public void slowOldCloseDoesNotHoldThePageLockOrRemoveAConcurrentSuccessor() throws Exception {
        Desktop initial = new Desktop("session-initial"), superseded = new Desktop("session-superseded"), latest = new Desktop("session-latest");
        Gate pendingClose = initial.closeGate = new Gate();
        try (var host = host(Map.of("grant-initial", initial, "grant-superseded", superseded, "grant-latest", latest));
                var page = host.openOwnerWindow()) {
            open(page, "grant-initial");
            Future<?> pending = workers.submit(() -> open(page, "grant-superseded"));
            try {
                pendingClose.awaitEntry();
                Future<NativeDeviceWorkspaceHost.DesktopWindow> latestOpen = workers.submit(() -> open(page, "grant-latest"));
                var current = latestOpen.get(5, TimeUnit.SECONDS);
                pendingClose.open();
                fails(pending, SecurityException.class);
                assertTrue(superseded.closes.get() > 0);
                assertEquals(0, latest.closes.get());
                assertEquals("session-latest", current.view().getString("sessionId"));
            } finally { pendingClose.open(); }
        }
    }

    @Test public void closingDesktopWhileOpenIsPendingDoesNotInvalidateItsLaterSuccessor() throws Exception {
        Desktop first = new Desktop("session-first"), second = new Desktop("session-second");
        Gate pendingOpen = first.openGate = new Gate();
        try (var host = host(Map.of("grant-first", first, "grant-second", second)); var page = host.openOwnerWindow()) {
            Future<?> pending = workers.submit(() -> open(page, "grant-first"));
            try {
                pendingOpen.awaitEntry();
                page.closeDesktop();
                var current = open(page, "grant-second");
                pendingOpen.open();
                fails(pending, SecurityException.class);
                assertTrue(first.closes.get() > 0);
                assertEquals(0, second.closes.get());
                assertEquals("session-second", current.view().getString("sessionId"));
            } finally { pendingOpen.open(); }
        }
    }

    @Test public void closingPageDuringOpenClosesTheLateNativeSourceAndRejectsMoreWork() throws Exception {
        Desktop first = new Desktop("session-first");
        Gate pendingOpen = first.openGate = new Gate();
        try (var host = host(Map.of("grant-first", first)); var page = host.openOwnerWindow()) {
            Future<?> pending = workers.submit(() -> open(page, "grant-first"));
            try {
                pendingOpen.awaitEntry();
                page.close();
                pendingOpen.open();
                fails(pending, IllegalStateException.class);
                assertTrue(first.closes.get() > 0);
                assertThrows(IllegalStateException.class, first.current::require);
                assertThrows(IllegalStateException.class, () -> open(page, "grant-first"));
            } finally { pendingOpen.open(); }
        }
    }

    @Test public void lateRequestReceiptCannotCrossToAReplacementSession() throws Exception {
        Desktop first = new Desktop("session-first"), second = new Desktop("session-second");
        Gate pendingRequest = first.requestGate = new Gate();
        try (var host = host(Map.of("grant-first", first, "grant-second", second)); var page = host.openOwnerWindow()) {
            var old = open(page, "grant-first");
            Future<?> pending = workers.submit(() -> old.request("status", new JSONObject()));
            try {
                pendingRequest.awaitEntry();
                var current = open(page, "grant-second");
                pendingRequest.open();
                fails(pending, SecurityException.class);
                assertEquals(1, first.requests.get());
                assertEquals("session-second", current.view().getString("sessionId"));
            } finally { pendingRequest.open(); }
        }
    }

    @Test public void pageCloseDiscardsAnAlreadyDispatchedRequestResult() throws Exception {
        Desktop first = new Desktop("session-first");
        Gate pendingRequest = first.requestGate = new Gate();
        try (var host = host(Map.of("grant-first", first)); var page = host.openOwnerWindow()) {
            var desktop = open(page, "grant-first");
            Future<?> pending = workers.submit(() -> desktop.request("status", new JSONObject()));
            try {
                pendingRequest.awaitEntry();
                page.close();
                pendingRequest.open();
                fails(pending, IllegalStateException.class);
                assertTrue(first.closes.get() > 0);
                assertEquals(1, first.requests.get());
            } finally { pendingRequest.open(); }
        }
    }

    @Test public void exactSessionCloseFinishesWithoutWaitingForBlockedInputOrClaimingRemoteCleanup() throws Exception {
        Desktop first = new Desktop("session-first");
        Gate pendingRequest = first.requestGate = new Gate();
        try (var host = host(Map.of("grant-first", first)); var page = host.openOwnerWindow()) {
            var desktop = open(page, "grant-first");
            Future<?> pending = workers.submit(() -> desktop.request("input", new JSONObject()));
            try {
                pendingRequest.awaitEntry();
                JSONObject closed = workers.submit(() -> page.closeDesktop(desktop.id())).get(5, TimeUnit.SECONDS);
                assertEquals(desktop.id(), closed.getString("sessionId"));
                assertTrue(closed.getBoolean("closed"));
                assertFalse(closed.getBoolean("remoteCloseConfirmed"));
                assertFalse("Close must not wait for the pending native result", pending.isDone());
                assertEquals(1, first.closes.get());
                assertThrows(SecurityException.class, first.current::require);
                assertThrows(SecurityException.class, () -> desktop.request("input", new JSONObject()));
                pendingRequest.open();
                fails(pending, SecurityException.class);
                assertEquals(1, first.requests.get());
                desktop.close(); assertEquals(1, first.closes.get());
            } finally { pendingRequest.open(); }
        }
    }

    @Test public void exactOldSessionIdCannotCloseOrInvalidateAReplacement() throws Exception {
        Desktop first = new Desktop("session-first"), second = new Desktop("session-second");
        try (var host = host(Map.of("grant-first", first, "grant-second", second)); var page = host.openOwnerWindow()) {
            var old = open(page, "grant-first");
            var current = open(page, "grant-second");
            assertThrows(SecurityException.class, () -> page.closeDesktop(old.id()));
            assertEquals(1, first.closes.get()); assertEquals(0, second.closes.get());
            second.current.require();
            assertEquals("session-second", current.request("status", new JSONObject()).getString("sessionId"));
            page.closeDesktop(current.id());
            assertThrows(SecurityException.class, second.current::require);
            assertThrows(SecurityException.class, () -> page.closeDesktop(old.id()));
            assertThrows(SecurityException.class, () -> page.closeDesktop(current.id()));
            assertEquals(1, second.closes.get());
            page.close();
            assertThrows(IllegalStateException.class, () -> page.closeDesktop(current.id()));
            assertEquals(1, first.closes.get()); assertEquals(1, second.closes.get());
        }
    }

    @Test public void explicitHandleCloseDiscardsItsLateViewAndBlocksFurtherRequests() throws Exception {
        Desktop first = new Desktop("session-first");
        try (var host = host(Map.of("grant-first", first)); var page = host.openOwnerWindow()) {
            var desktop = open(page, "grant-first");
            Gate pendingView = first.viewGate = new Gate();
            Future<?> pending = workers.submit(desktop::view);
            try {
                pendingView.awaitEntry();
                desktop.close();
                pendingView.open();
                fails(pending, SecurityException.class);
                assertThrows(SecurityException.class, () -> desktop.request("status", new JSONObject()));
                assertEquals(0, first.requests.get());
            } finally { pendingView.open(); }
        }
    }

    @Test public void hostCloseInvalidatesEveryPageWithoutConfusingTheirSessions() throws Exception {
        Desktop first = new Desktop("session-first"), second = new Desktop("session-second");
        try (var host = host(Map.of("grant-first", first, "grant-second", second));
                var firstPage = host.openOwnerWindow(); var secondPage = host.openOwnerWindow()) {
            var firstWindow = open(firstPage, "grant-first");
            var secondWindow = open(secondPage, "grant-second");
            firstPage.close();
            assertTrue(first.closes.get() > 0);
            assertEquals(0, second.closes.get());
            assertEquals("session-second", secondWindow.view().getString("sessionId"));
            host.close();
            assertTrue(second.closes.get() > 0);
            assertThrows(IllegalStateException.class, firstWindow::view);
            assertThrows(IllegalStateException.class, secondWindow::view);
            assertThrows(IllegalStateException.class, host::openOwnerWindow);
        }
    }

    @Test public void nativeOpenIdentityMismatchOrFailureAlwaysClosesItsUnpublishedSource() throws Exception {
        for (String changed : new String[]{"sessionId", "deviceId", "resourceBinding", "failure"}) {
            Desktop source = new Desktop("session-first");
            if (changed.equals("failure")) source.viewFailure = new java.io.IOException("Native read failed");
            else if (changed.equals("resourceBinding")) source.state.put(changed, binding().put("windowId", 43));
            else source.state.put(changed, changed.equals("deviceId") ? "pw1_" + "c".repeat(64) : "session-other");
            try (var host = host(Map.of("grant-first", source)); var page = host.openOwnerWindow()) {
                if (changed.equals("failure")) assertThrows(java.io.IOException.class, () -> open(page, "grant-first"));
                else assertThrows(SecurityException.class, () -> open(page, "grant-first"));
                assertEquals(1, source.closes.get());
            }
        }
    }

    @Test public void nativeViewsRequestArgumentsAndRepliesAreDefensiveCopies() throws Exception {
        Desktop source = new Desktop("session-first");
        try (var host = host(Map.of("grant-first", source)); var page = host.openOwnerWindow()) {
            var desktop = open(page, "grant-first");
            JSONObject view = desktop.view();
            view.getJSONObject("detail").put("value", "web-edited");
            assertEquals("native", source.state.getJSONObject("detail").getString("value"));
            JSONObject request = new JSONObject().put("nested", new JSONObject().put("value", "original"));
            JSONObject receipt = desktop.request("status", request);
            source.received.getJSONObject("nested").put("value", "native-edited");
            assertEquals("original", request.getJSONObject("nested").getString("value"));
            receipt.getJSONObject("detail").put("value", "web-edited-receipt");
            assertEquals("native", source.state.getJSONObject("detail").getString("value"));
        }
    }
}
