package dev.pocketengine.features.automation;

import dev.pocketengine.features.channels.ChannelsModule;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native consumer of the ONE authenticated inbox. No message copy, transport, model or task producer. */
public final class ChannelBusinessWaits {
    private final ChannelsModule channels;private final Object originalTasks;
    public ChannelBusinessWaits(ChannelsModule channels,Object originalTasks){this.channels=java.util.Objects.requireNonNull(channels);this.originalTasks=java.util.Objects.requireNonNull(originalTasks);}
    private void outsideTasks(){if(Thread.holdsLock(originalTasks))throw new SecurityException("原渠道核验不能在TaskStore锁或SQL事务内回调");}
    public JSONObject referenceForOwner(String originalEventId)throws Exception{
        outsideTasks();ChannelsModule.InboundFact fact=channels.originalInboundFact(originalEventId);fact.checkCurrent();JSONObject result=PlanBusinessWait.reference(fact.metadata());fact.checkCurrent();return result;
    }
    public void requireCurrent(JSONObject selected)throws Exception{
        outsideTasks();JSONObject reference=PlanBusinessWait.standard(selected),actual=referenceForOwner(PlanBusinessWait.anchorId(reference));if(!PlanBusinessWait.same(reference,actual))throw new SecurityException("所选入站来源、绑定或冻结版本已变化，须由主人重选审阅");
    }
    /** Metadata only from the already-visible original native inbox window; no raw text/title. */
    public JSONArray candidates()throws Exception{
        outsideTasks();JSONArray inbox=channels.snapshot().optJSONArray("inbox"),result=new JSONArray();
        for(int i=inbox==null?-1:inbox.length()-1;i>=0&&result.length()<20;i--)try{
            JSONObject reference=referenceForOwner(inbox.getJSONObject(i).getString("id")),anchor=reference.getJSONObject("anchor");
            result.put(new JSONObject().put("reference",reference).put("label",anchor.getString("provider")+" · "+anchor.getString("conversationKind")+" · "+anchor.getString("conversationId")).put("metadataOnly",true));
        }catch(Exception revoked){/* Unavailable original source is not a selectable authority. */}
        return result;
    }
    /** Called only by the native evidence resolver, outside Tasks. Every result is rechecked
     * against private carriers; persisted JSON cannot reproduce their currentness authority. */
    public JSONObject observe(JSONObject selected,String afterEventId,JSONObject consumed)throws Exception {
        outsideTasks();JSONObject reference=PlanBusinessWait.standard(selected);requireCurrent(reference);
        ChannelsModule.InboundFact anchor=channels.originalInboundFact(PlanBusinessWait.anchorId(reference));anchor.checkCurrent();
        String cursor=afterEventId==null||afterEventId.isEmpty()?anchor.metadata().getString("eventId"):afterEventId;
        ChannelsModule.InboundPage page=channels.nextInboundFact(anchor,cursor,100);page.checkCurrent();JSONObject metadata=page.metadata();
        JSONObject result=new JSONObject().put("authority","ChannelsModule").put("allowed",false).put("reason","business_wait_pending").put("reference",reference)
                .put("nextEventId",metadata.getString("nextEventId")).put("gap",metadata.getBoolean("gap")).put("hasMore",metadata.getBoolean("hasMore"))
                .put("scannedRows",metadata.getInt("scannedRows")).put("localWindowOnly",true).put("messageBodyIncluded",false).put("executionAuthorized",false);
        if(!"available".equals(metadata.getString("status"))||metadata.getBoolean("gap"))return result.put("reason","business_wait_retention_gap").put("nextEventId",cursor);
        List<ChannelsModule.InboundFact> facts=page.facts();if(facts.isEmpty())return result;
        ChannelsModule.InboundFact fact=facts.get(0);fact.checkCurrent();JSONObject observed=fact.metadata();
        if(!PlanBusinessWait.sameAudience(reference,observed)||cursor.equals(observed.getString("eventId"))||PlanBusinessWait.anchorId(reference).equals(observed.getString("eventId")))throw new SecurityException("原事件不是所选来源的新入站事实");
        JSONObject evidence=new JSONObject().put("kind","authenticated_channel_inbound").put("eventId",observed.getString("eventId")).put("fingerprint",observed.getString("fingerprint"))
                .put("payloadVersion",observed.getString("payloadVersion")).put("payloadSha256",observed.getString("payloadSha256")).put("reference",reference)
                .put("cursorBefore",cursor).put("localWindowOnly",true).put("verificationScope","original_authenticated_event_presence_only");
        if(consumed!=null&&(!evidence.getString("eventId").equals(consumed.optString("eventId"))||!evidence.getString("fingerprint").equals(consumed.optString("fingerprint"))||!PlanBusinessWait.same(reference,consumed.optJSONObject("reference"))))return result.put("reason","business_wait_consumed_event_changed").put("nextEventId",cursor);
        page.checkCurrent();fact.checkCurrent();return result.put("allowed",true).put("reason","business_wait_satisfied").put("evidence",evidence).put("nextEventId",observed.getString("eventId"));
    }
}
