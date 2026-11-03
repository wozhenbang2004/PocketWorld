package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.mcp.McpJson;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only native configuration inspection. No CLI, process, network, vault or installation API. */
public final class McpConfigImportAdapter {
    public static final int MAX_BYTES=131072,MAX_PREVIEW_BYTES=262144,MAX_SERVERS=32,MAX_DEPTH=8,MAX_NODES=4096,MAX_ARGS=64,MAX_BINDINGS=64;
    public enum Format { CLAUDE_JSON, GEMINI_JSON, CODEX_TOML, CODEX_LIST_JSON }
    private static final Set<String> SAFE_FLAGS=Set.of("--","-y","--yes","--read-only","--readonly","--verbose","--quiet","-q");
    private static final Set<String> FIELDS=Set.of("type","command","args","env","url","httpUrl","headers","http_headers","env_http_headers","bearer_token_env_var","env_vars","enabled");
    public static final class Rejected extends IllegalArgumentException {
        public final String code;
        Rejected(String code){super("MCP配置未导入："+code);this.code=code;}
    }
    static final class Source {
        final String key,ref;
        private Source(String key,String ref){this.key=key;this.ref=ref;}
        static Source selectedSaf(String selected)throws Exception {
            try{
                URI uri=new URI(selected);if(!"content".equals(uri.getScheme())||uri.getRawAuthority()==null||uri.getRawAuthority().isEmpty()||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||selected.length()>4096)throw new Rejected("selected_document_required");
                String digest=McpSkillSource.sha256(selected);return new Source("saf:"+digest,"selected-document-sha256:"+digest);
            }catch(Rejected known){throw known;}catch(Exception invalid){throw new Rejected("selected_document_required");}
        }
    }
    private static final class Item {
        final String id,digest,json;
        Item(String id,String digest,JSONObject view){this.id=id;this.digest=digest;json=view.toString();}
    }
    /** Opaque review pinned to this adapter, source bytes, format and captured installed identities. */
    public static final class Preview {
        public final String reviewDigest;
        private final McpConfigImportAdapter issuer;
        private final String sourceKey,sourceDigest,format,json,installations;
        private final List<Item> items;
        private Preview(McpConfigImportAdapter issuer,Source source,Format format,String sourceDigest,JSONObject view,List<Item> items,JSONArray installations)throws Exception {
            this.issuer=issuer;sourceKey=source.key;this.sourceDigest=sourceDigest;this.format=format.name();this.items=List.copyOf(items);this.installations=installations.toString();
            reviewDigest=McpJson.digest(view);json=view.put("reviewDigest",reviewDigest).toString();if(json.getBytes(StandardCharsets.UTF_8).length>MAX_PREVIEW_BYTES)throw new Rejected("preview_size");
        }
        public JSONObject view()throws Exception{return new JSONObject(json);}
        String expectedInstallation(String id)throws Exception {
            JSONArray known=new JSONArray(installations);for(int i=0;i<known.length();i++)if(id.equals(known.getJSONObject(i).getString("id")))return known.getJSONObject(i).getString("installationId");return "";
        }
        long expectedRevision(String id)throws Exception {JSONArray known=new JSONArray(installations);for(int i=0;i<known.length();i++)if(id.equals(known.getJSONObject(i).getString("id")))return known.getJSONObject(i).getLong("stateRevision");return -1;}
    }
    Preview inspect(Source source,Format format,InputStream input,JSONArray installations,Preview previous,RunControl original)throws Exception {
        if(source==null||format==null||input==null||installations==null||original==null)throw new Rejected("native_source_required");
        if(installations.length()>30)throw new Rejected("native_installation_references_required");for(int i=0;i<installations.length();i++){JSONObject reference=installations.optJSONObject(i);if(reference==null)throw new Rejected("native_installation_references_required");for(String key:keys(reference))if(!Set.of("id","installationId","stateRevision","version","enabled","grantsDigest").contains(key))throw new Rejected("native_installation_references_required");}
        if(previous!=null&&(previous.issuer!=this||!previous.sourceKey.equals(source.key)||!previous.format.equals(format.name())))throw new Rejected("previous_source_mismatch");
        byte[] bytes=read(input,original);String sourceDigest=sha256(bytes),text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw new Rejected("invalid_utf8");}
        JSONObject root;
        try{if(format==Format.CODEX_TOML)root=new Toml(text).read();else{String json=format==Format.CODEX_LIST_JSON?"{\"entries\":"+text+"}":text;preflightJson(json);root=McpJson.parse(json);}validateTree(root,0,new int[]{0});}
        catch(Rejected known){throw known;}catch(Exception invalid){throw new Rejected("invalid_or_unsupported_configuration");}
        original.check();JSONArray rootIssues=new JSONArray();JSONObject servers=new JSONObject();Set<String> secrets=new HashSet<>();
        if(format==Format.CODEX_LIST_JSON){
            JSONArray entries=root.optJSONArray("entries");if(entries==null||entries.length()>MAX_SERVERS)throw new Rejected("server_count");
            for(int i=0;i<entries.length();i++){
                JSONObject entry=entries.optJSONObject(i);if(entry==null||!(entry.opt("name") instanceof String)||entry.optJSONObject("transport")==null)throw new Rejected("invalid_codex_entry");
                String name=entry.getString("name");if(servers.has(name))throw new Rejected("duplicate_server");JSONObject server=new JSONObject(entry.getJSONObject("transport").toString());if(entry.has("enabled"))server.put("enabled",entry.get("enabled"));
                for(String key:keys(entry))if(!Set.of("name","transport","enabled").contains(key)){issue(rootIssues,"entries["+i+"]",key,"unsupported_field");collectStrings(entry.get(key),secrets);}
                servers.put(name,server);
            }
        }else{
            String key=format==Format.CODEX_TOML?"mcp_servers":"mcpServers";servers=root.optJSONObject(key);if(servers==null)throw new Rejected("mcp_server_table_required");
            for(String other:keys(root))if(!other.equals(key)){issue(rootIssues,"root",other,"unsupported_field");collectStrings(root.get(other),secrets);}
        }
        if(servers.length()>MAX_SERVERS)throw new Rejected("server_count");
        for(String name:keys(servers)){JSONObject server=servers.optJSONObject(name);if(server==null)throw new Rejected("server_object_required");for(String key:keys(server))if(Set.of("env","headers","http_headers").contains(key)||!FIELDS.contains(key))collectStrings(server.get(key),secrets);
            JSONArray args=server.optJSONArray("args");if(args!=null){for(int i=0;i<args.length();i++){Object value=args.get(i);if(!(value instanceof String)||!SAFE_FLAGS.contains(value))collectStrings(value,secrets);}}else if(server.has("args"))collectStrings(server.get("args"),secrets);
            for(String key:new String[]{"command","url","httpUrl"})if(server.has(key)&&!(server.get(key) instanceof String))collectStrings(server.get(key),secrets);
            JSONArray variables=server.optJSONArray("env_vars");if(variables!=null)for(int i=0;i<variables.length();i++){JSONObject pair=variables.optJSONObject(i);if(pair!=null&&pair.has("value"))collectStrings(pair.get("value"),secrets);}}
        List<Item> items=new ArrayList<>();JSONArray rows=new JSONArray();int position=0;
        for(String name:keys(servers)){
            original.check();JSONObject definition=servers.getJSONObject(name);String id=McpSkillSource.sha256(source.key+"\0"+format.name()+"\0"+name);JSONObject row=inspectServer(position++,id,name,definition,secrets,rootIssues.length()==0);rows.put(row);items.add(new Item(id,McpJson.digest(definition),row));
        }
        JSONObject diff=difference(previous,sourceDigest,items).put("installationChanged",previous!=null&&!McpJson.digest(new JSONArray(previous.installations)).equals(McpJson.digest(installations)));
        JSONObject view=new JSONObject().put("source",new JSONObject().put("kind","selected_saf_document").put("sourceId",source.key).put("ref",source.ref).put("digest",sourceDigest).put("bytes",bytes.length).put("format",format.name()).put("retrievedAt",System.currentTimeMillis()))
                .put("items",rows).put("unsupportedRootFields",rootIssues).put("diff",diff).put("installedReferences",new JSONArray(installations.toString()))
                .put("status",rootIssues.length()>0||hasIssues(rows)?"partial":"preview").put("readOnly",true).put("configurationWritten",false).put("credentialsImported",false).put("enabledAfterInstall",false)
                .put("grantsAfterInstall",new JSONArray()).put("processStarted",false).put("networkRequested",false).put("requiresDiscovery",true).put("requiresToolSchemaApproval",true).put("instructionsAreData",true);
        if(view.toString().getBytes(StandardCharsets.UTF_8).length>MAX_PREVIEW_BYTES)throw new Rejected("preview_size");
        return new Preview(this,source,format,sourceDigest,view,items,installations);
    }
    JSONObject discoveryInput(Preview preview,int item,String acceptedDigest,String targetId,String displayName,String protocol)throws Exception {
        if(preview==null||preview.issuer!=this||!preview.reviewDigest.equals(acceptedDigest)||item<0||item>=preview.items.size())throw new Rejected("current_native_preview_required");
        if(targetId==null||!targetId.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")||displayName==null||displayName.trim().isEmpty()||displayName.length()>80)throw new Rejected("native_target_required");
        if(!McpData.supportedVersion(protocol))throw new Rejected("unsupported_protocol");JSONObject row=new JSONObject(preview.items.get(item).json);
        if(!row.getBoolean("fieldsSupported"))throw new Rejected("unsupported_fields");if(!row.getString("transport").equals("http"))throw new Rejected("transport_preview_only");
        if(row.getBoolean("requiresCredentialReentry"))throw new Rejected("credential_reentry_required");
        return new JSONObject().put("id",targetId).put("name",displayName.trim()).put("endpoint",row.getString("endpoint")).put("protocolVersion",protocol).put("expectedInstallationId",preview.expectedInstallation(targetId)).put("expectedStateRevision",preview.expectedRevision(targetId));
    }
    private static JSONObject inspectServer(int position,String id,String name,JSONObject definition,Set<String> secrets,boolean rootSupported)throws Exception {
        JSONArray problems=new JSONArray(),credentials=new JSONArray(),argv=new JSONArray();JSONObject row=new JSONObject().put("index",position).put("itemId",id).put("name",safe(name,secrets)?name:"[来源名称含敏感值]");
        if(name.isBlank()||name.length()>120||!safe(name,secrets))issue(problems,"server",null,"invalid_or_sensitive_name");
        for(String key:keys(definition))if(!FIELDS.contains(key))issue(problems,"server",key,"unsupported_field");
        String type=definition.optString("type",definition.has("command")?"stdio":"http");if(type.equals("streamable_http"))type="http";
        if(!Set.of("stdio","http","sse").contains(type)){issue(problems,"type",null,"unsupported_transport");type="unsupported";}
        if(definition.has("command")&&(definition.has("url")||definition.has("httpUrl")))issue(problems,"transport",null,"ambiguous_transport");
        if(definition.has("url")&&definition.has("httpUrl"))issue(problems,"url",null,"ambiguous_endpoint");
        if(definition.has("headers")&&definition.has("http_headers"))issue(problems,"headers",null,"ambiguous_header_binding");
        if(definition.has("env")&&definition.has("env_vars"))issue(problems,"env",null,"ambiguous_environment_binding");
        row.put("transport",type);
        if(type.equals("stdio")){
            for(String key:new String[]{"headers","http_headers","env_http_headers","bearer_token_env_var"})if(definition.has(key))issue(problems,key,null,"network_fields_on_stdio_transport");
            Object command=definition.opt("command");if(!(command instanceof String)||((String)command).isBlank()||((String)command).length()>512||((String)command).matches("(?s).*[`$;&|<>\\r\\n].*")||!safe((String)command,secrets)){
                row.put("command","[需在原生审阅中重新输入]");issue(problems,"command",null,"unsafe_or_sensitive_command");
            }else row.put("command",command);
            JSONArray args=definition.optJSONArray("args");if(definition.has("args")&&args==null)issue(problems,"args",null,"string_array_required");
            if(args!=null){if(args.length()>MAX_ARGS)throw new Rejected("argument_count");for(int i=0;i<args.length();i++){
                Object argument=args.get(i);if(!(argument instanceof String)||((String)argument).length()>4096){issue(problems,"args["+i+"]",null,"bounded_string_required");continue;}
                String value=(String)argument;argv.put(new JSONObject().put("index",i).put("display",SAFE_FLAGS.contains(value)&&safe(value,secrets)?value:"[值未导入]").put("valueImported",false).put("reentryRef","config-argument:"+id+":"+i));
            }}
            row.put("argv",argv).put("argumentValuesImported",false);
        }else if(type.equals("http")||type.equals("sse")){
            Object endpoint=definition.opt(definition.has("httpUrl")?"httpUrl":"url");
            try{if(!(endpoint instanceof String)||((String)endpoint).length()>2048||!safe((String)endpoint,secrets))throw new IllegalArgumentException();PluginRules.validateEndpoint((String)endpoint);row.put("endpoint",endpoint);}
            catch(Exception invalid){issue(problems,"url",null,"unsafe_or_sensitive_endpoint");row.put("endpointHidden",true);}
            if(definition.has("args")||definition.has("env"))issue(problems,"transport",null,"stdio_fields_on_network_transport");
        }
        bindMap(definition,"env",id,secrets,credentials,problems,false,false);bindMap(definition,"headers",id,secrets,credentials,problems,true,false);bindMap(definition,"http_headers",id,secrets,credentials,problems,true,false);bindMap(definition,"env_http_headers",id,secrets,credentials,problems,true,true);
        if(definition.has("bearer_token_env_var"))reference(definition.get("bearer_token_env_var"),"bearer_token_env_var",id,secrets,credentials,problems);
        if(definition.has("env_vars")){
            JSONArray variables=definition.optJSONArray("env_vars");if(variables==null||variables.length()>MAX_BINDINGS)issue(problems,"env_vars",null,"bounded_reference_array_required");
            else for(int i=0;i<variables.length();i++){Object variable=variables.get(i);if(variable instanceof JSONObject){JSONObject object=(JSONObject)variable;if(object.length()!=2||!object.has("name")||!(object.opt("value") instanceof String))issue(problems,"env_vars["+i+"]",null,"unsupported_field");else reference(object.get("name"),"env_vars",id,secrets,credentials,problems);}else reference(variable,"env_vars",id,secrets,credentials,problems);}
        }
        if(definition.has("enabled")&&!(definition.get("enabled") instanceof Boolean))issue(problems,"enabled",null,"boolean_required");
        if(credentials.length()>MAX_BINDINGS)throw new Rejected("binding_count");
        row.put("sourceEnabled",definition.optBoolean("enabled",true)).put("enabledAfterInstall",false).put("credentialReferences",credentials).put("requiresCredentialReentry",credentials.length()>0)
                .put("unsupported",problems).put("fieldsSupported",rootSupported&&problems.length()==0).put("requiresDiscovery",true).put("requiresToolSchemaApproval",true).put("executionAuthorized",false);
        return row.put("status",!rootSupported||problems.length()>0?"unsupported":!type.equals("http")?"transport_preview_only":credentials.length()>0?"credential_reentry_required":"ready_for_discovery");
    }
    private static void bindMap(JSONObject definition,String key,String id,Set<String> secrets,JSONArray bindings,JSONArray problems,boolean header,boolean reference)throws Exception {
        if(!definition.has(key))return;JSONObject map=definition.optJSONObject(key);if(map==null||map.length()>MAX_BINDINGS){issue(problems,key,null,"bounded_string_map_required");return;}
        int position=0;for(String name:keys(map)){
            Object value=map.get(name);String location=key+"["+position+++"]";
            if(!(value instanceof String)||!name.matches(header?"[A-Za-z0-9!#$%&'*+.^_`|~-]{1,80}":"[A-Za-z_][A-Za-z0-9_]{0,79}")||!safe(name,secrets)){issue(problems,location,null,"invalid_or_sensitive_binding");continue;}
            if(reference&&(!((String)value).matches("[A-Za-z_][A-Za-z0-9_]{0,79}")||!safe((String)value,secrets))){issue(problems,location,null,"environment_reference_required");continue;}
            JSONObject binding=new JSONObject().put("kind",key).put("name",name).put("reentryRef","config-binding:"+id+":"+key+":"+McpSkillSource.sha256(name)).put("valueImported",false).put("bound",false);if(reference)binding.put("environmentName",value);bindings.put(binding);
        }
    }
    private static void reference(Object variable,String key,String id,Set<String> secrets,JSONArray bindings,JSONArray problems)throws Exception {
        if(!(variable instanceof String)||!((String)variable).matches("[A-Za-z_][A-Za-z0-9_]{0,79}")||!safe((String)variable,secrets)){issue(problems,key,null,"environment_reference_required");return;}
        bindings.put(new JSONObject().put("kind",key).put("name",variable).put("reentryRef","config-binding:"+id+":"+key+":"+McpSkillSource.sha256((String)variable)).put("valueImported",false).put("bound",false));
    }
    private static JSONObject difference(Preview previous,String digest,List<Item> items)throws Exception {
        JSONArray added=new JSONArray(),changed=new JSONArray(),removed=new JSONArray();Map<String,String> old=new LinkedHashMap<>();if(previous!=null)for(Item item:previous.items)old.put(item.id,item.digest);
        for(Item item:items){String prior=old.remove(item.id);if(prior==null)added.put(item.id);else if(!prior.equals(item.digest))changed.put(item.id);}for(String id:old.keySet())removed.put(id);
        return new JSONObject().put("kind",previous==null?"new_source":previous.sourceDigest.equals(digest)?"same_source_version":"source_changed").put("previousDigest",previous==null?JSONObject.NULL:previous.sourceDigest).put("added",added).put("changed",changed).put("removed",removed).put("installationChanged",false);
    }
    private static boolean hasIssues(JSONArray items)throws Exception{for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getJSONArray("unsupported").length()>0)return true;return false;}
    private static void issue(JSONArray target,String location,String unknown,String code)throws Exception {if(target.length()>=128)throw new Rejected("diagnostic_count");JSONObject item=new JSONObject().put("location",location).put("code",code);if(unknown!=null)item.put("fieldRef","sha256:"+McpSkillSource.sha256(unknown));target.put(item);}
    private static boolean safe(String value,Set<String> secrets){for(String secret:secrets)if(!secret.isEmpty()&&value.contains(secret))return false;return true;}
    private static void collectStrings(Object value,Set<String> result)throws Exception {
        if(value instanceof String){String text=(String)value;result.add(text);if(text.matches("(?i)^(bearer|basic)\\s+.+$"))result.add(text.substring(text.indexOf(' ')+1).trim());int equal=text.indexOf('=');if(equal>0&&equal<text.length()-1)result.add(text.substring(equal+1));}
        else if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(String key:keys(object))collectStrings(object.get(key),result);}
        else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)collectStrings(array.get(i),result);}
    }
    private static List<String> keys(JSONObject object){List<String> result=new ArrayList<>();object.keys().forEachRemaining(result::add);result.sort(String::compareTo);return result;}
    private static byte[] read(InputStream input,RunControl original)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] part=new byte[4096];int zeros=0;
        while(true){original.check();int count;try{count=input.read(part,0,Math.min(part.length,MAX_BYTES-bytes.size()+1));}catch(Exception unavailable){original.check();throw new Rejected("source_read_unavailable");}if(count<0)break;if(count==0){if(++zeros>8)throw new Rejected("source_read_unavailable");continue;}zeros=0;if(count>MAX_BYTES-bytes.size())throw new Rejected("source_size");bytes.write(part,0,count);}
        original.check();if(bytes.size()==0)throw new Rejected("empty_source");return bytes.toByteArray();
    }
    private static String sha256(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hex.toString();}
    /** Enforce container/token budgets before the strict JSON parser constructs any JSON objects. */
    private static void preflightJson(String text){
        boolean quoted=false,escaped=false;int depth=0,tokens=0,bare=0;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escaped){escaped=false;continue;}if(c=='\\'){escaped=true;continue;}if(c=='\"')quoted=false;continue;}
            if(c=='\"'){quoted=true;bare=0;if(++tokens>MAX_NODES)throw new Rejected("configuration_complexity");}
            else if(c=='{'||c=='['){bare=0;if(++depth>MAX_DEPTH||++tokens>MAX_NODES)throw new Rejected("configuration_complexity");}
            else if(c=='}'||c==']'){bare=0;if(--depth<0)throw new Rejected("invalid_or_unsupported_configuration");}
            else if(Character.isWhitespace(c)||c==':'||c==',')bare=0;
            else{if(bare++==0&&++tokens>MAX_NODES||bare>128)throw new Rejected("configuration_complexity");}
        }
        if(quoted||depth!=0)throw new Rejected("invalid_or_unsupported_configuration");
    }
    private static void validateTree(Object value,int depth,int[] nodes)throws Exception {
        if(depth>MAX_DEPTH||++nodes[0]>MAX_NODES)throw new Rejected("configuration_complexity");
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(String key:keys(object)){validateText(key);validateTree(object.get(key),depth+1,nodes);}}
        else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)validateTree(array.get(i),depth+1,nodes);}
        else if(value instanceof String)validateText((String)value);else if(value instanceof Number)McpJson.digest(value);
    }
    private static void validateText(String value)throws Exception{if(value.length()>8192||value.indexOf('\0')>=0)throw new Rejected("configuration_string_size");try{McpSkillSource.sha256(value);}catch(java.nio.charset.CharacterCodingException invalid){throw new Rejected("invalid_unicode");}}

    /** Closed TOML value/table subset; unsupported spellings fail instead of using a shell or guessing. */
    private static final class Toml {
        private final String text;private final Set<String> declared=new HashSet<>();
        Toml(String text){this.text=text;}
        JSONObject read()throws Exception {
            JSONObject root=new JSONObject(),table=root;int tableDepth=0;String[] lines=text.replace("\r\n","\n").split("\n",-1);if(lines.length>4096)throw new Rejected("toml_line_count");
            for(String original:lines){String line=comment(original).trim();if(line.isEmpty())continue;
                if(line.startsWith("[")){if(!line.endsWith("]")||line.startsWith("[["))throw new Rejected("unsupported_toml_table");List<String> path=path(line.substring(1,line.length()-1));String key=String.join("\0",path);if(!declared.add(key))throw new Rejected("duplicate_toml_table");table=root;tableDepth=path.size();for(String part:path){if(!table.has(part))table.put(part,new JSONObject());if(table.optJSONObject(part)==null)throw new Rejected("toml_table_conflict");table=table.getJSONObject(part);}continue;}
                int equal=separator(line,'=');if(equal<1)throw new Rejected("unsupported_toml_assignment");List<String> path=path(line.substring(0,equal));if(tableDepth+path.size()>MAX_DEPTH)throw new Rejected("configuration_complexity");JSONObject target=table;for(int i=0;i<path.size()-1;i++){String part=path.get(i);if(!target.has(part))target.put(part,new JSONObject());if(target.optJSONObject(part)==null)throw new Rejected("toml_table_conflict");target=target.getJSONObject(part);}String key=path.get(path.size()-1);if(target.has(key))throw new Rejected("duplicate_toml_key");target.put(key,value(line.substring(equal+1).trim(),tableDepth+path.size()));
            }
            return root;
        }
        private static List<String> path(String raw)throws Exception {List<String> parts=split(raw,'.');if(parts.size()>MAX_DEPTH)throw new Rejected("configuration_complexity");List<String> result=new ArrayList<>();for(String part:parts){String key=part.trim();if(key.startsWith("\"")||key.startsWith("'")){Object value=value(key,0);if(!(value instanceof String))throw new Rejected("unsupported_toml_key");key=(String)value;}else if(!key.matches("[A-Za-z0-9_-]{1,120}"))throw new Rejected("unsupported_toml_key");if(key.isEmpty())throw new Rejected("unsupported_toml_key");result.add(key);}return result;}
        private static Object value(String raw,int depth)throws Exception {
            if(depth>MAX_DEPTH)throw new Rejected("configuration_complexity");if(raw.isEmpty())throw new Rejected("unsupported_toml_value");
            if(raw.startsWith("\"")){if(raw.startsWith("\"\"\""))throw new Rejected("unsupported_toml_multiline");return McpJson.parse("{\"value\":"+raw+"}").get("value");}
            if(raw.startsWith("'")){if(raw.length()<2||!raw.endsWith("'")||raw.substring(1,raw.length()-1).contains("'"))throw new Rejected("unsupported_toml_literal");return raw.substring(1,raw.length()-1);}
            if(raw.startsWith("[")){if(!raw.endsWith("]"))throw new Rejected("unsupported_toml_multiline");JSONArray array=new JSONArray();String inner=raw.substring(1,raw.length()-1).trim();if(inner.isEmpty())return array;List<String> parts=split(inner,',');for(int i=0;i<parts.size();i++){String part=parts.get(i).trim();if(part.isEmpty()&&i==parts.size()-1)continue;array.put(value(part,depth+1));}return array;}
            if(raw.startsWith("{")){if(!raw.endsWith("}"))throw new Rejected("unsupported_toml_inline_table");JSONObject object=new JSONObject();String inner=raw.substring(1,raw.length()-1).trim();if(inner.isEmpty())return object;for(String entry:split(inner,',')){int equal=separator(entry,'=');if(equal<1)throw new Rejected("unsupported_toml_inline_table");List<String> keys=path(entry.substring(0,equal));if(keys.size()!=1||object.has(keys.get(0)))throw new Rejected("duplicate_or_unsupported_toml_key");object.put(keys.get(0),value(entry.substring(equal+1).trim(),depth+1));}return object;}
            if(raw.equals("true")||raw.equals("false"))return Boolean.valueOf(raw);if(raw.matches("[+-]?[0-9]{1,18}(?:\\.[0-9]{1,12})?"))return new BigDecimal(raw);throw new Rejected("unsupported_toml_value");
        }
        private static String comment(String raw)throws Exception {int position=separator(raw,'#');return position<0?raw:raw.substring(0,position);}
        private static int separator(String raw,char separator)throws Exception {List<Integer> positions=positions(raw,separator);return positions.isEmpty()?-1:positions.get(0);}
        private static List<String> split(String raw,char separator)throws Exception {List<Integer> positions=positions(raw,separator);List<String> result=new ArrayList<>();int start=0;for(int position:positions){result.add(raw.substring(start,position));start=position+1;}result.add(raw.substring(start));return result;}
        private static List<Integer> positions(String raw,char separator)throws Exception {
            List<Integer> result=new ArrayList<>();char quote=0;boolean escaped=false;int depth=0;
            for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(quote!=0){if(quote=='\"'&&escaped){escaped=false;continue;}if(quote=='\"'&&c=='\\'){escaped=true;continue;}if(c==quote)quote=0;continue;}
                if(c=='\"'||c=='\''){quote=c;continue;}if(c==separator&&depth==0){result.add(i);if(separator=='#')return result;}
                if(c=='['||c=='{'){if(++depth>MAX_DEPTH)throw new Rejected("configuration_complexity");}else if(c==']'||c=='}'){if(--depth<0)throw new Rejected("unsupported_toml_value");}
            }
            if(quote!=0||depth!=0)throw new Rejected("unsupported_toml_multiline");return result;
        }
    }
}
