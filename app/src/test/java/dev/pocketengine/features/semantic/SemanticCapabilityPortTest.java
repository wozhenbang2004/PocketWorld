package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SemanticCapabilityPortTest {
    private static final String TASK="11111111-1111-1111-1111-111111111111",RUN="22222222-2222-2222-2222-222222222222";
    private static final class Fixture {
        final RunControl control=new RunControl();final AtomicLong clock=new AtomicLong(10);final FakePlatform platform=new FakePlatform();
        final FakeAuthority authority=new FakeAuthority(control);final SemanticCapabilityPort port;
        Fixture() throws Exception {this(null,true);}
        Fixture(CapabilityPort existing,boolean dispatch) throws Exception {control.bindHostRun(TASK,RUN,1);port=new SemanticCapabilityPort(existing,control,authority,platform,dispatch,clock::get);}
        JSONObject discover() throws Exception {return port.execute(SemanticCapabilityPort.DISCOVER,new JSONObject("{kind:'web',url:'https://example.org/'}"),control);}
        JSONObject args(JSONObject discovery) throws Exception {return new JSONObject().put("discoveryId",discovery.getString("discoveryId")).put("candidateId",discovery.getJSONArray("candidates").getJSONObject(0).getString("candidateId"));}
        JSONObject operation(JSONObject args) throws Exception {String op=UUID.randomUUID().toString();authority.operation=op;authority.original=SemanticJson.copy(args);return SemanticJson.copy(args).put("_hostOperationId",op);}
        JSONObject dispatch(JSONObject args) throws Exception {return port.execute(SemanticCapabilityPort.DISPATCH,operation(args),control);}
    }
    private static final class FakeAuthority implements SemanticCapabilityPort.Authority {
        final RunControl originalControl;boolean live=true,approved=true,prepared=true;int approvals,checks;String operation;JSONObject original,capability;Runnable onApprove;
        FakeAuthority(RunControl control){originalControl=control;}
        @Override public void check(String tool,RunControl control) throws Exception {checks++;if(!live||control!=originalControl)throw new SecurityException("revoked");control.check();}
        @Override public void requirePrepared(String id,String tool,JSONObject args) throws Exception {
            if(!prepared||!id.equals(operation)||!SemanticJson.hash(args).equals(SemanticJson.hash(original)))throw new SecurityException("not original prepared operation");
        }
        @Override public JSONObject approve(String tool,JSONObject args,JSONObject identity) throws Exception {
            approvals++;capability=SemanticJson.copy(identity);if(onApprove!=null)onApprove.run();return new JSONObject().put("approved",approved).put("approvalId","approval-fixture");
        }
    }
    private static final class FakePlatform implements SemanticPlatform {
        List<Target> targets=new ArrayList<>();int calls,effects;String session="original";Runnable beforeNative,afterEffect;boolean throwsAfterEffect,emptyReceipt,definiteRefusal;
        FakePlatform() throws Exception {targets.add(target(1,"cert-a"));}
        static Target target(int version,String cert) throws Exception {return new Target(new JSONObject().put("component","example.browser/example.browser.Main").put("packageName","example.browser").put("versionCode",version).put("certificates",new JSONArray().put(cert)),"Example","browser");}
        @Override public List<Target> discover(SemanticRequest request,RunControl control) {return new ArrayList<>(targets);}
        @Override public JSONObject resourceIdentity() throws Exception {return new JSONObject().put("displayId",0).put("executionMode","main_screen").put("serviceSessionId",session);}
        @Override public JSONObject dispatch(SemanticRequest request,Target target,RunControl control,Admission admission) throws Exception {
            calls++;if(beforeNative!=null)beforeNative.run();try{admission.check();}catch(Exception denied){return new JSONObject().put("accepted",false).put("code","final_gate_refused");}
            if(definiteRefusal)return new JSONObject().put("accepted",false).put("code","app_unavailable");effects++;if(afterEffect!=null)afterEffect.run();
            if(throwsAfterEffect)throw new java.io.IOException("receipt lost");return emptyReceipt?null:new JSONObject().put("accepted",true);
        }
    }
    @Test public void realDiscoveryShapeDoesNotDispatchAndLabelsAreUntrusted() throws Exception {
        Fixture f=new Fixture();JSONObject result=f.discover();assertEquals("discovered",result.getString("status"));assertEquals(1,result.getJSONArray("candidates").length());
        assertEquals(0,f.platform.calls);assertTrue(result.getJSONArray("candidates").getJSONObject(0).getBoolean("labelIsUntrusted"));assertFalse(result.getBoolean("automaticFallback"));
    }
    @Test public void missingHandlerIsExplicitAndNeverClaimsNotInstalled() throws Exception {
        Fixture f=new Fixture();f.platform.targets.clear();JSONObject result=f.discover();assertEquals("unavailable",result.getString("status"));assertEquals(0,result.getJSONArray("candidates").length());assertTrue(result.getString("message").contains("不可见"));assertEquals(0,f.platform.effects);
    }
    @Test public void successfulLaunchIsOnlyAcceptedWithPostconditionStillRequired() throws Exception {
        Fixture f=new Fixture();JSONObject out=f.dispatch(f.args(f.discover()));assertTrue(out.getBoolean("accepted"));assertEquals(1,f.platform.effects);
        assertFalse(out.getBoolean("verified"));assertFalse(out.getBoolean("verifiedBusinessSuccess"));assertTrue(out.getBoolean("requiresPostconditionCheck"));assertEquals("observe",out.getString("nextObservationAction"));
        assertEquals("https://example.org/",f.authority.capability.getJSONObject("request").getString("url"));assertEquals("original",f.authority.capability.getJSONObject("resource").getString("serviceSessionId"));
    }
    @Test public void awaitingApprovalHasNoPlatformCall() throws Exception {
        Fixture f=new Fixture();f.authority.approved=false;JSONObject result=f.dispatch(f.args(f.discover()));assertEquals("approval_required",result.getString("code"));assertFalse(result.getBoolean("accepted"));assertEquals(0,f.platform.calls);
    }
    @Test public void requiresOriginalPreparedOperationAndRejectsMissingOperation() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());assertThrows(SecurityException.class,()->f.port.execute(SemanticCapabilityPort.DISPATCH,args,f.control));
        f.authority.prepared=false;JSONObject result=f.dispatch(args);assertFalse(result.getBoolean("accepted"));assertEquals(0,f.authority.approvals);assertEquals(0,f.platform.calls);
    }
    @Test public void exactOriginalArgumentsCannotBeSubstituted() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover()),operation=f.operation(args);f.authority.original.put("candidateId","0".repeat(64));
        assertFalse(f.port.execute(SemanticCapabilityPort.DISPATCH,operation,f.control).getBoolean("accepted"));assertEquals(0,f.platform.calls);
    }
    @Test public void discoveryAndDispatchRejectModelHostIdentityAndRoutes() throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalArgumentException.class,()->f.port.validate(SemanticCapabilityPort.DISCOVER,new JSONObject("{kind:'web',url:'https://example.org',component:'evil'}")));
        JSONObject args=f.args(f.discover()).put("taskId",TASK);assertThrows(IllegalArgumentException.class,()->f.port.validate(SemanticCapabilityPort.DISPATCH,args));
        assertThrows(SecurityException.class,()->f.port.execute(SemanticCapabilityPort.DISCOVER,new JSONObject("{kind:'web',url:'https://example.org'}").put("_hostOperationId",UUID.randomUUID().toString()),f.control));
    }
    @Test public void staleOrForeignDiscoveryDoesNotApproveOrDispatch() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());args.put("discoveryId",UUID.randomUUID().toString());assertEquals("stale_semantic_discovery",f.dispatch(args).getString("code"));
        args=f.args(f.discover());args.put("candidateId","0".repeat(64));assertEquals("stale_semantic_candidate",f.dispatch(args).getString("code"));assertEquals(0,f.authority.approvals);assertEquals(0,f.platform.calls);
    }
    @Test public void expiredDiscoveryUsesMonotonicClock() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.clock.addAndGet(SemanticCapabilityPort.DISCOVERY_TTL_MS);assertEquals("stale_semantic_discovery",f.dispatch(args).getString("code"));assertEquals(0,f.platform.effects);
    }
    @Test public void changedPackageVersionOrCertificateRejectsOldCandidate() throws Exception {
        for(boolean certificate:new boolean[]{false,true}) {
            Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.platform.targets.set(0,FakePlatform.target(certificate?1:2,certificate?"cert-b":"cert-a"));
            assertFalse(f.dispatch(args).getBoolean("accepted"));assertEquals(0,f.platform.calls);assertEquals(0,f.authority.approvals);
        }
    }
    @Test public void approvalDoesNotAuthorizeChangedTargetOrService() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.authority.onApprove=()->f.platform.session="replacement";assertFalse(f.dispatch(args).getBoolean("accepted"));assertEquals(0,f.platform.calls);
    }
    @Test public void finalNativeAdmissionStopsRevokedAuthority() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.platform.beforeNative=()->f.authority.live=false;assertFalse(f.dispatch(args).getBoolean("accepted"));assertEquals(1,f.platform.calls);assertEquals(0,f.platform.effects);
    }
    @Test public void pauseAndResumeBeforeNativeCallInvalidatesOldDecision() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.platform.beforeNative=()->{f.control.pause();f.control.resume();};assertFalse(f.dispatch(args).getBoolean("accepted"));assertEquals(0,f.platform.effects);
    }
    @Test public void originalControlCannotBeReplacedWithSameTaskIdentifiers() throws Exception {
        Fixture f=new Fixture();RunControl other=new RunControl();other.bindHostRun(TASK,RUN,1);JSONObject result=f.port.execute(SemanticCapabilityPort.DISPATCH,f.operation(f.args(f.discover())),other);assertFalse(result.getBoolean("accepted"));assertEquals(0,f.platform.calls);
    }
    @Test public void unknownReceiptBlocksAnotherOperationAndNoFallbackOccurs() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.platform.throwsAfterEffect=true;JSONObject first=f.dispatch(args);assertTrue(first.isNull("accepted"));assertTrue(first.getBoolean("requiresReconciliation"));assertFalse(first.getBoolean("automaticFallback"));
        f.platform.throwsAfterEffect=false;JSONObject second=f.dispatch(f.args(f.discover()));assertEquals("unknown",second.getString("status"));assertEquals(1,f.platform.calls);assertEquals(1,f.platform.effects);
    }
    @Test public void emptyReceiptIsUnknownAndSameOperationNeverReenters() throws Exception {
        Fixture f=new Fixture();JSONObject operation=f.operation(f.args(f.discover()));f.platform.emptyReceipt=true;
        assertTrue(f.port.execute(SemanticCapabilityPort.DISPATCH,operation,f.control).isNull("accepted"));assertEquals("unknown",f.port.execute(SemanticCapabilityPort.DISPATCH,operation,f.control).getString("status"));assertEquals(1,f.platform.effects);
    }
    @Test public void definiteFailureRequiresFreshDiscoveryAndDoesNotTryAnotherTarget() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.platform.definiteRefusal=true;JSONObject out=f.dispatch(args);assertFalse(out.getBoolean("accepted"));assertEquals("not_dispatched",out.getString("status"));assertFalse(out.getBoolean("automaticFallback"));
        assertEquals("stale_semantic_discovery",f.dispatch(args).getString("code"));assertEquals(1,f.platform.calls);
    }
    @Test public void acceptedReceiptSurvivesCancellationWithoutBusinessSuccessClaim() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.platform.afterEffect=f.control::cancel;JSONObject out=f.dispatch(args);
        assertTrue(out.getBoolean("accepted"));assertTrue(out.getBoolean("requiresPause"));assertTrue(out.getBoolean("executionInvalidatedAfterDispatch"));assertFalse(out.getBoolean("verifiedBusinessSuccess"));
    }
    @Test public void backgroundOrReadOnlyBindingHasDiscoveryButNoDispatch() throws Exception {
        Fixture f=new Fixture(null,false);assertTrue(f.port.supports(SemanticCapabilityPort.DISCOVER));assertFalse(f.port.supports(SemanticCapabilityPort.DISPATCH));assertEquals(1,f.port.tools().length());assertFalse(f.discover().getBoolean("dispatchAvailable"));
        assertThrows(SecurityException.class,()->f.port.execute(SemanticCapabilityPort.DISPATCH,new JSONObject(),f.control));
    }
    @Test public void boundedDiscoverySnapshotsEvictOldest() throws Exception {
        Fixture f=new Fixture();JSONObject old=f.args(f.discover());for(int i=0;i<16;i++)f.discover();assertEquals("stale_semantic_discovery",f.dispatch(old).getString("code"));
    }
    @Test public void closedPortCannotKeepDiscoveringOrDispatching() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.discover());f.port.close();assertThrows(SecurityException.class,()->f.discover());assertEquals("stale_semantic_discovery",f.dispatch(args).getString("code"));assertEquals(0,f.platform.calls);
    }
    @Test public void wrapperPreservesOriginalLifecycleAndPreparedTurnTools() throws Exception {
        int[] callbacks={0};CapabilityPort original=new CapabilityPort(){
            public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject("{type:'function',function:{name:'original',parameters:{type:'object'}}}"));}
            public boolean supports(String name){return name.equals("original");}public boolean sideEffecting(String name){return false;}
            public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{return new JSONObject().put("original",true);}
            public void beginRun(String goal,RunControl control){callbacks[0]++;}
            public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{callbacks[0]++;return new JSONObject().put("tools",tools()).put("reference",new JSONObject().put("kept",true));}
            public boolean responseCurrent(RunControl control){callbacks[0]++;return true;}
            public JSONObject beforeTool(String n,JSONObject a,RunControl c){callbacks[0]++;return null;}
            public void afterTool(String n,JSONObject a,JSONObject r,RunControl c){callbacks[0]++;}
            public void endRun(RunControl control){callbacks[0]++;}public void close(){callbacks[0]++;}
        };
        Fixture f=new Fixture(original,true);f.port.beginRun("goal",f.control);JSONObject turn=f.port.prepareTurn("goal",0,0,f.control);assertEquals(3,turn.getJSONArray("tools").length());assertTrue(turn.getJSONObject("reference").getBoolean("kept"));
        assertTrue(f.port.responseCurrent(f.control));f.port.beforeTool("original",new JSONObject(),f.control);f.port.afterTool("original",new JSONObject(),new JSONObject(),f.control);assertTrue(f.port.execute("original",new JSONObject(),f.control).getBoolean("original"));
        f.port.endRun(f.control);f.port.close();assertEquals(7,callbacks[0]);
    }
    @Test public void lateModelAnswerAfterPauseCycleIsNotCurrent() throws Exception {
        Fixture f=new Fixture();f.port.prepareTurn("q",1,0,f.control);f.control.pause();f.control.resume();assertFalse(f.port.responseCurrent(f.control));
    }
    @Test public void calendarDraftApprovalPinsFullRequestAndPausesForOwnerWithoutSaveClaim() throws Exception {
        Fixture f=new Fixture();JSONObject discovery=f.port.execute(SemanticCapabilityPort.DISCOVER,SemanticCalendarRequestTest.draft(),f.control);
        JSONObject out=f.dispatch(f.args(discovery));assertTrue(out.getBoolean("accepted"));assertTrue(out.getBoolean("requiresPause"));assertTrue(out.getBoolean("requiresOwnerCompletion"));
        assertFalse(out.getBoolean("savedEventVerified"));assertFalse(out.getBoolean("verifiedBusinessSuccess"));assertFalse(out.has("eventId"));assertTrue(f.port.sideEffecting(SemanticCapabilityPort.DISPATCH));
        JSONObject approved=f.authority.capability.getJSONObject("request");assertEquals("calendar_draft",approved.getString("kind"));assertEquals("Asia/Shanghai",approved.getString("timeZone"));
        assertTrue(f.authority.capability.getBoolean("receiverMaySaveAutomatically"));assertTrue(f.authority.capability.getBoolean("timeZoneIsRequestedHint"));assertFalse(out.getBoolean("timeZoneVerified"));
        assertEquals(SemanticCalendarRequestTest.draft().getLong("start"),approved.getLong("start"));assertEquals("owner_completes_in_target_app",out.getString("followup"));assertEquals(1,f.platform.effects);
    }
    @Test public void calendarEditorAbsentOrChangedCannotFallBackOrDispatch() throws Exception {
        Fixture f=new Fixture();f.platform.targets.clear();JSONObject absent=f.port.execute(SemanticCapabilityPort.DISCOVER,SemanticCalendarRequestTest.draft(),f.control);
        assertEquals("unavailable",absent.getString("status"));assertFalse(absent.getBoolean("automaticFallback"));assertEquals(0,f.platform.calls);
        f.platform.targets.add(FakePlatform.target(1,"cert-a"));JSONObject args=f.args(f.port.execute(SemanticCapabilityPort.DISCOVER,SemanticCalendarRequestTest.draft(),f.control));
        f.platform.targets.set(0,FakePlatform.target(2,"cert-a"));assertFalse(f.dispatch(args).getBoolean("accepted"));assertEquals(0,f.authority.approvals);assertEquals(0,f.platform.calls);
    }
    @Test public void calendarNeedsOriginalPreparedApprovalAndCancellationStillFencesDispatch() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.port.execute(SemanticCapabilityPort.DISCOVER,SemanticCalendarRequestTest.draft(),f.control));
        f.authority.approved=false;assertEquals("approval_required",f.dispatch(args).getString("code"));assertEquals(0,f.platform.calls);
        f.authority.approved=true;f.platform.beforeNative=f.control::cancel;assertFalse(f.dispatch(args).getBoolean("accepted"));assertEquals(0,f.platform.effects);
    }
    @Test public void lostCalendarLaunchReceiptNeverBecomesAnotherDraftOrSaveConfirmation() throws Exception {
        Fixture f=new Fixture();JSONObject args=f.args(f.port.execute(SemanticCapabilityPort.DISCOVER,SemanticCalendarRequestTest.draft(),f.control));f.platform.throwsAfterEffect=true;
        JSONObject unknown=f.dispatch(args);assertEquals("unknown",unknown.getString("status"));assertTrue(unknown.isNull("accepted"));assertTrue(unknown.getBoolean("requiresReconciliation"));
        f.platform.throwsAfterEffect=false;JSONObject next=f.args(f.port.execute(SemanticCapabilityPort.DISCOVER,SemanticCalendarRequestTest.draft(),f.control));
        assertEquals("unknown",f.dispatch(next).getString("status"));assertEquals(1,f.platform.effects);assertFalse(unknown.getBoolean("verifiedBusinessSuccess"));
    }
}
