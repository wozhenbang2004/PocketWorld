package dev.pocketengine.features.local.project;

import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real LocalModule and VersionedWorkspace files; no substituted runner or TaskStore. */
public final class ProjectLocalModuleBoundaryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final class Fixture implements AutoCloseable {
        final File files=temp.newFolder();final VersionedWorkspace projects=new VersionedWorkspace(new File(files,"projects"));final LocalModule local;final String checkout;
        Fixture()throws Exception {
            String project=projects.create("mobile-test").getString("id");projects.write(project,"main.py","print(1)\n",0,false);
            Context app=new ContextWrapper(null){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
            EngineModule source=new EngineModule(){public String id(){return "projects";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{if(!command.equals("export"))throw new AssertionError(command);return projects.export(input.getString("projectId"));}};
            local=new LocalModule(new FeatureContext(app,null,null,event->{}),source);checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
        }
        JSONObject file()throws Exception{return local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","main.py"));}
        JSONObject snapshot()throws Exception{return local.handle("export",new JSONObject().put("checkoutId",checkout));}
        @Override public void close(){local.close();}
    }
    @Test public void realHashCasEditInvalidatesPreparedSourceBeforeTermuxDispatch()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject before=f.file();String digest=ProjectSources.describe(f.snapshot()).getString("sourceDigest");
            JSONObject edit=new JSONObject().put("checkoutId",f.checkout).put("path","main.py").put("content","print(2)\n").put("expectedHash",before.getString("sha256"));f.local.handle("writeFile",edit);
            try{f.local.handle("writeFile",new JSONObject(edit.toString()).put("content","unreviewed"));fail();}catch(IllegalStateException expected){}
            JSONObject action=new JSONObject().put("checkoutId",f.checkout).put("expectedSourceDigest",digest).put("operationId","stale_build").put("executable","python3").put("argv",new JSONArray().put("main.py"));
            try{f.local.handle("termux.run",action);fail("old source approval executed");}catch(SecurityException expected){}
            try{f.local.handle("termux.prepare",action);fail("old source approval staged");}catch(SecurityException expected){}
            assertFalse(new File(f.files,"termux-receipts/stale_build.json").isFile());assertEquals("print(2)\n",f.file().getString("content"));
        }
    }
    @Test public void unknownTermuxOperationRetainsWorkspaceLeaseAndBlocksEditsOrRestage()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.file();File receipt=new File(f.files,"termux-receipts/unknown.json");JsonFiles.write(receipt,new JSONObject().put("id","unknown").put("operationId","unknown").put("checkoutId",f.checkout).put("status","unknown"));
            try{f.local.handle("writeFile",new JSONObject().put("checkoutId",f.checkout).put("path","main.py").put("content","changed").put("expectedHash",original.getString("sha256")));fail();}catch(IllegalStateException expected){}
            try{f.local.handle("termux.prepare",new JSONObject().put("checkoutId",f.checkout).put("operationId","new_stage"));fail();}catch(IllegalStateException expected){}
            JSONObject cancel=f.local.handle("termux.cancel",new JSONObject().put("jobId","unknown"));assertEquals("unknown",cancel.getString("status"));assertTrue(cancel.getBoolean("cancelRequested"));
            try{f.snapshot();fail();}catch(IllegalStateException expected){}assertFalse(new File(f.files,"termux-receipts/new_stage.json").isFile());assertEquals(original.getString("sha256"),f.file().getString("sha256"));
        }
    }
}
