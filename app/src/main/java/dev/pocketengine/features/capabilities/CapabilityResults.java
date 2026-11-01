package dev.pocketengine.features.capabilities;

import java.nio.charset.StandardCharsets;
import dev.pocketengine.features.memory.ToolOutputCapture;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Model data is kept below a fixed byte budget and never interpreted as host instructions. */
final class CapabilityResults {
    static final int MAX_BYTES=32768;
    private static final Set<String> SSH_DATA=Set.of("path","stdout","stderr","content","files","bytes","version","currentVersion","outputTruncated","readOnly","cooperativeConditionalWrite","externalWriterRacePossible","workspaceIsSandbox","stagingPath","lockPath");
    private static final Set<String> BROWSER_DATA=Set.of("url","title","text","elements","observationId","navigation","expiresInMs","profileId","profileRevision","browserSessionId","profileIsolation","untrustedPageContent");
    private static final Set<String> ATTACHMENT_DATA=Set.of("id","name","mimeType","size","sha256","createdAt","editableSource","formatCheck","documentPlanDigest","parentArtifact","processing");
    private CapabilityResults(){}

    static JSONObject adapt(CapabilityDescriptor descriptor,JSONObject nativeResult,String operationId)throws Exception{return adapt(descriptor,nativeResult,operationId,null);}
    static JSONObject adapt(CapabilityDescriptor descriptor,JSONObject nativeResult,String operationId,JSONObject capture)throws Exception {
        if(capture!=null)capture=ToolOutputCapture.checkedMetadata(capture);
        if(nativeResult==null)throw new IllegalStateException("能力未返回实际回执");
        if(descriptor.name.equals("attachments_read_document"))return document(descriptor,nativeResult,capture);
        if(descriptor.feature.equals("browser")&&descriptor.command.equals("observe"))return browserObservation(descriptor,nativeResult,capture);
        JSONObject out=envelope(descriptor,operationId,capture),receipt=new JSONObject();
        String status=nativeResult.optString("status");
        if(descriptor.name.matches("attachments_create_document|attachments_transform_image|attachments_trim_media")&&nativeResult.optBoolean("outputNotPublished")&&"failed".equals(nativeResult.optString("status"))){status="failed";}
        else if(descriptor.name.matches("attachments_create_text|attachments_create_document|attachments_transform_image|attachments_trim_media")){
            CapabilityDescriptor.uuid(nativeResult.optString("id"));if(!nativeResult.optString("sha256").matches("[a-f0-9]{64}"))throw new IllegalStateException("附件未返回实际字节摘要");status="created";
        }else if(!Set.of("completed","failed","cancelled","unknown","navigation_requested").contains(status)){
            status=descriptor.feature.equals("browser")&&nativeResult.opt("accepted") instanceof Boolean?"action_receipt":"unknown";
        }
        boolean unknown=status.equals("unknown")||nativeResult.has("accepted")&&nativeResult.isNull("accepted")||nativeResult.optString("code").contains("unknown");
        boolean accepted=nativeResult.optBoolean("accepted",true);
        if(descriptor.feature.equals("browser")&&!descriptor.command.equals("observe")&&!nativeResult.has("accepted"))unknown=true;
        boolean ok=!unknown&&!status.matches("failed|cancelled")&&accepted;
        out.put("ok",ok).put("status",unknown?"unknown":status).put("verified",false);
        receipt.put("status",unknown?"unknown":status).put("verified",false);
        if(nativeResult.has("accepted")){Object value=nativeResult.get("accepted");if(value instanceof Boolean||value==JSONObject.NULL)receipt.put("accepted",value);}
        if(unknown){out.put("accepted",JSONObject.NULL).put("code","operation_result_unknown").put("requiresReconciliation",true);}
        else if(nativeResult.has("accepted"))out.put("accepted",accepted);
        if(nativeResult.opt("exitCode") instanceof Number)receipt.put("exitCode",nativeResult.get("exitCode"));
        if(nativeResult.opt("code") instanceof String)receipt.put("code",clip(nativeResult.getString("code"),100));
        if(nativeResult.opt("error") instanceof String)receipt.put("error",clip(nativeResult.getString("error"),100));
        if(nativeResult.opt("message") instanceof String)receipt.put("message",clip(nativeResult.getString("message"),512));
        if(descriptor.feature.equals("ssh")){
            receipt.put("transport","direct_ssh").put("remoteTerminationConfirmed",false);
            // SshModule uses operationId as its own runId. It is never the host attempt's runId.
            if(nativeResult.has("operationId"))receipt.put("operationId",nativeResult.getString("operationId"));
        }
        if(descriptor.name.matches("attachments_create_text|attachments_create_document|attachments_transform_image|attachments_trim_media")&&!status.equals("failed")){out.put("accepted",true);receipt.put("accepted",true);}
        out.put("receipt",receipt);
        Set<String> fields=descriptor.feature.equals("browser")?BROWSER_DATA:descriptor.feature.equals("ssh")?SSH_DATA:ATTACHMENT_DATA;
        JSONObject selected=new JSONObject();for(String key:fields)if(nativeResult.has(key))selected.put(key,nativeResult.get(key));
        Budget budget=new Budget(Math.max(0,Math.min(24000,MAX_BYTES-bytes(out)-256)));out.put("data",copy(selected,budget,0)).put("outputTruncated",budget.truncated);
        if(bytes(out)>MAX_BYTES)out.put("data",new JSONObject()).put("outputTruncated",true);
        return out;
    }
    private static JSONObject browserObservation(CapabilityDescriptor descriptor,JSONObject raw,JSONObject capture)throws Exception {
        CapabilityDescriptor.uuid(raw.optString("observationId"));
        if(!(raw.opt("navigation") instanceof Number)||raw.getLong("navigation")<0||!(raw.opt("expiresInMs") instanceof Number)||raw.getLong("expiresInMs")<1||raw.getLong("expiresInMs")>20000||!(raw.opt("text") instanceof String)||!(raw.opt("elements") instanceof JSONArray))throw new IllegalStateException("浏览器观察缺少正式引用或内容字段");
        JSONObject data=new JSONObject().put("observationId",raw.getString("observationId")).put("navigation",raw.getLong("navigation"))
                .put("expiresInMs",raw.getLong("expiresInMs")).put("profileId",raw.getString("profileId")).put("profileRevision",raw.getLong("profileRevision")).put("browserSessionId",raw.getString("browserSessionId")).put("profileIsolation",raw.getString("profileIsolation")).put("untrustedPageContent",true)
                .put("url",clip(raw.optString("url"),2048)).put("title",clip(raw.optString("title"),256));
        JSONObject out=envelope(descriptor,"",capture).put("ok",true).put("status","observed").put("verified",false).put("outputTruncated",false)
                .put("receipt",new JSONObject().put("status","observed").put("verified",false)).put("data",data);
        JSONArray source=raw.getJSONArray("elements"),kept=new JSONArray();data.put("elements",kept);boolean truncated=!raw.optString("url").equals(data.getString("url"))||!raw.optString("title").equals(data.getString("title"));
        for(int i=0;i<source.length();i++){
            if(i>=250){truncated=true;break;}JSONObject element=source.optJSONObject(i);
            if(element==null||!element.optString("ref").matches("n[0-9]{1,3}")||!(element.opt("tag") instanceof String)||!(element.opt("type") instanceof String)||!(element.opt("text") instanceof String)){truncated=true;continue;}
            // A row is built in isolation, with its original ref intact. Shared text-budget
            // exhaustion must never create a partial element with missing/NULL control fields.
            JSONObject row=new JSONObject().put("ref",element.getString("ref"));
            for(String key:new String[]{"tag","type","text","id","name","href"})if(element.opt(key) instanceof String){String value=element.getString(key);int limit=key.equals("text")?160:key.equals("href")?512:key.equals("tag")||key.equals("type")?32:128;String bounded=clip(value,limit);row.put(key,bounded);if(!value.equals(bounded))truncated=true;}
            if(element.opt("ownerRequired") instanceof Boolean)row.put("ownerRequired",element.getBoolean("ownerRequired"));
            kept.put(row);if(bytes(out)>MAX_BYTES-11000){kept.remove(kept.length()-1);truncated=true;break;}
        }
        data.put("elementsOmitted",source.length()-kept.length());
        Budget body=new Budget(Math.min(10000,MAX_BYTES-bytes(out)-128));data.put("text",copy(raw.getString("text"),body,0));
        out.put("outputTruncated",truncated||body.truncated);if(bytes(out)>MAX_BYTES)throw new IllegalStateException("浏览器观察元数据超过模型预算");return out;
    }
    /** No contents are delivered after authority loss; the durable journal still receives bounded evidence. */
    static JSONObject uncertain(CapabilityDescriptor descriptor,String operationId,JSONObject nativeResult)throws Exception {
        JSONObject out=envelope(descriptor,operationId).put("ok",false).put("status","unknown").put("accepted",JSONObject.NULL)
                .put("verified",false).put("code","operation_result_unknown").put("requiresReconciliation",true)
                .put("message","调用已进入原执行器，当前回执或身份未确定；请核对原操作，不会自动重放。");
        if(nativeResult!=null){JSONObject evidence=new JSONObject();for(String key:new String[]{"status","accepted","exitCode","operationId","id","sha256"}){
            Object value=nativeResult.opt(key);if(value==JSONObject.NULL||value instanceof Boolean||value instanceof Number)evidence.put(key,value);
            else if(value instanceof String)evidence.put(key,clip((String)value,100));
        }out.put("receiptEvidence",evidence);}
        return out;
    }
    /** A definite committed local artifact is evidence, not a cancelled or rolled-back write. */
    static JSONObject creationAfterStop(CapabilityDescriptor descriptor,JSONObject raw,String operationId,boolean cancelled)throws Exception {
        JSONObject out=adapt(descriptor,raw,operationId);
        if(!"created".equals(out.optString("status")))throw new IllegalStateException("No committed creation receipt");
        return out.put("executionInvalidatedAfterDispatch",true).put("cancelledAfterDispatch",cancelled).put("requiresPause",true).put("code","needs_user")
                .put("message","已收到真实附件创建回执；随后执行状态或授权变化，已停止后续派发，请核对原成果。")
                .put("verifiedBusinessSuccess",false);
    }
    private static JSONObject document(CapabilityDescriptor descriptor,JSONObject raw,JSONObject capture)throws Exception {
        JSONObject out=envelope(descriptor,"",capture);String status=raw.optString("status");
        if(!Set.of("ok","partial","truncated","empty_range","unavailable","unsupported","encrypted","invalid","limit_exceeded","input_changed").contains(status))throw new IllegalStateException("文档读取未返回正式状态");
        CapabilityDescriptor.uuid(raw.optString("attachmentId"));if(!raw.optString("sha256").matches("[a-f0-9]{64}"))throw new IllegalStateException("文档缺少真实附件摘要");
        JSONObject data=new JSONObject();Budget metadata=new Budget(5000);
        for(String key:new String[]{"attachmentId","sha256","parserVersion","engineVersion","extraction","extractionStatus","format","coverage","totalUnits","warnings","cacheUsed"})if(raw.has(key))data.put(key,copy(raw.get(key),metadata,0));
        int nextUnit=raw.optInt("nextUnit",0),nextChunk=raw.optInt("nextChunk",0);
        if(nextUnit<0||nextUnit>1000000||nextChunk<0||nextChunk>1000000||nextUnit==0&&nextChunk!=0)throw new IllegalStateException("文档继续游标无效");
        data.put("status",status).put("untrustedAttachmentContent",true).put("truncated",raw.optBoolean("truncated"))
                .put("nextUnit",nextUnit).put("nextChunk",nextChunk);
        JSONArray chunks=raw.optJSONArray("chunks");
        if(status.equals("input_changed")){
            if(chunks!=null&&chunks.length()!=0)throw new IllegalStateException("输入已变化，不能返回旧内容");
            chunks=new JSONArray();data.put("truncated",false).put("nextUnit",0).put("nextChunk",0);
        }else if(chunks==null)throw new IllegalStateException("文档缺少实际内容块");
        JSONArray kept=new JSONArray();data.put("chunks",kept);
        out.put("ok",Set.of("ok","partial","truncated","empty_range").contains(status)).put("status",status).put("verified",false).put("data",data).put("outputTruncated",metadata.truncated);
        for(int i=0;i<chunks.length();i++){
            JSONObject chunk=chunks.getJSONObject(i);Budget unitBudget=new Budget(12000);Object candidate=copy(chunk,unitBudget,0);
            kept.put(candidate);
            if(unitBudget.truncated||bytes(out)>MAX_BYTES-256){
                kept.remove(kept.length()-1);data.put("truncated",true).put("nextUnit",chunk.getInt("unit")).put("nextChunk",chunk.getInt("chunk"));
                out.put("outputTruncated",true);break;
            }
        }
        if(bytes(out)>MAX_BYTES)throw new IllegalStateException("文档元数据超过模型结果预算");
        return out;
    }
    static JSONObject withCapture(JSONObject receipt,JSONObject metadata)throws Exception{
        if(metadata==null)return receipt;receipt.put("outputCapture",ToolOutputCapture.checkedMetadata(metadata));
        if(bytes(receipt)>MAX_BYTES){receipt.put("data",new JSONObject()).put("outputTruncated",true);if(bytes(receipt)>MAX_BYTES)throw new IllegalStateException("原输出定位元数据超过模型envelope预算");}return receipt;
    }
    private static JSONObject envelope(CapabilityDescriptor descriptor,String operationId,JSONObject capture)throws Exception{return withCapture(envelope(descriptor,operationId),capture);}
    private static JSONObject envelope(CapabilityDescriptor descriptor,String operationId)throws Exception {
        JSONObject out=new JSONObject().put("tool",descriptor.name).put("effect",descriptor.effect).put("untrustedToolContent",true)
                .put("instructionAuthority","none").put("grantsAdded",new JSONArray()).put("automaticReplayAllowed",false).put("verifiedBusinessSuccess",false);
        if(!operationId.isEmpty())out.put("hostOperationId",operationId);return out;
    }
    private static final Set<String> PRIVATE_KEYS=Set.of("leaseId","bindingHash","_profileBinding","principalId","spaceId","hostTaskId","taskId","runId","epoch","coordinatorEpoch","authorization","token","password","secret","apiKey","privateKey","passphrase","uri");
    static JSONObject captureData(CapabilityDescriptor descriptor,JSONObject raw)throws Exception{
        if(raw==null)throw new IllegalStateException("原native结果缺失");java.util.Set<String> fields=new java.util.HashSet<>();
        if(descriptor.feature.equals("ssh")){fields.addAll(SSH_DATA);fields.add("outputComplete");fields.add("nativeOutputRange");}
        else if(descriptor.name.equals("attachments_read_document"))fields.addAll(Set.of("attachmentId","sha256","parserVersion","engineVersion","extraction","extractionStatus","format","coverage","totalUnits","warnings","cacheUsed","truncated","nextUnit","nextChunk","chunks"));
        else if(descriptor.name.matches("attachments_create_text|attachments_create_document|attachments_transform_image|attachments_trim_media"))fields.addAll(ATTACHMENT_DATA);
        else throw new IllegalArgumentException("此producer尚未挂原始输出留存");
        fields.addAll(Set.of("status","accepted","exitCode","code","error","message","outputTruncated"));JSONObject selected=new JSONObject();for(String key:fields)if(raw.has(key))selected.put(key,raw.get(key));return (JSONObject)privateFiltered(selected,new int[]{0},0);
    }
    private static Object privateFiltered(Object value,int[] nodes,int depth)throws Exception{
        if(depth>24||++nodes[0]>20000)throw new IllegalStateException("原native数据结构超过有界投影，未冒完整捕获");
        if(value instanceof JSONObject){JSONObject out=new JSONObject(),source=(JSONObject)value;for(var keys=source.keys();keys.hasNext();){String key=keys.next();if(PRIVATE_KEYS.stream().anyMatch(p->p.equalsIgnoreCase(key)))continue;out.put(key,privateFiltered(source.get(key),nodes,depth+1));}return out;}
        if(value instanceof JSONArray){JSONArray out=new JSONArray(),source=(JSONArray)value;for(int i=0;i<source.length();i++)out.put(privateFiltered(source.get(i),nodes,depth+1));return out;}
        if(value==null||value==JSONObject.NULL||value instanceof String||value instanceof Boolean||value instanceof Number)return value==null?JSONObject.NULL:value;
        throw new IllegalArgumentException("原native结果含非JSON对象，未用描述字符串替代");
    }
    private static Object copy(Object value,Budget budget,int depth)throws Exception {
        if(depth>7||++budget.nodes>1500||budget.remaining<8){budget.truncated=true;return JSONObject.NULL;}
        if(value instanceof JSONObject){JSONObject source=(JSONObject)value,out=new JSONObject();for(java.util.Iterator<String> keys=source.keys();keys.hasNext();){String key=keys.next();
            if(PRIVATE_KEYS.stream().anyMatch(p->p.equalsIgnoreCase(key)))continue;
            int cost=JSONObject.quote(key).getBytes(StandardCharsets.UTF_8).length+3;if(cost>budget.remaining){budget.truncated=true;break;}budget.remaining-=cost;out.put(key,copy(source.get(key),budget,depth+1));
        }return out;}
        if(value instanceof JSONArray){JSONArray source=(JSONArray)value,out=new JSONArray();for(int i=0;i<source.length();i++){if(i>=250||budget.remaining<16){budget.truncated=true;break;}out.put(copy(source.get(i),budget,depth+1));budget.remaining--;}return out;}
        if(value instanceof String){String source=(String)value;int max=Math.min(12000,source.length()),end=0,used=2;while(end<max){int point=source.codePointAt(end),chars=Character.charCount(point);if(end+chars>max)break;int cost=JSONObject.quote(new String(Character.toChars(point))).getBytes(StandardCharsets.UTF_8).length-2;if(used+cost>budget.remaining)break;used+=cost;end+=chars;}budget.remaining-=used;if(end<source.length())budget.truncated=true;return source.substring(0,end);}
        if(value==JSONObject.NULL||value instanceof Boolean||value instanceof Number){budget.remaining-=String.valueOf(value).length();return value;}
        budget.truncated=true;return JSONObject.NULL;
    }
    private static String clip(String text,int max){int end=Math.min(max,text.length());if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end);}
    static int bytes(JSONObject object){return object.toString().getBytes(StandardCharsets.UTF_8).length;}
    private static final class Budget{int remaining,nodes;boolean truncated;Budget(int bytes){remaining=bytes;}}
}
