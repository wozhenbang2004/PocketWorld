package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Fixed owner diagnostic, using the original verified generation and native process cleanup. */
final class RuntimeOwnerSelfCheck {
    private RuntimeOwnerSelfCheck() {}
    static JSONObject run(AndroidRuntimePreparation android,Map<String,RuntimeTool> tools,File temporary,RunControl original)throws Exception{
        original.check();NativeProcessSupervisor supervisor=new NativeProcessSupervisor();
        if(!supervisor.available())return new JSONObject().put("status","partial").put("reason","native_supervisor_unavailable").put("resourcesReleased",true);
        if(android!=null&&!android.ready()||android==null&&(!tools.containsKey("sh")||!tools.containsKey("python3")))return new JSONObject().put("status","partial").put("reason","runtime_not_prepared").put("automaticDownload",false).put("resourcesReleased",true);
        AutoCloseable generation=android==null?()->{}:android.acquire();JSONObject result=new JSONObject().put("resourcesReleased",false);boolean processesReleased=false;
        try{
            List<String> shell=android==null?tools.get("sh").command(List.of("-c","printf 'pocketworld-shell-ok\\n'")):android.command(null,".",List.of(android.guestTool("sh"),"-c","printf 'pocketworld-shell-ok\\n'"));
            List<String> python=android==null?tools.get("python3").command(List.of("-B","-c","print('pocketworld-python-ok')")):android.command(null,".",List.of(android.guestTool("python3"),"-B","-c","print('pocketworld-python-ok')"));
            Map<String,String> environment=android==null?Map.of("PATH","/system/bin","TMPDIR",temporary.getCanonicalPath()):android.hostEnvironment(android.identity());
            JSONObject sh=probe(supervisor,shell,environment,temporary,"pocketworld-shell-ok",original),py=probe(supervisor,python,environment,temporary,"pocketworld-python-ok",original);
            result.put("shell",sh).put("python",py).put("status",sh.getBoolean("passed")&&py.getBoolean("passed")?"passed":"failed").put("automaticDownload",false).put("userProjectTouched",false);
            processesReleased=sh.optBoolean("processGroupAbsent")&&sh.optBoolean("outputComplete")&&py.optBoolean("processGroupAbsent")&&py.optBoolean("outputComplete");
        }catch(Exception failure){result.put("status",original.isCancelled()?"skipped":"failed").put("reason","fixed_runtime_probe_failed");}
        finally{try{generation.close();result.put("resourcesReleased",processesReleased);}catch(Exception cleanup){result.put("status","failed").put("reason","generation_release_unconfirmed").put("resourcesReleased",false);}}
        return result;
    }
    private static JSONObject probe(NativeProcessSupervisor supervisor,List<String> command,Map<String,String> environment,File directory,String expected,RunControl original)throws Exception{
        original.check();ByteArrayOutputStream output=new ByteArrayOutputStream();
        NativeProcessSupervisor.Exit exit=supervisor.execute(command,environment,directory,5000,1000,new NativeProcessSupervisor.Observer(){
            public boolean output(int stream,byte[] bytes){if(output.size()+bytes.length>4096)return false;if(stream==1)output.write(bytes,0,bytes.length);return !cancelled();}
            public boolean cancelled(){return original.isCancelled()||original.isPaused()||Thread.currentThread().isInterrupted();}
        });
        boolean marker=new String(output.toByteArray(),StandardCharsets.UTF_8).strip().equals(expected);
        return new JSONObject().put("passed",marker&&exit.exitCode==0&&exit.launchErrno==0&&!exit.cancelled&&!exit.timedOut&&exit.groupAbsent&&exit.outputComplete)
                .put("exitCode",exit.exitCode).put("outputMarkerMatched",marker).put("timedOut",exit.timedOut).put("cancelled",exit.cancelled).put("processGroupAbsent",exit.groupAbsent).put("outputComplete",exit.outputComplete);
    }
}
