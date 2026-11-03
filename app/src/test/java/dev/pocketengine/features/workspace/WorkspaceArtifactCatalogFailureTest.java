package dev.pocketengine.features.workspace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class WorkspaceArtifactCatalogFailureTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void committedCatalogWithLostAckKeepsBodyAndQueriesOriginalId()throws Exception {
        Fixture fixture=new Fixture(temporary.newFolder());
        JSONObject prior=fixture.original.create(fixture.project,"note.txt",0,new JSONObject());
        JSONObject[] attempted={null};int[] writes={0};IOException lostAck=new IOException("injected catalog ACK loss after real publication");
        WorkspaceArtifacts artifacts=new WorkspaceArtifacts(fixture.artifactRoot,fixture.workspace,(target,value)->{
            writes[0]++;attempted[0]=value.getJSONArray("artifacts").getJSONObject(1);
            JsonFiles.write(target,value);
            throw lostAck;
        });
        Exception failure=assertThrows(Exception.class,()->artifacts.create(fixture.project,"note.txt",0,new JSONObject()));
        String id=attempted[0].getString("id");File record=new File(fixture.artifactRoot,id+".json");
        assertTrue("A catalog whose ACK failed may already name this exact body",record.isFile());
        assertUnknown(failure,lostAck,id);assertEquals("Never replay the create",1,writes[0]);
        WorkspaceArtifacts cold=new WorkspaceArtifacts(fixture.artifactRoot,new VersionedWorkspace(fixture.projectRoot));
        JSONObject original=cold.read(id);
        assertEquals(id,original.getString("id"));assertEquals("original bytes\n",original.getString("content"));
        assertEquals(attempted[0].getString("sourceRevisionId"),original.getString("sourceRevisionId"));
        assertEquals(attempted[0].getString("sha256"),original.getString("sha256"));
        assertEquals(2,cold.list(fixture.project).length());assertEquals("original bytes\n",cold.read(prior.getString("id")).getString("content"));
        assertEquals("Querying never registers a replacement",2,recordCount(fixture.artifactRoot));
    }

    @Test public void catalogFailureBeforePublicationKeepsUnindexedOriginalWithoutClaimingSuccess()throws Exception {
        Fixture fixture=new Fixture(temporary.newFolder());
        JSONObject prior=fixture.original.create(fixture.project,"note.txt",0,new JSONObject());
        byte[] oldCatalog=Files.readAllBytes(new File(fixture.artifactRoot,"catalog.json").toPath());
        JSONObject[] attempted={null};int[] writes={0};IOException unavailable=new IOException("injected catalog failure before publication");
        WorkspaceArtifacts artifacts=new WorkspaceArtifacts(fixture.artifactRoot,fixture.workspace,(target,value)->{
            writes[0]++;attempted[0]=value.getJSONArray("artifacts").getJSONObject(1);throw unavailable;
        });
        Exception failure=assertThrows(Exception.class,()->artifacts.create(fixture.project,"note.txt",0,new JSONObject()));
        String id=attempted[0].getString("id");File record=new File(fixture.artifactRoot,id+".json");
        assertTrue("Uncertain publication must retain the original even when no catalog entry is visible",record.isFile());
        assertEquals("original bytes\n",JsonFiles.read(record).getString("content"));
        assertUnknown(failure,unavailable,id);assertEquals(1,writes[0]);
        assertArrayEquals(oldCatalog,Files.readAllBytes(new File(fixture.artifactRoot,"catalog.json").toPath()));
        WorkspaceArtifacts cold=new WorkspaceArtifacts(fixture.artifactRoot,new VersionedWorkspace(fixture.projectRoot));
        assertEquals(1,cold.list(fixture.project).length());assertEquals("original bytes\n",cold.read(prior.getString("id")).getString("content"));
        assertThrows(IllegalArgumentException.class,()->cold.read(id));
        assertEquals("A missing lookup is not permission to replay or delete the retained body",2,recordCount(fixture.artifactRoot));
    }

    @Test public void actualCatalogDestinationIoFailureRetainsOriginalAndReportsUnknown()throws Exception {
        Fixture fixture=new Fixture(temporary.newFolder());
        File catalog=new File(fixture.artifactRoot,"catalog.json");assertTrue(catalog.mkdirs());
        Files.writeString(new File(catalog,"retained-marker").toPath(),"do not overwrite");
        JSONObject[] attempted={null};int[] writes={0};Exception[] ioFailure={null};
        WorkspaceArtifacts artifacts=new WorkspaceArtifacts(fixture.artifactRoot,fixture.workspace,(target,value)->{
            writes[0]++;attempted[0]=value.getJSONArray("artifacts").getJSONObject(0);
            try{JsonFiles.write(target,value);}catch(Exception failed){ioFailure[0]=failed;throw failed;}
        });
        Exception failure=assertThrows(Exception.class,()->artifacts.create(fixture.project,"note.txt",0,new JSONObject()));
        String id=attempted[0].getString("id");
        assertTrue("Real Files.move failure must not delete the already-written body",new File(fixture.artifactRoot,id+".json").isFile());
        assertTrue(ioFailure[0] instanceof IOException);assertUnknown(failure,ioFailure[0],id);assertEquals(1,writes[0]);
        assertEquals("do not overwrite",Files.readString(new File(catalog,"retained-marker").toPath()));
        assertEquals(1,recordCount(fixture.artifactRoot));
    }

    private static void assertUnknown(Exception failure,Exception cause,String id){
        assertSame("Retain the actual storage failure",cause,failure.getCause());
        assertTrue(failure.getMessage().contains("未知"));assertTrue(failure.getMessage().contains(id));assertTrue(failure.getMessage().contains("不重放"));
    }
    private static int recordCount(File root){File[] records=root.listFiles(file->file.isFile()&&file.getName().matches("[a-f0-9-]{36}\\.json"));assertNotNull(records);return records.length;}
    private static final class Fixture {
        final File projectRoot,artifactRoot;final VersionedWorkspace workspace;final WorkspaceArtifacts original;final String project;
        Fixture(File root)throws Exception {
            projectRoot=new File(root,"projects");artifactRoot=new File(root,"artifacts");workspace=new VersionedWorkspace(projectRoot);original=new WorkspaceArtifacts(artifactRoot,workspace);
            project=workspace.create("catalog fault fixture").getString("id");workspace.write(project,"note.txt","original bytes\n",0,false);
        }
    }
}
