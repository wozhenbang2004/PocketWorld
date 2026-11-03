package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.*;
import java.util.Arrays;
import org.json.JSONObject;

/** Native owner-selected stream bytes and one reviewed manual write; never model JSON content/URI. */
public final class ProjectDocumentImport {
    public static final int PREVIEW_BYTES=64;
    private ProjectDocumentImport(){}
    public static final class Metadata {
        public final String name,mime;public final Long declaredBytes;public final boolean directory,virtual,partial;
        public Metadata(String name,String mime,Long declaredBytes,boolean directory,boolean virtual,boolean partial){this.name=name;this.mime=mime;this.declaredBytes=declaredBytes;this.directory=directory;this.virtual=virtual;this.partial=partial;}
    }
    public interface Document extends AutoCloseable {Metadata metadata()throws Exception;InputStream open()throws Exception;@Override void close()throws Exception;}
    public static final class Source implements AutoCloseable {
        private byte[] bytes;private final String name,mime,sha;private final Long declared;private final RunControl original;
        private Source(Metadata metadata,byte[] actual,RunControl original)throws Exception{this.original=original;bytes=actual;name=label(metadata.name,120);mime=label(metadata.mime,120);declared=metadata.declaredBytes;sha=ProjectSources.sha256(actual);}
        public synchronized JSONObject view()throws Exception{require();StringBuilder hex=new StringBuilder();for(int i=0;i<Math.min(PREVIEW_BYTES,bytes.length);i++)hex.append(String.format(java.util.Locale.ROOT,"%02x",bytes[i]&255));return new JSONObject().put("name",name).put("mimeHint",mime).put("bytes",bytes.length).put("sha256",sha).put("declaredBytes",declared==null?JSONObject.NULL:declared).put("previewHex",hex.toString()).put("previewBytes",Math.min(PREVIEW_BYTES,bytes.length)).put("previewTruncated",bytes.length>PREVIEW_BYTES).put("bodyIncluded",false).put("sourceUriIncluded",false).put("sourceSnapshot","actual_native_selected_stream_copy").put("providerAtomicSnapshot",false).put("sourceSecretsAbsenceVerified",false).put("executionAuthorized",false);}
        public synchronized String suggestedPath(){require();try{String path=VersionedWorkspace.path(name);if(path.contains("/")||!ProjectSources.sourcePath(path))return "imported-file.bin";return path;}catch(Exception invalid){return "imported-file.bin";}}
        private void require(){if(bytes==null)throw new IllegalStateException("原导入字节已释放；请重新明确选择文件");}
        public synchronized int size(){require();return bytes.length;}
        public String sha256(){return sha;}
        public void requireOriginal(RunControl control)throws Exception{if(original!=control)throw new SecurityException("不能更换原系统选择的控制");control.check();if(control.isPaused())throw new SecurityException("原文件选择已暂停");}
        private synchronized byte[] copy(){require();return bytes.clone();}
        @Override public synchronized void close(){if(bytes!=null){Arrays.fill(bytes,(byte)0);bytes=null;}}
    }
    private static String label(String raw,int limit){if(raw==null)return "未命名文件";String clean=(raw.length()>limit?raw.substring(0,limit):raw).replaceAll("[\\p{Cntrl}]"," ");return clean.isBlank()?"未命名文件":clean;}
    public static Source read(Document document,RunControl original)throws Exception{
        java.util.Objects.requireNonNull(document);
        try(Document selected=document){java.util.Objects.requireNonNull(original);if(original.hasHostRun())throw new SecurityException("主人文件选择不能借模型任务控制");original.check();Metadata metadata=selected.metadata();if(metadata==null||metadata.directory||metadata.virtual||metadata.partial||metadata.declaredBytes!=null&&(metadata.declaredBytes<0||metadata.declaredBytes>ProjectSources.MAX_FILE_BYTES))throw new IllegalArgumentException("请选择完整普通文件（非目录/虚拟/未下载项），最多2MiB");if(metadata.name!=null&&ProjectPackageArchive.excluded(metadata.name,false,false)!=null)throw new IllegalArgumentException("已知私有配置/依赖文件不由此源码导入入口读取；未导入");
            try(InputStream stream=selected.open();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){if(stream==null)throw new IOException("所选文件没有可读字节流");Runnable stop=()->{try{stream.close();}catch(Exception ignored){}};original.onCancel(stop);try{byte[] buffer=new byte[8192];while(true){original.check();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("原文件选择读取已取消");int read=stream.read(buffer);if(read<0)break;if(read==0)continue;if(bytes.size()>ProjectSources.MAX_FILE_BYTES-read)throw new IllegalArgumentException("所选文件超过2MiB，未导入部分字节");bytes.write(buffer,0,read);}original.check();if(metadata.declaredBytes!=null&&metadata.declaredBytes!=bytes.size())throw new SecurityException("实际读取大小与所选文件声明不同，未确认不完整来源");return new Source(metadata,bytes.toByteArray(),original);}finally{original.removeCancelAction(stop);}}
        }
    }
    public static final class Review {
        private final Object issuer;private final Source source;private final RunControl original;private final JSONObject value;private boolean claimed;
        private Review(Object issuer,Source source,RunControl original,JSONObject identity)throws Exception{this.issuer=java.util.Objects.requireNonNull(issuer);this.source=java.util.Objects.requireNonNull(source);this.original=java.util.Objects.requireNonNull(original);value=new JSONObject(identity.toString()).put("source",source.view()).put("format","pocketworld.native-document-import-review.v1").put("authorityAdded",false).put("writesOnlyWorkingCopy",true).put("automaticallyWritesProjectVersions",false).put("atomicSourceFence",false);value.put("reviewDigest",ProjectSources.digest(value));}
        public JSONObject view()throws Exception{return new JSONObject(value.toString());}
        public String checkoutId()throws Exception{return value.getString("checkoutId");}public String projectId()throws Exception{return value.getString("projectId");}public String path()throws Exception{return value.getString("path");}public String sourceDigest()throws Exception{return value.getString("sourceDigest");}public String expectedHash()throws Exception{return value.getString("expectedHash");}
        public synchronized byte[] claim(Object originalIssuer,RunControl control,String seenDigest)throws Exception{if(claimed)throw new SecurityException("原导入确认已消费；未知只核对目标，不自动重发");require(originalIssuer,control);if(!value.getString("reviewDigest").equals(seenDigest))throw new SecurityException("原文件/目标审阅身份不同");byte[] bytes=source.copy();claimed=true;return bytes;}
        public void require(Object originalIssuer,RunControl control)throws Exception{if(issuer!=originalIssuer||original!=control||control.hasHostRun())throw new SecurityException("不能更换原主人文件选择与导入控制");control.check();if(control.isPaused())throw new SecurityException("原主人文件导入已暂停");}
    }
    /** Called only by the actual LocalModule after original checkout/target facts, not a generic JSON action. */
    public static Review reviewForHost(Object issuer,Source source,RunControl original,String checkout,String project,String digest,String path,String expectedHash)throws Exception{ProjectBuildPlan.identity(checkout);ProjectBuildPlan.identity(project);path=VersionedWorkspace.path(path);java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(path));source.requireOriginal(original);if(!ProjectSources.sourcePath(path)||ProjectPackageArchive.excluded(path,false,false)!=null||!digest.matches("[a-f0-9]{64}")||!(expectedHash.equals("absent")||expectedHash.matches("[a-f0-9]{64}"))||original.hasHostRun())throw new SecurityException("缺少原项目目录/源/目标准确身份");original.check();return new Review(issuer,source,original,new JSONObject().put("checkoutId",checkout).put("projectId",project).put("sourceDigest",digest).put("path",path).put("expectedHash",expectedHash));}
}
