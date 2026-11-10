package dev.pocketengine.features.memory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Display grouping and exact comparisons over the original Book. Neither creates a fact store,
 * identifies a person from prose, nor grants an inferred statement native review authority. */
public final class PersonalMemorySemantics {
    public static final String MANUAL_SOURCE="主人主动添加";
    private static final Map<String,String> SETTING_PREDICATES=Map.ofEntries(
            Map.entry("language","language"),Map.entry("response_language","language"),Map.entry("preferred_response_language","language"),Map.entry("回答语言","language"),Map.entry("回复语言","language"),
            Map.entry("response_detail","response_detail"),Map.entry("answer_detail","response_detail"),Map.entry("回答详略","response_detail"),Map.entry("回复详略","response_detail"),
            Map.entry("conclusionfirst","conclusionFirst"),Map.entry("conclusion_first","conclusionFirst"),Map.entry("先给结论","conclusionFirst"),Map.entry("先结论","conclusionFirst"),
            Map.entry("includeexamples","includeExamples"),Map.entry("include_examples","includeExamples"),Map.entry("回答举例","includeExamples"),Map.entry("结合例子","includeExamples"),
            Map.entry("operation_pace","operation_pace"),Map.entry("操作节奏","operation_pace"),Map.entry("做事节奏","operation_pace"),
            Map.entry("initiative","initiative"),Map.entry("suggestion_initiative","initiative"),Map.entry("主动建议","initiative"));
    private PersonalMemorySemantics(){}

    /** Exact structured defaults have one writer in SettingsStore. This does not infer a
     * setting/value from prose, change a preference, or suppress an entire source message. */
    public static String preferenceKey(JSONObject row){
        JSONObject assertion=row==null?null:row.optJSONObject("assertion");
        if(!ownerAssertion(assertion)||!"preference".equals(row.optString("layer"))||"imagined".equals(assertion.optString("kind"))
                ||!normalizedText(assertion.optString("condition")).isEmpty()||!row.isNull("expiresAt")||assertion.has("objectId"))return "";
        for(String field:new String[]{"eventTime","validFrom","validTo"})if(!assertion.isNull(field))return "";
        String predicate=Normalizer.normalize(assertion.optString("predicate"),Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT).replace('-','_');
        boolean explicitNamespace=false;
        for(String prefix:new String[]{"preferences.","preferences:","preference.","preference:","personal_preferences.","profile.preferences."})
            if(predicate.startsWith(prefix)){predicate=predicate.substring(prefix.length());explicitNamespace=true;break;}
        // Bare language can mean a programming or spoken-language interest. Only its exact
        // SettingsStore enum or a declared preferences namespace identifies the default.
        if("language".equals(predicate)&&!explicitNamespace&&!Set.of("auto","zh-CN","en","ja").contains(assertion.optString("object")))return "";
        return SETTING_PREDICATES.getOrDefault(predicate,"");
    }
    public static final class PreferenceSettingRequiredException extends IllegalArgumentException {
        private final String key;
        private PreferenceSettingRequiredException(String key){super(preferenceMessage(key));this.key=key;}
        public String preferenceKey(){return key;}
        public String settingsSection(){return Set.of("operation_pace","initiative").contains(key)?"work":"communication";}
    }
    public static void requirePersonalMemory(JSONObject row){String key=preferenceKey(row);if(!key.isEmpty())throw new PreferenceSettingRequiredException(key);}
    private static String preferenceMessage(String key){
        String label=switch(key){case "language"->"回答语言";case "response_detail"->"回复详略";case "conclusionFirst"->"先给结论";case "includeExamples"->"结合例子";case "operation_pace"->"操作节奏";case "initiative"->"主动建议";default->throw new IllegalArgumentException("未知偏好");};
        return "这项内容对应个人中心的“"+(Set.of("operation_pace","initiative").contains(key)?"做事偏好":"交流偏好")+" · "+label+"”，请在那里修改默认值；本次未另存为记忆。";
    }

