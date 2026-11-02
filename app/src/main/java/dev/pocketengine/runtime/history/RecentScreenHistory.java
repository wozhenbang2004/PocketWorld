package dev.pocketengine.runtime.history;

import dev.pocketengine.core.RunControl;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Volatile evidence from pictures already captured for this run. IDs never carry action authority. */
public final class RecentScreenHistory implements AutoCloseable {
    public static final String LIST="phone_past_screens",VIEW="phone_view_past_screen",FORGET="phone_forget_screen";
    public static final String PREFIX="历史画面（仅作对照证据，不是当前页面或动作引用）：\n";
    private static final long TTL=300_000,MAX_BYTES=12L*1024*1024;
    private static final int MAX_FRAMES=4;
    private final RunControl original;private final RunControl.AuthorityCheck current;private final LongSupplier clock;
    private final LinkedHashMap<String,Frame> frames=new LinkedHashMap<>();private long bytes,pause;private boolean closed;
    private static final class Frame {
        final String id,mime,hash,metadata;final long at;final byte[] pixels;
        Frame(String id,String mime,String hash,JSONObject metadata,long at,byte[] pixels){this.id=id;this.mime=mime;this.hash=hash;this.metadata=metadata.toString();this.at=at;this.pixels=pixels;}
        void close(){Arrays.fill(pixels,(byte)0);}
    }
    public RecentScreenHistory(RunControl original,RunControl.AuthorityCheck current){this(original,current,()->System.nanoTime()/1_000_000);}
    RecentScreenHistory(RunControl original,RunControl.AuthorityCheck current,LongSupplier clock){this.original=java.util.Objects.requireNonNull(original);this.current=java.util.Objects.requireNonNull(current);this.clock=clock;pause=original.pauseRevision();}
    private void require(RunControl live)throws Exception {if(closed||live!=original)throw new SecurityException("历史画面不属于本次原运行");try{live.check();current.check();if(pause!=live.pauseRevision()){clear();pause=live.pauseRevision();}if(live.isPaused())throw new SecurityException("暂停期间不可读取历史画面");expire();live.check();}catch(Exception withdrawn){clear();throw withdrawn;}}
    /** Called only by the native Runner with an actual current observation, never model JSON. */
    public synchronized void retain(JSONObject observed,RunControl live)throws Exception {
        require(live);if(observed==null||observed.optBoolean("historicalOnly")||observed.optBoolean("protected")||observed.optBoolean("secure")||Boolean.FALSE.equals(observed.opt("screenShareAllowed")))return;
        String encoded=observed.optString("imageBase64"),mime=observed.optString("imageMimeType","image/jpeg");if(encoded.isEmpty()||encoded.length()>8*1024*1024||encoded.length()%4!=0||!encoded.matches("[A-Za-z0-9+/=\\r\\n]+")||!Set.of("image/png","image/jpeg","image/webp").contains(mime)||password(observed.optJSONArray("nodes")))return;
        byte[] actual;try{actual=Base64.getMimeDecoder().decode(encoded);}catch(IllegalArgumentException invalid){return;}if(actual.length<1||actual.length>6*1024*1024){Arrays.fill(actual,(byte)0);return;}
        String hash=hash(actual),id="screen-"+UUID.randomUUID();JSONObject metadata=new JSONObject().put("screenId",id).put("sha256",hash).put("mimeType",mime).put("sizeBytes",actual.length).put("historicalOnly",true).put("coordinateAuthority",false).put("sourceKind",observed.optString("kind","phone_observation"));
        for(String key:new String[]{"packageName","capturedAt","displayId","profileId","profileRevision","browserSessionId","tabId","imageWidthPx","imageHeightPx"})if(observed.has(key))metadata.put(key,observed.get(key));
        while(!frames.isEmpty()&&(frames.size()>=MAX_FRAMES||bytes+actual.length>MAX_BYTES))remove(frames.keySet().iterator().next());
        frames.put(id,new Frame(id,mime,hash,metadata,clock.getAsLong(),actual));bytes+=actual.length;
        observed.put("historicalScreenId",id).put("historicalScreenHash",hash);
    }
    public synchronized JSONArray index(RunControl live)throws Exception {require(live);JSONArray rows=new JSONArray();for(Frame frame:frames.values())rows.put(new JSONObject(frame.metadata));return rows;}
    public synchronized JSONObject recall(String id,RunControl live)throws Exception {
        require(live);Frame frame=frames.get(id);if(frame==null)return null;
        if(!frame.hash.equals(hash(frame.pixels))){remove(id);throw new SecurityException("历史画面字节与原摘要不符");}
        JSONObject value=new JSONObject(frame.metadata).put("kind","historical_screen_snapshot").put("imageBase64",Base64.getEncoder().encodeToString(frame.pixels)).put("imageMimeType",frame.mime).put("actionReferencesRestored",false);
        require(live);return value;
    }
    public synchronized boolean forget(String id,RunControl live)throws Exception {require(live);boolean found=frames.containsKey(id);remove(id);return found;}
    private void expire(){long now=clock.getAsLong();for(String id:java.util.List.copyOf(frames.keySet())){Frame f=frames.get(id);if(now<f.at||now-f.at>TTL)remove(id);}}
    private void remove(String id){Frame frame=frames.remove(id);if(frame!=null){bytes-=frame.pixels.length;frame.close();}}
    private void clear(){for(Frame f:frames.values())f.close();frames.clear();bytes=0;}
    @Override public synchronized void close(){closed=true;clear();}
    public static boolean supports(String name){return Set.of(LIST,VIEW,FORGET).contains(name);}
    public static void validate(String name,JSONObject args)throws Exception {if(!supports(name)||LIST.equals(name)&&args.length()!=0||!LIST.equals(name)&&(args.length()!=1||!(args.opt("screenId") instanceof String)||!args.getString("screenId").matches("screen-[a-f0-9-]{36}")))throw new IllegalArgumentException("历史图工具只接受本轮screenId，不接受observationId/nodeId/坐标");}
    public static JSONArray tools()throws Exception {JSONArray tools=new JSONArray();for(String name:new String[]{LIST,VIEW,FORGET}){JSONObject schema=new JSONObject().put("type","object").put("additionalProperties",false).put("properties",LIST.equals(name)?new JSONObject():new JSONObject().put("screenId",new JSONObject().put("type","string").put("maxLength",43))).put("required",LIST.equals(name)?new JSONArray():new JSONArray().put("screenId"));tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",LIST.equals(name)?"列本次原运行已捕获的短期历史画面；不拍新图、不恢复旧动作权限，暂停/过期/关闭即不可读。":VIEW.equals(name)?"回看准确screenId的真实图片字节，仅作历史证据；缺失明确not_found，不回退到其他画面。":"撤回本次原运行一个历史画面，后续不可回看。").put("parameters",schema)));}return tools;}
    private static boolean password(JSONArray nodes){if(nodes==null)return false;for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n!=null&&(n.optBoolean("password")||dev.pocketengine.runtime.policy.PhoneActionPolicy.financialCommit(n.optString("text"))||password(n.optJSONArray("children"))))return true;}return false;}
    private static String hash(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
