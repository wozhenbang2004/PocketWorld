package dev.pocketengine.runtime.extensions;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.PluginRules;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Executable extensions for ONE existing native task/run. This owns only registrations and
 * transient extension state, never tasks, effects, grants, retry loops or a second scheduler.
 */
public final class ExtensionRuntime implements AutoCloseable {
    public static final int MAX_EXTENSIONS=16;
    @FunctionalInterface public interface CurrentCheck {void check()throws Exception;}
    public interface Evaluator extends AutoCloseable {
        /** Must enforce the deadline and terminate owned evaluation on cancellation/close. */
        String evaluate(byte[] script,JSONObject request,int maxMillis,RunControl control,CurrentCheck current)throws Exception;
        /** Close only this evaluator's isolate; never close the Application's shared sandbox. */
        @Override void close();
    }
    @FunctionalInterface public interface Authority {
        /** Native live catalogue approval/source identity AND authoritative TaskStore run check. */
        void check(ExtensionProgram program,Binding binding,RunControl control)throws Exception;
    }
    public static final class Binding {
        public final String taskId,runId;
        public final int epoch;
        public Binding(String taskId,String runId,int epoch){
            if(taskId==null||!taskId.matches("[a-f0-9-]{36}")||runId==null||!runId.matches("[a-f0-9-]{36}")||epoch<1)throw new IllegalArgumentException("扩展缺少原生任务身份");
            this.taskId=taskId;this.runId=runId;this.epoch=epoch;
        }
    }
    public static final class Decision {
        public final boolean allowed;
        public final String extensionId,reason;
        private Decision(boolean allowed,String extensionId,String reason){this.allowed=allowed;this.extensionId=extensionId;this.reason=reason;}
    }
    private final Evaluator evaluator;
    private final Binding binding;
    private final Authority authority;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final Map<String,Tool> tools=new LinkedHashMap<>();
    private final AtomicReference<Invocation> active=new AtomicReference<>();
    private final AtomicBoolean callback=new AtomicBoolean();
    private volatile boolean closed;
    private volatile boolean cleanupComplete;

    public ExtensionRuntime(Evaluator evaluator,Binding binding,Authority authority){
        this.evaluator=java.util.Objects.requireNonNull(evaluator);this.binding=java.util.Objects.requireNonNull(binding);this.authority=java.util.Objects.requireNonNull(authority);
    }

    /** The caller first obtains this exact approved Program from its native catalogue. */
    public JSONObject mount(ExtensionProgram program,RunControl control)throws Exception {
        return serial(()->mountCurrent(program,control));
    }
    private JSONObject mountCurrent(ExtensionProgram program,RunControl control)throws Exception {
        java.util.Objects.requireNonNull(program);synchronized(this){ensureOpen();if(entries.containsKey(program.id))throw new IllegalStateException("该扩展已挂载，请先卸载原安装");if(entries.size()>=MAX_EXTENSIONS)throw new IllegalStateException("本次任务挂载的扩展过多");}
        Entry entry=new Entry(program,ExtensionScript.compile(program));
        Reply reply=invoke(entry,"describe",new JSONObject(),null,control);JSONObject descriptor=normalizeDescriptor(reply.descriptor);
        entry.descriptor=descriptor;entry.descriptorFingerprint=canonical(descriptor);
        JSONObject hooks=descriptor.getJSONObject("hooks");entry.context=hooks.getBoolean("context");entry.beforeTool=hooks.getBoolean("beforeTool");entry.onEvent=hooks.getBoolean("onEvent");
        List<Tool> added=new ArrayList<>();JSONArray definitions=descriptor.getJSONArray("tools");
        String prefix="ext_"+ExtensionProgram.sha256((program.id+"\n"+program.installationId+"\n"+program.approvalRevision+"\n"+program.sourceSha256).getBytes(StandardCharsets.UTF_8)).substring(0,12)+"_";
        for(int i=0;i<definitions.length();i++){
            JSONObject definition=definitions.getJSONObject(i);String localName=definition.getString("name"),name=prefix+localName;
            JSONObject wire=new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",definition.getString("description")+"\n稳定能力："+semanticCapability(program.id,localName)).put("parameters",copy(definition.getJSONObject("parameters"),16384)));
            added.add(new Tool(entry,localName,name,definition.getJSONObject("parameters"),wire));
        }
        requireCurrent(entry,control,reply.pauseRevision);
        synchronized(this){
            ensureOpen();if(entry.retired)throw new SecurityException("扩展已卸载");
            if(entries.containsKey(program.id)||entries.size()>=MAX_EXTENSIONS)throw new IllegalStateException("扩展挂载清单已变化");
            for(Tool tool:added)if(tools.containsKey(tool.name))throw new IllegalStateException("扩展工具名称冲突");
            entries.put(program.id,entry);for(Tool tool:added)tools.put(tool.name,tool);
        }
        return review(entry);
    }

