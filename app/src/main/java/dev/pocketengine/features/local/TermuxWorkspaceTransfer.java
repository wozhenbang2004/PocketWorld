package dev.pocketengine.features.local;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Our bounded text transfer format, carried by the official RUN_COMMAND stdout callback. */
public final class TermuxWorkspaceTransfer {
    private TermuxWorkspaceTransfer(){}
    public static final int MAX_BYTES=32000;
    /** One literal selected path; never scans the workspace/dependency tree. */
    public static String selectedScript(String selectedPath,String selectionDigest)throws Exception {
        String path=VersionedWorkspace.path(selectedPath);if(!path.equals(path.trim())||!selectionDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("选定文件身份无效");
        StringBuilder script=new StringBuilder("set -eu\nartifact_fail() { printf '%s\\n' 'Selected text artifact unavailable or changed' >&2; exit 31; }\n");String prefix="";
        for(String part:path.split("/")){prefix=prefix.isEmpty()?part:prefix+"/"+part;script.append("[ ! -L ").append(quote("./"+prefix)).append(" ] || artifact_fail\n");}
        script.append("f=").append(quote("./"+path)).append("\n[ -f \"$f\" ] || artifact_fail\n")
                .append("n=$(wc -c < \"$f\")\n[ \"$n\" -le 32000 ] || artifact_fail\nbefore=$(sha256sum < \"$f\")\n")
                .append("data=$(base64 < \"$f\" | tr -d '\\n')\nafter=$(sha256sum < \"$f\")\n[ \"$before\" = \"$after\" ] || artifact_fail\n")
                .append("[ \"$n\" = \"$(wc -c < \"$f\")\" ] || artifact_fail\nname=$(printf '%s' ").append(quote(path)).append(" | base64 | tr -d '\\n')\n")
                .append("line=\"$name:$data:${before%% *}:$n\"\n[ \"${#line}\" -le 60000 ] || artifact_fail\n")
                .append("printf '%s\\n' ").append(quote("POCKETWORLD_ARTIFACT_1:"+selectionDigest)).append("\nprintf '%s\\n' \"$line\"\nprintf '%s\\n' 'END:1'\n");
        return script.toString();
    }
    public static JSONObject decodeSelected(JSONObject receipt,String selectedPath,String selectionDigest)throws Exception {
        Object exit=receipt.opt("exitCode");
        if(!"completed".equals(receipt.optString("status"))||!(exit instanceof Integer||exit instanceof Long)||((Number)exit).longValue()!=0||!Boolean.TRUE.equals(receipt.opt("receiptReceived"))
                ||receipt.has("outputTruncated")&&!Boolean.FALSE.equals(receipt.opt("outputTruncated"))||receipt.has("cancelRequested")&&!Boolean.FALSE.equals(receipt.opt("cancelRequested"))||!"pre_exec_manifest_verified".equals(receipt.optString("sourceCheckStatus")))throw new SecurityException("需完整成功且源码核验通过的选定产物回执");
        if(!selectionDigest.matches("[a-f0-9]{64}"))throw new SecurityException("产物选择摘要无效");String text=receipt.getString("stdout");if(text.length()>61000)throw new IllegalArgumentException("产物回执超过大小限制");
        String[] lines=text.split("\n",-1);if(lines.length!=4||!lines[0].equals("POCKETWORLD_ARTIFACT_1:"+selectionDigest)||!lines[2].equals("END:1")||!lines[3].isEmpty())throw new IllegalArgumentException("选定产物回执不完整或属于其他选择");
        String[] fields=lines[1].split(":",-1);if(fields.length!=4||!fields[2].matches("[a-f0-9]{64}")||!fields[3].matches("0|[1-9][0-9]{0,4}"))throw new IllegalArgumentException("选定产物记录无效");
        String path=VersionedWorkspace.path(utf8(Base64.getDecoder().decode(fields[0])));if(!path.equals(selectedPath))throw new SecurityException("回执返回了未选择的文件");
        byte[] bytes=Base64.getDecoder().decode(fields[1]);if(bytes.length>MAX_BYTES||Integer.parseInt(fields[3])!=bytes.length)throw new IllegalArgumentException("返回文件超过32000字节或长度不完整");
        String content=utf8(bytes);if(content.indexOf('\0')>=0||!dev.pocketengine.features.local.project.ProjectSources.sha256(bytes).equals(fields[2]))throw new SecurityException("返回产物不是完整UTF-8文本或摘要不匹配");
        return new JSONObject().put("path",path).put("content",content).put("sha256",fields[2]).put("bytes",bytes.length);
    }
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    static String collectScript(){
        // Fixed POSIX shell program, not supplied by the model/user. No interpreter installation.
        // A partial, failed, truncated or concurrently changing export cannot pass the decoder.
        return """
            set -eu
            count=0
            total=0
            encoded_total=0
            scan() {
              for f in "$1"/* "$1"/.[!.]* "$1"/..?*; do
                if [ ! -e "$f" ] && [ ! -L "$f" ]; then continue; fi
                if [ -L "$f" ]; then printf '%s\\n' 'Symbolic links are not transferable' >&2; exit 21; fi
                if [ -d "$f" ]; then
                  if [ "${#f}" -gt 242 ]; then exit 22; fi
                  scan "$f"
                elif [ -f "$f" ]; then
                  count=$((count + 1))
                  if [ "$count" -gt 100 ]; then printf '%s\\n' 'At most 100 text files' >&2; exit 23; fi
                  n=$(wc -c < "$f")
                  total=$((total + n))
                  if [ "$total" -gt 32000 ]; then printf '%s\\n' 'Return transfer exceeds 32000 bytes; use a smaller workspace' >&2; exit 24; fi
                  before=$(sha256sum < "$f")
                  name=$(printf '%s' "${f#./}" | base64 | tr -d '\\n')
                  data=$(base64 < "$f" | tr -d '\\n')
                  after=$(sha256sum < "$f")
                  if [ "$before" != "$after" ]; then printf '%s\\n' 'File changed during collection' >&2; exit 25; fi
                  line="$name:$data"
                  encoded_total=$((encoded_total + ${#line} + 1))
                  if [ "$encoded_total" -gt 60000 ]; then printf '%s\\n' 'Encoded return transfer too large' >&2; exit 26; fi
                  printf '%s\\n' "$line"
                else
                  printf '%s\\n' 'Special files are not transferable' >&2; exit 27
                fi
              done
            }
            printf '%s\\n' 'POCKETWORLD_TEXT_1'
            scan .
            printf 'END:%s\\n' "$count"
            """;
    }
    static JSONArray decode(JSONObject receipt)throws Exception {
        if(!"completed".equals(receipt.optString("status"))||receipt.optInt("exitCode",-1)!=0||!receipt.optBoolean("receiptReceived")||receipt.optBoolean("outputTruncated"))
            throw new IllegalStateException("需收到完整、成功且未截断的Termux文件回执；不会用部分结果覆盖项目");
        String text=receipt.getString("stdout");if(text.length()>61000)throw new IllegalArgumentException("文件回执超过大小限制");
        String[] lines=text.split("\n",-1);if(lines.length<3||!"POCKETWORLD_TEXT_1".equals(lines[0])||!lines[lines.length-1].isEmpty())throw new IllegalArgumentException("文件回执格式不完整");
        int count=lines.length-3;if(count>100||!lines[lines.length-2].equals("END:"+count))throw new IllegalArgumentException("文件回执缺少完整结束标记");
        JSONArray result=new JSONArray();Set<String> names=new HashSet<>();int bytes=0;
        for(int i=1;i<=count;i++){
            String line=lines[i];int separator=line.indexOf(':');if(separator<1||line.indexOf(':',separator+1)>=0)throw new IllegalArgumentException("文件回执格式无效");
            String path=VersionedWorkspace.path(utf8(Base64.getDecoder().decode(line.substring(0,separator))));
            if(!path.equals(path.trim()))throw new IllegalArgumentException("文件路径不能以空白开头或结尾");
            if(!names.add(path))throw new IllegalArgumentException("文件回执有重复路径");
            byte[] content=Base64.getDecoder().decode(line.substring(separator+1));bytes+=content.length;if(bytes>MAX_BYTES)throw new IllegalArgumentException("返回文本超过32000字节");
            String value=utf8(content);if(value.indexOf('\0')>=0)throw new IllegalArgumentException("本工作台仅接收UTF-8文本文件，不导入二进制文件");
            result.put(new JSONObject().put("path",path).put("content",value));
        }
        for(String path:names){int slash=path.indexOf('/');while(slash>=0){if(names.contains(path.substring(0,slash)))throw new IllegalArgumentException("文件与目录路径冲突");slash=path.indexOf('/',slash+1);}}
        return result;
    }
    private static String utf8(byte[] bytes)throws Exception{return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();}
}
