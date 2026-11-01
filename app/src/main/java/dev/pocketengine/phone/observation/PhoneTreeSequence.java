package dev.pocketengine.phone.observation;

import dev.pocketengine.core.NativeToolPlan;
import dev.pocketengine.store.TaskStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A deterministic proposal and exact native target pins. The original Runner alone executes each step. */
public final class PhoneTreeSequence {
    public static final String TOOL="phone_prepare_sequence";
    public static final long TTL_MS=20_000;
    private PhoneTreeSequence() { }
    private static JSONObject string(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    private static JSONObject number(int min,int max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
    public static JSONObject schema()throws Exception {
        JSONObject target=new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("windowId").put("path"))
                .put("properties",new JSONObject().put("windowId",number(0,Integer.MAX_VALUE)).put("path",new JSONObject().put("type","array").put("maxItems",40).put("items",number(0,511))).put("resourceId",string(400)).put("role",string(400)).put("textExact",string(400)));
        JSONObject selector=new JSONObject().put("type","object").put("additionalProperties",false).put("properties",new JSONObject().put("windowId",number(0,Integer.MAX_VALUE)).put("text",string(400)).put("resourceId",string(400)).put("role",string(400)));
        JSONObject condition=new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("kind").put("selector").put("timeoutMs")).put("properties",new JSONObject().put("kind",new JSONObject().put("type","string").put("enum",new JSONArray().put("present").put("absent").put("stable"))).put("selector",selector).put("timeoutMs",number(100,6000)).put("stableForMs",number(250,6000)));
        JSONObject step=new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("tool")).put("properties",new JSONObject().put("tool",new JSONObject().put("type","string").put("enum",new JSONArray().put("phone_click_node").put("phone_type").put("phone_scroll_node").put("phone_condition_wait"))).put("target",target).put("text",string(12000)).put("direction",new JSONObject().put("type","string").put("enum",new JSONArray().put("forward").put("backward"))).put("condition",condition));
        return new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("observationId").put("steps")).put("properties",new JSONObject().put("observationId",string(100)).put("steps",new JSONObject().put("type","array").put("maxItems",4).put("items",step)));
    }
    public static void validate(JSONObject args)throws Exception {
        PhoneTreeExtract.keys(args,"observationId","steps");PhoneTreeExtract.text(args,"observationId",100);JSONArray steps=args.getJSONArray("steps");if(steps.length()<1||steps.length()>4||args.toString().length()>16000)throw new IllegalArgumentException("短序列需要1–4步且参数总量不超过16KiB");
        for(int i=0;i<steps.length();i++){JSONObject s=steps.getJSONObject(i);String tool=PhoneTreeExtract.text(s,"tool",40);if(!Set.of("phone_click_node","phone_type","phone_scroll_node","phone_condition_wait").contains(tool))throw new IllegalArgumentException("短序列不接受坐标、启动、主页、导航或其他工具");
            if(tool.equals("phone_condition_wait")){PhoneTreeExtract.keys(s,"tool","condition");JSONObject c=s.getJSONObject("condition");PhoneTreeQuery.validate(PhoneTreeQuery.WAIT,c);if(c.has("observationId")||c.getJSONObject("selector").has("parentId"))throw new IllegalArgumentException("序列等待只接受新鲜窗口/语义predicate，不接旧观察父节点");continue;}
            PhoneTreeExtract.keys(s,tool.equals("phone_type")?new String[]{"tool","target","text"}:tool.equals("phone_scroll_node")?new String[]{"tool","target","direction"}:new String[]{"tool","target"});
            JSONObject t=s.getJSONObject("target");PhoneTreeExtract.keys(t,"windowId","path","resourceId","role","textExact");PhoneTreeExtract.integer(t,"windowId",-1,0,Integer.MAX_VALUE);if(!t.has("windowId"))throw new IllegalArgumentException("短序列目标必须固定真实windowId");JSONArray path=t.getJSONArray("path");if(path.length()>40)throw new IllegalArgumentException("节点路径过深");for(int n=0;n<path.length();n++){Object p=path.get(n);if(!(p instanceof Number)||((Number)p).doubleValue()!=path.getInt(n)||path.getInt(n)<0||path.getInt(n)>511)throw new IllegalArgumentException("节点路径必须是原树的精确整数路径");}
            for(String key:new String[]{"resourceId","role","textExact"})if(t.has(key)&&(!(t.get(key) instanceof String)||t.getString(key).length()>400))throw new IllegalArgumentException("目标身份条件无效");
            if(tool.equals("phone_type")&&(!(s.opt("text") instanceof String)||s.getString("text").length()>12000))throw new IllegalArgumentException("输入值必须为原单步允许的文字，空串可清空");
            if(tool.equals("phone_scroll_node")&&!Set.of("forward","backward").contains(s.getString("direction")))throw new IllegalArgumentException("滚动方向无效");
        }
    }
    public static final class State {
        public final String initialObservation,scopeFingerprint,packageName;public final long created,pause;private final JSONArray steps;private final List<String> identities;private boolean started;
        private State(JSONObject args,JSONObject actual,long now)throws Exception {validate(args);initialObservation=actual.getString("observationId");if(!initialObservation.equals(args.getString("observationId"))||!PhoneTreeExtract.complete(actual))throw new SecurityException("短序列起点不是完整的当前原树");packageName=actual.optString("packageName");if(packageName.isEmpty())throw new SecurityException("原应用身份不可核对");scopeFingerprint=scope(actual);pause=actual.getLong("pauseRevision");created=now;steps=new JSONArray(args.getJSONArray("steps").toString());identities=new ArrayList<>();
            for(int i=0;i<steps.length();i++){JSONObject s=steps.getJSONObject(i);if(s.getString("tool").equals(PhoneTreeQuery.WAIT)){identities.add("");continue;}JSONObject target=select(actual,s);String id=target.optString("identityFingerprint");if(!id.matches("[0-9]{1,8}:[a-f0-9]{64}"))throw new SecurityException("短序列需要原生完整身份指纹，不使用展示摘要作目标");identities.add(id);}
        }
        public int size(){return steps.length();}
        public synchronized boolean started(){return started;}
        public synchronized void start(String actualLatest)throws Exception {if(started||!initialObservation.equals(actualLatest))throw new SecurityException("准备短序列后原起点已变化，未重绑到新页面");started=true;}
        public void requireScope(JSONObject actual,long now)throws Exception {if(now<created||now-created>TTL_MS||pause!=actual.optLong("pauseRevision",-1)||!scopeFingerprint.equals(scope(actual))||!packageName.equals(actual.optString("packageName")))throw new SecurityException("短序列的应用、显示、会话、暂停或时限已变化");}
        public JSONObject resolve(int index,JSONObject actual,long now)throws Exception {requireScope(actual,now);if(index<0||index>=steps.length()||!PhoneTreeExtract.complete(actual))throw new SecurityException("短序列当前树不可完整核对");JSONObject step=steps.getJSONObject(index),args=new JSONObject().put("observationId",actual.getString("observationId"));String tool=step.getString("tool");
            if(tool.equals(PhoneTreeQuery.WAIT)){JSONObject condition=step.getJSONObject("condition");long remaining=TTL_MS-(now-created);if(remaining<100||condition.has("stableForMs")&&condition.getInt("stableForMs")>remaining)throw new SecurityException("短序列剩余时限不足以核对本步条件，未开始新的等待");for(var keys=condition.keys();keys.hasNext();){String k=keys.next();args.put(k,condition.get(k));}args.put("timeoutMs",(int)Math.min(condition.getInt("timeoutMs"),remaining));PhoneTreeQuery.validate(PhoneTreeQuery.WAIT,args);return args;}
            JSONObject target=select(actual,step);if(!identities.get(index).equals(target.getString("identityFingerprint")))throw new SecurityException("下一步的真实目标身份已变化，余下步骤未派发");args.put("nodeId",target.getString("id"));if(tool.equals("phone_type"))args.put("text",step.getString("text"));if(tool.equals(NativeTreeScrollPort.TOOL))args.put("direction",step.getString("direction"));return args;
        }
        public NativeToolPlan.Tool tool(int index)throws Exception {return switch(steps.getJSONObject(index).getString("tool")){case "phone_click_node"->NativeToolPlan.Tool.CLICK_NODE;case "phone_type"->NativeToolPlan.Tool.TYPE;case "phone_scroll_node"->NativeToolPlan.Tool.SCROLL_NODE;default->NativeToolPlan.Tool.CONDITION_WAIT;};}
    }
    public static State prepare(JSONObject args,JSONObject actual,long now)throws Exception{return new State(args,actual,now);}
    static String scope(JSONObject o)throws Exception {JSONObject b=o.getJSONObject("observationBinding"),scope=new JSONObject();for(String k:new String[]{"taskId","runId","epoch","displayId","sessionId"})scope.put(k,b.get(k));return TaskStore.capabilityFingerprint(scope);}
    private static JSONObject select(JSONObject observation,JSONObject step)throws Exception {
        JSONObject declared=step.getJSONObject("target"),found=null;for(JSONObject node:PhoneTreeExtract.index(observation).values())if(node.getInt("windowId")==declared.getInt("windowId")&&node.getJSONArray("path").toString().equals(declared.getJSONArray("path").toString())){if(found!=null)throw new SecurityException("窗口/路径目标歧义");found=node;}
        if(found==null||found.optBoolean("password")||!found.optBoolean("visible",true)||!found.optBoolean("enabled",true))throw new SecurityException("短序列目标未展示、不可见、被禁用或需私密输入");for(String key:new String[]{"resourceId","role","textExact"})if(declared.has(key)&&!declared.getString(key).equals(found.optString(key.equals("textExact")?"text":key)))throw new SecurityException("短序列声明与当前真实目标不同");
        String tool=step.getString("tool");if(tool.equals("phone_click_node")&&!found.optBoolean("clickable")||tool.equals("phone_type")&&!found.optBoolean("editable")||tool.equals(NativeTreeScrollPort.TOOL)&&!found.optBoolean("scrollable"))throw new SecurityException("目标不支持所声明的原单步动作");return found;
    }
}
