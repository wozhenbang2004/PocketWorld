package dev.pocketengine.features.evaluation;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.json.JSONArray;
import org.json.JSONObject;

/** Closed native target: the original Runner can only read and write this synthetic task. */
public final class SyntheticEvaluationPort implements CapabilityPort {
    public static final String READ="evaluation_read_source", WRITE="evaluation_write_result", REPORT="evaluation_report";
    public interface Current { void check()throws Exception; }
    private final TaskStore tasks;
    private final Path directory;
    private final JSONObject trial;
    private final String task,run;
    private final int epoch;
    private final RunControl original;
    private final Current current;
    private boolean closed;

    public SyntheticEvaluationPort(TaskStore tasks,Path root,JSONObject task,RunControl original,Current current)throws Exception{
        this.tasks=java.util.Objects.requireNonNull(tasks);this.task=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");
        trial=new JSONObject(task.getJSONObject("evaluation").getJSONObject("trial").toString());EvaluationDataset.validate(trial);
        this.original=java.util.Objects.requireNonNull(original);this.current=java.util.Objects.requireNonNull(current);directory=directory(root,this.task);
        check(original);tasks.authorizeTools(this.task,run,new JSONArray().put(READ).put(WRITE).put(REPORT));
    }
    static Path directory(Path root,String task)throws Exception{
        EvaluationDataset.uuid(task);Path base=root.toAbsolutePath().normalize();if(Files.isSymbolicLink(base))throw new SecurityException("合成目标目录不可为链接");
        Path child=base.resolve(task);if(Files.isSymbolicLink(child))throw new SecurityException("合成目标不可为链接");return child;
    }
    public static void initialize(Path root,String task,JSONObject trial)throws Exception{
        EvaluationDataset.validate(trial);Path directory=directory(root,task);Files.createDirectories(directory);
        for(String name:new String[]{"result.json","claim.json","result.json.pending","claim.json.pending"})if(Files.exists(directory.resolve(name),LinkOption.NOFOLLOW_LINKS))throw new SecurityException("原合成目标已有未核对结果，不能重置或重放");
        Path source=directory.resolve("source.json");String expected=EvaluationDataset.source(trial.getString("caseId"));
        if(Files.exists(source,LinkOption.NOFOLLOW_LINKS)){if(!expected.equals(read(source)))throw new SecurityException("合成原始来源已变化；不会覆写旧试验");}
        else Files.write(source,expected.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
    private synchronized void check(RunControl control)throws Exception{
        if(closed||control!=original||!control.matchesHostRun(task,run,epoch))throw new SecurityException("旧评测回调或控制器不可继续");
        control.check();current.check();JSONObject stored=tasks.get(task,false);
        if(stored==null||!run.equals(stored.optString("runId"))||epoch!=stored.optInt("coordinatorEpoch")||stored.optBoolean("cancelRequested")
                ||!EvaluationDataset.goal(trial.getString("caseId"),trial.getString("strategy")).equals(stored.optString("goal")))throw new SecurityException("原评测任务已变化");
        EvaluationDataset.validate(stored.getJSONObject("evaluation").getJSONObject("trial"));
        if(!trial.getString("sourceSha256").equals(EvaluationDataset.sha(read(directory.resolve("source.json")))))throw new SecurityException("合成来源修订已变化，旧决定未继续");
    }
    @Override public boolean responseCurrent(RunControl live)throws Exception{check(live);return true;}
    @Override public void beforeModelRequest(String request,JSONArray messages,RunControl live)throws Exception{check(live);}
    @Override public JSONArray tools()throws Exception{
        check(original);JSONObject source=new JSONObject().put("sourceId",text()).put("sourceRevision",new JSONObject().put("type","integer")).put("sourceSha256",text());
        JSONObject values=new JSONObject(source.toString()).put("count",new JSONObject().put("type","integer").put("minimum",0).put("maximum",100)).put("totalCents",new JSONObject().put("type","integer").put("minimum",0).put("maximum",1000000));
        return new JSONArray().put(tool(READ,"读取当前任务的合成订单及精确来源身份。",new JSONObject()))
                .put(tool(WRITE,"只写本次合成结果一次；不能访问真实App或任意路径。",values,"sourceId","sourceRevision","sourceSha256","count","totalCents"))
                .put(tool(REPORT,"如实声明是否已完成；此声明本身不代表评测通过。",new JSONObject().put("completed",new JSONObject().put("type","boolean")),"completed"));
    }
    @Override public boolean supports(String name){return READ.equals(name)||WRITE.equals(name)||REPORT.equals(name);}
    @Override public boolean sideEffecting(String name){return WRITE.equals(name)||REPORT.equals(name);}
    @Override public void validate(String name,JSONObject args)throws Exception{
        if(READ.equals(name)){EvaluationDataset.keys(args);return;}
        if(WRITE.equals(name)){EvaluationDataset.keys(args,"sourceId","sourceRevision","sourceSha256","count","totalCents");
            if(!(args.opt("sourceId") instanceof String)||!(args.opt("sourceSha256") instanceof String))throw new IllegalArgumentException("需要原来源字段");
            integer(args,"sourceRevision",1,Long.MAX_VALUE);integer(args,"count",0,100);integer(args,"totalCents",0,1_000_000);return;}
        if(REPORT.equals(name)){EvaluationDataset.keys(args,"completed");if(!(args.opt("completed") instanceof Boolean))throw new IllegalArgumentException("completed需明确布尔值");return;}
        throw new SecurityException("评测不提供真实应用工具");
    }
    @Override public synchronized JSONObject execute(String name,JSONObject args,RunControl live)throws Exception{
        check(live);validate(name,args);tasks.checkCapability(task,run,epoch,name,live);
        if(READ.equals(name)){
            String body=read(directory.resolve("source.json"));check(live);tasks.recordEvaluationSourceRead(task,run,epoch,live,trial.getString("sourceSha256"));
            return new JSONObject().put("ok",true).put("sourceId",trial.getString("sourceId")).put("sourceRevision",1).put("sourceSha256",trial.getString("sourceSha256")).put("data",new JSONObject(body));
        }
        String operation=args.optString("_hostOperationId");EvaluationDataset.uuid(operation);requirePrepared(name,operation,args);
        if(WRITE.equals(name)&&(!trial.getString("sourceId").equals(args.getString("sourceId"))||args.getLong("sourceRevision")!=1||!trial.getString("sourceSha256").equals(args.getString("sourceSha256"))))
            return new JSONObject().put("ok",false).put("accepted",false).put("code","evaluation_source_mismatch");
        JSONObject value=new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("operationId",operation);
        if(WRITE.equals(name))value.put("sourceId",args.getString("sourceId")).put("sourceRevision",args.getLong("sourceRevision")).put("sourceSha256",args.getString("sourceSha256")).put("count",args.getLong("count")).put("totalCents",args.getLong("totalCents"));
        else value.put("completed",args.getBoolean("completed"));
        Path destination=directory.resolve(WRITE.equals(name)?"result.json":"claim.json");
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))return new JSONObject().put("ok",false).put("accepted",false).put("code","evaluation_already_written").put("message","原合成记录已有结果；不会重复覆写，独立探针会读取原记录。");
        long pause=live.pauseRevision();check(live);String encoded=value.toString();boolean[] entered={false};
        try{live.dispatchAtPauseRevision(pause,()->{entered[0]=true;writeOnce(destination,encoded);});}
        catch(Exception uncertain){
            if(!entered[0])return new JSONObject().put("ok",false).put("accepted",false).put("code","evaluation_dispatch_revoked").put("message","原执行在写入前已变化，没有派发此效果。");
            return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("status","unknown").put("code","evaluation_write_unknown").put("message","写入结果待核对；不会自动重放。");
        }
        // A cancellation after the admitted write cannot erase its real receipt.
        return new JSONObject().put("ok",true).put("accepted",true).put("code","evaluation_synthetic_written").put("operationId",operation).put("artifactSha256",EvaluationDataset.sha(encoded)).put("synthetic",true);
    }
    private void requirePrepared(String name,String operation,JSONObject args)throws Exception{
        JSONArray rows=tasks.get(task,false).getJSONArray("operations");boolean found=false;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String state=row.getString("state");
            if(operation.equals(row.getString("operationId"))){
                if(!run.equals(row.getString("runId"))||!name.equals(row.getString("tool"))||!"prepared".equals(state))throw new SecurityException("合成写入没有准确原prepared操作");
                JSONObject stored=row.getJSONObject("arguments");if(stored.length()!=args.length()-1)throw new SecurityException("原合成参数已变化");
                for(var keys=stored.keys();keys.hasNext();){String key=keys.next();if(!String.valueOf(stored.get(key)).equals(String.valueOf(args.opt(key))))throw new SecurityException("原合成参数已变化");}found=true;
            }else if(!"receipt".equals(state))throw new SecurityException("其他原操作效果未知，不能重复写入");
        }
        if(!found)throw new SecurityException("没有原任务prepared操作，未执行合成写入");
    }
    @Override public synchronized void close(){closed=true;}
    private static long integer(JSONObject row,String key,long min,long max)throws Exception{Object value=row.opt(key);if(!(value instanceof Integer||value instanceof Long))throw new IllegalArgumentException("合成数值必须为整数");long n=((Number)value).longValue();if(n<min||n>max)throw new IllegalArgumentException("合成数值超限");return n;}
    static String read(Path file)throws Exception{
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>8192)throw new SecurityException("合成目标缺失、变化或超出读取范围");
        byte[] bytes=Files.readAllBytes(file);if(bytes.length>8192)throw new SecurityException("合成目标超过上限");return new String(bytes,StandardCharsets.UTF_8);
    }
    private static void writeOnce(Path destination,String body)throws Exception{
        Path pending=destination.resolveSibling(destination.getFileName()+".pending");Files.write(pending,body.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        Files.move(pending,destination,StandardCopyOption.ATOMIC_MOVE);
    }
    private static JSONObject text()throws Exception{return new JSONObject().put("type","string").put("maxLength",128);}
    private static JSONObject tool(String name,String description,JSONObject properties,String... required)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false)));}
}
