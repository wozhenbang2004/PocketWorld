package dev.pocketengine.displayhelper;

/** Pure state used by the shell process; a lease never refers to the primary display. */
public final class LeasePolicy {
    private final String session;
    private final int display;
    private volatile long expires;
    private volatile long lastSeen;
    private volatile boolean released;
    public LeasePolicy(String session,int display,long now,long lifetimeMs) {
        if(session==null||session.isEmpty()||display<=0||lifetimeMs<1000||lifetimeMs>600_000)throw new IllegalArgumentException("Invalid display lease");
        this.session=session;this.display=display;this.expires=now+lifetimeMs;this.lastSeen=now;
    }
    public synchronized boolean accepts(String session,int display,long now) {
        if(released||display<=0||display!=this.display||!this.session.equals(session)||now>=expires||now-lastSeen>120_000)return false;
        lastSeen=now;return true;
    }
    /** Authenticated owner heartbeat renews only the same live display; never revives a lost lease. */
    public synchronized boolean renew(String session,int display,long now,long lifetimeMs){
        if(lifetimeMs<1||lifetimeMs>30_000||!accepts(session,display,now))return false;
        expires=now+lifetimeMs;return true;
    }
    public long expiresAt(){return expires;}
    public boolean expired(long now){return released||now>=expires||now-lastSeen>120_000;}
    public void release(){released=true;}
    public String session(){return session;}
    public int display(){return display;}
}
