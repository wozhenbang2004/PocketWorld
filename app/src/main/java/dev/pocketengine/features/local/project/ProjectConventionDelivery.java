package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.RecoveryProjection;
import java.nio.charset.StandardCharsets;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Attestation of an existing, owner-submitted quote. No Book access, additional prompt text,
 * tool, model call, file or run authority. Serialized draft/answer metadata cannot restore a slot. */
public final class ProjectConventionDelivery {
    private ProjectConventionDelivery(){}
    private static final WeakHashMap<RunControl,Slot> SLOTS=new WeakHashMap<>();
    public static final class Picked {
        private final String project,checkout,client,textHash,originHash;private final long revision;
        private Picked(String p,String c,String q,String textHash,String originHash,long revision){project=p;checkout=c;client=q;this.textHash=textHash;this.originHash=originHash;this.revision=revision;}
    }
    /** Native project presentation calls only with its own previously sealed marker, not a Web argument. */
    public static Picked fromReviewedDraft(JSONObject retained,JSONObject input)throws Exception{
        if(retained==null)return null;String p=input.getString("projectId"),c=input.getString("checkoutId"),q=input.getString("clientMessageId"),text=input.getString("constraints");
        if(!p.equals(retained.getString("projectId"))||!c.equals(retained.getString("checkoutId"))||q.isBlank()||text.isBlank()||text.length()>4000||
            !hash(text).equals(retained.getString("textSha256"))||retained.getString("recordId").isBlank()||retained.getLong("revision")<1)throw new SecurityException("本次草稿与已挑选的手写约定不一致");
        return new Picked(p,c,q,hash(text),ProjectSources.digest(retained),retained.getLong("revision"));
    }
    private static String hash(String text)throws Exception{return ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private static final class Slot {
        final Picked source; final String task,run,contractHash; final int epoch;
        String prefixHash,frameHash,request,completed; int prefixChars,frameChars; boolean wire;
        Slot(Picked source,JSONObject t,String contract)throws Exception{
            this.source=source;task=t.getString("id");run=t.getString("runId");epoch=t.getInt("coordinatorEpoch");contractHash=contract;
        }
        boolean same(RunControl c,String task,String run,int epoch){
            return this.task.equals(task)&&this.run.equals(run)&&this.epoch==epoch&&!c.isCancelled()&&c.matchesHostRun(task,run,epoch);
        }
        void clearAttempt(){request=null;completed=null;frameHash=null;frameChars=0;wire=false;}
    }
    static synchronized void bind(Picked original,JSONObject context,JSONObject task,RunControl c)throws Exception{
        if(original==null)return;c.requireExecution();
        JSONObject contract=context.getJSONObject("deliveryContract");String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
        if(!c.hasHostRun()||c.isCancelled()||!c.matchesHostRun(id,run,epoch)||!original.project.equals(context.getString("projectId"))||!original.checkout.equals(context.getString("checkoutId"))||
           !original.client.equals(context.getString("clientMessageId"))||!original.textHash.equals(hash(contract.getString("constraints")))||SLOTS.containsKey(c))
            throw new SecurityException("已挑选约定不是这次原项目提交");
        SLOTS.put(c,new Slot(original,task,ProjectSources.digest(contract)));
    }
    /** Keep only lengths and digests of the pre-existing native prefix and projected row; never copy prompt text to an audit or slot. */
    static synchronized void prepared(RunControl c,String task,String run,int epoch,JSONObject project,String prefix){
        Slot s=SLOTS.get(c);if(s==null)return;s.prefixHash=null;s.prefixChars=0;s.clearAttempt();
        try{
            JSONObject contract=project.optJSONObject("deliveryContract"),guidance=project.optJSONObject("projectGuidance");
            if(s.same(c,task,run,epoch)&&s.source.project.equals(project.optString("projectId"))&&s.source.checkout.equals(project.optString("checkoutId"))&&
                contract!=null&&guidance!=null&&!guidance.optString("deliveryToken").isEmpty()&&s.contractHash.equals(ProjectSources.digest(contract))&&
                prefix!=null&&!prefix.isEmpty()&&prefix.length()<=120000){s.prefixHash=hash(prefix);s.prefixChars=prefix.length();}
        }catch(Exception unknown){/* Optional local evidence must never change the request. */}
    }
    private static boolean hashMatches(String content,String expected){try{return expected.equals(hash(content));}catch(Exception unknown){return false;}}
    static synchronized void proposed(RunControl c,String request,JSONArray actual){
        Slot s=SLOTS.get(c);if(s==null)return;s.clearAttempt();
        if(s.prefixHash==null||request==null||request.isBlank()||!s.same(c,s.task,s.run,s.epoch))return;
        for(int i=0;actual!=null&&i<actual.length();i++){
            JSONObject row=actual.optJSONObject(i);
            if(RecoveryProjection.referencePrefixMatches(row,s.prefixChars,text->hashMatches(text,s.prefixHash))){
                try{String body=row.getString("content");s.frameHash=hash(body);s.frameChars=body.length();s.request=request;}
                catch(Exception unknown){s.clearAttempt();}return;
            }
        }
    }
    private record Expected(String frameHash,int chars){}
    private static synchronized Expected expected(RunControl c,String call){
        Slot s=SLOTS.get(c);if(s==null||call==null||!call.equals(s.request)||!s.same(c,s.task,s.run,s.epoch)||s.frameHash==null)return null;
        s.wire=false;return new Expected(s.frameHash,s.frameChars);
    }
    private static synchronized void mark(RunControl c,String call,Expected e){
        Slot s=SLOTS.get(c);if(s!=null&&call.equals(s.request)&&e.frameHash.equals(s.frameHash)&&s.same(c,s.task,s.run,s.epoch))s.wire=true;
    }
    private static boolean exact(Object text,Expected e){return text instanceof String value&&value.length()==e.chars&&hashMatches(value,e.frameHash);}
    private static boolean hasUserRow(JSONObject wire,String array,String parts,Expected e){
        JSONArray rows=wire.optJSONArray(array);if(rows==null||rows.length()>512)return false;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null||!"user".equals(row.optString("role"))||
                ("input".equals(array)&&row.has("type")&&!"message".equals(row.optString("type"))))continue;
            Object content=row.opt(parts);if(exact(content,e))return true;
            if(!(content instanceof JSONArray blocks)||blocks.length()>64)continue;
            for(int j=0;j<blocks.length();j++){
                JSONObject part=blocks.optJSONObject(j);if(part==null)continue;
                String type=part.optString("type");boolean plain=type.equals("text")||type.equals("input_text")||
                    (!part.has("type")&&part.length()==1&&part.has("text"));
                if(plain&&exact(part.opt("text"),e))return true;
            }
        }
        return false;
    }
    /** Inspect only the final immutable entity of this physical call, after adapter/policy, never the original history.
     * Large/unknown envelopes fail closed without touching transport. No bytes, text or access token are retained. */
    public static void observeWire(RunControl c,String request,byte[] entity){
        Expected e=expected(c,request);if(e==null||entity==null||entity.length>512*1024)return;
        try{
            JSONObject wire=new JSONObject(new String(entity,StandardCharsets.UTF_8));
            if(hasUserRow(wire,"messages","content",e)||hasUserRow(wire,"input","content",e)||hasUserRow(wire,"contents","parts",e))mark(c,request,e);
        }catch(Exception unknown){/* Never substitute an earlier version or an unrecognized provider shape. */}
    }
    static synchronized void responded(RunControl c,String request){
        Slot s=SLOTS.get(c);if(s==null)return;
        boolean matched=s.wire&&request!=null&&request.equals(s.request)&&s.same(c,s.task,s.run,s.epoch);
        s.clearAttempt();s.prefixHash=null;s.prefixChars=0;if(matched)s.completed=request;
    }
    static synchronized void abandon(RunControl c,String request){
        Slot s=SLOTS.get(c);if(s!=null&&(request==null||request.isEmpty()||request.equals(s.request)||request.equals(s.completed))){
            if(request==null||request.isEmpty()){s.prefixHash=null;s.prefixChars=0;}s.clearAttempt();
        }
    }
    static synchronized void release(RunControl original){SLOTS.remove(original);}
    /** Only the actual final native OwnerReply consumes a completed physical request; its local receipt has no source ID or body. */
    public static synchronized JSONObject forReply(RunControl c,String task,String request,String reply,String answer)throws Exception{
        Slot s=SLOTS.get(c);if(s==null||request==null||!request.equals(s.completed)||reply==null||reply.isEmpty()||answer==null||answer.isBlank()||!s.same(c,task,s.run,s.epoch))return null;
        s.completed=null;return new JSONObject().put("format","project_owner_quote_response.v1").put("taskId",s.task).put("runId",s.run).put("epoch",s.epoch)
          .put("projectId",s.source.project).put("checkoutId",s.source.checkout).put("clientMessageId",s.source.client).put("callId",request).put("replyId",reply)
          .put("sourceRevision",s.source.revision).put("sourceFingerprint",s.source.originHash).put("contractFingerprint",s.contractHash)
          .put("answerFingerprint",hash(answer)).put("modelUseVerified",false);
    }
}
