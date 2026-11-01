package dev.pocketengine.features.local.preview;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real captured workspace bytes and URL admission; no Android or browser execution claim. */
public final class ProjectPreviewResourcePathTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private ProjectPreviewBundle.Verified capture(Path root)throws Exception {Files.writeString(root.resolve("index.html"),"<script type=module src=assets/present.mjs></script>");return ProjectPreviewBundle.capture(root.toFile(),"selected-checkout","selected-project","a".repeat(64),"index.html");}
    @Test public void missingResourceDiagnosticsIdentifyOnlyTheLocalPathWithoutQueryOrFragment()throws Exception {
        ProjectPreviewBundle.Verified bundle=capture(temporary.newFolder().toPath());
        for(String path:new String[]{"assets/missing.css","assets/missing.mjs","assets/missing.png"}){
            assertEquals(path,bundle.resourcePath(bundle.origin+path+"?token=private&retry=2#section"));
            assertNull(bundle.resource(bundle.origin+path+"?token=private&retry=2#section"));
        }
        assertEquals("assets/中文 name.js",bundle.resourcePath(bundle.origin+"assets/%E4%B8%AD%E6%96%87%20name.js?secret=drop"));
    }
    @Test public void pathsRejectedByTheResourceResolverCannotBecomeTaskDiagnostics()throws Exception {
        ProjectPreviewBundle.Verified bundle=capture(temporary.newFolder().toPath());
        for(String url:new String[]{"https://foreign.invalid/private?token=secret","file:///private.png",bundle.origin+"%2e%2e/private.js",bundle.origin+"assets/%2e%2e/private.js",bundle.origin+"assets/%5cprivate.js",bundle.origin+"assets/%00private.js",bundle.origin+"assets/"}){
            assertNull(url,bundle.resourcePath(url));assertNull(url,bundle.resource(url));
        }
    }
    @Test public void pathDiagnosticsDoNotChangeCapturedResourceBytesOrPreviewIdentity()throws Exception {
        Path root=temporary.newFolder().toPath();Files.createDirectories(root.resolve("assets"));byte[] actual="export const real='captured';\n".getBytes(StandardCharsets.UTF_8);Files.write(root.resolve("assets/present.mjs"),actual);
        ProjectPreviewBundle.Verified bundle=capture(root);String identity=bundle.identity;
        assertEquals("assets/present.mjs",bundle.resourcePath(bundle.origin+"assets/present.mjs?v=1"));
        assertArrayEquals(actual,bundle.resource(bundle.origin+"assets/present.mjs?v=1").bytes());
        Files.writeString(root.resolve("assets/present.mjs"),"changed later");
        assertArrayEquals(actual,bundle.resource(bundle.origin+"assets/present.mjs?v=2").bytes());assertEquals(identity,bundle.identity);
    }
}
