package dev.pocketengine.features.memory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Native scope issuer is controlled here; no model JSON can construct this authority in product. */
public final class MemoryReadAudienceTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject row(String text)throws Exception{return new JSONObject().put("text",text);}
    private File file(){return new File(temp.getRoot(),"memory.json");}
    private static final class Audience implements ReadAudience {
        String owner="owner",space="private",id="team:original/member:research";long revision=1;boolean revoked;
        JSONArray records=new JSONArray(),sources=new JSONArray();int checks;
        public String ownerId(){return owner;}public String spaceId(){return space;}public String audienceId(){return id;}public long revision(){return revision;}
        public JSONArray allowedRecords()throws Exception{return new JSONArray(records.toString());}
        public JSONArray allowedSources()throws Exception{return new JSONArray(sources.toString());}
        public void checkCurrent(){checks++;if(revoked)throw new SecurityException("original member scope revoked");}
        Audience allow(JSONObject record)throws Exception{records.put(new JSONObject().put("id",record.getString("id")).put("revision",record.getLong("revision")));return this;}
        Audience span(SourceRef source,int offset,int chars)throws Exception{sources.put(new JSONObject().put("sourceRef",source.toJson()).put("offset",offset).put("chars",chars).put("unit","UTF-16"));return this;}
    }
    private interface Checked{void run()throws Exception;}
    private void denied(Checked action)throws Exception{try{action.run();fail("unselected native authority accepted");}catch(SecurityException|IllegalArgumentException|IllegalStateException expected){}}
    private static final class Backend implements MemoryRetrievalBackend {
        List<Document> documents=new ArrayList<>();List<Match> hits=new ArrayList<>();Runnable duringFind;
        public void synchronize(List<Document> docs){documents=new ArrayList<>(docs);}
        public List<Match> find(String owner,String space,String query,int limit){if(duringFind!=null)duringFind.run();return hits;}
        public void invalidate(){documents.clear();}public JSONObject status()throws Exception{return new JSONObject().put("backend","controlled_native_index");}public void close(){}
    }
    private TaskMemoryUse direct(MemoryBook book,TaskMemoryUse.SourceReader source){return new TaskMemoryUse(book,null,source);}
    private SourceRef source(){return new SourceRef("message","original-message",3,"owner","private");}

    @Test public void emptyMemberNeverFallsBackToPrivateCoreButOwnerStillReadsOwnCore()throws Exception{
        MemoryBook book=new MemoryBook(file());book.save(scope(),row("OWNER_PRIVATE_CORE_DO_NOT_SHARE").put("core",true),1);Audience audience=new Audience();
        assertEquals(0,book.contextSnapshot(scope(),"",16,12000,audience).getJSONArray("records").length());
        assertEquals(0,book.search(scope(),"OWNER_PRIVATE",16,audience).length());
        assertTrue(book.contextSnapshot(scope(),"",16,12000).getString("context").contains("OWNER_PRIVATE_CORE_DO_NOT_SHARE"));
    }
    @Test public void formalAllowlistFiltersBeforeIndexAndUnselectedCoreOrDerivedRecordsNeverEnterIt()throws Exception{
        Backend backend=new Backend();MemoryBook book=new MemoryBook(file(),null,backend);JSONObject selected=book.save(scope(),row("coffee selected"),1);
        JSONObject privateCore=book.save(scope(),row("coffee PRIVATE_CORE").put("core",true),2);
        JSONObject derived=book.save(scope(),row("coffee PRIVATE_DERIVATION").put("sourceIds",new JSONArray().put(selected.getString("id"))),3);
        backend.hits=List.of(new MemoryRetrievalBackend.Match(privateCore.getString("id"),1),new MemoryRetrievalBackend.Match(derived.getString("id"),1),new MemoryRetrievalBackend.Match(selected.getString("id"),1));
        JSONArray result=book.search(scope(),"coffee",16,new Audience().allow(selected));assertEquals(1,result.length());assertEquals(selected.getString("id"),result.getJSONObject(0).getString("id"));
        assertEquals(1,backend.documents.size());assertEquals(selected.getString("id"),backend.documents.get(0).id);
    }
    @Test public void selectedRecordIsAnExactVersionNotAFutureRevisionGrant()throws Exception{
        MemoryBook book=new MemoryBook(file());JSONObject selected=book.save(scope(),row("first selected").put("core",true),1);Audience audience=new Audience().allow(selected);
        assertEquals(1,book.contextSnapshot(scope(),"",16,12000,audience).getJSONArray("records").length());
        book.save(scope(),new JSONObject(selected.toString()).put("text","replacement PRIVATE_REVISION"),2);
        assertEquals(0,book.contextSnapshot(scope(),"",16,12000,audience).getJSONArray("records").length());
        assertTrue(book.contextSnapshot(scope(),"",16,12000).getString("context").contains("replacement PRIVATE_REVISION"));
    }
    @Test public void memberScopeRevocationDuringIndexReadStopsReturnInsteadOfReturningCapturedBodies()throws Exception{
        Backend backend=new Backend();MemoryBook book=new MemoryBook(file(),null,backend);JSONObject selected=book.save(scope(),row("coffee selected"),1);Audience audience=new Audience().allow(selected);
        backend.hits=List.of(new MemoryRetrievalBackend.Match(selected.getString("id"),1));backend.duringFind=()->audience.revoked=true;
        denied(()->book.search(scope(),"coffee",16,audience));assertTrue(audience.checks>=3);
    }
    @Test public void nativeSelectionCopiedListsCannotBeMutatedIntoAnotherGrant()throws Exception{
        MemoryBook book=new MemoryBook(file());JSONObject selected=book.save(scope(),row("selected").put("core",true),1);Audience audience=new Audience().allow(selected);
        MemoryAudienceSelection captured=MemoryAudienceSelection.capture(audience,MemoryBook.Scope.from(scope()));
        JSONArray copy=audience.allowedRecords();copy.getJSONObject(0).put("revision",999);captured.check();
        audience.records.getJSONObject(0).put("revision",999);denied(captured::check);
    }
    @Test public void malformedCrossScopeOrDuplicatedNativeSelectionsFailClosed()throws Exception{
        SourceRef ref=source();Audience wrong=new Audience().span(ref,0,2);wrong.owner="other";
        denied(()->MemoryAudienceSelection.capture(wrong,MemoryBook.Scope.from(scope())));
        JSONArray duplicate=new JSONArray().put(new JSONObject().put("id","one").put("revision",1)).put(new JSONObject().put("id","one").put("revision",2));denied(()->ReadAudience.checkedRecords(duplicate));
        JSONArray fakeRevision=new JSONArray().put(new JSONObject().put("id","one").put("revision","1"));denied(()->ReadAudience.checkedRecords(fakeRevision));
        JSONArray span=new Audience().span(ref,0,2).sources;span.getJSONObject(0).put("grantFullText",true);denied(()->ReadAudience.checkedSources(span,"owner","private"));
        JSONArray foreign=new Audience().span(new SourceRef("message","other",1,"owner","group:other"),0,2).sources;denied(()->ReadAudience.checkedSources(foreign,"owner","private"));
    }
    @Test public void directDiscussionReadReturnsOnlySelectedSpanWithoutPrivateTitleOrProvidedClaim()throws Exception{
        SourceRef ref=source();MemoryBook book=new MemoryBook(file());int[] reads={0};TaskMemoryUse use=direct(book,r->{reads[0]++;return new TaskMemoryUse.Evidence(ref,"PRIVATE_TITLE_NOT_SELECTED","prefix selected suffix");});
        JSONObject result=use.readAudienceSource(scope(),ref,7,8,new Audience().span(ref,7,8));assertEquals("selected",result.getString("text"));assertEquals("所选原文范围",result.getString("title"));
        assertEquals(2,reads[0]);assertTrue(result.getBoolean("readObserved"));assertFalse(result.getBoolean("modelUseVerified"));assertFalse(result.getBoolean("formalMemorySaved"));assertFalse(result.getBoolean("executionAuthorized"));
        assertFalse(result.has("requestId"));assertFalse(result.has("receiptId"));assertFalse(result.toString().contains("PRIVATE_TITLE_NOT_SELECTED"));assertEquals(7,result.getJSONObject("readRange").getInt("offset"));
    }
    @Test public void outsideSpanWrongVersionOrMissingAudienceDoesNotReadAnyOriginalBytes()throws Exception{
        SourceRef ref=source();int[] reads={0};TaskMemoryUse use=direct(new MemoryBook(file()),r->{reads[0]++;return new TaskMemoryUse.Evidence(ref,"title","private selected body");});Audience audience=new Audience().span(ref,8,8);
        denied(()->use.readAudienceSource(scope(),ref,0,16,audience));denied(()->use.readAudienceSource(scope(),ref,8,9,audience));
        denied(()->use.readAudienceSource(scope(),new SourceRef(ref.kind,ref.sourceId,4,"owner","private"),8,8,audience));denied(()->use.readAudienceSource(scope(),ref,8,8,null));assertEquals(0,reads[0]);
    }
    @Test public void formalRecordSelectionAloneDoesNotGrantWholeSourceOrEvenOneUnselectedCharacter()throws Exception{
        SourceRef ref=source();MemoryBook book=new MemoryBook(file(),(s,k,id)->new SourceRef.State(ref,false));JSONObject record=book.importFromSources(scope(),row("selected formal fact").put("sourceRefs",new JSONArray().put(ref.toJson())),1);
        int[] reads={0};TaskMemoryUse use=direct(book,r->{reads[0]++;return new TaskMemoryUse.Evidence(ref,"private title","private original");});denied(()->use.readAudienceSource(scope(),ref,0,1,new Audience().allow(record)));assertEquals(0,reads[0]);
    }
    @Test public void revokeDuringSourceReadStopsBeforeReturningAnyCapturedText()throws Exception{
        SourceRef ref=source();Audience audience=new Audience().span(ref,0,8);int[] reads={0};TaskMemoryUse use=direct(new MemoryBook(file()),r->{reads[0]++;audience.revoked=true;return new TaskMemoryUse.Evidence(ref,"private title","selected bytes");});
        denied(()->use.readAudienceSource(scope(),ref,0,8,audience));assertEquals(1,reads[0]);
    }
    @Test public void sourceMustRemainSameRevisionAndSameBodyAfterTheNativeRead()throws Exception{
        SourceRef ref=source();Audience audience=new Audience().span(ref,0,8);int[] reads={0};TaskMemoryUse use=direct(new MemoryBook(file()),r->new TaskMemoryUse.Evidence(ref,"title",++reads[0]==1?"selected before":"different after"));
        JSONObject changed=use.readAudienceSource(scope(),ref,0,8,audience);assertEquals("source_changed",changed.getString("status"));assertFalse(changed.has("text"));assertEquals(2,reads[0]);
    }
    @Test public void unicodeRangeDoesNotSplitCodePointsOrGrowBeyondApprovedSpan()throws Exception{
        SourceRef ref=source();TaskMemoryUse use=direct(new MemoryBook(file()),r->new TaskMemoryUse.Evidence(ref,"title","a🙂z"));Audience audience=new Audience().span(ref,1,2);
        JSONObject result=use.readAudienceSource(scope(),ref,1,2,audience);assertEquals("🙂",result.getString("text"));assertEquals(2,result.getJSONObject("readRange").getInt("chars"));
        denied(()->use.readAudienceSource(scope(),ref,2,1,audience));
    }
    @Test public void preparedMemberReferenceIsRetiredOnScopeChangeOrRecordForget()throws Exception{
        MemoryBook book=new MemoryBook(file());JSONObject record=book.save(scope(),row("selected formal core").put("core",true),1);Audience audience=new Audience().allow(record);
        TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef("task","run",1,"owner","private");TaskMemoryUse.HostReader host=new TaskMemoryUse.HostReader(){public TaskMemoryUse.TaskState readTask(MemoryBook.Scope s,String id){return new TaskMemoryUse.TaskState(task,"running",false);}public TaskMemoryUse.Provided readProvided(MemoryBook.Scope s,TaskMemoryUse.ReceiptRef ref){return null;}};
        MemoryHostFence fence=new MemoryHostFence(){public void check(){}public JSONObject admit(Admission a)throws Exception{return a.run();}};TaskMemoryUse use=new TaskMemoryUse(book,host,null);
        TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"",16,12000,fence,audience);assertEquals(1,use.validateTaskMemorySelection(scope(),selection,fence).getJSONArray("records").length());
        audience.revision++;denied(()->use.validateTaskMemorySelection(scope(),selection,fence));audience.revision--;
        book.forgetReviewed(scope(),record.getString("id"),1);denied(()->use.validateTaskMemorySelection(scope(),selection,fence));
    }
    @Test public void selectionAllowsNoCrossOwnerCoreEvenWhenNativeIndexerLies()throws Exception{
        Backend backend=new Backend();MemoryBook book=new MemoryBook(file(),null,backend);JSONObject own=book.save(scope(),row("coffee own"),1),other=book.save(new JSONObject().put("ownerId","other").put("spaceId","private"),row("coffee FOREIGN"),2);
        backend.hits=List.of(new MemoryRetrievalBackend.Match(other.getString("id"),1),new MemoryRetrievalBackend.Match(own.getString("id"),1));JSONArray result=book.search(scope(),"coffee",16,new Audience().allow(own));assertEquals(1,result.length());assertFalse(result.toString().contains("FOREIGN"));
    }
    @Test public void explicitMemberSpanCannotReviveAnAlreadyForgottenSourceVersion()throws Exception{
        SourceRef ref=source();MemoryBook book=new MemoryBook(file(),(s,k,id)->new SourceRef.State(ref,false));
        JSONObject record=book.importFromSources(scope(),row("saved from source").put("sourceRefs",new JSONArray().put(ref.toJson())),1);
        book.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));assertTrue(book.isSourceForgotten(scope(),ref));
        int[] reads={0};TaskMemoryUse use=direct(book,r->{reads[0]++;return new TaskMemoryUse.Evidence(ref,"title","selected bytes");});
        denied(()->use.readAudienceSource(scope(),ref,0,8,new Audience().span(ref,0,8)));assertEquals(0,reads[0]);
    }
    @Test public void sourceForgetDuringAnAllowedNativeReadDiscardsTheCapturedBody()throws Exception{
        SourceRef ref=source();MemoryBook book=new MemoryBook(file(),(s,k,id)->new SourceRef.State(ref,false));
        JSONObject record=book.importFromSources(scope(),row("saved from source").put("sourceRefs",new JSONArray().put(ref.toJson())),1);int[] reads={0};
        TaskMemoryUse use=direct(book,r->{reads[0]++;book.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));return new TaskMemoryUse.Evidence(ref,"title","selected bytes");});
        denied(()->use.readAudienceSource(scope(),ref,0,8,new Audience().span(ref,0,8)));assertEquals(1,reads[0]);assertTrue(book.isSourceForgotten(scope(),ref));
    }
}
