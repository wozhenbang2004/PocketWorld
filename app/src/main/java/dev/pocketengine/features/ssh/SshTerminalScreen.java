package dev.pocketengine.features.ssh;

import java.util.ArrayDeque;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded actual PTY bytes for a mature VT renderer, never a command queue. */
public final class SshTerminalScreen {
    public static final int MAX_BYTES=262144;
    private final ArrayDeque<byte[]> packets=new ArrayDeque<>();
    private long cursor,revision,generation;private int bytes;private boolean gap;
    public synchronized boolean append(String encoded,long expectedCursor,long nextCursor,boolean truncated)throws Exception {
        if(expectedCursor!=cursor||nextCursor<cursor)throw new IllegalArgumentException("原PTY输出位置已变化");
        byte[] value=Base64.getDecoder().decode(encoded);if(value.length>65536||nextCursor-expectedCursor<value.length||!truncated&&nextCursor-expectedCursor!=value.length)throw new IllegalArgumentException("原PTY字节范围不符");
        if(truncated){packets.clear();bytes=0;gap=true;generation++;}
        if(value.length==0&&!truncated){cursor=nextCursor;return false;}
        if(packets.size()>=128||bytes+value.length>MAX_BYTES){packets.clear();bytes=0;gap=true;generation++;}
        if(value.length>0){packets.add(value);bytes+=value.length;}cursor=nextCursor;revision++;return true;
    }
    public synchronized JSONObject view(int cols,int rows)throws Exception {
        if(cols<20||cols>300||rows<5||rows>120)throw new IllegalArgumentException("终端屏幕尺寸无效");
        JSONArray encoded=new JSONArray();for(byte[] packet:packets)encoded.put(Base64.getEncoder().encodeToString(packet));
        return new JSONObject().put("revision",revision).put("generation",generation).put("cols",cols).put("rows",rows).put("packets",encoded).put("retainedBytes",bytes).put("gap",gap)
                .put("screenState",gap?"reconstructed_from_retained_prefix_after_gap":"parsed_actual_pty_bytes");
    }
    public synchronized long cursor(){return cursor;}
}
