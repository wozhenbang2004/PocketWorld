package dev.pocketengine.features.memory;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.KnowledgeLearningWorkflow;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
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
public final class MemorySendAdmissionTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject save(MemoryModule memory,String text)throws Exception{return memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text",text))).getJSONObject("record");}
    private interface Change {void run()throws Exception;}
    private void physical(InferenceCapture.Admission gate,Change mutation,boolean afterStarted,boolean allowed)throws Exception {
        AtomicInteger http=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{http.incrementAndGet();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"null\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{
            ModelConfig model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"","test",false,8,10,"openai_chat",256);
            InferenceCapture.Target target=InferenceCapture.withAdmission(InferenceCapture.observe(InferenceCapture.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r)throws Exception{if(afterStarted)mutation.run();}public void finished(InferenceReceipt r){}}),gate);
            if(!afterStarted)mutation.run();
            try{ModelGateway.complete(model,new JSONArray().put(new JSONObject().put("role","user").put("content","original frozen input")),null,new RunControl(),target);assertTrue("stale input reached HTTP",allowed);}catch(Exception denied){if(allowed)throw denied;}
            assertEquals(allowed?1:0,http.get());
        }finally{server.stop(0);}
    }
    @Test public void exactSourceBodyAndRetirementAreRecheckedBeforeAndAfterStarted()throws Exception {
        MemoryModule memory=new MemoryModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));SourceRef expected=new SourceRef("message","original",1,"owner","private");
        for(boolean after:new boolean[]{false,true})for(boolean retired:new boolean[]{false,true}){
            KnowledgeLearningWorkflow.SourceDocument[] current={new KnowledgeLearningWorkflow.SourceDocument(expected,"Original","old body")};
            InferenceCapture.Admission gate=LearningInputAdmission.source(memory,ref->current[0],scope(),expected,new JSONObject().put("title","Original").put("text","old body"));
            physical(gate,()->current[0]=retired?null:new KnowledgeLearningWorkflow.SourceDocument(expected,"Original","changed body"),after,false);
        }
    }
    @Test public void exactParentsForgetAndUnrelatedAppendHaveDifferentPhysicalOutcomes()throws Exception {
        for(boolean after:new boolean[]{false,true}){
            MemoryModule memory=new MemoryModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));JSONObject a=save(memory,"parent a "+after),b=save(memory,"parent b "+after);JSONArray selected=new JSONArray().put(new JSONObject().put("id",a.getString("id")).put("revision",a.getLong("revision"))).put(new JSONObject().put("id",b.getString("id")).put("revision",b.getLong("revision")));
            InferenceCapture.Admission gate=LearningInputAdmission.consolidation(memory,scope(),selected,memory.consolidationSnapshot(scope(),selected));
            physical(gate,()->save(memory,"unrelated append"),after,true);
            physical(gate,()->memory.handle("forget",new JSONObject().put("scope",scope()).put("id",a.getString("id"))),after,false);
        }
    }
}
