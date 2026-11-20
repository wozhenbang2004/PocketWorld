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
    private static final String RESTRICTED_FORMAT="pocketworld.owner-composer.v1.first-message-paused";
    private static final String TASK_ONLY_FORMAT="pocketworld.owner-composer.v1.first-task-defaults-locked";
    private static final String ONE_MESSAGE_FORMAT="pocketworld.owner-composer.v1.one-message-learning-exclusion";
    public static final int MAX_DRAFTS=64,MAX_PENDING=64,MAX_TEXT=48_000,MAX_STATE_BYTES=1_048_576;
    /** Absence is the older query-only format. FENCED is not evidence of a send. */
    public enum FirstChatPhase { PREPARED, FENCED, RELEASED }
    public static final class Draft {
        public final String key,conversationId,text,mode,anchorMessage,observedMessage,voiceText,voiceInputId;
        public final long revision;
        public final int scrollIndex,scrollOffset;
        public final boolean newReplies;
        private final String attachments,sourceRefs,previewRefs,objectRefs;
        private Draft(String key,String conversation,long revision,String text,String mode,JSONArray files,
                      int index,int offset,String anchor,String observed,boolean unread,String voice,String voiceId,JSONArray sources,JSONArray previews,JSONArray objects)throws Exception {
            this.key=key;conversationId=conversation;this.revision=revision;this.text=text;this.mode=mode;
            attachments=checkedAttachments(files).toString();sourceRefs=OwnerSourceReferences.checked(sources).toString();previewRefs=dev.pocketengine.features.local.preview.PreviewElementRef.checked(previews).toString();objectRefs=OwnerObjectReferences.checked(objects).toString();scrollIndex=index;scrollOffset=offset;
            anchorMessage=anchor;observedMessage=observed;newReplies=unread;voiceText=voice;voiceInputId=voiceId;
            if(!validKey(key)||!conversation.isEmpty()&&!conversationId(conversation)||revision<1||text.length()>MAX_TEXT
                    ||!mode(mode)||index<0||index>100_000||offset<0||offset>1_000_000
                    ||anchor.length()>300||observed.length()>300||voice.length()>4000
                    ||!voiceId.isEmpty()&&!uuid(voiceId)||voice.isEmpty()!=voiceId.isEmpty())throw new IllegalArgumentException("原私聊草稿格式无效");
            if(key.startsWith("conversation:")&&!key.equals(conversationKey(conversation)))throw new IllegalArgumentException("草稿不属于原对话");
            if(isWorkKey(key)&&(conversation.isEmpty()||!attachments.equals("[]")||!sourceRefs.equals("[]")||!previewRefs.equals("[]")||!objectRefs.equals("[]")||!voice.isEmpty()))throw new IllegalArgumentException("工作补充须保留原对话与独立文字草稿");
            JSONArray selected=previewRefs();for(int i=0;i<selected.length();i++)if(!conversation.equals(selected.getJSONObject(i).getString("conversationId")))throw new SecurityException("元素引用不属于这份原对话草稿");
        }
        public JSONArray attachments()throws Exception{return new JSONArray(attachments);}
        /** Selected source locators only; currentness and body projection remain native host responsibilities. */
        public JSONArray sourceRefs()throws Exception{return new JSONArray(sourceRefs);}
        /** Low-trust selected DOM observations; persistence cannot recreate their native document. */
        public JSONArray previewRefs()throws Exception{return new JSONArray(previewRefs);}
        public JSONArray objectRefs()throws Exception{return new JSONArray(objectRefs);}
        private boolean hasInput(){return !text.isEmpty()||!attachments.equals("[]")||!voiceText.isEmpty()||!sourceRefs.equals("[]")||!previewRefs.equals("[]")||!objectRefs.equals("[]");}
        private boolean sameInput(Draft other){return other!=null&&key.equals(other.key)&&conversationId.equals(other.conversationId)&&
                revision==other.revision&&text.equals(other.text)&&mode.equals(other.mode)&&attachments.equals(other.attachments)&&
                sourceRefs.equals(other.sourceRefs)&&previewRefs.equals(other.previewRefs)&&objectRefs.equals(other.objectRefs)&&
                voiceText.equals(other.voiceText)&&voiceInputId.equals(other.voiceInputId);}
        private JSONObject json()throws Exception{return new JSONObject().put("key",key).put("conversationId",conversationId)
                .put("revision",revision).put("text",text).put("mode",mode).put("attachments",attachments()).put("ownerSourceRefs",sourceRefs()).put("ownerPreviewRefs",previewRefs()).put("ownerObjectRefs",objectRefs())
                .put("scrollIndex",scrollIndex).put("scrollOffset",scrollOffset).put("anchorMessage",anchorMessage)
                .put("observedMessage",observedMessage).put("newReplies",newReplies).put("voiceText",voiceText).put("voiceInputId",voiceInputId);}
        private Draft content(String value,String nextMode,JSONArray files,String voice,String voiceId)throws Exception {
            return new Draft(key,conversationId,increment(revision),value,nextMode,files,scrollIndex,scrollOffset,anchorMessage,observedMessage,newReplies,voice,voiceId,sourceRefs(),previewRefs(),objectRefs());
        }
    }
    public static final class Pending {
        public final String clientId,draftKey,fingerprint,conversationId,mode;
        public final long draftRevision;
        private final String originalTarget;
        public final FirstChatPhase firstChatPhase;
        /** Provenance of a private read-only composer intent, never permission to run or read again. */
        public final boolean primaryDisplayOnly;
        // Two Android activity instances may restore the same pending client before the first one retires.
        // A shared weak process fence tracks that original callback only; it cannot create work or survive a process restart.
        private static final java.util.WeakHashMap<String,java.lang.ref.WeakReference<DisplayFence>> fences=new java.util.WeakHashMap<>();
        private static final class DisplayFence{final String key;volatile String closedConversation;volatile java.util.function.BooleanSupplier origin;DisplayFence(String key){this.key=key;}}
        private static synchronized DisplayFence fence(String key){var weak=fences.get(key);var live=weak==null?null:weak.get();if(live==null){live=new DisplayFence(key);fences.remove(key);fences.put(live.key,new java.lang.ref.WeakReference<>(live));}return live;}
        private final DisplayFence display;
        public void watchOriginalDisplaySender(java.util.function.BooleanSupplier origin){if(display!=null)synchronized(display){if(display.closedConversation==null)display.origin=origin;}}
        public void releaseOriginalDisplaySender(java.util.function.BooleanSupplier origin){if(display!=null)synchronized(display){if(display.origin==origin)display.origin=null;}}
        boolean mayBeginDisplayRead(){return display!=null&&display.closedConversation==null;}
        boolean retainLookupWhenSenderGone(String storedConversation){if(display==null)return false;synchronized(display){
            if(display.closedConversation!=null){if(!display.closedConversation.equals(storedConversation))throw new IllegalStateException("原来源窗口已变化");return true;}
            if(display.origin!=null&&display.origin.getAsBoolean())return false;display.closedConversation=storedConversation;display.origin=null;return true;
        }}
        private boolean provedMissingIn(String conversation){return display!=null&&conversation!=null&&conversation.equals(display.closedConversation);}
        private Pending(String client,String key,long revision,String fingerprint,String conversation,String mode,JSONObject target,boolean primaryDisplayOnly,FirstChatPhase firstChatPhase)throws Exception{
            if(primaryDisplayOnly&&(isWorkKey(key)||target!=null&&target.length()!=0))throw new IllegalArgumentException("本机读数定位不能指向原工作");
            if(firstChatPhase!=null&&(primaryDisplayOnly||key==null||!key.startsWith("new:")||!conversation.isEmpty()||target!=null&&target.length()!=0))
                throw new SecurityException("首次派发边界只属于原未绑定的普通私聊");
            this.firstChatPhase=firstChatPhase;
            this.primaryDisplayOnly=primaryDisplayOnly;display=primaryDisplayOnly?fence(client):null;
            if(!uuid(client)||!validKey(key)||revision<1||!fingerprint.matches("[a-f0-9]{64}")||!conversation.isEmpty()&&!conversationId(conversation)||!mode(mode))throw new IllegalArgumentException("原输入待核对身份无效");
            clientId=client;draftKey=key;draftRevision=revision;this.fingerprint=fingerprint;conversationId=conversation;this.mode=mode;
            JSONObject locator=new JSONObject();if(target!=null&&target.length()>0){
                String task=target.getString("taskId"),run=target.getString("runId");long epoch=integer(target,"epoch",1,Integer.MAX_VALUE);
                if(!uuid(task)||!uuid(run))throw new IllegalArgumentException("原任务定位无效");
                locator.put("taskId",task).put("runId",run).put("epoch",epoch);
            }originalTarget=locator.toString();
            if(isWorkKey(key)&&(conversation.isEmpty()||locator.length()==0||!key.equals(workKey(locator.getString("taskId"),locator.getString("runId"),locator.getLong("epoch")))))throw new IllegalArgumentException("工作补充不属于原执行草稿");
        }
        /** Display/reconciliation locator only. Never pass it to a launch or resume operation. */
        public JSONObject originalTarget()throws Exception{return new JSONObject(originalTarget);}
        private boolean sameRequest(Pending other){return other!=null&&clientId.equals(other.clientId)&&draftKey.equals(other.draftKey)
                &&draftRevision==other.draftRevision&&fingerprint.equals(other.fingerprint)&&conversationId.equals(other.conversationId)
                &&mode.equals(other.mode)&&originalTarget.equals(other.originalTarget)&&primaryDisplayOnly==other.primaryDisplayOnly;}
        private JSONObject json()throws Exception{JSONObject row=new JSONObject().put("clientId",clientId).put("draftKey",draftKey).put("draftRevision",draftRevision)
                .put("fingerprint",fingerprint).put("conversationId",conversationId).put("mode",mode).put("originalTarget",originalTarget());
            if(primaryDisplayOnly)row.put("primaryDisplayOnly",true);
            if(firstChatPhase!=null)row.put("firstChatPhase",firstChatPhase.name());return row;}
    }
    public static final class Acknowledgement {
        public final boolean matched,cleared,migrated;
        public final String previousKey,currentKey;
        private Acknowledgement(boolean matched,boolean cleared,boolean migrated,String before,String after){this.matched=matched;this.cleared=cleared;this.migrated=migrated;previousKey=before;currentKey=after;}
    }
    private final LinkedHashMap<String,Draft> drafts=new LinkedHashMap<>();
    private final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
    // Optional restriction of an original unbound draft, never a grant or a global default.
    private final java.util.LinkedHashSet<String> firstMessagePaused=new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> firstTaskDefaultsLocked=new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> oneMessageExcluded=new java.util.LinkedHashSet<>();
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
    /** A work's unsent supplement is independent of the selected conversation editor. */
    public synchronized Draft workDraft(String conversation,String task,String run,long epoch)throws Exception {
        if(!conversationId(conversation))throw new IllegalArgumentException("工作原对话身份无效");
        String key=workKey(task,run,epoch);Draft existing=drafts.get(key);
        if(existing!=null){if(!existing.conversationId.equals(conversation))throw new SecurityException("工作草稿不属于原对话");return existing;}
        makeRoom();Draft created=blank(key,conversation);drafts.put(key,created);revision=increment(revision);return created;
    }
    public synchronized List<Draft> workDrafts(String task){
        if(!uuid(task))throw new IllegalArgumentException("工作身份无效");String prefix="work:"+task+":";List<Draft> result=new ArrayList<>();
        for(Draft draft:drafts.values())if(draft.key.startsWith(prefix)&&(draft.hasInput()||hasPending(draft.key)))result.add(draft);
        return Collections.unmodifiableList(result);
    }
    public synchronized Draft newConversation()throws Exception {
        if(active().conversationId.isEmpty())return active();
        for(Draft draft:drafts.values())if(draft.conversationId.isEmpty()&&!hasPending(draft.key)){activeKey=draft.key;revision=increment(revision);return draft;}
        makeRoom();activeKey="new:"+UUID.randomUUID();Draft draft=blank(activeKey,"");drafts.put(activeKey,draft);revision=increment(revision);return draft;
    }
    /** A freshly selected editor, not a send receipt or an execution grant. */
    public static final class FirstHostAnchor {
        private final OwnerComposerState issuer;
        private final Draft original;
        public final String conversationId,messageId;
        private FirstHostAnchor(OwnerComposerState issuer,Draft original){
            this.issuer=issuer;this.original=original;
            conversationId=firstHostId("conversation",original.key);
            messageId=firstHostId("message",original.key);
        }
        private static String firstHostId(String kind,String key){
            return UUID.nameUUIDFromBytes(("pocketworld:private-host-anchor:v1:"+kind+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        }
        Draft requireCurrent()throws Exception{
            synchronized(issuer){
                if(issuer.freshHostDraft(original.key,original.revision)!=original)throw new SecurityException("原未发草稿已变化");
                return original;
            }
        }
    }
    private Draft freshHostDraft(String key,long expected)throws Exception {
        Draft draft=require(key,expected);
        if(!activeKey.equals(key)||!key.startsWith("new:")||!draft.conversationId.isEmpty()||hasPending(key)||!"queue".equals(draft.mode)
                ||firstMessagePaused.contains(key)||oneMessageExcluded.contains(key)||firstTaskDefaultsLocked.contains(key)
                ||blank(draft.text)||draft.text.length()>4000||draft.text.indexOf('\0')>=0||!draft.voiceText.isEmpty()
                ||draft.attachments().length()!=0||draft.sourceRefs().length()!=0||draft.previewRefs().length()!=0||draft.objectRefs().length()!=0)
            throw new SecurityException("只保存当前纯文字的新请求；含引用、指定范围或待核对输入的原草稿不变");
        return draft;
    }
    public synchronized FirstHostAnchor reviewFirstHostAnchor(String key,long expected)throws Exception{
        return new FirstHostAnchor(this,freshHostDraft(key,expected));
    }
    /** Switch only the matching editor after its exact words exist as a passive local message. */
    public synchronized void adoptFirstHostAnchor(FirstHostAnchor anchor,String conversation)throws Exception{
        if(anchor==null||anchor.issuer!=this||!anchor.conversationId.equals(conversation))throw new SecurityException("本机保存回执不是原草稿");
        Draft old=anchor.requireCurrent();String target=conversationKey(conversation);
        if(drafts.containsKey(target))throw new SecurityException("原对话另有编辑记录，未覆盖原草稿");
        long nextVersion=increment(revision);
        Draft moved=new Draft(target,conversation,increment(old.revision),"","queue",old.attachments(),
                0,0,"","",false,"","",old.sourceRefs(),old.previewRefs(),old.objectRefs());
        drafts.remove(old.key);drafts.put(target,moved);activeKey=target;revision=nextVersion;
    }
    /** Explicitly return to an original pending editor, not whichever conversation is now visible. */
    public synchronized Draft selectPending(String client)throws Exception{
        Pending p=pending.get(client);if(p==null||!drafts.containsKey(p.draftKey))throw new IllegalArgumentException("原待核对输入不存在");
        if(isWorkKey(p.draftKey))throw new IllegalArgumentException("请回到原工作核对这份独立补充");
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
        Draft next=new Draft(key,prior.conversationId,increment(prior.revision),prior.text,prior.mode,prior.attachments(),prior.scrollIndex,prior.scrollOffset,prior.anchorMessage,prior.observedMessage,prior.newReplies,prior.voiceText,prior.voiceInputId,checked,prior.previewRefs(),prior.objectRefs());
        drafts.put(key,next);revision=increment(revision);return next;
    }
    /** Add/remove a native preview selection without replacing text, quotes, files or pending ACKs. */
    public synchronized Draft editPreviewRefs(String key,long expected,JSONArray nativeSelections)throws Exception {
        Draft prior=require(key,expected);JSONArray checked=dev.pocketengine.features.local.preview.PreviewElementRef.checked(nativeSelections);
        for(int i=0;i<checked.length();i++)if(!prior.conversationId.equals(checked.getJSONObject(i).getString("conversationId")))throw new SecurityException("预览引用不能进入另一个原对话草稿");
        if(prior.previewRefs.equals(checked.toString()))return prior;
        Draft next=new Draft(key,prior.conversationId,increment(prior.revision),prior.text,prior.mode,prior.attachments(),prior.scrollIndex,prior.scrollOffset,prior.anchorMessage,prior.observedMessage,prior.newReplies,prior.voiceText,prior.voiceInputId,prior.sourceRefs(),checked,prior.objectRefs());
        drafts.put(key,next);revision=increment(revision);return next;
    }
    /** Change only the owner's visible object selection, preserving unsent text and other references. */
    public synchronized Draft editObjectRefs(String key,long expected,JSONArray nativeSelections)throws Exception {
        Draft prior=require(key,expected);if(isWorkKey(key))throw new IllegalArgumentException("对象引用请保留在管家对话草稿");
        JSONArray checked=OwnerObjectReferences.checked(nativeSelections);if(prior.objectRefs.equals(checked.toString()))return prior;
        Draft next=new Draft(key,prior.conversationId,increment(prior.revision),prior.text,prior.mode,prior.attachments(),prior.scrollIndex,prior.scrollOffset,prior.anchorMessage,prior.observedMessage,prior.newReplies,prior.voiceText,prior.voiceInputId,prior.sourceRefs(),prior.previewRefs(),checked);
        drafts.put(key,next);revision=increment(revision);return next;
    }
    public synchronized Draft reading(String key,int index,int offset,String anchor,String observed,boolean unread)throws Exception{
        Draft prior=drafts.get(key);if(prior==null)throw new IllegalArgumentException("原阅读位置不存在");
        if(prior.scrollIndex==index&&prior.scrollOffset==offset&&prior.anchorMessage.equals(anchor)&&prior.observedMessage.equals(observed)&&prior.newReplies==unread)return prior;
        Draft next=new Draft(key,prior.conversationId,prior.revision,prior.text,prior.mode,prior.attachments(),index,offset,anchor,observed,unread,prior.voiceText,prior.voiceInputId,prior.sourceRefs(),prior.previewRefs(),prior.objectRefs());
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
    public synchronized boolean firstMessagePaused(String key){return firstMessagePaused.contains(key);}
    public synchronized Draft setFirstMessagePaused(String key,long expected,boolean paused)throws Exception {
        Draft prior=require(key,expected);
        if(!key.equals(activeKey)||!key.startsWith("new:")||!prior.conversationId.isEmpty()||hasPending(key))throw new IllegalStateException("请核对没有待确认首条输入的原新草稿");
        if(paused==firstMessagePaused.contains(key))return prior;
        Draft next=prior.content(prior.text,prior.mode,prior.attachments(),prior.voiceText,prior.voiceInputId);
        long nextRevision=increment(revision);
        if(paused)firstMessagePaused.add(key);else firstMessagePaused.remove(key);
        drafts.put(key,next);revision=nextRevision;return next;
    }
    /** The restriction belongs to one ordinary owner input, not to a task, a project or the conversation created later. */
    private static boolean oneMessageTarget(Draft d){
        return d!=null&&(d.conversationId.isEmpty()?d.key.startsWith("new:")&&uuid(d.key.substring(4)):d.key.equals(conversationKey(d.conversationId)));
    }
    public synchronized boolean oneMessageEligible(String key){return activeKey.equals(key)&&oneMessageTarget(drafts.get(key));}
    public synchronized boolean oneMessageExcluded(String key){return oneMessageExcluded.contains(key);}
    public synchronized boolean firstTaskDefaultsLocked(String key){return firstTaskDefaultsLocked.contains(key);}
    public synchronized Draft setOneMessageExcluded(String key,long expected,boolean excluded)throws Exception {
        return setOneMessageChoice(key,expected,excluded,excluded&&firstTaskDefaultsLocked.contains(key));
    }
    public synchronized Draft setOneMessageChoice(String key,long expected,boolean excluded,boolean lockDefaults)throws Exception {
        Draft prior=require(key,expected);
        if(lockDefaults&&(!excluded||!prior.conversationId.isEmpty()||!key.startsWith("new:")))throw new IllegalArgumentException("只可在未发送的新私聊首条限制这项委托的默认偏好写入");
        if(!oneMessageEligible(key)||hasPending(key))
            throw new IllegalStateException("请先核对这段私人对话的上一条输入回执");
        if(excluded==oneMessageExcluded.contains(key)&&lockDefaults==firstTaskDefaultsLocked.contains(key))return prior;
        Draft next=prior.content(prior.text,prior.mode,prior.attachments(),prior.voiceText,prior.voiceInputId);long nextRevision=increment(revision);
        if(excluded)oneMessageExcluded.add(key);else oneMessageExcluded.remove(key);
        if(lockDefaults)firstTaskDefaultsLocked.add(key);else firstTaskDefaultsLocked.remove(key);
        drafts.put(key,next);revision=nextRevision;return next;
    }
    public synchronized Pending prepare(String client,String key,long expected,String fingerprint,JSONObject target)throws Exception {
        return prepare(client,key,expected,fingerprint,target,false);
    }
    public synchronized Pending prepare(String client,String key,long expected,String fingerprint,JSONObject target,boolean excluded)throws Exception {
        return prepare(client,key,expected,fingerprint,target,excluded,false);
    }
    public synchronized Pending prepare(String client,String key,long expected,String fingerprint,JSONObject target,boolean excluded,boolean lockDefaults)throws Exception {
        return prepare(client,key,expected,fingerprint,target,excluded,lockDefaults,false,null);
    }
    public synchronized Pending prepareFirstChat(String client,String key,long expected,String fingerprint,boolean excluded,boolean lockDefaults)throws Exception{
        Draft draft=require(key,expected);
        if(!activeKey.equals(key)||blank(draft.text)||!draft.attachments.equals("[]")||!draft.sourceRefs.equals("[]")||
                !draft.previewRefs.equals("[]")||!draft.objectRefs.equals("[]")||!draft.voiceText.isEmpty())
            throw new SecurityException("首次派发边界只属于当前普通文字草稿");
        return prepare(client,key,expected,fingerprint,null,excluded,lockDefaults,false,FirstChatPhase.PREPARED);
    }
    public synchronized Pending preparePrimaryDisplay(String client,String key,long expected,String fingerprint,boolean excluded,boolean lockDefaults)throws Exception{
        Draft draft=require(key,expected);
        if(dev.pocketengine.phone.reading.PrimaryDisplayQuestion.parse(draft.text)==null||draft.attachments().length()!=0||draft.sourceRefs().length()!=0||draft.previewRefs().length()!=0||draft.objectRefs().length()!=0)throw new SecurityException("仅能登记原私人草稿的单一主屏读数");
        return prepare(client,key,expected,fingerprint,null,excluded,lockDefaults,true,null);
    }
    private Pending prepare(String client,String key,long expected,String fingerprint,JSONObject target,boolean excluded,boolean lockDefaults,boolean localRead,FirstChatPhase firstChatPhase)throws Exception {
        if(lockDefaults!=firstTaskDefaultsLocked.contains(key)||lockDefaults&&(!excluded||target!=null))throw new IllegalStateException("此草稿须按原新委托范围发送，未交给另一项工作");
        if(excluded!=oneMessageExcluded.contains(key))throw new IllegalStateException("原草稿的单条记忆限制不适用于另一提交入口，没有发送");
        Draft draft=require(key,expected);if((blank(draft.text)&&draft.attachments().length()==0)||draft.text.trim().length()>12000)throw new IllegalArgumentException("请保留草稿并将本条输入缩至12000字内；纯附件消息必须已有真实附件选择");
        Pending duplicate=matchingPending(key,fingerprint);if(duplicate!=null)return duplicate;
        if(excluded&&hasPending(key))throw new IllegalStateException("本条排除自动整理的原输入尚待核对，未发送第二条");
        if(pending.containsKey(client)||pending.size()>=MAX_PENDING)throw new IllegalStateException("待核对输入已达本机上限，请先查询原回执；未发送新输入");
        Pending next=new Pending(client,key,draft.revision,fingerprint,draft.conversationId,draft.mode,target,localRead,firstChatPhase);pending.put(client,next);revision=increment(revision);return next;
    }
    public synchronized Pending transitionPreparedFirstChat(Pending expected,FirstChatPhase phase)throws Exception {
        if(expected==null||pending.get(expected.clientId)!=expected||expected.firstChatPhase!=FirstChatPhase.PREPARED
                ||(phase!=FirstChatPhase.FENCED&&phase!=FirstChatPhase.RELEASED)||!activeKey.equals(expected.draftKey))
            throw new IllegalStateException("原待核对的首次输入已变化");
        require(expected.draftKey,expected.draftRevision);
        long nextRevision=increment(revision);
        Pending next=new Pending(expected.clientId,expected.draftKey,expected.draftRevision,expected.fingerprint,expected.conversationId,
                expected.mode,expected.originalTarget(),false,phase);
        pending.put(next.clientId,next);revision=nextRevision;return next;
    }
    /** After the native store confirms RELEASED (or reads it back on cold open), keep the draft verbatim. */
    public synchronized boolean forgetReleasedFirstChat(Pending released){
        if(released==null||released.firstChatPhase!=FirstChatPhase.RELEASED||pending.get(released.clientId)!=released)return false;
        long nextRevision=increment(revision);pending.remove(released.clientId);revision=nextRevision;return true;
    }
    /** Serialized with the original owner-composer file CAS. An ordinary autosave
     * cannot advance PREPARED, drop it, or reopen either terminal phase. */
    void requireFirstChatTransitions(OwnerComposerState disk,Pending before,Pending after){
        if((before==null)!=(after==null))throw new IllegalArgumentException("原首次输入切换缺少身份");
        if(before!=null){
            Pending prior=disk==null?null:disk.pending.get(before.clientId),next=pending.get(before.clientId);
            String key=before.draftKey;
            if(prior==null||!prior.sameRequest(before)||prior.firstChatPhase!=FirstChatPhase.PREPARED||before.firstChatPhase!=FirstChatPhase.PREPARED||
                    !prior.sameRequest(after)||next==null||!next.sameRequest(after)||next.firstChatPhase!=after.firstChatPhase||
                    (after.firstChatPhase!=FirstChatPhase.FENCED&&after.firstChatPhase!=FirstChatPhase.RELEASED)||
                    !disk.activeKey.equals(key)||!activeKey.equals(key)||disk.drafts.get(key).revision!=prior.draftRevision||
                    !disk.drafts.get(key).sameInput(drafts.get(key))||
                    firstMessagePaused.contains(key)!=disk.firstMessagePaused.contains(key)||
                    firstTaskDefaultsLocked.contains(key)!=disk.firstTaskDefaultsLocked.contains(key)||
                    oneMessageExcluded.contains(key)!=disk.oneMessageExcluded.contains(key))
                throw new IllegalStateException("磁盘上的原首次输入已变化，未切换派发状态");
        }
        if(disk!=null)for(Pending prior:disk.pending.values()){
            if(prior.firstChatPhase==null)continue;
            Pending next=pending.get(prior.clientId);
            if(next==null&&prior.firstChatPhase!=FirstChatPhase.PREPARED)continue; // Native receipt / already released.
            if(prior.sameRequest(next)&&(next.firstChatPhase==prior.firstChatPhase||
                    before!=null&&before.clientId.equals(prior.clientId)))continue;
            throw new IllegalStateException("普通草稿保存不能改写原首次输入的派发边界");
        }
        for(Pending next:pending.values())if(next.firstChatPhase!=null){
            Pending prior=disk==null?null:disk.pending.get(next.clientId);
            if(prior==null?next.firstChatPhase!=FirstChatPhase.PREPARED:prior.firstChatPhase==null)
                throw new IllegalStateException("这笔输入没有本版本的原准备记录");
        }
    }
    /** Caller has validated an exact native not-admitted receipt. Keep every draft byte;
     * only a later explicit owner Send may prepare a new identity. */
    public synchronized boolean rejectNotAdmitted(String client,String conversation)throws Exception {
        Pending p=pending.get(client);if(p==null)return false;
        if(!conversationId(conversation)||!p.conversationId.equals(conversation))throw new SecurityException("拒收回执不属于原输入对话");
        if(!drafts.containsKey(p.draftKey))throw new IllegalStateException("原草稿不可核对，未移除待核对身份");
        pending.remove(client);revision=increment(revision);return true;
    }
    /** A direct native send result proves persistence was never attempted. Retain all editor bytes. */
    public synchronized boolean rejectBeforeAdmission(String client,String conversation)throws Exception {
        Pending p=pending.get(client);if(p==null)return false;
        if(!p.conversationId.equals(conversation))throw new SecurityException("未发送回执不属于原输入对话");
        if(!drafts.containsKey(p.draftKey))throw new IllegalStateException("原草稿不可核对，未移除待核对身份");
        pending.remove(client);revision=increment(revision);return true;
    }
    /** Caller supplies an already validated native ACK, never an LLM/tool self-reported success. */
    public synchronized Acknowledgement acknowledge(String client,String conversation)throws Exception {
        return acknowledge(client,conversation,false);
    }
    /** Release the waiting locator only. Never erase the submitted/editor text or loosen its restrictions. */
    public synchronized Acknowledgement finishMissingDisplayReading(Pending original,String savedConversation)throws Exception{
        if(original==null||!original.provedMissingIn(savedConversation)||pending.get(original.clientId)!=original||!drafts.containsKey(original.draftKey))throw new SecurityException("原本机读数草稿已变化");
        if(!savedConversation.isEmpty()&&(!conversationId(savedConversation)||!original.conversationId.isEmpty()&&!original.conversationId.equals(savedConversation)))throw new SecurityException("原本机读数不属于这个对话");
        // A preserved editor with special first-message rules or a colliding draft stays exactly where it is.
        if(!savedConversation.isEmpty()&&drafts.get(original.draftKey).revision==original.draftRevision&&!oneMessageExcluded.contains(original.draftKey)&&!firstMessagePaused.contains(original.draftKey)
                &&!firstTaskDefaultsLocked.contains(original.draftKey)&&(!original.conversationId.isEmpty()||!drafts.containsKey(conversationKey(savedConversation))))
            return acknowledge(original.clientId,savedConversation,true);
        pending.remove(original.clientId);revision=increment(revision);return new Acknowledgement(true,false,false,original.draftKey,original.draftKey);
    }
    private Acknowledgement acknowledge(String client,String conversation,boolean keepText)throws Exception {
        Pending p=pending.get(client);if(p==null)return new Acknowledgement(false,false,false,activeKey,activeKey);
        if(!conversationId(conversation)||!p.conversationId.isEmpty()&&!p.conversationId.equals(conversation))throw new SecurityException("回执不属于原输入对话");
        Draft prior=drafts.get(p.draftKey);if(prior==null)throw new IllegalStateException("原草稿不可核对，未清除当前输入");
        String target=isWorkKey(prior.key)?prior.key:conversationKey(conversation);boolean clear=!keepText&&prior.revision==p.draftRevision,migrate=!prior.key.equals(target);
        Draft remaining=clear?new Draft(prior.key,prior.conversationId,increment(prior.revision),"",prior.mode,new JSONArray(),prior.scrollIndex,prior.scrollOffset,prior.anchorMessage,prior.observedMessage,prior.newReplies,"","",new JSONArray(),new JSONArray(),new JSONArray()):prior;
        boolean releaseOne=oneMessageExcluded.contains(prior.key);
        if(releaseOne&&!clear)remaining=prior.content(prior.text,prior.mode,prior.attachments(),prior.voiceText,prior.voiceInputId);
        Draft collision=drafts.get(target);
        if(migrate&&collision!=null&&collision.hasInput()&&remaining.hasInput())throw new IllegalStateException("输入已受理，但原对话另有未发送草稿；两份均保留，请回原输入核对");
        Draft next=migrate&&collision!=null&&collision.hasInput()?new Draft(target,conversation,increment(Math.max(collision.revision,remaining.revision)),collision.text,collision.mode,collision.attachments(),collision.scrollIndex,collision.scrollOffset,collision.anchorMessage,collision.observedMessage,collision.newReplies,collision.voiceText,collision.voiceInputId,collision.sourceRefs(),collision.previewRefs(),collision.objectRefs()):new Draft(target,conversation,remaining.revision,remaining.text,remaining.mode,remaining.attachments(),remaining.scrollIndex,remaining.scrollOffset,remaining.anchorMessage,remaining.observedMessage,remaining.newReplies,remaining.voiceText,remaining.voiceInputId,remaining.sourceRefs(),remaining.previewRefs(),remaining.objectRefs());
        // Separate first sends from an unbound editor may have created different
        // conversations. One receipt cannot bind another uncertain input to its target.
        boolean keepOriginal=migrate&&pending.values().stream().anyMatch(other->!other.clientId.equals(client)&&other.draftKey.equals(prior.key));
        if(keepOriginal&&collision==null)makeRoom();
        if(keepOriginal)drafts.put(prior.key,remaining);else {drafts.remove(prior.key);firstMessagePaused.remove(prior.key);}
        drafts.put(target,next);pending.remove(client);if(releaseOne){oneMessageExcluded.remove(prior.key);firstTaskDefaultsLocked.remove(prior.key);}
        if(activeKey.equals(prior.key))activeKey=target;
        revision=increment(revision);return new Acknowledgement(true,clear,migrate,prior.key,target);
    }
    public synchronized JSONObject toJson()throws Exception{
        JSONArray d=new JSONArray(),p=new JSONArray();for(Draft draft:drafts.values())d.put(draft.json());for(Pending item:pending.values())p.put(item.json());
        JSONObject result=new JSONObject().put("format",!firstTaskDefaultsLocked.isEmpty()?TASK_ONLY_FORMAT:!oneMessageExcluded.isEmpty()?ONE_MESSAGE_FORMAT:firstMessagePaused.isEmpty()?FORMAT:RESTRICTED_FORMAT).put("revision",revision).put("activeKey",activeKey).put("drafts",d).put("pending",p);
        if(!oneMessageExcluded.isEmpty())result.put("oneMessageExcluded",new JSONArray(oneMessageExcluded));
        if(!firstTaskDefaultsLocked.isEmpty())result.put("firstTaskDefaultsLocked",new JSONArray(firstTaskDefaultsLocked));
        if(!firstMessagePaused.isEmpty())result.put("firstMessagePaused",new JSONArray(firstMessagePaused));
        return result;
    }
    public static OwnerComposerState fromJson(JSONObject json)throws Exception {
        String format=json.getString("format");boolean restricted=RESTRICTED_FORMAT.equals(format);
        boolean taskOnly=TASK_ONLY_FORMAT.equals(format),one=ONE_MESSAGE_FORMAT.equals(format)||taskOnly;
        if(taskOnly!=json.has("firstTaskDefaultsLocked")||!FORMAT.equals(format)&&!restricted&&!one||one!=json.has("oneMessageExcluded")||!one&&restricted!=json.has("firstMessagePaused"))throw new IllegalArgumentException("草稿版本或首条记忆范围不受支持，请保留原文件");
        OwnerComposerState state=new OwnerComposerState();state.revision=integer(json,"revision",1,Long.MAX_VALUE);state.activeKey=json.getString("activeKey");
        JSONArray drafts=json.getJSONArray("drafts"),pending=json.getJSONArray("pending");if(drafts.length()<1||drafts.length()>MAX_DRAFTS||pending.length()>MAX_PENDING)throw new IllegalArgumentException("草稿记录超出有界范围");
        for(int i=0;i<drafts.length();i++){JSONObject row=drafts.getJSONObject(i);Draft d=new Draft(row.getString("key"),row.getString("conversationId"),integer(row,"revision",1,Long.MAX_VALUE),row.getString("text"),row.getString("mode"),row.getJSONArray("attachments"),(int)integer(row,"scrollIndex",0,100000),(int)integer(row,"scrollOffset",0,1000000),row.getString("anchorMessage"),row.getString("observedMessage"),bool(row,"newReplies"),row.getString("voiceText"),row.getString("voiceInputId"),row.has("ownerSourceRefs")?row.getJSONArray("ownerSourceRefs"):new JSONArray(),row.has("ownerPreviewRefs")?row.getJSONArray("ownerPreviewRefs"):new JSONArray(),row.has("ownerObjectRefs")?row.getJSONArray("ownerObjectRefs"):new JSONArray());if(state.drafts.put(d.key,d)!=null)throw new IllegalArgumentException("草稿身份重复");}
        if(json.has("firstMessagePaused")){
            JSONArray restrictions=json.getJSONArray("firstMessagePaused");if(restrictions.length()<1||restrictions.length()>drafts.length())throw new IllegalArgumentException("新草稿记忆范围不可核对");
            for(int i=0;i<restrictions.length();i++){Object raw=restrictions.get(i);if(!(raw instanceof String)||!((String)raw).startsWith("new:")||!state.drafts.containsKey(raw)||!state.drafts.get(raw).conversationId.isEmpty()||!state.firstMessagePaused.add((String)raw))throw new IllegalArgumentException("新草稿记忆范围不可核对");}
        }
        if(json.has("oneMessageExcluded")){
            JSONArray restrictions=json.getJSONArray("oneMessageExcluded");
            if(restrictions.length()<1||restrictions.length()>drafts.length())throw new IllegalArgumentException("单条记忆限制无法核对");
            for(int i=0;i<restrictions.length();i++){Object raw=restrictions.get(i);Draft d=raw instanceof String?state.drafts.get(raw):null;
                if(!oneMessageTarget(d)||!state.oneMessageExcluded.add(d.key))throw new IllegalArgumentException("单条记忆限制不属于原私人对话");}
        }
        if(taskOnly){
            JSONArray restrictions=json.getJSONArray("firstTaskDefaultsLocked");
            if(restrictions.length()<1||restrictions.length()>state.oneMessageExcluded.size())throw new IllegalArgumentException("首项委托的偏好范围无法核对");
            for(int i=0;i<restrictions.length();i++){Object raw=restrictions.get(i);
                if(!(raw instanceof String key)||!state.oneMessageExcluded.contains(key)||!key.startsWith("new:")||!state.drafts.get(key).conversationId.isEmpty()||!state.firstTaskDefaultsLocked.add(key))throw new IllegalArgumentException("默认偏好限制不属于原新草稿");}
        }
        if(!state.drafts.containsKey(state.activeKey)||isWorkKey(state.activeKey))throw new IllegalArgumentException("原活动对话草稿不存在");
        for(int i=0;i<pending.length();i++){JSONObject row=pending.getJSONObject(i);Pending p=new Pending(row.getString("clientId"),row.getString("draftKey"),integer(row,"draftRevision",1,Long.MAX_VALUE),row.getString("fingerprint"),row.getString("conversationId"),row.getString("mode"),row.getJSONObject("originalTarget"),Boolean.TRUE.equals(row.opt("primaryDisplayOnly")),row.has("firstChatPhase")?FirstChatPhase.valueOf(row.getString("firstChatPhase")):null);Draft d=state.drafts.get(p.draftKey);if(d==null||!d.conversationId.equals(p.conversationId)||p.draftRevision>d.revision||state.pending.put(p.clientId,p)!=null)throw new IllegalArgumentException("待核对输入不属于原草稿版本");}
        for(String key:state.oneMessageExcluded)if(state.pending.values().stream().filter(item->item.draftKey.equals(key)).count()>1)throw new IllegalArgumentException("单条记忆限制不能绑定多条待核对输入");
        return state;
    }
    private Draft require(String key,long expected){Draft d=drafts.get(key);if(d==null||d.revision!=expected)throw new IllegalStateException("原草稿已变化，未覆盖新输入");return d;}
    private boolean hasPending(String key){for(Pending p:pending.values())if(p.draftKey.equals(key))return true;return false;}
    private void makeRoom(){if(drafts.size()<MAX_DRAFTS)return;for(Draft d:new ArrayList<>(drafts.values()))if(!d.key.equals(activeKey)&&!d.hasInput()&&!hasPending(d.key)&&!firstMessagePaused.contains(d.key)&&!oneMessageExcluded.contains(d.key)){drafts.remove(d.key);return;}throw new IllegalStateException("已保留64份原草稿，请先处理已有输入；没有丢弃未发送文字或待核对身份");}
    private static Draft blank(String key,String conversation)throws Exception{return new Draft(key,conversation,1,"","queue",new JSONArray(),0,0,"","",false,"","",new JSONArray(),new JSONArray(),new JSONArray());}
    public static boolean isWorkKey(String value){return value!=null&&value.startsWith("work:");}
    public static String workKey(String task,String run,long epoch){
        if(!uuid(task)||!uuid(run)||epoch<1||epoch>Integer.MAX_VALUE)throw new IllegalArgumentException("工作执行草稿身份无效");
        return "work:"+task+":"+run+":"+epoch;
    }
    private static boolean validWorkKey(String value){
        if(!isWorkKey(value))return false;String[] parts=value.split(":",-1);if(parts.length!=4)return false;
        try{return value.equals(workKey(parts[1],parts[2],Long.parseLong(parts[3])));}catch(IllegalArgumentException invalid){return false;}
    }
    private static boolean validKey(String value){return value!=null&&(value.startsWith("new:")&&uuid(value.substring(4))||value.startsWith("conversation:")&&conversationId(value.substring(13))||validWorkKey(value));}
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
