package dev.pocketengine.features.ssh;

import android.util.AtomicFile;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private-owner native SSH. TaskStore owns durable tasks; this module stores profiles and transport receipts. */
public final class SshModule implements EngineModule {
    private final FeatureContext context;
    private final AtomicFile storage;
    private final Map<String,SshProfile> profiles=new java.util.LinkedHashMap<>();
    private final Map<String,Probe> probes=new ConcurrentHashMap<>();
    private final Map<String,Operation> active=new ConcurrentHashMap<>();
    private final Map<String,String> accounts=new ConcurrentHashMap<>();
    private final ScheduledExecutorService lifecycle=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private volatile SshFileArtifacts fileArtifacts;
    public interface NativeExecutionAuthority {void check(String originalTaskId,String originalRunId,SshNativeRequest request)throws Exception;}
    private volatile NativeExecutionAuthority nativeExecutionAuthority;
    public void setNativeExecutionAuthority(NativeExecutionAuthority nativeAuthority){nativeExecutionAuthority=java.util.Objects.requireNonNull(nativeAuthority);}

    public SshModule(FeatureContext context)throws Exception {
        this(context,new AtomicFile(new File(context.android.getFilesDir(),"ssh-profiles.json")));
    }
    /** Package-private storage seam for actual write/commit failure regression checks. */
    SshModule(FeatureContext context,AtomicFile storage)throws Exception {
        this.context=context;this.storage=storage;
        JSONArray saved;
        try{saved=new JSONArray(new String(storage.readFully(),StandardCharsets.UTF_8));}
        catch(java.io.FileNotFoundException absent){saved=new JSONArray();}
        catch(Exception corrupt){throw new IllegalStateException("SSH连接配置无法读取，原文件已保留",corrupt);}
        for(int i=0;i<saved.length();i++){SshProfile profile=new SshProfile(saved.getJSONObject(i));profiles.put(profile.id,profile);}
        lifecycle.scheduleWithFixedDelay(this::reap,1,1,TimeUnit.SECONDS);
    }
    /** Native Application-only mount. Generic feature/model arguments cannot manufacture file authority. */
    public void setOwnerAttachments(dev.pocketengine.features.attachments.AttachmentsModule ownerFiles){fileArtifacts=new SshFileArtifacts(dev.pocketengine.features.attachments.AttachmentRepository.get(context.android).book,java.util.Objects.requireNonNull(ownerFiles));}
    @Override public String id(){return "ssh";}
    @Override public synchronized JSONObject snapshot()throws Exception {
        JSONArray saved=new JSONArray(),terminals=new JSONArray(),operations=new JSONArray(),previews=new JSONArray();for(SshProfile profile:profiles.values())saved.put(publicProfile(profile));
        for(Operation operation:active.values())if(operation.terminal!=null)terminals.put(metadata(operation));
        for(Operation operation:active.values())if(!operation.finished.get())operations.put(metadata(operation));
        for(Operation operation:active.values())if(operation.forward!=null&&!operation.finished.get())previews.put(metadata(operation));
        return new JSONObject().put("profiles",saved).put("previews",previews).put("terminals",terminals).put("operations",operations).put("directSsh",true).put("sftp",true).put("environmentDoctor",true)
                .put("ptyImplemented",true).put("deviceVerified",false).put("remotePersistentJobs",false).put("posixShellOnly",true)
                .put("strongAtomicCas",false).put("binaryFileTransfers",fileArtifacts!=null).put("maxBinaryTransferBytes",DirectSshClient.MAX_TRANSFER).put("binaryBodiesInJson",false).put("automaticTransferResume",false).put("ownerOnly",true).put("library","com.github.mwiede:jsch:2.28.8");
    }
    /** Private native WorkProfile source only: reads no credential bytes and makes no SSH connection. */
    public synchronized JSONObject workProfileTargetFacts(JSONObject reference,RunControl observation)throws Exception {
        java.util.Objects.requireNonNull(observation).check();if(closed)throw new SecurityException("原SSH模块已关闭");
        if(reference==null)throw new SecurityException("缺少原SSH目标引用");
        for(var keys=reference.keys();keys.hasNext();)if(!java.util.Set.of("kind","profileId","bindingHash","optional").contains(keys.next()))throw new SecurityException("SSH目标不能携带命令、凭据、路径或执行许可");
        if(!"ssh".equals(reference.optString("kind"))||!(reference.opt("bindingHash") instanceof String)||!reference.getString("bindingHash").matches("[a-f0-9]{64}"))throw new SecurityException("SSH目标缺少原绑定版本");
        if(reference.has("optional")&&!(reference.opt("optional") instanceof Boolean))throw new SecurityException("目标optional必须为布尔值");
        SshProfile selected=profile(SshProfile.text(reference,"profileId",80));selected.requireBinding(reference.getString("bindingHash"));
        boolean trusted=!selected.pinnedKey.isEmpty(),configured=context.secrets.contains(selected.credentialSlot()),supported=trusted&&configured;observation.check();
        JSONObject evidence=new JSONObject().put("sourceState",supported?"source_ready":"unavailable").put("reasonCode",!trusted?"host_key_untrusted":!configured?"credentials_unconfigured":"original_ssh_binding_available")
                .put("hostKeyTrusted",trusted).put("credentialsConfigured",configured).put("connectionVerified",false).put("configurationApplied",false).put("runtimeVerified",false);
        return new JSONObject().put("kind","ssh").put("profileId",selected.id).put("bindingHash",selected.bindingHash()).put("supported",supported).put("sourceEvidence",evidence);
    }
    public synchronized void checkWorkProfileTargetCurrent(JSONObject reference,JSONObject capturedPublicBinding,RunControl observation)throws Exception {
        java.util.Objects.requireNonNull(capturedPublicBinding);
        JSONObject current=workProfileTargetFacts(reference,observation);
        for(String key:new String[]{"kind","profileId","bindingHash"})if(!current.get(key).equals(capturedPublicBinding.opt(key)))throw new SecurityException("工作Profile不是原冻结SSH目标");
        if(!current.getBoolean("supported"))throw new SecurityException("原SSH主机信任或凭据配置已撤回");observation.check();
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        if(closed)throw new IllegalStateException("SSH模块已关闭");if(input==null)input=new JSONObject();
        switch(command){
            case "saveProfile":return saveProfile(input);
            case "removeProfile":return removeProfile(input);
            case "probeHostKey":return probe(input);
            case "approveHostKey":return approve(input);
            case "forgetHostKey":return forget(input);
            case "exec":case "listFiles":case "readFile":case "writeFile":case "inspectFile":case "downloadFile":case "uploadFile":case "openPty":case "doctor":return execute(command,input,new RunControl(),"",0);
            case "cancel":return cancel(input);
            case "getOperation":{Operation operation=active.get(text(input,"operationId",80));return operation==null?DirectSshClient.receipt("not_active").put("verified",false).put("message","本进程没有该执行控制；已有TaskStore回执保持不变，没有重连或重放"):metadata(operation);}
            case "readPty":case "writePty":case "resizePty":case "closePty":return terminal(command,input);
            default:throw new IllegalArgumentException("未知SSH操作");
        }
    }
    /** Not in handle()/model capabilities. Only the private native page can construct Grant. */
    JSONObject openOwnerPreview(SshPreviewWorkflows.Grant grant)throws Exception {
        grant.checkCurrent();if(!grant.control.matchesHostRun(grant.task,grant.run,grant.epoch))throw new SecurityException("原预览控制身份不符");
        return execute("openPreviewForward",grant.input,grant.control,grant.run,grant.epoch);
    }
    public final class PreviewLease implements dev.pocketengine.features.local.preview.OwnedLoopbackPreview {
        private final Operation operation;private final String identity;
        private PreviewLease(Operation operation)throws Exception{this.operation=operation;identity=SshProfile.hash(("pocketworld/ssh-preview/v1\n"+operation.taskId+"\n"+operation.runId+"\n"+operation.profileId+"\n"+operation.forward.localPort+"\n"+operation.forward.remotePort).getBytes(StandardCharsets.UTF_8));}
        public String identity(){return identity;}
        public String baseUrl(){return "http://127.0.0.1:"+operation.forward.localPort+"/";}
        public void requireCurrent()throws Exception{if(closed||active.get(operation.id)!=operation||operation.finished.get()||operation.forward==null)throw new SecurityException("原隧道身份已失效");operation.forward.requireCurrent();}
    }
    public PreviewLease originalPreview(String taskId,String runId)throws Exception {
        Operation operation=active.get(runId);if(operation==null||!taskId.equals(operation.taskId)||!runId.equals(operation.runId)||operation.forward==null)throw new SecurityException("没有此原任务的实际隧道");PreviewLease lease=new PreviewLease(operation);lease.requireCurrent();return lease;
    }
    /** Native owner entry points only. Models/channels must not receive these configuration capabilities. */
    public static boolean configurationCommand(String command){return command.matches("saveProfile|removeProfile|probeHostKey|approveHostKey|forgetHostKey");}
    public static boolean taskCommand(String command){return command!=null&&command.matches("exec|listFiles|readFile|writeFile|inspectFile|downloadFile|uploadFile|openPty|doctor");}

