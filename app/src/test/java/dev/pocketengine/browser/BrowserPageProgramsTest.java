package dev.pocketengine.browser;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowserPageProgramsTest {
    @Test public void calculationsAreFiniteAndDoNotExpandToArbitraryJavascript()throws Exception {
        String source="{\"op\":\"sum\",\"input\":{\"op\":\"map\",\"input\":{\"op\":\"queryAll\",\"selector\":\".price\"},\"body\":{\"op\":\"number\",\"input\":{\"op\":\"text\",\"input\":{\"op\":\"item\"}}}}}";
        BrowserPagePrograms.validateExpression(source);assertTrue(BrowserPagePrograms.expression(source).contains("expression_budget"));
        assertThrows(Exception.class,()->BrowserPagePrograms.validateExpression("{\"op\":\"fetch\",\"value\":\"/private\"}"));
        for(String selector:new String[]{"input[value^=a]","input:checked","input[\\\\76alue]","[value = secret]","input:valid","div:has(input[value])"})assertThrows(Exception.class,()->BrowserPagePrograms.validateExpression(new JSONObject().put("op","queryAll").put("selector",selector).toString()));
        BrowserPagePrograms.validateSelector("main .price[data-currency='USD']");
    }
    @Test public void collectionParametersCannotBecomeLoopsOrPropertyAccess()throws Exception {
        for(JSONObject expression:new JSONObject[]{new JSONObject().put("op","slice").put("start",-1),new JSONObject().put("op","slice").put("limit",201),new JSONObject().put("op","sort").put("descending","true"),new JSONObject().put("op","attr").put("name","value")})assertThrows(Exception.class,()->BrowserPagePrograms.validateExpression(expression.toString()));
    }
}
