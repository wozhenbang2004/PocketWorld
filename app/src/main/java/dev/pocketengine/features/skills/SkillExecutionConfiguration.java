package dev.pocketengine.features.skills;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** One private native execution configuration; SkillBook remains the sole installed package registry. */
public final class SkillExecutionConfiguration {
    public static final String FORMAT="pocketworld.skill-companion-configuration.v1";
    private final File file;
    public SkillExecutionConfiguration(File file){this.file=java.util.Objects.requireNonNull(file);}
    public static final class Selection {
        public final CompanionSkillBackend.Deployment deployment;
        final ManagedSkillBackend.Deployment phoneDeployment;
        public final long revision;
        public final String digest,backendGrantId;
        private final String request;
        private Selection(JSONObject value,long revision)throws Exception {
            this.revision=revision;
            if(ManagedSkillPolicy.FORMAT.equals(value.optString("format"))){
                JSONObject phone=ManagedSkillPolicy.checked(value);deployment=null;phoneDeployment=ManagedSkillBackend.deployment(phone);backendGrantId="phone."+phoneDeployment.authority.fingerprint;
                String checkout=phone.getString("checkoutId");JSONObject q=new JSONObject(phone.toString());q.remove("format");q.remove("checkoutId");q.remove("preparePinnedDependencies");q.put("workspaceId",checkout.isEmpty()?"native_task_copy":checkout);request=q.toString();
            }else{deployment=SkillExecutionRecords.deployment(value.getJSONObject("deployment"));phoneDeployment=null;
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
        public JSONObject view()throws Exception{JSONObject value=new JSONObject().put("revision",revision).put("digest",digest).put("deployment",recordedDeployment()).put("request",new JSONObject(request)).put("executionLocation",phone()?"this_phone":"companion");if(phone())value.put("nativeExecutionContract",phoneDeployment.authority.risks()).put("configuration",phoneDeployment.configuration());return value;}
    }
    public final class Review {
        private final SkillExecutionConfiguration owner=SkillExecutionConfiguration.this;
        public final String digest;
        private final long expected;
        private final String value;
        private final AtomicBoolean consumed=new AtomicBoolean();
        private Review(JSONObject value,long expected)throws Exception {this.value=value.toString();this.expected=expected;digest=SkillExecution.digestJson(value);}
        public JSONObject view()throws Exception{return new Selection(new JSONObject(value),expected+1).view().put("reviewDigest",digest).put("probed",false);}
    }
    public synchronized Review inspect(String text)throws Exception {
        if(text==null||text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("执行配置需在16KB以内");
        JSONObject value=new JSONObject(text);only(value,Set.of("format","deployment","request","backendGrantId"));
        if(!FORMAT.equals(value.getString("format")))throw new IllegalArgumentException("请选择隔离执行端导出的配置文件");
        only(value.getJSONObject("deployment"),Set.of("profileId","serviceBase","environmentId","installationId","commandAlias","policySha256","readySha256","helperSha256"));
        only(value.getJSONObject("request"),Set.of("runtimeId","workspaceId","workingDirectory","readPaths","writePaths","outputPaths","timeoutSeconds","maxOutputBytes","maxResultChars"));
        new Selection(value,0);return new Review(value,state().optLong("revision"));
    }
    /** Native task preparation only; no model/Feature.handle route accepts it. */
    public synchronized Review inspectPhone(JSONObject ownerSelected)throws Exception {
        JSONObject phone=ManagedSkillPolicy.checked(ownerSelected);new Selection(phone,0);return new Review(phone,state().optLong("revision"));
    }
    public synchronized Selection install(Review review,String displayedDigest)throws Exception {
        if(review==null||review.owner!=this||!review.digest.equals(displayedDigest)||review.consumed.get())throw new SecurityException("配置审阅已失效");
        JSONObject old=state();if(old.optLong("revision")!=review.expected)throw new IllegalStateException("执行配置已变化，请重新审阅");
        long next=Math.addExact(review.expected,1);JSONObject value=new JSONObject(review.value);review.consumed.set(true);
        JsonFiles.write(file,new JSONObject().put("revision",next).put("enabled",true).put("configuration",value));return new Selection(value,next);
    }
    public synchronized Selection current()throws Exception {JSONObject state=state();if(!state.optBoolean("enabled")||state.optJSONObject("configuration")==null)throw new SkillExecution.Unavailable("尚未选择手机托管执行或已准备的电脑隔离执行端");return new Selection(state.getJSONObject("configuration"),state.getLong("revision"));}
    public synchronized void check(Selection selected)throws Exception {Selection current=current();if(selected.revision!=current.revision||!selected.digest.equals(current.digest))throw new SecurityException("原执行配置已替换、停用或移除，请重新审阅");}
    public synchronized void disable(long expectedRevision)throws Exception {
        JSONObject old=state();if(old.optLong("revision")!=expectedRevision)throw new IllegalStateException("执行配置已变化");
        JsonFiles.write(file,new JSONObject().put("revision",Math.addExact(expectedRevision,1)).put("enabled",false));
    }
    public synchronized JSONObject snapshot()throws Exception {JSONObject value=state();if(!value.optBoolean("enabled"))return new JSONObject().put("configured",false).put("revision",value.optLong("revision"));return current().view().put("configured",true).put("probed",false);}
    private JSONObject state()throws Exception{return file.isFile()?JsonFiles.read(file):new JSONObject().put("revision",0).put("enabled",false);}
    private static List<String> strings(JSONArray array)throws Exception {List<String> out=new ArrayList<>();for(int i=0;i<array.length();i++)out.add(array.getString(i));return out;}
    private static void only(JSONObject value,Set<String> allowed)throws Exception {var names=value.keys();while(names.hasNext())if(!allowed.contains(names.next()))throw new IllegalArgumentException("执行配置含不支持字段；请勿导入令牌、环境变量或命令字符串");if(value.length()!=allowed.size())throw new IllegalArgumentException("执行配置缺少必需字段");}
}
