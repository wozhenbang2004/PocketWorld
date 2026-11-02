package dev.pocketengine.displayhelper;

import java.util.*;
import org.json.*;

/** Native owner-only, single-use transfer of one proven root task. No package search or retry. */
final class OwnerTaskTransfer {
    interface Backend { List<Task> tasks() throws Exception; void move(int rootTask,int display) throws Exception; }
    static final class Task {
        final int id,display,user; final String component; final Object token; final int[] children;
        Task(int id,int display,int user,String component,Object token,int[] children){this.id=id;this.display=display;this.user=user;this.component=component;this.token=token;this.children=children==null?new int[0]:children.clone();}
        boolean same(Task other){return other!=null&&id==other.id&&user==other.user&&component.equals(other.component)&&token!=null&&token.equals(other.token)&&Arrays.equals(children,other.children);}
    }
    private final Backend backend; private final int display;
    private Task pinned; private String nonce="",state="none"; private long expires;private boolean planRevoked;
    OwnerTaskTransfer(Backend backend,int display){if(display<=0)throw new IllegalArgumentException("Owned display required");this.backend=backend;this.display=display;}
    synchronized boolean blocksInput(){return state.equals("main")||state.equals("unknown");}
    synchronized JSONObject prepare(long now)throws Exception {
        if(blocksInput())throw new IllegalStateException("原应用仍在主人手中，先核对并交还");
        Task candidate=null;for(Task task:backend.tasks())if(task.display==display){if(candidate!=null)throw new IllegalStateException("副屏有多个任务，不能确定接管对象");candidate=task;}
        if(candidate==null||candidate.token==null||candidate.children.length>1||candidate.component.isEmpty())throw new IllegalStateException("不能证明副屏中的单一实际任务身份");
        pinned=candidate;planRevoked=false;nonce=UUID.randomUUID().toString();state="prepared";expires=now+60_000;return receipt(false);
    }
    synchronized JSONObject status()throws Exception {
        // Revoke before certifying no entry: a delayed old socket request must not act
        // after this status response settled its journal as not dispatched.
        if(state.equals("prepared"))planRevoked=true;
        if(pinned!=null&&(state.equals("unknown")||state.equals("main"))){Task actual=find();if(pinned.same(actual)){if(actual.display==display)state="returned";else if(actual.display==0)state="main";else state="unknown";}else state="unknown";}
        return receipt(false);
    }
    synchronized JSONObject transfer(String expected,boolean returning,long now)throws Exception {
        if(pinned==null||!nonce.equals(expected)||planRevoked)throw new SecurityException("接管确认已失效");
        if(returning?!state.equals("main"):!state.equals("prepared")||now>expires)throw new SecurityException("接管状态已变化，请重新核对");
        Task actual=find();int from=returning?0:display,to=returning?display:0;
        if(!pinned.same(actual)||actual.display!=from)throw new SecurityException("实际任务身份或位置变化，未搬动其他应用");
        // Set unknown BEFORE binder entry. Never retry a possibly dispatched migration.
        state="unknown";
        try{backend.move(pinned.id,to);Task after=find();if(pinned.same(after)&&after.display==to){state=returning?"returned":"main";return receipt(true);}}
        catch(Exception uncertain){/* Read-only reconciliation is the only next step. */}
        return receipt(false).put("accepted",JSONObject.NULL).put("code","owner_handoff_unknown");
    }
    private Task find()throws Exception {for(Task task:backend.tasks())if(task.id==pinned.id)return task;return null;}
    private JSONObject receipt(boolean moved)throws Exception {JSONObject result=new JSONObject().put("ok",!state.equals("unknown")).put("accepted",state.equals("unknown")?JSONObject.NULL:moved).put("state",state).put("handoffId",nonce).put("originalDisplayId",display).put("taskMigrationPerformed",moved).put("nativeOwnerOnly",true).put("planRevoked",planRevoked).put("notDispatched",state.equals("prepared")&&planRevoked);if(state.equals("unknown"))result.put("code","owner_handoff_unknown");if(pinned!=null)result.put("rootTaskId",pinned.id).put("component",pinned.component).put("androidUserId",pinned.user).put("targetDisplayId",state.equals("main")?0:display);return result;}
}
