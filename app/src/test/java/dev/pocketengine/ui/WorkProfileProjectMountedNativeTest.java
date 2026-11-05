package dev.pocketengine.ui;

import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import dev.pocketengine.ui.web.PocketWebBridge;
import dev.pocketengine.ui.web.PocketWebWindowTicket;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import kotlin.Unit;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual Web bridge -> native Review/Selection -> typed App/project admission and loopback inference. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,
        shadows={ReadyApplicationFixture.NoUnrelatedStartup.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WorkProfileProjectMountedNativeTest {
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final Provider keys=new Provider("ProfileBridge-"+UUID.randomUUID(),1.0,"Explicit test-only AndroidKeyStore") {
            {put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}
        };
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final AtomicInteger posts=new AtomicInteger();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final HttpServer server;
        final String project,checkout,profile;
        final CountDownLatch releaseModel;
        Fixture()throws Exception {this(false);}
        Fixture(boolean holdModel)throws Exception {
            releaseModel=new CountDownLatch(holdModel?1:0);
            assertTrue(Security.insertProviderAt(keys,1)>0);ReadyApplicationFixture.initializeHost(app);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                posts.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                assertEquals("native-page-main",request.getString("model"));assertTrue(releaseModel.await(15,TimeUnit.SECONDS));
                byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop")
                    .put("message",new JSONObject().put("role","assistant").put("content","Only the original source was selected; no team or file effect is claimed."))))
                    .put("usage",new JSONObject().put("prompt_tokens",2).put("completion_tokens",1)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Throwable problem){failure.compareAndSet(null,problem);}finally{exchange.close();}});server.start();
            JSONObject connection=app.settings.saveApiConnection("",0,"Original Profile source",new JSONObject()
                .put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("provider","openai_chat")
                .put("model","native-page-main").put("sendScreenshots",false).put("maxSteps",8));
            app.settings.selectApiConnection(connection.getString("id"),1);
            project=app.features.module("workspace").handle("create",new JSONObject().put("name","Original Profile project")).getString("id");
            app.features.module("workspace").handle("write",new JSONObject().put("projectId",project).put("path","original.txt").put("content","UNCHANGED").put("expectedVersion",0));
            checkout=((LocalModule)app.features.module("local")).openProjectForOwner(project).getString("id");
            profile=worker.submit(()->{
                var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);
                try{JSONObject definition=WorkProfile.definition("draft-profile",1,"Original member Profile");
                    JSONObject target=ModelTargetRef.exact(connection.getString("id"),"native-page-main").toJson();
                    definition.getJSONObject("modelPolicies").put("MAIN",target).put("SUMMARY",target).put("MEMBER",target);
                    return page.save("",0,"Original member Profile",definition).getString("profileId");
                }finally{page.close();}
            }).get(10,TimeUnit.SECONDS);
        }
        @Override public void close()throws Exception {
            releaseModel.countDown();worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);server.stop(0);
            try{ReadyApplicationFixture.closeHost(app);}finally{Security.removeProvider(keys.getName());}
        }
    }
    private static final class Screen implements AutoCloseable {
        final Fixture fixture;
        final ActivityController<ComposeActivity> owner=Robolectric.buildActivity(ComposeActivity.class);
        final ComposeActivity activity=owner.get();
        final MediaController media;
        final PocketWorldController controller;
        final PocketWebBridge bridge;
        final List<JSONObject> events=new ArrayList<>();
        int sequence;
        Screen(Fixture fixture)throws Exception {
            this.fixture=fixture;media=new MediaController(activity,fixture.app::publish);
            Field field=ComposeActivity.class.getDeclaredField("media");field.setAccessible(true);field.set(activity,media);
            controller=new PocketWorldController(activity,fixture.app);
            field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);field.set(activity,controller);
            controller.attach();foreground(true);
            chooseProject(fixture.project,fixture.checkout);
            bridge=new PocketWebBridge(controller,event->{events.add(event);return Unit.INSTANCE;});bridge.start();
            command("app.ready",new JSONObject().put("documentId","profile-owner-document"));
            command("connections.enter",new JSONObject().put("section","workProfiles"));
        }
        Object invokeOn(Object target,String prefix,Object... args)throws Exception {
            for(Method method:target.getClass().getDeclaredMethods())if((method.getName().equals(prefix)||method.getName().startsWith(prefix+"$"))&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(target,args);}
            throw new AssertionError("No actual native method "+prefix);
        }
        void foreground(boolean visible)throws Exception {Field field=PocketWorldController.class.getDeclaredField("voiceForeground");field.setAccessible(true);field.setBoolean(controller,visible);}
        void chooseProject(String project,String checkout)throws Exception {
            invokeOn(invokeOn(controller,"getProjectWorkbench"),"selectCheckout",project,checkout,true);controller.selectProject(project);advance();
        }
        ExecutorService io()throws Exception {Field field=PocketWorldController.class.getDeclaredField("io");field.setAccessible(true);return (ExecutorService)field.get(controller);}
        void advance(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));try{Thread.sleep(10);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new AssertionError(stopped);}}
        void until(String label,BooleanSupplier condition){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(condition.getAsBoolean())return;advance();}throw new AssertionError("Missing "+label+"; "+controller.getState().getModuleErrors());}
        String enqueue(String action,JSONObject args)throws Exception {
            String id="profile-command-"+(++sequence);
            JSONObject request=new JSONObject().put("v",1).put("id",id).put("action",action).put("args",args).put("documentId","profile-owner-document");
            var window=(PocketWebWindowTicket)invokeOn(bridge,"captureWindow");
            bridge.postMessage(request.toString(),window,()->{throw new AssertionError("Original bridge window unexpectedly changed");});
            Shadows.shadowOf(Looper.getMainLooper()).idle();return id;
        }
        JSONObject reply(String action,JSONObject args)throws Exception {
            String id=enqueue(action,args);until("original bridge reply "+action,()->events.stream().anyMatch(event->"result".equals(event.optString("kind"))&&id.equals(event.optString("id"))));
            return events.stream().filter(event->"result".equals(event.optString("kind"))&&id.equals(event.optString("id"))).findFirst().orElseThrow();
        }
        JSONObject command(String action,JSONObject args)throws Exception {JSONObject reply=reply(action,args);assertTrue(action+": "+reply.optJSONObject("error"),reply.getBoolean("ok"));return reply.optJSONObject("value")==null?new JSONObject():reply.getJSONObject("value");}
        JSONObject preflight(boolean project)throws Exception {
            JSONObject read=command("profiles.read",new JSONObject().put("id",fixture.profile));
            JSONObject choices=command("profiles.choices",new JSONObject().put("useCurrentProject",project));
            if(project){assertEquals(controller.getState().getProjectId(),choices.getJSONObject("project").getString("projectId"));assertFalse(choices.getJSONObject("project").getBoolean("commandsAuthorized"));}
            return command("profiles.preflight",new JSONObject().put("viewHandle",read.getString("viewHandle")).put("choicesHandle",choices.getString("choicesHandle")));
        }
        JSONObject review(JSONObject preflight)throws Exception {return command("profiles.reviewStart",new JSONObject().put("preflightHandle",preflight.getString("preflightHandle")).put("goal","Complete this exact original project source"));}
        JSONObject confirm(JSONObject review)throws Exception {return command("profiles.confirm",new JSONObject().put("reviewHandle",review.getString("reviewHandle")).put("consent",true));}
        void rejectStart(JSONObject review)throws Exception {assertFalse(reply("profiles.confirm",new JSONObject().put("reviewHandle",review.getString("reviewHandle")).put("consent",true)).getBoolean("ok"));}
        @Override public void close()throws Exception {try{bridge.close();controller.detach();}finally{media.close();owner.close();}}
    }

    @Test public void existingPageRequiresExactOriginalProjectAndOwnerConsentBeforeItsTypedAppRoute()throws Exception {
        try(Fixture f=new Fixture();Screen ui=new Screen(f)){
            JSONObject defaultScope=ui.preflight(false);assertFalse(defaultScope.getBoolean("runnable"));
            assertFalse(ui.reply("profiles.reviewStart",new JSONObject().put("preflightHandle",defaultScope.getString("preflightHandle")).put("goal","Original project task")).getBoolean("ok"));
            assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
            JSONObject projectScope=ui.preflight(true);assertTrue(projectScope.getBoolean("runnable"));
            assertEquals(f.project,projectScope.getJSONObject("project").getString("projectId"));assertEquals(f.checkout,projectScope.getJSONObject("project").getString("checkoutId"));
            assertFalse(ui.reply("profiles.reviewStart",new JSONObject().put("preflightHandle",defaultScope.getString("preflightHandle")).put("goal","Old scope cannot be reused")).getBoolean("ok"));
            JSONObject review=ui.review(projectScope);
            assertFalse(ui.reply("profiles.confirm",new JSONObject().put("reviewHandle",review.getString("reviewHandle"))).getBoolean("ok"));
            assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
            JSONObject receipt=ui.confirm(review);if(f.failure.get()!=null)throw new AssertionError(f.failure.get());
            assertEquals("start",receipt.getString("command"));assertEquals(f.profile,receipt.getString("profileId"));assertEquals(1,f.posts.get());assertEquals(1,f.app.tasks.list().length());
            JSONObject task=f.app.tasks.get(receipt.getJSONObject("result").getString("taskId"),false);
            assertEquals(f.project,task.getJSONObject("developmentContext").getString("projectId"));assertEquals(f.checkout,task.getJSONObject("developmentContext").getString("checkoutId"));
            assertEquals(1,f.app.tasks.inferenceUsage(task.getString("id")).getInt("attempts"));
            assertEquals("UNCHANGED",f.app.features.module("workspace").handle("read",new JSONObject().put("projectId",f.project).put("path","original.txt")).getString("content"));
            ui.rejectStart(review);assertEquals(1,f.posts.get());assertEquals(1,f.app.tasks.list().length());
        }
    }

    @Test public void oldStartCallbackCannotOutliveProjectWithdrawalOrNativeForegroundConsent()throws Exception {
        try(Fixture f=new Fixture();Screen ui=new Screen(f)){
            JSONObject old=ui.review(ui.preflight(true));
            String replacement=f.app.features.module("workspace").handle("create",new JSONObject().put("name","Replacement original project")).getString("id");
            f.app.features.module("workspace").handle("write",new JSONObject().put("projectId",replacement).put("path","original.txt").put("content","SECOND_UNCHANGED").put("expectedVersion",0));
            String checkout=((LocalModule)f.app.features.module("local")).openProjectForOwner(replacement).getString("id");
            ui.chooseProject(replacement,checkout);ui.command("profiles.choices",new JSONObject().put("useCurrentProject",true));ui.rejectStart(old);
            JSONObject replacementStart=ui.review(ui.preflight(true));ui.rejectStart(old);
            ui.command("profiles.choices",new JSONObject().put("useCurrentProject",false));ui.rejectStart(replacementStart);
            ui.chooseProject(f.project,f.checkout);JSONObject foreground=ui.review(ui.preflight(true));
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            ui.io().execute(()->{entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            try{ui.enqueue("profiles.confirm",new JSONObject().put("reviewHandle",foreground.getString("reviewHandle")).put("consent",true));ui.foreground(false);ui.bridge.invalidatePresentation(false);}
            finally{release.countDown();}
            ui.io().submit(()->{}).get(5,TimeUnit.SECONDS);ui.advance();
            assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());assertNull(f.failure.get());
        }
    }

    @Test public void leavingAfterOriginalRunAdmissionDoesNotStopOrReplayTheAuthorizedTask()throws Exception {
        try(Fixture f=new Fixture(true);Screen ui=new Screen(f)){
            JSONObject review=ui.review(ui.preflight(true));ui.enqueue("profiles.confirm",new JSONObject().put("reviewHandle",review.getString("reviewHandle")).put("consent",true));
            ui.until("original model call",()->f.posts.get()==1);assertEquals(1,f.app.tasks.list().length());
            ui.foreground(false);ui.bridge.invalidatePresentation(false);f.releaseModel.countDown();
            ui.until("original runner completion",()->{try{return !"running".equals(f.app.tasks.get(f.app.tasks.list().getJSONObject(0).getString("id"),false).optString("status"));}catch(Exception e){throw new AssertionError(e);}});
            JSONObject task=f.app.tasks.get(f.app.tasks.list().getJSONObject(0).getString("id"),false);
            assertFalse(task.optBoolean("cancelRequested"));assertEquals(1,f.posts.get());assertEquals(1,f.app.tasks.inferenceUsage(task.getString("id")).getInt("attempts"));assertNull(f.failure.get());
        }
    }
}
