package dev.pocketengine.browser;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.automation.PlanWorkspacePort;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** The ordinary 14-tool browser surface stays outside the reviewed nine-tool workspace port. */
public final class BrowserWorkspaceBoundaryTest {
    private static final Set<String> WORKSPACE=Set.of("workspace_list","workspace_read","workspace_history","workspace_diff","workspace_write","workspace_artifacts","workspace_read_artifact","workspace_diff_artifact","workspace_create_artifact");
    private static final Set<String> BROWSER=Set.of("browser_task_open","browser_task_select","browser_task_observe","browser_task_request_user","browser_task_navigate","browser_task_click","browser_task_type","browser_task_scroll","browser_task_wait","browser_task_download","browser_task_download_status","browser_task_retain_source","browser_task_close","browser_task_finish");
    static Set<String> names(JSONArray definitions)throws Exception {Set<String> names=new HashSet<>();for(int i=0;i<definitions.length();i++)assertTrue(names.add(definitions.getJSONObject(i).getJSONObject("function").getString("name")));return names;}
    @Test public void ordinaryBrowserHasExactlyFourteenToolsIncludingNativeOwnerHandoff()throws Exception {try(BrowserTaskSpaceTest.Harness h=new BrowserTaskSpaceTest.Harness(true)){
        assertEquals(BROWSER,names(h.port.tools()));JSONArray declared=BrowserTaskCapabilityPort.taskToolNames(true);Set<String> granted=new HashSet<>();for(int i=0;i<declared.length();i++)granted.add(declared.getString(i));Set<String> declaredWithPublic=new HashSet<>(BROWSER);declaredWithPublic.add(PublicHttpReader.TOOL);assertEquals(declaredWithPublic,granted);assertEquals(15,declared.length());assertFalse("fixture SourcePort does not claim public HTTP retention",h.port.supports(PublicHttpReader.TOOL));assertTrue(h.port.supports("browser_task_request_user"));
    }}
    @Test public void actualWorkspaceWrapperKeepsItsExactNineAndRefusesEveryOrdinaryBrowserRoute()throws Exception {try(BrowserTaskSpaceTest.Harness h=new BrowserTaskSpaceTest.Harness(true)){
        AtomicInteger dispatches=new AtomicInteger();CapabilityPort workspace=new CapabilityPort(){public JSONArray tools()throws Exception {JSONArray result=new JSONArray();for(String name:WORKSPACE)result.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name)));return result;}public boolean supports(String name){return WORKSPACE.contains(name);}public boolean sideEffecting(String name){return true;}public JSONObject execute(String name,JSONObject args,RunControl control){dispatches.incrementAndGet();return new JSONObject();}};
        BrowserTaskCapabilityPort ordinary=new BrowserTaskCapabilityPort(h.space,h.authority,()->{},(b,c,id,n,args,pin)->new JSONObject().put("approved",true).put("approvalId",UUID.randomUUID().toString()),h.ui,workspace);assertEquals(23,ordinary.tools().length());String project=UUID.randomUUID().toString();PlanWorkspacePort restricted=new PlanWorkspacePort(ordinary,new JSONObject().put("kind","workspace_artifact").put("projectId",project).put("path","result.txt").put("requiredHeading","研究结果").put("minBytes",1),id->{throw new AssertionError("No artifact access is needed to reject a browser route");});
        assertEquals(WORKSPACE,names(restricted.tools()));assertEquals(WORKSPACE,names(restricted.prepareTurn("same reviewed workspace",1,0,h.control).getJSONArray("tools")));Set<String> allOrdinary=new HashSet<>(BROWSER);allOrdinary.add(PublicHttpReader.TOOL);for(String name:allOrdinary){assertFalse(restricted.supports(name));assertThrows(SecurityException.class,()->restricted.validate(name,new JSONObject()));assertThrows(SecurityException.class,()->restricted.execute(name,new JSONObject(),h.control));}assertEquals(0,dispatches.get());assertEquals(1,h.driver.pages.size());assertFalse(h.control.isPaused());
    }}
}
