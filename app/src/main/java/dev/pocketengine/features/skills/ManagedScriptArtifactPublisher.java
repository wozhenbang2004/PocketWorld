package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

/** Immediate local publication through the existing AttachmentBook. The original skills_execute
 * metadata owns the one-use claim; no new artifact store, execution, network sender or authority. */
public final class ManagedScriptArtifactPublisher {
    @FunctionalInterface public interface OriginalJournal {
        OriginalPublication claim(String approvedPath,String sha256,long bytes)throws Exception;
    }
    /** Implemented only by the original native TaskStore. Scripts cannot construct its plan,
     * grant or retained controller, or choose a publication identity in their JSON request. */
    public interface OriginalPublication {
        String taskId();String runId();String operationId();String planDigest();String path();
        String role();String sha256();String attachmentId();long bytes();boolean mayImport();
        void checkCurrent()throws Exception;
        JSONObject finish(AttachmentBook.VerifiedMetadata actual)throws Exception;
    }
    private final AttachmentBook book;
    private final OriginalJournal journal;
    private final RunControl original;
    public ManagedScriptArtifactPublisher(AttachmentBook book,OriginalJournal journal,RunControl original){
        this.book=Objects.requireNonNull(book);this.journal=Objects.requireNonNull(journal);this.original=Objects.requireNonNull(original);
    }
    /** Exact existing native-result route. Digest is checked by the Book, not part of its ID:
     * a changed file or deleted/uncertain record cannot obtain a replacement publication. */
    public static String attachmentId(String operation,String role,String task,String run){
        return UUID.nameUUIDFromBytes(("pocketworld-native-result-v1\0"+operation+"\0"+role+"\0"+task+"\0"+run).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public JSONObject share(String path,byte[] exactBytes)throws Exception {
        Objects.requireNonNull(exactBytes);original.check();String sha=SkillExecution.sha256(exactBytes);
        OriginalPublication publication=Objects.requireNonNull(journal.claim(path,sha,exactBytes.length));
        // The durable claim is already entered. Any later failure is query-only, including
        // an absent/deleted Book record; never rerun import under the same original slot.
        try{
            publication.checkCurrent();
            if(!path.equals(publication.path())||!sha.equals(publication.sha256())||exactBytes.length!=publication.bytes()
                    ||!publication.attachmentId().equals(attachmentId(publication.operationId(),publication.role(),publication.taskId(),publication.runId())))throw new SecurityException("原脚本成果发布身份与固定字节不一致");
            JSONObject actual=book.findNativeResult(publication.operationId(),publication.role(),publication.taskId(),publication.runId(),publication.planDigest(),original);
            if(!actual.getBoolean("found")){
                if(!publication.mayImport())return unknown(publication,"original_publication_absent_or_retired");
                publication.checkCurrent();
                JSONObject source=new JSONObject().put("kind","managed_build_output").put("taskId",publication.taskId()).put("runId",publication.runId())
                        .put("operationId",publication.operationId()).put("planDigest",publication.planDigest()).put("outputPath",publication.path())
                        .put("producer","original_managed_skill").put("untrusted",true).put("verifiedBusinessSuccess",false);
                String name=path.substring(path.lastIndexOf('/')+1);
                actual=book.importNativeResult(new ByteArrayInputStream(exactBytes),name,"application/octet-stream",source,publication.operationId(),publication.role(),publication.taskId(),publication.runId(),publication.planDigest(),original);
            }
            if(!publication.attachmentId().equals(actual.optString("id"))||!sha.equals(actual.optString("sha256"))||actual.optLong("size",-1)!=exactBytes.length)throw new SecurityException("原成果发布没有相同完整字节回执");
            AttachmentBook.VerifiedMetadata verified=book.readVerifiedMetadata(publication.attachmentId(),sha,original);
            publication.checkCurrent();JSONObject recorded=publication.finish(verified);
            return new JSONObject().put("ok",true).put("accepted",true).put("status","published").put("path",path).put("sha256",sha).put("bytes",verified.size)
                    .put("shared",true).put("delivery","original_private_task_attachment").put("artifact",new JSONObject().put("id",verified.id).put("sha256",verified.sha256).put("name",verified.name).put("mimeType",verified.mimeType).put("size",verified.size))
                    .put("originalPublication",recorded).put("externalTransmission",false).put("executionAuthorized",false).put("verifiedBusinessSuccess",false);
        }catch(Exception unavailable){return unknown(publication,"original_publication_unconfirmed");}
    }
    private static JSONObject unknown(OriginalPublication publication,String reason)throws Exception {
        return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("status","unknown").put("code","script_share_unknown")
                .put("shared",JSONObject.NULL).put("attachmentId",publication.attachmentId()).put("path",publication.path()).put("sha256",publication.sha256())
                .put("reason",reason).put("replayAllowed",false).put("externalTransmission",false).put("executionAuthorized",false);
    }
}
