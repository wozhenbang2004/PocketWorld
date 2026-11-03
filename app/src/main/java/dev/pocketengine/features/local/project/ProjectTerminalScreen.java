package dev.pocketengine.features.local.project;

import dev.pocketengine.features.ssh.SshTerminalScreen;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adapts the original process sequence pages to the existing bounded VT display. No I/O or input. */
public final class ProjectTerminalScreen {
    private final String execution;private final SshTerminalScreen screen=new SshTerminalScreen();
    private long cursor;private int columns,rows;private boolean truncated,incomplete;
    public ProjectTerminalScreen(String execution,int columns,int rows){this.execution=java.util.Objects.requireNonNull(execution);resize(columns,rows);}
    public static void requireDimensions(int columns,int rows){if(columns<20||columns>300||rows<5||rows>120)throw new IllegalArgumentException("此终端视图支持20至300列、5至120行");}
    public synchronized void resize(int columns,int rows){requireDimensions(columns,rows);this.columns=columns;this.rows=rows;}
    public synchronized long cursor(){return cursor;}
    public synchronized JSONObject append(JSONObject page)throws Exception {
        if(!execution.equals(page.getString("operationId")))throw new SecurityException("日志不是原手动终端");JSONArray chunks=page.getJSONArray("chunks");if(chunks.length()>32768)throw new SecurityException("原终端页超出范围");
        long next=cursor;boolean gap=false;ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int i=0;i<chunks.length();i++){
            JSONObject chunk=chunks.getJSONObject(i);long sequence=integer(chunk,"sequence"),count=integer(chunk,"bytes"),stream=integer(chunk,"stream");byte[] actual=Base64.getDecoder().decode(chunk.getString("dataBase64"));
            if(sequence<=next||count<1||count>8192||count!=actual.length||stream<1||stream>2||bytes.size()+count>32768)throw new SecurityException("原终端日志范围或字节不可核对");gap|=sequence!=next+1;next=sequence;bytes.write(actual);
        }
        if(integer(page,"nextCursor")!=next)throw new SecurityException("原终端页cursor不对应实际日志");
        byte[] actual=bytes.toByteArray();screen.append(Base64.getEncoder().encodeToString(actual),screen.cursor(),screen.cursor()+actual.length,gap);cursor=next;truncated|=page.optBoolean("outputTruncated");incomplete=page.optBoolean("logIncompleteTail");
        return new JSONObject().put("executionId",execution).put("outputCursor",cursor).put("screen",screen.view(columns,rows)).put("outputTruncated",truncated).put("logIncompleteTail",incomplete).put("hasMore",page.optBoolean("hasMore"));
    }
    private static long integer(JSONObject row,String key)throws Exception{Object raw=row.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0)throw new SecurityException("原终端日志数字无效");return ((Number)raw).longValue();}
}
