package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Default native research entry, real Settings routes/Runner/SQLite and loopback HTTP.
 * This bounded slice intentionally refuses completion-unknown replay (MAG-CP-V08
 * pure-inference retry remains outside its coverage). Billing unknown stays charged. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TeamMemberFallbackNativeJourneyTest {
    private static TeamResearchAgentNativeJourneyTest.Fixture fixture()throws Exception {
        return new TeamResearchAgentNativeJourneyTest.Fixture(true,false,false,false,false,true,false,false,true);
    }
    private static void budget(TeamResearchAgentNativeJourneyTest.Fixture f,int calls)throws Exception {
        f.waitIdle();var page=f.research.openBudget(f.coordinator,f.run);page.setForeground(true);
        try{var review=page.review(calls,8);page.approve(review,review.view().getString("digest"));}finally{page.close();}
    }
    private static SQLiteDatabase sql(TaskStore tasks)throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private static JSONObject document(TeamResearchAgentNativeJourneyTest.Fixture f,String task)throws Exception {
        synchronized(f.tasks){try(Cursor row=sql(f.tasks).rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{task})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
    }
    private static JSONObject pool(TeamResearchAgentNativeJourneyTest.Fixture f)throws Exception {
        return document(f,f.tasks.teamBudgetSnapshot(f.run).getString("parentTaskId")).getJSONObject("teamReservationPool");
    }
    private static JSONObject reservation(JSONObject pool,String id)throws Exception {
        JSONArray rows=pool.getJSONArray("reservations");JSONObject found=null;
        for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("reservationId"))){assertNull(found);found=rows.getJSONObject(i);}assertNotNull(found);return found;
    }
    private static JSONObject receipt(JSONObject usage,String call)throws Exception {
        JSONArray rows=usage.getJSONArray("receipts");for(int i=0;i<rows.length();i++)if(call.equals(rows.getJSONObject(i).getString("callId")))return rows.getJSONObject(i);throw new AssertionError("Missing original physical receipt "+call);
    }
    private static JSONObject memberLogical(TeamResearchAgentNativeJourneyTest.Fixture f,int attempts)throws Exception {
        JSONObject child=f.child(),usage=f.tasks.inferenceUsage(child.getString("id"));JSONArray rows=usage.getJSONArray("logicalRequests");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(child.getString("runId").equals(row.getString("runId"))&&"MEMBER".equals(row.getJSONObject("routeSnapshot").getString("contextPurpose"))&&row.getJSONObject("control").getJSONArray("attempts").length()==attempts)return row;}
        throw new AssertionError("Missing original member logical with "+attempts+" attempts: "+rows);
    }
    private static void closeCoordinator(TeamCoordinator coordinator)throws Exception {
        coordinator.close();for(String name:new String[]{"coordinator","members"}){var field=TeamCoordinator.class.getDeclaredField(name);field.setAccessible(true);assertTrue(((ExecutorService)field.get(coordinator)).awaitTermination(5,TimeUnit.SECONDS));}
    }
    @FunctionalInterface private interface BeforeFailure {void run()throws Exception;}
    private enum Fault { HTTP, DISCONNECT, PARTIAL }
    /** Replaces only the existing fixture peer after the native child reaches its
     * original budget wait. No authority, journal, receipt or ledger is mocked. */
    private static final class Peer {
        final AtomicInteger first=new AtomicInteger(),second=new AtomicInteger();
        int failAt=1,status=503,secondStatus=200;String code="overloaded";Fault fault=Fault.HTTP;
        BeforeFailure before=()->{};boolean allowClosed;
        Peer(TeamResearchAgentNativeJourneyTest.Fixture f){
            f.server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String model=request.getString("model");
                f.http.incrementAndGet();f.requestModels.add(model);
                if("research-member".equals(model)&&first.incrementAndGet()==failAt){
                    before.run();
                    if(fault==Fault.DISCONNECT)return;
                    if(fault==Fault.PARTIAL){assertTrue("exercise original streaming path",request.optBoolean("stream"));send(exchange,200,"text/event-stream","data: {\"id\":\"partial\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"original partial output\"},\"finish_reason\":null}]}\n\n");return;}
                    send(exchange,status,"application/json",new JSONObject().put("error",new JSONObject().put("code",code)).toString());return;
                }
                if("research-fallback".equals(model)){second.incrementAndGet();if(secondStatus!=200){send(exchange,secondStatus,"application/json","{\"error\":{\"code\":\"overloaded\"}}");return;}}
                JSONObject answer=f.answer(request);String body=new JSONObject().put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",15).put("completion_tokens",5).put("total_tokens",20)).toString();
                send(exchange,200,"application/json",body);
            }catch(Throwable failure){if(!(allowClosed&&failure instanceof java.io.IOException))f.failure.compareAndSet(null,failure);}finally{exchange.close();}});
        }
    }
    private static void send(HttpServer.Exchange exchange,int status,String type,String body)throws Exception {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type",type);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    @Test public void definite503FallsBackOnceInOriginalLogicalAndRetainsUnknownFirstBill()throws Exception {
        try(var f=fixture()){
            budget(f,12);JSONObject initial=f.tasks.teamBudgetSnapshot(f.run);Peer peer=new Peer(f);f.resumeNative();f.waitIdle();
            assertEquals("completed",f.child().getString("status"));assertEquals(1,peer.second.get());assertFalse(f.requestModels.contains("wrong-global"));
            JSONObject logical=memberLogical(f,2),usage=f.tasks.inferenceUsage(f.child().getString("id")),pool=pool(f);
            assertEquals("SUCCEEDED",logical.getString("state"));assertTrue(logical.getBoolean("semanticCommitted"));assertEquals(2,logical.getJSONObject("control").getInt("maxAttempts"));
            JSONArray attempts=logical.getJSONObject("control").getJSONArray("attempts");JSONObject a=attempts.getJSONObject(0),b=attempts.getJSONObject(1);
            assertNotEquals(a.getString("callId"),b.getString("callId"));assertEquals(b.getString("callId"),logical.getString("chosenCallId"));assertEquals("member-fallback",b.getString("candidateId"));
            assertNotEquals(a.getString("parentReservationId"),b.getString("parentReservationId"));
            for(int i=0;i<attempts.length();i++){
                JSONObject attempt=attempts.getJSONObject(i),actual=receipt(usage,attempt.getString("callId")),route=actual.getJSONObject("route"),reserved=reservation(pool,attempt.getString("parentReservationId"));
                assertEquals(logical.getString("logicalRequestId"),route.getString("logicalRequestId"));assertEquals(logical.getLong("deadlineAt"),route.getLong("deadlineAt"));
                assertEquals(logical.getString("logicalRequestId"),reserved.getString("logicalRequestId"));assertEquals(i+1,reserved.getInt("attemptNo"));assertEquals(attempt.getString("candidateId"),reserved.getString("candidateId"));
                assertEquals(attempt.getString("callId"),reserved.getString("plannedCallId"));assertEquals(1,reserved.getJSONArray("inferenceCallIds").length());assertEquals(1,reserved.getJSONArray("actualReceiptRefs").length());
                assertEquals(logical.getString("taskId"),reserved.getString("childTaskId"));assertEquals(logical.getString("runId"),reserved.getString("childRunId"));assertEquals(logical.getInt("epoch"),reserved.getInt("childEpoch"));
                assertEquals(initial.getString("parentTaskId"),reserved.getString("parentTaskId"));assertEquals(initial.getString("parentRunId"),reserved.getString("parentRunId"));
            }
            JSONObject first=reservation(pool,a.getString("parentReservationId"));assertEquals("unknown",first.getString("state"));assertEquals("FAILED",first.getString("outcome"));assertFalse(first.getJSONObject("actualUsage").getBoolean("reported"));
            JSONObject finalBudget=f.tasks.teamBudgetSnapshot(f.run);assertEquals(12,finalBudget.getInt("modelRequestLimit"));assertEquals(f.http.get(),finalBudget.getInt("modelRequestsReservedOrUsed"));assertFalse(usage.toString().contains("synthetic-native-"));
        }
    }
    @Test public void secondDefiniteFailureCannotStartThirdPhysicalAttempt()throws Exception {
        try(var f=fixture()){
            budget(f,12);Peer peer=new Peer(f);peer.secondStatus=503;int before=f.http.get();f.resumeNative();f.waitIdle();
            assertEquals(1,peer.first.get());assertEquals(1,peer.second.get());assertEquals(before+2,f.http.get());assertNotEquals("completed",f.child().getString("status"));
            assertEquals("FAILED",memberLogical(f,2).getString("state"));assertEquals(f.http.get(),f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));
        }
    }
    @Test public void authenticationQuotaPolicyAndRateLimitNeverChangeCandidate()throws Exception {
        for(int status:new int[]{401,403,402,429,500,503})try(var f=fixture()){
            budget(f,12);Peer peer=new Peer(f);peer.status=status;peer.code=status==500?"insufficient_quota":status==503?"policy_violation":status==429?"rate_limit_exceeded":"";
            int before=f.http.get();f.resumeNative();f.waitIdle();assertEquals(1,peer.first.get());assertEquals(0,peer.second.get());assertEquals(before+1,f.http.get());
            assertNotEquals("completed",f.child().getString("status"));assertEquals(1,memberLogical(f,1).getJSONObject("control").getJSONArray("attempts").length());
        }
    }
    @Test public void completionUnknownAndSemanticPartialDoNotReplayOnFallbackOrColdOpen()throws Exception {
        for(Fault fault:new Fault[]{Fault.DISCONNECT,Fault.PARTIAL})try(var f=fixture()){
            budget(f,12);Peer peer=new Peer(f);peer.fault=fault;int before=f.http.get();f.resumeNative();f.waitIdle();
            assertEquals(1,peer.first.get());assertEquals(0,peer.second.get());assertEquals(before+1,f.http.get());JSONObject logical=memberLogical(f,1);
            assertEquals(fault==Fault.PARTIAL?"PARTIAL":"UNKNOWN",logical.getString("state"));assertEquals(fault==Fault.PARTIAL,logical.getBoolean("semanticCommitted"));
            int used=f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed");closeCoordinator(f.coordinator);f.coordinator=null;f.openCoordinator(false);f.waitIdle();
            assertEquals("cold inspection does not replay the original unknown request",before+1,f.http.get());assertEquals(used,f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));
        }
    }
    @Test public void originalLastParentUnitStopsFallbackBeforeHttpWithoutRefill()throws Exception {
        try(var f=fixture()){
            f.waitIdle();JSONObject initial=f.tasks.teamBudgetSnapshot(f.run);int limit=initial.getInt("modelRequestLimit"),remaining=initial.getInt("remainingModelRequests");assertTrue(remaining>0);
            budget(f,limit);Peer peer=new Peer(f);peer.failAt=remaining;f.resumeNative();f.waitIdle();
            assertEquals(remaining,peer.first.get());assertEquals(0,peer.second.get());JSONObject after=f.tasks.teamBudgetSnapshot(f.run);
            assertEquals(limit,after.getInt("modelRequestLimit"));assertEquals(0,after.getInt("remainingModelRequests"));assertEquals(f.http.get(),after.getInt("modelRequestsReservedOrUsed"));assertNotEquals("completed",f.child().getString("status"));
            assertEquals(1,memberLogical(f,1).getJSONObject("control").getJSONArray("attempts").length());
        }
    }
    @Test public void twoOriginalConcurrentMembersCompeteForExactlyOneRemainingFallbackUnit()throws Exception {
        CountDownLatch opened=new CountDownLatch(2),failedTogether=new CountDownLatch(2);AtomicInteger fallback=new AtomicInteger();
        var memberCalls=new java.util.concurrent.ConcurrentHashMap<String,AtomicInteger>();memberCalls.put("a",new AtomicInteger());memberCalls.put("b",new AtomicInteger());
        try(var f=new TeamResearchAgentNativeJourneyTest.Fixture(false,false,false,false,false,true,false,false,true,(nativeFixture,definition)->{
            JSONObject reference=dev.pocketengine.runtime.models.ModelTargetRef.group(nativeFixture.nativeMemberGroup.getString("groupId"),nativeFixture.nativeMemberGroup.getLong("revision")).toJson();
            definition.getJSONArray("members").getJSONObject(0).put("role","RACE_MEMBER_A");
            definition.getJSONArray("members").put(new JSONObject().put("id","b").put("name","并发研究成员B").put("role","RACE_MEMBER_B").put("model","research-member").put("modelTarget",reference));
            nativeFixture.server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String model=request.getString("model"),system=request.getJSONArray("messages").getJSONObject(0).getString("content");
                String member=system.contains("RACE_MEMBER_A")?"a":system.contains("RACE_MEMBER_B")?"b":"";assertFalse("only actual member requests are admitted in this exhausted pool",member.isEmpty());
                nativeFixture.http.incrementAndGet();nativeFixture.requestModels.add(model);JSONObject answer;
                if("research-member".equals(model)){
                    int call=memberCalls.get(member).incrementAndGet();
                    if(call==1){opened.countDown();assertTrue("both original native children must exist before any tool spends budget",opened.await(10,TimeUnit.SECONDS));}
                    if(call==3){
                        failedTogether.countDown();assertTrue("both physical failures must race for the same last parent unit",failedTogether.await(10,TimeUnit.SECONDS));
                        send(exchange,503,"application/json","{\"error\":{\"code\":\"overloaded\"}}");return;
                    }
                    assertTrue("no new MAIN request may evade the failed logical",call<=2);
                    String tool=call==1?"team_materials":"skills_list";
                    answer=new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments","{}"))));
                }else{
                    assertEquals("research-fallback",model);fallback.incrementAndGet();answer=new JSONObject().put("role","assistant").put("content","此成员只核对本轮空显选目录，资料不足保留未知。");
                }
                send(exchange,200,"application/json",new JSONObject().put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",15).put("completion_tokens",5).put("total_tokens",20)).toString());
            }catch(Throwable failure){nativeFixture.failure.compareAndSet(null,failure);}finally{exchange.close();}});
        })){
            f.waitIdle();assertEquals(0,opened.getCount());assertEquals(0,failedTogether.getCount());assertEquals(3,memberCalls.get("a").get());assertEquals(3,memberCalls.get("b").get());assertEquals(1,fallback.get());assertEquals(7,f.http.get());
            JSONObject budget=f.tasks.teamBudgetSnapshot(f.run),pool=pool(f);assertEquals(7,budget.getInt("modelRequestLimit"));assertEquals(7,budget.getInt("modelRequestsReservedOrUsed"));assertEquals(0,budget.getInt("remainingModelRequests"));assertEquals(4,budget.getInt("toolOperationsReservedOrUsed"));
            int logicals=0,retried=0,failedReceipts=0;String winner="";
            for(String member:new String[]{"a","b"}){
                JSONObject child=f.child(member),usage=f.tasks.inferenceUsage(child.getString("id"));JSONArray requests=usage.getJSONArray("logicalRequests");logicals+=requests.length();
                for(int i=0;i<requests.length();i++){JSONObject logical=requests.getJSONObject(i);JSONArray attempts=logical.getJSONObject("control").getJSONArray("attempts");
                    if(attempts.length()==2){retried++;winner=member;assertEquals("SUCCEEDED",logical.getString("state"));assertEquals(logical.getString("chosenCallId"),attempts.getJSONObject(1).getString("callId"));}
                    for(int n=0;n<attempts.length();n++){
                        JSONObject attempt=attempts.getJSONObject(n),actual=receipt(usage,attempt.getString("callId")),reserved=reservation(pool,attempt.getString("parentReservationId"));
                        assertEquals(member,reserved.getString("memberId"));assertEquals(child.getString("id"),reserved.getString("childTaskId"));assertEquals(logical.getString("runId"),reserved.getString("childRunId"));assertEquals(logical.getString("logicalRequestId"),reserved.getString("logicalRequestId"));
                        assertEquals(budget.getString("parentTaskId"),reserved.getString("parentTaskId"));assertEquals(budget.getString("parentRunId"),reserved.getString("parentRunId"));assertEquals(logical.getLong("deadlineAt"),actual.getJSONObject("route").getLong("deadlineAt"));
                        if(actual.optJSONObject("failure")!=null&&actual.getJSONObject("failure").optInt("httpStatus")==503){failedReceipts++;assertEquals("unknown",reserved.getString("state"));assertFalse(reserved.getJSONObject("actualUsage").getBoolean("reported"));}
                    }
                }
            }
            assertEquals(6,logicals);assertEquals(1,retried);assertEquals(2,failedReceipts);assertEquals("completed",f.child(winner).getString("status"));assertNotEquals("completed",f.child(winner.equals("a")?"b":"a").getString("status"));
        }
    }
    @Test public void nativeGroupRevocationAfterFirstResponseReleasesOnlyUnsentFallback()throws Exception {
        try(var f=fixture()){
            budget(f,12);Peer peer=new Peer(f);peer.before=()->f.nativeSettings.saveModelGroup(f.nativeMemberGroup.getString("groupId"),f.nativeMemberGroup.getLong("revision"),"Revoked original revision",f.nativeMemberGroup);
            int before=f.http.get();f.resumeNative();f.waitIdle();assertEquals(before+1,f.http.get());assertEquals(0,peer.second.get());
            JSONObject logical=memberLogical(f,1),pool=pool(f);JSONArray attempts=logical.getJSONObject("control").getJSONArray("attempts");JSONObject first=reservation(pool,attempts.getJSONObject(0).getString("parentReservationId"));assertEquals("unknown",first.getString("state"));
            int unsent=0;JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(logical.getString("logicalRequestId").equals(row.optString("logicalRequestId"))&&row.optInt("attemptNo")==2){unsent++;assertEquals("released_unstarted",row.getString("state"));assertEquals(0,row.getJSONArray("inferenceCallIds").length());assertFalse(row.has("plannedCallId"));}}
            assertEquals(1,unsent);assertEquals(f.http.get(),f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));
        }
    }
    @Test public void cancelAtOriginalFirstResponseCannotAuthorizeFallback()throws Exception {
        try(var f=fixture()){
            budget(f,12);Peer peer=new Peer(f);peer.allowClosed=true;peer.before=()->{var control=f.research.currentControl(f.child().getString("id"));assertNotNull(control);control.cancel();};
            int before=f.http.get();f.resumeNative();f.waitIdle();assertEquals(before+1,f.http.get());assertEquals(0,peer.second.get());assertNotEquals("completed",f.child().getString("status"));
            JSONObject logical=memberLogical(f,1);assertEquals("CANCELLED",logical.getString("state"));assertEquals(f.http.get(),f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));
        }
    }
    @Test public void failedFallbackReservationTransactionCannotSendOrKeepRecoveryDebit()throws Exception {
        try(var f=fixture()){
            budget(f,12);JSONObject initial=f.tasks.teamBudgetSnapshot(f.run);String owner=initial.getString("parentTaskId");
            // Fail the original document update that would retain the second parent
            // reservation. SQLite must roll back that unit and modelRecoveryBudget.
            sql(f.tasks).execSQL("CREATE TRIGGER reject_team_fallback BEFORE UPDATE OF document ON tasks WHEN NEW.task_id='"+owner+"' AND NEW.document LIKE '%\"attemptNo\":2%' BEGIN SELECT RAISE(ABORT,'fixture fallback reservation failure'); END");
            Peer peer=new Peer(f);int before=f.http.get();f.resumeNative();f.waitIdle();assertEquals(before+1,f.http.get());assertEquals(0,peer.second.get());
            JSONObject logical=memberLogical(f,1),raw=document(f,f.child().getString("id")),recovery=raw.optJSONObject("modelRecoveryBudget");
            assertTrue(recovery==null||recovery.optInt("retries")==0);JSONArray rows=pool(f).getJSONArray("reservations");for(int i=0;i<rows.length();i++)assertFalse(logical.getString("logicalRequestId").equals(rows.getJSONObject(i).optString("logicalRequestId"))&&rows.getJSONObject(i).optInt("attemptNo")==2);
            assertEquals(f.http.get(),f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));sql(f.tasks).execSQL("DROP TRIGGER reject_team_fallback");
        }
    }
}
