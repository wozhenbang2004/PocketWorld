package dev.pocketengine.features.capabilities;

import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.store.TaskStore;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-facing discovery metadata. Never constructs a task, phone session, grant or executor. */
public final class PermissionToolCatalog {
    private final Map<String,JSONObject> rows=new TreeMap<>();
    private final JSONArray notices=new JSONArray();
    private PermissionToolCatalog(){}
    public static JSONObject snapshot(FeatureHost host,TaskStore tasks)throws Exception {
        PermissionToolCatalog catalog=new PermissionToolCatalog();
        try {catalog.rows(HostToolSupervisor.permissionToolCatalog(host,tasks));}
        catch(Exception unavailable){catalog.notices.put("当前本机注册目录读取失败；下列内置定义仍可查看，已安装插件列表可能不完整。");}
        JSONArray phone=dev.pocketengine.runtime.AgentRunner.tools();
        phone=dev.pocketengine.phone.observation.PhoneTreeCapabilityPort.appendTools(phone);
        phone=dev.pocketengine.phone.observation.PhoneExtensionTools.append(phone);
        phone=dev.pocketengine.phone.observation.PhoneTextTargetCapabilityPort.appendTools(phone);
        catalog.definitions(phone,"phone",true);
        catalog.definitions(new JSONArray().put(dev.pocketengine.features.notifications.NotificationTaskCapabilityPort.definition()),"phone",true);
        catalog.names("phone","读取原显示已授权应用的实际状态，准确冷启动须原主人完整审批；不提供任意系统命令。","phone_app_state","phone_prepare_cold_start");
        catalog.names("phone","仅原准确冷启动审批的两步内部操作；不能单独取得执行权，禁止任一步会阻止开始完整冷启动。","phone_force_stop_app","phone_restart_app");
        catalog.definitions(dev.pocketengine.runtime.history.RecentScreenHistory.tools(),"phone",true);
        catalog.names("phone","需要对应手机观察或路线会话；规则不会启用这些会话。","phone_observation_help",
                dev.pocketengine.runtime.routes.PhoneRouteSession.LIST,dev.pocketengine.runtime.routes.PhoneRouteSession.REPLAY,dev.pocketengine.runtime.routes.PhoneRouteSession.FORGET);
        for(String command:new String[]{"observe","navigate","click","type","scroll","back","forward","reload"})catalog.descriptor(CapabilityDescriptor.browser(command));
        for(String command:new String[]{"listFiles","readFile","exec","writeFile"})catalog.descriptor(CapabilityDescriptor.ssh(command));
        catalog.descriptor(CapabilityDescriptor.createText());catalog.descriptor(CapabilityDescriptor.createDocument());catalog.descriptor(CapabilityDescriptor.document());
        catalog.descriptor(CapabilityDescriptor.transformImage());catalog.descriptor(CapabilityDescriptor.trimMedia());
        catalog.rows(dev.pocketengine.browser.BrowserTaskCapabilityPort.permissionToolCatalog());
        catalog.names("web","可选官方API厂商服务端搜索，必须原主人开启并对本次调用准确授权；不同厂商的用量与外部费用单独记录。",dev.pocketengine.features.web.ProviderHostedWebSearch.TOOL);
        // Static names only. DeviceCapabilityPort.tools() requires a live, original native route;
        // do not construct it or inspect a peer to populate this owner-facing directory.
        catalog.names("devices","对原任务明确选定、原生批准范围内的设备工具发起一次调用；仍须目标设备授权、准确路由、参数与剩余预算。目录和策略不会创建这些授权。","device_invoke_tool");
        catalog.names("devices","将原任务目标交给准确已授权设备，使用原范围与分配预算；不因连接或允许规则自动开始任务。","device_delegate_goal");
        catalog.names("devices","查询原设备操作的真实状态和回执；不重试未知动作，不将接收等同完成。","device_operation_get");
        catalog.rows(dev.pocketengine.features.local.project.ProjectCapabilityPort.permissionToolCatalog());
        catalog.definitions(dev.pocketengine.features.semantic.SemanticCapabilityPort.catalogDefinitions(true),"semantic",true);
        catalog.names("remote","只作用于原任务明确选定的远程电脑和环境。",
                dev.pocketengine.features.remote.RemoteTaskCapabilities.START,dev.pocketengine.features.remote.RemoteTaskCapabilities.READ);
        catalog.names("memory","按原任务或原私人对话的已授权范围读取，不恢复旧权限。","memory_raw_query","memory_raw_read",
                dev.pocketengine.features.memory.ConversationHistory.SEARCH,dev.pocketengine.features.memory.ConversationHistory.READ);
        // These conditional routes are registered by MediaTaskCapabilities; listing never probes a provider.
        catalog.names("media","需要已配置的媒体服务与本次输入授权；目录不表示服务已就绪。","media_generate_image","media_generate_video","media_edit_image","media_synthesize_speech","media_read_original");
        catalog.names("assistant","仅主人已开启的可逆偏好；不能修改系统或工具权限。",AgentPreferencePort.LIST,AgentPreferencePort.SET);
        catalog.definitions(dev.pocketengine.runtime.policy.RunOutcomeTools.tools(),"run",true);
        catalog.definitions(dev.pocketengine.core.ExecutionPlan.tools(true),"run",false);
        catalog.definitions(dev.pocketengine.core.ExecutionPlan.tools(false),"run",false);
        catalog.definitions(new JSONArray().put(dev.pocketengine.core.ScopedToolCatalog.searchDefinition()),"run",false);
        catalog.notices.put("目录列出内置声明与本机已注册插件，不等于本次任务已挂载。工作配置、群组、设备及系统权限仍由原范围控制。");
        catalog.notices.put("插件/MCP 仅显示本机当前注册的定义；未启用、未授权或未完成发现的工具可能尚未登记，请到对应来源核对。目录读取不会连接服务或执行工具。");
        catalog.notices.put("JS 扩展的工具名在原任务加载扩展后生成；此页不会为列目录运行扩展。可按实际调用的精确名称添加规则。跨设备定义也不表示已有原生设备路由或授权。");
        JSONArray values=new JSONArray();for(JSONObject row:catalog.rows.values())values.put(row);
        return new JSONObject().put("tools",values).put("notices",catalog.notices).put("metadataOnly",true).put("executionAuthorized",false);
    }
    private void rows(JSONArray values)throws Exception {for(int i=0;i<values.length();i++){JSONObject row=values.getJSONObject(i);rows.put(row.getString("name"),row);}}
    private void definitions(JSONArray definitions,String category,boolean configurable)throws Exception {
        for(int i=0;i<definitions.length();i++){JSONObject tool=definitions.getJSONObject(i).getJSONObject("function");
            put(tool.getString("name"),category,tool.optString("description"),"task_bound",configurable);}
    }
    private void descriptor(CapabilityDescriptor descriptor)throws Exception {definitions(new JSONArray().put(descriptor.definition()),descriptor.feature,true);}
    private void names(String category,String description,String...names)throws Exception {for(String name:names)put(name,category,description,"task_bound",true);}
    private void put(String name,String category,String description,String source,boolean configurable)throws Exception {
        if(!name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("invalid_permission_catalog_name");
        rows.putIfAbsent(name,new JSONObject().put("name",name).put("category",category).put("description",description).put("source",source).put("configurable",configurable));
    }
}
