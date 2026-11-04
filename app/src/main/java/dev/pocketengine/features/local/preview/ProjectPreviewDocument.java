package dev.pocketengine.features.local.preview;

import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

/** One native renderer lifetime. Persisted JSON cannot reconstruct a Selection. */
public final class ProjectPreviewDocument {
    private final ProjectPreviewBundle.Verified bundle;
    private final String id=UUID.randomUUID().toString();
    private long generation,ticket;
    private boolean ready,released,selecting;
    private String path;

    public ProjectPreviewDocument(ProjectPreviewBundle.Verified bundle){this.bundle=Objects.requireNonNull(bundle);}
    public ProjectPreviewBundle.Verified bundle(){return bundle;}
    public String id(){return id;}
    public synchronized long generation(){return generation;}
    public synchronized boolean ready(){return ready&&!released;}
    public synchronized void loading(){if(released)return;generation=Math.addExact(generation,1);ticket=Math.addExact(ticket,1);ready=false;selecting=false;path=null;}
    public synchronized void loaded(String nativeUrl){
        if(released)throw stale();String current;
        try{current=path(nativeUrl);}catch(SecurityException invalid){loading();throw invalid;}
        if(generation==0||ready&&!current.equals(path))loading();path=current;ready=true;
    }
    public synchronized void release(){if(released)return;loading();released=true;}
    /** A fixed bundle may be observed; only the native registry's original SQL source can admit its reference. */
    public synchronized long beginSelection(){if(!ready())throw stale();ticket=Math.addExact(ticket,1);selecting=true;return ticket;}
    public synchronized void endSelection(){selecting=false;ticket=Math.addExact(ticket,1);}
    public synchronized Selection select(long capturedGeneration,long selectionTicket,String capturedNativeUrl,JSONObject observation)throws Exception {
        if(!ready()||!selecting||generation!=capturedGeneration||ticket!=selectionTicket||!Objects.equals(path,path(capturedNativeUrl)))throw stale();
        JSONObject bounded=PreviewElementRef.checkedObservation(observation);ProjectPreviewBundle.Resource resource=bundle.resource(capturedNativeUrl);
        String fullPath=bundle.previewRoot.isEmpty()?path:bundle.previewRoot+"/"+path;
        JSONObject snapshot=new JSONObject().put("documentId",id).put("documentGeneration",generation).put("path",fullPath).put("resourceSha256",resource.sha256);
        for(String key:new String[]{"selectorHint","text","htmlSnippet","bounds","truncated"})snapshot.put(key,bounded.get(key));
        return new Selection(this,generation,path,snapshot);
    }
    private String path(String nativeUrl){String relative=bundle.resourcePath(nativeUrl);if(relative==null||!bundle.document(nativeUrl))throw stale();return relative;}
    private synchronized void requireCurrent(long capturedGeneration,String capturedPath){if(!ready()||generation!=capturedGeneration||!Objects.equals(path,capturedPath))throw stale();}
    private static SecurityException stale(){return new SecurityException("原预览文档已改变或不可选择，请重新打开并选择元素");}

    public static final class Selection {
        private final ProjectPreviewDocument document;private final long generation;private final String path;private final String snapshot;
        private Selection(ProjectPreviewDocument document,long generation,String path,JSONObject snapshot){this.document=document;this.generation=generation;this.path=path;this.snapshot=snapshot.toString();}
        public ProjectPreviewBundle.Verified bundle(){return document.bundle;}
        public JSONObject snapshot()throws Exception{return new JSONObject(snapshot);}
        /** Mode-off/return hides input only. Already captured refs follow the original document lifetime. */
        public void requireCurrent(){document.requireCurrent(generation,path);}
    }
}