    /**
     * Native supervisor adapter only. The original control already retains live authority.
     * Run identity fields must be injected from the host binding, never model arguments.
     * PTY/configuration remain native-owner workflows and cannot enter through this port.
     */
    public JSONObject invokeForHost(String command,JSONObject input,RunControl sameLiveControl)throws Exception {
        if(command==null||!command.matches("exec|listFiles|readFile|writeFile|doctor"))throw new SecurityException("该SSH命令仅限原生主人入口");
        if(input==null||sameLiveControl==null)throw new SecurityException("SSH需要原宿主执行控制");
        sameLiveControl.check();if(sameLiveControl.isPaused())throw new SecurityException("宿主任务已暂停");
        String task=text(input,"hostTaskId",80),run=text(input,"_hostRunId",80);Object rawEpoch=input.opt("_hostEpoch");
        if(!(rawEpoch instanceof Number)||((Number)rawEpoch).doubleValue()!=((Number)rawEpoch).intValue()||((Number)rawEpoch).intValue()<1)throw new SecurityException("SSH需要原宿主执行代次");
        int epoch=((Number)rawEpoch).intValue();
        if(!sameLiveControl.hasHostRun()||!sameLiveControl.matchesHostRun(task,run,epoch))throw new SecurityException("SSH控制不属于原宿主任务与执行");
        return execute(command,new JSONObject(input.toString()),sameLiveControl,run,epoch);
    }

