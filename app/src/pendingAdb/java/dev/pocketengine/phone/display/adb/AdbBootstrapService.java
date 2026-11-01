package dev.pocketengine.phone.display.adb;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.conscrypt.Conscrypt;
import org.json.JSONObject;

/**
 * Non-exported :adb_bootstrap process only. The blocking pairing library is never
 * allowed to hold the main process or its cancellation thread. Hard cancellation
 * terminates this application's own dedicated child process, not a supplied PID.
 */
public final class AdbBootstrapService extends Service {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<String> current=new AtomicReference<>();
    private volatile ScheduledFuture<?> deadline;
    private volatile Manager manager;
    private volatile AdbStream helperStream;
    private volatile byte[] identityDigest;
    private volatile IBinder callback;
    private volatile long generation;
    private volatile String phase="idle";
    private boolean terminating;

    private final Binder binder=new Binder(){
        @Override protected synchronized boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(AdbBootstrapWire.SERVICE);return true;}
            if(Binder.getCallingUid()!=Process.myUid())throw new SecurityException("ADB启动器只接受本应用私有连接");
            data.enforceInterface(AdbBootstrapWire.SERVICE);
            if(code==AdbBootstrapWire.INFO){
                if(reply!=null){reply.writeNoException();reply.writeLong(generation);reply.writeString(phase);reply.writeString(current.get());}return true;
            }
            if(code==AdbBootstrapWire.CANCEL){
                // No call to Future.cancel(), manager.close() or a stuck library monitor.
                // A different executor performs SIGKILL even if pair() never returns.
                boolean accepted=data.readLong()==generation;
                if(accepted)terminating=true;
                if(reply!=null){reply.writeNoException();reply.writeInt(accepted?1:0);}
                if(accepted)deadlines.schedule(AdbBootstrapService.this::killSelf,25,TimeUnit.MILLISECONDS);return true;
            }
            if(code==AdbBootstrapWire.READY){String operation=data.readString();
                boolean accepted=operation!=null&&operation.equals(current.get())&&phase.equals("helper_started");
                if(accepted){
                    ScheduledFuture<?> timer=deadline;if(timer!=null)timer.cancel(false);current.compareAndSet(operation,null);phase="ready";
                }if(reply!=null){reply.writeNoException();reply.writeInt(accepted?1:0);}return true;
            }
            if(code!=AdbBootstrapWire.REQUEST)return super.onTransact(code,data,reply,flags);
            if(terminating)throw new IllegalStateException("ADB启动器正在等待独立进程退出");
            String encoded=data.readString();byte[] key=data.createByteArray(),certificate=data.createByteArray(),helper=data.createByteArray();IBinder listener=data.readStrongBinder();
            if(encoded==null||encoded.length()>8192||key==null||key.length>16384||certificate==null||certificate.length>16384||helper==null||helper.length>524288||listener==null)
                throw new IllegalArgumentException("ADB启动请求超出边界");
            final JSONObject request;
            try{request=new JSONObject(encoded);}catch(Exception invalid){throw new IllegalArgumentException("ADB启动请求无效");}
            String operation=request.optString("operationId");if(!operation.matches("[a-f0-9-]{36}")||!current.compareAndSet(null,operation))throw new IllegalStateException("已有ADB启动操作，不能并发配对");
            callback=listener;generation=request.optLong("generation");phase="starting";
            long duration=request.optString("action").equals("pair")?30000:45000;
            deadline=deadlines.schedule(()->{if(operation.equals(current.get()))killSelf();},duration,TimeUnit.MILLISECONDS);
            worker.execute(()->perform(request,key,certificate,helper,operation));
            if(reply!=null)reply.writeNoException();return true;
        }
    };

    @Override public void onCreate(){super.onCreate();if(!dedicatedProcess())throw new IllegalStateException("ADB启动器必须注册到独立应用进程");}
    @Override public IBinder onBind(Intent intent){return dedicatedProcess()?binder:null;}
    @Override public boolean onUnbind(Intent intent){stopSelf();return false;}
    @Override public void onDestroy(){
        // This service must have its own process; never invoke the unbounded library close path.
        killSelf();super.onDestroy();
    }
    private void perform(JSONObject request,byte[] key,byte[] certificate,byte[] helper,String operation){
        boolean keepWatchdog=false;
        try{
            if(Build.VERSION.SDK_INT<34)throw new IllegalStateException("unsupported_android");
            String action=request.optString("action");if(!action.equals("pair")&&!action.equals("connect"))throw new SecurityException("ADB启动器不接受任意服务或命令");
            int port=request.optInt(action.equals("pair")?"pairingPort":"connectPort",-1);
            String host=LocalAdbEndpoints.current().check(request.optString("host","127.0.0.1"),port);
            if(action.equals("connect"))LocalAdbEndpoints.distinctPorts(request.optInt("pairingPort",-1),port);
            configureIdentity(key,certificate);
            if(action.equals("pair")){
                phase="pairing";event(operation,"pairing");String code=LocalAdbEndpoints.pairingCode(request.optString("pairingCode"));
                boolean paired=manager.pair(host,port,code);request.remove("pairingCode");
                if(!paired)throw new IllegalStateException("pair_rejected");phase="paired";
            }else{
                phase="connecting";event(operation,"connecting");
                if(manager.isConnected())manager.disconnect();
                // Only this explicit pre-upload authorization rejection permits a
                // fresh pairing flow. A PAKE error or any later failure is unknown.
                try{if(!manager.connect(host,port)||!manager.isConnected())throw new IllegalStateException("connect_not_confirmed");}
                catch(AdbPairingRequiredException|AdbAuthenticationFailedException rejected){phase="needs_pairing";return;}
                phase="uploading";event(operation,"uploading");
                try(AdbStream sync=manager.openStream("sync:")){
                    AdbSyncUpload.send(sync.openInputStream(),sync.openOutputStream(),helper,System.currentTimeMillis()/1000);
                }
                JSONObject config=request.getJSONObject("bootstrap");
                if(config.optInt("ownerUid",-1)!=Process.myUid()||!config.optString("socketName").matches("pocketengine\\.display\\.[a-f0-9]{32}")||!config.optString("token").matches("[a-f0-9]{64}"))
                    throw new SecurityException("后台助手配置身份无效");
                // Fixed service and command, never an arbitrary model/user supplied shell.
                helperStream=manager.openStream(AdbSyncUpload.HELPER_COMMAND);OutputStream input=helperStream.openOutputStream();
                byte[] line=(config.toString()+"\n").getBytes(StandardCharsets.UTF_8);
                try{input.write(line);input.flush();}finally{Arrays.fill(line,(byte)0);}
                discardHelperOutput(helperStream);
                phase="helper_started";keepWatchdog=true;event(operation,"helper_started");
                // Only a main-process authenticated status handshake may acknowledge READY.
            }
        }catch(Exception unknown){phase=request.optString("action").equals("pair")?"pair_outcome_unknown":"bootstrap_outcome_unknown";}
        finally{
            request.remove("pairingCode");Arrays.fill(key,(byte)0);Arrays.fill(certificate,(byte)0);Arrays.fill(helper,(byte)0);
            if(!keepWatchdog){ScheduledFuture<?> timer=deadline;if(timer!=null)timer.cancel(false);current.compareAndSet(operation,null);event(operation,phase);}
        }
    }
    private void configureIdentity(byte[] key,byte[] certificate)throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(certificate);
        if(manager!=null){if(!java.security.MessageDigest.isEqual(identityDigest,digest))throw new SecurityException("identity_rotation_requires_process_restart");return;}
        if(!Conscrypt.isAvailable())throw new IllegalStateException("Conscrypt unavailable");
        Security.insertProviderAt(Conscrypt.newProvider(),1); // This child process only.
        PrivateKey privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
        Certificate cert=CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(certificate));
        manager=new Manager(privateKey,cert);manager.setApi(Build.VERSION.SDK_INT);manager.setTimeout(10,TimeUnit.SECONDS);manager.setThrowOnUnauthorised(true);identityDigest=digest;
    }
    private void discardHelperOutput(AdbStream stream){
        Thread drain=new Thread(()->{try{InputStream output=stream.openInputStream();byte[] buffer=new byte[4096];int total=0,n;while((n=output.read(buffer))>0){total+=n;if(total>65536)break;}Arrays.fill(buffer,(byte)0);}catch(Exception ignored){}},"adb-helper-output");drain.setDaemon(true);drain.start();
    }
    private void event(String operation,String value){
        try{JSONObject event=new JSONObject().put("operationId",operation).put("generation",generation).put("phase",value);
            Parcel data=Parcel.obtain();try{data.writeInterfaceToken(AdbBootstrapWire.CALLBACK);data.writeString(event.toString());IBinder listener=callback;if(listener!=null)listener.transact(AdbBootstrapWire.EVENT,data,null,IBinder.FLAG_ONEWAY);}finally{data.recycle();}
        }catch(Exception ignored){ /* A failed callback never removes the hard process deadline. */ }
    }
    private boolean dedicatedProcess(){return (getPackageName()+":adb_bootstrap").equals(android.app.Application.getProcessName());}
    private void killSelf(){if(dedicatedProcess())Process.killProcess(Process.myPid());}
    private static final class Manager extends AbsAdbConnectionManager {
        private final PrivateKey key;private final Certificate certificate;
        Manager(PrivateKey key,Certificate certificate){this.key=key;this.certificate=certificate;}
        @Override protected PrivateKey getPrivateKey(){return key;}
        @Override protected Certificate getCertificate(){return certificate;}
        @Override protected String getDeviceName(){return "PocketWorld@Android";}
    }
}
