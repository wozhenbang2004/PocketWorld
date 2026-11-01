package dev.pocketengine.features.conversation;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConversationPresentationTest {
    @Test public void codePreservesLeadingBlankLinesLiteralMarkupAndUnicode(){
        String source="# 结果\n\n说明 **重点** 与 `inline`\n\n````python\n\nprint(\"🙂\")\n```\n<script>not executable</script>\n````   \n\n结束";
        List<RichMessageDocument.Block> blocks=RichMessageDocument.parse(source);assertEquals(4,blocks.size());assertEquals(RichMessageDocument.Kind.HEADING,blocks.get(0).kind);assertEquals("重点",blocks.get(1).spans.get(1).text);assertEquals("bold",blocks.get(1).spans.get(1).style);
        RichMessageDocument.Block code=blocks.get(2);assertEquals(RichMessageDocument.Kind.CODE,code.kind);assertTrue(code.closed);assertEquals("python",code.language);assertEquals("\nprint(\"🙂\")\n```\n<script>not executable</script>",code.text);assertEquals(1,code.spans.size());assertEquals("结束",blocks.get(3).text);
    }
    @Test public void unclosedCodeAndUnsupportedMarkupStayReadableWithoutExecuting(){
        List<RichMessageDocument.Block> blocks=RichMessageDocument.parse("<script>post()</script>\n\n~~~js\nconst x = 1;\n");assertEquals(RichMessageDocument.Kind.PARAGRAPH,blocks.get(0).kind);assertTrue(blocks.get(0).text.contains("<script>"));assertFalse(blocks.get(1).closed);assertEquals("const x = 1;\n",blocks.get(1).text);
    }
    @Test public void safeLinksAreExplicitAndSchemesOrEmbeddedCredentialsStayPlain(){
        List<RichMessageDocument.Span> spans=RichMessageDocument.inline("[资料](https://example.org/guide) 与 [坏](javascript:run()) 和 [骗](https://user:pass@example.org/a)");assertEquals(2,spans.size());assertEquals("https://example.org/guide",spans.get(0).url);assertTrue(spans.get(1).text.contains("javascript:"));assertTrue(spans.get(1).text.contains("user:pass"));
        for(String url:List.of("javascript:run()","file:///a","content://secret","data:text/html,<b>","//example.org","https://user@example.org","https://example.org/\nnext"))assertFalse(url,RichMessageDocument.safeUrl(url));assertTrue(RichMessageDocument.safeUrl("http://localhost:8080/docs?q=hello#part"));
    }
    @Test public void listQuoteAndEscapedTablePipesKeepTheirContent(){
        List<RichMessageDocument.Block> blocks=RichMessageDocument.parse("- 首项\n  2. 子项\n> 引用\n\n| 字段 | 值 |\n| --- | :---: |\n| 路径 | a\\|b |\n");assertEquals(4,blocks.size());assertEquals("•",blocks.get(0).marker);assertEquals(1,blocks.get(1).level);assertEquals("2.",blocks.get(1).marker);assertEquals(RichMessageDocument.Kind.QUOTE,blocks.get(2).kind);assertEquals("a|b",blocks.get(3).rows.get(1).get(1));
        assertEquals("**literal**",RichMessageDocument.inline("\\*\\*literal\\*\\*").get(0).text);
    }
    @Test public void pendingReceiptAndAcceptedCallNeverInventBusinessCompletion(){
        JSONObject task=new JSONObject().put("id","original").put("runId","same-run").put("status","running").put("goal","交代的目标").put("operations",new JSONArray()
            .put(new JSONObject().put("operationId","one").put("tool","notifications_reply").put("state","prepared"))
            .put(new JSONObject().put("operationId","two").put("tool","browser_click").put("state","receipt").put("receipt",new JSONObject().put("ok",true).put("accepted",true).put("businessSuccessVerified",false))));
        TaskPresentation.Snapshot presentation=TaskPresentation.fromTaskStore(task);assertEquals("执行中",presentation.label);assertFalse(presentation.confirmedComplete);assertEquals("已登记，等待收据",presentation.operations.get(0).label);assertEquals("调用已接受",presentation.operations.get(1).label);assertFalse(task.has("completed"));assertEquals("same-run",presentation.runId);
    }
    @Test public void unknownAndMissingDetailsStayDistinctAndReadOnly(){
        JSONObject task=new JSONObject().put("id","same-task").put("status","reconciling");TaskPresentation.Snapshot unloaded=TaskPresentation.fromTaskStore(task);assertFalse(unloaded.detailsLoaded);assertTrue(unloaded.next.contains("先核对"));
        task.put("operations",new JSONArray().put(new JSONObject().put("operationId","old").put("state","receipt").put("receipt",new JSONObject().put("accepted",JSONObject.NULL).put("message","需要手工核对"))));String before=task.toString();TaskPresentation.Snapshot view=TaskPresentation.fromTaskStore(task);assertTrue(view.detailsLoaded);assertTrue(view.operations.get(0).uncertain);assertEquals("结果待核对",view.operations.get(0).label);assertEquals(before,task.toString());
        task.put("operations",new JSONArray()).put("status","completed");assertTrue(TaskPresentation.fromTaskStore(task).confirmedComplete);assertTrue(TaskPresentation.fromTaskStore(task).operations.isEmpty());
    }
    @Test public void acceptedTransportWithToolErrorAndPendingApprovalHaveDifferentFeedback(){
        JSONArray records=new JSONArray().put(new JSONObject().put("operationId","failed").put("state","receipt").put("receipt",new JSONObject().put("accepted",true).put("ok",false).put("stderr","执行返回错误")))
            .put(new JSONObject().put("operationId","waiting").put("state","receipt").put("receipt",new JSONObject().put("accepted",false).put("ok",false).put("code","approval_required")));
        TaskPresentation.Snapshot snapshot=TaskPresentation.fromTaskStore(new JSONObject().put("status","awaiting_approval").put("operations",records));assertEquals("工具返回错误",snapshot.operations.get(0).label);assertEquals("等待操作授权",snapshot.operations.get(1).label);assertFalse(snapshot.confirmedComplete);
    }
    @Test public void cardUsesOnlyDisplayFieldsAndBoundsLongOutputWithoutClippingEmoji(){
        JSONObject receipt=new JSONObject().put("message","可读结果").put("secret","not-a-display-field").put("data",new JSONObject().put("stdout","🙂".repeat(10000)).put("apiKey","must-not-display"));
        TaskPresentation.Snapshot view=TaskPresentation.fromTaskStore(new JSONObject().put("status","needs_confirmation").put("operations",new JSONArray().put(new JSONObject().put("operationId","op").put("state","receipt").put("receipt",receipt))));String text=view.operations.get(0).output;
        assertTrue(text.contains("可读结果"));assertTrue(text.contains("只展示部分"));assertFalse(text.contains("not-a-display-field"));assertFalse(text.contains("must-not-display"));assertTrue(text.length()<16200);for(int i=0;i<text.length();i++)if(Character.isHighSurrogate(text.charAt(i)))assertTrue(Character.isLowSurrogate(text.charAt(++i)));assertEquals("请确认结果",view.label);assertFalse(view.confirmedComplete);
    }
    @Test public void historySearchKeepsPersistedIdentityAndGroupsWithDeviceTimeZone(){
        long now=Instant.parse("2026-10-31T12:00:00Z").toEpochMilli();ConversationHistory.Item older=new ConversationHistory.Item("id1","旧标题",now-20_000),newer=new ConversationHistory.Item("id1","我的持续会话",now-1000),week=new ConversationHistory.Item("id2","持续研究",now-3*86_400_000L),past=new ConversationHistory.Item("id3","项目记录",now-30*86_400_000L);
        List<ConversationHistory.Group> found=ConversationHistory.organize(List.of(older,newer,week,past),"持续",now,ZoneId.of("UTC"));assertEquals(2,found.size());assertEquals("今天",found.get(0).label);assertEquals("id1",found.get(0).items.get(0).id);assertEquals("我的持续会话",found.get(0).items.get(0).title);assertEquals("最近七天",found.get(1).label);
        assertTrue(ConversationHistory.organize(List.of(newer,past),"没有此标题",now,ZoneId.of("UTC")).isEmpty());assertEquals("更早",ConversationHistory.organize(List.of(past),"",now,ZoneId.of("UTC")).get(0).label);
    }
}
