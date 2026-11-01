package dev.pocketengine.features.local;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TermuxWorkspaceTransferTest {
    private static String record(String path,byte[] bytes){return Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8))+":"+Base64.getEncoder().encodeToString(bytes)+"\n";}
    private static JSONObject receipt(String text)throws Exception{return new JSONObject().put("status","completed").put("exitCode",0).put("receiptReceived",true).put("stdout",text);}
    private static JSONObject execute(File directory)throws Exception {
        Process process=new ProcessBuilder("/bin/sh").directory(directory).start();process.getOutputStream().write(TermuxWorkspaceTransfer.collectScript().getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
        String stdout=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8),stderr=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(process.waitFor(5,TimeUnit.SECONDS));
        return receipt(stdout).put("status",process.exitValue()==0?"completed":"failed").put("exitCode",process.exitValue()).put("stderr",stderr);
    }
    @Test public void actualFixedShellTransfersNestedHiddenUnicodeAndShellMetacharacters()throws Exception {
        File root=Files.createTempDirectory("termux-transfer-fixture").toFile();File folder=new File(root,"nested folder");assertTrue(folder.mkdir());
        Files.write(new File(folder,"quote'$(not-executed).txt").toPath(),"真实文本\nsecond line\n".getBytes(StandardCharsets.UTF_8));Files.write(new File(root,".hidden").toPath(),new byte[0]);
        JSONObject result=execute(root);assertEquals(result.toString(),0,result.getInt("exitCode"));JSONArray files=TermuxWorkspaceTransfer.decode(result);assertEquals(2,files.length());
        boolean found=false;for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if(file.getString("path").equals("nested folder/quote'$(not-executed).txt")){assertEquals("真实文本\nsecond line\n",file.getString("content"));found=true;}}assertTrue(found);assertFalse(new File(root,"not-executed").exists());
    }
    @Test public void actualScriptRejectsSymlinkAndOversizedProjectWithoutCompleteFooter()throws Exception {
        File root=Files.createTempDirectory("termux-transfer-boundary").toFile(),outside=Files.createTempFile("outside-transfer",".txt").toFile();Files.createSymbolicLink(new File(root,"link").toPath(),outside.toPath());
        JSONObject linked=execute(root);assertNotEquals(0,linked.getInt("exitCode"));assertFalse(linked.getString("stdout").contains("END:"));Files.delete(new File(root,"link").toPath());Files.write(new File(root,"large.txt").toPath(),new byte[32001]);JSONObject large=execute(root);assertNotEquals(0,large.getInt("exitCode"));assertFalse(large.getString("stdout").contains("END:"));
    }
    @Test public void decoderRejectsTruncationDuplicatesPathEscapeMalformedAndBinary()throws Exception {
        String normal=record("file.txt","safe".getBytes(StandardCharsets.UTF_8));
        for(String text:new String[]{"POCKETWORLD_TEXT_1\n"+normal,"POCKETWORLD_TEXT_1\n"+normal+"END:2\n","POCKETWORLD_TEXT_1\n"+normal+normal+"END:2\n","POCKETWORLD_TEXT_1\n"+record("../escape",new byte[0])+"END:1\n","POCKETWORLD_TEXT_1\n"+record("file",new byte[]{0})+"END:1\n","POCKETWORLD_TEXT_1\n"+record("file",new byte[]{(byte)0xC0,(byte)0x80})+"END:1\n","POCKETWORLD_TEXT_1\n"+record("parent",new byte[0])+record("parent/child",new byte[0])+"END:2\n"}){
            try{TermuxWorkspaceTransfer.decode(receipt(text));fail("Unsafe transfer accepted: "+text);}catch(Exception expected){}
        }
        try{TermuxWorkspaceTransfer.decode(receipt("POCKETWORLD_TEXT_1\nEND:0\n").put("outputTruncated",true));fail("Truncated receipt accepted");}catch(IllegalStateException expected){}
    }
    @Test public void completeEmptyTransferRepresentsRealDeletions()throws Exception {assertEquals(0,TermuxWorkspaceTransfer.decode(receipt("POCKETWORLD_TEXT_1\nEND:0\n")).length());}
    private static JSONObject selected(File directory,String path,String digest)throws Exception {
        Process process=new ProcessBuilder("/bin/sh").directory(directory).start();process.getOutputStream().write(TermuxWorkspaceTransfer.selectedScript(path,digest).getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();String out=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8),err=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(process.waitFor(5,TimeUnit.SECONDS));return receipt(out).put("status",process.exitValue()==0?"completed":"failed").put("exitCode",process.exitValue()).put("stderr",err).put("sourceCheckStatus","pre_exec_manifest_verified");
    }
    @Test public void selectedOutputDoesNotWalkLargeDependencyTreeAndHashesActualUtf8Bytes()throws Exception {
        File root=Files.createTempDirectory("selected-output").toFile(),dist=new File(root,"dist"),deps=new File(root,"node_modules");assertTrue(dist.mkdir());assertTrue(deps.mkdir());for(int i=0;i<110;i++)Files.writeString(new File(deps,"dependency"+i).toPath(),"x".repeat(1000));String path="dist/quote'$(not-executed).html",html="<!doctype html><h1>真实生成结果</h1>";Files.writeString(new File(root,path).toPath(),html);String digest="b".repeat(64);
        assertNotEquals(0,execute(root).getInt("exitCode"));JSONObject decoded=TermuxWorkspaceTransfer.decodeSelected(selected(root,path,digest),path,digest);assertEquals(html,decoded.getString("content"));assertEquals(html.getBytes(StandardCharsets.UTF_8).length,decoded.getInt("bytes"));assertEquals(dev.pocketengine.features.local.project.ProjectSources.sha256(html.getBytes(StandardCharsets.UTF_8)),decoded.getString("sha256"));assertFalse(new File(root,"not-executed").exists());
    }
    @Test public void selectedCallbackBoundIsExactly32000ActualBytes()throws Exception {
        File root=Files.createTempDirectory("selected-bound").toFile();String digest="b".repeat(64);Files.writeString(new File(root,"index.html").toPath(),"x".repeat(32000));assertEquals(32000,TermuxWorkspaceTransfer.decodeSelected(selected(root,"index.html",digest),"index.html",digest).getInt("bytes"));Files.writeString(new File(root,"index.html").toPath(),"x".repeat(32001));try{TermuxWorkspaceTransfer.decodeSelected(selected(root,"index.html",digest),"index.html",digest);fail();}catch(SecurityException expected){}
    }
    @Test public void selectedPathRejectsSymlinkParentSpecialAndMissingFile()throws Exception {
        File root=Files.createTempDirectory("selected-link").toFile(),outside=Files.createTempDirectory("selected-outside").toFile();Files.writeString(new File(outside,"index.html").toPath(),"outside");Files.createSymbolicLink(new File(root,"dist").toPath(),outside.toPath());assertNotEquals(0,selected(root,"dist/index.html","b".repeat(64)).getInt("exitCode"));Files.delete(new File(root,"dist").toPath());assertTrue(new File(root,"dist").mkdir());assertNotEquals(0,selected(root,"dist/missing.html","b".repeat(64)).getInt("exitCode"));assertNotEquals(0,selected(root,"dist","b".repeat(64)).getInt("exitCode"));
    }
    @Test public void selectedDecoderRejectsOtherPlanPathDigestSizeFlagsAndBinary()throws Exception {
        File root=Files.createTempDirectory("selected-decode").toFile();String digest="b".repeat(64);Files.writeString(new File(root,"index.html").toPath(),"safe");JSONObject valid=selected(root,"index.html",digest);
        for(JSONObject bad:new JSONObject[]{new JSONObject(valid.toString()).put("outputTruncated",true),new JSONObject(valid.toString()).put("cancelRequested",true),new JSONObject(valid.toString()).put("exitCode",0.5),new JSONObject(valid.toString()).put("sourceCheckStatus","unverified_or_mismatch"),new JSONObject(valid.toString()).put("stdout",valid.getString("stdout").replace("END:1","END:0")),new JSONObject(valid.toString()).put("stdout",valid.getString("stdout").replace(":4\n",":3\n"))}){try{TermuxWorkspaceTransfer.decodeSelected(bad,"index.html",digest);fail();}catch(Exception expected){}}
        try{TermuxWorkspaceTransfer.decodeSelected(valid,"other.html",digest);fail();}catch(SecurityException expected){}try{TermuxWorkspaceTransfer.decodeSelected(valid,"index.html","a".repeat(64));fail();}catch(IllegalArgumentException expected){}
        Files.write(new File(root,"index.html").toPath(),new byte[]{0});try{TermuxWorkspaceTransfer.decodeSelected(selected(root,"index.html",digest),"index.html",digest);fail();}catch(SecurityException expected){}
        Files.write(new File(root,"index.html").toPath(),new byte[]{(byte)0xc0,(byte)0x80});try{TermuxWorkspaceTransfer.decodeSelected(selected(root,"index.html",digest),"index.html",digest);fail();}catch(java.nio.charset.CharacterCodingException expected){}
    }
}
