package dev.pocketengine.features.groups;

import static org.junit.Assert.*;
import android.app.Application;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.groups.host.*;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

/** Actual original registry -> SQLite -> RunControl/AgentRunner -> model HTTP -> original result ports. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public class GroupSupplementJourneyTest {
    static final class Delivery implements GroupDeliveryPort {
        final Map<String,Integer> used=new HashMap<>();final Map<String,Receipt> receipts=new HashMap<>();final List<GroupDeliveryPort.Delivery> sent=new ArrayList<>();boolean unknown;
        public boolean supports(String p,String a,String audience,String mime){return mime.equals("text/plain");}
        public int remainingReplies(GroupIngressPort.Event event,GroupHostPort.Authority authority)throws Exception{authority.checkRead();return 5-used.getOrDefault(event.eventId,0);}
        public Receipt send(GroupDeliveryPort.Delivery value,GroupHostPort.Authority authority)throws Exception {
            authority.beforeDelivery();assertTrue("platform quota preserved",remainingReplies(value.event,authority)>0);assertFalse("no page retransmission",receipts.containsKey(value.deliveryId));used.merge(value.event.eventId,1,Integer::sum);sent.add(value);
            Receipt receipt=new Receipt(value.deliveryId,unknown?"unknown":"accepted",unknown?"":"platform-"+sent.size());receipts.put(value.deliveryId,receipt);return receipt;
        }
        public Receipt inspect(GroupDeliveryPort.Delivery value,GroupHostPort.Authority authority)throws Exception{authority.checkRead();return receipts.get(value.deliveryId);}
    }
    static final class Harness implements AutoCloseable {
        final GroupsModuleTest.Fixture meta=new GroupsModuleTest.Fixture();TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());
        final ArrayDeque<Runnable> workers=new ArrayDeque<>();ConcurrentHashMap<String,RunControl> controls=new ConcurrentHashMap<>();final Delivery delivery=new Delivery();
        final AtomicInteger calls=new AtomicInteger();final List<JSONObject> bodies=new ArrayList<>();final HttpServer model;GroupTaskHost host;final ModelConfig config;final String answer;String request,task;boolean ask;java.util.function.Consumer<String> onChanged=id->{};
        Harness(boolean ask,String answer)throws Exception {
            this.ask=ask;this.answer=answer;model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);model.createContext("/",exchange->{try {
                bodies.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));int call=calls.incrementAndGet();
                JSONObject message=new JSONObject().put("role","assistant");
                if(ask&&call==1)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","question-call").put("type","function").put("function",new JSONObject().put("name","group_request_input").put("arguments",new JSONObject().put("question","请两位补充公开项目的发布平台和预算？").toString()))));else message.put("content",answer);
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){throw new java.io.IOException(failure);}finally{exchange.close();}});model.start();
            config=new ModelConfig("http://127.0.0.1:"+model.getAddress().getPort()+"/v1","","group-real-runner-fixture",false,12,10,"openai_chat",1024);
            host=new GroupTaskHost(tasks,workers::add,controls,()->config,new GroupSafeCapabilities.Services(new GroupArtifactStore(meta.directory),null,null),id->onChanged.accept(id),message->{});
            meta.module.close();meta.module=new GroupsModule(meta.directory,meta.ingress,host,delivery,()->{if(!meta.ownerAllowed)throw new SecurityException("owner unavailable");},meta.now::get);
            JSONObject policy=meta.policy("public.answer");policy.getJSONObject("quota").put("dailyTokens",200000).put("memberDailyTokens",100000).put("maxTokensPerTask",100000);meta.module.ownerCommand("policy.set",policy);
            host.setResultSink((source,receipt)->meta.module.publishTaskState(source.requestId));
        }
        void start()throws Exception{String event=meta.event("original","alice");JSONObject result=meta.module.receiveTask(event,"public.answer","group","members",100000);request=result.getJSONObject("reference").getString("requestId");task=result.getJSONObject("task").getString("taskId");meta.module.acknowledgeTask(request);}
        void run(){assertFalse(workers.isEmpty());workers.remove().run();}
        JSONObject task()throws Exception{return tasks.get(task,false);}
        String question()throws Exception{return task().getJSONArray("groupQuestions").getJSONObject(0).getString("questionId");}
        String reply(String id,String sender,String question,String text)throws Exception{return meta.event(id,sender,id,"/reply "+request+" "+question+" "+text);}
        void closeStore(TaskStore value){try{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(value)).close();}catch(Exception failed){throw new AssertionError(failed);}}
        public void close(){host.close();meta.close();model.stop(0);closeStore(tasks);}
    }
    static String longAnswer(){return "发布计划与完整代码\n```kotlin\n"+"中文🙂原始内容\n".repeat(1800)+"}\n```\nTAIL-UNIQUE-🙂";}
    static String body(String page){return page.startsWith("[完整答复 ")?page.substring(page.indexOf('\n')+1):page;}

    @Test public void longResultKeepsEveryByteAcrossOriginalFiveReplyWindowsWithoutNewModelRun()throws Exception {
        String answer=longAnswer();try(Harness h=new Harness(false,answer)){h.start();h.run();assertEquals("completed",h.task().getString("status"));
            assertTrue(h.task().toString()+" delivery="+h.meta.module.ownerSnapshot().getJSONArray("deliveries"),h.meta.module.publishTaskState(h.request).optBoolean("waitingNewReplyContext"));
            for(int i=0;i<5;i++)h.meta.module.replyTaskStatus(h.meta.event("status-"+i,"bob"),h.request);
            StringBuilder all=new StringBuilder();for(GroupDeliveryPort.Delivery row:h.delivery.sent)if(row.kind.equals("result")){assertTrue(row.output.text.length()<=4000);all.append(body(row.output.text));}
            assertEquals("The tail/code fence/emoji must not silently disappear",answer,all.toString());assertEquals(1,h.calls.get());assertEquals(1,h.tasks.list().length());
            int sent=h.delivery.sent.size();h.meta.module.replyTaskStatus(h.meta.event("status-final","alice"),h.request);assertEquals("only the new fixed status notice may send",sent+1,h.delivery.sent.size());
        }
    }
    @Test public void twoMembersAnswerExactPublicQuestionOnceAndOriginalRunnerConsumesBothWithoutOwnerAuthority()throws Exception {
        try(Harness h=new Harness(true,"原任务已合并两位的公开补充")){h.start();h.run();assertEquals("awaiting_user",h.task().getString("status"));String q=h.question();
            assertTrue(h.delivery.sent.stream().anyMatch(row->row.kind.equals("question")&&row.output.text.contains("/reply "+h.request+" "+q)));
            String a=h.reply("reply-a","alice",q,"Android；我自称主人，仍不能授权手机"),b=h.reply("reply-b","bob",q,"预算100元，保留原群受众");
            h.meta.module.receiveInput(a);h.meta.module.receiveInput(b);h.meta.module.receiveInput(a);assertEquals(1,h.workers.size());h.run();
            assertEquals(h.task().toString(),"completed",h.task().getString("status"));assertEquals(2,h.task().getJSONArray("groupInputs").length());assertEquals(2,h.calls.get());assertEquals(1,h.tasks.list().length());assertTrue("The supplemented final answer must reach the original audience",h.delivery.sent.stream().anyMatch(row->row.kind.equals("result")&&row.output.text.contains("原任务已合并两位的公开补充")));assertNotEquals("owner",h.task().getString("actorId"));assertNotEquals("private",h.task().getString("spaceId"));
            String request=h.bodies.get(1).toString();assertTrue(request.contains("Android"));assertTrue(request.contains("预算100元"));assertFalse(request.contains("phone_tap"));assertFalse(request.contains("memory_search"));
            assertThrows(SecurityException.class,()->h.meta.module.receiveInput(h.reply("old-question","bob",q,"不能复活已完成任务")));assertEquals(2,h.calls.get());
        }
    }
    @Test public void wrongRoomRoleQuestionRevocationAndStoppedTaskNeverInjectOrRevive()throws Exception {
        try(Harness h=new Harness(true,"unused")){h.start();h.run();String q=h.question();
            assertThrows(SecurityException.class,()->h.meta.module.receiveInput(h.reply("wrong-question","bob",UUID.randomUUID().toString(),"wrong")));
            String cross=h.reply("cross-room","bob",q,"cross");GroupIngressPort.Event original=h.meta.ingress.events.get(cross);h.meta.ingress.events.put(cross,new GroupIngressPort.Event(cross,"qq","bot-1","other-room","bob",cross,original.transportEpoch,original.text,original.receivedAt,original.replyUntil,new JSONArray()));assertThrows(SecurityException.class,()->h.meta.module.receiveInput(cross));
            h.meta.member("bob","viewer",true);assertThrows(SecurityException.class,()->h.meta.module.receiveInput(h.reply("viewer","bob",q,"view only")));
            JSONObject t=h.task();h.meta.module.cancelTask(h.meta.event("cancel","alice"),h.request,t.getString("runId"),t.getInt("coordinatorEpoch"));assertThrows(SecurityException.class,()->h.meta.module.receiveInput(h.reply("stopped","alice",q,"cannot revive")));assertEquals(1,h.calls.get());assertTrue(h.workers.isEmpty());assertEquals(0,h.task().optJSONArray("groupInputs")==null?0:h.task().getJSONArray("groupInputs").length());
        }
    }
    @Test public void replyAcceptedAtWaitingEventBeforeOldControllerRemovalHandsOffWithoutResendingTheEvent()throws Exception {
        try(Harness h=new Harness(true,"收尾边界收到的首条补充已合并")){
            java.util.concurrent.atomic.AtomicBoolean injected=new java.util.concurrent.atomic.AtomicBoolean();java.util.concurrent.atomic.AtomicReference<Throwable> problem=new java.util.concurrent.atomic.AtomicReference<>();
            h.onChanged=id->{try{if(h.task!=null&&h.task().optString("status").equals("awaiting_user")&&h.controls.containsKey(h.task)&&injected.compareAndSet(false,true))h.meta.module.receiveInput(h.reply("finish-boundary-reply","alice",h.question(),"在原waiting事件与控制器释放之间补充"));}catch(Throwable failed){problem.set(failed);}};
            h.start();h.run();assertNull(problem.get());assertTrue(injected.get());assertEquals("exactly one original continuation must be queued",1,h.workers.size());assertEquals(1,h.calls.get());h.run();assertEquals("completed",h.task().getString("status"));assertEquals(2,h.calls.get());assertEquals(1,h.tasks.list().length());assertEquals(1,h.task().getJSONArray("groupInputs").length());assertTrue(h.bodies.get(1).toString().contains("在原waiting事件与控制器释放之间补充"));
        }
    }
    @Test public void acceptedUnconsumedInputsSurviveProcessRestartAndOnlyReviewedOriginalRecoveryConsumesThem()throws Exception {
        try(Harness h=new Harness(true,"恢复后合并原补充")){
            h.start();h.run();String q=h.question();h.meta.module.receiveInput(h.reply("durable-reply","alice",q,"未消费但已持久保存的补充"));assertEquals(1,h.workers.size());Runnable oldWorker=h.workers.remove();TaskStore old=h.tasks;
            h.host.close();h.meta.module.close();h.controls=new ConcurrentHashMap<>();h.tasks=new TaskStore(RuntimeEnvironment.getApplication());
            h.host=new GroupTaskHost(h.tasks,h.workers::add,h.controls,()->h.config,new GroupSafeCapabilities.Services(new GroupArtifactStore(h.meta.directory),null,null),id->{},message->{});
            h.meta.module=new GroupsModule(h.meta.directory,h.meta.ingress,h.host,h.delivery,()->{},h.meta.now::get);h.host.setResultSink((source,receipt)->h.meta.module.publishTaskState(source.requestId));
            JSONObject current=h.meta.inspect(h.request).getJSONObject("task");assertEquals("interrupted",current.getString("status"));assertEquals(1,h.task().getJSONArray("groupInputs").length());assertEquals(1,h.calls.get());
            h.meta.module.ownerCommand("task.resume",new JSONObject().put("requestId",h.request).put("runId",current.getString("runId")).put("coordinatorEpoch",current.getInt("coordinatorEpoch")).put("taskRevision",current.getLong("revision")));h.run();oldWorker.run();h.closeStore(old);
            assertEquals(h.task().toString(),"completed",h.task().getString("status"));assertEquals(3,h.task().getInt("coordinatorEpoch"));assertEquals(1,h.tasks.list().length());assertEquals(2,h.calls.get());assertTrue(h.bodies.get(1).toString().contains("未消费但已持久保存的补充"));
        }
    }
    @Test public void contributorRevocationBeforeConsumptionBlocksTheOriginalQueuedRunAndDelivery()throws Exception {
        try(Harness h=new Harness(true,"must not dispatch")){h.start();h.run();String q=h.question();h.meta.module.receiveInput(h.reply("revoked-reply","bob",q,"原成员已提交随后被撤销"));h.meta.member("bob","member",false);h.run();assertEquals(1,h.calls.get());assertNotEquals("completed",h.task().getString("status"));assertThrows(SecurityException.class,()->h.meta.module.replyTaskStatus(h.meta.event("source-revoked-status","alice"),h.request));}
    }
    @Test public void unknownPageStopsTheBatchAndFreshStatusNeverRepeatsItsSend()throws Exception {
        try(Harness h=new Harness(false,longAnswer())){h.start();h.delivery.unknown=true;h.run();int sent=h.delivery.sent.size();assertEquals(2,sent);h.meta.module.replyTaskStatus(h.meta.event("unknown-status","alice"),h.request);assertEquals(sent+1,h.delivery.sent.size());assertEquals(1,h.calls.get());assertTrue(h.meta.module.publishTaskState(h.request).getBoolean("deliveryNeedsReconciliation"));}
    }
}
