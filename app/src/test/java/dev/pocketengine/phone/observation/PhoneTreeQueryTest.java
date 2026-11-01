package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneTreeQueryTest {
    static JSONObject node(String id,String parent,String text,boolean clickable,int...path)throws Exception{
        JSONArray p=new JSONArray();for(int i:path)p.put(i);
        return new JSONObject().put("id",id).put("parentId",parent==null?JSONObject.NULL:parent).put("windowId",9).put("path",p)
                .put("text",text).put("className","View").put("role","text").put("resourceId","")
                .put("visible",true).put("enabled",true).put("clickable",clickable).put("children",new JSONArray());
    }
    static JSONObject tree(String id)throws Exception{
        JSONObject root=node("n0",null,"列表",false),a=node("n1","n0","甲",true,0),aText=node("n2","n1","编辑",false,0,0);
        JSONObject b=node("n3","n0","乙",true,1),bText=node("n4","n3","编辑",false,1,0);
        root.put("children",new JSONArray().put("n1").put("n3"));a.getJSONArray("children").put("n2");b.getJSONArray("children").put("n4");
        JSONObject out=new JSONObject().put("ok",true).put("observationId",id).put("treeSchemaVersion",1).put("displayId",5).put("sessionId","s")
                .put("pauseRevision",0).put("observedAtUptimeMs",1000).put("truncated",false)
                .put("windows",new JSONArray().put(new JSONObject().put("windowId",9).put("displayId",5).put("rootAvailable",true)
                        .put("rootIds",new JSONArray().put("n0")))).put("nodes",new JSONArray().put(root).put(a).put(aText).put(b).put(bText));
        return ObservationReport.attach(out,true,"accessibility_tree",false,"not_requested");
    }
    @Test public void repeatedLabelsStaySeparateAndParentScopeGetsCorrectActionAncestor()throws Exception{
        JSONObject t=tree("o"),args=new JSONObject().put("observationId","o").put("selector",new JSONObject().put("text","编辑"));
        JSONObject all=PhoneTreeQuery.query(t,args);assertEquals(2,all.getInt("total"));assertEquals("n1",all.getJSONArray("matches").getJSONObject(0).getString("actionNodeId"));
        args.getJSONObject("selector").put("parentId","n3");JSONObject result=PhoneTreeQuery.query(t,args);
        assertEquals(1,result.getInt("total"));assertEquals("n4",result.getJSONArray("matches").getJSONObject(0).getString("id"));
        assertEquals("n3",result.getJSONArray("matches").getJSONObject(0).getString("actionNodeId"));
        assertEquals(3,result.getJSONObject("observation").getJSONArray("nodes").length());
        assertEquals("n0",result.getJSONObject("observation").getJSONArray("windows").getJSONObject(0).getJSONArray("rootIds").getString(0));
    }
    @Test public void wrongParentWindowAndStaleObservationNeverChooseAnotherLabel()throws Exception{
        JSONObject args=new JSONObject().put("observationId","o").put("selector",new JSONObject().put("text","编辑").put("parentId","gone"));
        assertEquals("parent_unavailable",PhoneTreeQuery.query(tree("o"),args).getString("reason"));
        args.put("observationId","old");assertEquals("stale_observation",PhoneTreeQuery.query(tree("o"),args).getString("reason"));
        args.put("observationId","o").getJSONObject("selector").put("parentId","n3").put("windowId",10);
        assertEquals("parent_outside_window",PhoneTreeQuery.query(tree("o"),args).getString("reason"));
    }
    @Test public void secretsAndInvisibleNodesNeverBecomeQueryMatches()throws Exception{
        JSONObject t=tree("o");t.getJSONArray("nodes").getJSONObject(2).put("password",true);t.getJSONArray("nodes").getJSONObject(4).put("visible",false);
        assertEquals(0,PhoneTreeQuery.query(t,new JSONObject().put("observationId","o").put("selector",new JSONObject().put("text","编辑"))).getInt("total"));
    }
    @Test public void partialTreeCannotProveAbsenceOrStability()throws Exception{
        for(String kind:new String[]{"absent","stable"}){
            JSONObject t=tree("o");t.put("truncated",true);
            JSONObject out=PhoneTreeQuery.await(new JSONObject().put("kind",kind).put("timeoutMs",100).put("selector",new JSONObject().put("text","missing")),null,()->t,new RunControl());
            assertEquals("timed_out",out.getString("status"));assertFalse(out.getBoolean("matched"));assertEquals(1,out.getInt("observations"));
        }
    }
    @Test public void presentCanReadPartialEvidenceButDoesNotAssertBusinessSuccess()throws Exception{
        JSONObject t=tree("o");t.put("truncated",true);
        JSONObject out=PhoneTreeQuery.await(new JSONObject().put("kind","present").put("timeoutMs",100).put("selector",new JSONObject().put("text","编辑")),null,()->t,new RunControl());
        assertEquals("matched",out.getString("status"));assertTrue(out.getBoolean("partial"));assertFalse(out.getBoolean("verifiedBusinessSuccess"));
    }
    @Test public void stableRequiresContinuousFreshReadAndNativeFingerprint()throws Exception{
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        JSONObject out=PhoneTreeQuery.await(new JSONObject().put("kind","stable").put("timeoutMs",1200).put("stableForMs",250).put("selector",new JSONObject()),null,()->{
            JSONObject t=tree("o"+reads.incrementAndGet());t.put("treeFingerprint",reads.get()<2?"changing":"stable");return t;
        },new RunControl());
        assertEquals("matched",out.getString("status"));assertEquals(3,reads.get());assertTrue(out.getLong("elapsedMs")>=500);
    }
    @Test public void pauseDoesNotWaitForResumeOrReadAgain()throws Exception{
        RunControl control=new RunControl();java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        long at=System.nanoTime();JSONObject out=PhoneTreeQuery.await(new JSONObject().put("kind","absent").put("timeoutMs",1000).put("selector",new JSONObject()),null,()->{reads.incrementAndGet();control.pause();return tree("o");},control);
        assertEquals("unavailable",out.getString("status"));assertEquals(1,reads.get());assertTrue((System.nanoTime()-at)/1000000<500);
    }
    @Test public void lateReadAndChangedParentAreDiscarded()throws Exception{
        JSONObject wait=new JSONObject().put("kind","present").put("timeoutMs",100).put("selector",new JSONObject().put("text","编辑"));
        JSONObject late=PhoneTreeQuery.await(wait,null,()->{Thread.sleep(120);return tree("late");},new RunControl());
        assertEquals("timed_out",late.getString("status"));assertFalse(late.has("observation"));
        wait.put("timeoutMs",500).put("observationId","o");wait.getJSONObject("selector").put("parentId","n3");
        JSONObject changed=tree("new");changed.getJSONArray("nodes").getJSONObject(3).put("text","different row");
        assertEquals("parent_changed",PhoneTreeQuery.await(wait,tree("o"),()->changed,new RunControl()).getString("reason"));
    }
    @Test public void missingTargetNeverSatisfiesStableEvenWhenSceneDoesNotChange()throws Exception{
        JSONObject out=PhoneTreeQuery.await(new JSONObject().put("kind","stable").put("timeoutMs",350).put("stableForMs",250)
                .put("selector",new JSONObject().put("text","nonexistent")),null,()->tree("scene"),new RunControl());
        assertEquals("timed_out",out.getString("status"));assertFalse(out.getBoolean("matched"));
    }
    @Test public void unrelatedWindowAnimationDoesNotResetStableTarget()throws Exception{
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        JSONObject out=PhoneTreeQuery.await(new JSONObject().put("kind","stable").put("timeoutMs",1000).put("stableForMs",250)
                .put("selector",new JSONObject().put("parentId","n3").put("text","编辑")).put("observationId","anchor"),tree("anchor"),()->{
            JSONObject t=tree("o"+reads.incrementAndGet());t.put("treeFingerprint","unrelated-animation-"+reads.get());
            t.getJSONArray("nodes").put(node("animation",null,"frame"+reads.get(),false).put("windowId",10));return t;
        },new RunControl());
        assertEquals("matched",out.getString("status"));assertEquals(2,reads.get());
    }
    @Test public void changingTargetContentRestartsStablePeriod()throws Exception{
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        JSONObject out=PhoneTreeQuery.await(new JSONObject().put("kind","stable").put("timeoutMs",1200).put("stableForMs",250)
                .put("selector",new JSONObject().put("resourceId","target")),null,()->{
            JSONObject t=tree("o"+reads.incrementAndGet());t.getJSONArray("nodes").getJSONObject(4).put("resourceId","target")
                    .put("identityFingerprint",reads.get()<2?"before":"changed");return t;
        },new RunControl());
        assertEquals("matched",out.getString("status"));assertEquals(3,reads.get());assertTrue(out.getLong("elapsedMs")>=500);
    }
    @Test public void clippedBodyDoesNotProveAbsenceOfUnshownText()throws Exception{
        JSONObject t=tree("o").put("treeCoverage",new JSONObject().put("bodyTruncated",true));
        JSONObject out=PhoneTreeQuery.await(new JSONObject().put("kind","absent").put("timeoutMs",100)
                .put("selector",new JSONObject().put("text","beyond_body_projection")),null,()->t,new RunControl());
        assertEquals("timed_out",out.getString("status"));assertTrue(out.getBoolean("partial"));
    }
    @Test public void malformedBudgetAndExtraAuthorityFieldsAreRejected()throws Exception{
        for(JSONObject args:new JSONObject[]{new JSONObject().put("kind","present").put("timeoutMs",6001).put("selector",new JSONObject()),
                new JSONObject().put("observationId","o").put("selector",new JSONObject().put("displayId",0))}){
            try{PhoneTreeQuery.validate(args.has("kind")?PhoneTreeQuery.WAIT:PhoneTreeQuery.QUERY,args);fail();}catch(IllegalArgumentException expected){}
        }
    }
}
