package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;

/** In-memory packet invariants only; no process, permission or TaskStore acceptance claims. */
public final class RuntimePipeInputTest {
    @Test public void exactPacketIsOneFrozenTicketAndDuplicateNeverQueuesAgain()throws Exception {
        RuntimePipeInput queue=new RuntimePipeInput(100);byte[] bytes={0,(byte)255,10};var original=queue.offer("original",bytes,false,()->{});bytes[0]=4;
        byte[] packet=queue.next();ByteBuffer data=ByteBuffer.wrap(packet);assertEquals(1,data.getInt());assertEquals(original.sequence,data.getLong());assertEquals(3,data.getInt());assertEquals(0,data.get());
        JSONObject ack=queue.observed(original.sequence,3,0,true);assertEquals("delivered",ack.getString("status"));assertFalse(ack.getBoolean("applicationConsumptionProven"));assertFalse(ack.getBoolean("inputTextIncluded"));
        assertSame(original,queue.offer("original",new byte[]{0,(byte)255,10},false,()->{}));assertNull(queue.next());
        assertThrows(SecurityException.class,()->queue.offer("original",new byte[]{1},false,()->{}));
    }
    @Test public void budgetIsNeverRefundedButRealEofWritesNoAdditionalBytes()throws Exception {
        RuntimePipeInput queue=new RuntimePipeInput(3);var first=queue.offer("one",new byte[3],false,()->{});queue.next();queue.observed(first.sequence,3,0,true);
        assertThrows(SecurityException.class,()->queue.offer("over",new byte[1],false,()->{}));
        var end=queue.offer("close",new byte[0],true,()->{});assertNotNull(queue.next());JSONObject ack=queue.observed(end.sequence,0,0,true);
        assertTrue(ack.getBoolean("stdinClosed"));assertEquals(3,ack.getInt("inputBytesCharged"));
        assertThrows(SecurityException.class,()->queue.offer("later",new byte[1],false,()->{}));
    }
    @Test public void activeNativePacketCountsInTheSixteenPendingBound()throws Exception {
        RuntimePipeInput queue=new RuntimePipeInput(100);queue.offer("active",new byte[1],false,()->{});queue.next();
        for(int i=1;i<16;i++)queue.offer("queued"+i,new byte[1],false,()->{});
        assertThrows(SecurityException.class,()->queue.offer("seventeenth",new byte[1],false,()->{}));
    }
    @Test public void queueTimeoutBeforeDequeueIsKnownButAfterDequeueHasUnknownEffects()throws Exception {
        RuntimePipeInput queue=new RuntimePipeInput(100);var pending=queue.offer("not-entered",new byte[1],false,()->{});
        JSONObject never=queue.await(pending,RuntimeContractsTest.original(),1);assertEquals("not_dispatched",never.getString("status"));assertFalse(never.getBoolean("effectMayHaveOccurred"));assertNull(queue.next());
        var entered=queue.offer("entered",new byte[1],false,()->{});queue.next();JSONObject unknown=queue.await(entered,RuntimeContractsTest.original(),1);
        assertEquals("unknown",unknown.getString("status"));assertTrue(unknown.getBoolean("effectMayHaveOccurred"));assertFalse(queue.current(entered.sequence));
        assertThrows(SecurityException.class,()->queue.offer("next",new byte[1],false,()->{}));
    }
    @Test public void partialAckCannotTurnIntoCompleteAndNativeDuplicateAckIsRejected()throws Exception {
        RuntimePipeInput queue=new RuntimePipeInput(100);var value=queue.offer("partial",new byte[20],false,()->{});queue.next();
        JSONObject ack=queue.observed(value.sequence,7,5,true);assertEquals("unknown",ack.getString("status"));assertEquals(7,ack.getInt("writtenBytes"));
        assertThrows(IllegalStateException.class,()->queue.observed(value.sequence,20,0,true));
        assertThrows(SecurityException.class,()->queue.offer("next",new byte[1],false,()->{}));
    }
    @Test public void stalePerRequestGateRejectsBeforeQueueAndBeforeEachNativeWrite()throws Exception {
        RuntimePipeInput queue=new RuntimePipeInput(100);AtomicBoolean current=new AtomicBoolean(false);
        var gate=(ProjectRuntime.LiveGate)()->{if(!current.get())throw new SecurityException("owned fixture revoked");};
        assertThrows(SecurityException.class,()->queue.offer("denied",new byte[1],false,gate));assertNull(queue.next());
        current.set(true);var accepted=queue.offer("accepted",new byte[1],false,gate);queue.next();assertTrue(queue.current(accepted.sequence));
        current.set(false);assertThrows(SecurityException.class,()->queue.current(accepted.sequence));queue.close();assertEquals("unknown",queue.query("accepted").getString("status"));assertTrue(queue.query("accepted").getBoolean("effectMayHaveOccurred"));
    }
    @Test public void invalidSizeActionOrTokenCannotCreateAPacket()throws Exception {
        RuntimePipeInput queue=new RuntimePipeInput(RuntimePipeInput.MAX_TOTAL);
        for(byte[] bytes:Arrays.asList(new byte[0],new byte[RuntimePipeInput.MAX_PACKET+1]))assertThrows(IllegalArgumentException.class,()->queue.offer("bad",bytes,false,()->{}));
        assertThrows(IllegalArgumentException.class,()->queue.offer("bad",new byte[1],true,()->{}));
        assertThrows(IllegalArgumentException.class,()->queue.offer("not a native operation",new byte[1],false,()->{}));assertNull(queue.next());
    }
}
