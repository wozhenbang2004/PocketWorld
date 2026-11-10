package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.vision.ocr.OcrPage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Human-only sensitive steps, not an approval/grant. Only native drivers mint this refusal. */
public final class NativeSensitiveStep {
    private NativeSensitiveStep() { }
    private static final java.util.concurrent.atomic.AtomicInteger OWNER_UPDATE_WINDOWS = new java.util.concurrent.atomic.AtomicInteger();
    /** Native Activity lifetime only. No tool argument, model text or stored receipt can acquire/release this window. */
    public static AutoCloseable openOwnerUpdateWindow() {
        OWNER_UPDATE_WINDOWS.incrementAndGet();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) OWNER_UPDATE_WINDOWS.decrementAndGet(); };
    }
    public static void requireOwnerUpdateWindow(RunControl control, long revision) throws Exception {
        if (OWNER_UPDATE_WINDOWS.get() > 0) refuse(Reason.PERMISSION_ELEVATION, control, revision);
    }
    public enum Reason { PAYMENT_CONFIRMATION, TRANSFER_CONFIRMATION, LOGIN_CODE, PERMISSION_ELEVATION, SENSITIVE_TARGET_UNCERTAIN }
    public static final class OwnerRequired extends Exception {
        private final RunControl original;
        private final long revision;
        public final Reason reason;
        private OwnerRequired(Reason reason,RunControl original,long revision) {
            super("这一步涉及付款、登录验证、应用安装或权限升级，请主人亲手完成；交还后将重新观察，不重放旧动作。");
            this.reason=reason;this.original=original;this.revision=revision;
        }
        /** The original Runner may settle only this still-unentered operation, never a prior unknown. */
        public boolean belongsTo(RunControl control,long pause) {
            return original==control&&revision==pause&&control.pauseRevision()==pause&&!control.isPaused()&&!control.isCancelled();
        }
        public JSONObject notDispatchedReceipt()throws Exception {
            return new JSONObject().put("ok",false).put("accepted",false).put("status","not_dispatched")
                    .put("code","native_sensitive_owner_required").put("reason",reason.name().toLowerCase(Locale.ROOT))
                    .put("nativeDispatched",false).put("automaticReplayAllowed",false).put("executionAuthorized",false)
                    .put("message",getMessage());
        }
    }

    private static final Pattern OTP=Pattern.compile("(?:\\b(?:otp|totp)\\b|\\bone[ -]?time (?:password|passcode|code|pin)\\b|\\b(?:verification|authentication|security|sms|login|sign in) (?:code|pin)\\b|验证码|校验码|短信码|动态口令|一次性(?:密码|口令|代码))");
    private static final Pattern PAY=Pattern.compile("(?:\\b(?:pay now|pay securely|confirm pay|confirm payment|confirm purchase|make payment|complete payment|slide to pay|swipe to pay)\\b|^(?:pay|payment)$|立即(?:支付|付款)|确认(?:支付|付款)|完成(?:支付|付款)|^(?:支付|付款)$|滑动(?:支付|付款))");
    private static final Pattern TRANSFER=Pattern.compile("(?:\\b(?:confirm transfer|transfer now|send money|confirm remittance)\\b|^transfer$|确认(?:转账|汇款)|立即(?:转账|汇款)|^(?:转账|汇款)$)");
    private static final Pattern NON_COMMIT=Pattern.compile("(?:^(?:payment|pay|transfer|transaction) (?:history|records?|receipts?|details|settings|methods?|help|status|options)\\b|(?:支付|付款|转账|汇款)(?:记录|明细|详情|设置|方式|帮助|状态)|^收款码$)");
    private static final Pattern CONFIRM=Pattern.compile("^(?:confirm|continue|submit|finish|complete|verify|sign in|log in|login|yes|ok|确认|确定|继续|提交|完成|验证|登录|同意)(?:[ !。.]*)$");
    private static final Pattern DENY=Pattern.compile("^(?:cancel|deny|don t allow|do not allow|not now|back|close|取消|拒绝|不允许|暂不|返回|关闭)(?:[ !。.]*)$");
    private static final Pattern GRANT=Pattern.compile("^(?:allow|allow all|allow access|enable|grant|activate|use|while using the app|only this time|always allow|允许|始终允许|仅此一次|仅在使用中允许|启用|开启|授权|激活|使用)(?:[ !。.]*)$");
    private static final Pattern SLIDE=Pattern.compile("(?:\\b(?:slide|swipe) to (?:pay|transfer|send money)\\b|滑动(?:支付|付款|转账|汇款))");
    private static final Pattern PAY_AMOUNT=Pattern.compile("^(?:pay|payment|支付|付款)\\s*(?:[$¥€£]|(?:usd|cny|rmb|eur|gbp)\\s*)\\s*[0-9][0-9,.]*(?:\\s*(?:usd|cny|rmb|eur|gbp|元))?$");
    private static final Pattern TRANSFER_AMOUNT=Pattern.compile("^(?:transfer|send|转账|汇款)\\s*(?:[$¥€£]|(?:usd|cny|rmb|eur|gbp)\\s*)\\s*[0-9][0-9,.]*(?:\\s*(?:usd|cny|rmb|eur|gbp|元))?$");

    /** Native history retention only. Reuses the existing sensitive-step vocabulary; never grants an action. */
    public static boolean historyContainsSensitiveStep(JSONObject nativeObservation){
        return sensitiveHistoryNodes(nativeObservation==null?null:nativeObservation.optJSONArray("nodes"));
    }
    private static boolean sensitiveHistoryNodes(JSONArray nodes){
        for(int i=0;nodes!=null&&i<nodes.length();i++){
            JSONObject node=nodes.optJSONObject(i);if(node==null)continue;if(node.optBoolean("password"))return true;
            boolean editable=node.optBoolean("editable");String label=(editable?"":node.optString("text"))+" "+node.optString("description")+" "+node.optString("hint")+" "+node.optString("labelledBy");
            if(risk(normal(label))!=null||classify(label,node.optString("resourceId",node.optString("viewId")),node.optString("packageName"),editable,editable,"")!=null||sensitiveHistoryNodes(node.optJSONArray("children")))return true;
        }return false;
    }

    /** Finite native facts BEFORE the public tree's text/body clipping; never a grant or retained UI text. */
    static final class Facts {
        final Reason click,type,labelRisk,ancestorPurpose,slide;
        final boolean blank,code,confirm,ordinaryInput,permissionSwitch,checked,truncated;
        Facts(CharSequence text,CharSequence description,CharSequence hint,String related,String resource,String pkg,String klass,boolean editable,boolean checked){
            truncated=length(text)>64000||length(description)>64000||length(hint)>64000||related.length()>64000;
            // Editable text is the current VALUE, not the field's purpose (a normal message may say "OTP").
            String purpose=purpose(editable?null:text,description,hint,related);
            Reason namedClick=classify(purpose,"",pkg,editable,false,""),namedType=classify(purpose,"",pkg,editable,true,"");
            click=namedClick!=null?namedClick:classify(purpose,resource,pkg,editable,false,"");type=namedType!=null?namedType:classify(purpose,resource,pkg,editable,true,"");
            labelRisk=risk(purpose);ancestorPurpose=risk(purpose(description,hint,related));
            blank=purpose.isEmpty();code=purpose.matches("(?:code|pin|输入|请输入|代码)");confirm=CONFIRM.matcher(purpose).matches();
            String own=purpose+" "+normal(resource);ordinaryInput=own.matches(".*(?:\\bsearch\\b|搜索|搜尋).*")||own.contains("source code")||own.contains("code editor")||own.contains("codeeditor")||own.contains("源代码")||own.contains("代码编辑");
            permissionSwitch=klass.endsWith("Switch")||klass.endsWith("CheckBox")||normal(resource).contains("switch widget");this.checked=checked;
            slide=SLIDE.matcher(purpose).find()?explicitCommit(purpose):null;
        }
        private static int length(CharSequence s){return s==null?0:s.length();}
        private static String bounded(CharSequence s){return s==null?"":s.subSequence(0,Math.min(64000,s.length())).toString();}
        private static String purpose(CharSequence... values){java.util.Set<String> parts=new java.util.LinkedHashSet<>();for(CharSequence value:values){String part=normal(bounded(value));if(!part.isEmpty())parts.add(part);}return String.join(" ",parts);}
    }

    /** Fresh forest from the same native read/resolve fence; never model-supplied observation JSON. */
    public static void requireNode(AccessibilityForest fresh,AccessibilityForest.Ref ref,String tool,RunControl control,long revision)throws Exception {
        if("phone_scroll_node".equals(tool))return; // Browsing a sensitive page is not the sensitive step.
        JSONObject target=null;
        for(int i=0;i<fresh.nodes.length();i++) {
            JSONObject node=fresh.nodes.getJSONObject(i);
            if(node.optInt("windowId",-1)==ref.windowId&&samePath(node.optJSONArray("path"),ref.path)){target=node;break;}
        }
        if(target==null)throw new IllegalStateException("native_sensitive_target_missing");
        refuse(nodeReason(fresh,target,ref,"phone_type".equals(tool)),control,revision);
    }

    /** Keyboard activation and editing retain the same human-only boundaries as the actual focused node. */
    public static void requireKey(AccessibilityForest fresh,AccessibilityForest.Ref focus,int key,RunControl control,long revision)throws Exception {
        if(!dev.pocketengine.displayhelper.PhoneKeyCodes.allowed(key))throw new IllegalArgumentException("unsupported_phone_key");
        requireOwnerUpdateWindow(control,revision);
        boolean activate=dev.pocketengine.displayhelper.PhoneKeyCodes.mayActivate(key);
        boolean edit=key==android.view.KeyEvent.KEYCODE_DEL||key==android.view.KeyEvent.KEYCODE_FORWARD_DEL||key==android.view.KeyEvent.KEYCODE_SPACE;
        if(!activate&&!edit)return;
        if(activate&&packageInstaller(fresh.packageName))refuse(Reason.PERMISSION_ELEVATION,control,revision);
        if(focus==null)throw new IllegalStateException("native_key_focus_required");
        JSONObject target=null;
        for(int i=0;i<fresh.nodes.length();i++){JSONObject candidate=fresh.nodes.getJSONObject(i);
            if(candidate.optInt("windowId",-1)==focus.windowId&&samePath(candidate.optJSONArray("path"),focus.path)){target=candidate;break;}}
        if(target==null)throw new IllegalStateException("native_sensitive_target_missing");
        if(target.optBoolean("password"))refuse(Reason.LOGIN_CODE,control,revision);
        if(focus.editable)refuse(nodeReason(fresh,target,focus,true),control,revision);
        if(activate){
            refuse(nodeReason(fresh,target,focus,false),control,revision);
            // Enter can submit an editor's form without clicking its confirmation button.
            if(focus.editable&&!focus.sensitive.ordinaryInput)refuse(context(fresh,target),control,revision);
        }
    }
    private static Reason nodeReason(AccessibilityForest forest,JSONObject target,AccessibilityForest.Ref ref,boolean typing){
        Facts facts=ref.sensitive;Reason nearby=context(forest,target),reason=typing?facts.type:facts.click;
        // A small unnamed clickable container can own its nearby noninteractive text child;
        // an entire page/canvas cannot inherit every descendant's financial words.
        if(reason==null&&facts.blank&&!typing)for(int i=0;i<forest.nodes.length();i++){
            JSONObject child=forest.nodes.optJSONObject(i);if(child==null||!child.optBoolean("visible")||child.optBoolean("clickable")||child.optBoolean("editable")||child.optInt("windowId",-1)!=ref.windowId||!descendant(child.optJSONArray("path"),ref.path))continue;
            JSONObject a=target.optJSONObject("bounds"),b=child.optJSONObject("bounds");AccessibilityForest.Ref nativeChild=forest.refs.get(child.optString("id"));
            if(a!=null&&b!=null&&nativeChild!=null&&area(a)<=8*Math.max(1,area(b))){if(nativeChild.sensitive.click!=null)reason=nativeChild.sensitive.click;else if(nearby==null)nearby=nativeChild.sensitive.labelRisk;}
        }
        if(reason==null&&!facts.ordinaryInput&&nearby!=null){
            if(nearby==Reason.LOGIN_CODE&&(target.optBoolean("editable")||typing)&&(facts.blank||facts.code))reason=Reason.LOGIN_CODE;
            else if(facts.confirm)reason=nearby;else if(facts.blank)reason=Reason.SENSITIVE_TARGET_UNCERTAIN;
        }
        if(facts.permissionSwitch&&nearby==Reason.PERMISSION_ELEVATION)reason=facts.checked?null:Reason.PERMISSION_ELEVATION;
        return reason;
    }

    /** Preserves existing pure-image input when there is no semantic evidence; it does NOT certify safety. */
    public static void requireCoordinate(AccessibilityForest fresh,int x,int y,int x2,int y2,boolean swipe,RunControl control,long revision)throws Exception {
        // Even an image-only target cannot use a missing/blank accessibility tree
        // to press a system installer's final confirmation. Back remains a separate native action.
        if (packageInstaller(fresh.packageName)) refuse(Reason.PERMISSION_ELEVATION, control, revision);
        JSONObject selected=null;double area=Double.MAX_VALUE;
        for(int i=0;i<fresh.nodes.length();i++) {
            JSONObject n=fresh.nodes.getJSONObject(i);JSONObject b=n.optJSONObject("bounds");
            if(b==null||!n.optBoolean("visible")||!n.optBoolean("enabled"))continue;
            AccessibilityForest.Ref nativeNode=fresh.refs.get(n.optString("id"));
            if(swipe&&nativeNode!=null&&nativeNode.sensitive.slide!=null&&intersects(b.optDouble("left"),b.optDouble("top"),b.optDouble("right"),b.optDouble("bottom"),x,y,x2,y2))
                refuse(nativeNode.sensitive.slide,control,revision);
            if(!(n.optBoolean("clickable")||n.optBoolean("editable")))continue;
            if(!contains(b,x,y))continue;double a=(b.optDouble("right")-b.optDouble("left"))*(b.optDouble("bottom")-b.optDouble("top"));
            if(a<area){selected=n;area=a;}
        }
        if(selected!=null) {
            AccessibilityForest.Ref ref=fresh.refs.get(selected.optString("id"));
            if(ref!=null)requireNode(fresh,ref,"phone_click_node",control,revision);
        } else if(!swipe) {
            // Only evidence geometrically attributable to this point, not the window's other text.
            for(int i=0;i<fresh.nodes.length();i++){JSONObject n=fresh.nodes.getJSONObject(i);JSONObject b=n.optJSONObject("bounds");
                AccessibilityForest.Ref nativeNode=fresh.refs.get(n.optString("id"));
                if(b!=null&&nativeNode!=null&&n.optBoolean("visible")&&near(b,x,y,20)&&nodeReason(fresh,n,nativeNode,false)!=null)
                    refuse(Reason.SENSITIVE_TARGET_UNCERTAIN,control,revision);
            }
        }
    }

    /** Finite native OCR evidence, retained with original image geometry, never receipt/model authority. */
    public static final class OcrEvidence {
        private final List<OcrPage.Line> lines;
        private final int width,height;
        private OcrEvidence(OcrPage page) {
            lines=new ArrayList<>(page.lines);width=page.width;height=page.height;
        }
        void requireTarget(String text,RunControl control,long revision)throws Exception {
            refuse(classify(normal(text),"","",false,false,""),control,revision);
        }
        void requireTarget(String text,int originalLine,RunControl control,long revision)throws Exception {
            OcrPage.Line parent=null;for(OcrPage.Line line:lines)if(line.index==originalLine){parent=line;break;}
            if(parent==null)throw new IllegalArgumentException("original_ocr_line_missing");
            // A word box is not a separate business control: its original enclosing line can
            // positively say "Transfer history", "Payment methods" or "Do not allow".
            // This exception is local to that native line, never a whole-page safe-list.
            String line=normal(parent.text);String local=context(parent);
            Reason commit=explicitCommit(line);
            boolean nonCommit=commit==null&&(NON_COMMIT.matcher(line).find()||DENY.matcher(line).matches()
                    ||line.matches(".*(?:\\bhelp\\b|\\blearn\\b|documentation|tutorial|帮助|教程|说明).*")
                    ||line.equals("continue shopping")||line.equals("继续购物"));
            Reason own=classify(normal(text),"","",false,false,local),whole=classify(line,"","",false,false,local);
            refuse(nonCommit?null:whole!=null?whole:commit!=null?Reason.SENSITIVE_TARGET_UNCERTAIN:own,control,revision);
        }
        void requireCoordinate(int x,int y,boolean swipe,RunControl control,long revision)throws Exception {
            requireCoordinate(x,y,x,y,swipe,control,revision);
        }
        void requireCoordinate(int x,int y,int x2,int y2,boolean swipe,RunControl control,long revision)throws Exception {
            double px=(double)x*(width-1)/1000,py=(double)y*(height-1)/1000,ex=(double)x2*(width-1)/1000,ey=(double)y2*(height-1)/1000;OcrPage.Line selected=null;
            if(swipe)for(OcrPage.Line line:lines)if(SLIDE.matcher(normal(line.text)).find()&&intersects(line.left-.5,line.top-.5,line.right-.5,line.bottom-.5,px,py,ex,ey))
                refuse(explicitCommit(normal(line.text)),control,revision);
            for(OcrPage.Line line:lines)if(px>=line.left-.5&&px<line.right-.5&&py>=line.top-.5&&py<line.bottom-.5){selected=line;break;}
            if(selected!=null)requireTarget(selected.text,selected.index,control,revision);
            else if(!swipe)for(OcrPage.Line line:lines)if(px>=line.left-lineHeight(line)&&px<=line.right+lineHeight(line)&&py>=line.top-lineHeight(line)&&py<=line.bottom+lineHeight(line)
                    &&classify(normal(line.text),"","",false,false,context(line))!=null)refuse(Reason.SENSITIVE_TARGET_UNCERTAIN,control,revision);
        }
        private String context(OcrPage.Line target) {
            StringBuilder out=new StringBuilder(target.text);for(OcrPage.Line line:lines)if(line!=target&&associated(line.left,line.top,line.right,line.bottom,target.left,target.top,target.right,target.bottom))out.append(' ').append(line.text);return normal(out.toString());
        }
        private static int lineHeight(OcrPage.Line line){return Math.max(1,line.bottom-line.top);}
    }
    static OcrEvidence ocrEvidence(OcrPage page){return new OcrEvidence(page);}

    private static void refuse(Reason reason,RunControl control,long revision)throws Exception {
        control.check();PhoneReadGate.checkCurrent();
        if(control.isPaused()||control.pauseRevision()!=revision)throw new InterruptedException("native_sensitive_control_changed");
        if(reason!=null)throw new OwnerRequired(reason,control,revision);
    }
    static Reason classify(String label,String resource,String pkg,boolean editable,boolean typing,String context) {
        label=normal(label).trim();String id=normal(resource),where=normal(context),own=label+" "+id;
        String actionId=normal(resource.substring(resource.lastIndexOf('/')+1)).replaceAll("^(?:btn|button|action) ","").replaceAll(" (?:btn|button)$","");
        if(DENY.matcher(label).matches())return null;
        if (!typing && !editable && packageInstaller(pkg) && !label.matches("(?:cancel|back|close|done|取消|返回|关闭|完成)"))
            return Reason.PERMISSION_ELEVATION;
        if (!typing && !editable && (label.matches("(?:安装|立即安装|确认安装|安装新版本|安装更新|install|install now|install update|install new version|confirm installation)")
                || "dev.pocketengine".equals(pkg) && (id.endsWith("owner app update install") || id.endsWith("owner app update download"))))
            return Reason.PERMISSION_ELEVATION;
        boolean search=own.matches(".*(?:\\bsearch\\b|搜索|搜尋).*"),editor=own.contains("source code")||own.contains("code editor")||own.contains("codeeditor")||own.contains("源代码")||own.contains("代码编辑");
        if(!search&&!editor&&OTP.matcher(own).find()&&(editable||typing||!label.matches(".*(?:\\bhelp\\b|\\blearn\\b|documentation|example|tutorial|帮助|教程|示例|说明).*")))return Reason.LOGIN_CODE;
        if(!typing&&!editable) {
            Reason commit=explicitCommit(label);
            if(commit!=null)return NON_COMMIT.matcher(label).find()?Reason.SENSITIVE_TARGET_UNCERTAIN:commit;
            if(!NON_COMMIT.matcher(actionId).find()){Reason identified=explicitCommit(actionId);if(identified!=null)return identified;}
        }
        boolean system=pkg.matches("(?:com\\.(?:android|google\\.android)\\.(?:permissioncontroller|packageinstaller)|android)")||id.contains("permission allow");
        if(!typing&&(system&&id.contains("permission allow")||GRANT.matcher(label).matches()&&(system||permissionContext(where))))return Reason.PERMISSION_ELEVATION;
        Reason nearby=risk(where);
        if(!search&&!editor&&nearby!=null) {
            if(nearby==Reason.LOGIN_CODE&&(editable||typing)&&(label.isEmpty()||label.matches("(?:code|pin|输入|请输入|代码)")))return Reason.LOGIN_CODE;
            if(CONFIRM.matcher(label).matches())return nearby;
            if(label.isEmpty()&&!typing)return Reason.SENSITIVE_TARGET_UNCERTAIN;
        }
        return null;
    }
    private static boolean packageInstaller(String pkg) {
        return pkg != null && (pkg.equals("com.android.packageinstaller") || pkg.equals("com.google.android.packageinstaller")
                || pkg.endsWith(".packageinstaller"));
    }
    private static Reason risk(String context) {
        String text=normal(context);
        Reason commit=explicitCommit(text);if(commit!=null)return commit;
        if(text.contains("confirm transfer")||text.contains("transfer amount")||text.contains("确认转账")||text.contains("转账金额")||text.contains("确认汇款")||text.contains("收款人")&&(text.contains("转账")||text.contains("汇款")))return Reason.TRANSFER_CONFIRMATION;
        if(text.contains("payment confirmation")||text.contains("confirm payment")||text.contains("amount to pay")||text.contains("total due")||text.contains("支付金额")||text.contains("付款金额")||text.contains("确认支付")||text.contains("确认付款")||text.contains("pay now"))return Reason.PAYMENT_CONFIRMATION;
        if(OTP.matcher(text).find())return Reason.LOGIN_CODE;
        if(permissionContext(text))return Reason.PERMISSION_ELEVATION;
        return null;
    }
    private static Reason explicitCommit(String label){
        if(TRANSFER.matcher(label).find()||TRANSFER_AMOUNT.matcher(label).matches()||SLIDE.matcher(label).find()&&(label.contains("transfer")||label.contains("money")||label.contains("转账")||label.contains("汇款")))return Reason.TRANSFER_CONFIRMATION;
        if(PAY.matcher(label).find()||PAY_AMOUNT.matcher(label).matches()||SLIDE.matcher(label).find())return Reason.PAYMENT_CONFIRMATION;
        return null;
    }
    private static boolean permissionContext(String s) {
        return s.contains("permission request")||s.contains("allow access")||s.contains("accessibility service")||s.contains("full control of your device")||s.contains("device administrator")||s.contains("install unknown apps")||s.contains("superuser request")||s.contains("权限申请")||s.contains("请求权限")||s.contains("无障碍服务")||s.contains("设备管理器")||s.contains("安装未知应用")||s.contains("超级用户请求");
    }
    private static Reason context(AccessibilityForest forest,JSONObject target) {
        AccessibilityForest.Ref own=forest.refs.get(target.optString("id"));Reason reason=own==null?null:own.sensitive.labelRisk;JSONArray path=target.optJSONArray("path");JSONObject box=target.optJSONObject("bounds");
        for(int i=0;i<forest.nodes.length();i++){
            JSONObject n=forest.nodes.optJSONObject(i);if(n==null||!n.optBoolean("visible")||n.optInt("windowId",-1)!=target.optInt("windowId",-1)||n==target)continue;
            JSONArray other=n.optJSONArray("path");
            // Ancestor purpose/labels, NOT an ancestor's aggregated article/chat body.
            AccessibilityForest.Ref nativeNode=forest.refs.get(n.optString("id"));if(nativeNode==null)continue;
            if(prefix(other,path)&&other.length()<path.length()){if(reason==null)reason=nativeNode.sensitive.ancestorPurpose;}
            // Only direct, nearby, non-interactive leaf labels can label a sibling field/button.
            else if(other!=null&&path!=null&&other.length()==path.length()&&sameParent(other,path)&&!n.optBoolean("clickable")&&!n.optBoolean("editable")&&n.optJSONArray("children")!=null&&n.optJSONArray("children").length()==0&&box!=null){
                JSONObject b=n.optJSONObject("bounds");if(reason==null&&b!=null&&associated(b.optDouble("left"),b.optDouble("top"),b.optDouble("right"),b.optDouble("bottom"),box.optDouble("left"),box.optDouble("top"),box.optDouble("right"),box.optDouble("bottom")))reason=nativeNode.sensitive.labelRisk;
            }
        }
        return reason;
    }
    private static String normal(String value){return Normalizer.normalize(value==null?"":value.replaceAll("([a-z])([A-Z])","$1 $2"),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[_:/-]+"," ").replaceAll("\\s+"," ").trim();}
    private static boolean samePath(JSONArray path,int[] expected){if(path==null||path.length()!=expected.length)return false;for(int i=0;i<expected.length;i++)if(path.optInt(i,-1)!=expected[i])return false;return true;}
    private static boolean descendant(JSONArray path,int[] parent){if(path==null||path.length()<=parent.length)return false;for(int i=0;i<parent.length;i++)if(path.optInt(i,-1)!=parent[i])return false;return true;}
    private static boolean contains(JSONObject b,int x,int y){return x>=b.optDouble("left")&&x<b.optDouble("right")&&y>=b.optDouble("top")&&y<b.optDouble("bottom");}
    private static boolean near(JSONObject b,double x,double y,double pad){return x>=b.optDouble("left")-pad&&x<=b.optDouble("right")+pad&&y>=b.optDouble("top")-pad&&y<=b.optDouble("bottom")+pad;}
    private static double area(JSONObject b){return Math.max(0,b.optDouble("right")-b.optDouble("left"))*Math.max(0,b.optDouble("bottom")-b.optDouble("top"));}
    private static boolean sameParent(JSONArray a,JSONArray b){if(a.length()!=b.length())return false;for(int i=0;i<a.length()-1;i++)if(a.optInt(i,-1)!=b.optInt(i,-1))return false;return true;}
    private static boolean prefix(JSONArray a,JSONArray b){if(a==null||b==null||a.length()>b.length())return false;for(int i=0;i<a.length();i++)if(a.optInt(i,-1)!=b.optInt(i,-1))return false;return true;}
    private static boolean associated(double l,double t,double r,double b,double tl,double tt,double tr,double tb){double gapY=Math.max(0,Math.max(t,tt)-Math.min(b,tb)),gapX=Math.max(0,Math.max(l,tl)-Math.min(r,tr));return b-t<=4*Math.max(1,tb-tt)&&(Math.min(r,tr)>Math.max(l,tl)&&gapY<=2*Math.max(b-t,tb-tt)||Math.min(b,tb)>Math.max(t,tt)&&gapX<=2*Math.max(b-t,tb-tt));}
    private static boolean intersects(double l,double t,double r,double b,double x,double y,double ex,double ey){double dx=ex-x,dy=ey-y,lo=0,hi=1;double[] p={-dx,dx,-dy,dy},q={x-l,r-x,y-t,b-y};for(int i=0;i<4;i++){if(p[i]==0){if(q[i]<0)return false;}else{double at=q[i]/p[i];if(p[i]<0)lo=Math.max(lo,at);else hi=Math.min(hi,at);if(lo>hi)return false;}}return true;}
}
