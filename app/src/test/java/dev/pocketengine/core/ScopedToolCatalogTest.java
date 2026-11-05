package dev.pocketengine.core;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScopedToolCatalogTest {
    private final RunControl control=new RunControl();
    private final AtomicBoolean live=new AtomicBoolean(true);
    public ScopedToolCatalogTest(){control.bindHostRun("task","run",1);}
    private JSONObject definition(String name)throws Exception {
        JSONObject properties=new JSONObject();for(int i=0;i<20;i++)properties.put("field"+i,new JSONObject().put("type","string").put("description","A current native tool field with an unchanged exact schema "+i));
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","native 功能 literal [ "+name).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray().put("field0")).put("additionalProperties",false)));
    }
    private JSONArray definitions(int count)throws Exception {JSONArray out=new JSONArray();for(int i=0;i<count;i++)out.put(definition("tool_"+i));return out;}
    private Map<String,ScopedToolCatalog.SourcePin> sources(JSONArray definitions)throws Exception {
        Map<String,ScopedToolCatalog.SourcePin> out=new HashMap<>();for(int i=0;i<definitions.length();i++){String name=definitions.getJSONObject(i).getJSONObject("function").getString("name");out.put(name,new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.PLUGIN,"installation-1","1",ScopedToolCatalog.fingerprint("native-route:"+name)));}return out;
    }
    private CapabilitySnapshot scope(JSONArray definitions)throws Exception {
        return scope(definitions,sources(definitions),"a".repeat(64));
    }
    private CapabilitySnapshot scope(JSONArray definitions,Map<String,ScopedToolCatalog.SourcePin> pins,String digest)throws Exception {
        Set<String> names=names(definitions);CapabilitySnapshot result=new CapabilitySnapshot("task","run",1,control,names,()->{if(!live.get())throw new SecurityException("original source withdrawn");});
        result.retainSources(pins);result.admitToolCatalog(names,digest,()->{if(!live.get())throw new SecurityException("original SQL scope withdrawn");});return result;
    }
    private ScopedToolCatalog catalog(JSONArray definitions,Set<String> resident)throws Exception{return ScopedToolCatalog.capture(scope(definitions),"task","run",1,control,definitions,sources(definitions),resident);}
    private Set<String> names(JSONArray tools)throws Exception{Set<String> out=new HashSet<>();for(int i=0;i<tools.length();i++)out.add(tools.getJSONObject(i).getJSONObject("function").getString("name"));return out;}
    @Test public void hundredToolsUseFewerInitialSchemaBytesWhileCoreDefinitionsStayExact()throws Exception {
        JSONArray all=definitions(100);ScopedToolCatalog catalog=catalog(all,Set.of("tool_0","tool_1","tool_2"));JSONArray initial=catalog.requestTools(Set.of());JSONObject summary=catalog.summary(Set.of());
        assertEquals(Set.of("tool_0","tool_1","tool_2",ScopedToolCatalog.SEARCH_TOOL),names(initial));assertEquals(all.getJSONObject(0).toString(),initial.getJSONObject(0).toString());
        int before=all.toString().getBytes(StandardCharsets.UTF_8).length,after=initial.toString().getBytes(StandardCharsets.UTF_8).length+summary.toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue("Initial schemas plus bounded short metadata must actually shrink",after*3<before);assertEquals(97,summary.getInt("hiddenCount"));assertEquals(12,summary.getInt("listedCount"));assertTrue(summary.getBoolean("truncated"));
        System.out.println("CATALOG_BYTES original="+before+" initial_with_summary="+after);
    }
    @Test public void searchPaginationAndNoMatchAreDifferentAndSchemasAreNeverCut()throws Exception {
        ScopedToolCatalog catalog=catalog(definitions(30),Set.of("tool_0"));JSONObject first=catalog.search(new ScopedToolCatalog.SearchRequest("tool_",4,"")).view();
        assertEquals("matched",first.getString("status"));assertEquals(4,first.getInt("returnedCount"));assertTrue(first.getBoolean("truncated"));assertTrue(first.toString().getBytes(StandardCharsets.UTF_8).length<=ScopedToolCatalog.MAX_PAGE_BYTES);
        JSONObject next=catalog.search(new ScopedToolCatalog.SearchRequest("tool_",4,first.getString("nextCursor"))).view();Set<String> seen=new HashSet<>();for(int i=0;i<4;i++)seen.add(first.getJSONArray("tools").getJSONObject(i).getString("toolName"));
        for(int i=0;i<next.getJSONArray("tools").length();i++){JSONObject row=next.getJSONArray("tools").getJSONObject(i);assertFalse(seen.contains(row.getString("toolName")));JSONObject schema=row.getJSONObject("definition").getJSONObject("function").getJSONObject("parameters");assertEquals(20,schema.getJSONObject("properties").length());assertEquals("field0",schema.getJSONArray("required").getString(0));assertEquals(ScopedToolCatalog.fingerprint(schema),row.getString("schemaHash"));}
        JSONObject none=catalog.search(new ScopedToolCatalog.SearchRequest("no-such-tool",4,"")).view();assertEquals("no_match",none.getString("status"));assertEquals(0,none.getInt("matchedCount"));assertFalse(none.getBoolean("truncated"));assertTrue(none.isNull("nextCursor"));
        assertTrue(catalog.search(new ScopedToolCatalog.SearchRequest("功能 literal [",4,"")).view().getInt("matchedCount")>0);
    }
    @Test public void sourceReplacementChangesIdentityAndInvalidatesOldCursorsEvenWithSameSchema()throws Exception {
        JSONArray definitions=definitions(10);Map<String,ScopedToolCatalog.SourcePin> pins=sources(definitions);ScopedToolCatalog first=ScopedToolCatalog.capture(scope(definitions),"task","run",1,control,definitions,pins,Set.of());String cursor=first.search(new ScopedToolCatalog.SearchRequest("tool_",2,"")).view().getString("nextCursor");
        pins.put("tool_0",new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.PLUGIN,"installation-2","1",ScopedToolCatalog.fingerprint("new-native-route")));ScopedToolCatalog replaced=ScopedToolCatalog.capture(scope(definitions,pins,"a".repeat(64)),"task","run",1,control,definitions,pins,Set.of());
        assertNotEquals(first.revision(),replaced.revision());assertThrows(SecurityException.class,()->replaced.search(new ScopedToolCatalog.SearchRequest("tool_",2,cursor)));assertThrows(SecurityException.class,()->first.search(new ScopedToolCatalog.SearchRequest("other",2,cursor)));assertThrows(SecurityException.class,()->first.search(new ScopedToolCatalog.SearchRequest("tool_",3,cursor)));
    }
    @Test public void searchesIdentifyPublishedWorkspaceReadsInsteadOfReportingThemMissing()throws Exception {
        JSONArray all=new JSONArray();for(String name:new String[]{"workspace_projects","workspace_list","workspace_read","workspace_read_artifact","workspace_write"})all.put(definition(name));
        Set<String> resident=Set.of("workspace_projects","workspace_list","workspace_read");ScopedToolCatalog catalog=catalog(all,resident);
        Set<String> initial=names(catalog.requestTools(Set.of()));assertTrue(initial.containsAll(resident));assertFalse(initial.contains("workspace_write"));
        for(String name:new String[]{"workspace_projects","workspace_list"}){
            ScopedToolCatalog.Page page=catalog.search(new ScopedToolCatalog.SearchRequest(name,4,""));JSONObject result=page.view();
            assertEquals("An exact published tool must not be reported as absent", "already_available",result.getString("status"));
            assertEquals(name,result.getJSONArray("alreadyPublishedToolNames").getString(0));assertEquals(1,result.getInt("alreadyPublishedCount"));
            assertEquals(0,result.getInt("returnedCount"));assertEquals(0,result.getJSONArray("tools").length());assertTrue(page.identities().isEmpty());
            assertFalse(result.getBoolean("executionAuthorized"));assertFalse(result.getBoolean("scopeExpanded"));
        }
        JSONObject read=catalog.search(new ScopedToolCatalog.SearchRequest("workspace_read",4,"")).view();
        assertEquals("matched",read.getString("status"));assertEquals("workspace_read",read.getJSONArray("alreadyPublishedToolNames").getString(0));
        assertEquals("workspace_read_artifact",read.getJSONArray("tools").getJSONObject(0).getString("toolName"));assertEquals(1,read.getInt("returnedCount"));
        JSONObject absent=catalog.search(new ScopedToolCatalog.SearchRequest("not_granted",4,"")).view();assertEquals("no_match",absent.getString("status"));assertEquals(0,absent.getInt("alreadyPublishedCount"));
        assertEquals(initial,names(catalog.requestTools(Set.of())));assertFalse(catalog.exposes("workspace_write",Set.of()));
    }
    @Test public void duplicateNamesMissingPinsAndOutOfScopeDefinitionsCannotBecomeDiscovery()throws Exception {
        JSONArray one=definitions(1),two=definitions(2);CapabilitySnapshot limited=scope(one);assertThrows(SecurityException.class,()->ScopedToolCatalog.capture(limited,"task","run",1,control,two,sources(two),Set.of()));
        assertThrows(SecurityException.class,()->ScopedToolCatalog.capture(scope(one),"task","run",1,control,one,Map.of(),Set.of()));JSONArray duplicate=new JSONArray(one.toString()).put(one.getJSONObject(0));assertThrows(SecurityException.class,()->catalog(duplicate,Set.of()));
        JSONArray reserved=new JSONArray().put(definition(ScopedToolCatalog.SEARCH_TOOL));assertThrows(SecurityException.class,()->catalog(reserved,Set.of()));
    }
    @Test public void scopeWithdrawalPermanentlyRetiresOldCatalogAndForeignControlIsRefused()throws Exception {
        JSONArray definitions=definitions(1);ScopedToolCatalog catalog=catalog(definitions,Set.of());live.set(false);assertThrows(SecurityException.class,()->catalog.search(new ScopedToolCatalog.SearchRequest("tool_",1,"")));live.set(true);assertThrows(SecurityException.class,catalog::check);
        RunControl foreign=new RunControl();foreign.bindHostRun("task","run",1);assertThrows(SecurityException.class,()->ScopedToolCatalog.capture(scope(definitions),"task","run",1,foreign,definitions,sources(definitions),Set.of()));
    }
    @Test public void specialSchemasStayResidentAndInputOrReturnedMutationCannotRewriteCatalog()throws Exception {
        JSONArray definitions=definitions(2);definitions.getJSONObject(1).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").put("apiKey",new JSONObject().put("type","string"));
        ScopedToolCatalog catalog=catalog(definitions,Set.of());String revision=catalog.revision();assertTrue(names(catalog.requestTools(Set.of())).contains("tool_1"));JSONObject resident=catalog.search(new ScopedToolCatalog.SearchRequest("tool_1",4,"")).view();assertEquals("already_available",resident.getString("status"));assertEquals(0,resident.getJSONArray("tools").length());assertEquals("tool_1",resident.getJSONArray("alreadyPublishedToolNames").getString(0));
        definitions.getJSONObject(0).getJSONObject("function").put("name","changed");JSONObject page=catalog.search(new ScopedToolCatalog.SearchRequest("tool_0",4,"")).view();page.getJSONArray("tools").getJSONObject(0).getJSONObject("definition").getJSONObject("function").put("name","changed");assertEquals("tool_0",catalog.search(new ScopedToolCatalog.SearchRequest("tool_0",4,"")).view().getJSONArray("tools").getJSONObject(0).getString("toolName"));assertEquals(revision,catalog.revision());
    }
    @Test public void malformedQueriesUnicodeAndCursorDataAreRejectedWithoutCoercion()throws Exception {
        assertThrows(IllegalArgumentException.class,()->ScopedToolCatalog.SearchRequest.fromJson(new JSONObject().put("query","tool").put("scope","owner")));
        assertThrows(IllegalArgumentException.class,()->ScopedToolCatalog.SearchRequest.fromJson(new JSONObject().put("query","tool").put("limit",1.5)));
        assertThrows(IllegalArgumentException.class,()->new ScopedToolCatalog.SearchRequest("bad\uD800",1,""));
        JSONArray invalid=definitions(1);invalid.getJSONObject(0).getJSONObject("function").put("description","bad\uD800");assertThrows(IllegalArgumentException.class,()->catalog(invalid,Set.of()));
        ScopedToolCatalog catalog=catalog(definitions(2),Set.of());assertThrows(SecurityException.class,()->catalog.search(new ScopedToolCatalog.SearchRequest("tool_",1,"not-a-native-cursor")));
    }
    @Test public void unobservedSourceIdentityKeepsOriginalSchemaResidentInsteadOfInventingAnInstallation()throws Exception {
        JSONArray definitions=definitions(1);Map<String,ScopedToolCatalog.SourcePin> pins=Map.of("tool_0",ScopedToolCatalog.SourcePin.unknown());ScopedToolCatalog catalog=ScopedToolCatalog.capture(scope(definitions,pins,"a".repeat(64)),"task","run",1,control,definitions,pins,Set.of());
        assertTrue(names(catalog.requestTools(Set.of())).contains("tool_0"));assertEquals(0,catalog.summary(Set.of()).getInt("hiddenCount"));ScopedToolCatalog.Page resident=catalog.search(new ScopedToolCatalog.SearchRequest("tool_0",1,""));assertEquals("already_available",resident.view().getString("status"));assertEquals(0,resident.view().getJSONArray("tools").length());assertTrue(resident.identities().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.UNKNOWN,"claimed-installation","1","a".repeat(64)));
    }
    @Test public void nativeCatalogAdmissionIsRequiredOnceAndNeverExpandsTheCandidateSet()throws Exception {
        JSONArray definitions=definitions(100);Set<String> candidates=names(definitions);candidates.add("semantic.alias");
        CapabilitySnapshot snapshot=new CapabilitySnapshot("task","run",1,control,candidates,()->{});
        assertThrows(SecurityException.class,()->snapshot.catalogTools("task","run",1,control));
        assertThrows(SecurityException.class,snapshot::catalogScopeDigest);
        assertThrows(SecurityException.class,()->snapshot.admitToolCatalog(Set.of("outside"),"a".repeat(64),()->{}));
        assertThrows(SecurityException.class,()->snapshot.admitToolCatalog(Set.of("tool_0"),"not-a-digest",()->{}));
        assertThrows(SecurityException.class,()->snapshot.admitToolCatalog(candidates,"a".repeat(64),()->{}));
        Set<String> admitted=names(definitions);snapshot.admitToolCatalog(admitted,"a".repeat(64),()->{});admitted.clear();
        assertEquals(100,snapshot.catalogTools("task","run",1,control).size());assertEquals("a".repeat(64),snapshot.catalogScopeDigest());
        assertEquals(candidates,snapshot.current("task","run",1,control));assertFalse(snapshot.current("task","run",1,control).contains(ScopedToolCatalog.SEARCH_TOOL));
        assertThrows(UnsupportedOperationException.class,()->snapshot.catalogTools("task","run",1,control).add("outside"));
        assertThrows(SecurityException.class,()->snapshot.admitToolCatalog(Set.of("tool_0"),"b".repeat(64),()->{}));
        RunControl foreign=new RunControl();foreign.bindHostRun("task","run",1);assertThrows(SecurityException.class,()->snapshot.catalogTools("task","run",1,foreign));
        CapabilitySnapshot reserved=new CapabilitySnapshot("task","run",1,control,Set.of(ScopedToolCatalog.SEARCH_TOOL),()->{});
        assertThrows(SecurityException.class,()->reserved.admitToolCatalog(Set.of(ScopedToolCatalog.SEARCH_TOOL),"a".repeat(64),()->{}));
    }
    @Test public void nativeSourcesAreCopiedSealedAndCannotBeUpgradedByCaptureArguments()throws Exception {
        JSONArray definitions=definitions(2);Map<String,ScopedToolCatalog.SourcePin> actual=sources(definitions),supplied=new HashMap<>();supplied.put("tool_0",actual.get("tool_0"));
        CapabilitySnapshot snapshot=new CapabilitySnapshot("task","run",1,control,names(definitions),()->{});snapshot.retainSources(supplied);supplied.clear();
        assertThrows(SecurityException.class,()->snapshot.retainSources(actual)); // Duplicate registration is atomic: tool_1 stays unknown.
        snapshot.admitToolCatalog(names(definitions),"a".repeat(64),()->{});
        Map<String,ScopedToolCatalog.SourcePin> frozen=snapshot.sourcePinsFor("task","run",1,control,names(definitions));
        assertEquals(actual.get("tool_0"),frozen.get("tool_0"));assertEquals(ScopedToolCatalog.SourcePin.unknown(),frozen.get("tool_1"));
        assertThrows(UnsupportedOperationException.class,()->frozen.put("tool_1",actual.get("tool_1")));
        assertEquals(frozen,snapshot.sourcePinsFor("task","run",1,control,names(definitions)));
        assertThrows(SecurityException.class,()->snapshot.retainSources(Map.of("tool_1",actual.get("tool_1"))));
        assertThrows(SecurityException.class,()->ScopedToolCatalog.capture(snapshot,"task","run",1,control,definitions,actual,Set.of()));
        ScopedToolCatalog catalog=ScopedToolCatalog.capture(snapshot,"task","run",1,control,definitions,frozen,Set.of());assertTrue(names(catalog.requestTools(Set.of())).contains("tool_1"));
        assertThrows(SecurityException.class,()->snapshot.sourcePinsFor("task","run",1,control,Set.of("outside")));
    }
    @Test public void sourceAndSqlChecksNeverRunUnderTheSnapshotMonitor()throws Exception {
        AtomicInteger sourceChecks=new AtomicInteger(),sqlChecks=new AtomicInteger();CapabilitySnapshot[] holder=new CapabilitySnapshot[1];
        holder[0]=new CapabilitySnapshot("task","run",1,control,Set.of("tool_0"),()->{assertFalse(Thread.holdsLock(holder[0]));sourceChecks.incrementAndGet();});
        CapabilitySnapshot snapshot=holder[0];snapshot.retainRouteCheck(()->assertFalse(Thread.holdsLock(snapshot)));
        snapshot.admitToolCatalog(Set.of("tool_0"),"a".repeat(64),()->{assertFalse(Thread.holdsLock(snapshot));sqlChecks.incrementAndGet();});
        assertEquals(0,sourceChecks.get());assertEquals(0,sqlChecks.get()); // Registration is not an external callback.
        synchronized(snapshot){assertThrows(SecurityException.class,snapshot::check);assertThrows(SecurityException.class,()->snapshot.sourcePinsFor("task","run",1,control,Set.of("tool_0")));}
        assertEquals(0,sourceChecks.get());assertEquals(0,sqlChecks.get());
        snapshot.retainSources(Map.of("tool_0",sources(definitions(1)).get("tool_0")));
        int before=sourceChecks.get();Map<String,ScopedToolCatalog.SourcePin> frozen=snapshot.sourcePinsFor("task","run",1,control,Set.of("tool_0"));
        assertEquals(1,frozen.size());assertEquals(before+2,sourceChecks.get());assertTrue(sqlChecks.get()>=2);
        snapshot.catalogTools("task","run",1,control);snapshot.catalogScopeDigest();snapshot.invalidate();assertThrows(SecurityException.class,snapshot::check);
    }
    @Test public void sourceReadRechecksCurrentnessAfterItSealsItsFrozenCopy()throws Exception {
        AtomicInteger checks=new AtomicInteger();CapabilitySnapshot snapshot=new CapabilitySnapshot("task","run",1,control,Set.of("tool_0"),()->{if(checks.incrementAndGet()==2)throw new SecurityException("revoked after frozen copy");});
        assertThrows(SecurityException.class,()->snapshot.sourcePinsFor("task","run",1,control,Set.of("tool_0")));
        assertEquals(2,checks.get());assertThrows(SecurityException.class,()->snapshot.retainSources(Map.of("tool_0",ScopedToolCatalog.SourcePin.unknown())));
    }
    @Test public void allHundredPublishedSchemasRemoveSearchInsteadOfCreatingA101stTool()throws Exception {
        JSONArray definitions=definitions(100);CapabilitySnapshot snapshot=scope(definitions);ScopedToolCatalog catalog=ScopedToolCatalog.capture(snapshot,"task","run",1,control,definitions,sources(definitions),Set.of());
        Set<String> activated=new HashSet<>();String cursor="";
        do{ScopedToolCatalog.Page page=catalog.search(new ScopedToolCatalog.SearchRequest("tool_",12,cursor));activated.addAll(page.identities());JSONObject view=page.view();cursor=view.isNull("nextCursor")?"":view.getString("nextCursor");}while(!cursor.isEmpty());
        assertEquals(100,activated.size());JSONArray all=catalog.requestTools(activated);assertEquals(100,all.length());assertFalse(names(all).contains(ScopedToolCatalog.SEARCH_TOOL));assertFalse(catalog.exposes(ScopedToolCatalog.SEARCH_TOOL,activated));
        activated.remove(activated.iterator().next());JSONArray oneHidden=catalog.requestTools(activated);assertEquals(100,oneHidden.length());assertTrue(names(oneHidden).contains(ScopedToolCatalog.SEARCH_TOOL));
        assertEquals(100,snapshot.catalogTools("task","run",1,control).size());assertFalse(snapshot.current("task","run",1,control).contains(ScopedToolCatalog.SEARCH_TOOL));
    }
    @Test public void sanitizerProtectedAndOversizeSchemasRemainCompleteOnOriginalTransport()throws Exception {
        JSONArray definitions=definitions(7);String[] protectedKeys={"token","secret","imageDataUrl","AUTHORIZATION"};
        for(int i=0;i<protectedKeys.length;i++)definitions.getJSONObject(i).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").put(protectedKeys[i],new JSONObject().put("type","string"));
        JSONArray largeEnum=new JSONArray();for(int i=0;i<201;i++)largeEnum.put("choice_"+i);
        definitions.getJSONObject(4).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").put("choice",new JSONObject().put("enum",largeEnum));
        definitions.getJSONObject(5).getJSONObject("function").put("description","x".repeat(12001));
        ScopedToolCatalog catalog=catalog(definitions,Set.of());JSONArray initial=catalog.requestTools(Set.of());assertEquals(7,initial.length());
        for(int i=0;i<6;i++){assertEquals(definitions.getJSONObject(i).toString(),initial.getJSONObject(i).toString());ScopedToolCatalog.Page resident=catalog.search(new ScopedToolCatalog.SearchRequest("tool_"+i,12,""));assertEquals("already_available",resident.view().getString("status"));assertEquals(0,resident.view().getJSONArray("tools").length());assertTrue(resident.identities().isEmpty());}
        assertFalse(names(initial).contains("tool_6"));assertTrue(names(initial).contains(ScopedToolCatalog.SEARCH_TOOL));
        JSONArray boundary=definitions(1);JSONArray exactEnum=new JSONArray();for(int i=0;i<200;i++)exactEnum.put("choice_"+i);
        boundary.getJSONObject(0).getJSONObject("function").put("description","x".repeat(12000)).getJSONObject("parameters").getJSONObject("properties").put("choice",new JSONObject().put("enum",exactEnum));
        ScopedToolCatalog exact=catalog(boundary,Set.of());assertEquals(Set.of(ScopedToolCatalog.SEARCH_TOOL),names(exact.requestTools(Set.of())));assertEquals(1,exact.search(new ScopedToolCatalog.SearchRequest("tool_0",1,"")).view().getInt("returnedCount"));
    }
    @Test public void nativeScopeDigestChangesCatalogRevisionAndRejectsOldCursors()throws Exception {
        JSONArray definitions=definitions(3);Map<String,ScopedToolCatalog.SourcePin> pins=sources(definitions);
        ScopedToolCatalog before=ScopedToolCatalog.capture(scope(definitions,pins,"a".repeat(64)),"task","run",1,control,definitions,pins,Set.of());
        String cursor=before.search(new ScopedToolCatalog.SearchRequest("tool_",1,"")).view().getString("nextCursor");
        ScopedToolCatalog after=ScopedToolCatalog.capture(scope(definitions,pins,"b".repeat(64)),"task","run",1,control,definitions,pins,Set.of());
        assertNotEquals(before.revision(),after.revision());assertThrows(SecurityException.class,()->after.search(new ScopedToolCatalog.SearchRequest("tool_",1,cursor)));
    }
}
