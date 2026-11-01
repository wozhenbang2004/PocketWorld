package dev.pocketengine.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** The observer's bounded native frame map and presented set, not another task/permission registry. UI-thread only. */
final class BrowserFrameReadRegistry<P> {
    static final int MAX_FRAMES=16;
    static final long REF_LIMIT_MS=120_000;
    static final class Frame<P> {
        final String id,origin,document,documentToken;final boolean main;final P proxy;
        Frame(String origin,boolean main,P proxy,String document,String token){id=main?"main":UUID.randomUUID().toString();this.origin=origin;this.main=main;this.proxy=proxy;this.document=document;documentToken=token;}
        JSONObject facts(boolean read)throws Exception{return new JSONObject().put("frameId",id).put("sourceOrigin",origin).put("mainFrame",main).put("documentId",document).put("read",read);}
    }
    private static final class Presented<P> {final Frame<P> frame;final long at;Presented(Frame<P> frame,long at){this.frame=frame;this.at=at;}}
    private final Map<P,Frame<P>> frames=new LinkedHashMap<>();
    private final Map<String,Presented<P>> presented=new LinkedHashMap<>();
    private String registration="",document="",topOrigin="";private Set<String> origins=Set.of();
    void reset(String nativeDocument,String registration,String topOrigin,Set<String> allowed)throws Exception {
        clear();BrowserTaskSpace.uuid(nativeDocument);document=nativeDocument;this.registration=registration;this.topOrigin=topOrigin;origins=Set.copyOf(allowed);
    }
    void clear(){frames.clear();presented.clear();registration="";origins=Set.of();}
    boolean register(P proxy,String sourceOrigin,boolean main,String echoedRegistration,String token)throws Exception {
        if(proxy==null||registration.isEmpty()||!registration.equals(echoedRegistration)||!origins.contains(sourceOrigin)||main&&!topOrigin.equals(sourceOrigin)||token==null||!token.matches("[a-f0-9]{32}"))return false;
        Frame<P> old=frames.get(proxy);
        if(old!=null&&old.origin.equals(sourceOrigin)&&old.main==main&&old.documentToken.equals(token))return true;
        if(old==null&&(frames.size()>=MAX_FRAMES||main&&main()!=null))return false;
        if(old!=null){frames.remove(proxy);presented.remove(old.id);}
        frames.put(proxy,new Frame<>(sourceOrigin,main,proxy,main?document:UUID.randomUUID().toString(),token));return true;
    }
    List<Frame<P>> frames(){return new ArrayList<>(frames.values());}
    Frame<P> main(){Frame<P> main=null;for(Frame<P> frame:frames.values())if(frame.main){if(main!=null)return null;main=frame;}return main;}
    boolean current(Frame<P> frame){return frame!=null&&frames.get(frame.proxy)==frame;}
    void retire(Frame<P> frame){if(current(frame)){frames.remove(frame.proxy);presented.remove(frame.id);}}
    Map<String,Frame<P>> candidates(){Map<String,Frame<P>> out=new LinkedHashMap<>();for(Frame<P> frame:frames.values())out.put(frame.id,frame);return out;}
    JSONArray catalogue(Frame<P> selected)throws Exception {JSONArray out=new JSONArray();for(Frame<P> frame:frames.values())out.put(frame.facts(frame==selected));return out;}
    /** A registered frame is not known to the model until the final safe projection actually shows it. */
    void presented(JSONArray delivered,Map<String,Frame<P>> candidates,long now)throws Exception {
        if(delivered==null||delivered.length()>MAX_FRAMES)throw new SecurityException("已展示frame目录无效");
        Map<String,Presented<P>> accepted=new LinkedHashMap<>();
        for(int i=0;i<delivered.length();i++){
            JSONObject row=delivered.getJSONObject(i);String id=row.getString("frameId");Frame<P> nativeFrame=candidates.get(id);
            if(nativeFrame==null||!current(nativeFrame)||accepted.containsKey(id)||row.length()!=5||!nativeFrame.origin.equals(row.getString("sourceOrigin"))||nativeFrame.main!=row.getBoolean("mainFrame")||!nativeFrame.document.equals(row.getString("documentId"))||!(row.get("read") instanceof Boolean))throw new SecurityException("frame目录不是当次真实原生呈现");
            accepted.put(id,new Presented<>(nativeFrame,now));
        }
        presented.putAll(accepted);presented.entrySet().removeIf(e->!current(e.getValue().frame)||now<e.getValue().at||now-e.getValue().at>REF_LIMIT_MS);
    }
    Frame<P> requirePresentedChild(String id,long now)throws Exception {
        BrowserTaskSpace.uuid(id);Presented<P> known=presented.get(id);
        if(known==null||known.frame.main||!current(known.frame)||now<known.at||now-known.at>REF_LIMIT_MS)throw new BrowserTaskSpace.FrameReadUnavailable();return known.frame;
    }
}
