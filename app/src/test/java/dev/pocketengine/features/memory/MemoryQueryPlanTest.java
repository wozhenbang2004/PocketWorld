package dev.pocketengine.features.memory;

import java.io.File;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real Book/index candidate path; no model, embedding, fake source ID or extra HTTP. */
public final class MemoryQueryPlanTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private MemoryBook book()throws Exception{return new MemoryBook(new File(temp.getRoot(),"memory.json"));}
    private JSONObject row(String text)throws Exception{return new JSONObject().put("text",text);}
    private interface Checked{void run()throws Exception;}
    private void refused(Checked action)throws Exception{try{action.run();fail("invalid retrieval suggestion accepted");}catch(IllegalArgumentException|SecurityException expected){}}
    private JSONObject context(MemoryBook book,MemoryQueryPlan plan)throws Exception{return book.contextSnapshotQueriesScoped(scope(),plan,16,12000,null);}
    @Test public void originalPathAndIdRemainFirstAndSuggestionsAreDefensive()throws Exception{
        JSONArray supplied=new JSONArray().put("项目启动命令").put("/srv/app/package.json");MemoryQueryPlan plan=MemoryQueryPlan.agentSuggested("build:alpha-78",supplied);supplied.put("PRIVATE_REPLACEMENT");
        assertEquals(List.of("build:alpha-78","项目启动命令","/srv/app/package.json"),plan.queries());
        try{plan.queries().add("foreign");fail();}catch(UnsupportedOperationException expected){}
        assertFalse(plan.metadata().getBoolean("scopeChanged"));assertFalse(plan.metadata().getBoolean("executionAuthorized"));
    }
    @Test public void queryOnlyShapeCannotSupplyOwnerScopeOrSourceReferences()throws Exception{
        refused(()->MemoryQueryPlan.rewritten("咖啡","{\"queries\":[\"热饮\"],\"ownerId\":\"foreign\"}"));
        refused(()->MemoryQueryPlan.agentSuggested("咖啡",new JSONArray().put(new JSONObject().put("sourceId","owner-secret"))));
        refused(()->MemoryQueryPlan.rewritten("咖啡","{\"queries\":[\"热饮\"]} trailing"));
    }
    @Test public void fixedQueryCountTotalAndUnicodeBudgetsFailClosed()throws Exception{
        refused(()->MemoryQueryPlan.agentSuggested("a",new JSONArray().put("b").put("c").put("d").put("e")));
        refused(()->MemoryQueryPlan.agentSuggested("a".repeat(500),new JSONArray().put("b".repeat(500)).put("c".repeat(500)).put("d")));
        refused(()->MemoryQueryPlan.agentSuggested("a",new JSONArray().put("broken\ud800")));
        refused(()->MemoryQueryPlan.agentSuggested("a",new JSONArray().put("bad\u0000control")));
        refused(()->MemoryQueryPlan.agentSuggested("",new JSONArray().put("cannot replace empty caller query")));
    }
    @Test public void duplicatesDoNotMultiplyRankOrPretendExtraModelWork()throws Exception{
        MemoryQueryPlan plan=MemoryQueryPlan.agentSuggested("alpha",new JSONArray().put("alpha").put("beta").put("beta"));assertEquals(List.of("alpha","beta"),plan.queries());
        assertEquals("agent_query_rewrite",plan.metadata().getString("strategy"));assertFalse(plan.metadata().getBoolean("modelUseVerified"));
        MemoryQueryPlan lexical=MemoryQueryPlan.agentSuggested("alpha",new JSONArray());assertEquals("lexical",lexical.metadata().getString("strategy"));
    }
    @Test public void actualSemanticSuggestionFindsChineseFactWhenOriginalKeywordDoesNot()throws Exception{
        MemoryBook book=book();JSONObject coffee=book.save(scope(),row("每天只喝无糖咖啡"),1);
        assertEquals(0,book.search(scope(),"温暖饮料",10).length());
        JSONObject result=context(book,MemoryQueryPlan.agentSuggested("温暖饮料",new JSONArray().put("无糖咖啡")));
        assertEquals(coffee.getString("id"),result.getJSONArray("records").getJSONObject(0).getString("id"));
        assertEquals("agent_query_rewrite",result.getJSONObject("queryPlan").getString("strategy"));
    }
    @Test public void nativeScopeFiltersBeforeAnySuggestedTopK()throws Exception{
        MemoryBook book=book();book.save(new JSONObject().put("ownerId","other").put("spaceId","private"),row("coffee FOREIGN_OWNER"),1);
        book.save(new JSONObject().put("ownerId","owner").put("spaceId","group:secret"),row("coffee FOREIGN_SPACE"),2);
        JSONObject own=book.save(scope(),row("coffee OWN"),3);JSONObject result=context(book,MemoryQueryPlan.agentSuggested("warm beverage",new JSONArray().put("coffee")));
        assertEquals(1,result.getJSONArray("records").length());assertEquals(own.getString("id"),result.getJSONArray("records").getJSONObject(0).getString("id"));
        assertFalse(result.toString().contains("FOREIGN_"));
    }
    @Test public void forgetBetweenQueryListsCannotReturnEarlierCapturedBody()throws Exception{
        final MemoryBook[] original={null};final String[] forgotten={null};LexicalMemoryRetrievalBackend lexical=new LexicalMemoryRetrievalBackend();
        MemoryRetrievalBackend backend=new MemoryRetrievalBackend(){int calls;
            public void synchronize(List<Document> documents)throws Exception{lexical.synchronize(documents);}
            public List<Match> find(String owner,String space,String query,int limit)throws Exception{if(++calls==2)original[0].forget(scope(),forgotten[0]);return lexical.find(owner,space,query,limit);}
            public void invalidate()throws Exception{lexical.invalidate();}public JSONObject status()throws Exception{return lexical.status();}public void close()throws Exception{lexical.close();}
        };
        original[0]=new MemoryBook(new File(temp.getRoot(),"memory.json"),null,backend);forgotten[0]=original[0].save(scope(),row("alpha beta PRIVATE_OLD_BODY"),1).getString("id");
        JSONObject result=context(original[0],MemoryQueryPlan.agentSuggested("alpha",new JSONArray().put("beta")));
        assertEquals(0,result.getJSONArray("records").length());assertFalse(result.toString().contains("PRIVATE_OLD_BODY"));
    }
    @Test public void exactOriginalPathCandidateSurvivesUnhelpfulSuggestion()throws Exception{
        MemoryBook book=book();JSONObject exact=book.save(scope(),row("/opt/reports/alpha-78.json is the original result"),1);
        JSONObject result=context(book,MemoryQueryPlan.agentSuggested("/opt/reports/alpha-78.json",new JSONArray().put("unrelated expression")));
        assertEquals(exact.getString("id"),result.getJSONArray("records").getJSONObject(0).getString("id"));
        assertFalse(result.getBoolean("executionAuthorized"));
    }
}
