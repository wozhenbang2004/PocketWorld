package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** The complete text from one explicit original Python output read, not the original pipe bytes. */
public final class OwnerPythonOutputText implements AutoCloseable {
    public static final String ROLE="owner_python_output_text", EVIDENCE="original_owner_python_output_text";
    private static final String FORMAT="pocketworld.owner-python-output-text.v1", MIME="text/plain";
    private static final int MAX_RAW=65536, MAX_TEXT=MAX_RAW+1024;
    private final Pin pin;
    private byte[] bytes;

    private OwnerPythonOutputText(Pin pin,byte[] bytes){this.pin=pin;this.bytes=bytes;}

    /** Called only with the native complete, exact, rechecked pipe read. Invalid text stays view-only. */
    static OwnerPythonOutputText capture(JSONObject original,JSONObject exit,byte[] stdout,byte[] stderr)throws Exception{
        String out,err;
        try{
            out=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(stdout)).toString();
            err=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(stderr)).toString();
        }catch(CharacterCodingException nonText){return null;}
        if(out.indexOf('\0')>=0||err.indexOf('\0')>=0)return null;
        byte[] snapshot=("Python 运行输出 · 完整留存文字\n脚本："+original.getString("path")+"\n脚本保存版本 SHA-256："+
                original.getString("sha256")+"\n原运行："+original.getString("runId")+"\n退出："+
                ("exit_code".equals(exit.opt("exitKind"))?"退出码 ":"信号 ")+exit.getInt("value")+
                "\nUTF-8 文字视图；两路输出分别列出，不表示发生顺序。\n\n[stdout · 程序输出]\n"+out+
                "\n\n[stderr · 错误输出]\n"+err+"\n").getBytes(StandardCharsets.UTF_8);
        boolean retained=false;
        try{
            JSONObject facts=pinFacts(original,exit,stdout.length,stderr.length,ProjectSources.sha256(snapshot),snapshot.length);
            OwnerPythonOutputText result=new OwnerPythonOutputText(new Pin(facts),snapshot);retained=true;return result;
        }finally{if(!retained)Arrays.fill(snapshot,(byte)0);}
    }

    private static long integer(JSONObject object,String key)throws Exception{
        Object value=object.get(key);
        if(!(value instanceof Integer||value instanceof Long))throw new SecurityException("原 TXT 字节范围不可核对");
        return ((Number)value).longValue();
    }

    private static JSONObject pinFacts(JSONObject original,JSONObject exit,long out,long err,String sha,long size)throws Exception{
        String task=original.getString("taskId"),run=original.getString("runId"),path=original.getString("path");
        if(!task.matches("[a-f0-9-]{36}")||!task.equals(run)||!task.equals(original.opt("operationId"))||
                integer(original,"epoch")!=1||!OwnerPythonRun.supportsPath(path)||!sha.matches("[a-f0-9]{64}")||
                out<0||out>MAX_RAW||err<0||err>MAX_RAW-out||out+err==0||size<out+err||size>MAX_TEXT||
                !exit.optString("exitKind").matches("exit_code|signal"))throw new SecurityException("没有可保存的原本机 Python 文字输出");
        String leaf=OwnerPythonRun.scriptLeaf(path);
        JSONObject result=new JSONObject().put("format",FORMAT).put("coverage","complete")
                .put("exitDigest",ProjectSources.digest(exit)).put("stdoutBytes",out).put("stderrBytes",err)
                .put("sha256",sha).put("size",size).put("name",leaf.substring(0,leaf.length()-3)+"-输出-"+run.substring(0,8)+".txt")
                .put("scriptSha256",original.getString("sha256"));
        for(String key:new String[]{"taskId","runId","operationId","epoch","projectId","checkoutId","path","scriptInputDigest","planDigest"})
            result.put(key,original.get(key));
        return result;
    }

    public Pin pin(){return pin;}
    @Override public synchronized void close(){if(bytes!=null){Arrays.fill(bytes,(byte)0);bytes=null;}}

    /** One currently held native snapshot. A ledger or Book lookup can never reconstruct it. */
    public Published publish(AttachmentBook book,RunControl control)throws Exception{
        byte[] input;
        synchronized(this){if(bytes==null)throw new SecurityException("本次文字已保存或原阅读已结束");input=bytes;bytes=null;}
        try{
            if(input.length!=pin.facts.getInt("size")||!pin.facts.getString("sha256").equals(ProjectSources.sha256(input)))
                throw new SecurityException("本次文字与原阅读记录不同");
            JSONObject registered=book.importNativeResult(new ByteArrayInputStream(input),pin.facts.getString("name"),MIME,pin.source(),
                    pin.operationId(),ROLE,pin.taskId(),pin.runId(),pin.digest,control);
            pin.checkedArtifact(registered);
            Published observed=pin.find(book,control);
            if(observed==null)throw new IllegalStateException("本次 TXT 保存尚待核对，不会再次写入");
            return observed;
        }finally{Arrays.fill(input,(byte)0);}
    }

    public static boolean claims(JSONObject source){
        JSONObject published=source==null?null:source.optJSONObject("nativePublication");
        return source!=null&&(ROLE.equals(source.opt("kind"))||source.has("originalPythonText")||
                published!=null&&ROLE.equals(published.opt("role")));
    }
    public static boolean resultRow(JSONObject row){
        return row!=null&&ROLE.equals(row.opt("sourceKind"))&&EVIDENCE.equals(row.opt("sourceRunEvidence"));
    }

    /** Only a real Book readback can create this local publication proof. */
    public static final class Published {
        private final Pin pin;
        private final JSONObject file;
        private Published(Pin pin,JSONObject file){this.pin=pin;this.file=file;}
        public JSONObject fileFor(Pin original)throws Exception{
            if(!pin.same(original))throw new SecurityException("不是这次原 TXT 保存的回执");
            return ProjectSources.copy(file);
        }
    }

    /** All state here is body-free. Only the original terminal/private Work can validate a slot. */
    public static final class Pin {
        private final JSONObject facts;
        private final String digest;
        private Pin(JSONObject facts)throws Exception{this.facts=facts;digest=ProjectSources.digest(facts);}
        public static Pin verified(JSONObject saved,JSONObject original,JSONObject exit)throws Exception{
            JSONObject exact=pinFacts(original,exit,integer(saved,"stdoutBytes"),integer(saved,"stderrBytes"),
                    saved.getString("sha256"),integer(saved,"size"));
            if(!ProjectSources.canonical(exact).equals(ProjectSources.canonical(saved)))
                throw new SecurityException("原文字不属于这次脚本及退出回执");
            return new Pin(exact);
        }
        public String taskId()throws Exception{return facts.getString("taskId");}
        public String runId()throws Exception{return facts.getString("runId");}
        private String operationId()throws Exception{return facts.getString("operationId");}
        public JSONObject facts()throws Exception{return ProjectSources.copy(facts);}
        public boolean same(Pin original){return original!=null&&digest.equals(original.digest);}
        public JSONObject offer()throws Exception{return new JSONObject().put("state","ready").put("coverage","complete")
                .put("name",facts.getString("name")).put("sha256",facts.getString("sha256")).put("size",facts.getInt("size"));}

        private JSONObject source()throws Exception{
            return new JSONObject().put("kind",ROLE).put("taskId",taskId()).put("runId",runId()).put("operationId",operationId())
                    .put("planDigest",facts.getString("planDigest")).put("originalPythonText",facts());
        }
        public Published find(AttachmentBook book,RunControl control)throws Exception{
            JSONObject actual=book.findNativeResult(operationId(),ROLE,taskId(),runId(),digest,control);
            if(!Boolean.TRUE.equals(actual.opt("found")))return null;
            return new Published(this,checkedArtifact(actual));
        }
        public JSONObject checkedArtifact(JSONObject actual)throws Exception{
            JSONObject registered=source().put("nativePublication",new JSONObject().put("operationId",operationId())
                    .put("role",ROLE).put("taskId",taskId()).put("runId",runId()).put("planDigest",digest));
            if(!"owner".equals(actual.opt("principalId"))||!"private".equals(actual.opt("spaceId"))||actual.optBoolean("outputSourceRetired")||
                    !MIME.equals(actual.opt("mimeType"))||!facts.getString("name").equals(actual.opt("name"))||
                    !facts.getString("sha256").equals(actual.opt("sha256"))||integer(actual,"size")!=integer(facts,"size")||
                    !ProjectSources.canonical(registered).equals(ProjectSources.canonical(actual.optJSONObject("source")))||
                    !AttachmentBook.isOriginalPublicationId(actual.getString("id"),operationId(),ROLE,taskId(),runId(),digest))
                throw new SecurityException("这份 TXT 不是本次原运行的固定文件");
            return new JSONObject().put("id",actual.getString("id")).put("name",facts.getString("name")).put("mimeType",MIME)
                    .put("size",facts.getInt("size")).put("sha256",facts.getString("sha256")).put("sourceKind",ROLE)
                    .put("sourceTaskId",taskId()).put("sourceTaskRunId",runId()).put("sourceCoordinatorEpoch",1)
                    .put("sourceOperationId",operationId()).put("sourceRunEvidence",EVIDENCE).put("role","result").put("availability","available");
        }
    }
}
