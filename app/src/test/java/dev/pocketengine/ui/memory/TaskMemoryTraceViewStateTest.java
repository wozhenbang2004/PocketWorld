package dev.pocketengine.ui.memory;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** UI state with the real frozen B1 quote checker; the supplied fixture is explicitly synthetic. */
public final class TaskMemoryTraceViewStateTest {
    private static JSONObject fixture()throws Exception{
        try(var input=TaskMemoryTraceViewStateTest.class.getResourceAsStream("/memory/operation-quote-fixture.json")){
            assertNotNull(input);return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private static Object construct(String type,Class<?>[] types,Object...args)throws Exception{
        return Class.forName("dev.pocketengine.ui.memory.TaskMemoryTraceViewState$"+type).getConstructor(types).newInstance(args);
    }
    private static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{
        try{return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}
    }
    private static Object call(Object target,String name)throws Exception{return call(target,name,new Class<?>[]{});}
    private static Object nav()throws Exception{return construct("Navigation",new Class<?>[]{String.class,String.class},"task-a","run-a");}
    private static JSONObject at(Object nav)throws Exception{return (JSONObject)call(nav,"location");}
    private static void set(Object nav,String action,String value)throws Exception{call(nav,action,new Class<?>[]{String.class},value);}
    private static Object operation()throws Exception{
        JSONObject request=fixture().getJSONObject("request");JSONObject entry=new JSONObject().put("kind","operation_receipt").put("sourceId",request.getString("sourceId")).put("revision",request.getString("revision"));
        return construct("Operation",new Class<?>[]{String.class,String.class,JSONObject.class},request.getString("taskId"),request.getString("runId"),entry);
    }
    private static JSONObject request(Object op,int offset)throws Exception{return (JSONObject)call(op,"request",new Class<?>[]{int.class,int.class},offset,65);}
    private static void begin(Object op,String id,int offset)throws Exception{call(op,"begin",new Class<?>[]{String.class,JSONObject.class},id,request(op,offset));}
    private static boolean receive(Object op,String id,JSONObject page)throws Exception{return (Boolean)call(op,"accept",new Class<?>[]{String.class,JSONObject.class},id,page);}
    private static JSONObject page(Object op)throws Exception{return (JSONObject)call(op,"page");}
    private static String quote(Object op)throws Exception{return (String)call(op,"citation");}

    @Test public void parentAndSourceReturnRestoreExactQueryCursorAndSnapshot()throws Exception{
        Object nav=nav();set(nav,"section","operations");set(nav,"query","  原始结果 😀  ");
        call(nav,"accepted",new Class<?>[]{JSONObject.class},new JSONObject().put("taskId","task-a").put("runId","run-a").put("snapshot","a".repeat(64)));
        call(nav,"request",new Class<?>[]{String.class,boolean.class},"opaque-original-cursor",true);JSONObject original=at(nav);
        call(nav,"focus",new Class<?>[]{String.class,String.class},"task:task-a:message:parent","a".repeat(64));assertEquals("",at(nav).getString("query"));assertEquals("task:task-a:message:parent",at(nav).getString("sourceId"));assertTrue((Boolean)call(nav,"back"));assertTrue(original.similar(at(nav)));
        call(nav,"parent",new Class<?>[]{String.class,String.class,String.class},"parent-task","parent-run","a".repeat(64));assertEquals("parent-task",at(nav).getString("taskId"));assertEquals("",at(nav).getString("query"));assertTrue((Boolean)call(nav,"back"));assertTrue(original.similar(at(nav)));assertFalse((Boolean)call(nav,"back"));
    }
    @Test public void runNavigationKeepsQueryAndBackCannotReadAFormerCursorInNewScope()throws Exception{
        Object nav=nav();set(nav,"section","messages");set(nav,"query","失败原因");call(nav,"run",new Class<?>[]{String.class,String.class},"run-before","b".repeat(64));assertEquals("失败原因",at(nav).getString("query"));assertEquals("",at(nav).getString("cursor"));assertEquals("",at(nav).getString("snapshot"));assertTrue((Boolean)call(nav,"back"));assertEquals("run-a",at(nav).getString("runId"));assertEquals("失败原因",at(nav).getString("query"));
        JSONObject args=(JSONObject)call(nav,"request",new Class<?>[]{String.class,boolean.class},"",false);assertEquals(Set.of("taskId","runId","section","query","sourceId","cursor","limit","maxChars"),args.keySet());set(nav,"query","新的查询");assertFalse((Boolean)call(nav,"matches",new Class<?>[]{JSONObject.class},args));
    }
    @Test public void navigationDepthFailureDoesNotDestroyTheCurrentLocation()throws Exception{
        Object nav=nav();for(int i=0;i<8;i++)call(nav,"parent",new Class<?>[]{String.class,String.class,String.class},"parent-"+i,"run-"+i,"");JSONObject before=at(nav);assertThrows(IllegalStateException.class,()->call(nav,"parent",new Class<?>[]{String.class,String.class,String.class},"too-deep","run",""));assertTrue(before.similar(at(nav)));
    }
    @Test public void exactOperationRequestComesFromOriginalEntryAndNeverFromSummary()throws Exception{
        Object op=operation();assertTrue(fixture().getJSONObject("request").similar(request(op,0)));assertThrows(IllegalArgumentException.class,()->request(op,-1));
        JSONObject pin=fixture().getJSONObject("request"),entry=new JSONObject().put("kind","checkpoint_message").put("sourceId",pin.getString("sourceId")).put("revision",pin.getString("revision"));
        assertThrows(InvocationTargetException.class,()->construct("Operation",new Class<?>[]{String.class,String.class,JSONObject.class},pin.getString("taskId"),pin.getString("runId"),entry));
    }
    @Test public void citationContainsOnlyCurrentRangeAndNextReadKeepsOriginalIdentity()throws Exception{
        Object op=operation();begin(op,"first",0);assertTrue(receive(op,"first",fixture().getJSONObject("current")));String quote=quote(op);JSONObject current=fixture().getJSONObject("current");assertTrue(quote.endsWith(current.getString("body")));assertTrue(quote.contains(current.getString("sourceId")));assertTrue(quote.contains(current.getString("revision")));assertTrue(quote.contains("65"));assertTrue(quote.contains("1159"));int next=(Integer)call(op,"nextOffset");assertEquals(65,next);
        begin(op,"next",next);assertNull(page(op));assertThrows(IllegalStateException.class,()->quote(op));JSONObject submitted=(JSONObject)call(op,"submitted");assertEquals(next,submitted.getInt("offset"));for(String key:new String[]{"taskId","runId","sourceId","revision"})assertEquals(current.getString(key),submitted.getString(key));
    }
    @Test public void changedMissingAndLimitedOriginalBodiesAreClearedWithoutAutoUpgrade()throws Exception{
        for(String state:new String[]{"sourceChanged","unavailable","limited"}){
            Object op=operation();begin(op,"old",0);receive(op,"old",fixture().getJSONObject("current"));begin(op,"check",0);JSONObject refused=fixture().getJSONObject(state).put("body","retained body must disappear");assertTrue(receive(op,"check",refused));assertNull(page(op));assertThrows(IllegalStateException.class,()->quote(op));assertEquals(fixture().getJSONObject("request").getString("revision"),request(op,0).getString("revision"));
        }
    }
    @Test public void cancelRefreshAndLateEnvelopesCannotReopenOrReplaceAnotherRead()throws Exception{
        Object op=operation();begin(op,"cancelled",0);call(op,"cancel");assertFalse(receive(op,"cancelled",fixture().getJSONObject("current")));assertNull(page(op));begin(op,"current",0);assertFalse(receive(op,"cancelled",fixture().getJSONObject("current")));assertEquals("current",call(op,"pendingId"));assertTrue(receive(op,"current",fixture().getJSONObject("current")));call(op,"invalidate");assertNull(page(op));assertThrows(IllegalStateException.class,()->quote(op));assertFalse(receive(op,"current",fixture().getJSONObject("current")));assertEquals(fixture().getJSONObject("request").getString("revision"),request(op,0).getString("revision"));
    }
    @Test public void realQuoteCheckerRejectsForeignVersionOrRangeBeforeDisplay()throws Exception{
        Object op=operation();begin(op,"read",0);assertThrows(SecurityException.class,()->receive(op,"read",fixture().getJSONObject("current").put("revision","sha256:"+"0".repeat(64))));assertNull(page(op));JSONObject wrong=fixture().getJSONObject("current");wrong.getJSONObject("readRange").put("offset",1);assertThrows(IllegalArgumentException.class,()->receive(op,"read",wrong));assertNull(page(op));
    }
    @Test public void storedPageAndSubmittedIdentityDoNotAliasCallerJson()throws Exception{
        Object op=operation();JSONObject submitted=request(op,0);call(op,"begin",new Class<?>[]{String.class,JSONObject.class},"read",submitted);submitted.put("sourceId","other");JSONObject current=fixture().getJSONObject("current");assertTrue(receive(op,"read",current));String before=quote(op);current.put("body","edited caller body");JSONObject exposed=page(op);exposed.put("body","edited exported body");assertEquals(before,quote(op));
    }
    @Test public void cancelledNativeReadIdentityCannotBeAcceptedAgainByDeduplication()throws Exception{
        Object reads=construct("Requests",new Class<?>[]{});assertEquals("original-read",call(reads,"begin",new Class<?>[]{String.class},"original-read"));call(reads,"abandon",new Class<?>[]{String.class},"original-read");assertThrows(IllegalStateException.class,()->call(reads,"begin",new Class<?>[]{String.class},"original-read"));assertEquals("new-explicit-read",call(reads,"begin",new Class<?>[]{String.class},"new-explicit-read"));
    }
    @Test public void failedReadEndsWaitingClearsPreviousBodyAndKeepsThePinnedReference()throws Exception{
        Object op=operation();begin(op,"old",0);receive(op,"old",fixture().getJSONObject("current"));begin(op,"failed",0);assertTrue((Boolean)call(op,"fail",new Class<?>[]{String.class,String.class},"failed","原来源读取被拒绝"));assertNull(page(op));assertNull(call(op,"pendingId"));assertEquals("error",call(op,"status"));assertThrows(IllegalStateException.class,()->quote(op));assertFalse(receive(op,"failed",fixture().getJSONObject("current")));assertEquals(fixture().getJSONObject("request").getString("revision"),request(op,0).getString("revision"));
    }
}
