package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Broadcast contract tests against durable mailbox snapshots and controlled failure points.
 * These do not assert Android mounting, model quality or automatic wake. */
public final class TeamParentBroadcastTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject origin()throws Exception{return new JSONObject().put("parentTaskId",id()).put("parentRunId",id()).put("parentEpoch",1).put("preparedOperationId",id());}
    private static JSONObject member(String id)throws Exception{return new JSONObject().put("id",id).put("generation",0).put("identityRevision",1);}
    private static JSONObject request(JSONObject run)throws Exception{return new JSONObject().put("teamRunId",run.getString("id")).put("expectedTeamRevision",run.getLong("revision")).put("expectedGeneration",run.getInt("generation")).put("delivery","queue").put("messageId",id()).put("text","同一资料；没有任何新权限").put("recipients",new JSONArray().put(target("a")).put(target("b")));}
    private static JSONObject target(String id)throws Exception{return new JSONObject().put("memberId",id).put("expectedMemberGeneration",0).put("identityRevision",1);}
    private static JSONObject operation(JSONObject origin,JSONObject args,String state)throws Exception{return new JSONObject().put("operationId",origin.getString("preparedOperationId")).put("runId",origin.getString("parentRunId")).put("tool","teams_message").put("arguments",new JSONObject(args.toString())).put("state",state);}
    private static final class Mail implements TeamParentBroadcast.Mailbox {
        final File file;final JSONObject origin;int sends;String failMember="";boolean failAfterSave,revoked;
        Mail(JSONObject origin)throws Exception{this.origin=origin;file=new File(Files.createTempDirectory("broadcast-mail-").toFile(),"mail.json");save(new JSONObject().put("id",id()).put("revision",1).put("generation",0).put("members",new JSONArray().put(member("a")).put(member("b"))).put("inbox",new JSONArray()));}
        public void checkCurrent(){if(revoked)throw new SecurityException("original_source_revoked");}
        public JSONObject snapshot()throws Exception{return JsonFiles.read(file);}
        void save(JSONObject run)throws Exception{JsonFiles.write(file,run);}
        public JSONObject send(JSONObject input)throws Exception {
            sends++;if(input.getString("memberId").equals(failMember)&&!failAfterSave)throw new java.io.IOException("controlled original delivery failure");
            JSONObject run=snapshot();assertEquals(run.getLong("revision"),input.getLong("expectedRevision"));
            run.getJSONArray("inbox").put(new JSONObject().put("id",input.getString("messageId")).put("targetMemberId",input.getString("memberId")).put("senderParentTaskId",origin.getString("parentTaskId")).put("mode","queue").put("text",input.getString("text")).put("status","pending").put("consumedBy",new JSONArray()).put("deliveries",new JSONArray()));run.put("revision",run.getLong("revision")+1);save(run);
            if(input.getString("memberId").equals(failMember))throw new java.io.IOException("controlled lost receipt after durable enqueue");return run;
        }
    }
    @Test public void independentlyQueuedTargetsRetainFrozenAudienceAndExactIdsAcrossReopen()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot());JSONArray ops=new JSONArray().put(operation(source,args,"prepared"));
        JSONObject queued=TeamParentBroadcast.deliver(source,args,ops,mail);assertEquals("queued",queued.getString("state"));assertEquals(2,queued.getInt("acceptedCount"));assertEquals(0,queued.getInt("consumedCount"));assertFalse(queued.getBoolean("automaticWake"));assertFalse(queued.getBoolean("executionAuthorized"));
        JSONObject saved=mail.snapshot();saved.getJSONArray("members").put(member("late"));mail.save(saved);
        JSONObject replay=TeamParentBroadcast.deliver(source,args,ops,mail);assertEquals(2,mail.sends);assertEquals(2,mail.snapshot().getJSONArray("inbox").length());assertEquals(2,replay.getInt("targetCount"));assertEquals(queued.getJSONArray("deliveries").getJSONObject(0).getString("messageId"),replay.getJSONArray("deliveries").getJSONObject(0).getString("messageId"));
    }
    @Test public void expansionResumesOnlyMissingOriginalIdsAndNeverDuplicatesDurableEnqueue()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot());JSONObject partialArgs=new JSONObject(args.toString()).put("recipients",new JSONArray().put(target("a")));
        TeamParentBroadcast.deliver(source,partialArgs,new JSONArray(),mail);assertEquals(1,mail.sends);
        // The original prepared full-audience arguments survive interruption. Its exact
        // first child already exists, while the second has never been enqueued.
        JSONObject result=TeamParentBroadcast.deliver(source,args,new JSONArray().put(operation(source,args,"prepared")),mail);assertEquals("queued",result.getString("state"));assertEquals(2,mail.sends);assertEquals(2,mail.snapshot().getJSONArray("inbox").length());
    }
    @Test public void uncertainEarlierOperationOnlyObservesOriginalIdsAndDoesNotRedispatch()throws Exception {
        JSONObject first=origin();Mail mail=new Mail(first);JSONObject args=request(mail.snapshot());mail.failMember="b";
        JSONObject partial=TeamParentBroadcast.deliver(first,args,new JSONArray().put(operation(first,args,"prepared")),mail);assertEquals("unknown",partial.getString("status"));assertTrue(partial.isNull("accepted"));assertEquals(1,partial.getInt("acceptedCount"));
        JSONObject retry=new JSONObject(first.toString()).put("preparedOperationId",id());JSONObject observed=TeamParentBroadcast.deliver(retry,args,new JSONArray().put(operation(first,args,"unknown")).put(operation(retry,args,"prepared")),mail);
        assertTrue(observed.getBoolean("queryOnly"));assertEquals(2,mail.sends);assertEquals("unknown",observed.getJSONArray("deliveries").getJSONObject(1).getString("state"));
    }
    @Test public void aLostReceiptAfterPersistenceIsReconciledFromTheSameMailboxEntry()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot());mail.failMember="a";mail.failAfterSave=true;
        JSONObject result=TeamParentBroadcast.deliver(source,args,new JSONArray().put(operation(source,args,"prepared")),mail);assertEquals("queued",result.getString("state"));assertEquals(0,result.getInt("unknownCount"));assertEquals(2,mail.sends);assertEquals(2,mail.snapshot().getJSONArray("inbox").length());
    }
    @Test public void oneExitedOrChangedTargetDoesNotHideOtherOriginalDelivery()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot()),changed=mail.snapshot();changed.getJSONArray("members").getJSONObject(1).put("exited",true).put("identityRevision",2);mail.save(changed);
        JSONObject result=TeamParentBroadcast.deliver(source,args,new JSONArray(),mail);assertEquals("partially_queued",result.getString("state"));assertFalse(result.getBoolean("ok"));assertEquals(1,result.getInt("acceptedCount"));assertEquals("member_exited",result.getJSONArray("deliveries").getJSONObject(1).getString("reason"));assertEquals(1,mail.sends);
    }
    @Test public void consumptionNeedsActualMatchingDeliveryProofAndIsPerRecipient()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot());TeamParentBroadcast.deliver(source,args,new JSONArray(),mail);
        JSONObject run=mail.snapshot(),a=run.getJSONArray("inbox").getJSONObject(0),b=run.getJSONArray("inbox").getJSONObject(1);a.put("status","consumed").getJSONArray("consumedBy").put("a");a.getJSONArray("deliveries").put(new JSONObject().put("memberId","a").put("state","consumed"));b.put("status","consumed");mail.save(run);
        JSONObject result=TeamParentBroadcast.deliver(source,args,new JSONArray(),mail);assertEquals(1,result.getInt("consumedCount"));assertEquals("unknown",result.getString("state"));assertEquals("consumed",result.getJSONArray("deliveries").getJSONObject(0).getString("state"));assertEquals(2,mail.sends);
    }
    @Test public void reusedMessageCannotChangeTargetsTextOrParentExecution()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot());JSONArray ops=new JSONArray().put(operation(source,args,"receipt"));JSONObject changedText=new JSONObject(args.toString()).put("text","different");
        assertThrows(SecurityException.class,()->TeamParentBroadcast.deliver(source,changedText,ops,mail));assertEquals(0,mail.sends);
        JSONObject changed=new JSONObject(args.toString());changed.getJSONArray("recipients").getJSONObject(0).put("identityRevision",2);final JSONObject different=changed;
        assertThrows(SecurityException.class,()->TeamParentBroadcast.deliver(source,different,ops,mail));assertEquals(0,mail.sends);
        JSONObject nextRun=new JSONObject(source.toString()).put("parentRunId",id());assertThrows(SecurityException.class,()->TeamParentBroadcast.deliver(nextRun,args,ops,mail));
    }
    @Test public void schemaAndNativeValidationKeepDirectRepliesSeparateFromBroadcast()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot());TeamParentTools.validate("teams_message",args);
        assertThrows(SecurityException.class,()->TeamParentTools.validate("teams_message",new JSONObject(args.toString()).put("delivery","continue")));
        assertThrows(SecurityException.class,()->TeamParentTools.validate("teams_message",new JSONObject(args.toString()).put("questionId",id())));
        assertThrows(IllegalArgumentException.class,()->TeamParentTools.validate("teams_message",new JSONObject(args.toString()).put("recipients",new JSONArray())));
        JSONObject duplicate=new JSONObject(args.toString());duplicate.getJSONArray("recipients").put(target("a"));assertThrows(IllegalArgumentException.class,()->TeamParentTools.validate("teams_message",duplicate));
        JSONObject direct=new JSONObject(args.toString());direct.remove("recipients");assertThrows(IllegalArgumentException.class,()->TeamParentTools.validate("teams_message",direct));
        JSONObject schema=TeamParentTools.parameters("teams_message");assertFalse(schema.getBoolean("additionalProperties"));assertEquals(4,schema.getJSONObject("properties").getJSONObject("recipients").getInt("maxItems"));
    }
    @Test public void staleCreationOrRevokedParentNeverQueuesAnyMessage()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot()).put("expectedTeamRevision",2);assertThrows(IllegalStateException.class,()->TeamParentBroadcast.deliver(source,args,new JSONArray(),mail));assertEquals(0,mail.sends);
        args.put("expectedTeamRevision",1);mail.revoked=true;assertThrows(SecurityException.class,()->TeamParentBroadcast.deliver(source,args,new JSONArray(),mail));assertEquals(0,mail.sends);
    }
    @Test public void preexistingDirectMessageCannotImpersonateABroadcastChild()throws Exception {
        JSONObject source=origin();Mail mail=new Mail(source);JSONObject args=request(mail.snapshot());String child=TeamParentBroadcast.deliveryId(args.getString("teamRunId"),args.getString("messageId"),target("a"));
        JSONObject direct=new JSONObject().put("operationId",id()).put("runId",source.getString("parentRunId")).put("tool","teams_message").put("state","receipt").put("arguments",new JSONObject().put("messageId",child));
        JSONObject result=TeamParentBroadcast.deliver(source,args,new JSONArray().put(direct),mail);assertEquals("partially_queued",result.getString("state"));assertEquals("message_identity_collision",result.getJSONArray("deliveries").getJSONObject(0).getString("reason"));assertEquals(1,mail.sends);
    }
}
