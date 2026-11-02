package dev.pocketengine.features.groups;

import org.json.JSONObject;

/** Official-channel outbox/media adapter. Never use a caller-supplied URL or file path. */
public interface GroupDeliveryPort {
    boolean supports(String provider,String accountId,String audience,String mimeType) throws Exception;
    default boolean supports(GroupIngressPort.Event event,String audience,String mimeType)throws Exception{return supports(event.provider,event.accountId,audience,mimeType);}
    /** Call authority.beforeDelivery at physical dispatch outside all channel/registry monitors. */
    Receipt send(Delivery delivery,GroupHostPort.Authority authority) throws Exception;
    /** Query only, including a null receipt. No automatic retransmit after an unknown send. */
    Receipt inspect(Delivery delivery,GroupHostPort.Authority authority) throws Exception;
    /** Remaining slots in this authenticated reply context; no reservation or dispatch. */
    default int remainingReplies(GroupIngressPort.Event event,GroupHostPort.Authority authority)throws Exception {authority.checkRead();return 8;}

    final class Delivery {
        public final String deliveryId,requestId,taskId,audience,senderId,kind;
        /** Process-local first dispatch proof; loading a persisted intent never creates it. */
        public final boolean newlyPrepared;
        public final GroupIngressPort.Event event;
        public final GroupHostPort.Output output;
        Delivery(JSONObject row) throws Exception {
            this(row,false);
        }
        Delivery(JSONObject row,boolean newlyPrepared) throws Exception {
            this.newlyPrepared=newlyPrepared;
            deliveryId=row.getString("deliveryId");requestId=row.getString("requestId");taskId=row.getString("taskId");audience=row.getString("audience");
            senderId=row.getString("senderId");kind=row.optString("kind","result");event=GroupIngressPort.Event.from(row.getJSONObject("event"));output=GroupHostPort.Output.from(row.getJSONObject("output"));
        }
    }
    final class Receipt {
        public final String deliveryId,state,platformMessageId;
        public Receipt(String deliveryId,String state,String platformMessageId) {
            this.deliveryId=GroupJson.id(deliveryId,"deliveryId");this.state=GroupJson.one(state,"投递状态","accepted","delivered","unknown","failed");
            this.platformMessageId=platformMessageId==null?"":platformMessageId;
            if(!this.platformMessageId.isEmpty())GroupJson.id(this.platformMessageId,"platformMessageId");
            if(state.equals("delivered")&&this.platformMessageId.isEmpty())throw new IllegalArgumentException("送达回执缺少平台消息身份");
        }
        public JSONObject json() throws Exception {return new JSONObject().put("deliveryId",deliveryId).put("state",state).put("platformMessageId",platformMessageId);}
    }
}
