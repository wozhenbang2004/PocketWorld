package dev.pocketengine.browser;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.webkit.WebView;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original TaskStore, native Profile metadata and private owner draft file. No Compose/IME/device rendering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserWorkspaceDraftSqlTest {
    static final class F implements AutoCloseable {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final String task=tasks.createCapabilityTask("原研究目标，不另建执行","").getString("id");final Context context;final BrowserModule browser;final BrowserTaskHost host;final ExecutorService io=Executors.newSingleThreadExecutor();
        F()throws Exception {File root=Files.createTempDirectory("browser-draft-owner-").toFile(),files=new File(root,"files"),privateFiles=new File(root,"no-backup"),cache=new File(root,"cache");files.mkdirs();privateFiles.mkdirs();cache.mkdirs();context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}@Override public File getNoBackupFilesDir(){return privateFiles;}@Override public File getCacheDir(){return cache;}};browser=new BrowserModule(new FeatureContext(context,null,null,event->{}));host=host(browser);}
        BrowserTaskHost host(BrowserModule module){return new BrowserTaskHost(module,tasks,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){public void checkModelConfigured(){throw new AssertionError("Viewing/restoring a text draft cannot configure inference");}public void launch(String task){throw new AssertionError("Viewing/restoring a draft cannot launch a task");}public RunControl currentControl(String task){throw new AssertionError("A text draft cannot restore original execution control");}},event->{});}
        <T>T call(Callable<T> action)throws Exception {Future<T> future=io.submit(action);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!future.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(3);}try{return future.get(1,TimeUnit.SECONDS);}catch(ExecutionException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw new AssertionError(failure.getCause());}}
        public void close()throws Exception {browser.close();Shadows.shadowOf(Looper.getMainLooper()).idle();io.shutdownNow();io.awaitTermination(2,TimeUnit.SECONDS);BrowserTaskPersistenceTest.closeDatabase(tasks);}
    }
    @Test public void selectedProfileOriginalTaskTextSurvivesModuleReopenWithoutAnyTaskOperationGrantOrInference()throws Exception {
        try(F f=new F()){int before=f.tasks.list().length();JSONObject blank=f.call(()->f.host.researchDraftForOwner("owner-default",f.task));assertEquals("",blank.getString("text"));String text="比较三个原页面🙂\n不能自动恢复旧同意";JSONObject saved=f.call(()->f.host.saveResearchDraftForOwner("owner-default",f.task,blank.getLong("revision"),text));BrowserModule reopened=new BrowserModule(new FeatureContext(f.context,null,null,event->{}));try{BrowserTaskHost owner=f.host(reopened);JSONObject loaded=f.call(()->owner.researchDraftForOwner("owner-default",f.task));assertEquals(text,loaded.getString("text"));assertEquals(saved.getLong("revision"),loaded.getLong("revision"));assertFalse(loaded.getBoolean("executionAuthorized"));assertEquals("",f.call(()->owner.researchDraftForOwner("owner-default","")).getString("text"));}finally{reopened.close();}assertEquals(before,f.tasks.list().length());JSONObject task=f.tasks.get(f.task,false);assertEquals(0,task.getJSONArray("operations").length());assertFalse(task.has("approvals"));String disk=Files.readString(new File(f.context.getNoBackupFilesDir(),"browser/research-goals.json").toPath());assertFalse(disk.contains("consent"));assertFalse(disk.contains("runId"));}
    }
    @Test public void wrongProfileMissingTaskAndForeignActualSqlTaskCannotReadOrSaveIntoAnotherContext()throws Exception {
        try(F f=new F()){JSONObject first=f.call(()->f.host.researchDraftForOwner("owner-default",f.task));f.call(()->f.host.saveResearchDraftForOwner("owner-default",f.task,first.getLong("revision"),"keep"));assertThrows(SecurityException.class,()->f.call(()->f.host.researchDraftForOwner("profile-"+UUID.randomUUID(),f.task)));assertThrows(SecurityException.class,()->f.call(()->f.host.researchDraftForOwner("owner-default",UUID.randomUUID().toString())));
            String foreign=f.tasks.createCapabilityTask("Explicit foreign-space SQL fixture","").getString("id");Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(f.tasks);JSONObject row=f.tasks.get(foreign,false).put("actorId","other-owner").put("spaceId","team-space");android.content.ContentValues value=new android.content.ContentValues();value.put("document",row.toString());assertEquals(1,db.update("tasks",value,"task_id=?",new String[]{foreign}));assertThrows(SecurityException.class,()->f.call(()->f.host.researchDraftForOwner("owner-default",foreign)));assertThrows(SecurityException.class,()->f.call(()->f.host.saveResearchDraftForOwner("owner-default",foreign,1,"wrong")));assertEquals("keep",f.call(()->f.host.researchDraftForOwner("owner-default",f.task)).getString("text"));assertThrows(Exception.class,()->f.browser.handle("research.draft.save",new JSONObject()));}
    }
    @Test public void nativeContextIntentAndOriginalTaskReturnDoNotReplaceTheExistingWebViewOrStartAnotherRun()throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            String tab=h.open("original-reading?version=1#anchor");Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);WebView before=(WebView)field.get(h.browser);JSONObject identity=h.browser.snapshot();int tasks=h.tasks.list().length(),pages=h.browser.tabsForOwner().getJSONArray("tabs").length();
            h.browser.onNewIntent(new Intent().putExtra("showResearch",true).putExtra("browserResearchTaskId",h.taskId));assertEquals(h.taskId,h.browser.researchTaskContextForOwner());h.browser.showResearchPanelForOwner(h.taskId);assertSame(before,field.get(h.browser));assertEquals(identity.getString("browserSessionId"),h.browser.snapshot().getString("browserSessionId"));assertEquals(identity.getString("profileId"),h.browser.snapshot().getString("profileId"));assertEquals(pages,h.browser.tabsForOwner().getJSONArray("tabs").length());assertEquals(tasks,h.tasks.list().length());assertEquals(tab,h.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("tabs").getJSONObject(0).getString("tabId"));
            android.content.pm.ResolveInfo launcher=new android.content.pm.ResolveInfo();launcher.activityInfo=new android.content.pm.ActivityInfo();launcher.activityInfo.packageName=h.browser.getPackageName();launcher.activityInfo.name="dev.pocketengine.ui.ComposeActivity";Shadows.shadowOf(h.browser.getPackageManager()).addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(h.browser.getPackageName()),launcher);
            h.browser.returnToTaskForOwner(h.taskId);Intent returned=Shadows.shadowOf(h.browser).getNextStartedActivity();assertEquals(h.taskId,returned.getStringExtra("quickPanelTaskId"));assertNotEquals(0,returned.getFlags()&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);assertFalse(h.browser.isFinishing());assertSame(before,field.get(h.browser));
        }
    }
}
