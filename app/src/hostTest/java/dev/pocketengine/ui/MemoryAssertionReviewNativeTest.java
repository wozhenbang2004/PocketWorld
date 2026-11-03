package dev.pocketengine.ui;

import android.app.Application;
import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual Controller -> original native review/Book/workspace/SQLite. No model-success fixture,
 * identity registry, provider inference, device input or visual acceptance is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class MemoryAssertionReviewNativeTest {
    static JSONObject j(Object... pairs)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static JSONObject scope()throws Exception{return j("ownerId","owner","spaceId","private");}
    static JSONObject ref(JSONObject row)throws Exception{return j("id",row.getString("id"),"revision",row.getLong("revision"));}
    static JSONObject subject(String type,String label)throws Exception{return j("mode","new","type",type,"label",label);}
    static JSONObject existing(JSONObject row,String role)throws Exception{return j("mode","existing","recordId",row.getString("id"),"recordRevision",row.getLong("revision"),"role",role);}
    static JSONObject input(JSONObject candidate,JSONObject subject,String predicate,String object)throws Exception{return ref(candidate).put("subject",subject).put("kind","told").put("predicate",predicate).put("object",object);}
    static SourceRef original(ProjectMemoryReviewUiNativeTest.Fixture f,String text)throws Exception {
        return f.io.submit(()->{
            // Independent originals share one real project, so the 21-source case tests
            // the memory source budget instead of the unrelated 20-project capacity.
            String project=null;JSONArray projects=f.workspace.snapshot().getJSONArray("projects");
            for(int i=0;i<projects.length();i++)if("身份审核原件".equals(projects.getJSONObject(i).getString("name"))){project=projects.getJSONObject(i).getString("id");break;}
            if(project==null)project=f.workspace.handle("create",j("name","身份审核原件")).getString("id");
            String path="source-"+UUID.randomUUID()+".txt";
            f.workspace.handle("write",j("projectId",project,"path",path,"content",text,"expectedVersion",0));
            var file=f.workspace.fileSnapshotForHost(project,path,0);
            // Native production entry points bootstrap a selected path on IO before
            // Book's non-locking resolver can regard its head as known/current.
            var head=f.workspace.refreshFileHeadForHost(project,path);
            assertTrue(head.authorityKnown);assertFalse(head.deleted);assertEquals(file.version,head.version);assertEquals(file.revisionId,head.revisionId);assertEquals(file.sha256,head.sha256);
            return SourceRef.workspaceFile(project,path,file.version,file.revisionId,file.sha256,0,text.length(),"owner","private");
        }).get(10,TimeUnit.SECONDS);
    }
    static JSONObject stage(ProjectMemoryReviewUiNativeTest.Fixture f,String text)throws Exception{return stage(f,text,new SourceRef[]{original(f,text)});}
    static JSONObject stage(ProjectMemoryReviewUiNativeTest.Fixture f,String text,SourceRef[] refs)throws Exception {
        return f.io.submit(()->f.memory.proposeLearning(scope(),j("layer","fact","text",text,"reason","只取本次真实原件，身份与事实仍待主人核对","applicability","本原件描述的情境","confidence",0.6),refs).getJSONObject("candidate")).get(10,TimeUnit.SECONDS);
    }
    static JSONObject annotate(ProjectMemoryReviewUiNativeTest.Fixture f,JSONObject input)throws Exception{return f.result("reviewCandidateAssertion",input).getJSONObject("candidate");}
    static JSONObject accept(ProjectMemoryReviewUiNativeTest.Fixture f,JSONObject candidate)throws Exception{return f.result("acceptCandidate",ref(candidate)).getJSONObject("record");}
    static JSONObject person(ProjectMemoryReviewUiNativeTest.Fixture f,String text,String label,String value)throws Exception {
        JSONObject candidate=stage(f,text);return accept(f,annotate(f,input(candidate,subject("person",label),"偏好",value)));
    }
    static MemoryBook book(ProjectMemoryReviewUiNativeTest.Fixture f)throws Exception {var field=MemoryModule.class.getDeclaredField("book");field.setAccessible(true);return (MemoryBook)field.get(f.memory);}
    static MemoryBook cold(ProjectMemoryReviewUiNativeTest.Fixture f)throws Exception {var resolver=MemoryBook.class.getDeclaredField("sourceResolver");resolver.setAccessible(true);return new MemoryBook(new File(f.app.getFilesDir(),"memory/records.json"),(SourceRef.Resolver)resolver.get(book(f)));}
    static JSONObject candidate(ProjectMemoryReviewUiNativeTest.Fixture f,JSONObject value)throws Exception{return f.result("getCandidate",j("id",value.getString("id"))).getJSONObject("candidate");}
    private static boolean containsId(JSONArray rows,String id)throws Exception{for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).optString("id")))return true;return false;}

    @Test public void sameNamePeopleAndAnExactRelationshipAreCreatedThroughNativeReviewAndColdRead()throws Exception {
        try(ProjectMemoryReviewUiNativeTest.Fixture f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            f.enter();JSONObject pending=stage(f,"研发的小林喜欢咖啡");
            assertFalse(pending.has("assertion"));JSONObject prepared=f.result("prepareAssertionReview",ref(pending));assertEquals(0,prepared.getJSONArray("identities").length());
            JSONObject payload=input(pending,subject("person","小林"),"偏好","咖啡"),edited=annotate(f,payload);
            assertTrue(edited.getJSONObject("assertion").getString("subjectId").matches("person:[a-f0-9-]{36}"));assertEquals(0,f.result("list",j()).getJSONArray("records").length());
            assertFalse(f.request("reviewCandidateAssertion",payload).getBoolean("ok"));assertEquals(edited.getLong("revision"),candidate(f,edited).getLong("revision"));
            JSONObject first=accept(f,edited),second=person(f,"设计的小林喜欢茶","小林","茶");
            assertNotEquals(first.getJSONObject("assertion").getString("subjectId"),second.getJSONObject("assertion").getString("subjectId"));
            assertEquals(0,f.result("cognitionProjection",j("subjectId","owner")).getJSONArray("current").length());
            JSONObject relation=stage(f,"研发的小林负责天琴项目"),choices=f.result("prepareAssertionReview",ref(relation).put("query","小林"));
            assertEquals(2,choices.getJSONArray("identities").length());
            JSONObject reviewed=annotate(f,input(relation,existing(first,"subject"),"负责项目","天琴").put("relationTarget",subject("entity","天琴")));
            assertEquals("assertion_review",reviewed.getString("formation"));assertEquals(1,reviewed.getJSONArray("recordRefs").length());assertEquals(2,reviewed.getJSONArray("sourceRefs").length());
            assertEquals(first.getString("id"),reviewed.getJSONArray("recordRefs").getJSONObject(0).getString("id"));
            JSONObject linked=accept(f,reviewed);assertEquals(first.getString("id"),linked.getJSONArray("sourceIds").getString(0));assertEquals("reviewed_learning",linked.getString("formation"));
            MemoryBook cold=cold(f);assertEquals(linked.getJSONObject("assertion").getString("objectId"),cold.get(scope(),linked.getString("id")).getJSONObject("assertion").getString("objectId"));assertEquals(1,cold.getCandidate(scope(),reviewed.getString("id")).getJSONArray("recordRefs").length());
            JSONObject archive=f.memory.ownerArchive(scope());MemoryBook restored=new MemoryBook(new File(f.app.getFilesDir(),"identity-passive-restore.json"));restored.restoreOwnerArchive(scope(),archive,"native-identity-archive");
            assertTrue(restored.list(scope()).length()>0);for(int i=0;i<restored.list(scope()).length();i++){JSONObject row=restored.list(scope()).getJSONObject(i);assertTrue(row.getBoolean("archiveOnly"));assertFalse(row.getBoolean("active"));}
            assertEquals(0,restored.listCandidates(scope()).length());assertEquals(0,restored.search(scope(),"小林",8).length());
        }
    }

    @Test public void exactIdentityDonorsRemainDependenciesThroughReconsiderCorrectionForgetAndColdRead()throws Exception {
        try(ProjectMemoryReviewUiNativeTest.Fixture f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            f.enter();JSONObject person=person(f,"林负责项目","林","咖啡");JSONObject entityDraft=stage(f,"北极星是当前项目名称");
            JSONObject entity=accept(f,annotate(f,input(entityDraft,subject("entity","北极星"),"项目阶段","开发")));
            JSONObject pending=stage(f,"林负责北极星"),edited=annotate(f,input(pending,existing(person,"subject"),"负责项目","北极星").put("relationTarget",existing(entity,"subject")));
            assertEquals(2,edited.getJSONArray("recordRefs").length());assertEquals(3,edited.getJSONArray("sourceRefs").length());
            JSONObject rejected=f.result("rejectCandidate",ref(edited).put("reason","先核对原件" )).getJSONObject("candidate");
            JSONObject draft=j("text",rejected.getString("text"),"layer",rejected.getString("layer"),"reason",rejected.getString("reason"),"applicability",rejected.getString("applicability"),"confidence",rejected.getDouble("confidence"));
            JSONObject reconsidered=f.result("reconsiderRejected",ref(rejected).put("draft",draft)).getJSONObject("candidate");assertEquals("assertion_review",reconsidered.getString("formation"));assertEquals(2,reconsidered.getJSONArray("recordRefs").length());
            JSONObject changed=f.result("correctReviewed",j("id",person.getString("id"),"expectedRevision",person.getLong("revision"),"text","林的偏好已更正")).getJSONObject("record");
            JSONObject invalid=candidate(f,edited);assertEquals("invalidated",invalid.getString("status"));assertFalse(invalid.has("text"));assertFalse(invalid.has("reason"));assertFalse(f.request("acceptCandidate",ref(reconsidered)).getBoolean("ok"));
            assertEquals("invalidated",cold(f).getCandidate(scope(),edited.getString("id")).getString("status"));
            JSONObject next=stage(f,"重新核对林负责北极星");JSONObject linked=accept(f,annotate(f,input(next,existing(changed,"subject"),"负责项目","北极星").put("relationTarget",existing(entity,"subject"))));
            f.result("forgetReviewed",j("id",entity.getString("id"),"expectedRevision",entity.getLong("revision")));
            assertThrows(Exception.class,()->book(f).get(scope(),linked.getString("id")));assertThrows(Exception.class,()->cold(f).get(scope(),linked.getString("id")));assertFalse(candidate(f,next).has("text"));
            JSONObject another=stage(f,"不能沿用已遗忘的北极星身份");assertFalse(f.request("reviewCandidateAssertion",input(another,existing(entity,"subject"),"阶段","待核对")).getBoolean("ok"));assertEquals(another.getLong("revision"),candidate(f,another).getLong("revision"));
        }
    }

    @Test public void planDatesAndProseCorrectionsNeverProveOccurrenceOrReuseAnOldTarget()throws Exception {
        try(ProjectMemoryReviewUiNativeTest.Fixture f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            f.enter();JSONObject pending=stage(f,"计划前往火星，目前没有成行证据");
            JSONObject edited=annotate(f,input(pending,j("mode","owner"),"旅行计划","火星").put("kind","imagined").put("eventTime",100L).put("validFrom",100L).put("relationTarget",subject("entity","火星")));
            JSONObject record=accept(f,edited),view=f.result("cognitionProjection",j("subjectId","owner"));
            assertEquals(0,view.getJSONArray("current").length());assertEquals("imagined_not_real_world_fact",view.getJSONArray("history").getJSONObject(0).getString("assertionStatus"));
            JSONObject available=stage(f,"独立候选");assertEquals(0,f.result("prepareAssertionReview",ref(available).put("query","火星")).getJSONArray("identities").length());
            JSONObject corrected=f.result("correctReviewed",j("id",record.getString("id"),"expectedRevision",record.getLong("revision"),"text","计划改为木星，仍没有成行证据")).getJSONObject("record");
            assertEquals("imagined",corrected.getJSONObject("assertion").getString("kind"));assertFalse(corrected.getJSONObject("assertion").has("objectId"));assertFalse(corrected.getJSONObject("assertion").has("objectType"));assertTrue(corrected.has("assertionReview"));
            assertEquals(0,f.result("cognitionProjection",j("subjectId","owner")).getJSONArray("current").length());assertEquals("imagined",cold(f).get(scope(),record.getString("id")).getJSONObject("assertion").getString("kind"));
            assertEquals(0,f.memory.handle("search",j("scope",scope(),"query","木星")).getJSONArray("records").length());
        }
    }

    @Test public void sourceChangesClosedInputsAndStorageFailureCannotPartiallyRewriteTheCandidate()throws Exception {
        try(ProjectMemoryReviewUiNativeTest.Fixture f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            f.enter();JSONObject donor=person(f,"甲是已核对的人物","甲","咖啡"),pending=stage(f,"甲的新线索");
            JSONObject nativeInput=input(pending,existing(donor,"subject"),"线索","仍待核对");
            assertFalse(f.request("reviewCandidateAssertion",new JSONObject(nativeInput.toString()).put("scope",j("ownerId","foreign","spaceId","private"))).getBoolean("ok"));
            assertFalse(f.request("reviewCandidateAssertion",new JSONObject(nativeInput.toString()).put("kind","user_said")).getBoolean("ok"));
            assertEquals(pending.getLong("revision"),candidate(f,pending).getLong("revision"));
            File file=new File(f.app.getFilesDir(),"memory/records.json"),backup=new File(f.app.getFilesDir(),"memory/identity-before-write.json");
            Files.move(file.toPath(),backup.toPath());Files.createDirectory(file.toPath());
            try{assertFalse(f.request("reviewCandidateAssertion",nativeInput).getBoolean("ok"));assertFalse(candidate(f,pending).has("assertion"));assertFalse(candidate(f,pending).has("recordRefs"));assertEquals(pending.getLong("revision"),candidate(f,pending).getLong("revision"));}
            finally{Files.delete(file.toPath());Files.move(backup.toPath(),file.toPath());}
            assertFalse(cold(f).getCandidate(scope(),pending.getString("id")).has("assertion"));JSONObject edited=annotate(f,nativeInput);assertEquals(1,edited.getJSONArray("recordRefs").length());
            JSONObject rejected=f.result("rejectCandidate",ref(edited).put("reason","本次不接受")).getJSONObject("candidate");f.result("archiveCandidates",j("selections",new JSONArray().put(ref(rejected))));
            JSONArray history=cold(f).listCandidateHistory(scope());assertEquals("assertion_review",history.getJSONObject(0).getString("formation"));assertEquals(1,history.getJSONObject(0).getJSONArray("recordRefs").length());
            JSONObject changed=stage(f,"修改前的原件");SourceRef source=SourceRef.fromJson(changed.getJSONArray("sourceRefs").getJSONObject(0));var address=SourceRef.workspaceAddress(source.sourceId);
            f.io.submit(()->f.workspace.handle("write",j("projectId",address.projectId,"path",address.path,"content","原件已经换版","expectedVersion",Math.toIntExact(source.revision)))).get(10,TimeUnit.SECONDS);
            assertFalse(f.request("reviewCandidateAssertion",input(changed,subject("person","乙"),"线索","旧原件")).getBoolean("ok"));assertFalse(candidate(f,changed).has("text"));
        }
    }

    @Test public void aLaterFactKeepsTheExactIdentityWhileOrdinarySemanticParentsStillRequireCurrentFacts()throws Exception {
        try(ProjectMemoryReviewUiNativeTest.Fixture f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            f.enter();JSONObject firstDraft=stage(f,"林过去住A城"),first=accept(f,annotate(f,input(firstDraft,subject("person","林"),"住址","A城").put("eventTime",10L)));
            JSONObject other=f.memory.handle("save",j("scope",scope(),"record",j("text","另一条明确保存的归纳依据"))).getJSONObject("record");
            JSONObject summaryDraft=book(f).proposeConsolidation(scope(),j("layer","fact","text","以原两条当前事实为前提的归纳","reason","原两条同时有效时适用","applicability","原条件","confidence",0.5),new JSONArray().put(ref(first)).put(ref(other)),System.currentTimeMillis());
            JSONObject reviewedSummary=annotate(f,input(summaryDraft,existing(first,"subject"),"条件归纳","原条件"));
            assertFalse("An existing semantic parent cannot be reclassified as identity-only",reviewedSummary.has("identityParentIds"));
            JSONObject summary=accept(f,reviewedSummary);
            JSONObject secondDraft=stage(f,"林现在住B城"),second=accept(f,annotate(f,input(secondDraft,existing(first,"subject"),"住址","B城").put("eventTime",20L)));
            assertEquals(first.getString("id"),second.getJSONArray("identityParentIds").getString(0));assertFalse(book(f).get(scope(),first.getString("id")).getBoolean("active"));assertTrue(book(f).get(scope(),second.getString("id")).getBoolean("active"));assertFalse(book(f).get(scope(),summary.getString("id")).getBoolean("active"));
            assertEquals(second.getString("id"),f.memory.handle("search",j("scope",scope(),"query","现在住B城")).getJSONArray("records").getJSONObject(0).getString("id"));assertTrue(cold(f).get(scope(),second.getString("id")).getBoolean("active"));
            JSONObject dependentDraft=book(f).proposeConsolidation(scope(),j("layer","fact","text","以B为前提的旧归纳","reason","B和另一父记录共同支持","applicability","同条件","confidence",0.5),new JSONArray().put(ref(second)).put(ref(other)),System.currentTimeMillis());
            JSONObject dependent=accept(f,annotate(f,input(dependentDraft,existing(second,"subject"),"依存总结","旧总结").put("eventTime",25L)));assertFalse(dependent.has("identityParentIds"));
            JSONObject newerDraft=stage(f,"依存总结的后续变化"),newer=accept(f,annotate(f,input(newerDraft,existing(second,"subject"),"依存总结","新总结").put("eventTime",35L)));
            JSONObject thirdDraft=stage(f,"林后来住C城"),third=accept(f,annotate(f,input(thirdDraft,existing(second,"subject"),"住址","C城").put("eventTime",30L)));
            assertTrue("A bounded original parent chain may contain two historical identity bindings",cold(f).get(scope(),third.getString("id")).getBoolean("active"));
            assertThrows(SecurityException.class,()->f.memory.handle("save",j("scope",scope(),"record",j("text","不能自发身份豁免","identityParentIds",new JSONArray().put(first.getString("id"))))));
            File file=new File(f.app.getFilesDir(),"memory/records.json"),corrupt=new File(f.app.getFilesDir(),"invalid-identity-parent.json");JSONObject stored=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            JSONArray rows=stored.getJSONArray("records");for(int i=0;i<rows.length();i++)if(third.getString("id").equals(rows.getJSONObject(i).getString("id")))rows.getJSONObject(i).put("identityParentIds",new JSONArray().put(other.getString("id")));
            Files.write(corrupt.toPath(),stored.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThrows(Exception.class,()->new MemoryBook(corrupt));
            JSONObject history=f.result("searchHistory",j("query","后来住C城","limit",10));assertEquals(1,history.getJSONArray("matches").length());JSONObject oldHeader=history.getJSONArray("matches").getJSONObject(0);
            assertTrue(containsId(f.result("searchHistory",j("query","以B为前提的旧归纳","limit",10)).getJSONArray("matches"),dependent.getString("id")));
            JSONObject bumped=f.result("setCore",ref(first).put("core",true)).getJSONObject("record");
            assertFalse("Identity selection keeps an exact donor revision even for a metadata-only new revision",book(f).get(scope(),third.getString("id")).getBoolean("active"));
            assertEquals(0,f.result("searchHistory",j("query","后来住C城","limit",10)).getJSONArray("matches").length());assertFalse(f.request("readHistoryRecord",ref(oldHeader)).getBoolean("ok"));
            assertFalse(containsId(f.result("searchHistory",j("query","以B为前提的旧归纳","limit",10)).getJSONArray("matches"),dependent.getString("id")));
            JSONObject afterRevision=f.result("cognitionProjection",j("subjectId",third.getJSONObject("assertion").getString("subjectId")));for(String section:new String[]{"current","history","pendingConflicts","sourceVersions"})for(JSONObject retired:new JSONObject[]{second,third,dependent,newer})assertFalse(afterRevision.getJSONArray(section).toString().contains(retired.getString("id")));
            assertEquals(dependent.getString("text"),book(f).get(scope(),dependent.getString("id")).getString("text"));assertFalse(book(f).get(scope(),dependent.getString("id")).getBoolean("active"));
            f.result("forgetReviewed",j("id",first.getString("id"),"expectedRevision",bumped.getLong("revision")));
            assertThrows(Exception.class,()->cold(f).get(scope(),second.getString("id")));assertThrows(Exception.class,()->cold(f).get(scope(),third.getString("id")));assertEquals(0,f.memory.handle("search",j("scope",scope(),"query","林")).getJSONArray("records").length());
        }
    }

    @Test public void inheritingAnIdentityCannotExceedTheOriginalTwentySourceBudget()throws Exception {
        try(ProjectMemoryReviewUiNativeTest.Fixture f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            f.enter();SourceRef[] originals=new SourceRef[20];for(int i=0;i<originals.length;i++)originals[i]=original(f,"身份依据原件 "+i);
            JSONObject draft=stage(f,"20份原件中的同一明确人物",originals),donor=accept(f,annotate(f,input(draft,subject("person","有界人物"),"描述","原来源内的描述")));
            JSONObject pending=stage(f,"第21份独立原件");assertFalse(f.request("reviewCandidateAssertion",input(pending,existing(donor,"subject"),"新的描述","必须拒绝超预算继承")).getBoolean("ok"));
            JSONObject unchanged=candidate(f,pending);assertEquals(pending.getLong("revision"),unchanged.getLong("revision"));assertEquals(1,unchanged.getJSONArray("sourceRefs").length());assertFalse(unchanged.has("recordRefs"));assertFalse(unchanged.has("assertion"));assertFalse(cold(f).getCandidate(scope(),pending.getString("id")).has("identityParentIds"));
        }
    }
}
