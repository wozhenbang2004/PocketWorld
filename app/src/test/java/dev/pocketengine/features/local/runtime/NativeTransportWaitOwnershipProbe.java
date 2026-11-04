package dev.pocketengine.features.local.runtime;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Separate test JVM. Its linked helper consumes only the original observed zombie. */
public final class NativeTransportWaitOwnershipProbe {
    private static native void arm(int phase);
    private static native long[] facts();
    private static final class IO implements NativeProcessSupervisor.PtyObserver,NativeProcessSupervisor.PipeObserver {
        public boolean output(int stream,byte[] bytes){return true;}
        public boolean cancelled(){return false;}
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
            System.out.println(new JSONObject().put("ptyAvailable",supervisor.ptyAvailable()).put("pipesAvailable",supervisor.pipesAvailable()));return;
        }
        if(!supervisor.available())throw new AssertionError("Original JNI unavailable");
        String mode=args[0],python=new File(args[1]).getCanonicalPath();arm(Integer.parseInt(args[3]));
        List<String> command=List.of(python,"-I","-c","import sys,time; print('original-output',flush=True); time.sleep(.08); sys.exit(7)");
        Map<String,String> environment=Map.of("PATH",new File(python).getParent(),"LANG","C.UTF-8");
        IO io=new IO();long start=System.nanoTime();
        NativeProcessSupervisor.Exit exit=mode.equals("pty")
                ?supervisor.executePty(command,environment,new File(args[2]),1200,50,24,80,io)
                :supervisor.executePipes(command,environment,new File(args[2]),1200,50,io);
        long[] facts=facts();
        JSONObject proof=new JSONObject().put("zombieObservations",facts[0]).put("actualReapCount",facts[1])
                .put("exactReapedPid",facts[2]).put("realWaitStatus",facts[3]).put("signalsAfterReap",facts[4]).put("realEchildCount",facts[5]);
        JSONObject receipt=new JSONObject().put("exitCode",exit.exitCode).put("signal",exit.signal).put("launchErrno",exit.launchErrno)
                .put("noChildCreated",exit.noChildCreated).put("groupAbsent",exit.groupAbsent).put("sessionAbsent",exit.sessionAbsent)
                .put("outputComplete",exit.outputComplete).put("contract",exit.resultContract);
        System.out.println(new JSONObject().put("mode",mode).put("receipt",receipt).put("facts",proof).put("elapsedMillis",(System.nanoTime()-start)/1000000));
    }
}
