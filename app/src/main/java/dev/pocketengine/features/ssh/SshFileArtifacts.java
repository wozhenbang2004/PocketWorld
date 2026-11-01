package dev.pocketengine.features.ssh;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentsModule;
import java.io.FileInputStream;
import java.io.InputStream;
import org.json.JSONObject;

/** Native-owner bridge to the original private attachment book. Binary bodies never cross JSON. */
final class SshFileArtifacts {
    private final AttachmentBook book;
    private final AttachmentsModule ownerFiles;
    SshFileArtifacts(AttachmentBook book,AttachmentsModule ownerFiles){this.book=java.util.Objects.requireNonNull(book);this.ownerFiles=java.util.Objects.requireNonNull(ownerFiles);}
    JSONObject download(DirectSshClient.Connection connection,SshProfile profile,String task,String operation,JSONObject input,RunControl control)throws Exception {
        String path=SshProfile.relative(SshProfile.text(input,"path",1024),false),expected=SshProfile.text(input,"expectedVersion",80);
        try(DirectSshClient.Connection.BinaryRead source=connection.openBinaryRead(path,expected)){
            JSONObject origin=new JSONObject().put("kind","ssh_download").put("taskId",task).put("operationId",operation).put("profileId",profile.id).put("profileBinding",profile.bindingHash())
                    .put("path",path).put("expectedVersion",expected).put("remoteAtomicSnapshot",false).put("authorshipVerified",false);
            JSONObject meta=book.importBytes(source,path.substring(path.lastIndexOf('/')+1),"application/octet-stream",origin,control);
            JSONObject result=source.receipt().put("artifactSaved",true).put("attachment",meta).put("verifiedBusinessSuccess",false).put("automaticRetry",false)
                    .put("message","所选远端版本的实际文件已流式校验并保存为私人附件；不证明由某个job创作、最新远端HEAD未再变化或业务测试成功");
            if(!expected.equals("sha256:"+meta.getString("sha256")))throw new SecurityException("本机冻结摘要与所选远端版本不一致");return result;
        }catch(DirectSshClient.InputChanged changed){return DirectSshClient.receipt("failed").put("accepted",false).put("artifactSaved",false).put("code","version_conflict").put("path",path).put("expectedVersion",expected).put("message",changed.getMessage()).put("automaticRetry",false);}
    }
    JSONObject upload(DirectSshClient.Connection connection,JSONObject input,RunControl control)throws Exception {
        String id=SshProfile.text(input,"attachmentId",80),expected=SshProfile.text(input,"expectedSha256",64),path=SshProfile.relative(SshProfile.text(input,"path",1024),false),remote=SshProfile.text(input,"expectedVersion",80);
        if(!expected.matches("[a-f0-9]{64}"))throw new SecurityException("需要原手机附件的准确SHA256");ownerFiles.requireOwnerAttachment(id);JSONObject selected=book.get(id);checkRecord(selected,id,expected);
        long size=selected.getLong("size");if(size<0||size>DirectSshClient.MAX_TRANSFER)throw new IllegalArgumentException("所选附件超过20MiB本次SSH传输上限");
        RunControl.AuthorityCheck current=()->{ownerFiles.requireOwnerAttachment(id);JSONObject actual=book.get(id);checkRecord(actual,id,expected);if(actual.getLong("size")!=size||!actual.getString("mimeType").equals(selected.getString("mimeType")))throw new SecurityException("原附件元数据或私人范围已变化");};
        try(AutoCloseable retained=control.retainAuthorityCheck(current)){
            current.check();book.readVerifiedMetadata(id,expected,control);
            try(InputStream bytes=new FileInputStream(book.file(id))){JSONObject result=connection.uploadBinary(path,bytes,size,expected,remote,input.optBoolean("acceptCooperativeWrite"),current,()->{current.check();book.readVerifiedMetadata(id,expected,control);});return result.put("sourceAttachment",new JSONObject().put("id",id).put("sha256",expected).put("bytes",size).put("mimeType",selected.getString("mimeType")));}
        }
    }
    private static void checkRecord(JSONObject actual,String id,String digest)throws Exception {
        JSONObject source=actual.optJSONObject("source");if(!actual.getString("id").equals(id)||!actual.optString("principalId").equals("owner")||!actual.optString("spaceId").equals("private")||!digest.equals(actual.getString("sha256"))||source!=null&&source.has("groupSpaceId"))throw new SecurityException("只能上传主人显选的当前私人附件，不能以ID借用其他群或旧版本");
    }
}
