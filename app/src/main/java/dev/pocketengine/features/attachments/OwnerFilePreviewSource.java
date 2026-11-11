package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import org.json.JSONObject;

/** Original native host-issued byte reader. It is neither a file URI nor a JSON permission.
 * Each read rechecks its real task/source/audience and the same immutable bytes. */
public final class OwnerFilePreviewSource {
    interface Read {byte[] read(RunControl control)throws Exception;}
    interface Current {void check(RunControl control)throws Exception;}
    interface Copy {void write(java.io.OutputStream destination,RunControl control)throws Exception;}
    private final JSONObject metadata;private final Read read;private final Current current;private final Copy copy;
    public final String identity,sha256,mimeType,name;public final long sizeBytes;public final boolean tombstone;
    OwnerFilePreviewSource(String provider,JSONObject original,Read read,Current current)throws Exception {
        this(provider,original,read,current,null);
    }
    OwnerFilePreviewSource(String provider,JSONObject original,Read read,Current current,Copy copy)throws Exception {
        this.copy=copy;
        this.metadata=ProjectSources.copy(original);this.metadata.remove("content");this.metadata.remove("contentBase64");this.read=java.util.Objects.requireNonNull(read);this.current=java.util.Objects.requireNonNull(current);
        tombstone=original.optBoolean("tombstone")||"file_tombstone".equals(original.optString("kind"));sha256=original.getString("sha256");mimeType=original.optString("mimeType","application/octet-stream");name=original.optString("name",original.optString("path","原文件"));sizeBytes=original.has("sizeBytes")?original.getLong("sizeBytes"):original.getLong("size");
        if(tombstone?(!sha256.equals("absent")||sizeBytes!=0):(!sha256.matches("[a-f0-9]{64}")||sizeBytes<0||sizeBytes>AttachmentBook.MAX_FILE_BYTES))throw new SecurityException("原文件预览版本或字节范围无效");
        identity=ProjectSources.digest(new JSONObject().put("provider",provider).put("original",metadata));
    }
    public JSONObject metadata()throws Exception{return ProjectSources.copy(metadata);}
    public void requireCurrent(RunControl control)throws Exception{java.util.Objects.requireNonNull(control).check();current.check(control);control.check();}
    /** Native private destination only. No URI or path is accepted. The destination may
     * contain partial bytes on failure and must not be published before this returns.
     * The caller owns/cleans/closes it; this method never closes the destination. */
    public long copyTo(java.io.OutputStream destination,RunControl control)throws Exception {
        java.util.Objects.requireNonNull(destination);requireCurrent(control);
        if(tombstone)throw new IllegalStateException("这是原删除修订，没有文件正文；未用空文件冒充");
        if(copy==null){byte[] value=bytes(control);try{for(int at=0;at<value.length;){control.check();int n=Math.min(32768,value.length-at);destination.write(value,at,n);at+=n;}requireCurrent(control);return value.length;}finally{java.util.Arrays.fill(value,(byte)0);}}
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long[] count={0};
        java.io.OutputStream bounded=new java.io.OutputStream(){
            @Override public void write(int value)throws java.io.IOException{write(new byte[]{(byte)value},0,1);}
            @Override public void write(byte[] value,int offset,int length)throws java.io.IOException{
                java.util.Objects.checkFromIndexSize(offset,length,value.length);
                try{control.check();}catch(InterruptedException stopped){throw new java.io.IOException("原文件读取已中断",stopped);}if(length>sizeBytes-count[0])throw new SecurityException("原预览流超过所选版本长度");
                destination.write(value,offset,length);digest.update(value,offset,length);count[0]+=length;
            }
        };
        copy.write(bounded,control);StringBuilder actual=new StringBuilder();for(byte value:digest.digest())actual.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));
        if(count[0]!=sizeBytes||!sha256.contentEquals(actual))throw new SecurityException("原预览流与所选版本不同");
        requireCurrent(control);return count[0];
    }
    public byte[] bytes(RunControl control)throws Exception {
        requireCurrent(control);if(tombstone)throw new IllegalStateException("这是原删除修订，没有文件正文；未用空文件冒充");byte[] value=read.read(control);
        try{if(value==null||value.length!=sizeBytes||!sha256.equals(ProjectSources.sha256(value)))throw new SecurityException("原预览字节与所选版本不同");requireCurrent(control);return value;}catch(Exception invalid){if(value!=null)java.util.Arrays.fill(value,(byte)0);throw invalid;}
    }
}
