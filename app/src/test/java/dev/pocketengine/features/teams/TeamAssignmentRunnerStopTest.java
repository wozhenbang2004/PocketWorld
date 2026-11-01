package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real original Runner/HTTP cancellation and task-indexed native control. SQL/app dispatch remain separate unverified layers. */
public final class TeamAssignmentRunnerStopTest {
    @Test public void taskIndexedOriginalControlStopsTheInFlightModelWithoutAnotherRunnerOrFileEffect()throws Exception {
        TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();var nativeTasks=new TeamAssignmentRunnerJourneyTest.NativeTasks(new File(files.root,"original-stop-journals"));TeamAssignmentHost originalHost=new TeamAssignmentHost(nativeTasks,files.workspace);CountDownLatch inModel=new CountDownLatch(1),serverRelease=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));if(request.getString("model").equals("a")){inModel.countDown();serverRelease.await(3,TimeUnit.SECONDS);}byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","没有文件副作用的显式模型fixture")))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        ContextBudgetPolicy.installHostSource(selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
        try(TeamCoordinator coordinator=new TeamCoordinator(new File(files.root,"stop-team.json"),(config,messages,tools,control)->{throw new AssertionError("no research loop");},event->{})){
            coordinator.setAssignmentFactory(originalHost);JSONObject team=new JSONObject().put("id",TeamWorkFilesTest.id()).put("name","原控制停止").put("mode","produce").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","等待中的原成员").put("runner","model").put("device","phone").put("model","a").put("writeFiles",new JSONArray().put("src/a.txt"))).put(new JSONObject().put("id","b").put("name","乙").put("role","其他原成员").put("runner","model").put("device","phone").put("model","b").put("writeFiles",new JSONArray().put("src/b.txt"))));String run=TeamWorkFilesTest.id();coordinator.startWithScope(team,"核对停止原成员",1,new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-secret","a",false,24,10,"openai_chat",256),run,null,null,TeamWriteScope.capture(files.workspace,TeamWorkFilesTest.id(),TeamWorkFilesTest.id(),files.project,team));assertTrue(inModel.await(2,TimeUnit.SECONDS));
            TeamAssignmentRunnerJourneyTest.Entry member=null;synchronized(nativeTasks){for(var entry:nativeTasks.entries.values())if(entry.binding.getString("memberId").equals("a"))member=entry;}assertNotNull(member);String task=member.binding.getString("taskId");RunControl actual=originalHost.currentControl(task);assertSame(member.control,actual);actual.cancel();
            long until=System.nanoTime()+2_000_000_000L;JSONObject state;do{state=coordinator.get(run);if(!state.optString("status").matches("queued|running"))break;Thread.sleep(5);}while(System.nanoTime()<until);
            assertEquals("paused",state.getString("status"));assertEquals("cancelled",TeamWorkPlan.find(state.getJSONArray("members"),"a").getString("status"));assertNull(originalHost.currentControl(task));assertEquals(1,files.files.projects().length());assertEquals(0,files.artifacts.list(null).length());assertEquals("原文件\n",files.files.read(files.project,"src/a.txt",0).getString("content"));
        }finally{serverRelease.countDown();server.stop(0);ContextBudgetPolicy.installHostSource(selected->null);}
    }
}
