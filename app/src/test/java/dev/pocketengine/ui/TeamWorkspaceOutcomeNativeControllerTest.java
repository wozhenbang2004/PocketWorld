package dev.pocketengine.ui;

import android.net.Uri;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.TeamWorkspaceOutcomeHost;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import static dev.pocketengine.ui.AttachmentOutcomeNativeControllerTest.*;
import static org.junit.Assert.*;

/** Real native overlay/coordinator, workspace and SAF bytes. Native source authority is an explicit fixture; this does not establish the SQL bridge. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class,qualifiers="w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TeamWorkspaceOutcomeNativeControllerTest {
    private static String uuid(){return java.util.UUID.randomUUID().toString();}
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();final File root=Files.createTempDirectory("native-team-outcome-").toFile();final VersionedWorkspace workspace=new VersionedWorkspace(new File(root,"workspace"));final WorkspaceArtifacts artifacts=new WorkspaceArtifacts(new File(root,"artifacts"),workspace);
        final String text="成员实际冻结文件\n".repeat(1800);final JSONObject ref;final TeamWorkspaceOutcomeHost host;final AtomicReference<JSONObject> returned=new AtomicReference<>();final AtomicBoolean revokeAfterCopy=new AtomicBoolean();final ActivityController<ComposeActivity> screen;final AttachmentOutcomeCoordinator flow;
        Fixture()throws Exception {
            String project=workspace.create("实际成员草稿").getString("id"),target=workspace.create("原项目").getString("id"),task=uuid(),run=uuid();JSONObject revision=workspace.write(project,"member.md",text,0,false),artifact=artifacts.create(project,"member.md",1,new JSONObject().put("_taskId",task).put("_runId",run));
            ref=new JSONObject().put("teamRunId",uuid()).put("assignmentId",task).put("memberId","writer").put("taskId",task).put("runId",run).put("artifactId",artifact.getString("id")).put("draftProjectId",project).put("targetProjectId",target).put("path","member.md").put("baseVersion",0).put("baseHash","absent").put("draftVersion",1).put("draftRevisionId",revision.getString("revisionId")).put("sha256",artifact.getString("sha256"));
            TeamWorkspaceOutcomeHost.NativeSource source=new TeamWorkspaceOutcomeHost.NativeSource(){public JSONObject resolveTeamArtifact(String team,String assignment,String member,String child,String frozen,String id,RunControl control)throws Exception{checkTeamArtifact(ref,control);if(!team.equals(ref.getString("teamRunId"))||!assignment.equals(ref.getString("assignmentId"))||!member.equals(ref.getString("memberId"))||!child.equals(ref.getString("taskId"))||!frozen.equals(ref.getString("runId"))||!id.equals(ref.getString("artifactId")))throw new SecurityException("fixture source mismatch");return new JSONObject(ref.toString());}public void checkTeamArtifact(JSONObject exact,RunControl control)throws Exception{control.checkpoint();if(!exact.toString().equals(new JSONObject(ref.toString()).toString())||revokeAfterCopy.get()&&destination!=null&&destination.isFile()&&destination.length()>0)throw new SecurityException("fixture source revoked");}};
            host=new TeamWorkspaceOutcomeHost(source,artifacts::read);screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();flow=flow(controller(screen.get()));flow.bindTeamOutcomes(host,returned::set);flow.openTeamArtifact(TeamWorkspaceOutcomeHost.Selection.fromManifest(ref));await(()->!flow.getState().getBusy()&&flow.getState().getSnapshot()!=null);
        }
        void export()throws Exception {flow.prepareExport();destination=new File(root,"selected-result.md");android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="b4-saf-fixture";info.exported=false;ShadowContentResolver.registerProviderInternal("b4-saf-fixture",Robolectric.buildContentProvider(ExportProvider.class).create(info).get());flow.exportResult(Uri.parse("content://b4-saf-fixture/selected-document"));await(()->!flow.getState().getBusy()&&(flow.getState().getNotice()!=null||flow.getState().getError()!=null));}
        public void close(){screen.pause().stop().destroy();app.features.close();}
    }
    @Test public void realTeamFilePagesAndExportsThroughTheExistingOverlayThenReturnsToItsAssignment()throws Exception {
        try(Fixture f=new Fixture()){
            assertNull(f.flow.getState().getFile());assertNotNull(f.flow.getState().getTeam());assertEquals("teams",f.flow.getState().getSnapshot().getString("provider"));assertEquals(12000,f.flow.getState().getSnapshot().getString("content").length());capture(f.screen.get(),"native-team-workspace-artifact.png");
            f.flow.readTeamFile(12000);await(()->!f.flow.getState().getBusy()&&f.flow.getState().getSnapshot().getInt("offset")==12000);assertEquals(f.text.substring(12000),f.flow.getState().getSnapshot().getString("content"));f.export();assertNull(f.flow.getState().getError());assertArrayEquals(f.text.getBytes(StandardCharsets.UTF_8),Files.readAllBytes(destination.toPath()));assertEquals(0,AttachmentRepository.get(f.app).book.list().length());assertEquals(0,f.app.tasks.list().length());assertEquals(1,f.artifacts.list(null).length());
            f.flow.continueTask();await(()->f.returned.get()!=null&&!f.flow.getState().getOpen());assertEquals(f.ref.getString("assignmentId"),f.returned.get().getString("assignmentId"));assertEquals(f.ref.getString("teamRunId"),f.returned.get().getString("teamRunId"));assertFalse(f.returned.get().has("runId"));
        }
    }
    @Test public void exportRevocationAfterARealFileCopyReportsUnconfirmedEffect()throws Exception {
        try(Fixture f=new Fixture()){f.revokeAfterCopy.set(true);f.export();assertNotNull(f.flow.getState().getError());assertTrue(f.flow.getState().getError().contains("未完整确认"));assertNull(f.flow.getState().getNotice());assertTrue(destination.length()>0);assertEquals(1,f.artifacts.list(null).length());}
    }
}
