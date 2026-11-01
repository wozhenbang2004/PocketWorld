package dev.pocketengine.media;

import android.app.Application;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentOutcomeHost;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual TaskStore approval/run/operation/bytes and HTTP; the inert pre-existing capability and provider response are explicit fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class MediaGoalOutcomeJourneyTest {
    @Test public void ownerApprovalProducesOneRealFileThenTheSameGoalContinuesWithoutReplayingTheOldOperation()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app);FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
        MediaModule media=new MediaModule(context);MediaWorkflowHost nativeHost=new MediaWorkflowHost(tasks);AtomicInteger posts=new AtomicInteger();
        String png="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=";HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/images/generations",exchange->{posts.incrementAndGet();JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("actual same-goal image fixture",input.getString("prompt"));byte[] body=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("b64_json",png))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {
            media.handle("configure",new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("imageModel","fixture-image"));
            String task=tasks.createCapabilityTask("查来源并生成图片，再基于原文件继续同一个目标","same-goal").getString("id"),revision=media.snapshot().getString("configurationRevision");JSONObject args=new JSONObject().put("prompt","actual same-goal image fixture").put("configurationRevision",revision);
            JSONObject first=tasks.startAttempt(task,"capabilities",8);RunControl firstControl=control(task,first);tasks.authorizeTools(task,first.getString("runId"),new JSONArray().put("media_generate_image").put("media_read_original"));
            MediaTaskCapabilities firstTools=tools(tasks,media,nativeHost,task,first,firstControl);RunJournal before=tasks.journal(task,first.getString("runId"),first.getInt("coordinatorEpoch"),firstControl);String notSent=before.beginOperation("media_generate_image",args);
            JSONObject pending=firstTools.execute("media_generate_image",new JSONObject(args.toString()).put("_hostOperationId",notSent),firstControl);assertEquals("approval_required",pending.getString("code"));assertFalse(pending.getBoolean("dispatched"));assertEquals(0,posts.get());before.finishOperation(notSent,pending);tasks.endAttempt(task,first.getString("runId"),"awaiting_user","exact native parameters pending",new JSONObject());tasks.decideCapabilityApproval(task,pending.getString("approvalId"),true);
            JSONObject approved=tasks.startAttempt(task,"capabilities",8);RunControl actual=control(task,approved);tasks.authorizeTools(task,approved.getString("runId"),new JSONArray().put("media_generate_image").put("media_read_original"));MediaTaskCapabilities selected=tools(tasks,media,nativeHost,task,approved,actual);RunJournal journal=tasks.journal(task,approved.getString("runId"),approved.getInt("coordinatorEpoch"),actual);String operation=journal.beginOperation("media_generate_image",args);
            JSONObject result=selected.execute("media_generate_image",new JSONObject(args.toString()).put("_hostOperationId",operation),actual);assertEquals("response_received",result.getString("status"));journal.finishOperation(operation,result);tasks.endAttempt(task,approved.getString("runId"),"needs_confirmation","actual file retained",new JSONObject());assertEquals(1,posts.get());assertFalse(nativeHost.ownsTask(task));
            JSONObject continued=tasks.startAttempt(task,"capabilities",8);assertNotEquals(approved.getString("runId"),continued.getString("runId"));String current=continued.getString("runId");
            JSONObject recovered=nativeHost.handleOriginal(media,"getOperation",new JSONObject().put("taskId",task).put("operationId",operation).put("runId",approved.getString("runId")).put("epoch",approved.getInt("coordinatorEpoch")),new RunControl());assertEquals(approved.getString("runId"),recovered.getString("runId"));assertEquals(current,tasks.get(task,false).getString("runId"));assertEquals(1,posts.get());assertEquals(1,tasks.list().length());
            AttachmentOutcomeHost outcomes=new AttachmentOutcomeHost(tasks,AttachmentRepository.get(app),new AttachmentsModule(context),(command,payload,live)->nativeHost.handleOriginal(media,command,payload,live));JSONObject file=outcomes.snapshot(task,new RunControl()).getJSONArray("artifacts").getJSONObject(0);
            assertEquals(result.getJSONArray("attachments").getJSONObject(0).getString("id"),file.getString("id"));assertArrayEquals(java.util.Base64.getDecoder().decode(png),AttachmentRepository.get(app).book.readVerifiedSnapshot(file.getString("id"),file.getString("sha256"),new RunControl()));
        } finally {nativeHost.close();media.close();server.stop(0);}
    }
    private static RunControl control(String task,JSONObject attempt)throws Exception{RunControl value=new RunControl();value.bindHostRun(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));return value;}
    private static MediaTaskCapabilities tools(TaskStore tasks,MediaModule media,MediaWorkflowHost host,String task,JSONObject attempt,RunControl control)throws Exception {
        CapabilityPort inert=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public void validate(String name,JSONObject args){throw new AssertionError("No unrelated capability should validate");}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("No unrelated capability should execute");}};
        return new MediaTaskCapabilities(inert,tasks,media,(command,input,live)->host.handleOriginal(media,command,input,live),task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),control);
    }
}
