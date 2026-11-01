package dev.pocketengine.ui;

import dev.pocketengine.EngineApplication;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual accepted steering input must drive recall in the same native Task/Run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskMemoryRecallIntentNativeTest {
    @Before public void keys(){new TaskMemoryNativeWorkflowTest().keys();}
    @Test public void latestAcceptedOwnerInputRetrievesItsFactInsteadOfTheLongOriginalGoal()throws Exception{
        try(TaskMemoryNativeWorkflowTest.Fixture f=new TaskMemoryNativeWorkflowTest.Fixture()){
            JSONObject relevant=f.memory.handle("save",TaskMemoryNativeWorkflowTest.j("scope",TaskMemoryNativeWorkflowTest.scope(),"record",TaskMemoryNativeWorkflowTest.j("text","DELTA_TICKET accepted requirement source"))).getJSONObject("record");
            f.blockCall=1;JSONObject first=f.send("irrelevant_old_background ".repeat(70));assertTrue(f.blocked.await(4,TimeUnit.SECONDS));
            JSONObject ack=f.assistant.handle("send",TaskMemoryNativeWorkflowTest.j("conversationId",f.conversation,"clientMessageId",UUID.randomUUID().toString(),"message","DELTA_TICKET","mode","steer","taskId",first.getString("taskId"),"runId",first.getString("runId"),"epoch",first.getInt("epoch")));
            assertEquals(first.getString("taskId"),ack.getString("taskId"));f.release.countDown();JSONObject done=f.completed(first);
            assertEquals(2,f.calls.get());assertEquals(first.getString("runId"),done.getString("runId"));
            assertTrue("latest input was sent but its formal reference was never retrieved",f.requests.get(1).toString().contains("accepted requirement source"));
            JSONObject proof=f.command("readTaskMemoryReceipt",f.binding(done));assertTrue(proof.getJSONArray("records").toString().contains(relevant.getString("id")));
        }
    }
}
