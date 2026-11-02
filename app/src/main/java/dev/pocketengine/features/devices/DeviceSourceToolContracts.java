package dev.pocketengine.features.devices;

import dev.pocketengine.features.plugins.PluginRules;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only source adapter for the implemented receiver parsers, not another wire protocol or
 * authority. PC contracts: cf118f3 desktop_adapter/desktop_jobs/device_companion; phone: this
 * version's DeviceToolsRuntime. Unknown versions/names are not advertised or dispatched. The
 * target still validates its actual lease, observation, native path and grant at execution. */
final class DeviceSourceToolContracts {
    private DeviceSourceToolContracts() { }
    static String effect(String version,String name){
        if("desktop-v2".equals(version))return switch(name){case "desktop.observe"->"read";case "desktop.act","desktop.control"->"write";default->null;};
        if("workspace-v1".equals(version))return switch(name){case "workspace.list","workspace.read","workspace.export","workspace.project.export","workspace.location"->"read";case "workspace.write","workspace.receive","workspace.project.receive","workspace.project.confirm","command.run"->"write";default->null;};
        return DeviceProtocol.VERSION.equals(version)&&DeviceToolsRuntime.supports(name)?DeviceToolsRuntime.effect(name):null;
    }
    static JSONArray variants(String version,String name)throws Exception{
        JSONArray out=new JSONArray();if(effect(version,name)==null)return out;
        if(DeviceProtocol.VERSION.equals(version)){
            JSONObject p=new JSONObject();String[] required=new String[0];
            switch(name){
                case "phone_observe" -> p.put("includeImage",bool());
                case "phone_launch_app" -> {p.put("packageName",text(255));required=new String[]{"packageName"};}
                case "phone_click_node" -> {p.put("nodeId",text(512));required=new String[]{"nodeId"};}
                case "phone_type" -> {p.put("nodeId",text(512)).put("text",text(12000));required=new String[]{"nodeId","text"};}
                case "phone_tap" -> {p.put("x",integer(0,1000)).put("y",integer(0,1000));required=new String[]{"x","y"};}
                case "phone_swipe" -> {for(String k:new String[]{"x1","y1","x2","y2"})p.put(k,integer(0,1000));p.put("durationMs",integer(100,2000));required=new String[]{"x1","y1","x2","y2","durationMs"};}
            }return out.put(object(p,required));
        }
        if("workspace-v1".equals(version)){
            JSONObject p=new JSONObject();String[] required=new String[0];
            switch(name){
                case "workspace.list" -> p.put("path",text(2048));
                case "workspace.location" -> { }
                case "workspace.read","workspace.export" -> {p.put("path",text(2048));required=new String[]{"path"};}
                case "workspace.project.export" -> {p.put("receiveOperationId",text(128));required=new String[]{"receiveOperationId"};}
                case "workspace.project.confirm" -> {p.put("receiveOperationId",text(128)).put("manifestDigest",text(64)).put("path",text(2048)).put("expectedVersion",enumeration("absent"));required=new String[]{"receiveOperationId","manifestDigest","path","expectedVersion"};}
                case "workspace.receive","workspace.project.receive" -> {p.put("path",text(2048)).put("expectedVersion",text(71)).put("artifact",new JSONObject().put("type","object"));required=new String[]{"path","expectedVersion","artifact"};}
                case "workspace.write" -> {p.put("path",text(2048)).put("content",text(262144)).put("expectedVersion",text(71));required=new String[]{"path","content","expectedVersion"};}
                case "command.run" -> {p.put("argv",new JSONObject().put("type","array").put("items",text(12000)).put("minItems",1).put("maxItems",80)).put("timeoutSeconds",integer(1,3600));required=new String[]{"argv"};}
            }return out.put(object(p,required));
        }
        if(name.equals("desktop.observe"))return out.put(object(new JSONObject().put("discovery",enumeration(true)),"discovery")).put(object(new JSONObject().put("pid",integer(1,4294967295L)).put("windowId",integer(1,DeviceProtocol.MAX_SAFE_INTEGER)),"pid","windowId")).put(object(new JSONObject()));
        if(name.equals("desktop.control"))return out.put(object(new JSONObject().put("action",enumeration("acquire")).put("durationMs",integer(1000,30000)),"action")).put(object(new JSONObject().put("action",enumeration("release")).put("lease",lease()),"action","lease"));
        for(String kind:new String[]{"click","insert_text","set_text","hotkey","press_key","scroll"}){
            int targets=kind.equals("click")?2:1;
            for(int target=0;target<targets;target++)for(boolean foreground:new boolean[]{false,true}){
                if(foreground&&kind.equals("set_text"))continue;
                JSONObject p=new JSONObject().put("kind",enumeration(kind)).put("lease",lease()).put("verification",verification()).put("deliveryMode",enumeration(foreground?"foreground":"background"));JSONArray required=new JSONArray().put("kind").put("lease");
                if(foreground){p.put("foregroundAfterOperationId",text(128));required.put("deliveryMode").put("foregroundAfterOperationId");}
                switch(kind){
                    case "click" -> {p.put("button",enumeration("left","right","middle")).put("count",integer(1,3));if(target==0){p.put("elementToken",token());required.put("elementToken");}else{p.put("x",integer(0,1199)).put("y",integer(0,1199));required.put("x").put("y");}}
                    case "insert_text" -> {p.put("text",text(12000));required.put("text");}
                    case "set_text" -> {p.put("elementToken",token()).put("text",text(12000));required.put("elementToken").put("text");}
                    case "hotkey" -> {p.put("keys",new JSONObject().put("type","array").put("items",text(20).put("pattern","^[A-Za-z0-9_]{1,20}$")).put("minItems",2).put("maxItems",5));required.put("keys");}
                    case "press_key" -> {p.put("key",enumeration("Enter","Escape","Tab"));required.put("key");}
                    case "scroll" -> {p.put("direction",enumeration("up","down","left","right")).put("amount",integer(1,50)).put("by",enumeration("line","page"));required.put("direction");}
                }
                out.put(new JSONObject().put("type","object").put("properties",p).put("required",required).put("additionalProperties",false));
            }
        }return out;
    }
    static String guidance(String name){return switch(name){
        case "desktop.observe" -> "{discovery:true}只列进程/窗口，不是action-ready。用准确pid+windowId取该窗口的新observationRef、state.elements[element_token]及真实capture。{}仅整显示观察。";
        case "desktop.control" -> "先acquire取得原job的payload.desktopReceipt.lease；每次act传该准确lease。HELD过期不自动转让：只可显式release原lease再acquire；unknown/NEEDS_RECONCILE不可自动释放或重放。";
        case "desktop.act" -> "必须外层observationRef=最新实际送入当前模型的同窗口frame，arguments.lease来自已结算的desktop.control。set_text用目标UIA ValuePattern完整替换（text空串为清空；固定UIA省略空值/读失败时只可回unknown，不能把缺value当清空成功或重放）；不支持时明确拒绝，不自动按键清空或降为追加。insert_text只插入，不混坐标focus+输入。每次act后改用payload.desktopReceipt.afterObservation的新ref，不复用旧ref。verification仅证明新观察的窗口条件，不证明文件保存；没条件或unknown不得称业务成功。foreground仅准确known-no-input background拒绝后独立新操作+新ref，禁止自动升级。";
        case "workspace.location" -> "只用{}读取该准确已授权workspace的native rootPath/pathStyle，供SaveAs定位；不能猜Documents/cwd或从binding反推。locationOnly=true，guiDirectorySandbox=false；不代表GUI具备OS目录写入沙箱。";
        case "workspace.write" -> "仅workspace相对path；expectedVersion来自原workspace.read的sha256版本，新文件为absent。不能覆盖版本不明/已变文件。";
        case "workspace.project.export" -> "只回读本任务已原生审阅导入的receiveOperationId所标识的新项目副本，目标按原清单封装修改字节；不读取整个workspace。收到后仅落原附件，需主人选包审阅导入。";
        case "workspace.project.receive" -> "仅native owner已选择原固定项目包materialId；接收只staging，未导入，随后必须等原机主再次审阅清单再继续原任务。";
        case "workspace.project.confirm" -> "只能确认原机主已在设备协作范围二次审阅的准确receiveOperationId；仅新副本，不恢复历史环境权限或运行代码。";
        case "workspace.receive" -> "只允许原主人在设备协作范围中选择的materialId；固定文件、目标路径与CAS版本由native填入，目标最多256KiB。queued不等于送达，查原operation确认CAS结果。";
        case "workspace.export" -> "相对path导出原实际文件bytes/hash及ArtifactDescriptor；只有verified bytes入库才是成果，不凭窗口标题或输入已派发声称已保存。";
        case "command.run" -> "明确argv数组，不是prompt或shell字符串；目标原生allowlist/timeout仍核验，不在PC调用外部Agent。";
        default -> "参数必须符合该固定receiver版本；node/ref来自当前准确授权观察，未知先查原operation，不换ID重发。";
    };}
    static void validate(String version,String name,JSONObject args)throws Exception{
        requireWireNumbers(args);JSONArray choices=variants(version,name);boolean matched=false;for(int i=0;i<choices.length();i++)try{PluginRules.validateArguments(args,plain(choices.getJSONObject(i)));matched=true;break;}catch(IllegalArgumentException invalid){ }
        if(!matched)throw new SecurityException("目标工具参数不符合该实际receiver版本的准确schema");
        if(name.equals("phone_launch_app")&&!args.getString("packageName").matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new SecurityException("应用身份无效");
        if(args.has("nodeId")&&args.getString("nodeId").isEmpty())throw new SecurityException("节点必须来自原观察");
        if(name.startsWith("workspace.")&&args.has("path")){String path=args.getString("path");if(path.length()>2048||path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf(':')>=0||path.chars().anyMatch(c->c<32)||path.isEmpty()&&!name.equals("workspace.list"))throw new SecurityException("只能使用准确workspace相对路径");if(!path.isEmpty())for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new SecurityException("相对路径不能越界");}
        if(name.equals("workspace.write")&&(!args.getString("expectedVersion").matches("absent|sha256:[a-f0-9]{64}")||bytes(args.getString("content"))>262144))throw new SecurityException("文件版本或实际UTF8字节界限无效");
        if(name.equals("workspace.project.confirm")&&(!args.getString("expectedVersion").equals("absent")||!args.getString("manifestDigest").matches("[a-f0-9]{64}")))throw new SecurityException("项目只可原清单absent-only新副本导入");
        if(name.equals("workspace.receive")||name.equals("workspace.project.receive")){boolean project=name.equals("workspace.project.receive");var ref=dev.pocketengine.features.devices.transport.DeviceArtifactTransfer.fromWireDescriptor(args.getJSONObject("artifact"));if(!args.getString("expectedVersion").matches("absent|sha256:[a-f0-9]{64}")||!ref.json().getString("kind").equals(project?"project_bundle":"file")||ref.bytes>(project?20971520:262144)||project&&!args.getString("expectedVersion").equals("absent"))throw new SecurityException("接收文件/CAS版本/256KiB上限无效");}
        if(name.equals("command.run")){JSONArray argv=args.getJSONArray("argv");if(argv.length()<1)throw new SecurityException("argv不能为空");for(int i=0;i<argv.length();i++)if(argv.getString(i).indexOf(0)>=0)throw new SecurityException("argv含NUL");}
        if(name.equals("desktop.act")){
            if(args.has("elementToken")&&!args.getString("elementToken").matches("s[0-9a-f]{8}:[0-9]+"))throw new SecurityException("不是原窗口element_token");
            if(args.has("text")&&((args.getString("text").isEmpty()&&!args.optString("kind").equals("set_text"))||bytes(args.getString("text"))>12000||args.getString("text").indexOf(0)>=0))throw new SecurityException("桌面输入必须是有界UTF8文本");
            if(args.has("keys")){JSONArray keys=args.getJSONArray("keys");if(keys.length()<2)throw new SecurityException("hotkey需2至5个键");for(int i=0;i<keys.length();i++)if(!keys.getString(i).matches("[A-Za-z0-9_]{1,20}"))throw new SecurityException("快捷键身份无效");}
            JSONObject condition=args.optJSONObject("verification");if(condition!=null)validateCondition(condition);
        }
        if(args.has("lease")&&!args.getJSONObject("lease").getString("id").matches("[a-f0-9]{32}"))throw new SecurityException("租约必须来自目标原native control回执");
    }
    private static void validateCondition(JSONObject value)throws Exception{
        String kind=value.getString("kind");Set<String> required=switch(kind){case "window_title_equals"->Set.of("kind","value");case "element_value_equals"->Set.of("kind","selector","value");case "element_present","element_absent"->Set.of("kind","selector");default->throw new SecurityException("未知窗口验证条件");};if(!DeviceProtocol.objectKeys(value).equals(required))throw new SecurityException("窗口验证条件缺少准确字段");if(value.has("value")&&bytes(value.getString("value"))>12000)throw new SecurityException("验证文本UTF8过大");if(value.has("selector")){JSONObject selector=value.getJSONObject("selector");if(selector.length()<1)throw new SecurityException("验证selector不能为空");for(String key:DeviceProtocol.objectKeys(selector))if(!Set.of("role","label").contains(key)||selector.getString(key).isEmpty()||selector.getString(key).codePointCount(0,selector.getString(key).length())>500)throw new SecurityException("验证selector无效");}}
    static JSONObject plain(JSONObject schema)throws Exception{JSONObject copy=new JSONObject(schema.toString());for(String key:new String[]{"anyOf","minItems","minLength","pattern","description"})copy.remove(key);JSONObject props=copy.optJSONObject("properties");if(props!=null)for(String key:DeviceProtocol.objectKeys(props))props.put(key,plain(props.getJSONObject(key)));if(copy.optJSONObject("items")!=null)copy.put("items",plain(copy.getJSONObject("items")));return copy;}
    private static void requireWireNumbers(Object value)throws Exception{if(value instanceof Number)DeviceProtocol.integer(value,"tool numeric field",-DeviceProtocol.MAX_SAFE_INTEGER,DeviceProtocol.MAX_SAFE_INTEGER);else if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(String field:DeviceProtocol.objectKeys(object))requireWireNumbers(object.get(field));}else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)requireWireNumbers(array.get(i));}}
    private static int bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    private static JSONObject text(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    private static JSONObject bool()throws Exception{return new JSONObject().put("type","boolean");}
    private static JSONObject integer(long min,long max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
    private static JSONObject enumeration(Object... values)throws Exception{return new JSONObject().put("type",values[0] instanceof Boolean?"boolean":"string").put("enum",new JSONArray(values));}
    private static JSONObject token()throws Exception{return text(128).put("pattern","^s[0-9a-f]{8}:[0-9]+$");}
    private static JSONObject object(JSONObject properties,String... required)throws Exception{return new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false);}
    private static JSONObject lease()throws Exception{return object(new JSONObject().put("id",text(32).put("pattern","^[a-f0-9]{32}$")).put("fence",integer(1,DeviceProtocol.MAX_SAFE_INTEGER)).put("expiresAt",integer(1,DeviceProtocol.MAX_SAFE_INTEGER)).put("resource",text(256)),"id","fence","expiresAt","resource");}
    private static JSONObject verification()throws Exception{JSONObject selector=object(new JSONObject().put("role",text(500)).put("label",text(500)));JSONObject properties=new JSONObject().put("kind",enumeration("window_title_equals","element_value_equals","element_present","element_absent")).put("value",text(12000)).put("selector",selector);JSONArray exact=new JSONArray();for(String kind:new String[]{"window_title_equals","element_value_equals","element_present","element_absent"}){JSONObject p=new JSONObject().put("kind",enumeration(kind));JSONArray required=new JSONArray().put("kind");if(!kind.equals("window_title_equals")){p.put("selector",selector);required.put("selector");}if(kind.endsWith("equals")){p.put("value",text(12000));required.put("value");}exact.put(new JSONObject().put("type","object").put("properties",p).put("required",required).put("additionalProperties",false));}return object(properties,"kind").put("anyOf",exact);}
}
