package dev.pocketengine.features.memory;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual persisted Book + native SQLite retrieval. Synthetic formal records are not a
 * measured model rewrite or a live device run; late source/donor checks exercise native gates. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemorySemanticRecallNativeTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private File file()throws Exception{return new File(temp.newFolder(),"records.json");}
    private static JSONObject fact(String subject,String label,String predicate,String text,long event)throws Exception{
        return new JSONObject().put("layer","fact").put("text",text).put("assertion",new JSONObject().put("version",1)
                .put("kind","told").put("subjectType","entity").put("subjectId",subject).put("subjectLabel",label)
                .put("predicate",predicate).put("object","synthetic reviewed value").put("eventTime",event).put("evidenceSpans",new JSONArray()));
    }
    private static MemoryQueryPlan plan(String query,JSONObject constraints)throws Exception{return MemoryQueryPlan.agentSuggested(query,new JSONArray(),constraints);}
    private static JSONObject recall(MemoryBook book,String query,JSONObject constraints)throws Exception{return book.contextSnapshotQueriesScoped(scope(),plan(query,constraints),16,24000,null);}
    private static boolean contains(JSONArray rows,String id)throws Exception{for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return true;return false;}
    private static JSONObject onlyTargetAfter(long at)throws Exception{return new JSONObject().put("eventAfter",at);}

    @Test public void aliasDonorRemainsADelayedDeliveryDependencyEvenWhenOnlyItsTargetPassesConstraints()throws Exception{
        for(MemoryRetrievalBackend backend:List.of(new LexicalMemoryRetrievalBackend(),SQLiteMemoryRetrievalBackend.create(false))){
            File path=file();MemoryBook book=new MemoryBook(path,null,backend);
            try{
                JSONObject donor=book.save(scope(),fact("entity:project-a","silvermaple","rename","reviewed old project label",100),1);
                JSONObject target=book.save(scope(),fact("entity:project-a","birch","builder","build with the existing wrapper",300),2);
                JSONObject snapshot=recall(book,"silvermaple",onlyTargetAfter(200));
                assertEquals(1,snapshot.getJSONArray("records").length());assertTrue(contains(snapshot.getJSONArray("records"),target.getString("id")));
                assertFalse(contains(snapshot.getJSONArray("records"),donor.getString("id")));
                assertTrue("alias donor must remain a dependency although filtered from the answer",contains(snapshot.getJSONArray("retrievalDependencies"),donor.getString("id")));
                JSONObject dependency=book.contextDependency(snapshot);book.requireContextDependency(dependency);
                book.forgetReviewed(scope(),donor.getString("id"),donor.getLong("revision"));
                assertThrows(SecurityException.class,()->book.requireContextDependency(dependency));
                assertEquals(target.getString("id"),book.get(scope(),target.getString("id")).getString("id"));
                assertEquals(0,recall(book,"silvermaple",onlyTargetAfter(200)).getJSONArray("records").length());
                MemoryBook cold=new MemoryBook(path);try{assertEquals(0,recall(cold,"silvermaple",onlyTargetAfter(200)).getJSONArray("records").length());}finally{cold.closeRetrieval();}
            }finally{book.closeRetrieval();}
        }
    }

    @Test public void twoHopStableIdentityPathKeepsIntermediateRevisionWithoutTreatingItAsCurrentTruth()throws Exception{
        File path=file();MemoryBook book=new MemoryBook(path,null,SQLiteMemoryRetrievalBackend.create(false));
        try{
            JSONObject seed=book.save(scope(),fact("entity:seed","atlas","seed","needleprobe is the original clue",100),1);
            JSONObject bridgeInput=fact("entity:seed","atlas","link","reviewed explicit relation",150);
            bridgeInput.getJSONObject("assertion").put("objectId","entity:result").put("objectType","entity");
            JSONObject bridge=book.save(scope(),bridgeInput,2),target=book.save(scope(),fact("entity:result","beacon","method","restart the bounded worker",300),3);
            JSONObject snapshot=recall(book,"needleprobe",onlyTargetAfter(200));
            assertEquals(1,snapshot.getJSONArray("records").length());assertTrue(contains(snapshot.getJSONArray("records"),target.getString("id")));
            JSONArray dependencies=snapshot.getJSONArray("retrievalDependencies");for(JSONObject row:List.of(seed,bridge,target))assertTrue(contains(dependencies,row.getString("id")));
            assertFalse(snapshot.getBoolean("executionAuthorized"));assertFalse(snapshot.getJSONObject("retrievalDiagnostics").getBoolean("rankIsTruthOrConfidence"));
            assertFalse(snapshot.getJSONArray("records").getJSONObject(0).getJSONObject("retrievalEvidence").getBoolean("originalEvidenceRead"));
            JSONObject dependency=book.contextDependency(snapshot);book.forgetReviewed(scope(),bridge.getString("id"),bridge.getLong("revision"));
            assertThrows(SecurityException.class,()->book.requireContextDependency(dependency));assertTrue(book.get(scope(),target.getString("id")).getBoolean("active"));
            assertEquals(0,recall(book,"needleprobe",onlyTargetAfter(200)).getJSONArray("records").length());
            assertEquals(0,recall(book,"needleprobe",onlyTargetAfter(200).put("maxHops",0)).getJSONArray("records").length());
        }finally{book.closeRetrieval();}
    }

    @Test public void unselectedAudienceAndAnotherNamespaceCannotDonateAliasesOrGraphLinks()throws Exception{
        MemoryBook book=new MemoryBook(file(),null,SQLiteMemoryRetrievalBackend.create(false));
        try{
            JSONObject donor=book.save(scope(),fact("entity:shared-id","silvermaple","label","original native alias",100),1);
            JSONObject target=book.save(scope(),fact("entity:shared-id","birch","builder","wrapper selection",300),2);
            ReadAudience exactTarget=new ReadAudience(){public String ownerId(){return "owner";}public String spaceId(){return "private";}public String audienceId(){return "target-only";}public long revision(){return 1;}public void checkCurrent(){}public JSONArray allowedSources(){return new JSONArray();}public JSONArray allowedRecords()throws Exception{return new JSONArray().put(new JSONObject().put("id",target.getString("id")).put("revision",target.getLong("revision")));}};
            MemoryAudienceSelection selected=MemoryAudienceSelection.capture(exactTarget,MemoryBook.Scope.from(scope()));
            JSONObject limited=book.contextSnapshotQueriesScoped(scope(),plan("silvermaple",new JSONObject()),16,24000,selected);
            assertEquals(0,limited.getJSONArray("records").length());assertEquals(0,limited.getJSONArray("retrievalDependencies").length());
            book.forgetReviewed(scope(),donor.getString("id"),donor.getLong("revision"));
            book.save(new JSONObject().put("ownerId","other-owner").put("spaceId","private"),fact("entity:shared-id","silvermaple","label","foreign alias must remain private",100),3);
            assertEquals(0,recall(book,"silvermaple",new JSONObject()).getJSONArray("records").length());
            assertThrows(IllegalArgumentException.class,()->plan("wrapper",new JSONObject().put("ownerId","other-owner")));
        }finally{book.closeRetrieval();}
    }

    @Test public void lateSourceChangeOnAliasDonorInvalidatesTheOriginalTaskSelectionBeforeExposure()throws Exception{
        SourceRef ref=new SourceRef("message","alias-source",1,"owner","private");Map<String,SourceRef.State> sources=new HashMap<>();sources.put(ref.sourceId,new SourceRef.State(ref,false));
        MemoryBook book=new MemoryBook(file(),(scope,kind,id)->sources.get(id),SQLiteMemoryRetrievalBackend.create(false));
        try{
            JSONObject donor=book.save(scope(),fact("entity:project","silvermaple","label","source backed name",100).put("sourceRefs",new JSONArray().put(ref.toJson())),1);
            book.save(scope(),fact("entity:project","birch","builder","bounded wrapper",300),2);
            TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef("task-selection","run-selection",1,"owner","private");
            TaskMemoryUse.HostReader host=new TaskMemoryUse.HostReader(){public TaskMemoryUse.TaskState readTask(MemoryBook.Scope scope,String id){return new TaskMemoryUse.TaskState(task,"running",false);}public TaskMemoryUse.Provided readProvided(MemoryBook.Scope scope,TaskMemoryUse.ReceiptRef ref){return null;}};
            TaskMemoryUse use=new TaskMemoryUse(book,host,expected->{throw new AssertionError("summary retrieval must not fabricate an original read");});
            MemoryHostFence fence=new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}};
            TaskMemoryUse.Selection selected=use.prepareSemanticTaskRecall(scope(),task,plan("silvermaple",onlyTargetAfter(200)),16,24000,fence);
            assertTrue(contains(selected.metadata().getJSONArray("retrievalDependencies"),donor.getString("id")));assertEquals(1,use.validateTaskMemorySelection(scope(),selected,fence).getJSONArray("records").length());
            sources.put(ref.sourceId,new SourceRef.State(new SourceRef("message",ref.sourceId,2,"owner","private"),false));
            assertThrows(SecurityException.class,()->use.validateTaskMemorySelection(scope(),selected,fence));
            assertEquals(0,recall(book,"silvermaple",onlyTargetAfter(200)).getJSONArray("records").length());
        }finally{book.closeRetrieval();}
    }

    @Test public void eventSourceAndLayerFiltersNeverUseUpdateTimeAsAnUnknownEventTime()throws Exception{
        MemoryBook book=new MemoryBook(file());
        try{
            JSONObject unknown=fact("entity:unknown","unknown","activity","needleprobe undated",0);unknown.getJSONObject("assertion").put("eventTime",JSONObject.NULL);
            book.save(scope(),unknown,5000);
            JSONObject dated=book.save(scope(),fact("entity:dated","dated","activity","needleprobe dated",300),10);
            JSONObject constraints=new JSONObject().put("eventAfter",200).put("eventBefore",400).put("updatedBefore",20)
                    .put("sourceKinds",new JSONArray().put("owner_saved")).put("layers",new JSONArray().put("fact")).put("entityIds",new JSONArray().put("entity:dated"));
            JSONObject result=recall(book,"needleprobe",constraints);assertEquals(1,result.getJSONArray("records").length());assertTrue(contains(result.getJSONArray("records"),dated.getString("id")));
            assertFalse(result.getString("context").contains("undated"));
            assertThrows(IllegalArgumentException.class,()->plan("needleprobe",new JSONObject().put("eventAfter",400).put("eventBefore",200)));
            assertThrows(IllegalArgumentException.class,()->plan("needleprobe",new JSONObject().put("sourceKinds",new JSONArray().put("model_success"))));
        }finally{book.closeRetrieval();}
    }

    @Test public void finiteGraphAndFunctionWordNoiseReportBoundsWithoutPersistingADerivedIndex()throws Exception{
        File path=file();MemoryBook book=new MemoryBook(path,null,SQLiteMemoryRetrievalBackend.create(false));
        try{
            book.save(scope(),fact("entity:fanout","maple","seed","needleprobe seeded record",100),1);
            for(int i=0;i<70;i++)book.save(scope(),fact("entity:fanout","maple","property-"+i,"parallel reviewed detail "+i,100),i+2);
            byte[] before=Files.readAllBytes(path.toPath());JSONObject snapshot=recall(book,"needleprobe",new JSONObject());JSONObject diagnostics=snapshot.getJSONObject("retrievalDiagnostics");
            assertTrue(diagnostics.getInt("candidateCount")<=MemorySemanticRecall.MAX_CANDIDATES);assertTrue(diagnostics.getInt("linkVisits")<=MemorySemanticRecall.MAX_LINK_VISITS);assertTrue(diagnostics.getBoolean("partial"));
            assertTrue(snapshot.getJSONArray("retrievalDependencies").length()<=64);assertTrue(snapshot.getJSONArray("records").length()<=16);assertTrue(snapshot.getString("context").length()<=24000);
            assertFalse(diagnostics.getBoolean("indexPersisted"));assertArrayEquals(before,Files.readAllBytes(path.toPath()));
            assertEquals(0,recall(book,"the and",new JSONObject()).getJSONArray("records").length());
            assertThrows(IllegalArgumentException.class,()->plan("needleprobe",new JSONObject().put("maxHops",3)));
            JSONObject repeated=recall(book,"needleprobe",new JSONObject());Set<String> one=new java.util.HashSet<>(),two=new java.util.HashSet<>();
            for(int i=0;i<snapshot.getJSONArray("records").length();i++)one.add(snapshot.getJSONArray("records").getJSONObject(i).getString("id"));
            for(int i=0;i<repeated.getJSONArray("records").length();i++)two.add(repeated.getJSONArray("records").getJSONObject(i).getString("id"));assertEquals(one,two);
        }finally{book.closeRetrieval();}
    }
}
