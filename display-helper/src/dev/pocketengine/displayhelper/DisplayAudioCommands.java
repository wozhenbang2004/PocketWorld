package dev.pocketengine.displayhelper;

import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Fixed appops argv only, used by the original host's private restoration ledger. */
final class DisplayAudioCommands {
    static JSONObject handle(String command,JSONObject request)throws Exception {
        String pkg=request.getString("packageName");int user=request.getInt("userId");if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||user<0||user>21474)throw new SecurityException("audio_identity_invalid");
        if(command.equals("audio.get")){String value=run(new String[]{"/system/bin/cmd","appops","get","--user",String.valueOf(user),pkg,"PLAY_AUDIO"});java.util.regex.Matcher result=java.util.regex.Pattern.compile("PLAY_AUDIO:\\s*(allow|ignore|deny|default|foreground)\\b").matcher(value);String mode=result.find()?result.group(1):value.contains("No operations.")?"default":null;return new JSONObject().put("ok",mode!=null).put("mode",mode==null?JSONObject.NULL:mode);}
        if(!command.equals("audio.set"))throw new SecurityException("audio_command_invalid");String mode=request.getString("mode");if(!Set.of("allow","ignore","deny","default","foreground").contains(mode))throw new SecurityException("audio_mode_invalid");run(new String[]{"/system/bin/cmd","appops","set","--user",String.valueOf(user),pkg,"PLAY_AUDIO",mode});return new JSONObject().put("ok",true).put("accepted",true).put("verified",false);
    }
    private static String run(String[] argv)throws Exception {
        Process process=new ProcessBuilder(argv).redirectErrorStream(true).start();java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();java.util.concurrent.Future<String> output=io.submit(()->{ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[1024];try(java.io.InputStream input=process.getInputStream()){int n;while((n=input.read(buffer))>=0){if(bytes.size()+n>8192)throw new SecurityException("audio_output_limit");bytes.write(buffer,0,n);}}return new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);});
        try{String text=output.get(2,TimeUnit.SECONDS);if(!process.waitFor(200,TimeUnit.MILLISECONDS)||process.exitValue()!=0)throw new SecurityException("audio_platform_refused");return text;}finally{process.destroy();output.cancel(true);io.shutdownNow();}
    }
}
