package dev.pocketengine.browser;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Original Runner/TaskStore/native scope, three explicitly selected fixture pages and real HTTP attachment bytes.
 * Model and rendered DOM are explicit local interface fixtures, not provider/site/device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserResearchJourneySqlTest {
    @Test public void originalResearchRunComparesThreePagesRetainsExactSourcesAndCompletedAttachment()throws Exception {try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F()){
        File files=Files.createTempDirectory("browser-research-results-").toFile();Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService worker=Executors.newFixedThreadPool(2);AtomicReference<Throwable> failed=new AtomicReference<>();AtomicBoolean deciding=new AtomicBoolean(true);AtomicInteger nativeDecisions=new AtomicInteger();byte[] specification="真实规格文件：三方案仅按各自原文比较。".getBytes(StandardCharsets.UTF_8);String downloadUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/specification?version=1";
        String foreign=UUID.randomUUID().toString();String[] tabs={x.tab,UUID.randomUUID().toString(),UUID.randomUUID().toString()},facts={"方案甲：99元/月；取消需提前7日通知。","方案乙：120元/月；可随时取消，已付费不退。","方案丙：88元/月；最低使用3个月。"};
        x.ui.call(()->{x.driver.original.pages.put(foreign,j("url","https://owner.example/unrelated","title","Unrelated owner page","navigation",1,"loading",false));for(int i=0;i<tabs.length;i++){if(i>0){x.driver.original.pages.put(tabs[i],j("url","https://plans.example/plan"+i+"?version=original#terms","title","方案"+i,"navigation",1,"loading",false));x.space.adoptOwnerNative(tabs[i],()->x.f.control.check());}x.driver.contents.put(tabs[i],facts[i]);}return null;});
        x.driver.realDownloads=BrowserDownloads.get(context);x.driver.downloadProfile=x.space.profile;String[] sourceRefs=new String[3],sourceIds=new String[3],job={null},attachment={null};int[] step={0},polls={0};AtomicReference<String> finalResult=new AtomicReference<>();
        Future<?> nativeOwner=worker.submit(()->{try{while(deciding.get()){JSONObject event=x.f.events.poll(50,TimeUnit.MILLISECONDS);if(event==null||!event.optString("type").equals("capability_approval"))continue;String tool=event.getString("tool");assertTrue("Only exact download/finish uses this explicit owner fixture",tool.matches("browser_task_download|browser_task_finish"));String id=event.getString("approvalId");if(tool.equals("browser_task_download"))assertEquals(downloadUrl,event.getJSONObject("arguments").getString("url"));x.f.tasks.decideCapabilityApproval(x.f.binding.taskId,id,true);x.approvals.nativeDecisionChanged(x.f.binding.taskId,id,x.f.control);nativeDecisions.incrementAndGet();}}catch(Throwable error){failed.compareAndSet(null,error);x.f.control.cancel();}});
        server.createContext("/specification",exchange->{try{exchange.getResponseHeaders().add("Content-Type","text/plain; charset=utf-8");exchange.sendResponseHeaders(200,specification.length);exchange.getResponseBody().write(specification);}finally{exchange.close();}});
        server.createContext("/",exchange->{try{
            JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));JSONArray messages=request.getJSONArray("messages");JSONObject receipt=null;for(int i=messages.length()-1;i>=0;i--)if(messages.getJSONObject(i).optString("role").equals("tool")){receipt=new JSONObject(messages.getJSONObject(i).getString("content"));break;}if(receipt!=null)assertTrue(receipt.toString(),receipt.optBoolean("ok"));String tool;JSONObject args,answer;
            if(step[0]<9){int page=step[0]/3,phase=step[0]%3;if(phase==0){if(page>0){assertTrue(receipt.getBoolean("sourceRetained"));sourceRefs[page-1]=receipt.getString("sourceRef");sourceIds[page-1]=receipt.getString("sourceId");}tool="browser_task_observe";args=j("tabId",tabs[page],"read",j("mode","outline","query","Revenue"));}
                else if(phase==1){String ref=receipt.getJSONObject("read").getJSONArray("data").getJSONObject(0).getString("regionRef");tool="browser_task_observe";args=j("tabId",tabs[page],"read",j("mode","table","regionRef",ref));}
                else{assertEquals(facts[page],receipt.getString("text"));assertEquals(tabs[page],receipt.getString("tabId"));assertEquals("full",receipt.getJSONObject("read").getJSONObject("delivery").getString("format"));tool="browser_task_retain_source";args=j("tabId",tabs[page],"observationId",receipt.getString("observationId"),"excerpt",receipt.getString("text"));}step[0]++;
            }else if(step[0]==9){sourceRefs[2]=receipt.getString("sourceRef");sourceIds[2]=receipt.getString("sourceId");tool="browser_task_observe";args=j("tabId",tabs[0]);step[0]++;}
            else if(step[0]==10){tool="browser_task_download";args=j("tabId",tabs[0],"observationId",receipt.getString("observationId"),"url",downloadUrl);step[0]++;}
            else if(step[0]==11){job[0]=receipt.getString("id");tool="browser_task_download_status";args=j("downloadId",job[0]);step[0]++;}
            else if(step[0]==12){if(!receipt.optString("status").equals("downloaded")){assertTrue("bounded status reads",++polls[0]<8);tool="browser_task_download_status";args=j("downloadId",job[0]);}
                else{attachment[0]=receipt.getJSONObject("attachment").getString("id");assertTrue(receipt.getJSONObject("attachment").getString("sha256").matches("[a-f0-9]{64}"));tool="browser_task_finish";args=new JSONObject();step[0]++;}}
            else{assertEquals("space_closed",receipt.getString("status"));StringBuilder result=new StringBuilder("|方案|已读原文|依据|\n|---|---|---|\n");for(int i=0;i<3;i++)result.append('|').append(i+1).append('|').append(facts[i]).append('|').append(sourceRefs[i]).append("|\n");result.append("规格附件：").append(attachment[0]).append("。仅比较实际已读条款，未读内容仍未知。");finalResult.set(result.toString());answer=j("role","assistant","content",result.toString());byte[] out=j("choices",new JSONArray().put(j("message",answer,"finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);return;}
            answer=j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",tool,"arguments",args.toString()))));byte[] out=j("choices",new JSONArray().put(j("message",answer,"finish_reason","tool_calls"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);
        }catch(Throwable error){failed.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        try{
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"local-explicit-fixture","three-page-source-attachment",false,45,24);RunOutcome outcome=worker.submit(()->new AgentRunner(null,x.port).run(x.f.binding.taskId,"比较三页价格和取消条款，保留每页原文及指定规格文件",config,x.f.control,event->{try{x.f.tasks.event(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},x.f.journal)).get(35,TimeUnit.SECONDS);
            if(failed.get()!=null)throw new AssertionError("explicit model/owner/DOM interface fixture",failed.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertNotNull(finalResult.get());assertEquals(3,new HashSet<>(Arrays.asList(sourceIds)).size());assertEquals(2,nativeDecisions.get());assertEquals(6,x.driver.reads);assertEquals(0,x.driver.original.mutableDispatches);assertNotNull(attachment[0]);assertEquals(new String(specification,StandardCharsets.UTF_8),AttachmentRepository.get(context).book.readText(attachment[0]).getString("text"));
            TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{JSONArray kept=BrowserTaskSourcePort.sourcesForOwner(reopened,x.f.binding.taskId);assertEquals(3,kept.length());for(int i=0;i<3;i++){JSONObject source=kept.getJSONObject(i);assertEquals(tabs[i],source.getString("tabId"));assertEquals(facts[i],source.getString("excerpt"));assertEquals("full",source.getJSONObject("semantic").getJSONObject("delivery").getString("format"));assertFalse(source.getJSONObject("semantic").getBoolean("wholePageComplete"));assertTrue(source.getString("privateUrl").contains(i==0?"owner.example":"?version=original#terms"));assertTrue(finalResult.get().contains(source.getString("sourceRef")));}}finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
            assertTrue(x.f.journal.restore().getJSONArray("messages").toString().contains(attachment[0]));assertEquals(x.f.binding.runId,x.f.tasks.get(x.f.binding.taskId,false).getString("runId"));for(String tab:tabs)assertFalse(x.driver.original.pages.containsKey(tab));assertTrue(x.driver.original.pages.containsKey(foreign)); // Only explicitly adopted task pages closed.
        }finally{deciding.set(false);server.stop(0);worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);BrowserDownloads.closeFor(context);}
    }}
}
