package dev.pocketengine.features.devices;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Source-side contracts for one owner-chosen text file, with an optional separately confirmed listing. */
public final class OwnerWindowsWorkspaceRead {
    public static final String FIELD="ownerWindowsWorkspaceRead";
    public static final String PICKER="ownerWindowsWorkspacePicker", PICKER_FORMAT="pocketworld.owner-windows-workspace-picker.v1";
    public static final String REVIEW="pocketworld.owner-windows-workspace-read-review.v1";
    public static final String START="pocketworld.owner-windows-workspace-read-start.v1";
    public static final String DIRECTORY_REVIEW="pocketworld.owner-windows-workspace-directory-review.v1";
    public static final String DIRECTORY_START="pocketworld.owner-windows-workspace-directory-start.v1";
    public static final String LISTED_FILE_REVIEW="pocketworld.owner-windows-workspace-listed-file-review.v1";
    public static final String LISTED_FILE_START="pocketworld.owner-windows-workspace-listed-file-start.v1";
    public static final String TOOL="device_invoke_tool", READ="workspace.read", LIST="workspace.list";
    public static final int MAX_DISPLAY_BYTES=8*1024;
    private static final Pattern WINDOWS_RESERVED=Pattern.compile("(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?",Pattern.CASE_INSENSITIVE);
    private OwnerWindowsWorkspaceRead(){}

    public static boolean marked(JSONObject task){return task!=null&&task.has(FIELD);}

    /** Keep the reviewed spelling byte-for-byte. The Windows native handle still owns path admission. */
    public static String path(String value)throws Exception {
        if(value==null||value.isEmpty()||value.length()>2048)throw invalidPath();
        for(String part:value.split("/",-1))if(part.isEmpty()||part.endsWith(".")||part.endsWith(" ")||
                WINDOWS_RESERVED.matcher(part).matches()||part.codePoints().anyMatch(c->invisible(c)||"<>:\"|?*\\".indexOf(c)>=0))throw invalidPath();
        DeviceSourceToolContracts.validate(DirectedWorkspaceTarget.VERSION,READ,new JSONObject().put("path",value));
        return value;
    }
    private static IllegalArgumentException invalidPath(){return new IllegalArgumentException("请输入工作区内准确的相对文件路径，例如 docs/README.md。不接受绝对路径、目录跳转或 Windows 保留名称。");}
    private static boolean invisible(int c){
        return switch(Character.getType(c)){
            case Character.CONTROL,Character.FORMAT,Character.SURROGATE,Character.LINE_SEPARATOR,Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }

    public static void requireGrant(DirectedWorkspaceTarget target,JSONObject route,PairingCoordinator.OutgoingGrant actual,long now,boolean picker)throws Exception {
        if(!target.matches(actual)||!route.getString("grantId").equals(actual.grantId())||
                route.getLong("authorizationEpoch")!=actual.authorizationEpoch()||route.getLong("deadline")>actual.deadline())
            throw new SecurityException("原电脑或它签署的工作区授权已变化，没有读取其他位置。");
        actual.checkCurrent(now);JSONObject grant=actual.grant();
        if(!route.getString("grantFingerprint").equals(DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(grant)))||
                !PairingCoordinator.contains(grant.getJSONArray("capabilities"),"tool.invoke")||
                !PairingCoordinator.contains(grant.getJSONArray("toolNames"),READ)||
                picker&&!PairingCoordinator.contains(grant.getJSONArray("toolNames"),LIST)||grant.getLong("toolBudget")<(picker?2:1)||
                !PairingCoordinator.resourceContains(grant.getJSONArray("resources"),route.getJSONObject("resourceBinding")))
            throw new SecurityException(picker?"原当前授权没有这个工作区的列目录和读取权限或两次份额。":"原当前授权不准从这个工作区读取文件。");
    }

    public enum Body { COMPLETE, TOO_LARGE, REJECTED, UNVERIFIED }

