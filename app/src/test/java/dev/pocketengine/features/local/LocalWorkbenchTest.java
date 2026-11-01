package dev.pocketengine.features.local;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class LocalWorkbenchTest {
    private static JSONObject args(String checkout,String path)throws Exception{return new JSONObject().put("checkoutId",checkout).put("path",path);}
    private static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();final WorkspaceModule projects;final LocalModule local;final String project,checkout;
        Fixture()throws Exception {
            FeatureContext feature=new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{});projects=new WorkspaceModule(feature);
            project=projects.handle("create",new JSONObject().put("name","真实工作台")).getString("id");
            projects.handle("write",new JSONObject().put("projectId",project).put("path","main.py").put("content","print(1)\n").put("expectedVersion",0));
            local=new LocalModule(feature,projects);checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
        }
        JSONObject review()throws Exception{return local.handle("reviewFile",args(checkout,"main.py"));}
        void edit(String text)throws Exception {JSONObject old=local.handle("readFile",args(checkout,"main.py"));local.handle("writeFile",args(checkout,"main.py").put("content",text).put("expectedHash",old.getString("sha256")));}
        String original()throws Exception{return projects.handle("read",new JSONObject().put("projectId",project).put("path","main.py")).getString("content");}
        @Override public void close(){local.close();}
    }
    @Test public void changesReviewAndReviewedImportUseTheRealWorkspace()throws Exception {
        try(Fixture f=new Fixture()){
            assertEquals(0,f.local.handle("changes",new JSONObject().put("checkoutId",f.checkout)).getInt("changedCount"));f.edit("print(2)\n");
            JSONObject changes=f.local.handle("changes",new JSONObject().put("checkoutId",f.checkout));assertEquals(1,changes.getInt("changedCount"));assertEquals("modified",changes.getJSONArray("files").getJSONObject(0).getString("change"));
            JSONObject review=f.review();assertEquals(1,review.getInt("expectedVersion"));assertTrue(review.getString("diff").contains("-print(1)"));assertTrue(review.getString("diff").contains("+print(2)"));assertTrue(review.getBoolean("importable"));
            f.local.handle("importToProject",review);assertEquals("print(2)\n",f.original());assertEquals(0,f.local.handle("changes",new JSONObject().put("checkoutId",f.checkout)).getInt("changedCount"));
        }
    }
    @Test public void missingOrStringVersionCannotUseTheLegacyImportBypass()throws Exception {
        try(Fixture f=new Fixture()){
            f.edit("changed");JSONObject review=f.review();
            for(JSONObject input:new JSONObject[]{args(f.checkout,"main.py"),args(f.checkout,"main.py").put("expectedVersion",1),args(f.checkout,"main.py").put("expectedVersion","1").put("expectedHash",review.getString("expectedHash"))}){
                try{f.local.handle("importToProject",input);fail("Unreviewed import accepted");}catch(IllegalArgumentException expected){}
            }
            assertEquals("print(1)\n",f.original());
        }
    }
    @Test public void localChangeAfterReviewCannotBeImportedWithOldHash()throws Exception {
        try(Fixture f=new Fixture()){
            f.edit("reviewed");JSONObject review=f.review();f.edit("unreviewed");
            try{f.local.handle("importToProject",review);fail("Changed local bytes imported");}catch(IllegalStateException expected){}
            assertEquals("print(1)\n",f.original());
        }
    }
    @Test public void targetChangeAfterReviewCannotBeOverwrittenAndReReviewUsesLatestVersion()throws Exception {
        try(Fixture f=new Fixture()){
            f.edit("local\n");JSONObject review=f.review();f.projects.handle("write",new JSONObject().put("projectId",f.project).put("path","main.py").put("content","other editor\n").put("expectedVersion",1));
            try{f.local.handle("importToProject",review);fail("New workspace version overwritten");}catch(IllegalStateException expected){}
            assertEquals("other editor\n",f.original());JSONObject next=f.review();assertEquals(2,next.getInt("expectedVersion"));assertTrue(next.getString("diff").contains("-other editor"));
        }
    }
    @Test public void deletionIsReviewedAndCasImportedNotImplicit()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject before=f.review();f.local.handle("deleteFile",args(f.checkout,"main.py").put("expectedHash",before.getString("expectedHash")));
            assertEquals("print(1)\n",f.original());JSONObject review=f.review();assertTrue(review.getBoolean("deleted"));assertEquals("absent",review.getString("expectedHash"));assertEquals("deleted",f.local.handle("changes",new JSONObject().put("checkoutId",f.checkout)).getJSONArray("files").getJSONObject(0).getString("change"));
            f.local.handle("importToProject",review);assertEquals(0,f.projects.handle("list",new JSONObject().put("projectId",f.project)).getJSONArray("files").length());assertEquals(0,f.local.handle("changes",new JSONObject().put("checkoutId",f.checkout)).getInt("changedCount"));
        }
    }
    @Test public void newFileRequiresAbsentHashThenReviewedTargetVersionZero()throws Exception {
        try(Fixture f=new Fixture()){
            f.local.handle("writeFile",args(f.checkout,"src/new.txt").put("content","new file\n").put("expectedHash","absent"));
            JSONObject review=f.local.handle("reviewFile",args(f.checkout,"src/new.txt"));assertEquals(0,review.getInt("expectedVersion"));f.local.handle("importToProject",review);
            assertEquals("new file\n",f.projects.handle("read",new JSONObject().put("projectId",f.project).put("path","src/new.txt")).getString("content"));
        }
    }
    @Test public void collectionCreatesOneNewRetainedCheckoutAndNeverMutatesOriginal()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject source=f.local.snapshot().getJSONArray("checkouts").getJSONObject(0);
            String record=Base64.getEncoder().encodeToString("main.py".getBytes(StandardCharsets.UTF_8))+":"+Base64.getEncoder().encodeToString("print(3)\n".getBytes(StandardCharsets.UTF_8));
            File receipt=new File(f.context.getFilesDir(),"termux-receipts/collection-one.json");JsonFiles.write(receipt,new JSONObject().put("id","collection-one").put("operationId","collection-one").put("kind","workspace_collect").put("checkoutId",f.checkout).put("status","completed").put("receiptReceived",true).put("exitCode",0).put("stdout","POCKETWORLD_TEXT_1\n"+record+"\nEND:1\n").put("sourceCheckout",source));
            JSONObject input=new JSONObject().put("checkoutId",f.checkout).put("jobId","collection-one"),newCheckout=f.local.handle("termux.materialize",input);
            assertNotEquals(f.checkout,newCheckout.getString("id"));assertEquals(newCheckout.getString("id"),f.local.handle("termux.materialize",input).getString("id"));
            assertEquals("print(1)\n",f.original());assertEquals("print(1)\n",f.local.handle("readFile",args(f.checkout,"main.py")).getString("content"));
            JSONObject review=f.local.handle("reviewFile",args(newCheckout.getString("id"),"main.py"));assertTrue(review.getString("diff").contains("+print(3)"));f.local.handle("importToProject",review);assertEquals("print(3)\n",f.original());
        }
    }
    @Test public void collectionCannotBeReboundToDifferentCheckoutOrUseTruncatedBytes()throws Exception {
        try(Fixture f=new Fixture()){
            File receipt=new File(f.context.getFilesDir(),"termux-receipts/truncated.json");JSONObject value=new JSONObject().put("id","truncated").put("kind","workspace_collect").put("checkoutId",f.checkout).put("status","completed").put("receiptReceived",true).put("exitCode",0).put("outputTruncated",true).put("stdout","POCKETWORLD_TEXT_1\nEND:0\n");JsonFiles.write(receipt,value);
            try{f.local.handle("termux.materialize",new JSONObject().put("checkoutId","wrong").put("jobId","truncated"));fail();}catch(SecurityException expected){}
            try{f.local.handle("termux.materialize",new JSONObject().put("checkoutId",f.checkout).put("jobId","truncated"));fail();}catch(IllegalStateException expected){}
            assertEquals(1,f.local.snapshot().getJSONArray("checkouts").length());
        }
    }
}
