package dev.pocketengine.features.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private owner's unsent editor state. No message, receipt, task or execution authority lives here. */
public final class OwnerComposerState {
    public static final String FORMAT="pocketworld.owner-composer.v1";
    public static final int MAX_DRAFTS=64,MAX_PENDING=64,MAX_TEXT=48_000,MAX_STATE_BYTES=1_048_576;
    public static final class Draft {
        public final String key,conversationId,text,mode,anchorMessage,observedMessage,voiceText,voiceInputId;
        public final long revision;
        public final int scrollIndex,scrollOffset;
        public final boolean newReplies;
        private final String attachments,sourceRefs,previewRefs;
        private Draft(String key,String conversation,long revision,String text,String mode,JSONArray files,
                      int index,int offset,String anchor,String observed,boolean unread,String voice,String voiceId,JSONArray sources,JSONArray previews)throws Exception {
            this.key=key;conversationId=conversation;this.revision=revision;this.text=text;this.mode=mode;
            attachments=checkedAttachments(files).toString();sourceRefs=OwnerSourceReferences.checked(sources).toString();previewRefs=dev.pocketengine.features.local.preview.PreviewElementRef.checked(previews).toString();scrollIndex=index;scrollOffset=offset;
            anchorMessage=anchor;observedMessage=observed;newReplies=unread;voiceText=voice;voiceInputId=voiceId;
            if(!validKey(key)||!conversation.isEmpty()&&!conversationId(conversation)||revision<1||text.length()>MAX_TEXT
                    ||!mode(mode)||index<0||index>100_000||offset<0||offset>1_000_000
                    ||anchor.length()>300||observed.length()>300||voice.length()>4000
                    ||!voiceId.isEmpty()&&!uuid(voiceId)||voice.isEmpty()!=voiceId.isEmpty())throw new IllegalArgumentException("原私聊草稿格式无效");
            if(key.startsWith("conversation:")&&!key.equals(conversationKey(conversation)))throw new IllegalArgumentException("草稿不属于原对话");
            JSONArray selected=previewRefs();for(int i=0;i<selected.length();i++)if(!conversation.equals(selected.getJSONObject(i).getString("conversationId")))throw new SecurityException("元素引用不属于这份原对话草稿");
        }
        public JSONArray attachments()throws Exception{return new JSONArray(attachments);}
        /** Selected source locators only; currentness and body projection remain native host responsibilities. */
        public JSONArray sourceRefs()throws Exception{return new JSONArray(sourceRefs);}
        /** Low-trust selected DOM observations; persistence cannot recreate their native document. */
        public JSONArray previewRefs()throws Exception{return new JSONArray(previewRefs);}
        private boolean hasInput(){return !text.isEmpty()||!attachments.equals("[]")||!voiceText.isEmpty()||!sourceRefs.equals("[]")||!previewRefs.equals("[]");}
        private JSONObject json()throws Exception{return new JSONObject().put("key",key).put("conversationId",conversationId)
                .put("revision",revision).put("text",text).put("mode",mode).put("attachments",attachments()).put("ownerSourceRefs",sourceRefs()).put("ownerPreviewRefs",previewRefs())
                .put("scrollIndex",scrollIndex).put("scrollOffset",scrollOffset).put("anchorMessage",anchorMessage)
                .put("observedMessage",observedMessage).put("newReplies",newReplies).put("voiceText",voiceText).put("voiceInputId",voiceInputId);}
        private Draft content(String value,String nextMode,JSONArray files,String voice,String voiceId)throws Exception {
            return new Draft(key,conversationId,increment(revision),value,nextMode,files,scrollIndex,scrollOffset,anchorMessage,observedMessage,newReplies,voice,voiceId,sourceRefs(),previewRefs());
        }
    }
    public static final class Pending {
        public final String clientId,draftKey,fingerprint,conversationId,mode;
        public final long draftRevision;
        private final String originalTarget;
        private Pending(String client,String key,long revision,String fingerprint,String conversation,String mode,JSONObject target)throws Exception{
            if(!uuid(client)||!validKey(key)||revision<1||!fingerprint.matches("[a-f0-9]{64}")||!conversation.isEmpty()&&!conversationId(conversation)||!mode(mode))throw new IllegalArgumentException("原输入待核对身份无效");
            clientId=client;draftKey=key;draftRevision=revision;this.fingerprint=fingerprint;conversationId=conversation;this.mode=mode;
            JSONObject locator=new JSONObject();if(target!=null&&target.length()>0){
                String task=target.getString("taskId"),run=target.getString("runId");long epoch=integer(target,"epoch",1,Integer.MAX_VALUE);
                if(!uuid(task)||!uuid(run))throw new IllegalArgumentException("原任务定位无效");
                locator.put("taskId",task).put("runId",run).put("epoch",epoch);
            }originalTarget=locator.toString();
        }
        /** Display/reconciliation locator only. Never pass it to a launch or resume operation. */
        public JSONObject originalTarget()throws Exception{return new JSONObject(originalTarget);}
        private JSONObject json()throws Exception{return new JSONObject().put("clientId",clientId).put("draftKey",draftKey).put("draftRevision",draftRevision)
                .put("fingerprint",fingerprint).put("conversationId",conversationId).put("mode",mode).put("originalTarget",originalTarget());}
    }
    public static final class Acknowledgement {
        public final boolean matched,cleared,migrated;
        public final String previousKey,currentKey;
        private Acknowledgement(boolean matched,boolean cleared,boolean migrated,String before,String after){this.matched=matched;this.cleared=cleared;this.migrated=migrated;previousKey=before;currentKey=after;}
    }
    private final LinkedHashMap<String,Draft> drafts=new LinkedHashMap<>();
    private final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
    private String activeKey;
    private long revision;
    private OwnerComposerState(){}
    public static OwnerComposerState empty()throws Exception{
        OwnerComposerState state=new OwnerComposerState();state.activeKey="new:"+UUID.randomUUID();state.drafts.put(state.activeKey,blank(state.activeKey,""));state.revision=1;return state;
    }
    public synchronized long revision(){return revision;}
    public synchronized String activeKey(){return activeKey;}
    public synchronized Draft active(){return drafts.get(activeKey);}
    public synchronized Draft draft(String key){return drafts.get(key);}
    public synchronized List<Pending> pending(){return Collections.unmodifiableList(new ArrayList<>(pending.values()));}
    public synchronized Pending pending(String client){return pending.get(client);}
    public synchronized Pending matchingPending(String key,String fingerprint){for(Pending p:pending.values())if(p.draftKey.equals(key)&&p.fingerprint.equals(fingerprint))return p;return null;}
    public synchronized Draft selectConversation(String conversation)throws Exception {
        String key=conversationKey(conversation);if(!drafts.containsKey(key)){makeRoom();drafts.put(key,blank(key,conversation));}
        if(!key.equals(activeKey)){activeKey=key;revision=increment(revision);}return active();
    }
    /** Reading another conversation retains only its editor/reading anchor; never selects it. */
    public synchronized Draft readingConversation(String conversation)throws Exception{
        String key=conversationKey(conversation);Draft existing=drafts.get(key);if(existing!=null)return existing;
        makeRoom();Draft created=blank(key,conversation);drafts.put(key,created);revision=increment(revision);return created;
    }
    public synchronized Draft newConversation()throws Exception {
        if(active().conversationId.isEmpty())return active();
        for(Draft draft:drafts.values())if(draft.conversationId.isEmpty()&&!hasPending(draft.key)){activeKey=draft.key;revision=increment(revision);return draft;}
        makeRoom();activeKey="new:"+UUID.randomUUID();Draft draft=blank(activeKey,"");drafts.put(activeKey,draft);revision=increment(revision);return draft;
    }
    /** Explicitly return to an original pending editor, not whichever conversation is now visible. */
    public synchronized Draft selectPending(String client)throws Exception{
        Pending p=pending.get(client);if(p==null||!drafts.containsKey(p.draftKey))throw new IllegalArgumentException("原待核对输入不存在");
        if(!activeKey.equals(p.draftKey)){activeKey=p.draftKey;revision=increment(revision);}return active();
    }
    public synchronized Draft edit(String key,long expected,String text,String mode,JSONArray files)throws Exception {
        Draft prior=require(key,expected);Objects.requireNonNull(text);JSONArray checked=checkedAttachments(files);
        if(prior.text.equals(text)&&prior.mode.equals(mode)&&prior.attachments.equals(checked.toString()))return prior;
        Draft next=prior.content(text,mode,checked,prior.voiceText,prior.voiceInputId);drafts.put(key,next);revision=increment(revision);return next;
    }
    /** Explicit editor CAS; validating a locator never grants source access or submits the draft. */
    public synchronized Draft editSources(String key,long expected,JSONArray nativeSelections)throws Exception {
        Draft prior=require(key,expected);JSONArray checked=OwnerSourceReferences.checked(nativeSelections);
        if(prior.sourceRefs.equals(checked.toString()))return prior;
        Draft next=new Draft(key,prior.conversationId,increment(prior.revision),prior.text,prior.mode,prior.attachments(),prior.scrollIndex,prior.scrollOffset,prior.anchorMessage,prior.observedMessage,prior.newReplies,prior.voiceText,prior.voiceInputId,checked,prior.previewRefs());
        drafts.put(key,next);revision=increment(revision);return next;
    }
    /** Add/remove a native preview selection without replacing text, quotes, files or pending ACKs. */
    public synchronized Draft editPreviewRefs(String key,long expected,JSONArray nativeSelections)throws Exception {
        Draft prior=require(key,expected);JSONArray checked=dev.pocketengine.features.local.preview.PreviewElementRef.checked(nativeSelections);
        for(int i=0;i<checked.length();i++)if(!prior.conversationId.equals(checked.getJSONObject(i).getString("conversationId")))throw new SecurityException("预览引用不能进入另一个原对话草稿");
        if(prior.previewRefs.equals(checked.toString()))return prior;
        Draft next=new Draft(key,prior.conversationId,increment(prior.revision),prior.text,prior.mode,prior.attachments(),prior.scrollIndex,prior.scrollOffset,prior.anchorMessage,prior.observedMessage,prior.newReplies,prior.voiceText,prior.voiceInputId,prior.sourceRefs(),checked);
        drafts.put(key,next);revision=increment(revision);return next;
    }
    public synchronized Draft reading(String key,int index,int offset,String anchor,String observed,boolean unread)throws Exception{
        Draft prior=drafts.get(key);if(prior==null)throw new IllegalArgumentException("原阅读位置不存在");
        if(prior.scrollIndex==index&&prior.scrollOffset==offset&&prior.anchorMessage.equals(anchor)&&prior.observedMessage.equals(observed)&&prior.newReplies==unread)return prior;
        Draft next=new Draft(key,prior.conversationId,prior.revision,prior.text,prior.mode,prior.attachments(),index,offset,anchor,observed,unread,prior.voiceText,prior.voiceInputId,prior.sourceRefs(),prior.previewRefs());
        drafts.put(key,next);revision=increment(revision);return next;
    }
    /** Only a completed, released native ASR's final text is retained; no microphone lease is serialized. */
    public synchronized Draft retainVoice(String key,String inputId,String finalText)throws Exception {
        Draft prior=drafts.get(key);if(prior==null)throw new IllegalArgumentException("原听写输入不存在");
        if(prior.voiceInputId.equals(inputId)&&prior.voiceText.equals(finalText))return prior;
        Draft next=prior.content(prior.text,prior.mode,prior.attachments(),finalText,inputId);drafts.put(key,next);revision=increment(revision);return next;
    }
    public synchronized Draft useVoice(String key,long expected,String edited)throws Exception{
        Draft prior=require(key,expected);if(prior.voiceText.isEmpty()||edited==null||blank(edited)||edited.length()>4000)throw new IllegalArgumentException("请核对原听写文字");
        String text=prior.text.isEmpty()?edited.trim():prior.text+"\n"+edited.trim();Draft next=prior.content(text,prior.mode,prior.attachments(),"","");drafts.put(key,next);revision=increment(revision);return next;
    }
    public synchronized Draft discardVoice(String key,long expected)throws Exception{Draft prior=require(key,expected);Draft next=prior.content(prior.text,prior.mode,prior.attachments(),"","");drafts.put(key,next);revision=increment(revision);return next;}
    public synchronized Pending prepare(String client,String key,long expected,String fingerprint,JSONObject target)throws Exception {
        Draft draft=require(key,expected);if((blank(draft.text)&&draft.attachments().length()==0)||draft.text.trim().length()>12000)throw new IllegalArgumentException("请保留草稿并将本条输入缩至12000字内；纯附件消息必须已有真实附件选择");
        Pending duplicate=matchingPending(key,fingerprint);if(duplicate!=null)return duplicate;
        if(pending.containsKey(client)||pending.size()>=MAX_PENDING)throw new IllegalStateException("待核对输入已达本机上限，请先查询原回执；未发送新输入");
        Pending next=new Pending(client,key,draft.revision,fingerprint,draft.conversationId,draft.mode,target);pending.put(client,next);revision=increment(revision);return next;
    }
    /** Caller has validated an exact native not-admitted receipt. Keep every draft byte;
     * only a later explicit owner Send may prepare a new identity. */
    public synchronized boolean rejectNotAdmitted(String client,String conversation)throws Exception {
        Pending p=pending.get(client);if(p==null)return false;
        if(!conversationId(conversation)||!p.conversationId.equals(conversation))throw new SecurityException("拒收回执不属于原输入对话");
        if(!drafts.containsKey(p.draftKey))throw new IllegalStateException("原草稿不可核对，未移除待核对身份");
        pending.remove(client);revision=increment(revision);return true;
    }
    /** Caller supplies an already validated native ACK, never an LLM/tool self-reported success. */
    public synchronized Acknowledgement acknowledge(String client,String conversation)throws Exception {
        Pending p=pending.get(client);if(p==null)return new Acknowledgement(false,false,false,activeKey,activeKey);
        if(!conversationId(conversation)||!p.conversationId.isEmpty()&&!p.conversationId.equals(conversation))throw new SecurityException("回执不属于原输入对话");
        Draft prior=drafts.get(p.draftKey);if(prior==null)throw new IllegalStateException("原草稿不可核对，未清除当前输入");
        String target=conversationKey(conversation);boolean clear=prior.revision==p.draftRevision,migrate=!prior.key.equals(target);
        Draft remaining=clear?new Draft(prior.key,prior.conversationId,increment(prior.revision),"",prior.mode,new JSONArray(),prior.scrollIndex,prior.scrollOffset,prior.anchorMessage,prior.observedMessage,prior.newReplies,"","",new JSONArray(),new JSONArray()):prior;
        Draft collision=drafts.get(target);
        if(migrate&&collision!=null&&collision.hasInput()&&remaining.hasInput())throw new IllegalStateException("输入已受理，但原对话另有未发送草稿；两份均保留，请回原输入核对");
        Draft next=migrate&&collision!=null&&collision.hasInput()?new Draft(target,conversation,increment(Math.max(collision.revision,remaining.revision)),collision.text,collision.mode,collision.attachments(),collision.scrollIndex,collision.scrollOffset,collision.anchorMessage,collision.observedMessage,collision.newReplies,collision.voiceText,collision.voiceInputId,collision.sourceRefs(),collision.previewRefs()):new Draft(target,conversation,remaining.revision,remaining.text,remaining.mode,remaining.attachments(),remaining.scrollIndex,remaining.scrollOffset,remaining.anchorMessage,remaining.observedMessage,remaining.newReplies,remaining.voiceText,remaining.voiceInputId,remaining.sourceRefs(),remaining.previewRefs());
        // Separate first sends from an unbound editor may have created different
        // conversations. One receipt cannot bind another uncertain input to its target.
        boolean keepOriginal=migrate&&pending.values().stream().anyMatch(other->!other.clientId.equals(client)&&other.draftKey.equals(prior.key));
        if(keepOriginal&&collision==null)makeRoom();
        if(keepOriginal)drafts.put(prior.key,remaining);else drafts.remove(prior.key);
        drafts.put(target,next);pending.remove(client);
        if(activeKey.equals(prior.key))activeKey=target;
        revision=increment(revision);return new Acknowledgement(true,clear,migrate,prior.key,target);
    }
    public synchronized JSONObject toJson()throws Exception{
        JSONArray d=new JSONArray(),p=new JSONArray();for(Draft draft:drafts.values())d.put(draft.json());for(Pending item:pending.values())p.put(item.json());
        return new JSONObject().put("format",FORMAT).put("revision",revision).put("activeKey",activeKey).put("drafts",d).put("pending",p);
    }
    public static OwnerComposerState fromJson(JSONObject json)throws Exception {
        if(!FORMAT.equals(json.getString("format")))throw new IllegalArgumentException("草稿版本不受支持，请保留原文件");
        OwnerComposerState state=new OwnerComposerState();state.revision=integer(json,"revision",1,Long.MAX_VALUE);state.activeKey=json.getString("activeKey");
        JSONArray drafts=json.getJSONArray("drafts"),pending=json.getJSONArray("pending");if(drafts.length()<1||drafts.length()>MAX_DRAFTS||pending.length()>MAX_PENDING)throw new IllegalArgumentException("草稿记录超出有界范围");
        for(int i=0;i<drafts.length();i++){JSONObject row=drafts.getJSONObject(i);Draft d=new Draft(row.getString("key"),row.getString("conversationId"),integer(row,"revision",1,Long.MAX_VALUE),row.getString("text"),row.getString("mode"),row.getJSONArray("attachments"),(int)integer(row,"scrollIndex",0,100000),(int)integer(row,"scrollOffset",0,1000000),row.getString("anchorMessage"),row.getString("observedMessage"),bool(row,"newReplies"),row.getString("voiceText"),row.getString("voiceInputId"),row.has("ownerSourceRefs")?row.getJSONArray("ownerSourceRefs"):new JSONArray(),row.has("ownerPreviewRefs")?row.getJSONArray("ownerPreviewRefs"):new JSONArray());if(state.drafts.put(d.key,d)!=null)throw new IllegalArgumentException("草稿身份重复");}
        if(!state.drafts.containsKey(state.activeKey))throw new IllegalArgumentException("原活动草稿不存在");
        for(int i=0;i<pending.length();i++){JSONObject row=pending.getJSONObject(i);Pending p=new Pending(row.getString("clientId"),row.getString("draftKey"),integer(row,"draftRevision",1,Long.MAX_VALUE),row.getString("fingerprint"),row.getString("conversationId"),row.getString("mode"),row.getJSONObject("originalTarget"));Draft d=state.drafts.get(p.draftKey);if(d==null||!d.conversationId.equals(p.conversationId)||p.draftRevision>d.revision||state.pending.put(p.clientId,p)!=null)throw new IllegalArgumentException("待核对输入不属于原草稿版本");}
        return state;
    }
    private Draft require(String key,long expected){Draft d=drafts.get(key);if(d==null||d.revision!=expected)throw new IllegalStateException("原草稿已变化，未覆盖新输入");return d;}
    private boolean hasPending(String key){for(Pending p:pending.values())if(p.draftKey.equals(key))return true;return false;}
    private void makeRoom(){if(drafts.size()<MAX_DRAFTS)return;for(Draft d:new ArrayList<>(drafts.values()))if(!d.key.equals(activeKey)&&!d.hasInput()&&!hasPending(d.key)){drafts.remove(d.key);return;}throw new IllegalStateException("已保留64份原草稿，请先处理已有输入；没有丢弃未发送文字或待核对身份");}
    private static Draft blank(String key,String conversation)throws Exception{return new Draft(key,conversation,1,"","queue",new JSONArray(),0,0,"","",false,"","",new JSONArray(),new JSONArray());}
    private static boolean validKey(String value){return value!=null&&(value.startsWith("new:")&&uuid(value.substring(4))||value.startsWith("conversation:")&&conversationId(value.substring(13)));}
    private static String conversationKey(String value){if(!conversationId(value))throw new IllegalArgumentException("原对话身份无效");return "conversation:"+value;}
    /** Conversation IDs are the original native facade's opaque IDs, including reviewed imports. */
    private static boolean conversationId(String value){return value!=null&&!value.isEmpty()&&value.length()<=240;}
    private static boolean uuid(String value){if(value==null)return false;try{return UUID.fromString(value).toString().equalsIgnoreCase(value);}catch(IllegalArgumentException invalid){return false;}}
    private static boolean mode(String value){return "queue".equals(value)||"steer".equals(value);}
    private static boolean blank(String value){return value.codePoints().allMatch(Character::isWhitespace);}
    private static long increment(long value){if(value==Long.MAX_VALUE)throw new IllegalStateException("草稿版本已达上限");return value+1;}
    private static long integer(JSONObject row,String key,long min,long max)throws Exception{Object raw=row.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<min||((Number)raw).longValue()>max)throw new IllegalArgumentException("草稿整数无效："+key);return ((Number)raw).longValue();}
    private static boolean bool(JSONObject row,String key)throws Exception{Object value=row.get(key);if(!(value instanceof Boolean))throw new IllegalArgumentException("草稿状态无效");return (Boolean)value;}
    private static JSONArray checkedAttachments(JSONArray files)throws Exception {
        if(files==null||files.length()>4)throw new IllegalArgumentException("每份原草稿最多4个附件引用");JSONArray checked=new JSONArray();java.util.Set<String> ids=new java.util.HashSet<>();
        for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String id=f.getString("id"),sha=f.getString("sha256"),name=f.getString("name"),mime=f.getString("mimeType");long size=integer(f,"size",0,20L*1024*1024);
            if(!uuid(id)||!sha.matches("[a-f0-9]{64}")||name.length()>512||mime.isEmpty()||mime.length()>200||!ids.add(id))throw new IllegalArgumentException("原附件引用无效");
            checked.put(new JSONObject().put("id",id).put("sha256",sha).put("name",name).put("mimeType",mime).put("size",size));
        }return checked;
    }
}
