package dev.pocketengine.browser;

/** Native owner consent for this browser session. Never callable through a model tool or a page bridge. */
public final class BrowserNetworkDebugging implements AutoCloseable {
    @FunctionalInterface public interface Switch {void set(boolean enabled);}
    private static final Object PROCESS_LOCK=new Object();
    private static BrowserNetworkDebugging processOwner;
    private final Switch setting;
    private volatile boolean permitted,owned;

    public BrowserNetworkDebugging(Switch setting){this.setting=java.util.Objects.requireNonNull(setting);}
    /** The owner has just confirmed the process-wide scope. A real fixed-socket probe precedes this call.
     * There is no Android getter for this setting. A debuggable build's existing setting is never changed. */
    public boolean enableForOwner(boolean debuggable,boolean alreadyListening){
        synchronized(PROCESS_LOCK){
            if(permitted)return true;
            if(alreadyListening){permitted=true;return true;}
            if(debuggable||processOwner!=null)return false;
            // Non-debuggable apps default to disabled. Only this explicit owner path changes it.
            owned=true;processOwner=this;setting.set(true);permitted=true;return true;
        }
    }
    public boolean permitted(){return permitted;}
    public boolean enabledByThisSession(){return owned;}
    @Override public void close(){
        synchronized(PROCESS_LOCK){
            permitted=false;
            if(owned&&processOwner==this){setting.set(false);owned=false;processOwner=null;}
        }
    }
}
