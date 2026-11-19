package dev.pocketengine.features.remote;

import java.util.Collection;
import org.json.JSONObject;

/** Read-only capacity of one originally bound job when it was queried. No host-wide numbers, reservations or grants. */
public final class RemoteSnapshotRoom {
    private RemoteSnapshotRoom(){}
    private static final int MAX_COUNT=4, MAX_TOTAL=1_048_576, MAX_CAPTURE=524_288, MAX_FILE=262_144;
    private static final String PROTOCOL="original_result_snapshot_room_v1";
    private static int count(JSONObject row,String key,int max){Object n=row.opt(key);if(!(n instanceof Integer||n instanceof Long))return -1;long value=((Number)n).longValue();return value<0||value>max?-1:(int)value;}
    /** Old or incomplete protocols do not imply an empty job; the existing server check remains authoritative. */
    public static JSONObject observed(JSONObject response,JSONObject source)throws Exception{
        JSONObject room=response==null?null:response.optJSONObject("snapshotRoom");if(room==null||source==null||!PROTOCOL.equals(room.opt("protocol")))return null;
        for(String key:new String[]{"jobId","jobOperationId","environmentId","worker","workspaceIdentity"}){
            String reported=key.equals("jobOperationId")?"operationId":key.equals("jobId")&&response.has("id")?"id":key;
            if(!(source.opt(key) instanceof String here)||here.isEmpty()||!here.equals(room.opt(key))||!here.equals(response.opt(reported)))return null;
        }
        if(!source.getString("workspaceIdentity").matches("[a-f0-9]{64}"))return null;
        int used=count(room,"retainedCount",MAX_COUNT),bytes=count(room,"retainedBytes",MAX_TOTAL);
        if(count(room,"maximumCount",MAX_COUNT)!=MAX_COUNT||count(room,"maximumBytes",MAX_TOTAL)!=MAX_TOTAL||count(room,"captureMaximumBytes",MAX_CAPTURE)!=MAX_CAPTURE||used<0||bytes<0||bytes>(long)used*MAX_CAPTURE)return null;
        JSONObject out=new JSONObject().put("protocol",PROTOCOL).put("maximumCount",MAX_COUNT).put("maximumBytes",MAX_TOTAL).put("captureMaximumBytes",MAX_CAPTURE)
            .put("retainedCount",used).put("retainedBytes",bytes).put("remainingCount",MAX_COUNT-used).put("remainingBytes",MAX_TOTAL-bytes);
        for(String key:new String[]{"jobId","jobOperationId","environmentId","worker","workspaceIdentity"})out.put(key,source.getString(key));return out;
    }
    /** A typed path with no byte observation can only be blocked on exhausted slots. Empty originals need zero bytes. */
    public static void requireRoom(JSONObject result,JSONObject source,Long knownBytes)throws Exception{
        if(knownBytes!=null&&(knownBytes<0||knownBytes>MAX_CAPTURE))throw new IllegalArgumentException("所选文件合计超过单次512 KiB，未读取或提交新快照");
        JSONObject room=observed(result,source);if(room==null)return;
        if(room.getInt("remainingCount")==0)throw new IllegalStateException("原主机上次核对时这次作业的4份快照已满。先看已冻结版本，或重新查询原作业；未再提交");
        if(knownBytes!=null&&knownBytes>room.getInt("remainingBytes"))throw new IllegalStateException("所选文件超过该原作业上次核对的剩余字节。请选择较小范围或读取已有快照；未再提交");
    }
    public static long selectionBytes(Collection<JSONObject> selected,long extra)throws Exception{
        if(extra<0||extra>MAX_FILE)throw new IllegalArgumentException("目录中的文件不在单件256 KiB范围");long sum=extra;
        for(JSONObject file:selected){int n=count(file,"bytes",MAX_FILE);if(n<0)throw new IllegalStateException("已选原件大小无法核对，请重新选择");sum+=n;}return sum;
    }
}
