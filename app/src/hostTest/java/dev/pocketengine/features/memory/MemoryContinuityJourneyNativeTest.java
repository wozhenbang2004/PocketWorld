package dev.pocketengine.features.memory;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.workspace.OwnerExperienceArchive;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original App/Runner/SQLite -> actual loopback model bytes -> native review -> cold Book
 * -> source forget -> original portable archive. Fixture semantics are not model-quality proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class MemoryContinuityJourneyNativeTest {
    @org.junit.Before public void keys(){java.security.Security.addProvider(new java.security.Provider("MemoryContinuityFixtureKeys",1.0d,"Test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @org.junit.After public void removeKeys(){java.security.Security.removeProvider("MemoryContinuityFixtureKeys");}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final String OLD="当时住A城，因为成本和离家近选择X。",CURRENT="现在住B城，方案改Y，因为约束已经变化。",LATE="迟到的旧记录：当时仍住A城。",THIRD="我的同事同名小王喜欢咖啡，不是我的偏好。",IMAGINED="这是一段虚构练习：我住在星空城。";
    private static JSONObject answer(String words)throws Exception {
        JSONObject draft=new JSONObject().put("layer","fact").put("text","不能用这段模型概括代替原话").put("quote",words).put("reason","受控模型根据这次原话提出，事实仍未独立核实").put("applicability","").put("confidence",0.5);
        if(!THIRD.equals(words))draft.put("assertion",new JSONObject().put("kind",IMAGINED.equals(words)?"imagined":"inferred").put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人").put("predicate","居住城市")
                .put("object",CURRENT.equals(words)?"B城":IMAGINED.equals(words)?"星空城":"A城").put("condition","").put("eventTime",CURRENT.equals(words)?200:IMAGINED.equals(words)?300:100).put("quote",words).put("offset",0));
        return draft;
    }
    private static JSONObject turn(EngineApplication app,AssistantModule assistant,MemoryModule memory,String words,int completedJobs)throws Exception {
        JSONObject ack=assistant.handle("send",new JSONObject().put("message",words).put("clientMessageId",UUID.randomUUID().toString()));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        var controls=EngineApplication.class.getDeclaredField("capabilityControls");controls.setAccessible(true);
        while((assistant.snapshot().optBoolean("running")||((Map<?,?>)controls.get(app)).containsKey(ack.getString("taskId")))&&System.nanoTime()<until)Thread.sleep(10);
        JSONObject displayed=app.tasks.get(ack.getString("taskId"),false);assertTrue(displayed.optString("summary"),displayed.getString("status").matches("completed|needs_confirmation"));
        if("needs_confirmation".equals(displayed.getString("status")))app.dispatchOwnerAction("confirmResult",new JSONObject().put("taskId",ack.getString("taskId")).put("resultReviewDigest",displayed.getString("resultReviewDigest")).put("completed",true).put("feedback",""));
        JSONObject candidate=null;boolean done=false;
        while(System.nanoTime()<until){JSONArray jobs=app.tasks.listSourceLearningJobs("",20).getJSONArray("jobs");int completed=0;for(int i=0;i<jobs.length();i++)if("completed".equals(jobs.getJSONObject(i).optString("taskStatus")))completed++;
            JSONArray candidates=memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates");for(int i=0;i<candidates.length();i++)if(words.equals(candidates.getJSONObject(i).optString("text")))candidate=candidates.getJSONObject(i);
            if(completed==completedJobs&&candidate!=null){done=true;break;}Thread.sleep(10);
        }
        assertTrue("original automatic batch did not finish: "+words,done);assertEquals("pending",candidate.getString("status"));assertEquals(words,candidate.getString("text"));
        SourceRef actual=SourceRef.fromJson(candidate.getJSONArray("sourceRefs").getJSONObject(0));assertEquals(new SourceRef("message",ack.getString("messageId"),1,"owner","private"),actual);
        JSONObject accepted=app.nativeOwnerWorkflows.memory("acceptCandidate",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision"))).getJSONObject("record");
        assertEquals("unverified",accepted.getString("validationStatus"));assertFalse(accepted.getBoolean("core"));return accepted;
    }
    private static JSONObject capture(OwnerExperienceArchive archive,Map<String,JSONObject> objects)throws Exception{return archive.exportObjects(object->{String hash=ProjectSources.digest(object);objects.put(hash,new JSONObject(object.toString()));return hash;},new RunControl());}
    private static String readAll(OwnerExperienceArchive archive,JSONObject section,Map<String,JSONObject> objects)throws Exception {StringBuilder text=new StringBuilder();for(int i=0;i<section.getJSONArray("pages").length();i++){JSONObject page=archive.readPage(section,i,objects::get,new RunControl());assertTrue(page.getBoolean("archiveOnly"));assertFalse(page.getBoolean("executionAuthorized"));text.append(page);}return text.toString();}
    private static JSONArray learningDrafts(OwnerExperienceArchive archive,JSONObject section,Map<String,JSONObject> objects)throws Exception {JSONArray result=new JSONArray();for(int i=0;i<section.getJSONArray("pages").length();i++){JSONObject page=archive.readPage(section,i,objects::get,new RunControl());if(!"operations".equals(page.getString("kind")))continue;JSONArray rows=page.getJSONObject("data").getJSONArray("entries");for(int j=0;j<rows.length();j++)if(SourceLearningJob.TOOL.equals(rows.getJSONObject(j).optString("tool")))result.put(rows.getJSONObject(j));}return result;}
    @Test public void actualOwnerTurnsKeepCurrentFactAndExactReasonsThenForgetAndOldArchiveCannotReactivateThem()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();AssistantModule assistant=(AssistantModule)app.features.module("assistant");MemoryModule memory=(MemoryModule)app.features.module("memory");
        AtomicInteger mainCalls=new AtomicInteger(),learningCalls=new AtomicInteger();AtomicReference<Throwable> fixtureFailure=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));boolean learning=request.getJSONArray("messages").getJSONObject(0).getString("content").contains("记忆候选提炼器");String content;
            if(learning){learningCalls.incrementAndGet();String words=new JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content")).getString("text");if(!java.util.Set.of(OLD,CURRENT,LATE,THIRD,IMAGINED).contains(words))throw new IllegalStateException("unexpected native source");content=answer(words).toString();}else{mainCalls.incrementAndGet();content="已收到这次原话，请按原生记忆入口审阅候选。";}
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",content)).put("finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Throwable failed){fixtureFailure.compareAndSet(null,failed);throw new IllegalStateException(failed);}finally{exchange.close();}});server.start();
        boolean sourceClosed=false;
        try{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("provider","openai_chat").putString("model","controlled-continuity-fixture").putBoolean("supportsImageInput",false).putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
            JSONObject policy=app.automaticUserLearningPolicy();assertFalse(policy.getBoolean("enabled"));app.setAutomaticUserLearningPolicy(policy.getLong("revision"),true,1,true);
            JSONObject old=turn(app,assistant,memory,OLD,1),current=turn(app,assistant,memory,CURRENT,2);turn(app,assistant,memory,LATE,3);JSONObject third=turn(app,assistant,memory,THIRD,4),imagined=turn(app,assistant,memory,IMAGINED,5);
            assertFalse(third.has("assertion"));assertEquals("imagined",imagined.getJSONObject("assertion").getString("kind"));assertNull(fixtureFailure.get());assertEquals(5,mainCalls.get());assertEquals(5,learningCalls.get());
            JSONObject view=app.nativeOwnerWorkflows.memory("cognitionProjection",new JSONObject().put("subjectId","owner"));assertEquals(1,view.getJSONArray("current").length());assertEquals(current.getString("id"),view.getJSONArray("current").getJSONObject(0).getString("id"));assertEquals(3,view.getJSONArray("history").length());
            JSONObject span=current.getJSONObject("assertion").getJSONArray("evidenceSpans").getJSONObject(0);JSONObject evidence=app.nativeOwnerWorkflows.memory("readAssertionEvidence",span);assertEquals(CURRENT,evidence.getString("text"));assertTrue(evidence.getBoolean("verifiedOriginalSpan"));assertFalse(evidence.getBoolean("assertionTruthVerified"));
            JSONObject snapshot=memory.contextSnapshot(scope(),"居住城市",16,12000);assertEquals(1,snapshot.getJSONArray("records").length());assertEquals(current.getString("id"),snapshot.getJSONArray("records").getJSONObject(0).getString("id"));assertTrue(snapshot.getString("context").contains("约束已经变化"));assertFalse(snapshot.getString("context").contains(OLD));assertFalse(snapshot.getString("context").contains(IMAGINED));
            File bookFile=new File(app.getFilesDir(),"memory/records.json");MemoryBook cold=new MemoryBook(bookFile,(identity,kind,id)->assistant.resolveSource(identity,id));assertEquals(current.getString("id"),cold.search(scope(),"居住城市",16).getJSONObject(0).getString("id"));assertEquals("inferred",cold.get(scope(),current.getString("id")).getJSONObject("assertion").getString("kind"));cold.closeRetrieval();
            policy=app.automaticUserLearningPolicy();app.setAutomaticUserLearningPolicy(policy.getLong("revision"),false,1,false);
            OwnerExperienceArchive archive=new OwnerExperienceArchive(app.tasks,memory);Map<String,JSONObject> oldObjects=new LinkedHashMap<>();JSONObject oldExperiences=capture(archive,oldObjects),oldMemory=memory.ownerArchive(scope());assertTrue(readAll(archive,oldExperiences,oldObjects).contains(CURRENT));
            app.nativeOwnerWorkflows.memory("forgetReviewed",new JSONObject().put("id",current.getString("id")).put("expectedRevision",current.getLong("revision")));
            assertEquals(0,memory.contextSnapshot(scope(),"居住城市",16,12000).getJSONArray("records").length());assertThrows(Exception.class,()->app.nativeOwnerWorkflows.memory("readAssertionEvidence",span));
            // A known local source withdrawal must also fence a previously exported learning
            // draft inside the passive original operation pages, not just its message row.
            JSONArray drafts=learningDrafts(archive,oldExperiences,oldObjects);assertTrue(drafts.length()>0);assertFalse("old archive retained withdrawn learning draft: "+drafts, drafts.toString().contains(CURRENT));
            Map<String,JSONObject> afterObjects=new LinkedHashMap<>();JSONObject afterExperiences=capture(archive,afterObjects);assertFalse(learningDrafts(archive,afterExperiences,afterObjects).toString().contains(CURRENT));
            assistant.close();memory.close();app.onTerminate();var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((android.database.sqlite.SQLiteDatabase)database.get(app.tasks)).close();app.deleteDatabase("pocket-world.db");Files.delete(bookFile.toPath());sourceClosed=true;
            TaskStore targetTasks=new TaskStore(app);targetTasks.conversationRepository().initializeEmpty();MemoryModule targetMemory=new MemoryModule(new FeatureContext(app,null,null,event->{}));try{
                assertNull(targetTasks.conversationRepository().metadata(SourceRef.fromJson(span.getJSONObject("sourceRef")).sourceId));OwnerExperienceArchive target=new OwnerExperienceArchive(targetTasks,targetMemory);
                assertTrue("old snapshot may retain unknown later history, but only as archive",readAll(target,oldExperiences,oldObjects).contains(CURRENT));assertFalse(learningDrafts(target,afterExperiences,afterObjects).toString().contains(CURRENT));
                JSONObject restored=targetMemory.restoreOwnerArchive(scope(),oldMemory,"native-journey-old-book");assertEquals(0,restored.getInt("formalMemoryActivated"));assertEquals(0,targetMemory.contextSnapshot(scope(),"居住城市",16,12000).getJSONArray("records").length());assertNull(targetTasks.conversationRepository().metadata(SourceRef.fromJson(span.getJSONObject("sourceRef")).sourceId));
            }finally{targetMemory.close();((android.database.sqlite.SQLiteDatabase)database.get(targetTasks)).close();}
        }finally{if(!sourceClosed){JSONObject policy=app.automaticUserLearningPolicy();app.setAutomaticUserLearningPolicy(policy.getLong("revision"),false,1,false);assistant.handle("cancel",new JSONObject());assistant.close();}server.stop(0);}
    }
}
