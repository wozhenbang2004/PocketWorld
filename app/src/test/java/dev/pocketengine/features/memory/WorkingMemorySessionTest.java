package dev.pocketengine.features.memory;

import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WorkingMemorySessionTest {
    private static final class Fence implements MemoryHostFence {
        boolean live=true;int admissions;
        @Override public synchronized void check(){if(!live)throw new SecurityException("原run已换代");}
        @Override public synchronized JSONObject admit(Admission action)throws Exception{check();admissions++;return action.run();}
    }
    private interface Checked {void run()throws Exception;}
    private void rejected(Checked action)throws Exception{try{action.run();fail("closed or invalid session admitted");}catch(IllegalStateException|IllegalArgumentException|SecurityException expected){}}
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONArray notes(String text)throws Exception{return new JSONArray().put(new JSONObject().put("kind","observation").put("text",text));}
    private WorkingMemorySession session(Fence fence,AtomicLong clock)throws Exception{return new WorkingMemorySession(scope(),"task-1","run-1",7,fence,clock::get);}

    @Test public void transientNotesAreBoundToHostIdentityAndExactRevisionWithDefensiveCopies()throws Exception {
        Fence fence=new Fence();WorkingMemorySession session=session(fence,new AtomicLong(100));JSONArray notes=notes("观察到的数据");
        JSONObject result=session.replace(1,notes);notes.getJSONObject(0).put("text","外部篡改");result.getJSONArray("notes").getJSONObject(0).put("text","篡改快照");
        JSONObject current=session.snapshot();assertEquals("观察到的数据",current.getJSONArray("notes").getJSONObject(0).getString("text"));
        assertEquals("task-1",current.getString("taskId"));assertEquals("run-1",current.getString("runId"));assertEquals(7,current.getLong("epoch"));assertEquals(2,current.getLong("revision"));
        assertFalse(current.getBoolean("persisted"));assertFalse(current.getBoolean("executionAuthorized"));assertTrue(current.getString("context").contains("低优先级"));assertEquals(2,fence.admissions);
        rejected(()->session.replace(1,notes("迟到的摘要")));assertEquals("观察到的数据",session.snapshot().getJSONArray("notes").getJSONObject(0).getString("text"));
    }

    @Test public void hostRevocationStopsReadAndWriteAndScrubsBeforeARevokedFenceIsRestored()throws Exception {
        Fence fence=new Fence();WorkingMemorySession session=session(fence,new AtomicLong(100));session.replace(1,notes("私人记忆"));fence.live=false;
        rejected(session::snapshot);rejected(()->session.replace(2,notes("旧run迟到数据")));fence.live=true;rejected(session::snapshot);
    }

    @Test public void ttlUsesMonotonicTimeAndReadsNeverKeepAnIdleSessionAlive()throws Exception {
        Fence fence=new Fence();AtomicLong clock=new AtomicLong(10);WorkingMemorySession session=session(fence,clock);session.replace(1,notes("有界临时数据"));
        clock.addAndGet(WorkingMemorySession.TTL_MILLIS-1);assertEquals(1,session.snapshot().getJSONArray("notes").length());clock.incrementAndGet();rejected(session::snapshot);rejected(()->session.replace(2,notes("不能恢复过期数据")));
        WorkingMemorySession backward=session(fence,clock);clock.decrementAndGet();rejected(backward::snapshot);
    }

    @Test public void limitsRejectWholeInputWithoutTruncatingOrMutatingLiveNotes()throws Exception {
        Fence fence=new Fence();WorkingMemorySession session=session(fence,new AtomicLong());session.replace(1,notes("原数据"));
        JSONArray tooMany=new JSONArray();for(int i=0;i<17;i++)tooMany.put(new JSONObject().put("kind","summary").put("text","小结"));rejected(()->session.replace(2,tooMany));
        JSONArray tooLarge=new JSONArray();for(int i=0;i<4;i++)tooLarge.put(new JSONObject().put("kind","summary").put("text","字".repeat(3900)));rejected(()->session.replace(2,tooLarge));
        rejected(()->session.replace(2,notes("越权").put(new JSONObject().put("kind","plan").put("text","执行").put("scope",scope()))));
        for(String bad:new String[]{"\uD800","\uDC00","含\0空字节"})rejected(()->session.replace(2,notes(bad)));
        JSONObject current=session.snapshot();assertEquals(2,current.getLong("revision"));assertEquals("原数据",current.getJSONArray("notes").getJSONObject(0).getString("text"));assertTrue(current.getInt("charsUsed")<=WorkingMemorySession.MAX_CONTEXT_CHARS);
    }

    @Test public void closeAlwaysWorksAfterRevocationAndFreshRunHasEmptyIndependentNotes()throws Exception {
        Fence fence=new Fence();WorkingMemorySession old=session(fence,new AtomicLong());old.replace(1,notes("上一任务数据"));fence.live=false;old.close();fence.live=true;rejected(old::snapshot);
        WorkingMemorySession next=new WorkingMemorySession(scope(),"task-1","run-2",8,fence);assertEquals("",next.snapshot().getString("context"));assertEquals(1,next.snapshot().getLong("revision"));
    }
}