    public static JSONObject topicOf(JSONObject row)throws Exception {
        JSONObject assertion=row==null?null:row.optJSONObject("assertion");String id="other",label="其他";
        if(ownerAssertion(assertion)){
            String predicate=Normalizer.normalize(assertion.optString("predicate"),Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
            if(predicate.length()<=160){
                String leaf=predicate.replace('-', '_').replace(' ', '_');int separator=Math.max(leaf.lastIndexOf('.'),leaf.lastIndexOf(':'));if(separator>=0)leaf=leaf.substring(separator+1);
                if(Set.of("name","nickname","age","birthday","birthplace","hometown","location","education","background","about","姓名","昵称","年龄","生日","出生地","家乡","居住地","学历","背景").contains(leaf)){id="background";label="个人背景";}
                else if(Set.of("work","job","occupation","profession","career","company","workplace","job_title","工作","职业","公司","职位").contains(leaf)){id="work";label="工作与学习";}
                else if(Set.of("interests","interest","hobby","hobbies","favorite","favourites","兴趣","爱好","喜欢的事物").contains(leaf)){id="interests";label="兴趣爱好";}
                else if(Set.of("language","preferred_language","response_style","communication","communication_style","response_detail","answer_style","address_as","语言","回答语言","表达方式","沟通方式","回答风格","称呼").contains(leaf)){id="communication";label="交流偏好";}
                else if(Set.of("habit","habits","routine","diet","food_preference","sleep","exercise","习惯","作息","饮食","饮食偏好","睡眠","运动习惯").contains(leaf)){id="habits";label="生活习惯";}
            }
        }
        return new JSONObject().put("id",id).put("label",label);
    }

    /** Native form input is an explicit note, not a fabricated original conversation quote.
     * A text-specific predicate keeps unrelated manual notes from superseding each other. */
    public static JSONObject ownerNote(String text,String layer,Long expiresAt,long now)throws Exception {
        if(text==null)throw new IllegalArgumentException("请填写记忆内容");MemoryBook.validateUnicode(text);text=text.trim();
        if(text.isEmpty()||text.length()>4000||layer==null||!Set.of("fact","preference","episodic").contains(layer)||now<0)throw new IllegalArgumentException("个人记忆内容、类型或保存时间无效");
        JSONObject assertion=MemoryAssertion.checked(new JSONObject().put("version",MemoryAssertion.VERSION).put("kind","inferred")
                .put("subjectId","owner").put("subjectType","owner").put("subjectLabel","我")
                .put("predicate","personal_note:"+TaskMemoryUse.digest(normalizedText(text))).put("object",text).put("condition","")
                .put("assertedAt",now).put("evidenceSpans",new JSONArray()));
        return new JSONObject().put("text",text).put("layer",layer).put("source",MANUAL_SOURCE).put("confidence",1d)
                .put("core",false).put("sourceIds",new JSONArray()).put("sourceRefs",new JSONArray())
                .put("expiresAt",MemoryBook.checkedExpiry(expiresAt)).put("assertion",assertion);
    }

    static String normalizedText(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFC).trim().replaceAll("[\\p{Z}\\s]+"," ");}
    static boolean ownerAssertion(JSONObject assertion){return assertion!=null&&"owner".equals(assertion.optString("subjectId"))&&"owner".equals(assertion.optString("subjectType"));}
    static boolean sameMeaning(JSONObject left,JSONObject right)throws Exception {return sameMeaning(left,right,false);}
    static boolean sameMeaning(JSONObject left,JSONObject right,boolean nativeOwnerNoteDraft)throws Exception {
        JSONObject a=left.optJSONObject("assertion"),b=right.optJSONObject("assertion");
        if(!ownerAssertion(a)||!ownerAssertion(b)||!left.optString("layer").equals(right.optString("layer"))
                ||"imagined".equals(a.optString("kind"))||"imagined".equals(b.optString("kind"))
                ||!normalizedText(a.optString("condition")).equals(normalizedText(b.optString("condition"))))return false;
        for(String field:new String[]{"objectId","objectType","eventTime","validFrom","validTo"})
            if(!MemoryArchive.canonical(a.opt(field)).equals(MemoryArchive.canonical(b.opt(field))))return false;
        String text=normalizedText(left.optString("text"));boolean sameText=!text.isEmpty()&&text.equals(normalizedText(right.optString("text")));
        if(!a.optString("kind").equals(b.optString("kind")))
            return sameText&&(nativeManualNote(left)&&"user_said".equals(b.optString("kind"))
                    ||"user_said".equals(a.optString("kind"))&&(nativeManualNote(right)||nativeOwnerNoteDraft&&manualNoteContent(right)));
        return sameText
                ||normalizedText(a.optString("predicate")).equals(normalizedText(b.optString("predicate")))
                &&normalizedText(a.optString("object")).equals(normalizedText(b.optString("object")));
    }
    private static boolean manualNoteContent(JSONObject row){JSONObject assertion=row.optJSONObject("assertion");return MANUAL_SOURCE.equals(row.optString("source"))&&assertion!=null&&"inferred".equals(assertion.optString("kind"));}
    private static boolean nativeManualNote(JSONObject row){return manualNoteContent(row)&&"explicit_save".equals(row.optString("formation"))&&row.optJSONObject("assertionReview")!=null;}
}
