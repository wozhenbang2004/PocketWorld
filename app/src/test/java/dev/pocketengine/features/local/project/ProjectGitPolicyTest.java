package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import dev.pocketengine.features.EngineModule;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Semantic contracts only: no installed Git, Android process or approval is represented by these fixtures. */
public final class ProjectGitPolicyTest {
    static final String CHECKOUT="12345678-1234-1234-1234-123456789abc",GIT_SHA="b".repeat(64);
    static JSONObject pin()throws Exception{return new JSONObject().put("head","a".repeat(40)).put("headRef","refs/heads/main").put("headFileSha256","1".repeat(64)).put("indexSha256","2".repeat(64)).put("configSha256","3".repeat(64)).put("mergeHeadSha256","absent").put("worktreeSha256","4".repeat(64)).put("statusSha256","5".repeat(64)).put("snapshotDigest","6".repeat(64));}
    static JSONObject row(String path,String index,String worktree,boolean conflict)throws Exception {return new JSONObject().put("path",path).put("indexStatus",index).put("worktreeStatus",worktree).put("conflict",conflict).put("sourceScope",true).put("indexEntries",new JSONArray().put(new JSONObject().put("mode","100644").put("objectId","7".repeat(40)).put("stage",0)));}
    static JSONObject snapshot()throws Exception{return new JSONObject().put("pin",pin()).put("mergeParentIds",new JSONArray()).put("entries",new JSONArray().put(row("main.py","M","M",false)).put(row("other.py","M",".",false)));}
    static final class Source implements EngineModule {
        final JSONObject source;Source()throws Exception{source=new JSONObject().put("checkout",new JSONObject().put("id",CHECKOUT).put("projectId","project")).put("files",new JSONArray().put(new JSONObject().put("path","main.py").put("content","print('original')\n")));}
        public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}
        public JSONObject handle(String command,JSONObject input)throws Exception{return switch(command){case "export"->ProjectSources.copy(source);case "probe"->new JSONObject().put("termux",new JSONObject().put("installed",false));case "listJobs"->new JSONObject().put("termuxJobs",new JSONArray());case "project.runtime.describe"->new JSONObject().put("identity","c".repeat(64)).put("canExecute",true).put("tools",new JSONArray().put(new JSONObject().put("name","git").put("hashVerified",true).put("sha256",GIT_SHA)).put(new JSONObject().put("name","python3").put("hashVerified",true).put("sha256","d".repeat(64))));default->throw new AssertionError("Pure proposal dispatched "+command);};}
        JSONObject input(String action)throws Exception{return new JSONObject().put("intent","git").put("gitAction",action).put("checkoutId",CHECKOUT).put("runtimeId","managed_process_v1").put("expectedSourceDigest",ProjectSources.describe(source).getString("sourceDigest"));}
    }
    @Test public void originalPlanCarriesSemanticRequestAndFixedProgramHashNotInlineScript()throws Exception{
        Source source=new Source();JSONObject plan=ProjectBuildPlan.create(source,source.input("status")),verified=ProjectBuildPlan.verified(plan),payload=ProjectGit.step(verified).getJSONObject("payload");
        assertFalse(payload.has("argv"));assertEquals(ProjectGitProgram.identity(),payload.getString("gitProgramDigest"));assertEquals(3,ProjectGit.arguments(payload).length());assertEquals(ProjectGitProgram.source(),ProjectGit.arguments(payload).getString(1));
        JSONObject reference=ProjectPlanRecords.reference("12345678-1111-2222-3333-123456789abc",plan);assertFalse(reference.getBoolean("executionAuthorized"));assertTrue(reference.getJSONArray("steps").getJSONObject(0).getJSONObject("request").has("git"));assertFalse(reference.toString().contains("import hashlib"));assertTrue(reference.toString().length()<12000);
        JSONObject effects=ProjectGit.step(plan).getJSONObject("effects");assertFalse(effects.getBoolean("writes"));assertFalse(effects.getBoolean("networkMayBeUsed"));assertFalse(effects.getBoolean("scopeEnforced"));assertFalse(effects.getBoolean("repositoryHooks"));
    }
    @Test public void arbitraryArgumentsTerminalNetworkFieldsAndProgramSwapAreRejected()throws Exception{
        Source source=new Source();for(String key:Set.of("argv","executable","env","cwd","terminal","runMode","content","devWatch")){JSONObject input=source.input("status").put(key,key.equals("argv")?new JSONArray().put("fetch"):"forbidden");assertThrows(key,Exception.class,()->ProjectBuildPlan.create(source,input));}
        JSONObject plan=ProjectBuildPlan.create(source,source.input("status"));plan.getJSONArray("steps").getJSONObject(0).getJSONObject("payload").put("gitProgramDigest","0".repeat(64));assertThrows(Exception.class,()->ProjectBuildPlan.verified(plan));
        JSONObject raw=ProjectGit.request(new JSONObject().put("gitAction","status"),GIT_SHA);raw.put("argv",new JSONArray().put("push"));assertThrows(Exception.class,()->ProjectGit.verifiedRequest(raw));
    }
    @Test public void twoStatusLayersAndCommitTreeInputsAreNotDeduplicatedOrTakenFromWorkingText()throws Exception{
        JSONObject snapshot=snapshot(),request=ProjectGit.selectionRequest(snapshot,"commit",new JSONArray().put("main.py"));assertEquals(1,request.getJSONArray("gitEntries").length());assertEquals("7".repeat(40),request.getJSONArray("gitEntries").getJSONObject(0).getString("objectId"));assertEquals("a".repeat(40),request.getJSONArray("gitParents").getString(0));assertEquals("M",snapshot.getJSONArray("entries").getJSONObject(0).getString("worktreeStatus"));assertEquals(2,snapshot.getJSONArray("entries").length());
        JSONObject full=ProjectGit.request(request.put("commitMessage","review selected staged blob").put("authorName","Owner").put("authorEmail","owner@example.invalid"),GIT_SHA);assertEquals("main.py",full.getJSONArray("paths").getString(0));assertEquals("7".repeat(40),full.getJSONArray("reviewedEntries").getJSONObject(0).getString("objectId"));assertEquals(1,full.getJSONArray("parents").length());
        request.getJSONArray("gitEntries").getJSONObject(0).put("mode","120000");assertThrows(Exception.class,()->ProjectGit.request(request,GIT_SHA));
    }
    @Test public void conflictResolutionIsSeparateFromStageAndSelectionCannotJumpToAnUnreadPage()throws Exception{
        JSONObject snapshot=snapshot();snapshot.getJSONArray("entries").getJSONObject(0).put("conflict",true);assertThrows(Exception.class,()->ProjectGit.selectionRequest(snapshot,"stage",new JSONArray().put("main.py")));assertEquals("resolve",ProjectGit.selectionRequest(snapshot,"resolve",new JSONArray().put("main.py")).getString("gitAction"));assertThrows(Exception.class,()->ProjectGit.selectionRequest(snapshot,"resolve",new JSONArray().put("other.py")));assertThrows(Exception.class,()->ProjectGit.selectionRequest(snapshot,"stage",new JSONArray().put("not-read.py")));assertThrows(Exception.class,()->ProjectGit.selectionRequest(snapshot,"unstage",new JSONArray().put("main.py")));
    }
    @Test public void sourceAndMetadataPathsCannotBecomePathspecOrPrivateFileReads()throws Exception{
        for(String path:new String[]{"../outside","/outside",".git/config","src/../outside","src\\outside",".env","node_modules/pkg/index.js","dist/bundle.js"})assertThrows(path,Exception.class,()->ProjectGit.path(path));assertEquals("-dash file.py",ProjectGit.path("-dash file.py"));assertEquals("中文/emoji😀.py",ProjectGit.path("中文/emoji😀.py"));
    }
    @Test public void statusAndDiffPageCursorsRetainAllOriginalPins()throws Exception{
        assertThrows(Exception.class,()->ProjectGit.request(new JSONObject().put("gitAction","status").put("gitOffset",40),GIT_SHA));JSONObject r=ProjectGit.request(new JSONObject().put("gitAction","diff").put("gitPin",pin()).put("path","main.py").put("gitLayer","head_index"),GIT_SHA);assertEquals("head_index",r.getString("layer"));JSONObject incomplete=pin();incomplete.remove("indexSha256");assertThrows(Exception.class,()->ProjectGit.pin(incomplete));assertThrows(Exception.class,()->ProjectGit.request(new JSONObject().put("gitAction","diff").put("gitPin",pin()).put("path","main.py").put("gitLayer","external"),GIT_SHA));
    }
    @Test public void nativeConfirmationIsOpaqueCopiesContentAndDoesNotFollowModelApprovalFlags()throws Exception{
        Source source=new Source();JSONObject plan=ProjectBuildPlan.create(source,source.input("status"));var selected=ProjectGit.ownerRequest(plan,new JSONObject().put("fixture",true));var confirmed=selected.confirm();assertEquals(selected.requestId,confirmed.requestId());JSONObject copy=confirmed.plan();copy.put("sourceDigest","changed");assertNotEquals("changed",confirmed.plan().getString("sourceDigest"));assertTrue(java.lang.reflect.Modifier.isPrivate(ProjectGit.Confirmed.class.getDeclaredConstructors()[0].getModifiers()));assertTrue(java.lang.reflect.Modifier.isPrivate(ProjectGit.OwnerRequest.class.getDeclaredConstructors()[0].getModifiers()));var proof=ProjectGit.rejectedBeforeRuntime(confirmed,new SecurityException("fixture preflight"));assertTrue(proof.matches(confirmed));assertFalse(proof.matches(selected.confirm()));
    }
    @Test public void modelPagesOneOriginalStatusResultWithoutClaimingWholeRepositoryComplete()throws Exception{
        JSONObject data=snapshot().put("offset",40).put("nextOffset",42).put("totalEntries",100).put("truncated",true);JSONObject original=new JSONObject().put("data",data).put("ok",true).put("publicationUnknown",false);JSONObject first=ProjectGitResult.modelView(original,0,1),second=ProjectGitResult.modelView(original,1,1);assertEquals("main.py",first.getJSONObject("data").getJSONArray("entries").getJSONObject(0).getString("path"));assertEquals("other.py",second.getJSONObject("data").getJSONArray("entries").getJSONObject(0).getString("path"));assertTrue(first.getJSONObject("data").getBoolean("resultEntriesOmitted"));assertEquals(100,first.getJSONObject("data").getInt("totalEntries"));assertEquals(2,original.getJSONObject("data").getJSONArray("entries").length());assertThrows(Exception.class,()->ProjectGitResult.modelView(original,3,1));
    }
}
