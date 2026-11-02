package dev.pocketengine.phone.display;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import org.json.JSONObject;

/** Native view of one original admitted display. It creates no task, run, grant, or alternate transport. */
public interface BackgroundDisplaySession {
    boolean isConnected();
    boolean hasSession();
    boolean ownedBy(String task);
    JSONObject session()throws Exception;
    JSONObject connect(RunControl original)throws Exception;
    JSONObject request(String command,JSONObject payload,RunControl original)throws Exception;
    JSONObject request(String command,JSONObject payload,RunControl original,long revision,boolean pausedOwner)throws Exception;
    JSONObject requestAdmitted(String command,JSONObject payload,RunControl original,long revision,String session,int display,NativePhoneEntry.Admission admission)throws Exception;
    JSONObject requestOcrTap(JSONObject payload,RunControl original,long revision,String session,int display,NativePhoneEntry.Admission admission)throws Exception;
    JSONObject renewTaskLease(String task,String run,int epoch,RunControl original,String session,int display,long deadline)throws Exception;
    void noteReleasePending(String session,int display);
    JSONObject releaseSession(String session,int display)throws Exception;

    /** Compatibility view delegates to every original single-session check unchanged. */
    static BackgroundDisplaySession legacy(DisplayModule module){
        java.util.Objects.requireNonNull(module);
        return new BackgroundDisplaySession(){
            public boolean isConnected(){return module.isConnected();}public boolean hasSession(){return module.hasSession();}public boolean ownedBy(String task){return module.ownedBy(task);}
            public JSONObject session()throws Exception{return module.session();}public JSONObject connect(RunControl original)throws Exception{return module.connect(original);}
            public JSONObject request(String command,JSONObject payload,RunControl original)throws Exception{return module.request(command,payload,original);}
            public JSONObject request(String command,JSONObject payload,RunControl original,long revision,boolean pausedOwner)throws Exception{return module.request(command,payload,original,revision,pausedOwner);}
            public JSONObject requestAdmitted(String command,JSONObject payload,RunControl original,long revision,String session,int display,NativePhoneEntry.Admission admission)throws Exception{return module.requestAdmitted(command,payload,original,revision,session,display,admission);}
            public JSONObject requestOcrTap(JSONObject payload,RunControl original,long revision,String session,int display,NativePhoneEntry.Admission admission)throws Exception{return module.requestOcrTap(payload,original,revision,session,display,admission);}
            public JSONObject renewTaskLease(String task,String run,int epoch,RunControl original,String session,int display,long deadline)throws Exception{return module.renewTaskLease(task,run,epoch,original,session,display,deadline);}
            public void noteReleasePending(String session,int display){module.noteReleasePending(session,display);}
            public JSONObject releaseSession(String session,int display)throws Exception{return module.releaseSession(session,display);}
        };
    }
}
