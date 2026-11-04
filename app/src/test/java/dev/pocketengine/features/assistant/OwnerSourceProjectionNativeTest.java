package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.features.memory.*;
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
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual original DB/Book, native input, Runner and loopback wire. No external model/device/UI proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerSourceProjectionNativeTest {
    private static final String TARGET="target-private",SOURCE="source-private",CANARY="OLD-ORIGINAL-LOW-TRUST-CANARY";
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static String id(){return UUID.randomUUID().toString();}
    /** DB metadata fixture only. It deliberately creates no native Document/registry selection. */
    private static JSONArray previewMetadata()throws Exception{return new JSONArray().put(new JSONObject()
        .put("referenceId",id()).put("taskId",id()).put("runId",id()).put("epoch",1).put("conversationId",TARGET)
        .put("projectId",id()).put("checkoutId",id()).put("previewId","a".repeat(64)).put("sourceDigest","b".repeat(64))
        .put("documentId",id()).put("documentGeneration",2L).put("path","dist/index.html").put("resourceSha256","c".repeat(64))
        .put("selectorHint","button span").put("text",CANARY).put("htmlSnippet","<span>"+CANARY+"</span>")
        .put("bounds",new JSONObject().put("x",1).put("y",2).put("width",40).put("height",20))
        .put("truncated",new JSONObject().put("selectorHint",false).put("text",false).put("htmlSnippet",false))
        .put("untrusted",true).put("selectorIsSourceLocation",false));}
    // Test-only package bridge over rows returned by the actual native producer. The model still
    // receives only opaque Capability selections; the production locator factory stays private.
    private static ConversationHistory.SourceLocator locator(JSONObject nativeRow)throws Exception{var method=ConversationHistory.SourceLocator.class.getDeclaredMethod("from",JSONObject.class);method.setAccessible(true);return (ConversationHistory.SourceLocator)method.invoke(null,nativeRow);}

    private static ModelConfig config(){return new ModelConfig("http://127.0.0.1","","fixture-model",false,8,10);}
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final ConversationRepository repo;
        final MemoryModule memory;final AssistantModule assistant;final FeatureHost host;final MemorySourceBridge bridge;final TaskMemoryNativeHost nativeHost;
        final AttachmentRequestProjection attachments;final Flow flow;final java.util.List<java.io.File> echoFiles=new java.util.ArrayList<>();final AtomicInteger echoes=new AtomicInteger();final Map<RunControl,JSONObject> bindings=new IdentityHashMap<>();
        Fixture()throws Exception{this(null,null);}
        Fixture(Server server,byte[] legacy)throws Exception{
            android.deleteDatabase("pocket-world.db");java.io.File old=new java.io.File(android.getFilesDir(),"private_conversations.json");if(legacy!=null)Files.write(old.toPath(),legacy);
            if(server!=null)android.getSharedPreferences("private_settings",0).edit().putString("baseUrl",server.base()).putString("model","fixture-model").putString("provider","openai_chat").putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
            tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);repo=tasks.conversationRepository();
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});AtomicReference<AssistantModule> bound=new AtomicReference<>();
            memory=new MemoryModule(context,(identity,kind,source)->bound.get().resolveSource(identity,source));attachments=new AttachmentRequestProjection(AttachmentRepository.get(android).book,null);
            flow=server==null?null:new Flow(this,server.scripted);assistant=new AssistantModule(context,memory,flow==null?null:flow::execute,tasks);bound.set(assistant);
            if(flow!=null){assistant.setNativeInputHost(flow);assistant.setAttachmentProjection(attachments);}
            host=new FeatureHost(event->{});host.register(memory);host.register(assistant);host.register(new dev.pocketengine.features.skills.SkillsModule(context));bridge=new MemorySourceBridge(host,tasks);bridge.bindAssistant(assistant);nativeHost=new TaskMemoryNativeHost(host,tasks,bridge);
            if(repo.active()){conversation(TARGET);conversation(SOURCE);}
        }
        void conversation(String conversation)throws Exception{repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title",conversation).put("messages",new JSONArray()));return null;});}
        JSONObject save(String conversation,String message,long revision,String body,JSONArray refs)throws Exception{
            return save(conversation,message,revision,body,refs,null);
        }
        JSONObject save(String conversation,String message,long revision,String body,JSONArray refs,JSONArray previews)throws Exception{
            JSONObject row=new JSONObject().put("id",message).put("revision",revision).put("role","user").put("content",body);if(refs!=null)row.put("ownerSourceRefs",OwnerSourceReferences.checked(refs));
            if(previews!=null)row.put("ownerPreviewRefs",dev.pocketengine.features.local.preview.PreviewElementRef.checked(previews));
            repo.transaction(()->{repo.saveSource(new SourceRef("message",message,revision,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false));repo.saveMessage(conversation,row);return null;});return row;
        }
        JSONObject hit(String text)throws Exception{return nativeHost.handle("searchOwnerOriginals",new JSONObject().put("query",text).put("cursor","").put("limit",20).put("maxChars",16000),new RunControl()).getJSONArray("entries").getJSONObject(0);}
        JSONObject selection(JSONObject hit,int offset,int chars)throws Exception{return nativeHost.handle("selectOwnerOriginalReference",new JSONObject().put("ownerLocator",hit.getJSONObject("ownerLocator")).put("offset",offset).put("chars",chars),new RunControl());}
        JSONArray source(String body)throws Exception{save(SOURCE,id(),9_000_000_000L,body,null);return new JSONArray().put(selection(hit(body.substring(0,Math.min(20,body.length()))),0,Math.min(100,body.length())).getJSONObject("ownerSourceRef"));}
        JSONObject carrier(JSONArray refs,String instruction)throws Exception{JSONObject nativeMessage=save(TARGET,id(),1,instruction,refs);return new JSONObject().put("role","user").put("content",instruction).put("ownerMessageId",nativeMessage.getString("id")).put("ownerMessageRevision",1).put("ownerSourceRefs",OwnerSourceReferences.checked(refs));}
        JSONObject previewCarrier(JSONArray refs,JSONArray previews,String instruction)throws Exception{
            JSONObject message=save(TARGET,id(),1,instruction,refs,previews);
            return new JSONObject().put("role","user").put("content",instruction).put("ownerMessageId",message.getString("id")).put("ownerMessageRevision",1)
                .put("ownerSourceRefs",OwnerSourceReferences.checked(refs)).put("ownerPreviewRefs",dev.pocketengine.features.local.preview.PreviewElementRef.checked(previews));
        }
        RunControl control()throws Exception{JSONObject attempt=tasks.startAttempt(tasks.createCapabilityTask("仅主人的新要求",TARGET).getString("id"),"capabilities",8);RunControl control=new RunControl();control.bindHostRun(attempt.getString("id"),attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));tasks.openOwnerInputMailbox(attempt.getString("id"),attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),TARGET,control);bindings.put(control,new JSONObject().put("taskId",attempt.getString("id")).put("runId",attempt.getString("runId")).put("epoch",attempt.getInt("coordinatorEpoch")));return control;}
        OwnerSourceRequestProjection projection(RunControl control)throws Exception{return projection(control,null);}
        OwnerSourceRequestProjection projection(RunControl control,AttachmentRequestProjection attachments)throws Exception{
            JSONObject b=bindings.get(control);return new OwnerSourceRequestProjection(assistant,control,TARGET,attachments,(carriers,parts,live)->tasks.recordOwnerSourceExposure(b.getString("taskId"),b.getString("runId"),b.getInt("epoch"),live,carriers,parts));
        }
        JSONArray preparedRefs(RunControl control,JSONObject... rows)throws Exception{JSONObject b=bindings.get(control);JSONArray selected=new JSONArray();for(JSONObject row:rows)selected.put(new JSONObject().put("sourceId",row.getString("ownerMessageId")).put("revision",row.get("ownerMessageRevision")));return tasks.preparedConversationUserRefs(b.getString("taskId"),b.getString("runId"),b.getInt("epoch"),TARGET,selected);}
        RunControl resume(String task)throws Exception{
            JSONObject attempt=tasks.startAttempt(task,"capabilities",8);RunControl control=new RunControl();control.bindHostRun(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));tasks.openOwnerInputMailbox(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),TARGET,control);bindings.put(control,new JSONObject().put("taskId",task).put("runId",attempt.getString("runId")).put("epoch",attempt.getInt("coordinatorEpoch")));return control;
        }
        JSONObject queryOptions(String query)throws Exception{return new JSONObject().put("query",query).put("cursor","").put("limit",20).put("maxChars",16000).put("createdAfter",-1).put("createdBefore",-1);}
        TaskStoreMemoryReader.RawBinding raw(RunControl control,boolean budgetAware)throws Exception{
            JSONObject task=bindings.get(control);String id=task.getString("taskId"),run=task.getString("runId");int epoch=task.getInt("epoch");
            var binding=new TaskStoreMemoryReader.RawBinding(id,run,epoch,control,new MemoryHostFence(){public void check()throws Exception{tasks.requireCapabilityAttempt(id,control);}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}},host::memoryContextRevision);
            var history=history(binding);
            ConversationHistory.MessageProjection projection=budgetAware?new ConversationHistory.MessageProjection(){
                public JSONArray project(JSONArray messages,Set<String> delegated)throws Exception{return assistant.projectMemoryHistory(messages,delegated,TARGET);}
                public JSONArray project(JSONArray messages,Set<String> delegated,ConversationHistory.SourceBytes bytes)throws Exception{return assistant.projectMemoryHistory(messages,delegated,TARGET,bytes);}
            }:(messages,delegated)->assistant.projectMemoryHistory(messages,delegated,TARGET);
            binding.bindMessageCurrentness(ConversationHistory.currentness(history,remaining->tasks.openConversationHistoryProjection(history,remaining),projection));return binding;
        }
        ConversationHistory.HistoryBinding history(TaskStoreMemoryReader.RawBinding binding)throws Exception{return new ConversationHistory.HistoryBinding(binding,TARGET,new ConversationHistory.Source(){
                public ConversationHistory.Gate open(String cid)throws Exception{return assistant.openHistoryConversation(cid);}
                public JSONObject query(ConversationHistory.Gate gate,JSONObject options,RunControl live)throws Exception{return assistant.queryRetainedHistory(gate,options,live);}
                public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> refs)throws Exception{return assistant.inspectHistorySources(gate,refs);}
                public JSONObject read(ConversationHistory.Gate gate,SourceRef ref,int offset,int chars,RunControl live)throws Exception{return assistant.readHistoryMessage(gate,ref,offset,chars,live);}
            });
        }
        JSONObject nativeEcho(RunControl live,String text)throws Exception{return nativeEcho(live,text,1);}
        JSONObject nativeEcho(RunControl live,String text,int repeat)throws Exception{
            JSONObject b=bindings.get(live);String task=b.getString("taskId"),run=b.getString("runId");tasks.authorizeTools(task,run,new JSONArray().put("fixture_echo"));tasks.status(task,"running","fixture original operation");RunJournal journal=tasks.journal(task,run,b.getInt("epoch"),live);String op=journal.beginOperation("fixture_echo",new JSONObject().put("text",text).put("repeat",repeat));tasks.checkCapability(task,run,b.getInt("epoch"),"fixture_echo",live);
            java.io.File file=new java.io.File(android.getFilesDir(),"source-echo-"+op);echoFiles.add(file);Files.writeString(file.toPath(),text.repeat(repeat),StandardCharsets.UTF_8);String actual=Files.readString(file.toPath(),StandardCharsets.UTF_8);echoes.incrementAndGet();JSONObject receipt=new JSONObject().put("ok",true).put("accepted",true).put("operationId",op).put("stdout",actual);journal.finishOperation(op,receipt);return receipt;
        }
        ConversationHistory.SourceLocator historyRun(ConversationHistory.HistoryBinding caller,String sourceTask,String sourceRun)throws Exception{
            JSONObject tasksPage=tasks.queryConversationHistory(caller,null,"runs",queryOptions(sourceTask));ConversationHistory.SourceLocator task=locator(tasksPage.getJSONArray("entries").getJSONObject(0));
            JSONArray runs=tasks.queryConversationHistory(caller,task,"runs",queryOptions("")).getJSONArray("entries");for(int i=0;i<runs.length();i++)if(sourceRun.equals(runs.getJSONObject(i).getString("sourceRunId")))return locator(runs.getJSONObject(i));throw new AssertionError("native discovered source run absent");
        }
        JSONObject auditOperation(String task,String run,String op)throws Exception{
            JSONObject trace=nativeHost.handle("readTaskMemoryTrace",new JSONObject().put("taskId",task).put("runId",run).put("section","operations").put("query",op).put("cursor","").put("limit",20).put("maxChars",16000));assertEquals("current",trace.getString("status"));assertEquals(1,trace.getJSONArray("entries").length());return trace.getJSONArray("entries").getJSONObject(0);
        }
        JSONObject fact(String run)throws Exception{try(var row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"run.owner_source_context."+run})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
        void retire(JSONArray refs)throws Exception{assistant.retireMemoryContexts(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(refs.getJSONObject(0).getJSONObject("sourceRef")))),"owner_forget");}
        JSONObject formal(JSONArray refs)throws Exception{return memory.handle("importFromSources",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","受审阅的派生记忆").put("sourceRefs",new JSONArray().put(refs.getJSONObject(0).getJSONObject("sourceRef"))))).getJSONObject("record");}
        public void close()throws Exception{for(java.io.File file:echoFiles)Files.deleteIfExists(file.toPath());assistant.close();var f=AssistantModule.class.getDeclaredField("turns");f.setAccessible(true);assertTrue("owned assistant worker exited",((ExecutorService)f.get(assistant)).awaitTermination(5,TimeUnit.SECONDS));host.close();db.close();}
    }
    private static final class Server implements AutoCloseable {
        final HttpServer server;final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"owner-source-wire-fixture");t.setDaemon(true);return t;});
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final List<JSONObject> bodies=new CopyOnWriteArrayList<>();final String answer;final boolean scripted;final int toolReplies;final AtomicInteger replies=new AtomicInteger();
        Server(String answer)throws Exception{this(answer,false);}
        Server(String answer,boolean scripted)throws Exception{this(answer,scripted,scripted?1:0);}
        Server(String answer,boolean scripted,int toolReplies)throws Exception{this.answer=answer;this.scripted=scripted;this.toolReplies=toolReplies;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);server.createContext("/",exchange->{try{
            bodies.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("native fixture response gate timed out");
            JSONObject message=new JSONObject().put("role","assistant").put("content",this.answer);int reply=replies.getAndIncrement();boolean tool=this.scripted&&reply<toolReplies;
            if(tool)message=new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id","native-echo-call-"+reply).put("type","function").put("function",new JSONObject().put("name","fixture_echo").put("arguments",new JSONObject().put("text",CANARY).toString()))));
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",tool?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failed){throw new RuntimeException(failed);}finally{exchange.close();}});server.start();}
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}public void close(){release.countDown();server.stop(0);workers.shutdownNow();}
    }
    private static final class Flow implements AssistantModule.NativeInputHost,AgentRunner.OwnerInputs {
        final Fixture f;volatile JSONObject binding;volatile String conversation;volatile RunJournal journal;volatile RunOutcome outcome;final AtomicInteger starts=new AtomicInteger();
        final boolean echo;boolean explicitResume;RunControl original;Flow(Fixture f,boolean echo){this.f=f;this.echo=echo;}
        public JSONObject start(String cid,String goal,JSONObject message,ModelConfig config,RunControl original)throws Exception{
            starts.incrementAndGet();JSONObject attempt=f.tasks.startAttempt(f.tasks.createCapabilityTask(goal,cid).getString("id"),"capabilities",config.maxSteps);String task=attempt.getString("id"),run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");
            original.bindHostRun(task,run,epoch);this.original=original;if(echo)f.tasks.authorizeTools(task,run,new JSONArray().put("fixture_echo"));f.tasks.openOwnerInputMailbox(task,run,epoch,cid,original);f.tasks.appendOwnerGoalInput(task,run,epoch,cid,message,"queue",original);conversation=cid;binding=new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch);journal=f.tasks.journal(task,run,epoch,original);f.bindings.put(original,new JSONObject(binding.toString()));return new JSONObject(binding.toString());
        }
        public JSONObject append(String cid,JSONObject message,String mode,JSONObject expected,RunControl original)throws Exception{f.tasks.appendOwnerInput(expected.getString("taskId"),expected.getString("runId"),expected.getInt("epoch"),cid,message,mode,original);return new JSONObject(expected.toString());}
        public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl original)throws Exception{return f.tasks.pollOwnerInputs(binding.getString("taskId"),binding.getString("runId"),binding.getInt("epoch"),included,boundary,close,original);}
        public void completed(AgentRunner.OwnerReply reply,RunControl original)throws Exception{if(!explicitResume)f.assistant.acceptNativeReply(conversation,binding,reply,original);}
        JSONObject execute(String cid,String goal,JSONArray initial,ModelConfig config,RunControl original)throws Exception{
            journal.checkpoint(initial,0,new JSONObject());CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return echo?new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","fixture_echo").put("description","Only a private fixture file echo; no external side effect").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("text",new JSONObject().put("type","string"))).put("required",new JSONArray().put("text")).put("additionalProperties",false)))):new JSONArray();}
                public boolean supports(String name){return echo&&"fixture_echo".equals(name);}public boolean sideEffecting(String name){return supports(name);}
                public JSONObject execute(String name,JSONObject args,RunControl c)throws Exception{
                    if(!supports(name))throw new SecurityException("no fixture tool");String task=binding.getString("taskId"),run=binding.getString("runId"),op=args.getString("_hostOperationId");f.tasks.checkCapability(task,run,binding.getInt("epoch"),name,c);
                    try(var row=f.db.rawQuery("SELECT arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool=? AND state='prepared'",new String[]{task,run,op,name})){assertTrue("real native operation prepared before file effect",row.moveToFirst());assertEquals(args.getString("text"),new JSONObject(row.getString(0)).getString("text"));}
                    java.io.File file=new java.io.File(f.android.getFilesDir(),"source-echo-"+op);f.echoFiles.add(file);Files.writeString(file.toPath(),args.getString("text"),StandardCharsets.UTF_8);String actual=Files.readString(file.toPath(),StandardCharsets.UTF_8);f.echoes.incrementAndGet();return new JSONObject().put("ok",true).put("accepted",true).put("operationId",op).put("stdout",actual).put("fixtureFileEffect",true);
                }
            };
            try(var sources=f.projection(original,f.attachments)){
                AgentRunner.RequestProjection projection=new AgentRunner.RequestProjection(){
                    public JSONArray project(JSONArray history,ModelConfig selected,RunControl live)throws Exception{Set<String> delegated=new HashSet<>();JSONArray rows=f.tasks.get(binding.getString("taskId"),false).optJSONArray("ownerInputs");for(int i=0;rows!=null&&i<rows.length();i++)delegated.add(rows.getJSONObject(i).getString("messageId"));JSONArray current=f.assistant.projectMemoryHistory(history,delegated,cid);current=f.tasks.projectConversationHistoryReferences(current,cid);current=f.tasks.projectOwnerSourceContext(binding.getString("taskId"),binding.getString("runId"),binding.getInt("epoch"),live,current);return sources.project(current,selected,live);}
                    public void prepared(JSONArray original,JSONArray actual,ModelConfig selected,RunControl live)throws Exception{sources.prepared(original,actual,selected,live);}
                    public void derivedPrepared(JSONArray original,RunControl live)throws Exception{sources.derivedPrepared(original,live);}
                };
                outcome=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource(),projection,this).run(binding.getString("taskId"),goal,config,original,event->{try{f.tasks.event(binding.getString("taskId"),binding.getString("runId"),binding.getInt("epoch"),event);}catch(Exception failed){throw new IllegalStateException(failed);}},journal);
            }
            f.tasks.endAttempt(binding.getString("taskId"),binding.getString("runId"),outcome.status,outcome.summary,outcome.evidence);return new JSONObject().put("taskId",binding.getString("taskId")).put("content",outcome.evidence.optString("modelSummary",outcome.summary));
        }
    }
    private static void idle(AssistantModule assistant)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<until){if(!assistant.snapshot().getBoolean("running"))return;Thread.sleep(5);}throw new AssertionError("original assistant remained active");}

    @Test public void previewCarrierRequiresExactNativeSelectionAndOriginalConversation()throws Exception{try(Fixture f=new Fixture()){
        JSONObject owner=f.previewCarrier(new JSONArray(),previewMetadata(),"只改原按钮的文字");
        try(var check=f.repo.inspectOwnerSources()){check.requirePreviewCarrier(owner,TARGET);}
        JSONObject changed=new JSONObject(owner.toString());changed.getJSONArray("ownerPreviewRefs").getJSONObject(0).put("text","forged DOM");
        assertThrows(SecurityException.class,()->{try(var check=f.repo.inspectOwnerSources()){check.requirePreviewCarrier(changed,TARGET);}});
        assertThrows(SecurityException.class,()->{try(var check=f.repo.inspectOwnerSources()){check.requirePreviewCarrier(owner,SOURCE);}});
        assertThrows(SecurityException.class,()->{try(var check=f.repo.inspectOwnerSources()){check.requirePreviewCarrier(new JSONObject(owner.toString()).put("role","assistant"),TARGET);}});
        f.save(TARGET,owner.getString("ownerMessageId"),2,"新版主人消息",new JSONArray(),owner.getJSONArray("ownerPreviewRefs"));
        assertThrows(SecurityException.class,()->{try(var check=f.repo.inspectOwnerSources()){check.requirePreviewCarrier(owner,TARGET);}});
    }}
    @Test public void originalQuoteAndPreviewCarrierRechecksRemainIndependentAtBatchClose()throws Exception{try(Fixture f=new Fixture()){
        JSONArray quotes=f.source("被独立选择的原文范围");JSONObject owner=f.previewCarrier(quotes,previewMetadata(),"同时参考原文与按钮");
        var check=f.repo.inspectOwnerSources();check.requireCarrier(owner,TARGET);check.requirePreviewCarrier(owner,TARGET);
        JSONArray changedQuotes=new JSONArray(quotes.toString());changedQuotes.getJSONObject(0).put("chars",1);
        // Same native message metadata changes between the two reads: checking DOM last
        // must not overwrite the earlier quote dependency in this same real SQL batch.
        f.save(TARGET,owner.getString("ownerMessageId"),1,owner.getString("content"),changedQuotes,owner.getJSONArray("ownerPreviewRefs"));
        assertThrows(SecurityException.class,check::close);
        var previewCheck=f.repo.inspectOwnerSources();JSONObject current=new JSONObject(owner.toString()).put("ownerSourceRefs",changedQuotes);
        previewCheck.requirePreviewCarrier(current,TARGET);previewCheck.requireCarrier(current,TARGET);
        JSONArray changedPreviews=new JSONArray(owner.getJSONArray("ownerPreviewRefs").toString());changedPreviews.getJSONObject(0).put("text","changed DOM");
        f.save(TARGET,owner.getString("ownerMessageId"),1,owner.getString("content"),changedQuotes,changedPreviews);
        assertThrows(SecurityException.class,previewCheck::close);
    }}
    @Test public void savedPreviewJsonAloneCannotReachAModelRequest()throws Exception{try(Fixture f=new Fixture()){
        JSONObject owner=f.previewCarrier(new JSONArray(),previewMetadata(),"只改原按钮");RunControl control=f.control();
        try(var projection=f.projection(control)){
            assertThrows(IllegalStateException.class,()->projection.project(new JSONArray().put(owner),config(),control));
        }
        assertEquals(1,f.repo.message(TARGET,owner.getString("ownerMessageId")).getJSONArray("ownerPreviewRefs").length());
    }}
    @Test public void coldPreviewProjectionRetiresDerivedContentButKeepsAuditAndNewOwnerBoundary()throws Exception{try(Fixture f=new Fixture()){
        JSONObject owner=f.previewCarrier(new JSONArray(),previewMetadata(),"原按钮要求"),next=f.carrier(new JSONArray(),"独立的新要求");
        JSONArray raw=new JSONArray().put(owner).put(new JSONObject().put("role","assistant").put("content",CANARY).put("reasoning_content",CANARY)
            .put("provider_data",new JSONObject().put("text",CANARY)).put("tool_calls",new JSONArray().put(new JSONObject().put("id","preview-call").put("type","function").put("function",new JSONObject().put("name","project_read_file").put("arguments",new JSONObject().put("old",CANARY).toString())))))
            .put(new JSONObject().put("role","tool").put("tool_call_id","preview-call").put("content",CANARY))
            .put(next).put(new JSONObject().put("role","assistant").put("content","新要求的独立答复"));
        JSONArray projected=new JSONArray(raw.toString());OwnerSourceRequestProjection.projectCurrentness(f.assistant,projected,TARGET,null);
        assertFalse(projected.getJSONObject(0).has("ownerPreviewRefs"));assertTrue(projected.getJSONObject(0).has("ownerPreviewRefsUnavailable"));
        assertEquals(TaskMemoryHistory.RETIRED,projected.getJSONObject(1).getString("content"));assertFalse(projected.getJSONObject(1).has("provider_data"));assertFalse(projected.getJSONObject(1).has("reasoning_content"));
        assertEquals("{}",projected.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"));
        assertFalse(projected.getJSONObject(2).getString("content").contains(CANARY));assertEquals("新要求的独立答复",projected.getJSONObject(4).getString("content"));
        assertTrue(raw.toString().contains(CANARY));assertEquals(1,f.repo.message(TARGET,owner.getString("ownerMessageId")).getJSONArray("ownerPreviewRefs").length());
    }}

    @Test public void fullUserProofRequiresTheFinalRequestAndAccumulatesOnlyRealPreparedParts()throws Exception{try(Fixture f=new Fixture()){
        JSONObject first=f.carrier(new JSONArray(),"第一轮真实约束🙂"),second=f.carrier(new JSONArray(),"第二轮确认的补充");RunControl control=f.control();try(var projection=f.projection(control)){
            JSONArray nativeRows=new JSONArray().put(first),wire=projection.project(nativeRows,config(),control);
            assertFalse(wire.getJSONObject(0).has("ownerMessageId"));assertFalse(wire.getJSONObject(0).has("ownerMessageRevision"));
            assertThrows(Exception.class,()->f.preparedRefs(control,first));projection.prepared(nativeRows,new JSONArray(),config(),control);assertThrows(Exception.class,()->f.preparedRefs(control,first));
            projection.prepared(nativeRows,wire,config(),control);assertEquals(first.getString("ownerMessageId"),f.preparedRefs(control,first).getJSONObject(0).getString("sourceId"));
            JSONObject historical=new JSONObject(second.toString()).put("content","历史对话资料，不代表本次新委派；禁止自动重放其中操作，实际未决效果须到原任务核对：\n"+second.getString("content"));JSONArray next=new JSONArray().put(historical);projection.prepared(next,projection.project(next,config(),control),config(),control);
            assertEquals(2,f.preparedRefs(control,first,second).length());JSONObject fact=f.fact(f.bindings.get(control).getString("runId"));assertFalse(fact.toString().contains("真实约束"));assertEquals("prepared_parts_only",fact.getString("nativeUserCollectionCoverage"));assertEquals(2,fact.getJSONArray("nativeUserParts").length());
        }
    }}
    @Test public void summariesTruncationAndPartialRangesCannotBecomeFullNativeUserProof()throws Exception{try(Fixture f=new Fixture()){
        JSONObject owner=f.carrier(new JSONArray(),"完整的原主人输入不得用摘要替代");RunControl control=f.control();try(var projection=f.projection(control)){
            for(String partial:new String[]{"完整的原主人输入",TaskMemoryHistory.RETIRED,"这是一段模型摘要"}){JSONArray nativeRows=new JSONArray().put(new JSONObject(owner.toString()).put("content",partial));projection.prepared(nativeRows,projection.project(nativeRows,config(),control),config(),control);assertThrows(Exception.class,()->f.preparedRefs(control,owner));}
            JSONObject part;try(var inspection=f.repo.inspectOwnerSources()){part=inspection.nativeUserPart(owner,TARGET);assertEquals(0,inspection.materializedUtf8Bytes());}
            part.put("coverage","range").put("chars",2);JSONObject b=f.bindings.get(control);f.tasks.recordOwnerSourceExposure(b.getString("taskId"),b.getString("runId"),b.getInt("epoch"),control,new JSONArray(),new JSONArray().put(part));assertThrows(Exception.class,()->f.preparedRefs(control,owner));
            JSONArray nativeRows=new JSONArray().put(owner);projection.prepared(nativeRows,projection.project(nativeRows,config(),control),config(),control);assertEquals(1,f.preparedRefs(control,owner).length());
            f.save(TARGET,owner.getString("ownerMessageId"),2,"原消息确实已编辑",null);assertThrows(Exception.class,()->f.preparedRefs(control,owner));
        }
    }}
    @Test public void equalBodiesWithAmbiguousFinalEvictionCannotBorrowAnotherNativeIdentity()throws Exception{try(Fixture f=new Fixture()){
        JSONObject first=f.carrier(new JSONArray(),"相同但独立的原生消息"),second=f.carrier(new JSONArray(),"相同但独立的原生消息");RunControl control=f.control();try(var projection=f.projection(control)){
            JSONArray original=new JSONArray().put(first).put(second),wire=projection.project(original,config(),control);projection.prepared(original,new JSONArray().put(wire.getJSONObject(1)),config(),control);
            assertThrows(Exception.class,()->f.preparedRefs(control,first));assertThrows(Exception.class,()->f.preparedRefs(control,second));projection.prepared(original,wire,config(),control);assertEquals(2,f.preparedRefs(control,first,second).length());
        }
    }}
    @Test public void pauseDeleteAndDifferentConversationRejectFinalNativeSourcePublication()throws Exception{try(Fixture f=new Fixture()){
        JSONObject owner=f.carrier(new JSONArray(),"只属于原请求"),foreign=f.save(SOURCE,id(),1,"别的会话原话",null);RunControl control=f.control();try(var projection=f.projection(control)){
            JSONArray original=new JSONArray().put(owner),wire=projection.project(original,config(),control);control.pause();control.resume();assertThrows(Exception.class,()->projection.prepared(original,wire,config(),control));assertThrows(Exception.class,()->f.preparedRefs(control,owner));
            projection.project(original,config(),control);JSONObject foreignRow=new JSONObject().put("role","user").put("content",foreign.getString("content")).put("ownerMessageId",foreign.getString("id")).put("ownerMessageRevision",1);JSONArray foreignInput=new JSONArray().put(foreignRow);projection.prepared(foreignInput,projection.project(foreignInput,config(),control),config(),control);assertThrows(Exception.class,()->f.preparedRefs(control,foreignRow));
            JSONArray finalWire=projection.project(original,config(),control);f.assistant.handle("delete",new JSONObject().put("conversationId",TARGET));assertThrows(Exception.class,()->projection.prepared(original,finalWire,config(),control));
        }
    }}

    @Test public void visibleMessageQuoteUsesTheOriginalLocatorAndRejectsChangedRevision()throws Exception{try(Fixture f=new Fixture()){
        String messageId=id(),body="原话甲🙂乙";f.save(SOURCE,messageId,7,body,null);
        JSONObject visible=f.assistant.readOriginalConversation(SOURCE,"",80).getJSONArray("messages").getJSONObject(0);
        JSONObject locator=visible.getJSONObject("ownerOriginalLocator");
        assertEquals(messageId,locator.getString("recordId"));assertEquals(7,locator.getLong("recordVersion"));assertEquals(SOURCE,locator.getString("conversationId"));
        assertEquals("sha256:"+ConversationRepository.shaBytes(body.getBytes(StandardCharsets.UTF_8)),locator.getString("fingerprint"));
        JSONObject receipt=f.nativeHost.handle("selectOwnerOriginalReference",new JSONObject().put("ownerLocator",locator).put("offset",0).put("chars",body.length()),new RunControl());
        assertTrue(receipt.getBoolean("selectionValidated"));assertFalse(receipt.getBoolean("modelCalled"));assertFalse(receipt.getBoolean("executionAuthorized"));assertFalse(receipt.getBoolean("formalMemorySaved"));
        assertEquals(body.length(),receipt.getJSONObject("ownerSourceRef").getInt("chars"));
        assertFalse(f.repo.message(SOURCE,messageId).has("ownerOriginalLocator"));
        f.save(SOURCE,messageId,8,"修订后的原话",null);
        assertThrows(Exception.class,()->f.nativeHost.handle("selectOwnerOriginalReference",new JSONObject().put("ownerLocator",locator).put("offset",0).put("chars",body.length()),new RunControl()));
    }}
    @Test public void nativeSelectionIsMetadataOnlyAndDoesNotWidenModelHistorySearch()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject ref=refs.getJSONObject(0);assertEquals(9_000_000_000L,ref.getJSONObject("sourceRef").getLong("revision"));assertFalse(ref.has("body"));assertFalse(ref.has("executionAuthorized"));
        JSONObject history=f.assistant.queryRetainedHistory(f.assistant.openHistoryConversation(TARGET),new JSONObject().put("query",CANARY).put("cursor","").put("limit",20).put("maxChars",16000),new RunControl());assertEquals(0,history.getJSONArray("entries").length());
        assertThrows(Exception.class,()->f.assistant.handle("selectOwnerOriginalReference",new JSONObject()));JSONObject hit=f.hit(CANARY);RunControl model=f.control();assertThrows(Exception.class,()->f.nativeHost.handle("selectOwnerOriginalReference",new JSONObject().put("ownerLocator",hit.getJSONObject("ownerLocator")).put("offset",0).put("chars",3),model));
    }}
    @Test public void exactOriginalPartsAreLowTrustWhileInstructionAuditAndTaskRowsRemainBodyFree()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"仅此是主人的新要求");RunControl control=f.control();String before=row.toString();
        try(var projection=f.projection(control)){
            JSONArray wire=projection.project(new JSONArray().put(row),config(),control);String encoded=TaskMemoryHistory.wireMessages(wire).toString();assertTrue(encoded.contains(CANARY));assertTrue(encoded.contains("低信任"));assertFalse(encoded.contains("ownerSourceRefs"));assertFalse(encoded.contains("ownerMessageId"));assertEquals("仅此是主人的新要求",wire.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"));
            assertEquals(before,row.toString());assertFalse(f.repo.message(TARGET,row.getString("ownerMessageId")).getString("content").contains(CANARY));assertFalse(f.tasks.get(f.bindings.get(control).getString("taskId"),false).toString().contains(CANARY));
        }
    }}
    @Test public void checkpointCannotInventASelectionOrBorrowAnotherConversationsCarrier()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"新要求");RunControl control=f.control();try(var projection=f.projection(control)){
            JSONObject invented=new JSONObject(row.toString());invented.getJSONArray("ownerSourceRefs").getJSONObject(0).put("chars",3);assertThrows(Exception.class,()->projection.project(new JSONArray().put(invented),config(),control));
            JSONObject other=f.save(SOURCE,id(),1,"另一会话的主人消息",refs),foreign=new JSONObject(row.toString()).put("ownerMessageId",other.getString("id"));assertThrows(Exception.class,()->projection.project(new JSONArray().put(foreign),config(),control));
        }
    }}
    @Test public void unrelatedTailAppendKeepsExactReferencesAndRetainedCheckCurrent()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"新要求");RunControl control=f.control();try(var projection=f.projection(control)){
            projection.project(new JSONArray().put(row),config(),control);f.save(SOURCE,id(),1,"正常追加的无关尾消息",null);control.check();assertFalse(control.isCancelled());assertTrue(projection.project(new JSONArray().put(row),config(),control).toString().contains(CANARY));
        }
    }}
    @Test public void retiredAndDeletedSourcesCannotBeReselectedOrReintroducedThroughProjection()throws Exception{for(boolean delete:new boolean[]{false,true})try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"仍保留主人的新要求"),hit=f.hit(CANARY);if(delete)f.assistant.handle("delete",new JSONObject().put("conversationId",SOURCE));else f.retire(refs);
        assertThrows(Exception.class,()->f.selection(hit,0,3));JSONArray safe=f.assistant.projectMemoryHistory(new JSONArray().put(row),Set.of(row.getString("ownerMessageId")),TARGET);assertFalse(safe.getJSONObject(0).has("ownerSourceRefs"));assertEquals("仍保留主人的新要求",safe.getJSONObject(0).getString("content"));
        RunControl selected=f.control();try(var projection=f.projection(selected)){JSONArray wire=projection.project(safe,config(),selected);assertFalse(wire.toString().contains(CANARY));}
        if(!delete)assertEquals("retired_for_derived_context",f.nativeHost.handle("readOwnerOriginalSearchHit",new JSONObject().put("ownerLocator",hit.getJSONObject("ownerLocator")).put("offset",0).put("maxChars",64),new RunControl()).getString("derivedUseStatus"));
    }}
    @Test public void invalidReferenceSegmentMasksDerivedProseToolArgumentsAndResultsButNotNextNativeInput()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"旧阶段的新要求"),next=f.carrier(new JSONArray(),"下一条真实主人新要求");next.remove("ownerSourceRefs");JSONObject call=new JSONObject().put("id","old-call").put("type","function").put("function",new JSONObject().put("name","fixture_read").put("arguments",new JSONObject().put("text",CANARY).toString()));
        JSONArray history=new JSONArray().put(row).put(new JSONObject().put("role","assistant").put("content",CANARY).put("provider_data",CANARY).put("reasoning_content",CANARY).put("tool_calls",new JSONArray().put(call))).put(new JSONObject().put("role","tool").put("tool_call_id","old-call").put("content",CANARY)).put(next).put(new JSONObject().put("role","assistant").put("content","新阶段没有旧原话的答复"));String audit=history.toString();f.retire(refs);
        JSONArray safe=f.assistant.projectMemoryHistory(history,Set.of(row.getString("ownerMessageId"),next.getString("ownerMessageId")),TARGET);assertFalse(safe.toString().contains(CANARY));assertEquals("{}",safe.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"));assertEquals("新阶段没有旧原话的答复",safe.getJSONObject(4).getString("content"));assertEquals(audit,history.toString());
    }}
    @Test public void actualHashRevisionAndContainerAreRecheckedNotJustSelectionMetadata()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"新要求");RunControl control=f.control();try(var projection=f.projection(control)){
            projection.project(new JSONArray().put(row),config(),control);JSONObject ref=refs.getJSONObject(0);f.db.execSQL("UPDATE assistant_messages SET body_sha=? WHERE source_id=?",new Object[]{"sha256:"+"b".repeat(64),ref.getJSONObject("sourceRef").getString("sourceId")});assertThrows(Exception.class,control::check);assertTrue(control.isCancelled());
        }
    }}
    @Test public void deletingCarrierConversationRevokesInflightReferenceEvenWhenForeignOriginalStillExists()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"当前要求");RunControl control=f.control();try(var projection=f.projection(control)){
            projection.project(new JSONArray().put(row),config(),control);f.assistant.handle("delete",new JSONObject().put("conversationId",TARGET));assertThrows(Exception.class,control::check);assertTrue(control.isCancelled());assertEquals(1,f.assistant.readOriginalConversation(SOURCE,"",80).getJSONArray("messages").length());
        }
    }}
    @Test public void pauseDuringExactReadRejectsLateBodyAndFreshNativeReadAfterResumeWorks()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY+"x".repeat(600000));RunControl control=f.control();AtomicInteger checks=new AtomicInteger();control.setAuthorityCheck(()->{if(checks.incrementAndGet()==2){control.pause();control.resume();}});
        try(var inspection=f.repo.inspectOwnerSources()){assertThrows(Exception.class,()->inspection.readRange(refs.getJSONObject(0),control));}assertFalse(control.isCancelled());control.setAuthorityCheck(()->{});
        try(var inspection=f.repo.inspectOwnerSources()){JSONObject page=inspection.readRange(refs.getJSONObject(0),control);assertTrue(page.getString("body").startsWith(CANARY));assertEquals("verified_native_SQL_substr_blocks",page.getString("readMode"));assertTrue(page.getLong("applicationMaterializedSqlBlockUtf8Bytes")<50000);}
    }}
    @Test public void metadataCurrentnessDoesNotAcquireTaskStoreMonitorOrReadBodies()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);AtomicReference<Throwable> failed=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);Thread reader=new Thread(()->{try(var inspection=f.repo.inspectOwnerSources()){inspection.requireSource(refs.getJSONObject(0));assertEquals(0,inspection.materializedUtf8Bytes());}catch(Throwable failure){failed.set(failure);}finally{done.countDown();}},"source-metadata-no-task-monitor");
        synchronized(f.tasks){reader.start();assertTrue("metadata read cannot wait for TaskStore monitor",done.await(1,TimeUnit.SECONDS));}reader.join(1000);assertNull(failed.get());
    }}
    @Test public void importedUnknownRevisionAndRetiredSourceNeverBecomeModelSourceRefs()throws Exception{byte[] bytes="[{\"messages\":[{\"role\":\"user\",\"content\":\"UNKNOWN-IMPORTED-ORIGINAL\"}]}]".getBytes(StandardCharsets.UTF_8);try(Fixture f=new Fixture(null,bytes)){
        JSONObject review=f.assistant.reviewOriginalHistoryMigration();f.assistant.activateOriginalHistoryMigration(review.getString("migrationTaskId"),review.getString("sourceSha256"),true);JSONObject hit=f.hit("UNKNOWN-IMPORTED-ORIGINAL");assertTrue(hit.getBoolean("importAssignedIdentity"));assertThrows(Exception.class,()->f.selection(hit,0,3));
    }}
    @Test public void actualMailboxDedupIncludesRangesAndKeepsLegacyEmptyReferenceHash()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"新要求");RunControl control=f.control();String task=f.bindings.get(control).getString("taskId"),run=f.bindings.get(control).getString("runId");int epoch=f.bindings.get(control).getInt("epoch");JSONObject nativeMessage=f.repo.message(TARGET,row.getString("ownerMessageId")).put("clientMessageId",id());
        JSONObject accepted=f.tasks.appendOwnerInput(task,run,epoch,TARGET,nativeMessage,"queue",control);assertEquals(1,accepted.getJSONArray("ownerSourceRefs").length());assertTrue(f.tasks.appendOwnerInput(task,run,epoch,TARGET,nativeMessage,"queue",control).getBoolean("duplicate"));JSONObject changed=new JSONObject(nativeMessage.toString());changed.getJSONArray("ownerSourceRefs").getJSONObject(0).put("chars",3);assertThrows(SecurityException.class,()->f.tasks.appendOwnerInput(task,run,epoch,TARGET,changed,"queue",control));
        JSONObject plain=new JSONObject().put("id",id()).put("clientMessageId",id()).put("content","原普通输入");f.tasks.appendOwnerInput(task,run,epoch,TARGET,plain,"queue",control);assertTrue(f.tasks.appendOwnerInput(task,run,epoch,TARGET,new JSONObject(plain.toString()).put("ownerSourceRefs",new JSONArray()),"queue",control).getBoolean("duplicate"));assertEquals(2,f.tasks.get(task,false).getJSONArray("ownerInputs").length());assertFalse(f.tasks.get(task,false).toString().contains(CANARY));
    }}
    @Test public void rawAndH4SharedCurrentnessChargesActualCarrierUtf8AndRejectsNearFullOriginalRow()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject carrier=f.carrier(refs,"本次明确范围");f.repo.transaction(()->{JSONObject metadata=f.repo.message(TARGET,carrier.getString("ownerMessageId")).put("nativeNote","汉字🙂");f.repo.saveMessage(TARGET,metadata);return null;});RunControl control=f.control();JSONObject binding=f.bindings.get(control);String task=binding.getString("taskId"),run=binding.getString("runId");int epoch=binding.getInt("epoch");
        JSONArray messages=new JSONArray().put(carrier).put(new JSONObject().put("role","assistant").put("content","有当前来源支持的原审计助手行"));RunJournal journal=f.tasks.journal(task,run,epoch,control);journal.checkpoint(messages,0,new JSONObject());
        var raw=f.raw(control,true);JSONObject citation=new JSONObject().put("sourceId",TaskMemoryTrace.rawMessageId(task,run,1)).put("revision",TaskMemoryTrace.rawFingerprint(messages.getJSONObject(1))).put("offset",0).put("maxChars",64);
        JSONObject result=f.tasks.readMemoryRaw(raw,citation);assertEquals("current",result.getString("status"));JSONObject budget=result.getJSONObject("readBudget"),gate=budget.getJSONObject("currentnessBudget");assertTrue(gate.getLong("guardExtraProjectionSourceUtf8Bytes")>0);assertEquals(gate.getLong("guardExtraProjectionSourceUtf8Bytes")+gate.getLong("guardOperationContextUtf8Bytes"),gate.getLong("guardSourceUtf8Bytes"));assertTrue(gate.getLong("guardOperationContextUtf8Bytes")>0);try(var metadata=f.db.rawQuery("SELECT length(CAST(meta_json AS BLOB)) FROM assistant_messages WHERE source_id=?",new String[]{carrier.getString("ownerMessageId")})){metadata.moveToFirst();assertEquals(4*metadata.getLong(0),gate.getLong("guardExtraProjectionSourceUtf8Bytes"));}assertTrue(budget.getLong("scannedUtf8Bytes")<=TaskStoreMemoryQuery.RAW_MAX_BYTES);
        int base=new JSONObject().put("messages",messages).put("steps",0).put("observation",new JSONObject()).toString().getBytes(StandardCharsets.UTF_8).length;
        int old=messages.getJSONObject(1).getString("content").getBytes(StandardCharsets.UTF_8).length;messages.getJSONObject(1).put("content","x".repeat(TaskStoreMemoryQuery.RAW_MAX_BYTES-base+old-1));journal.checkpoint(messages,0,new JSONObject());
        try(var exact=f.db.rawQuery("SELECT length(CAST(checkpoint AS BLOB)) FROM runs WHERE run_id=?",new String[]{run})){assertTrue(exact.moveToFirst());assertEquals(TaskStoreMemoryQuery.RAW_MAX_BYTES-1,exact.getLong(0));}
        citation.put("revision",TaskMemoryTrace.rawFingerprint(messages.getJSONObject(1)));JSONObject denied=f.tasks.readMemoryRaw(raw,citation);assertNotEquals("current",denied.getString("status"));assertFalse(denied.has("body"));assertTrue(denied.getString("reason").contains("owner_source_carrier_metadata_exceeds_batch_bytes"));
    }}
    @Test public void oldProjectionProviderCannotHideExtraBytesAndFinalCloseRechecksSourceRetirement()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject carrier=f.carrier(refs,"当前新要求");RunControl control=f.control();JSONObject nativeBinding=f.bindings.get(control);String task=nativeBinding.getString("taskId"),run=nativeBinding.getString("runId");JSONArray messages=new JSONArray().put(carrier).put(new JSONObject().put("role","assistant").put("content","尚未退休的派生原行"));f.tasks.journal(task,run,nativeBinding.getInt("epoch"),control).checkpoint(messages,0,new JSONObject());
        var old=f.raw(control,false);JSONObject citation=new JSONObject().put("sourceId",TaskMemoryTrace.rawMessageId(task,run,1)).put("revision",TaskMemoryTrace.rawFingerprint(messages.getJSONObject(1)));
        JSONObject unavailable=f.tasks.readMemoryRaw(old,citation);assertNotEquals("current",unavailable.getString("status"));assertFalse(unavailable.has("body"));assertEquals("owner_source_projection_budget_unavailable",unavailable.getString("reason"));
        var current=f.raw(control,true);synchronized(f.tasks){long bytes;try(var row=f.db.rawQuery("SELECT length(CAST(checkpoint AS BLOB)) FROM runs WHERE run_id=?",new String[]{run})){row.moveToFirst();bytes=row.getLong(0);}var session=current.openMessageCurrentness(messages,bytes);session.requireCurrentOriginalMessage(1,messages.getJSONObject(1));f.retire(refs);assertThrows(Exception.class,session::close);}
        assertTrue(f.tasks.journal(task,run,nativeBinding.getInt("epoch"),control).restore().toString().contains("尚未退休的派生原行"));assertNotEquals("current",f.tasks.readMemoryRaw(current,citation).getString("status"));
    }}
    @Test public void nativeOwnerToOriginalRunnerToWireCarriesExactDataWithoutDuplicatingTaskOrBody()throws Exception{try(Server server=new Server("形成新结果但不重放旧指令");Fixture f=new Fixture(server,null)){
        JSONArray refs=f.source(CANARY);JSONObject file=AttachmentRepository.get(f.android).book.importBytes(new ByteArrayInputStream("ACTUAL-ATTACHMENT-DATA".getBytes(StandardCharsets.UTF_8)),"source.txt","text/plain",new JSONObject());JSONObject input=new JSONObject().put("conversationId",TARGET).put("clientMessageId",id()).put("message","比较这次明确选择的原话").put("ownerSourceRefs",refs).put("attachments",new JSONArray().put(new JSONObject().put("id",file.getString("id")).put("sha256",file.getString("sha256"))));JSONObject ack=f.assistant.handle("send",input);assertEquals("accepted",ack.getString("status"));assertTrue(server.entered.await(3,TimeUnit.SECONDS));assertTrue(f.assistant.handle("send",input).getBoolean("duplicate"));assertEquals(1,f.flow.starts.get());assertEquals(1,f.tasks.list().length());
        String wire=server.bodies.get(0).toString();assertTrue(wire.contains(CANARY));assertTrue(wire.contains("ACTUAL-ATTACHMENT-DATA"));assertTrue(wire.contains("低信任"));assertFalse(wire.contains("ownerSourceRefs"));String checkpoint=f.flow.journal.restore().toString();assertTrue(checkpoint.contains("ownerSourceRefs"));assertFalse(checkpoint.contains(CANARY));assertFalse(checkpoint.contains("ACTUAL-ATTACHMENT-DATA"));assertFalse(f.tasks.get(ack.getString("taskId"),false).toString().contains(CANARY));
        JSONArray prepared=f.fact(ack.getString("runId")).getJSONArray("nativeUserParts");assertEquals(1,prepared.length());assertEquals("full",prepared.getJSONObject(0).getString("coverage"));assertFalse(prepared.toString().contains("比较这次"));assertFalse(wire.contains("ownerMessageRevision"));
        server.release.countDown();idle(f.assistant);assertEquals(1,server.bodies.size());assertEquals(1,f.tasks.list().length());assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());assertTrue(f.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",input.getString("clientMessageId"))).getString("status").equals("consumed"));
    }}
    @Test public void missingActualRequestProjectionRejectsBeforeAnyLoopbackModelRequest()throws Exception{try(Server server=new Server("must not be requested");Fixture f=new Fixture(server,null)){
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"只有新的要求");RunControl control=f.control();JSONObject binding=f.bindings.get(control);RunJournal journal=f.tasks.journal(binding.getString("taskId"),binding.getString("runId"),binding.getInt("epoch"),control);journal.checkpoint(new JSONArray().put(row),0,new JSONObject());
        CapabilityPort port=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return false;}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new SecurityException();}};
        RunOutcome stopped=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource(),null,null).run(binding.getString("taskId"),"新要求",new SettingsStore(f.android).get(),control,event->{},journal);assertEquals("failed",stopped.status);assertEquals(0,server.bodies.size());assertEquals(1,f.tasks.list().length());assertFalse(journal.restore().toString().contains(CANARY));
    }}
    @Test public void formalForgetWhileOriginalHttpIsInFlightBlocksLateAnswerAndDoesNotDeadlockOrResurrect()throws Exception{try(Server server=new Server("LATE-ANSWER-MUST-NOT-PUBLISH");Fixture f=new Fixture(server,null)){
        JSONArray refs=f.source(CANARY);JSONObject record=f.formal(refs);JSONObject input=new JSONObject().put("conversationId",TARGET).put("clientMessageId",id()).put("message","使用这次明确选中的原话").put("ownerSourceRefs",refs),ack=f.assistant.handle("send",input);assertTrue(server.entered.await(3,TimeUnit.SECONDS));
        f.bridge.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));server.release.countDown();idle(f.assistant);assertEquals(1,server.bodies.size());assertFalse(f.assistant.readOriginalConversation(TARGET,"",80).toString().contains("LATE-ANSWER-MUST-NOT-PUBLISH"));assertThrows(Exception.class,f.flow.journal::restore);JSONObject audit=f.nativeHost.handle("readTaskMemoryTrace",new JSONObject().put("taskId",ack.getString("taskId")).put("runId",ack.getString("runId")).put("section","messages").put("query","").put("cursor","").put("limit",20).put("maxChars",16000));assertEquals("current",audit.getString("status"));assertTrue(audit.getJSONArray("entries").length()>0);assertFalse(audit.toString().contains("LATE-ANSWER-MUST-NOT-PUBLISH"));assertNotEquals("consumed",f.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",input.getString("clientMessageId"))).getString("status"));assertTrue(f.memory.isSourceForgotten(scope(),SourceRef.fromJson(refs.getJSONObject(0).getJSONObject("sourceRef"))));assertThrows(Exception.class,()->f.selection(f.hit(CANARY),0,3));assertEquals(1,f.tasks.get(ack.getString("taskId"),false).getJSONArray("ownerInputs").length());
    }}
    @Test public void preparedOriginalThenRealNativeEchoCannotReenterRawAfterCheckpointDropsCarrierAndForget()throws Exception{try(Fixture f=new Fixture()){
        JSONArray refs=f.source(CANARY);JSONObject carrier=f.carrier(refs,"保留这条新的主人要求");RunControl control=f.control();JSONObject b=f.bindings.get(control);String task=b.getString("taskId"),run=b.getString("runId");
        try(var projection=f.projection(control)){assertTrue(projection.project(new JSONArray().put(carrier),config(),control).toString().contains(CANARY));}
        JSONObject echo=f.nativeEcho(control,CANARY);assertEquals(CANARY,echo.getString("stdout"));assertEquals(1,f.echoes.get());assertTrue(f.fact(run).getBoolean("trackingComplete"));assertEquals("prepared_before_model_dispatch",f.fact(run).getString("exposureStage"));assertFalse(f.fact(run).toString().contains(CANARY));
        JSONArray evolved=new JSONArray().put(new JSONObject().put("role","user").put("content","保留这条新的主人要求")).put(new JSONObject().put("role","assistant").put("content",CANARY).put("tool_calls",new JSONArray().put(new JSONObject().put("id","evolved-call").put("type","function").put("function",new JSONObject().put("name","fixture_echo").put("arguments",new JSONObject().put("text",CANARY).toString()))))).put(new JSONObject().put("role","tool").put("tool_call_id","evolved-call").put("content",echo.toString()));
        RunJournal journal=f.tasks.journal(task,run,b.getInt("epoch"),control);journal.checkpoint(evolved,1,new JSONObject());assertFalse(journal.restore().toString().contains("ownerSourceRefs"));
        var before=f.raw(control,true);JSONObject entry=f.tasks.queryMemoryRaw(before,new JSONObject().put("section","operations").put("query",CANARY)).getJSONArray("entries").getJSONObject(0),citation=new JSONObject().put("sourceId",entry.getString("sourceId")).put("revision",entry.getString("revision")).put("offset",0).put("maxChars",16000);assertEquals("current",f.tasks.readMemoryRaw(before,citation).getString("status"));
        JSONObject formal=f.formal(refs);f.bridge.forgetReviewed(scope(),formal.getString("id"),formal.getLong("revision"));assertTrue(f.memory.isSourceForgotten(scope(),SourceRef.fromJson(refs.getJSONObject(0).getJSONObject("sourceRef"))));
        var fresh=f.raw(control,true);JSONObject query=f.tasks.queryMemoryRaw(fresh,new JSONObject().put("section","operations").put("query",CANARY));assertEquals("unavailable",query.getString("status"));assertEquals(OwnerSourceRunContext.UNAVAILABLE,query.getString("reason"));assertEquals(0,query.getJSONArray("entries").length());assertEquals("",query.getString("nextCursor"));JSONObject denied=f.tasks.readMemoryRaw(fresh,citation);assertEquals("unavailable",denied.getString("status"));assertFalse(denied.has("body"));
        assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->f.tasks.projectOwnerSourceContext(task,run,b.getInt("epoch"),control,evolved));assertEquals("保留这条新的主人要求",evolved.getJSONObject(0).getString("content"));
        JSONObject messageCitation=new JSONObject().put("sourceId",TaskMemoryTrace.rawMessageId(task,run,2)).put("revision",TaskMemoryTrace.rawFingerprint(evolved.getJSONObject(2)));assertNotEquals("current",f.tasks.readMemoryRaw(fresh,messageCitation).getString("status"));synchronized(f.tasks){assertThrows(Exception.class,()->{try(var gate=fresh.openMessageCurrentness(evolved)){gate.requireCurrentOriginalMessage(2,evolved.getJSONObject(2));}});}
        assertTrue(f.auditOperation(task,run,echo.getString("operationId")).toString().contains(CANARY));assertTrue(journal.restore().toString().contains(CANARY));assertEquals(1,f.fact(run).getJSONArray("carriers").length());
    }}
    @Test public void actualRunnerEchoThenNewRunInheritanceAndHistoricalReadsKeepForgottenOriginalUnavailable()throws Exception{try(Server server=new Server("新成果，不新增权限",true);Fixture f=new Fixture(server,null)){
        JSONArray refs=f.source(CANARY);JSONObject ack=f.assistant.handle("send",new JSONObject().put("conversationId",TARGET).put("clientMessageId",id()).put("message","比较原文后只做fixture文件回显").put("ownerSourceRefs",refs));assertTrue(server.entered.await(3,TimeUnit.SECONDS));server.release.countDown();idle(f.assistant);assertEquals(2,server.bodies.size());assertTrue(server.bodies.get(0).toString().contains(CANARY));assertTrue(server.bodies.get(1).toString().contains(CANARY));assertEquals(1,f.echoes.get());assertEquals("needs_confirmation",f.flow.outcome.status);assertTrue(f.flow.outcome.evidence.getBoolean("noDeviceObservation"));assertTrue(f.flow.outcome.summary.contains("外部效果仍以对应收据为准"));
        String sourceTask=ack.getString("taskId"),sourceRun=ack.getString("runId"),op=f.tasks.get(sourceTask,false).getJSONArray("operations").getJSONObject(0).getString("operationId");RunControl caller=f.control();var history=f.history(f.raw(caller,true));var selected=f.historyRun(history,sourceTask,sourceRun);JSONObject page=f.tasks.queryConversationHistory(history,selected,"operations",f.queryOptions(CANARY));assertEquals(1,page.getJSONArray("entries").length());var operation=locator(page.getJSONArray("entries").getJSONObject(0));assertEquals("current",f.tasks.readConversationHistoryOperation(history,operation,0,16000).getString("status"));JSONObject callerBinding=f.bindings.get(caller);assertEquals(1,f.fact(callerBinding.getString("runId")).getJSONArray("carriers").length());JSONObject secondEcho=f.nativeEcho(caller,CANARY);assertEquals(CANARY,secondEcho.getString("stdout"));
        JSONObject formal=f.formal(refs);f.bridge.forgetReviewed(scope(),formal.getString("id"),formal.getLong("revision"));JSONObject secondDenied=f.tasks.queryMemoryRaw(f.raw(caller,true),new JSONObject().put("section","operations").put("query",CANARY));assertEquals(OwnerSourceRunContext.UNAVAILABLE,secondDenied.getString("reason"));assertTrue(f.auditOperation(callerBinding.getString("taskId"),callerBinding.getString("runId"),secondEcho.getString("operationId")).toString().contains(CANARY));RunControl freshCaller=f.control();var after=f.history(f.raw(freshCaller,true));assertEquals("unavailable",f.tasks.queryConversationHistory(after,selected,"operations",f.queryOptions(CANARY)).getString("status"));JSONObject denied=f.tasks.readConversationHistoryOperation(after,operation,0,16000);assertEquals(OwnerSourceRunContext.UNAVAILABLE,denied.getString("reason"));assertFalse(denied.has("body"));assertThrows(Exception.class,()->f.tasks.requireConversationHistorySources(after,List.of(operation)));
        assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->f.resume(sourceTask));assertEquals(sourceRun,f.tasks.get(sourceTask,false).getString("runId"));assertTrue(f.auditOperation(sourceTask,sourceRun,op).toString().contains(CANARY));
    }}
    @Test public void unknownLegacyTrackingDoesNotBecomeKnownEmptyAfterCheckpointRewriteOrResume()throws Exception{try(Fixture f=new Fixture()){
        RunControl control=f.control();JSONObject b=f.bindings.get(control);String task=b.getString("taskId"),run=b.getString("runId");JSONObject echo=f.nativeEcho(control,"OLD-UNPROVEN-CONTEXT");f.db.delete("meta","key=?",new String[]{"run.owner_source_context."+run});f.tasks.journal(task,run,b.getInt("epoch"),control).checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content","主人新指令保留")).put(new JSONObject().put("role","assistant").put("content","OLD-UNPROVEN-CONTEXT")),1,new JSONObject());
        JSONObject denied=f.tasks.queryMemoryRaw(f.raw(control,true),new JSONObject().put("section","operations").put("query",""));assertEquals(OwnerSourceRunContext.UNAVAILABLE,denied.getString("reason"));assertTrue(f.auditOperation(task,run,echo.getString("operationId")).toString().contains("OLD-UNPROVEN-CONTEXT"));f.tasks.endAttempt(task,run,"awaiting_user","等待主人",new JSONObject());assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->f.resume(task));assertEquals(run,f.tasks.get(task,false).getString("runId"));assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->f.tasks.projectOwnerSourceContext(task,run,b.getInt("epoch"),control,f.tasks.journal(task,run,b.getInt("epoch"),control).restore().getJSONArray("messages")));
    }}
    @Test public void newNoSourceRunAndUnrelatedSourceTailStayReadableWithHonestSharedByteAccounting()throws Exception{try(Fixture f=new Fixture()){
        RunControl plain=f.control();JSONObject b=f.bindings.get(plain),echo=f.nativeEcho(plain,"UNRELATED-PLAIN-ORIGINAL");assertTrue(f.fact(b.getString("runId")).getBoolean("trackingComplete"));assertEquals(0,f.fact(b.getString("runId")).getJSONArray("carriers").length());JSONObject ordinary=f.tasks.queryMemoryRaw(f.raw(plain,true),new JSONObject().put("section","operations").put("query","UNRELATED-PLAIN-ORIGINAL"));assertEquals("current",ordinary.getString("status"));assertEquals(1,ordinary.getJSONArray("entries").length());assertTrue(ordinary.getJSONObject("pageCoverage").getLong("operationContextUtf8Bytes")>0);
        JSONArray refs=f.source(CANARY);JSONObject row=f.carrier(refs,"本次指令");RunControl selected=f.control();try(var projection=f.projection(selected)){projection.project(new JSONArray().put(row),config(),selected);}f.nativeEcho(selected,CANARY);f.save(SOURCE,id(),1,"正常新增尾消息不撤销旧范围",null);JSONObject current=f.tasks.queryMemoryRaw(f.raw(selected,true),new JSONObject().put("section","operations").put("query",CANARY));assertEquals("current",current.getString("status"));assertEquals(1,current.getJSONArray("entries").length());assertTrue(current.getJSONObject("pageCoverage").getLong("scannedUtf8Bytes")<=TaskStoreMemoryQuery.RAW_MAX_BYTES);
    }}

    @Test public void realSummaryPublicationDoesNotEraseRunProvenanceAndForgetRejectsRawRecoveryAndRepublish()throws Exception{try(Server server=new Server("已结算结果的低信任摘要");Fixture f=new Fixture(server,null)){
        JSONArray refs=f.source(CANARY);JSONObject carrier=f.carrier(refs,"主人本轮要求保留");RunControl control=f.control();JSONObject b=f.bindings.get(control);String task=b.getString("taskId"),run=b.getString("runId");try(var projection=f.projection(control)){projection.project(new JSONArray().put(carrier),config(),control);}
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","native fixture invariant")).put(new JSONObject().put("role","user").put("content","主人本轮要求保留"));
        for(int i=0;i<5;i++){String text=i==0?CANARY:"x";int repeat=9000/text.length();JSONObject receipt=f.nativeEcho(control,text,repeat);assertTrue(receipt.getString("stdout").length()>8900);String call="real-"+receipt.getString("operationId");messages.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id",call).put("type","function").put("function",new JSONObject().put("name","fixture_echo").put("arguments",new JSONObject().put("text",text).put("repeat",repeat).toString()))))).put(new JSONObject().put("role","tool").put("tool_call_id",call).put("content",receipt.toString()));}
        messages.put(new JSONObject().put("role","user").put("content","最新要求：不要扩大权限")).put(new JSONObject().put("role","assistant").put("content","当前尾部仍是原文"));assertFalse(messages.toString().contains("ownerSourceRefs"));
        var raw=f.raw(control,true);ModelConfig selected=new ModelConfig(server.base(),"","fixture-model",false,8,10,"openai_chat",1000);RunJournal base=f.tasks.journal(task,run,b.getInt("epoch"),control);var host=f.tasks.compactionJournal(task,run,b.getInt("epoch"),control,raw,()->selected);RunJournal journal=base.withCompaction(host);journal.checkpoint(messages,5,new JSONObject());
        dev.pocketengine.runtime.context.ContextWindow.Budget limits=new dev.pocketengine.runtime.context.ContextWindow.Budget(70000,1000,1000,100000,64,0);ContextBudgetPolicy.Source budgets=c->ContextBudgetPolicy.RouteBudget.forRoute(c,limits,ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);var compactor=new dev.pocketengine.runtime.context.ContextCompactor(journal,host,budgets);List<Integer> indexes=new ArrayList<>();for(int i=0;i<messages.length();i++)indexes.add(i);JSONArray tools=new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","fixture_echo").put("description","s".repeat(30000)).put("parameters",new JSONObject().put("type","object"))));server.release.countDown();
        var projected=compactor.request(messages,new JSONArray(messages.toString()),indexes,tools,selected,control,new dev.pocketengine.runtime.context.RunContextBudget(budgets));assertEquals(1,server.bodies.size());assertTrue(server.bodies.get(0).toString().contains(CANARY));JSONObject stored=journal.restore();assertTrue(stored.has("contextCompaction"));assertTrue(projected.plan().messages().toString().contains("派生"));assertEquals(1,f.fact(run).getJSONArray("carriers").length());assertFalse(stored.getJSONArray("messages").toString().contains("ownerSourceRefs"));
        JSONObject record=f.formal(refs);f.bridge.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));assertThrows(Exception.class,()->compactor.request(messages,new JSONArray(messages.toString()),indexes,tools,selected,control,new dev.pocketengine.runtime.context.RunContextBudget(budgets)));assertEquals(1,server.bodies.size());assertFalse(base.restore().has("contextCompaction"));assertTrue(base.restore().toString().contains(CANARY));var fresh=f.raw(control,true);assertEquals("unavailable",f.tasks.queryMemoryRaw(fresh,new JSONObject().put("section","operations").put("query",CANARY)).getString("status"));assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->f.tasks.projectOwnerSourceContext(task,run,b.getInt("epoch"),control,messages));
    }}

    @Test public void historyPreparedDependenciesUnionMonotonicallyWithoutCopyingBodiesOrChangingCallerScope()throws Exception{try(Fixture f=new Fixture()){
        JSONArray a=f.source(CANARY),other=f.source("SECOND-NATIVE-ORIGINAL");JSONObject aCarrier=f.carrier(a,"来源A的主人新要求"),bCarrier=f.carrier(other,"来源B的主人新要求");RunControl source=f.control(),caller=f.control();JSONObject sa=f.bindings.get(source),cb=f.bindings.get(caller);try(var p=f.projection(source)){p.project(new JSONArray().put(aCarrier),config(),source);}try(var p=f.projection(caller)){p.project(new JSONArray().put(bCarrier),config(),caller);}JSONObject echo=f.nativeEcho(source,CANARY);var h=f.history(f.raw(caller,true));var selected=f.historyRun(h,sa.getString("taskId"),sa.getString("runId"));
        for(int i=0;i<2;i++){JSONObject page=f.tasks.queryConversationHistory(h,selected,"operations",f.queryOptions(CANARY));assertEquals("current",page.getString("status"));assertEquals(1,page.getJSONArray("entries").length());assertTrue(page.getJSONObject("pageCoverage").getLong("operationContextUtf8Bytes")>0);}
        JSONObject fact=f.fact(cb.getString("runId"));assertTrue(fact.getBoolean("trackingComplete"));assertEquals(2,fact.getJSONArray("carriers").length());assertFalse(fact.toString().contains(CANARY));assertFalse(fact.toString().contains("SECOND-NATIVE-ORIGINAL"));assertEquals(cb.getString("taskId"),fact.getString("taskId"));assertEquals(TARGET,f.tasks.get(cb.getString("taskId"),false).getString("conversationId"));assertTrue(f.auditOperation(sa.getString("taskId"),sa.getString("runId"),echo.getString("operationId")).toString().contains(CANARY));
        f.retire(a);JSONObject denied=f.tasks.queryMemoryRaw(f.raw(caller,true),new JSONObject().put("section","operations").put("query",""));assertEquals(OwnerSourceRunContext.UNAVAILABLE,denied.getString("reason"));assertEquals(2,f.fact(cb.getString("runId")).getJSONArray("carriers").length());
    }}

    @Test public void legacyRunContinuationRejectsBeforeCommitWithNoFreshGrantOrModelAndNewConversationRemainsAvailable()throws Exception{try(Server server=new Server("MUST-NOT-CALL",true,2);Fixture f=new Fixture(server,null)){
        RunControl old=f.control();JSONObject b=f.bindings.get(old);String task=b.getString("taskId"),run=b.getString("runId");JSONObject echo=f.nativeEcho(old,"LEGACY-UNPROVEN-ECHO");RunJournal journal=f.tasks.journal(task,run,b.getInt("epoch"),old);journal.checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content","原主人目标保留")).put(new JSONObject().put("role","assistant").put("content","LEGACY-UNPROVEN-ECHO")),1,new JSONObject());f.db.delete("meta","key=?",new String[]{"run.owner_source_context."+run});f.tasks.endAttempt(task,run,"awaiting_user","原任务待核",new JSONObject());
        var blocked=assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->f.resume(task));assertTrue(blocked.getMessage().contains("新对话"));assertEquals(0,server.bodies.size());assertEquals(1,f.echoes.get());JSONObject current=f.tasks.get(task,false);assertEquals(run,current.getString("runId"));assertEquals(b.getInt("epoch"),current.getInt("coordinatorEpoch"));assertEquals(1,current.getJSONArray("operations").length());try(var row=f.db.rawQuery("SELECT count(*) FROM runs WHERE task_id=?",new String[]{task})){assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));}assertTrue(f.auditOperation(task,run,echo.getString("operationId")).toString().contains("LEGACY-UNPROVEN-ECHO"));assertEquals("原主人目标保留",journal.restore().getJSONArray("messages").getJSONObject(0).getString("content"));
        RunControl fresh=f.control();JSONObject next=f.bindings.get(fresh);assertNotEquals(task,next.getString("taskId"));assertTrue(f.fact(next.getString("runId")).getBoolean("trackingComplete"));assertEquals(0,f.fact(next.getString("runId")).getJSONArray("carriers").length());
    }}
    @Test public void activeUnknownSourceRunStopsOriginalRunnerBeforeFirstModelInsteadOfStrippingNewToolResultsForever()throws Exception{try(Server server=new Server("MUST-NOT-CALL",true,2);Fixture f=new Fixture(server,null)){
        RunControl control=f.control();JSONObject b=f.bindings.get(control);String task=b.getString("taskId"),run=b.getString("runId");f.db.delete("meta","key=?",new String[]{"run.owner_source_context."+run});f.flow.binding=b;f.flow.original=control;f.flow.conversation=TARGET;f.flow.explicitResume=true;f.flow.journal=f.tasks.journal(task,run,b.getInt("epoch"),control);server.release.countDown();JSONArray raw=new JSONArray().put(new JSONObject().put("role","user").put("content","原主人新要求仍保留")).put(new JSONObject().put("role","assistant").put("content","UNPROVEN-OLD-BODY"));f.flow.execute(TARGET,"原主人新要求仍保留",raw,new SettingsStore(f.android).get(),control);
        assertEquals("awaiting_user",f.flow.outcome.status);assertEquals(OwnerSourceRunContext.NEW_TASK_MESSAGE,f.flow.outcome.summary);assertEquals("new_private_conversation",f.flow.outcome.evidence.getJSONObject("contextProvenance").getString("nextAction"));assertEquals(0,server.bodies.size());assertEquals(0,f.echoes.get());assertEquals(0,f.tasks.get(task,false).getJSONArray("operations").length());assertTrue(f.flow.journal.restore().toString().contains("UNPROVEN-OLD-BODY"));assertTrue(f.flow.journal.restore().toString().contains("原主人新要求仍保留"));
    }}
    @Test public void trackedValidSourceResumeKeepsTwoRealNativeToolResultsUsableAcrossThreeModelRequests()throws Exception{try(Server server=new Server("新的可核对成果",true,2);Fixture f=new Fixture(server,null)){
        JSONArray refs=f.source(CANARY);JSONObject carrier=f.carrier(refs,"本次明确的新要求");RunControl old=f.control();JSONObject before=f.bindings.get(old);String task=before.getString("taskId"),oldRun=before.getString("runId");try(var p=f.projection(old)){p.project(new JSONArray().put(carrier),config(),old);}RunJournal original=f.tasks.journal(task,oldRun,before.getInt("epoch"),old);original.checkpoint(new JSONArray().put(carrier),0,new JSONObject());f.tasks.endAttempt(task,oldRun,"awaiting_user","主人显式继续",new JSONObject());
        RunControl live=f.resume(task);JSONObject b=f.bindings.get(live);assertNotEquals(oldRun,b.getString("runId"));assertTrue(f.fact(b.getString("runId")).getBoolean("trackingComplete"));assertEquals(f.fact(oldRun).getJSONArray("carriers").toString(),f.fact(b.getString("runId")).getJSONArray("carriers").toString());f.flow.binding=b;f.flow.original=live;f.flow.conversation=TARGET;f.flow.explicitResume=true;f.flow.journal=f.tasks.journal(task,b.getString("runId"),b.getInt("epoch"),live);f.tasks.authorizeTools(task,b.getString("runId"),new JSONArray().put("fixture_echo"));f.tasks.checkCapability(task,b.getString("runId"),b.getInt("epoch"),"fixture_echo",live);JSONArray restored=f.flow.journal.restore().getJSONArray("messages");server.release.countDown();f.flow.execute(TARGET,"本次明确的新要求",restored,new SettingsStore(f.android).get(),live);
        assertEquals(f.flow.outcome.summary,"needs_confirmation",f.flow.outcome.status);assertEquals(3,server.bodies.size());assertEquals(2,f.echoes.get());assertEquals(2,f.tasks.get(task,false).getJSONArray("operations").length());for(int i=1;i<3;i++){String request=server.bodies.get(i).toString();assertTrue(request.contains(CANARY));assertTrue(request.contains("fixtureFileEffect"));assertFalse(request.contains(OwnerSourceRunContext.UNAVAILABLE));}assertEquals(1,f.fact(b.getString("runId")).getJSONArray("carriers").length());
    }}

}
