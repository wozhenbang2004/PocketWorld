package dev.pocketengine.browser;

import dev.pocketengine.store.TaskStore;
import java.net.URI;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Logical task-page metadata in original TaskStore events. Never renderer objects, grants, cookies or action refs. */
public final class BrowserResearchPages {
    public static final String EVENT="browser_page_manifest";
    public static final int MAX_PAGES=16;
    private BrowserResearchPages() { }
    public static JSONObject page(String logicalPageId,String raw,String title,String state,String openedBy)throws Exception {
        BrowserTaskSpace.uuid(logicalPageId);if(!Set.of("opening","loading","ready","owner_control","closed","renderer_gone","unknown","needs_reload").contains(state)||!Set.of("task","owner_adopted").contains(openedBy))throw new IllegalArgumentException("逻辑页状态无效");
        String url="";boolean omitted=false;try{URI u=new URI(raw==null?"":raw);if(Set.of("http","https").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null){String clean=new URI(u.getScheme()+"://"+u.getRawAuthority()+(u.getRawPath()==null?"":u.getRawPath())).toASCIIString();if(clean.length()<=2048){url=clean;omitted=u.getRawQuery()!=null||u.getRawFragment()!=null;}}}catch(Exception unavailable){/* A non-HTTP/auth destination is not converted into a recoverable navigation target. */}
        String safeTitle=title==null?"":title;if(raw!=null&&!raw.isEmpty()&&(raw.startsWith(safeTitle)||safeTitle.contains(raw)))safeTitle=url;
        return new JSONObject().put("logicalPageId",logicalPageId).put("url",url).put("urlQueryOmitted",omitted).put("title",clip(safeTitle,240)).put("state",state).put("openedBy",openedBy).put("needsFreshObservation",true);
    }
    public static void validate(JSONObject manifest)throws Exception {
        Set<String> keys=Set.of("version","taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","revision","pages","phase","automaticReplayAllowed","actionRefsRestored");if(manifest.length()!=keys.size())throw new SecurityException("研究页清单字段不完整");for(var it=manifest.keys();it.hasNext();)if(!keys.contains(it.next()))throw new SecurityException("研究页清单含额外字段");
        if(manifest.getInt("version")!=1||manifest.getInt("epoch")<1||manifest.getLong("revision")<1||manifest.getLong("profileRevision")<1||!manifest.getString("profileId").matches("[A-Za-z0-9_-]{1,80}")||!Set.of("active","owner_control","ended","interrupted").contains(manifest.getString("phase"))||!Boolean.FALSE.equals(manifest.get("automaticReplayAllowed"))||!Boolean.FALSE.equals(manifest.get("actionRefsRestored")))throw new SecurityException("研究页恢复身份无效");
        for(String k:new String[]{"taskId","runId","browserSessionId","browserSpaceId"})BrowserTaskSpace.uuid(manifest.getString(k));JSONArray pages=manifest.getJSONArray("pages");if(pages.length()>MAX_PAGES)throw new SecurityException("研究页清单超限");Set<String> ids=new java.util.HashSet<>();
        for(int i=0;i<pages.length();i++){JSONObject p=pages.getJSONObject(i);if(p.length()!=7||!ids.add(p.getString("logicalPageId"))||!(p.get("urlQueryOmitted") instanceof Boolean)||!Boolean.TRUE.equals(p.get("needsFreshObservation")))throw new SecurityException("逻辑页身份重复或不完整");JSONObject checked=page(p.getString("logicalPageId"),p.getString("url"),p.getString("title"),p.getString("state"),p.getString("openedBy"));if(!p.getString("url").equals(checked.getString("url"))||p.getString("title").length()>240)throw new SecurityException("逻辑页地址不是可复核的安全投影");}
        if(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>49152)throw new SecurityException("研究页清单过大");
    }
    public static JSONObject fromTask(JSONObject task)throws Exception {
        JSONArray events=task.optJSONArray("events");for(int i=events==null?-1:events.length()-1;i>=0;i--){JSONObject event=events.getJSONObject(i);if(!EVENT.equals(event.optString("type")))continue;JSONObject m=event.getJSONObject("manifest");validate(m);if(!task.getString("id").equals(m.getString("taskId"))||!TaskStore.capabilityFingerprint(m).equals(event.getString("manifestFingerprint")))throw new SecurityException("原研究页清单版本不可核对");return new JSONObject(m.toString());}return null;
    }
    public static JSONObject required(JSONObject manifest,String logicalPageId)throws Exception {validate(manifest);BrowserTaskSpace.uuid(logicalPageId);JSONArray pages=manifest.getJSONArray("pages");for(int i=0;i<pages.length();i++)if(logicalPageId.equals(pages.getJSONObject(i).getString("logicalPageId")))return new JSONObject(pages.getJSONObject(i).toString());throw new SecurityException("逻辑页不属于这份原任务清单");}
    private static String clip(String value,int max){if(value==null)return "";int end=Math.min(value.length(),max);if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;return value.substring(0,end);}
}
