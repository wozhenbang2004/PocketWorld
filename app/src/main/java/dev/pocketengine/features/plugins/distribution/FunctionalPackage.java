package dev.pocketengine.features.plugins.distribution;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Accurate transport for an instruction Skill + existing supported components. Never runs a recipe. */
public final class FunctionalPackage {
    public static final String FORMAT="pocketworld.functional-pack.v1";
    public static final String HOST_API="pocketworld-components-v1";
    public static final int MAX_BYTES=6*1024*1024,MAX_COMPONENT_BYTES=4*1024*1024,MAX_COMPONENTS=6;
    public final String id,name,version,packageSha256;
    public final int byteSize;
    private final byte[] bytes;
    private final String metadata;
    private final List<Component> components;
    private FunctionalPackage(byte[] bytes,JSONObject data,List<Component> components)throws Exception {
        this.bytes=bytes.clone();this.components=java.util.Collections.unmodifiableList(new ArrayList<>(components));id=data.getString("id");name=data.getString("name");version=data.getString("version");byteSize=bytes.length;packageSha256=PluginPackages.hash(bytes);
        JSONObject publicData=new JSONObject(data.toString());JSONArray rows=new JSONArray();for(Component component:components)rows.put(component.view());publicData.put("components",rows);metadata=publicData.put("packageSha256",packageSha256).put("byteSize",byteSize).put("executed",false).put("signed",false).toString();
    }
    public static final class Component {
        public final String key,kind,sha256;
        public final int byteSize;
        private final byte[] bytes;
        private final String requirements,capabilities;
        private Component(JSONObject row,byte[] bytes)throws Exception {key=row.getString("key");kind=row.getString("kind");sha256=row.getString("sha256");byteSize=bytes.length;this.bytes=bytes.clone();requirements=row.getJSONArray("requires").toString();capabilities=row.getJSONArray("requiredCapabilities").toString();}
        public byte[] exactBytes(){return bytes.clone();}
        public InputStream open(){return new ByteArrayInputStream(bytes);}
        public JSONArray requires()throws Exception{return new JSONArray(requirements);}
        public JSONArray requiredCapabilities()throws Exception{return new JSONArray(capabilities);}
        public JSONObject view()throws Exception{return new JSONObject().put("key",key).put("kind",kind).put("sha256",sha256).put("byteSize",byteSize).put("requires",requires()).put("requiredCapabilities",requiredCapabilities());}
    }
    public byte[] exactBytes(){return bytes.clone();}
    public List<Component> components(){return components;}
    public JSONObject view()throws Exception{return new JSONObject(metadata);}
    public static FunctionalPackage read(InputStream input,RunControl control)throws Exception {
        if(input==null||control==null)throw new IllegalArgumentException("请选择准确功能包文件");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        while(true){control.check();int count=input.read(buffer,0,Math.min(buffer.length,MAX_BYTES+1-out.size()));if(count<0)break;if(count==0)throw new IllegalArgumentException("功能包流没有提供字节");out.write(buffer,0,count);if(out.size()>MAX_BYTES)throw new IllegalArgumentException("功能包超过6MiB");}
        control.check();byte[] bytes=out.toByteArray();String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();JSONObject data=PackageJson.parse(text);
        fields(data,"format","hostApi","id","name","version","license","source","recipe","components");if(!FORMAT.equals(data.getString("format"))||!HOST_API.equals(data.getString("hostApi")))throw new IllegalArgumentException("不支持此功能包格式或宿主API；不执行npm、shell或宿主桥");
        String id=string(data,"id",80);if(!id.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}"))throw new IllegalArgumentException("功能包ID无效");string(data,"name",120);string(data,"version",32);string(data,"license",1000);JSONObject source=data.getJSONObject("source");fields(source,"label","url");string(source,"label",240);if(source.has("url"))string(source,"url",2000);
        JSONObject recipe=data.getJSONObject("recipe");fields(recipe,"kind","entry");if(!"instruction".equals(recipe.getString("kind")))throw new IllegalArgumentException("本版只支持由原Runner解释的instruction recipe，不支持自动执行配方");String entry=string(recipe,"entry",80);
        JSONArray rows=data.getJSONArray("components");if(rows.length()<1||rows.length()>MAX_COMPONENTS)throw new IllegalArgumentException("功能包需含1至6个准确组件");List<Component> result=new ArrayList<>();Set<String> keys=new HashSet<>();int total=0;
        for(int i=0;i<rows.length();i++){control.check();JSONObject row=rows.getJSONObject(i);fields(row,"key","kind","sha256","byteSize","bytesBase64","requires","requiredCapabilities");String key=string(row,"key",80),kind=string(row,"kind",32);
            if(!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")||!keys.add(key)||!Set.of("skill_zip","declaration","executable_js").contains(kind))throw new IllegalArgumentException("组件标识重复或格式不受支持");String digest=string(row,"sha256",64);if(!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("组件摘要无效");
            int size=integer(row,"byteSize",1,kind.equals("skill_zip")?1_100_000:kind.equals("declaration")?PluginPackages.MAX_BYTES:1024*1024);byte[] raw;
            try{raw=Base64.getDecoder().decode(row.getString("bytesBase64"));}catch(IllegalArgumentException malformed){throw new IllegalArgumentException("组件必须为准确标准Base64字节",malformed);}
            if(raw.length!=size||!Base64.getEncoder().encodeToString(raw).equals(row.getString("bytesBase64"))||!digest.equals(PluginPackages.hash(raw)))throw new SecurityException("组件实际字节、大小或摘要不符");total=Math.addExact(total,raw.length);if(total>MAX_COMPONENT_BYTES)throw new IllegalArgumentException("组件合计超过4MiB");
            JSONArray dependencies=row.getJSONArray("requires"),capabilities=row.getJSONArray("requiredCapabilities");if(dependencies.length()>MAX_COMPONENTS||capabilities.length()>32||!kind.equals("skill_zip")&&capabilities.length()!=0)throw new IllegalArgumentException("组件依赖或能力引用无效");Set<String> unique=new HashSet<>();for(int j=0;j<dependencies.length();j++){String dependency=dependencies.getString(j);if(!dependency.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")||dependency.equals(key)||!unique.add(dependency))throw new IllegalArgumentException("组件依赖重复或无效");}
            unique.clear();for(int j=0;j<capabilities.length();j++){String capability=capabilities.getString(j);if(!capability.matches("[a-z][a-z0-9_.:-]{0,79}")||!unique.add(capability))throw new IllegalArgumentException("技能能力引用无效；它们不授予权限");}
            result.add(new Component(row,raw));
        }
        Component entryComponent=null;for(Component component:result){if(component.key.equals(entry))entryComponent=component;JSONArray deps=component.requires();for(int i=0;i<deps.length();i++)if(!keys.contains(deps.getString(i)))throw new IllegalArgumentException("缺少所引用的组件："+deps.getString(i));}
        if(entryComponent==null||!entryComponent.kind.equals("skill_zip"))throw new IllegalArgumentException("instruction recipe入口必须是本包的准确Skill ZIP");
        List<Component> ordered=new ArrayList<>();Set<String> ready=new HashSet<>();while(ordered.size()<result.size()){boolean moved=false;for(Component component:result)if(!ready.contains(component.key)){boolean all=true;JSONArray deps=component.requires();for(int i=0;i<deps.length();i++)all&=ready.contains(deps.getString(i));if(all){ordered.add(component);ready.add(component.key);moved=true;}}if(!moved)throw new IllegalArgumentException("组件依赖循环，未安装任何内容");}
        control.check();return new FunctionalPackage(bytes,data,ordered);
    }
    /** Authoring data only. Exact component bytes are never parsed/reserialized by this transport writer. */
    public static byte[] author(JSONObject metadata,List<JSONObject> descriptors,List<byte[]> exactComponents,RunControl control)throws Exception {
        if(descriptors.size()!=exactComponents.size())throw new IllegalArgumentException("每份组件需有准确描述与原字节");JSONObject data=new JSONObject(metadata.toString());data.put("format",FORMAT).put("hostApi",HOST_API);JSONArray rows=new JSONArray();
        for(int i=0;i<descriptors.size();i++){control.check();byte[] bytes=exactComponents.get(i).clone();JSONObject row=new JSONObject(descriptors.get(i).toString());row.put("sha256",PluginPackages.hash(bytes)).put("byteSize",bytes.length).put("bytesBase64",Base64.getEncoder().encodeToString(bytes));rows.put(row);}
        data.put("components",rows);byte[] encoded=PackageJson.canonical(data).getBytes(StandardCharsets.UTF_8);return read(new ByteArrayInputStream(encoded),control).exactBytes();
    }
    private static void fields(JSONObject data,String... allowed){Set<String> names=Set.of(allowed);data.keys().forEachRemaining(key->{if(!names.contains(key))throw new IllegalArgumentException("功能包包含不支持的字段："+key);});}
    private static String string(JSONObject data,String key,int max)throws Exception {Object value=data.get(key);if(!(value instanceof String)||((String)value).codePoints().allMatch(Character::isWhitespace)||((String)value).length()>max)throw new IllegalArgumentException(key+"无效");PackageJson.unicode((String)value);return (String)value;}
    private static int integer(JSONObject data,String key,int min,int max)throws Exception {Object value=data.get(key);if(!(value instanceof Integer)&&!(value instanceof Long))throw new IllegalArgumentException(key+"必须为整数");long result=((Number)value).longValue();if(result<min||result>max)throw new IllegalArgumentException(key+"超出范围");return (int)result;}
}
