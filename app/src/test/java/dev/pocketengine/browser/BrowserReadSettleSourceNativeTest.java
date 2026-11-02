package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Original prepared operation, SourcePort, SQLite and reopen; DOM samples explicitly are interface fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserReadSettleSourceNativeTest {
    @Test public void finalDynamicEvidenceSurvivesOriginalSourcePortAndSQLiteReopenWithoutTrustUpgrade()throws Exception {
        try(BrowserTaskPersistenceTest.Fixture f=new BrowserTaskPersistenceTest.Fixture()){
            JSONObject source=f.source(),raw=raw("text");JSONObject d=j("scope","selected_region_and_nearest_container","busy",false,"contentItems",1,"observerActive",true,"mutationVersion",3,"pagination",new JSONArray().put(j("kind","next","label","Next","href","https://www.youtube.com/watch?page=2","disabled",false,"current",false)),"paginationPartial",false,"nodesVisited",8,"networkIdleProven",false,"trust","untrusted_rendered_dom");
            raw.getJSONObject("read").put("dynamic",d);JSONObject projected=BrowserSemanticRead.result(raw);BrowserReadSettle.Tracker tracker=new BrowserReadSettle.Tracker(j());tracker.sample(projected,0);assertTrue(tracker.sample(projected,600));projected.getJSONObject("read").put("settle",tracker.evidence(true,600));
            source.put("kind",BrowserSemanticRead.KIND).put("acquisitionMethod","rendered_semantic_dom").put("documentId",raw.getString("documentId")).put("frameCoverage",frame()).put("excerpt",raw.getString("text")).put("semantic",BrowserSemanticRead.sourceFacts(projected.getJSONObject("read")));
            String operation=f.journal.beginOperation("browser_task_retain_source",f.arguments(source));JSONObject receipt=f.sources.retain(f.binding,f.control,operation,source.getString("sourceId"),source);f.journal.finishOperation(operation,receipt);assertEquals("retained",receipt.getString("status"));
            TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{JSONObject saved=BrowserTaskSourcePort.sourcesForOwner(reopened,f.binding.taskId).getJSONObject(0);assertEquals(f.binding.taskId,saved.getString("taskId"));assertEquals(raw.getString("documentId"),saved.getString("documentId"));assertEquals(d.toString(),saved.getJSONObject("semantic").getJSONObject("dynamic").toString());assertTrue(saved.getJSONObject("semantic").getJSONObject("settle").getBoolean("conditionMet"));assertFalse(saved.getJSONObject("semantic").getJSONObject("settle").getBoolean("networkIdleProven"));assertFalse(saved.getBoolean("verified"));assertTrue(saved.getBoolean("untrustedPageContent"));assertEquals(1,BrowserTaskSourcePort.sourcesForOwner(reopened,f.binding.taskId).length());}finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
        }
    }
}
