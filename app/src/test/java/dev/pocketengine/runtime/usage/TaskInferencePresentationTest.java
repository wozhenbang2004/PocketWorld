package dev.pocketengine.runtime.usage;

import dev.pocketengine.runtime.context.ContextWindow;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TaskInferencePresentationTest {
    private static JSONObject task(JSONArray events)throws Exception{return new JSONObject().put("id","task").put("runId","run").put("status","running").put("events",events);}
    private static JSONObject receipt(String id,InferenceReceipt.Purpose purpose,InferenceReceipt.Outcome outcome,ProviderUsage.Collector usage)throws Exception {
        return new InferenceReceipt(id,new InferenceReceipt.Context("task","run",1,purpose,""),outcome,100,outcome==InferenceReceipt.Outcome.STARTED?0:150,100,
                new JSONObject().put("kind","api_key").put("requestedModel","fixture").put("responseModel","served-fixture").put("connectionId","connection-1"),usage.toJson()).toJson();
    }
    private static ProviderUsage.Collector usage(int input,int output,ProviderUsage.Stage stage)throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("openai_chat");usage.observe(new JSONObject().put("usage",new JSONObject().put("prompt_tokens",input).put("completion_tokens",output).put("total_tokens",input+output)),stage);return usage;
    }
    private static String counter(TaskInferencePresentation.Group group,String name){return group.counters().stream().filter(x->x.name().equals(name)).findFirst().orElseThrow().text();}
    @Test public void absentRecordsAreUnknownNotAZeroBillAndOpeningIsReadOnly()throws Exception {
        JSONObject task=task(new JSONArray());String before=task.toString();var view=TaskInferencePresentation.fromTask(task);
        assertTrue(view.headline().contains("暂无保留"));assertTrue(view.coverage().contains("不等于实际用量为 0"));assertTrue(view.coverage().contains("套餐剩余量：未知"));assertTrue(view.groups().isEmpty());assertFalse(view.hasAccount());assertEquals(before,task.toString());
    }
    @Test public void sameNativeProjectionSeparatesMainAndSummaryAndDeduplicatesSnapshots()throws Exception {
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();ProviderUsage.Collector usage=usage(10,3,ProviderUsage.Stage.FINAL);
        JSONObject main=receipt(first,InferenceReceipt.Purpose.MAIN,InferenceReceipt.Outcome.COMPLETED,usage),summary=receipt(second,InferenceReceipt.Purpose.SUMMARY,InferenceReceipt.Outcome.COMPLETED,usage);
        JSONArray events=new JSONArray().put(main).put(main).put(summary);String before=events.toString();var view=TaskInferencePresentation.fromTask(task(events));
        assertEquals(2,view.groups().size());assertEquals(2,view.calls().size());assertTrue(view.groups().get(0).title().contains("MAIN"));assertTrue(view.groups().get(1).title().contains("SUMMARY"));
        assertTrue(counter(view.groups().get(0),"输入").startsWith("10（已返回小计）"));assertTrue(counter(view.groups().get(0),"服务总计").startsWith("13"));assertTrue(view.groups().get(0).semantics().contains("不再相加"));assertEquals(before,events.toString());
    }
    @Test public void cancellationKeepsPartialProviderCountersAcrossColdProjectionAndDoesNotInventFinal()throws Exception {
        JSONObject cancelled=receipt(UUID.randomUUID().toString(),InferenceReceipt.Purpose.MAIN,InferenceReceipt.Outcome.CANCELLED,usage(20,4,ProviderUsage.Stage.PARTIAL));
        JSONObject task=task(new JSONArray().put(cancelled));var view=TaskInferencePresentation.fromTask(new JSONObject(task.toString()));
        assertTrue(view.calls().get(0).status().contains("不代表退款"));assertTrue(counter(view.groups().get(0),"输入").contains("20"));assertTrue(counter(view.groups().get(0),"输入").contains("部分返回"));
        assertTrue(counter(view.groups().get(0),"缓存读取").startsWith("未知"));assertTrue(view.calls().get(0).counters().get(0).text().contains("usage.prompt_tokens"));assertFalse(view.calls().get(0).counters().get(0).text().contains("最终返回"));
    }
    @Test public void unfinishedIsUncertainRatherThanProofItIsStillRunningOrFree()throws Exception {
        JSONObject started=receipt(UUID.randomUUID().toString(),InferenceReceipt.Purpose.MAIN,InferenceReceipt.Outcome.STARTED,new ProviderUsage.Collector("openai_chat"));
        var view=TaskInferencePresentation.fromTask(task(new JSONArray().put(started)));assertTrue(view.warnings().get(0).contains("不代表仍在运行"));assertTrue(view.calls().get(0).status().contains("未见终态"));assertEquals("未知 · 1 次未知",counter(view.groups().get(0),"输入"));
    }
    @Test public void conflictIsVisibleAndNoArbitraryReceiptNumberIsDisplayedAsTrusted()throws Exception {
        JSONObject first=receipt(UUID.randomUUID().toString(),InferenceReceipt.Purpose.SUMMARY,InferenceReceipt.Outcome.COMPLETED,usage(10,2,ProviderUsage.Stage.FINAL)),other=new JSONObject(first.toString());
        other.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").put("value",999);
        var view=TaskInferencePresentation.fromTask(task(new JSONArray().put(first).put(other)));assertTrue(view.groups().isEmpty());assertTrue(view.warnings().get(0).contains("冲突"));assertTrue(view.calls().get(0).counters().get(0).text().startsWith("未知（该调用记录冲突"));
    }
    @Test public void foreignTaskAndUnknownFieldsCannotTurnIntoThisTasksUsageOrVisibleBodies()throws Exception {
        JSONObject foreign=receipt(UUID.randomUUID().toString(),InferenceReceipt.Purpose.MAIN,InferenceReceipt.Outcome.COMPLETED,usage(100,2,ProviderUsage.Stage.FINAL));foreign.getJSONObject("context").put("taskId","other-task");
        JSONObject own=receipt(UUID.randomUUID().toString(),InferenceReceipt.Purpose.TOOL,InferenceReceipt.Outcome.FAILED,new ProviderUsage.Collector("anthropic"));own.getJSONObject("route").put("apiKey","SECRET_BODY_91");own.getJSONObject("usage").put("rawBody","SECRET_BODY_91");
        JSONObject input=task(new JSONArray().put(foreign).put(own));String before=input.toString();var view=TaskInferencePresentation.fromTask(input);
        assertEquals(1,view.calls().size());assertTrue(view.warnings().get(0).contains("归属"));assertFalse(view.toString().contains("SECRET_BODY_91"));assertTrue(view.calls().get(0).status().contains("不代表未计费"));assertEquals(before,input.toString());
    }
    @Test public void exactAccountRegistrationAndObservedVersionAreShownWithoutQuotaGuessing()throws Exception {
        JSONObject account=receipt(UUID.randomUUID().toString(),InferenceReceipt.Purpose.MAIN,InferenceReceipt.Outcome.COMPLETED,usage(3,2,ProviderUsage.Stage.FINAL));
        account.getJSONObject("route").put("kind","account").put("accountId","registration-42").put("accountVersion",9);
        var view=TaskInferencePresentation.fromTask(task(new JSONArray().put(account)));assertTrue(view.hasAccount());assertTrue(view.calls().get(0).route().contains("registration-42 · v9"));assertTrue(view.coverage().contains("套餐剩余量：未知"));
    }
    private static JSONObject diagnostics(boolean known)throws Exception {
        var budget=known?new ContextWindow.Budget(10000,1000,100,100000,0,0):ContextWindow.Budget.localBytes(100000);
        return ContextWindow.plan(new JSONArray().put(new JSONObject().put("role","user").put("content","fixture")),null,budget,ContextWindow.Retention.preserveAll()).diagnostics();
    }
    @Test public void contextShowsTheActualLocalEstimateSeparatelyFromProviderUsageAndHistoricalSummary()throws Exception {
        String summaryCall=UUID.randomUUID().toString();JSONObject diagnostic=diagnostics(true).put("summaryProjectionUsed",true).put("summaryCallId",summaryCall).put("coveredIndexes",new JSONArray().put(2).put(3));
        JSONObject budget=new JSONObject().put("type","context_budget").put("taskId","task").put("receipt",diagnostic).put("at",12345);
        var view=TaskInferencePresentation.fromTask(task(new JSONArray().put(budget)));String context=view.context().toString();
        assertTrue(context.contains("本地保守估算"));assertTrue(context.contains("不是服务实际计数"));assertTrue(context.contains("覆盖 2 条"));assertTrue(context.contains("消息索引：2, 3"));assertTrue(context.contains(summaryCall));assertTrue(context.contains("曾使用"));assertTrue(context.contains("不证明当前"));assertEquals(12345,view.context().at());assertFalse(view.context().attention());assertTrue(view.calls().isEmpty());
    }
    @Test public void unknownWindowNeverGetsAPercentageAndBlockedResultSupersedesEarlierReadyEvent()throws Exception {
        JSONObject task=task(new JSONArray().put(new JSONObject().put("type","context_budget").put("taskId","task").put("receipt",diagnostics(false))));
        assertTrue(TaskInferencePresentation.fromTask(task).context().toString().contains("不显示占窗百分比"));
        task.put("status","awaiting_user").put("evidence",new JSONObject().put("contextCompaction",new JSONObject().put("status","summary_publish_stale").put("rawRetained",true)));
        var view=TaskInferencePresentation.fromTask(task);assertTrue(view.context().attention());assertTrue(view.context().toString().contains("未能安全发布"));assertFalse(view.context().toString().contains("曾使用"));assertEquals(0,view.context().at());
    }
    @Test public void android30CompatibleWhitespaceKeepsUnicodeSemanticsAndListsRemainImmutable()throws Exception {
        assertThrows(IllegalArgumentException.class,()->TaskInferencePresentation.fromTask(task(new JSONArray()).put("id"," \t\n\u2003")));
        JSONObject entry=receipt(UUID.randomUUID().toString(),InferenceReceipt.Purpose.MAIN,InferenceReceipt.Outcome.COMPLETED,usage(1,1,ProviderUsage.Stage.FINAL));
        entry.getJSONObject("route").put("requestedModel","\u2003").put("responseModel","\u00a0");
        var view=TaskInferencePresentation.fromTask(task(new JSONArray().put(entry)));
        assertTrue(view.calls().get(0).route().contains("请求模型未知"));assertTrue(view.calls().get(0).route().contains("返回：\u00a0"));
        assertThrows(UnsupportedOperationException.class,()->view.groups().clear());assertThrows(UnsupportedOperationException.class,()->view.groups().get(0).counters().clear());
    }

}
