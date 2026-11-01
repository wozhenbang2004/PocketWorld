package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.TermuxBridge;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real generated staging/guard/compiler process; Android service transport is an explicit fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TermuxRuntimeFlowTest {
    private Context android;private TermuxProjectRuntime runtime;private Path namespace;
    private JSONObject source;private RunControl original;
    @Before public void start() throws Exception {
        android=RuntimeEnvironment.getApplication();PackageInfo pkg=new PackageInfo();pkg.packageName="com.termux";pkg.versionName="runtime-transport-fixture";
        shadowOf(android.getPackageManager()).installPackage(pkg);shadowOf((Application)android).grantPermissions(TermuxBridge.PERMISSION);
        TermuxBridge bridge=new TermuxBridge(new FeatureContext(android,null,null,event->{}));bridge.setEnabled(true);runtime=new TermuxProjectRuntime(bridge);
        namespace=Files.createTempDirectory("a25d-runtime-namespace-");original=RuntimeContractsTest.original();
        source=RuntimeContractsTest.source();source.getJSONArray("files").getJSONObject(0).put("content",
                "public class Main { public static void main(String[] args) { System.out.println(Helper.message()); System.err.println(\"ACTUAL_STDERR\"); } }\n");
        source.getJSONArray("files").put(new JSONObject().put("path","Helper.java").put("content",
                "class Helper { static String message() { return \"ACTUAL_COMPILED_DEPENDENCY\"; } }\n"));
    }
    @After public void clean() throws Exception {
        if(namespace!=null)try(var paths=Files.walk(namespace)) { for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path); }
    }
    private RuntimeInvocation invocation(String operation,String command,String executable,JSONArray argv,ProjectRuntime.LiveGate gate) throws Exception {
        JSONObject exact=RuntimeContractsTest.exact(source,command,argv);
        if(executable!=null) { JSONObject payload=exact.getJSONObject("payload");payload.put("executable",executable);exact.put("requestDigest",ProjectSources.digest(payload)); }
        return new RuntimeInvocation(exact,RuntimeContractsTest.binding(operation),source,original,gate);
    }
    private RuntimeReceipt executeCaptured(RuntimeExecutionRef reference) throws Exception {
        Intent intent=shadowOf((Application)android).getNextStartedService();assertNotNull(intent);
        assertEquals("com.termux.RUN_COMMAND",intent.getAction());assertEquals("com.termux",intent.getComponent().getPackageName());
        String prefix="/data/data/com.termux/files/usr/bin/",home="/data/data/com.termux/files/home",jdk="/usr/lib/jvm/java-17-openjdk-amd64/bin/";
        String path=intent.getStringExtra("com.termux.RUN_COMMAND_PATH"),stdin=intent.getStringExtra("com.termux.RUN_COMMAND_STDIN");
        ArrayList<String> argv=new ArrayList<>();argv.add(path.equals(prefix+"sh")?"/bin/sh":path.replace(prefix,jdk));
        String[] args=intent.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS");if(args!=null)java.util.Collections.addAll(argv,args);
        ProcessBuilder builder=new ProcessBuilder(argv).directory(Path.of(intent.getStringExtra("com.termux.RUN_COMMAND_WORKDIR")
                .replace(home,namespace.toString())).toFile());
        builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin");builder.environment().put("HOME",namespace.toString());
        Process process=builder.start();
        try(var input=process.getOutputStream()) { if(stdin!=null)input.write(stdin.replace(home,namespace.toString()).replace(prefix,jdk).getBytes(StandardCharsets.UTF_8)); }
        // These fixture programs write a small finite output; both values come from the real child.
        String stdout=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        String stderr=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10,TimeUnit.SECONDS));
        PendingIntent pending=intent.getParcelableExtra("com.termux.RUN_COMMAND_PENDING_INTENT");
        Intent callback=new Intent(shadowOf(pending).getSavedIntent());Bundle result=new Bundle();
        result.putInt("err",-1);result.putInt("exitCode",process.exitValue());result.putString("stdout",stdout);result.putString("stderr",stderr);
        callback.putExtra("result",result);assertNotNull(TermuxBridge.receive(android,callback));
        return runtime.query(reference);
    }

    @Test public void realSourceStagesCompilesDependencyRunsAndReturnsOriginalReceipt() throws Exception {
        assertFalse(runtime.describe().getBoolean("liveStdout"));
        RuntimeInvocation stage=invocation("stage","termux.prepare",null,null,()->{});
        assertFalse(runtime.dispatch(stage).terminal());runtime.dispatch(stage);
        RuntimeReceipt staged=executeCaptured(stage.executionRef());assertTrue(staged.terminal());assertEquals(0,staged.json().getInt("exitCode"));
        assertNull(shadowOf((Application)android).getNextStartedService());
        RuntimeInvocation build=invocation("compile","termux.run","javac",new JSONArray().put("-d").put("build/classes").put("Main.java").put("Helper.java"),()->{});
        runtime.dispatch(build);RuntimeReceipt compiled=executeCaptured(build.executionRef());assertEquals(compiled.json().optString("stderr"),0,compiled.json().getInt("exitCode"));
        assertEquals("pre_exec_manifest_verified",compiled.json().getString("sourceCheckStatus"));
        RuntimeInvocation run=invocation("run","termux.run","java",new JSONArray().put("-cp").put("build/classes").put("Main"),()->{});
        runtime.dispatch(run);RuntimeReceipt actual=executeCaptured(run.executionRef());assertTrue(actual.terminal());
        assertEquals("ACTUAL_COMPILED_DEPENDENCY\n",actual.json().getString("stdout"));assertEquals("ACTUAL_STDERR\n",actual.json().getString("stderr"));
        assertEquals("task",actual.json().getJSONObject("projectExecution").getString("taskId"));
        assertEquals("run",actual.json().getJSONObject("projectExecution").getString("runId"));
        assertEquals("project",actual.json().getJSONObject("projectExecution").getString("projectId"));
        System.out.println("A25D_RUNTIME realDesktopStageAndJavacAndJava=true; sameOriginalRef=true; AndroidTermuxDevice=false");
    }
    @Test public void revokedOrPausedOriginalNeverDispatchesAndUnknownCancelNeverClaimsKill() throws Exception {
        AtomicBoolean live=new AtomicBoolean(true);RuntimeInvocation stage=invocation("stage","termux.prepare",null,null,()->{if(!live.get())throw new SecurityException("revoked");});
        live.set(false);assertThrows(SecurityException.class,()->runtime.dispatch(stage));assertNull(shadowOf((Application)android).getNextStartedService());
        live.set(true);original.pause();original.resume();assertThrows(SecurityException.class,()->runtime.dispatch(stage));
        RuntimeInvocation fresh=invocation("fresh_stage","termux.prepare",null,null,()->{});runtime.dispatch(fresh);
        RuntimeReceipt cancelled=runtime.requestCancel(fresh.executionRef());assertFalse(cancelled.terminal());
        assertEquals("unknown",cancelled.status());assertTrue(cancelled.json().getBoolean("cancelRequested"));
        assertFalse(runtime.describe().getBoolean("reliableCancellation"));
        RuntimeExecutionRef wrong=RuntimeExecutionRef.fromHost(fresh.exact(),RuntimeContractsTest.binding("other"));
        assertThrows(Exception.class,()->runtime.query(wrong));
        assertTrue(executeCaptured(fresh.executionRef()).terminal());
    }
    @Test public void realCompilerFailureIsNotSyntheticSuccess() throws Exception {
        source.getJSONArray("files").getJSONObject(0).put("content","public class Main { syntax error }\n");
        RuntimeInvocation stage=invocation("bad_stage","termux.prepare",null,null,()->{});runtime.dispatch(stage);executeCaptured(stage.executionRef());
        RuntimeInvocation compile=invocation("bad_compile","termux.run","javac",new JSONArray().put("Main.java"),()->{});runtime.dispatch(compile);
        RuntimeReceipt failed=executeCaptured(compile.executionRef());assertTrue(failed.terminal());assertEquals("failed",failed.status());
        assertNotEquals(0,failed.json().getInt("exitCode"));assertFalse(failed.json().getString("stderr").isEmpty());
    }
}
