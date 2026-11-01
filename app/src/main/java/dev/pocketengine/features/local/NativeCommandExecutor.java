package dev.pocketengine.features.local;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes a narrow argv grammar with actual system binaries; never invokes an arbitrary shell. */
public final class NativeCommandExecutor implements AutoCloseable {
    private static final Set<String> COMMANDS=Set.of("pwd","ls","cat","wc","head","tail","sha256sum","sort","uniq");
    private final File receipts;
    private final List<File> systemPaths;
    private final Consumer<JSONObject> events;
    private final Map<String,JSONObject> jobs=new LinkedHashMap<>();
    private final Map<String,Process> running=new LinkedHashMap<>();
    private final Set<String> busy=new java.util.HashSet<>();
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private volatile boolean closed;
    public NativeCommandExecutor(File receipts,List<File> systemPaths,Consumer<JSONObject> events)throws Exception {
        this.receipts=receipts;this.systemPaths=new ArrayList<>(systemPaths);this.events=events;
        if(!receipts.isDirectory()&&!receipts.mkdirs())throw new IllegalStateException("无法建立本地执行回执目录");
        File[] saved=receipts.listFiles((dir,name)->name.endsWith(".json"));
        if(saved!=null)for(File file:saved){JSONObject job=JsonFiles.read(file);String id=job.optString("id");if(!id.matches("[A-Za-z0-9_-]{1,80}"))continue;if(Set.of("queued","running","stopping").contains(job.optString("status"))){job.put("status","interrupted").put("message","本机进程已中断，保留原回执；不会自动重放命令");JsonFiles.write(file,job);}jobs.put(id,job);}
    }
    public synchronized JSONArray tools()throws Exception {
        JSONArray result=new JSONArray();
        for(String name:new String[]{"pwd","ls","cat","wc","head","tail","sha256sum","sort","uniq","node","python3","python","java","javac","git"}){File found=executable(name);result.put(new JSONObject().put("name",name).put("available",found!=null).put("executionAllowed",found!=null&&COMMANDS.contains(name)).put("location",found==null?JSONObject.NULL:found.getAbsolutePath()));}
        return result;
    }
    private File executable(String name){for(File path:systemPaths){File executable=new File(path,name);if(executable.isFile()&&executable.canExecute())return executable;}return null;}
    public synchronized JSONObject submit(String checkoutId,File cwd,JSONArray argv,String operationId,int timeout)throws Exception {
        if(closed)throw new IllegalStateException("本地执行器已关闭");
        if(operationId==null||!operationId.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("本地操作编号无效");
        if(timeout<1||timeout>120)throw new IllegalArgumentException("本地命令超时需为1至120秒");
        List<String> launch=validate(cwd,argv);String fingerprint=checkoutId+"\n"+argv.toString()+"\n"+timeout;
        JSONObject previous=jobs.get(operationId);if(previous!=null){if(!fingerprint.equals(previous.optString("fingerprint")))throw new IllegalArgumentException("同一操作编号不能改变命令");return publicJob(previous);}
        if(jobs.size()>=500)throw new IllegalStateException("本地回执已达500条，请先导出归档；未释放旧操作编号");
        if(busy.contains(checkoutId))throw new IllegalStateException("该本地工作目录已有执行任务");
        JSONObject job=new JSONObject().put("id",operationId).put("operationId",operationId).put("checkoutId",checkoutId).put("fingerprint",fingerprint).put("status","queued").put("argv",new JSONArray(argv.toString())).put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis()).put("output","").put("stdout","").put("stderr","").put("verified",false);
        save(job);jobs.put(operationId,job);busy.add(checkoutId);
        workers.execute(()->run(operationId,cwd,launch,timeout));return publicJob(job);
    }
    private List<String> validate(File cwd,JSONArray argv)throws Exception {
        if(argv==null||argv.length()<1||argv.length()>40)throw new IllegalArgumentException("命令需为1至40项argv数组");
        String name=argv.getString(0);if(!COMMANDS.contains(name))throw new SecurityException("该工具不在本地只读命令集合；编程运行时需明确连接Termux或电脑环境");
        File executable=executable(name);if(executable==null)throw new IllegalStateException("当前系统没有安装"+name);
        List<String> args=new ArrayList<>();args.add(executable.getAbsolutePath());boolean number=false;int paths=0;
        for(int i=1;i<argv.length();i++){
            String value=argv.getString(i);if(value.length()>2048||value.indexOf('\0')>=0)throw new IllegalArgumentException("命令参数无效");
            if(number){if(!value.matches("[0-9]{1,5}")||Integer.parseInt(value)>65536)throw new IllegalArgumentException("读取数量无效");number=false;args.add(value);continue;}
            if(value.startsWith("-")){boolean allowed=name.equals("ls")&&Set.of("-l","-a","-la","-al").contains(value)||name.equals("wc")&&Set.of("-l","-w","-c").contains(value)||(name.equals("head")||name.equals("tail"))&&Set.of("-n","-c").contains(value)||name.equals("sort")&&Set.of("-r","-n","-u").contains(value)||name.equals("uniq")&&Set.of("-c","-d","-u").contains(value);if(!allowed)throw new SecurityException("不支持该命令选项");number=value.equals("-n")&&(name.equals("head")||name.equals("tail"))||value.equals("-c")&&(name.equals("head")||name.equals("tail"));args.add(value);continue;}
            File target=within(cwd,value);if(!target.exists())throw new IllegalArgumentException("工作目录内不存在该文件");args.add(target.getAbsolutePath());paths++;
        }
        if(name.equals("uniq")&&paths!=1)throw new SecurityException("uniq只允许一个输入文件，不能指定输出文件");
        if(number||name.equals("pwd")&&argv.length()!=1||!name.equals("pwd")&&!name.equals("ls")&&paths==0)throw new IllegalArgumentException("命令参数不完整");return args;
    }
    public static File within(File root,String relative)throws Exception {
        if(relative==null||relative.isEmpty()||relative.startsWith("/")||relative.contains("\\")||relative.contains(":")||relative.chars().anyMatch(value->value<32))throw new SecurityException("仅允许工作目录内的相对路径");
        File value=root;for(String part:relative.split("/",-1)){if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new SecurityException("路径不可越过工作目录");value=new File(value,part);if(Files.isSymbolicLink(value.toPath()))throw new SecurityException("不跟随工作目录的符号链接");}
        if(!value.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new SecurityException("路径不可越过工作目录");return value;
    }
    public synchronized JSONObject get(String id)throws Exception{JSONObject job=jobs.get(id);if(job==null)throw new IllegalArgumentException("本地执行回执不存在");return publicJob(job);}
    public synchronized JSONArray list()throws Exception{JSONArray out=new JSONArray();for(JSONObject job:jobs.values()){JSONObject item=publicJob(job);String output=item.optString("output");item.remove("output");item.remove("stdout");item.remove("stderr");item.put("outputPreview",output.length()>2000?output.substring(output.length()-2000):output);out.put(item);}return out;}
    public synchronized boolean busy(String id){return busy.contains(id);}
    public synchronized JSONObject cancel(String id)throws Exception{JSONObject job=jobs.get(id);if(job==null)throw new IllegalArgumentException("本地执行回执不存在");if(!Set.of("queued","running","stopping").contains(job.optString("status")))return publicJob(job);job.put("cancelRequested",true).put("status","stopping");save(job);Process process=running.get(id);if(process!=null)process.destroy();return publicJob(job);}
    private void run(String id,File cwd,List<String> argv,int timeout){
        Process process=null;
        try{
            synchronized(this){
                JSONObject job=jobs.get(id);
                if(closed||job.optBoolean("cancelRequested")){job.put("status","cancelled").put("message","命令派发前已取消");save(job);events.accept(publicJob(job));return;}
                ProcessBuilder builder=new ProcessBuilder(argv).directory(cwd);
                builder.environment().clear();builder.environment().put("PATH",String.join(":",systemPaths.stream().map(File::getAbsolutePath).toArray(String[]::new)));builder.environment().put("HOME",cwd.getAbsolutePath());
                process=builder.start();process.getOutputStream().close();running.put(id,process);job.put("status","running");save(job);events.accept(publicJob(job));
            }
            final Process started=process;
            Thread stdout=new Thread(()->drain(id,started.getInputStream(),"stdout"),"pocket-local-stdout");
            Thread stderr=new Thread(()->drain(id,started.getErrorStream(),"stderr"),"pocket-local-stderr");
            stdout.setDaemon(true);stderr.setDaemon(true);stdout.start();stderr.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);boolean timedOut=false;
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)){
                boolean cancelled;synchronized(this){cancelled=closed||jobs.get(id).optBoolean("cancelRequested");}
                if(cancelled||System.nanoTime()>=deadline){timedOut=!cancelled;process.destroyForcibly();break;}
            }
            process.waitFor(2,TimeUnit.SECONDS);stdout.join(1000);stderr.join(1000);
            synchronized(this){
                JSONObject job=jobs.get(id);boolean alive=process.isAlive();
                job.put("status",alive?"unknown":job.optBoolean("cancelRequested")||closed?"cancelled":timedOut?"timed_out":process.exitValue()==0?"completed":"failed")
                    .put("exitCode",alive?JSONObject.NULL:process.exitValue()).put("outputComplete",!stdout.isAlive()&&!stderr.isAlive()&&!job.optBoolean("stdoutReadError")&&!job.optBoolean("stderrReadError")).put("message",alive?"未能确认本机进程停止，请核对原执行回执":"实际进程已返回，请检查输出；这不等于项目目标已完成");
                save(job);events.accept(publicJob(job));
            }
        }catch(Exception error){synchronized(this){try{JSONObject job=jobs.get(id);job.put("status",process==null?"failed":"unknown").put("message",process==null?"当前系统无法启动该本地工具":"本地进程监督中断，请检查原执行回执");save(job);events.accept(publicJob(job));}catch(Exception ignored){}}}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();synchronized(this){running.remove(id);JSONObject job=jobs.get(id);if(job!=null)busy.remove(job.optString("checkoutId"));}}
    }
    private void drain(String id,InputStream stream,String channel){
        try(java.io.Reader reader=new java.io.InputStreamReader(stream,StandardCharsets.UTF_8)){
            char[] chunk=new char[2048];int count;
            while((count=reader.read(chunk))>=0)synchronized(this){
                JSONObject job=jobs.get(id);String old=job.optString(channel);int room=Math.max(0,65536-old.length());
                if(room>0)job.put(channel,old+new String(chunk,0,Math.min(room,count)));
                if(count>room)job.put(channel+"Truncated",true).put("outputTruncated",true);
                // Legacy readers retain an aggregate; the workbench uses the separate real streams.
                String combined=job.optString("output");int combinedRoom=Math.max(0,131072-combined.length());
                if(combinedRoom>0)job.put("output",combined+new String(chunk,0,Math.min(combinedRoom,count)));
                job.put("updatedAt",System.currentTimeMillis());
            }
        }catch(Exception ignored){synchronized(this){try{jobs.get(id).put(channel+"ReadError",true);}catch(Exception unavailable){}}}
    }
    private void save(JSONObject job)throws Exception{job.put("updatedAt",System.currentTimeMillis());JsonFiles.write(new File(receipts,job.getString("id")+".json"),job);}
    private static JSONObject publicJob(JSONObject job)throws Exception{JSONObject value=JsonFiles.copy(job);value.remove("fingerprint");return value;}
    @Override public synchronized void close(){closed=true;for(Process process:running.values())process.destroyForcibly();workers.shutdownNow();}
}
