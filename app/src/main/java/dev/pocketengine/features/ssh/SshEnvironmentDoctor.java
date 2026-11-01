package dev.pocketengine.features.ssh;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed, read-only metadata probes. Raw remote output is never a diagnostic result. */
final class SshEnvironmentDoctor {
    static final int MAX_OUTPUT=16384,TIMEOUT_SECONDS=30;
    private static final String HEADER="PW_SSH_DOCTOR_V1",END="PW_SSH_DOCTOR_END";
    private static final String[] KEYS={"os","architecture","shell","path","cwd","workspace_exists","workspace_readable","workspace_searchable","workspace_writable","virtual_env","conda_env","git_path","git_version","java_path","java_version","python_path","python_version","node_path","node_version","docker_path","podman_path"};
    private SshEnvironmentDoctor(){}

    /** No user command, rc/configuration file, directory scan, daemon or network request. */
    static String script(SshProfile profile){
        return "pw_workspace="+SshProfile.quote(profile.root)+"\n"+"""
                LC_ALL=C; export LC_ALL
                printf 'PW_SSH_DOCTOR_V1\\000'
                pw_record() { printf '%s\\000%s\\000%s\\000' "$1" "$2" "$3"; }
                pw_value=$(command -p uname -s 2>/dev/null); pw_rc=$?
                if [ "$pw_rc" = 0 ]; then pw_record os available "$pw_value"; else pw_record os unavailable ''; fi
                pw_value=$(command -p uname -m 2>/dev/null); pw_rc=$?
                if [ "$pw_rc" = 0 ]; then pw_record architecture available "$pw_value"; else pw_record architecture unavailable ''; fi
                pw_record shell available "${SHELL-}"
                pw_record path available "${PATH-}"
                pw_value=$(pwd -P 2>/dev/null); pw_record cwd available "$pw_value"
                for pw_flag in exists readable searchable writable; do
                  pw_yes=false
                  case "$pw_flag" in
                    exists) [ -d "$pw_workspace" ] && pw_yes=true ;;
                    readable) [ -d "$pw_workspace" ] && [ -r "$pw_workspace" ] && pw_yes=true ;;
                    searchable) [ -d "$pw_workspace" ] && [ -x "$pw_workspace" ] && pw_yes=true ;;
                    writable) [ -d "$pw_workspace" ] && [ -w "$pw_workspace" ] && pw_yes=true ;;
                  esac
                  pw_record "workspace_$pw_flag" available "$pw_yes"
                done
                pw_yes=false; [ -n "${VIRTUAL_ENV-}" ] && pw_yes=true; pw_record virtual_env available "$pw_yes"
                pw_yes=false; [ -n "${CONDA_PREFIX-}" ] && pw_yes=true; pw_record conda_env available "$pw_yes"
                pw_capture() {
                  pw_value=$({ command -p env -i PATH="$PATH" LC_ALL=C "$@" 2>&1; printf '\\nPW_DOCTOR_EXIT:%s' "$?"; } | command -p dd bs=1 count=4096 2>/dev/null)
                  case "$pw_value" in *'
                PW_DOCTOR_EXIT:0') pw_rc=0 ;; *) pw_rc=1 ;; esac
                }
                pw_tool() {
                  pw_name=$1; shift
                  pw_binary=$(command -v "$pw_name" 2>/dev/null)
                  case "$pw_binary" in
                    /*) pw_record "${pw_name}_path" available "$pw_binary"
                        pw_capture "$pw_binary" "$@"
                        if [ "$pw_rc" = 0 ]; then pw_record "${pw_name}_version" available "$pw_value"; else pw_record "${pw_name}_version" failed ''; fi ;;
                    *) pw_record "${pw_name}_path" unavailable ''; pw_record "${pw_name}_version" unavailable '' ;;
                  esac
                }
                pw_tool git --version
                pw_tool java -version
                pw_python=$(command -v python3 2>/dev/null)
                case "$pw_python" in /*) ;; *) pw_python=$(command -v python 2>/dev/null) ;; esac
                case "$pw_python" in
                  /*) pw_record python_path available "$pw_python"
                      pw_capture "$pw_python" -I -S --version
                      if [ "$pw_rc" = 0 ]; then pw_record python_version available "$pw_value"; else pw_record python_version failed ''; fi ;;
                  *) pw_record python_path unavailable ''; pw_record python_version unavailable '' ;;
                esac
                pw_tool node --version
                for pw_name in docker podman; do
                  pw_binary=$(command -v "$pw_name" 2>/dev/null)
                  case "$pw_binary" in /*) pw_record "${pw_name}_path" available "$pw_binary" ;; *) pw_record "${pw_name}_path" unavailable '' ;; esac
                done
                printf 'PW_SSH_DOCTOR_END\\000'
                """;
    }

