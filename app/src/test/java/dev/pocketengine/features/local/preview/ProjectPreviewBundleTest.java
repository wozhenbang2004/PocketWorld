package dev.pocketengine.features.local.preview;

import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class ProjectPreviewBundleTest {
    private static final String CHECKOUT="113fa15d-4d97-4a8b-b812-9f56f8331943",PROJECT="264a8d7c-31e6-44cf-aa4e-60944c4f2e5c";
    @Test public void actualMultiResourceBytesIncludeCssModuleAndPngBeyondIntentBudget()throws Exception {
        Path root=Files.createTempDirectory("project-preview");Files.createDirectories(root.resolve("dist/assets"));
        Files.writeString(root.resolve("dist/index.html"),"<link rel='stylesheet' href='./style.css'><img src='./assets/pixel.png'><script type='module' src='./app.mjs'></script>");
        byte[] css="body{color:rgb(25,50,75)}".getBytes(StandardCharsets.UTF_8),js=("/*"+"x".repeat(70000)+"*/\ndocument.body.dataset.actual='module-loaded';").getBytes(StandardCharsets.UTF_8);
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jxQAAAABJRU5ErkJggg==");
        Files.write(root.resolve("dist/style.css"),css);Files.write(root.resolve("dist/app.mjs"),js);Files.write(root.resolve("dist/assets/pixel.png"),png);
        Files.createDirectories(root.resolve("dist/node_modules/hidden"));Files.writeString(root.resolve("dist/node_modules/hidden/private.js"),"never served");Files.writeString(root.resolve("dist/.env"),"NOT_A_PREVIEW_RESOURCE=private");
        var preview=ProjectPreviewBundle.capture(root.toFile(),CHECKOUT,PROJECT,ProjectSources.sha256("source".getBytes(StandardCharsets.UTF_8)),"dist/index.html");
        assertEquals(4,preview.metadata().getInt("resourceCount"));assertTrue(preview.totalBytes>65536);
        for(Object[] expected:new Object[][]{{"style.css","text/css",css},{"app.mjs","text/javascript",js},{"assets/pixel.png","image/png",png}}){
            var response=ProjectBundleWebView.response(preview,preview.origin+expected[0],"GET");assertEquals(200,response.getStatusCode());assertEquals(expected[1],response.getMimeType());assertArrayEquals((byte[])expected[2],response.getData().readAllBytes());
        }
        assertNotNull(preview.resource(preview.origin+"app.mjs?v=1"));assertNull(preview.resource(preview.origin+"node_modules/hidden/private.js"));assertNull(preview.resource(preview.origin+".env"));
        Files.writeString(root.resolve("dist/app.mjs"),"changed after observation");assertArrayEquals(js,preview.resource(preview.origin+"app.mjs").bytes());
        var refreshed=ProjectPreviewBundle.capture(root.toFile(),CHECKOUT,PROJECT,preview.sourceDigest,"dist/index.html");assertNotEquals(preview.identity,refreshed.identity);
        System.out.println("PROJECT_PREVIEW actualHtmlCssModulePngBytes=true; bytes="+preview.totalBytes+"; immutableAfterCapture=true; AndroidWebViewJavascriptExecutionVerified=false");
    }
    @Test public void resolverRejectsTraversalExternalOriginAndPost()throws Exception {
        Path root=Files.createTempDirectory("project-preview-scope");Files.writeString(root.resolve("index.html"),"<h1>Actual local file</h1>");
        var preview=ProjectPreviewBundle.capture(root.toFile(),CHECKOUT,PROJECT,"a".repeat(64),"index.html");
        for(String url:new String[]{"file:///index.html","https://example.invalid/index.html",preview.origin+"%2e%2e/private.html",preview.origin+"assets/%2e%2e/index.html",preview.origin+"missing.js"})assertEquals(404,ProjectBundleWebView.response(preview,url,"GET").getStatusCode());
        assertEquals(404,ProjectBundleWebView.response(preview,preview.entryUrl,"POST").getStatusCode());assertTrue(preview.document(preview.entryUrl+"#section"));
    }
    @Test public void binarySourceIdentityUsesActualBytesAndCachePathsAreSeparate()throws Exception {
        byte[] bytes={0,1,2,(byte)255};var file=new org.json.JSONObject().put("path","assets/image.png").put("binary",true).put("contentBase64",Base64.getEncoder().encodeToString(bytes)).put("sha256",ProjectSources.sha256(bytes));
        var snapshot=new org.json.JSONObject().put("checkout",new org.json.JSONObject().put("id",CHECKOUT).put("projectId",PROJECT)).put("files",new org.json.JSONArray().put(file));
        assertEquals(4,ProjectSources.describe(snapshot).getInt("bytes"));assertTrue(ProjectSources.files(snapshot).isEmpty());assertFalse(ProjectSources.sourcePath("node_modules/pkg/index.js"));assertFalse(ProjectSources.sourcePath("dist/assets/image.png"));assertTrue(ProjectSources.sourcePath("assets/image.png"));
        file.put("contentBase64",Base64.getEncoder().encodeToString(new byte[]{0,1,3}));try{ProjectSources.describe(snapshot);fail();}catch(SecurityException expected){}
    }
}
