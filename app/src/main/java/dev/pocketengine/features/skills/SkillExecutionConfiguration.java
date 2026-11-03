package dev.pocketengine.features.skills;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** One private native execution configuration; SkillBook remains the sole installed package registry. */
public final class SkillExecutionConfiguration {
    public static final String FORMAT="pocketworld.skill-companion-configuration.v1";
    private static final int MAX_STATE_BYTES=65536;
    private final File file;
    public SkillExecutionConfiguration(File file){this.file=java.util.Objects.requireNonNull(file);}
    public static final class Selection {
        public final CompanionSkillBackend.Deployment deployment;
        final ManagedSkillBackend.Deployment phoneDeployment;
        public final long revision;
        public final String digest,backendGrantId;
        private final String request,stateFingerprint;
        private Selection(JSONObject value,long revision)throws Exception {this(value,revision,"");}
        private Selection(JSONObject value,long revision,String stateFingerprint)throws Exception {
            this.stateFingerprint=stateFingerprint;
            this.revision=revision;
            if(ManagedSkillPolicy.FORMAT.equals(value.optString("format"))){
                JSONObject phone=ManagedSkillPolicy.checked(value);deployment=null;phoneDeployment=ManagedSkillBackend.deployment(phone);backendGrantId="phone."+phoneDeployment.authority.fingerprint;
                String checkout=phone.getString("checkoutId");JSONObject q=new JSONObject(phone.toString());q.remove("format");q.remove("checkoutId");q.remove("preparePinnedDependencies");q.put("workspaceId",checkout.isEmpty()?"native_task_copy":checkout);request=q.toString();
            }else{if(!FORMAT.equals(value.optString("format")))throw new IllegalArgumentException("执行配置版本不受支持");deployment=SkillExecutionRecords.deployment(value.getJSONObject("deployment"));phoneDeployment=null;
                backendGrantId=value.getString("backendGrantId");if(!backendGrantId.matches("[A-Za-z0-9_.:-]{1,160}"))throw new IllegalArgumentException("执行端授权标识无效");request=value.getJSONObject("request").toString();}
            JSONObject q=new JSONObject(request);request("scripts/review.py",List.of(),q.getInt("timeoutSeconds"),q.getInt("maxOutputBytes"));digest=SkillExecution.digestJson(value);
        }
        public SkillExecution.Request request(String script,List<String> arguments,int seconds,int outputBytes)throws Exception {
            if(phoneDeployment!=null)return ManagedSkillPolicy.request(phoneDeployment.configuration(),phoneDeployment.authority,script,arguments,seconds,outputBytes);
            JSONObject q=new JSONObject(request);return new SkillExecution.Request(script,q.getString("runtimeId"),q.getString("workspaceId"),q.getString("workingDirectory"),arguments,
                    strings(q.getJSONArray("readPaths")),strings(q.getJSONArray("writePaths")),strings(q.getJSONArray("outputPaths")),Math.min(seconds,q.getInt("timeoutSeconds")),Math.min(outputBytes,q.getInt("maxOutputBytes")),q.getInt("maxResultChars"));
        }
        public boolean phone(){return phoneDeployment!=null;}
        JSONObject recordedDeployment()throws Exception{return phone()?phoneDeployment.metadata():SkillExecutionRecords.deployment(deployment);}
        public JSONObject view()throws Exception{JSONObject value=new JSONObject().put("revision",revision).put("digest",digest).put("deployment",recordedDeployment()).put("request",new JSONObject(request)).put("executionLocation",phone()?"this_phone":"companion");if(phone())value.put("nativeExecutionContract",phoneDeployment.authority.risks()).put("configuration",phoneDeployment.configuration());if(!stateFingerprint.isEmpty())value.put("stateFingerprint",stateFingerprint);return value;}
    }
    public final class Review {
        private final SkillExecutionConfiguration owner=SkillExecutionConfiguration.this;
        public final String digest;
        private final long expected;
        private final String value;
        private final State original;
        private final AtomicBoolean consumed=new AtomicBoolean();
        private Review(JSONObject value,State original)throws Exception {this.value=value.toString();this.original=original;expected=original.healthy()?original.value.getLong("revision"):0;digest=SkillExecution.digestJson(value);}
        public JSONObject view()throws Exception{return new Selection(new JSONObject(value),Math.addExact(expected,1)).view().put("reviewDigest",digest).put("probed",false).put("configurationRecovery",original.recovery());}
    }
    public synchronized Review inspect(String text)throws Exception {
        if(text==null||text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("执行配置需在16KB以内");
        JSONObject value=new JSONObject(text);only(value,Set.of("format","deployment","request","backendGrantId"));
        if(!FORMAT.equals(value.getString("format")))throw new IllegalArgumentException("请选择隔离执行端导出的配置文件");
        only(value.getJSONObject("deployment"),Set.of("profileId","serviceBase","environmentId","installationId","commandAlias","policySha256","readySha256","helperSha256"));
        only(value.getJSONObject("request"),Set.of("runtimeId","workspaceId","workingDirectory","readPaths","writePaths","outputPaths","timeoutSeconds","maxOutputBytes","maxResultChars"));
        new Selection(value,0);return new Review(value,capture());
    }
    /** Native task preparation only; no model/Feature.handle route accepts it. */
    public synchronized Review inspectPhone(JSONObject ownerSelected)throws Exception {
        JSONObject phone=ManagedSkillPolicy.checked(ownerSelected);if(phone.toString().getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("执行配置需在16KB以内");new Selection(phone,0);return new Review(phone,capture());
    }
    public synchronized Selection install(Review review,String displayedDigest)throws Exception {
        if(review==null||review.owner!=this||!review.digest.equals(displayedDigest)||review.consumed.get())throw new SecurityException("配置审阅已失效");
        if(!capture().fingerprint.equals(review.original.fingerprint))throw new IllegalStateException("执行配置实际字节已变化，请重新审阅");
        long next=Math.addExact(review.expected,1);JSONObject value=new JSONObject(review.value);review.consumed.set(true);
        if(!review.original.healthy())retainOriginal(review.original);
        if(!capture().fingerprint.equals(review.original.fingerprint))throw new IllegalStateException("保留原配置期间实际字节已变化，未替换");
        // A new native state identity fences every retained Selection even when a damaged
        // record's old revision cannot be recovered. It never restores a task/script grant.
        String stateId=java.util.UUID.randomUUID().toString();
        JsonFiles.write(file,new JSONObject().put("revision",next).put("stateId",stateId).put("enabled",true).put("configuration",value));
        State actual=capture();if(!actual.healthy()||!stateId.equals(actual.value.optString("stateId")))throw new IllegalStateException("新配置提交尚未确认，请重新读取原配置");return new Selection(actual.value.getJSONObject("configuration"),next,actual.fingerprint);
    }
    public synchronized Selection current()throws Exception {
        State state;try{state=capture();}catch(Exception unreadable){throw unavailable();}
        if(!state.healthy())throw unavailable();if(!state.value.getBoolean("enabled"))throw new SkillExecution.Unavailable("尚未选择手机托管执行或已准备的电脑隔离执行端");return new Selection(state.value.getJSONObject("configuration"),state.value.getLong("revision"),state.fingerprint);
    }
    public synchronized void check(Selection selected)throws Exception {Selection current=current();if(selected==null||selected.stateFingerprint.isEmpty()||selected.revision!=current.revision||!selected.digest.equals(current.digest)||!selected.stateFingerprint.equals(current.stateFingerprint))throw new SecurityException("原执行配置已替换、停用或实际状态改变，请重新审阅");}
    /** A legacy revision cannot identify the installed state after an explicit damaged-state recovery. */
    @Deprecated public synchronized void disable(long expectedRevision)throws Exception {throw new SecurityException("请重新读取原执行配置，停用需带该次观察的准确状态指纹");}
    public synchronized void disable(long expectedRevision,String observedStateFingerprint)throws Exception {
        State old=capture();if(!old.healthy())throw unavailable();if(old.value.getLong("revision")!=expectedRevision)throw new IllegalStateException("执行配置已变化");
        if(observedStateFingerprint==null||!observedStateFingerprint.equals(old.fingerprint))throw new SecurityException("原配置状态已变化，请重新读取后再停用");
        JsonFiles.write(file,new JSONObject().put("revision",Math.addExact(expectedRevision,1)).put("stateId",java.util.UUID.randomUUID().toString()).put("enabled",false));
    }
    public synchronized JSONObject snapshot()throws Exception {
        State state;try{state=capture();}catch(Exception unreadable){return damaged(false).put("message","原执行配置不能安全读取或留存；请核对原文件，未恢复执行权限");}
        if(!state.healthy())return damaged(true).put("configurationRecovery",state.recovery()).put("message","原执行配置损坏，脚本未启用；可重新审阅执行配置，确认后保留原字节再替换");
        if(!state.value.getBoolean("enabled"))return new JSONObject().put("configured",false).put("revision",state.value.getLong("revision")).put("stateFingerprint",state.fingerprint).put("revisionKnown",true);
        return new Selection(state.value.getJSONObject("configuration"),state.value.getLong("revision"),state.fingerprint).view().put("configured",true).put("probed",false).put("revisionKnown",true);
    }
    private static JSONObject damaged(boolean recoverable)throws Exception{return new JSONObject().put("configured",false).put("status","configuration_unavailable").put("recoveryRequired",true).put("recoveryAvailable",recoverable).put("revisionKnown",false).put("executionAuthorized",false).put("originalStateRetained",true);}
    private static SkillExecution.Unavailable unavailable(){return new SkillExecution.Unavailable("原执行配置不可用，未启用脚本；请在原任务重新审阅配置并明确恢复，旧批准不会恢复");}
    private static final class State {
        final byte[] bytes;final String fingerprint;final JSONObject value;
        State(byte[] bytes,JSONObject value)throws Exception{this.bytes=bytes;this.value=value;fingerprint=bytes==null?"absent":SkillExecution.sha256(bytes);}
        boolean healthy(){return value!=null;}
        JSONObject recovery()throws Exception{JSONObject result=new JSONObject().put("required",!healthy()).put("previousRevisionKnown",healthy()).put("restoresApprovals",false).put("startsProcess",false);
            if(!healthy())result.put("originalSha256",fingerprint).put("originalBytes",bytes.length).put("willRetainBeforeReplacement",true);return result;}
    }
    private State capture()throws Exception {
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return new State(null,new JSONObject().put("revision",0).put("enabled",false));
        byte[] bytes=readOriginal(file);JSONObject value=null;
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();org.json.JSONTokener parser=new org.json.JSONTokener(text);Object decoded=parser.nextValue();if(!(decoded instanceof JSONObject)||parser.nextClean()!=0)throw new IllegalArgumentException();JSONObject record=(JSONObject)decoded;
            Set<String> allowed=Set.of("revision","stateId","enabled","configuration");var keys=record.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new IllegalArgumentException();
            Object revision=record.opt("revision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<0||!(record.opt("enabled") instanceof Boolean))throw new IllegalArgumentException();
            if(record.has("stateId")&&!record.getString("stateId").matches("[a-f0-9-]{36}"))throw new IllegalArgumentException();
            if(record.getBoolean("enabled")&&(record.getLong("revision")<1||record.optJSONObject("configuration")==null))throw new IllegalArgumentException();
            if(record.has("configuration"))new Selection(record.getJSONObject("configuration"),record.getLong("revision"));value=record;
        }catch(Exception invalid){/* Retain exact bad bytes; reading never creates a disabled replacement. */}
        return new State(bytes,value);
    }
    private static byte[] readOriginal(File file)throws Exception {
        BasicFileAttributes before=Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.size()>MAX_STATE_BYTES)throw unavailable();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){byte[] page=new byte[8192];for(int count;(count=input.read(page))>=0;){if(count==0)continue;if(bytes.size()+count>MAX_STATE_BYTES)throw unavailable();bytes.write(page,0,count);}}
        BasicFileAttributes after=Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!after.isRegularFile()||before.size()!=bytes.size()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!java.util.Objects.equals(before.fileKey(),after.fileKey()))throw unavailable();return bytes.toByteArray();
    }
    private void retainOriginal(State original)throws Exception {
        File directory=new File(file.getParentFile(),file.getName()+".recovery");
        if(!Files.exists(directory.toPath(),LinkOption.NOFOLLOW_LINKS))try{Files.createDirectory(directory.toPath());}catch(java.nio.file.FileAlreadyExistsException raced){/* Validate the actual native directory below. */}
        if(Files.isSymbolicLink(directory.toPath())||!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new SecurityException("原配置留存位置不可用，未替换原文件");File target=new File(directory,original.fingerprint+".bin");
        if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))try(FileChannel output=FileChannel.open(target.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer bytes=ByteBuffer.wrap(original.bytes);while(bytes.hasRemaining())output.write(bytes);output.force(true);}
        if(!java.util.Arrays.equals(original.bytes,readOriginal(target)))throw new SecurityException("原配置留存字节不一致，未覆盖原文件或留存件");
    }
    private static List<String> strings(JSONArray array)throws Exception {List<String> out=new ArrayList<>();for(int i=0;i<array.length();i++)out.add(array.getString(i));return out;}
    private static void only(JSONObject value,Set<String> allowed)throws Exception {var names=value.keys();while(names.hasNext())if(!allowed.contains(names.next()))throw new IllegalArgumentException("执行配置含不支持字段；请勿导入令牌、环境变量或命令字符串");if(value.length()!=allowed.size())throw new IllegalArgumentException("执行配置缺少必需字段");}
}
