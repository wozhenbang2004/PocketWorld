package dev.pocketengine.browser;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-owned profile metadata only; never a task journal, credential store or cookie export. */
public final class BrowserProfileRegistry {
    private final File file;
    private JSONObject state;
    private Session session;

    BrowserProfileRegistry(File file)throws Exception {
        this.file=file;
        if(file.isFile()){
            state=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            if(state.getInt("version")!=1)throw new IllegalStateException("浏览器资料版本不受支持");
            JSONArray profiles=state.getJSONArray("profiles");
            if(profiles.length()>128)throw new IllegalStateException("浏览器资料记录过多");
            for(int i=0;i<profiles.length();i++){
                JSONObject profile=profiles.getJSONObject(i);
                if(!profile.getString("providerName").matches("pw_[a-f0-9]{32}")||!profile.getString("id").matches("owner-default|profile-[a-f0-9-]{36}"))throw new IllegalStateException("浏览器资料记录无效");
                storage(profile,false);if(profile.has("ownerHistory"))validateHistory(profile.getJSONArray("ownerHistory"));
            }
        }else{
            state=new JSONObject().put("version",1).put("revision",1L).put("selectedProfileId",BrowserLease.PROFILE)
                    .put("profiles",new JSONArray().put(profile(BrowserLease.PROFILE,"个人浏览资料",1L)));
            persist(state);
        }
    }

    public synchronized JSONObject snapshot(boolean multiProfile)throws Exception {
        JSONArray profiles=new JSONArray();JSONArray source=state.getJSONArray("profiles");String selected=state.optString("selectedProfileId"),selectedIsolation="unavailable";
        for(int i=0;i<source.length();i++){
            JSONObject raw=source.getJSONObject(i);String id=raw.getString("id");
            String storage=storage(raw,multiProfile);
            if(id.equals(selected)&&raw.getString("status").equals("active")&&(multiProfile||storage.equals("legacy-default")))selectedIsolation=storage.equals("androidx")?"androidx-multi-profile":"default-webview-only";
            profiles.put(new JSONObject().put("id",id).put("label",raw.getString("label")).put("revision",raw.getLong("revision"))
                    .put("status",raw.getString("status")).put("selected",id.equals(selected)).put("ownerSpaceId","private")
                    .put("available",raw.getString("status").equals("active")&&(multiProfile||storage.equals("legacy-default")))
                    .put("storageKind",storage).put("profileIsolation",storage.equals("androidx")?"androidx-multi-profile":"default-webview-only")
                    .put("deletable",multiProfile&&storage.equals("androidx"))
                    .put("deletionDetail",raw.optString("deletionDetail")));
        }
        return new JSONObject().put("revision",revision()).put("selectedProfileId",selected).put("profiles",profiles)
                .put("multiProfileSupported",multiProfile).put("ownerManagementOnly",true).put("sharedWithSystemChrome",false)
                .put("profileIsolation",selectedIsolation);
    }

    public synchronized long revision(){return state.optLong("revision");}
    public synchronized String selectedId(){return state.optString("selectedProfileId");}
    public synchronized void requireRevision(long expected){if(expected!=revision())throw new IllegalStateException("浏览器资料已变化，请刷新后重新确认");}

    synchronized JSONObject create(String label,long expected,boolean supported)throws Exception {
        requireRevision(expected);if(!supported)throw new UnsupportedOperationException("此WebView不支持独立多资料，不能创建隔离会话");
        String name=label==null?"":label.trim();if(name.isEmpty()||name.length()>60||name.matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("资料名称须为1至60个可见字符");
        JSONArray all=state.getJSONArray("profiles");int active=0;for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("status").equals("active"))active++;
        if(active>=12||all.length()>=128)throw new IllegalStateException("浏览器资料数量已达上限");
        JSONObject next=copy(state);long revision=revision()+1;JSONObject created=profile("profile-"+UUID.randomUUID(),name,revision);
        next.getJSONArray("profiles").put(created);next.put("revision",revision);save(next);return visible(created);
    }

    synchronized void select(String id,long expected,boolean supported)throws Exception {
        requireRevision(expected);JSONObject profile=required(id);requireActive(profile,supported);
        invalidateSession();JSONObject next=copy(state).put("selectedProfileId",id).put("revision",revision()+1);save(next);
    }

