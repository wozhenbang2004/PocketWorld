package dev.pocketengine.ui;

import android.os.Handler;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentOutcomeHost;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.features.attachments.creation.DocumentDraft;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import kotlin.Unit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Dropped native callback + reopened original SQLite + fresh no-Bundle coordinator.
 * This is controlled host cold recovery, not an Android OS hard-kill/device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class,qualifiers="w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AttachmentOutcomeColdRecoveryNativeTest {
    private static JSONObject draft(String body)throws Exception{return new JSONObject().put("format","docx").put("name","同名原文档").put("body",body);}
    private static final class QueuedIo implements Executor {
        private final ArrayDeque<Runnable> pending=new ArrayDeque<>();public void execute(Runnable job){pending.addLast(job);}void oneWithoutUi(){assertFalse(pending.isEmpty());pending.removeFirst().run();}boolean empty(){return pending.isEmpty();}
        void drain(){for(int i=0;i<40;i++){if(!pending.isEmpty())pending.removeFirst().run();Shadows.shadowOf(Looper.getMainLooper()).idle();if(pending.isEmpty())return;}throw new AssertionError("Original owner read did not settle");}
    }
    private static AttachmentOutcomeCoordinator flow(ComposeActivity activity,EngineApplication app,Executor io){return new AttachmentOutcomeCoordinator(activity,app,io,new Handler(Looper.getMainLooper()),()->{throw new AssertionError("No group authority in private cold recovery");},id->Unit.INSTANCE,()->Unit.INSTANCE);}
    private static void reopen(EngineApplication app){app.tasks=new TaskStore(app);app.attachmentOutcomes=new AttachmentOutcomeHost(app.tasks,AttachmentRepository.get(app),(AttachmentsModule)app.features.module("attachments"),(a,b,c)->{throw new AssertionError("No media dispatch during cold recovery");});}
    @Test public void completedOriginalWithLostCallbackIsChosenByItsExactActionNotTheNewestName()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();ActivityController<ComposeActivity> screen=null;AttachmentOutcomeCoordinator first=null,cold=null;
        try{
            screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();QueuedIo originalIo=new QueuedIo();first=flow(screen.get(),app,originalIo);first.foreground(true);first.openTask(null);originalIo.drain();first.newDocument();first.createDocument(draft("Original document body"));String original=first.getState().getPendingDocumentTask();assertNotNull(original);
            originalIo.oneWithoutUi();assertEquals("needs_confirmation",app.tasks.get(original,false).getString("status"));assertEquals("completed",app.tasks.get(original,false).getString("executionStatus"));assertEquals(2,AttachmentRepository.get(app).book.list().length());assertEquals(original,first.getState().getPendingDocumentTask()); // callback has not been delivered
            first.detach();first=null;Shadows.shadowOf(Looper.getMainLooper()).idle();
            JSONObject newer=app.features.createDocumentForOwner(draft("Different newer body"),new RunControl(),UUID.randomUUID().toString());assertNotEquals(original,newer.getString("hostTaskId"));assertEquals(4,AttachmentRepository.get(app).book.list().length());
            screen.pause().stop().destroy();screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();reopen(app);QueuedIo coldIo=new QueuedIo();cold=flow(screen.get(),app,coldIo);cold.restore(null);cold.foreground(true);cold.openTask(null);coldIo.drain();
            assertNull(cold.getState().getPendingDocumentTask());assertNull(cold.getState().getDocumentDraft());assertEquals(2,cold.getState().getSnapshot().getJSONObject("documentActions").getJSONArray("actions").length());
            JSONArray actions=cold.getState().getSnapshot().getJSONObject("documentActions").getJSONArray("actions");
            java.util.Set<String> actionIds=new java.util.HashSet<>();for(int i=0;i<actions.length();i++)actionIds.add(actions.getJSONObject(i).getString("taskId"));assertTrue(actionIds.contains(original));assertTrue(actionIds.contains(newer.getString("hostTaskId")));
            cold.openDocumentAction(original);coldIo.drain();assertEquals(original,cold.getState().getTaskId());assertEquals(original,cold.getState().getPendingDocumentTask());assertNull(cold.getState().getDocumentDraft());
            cold.queryDocument();coldIo.drain();assertNull(cold.getState().getPendingDocumentTask());JSONObject actual=cold.getState().getFile();assertNotNull(actual);assertEquals(original,actual.getJSONObject("source").getString("operationId"));assertNotEquals(newer.getString("id"),actual.getString("id"));
            assertEquals(2,app.tasks.list().length());assertEquals(4,AttachmentRepository.get(app).book.list().length());assertEquals(original,app.tasks.get(original,false).getString("runId"));assertEquals(1,app.tasks.get(original,false).getInt("coordinatorEpoch"));
        }finally{if(first!=null)first.detach();if(cold!=null)cold.detach();if(screen!=null)screen.pause().stop().destroy();app.features.close();}
    }
    @Test public void admittedOriginalSurvivesNoBundleAndAnAbsentResultNeverRecreatesItsDraftOrOutput()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();ActivityController<ComposeActivity> screen=null;AttachmentOutcomeCoordinator cold=null;
        try{
            String original=UUID.randomUUID().toString();app.tasks.beginOwnerDocumentForOwner(original,new DocumentDraft(draft("Never regenerate this unknown source")));app.tasks.status(original,"running","Submission was durable before death");reopen(app);
            assertEquals("interrupted",app.tasks.get(original,false).getString("status"));for(int i=0;i<25;i++)app.tasks.createCapabilityTask("Newer unrelated task "+i,"native-page-fixture");screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();QueuedIo io=new QueuedIo();cold=flow(screen.get(),app,io);cold.restore(null);cold.foreground(true);cold.openTask(null);io.drain();assertNull(cold.getState().getPendingDocumentTask());assertEquals(0,cold.getState().getSnapshot().getJSONObject("documentActions").getJSONArray("actions").length());assertTrue(cold.getState().getSnapshot().getJSONObject("documentActions").getBoolean("hasMore"));cold.olderDocumentActions();io.drain();assertEquals(original,cold.getState().getSnapshot().getJSONObject("documentActions").getJSONArray("actions").getJSONObject(0).getString("taskId"));
            cold.openDocumentAction(UUID.randomUUID().toString());assertNull(cold.getState().getTaskId());assertNull(cold.getState().getPendingDocumentTask());assertTrue(io.empty());assertNotNull(cold.getState().getError());
            cold.openDocumentAction(original);io.drain();assertEquals(original,cold.getState().getPendingDocumentTask());assertNull(cold.getState().getDocumentDraft());cold.queryDocument();io.drain();assertEquals(original,cold.getState().getPendingDocumentTask());assertNull(cold.getState().getFile());assertNull(cold.getState().getDocumentDraft());
            cold.createDocument(draft("Replacement must not execute"));cold.newDocument();assertTrue(io.empty());assertEquals(26,app.tasks.list().length());assertEquals(0,AttachmentRepository.get(app).book.list().length());assertEquals("interrupted",app.tasks.get(original,false).getString("status"));assertEquals(original,app.tasks.get(original,false).getString("runId"));assertEquals(0,app.tasks.get(original,false).getJSONArray("operations").length());
        }finally{if(cold!=null)cold.detach();if(screen!=null)screen.pause().stop().destroy();app.features.close();}
    }
}
