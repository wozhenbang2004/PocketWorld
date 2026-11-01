package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Same original input/reply semantics using APIs available at the declared minSdk 30. */
public final class RunnerMin30CompatibilityTest {
    private static JSONArray append(String text)throws Exception{
        JSONArray history=new JSONArray();Method append=AgentRunner.class.getDeclaredMethod("appendOwnerInputs",JSONArray.class,JSONArray.class,ModelConfig.class);append.setAccessible(true);
        append.invoke(null,history,new JSONArray().put(new JSONObject().put("messageId","01234567-89ab-cdef-0123-456789abcdef").put("mode","queue").put("content",text)),new ModelConfig("","","fixture",false,5,10));return history;
    }
    @Test public void inputBlankCheckKeepsUnicodeWhitespaceAndDoesNotRejectNonBreakingSpaceOrSupplementaryText()throws Exception{
        for(String blank:List.of(""," \t\n","\u2003\u3000"))try{append(blank);fail("blank native input accepted");}catch(InvocationTargetException rejected){assertTrue(rejected.getCause() instanceof SecurityException);}
        for(String text:List.of("\u00a0","\ud83e\uddea 原补充"))assertEquals(text,append(text).getJSONObject(0).getString("content"));
    }
    private static AgentRunner.OwnerReply reply(List<String> ids)throws Exception{
        Constructor<AgentRunner.OwnerReply> constructor=AgentRunner.OwnerReply.class.getDeclaredConstructor(String.class,String.class,List.class);constructor.setAccessible(true);return constructor.newInstance("original-task","actual reply",ids);
    }
    @Test public void originalOwnerReplyIdsAreAnImmutableSnapshotAndStillRejectNullMembers()throws Exception{
        List<String> source=new ArrayList<>(List.of("first","second"));AgentRunner.OwnerReply reply=reply(source);source.clear();assertEquals(List.of("first","second"),reply.messageIds());assertThrows(UnsupportedOperationException.class,()->reply.messageIds().add("later"));
        try{reply(Arrays.asList("first",null));fail("List.copyOf null rejection lost");}catch(InvocationTargetException rejected){assertTrue(rejected.getCause() instanceof NullPointerException);}
    }
    @SuppressWarnings("unchecked")
    @Test public void originalRequestOwnerIdsRemainOrderedDeduplicatedImmutableAndIndependentOfLaterHistory()throws Exception{
        JSONArray history=new JSONArray().put(new JSONObject().put("ownerMessageId","first")).put(new JSONObject().put("ownerMessageId","second")).put(new JSONObject().put("ownerMessageId","first"));
        Method method=AgentRunner.class.getDeclaredMethod("ownerMessageIds",JSONArray.class);method.setAccessible(true);List<String> ids=(List<String>)method.invoke(null,history);history.put(new JSONObject().put("ownerMessageId","later"));
        assertEquals(List.of("first","second"),ids);assertThrows(UnsupportedOperationException.class,()->ids.remove(0));
    }
}
