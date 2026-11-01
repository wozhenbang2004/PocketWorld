package dev.pocketengine.features.attachments.processing;

import dev.pocketengine.features.local.project.ProjectSources;
import org.json.JSONObject;

/** Real processor result, not a source grant or publication ticket. A scoped original writer
 * must independently approve and retain task/audience before persisting or sending it. */
public final class ProcessedOriginalFile {
    private final byte[] bytes;private final JSONObject evidence;
    public final String sha256,mimeType,extension,sourceIdentity,sourceSha256,planDigest;
    ProcessedOriginalFile(byte[] actual,String mime,String extension,String originalIdentity,String originalSha,String plan,JSONObject measured)throws Exception{
        if(actual==null||actual.length<1||actual.length>20*1024*1024)throw new IllegalArgumentException("加工结果为空或超过20MiB，未发布部分文件");bytes=actual.clone();sha256=ProjectSources.sha256(bytes);mimeType=mime;this.extension=extension;sourceIdentity=originalIdentity;sourceSha256=originalSha;planDigest=plan;evidence=ProjectSources.copy(measured).put("sha256",sha256).put("bytes",bytes.length).put("mimeType",mime).put("sourceIdentity",originalIdentity).put("sourceSha256",originalSha).put("planDigest",plan).put("sourceOverwritten",false).put("automaticallyShared",false);
    }
    public byte[] bytes(){return bytes.clone();}public JSONObject evidence()throws Exception{return ProjectSources.copy(evidence);}
}
