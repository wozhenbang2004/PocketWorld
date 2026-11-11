package dev.pocketengine.features.local.project;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentViewSnapshots;
import dev.pocketengine.features.attachments.OwnerFilePreviewSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original private managed-build artifact only. Parsing is not signature verification;
 * Android's system installer makes the final installation decision. No signing keys. */
final class OwnerProjectApk {
    private OwnerProjectApk() {}
    static void requireSource(OwnerFilePreviewSource source) throws Exception {
        JSONObject meta=source.metadata(),origin=meta.optJSONObject("source");
        JSONObject publication=origin==null?null:origin.optJSONObject("nativePublication");
        if(source.tombstone||source.sizeBytes<1||source.sizeBytes>AttachmentBook.MAX_FILE_BYTES||
                !source.name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")||
                !"owner".equals(meta.optString("principalId"))||!"private".equals(meta.optString("spaceId"))||
                origin==null||publication==null||!ProjectManagedOutput.ROLE.equals(origin.optString("kind"))||
                !ProjectManagedOutput.ROLE.equals(publication.optString("role"))||
                !publication.optString("planDigest").equals(origin.optString("artifactPlanDigest"))||
                !publication.optString("operationId").equals(origin.optString("operationId"))||
                !publication.optString("taskId").equals(origin.optString("taskId"))||
                !publication.optString("runId").equals(origin.optString("runId"))||
                !origin.optString("artifactPlanDigest").matches("[a-f0-9]{64}")||
                !origin.optString("buildReceiptIdentity").matches("[a-f0-9]{64}"))
            throw new SecurityException("请从原私人项目的固定 APK 构建成果打开，不接受任意 URI、团队文件或同名替代文件");
    }

