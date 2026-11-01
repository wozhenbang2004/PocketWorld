package dev.pocketengine.features.workspace;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VersionedWorkspaceTest {
    @Test public void savesVersionsRejectsLostUpdatesAndRestoresCommittedContent()throws Exception {
        java.io.File root=Files.createTempDirectory("pocket-workspace-test").toFile();VersionedWorkspace workspace=new VersionedWorkspace(root);
        String id=workspace.create("手机小工具").getString("id");
        JSONObject first=workspace.write(id,"src/main.js","const a=1;\n",0,false);assertEquals(1,first.getInt("version"));assertFalse(first.getString("revisionId").isEmpty());assertEquals(first.getString("revisionId"),workspace.read(id,"src/main.js",1).getString("revisionId"));
        assertEquals(2,workspace.write(id,"src/main.js","const a=2;\n",1,false).getInt("version"));
        try{workspace.write(id,"src/main.js","lost update",1,false);fail();}catch(IllegalStateException conflict){assertTrue(conflict.getMessage().contains("版本冲突"));}
        assertEquals("const a=2;\n",new VersionedWorkspace(root).read(id,"src/main.js",0).getString("content"));
        assertEquals("const a=1;\n",workspace.read(id,"src/main.js",1).getString("content"));
        assertTrue(workspace.diff(id,"src/main.js",1).getString("diff").contains("+const a=2;"));
        assertEquals(2,workspace.history(id,"src/main.js").length());
    }
    @Test public void traversalIsRejectedAndDeletionRetainsOldRevision()throws Exception {
        VersionedWorkspace workspace=new VersionedWorkspace(Files.createTempDirectory("pocket-path-test").toFile());String id=workspace.create("demo").getString("id");
        for(String path:new String[]{"../secret","/data/file","a/../../secret","a\\b","a//b","a/./b","C:/file"})try{workspace.write(id,path,"x",0,false);fail(path);}catch(IllegalArgumentException expected){}
        workspace.write(id,"hello.txt","保留这份内容",0,false);workspace.write(id,"hello.txt","",1,true);assertEquals(0,workspace.list(id).length());
        try{workspace.read(id,"hello.txt",0);fail();}catch(IllegalStateException deleted){}
        assertEquals("保留这份内容",workspace.read(id,"hello.txt",1).getString("content"));
    }
    @Test public void revisionRetentionIsBounded()throws Exception {
        VersionedWorkspace workspace=new VersionedWorkspace(Files.createTempDirectory("pocket-history-test").toFile());String id=workspace.create("demo").getString("id");
        for(int i=0;i<12;i++)workspace.write(id,"x",String.valueOf(i),i,false);
        assertEquals(8,workspace.history(id,"x").length());assertEquals("11",workspace.read(id,"x",0).getString("content"));
        try{workspace.read(id,"x",1);fail();}catch(IllegalArgumentException gone){}
    }
    @Test public void exportContainsCommittedFilesAndOmitsDeletedContent()throws Exception {
        VersionedWorkspace workspace=new VersionedWorkspace(Files.createTempDirectory("pocket-export-test").toFile());String id=workspace.create("导出项目").getString("id");
        workspace.write(id,"src/hello.kt","fun main() = println(\"你好\")",0,false);
        workspace.write(id,"removed.txt","不要导出",0,false);workspace.write(id,"removed.txt","",1,true);
        JSONObject export=workspace.export(id);assertEquals("pocketworld.project.v1",export.getString("format"));assertEquals(1,export.getJSONArray("files").length());
        JSONObject file=export.getJSONArray("files").getJSONObject(0);assertEquals("src/hello.kt",file.getString("path"));assertEquals(1,file.getInt("version"));assertEquals(workspace.read(id,"src/hello.kt",0).getString("revisionId"),file.getString("revisionId"));
        assertEquals("fun main() = println(\"你好\")",file.getString("content"));assertFalse(export.toString().contains("不要导出"));assertFalse(export.getBoolean("includesHistory"));
    }
    @Test public void restoringATombstoneCannotBypassProjectFileLimit()throws Exception {
        VersionedWorkspace workspace=new VersionedWorkspace(Files.createTempDirectory("pocket-limit-test").toFile());String id=workspace.create("数量限制").getString("id");
        workspace.write(id,"restored.txt","old",0,false);workspace.write(id,"restored.txt","",1,true);
        for(int i=0;i<100;i++)workspace.write(id,"file-"+i,"x",0,false);
        try{workspace.write(id,"restored.txt","restored",2,false);fail("restoration must enforce the same cap as creation");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("100"));}
        assertEquals(100,workspace.list(id).length());
        try{workspace.write(id,"nonexistent","",0,true);fail("deleting a missing file must not create a hidden record");}catch(IllegalArgumentException expected){}
    }
    @Test public void crashLeftoverJsonNeverBecomesACommittedFileOrGhostRevision()throws Exception {
        java.io.File root=Files.createTempDirectory("pocket-pending-record-test").toFile();VersionedWorkspace workspace=new VersionedWorkspace(root);String id=workspace.create("恢复检查").getString("id");workspace.write(id,"notes.txt","committed",0,false);
        java.io.File folder=new java.io.File(root,id);java.io.File committed=folder.listFiles(file->file.getName().matches("[a-f0-9]{64}\\.json"))[0];JSONObject unfinished=JsonFiles.read(committed);unfinished.put("version",999);
        java.io.File pending=new java.io.File(folder,"pending-crash.json");Files.write(pending.toPath(),unfinished.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(new java.io.File(folder,"pending-partial.json").toPath(),"{unfinished".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VersionedWorkspace reopened=new VersionedWorkspace(root);assertEquals(1,reopened.list(id).length());assertEquals(1,reopened.list(id).getJSONObject(0).getInt("version"));JSONObject exported=reopened.export(id);assertEquals(1,exported.getJSONArray("files").length());assertEquals("committed",exported.getJSONArray("files").getJSONObject(0).getString("content"));assertTrue(pending.isFile());
        try{reopened.read(id,"notes.txt",999);fail("uncommitted revision must remain absent");}catch(IllegalArgumentException expected){}
    }
    @Test public void unfinishedPendingBytesDoNotConsumeCommittedProjectQuota()throws Exception {
        java.io.File root=Files.createTempDirectory("pocket-pending-quota-test").toFile();VersionedWorkspace workspace=new VersionedWorkspace(root);String id=workspace.create("配额检查").getString("id");workspace.write(id,"first.txt","first",0,false);
        java.io.File pending=new java.io.File(new java.io.File(root,id),"pending-large.json");try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(pending,"rw")){file.setLength(5*1024*1024L);}
        assertEquals(1,workspace.write(id,"second.txt","second",0,false).getInt("version"));assertEquals(2,workspace.list(id).length());assertTrue(pending.isFile());
    }
}
