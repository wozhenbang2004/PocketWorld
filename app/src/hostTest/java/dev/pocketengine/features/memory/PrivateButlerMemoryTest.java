package dev.pocketengine.features.memory;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** One formal Book/projection, original task receipts, exact source versions and member audiences. */
public final class PrivateButlerMemoryTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private final Map<String,SourceRef.State> sources=new HashMap<>();
    private final TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef("owner-work","native-run",7,"owner","private");
    private final TaskMemoryUse.ReceiptRef receipt=new TaskMemoryUse.ReceiptRef(task,"physical-request","original-answer");
    private final Host host=new Host();
    private final MemoryHostFence fence=new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}};
    private final class Host implements TaskMemoryUse.HostReader {
        TaskMemoryUse.Provided provided;
        public TaskMemoryUse.TaskState readTask(MemoryBook.Scope scope,String id){return new TaskMemoryUse.TaskState(task,"running",false);}
        public TaskMemoryUse.Provided readProvided(MemoryBook.Scope scope,TaskMemoryUse.ReceiptRef expected){return provided;}
    }
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private File file(){return new File(temp.getRoot(),"private-memory.json");}
    private MemoryBook book()throws Exception{return new MemoryBook(file(),(s,kind,id)->sources.get(kind+":"+id));}
    private TaskMemoryUse use(MemoryBook book){return new TaskMemoryUse(book,host,null);}
    private static JSONObject assertion(String predicate,String value,long event)throws Exception {
        return new JSONObject().put("version",1).put("kind","inferred").put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人")
                .put("predicate",predicate).put("object",value).put("eventTime",event).put("evidenceSpans",new JSONArray());
    }
    private static JSONObject row(JSONObject assertion)throws Exception{return new JSONObject().put("layer","preference").put("text",assertion.getString("object")).put("assertion",assertion);}
    private static Set<String> ids(JSONArray rows)throws Exception {Set<String> ids=new HashSet<>();for(int i=0;i<rows.length();i++)ids.add(rows.getJSONObject(i).getString("id"));return ids;}
    private static JSONObject ref(JSONObject row)throws Exception{return new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision"));}
    private void prove(TaskMemoryUse.Selection selection)throws Exception {
        JSONObject metadata=selection.metadata();host.provided=new TaskMemoryUse.Provided(receipt,metadata.getString("selectionId"),metadata.getString("contextDigest"),System.currentTimeMillis(),false,metadata.getJSONArray("records"),metadata.getJSONObject("selectionDiagnostics"));
    }
    private interface Checked {void run()throws Exception;}
    private static void stale(Checked action)throws Exception {try{action.run();fail("old owner context was admitted");}catch(SecurityException|IllegalStateException expected){}}

    @Test public void unrelatedOwnerTurnUsesSameCurrentProjectionAndRetainsLegacyCoreWithoutInventingSubjects()throws Exception {
        MemoryBook book=book();long now=System.currentTimeMillis();
        JSONObject legacy=book.save(scope(),new JSONObject().put("text","旧核心写作偏好").put("core",true),1);
        book.save(scope(),row(assertion("居住城市","HISTORICAL_CITY",100).put("condition","工作日期间")),2);
        JSONObject current=book.save(scope(),row(assertion("居住城市","CURRENT_CITY",200).put("condition","工作日期间").put("reviewAfter",now+86_400_000)),3);
        book.save(scope(),row(assertion("演练","IMAGINED_CITY",300).put("kind","imagined")),4);
        book.save(scope(),row(assertion("未来安排","FUTURE_CITY",400).put("validFrom",now+86_400_000)),5);
        book.save(scope(),row(assertion("旧安排","EXPIRED_CITY",500).put("validTo",now-1)),6);
        book.save(scope(),row(assertion("同事城市","OTHER_PERSON",100).put("subjectId","person:one").put("subjectType","person")),7);
        assertEquals(Set.of(legacy.getString("id")),ids(book.contextSnapshot(scope(),"",16,12000).getJSONArray("records")));
        JSONObject selected=book.ownerContextSnapshot(scope(),"",16,12000);
        assertEquals(Set.of(legacy.getString("id"),current.getString("id")),ids(selected.getJSONArray("records")));
        assertEquals(Set.of(current.getString("id")),ids(book.cognitionProjection(scope(),"owner",now).getJSONArray("current")));
        assertTrue(selected.getString("context").contains("工作日期间"));assertTrue(selected.getString("context").contains("reviewAfter"));
        for(String excluded:new String[]{"HISTORICAL_CITY","IMAGINED_CITY","FUTURE_CITY","EXPIRED_CITY","OTHER_PERSON"})assertFalse(selected.getString("context").contains(excluded));
        assertEquals("formal_cognition_projection",selected.getJSONObject("ownerContext").getString("source"));assertEquals(1,selected.getJSONObject("ownerContext").getInt("currentAvailable"));
        assertFalse(selected.getJSONObject("ownerContext").toString().contains("CURRENT_CITY"));assertEquals(0,selected.getJSONObject("queryPlan").getInt("additionalModelRequests"));
        assertEquals(ids(selected.getJSONArray("records")),ids(book().ownerContextSnapshot(scope(),"",16,12000).getJSONArray("records")));
    }

    @Test public void originalBudgetKeepsATaskMatchAndOwnerFactWithCoreAndRoundTripsBodyFreeDiagnostics()throws Exception {
        MemoryBook book=book();for(int i=0;i<16;i++)book.save(scope(),new JSONObject().put("text","核心偏好 "+i).put("core",true),i+1);
        JSONObject owner=book.save(scope(),row(assertion("回答习惯","OWNER_CURRENT_VALUE",100)),100);
        JSONObject match=book.save(scope(),new JSONObject().put("text","TASK_NEEDLE current task detail"),101);
        TaskMemoryUse use=use(book);TaskMemoryUse.Selection selected=use.prepareOwnerTaskRecall(scope(),task,"TASK_NEEDLE",4,12000,fence);
        JSONObject snapshot=selected.snapshot(),diagnostics=selected.metadata().getJSONObject("selectionDiagnostics");
        assertEquals(4,snapshot.getJSONArray("records").length());assertTrue(ids(snapshot.getJSONArray("records")).containsAll(Set.of(owner.getString("id"),match.getString("id"))));
        assertEquals("balanced_core_owner_task_v1",diagnostics.getString("policy"));assertTrue(diagnostics.toString().contains("owner_current"));assertTrue(diagnostics.toString().contains("task_query_match"));
        assertFalse(selected.metadata().toString().contains("OWNER_CURRENT_VALUE"));assertTrue(snapshot.getString("context").length()<=12000);
        prove(selected);assertEquals("provided_to_request",use.confirmTaskMemoryUse(scope(),selected,receipt,fence).getString("phase"));
        JSONObject tiny=book.ownerContextSnapshot(scope(),"TASK_NEEDLE",4,80);assertTrue(tiny.getString("context").length()<=80);assertEquals(0,tiny.getJSONArray("records").length());
        JSONObject zero=book.ownerContextSnapshot(scope(),"",0,0);assertEquals("",zero.getString("context"));assertEquals(0,zero.getJSONArray("records").length());
    }

    @Test public void unresolvedDifferencesHaveABoundedNoticeWithoutEitherBodyAndResolveRetiresThatContext()throws Exception {
        MemoryBook book=book();JSONObject a=book.save(scope(),row(assertion("饮食偏好","CONFLICT_BODY_A",100)),1),b=book.save(scope(),row(assertion("饮食偏好","CONFLICT_BODY_B",100)),2);
        JSONObject selected=book.ownerContextSnapshot(scope(),"",16,12000),dependency=book.contextDependency(selected);
        assertEquals(0,selected.getJSONArray("records").length());assertEquals(1,selected.getJSONObject("ownerContext").getInt("pendingConflictGroups"));assertTrue(selected.getString("context").contains("待核对"));
        assertFalse(selected.getString("context").contains("CONFLICT_BODY"));assertEquals(selected.getString("context").length(),selected.getJSONObject("selectionDiagnostics").getInt("charsUsed"));
        book.resolveAssertionConflictReviewed(scope(),new JSONArray().put(ref(a)).put(ref(b)),"replace",b.getString("id"),"主人核对原证据后选定",removed->{},System.currentTimeMillis());
        stale(()->book.requireContextDependency(dependency));JSONObject next=book.ownerContextSnapshot(scope(),"",16,12000);assertEquals(0,next.getJSONObject("ownerContext").getInt("pendingConflictGroups"));assertTrue(next.getString("context").contains("CONFLICT_BODY_B"));assertFalse(next.getString("context").contains("CONFLICT_BODY_A"));
    }

    @Test public void newOwnerFactCorrectionAndForgetFenceBothPendingRequestsAndReturnedOldSelections()throws Exception {
        MemoryBook book=book();JSONObject first=book.save(scope(),row(assertion("称呼","龟龟",100)),1);TaskMemoryUse use=use(book);
        TaskMemoryUse.Selection beforeAdd=use.prepareOwnerTaskRecall(scope(),task,"",1,12000,fence);prove(beforeAdd);
        book.save(scope(),row(assertion("饮食","素食",200)),2);
        stale(()->use.validateTaskMemorySelection(scope(),beforeAdd,fence));stale(()->use.confirmTaskMemoryUse(scope(),beforeAdd,receipt,fence));
        TaskMemoryUse.Selection beforeCorrection=use.prepareOwnerTaskRecall(scope(),task,"",16,12000,fence);prove(beforeCorrection);
        book.correctReviewed(scope(),first.getString("id"),1,"主人确认的新称呼",removed->{},System.currentTimeMillis());
        stale(()->use.validateTaskMemorySelection(scope(),beforeCorrection,fence));stale(()->use.confirmTaskMemoryUse(scope(),beforeCorrection,receipt,fence));
        TaskMemoryUse.Selection beforeForget=use.prepareOwnerTaskRecall(scope(),task,"",16,12000,fence);prove(beforeForget);
        book.forgetReviewed(scope(),first.getString("id"),2);
        stale(()->use.validateTaskMemorySelection(scope(),beforeForget,fence));stale(()->use.confirmTaskMemoryUse(scope(),beforeForget,receipt,fence));
        assertFalse(book().ownerContextSnapshot(scope(),"",16,12000).getString("context").contains("主人确认的新称呼"));
    }

    @Test public void exactSourceRevisionChangeInvalidatesBothPendingAndPreviouslyProvidedOwnerContext()throws Exception {
        SourceRef source=new SourceRef("message","original-owner-utterance",1,"owner","private");sources.put("message:"+source.sourceId,new SourceRef.State(source,false));
        MemoryBook book=book();book.save(scope(),row(assertion("早餐","早餐偏好",100)).put("sourceRefs",new JSONArray().put(source.toJson())),1);TaskMemoryUse use=use(book);
        TaskMemoryUse.Selection selected=use.prepareOwnerTaskRecall(scope(),task,"",16,12000,fence);prove(selected);JSONObject dependency=book.contextDependency(book.ownerContextSnapshot(scope(),"",16,12000));
        sources.put("message:"+source.sourceId,new SourceRef.State(new SourceRef("message",source.sourceId,2,"owner","private"),false));
        stale(()->use.validateTaskMemorySelection(scope(),selected,fence));stale(()->use.confirmTaskMemoryUse(scope(),selected,receipt,fence));stale(()->book.requireContextDependency(dependency));assertEquals(0,book.ownerContextSnapshot(scope(),"",16,12000).getJSONArray("records").length());
    }

    @Test public void sameTextSemanticCorrectionKeepsLineageRetiresDerivedEvidenceAndPreservesFutureValidity()throws Exception {
        SourceRef oldSource=new SourceRef("message","earlier-owner-turn",1,"owner","private"),newSource=new SourceRef("message","later-owner-turn",1,"owner","private");
        for(SourceRef source:new SourceRef[]{oldSource,newSource})sources.put("message:"+source.sourceId,new SourceRef.State(source,false));
        MemoryBook book=book();JSONObject old=book.save(scope(),row(assertion("工作时间","下午沟通",100)).put("sourceRefs",new JSONArray().put(oldSource.toJson())),1);
        JSONObject child=book.save(scope(),new JSONObject().put("text","从旧条件派生").put("sourceIds",new JSONArray().put(old.getString("id"))),2),snapshot=book.ownerContextSnapshot(scope(),"",16,12000),dependency=book.contextDependency(snapshot);
        String quote="从下周开始，工作日再按这个时间联系。";long nextWeek=System.currentTimeMillis()+604_800_000;
        JSONObject claim=new JSONObject().put("kind","inferred").put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人").put("predicate","工作时间").put("object","下午沟通").put("condition","仅工作日").put("validFrom",nextWeek).put("reviewAfter",nextWeek+86_400_000).put("quote",quote).put("offset",0);
        JSONObject checked=LearningAssertionDraft.checkedAssertion(claim,"下午沟通",newSource,quote);int[] retired={0};
        JSONObject changed=book.correctAssertionReviewed(scope(),old.getString("id"),1,"下午沟通",checked,new JSONArray().put(newSource.toJson()),removed->retired[0]=removed.length(),System.currentTimeMillis()).getJSONObject("record");
        assertEquals(2,changed.getLong("revision"));assertEquals(2,changed.getJSONArray("sourceRefs").length());assertEquals(2,retired[0]);assertEquals("仅工作日",changed.getJSONObject("assertion").getString("condition"));assertEquals(nextWeek,changed.getJSONObject("assertion").getLong("validFrom"));
        assertEquals(newSource,SourceRef.fromJson(changed.getJSONObject("assertion").getJSONArray("evidenceSpans").getJSONObject(0).getJSONObject("sourceRef")));
        assertThrows(IllegalArgumentException.class,()->book.get(scope(),child.getString("id")));stale(()->book.requireContextDependency(dependency));assertEquals(0,book.ownerContextSnapshot(scope(),"",16,12000).getJSONArray("records").length());
        assertEquals("下午沟通",book().cognitionProjection(scope(),"owner",nextWeek+1).getJSONArray("current").getJSONObject(0).getString("text"));
    }

    private static final class Audience implements ReadAudience {
        final JSONArray records=new JSONArray();boolean revoked;
        Audience allow(JSONObject row)throws Exception{records.put(ref(row));return this;}
        public String ownerId(){return "owner";}public String spaceId(){return "private";}public String audienceId(){return "original-team/member";}public long revision(){return 1;}
        public JSONArray allowedRecords()throws Exception{return new JSONArray(records.toString());}public JSONArray allowedSources(){return new JSONArray();}
        public void checkCurrent(){if(revoked)throw new SecurityException("original audience withdrawn");}
    }
    @Test public void existingAudienceAutoSelectionNeverExpandsRecordsSourcesOrHiddenConflictCounts()throws Exception {
        MemoryBook book=book();JSONObject seed=book.save(scope(),row(assertion("允许的偏好","ALLOWED_SEED",100)),1);
        JSONObject allowed=book.correctReviewed(scope(),seed.getString("id"),1,"ALLOWED_VALUE",removed->{},1000).getJSONObject("record");
        book.save(scope(),new JSONObject().put("text","PRIVATE_CORE_VALUE").put("core",true),2);
        book.save(scope(),row(assertion("未选事实","PRIVATE_CURRENT_VALUE",100)),3);
        book.save(scope(),row(assertion("未选分歧","PRIVATE_CONFLICT_A",100)),4);book.save(scope(),row(assertion("未选分歧","PRIVATE_CONFLICT_B",100)),5);
        book.save(scope(),row(assertion("允许的偏好","PRIVATE_CHALLENGER",2000)),6);
        Audience audience=new Audience().allow(allowed);TaskMemoryUse use=use(book);TaskMemoryUse.Selection selected=use.prepareOwnerTaskRecall(scope(),task,"",16,12000,fence,audience);
        assertEquals(Set.of(allowed.getString("id")),ids(selected.snapshot().getJSONArray("records")));assertFalse(selected.snapshot().toString().contains("PRIVATE_"));assertEquals(0,selected.snapshot().getJSONObject("ownerContext").getInt("pendingConflictGroups"));
        book.save(scope(),row(assertion("又一未选事实","PRIVATE_LATER_VALUE",100)),6);use.validateTaskMemorySelection(scope(),selected,fence);
        assertEquals(0,book.ownerContextSnapshot(scope(),"",16,12000,new Audience()).getJSONArray("records").length());
        assertThrows(SecurityException.class,()->use.readAudienceSource(scope(),new SourceRef("message","unselected-original",1,"owner","private"),0,1,audience));
        audience.revoked=true;stale(()->use.validateTaskMemorySelection(scope(),selected,fence));
    }

    @Test public void currentOwnerPathCannotBeUsedAsAnotherPersonsOrProjectMemoryFallback()throws Exception {
        MemoryBook book=book();book.save(scope(),row(assertion("私人偏好","PRIVATE_ONLY",100)),1);
        assertThrows(SecurityException.class,()->book.ownerContextSnapshot(new JSONObject().put("ownerId","other").put("spaceId","private"),"",16,12000));
        TaskMemoryUse.TaskRef foreign=new TaskMemoryUse.TaskRef("foreign","foreign-run",1,"other","private");
        assertThrows(SecurityException.class,()->use(book).prepareOwnerTaskRecall(new JSONObject().put("ownerId","other").put("spaceId","private"),foreign,"",16,12000,fence));
    }
    @Test public void excessCorrectionLineageFailsBeforeRetiringAnyExistingContext()throws Exception {
        JSONArray originalSources=new JSONArray();for(int i=0;i<20;i++){SourceRef source=new SourceRef("message","source-"+i,1,"owner","private");sources.put("message:"+source.sourceId,new SourceRef.State(source,false));originalSources.put(source.toJson());}
        SourceRef additional=new SourceRef("message","twenty-first-source",1,"owner","private");sources.put("message:"+additional.sourceId,new SourceRef.State(additional,false));
        MemoryBook book=book();JSONObject old=book.save(scope(),row(assertion("偏好","原正式事实",100)).put("sourceRefs",originalSources),1),dependency=book.contextDependency(book.ownerContextSnapshot(scope(),"",16,12000));
        JSONObject assertion=assertion("偏好","新的正式事实",200).put("evidenceSpans",new JSONArray().put(new JSONObject().put("sourceRef",additional.toJson()).put("offset",0).put("chars",1).put("sha256",TaskMemoryUse.digest("新"))));
        boolean[] retired={false};assertThrows(IllegalArgumentException.class,()->book.correctAssertionReviewed(scope(),old.getString("id"),1,"新的正式事实",assertion,new JSONArray().put(additional.toJson()),removed->retired[0]=true,200));
        assertFalse(retired[0]);assertEquals(1,book.get(scope(),old.getString("id")).getLong("revision"));book.requireContextDependency(dependency);
    }
    @Test public void changedOwnerConflictNoticeRetiresDerivedHistoryEvenWhenProtectedActiveRecordIsUnchanged()throws Exception {
        MemoryBook book=book();JSONObject seed=book.save(scope(),row(assertion("联系时间","较早的说法",100)),1),protectedRow=book.correctReviewed(scope(),seed.getString("id"),1,"主人核对的时间",removed->{},1000).getJSONObject("record");
        JSONObject before=book.ownerContextSnapshot(scope(),"",16,12000),validation=new JSONObject().put("memory",new JSONArray().put(ref(protectedRow))).put("ownerContextRevision",before.getJSONObject("ownerContext").getString("revision")),dependency=TaskMemoryHistory.dependency(validation);
        book.save(scope(),row(assertion("联系时间","后来未经确认的矛盾推断",2000)),2);JSONObject after=book.ownerContextSnapshot(scope(),"",16,12000);
        assertEquals(ids(before.getJSONArray("records")),ids(after.getJSONArray("records")));assertEquals(1,after.getJSONObject("ownerContext").getInt("pendingConflictGroups"));
        validation.put("ownerContextRevision",after.getJSONObject("ownerContext").getString("revision"));assertFalse(TaskMemoryHistory.current(dependency,validation));
        JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","原主人请求")).put(new JSONObject().put("role","assistant").put("content","基于旧认知的回复").put("provider_data",new JSONObject().put("text","旧派生文本")).put("memoryContext",dependency));
        assertEquals(1,TaskMemoryHistory.retire(history,validation,false));assertEquals("原主人请求",history.getJSONObject(0).getString("content"));assertEquals(TaskMemoryHistory.RETIRED,history.getJSONObject(1).getString("content"));assertFalse(history.getJSONObject(1).has("provider_data"));
    }
    @Test public void frozenOwnerWorkReadsRetireDerivedHistoryOnColdRestoreOrDifferentResult()throws Exception {
        JSONObject first=new JSONObject().put("taskId",java.util.UUID.randomUUID().toString()).put("deliveryId",java.util.UUID.randomUUID().toString()).put("resultDigest","a".repeat(64));
        JSONObject second=new JSONObject().put("taskId",java.util.UUID.randomUUID().toString()).put("deliveryId",java.util.UUID.randomUUID().toString()).put("resultDigest","b".repeat(64));
        JSONObject cold=new JSONObject().put("memory",new JSONArray()),current=new JSONObject(cold.toString()).put("ownerWorkResults",new JSONArray().put(first).put(second)),dependency=TaskMemoryHistory.dependency(current);
        assertTrue(TaskMemoryHistory.current(dependency,new JSONObject(cold.toString()).put("ownerWorkResults",new JSONArray().put(second).put(first))));assertFalse(TaskMemoryHistory.current(dependency,cold));
        JSONObject changed=new JSONObject(first.toString()).put("resultDigest","c".repeat(64));assertFalse(TaskMemoryHistory.current(dependency,new JSONObject(cold.toString()).put("ownerWorkResults",new JSONArray().put(changed).put(second))));
        JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","原主人委托")).put(new JSONObject().put("role","assistant").put("content","基于已读工作结果的回复").put("provider_data",new JSONObject().put("text","不能从冷恢复重新曝露的旧结果")).put("memoryContext",dependency));
        assertEquals(1,TaskMemoryHistory.retire(history,cold,false));assertEquals("原主人委托",history.getJSONObject(0).getString("content"));assertEquals(TaskMemoryHistory.RETIRED,history.getJSONObject(1).getString("content"));assertFalse(history.getJSONObject(1).has("provider_data"));
        assertTrue(TaskMemoryHistory.current(TaskMemoryHistory.dependency(cold),new JSONObject(cold.toString()).put("ownerWorkResults",new JSONArray())));
        JSONObject forged=new JSONObject(first.toString()).put("content","不能把正文混入native依赖");assertThrows(SecurityException.class,()->TaskMemoryHistory.dependency(new JSONObject(cold.toString()).put("ownerWorkResults",new JSONArray().put(forged))));
        JSONArray excess=new JSONArray();for(int i=0;i<17;i++)excess.put(first);assertThrows(SecurityException.class,()->TaskMemoryHistory.dependency(new JSONObject(cold.toString()).put("ownerWorkResults",excess)));
    }
}