    /** Native private Settings workflow only; never exposed by EngineModule.handle. */
    public static final class ConnectionBackupReview {
        private final SshModule issuer;private final String section,action;private final dev.pocketengine.core.RunControl original;private final long expires;private ConnectionBackupAdmission admission;
        private ConnectionBackupReview(SshModule issuer,JSONObject section,String action,dev.pocketengine.core.RunControl original){this.issuer=issuer;this.section=section.toString();this.action=action;this.original=original;expires=System.currentTimeMillis()+600_000L;}
        public JSONObject metadata()throws Exception {current();return dev.pocketengine.features.ConnectionBackupSections.reviewMetadata("ssh",action,new JSONObject(section));}
        private void current()throws Exception {original.check();if(issuer.closed||System.currentTimeMillis()>expires)throw new SecurityException("原私人设置恢复审阅已关闭或到期");}
        public synchronized ConnectionBackupAdmission confirm()throws Exception {current();if(admission==null)admission=new ConnectionBackupAdmission(this);return admission;}
    }
    public static final class ConnectionBackupAdmission {
        private final ConnectionBackupReview review;private boolean attempted;
        private ConnectionBackupAdmission(ConnectionBackupReview review){this.review=review;}
        public JSONObject metadata()throws Exception{return review.metadata();}
    }
    public JSONObject exportConnectionBackupForOwner(dev.pocketengine.core.RunControl original)throws Exception {
        java.util.Objects.requireNonNull(original).check();JSONArray rows; synchronized(this){if(closed)throw new SecurityException("原连接模块已关闭");rows=connectionBackupRows();}original.check();return dev.pocketengine.features.ConnectionBackupSections.section("ssh",rows);
    }
    public ConnectionBackupReview reviewConnectionBackupForOwner(JSONObject exactSection,String ownerActionId,dev.pocketengine.core.RunControl original)throws Exception {
        java.util.Objects.requireNonNull(original).check();if(closed)throw new SecurityException("原连接模块已关闭");JSONObject section=dev.pocketengine.features.ConnectionBackupSections.checked("ssh",exactSection);ConnectionBackupReview review=new ConnectionBackupReview(this,section,dev.pocketengine.features.ConnectionBackupSections.action(ownerActionId),original);review.current();return review;
    }
    /** Cold unknown recovery: original action/digest/id lookup only, never mint an admission or write. */
    public JSONObject queryConnectionBackupForOwner(JSONObject exactSection,String originalOwnerActionId,dev.pocketengine.core.RunControl original)throws Exception {
        java.util.Objects.requireNonNull(original).check();JSONObject section=dev.pocketengine.features.ConnectionBackupSections.checked("ssh",exactSection);String action=dev.pocketengine.features.ConnectionBackupSections.action(originalOwnerActionId),digest=dev.pocketengine.features.ConnectionBackupSections.digest(section);JSONArray rows=section.getJSONArray("profiles"),results=new JSONArray();
        for(int n=0;n<rows.length();n++){original.check();JSONObject row=rows.getJSONObject(n);String id=dev.pocketengine.features.ConnectionBackupSections.restoredId("ssh",action,digest,row.getString("sourceProfileId"));String actual="unconfirmed";try{synchronized(this){if(closed)throw new SecurityException("原连接模块已关闭");original.check();actual=restoreConnectionBackupRow(row,id,action,digest,true);}}catch(Exception unconfirmed){original.check();}results.put(new JSONObject().put("originalProfileId",row.getString("sourceProfileId")).put("newProfileId",id).put("state",actual.equals("already_restored")?"found":"unknown").put("observation",actual));}
        original.check();return new JSONObject().put("module","ssh").put("ownerActionId",action).put("sectionDigest",digest).put("profiles",results).put("queryOnly",true).put("configurationWritten",false).put("automaticConnection",false);
    }
    public JSONObject restoreConnectionBackupForOwner(ConnectionBackupAdmission admission,dev.pocketengine.core.RunControl original)throws Exception {
        if(admission==null||admission.review.issuer!=this||admission.review.original!=original)throw new SecurityException("没有此模块/原私人设置操作的native恢复批准");ConnectionBackupReview review=admission.review;review.current();boolean queryOnly;synchronized(admission){queryOnly=admission.attempted;admission.attempted=true;}
        JSONObject section=new JSONObject(review.section);String digest=dev.pocketengine.features.ConnectionBackupSections.digest(section);JSONArray rows=section.getJSONArray("profiles"),results=new JSONArray();boolean stopped=false;
        for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);String old=row.getString("sourceProfileId"),id=dev.pocketengine.features.ConnectionBackupSections.restoredId("ssh",review.action,digest,old);JSONObject receipt=new JSONObject().put("originalProfileId",old).put("newProfileId",id);
            if(stopped){results.put(receipt.put("state","not_attempted"));continue;}
            review.current();try{synchronized(this){review.current();receipt.put("state",restoreConnectionBackupRow(row,id,review.action,digest,queryOnly));}}catch(Exception uncertain){receipt.put("state","unknown").put("reason","原配置提交未确认；仅核对这次固定编号，不重建或自动连接");stopped=true;}results.put(receipt);
        }
        return new JSONObject().put("format","pocketworld.native-connection-restore-receipt.v1").put("module","ssh").put("ownerActionId",review.action).put("sectionDigest",digest).put("queryOnly",queryOnly).put("profiles",results).put("credentialsRestored",false).put("hostKeyTrustRestored",false).put("executionGrantsRestored",false).put("automaticConnection",false);
    }

    private JSONArray connectionBackupRows()throws Exception {
        JSONArray rows=new JSONArray();for(SshProfile profile:profiles.values())rows.put(backupConfiguration(profile).put("sourceProfileId",profile.id).put("previousHostFingerprint",profile.pinnedKey.isEmpty()?"":SshProfile.fingerprint(Base64.getDecoder().decode(profile.pinnedKey))));return rows;
    }
    private JSONObject backupConfiguration(SshProfile profile)throws Exception {JSONObject value=profile.document();for(String key:new String[]{"pinnedKey","configRevision","credentialRevision","nativeBackupOrigin","id"})value.remove(key);return value;}
    private String restoreConnectionBackupRow(JSONObject row,String id,String action,String sectionDigest,boolean queryOnly)throws Exception {
        JSONObject config=new JSONObject(row.toString()).put("id",id);config.remove("sourceProfileId");config.remove("previousHostFingerprint");SshProfile desired=new SshProfile(config);JSONObject origin=dev.pocketengine.features.ConnectionBackupSections.origin(action,sectionDigest,row.getString("sourceProfileId"),dev.pocketengine.features.ConnectionBackupSections.digest(backupConfiguration(desired)));
        // Read the same original file before any new row. An uncertain/invalid file never becomes empty.
        JSONArray actual;try{actual=new JSONArray(new String(storage.readFully(),StandardCharsets.UTF_8));}catch(java.io.FileNotFoundException missing){if(storage.getBaseFile().exists()||!profiles.isEmpty())throw missing;actual=new JSONArray();}
        Map<String,SshProfile> durable=new java.util.LinkedHashMap<>();for(int n=0;n<actual.length();n++){SshProfile found=new SshProfile(actual.getJSONObject(n));durable.put(found.id,found);}SshProfile known=durable.get(id);
        if(known!=null){JSONObject previous=known.document().optJSONObject("nativeBackupOrigin");if(previous==null||!dev.pocketengine.features.ConnectionBackupSections.digest(origin).equals(dev.pocketengine.features.ConnectionBackupSections.digest(previous))||!dev.pocketengine.features.ConnectionBackupSections.digest(backupConfiguration(desired)).equals(dev.pocketengine.features.ConnectionBackupSections.digest(backupConfiguration(known))))return "configuration_changed_not_overwritten";if(!queryOnly){profiles.clear();profiles.putAll(durable);}return "already_restored";}
        if(queryOnly)return "not_confirmed_query_only";if(durable.size()>=20)throw new IllegalStateException("原SSH连接已满");config.put("pinnedKey","").put("configRevision",UUID.randomUUID().toString()).put("credentialRevision",UUID.randomUUID().toString()).put("nativeBackupOrigin",origin);SshProfile restored=new SshProfile(config);durable.put(id,restored);commitProfiles(durable);return "restored_needs_credentials_and_host_verification";
    }

    private synchronized JSONObject saveProfile(JSONObject input)throws Exception {
        JSONObject value=new JSONObject();for(String field:new String[]{"id","name","host","port","username","workspace","authType"})if(input.has(field))value.put(field,input.get(field));
        if(value.optString("id").isEmpty())value.put("id",UUID.randomUUID().toString());
        SshProfile next=new SshProfile(value);SshProfile old=profiles.get(next.id);checkEditorBinding(input,old);if(old!=null)notBusy(old);
        if(old==null&&profiles.size()>=20)throw new IllegalStateException("最多保存20个SSH连接");
        boolean same=old!=null&&old.binding().equals(next.binding());if(same)value.put("pinnedKey",old.pinnedKey);
        value.put("configRevision",UUID.randomUUID().toString()).put("credentialRevision",UUID.randomUUID().toString());next=new SshProfile(value);
        JSONObject stored=same&&old.authType.equals(next.authType)&&context.secrets.contains(old.credentialSlot())?new JSONObject(context.secrets.get(old.credentialSlot())):new JSONObject();
        JSONObject secret=SshCredentials.merge(old,next,input,stored);
        // A fresh, non-secret revision selects a fresh vault slot. A failed config save
        // must never leave an old approved binding pointing at replacement credentials.
        try{
            if(secret.length()>0)context.secrets.put(next.credentialSlot(),secret.toString());
            Map<String,SshProfile> proposed=new java.util.LinkedHashMap<>(profiles);proposed.put(next.id,next);commitProfiles(proposed);
        }catch(Exception failed){try{context.secrets.remove(next.credentialSlot());}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
        boolean cleanupPending=old!=null&&!old.credentialSlot().equals(next.credentialSlot())&&!removeCredential(old.credentialSlot());
        final String id=next.id;probes.entrySet().removeIf(entry->entry.getValue().profileId.equals(id));JSONObject result=publicProfile(next);if(cleanupPending)result.put("credentialCleanupPending",true);return result;
    }
    private synchronized JSONObject removeProfile(JSONObject input)throws Exception {
        String id=text(input,"profileId",80);SshProfile old=profile(id);checkEditorBinding(input,old);notBusy(old);Map<String,SshProfile> proposed=new java.util.LinkedHashMap<>(profiles);proposed.remove(id);commitProfiles(proposed);boolean cleanupPending=!removeCredential(old.credentialSlot());probes.entrySet().removeIf(entry->entry.getValue().profileId.equals(id));return new JSONObject().put("removed",true).put("credentialCleanupPending",cleanupPending);
    }
    private void checkEditorBinding(JSONObject input,SshProfile old)throws Exception {
        if(!input.has("expectedBindingHash"))return;
        Object expected=input.get("expectedBindingHash");
        if(!(expected instanceof String)||!(old==null?"":old.bindingHash()).equals(expected))throw new SecurityException("SSH连接已在其他页面修改或移除，请重新打开当前连接；没有覆盖新配置");
    }
    private JSONObject probe(JSONObject input)throws Exception {
        String id=text(input,"profileId",80);SshProfile selected; synchronized(this){selected=profile(id);if(input.has("_profileBinding"))selected.requireBinding(text(input,"_profileBinding",64));}
        JSONObject found=DirectSshClient.probe(selected);String probeId=UUID.randomUUID().toString();
        synchronized(this){profile(id).requireBinding(selected.bindingHash());}
        Probe probe=new Probe(id,selected.bindingHash(),found.getString("key"),found.getString("fingerprint"),System.currentTimeMillis()+300_000);
        if(probes.size()>=40)probes.entrySet().removeIf(entry->entry.getValue().expiresAt<System.currentTimeMillis());
        if(probes.size()>=40)throw new IllegalStateException("待核对指纹过多，请先处理已有请求");probes.put(probeId,probe);
        found.remove("key");return found.put("probeId",probeId).put("profileId",id).put("bindingHash",selected.bindingHash()).put("expiresAt",probe.expiresAt).put("hostKeyTrusted",!selected.pinnedKey.isEmpty()&&!found.optBoolean("changed"));
    }
    private synchronized JSONObject approve(JSONObject input)throws Exception {
        SshProfile selected=profile(text(input,"profileId",80));checkEditorBinding(input,selected);notBusy(selected);String id=text(input,"probeId",80);Probe probe=probes.get(id);
        if(probe==null||probe.expiresAt<=System.currentTimeMillis()||!probe.profileId.equals(selected.id)||!probe.binding.equals(selected.bindingHash())||!probe.fingerprint.equals(text(input,"fingerprint",100)))
            throw new SecurityException("指纹核对请求已过期或不属于该主机与工作区，请重新探测");
        if(!selected.pinnedKey.isEmpty()&&!selected.pinnedKey.equals(probe.key))throw new SecurityException("已保存的主机密钥发生变化，先独立核对并显式清除旧指纹，不能直接批准覆盖");
        JSONObject changed=selected.document().put("pinnedKey",probe.key).put("configRevision",UUID.randomUUID().toString());SshProfile next=new SshProfile(changed);Map<String,SshProfile> proposed=new java.util.LinkedHashMap<>(profiles);proposed.put(next.id,next);commitProfiles(proposed);probes.remove(id);return publicProfile(next);
    }
    private synchronized JSONObject forget(JSONObject input)throws Exception {
        SshProfile selected=profile(text(input,"profileId",80));checkEditorBinding(input,selected);notBusy(selected);
        if(selected.pinnedKey.isEmpty()||!SshProfile.fingerprint(Base64.getDecoder().decode(selected.pinnedKey)).equals(text(input,"fingerprint",100)))throw new SecurityException("请明确核对要移除的当前主机指纹");
        SshProfile next=new SshProfile(selected.document().put("pinnedKey","").put("configRevision",UUID.randomUUID().toString()));Map<String,SshProfile> proposed=new java.util.LinkedHashMap<>(profiles);proposed.put(next.id,next);commitProfiles(proposed);return publicProfile(next);
    }

    private JSONObject execute(String command,JSONObject input,RunControl control,String hostRun,int epoch)throws Exception {
        input=new JSONObject(input.toString());control.check();if(control.isPaused())throw new SecurityException("宿主任务已暂停");
        String task=text(input,"hostTaskId",80),id=text(input,"_operationId",80);
        if(SshNativeRequest.guarded(command)&&hostRun.isEmpty()){
            NativeExecutionAuthority captured=nativeExecutionAuthority;if(captured==null)throw new SecurityException("原SSH任务SQL实时准入尚未挂载，未连接或派发");
            SshNativeRequest exact=SshNativeRequest.capture(command,input);captured.check(task,id,exact);control.bindTask(task);control.setAuthorityCheck(()->{if(closed||nativeExecutionAuthority!=captured)throw new SecurityException("原SSH执行宿主已关闭");captured.check(task,id,exact);});
        }
        SshProfile profile;Operation operation;
        // Configuration changes and operation registration share one monitor: no captured old
        // profile can begin authenticating after the owner saves a replacement identity.
        synchronized(this){
            if(closed)throw new IllegalStateException("SSH模块已关闭");control.check();
            profile=profile(text(input,"profileId",80));profile.requireBinding(input.optString("_profileBinding"));
            if(profile.pinnedKey.isEmpty())throw new SecurityException("SSH执行前必须由主人核对并确认主机指纹");
            if(!task.matches("[A-Za-z0-9_-]{1,80}")||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new SecurityException("SSH需要有效宿主任务身份");
            if(!"owner".equals(input.optString("principalId"))||!"private".equals(input.optString("spaceId")))throw new SecurityException("SSH只接受宿主核验的私人主人任务");
            operation=new Operation(task,id,profile.id,profile.accountKey(),control,hostRun,epoch,command);
            if(binary(command)){Object seconds=input.opt("timeoutSeconds");int timeout=input.optInt("timeoutSeconds",180);if(seconds!=null&&(!(seconds instanceof Number)||((Number)seconds).doubleValue()!=timeout)||timeout<10||timeout>600)throw new IllegalArgumentException("文件传输本机等待上限需为10至600秒");operation.transferExpiresAt=System.currentTimeMillis()+timeout*1000L;operation.transferDeadlineNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);if(fileArtifacts==null)throw new SecurityException("原私人文件读取宿主尚未挂载，未创建SFTP传输");}

            if(active.putIfAbsent(id,operation)!=null)throw new SecurityException("该SSH执行编号已经使用，不能重复派发");
            if(accounts.putIfAbsent(operation.account,id)!=null){active.remove(id,operation);throw new IllegalStateException("该SSH账号已有任务或终端占用，请先结束原执行");}
        }
        boolean retained=false,actionStarted=false;
        try{
            operation.control.check();
            emit(operation,DirectSshClient.receipt("running"));
            operation.control.check();
            String encrypted=context.secrets.get(profile.credentialSlot());if(encrypted.isEmpty())throw new IllegalArgumentException("请先在私人连接设置中保存SSH凭据");
            DirectSshClient.Connection connection=DirectSshClient.connect(profile,new JSONObject(encrypted),operation.control);
            operation.connection=connection;
            try{
                JSONObject result;
                switch(command){
                    case "doctor":result=connection.doctor();break;
                    case "openPreviewForward":operation.forward=connection.openPreviewForward(input.getInt("remotePort"),input.getInt("timeoutSeconds"));result=operation.forward.metadata();break;
                    case "exec":actionStarted=true;result=connection.exec(text(input,"command",12000),input.optInt("timeoutSeconds",60));break;
                    case "listFiles":result=connection.listFiles(input.optString("path"));break;
                    case "readFile":result=connection.readFile(text(input,"path",1024));break;
                    case "inspectFile":result=connection.inspectFile(text(input,"path",1024));break;
                    case "downloadFile":result=fileArtifacts.download(connection,profile,operation.taskId,operation.id,input,operation.control);break;
                    // uploadBinary itself returns unknown after any possibly sent mutation. Native
                    // preflight/source rejection before that remains a known non-dispatch failure.
                    case "uploadFile":result=fileArtifacts.upload(connection,input,operation.control);break;
                    case "writeFile":if(!(input.opt("content") instanceof String))throw new IllegalArgumentException("文件内容无效");actionStarted=true;result=connection.writeFile(text(input,"path",1024),input.getString("content"),text(input,"expectedVersion",80),input.optBoolean("acceptCooperativeWrite"));break;
                    case "openPty":
                        if(!input.optBoolean("allowInteractiveShell"))throw new SecurityException("请主人明确选择交互式远端Shell");
                        actionStarted=true;operation.terminal=connection.openPty(input.optInt("cols",80),input.optInt("rows",24),input.optInt("timeoutSeconds",600));
                        result=DirectSshClient.receipt("running").put("terminalId",id).put("ptyConfirmed",true).put("expiresAt",operation.terminal.expiresAt()).put("workspaceIsSandbox",false);break;
                    default:throw new IllegalArgumentException();
                }
                emit(operation,result);retained=operation.terminal!=null||operation.forward!=null;return decorate(operation,result);
            }finally{if(!retained){if(operation.terminal!=null)operation.terminal.close();if(operation.forward!=null)operation.forward.close();connection.close();}}
        }catch(Exception failure){
            JSONObject result=actionStarted?DirectSshClient.unknown("本次SSH动作未取得确定回执，请核对远端；没有重试",true):DirectSshClient.receipt(operation.control.isCancelled()?"cancelled":"failed").put("accepted",false).put("verified",false)
                    .put("message",failure instanceof SecurityException?failure.getMessage():"SSH操作未取得执行回执，请检查配置、认证与连接状态");
            emit(operation,result);if(failure instanceof SecurityException&&!actionStarted)throw failure;return decorate(operation,result);
        }finally{if(!retained)finish(operation);trim();}
    }
    private JSONObject terminal(String command,JSONObject input)throws Exception {
        Operation operation=active.get(text(input,"terminalId",80));if(operation==null||operation.terminal==null)return DirectSshClient.unknown("终端已不在本机进程中，未重建或重放，请核对远端状态",true);
        if(!operation.taskId.equals(text(input,"hostTaskId",80))||!operation.id.equals(text(input,"runId",80)))throw new SecurityException("终端不属于当前宿主执行");
        JSONObject result;
        switch(command){
            case "readPty":result=operation.terminal.read(input.optLong("cursor",0));break;
            case "writePty":result=operation.terminal.write(text(input,"dataBase64",100000));break;
            case "resizePty":result=operation.terminal.resize(input.optInt("cols"),input.optInt("rows"));break;
            case "closePty":operation.control.cancel();operation.terminal.close();result=DirectSshClient.unknown("已关闭本机终端连接，远端终止情况未确认",true);emit(operation,result);finish(operation);break;
            default:throw new IllegalArgumentException();
        }
        if(!operation.terminal.status().equals("running"))finish(operation);return decorate(operation,result).put("terminalId",operation.id);
    }
    private JSONObject cancel(JSONObject input)throws Exception {
        Operation operation=active.get(text(input,"operationId",80));if(operation==null)return DirectSshClient.unknown("未找到本机执行控制，不能确认远端停止状态",true);
        if(operation.finished.get())return metadata(operation);
        operation.control.cancel();if(operation.terminal!=null)operation.terminal.close();if(operation.forward!=null)operation.forward.close();
        JSONObject result=DirectSshClient.unknown("已中断本机连接，不能保证远端进程已终止；不会自动重试",true);emit(operation,result);
        // An exec/connect worker owns the lease until its finally block has closed resources.
        if(operation.terminal!=null&&operation.terminal.localClosed()||operation.forward!=null&&operation.forward.connection.localClosed())finish(operation);return decorate(operation,operation.receipt);
    }
    private static boolean binary(String command){return command!=null&&command.matches("inspectFile|downloadFile|uploadFile");}
    private void reap(){if(closed)return;
        for(Operation operation:active.values())if(operation.forward!=null&&!operation.finished.get())try{operation.forward.requireCurrent();}catch(Exception ended){operation.control.cancel();operation.forward.close();try{emit(operation,operation.forward.metadata().put("message","本机预览监听与连接已关闭；远端服务和job未被停止或重启"));}catch(Exception ignored){}finish(operation);}

        for(Operation operation:active.values())if(!operation.finished.get()&&operation.transferDeadlineNanos>0&&System.nanoTime()>=operation.transferDeadlineNanos)operation.control.cancel();
        for(Operation operation:active.values())if(operation.terminal!=null&&!operation.finished.get())try{
        if(System.currentTimeMillis()>=operation.terminal.expiresAt())operation.terminal.close();
        if(!operation.terminal.status().equals("running")){JSONObject result=DirectSshClient.receipt(operation.terminal.status()).put("message","SSH终端执行连接已结束，请核对远端结果");emit(operation,result);finish(operation);}
    }catch(Exception ignored){}}
    private void finish(Operation operation){
        DirectSshClient.Connection connection=operation.connection;
        if(connection!=null&&!connection.localClosed())return;
        operation.finished.set(true);operation.finishedAt=System.currentTimeMillis();operation.connection=null;
        accounts.remove(operation.account,operation.id);
    }
    private void emit(Operation operation,JSONObject receipt)throws Exception {
        JSONObject summary=new JSONObject(receipt.toString());summary.remove("doctor");
        synchronized(operation){
            if(summary.optString("status").equals("unknown")&&operation.receipt!=null&&operation.receipt.optString("status").matches("completed|failed|partial|cancelled"))return;
            operation.receipt=summary;
            // Serialize the legacy event with receipt replacement so a late cancel event
            // cannot overtake a definite completion after leaving this monitor.
            if(!operation.hostManaged)context.emit(id(),new JSONObject().put("kind","operation").put("operation",decorate(operation,summary)));
        }
        // The original AgentRunner journals host calls. Duplicate feature events must not
        // complete its run or store private diagnostic bodies in the task event stream.
    }
    private JSONObject decorate(Operation operation,JSONObject receipt)throws Exception{return new JSONObject(receipt.toString()).put("id",operation.id).put("operationId",operation.id).put("hostTaskId",operation.taskId).put("taskId",operation.taskId).put("runId",operation.runId).put("epoch",operation.epoch).put("profileId",operation.profileId).put("command",operation.command).put("hostManaged",operation.hostManaged).put("transferExpiresAt",operation.transferExpiresAt).put("localTransferDeadline",operation.transferExpiresAt>0);}
    private JSONObject metadata(Operation operation)throws Exception {JSONObject out=decorate(operation,operation.receipt==null?DirectSshClient.receipt("running"):operation.receipt);out.remove("stdout");out.remove("stderr");out.remove("content");out.remove("doctor");out.put("startedAt",operation.startedAt).put("finishedAt",operation.finishedAt).put("localResourcesClosed",operation.connection==null?operation.finished.get():operation.connection.localClosed());if(operation.forward!=null)out.put("preview",operation.forward.metadata());if(operation.terminal!=null)out.put("terminalId",operation.id).put("status",operation.terminal.status()).put("expiresAt",operation.terminal.expiresAt());return out;}
    private synchronized SshProfile profile(String id){SshProfile found=profiles.get(id);if(found==null)throw new IllegalArgumentException("SSH连接不存在");return found;}
    private void notBusy(SshProfile profile){for(Operation operation:active.values())if(operation.profileId.equals(profile.id)&&!operation.finished.get())throw new IllegalStateException("该SSH连接正在执行，不能修改身份或信任配置");}
    private synchronized JSONObject publicProfile(SshProfile profile)throws Exception {JSONObject out=profile.document();out.remove("pinnedKey");return out.put("bindingHash",profile.bindingHash()).put("credentialsConfigured",context.secrets.contains(profile.credentialSlot())).put("hostKeyTrusted",!profile.pinnedKey.isEmpty()).put("hostFingerprint",profile.pinnedKey.isEmpty()?"":SshProfile.fingerprint(Base64.getDecoder().decode(profile.pinnedKey)));}
    private void commitProfiles(Map<String,SshProfile> proposed)throws Exception {
        JSONArray values=new JSONArray();for(SshProfile profile:proposed.values())values.put(profile.document());byte[] expected=values.toString().getBytes(StandardCharsets.UTF_8);FileOutputStream output=null;
        try{output=storage.startWrite();output.write(expected);storage.finishWrite(output);output=null;if(!storedEquals(expected))throw new java.io.IOException("SSH配置提交未通过读回核验");}
        catch(Exception failure){
            // A provider can throw after the rename committed. Read back before deciding
            // whether the staged slot is live; never roll back a confirmed committed slot.
            if(!storedEquals(expected)){if(output!=null)storage.failWrite(output);throw new IllegalStateException("SSH配置保存未取得核验回执，未发布新配置；请重新查看连接设置",failure);}
        }
        profiles.clear();profiles.putAll(proposed);
    }
    private boolean storedEquals(byte[] expected){try{return java.util.Arrays.equals(expected,storage.readFully());}catch(Exception unreadable){return false;}}
    private boolean removeCredential(String slot){try{context.secrets.remove(slot);return true;}catch(Exception cleanup){return false;}}
    private void trim(){if(active.size()<=100)return;for(Operation operation:active.values())if(active.size()>80&&operation.finished.get())active.remove(operation.id,operation);}
    private static String text(JSONObject input,String key,int max){return SshProfile.text(input,key,max);}
    @Override public void close(){closed=true;lifecycle.shutdownNow();for(Operation operation:active.values()){operation.control.cancel();if(operation.terminal!=null)operation.terminal.close();if(operation.forward!=null){operation.forward.close();finish(operation);}}accounts.clear();}
    private static final class Probe{final String profileId,binding,key,fingerprint;final long expiresAt;Probe(String profileId,String binding,String key,String fingerprint,long expiresAt){this.profileId=profileId;this.binding=binding;this.key=key;this.fingerprint=fingerprint;this.expiresAt=expiresAt;}}
    private static final class Operation{final String taskId,id,profileId,account,runId,command;final int epoch;final boolean hostManaged;final long startedAt=System.currentTimeMillis();final RunControl control;final AtomicBoolean finished=new AtomicBoolean();volatile long finishedAt,transferExpiresAt,transferDeadlineNanos;volatile DirectSshClient.Connection connection;volatile DirectSshClient.Pty terminal;volatile DirectSshClient.Forward forward;volatile JSONObject receipt;Operation(String taskId,String id,String profileId,String account,RunControl control,String runId,int epoch,String command){this.taskId=taskId;this.id=id;this.profileId=profileId;this.account=account;this.control=control;this.hostManaged=!runId.isEmpty();this.runId=hostManaged?runId:id;this.epoch=epoch;this.command=command;}}
}
