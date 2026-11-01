package dev.pocketengine.features.groups;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native authenticated-channel adapter. No public command may supply one of these proofs. */
public interface GroupIngressPort {
    Event authenticate(String authenticatedEventId) throws Exception;
    /** Local proof/session-epoch revalidation, including after process recovery. */
    void check(Event event) throws Exception;

    final class Event {
        public final String eventId,provider,accountId,roomId,senderId,messageId,transportEpoch,text;
        public final long receivedAt,replyUntil;
        private final JSONArray artifacts;
        private final JSONObject messageParts;
        public Event(String eventId,String provider,String accountId,String roomId,String senderId,
                     String messageId,String transportEpoch,String text,long receivedAt,long replyUntil,JSONArray artifacts) throws Exception {
            this(eventId,provider,accountId,roomId,senderId,messageId,transportEpoch,text,receivedAt,replyUntil,artifacts,null);
        }
        public Event(String eventId,String provider,String accountId,String roomId,String senderId,
                     String messageId,String transportEpoch,String text,long receivedAt,long replyUntil,JSONArray artifacts,JSONObject messageParts) throws Exception {
            this.messageParts=messageParts==null?new JSONObject():new JSONObject(messageParts.toString());
            if(this.messageParts.length()>0&&(!provider.equals(this.messageParts.optString("provider"))||!messageId.equals(this.messageParts.optString("messageId"))||this.messageParts.optJSONArray("parts")==null||this.messageParts.getJSONArray("parts").length()>64||this.messageParts.toString().length()>65536))throw new SecurityException("原群消息parts没有认证平台来源");
            this.eventId=GroupJson.id(eventId,"eventId");this.provider=GroupJson.id(provider,"provider");
            this.accountId=GroupJson.id(accountId,"accountId");this.roomId=GroupJson.id(roomId,"roomId");
            this.senderId=GroupJson.id(senderId,"senderId");this.messageId=GroupJson.id(messageId,"messageId");
            this.transportEpoch=GroupJson.id(transportEpoch,"transportEpoch");this.text=GroupJson.text(text,4000,"goal");
            if(receivedAt<1||replyUntil<receivedAt||replyUntil-receivedAt>86_400_000L)throw new IllegalArgumentException("群消息有效窗口无效");
            this.receivedAt=receivedAt;this.replyUntil=replyUntil;this.artifacts=new JSONArray();
            JSONArray supplied=artifacts==null?new JSONArray():artifacts;
            if(supplied.length()>8)throw new IllegalArgumentException("一次最多使用8个群附件");
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(int i=0;i<supplied.length();i++) {
                JSONObject row=supplied.getJSONObject(i);String artifactId=GroupJson.id(row,"artifactId");
                if(!seen.add(artifactId))throw new IllegalArgumentException("群附件身份重复");
                this.artifacts.put(new JSONObject().put("artifactId",artifactId).put("spaceId",GroupJson.id(row,"spaceId"))
                        .put("mimeType",GroupJson.text(row,"mimeType",120)).put("bytes",GroupJson.number(row,"bytes",0,20*1024*1024L)));
            }
        }
        public JSONArray artifacts() throws Exception {return GroupJson.copy(artifacts);}
        public JSONObject messageParts() throws Exception {return new JSONObject(messageParts.toString());}
        JSONObject json() throws Exception {return new JSONObject().put("eventId",eventId).put("provider",provider).put("accountId",accountId)
                .put("roomId",roomId).put("senderId",senderId).put("messageId",messageId).put("transportEpoch",transportEpoch)
                .put("text",text).put("receivedAt",receivedAt).put("replyUntil",replyUntil).put("artifacts",artifacts()).put("messageParts",messageParts());}
        static Event from(JSONObject v) throws Exception {return new Event(v.getString("eventId"),v.getString("provider"),v.getString("accountId"),
                v.getString("roomId"),v.getString("senderId"),v.getString("messageId"),v.getString("transportEpoch"),v.getString("text"),
                v.getLong("receivedAt"),v.getLong("replyUntil"),v.getJSONArray("artifacts"),v.optJSONObject("messageParts"));}
    }
}
