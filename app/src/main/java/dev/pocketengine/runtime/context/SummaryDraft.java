package dev.pocketengine.runtime.context;

import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.core.ModelConfig;
import org.json.JSONObject;

/** A completed real SUMMARY call. Coverage and provenance are native, never supplied by the model. */
public final class SummaryDraft {
    public final String text,bodyFingerprint,callId;
    private final String receipt;
    private final boolean secretRedacted;
    private final CompactionJournal.FrozenPrefix expected;
    private SummaryDraft(CompactionJournal.FrozenPrefix expected,JSONObject actual,InferenceReceipt receipt,ModelConfig selected)throws Exception {
        if(receipt==null||receipt.outcome!=InferenceReceipt.Outcome.COMPLETED||receipt.context.purpose!=InferenceReceipt.Purpose.SUMMARY
                ||!expected.taskId.equals(receipt.context.taskId)||!expected.runId.equals(receipt.context.runId)||expected.epoch!=receipt.context.epoch)
            throw new SecurityException("摘要没有原任务真实完成的SUMMARY调用证据");
        if(actual.has("tool_calls")||!(actual.opt("content") instanceof String))throw new SecurityException("摘要不能派发工具或自行选择覆盖范围");
        String body=actual.getString("content");if(blank(body)||body.length()>CompactionPlan.MAX_SUMMARY_CHARS)throw new CompactionPlan.Unavailable("summary_output_exceeds_bound");
        text=java.util.Objects.requireNonNull(selected).redactSensitive(body);secretRedacted=!body.equals(text);
        if(text.length()>CompactionPlan.MAX_SUMMARY_CHARS)throw new CompactionPlan.Unavailable("summary_output_exceeds_bound");
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))throw new IllegalArgumentException("摘要Unicode不完整");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("摘要Unicode不完整");}
        this.expected=expected;bodyFingerprint=CompactionPlan.fingerprint(text);callId=receipt.callId;this.receipt=receipt.toJson().toString();
    }
    public static SummaryDraft completed(CompactionJournal.FrozenPrefix expected,JSONObject actual,InferenceReceipt receipt,ModelConfig selected)throws Exception{return new SummaryDraft(expected,actual,receipt,selected);}
    public boolean belongsTo(CompactionJournal.FrozenPrefix prefix){return prefix==expected;}
    public JSONObject toJson()throws Exception{return new JSONObject().put("format",1).put("text",text).put("bodyFingerprint",bodyFingerprint).put("promptVersion",CompactionPlan.PROMPT_VERSION)
            .put("callId",callId).put("inferenceReceipt",new JSONObject(receipt)).put("coverage",expected.plan.metadata()).put("sources",expected.sources())
            .put("guard",expected.guard()).put("trust","derived_untrusted_context").put("hostSecretRedacted",secretRedacted).put("formalMemorySaved",false).put("executionAuthorized",false);}
    /** Same code-point semantics as String.isBlank, available on minSdk30. */
    private static boolean blank(String value){
        if(value==null)return true;
        for(int offset=0;offset<value.length();){int point=value.codePointAt(offset);if(!Character.isWhitespace(point))return false;offset+=Character.charCount(point);}
        return true;
    }

}
