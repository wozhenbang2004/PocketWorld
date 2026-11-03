package dev.pocketengine.features.remote;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure native-catalog selection check. A JSON value is never an owner approval. */
public final class RemoteModelOptions {
    private RemoteModelOptions(){}
    public static JSONObject select(JSONObject source,JSONObject requested)throws Exception {
        String worker=source.optString("worker"),protocol=worker.equals("claude")?"claude_initialize_model_options_v1":worker.equals("codex_app_server")?"codex_model_options_v1":"unsupported";
        if(!worker.matches("claude|codex_app_server"))throw new SecurityException("此worker没有已核原生模型/档位选择");
        for(var keys=requested.keys();keys.hasNext();)if(!Set.of("model","effort","modelOptionsDigest").contains(keys.next()))throw new SecurityException("模型选择不接受其它参数或权限");
        JSONObject options=source.optJSONObject("modelOptions");String digest=text(requested,"modelOptionsDigest",64),model=text(requested,"model",200);
        if(!digest.matches("[a-f0-9]{64}")||options==null||!protocol.equals(options.optString("protocol"))||!digest.equals(options.optString("digest"))||options.opt("runningTurnChanges")!=Boolean.FALSE)throw new SecurityException("原CLI/模型目录代次已变或未知，请重读原任务");
        JSONArray modes=options.optJSONArray("changeModes");boolean next=false;for(int i=0;modes!=null&&i<modes.length();i++)next|="settled_continuation".equals(modes.optString(i));if(!next)throw new SecurityException("原driver未声明已结算下一轮选择");
        JSONArray models=options.optJSONArray("models");if(models==null||models.length()>100)throw new SecurityException("原模型目录缺失或超限");JSONObject selected=null;
        for(int i=0;i<models.length();i++){JSONObject row=models.getJSONObject(i);if(model.equals(row.optString("id"))||model.equals(row.optString("resolvedModel"))){if(selected!=null)throw new SecurityException("模型身份有歧义");selected=row;}}
        if(selected==null)throw new SecurityException("所选模型不在此原native目录");JSONObject result=new JSONObject().put("model",model).put("modelOptionsDigest",digest);
        if(requested.has("effort")){String effort=text(requested,"effort",80);JSONArray efforts=selected.optJSONArray("efforts");boolean found=false;for(int i=0;efforts!=null&&i<efforts.length();i++)found|=effort.equals(efforts.getJSONObject(i).optString("id"));if(!found)throw new SecurityException("此原模型未声明所选档位；不会取近似值");result.put("effort",effort);}
        return result;
    }
    private static String text(JSONObject value,String key,int maximum)throws Exception {Object raw=value.opt(key);if(!(raw instanceof String))throw new IllegalArgumentException("模型选择字段无效");String text=(String)raw;if(text.isEmpty()||text.length()>maximum||!text.equals(text.trim()))throw new IllegalArgumentException("模型选择字段无效");for(int i=0;i<text.length();i++)if(text.charAt(i)<32)throw new IllegalArgumentException("模型选择字段含控制字符");return text;}
}
