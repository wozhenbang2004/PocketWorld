package dev.pocketengine.browser;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import dev.pocketengine.BrowserActivity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** The same test compiles and fails on the old Activity's shared request-code implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserPickerIdentityTest {
    @Test public void delayedResultFromCancelledPickerCannotCancelItsReplacement()throws Exception {
        var controller=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();BrowserActivity browser=controller.get();
        try{
            Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);WebView web=(WebView)field.get(browser);List<Uri[]> first=new ArrayList<>(),second=new ArrayList<>();
            web.getWebChromeClient().onShowFileChooser(web,first::add,chooser());int oldCode=Shadows.shadowOf(browser).getNextStartedActivityForResult().requestCode;
            web.getWebViewClient().onPageStarted(web,"https://example.com/next",null);assertEquals(1,first.size());assertNull(first.get(0));
            web.getWebChromeClient().onShowFileChooser(web,second::add,chooser());int newCode=Shadows.shadowOf(browser).getNextStartedActivityForResult().requestCode;
            var result=BrowserActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);result.invoke(browser,oldCode,Activity.RESULT_CANCELED,null);
            assertEquals("Old picker result must not consume the new callback",0,second.size());assertTrue(browser.snapshot().getBoolean("fileChooserPending"));
            result.invoke(browser,newCode,Activity.RESULT_CANCELED,null);assertEquals(1,second.size());assertNull(second.get(0));
        }finally{controller.pause().stop().destroy();BrowserActivity.setEvents(null);}
    }
    private WebChromeClient.FileChooserParams chooser(){return new WebChromeClient.FileChooserParams(){@Override public int getMode(){return MODE_OPEN;}@Override public String[] getAcceptTypes(){return new String[]{"text/plain"};}@Override public boolean isCaptureEnabled(){return false;}@Override public CharSequence getTitle(){return null;}@Override public String getFilenameHint(){return null;}@Override public Intent createIntent(){return new Intent(Intent.ACTION_OPEN_DOCUMENT);}};}
    @Test public void changedDestinationWithoutNavigationCannotReceiveThePreviousPickerFiles()throws Exception {
        var controller=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();BrowserActivity browser=controller.get();
        try{browser.openForOwner("https://upload.example/account?document=original");Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);WebView web=(WebView)field.get(browser);List<Uri[]> chosen=new ArrayList<>();web.getWebChromeClient().onShowFileChooser(web,chosen::add,chooser());int code=Shadows.shadowOf(browser).getNextStartedActivityForResult().requestCode;long navigation=browser.snapshot().getLong("navigation");web.loadUrl("https://upload.example/account?document=another");assertEquals(navigation,browser.snapshot().getLong("navigation"));Uri picked=Uri.parse("content://fixture/should-not-read");Shadows.shadowOf(browser.getContentResolver()).registerInputStream(picked,new java.io.ByteArrayInputStream(new byte[]{1,2,3}));var result=BrowserActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);result.invoke(browser,code,Activity.RESULT_OK,new Intent().setData(picked));long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(chosen.isEmpty()&&System.nanoTime()<until){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(3);}assertEquals(1,chosen.size());assertNull(chosen.get(0));assertEquals(0,dev.pocketengine.features.attachments.AttachmentRepository.get(browser).book.list().length());assertFalse(browser.snapshot().getBoolean("fileChooserPending"));}
        finally{controller.pause().stop().destroy();BrowserActivity.setEvents(null);}
    }
}
