package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.store.*;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real AssistantModule -> AgentRunner -> HTTP bytes and SQLite journal.
 * The native mailbox is an explicit fixture until the separately owned Application host is mounted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AssistantInputIntegrationTest {
    private static final String IMAGE="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQZkAAAAASUVORK5CYII=";
    private Context android(){return RuntimeEnvironment.getApplication();}
    private static JSONObject j(Object... values)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);return o;}
    private static JSONObject response(String answer)throws Exception{return j("choices",new JSONArray().put(j("message",j("role","assistant","content",answer),"finish_reason","stop")));}
    private static JSONObject calls(int n)throws Exception{JSONArray calls=new JSONArray();for(int i=0;i<n;i++)calls.put(j("id","call-"+i,"type","function","function",j("name","fixture_effect","arguments","{}")));return j("choices",new JSONArray().put(j("message",j("role","assistant","content","","tool_calls",calls),"finish_reason","tool_calls")));}
    private static JSONObject selected(JSONObject meta)throws Exception{return j("id",meta.getString("id"),"sha256",meta.getString("sha256"));}
    private static int occurrences(String text,String needle){return (text.length()-text.replace(needle,"").length())/needle.length();}
    private static void awaitIdle(AssistantModule assistant)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<until){if(!assistant.snapshot().getBoolean("running"))return;Thread.sleep(5);}throw new AssertionError("runner remained active");}
    private static final class Server implements AutoCloseable {
        final HttpServer server;final List<JSONObject> bodies=new CopyOnWriteArrayList<>();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final List<JSONObject> responses;Server(JSONObject... responses)throws Exception{this.responses=List.of(responses);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int index=bodies.size();bodies.add(body);if(index==0){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("fixture gate timeout");}byte[] bytes=this.responses.get(Math.min(index,this.responses.size()-1)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();}
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}public void close(){release.countDown();server.stop(0);}
    }
    private final class Host implements AssistantModule.NativeInputHost,AgentRunner.OwnerInputs {
        final TaskStore tasks=new TaskStore(android());final List<JSONObject> inputs=new ArrayList<>();final AtomicInteger starts=new AtomicInteger(),appends=new AtomicInteger(),effects=new AtomicInteger();
        volatile JSONObject binding;volatile RunControl live;volatile boolean open=true,unknownAppend,holdEndRun;final CountDownLatch ending=new CountDownLatch(1),releaseEnd=new CountDownLatch(1);volatile RunJournal journal;volatile RunOutcome outcome;volatile AssistantModule assistant;
        public synchronized JSONObject start(String cid,String goal,JSONObject message,ModelConfig config,RunControl control)throws Exception{
            starts.incrementAndGet();String id=tasks.createCapabilityTask(goal,cid).getString("id");JSONObject attempt=tasks.startAttempt(id,"capabilities",config.maxSteps);control.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));live=control;
            binding=j("taskId",id,"runId",attempt.getString("runId"),"epoch",attempt.getInt("coordinatorEpoch"));journal=tasks.journal(id,binding.getString("runId"),binding.getInt("epoch"),control);return new JSONObject(binding.toString());
        }
        public synchronized JSONObject append(String cid,JSONObject message,String mode,JSONObject expected,RunControl control)throws Exception{
            appends.incrementAndGet();control.check();assertSame(live,control);assertTrue(control.matchesHostRun(expected.getString("taskId"),expected.getString("runId"),expected.getInt("epoch")));if(!open)throw new OwnerInputNotAdmittedException("mailbox closed");if(unknownAppend)throw new java.io.IOException("unknown fixture admission");
            inputs.add(j("messageId",message.getString("id"),"clientMessageId",message.getString("clientMessageId"),"content",message.getString("content"),"mode",mode,"attachments",message.getJSONArray("attachments")));return new JSONObject(binding.toString());
        }
        public synchronized JSONArray poll(JSONArray ids,boolean turnBoundary,boolean closeIfEmpty,RunControl control)throws Exception{
            control.check();Set<String> seen=new HashSet<>();for(int i=0;i<ids.length();i++)seen.add(ids.getString(i));JSONArray pending=new JSONArray();for(JSONObject row:inputs)if(!seen.contains(row.getString("messageId"))&&(turnBoundary||row.getString("mode").equals("steer")))pending.put(new JSONObject(row.toString()));if(closeIfEmpty&&pending.length()==0)open=false;return pending;
        }
        public void completed(AgentRunner.OwnerReply reply,RunControl control)throws Exception{
            assertSame(live,control);assistant.acceptNativeReply(assistant.snapshot().getJSONObject("activeTurn").getString("conversationId"),new JSONObject(binding.toString()),reply,control);
        }
        JSONObject execute(String cid,String goal,JSONArray initial,ModelConfig config,RunControl control,AttachmentRequestProjection projection)throws Exception{
            journal.checkpoint(initial,0,new JSONObject());
            CapabilityPort port=new CapabilityPort(){public void endRun(RunControl c)throws Exception{if(holdEndRun){ending.countDown();if(!releaseEnd.await(5,TimeUnit.SECONDS))throw new AssertionError("end-run gate timeout");}}public JSONArray tools()throws Exception{return new JSONArray().put(j("type","function","function",j("name","fixture_effect","parameters",j("type","object","properties",new JSONObject()))));}public boolean supports(String n){return n.equals("fixture_effect");}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject a,RunControl c)throws Exception{effects.incrementAndGet();return j("ok",true,"accepted",true,"verified",false);}};
            outcome=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource(),projection,this).run(binding.getString("taskId"),goal,config,control,event->{try{tasks.event(binding.optString("taskId"),binding.optString("runId"),binding.optInt("epoch"),event);}catch(Exception failure){throw new IllegalStateException(failure);}},journal);
            tasks.endAttempt(binding.getString("taskId"),binding.getString("runId"),outcome.status,outcome.summary,outcome.evidence);return j("taskId",binding.getString("taskId"),"content",outcome.evidence.optString("modelSummary",outcome.summary));
        }
    }
    private AssistantModule assistant(Server server,Host host,boolean images)throws Exception{
        // Use native preferences for an uncredentialed loopback fixture; no Keystore fake or external model.
        android().getSharedPreferences("private_settings",0).edit().putString("baseUrl",server.base()).putString("model","fixture-model").putString("provider","openai_chat").putBoolean("sendScreenshots",false).putBoolean("supportsImageInput",images).putInt("maxSteps",8).commit();
        FeatureContext context=new FeatureContext(android(),new SettingsStore(android()),new SecretVault(android()),event->{});AttachmentsModule attachments=new AttachmentsModule(context);AttachmentRequestProjection projection=new AttachmentRequestProjection(AttachmentRepository.get(android()).book,attachments);
        AssistantModule result=new AssistantModule(context,null,(cid,goal,history,config,control)->host.execute(cid,goal,history,config,control,projection));host.assistant=result;result.setSourceSynchronizer(()->{});result.setNativeInputHost(host);result.setAttachmentProjection(projection);return result;
    }
    private JSONObject send(String text)throws Exception{return j("message",text,"clientMessageId",UUID.randomUUID().toString());}
    private JSONObject follow(JSONObject first,String text,String mode)throws Exception{return send(text).put("conversationId",first.getString("conversationId")).put("taskId",first.getString("taskId")).put("runId",first.getString("runId")).put("epoch",first.getInt("epoch")).put("mode",mode);}
    private JSONObject image()throws Exception{return AttachmentRepository.get(android()).book.importBytes(new ByteArrayInputStream(Base64.getDecoder().decode(IMAGE)),"real.png","image/png",new JSONObject());}

    @Test public void busyQueueDedupAndRealAttachmentsReachHttpOnlyNotJournal()throws Exception{
        Host host=new Host();try(Server server=new Server(response("第一轮完成"),response("补充也已阅读"));AssistantModule assistant=assistant(server,host,true)){
            JSONObject image=image(),document=AttachmentRepository.get(android()).book.importBytes(new ByteArrayInputStream("PRIVATE-DOCUMENT-CONTENT".getBytes(StandardCharsets.UTF_8)),"private.txt","text/plain",new JSONObject());
            JSONObject original=send("看所选文件").put("attachments",new JSONArray().put(selected(image)).put(selected(document))),first=assistant.handle("send",original);assertEquals("accepted",first.getString("status"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));
            JSONObject extra=follow(first,"唯一排队补充","queue"),queued=assistant.handle("send",extra),again=assistant.handle("send",extra);assertEquals("queued",queued.getString("status"));assertEquals(queued.getString("messageId"),again.getString("messageId"));assertTrue(again.getBoolean("duplicate"));assertEquals(first.getString("taskId"),queued.getString("taskId"));assertFalse(host.live.isCancelled());server.release.countDown();awaitIdle(assistant);
            assertEquals(1,host.starts.get());assertEquals(1,host.appends.get());assertEquals(1,host.tasks.list().length());assertEquals(2,server.bodies.size());String firstBody=server.bodies.get(0).toString(),secondBody=server.bodies.get(1).toString();assertTrue(firstBody.replace("\\/","/").contains("data:image/png;base64,"+IMAGE));assertTrue(firstBody.contains("PRIVATE-DOCUMENT-CONTENT"));assertTrue(firstBody.contains("低信任"));assertFalse(firstBody.contains("ownerAttachmentRefs"));assertEquals(1,occurrences(secondBody,"唯一排队补充"));
            String stored=host.tasks.get(first.getString("taskId"),false).toString().replace("\\/","/");assertFalse(stored.contains(IMAGE));assertFalse(stored.contains("PRIVATE-DOCUMENT-CONTENT"));assertFalse(Files.readString(new java.io.File(android().getFilesDir(),"private_conversations.json").toPath()).contains(IMAGE));
            assertTrue(assistant.handle("send",original).getBoolean("duplicate"));assertEquals(1,host.starts.get());
        }
    }
    @Test public void steerDiscardsUnexecutedDecisionWithoutCancellingOrNewRunner()throws Exception{
        Host host=new Host();try(Server server=new Server(calls(1),response("按新要求答复"));AssistantModule assistant=assistant(server,host,false)){
            JSONObject first=assistant.handle("send",send("先检查"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));assistant.handle("send",follow(first,"不要执行旧决定","steer"));server.release.countDown();awaitIdle(assistant);assertEquals(2,server.bodies.size());assertEquals(0,host.effects.get());assertEquals(1,host.starts.get());assertFalse(host.live.isCancelled());assertEquals(1,occurrences(server.bodies.get(1).toString(),"不要执行旧决定"));
        }
    }
    @Test public void queueWaitsForCurrentToolTurnRatherThanInterruptingItsBatch()throws Exception{
        Host host=new Host();try(Server server=new Server(calls(2),response("当前工具轮结束"),response("下一轮完成"));AssistantModule assistant=assistant(server,host,false)){
            JSONObject first=assistant.handle("send",send("当前任务"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));assistant.handle("send",follow(first,"下轮才处理","queue"));server.release.countDown();awaitIdle(assistant);assertEquals(2,host.effects.get());assertEquals(3,server.bodies.size());assertFalse(server.bodies.get(1).toString().contains("下轮才处理"));assertEquals(1,occurrences(server.bodies.get(2).toString(),"下轮才处理"));
        }
    }
    @Test public void staleEpochAndOtherConversationCannotEnterTheActiveTask()throws Exception{
        Host host=new Host();try(Server server=new Server(response("完成"));AssistantModule assistant=assistant(server,host,false)){
            JSONObject first=assistant.handle("send",send("原任务"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));JSONObject stale=follow(first,"不应保存","steer").put("epoch",first.getInt("epoch")+1);assertThrows(SecurityException.class,()->assistant.handle("send",stale));JSONObject other=follow(first,"不应创建","queue").put("conversationId","other");assertThrows(IllegalStateException.class,()->assistant.handle("send",other));assertEquals(0,host.appends.get());assertEquals(1,assistant.handle("get",j("conversationId",first.getString("conversationId"))).getJSONArray("messages").length());server.release.countDown();awaitIdle(assistant);
        }
    }
    @Test public void unknownAppendIsQueryableAndNeverResubmitted()throws Exception{
        Host host=new Host();try(Server server=new Server(response("完成"));AssistantModule assistant=assistant(server,host,false)){
            JSONObject first=assistant.handle("send",send("原任务"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));host.unknownAppend=true;JSONObject input=follow(first,"未知补充","queue"),ack=assistant.handle("send",input);assertEquals("unconfirmed",ack.getString("status"));assertTrue(assistant.handle("send",input).getBoolean("duplicate"));assertEquals("unconfirmed",assistant.handle("getSubmission",j("clientMessageId",input.getString("clientMessageId"))).getString("status"));assertEquals(1,host.appends.get());server.release.countDown();awaitIdle(assistant);
        }
    }
    @Test public void mailboxCloseBeforeTurnReleaseIsDefinitivelyRejectedWithoutReplay()throws Exception{
        Host host=new Host();host.holdEndRun=true;
        try(Server server=new Server(response("完成"));AssistantModule assistant=assistant(server,host,false)){
            JSONObject first=assistant.handle("send",send("原任务"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));server.release.countDown();
            try{
                assertTrue(host.ending.await(3,TimeUnit.SECONDS));assertFalse(host.open);assertTrue(assistant.snapshot().getBoolean("running"));
                JSONObject input=follow(first,"未入队的补充","queue"),ack=assistant.handle("send",input);
                assertEquals("rejected",ack.getString("status"));assertTrue(ack.getBoolean("notAdmitted"));assertFalse(ack.getBoolean("reconciliationRequired"));
                assertEquals(first.getString("runId"),ack.getString("runId"));assertTrue(assistant.handle("send",input).getBoolean("duplicate"));
                assertEquals("rejected",assistant.handle("getSubmission",j("clientMessageId",input.getString("clientMessageId"))).getString("status"));
                assertEquals(1,host.appends.get());assertTrue(host.inputs.isEmpty());
            }finally{host.releaseEnd.countDown();}
            awaitIdle(assistant);assertEquals(1,host.starts.get());assertEquals(1,server.bodies.size());
        }
    }
    @Test public void realClosedMailboxRejectsBeforeWritingAnInputRow()throws Exception{
        TaskStore tasks=new TaskStore(android());String cid=UUID.randomUUID().toString();JSONObject task=tasks.createCapabilityTask("原任务",cid),attempt=tasks.startAttempt(task.getString("id"),"capabilities",8);
        String id=task.getString("id"),run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(id,run,epoch);
        tasks.openOwnerInputMailbox(id,run,epoch,cid,control);assertEquals(0,tasks.pollOwnerInputs(id,run,epoch,new JSONArray(),true,true,control).length());
        JSONObject input=j("id",UUID.randomUUID().toString(),"clientMessageId",UUID.randomUUID().toString(),"content","未入队","attachments",new JSONArray());
        assertThrows(OwnerInputNotAdmittedException.class,()->tasks.appendOwnerInput(id,run,epoch,cid,input,"queue",control));
        assertEquals(0,tasks.get(id,false).getJSONArray("ownerInputs").length());
    }
    @Test public void closedMailboxNeverClaimsAnEarlierAdmittedIdentityWasRejected()throws Exception{
        TaskStore tasks=new TaskStore(android());String cid=UUID.randomUUID().toString();JSONObject task=tasks.createCapabilityTask("原任务",cid),attempt=tasks.startAttempt(task.getString("id"),"capabilities",8);
        String id=task.getString("id"),run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(id,run,epoch);
        tasks.openOwnerInputMailbox(id,run,epoch,cid,control);JSONObject input=j("id",UUID.randomUUID().toString(),"clientMessageId",UUID.randomUUID().toString(),"content","已登记","attachments",new JSONArray());
        tasks.appendOwnerInput(id,run,epoch,cid,input,"queue",control);tasks.pollOwnerInputs(id,run,epoch,new JSONArray().put(input.getString("id")),true,true,control);
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->tasks.appendOwnerInput(id,run,epoch,cid,input,"queue",control));assertFalse(failure instanceof OwnerInputNotAdmittedException);
        assertEquals(1,tasks.get(id,false).getJSONArray("ownerInputs").length());
    }
    @Test public void imageRequiresExplicitModelCapabilityAndCurrentDigestBeforeAnyAdmission()throws Exception{
        Host host=new Host();try(Server server=new Server(response("not reached"));AssistantModule assistant=assistant(server,host,false)){
            JSONObject image=image(),input=send("图片").put("attachments",new JSONArray().put(selected(image)));assertThrows(IllegalArgumentException.class,()->assistant.handle("send",input));assertEquals(0,host.starts.get());assertEquals(0,server.bodies.size());
            input.put("attachments",new JSONArray().put(selected(image).put("sha256","0".repeat(64))));assertThrows(IllegalArgumentException.class,()->assistant.handle("send",input));assertEquals(0,assistant.handle("list",new JSONObject()).getJSONArray("conversations").length());
        }
    }
    @Test public void cancelDoesNotDispatchQueuedOrLateInput()throws Exception{
        Host host=new Host();try(Server server=new Server(calls(1),response("not reached"));AssistantModule assistant=assistant(server,host,false)){
            JSONObject first=assistant.handle("send",send("原任务"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));assistant.handle("send",follow(first,"保留但不自动执行","queue"));assistant.handle("cancel",new JSONObject());assertThrows(InterruptedException.class,()->assistant.handle("send",follow(first,"停止后补充","steer")));server.release.countDown();awaitIdle(assistant);assertEquals(0,host.effects.get());assertEquals(1,server.bodies.size());assertTrue(host.live.isCancelled());
        }
    }
    @Test public void sameTaskRestoreRebuildsSelectedBytesButRetiresPhoneScreenshotAndRejectsDeletedRef()throws Exception{
        Host host=new Host();try(Server server=new Server(response("第一轮"),response("恢复轮"));AssistantModule assistant=assistant(server,host,true)){
            JSONObject image=image(),first=assistant.handle("send",send("读图片").put("attachments",new JSONArray().put(selected(image))));assertTrue(server.entered.await(3,TimeUnit.SECONDS));server.release.countDown();awaitIdle(assistant);
            String id=first.getString("taskId");JSONObject next=host.tasks.startAttempt(id,"capabilities",8);RunControl resumed=new RunControl();resumed.bindHostRun(id,next.getString("runId"),next.getInt("coordinatorEpoch"));RunJournal actual=host.tasks.journal(id,next.getString("runId"),next.getInt("coordinatorEpoch"),resumed);
            RunJournal oldScreenshotFixture=new RunJournal(){public JSONObject restore()throws Exception{JSONObject data=actual.restore();data.getJSONArray("messages").put(j("role","user","content",new JSONArray().put(j("type","text","text","历史手机截图")).put(j("type","image_url","image_url",j("url","data:image/png;base64,ZXhwaXJlZA==")))));return data;}public void checkpoint(JSONArray m,int n,JSONObject o)throws Exception{actual.checkpoint(m,n,o);}public String beginOperation(String t,JSONObject a)throws Exception{return actual.beginOperation(t,a);}public void finishOperation(String i,JSONObject r)throws Exception{actual.finishOperation(i,r);}};
            AttachmentRequestProjection projection=new AttachmentRequestProjection(AttachmentRepository.get(android()).book,null);CapabilityPort port=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return false;}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("no tools");}};
            RunOutcome outcome=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource(),projection,null).run(id,"读图片",new SettingsStore(android()).get(),resumed,event->{},oldScreenshotFixture);assertEquals("needs_confirmation",outcome.status);assertEquals(2,server.bodies.size());String restored=server.bodies.get(1).toString().replace("\\/","/");assertTrue(restored.contains(IMAGE));assertFalse(restored.contains("ZXhwaXJlZA=="));assertFalse(actual.restore().toString().replace("\\/","/").contains(IMAGE));
            host.tasks.endAttempt(id,next.getString("runId"),outcome.status,outcome.summary,outcome.evidence);AttachmentRepository.get(android()).remove(image.getString("id"));JSONObject third=host.tasks.startAttempt(id,"capabilities",8);RunControl deleted=new RunControl();deleted.bindHostRun(id,third.getString("runId"),third.getInt("coordinatorEpoch"));RunOutcome stopped=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource(),projection,null).run(id,"读图片",new SettingsStore(android()).get(),deleted,event->{},host.tasks.journal(id,third.getString("runId"),third.getInt("coordinatorEpoch"),deleted));assertEquals("failed",stopped.status);assertEquals(2,server.bodies.size());assertEquals(1,host.tasks.list().length());
        }
    }
    @Test public void importedBytesDigestAndLimitsAreRecheckedInsteadOfTrustingMetadata()throws Exception{
        AttachmentBook book=AttachmentRepository.get(android()).book;AttachmentRequestProjection projection=new AttachmentRequestProjection(book,null);JSONObject image=image();ModelConfig config=new ModelConfig("http://127.0.0.1","","fixture",false,3,10).withOwnerImageInput(true);JSONArray refs=projection.select(new JSONArray().put(selected(image)),config,new RunControl());
        JSONArray history=new JSONArray().put(j("role","user","content","所选图片","ownerAttachmentRefs",refs));byte[] corrupted=Files.readAllBytes(book.file(image.getString("id")).toPath());corrupted[corrupted.length-1]^=1;Files.write(book.file(image.getString("id")).toPath(),corrupted);assertThrows(IllegalArgumentException.class,()->projection.project(history,config,new RunControl()));
        JSONObject large=book.importBytes(new ByteArrayInputStream("x".repeat(12001).getBytes(StandardCharsets.UTF_8)),"too-long.txt","text/plain",new JSONObject());assertThrows(IllegalArgumentException.class,()->projection.select(new JSONArray().put(selected(large)),config,new RunControl()));
        JSONObject unsupported=book.importBytes(new ByteArrayInputStream(new byte[]{0,1,2,3}),"unknown.bin","application/octet-stream",new JSONObject());assertThrows(IllegalArgumentException.class,()->projection.select(new JSONArray().put(selected(unsupported)),config,new RunControl()));
        RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->projection.select(new JSONArray().put(selected(large)),config,cancelled));
    }
    @Test public void submissionReceiptSurvivesConversationDeletionAndReloadWithoutNewTask()throws Exception{
        Host host=new Host();JSONObject input,ack;
        try(Server server=new Server(response("完成"));AssistantModule assistant=assistant(server,host,false)){
            input=send("持久去重");ack=assistant.handle("send",input);server.release.countDown();awaitIdle(assistant);assistant.handle("delete",j("conversationId",ack.getString("conversationId")));
        }
        FeatureContext context=new FeatureContext(android(),new SettingsStore(android()),new SecretVault(android()),event->{});try(AssistantModule restored=new AssistantModule(context,null,(a,b,c,d,e)->{throw new AssertionError("must not execute");})){
            restored.setNativeInputHost(host);JSONObject duplicate=restored.handle("send",input);assertTrue(duplicate.getBoolean("duplicate"));assertEquals(ack.getString("messageId"),duplicate.getString("messageId"));assertEquals(1,host.starts.get());
        }
    }

    @Test public void newTurnDoesNotImplicitlyResendUnconfirmedPriorInput()throws Exception{
        Host host=new Host();try(Server server=new Server(response("原轮完成"),response("新轮完成"));AssistantModule assistant=assistant(server,host,false)){
            JSONObject first=assistant.handle("send",send("原始委派"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));host.unknownAppend=true;JSONObject uncertain=assistant.handle("send",follow(first,"UNCONFIRMED-DO-NOT-REPLAY","queue"));assertEquals("unconfirmed",uncertain.getString("status"));server.release.countDown();awaitIdle(assistant);
            host.unknownAppend=false;host.open=true;assistant.handle("send",send("独立的新消息").put("conversationId",first.getString("conversationId")));awaitIdle(assistant);assertEquals(2,server.bodies.size());assertFalse(server.bodies.get(1).toString().contains("UNCONFIRMED-DO-NOT-REPLAY"));assertTrue(server.bodies.get(1).toString().contains("不代表本次新委派"));assertEquals(2,host.starts.get());
        }
    }

    @Test public void opaqueOwnerIdentityAndAttachmentRefsSurviveTextSecretRedaction()throws Exception{
        try(Server server=new Server(response("完成"))){
            server.release.countDown();AttachmentBook book=AttachmentRepository.get(android()).book;JSONObject attachment=image();ModelConfig config=new ModelConfig(server.base(),attachment.getString("sha256").substring(0,8),"fixture",false,3,10).withOwnerImageInput(true);AttachmentRequestProjection projection=new AttachmentRequestProjection(book,null);JSONArray refs=projection.select(new JSONArray().put(selected(attachment)),config,new RunControl());
            TaskStore tasks=new TaskStore(android());String id=tasks.createCapabilityTask("目标","conv").getString("id");JSONObject attempt=tasks.startAttempt(id,"capabilities",3);RunControl live=new RunControl();live.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));RunJournal journal=tasks.journal(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),live);String opaque=config.apiKey+"-aaaa-4aaa-8aaa-aaaaaaaaaaaa";journal.checkpoint(new JSONArray().put(j("role","system","content","system")).put(j("role","user","content","目标","ownerMessageId",opaque,"ownerAttachmentRefs",refs)),0,new JSONObject());
            CapabilityPort port=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return false;}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("no effects");}};
            RunOutcome outcome=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource(),projection,(ids,turn,close,control)->{assertEquals(opaque,ids.getString(0));return new JSONArray();}).run(id,"目标",config,live,event->{},journal);assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(1,server.bodies.size());assertTrue(server.bodies.get(0).toString().replace("\\/","/").contains(IMAGE));assertEquals(opaque,journal.restore().getJSONArray("messages").getJSONObject(1).getString("ownerMessageId"));
        }
    }

}
