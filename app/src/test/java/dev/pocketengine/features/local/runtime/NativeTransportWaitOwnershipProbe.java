package dev.pocketengine.features.local.runtime;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Separate test JVM. Only its exact owned helper/command tree is faulted or reaped. */
public final class NativeTransportWaitOwnershipProbe {
    private static native void arm(int phase);
    private static native long[] facts();
    private static final class IO implements NativeProcessSupervisor.PtyObserver,NativeProcessSupervisor.PipeObserver {
        final boolean cancelOnOutput;boolean cancel;
        IO(boolean cancelOnOutput){this.cancelOnOutput=cancelOnOutput;}
        public boolean output(int stream,byte[] bytes){if(cancelOnOutput)cancel=true;return true;}
        public boolean cancelled(){return cancel;}
        public void ptyIdentity(long pid,long born,long session){}
        public byte[] nextTerminalCommand(){return null;}
        public boolean terminalCommandCurrent(long sequence){return false;}
        public void terminalCommandResult(long sequence,int bytes,int error,int rows,int columns,boolean effects){}
        public byte[] nextStdinCommand(){return null;}
        public boolean stdinCommandCurrent(long sequence){return false;}
        public void stdinCommandResult(long sequence,int bytes,int error,boolean effects){}
    }
    public static void main(String[] args)throws Exception {
        NativeProcessSupervisor supervisor=new NativeProcessSupervisor();
        if(args[0].equals("availability")) {
            System.out.println(new JSONObject().put("available",supervisor.available()).put("ptyAvailable",supervisor.ptyAvailable()).put("pipesAvailable",supervisor.pipesAvailable()));return;
        }
        if(!supervisor.available())throw new AssertionError("Original JNI unavailable");
        String mode=args[0],python=new File(args[1]).getCanonicalPath();int phase=Integer.parseInt(args[3]);arm(phase);
        String program="import sys,time; print('original-output',flush=True); time.sleep("+(phase==4||phase==5?"60":".08")+"); sys.exit(7)";
        List<String> command=List.of(python,"-I","-c",program);
        Map<String,String> environment=Map.of("PATH",new File(python).getParent(),"LANG","C.UTF-8");
        IO io=new IO(phase==4||phase==5);long start=System.nanoTime();
        NativeProcessSupervisor.Exit exit=mode.equals("pty")
                ?supervisor.executePty(command,environment,new File(args[2]),1200,50,24,80,io)
                :mode.equals("pipes")?supervisor.executePipes(command,environment,new File(args[2]),1200,50,io)
                :supervisor.execute(command,environment,new File(args[2]),1200,50,io);
        long[] facts=facts();
        JSONObject proof=new JSONObject().put("zombieObservations",facts[0]).put("actualReapCount",facts[1])
                .put("exactReapedPid",facts[2]).put("realWaitStatus",facts[3]).put("signalsAfterReap",facts[4]).put("realEchildCount",facts[5])
                .put("appNumericSignals",facts[6]).put("appReapAttempts",facts[7]).put("ownerReapCount",facts[8]).put("ownerWaitStatus",facts[9])
                .put("ownerNumericSignals",facts[10]).put("faultTriggered",facts[11]).put("inheritedFdClosed",facts[12]==1)
                .put("controlFdClosed",facts[13]==1).put("appFdPreserved",facts[14]==1).put("pidfdProbes",facts[15]);
        JSONObject receipt=new JSONObject().put("exitCode",exit.exitCode).put("signal",exit.signal).put("launchErrno",exit.launchErrno)
                .put("noChildCreated",exit.noChildCreated).put("groupAbsent",exit.groupAbsent).put("sessionAbsent",exit.sessionAbsent)
                .put("outputComplete",exit.outputComplete).put("contract",exit.resultContract);
        System.out.println(new JSONObject().put("mode",mode).put("receipt",receipt).put("facts",proof).put("elapsedMillis",(System.nanoTime()-start)/1000000));
    }
}
