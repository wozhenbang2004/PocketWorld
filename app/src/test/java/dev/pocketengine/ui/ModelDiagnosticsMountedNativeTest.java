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
import java.time.Duration;
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

/** Actual original model page -> owner button -> Service -> original SQL/Gateway/HTTP.
 * Semantics only: no screenshots, PixelCopy, screen images or visual acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = EngineApplication.class,
        qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ModelDiagnosticsMountedNativeTest {
    public static final class FixtureActivity extends ComponentActivity { }

    private static final class Fixture implements AutoCloseable {
        final EngineApplication app = (EngineApplication) RuntimeEnvironment.getApplication();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final AtomicInteger posts = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final HttpServer server;

        Fixture(boolean block) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                server.createContext("/", exchange -> {
                    try {
                        posts.incrementAndGet();
                        JSONObject request = new JSONObject(new String(
                                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        assertEquals(1, request.getJSONArray("messages").length());
                        String prompt = request.getJSONArray("messages").getJSONObject(0)
                                .getString("content");
                        entered.countDown();
                        if (block) release.await(10, TimeUnit.SECONDS);
                        String text = request.has("tools")
                                ? "This provider fixture does not emit an echo tool"
                                : prompt.substring(prompt.lastIndexOf(' ') + 1);
                        byte[] body = new JSONObject().put("choices", new JSONArray().put(
                                new JSONObject().put("finish_reason", "stop").put("message",
                                        new JSONObject().put("role", "assistant").put("content", text))))
                                .put("usage", new JSONObject().put("prompt_tokens", 2)
                                        .put("completion_tokens", 1))
                                .toString().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } catch (Exception stopped) {
                        // The owner's native cancellation may close this fixture socket.
                    } finally {
                        exchange.close();
                    }
                });
                server.start();
                app.settings.save(new JSONObject()
                        .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                        .put("provider", "openai_chat").put("model", "fixture-ui-model")
                        .put("sendScreenshots", false));
                app.settings.apiConnections();
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            release.countDown();
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
            server.stop(0);
            app.onTerminate();
            app.features.close();
        }
    }

    private static final class Screen implements AutoCloseable {
        final ActivityController<FixtureActivity> owner =
                Robolectric.buildActivity(FixtureActivity.class).setup().visible();
        final FixtureActivity activity = owner.get();
        final int width = Math.round(360 * activity.getResources().getDisplayMetrics().density);
        final int height = Math.round(800 * activity.getResources().getDisplayMetrics().density);

        Screen(Fixture fixture) {
            try {
                ComposeView view = new ComposeView(activity);
                view.setContent(new Function2<Composer, Integer, Unit>() {
                    @Override public Unit invoke(Composer composer, Integer flags) {
                        ModelConnectionsPageKt.ModelConnectionsPage(fixture.app.modelConnections,
                                runnable -> { fixture.worker.execute(runnable); return Unit.INSTANCE; },
                                () -> Unit.INSTANCE, () -> Unit.INSTANCE, composer, 0);
                        return Unit.INSTANCE;
                    }
                });
                activity.setContentView(view);
                advance();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

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
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
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

        @Override public void close() {
            owner.pause().stop().destroy();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test public void actualModelPageRequiresExplicitOwnerClickAndShowsIndependentObservedResults()
            throws Exception {
        try (Fixture f = new Fixture(false); Screen ui = new Screen(f)) {
            ui.await("当前 API 模型：fixture-ui-model");
            assertEquals(0, f.posts.get());
            ui.click("文本、工具与合成图片实测");
            assertEquals(0, f.posts.get());
            ui.await("会消耗该连接额度");
            ui.click("明确测试这项 API（最多2次）");
            ui.await("文本：本次通过 · 工具：未证实 · 图片：未测试");
            assertEquals(2, f.posts.get());
            JSONObject probe = f.app.modelConnections.snapshot().getJSONObject("capabilityProbe");
            String task = probe.getJSONObject("inferenceReference").getString("taskId");
            assertEquals(2, f.app.tasks.inferenceUsage(task).getInt("attempts"));
            assertEquals(0, f.app.tasks.get(task, false).getJSONArray("operations").length());
            assertFalse(probe.getBoolean("capabilityMetadataChanged"));
        }
    }

    @Test public void mountedOwnerStopDoesNotIssueTheSecondDiagnosticOrEraseTheFirstPhysicalUse()
            throws Exception {
        try (Fixture f = new Fixture(true); Screen ui = new Screen(f)) {
            ui.await("当前 API 模型：fixture-ui-model");
            ui.click("文本、工具与合成图片实测");
            ui.click("明确测试这项 API（最多2次）");
            assertTrue(f.entered.await(5, TimeUnit.SECONDS));
            ui.await("停止");
            ui.click("停止");
            ui.await("探测已停止");
            f.release.countDown();
            assertEquals(1, f.posts.get());
            JSONObject task = null;
            JSONArray tasks = f.app.tasks.list();
            for (int i = 0; i < tasks.length(); i++) {
                if ("inference".equals(tasks.getJSONObject(i).optString("kind"))) {
                    task = tasks.getJSONObject(i);
                }
            }
            assertNotNull(task);
            assertEquals("cancelled", task.getString("status"));
            assertEquals(1, f.app.tasks.inferenceUsage(task.getString("id")).getInt("attempts"));
            assertEquals(0, f.app.tasks.get(task.getString("id"), false)
                    .getJSONArray("operations").length());
            assertNull(f.app.modelConnections.snapshot().optJSONObject("capabilityProbe"));
        }
    }
}
