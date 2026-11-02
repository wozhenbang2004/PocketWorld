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
                if(control==null||ownerTakeover)throw new SecurityException("OCR目标必须保留原模型执行控制");
                dev.pocketengine.phone.observation.NativePhoneEntry.call(control,expectedRevision,admission,()->{writeEntered.set(true);DisplayWire.write(output,request.toString(),32*1024);return null;});
            }else if(command.startsWith("owner_handoff_")&&control!=null){
                if(!ownerTakeover)throw new SecurityException("移屏仅供原生主人");
                control.dispatchAtPauseState(expectedRevision,true,()->{writeEntered.set(true);DisplayWire.write(output,request.toString(),32*1024);});
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
