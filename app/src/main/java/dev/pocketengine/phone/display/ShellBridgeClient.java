package dev.pocketengine.phone.display;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import dev.pocketengine.core.RunControl;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** One authenticated request per connection, with a bounded frame response. */
final class ShellBridgeClient {
    private final String socketName,token;
    ShellBridgeClient(String socketName,String token){this.socketName=socketName;this.token=token;}
    /** Dedicated read-only wire lane: viewer polling never runs action gates or changes pause state. */
    JSONObject previewFrame(JSONObject input,RunControl original,
            dev.pocketengine.phone.observation.NativePhoneEntry.Admission currentOwner)throws Exception {
        if(original==null||currentOwner==null)throw new SecurityException("观看需要原生主人窗口和原执行");
        LocalSocket socket=new LocalSocket();Runnable cancel=()->{try{socket.close();}catch(Exception ignored){}};
        original.onCancel(cancel);
        try{
            previewCurrent(original,currentOwner);
            socket.connect(new LocalSocketAddress(socketName,LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(3000);
            int uid=socket.getPeerCredentials().getUid();if(uid!=0&&uid!=2000)throw new SecurityException("后台助手身份无效");
            String id=UUID.randomUUID().toString();
            JSONObject request=new JSONObject(input.toString()).put("protocol",1).put("requestId",id).put("token",token).put("command","preview_frame");
            previewCurrent(original,currentOwner);
            DisplayWire.write(new DataOutputStream(socket.getOutputStream()),request.toString(),32*1024);
            JSONObject result=new JSONObject(DisplayWire.read(new DataInputStream(socket.getInputStream()),2*1024*1024));
            previewCurrent(original,currentOwner);
            if(!id.equals(result.optString("requestId")))throw new SecurityException("原画面请求回执不匹配");
            return result;
        }finally{original.removeCancelAction(cancel);cancel.run();}
    }
    private static void previewCurrent(RunControl original,dev.pocketengine.phone.observation.NativePhoneEntry.Admission currentOwner)throws Exception {
        if(original.isCancelled()||Thread.currentThread().isInterrupted())throw new InterruptedException("原观看执行已停止");
        currentOwner.check();
        if(original.isCancelled()||Thread.currentThread().isInterrupted())throw new InterruptedException("原观看执行已停止");
    }
    JSONObject call(String command,JSONObject input,RunControl control)throws Exception {
        return call(command,input,control,control==null?-1:control.pauseRevision());
    }
    JSONObject call(String command,JSONObject input,RunControl control,long expectedRevision)throws Exception {
        return call(command,input,control,expectedRevision,false);
    }
    JSONObject call(String command,JSONObject input,RunControl control,long expectedRevision,boolean ownerTakeover)throws Exception {
        return call(command,input,control,expectedRevision,ownerTakeover,null);
    }
    JSONObject call(String command,JSONObject input,RunControl control,long expectedRevision,boolean ownerTakeover,
            dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
        return call(command,input,control,expectedRevision,ownerTakeover,admission,null);
    }
    /** Only DisplayModule can mint this first-screen permit after checking the original run. */
    JSONObject createForOwner(DisplayModule.OwnerCreation creation)throws Exception {
        java.util.Objects.requireNonNull(creation);
        return call("create",creation.payload(),creation.original,creation.revision,true,creation::check,creation);
    }
    private JSONObject call(String command,JSONObject input,RunControl control,long expectedRevision,boolean ownerTakeover,
            dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission,DisplayModule.OwnerCreation ownerCreation)throws Exception {
        if("create".equals(command)&&ownerTakeover&&ownerCreation==null)
            throw new SecurityException("暂停后的首次建屏需要原主人专用准入");
        LocalSocket socket=new LocalSocket();Runnable cancel=()->{try{socket.close();}catch(Exception ignored){}};
        java.util.concurrent.atomic.AtomicBoolean writeEntered=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            if(control!=null){control.check();fresh(control,expectedRevision,ownerTakeover);control.onCancel(cancel);}
            socket.connect(new LocalSocketAddress(socketName,LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(8000);
            int uid=socket.getPeerCredentials().getUid();
            if(uid!=0&&uid!=2000)throw new SecurityException("后台助手身份无效");
            String id=UUID.randomUUID().toString();
            JSONObject request=new JSONObject(input.toString()).put("protocol",1).put("requestId",id).put("token",token).put("command",command);
            byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>32*1024)throw new IllegalArgumentException("后台请求过大");
            if(control!=null){control.check();fresh(control,expectedRevision,ownerTakeover);}
            DataOutputStream output=new DataOutputStream(socket.getOutputStream());
            if(admission!=null){
                if(control==null||ownerTakeover&&ownerCreation==null&&!java.util.Set.of("ownership","audio.get","audio.set").contains(command))throw new SecurityException("原生准入不能扩大暂停后的主人动作范围");
                dev.pocketengine.phone.observation.NativePhoneEntry.call(control,expectedRevision,ownerTakeover,admission,()->{if(ownerCreation!=null)ownerCreation.beginWrite();writeEntered.set(true);DisplayWire.write(output,request.toString(),32*1024);return null;});
            }else if(command.startsWith("owner_handoff_")&&control!=null){
                if(!ownerTakeover)throw new SecurityException("移屏仅供原生主人");
                control.dispatchAtPauseState(expectedRevision,true,()->{writeEntered.set(true);DisplayWire.write(output,request.toString(),32*1024);});
            }else if(control!=null&&!ownerTakeover){
                // Only the bounded wire write shares pause admission, never the response wait.
                try{control.dispatchAtPauseState(expectedRevision,false,()->{writeEntered.set(true);DisplayWire.write(output,request.toString(),32*1024);});}
                catch(SecurityException changed){if(writeEntered.get())throw changed;StaleRequestException stale=new StaleRequestException();stale.initCause(changed);throw stale;}
            }else {writeEntered.set(true);DisplayWire.write(output,request.toString(),32*1024);}
            DataInputStream stream=new DataInputStream(socket.getInputStream());String response=DisplayWire.read(stream,8*1024*1024);
            if(control!=null)control.check();
            JSONObject result=new JSONObject(response);
            if(!id.equals(result.optString("requestId")))throw new SecurityException("后台请求回执不匹配");
            return result;
        }catch(Exception failure){if(command.startsWith("owner_handoff_")&&!writeEntered.get())throw new NotDispatchedException(failure);throw failure;}finally{if(control!=null)control.removeCancelAction(cancel);cancel.run();}
    }
    private static void fresh(RunControl control,long expectedRevision,boolean ownerTakeover)throws StaleRequestException {
        if(control.isPaused()!=ownerTakeover||control.pauseRevision()!=expectedRevision)throw new StaleRequestException();
    }
    static final class NotDispatchedException extends Exception {NotDispatchedException(Throwable cause){super("原移屏请求未开始写入，未派发",cause);}}
    static final class StaleRequestException extends Exception {StaleRequestException(){super("任务曾暂停，旧后台请求未派发，请重新观察");}}
}
