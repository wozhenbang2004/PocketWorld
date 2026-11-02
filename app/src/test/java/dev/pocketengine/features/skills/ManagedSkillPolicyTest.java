package dev.pocketengine.features.skills;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Real canonical plan/configuration primitives only; not Android isolation or a
 * promise that a requirement has already been fetched/installed. */
public final class ManagedSkillPolicyTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    static JSONObject configuration()throws Exception{return new JSONObject().put("format",ManagedSkillPolicy.FORMAT).put("runtimeId","python3").put("checkoutId","").put("workingDirectory",".").put("readPaths",new JSONArray()).put("writePaths",new JSONArray().put("output/")).put("outputPaths",new JSONArray().put("output/result.txt")).put("timeoutSeconds",120).put("maxOutputBytes",65536).put("maxResultChars",12000).put("preparePinnedDependencies",true);}
    @Test public void nativeConfigurationIsClosedAndDoesNotAcceptJsonStrongDenyOrArbitraryCommand()throws Exception {
        JSONObject nativeChoice=configuration();assertEquals(ManagedSkillPolicy.FORMAT,ManagedSkillPolicy.checked(nativeChoice).getString("format"));
        for(String field:List.of("network","backendId","fences","argv","environment")){JSONObject forged=new JSONObject(nativeChoice.toString()).put(field,"deny");assertThrows(IllegalArgumentException.class,()->ManagedSkillPolicy.checked(forged));}
        assertThrows(IllegalArgumentException.class,()->ManagedSkillPolicy.checked(new JSONObject(nativeChoice.toString()).put("runtimeId","bash -c")));
        assertThrows(IllegalArgumentException.class,()->ManagedSkillPolicy.checked(new JSONObject(nativeChoice.toString()).put("readPaths",new JSONArray().put("input/data.txt"))));
        assertThrows(IllegalArgumentException.class,()->ManagedSkillPolicy.checked(new JSONObject(nativeChoice.toString()).put("workingDirectory","../outside")));
    }
    @Test public void ordinaryRequestCannotTurnThreeObservedFencesIntoTheStrictCompanionContract()throws Exception {
        SkillExecution.Request ordinary=new SkillExecution.Request("scripts/main.py","python3","native_task_copy",".",List.of(),List.of(),List.of("output/"),List.of("output/result.txt"),10,4096,2048);
        var fake=new SkillExecution.Environment(ManagedSkillBackend.BACKEND,"installation","python3","fixed","a".repeat(64),List.of("/usr/bin/python3"),"native_task_copy","revision","/workspace","/package","no-json-authority",Set.of(SkillExecution.Fence.EXACT_BYTES,SkillExecution.Fence.EXACT_ARGV,SkillExecution.Fence.TIMEOUT));
        assertThrows(SkillExecution.Unavailable.class,()->fake.require(ordinary));
        JSONObject config=configuration();var authority=ManagedSkillPolicy.authority(config);var managed=ManagedSkillPolicy.request(config,authority,"scripts/main.py",List.of("$(literal) 中文"),10,4096);
        var actual=SkillExecution.Environment.managed(authority,"installation","python3","fixed","a".repeat(64),List.of("/usr/bin/python3"),"native_task_copy","revision","/workspace","/package","b".repeat(64),new JSONObject().put("files",new JSONArray()));actual.require(managed);
        assertFalse(actual.json().getJSONObject("nativeExecutionContract").getBoolean("osSandbox"));assertEquals("possible_same_uid",managed.json().getString("network"));assertEquals(3,actual.json().getJSONArray("fences").length());assertThrows(SkillExecution.Unavailable.class,()->actual.require(ordinary));
        var different=ManagedSkillPolicy.authority(config);var wrong=ManagedSkillPolicy.request(config,different,"scripts/main.py",List.of(),10,4096);assertThrows(SkillExecution.Unavailable.class,()->actual.require(wrong));
    }
    @Test public void configurationColdReadKeepsLocationButNeverPersistsNativeAuthorityOrPlan()throws Exception {
        File file=new File(temp.newFolder(),"original-skill-execution.json");SkillExecutionConfiguration original=new SkillExecutionConfiguration(file);var review=original.inspectPhone(configuration());assertFalse(file.exists());var chosen=original.install(review,review.digest);assertTrue(chosen.phone());assertEquals("this_phone",chosen.view().getString("executionLocation"));assertFalse(chosen.view().has("grant"));
        var cold=new SkillExecutionConfiguration(file);var loaded=cold.current();assertEquals(chosen.digest,loaded.digest);assertEquals(chosen.revision,loaded.revision);assertNotSame(chosen.phoneDeployment.authority,loaded.phoneDeployment.authority);assertEquals(chosen.phoneDeployment.authority.fingerprint,loaded.phoneDeployment.authority.fingerprint);assertFalse(java.nio.file.Files.readString(file.toPath()).contains("operationId"));original.disable(chosen.revision);assertThrows(SkillExecution.Unavailable.class,()->cold.check(loaded));
    }
    @Test public void dependencyLockRequiresExactVersionHashesNoUrlSourceBuildOrImplicitResolution()throws Exception {
        String sha="a".repeat(64),second="b".repeat(64);String line="Own_Package==1.2.3 --hash=sha256:"+sha+" --hash=sha256:"+second+"\n";JSONObject actual=ManagedSkillDependencies.inspect(Map.of(ManagedSkillDependencies.PATH,line.getBytes(StandardCharsets.UTF_8)),"python3",true);assertEquals("own-package",actual.getJSONArray("packages").getJSONObject(0).getString("name"));assertEquals(sha,actual.getJSONArray("packages").getJSONObject(0).getJSONArray("sha256").getString(0));assertFalse(actual.getBoolean("sourceBuildAllowed"));assertFalse(actual.getBoolean("implicitDependencies"));assertEquals(SkillExecution.sha256(line.getBytes(StandardCharsets.UTF_8)),actual.getString("requirementsSha256"));
        assertThrows(SkillExecution.Unavailable.class,()->ManagedSkillDependencies.inspect(Map.of(ManagedSkillDependencies.PATH,line.getBytes(StandardCharsets.UTF_8)),"sh",true));assertThrows(SkillExecution.Unavailable.class,()->ManagedSkillDependencies.inspect(Map.of(ManagedSkillDependencies.PATH,line.getBytes(StandardCharsets.UTF_8)),"python3",false));
        for(String invalid:List.of("Own_Package>=1.2.3 --hash=sha256:"+sha,"Own_Package==1.2.3","--extra-index-url https://elsewhere.invalid","Own_Package @ https://elsewhere.invalid/a.whl --hash=sha256:"+sha,"Own_Package==1.2.3 ; python_version>'3' --hash=sha256:"+sha,line+"own.package==1.2.3 --hash=sha256:"+sha,line.trim()+" \\","Own_Package==1.2.3 --hash=sha256:"+sha+" --hash=sha256:"+sha))assertThrows("invalid exact requirement="+invalid,IllegalArgumentException.class,()->ManagedSkillDependencies.inspect(Map.of(ManagedSkillDependencies.PATH,invalid.getBytes(StandardCharsets.UTF_8)),"python3",true));
    }
    @Test public void malformedUtf8OrUnboundedRequirementsCannotReachPip()throws Exception {
        assertThrows(java.nio.charset.CharacterCodingException.class,()->ManagedSkillDependencies.inspect(Map.of(ManagedSkillDependencies.PATH,new byte[]{(byte)0xff}),"python3",true));assertThrows(IllegalArgumentException.class,()->ManagedSkillDependencies.inspect(Map.of(ManagedSkillDependencies.PATH,new byte[16385]),"python3",true));StringBuilder tooMany=new StringBuilder();for(int i=0;i<33;i++)tooMany.append("own").append(i).append("==1.0.0 --hash=sha256:").append("a".repeat(64)).append('\n');assertThrows(IllegalArgumentException.class,()->ManagedSkillDependencies.inspect(Map.of(ManagedSkillDependencies.PATH,tooMany.toString().getBytes(StandardCharsets.UTF_8)),"python3",true));assertFalse(ManagedSkillDependencies.inspect(Map.of(),"sh",false).getBoolean("networkNeeded"));
    }
    @Test public void fixedNativeHelperHasDistinctIdentityAndNoGenericJsonEvalOrNewJobLoop()throws Exception {
        String helper=ManagedSkillProgram.source();assertEquals(SkillExecution.sha256(helper.getBytes(StandardCharsets.UTF_8)),ManagedSkillProgram.identity());assertTrue(helper.contains("subprocess.Popen(argv"));assertTrue(helper.contains("stdin=subprocess.DEVNULL"));assertFalse(helper.contains("shell=True"));assertTrue(helper.contains("'--require-hashes'"));assertTrue(helper.contains("'--only-binary=:all:'"));assertTrue(helper.contains("'--no-deps'"));assertTrue(helper.contains("resource.RLIMIT_FSIZE"));assertFalse(helper.contains("os.setsid("));assertFalse(helper.contains("requests.post("));
    }
    @Test public void explicitKernelOfflineChangesNativeAuthorityAndKeepsHonestIsolationLimits()throws Exception{
        JSONObject config=configuration().put("networkMode","kernel_offline");var selected=ManagedSkillPolicy.authority(config);var request=ManagedSkillPolicy.request(config,selected,"scripts/main.py",List.of(),10,4096);assertEquals("kernel_offline",request.json().getString("network"));assertEquals("required_before_target_exec",selected.risks().getString("networkEnforcement"));assertFalse(selected.risks().getBoolean("osSandbox"));assertTrue(selected.risks().getBoolean("localUnixIpcAllowed"));assertFalse(selected.risks().getBoolean("inheritedIpSocketsIsolated"));assertNotEquals(ManagedSkillPolicy.authority(configuration()).fingerprint,selected.fingerprint);
        assertThrows(IllegalArgumentException.class,()->ManagedSkillPolicy.checked(configuration().put("networkMode",JSONObject.NULL)));
    }

}
