package dev.pocketengine.store;

import android.app.Application;
import android.content.Context;
import android.util.Base64;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskResultImageTest {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static JSONObject evidence(byte[] image)throws Exception{return new JSONObject().put("imageBase64",Base64.encodeToString(image,Base64.NO_WRAP)).put("automaticallyVerified",false);}
    private static String sha(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static JSONObject summary(TaskStore store,String id)throws Exception{
        var rows=store.taskSummaryPage("",40).getJSONArray("tasks");
        for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);
        throw new AssertionError("Original task missing from native summary");
    }

    @Test public void finishHashesActualDecodedBytesAndReusedFilenameDoesNotReuseResultIdentity()throws Exception{
        Context context=RuntimeEnvironment.getApplication();TaskStore store=new TaskStore(context);String id=store.create("核对截图").getString("id");
        byte[] a=bytes("screenshot A"),b=bytes("screenshot B");
        store.finish(id,"needs_confirmation","请核对结果",evidence(a).put("imageSha256","f".repeat(64)));
        JSONObject first=store.getForUi(id,true),firstEvidence=first.getJSONObject("evidence");
        assertEquals(sha(a),firstEvidence.getString("imageSha256"));
        assertArrayEquals(a,Base64.decode(firstEvidence.getString("imageDataUrl").substring("data:image/jpeg;base64,".length()),Base64.DEFAULT));
        store.finish(id,"needs_confirmation","请核对结果",evidence(b));
        JSONObject next=store.getForUi(id,true),nextEvidence=next.getJSONObject("evidence");
        assertEquals(firstEvidence.getString("imageFile"),nextEvidence.getString("imageFile"));
        assertEquals(sha(b),nextEvidence.getString("imageSha256"));
        assertArrayEquals(b,Files.readAllBytes(new File(context.getFilesDir(),"artifacts/"+id+".jpg").toPath()));
        assertArrayEquals(b,Base64.decode(nextEvidence.getString("imageDataUrl").substring("data:image/jpeg;base64,".length()),Base64.DEFAULT));
        assertNotEquals(first.getString("resultImageDigest"),next.getString("resultImageDigest"));
        assertNotEquals(first.getString("resultReviewDigest"),next.getString("resultReviewDigest"));
        assertTrue(next.getLong("resultRevision")>first.getLong("resultRevision"));
        assertEquals("needs_confirmation",next.getString("status"));assertFalse(nextEvidence.getBoolean("automaticallyVerified"));
    }

    @Test public void boundedSummaryProjectsOnlyNativeImageIdentityForTheExactResult()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=store.create("截图身份").getString("id");
        store.finish(id,"needs_confirmation","返回结果",evidence(bytes("result")).put("largePrivateResult","not a header field"));
        JSONObject detail=store.getForUi(id,true),header=summary(store,id),image=header.getJSONObject("evidence");
        assertEquals(detail.getString("resultImageDigest"),header.getString("resultImageDigest"));
        assertEquals(detail.getLong("resultRevision"),header.getLong("resultRevision"));
        assertEquals(detail.getJSONObject("evidence").getString("imageSha256"),image.getString("imageSha256"));
        assertEquals(2,image.length());assertFalse(image.has("imageDataUrl"));assertFalse(image.has("largePrivateResult"));
        store.event(id,new JSONObject().put("type","observation"));
        assertEquals(header.getString("resultImageDigest"),summary(store,id).getString("resultImageDigest"));
        store.status(id,"needs_confirmation","不同的结果说明");
        assertNotEquals(header.getString("resultImageDigest"),summary(store,id).getString("resultImageDigest"));
    }

    @Test public void fileWhoseBytesNoLongerMatchTheSavedNativeDigestIsNotAttached()throws Exception{
        Context context=RuntimeEnvironment.getApplication();TaskStore store=new TaskStore(context);String id=store.create("只读原截图").getString("id");
        store.finish(id,"needs_confirmation","A",evidence(bytes("A")));JSONObject before=store.getForUi(id,true);
        Files.write(new File(context.getFilesDir(),"artifacts/"+id+".jpg").toPath(),bytes("B"));
        assertFalse(store.getForUi(id,true).getJSONObject("evidence").has("imageDataUrl"));
        assertFalse(store.get(id,true).getJSONObject("evidence").has("imageDataUrl"));
        assertEquals(before.getString("resultImageDigest"),summary(store,id).getString("resultImageDigest"));
    }

    @Test public void imageFreeResultCannotClaimThePreviousFileOrCallerSuppliedDigest()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=store.create("无图结果").getString("id");byte[] a=bytes("A");
        store.finish(id,"needs_confirmation","A",evidence(a));
        store.finish(id,"needs_confirmation","无图",new JSONObject().put("imageFile",id+".jpg").put("imageSha256",sha(a)).put("imageDataUrl","data:image/jpeg;base64,QQ=="));
        JSONObject next=store.getForUi(id,true),header=summary(store,id),saved=next.getJSONObject("evidence");
        assertFalse(saved.has("imageFile"));assertFalse(saved.has("imageSha256"));assertFalse(saved.has("imageDataUrl"));
        assertFalse(next.has("resultImageDigest"));assertFalse(header.has("resultImageDigest"));assertFalse(header.has("evidence"));
    }

    @Test public void changedSummaryWithIdenticalImageBytesStillGetsANewExactResultIdentity()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=store.create("相同截图不同结果").getString("id");byte[] a=bytes("A");
        store.finish(id,"needs_confirmation","A",evidence(a));JSONObject first=store.getForUi(id,true);
        store.finish(id,"needs_confirmation","不同结果",evidence(a));JSONObject second=store.getForUi(id,true);
        assertEquals(first.getJSONObject("evidence").getString("imageSha256"),second.getJSONObject("evidence").getString("imageSha256"));
        assertNotEquals(first.getString("resultImageDigest"),second.getString("resultImageDigest"));
        assertEquals(second.getString("resultImageDigest"),summary(store,id).getString("resultImageDigest"));
    }
}
