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
            controller.attach();Method editor=ConnectionCenterKt.class.getDeclaredMethod("ReminderEditor",PocketUiState.class,PocketWorldController.class,Function0.class,Composer.class,int.class);editor.setAccessible(true);
            view=new ComposeView(activity);view.setContent(new Function2<Composer,Integer,Unit>(){public Unit invoke(Composer composer,Integer flags){try{editor.invoke(null,controller.getState(),controller,(Function0<Unit>)()->{closed=true;return Unit.INSTANCE;},composer,0);return Unit.INSTANCE;}catch(Exception failure){throw new IllegalStateException(failure);}}});activity.setContentView(view);advance();
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
        public void close()throws Exception {if(view!=null)view.disposeComposition();controller.detach();owner.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();app.onTerminate();app.features.close();}
    }
    @Test public void allFiveModesAreNativeActionsAndIntervalSaveKeepsFutureAnchorAndOriginalDefaults()throws Exception {
        try(Screen ui=new Screen()){
            ui.click("时间安排：一次 ▾");for(String type:new String[]{"一次","固定间隔","每天","每周","每月"})assertTrue(type,ui.has(type));ui.click("固定间隔");assertTrue("Interval selected: "+ui.nodes().stream().map(ui::text).toList(),ui.has("首次约"));ui.edit("","Native interval rule");ui.edit("30","45");long before=System.currentTimeMillis();ui.click("创建提醒");JSONObject rule=ui.saved("Native interval rule"),schedule=rule.getJSONObject("schedule");assertEquals("interval",schedule.getString("type"));assertEquals(2700000L,schedule.getLong("intervalMillis"));assertTrue(schedule.getLong("anchorAt")>=before+2700000L);assertTrue(rule.getLong("nextAt")>=schedule.getLong("anchorAt"));assertEquals("skip",rule.getJSONObject("policy").getString("misfire"));assertEquals("buffer_one",rule.getJSONObject("policy").getString("overlap"));assertEquals("reminder",rule.getString("kind"));assertNull(ui.app.activeId());assertEquals(0,ui.module.handle("receipts",new JSONObject()).getJSONArray("receipts").length());
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
}
