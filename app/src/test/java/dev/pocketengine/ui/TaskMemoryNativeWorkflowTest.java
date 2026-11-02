package dev.pocketengine.ui;

import android.os.Looper;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real Application/Controller/TaskStore/MemoryBook/Runner/Gateway loopback requests, no provided fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskMemoryNativeWorkflowTest {
    private static final String OLD="B1_OLD_REFERENCE_741",UPDATED="B1_CORRECTED_REFERENCE_952",KEPT="B1_REPLACEMENT_REFERENCE_863";
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("TaskMemoryNativeKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    interface Check{boolean done()throws Exception;}
    static void await(Check check)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!check.done()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("native task memory condition missing",check.done());}
    static JSONObject j(Object... fields)throws Exception{JSONObject value=new JSONObject();for(int i=0;i<fields.length;i+=2)value.put((String)fields[i],fields[i+1]);return value;}
    static JSONObject scope()throws Exception{return j("ownerId","owner","spaceId","private");}
    static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();final MemoryModule memory=(MemoryModule)app.features.module("memory");final AssistantModule assistant=(AssistantModule)app.features.module("assistant");
        final PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);final HttpServer server;
        final CopyOnWriteArrayList<JSONObject> requests=new CopyOnWriteArrayList<>();final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);volatile int blockCall=-1;volatile boolean fail;volatile String answer="本轮参考："+OLD;
        final String sourceTask;final JSONObject old;final SourceRef source;String conversation;
        Fixture()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{
                int call=calls.incrementAndGet();requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                if(call==blockCall){blocked.countDown();release.await(10,TimeUnit.SECONDS);}
                byte[] bytes=j("choices",new JSONArray().put(j("message",j("role","assistant","content",answer),"finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(fail?503:200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception error){throw new IllegalStateException(error);}finally{exchange.close();}});server.start();
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("provider","openai_chat").putString("model","native-memory-flow").putInt("maxOutputTokens",1024).putInt("maxSteps",8).putBoolean("sendScreenshots",false).commit();
            sourceTask=app.tasks.createCapabilityTask("早期项目验收原任务证据","").getString("id");app.tasks.finish(sourceTask,"needs_confirmation","主人核对时的原结果 "+OLD,new JSONObject());
            app.dispatchOwnerAction("confirmResult",j("taskId",sourceTask,"completed",true));source=app.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),"task_evidence","task:"+sourceTask+":verified_result").ref;
            JSONObject saved=memory.importFromSources(scope(),j("text","项目约定 "+OLD,"sourceRefs",new JSONArray().put(source.toJson()),"source","主人核对的原任务来源")).getJSONObject("record");
            old=memory.handle("setCore",j("scope",scope(),"id",saved.getString("id"),"revision",saved.getLong("revision"),"core",true)).getJSONObject("record");
            controller.attach();controller.enterOwnerDomain("taskMemory");
        }
        JSONObject send(String text)throws Exception{
            JSONObject payload=j("message",text,"clientMessageId",UUID.randomUUID().toString());if(conversation!=null)payload.put("conversationId",conversation);
            JSONObject ack=assistant.handle("send",payload);conversation=ack.getString("conversationId");return ack;
        }
        JSONObject completed(JSONObject ack)throws Exception{
            await(()->!assistant.snapshot().getBoolean("running")&&app.tasks.get(ack.getString("taskId"),false).optString("status").matches("completed|failed|needs_confirmation|cancelled"));
            return app.tasks.get(ack.getString("taskId"),false);
        }
        JSONObject command(String command,JSONObject payload)throws Exception{
            java.util.concurrent.atomic.AtomicReference<String> id=new java.util.concurrent.atomic.AtomicReference<>(controller.requestOwnerDomain("taskMemory",command,payload));AtomicInteger refreshed=new AtomicInteger();
            await(()->{JSONObject result=controller.getState().ownerResponses("taskMemory").getOrDefault(command,new JSONObject());if(id.get().equals(result.optString("requestId")))return true;
                if(command.matches("readTaskMemoryReceipt|readTaskMemorySource|listTaskMemoryReplacements")&&!controller.getState().getOwnerPending().contains("taskMemory."+command)&&refreshed.incrementAndGet()<=2)id.set(controller.requestOwnerDomain("taskMemory",command,payload));return false;});
            JSONObject envelope=controller.getState().ownerResponses("taskMemory").get(command);assertTrue(envelope.optString("error"),envelope.getBoolean("ok"));return envelope.getJSONObject("result");
        }
        JSONObject binding(JSONObject task)throws Exception{JSONObject reference=task.getJSONObject("evidence").getJSONObject("memoryReference"),result=new JSONObject();for(String key:new String[]{"taskId","runId","requestId","answerId"})result.put(key,reference.get(key));return result;}
        JSONObject review(JSONObject task,JSONObject row)throws Exception{
            JSONObject binding=binding(task),receipt=command("readTaskMemoryReceipt",binding);return binding.put("receiptId",receipt.getString("receiptId")).put("id",row.getString("id")).put("expectedRevision",row.getLong("revision"));
        }
        @Override public void close(){release.countDown();controller.detach();app.features.close();server.stop(0);java.security.Security.removeProvider("TaskMemoryNativeKeyStore");}
    }
    private static JSONObject row(JSONObject receipt,String id)throws Exception{JSONArray rows=receipt.getJSONArray("records");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);throw new AssertionError("missing native reference row");}
    @Test public void fullHistorySearchRealRequestSourceCorrectionAndNextConversationUseSameAuthority()throws Exception{
        try(Fixture f=new Fixture()){
            for(int i=0;i<205;i++)f.app.tasks.beginExternal("memory_history_fixture","inspect",UUID.randomUUID().toString(),j("goal","较新的普通原生任务 "+i));
            assertFalse(f.app.tasks.list().toString().contains(f.sourceTask));
            JSONObject found=f.command("searchTaskMemoryTasks",j("query","早期项目验收","cursor","","limit",20));assertEquals("full_task_store",found.getString("coverage"));assertEquals(f.sourceTask,found.getJSONArray("tasks").getJSONObject(0).getString("taskId"));assertFalse(found.getJSONArray("tasks").getJSONObject(0).getBoolean("formalMemory"));
            f.controller.focusTask(f.sourceTask);await(()->f.controller.getState().getTasks().stream().anyMatch(t->f.sourceTask.equals(t.getId())));
            JSONObject first=f.completed(f.send("请按项目约定回答"));assertEquals("completed",first.getString("status"));assertEquals(1,f.calls.get());assertTrue(f.requests.get(0).toString().contains(OLD));
            JSONObject receipt=f.command("readTaskMemoryReceipt",f.binding(first));assertEquals("provided_to_request",receipt.getString("phase"));assertFalse(receipt.getBoolean("modelUseVerified"));JSONObject selected=row(receipt,f.old.getString("id"));assertEquals(f.old.getLong("revision"),selected.getLong("revision"));
            JSONObject source=f.command("readTaskMemorySource",f.review(first,selected).put("sourceRef",f.source.toJson()));assertEquals("current",source.getString("status"));assertTrue(source.getString("text").contains(OLD));
            JSONObject corrected=f.command("correctTaskMemoryReviewed",f.review(first,selected).put("text","项目约定 "+UPDATED));assertTrue(corrected.getBoolean("contextInvalidated"));assertTrue(corrected.getBoolean("futureRecallBlocked"));assertFalse(corrected.getBoolean("revocationPending"));assertEquals(f.old.getLong("revision")+1,corrected.getJSONObject("record").getLong("revision"));
            JSONObject stale=f.command("readTaskMemoryReceipt",f.binding(first));assertEquals("record_changed",row(stale,f.old.getString("id")).getString("status"));assertFalse(row(stale,f.old.getString("id")).has("text"));
            f.answer="本轮参考："+UPDATED;JSONObject second=f.completed(f.send("再次核对项目约定"));assertEquals("completed",second.getString("status"));assertTrue(f.requests.get(1).toString().contains(UPDATED));assertFalse("old assistant prose/reference leaked into next request",f.requests.get(1).toString().contains(OLD));
            JSONObject messages=f.assistant.handle("get",j("conversationId",f.conversation));assertTrue(messages.toString().contains("memoryReference"));assertTrue(messages.toString().contains("memoryContextRetired"));
            TaskStore recovered=new TaskStore(f.app);TaskStoreMemoryReader reader=new TaskStoreMemoryReader(recovered,null,recovered::queryMemoryTasks);
            JSONObject original=f.binding(first);TaskMemoryUse.ReceiptRef ref=reader.readReceiptRef(scope(),original.getString("taskId"),original.getString("runId"),original.getString("requestId"),original.getString("answerId"));assertNotNull(ref);assertNotNull(reader.readProvided(MemoryBook.Scope.from(scope()),ref));
            assertNotEquals(original.getString("requestId"),f.binding(second).getString("requestId"));
        }
    }
    @Test public void exactReplacementThenForgetCannotReintroduceOldEvidenceInLaterRequests()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject kept=f.memory.handle("save",j("scope",scope(),"record",j("text","项目新约定 "+KEPT,"core",true))).getJSONObject("record");
            JSONObject first=f.completed(f.send("请读取项目约定")),receipt=f.command("readTaskMemoryReceipt",f.binding(first)),old=row(receipt,f.old.getString("id"));
            JSONObject options=f.command("listTaskMemoryReplacements",f.review(first,old));assertTrue(options.getJSONArray("records").toString().contains(kept.getString("id")));
            JSONObject replaced=f.command("supersedeTaskMemoryReviewed",f.review(first,old).put("keptId",kept.getString("id")).put("keptRevision",kept.getLong("revision")).put("replacedId",old.getString("id")).put("replacedRevision",old.getLong("revision")));
            assertTrue(replaced.getBoolean("futureRecallBlocked"));assertFalse(replaced.getBoolean("revocationPending"));JSONObject previous=f.command("readTaskMemoryReceipt",f.binding(first));assertEquals("superseded",row(previous,old.getString("id")).getString("status"));assertFalse(row(previous,old.getString("id")).has("text"));
            assertThrows(IllegalStateException.class,()->f.app.tasks.readMemorySource(f.source));
            f.answer="本轮参考："+KEPT;JSONObject second=f.completed(f.send("现在的项目约定"));assertFalse(f.requests.get(1).toString().contains(OLD));assertTrue(f.requests.get(1).toString().contains(KEPT));
            JSONObject secondReceipt=f.command("readTaskMemoryReceipt",f.binding(second)),newKept=row(secondReceipt,kept.getString("id"));
            JSONObject forgotten=f.command("forgetTaskMemoryReviewed",f.review(second,newKept));assertTrue(forgotten.getInt("deleted")>0);assertTrue(forgotten.getBoolean("contextInvalidated"));
            f.answer="当前没有保存的项目约定";f.completed(f.send("再检查当前约定"));assertFalse(f.requests.get(2).toString().contains(OLD));assertFalse(f.requests.get(2).toString().contains(KEPT));
        }
    }
    @Test public void forgetDuringActualSendWithdrawsLateAnswerAndReplansWithoutOldReference()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject first=f.completed(f.send("先核对项目约定"));JSONObject original=f.command("readTaskMemoryReceipt",f.binding(first));JSONObject selected=row(original,f.old.getString("id"));
            f.blockCall=2;JSONObject secondAck=f.send("再核对同一约定");assertTrue(f.blocked.await(5,TimeUnit.SECONDS));
            JSONObject forgotten=f.command("forgetTaskMemoryReviewed",f.review(first,selected));assertTrue(forgotten.getBoolean("futureRecallBlocked"));f.answer="当前没有旧约定";f.release.countDown();
            JSONObject second=f.completed(secondAck);assertEquals("completed",second.getString("status"));assertEquals(3,f.calls.get());assertTrue(f.requests.get(1).toString().contains(OLD));assertFalse(f.requests.get(2).toString().contains(OLD));
            JSONArray operations=second.getJSONArray("operations");int unknown=0,provided=0;for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(TaskMemoryJournal.UNCONFIRMED.equals(op.optString("state")))unknown++;if(TaskMemoryJournal.PROVIDED.equals(op.optString("state")))provided++;}assertEquals(1,unknown);assertEquals(1,provided);
        }
    }
    @Test public void failedRequestAndPlausibleTaskEventsNeverBecomeProvidedReceipts()throws Exception{
        try(Fixture f=new Fixture()){
            f.fail=true;JSONObject failed=f.completed(f.send("读取项目约定但连接失败"));assertEquals("failed",failed.getString("status"));assertEquals(1,f.calls.get());
            JSONArray operations=failed.getJSONArray("operations");assertEquals(1,operations.length());JSONObject op=operations.getJSONObject(0);assertEquals(TaskMemoryJournal.UNCONFIRMED,op.getString("state"));JSONObject args=op.getJSONObject("arguments");
            JSONObject fake=j("taskId",failed.getString("id"),"runId",failed.getString("runId"),"requestId",args.getString("requestId"),"answerId","fake_answer");
            f.app.tasks.event(failed.getString("id"),j("type","memory_provided","requestId",args.getString("requestId"),"receipt",fake,"provided",true));
            JSONObject receipt=f.command("readTaskMemoryReceipt",fake);assertEquals("unavailable",receipt.getString("phase"));assertEquals(0,receipt.getJSONArray("records").length());
        }
    }
    @Test public void revokedNativeTaskConfirmationInvalidatesOriginalReceiptAndLaterRequest()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject first=f.completed(f.send("读取原项目约定"));JSONObject original=f.command("readTaskMemoryReceipt",f.binding(first));assertEquals("current",row(original,f.old.getString("id")).getString("status"));
            f.app.dispatchOwnerAction("confirmResult",j("taskId",f.sourceTask,"completed",false));
            JSONObject invalid=f.command("readTaskMemoryReceipt",f.binding(first));assertFalse(row(invalid,f.old.getString("id")).has("text"));assertNotEquals("current",row(invalid,f.old.getString("id")).getString("status"));
            assertThrows(IllegalStateException.class,()->f.app.tasks.readMemorySource(f.source));f.answer="原确认已经撤销，当前没有旧约定";
            f.completed(f.send("现在可核对的项目约定"));assertFalse(f.requests.get(1).toString().contains(OLD));
        }
    }
    @Test public void nativeMessageEvidenceUsesBoundSingletonAndEditingItInvalidatesLaterRecall()throws Exception{
        try(Fixture f=new Fixture()){
            f.completed(f.send("这是我的消息来源原文"));JSONObject original=f.assistant.handle("get",j("conversationId",f.conversation)).getJSONArray("messages").getJSONObject(0);
            SourceRef source=f.assistant.resolveSource(MemoryBook.Scope.from(scope()),original.getString("id")).ref;
            JSONObject saved=f.memory.importFromSources(scope(),j("text","消息偏好 B1_MESSAGE_SOURCE_153","sourceRefs",new JSONArray().put(source.toJson()))).getJSONObject("record");
            f.memory.handle("setCore",j("scope",scope(),"id",saved.getString("id"),"revision",saved.getLong("revision"),"core",true));
            JSONObject task=f.completed(f.send("按保存的消息偏好回答")),receipt=f.command("readTaskMemoryReceipt",f.binding(task)),selected=row(receipt,saved.getString("id"));
            JSONObject body=f.command("readTaskMemorySource",f.review(task,selected).put("sourceRef",source.toJson()));assertEquals("current",body.getString("status"));assertEquals("这是我的消息来源原文",body.getString("text"));
            f.assistant.handle("editMessage",j("conversationId",f.conversation,"messageId",original.getString("id"),"revision",original.getLong("revision"),"content","主人已修订原消息内容"));
            JSONObject invalid=f.command("readTaskMemoryReceipt",f.binding(task));assertFalse(row(invalid,saved.getString("id")).has("text"));
            f.answer="消息来源已修订";f.completed(f.send("重新核对保存的消息偏好"));assertFalse(f.requests.get(2).toString().contains("B1_MESSAGE_SOURCE_153"));
        }
    }
    @Test public void forgettingMessageDerivedMemoryRetiresItsHistoricalUserReferenceFromNewRequests()throws Exception{
        try(Fixture f=new Fixture()){
            f.completed(f.send("来源原句 B1_FORGOTTEN_USER_SOURCE_372"));JSONObject message=f.assistant.handle("get",j("conversationId",f.conversation)).getJSONArray("messages").getJSONObject(0);
            SourceRef source=f.assistant.resolveSource(MemoryBook.Scope.from(scope()),message.getString("id")).ref;
            JSONObject saved=f.memory.importFromSources(scope(),j("text","个人事实 B1_FORGOTTEN_USER_SOURCE_372","sourceRefs",new JSONArray().put(source.toJson()))).getJSONObject("record");f.memory.handle("setCore",j("scope",scope(),"id",saved.getString("id"),"revision",saved.getLong("revision"),"core",true));
            JSONObject task=f.completed(f.send("请按保存的个人事实回答")),receipt=f.command("readTaskMemoryReceipt",f.binding(task));f.command("forgetTaskMemoryReviewed",f.review(task,row(receipt,saved.getString("id"))));
            JSONArray history=new JSONArray().put(j("role","user","content",message.getString("content"),"ownerMessageId",message.getString("id"),"ownerMessageRevision",message.getLong("revision")));
            assertFalse(f.assistant.projectMemoryHistory(history,java.util.Set.of()).toString().contains("B1_FORGOTTEN_USER_SOURCE_372"));
            assertTrue("memory retirement cannot revoke an original task delegation",f.assistant.projectMemoryHistory(history,java.util.Set.of(message.getString("id"))).toString().contains("B1_FORGOTTEN_USER_SOURCE_372"));
            f.answer="当前不再使用原个人事实";f.completed(f.send("现在还可核对什么？"));assertFalse("forgotten source was reintroduced by automatic historical user-message context",f.requests.get(2).toString().contains("B1_FORGOTTEN_USER_SOURCE_372"));
            assertTrue("original display record is retained; forgetting memory is not deleting the conversation",f.assistant.handle("get",j("conversationId",f.conversation)).toString().contains("B1_FORGOTTEN_USER_SOURCE_372"));
        }
    }
}
