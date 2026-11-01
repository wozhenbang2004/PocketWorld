package dev.pocketengine.displayhelper;

import android.os.Handler;
import android.os.Looper;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** ADB entry point. Shizuku uses the same DisplayServer from its Binder UserService. */
public final class Main {
    public static void main(String[] args)throws Exception {
        int uid=android.os.Process.myUid();
        if(uid!=0&&uid!=2000)throw new SecurityException("An authorized shell is required");
        StringBuilder line=new StringBuilder();InputStreamReader input=new InputStreamReader(System.in,StandardCharsets.UTF_8);
        int c;while((c=input.read())!=-1&&c!='\n'){if(line.length()>=4096)throw new SecurityException("Bootstrap too large");line.append((char)c);}
        Looper.prepareMainLooper();
        DisplayServer server=new DisplayServer(DisplayServer.shellContext(),new Handler(Looper.getMainLooper()),new JSONObject(line.toString()));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Looper.loop();
    }
}
