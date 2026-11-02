package dev.pocketengine.browser;

import org.json.JSONObject;

/** Exact production programs for a separate real DOM test. Not an Android WebView/provider proof. */
public final class EmitBrowserEnhancements {
    public static void main(String[] args)throws Exception {
        JSONObject out=new JSONObject();
        out.put("publicText",BrowserPagePrograms.expression("{\"op\":\"text\",\"input\":{\"op\":\"query\",\"selector\":\"main\"}}"));
        if(args.length==0||!args[0].equals("baseline"))out.put("sum",BrowserPagePrograms.expression("{\"op\":\"sum\",\"input\":{\"op\":\"map\",\"input\":{\"op\":\"queryAll\",\"selector\":\".price\"},\"body\":{\"op\":\"number\",\"input\":{\"op\":\"text\",\"input\":{\"op\":\"item\"}}}}}"));
        out.put("inspect",BrowserPagePrograms.inspect());System.out.println(out);
    }
}
