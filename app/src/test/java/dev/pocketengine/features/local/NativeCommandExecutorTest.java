package dev.pocketengine.features.local;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeCommandExecutorTest {
    private JSONObject finish(NativeCommandExecutor executor,String id)throws Exception{long limit=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<limit){JSONObject job=executor.get(id);if(!Arrays.asList("queued","running","stopping").contains(job.optString("status")))return job;Thread.sleep(10);}throw new AssertionError("Local process did not finish");}
    @Test public void actualSystemCommandOutputIsRetainedAndOperationCannotChange()throws Exception {
        File root=Files.createTempDirectory("pocket-local-test").toFile(),cwd=new File(root,"project");assertTrue(cwd.mkdir());Files.write(new File(cwd,"hello.txt").toPath(),"actual local output\n".getBytes(StandardCharsets.UTF_8));
        try(NativeCommandExecutor executor=new NativeCommandExecutor(new File(root,"receipts"),Arrays.asList(new File("/usr/bin"),new File("/bin")),job->{})){
            JSONArray argv=new JSONArray().put("cat").put("hello.txt");executor.submit("project",cwd,argv,"one-local-operation",2);JSONObject job=finish(executor,"one-local-operation");assertEquals("completed",job.getString("status"));assertEquals("actual local output\n",job.getString("output"));assertEquals("actual local output\n",job.getString("stdout"));assertEquals("",job.getString("stderr"));assertFalse(job.getBoolean("verified"));assertEquals(job.getString("id"),executor.submit("project",cwd,argv,"one-local-operation",2).getString("id"));
            try{executor.submit("project",cwd,new JSONArray().put("pwd"),"one-local-operation",2);fail("Operation arguments were changed");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void shellCommandsPathEscapeSymlinkAndWritingOptionsAreRejected()throws Exception {
        File root=Files.createTempDirectory("pocket-local-boundary").toFile(),cwd=new File(root,"project");assertTrue(cwd.mkdir());Files.write(new File(cwd,"input").toPath(),"b\na\n".getBytes(StandardCharsets.UTF_8));Files.createSymbolicLink(new File(cwd,"escape").toPath(),new File(root,"outside").toPath());
        try(NativeCommandExecutor executor=new NativeCommandExecutor(new File(root,"receipts"),Arrays.asList(new File("/usr/bin"),new File("/bin")),job->{})){
            for(JSONArray args:new JSONArray[]{new JSONArray().put("sh").put("-c").put("echo unsafe"),new JSONArray().put("cat").put("../outside"),new JSONArray().put("cat").put("escape"),new JSONArray().put("sort").put("-o").put("input")}){
                try{executor.submit("project",cwd,args,"rejected-"+System.nanoTime(),2);fail("Unsafe local command accepted");}catch(SecurityException expected){}
            }
            assertEquals(0,executor.list().length());
        }
    }
    @Test public void cancellingTheActualProcessReturnsCancelledAndKeepsItsReceipt()throws Exception {
        File root=Files.createTempDirectory("pocket-local-cancel").toFile(),cwd=new File(root,"project"),tools=new File(root,"tools");assertTrue(cwd.mkdir());assertTrue(tools.mkdir());Files.write(new File(cwd,"input").toPath(),"data".getBytes(StandardCharsets.UTF_8));File fake=new File(tools,"cat");Files.write(fake.toPath(),"#!/bin/sh\nexec /bin/sleep 20\n".getBytes(StandardCharsets.UTF_8));assertTrue(fake.setExecutable(true));
        try(NativeCommandExecutor executor=new NativeCommandExecutor(new File(root,"receipts"),Arrays.asList(tools),job->{})){
            executor.submit("project",cwd,new JSONArray().put("cat").put("input"),"cancel-local",30);executor.cancel("cancel-local");assertEquals("cancelled",finish(executor,"cancel-local").getString("status"));assertTrue(new File(root,"receipts/cancel-local.json").isFile());
        }
    }
    @Test public void stdoutAndStderrRemainSeparateAndFailureIsReal()throws Exception {
        File root=Files.createTempDirectory("pocket-local-streams").toFile(),cwd=new File(root,"project"),tools=new File(root,"tools");assertTrue(cwd.mkdir());assertTrue(tools.mkdir());
        File command=new File(tools,"pwd");Files.write(command.toPath(),"#!/bin/sh\nprintf 'stdout-only\\n'\nprintf 'stderr-only\\n' >&2\nexit 7\n".getBytes(StandardCharsets.UTF_8));assertTrue(command.setExecutable(true));
        try(NativeCommandExecutor executor=new NativeCommandExecutor(new File(root,"receipts"),Arrays.asList(tools),job->{})){
            executor.submit("project",cwd,new JSONArray().put("pwd"),"separate-streams",2);JSONObject result=finish(executor,"separate-streams");
            assertEquals("failed",result.getString("status"));assertEquals(7,result.getInt("exitCode"));assertEquals("stdout-only\n",result.getString("stdout"));assertEquals("stderr-only\n",result.getString("stderr"));
            assertFalse(executor.list().getJSONObject(0).has("stdout"));assertFalse(executor.list().getJSONObject(0).has("stderr"));
        }
    }
    @Test public void cancellationEscalatesWhenActualProcessIgnoresTerm()throws Exception {
        File root=Files.createTempDirectory("pocket-local-resistant").toFile(),cwd=new File(root,"project"),tools=new File(root,"tools");assertTrue(cwd.mkdir());assertTrue(tools.mkdir());
        File command=new File(tools,"pwd");Files.write(command.toPath(),"#!/bin/sh\ntrap '' TERM\nwhile :; do :; done\n".getBytes(StandardCharsets.UTF_8));assertTrue(command.setExecutable(true));
        try(NativeCommandExecutor executor=new NativeCommandExecutor(new File(root,"receipts"),Arrays.asList(tools),job->{})){
            executor.submit("project",cwd,new JSONArray().put("pwd"),"resistant",30);long limit=System.nanoTime()+2_000_000_000L;while(!executor.get("resistant").optString("status").equals("running")&&System.nanoTime()<limit)Thread.sleep(5);
            executor.cancel("resistant");assertEquals("cancelled",finish(executor,"resistant").getString("status"));
        }
    }

    @Test public void uniqOutputOperandIsRejectedBeforeDispatchAndInputOnlyRemainsReadOnly()throws Exception {
        File root=Files.createTempDirectory("pocket-uniq-readonly").toFile(),cwd=new File(root,"project");assertTrue(cwd.mkdir());
        File input=new File(cwd,"input.txt"),valuable=new File(cwd,"valuable.txt");
        Files.write(input.toPath(),"a\na\nb\n".getBytes(StandardCharsets.UTF_8));
        byte[] original="valuable original content\n".getBytes(StandardCharsets.UTF_8);Files.write(valuable.toPath(),original);
        try(NativeCommandExecutor executor=new NativeCommandExecutor(new File(root,"receipts"),Arrays.asList(new File("/usr/bin"),new File("/bin")),job->{})){
            assertThrows(SecurityException.class,()->executor.submit("project",cwd,new JSONArray().put("uniq").put("input.txt").put("valuable.txt"),"reject-uniq-output",2));
            assertArrayEquals(original,Files.readAllBytes(valuable.toPath()));assertEquals(0,executor.list().length());
            executor.submit("project",cwd,new JSONArray().put("uniq").put("input.txt"),"uniq-input-only",2);
            assertEquals("a\nb\n",finish(executor,"uniq-input-only").getString("stdout"));assertArrayEquals(original,Files.readAllBytes(valuable.toPath()));
        }
    }

}