    static synchronized JSONObject inspect(Context context,OwnerFilePreviewSource source,RunControl control) throws Exception {
        requireSource(source);source.requireCurrent(control);File temp=null;
        try {
            File root=new File(context.getCacheDir().getCanonicalFile(),"project-apk-inspection");
            if(Files.isSymbolicLink(root.toPath())||!root.getAbsoluteFile().equals(root.getCanonicalFile())||!root.isDirectory()&&!root.mkdirs())throw new SecurityException("APK 私有审阅目录不可用");
            // At most one inspection per process; interrupted copies have a bounded lifetime/budget.
            long total=0;int count=0;File[] abandoned=root.listFiles();
            if(abandoned==null)throw new SecurityException("无法核对 APK 审阅缓存");
            for(File child:abandoned){
                if(!child.getName().matches("[a-f0-9-]{36}\\.apk")||Files.isSymbolicLink(child.toPath())||!Files.isRegularFile(child.toPath(),LinkOption.NOFOLLOW_LINKS))throw new SecurityException("APK 审阅缓存含不可核对条目");
                if(System.currentTimeMillis()-child.lastModified()>10*60_000L){Files.deleteIfExists(child.toPath());continue;}
                total+=child.length();count++;
            }
            if(count>=4||total+source.sizeBytes>40L*1024*1024)throw new IllegalStateException("APK 审阅临时空间正在占用，请稍后再读；没有丢弃其他正在审阅的文件");
            temp=new File(root,UUID.randomUUID()+".apk");
            try(var out=Files.newOutputStream(temp.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                source.copyTo(out,control);
            }
            var before=Files.readAttributes(temp.toPath(),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            try{ProjectPackageArchive.preflightAndroidApk(temp);}catch(Exception unsupported){throw new IllegalArgumentException("APK ZIP 格式或展开声明超出有界审阅范围，未请求安装",unsupported);}
            control.check();PackageInfo info=context.getPackageManager().getPackageArchiveInfo(temp.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
            verifyInspection(temp,source,control,before);
            if(info==null||info.applicationInfo==null||info.signingInfo==null||info.packageName==null||
                    !info.packageName.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")||info.getLongVersionCode()<0)
                throw new IllegalArgumentException("不是本机可解析且带签名信息的完整 APK；未签名、分包或损坏文件不能在此安装");
            if(context.getPackageName().equals(info.packageName))throw new SecurityException("PocketWorld 自身升级只能走原应用更新入口，不能从项目成果覆盖");
            if(info.applicationInfo.minSdkVersion>android.os.Build.VERSION.SDK_INT)throw new IllegalArgumentException("此 APK 最低 Android API 高于当前手机，未请求安装");
            JSONArray signers=signers(info);if(signers.length()==0)throw new IllegalArgumentException("未读到 APK 签名证书；本入口不会自动签名或生成替代密钥");
            source.requireCurrent(control);
            JSONObject result=new JSONObject().put("artifactId",source.metadata().getString("id")).put("artifactSha256",source.sha256).put("bytes",source.sizeBytes)
                    .put("packageName",info.packageName).put("versionCode",info.getLongVersionCode()).put("minSdk",info.applicationInfo.minSdkVersion)
                    .put("signerSha256",signers).put("cryptographicVerificationPerformed",false);
            JSONObject installed=installed(context,info.packageName);result.put("beforeInstalled",installed);
            if(installed.optBoolean("observed")){
                if(!signers.toString().equals(installed.getJSONArray("signerSha256").toString()))throw new SecurityException("当前已安装应用的证书不同，未请求覆盖；不会自动卸载、换签名或清数据");
                if(info.getLongVersionCode()<installed.getLong("versionCode"))throw new SecurityException("此版本低于当前安装版本，未请求降级");
            }
            return result;
        } finally { if(temp!=null)Files.deleteIfExists(temp.toPath()); }
    }

    private static void verifyInspection(File file,OwnerFilePreviewSource source,RunControl control,java.nio.file.attribute.BasicFileAttributes before)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long size=0;byte[] buffer=new byte[32768];
        try(var input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){int n;while((n=input.read(buffer))!=-1){control.check();size+=n;if(size>source.sizeBytes)throw new SecurityException("APK 审阅副本长度变化");digest.update(buffer,0,n);}}
        finally{Arrays.fill(buffer,(byte)0);}
        var after=Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||!after.isRegularFile()||before.isSymbolicLink()||after.isSymbolicLink()||
                !java.util.Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime())||
                before.size()!=source.sizeBytes||after.size()!=source.sizeBytes||size!=source.sizeBytes||
                ((Number)Files.getAttribute(file.toPath(),"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1||
                !hex(digest.digest()).equals(source.sha256))throw new SecurityException("APK 审阅副本与原固定成果不同");
    }

    static Intent prepare(Context context,OwnerFilePreviewSource source,JSONObject reviewed,RunControl control) throws Exception {
        JSONObject actual=inspect(context,source,control);
        for(String key:new String[]{"artifactId","artifactSha256","packageName"})if(!reviewed.getString(key).equals(actual.getString(key)))throw new SecurityException("原 APK 审阅对象已变化");
        if(reviewed.getLong("bytes")!=actual.getLong("bytes")||reviewed.getLong("versionCode")!=actual.getLong("versionCode")||
                !reviewed.getJSONArray("signerSha256").toString().equals(actual.getJSONArray("signerSha256").toString())||
                !ProjectSources.canonical(reviewed.getJSONObject("beforeInstalled")).equals(ProjectSources.canonical(actual.getJSONObject("beforeInstalled"))))throw new SecurityException("APK 或当前安装身份已变化，请重新审阅，不沿旧决定继续");
        AttachmentViewSnapshots views=new AttachmentViewSnapshots(context);Intent view=null;
        try {
            view=views.prepare(source,"application/vnd.android.package-archive",control);
            source.requireCurrent(control);return systemIntent(context,view.setAction(Intent.ACTION_INSTALL_PACKAGE).putExtra(Intent.EXTRA_RETURN_RESULT,true));
        } catch(Exception failure){if(view!=null)try{views.discardUnissuedView(view);}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }

    static Intent systemIntent(Context context,Intent intent) {
        var target=context.getPackageManager().resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
        if(target==null||target.activityInfo==null||target.activityInfo.applicationInfo==null||
                (target.activityInfo.applicationInfo.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0)
            throw new SecurityException("未找到可确认的系统安装/设置组件，未把 APK 交给其他应用");
        boolean actualHandler=false;
        for(var candidate:context.getPackageManager().queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY))if(candidate.activityInfo!=null&&target.activityInfo.packageName.equals(candidate.activityInfo.packageName)&&target.activityInfo.name.equals(candidate.activityInfo.name))actualHandler=true;
        if(!actualHandler)throw new SecurityException("系统返回的是选择器而非固定安装/设置组件，未向其他应用交付文件");
        intent.setComponent(new android.content.ComponentName(target.activityInfo.packageName,target.activityInfo.name));
        return intent;
    }

    static JSONObject installed(Context context,String name) throws Exception {
        try {
            PackageInfo info=context.getPackageManager().getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES);
            return new JSONObject().put("observed",true).put("packageName",info.packageName).put("versionCode",info.getLongVersionCode()).put("signerSha256",signers(info));
        } catch(PackageManager.NameNotFoundException notVisible) {return new JSONObject().put("observed",false).put("state","not_observed_not_proof_of_absence");}
    }
    static boolean matches(JSONObject expected,JSONObject installed) throws Exception {
        return installed.optBoolean("observed")&&expected.getString("packageName").equals(installed.getString("packageName"))&&
                expected.getLong("versionCode")==installed.getLong("versionCode")&&expected.getJSONArray("signerSha256").toString().equals(installed.getJSONArray("signerSha256").toString());
    }
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return result.toString();}
    private static JSONArray signers(PackageInfo info) throws Exception {
        TreeSet<String> values=new TreeSet<>();Signature[] signatures=info.signingInfo==null?null:info.signingInfo.getApkContentsSigners();
        if(signatures!=null){if(signatures.length>8)throw new SecurityException("APK 证书数量超出审阅范围");for(Signature signature:signatures){byte[] encoded=signature.toByteArray();if(encoded.length>65536)throw new SecurityException("APK 证书超出审阅范围");values.add(ProjectSources.sha256(encoded));}}
        JSONArray result=new JSONArray();for(String value:values)result.put(value);return result;
    }
}