    synchronized void requestDelete(String id,long expected,boolean supported)throws Exception {
        requireRevision(expected);JSONObject current=required(id);
        if(!current.getString("status").equals("active"))throw new IllegalStateException("此资料已停用，请查看清理状态");
        if(!supported||!storage(current,supported).equals("androidx"))throw new UnsupportedOperationException("此资料不能按隔离Profile清除；未声称删除默认WebView数据");
        if(session!=null&&session.profileId.equals(id))invalidateSession();
        JSONObject next=copy(state);long revision=revision()+1;
        find(next,id).put("status","delete_pending").put("deletionDetail","pending_provider_cleanup").put("revision",revision).remove("ownerHistory");
        next.put("revision",revision);if(id.equals(selectedId()))next.put("selectedProfileId","");save(next);
    }

    /** Native owner history in the existing private profile file, separate from task/source history.
     * Query/fragment are deliberately omitted; these rows cannot recover login redirects or unsaved forms. */
    synchronized JSONArray historyForOwner(Session expected)throws Exception {requireOwnerSession(expected);JSONArray rows=required(expected.profileId).optJSONArray("ownerHistory");return rows==null?new JSONArray():new JSONArray(rows.toString());}
    synchronized JSONObject visitForOwner(Session expected,String rawUrl,String title,long at,boolean closed)throws Exception {
        requireOwnerSession(expected);JSONObject target=historyTarget(rawUrl);if(target==null)return null;JSONObject next=copy(state),profile=find(next,expected.profileId);JSONArray prior=profile.optJSONArray("ownerHistory"),rows=new JSONArray();JSONObject existing=null;
        for(int i=0;prior!=null&&i<prior.length();i++){JSONObject row=prior.getJSONObject(i);if(row.getString("url").equals(target.getString("url")))existing=copy(row);else rows.put(copy(row));}
        JSONObject row=existing==null?new JSONObject().put("id",UUID.randomUUID().toString()).put("bookmark",false):existing;
        String safeTitle=title==null?"":title;if(rawUrl!=null&&!rawUrl.isEmpty()&&(rawUrl.startsWith(safeTitle)||safeTitle.contains(rawUrl)))safeTitle=target.getString("url");
        row.put("url",target.getString("url")).put("urlQueryOmitted",target.getBoolean("urlQueryOmitted")).put("title",clip(safeTitle,240)).put("visitedAt",Math.max(1,at)).put("closed",closed);
        JSONArray newest=new JSONArray().put(row);for(int i=0;i<rows.length()&&newest.length()<100;i++)newest.put(rows.getJSONObject(i));
        // A bookmark is never evicted merely because recent browsing filled the bounded history.
        if(rows.length()>=100){for(int i=99;i<rows.length();i++)if(rows.getJSONObject(i).getBoolean("bookmark")){int remove=-1;for(int j=newest.length()-1;j>0;j--)if(!newest.getJSONObject(j).getBoolean("bookmark")){remove=j;break;}if(remove<0)throw new IllegalStateException("书签数量已到上限，请先整理");newest.remove(remove);newest.put(rows.getJSONObject(i));}}
        validateHistory(newest);profile.put("ownerHistory",newest);save(next);return copy(row);
    }
    synchronized JSONObject historyItemForOwner(Session expected,String id)throws Exception {requireOwnerSession(expected);BrowserTaskSpace.uuid(id);JSONArray rows=historyForOwner(expected);for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(id))return copy(rows.getJSONObject(i));throw new IllegalStateException("这条浏览记录已被清除，未替换成其他网页");}
    synchronized boolean bookmarkForOwner(Session expected,String id)throws Exception {requireOwnerSession(expected);JSONObject next=copy(state),profile=find(next,expected.profileId);JSONArray rows=profile.optJSONArray("ownerHistory");int count=0;for(int i=0;rows!=null&&i<rows.length();i++)if(rows.getJSONObject(i).getBoolean("bookmark"))count++;
        for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("id").equals(id)){boolean value=!row.getBoolean("bookmark");if(value&&count>=30)throw new IllegalStateException("每份资料最多30个书签，请先整理");row.put("bookmark",value);save(next);return value;}}throw new IllegalStateException("这条浏览记录已变化，请刷新");}
    synchronized void clearHistoryForOwner(Session expected,boolean bookmarksToo)throws Exception {requireOwnerSession(expected);JSONObject next=copy(state),profile=find(next,expected.profileId);JSONArray prior=profile.optJSONArray("ownerHistory"),kept=new JSONArray();for(int i=0;!bookmarksToo&&prior!=null&&i<prior.length();i++)if(prior.getJSONObject(i).getBoolean("bookmark"))kept.put(copy(prior.getJSONObject(i)));profile.put("ownerHistory",kept);save(next);}
    private void requireOwnerSession(Session expected)throws Exception {if(expected==null||session!=expected||!expected.current()||!selectedId().equals(expected.profileId)||!required(expected.profileId).getString("status").equals("active"))throw new SecurityException("浏览记录所属资料已变化，请重新打开");}
    private static JSONObject historyTarget(String raw)throws Exception {java.net.URI u;try{u=new java.net.URI(raw==null?"":raw);}catch(Exception invalid){return null;}if(!java.util.Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||raw.length()>8192)return null;String clean=new java.net.URI(u.getScheme()+"://"+u.getRawAuthority()+(u.getRawPath()==null?"":u.getRawPath())).toASCIIString();if(clean.length()>2048)return null;return new JSONObject().put("url",clean).put("urlQueryOmitted",u.getRawQuery()!=null||u.getRawFragment()!=null);}
    private static void validateHistory(JSONArray rows)throws Exception {if(rows.length()>100)throw new IllegalStateException("浏览历史数量超限");java.util.Set<String> ids=new java.util.HashSet<>();int bookmarks=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.length()!=7||!ids.add(row.getString("id")))throw new IllegalStateException("浏览历史身份无效");BrowserTaskSpace.uuid(row.getString("id"));JSONObject target=historyTarget(row.getString("url"));if(target==null||!target.getString("url").equals(row.getString("url"))||row.getString("title").length()>240||row.getLong("visitedAt")<1||!(row.get("bookmark") instanceof Boolean)||!(row.get("closed") instanceof Boolean)||!(row.get("urlQueryOmitted") instanceof Boolean))throw new IllegalStateException("浏览历史内容无效");if(row.getBoolean("bookmark"))bookmarks++;}if(bookmarks>30)throw new IllegalStateException("书签数量超限");}
    private static String clip(String value,int max){if(value==null)return "";int end=Math.min(value.length(),max);if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;return value.substring(0,end);}

    synchronized JSONArray pendingDeletes()throws Exception {
        JSONArray pending=new JSONArray(),all=state.getJSONArray("profiles");
        for(int i=0;i<all.length();i++){JSONObject profile=all.getJSONObject(i);if(profile.getString("status").equals("delete_pending"))pending.put(copy(profile));}return pending;
    }

    synchronized void deletionResult(String id,String detail,boolean accepted)throws Exception {
        JSONObject current=required(id);if(!current.getString("status").equals("delete_pending"))return;
        if(detail.equals(current.optString("deletionDetail"))&&!accepted)return;
        JSONObject next=copy(state);find(next,id).put("deletionDetail",detail).put("status",accepted?"disabled":"delete_pending");
        next.put("revision",revision()+1);save(next);
    }

    synchronized Session openSession(boolean supported)throws Exception {
        JSONObject profile=required(selectedId());requireActive(profile,supported);invalidateSession();
        if(profile.optString("storageKind","unbound").equals("unbound")){
            JSONObject next=copy(state);long revision=revision()+1;find(next,selectedId()).put("storageKind",storage(profile,supported)).put("revision",revision);next.put("revision",revision);save(next);profile=required(selectedId());
        }
        session=new Session(profile.getString("id"),profile.getLong("revision"),profile.getString("providerName"),storage(profile,supported).equals("androidx"));return session;
    }
    synchronized void endSession(Session expected){if(expected!=null)expected.invalidate();if(session==expected)session=null;}
    private void invalidateSession(){if(session!=null)session.invalidate();session=null;}
    private void requireActive(JSONObject profile,boolean supported)throws Exception {
        if(!profile.getString("status").equals("active"))throw new SecurityException("浏览器资料已停用，不能恢复旧会话");
        if(!supported&&!storage(profile,supported).equals("legacy-default"))throw new UnsupportedOperationException("当前WebView不能隔离所选资料，请主人另行选择兼容资料");
    }
    private JSONObject required(String id)throws Exception {return find(state,id);}
    private static JSONObject find(JSONObject state,String id)throws Exception {
        JSONArray all=state.getJSONArray("profiles");for(int i=0;i<all.length();i++){JSONObject profile=all.getJSONObject(i);if(profile.getString("id").equals(id))return profile;}
        throw new IllegalArgumentException("请选择仍可用的浏览器资料");
    }
    private static String storage(JSONObject profile,boolean supported){
        Object raw=profile.opt("storageKind");if(!(raw instanceof String))throw new IllegalStateException("浏览器资料存储格式无效，未替换为默认会话");String kind=(String)raw;
        if(!java.util.Set.of("androidx","legacy-default","unbound").contains(kind))throw new IllegalStateException("未知浏览器资料存储格式，未替换为默认会话");
        if(!kind.equals("androidx")&&!BrowserLease.PROFILE.equals(profile.optString("id")))throw new IllegalStateException("兼容浏览器资料身份无效");
        // A missing registry also occurs on a normal pre-profile upgrade. Preserve that jar;
        // only the native owner's explicit create/select chooses a separate named profile.
        return kind.equals("unbound")?"legacy-default":kind;
    }
    private static JSONObject profile(String id,String label,long revision)throws Exception {return new JSONObject().put("id",id).put("label",label).put("revision",revision).put("providerName","pw_"+UUID.randomUUID().toString().replace("-","")).put("status","active").put("storageKind",BrowserLease.PROFILE.equals(id)?"legacy-default":"androidx");}
    private static JSONObject visible(JSONObject profile)throws Exception {JSONObject value=copy(profile);value.remove("providerName");return value;}
    private static JSONObject copy(JSONObject value)throws Exception {return new JSONObject(value.toString());}
    private void save(JSONObject next)throws Exception {persist(next);state=next;}
    private void persist(JSONObject next)throws Exception {
        File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("浏览器资料目录无法保存");
        File temporary=new File(parent,file.getName()+".pending");
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(next.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }

    /** Transient browser-view fence. A switch away and back always receives a different token. */
    public static final class Session implements BrowserTransferFence {
        public final String profileId,sessionId,providerName,isolation;
        public final long profileRevision;
        public final boolean multiProfile;
        private final AtomicBoolean active=new AtomicBoolean(true);
        private final java.util.concurrent.CopyOnWriteArrayList<Cleanup> cleanups=new java.util.concurrent.CopyOnWriteArrayList<>();
        private Session(String id,long revision,String providerName,boolean multiProfile){profileId=id;profileRevision=revision;this.providerName=providerName;this.multiProfile=multiProfile;sessionId=UUID.randomUUID().toString();isolation=multiProfile?"androidx-multi-profile":"default-webview-only";}
        public boolean current(){return active.get();}
        public void check()throws Exception {if(!current())throw new SecurityException("浏览器资料会话已失效");}
        private void invalidate(){active.set(false);for(Cleanup cleanup:cleanups)cleanup.run();cleanups.clear();}
        public AutoCloseable watch(Runnable action){Cleanup cleanup=new Cleanup(action);cleanups.add(cleanup);if(!current()){cleanups.remove(cleanup);cleanup.run();}return ()->cleanups.remove(cleanup);}
        private static final class Cleanup implements Runnable {
            private final Runnable action;private final AtomicBoolean ran=new AtomicBoolean();
            Cleanup(Runnable action){this.action=action;}
            @Override public void run(){if(ran.compareAndSet(false,true))try{action.run();}catch(RuntimeException ignored){}}
        }
        public JSONObject identity()throws Exception {return new JSONObject().put("profileId",profileId).put("profileRevision",profileRevision).put("browserSessionId",sessionId).put("profileIsolation",isolation);}
    }
}
