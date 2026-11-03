package dev.pocketengine.features.skills;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.ProjectMemoryNamespaces;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real Workspace/Local/Memory namespace, original TaskStore and Supervisor. No scope token
 * is fabricated from model arguments, and no process or device execution is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class HostSkillProjectScopeTest {
    private static JSONObject owner()throws Exception {
        return new JSONObject().put("ownerId","owner").put("spaceId","private");
    }
    private static JSONObject reference(JSONObject row)throws Exception {
        return new JSONObject().put("id",row.getString("id")).put("revision",row.getInt("revision"))
                .put("digest",row.getString("digest")).put("offset",0).put("length",8000);
    }

    private static final class Rig implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();
        final FeatureContext context=new FeatureContext(android,null,null,event->{});
        final TaskStore tasks=new TaskStore(android);
        final FeatureHost host=new FeatureHost(event->{},tasks,()->false);
        final WorkspaceModule workspace;
        final LocalModule local;
        final SkillsModule skills;
        final ProjectMemoryNamespaces namespaces;
        final ProjectMemoryNamespaces.Selection ownerMemorySelection;
        final JSONObject projectA,projectB,choiceA,choiceB,globalSkill,projectSkill;
        final List<HostToolSupervisor> ports=new ArrayList<>();

        Rig()throws Exception {
            workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);
            host.register(workspace);host.register(local);
            MemorySourceBridge sources=new MemorySourceBridge(host,tasks);sources.bindWorkspace(workspace);
            MemoryModule memory=new MemoryModule(context,sources.assertionResolver());host.register(memory);
            skills=new SkillsModule(context,memory::resolveExperience);host.register(skills);
            namespaces=new NativeOwnerWorkflows(host,context,tasks,sources).projectMemoryForOwner();
            assertSame(namespaces,memory.projectNamespacesForOwner(local,workspace,sources));
            skills.mountProjectNamespaces(namespaces);
            projectA=project("Project A");projectB=project("Project B");
            choiceA=choice(projectA);choiceB=choice(projectB);
            ownerMemorySelection=namespaces.selectForOwner(choiceB.getString("checkoutId"),
                    choiceB.getString("projectId"),choiceB.getString("revision"));

            globalSkill=enable(imported("native:global-guide","GLOBAL_GUIDE_BODY"));
            JSONObject candidate=imported("native:project-a-guide","PROJECT_A_ONLY_BODY");
            JSONObject configured=skills.configureSourceForOwner(owner(),candidate.getString("id"),
                    candidate.getInt("revision"),candidate.getString("digest"),candidate.getLong("executionGeneration"),
                    SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,choiceA)
                    .getJSONObject("skill");
            assertFalse(configured.getBoolean("enabled"));
            assertEquals(projectA.getString("projectId"),configured.getJSONObject("sourcePolicy").getString("projectId"));
            projectSkill=enable(configured);
        }
        JSONObject project(String name)throws Exception {
            String id=workspace.handle("create",new JSONObject().put("name",name)).getString("id");
            workspace.handle("write",new JSONObject().put("projectId",id).put("path","notes.txt")
                    .put("content",name+" actual project source").put("expectedVersion",0));
            return local.handle("prepare",new JSONObject().put("projectId",id));
        }
        JSONObject choice(JSONObject checkout)throws Exception {
            JSONArray choices=skills.projectSourceChoicesForOwner();
            for(int i=0;i<choices.length();i++){
                JSONObject row=choices.getJSONObject(i);
                if(checkout.getString("id").equals(row.getString("checkoutId"))){
                    assertTrue(row.getBoolean("available"));return row;
                }
            }
            throw new AssertionError("Original checkout is missing from the native project catalogue");
        }
        JSONObject imported(String source,String body)throws Exception {
            return skills.handle("import",new JSONObject().put("source",source).put("sourceVersion","fixture-v1")
                    .put("manifest","---\nname: shared-guide\ndescription: shared guide for project work\n---\n"+body))
                    .getJSONObject("skill");
        }
        JSONObject enable(JSONObject row)throws Exception {
            return skills.handle("setEnabled",new JSONObject().put("id",row.getString("id"))
                    .put("enabled",true).put("confirmed",true).put("acceptedDigest",row.getString("digest")))
                    .getJSONObject("skill");
        }
        Run run(JSONObject checkout)throws Exception {
            JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("shared-guide project work","")
                    .getString("id"),"capabilities",30);
            RunControl control=new RunControl();String id=task.getString("id"),run=task.getString("runId");
            int epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);
            if(checkout!=null){
                JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout.getString("id"))));
                tasks.bindDevelopmentContext(id,run,epoch,control,new JSONObject()
                        .put("format","pocketworld.development-context.v1").put("projectId",checkout.getString("projectId"))
                        .put("checkoutId",checkout.getString("id")).put("runtime","termux")
                        .put("initialSourceDigest",source.getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
            }
            tasks.authorizeTools(id,run,new JSONArray().put("skills_list").put("skills_read"));
            HostToolSupervisor port=new HostToolSupervisor(host,tasks,id,run,epoch,false);ports.add(port);
            return new Run(this,id,run,epoch,control,port);
        }
        public void close()throws Exception {
            for(HostToolSupervisor port:ports)port.close();
            host.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);
            ((SQLiteDatabase)field.get(tasks)).close();
        }
    }
    private static final class Run {
        final Rig rig;final String id,run;final int epoch;final RunControl control;final HostToolSupervisor port;
        Run(Rig rig,String id,String run,int epoch,RunControl control,HostToolSupervisor port){
            this.rig=rig;this.id=id;this.run=run;this.epoch=epoch;this.control=control;this.port=port;
        }
        JSONObject call(String name,JSONObject args)throws Exception {
            port.validate(name,args);var journal=rig.tasks.journal(id,run,epoch,control);
            String operation=journal.beginOperation(name,args);
            try{
                JSONObject result=port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",operation),control);
                journal.finishOperation(operation,result);return result;
            }catch(Exception failure){
                journal.finishOperation(operation,new JSONObject().put("ok",false).put("accepted",false)
                        .put("error","fixture: original Skill operation rejected"));throw failure;
            }
        }
        JSONObject catalogue()throws Exception{return call("skills_list",new JSONObject().put("mode","catalogue").put("pageSize",20));}
        JSONObject read(JSONObject row)throws Exception{return call("skills_read",reference(row));}
    }
    private static void assertOnlySkill(JSONObject response,String id)throws Exception {
        JSONArray rows=response.getJSONArray("skills");assertEquals(1,rows.length());assertEquals(id,rows.getJSONObject(0).getString("id"));
    }

    @Test public void originalProjectTaskSelectsProjectSourceAcrossCatalogueDiscoveryAndPinnedRead()throws Exception {
        try(Rig r=new Rig()){
            Run a=r.run(r.projectA);JSONObject task=r.tasks.get(a.id,false);
            assertEquals("owner",task.getString("actorId"));assertEquals("private",task.getString("spaceId"));
            assertEquals(r.projectA.getString("id"),task.getJSONObject("developmentContext").getString("checkoutId"));
            JSONObject page=a.catalogue();assertOnlySkill(page,r.projectSkill.getString("id"));
            assertFalse(page.getBoolean("executable"));assertEquals(0,page.getJSONArray("grantsAdded").length());
            assertFalse(page.toString().contains("PROJECT_A_ONLY_BODY"));
            JSONObject context=a.port.contextSnapshot("shared-guide");
            assertOnlySkill(context.getJSONObject("skillsDiscovery"),r.projectSkill.getString("id"));
            assertOnlySkill(context.getJSONObject("referenceValidation"),r.projectSkill.getString("id"));
            assertFalse(context.toString().contains("PROJECT_A_ONLY_BODY"));assertTrue(a.port.responseCurrent(a.control));
            JSONObject read=a.read(r.projectSkill);assertTrue(read.getString("content").contains("PROJECT_A_ONLY_BODY"));
            assertFalse(read.getString("content").contains("GLOBAL_GUIDE_BODY"));
            assertEquals(r.projectSkill.getInt("revision"),read.getInt("revision"));
            assertEquals(page.getString("skillScopeFingerprint"),read.getString("skillScopeFingerprint"));
            assertThrows(SecurityException.class,()->a.read(r.globalSkill));
            JSONObject proof=r.tasks.get(a.id,false).getJSONArray("skillReferenceReads").getJSONObject(0);
            assertEquals(a.run,proof.getString("runId"));assertEquals(a.epoch,proof.getInt("epoch"));
            assertEquals(r.projectA.getString("projectId"),proof.getJSONObject("package").getJSONObject("skillScope").getString("projectId"));
            assertTrue(r.ownerMemorySelection.metadata().getBoolean("selectionCurrent"));
            assertEquals(r.projectB.getString("projectId"),r.ownerMemorySelection.metadata().getString("projectId"));
            assertEquals(1,r.tasks.list().length());
        }
    }

    @Test public void otherProjectAndUnboundTaskKeepGlobalSourceAndCannotForgeProjectThroughToolJson()throws Exception {
        try(Rig r=new Rig()){
            Run b=r.run(r.projectB),global=r.run(null);
            for(Run run:List.of(b,global)){
                assertOnlySkill(run.catalogue(),r.globalSkill.getString("id"));
                assertTrue(run.read(r.globalSkill).getString("content").contains("GLOBAL_GUIDE_BODY"));
                assertThrows(SecurityException.class,()->run.read(r.projectSkill));
                JSONObject context=run.port.contextSnapshot("shared-guide");
                assertOnlySkill(context.getJSONObject("skillsDiscovery"),r.globalSkill.getString("id"));
                assertOnlySkill(context.getJSONObject("referenceValidation"),r.globalSkill.getString("id"));
                assertFalse(context.toString().contains(r.projectSkill.getString("id")));
                assertThrows(IllegalArgumentException.class,()->run.port.validate("skills_read",
                        reference(r.projectSkill).put("projectId",r.projectA.getString("projectId"))));
                assertThrows(SecurityException.class,()->run.port.validate("skills_list",
                        new JSONObject().put("mode","catalogue").put("scope",owner().put("projectId",r.projectA.getString("projectId")))));
                assertEquals(1,r.tasks.get(run.id,false).getJSONArray("skillReferenceReads").length());
            }
            assertThrows(SecurityException.class,()->r.skills.handle("read",reference(r.projectSkill)
                    .put("projectId",r.projectA.getString("projectId"))));
            assertEquals(r.projectB.getString("projectId"),r.tasks.get(b.id,false).getJSONObject("developmentContext").getString("projectId"));
            assertFalse(r.tasks.get(global.id,false).has("developmentContext"));
            assertEquals(2,r.tasks.list().length());
        }
    }

    @Test public void replacedCheckoutInvalidatesAdmittedResponseAndMissingCheckoutNeverFallsBackToGlobal()throws Exception {
        try(Rig r=new Rig()){
            Run a=r.run(r.projectA);JSONObject originalTask=r.tasks.get(a.id,false);
            JSONObject admitted=r.skills.taskScopeForHost(originalTask);
            a.port.contextSnapshot("shared-guide");a.read(r.projectSkill);assertTrue(a.port.responseCurrent(a.control));
            File record=new File(r.android.getFilesDir(),"local-development/checkouts/"+r.projectA.getString("id")+"/checkout.json");
            JSONObject replaced=JsonFiles.read(record);
            // A fixture-only physical occurrence replacement keeps the public checkout ID. The
            // native catalogue, rather than a fabricated project revision, detects the change.
            replaced.put("createdAt",Math.addExact(replaced.getLong("createdAt"),1));JsonFiles.write(record,replaced);
            assertNotEquals(r.choiceA.getString("revision"),r.choice(r.projectA).getString("revision"));
            assertFalse(a.port.responseCurrent(a.control));
            assertThrows(SecurityException.class,()->r.skills.requireTaskScopeForHost(r.tasks.get(a.id,false),admitted));
            assertThrows(SecurityException.class,()->r.skills.configureSourceForOwner(owner(),r.projectSkill.getString("id"),
                    r.projectSkill.getInt("revision"),r.projectSkill.getString("digest"),r.projectSkill.getLong("executionGeneration"),
                    SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,r.choiceA));

            File missing=new File(record.getParentFile(),"checkout.unavailable");Files.move(record.toPath(),missing.toPath());
            try{
                assertThrows(SecurityException.class,()->a.port.contextSnapshot("shared-guide"));
                assertThrows(SecurityException.class,a::catalogue);
                assertThrows(SecurityException.class,()->a.read(r.projectSkill));
                assertEquals(1,r.tasks.get(a.id,false).getJSONArray("skillReferenceReads").length());
            }finally{Files.move(missing.toPath(),record.toPath());}
            assertEquals(1,r.tasks.list().length());
        }
    }
}
