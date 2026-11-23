package dev.pocketengine.features.local.project;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/** Only the app-owned v2 starter with literal text fields is convertible. Never interpret user code. */
public final class PortableOfflinePage {
    private static final String DEFAULT_TITLE="我的第一份网页";
    private static final String DEFAULT_INTRO="改一改标题，保存后预览。点击下面的按钮试试看。";
    private static final String LINK="<link rel=\"stylesheet\" href=\"/src/style.css\">";
    private static final String SCRIPT="<script type=\"module\" src=\"/src/main.js\"></script>";
    private static final String CHARSET="<meta charset=\"UTF-8\">";
    private static final int MAX_INPUT_BYTES=4096,MAX_OUTPUT_BYTES=16*1024;
    // Code-owned alternatives for the original v2 stylesheet only. Mirror the exact
    // bytes in ProjectOfflinePagePalette; the registered starter source remains unchanged.
    private static final String PAPER_CSS_SUFFIX="""
        :root { color-scheme: light; }
        body { background-color: #f4f1ec; color: #211f1b; }
        button { background-color: #302b24; color: #fffaf1; border: 1px solid #302b24; }
        button:focus-visible { outline: 2px solid #44392e; outline-offset: 3px; }
        """;
    private static final String INK_CSS_SUFFIX="""
        :root { color-scheme: dark; }
        body { background-color: #121417; color: #f3f4f6; }
        button { background-color: #e6ebf4; color: #171a20; border: 1px solid #e6ebf4; }
        button:focus-visible { outline: 2px solid #ebeff5; outline-offset: 3px; }
        """;

    public record Copy(byte[] bytes,String sha256){
        public Copy{bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
        public int byteSize(){return bytes.length;}
    }

    public static Copy fromVerifiedStarter(byte[] entry,byte[] css,byte[] js)throws Exception{
        var template=ProjectLiveProjectTemplate.builtinSavedPageStarterFiles();
        String stylesheet=verifiedStylesheet(css,template.get("src/style.css")),counter=template.get("src/main.js");
        if(entry.length>MAX_INPUT_BYTES||stylesheet==null||!Arrays.equals(js,utf8(counter)))
            throw unavailable();
        String html=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(entry)).toString();
        requireLiteralFields(html,template.get("index.html"));
        // These exact code-owned stylesheets have no URLs; the exact stock counter has no imports.
        // Inlining removes the app-only /src paths and does not need a local server or module loader.
        String csp="<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'sha256-"+
            inlineHash(stylesheet)+"'; script-src 'sha256-"+inlineHash(counter)+"'; base-uri 'none'; form-action 'none'\">";
        if(!html.contains(CHARSET)||!html.contains(LINK)||!html.contains(SCRIPT))throw unavailable();
        byte[] result=utf8(html.replace(CHARSET,CHARSET+"\n    "+csp)
            .replace(LINK,"<style>"+stylesheet+"</style>").replace(SCRIPT,"<script>"+counter+"</script>"));
        if(result.length>MAX_OUTPUT_BYTES)throw unavailable();
        return new Copy(result,ProjectSources.sha256(result));
    }

    private static String verifiedStylesheet(byte[] saved,String stock){
        if(saved.length>MAX_INPUT_BYTES)return null;
        for(String known:new String[]{stock,stock+PAPER_CSS_SUFFIX,stock+INK_CSS_SUFFIX})
            if(Arrays.equals(saved,utf8(known)))return known; // Inline the code-owned literal, not arbitrary stored CSS.
        return null;
    }

    private static void requireLiteralFields(String html,String template){
        // Split the app-owned scaffold, then require the entire retained HTML to round trip.
        int first=template.indexOf(DEFAULT_TITLE),next=template.indexOf(DEFAULT_TITLE,first+DEFAULT_TITLE.length());
        int intro=template.indexOf(DEFAULT_INTRO,next+DEFAULT_TITLE.length());
        if(first<0||next<=first||intro<=next)throw unavailable();
        String start=template.substring(0,first),between=template.substring(first+DEFAULT_TITLE.length(),next),
            after=template.substring(next+DEFAULT_TITLE.length(),intro),end=template.substring(intro+DEFAULT_INTRO.length());
        if(!html.startsWith(start)||!html.endsWith(end))throw unavailable();
        int split=html.indexOf(between,start.length());
        if(split<0)throw unavailable();
        String escapedTitle=html.substring(start.length(),split);
        String prefix=start+escapedTitle+between+escapedTitle+after;
        int endAt=html.length()-end.length();
        if(!html.startsWith(prefix)||prefix.length()>endAt)throw unavailable();
        String escapedIntro=html.substring(prefix.length(),endAt);
        String title=decode(escapedTitle),description=decode(escapedIntro);
        if(title.isBlank()||!literal(title,80)||!literal(description,240)||
                !html.equals(start+encode(title)+between+encode(title)+after+encode(description)+end))throw unavailable();
    }

    private static boolean literal(String value,int limit){
        return value.codePointCount(0,value.length())<=limit&&value.codePoints().noneMatch(c->
            Character.isISOControl(c)||c==0x2028||c==0x2029||c>=0xd800&&c<=0xdfff);
    }
    private static String decode(String value){
        return value.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&amp;","&");
    }
    private static String encode(String value){
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    private static String inlineHash(String value)throws Exception{
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(utf8(value)));
    }
    private static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static SecurityException unavailable(){
        return new SecurityException("只可为原内置起步网页的已保留版本生成单文件副本；不改写其他内容");
    }
    private PortableOfflinePage(){}
}
