package dev.pocketengine.features.memory;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure metadata/range checks; these fixtures are not model use, UI rendering or physical-I/O evidence. */
public final class TaskMemoryRawContractTest {
    private static final String TASK="original-task",RUN="original-run";
    private static JSONObject task()throws Exception{return new JSONObject().put("id",TASK).put("actorId","owner").put("spaceId","private").put("runId",RUN).put("events",new JSONArray());}
    private static JSONObject citation(JSONObject message,int offset,int max)throws Exception{return new JSONObject().put("sourceId",TaskMemoryTrace.rawMessageId(TASK,RUN,0)).put("revision",TaskMemoryTrace.rawFingerprint(message)).put("offset",offset).put("maxChars",max);}
    private static JSONObject page(JSONObject message)throws Exception{
        JSONObject source=TaskMemoryTrace.rawMessagePage(TASK,RUN,message,citation(message,0,64));
        return new JSONObject().put("taskId",TASK).put("runId",RUN).put("ownerId","owner").put("spaceId","private").put("section","messages").put("query","").put("status","current")
                .put("entries",new JSONArray().put(new JSONObject(source.toString()).put("kind","checkpoint_message"))).put("gaps",new JSONArray()).put("charsReturned",source.getString("body").length()).put("nextCursor","")
                .put("pageCoverage",new JSONObject().put("scannedRows",1).put("scannedUtf8Bytes",100).put("totalKnown",true).put("partialReason","").put("byteAccounting","materialized_sqlite_text_not_physical_io"))
                .put("executionAuthorized",false).put("executionIndependentlyVerified",false).put("completeOriginalTrace",false).put("formalMemorySaved",false);
    }
    @Test public void nativeMessageCitationAdapterPinsVersionAndExactUtf16OffsetUsingExistingTrace()throws Exception{
        JSONObject message=new JSONObject().put("role","user").put("content","保留原文🙂".repeat(200));String fullBody=message.toString(2);int start=fullBody.indexOf("保留原文");JSONObject original=citation(message,start,127);
        JSONObject options=TaskMemoryTrace.rawMessageTraceRequest(TASK,RUN,original,"");JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        JSONObject run=new JSONObject().put("taskId",TASK).put("runId",RUN).put("coordinatorEpoch",1).put("checkpoint",new JSONObject().put("messages",new JSONArray().put(message)));
        JSONObject lineage=new JSONObject().put("links",new JSONArray()).put("status","complete");
        JSONObject firstOptions=new JSONObject(options.toString());firstOptions.remove("taskId");firstOptions.remove("runId");
        JSONObject first=TaskMemoryTrace.page(scope,task(),run,Collections.emptyIterator(),lineage,firstOptions);
        JSONObject request=TaskMemoryTrace.rawMessageTraceRequest(TASK,RUN,original,first.getString("snapshot")),nextOptions=new JSONObject(request.toString());nextOptions.remove("taskId");nextOptions.remove("runId");
        JSONObject exact=TaskMemoryTrace.checkedPage(TaskMemoryTrace.page(scope,task(),run,Collections.emptyIterator(),lineage,nextOptions),request);JSONObject row=exact.getJSONArray("entries").getJSONObject(0);
        int end=start+127;if(Character.isHighSurrogate(fullBody.charAt(end-1)))end--;
        assertEquals(start,row.getJSONObject("readRange").getInt("offset"));assertEquals(original.getString("revision"),row.getString("revision"));assertEquals(fullBody.substring(start,end),row.getString("body"));
    }
    @Test public void sourceJumpExtractionUsesOnlyRealRawToolEnvelopeAndRejectsForeignOrAlteredRange()throws Exception{
        JSONObject message=new JSONObject().put("role","user").put("content","actual source");JSONObject receipt=page(message),task=task();
        JSONArray events=new JSONArray().put(new JSONObject().put("type","assistant").put("tool",TaskStoreMemoryReader.RAW_QUERY).put("receipt",receipt))
                .put(new JSONObject().put("type","tool").put("tool","workspace_read").put("receipt",receipt));task.put("events",events);assertEquals(0,TaskMemoryTrace.rawCitationJumps(task).length());
        events.put(new JSONObject().put("type","tool").put("tool",TaskStoreMemoryReader.RAW_QUERY).put("receipt",receipt));assertEquals(1,TaskMemoryTrace.rawCitationJumps(task).length());
        receipt.getJSONArray("entries").getJSONObject(0).getJSONObject("sourceJump").put("offset",1);assertEquals(0,TaskMemoryTrace.rawCitationJumps(task).length());
        receipt=page(message).put("runId","other-run");task.put("events",new JSONArray().put(new JSONObject().put("type","tool").put("tool",TaskStoreMemoryReader.RAW_QUERY).put("receipt",receipt)));assertEquals(0,TaskMemoryTrace.rawCitationJumps(task).length());
    }
    @Test public void boundedRawPageNeverAcceptsAnAggregateSnapshotOrFalseProofFlags()throws Exception{
        JSONObject message=new JSONObject().put("role","user").put("content","actual source"),options=new JSONObject().put("section","messages").put("query","");
        try{TaskMemoryTrace.checkedRawPage(page(message).put("snapshot","a".repeat(64)),TASK,RUN,options);fail("aggregate snapshot accepted");}catch(SecurityException expected){}
        try{TaskMemoryTrace.checkedRawPage(page(message).put("executionAuthorized",true),TASK,RUN,options);fail("reader granted execution");}catch(SecurityException expected){}
        try{TaskMemoryTrace.checkedRawPage(page(message).put("pageCoverage",new JSONObject().put("scannedRows",101)),TASK,RUN,options);fail("source row limit accepted");}catch(Exception expected){}
    }
    @Test public void staleExactSourceDropsBodyAndRangeAndHashesCannotBecomeFormalSourceRefs()throws Exception{
        JSONObject message=new JSONObject().put("role","user").put("content","old evidence"),citation=citation(message,0,64),source=TaskMemoryTrace.rawMessagePage(TASK,RUN,message,citation);
        JSONObject stale=TaskMemoryTrace.checkedRawSource(source.put("status","source_changed").put("reason","changed"),TASK,RUN,citation);assertFalse(stale.has("body"));assertFalse(stale.has("readRange"));
        try{SourceRef.fromJson(new JSONObject().put("kind","message").put("sourceId","some-source").put("revision",citation.getString("revision")).put("ownerId","owner").put("spaceId","private"));fail("content fingerprint became native revision");}catch(IllegalArgumentException expected){}
    }
    @Test public void delayedReadCannotPublishAfterTheOriginalSourceBarrierChanges()throws Exception{
        RunControl control=new RunControl();control.bindHostRun(TASK,RUN,1);AtomicLong revision=new AtomicLong();
        var binding=new TaskStoreMemoryReader.RawBinding(TASK,RUN,1,control,new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}},revision::get);
        CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl live){return new JSONObject();}};
        var port=new TaskStoreMemoryReader.RawCapability(existing,binding,new TaskStoreMemoryReader.RawAccess(){
            public JSONObject query(TaskStoreMemoryReader.RawBinding selected,JSONObject options)throws Exception{revision.incrementAndGet();return page(new JSONObject().put("role","user").put("content","LATE_BODY_51"));}
            public JSONObject read(TaskStoreMemoryReader.RawBinding selected,JSONObject source){throw new AssertionError();}
        });
        try{port.execute(TaskStoreMemoryReader.RAW_QUERY,new JSONObject().put("section","messages").put("query",""),control);fail("late body returned after source barrier");}catch(SecurityException expected){}finally{port.close();}
    }
}
