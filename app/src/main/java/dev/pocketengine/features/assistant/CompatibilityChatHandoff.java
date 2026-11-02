package dev.pocketengine.features.assistant;

import android.content.Intent;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.ui.ComposeActivity;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.json.JSONObject;

/** Native, process-local navigation/draft handoff. Never a submission, grant or cancel capability. */
public final class CompatibilityChatHandoff {
    public static final String EXTRA="nativeCompatibilityChatHandoff";
    private static final LinkedHashMap<String,Review> pending=new LinkedHashMap<>();
    public static final class Review {
        private final EngineApplication owner;
        private final String page;
        public final String id,conversationId,text,taskId,runId;
        public final int epoch;
        private boolean applied;
        private Review(EngineApplication owner,String page,String conversation,String text,JSONObject turn){
            this.owner=owner;this.page=page;id=UUID.randomUUID().toString();conversationId=conversation;this.text=text;
            taskId=turn.optString("taskId");runId=turn.optString("runId");epoch=turn.optInt("epoch");
        }
        public synchronized boolean applied(){return applied;}
    }
    public static synchronized Intent open(EngineApplication app,String nativePage,JSONObject input)throws Exception {
        String conversation=input.optString("conversationId"),text=input.optString("draft");
        if(text.length()>12000||conversation.length()>240)throw new IllegalArgumentException("请先保留草稿并缩短后继续");
        if(!conversation.isEmpty()){
            JSONObject original=app.features.module("assistant").handle("get",new JSONObject().put("conversationId",conversation));
            if(!conversation.equals(original.optString("conversationId",original.optString("id"))))throw new SecurityException("原对话不可读取，未转到其他对话");
        }
        JSONObject turn=input.optJSONObject("originalTurn");if(turn==null)turn=new JSONObject();
        if(turn.length()>0){
            JSONObject task=app.tasks.get(turn.getString("taskId"),false);
            if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!conversation.equals(task.optString("conversationId"))||!turn.getString("runId").equals(task.optString("runId"))||turn.getInt("epoch")!=task.optInt("coordinatorEpoch"))throw new SecurityException("原对话执行已变化，请刷新后进入原任务控制");
        }
        for(Review review:pending.values())if(review.owner==app&&review.page.equals(nativePage)&&review.conversationId.equals(conversation)&&review.text.equals(text)&&review.taskId.equals(turn.optString("taskId"))&&review.runId.equals(turn.optString("runId"))&&review.epoch==turn.optInt("epoch"))return intent(app,review.id);
        if(pending.size()>=16){String retired=null;for(Review review:pending.values())if(review.applied()){retired=review.id;break;}if(retired==null)throw new IllegalStateException("请先处理已保留的原生草稿交接；没有丢弃文字");pending.remove(retired);}
        Review review=new Review(app,nativePage,conversation,text,turn);pending.put(review.id,review);return intent(app,review.id);
    }
    private static Intent intent(EngineApplication app,String id){return new Intent(app,ComposeActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA,id);}
    public static synchronized Review read(EngineApplication app,String id)throws Exception {
        Review review=pending.get(id);if(review==null||review.owner!=app)throw new SecurityException("原生草稿交接已失效；请回兼容页保留原文后重试");
        if(!review.conversationId.isEmpty())app.features.module("assistant").handle("get",new JSONObject().put("conversationId",review.conversationId));
        if(!review.taskId.isEmpty()){JSONObject task=app.tasks.get(review.taskId,false);if(task==null||!review.runId.equals(task.optString("runId"))||review.epoch!=task.optInt("coordinatorEpoch")||!review.conversationId.equals(task.optString("conversationId")))throw new SecurityException("原对话执行已变化，请返回原入口刷新后核对");}
        return review;
    }
    public static void applied(Review review){synchronized(review){review.applied=true;}}
    private CompatibilityChatHandoff(){}
}
