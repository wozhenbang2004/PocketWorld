package dev.pocketengine.phone;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Actual native Views/WindowManager; callback counters are UI boundaries, not a phone/model host. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,qualifiers="w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class QuickPanelSurfaceTest {
    private static final class Fixture implements AutoCloseable {
        final Class<?> type;
        final Object surface;
        final ViewGroup view;
        int captures,cancels,sends,queries,opens;
        boolean includeImage;
        String sentNote;
        Fixture()throws Exception {
            try { type=Class.forName("dev.pocketengine.phone.QuickPanelSurface"); }
            catch(ClassNotFoundException missing){throw new AssertionError("The native explicit capture/preview/cancel/send entry is absent",missing);}
            Class<?> actions=Class.forName("dev.pocketengine.phone.QuickPanelSurface$Actions");
            Object callback=Proxy.newProxyInstance(actions.getClassLoader(),new Class<?>[]{actions},(proxy,method,args)->{
                switch(method.getName()) {
                    case "capture":captures++;includeImage=(boolean)args[0];break;
                    case "cancelPreview":cancels++;call("ready");break;
                    case "send":sends++;sentNote=(String)args[0];break;
                    case "queryReceipt":queries++;break;
                    case "openOriginalTask":opens++;break;
                    case "close":close();break;
                    default:break;
                }
                return null;
            });
            Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
            surface=constructor.newInstance(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),android.R.style.Theme_Material_Light_NoActionBar),
                    "比较这两个订单，再把核对结果留在原任务。不要直接替我提交。","后台独立屏",callback);
            call("show");view=(ViewGroup)call("view");
        }
        Object call(String name,Object...args)throws Exception {
            Method method=null;for(Method candidate:type.getDeclaredMethods())if(candidate.getName().equals(name)&&candidate.getParameterCount()==args.length){method=candidate;break;}
            assertNotNull(name,method);method.setAccessible(true);return method.invoke(surface,args);
        }
        List<View> views(){List<View> out=new ArrayList<>();walk(view,out);return out;}
        android.app.AlertDialog noteDialog()throws Exception {java.lang.reflect.Field field=type.getDeclaredField("noteDialog");field.setAccessible(true);return (android.app.AlertDialog)field.get(surface);}
        EditText editNote()throws Exception {Button edit=(Button)view.findViewWithTag("quick_panel_edit_note");assertNotNull(edit);assertTrue(edit.performClick());android.app.AlertDialog dialog=noteDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,dialog.getWindow().getAttributes().type);return dialog.getWindow().getDecorView().findViewWithTag("quick_panel_note");}
        void saveNote()throws Exception {android.app.AlertDialog dialog=noteDialog();assertNotNull(dialog);assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick());org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
        void click(String label){Button target=null;for(View child:views())if(child instanceof Button&&((Button)child).getText().toString().equals(label)){target=(Button)child;break;}assertNotNull("Visible native action: "+label,target);assertTrue(target.isEnabled());assertTrue(target.performClick());}
        void preview(String text,boolean truncated)throws Exception {
            Class<?> content=Class.forName("dev.pocketengine.phone.QuickPanelSurface$PreviewContent");
            Constructor<?> constructor=content.getDeclaredConstructors()[0];constructor.setAccessible(true);
            call("preview",constructor.newInstance("订单 App",text,truncated,null,"这次只读取文字，没有截图。"));
        }
        void save(String name)throws Exception {
            int width=360;view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.AT_MOST));
            view.layout(0,0,width,view.getMeasuredHeight());Bitmap bitmap=Bitmap.createBitmap(width,Math.max(1,view.getMeasuredHeight()),Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));File target=new File(System.getProperty("pocketworld.a24.entry.renders","build/test-renders/a24-entry"),name);
            target.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(target)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();assertTrue(target.length()>1500);
        }
        @Override public void close()throws Exception {call("close");org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    }
    private static void walk(View view,List<View> out){out.add(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)walk(((ViewGroup)view).getChildAt(i),out);}

    @Test public void openingAndCancellingNeverReadOrSendAndScreenshotStartsOff()throws Exception {
        try(Fixture f=new Fixture()) {
            assertEquals(0,f.captures);assertEquals(0,f.sends);f.click("补充原任务");
            CheckBox images=(CheckBox)f.views().stream().filter(v->v instanceof CheckBox).findFirst().orElseThrow();assertFalse(images.isChecked());
            assertTrue(f.views().stream().anyMatch(v->v instanceof TextView&&((TextView)v).getText().toString().contains("后台独立屏")));
            f.save("01-explicit-capture-ready.png");f.click("捕获并预览");assertEquals(1,f.captures);assertFalse(f.includeImage);
            f.call("capturing");f.click("取消这次捕获");assertEquals(1,f.cancels);assertEquals(0,f.sends);
            f.preview("订单A · ￥199\n订单B · ￥219\n尚未提交。",false);f.click("取消这份内容");assertEquals(2,f.cancels);assertEquals(0,f.sends);
            assertEquals(1,f.captures);
        }
    }
    @Test public void actualPreviewShowsSourceAndSendsOnlyFromExplicitButtonUnknownOnlyQueries()throws Exception {
        try(Fixture f=new Fixture()) {
            f.click("补充原任务");f.preview("订单A · ￥199\n订单B · ￥219\n"+"长中文内容，保留原任务与明确来源。".repeat(35),true);
            EditText note=f.editNote();note.setText("只比较价格和收货日期。");f.saveNote();
            assertEquals(0,f.sends);assertTrue(f.views().stream().anyMatch(v->v instanceof TextView&&((TextView)v).getText().toString().contains("当前手机主屏")));
            f.save("02-source-preview-and-explicit-send.png");f.click("发送到原任务");assertEquals(1,f.sends);assertEquals("只比较价格和收货日期。",f.sentNote);
            f.call("sending");assertFalse(f.views().stream().anyMatch(v->v instanceof Button&&((Button)v).getText().toString().equals("发送到原任务")));
            f.call("unknown","登记结果待核对");f.click("查询原回执");f.click("查询原回执");assertEquals(2,f.queries);assertEquals(1,f.sends);assertEquals(0,f.captures);f.save("03-unconfirmed-query-only.png");
        }
    }
    @Test public void hiddenCaptureWindowIsUntouchableAndCloseRemovesNativeContents()throws Exception {
        Fixture f=new Fixture();f.click("补充原任务");f.call("captureHidden",true);
        assertEquals(View.INVISIBLE,f.view.getVisibility());
        java.lang.reflect.Field field=f.type.getDeclaredField("layout");field.setAccessible(true);WindowManager.LayoutParams layout=(WindowManager.LayoutParams)field.get(f.surface);
        assertTrue((layout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);assertFalse((Boolean)f.call("visible"));
        f.call("captureHidden",false);assertEquals(View.VISIBLE,f.view.getVisibility());assertEquals(0,layout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        f.close();assertEquals(0,f.view.getChildCount());assertFalse((Boolean)f.call("visible"));assertEquals(0,f.captures);assertEquals(0,f.sends);
    }

    @Test @Config(qualifiers="w320dp-h480dp-mdpi") public void smallScreenLargeFontKeepsHeaderAndPhaseActionsOutsideBoundedBody()throws Exception {
        float before=RuntimeEnvironment.getFontScale();RuntimeEnvironment.setFontScale(2f);
        try(Fixture f=new Fixture()) {
            f.click("补充原任务");f.preview("这是固定捕获的原文。\n".repeat(300),true);
            f.view.measure(View.MeasureSpec.makeMeasureSpec(296,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(444,View.MeasureSpec.AT_MOST));f.view.layout(0,0,296,f.view.getMeasuredHeight());
            View header=f.view.findViewWithTag("quick_panel_header"),body=f.view.findViewWithTag("quick_panel_body_scroll"),actions=f.view.findViewWithTag("quick_panel_phase_actions");assertNotNull(header);assertNotNull(body);assertNotNull(actions);
            assertSame(f.view,header.getParent());assertSame(f.view,actions.getParent());assertTrue(f.view.getMeasuredHeight()<=444);assertTrue(body.getMeasuredHeight()>0);assertTrue(actions.getTop()>=header.getBottom());assertTrue("Phase actions remain inside native surface",actions.getBottom()<=f.view.getHeight()-f.view.getPaddingBottom());
            assertTrue(f.views().stream().anyMatch(v->v instanceof Button&&((Button)v).getText().toString().equals("关闭")));Button send=(Button)f.view.findViewWithTag("quick_panel_send");assertEquals(2f,send.getResources().getConfiguration().fontScale,.001f);float platformSp=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,13f,send.getResources().getDisplayMetrics());assertEquals("Actual text uses Android platform SP conversion (not a linear fontScale assumption)",platformSp,send.getTextSize(),.01f);assertTrue("Large font reaches the actual native TextView",send.getTextSize()>13.01f);f.click("发送到原任务");assertEquals(1,f.sends);assertEquals(0,f.captures);
        } finally {RuntimeEnvironment.setFontScale(before);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    }
    @Test public void collapsingUnknownKeepsSameQueryOnlyStateWithoutCapturingOrSending()throws Exception {
        try(Fixture f=new Fixture()) {
            f.click("补充原任务");f.preview("固定来源，不随其他App变化。",false);f.call("unknown","原发送身份待核对");f.click("收起");
            assertEquals(0,f.captures);assertEquals(0,f.sends);f.click("待核对 · 原任务");
            assertFalse(f.views().stream().anyMatch(v->v instanceof Button&&((Button)v).getText().toString().equals("发送到原任务")));
            assertFalse(f.views().stream().anyMatch(v->v instanceof Button&&((Button)v).getText().toString().equals("捕获并预览")));
            f.click("查询原回执");assertEquals(1,f.queries);assertEquals(0,f.sends);assertEquals(0,f.captures);
        }
    }
    @Test public void cancellingOnePreviewDoesNotCarryItsNoteIntoAnotherSource()throws Exception {
        try(Fixture f=new Fixture()) {
            f.click("补充原任务");f.preview("原来源A",false);f.editNote().setText("只属于来源A的说明");f.saveNote();f.click("取消这份内容");
            assertEquals(1,f.cancels);f.preview("原来源B",false);assertEquals("",f.editNote().getText().toString());assertEquals(0,f.sends);
        }
    }


    @Test public void partialStateAndNoteEntryStayOutsideLongBodyWithoutReplacingCapturedSource()throws Exception {
        try(Fixture f=new Fixture()) {
            f.click("补充原任务");String captured="原捕获长文。".repeat(500);f.preview(captured,true);View partial=f.view.findViewWithTag("quick_panel_partial"),body=f.view.findViewWithTag("quick_panel_body_scroll"),edit=f.view.findViewWithTag("quick_panel_edit_note");assertNotNull(partial);assertSame(f.view,partial.getParent());assertNotSame(body,edit.getParent());
            f.view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.AT_MOST));f.view.layout(0,0,360,f.view.getMeasuredHeight());assertTrue(partial.getBottom()<=body.getTop());assertTrue(((View)edit.getParent()).getBottom()<=f.view.getHeight()-f.view.getPaddingBottom());
            assertEquals(captured,((TextView)f.view.findViewWithTag("quick_panel_source")).getText().toString());EditText input=f.editNote();input.setText("仍属于原捕获");f.saveNote();assertEquals(captured,((TextView)f.view.findViewWithTag("quick_panel_source")).getText().toString());assertEquals(0,f.captures);assertEquals(0,f.sends);f.save("04-partial-and-note-entry-visible.png");f.click("发送到原任务");assertEquals("仍属于原捕获",f.sentNote);assertEquals(1,f.sends);
        }
    }
    @Test public void cancellingOrClosingNoteEditorNeverSendsAndClearsItsTemporaryText()throws Exception {
        try(Fixture f=new Fixture()) {
            f.click("补充原任务");f.preview("固定来源A",false);EditText saved=f.editNote();saved.setText("明确保存的说明");f.saveNote();EditText canceled=f.editNote();canceled.setText("取消的编辑");android.app.AlertDialog dialog=f.noteDialog();assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick());org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals("",canceled.getText().toString());assertEquals("明确保存的说明",f.editNote().getText().toString());EditText open=f.noteDialog().getWindow().getDecorView().findViewWithTag("quick_panel_note");open.setText("关闭时清理");f.close();assertEquals("",open.getText().toString());assertEquals(0,f.captures);assertEquals(0,f.sends);assertEquals(0,f.view.getChildCount());
        }
    }
    @Test public void savedNoteStaysWithSamePreviewButNeverCrossesCaptureOrNewSourceIdentity()throws Exception {
        try(Fixture f=new Fixture()) {
            f.click("补充原任务");f.preview("固定来源A",false);f.editNote().setText("已经保存、只属于A");f.saveNote();
            java.lang.reflect.Field field=f.type.getDeclaredField("preview");field.setAccessible(true);Object samePreview=field.get(f.surface);
            f.call("error","同一份内容的可恢复提示");assertEquals("已经保存、只属于A",f.editNote().getText().toString());f.saveNote();
            f.call("preview",samePreview);assertEquals("已经保存、只属于A",f.editNote().getText().toString());f.saveNote();
            f.call("capturing");f.preview("固定来源B",false);assertEquals("",f.editNote().getText().toString());f.editNote().setText("已经保存、只属于B");f.saveNote();
            f.preview("直接交来的固定来源C",false);assertEquals("",f.editNote().getText().toString());assertEquals(0,f.sends);assertEquals(0,f.captures);
        }
    }

    @Test public void changedCaptureDiscardsAnOpenNoteEditorWithoutApplyingItToTheNextSource()throws Exception {
        try(Fixture f=new Fixture()) {
            f.click("补充原任务");f.preview("固定来源A",false);EditText input=f.editNote();input.setText("只属于A的未保存编辑");android.app.AlertDialog original=f.noteDialog();f.call("ready");assertFalse(original.isShowing());assertEquals("",input.getText().toString());f.preview("固定来源B",false);assertEquals("",f.editNote().getText().toString());assertEquals(0,f.sends);assertEquals(0,f.captures);
        }
    }
}
