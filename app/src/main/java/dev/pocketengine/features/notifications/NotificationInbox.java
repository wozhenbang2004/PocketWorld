package dev.pocketengine.features.notifications;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded, process-local notification observations. Not a message archive or task journal. */
final class NotificationInbox {
    static final int MAX_PACKAGES=20, MAX_ACTIVE=200, MAX_CHANGES=128;
    static final long RETENTION_MILLIS=15*60_000L;
    private final LinkedHashMap<String,Notice> active=new LinkedHashMap<>();
    private final List<Change> changes=new ArrayList<>();
    private Set<String> packages=Collections.emptySet();
    private String revision=UUID.randomUUID().toString();
    private Lease connection;
    private boolean enabled;
    private long sequence, evicted;

    static final class Policy {
        final String revision;
        final boolean enabled;
        final Set<String> packages;
        Policy(String revision,boolean enabled,Set<String> packages){this.revision=revision;this.enabled=enabled;this.packages=Collections.unmodifiableSet(new LinkedHashSet<>(packages));}
    }
    static final class Lease {
        final String revision, connectionId=UUID.randomUUID().toString();
        final long connectedAtWall=System.currentTimeMillis();
        private Lease(String revision){this.revision=revision;}
    }
    static final class Content {
        final String title,text,channel,category;
        final long postedAt;
        final boolean ongoing;
        Content(String title,String text,String channel,String category,long postedAt,boolean ongoing){
            this.title=clip(title,512);this.text=clip(text,2048);this.channel=clip(channel,256);this.category=clip(category,80);this.postedAt=postedAt;this.ongoing=ongoing;
        }
        boolean same(Content other){return title.equals(other.title)&&text.equals(other.text)&&channel.equals(other.channel)&&category.equals(other.category)&&ongoing==other.ongoing;}
    }
    static final class Notice {
        final String ref,packageName,kind;
        final long version,observedAt;
        final boolean triggerCandidate;
        final Content content;
        Notice(String ref,String packageName,String kind,long version,long observedAt,boolean triggerCandidate,Content content){
            this.ref=ref;this.packageName=packageName;this.kind=kind;this.version=version;this.observedAt=observedAt;this.triggerCandidate=triggerCandidate;this.content=content;
        }
    }
    static final class Change {
        final String ref,kind;
        final long version,at;
        final int reason;
        final boolean triggerCandidate;
        Change(String ref,String kind,long version,long at,int reason,boolean triggerCandidate){this.ref=ref;this.kind=kind;this.version=version;this.at=at;this.reason=reason;this.triggerCandidate=triggerCandidate;}
    }

