package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Actual host Python/venv only; this does not execute Android/ARM64 guest tools. */
public final class ManagedProjectVenvHostTest {
    @Test public void generatedManagedPlanCreatesAndRunsRealProjectVenvOnHostOnly() throws Exception {verifyGeneratedProjectVenv(false);}
    @Test public void generatedManagedVenvAlsoControlsChildPythonAndProjectCli() throws Exception {verifyGeneratedProjectVenv(true);}
    private static void verifyGeneratedProjectVenv(boolean checkChildren) throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("managed-project-venv-");
        try {
            ManagedPreparationPlanTest.Local local=new ManagedPreparationPlanTest.Local();local.ready=true;JSONArray files=local.source.getJSONArray("files");files.getJSONObject(0).put("content",checkChildren?"import os,sys,subprocess\nassert sys.prefix.endswith('/.venv'), sys.prefix\nassert os.environ.get('VIRTUAL_ENV') == sys.prefix, os.environ.get('VIRTUAL_ENV')\nassert subprocess.check_output(['python3','-c','import sys;print(sys.prefix)'],text=True).strip() == sys.prefix\nassert subprocess.check_output(['pocketworld-venv-fixture-helper'],text=True).strip() == sys.prefix\nprint(sys.executable)\n":"import sys\nassert sys.prefix.endswith('/.venv'), sys.prefix\nprint(sys.executable)\n");files.getJSONObject(1).put("content","");
            for(int i=0;i<files.length();i++)java.nio.file.Files.writeString(root.resolve(files.getJSONObject(i).getString("path")),files.getJSONObject(i).getString("content"));
            JSONObject dependencies=ProjectBuildPlan.step(local.plan("dependencies","managed_process_v1"),"dependencies").getJSONObject("payload");executeHostShell(root,dependencies);
            assertTrue(java.nio.file.Files.isRegularFile(root.resolve(".venv/pyvenv.cfg")));assertTrue(java.nio.file.Files.isExecutable(root.resolve(".venv/bin/python")));
            if(checkChildren){java.nio.file.Path helper=root.resolve(".venv/bin/pocketworld-venv-fixture-helper");java.nio.file.Files.writeString(helper,"#!"+root.resolve(".venv/bin/python")+"\nimport sys\nprint(sys.prefix)\n");assertTrue(helper.toFile().setExecutable(true));}
            JSONObject run=ProjectBuildPlan.step(local.plan("run","managed_process_v1"),"run").getJSONObject("payload");String output=executeHostShell(root,run);assertTrue(output,output.contains(root.resolve(".venv/bin/python").toString()));
        } finally {try(var paths=java.nio.file.Files.walk(root)){for(java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(path);}}
    }
    private static String executeHostShell(java.nio.file.Path directory,JSONObject payload) throws Exception {
        java.util.List<String> command=new java.util.ArrayList<>();command.add("/bin/sh");JSONArray args=payload.getJSONArray("argv");for(int i=0;i<args.length();i++)command.add(args.getString(i));
        java.io.File log=directory.resolve("fixture-output.txt").toFile();ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log);builder.environment().clear();builder.environment().put("PATH",new java.io.File(System.getProperty("a25.python")).getParent()+":/usr/local/bin:/usr/bin:/bin");builder.environment().put("HOME",directory.toString());
        Process process=builder.start();if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("host-only venv fixture timed out");}String output=java.nio.file.Files.readString(log.toPath());assertEquals(output,0,process.exitValue());return output;
    }

}