    public JSONArray tools(RunControl control)throws Exception {
        JSONArray result=new JSONArray();for(Entry entry:entrySnapshot())requireCurrent(entry,control,control.pauseRevision());
        synchronized(this){ensureOpen();for(Tool tool:tools.values())if(!tool.entry.retired)result.put(copy(tool.wire,32768));}return result;
    }
    public synchronized boolean supports(String name){return !closed&&tools.containsKey(name)&&!tools.get(name).entry.retired;}
    /** Stable package/tool semantics; callable names remain pinned to this exact installation. */
    public static String semanticCapability(String extensionId,String localTool)throws Exception{
        if(extensionId==null||!extensionId.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")||localTool==null||!localTool.matches("[A-Za-z][A-Za-z0-9_]{0,31}"))throw new IllegalArgumentException("扩展能力身份无效");
        String readable="js:"+extensionId+":"+localTool;
        return readable.matches("[a-z][a-z0-9_.:-]{0,79}")?readable:"js:"+ExtensionProgram.sha256((extensionId+"\n"+localTool).getBytes(StandardCharsets.UTF_8));
    }
    public Map<String,String> semanticCapabilities(RunControl control)throws Exception{
        for(Entry entry:entrySnapshot())requireCurrent(entry,control,control.pauseRevision());Map<String,String> result=new LinkedHashMap<>();
        synchronized(this){ensureOpen();for(Tool tool:tools.values())if(!tool.entry.retired){String alias=semanticCapability(tool.entry.program.id,tool.localName);if(result.put(alias,tool.name)!=null)throw new SecurityException("稳定扩展能力身份冲突");}}return Map.copyOf(result);
    }
    public void validate(String name,JSONObject arguments)throws Exception {Tool tool=tool(name);PluginRules.validateArguments(copy(arguments,32768),tool.schema);}

    /** Pure JS output is untrusted data, never a receipt proving an external effect. */
    public JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception {
        return serial(()->executeCurrent(name,arguments,control));
    }
    private JSONObject executeCurrent(String name,JSONObject arguments,RunControl control)throws Exception {
        Tool tool=tool(name);JSONObject input=copy(arguments,32768);PluginRules.validateArguments(input,tool.schema);
        Reply reply=invoke(tool.entry,"tool",input,tool.localName,control);only(reply.result,"value","state");
        if(!reply.result.has("value"))throw invalid("自定义工具没有返回value");
        Object value=bounded(reply.result.get("value"),16384);commitState(tool.entry,reply,control);
        return new JSONObject().put("ok",true).put("output",value).put("extension",tool.entry.program.identity())
                .put("untrustedOutput",true).put("businessVerified",false).put("hostEffects",false);
    }

    /** Appends reference text only; it cannot rewrite system/user messages or canonical tool facts. */
    public JSONObject context(JSONObject snapshot,RunControl control)throws Exception {
        return serial(()->contextCurrent(snapshot,control));
    }
    private JSONObject contextCurrent(JSONObject snapshot,RunControl control)throws Exception {
        long pause=control.pauseRevision();
        JSONObject input=copy(snapshot,32768);StringBuilder text=new StringBuilder();JSONArray sources=new JSONArray();
        for(Entry entry:entrySnapshot()){
            if(!entry.context)continue;Reply reply=invoke(entry,"context",input,null,control);only(reply.result,"text","state");
            if(!(reply.result.opt("text") instanceof String))throw invalid("扩展context需返回text");String addition=reply.result.getString("text");
            if(addition.length()>4000||text.length()+addition.length()>12000)throw invalid("扩展参考上下文超出本轮上限");
            requireCurrent(entry,control,pause);commitState(entry,reply,control);if(!addition.isEmpty()){if(text.length()>0)text.append('\n');text.append(addition);sources.put(entry.program.identity());}
        }
        return new JSONObject().put("text",text.toString()).put("sources",sources).put("untrustedReference",true).put("executionAuthorized",false);
    }

    /** Denial-only. The original tool arguments and the native approval remain untouched. */
    public Decision beforeTool(String name,JSONObject arguments,RunControl control)throws Exception {
        return serial(()->beforeToolCurrent(name,arguments,control));
    }
    private Decision beforeToolCurrent(String name,JSONObject arguments,RunControl control)throws Exception {
        long pause=control.pauseRevision();
        if(name==null||name.isEmpty()||name.length()>100)throw new IllegalArgumentException("工具名称无效");
        JSONObject input=new JSONObject().put("name",name).put("arguments",copy(arguments,32768));
        for(Entry entry:entrySnapshot()){
            if(!entry.beforeTool)continue;Reply reply=invoke(entry,"beforeTool",input,null,control);only(reply.result,"block","reason","state");
            if(reply.result.has("block")&&!(reply.result.get("block") instanceof Boolean))throw invalid("扩展block需为布尔值");
            boolean block=reply.result.optBoolean("block",false);String reason="";
            if(reply.result.has("reason")){if(!(reply.result.get("reason") instanceof String))throw invalid("扩展拒绝原因无效");reason=reply.result.getString("reason");}
            if(reason.length()>500||block&&reason.isBlank())throw invalid("扩展拒绝需给出简短原因");
            requireCurrent(entry,control,pause);commitState(entry,reply,control);if(block)return new Decision(false,entry.program.id,reason);
        }
        return new Decision(true,"","");
    }

    /** These events may update only this run's transient extension state. No return-value action. */
    public void event(String kind,JSONObject payload,RunControl control)throws Exception {
        serial(()->{eventCurrent(kind,payload,control);return null;});
    }
    private void eventCurrent(String kind,JSONObject payload,RunControl control)throws Exception {
        long pause=control.pauseRevision();
        if(!List.of("run_start","turn_start","tool_result","run_end").contains(kind))throw new IllegalArgumentException("不支持的扩展事件");
        JSONObject input=new JSONObject().put("type",kind).put("payload",copy(payload,32768));
        for(Entry entry:entrySnapshot()){if(!entry.onEvent)continue;Reply reply=invoke(entry,"event",input,null,control);only(reply.result,"state");requireCurrent(entry,control,pause);commitState(entry,reply,control);}
    }

    /** Registry mutation is immediate; active old-source evaluation is actually cancelled. */
    public void unmount(String installationId){
        List<Entry> removed=new ArrayList<>();synchronized(this){Iterator<Entry> iterator=entries.values().iterator();while(iterator.hasNext()){Entry entry=iterator.next();if(entry.program.installationId.equals(installationId)){entry.retired=true;removed.add(entry);iterator.remove();}}tools.values().removeIf(tool->tool.entry.retired);}
        Invocation running=active.get();if(running!=null&&removed.contains(running.entry))running.control.cancel();
    }
    public synchronized JSONArray mounted()throws Exception {JSONArray result=new JSONArray();for(Entry entry:entries.values())result.put(review(entry));return result;}

    private Reply invoke(Entry entry,String method,JSONObject input,String tool,RunControl parent)throws Exception {
        long pause=parent.pauseRevision();requireCurrent(entry,parent,pause);
        RunControl operation=new RunControl();operation.bindHostRun(binding.taskId,binding.runId,binding.epoch);operation.setAuthorityCheck(()->requireCurrent(entry,parent,pause));
        Invocation invocation=new Invocation(entry,operation);if(!active.compareAndSet(null,invocation))throw new IllegalStateException("原扩展回调仍在执行；不会并发重入");
        Runnable cancel=operation::cancel;parent.onCancel(cancel);
        try{
            JSONObject request=new JSONObject().put("method",method).put("input",copy(input,32768)).put("state",copyState(entry.state));if(tool!=null)request.put("tool",tool);
            String raw=evaluator.evaluate(entry.script,request,entry.program.maxMillis,operation,()->requireCurrent(entry,parent,pause));
            requireCurrent(entry,parent,pause);if(raw==null||raw.length()>65536)throw invalid("扩展返回超限");
            JSONTokener parser=new JSONTokener(raw);Object parsed=parser.nextValue();if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw invalid("扩展返回无效的JSON信封");
            JSONObject envelope=copy((JSONObject)parsed,65536);only(envelope,"descriptor","result");JSONObject descriptor=normalizeDescriptor(envelope.getJSONObject("descriptor"));
            if(entry.descriptorFingerprint!=null&&!entry.descriptorFingerprint.equals(canonical(descriptor)))throw invalid("扩展工具或钩子声明在运行期间改变，请重新挂载");
            return new Reply(descriptor,envelope.getJSONObject("result"),pause);
        }finally{parent.removeCancelAction(cancel);active.compareAndSet(invocation,null);}
    }
    private void requireCurrent(Entry entry,RunControl control,long pause)throws Exception {
        control.check();if(closed||entry.retired)throw new SecurityException("扩展已卸载或本轮已结束");
        if(!control.hasHostRun()||!control.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("扩展不能跨任务或执行代次使用");
        if(control.isPaused()||control.pauseRevision()!=pause)throw new SecurityException("扩展回调所依据的现场已过期");authority.check(entry.program,binding,control);control.check();
    }
    private void commitState(Entry entry,Reply reply,RunControl control)throws Exception {
        JSONObject result=reply.result,state=result.has("state")?copyState(result.getJSONObject("state")):null;
        requireCurrent(entry,control,reply.pauseRevision);
        synchronized(this){ensureOpen();if(entry.retired)throw new SecurityException("扩展已卸载");if(state!=null)entry.state=state;}
    }
    @FunctionalInterface private interface Callback<T>{T run()throws Exception;}
    private <T>T serial(Callback<T> work)throws Exception {
        if(!callback.compareAndSet(false,true))throw new IllegalStateException("原扩展回调尚未提交；不会并发重入");
        try{ensureOpen();return work.run();}finally{callback.set(false);}
    }
    private synchronized Tool tool(String name){ensureOpen();Tool tool=tools.get(name);if(tool==null||tool.entry.retired)throw new IllegalArgumentException("本轮没有该扩展工具");return tool;}
    private synchronized List<Entry> entrySnapshot(){ensureOpen();return new ArrayList<>(entries.values());}
    private void ensureOpen(){if(closed)throw new IllegalStateException("本轮扩展已关闭");}
    private static JSONObject review(Entry entry)throws Exception {return entry.program.identity().put("descriptor",copy(entry.descriptor,32768)).put("statePersistence","run_local_only");}

    @Override public void close(){
        synchronized(this){if(cleanupComplete)return;closed=true;for(Entry entry:entries.values())entry.retired=true;entries.clear();tools.clear();}
        Invocation running=active.get();if(running!=null)running.control.cancel();evaluator.close();cleanupComplete=true;
    }

    private static JSONObject normalizeDescriptor(JSONObject source)throws Exception {
        only(source,"tools","hooks");JSONArray rows=source.getJSONArray("tools");if(rows.length()>12)throw invalid("扩展工具过多");
        JSONObject hooks=source.getJSONObject("hooks");only(hooks,"context","beforeTool","onEvent");JSONObject normalizedHooks=new JSONObject();
        for(String key:List.of("context","beforeTool","onEvent")){if(!(hooks.opt(key) instanceof Boolean))throw invalid("扩展hook声明无效");normalizedHooks.put(key,hooks.getBoolean(key));}
        TreeSet<String> names=new TreeSet<>();JSONArray tools=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);only(row,"name","description","parameters");if(!(row.opt("name") instanceof String)||!(row.opt("description") instanceof String))throw invalid("扩展工具名称及描述需为文字");String name=row.getString("name"),description=row.getString("description");
            if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,31}")||!names.add(name)||description.isBlank()||description.length()>1000)throw invalid("扩展工具名称或描述无效");
            JSONObject schema=copy(row.getJSONObject("parameters"),16384);if(!"object".equals(schema.optString("type")))throw invalid("扩展工具参数需为对象");PluginRules.diagnoseArguments(new JSONObject(),schema);
            tools.put(new JSONObject().put("name",name).put("description",description).put("parameters",schema));
        }
        JSONObject result=new JSONObject().put("tools",tools).put("hooks",normalizedHooks);if(result.toString().length()>32768)throw invalid("扩展声明过大");return result;
    }
    private static void only(JSONObject object,String... accepted){java.util.Set<String> keys=java.util.Set.of(accepted);Iterator<String> iterator=object.keys();while(iterator.hasNext())if(!keys.contains(iterator.next()))throw invalid("扩展返回了不支持的字段");}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
    private static JSONObject copy(JSONObject value,int max)throws Exception {if(value==null)throw invalid("缺少扩展JSON数据");return (JSONObject)bounded(value,max);}
    private static JSONObject copyState(JSONObject value)throws Exception {
        JSONObject state=copy(value,8192);
        if(canonical(state).getBytes(StandardCharsets.UTF_8).length>8192)throw invalid("扩展JSON数据超限");
        return state;
    }
    private static Object bounded(Object value,int max)throws Exception {Object result=copyValue(value,0,new int[]{0,0});String encoded=canonical(result);if(encoded.length()>max)throw invalid("扩展JSON数据超限");return result;}
    private static Object copyValue(Object value,int depth,int[] budget)throws Exception {
        if(depth>16||++budget[0]>8192)throw invalid("扩展JSON结构超限");
        if(value instanceof JSONObject){JSONObject out=new JSONObject(),source=(JSONObject)value;if(source.length()>128)throw invalid("扩展JSON字段过多");Iterator<String> names=source.keys();while(names.hasNext()){String key=names.next();count(key,budget);out.put(key,copyValue(source.get(key),depth+1,budget));}return out;}
        if(value instanceof JSONArray){JSONArray out=new JSONArray(),source=(JSONArray)value;if(source.length()>1024)throw invalid("扩展JSON列表过长");for(int i=0;i<source.length();i++)out.put(copyValue(source.get(i),depth+1,budget));return out;}
        if(value instanceof String){count((String)value,budget);return value;}
        if(value instanceof Number){double number=((Number)value).doubleValue();if(!Double.isFinite(number))throw invalid("扩展JSON数字无效");return value;}
        if(value==null||value==JSONObject.NULL)return JSONObject.NULL;if(value instanceof Boolean)return value;throw invalid("扩展仅支持JSON数据");
    }
    private static void count(String text,int[] budget){
        if(text.length()>65536-budget[1])throw invalid("扩展JSON文字超限");budget[1]+=text.length();
        for(int i=0;i<text.length();i++){char value=text.charAt(i);if(Character.isSurrogate(value)&&(!Character.isHighSurrogate(value)||i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i))))throw invalid("扩展JSON Unicode无效");}
    }
    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();object.keys().forEachRemaining(keys::add);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray values=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<values.length();i++){if(i>0)out.append(',');out.append(canonical(values.get(i)));}return out.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";if(value instanceof String)return JSONObject.quote((String)value);if(value instanceof Number||value instanceof Boolean)return value.toString();throw invalid("扩展JSON值无效");
    }
    private static final class Entry {
        final ExtensionProgram program;final byte[] script;volatile boolean retired;JSONObject state=new JSONObject(),descriptor;String descriptorFingerprint;boolean context,beforeTool,onEvent;
        Entry(ExtensionProgram program,byte[] script){this.program=program;this.script=script;}
    }
    private static final class Tool {
        final Entry entry;final String localName,name;final JSONObject schema,wire;
        Tool(Entry entry,String localName,String name,JSONObject schema,JSONObject wire){this.entry=entry;this.localName=localName;this.name=name;this.schema=schema;this.wire=wire;}
    }
    private static final class Reply {final JSONObject descriptor,result;final long pauseRevision;Reply(JSONObject descriptor,JSONObject result,long pauseRevision){this.descriptor=descriptor;this.result=result;this.pauseRevision=pauseRevision;}}
    private static final class Invocation {final Entry entry;final RunControl control;Invocation(Entry entry,RunControl control){this.entry=entry;this.control=control;}}
}
