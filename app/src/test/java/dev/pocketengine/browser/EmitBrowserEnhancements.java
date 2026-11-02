package dev.pocketengine.browser;

import org.json.JSONObject;

/** Exact production programs for a separate real DOM test. Not an Android WebView/provider proof. */
public final class EmitBrowserEnhancements {
    public static void main(String[] args)throws Exception {
        JSONObject out=new JSONObject();
        out.put("publicText",BrowserPagePrograms.expression("{\"op\":\"text\",\"input\":{\"op\":\"query\",\"selector\":\"main\"}}"));
        if(args.length==0||!args[0].equals("baseline"))out.put("sum",BrowserPagePrograms.expression("{\"op\":\"sum\",\"input\":{\"op\":\"map\",\"input\":{\"op\":\"queryAll\",\"selector\":\".price\"},\"body\":{\"op\":\"number\",\"input\":{\"op\":\"text\",\"input\":{\"op\":\"item\"}}}}}"));
        if(args.length==0||!args[0].equals("baseline")){out.put("overflow",BrowserPagePrograms.expression("{\"op\":\"multiply\",\"left\":{\"op\":\"literal\",\"value\":1e308},\"right\":{\"op\":\"literal\",\"value\":1e308}}"));out.put("add",BrowserPagePrograms.expression("{\"op\":\"add\",\"left\":{\"op\":\"literal\",\"value\":12},\"right\":{\"op\":\"literal\",\"value\":8}}"));}
        out.put("inspect",BrowserPagePrograms.inspect());System.out.println(out);
    }
}
