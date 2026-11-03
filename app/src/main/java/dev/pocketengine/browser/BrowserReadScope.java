package dev.pocketengine.browser;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure native-owner consent and read eligibility. It owns no grants, counters, database or execution loop. */
public final class BrowserReadScope {
    public static final long TTL_MS=300_000L,PUBLIC_BYTES=1024*1024;
    public static final int READS=32,SEEDS=8;
    private static final String[] IDENTITY={"taskId","runId","epoch","profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId"};
    private BrowserReadScope() { }
    /** Constructed by the native owner panel, never accepted in a model tool's arguments. */
    public static final class Consent {
        private final JSONObject policy;
        private Consent(JSONObject policy){this.policy=policy;}
        public boolean enabled(){return policy.optBoolean("enabled");}
        public JSONObject review()throws Exception{return new JSONObject(policy.toString());}
    }
    public static Consent disabled() {try{return new Consent(policy(false,false,new JSONArray()));}catch(Exception impossible){throw new AssertionError(impossible);}}
    public static Consent forOwner(boolean enabled,boolean sourceRetention,String exactSeedLines)throws Exception {
        if(!enabled)return disabled();if(exactSeedLines==null||exactSeedLines.length()>16_400)throw new IllegalArgumentException("公共种子URL总长度过大");
        LinkedHashSet<String> seeds=new LinkedHashSet<>();
        for(String line:exactSeedLines.split("\\r?\\n")){String raw=line.trim();if(raw.isEmpty())continue;
            String canonical=PublicHttpReader.request(PublicHttpReader.url(raw)).url().toString();PublicHttpReader.url(canonical);seeds.add(canonical);if(seeds.size()>SEEDS)throw new IllegalArgumentException("只读范围最多8个明确公共种子URL");}
        if(!sourceRetention&&!seeds.isEmpty())throw new IllegalArgumentException("公共HTTP工具同时保存原任务来源；请明确同意保存来源，或清空种子仅授权原页只读");
        return new Consent(policy(true,sourceRetention,new JSONArray(seeds)));
    }
    private static JSONObject policy(boolean enabled,boolean sources,JSONArray seeds)throws Exception {
        return new JSONObject().put("enabled",enabled).put("allowSourceRetention",enabled&&sources).put("ttlMs",TTL_MS).put("maxReads",READS).put("maxPublicBytes",PUBLIC_BYTES).put("publicSeeds",seeds);
    }
    /** Revalidate the exact native policy at the original TaskStore boundary. Fixed ceilings cannot be enlarged. */
    public static JSONObject validatePolicy(JSONObject supplied)throws Exception {
        JSONObject value=new JSONObject(supplied.toString());Set<String> fields=Set.of("enabled","allowSourceRetention","ttlMs","maxReads","maxPublicBytes","publicSeeds");
        if(value.length()!=fields.size())throw new SecurityException("只读范围策略字段无效");for(java.util.Iterator<String> keys=value.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new SecurityException("只读范围含额外授权字段");
        if(!Boolean.TRUE.equals(value.get("enabled"))||!(value.get("allowSourceRetention") instanceof Boolean)||value.getLong("ttlMs")!=TTL_MS||value.getInt("maxReads")!=READS||value.getLong("maxPublicBytes")!=PUBLIC_BYTES)throw new SecurityException("只读范围必须是主人明确同意的固定小范围");
        for(String key:new String[]{"ttlMs","maxReads","maxPublicBytes"}){Object number=value.get(key);if(!(number instanceof Number)||!Double.isFinite(((Number)number).doubleValue())||((Number)number).doubleValue()!=Math.rint(((Number)number).doubleValue()))throw new SecurityException("只读范围预算必须为固定整数");}
        JSONArray seeds=value.getJSONArray("publicSeeds");if(seeds.length()>SEEDS||!value.getBoolean("allowSourceRetention")&&seeds.length()>0)throw new SecurityException("公共种子或来源保存未获主人明确同意");
        Set<String> unique=new LinkedHashSet<>();for(int i=0;i<seeds.length();i++){String seed=seeds.getString(i);if(!seed.equals(PublicHttpReader.request(PublicHttpReader.url(seed)).url().toString())||!unique.add(seed))throw new SecurityException("公共种子必须是逐字固定、无重复的实际请求URL");}
        return value;
    }
    public static JSONObject identity(JSONObject pin,long pauseRevision)throws Exception {
        JSONObject value=new JSONObject();for(String key:IDENTITY)value.put(key,pin.get(key));
        for(String key:new String[]{"taskId","runId","browserSessionId","browserSpaceId"})BrowserTaskSpace.uuid(value.getString(key));
        if(value.getInt("epoch")<1||value.getLong("profileRevision")<1||!value.getString("profileId").matches("[A-Za-z0-9_-]{1,80}")||!Set.of("androidx-multi-profile","default-webview-only").contains(value.getString("profileIsolation"))||pauseRevision<0)throw new SecurityException("只读范围需要实际原任务/资料/浏览器身份");
        return value.put("pauseRevision",pauseRevision);
    }
    public static boolean sameIdentity(JSONObject granted,JSONObject nativePin,long pauseRevision)throws Exception {
        JSONObject actual=identity(nativePin,pauseRevision);for(java.util.Iterator<String> keys=actual.keys();keys.hasNext();){String key=keys.next();if(!String.valueOf(granted.opt(key)).equals(String.valueOf(actual.get(key))))return false;}return true;
    }
    public static boolean readTool(String tool){return Set.of("browser_task_observe","browser_task_wait","browser_task_download_status","browser_task_retain_source",PublicHttpReader.TOOL).contains(tool);}
    /** Exact existing page IDs, original observations and exact owner seeds only. No synthetic href authority. */
    public static boolean eligible(JSONObject policy,String tool,JSONObject args,JSONObject nativePin)throws Exception {
        if(!readTool(tool))return false;Set<String> fields;
        switch(tool){
            case "browser_task_observe":fields=Set.of("tabId","read","recipe","frameRef");break;
            case "browser_task_wait":fields=Set.of("tabId","condition","value","target","barrier","timeoutMs");break;
            case "browser_task_download_status":fields=Set.of("downloadId");break;
            case "browser_task_retain_source":fields=Set.of("tabId","observationId","excerpt");break;
            default:fields=Set.of("url","allowedRedirectOrigins","maxBytes","maxChars");
        }
        for(java.util.Iterator<String> keys=args.keys();keys.hasNext();)if(!fields.contains(keys.next()))return false;
        if(tool.equals(PublicHttpReader.TOOL)){
            if(!policy.getBoolean("allowSourceRetention"))return false;PublicHttpReader.validate(args);boolean seed=false;Set<String> origins=new LinkedHashSet<>();JSONArray seeds=policy.getJSONArray("publicSeeds");
            for(int i=0;i<seeds.length();i++){String fixed=seeds.getString(i);if(fixed.equals(args.getString("url")))seed=true;okhttp3.HttpUrl u=PublicHttpReader.url(fixed);origins.add(PublicHttpReader.origin(u));if(!u.isHttps())origins.add("https://"+(u.host().contains(":")?"["+u.host()+"]":u.host()));}
            if(!seed)return false;JSONArray redirects=args.optJSONArray("allowedRedirectOrigins");if(redirects!=null)for(int i=0;i<redirects.length();i++)if(!origins.contains(redirects.getString(i)))return false;return true;
        }
        if(tool.equals("browser_task_download_status"))return args.getString("downloadId").equals(nativePin.optString("downloadId"));
        if(!args.getString("tabId").equals(nativePin.optString("tabId")))return false;
        if(tool.equals("browser_task_retain_source"))return policy.getBoolean("allowSourceRetention")&&args.getString("observationId").equals(nativePin.optString("observationId"))&&(!args.has("excerpt")||args.get("excerpt") instanceof String&&args.getString("excerpt").length()<=6000);
        if(args.has("frameRef")&&(nativePin.optJSONObject("readFrame")==null||!args.getString("frameRef").equals(nativePin.getJSONObject("readFrame").optString("frameId"))))return false;
        if(tool.equals("browser_task_observe")&&args.has("read"))BrowserSemanticRead.validate(args.getJSONObject("read"));if(args.has("recipe")){if(!tool.equals("browser_task_observe")||!args.has("read"))return false;BrowserReadRecipe.reference(args.getJSONObject("recipe"));}
        if(tool.equals("browser_task_wait")){
            String condition=args.getString("condition");
            if(condition.equals("barrier")){if(args.has("value")||args.has("target"))return false;BrowserWaitBarrier.validate(args.getJSONObject("barrier"));String capture=args.getJSONObject("barrier").optString("captureId");if(!capture.isEmpty()&&(nativePin.optJSONObject("networkCapture")==null||!capture.equals(nativePin.getJSONObject("networkCapture").optString("captureId"))))return false;}
            else {if(args.has("barrier"))return false;if(condition.equals("target")){if(args.has("value")||!args.has("target"))return false;BrowserTargetCondition.validate(args.getJSONObject("target"));if(args.getJSONObject("target").has("frameRef")&&(nativePin.optJSONObject("readFrame")==null||!args.getJSONObject("target").getString("frameRef").equals(nativePin.getJSONObject("readFrame").optString("frameId"))))return false;}
            else if(args.has("target")||!Set.of("load","url","text").contains(condition)||condition.equals("load")&&args.has("value")||!condition.equals("load")&&args.optString("value").isEmpty())return false;}
            if(args.has("timeoutMs")){Object ms=args.get("timeoutMs");if(!(ms instanceof Number)||((Number)ms).doubleValue()!=args.optInt("timeoutMs")||args.getInt("timeoutMs")<100||args.getInt("timeoutMs")>6000)return false;}
        }
        return true;
    }
    public static long bodyReservation(String tool,JSONObject args){return tool.equals(PublicHttpReader.TOOL)?args.optInt("maxBytes",PublicHttpReader.DEFAULT_BYTES):0;}
    public static JSONObject receipt(JSONObject actual,JSONObject permit)throws Exception {
        if(permit==null)return actual;return actual.put("readAuthorization",new JSONObject().put("mode","native_read_scope").put("scopeId",permit.getString("scopeId")).put("scopeRevision",permit.getLong("scopeRevision"))
                .put("operationId",permit.getString("operationId")).put("admittedAt",permit.getLong("admittedAt")).put("remainingReadsAtAdmission",permit.getInt("remainingReadsAtAdmission")).put("remainingPublicBytesAtAdmission",permit.getLong("remainingPublicBytesAtAdmission")));
    }
}
