package dev.pocketengine.features.devices;

import dev.pocketengine.features.automation.DeviceScheduleOrigin;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Narrow tools within the original ToolInvocation extension point. There is no receiving
 * task, phone resource, model call or automatic scheduling at this ingress. */
public final class DeviceScheduleDrafts {
    public static final String PROPOSE="automation_propose_schedule",QUERY="automation_get_schedule_draft";
    public static final String CAPABILITY="automation.schedule_draft";
    private DeviceScheduleDrafts(){}
    public static JSONArray tools(){return new JSONArray().put(PROPOSE).put(QUERY);}
    public static JSONObject resource()throws Exception{return new JSONObject().put("screenMode","capabilities");}
    public static boolean handles(DeviceTaskEnvelope request){return request!=null&&"tool.invoke".equals(request.method())&&Set.of(PROPOSE,QUERY).contains(request.toolInvocation().toolName());}
    /** Only the actual authenticated ingress can create this carrier. */
    static Admission admit(PairingCoordinator.Permission permission)throws Exception{return new Admission(permission);}
    public static final class Admission {
        private final PairingCoordinator.Permission permission;
        private final DeviceTaskEnvelope request;
        private final boolean query;
        private final String origin;
        private Admission(PairingCoordinator.Permission permission)throws Exception{
            this.permission=java.util.Objects.requireNonNull(permission);request=permission.envelope();
            if(!handles(request))throw new SecurityException("不是原设备计划草稿入口");query=QUERY.equals(request.toolInvocation().toolName());
            requireShape(request,query);requireGrant(permission.grant());permission.checkOriginalGrantCurrent(System.currentTimeMillis());
            JSONObject arguments=request.toolInvocation().arguments();String operation=query?arguments.getString("operationId"):request.operationId(),digest=query?arguments.getString("argumentsDigest"):request.argumentsDigest();
            JSONObject source=new JSONObject().put("kind",DeviceScheduleOrigin.KIND).put("ownerId","owner").put("spaceId","private")
                    .put("originDeviceId",request.originDeviceId()).put("callerDeviceId",request.callerDeviceId()).put("targetDeviceId",request.targetDeviceId()).put("instanceId",request.instanceId())
                    .put("certificateFingerprint",permission.certificateFingerprint()).put("grantId",permission.grantId()).put("authorizationEpoch",permission.authorizationEpoch()).put("grantFingerprint",permission.grantFingerprint()).put("grantExpiresAt",permission.grantExpiresAt())
                    .put("operationId",operation).put("argumentsDigest",digest).put("rootTaskId",request.rootTaskId()).put("localTaskId",request.localTaskId()).put("requestRunId",request.runId()).put("requestExecutorEpoch",request.executorEpoch());
            origin=DeviceScheduleOrigin.checked(source).toString();
        }
        public void checkCurrent()throws Exception{permission.checkOriginalGrantCurrent(System.currentTimeMillis());}
        public boolean queryOnly(){return query;}
        public DeviceTaskEnvelope request(){return request;}
        public JSONObject origin()throws Exception{return new JSONObject(origin);}
        public JSONObject draft()throws Exception{if(query)throw new SecurityException("原草稿查询不能创建新草稿");return new JSONObject(request.toolInvocation().arguments().toString());}
        public void requireOwnRow(JSONObject row)throws Exception{
            JSONObject from=DeviceScheduleOrigin.checked(row.getJSONObject("origin")),expected=origin();
            if(!DeviceScheduleOrigin.identity(from).equals(DeviceScheduleOrigin.identity(expected))||!from.getString("argumentsDigest").equals(expected.getString("argumentsDigest"))||!DeviceScheduleOrigin.authorityKey(from).equals(DeviceScheduleOrigin.authorityKey(expected)))throw new SecurityException("只能查询这次原认证来源提交的同一草稿");
        }
        public JSONObject receipt(JSONObject row)throws Exception{
            JSONObject expected=origin();if(row==null)return DeviceProtocol.operationReceipt(request.targetDeviceId(),request.instanceId(),expected.getString("operationId"),expected.getString("argumentsDigest"),"not_found",false,"unknown",new JSONObject().put("draftFound",false).put("replayAllowed",false).put("scheduled",false).put("executionAuthorized",false),null);
            requireOwnRow(row);JSONObject actual=row.getJSONObject("origin");
            JSONObject payload=new JSONObject().put("draftFound",true).put("proposalId",row.getString("id")).put("proposalDigest",row.getString("digest")).put("status",row.getString("status")).put("sourceCheckPending",row.optBoolean("sourceCheckPending"))
                    .put("reviewRequired",!"approved".equals(row.optString("status"))).put("schedulingEnteredByIngress",false).put("executionAuthorized",false).put("modelCalled",false).put("receiverTaskCreated",false).put("replayAllowed",false).put("bodyIncluded",false);
            return DeviceProtocol.operationReceipt(actual.getString("targetDeviceId"),actual.getString("instanceId"),actual.getString("operationId"),actual.getString("argumentsDigest"),"succeeded",true,"reported",payload,new JSONObject().put("acceptedAt",row.getLong("createdAt")).put("audience",DeviceScheduleOrigin.audience(actual,row.getLong("createdAt"))));
        }
        public JSONObject notAccepted(String code,boolean unknown)throws Exception{
            JSONObject expected=origin();return DeviceProtocol.operationReceipt(request.targetDeviceId(),request.instanceId(),expected.getString("operationId"),expected.getString("argumentsDigest"),unknown?"unknown":"rejected",false,unknown?"unknown":"not_dispatched",new JSONObject().put("code",code).put("queryOriginalOnly",true).put("replayAllowed",false).put("scheduled",false).put("executionAuthorized",false).put("modelCalled",false),null);
        }
    }
    public static void requireGrant(JSONObject grant)throws Exception{
        if(grant.optLong("toolBudget",-1)!=0||grant.optLong("modelBudget",-1)!=0||grant.optBoolean("allowDelegation")||grant.optInt("maxDelegationDepth",-1)!=0||grant.has("forwardingScope")||grant.has("historyRead"))throw new SecurityException("设备计划草稿须单独授予零执行预算范围");
        if(grant.optJSONArray("allowedOrigins")==null||grant.getJSONArray("allowedOrigins").length()!=1||!grant.optString("peerDeviceId").equals(grant.getJSONArray("allowedOrigins").optString(0)))throw new SecurityException("设备计划草稿只授予原直接配对设备");
        if(!PairingCoordinator.contains(grant.optJSONArray("capabilities"),"tool.invoke")||!PairingCoordinator.contains(grant.optJSONArray("toolNames"),PROPOSE)||!PairingCoordinator.contains(grant.optJSONArray("toolNames"),QUERY)||grant.getJSONArray("toolNames").length()!=2||grant.getJSONArray("resources").length()!=1||!DeviceProtocol.resourceEquals(resource(),grant.getJSONArray("resources").getJSONObject(0)))throw new SecurityException("设备计划草稿缺少准确的原工具及资源授权");
        JSONArray methods=grant.getJSONArray("capabilities");for(int i=0;i<methods.length();i++)if(!Set.of("device.describe","tool.invoke").contains(methods.getString(i)))throw new SecurityException("计划草稿授权不能混入任务执行或控制权限");
    }
    private static void requireShape(DeviceTaskEnvelope request,boolean query)throws Exception{
        if(!request.originDeviceId().equals(request.callerDeviceId())||request.json().optJSONArray("delegationChain")!=null&&request.json().getJSONArray("delegationChain").length()!=0||!DeviceProtocol.VERSION.equals(request.toolInvocation().capabilityVersion())||!DeviceProtocol.resourceEquals(resource(),request.resourceBinding()))throw new SecurityException("设备草稿仅接受直接配对的准确能力资源");
        if(request.budget().getLong("toolCalls")!=0||request.budget().getLong("modelCalls")!=0||!(query?"read":"write").equals(request.toolInvocation().effect())||request.json().has("observationRef")||request.json().has("dependencySnapshot"))throw new SecurityException("计划草稿不能携带执行预算、屏幕观察或其他依赖权限");
        JSONObject arguments=request.toolInvocation().arguments();
        if(query){DeviceProtocol.keys(arguments,Set.of("operationId","argumentsDigest"),Set.of());}
        else{DeviceProtocol.keys(arguments,Set.of("title","kind","schedule"),Set.of("goal"));if(!Set.of("reminder","phone_goal").contains(arguments.optString("kind"))||!(arguments.opt("title") instanceof String)||arguments.getString("title").isBlank()||arguments.getString("title").length()>200||arguments.optJSONObject("schedule")==null)throw new IllegalArgumentException("设备计划草稿内容无效");if("phone_goal".equals(arguments.getString("kind"))&&(!(arguments.opt("goal") instanceof String)||arguments.getString("goal").isBlank()||arguments.getString("goal").length()>4000)||"reminder".equals(arguments.getString("kind"))&&arguments.has("goal"))throw new IllegalArgumentException("请明确提醒或等待主人启动的手机目标");dev.pocketengine.features.automation.AutomationBook.checkedScheduleProposal(arguments);}
    }
}
