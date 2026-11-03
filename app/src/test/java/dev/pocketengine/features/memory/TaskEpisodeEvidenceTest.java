package dev.pocketengine.features.memory;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TaskEpisodeEvidenceTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static final class Facts {
        final String taskId=id(),runId=id(),operation=id();final JSONObject task,run;final JSONArray operations=new JSONArray();
        Facts()throws Exception{task=new JSONObject().put("id",taskId).put("runId",runId).put("coordinatorEpoch",2).put("actorId","owner").put("spaceId","private").put("kind","assistant").put("status","completed").put("resultRevision",9).put("goal","检查出行前的天气信息").put("summary","助手报告已经完成，但这里没有主人业务确认。");run=new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",2).put("status","finished").put("updatedAt",123);operations.put(new JSONObject().put("operationId",operation).put("runId",runId).put("tool","synthetic_read").put("state","receipt").put("receiptDigest","a".repeat(64)).put("createdAt",100).put("arguments",new JSONObject().put("credential","SHOULD_NOT_LEAVE_ARGUMENTS")).put("receipt",new JSONObject().put("ok",true).put("accepted",true).put("token","SHOULD_NOT_LEAVE_RECEIPT").put("status","completed")));}
        JSONObject event(JSONObject verification)throws Exception{return TaskEpisodeEvidence.project(task,run,operations,operations.length(),verification);}
        JSONObject verification(boolean completed)throws Exception{task.put("resultConfirmation",new JSONObject().put("completed",completed).put("confirmedResultRevision",9).put("resultReviewDigest","b".repeat(64)));return new JSONObject().put("runId",runId).put("coordinatorEpoch",2).put("seq",8).put("resultReviewDigest","b".repeat(64)).put("completed",completed);}
        JSONObject answer()throws Exception{return new JSONObject().put("constraints","仅能描述实际读到的信息").put("steps",new JSONArray().put(new JSONObject().put("operationId",operation).put("description","读取原信息"))).put("resultExplanation","结果尚需主人核对").put("lesson","不足的信息保持未知").put("reason","保留这次有限经过").put("applicability","只适用于相同输入条件").put("confidence",0.4);}
    }
    @Test public void toolSuccessAndCompletedTaskDoNotBecomeBusinessSuccess()throws Exception{
        Facts f=new Facts();JSONObject event=f.event(null),snapshot=TaskEpisodeEvidence.snapshot(event);assertEquals("unknown",snapshot.getJSONObject("outcome").getString("classification"));assertFalse(snapshot.getJSONObject("outcome").getBoolean("toolReceiptIsBusinessSuccess"));String text=TaskEpisodeEvidence.text(event);assertFalse(text.contains("SHOULD_NOT_LEAVE"));assertFalse(event.toString().contains("SHOULD_NOT_LEAVE"));assertFalse(snapshot.getBoolean("completeOriginalTrace"));assertTrue(snapshot.getBoolean("operationsComplete"));
    }
    @Test public void onlyMatchingOriginalOwnerConfirmationClassifiesTheResult()throws Exception{
        Facts f=new Facts();JSONObject verified=f.verification(true);assertEquals("owner_confirmed_completed",f.event(verified).getJSONObject("snapshot").getJSONObject("outcome").getString("classification"));
        for(String field:new String[]{"runId","coordinatorEpoch","resultReviewDigest"}){JSONObject changed=new JSONObject(verified.toString()).put(field,field.equals("coordinatorEpoch")?3:field.equals("runId")?id():"c".repeat(64));assertEquals("unknown",f.event(changed).getJSONObject("snapshot").getJSONObject("outcome").getString("classification"));}
        f.task.put("resultRevision",10);assertEquals("unknown",f.event(verified).getJSONObject("snapshot").getJSONObject("outcome").getString("classification"));f.task.put("resultRevision",9);assertEquals("owner_confirmed_not_completed",f.event(f.verification(false)).getJSONObject("snapshot").getJSONObject("outcome").getString("classification"));
    }
    @Test public void activeOrForeignTasksCannotYieldAnEpisodeSource()throws Exception{
        Facts f=new Facts();f.run.put("status","running");assertThrows(SecurityException.class,()->f.event(null));f.run.put("status","finished");f.task.put("origin",new JSONObject().put("kind","group"));assertThrows(SecurityException.class,()->f.event(null));f.task.remove("origin");f.task.put("runId",id());assertThrows(SecurityException.class,()->f.event(null));
    }
    @Test public void boundedCoverageAndUnicodeBoundariesRemainVisible()throws Exception{
        Facts f=new Facts();f.task.put("goal","中".repeat(3999)+"😀"+"后".repeat(30));JSONObject event=TaskEpisodeEvidence.project(f.task,f.run,f.operations,100,null),snapshot=event.getJSONObject("snapshot");assertFalse(snapshot.getBoolean("operationsComplete"));assertTrue(snapshot.getJSONObject("goal").getBoolean("truncated"));assertEquals(3999,snapshot.getJSONObject("goal").getString("text").length());assertTrue(TaskEpisodeEvidence.text(event).contains("1/100"));assertEquals(TaskEpisodeEvidence.text(event),TaskEpisodeEvidence.text(new JSONObject(event.toString())));
    }
    @Test public void episodeDraftKeepsNativeUnknownAndCannotChooseItsOwnOutcomeOrOperation()throws Exception{
        Facts f=new Facts();JSONObject snapshot=f.event(null).getJSONObject("snapshot");JSONObject draft=TaskEpisodeEvidence.learningDraft(f.answer(),snapshot);assertEquals("episodic",draft.getString("layer"));assertTrue(draft.getString("text").contains("结果分类（原生固定）：unknown"));assertFalse(draft.has("assertion"));assertTrue(draft.getString("text").contains(f.operation));
        JSONObject forged=f.answer().put("outcome","success");assertThrows(SecurityException.class,()->TaskEpisodeEvidence.learningDraft(forged,snapshot));JSONObject invented=f.answer();invented.getJSONArray("steps").getJSONObject(0).put("operationId",id());assertThrows(SecurityException.class,()->TaskEpisodeEvidence.learningDraft(invented,snapshot));
    }
    @Test public void changingCoverageOrVerificationFlagsIsRejected()throws Exception{
        Facts f=new Facts();JSONObject event=f.event(null);event.getJSONObject("snapshot").put("operationsComplete",false);assertThrows(SecurityException.class,()->TaskEpisodeEvidence.text(event));JSONObject promoted=f.event(null);promoted.getJSONObject("snapshot").getJSONObject("outcome").put("businessIndependentlyVerified",true);assertThrows(SecurityException.class,()->TaskEpisodeEvidence.snapshot(promoted));JSONObject swapped=f.event(null).put("runId",id());assertThrows(SecurityException.class,()->TaskEpisodeEvidence.snapshot(swapped));
    }
    @Test public void versionsRequireTheOriginalNativeObservation()throws Exception{
        Facts f=new Facts();JSONObject app=new JSONObject().put("packageName","example.synthetic").put("versionCode",5).put("lastUpdateTime",7).put("source","android_package_manager");JSONArray observations=new JSONArray().put(new JSONObject().put("runId",f.runId).put("epoch",2).put("app",app)).put(new JSONObject().put("runId",id()).put("epoch",2).put("app",new JSONObject(app.toString()).put("packageName","wrong.run"))).put(new JSONObject().put("runId",f.runId).put("epoch",2).put("app",new JSONObject(app.toString()).put("source","model_label")));f.task.put("nativeAppObservations",observations);JSONArray versions=f.event(null).getJSONObject("snapshot").getJSONArray("appVersions");assertEquals(1,versions.length());assertEquals("5@7",versions.getJSONObject(0).getString("appVersion"));assertFalse(versions.getJSONObject(0).getBoolean("methodVerified"));
    }
}
