package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

/** Real Linux JNI pipes and self-owned Python children; not TaskStore approval or Android acceptance. */
public final class NativePipeTransportTest {
    private abstract static class IO implements NativeProcessSupervisor.PipeObserver {
        final RuntimePipeInput queue=new RuntimePipeInput(1024*1024);final ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        final List<JSONObject> acks=new ArrayList<>();boolean cancel;int packets;
        public boolean cancelled(){return cancel;}
        public byte[] nextStdinCommand(){byte[] packet=queue.next();if(packet!=null)packets++;return packet;}
        public boolean stdinCommandCurrent(long sequence){try{return queue.current(sequence);}catch(Exception revoked){return false;}}
        public void stdinCommandResult(long sequence,int written,int error,boolean effects){try{acks.add(queue.observed(sequence,written,error,effects));acknowledged();}catch(Exception invalid){throw new IllegalStateException(invalid);}}
        public boolean output(int stream,byte[] bytes){try{(stream==1?stdout:stderr).write(bytes);return observed(stream);}catch(Exception invalid){throw new IllegalStateException(invalid);}}
        boolean observed(int stream)throws Exception{return true;}
        void acknowledged()throws Exception{}
        String text(){return new String(stdout.toByteArray(),StandardCharsets.UTF_8);}
        void write(String id,byte[] value)throws Exception{queue.offer(id,value,false,()->{});}
        void eof(String id)throws Exception{queue.offer(id,new byte[0],true,()->{});}
    }
    private NativeProcessSupervisor.Exit run(String program,IO io,long timeout)throws Exception {
        Path root=Files.createTempDirectory("pw-own-native-pipe-");Path script=root.resolve("server.py");Files.write(script,program.getBytes(StandardCharsets.UTF_8));
        try {var supervisor=new NativeProcessSupervisor();assertTrue(supervisor.pipesAvailable());return supervisor.executePipes(List.of(Path.of(Objects.requireNonNull(System.getProperty("a25.python"))).toRealPath().toString(),"-u",script.toString()),Map.of("PATH","/usr/bin:/bin","LANG","C.UTF-8"),root.toFile(),timeout,100,io);}
        finally {io.queue.close();Files.delete(script);Files.delete(root);}
    }
    @Test public void actualNonTtyUnicodeBytesHaveSeparateStderrAndOrderedRealEof()throws Exception {
        byte[] line="{\"jsonrpc\":\"2.0\",\"id\":1,\"text\":\"中文😀\"}\n".getBytes(StandardCharsets.UTF_8);
        IO io=new IO(){boolean offered;boolean observed(int stream)throws Exception{if(text().contains("READY")&&!offered){offered=true;write("original-line",line);eof("original-eof");}return true;}};
        var exit=run("import os,sys\nprint('TTY',os.isatty(0),os.isatty(1),os.isatty(2),flush=True)\nprint('ONLY_DIAGNOSTIC',file=sys.stderr,flush=True)\nprint('READY',flush=True)\nfor line in sys.stdin: print('ECHO:'+line.rstrip(),flush=True)\nprint('ACTUAL_EOF',flush=True)\n",io,5000);
        assertEquals(io.text(),0,exit.exitCode);assertTrue(exit.groupAbsent);assertTrue(exit.sessionAbsent);assertTrue(exit.outputComplete);
        assertEquals(NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT,exit.resultContract);assertTrue(io.text().contains("TTY False False False"));
        assertTrue(io.text().contains("中文😀"));assertTrue(io.text().contains("ACTUAL_EOF"));assertFalse(io.text().contains("ONLY_DIAGNOSTIC"));
        assertTrue(new String(io.stderr.toByteArray(),StandardCharsets.UTF_8).contains("ONLY_DIAGNOSTIC"));
        assertEquals(2,io.acks.size());assertEquals(line.length,io.acks.get(0).getInt("writtenBytes"));assertTrue(io.acks.get(1).getBoolean("stdinClosed"));assertEquals(2,io.packets);
    }
    @Test public void revokedAfterRealPartialWriteIsUnknownAndTheOriginalPacketIsNeverRequeued()throws Exception {
        byte[] large=new byte[128*1024];Arrays.fill(large,(byte)'x');
        IO io=new IO(){boolean offered;int checks;boolean observed(int stream)throws Exception{if(text().contains("READY")&&!offered){offered=true;write("partial",large);}return true;}public boolean stdinCommandCurrent(long sequence){return ++checks==1&&super.stdinCommandCurrent(sequence);}};
        var exit=run("import fcntl,time\nfcntl.fcntl(0,fcntl.F_SETPIPE_SZ,4096)\nprint('READY',flush=True)\ntime.sleep(60)\n",io,5000);
        assertTrue(exit.cancelled);assertTrue(exit.groupAbsent);assertTrue(exit.sessionAbsent);assertEquals(1,io.acks.size());JSONObject ack=io.acks.get(0);
        assertEquals("unknown",ack.getString("status"));assertTrue(ack.getInt("writtenBytes")>0);assertTrue(ack.getInt("writtenBytes")<large.length);assertTrue(ack.getBoolean("effectMayHaveOccurred"));assertEquals(1,io.packets);
        var original=io.queue.offer("partial",large,false,()->{});assertEquals(ack.getLong("inputSequence"),original.sequence);assertNull(io.queue.next());
    }
    @Test public void brokenReaderProducesKnownZeroByteAckWithoutGlobalSigpipe()throws Exception {
        IO io=new IO(){boolean offered;boolean observed(int stream)throws Exception{if(text().contains("READY")&&!offered){offered=true;write("closed-reader","one\n".getBytes(StandardCharsets.UTF_8));}return true;}};
        var exit=run("import os,time\nos.close(0)\nprint('READY',flush=True)\ntime.sleep(60)\n",io,5000);
        assertTrue(exit.cancelled);assertTrue(exit.groupAbsent);assertTrue(exit.sessionAbsent);assertEquals(1,io.acks.size());
        assertEquals("not_dispatched",io.acks.get(0).getString("status"));assertEquals(0,io.acks.get(0).getInt("writtenBytes"));assertFalse(io.acks.get(0).getBoolean("effectMayHaveOccurred"));assertTrue(io.acks.get(0).getInt("nativeErrno")>0);
    }
    @Test public void rejectedOutputBackpressureStopsAndDrainsRatherThanDroppingAndClaimingComplete()throws Exception {
        IO io=new IO(){boolean observed(int stream){return false;}};
        var exit=run("import os,time\nwhile True: os.write(1,b'own-output'*1000)\n",io,5000);
        assertTrue(exit.cancelled);assertTrue(exit.groupAbsent);assertTrue(exit.sessionAbsent);assertFalse(exit.outputComplete);assertTrue(io.stdout.size()>0);assertTrue(io.stdout.size()<=8192);
    }
    @Test public void lostAckAfterWriteIsUnknownAndNeverGetsASecondPacket()throws Exception {
        IO io=new IO(){boolean offered;int attempted;boolean observed(int stream)throws Exception{if(text().contains("READY")&&!offered){offered=true;write("lost","one\n".getBytes(StandardCharsets.UTF_8));}return true;}public void stdinCommandResult(long sequence,int bytes,int error,boolean effects){attempted++;assertTrue(bytes>0);throw new IllegalStateException("controlled original native ack delivery loss");}};
        var exit=run("import sys,time\nprint('READY',flush=True)\nsys.stdin.readline()\ntime.sleep(60)\n",io,5000);
        assertTrue(exit.cancelled);assertFalse(exit.outputComplete);assertTrue(exit.groupAbsent);assertTrue(exit.sessionAbsent);assertEquals(1,io.packets);assertEquals("unknown",io.queue.query("lost").getString("status"));assertFalse(io.queue.query("lost").getBoolean("nativeAcknowledged"));
    }
    @Test public void originalDeadlineIsNotRenewedByOutputOrBlockedStdin()throws Exception {
        IO io=new IO(){boolean offered;boolean observed(int stream)throws Exception{if(text().contains("READY")&&!offered){offered=true;write("blocked",new byte[128*1024]);}return true;}};
        long started=System.nanoTime();var exit=run("import fcntl,time\nfcntl.fcntl(0,fcntl.F_SETPIPE_SZ,4096)\nprint('READY',flush=True)\nwhile True:\n print('tick',flush=True)\n time.sleep(.03)\n",io,180);
        assertTrue(exit.timedOut);assertTrue(exit.cancelled);assertTrue(exit.groupAbsent);assertTrue(exit.sessionAbsent);assertTrue((System.nanoTime()-started)/1000000L<2500);assertEquals("unknown",io.queue.query("blocked").getString("status"));
    }
    @Test public void currentSameSessionDifferentGroupChildIsActuallyReapedBeforeRelease()throws Exception {
        IO io=new IO(){boolean observed(int stream){if(text().contains("CHILD_READY"))cancel=true;return true;}};
        var exit=run("import subprocess,os,signal,sys\nchild=subprocess.Popen(['/bin/sleep','60'],preexec_fn=lambda:os.setpgid(0,0))\ndef stop(*args):\n child.terminate()\n child.wait()\n sys.exit(0)\nsignal.signal(signal.SIGTERM,stop)\nprint('CHILD_READY',child.pid,flush=True)\nchild.wait()\n",io,5000);
        assertTrue(exit.cancelled);assertTrue(exit.groupAbsent);assertTrue(exit.sessionAbsent);String text=io.text().trim();long child=Long.parseLong(text.substring(text.lastIndexOf(' ')+1));assertFalse(Files.exists(Path.of("/proc/"+child)));
    }
    @Test public void actualEntryCancellationAndExecFailureKeepDifferentNativeProofs()throws Exception {
        Path root=Files.createTempDirectory("pw-own-native-pipe-entry-");try {
            NativeProcessSupervisor supervisor=new NativeProcessSupervisor();IO cancelled=new IO(){};cancelled.cancel=true;
            var absent=supervisor.executePipes(List.of("/bin/sh","-c","exit 7"),Map.of(),root.toFile(),1000,100,cancelled);
            assertTrue(absent.noChildCreated);assertTrue(absent.groupAbsent);assertTrue(absent.sessionAbsent);assertEquals(-1,absent.exitCode);assertEquals(0,cancelled.packets);
            var failed=supervisor.executePipes(List.of(root.resolve("missing").toString()),Map.of(),root.toFile(),1000,100,new IO(){});
            assertEquals(127,failed.exitCode);assertTrue(failed.launchErrno>0);assertFalse(failed.noChildCreated);assertTrue(failed.groupAbsent);assertTrue(failed.sessionAbsent);
        }finally {Files.delete(root);}
    }
}
