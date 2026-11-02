package dev.pocketengine.runtime.models;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit owner diagnostics. The declared function is never connected to an executor. */
public final class ModelCapabilityProbe {
    private ModelCapabilityProbe() { }
    private static final String TEXT="Return exactly this diagnostic token: ";
    private static final String FUNCTION="Call pocket_capability_echo exactly once with token ", FUNCTION_END=". Do not answer in text.";
    private static final String IMAGE="Read the six digits in this diagnostic image. Reply with those digits only.";
    private static final String TOKEN="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    public interface Caller { JSONObject complete(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,InferenceCapture.Target target)throws Exception; }
    public static JSONObject run(ModelConfig config,boolean image,RunControl control,InferenceCapture.Target target)throws Exception {
        return run(config,image,control,target,ModelGateway::complete);
    }
    static JSONObject run(ModelConfig selected,boolean image,RunControl control,InferenceCapture.Target target,Caller caller)throws Exception {
        if(selected.usesAccount()||selected.capabilityBinding==null)throw new IllegalArgumentException("请选择原API连接后探测；账号能力仍由账号页核对");
        InferenceCapture.requireOriginalContext(control,target);
        if(target.context.purpose!=dev.pocketengine.runtime.usage.InferenceReceipt.Purpose.MODEL_CAPABILITY_PROBE||target.logical==null)throw new SecurityException("native_model_capability_probe_session_required");
        // The diagnostic purpose permits a synthetic question about an unknown capability.
        // The exact catalog contract, binding, limits and original SQL admission remain intact.
        ModelConfig config=selected.withRoutePlan(null);
        JSONObject result=new JSONObject().put("format","pocketworld.model-probe.v1").put("binding",selected.capabilityBinding.toJson())
                .put("model",selected.model).put("configurationDigest",ModelRoutePlan.configurationDigest(selected)).put("probeRequestDigest",ModelRoutePlan.configurationDigest(config)).put("at",System.currentTimeMillis())
                .put("deviceToolsExecuted",false).put("capabilityMetadataChanged",false).put("text", "not_run").put("tools","not_run").put("image",image?"not_run":"not_requested");
        control.check();
        String token=UUID.randomUUID().toString();
        try{JSONObject text=caller.complete(config,new JSONArray().put(new JSONObject().put("role","user").put("content",TEXT+token)),null,control,target);
            result.put("text",token.equals(text.optString("content").trim())?"passed":"failed");}catch(InterruptedException stopped){throw stopped;}catch(SecurityException revoked){throw revoked;}catch(Exception failed){control.check();result.put("text","failed");}
        control.check();
        JSONArray tools=echoTools();
        JSONObject reply;try{reply=caller.complete(config,new JSONArray().put(new JSONObject().put("role","user").put("content",FUNCTION+token+FUNCTION_END)),tools,control,target);}catch(InterruptedException stopped){throw stopped;}catch(SecurityException revoked){throw revoked;}catch(Exception failed){control.check();reply=new JSONObject();}
        boolean called=false;JSONArray calls=reply.optJSONArray("tool_calls");
        if(calls!=null&&calls.length()==1)try{JSONObject fn=calls.getJSONObject(0).getJSONObject("function");JSONObject args=new JSONObject(fn.getString("arguments"));called="pocket_capability_echo".equals(fn.getString("name"))&&args.length()==1&&token.equals(args.getString("token"));}catch(Exception invalid){called=false;}
        result.put("tools",called?"passed":"failed");
        if(image){
            control.check();String digits=String.format(java.util.Locale.ROOT,"%06d",new java.security.SecureRandom().nextInt(1000000));
            JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text",IMAGE))
                    .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",image(digits))));
            try{JSONObject visual=caller.complete(config,new JSONArray().put(new JSONObject().put("role","user").put("content",content)),null,control,target);
                result.put("image",digits.equals(visual.optString("content").trim())?"passed":"failed");}catch(InterruptedException stopped){throw stopped;}catch(SecurityException revoked){throw revoked;}catch(Exception failed){control.check();result.put("image","failed");}
        }
        return result.put("meaning","one_synthetic_probe_not_general_quality_or_device_permission");
    }
    private static JSONArray echoTools()throws Exception {
        return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","pocket_capability_echo")
                .put("description","Diagnostic only. Return the supplied token; no function will be executed.")
                .put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("token",new JSONObject().put("type","string")))
                        .put("required",new JSONArray().put("token")).put("additionalProperties",false))));
    }
    /** A native purpose cannot turn ordinary prompts, histories or functions into a probe. */
    public static void requireSyntheticRequest(JSONArray messages,JSONArray tools)throws Exception {
        if(messages==null||messages.length()!=1)throw new SecurityException("synthetic_model_probe_request_required");
        JSONObject row=messages.optJSONObject(0);if(row==null||row.length()!=2||!"user".equals(row.optString("role")))throw new SecurityException("synthetic_model_probe_request_required");
        Object content=row.opt("content");boolean noTools=tools==null||tools.length()==0;
        if(content instanceof String text){
            if(noTools&&text.startsWith(TEXT)&&text.substring(TEXT.length()).matches(TOKEN))return;
            if(text.startsWith(FUNCTION)&&text.endsWith(FUNCTION_END)&&text.substring(FUNCTION.length(),text.length()-FUNCTION_END.length()).matches(TOKEN)&&tools!=null&&tools.toString().equals(echoTools().toString()))return;
        }else if(noTools&&content instanceof JSONArray parts&&parts.length()==2){
            JSONObject label=parts.optJSONObject(0),picture=parts.optJSONObject(1),image=picture==null?null:picture.optJSONObject("image_url");
            if(label!=null&&label.length()==2&&"text".equals(label.optString("type"))&&IMAGE.equals(label.optString("text"))&&picture!=null&&picture.length()==2&&"image_url".equals(picture.optString("type"))&&image!=null&&image.length()==1){
                String url=image.optString("url"),prefix="data:image/png;base64,";
                if(url.startsWith(prefix)&&url.length()<=32768)try{byte[] png=Base64.getDecoder().decode(url.substring(prefix.length()));
                    if(png.length>=24&&java.nio.ByteBuffer.wrap(png).getLong()==0x89504e470d0a1a0aL&&java.nio.ByteBuffer.wrap(png,12,12).getInt()==0x49484452&&java.nio.ByteBuffer.wrap(png,16,8).getInt()==360&&java.nio.ByteBuffer.wrap(png,20,4).getInt()==100)return;
                }catch(IllegalArgumentException invalid){/* Malformed data is not a synthetic diagnostic image. */}
            }
        }
        throw new SecurityException("synthetic_model_probe_request_required");
    }
    private static String image(String digits)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(360,100,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setColor(Color.BLACK);ink.setTextSize(64);ink.setTypeface(android.graphics.Typeface.MONOSPACE);canvas.drawText(digits,35,74,ink);ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IllegalStateException("diagnostic_image_unavailable");return "data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());}finally{bitmap.recycle();}
    }
}
