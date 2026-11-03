package dev.pocketengine.ui;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import androidx.activity.ComponentActivity;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.platform.ComposeView;
import androidx.compose.ui.semantics.AccessibilityAction;
import androidx.compose.ui.semantics.SemanticsActions;
import androidx.compose.ui.semantics.SemanticsNode;
import androidx.compose.ui.semantics.SemanticsOwner;
import androidx.compose.ui.semantics.SemanticsProperties;
import androidx.compose.ui.text.AnnotatedString;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function1;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.util.concurrent.atomic.AtomicReference;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual existing WorkProfilesPage and original typed App/project admission. Semantics only,
 * no screenshot/PixelCopy/pixel assertions; not visual, live-provider or team-quality acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WorkProfileProjectMountedNativeTest {
    public static final class FixtureActivity extends ComponentActivity { }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void complete(Function1 callback,JSONObject value,Exception failure) {
        try {
            Object result=failure==null?value:kotlin.ResultKt.createFailure(failure);
            Object boxed=kotlin.Result.class.getDeclaredMethod("box-impl",Object.class).invoke(null,result);
            callback.invoke(boxed);
        }catch(Exception invalid){throw new AssertionError(invalid);}
    }
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final AtomicInteger posts=new AtomicInteger(),starts=new AtomicInteger();
        final AtomicReference<JSONObject> result=new AtomicReference<>();
        final AtomicReference<JSONObject> projectForReview=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final HttpServer server;final String project,checkout;
        Fixture()throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            try {
                server.createContext("/",exchange->{try{
                    posts.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    assertEquals("native-page-main",request.getString("model"));
                    byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop")
                        .put("message",new JSONObject().put("role","assistant").put("content","Only the native source was selected; no team or file effect is claimed."))))
                        .put("usage",new JSONObject().put("prompt_tokens",2).put("completion_tokens",1)).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                }catch(Throwable problem){failure.compareAndSet(null,problem);}finally{exchange.close();}});server.start();
                JSONObject connection=app.settings.saveApiConnection("",0,"Native mounted Profile source",new JSONObject()
                    .put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("provider","openai_chat")
                    .put("model","native-page-main").put("sendScreenshots",false).put("maxSteps",8));
                app.settings.selectApiConnection(connection.getString("id"),1);
                project=app.features.module("workspace").handle("create",new JSONObject().put("name","Mounted Profile original project")).getString("id");
                app.features.module("workspace").handle("write",new JSONObject().put("projectId",project).put("path","original.txt").put("content","UNCHANGED").put("expectedVersion",0));
                checkout=((LocalModule)app.features.module("local")).openProjectForOwner(project).getString("id");
                projectForReview.set(new JSONObject().put("projectId",project).put("checkoutId",checkout));
                worker.submit(()->{try{
                    var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);
                    try {JSONObject definition=WorkProfile.definition("mounted-draft",1,"Mounted original member Profile");
                        JSONObject target=ModelTargetRef.exact(connection.getString("id"),"native-page-main").toJson();
                        definition.getJSONObject("modelPolicies").put("MAIN",target).put("SUMMARY",target).put("MEMBER",target);
                        page.save("",0,"Mounted original member Profile",definition);
                    }finally{page.close();}
                    return null;
                }catch(Exception failed){throw new IllegalStateException(failed);}}).get(10,TimeUnit.SECONDS);
            }catch(Exception|Error failed){close();throw failed;}
        }
        @Override public void close()throws Exception {
            worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);server.stop(0);app.onTerminate();app.features.close();
        }
    }
    private static final class Screen implements AutoCloseable {
        final ActivityController<FixtureActivity> owner=Robolectric.buildActivity(FixtureActivity.class).setup().visible();
        final FixtureActivity activity=owner.get();
        final int width=Math.round(360*activity.getResources().getDisplayMetrics().density),height=Math.round(800*activity.getResources().getDisplayMetrics().density);
        Screen(Fixture f)throws Exception {try{
            NativeWorkProfileHost host=f.app.nativeWorkProfiles();
            ComposeView view=new ComposeView(activity);view.setContent((composer,flags)->{
                WorkProfilesPageKt.WorkProfilesPage(host,r->{f.worker.execute(r);return Unit.INSTANCE;},r->{f.worker.execute(r);return Unit.INSTANCE;},
                    (goal,selection,control,callback)->{f.failure.compareAndSet(null,new AssertionError("Member Profile must use the exact project route"));return Unit.INSTANCE;},
                    task->Unit.INSTANCE,domain->Unit.INSTANCE,()->Unit.INSTANCE,
                    ()->{try{JSONObject source=f.projectForReview.get();return f.app.reviewOwnerWorkProfileProject(source.getString("projectId"),source.getString("checkoutId"),"managed_process_v1");}catch(Exception error){throw new IllegalStateException(error);}},
                    (goal,selection,control,project,callback)->{f.starts.incrementAndGet();try{
                        f.app.runOwnerWorkProfileAsync(goal,"",null,null,selection,control,project,(value,error)->{
                            if(error!=null)f.failure.compareAndSet(null,error);if(value!=null)f.result.set(value);complete(callback,value,error);
                        });
                    }catch(Exception error){f.failure.compareAndSet(null,error);complete(callback,null,error);}return Unit.INSTANCE;},composer,0,0);
                return Unit.INSTANCE;
            });activity.setContentView(view);advance();
        }catch(Exception|Error error){close();throw error;}}
        private View compose(View view) {
            if (view.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView")) {
                return view;
            }
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    View found = compose(group.getChildAt(i));
                    if (found != null) return found;
                }
            }
            return null;
        }

        private void collect(SemanticsNode node, List<SemanticsNode> out) {
            out.add(node);
            for (SemanticsNode child : node.getChildren()) collect(child, out);
        }

        List<SemanticsNode> nodes() throws Exception {
            List<SemanticsNode> out = new ArrayList<>();
            for (View root : WindowInspector.getGlobalWindowViews()) {
                if (!root.isAttachedToWindow() || root.getVisibility() != View.VISIBLE) continue;
                View composed = compose(root);
                if (composed != null) {
                    SemanticsOwner semantics = (SemanticsOwner) composed.getClass()
                            .getMethod("getSemanticsOwner").invoke(composed);
                    collect(semantics.getRootSemanticsNode(), out);
                }
            }
            return out;
        }

        String text(SemanticsNode node) {
            var key = SemanticsProperties.INSTANCE.getText();
            if (!node.getConfig().contains(key)) return "";
            StringBuilder out = new StringBuilder();
            for (AnnotatedString value : node.getConfig().get(key)) {
                if (out.length() > 0) out.append(' ');
                out.append(value.getText());
            }
            return out.toString();
        }

        void advance() {
            for (int i = 0; i < 5; i++) {
                for(int task=0;task<12;task++)Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
                View root = activity.getWindow().getDecorView();
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                root.invalidate();
                try { Thread.sleep(5); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(stopped);
                }
            }
        }

        void await(String label) throws Exception {
            for (int i = 0; i < 70; i++) {
                if (nodes().stream().anyMatch(node -> text(node).contains(label))) return;
                advance();
            }
            throw new AssertionError("Missing model-page semantics: " + label + "; "
                    + nodes().stream().map(this::text).toList());
        }

        void click(String label) throws Exception {
            for (SemanticsNode node : nodes()) {
                var key = SemanticsActions.INSTANCE.getOnClick();
                if (!text(node).equals(label) || !node.getConfig().contains(key)) continue;
                AccessibilityAction<?> click = node.getConfig().get(key);
                if (click.getAction() == null) continue;
                assertEquals(Boolean.TRUE, ((Function0<?>) click.getAction()).invoke());
                advance();
                return;
            }
            throw new AssertionError("Missing click: " + label);
        }


        SemanticsNode tagged(String tag)throws Exception {
            var key=SemanticsProperties.INSTANCE.getTestTag();
            for(SemanticsNode node:nodes())if(node.getConfig().contains(key)&&tag.equals(node.getConfig().get(key)))return node;
            throw new AssertionError("Missing native Profile control "+tag);
        }
        void awaitTag(String tag)throws Exception {for(int i=0;i<100;i++){try{tagged(tag);return;}catch(AssertionError waiting){advance();}}throw new AssertionError("Missing "+tag);}
        Function0<?> action(String tag)throws Exception {
            SemanticsNode node=tagged(tag);var key=SemanticsActions.INSTANCE.getOnClick();assertTrue(node.getConfig().contains(key));return (Function0<?>)node.getConfig().get(key).getAction();
        }
        void clickTag(String tag)throws Exception {assertEquals(Boolean.TRUE,action(tag).invoke());advance();}
        void goal()throws Exception {
            var action=tagged("work_profile_goal").getConfig().get(SemanticsActions.INSTANCE.getSetText());
            assertEquals(Boolean.TRUE,((Function1<AnnotatedString,?>)action.getAction()).invoke(new AnnotatedString("Complete this exact native original project source",java.util.List.of(),java.util.List.of())));advance();
        }
        boolean disabled(String tag)throws Exception{return tagged(tag).getConfig().contains(SemanticsProperties.INSTANCE.getDisabled());}
        void ready()throws Exception {await("选择与预检");click("选择与预检");awaitTag("work_profile_preflight");clickTag("work_profile_preflight");awaitTag("work_profile_goal");goal();}
        @Override public void close(){owner.pause().stop().destroy();for(int i=0;i<20;i++)Shadows.shadowOf(Looper.getMainLooper()).runOneTask();}
    }
    @Test public void existingPageRequiresExactOriginalProjectAndOwnerConsentBeforeItsTypedAppRoute()throws Exception {
        try(Fixture f=new Fixture();Screen ui=new Screen(f)){
            ui.ready();assertTrue(ui.disabled("work_profile_start_exact"));assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
            ui.clickTag("work_profile_owner_consent");assertTrue(ui.disabled("work_profile_start_exact"));assertEquals(0,f.starts.get());
            ui.clickTag("work_profile_choose_original_project");ui.await("原项目 "+f.project);
            assertThrows(AssertionError.class,()->ui.tagged("work_profile_start_exact"));
            assertThrows(AssertionError.class,()->ui.tagged("work_profile_owner_consent"));
            assertEquals(0,f.app.tasks.list().length());assertEquals(0,f.posts.get());
            ui.clickTag("work_profile_preflight");ui.awaitTag("work_profile_goal");ui.goal();
            assertTrue(ui.disabled("work_profile_start_exact"));assertEquals(0,f.starts.get());
            ui.clickTag("work_profile_owner_consent");assertFalse(ui.disabled("work_profile_start_exact"));ui.clickTag("work_profile_start_exact");
            for(int i=0;i<120&&f.result.get()==null&&f.failure.get()==null;i++)ui.advance();
            if(f.failure.get()!=null)throw new AssertionError(f.failure.get());assertNotNull(f.result.get());assertEquals(1,f.starts.get());assertEquals(1,f.posts.get());
            JSONObject task=f.app.tasks.get(f.result.get().getString("taskId"),false);assertEquals(f.project,task.getJSONObject("developmentContext").getString("projectId"));
            assertEquals(f.checkout,task.getJSONObject("developmentContext").getString("checkoutId"));assertEquals(1,f.app.tasks.inferenceUsage(task.getString("id")).getInt("attempts"));
            assertEquals("UNCHANGED",f.app.features.module("workspace").handle("read",new JSONObject().put("projectId",f.project).put("path","original.txt")).getString("content"));
        }
    }
    @Test public void oldStartCallbackCannotOutliveProjectWithdrawalOrNativeForegroundConsent()throws Exception {
        try(Fixture f=new Fixture();Screen ui=new Screen(f)){
            ui.ready();ui.clickTag("work_profile_choose_original_project");ui.await("原项目 "+f.project);
            ui.clickTag("work_profile_preflight");ui.awaitTag("work_profile_goal");ui.goal();ui.clickTag("work_profile_owner_consent");
            assertFalse(ui.disabled("work_profile_start_exact"));Function0<?> old=ui.action("work_profile_start_exact");
            String replacement=f.app.features.module("workspace").handle("create",new JSONObject().put("name","Mounted replacement original project")).getString("id");
            f.app.features.module("workspace").handle("write",new JSONObject().put("projectId",replacement).put("path","original.txt").put("content","SECOND_UNCHANGED").put("expectedVersion",0));
            String replacementCheckout=((LocalModule)f.app.features.module("local")).openProjectForOwner(replacement).getString("id");
            f.projectForReview.set(new JSONObject().put("projectId",replacement).put("checkoutId",replacementCheckout));
            ui.clickTag("work_profile_choose_original_project");ui.await("原项目 "+replacement);
            assertThrows(AssertionError.class,()->ui.tagged("work_profile_start_exact"));
            assertThrows(AssertionError.class,()->ui.tagged("work_profile_owner_consent"));
            assertEquals(Boolean.TRUE,old.invoke());f.worker.submit(()->null).get(10,TimeUnit.SECONDS);ui.advance();assertEquals(0,f.starts.get());assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
            ui.clickTag("work_profile_preflight");ui.awaitTag("work_profile_goal");ui.goal();
            assertTrue(ui.disabled("work_profile_start_exact"));
            assertEquals(Boolean.TRUE,old.invoke());f.worker.submit(()->null).get(10,TimeUnit.SECONDS);ui.advance();assertEquals(0,f.starts.get());assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
            ui.clickTag("work_profile_owner_consent");assertFalse(ui.disabled("work_profile_start_exact"));Function0<?> replacementStart=ui.action("work_profile_start_exact");
            ui.click("不关联项目");ui.await("选择当前原项目工作台副本（项目技能与团队必选）");
            assertEquals(Boolean.TRUE,replacementStart.invoke());f.worker.submit(()->null).get(10,TimeUnit.SECONDS);ui.advance();assertEquals(0,f.starts.get());assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
            f.projectForReview.set(new JSONObject().put("projectId",f.project).put("checkoutId",f.checkout));
            ui.clickTag("work_profile_choose_original_project");ui.await("原项目 "+f.project);
            ui.clickTag("work_profile_preflight");ui.awaitTag("work_profile_goal");ui.goal();ui.clickTag("work_profile_owner_consent");
            assertFalse(ui.disabled("work_profile_start_exact"));Function0<?> foreground=ui.action("work_profile_start_exact");
            ui.owner.pause();ui.advance();assertEquals(Boolean.TRUE,foreground.invoke());f.worker.submit(()->null).get(10,TimeUnit.SECONDS);ui.advance();assertEquals(0,f.starts.get());assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
            assertNull(f.failure.get());
        }
    }
}
