package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded credential-free GitHub reads; pin one commit before fetching only the selected subtree.
 * No clone, write, shell, dependency install, redirects, retries, background polling or secret lookup. */
public final class SkillGitClient {
    public static final int MAX_REQUESTS=64,MAX_TREE_ENTRIES=2048,MAX_DIRECTORIES=32,MAX_JSON_BYTES=1_100_000;
    private final URI api;private final Clock clock;
    private final OkHttpClient http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cache(null)
            .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).callTimeout(15,TimeUnit.SECONDS).build();
    public SkillGitClient(){this(URI.create("https://api.github.com"),Clock.systemUTC());}
    /** Package-private loopback protocol fixture seam; never selectable by imported/model data. */
    SkillGitClient(URI api,Clock clock){this.api=api;this.clock=clock;
        boolean fixture="http".equals(api.getScheme())&&Set.of("localhost","127.0.0.1","[::1]").contains(api.getHost());
        if(!("https".equals(api.getScheme())&&"api.github.com".equals(api.getHost())||fixture)||api.getRawUserInfo()!=null||api.getRawQuery()!=null||api.getRawFragment()!=null||!Set.of("","/").contains(api.getPath()))throw new IllegalArgumentException("Git API 来源无效");}
    public static final class Unavailable extends Exception {
        public final String code;public final int httpStatus;
        Unavailable(String code,int status){super(switch(code){case "gone"->"已读取该固定 commit，但所选技能目录或 SKILL.md 不在其中；本地技能保留。";case "network_unavailable"->"网络读取未完成，暂不能确认来源变化；不会把连接失败当作远端删除，本地技能保留。";case "rate_limited"->"Git 服务暂时限流，无法确认更新；本地技能保留，请稍后明确重新检查。";case "read_limited"->"所选 Git 技能超过本次完整读取限制；未导入局部内容，本地技能保留。";case "unsupported_entry"->"所选技能含链接、子模块或不支持的资源；未跟随或执行，本地技能保留。";default->"Git 来源暂不可完整核对；可能是网络、仓库/ref权限或数据问题，本地技能保留。";});this.code=code;httpStatus=status;}
    }
    private final class Fetch {
        final SkillGitSource source;final RunControl control;final long deadline=System.nanoTime()+45_000_000_000L;
        int requests,directories;long payloadBytes,wireBytes;final Map<String,JSONObject> trees=new HashMap<>();final JSONObject files=new JSONObject();final Set<String> paths=new HashSet<>();
        Fetch(SkillGitSource source,RunControl control){this.source=source;this.control=control;}
        void check()throws Exception{control.check();if(System.nanoTime()>=deadline)throw new Unavailable("read_limited",0);}
        Object get(String path)throws Exception {
            check();if(++requests>MAX_REQUESTS)throw new Unavailable("read_limited",0);URI target=api.resolve(path);if(!api.getHost().equals(target.getHost())||api.getPort()!=target.getPort()||!api.getScheme().equals(target.getScheme()))throw new SecurityException("Git 读取不能改 origin");
            var call=http.newCall(new Request.Builder().get().url(target.toURL()).header("Accept","application/vnd.github+json").header("User-Agent","PocketWorld-Skills").header("X-GitHub-Api-Version","2022-11-28").build());call.timeout().deadlineNanoTime(deadline);Runnable cancel=call::cancel;control.onCancel(cancel);
            try(var response=call.execute()){
                check();int status=response.code();if(status!=200)throw new Unavailable(status==429||status==403&&"0".equals(response.header("X-RateLimit-Remaining"))?"rate_limited":"source_unavailable",status);
                if(response.body()==null)throw new Unavailable("source_unavailable",status);
                try(InputStream input=response.body().byteStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] part=new byte[8192];int count;
                    while((count=input.read(part,0,Math.min(part.length,MAX_JSON_BYTES-bytes.size()+1)))!=-1){check();if(count==0)continue;wireBytes+=count;if(wireBytes>4_194_304)throw new Unavailable("read_limited",status);bytes.write(part,0,count);if(bytes.size()>MAX_JSON_BYTES)throw new Unavailable("read_limited",status);}
                    String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();org.json.JSONTokener tokenizer=new org.json.JSONTokener(json);Object value=tokenizer.nextValue();if(tokenizer.nextClean()!=0)throw new Unavailable("source_unavailable",status);return value;}
            }catch(java.io.IOException unavailable){control.check();throw new Unavailable("network_unavailable",0);}finally{control.removeCancelAction(cancel);}
        }
        JSONObject tree(String sha)throws Exception{
            check();if(trees.containsKey(sha))return trees.get(sha);Object data=get("/repos/"+source.slug()+"/git/trees/"+sha);if(!(data instanceof JSONObject))throw new Unavailable("source_unavailable",0);JSONObject tree=(JSONObject)data;
            if(!sha.equals(tree.optString("sha"))||!Boolean.FALSE.equals(tree.opt("truncated"))||tree.getJSONArray("tree").length()>MAX_TREE_ENTRIES)throw new Unavailable("read_limited",0);
            trees.put(sha,tree);return tree;
        }
        String subtree(String root)throws Exception{
            String current=root;for(String name:source.subpath.isEmpty()?new String[0]:source.subpath.split("/")){
                JSONArray entries=tree(current).getJSONArray("tree");JSONObject next=null;for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);if(name.equals(row.optString("path"))){if(next!=null)throw new Unavailable("source_unavailable",0);next=row;}}
                if(next==null)throw new Unavailable("gone",0);if(!"tree".equals(next.optString("type"))||!"040000".equals(next.optString("mode")))throw new Unavailable("unsupported_entry",0);current=sha(next.getString("sha"));
            }return current;
        }
        void collect(String treeSha,String prefix,int depth)throws Exception{
            check();if(depth>12||++directories>MAX_DIRECTORIES)throw new Unavailable("read_limited",0);JSONArray entries=tree(treeSha).getJSONArray("tree");
            for(int i=0;i<entries.length();i++){
                JSONObject row=entries.getJSONObject(i);String leaf=row.getString("path");if(leaf.contains("/")||!paths.add(prefix+leaf))throw new Unavailable("source_unavailable",0);String path=SkillBook.path(prefix+leaf),type=row.getString("type"),mode=row.getString("mode"),hash=sha(row.getString("sha"));
                if(type.equals("tree")&&mode.equals("040000")){collect(hash,path+"/",depth+1);continue;}
                if(!type.equals("blob")||!Set.of("100644","100755").contains(mode))throw new Unavailable("unsupported_entry",0);
                if(files.length()>=41)throw new Unavailable("read_limited",0);long expected=integer(row.opt("size"));if(expected>256000||payloadBytes+expected>1_000_000)throw new Unavailable("read_limited",0);
                Object data=get("/repos/"+source.slug()+"/git/blobs/"+hash);if(!(data instanceof JSONObject))throw new Unavailable("source_unavailable",0);JSONObject blob=(JSONObject)data;
                if(!hash.equals(blob.optString("sha"))||!"base64".equals(blob.optString("encoding"))||integer(blob.opt("size"))!=expected)throw new Unavailable("source_unavailable",0);
                String encoded=blob.getString("content");if(encoded.length()>360000)throw new Unavailable("read_limited",0);String compact=encoded.replace("\n","").replace("\r","");byte[] bytes;
                try{bytes=Base64.getDecoder().decode(compact);}catch(IllegalArgumentException invalid){throw new Unavailable("source_unavailable",0);}
                if(bytes.length!=expected||!blobHash(bytes).equals(hash))throw new Unavailable("source_unavailable",0);payloadBytes+=bytes.length;
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();files.put(path,text);
            }
        }
    }
    public SkillGitSource.Fetched fetch(SkillGitSource source,RunControl original)throws Exception {
        if(source==null||original==null)throw new IllegalArgumentException("请提供原生 Git 来源与取消控制");Fetch fetch=new Fetch(source,original);
        try{Object response=fetch.get("/repos/"+source.slug()+"/commits?sha="+URLEncoder.encode(source.ref,"UTF-8")+"&per_page=1");if(!(response instanceof JSONArray)||((JSONArray)response).length()!=1)throw new Unavailable("source_unavailable",0);
            JSONObject commit=((JSONArray)response).getJSONObject(0);String id=sha(commit.getString("sha")),tree=sha(commit.getJSONObject("commit").getJSONObject("tree").getString("sha"));if(source.ref.matches("[a-f0-9]{40}")&&!source.ref.equals(id))throw new Unavailable("source_unavailable",0);fetch.collect(fetch.subtree(tree),"",0);fetch.check();if(!fetch.files.has("SKILL.md"))throw new Unavailable("gone",0);
            return new SkillGitSource.Fetched(source,id,fetch.files,clock.millis());
        }catch(Unavailable|InterruptedException|java.util.concurrent.CancellationException known){throw known;}catch(Exception invalid){original.check();throw new Unavailable("source_unavailable",0);}
    }
    private static String sha(String value)throws Unavailable{if(value==null||!value.matches("[a-f0-9]{40}"))throw new Unavailable("source_unavailable",0);return value;}
    private static long integer(Object value)throws Unavailable{if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0)throw new Unavailable("source_unavailable",0);return ((Number)value).longValue();}
    static String blobHash(byte[] bytes)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-1");hash.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));hash.update(bytes);StringBuilder text=new StringBuilder();for(byte b:hash.digest())text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
}
