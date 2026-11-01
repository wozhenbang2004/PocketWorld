package dev.pocketengine.features.ssh;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** One native owner preview over the original SSH/SQL lifecycle; no runner or tunnel store. */
public final class SshPreviewWorkflows {
    private final SshModule ssh;private final TaskStore tasks;private final Executor worker;private final Consumer<String> changed;
    public SshPreviewWorkflows(SshModule ssh,TaskStore tasks,Executor worker,Consumer<String> changed){this.ssh=ssh;this.tasks=tasks;this.worker=worker;this.changed=changed;}
    private JSONObject profile(String id,String binding)throws Exception {
        JSONArray profiles=ssh.snapshot().getJSONArray("profiles");for(int i=0;i<profiles.length();i++){JSONObject row=profiles.getJSONObject(i);if(id.equals(row.optString("id"))){if(!binding.equals(row.optString("bindingHash"))||!row.optBoolean("hostKeyTrusted")||!row.optBoolean("credentialsConfigured"))throw new SecurityException("原SSH配置、信任或凭据代次已变化");return new JSONObject(row.toString());}}throw new SecurityException("原SSH连接不存在");
    }
    public Page newPage(String originalRemoteTask){return new Page(originalRemoteTask);}
    public static final class Review {
        private final Page page;private final long generation,expires;private final String scope,digest;
        private Review(Page page,long generation,JSONObject scope)throws Exception {this.page=page;this.generation=generation;this.scope=scope.toString();expires=System.currentTimeMillis()+60_000;digest=TaskStore.capabilityFingerprint(scope);}
        public JSONObject view()throws Exception{return new JSONObject(scope).put("digest",digest).put("expiresAt",expires);}
    }
    /** Private construction, with the actual owner page, original SQL row and same live control. */
    static final class Grant {
        private final Page page;private final long generation;final String task,run;final int epoch;final JSONObject input;final RunControl control;final SshNativeRequest exact;
        private Grant(Page page,long generation,JSONObject task,JSONObject input,RunControl control)throws Exception {this.page=page;this.generation=generation;this.task=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");this.input=new JSONObject(input.toString());this.control=control;exact=SshNativeRequest.capture("openPreviewForward",input);}
        void checkCurrent()throws Exception {
            synchronized(page){if(page.closed||!page.foreground||page.generation!=generation||page.control!=control)throw new SecurityException("原预览页面已离开、切后台或被替代");}
            page.owner().profile(input.getString("profileId"),input.getString("_profileBinding"));page.owner().tasks.requireSshPreviewRun(task,run,exact);
        }
    }
    public final class Page implements AutoCloseable {
        private final String remoteTask;private boolean closed,foreground;private long generation;private Review review;private RunControl control;private SshModule.PreviewLease lease;private String task="",run="";
        private Page(String task){remoteTask=task;}
        private SshPreviewWorkflows owner(){return SshPreviewWorkflows.this;}
        public synchronized void setForeground(boolean value){if(closed)return;if(foreground!=value){foreground=value;generation++;review=null;if(!value&&control!=null)control.cancel();}}
        public synchronized Review review(String profileId,String binding,int remotePort,int seconds)throws Exception {
            if(closed||!foreground||control!=null&&!control.isCancelled())throw new SecurityException("原生前台预览页未就绪或已有隧道");
            JSONObject sshProfile=profile(profileId,binding),source=tasks.remoteOwnerTarget(remoteTask);tasks.checkRemotePreviewSource(source);
            JSONObject input=new JSONObject().put("profileId",profileId).put("_profileBinding",binding).put("remotePort",remotePort).put("timeoutSeconds",seconds).put("acceptPreviewRisk",true).put("previewSource",source);
            input.put("previewSource",SshNativeRequest.capture("openPreviewForward",input).view().getJSONObject("previewSource"));
            JSONObject scope=new JSONObject().put("sshProfile",sshProfile).put("input",input).put("remoteHost","127.0.0.1").put("localBinding","127.0.0.1_random_port").put("projectAssociation","owner_reviewed_not_cryptographically_proven_by_cwd")
                    .put("pageScriptsAndSamePortWebSocket",true).put("otherOriginsAllowed",false).put("remoteServerStarted",false).put("osIsolationVerified",false).put("closingStopsRemoteJob",false);
            review=new Review(this,generation,scope);return review;
        }
        public synchronized String open(Review approved,String digest,boolean riskAccepted,Consumer<JSONObject> update)throws Exception {
            if(closed||!foreground||approved==null||approved.page!=this||review!=approved||approved.generation!=generation||System.currentTimeMillis()>approved.expires||!approved.digest.equals(digest)||!riskAccepted)throw new SecurityException("请在原生前台核对准确预览范围并明确确认");
            JSONObject scope=new JSONObject(approved.scope),input=scope.getJSONObject("input"),captured=profile(input.getString("profileId"),input.getString("_profileBinding"));tasks.checkRemotePreviewSource(input.getJSONObject("previewSource"));
            JSONObject admitted=tasks.beginSshPreviewTask(UUID.randomUUID().toString(),input,captured);task=admitted.getString("id");run=admitted.getString("runId");control=new RunControl();control.bindHostRun(task,run,admitted.getInt("coordinatorEpoch"));
            input.put("hostTaskId",task).put("_operationId",run).put("_hostRunId",run).put("_hostEpoch",admitted.getInt("coordinatorEpoch")).put("principalId","owner").put("spaceId","private");
            Grant grant=new Grant(this,generation,admitted,input,control);control.setAuthorityCheck(grant::checkCurrent);String originalTask=task,originalRun=run;
            control.onCancel(()->{try{tasks.cancelSshPreviewIfRunning(originalTask,originalRun);changed.accept(originalTask);}catch(Exception ignored){}});review=null;changed.accept(task);
            try{worker.execute(()->connect(grant,update));}catch(RuntimeException unavailable){control.cancel();tasks.updateExternal(task,new JSONObject().put("operationId",run).put("status","cancelled").put("accepted",false).put("forwardingEstablished",false).put("localResourcesClosed",true));changed.accept(task);throw unavailable;}
            return task;
        }
        private void connect(Grant grant,Consumer<JSONObject> update){
            try{
                grant.control.check();JSONObject actual=ssh.openOwnerPreview(grant);tasks.updateExternal(grant.task,actual);changed.accept(grant.task);
                synchronized(this){if(!closed&&foreground&&generation==grant.generation&&!grant.control.isCancelled()){lease=ssh.originalPreview(grant.task,grant.run);update.accept(new JSONObject(actual.toString()));}}
            }catch(Exception unavailable){
                try{grant.control.cancel();JSONObject actual=new JSONObject().put("operationId",grant.run).put("hostTaskId",grant.task).put("status","cancelled").put("accepted",false).put("forwardingEstablished",false).put("remoteServerStopped",false).put("message","原隧道未能保持；未重连、重启远端或重放输入");tasks.updateExternal(grant.task,actual);changed.accept(grant.task);synchronized(this){if(!closed&&foreground&&generation==grant.generation)update.accept(actual);}}catch(Exception ignored){}
            }
        }
        public synchronized SshModule.PreviewLease mount()throws Exception {if(closed||!foreground||lease==null)throw new SecurityException("尚无当前原生隧道");lease.requireCurrent();return lease;}
        public synchronized String taskId(){return task;}
        public synchronized void stop(){review=null;generation++;if(control!=null)control.cancel();lease=null;}
        @Override public synchronized void close(){if(closed)return;closed=true;foreground=false;stop();}
    }
}
