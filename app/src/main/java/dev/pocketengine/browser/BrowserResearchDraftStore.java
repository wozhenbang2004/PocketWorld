package dev.pocketengine.browser;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original browser module's private owner text drafts. No tasks, credentials, consent or execution state. */
public final class BrowserResearchDraftStore {
    static final int MAX_DRAFTS=64,MAX_BYTES=1200000;
    // Only the native module constructs this fixed private file path. Strong process-lifetime keys
    // are intentional: collecting the first editor must never split a still-live editor's CAS lock.
    private static final java.util.concurrent.ConcurrentMap<String,Object> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
    private final File file;private final Object lock;
    public BrowserResearchDraftStore(File file){
        try{this.file=file.getCanonicalFile();}catch(java.io.IOException unavailable){throw new IllegalArgumentException("原浏览草稿路径不可核对",unavailable);}
        lock=LOCKS.computeIfAbsent(this.file.getPath(),key->new Object());
    }
    static void context(String profile,String task){if(profile==null||!profile.matches("owner-default|profile-[a-f0-9-]{36}"))throw new IllegalArgumentException("草稿需要原浏览资料");if(task==null)throw new IllegalArgumentException("草稿上下文缺失");if(!task.isEmpty())BrowserTaskSpace.uuid(task);}
    public JSONObject read(String profile,String task)throws Exception {synchronized(lock){context(profile,task);JSONObject state=load();return projection(state,profile,task);}}
    public JSONObject save(String profile,String task,long expectedRevision,String goal)throws Exception {synchronized(lock){
        context(profile,task);if(goal==null||goal.length()>4000||!unicode(goal))throw new IllegalArgumentException("研究目标草稿最多4000字，需完整Unicode文字");JSONObject state=load();if(expectedRevision<0||state.getLong("revision")!=expectedRevision)throw new IllegalStateException("原研究草稿版本已变化；当前输入仍保留，请重新核对后保存");
        JSONArray rows=state.getJSONArray("drafts"),next=new JSONArray();boolean found=false;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(profile.equals(row.getString("profileId"))&&task.equals(row.getString("taskId"))){found=true;continue;}next.put(row);}
        long now=System.currentTimeMillis();if(!goal.isEmpty()){if(!found&&next.length()>=MAX_DRAFTS)throw new IllegalStateException("研究草稿已达64份，请先明确清空不用的草稿；当前输入未丢弃");next.put(new JSONObject().put("profileId",profile).put("taskId",task).put("text",goal).put("updatedAt",now));}
        // A durable global CAS revision remains even after clear. Old editors cannot revive deleted text.
        JSONObject updated=new JSONObject().put("version",1).put("revision",Math.addExact(expectedRevision,1L)).put("drafts",next);persist(updated);return projection(updated,profile,task);
    }}
    public void clearProfile(String profile)throws Exception {synchronized(lock){
        context(profile,"");JSONObject state=load();JSONArray rows=state.getJSONArray("drafts"),next=new JSONArray();for(int i=0;i<rows.length();i++)if(!profile.equals(rows.getJSONObject(i).getString("profileId")))next.put(rows.getJSONObject(i));
        // Bump even for an empty profile: pending old saves must not resurrect a deleted profile's draft.
        persist(new JSONObject().put("version",1).put("revision",Math.addExact(state.getLong("revision"),1L)).put("drafts",next));
    }}
    private JSONObject load()throws Exception {
        if(!file.exists())return new JSONObject().put("version",1).put("revision",0L).put("drafts",new JSONArray());
        if(!file.isFile()||file.length()>MAX_BYTES)throw new IllegalStateException("原浏览草稿文件不可读或过大，未覆盖原件");JSONObject state=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));if(state.length()!=3||state.getInt("version")!=1||state.getLong("revision")<0)throw new IllegalStateException("原浏览草稿版本不可核对，未重置原件");JSONArray rows=state.getJSONArray("drafts");if(rows.length()>MAX_DRAFTS)throw new IllegalStateException("原浏览草稿数量不可核对");Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.length()!=4)throw new IllegalStateException("原草稿含非文字/身份字段，未恢复授权");context(row.getString("profileId"),row.getString("taskId"));String goal=row.getString("text");if(goal.isEmpty()||goal.length()>4000||!unicode(goal)||row.getLong("updatedAt")<0||!seen.add(row.getString("profileId")+"|"+row.getString("taskId")))throw new IllegalStateException("原研究草稿内容不可核对");}return state;
    }
    private static JSONObject projection(JSONObject state,String profile,String task)throws Exception {
        String text="";long updated=0;JSONArray rows=state.getJSONArray("drafts");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(profile.equals(row.getString("profileId"))&&task.equals(row.getString("taskId"))){text=row.getString("text");updated=row.getLong("updatedAt");break;}}
        return new JSONObject().put("profileId",profile).put("taskId",task).put("text",text).put("revision",state.getLong("revision")).put("updatedAt",updated).put("draftOnly",true).put("executionAuthorized",false);
    }
    private void persist(JSONObject state)throws Exception {
        byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IllegalStateException("原浏览草稿超出预算，当前输入未丢弃");File parent=file.getParentFile();if(parent==null||!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("原浏览私有草稿目录不可用");File temporary=File.createTempFile("research-draft-",".tmp",parent);
        try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){throw new IllegalStateException("原目录不支持原子保存；旧草稿未替换，当前输入尚未保存。",unsupported);}}finally{Files.deleteIfExists(temporary.toPath());java.util.Arrays.fill(bytes,(byte)0);}
    }
    private static boolean unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
}
