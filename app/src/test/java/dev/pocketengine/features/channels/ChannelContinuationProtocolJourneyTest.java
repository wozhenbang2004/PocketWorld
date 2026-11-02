package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import android.app.Application;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Actual signed QQ ingress, production bridge/SQLite/Runner, loopback model and platform HTTP. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ChannelContinuationProtocolJourneyTest {
    // Explicit isolated protocol fixture: this changes only its own keystore precedence,
    // not production TLS/hostname gates or an already cached OkHttp platform selection.
    @Before public void keys(){java.security.Security.insertProviderAt(new java.security.Provider("ContinuationProtocolKeys","1","Synthetic test keystore"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}},1);}
    @After public void closeKeys(){java.security.Security.removeProvider("ContinuationProtocolKeys");}
    private static void waitFor(java.util.concurrent.Callable<Boolean> condition)throws Exception {long end=System.nanoTime()+10_000_000_000L;while(!condition.call()){if(System.nanoTime()>end)fail("Original protocol did not reach its expected result");Thread.sleep(10);}}
    private static String body(String text){return text.startsWith("[完整答复 ")?text.substring(text.indexOf('\n')+1):text;}

    @Test public void signedReplyReturnsToOriginalQuestionTaskAndDuplicateNeverRunsAnotherModelTurn()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()) {
            f.enable("public.answer");f.group=f.groups.ownerCommand("policy.set",new JSONObject().put("id",f.groupId).put("revision",f.group.getLong("revision")).put("enabled",true).put("visibility","members").put("quota",f.group.getJSONObject("quota")).put("grants",f.group.getJSONArray("grants")));
            CopyOnWriteArrayList<JSONObject> modelRequests=new CopyOnWriteArrayList<>();f.model=request->{modelRequests.add(request);return f.modelCalls.get()==1?GroupTaskFlowsTest.tool("group_request_input",new JSONObject().put("question","请补充公开平台").toString()):GroupTaskFlowsTest.message("已按原群补充继续：Android");};
            f.host.setResultSink((request,receipt)->f.groups.publishTaskState(request.requestId));
            f.send("/task --share --tokens=60000 核对公开发布计划",false);JSONObject first=f.task();assertEquals(first.toString(),"awaiting_user",first.getString("status"));String id=first.getString("id"),request=first.getJSONObject("origin").getString("requestId"),question=first.getJSONArray("groupQuestions").getJSONObject(0).getString("questionId");
            assertTrue(f.replies.stream().anyMatch(row->row.optString("content").contains("/reply "+request+" "+question)));
            String command="/reply "+request+" "+question+" Android";f.send(command,false,true,"same-reply");assertEquals("completed",f.task().getString("status"));assertEquals(id,f.task().getString("id"));assertEquals(2,f.task().getInt("coordinatorEpoch"));assertEquals(1,f.tasks.list().length());assertEquals(2,f.modelCalls.get());assertTrue(modelRequests.get(1).toString().contains("Android"));assertTrue(f.replies.stream().anyMatch(row->row.optString("content").contains("已按原群补充继续")));
            f.send(command,false,true,"same-reply");assertEquals(2,f.modelCalls.get());assertEquals(1,f.task().getJSONArray("groupInputs").length());
        }
    }
    @Test public void ordinaryNonStreamingSignedQqAnswerUsesFiveSlotsAndMoreOnlySendsRemainingFrozenPages()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()) {
            String text="原始开头\n"+"中文🙂完整代码\n".repeat(2400)+"```\nTAIL-PROTOCOL-🙂";
            f.context.settings.save(new JSONObject().put("baseUrl",f.base+"/v1").put("provider","openai_chat").put("model","group-flow-model").put("maxOutputTokens",256).put("maxSteps",8));
            f.channels.handle("profile.save",new JSONObject().put("id",f.profile).put("name","Signed long QQ answer").put("provider","qq").put("appId","flow-bot").put("enabled",true).put("autoReply",true));f.model=request->GroupTaskFlowsTest.message(text);
            f.send("请给完整的长答复",false,true,"long-source");try{waitFor(()->f.replies.size()==5);}catch(AssertionError failure){throw new AssertionError("modelCalls="+f.modelCalls.get()+" snapshot="+f.channels.snapshot(),failure);}JSONArray out=f.channels.snapshot().getJSONArray("outbox");String answer=out.getJSONObject(0).getJSONObject("nativeAnswer").getString("answerId");int parts=out.length();assertTrue(parts>5);assertEquals(1,f.modelCalls.get());
            for(int i=0;i<8&&f.replies.size()<parts;i++){String message="more-"+i;int expected=Math.min(parts,f.replies.size()+5);f.send("/more "+answer,false,true,message);waitFor(()->f.replies.size()==expected);}
            StringBuilder all=new StringBuilder();for(JSONObject sent:f.replies){assertTrue(sent.getString("content").length()<=4000);all.append(body(sent.getString("content")));}assertEquals(text,all.toString());assertEquals(parts,f.replies.size());assertEquals(1,f.modelCalls.get());assertEquals(0,f.tasks.list().length());
            f.send("/more "+answer,false,true,"already-complete");Thread.sleep(30);assertEquals(parts,f.replies.size());assertEquals(1,f.modelCalls.get());
        }
    }
}
