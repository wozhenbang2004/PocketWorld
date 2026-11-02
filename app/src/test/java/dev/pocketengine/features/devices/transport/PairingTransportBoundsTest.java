package dev.pocketengine.features.devices.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PairingTransportBoundsTest {
    @Test public void isolatedCandidateFramingRejectsBusinessSizedControlAndAllBinary() throws Exception {
        DeviceFrameCodec.Listener never = new DeviceFrameCodec.Listener() {
            public void control(byte[] raw) { fail("Oversized onboarding control reached native handler"); }
            public void chunk(UUID id, long offset, byte[] raw) { fail("Binary reached native onboarding"); }
        };
        for (byte[] raw : new byte[][] {ByteBuffer.allocate(4).putInt(PairingCandidateTransport.MAX_CANDIDATE_BYTES + 1).array(), DeviceFrameCodec.chunk(UUID.randomUUID(), 0, new byte[]{1})}) {
            try { new DeviceFrameCodec.Decoder(PairingCandidateTransport.MAX_CANDIDATE_BYTES, false).accept(ByteBuffer.wrap(raw), never); fail("Invalid isolated frame accepted"); }
            catch (IOException expected) { }
        }
    }
    @Test public void invitationExpiryPreflightDoesNotCreateTrustOrStartTls() throws Exception {
        long now = System.currentTimeMillis();
        for (long expiry : new long[]{0, now - 1, now + 301_000}) {
            try { PairingCandidateTransport.deadline(expiry); fail("Invalid native invitation accepted"); }
            catch (IOException expected) { }
        }
        assertTrue(PairingCandidateTransport.deadline(now + 120_000) <= System.currentTimeMillis() + 15_000);
    }
}
