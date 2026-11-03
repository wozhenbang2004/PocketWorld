package dev.pocketengine.media;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import static org.junit.Assert.*;

/** Test-only application-interceptor seam forwarding actual GET bytes to one owned loopback.
 * It deliberately does not test DNS, actual socket admission or TLS. URL/auth/redirect policy
 * above the socket still runs unchanged. The production client is restored after each test. */
public final class MediaResultLoopbackFixture implements AutoCloseable {
    public final List<String> requested=new ArrayList<>(),auth=new ArrayList<>();
    private final OkHttpClient original;private final Object unsafe,staticBase;private final long staticOffset;
    public MediaResultLoopbackFixture(String expected,String loopbackBase)throws Exception{
        Field field=MediaResultDownload.class.getDeclaredField("BASE");field.setAccessible(true);original=(OkHttpClient)field.get(null);
        Class<?> type=Class.forName("sun.misc.Unsafe");Field singleton=type.getDeclaredField("theUnsafe");singleton.setAccessible(true);unsafe=singleton.get(null);
        staticBase=type.getMethod("staticFieldBase",Field.class).invoke(unsafe,field);staticOffset=(Long)type.getMethod("staticFieldOffset",Field.class).invoke(unsafe,field);
        OkHttpClient fixture=original.newBuilder().addInterceptor(chain->{
            String raw=chain.request().url().toString();requested.add(raw);auth.add(chain.request().header("Authorization"));assertEquals(expected,raw);assertEquals("GET",chain.request().method());assertNull(chain.request().body());
            URI source=URI.create(raw);HttpURLConnection connection=(HttpURLConnection)URI.create(loopbackBase+source.getRawPath()+(source.getRawQuery()==null?"":"?"+source.getRawQuery())).toURL().openConnection();connection.setRequestMethod("GET");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
            if(chain.request().header("Authorization")!=null)connection.setRequestProperty("Authorization",chain.request().header("Authorization"));
            try{int status=connection.getResponseCode();byte[] bytes=status==200?connection.getInputStream().readAllBytes():new byte[0];Response.Builder reply=new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("owned loopback fixture").body(ResponseBody.create(bytes,MediaType.get("application/octet-stream")));String location=connection.getHeaderField("Location");if(location!=null)reply.header("Location",location);return reply.build();}finally{connection.disconnect();}
        }).build();
        type.getMethod("putObjectVolatile",Object.class,long.class,Object.class).invoke(unsafe,staticBase,staticOffset,fixture);
    }
    @Override public void close()throws Exception{unsafe.getClass().getMethod("putObjectVolatile",Object.class,long.class,Object.class).invoke(unsafe,staticBase,staticOffset,original);}
}
