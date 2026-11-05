package dev.pocketengine.ui;

import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.automation.AutomationBook;
import dev.pocketengine.features.automation.AutomationModule;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import dev.pocketengine.ui.web.PocketWebBridge;
import dev.pocketengine.ui.web.PocketWebWindowTicket;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Provider;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real Web bridge reviews -> original Controller/App/AutomationModule/Book and matching receipts.
 * Task/Book recovery and controlled out-of-order delivery remain the actual native business paths. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,
        shadows={ReadyApplicationFixture.NoUnrelatedStartup.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ReminderEditorMountedNativeTest {
    private static final class Screen implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final Provider keys=new Provider("ReminderBridge-"+UUID.randomUUID(),1.0,"Explicit test-only AndroidKeyStore") {
            {put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}
        };
        final ActivityController<ComposeActivity> owner=Robolectric.buildActivity(ComposeActivity.class);
        final ComposeActivity activity=owner.get();
        final MediaController media;
        final PocketWorldController controller;
        final AutomationModule module;
        final PocketWebBridge bridge;
        final List<JSONObject> events=new ArrayList<>();
        int sequence;
        Screen()throws Exception {
            assertTrue(Security.insertProviderAt(keys,1)>0); ReadyApplicationFixture.initializeHost(app);
            module=(AutomationModule)app.features.module("automation");assertNotNull(module);
            media=new MediaController(activity,app::publish);
            Field mediaField=ComposeActivity.class.getDeclaredField("media");mediaField.setAccessible(true);mediaField.set(activity,media);
            controller=new PocketWorldController(activity,app);
            Field controllerField=ComposeActivity.class.getDeclaredField("controller");controllerField.setAccessible(true);controllerField.set(activity,controller);
            controller.attach();
            Field visible=PocketWorldController.class.getDeclaredField("voiceForeground");visible.setAccessible(true);visible.setBoolean(controller,true);
            bridge=new PocketWebBridge(controller,event->{events.add(event);return Unit.INSTANCE;});bridge.start();
            command("app.ready",new JSONObject().put("documentId","reminder-owner-document"));enter();
        }
        void advance(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));try{Thread.sleep(5);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new AssertionError(stopped);}}
        void until(String label,BooleanSupplier condition){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(condition.getAsBoolean())return;advance();}throw new AssertionError("Missing "+label+"; "+controller.getState().getModuleErrors());}
        Object invokeOn(Object target,String prefix,Object... args)throws Exception {
            for(Method method:target.getClass().getDeclaredMethods())if((method.getName().equals(prefix)||method.getName().startsWith(prefix+"$"))&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(target,args);}
            throw new AssertionError("Missing original method "+prefix);
        }
        JSONObject reply(String action,JSONObject args)throws Exception {
            String id="reminder-command-"+(++sequence);
            JSONObject request=new JSONObject().put("v",1).put("id",id).put("action",action).put("args",args).put("documentId","reminder-owner-document");
            PocketWebWindowTicket window=(PocketWebWindowTicket)invokeOn(bridge,"captureWindow");
            bridge.postMessage(request.toString(),window,()->{throw new AssertionError("Unexpected stale bridge window");});
            until("original bridge reply "+action,()->events.stream().anyMatch(event->"result".equals(event.optString("kind"))&&id.equals(event.optString("id"))));
            return events.stream().filter(event->"result".equals(event.optString("kind"))&&id.equals(event.optString("id"))).findFirst().orElseThrow();
        }
        JSONObject command(String action,JSONObject args)throws Exception {JSONObject result=reply(action,args);assertTrue(action+": "+result.optJSONObject("error"),result.getBoolean("ok"));return result.optJSONObject("value")==null?new JSONObject():result.getJSONObject("value");}
        void enter()throws Exception {command("connections.enter",new JSONObject().put("section","automation"));until("initial original automation reads",()->!controller.getState().getModuleBusy().contains("automation.list")&&!controller.getState().getModuleBusy().contains("automation.receipts"));assertNull(controller.getState().getModuleErrors().get("automation.list"));}
        JSONObject featureReceipt(String command,JSONObject accepted)throws Exception {
            assertTrue(accepted.getBoolean("accepted"));String request=accepted.getString("requestId"),key="automation."+command;
            until("matching original "+key+" receipt",()->{JSONObject input=controller.getState().getModuleResultInputs().get(key);return !controller.getState().getModuleBusy().contains(key)&&input!=null&&request.equals(input.optString("_requestId"));});
            assertNull(controller.getState().getModuleErrors().get(key));return controller.getState().getModuleResults().get(key);
        }
        JSONObject read(String command)throws Exception {return featureReceipt(command,command("automation."+command,new JSONObject()));}
        JSONObject draft(String type,String title)throws Exception {return new JSONObject().put("title",title).put("phone",false).put("goal","")
            .put("type",type).put("minutes","30").put("time","09:00").put("zone","UTC").put("weekdays",new JSONArray().put(1).put(2).put(3).put(4).put(5))
            .put("monthDay",1).put("lastDay",false).put("skipGap",false).put("laterOverlap",false).put("cron","0 9 * * 1-5")
            .put("preserveSchedule",false).put("misfire","skip").put("overlap","buffer_one").put("maxCatchUp",3);}
        JSONObject review(JSONObject draft,JSONObject original)throws Exception {
            JSONObject args=new JSONObject().put("intent",original==null?"create":"edit").put("draft",draft);
            if(original!=null)args.put("id",original.getString("id")).put("revision",original.getInt("revision"));
            return command("automation.reviewSchedule",args);
        }
        JSONObject confirm(JSONObject reviewed)throws Exception {return featureReceipt("save",command("automation.confirmReview",new JSONObject().put("reviewHandle",reviewed.getString("reviewHandle"))));}
        JSONObject create(JSONObject draft)throws Exception {return confirm(review(draft,null)).getJSONObject("rule");}
        JSONObject visibleRule(String id,int revision)throws Exception {
            final JSONObject[] found={null};until("original rule in owner state",()->{JSONObject automation=controller.getState().getFeatures().optJSONObject("automation");JSONArray rules=automation==null?null:automation.optJSONArray("rules");
                for(int i=0;rules!=null&&i<rules.length();i++){JSONObject row=rules.optJSONObject(i);if(row!=null&&id.equals(row.optString("id"))&&revision==row.optInt("revision")){found[0]=row;return true;}}return false;});return found[0];
        }
        void receiveHeld(JSONObject event)throws Exception {Method receive=PocketWorldController.class.getDeclaredMethod("receive",JSONObject.class);receive.setAccessible(true);receive.invoke(controller,new JSONObject(event.toString()));}
        @Override public void close()throws Exception {
            try{bridge.close();controller.detach();media.close();owner.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
            finally{try{ReadyApplicationFixture.closeHost(app);}finally{Security.removeProvider(keys.getName());}}
        }
    }

    @Test public void allSixModesAreNativeActionsAndIntervalSaveKeepsFutureAnchorAndOriginalDefaults()throws Exception {
        try(Screen ui=new Screen()){
            JSONArray available=ui.module.snapshot().getJSONArray("scheduleTypes");
            for(String type:new String[]{"once","interval","daily","weekly","monthly","cron"}){
                assertTrue(available.toString().contains("\""+type+"\""));JSONObject review=ui.review(ui.draft(type,"Preview "+type),null);
                assertEquals(type,review.getJSONObject("rule").getJSONObject("schedule").getString("type"));assertTrue(review.getJSONObject("preview").getJSONArray("occurrences").length()>0);
                assertFalse(review.getBoolean("executionAuthorized"));assertEquals(0,ui.module.snapshot().getJSONArray("rules").length());
            }
            long before=System.currentTimeMillis();JSONObject review=ui.review(ui.draft("interval","Native interval rule").put("minutes","45"),null);
            JSONObject result=ui.confirm(review),rule=result.getJSONObject("rule"),schedule=rule.getJSONObject("schedule");
            assertEquals("saved",result.getJSONObject("saveReceipt").getString("state"));assertEquals("interval",schedule.getString("type"));assertEquals(2700000L,schedule.getLong("intervalMillis"));
            assertTrue(schedule.getLong("anchorAt")>=before+2700000L);assertTrue(rule.getLong("nextAt")>=schedule.getLong("anchorAt"));
            assertEquals("skip",rule.getJSONObject("policy").getString("misfire"));assertEquals("buffer_one",rule.getJSONObject("policy").getString("overlap"));assertEquals("reminder",rule.getString("kind"));
            assertNull(ui.app.activeId());assertEquals(0,ui.app.tasks.list().length());assertEquals(0,ui.read("receipts").getJSONArray("receipts").length());
        }
    }
    @Test public void monthlyOwnerSelectionUsesOriginalShortMonthPolicyRatherThanAParallelSchedule()throws Exception {
        try(Screen ui=new Screen()){
            JSONObject reviewed=ui.review(ui.draft("monthly","Native last-day rule").put("monthDay",31).put("lastDay",true),null);
            assertTrue(reviewed.getJSONObject("preview").getJSONArray("occurrences").length()>0);
            JSONObject schedule=ui.confirm(reviewed).getJSONObject("rule").getJSONObject("schedule");
            assertEquals("monthly",schedule.getString("type"));assertEquals(31,schedule.getInt("dayOfMonth"));assertEquals("last_day",schedule.getString("shortMonthPolicy"));assertFalse(schedule.has("intervalMillis"));assertNull(ui.app.activeId());
        }
    }
    @Test public void invalidCalendarCannotSaveAndOwnerCancelDoesNotCreateAnyRule()throws Exception {
        try(Screen ui=new Screen()){
            assertFalse(ui.reply("automation.reviewSchedule",new JSONObject().put("intent","create").put("draft",ui.draft("monthly","Cancelled invalid rule").put("monthDay",0))).getBoolean("ok"));
            assertEquals(0,ui.module.snapshot().getJSONArray("rules").length());
            JSONObject valid=ui.review(ui.draft("once","Cancelled valid review"),null);
            ui.command("connections.leave",new JSONObject().put("section","automation"));ui.enter();
            assertFalse(ui.reply("automation.confirmReview",new JSONObject().put("reviewHandle",valid.getString("reviewHandle"))).getBoolean("ok"));
            assertEquals(0,ui.module.snapshot().getJSONArray("rules").length());assertEquals(0,ui.app.tasks.list().length());assertNull(ui.app.activeId());
        }
    }
    @Test public void ownerCronPreviewSaveEditAndReadbackUseOneOriginalRuleAndNoTask()throws Exception {
        try(Screen ui=new Screen()){
            JSONObject reviewed=ui.review(ui.draft("cron","Native cron rule"),null);assertTrue(reviewed.getJSONObject("preview").getJSONArray("occurrences").length()>0);
            JSONObject rule=ui.confirm(reviewed).getJSONObject("rule");String id=rule.getString("id");
            assertEquals("cron",rule.getJSONObject("schedule").getString("type"));assertEquals(1,rule.getJSONObject("schedule").getInt("scheduleVersion"));assertEquals(1,ui.visibleRule(id,1).getInt("revision"));assertNull(ui.app.activeId());
            JSONObject changed=ui.confirm(ui.review(ui.draft("cron","Edited native cron rule").put("preserveSchedule",true),rule)).getJSONObject("rule");
            assertEquals(id,changed.getString("id"));assertEquals(2,changed.getInt("revision"));assertEquals(rule.getJSONObject("schedule").toString(),changed.getJSONObject("schedule").toString());
            assertEquals(1,ui.read("list").getJSONArray("rules").length());assertEquals("Edited native cron rule",ui.visibleRule(id,2).getString("title"));
            assertEquals(0,ui.read("receipts").getJSONArray("receipts").length());assertNull(ui.app.activeId());assertFalse(ui.module.snapshot().getBoolean("autonomousPhoneExecution"));
            assertFalse(ui.reply("automation.confirmReview",new JSONObject().put("reviewHandle",reviewed.getString("reviewHandle"))).getBoolean("ok"));assertEquals(1,ui.module.snapshot().getJSONArray("rules").length());
        }
    }
    @Test public void invalidCronAndAnOldEditorCannotReplaceTheOriginalRule()throws Exception {
        try(Screen ui=new Screen()){
            assertFalse(ui.reply("automation.reviewSchedule",new JSONObject().put("intent","create").put("draft",ui.draft("cron","Invalid cron").put("cron","0 9 1 * 1"))).getBoolean("ok"));
            assertEquals(0,ui.module.snapshot().getJSONArray("rules").length());
            JSONObject rule=ui.create(ui.draft("cron","Original native rule").put("cron","0 9 * * *"));
            JSONObject stale=ui.review(ui.draft("cron","Stale native edit").put("preserveSchedule",true),rule);
            JSONObject changed=ui.module.handle("save",new JSONObject().put("rule",new JSONObject(rule.toString()).put("title","Newer native rule"))).getJSONObject("rule");ui.read("list");
            assertFalse(ui.reply("automation.confirmReview",new JSONObject().put("reviewHandle",stale.getString("reviewHandle"))).getBoolean("ok"));
            assertEquals("Newer native rule",ui.module.snapshot().getJSONArray("rules").getJSONObject(0).getString("title"));assertEquals(changed.getInt("revision"),ui.module.snapshot().getJSONArray("rules").getJSONObject(0).getInt("revision"));assertNull(ui.app.activeId());
        }
    }
    @Test public void eventFailureAfterOriginalBookSaveReturnsTheSameDurableIdentityForReadOnlyRecovery()throws Exception {
        try(Screen ui=new Screen()){
            java.util.concurrent.atomic.AtomicInteger publications=new java.util.concurrent.atomic.AtomicInteger();
            // Fault only the original native publisher; the mounted module/Book/atomic writer,
            // AlarmManager, bridge request, Controller result and subsequent reads stay real.
            Field contextField=AutomationModule.class.getDeclaredField("context");contextField.setAccessible(true);
            FeatureContext context=(FeatureContext)contextField.get(ui.module);Field eventsField=FeatureContext.class.getDeclaredField("events");eventsField.setAccessible(true);
            EventSink original=(EventSink)eventsField.get(context);JSONObject result;
            try{
                eventsField.set(context,(EventSink)event->{if(event.optJSONObject("payload")!=null&&"automation_changed".equals(event.optJSONObject("payload").optString("event"))){publications.incrementAndGet();throw new IllegalStateException("fixture publication fault");}original.emit(event);});
                result=ui.confirm(ui.review(ui.draft("cron","Saved before publication fault").put("cron","0 9 * * *"),null));
            }finally{eventsField.set(context,original);}
            JSONObject receipt=result.getJSONObject("saveReceipt");String id=receipt.getString("ruleId");
            assertEquals("saved",receipt.getString("state"));assertEquals(1,receipt.getInt("ruleRevision"));assertEquals("unconfirmed",receipt.getString("changePublication"));assertEquals("same_revision",receipt.getString("ruleReadback"));assertEquals("not_applicable",receipt.getString("sourceState"));assertFalse(receipt.getBoolean("executionAuthorized"));assertEquals(1,publications.get());assertEquals(id,result.getJSONObject("rule").getString("id"));
            JSONObject prior=ui.module.snapshot();JSONObject readback=ui.read("list");assertEquals(prior.getString("observationGeneration"),readback.getString("observationGeneration"));assertTrue(readback.getLong("observationSequence")>prior.getLong("observationSequence"));
            JSONArray rules=readback.getJSONArray("rules");assertEquals(1,rules.length());assertEquals(id,rules.getJSONObject(0).getString("id"));assertEquals(1,rules.getJSONObject(0).getInt("revision"));assertEquals(id,ui.visibleRule(id,1).getString("id"));assertEquals(0,ui.read("receipts").getJSONArray("receipts").length());assertNull(ui.app.activeId());
        }
    }
    @Test public void cronColdUnknownOccurrenceOnlyFindsOneOriginalSqlTaskAndNeverStartsIt()throws Exception {
        try(Screen ui=new Screen()){
            long now=System.currentTimeMillis(),planned=now-now%60000L;assertTrue(planned>0);
            java.io.File file=new java.io.File(ui.app.getFilesDir(),"automation/rules.json");AutomationBook original=new AutomationBook(file);
            JSONObject rule=original.save(new JSONObject().put("title","Original cron occurrence").put("kind","phone_goal").put("goal","仅由主人审阅的原手机目标").put("schedule",new JSONObject().put("type","cron").put("scheduleVersion",1).put("expression","* * * * *").put("zone","UTC")),planned-1);
            JSONObject claim=original.claimDue(now,10).getJSONObject(0);String occurrence=claim.getString("id");assertEquals(planned,claim.getLong("occurrenceAt"));AutomationBook cold=new AutomationBook(file);cold.recoverInterrupted(now);assertEquals("delivery_unknown",cold.receipts().getJSONObject(0).getString("status"));
            JSONArray shown=ui.read("receipts").getJSONArray("receipts");assertEquals(occurrence,shown.getJSONObject(0).getString("id"));assertEquals("delivery_unknown",shown.getJSONObject(0).getString("status"));
            assertFalse(ui.reply("automation.prepareOccurrence",new JSONObject().put("occurrenceId",occurrence)).getBoolean("ok"));assertEquals(0,ui.app.tasks.list().length());
            // Preserve the original cold-recovery assertion: explicit owner App admission only
            // creates/finds an inert task, independent of the Web page's unknown-delivery action.
            JSONObject first=ui.app.dispatchOwnerAction("prepareReminderTask",new JSONObject().put("occurrenceId",occurrence).put("executionMode","background")).getJSONObject("task");assertEquals("preparing",first.getString("status"));assertEquals(0,first.getInt("coordinatorEpoch"));assertTrue(first.getBoolean("created"));
            ui.app.tasks.status(first.getString("id"),"reconciling","fixture: original task still needs reconciliation");new AutomationBook(file).recoverInterrupted(now+1);
            JSONObject repeated=ui.app.dispatchOwnerAction("prepareReminderTask",new JSONObject().put("occurrenceId",occurrence).put("executionMode","background")).getJSONObject("task");assertEquals(first.getString("id"),repeated.getString("id"));assertFalse(repeated.getBoolean("created"));assertEquals("reconciling",repeated.getString("status"));assertEquals(0,repeated.getInt("coordinatorEpoch"));
            ui.controller.prepareReminderOccurrence(occurrence);ui.until("same original task returned by Controller",()->first.optString("id").equals(ui.controller.getState().getRequestedTaskId()));
            assertEquals(1,ui.app.tasks.list().length());assertEquals(first.getString("id"),ui.app.tasks.automationTask(occurrence).getString("id"));assertEquals(0,cold.claimDue(now,10).length());assertEquals(1,cold.rules().getJSONObject(0).getLong("claimedCount"));assertEquals(rule.getString("id"),repeated.getString("automationRuleId"));assertNull(ui.app.activeId());
        }
    }
    @Test public void delayedOriginalReadbacksCannotReplaceANewerRevisionOrRestoreADeletedRule()throws Exception {
        try(Screen ui=new Screen()){
            JSONObject first=ui.create(ui.draft("cron","Before delayed readback").put("cron","0 9 * * *"));String id=first.getString("id");ui.visibleRule(id,1);
            JSONObject oldSnapshot=ui.module.snapshot(),oldFeatures=new JSONObject(ui.controller.getState().getFeatures().toString()).put("automation",oldSnapshot);
            java.util.concurrent.atomic.AtomicReference<JSONObject> held=new java.util.concurrent.atomic.AtomicReference<>();
            EngineApplication.Observer capture=event->{if("feature_result".equals(event.optString("type"))&&"automation".equals(event.optString("feature"))&&"list".equals(event.optString("command")))held.set(event);};
            String request;
            ui.app.addObserver(capture);
            try{
                request=ui.controller.requestFeature("automation","list",new JSONObject());
                // App publication posts the Controller receive separately. Stop after capturing
                // the real reply, before its queued receive runs; no fabricated pending entry.
                for(int i=0;i<1000&&held.get()==null;i++){Shadows.shadowOf(Looper.getMainLooper()).runOneTask();Thread.sleep(5);}
            }finally{ui.app.removeObserver(capture);}
            assertNotNull("Actual original list reply",held.get());JSONObject oldListReply=new JSONObject(held.get().toString());assertEquals(request,oldListReply.getString("requestId"));assertTrue(oldListReply.getBoolean("ok"));assertTrue(ui.controller.getState().getModuleBusy().contains("automation.list"));assertEquals(id,oldListReply.getJSONObject("result").getJSONArray("rules").getJSONObject(0).getString("id"));
            JSONObject edited=ui.module.handle("save",new JSONObject().put("rule",new JSONObject(first.toString()).put("title","Current revision"))).getJSONObject("rule"),newerSnapshot=ui.module.snapshot();assertEquals(2,edited.getInt("revision"));assertTrue(newerSnapshot.getLong("observationSequence")>oldListReply.getJSONObject("result").getLong("observationSequence"));
            // Controlled transport reorder through the actual private receiver. Every snapshot
            // and the delayed matching-request result came from the original native module.
            ui.receiveHeld(new JSONObject().put("type","feature_event").put("feature","automation").put("payload",new JSONObject().put("event","automation_changed").put("snapshot",newerSnapshot)));
            ui.receiveHeld(new JSONObject().put("type","feature_event").put("feature","automation").put("payload",new JSONObject().put("event","automation_changed").put("snapshot",oldSnapshot)));
            assertEquals(2,ui.controller.getState().getFeatures().getJSONObject("automation").getJSONArray("rules").getJSONObject(0).getInt("revision"));
            ui.module.handle("delete",new JSONObject().put("id",id).put("revision",2));JSONObject deleted=ui.module.snapshot();assertEquals(0,deleted.getJSONArray("rules").length());
            ui.receiveHeld(new JSONObject().put("type","features_state").put("features",new JSONObject(ui.controller.getState().getFeatures().toString()).put("automation",deleted)));
            ui.receiveHeld(new JSONObject().put("type","features_state").put("features",oldFeatures));
            ui.receiveHeld(new JSONObject().put("type","feature_event").put("feature","automation").put("payload",new JSONObject().put("event","automation_changed").put("snapshot",newerSnapshot)));
            ui.receiveHeld(oldListReply);JSONObject retained=ui.controller.getState().getFeatures().getJSONObject("automation");assertEquals(0,retained.getJSONArray("rules").length());assertEquals(deleted.getString("observationGeneration"),retained.getString("observationGeneration"));assertEquals(deleted.getLong("observationSequence"),retained.getLong("observationSequence"));
            ui.advance();assertEquals(0,ui.controller.getState().getFeatures().getJSONObject("automation").getJSONArray("rules").length());assertEquals(0,ui.module.snapshot().getJSONArray("rules").length());assertEquals(0,ui.app.tasks.list().length());assertNull(ui.app.activeId());
        }
    }
}
