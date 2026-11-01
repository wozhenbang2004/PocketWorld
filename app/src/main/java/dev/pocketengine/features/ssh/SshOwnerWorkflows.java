package dev.pocketengine.features.ssh;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native private owner workflows, composed with the existing task store and executor. */
public final class SshOwnerWorkflows {
    private final SshModule ssh;private final TaskStore tasks;private final AssistantModule assistant;private final Executor worker;private final Consumer<String> changed;
    private final ThreadLocal<JSONObject> selected=new ThreadLocal<>();
    public SshOwnerWorkflows(SshModule ssh,TaskStore tasks,AssistantModule assistant,Executor worker,Consumer<String> changed){this.ssh=java.util.Objects.requireNonNull(ssh);this.tasks=java.util.Objects.requireNonNull(tasks);this.assistant=java.util.Objects.requireNonNull(assistant);this.worker=java.util.Objects.requireNonNull(worker);this.changed=java.util.Objects.requireNonNull(changed);}
    private JSONObject profile(String id,String binding)throws Exception {
        JSONArray profiles=ssh.snapshot().getJSONArray("profiles");for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);if(id.equals(p.optString("id"))){if(!p.getString("bindingHash").equals(binding)||!p.optBoolean("hostKeyTrusted")||!p.optBoolean("credentialsConfigured"))throw new SecurityException("连接配置、凭据或信任已变化，请重新核对");return new JSONObject(p.toString());}}throw new SecurityException("SSH连接已移除");
    }
    public JSONObject startTask(JSONObject input)throws Exception {
        String id=input.getString("profileId"),binding=input.getString("bindingHash"),client=input.getString("clientMessageId"),goal=input.getString("goal").trim();
        if(!client.matches("[a-fA-F0-9-]{36}")||goal.isBlank()||goal.length()>12000)throw new IllegalArgumentException("请填写本次SSH任务目标");
        JSONObject prior=null;try{prior=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",client));}catch(IllegalArgumentException absent){}
        if(prior!=null){verifyTask(prior,id,binding);return prior;}
        JSONObject p=profile(id,binding);if(selected.get()!=null)throw new SecurityException("不能嵌套开始SSH任务");selected.set(p);
        try{JSONObject ack=assistant.handle("send",new JSONObject().put("clientMessageId",client).put("message",goal).put("mode","queue"));if(ack.has("taskId"))verifyTask(ack,id,binding);return ack;}finally{selected.remove();}
    }
    public void bindNewAttempt(JSONObject task,RunControl original)throws Exception {JSONObject p=selected.get();if(p!=null)tasks.bindSshSelection(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,p);}
    private void verifyTask(JSONObject ack,String id,String binding)throws Exception {if(!ack.has("taskId"))return;JSONObject task=tasks.get(ack.getString("taskId"),false),p=task==null?null:task.optJSONObject("sshSelection");if(p==null||!id.equals(p.optString("id"))||!binding.equals(p.optString("bindingHash")))throw new SecurityException("原输入回执属于另一已审阅连接");}
    public DoctorPage newDoctorPage(){return new DoctorPage();}
    public final class DoctorPage implements AutoCloseable {
        private boolean closed,running;private RunControl control;private String taskId="";private long generation;
        public synchronized boolean running(){return running;}
        public synchronized void start(String profileId,String capturedBinding,Consumer<JSONObject> privateResult)throws Exception {
            if(closed||running)throw new IllegalStateException("当前私人诊断页已关闭或仍在诊断");JSONObject captured=profile(profileId,capturedBinding);long token=++generation;
            RunControl original=new RunControl();control=original;running=true;
            try{worker.execute(()->diagnose(captured,token,original,privateResult));}catch(RuntimeException unavailable){running=false;control=null;throw unavailable;}
        }
        private void diagnose(JSONObject captured,long token,RunControl original,Consumer<JSONObject> privateResult){String id=null;AutoCloseable authority=null;
            try{
                original.check();String operation=UUID.randomUUID().toString();JSONObject input=new JSONObject().put("profileId",captured.getString("id")).put("_profileBinding",captured.getString("bindingHash"));
                JSONObject task=tasks.beginSshDoctorTask(operation,input,captured);id=task.getString("id");final String admitted=id,run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);
                synchronized(this){taskId=id;}original.onCancel(()->{try{tasks.cancelSshDoctorIfRunning(admitted,run);}catch(Exception ignored){}});
                authority=original.retainAuthorityCheck(()->{JSONObject current=tasks.requireExternalRun(admitted,run,"ssh",false);if(current.optBoolean("cancelRequested")||!current.optString("status").matches("queued|running")||epoch!=current.optInt("coordinatorEpoch"))throw new SecurityException("原诊断任务已停止");});
                input.put("hostTaskId",id).put("_operationId",run).put("_hostRunId",run).put("_hostEpoch",epoch).put("principalId","owner").put("spaceId","private");
                changed.accept(id);JSONObject result=ssh.invokeForHost("doctor",input,original);JSONObject metadata=SshPrivacy.doctorMetadata(result);tasks.updateExternal(id,metadata);changed.accept(id);
                synchronized(this){if(!closed&&token==generation&&!original.isCancelled())privateResult.accept(new JSONObject(result.toString()));}
            }catch(Exception failed){
                try{JSONObject metadata=new JSONObject().put("status",original.isCancelled()?"cancelled":"failed").put("accepted",false).put("verified",false).put("readOnly",true).put("persistReport",false);if(id!=null){tasks.updateExternal(id,SshPrivacy.doctorMetadata(metadata));changed.accept(id);}synchronized(this){if(!closed&&token==generation)privateResult.accept(metadata.put("message","诊断未取得完整结果，请核对主机身份、连接和原任务；没有自动重试"));}}catch(Exception ignored){}
            }finally{if(authority!=null)try{authority.close();}catch(Exception ignored){}synchronized(this){if(token==generation){running=false;control=null;}}}
        }
        public synchronized String taskId(){return taskId;}
        public synchronized void cancel(){if(control!=null)control.cancel();}
        @Override public synchronized void close(){if(closed)return;closed=true;++generation;if(control!=null)control.cancel();control=null;}
    }
}
