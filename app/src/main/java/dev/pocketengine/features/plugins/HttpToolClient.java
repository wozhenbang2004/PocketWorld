package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class HttpToolClient {
    private HttpToolClient() { }
    public static JSONObject invoke(String endpoint,String token,String tool,JSONObject arguments)throws Exception {return invoke(endpoint,token,tool,arguments,java.util.UUID.randomUUID().toString());}
    public static JSONObject invoke(String endpoint,String token,String tool,JSONObject arguments,String operationId)throws Exception {
        return invoke(endpoint,token,tool,arguments,operationId,new RunControl());
    }
    public static JSONObject invoke(String endpoint,String token,String tool,JSONObject arguments,String operationId,RunControl control)throws Exception {
        control.check();PluginRules.validateEndpoint(endpoint);HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
        Runnable disconnect=()->{Thread worker=new Thread(connection::disconnect,"pocket-plugin-disconnect");worker.setDaemon(true);worker.start();};control.onCancel(disconnect);
        Thread deadline=new Thread(()->{try{Thread.sleep(20_000);connection.disconnect();}catch(InterruptedException finished){}},"pocket-plugin-deadline");deadline.setDaemon(true);deadline.start();
        try {
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(15000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            if(!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);
            byte[] body=new JSONObject().put("operationId",operationId).put("tool",tool).put("arguments",arguments).toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);
            control.check();try(OutputStream out=connection.getOutputStream()){control.check();out.write(body);}
            int status=connection.getResponseCode();if(status<200||status>=300)throw new IllegalStateException("HTTP工具返回状态"+status+"；未自动重试");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1){control.check();if(bytes.size()+count>262144)throw new IllegalStateException("工具回复超过256KiB");bytes.write(buffer,0,count);}}
            control.check();return (JSONObject)McpData.scrub(new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)),token,0);
        } catch(Exception failure) {throw new IllegalStateException("HTTP工具请求未取得有效结果，请先检查服务端结果再决定是否重试",failure);}
        finally {deadline.interrupt();control.removeCancelAction(disconnect);connection.disconnect();}
    }
}
