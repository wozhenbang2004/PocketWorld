package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.net.Uri;
import android.os.PersistableBundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Caller-provided synthetic ClipData only; these parser tests read no system clipboard. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteDraftClipboardTest {
    private static ClipData plain(String text){return ClipData.newPlainText("private label must not become an error",text);}
    private static ClipData item(ClipData.Item item){return new ClipData(new ClipDescription("fixture",new String[]{"text/plain"}),item);}
    @Test public void preservesExactCjkMultilineWithoutMutatingTheProvidedSample(){String text="中文\nline 2 😀";ClipData sample=plain(text);assertEquals(text,RemoteDraftClipboard.text(sample));assertEquals(text,sample.getItemAt(0).getText().toString());assertEquals(1,sample.getItemCount());}
    @Test public void missingBlankAndNulNeverInventAnEmptyDraft(){assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(null));for(String text:new String[]{"","  ","bad\0text"})assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(plain(text)));}
    @Test public void preservesTheOriginalLengthBoundWithoutTruncation(){assertEquals(12000,RemoteDraftClipboard.text(plain("字".repeat(12000))).length());assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(plain("字".repeat(12001))));}
    @Test public void rejectsUriEvenWithPlainFallbackWithoutProviderCoercion(){ClipData sample=item(new ClipData.Item("fallback",null,null,Uri.parse("content://not-opened/private")));assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(sample));}
    @Test public void rejectsIntentEvenWithPlainFallbackWithoutLaunchingIt(){ClipData sample=item(new ClipData.Item("fallback",null,new Intent("never-launch-this"),null));assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(sample));}
    @Test public void rejectsHtmlUnderAMislabeledPlainDescription(){assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(item(new ClipData.Item("fallback","<b>do not parse</b>"))));}
    @Test public void multipleItemsAndAmbiguousMimeAreNotSilentlyReduced(){ClipData sample=plain("first");sample.addItem(new ClipData.Item("second"));assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(sample));for(String[] types:new String[][]{{"text/html"},{"text/*"},{"text/plain","text/html"}}){ClipData other=new ClipData(new ClipDescription("fixture",types),new ClipData.Item("data"));assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(other));}}
    @Test public void declaredSensitiveBodyAndLabelNeverAppearInErrors(){ClipData sample=plain("PRIVATE_CLIP_SENTINEL");PersistableBundle flags=new PersistableBundle();flags.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true);sample.getDescription().setExtras(flags);RemoteDraftClipboard.Failure error=assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.text(sample));assertEquals(RemoteDraftClipboard.Reason.SENSITIVE,error.reason);assertFalse(error.toString().contains("PRIVATE_CLIP_SENTINEL"));assertFalse(error.toString().contains("private label"));assertEquals("PRIVATE_CLIP_SENTINEL",sample.getItemAt(0).getText().toString());}
    @Test public void appendPreservesTheExistingEditorAndExactMultilineImport(){assertEquals("旧要求\n新来源\nsecond",RemoteDraftClipboard.append("旧要求","新来源\nsecond"));assertEquals("旧要求\n新来源",RemoteDraftClipboard.append("旧要求\n","新来源"));assertEquals("新来源",RemoteDraftClipboard.append("","新来源"));}
    @Test public void combinedLimitIsRejectedWithoutTruncationOrChangingCallerText(){String existing="e".repeat(11999);assertEquals(RemoteDraftClipboard.Reason.APPEND_LIMIT,assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.append(existing,"xx")).reason);assertEquals(11999,existing.length());}
    @Test public void appendDoesNotAcceptAnEmptyOrNulSourceFromAnotherCaller(){for(String text:new String[]{"","  ","x\0y"})assertThrows(RemoteDraftClipboard.Failure.class,()->RemoteDraftClipboard.append("old",text));}
}
