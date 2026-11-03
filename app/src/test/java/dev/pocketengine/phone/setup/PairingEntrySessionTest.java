package dev.pocketengine.phone.setup;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PairingEntrySessionTest {
    // Retained from the original PhoneSetupLifecycleTest; scoped here without its unrelated audio/intent cases.
    @Test public void oldNotificationCannotConsumeOrCancelCurrentPairingSession() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        PairingEntrySession session = new PairingEntrySession(clock::get);
        var old = session.begin("127.0.0.1", 12345);
        var fresh = session.begin("127.0.0.1", 23456);
        assertThrows(SecurityException.class, () -> session.submit(old.id(), "123456"));
        assertFalse(session.cancel(old.id()));
        assertTrue(session.snapshot().getBoolean("active"));
        JSONObject request = session.submit(fresh.id(), "482911");
        assertEquals(23456, request.getInt("pairingPort"));
        assertFalse(session.snapshot().getBoolean("active"));
        assertThrows(SecurityException.class, () -> session.submit(fresh.id(), "482911"));
        assertFalse(session.snapshot().toString().contains("482911"));
    }

    // Retained expiry/cancel coverage from the original source.
    @Test public void pairingExpiryAndCancelNeverSubmitCode() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        PairingEntrySession session = new PairingEntrySession(clock::get);
        var entry = session.begin("127.0.0.1", 11111);
        clock.set(120000);
        assertThrows(SecurityException.class, () -> session.submit(entry.id(), "123456"));
        var next = session.begin("127.0.0.1", 22222);
        session.cancel();
        assertThrows(SecurityException.class, () -> session.submit(next.id(), "123456"));
    }

    @Test public void repeatedBeginCannotExtendNonceAndOldExpiryCannotCancelReplacement() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        PairingEntrySession session = new PairingEntrySession(clock::get);
        var original = session.begin("127.0.0.1", 12345, "wireless:4:8");
        clock.set(119000);
        var duplicate = session.begin("127.0.0.1", 12345, "wireless:4:8");
        assertEquals(original.id(), duplicate.id());
        assertEquals(original.expiresAt(), duplicate.expiresAt());
        var replacement = session.begin("127.0.0.1", 12346, "wireless:4:8");
        clock.set(120101);
        assertFalse(session.expire(original.id()));
        assertEquals(replacement.id(), session.snapshot().getString("sessionId"));
    }

    @Test public void changedSourceInvalidatesNonceButMalformedCodeDoesNotConsumeIt() throws Exception {
        PairingEntrySession session = new PairingEntrySession(() -> 100);
        var entry = session.begin("127.0.0.1", 12345, "wireless:4:8");
        assertThrows(IllegalArgumentException.class, () -> session.submit(entry.id(), "12345", "wireless:4:8"));
        assertTrue(session.snapshot().getBoolean("active"));
        assertThrows(SecurityException.class, () -> session.submit(entry.id(), "123456", "wireless:5:9"));
        assertFalse(session.snapshot().getBoolean("active"));
    }

    @Test public void concurrentRepliesClaimExactlyOnce() throws Exception {
        PairingEntrySession session = new PairingEntrySession(() -> 100);
        var entry = session.begin("127.0.0.1", 12345, "wireless:4:8");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        Runnable submit = () -> {
            try { start.await(); session.submit(entry.id(), "123456", "wireless:4:8"); claimed.incrementAndGet(); }
            catch (SecurityException expected) { }
            catch (Exception unexpected) { throw new AssertionError(unexpected); }
        };
        Thread first = new Thread(submit), second = new Thread(submit);
        first.start(); second.start(); start.countDown(); first.join(2000); second.join(2000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        assertEquals(1, claimed.get());
    }
}
