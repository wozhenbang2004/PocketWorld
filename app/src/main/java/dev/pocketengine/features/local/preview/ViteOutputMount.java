package dev.pocketengine.features.local.preview;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/** One canonical Vite subpath, derived from the immutable HTML rather than a caller or a live tree. */
public final class ViteOutputMount {
    public static final int MAX_ENTRY_BYTES = 256 * 1024;
    private static final String LEAF = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}";
    private static final Pattern MODULE = Pattern.compile("(/(?:[A-Za-z0-9][A-Za-z0-9._-]{0,63}/){1,4})(assets/" + LEAF + ")");
    private static final Pattern MEMBER = Pattern.compile("(?:assets/)?" + LEAF);
    private static final Set<String> RESOURCE_LINKS = Set.of("stylesheet","modulepreload","preload","icon","apple-touch-icon","mask-icon","manifest");
    private ViteOutputMount() {}

    static String rootedBase(byte[] html,Predicate<String> hasMember)throws Exception {
        if(html.length>MAX_ENTRY_BYTES)return "";
        return rootedBase(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(html)).toString(),hasMember);
    }

    /** Empty preserves the existing / and ./ paths. A recognized but unproven mount fails closed. */
    public static String rootedBase(String html,Predicate<String> hasMember) {
        if(!MODULE.matcher(html).find())return "";
        var page=Jsoup.parse(html);
        var modules=page.select("script[type=module]");
        for(var module:modules){
            var src=MODULE.matcher(module.attr("src"));
            if(!src.matches()||!(src.group(2).endsWith(".js")||src.group(2).endsWith(".mjs")))continue;
            if(modules.size()!=1||module.parent()!=page.head()||page.selectFirst("base")!=null)
                throw new SecurityException("原 Vite 子路径需要一个明确的模块入口，不采用其他页面基址");
            String base=src.group(1);
            for(var resource:page.select("script[src], img[src], source[src]")){
                String url=resource.attr("src");
                if(!"img".equals(resource.normalName())||!url.startsWith("data:image/"))requireMember(base,url,hasMember);
            }
            for(var link:page.select("link[href]")){
                String rel=link.attr("rel").toLowerCase(Locale.ROOT);
                String url=link.attr("href");
                for(String type:rel.split("\\s+")){
                    if(!RESOURCE_LINKS.contains(type))continue;
                    if(!"icon".equals(type)||!url.startsWith("data:image/"))requireMember(base,url,hasMember);
                    break;
                }
            }
            return base;
        }
        return "";
    }

    private static void requireMember(String base,String literal,Predicate<String> hasMember){
        String member=literal.startsWith("/")?relativeMember(base,literal):
            literal.startsWith("./")?literal.substring(2):literal;
        if(member==null||!MEMBER.matcher(member).matches()||!hasMember.test(member))
            throw new SecurityException("原 Vite 子路径引用的资源不在同一固定网页包中，未替换资源");
    }

    /** Look up only literal members of the one mounted resource root, not an alias at /. */
    static String relativeMember(String base,String literalPath) {
        if(!literalPath.startsWith(base))return null;
        String member=literalPath.substring(base.length());
        return MEMBER.matcher(member).matches()?member:null;
    }
}
