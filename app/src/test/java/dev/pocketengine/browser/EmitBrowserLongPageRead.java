package dev.pocketengine.browser;

import org.json.JSONObject;

/** Emits the production read programs for the loopback Chromium regression, never synthetic DOM facts. */
public final class EmitBrowserLongPageRead {
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("verify")){
            JSONObject evidence=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[1])),java.nio.charset.StandardCharsets.UTF_8));org.json.JSONArray samples=evidence.getJSONArray("samples");
            for(int i=0;i<samples.length();i++){JSONObject raw=samples.getJSONObject(i).getJSONObject("raw");JSONObject projected=BrowserSemanticRead.result(raw);if(projected.optString("status").equals("observed"))BrowserSemanticRead.validateSourceFacts(BrowserSemanticRead.sourceFacts(projected.getJSONObject("read")));}
            System.out.println("Validated "+samples.length()+" captured read receipts with production result/source validators");return;
        }
        String nonce="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",namespace="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        JSONObject scripts=new JSONObject();
        for(String mode:new String[]{"outline","text","table","list"}){
            scripts.put(mode,BrowserSemanticRead.script(new JSONObject().put("mode",mode),"pw_long_page_fixture","fixture_epoch",nonce,namespace));
            scripts.put("cursor_"+mode,BrowserSemanticRead.script(new JSONObject().put("mode",mode).put("cursor","cccccccc-cccc-4ccc-8ccc-cccccccccccc:c0"),"pw_long_page_fixture","fixture_epoch",nonce,namespace));
        }
        scripts.put("query",BrowserSemanticRead.script(new JSONObject().put("mode","outline").put("query","长页尾端唯一中文词"),"pw_long_page_fixture","fixture_epoch",nonce,namespace));
        scripts.put("missing",BrowserSemanticRead.script(new JSONObject().put("mode","outline").put("query","全文从未存在的唯一词"),"pw_long_page_fixture","fixture_epoch",nonce,namespace));
        scripts.put("region_table",BrowserSemanticRead.script(new JSONObject().put("mode","table").put("regionRef",namespace+":r1"),"pw_long_page_fixture","fixture_epoch",nonce,namespace));
        scripts.put("region_list",BrowserSemanticRead.script(new JSONObject().put("mode","list").put("regionRef",namespace+":r1"),"pw_long_page_fixture","fixture_epoch",nonce,namespace));
        scripts.put("region_text",BrowserSemanticRead.script(new JSONObject().put("mode","text").put("regionRef",namespace+":r1"),"pw_long_page_fixture","fixture_epoch",nonce,namespace));
        System.out.print(scripts);
    }
}
