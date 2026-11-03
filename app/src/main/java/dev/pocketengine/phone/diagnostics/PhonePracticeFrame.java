package dev.pocketengine.phone.diagnostics;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.Base64;
import org.json.JSONObject;

/** Local pixels only; returns dimensions/availability, never screenshot bytes or screen text. */
public final class PhonePracticeFrame {
    private PhonePracticeFrame() { }
    public static JSONObject inspect(JSONObject actual,int display,String session,long revision)throws Exception {
        if(actual==null||!actual.optBoolean("ok")||actual.optInt("displayId",-1)!=display||!session.equals(actual.optString("sessionId"))||actual.optLong("pauseRevision",-1)!=revision)return result("unknown","original_frame_identity_unavailable",0,0);
        String encoded=actual.optString("imageBase64");if(encoded.isEmpty()||encoded.length()>8*1024*1024)return result("failed","actual_image_unavailable",0,0);byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(Exception invalid){return result("failed","image_encoding_invalid",0,0);}
        Bitmap bitmap=null;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            if(bounds.outWidth<1||bounds.outHeight<1||(long)bounds.outWidth*bounds.outHeight>2400000||bounds.outWidth!=actual.optInt("imageWidth",actual.optInt("screenWidth",-1))||bounds.outHeight!=actual.optInt("imageHeight",actual.optInt("screenHeight",-1)))return result("failed","image_dimensions_invalid",0,0);
            bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)return result("failed","image_decode_failed",0,0);
            int min=255,max=0;long total=0;int count=0;for(int y=0;y<24;y++)for(int x=0;x<24;x++){int pixel=bitmap.getPixel(Math.min(bitmap.getWidth()-1,(2*x+1)*bitmap.getWidth()/48),Math.min(bitmap.getHeight()-1,(2*y+1)*bitmap.getHeight()/48));int luma=((pixel>>16&255)*299+(pixel>>8&255)*587+(pixel&255)*114)/1000;min=Math.min(min,luma);max=Math.max(max,luma);total+=luma;count++;}boolean varied=max-min>=8&&total/count>4;return result(varied?"passed":"failed",varied?"actual_nonuniform_frame":"blank_or_uniform_frame",bitmap.getWidth(),bitmap.getHeight());
        }finally{if(bitmap!=null)bitmap.recycle();java.util.Arrays.fill(bytes,(byte)0);}
    }
    private static JSONObject result(String state,String reason,int width,int height)throws Exception{return new JSONObject().put("state",state).put("reason",reason).put("width",width).put("height",height).put("localOnly",true).put("imageUploaded",false).put("containsScreenContent",false);}
}
