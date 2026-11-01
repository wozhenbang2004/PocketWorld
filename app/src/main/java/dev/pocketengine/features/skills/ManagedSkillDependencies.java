package dev.pocketengine.features.skills;

import java.nio.charset.*;
import java.util.*;
import org.json.*;

/** Closed, exact wheel requirements from the already reviewed Skill package.
 * pip owns resolution/installing; this parser never executes requirement options. */
public final class ManagedSkillDependencies {
    public static final String PATH="scripts/requirements.lock";
    private ManagedSkillDependencies(){}
    public static JSONObject inspect(Map<String,byte[]> originalPackage,String interpreter,boolean preparationAllowed)throws Exception {
        byte[] bytes=originalPackage.get(PATH);JSONArray rows=new JSONArray();if(bytes==null)return new JSONObject().put("requirementsPath",JSONObject.NULL).put("requirementsSha256",JSONObject.NULL).put("packages",rows).put("networkNeeded",false).put("installationScriptsAllowed",false);
        if(!interpreter.equals("python3")||!preparationAllowed)throw new SkillExecution.Unavailable("此技能包含Python依赖锁；请由主人在本机执行准备中明确允许固定依赖准备，Shell模式不混装Python依赖");
        if(bytes.length>16384)throw new IllegalArgumentException("手机技能依赖锁超过16KiB");String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();Set<String> seen=new HashSet<>();
        for(String line:text.split("\\r?\\n",-1)){String value=line.trim();if(value.isEmpty()||value.startsWith("#"))continue;if(rows.length()>=32||value.length()>600||value.indexOf('\\')>=0)throw new IllegalArgumentException("本机技能一次仅支持32个完整固定wheel依赖，不能含续行或隐式选项");String[] words=value.split("[ \\t]+");if(words.length<2||words.length>5||!words[0].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}==[0-9][A-Za-z0-9.!+_-]{0,79}"))throw new IllegalArgumentException("每条依赖必须为明确名称==精确版本及至少一个SHA-256；不接受URL、源码包、未锁版本、标记或安装选项");String[] pin=words[0].split("==",-1);String name=pin[0].toLowerCase(Locale.ROOT).replaceAll("[-_.]+","-");if(!seen.add(name))throw new IllegalArgumentException("依赖名称重复或规范化冲突");JSONArray hashes=new JSONArray();Set<String> unique=new HashSet<>();for(int i=1;i<words.length;i++){if(!words[i].matches("--hash=sha256:[a-f0-9]{64}")||!unique.add(words[i].substring(14)))throw new IllegalArgumentException("仅接受原锁中不重复的完整SHA-256，不接受其它pip选项");hashes.put(words[i].substring(14));}rows.put(new JSONObject().put("name",name).put("version",pin[1]).put("sha256",hashes));}
        return new JSONObject().put("requirementsPath",PATH).put("requirementsSha256",SkillExecution.sha256(bytes)).put("packages",rows).put("networkNeeded",rows.length()>0).put("index","https://pypi.org/simple").put("sourceBuildAllowed",false).put("installationScriptsAllowed",false).put("onlyBinaryWheels",true).put("requiresAllHashes",true).put("implicitDependencies",false);
    }
}
