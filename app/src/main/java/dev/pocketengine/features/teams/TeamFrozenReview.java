package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent original Runner's read-only surface. Reads immutable real bytes, not author summaries. */
public final class TeamFrozenReview implements CapabilityPort {
    public interface Current {void check(JSONObject binding,String tool,RunControl control)throws Exception;}
    public static final class Proof {
        private final String json;private Proof(JSONObject value){json=value.toString();}
        public JSONObject snapshot()throws Exception{return new JSONObject(json);}
    }
    private final TeamWorkFiles.Workspace workspace;private final JSONObject binding,plan;private final RunControl original;private final Current current;
    private final Map<String,Integer> prefixes=new HashMap<>();private JSONObject verdict;private boolean closed;
    public TeamFrozenReview(TeamWorkFiles.Workspace workspace,JSONObject nativeBinding,RunControl original,Current current)throws Exception {
        this.workspace=workspace;binding=new JSONObject(nativeBinding.toString());plan=binding.getJSONObject("plan");this.original=original;this.current=current;
        if(!TeamWorkFiles.hash(TeamWorkFiles.canonical(plan)).equals(binding.getString("planHash"))||!"reviewer".equals(binding.getString("memberId")))throw new SecurityException("独审缺少原冻结计划或独立执行身份");
    }
    @Override public JSONArray tools()throws Exception {
        JSONObject props=new JSONObject().put("artifactId",new JSONObject().put("type","string").put("maxLength",36)).put("offset",new JSONObject().put("type","integer").put("minimum",0)).put("length",new JSONObject().put("type","integer").put("minimum",1).put("maximum",12000));
        JSONObject decision=new JSONObject().put("verdict",new JSONObject().put("type","string").put("enum",new JSONArray().put("no_blocking_findings").put("changes_requested").put("inconclusive"))).put("summary",new JSONObject().put("type","string").put("minLength",1).put("maxLength",4000));
        return new JSONArray().put(tool("team_review_manifest","读取本次独审的真实冻结成果清单；不是文件正文。",new JSONObject(),new JSONArray()))
                .put(tool("team_read_review_file","分页读取选定冻结成果的实际原字节；应从offset=0连续读取完整文件。",props,new JSONArray().put("artifactId")))
                .put(tool("team_submit_review","提交独立审查意见；只是模型意见，不能批准写入或宣称业务验证。",decision,new JSONArray().put("verdict").put("summary")));
    }
    private static JSONObject tool(String name,String description,JSONObject fields,JSONArray required)throws Exception {
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",fields).put("required",required).put("additionalProperties",false)));
    }
    @Override public boolean supports(String name){return name.equals("team_review_manifest")||name.equals("team_read_review_file")||name.equals("team_submit_review");}
    @Override public boolean sideEffecting(String name){return false;}
    @Override public void validate(String name,JSONObject args)throws Exception {
        if(!supports(name))throw new SecurityException("独审只允许读取原冻结成果及提交意见");java.util.Iterator<String> keys=args.keys();while(keys.hasNext()){String key=keys.next();if(name.equals("team_review_manifest")||!(name.equals("team_submit_review")?java.util.Set.of("verdict","summary"):java.util.Set.of("artifactId","offset","length")).contains(key))throw new SecurityException("独审不能提供执行身份或写操作");}
        if(name.equals("team_submit_review")&&(!args.getString("verdict").matches("no_blocking_findings|changes_requested|inconclusive")||args.getString("summary").codePoints().allMatch(Character::isWhitespace)||args.getString("summary").length()>4000))throw new IllegalArgumentException("独审意见无效");
        if(name.equals("team_read_review_file")){selected(args.getString("artifactId"));for(String key:new String[]{"offset","length"})if(args.has(key)&&(!(args.opt(key) instanceof Integer)||args.getInt(key)<(key.equals("length")?1:0)||args.getInt(key)>(key.equals("length")?12000:65536)))throw new IllegalArgumentException("读取分页无效");}
    }
    private JSONObject selected(String id)throws Exception {JSONArray files=plan.getJSONArray("files");for(int i=0;i<files.length();i++)if(id.equals(files.getJSONObject(i).getString("artifactId")))return files.getJSONObject(i);throw new SecurityException("成果不属于原冻结独审版本");}
    private void check(String tool,RunControl control)throws Exception {if(closed||control!=original||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("独审原执行身份已变化");original.check();if(original.isPaused())throw new SecurityException("独审已暂停");current.check(binding,tool,control);}
    @Override public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception {
        check(name,control);validate(name,args);
        if(name.equals("team_review_manifest"))return new JSONObject().put("planHash",binding.getString("planHash")).put("files",new JSONArray(plan.getJSONArray("files").toString())).put("businessVerified",false);
        if(name.equals("team_submit_review")){if(!args.getString("verdict").equals("inconclusive")&&!proof().snapshot().getBoolean("completeOriginalFilesRead"))throw new SecurityException("尚未实际读取全部冻结文件，不能给出完整独审结论");verdict=new JSONObject(args.toString());return new JSONObject().put("savedForOriginalReview",true).put("verdict",args.getString("verdict")).put("planHash",binding.getString("planHash")).put("executionAuthorized",false).put("businessVerified",false);}
        JSONObject ref=selected(args.getString("artifactId")),artifact=TeamWorkReview.artifact(workspace,ref);String text=artifact.getString("content");int start=args.optInt("offset",0);if(start>text.length())throw new IllegalArgumentException("读取起点超过原文件");int end=Math.min(text.length(),start+args.optInt("length",12000));
        if(start>0&&start<text.length()&&Character.isLowSurrogate(text.charAt(start)))throw new IllegalArgumentException("读取起点不能拆开字符");if(end<text.length()&&end>start&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        check(name,control);int prefix=prefixes.getOrDefault(ref.getString("artifactId"),0);if(start<=prefix)prefixes.put(ref.getString("artifactId"),Math.max(prefix,end));
        return new JSONObject().put("artifactId",ref.getString("artifactId")).put("sha256",ref.getString("sha256")).put("path",ref.getString("path")).put("body",text.substring(start,end)).put("offset",start).put("nextOffset",end).put("totalChars",text.length()).put("complete",end==text.length()).put("planHash",binding.getString("planHash")).put("executionAuthorized",false);
    }
    public Proof proof()throws Exception {
        JSONArray read=new JSONArray(),files=plan.getJSONArray("files");boolean complete=files.length()>0;
        for(int i=0;i<files.length();i++){JSONObject ref=files.getJSONObject(i),artifact=TeamWorkReview.artifact(workspace,ref);int prefix=prefixes.getOrDefault(ref.getString("artifactId"),-1),length=artifact.getString("content").length();boolean covered=prefix>=length;complete&=covered;read.put(new JSONObject(ref.toString()).put("readPrefixChars",Math.max(0,prefix)).put("totalChars",length).put("complete",covered));}
        return new Proof(new JSONObject().put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId")).put("teamRunId",binding.getString("teamRunId")).put("planHash",binding.getString("planHash")).put("files",read).put("completeOriginalFilesRead",complete).put("verdictSubmitted",verdict!=null).put("verdict",verdict==null?"inconclusive":verdict.getString("verdict")).put("findings",verdict==null?"":verdict.getString("summary")).put("businessVerified",false));
    }
    @Override public void close(){closed=true;}
}
