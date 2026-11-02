package dev.pocketengine.displayhelper;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import dev.pocketengine.phone.display.DisplayWire;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Same authenticated helper transport for ADB bootstrap and Shizuku UserService. */
public final class DisplayServer implements AutoCloseable {
    private final DisplayEngine engine;
    private final LocalServerSocket server;
    private final Handler handler;
    private final ThreadPoolExecutor requests=new ThreadPoolExecutor(2,3,20,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
    private volatile boolean closed;
    private final Runnable reap=new Runnable(){@Override public void run(){if(!closed){engine.tick();handler.postDelayed(this,1000);}}};

    public DisplayServer(Context context,Handler handler,JSONObject setup)throws Exception {
        int identity=android.os.Process.myUid();
        if(identity!=0&&identity!=2000)throw new SecurityException("An authorized shell is required");
        String socket=setup.getString("socketName"),token=setup.getString("token");int owner=setup.getInt("ownerUid");
        if(!socket.matches("pocketengine\\.display\\.[a-f0-9]{32}")||!token.matches("[a-f0-9]{64}")||owner<10000||owner>=100000)
            throw new SecurityException("Invalid bootstrap");
        this.handler=handler;engine=new DisplayEngine(context,handler,owner);server=new LocalServerSocket(socket);
        Thread accept=new Thread(()->{
            while(!closed)try{
                LocalSocket client=server.accept();
                try{requests.execute(()->serve(client,owner,token));}
                catch(RuntimeException busy){try{client.close();}catch(Exception ignored){}}
            }catch(Exception stopped){break;}
        },"pocket-display-ipc");accept.setDaemon(true);accept.start();handler.post(reap);
    }

    /** Privileged shell attribution, without depending on normal app-process lifecycle APIs. */
    public static Context shellContext()throws Exception {
        Class<?> type=Class.forName("android.app.ActivityThread");
        Object thread=type.getDeclaredMethod("currentActivityThread").invoke(null);
        if(thread==null)thread=type.getDeclaredMethod("systemMain").invoke(null);
        Context system=(Context)type.getDeclaredMethod("getSystemContext").invoke(thread);
        return system.createPackageContext("com.android.shell",0);
    }

    private void serve(LocalSocket client,int owner,String secret){
        try(LocalSocket connection=client){
            connection.setSoTimeout(8000);
            if(closed||connection.getPeerCredentials().getUid()!=owner)return;
            JSONObject request=new JSONObject(DisplayWire.read(new DataInputStream(connection.getInputStream()),32*1024));
            if(request.optInt("protocol")!=1||!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),request.optString("token").getBytes(StandardCharsets.UTF_8)))return;
            JSONObject reply;
            try{reply=engine.handle(request);}
            catch(Exception failure){
                DisplayEngine.Failure known=failure instanceof DisplayEngine.Failure?(DisplayEngine.Failure)failure:null;
                reply=new JSONObject().put("ok",false).put("error",known==null?"platform_unavailable":known.code)
                        .put("message",known==null?"系统不支持此后台屏操作，未回落主屏。":known.getMessage())
                        .put("accepted",known!=null&&known.dispatched?JSONObject.NULL:false).put("verified",false);
            }
            reply.put("requestId",request.optString("requestId"));
            DisplayWire.write(new DataOutputStream(connection.getOutputStream()),reply.toString(),8*1024*1024);
        }catch(Exception ignored){ /* Never log a request, token, frame or exception payload. */ }
    }

    @Override public void close(){
        if(closed)return;closed=true;handler.removeCallbacks(reap);
        try{server.close();}catch(Exception ignored){}
        requests.shutdownNow();engine.close();
    }
}
