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
    public interface Caller { JSONObject complete(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,InferenceCapture.Target target)throws Exception; }
    public static JSONObject run(ModelConfig config,boolean image,RunControl control,InferenceCapture.Target target)throws Exception {
        return run(config,image,control,target,ModelGateway::complete);
    }
    static JSONObject run(ModelConfig selected,boolean image,RunControl control,InferenceCapture.Target target,Caller caller)throws Exception {
        if(selected.usesAccount()||selected.capabilityBinding==null)throw new IllegalArgumentException("请选择原API连接后探测；账号能力仍由账号页核对");
        // A probe asks an unknown capability a question; it is not an owner override of that fact.
        // Keep the native exact binding/currentness, output reserve and original accounting gate.
        ModelConfig config=selected.withApiConnection(selected.capabilityBinding).withRoutePlan(null);
        JSONObject result=new JSONObject().put("format","pocketworld.model-probe.v1").put("binding",selected.capabilityBinding.toJson())
                .put("model",selected.model).put("configurationDigest",ModelRoutePlan.configurationDigest(selected)).put("probeRequestDigest",ModelRoutePlan.configurationDigest(config)).put("at",System.currentTimeMillis())
                .put("deviceToolsExecuted",false).put("capabilityMetadataChanged",false).put("text", "not_run").put("tools","not_run").put("image",image?"not_run":"not_requested");
        control.check();
        String token=UUID.randomUUID().toString();
        try{JSONObject text=caller.complete(config,new JSONArray().put(new JSONObject().put("role","user").put("content","Return exactly this diagnostic token: "+token)),null,control,target);
            result.put("text",token.equals(text.optString("content").trim())?"passed":"failed");}catch(InterruptedException stopped){throw stopped;}catch(SecurityException revoked){throw revoked;}catch(Exception failed){control.check();result.put("text","failed");}
        control.check();
        JSONArray tools=new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","pocket_capability_echo")
                .put("description","Diagnostic only. Return the supplied token; no function will be executed.")
                .put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("token",new JSONObject().put("type","string")))
                        .put("required",new JSONArray().put("token")).put("additionalProperties",false))));
        JSONObject reply;try{reply=caller.complete(config,new JSONArray().put(new JSONObject().put("role","user").put("content","Call pocket_capability_echo exactly once with token "+token+". Do not answer in text.")),tools,control,target);}catch(InterruptedException stopped){throw stopped;}catch(SecurityException revoked){throw revoked;}catch(Exception failed){control.check();reply=new JSONObject();}
        boolean called=false;JSONArray calls=reply.optJSONArray("tool_calls");
        if(calls!=null&&calls.length()==1)try{JSONObject fn=calls.getJSONObject(0).getJSONObject("function");JSONObject args=new JSONObject(fn.getString("arguments"));called="pocket_capability_echo".equals(fn.getString("name"))&&args.length()==1&&token.equals(args.getString("token"));}catch(Exception invalid){called=false;}
        result.put("tools",called?"passed":"failed");
        if(image){
            control.check();String digits=String.format(java.util.Locale.ROOT,"%06d",new java.security.SecureRandom().nextInt(1000000));
            JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text","Read the six digits in this diagnostic image. Reply with those digits only."))
                    .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",image(digits))));
            try{JSONObject visual=caller.complete(config,new JSONArray().put(new JSONObject().put("role","user").put("content",content)),null,control,target);
                result.put("image",digits.equals(visual.optString("content").trim())?"passed":"failed");}catch(InterruptedException stopped){throw stopped;}catch(SecurityException revoked){throw revoked;}catch(Exception failed){control.check();result.put("image","failed");}
        }
        return result.put("meaning","one_synthetic_probe_not_general_quality_or_device_permission");
    }
    private static String image(String digits)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(360,100,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setColor(Color.BLACK);ink.setTextSize(64);ink.setTypeface(android.graphics.Typeface.MONOSPACE);canvas.drawText(digits,35,74,ink);ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IllegalStateException("diagnostic_image_unavailable");return "data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());}finally{bitmap.recycle();}
    }
}
