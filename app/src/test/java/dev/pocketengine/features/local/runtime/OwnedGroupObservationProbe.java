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
   "print(json.dumps({'event':'owned_scope','nonce':sys.argv[1],'owner':fact(os.getppid()),'leader':fact(os.getpid()),'child':fact(child.pid)}),flush=True)\n"+
   "while True: time.sleep(.1)\n";
  if(args.length>3){managed(python,nonce,cwd,program,args[3]);return;}
  NativeProcessSupervisor.Exit exit=new NativeProcessSupervisor().execute(List.of(python,"-I","-c",program,nonce),Map.of("PATH",new File(python).getParent(),"LANG","C.UTF-8"),cwd,15000,150,new NativeProcessSupervisor.Observer(){
   public boolean output(int stream,byte[] bytes){if(stream!=1)return true;try{pending.append(new String(bytes,StandardCharsets.UTF_8));int at;while((at=pending.indexOf("\n"))>=0){JSONObject row=new JSONObject(pending.substring(0,at));pending.delete(0,at+1);if(!nonce.equals(row.getString("nonce")))return false;System.out.println(row);System.out.flush();cancel.set(true);}return true;}catch(Exception invalid){return false;}}
   public boolean cancelled(){return cancel.get();}
  });
  JSONObject receipt=new JSONObject().put("event","native_exit").put("nonce",nonce).put("cancelled",exit.cancelled).put("groupAbsent",exit.groupAbsent).put("outputComplete",exit.outputComplete).put("exitCode",exit.exitCode).put("signal",exit.signal).put("contract",exit.resultContract).put("elapsedMillis",(System.nanoTime()-began)/1000000);System.out.println(receipt);System.out.flush();
 }
 private static void managed(String python,String nonce,File cwd,String program,String mode)throws Exception {
  long began=System.nanoTime();java.nio.file.Path active=java.nio.file.Files.createDirectory(cwd.toPath().resolve("active"));java.nio.file.Files.write(active.resolve("Main.java"),"class Main {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  ManagedProcessJobs jobs=new ManagedProcessJobs(new File(cwd,"runtime"),new NativeProcessSupervisor());
  var closed=new java.util.concurrent.atomic.AtomicInteger();JSONObject source=RuntimeContractsTest.source();boolean preparation="preparation".equals(mode);
  List<String> command=List.of(python,"-I","-c",program,nonce);Map<String,String> environment=Map.of("PATH",new File(python).getParent(),"LANG","C.UTF-8","POCKETWORLD_ENVIRONMENT_IDENTITY","original-environment");
  JSONObject exact=RuntimeContractsTest.exact(source,preparation?"termux.prepare":"termux.run",preparation?null:new JSONArray(command.subList(1,command.size())));
  if(!preparation){exact.getJSONObject("payload").put("executable","python3");exact.put("requestDigest",dev.pocketengine.features.local.project.ProjectSources.digest(exact.getJSONObject("payload")));}
  RuntimeInvocation invocation=new RuntimeInvocation(exact,RuntimeContractsTest.binding("owned-group-"+mode),source,RuntimeContractsTest.original(),()->{},new RuntimeWorkspace(active.toFile(),"project",RuntimeContractsTest.CHECKOUT));
  AutoCloseable lease=()->closed.incrementAndGet();
  if(preparation)jobs.startPreparation(invocation,"original-environment",context->{context.execute(command,environment,active.toFile(),15000);return new JSONObject();},lease);
  else jobs.start(invocation,command,environment,active.toFile(),15000,lease);
  JSONObject actual=null;boolean scopeSent=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
  while(System.nanoTime()<deadline){
   actual=jobs.query(invocation.executionRef()).json();
   if(!scopeSent){String stdout=actual.optString("stdout");int at=stdout.indexOf('\n');if(at>=0){JSONObject scope=new JSONObject(stdout.substring(0,at));if(!nonce.equals(scope.getString("nonce")))throw new AssertionError("wrong original scope");System.out.println(scope);System.out.flush();scopeSent=true;jobs.requestCancel(invocation.executionRef());}}
   if(actual.has("finishedAt")&&!actual.optBoolean("ownedWorkerLive"))break;Thread.sleep(5);
  }
  if(!scopeSent||actual==null||!actual.has("finishedAt"))throw new AssertionError("original managed job did not settle");
  boolean closeRejected=false,idleRejected=false;try{jobs.close();}catch(IllegalStateException held){if(!"runtime_process_drain_unconfirmed".equals(held.getMessage()))throw held;closeRejected=true;}
  try{jobs.requireEnvironmentIdle(null);}catch(IllegalStateException held){if(!"runtime_environment_has_live_or_unresolved_job".equals(held.getMessage()))throw held;idleRejected=true;}
  JSONObject cold,cancel;try(ManagedProcessJobs observer=new ManagedProcessJobs(new File(cwd,"runtime"),new NativeProcessSupervisor())){cold=observer.query(invocation.executionRef()).json();cancel=observer.requestCancel(invocation.executionRef()).json();}
  JSONObject receipt=new JSONObject().put("event","native_exit").put("nonce",nonce).put("mode",mode).put("actual",actual).put("leaseCloseCount",closed.get()).put("closeRejected",closeRejected).put("maintenanceRejected",idleRejected).put("cold",cold).put("coldCancel",cancel).put("elapsedMillis",(System.nanoTime()-began)/1000000);
  System.out.println(receipt);System.out.flush();
 }
}