    /** The caller obtains this receipt from the original native acceptance ledger, not another read or a model. */
    private static boolean matchesReceipt(JSONObject saved,DirectedWorkspaceTarget target,JSONObject route,String task,String run,String operation)throws Exception {
        if(saved==null||!"OperationReceipt".equals(saved.opt("messageType"))||
                !Boolean.TRUE.equals(saved.opt("accepted"))||!operation.equals(saved.opt("operationId"))||
                !target.deviceId().equals(saved.opt("executorDeviceId"))||!target.instanceId().equals(saved.opt("instanceId"))||
                !saved.optString("jobId").matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||
                !saved.optString("argumentsDigest").matches("[a-f0-9]{64}")||saved.has("localTaskId")||saved.has("runId")||
                saved.has("executorEpoch")||saved.has("cancellation"))return false;
        JSONObject audience=saved.optJSONObject("audience");
        if(audience==null||!"operation".equals(audience.opt("kind"))||!operation.equals(audience.opt("objectId"))||
                !task.equals(audience.opt("rootTaskId"))||!run.equals(audience.opt("requestRunId"))||audience.optInt("requestExecutorEpoch")!=1||
                !target.deviceId().equals(audience.opt("targetDeviceId"))||!target.instanceId().equals(audience.opt("instanceId"))||
                !route.getString("grantId").equals(audience.opt("grantId"))||route.getLong("authorizationEpoch")!=audience.optLong("authorizationEpoch")||
                !saved.optString("argumentsDigest").equals(audience.opt("argumentsDigest"))||audience.has("sourceShareId")||
                !audience.optString("callerDeviceId").matches("pw1_[a-f0-9]{64}")||!audience.optString("callerDeviceId").equals(audience.opt("originDeviceId"))||
                audience.optJSONObject("resourceBinding")==null||!DeviceProtocol.resourceEquals(route.getJSONObject("resourceBinding"),audience.getJSONObject("resourceBinding")))
            return false;
        return true;
    }
    public static Body body(JSONObject saved,DirectedWorkspaceTarget target,JSONObject route,String task,String run,String operation,String path)throws Exception {
        if(!matchesReceipt(saved,target,route,task,run,operation))return Body.UNVERIFIED;
        if(Set.of("failed","rejected","cancelled").contains(saved.optString("status")))return Body.REJECTED;
        JSONObject file=saved.optJSONObject("payload");
        if(!"succeeded".equals(saved.opt("status"))||!"reported".equals(saved.opt("effectState"))||file==null||
                !DeviceProtocol.objectKeys(file).equals(Set.of("path","content","version","bytes","revisionRetained"))||
                !path.equals(file.opt("path"))||!(file.opt("content") instanceof String content)||
                !(file.opt("revisionRetained") instanceof Boolean)||content.length()>24576)return Body.UNVERIFIED;
        int size=(int)DeviceProtocol.integer(file.opt("bytes"),"workspace.read bytes",0,24576);
        var exact=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content));
        if(exact.remaining()!=size)return Body.UNVERIFIED;
        byte[] bytes=new byte[size];exact.get(bytes);
        if(!("sha256:"+DeviceProtocol.sha256(bytes)).equals(file.opt("version")))return Body.UNVERIFIED;
        // Match the current reader's display bound as well as the unmodified wire bytes.
        String lines=content.replace("\r\n","\n").replace('\r','\n');
        int display=lines.codePoints().map(c->(c!='\n'&&c!='\t'&&(invisible(c)||Character.getType(c)==Character.PRIVATE_USE||Character.getType(c)==Character.UNASSIGNED))
                ?3:c<=0x7F?1:c<=0x7FF?2:c<=0xFFFF?3:4).sum();
        return size>MAX_DISPLAY_BYTES||display>MAX_DISPLAY_BYTES?Body.TOO_LARGE:Body.COMPLETE;
    }

    public record Directory(Body state,Map<Integer,String> choices) {}
    private static Directory noDirectory(Body state){return new Directory(state,Map.of());}
    /** The bounded raw indices (not displayed names or filtered positions) identify the new file choice. */
    public static Directory directory(JSONObject saved,DirectedWorkspaceTarget target,JSONObject route,String task,String run,String operation)throws Exception {
        if(!matchesReceipt(saved,target,route,task,run,operation))return noDirectory(Body.UNVERIFIED);
        if(Set.of("failed","rejected","cancelled").contains(saved.optString("status")))return noDirectory(Body.REJECTED);
        JSONObject data=saved.optJSONObject("payload");
        if(!"succeeded".equals(saved.opt("status"))||!"reported".equals(saved.opt("effectState"))||data==null||
                !DeviceProtocol.objectKeys(data).equals(Set.of("path","directoryIdentity","truncated","files"))||
                !"".equals(data.opt("path"))||!(data.opt("truncated") instanceof Boolean)||
                DeviceProtocol.canonicalBytes(data).length>24576)return noDirectory(Body.UNVERIFIED);
        JSONArray identity=data.optJSONArray("directoryIdentity"),files=data.optJSONArray("files");
        if(identity==null||identity.length()!=3||files==null||files.length()>200)return noDirectory(Body.UNVERIFIED);
        for(int i=0;i<3;i++)DeviceProtocol.integer(identity.opt(i),"directory identity",0,0xffff_ffffL);
        var names=new java.util.HashSet<String>();Map<Integer,String> choices=new LinkedHashMap<>();
        for(int i=0;i<files.length();i++){
            JSONObject row=files.getJSONObject(i);
            if(!DeviceProtocol.objectKeys(row).equals(Set.of("name","path","kind","bytes","modifiedAt"))||
                    !(row.opt("name") instanceof String name)||name.isEmpty()||name.codePointCount(0,name.length())>255||
                    name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0||!name.equals(row.opt("path"))||
                    !(row.opt("kind") instanceof String kind)||!Set.of("file","directory","reparse_point","unsupported").contains(kind)||
                    !names.add(name))return noDirectory(Body.UNVERIFIED);
            long bytes=DeviceProtocol.integer(row.opt("bytes"),"directory entry bytes",0,DeviceProtocol.MAX_SAFE_INTEGER);
            DeviceProtocol.integer(row.opt("modifiedAt"),"directory entry time",0,DeviceProtocol.MAX_SAFE_INTEGER);
            if(choices.size()<8&&"file".equals(kind)&&bytes<=MAX_DISPLAY_BYTES){
                try{choices.put(i,path(name));}catch(Exception ineligible){/* Never replace an ineligible spelling. */}
            }
        }
        return new Directory(Body.COMPLETE,Map.copyOf(choices));
    }
}
