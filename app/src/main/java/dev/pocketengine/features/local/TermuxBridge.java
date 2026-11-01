package dev.pocketengine.features.local;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.project.ProjectBuildPlan;
import dev.pocketengine.features.local.project.GeneratedArtifacts;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.runtime.RuntimeReceipt;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional official RUN_COMMAND transport; no emulated interpreter and no exported secret bridge. */
public final class TermuxBridge implements AutoCloseable {
    /** Retained native invocation, never reconstructed from feature/model JSON. */
    static final class NativeProjectCall {
        final RunControl control;final LocalModule.ProjectAdmission admission;final JSONObject link;final long pauseRevision;
        NativeProjectCall(RunControl control,LocalModule.ProjectAdmission admission,JSONObject link)throws Exception {this.control=java.util.Objects.requireNonNull(control);this.admission=java.util.Objects.requireNonNull(admission);this.link=new JSONObject(link.toString());pauseRevision=control.pauseRevision();check();}
        void check()throws Exception {control.check();if(!control.hasHostRun()||!control.matchesHostRun(link.getString("taskId"),link.getString("runId"),link.getInt("coordinatorEpoch"))||control.isPaused()||pauseRevision!=control.pauseRevision())throw new SecurityException("项目命令的原执行控制已停止或暂停");admission.check();control.check();}
    }
    private static final Object LOCK=new Object();
    public static final String PERMISSION="com.termux.permission.RUN_COMMAND";
    private static final String PREFIX="/data/data/com.termux/files/usr/bin/",HOME="/data/data/com.termux/files/home";
    private final FeatureContext context;
    public TermuxBridge(FeatureContext context){this.context=context;}
    public JSONObject status()throws Exception {
        boolean installed=true;String version="";try{version=context.android.getPackageManager().getPackageInfo("com.termux",0).versionName;}catch(PackageManager.NameNotFoundException absent){installed=false;}
        boolean permission=context.android.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED;
        return new JSONObject().put("installed",installed).put("version",version==null?"":version).put("permissionGranted",permission).put("enabled",context.android.getSharedPreferences("local_development",0).getBoolean("termuxEnabled",false)).put("externalAppsSetting","unverified").put("runCommandAvailable",installed&&permission).put("reliableCancellation",false).put("message",!installed?"请先自行安装Termux；本应用未内置Node/Python":!permission?"请在系统设置授予运行Termux命令权限":"还需在Termux中启用allow-external-apps，实际命令回执用于确认接入");
    }
    public JSONObject setEnabled(boolean value)throws Exception {if(!context.android.getSharedPreferences("local_development",0).edit().putBoolean("termuxEnabled",value).commit())throw new IllegalStateException("无法保存Termux连接选择");return status();}
    /** One original-operation runtime port, sharing the owner bridge and its actual receipt cache. */
    public RuntimeReceipt dispatchForRuntime(RuntimeInvocation invocation)throws Exception {
        invocation.checkCurrent();JSONObject exact=invocation.exact(),payload=JsonFiles.copy(exact.getJSONObject("payload"));
        JSONObject link=invocation.executionRef().executionLink();
        NativeProjectCall call=new NativeProjectCall(invocation.originalControl(),invocation::checkCurrent,link);
        payload.put("operationId",invocation.executionRef().operationId());
        final JSONObject actual;
        switch(invocation.executionRef().phase()) {
            case PROBE:actual=probe(payload,call);break;
            case STAGE:actual=stage(invocation.sourceSnapshot(),payload,call);break;
            case COMMAND:actual=run(payload,call);break;
            default:throw new SecurityException("runtime_project_command_unsupported");
        }
        return RuntimeReceipt.fromBackend(invocation.executionRef(),actual);
    }
    public JSONObject run(JSONObject input)throws Exception {
        return run(input,null);
    }
    JSONObject run(JSONObject input,NativeProjectCall call)throws Exception {
        String name=JsonFiles.text(input,"executable",32);if(!java.util.Set.of("node","npm","pnpm","python","python3","git","java","javac","gradle").contains(name))throw new SecurityException("请选择受支持的Termux编程工具");
        JSONArray args=input.optJSONArray("argv");if(args==null)args=new JSONArray();
        String checkout=JsonFiles.text(input,"checkoutId",80),digest=input.optString("expectedSourceDigest","");String directory=directory(checkout,digest);
        if(input.has("workdir")&&!directory.equals(input.getString("workdir")))throw new SecurityException("工作目录必须与此已准备的副本一致");
        JSONObject projectLink=projectLink(input,"termux.run",call);
        synchronized(LOCK){
            JSONObject prepared=requirePrepared(checkout,digest);
            if(digest.isEmpty()){if(projectLink!=null)throw new SecurityException("项目计划必须绑定确切源码摘要");return dispatch(name,args,directory,"",input.optString("operationId",UUID.randomUUID().toString()),"command",checkout,null);}
            JSONObject source=JsonFiles.copy(prepared.getJSONObject("sourceCheckout"));
            source.put("requestedExecutable",name).put("requestedArgv",new JSONArray(args.toString()));
            if(projectLink!=null)source.put("projectExecution",projectLink);else source.remove("projectExecution");
            String guard=ProjectSources.launchGuard(source.getJSONArray("sourceManifest"),digest,PREFIX+name,args);
            return dispatch("sh",new JSONArray(),directory,guard,input.optString("operationId",UUID.randomUUID().toString()),"command",checkout,source,call);
        }
    }
    public JSONObject probe(JSONObject input)throws Exception {return probe(input,null);}
    JSONObject probe(JSONObject input,NativeProjectCall call)throws Exception {JSONObject link=projectLink(input,"termux.probe",call);return dispatch("sh",new JSONArray().put("-c").put("for t in node npm pnpm python3 python git java javac gradle; do p=$(command -v \"$t\" 2>/dev/null); [ -z \"$p\" ] || printf '%s=%s\\n' \"$t\" \"$p\"; done"),HOME,"",input.optString("operationId",UUID.randomUUID().toString()),"tool_probe",input.optString("checkoutId",""),link==null?null:new JSONObject().put("projectExecution",link),call);}
    public JSONObject stage(JSONObject snapshot,JSONObject input)throws Exception {
        return stage(snapshot,input,null);
    }
    JSONObject stage(JSONObject snapshot,JSONObject input,NativeProjectCall call)throws Exception {
        String checkout=snapshot.getJSONObject("checkout").getString("id");if(!checkout.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("工作副本编号无效");
        String digest=input.optString("expectedSourceDigest","");JSONObject source=JsonFiles.copy(snapshot.getJSONObject("checkout"));
        JSONObject projectLink=projectLink(input,"termux.prepare",call);if(projectLink!=null){if(digest.isEmpty())throw new SecurityException("项目计划必须绑定确切源码摘要");source.put("projectExecution",projectLink);}
        if(!digest.isEmpty()){
            JSONObject actual=ProjectSources.describe(snapshot);if(!digest.equals(actual.getString("sourceDigest")))throw new SecurityException("计划源码已变化，未准备旧版本");
            source.put("sourceDigest",digest).put("sourceManifest",actual.getJSONArray("files"));
        }
        String directory=directory(checkout,digest),parent=digest.isEmpty()?HOME+"/pocketworld":directory(checkout)+"/revisions";
        StringBuilder script=new StringBuilder("set -eu\nmkdir -p "+quote(parent)+"\nmkdir "+quote(directory)+"\ncd "+quote(directory)+"\n");JSONArray files=snapshot.getJSONArray("files");
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);String path=dev.pocketengine.features.workspace.VersionedWorkspace.path(file.getString("path"));int slash=path.lastIndexOf('/');if(slash>0)script.append("mkdir -p ").append(quote(path.substring(0,slash))).append('\n');String encoded=android.util.Base64.encodeToString(file.getString("content").getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);script.append("printf '%s' ").append(quote(encoded)).append(" | base64 -d > ").append(quote(path)).append('\n');}
        if(script.toString().getBytes(StandardCharsets.UTF_8).length>196608)throw new IllegalArgumentException("Termux一次传入项目最多约144KiB内容，请拆分项目");
        String stagedScript=digest.isEmpty()?script.toString():ProjectSources.stageScript(snapshot,directory,parent);
        return dispatch("sh",new JSONArray(),HOME,stagedScript,input.optString("operationId",UUID.randomUUID().toString()),"project_stage",checkout,source,call).put("workdir",directory);
    }
    public JSONObject collect(JSONObject checkout,JSONObject input)throws Exception {
        String id=checkout.getString("id");
        synchronized(LOCK){
            if(input.has("artifactSelection")){
                JSONObject selection=GeneratedArtifacts.verified(input.getJSONObject("artifactSelection"));JSONObject link=GeneratedArtifacts.ownerCollection(selection,input);
                JSONObject build=GeneratedArtifacts.ownerBuild(selection.getJSONObject("buildPlan"),JsonFiles.read(file(context.android,selection.getString("buildJobId"))));
                GeneratedArtifacts.requireBuild(selection,build);GeneratedArtifacts.requireLatest(build,list());
                String digest=selection.getString("sourceDigest");JSONObject source=JsonFiles.copy(requirePrepared(id,digest).getJSONObject("sourceCheckout"));source.remove("projectExecution");
                source.put("artifactSelection",selection).put("artifactCollection",link);
                String selected=TermuxWorkspaceTransfer.selectedScript(selection.getString("path"),selection.getString("artifactPlanDigest"));
                String guarded=ProjectSources.launchGuard(source.getJSONArray("sourceManifest"),digest,PREFIX+"sh",new JSONArray().put("-c").put(selected));
                return dispatch("sh",new JSONArray(),directory(id,digest),guarded,input.getString("operationId"),"workspace_collect",id,source);
            }
            requirePrepared(id,"");return dispatch("sh",new JSONArray(),directory(id),TermuxWorkspaceTransfer.collectScript(),input.optString("operationId",UUID.randomUUID().toString()),"workspace_collect",id,checkout);
        }
    }
    public boolean busy(String checkout)throws Exception {
        synchronized(LOCK){JSONArray jobs=list();for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);if(checkout.equals(job.optString("checkoutId"))&&!java.util.Set.of("completed","failed").contains(job.optString("status")))return true;}return false;}
    }
    private JSONObject requirePrepared(String checkout,String digest)throws Exception {
        JSONArray jobs=list();for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);Object exit=job.opt("exitCode");boolean received=digest.isEmpty()||Boolean.TRUE.equals(job.opt("receiptReceived"))&&(exit instanceof Integer||exit instanceof Long)&&((Number)exit).longValue()==0;if(received&&checkout.equals(job.optString("checkoutId"))&&digest.equals(job.optString("sourceDigest"))&&"project_stage".equals(job.optString("kind"))&&"completed".equals(job.optString("status")))return JsonFiles.read(file(context.android,job.getString("id")));}
        throw new IllegalStateException("请先准备此确切源码版本到Termux，并等待真实成功回执");
    }
    private static String directory(String checkout){if(checkout==null||!checkout.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("工作副本编号无效");return HOME+"/pocketworld/"+checkout;}
    private static String directory(String checkout,String digest){if(digest.isEmpty())return directory(checkout);if(!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("源码摘要无效");return directory(checkout)+"/revisions/"+digest;}
    JSONObject collection(String id)throws Exception {synchronized(LOCK){JSONObject value=JsonFiles.read(file(context.android,id));if(!"workspace_collect".equals(value.optString("kind")))throw new IllegalArgumentException("这不是文件回收回执");return value;}}
    void materialized(String operation,String checkout)throws Exception {synchronized(LOCK){File file=file(context.android,operation);JSONObject value=JsonFiles.read(file);value.put("materializedCheckoutId",checkout);JsonFiles.write(file,value);}}
    private JSONObject dispatch(String name,JSONArray args,String workdir,String stdin,String operation,String kind,String checkout,JSONObject sourceCheckout)throws Exception {
        return dispatch(name,args,workdir,stdin,operation,kind,checkout,sourceCheckout,null);
    }
    private JSONObject dispatch(String name,JSONArray args,String workdir,String stdin,String operation,String kind,String checkout,JSONObject sourceCheckout,NativeProjectCall call)throws Exception {
        synchronized(LOCK){
            if(call!=null)call.check();
            if(!operation.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("操作编号无效");
            if(!workdir.equals(HOME)&&!workdir.startsWith(HOME+"/"))throw new SecurityException("Termux工作目录需属于其home");for(String part:workdir.split("/"))if(part.equals(".."))throw new SecurityException("工作目录不可穿越");
            if(args.length()>40)throw new IllegalArgumentException("Termux参数过多");String[] argv=new String[args.length()];int size=stdin.getBytes(StandardCharsets.UTF_8).length;for(int i=0;i<argv.length;i++){argv[i]=args.getString(i);if(argv[i].indexOf('\0')>=0)throw new IllegalArgumentException("命令参数无效");size+=argv[i].getBytes(StandardCharsets.UTF_8).length;}if(size>196608)throw new IllegalArgumentException("Termux请求超过大小限制");
            File receipt=file(context.android,operation);String fingerprint=name+"\n"+args+"\n"+workdir+"\n"+stdin+"\n"+checkout;
            if(receipt.isFile()){JSONObject old=JsonFiles.read(receipt);if(!digest(fingerprint).equals(old.optString("fingerprint")))throw new IllegalArgumentException("该编号已用于另一项Termux操作");return publicReceipt(old);}
            if(!checkout.isEmpty()&&busy(checkout))throw new IllegalStateException("该Termux工作副本仍在执行或结果不明，请先核对原任务；不会重发命令");
            JSONObject availability=status();if(!availability.optBoolean("installed")||!availability.optBoolean("permissionGranted")||!availability.optBoolean("enabled"))throw new IllegalStateException(availability.optString("message")+"；请先在本机连接设置明确启用Termux");
            File[] existing=receipt.getParentFile().listFiles((dir,n)->n.endsWith(".json"));if(existing!=null&&existing.length>=200)throw new IllegalStateException("Termux回执已达200条，需先导出归档；未释放旧操作编号");
            String nonce=UUID.randomUUID().toString();JSONObject value=new JSONObject().put("id",operation).put("operationId",operation).put("kind",kind).put("executable",name).put("argv",new JSONArray(args.toString())).put("checkoutId",checkout).put("workdir",kind.equals("project_stage")?directory(checkout,sourceCheckout==null?"":sourceCheckout.optString("sourceDigest")):workdir).put("fingerprint",digest(fingerprint)).put("nonce",nonce).put("status","unknown").put("createdAt",System.currentTimeMillis()).put("message","Termux派发已登记，等待实际回执").put("verified",false);if(sourceCheckout!=null){value.put("sourceCheckout",JsonFiles.copy(sourceCheckout));if(sourceCheckout.has("sourceDigest"))value.put("sourceDigest",sourceCheckout.getString("sourceDigest")).put("sourceGuardRequired",kind.equals("command")).put("atomicSourceFence",false).put("sourceCheckScope","manifest_files_only");if(sourceCheckout.has("requestedExecutable"))value.put("requestedExecutable",sourceCheckout.getString("requestedExecutable")).put("requestedArgv",sourceCheckout.getJSONArray("requestedArgv"));}value.put("updatedAt",value.getLong("createdAt"));JsonFiles.write(receipt,value);
            if(sourceCheckout!=null&&sourceCheckout.has("projectExecution")){value.put("projectExecution",JsonFiles.copy(sourceCheckout.getJSONObject("projectExecution")));JsonFiles.write(receipt,value);}
            if(!checkout.isEmpty())value.put("checkoutSequence",nextSequence(checkout,operation));
            if(sourceCheckout!=null&&sourceCheckout.has("artifactSelection"))value.put("collectionMode","selected_text_artifact").put("artifactCollection",JsonFiles.copy(sourceCheckout.getJSONObject("artifactCollection"))).put("sourceGuardRequired",true).put("artifactReady",false);
            JsonFiles.write(receipt,value);
            Intent callback=new Intent(context.android,TermuxResultReceiver.class).setData(Uri.parse("pocketworld-local://result/"+operation+"/"+nonce));int flags=PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_CANCEL_CURRENT;if(android.os.Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            PendingIntent pending=PendingIntent.getBroadcast(context.android,operation.hashCode(),callback,flags);
            Intent command=new Intent("com.termux.RUN_COMMAND").setComponent(new ComponentName("com.termux","com.termux.app.RunCommandService")).putExtra("com.termux.RUN_COMMAND_PATH",PREFIX+name).putExtra("com.termux.RUN_COMMAND_ARGUMENTS",argv).putExtra("com.termux.RUN_COMMAND_WORKDIR",workdir).putExtra("com.termux.RUN_COMMAND_BACKGROUND",true).putExtra("com.termux.RUN_COMMAND_STDIN",stdin).putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",pending);
            if(call!=null)call.check();
            try{ComponentName started=context.android.startService(command);if(started==null)throw new IllegalStateException("Termux未接收服务请求");value.put("status","awaiting_result").put("message","请求已交给Termux；只有收到实际执行回执才知道结果");JsonFiles.write(receipt,value);}catch(Exception failure){value.put("status","unknown").put("message","未取得Termux接收确认，请检查Termux与原操作；不会自动重试");JsonFiles.write(receipt,value);}
            return publicReceipt(value);
        }
    }
    private static JSONObject projectLink(JSONObject input,String command,NativeProjectCall call)throws Exception {
        if(call==null)return ProjectBuildPlan.ownerLink(input,command);call.check();
        if(!command.equals(call.link.getString("command"))||!call.link.getString("operationId").equals(input.getString("operationId")))throw new SecurityException("项目命令与原操作不同");
        JSONObject payload=new JSONObject(input.toString());payload.remove("operationId");if(!ProjectSources.digest(payload).equals(call.link.getString("requestDigest")))throw new SecurityException("项目派发参数已变化");return new JSONObject(call.link.toString());
    }
    public JSONArray list()throws Exception{synchronized(LOCK){JSONArray result=new JSONArray();File folder=file(context.android,"placeholder").getParentFile();File[] files=folder.listFiles((dir,n)->n.endsWith(".json"));if(files!=null)for(File file:files)result.put(summary(JsonFiles.read(file)));return result;}}
    public JSONObject get(String id)throws Exception{synchronized(LOCK){File file=file(context.android,id);if(!file.isFile())throw new IllegalArgumentException("Termux回执不存在");return publicReceipt(JsonFiles.read(file));}}
    public JSONObject cancel(String id)throws Exception{synchronized(LOCK){File file=file(context.android,id);JSONObject value=JsonFiles.read(file);if(value.length()==0)throw new IllegalArgumentException("Termux回执不存在");if(!java.util.Set.of("completed","failed").contains(value.optString("status"))){value.put("updatedAt",Math.max(System.currentTimeMillis(),value.optLong("updatedAt")+1)).put("cancelRequested",true).put("status","unknown").put("message","已登记停止要求，但Termux公共RUN_COMMAND没有可靠的单任务取消接口；请在Termux中终止进程并核对结果");JsonFiles.write(file,value);}return publicReceipt(value);}}
    public static JSONObject receive(Context context,Intent intent)throws Exception {
        synchronized(LOCK){Uri data=intent.getData();if(data==null||!"pocketworld-local".equals(data.getScheme())||!"result".equals(data.getHost())||data.getPathSegments().size()!=2)return null;String id=data.getPathSegments().get(0),nonce=data.getPathSegments().get(1);File file=file(context,id);JSONObject value=JsonFiles.read(file);if(!nonce.equals(value.optString("nonce"))||value.optBoolean("receiptReceived"))return null;Bundle result=intent.getBundleExtra("result");if(result==null)return null;
            String stdout=result.getString("stdout",""),stderr=result.getString("stderr","");if(stdout==null)stdout="";if(stderr==null)stderr="";int exit=result.getInt("exitCode",Integer.MIN_VALUE),error=result.getInt("err",Integer.MIN_VALUE);boolean known=exit!=Integer.MIN_VALUE&&error==-1;
            boolean truncated=stdout.length()>65536||stderr.length()>65536||originalLength(result,"stdout_original_length",stdout.length())>stdout.length()||originalLength(result,"stderr_original_length",stderr.length())>stderr.length();
            if(value.optBoolean("sourceGuardRequired")){String marker=ProjectSources.marker(value.getString("sourceDigest"))+"\n";boolean checked=stdout.startsWith(marker);value.put("sourceCheckStatus",checked?"pre_exec_manifest_verified":"unverified_or_mismatch");if(checked)stdout=stdout.substring(marker.length());}
            value.put("receiptReceived",true).put("status",known?(exit==0?"completed":"failed"):"unknown").put("exitCode",exit==Integer.MIN_VALUE?JSONObject.NULL:exit).put("stdout",bound(stdout)).put("stderr",bound(stderr)).put("outputTruncated",truncated).put("updatedAt",Math.max(System.currentTimeMillis(),value.optLong("updatedAt")+1)).put("message",known?"收到Termux真实执行回执，请检查输出与文件":"Termux返回不完整或内部错误回执，请检查原任务");
            if("selected_text_artifact".equals(value.optString("collectionMode"))){try{JSONObject selection=value.getJSONObject("sourceCheckout").getJSONObject("artifactSelection");value.put("collectedArtifact",TermuxWorkspaceTransfer.decodeSelected(value,selection.getString("path"),selection.getString("artifactPlanDigest"))).put("artifactReady",true);}catch(Exception invalid){value.put("artifactReady",false).put("artifactError","选定产物回执未完整核验，未提供预览；仅核对原收集操作");}}
            JsonFiles.write(file,value);return publicReceipt(value);
        }
    }
    private static File file(Context context,String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Termux操作编号无效");return new File(new File(context.getFilesDir(),"termux-receipts"),id+".json");}
    private static JSONObject summary(JSONObject value)throws Exception{JSONObject copy=publicReceipt(value);copy.remove("stdout");copy.remove("stderr");if(copy.optJSONObject("collectedArtifact")!=null)copy.getJSONObject("collectedArtifact").remove("content");return copy;}
    private static JSONObject publicReceipt(JSONObject original)throws Exception{JSONObject copy=JsonFiles.copy(original);copy.remove("nonce");copy.remove("fingerprint");copy.remove("sourceCheckout");if("workspace_collect".equals(copy.optString("kind"))){
            copy.remove("stdout");copy.put("transferReady",false);
            if("selected_text_artifact".equals(original.optString("collectionMode"))){copy.put("artifactReady",false);copy.remove("collectedArtifact");try{JSONObject selection=original.getJSONObject("sourceCheckout").getJSONObject("artifactSelection");JSONObject artifact=TermuxWorkspaceTransfer.decodeSelected(original,selection.getString("path"),selection.getString("artifactPlanDigest"));if(!original.has("collectedArtifact")||!ProjectSources.canonical(artifact).equals(ProjectSources.canonical(original.getJSONObject("collectedArtifact"))))throw new SecurityException("产物缓存不一致");copy.put("artifactReady",true).put("collectedArtifact",artifact);}catch(Exception invalid){if("completed".equals(original.optString("status")))copy.put("artifactError","选定产物回执不完整或校验失败，未提供预览");}return copy;}
            if("completed".equals(original.optString("status")))try{JSONArray files=TermuxWorkspaceTransfer.decode(original);copy.put("transferReady",true).put("transferFileCount",files.length());}
            catch(Exception invalid){copy.put("transferError","文件回执不完整或格式不受支持，未创建副本；请核对Termux输出");}
        }return copy;}
    private long nextSequence(String checkout,String current)throws Exception {long last=0;JSONArray jobs=list();for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);if(!checkout.equals(job.optString("checkoutId"))||current.equals(job.optString("id"))||!job.has("checkoutSequence"))continue;Object value=job.get("checkoutSequence");if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)throw new SecurityException("原生执行顺序记录无效");last=Math.max(last,((Number)value).longValue());}if(last==Long.MAX_VALUE)throw new IllegalStateException("原生执行顺序已满");return last+1;}
    private static long originalLength(Bundle bundle,String key,int fallback){Object value=bundle.get(key);try{return value instanceof Number?((Number)value).longValue():value instanceof String?Long.parseLong((String)value):fallback;}catch(NumberFormatException invalid){return fallback;}}
    private static String bound(String text){return text.length()>65536?text.substring(0,65536):text;}
    private static String digest(String text)throws Exception{StringBuilder out=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static String quote(String text){return "'"+text.replace("'","'\\''")+"'";}
    @Override public void close(){}
}
