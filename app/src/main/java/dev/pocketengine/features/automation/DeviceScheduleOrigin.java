package dev.pocketengine.features.automation;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Body-free provenance for a draft received by the existing paired device ingress.
 * Validation checks a stored shape, never authenticates a peer or grants execution. */
public final class DeviceScheduleOrigin {
    public static final String KIND="paired_device_schedule";
    private static final Set<String> FIELDS=Set.of("kind","ownerId","spaceId","originDeviceId","callerDeviceId","targetDeviceId","instanceId","certificateFingerprint","grantId","authorizationEpoch","grantFingerprint","grantExpiresAt","operationId","argumentsDigest","rootTaskId","localTaskId","requestRunId","requestExecutorEpoch");
    private DeviceScheduleOrigin(){}
    public static boolean isDevice(JSONObject origin){return origin!=null&&KIND.equals(origin.optString("kind"));}
    public static JSONObject checked(JSONObject origin)throws Exception{
        if(origin==null||origin.length()!=FIELDS.size())throw new SecurityException("设备草稿缺少原认证来源");
        for(var keys=origin.keys();keys.hasNext();)if(!FIELDS.contains(keys.next()))throw new SecurityException("设备草稿来源含未支持字段");
        if(!isDevice(origin)||!"owner".equals(origin.optString("ownerId"))||!"private".equals(origin.optString("spaceId")))throw new SecurityException("设备草稿不是本机主人待审范围");
        for(String key:new String[]{"originDeviceId","callerDeviceId","targetDeviceId"})if(!(origin.opt(key) instanceof String)||!origin.getString(key).matches("pw1_[a-f0-9]{64}"))throw new SecurityException("设备草稿来源身份无效");
        if(!origin.getString("originDeviceId").equals(origin.getString("callerDeviceId"))||origin.getString("callerDeviceId").equals(origin.getString("targetDeviceId")))throw new SecurityException("设备草稿只接受直接配对来源");
        for(String key:new String[]{"instanceId","grantId","rootTaskId","localTaskId","requestRunId"})if(!(origin.opt(key) instanceof String)||!origin.getString(key).matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))throw new SecurityException("设备草稿关联身份无效");
        if(!(origin.opt("operationId") instanceof String)||!origin.getString("operationId").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new SecurityException("设备草稿操作须保留原UUID");
        for(String key:new String[]{"certificateFingerprint","grantFingerprint","argumentsDigest"})if(!(origin.opt(key) instanceof String)||!origin.getString(key).matches("[a-f0-9]{64}"))throw new SecurityException("设备草稿原指纹无效");
        for(String key:new String[]{"authorizationEpoch","grantExpiresAt","requestExecutorEpoch"}){Object value=origin.opt(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1||((Number)value).longValue()>9007199254740991L)throw new SecurityException("设备草稿原代次无效");}
        return new JSONObject(origin.toString());
    }
    public static String identity(JSONObject origin)throws Exception{checked(origin);return "device:"+origin.getString("targetDeviceId")+":"+origin.getString("callerDeviceId")+":"+origin.getString("operationId");}
    /** Revoking/changing a credential quarantines the original rows; it never erases their body. */
    public static String authorityKey(JSONObject origin)throws Exception{
        checked(origin);JSONArray result=new JSONArray();for(String field:new String[]{"kind","callerDeviceId","targetDeviceId","instanceId","certificateFingerprint","grantId","authorizationEpoch","grantFingerprint","grantExpiresAt"})result.put(origin.get(field));return result.toString();
    }
    public static JSONObject audience(JSONObject origin,long acceptedAt)throws Exception{
        checked(origin);JSONObject out=new JSONObject().put("kind","operation").put("objectId",origin.getString("operationId"));
        for(String field:new String[]{"originDeviceId","callerDeviceId","targetDeviceId","instanceId","rootTaskId","localTaskId","grantId","authorizationEpoch","argumentsDigest","requestRunId","requestExecutorEpoch"})out.put(field,origin.get(field));
        return out.put("resourceBinding",new JSONObject().put("screenMode","capabilities")).put("acceptedAt",acceptedAt).put("retainedUntil",origin.getLong("grantExpiresAt"));
    }
}
