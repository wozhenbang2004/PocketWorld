package dev.pocketengine.features.devices;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.Normalizer;
import org.json.JSONObject;

/** Owner presentation only. Never call from discovery, candidate/grant signing, tool hints or routing. */
final class DeviceOwnerPeerNames {
    private final SharedPreferences prefs;
    DeviceOwnerPeerNames(Context context) { prefs=context.getSharedPreferences("device-owner-peer-names-v1",Context.MODE_PRIVATE); }
    static String normalized(String input) {
        if(input==null || input.length()>128)throw new IllegalArgumentException("备注最多32个字符");
        String value=Normalizer.normalize(input,Normalizer.Form.NFC); int first=-1,last=0; boolean visible=false;
        for(int i=0;i<value.length();){
            int ch=value.codePointAt(i);i+=Character.charCount(ch);int type=Character.getType(ch);
            if(Character.isISOControl(ch) || type==Character.FORMAT || type==Character.SURROGATE ||
                    type==Character.LINE_SEPARATOR || type==Character.PARAGRAPH_SEPARATOR)
                throw new IllegalArgumentException("备注须为一行可见文字，不能包含控制符或方向标记");
            if(!Character.isSpaceChar(ch) && !Character.isWhitespace(ch)) {
                if(first<0)first=i-Character.charCount(ch); last=i;
                if(type!=Character.NON_SPACING_MARK && type!=Character.ENCLOSING_MARK && type!=Character.COMBINING_SPACING_MARK)visible=true;
            }
        }
        if(first<0)return "";
        value=value.substring(first,last);
        if(!visible || value.codePointCount(0,value.length())>32)throw new IllegalArgumentException("请使用最多32个可见字符");
        return value;
    }
    private String key(DeviceIdentity.LocalIdentity here,String id,String instance,String fp)throws Exception{
        return DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONObject()
                .put("here",here.deviceId()).put("hereInstance",here.instanceId()).put("hereFingerprint",here.certificateFingerprint())
                .put("peer",id).put("peerInstance",instance).put("peerFingerprint",fp)));
    }
    String get(DeviceIdentity.LocalIdentity here,String id,String instance,String fp)throws Exception{
        if(here==null||fp==null||!fp.matches("[a-f0-9]{64}")||instance==null||instance.isEmpty())return "";
        String value=prefs.getString(key(here,id,instance,fp),"");
        try { return normalized(value); } catch (IllegalArgumentException unreadable) { return ""; }
    }
    synchronized void change(DeviceWorkspaceNativeOwner owner,String localId,String localInstance,String localFp,String id,String instance,String fp,String expected,String next)throws Exception {
        DeviceIdentity.LocalIdentity here=owner.existingIdentity(); JSONObject peer=owner.trust.peer(id);
        if(here==null || !localId.equals(here.deviceId()) || !localInstance.equals(here.instanceId()) || !localFp.equals(here.certificateFingerprint())
                || peer==null || !instance.equals(peer.optString("instanceId")) || !fp.equals(peer.optString("fingerprint")) ||
                !fp.matches("[a-f0-9]{64}") || here.deviceId().equals(id))throw new SecurityException("当前设备身份已变化，未修改备注");
        String name=normalized(next); if(peer.optBoolean("revoked",true) && !name.isEmpty())throw new SecurityException("不能给已撤销的身份添加备注");
        if(!get(here,id,instance,fp).equals(expected))throw new SecurityException("原备注已变化，请重新打开后再修改");
        String key=key(here,id,instance,fp); SharedPreferences.Editor edit=prefs.edit();
        if(name.isEmpty())edit.remove(key);else edit.putString(key,name);
        if(!edit.commit())throw new java.io.IOException("本机没有保存这次备注修改");
    }
}
