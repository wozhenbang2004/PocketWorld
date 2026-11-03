package dev.pocketengine.features.skills;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/** Owner-selected public Git source identity. Content provenance, never a tool or execution grant. */
public final class SkillGitSource {
    public final String repository,ref,subpath;
    public SkillGitSource(String repository,String ref,String subpath){
        URI uri;try{uri=URI.create(Objects.requireNonNull(repository));}catch(RuntimeException invalid){throw new IllegalArgumentException("请填写有效且不含凭据的 GitHub HTTPS 仓库地址");}
        if(!"https".equals(uri.getScheme())||!"github.com".equalsIgnoreCase(uri.getHost())||uri.getPort()!=-1||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException("请选择不含凭据的 GitHub HTTPS 仓库地址；本次不支持私有令牌或其他 Git 服务");
        String path=uri.getPath();if(path.endsWith(".git"))path=path.substring(0,path.length()-4);if(!path.matches("/[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))throw new IllegalArgumentException("仓库地址只填写 https://github.com/owner/repo，不包含分支或文件路径");
        for(String segment:path.substring(1).split("/"))if(segment.equals(".")||segment.equals(".."))throw new IllegalArgumentException("仓库名称无效");
        this.repository="https://github.com"+path;
        if(ref==null||ref.length()>200||!ref.matches("[A-Za-z0-9_][A-Za-z0-9_./-]*")||ref.contains("..")||ref.endsWith("/")||ref.contains("//")||ref.endsWith(".lock"))throw new IllegalArgumentException("请填写明确分支、标签或 commit（最多200个ASCII字符）；不会自动换默认分支");
        this.ref=ref;this.subpath=subpath==null?"":subpath;
        if(!this.subpath.isEmpty())SkillBook.path(this.subpath);
    }
    String slug(){return repository.substring("https://github.com/".length());}
    /** Normalize a pasted link before the existing pinned-source review. The owner supplies
     * the exact ref: guessing where a slash-containing branch ends can select another skill.
     * Stored source identities still go through the strict repository-only constructor. */
    public static SkillGitSource fromInput(String address,String ref,String subpath){
        URI uri;try{uri=URI.create(Objects.requireNonNull(address));}catch(RuntimeException invalid){throw new IllegalArgumentException("请填写有效且不含凭据的 GitHub HTTPS 链接");}
        if(address.length()>2048||!"https".equals(uri.getScheme())||uri.getPort()!=-1||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawPath()==null||uri.getRawPath().contains("%"))throw new IllegalArgumentException("GitHub 链接不能包含凭据、查询参数、片段或编码路径；请复制明确的原始路径");
        boolean raw="raw.githubusercontent.com".equalsIgnoreCase(uri.getHost());
        if(!raw&&!"github.com".equalsIgnoreCase(uri.getHost()))throw new IllegalArgumentException("只接受 github.com 或 raw.githubusercontent.com 的公开 HTTPS 链接");
        String path=uri.getPath();if(path.endsWith("/"))path=path.substring(0,path.length()-1);
        String[] parts=path.split("/",-1);
        if(parts.length<3||!parts[0].isEmpty())throw new IllegalArgumentException("链接中缺少明确的 GitHub 仓库");
        String repository="https://github.com/"+parts[1]+"/"+parts[2];
        // Also validates ref syntax before it is used as a path prefix.
        SkillGitSource checked=new SkillGitSource(repository,ref,"");
        if(!raw&&parts.length==3)return new SkillGitSource(checked.repository,ref,subpath);
        String kind=raw?"raw":parts.length>3?parts[3]:"";
        if(!Set.of("tree","blob","raw").contains(kind))throw new IllegalArgumentException("请选择仓库、tree 目录或 SKILL.md 的 blob/raw 链接");
        int prefix=raw?3:4;
        if(parts.length<=prefix)throw new IllegalArgumentException("链接中缺少分支、标签或 commit；请明确填写 ref");
        String tail=String.join("/",java.util.Arrays.copyOfRange(parts,prefix,parts.length));
        // GitHub's raw links may explicitly spell refs/heads or refs/tags.
        String selected=ref;
        if(!tail.equals(selected)&&!tail.startsWith(selected+"/")){
            if(raw||kind.equals("raw")){
                if(tail.startsWith("refs/heads/"+ref+"/"))selected="refs/heads/"+ref;
                else if(tail.startsWith("refs/tags/"+ref+"/"))selected="refs/tags/"+ref;
            }
        }
        if(!tail.equals(selected)&&!tail.startsWith(selected+"/"))throw new IllegalArgumentException("链接与填写的 ref 不一致；含斜杠的分支请完整填写，不能自动猜测或改用默认分支");
        String selectedPath=tail.equals(selected)?"":tail.substring(selected.length()+1);
        if(!kind.equals("tree")){
            if(!selectedPath.equals("SKILL.md")&&!selectedPath.endsWith("/SKILL.md"))throw new IllegalArgumentException("文件链接必须明确指向 SKILL.md；不会导入相邻目录或扩大到仓库根");
            selectedPath=selectedPath.equals("SKILL.md")?"":selectedPath.substring(0,selectedPath.length()-"/SKILL.md".length());
        }
        if(subpath!=null&&!subpath.isEmpty()&&!subpath.equals(selectedPath))throw new IllegalArgumentException("链接中的技能目录与另填子目录不一致，请核对后再检查");
        return new SkillGitSource(checked.repository,ref,selectedPath);
    }
    public JSONObject toJson()throws Exception{return new JSONObject().put("kind","github_public_git").put("repository",repository).put("ref",ref).put("subpath",subpath);}
    public static SkillGitSource fromJson(JSONObject value)throws Exception{
        Set<String> keys=new java.util.HashSet<>();value.keys().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("kind","repository","ref","subpath"))||!"github_public_git".equals(value.getString("kind")))throw new IllegalArgumentException("Git 来源格式无效");
        return new SkillGitSource(value.getString("repository"),value.getString("ref"),value.getString("subpath"));
    }
    @Override public boolean equals(Object other){return other instanceof SkillGitSource s&&repository.equals(s.repository)&&ref.equals(s.ref)&&subpath.equals(s.subpath);}
    @Override public int hashCode(){return Objects.hash(repository,ref,subpath);}
    /** Exact fetched revision. Only the native client can create this content-bearing object. */
    public static final class Fetched {
        public final SkillGitSource source;public final String commit,digest;public final long observedAt;private final String filesJson;
        Fetched(SkillGitSource source,String commit,JSONObject files,long observedAt)throws Exception{
            this.source=Objects.requireNonNull(source);if(commit==null||!commit.matches("[a-f0-9]{40}")||observedAt<1)throw new IllegalArgumentException("缺少原 Git commit 和观察时间");this.commit=commit;this.observedAt=observedAt;
            // Reuse the original complete text-package validator, not a second import format.
            String name=SkillManifest.parse(files.getString("SKILL.md")).getString("name");JSONObject checked=SkillArchive.read(new java.io.ByteArrayInputStream(SkillArchive.write(name,files)));
            filesJson=checked.toString();digest=SkillBook.digest(checked);
        }
        JSONObject files()throws Exception{return new JSONObject(filesJson);}
        public JSONObject view()throws Exception{return new JSONObject().put("source",source.toJson()).put("commit",commit).put("digest",digest).put("observedAt",observedAt).put("resources",SkillBook.resourceNames(files())).put("bodyIncluded",false).put("executionAuthorized",false);}
    }
}
