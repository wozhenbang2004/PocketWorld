package dev.pocketengine.features.local;

import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.local.runtime.NativeProcessSupervisor;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
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
    private final NativeProcessSupervisor supervisor=new NativeProcessSupervisor();
    private final Set<String> busy=new java.util.HashSet<>();
    private int unreadableOriginalRecords,coldUnconfirmedRecords;
    private boolean originalInventoryUnconfirmed;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private volatile boolean closed;
    public NativeCommandExecutor(File receipts,List<File> systemPaths,Consumer<JSONObject> events)throws Exception {
        this.receipts=receipts;this.systemPaths=new ArrayList<>(systemPaths);this.events=events;
        if(Files.isSymbolicLink(receipts.toPath())){originalInventoryUnconfirmed=true;return;}
        if(!receipts.isDirectory()&&!receipts.mkdirs())throw new IllegalStateException("无法建立本地执行回执目录");
        File[] saved=receipts.listFiles((dir,name)->name.endsWith(".json"));
        if(saved==null)originalInventoryUnconfirmed=true;
        else for(File file:saved)try{
            JSONObject job=readOriginal(file);Object recordedId=job.opt("id");String id=recordedId instanceof String?(String)recordedId:"",status=job.optString("status");
            if(!id.matches("[A-Za-z0-9_-]{1,80}")||!file.getName().equals(id+".json")
                    ||job.has("operationId")&&!id.equals(job.opt("operationId"))
                    ||!Set.of("queued","running","stopping","unknown","interrupted","completed","failed","cancelled","timed_out").contains(status))throw new java.io.IOException("legacy_original_receipt_identity_unconfirmed");
            if(Set.of("queued","running","stopping","unknown","interrupted").contains(status)){
                // A cold observer lost the original Process object. Neither a
                // new constructor nor an old 'interrupted' label proves exit.
                // Keep the bytes; this is only an in-memory unknown projection.
                job.put("originalPersistedStatus",status).put("status","unknown").put("coldProcessUnconfirmed",true)
                        .put("processExitObserved",false).put("resourcesReleased",false).put("replayAllowed",false)
                        .put("message","原只读工具的进程状态尚未确认；原回执保留，没有重启或恢复执行权限");
                coldUnconfirmedRecords++;Object checkout=job.opt("checkoutId");
                if(checkout instanceof String&&((String)checkout).matches("[A-Za-z0-9_-]{1,80}"))busy.add((String)checkout);
                else originalInventoryUnconfirmed=true;
            }
            jobs.put(id,job);
        }catch(Exception unconfirmed){unreadableOriginalRecords++;originalInventoryUnconfirmed=true;/* Never rewrite, remove, rename or invent a Job from damaged bytes. */}
    }
    private static JSONObject readOriginal(File file)throws Exception {
        var path=file.toPath();BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.fileKey()==null||before.size()>8L*1024*1024)throw new java.io.IOException("legacy_original_receipt_not_regular_or_over_budget");byte[] bytes;
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] chunk=new byte[8192];for(int n;(n=input.read(chunk))!=-1;){if(out.size()+n>8L*1024*1024)throw new java.io.IOException("legacy_original_receipt_over_budget");out.write(chunk,0,n);}bytes=out.toByteArray();
        }
        BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!after.isRegularFile()||!before.fileKey().equals(after.fileKey())||before.size()!=bytes.length||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new java.io.IOException("legacy_original_receipt_changed_during_observation");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();return new JSONObject(text);
    }
    /** Fixed facts only. This is not another execution ledger, a process-exit
     * proof, a repair grant or permission to recycle old operation identities. */
    public synchronized JSONObject receiptRecovery()throws Exception {
        return new JSONObject().put("scope","legacy_readonly_command_receipts").put("readableOriginalRecords",jobs.size())
                .put("unreadableOriginalRecordsRetained",unreadableOriginalRecords).put("coldUnconfirmedRecords",coldUnconfirmedRecords)
                .put("originalInventoryUnconfirmed",originalInventoryUnconfirmed).put("newLegacyDispatchBlocked",originalInventoryUnconfirmed)
                .put("blockingScope",originalInventoryUnconfirmed?"all_checkouts":coldUnconfirmedRecords>0?"recorded_legacy_checkouts":"none")
                .put("originalFilesChangedByRecovery",false).put("processExitInferredFromColdStart",false).put("executionAuthorityRestored",false)
                .put("message",originalInventoryUnconfirmed?"部分原只读命令回执不可完整核对；仍可查看可读记录和项目，原范围新执行或写入保持阻止，未删除坏记录":coldUnconfirmedRecords>0?"原只读命令进程状态未确认；相关副本保持占用，未把重新打开应用当作退出证据":"原只读命令回执已读取；不代表项目任务或设备验收完成");
    }
    public synchronized JSONArray tools()throws Exception {
        JSONArray result=new JSONArray();
        boolean supervised=supervisor.available();
        for(String name:new String[]{"pwd","ls","cat","wc","head","tail","sha256sum","sort","uniq","node","python3","python","java","javac","git"}){File found=executable(name);result.put(new JSONObject().put("name",name).put("available",found!=null).put("executionAllowed",found!=null&&COMMANDS.contains(name)&&supervised&&!originalInventoryUnconfirmed).put("nativeSupervisorAvailable",supervised).put("location",found==null?JSONObject.NULL:found.getAbsolutePath()));}
        return result;
    }
    private File executable(String name){for(File path:systemPaths){File executable=new File(path,name);if(executable.isFile()&&executable.canExecute())return executable;}return null;}
    public synchronized JSONObject submit(String checkoutId,File cwd,JSONArray argv,String operationId,int timeout)throws Exception {
        if(closed)throw new IllegalStateException("本地执行器已关闭");
        if(operationId==null||!operationId.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("本地操作编号无效");
        if(timeout<1||timeout>120)throw new IllegalArgumentException("本地命令超时需为1至120秒");
        List<String> launch=validate(cwd,argv);String fingerprint=checkoutId+"\n"+argv.toString()+"\n"+timeout;
        JSONObject previous=jobs.get(operationId);if(previous!=null){if(!fingerprint.equals(previous.optString("fingerprint")))throw new IllegalArgumentException("同一操作编号不能改变命令");return publicJob(previous);}
        if(originalInventoryUnconfirmed)throw new IllegalStateException("原只读命令回执归属尚未核对，未派发新命令或回收原操作编号");
        if(!supervisor.available())throw new IllegalStateException("本机进程组监督器不可用，未派发只读命令，也没有使用较弱的进程回退");
        if(jobs.size()>=500)throw new IllegalStateException("本地回执已达500条，请先导出归档；未释放旧操作编号");
        if(busy.contains(checkoutId))throw new IllegalStateException("该本地工作目录已有执行任务");
        JSONObject job=new JSONObject().put("id",operationId).put("operationId",operationId).put("checkoutId",checkoutId).put("fingerprint",fingerprint).put("status","queued").put("argv",new JSONArray(argv.toString())).put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis()).put("output","").put("stdout","").put("stderr","").put("verified",false);
        save(job);jobs.put(operationId,job);busy.add(checkoutId);
        workers.execute(()->run(operationId,cwd,launch,timeout));return publicJob(job);
    }
    private List<String> validate(File cwd,JSONArray argv)throws Exception {
        if(argv==null||argv.length()<1||argv.length()>40)throw new IllegalArgumentException("命令需为1至40项argv数组");
        String name=argv.getString(0);if(!COMMANDS.contains(name))throw new SecurityException("该工具不在本机只读命令集合；编程命令请使用原已审批的本机开发环境或明确选定的远端");
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
    public synchronized boolean busy(String id){return originalInventoryUnconfirmed||busy.contains(id);}
    public synchronized JSONObject cancel(String id)throws Exception{JSONObject job=jobs.get(id);if(job==null)throw new IllegalArgumentException("本地执行回执不存在");if(!Set.of("queued","running","stopping").contains(job.optString("status")))return publicJob(job);job.put("cancelRequested",true).put("status","stopping");save(job);return publicJob(job);}
    private void run(String id,File cwd,List<String> argv,int timeout){
        boolean nativeEntered=false,nativeResultReceived=false,resourcesProved=false;
        OutputDecoder stdout=new OutputDecoder(text->appendOutput(id,"stdout",text)),stderr=new OutputDecoder(text->appendOutput(id,"stderr",text));
        try{
            synchronized(this){
                JSONObject job=jobs.get(id);
                if(closed||job.optBoolean("cancelRequested")){resourcesProved=true;job.put("status","cancelled").put("nativeDispatchAttempted",false).put("nativeResultReceived",false).put("effectStatus","not_dispatched").put("resourcesReleased",true).put("outputComplete",true).put("message","原命令在监督器派发前已取消");save(job);events.accept(publicJob(job));return;}
            }
            Map<String,String> environment=Map.of("PATH",String.join(":",systemPaths.stream().map(File::getAbsolutePath).toArray(String[]::new)),"HOME",cwd.getAbsolutePath());
            nativeEntered=true;
            NativeProcessSupervisor.Exit exit=supervisor.execute(argv,environment,cwd,TimeUnit.SECONDS.toMillis(timeout),500,new NativeProcessSupervisor.Observer(){
                @Override public boolean started(){synchronized(NativeCommandExecutor.this){try{JSONObject job=jobs.get(id);job.put("status",job.optBoolean("cancelRequested")||closed?"stopping":"running").put("nativeStartObserved",true);save(job);events.accept(publicJob(job));return !closed&&!job.optBoolean("cancelRequested");}catch(Exception failed){try{jobs.get(id).put("nativeReceiptObservationFailed",true);}catch(Exception ignored){}return false;}}}
                @Override public boolean output(int stream,byte[] bytes){synchronized(NativeCommandExecutor.this){try{
                    String channel=stream==1?"stdout":stream==2?"stderr":null;if(channel==null)throw new IllegalStateException("legacy_native_stream_invalid");JSONObject job=jobs.get(id);
                    if(job.optString(channel).length()>=65536&&job.optString("output").length()>=131072){if(bytes.length>0)job.put(channel+"Truncated",true).put("outputTruncated",true);job.put("updatedAt",System.currentTimeMillis());return true;}
                    if(stream==1)stdout.append(bytes);else stderr.append(bytes);return true;
                }catch(Exception failed){try{jobs.get(id).put("nativeReceiptObservationFailed",true);}catch(Exception ignored){}return false;}}}
                @Override public boolean cancelled(){synchronized(NativeCommandExecutor.this){return closed||Thread.currentThread().isInterrupted()||jobs.get(id).optBoolean("cancelRequested");}}
            });
            nativeResultReceived=true;resourcesProved=NativeProcessSupervisor.knownOrdinaryExitContract(exit.resultContract)&&exit.groupAbsent&&exit.outputComplete;
            synchronized(this){
                stdout.finish();stderr.finish();JSONObject job=jobs.get(id);
                boolean uncertain=!resourcesProved||job.optBoolean("nativeReceiptObservationFailed");
                job.put("status",uncertain?"unknown":exit.cancelled?"cancelled":exit.timedOut?"timed_out":exit.launchErrno!=0||exit.exitCode!=0?"failed":"completed")
                    .put("exitCode",exit.exitCode<0?JSONObject.NULL:exit.exitCode).put("signal",exit.signal).put("launchErrno",exit.launchErrno)
                    .put("nativeDispatchAttempted",true).put("nativeResultReceived",true).put("nativeResultContract",exit.resultContract).put("processGroupAbsent",exit.groupAbsent).put("outputComplete",exit.outputComplete)
                    .put("resourcesReleased",resourcesProved).put("nativeCancelled",exit.cancelled).put("nativeTimedOut",exit.timedOut).put("noChildCreated",exit.noChildCreated)
                    .put("message",uncertain?"原进程组、输出或回执尚未完整核对，未放行未确认资源或重发原命令":"原监督器已返回进程组与输出事实，请核对输出；这不等于项目目标已完成");
                save(job);events.accept(publicJob(job));
            }
        }catch(Exception|LinkageError error){synchronized(this){try{JSONObject job=jobs.get(id);job.put("status",nativeEntered?"unknown":"failed").put("nativeDispatchAttempted",nativeEntered).put("nativeResultReceived",nativeResultReceived).put("resourcesReleased",resourcesProved).put("message",nativeEntered?"原本机监督结果未完整确认，请核对原回执；没有弱回退或重新派发":"当前系统无法启动该本地工具，未进入监督器");save(job);events.accept(publicJob(job));}catch(Exception ignored){}}}
        finally{synchronized(this){JSONObject job=jobs.get(id);if(job!=null&&resourcesProved)busy.remove(job.optString("checkoutId"));}}
    }
    private synchronized void appendOutput(String id,String channel,String text)throws Exception {
        JSONObject job=jobs.get(id);String old=job.optString(channel);int room=Math.max(0,65536-old.length());
        if(room>0)job.put(channel,old+text.substring(0,Math.min(room,text.length())));
        if(text.length()>room)job.put(channel+"Truncated",true).put("outputTruncated",true);
        String combined=job.optString("output");int combinedRoom=Math.max(0,131072-combined.length());if(combinedRoom>0)job.put("output",combined+text.substring(0,Math.min(combinedRoom,text.length())));job.put("updatedAt",System.currentTimeMillis());
    }
    @FunctionalInterface interface DecodedText {void accept(String text)throws Exception;}
    /** Same replacement semantics/UTF16 limits as the old Reader, but preserves
     * incomplete UTF8 across actual native byte chunks without extra readers. */
    static final class OutputDecoder {
        private final java.nio.charset.CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        private final DecodedText sink;private final java.nio.CharBuffer chars=java.nio.CharBuffer.allocate(8192);private byte[] carry=new byte[0];private boolean ended;
        OutputDecoder(DecodedText sink){this.sink=sink;}
        void append(byte[] bytes)throws Exception{decode(bytes,false);}
        void finish()throws Exception{decode(new byte[0],true);}
        private void decode(byte[] bytes,boolean end)throws Exception {
            if(ended)throw new IllegalStateException("legacy_output_already_finished");java.nio.ByteBuffer input;if(carry.length==0)input=java.nio.ByteBuffer.wrap(bytes);else{input=java.nio.ByteBuffer.allocate(carry.length+bytes.length);input.put(carry).put(bytes).flip();}
            for(;;){var result=decoder.decode(input,chars,end);emit(chars);if(result.isError())result.throwException();if(result.isUnderflow())break;}
            carry=new byte[input.remaining()];input.get(carry);
            if(end){for(;;){var result=decoder.flush(chars);emit(chars);if(result.isError())result.throwException();if(result.isUnderflow())break;}ended=true;}
        }
        private void emit(java.nio.CharBuffer chars)throws Exception{chars.flip();if(chars.hasRemaining())sink.accept(chars.toString());chars.clear();}
    }
    private void save(JSONObject job)throws Exception{job.put("updatedAt",System.currentTimeMillis());JsonFiles.write(new File(receipts,job.getString("id")+".json"),job);}
    private static JSONObject publicJob(JSONObject job)throws Exception{JSONObject value=JsonFiles.copy(job);value.remove("fingerprint");return value;}
    @Override public synchronized void close(){closed=true;workers.shutdownNow();}
}
