package dev.pocketengine.features.remote;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.teams.ExternalMemberDriver;
import dev.pocketengine.features.teams.ExternalMemberFactory;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Receipt adapter for the existing Companion, not a second team/task coordinator. */
public final class CompanionExternalMemberFactory implements ExternalMemberFactory {
    public interface CommandPort {JSONObject call(String command,JSONObject input)throws Exception;}
    private final CommandPort remote;
    private final ExternalChildJournal journal;
    public CompanionExternalMemberFactory(RemoteModule remote,ExternalChildJournal journal){this(remote::handle,journal);}
    public CompanionExternalMemberFactory(CommandPort remote,ExternalChildJournal journal){this.remote=Objects.requireNonNull(remote);this.journal=Objects.requireNonNull(journal);}

    @Override public ExternalMemberDriver forSlot(JSONObject slot)throws Exception {
        return new Member(new JSONObject(slot.toString()));
    }

    private final class Member implements ExternalMemberDriver {
        private final JSONObject slot,binding;
        private JSONObject lastProbe;
        private long probeAt;
        Member(JSONObject slot)throws Exception {
            if(!slot.optString("runner").matches("codex_app_server|claude"))throw new UnsupportedOperationException("该团队成员没有已实现的原生协议适配器");
            if(slot.optString("runner").equals("claude")&&!Boolean.TRUE.equals(slot.opt("externalExecutionRiskAcknowledged")))throw new SecurityException("Claude成员尚未确认非OS沙箱与账号/费用边界");
            identifier(slot,"id");JSONObject device=slot.getJSONObject("device");
            binding=new JSONObject().put("profileId",identifier(device,"profileId")).put("environmentId",identifier(device,"environmentId"))
                    .put("worker",slot.getString("runner"));
            if(slot.getString("runner").equals("claude"))binding.put("teamReadOnlyTools",true).put("externalExecutionRiskAcknowledged",true);else binding.put("readOnly",true);
            if(!slot.optString("model").isEmpty())binding.put("model",text(slot,"model",200));
            this.slot=slot;
        }
        private void verify(JSONObject supplied)throws Exception {
            if(!slot.optString("id").equals(supplied.optString("id"))||!slot.optString("runner").equals(supplied.optString("runner")))throw new SecurityException("团队成员身份已改变");
            JSONObject device=supplied.getJSONObject("device");
            if(!binding.getString("profileId").equals(device.optString("profileId"))||!binding.getString("environmentId").equals(device.optString("environmentId"))
                    ||!slot.optString("model").equals(supplied.optString("model"))||slot.optBoolean("externalExecutionRiskAcknowledged")!=supplied.optBoolean("externalExecutionRiskAcknowledged"))throw new SecurityException("团队执行环境或模型已改变");
        }
        @Override public JSONObject probe(JSONObject supplied,RunControl control)throws Exception {
            verify(supplied);control.check();if(control.isPaused())throw new InterruptedException("团队成员已暂停");
            JSONObject directory=remote.call("environments",new JSONObject().put("profileId",binding.getString("profileId")));control.check();
            JSONObject selected=null;JSONArray rows=directory.getJSONArray("environments");for(int i=0;i<rows.length();i++)if(binding.getString("environmentId").equals(rows.getJSONObject(i).optString("id")))selected=rows.getJSONObject(i);
            if(selected==null||!selected.optString("bindingHash").matches("[a-f0-9]{64}"))throw new SecurityException("原电脑环境没有可核对的配置绑定，未启动Harness");
            binding.put("environmentBinding",selected.getString("bindingHash"));
            JSONObject result=remote.call("probeWorker",copy(binding));control.check();result.put("environmentBinding",selected.getString("bindingHash")).put("workspace",selected.optString("workspace")).put("executionKind",selected.optString("kind")).put("sessionPersistence","resume_original_settled_job").put("processReuse",false);lastProbe=copy(result);probeAt=System.nanoTime();return result;
        }
        @Override public JSONObject execute(JSONObject request,RunControl control,Consumer<JSONObject> events)throws Exception {
            verify(request.getJSONObject("slot"));
            if(!slot.getString("id").equals(request.optString("memberId")))throw new SecurityException("请求不属于绑定成员");
            text(request,"hostTaskId",80);text(request,"runId",80);identifier(request,"operationId");
            if(request.optInt("round")<1||request.optInt("generation")<0)throw new IllegalArgumentException("成员轮次或执行代次无效");
            control.check();if(control.isPaused())throw new InterruptedException("团队成员已暂停");
            long pauseRevision=control.pauseRevision();
            JSONObject link=journal.prepare(copy(request),copy(binding));
            String operation=identifier(link,"operationId");text(link,"taskId",80);
            if(!(link.opt("knownOperation") instanceof Boolean))throw new SecurityException("宿主缺少操作登记回执");
            if(!operation.equals(request.optString("operationId"))&&!operation.equals(request.optString("previousOperationId")))throw new SecurityException("宿主回执绑定了另一操作");
            JSONObject receipt=queryOperation(operation);
            if("not_found".equals(receipt.optString("status"))){
                if(link.getBoolean("knownOperation")||!request.optString("previousOperationId").isEmpty()){
                    receipt=unknown(operation,"原操作的接收回执尚未找到；没有重新提交，请核对电脑实际任务");
                }else{
                    if(stopped(control,pauseRevision))return save(link,cancelled(operation),events);
                    JSONObject capability;
                    try{capability=lastProbe!=null&&System.nanoTime()-probeAt<30_000_000_000L?copy(lastProbe):probe(slot,control);}
                    catch(InterruptedException stopped){return save(link,cancelled(operation),events);}
                    catch(IOException unavailable){return save(link,new JSONObject().put("operationId",operation).put("status","blocked").put("message","无法探测所选Harness能力；未提交任务"),events);}
                    JSONObject caps=capability.optJSONObject("capabilities");
                    boolean claude=binding.getString("worker").equals("claude");
                    if(!capability.optBoolean("available")||(claude?!capability.optBoolean("cliFlagsVerified"):!capability.optBoolean("protocolVerified")||!capability.optBoolean("accountConfigured")||!capability.optBoolean("configReadVerified")||caps==null||!caps.optBoolean("readOnlySandbox"))){
                        return save(link,new JSONObject().put("operationId",operation).put("status","blocked").put("message","所选Harness前置检查未通过；未启动任务"),events);
                    }
                    if(stopped(control,pauseRevision))return save(link,cancelled(operation),events);
                    JSONObject body=copy(binding).put("operationId",operation).put("prompt",text(request,"prompt",12000)).put("timeoutSeconds",300);
                    if(request.has("sessionSource")){JSONObject source=request.getJSONObject("sessionSource");body.put("threadId",text(source,"threadId",200)).put("resumeJobId",identifier(source,"jobId")).put("jobOperationId",identifier(source,"operationId")).put("serviceBase",text(source,"serviceBase",2048));}
                    try{receipt=remote.call("submitJob",body);}
                    catch(IOException uncertain){receipt=unknown(operation,"任务提交回执未确认；保留原操作编号，不会重复提交");}
                }
            }
            // Persist first. A journal failure must never be followed by another dispatch.
            if(request.has("sessionSource"))link.put("expectedThreadId",request.getJSONObject("sessionSource").getString("threadId"));
            JSONObject result=save(link,receipt,events);
            if(stopped(control,pauseRevision)&&cancellable(receipt))return stopAndReconcile(link,receipt,operation,events);
            long deadline=System.nanoTime()+305_000_000_000L;
            while(active(receipt.optString("status"))){
                if(stopped(control,pauseRevision))return stopAndReconcile(link,receipt,operation,events);
                if(System.nanoTime()>=deadline)return stopAndReconcile(link,receipt,operation,events);
                try{Thread.sleep(200);}catch(InterruptedException interrupted){
                    try{return stopAndReconcile(link,receipt,operation,events);}finally{Thread.currentThread().interrupt();}
                }
                try{receipt=remote.call("getJob",jobRequest(receipt));}
                catch(IOException uncertain){receipt=unknown(operation,"电脑任务状态无法读取；原任务可能继续执行，未重复提交").put("id",receipt.optString("id"));}
                result=save(link,receipt,events);
                if(stopped(control,pauseRevision)&&cancellable(receipt))return stopAndReconcile(link,receipt,operation,events);
            }
            return result;
        }
        private JSONObject queryOperation(String operation)throws Exception {
            try{return remote.call("reconcileOperation",copy(binding).put("operationId",operation));}
            catch(IOException unavailable){return unknown(operation,"无法核对原操作；未提交新任务");}
        }
        private JSONObject jobRequest(JSONObject receipt)throws Exception {
            JSONObject target=copy(binding).put("jobId",identifier(receipt,"id")).put("jobOperationId",identifier(receipt,"operationId"));if(receipt.has("serviceBase"))target.put("serviceBase",receipt.getString("serviceBase"));return target;
        }
        private JSONObject stopAndReconcile(JSONObject link,JSONObject receipt,String operation,Consumer<JSONObject> events)throws Exception {
            if(receipt.optString("id").isEmpty())return save(link,unknown(operation,"未取得电脑任务编号，无法确认停止；请按操作编号核对"),events);
            JSONObject target=jobRequest(receipt).put("jobOperationId",link.getString("operationId"));long deadline=System.nanoTime()+5_000_000_000L;
            try{
                receipt=remote.call("cancelJob",target);save(link,receipt,events);
                while(active(receipt.optString("status"))&&System.nanoTime()<deadline){Thread.sleep(100);receipt=remote.call("getJob",target);save(link,receipt,events);}
            }catch(IOException|InterruptedException uncertain){return save(link,unknown(operation,"已请求停止，最终结果尚未确认；已发生的操作不会撤销").put("id",target.getString("jobId")),events);}
            if(active(receipt.optString("status")))receipt=unknown(operation,"停止请求未取得最终回执；请查询原任务").put("id",target.getString("jobId"));
            return save(link,receipt,events);
        }
        private JSONObject save(JSONObject link,JSONObject receipt,Consumer<JSONObject> events)throws Exception {
            receipt=copy(receipt).put("profileId",binding.getString("profileId"));
            if(!receipt.has("operationId"))receipt.put("operationId",link.getString("operationId"));
            if(!link.getString("operationId").equals(receipt.optString("operationId")))throw new SecurityException("电脑回执属于另一操作");
            String status=receipt.optString("status","unknown"),content=receipt.optString("output","");
            boolean claude=binding.getString("worker").equals("claude");
            boolean confirmed=claude?"claude_stream_json".equals(receipt.optString("protocol"))&&receipt.optBoolean("protocolVerified")&&receipt.optBoolean("sessionIdentityConfirmed")&&receipt.optBoolean("teamReadOnlyTools")&&receipt.optBoolean("readOnly")&&receipt.optString("sessionId").equals(receipt.optString("threadId")):"codex_app_server".equals(receipt.optString("protocol"))&&receipt.optBoolean("readOnly")&&receipt.optBoolean("readOnlyConfirmed")&&receipt.optBoolean("externalToolsDisabled");
            if(link.has("expectedThreadId")&&!link.getString("expectedThreadId").equals(receipt.optString("threadId")))confirmed=false;
            if("completed".equals(status)&&!confirmed){status="unknown";receipt.put("status",status).put("message","电脑终态回执未证实本次只读协议与外部工具限制，需核对原任务");}
            if("completed".equals(status)&&content.trim().isEmpty()){status="failed";receipt.put("status",status).put("message","Harness终态没有返回有效成员文本，未标记成员完成");}
            journal.record(copy(link),copy(receipt));
            JSONObject external=copy(binding).put("childTaskId",link.getString("taskId")).put("operationId",link.getString("operationId"));
            for(String key:new String[]{"threadId","sessionId","promptRequestId","turnId","protocol","controls","capabilities","serviceBase","protocolVerified","teamReadOnlyTools","osReadIsolationVerified","processCleanupProof"})if(receipt.has(key))external.put(key,receipt.get(key));
            if(receipt.has("id"))external.put("jobId",receipt.get("id"));
            JSONObject result=new JSONObject().put("status",status).put("external",external).put("message",receipt.optString("message","已收到实际电脑回执"));
            if(!content.isEmpty())result.put("content",content.length()>12000?content.substring(0,12000):content).put("contentTruncated",content.length()>12000);
            if(status.matches("completed|failed|cancelled")&&confirmed&&!receipt.optString("threadId").isEmpty()&&!receipt.optString("serviceBase").isEmpty())result.put("sessionSource",copy(external));
            JSONArray questions=receipt.optJSONArray("pendingQuestions");if(questions!=null)result.put("pendingQuestions",new JSONArray(questions.toString()));
            JSONArray approvals=receipt.optJSONArray("pendingApprovals");if(approvals!=null)result.put("pendingApprovals",new JSONArray(approvals.toString()));
            if(events!=null)events.accept(copy(result));return result;
        }
        private JSONObject unknown(String operation,String message)throws Exception{return new JSONObject().put("operationId",operation).put("status","unknown").put("message",message);}
        private JSONObject cancelled(String operation)throws Exception{return new JSONObject().put("operationId",operation).put("status","cancelled").put("message","派发前已停止，未启动电脑任务");}
    }
    private static boolean active(String status){return status.equals("queued")||status.equals("running")||status.equals("stopping")||status.equals("awaiting_approval")||status.equals("awaiting_input");}
    private static boolean cancellable(JSONObject receipt){String status=receipt.optString("status");return active(status)||status.equals("unknown")&&!receipt.optString("id").isEmpty();}
    private static boolean stopped(RunControl control,long revision){return control.isCancelled()||control.isPaused()||control.pauseRevision()!=revision||Thread.currentThread().isInterrupted();}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static String identifier(JSONObject value,String key)throws Exception{String result=text(value,key,80);if(!result.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("无效编号："+key);return result;}
    private static String text(JSONObject value,String key,int maximum)throws Exception{Object raw=value.opt(key);if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>maximum||((String)raw).contains("\u0000"))throw new IllegalArgumentException("无效字段："+key);return (String)raw;}
}
