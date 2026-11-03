package dev.pocketengine.phone;

import dev.pocketengine.media.voice.input.VoiceDraftInput;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** One-use foreground navigation to the existing original draft; never a recording or task queue. */
public final class QuickPanelVoiceHandoff {
    public static final String ACTION = "dev.pocketengine.QUICK_PANEL_VOICE_DRAFT";
    public static final String TOKEN_EXTRA = "quickPanelVoiceToken";
    private static final long OFFER_LIFETIME = TimeUnit.MINUTES.toNanos(2);
    private static final Map<String,Offer> OFFERS = new LinkedHashMap<>();
    @FunctionalInterface interface Original { JSONObject read(String taskId) throws Exception; }
    private QuickPanelVoiceHandoff() {}

    static Offer offer(QuickPanelDraftStore store, QuickPanelDraftStore.State saved, JSONObject expected,
                       Original original, Runnable onClaim) throws Exception {
        return offer(store,saved,expected,original,onClaim,System::nanoTime);
    }
    static Offer offer(QuickPanelDraftStore store, QuickPanelDraftStore.State saved, JSONObject expected,
                       Original original, Runnable onClaim, LongSupplier clock) throws Exception {
        Offer value = new Offer(store,saved,new JSONObject(expected.toString()),original,onClaim,clock);
        value.requireOriginal();store.requireSnapshot(saved);
        synchronized (OFFERS) {
            OFFERS.values().removeIf(Offer::expired);
            if (OFFERS.size() >= 8) throw new IllegalStateException("已有听写交接待处理，请回原窗口核对");
            OFFERS.put(value.token,value);
        }
        return value;
    }

    /** Native Activity calls this on its I/O worker. An exported intent's text is only a one-use selector. */
    public static Session claim(String token) throws Exception {
        final Offer offer;
        synchronized (OFFERS) { offer = OFFERS.remove(token); }
        if (offer == null) throw new SecurityException("原浮层听写交接已失效，请重新点击原浮层话筒；没有录音");
        synchronized (offer) {
            if (offer.closed || offer.claimed || offer.expired()) throw new SecurityException("原浮层听写交接已关闭或超时；没有录音");
            offer.claimed = true;
        }
        try {
            offer.requireOriginal();offer.store.requireSnapshot(offer.saved);
            offer.onClaim.run();
            Session session = new Session(offer);session.checkActive();return session;
        } catch (Exception unavailable) { offer.close();throw unavailable; }
    }

    static final class Offer implements AutoCloseable {
        final String token = UUID.randomUUID().toString();
        final QuickPanelDraftStore store;
        final QuickPanelDraftStore.State saved;
        final JSONObject expected;
        final Original original;
        final Runnable onClaim;
        final LongSupplier clock;
        final long offeredAt;
        volatile boolean closed;
        boolean claimed;
        Offer(QuickPanelDraftStore store,QuickPanelDraftStore.State saved,JSONObject expected,Original original,Runnable onClaim,LongSupplier clock) {
            this.store=Objects.requireNonNull(store);this.saved=Objects.requireNonNull(saved);this.expected=expected;
            this.original=Objects.requireNonNull(original);this.onClaim=Objects.requireNonNull(onClaim);this.clock=Objects.requireNonNull(clock);offeredAt=clock.getAsLong();
        }
        boolean expired() { long age=clock.getAsLong()-offeredAt;return closed||age<0||age>OFFER_LIFETIME; }
        void requireOriginal() throws Exception {
            JSONObject task=original.read(expected.getString("taskId"));
            if (task==null || !expected.getString("taskId").equals(task.optString("id"))
                    || !expected.getString("runId").equals(task.optString("runId")) || expected.getInt("epoch")!=task.optInt("coordinatorEpoch")
                    || !expected.getString("executionMode").equals(task.optString("executionMode"))
                    || !"owner".equals(task.optString("actorId")) || !"private".equals(task.optString("spaceId"))
                    || !task.optString("status").matches("preparing|running|pausing|paused|awaiting_user")
                    || task.optBoolean("cancelRequested") || task.has("sourceLearningJob"))
                throw new SecurityException("原任务或执行代次已变化，未把听写交给其他任务");
            JSONObject origin=task.optJSONObject("origin");
            if (task.has("origin")&&(origin==null||origin.optString("kind").matches("channel|group|reviewed_external_group"))) throw new SecurityException("此任务请回原入口补充，未改变原受众");
            String kind=task.optString("kind"),mode=task.optString("executionMode");
            if (!("assistant".equals(kind)&&"capabilities".equals(mode)||"phone".equals(kind)&&mode.matches("main_screen|background")))
                throw new SecurityException("原任务不支持私人浮层听写");
        }
        @Override public void close() {
            closed=true;
            synchronized (OFFERS) { OFFERS.remove(token,this); }
        }
    }

    public static final class Session implements AutoCloseable {
        private final Offer original;
        private boolean writing;
        private String savedReview;
        private JSONObject receipt;
        private Session(Offer original) { this.original=original; }

        /** Cheap foreground Audio authority companion. The Controller still checks focus and its audio lease. */
        public void checkActive() { if(original.closed)throw new IllegalStateException("原浮层听写窗口已关闭，未继续录音或写入"); }

        public JSONObject snapshot() throws Exception {
            checkActive();
            return new JSONObject(original.expected.toString()).put("handoffId",original.token).put("draftRevision",original.saved.revision)
                    .put("note",original.saved.note).put("sendLimit",1500).put("draftLimit",8192)
                    .put("automaticSend",false).put("executionAuthorized",false);
        }

        public VoiceDraftInput.Target voiceTarget(long viewEpoch) throws Exception {
            checkActive();
            return new VoiceDraftInput.Target("quick-panel:"+original.token,viewEpoch,true,original.expected);
        }

        /** I/O worker only, after explicit final-text review. Preserve the entire previous note. */
        public synchronized JSONObject appendReviewed(String text) throws Exception {
            checkActive();
            if(text==null||text.isBlank()||text.length()>4000)throw new IllegalArgumentException("请先核对1至4000字的最终听写文字");
            String reviewed=text.trim();
            if(receipt!=null){if(!reviewed.equals(savedReview))throw new SecurityException("这次听写已保存，不能用原交接重复追加另一段文字");return new JSONObject(receipt.toString()).put("duplicate",true);}
            if(writing)throw new IllegalStateException("原草稿写入结果尚未确认，请返回浮层读取已有内容；不会重复追加");
            original.requireOriginal();original.store.requireSnapshot(original.saved);checkActive();
            String combined=original.saved.note+(original.saved.note.isEmpty()?"":"\n")+reviewed;
            if(combined.length()>8192)throw new IllegalArgumentException("合并后超过8192字草稿上限，原说明仍保留；请缩短听写文字后再保存");
            writing=true;
            QuickPanelDraftStore.State saved=original.store.saveReviewed(original.saved,combined);
            receipt=new JSONObject(original.expected.toString()).put("saved",true).put("draftRevision",saved.revision)
                    .put("previousRevision",original.saved.revision).put("characters",saved.note.length())
                    .put("requiresEditingBeforeSend",saved.note.length()>1500).put("automaticSend",false).put("executionAuthorized",false);
            savedReview=reviewed;return new JSONObject(receipt.toString());
        }

        @Override public void close() { original.close(); }
    }
}
