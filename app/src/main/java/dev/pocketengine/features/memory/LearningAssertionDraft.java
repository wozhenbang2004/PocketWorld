package dev.pocketengine.features.memory;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Converts optional model semantics into a pending Book draft with native source evidence.
 * The model cannot issue a SourceRef, certify an author or create a stable person identity. */
public final class LearningAssertionDraft {
    private static final Set<String> FIELDS=Set.of("kind","subjectId","subjectType","subjectLabel","predicate","object","condition","eventTime","validFrom","validTo","reviewAfter","quote","offset");
    private LearningAssertionDraft(){}

    public static JSONObject checked(JSONObject response,SourceRef source,String sourceText)throws Exception {
        if(response==null)return null;
        JSONObject draft=new JSONObject(response.toString()),claim=draft.optJSONObject("assertion");
        if(!draft.has("assertion"))return MemoryBook.checkedLearningDraft(draft);
        if(claim==null)throw new IllegalArgumentException("人本候选陈述必须为对象");
        for(var keys=claim.keys();keys.hasNext();)if(!FIELDS.contains(keys.next()))throw new SecurityException("模型不能自报原来源、作者证明、稳定身份授权或写入时间");
        Object quoteValue=claim.opt("quote"),offsetValue=claim.opt("offset");
        if(source==null||sourceText==null||!(quoteValue instanceof String)||((String)quoteValue).isEmpty()||((String)quoteValue).length()>4000||!(offsetValue instanceof Integer||offsetValue instanceof Long))throw new SecurityException("人本候选须定位本次实际来源的确切原句");
        String quote=(String)quoteValue;long offset=((Number)offsetValue).longValue(),end=offset+quote.length();
        if(offset<0||end>sourceText.length()||end<offset||!sourceText.regionMatches((int)offset,quote,0,quote.length())||splitsPair(sourceText,(int)offset)||splitsPair(sourceText,(int)end))throw new SecurityException("候选原句或UTF-16范围与本次实际来源不符");
        String kind=claim.getString("kind");
        if("user_said".equals(kind)&&(!"message".equals(source.kind)||!quote.equals(claim.optString("object"))||!quote.equals(draft.optString("text"))))throw new SecurityException("user_said只能是原消息确切原句，摘要必须标为推断或转述");
        // A model-created display name is not a native person selection. Keep useful text as
        // an ordinary reviewed candidate; it must not become an owner/person profile by guessing.
        if(!"owner".equals(claim.optString("subjectId"))||!"owner".equals(claim.optString("subjectType"))){
            draft.remove("assertion");return MemoryBook.checkedLearningDraft(draft);
        }
        JSONObject assertion=new JSONObject(claim.toString());assertion.remove("quote");assertion.remove("offset");
        assertion.put("version",MemoryAssertion.VERSION).put("assertedAt",JSONObject.NULL)
                .put("evidenceSpans",new JSONArray().put(new JSONObject().put("sourceRef",source.toJson()).put("offset",offset).put("chars",quote.length()).put("sha256",TaskMemoryUse.digest(quote))));
        draft.put("assertion",MemoryAssertion.checked(assertion));return MemoryBook.checkedLearningDraft(draft);
    }
    private static boolean splitsPair(String text,int offset){return offset>0&&offset<text.length()&&Character.isHighSurrogate(text.charAt(offset-1))&&Character.isLowSurrogate(text.charAt(offset));}

    public static String instruction(){
        return "可选返回assertion对象以保留主体、来源类型与事件时间：kind为user_said/inferred/told/imagined，subjectId、subjectType、subjectLabel、predicate、object、condition，以及可空eventTime、validFrom、validTo、reviewAfter（原事件明确的毫秒时间，未知为null，不用今天或生成时间替代）。"
                +"quote必须逐字摘自来源text，offset是其UTF-16起点；不要返回version、assertedAt、evidenceSpans、SourceRef或权限字段，这些只由宿主提供。"
                +"user_said只能用于真实用户原句且text/object/quote完全相同；摘要、推断或转述必须另标kind，虚构标imagined。主体指事实描述的对象，不是说话人；只有明确描述主人本人的事实才用subjectId=owner、subjectType=owner。"
                +"尚未发生或尚未完成的计划摘要也标imagined，保留原文中的计划含义；日期已经过去不证明行动发生，不得自动改为现实事实或实际结果。原话中的计划也只是说过这句话的证据。"
                +"第三人、同名人或主体不明时不要猜主人或创建稳定身份，省略assertion并在普通候选中保留原来的描述和未知。所有输出只是待主人审阅的候选，不能宣称已保存、确认或验证。";
    }
}
