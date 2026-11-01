package dev.pocketengine.features.workspace;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceArtifactsTest {
    @Test public void immutableArtifactSurvivesRevisionPruningAndExportsActualContent()throws Exception {
        File root=Files.createTempDirectory("pocket-artifact-test").toFile();VersionedWorkspace workspace=new VersionedWorkspace(new File(root,"projects"));WorkspaceArtifacts artifacts=new WorkspaceArtifacts(new File(root,"artifacts"),workspace);
        String project=workspace.create("实际项目").getString("id");workspace.write(project,"src/demo.kt","first version\n",0,false);
        String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();JSONObject artifact=artifacts.create(project,"src/demo.kt",0,new JSONObject().put("_taskId",task).put("_runId",run));
        assertFalse(artifact.has("content"));assertEquals(task,artifact.getString("taskId"));assertEquals(run,artifact.getString("runId"));
        for(int i=1;i<12;i++)workspace.write(project,"src/demo.kt","changed "+i+"\n",i,false);
        try{workspace.read(project,"src/demo.kt",1);fail("old editable revision should be pruned");}catch(IllegalArgumentException expected){}
        assertEquals("first version\n",artifacts.read(artifact.getString("id")).getString("content"));
        JSONObject export=artifacts.prepareExport(new JSONObject().put("artifactId",artifact.getString("id")));assertEquals("demo.kt",export.getString("suggestedName"));assertEquals("first version\n",export.getString("content"));assertEquals(artifact.getString("sha256"),export.getString("sha256"));assertTrue(export.getBoolean("requiresDocumentPicker"));assertFalse(export.toString().contains(root.getAbsolutePath()));
        assertTrue(artifacts.diff(artifact.getString("id")).getString("diff").contains("+changed 11"));assertEquals(1,artifacts.list(project).length());
    }
    @Test public void exportContractsDistinguishProjectAndFileAndRejectAmbiguousTargets()throws Exception {
        File root=Files.createTempDirectory("pocket-export-document").toFile();VersionedWorkspace workspace=new VersionedWorkspace(new File(root,"projects"));WorkspaceArtifacts artifacts=new WorkspaceArtifacts(new File(root,"artifacts"),workspace);
        String project=workspace.create("可携带项目").getString("id");workspace.write(project,"notes/readme.md","# 你好\n",0,false);
        JSONObject file=artifacts.prepareExport(new JSONObject().put("projectId",project).put("path","notes/readme.md"));assertEquals("text/markdown",file.getString("mimeType"));assertEquals("readme.md",file.getString("suggestedName"));assertEquals("# 你好\n",file.getString("content"));
        JSONObject exported=artifacts.prepareExport(new JSONObject().put("projectId",project));assertEquals("application/json",exported.getString("mimeType"));assertEquals("pocketworld.project.v1",new JSONObject(exported.getString("content")).getString("format"));
        JSONObject artifact=artifacts.create(project,"notes/readme.md",0,new JSONObject());assertEquals("owner_workspace",artifact.getString("source"));
        try{artifacts.prepareExport(new JSONObject().put("projectId",project).put("artifactId",artifact.getString("id")));fail("ambiguous exports must be rejected");}catch(IllegalArgumentException expected){}
        try{artifacts.read("../secret");fail("artifact identity cannot become a host path");}catch(IllegalArgumentException expected){}
    }
}
