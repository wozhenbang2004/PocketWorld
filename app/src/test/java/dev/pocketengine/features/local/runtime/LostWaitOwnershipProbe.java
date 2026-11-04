package dev.pocketengine.features.local.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated test JVM only. Owner death permits this test subreaper to consume the exact original command. */
public final class LostWaitOwnershipProbe {
    private static native void arm(int observations);
    private static native long[] facts();
    public static void main(String[] args)throws Exception {
        NativeProcessSupervisor supervisor=new NativeProcessSupervisor();if(args[0].equals("availability")){System.out.println(new JSONObject().put("available",supervisor.available()));return;}if(!supervisor.available())throw new AssertionError("Original JNI unavailable");
        String mode=args[0],python=new File(args[1]).getCanonicalPath();File root=new File(args[2]);int after=Integer.parseInt(args[3]);arm(after);
        AtomicBoolean finished=new AtomicBoolean();Thread watchdog=new Thread(()->{try{Thread.sleep(5500);if(!finished.get()&&facts()[1]==1){System.out.println(new JSONObject().put("watchdogAfterActualReap",true).put("facts",proof()).toString());System.out.flush();Runtime.getRuntime().halt(124);}}catch(Exception failure){throw new AssertionError(failure);}},"own-lost-wait-proof");watchdog.setDaemon(true);watchdog.start();
        long start=System.nanoTime();List<String> command=List.of(python,"-I","-c","import sys,time; print('original-output',flush=True); time.sleep(.08); sys.exit(7)");
        Map<String,String> environment=Map.of("PATH",new File(python).getParent(),"LANG","C.UTF-8","POCKETWORLD_ENVIRONMENT_IDENTITY","original-environment");JSONObject result;
        if(mode.equals("direct")){
            NativeProcessSupervisor.Exit exit=supervisor.execute(command,environment,root,1200,50,new NativeProcessSupervisor.Observer(){public boolean output(int stream,byte[] bytes){return true;}public boolean cancelled(){return false;}});
            result=new JSONObject().put("exitCode",exit.exitCode).put("signal",exit.signal).put("launchErrno",exit.launchErrno).put("noChildCreated",exit.noChildCreated).put("groupAbsent",exit.groupAbsent).put("outputComplete",exit.outputComplete).put("contract",exit.resultContract);
        }else{
            File active=Files.createDirectory(root.toPath().resolve("active")).toFile();Files.write(active.toPath().resolve("Main.java"),"class Main {}\n".getBytes(StandardCharsets.UTF_8));boolean preparation=mode.equals("preparation");
            ManagedProcessJobs jobs=new ManagedProcessJobs(new File(root,"runtime"),supervisor);AtomicInteger closes=new AtomicInteger();JSONObject source=RuntimeContractsTest.source();
            JSONObject exact=RuntimeContractsTest.exact(source,preparation?"termux.prepare":"termux.run",preparation?null:new JSONArray(command.subList(1,command.size())));
            if(!preparation){exact.getJSONObject("payload").put("executable","python3");exact.put("requestDigest",dev.pocketengine.features.local.project.ProjectSources.digest(exact.getJSONObject("payload")));}
            RuntimeInvocation invocation=new RuntimeInvocation(exact,RuntimeContractsTest.binding("lost-wait-"+mode),source,RuntimeContractsTest.original(),()->{},new RuntimeWorkspace(active,"project",RuntimeContractsTest.CHECKOUT));
            if(preparation)jobs.startPreparation(invocation,"original-environment",context->{context.execute(command,environment,active,1200);return new JSONObject();},()->closes.incrementAndGet());
            else jobs.start(invocation,command,environment,active,1200,()->closes.incrementAndGet());
            JSONObject actual;do{actual=jobs.query(invocation.executionRef()).json();if(actual.has("finishedAt")&&!actual.optBoolean("ownedWorkerLive"))break;Thread.sleep(5);}while((System.nanoTime()-start)/1000000<4500);
            if(!actual.has("finishedAt")||actual.optBoolean("ownedWorkerLive"))throw new AssertionError("Original native wait did not settle");
            boolean closeRejected=false,idleRejected=false;try{jobs.close();}catch(IllegalStateException held){if(!"runtime_process_drain_unconfirmed".equals(held.getMessage()))throw held;closeRejected=true;}
            try{jobs.requireEnvironmentIdle(null);}catch(IllegalStateException held){if(!"runtime_environment_has_live_or_unresolved_job".equals(held.getMessage()))throw held;idleRejected=true;}
            JSONObject cold,cancel;try(ManagedProcessJobs observer=new ManagedProcessJobs(new File(root,"runtime"),supervisor)){cold=observer.query(invocation.executionRef()).json();cancel=observer.requestCancel(invocation.executionRef()).json();}
            result=new JSONObject().put("actual",actual).put("cold",cold).put("coldCancel",cancel).put("leaseCloseCount",closes.get()).put("closeRejected",closeRejected).put("maintenanceRejected",idleRejected);
        }
        finished.set(true);System.out.println(new JSONObject().put("mode",mode).put("receipt",result).put("facts",proof()).put("elapsedMillis",(System.nanoTime()-start)/1000000));System.out.flush();
    }
    private static JSONObject proof()throws Exception{long[] v=facts();return new JSONObject().put("zombieObservations",v[0]).put("actualReapCount",v[1]).put("exactReapedPid",v[2]).put("realWaitStatus",v[3]).put("signalsAfterReap",v[4]).put("realEchildCount",v[5]);}
}
