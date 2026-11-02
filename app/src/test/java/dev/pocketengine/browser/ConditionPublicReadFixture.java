package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only response bytes through the ORIGINAL bounded parser/hash routine; no network/DNS claim. */
public final class ConditionPublicReadFixture {
    private ConditionPublicReadFixture(){}
    public static PublicHttpReader.Result consume(JSONObject args,RunControl original,BrowserTaskSpace.Gate gate,int status,String text)throws Exception{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);Buffer bytes=new Buffer().write(data);
        ResponseBody body=new ResponseBody(){public MediaType contentType(){return MediaType.parse("text/plain; charset=utf-8");}public long contentLength(){return data.length;}public BufferedSource source(){return bytes;}};
        try(Response response=new Response.Builder().request(new Request.Builder().url(args.getString("url")).build()).protocol(Protocol.HTTP_1_1).code(status).message("controlled native fixture").header("Content-Type","text/plain; charset=utf-8").body(body).build()){
            return PublicHttpReader.consume(response,args,PublicHttpReader.facts(response,args,new JSONArray()),new PublicHttpReader.Budget(original),gate);
        }
    }
}
