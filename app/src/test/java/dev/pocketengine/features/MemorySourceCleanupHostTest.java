package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real MemoryBook, TaskStore SQLite and Skill store; no external model or authorization. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class MemorySourceCleanupHostTest {
    static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks=new TaskStore(app);
        final FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),e->{});
        final FeatureHost host=new FeatureHost(e->{});final MemorySourceBridge bridge=new MemorySourceBridge(host,tasks);
        final MemoryModule memory=new MemoryModule(context,bridge::resolve);final SkillsModule skills=new SkillsModule(context,memory::resolveExperience);
        Fixture()throws Exception{host.register(memory);host.register(skills);host.setMemorySynchronizer(bridge::synchronize);}
        SourceRef source(String goal)throws Exception{JSONObject t=tasks.create(goal);tasks.status(t.getString("id"),"completed","fixture checked result");tasks.event(t.getString("id"),new JSONObject().put("type","user_verification").put("completed",true));return tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),"task_evidence","task:"+t.getString("id")+":verified_result").ref;}
        JSONObject save(JSONArray refs,String text)throws Exception{return memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text",text).put("sourceRefs",refs))).getJSONObject("record");}
        JSONObject ref(JSONObject record,SourceRef source)throws Exception{return new JSONObject().put("recordId",record.getString("id")).put("revision",record.getLong("revision")).put("ownerId","owner").put("spaceId","private").put("sourceRefs",new JSONArray().put(source.toJson()));}
        String intent(JSONObject ref,String cause)throws Exception{String id=java.util.UUID.randomUUID().toString();tasks.beginExternal("memory","sourceCleanup",id,new JSONObject());tasks.status(id,"running","fixture interrupted intent");tasks.beginCapabilityOperation(id,id,"memory","sourceCleanup",new JSONObject().put("nativeReceipt","memory_source_cleanup").put("scope",scope()).put("cause",cause).put("removedRefs",new JSONArray().put(ref)));return id;}
        public void close(){host.close();}
    }
    @Test public void ownerForgetJournalsEveryOneOf205AffectedRecordsBeforeCleanup()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef source=f.source("205 records");JSONObject first=null;
            for(int i=0;i<205;i++){JSONObject r=f.save(new JSONArray().put(source.toJson()),"record "+i);if(first==null)first=r;}
            assertEquals(205,f.bridge.forgetReviewed(scope(),first.getString("id"),1).getInt("deleted"));
            assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());
            int receipts=0,refs=0;JSONArray tasks=f.tasks.list();
            for(int i=0;i<tasks.length();i++){JSONObject t=tasks.getJSONObject(i);if(!"sourceCleanup".equals(t.optString("command")))continue;receipts++;assertEquals("completed",t.getString("status"));JSONArray saved=f.tasks.get(t.getString("id"),false).getJSONArray("operations").getJSONObject(0).getJSONObject("arguments").getJSONArray("removedRefs");assertTrue(saved.length()<=100);refs+=saved.length();}
            assertEquals(3,receipts);assertEquals(205,refs);assertEquals(0,f.tasks.memoryCleanupTasks().length());assertThrows(IllegalStateException.class,()->f.tasks.readMemorySource(source));
        }
    }
    @Test public void preIntentIsNotMemoryCommitAndNeverErasesOriginalSource()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef source=f.source("retained source");JSONObject record=f.save(new JSONArray().put(source.toJson()),"still here");String intent=f.intent(f.ref(record,source),"owner_forget");
            new MemorySourceBridge(f.host,f.tasks).synchronize(scope());assertEquals("failed",f.tasks.get(intent,false).getString("status"));
            assertEquals(1,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());assertNotNull(f.tasks.readMemorySource(source));
        }
    }
    @Test public void committedForgetInterruptedBeforeCleanupReplaysOnlyCleanup()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef source=f.source("committed source");JSONObject record=f.save(new JSONArray().put(source.toJson()),"remove me");String intent=f.intent(f.ref(record,source),"owner_forget");
            f.memory.forgetReviewed(scope(),record.getString("id"),1,ignored->{});
            new MemorySourceBridge(f.host,f.tasks).synchronize(scope());assertEquals("completed",f.tasks.get(intent,false).getString("status"));assertThrows(IllegalStateException.class,()->f.tasks.readMemorySource(source));
            f.bridge.synchronize(scope());assertEquals(0,f.tasks.memoryCleanupTasks().length());
        }
    }
    @Test public void sourceChangeNeverErasesStillValidSiblingTaskSnapshot()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef a=f.source("A removed"),b=f.source("B valid");f.save(new JSONArray().put(a.toJson()).put(b.toJson()),"derived from A plus B");
            String id=a.sourceId.substring(5,a.sourceId.length()-16);f.tasks.event(id,new JSONObject().put("type","user_verification").put("completed",false));
            f.bridge.synchronize(scope());assertNotNull(f.tasks.readMemorySource(b));assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());
        }
    }
    @Test public void ownerForgetAAlsoPreservesBWhenOnlyTheirDerivedSummaryIsRemoved()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef a=f.source("forget A"),b=f.source("retain B");JSONObject ra=f.save(new JSONArray().put(a.toJson()),"A"),rb=f.save(new JSONArray().put(b.toJson()),"B");
            f.memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","summary A plus B").put("sourceIds",new JSONArray().put(ra.getString("id")).put(rb.getString("id")))));
            assertEquals(2,f.bridge.forgetReviewed(scope(),ra.getString("id"),1).getInt("deleted"));assertNotNull(f.tasks.readMemorySource(b));
            assertThrows(IllegalStateException.class,()->f.tasks.readMemorySource(a));
        }
    }
    @Test public void channelScopeSynchronizationDoesNotConsumeOwnerPrivateCleanupIntent()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef source=f.source("private retained");JSONObject record=f.save(new JSONArray().put(source.toJson()),"private record");String intent=f.intent(f.ref(record,source),"owner_forget");
            f.bridge.synchronize(new JSONObject().put("ownerId","owner").put("spaceId","qq:group:fixture"));assertEquals("running",f.tasks.get(intent,false).getString("status"));assertNotNull(f.tasks.readMemorySource(source));
            f.bridge.synchronize(scope());assertEquals("failed",f.tasks.get(intent,false).getString("status"));
        }
    }
    @Test public void readOnlyForgetProofRequiresCommittedExactScopeAndCoveredRevision()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef source=f.source("barrier proof");JSONObject record=f.save(new JSONArray().put(source.toJson()),"original");
            assertFalse(f.memory.isSourceForgotten(scope(),source));
            assertThrows(IllegalStateException.class,()->f.memory.forgetReviewed(scope(),record.getString("id"),1,refs->{throw new IllegalStateException("fixture journal failure");}));
            assertFalse(f.memory.isSourceForgotten(scope(),source));f.bridge.forgetReviewed(scope(),record.getString("id"),1);assertTrue(f.memory.isSourceForgotten(scope(),source));
            assertFalse(f.memory.isSourceForgotten(scope(),new SourceRef(source.kind,source.sourceId,source.revision+1,"owner","private")));
            assertThrows(SecurityException.class,()->f.memory.isSourceForgotten(new JSONObject().put("ownerId","owner").put("spaceId","qq:group:fixture"),source));
            assertThrows(IllegalArgumentException.class,()->f.memory.handle("isSourceForgotten",new JSONObject().put("scope",scope()).put("sourceRef",source.toJson())));
        }
    }
    @Test public void unrelatedSourceDeletionAfterFailedForgetIntentIsNotForgetCommitProof()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef a=f.source("changed A"),b=f.source("valid B");JSONObject record=f.save(new JSONArray().put(a.toJson()).put(b.toJson()),"A plus B root");
            String intent=f.intent(f.ref(record,a).put("sourceRefs",new JSONArray().put(a.toJson()).put(b.toJson())),"owner_forget");
            String id=a.sourceId.substring(5,a.sourceId.length()-16);f.tasks.event(id,new JSONObject().put("type","user_verification").put("completed",false));
            SourceRef revoked=f.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),a.kind,a.sourceId).ref;
            f.memory.sourceDeleted(scope(),revoked,ignored->{});assertFalse(f.memory.isSourceForgotten(scope(),b));
            f.bridge.synchronize(scope());assertEquals("completed",f.tasks.get(intent,false).getString("status"));assertNotNull(f.tasks.readMemorySource(b));assertFalse(f.memory.isSourceForgotten(scope(),a));
        }
    }
    @Test public void nestedTransitiveSourcesAlsoStayBelowJournalArrayLimit()throws Exception{
        JSONArray refs=new JSONArray();for(int i=0;i<205;i++)refs.put(new SourceRef("task_evidence","task:"+i+":verified_result",1,"owner","private").toJson());
        JSONObject row=new JSONObject().put("recordId","r").put("revision",1).put("ownerId","owner").put("spaceId","private").put("sourceRefs",refs);
        JSONArray split=MemorySourceBridge.boundedCleanupRefs(scope(),new JSONArray().put(row),true);assertEquals(3,split.length());int n=0;for(int i=0;i<split.length();i++){JSONArray chunk=split.getJSONObject(i).getJSONArray("sourceRefs");assertTrue(chunk.length()<=100);n+=chunk.length();}assertEquals(205,n);
        assertEquals(0,MemorySourceBridge.boundedCleanupRefs(scope(),new JSONArray().put(row),false).getJSONObject(0).getJSONArray("sourceRefs").length());
    }
}
