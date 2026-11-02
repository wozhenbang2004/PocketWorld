package dev.pocketengine.testutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real loopback HTTP fixture for Android unit-test compilation; bounded Content-Length requests only. */
public final class HttpServer {
    public interface Handler {void handle(Exchange exchange)throws Exception;}
    private final ServerSocket server;
    private final Map<String,Handler> contexts=new ConcurrentHashMap<>();
    private final java.util.Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"loopback-http-fixture");t.setDaemon(true);return t;});
    private volatile java.util.concurrent.Executor exchangeExecutor=workers;
    private HttpServer(InetSocketAddress address,int backlog)throws java.io.IOException{server=new ServerSocket();server.bind(address,backlog);}
    public static HttpServer create(InetSocketAddress address,int backlog)throws java.io.IOException{return new HttpServer(address,backlog);}
    public InetSocketAddress getAddress(){return (InetSocketAddress)server.getLocalSocketAddress();}
    public void createContext(String path,Handler handler){contexts.put(path,handler);}
    public void setExecutor(java.util.concurrent.Executor executor){exchangeExecutor=executor==null?workers:executor;}
    public void start(){workers.execute(()->{while(!server.isClosed())try{Socket client=server.accept();clients.add(client);exchangeExecutor.execute(()->serve(client));}catch(Exception closed){if(!server.isClosed())throw new IllegalStateException(closed);}});}
    private void serve(Socket socket){try(Socket client=socket){
        client.setSoTimeout(5000);InputStream in=client.getInputStream();String[] request=line(in).split(" ",3);if(request.length!=3)throw new java.io.IOException("Invalid fixture request");
        Headers headers=new Headers();int total=0;String line;
        while(!(line=line(in)).isEmpty()){total+=line.length();if(total>65536)throw new java.io.IOException("Fixture headers too large");int at=line.indexOf(':');if(at<=0)throw new java.io.IOException("Invalid fixture header");headers.add(line.substring(0,at),line.substring(at+1).trim());}
        int length=headers.getFirst("Content-Length")==null?0:Integer.parseInt(headers.getFirst("Content-Length"));if(length<0||length>4*1024*1024)throw new java.io.IOException("Fixture request too large");
        byte[] body=new byte[length];int used=0;while(used<length){int n=in.read(body,used,length-used);if(n<0)throw new java.io.IOException("Incomplete fixture request");used+=n;}
        Exchange exchange=new Exchange(client,request[0],URI.create(request[1]),headers,body);Handler handler=null;int best=-1;
        for(Map.Entry<String,Handler> entry:contexts.entrySet())if(exchange.uri.getPath().startsWith(entry.getKey())&&entry.getKey().length()>best){handler=entry.getValue();best=entry.getKey().length();}
        if(handler==null){exchange.sendResponseHeaders(404,0);exchange.close();}else handler.handle(exchange);
    }catch(Exception ignored){/* Client assertions expose missing/invalid responses; cancellation may close either side. */}finally{clients.remove(socket);}}
    public void stop(int ignored){try{server.close();}catch(Exception absent){}for(Socket client:clients)try{client.close();}catch(Exception absent){}workers.shutdownNow();}
    private static String line(InputStream in)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1;while(bytes.size()<65536){int value=in.read();if(value<0)throw new java.io.IOException("Unexpected EOF");if(previous=='\r'&&value=='\n'){byte[] data=bytes.toByteArray();return new String(data,0,data.length-1,StandardCharsets.US_ASCII);}bytes.write(value);previous=value;}throw new java.io.IOException("Fixture line too long");}
    public static final class Headers extends TreeMap<String,List<String>>{
        Headers(){super(String.CASE_INSENSITIVE_ORDER);}
        public void add(String name,String value){computeIfAbsent(name,key->new ArrayList<>()).add(value);}
        public void set(String name,String value){put(name,new ArrayList<>(java.util.List.of(value)));}
        public String getFirst(String name){List<String> values=get(name);return values==null||values.isEmpty()?null:values.get(0);}
    }
    public static final class Exchange {
        private final Socket socket;private final String method;private final URI uri;private final Headers request,response=new Headers();private final InputStream body;
        private OutputStream output;
        private final OutputStream responseBody=new OutputStream(){
            @Override public void write(int value)throws java.io.IOException{requireOutput().write(value);}
            @Override public void write(byte[] value,int offset,int length)throws java.io.IOException{requireOutput().write(value,offset,length);}
            @Override public void flush()throws java.io.IOException{requireOutput().flush();}
            @Override public void close()throws java.io.IOException{if(output!=null)output.close();else socket.close();}
        };
        Exchange(Socket socket,String method,URI uri,Headers request,byte[] body){this.socket=socket;this.method=method;this.uri=uri;this.request=request;this.body=new ByteArrayInputStream(body);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}
        public String getRequestMethod(){return method;}public URI getRequestURI(){return uri;}
        public InputStream getRequestBody(){return body;}public OutputStream getResponseBody(){return responseBody;}
        private OutputStream requireOutput()throws java.io.IOException{if(output==null)throw new java.io.IOException("Response headers not sent");return output;}
        public void sendResponseHeaders(int status,long length)throws java.io.IOException{
            if(output!=null)throw new java.io.IOException("Response headers already sent");
            boolean chunked=length==0&&status!=204&&status!=304;OutputStream raw=socket.getOutputStream();
            StringBuilder out=new StringBuilder("HTTP/1.1 ").append(status).append(" Fixture\r\n");
            if(chunked)out.append("Transfer-Encoding: chunked\r\n");else out.append("Content-Length: ").append(Math.max(0,length)).append("\r\n");
            out.append("Connection: close\r\n");for(Map.Entry<String,List<String>> header:response.entrySet())for(String value:header.getValue())out.append(header.getKey()).append(": ").append(value).append("\r\n");out.append("\r\n");raw.write(out.toString().getBytes(StandardCharsets.US_ASCII));
            output=chunked?new ChunkedOutput(raw):raw;
        }
        public void close(){try{responseBody.close();}catch(Exception absent){}finally{try{socket.close();}catch(Exception absent){}}}
    }
    private static final class ChunkedOutput extends OutputStream {
        private final OutputStream raw;private boolean closed;
        ChunkedOutput(OutputStream raw){this.raw=raw;}
        @Override public void write(int value)throws java.io.IOException{write(new byte[]{(byte)value},0,1);}
        @Override public synchronized void write(byte[] value,int offset,int length)throws java.io.IOException{
            if(closed)throw new java.io.IOException("Fixture response closed");if(length==0)return;
            raw.write((Integer.toHexString(length)+"\r\n").getBytes(StandardCharsets.US_ASCII));raw.write(value,offset,length);raw.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        @Override public synchronized void flush()throws java.io.IOException{raw.flush();}
        @Override public synchronized void close()throws java.io.IOException{if(closed)return;closed=true;raw.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));raw.flush();raw.close();}
    }
}
