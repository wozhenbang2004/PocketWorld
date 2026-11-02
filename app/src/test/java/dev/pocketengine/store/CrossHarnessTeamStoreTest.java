package dev.pocketengine.store;
import android.app.Application;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CrossHarnessTeamStoreTest {
    @Test public void onlySameOriginalMemberMayResumeItsActualSessionAndUnknownCannotBecomeAnchor()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String run=UUID.randomUUID().toString(),first=UUID.randomUUID().toString();store.beginExternal("teams","start",run,new JSONObject().put("goal","mixed harness research"));
        JSONObject binding=new JSONObject().put("worker","claude").put("teamReadOnlyTools",true).put("externalExecutionRiskAcknowledged",true).put("profileId","paired").put("environmentId","workspace");
        JSONObject request=new JSONObject().put("runId",run).put("hostTaskId",run).put("memberId","claude-member").put("round",1).put("generation",0).put("operationId",first);
        store.prepareTeamChild(request,binding);assertTrue(store.prepareTeamChild(request,binding).getBoolean("knownOperation"));
        JSONObject receipt=new JSONObject().put("operationId",first).put("id","first-job").put("profileId","paired").put("environmentId","workspace").put("worker","claude").put("serviceBase","https://computer.example").put("status","completed").put("protocol","claude_stream_json").put("protocolVerified",true).put("sessionIdentityConfirmed",true).put("threadId","session-1").put("sessionId","session-1").put("teamReadOnlyTools",true).put("readOnly",true).put("output","actual member result");
        store.updateExternal(first,receipt);
        JSONObject source=new JSONObject().put("childTaskId",first).put("jobId","first-job").put("operationId",first).put("threadId","session-1").put("serviceBase","https://computer.example").put("profileId","paired").put("environmentId","workspace").put("worker","claude");
        JSONObject next=new JSONObject(request.toString()).put("operationId",UUID.randomUUID().toString()).put("round",2).put("sessionSource",source);
        assertFalse(store.prepareTeamChild(next,binding).getBoolean("knownOperation"));assertEquals(first,store.get(next.getString("operationId"),false).getJSONObject("sessionSource").getString("childTaskId"));
        assertThrows(SecurityException.class,()->store.prepareTeamChild(new JSONObject(next.toString()).put("operationId",UUID.randomUUID().toString()).put("memberId","other-member"),binding));
        JSONObject wrong=new JSONObject(source.toString()).put("threadId","another-session");assertThrows(SecurityException.class,()->store.prepareTeamChild(new JSONObject(next.toString()).put("operationId",UUID.randomUUID().toString()).put("sessionSource",wrong),binding));
        String unknown=UUID.randomUUID().toString();store.prepareTeamChild(new JSONObject(request.toString()).put("operationId",unknown),binding);store.updateExternal(unknown,new JSONObject(receipt.toString()).put("operationId",unknown).put("id","unknown-job").put("status","unknown"));
        JSONObject uncertain=new JSONObject(source.toString()).put("childTaskId",unknown).put("operationId",unknown).put("jobId","unknown-job");assertThrows(SecurityException.class,()->store.prepareTeamChild(new JSONObject(next.toString()).put("operationId",UUID.randomUUID().toString()).put("sessionSource",uncertain),binding));
    }
}