    static JSONObject report(JSONObject transport,SshProfile profile)throws Exception {
        Map<String,Field> fields=new LinkedHashMap<>();boolean framed=false,complete=false;
        String raw=transport.optString("stdout");
        if(raw.length()<=MAX_OUTPUT){
            String[] parts=raw.split("\u0000",-1);
            if(parts.length>1&&HEADER.equals(parts[0])){
                framed=true;int at=1;
                while(at<parts.length-1){
                    if(END.equals(parts[at])){complete=at==parts.length-2&&parts[at+1].isEmpty();break;}
                    if(at+2>=parts.length-1)break;
                    String key=parts[at],state=parts[at+1],value=parts[at+2];
                    if(!known(key)||fields.containsKey(key)||!state.matches("available|unavailable|failed")){framed=false;fields.clear();break;}
                    fields.put(key,new Field(state,value));at+=3;
                }
            }
        }
        JSONArray checks=new JSONArray(),suggestions=new JSONArray();
        JSONObject system=new JSONObject().put("os",identifier(fields.get("os"))).put("architecture",identifier(fields.get("architecture")))
                .put("configuredShell",path(fields.get("shell"))).put("interpreter","/bin/sh").put("mode","non_interactive_exec");
        JSONObject directory=new JSONObject().put("configured",profile.root).put("initialDirectory",path(fields.get("cwd")));
        for(String key:new String[]{"exists","readable","searchable","writable"})directory.put(key,flag(fields.get("workspace_"+key)));
        JSONObject tools=new JSONObject();
        for(String tool:new String[]{"git","java","python","node"}){
            Field location=fields.get(tool+"_path"),version=fields.get(tool+"_version");String resolved=path(location),number=version(tool,version);
            String status=resolved.isEmpty()?(location==null?"not_collected":location.state.equals("unavailable")?"unavailable":"invalid_output")
                    :number.isEmpty()?(version!=null&&version.state.equals("failed")?"probe_failed":"version_unrecognized"):"available";
            tools.put(tool,new JSONObject().put("status",status).put("path",resolved).put("version",number));
            checks.put(new JSONObject().put("id",tool).put("status",status));
            if(!status.equals("available"))suggestions.put(new JSONObject().put("code","review_"+tool).put("message","请主人检查远端非交互Shell中的"+tool+"路径与版本；Doctor不会安装或修改工具链"));
        }
        JSONObject paths=paths(fields.get("path"));
        if(!Boolean.TRUE.equals(directory.opt("exists")))suggestions.put(new JSONObject().put("code","review_workspace").put("message","请主人检查所选工作区是否存在且可进入；未创建目录或写入测试文件"));
        if(paths.optBoolean("currentDirectorySearch"))suggestions.put(new JSONObject().put("code","review_path_current_directory").put("message","PATH包含当前目录搜索项；请主人核对命令解析顺序"));
        boolean full=framed&&complete&&fields.size()==KEYS.length&&transport.optString("status").equals("completed")&&transport.optInt("exitCode",-1)==0;
        JSONObject doctor=new JSONObject().put("schema","ssh.environment.v1").put("collectedAt",System.currentTimeMillis()).put("complete",full)
                .put("system",system).put("path",paths).put("workspace",directory).put("tools",tools)
                .put("virtualEnvironment",new JSONObject().put("venvPresent",flag(fields.get("virtual_env"))).put("condaPresent",flag(fields.get("conda_env"))))
                .put("containerTools",new JSONObject().put("docker",path(fields.get("docker_path"))).put("podman",path(fields.get("podman_path"))).put("daemonQueried",false))
                .put("checks",checks).put("suggestions",suggestions).put("networkProbed",false).put("configurationFilesRead",false)
                .put("transportStatus",transport.optString("status","failed")).put("framingValid",framed);
        return DirectSshClient.receipt(full?"completed":transport.optBoolean("cancelled")?"cancelled":fields.isEmpty()?"failed":"partial").put("readOnly",true).put("accepted",transport.opt("accepted"))
                .put("verified",false).put("diagnosticDataPrivate",true).put("persistReport",false).put("doctor",doctor)
                .put("remoteTerminationConfirmed",false).put("message",full?"已取得当前SSH非交互环境的只读诊断；没有安装或修改环境":"诊断未完整收集；仅展示已经解析的元数据，没有重试或修改环境");
    }

    private static boolean known(String key){for(String expected:KEYS)if(expected.equals(key))return true;return false;}
    private static String identifier(Field field){return field!=null&&field.state.equals("available")&&field.value.matches("[A-Za-z0-9_.-]{1,80}")?field.value:"";}
    private static String path(Field field){return field!=null&&field.state.equals("available")&&safePath(field.value)?field.value:"";}
    private static boolean safePath(String value){
        if(value.isEmpty()||value.length()>1024||!value.startsWith("/")||value.contains("://")||value.contains("=")||value.contains("?")||value.contains("#"))return false;
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return false;return true;
    }
    private static Object flag(Field field){return field==null||!field.state.equals("available")||!field.value.matches("true|false")?JSONObject.NULL:Boolean.valueOf(field.value);}
    private static JSONObject paths(Field field)throws Exception {
        JSONArray entries=new JSONArray();int omitted=0;boolean local=false,truncated=false;
        if(field!=null&&field.state.equals("available")){
            if(field.value.length()>8192)truncated=true;
            else for(String value:field.value.split(":",-1)){
                if(value.isEmpty()||value.equals(".")){local=true;continue;}
                if(entries.length()>=32){truncated=true;continue;}
                if(safePath(value))entries.put(value);else omitted++;
            }
        }
        return new JSONObject().put("entries",entries).put("omitted",omitted).put("truncated",truncated).put("currentDirectorySearch",local).put("collected",field!=null);
    }
    private static String version(String tool,Field field){
        if(field==null||!field.state.equals("available")||field.value.length()>4096)return "";
        String number="([0-9]{1,4}(?:[._+\\-][0-9]{1,4}){0,4})";
        String expression=tool.equals("git")?"(?m)^git version "+number+"(?:[ .\\r\\n]|$)":tool.equals("java")?"(?m)^(?:openjdk|java) version \\\""+number+"\\\"":tool.equals("python")?"(?m)^Python "+number+"(?:[ \\r\\n]|$)":"(?m)^v"+number+"(?:[ \\r\\n]|$)";
        Matcher match=Pattern.compile(expression).matcher(field.value);return match.find()?match.group(1):"";
    }
    private static final class Field {final String state,value;Field(String state,String value){this.state=state;this.value=value;}}
}
