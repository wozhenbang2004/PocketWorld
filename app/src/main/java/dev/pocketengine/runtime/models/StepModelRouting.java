package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Run-local choice only. TaskStore/Gateway retain all admission, retries and physical usage. */
public final class StepModelRouting {
    public interface Source { ModelConfig light(ModelConfig capturedMain)throws Exception; }
    private static volatile Source source;
    public static void install(Source nativeSource){source=nativeSource;}
    private StepModelRouting() { }
    public static Session forRun(ModelConfig main,RunControl control){
        ModelConfig light=null;Source captured=source;
        try{if(captured!=null&&!control.isCancelled())light=captured.light(main);}catch(Exception unavailable){/* Opt-in unavailable: retain the captured main route. */}
        return new Session(main,light,control);
    }
    public static final class Session {
        private final ModelConfig main,light;private final RunControl control;
        private int successfulReads,mainTurns;private long pause;private String place="";private boolean usingLight;
        private Session(ModelConfig main,ModelConfig light,RunControl control){this.main=main;this.light=light;this.control=control;pause=control.pauseRevision();}
        public ModelConfig pick(int round,JSONObject latest,long pauseRevision){
            return pick(round,latest,pauseRevision,true);
        }
        /** The original Runner may require MAIN at a native budget/terminal-only boundary. */
        public ModelConfig pick(int round,JSONObject latest,long pauseRevision,boolean allowLight){
            String current=place(latest);boolean same=!current.isEmpty()&&current.equals(place);
            if(pauseRevision!=pause||!same){successfulReads=0;mainTurns=Math.max(mainTurns,1);}pause=pauseRevision;place=current;
            usingLight=allowLight&&light!=null&&!control.isCancelled()&&round>0&&successfulReads>=2&&same&&mainTurns==0;
            if(mainTurns>0)mainTurns--;return usingLight?light:main;
        }
        /** Called before an answer/tool enters history. A rejected light response has zero tool dispatch. */
        public boolean accept(JSONObject response){
            if(!usingLight)return true;JSONArray calls=response.optJSONArray("tool_calls");
            boolean safe=calls!=null&&calls.length()==1;
            if(safe)try{JSONObject fn=calls.getJSONObject(0).getJSONObject("function");safe=safeTool(fn.getString("name"),new JSONObject(fn.getString("arguments")));}catch(Exception invalid){safe=false;}
            if(!safe){successfulReads=0;mainTurns=2;}return safe;
        }
        public void failed(Exception failure){if(usingLight){successfulReads=0;mainTurns=2;}}
        public void after(String name,JSONObject args,JSONObject receipt,JSONObject latest){
            boolean okay=receipt!=null&&receipt.optBoolean("ok",false)&&!receipt.optString("code").contains("unknown")&&!(receipt.has("accepted")&&receipt.isNull("accepted"))
                    &&!receipt.optBoolean("requiresPause")&&!(receipt.has("matched")&&!receipt.optBoolean("matched"))&&safeTool(name,args);
            String current=place(latest);
            if(okay&&!current.isEmpty()&&current.equals(place)){successfulReads=Math.min(3,successfulReads+1);}else {successfulReads=0;mainTurns=2;}
            place=current;
        }
        public boolean usingLight(){return usingLight;}
        public JSONObject origin()throws Exception{return new JSONObject().put("format","pocketworld.step-model-origin.v1").put("configurationDigest",identity(usingLight?light:main));}
        /** Durable history stays intact; unproven/foreign opaque reasoning never crosses routes. */
        public JSONArray wireMessages(JSONArray history)throws Exception {
            String digest=identity(usingLight?light:main);JSONArray out=new JSONArray();
            for(int i=0;i<history.length();i++){JSONObject row=new JSONObject(history.getJSONObject(i).toString());JSONObject origin=row.optJSONObject("nativeStepModelOrigin");row.remove("nativeStepModelOrigin");
                if(light!=null&&(origin==null?usingLight:!digest.equals(origin.optString("configurationDigest")))){row.remove("provider_data");JSONArray calls=row.optJSONArray("tool_calls");for(int n=0;calls!=null&&n<calls.length();n++){JSONObject call=calls.optJSONObject(n);if(call!=null){call.remove("provider_data");call.remove("opaque");JSONObject fn=call.optJSONObject("function");if(fn!=null){fn.remove("provider_data");fn.remove("opaque");}}}}
                out.put(row);
            }return out;
        }
    }
    private static String identity(ModelConfig model)throws Exception {String nativeIdentity=model.usesAccount()?model.accountModel.toJson().toString():model.capabilityBinding==null?"legacy-main":model.capabilityBinding.toJson().toString();StringBuilder out=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest((nativeIdentity+ModelRoutePlan.configurationDigest(model)).getBytes(java.nio.charset.StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String place(JSONObject observation){if(observation==null)return "";for(String key:new String[]{"packageName","package","appPackage","browserSpaceId"}){String value=observation.optString(key);if(!value.isEmpty()&&value.length()<300)return key+":"+value;}return "";}
    private static boolean safeTool(String name,JSONObject args){
        // Explicit closed set: arbitrary capabilities, scripts, writes and native batches remain MAIN.
        return java.util.Set.of("phone_observe","phone_query","phone_condition_wait","phone_wait","browser_task_observe","browser_task_read_public").contains(name)
                &&args!=null&&!args.optBoolean("includeImage")&&!args.optBoolean("captureImage");
    }
}
