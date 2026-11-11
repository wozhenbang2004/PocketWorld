package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.*;

/** One selected real managed-build output. Original Local/TaskStore admission and the
 * existing attachment Book remain authoritative; this class has no process, index or store. */
public final class ProjectManagedOutput {
    public static final String FORMAT="pocketworld.managed-build-output.v1",COMMAND="collectProjectOutput",KIND="managed_output_collect",ROLE="managed_build_output";
    // The byte[] reader is also used by Skills; its existing small budget is unchanged.
    public static final int MAX_BYTES=2*1024*1024;
    public static final String STREAM_FORMAT="pocketworld.managed-build-output.v2";
    public static final long MAX_STREAM_BYTES=AttachmentBook.MAX_FILE_BYTES;
    private ProjectManagedOutput(){}
    public static boolean selection(JSONObject value){return value!=null&&(FORMAT.equals(value.optString("format"))||STREAM_FORMAT.equals(value.optString("format")));}
    public static JSONObject prepare(RuntimeWorkspace root,JSONObject snapshot,JSONObject originalPlan,JSONObject originalBinding,JSONObject actualBuild,long currentOrder,String selectedPath,RunControl original)throws Exception{
        JSONObject plan=ProjectBuildPlan.verified(originalPlan),source=ProjectSources.describe(snapshot);String path=path(selectedPath);JSONObject proof=build(plan,originalBinding,actualBuild,currentOrder);
        if(!root.projectId().equals(plan.getString("projectId"))||!root.checkoutId().equals(plan.getString("checkoutId"))||!source.getString("sourceDigest").equals(plan.getString("sourceDigest")))throw new SecurityException("原成功构建与当前完整源码版本不同，未收集");
        for(int i=0;i<plan.getJSONArray("sourceManifest").length();i++)if(path.equals(plan.getJSONArray("sourceManifest").getJSONObject(i).getString("path")))throw new SecurityException("源码不是生成文件，请按原源码或固定项目包查看");
        ReviewedBuildOutput.Observation observed=ReviewedBuildOutput.inspect(root,path,MAX_STREAM_BYTES,original);JSONObject core=new JSONObject().put("format",STREAM_FORMAT).put("projectId",plan.getString("projectId")).put("checkoutId",plan.getString("checkoutId")).put("sourceDigest",plan.getString("sourceDigest"))
            .put("runtimeId","managed_process_v1").put("runtimeIdentity",plan.getString("runtimeIdentity")).put("buildPlan",plan).put("buildBinding",ProjectSources.copy(originalBinding)).put("buildReceipt",proof).put("buildJobId",proof.getString("jobId")).put("buildReceiptIdentity",proof.getString("receiptIdentity")).put("buildCheckoutSequence",proof.getLong("checkoutSequence"))
            .put("path",path).put("sha256",observed.sha256).put("bytes",observed.bytes).put("mimeType",mime(path)).put("maxBytes",MAX_STREAM_BYTES).put("atomicArtifactFence",false).put("buildOutputAttribution","selected_output_observed_after_build")
            .put("warnings",new JSONArray().put("原managed成功build、同源码SHA和原job顺序必须仍一致；仅固定这一个实际生成文件，支持二进制，流式收集最多20MiB，仍受原附件总容量100MiB限制；APK只保存真实字节，不代表已签名、已验签或可安装").put("同UID不是OS沙箱；输出是构建后选定的实际字节，不据退出码推断业务正确或独占生成归属；不自动执行、分享或覆盖源码"));
        String digest=ProjectSources.digest(core);return core.put("artifactPlanId",digest).put("artifactPlanDigest",digest);
    }
    public static JSONObject verified(JSONObject supplied)throws Exception{
        JSONObject core=ProjectSources.copy(supplied);core.remove("artifactPlanId");core.remove("artifactPlanDigest");String digest=supplied.getString("artifactPlanDigest");
        if(!selection(core)||!digest.matches("[a-f0-9]{64}")||!digest.equals(supplied.getString("artifactPlanId"))||!digest.equals(ProjectSources.digest(core)))throw new SecurityException("原managed生成文件选择已变化");
        var keys=new TreeSet<String>();supplied.keys().forEachRemaining(keys::add);if(!keys.equals(new TreeSet<>(Set.of("format","projectId","checkoutId","sourceDigest","runtimeId","runtimeIdentity","buildPlan","buildBinding","buildReceipt","buildJobId","buildReceiptIdentity","buildCheckoutSequence","path","sha256","bytes","mimeType","maxBytes","atomicArtifactFence","buildOutputAttribution","warnings","artifactPlanId","artifactPlanDigest"))))throw new SecurityException("生成文件选择含未审阅字段");
        JSONObject plan=ProjectBuildPlan.verified(core.getJSONObject("buildPlan")),proof=core.getJSONObject("buildReceipt");for(String field:new String[]{"projectId","checkoutId","sourceDigest","runtimeId","runtimeIdentity"})if(!plan.getString(field).equals(core.getString(field)))throw new SecurityException("生成文件选择与原构建项目/环境不同");
        if(!"managed_process_v1".equals(core.getString("runtimeId"))||!core.getString("buildJobId").equals(proof.getString("jobId"))||!core.getString("buildReceiptIdentity").equals(proof.getString("receiptIdentity"))||positive(core.opt("buildCheckoutSequence"))!=positive(proof.opt("checkoutSequence"))||!Boolean.FALSE.equals(core.opt("atomicArtifactFence"))||!"selected_output_observed_after_build".equals(core.optString("buildOutputAttribution"))||!number(core.opt("maxBytes"),limit(core))||!core.getString("sha256").matches("[a-f0-9]{64}")||!(core.opt("bytes") instanceof Integer||core.opt("bytes") instanceof Long)||core.getLong("bytes")<0||core.getLong("bytes")>limit(core)||!mime(path(core.getString("path"))).equals(core.getString("mimeType")))throw new SecurityException("生成文件原顺序、范围、摘要或容量无效");
        for(int i=0;i<plan.getJSONArray("sourceManifest").length();i++)if(core.getString("path").equals(plan.getJSONArray("sourceManifest").getJSONObject(i).getString("path")))throw new SecurityException("原源码不能改标为生成文件");
        return core.put("artifactPlanId",digest).put("artifactPlanDigest",digest);
    }
    public static JSONObject summary(JSONObject selection)throws Exception{selection=verified(selection);JSONObject view=new JSONObject();for(String key:new String[]{"projectId","checkoutId","sourceDigest","path","sha256","bytes","mimeType","maxBytes","buildJobId","buildReceiptIdentity","buildCheckoutSequence","artifactPlanDigest","buildOutputAttribution"})view.put(key,selection.get(key));return view.put("nativeFileOperation",true).put("writesCheckout",false).put("automaticallyExported",false);}
    /** Must receive the original managed cache receipt, never the copy embedded in a model plan. */
    public static JSONObject build(JSONObject plan,JSONObject binding,JSONObject actual,long currentOrder)throws Exception{
        if(!"managed_process_v1".equals(plan.optString("runtimeId"))||!"managed_process".equals(actual.optString("provider"))||!actual.optBoolean("resourcesReleased")||!actual.optBoolean("processExited")||!actual.optBoolean("processGroupAbsent")||actual.optBoolean("ownedWorkerLive")||actual.optBoolean("timedOut"))throw new SecurityException("原managed build尚未真实退出并释放，不能收集");
        JSONObject proof=GeneratedArtifacts.managedBuild(plan,actual,binding);if(positive(actual.opt("checkoutSequence"))!=currentOrder)throw new SecurityException("副本已有后续原命令/准备，旧build不能再采纳生成文件");return proof;
    }
    public static void requireActual(JSONObject selection,JSONObject actualBuild,long currentOrder)throws Exception{
        selection=verified(selection);JSONObject proof=build(selection.getJSONObject("buildPlan"),selection.getJSONObject("buildBinding"),actualBuild,currentOrder);
        if(!selection.getString("buildReceiptIdentity").equals(proof.getString("receiptIdentity"))||selection.getLong("buildCheckoutSequence")!=proof.getLong("checkoutSequence"))throw new SecurityException("原成功构建回执身份已变化");
    }
    public static void requireCollection(JSONObject selection,JSONObject link)throws Exception{
        selection=verified(selection);JSONObject build=selection.getJSONObject("buildBinding");if(!COMMAND.equals(link.optString("command"))||!"native_host_operation".equals(link.optString("authority")))throw new SecurityException("生成文件只能沿原prepared文件动作保存");
        if(!build.getString("taskId").equals(link.getString("taskId")))throw new SecurityException("生成文件不能跨任务借原构建来源；旧run仅作证据，不搬grant或审批");
        if(build.getString("operationId").equals(link.getString("operationId"))||!selection.getString("projectId").equals(link.optString("projectId"))||!selection.getString("checkoutId").equals(link.optString("checkoutId"))||!selection.getString("sourceDigest").equals(link.optString("sourceDigest")))throw new SecurityException("生成文件原项目或操作关联不同");
    }
    public static JSONObject publish(AttachmentBook book,RuntimeWorkspace root,JSONObject selection,JSONObject link,RunControl original,Runnable beforePublication)throws Exception{
        selection=verified(selection);requireCollection(selection,link);JSONObject prior=find(book,selection,link,original);if(prior.optBoolean("found"))return receipt(selection,link,prior);
        String relative=selection.getString("path");long approvedLimit=limit(selection);
        ReviewedBuildOutput.Observation observed=ReviewedBuildOutput.inspect(root,relative,approvedLimit,original);
        if(observed.bytes!=selection.getLong("bytes")||!observed.sha256.equals(selection.getString("sha256")))throw new SecurityException("原生成文件审阅后已变化，未发布或读取其他版本");original.check();
        try(ReviewedBuildOutput actual=ReviewedBuildOutput.open(root,relative,approvedLimit,selection.getLong("bytes"),selection.getString("sha256"),original)){
            JSONObject source=new JSONObject().put("kind",ROLE).put("projectId",selection.getString("projectId")).put("checkoutId",selection.getString("checkoutId")).put("path",selection.getString("path")).put("sourceDigest",selection.getString("sourceDigest")).put("buildJobId",selection.getString("buildJobId")).put("buildReceiptIdentity",selection.getString("buildReceiptIdentity")).put("buildCheckoutSequence",selection.getLong("buildCheckoutSequence")).put("artifactPlanDigest",selection.getString("artifactPlanDigest")).put("taskId",link.getString("taskId")).put("runId",link.getString("runId")).put("operationId",link.getString("operationId")).put("planDigest",link.getString("planDigest")).put("buildOutputAttribution","selected_output_observed_after_build").put("businessVerified",false);
            // Keep the original Local/TaskStore admission outside the Book monitor. The stream
            // then refuses EOF unless the actual copied bytes match the approved identity;
            // Book keeps its original final pause-revision commit fence and same operation ID.
            beforePublication.run();
            JSONObject saved=book.importNativeResult(actual,relative.substring(relative.lastIndexOf('/')+1),selection.getString("mimeType"),source,link.getString("operationId"),ROLE,link.getString("taskId"),link.getString("runId"),selection.getString("artifactPlanDigest"),original);
            if(saved.getLong("size")!=selection.getLong("bytes")||!saved.getString("sha256").equals(selection.getString("sha256")))throw new SecurityException("原Book发布回执与审阅字节不同，请核对原操作");
            return receipt(selection,link,saved);
        }
    }
    public static JSONObject observe(AttachmentBook book,JSONObject selection,JSONObject link,RunControl original)throws Exception{
        selection=verified(selection);requireCollection(selection,link);JSONObject prior=find(book,selection,link,original);if(!prior.optBoolean("found"))return base(selection,link).put("status","unknown").put("receiptReceived",false).put("accepted",JSONObject.NULL).put("message","原生成文件发布未完整确认，只查询同原动作，不再次保存");return receipt(selection,link,prior).put("reconciledFromOriginalCatalog",true);
    }
    private static JSONObject find(AttachmentBook book,JSONObject selection,JSONObject link,RunControl control)throws Exception{
        JSONObject found=book.findNativeResult(link.getString("operationId"),ROLE,link.getString("taskId"),link.getString("runId"),selection.getString("artifactPlanDigest"),control);if(!found.optBoolean("found"))return found;
        JSONObject source=found.getJSONObject("source");for(String key:new String[]{"projectId","checkoutId","path","sourceDigest","buildJobId","buildReceiptIdentity","artifactPlanDigest"})if(!selection.getString(key).equals(source.optString(key)))throw new SecurityException("原catalog生成成果不是这份构建或文件选择");
        if(!found.getString("sha256").equals(selection.getString("sha256"))||found.getLong("size")!=selection.getLong("bytes"))throw new SecurityException("原catalog生成成果字节身份改变");return found;
    }
    private static JSONObject base(JSONObject selection,JSONObject link)throws Exception{return new JSONObject().put("id",link.getString("operationId")).put("operationId",link.getString("operationId")).put("kind",KIND).put("provider","app_private_checkout").put("projectExecution",ProjectSources.copy(link)).put("checkoutId",selection.getString("checkoutId")).put("sourceDigest",selection.getString("sourceDigest")).put("sourceCheckStatus","native_checkout_verified").put("path",selection.getString("path")).put("buildJobId",selection.getString("buildJobId")).put("buildReceiptIdentity",selection.getString("buildReceiptIdentity")).put("artifactPlanDigest",selection.getString("artifactPlanDigest")).put("nativeFileOperation",true).put("nativePublicationEntered",false).put("processExitCode",JSONObject.NULL).put("replayAllowed",false).put("businessVerified",false);}
    private static JSONObject receipt(JSONObject selection,JSONObject link,JSONObject actual)throws Exception{return base(selection,link).put("status","completed").put("exitCode",0).put("receiptReceived",true).put("accepted",true).put("nativePublicationEntered",true).put("artifactReady",true).put("generatedArtifact",true).put("atomicArtifactFence",false).put("buildOutputAttribution","selected_output_observed_after_build").put("attachment",actual).put("message","已从原成功build的同版副本保存选定文件，原目录未覆盖；输出是固定实际字节，不代表业务已验证，未分享或执行");}
    public static JSONObject failedBeforePublication(JSONObject selection,JSONObject link,Exception failure)throws Exception{return base(verified(selection),link).put("status","failed").put("exitCode",1).put("receiptReceived",true).put("accepted",false).put("code","managed_output_not_published").put("message","选定生成文件未进入发布（"+failure.getClass().getSimpleName()+"），请重新核对原build与所选SHA");}
    public static boolean terminalReceipt(JSONObject exact,JSONObject actual){try{
        if(!COMMAND.equals(exact.optString("command"))||!KIND.equals(actual.optString("kind"))||!"app_private_checkout".equals(actual.optString("provider"))||!actual.optBoolean("nativeFileOperation")||!actual.has("processExitCode")||!actual.isNull("processExitCode")||!actual.optBoolean("receiptReceived"))return false;JSONObject selected=verified(exact.getJSONObject("payload").getJSONObject("artifactSelection"));if(!actual.optString("artifactPlanDigest").equals(selected.getString("artifactPlanDigest")))return false;
        if("failed".equals(actual.optString("status")))return !actual.optBoolean("nativePublicationEntered")&&!actual.has("attachment");if(!"completed".equals(actual.optString("status")))return false;JSONObject artifact=actual.getJSONObject("attachment"),source=artifact.getJSONObject("source");return selected.getString("sha256").equals(artifact.getString("sha256"))&&selected.getLong("bytes")==artifact.getLong("size")&&selected.getString("artifactPlanDigest").equals(source.optString("artifactPlanDigest"))&&actual.getString("operationId").equals(source.optString("operationId"))&&ROLE.equals(source.optString("kind"))&&actual.optBoolean("nativePublicationEntered")&&exact.getString("planDigest").equals(source.optString("planDigest"))&&actual.getJSONObject("projectExecution").getString("taskId").equals(source.optString("taskId"))&&actual.getJSONObject("projectExecution").getString("runId").equals(source.optString("runId"));
    }catch(Exception invalid){return false;}}
    public static byte[] bytes(RuntimeWorkspace root,String relative,RunControl control)throws Exception{
        String selected=path(relative);Path file=root.resolve(selected,true).toPath();BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!before.isRegularFile()||before.size()>MAX_BYTES)throw new SecurityException("选定生成文件不是2MiB以内普通文件");
        Object links=Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS);if(!(links instanceof Number)||((Number)links).longValue()!=1)throw new SecurityException("生成文件链接数量不明或不是独立普通文件");ByteArrayOutputStream bytes=new ByteArrayOutputStream((int)before.size());
        try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[16384];for(int n;(n=input.read(buffer))>=0;){control.check();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("原生成文件读取已停止");if(n==0)continue;if(bytes.size()+n>MAX_BYTES)throw new IOException("生成文件读取时超过2MiB");bytes.write(buffer,0,n);}}
        BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!after.isRegularFile()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey())||after.size()!=bytes.size())throw new SecurityException("选定生成文件在读取期间变化");byte[] result=bytes.toByteArray();if(!ProjectSources.sha256(result).equals(hashFile(file,control)))throw new SecurityException("选定生成文件复核SHA变化");return result;
    }
    private static String hashFile(Path path,RunControl control)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] block=new byte[16384];int count=0;for(int n;(n=input.read(block))>=0;){control.check();if(n==0)continue;if((count+=n)>MAX_BYTES)throw new IOException("生成文件复核时增长");digest.update(block,0,n);}}StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));return hash.toString();}
    public static String path(String path){path=VersionedWorkspace.path(path);if(ProjectPackageArchive.excluded(path,false,true)!=null)throw new SecurityException("环境、依赖、凭据和私有配置不是生成成果");return path;}
    private static String mime(String path){String value=path.toLowerCase(Locale.ROOT);return value.endsWith(".html")||value.endsWith(".htm")?"text/html":value.endsWith(".css")?"text/css":value.endsWith(".js")||value.endsWith(".mjs")?"text/javascript":value.endsWith(".json")?"application/json":value.endsWith(".png")?"image/png":value.endsWith(".jpg")||value.endsWith(".jpeg")?"image/jpeg":value.endsWith(".pdf")?"application/pdf":value.endsWith(".txt")||value.endsWith(".md")||value.endsWith(".log")||value.endsWith(".csv")?"text/plain":"application/octet-stream";}
    private static long limit(JSONObject selection){return STREAM_FORMAT.equals(selection.optString("format"))?MAX_STREAM_BYTES:MAX_BYTES;}
    private static long positive(Object value){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)throw new SecurityException("历史build缺原生顺序，需重新明确批准构建");return ((Number)value).longValue();}
    private static boolean number(Object value,long expected){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()==expected;}
}
