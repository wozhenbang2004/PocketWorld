package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.NativeCommandExecutor;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.json.JSONObject;

/** Original manual-owner source-to-version import facts, not a task/grant or model byte writer. */
public final class ProjectBinaryImport {
    public static final String REVIEW="pocketworld.local-binary-file-review.v1",RECEIPT="pocketworld.local-binary-file-import.v1";
    public static final int PREVIEW_BYTES=64;
    @FunctionalInterface public interface Current {void check()throws Exception;}
    private ProjectBinaryImport() {}
    /** Only this native reader creates observations; no caller supplied Base64/host path enters a plan. */
    public static final class Source {
        private final java.io.File root;private final byte[] original;
        public final String checkoutId,path,sha256;public final boolean deleted,binary;public final int sizeBytes;
        private Source(java.io.File root,String checkout,String path,byte[] bytes,boolean missing)throws Exception{this.root=root;checkoutId=checkout;this.path=path;original=bytes;deleted=missing;sizeBytes=bytes.length;sha256=missing?"absent":ProjectSources.sha256(bytes);binary=!missing&&binary(bytes);}
        public byte[] bytes(){if(deleted)throw new IllegalStateException("所选工作文件已删除，没有字节可导回");return original.clone();}
        public void requireCurrent(Current current)throws Exception{Source after=read(root,checkoutId,path,current);if(deleted!=after.deleted||!sha256.equals(after.sha256))throw new SecurityException("审阅后的工作副本原字节已变化，未导回旧版本");}
    }
    public static boolean binary(byte[] bytes){try{String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();return text.indexOf('\0')>=0;}catch(java.nio.charset.CharacterCodingException invalid){return true;}}
    public static Source read(java.io.File root,String checkout,String relative,Current current)throws Exception {
        current.check();String path=VersionedWorkspace.path(relative);if(!ProjectSources.sourcePath(path))throw new SecurityException("原字节导回只读取已选副本source路径，不读取依赖或构建目录");if(Files.isSymbolicLink(root.toPath())||!root.getAbsoluteFile().equals(root.getCanonicalFile()))throw new SecurityException("工作副本目录含链接，未读取原字节");Path target=NativeCommandExecutor.within(root,path).toPath();final BasicFileAttributes before;
        try{before=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){current.check();return new Source(root,checkout,path,new byte[0],true);}
        if(!before.isRegularFile()||before.isSymbolicLink()||before.size()>ProjectSources.MAX_FILE_BYTES)throw new SecurityException("原字节审阅只支持2MiB内普通source文件，不跟随链接或截断");byte[] bytes;
        try(var input=Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS);var out=new ByteArrayOutputStream()){byte[] block=new byte[8192];int n;while((n=input.read(block))>=0){current.check();if(out.size()+n>ProjectSources.MAX_FILE_BYTES)throw new SecurityException("读取期间文件超过2MiB，未导回部分字节");out.write(block,0,n);}bytes=out.toByteArray();}
        BasicFileAttributes after=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!after.isRegularFile()||after.isSymbolicLink()||before.size()!=bytes.length||after.size()!=bytes.length||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw new SecurityException("原字节审阅期间文件变化，请重新读取");current.check();return new Source(root,checkout,path,bytes,false);
    }
    private static JSONObject preview(byte[] bytes)throws Exception{int n=Math.min(PREVIEW_BYTES,bytes.length);char[] hex=new char[n*2],digits="0123456789abcdef".toCharArray();for(int i=0;i<n;i++){hex[2*i]=digits[(bytes[i]&255)>>>4];hex[2*i+1]=digits[bytes[i]&15];}return new JSONObject().put("hex",new String(hex)).put("shownBytes",n).put("totalBytes",bytes.length).put("truncated",n<bytes.length);}
    public static boolean isReview(JSONObject value){return value!=null&&REVIEW.equals(value.optString("format"));}
    public static JSONObject review(Source source,VersionedWorkspace.FileSnapshot target,boolean baselineCurrent)throws Exception {
        boolean same=source.deleted==target.tombstone&&source.sha256.equals(target.sha256);return new JSONObject().put("format",REVIEW).put("checkoutId",source.checkoutId).put("projectId",target.projectId).put("path",source.path).put("binary",true).put("sourceKind",source.deleted?"tombstone":source.binary?"binary":"text").put("targetKind",target.kind).put("deleted",source.deleted).put("targetDeleted",target.tombstone).put("sourceBytes",source.sizeBytes).put("targetBytes",target.sizeBytes).put("expectedHash",source.sha256).put("expectedVersion",target.version).put("expectedTargetHash",target.sha256).put("expectedTargetRevisionId",target.revisionId).put("sourcePreview",preview(source.original)).put("targetPreview",preview(target.tombstone?new byte[0]:target.bytes())).put("previewLimitBytes",PREVIEW_BYTES).put("textDiff",false).put("contentAvailable",false).put("same",same).put("baselineNeedsRefresh",same&&!baselineCurrent).put("importable",!same||!baselineCurrent).put("atomicSourceFence",false).put("authorityAdded",false).put("message",same?baselineCurrent?"当前正式版本与所选副本原字节一致，无需重复保存":"当前正式版本与所选副本原字节一致；可明确只更新副本基线，不再写一个版本":"这是原字节身份与有限hex预览，不是文字差异或完整内容预览；核对源SHA、正式版本和目标SHA后，才明确导回，不运行或自动分享");
    }
    /** UI submission copying only the current original review identities. This marker is not permission. */
    public static JSONObject payload(JSONObject review)throws Exception{if(!isReview(review)||!Boolean.TRUE.equals(review.opt("importable")))throw new SecurityException("需要当前可导回的原字节审阅");JSONObject out=new JSONObject().put("byteReviewContract",REVIEW);for(String key:new String[]{"checkoutId","projectId","path","expectedHash","expectedTargetHash","expectedTargetRevisionId"})out.put(key,review.getString(key));Object version=review.get("expectedVersion");if(!(version instanceof Integer||version instanceof Long)||((Number)version).longValue()<0||((Number)version).longValue()>Integer.MAX_VALUE)throw new SecurityException("原字节审阅版本无效");return out.put("expectedVersion",version);}
    public static void requireInput(JSONObject input,Source source,VersionedWorkspace.FileSnapshot target)throws Exception {
        if(!REVIEW.equals(input.optString("byteReviewContract")))throw new SecurityException("请在原字节审阅入口明确确认，不借旧文字导回参数写二进制");for(String key:new String[]{"content","contentBase64","beforeContent","beforeContentBase64","newHex","bytes"})if(input.has(key))throw new SecurityException("原字节导回只取实际工作文件，不接受提交正文或Base64");if(!source.checkoutId.equals(input.getString("checkoutId"))||!source.path.equals(input.getString("path"))||!target.projectId.equals(input.getString("projectId"))||!source.sha256.equals(input.getString("expectedHash")))throw new SecurityException("原字节来源或所选项目已变化");requireTarget(input,target);
    }
    public static void requireTarget(JSONObject input,VersionedWorkspace.FileSnapshot target)throws Exception{Object raw=input.get("expectedVersion");if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()!=target.version||!target.sha256.equals(input.getString("expectedTargetHash"))||!target.revisionId.equals(input.getString("expectedTargetRevisionId")))throw new SecurityException("正式文件版本/SHA/修订身份已变化，请重新审阅，不覆盖新版本");}
}
