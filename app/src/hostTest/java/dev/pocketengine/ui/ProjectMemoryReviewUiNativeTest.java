package dev.pocketengine.ui;

import android.app.Application;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.ProjectMemoryNamespaces;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual Controller -> original App/native host/Bridge/VWS/SQLite. Not Compose visual acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ProjectMemoryReviewUiNativeTest {
    private static final String WORDS="项目原文：保留约束与选择理由，不能只记最终结论。";
    private static JSONObject owner()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    static final class Fixture implements AutoCloseable {
        final EngineApplication app=new EngineApplication();final PocketWorldController controller;final ExecutorService io;final android.database.sqlite.SQLiteDatabase db;
        final WorkspaceModule workspace;final LocalModule local;final MemoryModule memory;final ProjectMemoryNamespaces nativeScopes;
        Fixture()throws Exception{
            Method attach=Application.class.getDeclaredMethod("attach",android.content.Context.class);attach.setAccessible(true);attach.invoke(app,RuntimeEnvironment.getApplication());app.deleteDatabase("pocket-world.db");app.settings=new SettingsStore(app);app.tasks=new TaskStore(app);var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);db=(android.database.sqlite.SQLiteDatabase)database.get(app.tasks);app.features=new FeatureHost(event->{},app.tasks,()->false);
            FeatureContext context=new FeatureContext(app,app.settings,new SecretVault(app),event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);app.features.register(workspace);app.features.register(local);app.features.register(new dev.pocketengine.features.attachments.AttachmentsModule(context));MemorySourceBridge sources=new MemorySourceBridge(app.features,app.tasks);sources.bindWorkspace(workspace);memory=new MemoryModule(context,sources.assertionResolver());app.features.register(memory);app.features.register(new SkillsModule(context,memory::resolveExperience));app.features.setMemorySynchronizer(sources::synchronize);app.nativeOwnerWorkflows=new NativeOwnerWorkflows(app.features,context,app.tasks,sources);nativeScopes=app.nativeOwnerWorkflows.projectMemoryForOwner();controller=new PocketWorldController(new ComposeActivity(),app);var field=PocketWorldController.class.getDeclaredField("io");field.setAccessible(true);io=(ExecutorService)field.get(controller);
        }
        JSONObject create(String name)throws Exception{
            String id=workspace.handle("create",new JSONObject().put("name",name)).getString("id");workspace.handle("write",new JSONObject().put("projectId",id).put("path","notes.txt").put("content",WORDS).put("expectedVersion",0));JSONObject checkout=local.handle("prepare",new JSONObject().put("projectId",id));JSONArray choices=nativeScopes.choicesForOwner();for(int i=0;i<choices.length();i++){JSONObject row=choices.getJSONObject(i);if(checkout.getString("id").equals(row.getString("checkoutId")))return row;}throw new AssertionError("missing actual project choice");
        }
        void enter()throws Exception{controller.enterOwnerDomain("memoryReview");settle();}
        void settle()throws Exception{io.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        void choose(JSONObject choice)throws Exception{controller.selectMemoryNamespace(choice);settle();}
        JSONObject snapshot(){return controller.getState().ownerSnapshot("memoryReview");}
        JSONObject response(String command){return controller.getState().ownerResponses("memoryReview").get(command);}
        JSONObject request(String command,JSONObject payload)throws Exception{controller.requestOwnerDomain("memoryReview",command,payload);settle();return response(command);}
        private Object diagnosticField(Object target,String name)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        private int diagnosticEpoch()throws Exception{return ((java.util.Map<String,Integer>)diagnosticField(controller,"ownerEpochs")).getOrDefault("memoryReview",0);}
        private String missingReplyDiagnostic(String command,String expectedId,int beforeEpoch)throws Exception{
            var requests=(java.util.Map<?,?>)diagnosticField(controller,"ownerRequests");Object pending=requests.get("memoryReview."+command);
            boolean matches=pending!=null&&expectedId.equals(diagnosticField(pending,"id"));
            boolean epochMatches=pending!=null&&((Integer)diagnosticField(pending,"epoch"))==diagnosticEpoch();
            return "no native reply; closed="+diagnosticField(controller,"closed")
                    +"; epochUnchanged="+(beforeEpoch==diagnosticEpoch())
                    +"; pending="+(pending!=null)+"; requestMatches="+matches+"; pendingEpochMatches="+epochMatches
                    +"; callbackPostOrHandled=unobserved";
        }
        JSONObject result(String command,JSONObject payload)throws Exception{
            int beforeEpoch=diagnosticEpoch();String expectedId=controller.requestOwnerDomain("memoryReview",command,payload);settle();JSONObject reply=response(command);
            assertTrue(reply==null?missingReplyDiagnostic(command,expectedId,beforeEpoch):reply.toString(),reply!=null&&reply.getBoolean("ok"));return reply.getJSONObject("result");
        }
        ProjectMemoryNamespaces.Selection token()throws Exception{var field=PocketWorldController.class.getDeclaredField("memoryReviewScope");field.setAccessible(true);Object scope=field.get(controller);var getter=scope.getClass().getDeclaredMethod("getSelected");getter.setAccessible(true);return (ProjectMemoryNamespaces.Selection)getter.invoke(scope);}
        JSONObject file()throws Exception{return result("projectFileChoices",new JSONObject()).getJSONArray("files").getJSONObject(0);}
        JSONObject propose(JSONObject file)throws Exception{return result("proposeProjectFile",new JSONObject().put("path",file.getString("path")).put("version",file.getInt("version")).put("revisionId",file.getString("revisionId")).put("sha256",file.getString("sha256")).put("offset",0).put("chars",4000));}
        public void close(){controller.detach();app.onTerminate();db.close();}
    }
    @Test public void ownerCanSelectRealProjectProposeReviewAndForgetWithoutPrivateFallback()throws Exception{try(Fixture f=new Fixture()){
        JSONObject choice=f.create("当前项目");f.enter();assertEquals("private",f.snapshot().getString("namespaceStatus"));f.choose(choice);assertEquals("project",f.snapshot().getString("namespaceStatus"));assertEquals(choice.getString("spaceId"),f.snapshot().getJSONObject("scope").getString("spaceId"));JSONObject file=f.file();assertEquals("text",file.getString("kind"));assertTrue(file.getLong("sizeBytes")>0);JSONObject result=f.propose(file),candidate=result.getJSONObject("candidate");assertFalse(result.getBoolean("modelCalled"));assertEquals(WORDS,candidate.getString("text"));assertEquals("pending",candidate.getString("status"));JSONObject record=f.result("acceptCandidate",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision"))).getJSONObject("record");assertEquals(choice.getString("spaceId"),record.getString("spaceId"));assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",owner())).getJSONArray("records").length());
        JSONObject source=record.getJSONArray("sourceRefs").getJSONObject(0);f.controller.openOwnerSource(source);f.settle();assertEquals(WORDS,f.controller.getState().getOwnerSource().getString("text"));f.result("forgetReviewed",new JSONObject().put("id",record.getString("id")).put("expectedRevision",record.getLong("revision")));f.controller.openOwnerSource(source);f.settle();assertFalse(f.response("openSource").getBoolean("ok"));assertNull(f.controller.getState().getOwnerSource());assertEquals(0,f.result("list",new JSONObject()).getJSONArray("records").length());
    }}
    @Test public void switchingProjectClearsOldRepliesAndRevokesTheExactOldNativeToken()throws Exception{try(Fixture f=new Fixture()){
        JSONObject first=f.create("项目甲"),second=f.create("项目乙");f.enter();f.choose(first);f.result("projectFileChoices",new JSONObject());ProjectMemoryNamespaces.Selection old=f.token();f.controller.selectMemoryNamespace(second);assertEquals("changing",f.snapshot().getString("namespaceStatus"));assertTrue(f.controller.getState().ownerResponses("memoryReview").isEmpty());assertFalse(old.metadata().getBoolean("selectionCurrent"));f.settle();assertEquals(second.getString("spaceId"),f.snapshot().getJSONObject("scope").getString("spaceId"));assertThrows(SecurityException.class,()->f.nativeScopes.handleForOwner(old,"list",new JSONObject()));assertEquals(0,f.result("list",new JSONObject()).getJSONArray("records").length());
    }}
    @Test public void invalidScopeIsUnavailableNotAnEmptyOrOwnerGlobalBook()throws Exception{try(Fixture f=new Fixture()){
        JSONObject choice=f.create("项目甲");f.memory.handle("save",new JSONObject().put("record",new JSONObject().put("text","原主人全局敏感记忆")));f.enter();JSONObject wrong=new JSONObject(choice.toString()).put("projectId","22222222-2222-2222-2222-222222222222");f.choose(wrong);assertFalse(f.snapshot().getBoolean("available"));assertEquals("unavailable",f.snapshot().getString("namespaceStatus"));assertFalse(f.snapshot().has("records"));assertThrows(SecurityException.class,()->f.controller.requestOwnerDomain("memoryReview","list",new JSONObject()));
        f.choose(null);assertEquals("private",f.snapshot().getString("namespaceStatus"));assertEquals("原主人全局敏感记忆",f.result("list",new JSONObject()).getJSONArray("records").getJSONObject(0).getString("text"));
    }}
    @Test public void leavingPageErasesMetadataViewAndNeverRestoresProjectAuthority()throws Exception{try(Fixture f=new Fixture()){
        JSONObject choice=f.create("项目甲");f.enter();f.choose(choice);ProjectMemoryNamespaces.Selection token=f.token();f.controller.leaveOwnerDomain("memoryReview");assertFalse(token.metadata().getBoolean("selectionCurrent"));assertFalse(f.controller.getState().getOwnerSnapshots().containsKey("memoryReview"));f.enter();assertEquals("private",f.snapshot().getString("namespaceStatus"));assertThrows(SecurityException.class,()->f.nativeScopes.handleForOwner(token,"snapshot",new JSONObject()));
    }}
    @Test public void queuedOldReadCannotPopulateTheNewProjectScope()throws Exception{try(Fixture f=new Fixture()){
        JSONObject first=f.create("项目甲"),second=f.create("项目乙");f.enter();f.choose(first);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.io.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));try{f.controller.requestOwnerDomain("memoryReview","list",new JSONObject());f.controller.selectMemoryNamespace(second);}finally{release.countDown();}f.settle();assertEquals(second.getString("spaceId"),f.snapshot().getJSONObject("scope").getString("spaceId"));assertFalse(f.controller.getState().ownerResponses("memoryReview").containsKey("list"));
    }}
    @Test public void groupContextAndForgedProjectArgumentsCannotCreateReadOrLearningAuthority()throws Exception{try(Fixture f=new Fixture()){
        JSONObject choice=f.create("项目甲");f.enter();f.choose(choice);JSONObject file=f.file();JSONObject forged=new JSONObject().put("path",file.getString("path")).put("version",file.getInt("version")).put("revisionId",file.getString("revisionId")).put("sha256",file.getString("sha256")).put("offset",0).put("chars",4000).put("projectId",choice.getString("projectId"));assertFalse(f.request("proposeProjectFile",forged).getBoolean("ok"));assertEquals(0,f.result("listCandidates",new JSONObject()).getJSONArray("candidates").length());JSONObject unsupported=f.request("proposeConsolidation",new JSONObject().put("selections",new JSONArray()));assertFalse(unsupported.getBoolean("ok"));assertTrue(unsupported.getString("error").contains("项目记忆操作"));assertTrue(f.snapshot().getBoolean("taskContextSupported"));
        f.controller.leaveOwnerDomain("memoryReview");var domain=PocketWorldController.class.getDeclaredField("ownerDomain");domain.setAccessible(true);domain.set(f.controller,"groups");assertThrows(IllegalStateException.class,()->f.controller.selectMemoryNamespace(choice));domain.set(f.controller,null);
    }}
    @Test public void revokedNativeScopeRefreshShowsUnavailableAndBlocksFurtherReads()throws Exception{try(Fixture f=new Fixture()){
        JSONObject choice=f.create("项目甲");f.enter();f.choose(choice);f.workspace.close();assertFalse(f.request("snapshot",new JSONObject()).getBoolean("ok"));assertEquals("unavailable",f.snapshot().getString("namespaceStatus"));assertFalse(f.snapshot().getBoolean("available"));assertFalse(f.snapshot().has("records"));assertThrows(SecurityException.class,()->f.controller.requestOwnerDomain("memoryReview","listCandidates",new JSONObject()));
    }}
}
