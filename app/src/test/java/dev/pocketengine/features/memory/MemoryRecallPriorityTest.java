package dev.pocketengine.features.memory;

import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Original correct-behavior counterexamples; preserve these unchanged across RED and GREEN. */
public final class MemoryRecallPriorityTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Test public void sixteenCoreRecordsCannotCrowdOutTheCurrentTaskHit()throws Exception{
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject scope=scope();
        for(int i=0;i<16;i++)book.save(scope,new JSONObject().put("text","Stable owner preference number "+i).put("core",true),i);
        JSONObject relevant=book.save(scope,new JSONObject().put("text","DELTA_TICKET original task instruction"),30);
        JSONObject snapshot=book.contextSnapshot(scope,"DELTA_TICKET",16,12000);JSONArray selected=snapshot.getJSONArray("records");
        assertTrue("all16 core entries consumed the task-reference budget",selected.toString().contains(relevant.getString("id")));
        assertTrue(snapshot.getString("context").contains("DELTA_TICKET"));assertTrue(selected.length()<=16);
    }
    @Test public void longBackgroundCannotDiscardTheNewestTaskRequirement()throws Exception{
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject scope=scope();
        JSONObject relevant=book.save(scope,new JSONObject().put("text","DELTA_TICKET original instruction"),1);
        JSONObject snapshot=book.contextSnapshot(scope,"irrelevant_old_background ".repeat(70)+"\nDELTA_TICKET",16,12000);
        assertTrue("the first500 old characters hid the newest requirement",snapshot.getJSONArray("records").toString().contains(relevant.getString("id")));
        assertTrue(snapshot.getBoolean("queryTruncated"));
    }
}