    synchronized void restore(Policy policy){
        if(policy==null)return;invalidate();revision=policy.revision;enabled=policy.enabled;packages=enabled?validatePackages(policy.packages):Collections.emptySet();
    }
    synchronized Policy policy(){return new Policy(revision,enabled,packages);}
    synchronized String revision(){return revision;}
    synchronized boolean enabled(){return enabled;}
    synchronized boolean connected(){return connection!=null;}
    synchronized long sequence(){return sequence;}
    synchronized long evicted(){return evicted;}
    synchronized Set<String> packages(){return new LinkedHashSet<>(packages);}
    synchronized void enable(String expectedRevision,Set<String> selected){
        checkRevision(expectedRevision);Set<String> checked=validatePackages(selected);
        invalidate();packages=checked;enabled=true;
    }
    synchronized void disable(String expectedRevision){checkRevision(expectedRevision);invalidate();}
    synchronized void revoke(){invalidate();}
    private void invalidate(){enabled=false;packages=Collections.emptySet();connection=null;active.clear();changes.clear();revision=UUID.randomUUID().toString();sequence=0;evicted=0;}
    synchronized Lease connect(){
        if(!enabled)throw new SecurityException("通知读取尚未由本机主人开启");
        // Every connection starts a new baseline. A previous callback can never populate it.
        active.clear();changes.clear();connection=new Lease(revision);return connection;
    }
    synchronized boolean current(Lease lease){return enabled&&lease!=null&&lease==connection&&revision.equals(lease.revision);}
    synchronized boolean accepts(Lease lease,String pkg){return current(lease)&&packages.contains(pkg);}
    synchronized boolean disconnect(Lease lease){
        if(!current(lease))return false;connection=null;active.clear();changes.clear();return true;
    }
    synchronized boolean posted(Lease lease,String key,String pkg,Content content,boolean baseline,long now){
        if(!accepts(lease,pkg)||key==null||key.isEmpty()||key.length()>4096)return false;
        prune(now);String ref=reference(lease.connectionId,key);Notice previous=active.get(ref);
        if(previous!=null&&previous.content.same(content))return false;
        String kind=previous==null?(baseline?"baseline":"posted"):"updated";
        // Baseline completion must not turn a live event into a replay trigger.
        boolean trigger=!baseline;long version=++sequence;
        active.remove(ref);active.put(ref,new Notice(ref,pkg,kind,version,now,trigger,content));
        if(!baseline)record(new Change(ref,kind,version,now,0,trigger));
        while(active.size()>MAX_ACTIVE){Iterator<Notice> it=active.values().iterator();Notice oldest=it.next();it.remove();evicted++;record(new Change(oldest.ref,"evicted",++sequence,now,0,false));}
        return true;
    }
    synchronized boolean unavailable(Lease lease,String key,long now){
        if(!current(lease)||key==null||key.isEmpty()||key.length()>4096)return false;
        prune(now);Notice previous=active.remove(reference(lease.connectionId,key));
        if(previous==null)return false;record(new Change(previous.ref,"unavailable",++sequence,now,0,false));return true;
    }
    synchronized boolean removed(Lease lease,String key,int reason,long now){
        if(!current(lease)||key==null||key.length()>4096)return false;
        prune(now);String ref=reference(lease.connectionId,key);Notice previous=active.remove(ref);
        if(previous==null)return false;record(new Change(ref,"removed",++sequence,now,reason,false));return true;
    }
    synchronized void expire(long now){prune(now);}
    synchronized List<Notice> active(long now){prune(now);return new ArrayList<>(active.values());}
    synchronized List<Change> changes(long now){prune(now);return new ArrayList<>(changes);}
    synchronized Notice reviewed(String expectedRevision,String ref,long version,long now){
        checkRevision(expectedRevision);if(!enabled||connection==null)throw new SecurityException("通知来源已断开，请重新读取");prune(now);
        Notice notice=active.get(ref);if(notice==null||notice.version!=version)throw new SecurityException("通知已更新、撤销或过期，请重新审阅");return notice;
    }
    private void checkRevision(String expected){if(!revision.equals(expected))throw new SecurityException("通知授权版本已变化，请刷新后确认");}
    private void record(Change change){changes.add(change);while(changes.size()>MAX_CHANGES)changes.remove(0);}
    private void prune(long now){
        Iterator<Notice> notices=active.values().iterator();while(notices.hasNext()){Notice n=notices.next();if(expired(n.observedAt,now)){notices.remove();record(new Change(n.ref,"expired",++sequence,now,0,false));}}
        changes.removeIf(change->expired(change.at,now));
    }
    private static boolean expired(long at,long now){return now<at||now-at>=RETENTION_MILLIS;}
    static Set<String> validatePackages(Set<String> selected){
        if(selected==null||selected.isEmpty()||selected.size()>MAX_PACKAGES)throw new IllegalArgumentException("请选择 1 至 20 个允许读取通知的应用");
        LinkedHashSet<String> result=new LinkedHashSet<>();for(String pkg:selected){if(pkg==null||pkg.length()>200||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"))throw new IllegalArgumentException("应用包名无效");result.add(pkg);}return Collections.unmodifiableSet(result);
    }
    private static String reference(String revision,String key){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((revision+"\n"+key).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("notice-");for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static String clip(String value,int max){
        String text=value==null?"":value;if(text.length()<=max)return text;int end=max;if(Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end);
    }
}
