package dev.pocketengine.features.local.project;

import dev.pocketengine.browser.PublicHttpReader;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.runtime.ProjectRuntime;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** One native Git import's finite CONNECT transport. It does not decrypt TLS,
 * persist listeners, accept model targets or grant general proxy/network use.
 * This bounds this fixed Git transport, not other same-UID process capabilities. */
public final class ProjectGitImportTunnel {
    public static final long MAX_TRANSFER_BYTES=256L*1024*1024,MAX_MILLIS=10*60*1000;
    private static final int MAX_CONNECTIONS=32,MAX_ACTIVE=4;
    private ProjectGitImportTunnel(){}
    public static final class Target {
        public final String url,host,address;public final int port;private final InetAddress fixed;
        private Target(String url,String host,int port,InetAddress fixed){this.url=url;this.host=host;this.port=port;this.fixed=fixed;address=fixed.getHostAddress();}
        public JSONObject metadata()throws Exception{return new JSONObject().put("url",url).put("host",host).put("port",port).put("fixedPublicAddress",address).put("redirectsAllowed",false).put("tlsVerificationRequired",true).put("networkScope","one_reviewed_https_origin_and_pinned_ip");}
    }
    /** Explicit native URL review only; no token or userinfo is accepted in it.
     * All answers must be public, then one exact IP is retained until expiry. */
    public static Target review(String supplied,RunControl original)throws Exception {
        original.checkpoint();if(supplied==null||supplied.length()>2048||!supplied.equals(supplied.trim())||supplied.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("请填写不含账号或令牌的HTTPS仓库地址");URI uri=new URI(supplied);String host=uri.getHost();int port=uri.getPort()==-1?443:uri.getPort();
        if(!"https".equals(uri.getScheme())||host==null||!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")||host.endsWith(".")||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||port<1||port>65535||uri.getRawPath()==null||uri.getRawPath().isEmpty()||uri.getRawPath().contains("\\"))throw new SecurityException("仅支持明确的HTTPS普通仓库地址，不接受URL凭据、参数、片段或替代协议");
        host=host.toLowerCase(Locale.ROOT);InetAddress[] answers=InetAddress.getAllByName(host);original.checkpoint();if(answers.length==0)throw new SecurityException("原仓库域名没有可核对的公有地址");for(InetAddress answer:answers)if(!PublicHttpReader.publicAddress(answer))throw new SecurityException("仓库解析含非公有地址，本入口不访问本机或内网服务");Arrays.sort(answers,Comparator.comparing(InetAddress::getHostAddress));String normalized=new URI("https://"+host+(port==443?"":":"+port)+uri.getRawPath()).toASCIIString();return new Target(normalized,host,port,answers[0]);
    }
    public static Lease open(Target selected,RunControl original,ProjectRuntime.LiveGate current)throws Exception {return open(selected,original,current,null,null);}
    /** Optional native owner secret is copied only into this finite job's RAM.
     * It is delivered once to its fixed helper, never to a URL/env/log/config. */
    public static Lease open(Target selected,RunControl original,ProjectRuntime.LiveGate current,char[] username,char[] token)throws Exception {return new Lease(selected,original,current,(socket,address,port)->socket.connect(new InetSocketAddress(address,port),15000),username,token);}
    private static String nonce(){byte[] value=new byte[32];new java.security.SecureRandom().nextBytes(value);StringBuilder out=new StringBuilder();for(byte part:value)out.append(String.format(Locale.ROOT,"%02x",part&255));return out.toString();}
    private static void requireCredentials(char[] username,char[] token)throws Exception {
        if(username==null&&token==null)return;if(username==null||token==null||username.length==0||username.length>256||token.length==0||token.length>4096)throw new SecurityException("一次性Git凭据范围无效");
        for(char value:username)if(value<32||value==127||value==':')throw new SecurityException("Git用户名不能包含控制符或冒号");for(char value:token)if(value<32||value==127)throw new SecurityException("Git令牌不能包含控制符");
        java.nio.charset.CharsetEncoder encoder=StandardCharsets.UTF_8.newEncoder();if(encoder.encode(java.nio.CharBuffer.wrap(username)).remaining()>256||encoder.encode(java.nio.CharBuffer.wrap(token)).remaining()>4096)throw new SecurityException("一次性Git凭据字节超限");
    }
    interface Connect {void open(Socket originalOwnedSocket,InetAddress address,int port)throws Exception;}
    public static final class Lease implements AutoCloseable {
        private final Target target;private final RunControl original;private final ProjectRuntime.LiveGate current;private final Connect connect;private final ServerSocket listener;private final long expires=System.nanoTime()+MAX_MILLIS*1000000L;
        private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();private final List<Thread> threads=Collections.synchronizedList(new ArrayList<>());private final AtomicInteger connections=new AtomicInteger(),active=new AtomicInteger();private final AtomicLong transferred=new AtomicLong();private final AtomicBoolean stopped=new AtomicBoolean(),credentialTaken=new AtomicBoolean();private final Runnable cancellation=this::signalStop;private final String credentialNonce=nonce();private char[] username,token;
        Lease(Target target,RunControl original,ProjectRuntime.LiveGate current,Connect connect,char[] username,char[] token)throws Exception {
            requireCredentials(username,token);this.username=username==null?null:username.clone();this.token=token==null?null:token.clone();this.target=Objects.requireNonNull(target);this.original=Objects.requireNonNull(original);this.current=Objects.requireNonNull(current);this.connect=Objects.requireNonNull(connect);try{if(!PublicHttpReader.publicAddress(target.fixed))throw new SecurityException("原Git传输只接受已核公有IP");check();}catch(Exception failed){clearCredentials();throw failed;}listener=new ServerSocket();try{listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0),4);listener.setSoTimeout(500);original.onCancel(cancellation);Thread accept=new Thread(this::accept,"pw-git-import-connect");threads.add(accept);accept.start();}catch(Exception failed){signalStop();original.removeCancelAction(cancellation);throw failed;}
        }
        public String proxyUrl(){return "http://127.0.0.1:"+listener.getLocalPort();}
        public JSONObject helperScope()throws Exception{check();synchronized(this){return new JSONObject().put("nativeProxy",proxyUrl()).put("credentialProvided",token!=null).put("nativeCredentialNonce",token==null?"":credentialNonce);}}
        private void deliverCredential(Socket client,String first)throws Exception {
            check();String expected="PW-NATIVE-CREDENTIAL "+credentialNonce;byte[] response=null;
            try{
                synchronized(this){if(!java.security.MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII))||token==null||!credentialTaken.compareAndSet(false,true))throw new SecurityException("原Git凭据通道未匹配或已使用，不能重放");
                    try{response=new JSONObject().put("username",new String(username)).put("token",new String(token)).toString().getBytes(StandardCharsets.UTF_8);if(response.length>16384)throw new SecurityException("原Git凭据超出一次传输范围");}finally{clearCredentials();}}
                check();client.getOutputStream().write(response);client.getOutputStream().flush();
            }finally{if(response!=null)Arrays.fill(response,(byte)0);}
        }
        private synchronized void clearCredentials(){if(username!=null)Arrays.fill(username,'\0');if(token!=null)Arrays.fill(token,'\0');username=null;token=null;}
        public JSONObject metadata()throws Exception{return new JSONObject().put("target",target.metadata()).put("connections",connections.get()).put("transferredBytes",transferred.get()).put("tlsInspected",false).put("persistentListener",false).put("credentialDelivery","one_use_original_native_memory_not_env_or_argv").put("credentialConsumed",credentialTaken.get()).put("maxMillis",MAX_MILLIS).put("maxTransferBytes",MAX_TRANSFER_BYTES);}
        private void check()throws Exception {original.check();current.check();if(original.isPaused()||stopped.get()||System.nanoTime()>=expires)throw new InterruptedIOException("原Git单目标传输已停止、到期或授权变化");}
        private void accept(){try{while(!stopped.get()){check();try{Socket socket=listener.accept();sockets.add(socket);if(connections.incrementAndGet()>MAX_CONNECTIONS){closeSocket(socket);continue;}if(active.incrementAndGet()>MAX_ACTIVE){active.decrementAndGet();closeSocket(socket);continue;}Thread worker=new Thread(()->serve(socket),"pw-git-import-connection");threads.add(worker);try{worker.start();}catch(RuntimeException|Error failed){active.decrementAndGet();closeSocket(socket);throw failed;}}catch(SocketTimeoutException idle){}}}catch(Exception failure){signalStop();}}
        private void serve(Socket client){Socket remote=null;Thread outgoing=null;try{client.setSoTimeout(1000);String header=request(client.getInputStream());String authority=target.host+":"+target.port;String first=header.substring(0,header.indexOf("\r\n"));if(first.startsWith("PW-NATIVE-CREDENTIAL ")){deliverCredential(client,first);return;}if(!first.equals("CONNECT "+authority+" HTTP/1.1")&&!first.equals("CONNECT "+authority+" HTTP/1.0"))throw new SecurityException("非原目标CONNECT已拒绝");for(String line:header.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("proxy-authorization:"))throw new SecurityException("此原传输不接收代理凭据");check();remote=new Socket();sockets.add(remote);check();connect.open(remote,target.fixed,target.port);check();remote.setSoTimeout(1000);client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();final Socket upstream=remote;outgoing=new Thread(()->{try{pump(client,upstream);}catch(Exception failed){closeSocket(client);closeSocket(upstream);}},"pw-git-import-tls-out");threads.add(outgoing);outgoing.start();pump(remote,client);}
            catch(Exception failed){/* Never log the CONNECT headers, TLS data, URL credentials or provider response. */}
            finally{closeSocket(client);if(remote!=null)closeSocket(remote);if(outgoing!=null)try{outgoing.join(2000);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}active.decrementAndGet();}}
        private String request(InputStream input)throws Exception {ByteArrayOutputStream raw=new ByteArrayOutputStream();int state=0;while(raw.size()<4096){check();int value;try{value=input.read();}catch(SocketTimeoutException idle){continue;}if(value<0)throw new EOFException("不完整CONNECT");if(value>127||value<32&&value!='\r'&&value!='\n'&&value!='\t')throw new SecurityException("CONNECT头格式不支持");raw.write(value);state=state==0&&value=='\r'?1:state==1&&value=='\n'?2:state==2&&value=='\r'?3:state==3&&value=='\n'?4:value=='\r'?1:0;if(state==4)return raw.toString(StandardCharsets.US_ASCII.name());}throw new SecurityException("CONNECT头超过4KiB");}
        private void pump(Socket from,Socket to)throws Exception {byte[] buffer=new byte[16384];InputStream input=from.getInputStream();OutputStream output=to.getOutputStream();while(true){check();int count;try{count=input.read(buffer);}catch(SocketTimeoutException idle){continue;}if(count<0){try{to.shutdownOutput();}catch(IOException ignored){}return;}if(count==0)continue;if(transferred.addAndGet(count)>MAX_TRANSFER_BYTES){signalStop();throw new IOException("原Git传输超过256MiB");}output.write(buffer,0,count);output.flush();}}
        private void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}sockets.remove(socket);}
        private void signalStop(){clearCredentials();if(stopped.compareAndSet(false,true)){try{listener.close();}catch(Exception ignored){}for(Socket socket:sockets)closeSocket(socket);}}
        @Override public void close()throws Exception {
            signalStop();original.removeCancelAction(cancellation);long deadline=System.nanoTime()+5000000000L;int examined=0;
            // The acceptor is registered first. Join it before taking subsequent
            // snapshots, then include any pump registered by a closing worker.
            while(true){List<Thread> owned;synchronized(threads){owned=new ArrayList<>(threads);}for(int i=examined;i<owned.size();i++){Thread thread=owned.get(i);if(thread!=Thread.currentThread()){long remaining=deadline-System.nanoTime();if(remaining>0)thread.join(Math.max(1,remaining/1000000));}}examined=owned.size();synchronized(threads){if(examined==threads.size())break;}if(System.nanoTime()>=deadline)throw new IOException("原Git单目标传输线程收尾未确认");}
            synchronized(threads){for(Thread thread:threads)if(thread.isAlive()&&thread!=Thread.currentThread())throw new IOException("原Git单目标传输资源退出未确认");}if(!listener.isClosed()||!sockets.isEmpty()||active.get()!=0)throw new IOException("原Git单目标传输连接关闭未确认");
        }
    }
}
