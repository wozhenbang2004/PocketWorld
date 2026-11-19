package dev.pocketengine.phone.reading;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** A whole, single-purpose owner question. A mention of Hz or a model-proposed action is not a request. */
public enum PrimaryDisplayQuestion {
    RATE, SIZE, BOTH;

    private static final String ZH_RATE = "(?:刷新率(?:是?多少(?:hz|赫兹)?)?|(?:是?多少|几)(?:hz|赫兹))";
    private static final String ZH_SIZE = "(?:模式)?分辨率(?:是?多少)?";
    private static final String ZH_METRIC = "(?:(?:现在|当前(?:模式)?|目前|此刻|系统报告|的){0,4})("+ZH_RATE+"|"+ZH_SIZE+")";
    private static final Pattern ZH = Pattern.compile("^(?:(?:请问|请|麻烦)?(?:帮我)?(?:查(?:一下|下|查)?|看(?:一下|下|看)?|读取|告诉我|核对(?:一下)?)?)"
            +"(?:这台手机|这部手机|本机|我的手机)(?:(?:现在|目前|当前)?的?)?(?:默认)?(?:主屏幕?|屏幕)的?"+ZH_METRIC
            +"(?:(?:、|,|和|以及|及)"+ZH_METRIC+")?(?:[,、]?(?:把读数告诉我|把结果告诉我))?[?。.!]?$");
    private static final String EN_METRIC="(refresh rate|(?:mode )?resolution)";
    private static final Pattern EN=Pattern.compile("^(?:what (?:is|are)|check|read|tell me) (?:this|my) phone['’]s (?:current |active )?"
            +"(?:main |primary )?(?:display|screen)(?:['’]s)? (?:current |active )?"+EN_METRIC+"(?: and "+EN_METRIC+")?[?.!]?$");

    public static PrimaryDisplayQuestion parse(String text) {
        if(text==null||text.trim().isEmpty()||text.length()>180)return null;
        String value=Normalizer.normalize(text.trim(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Matcher m=ZH.matcher(value.replace(" ",""));
        if(!m.matches()){m=EN.matcher(value.replaceAll(" +"," "));if(!m.matches())return null;}
        boolean size=isSize(m.group(1));String second=m.group(2);
        if(second!=null)return size!=isSize(second)?BOTH:null;
        return size?SIZE:RATE;
    }
    private static boolean isSize(String field){return field.contains("分辨率")||field.contains("resolution");}

    /** The private composer supplies the original draft. Attached material, directed work, or appended input is not this route. */
    public static boolean matchesFreshDraft(JSONObject input) {
        if(input==null||!java.util.Set.of("queue","steer").contains(input.optString("mode"))||parse(input.optString("message"))==null)return false;
        for(String key:new String[]{"taskId","runId","epoch","_ownerWorkTargeted"})if(input.has(key))return false;
        for(String key:new String[]{"attachments","ownerSourceRefs","ownerPreviewRefs","ownerObjectRefs"})
            if(input.has(key)&&(input.optJSONArray(key)==null||input.optJSONArray(key).length()!=0))return false;
        return true;
    }
}
