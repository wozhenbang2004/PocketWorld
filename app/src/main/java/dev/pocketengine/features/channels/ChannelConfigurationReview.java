package dev.pocketengine.features.channels;

import org.json.JSONArray;
import org.json.JSONObject;

/** Finite comparisons of the existing native configuration; never a second configuration store. */
public final class ChannelConfigurationReview {
    private ChannelConfigurationReview() { }

    public static void profile(JSONObject actual,String expectedEpoch)throws Exception {
        if(expectedEpoch==null)throw new SecurityException("请重新读取原渠道配置");
        if(expectedEpoch.isEmpty()) {
            if(actual!=null)throw new SecurityException("新连接编号已存在，未覆盖已有连接");
        } else if(actual==null||!expectedEpoch.equals(epoch(actual,"transportEpoch"))) {
            throw new SecurityException("原渠道配置已改变或移除，请重新读取后再操作");
        }
    }

    public static void binding(JSONObject actual,String expectedBinding,String expectedGrant)throws Exception {
        if(expectedBinding==null||expectedGrant==null)throw new SecurityException("请重新核对原主人绑定");
        if(actual==null) {
            if(!expectedBinding.isEmpty()||!expectedGrant.isEmpty())throw new SecurityException("原主人绑定已移除，未作用于另一账号");
        } else if(!expectedBinding.equals(epoch(actual,"bindingEpoch"))||!expectedGrant.equals(epoch(actual,"taskGrantEpoch"))) {
            throw new SecurityException("原主人绑定或任务权限已改变，请重新核对后确认");
        }
    }

    /** Capture before requesting the QR. A late result may not overwrite a later local edit. */
    static JSONArray loginProfiles(JSONArray profiles)throws Exception {
        JSONArray result=new JSONArray();
        for(int i=0;i<profiles.length();i++) {
            JSONObject row=profiles.getJSONObject(i);
            if(!"weixin".equals(row.optString("provider")))continue;
            result.put(new JSONObject().put("id",required(row,"id")).put("appId",required(row,"appId"))
                    .put("transportEpoch",epoch(row,"transportEpoch")));
        }
        return result;
    }

    static JSONObject loginTarget(JSONArray captured,JSONArray current,String bot)throws Exception {
        if(bot==null||bot.isEmpty())throw new SecurityException("官方登录未返回原账号身份");
        JSONObject prior=matching(captured,bot,false),actual=matching(current,bot,true);
        if(prior==null) {
            if(actual!=null)throw new SecurityException("此账号已在扫码期间新增，请重新核对；未替换当前凭据");
        } else {
            if(actual==null||!required(prior,"id").equals(required(actual,"id")))throw new SecurityException("原微信连接已移除或重建，未恢复旧连接");
            profile(actual,epoch(prior,"transportEpoch"));
        }
        return actual==null?null:new JSONObject(actual.toString());
    }

    private static JSONObject matching(JSONArray rows,String bot,boolean providerRequired)throws Exception {
        JSONObject found=null;
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);
            if(providerRequired&&!"weixin".equals(row.optString("provider")))continue;
            if(!bot.equals(row.optString("appId")))continue;
            if(found!=null)throw new SecurityException("原微信账号配置不唯一，请先在本机核对");
            found=row;
        }
        return found;
    }
    private static String epoch(JSONObject row,String key)throws Exception {return required(row,key);}
    private static String required(JSONObject row,String key)throws Exception {
        Object value=row.opt(key);
        if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>180)
            throw new SecurityException("原渠道身份版本不完整，请重新读取");
        return (String)value;
    }
}
