package dev.pocketengine.features.groups;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.groups.host.GroupArtifactStore;
import dev.pocketengine.features.groups.host.GroupMediaService;
import dev.pocketengine.media.MediaModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real SQLite, HTTP, existing MediaJobs/imports and group files; native member/owner authority is an explicit fixture. No QQ or live model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class GroupOutcomeRecoveryJourneyTest {
    @Test public void approvedOriginalJobSurvivesRestartAndFetchesOneCanonicalGroupFileWithoutReAdmission()throws Exception {
        Application app=RuntimeEnvironment.getApplication();AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger(),downloads=new AtomicInteger();AtomicReference<JSONObject> submitted=new AtomicReference<>();
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/prompt",exchange->{posts.incrementAndGet();JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));submitted.set(body);respond(exchange,new JSONObject().put("prompt_id",body.getString("prompt_id")));});
        server.createContext("/history",exchange->{gets.incrementAndGet();JSONObject body=submitted.get();respond(exchange,new JSONObject().put(body.getString("prompt_id"),new JSONObject().put("status",new JSONObject().put("completed",true).put("status_str","success")).put("outputs",new JSONObject().put("9",new JSONObject().put("images",new JSONArray().put(new JSONObject().put("filename","fixture.png").put("subfolder","").put("type","output")))))));});
        server.createContext("/view",exchange->{downloads.incrementAndGet();exchange.sendResponseHeaders(200,png.length);exchange.getResponseBody().write(png);exchange.close();});server.start();
        MediaModule media=module(app);MediaModule recovered=null;
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();JSONObject workflow=new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text","explicit protocol fixture")));
            media.handle("configure",new JSONObject().put("provider","comfyui").put("baseUrl",base).put("workflow",workflow).put("promptNodeId","6").put("promptInput","text"));
            TaskStore tasks=new TaskStore(app);JSONObject row=GroupTaskStoreTest.row("media.image",8000).put("resource","media:"+media.snapshot().getString("configurationRevision"));GroupHostPort.Request request=new GroupHostPort.Request(row);
            GroupHostPort.Authority execution=()->{};GroupHostPort.Authority ownerRead=new GroupHostPort.Authority(){public void check(){throw new SecurityException("read is not execution authority");}public void checkRead(){}};
            tasks.registerGroupTask(request,execution);JSONObject attempt=tasks.startGroupAttempt(request,execution,8);String oldRun=attempt.getString("runId");int oldEpoch=attempt.getInt("coordinatorEpoch");RunControl live=new RunControl();live.bindHostRun(request.taskId,oldRun,oldEpoch);
            RunJournal journal=tasks.groupJournal(request,oldRun,oldEpoch,live,execution);JSONObject args=new JSONObject().put("prompt","make the original group fixture");String operation=journal.beginOperation("group_media_image",args);
            File groupDir=new File(app.getFilesDir(),"group-outcome-journey");GroupArtifactStore files=new GroupArtifactStore(groupDir);GroupMediaService service=new GroupMediaService(media,AttachmentRepository.get(app).book,files,tasks);
            final MediaModule first=media;assertThrows(SecurityException.class,()->service.execute(request,oldRun,oldEpoch,operation,args.getString("prompt"),live,execution));assertEquals(0,posts.get());
            JSONObject capability=new JSONObject().put("kind","native_group_media").put("requestId",request.requestId).put("spaceId",request.spaceId).put("actorId",request.actorId).put("capability",request.capability).put("resource",request.resource).put("policyEpoch",request.policyEpoch).put("audience",request.audience);
            JSONObject approval=tasks.requireCapabilityApproval(request.taskId,oldRun,"group_media_image",args,capability);tasks.decideCapabilityApproval(request.taskId,approval.getString("approvalId"),true);assertTrue(tasks.requireCapabilityApproval(request.taskId,oldRun,"group_media_image",args,capability).getBoolean("approved"));
            JSONObject queued=service.execute(request,oldRun,oldEpoch,operation,args.getString("prompt"),live,execution);journal.finishOperation(operation,queued);assertEquals("queued",queued.getString("status"));assertEquals(1,posts.get());
            tasks.endAttempt(request.taskId,oldRun,"paused","native fixture boundary",new JSONObject());assertThrows(SecurityException.class,()->tasks.startGroupAttempt(request,execution,8));media.close();
            TaskStore reopened=new TaskStore(app);recovered=module(app);GroupArtifactStore reopenedFiles=new GroupArtifactStore(groupDir);GroupMediaService restored=new GroupMediaService(recovered,AttachmentRepository.get(app).book,reopenedFiles,reopened);
            JSONObject result=restored.refreshOriginal(request,operation,"fetchResults",new RunControl(),ownerRead);assertEquals("response_received",result.getString("status"));String artifact=result.getJSONArray("groupArtifacts").getJSONObject(0).getString("artifactId");
            assertArrayEquals(png,reopenedFiles.readOutputSnapshot(request,artifact,ownerRead,new RunControl()).copyBytes());assertEquals(request.spaceId,reopenedFiles.readOutputMetadata(request,artifact,ownerRead,new RunControl()).getString("spaceId"));
            assertEquals(oldRun,reopened.get(request.taskId,false).getString("runId"));assertEquals(oldRun,reopened.groupMediaOperationSource(request,operation,ownerRead).getString("runId"));
            JSONObject again=restored.refreshOriginal(request,operation,"fetchResults",new RunControl(),ownerRead);assertEquals(artifact,again.getJSONArray("groupArtifacts").getJSONObject(0).getString("artifactId"));assertEquals(1,posts.get());assertEquals(1,downloads.get());
            int previousGets=gets.get();recovered.handle("configure",new JSONObject().put("provider","comfyui").put("baseUrl",base).put("workflow",new JSONObject(workflow.toString()).put("7",new JSONObject().put("class_type","FixtureNode").put("inputs",new JSONObject()))).put("promptNodeId","6").put("promptInput","text"));
            assertThrows(SecurityException.class,()->restored.refreshOriginal(request,operation,"getJob",new RunControl(),ownerRead));assertEquals(previousGets,gets.get());assertEquals(1,posts.get());
        } finally {media.close();if(recovered!=null)recovered.close();server.stop(0);}
    }
    private static MediaModule module(Application app){return new MediaModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));}
    private static void respond(HttpServer.Exchange exchange,JSONObject body)throws Exception{byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
}
