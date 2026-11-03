package dev.pocketengine.features.local.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Own process-group transport. This has no task, approval, retry or model authority. */
public final class NativeProcessSupervisor {
    /** Bound to the bundled process_supervisor.cpp ordinary protocol2 algorithm, not arbitrary JSON.
     * Source SHA256 102484ca83ac7225c45df1c1d8bc25a357e944017aea3eb24ccfe4957fc472d1.
     * Lost native wait ownership cannot establish group absence or invent a successful exit. */
    public static final String EXIT_RESULT_CONTRACT="pocketworld.posix-exit7-drain-v2:102484ca83ac7225c45df1c1d8bc25a357e944017aea3eb24ccfe4957fc472d1";
    public static final String LEGACY_EXIT_RESULT_CONTRACT="pocketworld.posix-exit7-no-child-v1:cb2abfe8465e654402d1344d11345bb7f43047fecb3dd0220fed44e07ef0571f";
    public static final String PTY_EXIT_RESULT_CONTRACT="pocketworld.posix-pty-exit8-v1:f9132cee828fcbafd4252e71a139cb7c21839b1336111e1a762fab0afe487524";
    public static final String PIPE_EXIT_RESULT_CONTRACT="pocketworld.posix-duplex-exit8-v1:0e9ce13c7e6438484c8f855f763227e0d4c5a0ffa0ae06c1b2490fad68f92423";
    /** Native-only ordered stdin packets. No FD, PID, JSON grant or terminal emulation. */
    public interface PipeObserver extends Observer {
        byte[] nextStdinCommand();
        boolean stdinCommandCurrent(long sequence);
        void stdinCommandResult(long sequence,int writtenBytes,int error,boolean effectMayHaveOccurred);
    }
    public interface PtyObserver extends Observer {
        /** Native birth evidence is an observation, never a signal handle after process death. */
        void ptyIdentity(long pid,long startTicks,long sessionId);
        /** One bounded command packet belonging to this same already admitted job, or null. */
        byte[] nextTerminalCommand();
        boolean terminalCommandCurrent(long sequence);
        void terminalCommandResult(long sequence,int writtenBytes,int error,int rows,int columns,boolean effectMayHaveOccurred);
    }
    public interface Observer {
        /** Native exec handshake of an owned leader, never HTTP/service readiness. */
        default boolean started(){return true;}
        /** Raw bytes, in the observed pipe order; false asks the supervisor to stop and drain. */
        boolean output(int stream, byte[] bytes);
        boolean cancelled();
    }
    public static final class Exit {
        public final int exitCode, signal, launchErrno;
        public final boolean cancelled, timedOut, groupAbsent, outputComplete,noChildCreated,sessionAbsent;
        public final String resultContract;
        private Exit(long[] result) {this(result,false);}
        private Exit(long[] result,boolean pty) {this(result,pty,pty?PTY_EXIT_RESULT_CONTRACT:EXIT_RESULT_CONTRACT);}
        private Exit(long[] result,boolean pty,String contract) {
            if (result == null || result.length != (pty?8:7)) throw new IllegalStateException("runtime_native_result_invalid");
            for(int i=0;i<3;i++)if(result[i]<Integer.MIN_VALUE||result[i]>Integer.MAX_VALUE)throw new IllegalStateException("runtime_native_result_invalid");
            if(result[0]<-1||result[1]<0||result[2]<0)throw new IllegalStateException("runtime_native_result_invalid");
            for(int i=3;i<result.length;i++)if(result[i]!=0&&result[i]!=1)throw new IllegalStateException("runtime_native_result_invalid");
            exitCode=(int)result[0]; signal=(int)result[1]; launchErrno=(int)result[2];
            cancelled=result[3]!=0; timedOut=result[4]!=0; groupAbsent=result[5]!=0; outputComplete=result[6]!=0;
            // Only this private actual-JNI constructor creates the fact. It is never
            // reconstructed from a model/feature receipt, old cache or an exception.
            sessionAbsent=pty&&result[7]==1;resultContract=contract;
            noChildCreated=exitCode==-1&&signal==0&&launchErrno>0&&!timedOut&&groupAbsent&&outputComplete&&(!pty||sessionAbsent);
        }
    }
    /** Validate a prior native proof; callers must separately verify its original host binding.
     * Missing/old/unknown receipts are not upgraded by inferring a new noChildCreated flag. */
    public static boolean validatesNoChildReceipt(JSONObject receipt) {
        boolean knownPipe=knownOrdinaryExitContract(receipt.optString("nativeResultContract"));
        boolean knownPty=(PTY_EXIT_RESULT_CONTRACT.equals(receipt.optString("nativeResultContract"))||PIPE_EXIT_RESULT_CONTRACT.equals(receipt.optString("nativeResultContract")))&&Boolean.TRUE.equals(receipt.opt("processSessionAbsent"));
        return (knownPipe||knownPty)&&"no_child_created".equals(receipt.optString("launchPhase"))
                &&Boolean.TRUE.equals(receipt.opt("noChildCreated"))&&Boolean.FALSE.equals(receipt.opt("processLaunched"))&&Boolean.FALSE.equals(receipt.opt("processExited"))
                &&integer(receipt.opt("exitCode"),-1)&&integer(receipt.opt("terminationSignal"),0)&&positiveInteger(receipt.opt("launchErrno"))
                &&Boolean.FALSE.equals(receipt.opt("timedOut"))&&Boolean.TRUE.equals(receipt.opt("processGroupAbsent"))&&Boolean.TRUE.equals(receipt.opt("outputComplete"));
    }
    private static boolean integer(Object value,long expected){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()==expected;}
    private static boolean positiveInteger(Object value){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>0&&((Number)value).longValue()<=Integer.MAX_VALUE;}
    /** Exact historical native contracts only. Callers still prove original host/job ownership. */
    public static boolean knownOrdinaryExitContract(String contract){return EXIT_RESULT_CONTRACT.equals(contract)||LEGACY_EXIT_RESULT_CONTRACT.equals(contract);}
    private static final class Library {
        static final Throwable ERROR;
        static {
            Throwable error=null;
            try { System.loadLibrary("pocketprocess"); } catch (LinkageError | SecurityException e) { error=e; }
            ERROR=error;
        }
    }
    private static final class OrdinaryLibrary {
        static final int PROTOCOL;
        static final String CONTRACT;
        static {int version=0;if(Library.ERROR==null)try{version=ordinaryProtocol();}catch(UnsatisfiedLinkError legacy){version=1;}catch(LinkageError|SecurityException unavailable){/* Not a legacy proof. */}
            PROTOCOL=version==1||version==2?version:0;CONTRACT=PROTOCOL==2?EXIT_RESULT_CONTRACT:PROTOCOL==1?LEGACY_EXIT_RESULT_CONTRACT:"";}
    }
    private static final class PtyLibrary {
        static final boolean AVAILABLE;
        static {boolean yes=false;try{yes=Library.ERROR==null&&ptyProtocol()==1;}catch(LinkageError|SecurityException unavailable){/* Old native libraries remain usable only for their declared pipe API. */}AVAILABLE=yes;}
    }
    private static final class PipeLibrary {
        static final boolean AVAILABLE;
        static {boolean yes=false;try{yes=Library.ERROR==null&&pipeProtocol()==1;}catch(LinkageError|SecurityException unavailable){/* No fallback to /dev/null or a PTY. */}AVAILABLE=yes;}
    }
    public boolean available() { return Library.ERROR==null&&OrdinaryLibrary.PROTOCOL!=0; }
    public boolean ptyAvailable(){return PtyLibrary.AVAILABLE;}
    public boolean pipesAvailable(){return PipeLibrary.AVAILABLE;}
    public Exit executePipes(List<String> command,Map<String,String> environment,File directory,long timeoutMillis,long graceMillis,PipeObserver observer)throws Exception {
        if(!pipesAvailable())throw new IllegalStateException("runtime_native_duplex_pipes_missing");
        if(command.isEmpty()||command.size()>256||!new File(command.get(0)).isAbsolute())throw new IllegalArgumentException("runtime_absolute_executable_required");
        if(timeoutMillis<1||timeoutMillis>300000||graceMillis<0||graceMillis>10000)throw new IllegalArgumentException("runtime_pipe_budget_invalid");
        byte[][] argv=new byte[command.size()][],env=new byte[environment.size()][];
        for(int i=0;i<argv.length;i++)argv[i]=bytes(command.get(i));int at=0;
        for(Map.Entry<String,String> entry:environment.entrySet()){if(!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("runtime_environment_key_invalid");env[at++]=bytes(entry.getKey()+"="+entry.getValue());}
        return new Exit(runPipes(argv,env,bytes(directory.getCanonicalPath()),timeoutMillis,graceMillis,java.util.Objects.requireNonNull(observer)),true,PIPE_EXIT_RESULT_CONTRACT);
    }
    public Exit executePty(List<String> command,Map<String,String> environment,File directory,long timeoutMillis,long graceMillis,int rows,int columns,PtyObserver observer)throws Exception {
        if(!ptyAvailable())throw new IllegalStateException("runtime_native_pty_missing");
        if(command.isEmpty()||command.size()>256||!new File(command.get(0)).isAbsolute())throw new IllegalArgumentException("runtime_absolute_executable_required");
        if(timeoutMillis<1||timeoutMillis>300000||graceMillis<0||graceMillis>10000||rows<2||rows>200||columns<4||columns>400)throw new IllegalArgumentException("runtime_pty_budget_or_size_invalid");
        byte[][] argv=new byte[command.size()][],env=new byte[environment.size()][];for(int i=0;i<argv.length;i++)argv[i]=bytes(command.get(i));int at=0;
        for(Map.Entry<String,String> entry:environment.entrySet()){if(!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("runtime_environment_key_invalid");env[at++]=bytes(entry.getKey()+"="+entry.getValue());}
        return new Exit(runPty(argv,env,bytes(directory.getCanonicalPath()),timeoutMillis,graceMillis,rows,columns,java.util.Objects.requireNonNull(observer)),true);
    }
    public Exit execute(List<String> command, Map<String,String> environment, File directory,
            long timeoutMillis, long graceMillis, Observer observer) throws Exception {
        if (!available()) throw new IllegalStateException("runtime_native_supervisor_missing",Library.ERROR);
        if (command.isEmpty() || command.size()>256 || !new File(command.get(0)).isAbsolute())
            throw new IllegalArgumentException("runtime_absolute_executable_required");
        if (timeoutMillis<1 || timeoutMillis>86400000 || graceMillis<0 || graceMillis>10000)
            throw new IllegalArgumentException("runtime_process_budget_invalid");
        byte[][] argv=new byte[command.size()][],env=new byte[environment.size()][];
        for(int i=0;i<argv.length;i++) argv[i]=bytes(command.get(i));
        int at=0;
        for(Map.Entry<String,String> entry:environment.entrySet()) {
            if(!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("runtime_environment_key_invalid");
            env[at++]=bytes(entry.getKey()+"="+entry.getValue());
        }
        return new Exit(run(argv,env,bytes(directory.getCanonicalPath()),timeoutMillis,graceMillis,
                java.util.Objects.requireNonNull(observer)),false,OrdinaryLibrary.CONTRACT);
    }
    private static byte[] bytes(String value) {
        if(value==null || value.indexOf('\0')>=0 || value.length()>1048576)
            throw new IllegalArgumentException("runtime_process_string_invalid");
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static native int ordinaryProtocol();
    private static native int ptyProtocol();
    private static native int pipeProtocol();
    private static native long[] runPipes(byte[][] argv,byte[][] environment,byte[] directory,long timeoutMillis,long graceMillis,PipeObserver observer);
    private static native long[] runPty(byte[][] argv,byte[][] environment,byte[] directory,long timeoutMillis,long graceMillis,int rows,int columns,PtyObserver observer);
    private static native long[] run(byte[][] argv,byte[][] environment,byte[] directory,
            long timeoutMillis,long graceMillis,Observer observer);
}
