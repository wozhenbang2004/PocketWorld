package dev.pocketengine.features.capabilities;

import dev.pocketengine.core.*;
import dev.pocketengine.features.automation.PlanWorkspacePort;
import dev.pocketengine.features.devices.DeviceCapabilityMux;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure boundary tests. The in-memory journal fixture does not stand in for TaskStore/Android tests. */
public final class PlanningCapabilityPortTest {
    private static final String TASK="11111111-1111-1111-1111-111111111111",RUN="22222222-2222-2222-2222-222222222222";
    private static RunControl control(){RunControl result=new RunControl();result.bindHostRun(TASK,RUN,1);return result;}
    private static JSONObject args()throws Exception{return new JSONObject().put("path","notes.txt");}
    private static JSONArray names(JSONArray tools)throws Exception{JSONArray names=new JSONArray();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));return names;}
    private static JSONObject definition(String name)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","read only (untrusted description)").put("annotations",new JSONObject().put("readOnlyHint",true)).put("parameters",new JSONObject().put("type","object")));}
    private static String hash(JSONObject args)throws Exception{JSONObject clean=new JSONObject(args.toString());clean.remove("_hostOperationId");byte[] bytes=MessageDigest.getInstance("SHA-256").digest(clean.toString().getBytes(StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(bytes);}

    private static final class Planning extends ExecutionPlanning {
        String phase="planning";long revision=1,planRevision;ReadClaim active;int begins,finishes,dispatchChecks,closes;
        Planning(RunControl original){super(original);}
        public Snapshot capture()throws Exception{original().check();if(original().isPaused())throw new SecurityException("paused");return snapshot(revision,phase,planRevision,"","");}
        public void requireCurrent(Snapshot expected)throws Exception{requireOwned(expected);original().check();if(original().isPaused()||expected.scopeRevision!=revision||!expected.phase.equals(phase)||expected.planRevision!=planRevision||expected.pauseRevision!=original().pauseRevision())throw new SecurityException("original planning revision changed");}
        public ReadClaim beginRead(Snapshot expected,String tool,JSONObject arguments,String call,Access access)throws Exception{requireCurrent(expected);if(!expected.planning()||access!=Access.READ||active!=null)throw new SecurityException("read not admitted");begins++;return active=readClaim(expected,UUID.randomUUID().toString(),tool,hash(arguments));}
        public void finishRead(ReadClaim claim,JSONObject receipt,ReadOutcome outcome){requireOwned(claim);if(active!=claim)throw new SecurityException("original read changed");finishes++;active=null;}
        public void requireReadDispatch(String tool,JSONObject arguments,RunControl supplied)throws Exception{dispatchChecks++;if(supplied!=original()||active==null||!active.tool.equals(tool)||!active.argumentsHash.equals(hash(arguments))||arguments.has("_hostOperationId")&&!active.operationId.equals(arguments.optString("_hostOperationId")))throw new SecurityException("no exact native read claim");requireCurrent(active.snapshot);}
        public JSONObject submitPlan(Snapshot expected,JSONObject proposal,String call){throw new AssertionError("wrapper cannot submit plans");}
        public JSONObject requestPhase(Snapshot expected,String next,String reason,String call){throw new AssertionError("wrapper cannot request phases");}
        public void close(){closes++;active=null;}
    }
    private static class Port implements CapabilityPort {
        final Map<String,ExecutionPlanning.Access> nativeRoutes=new LinkedHashMap<>();final Set<String> calls=new LinkedHashSet<>();
        int executions,before;boolean routeCurrent=true,writeApproved;
        Port(){nativeRoutes.put("native_read",ExecutionPlanning.Access.READ);nativeRoutes.put("journal_read",ExecutionPlanning.Access.READ);nativeRoutes.put("ssh_exec",ExecutionPlanning.Access.DENY);nativeRoutes.put("bounded_proposal",ExecutionPlanning.Access.RESTRICTED_PROPOSAL);}
        public JSONArray tools()throws Exception{JSONArray result=new JSONArray();for(String name:nativeRoutes.keySet())result.put(definition(name));return result;}
        public boolean supports(String name){return nativeRoutes.containsKey(name);}
        public boolean sideEffecting(String name){return "journal_read".equals(name);}
        public ExecutionPlanning.Access planningAccess(String name,JSONObject arguments){if(!routeCurrent)return ExecutionPlanning.Access.DENY;if(arguments!=null&&arguments.optBoolean("write"))return ExecutionPlanning.Access.DENY;return nativeRoutes.getOrDefault(name,ExecutionPlanning.Access.DENY);}
        public void validate(String name,JSONObject arguments){if(!supports(name))throw new SecurityException("not an original route");calls.add("validate");}
        public JSONObject beforeTool(String name,JSONObject arguments,RunControl original){before++;return null;}
        public JSONObject execute(String name,JSONObject arguments,RunControl original)throws Exception{if("ssh_exec".equals(name)&&!writeApproved)throw new SecurityException("original approval still required");executions++;return new JSONObject().put("ok",true);}
        public JSONObject prepareTurn(String query,int round,int steps,RunControl original)throws Exception{calls.add("prepareTurn");return new JSONObject().put("tools",tools()).put("reference",new JSONObject().put("actualSource","kept")).put("nativeTurnMarker",7);}
        public JSONObject contextSnapshot(String query)throws Exception{calls.add("contextSnapshot");return new JSONObject().put("source","original");}
        public void beginRun(String goal,RunControl original){calls.add("beginRun");}
        public void prepareTurnSurface(RunControl original){calls.add("prepareTurnSurface");}
        public void admitCapabilities(CapabilitySnapshot snapshot,RunControl original){calls.add("admitCapabilities");}
        public boolean responseCurrent(RunControl original){calls.add("responseCurrent");return true;}
        public boolean stepReadSourceCurrent(String name,JSONObject receipt,RunControl original){calls.add("stepReadSourceCurrent");return true;}
        public void beforeModelRequest(String request,JSONArray messages,RunControl original){calls.add("beforeModelRequest");}
        public JSONObject afterModelResponse(String request,String answer,RunControl original)throws Exception{calls.add("afterModelResponse");return new JSONObject().put("source","same");}
        public void modelRequestAbandoned(String request){calls.add("modelRequestAbandoned");}
        public NativeToolObservation.Requirement observationRequirement(String name,JSONObject arguments,RunControl original){calls.add("observationRequirement");return NativeToolObservation.Requirement.IMAGE;}
        public NativeToolObservation takeToolObservation(String name,String operation,RunControl original){calls.add("takeToolObservation");return null;}
        public NativeToolPlan takeToolPlan(String name,String operation,RunControl original){calls.add("takeToolPlan");return null;}
        public NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl original){calls.add("nativeReadContextRepair");return null;}
        public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl original){calls.add("afterTool");}
        public void endRun(RunControl original){calls.add("endRun");}
        public void close(){calls.add("close");}
    }

    @Test public void absentPlanningReturnsTheExactOriginalPort(){Port original=new Port();assertSame(original,PlanningCapabilityPort.wrap(original,null));}
    @Test public void readHintsNamesAndFalseEffectFlagsNeverGrantPlanningAccess()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);int[] dispatched={0};
        CapabilityPort declaredRead=new CapabilityPort(){public JSONArray tools()throws Exception{return new JSONArray().put(definition("workspace_read"));}public boolean supports(String name){return "workspace_read".equals(name);}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject arguments,RunControl live){dispatched[0]++;return new JSONObject();}};
        CapabilityPort wrapped=PlanningCapabilityPort.wrap(declaredRead,planning);assertEquals(0,wrapped.tools().length());assertFalse(wrapped.supports("workspace_read"));
        assertThrows(SecurityException.class,()->wrapped.execute("workspace_read",args(),original));assertEquals(0,dispatched[0]);assertEquals(0,planning.dispatchChecks);
    }
    @Test public void catalogueUsesNativeClassificationAndKeepsJournalledReads()throws Exception{
        Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,new Planning(control()));
        assertEquals("[\"native_read\",\"journal_read\"]",names(wrapped.tools()).toString());assertTrue(wrapped.sideEffecting("journal_read"));assertFalse(wrapped.sideEffecting("ssh_exec"));
        assertFalse(wrapped.supports("ssh_exec"));assertFalse(wrapped.supports("bounded_proposal"));assertFalse(wrapped.supports("invented_read"));
        nativePort.routeCurrent=false;assertEquals(0,wrapped.tools().length());
    }
    @Test public void argumentDependentReadRestrictionsApplyBeforeClaimAndAtDispatch()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);
        JSONObject changing=args().put("write",true);assertThrows(SecurityException.class,()->wrapped.validate("native_read",changing));assertThrows(SecurityException.class,()->wrapped.beforeTool("native_read",changing,original));assertThrows(SecurityException.class,()->wrapped.execute("native_read",changing,original));
        assertEquals(0,nativePort.before);assertEquals(0,nativePort.executions);assertEquals(0,planning.begins);assertEquals(0,planning.dispatchChecks);
    }
    @Test public void beforeToolDoesNotRequireOrMintAReadButExecuteRequiresTheExactLiveClaim()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);JSONObject args=args();
        wrapped.validate("native_read",args);assertNull(wrapped.beforeTool("native_read",args,original));assertEquals(0,planning.begins);assertEquals(0,planning.dispatchChecks);
        assertThrows(SecurityException.class,()->wrapped.execute("native_read",args,original));assertEquals(0,nativePort.executions);
        ExecutionPlanning.ReadClaim claim=planning.beginRead(planning.capture(),"native_read",args,"call",ExecutionPlanning.Access.READ);
        assertThrows(SecurityException.class,()->wrapped.execute("native_read",new JSONObject().put("path","other.txt"),original));
        assertThrows(SecurityException.class,()->wrapped.execute("native_read",args,control()));assertEquals(0,nativePort.executions);
        JSONObject receipt=wrapped.execute("native_read",args,original);assertTrue(receipt.getBoolean("ok"));assertEquals(1,planning.begins);assertEquals(0,planning.finishes);assertEquals(1,nativePort.executions);
        planning.finishRead(claim,receipt,ExecutionPlanning.ReadOutcome.RECEIPT);assertThrows(SecurityException.class,()->wrapped.execute("native_read",args,original));assertEquals(1,nativePort.executions);
    }
    @Test public void runnerOwnedInterceptorsKeepTheirStageFenceWithoutBecomingDelegateTools()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);wrapped.prepareTurn("plan",1,0,original);
        for(String name:new String[]{"phone_observe","phone_wait","phone_routes","run_ask_user",ExecutionPlanning.SUBMIT,ExecutionPlanning.REQUEST}){
            assertNull(wrapped.beforeTool(name,new JSONObject(),original));assertFalse(wrapped.supports(name));
            assertThrows(SecurityException.class,()->wrapped.validate(name,new JSONObject()));assertThrows(SecurityException.class,()->wrapped.execute(name,new JSONObject(),original));
        }
        assertEquals(6,nativePort.before);assertEquals(0,planning.begins);assertEquals(0,planning.dispatchChecks);
        for(String name:new String[]{"phone_click","invented_read","workspace_write"})assertThrows(SecurityException.class,()->wrapped.beforeTool(name,new JSONObject(),original));
        planning.planRevision++;assertThrows(SecurityException.class,()->wrapped.beforeTool("phone_observe",new JSONObject(),original));assertEquals(6,nativePort.before);
    }
    @Test public void aDelegateCannotBorrowTheClassificationOfARunnerOwnedName()throws Exception{
        RunControl original=control();Port nativePort=new Port();nativePort.nativeRoutes.put("phone_observe",ExecutionPlanning.Access.DENY);CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,new Planning(original));
        assertThrows(SecurityException.class,()->wrapped.beforeTool("phone_observe",new JSONObject(),original));assertEquals(0,nativePort.before);
    }
    @Test public void onlyNativeJournalOrImageConsumersReceiveTheAlreadyVerifiedOperationMetadata()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);JSONObject[] received={null};
        Port nativePort=new Port(){
            public NativeToolObservation.Requirement observationRequirement(String name,JSONObject arguments,RunControl live){assertFalse(arguments.has("_hostOperationId"));return "image_read".equals(name)?NativeToolObservation.Requirement.IMAGE:NativeToolObservation.Requirement.NONE;}
            public JSONObject execute(String name,JSONObject arguments,RunControl live)throws Exception{received[0]=new JSONObject(arguments.toString());return super.execute(name,arguments,live);}
        };nativePort.nativeRoutes.put("image_read",ExecutionPlanning.Access.READ);CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);
        for(String name:new String[]{"native_read","journal_read","image_read"}){
            ExecutionPlanning.ReadClaim claim=planning.beginRead(planning.capture(),name,args(),"call",ExecutionPlanning.Access.READ);JSONObject transport=args().put("_hostOperationId",claim.operationId);
            assertThrows(SecurityException.class,()->wrapped.execute(name,args().put("_hostOperationId",UUID.randomUUID().toString()),original));
            JSONObject result=wrapped.execute(name,transport,original);assertEquals(!name.equals("native_read"),received[0].has("_hostOperationId"));assertEquals(claim.operationId,transport.getString("_hostOperationId"));
            planning.finishRead(claim,result,ExecutionPlanning.ReadOutcome.RECEIPT);
        }
        assertEquals(3,nativePort.executions);assertEquals(3,planning.begins);assertEquals(3,planning.finishes);
    }
    @Test public void ownerReturnContextCannotReadWithoutTheOriginalClaimBearingBrowserInterface()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);
        ExecutionPlanning.ReadClaim pending=planning.beginRead(planning.capture(),"native_read",args(),"call",ExecutionPlanning.Access.READ);
        assertThrows(PlanningCapabilityPort.OwnerReturnReviewRequired.class,()->wrapped.contextSnapshot("native.browser.owner-return-observation.v1"));assertFalse(nativePort.calls.contains("contextSnapshot"));assertSame(pending,planning.active);assertEquals(0,planning.finishes);
        assertEquals("original",wrapped.contextSnapshot("ordinary reference").getString("source"));planning.finishRead(pending,new JSONObject(),ExecutionPlanning.ReadOutcome.UNCONFIRMED);
        planning.phase="executing";planning.revision++;assertEquals("original",wrapped.contextSnapshot("native.browser.owner-return-observation.v1").getString("source"));
    }
    @Test public void phaseChangeWhileBuildingTheCatalogueCannotExposeAnExecutingSnapshot()throws Exception{
        Planning planning=new Planning(control());planning.phase="executing";
        Port nativePort=new Port(){public JSONArray tools()throws Exception{JSONArray offered=super.tools();planning.phase="planning";planning.revision++;return offered;}};
        CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);assertThrows(SecurityException.class,wrapped::tools);
    }
    @Test public void planRevisionChangeInvalidatesThePreparedResponseAndQueuedTool()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);
        JSONObject turn=wrapped.prepareTurn("plan",1,0,original);assertEquals(7,turn.getInt("nativeTurnMarker"));assertEquals("kept",turn.getJSONObject("reference").getString("actualSource"));
        planning.planRevision++;assertFalse(wrapped.responseCurrent(original));assertThrows(SecurityException.class,()->wrapped.beforeTool("native_read",args(),original));assertThrows(SecurityException.class,()->wrapped.execute("native_read",args(),original));assertEquals(0,nativePort.executions);
        wrapped.prepareTurn("revised plan",2,0,original);assertTrue(wrapped.responseCurrent(original));
    }
    @Test public void executingUsesOriginalApprovalAndReturningToPlanningRejectsAQueuedWrite()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);planning.phase="executing";Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);
        assertEquals(nativePort.tools().length(),wrapped.tools().length());wrapped.prepareTurn("execute",1,0,original);
        assertThrows(SecurityException.class,()->wrapped.execute("ssh_exec",args(),original));assertEquals(0,planning.dispatchChecks);
        nativePort.writeApproved=true;wrapped.execute("ssh_exec",args(),original);assertEquals(1,nativePort.executions);
        planning.phase="planning";planning.revision++;assertThrows(SecurityException.class,()->wrapped.execute("ssh_exec",args(),original));assertEquals(1,nativePort.executions);assertFalse(wrapped.responseCurrent(original));
    }
    @Test public void pauseResumeCannotMakeTheOldTurnCurrentAgain()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);CapabilityPort wrapped=PlanningCapabilityPort.wrap(new Port(),planning);wrapped.prepareTurn("plan",1,0,original);
        original.pause();original.resume();assertFalse(wrapped.responseCurrent(original));assertThrows(SecurityException.class,()->wrapped.validate("native_read",args()));
    }
    @Test public void catalogueProjectionCannotMutateTheOriginalDefinitions()throws Exception{
        Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,new Planning(control()));JSONArray projected=wrapped.tools();projected.getJSONObject(0).getJSONObject("function").put("name","ssh_exec");assertEquals("native_read",names(wrapped.tools()).getString(0));assertEquals("native_read",names(nativePort.tools()).getString(0));
    }
    @Test public void originalLifecycleAndTypedConsumerHooksAreAllForwarded()throws Exception{
        RunControl original=control();Port nativePort=new Port();CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,new Planning(original));
        for(var method:CapabilityPort.class.getDeclaredMethods())assertEquals("Missing wrapper hook: "+method.getName(),PlanningCapabilityPort.class,wrapped.getClass().getMethod(method.getName(),method.getParameterTypes()).getDeclaringClass());
        wrapped.beginRun("plan",original);wrapped.prepareTurnSurface(original);wrapped.admitCapabilities(new CapabilitySnapshot(TASK,RUN,1,original,Set.of("native_read"),()->{}),original);wrapped.prepareTurn("plan",1,0,original);
        assertEquals("original",wrapped.contextSnapshot("source").getString("source"));assertTrue(wrapped.responseCurrent(original));assertTrue(wrapped.stepReadSourceCurrent("native_read",new JSONObject(),original));
        wrapped.beforeModelRequest("request",new JSONArray(),original);assertEquals("same",wrapped.afterModelResponse("request","answer",original).getString("source"));wrapped.modelRequestAbandoned("request");
        assertEquals(NativeToolObservation.Requirement.IMAGE,wrapped.observationRequirement("native_read",args(),original));wrapped.takeToolObservation("native_read","operation",original);wrapped.takeToolPlan("native_read","operation",original);wrapped.nativeReadContextRepair(new JSONArray(),original);wrapped.afterTool("native_read",args(),new JSONObject(),original);wrapped.endRun(original);wrapped.close();
        for(String hook:new String[]{"beginRun","prepareTurnSurface","admitCapabilities","prepareTurn","contextSnapshot","responseCurrent","stepReadSourceCurrent","beforeModelRequest","afterModelResponse","modelRequestAbandoned","observationRequirement","takeToolObservation","takeToolPlan","nativeReadContextRepair","afterTool","endRun","close"})assertTrue(hook,nativePort.calls.contains(hook));
        assertThrows(CapabilityPort.Unavailable.class,wrapped::tools);
    }
    @Test public void actualDeviceMuxForwardsOnlyTheSelectedNativeRoutesClassification()throws Exception{
        Port project=new Port(),device=new Port();project.nativeRoutes.clear();project.nativeRoutes.put("project_read_file",ExecutionPlanning.Access.READ);device.nativeRoutes.clear();device.nativeRoutes.put("device_invoke_tool",ExecutionPlanning.Access.DENY);
        CapabilityPort mux=new DeviceCapabilityMux(project,device);assertEquals(ExecutionPlanning.Access.READ,mux.planningAccess("project_read_file",null));assertEquals(ExecutionPlanning.Access.DENY,mux.planningAccess("device_invoke_tool",null));
        CapabilityPort wrapped=PlanningCapabilityPort.wrap(mux,new Planning(control()));assertEquals("[\"project_read_file\"]",names(wrapped.tools()).toString());
    }
    @Test public void closeReleasesOnlyItsOriginalPlanningHandleAfterTheDelegate()throws Exception{
        RunControl original=control();Planning planning=new Planning(original),unrelated=new Planning(original);CapabilityPort[] wrapped={null};
        planning.beginRead(planning.capture(),"native_read",args(),"original-read",ExecutionPlanning.Access.READ);
        Port nativePort=new Port(){@Override public void close(){assertEquals(0,planning.closes);assertFalse(wrapped[0].supports("native_read"));assertThrows(CapabilityPort.Unavailable.class,()->wrapped[0].tools());super.close();}};
        wrapped[0]=PlanningCapabilityPort.wrap(nativePort,planning);wrapped[0].close();
        assertTrue(nativePort.calls.contains("close"));assertEquals(1,planning.closes);assertNull(planning.active);assertEquals(0,unrelated.closes);assertFalse(original.isCancelled());
    }
    @Test public void aDelegateCloseFailureStillReleasesTheSamePlanningHandle()throws Exception{
        RunControl original=control();Planning planning=new Planning(original);RuntimeException failure=new IllegalStateException("original close failed");
        planning.beginRead(planning.capture(),"native_read",args(),"original-read",ExecutionPlanning.Access.READ);
        Port nativePort=new Port(){@Override public void close(){super.close();throw failure;}};
        CapabilityPort wrapped=PlanningCapabilityPort.wrap(nativePort,planning);
        assertSame(failure,assertThrows(IllegalStateException.class,wrapped::close));assertTrue(nativePort.calls.contains("close"));
        assertEquals(1,planning.closes);assertNull(planning.active);assertThrows(CapabilityPort.Unavailable.class,wrapped::tools);assertFalse(original.isCancelled());
    }
    @Test public void actualAutomationWrapperKeepsTheOriginalProjectRestriction()throws Exception{
        Port nativePort=new Port();nativePort.nativeRoutes.clear();nativePort.nativeRoutes.put("workspace_read",ExecutionPlanning.Access.READ);nativePort.nativeRoutes.put("workspace_write",ExecutionPlanning.Access.DENY);
        JSONObject standard=new JSONObject().put("kind","workspace_artifact").put("projectId",TASK).put("path","result.md").put("requiredHeading","# Result");
        CapabilityPort scoped=new PlanWorkspacePort(nativePort,standard,id->null);assertEquals(ExecutionPlanning.Access.READ,scoped.planningAccess("workspace_read",null));assertEquals(ExecutionPlanning.Access.DENY,scoped.planningAccess("workspace_write",null));
        assertEquals(ExecutionPlanning.Access.READ,scoped.planningAccess("workspace_read",new JSONObject().put("projectId",TASK).put("path","notes.md")));
        assertThrows(SecurityException.class,()->scoped.planningAccess("workspace_read",new JSONObject().put("projectId",RUN).put("path","notes.md")));assertEquals(0,nativePort.executions);
    }
}
