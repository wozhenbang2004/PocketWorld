package dev.pocketengine.features.assistant;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ordering within one existing worker scan, not a scheduler or an execution queue. */
public final class ConversationPhoneDeliveryPass {
    @FunctionalInterface public interface Publisher {JSONObject publish(String task,String run,int epoch)throws Exception;}
    private final Set<String> deferred=new HashSet<>();
    public void begin(){deferred.clear();}
    public void publish(JSONArray entries,Publisher publisher,BooleanSupplier current)throws Exception {
        for(int i=0;i<entries.length();i++){
            if(!current.getAsBoolean())return;
            JSONObject entry=entries.getJSONObject(i);String conversation=entry.getString("conversationId");
            if(conversation.isBlank())throw new SecurityException("原手机结果目的对话不可核对");
            if(deferred.contains(conversation))continue;
            try{
                JSONObject receipt=publisher.publish(entry.getString("taskId"),entry.getString("runId"),entry.getInt("epoch"));
                // Idle can arrive between rows. Never let a later result overtake the
                // waiting one; the existing Dirty flag starts a fresh ordered scan.
                if(receipt==null||!receipt.optBoolean("accepted")&&!receipt.optBoolean("conversationDeleted"))deferred.add(conversation);
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(Exception unavailable){deferred.add(conversation);}
        }
    }
}
