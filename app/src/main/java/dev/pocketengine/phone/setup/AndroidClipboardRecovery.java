package dev.pocketengine.phone.setup;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Parcel;
import android.util.Base64;
import dev.pocketengine.store.SecretVault;

/** Public clipboard API only. Background clipboard reads denied by Android stay unavailable. */
public final class AndroidClipboardRecovery {
    private AndroidClipboardRecovery() { }
    public static boolean allowed(Context context){return context.getApplicationContext().getSharedPreferences("phone_clipboard_policy",0).getBoolean("enabled",false);}
    public static void setOwnerAllowed(Context context,boolean enabled){if(!context.getApplicationContext().getSharedPreferences("phone_clipboard_policy",0).edit().putBoolean("enabled",enabled).commit())throw new IllegalStateException("clipboard_policy_save_failed");}
    public static ClipboardRestoration create(Context context){return create(context,null,-1,null);}
    public static ClipboardRestoration create(Context context,dev.pocketengine.core.RunControl control,long revision,dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission){
        Context app=context.getApplicationContext();SecretVault vault=new SecretVault(app);String key="phone.clipboard.pending.v1";
        ClipboardRestoration.Storage store=new ClipboardRestoration.Storage(){public String read()throws Exception{String value=vault.get(key);return value.isEmpty()?null:value;}public void write(String value)throws Exception{vault.put(key,value);if(!value.equals(vault.get(key)))throw new IllegalStateException("clipboard_original_save_failed");}public void clear()throws Exception{vault.remove(key);}};
        ClipboardManager manager=app.getSystemService(ClipboardManager.class);
        ClipboardRestoration.Clipboard clipboard=new ClipboardRestoration.Clipboard(){
            public String snapshot(){if(manager==null)return null;ClipData clip=manager.getPrimaryClip();if(clip==null){ActivityManager.RunningAppProcessInfo info=new ActivityManager.RunningAppProcessInfo();ActivityManager.getMyMemoryState(info);return info.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND?"empty":null;}return encode(clip);}
            public String replacement(String text){return encode(ClipData.newPlainText("PocketWorld temporary input",text));}
            public void installTemporary(String value)throws Exception {if(control==null)throw new SecurityException("clipboard_original_run_required");dev.pocketengine.phone.observation.NativePhoneEntry.call(control,revision,()->{if(!allowed(app))throw new SecurityException("clipboard_owner_permission_required");java.util.Objects.requireNonNull(admission).check();},()->{install(value);return null;});}
            public String fingerprint(String value)throws Exception {if(value==null)throw new SecurityException("clipboard_unreadable");if(value.equals("empty"))return "empty";Parcel parcel=Parcel.obtain();try{byte[] bytes=Base64.decode(value,Base64.NO_WRAP);parcel.unmarshall(bytes,0,bytes.length);parcel.setDataPosition(0);ClipData clip=ClipData.CREATOR.createFromParcel(parcel);StringBuilder stable=new StringBuilder();stable.append(clip.getDescription().getLabel()).append('\0');for(int i=0;i<clip.getDescription().getMimeTypeCount();i++)stable.append(clip.getDescription().getMimeType(i)).append('\0');for(int i=0;i<clip.getItemCount();i++){ClipData.Item item=clip.getItemAt(i);stable.append(item.getText()).append('\0').append(item.getHtmlText()).append('\0').append(item.getUri()).append('\0').append(item.getIntent()==null?"":item.getIntent().toUri(0)).append('\0');}byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(stable.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));return Base64.encodeToString(digest,Base64.NO_WRAP);}finally{parcel.recycle();}}
            public void install(String value){if(manager==null)throw new SecurityException("clipboard_unavailable");if(value.equals("empty")){manager.clearPrimaryClip();return;}Parcel parcel=Parcel.obtain();try{byte[] bytes=Base64.decode(value,Base64.NO_WRAP);if(bytes.length>100000)throw new SecurityException("clipboard_snapshot_too_large");parcel.unmarshall(bytes,0,bytes.length);parcel.setDataPosition(0);manager.setPrimaryClip(ClipData.CREATOR.createFromParcel(parcel));}finally{parcel.recycle();}}
            private String encode(ClipData clip){Parcel parcel=Parcel.obtain();try{clip.writeToParcel(parcel,0);byte[] bytes=parcel.marshall();return bytes.length>100000?null:Base64.encodeToString(bytes,Base64.NO_WRAP);}finally{parcel.recycle();}}
        };
        return new ClipboardRestoration(store,clipboard);
    }
}

