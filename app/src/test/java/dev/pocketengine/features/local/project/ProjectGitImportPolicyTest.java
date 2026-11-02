package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import org.json.*;
import org.junit.Test;

/** Only closed program/URL/opaque-carrier contracts; no Git/Android execution. */
public final class ProjectGitImportPolicyTest {
    static JSONObject exact()throws Exception {JSONObject payload=new JSONObject().put("checkoutId","12345678-1234-1234-1234-123456789abc").put("expectedSourceDigest","a".repeat(64)).put("executable","python3").put("workingDirectory",".").put("terminalMode","pipes").put("runMode","foreground").put("timeoutMillis",600000).put("gitImportProgramDigest",ProjectGitImportProgram.identity());return new JSONObject().put("feature","local").put("command","project.runtime.run").put("intent",ProjectGitImport.INTENT).put("stepId",ProjectGitImport.STEP).put("payload",payload);}
    @Test public void nativeProgramIsAssetExactAndDoesNotPermitArbitraryShellArgumentsOrScopeChanges()throws Exception {
        Path asset=Path.of(System.getProperty("c2.sourceRoot")).resolve("app/src/main/assets/runtime/project_git_import.py");byte[] raw=Files.readAllBytes(asset);assertEquals(ProjectSources.sha256(raw),ProjectGitImportProgram.identity());assertArrayEquals(raw,ProjectGitImportProgram.source().getBytes(java.nio.charset.StandardCharsets.UTF_8));ProjectGitImport.requireProgram(exact());JSONArray args=ProjectGitImport.arguments(exact().getJSONObject("payload"));assertEquals(3,args.length());assertEquals("-I",args.getString(0));assertEquals("-c",args.getString(1));
        for(String field:new String[]{"argv","env","shell","host","port","credential","nativeApproved"}){JSONObject changed=exact();changed.getJSONObject("payload").put(field,"cannot mint native authority");assertThrows(field,Exception.class,()->ProjectGitImport.requireProgram(changed));}
        for(String field:new String[]{"executable","workingDirectory","terminalMode","runMode","gitImportProgramDigest"}){JSONObject changed=exact();changed.getJSONObject("payload").put(field,"changed");assertThrows(field,Exception.class,()->ProjectGitImport.requireProgram(changed));}
        JSONObject tooLong=exact();tooLong.getJSONObject("payload").put("timeoutMillis",600001);assertThrows(Exception.class,()->ProjectGitImport.requireProgram(tooLong));
        for(Class<?> type:new Class<?>[]{ProjectGitImport.Review.class,ProjectGitImport.Confirmed.class,ProjectGitImport.ProcessGrant.class,ProjectGitImport.Result.class,ProjectGitImport.Observation.class})for(var constructor:type.getDeclaredConstructors())assertTrue(type.getSimpleName(),Modifier.isPrivate(constructor.getModifiers()));
        assertEquals(1,ProjectGitImport.class.getConstructors().length);assertFalse(Modifier.isPublic(ProjectGitImport.Network.class.getModifiers()));
    }
    @Test public void unsupportedProtocolsUserinfoRedirectTargetsAndNonpublicAddressesAreNotReviewed()throws Exception {
        for(String url:new String[]{"http://127.0.0.1/repo","ssh://example.invalid/repo","https://user:secret@example.invalid/repo","https://example.invalid/repo?token=secret","https://example.invalid/repo#fragment","https://127.0.0.1/repo","https://169.254.169.254/repo","https://10.1.2.3/repo","https://localhost/repo"})assertThrows(url,Exception.class,()->ProjectGitImportTunnel.review(url,new RunControl()));
    }
}
