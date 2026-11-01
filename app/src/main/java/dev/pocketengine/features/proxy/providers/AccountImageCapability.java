package dev.pocketengine.features.proxy.providers;

import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit selected-token catalog evidence only. Conflicts and malformed declarations are unknown. */
public final class AccountImageCapability {
    public static final String SOURCE="https://api.openai.com/v1/models";
    private AccountImageCapability(){}
    static JSONObject parse(JSONObject model)throws Exception {
        Boolean modalities=null,capability=null;boolean malformed=false;
        if(model.has("input_modalities")){
            JSONArray values=model.optJSONArray("input_modalities");
            if(values==null||values.length()==0||values.length()>16)malformed=true;
            else{java.util.Set<String> seen=new java.util.HashSet<>();boolean image=false;
                for(int i=0;i<values.length();i++){Object raw=values.opt(i);if(!(raw instanceof String)||!((String)raw).matches("[a-z_]{1,24}")||!seen.add((String)raw))malformed=true;else if(raw.equals("image"))image=true;}
                modalities=image;
            }
        }
        if(model.has("capabilities")){
            JSONObject capabilities=model.optJSONObject("capabilities");
            if(capabilities==null)malformed=true;
            else if(capabilities.has("image_input")){
                Object raw=capabilities.opt("image_input");if(raw instanceof JSONObject)raw=((JSONObject)raw).opt("supported");
                if(raw instanceof Boolean)capability=(Boolean)raw;else malformed=true;
            }
        }
        Boolean value=modalities!=null?modalities:capability;
        if(malformed||(modalities!=null&&capability!=null&&!modalities.equals(capability)))value=null;
        return new JSONObject().put("known",value!=null).put("value",value==null?JSONObject.NULL:value)
            .put("source",SOURCE).put("observedAt",System.currentTimeMillis());
    }
    public static boolean supported(JSONObject evidence){return evidence!=null&&evidence.optBoolean("known")&&Boolean.TRUE.equals(evidence.opt("value"))&&SOURCE.equals(evidence.optString("source"));}
}
