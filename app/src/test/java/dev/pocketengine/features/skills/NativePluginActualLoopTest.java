package dev.pocketengine.features.skills;
import android.app.Application;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.catalog.ExecutablePluginCatalog;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
/** Real installed Application/source/outer port/gateway/SQLite; Node VM is only the test isolate service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class NativePluginActualLoopTest {
 static final String SOURCE="defineExtension({tools:[{name:'twice',description:'Pure arithmetic',parameters:{type:'object',properties:{n:{type:'integer'}},required:['n'],additionalProperties:false},execute(args,state){return {value:args.n*2,state};}}],context(input,state){return {text:'native-catalog-context',state};}});";
 static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void install(ExecutablePluginCatalog c,boolean enabled)throws Exception{JsPluginPackage p=JsPluginPackage.author(new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)),"script","Native source fixture","1",new RunControl());var review=c.inspect(p,new RunControl());c.installReviewed(review,review.reviewDigest,new RunControl());if(enabled){var a=c.reviewInstalled("script",new RunControl());c.approveReviewed(a,a.reviewDigest,true,new RunControl());var e=c.reviewInstalled("script",new RunControl());c.setEnabledReviewed(e,e.reviewDigest,true,new RunControl());}}
 static JSONObject run(EngineApplication app,ModelConfig config,RunControl control)throws Exception{String id=app.tasks.createCapabilityTask("calculate a pure reviewed answer","").getString("id");Method m=EngineApplication.class.getDeclaredMethod("runCapabilityAttempt",String.class,JSONArray.class,ModelConfig.class,RunControl.class);m.setAccessible(true);try{return (JSONObject)m.invoke(app,id,null,config,control);}catch(java.lang.reflect.InvocationTargetException cause){if(cause.getCause() instanceof Exception)throw (Exception)cause.getCause();throw cause;}}
 @Test public void actualApplicationFactoryExecutesApprovedBytesThroughSharedSandboxAndExistingModelLoop()throws Exception{
  EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();Provider provider=replaceProvider(app);ExecutablePluginCatalog catalog=(ExecutablePluginCatalog)field(app.pluginHost,"executable");install(catalog,true);
  try(Server server=new Server(null)){var worker=Executors.newSingleThreadExecutor();JSONObject result;try{result=worker.submit(()->run(app,server.config(),new RunControl())).get(30,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
   assertEquals(result.toString()+" evaluations="+provider.evaluations.get(),2,server.requests.size());assertTrue(server.requests.get(0).toString().contains("native-catalog-context"));assertTrue(server.requests.get(1).toString().contains("42"));assertEquals(1,provider.connects.get());assertTrue(provider.evaluations.get()>0);assertEquals(provider.created.get(),provider.closed.get());String id=result.getString("taskId");assertEquals(0,app.tasks.get(id,false).getJSONArray("operations").length());assertTrue(((java.util.Map<?,?>)field(app.pluginHost,"runs")).isEmpty());assertEquals("ready",app.pluginHost.runtimeSnapshot().getString("status"));assertFalse(result.getString("content").contains("fixture-api-key"));
  }
 }
 @Test public void installedButUnapprovedSourceNeverWarmsOrExecutesAndRevocationDuringModelCannotDispatchOldJs()throws Exception{
  EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();Provider provider=replaceProvider(app);ExecutablePluginCatalog catalog=(ExecutablePluginCatalog)field(app.pluginHost,"executable");install(catalog,false);
  try(Server server=new Server(null)){var worker=Executors.newSingleThreadExecutor();try{worker.submit(()->run(app,server.config(),new RunControl())).get(20,TimeUnit.SECONDS);}finally{worker.shutdownNow();}assertEquals(0,provider.connects.get());assertEquals(0,provider.evaluations.get());assertFalse(server.requests.get(0).toString().contains("native-catalog-context"));}
  var a=catalog.reviewInstalled("script",new RunControl());catalog.approveReviewed(a,a.reviewDigest,true,new RunControl());var e=catalog.reviewInstalled("script",new RunControl());catalog.setEnabledReviewed(e,e.reviewDigest,true,new RunControl());
  AtomicInteger atResponse=new AtomicInteger(),remainingMounted=new AtomicInteger(-1);AtomicReference<String> mutationTask=new AtomicReference<>();try(Server server=new Server(()->{try{
   atResponse.set(provider.evaluations.get());var page=app.pluginHost.newOwnerPage();var review=page.session.begin(page.nativeOwner(),java.util.UUID.randomUUID().toString(),"jsReviewInstalled",new JSONObject().put("id","script"));page.beforeDispatch(review,page.session.view(page.nativeOwner()),new JSONObject().put("id","script"));var view=review.execute();page.settled(review,view,review.receipt());var revoke=page.session.changeJs(page.nativeOwner(),java.util.UUID.randomUUID().toString(),view.approval,"revoke");page.beforeDispatch(revoke,page.session.view(page.nativeOwner()),new JSONObject().put("action","revoke"));mutationTask.set(revoke.requestId);var saved=revoke.execute();page.close();page.settled(revoke,saved,revoke.receipt());var host=field(app.pluginHost,"extensions");Object port=((java.util.Set<?>)field(host,"live")).iterator().next();Object runtime=field(port,"runtime");remainingMounted.set(((java.util.Map<?,?>)field(runtime,"entries")).size());
  }catch(Exception failure){throw new IllegalStateException(failure);}})){
   var worker=Executors.newSingleThreadExecutor();JSONObject result;try{result=worker.submit(()->run(app,server.config(),new RunControl())).get(30,TimeUnit.SECONDS);}finally{worker.shutdownNow();}assertEquals(1,server.requests.size());assertEquals(atResponse.get(),provider.evaluations.get());assertEquals(0,remainingMounted.get());JSONObject mutation=app.tasks.get(mutationTask.get(),false).getJSONArray("operations").getJSONObject(0);assertEquals("saved",mutation.getJSONObject("receipt").getString("status"));assertFalse(mutation.getJSONObject("arguments").getString("oldInstallationId").isEmpty());assertEquals("revoke",mutation.getJSONObject("arguments").getString("action"));assertFalse(result.getString("status").equals("completed"));assertEquals(0,app.tasks.get(result.getString("taskId"),false).getJSONArray("operations").length());assertEquals(provider.created.get(),provider.closed.get());
  }
 }
 static Provider replaceProvider(EngineApplication app)throws Exception{Provider p=new Provider();Field f=AndroidJavascriptSandboxRuntime.class.getDeclaredField("provider");f.setAccessible(true);f.set(app.pluginHost.sharedSandbox(),p);return p;}
 static final class Provider implements AndroidJavascriptSandboxRuntime.Provider {
  final AtomicInteger connects=new AtomicInteger(),created=new AtomicInteger(),closed=new AtomicInteger(),evaluations=new AtomicInteger();
  public String generation(){return "test-installed-webview";}
  public AndroidJavascriptSandboxRuntime.Identity fingerprint()throws Exception{return new AndroidJavascriptSandboxRuntime.Identity(generation(),"fixture-webview","a".repeat(64));}
  public AndroidJavascriptSandboxRuntime.Engine connect(){connects.incrementAndGet();return new AndroidJavascriptSandboxRuntime.Engine(){public boolean supports(String feature){return true;}public AndroidJavascriptSandboxRuntime.Isolate isolate(long heap,int max){created.incrementAndGet();return new NodeIsolate(Provider.this);}public void close(){}};}
 }
 static final class NodeIsolate implements AndroidJavascriptSandboxRuntime.Isolate {
  final Provider owner;String initialization;boolean done;volatile Process process;
  NodeIsolate(Provider owner){this.owner=owner;}
  public ListenableFuture<String> evaluate(String source){SettableFuture<String> result=SettableFuture.create();try{
   if(done)throw new IllegalStateException("closed fixture isolate");boolean bootstrap=source.startsWith("(()=>{const a=JSON.parse(");if(bootstrap)initialization=source;else if(!source.startsWith("JSON.stringify({"))owner.evaluations.incrementAndGet();
   String runner="const vm=require('node:vm');let raw='';process.stdin.setEncoding('utf8');process.stdin.on('data',x=>raw+=x);process.stdin.on('end',async()=>{try{let p=JSON.parse(raw);let c=vm.createContext({});if(p.init)vm.runInContext(p.init,c,{timeout:100});let v=await vm.runInContext(p.source,c,{timeout:100});process.stdout.write(JSON.stringify(v));}catch(e){process.stderr.write(String(e));process.exitCode=1;}});";
   process=new ProcessBuilder("node","-e",runner).start();JSONObject input=new JSONObject().put("init",bootstrap?"":initialization==null?"":initialization).put("source",source);process.getOutputStream().write(input.toString().getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Node fixture timeout");}String out=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);if(process.exitValue()!=0)throw new IllegalStateException(new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8));result.set(new JSONArray("["+out+"]").getString(0));
  }catch(Exception failure){result.setException(failure);}finally{process=null;}return result;}
  public void close(){if(!done){done=true;owner.closed.incrementAndGet();Process current=process;if(current!=null)current.destroyForcibly();}}
 }
 static final class Server implements AutoCloseable {
  final HttpServer http;final List<JSONObject> requests=new CopyOnWriteArrayList<>();final Runnable first;
  Server(Runnable first)throws Exception{this.first=first;http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",x->{try{JSONObject request=new JSONObject(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);String name="";JSONArray tools=request.optJSONArray("tools");for(int i=0;tools!=null&&i<tools.length();i++){String n=tools.getJSONObject(i).getJSONObject("function").getString("name");if(n.startsWith("ext_")){name=n;break;}}JSONObject message=new JSONObject().put("role","assistant").put("content","The actual pure arithmetic reply is 42.");if(requests.size()==1&&!name.isEmpty())message.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","calculation").put("type","function").put("function",new JSONObject().put("name",name).put("arguments","{\"n\":21}"))));if(requests.size()==1&&first!=null)first.run();byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().add("Content-Type","application/json");x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);}catch(Exception e){x.sendResponseHeaders(500,-1);}finally{x.close();}});http.start();}
  ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort()+"/v1","fixture-api-key","fixture-model",false,8,10);}
  public void close(){http.stop(0);}
 }
}
