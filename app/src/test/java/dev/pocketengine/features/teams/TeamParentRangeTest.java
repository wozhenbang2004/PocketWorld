package dev.pocketengine.features.teams;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TeamParentRangeTest {
    private static JSONObject range(String text,int offset,int length)throws Exception {
        Method method=TeamParentHost.class.getDeclaredMethod("range",String.class,int.class,int.class);method.setAccessible(true);
        try{return (JSONObject)method.invoke(null,text,offset,length);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}
    }
    @Test public void oneCodeUnitCannotReturnEmptyHasMoreForEmoji()throws Exception {
        try{range("\uD83D\uDE03tail",0,1);fail("must not report a zero-progress page");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Unicode"));}
        try{range("a\uD83D\uDE03",2,2);fail("must not begin at the low surrogate");}catch(IllegalArgumentException expected){}
    }
    @Test public void completeEmojiAndMixedPagesKeepExactBoundaries()throws Exception {
        String text="a\uD83D\uDE03b";JSONObject first=range(text,0,2);assertEquals("a",first.getString("content"));assertEquals(1,first.getInt("endOffset"));assertTrue(first.getBoolean("hasMore"));
        JSONObject emoji=range(text,first.getInt("endOffset"),2);assertEquals("\uD83D\uDE03",emoji.getString("content"));assertEquals(3,emoji.getInt("endOffset"));assertTrue(emoji.getBoolean("hasMore"));
        JSONObject last=range(text,emoji.getInt("endOffset"),1);assertEquals("b",last.getString("content"));assertEquals(text.length(),last.getInt("endOffset"));assertFalse(last.getBoolean("hasMore"));
    }
}
