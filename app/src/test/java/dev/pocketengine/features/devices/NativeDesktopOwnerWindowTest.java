package dev.pocketengine.features.devices;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real owner Window lifecycle; the remote DesktopOwner/Session are explicit fixtures. */
public final class NativeDesktopOwnerWindowTest {
    private static final long WAIT_SECONDS = 3;

    private static final class DesktopFixture implements NativeDeviceWorkspaceHost.DesktopOwner {
        final List<SessionFixture> sessions = new CopyOnWriteArrayList<>();
        final CountDownLatch openEntered = new CountDownLatch(1);
        final CountDownLatch finishOpen;
        final boolean blockCommand;

        DesktopFixture(boolean blockOpen, boolean blockCommand) {
            finishOpen = new CountDownLatch(blockOpen ? 1 : 0);
            this.blockCommand = blockCommand;
        }

        @Override public NativeDeviceWorkspaceHost.DesktopSession open(String deviceId, String grantId,
                JSONObject binding, JSONObject target, NativeDeviceWorkspaceHost.OwnerCurrent current) throws Exception {
            current.require();
            SessionFixture session = new SessionFixture("fixture-view-" + (sessions.size() + 1), current, blockCommand);
            sessions.add(session);
            openEntered.countDown();
            await(finishOpen, "The fixture configuration request was not released");
            // Deliberately return a late producer result: the real Window must
            // reject and close it rather than relying on a well-behaved server.
            return session;
        }
    }

    private static final class SessionFixture implements NativeDeviceWorkspaceHost.DesktopSession {
        final String id;
        final NativeDeviceWorkspaceHost.OwnerCurrent ownerCurrent;
        final AtomicInteger closes = new AtomicInteger(), commands = new AtomicInteger(), dispatches = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        final CountDownLatch commandEntered = new CountDownLatch(1), finishCommand;

        SessionFixture(String id, NativeDeviceWorkspaceHost.OwnerCurrent ownerCurrent, boolean blockCommand) {
            this.id = id; this.ownerCurrent = ownerCurrent;
            finishCommand = new CountDownLatch(blockCommand ? 1 : 0);
        }

        @Override public String id() { return id; }
        @Override public JSONObject view() throws Exception {
            ownerCurrent.require();
            return new JSONObject().put("kind", "desktopSession").put("sessionId", id);
        }
        @Override public synchronized JSONObject command(String action, JSONObject arguments) throws Exception {
            ownerCurrent.require();
            commands.incrementAndGet(); commandEntered.countDown();
            await(finishCommand, "The fixture desktop command was not released");
            ownerCurrent.require();
            if (closed.get()) throw new SecurityException("The fixture session was closed");
            dispatches.incrementAndGet();
            return new JSONObject().put("sessionId", id).put("status", "completed");
        }
        // The real source also fences immediately and performs network cleanup
        // separately; taking the command monitor here would defeat that contract.
        @Override public void close() { closed.set(true); closes.incrementAndGet(); }
    }

