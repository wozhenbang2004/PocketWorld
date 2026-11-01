package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** Actual host POSIX PTY/JNI; not Android ARM, a shell sandbox or a model/provider test. */
public final class NativePtyTransportTest {
    private static final String PYTHON=System.getProperty("c2.fixture.python","/usr/bin/python3");
    private abstract static class IO implements NativeProcessSupervisor.PtyObserver {
        final RuntimePtyInput queue=new RuntimePtyInput(4096);final ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean cancel;long pid,born,session;int acks;
        public void ptyIdentity(long pid,long born,long session){this.pid=pid;this.born=born;this.session=session;}
        public boolean cancelled(){return cancel;}
        public byte[] nextTerminalCommand(){return queue.next();}
        public boolean terminalCommandCurrent(long sequence){try{queue.checkCurrent(sequence);return true;}catch(Exception error){return false;}}
        public boolean output(int stream,byte[] value){assertEquals("PTY has one merged byte stream",1,stream);bytes.write(value,0,value.length);try{observed(text());return true;}catch(Exception failed){throw new AssertionError(failed);}}
        public void terminalCommandResult(long sequence,int bytes,int error,int rows,int columns,boolean effects){try{JSONObject receipt=queue.observed(sequence,bytes,error,rows,columns,effects);acks++;acknowledged(receipt);}catch(Exception failed){throw new AssertionError(failed);}}
        String text(){return bytes.toString(StandardCharsets.UTF_8);}
        void observed(String text)throws Exception{}
        void acknowledged(JSONObject actual)throws Exception{}
    }
    private NativeProcessSupervisor.Exit run(Path directory,String program,IO io)throws Exception{return new NativeProcessSupervisor().executePty(List.of(PYTHON,"-u","-c",program),Map.of("PATH","/usr/bin:/bin","LANG","C.UTF-8","TERM","xterm-256color"),directory.toFile(),7000,100,24,80,io);}
    @Test public void realPtyReceivesUnicodeResizeAndCanonicalEofWithBothOutputSources()throws Exception{
        Path directory=Files.createTempDirectory("c2-pty-interaction-");try{
            IO io=new IO(){boolean resize,text,eof;void observed(String output)throws Exception{if(output.contains("READY")&&!resize){resize=true;queue.offer("resize_1",new JSONObject().put("action","resize").put("rows",33).put("columns",100));}if(output.contains("SIZE 33 100")&&!eof){eof=true;queue.offer("eof_1",new JSONObject().put("action","eof"));}}void acknowledged(JSONObject actual)throws Exception{assertEquals("delivered",actual.getString("status"));if(actual.getString("action").equals("resize")&&!text){text=true;queue.offer("text_1",new JSONObject().put("action","text").put("text","hello 😀\n"));}}};
            String code="import os,sys,fcntl,termios,struct\nprint('TTY',os.isatty(0),os.isatty(1),os.isatty(2),flush=True)\nprint('STDERR_CANARY',file=sys.stderr,flush=True)\nprint('READY',flush=True)\nline=sys.stdin.readline()\nprint('INPUT '+line.rstrip(),flush=True)\nr,c,_,_=struct.unpack('HHHH',fcntl.ioctl(0,termios.TIOCGWINSZ,b'\\0'*8))\nprint('SIZE',r,c,flush=True)\nrest=sys.stdin.read()\nprint('EOF_BYTES',len(rest),flush=True)\n";
            NativeProcessSupervisor.Exit actual=run(directory,code,io);assertEquals(io.text(),0,actual.exitCode);assertTrue(actual.groupAbsent);assertTrue(actual.sessionAbsent);assertTrue(actual.outputComplete);assertFalse(actual.noChildCreated);assertFalse(actual.cancelled);assertEquals(3,io.acks);assertTrue(io.pid>1&&io.born>0);assertEquals(io.pid,io.session);assertTrue(io.text().contains("TTY True True True"));assertTrue(io.text().contains("STDERR_CANARY"));assertTrue(io.text().contains("INPUT hello 😀"));assertTrue(io.text().contains("SIZE 33 100"));assertTrue(io.text().contains("EOF_BYTES 0"));
        }finally{Files.delete(directory);}
    }
    @Test public void rawModeEofIsExplicitlyRejectedThenCancelDrainsRealSession()throws Exception{
        Path directory=Files.createTempDirectory("c2-pty-raw-");try{
            IO io=new IO(){boolean offered;void observed(String text)throws Exception{if(text.contains("RAW_READY")&&!offered){offered=true;queue.offer("eof_raw",new JSONObject().put("action","eof"));}}void acknowledged(JSONObject actual)throws Exception{assertEquals("not_dispatched",actual.getString("status"));assertTrue(actual.getInt("nativeErrno")>0);assertEquals(0,actual.getInt("writtenBytes"));cancel=true;}};
            var actual=run(directory,"import tty,time\ntty.setraw(0)\nprint('RAW_READY',flush=True)\ntime.sleep(60)\n",io);assertEquals(1,io.acks);assertTrue(actual.cancelled);assertTrue(actual.signal>0);assertTrue(actual.groupAbsent);assertTrue(actual.sessionAbsent);assertFalse(actual.noChildCreated);
        }finally{Files.delete(directory);}
    }
    @Test public void sameSessionDifferentProcessGroupIsActuallyStoppedAndReaped()throws Exception{
        Path directory=Files.createTempDirectory("c2-pty-jobcontrol-");try{
            IO io=new IO(){void observed(String text){if(text.contains("CHILD_READY"))cancel=true;}};
            String code="import subprocess,os,signal,sys\nchild=subprocess.Popen(['/bin/sleep','60'],preexec_fn=lambda:os.setpgid(0,0))\ndef stop(sig,frame):\n child.terminate()\n child.wait()\n sys.exit(0)\nsignal.signal(signal.SIGTERM,stop)\nprint('CHILD_READY',child.pid,os.getpgid(child.pid),os.getsid(child.pid),flush=True)\nchild.wait()\n";
            var actual=run(directory,code,io);assertTrue(io.text(),actual.cancelled);assertTrue(actual.groupAbsent);assertTrue(actual.sessionAbsent);assertTrue(io.text().contains("CHILD_READY"));
        }finally{Files.delete(directory);}
    }
    @Test public void actualPtyEntryCancellationHasEightValueNoChildProofAndExecFailureIs127()throws Exception{
        Path directory=Files.createTempDirectory("c2-pty-launch-");try{
            var supervisor=new NativeProcessSupervisor();assertTrue(supervisor.ptyAvailable());IO cancelled=new IO(){};cancelled.cancel=true;var absent=supervisor.executePty(List.of("/bin/sh","-c","exit 7"),Map.of(),directory.toFile(),3000,100,24,80,cancelled);assertTrue(absent.noChildCreated);assertTrue(absent.groupAbsent);assertTrue(absent.sessionAbsent);assertEquals(-1,absent.exitCode);assertEquals(NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT,absent.resultContract);
            var missing=supervisor.executePty(List.of(directory.resolve("not-present").toString()),Map.of(),directory.toFile(),3000,100,24,80,new IO(){});assertEquals(127,missing.exitCode);assertTrue(missing.launchErrno>0);assertTrue(missing.groupAbsent);assertTrue(missing.sessionAbsent);assertFalse(missing.noChildCreated);
            var oldSeven=NativeProcessSupervisor.Exit.class.getDeclaredConstructor(long[].class);oldSeven.setAccessible(true);assertThrows(java.lang.reflect.InvocationTargetException.class,()->oldSeven.newInstance((Object)new long[]{-1,0,2,1,0,1,1,1}));
        }finally{Files.delete(directory);}
    }
}
