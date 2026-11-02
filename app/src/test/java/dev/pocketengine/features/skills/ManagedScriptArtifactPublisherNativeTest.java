package dev.pocketengine.features.skills;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original SQLite Skill review/claim and original AttachmentBook bytes. Native runtime
 * discovery is a fixture; no process, phone, external share or network execution is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ManagedScriptArtifactPublisherNativeTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private final class Fixture implements AutoCloseable {
        final TaskStore tasks;final SQLiteDatabase db;final AttachmentBook attachments;
        final RunControl original=new RunControl();final SkillExecution.Plan plan;final SkillExecution.Grant grant;
        Fixture()throws Exception{
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);attachments=new AttachmentBook(temp.newFolder());
            JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("原脚本明确批准的两个成果","").getString("id"),"capabilities",10);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("skills_execute"));
            SkillBook skills=new SkillBook(temp.newFolder());JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");long now=System.currentTimeMillis();
            JSONObject skill=skills.install(scope,new JSONObject().put("source","fixture:native-script-publication").put("manifest","---\nname: original-artifact\ndescription: 原任务受限成果\n---\n原生测试，不运行。")
                    .put("resources",new JSONObject().put("scripts/main.py","raise RuntimeError('not executed')")),now);skills.setEnabled(scope,skill.getString("id"),true,skill.getString("digest"),true,now);
            JSONObject configuration=ManagedSkillPolicyTest.configuration().put("outputPaths",new JSONArray().put("output/a.txt").put("output/b.txt"));var deployment=ManagedSkillBackend.deployment(configuration);
            SkillExecution.Request request=ManagedSkillPolicy.request(configuration,deployment.authority,"scripts/main.py",List.of(),10,4096);
            SkillExecution.Environment environment=SkillExecution.Environment.managed(deployment.authority,"fixture-install","python3","fixture-version","a".repeat(64),List.of("python3"),"native_task_copy","source-revision","/workspace","/package","b".repeat(64),new JSONObject().put("files",new JSONArray()));
            SkillExecution.Backend backend=new SkillExecution.Backend(){public SkillExecution.Environment describe(SkillExecution.Run run,SkillExecution.Request actual){return environment;}public SkillExecution.Receipt submit(SkillExecution.Dispatch actual,RunControl live){throw new AssertionError("No runtime dispatch in publication fixture");}};
            SkillExecution.HostPort host=new SkillExecution.HostPort(){public void checkCurrent(SkillExecution.Plan p,SkillExecution.Grant g,RunControl c)throws Exception{tasks.checkSkillExecution(p,g,c);}public SkillExecution.Receipt dispatchOnce(SkillExecution.Plan p,SkillExecution.Grant g,RunControl c,SkillExecution.Submission s){throw new AssertionError("No second execution");}public SkillExecution.Receipt reconcile(SkillExecution.Plan p,SkillExecution.Grant g){throw new AssertionError("No runtime reconciliation fixture");}};
            plan=new SkillExecution(skills,backend,host,System::currentTimeMillis).prepare(new SkillExecution.Run("owner","private",id,run,epoch,Set.of("skills_execute")),skill.getString("id"),skill.getInt("revision"),skill.getString("digest"),request,original);
            String operation=tasks.journal(id,run,epoch,original).beginOperation("skills_execute",new JSONObject().put("id",skill.getString("id")).put("revision",skill.getInt("revision")).put("digest",skill.getString("digest")).put("scriptPath","scripts/main.py").put("arguments",new JSONArray()));
            grant=new SkillExecution.Grant(operation,UUID.randomUUID().toString(),"native-script-artifact-fixture",plan.digest,id,run,epoch,SkillExecution.environmentBinding(plan),plan.expiresAt);
            tasks.reserveSkillExecution(plan,grant,deployment.metadata(),original);tasks.decideSkillExecution(plan,grant,original,true);assertTrue(tasks.claimSkillExecution(plan,grant,CompanionSkillBackend.ReceiptRef.from(plan,grant),original));
        }
        ManagedScriptArtifactPublisher publisher(){return new ManagedScriptArtifactPublisher(attachments,(path,sha,size)->tasks.claimScriptArtifactPublication(plan,grant,path,sha,size,original),original);}
        JSONArray slots()throws Exception{return tasks.scriptArtifactPublicationsForOwner(grant.taskId,grant.operationId);}
        public void close(){db.close();}
    }
    @Test public void immediateOriginalAttachmentIsByteExactAndDuplicateIsOnePublication()throws Exception{try(Fixture f=new Fixture()){
        byte[] bytes="即时原成果🙂".getBytes(StandardCharsets.UTF_8);JSONObject first=f.publisher().share("output/a.txt",bytes);assertTrue(first.getBoolean("shared"));assertFalse(first.getBoolean("externalTransmission"));assertFalse(first.getBoolean("executionAuthorized"));String id=first.getJSONObject("artifact").getString("id");assertArrayEquals(bytes,f.attachments.readVerifiedSnapshot(id,SkillExecution.sha256(bytes),f.original));
        assertEquals(id,f.publisher().share("output/a.txt",bytes).getJSONObject("artifact").getString("id"));assertEquals(1,f.attachments.list().length());assertEquals(1,f.slots().length());assertEquals("published",f.slots().getJSONObject(0).getString("state"));assertEquals("prepared",f.tasks.get(f.grant.taskId,false).getJSONArray("operations").getJSONObject(0).getString("state"));
    }}
    @Test public void samePathDifferentBytesUnapprovedPathBudgetAndPausedControlCannotPublish()throws Exception{try(Fixture f=new Fixture()){
        f.publisher().share("output/a.txt",new byte[2048]);assertThrows(SecurityException.class,()->f.publisher().share("output/a.txt",new byte[]{1}));assertThrows(SecurityException.class,()->f.publisher().share("output/other.txt",new byte[]{1}));assertThrows(SecurityException.class,()->f.publisher().share("output/b.txt",new byte[2049]));assertEquals(1,f.attachments.list().length());assertEquals(1,f.slots().length());f.original.pause();assertThrows(SecurityException.class,()->f.publisher().share("output/b.txt",new byte[]{2}));assertEquals(1,f.slots().length());
    }}
    @Test public void removedBookRecordCannotBeImportedAgainUnderTheOriginalOperation()throws Exception{try(Fixture f=new Fixture()){
        byte[] bytes={3,4,5};JSONObject first=f.publisher().share("output/a.txt",bytes);String id=first.getJSONObject("artifact").getString("id");f.attachments.remove(id);JSONObject second=f.publisher().share("output/a.txt",bytes);assertEquals("unknown",second.getString("status"));assertTrue(second.isNull("accepted"));assertEquals(id,second.getString("attachmentId"));assertEquals(0,f.attachments.list().length());assertEquals(1,f.slots().length());
    }}
    @Test public void originalClaimWithNoBookCommitIsQueryOnlyAndOneUse()throws Exception{try(Fixture f=new Fixture()){
        byte[] bytes={6};var claimed=f.tasks.claimScriptArtifactPublication(f.plan,f.grant,"output/a.txt",SkillExecution.sha256(bytes),bytes.length,f.original);assertTrue(claimed.mayImport());assertFalse(claimed.mayImport());assertEquals("unknown",f.publisher().share("output/a.txt",bytes).getString("status"));assertEquals(0,f.attachments.list().length());assertEquals("publication_unknown",f.slots().getJSONObject(0).getString("state"));
    }}
}
