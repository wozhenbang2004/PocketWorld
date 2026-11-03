package dev.pocketengine.features.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product-owned, synthetic-only tasks. Answers stay in the independent native probe. */
public final class EvaluationDataset {
    public static final String ID="synthetic-order-totals", VERSION="1";
    public static final List<String> TUNE=List.of("tune-a","tune-b"), HOLDOUT=List.of("holdout-a","holdout-b");
    public static final List<String> STRATEGIES=List.of("baseline","verify-first");
    public static final int MODEL_LIMIT=6, TOOL_LIMIT=6;
    private EvaluationDataset(){}

    public static String pool(String id){
        if(TUNE.contains(id))return "tune";
        if(HOLDOUT.contains(id))return "holdout";
        throw new IllegalArgumentException("未知合成场景");
    }
    public static String source(String id)throws Exception{
        pool(id);
        // Deliberate distractors exercise a real read/filter/write path, not a completion claim.
        String rows=switch(id){
            case "tune-a" -> "[{\"id\":\"a1\",\"status\":\"paid\",\"cents\":125},{\"id\":\"a2\",\"status\":\"void\",\"cents\":900},{\"id\":\"a3\",\"status\":\"paid\",\"cents\":275}]";
            case "tune-b" -> "[{\"id\":\"b1\",\"status\":\"pending\",\"cents\":800},{\"id\":\"b2\",\"status\":\"paid\",\"cents\":0},{\"id\":\"b3\",\"status\":\"paid\",\"cents\":431},{\"id\":\"b4\",\"status\":\"paid\",\"cents\":69}]";
            case "holdout-a" -> "[{\"id\":\"h1\",\"status\":\"void\",\"cents\":333},{\"id\":\"h2\",\"status\":\"paid\",\"cents\":199},{\"id\":\"h3\",\"status\":\"pending\",\"cents\":722},{\"id\":\"h4\",\"status\":\"paid\",\"cents\":801}]";
            default -> "[{\"id\":\"j1\",\"status\":\"pending\",\"cents\":55},{\"id\":\"j2\",\"status\":\"void\",\"cents\":100}]";
        };
        return "{\"synthetic\":true,\"sourceId\":\""+id+"\",\"sourceRevision\":1,\"currency\":\"TEST\",\"orders\":"+rows+"}";
    }
    public static String sourceSha(String id)throws Exception{return sha(source(id));}
    public static String digest()throws Exception{
        StringBuilder sources=new StringBuilder(ID).append('\n').append(VERSION);
        for(String id:List.of("tune-a","tune-b","holdout-a","holdout-b"))sources.append('\n').append(id).append(':').append(pool(id)).append(':').append(sourceSha(id));
        return sha(sources.toString());
    }
    public static int steps(String strategy){if(!STRATEGIES.contains(strategy))throw new IllegalArgumentException("未知有界策略");return "baseline".equals(strategy)?4:6;}
    public static String goal(String id,String strategy){
        pool(id);steps(strategy);
        return "这是明确批准的合成评测，不能访问任何真实应用、文件、聊天或私人记忆。先用 evaluation_read_source 读取本次原始合成订单。"
                +"只统计 status 精确为 paid 的订单数量 count 和总金额 totalCents（整数分），包括金额为0的paid订单。用 evaluation_write_result 保存，携带该工具返回的 sourceId/sourceRevision/sourceSha256。"
                +("verify-first".equals(strategy)?"保存前逐条核对状态过滤、零金额与计数，再独立复算总和。":"")
                +"用 evaluation_report 声明你是否完成（completed），这只是你的声明，真实成绩由独立宿主读回决定。最后简短说明实际结果；遇到障碍如实声明未完成。";
    }
    static JSONObject expected(String id)throws Exception{
        JSONArray rows=new JSONObject(source(id)).getJSONArray("orders");long total=0;int count=0;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if("paid".equals(row.getString("status"))){count++;total=Math.addExact(total,row.getLong("cents"));}}
        return new JSONObject().put("count",count).put("totalCents",total);
    }
    public static JSONObject trial(String study,String id,String strategy)throws Exception{
        uuid(study);steps(strategy);
        return new JSONObject().put("format","pocketworld.evaluation-trial.v1").put("studyId",study)
                .put("datasetId",ID).put("datasetVersion",VERSION).put("datasetSha256",digest()).put("caseId",id)
                .put("pool",pool(id)).put("strategy",strategy).put("goalSha256",sha(goal(id,strategy))).put("sourceId",id).put("sourceRevision",1)
                .put("sourceSha256",sourceSha(id)).put("modelCallLimit",MODEL_LIMIT).put("toolLimit",TOOL_LIMIT).put("stepLimit",steps(strategy));
    }
    public static void validate(JSONObject trial)throws Exception{
        JSONObject expected=trial(trial.getString("studyId"),trial.getString("caseId"),trial.getString("strategy"));
        for(var keys=expected.keys();keys.hasNext();){String key=keys.next();if(!expected.get(key).equals(trial.opt(key)))throw new SecurityException("评测数据集或原场景版本已变化");}
    }
    public static String taskId(JSONObject trial)throws Exception{
        validate(trial);return java.util.UUID.nameUUIDFromBytes(("evaluation:"+trial.getString("studyId")+":"+trial.getString("caseId")+":"+trial.getString("strategy")).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static void uuid(String value){try{if(value==null||!java.util.UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}catch(Exception invalid){throw new IllegalArgumentException("原评测身份无效");}}
    public static String sha(String value)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static void keys(JSONObject args,String... expected){Set<String> allowed=new java.util.HashSet<>(List.of(expected));allowed.add("_hostOperationId");for(var i=args.keys();i.hasNext();)if(!allowed.contains(i.next()))throw new IllegalArgumentException("合成工具不接受额外字段、路径或执行身份");}
}
