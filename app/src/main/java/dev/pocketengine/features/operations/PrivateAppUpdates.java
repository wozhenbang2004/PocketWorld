package dev.pocketengine.features.operations;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;
import dev.pocketengine.R;

/** Owner-driven private distribution. Startup only reads the original download; it never enqueues or installs. */
public final class PrivateAppUpdates {
    private static PrivateAppUpdates instance;
    public static synchronized PrivateAppUpdates get(Context context)throws Exception{if(instance==null)instance=new PrivateAppUpdates(context);return instance;}
    private final Context context;private final DownloadManager downloads;private final AtomicFile stateFile;private final File directory;
    private PrivateAppUpdates(Context context)throws Exception{this.context=context.getApplicationContext();downloads=this.context.getSystemService(DownloadManager.class);File external=this.context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);if(external==null)throw new IOException(LiteralUiText.text(R.string.w8_update_message_000, "应用下载目录不可用"));directory=new File(external,"private-app-updates");if(!directory.mkdirs()&&!directory.isDirectory())throw new IOException(LiteralUiText.text(R.string.w8_update_message_001, "更新目录不可用"));stateFile=new AtomicFile(new File(this.context.getFilesDir(),"private-app-update-state.json"));}
    private JSONObject read()throws Exception{if(!stateFile.getBaseFile().exists())return new JSONObject().put("phase","unconfigured");byte[] raw=stateFile.readFully();if(raw.length>65536)throw new IOException(LiteralUiText.text(R.string.w8_update_message_002, "更新状态超过限定大小"));return new JSONObject(new String(raw,StandardCharsets.UTF_8));}
    private void save(JSONObject state)throws Exception{byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8);FileOutputStream output=null;try{output=stateFile.startWrite();output.write(bytes);stateFile.finishWrite(output);output=null;if(!Arrays.equals(bytes,stateFile.readFully()))throw new IOException(LiteralUiText.text(R.string.w8_update_message_003, "更新状态保存未确认"));}catch(Exception failed){if(output!=null)stateFile.failWrite(output);throw failed;}}
    public synchronized JSONObject state()throws Exception{return view(read());}
    private JSONObject view(JSONObject state)throws Exception{JSONObject view=new JSONObject().put("phase",state.optString("phase")).put("sourceConfigured",state.has("source")).put("automaticInstall",false);for(String key:new String[]{"updateId","receivedBytes","totalBytes","lastErrorCategory"})if(state.has(key))view.put(key,state.get(key));JSONObject manifest=state.optJSONObject("manifest");if(manifest!=null)view.put("versionName",manifest.optString("versionName")).put("versionCode",manifest.getLong("versionCode")).put("bytes",manifest.getLong("bytes")).put("sha256",manifest.getString("sha256"));return view;}
    public synchronized JSONObject configure(String raw)throws Exception{JSONObject state=read();if(state.has("updateId")&&!Set.of("cancelled","rejected").contains(state.optString("phase")))throw new IllegalStateException(LiteralUiText.text(R.string.w8_update_message_004, "请先核对或取消原下载，不能用换源覆盖它"));URI source=UpdatePackagePolicy.source(raw.strip());state=new JSONObject().put("source",source.toString()).put("phase","configured");save(state);return view(state);}
    @SuppressWarnings("deprecation") private PackageInfo installed()throws Exception{return context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);}
    public JSONObject check()throws Exception{
        final String configured;synchronized(this){JSONObject state=read();if(!state.has("source"))throw new IllegalStateException(LiteralUiText.text(R.string.w8_update_message_005, "尚未配置正式私有分发源"));if(state.has("updateId")&&!Set.of("cancelled","rejected").contains(state.optString("phase")))return view(state);configured=state.getString("source");}
        URI source=UpdatePackagePolicy.source(configured);HttpURLConnection connection=(HttpURLConnection)source.toURL().openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(8000);connection.setRequestProperty("Accept","application/json");
        JSONObject manifest;
        try{int status=connection.getResponseCode();if(status==401||status==403)throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_006, "私有更新源需要正式分发授权；未改用公开源"));if(status!=200)throw new IOException("private_update_http_"+status);ByteArrayOutputStream body=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] block=new byte[4096];int count;while((count=in.read(block))!=-1){if(body.size()+count>32768)throw new IOException(LiteralUiText.text(R.string.w8_update_message_007, "更新清单过大"));body.write(block,0,count);}}manifest=UpdatePackagePolicy.manifest(new JSONObject(new String(body.toByteArray(),StandardCharsets.UTF_8)),context.getPackageName(),installed().getLongVersionCode(),source);}
        finally{connection.disconnect();}
        synchronized(this){JSONObject state=read();if(!configured.equals(state.optString("source"))||state.has("updateId")&&!Set.of("cancelled","rejected").contains(state.optString("phase")))throw new IllegalStateException(LiteralUiText.text(R.string.w8_update_message_008, "更新选择已变化"));state=new JSONObject().put("source",configured).put("phase",manifest.getLong("versionCode")>installed().getLongVersionCode()?"available":"up_to_date").put("manifest",manifest);save(state);return view(state);}
    }
    public synchronized JSONObject download(String expectedSha)throws Exception{
        JSONObject state=read();if(state.has("updateId"))return query(state.getString("updateId"));if(!"available".equals(state.optString("phase"))||!expectedSha.equals(state.getJSONObject("manifest").getString("sha256")))throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_009, "请先核对当前更新清单"));
        if(downloads==null)throw new IllegalStateException(LiteralUiText.text(R.string.w8_update_message_010, "系统下载服务不可用"));String update=UUID.randomUUID().toString();File target=new File(directory,"package-"+update+".apk");
        state.put("updateId",update).put("fileName",target.getName()).put("phase","enqueue_prepared");save(state);
        try{
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(state.getJSONObject("manifest").getString("downloadUrl"))).setTitle(LiteralUiText.text(R.string.w8_update_message_011, "Pocket World update")).setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE).setDestinationUri(Uri.fromFile(target));
            long id=downloads.enqueue(request);state.put("downloadId",id).put("phase","downloading");save(state);
        }catch(Exception unknown){state.put("phase","enqueue_unknown");try{save(state);}catch(Exception ignored){}throw new IllegalStateException(LiteralUiText.text(R.string.w8_update_message_012, "原下载是否已创建尚不确定；只查询原编号，不重复下载"),unknown);}
        return view(state);
    }
    private File target(JSONObject state)throws Exception{String name=state.getString("fileName");if(!name.equals("package-"+state.getString("updateId")+".apk"))throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_013, "原更新文件定位无效"));File file=new File(directory,name);if(!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_014, "更新文件越界"));return file;}
    private long findOriginal(JSONObject state)throws Exception{
        if(state.has("downloadId"))return state.getLong("downloadId");if(downloads==null)return -1;String expected=Uri.fromFile(target(state)).toString();long match=-1;int scanned=0;
        try(Cursor rows=downloads.query(new DownloadManager.Query())){if(rows==null)return -1;while(rows.moveToNext()){if(++scanned>1000)throw new IOException(LiteralUiText.text(R.string.w8_update_message_015, "原下载查询超过限定窗口"));String local=rows.getString(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));if(expected.equals(local)){if(match!=-1)throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_016, "发现多个原下载，未推断唯一编号"));match=rows.getLong(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));}}}
        if(match>0){state.put("downloadId",match);save(state);}return match;
    }
    public synchronized JSONObject query(String expectedUpdate)throws Exception{
        JSONObject state=read();if(!expectedUpdate.equals(state.optString("updateId")))throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_008, "更新选择已变化"));if(Set.of("cancelled","rejected").contains(state.optString("phase")))return view(state);long id=findOriginal(state);
        if(id<1){state.put("phase","download_unknown");save(state);return view(state);}try(Cursor row=downloads.query(new DownloadManager.Query().setFilterById(id))){
            if(row==null||!row.moveToFirst()){state.put("phase","download_unknown");save(state);return view(state);}
            int status=row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));state.put("receivedBytes",row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))).put("totalBytes",row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
            if(status==DownloadManager.STATUS_SUCCESSFUL){state.put("phase","downloaded");try{verify(state);state.put("phase","ready");}catch(Exception rejected){state.put("phase","rejected").put("lastErrorCategory","package_verification_failed");save(state);throw rejected;}}
            else state.put("phase",status==DownloadManager.STATUS_FAILED?"failed":status==DownloadManager.STATUS_PAUSED?"paused":"downloading");save(state);return view(state);
        }
    }
    public synchronized JSONObject cancel(String expectedUpdate)throws Exception{
        JSONObject state=read();if(!expectedUpdate.equals(state.optString("updateId")))throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_017, "不能取消另一更新"));long id=findOriginal(state);if(id<1){state.put("phase","cancellation_unconfirmed");save(state);return view(state);}downloads.remove(id);File file=target(state);if(file.exists()&&!file.delete())throw new IOException(LiteralUiText.text(R.string.w8_update_message_018, "原更新文件清理未确认"));state.put("phase","cancelled");state.remove("installNonce");save(state);return view(state);
    }
    @SuppressWarnings("deprecation") private void verify(JSONObject state)throws Exception{
        File file=target(state);JSONObject manifest=state.getJSONObject("manifest");if(!file.isFile()||file.length()!=manifest.getLong("bytes")||!sha(file).equals(manifest.getString("sha256")))throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_019, "更新文件大小或摘要不符"));
        PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(file.getPath(),PackageManager.GET_SIGNING_CERTIFICATES),installed=installed();if(archive==null)throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_020, "更新文件不是可读安装包"));
        UpdatePackagePolicy.packageIdentity(manifest,context.getPackageName(),installed.getLongVersionCode(),archive.packageName,archive.getLongVersionCode(),archive.versionName,signers(installed),signers(archive));
    }
    private static Set<String> signers(PackageInfo info)throws Exception{Set<String> values=new HashSet<>();if(info.signingInfo==null)return values;for(var signature:info.signingInfo.getApkContentsSigners())values.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));return values;}
    public synchronized Intent installIntent(String expectedUpdate)throws Exception{
        JSONObject state=read();if(!expectedUpdate.equals(state.optString("updateId"))||!Set.of("ready","installer_requested").contains(state.optString("phase")))throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_021, "原更新尚未验证完成"));verify(state);
        if(!context.getPackageManager().canRequestPackageInstalls())throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_022, "请在系统设置允许本应用请求安装，再由你重新选择安装"));
        String nonce=UUID.randomUUID().toString();state.put("installNonce",nonce).put("phase","installer_requested");save(state);
        Uri uri=new Uri.Builder().scheme("content").authority(context.getPackageName()+".updates").appendPath(expectedUpdate).appendPath(state.getJSONObject("manifest").getString("sha256")).appendPath(nonce).build();
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    synchronized File fileForInstaller(Uri uri)throws Exception{
        JSONObject state=read();List<String> parts=uri.getPathSegments();if(parts.size()!=3||!context.getPackageName().concat(".updates").equals(uri.getAuthority())||!parts.get(0).equals(state.optString("updateId"))||!parts.get(1).equals(state.getJSONObject("manifest").getString("sha256"))||!parts.get(2).equals(state.optString("installNonce"))||!"installer_requested".equals(state.optString("phase")))throw new SecurityException(LiteralUiText.text(R.string.w8_update_message_023, "原安装文件授权已变化"));verify(state);return target(state);
    }
    private static String sha(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] block=new byte[65536];int count;while((count=in.read(block))!=-1)digest.update(block,0,count);}return hex(digest.digest());}
    private static String hex(byte[] digest){StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}
}
