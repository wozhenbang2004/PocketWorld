package dev.pocketengine.store;

import android.app.Application;
import android.content.Context;
import android.util.Base64;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exact-result cache identity with real PNG bytes and the encrypted native screenshot store. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskResultImageTest {
    private static byte[] imageA(){return Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNgYGAAAAAEAAH2FzhVAAAAAElFTkSuQmCC",Base64.DEFAULT);}
    private static byte[] imageB(){return Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC",Base64.DEFAULT);}
    private static TaskScreenshotStorage.Crypto crypto()throws Exception {
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey key=generator.generateKey();
        return new TaskScreenshotStorage.Crypto(){
            public byte[] seal(byte[] plain,byte[] aad)throws Exception{return TaskScreenshotStorage.seal(key,plain,aad);}
            public byte[] open(byte[] encrypted,byte[] aad)throws Exception{return TaskScreenshotStorage.open(key,encrypted,aad);}
        };
    }
    private static JSONObject evidence(byte[] image)throws Exception{return new JSONObject().put("imageBase64",Base64.encodeToString(image,Base64.NO_WRAP)).put("automaticallyVerified",false);}
    private static String sha(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static byte[] displayed(JSONObject evidence)throws Exception{return Base64.decode(evidence.getString("imageDataUrl").substring("data:image/png;base64,".length()),Base64.DEFAULT);}
    private static File imageFile(Context context,JSONObject evidence)throws Exception{return new File(context.getFilesDir(),"artifacts/"+evidence.getString("imageFile"));}
    private static JSONObject summary(TaskStore store,String id)throws Exception{
        var rows=store.taskSummaryPage("",40).getJSONArray("tasks");
        for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);
        throw new AssertionError("Original task missing from native summary");
    }

    @Test public void finishHashesActualDecodedBytesAndReplacementDoesNotReuseResultIdentity()throws Exception{
        Context context=RuntimeEnvironment.getApplication();TaskStore store=new TaskStore(context,crypto());String id=store.create("核对截图").getString("id");
        byte[] a=imageA(),b=imageB();
        store.finish(id,"needs_confirmation","请核对结果",evidence(a).put("imageSha256","f".repeat(64)));
        JSONObject first=store.getForUi(id,true),firstEvidence=first.getJSONObject("evidence");
        assertEquals(sha(a),firstEvidence.getString("imageSha256"));assertArrayEquals(a,displayed(firstEvidence));
        assertTrue(firstEvidence.getJSONObject("screenshot").getBoolean("encrypted"));
        assertFalse(Arrays.equals(a,Files.readAllBytes(imageFile(context,firstEvidence).toPath())));
        assertFalse(new File(context.getFilesDir(),"artifacts/"+id+".jpg").exists());
        store.finish(id,"needs_confirmation","请核对结果",evidence(b));
        JSONObject next=store.getForUi(id,true),nextEvidence=next.getJSONObject("evidence");
        assertNotEquals(firstEvidence.getString("imageFile"),nextEvidence.getString("imageFile"));
        assertEquals(sha(b),nextEvidence.getString("imageSha256"));assertArrayEquals(b,displayed(nextEvidence));
        assertNotEquals(first.getString("resultImageDigest"),next.getString("resultImageDigest"));
        assertNotEquals(first.getString("resultReviewDigest"),next.getString("resultReviewDigest"));
        assertTrue(next.getLong("resultRevision")>first.getLong("resultRevision"));
        assertEquals("needs_confirmation",next.getString("status"));assertFalse(nextEvidence.getBoolean("automaticallyVerified"));
    }

    @Test public void boundedSummaryProjectsOnlyNativeImageIdentityForTheExactResult()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication(),crypto());String id=store.create("截图身份").getString("id");
        store.finish(id,"needs_confirmation","返回结果",evidence(imageA()).put("largePrivateResult","not a header field"));
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

    @Test public void sameEncryptedPathWithDifferentAuthenticatedBytesIsNotAttached()throws Exception{
        Context context=RuntimeEnvironment.getApplication();TaskScreenshotStorage.Crypto cipher=crypto();TaskStore store=new TaskStore(context,cipher);String id=store.create("只读原截图").getString("id");
        byte[] a=imageA(),b=imageB();assertEquals(a.length,b.length);
        store.finish(id,"needs_confirmation","A",evidence(a));JSONObject before=store.getForUi(id,true),saved=before.getJSONObject("evidence");
        // Even valid AES ciphertext with the same original AAD and length cannot replace the saved byte identity.
        Files.write(imageFile(context,saved).toPath(),cipher.seal(b,TaskScreenshotPolicy.aad(saved.getJSONObject("screenshot"))));
        assertFalse(store.getForUi(id,true).getJSONObject("evidence").has("imageDataUrl"));
        assertFalse(store.get(id,true).getJSONObject("evidence").has("imageDataUrl"));
        assertEquals(before.getString("resultImageDigest"),summary(store,id).getString("resultImageDigest"));
    }

    @Test public void imageFreeResultCannotClaimThePreviousFileOrCallerSuppliedDigest()throws Exception{
        Context context=RuntimeEnvironment.getApplication();TaskStore store=new TaskStore(context,crypto());String id=store.create("无图结果").getString("id");byte[] a=imageA();
        store.finish(id,"needs_confirmation","A",evidence(a));JSONObject original=store.getForUi(id,true).getJSONObject("evidence");
        store.finish(id,"needs_confirmation","无图",new JSONObject().put("imageFile",original.getString("imageFile")).put("imageSha256",sha(a)).put("imageDataUrl","data:image/png;base64,QQ=="));
        JSONObject next=store.getForUi(id,true),header=summary(store,id),saved=next.getJSONObject("evidence");
        assertFalse(saved.has("imageFile"));assertFalse(saved.has("imageSha256"));assertFalse(saved.has("imageDataUrl"));
        assertFalse(next.has("resultImageDigest"));assertFalse(header.has("resultImageDigest"));assertFalse(header.has("evidence"));
        assertTrue(saved.getJSONObject("screenshot").getBoolean("retainedFromPreviousResult"));
        assertEquals(original.getJSONObject("screenshot").getString("imageId"),saved.getJSONObject("screenshot").getString("imageId"));
        assertTrue(imageFile(context,original).isFile());
    }

    @Test public void changedSummaryWithIdenticalImageBytesStillGetsANewExactResultIdentity()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication(),crypto());String id=store.create("相同截图不同结果").getString("id");byte[] a=imageA();
        store.finish(id,"needs_confirmation","A",evidence(a));JSONObject first=store.getForUi(id,true);
        store.finish(id,"needs_confirmation","不同结果",evidence(a));JSONObject second=store.getForUi(id,true);
        assertEquals(first.getJSONObject("evidence").getString("imageSha256"),second.getJSONObject("evidence").getString("imageSha256"));
        assertNotEquals(first.getString("resultImageDigest"),second.getString("resultImageDigest"));
        assertEquals(second.getString("resultImageDigest"),summary(store,id).getString("resultImageDigest"));
    }
}
