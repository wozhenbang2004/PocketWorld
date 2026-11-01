package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent original Runner. Native reads alone are not proof that unchanged bytes reached its model. */
public final class TeamFrozenReview implements CapabilityPort {
    public interface Current {void check(JSONObject binding,String tool,RunControl control)throws Exception;}
    public static final class Proof {
        private final String json;private Proof(JSONObject value){json=value.toString();}
        public JSONObject snapshot()throws Exception{return new JSONObject(json);}
    }
    private final TeamWorkFiles.Workspace workspace;private final JSONObject binding,plan;private final RunControl original;private final Current current;
    private final Map<String,Integer> deliveredPrefixes=new HashMap<>(),presentedPrefixes=new HashMap<>();
    private final Map<String,JSONObject> pendingPages=new HashMap<>(),deliveredPages=new java.util.LinkedHashMap<>();
    private final Map<String,Map<String,Integer>> requests=new HashMap<>();private JSONObject verdict;private boolean closed;
    public TeamFrozenReview(TeamWorkFiles.Workspace workspace,JSONObject nativeBinding,RunControl original,Current current)throws Exception {
        this.workspace=workspace;binding=new JSONObject(nativeBinding.toString());plan=binding.getJSONObject("plan");this.original=original;this.current=current;
        if(!TeamWorkFiles.hash(TeamWorkFiles.canonical(plan)).equals(binding.getString("planHash"))||!"reviewer".equals(binding.getString("memberId")))throw new SecurityException("独审缺少原冻结计划或独立执行身份");
    }
    @Override public JSONArray tools()throws Exception {
        JSONObject props=new JSONObject().put("artifactId",new JSONObject().put("type","string").put("maxLength",36)).put("offset",new JSONObject().put("type","integer").put("minimum",0).put("maximum",2*1024*1024)).put("length",new JSONObject().put("type","integer").put("minimum",1).put("maximum",12000));
        JSONObject decision=new JSONObject().put("verdict",new JSONObject().put("type","string").put("enum",new JSONArray().put("no_blocking_findings").put("changes_requested").put("inconclusive"))).put("summary",new JSONObject().put("type","string").put("minLength",1).put("maxLength",4000));
        return new JSONArray().put(tool("team_review_manifest","读取本次独审的真实冻结成果清单和阅读单位；清单不算已读正文。",new JSONObject(),new JSONArray()))
                .put(tool("team_read_review_file","从offset=0连续读准确原件。文字body以UTF-16分页；二进制contentBase64以byte分页(每页最多8192B)；真实删除只读tombstone证据。须实际收到正文后再独立判断，不能同批边读边凭空提交结论。",props,new JSONArray().put("artifactId")))
                .put(tool("team_submit_review","提交独立模型意见。只有完整准确原件已呈现给本模型并响应后才准完整结论；无法判断二进制语义应inconclusive，不自动算质量合格。",decision,new JSONArray().put("verdict").put("summary")));
    }
    private static JSONObject tool(String name,String description,JSONObject fields,JSONArray required)throws Exception {return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",fields).put("required",required).put("additionalProperties",false)));}
    @Override public boolean supports(String name){return "team_review_manifest".equals(name)||"team_read_review_file".equals(name)||"team_submit_review".equals(name);}
    @Override public boolean sideEffecting(String name){return false;}
    @Override public void validate(String name,JSONObject args)throws Exception {
        if(!supports(name))throw new SecurityException("独审只允许读取原冻结成果及提交意见");java.util.Iterator<String> keys=args.keys();while(keys.hasNext()){String key=keys.next();if(name.equals("team_review_manifest")||!(name.equals("team_submit_review")?java.util.Set.of("verdict","summary"):java.util.Set.of("artifactId","offset","length")).contains(key))throw new SecurityException("独审不能提供执行身份或写操作");}
        if(name.equals("team_submit_review")&&(!args.getString("verdict").matches("no_blocking_findings|changes_requested|inconclusive")||args.getString("summary").codePoints().allMatch(Character::isWhitespace)||args.getString("summary").length()>4000))throw new IllegalArgumentException("独审意见无效");
        if(name.equals("team_read_review_file")){selected(args.getString("artifactId"));for(String key:new String[]{"offset","length"})if(args.has(key)&&(!(args.opt(key) instanceof Integer)||args.getInt(key)<(key.equals("length")?1:0)||args.getInt(key)>(key.equals("length")?12000:2*1024*1024)))throw new IllegalArgumentException("读取分页无效");}
    }
    private JSONObject selected(String id)throws Exception {JSONArray files=plan.getJSONArray("files");for(int i=0;i<files.length();i++)if(id.equals(files.getJSONObject(i).getString("artifactId")))return files.getJSONObject(i);throw new SecurityException("成果不属于原冻结独审版本");}
    private void check(String tool,RunControl control)throws Exception {if(closed||control!=original||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("独审原执行身份已变化");original.check();if(original.isPaused())throw new SecurityException("独审已暂停");current.check(binding,tool,control);}
    @Override public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception {
        check(name,control);validate(name,args);
        if(name.equals("team_review_manifest"))return new JSONObject().put("ok",true).put("planHash",binding.getString("planHash")).put("files",new JSONArray(plan.getJSONArray("files").toString())).put("readPlan",readPlan(workspace,plan)).put("businessVerified",false);
        if(name.equals("team_submit_review")){if(!args.getString("verdict").equals("inconclusive")&&!proof().snapshot().getBoolean("completeOriginalFilesRead"))throw new SecurityException("准确原件尚未全部完成安全回执及实际模型呈现，不能给出完整独审结论");verdict=new JSONObject(args.toString());return new JSONObject().put("ok",true).put("savedForOriginalReview",true).put("verdict",args.getString("verdict")).put("planHash",binding.getString("planHash")).put("executionAuthorized",false).put("businessVerified",false);}
        JSONObject ref=selected(args.getString("artifactId")),artifact=TeamWorkReview.artifact(workspace,ref),page;int start=args.optInt("offset",0);
        if(TeamWorkReview.isDeletion(ref)){if(start!=0)throw new IllegalArgumentException("删除成果没有正文分页");page=TeamWorkFiles.deletedView(ref.getString("path"),ref.getInt("draftVersion")).put("unit","tombstone").put("offset",0).put("nextOffset",0).put("totalUnits",0).put("deletionEvidence",new JSONObject().put("artifactKind","file_tombstone").put("sourceVersion",ref.getInt("draftVersion")).put("sourceRevisionId",ref.getString("draftRevisionId")));}
        else {byte[] bytes=TeamWorkReview.artifactBytes(workspace,ref);String text=artifact.optString("content",null);page=TeamWorkFiles.page(ref.getString("path"),artifact.optString("kind","text_file"),text,bytes,start,args.optInt("length",12000));if(page.has("content")){String body=page.getString("content");page.remove("content");page.put("body",body).put("rangeSha256",TeamWorkFiles.hash(body)).put("totalUnits",text.length());}else page.put("totalUnits",bytes.length);}
        page.put("ok",true).put("artifactId",ref.getString("artifactId")).put("sha256",ref.getString("sha256")).put("planHash",binding.getString("planHash")).put("executionAuthorized",false);
        check(name,control);pendingPages.put(pageKey(page),new JSONObject(page.toString()));return page;
    }
    private static String pageKey(JSONObject page)throws Exception{return page.getString("artifactId")+":"+page.getString("unit")+":"+page.getInt("offset")+":"+page.getInt("nextOffset");}
    private static void advance(Map<String,Integer> target,JSONObject page)throws Exception {String id=page.getString("artifactId");int before=target.getOrDefault(id,-1);if(page.getInt("offset")<=Math.max(0,before))target.put(id,Math.max(before,page.getInt("nextOffset")));}
    /** The ORIGINAL Runner calls this only after its real safe receipt and checkpoint committed. */
    @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control)throws Exception {
        if(!name.equals("team_read_review_file"))return;check(name,control);String key=pageKey(receipt);JSONObject expected=pendingPages.remove(key);
        if(expected==null||!args.getString("artifactId").equals(receipt.getString("artifactId"))||!TeamWorkFiles.canonical(expected).equals(TeamWorkFiles.canonical(receipt)))throw new SecurityException("原件页在真实安全回执投影中改变或缺失，未计入完整阅读；不绕过密钥清洗");
        if(receipt.has("contentBase64")){byte[] actual;try{actual=java.util.Base64.getDecoder().decode(receipt.getString("contentBase64"));}catch(IllegalArgumentException invalid){throw new SecurityException("安全回执并非原二进制页",invalid);}if(actual.length!=receipt.getInt("nextOffset")-receipt.getInt("offset")||!TeamWorkFiles.hash(actual).equals(receipt.getString("rangeSha256")))throw new SecurityException("安全回执原字节页hash不符");}
        deliveredPages.put(key,new JSONObject(receipt.toString()));advance(deliveredPrefixes,receipt);
    }
    /** Presentation is checked against the final original wire, not a fabricated provided/use receipt. */
    @Override public void beforeModelRequest(String requestId,JSONArray projected,RunControl control)throws Exception {
        check("team_review_manifest",control);if(requests.size()>=8&&!requests.containsKey(requestId))throw new SecurityException("独审未决模型请求已满");Map<String,Integer> proposed=new HashMap<>(presentedPrefixes);java.util.List<JSONObject> pages=new java.util.ArrayList<>();
        for(int i=0;i<projected.length();i++){JSONObject row=projected.getJSONObject(i);if(!"tool".equals(row.optString("role")))continue;JSONObject page;try{page=new JSONObject(row.getString("content"));if(!page.has("artifactId")||!page.has("unit")||!page.has("nextOffset"))continue;String key=pageKey(page);JSONObject delivered=deliveredPages.get(key);if(delivered!=null&&TeamWorkFiles.canonical(delivered).equals(TeamWorkFiles.canonical(page)))pages.add(page);}catch(org.json.JSONException notPage){/* Ordinary unrelated real tool rows are not page proofs. */}}
        pages.sort(java.util.Comparator.comparing((JSONObject value)->value.optString("artifactId")).thenComparingInt(value->value.optInt("offset")));for(JSONObject page:pages)advance(proposed,page);requests.put(requestId,proposed);
    }
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception {check("team_review_manifest",control);Map<String,Integer> proposed=requests.remove(requestId);if(proposed!=null)for(var entry:proposed.entrySet())presentedPrefixes.merge(entry.getKey(),entry.getValue(),Math::max);return null;}
    @Override public void modelRequestAbandoned(String requestId){requests.remove(requestId);}
    public static JSONArray readPlan(TeamWorkFiles.Workspace workspace,JSONObject plan)throws Exception {
        JSONArray files=plan.getJSONArray("files"),rows=new JSONArray();for(int i=0;i<files.length();i++){JSONObject ref=files.getJSONObject(i),actual=TeamWorkReview.artifact(workspace,ref);boolean deleted=TeamWorkReview.isDeletion(ref),text=actual.has("content");int length=deleted?0:text?actual.getString("content").length():TeamWorkReview.artifactBytes(workspace,ref).length,perPage=text?12000:8192;rows.put(new JSONObject().put("artifactId",ref.getString("artifactId")).put("unit",deleted?"tombstone":text?"UTF-16":"byte").put("totalUnits",length).put("minimumReadCalls",Math.max(1,(length+perPage-1)/perPage)));}return rows;
    }
    public Proof proof()throws Exception {
        JSONArray read=new JSONArray(),files=plan.getJSONArray("files"),readPlan=readPlan(workspace,plan);boolean complete=files.length()>0;
        for(int i=0;i<files.length();i++){JSONObject ref=files.getJSONObject(i),required=readPlan.getJSONObject(i);int delivered=deliveredPrefixes.getOrDefault(ref.getString("artifactId"),-1),presented=presentedPrefixes.getOrDefault(ref.getString("artifactId"),-1),length=required.getInt("totalUnits");boolean covered=delivered>=length&&presented>=length;complete&=covered;JSONObject row=new JSONObject(ref.toString()).put("coverageUnit",required.getString("unit")).put("safeReceiptPrefix",Math.max(0,delivered)).put("modelPresentedPrefix",Math.max(0,presented)).put("totalUnits",length).put("complete",covered);if(required.getString("unit").equals("UTF-16"))row.put("readPrefixChars",Math.max(0,presented)).put("totalChars",length);read.put(row);}
        return new Proof(new JSONObject().put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId")).put("teamRunId",binding.getString("teamRunId")).put("planHash",binding.getString("planHash")).put("files",read).put("completeOriginalFilesRead",complete).put("safeReceiptAndModelPresentationVerified",complete).put("verdictSubmitted",verdict!=null).put("verdict",verdict==null?"inconclusive":verdict.getString("verdict")).put("findings",verdict==null?"":verdict.getString("summary")).put("businessVerified",false).put("actualModelUseVerified",false));
    }
    @Override public void close(){closed=true;pendingPages.clear();requests.clear();}
}
