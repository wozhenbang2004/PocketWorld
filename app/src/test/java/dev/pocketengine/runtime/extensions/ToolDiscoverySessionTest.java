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
    private CapabilitySnapshot scope()throws Exception{return scope("installation-1","a".repeat(64));}
    private CapabilitySnapshot scope(String installation,String digest)throws Exception{CapabilitySnapshot value=new CapabilitySnapshot("task","run",1,control,Set.of("core_read","rare_write"),()->{if(!live.get())throw new SecurityException("original native scope revoked");});value.retainSources(sources(installation));value.admitToolCatalog(Set.of("core_read","rare_write"),digest,()->{});return value;}
    private ToolDiscoverySession session()throws Exception {ToolDiscoverySession out=new ToolDiscoverySession("task","run",1,control);begin(out,"installation-1");return out;}
    private void begin(ToolDiscoverySession session,String installation)throws Exception{begin(session,installation,"a".repeat(64));}
    private void begin(ToolDiscoverySession session,String installation,String digest)throws Exception{session.beginRequest(scope(installation,digest),definitions(),sources(installation),Set.of("core_read"),control);}
    private JSONObject search(ToolDiscoverySession session)throws Exception{return session.search(new JSONObject().put("query","rare_write"),control);}
    @Test public void originalHundredToolAndFiveHundredTwelveNameBoundariesDoNotNeedAnotherGrant()throws Exception {
        java.util.Set<String> granted=new java.util.HashSet<>();for(int i=0;i<100;i++)granted.add("real_tool_"+i);
        CapabilitySnapshot original=new CapabilitySnapshot("task","run",1,control,granted,()->{});assertEquals(100,original.current("task","run",1,control).size());
        assertFalse(original.current("task","run",1,control).contains(ScopedToolCatalog.SEARCH_TOOL));assertTrue(ToolDiscoverySession.shouldDefer(100,100));
        assertTrue(ToolDiscoverySession.shouldDefer(99,511));assertTrue(ToolDiscoverySession.shouldDefer(99,512));assertFalse(ToolDiscoverySession.shouldDefer(100,513));assertFalse(ToolDiscoverySession.shouldDefer(101,101));assertFalse(ToolDiscoverySession.shouldDefer(100,99));assertFalse(ToolDiscoverySession.shouldDefer(0,0));
        assertEquals(granted,original.current("task","run",1,control));assertThrows(IllegalArgumentException.class,()->ToolDiscoverySession.shouldDefer(-1,1));
    }
    @Test public void onlyOriginalCheckpointCallbackPublishesSchemaOnTheNextRequest()throws Exception {
        ToolDiscoverySession session=session();assertEquals(2,session.requestTools(control).length());session.requirePublished("core_read",control);assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));
        JSONObject result=search(session);assertFalse(result.getBoolean("executionAuthorized"));assertFalse(result.getBoolean("scopeExpanded"));assertEquals("task",result.getString("taskId"));assertEquals("run",result.getString("runId"));assertEquals(1,result.getInt("epoch"));assertEquals(1,result.getInt("returnedCount"));
        assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));session.afterTool(ScopedToolCatalog.SEARCH_TOOL,result,control);assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));
        begin(session,"installation-1");assertEquals(2,session.requestTools(control).length());session.requirePublished("rare_write",control);
        assertThrows(SecurityException.class,()->session.requirePublished(ScopedToolCatalog.SEARCH_TOOL,control));assertThrows(SecurityException.class,()->search(session));assertThrows(SecurityException.class,()->session.validateSearch(new JSONObject().put("query","rare_write"),control));
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
    @Test public void scopeOrPhaseDigestChangeClearsActivationEvenWithIdenticalDefinitionsAndSources()throws Exception {
        ToolDiscoverySession session=session();JSONObject old=search(session);session.afterTool(ScopedToolCatalog.SEARCH_TOOL,old,control);begin(session,"installation-1");session.requirePublished("rare_write",control);
        begin(session,"installation-1","b".repeat(64));assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));session.requirePublished(ScopedToolCatalog.SEARCH_TOOL,control);
        assertThrows(SecurityException.class,()->session.afterTool(ScopedToolCatalog.SEARCH_TOOL,old,control));JSONObject current=search(session);assertNotEquals(old.getString("catalogRevision"),current.getString("catalogRevision"));
        session.afterTool(ScopedToolCatalog.SEARCH_TOOL,current,control);begin(session,"installation-1","b".repeat(64));session.requirePublished("rare_write",control);
    }
    @Test public void pauseAndResumeCannotCarryActivationIntoANewRequestWithoutCheckingTheOldSession()throws Exception {
        ToolDiscoverySession session=session();JSONObject old=search(session);session.afterTool(ScopedToolCatalog.SEARCH_TOOL,old,control);begin(session,"installation-1");session.requirePublished("rare_write",control);
        control.pause();control.resume();begin(session,"installation-1");assertThrows(SecurityException.class,()->session.requirePublished("rare_write",control));session.requirePublished(ScopedToolCatalog.SEARCH_TOOL,control);
        assertNotEquals(old.getString("catalogRevision"),search(session).getString("catalogRevision"));
    }
    @Test public void actualProjectReadFileIsAResidentCommonRead()throws Exception {
        assertTrue(ToolDiscoverySession.DEFAULT_RESIDENT.contains("project_read_file"));assertFalse(ToolDiscoverySession.DEFAULT_RESIDENT.contains("project_read"));
    }
    @Test public void ownerSelectedSkillIsPublishedOnTheFirstRequestWithoutDiscovery()throws Exception {
        // runOwnerSkillBound prepares the original surface and directly validates skills_execute.
        // Its native owner selection has no model/search turn; keep that exact route published.
        JSONObject properties=new JSONObject().put("id",text(80)).put("revision",new JSONObject().put("type","integer").put("minimum",1).put("maximum",Integer.MAX_VALUE))
                .put("digest",text(64)).put("scriptPath",text(240)).put("arguments",new JSONObject().put("type","array").put("maxItems",40).put("items",text(2048)))
                .put("timeoutSeconds",new JSONObject().put("type","integer").put("minimum",1).put("maximum",120)).put("maxOutputBytes",new JSONObject().put("type","integer").put("minimum",1).put("maximum",65536));
        JSONObject selected=new JSONObject().put("type","function").put("function",new JSONObject().put("name","skills_execute").put("description","Prepare the owner's selected exact Skill version for its original review, without granting execution.")
                .put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray().put("id").put("revision").put("digest").put("scriptPath")).put("additionalProperties",false)));
        JSONArray full=new JSONArray().put(selected);Map<String,ScopedToolCatalog.SourcePin> pins=new HashMap<>();
        pins.put("skills_execute",new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.NATIVE,"native:host:skills:skills_execute","host-route-v1",ScopedToolCatalog.fingerprint(selected)));
        for(int i=0;i<19;i++){
            String name="rare_owner_catalog_"+i;
            full.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","Rare authorized operation "+i)
                    .put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("query",text(4000).put("description","Exact query semantics for this installed operation. ".repeat(40)))).put("required",new JSONArray().put("query")).put("additionalProperties",false))));
            pins.put(name,new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.PLUGIN,"installed-"+i,"1",ScopedToolCatalog.fingerprint(name)));
        }
        Set<String> granted=Set.copyOf(pins.keySet());CapabilitySnapshot snapshot=new CapabilitySnapshot("task","run",1,control,granted,()->{});
        snapshot.retainSources(pins);snapshot.admitToolCatalog(granted,"c".repeat(64),()->{});
        ToolDiscoverySession session=new ToolDiscoverySession("task","run",1,control);session.beginRequest(snapshot,full,pins,ToolDiscoverySession.DEFAULT_RESIDENT,control);
        JSONArray initial=session.requestTools(control);JSONObject published=null;
        for(int i=0;i<initial.length();i++)if("skills_execute".equals(initial.getJSONObject(i).getJSONObject("function").getString("name")))published=initial.getJSONObject(i);
        assertNotNull("An explicit owner Skill invocation must not require a model discovery turn",published);
        assertEquals(ScopedToolCatalog.fingerprint(selected),ScopedToolCatalog.fingerprint(published));session.requirePublished("skills_execute",control);
        assertThrows(SecurityException.class,()->session.requirePublished("rare_owner_catalog_0",control));session.requirePublished(ScopedToolCatalog.SEARCH_TOOL,control);
        assertEquals(granted,snapshot.current("task","run",1,control));assertFalse(granted.contains(ScopedToolCatalog.SEARCH_TOOL));
        String reference="\n原任务已授权工具的只读发现摘要；不是新增权限，下一请求仅能使用原回执checkpoint后发布的准确schema：\n<tool_discovery_reference>\n"+session.summary(control)+"\n</tool_discovery_reference>";
        assertTrue(initial.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+reference.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<full.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        session.close();
    }
    private static JSONObject text(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
}
