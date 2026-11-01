package dev.pocketengine.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ephemeral captions/review fence, not a conversation database, task store, ASR or execution loop. */
final class RealtimeTurnBuffer {
    static final int MAX_TURNS=64,MAX_TEXT=4000;
    private final java.util.function.LongSupplier clock;
    RealtimeTurnBuffer(){this(System::currentTimeMillis);}
    RealtimeTurnBuffer(java.util.function.LongSupplier clock){this.clock=clock;}
    private String sessionId="";
    private boolean active,reconnecting;
    private final Map<String,Turn> turns=new LinkedHashMap<>();
    private final Map<String,Review> reviews=new LinkedHashMap<>();
    private JSONObject lastReceipt;
    private String notice="";
    private static final class Turn {
        final String id=UUID.randomUUID().toString(),commitId=UUID.randomUUID().toString();
        String segment,participant,track,protocol,text="",phase="collecting",commit="none";
        long sequence,interruptedSequence,revision=1;boolean local,finalText,postCommitUpdate;
        RealtimeReviewedTurn submitted;JSONObject receipt;
    }
    private static final class Review {
        final String id=UUID.randomUUID().toString();final RealtimeReviewedTurn turn;final long expiresAt;
        Review(RealtimeReviewedTurn turn,long expiresAt){this.turn=turn;this.expiresAt=expiresAt;}
    }
    synchronized void start(String session){sessionId=session;active=true;reconnecting=false;turns.entrySet().removeIf(entry->!entry.getValue().commit.matches("committing|unknown"));reviews.clear();lastReceipt=null;notice="";}
    synchronized void stop(){
        if(!active)return;active=false;reconnecting=false;invalidateUnsubmittedReviews();
        turns.entrySet().removeIf(entry->entry.getValue().commit.equals("committed"));
    }
    /** Keep this session's text and original submissions, but never finish an interrupted stream. */
    synchronized void reconnecting(){
        if(!active||reconnecting)return;reconnecting=true;invalidateUnsubmittedReviews();
    }
    private void invalidateUnsubmittedReviews(){
        reviews.clear();
        for(Turn turn:turns.values())if(turn.commit.equals("none")){
            if(!turn.finalText){turn.phase="incomplete";turn.interruptedSequence=turn.sequence;}
            turn.revision++;
        }
    }
    synchronized void reconnected(){if(active)reconnecting=false;}
    synchronized boolean accept(JSONObject update)throws Exception{
        if(!active||reconnecting)return false;
        String segment=bounded(update,"segmentId",200),participant=bounded(update,"participantIdentity",200),track=bounded(update,"trackSid",200);
        String protocol=update.optString("protocol");if(!protocol.matches("text_stream|legacy_event|openai_realtime_ga"))return false;
        if(segment.isBlank()||participant.isBlank()||track.isBlank())return false;
        String key=new JSONArray().put(sessionId).put(participant).put(track).put(segment).toString();
        Turn turn=turns.get(key);boolean created=turn==null,provenanceChanged=false;long sequence=update.optLong("sequence");if(sequence<=0||turn!=null&&sequence<=turn.interruptedSequence)return false;
        String phase=update.optString("phase"),text=update.optString("text");boolean isFinal=update.optBoolean("isFinal"),finalStream=update.optBoolean("finalStream");
        if(!phase.matches("begin|partial|complete|failed|legacy"))return false;
        if(text.length()>MAX_TEXT){
            boolean changed=notice("一段转录超过4000字，未接受为任务目标");
            if(turn!=null&&sequence>=turn.sequence&&turn.commit.equals("none")){turn.sequence=sequence;turn.finalText=false;turn.phase="incomplete";turn.revision++;String id=turn.id;reviews.entrySet().removeIf(entry->entry.getValue().turn.turnId.equals(id));changed=true;}
            return changed;
        }
        if(turn==null){
            if(turns.size()>=MAX_TURNS)return notice("本次字幕已达到64段上限；新段未保留，已有任务不受影响");
            turn=new Turn();turn.segment=segment;turn.participant=participant;turn.track=track;turn.protocol=protocol;turn.local=update.optBoolean("reportedLocalMicrophone");turn.sequence=sequence;turns.put(key,turn);
        }else{
            if(sequence<turn.sequence)return false;
            if(!turn.commit.equals("none")){if(!turn.postCommitUpdate&&!turn.text.equals(text)&&!text.isEmpty()){turn.postCommitUpdate=true;return true;}return false;}
            // Late interim messages may not reopen an already final segment. A newer final stream can revise it.
            if(turn.finalText&&!finalStream&&!isFinal)return false;
            if(phase.equals("begin")&&sequence==turn.sequence)return false;
            if(!phase.matches("begin|legacy")&&sequence!=turn.sequence)return false;
            if(turn.local!=update.optBoolean("reportedLocalMicrophone")||!turn.protocol.equals(protocol)){
                // Conflicting provenance is displayed as untrusted, never silently promoted.
                provenanceChanged=turn.local;turn.local=false;
            }
        }
        String nextText=phase.equals("begin")?"":text;
        boolean complete=(phase.equals("complete")||phase.equals("legacy"))&&isFinal;
        String nextPhase=phase.equals("failed")?"incomplete":complete?"final":"collecting";
        boolean changed=created||provenanceChanged||turn.sequence!=sequence||!turn.text.equals(nextText)||turn.finalText!=complete||!turn.phase.equals(nextPhase);
        turn.sequence=sequence;turn.text=nextText;turn.finalText=complete;turn.phase=nextPhase;
        if(changed){turn.revision++;String id=turn.id;reviews.entrySet().removeIf(entry->entry.getValue().turn.turnId.equals(id));}
        return changed;
    }
    synchronized JSONObject snapshot(boolean committerReady)throws Exception{return snapshot(committerReady,true);}
    synchronized JSONObject snapshot(boolean committerReady,boolean reviewAllowed)throws Exception{
        boolean reviewReady=reviewAllowed&&!reconnecting&&!sessionId.isEmpty();
        JSONArray rows=new JSONArray();for(Turn turn:turns.values())rows.put(new JSONObject().put("id",turn.id).put("sessionId",turn.submitted==null?sessionId:turn.submitted.sessionId).put("segmentId",turn.segment).put("participantIdentity",turn.participant).put("trackSid",turn.track).put("protocol",turn.protocol)
            .put("text",turn.text).put("revision",turn.revision).put("isFinal",turn.finalText).put("phase",turn.phase).put("sourceKind",turn.local?"reported_local_microphone":"remote_or_unverified")
            .put("canReview",reviewReady&&committerReady&&turn.local&&turn.finalText&&turn.commit.equals("none")&&!turn.text.isBlank()).put("commitState",turn.commit).put("commitId",turn.commit.equals("unknown")?turn.commitId:"").put("postCommitUpdate",turn.postCommitUpdate)
            .put("taskId",turn.receipt==null?"":turn.receipt.optString("id")));
        return new JSONObject().put("sessionId",sessionId).put("active",active).put("reconnecting",active&&reconnecting).put("retained",!active&&!turns.isEmpty()).put("reviewReady",reviewReady).put("turns",rows).put("notice",notice).put("commitAvailable",committerReady).put("lastTask",lastReceipt==null?JSONObject.NULL:new JSONObject(lastReceipt.toString()));
    }
    synchronized JSONObject review(String expectedSession,String turnId,long revision)throws Exception{
        requireSession(expectedSession);Turn turn=find(turnId);requireFinal(turn,revision);
        RealtimeReviewedTurn immutable=new RealtimeReviewedTurn(turn.commitId,sessionId,turn.id,turn.revision,turn.text,turn.segment,turn.participant,turn.track,turn.protocol);
        reviews.entrySet().removeIf(entry->entry.getValue().turn.turnId.equals(turnId));Review review=new Review(immutable,clock.getAsLong()+120_000);reviews.put(review.id,review);
        return new JSONObject().put("reviewId",review.id).put("sessionId",sessionId).put("turnId",turnId).put("revision",revision).put("text",immutable.text).put("contentHash",immutable.contentHash).put("source",immutable.source()).put("expiresAt",review.expiresAt).put("action","create_waiting_task");
    }
    synchronized RealtimeReviewedTurn beginCommit(String expectedSession,String reviewId,String expectedHash)throws Exception{
        requireSession(expectedSession);Review review=reviews.get(reviewId);
        if(review==null||review.expiresAt<=clock.getAsLong()||!review.turn.contentHash.equals(expectedHash))throw new IllegalStateException("语音复核已变化或过期，请重新检查最终文本");
        Turn turn=find(review.turn.turnId);requireFinal(turn,review.turn.finalRevision);
        if(!turn.text.equals(review.turn.text))throw new IllegalStateException("转录已修订，不能提交旧文本");
        reviews.remove(reviewId);turn.commit="committing";turn.submitted=review.turn;return review.turn;
    }
    synchronized RealtimeReviewedTurn pending(String commitId)throws Exception{
        for(Turn turn:turns.values())if(turn.commitId.equals(commitId)&&turn.commit.equals("unknown")&&turn.submitted!=null)return turn.submitted;
        throw new IllegalStateException("没有可核对的原提交");
    }
    synchronized void completed(RealtimeReviewedTurn committed,JSONObject task)throws Exception{
        Turn turn=findSubmitted(committed);if(turn==null)return;
        if(task==null||!committed.commitId.equals(task.optString("id")))throw new IllegalStateException("宿主未返回同一个任务身份");
        turn.commit="committed";turn.receipt=new JSONObject().put("id",task.getString("id")).put("status",task.optString("status")).put("created",task.optBoolean("created"));lastReceipt=turn.receipt;
        if(!active)turns.values().remove(turn);
    }
    synchronized void unknown(RealtimeReviewedTurn committed){Turn turn=findSubmitted(committed);if(turn!=null)turn.commit="unknown";notice="任务保存结果未确认；只能核对原提交，不能重新执行";}
    private Turn findSubmitted(RealtimeReviewedTurn submitted){for(Turn turn:turns.values())if(turn.submitted==submitted)return turn;return null;}
    private void requireSession(String expected){if(sessionId.isEmpty()||!sessionId.equals(expected))throw new IllegalStateException("本次转录已清除或会话已变化，请重新核对");if(reconnecting)throw new IllegalStateException("通话正在恢复，连接稳定后请重新复核最终文字");}
    private Turn find(String id){for(Turn turn:turns.values())if(turn.id.equals(id))return turn;throw new IllegalStateException("转录不在当前会话中");}
    private void requireFinal(Turn turn,long revision){if(!turn.local||!turn.finalText||turn.text.isBlank()||turn.revision!=revision||!turn.commit.equals("none"))throw new IllegalStateException("只可复核当前本机麦克风来源的最终文本；草稿和远端字幕不能成为机主任务");}
    private boolean notice(String next){boolean changed=!notice.equals(next);notice=next;return changed;}
    private static String bounded(JSONObject value,String key,int max){String text=value.optString(key);return text.length()>max?"":text;}
}
