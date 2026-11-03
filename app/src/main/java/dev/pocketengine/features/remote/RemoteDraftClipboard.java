package dev.pocketengine.features.remote;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import dev.pocketengine.features.RemoteOwnerWorkflows;

/** Explicit native owner paste into a local editor, never clipboard synchronization
 * or execution permission. No URI/provider coercion, clipboard writes or logging. */
public final class RemoteDraftClipboard {
    private RemoteDraftClipboard(){}
    public enum Reason {
        TARGET("原草稿目标或审阅已变化，请查询原任务后再读取。"),
        FOREGROUND("仅当前前台、聚焦的原页面能读取本机剪贴板。"),
        LOCKED("锁屏状态或锁屏状态未知，未读取本机剪贴板。"),
        UNAVAILABLE("本次剪贴板不可用，未导入；请重新审阅后再试。"),
        PLAIN_ONLY("仅导入一项纯文本；不转换URI、Intent、HTML或其他内容。"),
        SENSITIVE("来源标为敏感内容，未导入本机草稿。"),
        LENGTH("纯文本须为1至12000字符；未截断、未导入。"),
        APPEND_LIMIT("追加后超过12000字符；原编辑文字保留，未截断、未发送。");
        public final String message;
        Reason(String message){this.message=message;}
    }
    public static final class Failure extends SecurityException {
        public final Reason reason;
        private Failure(Reason reason){super(reason.message);this.reason=reason;}
    }
    public static Failure targetFailure(){return new Failure(Reason.TARGET);}
    public static Failure unavailableFailure(){return new Failure(Reason.UNAVAILABLE);}

    /** Metadata is checked before the one raw sample and checked again on it.
     * Android may announce an access; no claim of effect-free OS internals is made. */
    public static ClipData read(Activity owner,View focused,RemoteOwnerWorkflows.ClipboardDraftReadAccess approval){
        requireUi(owner,focused);check(approval,owner,true);requireUi(owner,focused);
        try{
            ClipboardManager clipboard=owner.getSystemService(ClipboardManager.class);
            if(clipboard==null)throw unavailableFailure();
            description(clipboard.getPrimaryClipDescription());
            requireUi(owner,focused);check(approval,owner,false);
            ClipData sample=clipboard.getPrimaryClip();
            requireUi(owner,focused);check(approval,owner,false);
            if(sample==null)throw unavailableFailure();
            description(sample.getDescription());return sample;
        }catch(Failure safe){throw safe;}
        catch(Exception unavailable){throw unavailableFailure();}
    }
    private static void check(RemoteOwnerWorkflows.ClipboardDraftReadAccess approval,Object owner,boolean claim){
        if(approval==null)throw targetFailure();
        try{if(claim)approval.claimNativeRead(owner);else approval.checkCurrent();}
        catch(Exception changed){throw targetFailure();}
    }
    private static void requireUi(Activity owner,View focused){
        if(Looper.myLooper()!=Looper.getMainLooper()||owner==null||owner.isFinishing()||owner.isDestroyed()
                ||!(owner instanceof LifecycleOwner)||!((LifecycleOwner)owner).getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                ||focused==null||!focused.isAttachedToWindow()||!focused.isShown()||!focused.hasWindowFocus()||!belongsTo(focused.getContext(),owner))throw new Failure(Reason.FOREGROUND);
        KeyguardManager keyguard=owner.getSystemService(KeyguardManager.class);
        try{if(keyguard==null||keyguard.isDeviceLocked()||keyguard.isKeyguardLocked())throw new Failure(Reason.LOCKED);}
        catch(Failure safe){throw safe;}catch(Exception unknown){throw new Failure(Reason.LOCKED);}
    }
    private static boolean belongsTo(Context context,Activity owner){
        for(int i=0;i<32&&context!=null;i++){
            if(context==owner)return true;
            if(!(context instanceof ContextWrapper))return false;
            Context next=((ContextWrapper)context).getBaseContext();if(next==context)return false;context=next;
        }return false;
    }
    private static void description(ClipDescription description){
        if(description==null)throw unavailableFailure();
        if(description.getExtras()!=null&&description.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE,false))throw new Failure(Reason.SENSITIVE);
        if(description.getMimeTypeCount()!=1||!ClipDescription.MIMETYPE_TEXT_PLAIN.equals(description.getMimeType(0)))throw new Failure(Reason.PLAIN_ONLY);
    }
    /** Data-only parsing of the actual returned sample; it cannot mint a read approval. */
    public static String text(ClipData sample){
        if(sample==null)throw unavailableFailure();
        description(sample.getDescription());
        if(sample.getItemCount()!=1)throw new Failure(Reason.PLAIN_ONLY);
        ClipData.Item item=sample.getItemAt(0);
        if(item.getUri()!=null||item.getIntent()!=null||item.getHtmlText()!=null||Build.VERSION.SDK_INT>=35&&item.getIntentSender()!=null)throw new Failure(Reason.PLAIN_ONLY);
        CharSequence raw=item.getText();if(raw==null||raw.length()>12000)throw new Failure(Reason.LENGTH);
        try{return RemoteTaskDrafts.text(raw.toString());}catch(IllegalArgumentException invalid){throw new Failure(Reason.LENGTH);}
    }
    /** Preserve the exact existing editor text. Never overwrite or silently truncate. */
    public static String append(String existing,String imported){
        if(existing==null||imported==null)throw new Failure(Reason.LENGTH);
        try{RemoteTaskDrafts.text(imported);}catch(IllegalArgumentException invalid){throw new Failure(Reason.LENGTH);}
        String separator=existing.isEmpty()||existing.endsWith("\n")?"":"\n";
        if((long)existing.length()+separator.length()+imported.length()>12000)throw new Failure(Reason.APPEND_LIMIT);
        return existing+separator+imported;
    }
}
