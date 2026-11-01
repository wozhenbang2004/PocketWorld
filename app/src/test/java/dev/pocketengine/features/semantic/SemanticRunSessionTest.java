package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Memory/lifetime checks only; the separate resume review uses actual TaskStore and Module.bind. */
public final class SemanticRunSessionTest {
    private static final String TASK="11111111-1111-1111-1111-111111111111",RUN="22222222-2222-2222-2222-222222222222";
    private static final class Fixture implements SemanticCapabilityPort.Authority,SemanticPlatform {
        final RunControl control=new RunControl();final AtomicLong clock=new AtomicLong(1);final SemanticRunSession session;
        final Target target;int effects;boolean loseReceipt;
        Fixture()throws Exception {control.bindHostRun(TASK,RUN,1);session=new SemanticRunSession(control,clock::get);
            target=new Target(new JSONObject().put("component","review.editor/review.editor.Main").put("packageName","review.editor"),"fixture","official_intent");}
        SemanticCapabilityPort port()throws Exception{return new SemanticCapabilityPort(null,control,this,this,true,session);}
        public void check(String tool,RunControl c)throws Exception {assertSame(control,c);c.check();}
        public void requirePrepared(String op,String tool,JSONObject args){assertNotNull(op);}
        public JSONObject approve(String tool,JSONObject args,JSONObject capability)throws Exception{return new JSONObject().put("approved",true);}
        public List<Target> discover(SemanticRequest request,RunControl c){return List.of(target);}
        public JSONObject resourceIdentity()throws Exception{return new JSONObject().put("executionMode","main_screen").put("serviceSessionId","original").put("displayId",0);}
        public JSONObject dispatch(SemanticRequest request,Target t,RunControl c,Admission admission)throws Exception {admission.check();effects++;if(loseReceipt)throw new java.io.IOException("lost ACK");return new JSONObject().put("accepted",true);}
        JSONObject discover(SemanticCapabilityPort p)throws Exception {JSONObject found=p.execute(SemanticCapabilityPort.DISCOVER,new JSONObject().put("kind","web").put("url","https://example.org/"),control);
            return new JSONObject().put("discoveryId",found.getString("discoveryId")).put("candidateId",found.getJSONArray("candidates").getJSONObject(0).getString("candidateId"));}
        JSONObject dispatch(SemanticCapabilityPort p,JSONObject args)throws Exception{return p.execute(SemanticCapabilityPort.DISPATCH,new JSONObject(args.toString()).put("_hostOperationId",UUID.randomUUID().toString()),control);}
    }
    @Test public void detachRetainsDiscoveryButClosedPortCannotDispatch()throws Exception {
        Fixture f=new Fixture();SemanticCapabilityPort first=f.port();JSONObject args=f.discover(first);first.close();
        assertFalse(f.dispatch(first,args).getBoolean("accepted"));assertEquals(0,f.effects);
        assertTrue(f.dispatch(f.port(),args).getBoolean("accepted"));assertEquals(1,f.effects);f.session.close();
    }
    @Test public void consumedDiscoveryCannotReplayThroughReplacementPort()throws Exception {
        Fixture f=new Fixture();SemanticCapabilityPort first=f.port();JSONObject args=f.discover(first);assertTrue(f.dispatch(first,args).getBoolean("accepted"));first.close();
        assertEquals("stale_semantic_discovery",f.dispatch(f.port(),args).getString("code"));assertEquals(1,f.effects);f.session.close();
    }
    @Test public void unknownFenceSurvivesPortReconstruction()throws Exception {
        Fixture f=new Fixture();SemanticCapabilityPort first=f.port();f.loseReceipt=true;assertEquals("unknown",f.dispatch(first,f.discover(first)).getString("status"));first.close();
        SemanticCapabilityPort second=f.port();f.loseReceipt=false;assertEquals("unknown",f.dispatch(second,f.discover(second)).getString("status"));assertEquals(1,f.effects);f.session.close();
    }
    @Test public void replacementControlWithSameIdsCannotBorrowRunState()throws Exception {
        Fixture f=new Fixture();RunControl other=new RunControl();other.bindHostRun(TASK,RUN,1);
        assertThrows(SecurityException.class,()->new SemanticCapabilityPort(null,other,f,f,true,f.session));f.session.close();
    }
    @Test public void ttlAndExplicitRunEndStillInvalidateOldDiscovery()throws Exception {
        Fixture f=new Fixture();SemanticCapabilityPort first=f.port();JSONObject args=f.discover(first);first.close();f.clock.addAndGet(SemanticCapabilityPort.DISCOVERY_TTL_MS);
        assertEquals("stale_semantic_discovery",f.dispatch(f.port(),args).getString("code"));assertEquals(0,f.effects);
        f.session.close();assertThrows(SecurityException.class,f::port);
    }
    @Test public void cancellationEndsOnlyThatOriginalSession()throws Exception {
        Fixture f=new Fixture();SemanticCapabilityPort p=f.port();JSONObject args=f.discover(p);f.control.cancel();
        assertTrue(f.session.ended());assertNull(f.session.find(args.getString("discoveryId")));assertThrows(SecurityException.class,f::port);assertEquals(0,f.effects);
    }
}
