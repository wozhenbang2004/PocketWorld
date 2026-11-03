package dev.pocketengine.features.local.runtime;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
/** Test process only. The outer parent independently owns/reaps this exact subtree. */
public final class OwnedGroupObservationProbe {
 public static void main(String[] args)throws Exception {
  String python=new File(args[0]).getCanonicalPath(),nonce=args[1];File cwd=new File(args[2]);AtomicBoolean cancel=new AtomicBoolean();StringBuilder pending=new StringBuilder();long began=System.nanoTime();
  String program="import json,os,pathlib,subprocess,sys,time\n"+
   "def fact(p):\n s=pathlib.Path('/proc/'+str(p)+'/stat').read_text().rsplit(') ',1)[1].split(); return {'pid':p,'parent':int(s[1]),'group':int(s[2]),'session':int(s[3]),'born':int(s[19])}\n"+
   "child=subprocess.Popen([sys.executable,'-I','-c','import time; time.sleep(30)'],stdin=subprocess.DEVNULL,close_fds=True)\n"+
   "print(json.dumps({'event':'owned_scope','nonce':sys.argv[1],'leader':fact(os.getpid()),'child':fact(child.pid)}),flush=True)\n"+
   "while True: time.sleep(.1)\n";
  NativeProcessSupervisor.Exit exit=new NativeProcessSupervisor().execute(List.of(python,"-I","-c",program,nonce),Map.of("PATH",new File(python).getParent(),"LANG","C.UTF-8"),cwd,15000,150,new NativeProcessSupervisor.Observer(){
   public boolean output(int stream,byte[] bytes){if(stream!=1)return true;try{pending.append(new String(bytes,StandardCharsets.UTF_8));int at;while((at=pending.indexOf("\n"))>=0){JSONObject row=new JSONObject(pending.substring(0,at));pending.delete(0,at+1);if(!nonce.equals(row.getString("nonce")))return false;System.out.println(row);System.out.flush();cancel.set(true);}return true;}catch(Exception invalid){return false;}}
   public boolean cancelled(){return cancel.get();}
  });
  JSONObject receipt=new JSONObject().put("event","native_exit").put("nonce",nonce).put("cancelled",exit.cancelled).put("groupAbsent",exit.groupAbsent).put("outputComplete",exit.outputComplete).put("exitCode",exit.exitCode).put("signal",exit.signal).put("contract",exit.resultContract).put("elapsedMillis",(System.nanoTime()-began)/1000000);System.out.println(receipt);System.out.flush();
 }
}
