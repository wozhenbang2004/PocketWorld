package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import org.json.JSONObject;

/** Original native host-issued byte reader. It is neither a file URI nor a JSON permission.
 * Each read rechecks its real task/source/audience and the same immutable bytes. */
public final class OwnerFilePreviewSource {
    interface Read {byte[] read(RunControl control)throws Exception;}
    interface Current {void check(RunControl control)throws Exception;}
    private final JSONObject metadata;private final Read read;private final Current current;
    public final String identity,sha256,mimeType,name;public final long sizeBytes;public final boolean tombstone;
    OwnerFilePreviewSource(String provider,JSONObject original,Read read,Current current)throws Exception {
        this.metadata=ProjectSources.copy(original);this.metadata.remove("content");this.metadata.remove("contentBase64");this.read=java.util.Objects.requireNonNull(read);this.current=java.util.Objects.requireNonNull(current);
        tombstone=original.optBoolean("tombstone")||"file_tombstone".equals(original.optString("kind"));sha256=original.getString("sha256");mimeType=original.optString("mimeType","application/octet-stream");name=original.optString("name",original.optString("path","原文件"));sizeBytes=original.has("sizeBytes")?original.getLong("sizeBytes"):original.getLong("size");
        if(tombstone?(!sha256.equals("absent")||sizeBytes!=0):(!sha256.matches("[a-f0-9]{64}")||sizeBytes<0||sizeBytes>AttachmentBook.MAX_FILE_BYTES))throw new SecurityException("原文件预览版本或字节范围无效");
        identity=ProjectSources.digest(new JSONObject().put("provider",provider).put("original",metadata));
    }
    public JSONObject metadata()throws Exception{return ProjectSources.copy(metadata);}
    public void requireCurrent(RunControl control)throws Exception{java.util.Objects.requireNonNull(control).check();current.check(control);control.check();}
    public byte[] bytes(RunControl control)throws Exception {
        requireCurrent(control);if(tombstone)throw new IllegalStateException("这是原删除修订，没有文件正文；未用空文件冒充");byte[] value=read.read(control);
        try{if(value==null||value.length!=sizeBytes||!sha256.equals(ProjectSources.sha256(value)))throw new SecurityException("原预览字节与所选版本不同");requireCurrent(control);return value;}catch(Exception invalid){if(value!=null)java.util.Arrays.fill(value,(byte)0);throw invalid;}
    }
}
