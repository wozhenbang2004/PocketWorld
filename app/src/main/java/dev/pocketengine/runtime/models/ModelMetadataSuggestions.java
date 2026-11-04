package dev.pocketengine.runtime.models;

import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Optional third-party reference cards. No ModelFacts, price quote, authority or request setting is changed. */
public final class ModelMetadataSuggestions {
    public static final String SOURCE="https://models.dev/api.json";
    public static final int MAX_DOCUMENT_BYTES=16*1024*1024;
    public static final long MAX_AGE_MILLIS=7L*24*60*60*1000;
    private static final int MAX_CACHE_BYTES=2*1024*1024,MAX_ENTRIES=128;
    private final Path file;
    private record Key(ModelBinding binding,String model) { Key {java.util.Objects.requireNonNull(binding);model=ModelBinding.modelId(model);} }
    private Map<Key,JSONObject> entries=new LinkedHashMap<>();
    private boolean readable=true;
    public ModelMetadataSuggestions(Path file){this.file=file.toAbsolutePath();try{load();}catch(Exception invalid){readable=false;}}
    public synchronized JSONObject snapshot(ModelBinding binding,String model,long now)throws Exception{
        JSONObject saved=entries.get(key(binding,model));JSONObject out=saved==null?new JSONObject():new JSONObject(saved.toString());
        long at=out.optLong("observedAtMillis");return out.put("available",saved!=null).put("cacheReadable",readable)
                .put("fresh",saved!=null&&now>=at&&now-at<=MAX_AGE_MILLIS).put("source",SOURCE).put("adviceOnly",true).put("capabilityMetadataChanged",false);
    }
    public synchronized void save(ModelBinding binding,String model,JSONObject suggestion)throws Exception{
        requireReadable();validate(suggestion);if(!binding.equals(ModelBinding.fromJson(suggestion.getJSONObject("binding")))||!model.equals(suggestion.getString("modelId")))throw new SecurityException("metadata_original_binding_changed");
        Map<Key,JSONObject> next=new LinkedHashMap<>(entries);next.keySet().removeIf(old->old.binding().connectionId().equals(binding.connectionId())&&old.model().equals(model));next.put(key(binding,model),new JSONObject(suggestion.toString()));
        if(next.size()>MAX_ENTRIES)throw new IllegalStateException("最多保留128项第三方参考，请先清除不用的参考。");persist(next);entries=next;
    }
    public synchronized void clear(ModelBinding binding,String model)throws Exception{
        requireReadable();Map<Key,JSONObject> next=new LinkedHashMap<>(entries);if(next.keySet().removeIf(old->old.binding().connectionId().equals(binding.connectionId())&&old.model().equals(model))){persist(next);entries=next;}
    }
    /** V1 published API keys are looked up exactly; no alias/name guessing or provider-agnostic fallback. */
    public static JSONObject parse(ModelBinding binding,String model,String provider,String body,long observedAt)throws Exception{
        ModelBinding.modelId(model);if(provider==null||!provider.matches("[a-z0-9][a-z0-9_-]{0,99}"))throw new IllegalArgumentException("请填写准确的 models.dev Provider ID。");
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_DOCUMENT_BYTES||observedAt<1)throw new IllegalArgumentException("metadata_document_limit");
        JSONTokener parser=new JSONTokener(body);Object raw=parser.nextValue();if(!(raw instanceof JSONObject root)||parser.nextClean()!=0)throw new IllegalArgumentException("metadata_document_invalid");
        JSONObject origin=root.optJSONObject(provider),models=origin==null?null:origin.optJSONObject("models"),row=models==null?null:models.optJSONObject(model);
        if(row==null||row.has("id")&&!model.equals(row.optString("id")))throw new IllegalArgumentException("该来源没有此 Provider ID 与模型 ID 的精确组合；没有采用同名或近似模型。");
        JSONObject hints=new JSONObject();JSONObject limit=row.optJSONObject("limit"),cost=row.optJSONObject("cost");
        for(String field:java.util.List.of("context","input","output")){Object value=limit==null?null:limit.opt(field);if(value instanceof Integer||value instanceof Long){long number=((Number)value).longValue();if(number>0&&number<=100_000_000L)hints.put("limit."+field,number);}}
        for(String field:java.util.List.of("tool_call","reasoning","structured_output")){Object value=row.opt(field);if(value instanceof Boolean)hints.put(field,value);}
        JSONObject modalities=row.optJSONObject("modalities");Object input=modalities==null?null:modalities.opt("input");
        if(input instanceof JSONArray types&&types.length()<=16){boolean valid=true,image=false;for(int i=0;i<types.length();i++){Object value=types.opt(i);if(!(value instanceof String type)||!type.matches("[a-z][a-z0-9_+./-]{0,63}")){valid=false;break;}image|=type.equals("image")||type.startsWith("image/");}if(valid)hints.put("image_input",image);}
        for(String field:java.util.List.of("input","output","reasoning","cache_read","cache_write")){Object value=cost==null?null:cost.opt(field);if(value instanceof Number)try{java.math.BigDecimal number=new java.math.BigDecimal(value.toString());if(number.signum()>=0&&number.compareTo(new java.math.BigDecimal("1000000"))<=0&&number.precision()<=24)hints.put("cost."+field,number.stripTrailingZeros().toPlainString());}catch(NumberFormatException ignored){/* Unknown stays absent. */}}
        String api="";Object declared=origin.opt("api");if(declared instanceof String value)try{URI uri=URI.create(value);if(value.length()<=2048&&"https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getRawQuery()==null&&uri.getFragment()==null)api=value;}catch(IllegalArgumentException ignored){/* Never follow a metadata URL. */}
        String match="provider_origin_unverified";
        if(!api.isEmpty()){
            URI declaredUri=URI.create(api),local=binding.modelsEndpoint();
            if(local.getScheme()!=null&&local.getHost()!=null)match=local.getScheme().equals(declaredUri.getScheme())&&local.getHost().equalsIgnoreCase(declaredUri.getHost())&&effectivePort(local)==effectivePort(declaredUri)?"same_origin_only_not_model_verification":"different_origin";
        }
        return new JSONObject().put("format","pocketworld.model-metadata-suggestion.v1").put("binding",binding.toJson()).put("modelId",model)
                .put("providerId",provider).put("source",SOURCE).put("sourceVersion","sha256:"+hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("observedAtMillis",observedAt).put("providerApi",api).put("originMatch",match).put("hints",hints).put("currency","USD")
                .put("sourcePriority","owner_override_then_original_provider;third_party_reference_only").put("adviceOnly",true).put("capabilityMetadataChanged",false);
    }
    private static int effectivePort(URI uri){return uri.getPort()<0?443:uri.getPort();}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static Key key(ModelBinding binding,String model){return new Key(binding,model);}
    private void requireReadable(){if(!readable)throw new IllegalStateException("第三方参考缓存暂不可读取；原文件已保留，不影响模型选择或机主设置。");}
    private static void validate(JSONObject row)throws Exception{
        if(!"pocketworld.model-metadata-suggestion.v1".equals(row.getString("format"))||!SOURCE.equals(row.getString("source"))||!row.getString("sourceVersion").matches("sha256:[a-f0-9]{64}")||row.getLong("observedAtMillis")<1||!row.getString("providerId").matches("[a-z0-9][a-z0-9_-]{0,99}")||!row.getBoolean("adviceOnly")||row.getBoolean("capabilityMetadataChanged"))throw new IllegalArgumentException("invalid_metadata_suggestion");
        ModelBinding.fromJson(row.getJSONObject("binding"));ModelBinding.modelId(row.getString("modelId"));JSONObject hints=row.getJSONObject("hints");
        java.util.Iterator<String> fields=hints.keys();while(fields.hasNext())if(!java.util.Set.of("limit.context","limit.input","limit.output","tool_call","reasoning","structured_output","image_input","cost.input","cost.output","cost.reasoning","cost.cache_read","cost.cache_write").contains(fields.next()))throw new IllegalArgumentException("invalid_metadata_hint");
    }
    private void load()throws Exception{
        if(!Files.exists(file))return;if(Files.size(file)>MAX_CACHE_BYTES)throw new IllegalArgumentException("metadata_cache_limit");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();JSONTokener parser=new JSONTokener(text);Object raw=parser.nextValue();
        if(!(raw instanceof JSONObject root)||parser.nextClean()!=0||root.getInt("schema")!=1)throw new IllegalArgumentException("invalid_metadata_cache");JSONArray rows=root.getJSONArray("entries");if(rows.length()>MAX_ENTRIES)throw new IllegalArgumentException("metadata_cache_limit");Map<Key,JSONObject> loaded=new LinkedHashMap<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);validate(row);if(loaded.put(key(ModelBinding.fromJson(row.getJSONObject("binding")),row.getString("modelId")),row)!=null)throw new IllegalArgumentException("duplicate_metadata_cache");}entries=loaded;
    }
    private void persist(Map<Key,JSONObject> values)throws Exception{
        byte[] bytes=new JSONObject().put("schema",1).put("entries",new JSONArray(values.values())).toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_CACHE_BYTES)throw new IllegalArgumentException("metadata_cache_limit");
        Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"model-advice-",".tmp");try{Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
}
