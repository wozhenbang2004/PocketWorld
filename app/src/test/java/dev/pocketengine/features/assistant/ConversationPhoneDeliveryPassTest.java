package dev.pocketengine.features.assistant;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConversationPhoneDeliveryPassTest {
    private static JSONObject entry(String conversation,String task,int epoch)throws Exception{return new JSONObject().put("conversationId",conversation).put("taskId",task).put("runId",task+"-"+epoch).put("epoch",epoch);}
    private static JSONObject accepted()throws Exception{return new JSONObject().put("accepted",true);}
    @Test public void idleBetweenRowsCannotPublishNewerResultsBeforeTheWaitingOne()throws Exception {
        var pass=new ConversationPhoneDeliveryPass();var attempts=new ArrayList<String>();boolean[] busy={true};
        JSONArray page=new JSONArray().put(entry("a","phone",1)).put(entry("a","phone",2)).put(entry("b","other",1));
        pass.begin();pass.publish(page,(task,run,epoch)->{
            attempts.add(run);
            if(task.equals("phone")&&busy[0]){busy[0]=false;return new JSONObject().put("waitingForOriginalConversation",true);}
            return accepted();
        },()->true);
        assertEquals(List.of("phone-1","other-1"),attempts);
        // The real idle event's Dirty flag causes the Engine to begin another scan.
        pass.begin();pass.publish(page,(task,run,epoch)->{attempts.add(run);return accepted();},()->true);
        assertEquals(List.of("phone-1","other-1","phone-1","phone-2","other-1"),attempts);
    }
    @Test public void deferralSurvivesPaginationAndCoversAnotherTaskInTheSameConversation()throws Exception {
        var pass=new ConversationPhoneDeliveryPass();var attempts=new ArrayList<String>();pass.begin();
        pass.publish(new JSONArray().put(entry("a","first",1)),(task,run,epoch)->{attempts.add(run);return new JSONObject().put("waitingForOriginalConversation",true);},()->true);
        pass.publish(new JSONArray().put(entry("a","second",1)).put(entry("b","independent",1)),(task,run,epoch)->{attempts.add(run);return accepted();},()->true);
        assertEquals(List.of("first-1","independent-1"),attempts);
    }
    @Test public void uncertainPublicationDefersSuccessorsWithoutBlockingOtherConversations()throws Exception {
        var pass=new ConversationPhoneDeliveryPass();var attempts=new ArrayList<String>();pass.begin();
        pass.publish(new JSONArray().put(entry("a","phone",1)).put(entry("a","phone",2)).put(entry("b","other",1)),(task,run,epoch)->{
            attempts.add(run);if(run.equals("phone-1"))throw new IllegalStateException("publication outcome unavailable");return accepted();
        },()->true);
        assertEquals(List.of("phone-1","other-1"),attempts);
    }
}
