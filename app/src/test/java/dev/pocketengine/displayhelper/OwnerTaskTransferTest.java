package dev.pocketengine.displayhelper;

import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual helper state machine with explicit binder fixtures; not an Android device claim. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class)
public final class OwnerTaskTransferTest {
    static final class Backend implements OwnerTaskTransfer.Backend {
        final List<OwnerTaskTransfer.Task> rows=new ArrayList<>();int moves;boolean loseReceipt;
        Backend(){rows.add(task(7,9,"owned-token"));rows.add(task(8,0,"same-app-main-token"));}
        public List<OwnerTaskTransfer.Task> tasks(){return new ArrayList<>(rows);}
        public void move(int id,int display)throws Exception {moves++;for(int i=0;i<rows.size();i++){OwnerTaskTransfer.Task t=rows.get(i);if(t.id==id)rows.set(i,new OwnerTaskTransfer.Task(t.id,display,t.user,t.component,t.token,t.children));}if(loseReceipt)throw new Exception("binder reply lost");}
        static OwnerTaskTransfer.Task task(int id,int display,String token){return new OwnerTaskTransfer.Task(id,display,0,"example.app/.Original",token,new int[]{id});}
    }
    @Test public void exactTaskMovesAndReturnsWithoutTouchingSamePackageMainTask()throws Exception {Backend b=new Backend();OwnerTaskTransfer t=new OwnerTaskTransfer(b,9);String id=t.prepare(10).getString("handoffId");assertEquals("main",t.transfer(id,false,11).getString("state"));assertTrue(t.blocksInput());assertEquals(0,b.rows.get(1).display);assertEquals(8,b.rows.get(1).id);assertEquals("returned",t.transfer(id,true,12).getString("state"));assertFalse(t.blocksInput());assertEquals(9,b.rows.get(0).display);assertEquals(2,b.moves);assertThrows(SecurityException.class,()->t.transfer(id,true,13));}
    @Test public void recycledTaskIdTokenOrRootGroupChangeIsRefused()throws Exception {Backend b=new Backend();OwnerTaskTransfer t=new OwnerTaskTransfer(b,9);String id=t.prepare(10).getString("handoffId");b.rows.set(0,Backend.task(7,9,"replacement-token"));assertThrows(SecurityException.class,()->t.transfer(id,false,11));assertEquals(0,b.moves);b.rows.set(0,new OwnerTaskTransfer.Task(7,9,0,"example.app/.Original","owned-token",new int[]{7,99}));assertThrows(SecurityException.class,()->t.transfer(id,false,11));assertEquals(0,b.moves);}
    @Test public void ambiguousOwnedRootsAndMultipleChildrenNeverMove()throws Exception {Backend b=new Backend();b.rows.add(Backend.task(99,9,"other"));OwnerTaskTransfer t=new OwnerTaskTransfer(b,9);assertThrows(IllegalStateException.class,()->t.prepare(1));b.rows.remove(2);b.rows.set(0,new OwnerTaskTransfer.Task(7,9,0,"example.app/.Original","owned-token",new int[]{7,99}));assertThrows(IllegalStateException.class,()->t.prepare(1));assertEquals(0,b.moves);}
    @Test public void expiredOrInventedConsentCannotMove()throws Exception {Backend b=new Backend();OwnerTaskTransfer t=new OwnerTaskTransfer(b,9);String id=t.prepare(1).getString("handoffId");assertThrows(SecurityException.class,()->t.transfer("invented",false,2));assertThrows(SecurityException.class,()->t.transfer(id,false,60_002));assertEquals(0,b.moves);}
    @Test public void lostReplyBlocksInputAndRetryUntilReadOnlyExactReconciliation()throws Exception {Backend b=new Backend();OwnerTaskTransfer t=new OwnerTaskTransfer(b,9);String id=t.prepare(1).getString("handoffId");b.loseReceipt=true;JSONObject unknown=t.transfer(id,false,2);assertTrue(unknown.isNull("accepted"));assertTrue(t.blocksInput());assertThrows(SecurityException.class,()->t.transfer(id,false,3));assertEquals(1,b.moves);assertEquals("main",t.status().getString("state"));assertEquals(1,b.moves);b.loseReceipt=false;assertEquals("returned",t.transfer(id,true,4).getString("state"));}
    @Test public void missingOrReplacedMainTaskCannotBeClaimedOrReturned()throws Exception {Backend b=new Backend();OwnerTaskTransfer t=new OwnerTaskTransfer(b,9);String id=t.prepare(1).getString("handoffId");t.transfer(id,false,2);b.rows.set(0,Backend.task(7,0,"replacement"));assertEquals("unknown",t.status().getString("state"));assertTrue(t.blocksInput());assertThrows(SecurityException.class,()->t.transfer(id,true,3));assertEquals(1,b.moves);}
    @Test public void preparedStatusRevokesOldDispatchBeforeProvingNoMigration()throws Exception {Backend b=new Backend();OwnerTaskTransfer t=new OwnerTaskTransfer(b,9);String old=t.prepare(1).getString("handoffId");JSONObject checked=t.status();assertTrue(checked.getBoolean("notDispatched"));assertTrue(checked.getBoolean("planRevoked"));assertEquals("prepared",checked.getString("state"));assertThrows(SecurityException.class,()->t.transfer(old,false,2));assertEquals(0,b.moves);String fresh=t.prepare(3).getString("handoffId");assertNotEquals(old,fresh);assertEquals("main",t.transfer(fresh,false,4).getString("state"));assertEquals(1,b.moves);}
}
