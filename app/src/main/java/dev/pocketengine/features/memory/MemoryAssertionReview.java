package dev.pocketengine.features.memory;

import java.util.HashSet;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native candidate review on the original Book. Identity choices are projections of live
 * records, never a second registry, a name matcher, a model proof or an execution grant. */
public final class MemoryAssertionReview {
    public static final int MAX_IDENTITIES=64;
    private static final Set<String> PREPARE=Set.of("id","revision","query");
    private static final Set<String> APPLY=Set.of("id","revision","subject","relationTarget","kind","predicate","object","condition","eventTime","validFrom","validTo","reviewAfter");
    private MemoryAssertionReview(){}

    public static JSONObject prepare(MemoryBook book,JSONObject scope,JSONObject request,long now)throws Exception {
        keys(request,PREPARE);String query=request.has("query")?text(request,"query",0,160):"";
        synchronized(book){
            JSONObject candidate=pending(book,scope,request);
            JSONArray identities=new JSONArray(),rows=book.list(scope);Set<String> seen=new HashSet<>();boolean more=false;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);if(!eligible(row))continue;
                for(String role:new String[]{"subject","object"}){
                    JSONObject identity=identity(row,role);if(identity==null||"owner".equals(identity.getString("type")))continue;
                    String key=identity.getString("id")+"\n"+identity.getString("type")+"\n"+identity.getString("label");
                    String searchable=(identity.getString("id")+"\n"+identity.getString("label")+"\n"+row.getString("text")).toLowerCase(Locale.ROOT);
                    if(!searchable.contains(query.toLowerCase(Locale.ROOT))||!seen.add(key))continue;
                    if(identities.length()>=MAX_IDENTITIES){more=true;continue;}
                    identities.put(identity.put("selection",new JSONObject().put("mode","existing").put("recordId",row.getString("id"))
                            .put("recordRevision",row.getLong("revision")).put("role",role)).put("recordPreview",prefix(row.getString("text"),160)));
                }
            }
            return outcome(candidate).put("identities",identities).put("query",query).put("hasMoreIdentities",more);
        }
    }

    public static JSONObject apply(MemoryBook book,JSONObject scope,JSONObject request,long now)throws Exception {
        keys(request,APPLY);
        String kind=text(request,"kind",1,20);if(!Set.of("inferred","told","imagined").contains(kind))throw new SecurityException("主体核对不会签发主人原话证明，请保留推测、转述或未发生的计划标注");
        synchronized(book){
            JSONObject candidate=pending(book,scope,request);
            JSONObject subject=selected(book,scope,candidate,request.getJSONObject("subject"));
            Map<String,JSONObject> donors=new LinkedHashMap<>();donor(request.getJSONObject("subject"),donors);
            JSONObject assertion=new JSONObject().put("version",MemoryAssertion.VERSION).put("kind",kind)
                    .put("subjectId",subject.getString("id")).put("subjectType",subject.getString("type")).put("subjectLabel",subject.getString("label"))
                    .put("predicate",text(request,"predicate",1,160)).put("object",text(request,"object",1,4000))
                    .put("condition",request.has("condition")?text(request,"condition",0,1000):"")
                    .put("assertedAt",JSONObject.NULL).put("evidenceSpans",new JSONArray());
            for(String field:new String[]{"eventTime","validFrom","validTo","reviewAfter"})assertion.put(field,request.has(field)&&!request.isNull(field)?SourceRef.integer(request,field,0):JSONObject.NULL);
            if(request.has("relationTarget")&&!request.isNull("relationTarget")){
                JSONObject target=selected(book,scope,candidate,request.getJSONObject("relationTarget"));
                if(!assertion.getString("object").equals(target.getString("label")))throw new IllegalArgumentException("关系目标名称须与事实值一致；请重新核对目标，不能把另一段文字关联到该身份");
                assertion.put("objectId",target.getString("id")).put("objectType",target.getString("type"));
                donor(request.getJSONObject("relationTarget"),donors);
            }
            JSONArray references=new JSONArray();for(JSONObject ref:donors.values())references.put(ref);
            // The original Book transaction retains exact donor dependencies too: a later
            // correction/forget must retire this candidate and its accepted derivation.
            JSONObject updated=book.reviewCandidateAssertion(scope,candidate.getString("id"),candidate.getLong("revision"),
                    MemoryAssertion.checked(assertion),references,now);
            return outcome(updated);
        }
    }

    private static JSONObject pending(MemoryBook book,JSONObject scope,JSONObject request)throws Exception {
        String id=text(request,"id",1,160);long revision=SourceRef.integer(request,"revision",1);
        JSONObject row=book.getCandidate(scope,id);
        if(row.getLong("revision")!=revision||!"pending".equals(row.getString("status")))throw new IllegalStateException("候选已变化或不再待审，请读取当前版本后重新核对主体");
        return row;
    }
    private static JSONObject selected(MemoryBook book,JSONObject scope,JSONObject candidate,JSONObject selection)throws Exception {
        String mode=text(selection,"mode",1,20);
        if("owner".equals(mode)){keys(selection,Set.of("mode"));return new JSONObject().put("id","owner").put("type","owner").put("label","主人");}
        if("new".equals(mode)){
            keys(selection,Set.of("mode","type","label"));String type=text(selection,"type",1,16);
            if(!Set.of("person","entity").contains(type))throw new IllegalArgumentException("请选择独立的人物或实体；主人身份需单独明确选择");
            return new JSONObject().put("id",type+":"+UUID.randomUUID()).put("type",type).put("label",text(selection,"label",1,160));
        }
        if("candidate".equals(mode)){
            keys(selection,Set.of("mode","role","label"));
            JSONObject result=candidate.has("assertion")?identity(candidate,text(selection,"role",1,16)):null;
            if(result==null||"owner".equals(result.getString("type")))throw new SecurityException("本候选没有已保存的独立身份，请明确选择主体");
            if(selection.has("label"))result.put("label",text(selection,"label",1,160));return result;
        }
        if(!"existing".equals(mode))throw new IllegalArgumentException("请选择已核对的主体或新建独立身份，不能从姓名猜测");
        keys(selection,Set.of("mode","recordId","recordRevision","role","label"));
        JSONObject row=book.get(scope,text(selection,"recordId",1,160));long revision=SourceRef.integer(selection,"recordRevision",1);
        if(row.getLong("revision")!=revision||!eligible(row))throw new SecurityException("所选身份的原记忆已换版、失效或遗忘，请重新选择");
        JSONObject result=identity(row,text(selection,"role",1,16));
        if(result==null||"owner".equals(result.getString("type")))throw new SecurityException("原记录没有这个可选身份；主人须明确选择，不能由姓名转入");
        if(selection.has("label"))result.put("label",text(selection,"label",1,160));
        return result;
    }
    private static boolean eligible(JSONObject row){return row.optBoolean("active")&&!row.optBoolean("archiveOnly")&&row.optJSONObject("assertion")!=null;}
    private static void donor(JSONObject selection,Map<String,JSONObject> donors)throws Exception {
        if("existing".equals(selection.getString("mode")))donors.put(selection.getString("recordId"),new JSONObject().put("id",selection.getString("recordId")).put("revision",selection.getLong("recordRevision")));
    }
    private static JSONObject identity(JSONObject row,String role)throws Exception {
        JSONObject assertion=row.getJSONObject("assertion");
        if("subject".equals(role))return new JSONObject().put("id",assertion.getString("subjectId")).put("type",assertion.getString("subjectType")).put("label",assertion.getString("subjectLabel"));
        if(!"object".equals(role))throw new IllegalArgumentException("身份端点只能选择主体或关系目标");
        if(!assertion.has("objectId"))return null;
        return new JSONObject().put("id",assertion.getString("objectId")).put("type",assertion.getString("objectType")).put("label",prefix(assertion.getString("object"),160));
    }
    private static JSONObject outcome(JSONObject candidate)throws Exception{return new JSONObject().put("candidate",candidate).put("formalMemorySaved",false).put("executionAuthorized",false).put("sourceProofIssued",false);}
    private static void keys(JSONObject value,Set<String> allowed){if(value==null)throw new IllegalArgumentException("缺少主体核对内容");for(var it=value.keys();it.hasNext();)if(!allowed.contains(it.next()))throw new SecurityException("主体核对不能设置来源、空间、证明或执行权限");}
    private static String text(JSONObject value,String key,int min,int max){Object raw=value.opt(key);if(!(raw instanceof String))throw new IllegalArgumentException(key+"必须是文字");String text=((String)raw).trim();if(text.length()<min||text.length()>max)throw new IllegalArgumentException(key+"长度无效");return text;}
    private static String prefix(String value,int max){int end=Math.min(max,value.length());if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;return value.substring(0,end);}
}
