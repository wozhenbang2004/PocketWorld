package dev.pocketengine.ui;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import androidx.activity.ComponentActivity;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.platform.ComposeView;
import androidx.compose.ui.semantics.SemanticsActions;
import androidx.compose.ui.semantics.SemanticsNode;
import androidx.compose.ui.semantics.SemanticsOwner;
import androidx.compose.ui.semantics.SemanticsProperties;
import androidx.compose.ui.text.AnnotatedString;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.automation.AutomationBook;
import dev.pocketengine.features.automation.AutomationModule;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
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
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original editor semantics -> original Controller/App/AutomationModule/Book.
 * No screenshots, alternate backend, system alarm timing, device or visual acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,qualifiers="w400dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ReminderEditorMountedNativeTest {
    public static final class FixtureActivity extends ComponentActivity { }
    private static final class Screen implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);
        final ActivityController<FixtureActivity> owner=Robolectric.buildActivity(FixtureActivity.class).setup().visible();
        final FixtureActivity activity=owner.get();final AutomationModule module=(AutomationModule)app.features.module("automation");
        final int width=Math.round(400*activity.getResources().getDisplayMetrics().density),height=Math.round(1000*activity.getResources().getDisplayMetrics().density);
        boolean closed; ComposeView view;
        Screen()throws Exception {
            // Popup location polling must advance by real virtual frame intervals, not unbounded zero-delay vsync.
            org.robolectric.shadows.ShadowChoreographer.setPaused(true);
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
            controller.attach();openEditor(null);
        }
        void openEditor(JSONObject original)throws Exception {
            if(view!=null)view.disposeComposition();closed=false;
            Method editor=NativeRemindersKt.class.getDeclaredMethod("ReminderEditor",PocketUiState.class,PocketWorldController.class,JSONObject.class,Function0.class,Composer.class,int.class);editor.setAccessible(true);
            view=new ComposeView(activity);view.setContent(new Function2<Composer,Integer,Unit>(){public Unit invoke(Composer composer,Integer flags){try{editor.invoke(null,controller.getState(),controller,original,(Function0<Unit>)()->{closed=true;return Unit.INSTANCE;},composer,0);return Unit.INSTANCE;}catch(Exception failure){throw new IllegalStateException(failure);}}});activity.setContentView(view);advance();
        }
        void advance()throws Exception {for(int i=0;i<6;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));for(View root:WindowInspector.getGlobalWindowViews()){if(!root.isAttachedToWindow())continue;root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);root.invalidate();}Thread.sleep(5);}}
        private View compose(View view){if(view.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView"))return view;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View match=compose(group.getChildAt(i));if(match!=null)return match;}return null;}
        private void collect(SemanticsNode node,List<SemanticsNode> result){result.add(node);for(SemanticsNode child:node.getChildren())collect(child,result);}
        List<SemanticsNode> nodes()throws Exception{List<SemanticsNode> result=new ArrayList<>();for(View root:WindowInspector.getGlobalWindowViews()){if(!root.isAttachedToWindow()||root.getVisibility()!=View.VISIBLE)continue;View composed=compose(root);if(composed!=null)collect(((SemanticsOwner)composed.getClass().getMethod("getSemanticsOwner").invoke(composed)).getRootSemanticsNode(),result);}return result;}
        String text(SemanticsNode node){var key=SemanticsProperties.INSTANCE.getText();if(!node.getConfig().contains(key))return "";StringBuilder result=new StringBuilder();for(AnnotatedString value:node.getConfig().get(key)){if(result.length()>0)result.append(' ');result.append(value.getText());}return result.toString();}
        boolean has(String label)throws Exception{for(var node:nodes())if(text(node).contains(label))return true;return false;}
        void click(String label)throws Exception {for(var node:nodes()){var key=SemanticsActions.INSTANCE.getOnClick();if(!text(node).equals(label)||!node.getConfig().contains(key))continue;assertFalse("Disabled native action cannot be invoked by this fixture",node.getConfig().contains(SemanticsProperties.INSTANCE.getDisabled()));var action=node.getConfig().get(key);if(action.getAction()==null)continue;assertEquals(Boolean.TRUE,((Function0<?>)action.getAction()).invoke());advance();return;}throw new AssertionError("No click "+label+" in "+nodes().stream().map(this::text).toList());}
        void edit(String previous,String value)throws Exception {for(var node:nodes()){var key=SemanticsActions.INSTANCE.getSetText();var editable=SemanticsProperties.INSTANCE.getEditableText();if(!node.getConfig().contains(key)||!node.getConfig().contains(editable)||!previous.equals(node.getConfig().get(editable).getText()))continue;assertEquals(Boolean.TRUE,((Function1<AnnotatedString,?>)node.getConfig().get(key).getAction()).invoke(new AnnotatedString(value,java.util.Collections.emptyList(),java.util.Collections.emptyList())));advance();return;}throw new AssertionError("No editable native value "+previous);}
        void toggleAfter(String label)throws Exception {
            boolean labelled=false;
            for(var node:nodes()){
                if(text(node).equals(label))labelled=true;
                var state=SemanticsProperties.INSTANCE.getToggleableState();var click=SemanticsActions.INSTANCE.getOnClick();
                if(labelled&&node.getConfig().contains(state)&&node.getConfig().contains(click)){
                    assertEquals(Boolean.TRUE,((Function0<?>)node.getConfig().get(click).getAction()).invoke());advance();return;
                }
            }
            throw new AssertionError("No labelled native switch: "+label);
        }
        JSONObject saved(String title)throws Exception {for(int i=0;i<60;i++){advance();JSONArray rules=module.snapshot().getJSONArray("rules");for(int j=0;j<rules.length();j++)if(title.equals(rules.getJSONObject(j).getString("title")))return rules.getJSONObject(j);}throw new AssertionError("No saved original rule: "+controller.getState().getModuleErrors());}
        JSONObject visibleRule(String id,int revision)throws Exception {for(int i=0;i<60;i++){advance();JSONObject automation=controller.getState().getFeatures().optJSONObject("automation");JSONArray rules=automation==null?null:automation.optJSONArray("rules");for(int j=0;rules!=null&&j<rules.length();j++){JSONObject row=rules.getJSONObject(j);if(id.equals(row.optString("id"))&&revision==row.optInt("revision"))return row;}}throw new AssertionError("Original rule was not read back into native owner state");}
        void receiveHeld(JSONObject event)throws Exception {Method receive=PocketWorldController.class.getDeclaredMethod("receive",JSONObject.class);receive.setAccessible(true);receive.invoke(controller,new JSONObject(event.toString()));}
        public void close()throws Exception {if(view!=null)view.disposeComposition();controller.detach();owner.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();app.onTerminate();app.features.close();}
    }
    @Test public void allSixModesAreNativeActionsAndIntervalSaveKeepsFutureAnchorAndOriginalDefaults()throws Exception {
        try(Screen ui=new Screen()){
            ui.click("时间安排：一次 ▾");for(String type:new String[]{"一次","固定间隔","每天","每周","每月","Cron 表达式"})assertTrue(type,ui.has(type));ui.click("固定间隔");assertTrue("Interval selected: "+ui.nodes().stream().map(ui::text).toList(),ui.has("首次约"));ui.edit("","Native interval rule");ui.edit("30","45");long before=System.currentTimeMillis();ui.click("创建提醒");JSONObject rule=ui.saved("Native interval rule"),schedule=rule.getJSONObject("schedule");assertEquals("interval",schedule.getString("type"));assertEquals(2700000L,schedule.getLong("intervalMillis"));assertTrue(schedule.getLong("anchorAt")>=before+2700000L);assertTrue(rule.getLong("nextAt")>=schedule.getLong("anchorAt"));assertEquals("skip",rule.getJSONObject("policy").getString("misfire"));assertEquals("buffer_one",rule.getJSONObject("policy").getString("overlap"));assertEquals("reminder",rule.getString("kind"));assertNull(ui.app.activeId());assertEquals(0,ui.module.handle("receipts",new JSONObject()).getJSONArray("receipts").length());
        }
    }
    @Test public void monthlyOwnerSelectionUsesOriginalShortMonthPolicyRatherThanAParallelSchedule()throws Exception {
        try(Screen ui=new Screen()){
            ui.click("时间安排：一次 ▾");ui.click("每月");assertTrue("Monthly selected: "+ui.nodes().stream().map(ui::text).toList(),ui.has("短月使用最后一天"));ui.edit("","Native last-day rule");ui.edit("1","31");ui.toggleAfter("短月使用最后一天");assertTrue("Last day selected: "+ui.nodes().stream().map(ui::text).toList(),ui.has("使用2月最后一天"));ui.click("创建提醒");JSONObject rule=ui.saved("Native last-day rule"),schedule=rule.getJSONObject("schedule");assertEquals("monthly",schedule.getString("type"));assertEquals(31,schedule.getInt("dayOfMonth"));assertEquals("last_day",schedule.getString("shortMonthPolicy"));assertFalse(schedule.has("intervalMillis"));assertNull(ui.app.activeId());
        }
    }
    @Test public void invalidCalendarCannotSaveAndOwnerCancelDoesNotCreateAnyRule()throws Exception {
        try(Screen ui=new Screen()){
            ui.click("时间安排：一次 ▾");ui.click("每月");ui.edit("","Cancelled invalid rule");ui.edit("1","0");
            boolean disabled=false;
            for(var node:ui.nodes())if(ui.text(node).equals("创建提醒")&&node.getConfig().contains(SemanticsProperties.INSTANCE.getDisabled()))disabled=true;
            assertTrue("Invalid native draft keeps create disabled",disabled);
            assertEquals(0,ui.module.snapshot().getJSONArray("rules").length());ui.click("取消");assertTrue(ui.closed);
            assertEquals(0,ui.module.snapshot().getJSONArray("rules").length());assertNull(ui.app.activeId());
        }
    }
    @Test public void ownerCronPreviewSaveEditAndReadbackUseOneOriginalRuleAndNoTask()throws Exception {
        try(Screen ui=new Screen()){
            ui.click("时间安排：一次 ▾");ui.click("Cron 表达式");assertTrue(ui.has("日与周至少一项"));ui.edit("","Native cron rule");assertTrue(ui.has("预计："));ui.click("创建提醒");JSONObject rule=ui.saved("Native cron rule");String id=rule.getString("id");
            assertEquals("cron",rule.getJSONObject("schedule").getString("type"));assertEquals(1,rule.getJSONObject("schedule").getInt("scheduleVersion"));assertEquals(1,ui.visibleRule(id,1).getInt("revision"));assertNull(ui.app.activeId());
            ui.openEditor(new JSONObject(rule.toString()));ui.edit("Native cron rule","Edited native cron rule");ui.click("保存修改");JSONObject changed=ui.saved("Edited native cron rule");assertEquals(id,changed.getString("id"));assertEquals(2,changed.getInt("revision"));assertEquals(rule.getJSONObject("schedule").toString(),changed.getJSONObject("schedule").toString());assertEquals(1,ui.module.snapshot().getJSONArray("rules").length());assertEquals("Edited native cron rule",ui.visibleRule(id,2).getString("title"));
            ui.controller.requestFeature("automation","list",new JSONObject());ui.controller.requestFeature("automation","receipts",new JSONObject());for(int i=0;i<20;i++){ui.advance();if(!ui.controller.getState().getModuleBusy().contains("automation.list")&&!ui.controller.getState().getModuleBusy().contains("automation.receipts"))break;}
            assertEquals(2,ui.visibleRule(id,2).getInt("revision"));assertEquals(0,ui.controller.getState().getModuleResults().get("automation.receipts").getJSONArray("receipts").length());assertNull(ui.app.activeId());assertFalse(ui.module.snapshot().getBoolean("autonomousPhoneExecution"));
        }
    }
    @Test public void invalidCronAndAnOldEditorCannotReplaceTheOriginalRule()throws Exception {
        try(Screen ui=new Screen()){
            ui.click("时间安排：一次 ▾");ui.click("Cron 表达式");ui.edit("","Invalid cron");ui.edit("0 9 * * 1-5","0 9 1 * 1");assertTrue(ui.has("日与周最多限制一项"));boolean disabled=false;for(var node:ui.nodes())if(ui.text(node).equals("创建提醒")&&node.getConfig().contains(SemanticsProperties.INSTANCE.getDisabled()))disabled=true;assertTrue(disabled);assertEquals(0,ui.module.snapshot().getJSONArray("rules").length());ui.click("取消");
            JSONObject rule=ui.module.handle("save",new JSONObject().put("rule",new JSONObject().put("title","Original native rule").put("schedule",new JSONObject().put("type","cron").put("scheduleVersion",1).put("expression","0 9 * * *").put("zone","UTC")))).getJSONObject("rule");ui.openEditor(new JSONObject(rule.toString()));
            JSONObject changed=ui.module.handle("save",new JSONObject().put("rule",new JSONObject(rule.toString()).put("title","Newer native rule"))).getJSONObject("rule");ui.edit("Original native rule","Stale native edit");ui.click("保存修改");for(int i=0;i<30;i++){ui.advance();if(ui.controller.getState().getModuleErrors().containsKey("automation.save"))break;}
            assertTrue(ui.controller.getState().getModuleErrors().containsKey("automation.save"));assertEquals("Newer native rule",ui.module.snapshot().getJSONArray("rules").getJSONObject(0).getString("title"));assertEquals(changed.getInt("revision"),ui.module.snapshot().getJSONArray("rules").getJSONObject(0).getInt("revision"));assertNull(ui.app.activeId());
        }
    }
    @Test public void eventFailureAfterOriginalBookSaveReturnsTheSameDurableIdentityForReadOnlyRecovery()throws Exception {
        try(Screen ui=new Screen()){
            java.util.concurrent.atomic.AtomicInteger publications=new java.util.concurrent.atomic.AtomicInteger();
            // Only the native publisher is faulted. The original module/Book/atomic writer,
            // AlarmManager and actual subsequent reads are unchanged, in the same app store.
            AutomationModule faulted=new AutomationModule(new FeatureContext(ui.app,null,null,event->{if(event.optJSONObject("payload")!=null&&"automation_changed".equals(event.optJSONObject("payload").optString("event"))){publications.incrementAndGet();throw new IllegalStateException("fixture publication fault");}}));
            JSONObject result=faulted.handle("save",new JSONObject().put("rule",new JSONObject().put("title","Saved before publication fault").put("schedule",new JSONObject().put("type","cron").put("scheduleVersion",1).put("expression","0 9 * * *").put("zone","UTC"))));
            JSONObject receipt=result.getJSONObject("saveReceipt");String id=receipt.getString("ruleId");assertEquals("saved",receipt.getString("state"));assertEquals(1,receipt.getInt("ruleRevision"));assertEquals("unconfirmed",receipt.getString("changePublication"));assertEquals("same_revision",receipt.getString("ruleReadback"));assertEquals("not_applicable",receipt.getString("sourceState"));assertFalse(receipt.getBoolean("executionAuthorized"));assertEquals(1,publications.get());assertEquals(id,result.getJSONObject("rule").getString("id"));
            JSONObject faultedReadback=faulted.snapshot(),mountedReadback=ui.module.snapshot();assertEquals(faultedReadback.getString("observationGeneration"),mountedReadback.getString("observationGeneration"));assertTrue(mountedReadback.getLong("observationSequence")>faultedReadback.getLong("observationSequence"));
            // Query the original mounted module and owner Controller; no second save/retry.
            JSONArray original=ui.module.handle("list",new JSONObject()).getJSONArray("rules");assertEquals(1,original.length());assertEquals(id,original.getJSONObject(0).getString("id"));assertEquals(1,original.getJSONObject(0).getInt("revision"));ui.controller.requestFeature("automation","list",new JSONObject());assertEquals(id,ui.visibleRule(id,1).getString("id"));assertEquals(0,ui.module.handle("receipts",new JSONObject()).getJSONArray("receipts").length());assertNull(ui.app.activeId());faulted.close();
        }
    }
    @Test public void cronColdUnknownOccurrenceOnlyFindsOneOriginalSqlTaskAndNeverStartsIt()throws Exception {
        try(Screen ui=new Screen()){
            long now=System.currentTimeMillis(),planned=now-now%60000L;assertTrue(planned>0);
            java.io.File file=new java.io.File(ui.app.getFilesDir(),"automation/rules.json");AutomationBook original=new AutomationBook(file);
            JSONObject rule=original.save(new JSONObject().put("title","Original cron occurrence").put("kind","phone_goal").put("goal","仅由主人审阅的原手机目标").put("schedule",new JSONObject().put("type","cron").put("scheduleVersion",1).put("expression","* * * * *").put("zone","UTC")),planned-1);
            JSONObject claim=original.claimDue(now,10).getJSONObject(0);String occurrence=claim.getString("id");assertEquals(planned,claim.getLong("occurrenceAt"));AutomationBook cold=new AutomationBook(file);cold.recoverInterrupted(now);assertEquals("delivery_unknown",cold.receipts().getJSONObject(0).getString("status"));
            // Existing owner review action creates/finds an inert original task; it does not grant a run.
            JSONObject first=ui.app.dispatchOwnerAction("prepareReminderTask",new JSONObject().put("occurrenceId",occurrence).put("executionMode","background")).getJSONObject("task");assertEquals("preparing",first.getString("status"));assertEquals(0,first.getInt("coordinatorEpoch"));assertTrue(first.getBoolean("created"));
            ui.app.tasks.status(first.getString("id"),"reconciling","fixture: original task still needs reconciliation");new AutomationBook(file).recoverInterrupted(now+1);
            JSONObject repeated=ui.app.dispatchOwnerAction("prepareReminderTask",new JSONObject().put("occurrenceId",occurrence).put("executionMode","background")).getJSONObject("task");assertEquals(first.getString("id"),repeated.getString("id"));assertFalse(repeated.getBoolean("created"));assertEquals("reconciling",repeated.getString("status"));assertEquals(0,repeated.getInt("coordinatorEpoch"));assertEquals(1,ui.app.tasks.list().length());assertEquals(first.getString("id"),ui.app.tasks.automationTask(occurrence).getString("id"));assertEquals(0,cold.claimDue(now,10).length());assertEquals(1,cold.rules().getJSONObject(0).getLong("claimedCount"));assertEquals(rule.getString("id"),repeated.getString("automationRuleId"));assertNull(ui.app.activeId());
        }
    }
    @Test public void delayedOriginalReadbacksCannotReplaceANewerRevisionOrRestoreADeletedRule()throws Exception {
        try(Screen ui=new Screen()){
            JSONObject first=ui.module.handle("save",new JSONObject().put("rule",new JSONObject().put("title","Before delayed readback").put("schedule",new JSONObject().put("type","cron").put("scheduleVersion",1).put("expression","0 9 * * *").put("zone","UTC")))).getJSONObject("rule");String id=first.getString("id");ui.visibleRule(id,1);
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
