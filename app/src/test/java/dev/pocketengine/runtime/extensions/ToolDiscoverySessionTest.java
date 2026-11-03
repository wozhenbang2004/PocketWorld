package dev.pocketengine.runtime.extensions;

import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.ScopedToolCatalog;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ToolDiscoverySessionTest {
    private final RunControl control=new RunControl();private final AtomicBoolean live=new AtomicBoolean(true);
    public ToolDiscoverySessionTest(){control.bindHostRun("task","run",1);}
    private JSONArray definitions()throws Exception {JSONArray out=new JSONArray();for(String name:Set.of("core_read","rare_write"))out.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","native "+name).put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path")).put("additionalProperties",false))));return out;}
    private Map<String,ScopedToolCatalog.SourcePin> sources(String installation)throws Exception {Map<String,ScopedToolCatalog.SourcePin> out=new HashMap<>();out.put("core_read",new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.NATIVE,"native-host","1",ScopedToolCatalog.fingerprint("core")));out.put("rare_write",new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.PLUGIN,installation,"1",ScopedToolCatalog.fingerprint(installation)));return out;}
    private CapabilitySnapshot scope(){return new CapabilitySnapshot("task","run",1,control,Set.of("core_read","rare_write",ScopedToolCatalog.SEARCH_TOOL),()->{if(!live.get())throw new SecurityException("original native scope revoked");});}
    private ToolDiscoverySession session()throws Exception {ToolDiscoverySession out=new ToolDiscoverySession("task","run",1,control);begin(out,"installation-1");return out;}
    private void begin(ToolDiscoverySession session,String installation)throws Exception{session.beginRequest(scope(),definitions(),sources(installation),Set.of("core_read"),control);}
    private JSONObject search(ToolDiscoverySession session)throws Exception{return session.search(new JSONObject().put("query","rare_write"),control);}
    @Test public void originalHundredToolAndFiveHundredTwelveNameBoundariesRequireFullSchemaFallback()throws Exception {
        java.util.Set<String> granted=new java.util.HashSet<>();for(int i=0;i<100;i++)granted.add("real_tool_"+i);
        CapabilitySnapshot original=new CapabilitySnapshot("task","run",1,control,granted,()->{});assertEquals(100,original.current("task","run",1,control).size());
        assertFalse(original.current("task","run",1,control).contains(ScopedToolCatalog.SEARCH_TOOL));assertFalse(ToolDiscoverySession.shouldDefer(100,100));
        assertTrue(ToolDiscoverySession.shouldDefer(99,511));assertFalse(ToolDiscoverySession.shouldDefer(99,512));assertFalse(ToolDiscoverySession.shouldDefer(101,101));
        assertEquals(granted,original.current("task","run",1,control));assertThrows(IllegalArgumentException.class,()->ToolDiscoverySession.shouldDefer(-1,1));
    }
    @Test public void onlyOriginalCheckpointCallbackPublishesSchemaOnTheNextRequest()throws Exception {
        ToolDiscoverySession session=session();assertEquals(2,session.requestTools(control).length());session.requirePublished("core_read",control);assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));
        JSONObject result=search(session);assertFalse(result.getBoolean("executionAuthorized"));assertFalse(result.getBoolean("scopeExpanded"));assertEquals("task",result.getString("taskId"));assertEquals("run",result.getString("runId"));assertEquals(1,result.getInt("epoch"));assertEquals(1,result.getInt("returnedCount"));
        assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));session.afterTool(ScopedToolCatalog.SEARCH_TOOL,result,control);assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));
        begin(session,"installation-1");assertEquals(3,session.requestTools(control).length());session.requirePublished("rare_write",control);
        assertTrue(scope().current("task","run",1,control).contains("rare_write"));assertFalse(result.has("approved"));assertFalse(result.has("grantId"));
    }
    @Test public void jsonCannotRestoreUncommittedOrAnotherSessionsDiscoveryAndReceiptsAreSingleUse()throws Exception {
        ToolDiscoverySession first=session();JSONObject issued=search(first);ToolDiscoverySession fresh=session();assertThrows(SecurityException.class,()->fresh.afterTool(ScopedToolCatalog.SEARCH_TOOL,new JSONObject(issued.toString()),control));
        first.afterTool(ScopedToolCatalog.SEARCH_TOOL,issued,control);assertThrows(SecurityException.class,()->first.afterTool(ScopedToolCatalog.SEARCH_TOOL,issued,control));
        ToolDiscoverySession uncommitted=session();JSONObject notCommitted=search(uncommitted);begin(uncommitted,"installation-1");assertThrows(SecurityException.class,()->uncommitted.afterTool(ScopedToolCatalog.SEARCH_TOOL,notCommitted,control));assertThrows(SecurityException.class,()->uncommitted.requirePublished("rare_write",control));
    }
    @Test public void installationReplacementWithSameNameAndSchemaWithdrawsPreviouslyDiscoveredDefinition()throws Exception {
        ToolDiscoverySession session=session();JSONObject receipt=search(session);session.afterTool(ScopedToolCatalog.SEARCH_TOOL,receipt,control);begin(session,"installation-1");session.requirePublished("rare_write",control);
        begin(session,"installation-2");assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));JSONObject replacement=search(session);assertNotEquals(receipt.getString("catalogRevision"),replacement.getString("catalogRevision"));assertNotEquals(receipt.getJSONArray("tools").getJSONObject(0).getString("identity"),replacement.getJSONArray("tools").getJSONObject(0).getString("identity"));
    }
    @Test public void revocationPauseAndWrongControlDoNotTurnDiscoveryIntoPermission()throws Exception {
        ToolDiscoverySession session=session();JSONObject receipt=search(session);live.set(false);assertThrows(SecurityException.class,()->session.afterTool(ScopedToolCatalog.SEARCH_TOOL,receipt,control));live.set(true);assertFalse(session.current(control));
        begin(session,"installation-1");assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));RunControl other=new RunControl();other.bindHostRun("task","run",1);assertThrows(SecurityException.class,()->session.search(new JSONObject().put("query","rare_write"),other));
        control.pause();assertFalse(session.current(control));control.resume();assertFalse(session.current(control));
    }
    @Test public void changedOrRedactedDiscoveryReceiptCannotActivateAnUnprovenSchema()throws Exception {
        ToolDiscoverySession session=session();JSONObject receipt=search(session);JSONObject changed=new JSONObject(receipt.toString());changed.getJSONArray("tools").getJSONObject(0).getJSONObject("definition").getJSONObject("function").getJSONObject("parameters").remove("required");
        assertThrows(SecurityException.class,()->session.afterTool(ScopedToolCatalog.SEARCH_TOOL,changed,control));begin(session,"installation-1");assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));
    }
    @Test public void noMatchIsACommittedMetadataReceiptAndDoesNotPublishAnything()throws Exception {
        ToolDiscoverySession session=session();JSONObject result=session.search(new JSONObject().put("query","no-such-name"),control);assertTrue(result.getBoolean("ok"));assertEquals("no_match",result.getString("status"));assertFalse(result.getBoolean("truncated"));session.afterTool(ScopedToolCatalog.SEARCH_TOOL,result,control);begin(session,"installation-1");assertEquals(2,session.requestTools(control).length());
        assertThrows(IllegalArgumentException.class,()->session.search(new JSONObject().put("query","rare_write").put("taskId","foreign"),control));session.close();assertFalse(session.current(control));
    }
}
