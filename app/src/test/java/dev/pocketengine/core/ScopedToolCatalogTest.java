package dev.pocketengine.core;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
        Set<String> names=new HashSet<>();names.add(ScopedToolCatalog.SEARCH_TOOL);for(int i=0;i<definitions.length();i++)names.add(definitions.getJSONObject(i).getJSONObject("function").getString("name"));return new CapabilitySnapshot("task","run",1,control,names,()->{if(!live.get())throw new SecurityException("original source withdrawn");});
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
        pins.put("tool_0",new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.PLUGIN,"installation-2","1",ScopedToolCatalog.fingerprint("new-native-route")));ScopedToolCatalog replaced=ScopedToolCatalog.capture(scope(definitions),"task","run",1,control,definitions,pins,Set.of());
        assertNotEquals(first.revision(),replaced.revision());assertThrows(SecurityException.class,()->replaced.search(new ScopedToolCatalog.SearchRequest("tool_",2,cursor)));assertThrows(SecurityException.class,()->first.search(new ScopedToolCatalog.SearchRequest("other",2,cursor)));assertThrows(SecurityException.class,()->first.search(new ScopedToolCatalog.SearchRequest("tool_",3,cursor)));
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
        ScopedToolCatalog catalog=catalog(definitions,Set.of());String revision=catalog.revision();assertTrue(names(catalog.requestTools(Set.of())).contains("tool_1"));assertEquals("no_match",catalog.search(new ScopedToolCatalog.SearchRequest("tool_1",4,"")).view().getString("status"));
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
        JSONArray definitions=definitions(1);Map<String,ScopedToolCatalog.SourcePin> pins=Map.of("tool_0",ScopedToolCatalog.SourcePin.unknown());ScopedToolCatalog catalog=ScopedToolCatalog.capture(scope(definitions),"task","run",1,control,definitions,pins,Set.of());
        assertTrue(names(catalog.requestTools(Set.of())).contains("tool_0"));assertEquals(0,catalog.summary(Set.of()).getInt("hiddenCount"));assertEquals("no_match",catalog.search(new ScopedToolCatalog.SearchRequest("tool_0",1,"")).view().getString("status"));
        assertThrows(IllegalArgumentException.class,()->new ScopedToolCatalog.SourcePin(ScopedToolCatalog.SourceKind.UNKNOWN,"claimed-installation","1","a".repeat(64)));
    }
}
