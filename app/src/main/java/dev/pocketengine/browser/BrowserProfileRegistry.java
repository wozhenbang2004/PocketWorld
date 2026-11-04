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
                storage(profile,false);if(profile.has("ownerHistory"))validateHistory(profile.getJSONArray("ownerHistory"));validatePrivateNavigations(profile);
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
                    .put("deletionDetail",raw.optString("deletionDetail")).put("exactUrlRetention",raw.optBoolean("exactUrlRetention")));
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

    /** Read-only owner preflight. The real mutation repeats this check under the same monitor. */
    synchronized void checkSelect(String id,long expected,boolean supported)throws Exception {
        requireRevision(expected);JSONObject profile=required(id);requireActive(profile,supported);
    }

    synchronized void select(String id,long expected,boolean supported)throws Exception {
        checkSelect(id,expected,supported);
        invalidateSession();JSONObject next=copy(state).put("selectedProfileId",id).put("revision",revision()+1);save(next);
    }

    synchronized void checkDelete(String id,long expected,boolean supported)throws Exception {
        requireRevision(expected);JSONObject current=required(id);
        if(!current.getString("status").equals("active"))throw new IllegalStateException("此资料已停用，请查看清理状态");
        if(!supported||!storage(current,supported).equals("androidx"))throw new UnsupportedOperationException("此资料不能按隔离Profile清除；未声称删除默认WebView数据");
    }

    synchronized void requestDelete(String id,long expected,boolean supported)throws Exception {
        checkDelete(id,expected,supported);
        if(session!=null&&session.profileId.equals(id))invalidateSession();
        JSONObject next=copy(state);long revision=revision()+1;
        find(next,id).put("status","delete_pending").put("deletionDetail","pending_provider_cleanup").put("revision",revision).remove("ownerHistory");
        find(next,id).remove("privateNavigations");find(next,id).remove("exactUrlRetention");
        next.put("revision",revision);if(id.equals(selectedId()))next.put("selectedProfileId","");save(next);
    }

    /** Native owner history contains display projections only. Exact destinations, when explicitly
     * enabled by this owner, live behind opaque refs in the same no-backup profile file. */
    synchronized JSONArray historyForOwner(Session expected)throws Exception {requireOwnerSession(expected);JSONArray rows=required(expected.profileId).optJSONArray("ownerHistory");return rows==null?new JSONArray():new JSONArray(rows.toString());}
    synchronized JSONObject visitForOwner(Session expected,String rawUrl,String title,long at,boolean closed)throws Exception {
        requireOwnerSession(expected);JSONObject target=historyTarget(rawUrl);if(target==null)return null;JSONObject next=copy(state),profile=find(next,expected.profileId);JSONArray prior=profile.optJSONArray("ownerHistory"),rows=new JSONArray();JSONObject existing=null;
        BrowserNavigationTarget destination=BrowserNavigationTarget.parse(rawUrl);String navigationId=destination.parameters&&profile.optBoolean("exactUrlRetention")?retainNavigation(profile,"history","","",destination.exactUrl):"";
        for(int i=0;prior!=null&&i<prior.length();i++){JSONObject row=prior.getJSONObject(i);boolean same=navigationId.isEmpty()?(!row.has("privateNavigationId")&&row.getString("url").equals(target.getString("url"))&&row.getBoolean("urlQueryOmitted")==target.getBoolean("urlQueryOmitted")):navigationId.equals(row.optString("privateNavigationId"));if(same)existing=copy(row);else rows.put(copy(row));}
        JSONObject row=existing==null?new JSONObject().put("id",UUID.randomUUID().toString()).put("bookmark",false):existing;
        row.remove("privateNavigationId");if(!navigationId.isEmpty())row.put("privateNavigationId",navigationId);
        row.put("url",target.getString("url")).put("urlQueryOmitted",target.getBoolean("urlQueryOmitted")).put("title",clip(destination.title(rawUrl,title),240)).put("visitedAt",Math.max(1,at)).put("closed",closed);
        JSONArray newest=new JSONArray().put(row);for(int i=0;i<rows.length()&&newest.length()<100;i++)newest.put(rows.getJSONObject(i));
        // A bookmark is never evicted merely because recent browsing filled the bounded history.
        if(rows.length()>=100){for(int i=99;i<rows.length();i++)if(rows.getJSONObject(i).getBoolean("bookmark")){int remove=-1;for(int j=newest.length()-1;j>0;j--)if(!newest.getJSONObject(j).getBoolean("bookmark")){remove=j;break;}if(remove<0)throw new IllegalStateException("书签数量已到上限，请先整理");newest.remove(remove);newest.put(rows.getJSONObject(i));}}
        validateHistory(newest);profile.put("ownerHistory",newest);pruneHistoryNavigations(profile);save(next);return copy(row);
    }
    synchronized JSONObject historyItemForOwner(Session expected,String id)throws Exception {requireOwnerSession(expected);BrowserTaskSpace.uuid(id);JSONArray rows=historyForOwner(expected);for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(id))return copy(rows.getJSONObject(i));throw new IllegalStateException("这条浏览记录已被清除，未替换成其他网页");}
    synchronized boolean bookmarkForOwner(Session expected,String id)throws Exception {requireOwnerSession(expected);JSONObject next=copy(state),profile=find(next,expected.profileId);JSONArray rows=profile.optJSONArray("ownerHistory");int count=0;for(int i=0;rows!=null&&i<rows.length();i++)if(rows.getJSONObject(i).getBoolean("bookmark"))count++;
        for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("id").equals(id)){boolean value=!row.getBoolean("bookmark");if(value&&count>=30)throw new IllegalStateException("每份资料最多30个书签，请先整理");row.put("bookmark",value);save(next);return value;}}throw new IllegalStateException("这条浏览记录已变化，请刷新");}
    synchronized void clearHistoryForOwner(Session expected,boolean bookmarksToo)throws Exception {requireOwnerSession(expected);JSONObject next=copy(state),profile=find(next,expected.profileId);JSONArray prior=profile.optJSONArray("ownerHistory"),kept=new JSONArray();for(int i=0;!bookmarksToo&&prior!=null&&i<prior.length();i++)if(prior.getJSONObject(i).getBoolean("bookmark"))kept.put(copy(prior.getJSONObject(i)));profile.put("ownerHistory",kept);pruneHistoryNavigations(profile);save(next);}
    private void requireOwnerSession(Session expected)throws Exception {if(expected==null||session!=expected||!expected.current()||!selectedId().equals(expected.profileId)||!required(expected.profileId).getString("status").equals("active"))throw new SecurityException("浏览记录所属资料已变化，请重新打开");}
    private static JSONObject historyTarget(String raw)throws Exception {BrowserNavigationTarget target=BrowserNavigationTarget.parse(raw);return target==null?null:new JSONObject().put("url",target.displayUrl).put("urlQueryOmitted",target.parameters);}
    private static void validateHistory(JSONArray rows)throws Exception {if(rows.length()>100)throw new IllegalStateException("浏览历史数量超限");java.util.Set<String> ids=new java.util.HashSet<>();int bookmarks=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);java.util.Set<String> keys=java.util.Set.of("id","url","urlQueryOmitted","title","visitedAt","bookmark","closed","privateNavigationId");for(var it=row.keys();it.hasNext();)if(!keys.contains(it.next()))throw new IllegalStateException("浏览历史含额外字段");if(row.length()!=(row.has("privateNavigationId")?8:7)||!ids.add(row.getString("id")))throw new IllegalStateException("浏览历史身份无效");BrowserTaskSpace.uuid(row.getString("id"));if(row.has("privateNavigationId"))BrowserTaskSpace.uuid(row.getString("privateNavigationId"));JSONObject target=historyTarget(row.getString("url"));if(target==null||!target.getString("url").equals(row.getString("url"))||row.getString("title").length()>240||row.getLong("visitedAt")<1||!(row.get("bookmark") instanceof Boolean)||!(row.get("closed") instanceof Boolean)||!(row.get("urlQueryOmitted") instanceof Boolean))throw new IllegalStateException("浏览历史内容无效");if(row.getBoolean("bookmark"))bookmarks++;}if(bookmarks>30)throw new IllegalStateException("书签数量超限");}

    synchronized boolean exactUrlsForOwner(Session expected)throws Exception {requireOwnerSession(expected);return required(expected.profileId).optBoolean("exactUrlRetention");}
    synchronized void exactUrlsForOwner(Session expected,boolean enabled)throws Exception {
        requireOwnerSession(expected);JSONObject next=copy(state),profile=find(next,expected.profileId);profile.put("exactUrlRetention",enabled);
        if(!enabled){profile.remove("privateNavigations");JSONArray rows=profile.optJSONArray("ownerHistory");for(int i=0;rows!=null&&i<rows.length();i++)rows.getJSONObject(i).remove("privateNavigationId");}
        next.put("revision",revision()+1);save(next);
    }
    synchronized JSONObject recoveryPageForOwner(Session expected,String taskId,String logicalPageId,String rawUrl,String title,String pageState,String openedBy)throws Exception {
        requireOwnerSession(expected);BrowserTaskSpace.uuid(taskId);JSONObject row=BrowserResearchPages.page(logicalPageId,rawUrl,title,pageState,openedBy);BrowserNavigationTarget target=BrowserNavigationTarget.parse(rawUrl);
        if(target!=null&&target.parameters&&exactUrlsForOwner(expected)){
            JSONObject next=copy(state),profile=find(next,expected.profileId);String id=retainNavigation(profile,"research",taskId,logicalPageId,target.exactUrl);row.put("privateNavigationId",id);JSONObject prior=required(expected.profileId).optJSONObject("privateNavigations");if(prior==null||!prior.has(id))save(next);
        }
        return row;
    }
    synchronized String recoveryUrlForOwner(Session expected,String taskId,JSONObject page)throws Exception {
        requireOwnerSession(expected);BrowserTaskSpace.uuid(taskId);BrowserTaskSpace.uuid(page.getString("logicalPageId"));
        return navigationUrl(required(expected.profileId),page,"research",taskId,page.getString("logicalPageId"));
    }
    synchronized String historyUrlForOwner(Session expected,String id)throws Exception {return navigationUrl(requiredOwnerProfile(expected),historyItemForOwner(expected,id),"history","","");}
    private JSONObject requiredOwnerProfile(Session expected)throws Exception {requireOwnerSession(expected);return required(expected.profileId);}
    private static String navigationUrl(JSONObject profile,JSONObject row,String kind,String taskId,String logicalPageId)throws Exception {
        String display=row.getString("url");BrowserNavigationTarget visible=BrowserNavigationTarget.parse(display);if(visible==null||visible.parameters||!display.equals(visible.displayUrl))throw new SecurityException("保存的网页地址无效");
        if(!row.has("privateNavigationId")){if(kind.equals("research")&&row.optBoolean("urlQueryOmitted"))throw new SecurityException("原页的查询参数或片段未保存，不能恢复原内容；请手动重新选择");return display;}
        String id=row.getString("privateNavigationId");BrowserTaskSpace.uuid(id);JSONObject all=profile.optJSONObject("privateNavigations"),saved=all==null?null:all.optJSONObject(id);
        if(!profile.optBoolean("exactUrlRetention")||saved==null||!kind.equals(saved.optString("kind"))||!taskId.equals(saved.optString("taskId"))||!logicalPageId.equals(saved.optString("logicalPageId")))throw new SecurityException("本页精确地址未保存或已清除，请在原网页重新选择；未改开其他内容");
        BrowserNavigationTarget exact=BrowserNavigationTarget.parse(saved.getString("url"));if(exact==null||!exact.parameters||!display.equals(exact.displayUrl))throw new SecurityException("原私有地址与页面身份不符，未改开其他内容");return exact.exactUrl;
    }
    private static String retainNavigation(JSONObject profile,String kind,String taskId,String logicalPageId,String exact)throws Exception {
        JSONObject all=profile.optJSONObject("privateNavigations");if(all==null){all=new JSONObject();profile.put("privateNavigations",all);}
        for(var it=all.keys();it.hasNext();){String id=it.next();JSONObject row=all.getJSONObject(id);if(kind.equals(row.getString("kind"))&&taskId.equals(row.getString("taskId"))&&logicalPageId.equals(row.getString("logicalPageId"))&&exact.equals(row.getString("url")))return id;}
        if(kind.equals("research")){java.util.List<String> old=new java.util.ArrayList<>();for(var it=all.keys();it.hasNext();){String previous=it.next();JSONObject row=all.getJSONObject(previous);if(kind.equals(row.getString("kind"))&&taskId.equals(row.getString("taskId"))&&logicalPageId.equals(row.getString("logicalPageId")))old.add(previous);}for(String previous:old)all.remove(previous);}
        if(all.length()>=512)throw new IllegalStateException("私有精确地址已达保存上限，请在地址保存设置中清除后重试");String id=UUID.randomUUID().toString();all.put(id,new JSONObject().put("kind",kind).put("taskId",taskId).put("logicalPageId",logicalPageId).put("url",exact));return id;
    }
    private static void pruneHistoryNavigations(JSONObject profile)throws Exception {
        JSONObject all=profile.optJSONObject("privateNavigations");if(all==null)return;java.util.Set<String> kept=new java.util.HashSet<>();JSONArray history=profile.optJSONArray("ownerHistory");for(int i=0;history!=null&&i<history.length();i++){String id=history.getJSONObject(i).optString("privateNavigationId");if(!id.isEmpty())kept.add(id);}
        java.util.List<String> remove=new java.util.ArrayList<>();for(var it=all.keys();it.hasNext();){String id=it.next();if("history".equals(all.getJSONObject(id).optString("kind"))&&!kept.contains(id))remove.add(id);}for(String id:remove)all.remove(id);
    }
    private static void validatePrivateNavigations(JSONObject profile)throws Exception {
        if(profile.has("exactUrlRetention")&&!(profile.get("exactUrlRetention") instanceof Boolean))throw new IllegalStateException("地址保存设置无效");JSONObject all=profile.optJSONObject("privateNavigations");if(profile.has("privateNavigations")&&(all==null||!profile.optBoolean("exactUrlRetention")))throw new IllegalStateException("私有导航设置无效");if(all==null)return;if(all.length()>512)throw new IllegalStateException("私有导航数量超限");
        for(var it=all.keys();it.hasNext();){String id=it.next();BrowserTaskSpace.uuid(id);JSONObject row=all.getJSONObject(id);if(row.length()!=4||!java.util.Set.of("history","research").contains(row.getString("kind")))throw new IllegalStateException("私有导航身份无效");if(row.getString("kind").equals("research")){BrowserTaskSpace.uuid(row.getString("taskId"));BrowserTaskSpace.uuid(row.getString("logicalPageId"));}else if(!row.getString("taskId").isEmpty()||!row.getString("logicalPageId").isEmpty())throw new IllegalStateException("历史导航身份无效");BrowserNavigationTarget target=BrowserNavigationTarget.parse(row.getString("url"));if(target==null||!target.parameters||!target.exactUrl.equals(row.getString("url")))throw new IllegalStateException("私有导航地址无效");}
    }
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
