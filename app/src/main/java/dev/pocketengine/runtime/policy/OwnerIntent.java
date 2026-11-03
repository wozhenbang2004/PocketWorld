package dev.pocketengine.runtime.policy;

import java.util.Locale;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/** A conservative owner-language hint. It can remove tools, never issue a grant. */
public final class OwnerIntent {
    public enum Kind { DISCUSS, READ, ACT, UNSPECIFIED }
    private volatile Kind kind=Kind.UNSPECIFIED;
    private String ownerText="";
    public OwnerIntent(String goal){update(goal);}
    public Kind kind(){return kind;}
    public boolean deviceReadAllowed(){return kind!=Kind.DISCUSS;}
    public boolean effectsAllowed(){return kind==Kind.ACT||kind==Kind.UNSPECIFIED;}
    /** Same input is evaluated once, without another inference, permission or retry budget. */
    public synchronized void update(String text){
        String next=text==null?"":text.trim();if(next.equals(ownerText))return;ownerText=next;
        Kind classified=classify(next);
        if(classified!=Kind.UNSPECIFIED||kind!=Kind.READ&&kind!=Kind.DISCUSS)kind=classified;
    }
    public static Kind classify(String raw){
        String text=raw==null?"":raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
        if(text.isEmpty())return Kind.UNSPECIFIED;
        // Negated execution and questions about instructions take precedence over action words.
        if(text.matches("(?s).*(不要操作|别操作|不要执行|别执行|先别动|不要点击|只讨论|仅讨论|只解释|仅解释|只告诉我|do not (act|execute|click)|don't (act|execute|click)).*"))return Kind.DISCUSS;
        if(text.matches("(?s)^(该不该|要不要|你觉得|为什么|should i|what is|what are|why ).*"))return Kind.DISCUSS;
        if(text.matches("(?s)^(请|麻烦|可以|能否|你能|能不能|想|我想|先|只是|帮我|告诉我|给我|向我|讲讲|解释|说明|介绍|what|why|explain|tell me|show me|can you|could you|please|how|what's|what is|what are|is it|should i|would it).*" )
                &&text.matches("(?s).*(如何|怎么(做|操作|使用|转账|付款|支付|发消息|删除|设置)|原理|解释|说明|介绍|方案|how (to|do|can|would)|explain|tell me how|what (is|are)|why ).*"))return Kind.DISCUSS;
        if(text.matches("(?s).*(只读|仅读取|仅查询|仅研究|不要修改|别修改|不做修改|read.only|only (read|research|look up)|without (changing|modifying|writing)).*"))return Kind.READ;
        if(text.matches("(?s)^(请|麻烦|帮我|替我|现在|开始|继续|执行|打开|点击|输入|设置|查找|搜索|go ahead|please|can you|could you|i want you to|open|click|type|set|execute|continue).*"))return Kind.ACT;
        return Kind.UNSPECIFIED;
    }
    public boolean permits(String tool,Predicate<String> effect){
        if(tool==null)return false;
        // Unregistered tool names, including run_* aliases, have no native exception here.
        if(kind==Kind.DISCUSS)return false;
        return kind!=Kind.READ||!effect.test(tool);
    }
    public JSONArray filter(JSONArray tools,Predicate<String> effect)throws Exception {
        JSONArray kept=new JSONArray();for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);if(permits(tool.getJSONObject("function").getString("name"),effect))kept.put(tool);}return kept;
    }
    public JSONObject diagnostics()throws Exception{return new JSONObject().put("intent",kind.name().toLowerCase(Locale.ROOT)).put("source","owner_text_conservative_native_hint").put("grantsIssued",false).put("extraModelRequests",0);}
}
