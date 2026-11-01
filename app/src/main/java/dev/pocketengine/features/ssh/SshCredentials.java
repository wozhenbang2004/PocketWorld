package dev.pocketengine.features.ssh;

import org.json.JSONObject;

/** Blank settings fields preserve only credentials bound to the same profile/endpoint/auth method. */
final class SshCredentials {
    private SshCredentials(){}
    static JSONObject merge(SshProfile old,SshProfile next,JSONObject request,JSONObject stored)throws Exception {
        boolean retain=old!=null&&old.id.equals(next.id)&&old.binding().equals(next.binding())&&old.authType.equals(next.authType);
        JSONObject result=retain?new JSONObject(stored.toString()):new JSONObject();
        if(request.optBoolean("clearCredentials"))return new JSONObject();
        for(String key:new String[]{"password","privateKey","passphrase"})if(request.has(key)&&!(request.opt(key) instanceof String))throw new IllegalArgumentException("SSH凭据字段无效");
        if(next.authType.equals("password")){
            result.remove("privateKey");result.remove("passphrase");String password=request.optString("password");
            if(!password.isEmpty())result.put("password",password);
            if(request.optString("privateKey").length()>0)throw new IllegalArgumentException("密码认证不可同时提交私钥");
        }else{
            result.remove("password");String key=request.optString("privateKey");
            if(!key.isEmpty()){result.put("privateKey",key).put("passphrase",request.optString("passphrase"));}
            else if(!request.optString("passphrase").isEmpty())result.put("passphrase",request.getString("passphrase"));
            if(request.optBoolean("clearPassphrase"))result.put("passphrase","");
            if(!request.optString("password").isEmpty())throw new IllegalArgumentException("私钥认证不可同时提交密码");
            if(result.length()>0&&result.optString("privateKey").isEmpty())throw new IllegalArgumentException("请先提供私钥，再设置口令");
        }
        if(result.toString().length()>65536)throw new IllegalArgumentException("SSH凭据过大");return result;
    }
}
