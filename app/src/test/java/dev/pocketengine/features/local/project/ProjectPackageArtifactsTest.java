package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectPackageArtifactsTest {
    @Test public void sameCatalogKeepsTextArtifactAndMultipleImmutableProjectVersionsAcrossColdReopen()throws Exception {
        File root=Files.createTempDirectory("project-package-catalog-").toFile();try{
            VersionedWorkspace workspace=new VersionedWorkspace(new File(root,"workspace"));String project=workspace.create("原项目").getString("id");workspace.write(project,"note.md","saved text",0,false);
            WorkspaceArtifacts artifacts=new WorkspaceArtifacts(new File(root,"artifacts"),workspace);JSONObject text=artifacts.create(project,"note.md",1,new JSONObject());
            File active=new File(root,"active");assertTrue(active.mkdir());ProjectPackageArchiveTest.text(active,"index.html","<h1>40.00</h1>");ProjectPackageArchiveTest.bytes(active,"raw.bin",new byte[]{0,1,-1,8});
            JSONObject source=ProjectPackageArchiveTest.original().put("projectId",project);var first=ProjectPackageArchive.capture(active,source,"source_project","",false);String client=UUID.randomUUID().toString();JSONObject one=artifacts.createProjectPackage(first,client);
            assertEquals(one.getString("id"),artifacts.createProjectPackage(first,client).getString("id"));assertFalse(one.getBoolean("businessVerified"));assertEquals("owner_active_project",one.getString("source"));assertEquals(2,artifacts.list(project).length());
            ProjectPackageArchiveTest.text(active,"index.html","<h1>45.00</h1>");var changed=ProjectPackageArchive.capture(active,source,"source_project","",false);
            try{artifacts.createProjectPackage(changed,client);fail("Original action overwrote immutable snapshot");}catch(SecurityException expected){}
            JSONObject two=artifacts.createProjectPackage(changed,UUID.randomUUID().toString());assertEquals(2,two.getInt("versionSequence"));assertNotEquals(one.getString("id"),two.getString("id"));
            var cold=new WorkspaceArtifacts(new File(root,"artifacts"),new VersionedWorkspace(new File(root,"workspace")));
            assertEquals("<h1>40.00</h1>",new String(cold.readProjectPackage(one.getString("id"),one.getString("sha256")).file("index.html"),StandardCharsets.UTF_8));assertEquals("saved text",cold.read(text.getString("id")).getString("content"));
            assertArrayEquals(new byte[]{0,1,-1,8},cold.readProjectPackage(one.getString("id"),one.getString("sha256")).file("raw.bin"));
            try{cold.readProjectPackage(one.getString("id"),two.getString("sha256"));fail("Wrong original SHA fell back to new artifact");}catch(SecurityException expected){}
            ByteArrayOutputStream out=new ByteArrayOutputStream();assertEquals(one.getLong("sizeBytes"),cold.exportProjectPackage(one.getString("id"),one.getString("sha256"),out));assertEquals(one.getString("sha256"),ProjectSources.sha256(out.toByteArray()));
            assertEquals(3,cold.list(project).length());assertFalse(one.has("taskId"));assertFalse(one.has("runId"));
        }finally{ProjectPackageArchiveTest.remove(root);}
    }
    @Test public void unknownOriginalPayloadIsNotOverwrittenAndCorruptionNeverUsesActiveFiles()throws Exception {
        File root=Files.createTempDirectory("project-package-corrupt-").toFile();try{
            VersionedWorkspace workspace=new VersionedWorkspace(new File(root,"workspace"));String project=workspace.create("原项目").getString("id");File active=new File(root,"active");assertTrue(active.mkdir());ProjectPackageArchiveTest.text(active,"index.html","original");var frozen=ProjectPackageArchive.capture(active,ProjectPackageArchiveTest.original().put("projectId",project),"source_project","",false);
            File folder=new File(root,"artifacts");assertTrue(folder.mkdir());WorkspaceArtifacts artifacts=new WorkspaceArtifacts(folder,workspace);String client=UUID.randomUUID().toString(),id=UUID.nameUUIDFromBytes(("project-package:"+client).getBytes(StandardCharsets.UTF_8)).toString();File unknown=new File(folder,id+".project.zip");Files.write(unknown.toPath(),new byte[]{4,5,6});
            try{artifacts.createProjectPackage(frozen,client);fail("Unknown private payload overwritten");}catch(SecurityException expected){}assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(unknown.toPath()));assertEquals(0,artifacts.list(project).length());
            JSONObject actual=artifacts.createProjectPackage(frozen,UUID.randomUUID().toString());File archive=new File(folder,actual.getString("id")+".project.zip");Files.write(archive.toPath(),new byte[]{0,0,0});
            try{artifacts.readProjectPackage(actual.getString("id"),actual.getString("sha256"));fail("Corrupt fixed package substituted active content");}catch(IllegalStateException expected){}
        }finally{ProjectPackageArchiveTest.remove(root);}
    }
    @Test public void explicitRemovalFreesOnlyPackagePayloadAndCatalogSlotButNeverReplaysRetiredAction()throws Exception {
        File root=Files.createTempDirectory("project-package-remove-").toFile();try{
            VersionedWorkspace workspace=new VersionedWorkspace(new File(root,"workspace"));String project=workspace.create("kept project").getString("id");File active=new File(root,"active");assertTrue(active.mkdir());ProjectPackageArchiveTest.text(active,"index.html","kept source");var frozen=ProjectPackageArchive.capture(active,ProjectPackageArchiveTest.original().put("projectId",project),"source_project","",false);File folder=new File(root,"artifacts");WorkspaceArtifacts artifacts=new WorkspaceArtifacts(folder,workspace);String action=UUID.randomUUID().toString();JSONObject selected=artifacts.createProjectPackage(frozen,action);
            JSONObject removal=artifacts.removeProjectPackage(selected.getString("id"),selected.getString("sha256"));assertTrue(removal.getBoolean("payloadCleanupComplete"));assertFalse(removal.getBoolean("sourceProjectRemoved"));assertFalse(removal.getBoolean("environmentRemoved"));assertEquals(0,artifacts.list(project).length());assertFalse(new File(folder,selected.getString("id")+".project.zip").exists());assertTrue(dev.pocketengine.features.workspace.JsonFiles.read(new File(folder,selected.getString("id")+".project.json")).getBoolean("retired"));assertEquals("kept source",Files.readString(new File(active,"index.html").toPath()));assertEquals(1,workspace.projects().length());
            try{artifacts.createProjectPackage(frozen,action);fail("Retired original action was replayed");}catch(IllegalStateException expected){}
        }finally{ProjectPackageArchiveTest.remove(root);}
    }
    @Test public void importedProjectIdentityIsIdempotentWithoutRestoringAuthorityOrChangingOldProject()throws Exception {
        File root=Files.createTempDirectory("project-package-project-").toFile();try{
            VersionedWorkspace workspace=new VersionedWorkspace(root);String old=workspace.create("old").getString("id");workspace.write(old,"old.txt","untouched",0,false);String action=UUID.randomUUID().toString(),sha="a".repeat(64);
            JSONObject imported=workspace.createImported("imported",action,sha);assertFalse(imported.getJSONObject("sourceImport").getBoolean("executionAuthorityRestored"));assertEquals(imported.getString("id"),workspace.createImported("imported",action,sha).getString("id"));assertEquals(2,workspace.projects().length());assertEquals("untouched",workspace.read(old,"old.txt",0).getString("content"));
            try{workspace.createImported("renamed",action,sha);fail("Original import action silently relabeled project");}catch(SecurityException expected){}
        }finally{ProjectPackageArchiveTest.remove(root);}
    }
}
