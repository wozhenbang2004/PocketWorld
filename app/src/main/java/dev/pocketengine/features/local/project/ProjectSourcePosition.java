package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Set;
import org.json.JSONObject;

/** One device-private locator for an explicit new-source publication. No file bytes, permission or outcome.
 * A fresh process must validate the plan and query the real checkout. Reading never creates a directory. */
public final class ProjectSourcePosition {
    public interface PlanCheck { void check(JSONObject plan)throws Exception; }
    private static final String FORMAT="pocketworld.phone-source-position.v1";
    private static final Set<String> KEYS=Set.of("format","selectionId","packageSha256","manifestIdentity","projectName",
        "clientImportId","mode","sourceEvidenceSha256","planDigest");
    private final File file;
    private final PlanCheck check;
    public ProjectSourcePosition(File originalDirectory,PlanCheck check){this.file=new File(originalDirectory,"owner-source-position-v1.json");this.check=check;}
    private void safe()throws Exception{
        if(!file.getAbsoluteFile().equals(file.getCanonicalFile())||Files.isSymbolicLink(file.toPath())||
            Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||file.length()>8192))
            throw new IllegalStateException("上次本机项目的创建定位无法核对；没有另建副本。已有项目可从项目目录查看。");
    }
    private JSONObject checked(JSONObject plan)throws Exception{
        if(plan==null||plan.length()!=KEYS.size()||!"new_project".equals(plan.opt("mode")))throw new SecurityException("原定位不是新的本机源码项目");
        for(String key:KEYS)if(!(plan.opt(key) instanceof String))throw new SecurityException("原本机项目定位不完整");
        check.check(plan);
        return new JSONObject(plan.toString());
    }
    public synchronized JSONObject read()throws Exception{
        safe();
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return new JSONObject().put("state","none");
        JSONObject raw=ProjectPrivateRecord.read(file,8192);
        if(!FORMAT.equals(raw.opt("format")))throw new SecurityException("上次本机项目的创建定位损坏；未重建或重复导入");
        if("released".equals(raw.opt("state"))&&raw.length()==4&&raw.optString("clientImportId").matches("[a-f0-9-]{36}")&&raw.optString("planDigest").matches("[a-f0-9]{64}"))return raw;
        if(!"located".equals(raw.opt("state"))||raw.length()!=3)throw new SecurityException("无法核对原创建定位；未重新执行");
        return new JSONObject().put("state","located").put("plan",checked(raw.getJSONObject("plan")));
    }
    private static boolean same(JSONObject a,JSONObject b)throws Exception{
        return a.getString("clientImportId").equals(b.getString("clientImportId"))&&a.getString("planDigest").equals(b.getString("planDigest"));
    }
    public synchronized void checkLookup(JSONObject expected)throws Exception{
        JSONObject plan=checked(expected),prior=read(),pin=prior.optJSONObject("plan");
        if(pin!=null&&!same(pin,plan)||"released".equals(prior.optString("state"))&&same(prior,plan))
            throw new SecurityException("这不是当前保留的原创建定位，请重新打开本机源码入口");
    }
    /** Must complete before the original importer is allowed to touch its project or checkout directories. */
    public synchronized void keep(JSONObject selected)throws Exception{
        JSONObject plan=checked(selected),prior=read(),pin=prior.optJSONObject("plan");
        if(pin!=null){if(!same(pin,plan))throw new IllegalStateException("请先从本机工程入口核对上次明确创建的副本；未覆盖原定位。");return;}
        if("released".equals(prior.optString("state"))&&same(prior,plan))throw new SecurityException("这次旧创建定位已明确结束，请重新核对来源");
        JSONObject value=new JSONObject().put("format",FORMAT).put("state","located").put("plan",plan);
        if(value.toString().getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException("原创建定位超过限制");
        JsonFiles.write(file,value);JSONObject saved=read().optJSONObject("plan");if(saved==null||!same(saved,plan))throw new IllegalStateException("未确认创建定位已保存，尚未开始发布");
    }
    /** The caller must query the actual original result first. Keeps a tiny dismissal so stale Activity state cannot revive it. */
    public synchronized void release(JSONObject expected)throws Exception{
        JSONObject plan=checked(expected),prior=read(),pin=prior.optJSONObject("plan");
        if(pin!=null&&!same(pin,plan))throw new SecurityException("当前保留的是另一份创建定位，没有改变它");
        if("released".equals(prior.optString("state"))&&same(prior,plan))return;
        safe();JsonFiles.write(file,new JSONObject().put("format",FORMAT).put("state","released")
            .put("clientImportId",plan.getString("clientImportId")).put("planDigest",plan.getString("planDigest")));
    }
}
