package dev.pocketengine.phone.display;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import dev.pocketengine.displayhelper.DisplayServer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Our Binder UserService; Shizuku loads it from this APK under shell/root identity. */
public final class ShizukuDisplayService extends Binder {
    static final String DESCRIPTOR="dev.pocketengine.display.bootstrap.v1";
    static final int START=IBinder.FIRST_CALL_TRANSACTION;
    // Official Shizuku UserService lifecycle transaction, not an app execution command.
    private static final int DESTROY=16777115;
    private final Context shell;
    private final int ownerUid;
    private HandlerThread thread;
    private DisplayServer server;
    private String socketName="",secret="";

    public ShizukuDisplayService()throws Exception {
        int identity=android.os.Process.myUid();
        if(identity!=0&&identity!=2000)throw new SecurityException("Privileged UserService identity required");
        shell=DisplayServer.shellContext();
        String packageName=ShizukuDisplayService.class.getName().split("\\.phone\\.display\\.",2)[0];
        ownerUid=shell.getPackageManager().getApplicationInfo(packageName,0).uid;
        if(ownerUid<10000||ownerUid>=100000)throw new SecurityException("Unsupported Android user");
        attachInterface(null,DESCRIPTOR);
    }
    public ShizukuDisplayService(Context ignored)throws Exception {this();}

    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
        if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(DESCRIPTOR);return true;}
        int caller=Binder.getCallingUid();
        if(code==DESTROY){
            if(caller!=ownerUid&&caller!=0&&caller!=2000)throw new SecurityException("Caller does not own the display service");
            close();if(reply!=null)reply.writeNoException();
            new Thread(()->{try{Thread.sleep(100);}catch(InterruptedException ignored){}System.exit(0);},"display-service-exit").start();return true;
        }
        if(code!=START)return super.onTransact(code,data,reply,flags);
        if(caller!=ownerUid)throw new SecurityException("Caller does not own the display service");
        data.enforceInterface(DESCRIPTOR);String encoded=data.readString();
        if(encoded==null||encoded.length()>4096)throw new IllegalArgumentException("Invalid display bootstrap");
        try{
            JSONObject setup=new JSONObject(encoded);if(setup.getInt("ownerUid")!=caller)throw new SecurityException("Bootstrap owner mismatch");
            start(setup);
            if(reply!=null){reply.writeNoException();reply.writeString(new JSONObject().put("ok",true).put("transport","local_socket").toString());}
        }catch(SecurityException denied){throw denied;}
        catch(Exception unavailable){throw new IllegalStateException("后台UserService未能启动，请检查Shizuku与系统支持");}
        return true;
    }

    private synchronized void start(JSONObject setup)throws Exception {
        String requestedSocket=setup.getString("socketName"),requestedSecret=setup.getString("token");
        if(server!=null){
            if(!requestedSocket.equals(socketName)||!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),requestedSecret.getBytes(StandardCharsets.UTF_8)))
                throw new SecurityException("Existing helper belongs to another bootstrap");
            return;
        }
        HandlerThread worker=new HandlerThread("pocket-display-engine");worker.start();
        try{
            server=new DisplayServer(shell,new Handler(worker.getLooper()),setup);thread=worker;
            socketName=requestedSocket;secret=requestedSecret;
        }catch(Exception failed){worker.quitSafely();throw failed;}
    }
    private synchronized void close(){
        if(server!=null){server.close();server=null;}
        if(thread!=null){thread.quitSafely();thread=null;}
        socketName="";secret="";
    }
}
