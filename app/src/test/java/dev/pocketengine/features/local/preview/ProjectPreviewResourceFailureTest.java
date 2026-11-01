package dev.pocketengine.features.local.preview;

import android.app.Activity;
import android.net.Uri;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Intercepted-resource and HTTP-error callbacks with real SDK parameters; not Chromium rendering or JS.
 * The modern onReceivedError network-error callback is not constructed or executed by this fixture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public final class ProjectPreviewResourceFailureTest {
    @Test public void missingStylesModulesAndImagesReachExistingEventsWithoutQueriesOrDuplicateErrors()throws Exception {
        try(Fixture f=new Fixture()){
            List<WebResourceResponse> responses=new ArrayList<>();
            Thread interceptor=new Thread(()->{for(String path:List.of("assets/missing.css","assets/missing.mjs","assets/missing.png"))responses.add(f.client.shouldInterceptRequest(f.web,new Request(f.bundle.origin+path+"?private=not-for-task-log",false)));});
            interceptor.start();interceptor.join(2000);assertFalse(interceptor.isAlive());
            assertEquals(3,responses.size());for(WebResourceResponse response:responses)assertEquals(404,response.getStatusCode());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Each failed real resource request must reach the existing task callback",3,f.console.size());
            for(String path:List.of("assets/missing.css","assets/missing.mjs","assets/missing.png"))assertTrue(f.console.toString(),f.console.stream().anyMatch(value->value.contains(path)));
            assertFalse(f.console.toString().contains("private="));assertFalse(f.console.toString().contains("not-for-task-log"));
            f.client.onReceivedHttpError(f.web,new Request(f.bundle.origin+"assets/missing.css?another=query",false),httpError());
            f.client.onReceivedHttpError(f.web,new Request(f.bundle.origin+"assets/missing.css",false),responses.get(0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(3,f.console.size());
            assertEquals(Thread.currentThread(),f.callbackThread);
        }
    }
    @Test public void observedHttpResourceFailuresAreBoundedAndQueuedCallbacksAreDiscardedAfterRelease()throws Exception {
        try(Fixture f=new Fixture()){
            f.client.onReceivedHttpError(f.web,new Request(f.bundle.origin+"assets/present.mjs?secret=drop",false),httpError());
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,f.console.size());assertTrue(f.console.get(0).contains("assets/present.mjs"));assertFalse(f.console.get(0).contains("secret="));
            for(int i=0;i<80;i++)f.client.shouldInterceptRequest(f.web,new Request(f.bundle.origin+"missing-"+i+".css",false));
            f.client.shouldInterceptRequest(f.web,new Request("https://external.invalid/private?token=hidden",false));
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(24,f.console.size());assertFalse(f.console.toString().contains("external.invalid"));
        }
        try(Fixture f=new Fixture()){
            f.client.shouldInterceptRequest(f.web,new Request(f.bundle.origin+"queued-error.js",false));
            f.web.release();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(f.console.isEmpty());
            f.client.shouldInterceptRequest(f.web,new Request(f.bundle.origin+"after-close.js",false));
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(f.console.isEmpty());
        }
    }
    private static WebResourceResponse httpError(){return new WebResourceResponse("text/plain","UTF-8",500,"Internal Server Error",Map.of(),new ByteArrayInputStream("raw engine diagnostics are not copied into task logs".getBytes(StandardCharsets.UTF_8)));}
    private static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("project-preview-resource-error-");
        final org.robolectric.android.controller.ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup();
        final List<String> console=new ArrayList<>();final ProjectPreviewBundle.Verified bundle;final ProjectBundleWebView web;final WebViewClient client;Thread callbackThread;
        Fixture()throws Exception {
            Files.createDirectories(root.resolve("assets"));Files.writeString(root.resolve("index.html"),"<link rel=stylesheet href=assets/missing.css><img src=assets/missing.png><script type=module src=assets/missing.mjs></script>");Files.writeString(root.resolve("assets/present.mjs"),"export const actual=true;\n");
            bundle=ProjectPreviewBundle.capture(root.toFile(),"fixture-checkout","fixture-project","a".repeat(64),"index.html");
            web=new ProjectBundleWebView(owner.get(),bundle,new ProjectBundleWebView.Events(){public void error(String message){}public void console(String level,String text,String source,int line){callbackThread=Thread.currentThread();console.add(level+"|"+text+"|"+source);}});
            owner.get().setContentView(web);client=web.getWebViewClient();
        }
        @Override public void close()throws Exception {web.release();owner.pause().stop().destroy();try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static final class Request implements WebResourceRequest {
        private final String url;private final boolean main;Request(String url,boolean main){this.url=url;this.main=main;}
        @Override public Uri getUrl(){return Uri.parse(url);}@Override public boolean isForMainFrame(){return main;}@Override public boolean isRedirect(){return false;}@Override public boolean hasGesture(){return false;}@Override public String getMethod(){return "GET";}@Override public Map<String,String> getRequestHeaders(){return Map.of();}
    }
}
