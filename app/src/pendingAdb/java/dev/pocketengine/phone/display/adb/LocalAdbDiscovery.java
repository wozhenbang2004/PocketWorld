package dev.pocketengine.phone.display.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** TLS-only NSD metadata. Discovery never grants pairing, starts a connection or trusts another LAN device. */
public final class LocalAdbDiscovery implements AutoCloseable {
    private final NsdManager manager;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Consumer<JSONObject> changed;
    private final Map<String,NsdServiceInfo> seen=new LinkedHashMap<>();
    private final Map<String,JSONObject> endpoints=new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();
    private final List<NsdManager.DiscoveryListener> listeners=new ArrayList<>();
    private final Set<String> activeKinds=new HashSet<>();
    private volatile JSONObject state=new JSONObject();
    private boolean resolving,closed,started;
    private int epoch;
    private long resolveGeneration;
    private NsdManager.ResolveListener activeResolve;
    private String activeResolveKey;
    private Runnable resolveDeadline,scanDeadline;
    private String errorCode="";
    private boolean timedOut;
    private String message="尚未发现无线调试服务；请在系统开发者选项开启无线调试。";
    public LocalAdbDiscovery(Context context,Consumer<JSONObject> changed){manager=context.getSystemService(NsdManager.class);this.changed=changed;
        try{state=new JSONObject().put("discovering",false).put("pairing",new JSONArray()).put("connect",new JSONArray()).put("message",message).put("errorCode","").put("timedOut",false);}catch(Exception impossible){}
    }
    public JSONObject snapshot(){try{return new JSONObject(state.toString());}catch(Exception impossible){return new JSONObject();}}
    public void start(){main.post(this::startOnMain);}
    private void startOnMain(){
        if(closed)return;if(started){refreshConnectOnMain();publish();return;}
        stopOnMain();errorCode="";timedOut=false;
        if(manager==null){errorCode="nsd_unavailable";message="当前系统没有可用NSD服务，请手动读取本机配对与连接端口。";publish();return;}
        started=true;final int current=++epoch;activeKinds.add(LocalAdbEndpoints.PAIRING);activeKinds.add(LocalAdbEndpoints.CONNECT);
        scanDeadline=()->{if(valid(current)){boolean empty=endpoints.isEmpty();stopOnMain();timedOut=empty;errorCode=empty?"discovery_timeout":"";message=empty?"本次本机服务发现超时，可重新发现或手动输入系统端口。":"本次本机服务发现已结束，端口变化后请重新发现。";publish();}};
        main.postDelayed(scanDeadline,20000);
        for(String type:new String[]{LocalAdbEndpoints.PAIRING,LocalAdbEndpoints.CONNECT}){
            NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
                @Override public void onDiscoveryStarted(String service){main.post(()->{if(valid(current)){if(errorCode.isEmpty())message="正在查找本机无线调试TLS服务，配对与连接端口分别显示。";publish();}});}
                @Override public void onStartDiscoveryFailed(String service,int error){main.post(()->{if(valid(current))discoveryFailed(type);});}
                @Override public void onStopDiscoveryFailed(String service,int error){}
                @Override public void onDiscoveryStopped(String service){main.post(()->{if(valid(current)){activeKinds.remove(type);if(activeKinds.isEmpty())stopOnMain();publish();}});}
                @Override public void onServiceFound(NsdServiceInfo info){main.post(()->{
                    if(!valid(current))return;
                    try{LocalAdbEndpoints.serviceType(info.getServiceType());String key=key(info);if(seen.size()>=32&&!seen.containsKey(key))return;seen.put(key,info);enqueue(info);}
                    catch(Exception ignored){}
                });}
                @Override public void onServiceLost(NsdServiceInfo info){main.post(()->{if(valid(current)){
                    String key=key(info);seen.remove(key);endpoints.remove(key);queue.removeIf(item->key(item).equals(key));
                    // A new pairing window can reuse the service name with a different port.
                    // Its old resolution must not publish into the rediscovered generation.
                    if(key.equals(activeResolveKey)){resolveGeneration++;stopResolution(activeResolve);finishResolution();resolveNext();}
                    publish();
                }});}
            };listeners.add(listener);
            try{manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);}catch(RuntimeException unavailable){discoveryFailed(type);}
        }publish();
    }
    private void discoveryFailed(String type){activeKinds.remove(type);errorCode="discovery_start_failed";message="系统NSD发现不可用，可手动输入本机端口；未尝试其他设备。";if(activeKinds.isEmpty())stopOnMain();publish();}
    /** A connect advertisement may predate pairing; explicitly resolve it again after PAKE succeeds. */
    public void refreshConnect(){main.post(()->{if(!closed){if(started)refreshConnectOnMain();else startOnMain();}});}
    private void refreshConnectOnMain(){for(NsdServiceInfo info:new ArrayList<>(seen.values()))try{if(LocalAdbEndpoints.CONNECT.equals(LocalAdbEndpoints.serviceType(info.getServiceType())))enqueue(info);}catch(Exception ignored){}}
    private boolean valid(int expected){return !closed&&started&&epoch==expected;}
    private void enqueue(NsdServiceInfo info){if(queue.size()<32&&queue.stream().noneMatch(item->key(item).equals(key(info)))){queue.add(info);resolveNext();}}
    private void resolveNext(){
        if(resolving||queue.isEmpty()||closed)return;NsdServiceInfo info=queue.remove();String key=key(info);int current=epoch;long attempt=++resolveGeneration;resolving=true;
        NsdManager.ResolveListener listener=new NsdManager.ResolveListener(){
            @Override public void onResolveFailed(NsdServiceInfo service,int error){main.post(()->{if(resolutionCurrent(current,attempt)){endpoints.remove(key);finishResolution();errorCode="resolve_failed";timedOut=false;message="本机服务端口解析失败，可重新发现或手动输入系统端口。";resolveNext();publish();}});}
            @Override public void onServiceResolved(NsdServiceInfo resolved){main.post(()->{
                if(!resolutionCurrent(current,attempt))return;
                try{if(seen.containsKey(key)&&resolved.getHost()!=null){String type=LocalAdbEndpoints.serviceType(resolved.getServiceType());String host=LocalAdbEndpoints.current().check(resolved.getHost().getHostAddress(),resolved.getPort());
                    endpoints.put(key,new JSONObject().put("host",host).put("port",resolved.getPort()).put("kind",type.equals(LocalAdbEndpoints.PAIRING)?"pairing":"connect").put("serviceName",resolved.getServiceName()).put("resolvedAt",System.currentTimeMillis()));errorCode="";timedOut=false;}}
                catch(Exception foreignOrUnavailable){endpoints.remove(key);}
                finally{finishResolution();publish();resolveNext();}
            });}
        };activeResolve=listener;activeResolveKey=key;
        resolveDeadline=()->{
            if(!resolutionCurrent(current,attempt))return;
            // A timed-out callback must not reset a newer resolution or publish a stale port.
            resolveGeneration++;stopResolution(listener);finishResolution();endpoints.remove(key);
            errorCode="resolve_timeout";timedOut=true;message="本机服务解析超时，可重新发现或手动读取系统连接端口。";publish();resolveNext();
        };main.postDelayed(resolveDeadline,5000);
        try{manager.resolveService(info,listener);}catch(RuntimeException unavailable){finishResolution();endpoints.remove(key);errorCode="resolve_unavailable";message="系统暂不能解析服务，可手动输入本机端口。";resolveNext();publish();}
    }
    private boolean resolutionCurrent(int current,long attempt){return valid(current)&&resolving&&resolveGeneration==attempt;}
    private void finishResolution(){if(resolveDeadline!=null)main.removeCallbacks(resolveDeadline);resolveDeadline=null;activeResolve=null;activeResolveKey=null;resolving=false;}
    private void stopResolution(NsdManager.ResolveListener listener){if(manager!=null&&listener!=null&&Build.VERSION.SDK_INT>=34)try{manager.stopServiceResolution(listener);}catch(RuntimeException ignored){}}
    private static String key(NsdServiceInfo info){return info.getServiceType()+"/"+info.getServiceName();}
    private void publish(){try{
        JSONArray pairing=new JSONArray(),connect=new JSONArray();long cutoff=System.currentTimeMillis()-180000;
        endpoints.entrySet().removeIf(entry->entry.getValue().optLong("resolvedAt")<cutoff);
        for(JSONObject item:endpoints.values())(item.optString("kind").equals("pairing")?pairing:connect).put(new JSONObject(item.toString()));
        state=new JSONObject().put("discovering",started&&!closed).put("pairing",pairing).put("connect",connect).put("message",message).put("errorCode",errorCode).put("timedOut",timedOut);changed.accept(snapshot());
    }catch(Exception ignored){}}
    public void stop(){main.post(()->{if(!closed){stopOnMain();errorCode="";timedOut=false;message="已停止本次本机服务发现，可重新发现或手动输入端口。";publish();}});}
    private void stopOnMain(){
        started=false;epoch++;resolveGeneration++;if(scanDeadline!=null)main.removeCallbacks(scanDeadline);scanDeadline=null;
        stopResolution(activeResolve);finishResolution();for(NsdManager.DiscoveryListener listener:listeners)try{manager.stopServiceDiscovery(listener);}catch(Exception ignored){}
        listeners.clear();activeKinds.clear();queue.clear();seen.clear();
    }
    @Override public void close(){main.post(()->{if(closed)return;stopOnMain();closed=true;endpoints.clear();publish();});}
}