    @SuppressWarnings("unchecked") private static <T> T unusedOwner(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            throw new AssertionError("Unrelated owner invoked: " + method.getName());
        });
    }
    private static NativeDeviceWorkspaceHost host(DesktopFixture desktop) {
        return new NativeDeviceWorkspaceHost(() -> new JSONObject().put("format", NativeDeviceWorkspaceHost.FORMAT)
                .put("capturedAt", System.currentTimeMillis()).put("local", new JSONObject())
                .put("peers", new JSONArray()).put("pendingPairings", new JSONArray()),
                unusedOwner(NativeDeviceWorkspaceHost.ReceiverOwner.class), unusedOwner(NativeDeviceWorkspaceHost.PairingOwner.class),
                unusedOwner(NativeDeviceWorkspaceHost.GoalOwner.class), unusedOwner(NativeDeviceWorkspaceHost.TaskControl.class), desktop);
    }
    private static JSONObject binding() throws Exception {
        return new JSONObject().put("kind", "desktop").put("sessionId", 1).put("displayId", "primary");
    }
    private static ExecutorService workers() {
        return Executors.newFixedThreadPool(2, work -> {
            Thread thread = new Thread(work, "desktop-window-fixture"); thread.setDaemon(true); return thread;
        });
    }
    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError(message);
    }
    private static void rejected(Future<?> future, Class<? extends Throwable> expected) throws Exception {
        try { future.get(WAIT_SECONDS, TimeUnit.SECONDS); fail("A retired owner operation unexpectedly succeeded"); }
        catch (ExecutionException rejected) { assertTrue("Expected " + expected.getSimpleName() + ", got " + rejected.getCause(), expected.isInstance(rejected.getCause())); }
    }
    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow(); assertTrue("Fixture workers must finish", workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test(timeout = 12000) public void windowCloseFencesAnOpeningSessionAndClosesItsLateResultExactlyOnce() throws Exception {
        DesktopFixture desktop = new DesktopFixture(true, false);
        ExecutorService workers = workers();
        try (var host = host(desktop); var window = host.openOwnerWindow()) {
            try {
                Future<JSONObject> opening = workers.submit(() -> window.openDesktop("fixture-computer", "fixture-grant", binding(), null));
                await(desktop.openEntered, "Opening did not reach the remote configuration fixture");
                SessionFixture late = desktop.sessions.get(0);
                Future<?> closing = workers.submit(window::close);
                closing.get(WAIT_SECONDS, TimeUnit.SECONDS);
                assertFalse("Close must not wait for the remote configuration", opening.isDone());
                assertEquals("The Window has not received the late handle yet", 0, late.closes.get());
                assertThrows(IllegalStateException.class, late.ownerCurrent::require);
                desktop.finishOpen.countDown();
                rejected(opening, IllegalStateException.class);
                assertEquals(1, late.closes.get());
                assertEquals(0, late.commands.get());
                window.close(); assertEquals("Repeated Window close does not close the late handle again", 1, late.closes.get());
            } finally { desktop.finishOpen.countDown(); }
        } finally { stop(workers); }
    }

    @Test(timeout = 12000) public void exactCloseDoesNotWaitForBlockedInputAndInvalidatesItsOriginalOwnerGuard() throws Exception {
        DesktopFixture desktop = new DesktopFixture(false, true);
        ExecutorService workers = workers();
        try (var host = host(desktop); var window = host.openOwnerWindow()) {
            window.openDesktop("fixture-computer", "fixture-grant", binding(), null);
            SessionFixture original = desktop.sessions.get(0);
            try {
                Future<JSONObject> input = workers.submit(() -> window.desktopCommand(original.id, "input", new JSONObject()));
                await(original.commandEntered, "Input did not reach the blocked desktop fixture");
                Future<JSONObject> closing = workers.submit(() -> window.closeDesktop(original.id));
                JSONObject closed = closing.get(WAIT_SECONDS, TimeUnit.SECONDS);
                assertEquals(original.id, closed.getString("sessionId"));
                assertTrue(closed.getBoolean("closed"));
                assertFalse("Local fencing cannot certify remote cleanup", closed.getBoolean("remoteCloseConfirmed"));
                assertFalse("Exact close must finish while the normal command is still blocked", input.isDone());
                assertEquals(1, original.closes.get());
                assertThrows(SecurityException.class, original.ownerCurrent::require);
                assertThrows(SecurityException.class, () -> window.desktopCommand(original.id, "input", new JSONObject()));
                original.finishCommand.countDown();
                rejected(input, SecurityException.class);
                assertEquals("An invalidated input never reaches the fixture dispatch marker", 0, original.dispatches.get());
                assertEquals(1, original.commands.get());
            } finally { original.finishCommand.countDown(); }
        } finally { stop(workers); }
    }

    @Test public void anOldSessionIdCannotCloseOrInvalidateItsReplacement() throws Exception {
        DesktopFixture desktop = new DesktopFixture(false, false);
        try (var host = host(desktop); var window = host.openOwnerWindow()) {
            window.openDesktop("fixture-computer", "fixture-grant", binding(), null);
            SessionFixture old = desktop.sessions.get(0);
            window.openDesktop("fixture-computer", "fixture-grant", binding(), null);
            SessionFixture current = desktop.sessions.get(1);
            assertEquals(1, old.closes.get()); assertThrows(SecurityException.class, old.ownerCurrent::require);
            assertThrows(SecurityException.class, () -> window.closeDesktop(old.id));
            assertEquals(0, current.closes.get()); current.ownerCurrent.require();
            assertEquals(current.id, window.desktopCommand(current.id, "input", new JSONObject()).getString("sessionId"));
            assertEquals(1, current.dispatches.get());
            window.closeDesktop(current.id);
            assertEquals(1, current.closes.get()); assertThrows(SecurityException.class, current.ownerCurrent::require);
            assertThrows(SecurityException.class, () -> window.closeDesktop(old.id));
            assertEquals(1, old.closes.get()); assertEquals(1, current.closes.get());
        }
    }

    @Test public void closedOwnerWindowCannotReopenAViewerOrSendMoreInput() throws Exception {
        DesktopFixture desktop = new DesktopFixture(false, false);
        try (var host = host(desktop); var window = host.openOwnerWindow()) {
            window.openDesktop("fixture-computer", "fixture-grant", binding(), null);
            SessionFixture original = desktop.sessions.get(0);
            window.close();
            assertThrows(IllegalStateException.class, () -> window.openDesktop("fixture-computer", "fixture-grant", binding(), null));
            assertThrows(IllegalStateException.class, () -> window.desktopCommand(original.id, "input", new JSONObject()));
            assertThrows(IllegalStateException.class, () -> window.closeDesktop(original.id));
            assertThrows(IllegalStateException.class, original.ownerCurrent::require);
            assertEquals(1, desktop.sessions.size()); assertEquals(1, original.closes.get()); assertEquals(0, original.commands.get());
            host.close(); assertThrows(IllegalStateException.class, host::openOwnerWindow);
        }
    }
}
