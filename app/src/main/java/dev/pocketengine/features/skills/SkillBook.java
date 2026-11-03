package dev.pocketengine.features.skills;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private, versioned skill packages. Instructions never confer tool grants. */
public final class SkillBook {
    private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
    private final File root,index;
    private final Object lock;
    private final ExperienceOrigin.ExperienceResolver experienceResolver;
    private dev.pocketengine.features.plugins.McpSkillSource.Validator mcpSourceValidator;
    public SkillBook(File root)throws Exception{this(root,null);}
    public SkillBook(File root,ExperienceOrigin.ExperienceResolver resolver)throws Exception{this.root=root;index=new File(root,"index.json");lock=LOCKS.computeIfAbsent(root.getCanonicalPath(),key->new Object());experienceResolver=resolver;}
    public boolean experienceResolverAvailable(){return experienceResolver!=null;}
    /** Original app composition only; absent validation keeps imported remote sources unreadable. */
    public void mountMcpSourceValidation(dev.pocketengine.features.plugins.McpSkillSource.Validator validator){
        synchronized(lock){if(mcpSourceValidator!=null)throw new IllegalStateException("MCP技能来源校验已挂载");mcpSourceValidator=java.util.Objects.requireNonNull(validator);}
    }
    public JSONObject install(JSONObject scope,JSONObject input,long now)throws Exception {
        if(input.has("origin")||input.has("provenance")||input.has("experienceRef")||input.has("gitSource")||input.has("mcpSource")||input.has("restoredFrom")||input.has("sourcePolicy"))throw new SecurityException("经验来源和来源策略只能由可信宿主提供");
        return install(scope,input,now,null);
    }
    private JSONObject install(JSONObject scope,JSONObject input,long now,ExperienceOrigin origin)throws Exception {
        return installRetained(scope,input,now,origin,null,null);
    }
    private JSONObject installRetained(JSONObject scope,JSONObject input,long now,ExperienceOrigin origin,JSONObject nativeGit,JSONObject restoredFrom)throws Exception {
        return installRetained(scope,input,now,origin,nativeGit,restoredFrom,null);
    }
    private JSONObject installRetained(JSONObject scope,JSONObject input,long now,ExperienceOrigin origin,JSONObject nativeGit,JSONObject restoredFrom,JSONObject nativeMcp)throws Exception {
        return installRetained(scope,input,now,origin,nativeGit,restoredFrom,nativeMcp,null);
    }
    private JSONObject installRetained(JSONObject scope,JSONObject input,long now,ExperienceOrigin origin,JSONObject nativeGit,JSONObject restoredFrom,JSONObject nativeMcp,JSONObject inheritedPolicy)throws Exception {
        synchronized(lock){String[] owner=scope(scope);JSONObject state=state();JSONArray records=state.getJSONArray("skills");String id=input.optString("id");JSONObject old=id.isEmpty()?null:required(records,owner,id);
            if(old!=null&&((origin==null&&old.has("origin"))||(origin!=null&&!old.has("origin"))))throw new SecurityException("普通技能和来源型技能不能互相覆盖，请登记为新技能");
            if(origin!=null){requireOrigin(owner,origin);if(old!=null&&!origin.ref.sameIdentity(ExperienceOrigin.fromJson(old.getJSONObject("origin")).ref))throw new SecurityException("更新不能改变经验身份");}
            if(old==null&&records.length()>=100)throw new IllegalStateException("最多登记100个技能");
            if(old!=null&&input.optInt("revision",-1)!=old.getInt("revision"))throw new IllegalStateException("技能已改变，请刷新后再导入更新");
            String manifest=input.getString("manifest");JSONObject metadata=SkillManifest.parse(manifest);if(old!=null&&!metadata.getString("name").equals(old.getString("name")))throw new IllegalArgumentException("修改技能名称请登记为新技能");
            String source=text(input.optString("source"),1,1000,"来源"),version=text(input.optString("sourceVersion",metadata.getJSONObject("metadata").optString("version","未注明")),1,120,"来源版本");
            JSONArray permissions=permissions(input.optJSONArray("requiredCapabilities"));long expires=expiry(input.opt("expiresAt"));
            JSONObject files=draftFiles(input);long bytes=packageBytes(files);
            String stable=old==null?UUID.randomUUID().toString():id;int revision=old==null?1:Math.addExact(old.getInt("revision"),1);String digest=digest(files);
            File destination=new File(root,stable+"/"+revision+"/"+metadata.getString("name"));if(destination.exists())throw new IllegalStateException("技能版本目录已存在，请检查未完成的导入");
            // Only an actually current, verified old package can acquire retained metadata. Older
            // unrecorded directories stay unknown; no source version or install time is backfilled.
            if(old!=null&&!old.has("origin"))try{verifiedFiles(old);retainRevision(old,now);}catch(IOException|IllegalArgumentException|IllegalStateException|SecurityException unavailable){/* Original bytes remain; never promote damaged content into a restore proof. */}
            JSONObject intent=null;
            if(origin!=null){if(cleanups(state).length()>=100)throw new IllegalStateException("请先恢复未完成的经验技能清理");intent=cleanupReceipt(stable,revision,owner);cleanups(state).put(intent);save(state);}
            try{if(!destination.mkdirs())throw new IllegalStateException("无法创建技能目录");java.util.Iterator<String> names=files.keys();while(names.hasNext()){String name=names.next();File target=child(destination,name);if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IllegalStateException("无法创建技能资源目录");writeBytes(target,files.getString(name).getBytes(StandardCharsets.UTF_8));}
                JSONObject record=new JSONObject(metadata.toString()).put("id",stable).put("revision",revision).put("ownerId",owner[0]).put("spaceId",owner[1])
                        .put("source",source).put("sourceVersion",version).put("digest",digest).put("requiredCapabilities",permissions).put("expiresAt",expires==0?JSONObject.NULL:expires)
                        .put("enabled",false).put("approvedDigest","").put("createdAt",old==null?now:old.getLong("createdAt")).put("updatedAt",now).put("sizeBytes",bytes).put("resources",resourceNames(files));
                record.put("sourcePolicy",inheritedPolicy!=null?new JSONObject(inheritedPolicy.toString()):old==null?SkillSourcePolicy.of(SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER):SkillSourcePolicy.current(old));
                if(origin!=null){requireOrigin(owner,origin);record.put("origin",origin.toJson());removeCleanup(state,stable,revision);}
                else if(nativeGit!=null){JSONObject pinned=new JSONObject(nativeGit.toString());if(pinned.optBoolean("newBase")){pinned.remove("newBase");pinned.put("baseRevision",revision);}record.put("gitSource",pinned);}
                else if(nativeMcp!=null){requireMcpSource(nativeMcp);record.put("mcpSource",new JSONObject(nativeMcp.toString()));}
                else if(restoredFrom==null&&old!=null&&old.has("gitSource"))record.put("gitSource",new JSONObject(old.getJSONObject("gitSource").toString()));
                else if(restoredFrom==null&&old!=null&&old.has("mcpSource"))record.put("mcpSource",new JSONObject(old.getJSONObject("mcpSource").toString()));
                if(restoredFrom!=null)record.put("restoredFrom",new JSONObject(restoredFrom.toString()));
                if(!record.has("origin"))retainRevision(record,now);
                if(old!=null)remove(records,stable);records.put(record);save(state);return present(record,now);
            }catch(Exception failure){
                try{erase(destination);File retained=new File(destination.getParentFile(),"retained.json");if(retained.exists())Files.delete(retained.toPath());if(intent!=null){JSONObject recovery=state();removeCleanup(recovery,stable,revision);save(recovery);}}catch(Exception cleanupFailure){failure.addSuppressed(cleanupFailure);}
                throw failure;
            }
        }
    }
    public JSONArray list(JSONObject scope,long now)throws Exception {synchronized(lock){String[] owner=scope(scope);JSONArray rows=state().getJSONArray("skills"),result=new JSONArray();Map<String,String> preferred=preferredSources(rows,scope,now);for(int i=0;i<rows.length();i++){JSONObject record=rows.getJSONObject(i);if(matches(record,owner))result.put(ownerSourceStatus(inspectCurrent(record,null,now),record,preferred,scope));}return result;}}
    public JSONObject get(JSONObject scope,String id,long now)throws Exception {synchronized(lock){JSONArray rows=state().getJSONArray("skills");String[] owner=scope(scope);JSONObject record=required(rows,owner,id);return ownerSourceStatus(inspectCurrent(record,null,now),record,preferredSources(rows,scope,now),scope);}}

    /** Compatibility catalogue: metadata only, with fresh package validation and isolated bad packages. */
    public JSONObject catalogueForHost(JSONObject authenticatedScope,long now)throws Exception {
        synchronized(lock){String[] owner=scope(authenticatedScope);JSONArray rows=state().getJSONArray("skills"),selected=new JSONArray();int invalid=0;
            Map<String,String> preferred=preferredSources(rows,authenticatedScope,now);
            for(int i=0;i<rows.length();i++){JSONObject record=rows.getJSONObject(i);if(!matches(record,owner)||!SkillSourcePolicy.applicable(record,authenticatedScope))continue;
                JSONObject current=inspectCurrent(record,null,now);if(current.getString("status").equals("invalid"))invalid++;
                if(current.getBoolean("active")&&preferred(record,preferred)&&SkillSourcePolicy.modelAllowed(record))selected.put(current.put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(authenticatedScope)));
            }
            return new JSONObject().put("skills",selected).put("metadataOnly",true).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(authenticatedScope)).put("grantsAdded",new JSONArray())
                    .put("status",invalid==0?"ok":"partial").put("invalidCount",invalid).put("untrustedReference",true).put("executable",false);
        }
    }

    /** Stable, bounded metadata pages for skills unrelated to the current goal's lexical terms.
     * A page cursor binds the complete eligible version set and host capability scope. */
    public JSONObject cataloguePageForHost(JSONObject authenticatedScope,Set<String> hostCapabilities,JSONObject cursor,int pageSize,long now)throws Exception {
        return cataloguePageForHost(authenticatedScope,hostCapabilities,cursor,pageSize,null,now);
    }
    /** Native exact-version selection only narrows metadata; it is not an authorization or instruction grant. */
    public JSONObject cataloguePageForHost(JSONObject authenticatedScope,Set<String> hostCapabilities,JSONObject cursor,int pageSize,Set<String> selectedVersions,long now)throws Exception {
        String[] owner=scope(authenticatedScope);Set<String> capabilities=hostCapabilities(hostCapabilities);
        Set<String> selection=selectedVersions==null?null:Set.copyOf(selectedVersions);
        if(pageSize<1||pageSize>20)throw new IllegalArgumentException("技能目录每页为1至20项");
        synchronized(lock){JSONArray rows=state().getJSONArray("skills"),identity=new JSONArray(),page=new JSONArray();List<JSONObject> eligible=new ArrayList<>();int invalid=0;
            Map<String,String> preferred=preferredSources(rows,authenticatedScope,now);
            for(int i=0;i<rows.length();i++){JSONObject record=rows.getJSONObject(i);if(!matches(record,owner)||!SkillSourcePolicy.applicable(record,authenticatedScope))continue;
                if(selection!=null&&!selection.contains(new JSONArray().put(record.getString("id")).put(record.getInt("revision")).put(record.getString("digest")).toString()))continue;
                JSONObject current=inspectCurrent(record,capabilities,now);if(current.getString("status").equals("invalid"))invalid++;
                if(!current.getString("status").equals("active")||!preferred(record,preferred)||!SkillSourcePolicy.modelAllowed(record))continue;
                eligible.add(discoveryMetadata(record,new JSONObject().put("score",0).put("matchReasons",new JSONArray())).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(authenticatedScope)));
            }
            eligible.sort(Comparator.comparing((JSONObject row)->row.optString("name")).thenComparing(row->row.optString("id")));
            for(JSONObject row:eligible)identity.put(new JSONArray().put(row.getString("id")).put(row.getInt("revision")).put(row.getString("digest"))
                    .put(row.getLong("executionGeneration")).put(row.getString("source")).put(row.getString("sourceVersion")).put(row.getJSONObject("sourcePolicy").toString()));
            List<String> sortedCapabilities=new ArrayList<>(capabilities);sortedCapabilities.sort(Comparator.naturalOrder());
            String fingerprint=digest(new JSONObject().put("scope",SkillSourcePolicy.scopeFingerprint(authenticatedScope)).put("capabilities",new JSONArray(sortedCapabilities).toString()).put("versions",identity.toString()));
            int offset=0;
            if(cursor!=null){if(cursor.length()!=2||!fingerprint.equals(cursor.optString("catalogueDigest"))||!(cursor.opt("offset") instanceof Integer||cursor.opt("offset") instanceof Long))throw new SecurityException("技能目录或宿主能力已变化，请从第一页重新发现");long raw=cursor.getLong("offset");if(raw<0||raw>eligible.size())throw new SecurityException("技能目录游标越界");offset=(int)raw;}
            int end=Math.min(offset+pageSize,eligible.size());for(int i=offset;i<end;i++)page.put(eligible.get(i));
            return new JSONObject().put("skills",page).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(authenticatedScope)).put("totalCount",eligible.size()).put("pageSize",pageSize).put("offset",offset)
                    .put("catalogueDigest",fingerprint).put("nextCursor",end<eligible.size()?new JSONObject().put("catalogueDigest",fingerprint).put("offset",end):JSONObject.NULL)
                    .put("hasMore",end<eligible.size()).put("metadataOnly",true).put("status",invalid==0?"ok":"partial").put("invalidCount",invalid)
                    .put("untrustedReference",true).put("executable",false).put("grantsAdded",new JSONArray());
        }
    }

    /** Native supervisor only: scope/capabilities come from authenticated host state, never tool arguments. */
    public JSONObject discoverForHost(JSONObject authenticatedScope,String query,Set<String> hostCapabilities,int limit,int maxChars,long now)throws Exception {
        String[] owner=scope(authenticatedScope);Set<String> capabilities=hostCapabilities(hostCapabilities),terms=queryTerms(query);
        if(limit<1||limit>20)throw new IllegalArgumentException("技能发现数量必须为1至20");
        if(maxChars<512||maxChars>32000)throw new IllegalArgumentException("技能发现预算必须为512至32000字符");
        synchronized(lock){JSONArray rows=state().getJSONArray("skills"),selected=new JSONArray();List<JSONObject> candidates=new ArrayList<>();
            Map<String,String> preferred=preferredSources(rows,authenticatedScope,now);
            JSONObject omitted=new JSONObject().put("inactive",0).put("invalid",0).put("missingCapabilities",0).put("noMatch",0).put("duplicates",0).put("shadowed",0).put("ownerOnly",0).put("limit",0).put("budget",0);
            for(int i=0;i<rows.length();i++){JSONObject record=rows.getJSONObject(i);if(!matches(record,owner)||!SkillSourcePolicy.applicable(record,authenticatedScope))continue;
                JSONObject current=inspectCurrent(record,capabilities,now);String status=current.getString("status");
                if(!status.equals("active")){increment(omitted,status);continue;}
                if(!preferred(record,preferred)){increment(omitted,samePreferredDigest(record,rows,preferred)?"duplicates":"shadowed");continue;}
                if(!SkillSourcePolicy.modelAllowed(record)){increment(omitted,"ownerOnly");continue;}
                JSONObject match=match(record,terms,query);if(match.getInt("score")==0){increment(omitted,"noMatch");continue;}
                candidates.add(discoveryMetadata(record,match).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(authenticatedScope)));
            }
            candidates.sort(Comparator.<JSONObject>comparingInt(row->row.optInt("score")).reversed()
                    .thenComparing(row->row.optString("name")).thenComparing(row->row.optString("id")));
            JSONObject result=new JSONObject().put("skills",selected).put("metadataOnly",true).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(authenticatedScope)).put("matching","lexical-v1")
                    .put("status",omitted.getInt("invalid")==0?"ok":"partial").put("omitted",omitted).put("truncated",false)
                    .put("untrustedReference",true).put("executable",false).put("requiresHostAuthorization",true).put("grantsAdded",new JSONArray());
            Set<String> digests=new HashSet<>();
            for(JSONObject candidate:candidates){if(!digests.add(candidate.getString("digest"))){increment(omitted,"duplicates");continue;}
                if(selected.length()>=limit){increment(omitted,"limit");continue;}
                selected.put(candidate);
                // Reserve room for final counters (at most 100 records) and boolean length changes.
                if(result.toString().length()+32>maxChars){selected.remove(selected.length()-1);increment(omitted,"budget");}
            }
            result.put("truncated",omitted.getInt("budget")>0||omitted.getInt("limit")>0);return result;
        }
    }

    /** Owner management diagnostics, deliberately separate from the model-safe discovery result. */
    public JSONObject diagnoseDiscovery(JSONObject authenticatedScope,String query,Set<String> hostCapabilities,long now)throws Exception {
        String[] owner=scope(authenticatedScope);Set<String> capabilities=hostCapabilities(hostCapabilities),terms=queryTerms(query);
        synchronized(lock){JSONArray rows=state().getJSONArray("skills");List<JSONObject> diagnostics=new ArrayList<>();int invalid=0;
            Map<String,String> preferred=preferredSources(rows,authenticatedScope,now);
            for(int i=0;i<rows.length();i++){JSONObject record=rows.getJSONObject(i);if(!matches(record,owner))continue;
                JSONObject current=inspectCurrent(record,capabilities,now);
                if(current.getString("status").equals("invalid"))invalid++;
                else{JSONObject match=match(record,terms,query);current.put("qualityState",record.optString("qualityState","unreviewed")).put("score",match.getInt("score")).put("matchReasons",match.getJSONArray("matchReasons"));
                    if(current.getString("status").equals("active")){
                        if(!preferred(record,preferred))current.put("status","shadowed").put("shadowedBy",preferred.get(record.getString("name")));
                        else if(!SkillSourcePolicy.modelAllowed(record))current.put("status","ownerOnly");
                        else if(match.getInt("score")==0)current.put("status","noMatch");
                    }}
                boolean applicable=SkillSourcePolicy.applicable(record,authenticatedScope);
                if(!applicable)current.put("status",SkillSourcePolicy.bindingRequired(record)?"projectBindingRequired":"differentProject");
                current.put("applicableToScope",applicable).put("eligibleForDiscovery",applicable&&current.getString("status").equals("active"))
                        .put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(authenticatedScope));diagnostics.add(current);
            }
            diagnostics.sort(Comparator.comparing((JSONObject row)->row.optString("name")).thenComparing(row->row.optString("id")));
            return new JSONObject().put("skills",new JSONArray(diagnostics)).put("managementOnly",true).put("metadataOnly",true)
                    .put("status",invalid==0?"ok":"partial").put("invalidCount",invalid).put("untrustedReference",true).put("executable",false);
        }
    }

    private JSONObject inspectCurrent(JSONObject record,Set<String> capabilities,long now)throws Exception {
        JSONObject current=present(record,now);JSONArray reasons=new JSONArray(),missing=new JSONArray();boolean verified=true;
        if(!record.optBoolean("enabled"))reasons.put("disabled");
        if(SkillSourcePolicy.bindingRequired(record))reasons.put("projectBindingRequired");
        if(expired(record,now))reasons.put("expired");
        if(!record.getString("digest").equals(record.optString("approvedDigest")))reasons.put("approvalMismatch");
        // Only expected content-validation failures are per-package diagnostics. Index/algorithm/programming errors propagate.
        try{verify(record);if(record.has("origin"))current.put("originStatus","current");}
        catch(OriginUnavailable unavailable){verified=false;reasons.put(unavailable.reason);current.put("originStatus",unavailable.reason);}
        catch(IOException|IllegalArgumentException|IllegalStateException|SecurityException invalid){
            verified=false;reasons.put("contentInvalid");current.put("contentError","技能资源缺失、无法读取或校验失败，请检查后重新导入");
        }
        if(capabilities!=null){JSONArray required=record.getJSONArray("requiredCapabilities");List<String> absent=new ArrayList<>();
            for(int i=0;i<required.length();i++)if(!capabilities.contains(required.getString(i)))absent.add(required.getString(i));
            absent.sort(Comparator.naturalOrder());missing=new JSONArray(absent);if(!absent.isEmpty())reasons.put("missingCapabilities");}
        boolean active=verified&&active(record,now);String status=!verified?"invalid":!active?"inactive":missing.length()>0?"missingCapabilities":"active";
        return current.put("enabled",record.optBoolean("enabled")).put("approvedDigest",record.optString("approvedDigest")).put("executionGeneration",record.optLong("executionGeneration",0))
                .put("active",active).put("status",status).put("statusReasons",reasons).put("contentVerified",verified)
                .put("capabilitiesChecked",capabilities!=null).put("missingCapabilities",missing).put("executable",false);
    }

    private static JSONObject discoveryMetadata(JSONObject record,JSONObject match)throws Exception {
        String description=record.getString("description");int end=Math.min(384,description.length());
        if(end<description.length()&&Character.isHighSurrogate(description.charAt(end-1)))end--;
        JSONObject result=new JSONObject().put("id",record.getString("id")).put("revision",record.getInt("revision")).put("digest",record.getString("digest"))
                .put("name",record.getString("name")).put("description",description.substring(0,end)).put("descriptionTruncated",end<description.length())
                .put("entryPath","SKILL.md").put("resourceCount",record.getJSONArray("resources").length()).put("missingCapabilities",new JSONArray())
                .put("source",record.getString("source")).put("sourceVersion",record.getString("sourceVersion")).put("sourceKind",SkillSourcePolicy.kind(record))
                .put("sourcePolicy",SkillSourcePolicy.metadata(record)).put("executionGeneration",record.optLong("executionGeneration",0))
                .put("score",match.getInt("score")).put("matchReasons",match.getJSONArray("matchReasons"));
        if(record.has("origin")){ExperienceOrigin origin=ExperienceOrigin.fromJson(record.getJSONObject("origin"));result.put("originKind","accepted_procedure").put("generated",origin.author==ExperienceOrigin.DraftAuthor.MODEL).put("validationStatus","unverified").put("executionVerified",false);}
        return result;
    }

    /** Choose before query/capability filtering. A broken, unavailable or owner-only winner
     * cannot silently fall through to another source with the same name. Explicit withdrawal
     * can expose the next enabled source, with a new catalogue identity. */
    private static Map<String,String> preferredSources(JSONArray rows,JSONObject nativeScope,long now)throws Exception {
        String[] owner=scope(nativeScope);SkillSourcePolicy.checkedScope(nativeScope);Map<String,JSONObject> winners=new HashMap<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);if(!matches(row,owner)||!active(row,now)||!SkillSourcePolicy.applicable(row,nativeScope))continue;
            String name=row.getString("name");JSONObject previous=winners.get(name);
            if(previous==null||sourceRank(row)>sourceRank(previous)||sourceRank(row)==sourceRank(previous)&&row.getString("id").compareTo(previous.getString("id"))<0)winners.put(name,row);
        }
        Map<String,String> ids=new HashMap<>();for(Map.Entry<String,JSONObject> entry:winners.entrySet())ids.put(entry.getKey(),entry.getValue().getString("id"));return ids;
    }
    private static int sourceRank(JSONObject row){try{return SkillSourcePolicy.rank(row);}catch(Exception invalid){return Integer.MAX_VALUE;}}
    private static boolean preferred(JSONObject row,Map<String,String> winners)throws Exception{return row.getString("id").equals(winners.get(row.getString("name")));}
    private static boolean samePreferredDigest(JSONObject row,JSONArray rows,Map<String,String> winners)throws Exception {
        String selected=winners.get(row.getString("name"));for(int i=0;selected!=null&&i<rows.length();i++){JSONObject candidate=rows.getJSONObject(i);if(selected.equals(candidate.optString("id")))return row.getString("digest").equals(candidate.getString("digest"));}return false;
    }
    private static JSONObject ownerSourceStatus(JSONObject current,JSONObject record,Map<String,String> winners,JSONObject nativeScope)throws Exception {
        boolean applicable=SkillSourcePolicy.applicable(record,nativeScope),selected=applicable&&preferred(record,winners),model=SkillSourcePolicy.modelAllowed(record);
        if(!applicable)current.put("status",SkillSourcePolicy.bindingRequired(record)?"projectBindingRequired":"differentProject");
        if("active".equals(current.getString("status"))){
            if(!selected)current.put("status","shadowed").put("shadowedBy",winners.get(record.getString("name")));
            else if(!model)current.put("status","ownerOnly");
        }
        return current.put("applicableToScope",applicable).put("sourceSelected",selected).put("eligibleForModel",current.getBoolean("active")&&selected&&model);
    }

    private static JSONObject match(JSONObject record,Set<String> queryTerms,String query)throws Exception {
        JSONArray reasons=new JSONArray();int score=0;String normalized=normalize(query).trim();
        if(normalize(record.getString("name")).equals(normalized)){score=32;reasons.put(new JSONObject().put("field","name").put("kind","exactName").put("points",32));}
        StringBuilder metadata=new StringBuilder();JSONObject values=record.getJSONObject("metadata");List<String> keys=new ArrayList<>();values.keys().forEachRemaining(keys::add);keys.sort(Comparator.naturalOrder());
        for(String key:keys)metadata.append(key).append(' ').append(values.getString(key)).append('\n');
        String[] fields={"name","description","metadata"},text={record.getString("name"),record.getString("description"),metadata.toString()};int[] weights={8,4,1};
        for(int i=0;i<fields.length;i++){Set<String> words=terms(text[i]);String normalizedText=normalize(text[i]);JSONArray matched=new JSONArray();int count=0;
            for(String term:queryTerms)if(words.contains(term)||(term.codePointCount(0,term.length())==1&&Character.UnicodeScript.of(term.codePointAt(0))==Character.UnicodeScript.HAN&&normalizedText.contains(term))){count++;if(matched.length()<8)matched.put(term);}
            if(count>0){int points=count*weights[i];score+=points;reasons.put(new JSONObject().put("field",fields[i]).put("kind","terms")
                    .put("terms",matched).put("matchedTermCount",count).put("points",points));}
        }
        return new JSONObject().put("score",score).put("matchReasons",reasons);
    }

    private static Set<String> queryTerms(String query){
        if(query==null||query.trim().isEmpty()||query.length()>512)throw new IllegalArgumentException("技能查询必须为1至512字符");
        for(int i=0;i<query.length();i++){char c=query.charAt(i);
            if(Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')throw new IllegalArgumentException("技能查询含无效控制字符");
            if(Character.isHighSurrogate(c)){if(i+1>=query.length()||!Character.isLowSurrogate(query.charAt(++i)))throw new IllegalArgumentException("技能查询包含无效Unicode");}
            else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("技能查询包含无效Unicode");}
        Set<String> result=terms(query);if(result.isEmpty())throw new IllegalArgumentException("技能查询需要文字或数字");return result;
    }
    private static Set<String> hostCapabilities(Set<String> input){
        if(input==null||input.size()>256)throw new IllegalArgumentException("宿主必须提供至多256项实际能力");Set<String> result=new HashSet<>();
        for(String capability:input){if(capability==null||!capability.matches("[a-z][a-z0-9_.:-]{0,79}"))throw new IllegalArgumentException("宿主能力格式无效，不支持通配符");result.add(capability);}return result;
    }
    private static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    /** Literal lexical retrieval only: word tokens plus adjacent Han bigrams, no inferred synonyms/embeddings. */
    private static Set<String> terms(String value){
        Set<String> result=new LinkedHashSet<>();int[] points=normalize(value).codePoints().toArray();StringBuilder word=new StringBuilder();
        for(int i=0;i<points.length;i++){int point=points[i];boolean han=Character.UnicodeScript.of(point)==Character.UnicodeScript.HAN;
            if(han){if(word.length()>0){result.add(word.toString());word.setLength(0);}
                boolean previous=i>0&&Character.UnicodeScript.of(points[i-1])==Character.UnicodeScript.HAN;
                boolean next=i+1<points.length&&Character.UnicodeScript.of(points[i+1])==Character.UnicodeScript.HAN;
                if(next)result.add(new String(Character.toChars(point))+new String(Character.toChars(points[i+1])));else if(!previous)result.add(new String(Character.toChars(point)));
            }else if(Character.isLetterOrDigit(point))word.appendCodePoint(point);
            else if(word.length()>0){result.add(word.toString());word.setLength(0);}
        }
        if(word.length()>0)result.add(word.toString());return result;
    }
    private static void increment(JSONObject counts,String key)throws Exception{counts.put(key,counts.getInt(key)+1);}
    /** Native only: draft may contain instructions/resources, never identity, approval or policy. */
    public JSONObject inspectExperience(JSONObject authenticatedScope,ExperienceOrigin origin,JSONObject draft,JSONObject options,long now)throws Exception {
        String[] owner=scope(authenticatedScope);JSONObject files=experienceDraftFiles(draft);checkExperienceOptions(options,false);
        synchronized(lock){return experienceInspection(owner,origin,files,options,now);}
    }
    public JSONObject importExperience(JSONObject authenticatedScope,ExperienceOrigin origin,JSONObject draft,JSONObject options,long now)throws Exception {
        String[] owner=scope(authenticatedScope);JSONObject files=experienceDraftFiles(draft);checkExperienceOptions(options,true);
        synchronized(lock){JSONObject inspection=experienceInspection(owner,origin,files,options,now);
            if(!inspection.getString("inspectedDigest").equals(options.optString("inspectedDigest")))throw new SecurityException("经验、技能草稿或策略已改变，请重新审阅");
            JSONObject comparison=inspection.getJSONObject("comparison");
            if(comparison.has("id")&&(options.optInt("revision",-1)!=comparison.getInt("revision")||!comparison.getString("expectedCurrentDigest").equals(options.optString("expectedCurrentDigest"))))throw new IllegalStateException("当前技能版本已改变，请重新审阅");
            JSONObject input=new JSONObject(options.toString());JSONObject policy=inspection.getJSONObject("policy");
            input.put("requiredCapabilities",policy.getJSONArray("requiredCapabilities")).put("expiresAt",policy.get("expiresAt"))
                    .put("manifest",files.getString("SKILL.md")).put("source","accepted-procedure:"+origin.ref.recordId)
                    .put("sourceVersion",Long.toString(origin.ref.revision));
            files.remove("SKILL.md");input.put("resources",files);
            return install(authenticatedScope,input,now,origin);
        }
    }
    private JSONObject experienceInspection(String[] owner,ExperienceOrigin origin,JSONObject files,JSONObject options,long now)throws Exception {
        requireOrigin(owner,origin);String id=options.optString("id");JSONObject old=null;
        if(!id.isEmpty()){
            old=required(state().getJSONArray("skills"),owner,id);
            if(!old.has("origin")||!origin.ref.sameIdentity(ExperienceOrigin.fromJson(old.getJSONObject("origin")).ref))throw new SecurityException("只能更新同一经验身份的来源型技能");
        }
        JSONObject policy=new JSONObject().put("requiredCapabilities",permissions(options.has("requiredCapabilities")?options.getJSONArray("requiredCapabilities"):old==null?null:old.getJSONArray("requiredCapabilities")));
        long expires=expiry(options.has("expiresAt")?options.opt("expiresAt"):old==null?null:old.opt("expiresAt"));
        policy.put("expiresAt",expires==0?JSONObject.NULL:expires);
        JSONObject result=inspectFiles(owner,files,id,now),comparison=result.getJSONObject("comparison");
        if(old!=null){try{requireRecordOrigin(old);comparison.put("currentOriginStatus","current");}catch(OriginUnavailable unavailable){comparison.put("currentOriginStatus",unavailable.reason);}}
        // Hash individual canonical fields, not JSONObject map iteration order. Pins policy and update target too.
        JSONObject binding=new JSONObject().put("package",result.getJSONObject("package").getString("digest"))
                .put("recordId",origin.ref.recordId).put("sourceRevision",Long.toString(origin.ref.revision))
                .put("ownerId",owner[0]).put("spaceId",owner[1]).put("draftAuthor",origin.author.name())
                .put("targetId",id).put("targetRevision",Integer.toString(comparison.optInt("revision",0)))
                .put("targetDigest",comparison.optString("expectedCurrentDigest"))
                .put("requiredCapabilities",policy.getJSONArray("requiredCapabilities").toString()).put("expiresAt",Long.toString(expires));
        requireOrigin(owner,origin);
        return result.put("origin",origin.toJson()).put("originStatus","current").put("policy",policy).put("inspectedDigest",digest(binding))
                .put("reviewRequired",true).put("validationLimit","来源已接受不等于程序执行成功；草稿和效果均未经本模块验证");
    }
    private static List<String> jsonKeys(JSONObject object) {
        List<String> keys=new ArrayList<>();
        for(Iterator<String> iterator=object.keys();iterator.hasNext();)keys.add(iterator.next());
        return keys;
    }
    private static void checkExperienceOptions(JSONObject options,boolean importing)throws org.json.JSONException {
        if(options==null)throw new IllegalArgumentException("必须提供原生审阅选项");
        Set<String> allowed=importing?Set.of("id","revision","expectedCurrentDigest","inspectedDigest","requiredCapabilities","expiresAt"):Set.of("id","requiredCapabilities","expiresAt");
        for(String key:jsonKeys(options))if(!allowed.contains(key))throw new IllegalArgumentException("经验导入不接受选项："+key);
        if(options.has("id")&&(!(options.opt("id") instanceof String)||options.getString("id").isEmpty()))throw new IllegalArgumentException("更新标识无效");
        if(options.has("revision")&&!(options.opt("revision") instanceof Integer))throw new IllegalArgumentException("技能版本必须为整数");
    }
    private static JSONObject experienceDraftFiles(JSONObject draft)throws Exception {
        if(draft==null)throw new IllegalArgumentException("必须提供技能草稿");
        for(String key:jsonKeys(draft))if(!key.equals("manifest")&&!key.equals("resources"))throw new SecurityException("草稿只能包含manifest和resources，来源与策略由宿主提供");
        if(draft.has("resources")&&draft.optJSONObject("resources")==null)throw new IllegalArgumentException("资源必须是文本对象");
        SkillManifest.parse(draft.getString("manifest"));JSONObject files=draftFiles(draft);
        for(String path:jsonKeys(files)){
            String content=files.getString(path);
            for(int i=0;i<content.length();i++){char c=content.charAt(i);
                if(Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')throw new IllegalArgumentException("技能草稿只接受文本");
                if(Character.isHighSurrogate(c)){if(i+1>=content.length()||!Character.isLowSurrogate(content.charAt(++i)))throw new IllegalArgumentException("技能草稿Unicode无效");}
                else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("技能草稿Unicode无效");
            }
            for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))if(files.has(path.substring(0,slash)))throw new IllegalArgumentException("资源文件与目录冲突");
        }
        return files;
    }
    private static JSONObject draftFiles(JSONObject input)throws Exception {
        String manifest=input.getString("manifest");JSONObject resources=input.optJSONObject("resources");
        if(resources==null)resources=new JSONObject();if(resources.length()>40)throw new IllegalArgumentException("一次最多导入40份文本资源");
        JSONObject files=new JSONObject().put("SKILL.md",manifest);long bytes=manifest.getBytes(StandardCharsets.UTF_8).length;
        for(String raw:jsonKeys(resources)){String name=path(raw);if(name.equals("SKILL.md"))throw new IllegalArgumentException("SKILL.md应通过manifest字段导入");Object body=resources.get(name);
            if(!(body instanceof String)||((String)body).length()>64000)throw new IllegalArgumentException("资源必须是64000字以内的文本");
            bytes+=((String)body).getBytes(StandardCharsets.UTF_8).length;if(bytes>1_000_000)throw new IllegalArgumentException("单个技能文本包不得超过1MB");files.put(name,body);
        }
        return files;
    }
    private static long packageBytes(JSONObject files)throws org.json.JSONException {long bytes=0;for(String name:jsonKeys(files))bytes+=files.getString(name).getBytes(StandardCharsets.UTF_8).length;return bytes;}
    private static final class OriginUnavailable extends SecurityException {
        final String reason;
        OriginUnavailable(String reason){super("经验来源不可用："+reason);this.reason=reason;}
    }
    private ExperienceOrigin.State resolveOrigin(String[] owner,ExperienceOrigin origin)throws Exception {
        if(origin==null)throw new IllegalArgumentException("必须提供宿主经验来源");
        if(!owner[0].equals(origin.ref.ownerId)||!owner[1].equals(origin.ref.spaceId))throw new OriginUnavailable("sourceScopeMismatch");
        if(experienceResolver==null)throw new OriginUnavailable("sourceResolverUnavailable");
        ExperienceOrigin.State current;
        try{current=experienceResolver.resolve(owner[0],owner[1],origin.ref.recordId);}catch(Exception unavailable){throw new OriginUnavailable("sourceResolverUnavailable");}
        if(current==null)throw new OriginUnavailable("sourceUnavailable");
        if(!origin.ref.sameIdentity(current.current))throw new OriginUnavailable("sourceIdentityMismatch");
        return current;
    }
    private void requireOrigin(String[] owner,ExperienceOrigin origin)throws Exception {
        ExperienceOrigin.State current=resolveOrigin(owner,origin);
        if(current.status==ExperienceOrigin.Status.DELETED)throw new OriginUnavailable("sourceDeleted");
        if(current.status==ExperienceOrigin.Status.FORGOTTEN)throw new OriginUnavailable("sourceForgotten");
        if(!origin.ref.sameVersion(current.current))throw new OriginUnavailable("sourceChanged");
        if(current.status!=ExperienceOrigin.Status.ACTIVE)throw new OriginUnavailable("sourceInactive");
        if(!current.acceptedProcedure)throw new OriginUnavailable("sourceNotAcceptedProcedure");
    }
    private void requireRecordOrigin(JSONObject record)throws Exception {
        if(record.has("origin"))requireOrigin(new String[]{record.getString("ownerId"),record.getString("spaceId")},ExperienceOrigin.fromJson(record.getJSONObject("origin")));
        if(record.has("mcpSource"))try{requireMcpSource(record.getJSONObject("mcpSource"));}
        catch(Exception unavailable){try{revokeMcpApproval(record);}catch(Exception revokeFailure){unavailable.addSuppressed(revokeFailure);}throw unavailable;}
    }
    /** A known rejected source cannot regain the old approval when identical bytes reappear. */
    private void revokeMcpApproval(JSONObject rejected)throws Exception {
        if(!rejected.optBoolean("enabled")&&rejected.optString("approvedDigest").isEmpty())return;
        synchronized(lock){JSONObject state=state();JSONArray records=state.getJSONArray("skills");
            for(int i=0;i<records.length();i++){
                JSONObject current=records.getJSONObject(i);if(!current.optString("id").equals(rejected.optString("id"))||!matches(current,new String[]{rejected.getString("ownerId"),rejected.getString("spaceId")}))continue;
                if(current.getInt("revision")!=rejected.getInt("revision")||!current.getString("digest").equals(rejected.getString("digest"))||current.optJSONObject("mcpSource")==null||!current.getJSONObject("mcpSource").toString().equals(rejected.getJSONObject("mcpSource").toString()))return;
                if(!current.optBoolean("enabled")&&current.optString("approvedDigest").isEmpty())return;
                long generation=Math.addExact(current.optLong("executionGeneration",0),1);current.put("enabled",false).put("approvedDigest","").put("executionGeneration",generation).put("updatedAt",System.currentTimeMillis());save(state);
                rejected.put("enabled",false).put("approvedDigest","").put("executionGeneration",generation).put("updatedAt",current.getLong("updatedAt"));return;
            }
        }
    }
    private void requireMcpSource(JSONObject source)throws Exception {
        if(mcpSourceValidator==null)throw new SecurityException("原MCP来源宿主未挂载，不能沿用远端技能批准");
        mcpSourceValidator.check(new JSONObject(source.toString()));
    }
    /** Revoke stale source approval without deleting locally edited or historical bytes. */
    public JSONObject reconcileMcpSources(JSONObject scope,long now)throws Exception {
        synchronized(lock){String[] owner=scope(scope);JSONObject state=state();JSONArray records=state.getJSONArray("skills");int revoked=0,unavailable=0;
            for(int i=0;i<records.length();i++){
                JSONObject record=records.getJSONObject(i);if(!matches(record,owner)||!record.has("mcpSource"))continue;
                try{requireMcpSource(record.getJSONObject("mcpSource"));}catch(Exception stale){
                    unavailable++;if(record.optBoolean("enabled")||!record.optString("approvedDigest").isEmpty()){
                        record.put("enabled",false).put("approvedDigest","").put("executionGeneration",Math.addExact(record.optLong("executionGeneration",0),1)).put("updatedAt",now);revoked++;
                    }
                }
            }
            if(revoked>0)save(state);return new JSONObject().put("revoked",revoked).put("unavailableSources",unavailable).put("deleted",0).put("executionAuthorized",false);
        }
    }

    /**
     * Native committed-delete/forget callback, not a guess based on resolver null. The host retains
     * this exact pre-delete Ref in its existing operation receipt until cleanup is acknowledged.
     */
    public JSONObject removeExperienceSource(JSONObject authenticatedScope,ExperienceOrigin.Ref removed)throws Exception {
        String[] owner=scope(authenticatedScope);
        if(removed==null||!owner[0].equals(removed.ownerId)||!owner[1].equals(removed.spaceId))throw new SecurityException("删除来源与当前空间不一致");
        synchronized(lock){JSONObject state=state();JSONArray records=state.getJSONArray("skills");int count=0,newer=0;
            for(int i=records.length()-1;i>=0;i--){JSONObject record=records.getJSONObject(i);if(!matches(record,owner)||!record.has("origin"))continue;
                ExperienceOrigin.Ref ref=ExperienceOrigin.fromJson(record.getJSONObject("origin")).ref;if(!ref.sameIdentity(removed))continue;
                if(ref.revision>removed.revision){newer++;continue;}
                cleanups(state).put(cleanupReceipt(record.getString("id"),0,owner));records.remove(i);count++;
            }
            if(count>0)save(state);int pending=retryCleanups(state,owner);
            return new JSONObject().put("removed",count).put("newerSources",newer).put("pendingCleanup",pending)
                    .put("status",pending==0&&newer==0?"complete":"blocked").put("managementOnly",true);
        }
    }
    /** Native change/forget and startup-recovery hook. Host must serialize source mutation/context eviction. */
    public JSONObject reconcileExperiences(JSONObject authenticatedScope,long now)throws Exception {
        synchronized(lock){String[] owner=scope(authenticatedScope);JSONObject state=state();JSONArray records=state.getJSONArray("skills");int revoked=0,removed=0,unavailable=0;boolean changed=false;
            for(int i=records.length()-1;i>=0;i--){JSONObject record=records.getJSONObject(i);if(!matches(record,owner)||!record.has("origin"))continue;
                ExperienceOrigin origin=ExperienceOrigin.fromJson(record.getJSONObject("origin"));ExperienceOrigin.State current;
                try{current=resolveOrigin(owner,origin);}catch(OriginUnavailable absent){unavailable++;continue;}
                if(current.current.revision<origin.ref.revision){unavailable++;continue;}
                if(current.status==ExperienceOrigin.Status.DELETED||current.status==ExperienceOrigin.Status.FORGOTTEN){
                    cleanups(state).put(cleanupReceipt(record.getString("id"),0,owner));records.remove(i);removed++;changed=true;
                }else if(!origin.ref.sameVersion(current.current)||current.status!=ExperienceOrigin.Status.ACTIVE||!current.acceptedProcedure){
                    if(record.optBoolean("enabled")||!record.optString("approvedDigest").isEmpty()){
                        record.put("enabled",false).put("approvedDigest","").put("updatedAt",now);revoked++;changed=true;
                    }
                }
            }
            // First remove all readable metadata and approval atomically; only then touch the package directories.
            if(changed)save(state);int pending=retryCleanups(state,owner);
            return new JSONObject().put("revoked",revoked).put("removed",removed).put("pendingCleanup",pending).put("unavailableSources",unavailable)
                    .put("status",pending==0&&unavailable==0?"complete":"blocked").put("managementOnly",true);
        }
    }
    private static JSONArray cleanups(JSONObject state)throws org.json.JSONException {
        if(!state.has("experienceCleanup"))state.put("experienceCleanup",new JSONArray());return state.getJSONArray("experienceCleanup");
    }
    private static JSONObject cleanupReceipt(String id,int revision,String[] owner)throws org.json.JSONException {return new JSONObject().put("id",id).put("revision",revision).put("ownerId",owner[0]).put("spaceId",owner[1]);}
    private static void removeCleanup(JSONObject state,String id,int revision)throws org.json.JSONException {JSONArray receipts=cleanups(state);for(int i=receipts.length()-1;i>=0;i--){JSONObject receipt=receipts.getJSONObject(i);if(id.equals(receipt.getString("id"))&&revision==receipt.getInt("revision"))receipts.remove(i);}}
    private int retryCleanups(JSONObject state,String[] owner)throws Exception {
        JSONArray receipts=cleanups(state);int pending=0;boolean changed=false;
        for(int i=receipts.length()-1;i>=0;i--){JSONObject receipt=receipts.getJSONObject(i);if(!matches(receipt,owner))continue;
            String id=receipt.getString("id");int revision=receipt.getInt("revision");
            if(!id.matches("[a-f0-9-]{36}")||revision<0)throw new IllegalStateException("经验清理收据损坏");
            for(int j=0;j<state.getJSONArray("skills").length();j++){JSONObject live=state.getJSONArray("skills").getJSONObject(j);
                if(id.equals(live.getString("id"))&&(revision==0||revision==live.getInt("revision")))throw new IllegalStateException("清理收据与当前技能版本冲突，未删除任何当前内容");
            }
            try{erase(child(root,id+(revision==0?"":"/"+revision)));}catch(IOException|SecurityException failure){pending++;continue;}
            receipts.remove(i);changed=true;
        }
        if(changed)save(state);return pending;
    }
    /** Native file-picker flow only. The caller owns the stream; inspection never installs content. */
    public JSONObject inspectPackage(JSONObject scope,InputStream archive,String id,long now)throws Exception {
        String[] owner=scope(scope);JSONObject files=SkillArchive.read(archive);
        synchronized(lock){return inspectFiles(owner,files,id,now);}
    }
    private JSONObject inspectFiles(String[] owner,JSONObject files,String id,long now)throws Exception {
        JSONObject record=id==null||id.isEmpty()?null:required(state().getJSONArray("skills"),owner,id);
            JSONObject metadata=SkillManifest.parse(files.getString("SKILL.md"));String digest=digest(files);
            if(record!=null&&!record.getString("name").equals(metadata.getString("name")))throw new IllegalArgumentException("修改技能名称请登记为新技能");
            long size=0;java.util.Iterator<String> names=files.keys();while(names.hasNext())size+=files.getString(names.next()).getBytes(StandardCharsets.UTF_8).length;
            JSONObject summary=new JSONObject(metadata.toString()).put("digest",digest).put("resources",resourceNames(files)).put("sizeBytes",size)
                    .put("sourceVersion",metadata.getJSONObject("metadata").optString("version","未注明"));
            JSONObject comparison=new JSONObject().put("kind","new").put("added",resourceNames(files)).put("removed",new JSONArray()).put("modified",new JSONArray());
            if(record!=null){JSONObject oldFiles=null;try{oldFiles=verifiedFiles(record);}catch(Exception unavailable){/* A new complete snapshot may repair a damaged package. */}
                JSONArray added=new JSONArray(),removed=new JSONArray(),modified=new JSONArray(),oldPaths=record.getJSONArray("resources"),newPaths=resourceNames(files);
                for(int i=0;i<newPaths.length();i++){String path=newPaths.getString(i);if(!contains(oldPaths,path))added.put(path);else if(oldFiles==null||!oldFiles.getString(path).equals(files.getString(path)))modified.put(path);}
                for(int i=0;i<oldPaths.length();i++)if(!files.has(oldPaths.getString(i)))removed.put(oldPaths.getString(i));
                comparison.put("kind",oldFiles==null?"repair":record.getString("digest").equals(digest)?"same-content":"changed-content")
                        .put("id",record.getString("id")).put("revision",record.getInt("revision")).put("expectedCurrentDigest",record.getString("digest"))
                        .put("currentSourceVersion",record.getString("sourceVersion")).put("currentContentVerified",oldFiles!=null)
                        .put("added",added).put("removed",removed).put("modified",modified);
            }
            return new JSONObject().put("package",summary).put("comparison",comparison).put("requiresNewApproval",true).put("untrustedReference",true).put("scriptsExecutable",false);
    }
    /** Re-importing an exported older package creates a new disabled revision, never restores approval. */
    public JSONObject importPackage(JSONObject scope,InputStream archive,JSONObject options,long now)throws Exception {
        String[] owner=scope(scope);JSONObject input=new JSONObject(options.toString()),files=SkillArchive.read(archive);
        if(!digest(files).equals(input.optString("inspectedDigest")))throw new SecurityException("技能包已改变，请重新检查后导入");
        synchronized(lock){String id=input.optString("id");if(!id.isEmpty()){
                JSONObject current=required(state().getJSONArray("skills"),owner,id);
                if(input.optInt("revision",-1)!=current.getInt("revision")||!current.getString("digest").equals(input.optString("expectedCurrentDigest")))throw new IllegalStateException("当前技能版本已改变，请重新检查");
                // ZIP carries content, not host policy. Omission must not relax the existing restrictions.
                if(!input.has("requiredCapabilities"))input.put("requiredCapabilities",new JSONArray(current.getJSONArray("requiredCapabilities").toString()));
                if(!input.has("expiresAt"))input.put("expiresAt",current.get("expiresAt"));
            }
            String manifest=files.getString("SKILL.md");files.remove("SKILL.md");input.put("manifest",manifest).put("resources",files);
            return install(scope,input,now);
        }
    }
    /** Export exact verified content, without owner IDs, enabled state, approval or host-policy fields. */
    public JSONObject exportPackage(JSONObject scope,String id,int revision,String expectedDigest,OutputStream output,long now)throws Exception {
        if(output==null)throw new IllegalArgumentException("请选择技能ZIP保存位置");byte[] archive;JSONObject result;
        synchronized(lock){JSONObject record=required(state().getJSONArray("skills"),scope(scope),id);
            if(record.getInt("revision")!=revision||!record.getString("digest").equals(expectedDigest))throw new IllegalStateException("当前技能版本已改变，请刷新后导出");
            if(record.has("origin"))throw new SecurityException("来源型技能暂不支持去除来源身份的ZIP导出");
            archive=SkillArchive.write(record.getString("name"),verifiedFiles(record));
            result=new JSONObject().put("fileName",record.getString("name")+".zip").put("mediaType","application/zip").put("id",id).put("revision",revision)
                    .put("digest",expectedDigest).put("archiveBytes",archive.length).put("containsApproval",false).put("scriptsExecutable",false);
        }
        output.write(archive);return result;
    }
    public JSONObject setEnabled(JSONObject scope,String id,boolean enabled,String digest,boolean confirmed,long now)throws Exception {
        return setEnabled(scope,id,enabled,digest,confirmed,now,false);
    }
    /** Owner's exact reviewed package only. Classification is not provenance and grants no
     * execution permission. Changing it withdraws the old approval before any new discovery. */
    public JSONObject configureSourceForOwner(JSONObject scope,String id,int revision,String digest,long expectedExecutionGeneration,
            SkillSourcePolicy.Layer layer,SkillSourcePolicy.Invocation invocation,long now)throws Exception {
        return configureSourceForOwner(scope,id,revision,digest,expectedExecutionGeneration,layer,invocation,null,now);
    }
    /** Native module has rechecked this project against the original project catalogue. */
    JSONObject configureSourceForOwner(JSONObject scope,String id,int revision,String digest,long expectedExecutionGeneration,
            SkillSourcePolicy.Layer layer,SkillSourcePolicy.Invocation invocation,String projectId,long now)throws Exception {
        JSONObject next=SkillSourcePolicy.of(layer,invocation,projectId);
        synchronized(lock){JSONObject state=state(),record=required(state.getJSONArray("skills"),scope(scope),id);
            if(record.getInt("revision")!=revision||!record.getString("digest").equals(digest)||record.optLong("executionGeneration",0)!=expectedExecutionGeneration)throw new SecurityException("技能来源、版本或批准已变化，请重新打开当前详情");
            verify(record);JSONObject current=SkillSourcePolicy.current(record);
            if(SkillExecution.digestJson(current).equals(SkillExecution.digestJson(next)))return present(record,now);
            record.put("sourcePolicy",next).put("enabled",false).put("approvedDigest","").put("updatedAt",now)
                    .put("executionGeneration",Math.addExact(expectedExecutionGeneration,1));save(state);return present(record,now);
        }
    }
    /** Native explicit owner approval; a model JSON confirmed:true is never accepted for derived skills. */
    public JSONObject approveExperience(JSONObject authenticatedScope,String id,int revision,String digest,long now)throws Exception {
        synchronized(lock){JSONObject record=required(state().getJSONArray("skills"),scope(authenticatedScope),id);
            if(!record.has("origin")||record.getInt("revision")!=revision)throw new SecurityException("来源型技能版本已改变，请重新审阅");
            return setEnabled(authenticatedScope,id,true,digest,true,now,true);
        }
    }
    private JSONObject setEnabled(JSONObject scope,String id,boolean enabled,String digest,boolean confirmed,long now,boolean nativeExperienceApproval)throws Exception {
        synchronized(lock){JSONObject state=state(),record=required(state.getJSONArray("skills"),scope(scope),id);
            if(enabled&&record.has("origin")&&!nativeExperienceApproval)throw new SecurityException("来源型技能必须通过原生审阅入口批准");
            if(enabled){if(SkillSourcePolicy.bindingRequired(record))throw new SecurityException("原项目技能尚未绑定真实项目，请先重新审阅来源");if(!confirmed||!record.getString("digest").equals(digest))throw new SecurityException("请先核对当前技能内容与来源，再确认启用");if(expired(record,now))throw new IllegalStateException("技能已过期，请重新导入更新");verify(record);}
            if(record.optBoolean("enabled")==enabled&&(enabled?record.getString("digest").equals(record.optString("approvedDigest")):record.optString("approvedDigest").isEmpty()))return present(record,now);
            if(record.has("origin")&&enabled){record.put("qualityState","trial").put("qualityWindow",new JSONArray());}
            record.put("enabled",enabled).put("approvedDigest",enabled?record.getString("digest"):"").put("updatedAt",now)
                    .put("executionGeneration",Math.addExact(record.optLong("executionGeneration",0),1));save(state);return present(record,now);
        }
    }
    /** Opaque current reference selection; created only after native verified content read. */
    public static final class ReferenceSelection {
        private final SkillBook issuer;private final String scope,binding;private final Set<String> capabilities;
        private ReferenceSelection(SkillBook issuer,JSONObject scope,JSONObject binding,Set<String> capabilities){this.issuer=issuer;this.scope=scope.toString();this.binding=binding.toString();this.capabilities=Set.copyOf(capabilities);}
        public JSONObject metadata()throws Exception{return new JSONObject(binding);}
        public void requireNoBookMonitorHeld(){if(Thread.holdsLock(issuer.lock))throw new SecurityException("不得从技能锁反向进入原任务");}
        public void checkCurrent()throws Exception{JSONObject expected=metadata(),nativeScope=new JSONObject(scope);synchronized(issuer.lock){JSONObject row=required(issuer.state().getJSONArray("skills"),scope(nativeScope),expected.getString("id"));issuer.assertHostRead(row,nativeScope,expected.getString("digest"),capabilities,System.currentTimeMillis());if(row.getInt("revision")!=expected.getInt("revision")||row.optLong("executionGeneration",0)!=expected.getLong("executionGeneration"))throw new SecurityException("参考技能版本或批准已改变");}}
    }
    public ReferenceSelection selectReferenceForHost(JSONObject scope,String id,int revision,String digest,Set<String> capabilities,long now)throws Exception {
        synchronized(lock){JSONObject row=required(state().getJSONArray("skills"),scope(scope),id);assertHostRead(row,scope,digest,capabilities,now);if(row.getInt("revision")!=revision)throw new SecurityException("参考技能版本已改变");
            JSONObject binding=new JSONObject().put("id",id).put("revision",revision).put("digest",digest).put("executionGeneration",row.optLong("executionGeneration",0))
                    .put("source",row.getString("source")).put("sourceVersion",row.getString("sourceVersion")).put("sourcePolicy",SkillSourcePolicy.metadata(row))
                    .put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(scope)).put("skillScope",SkillSourcePolicy.checkedScope(scope));if(row.has("origin"))binding.put("origin",new JSONObject(row.getJSONObject("origin").toString()));
            return new ReferenceSelection(this,scope,binding,capabilities);
        }
    }
    /** Native feedback only; exact package+approval generation fence and durable replay barrier. */
    public JSONObject recordQuality(JSONObject scope,SkillQualityReceipt evidence,long now)throws Exception {
        JSONObject fact=evidence.snapshot();String[] owner=scope(scope);
        synchronized(lock){JSONObject state=state(),record=required(state.getJSONArray("skills"),owner,fact.getString("skillId"));
            if(!owner[0].equals(fact.getString("ownerId"))||!owner[1].equals(fact.getString("spaceId"))||record.getInt("revision")!=fact.getInt("revision")||!record.getString("digest").equals(fact.getString("digest")))throw new SecurityException("使用结果属于其它技能或旧版本");
            verify(record);
            JSONArray history=record.optJSONArray("qualityHistory");if(history==null)history=new JSONArray();
            for(int i=0;i<history.length();i++){JSONObject old=history.getJSONObject(i);if(old.getString("operationId").equals(fact.getString("operationId"))&&old.getString("taskId").equals(fact.getString("taskId"))){
                if(!old.getString("outcome").equals(fact.getString("outcome"))||!old.getString("note").equals(fact.getString("note")))throw new SecurityException("原使用结果已审阅，不能重复改写归责");return present(record,now);}}
            for(int i=0;i<history.length();i++){JSONObject prior=history.getJSONObject(i);
                if(prior.getString("taskId").equals(fact.getString("taskId"))&&prior.getString("runId").equals(fact.getString("runId"))&&prior.getLong("executionGeneration")==fact.getLong("executionGeneration")&&java.util.Set.of("verified","skill_failed","verifier_rejected").contains(prior.getString("outcome"))&&java.util.Set.of("verified","skill_failed","verifier_rejected").contains(fact.getString("outcome"))){
                    if(!prior.getString("outcome").equals(fact.getString("outcome")))throw new SecurityException("同一轮已有不同技能结果，请核对原归责");return present(record,now);
                }
            }
            if(record.optLong("executionGeneration",0)!=fact.getLong("executionGeneration")||!active(record,now))throw new SecurityException("技能已停用或重新批准，请勿将旧使用结果归给新试用");
            if(history.length()>=200)throw new IllegalStateException("此版本已保留200次审阅，请导入新版本后继续试用");
            history.put(fact);record.put("qualityHistory",history);
            String outcome=fact.getString("outcome"),quality=record.optString("qualityState","trial");
            if(java.util.Set.of("verified","skill_failed","verifier_rejected").contains(outcome)){
                JSONArray window=record.optJSONArray("qualityWindow");if(window==null)window=new JSONArray();window.put(outcome);while(window.length()>3)window.remove(0);
                int bad=0;for(int i=0;i<window.length();i++)if(!window.getString(i).equals("verified"))bad++;
                if(quality.equals("trial"))quality=outcome.equals("verified")?"active":"suspended";
                else if(quality.equals("active")&&(outcome.equals("verifier_rejected")||bad>=2))quality="suspended";
                record.put("qualityWindow",window).put("qualityState",quality);
                if(quality.equals("suspended"))record.put("enabled",false).put("approvedDigest","").put("executionGeneration",Math.addExact(record.optLong("executionGeneration",0),1));
            }
            record.put("updatedAt",now);save(state);return present(record,now);
        }
    }
    public JSONObject read(JSONObject scope,String id,String path,int offset,int length,long now)throws Exception {
        synchronized(lock){JSONObject record=required(state().getJSONArray("skills"),scope(scope),id);return readVerified(record,verifiedFiles(record),path,offset,length,now);}
    }
    private JSONObject readVerified(JSONObject record,JSONObject files,String path,int offset,int length,long now)throws Exception {
            String checked=path(path);if(!files.has(checked))throw new IllegalArgumentException("技能没有这份资源");String content=files.getString(checked);
            if(offset<0||offset>content.length())throw new IllegalArgumentException("资源读取位置无效");int end=Math.min(content.length(),offset+Math.max(1,Math.min(12000,length)));
            if(end<content.length()&&end>offset&&Character.isHighSurrogate(content.charAt(end-1)))end--;
            JSONObject result=new JSONObject().put("id",record.getString("id")).put("revision",record.getInt("revision")).put("path",checked).put("digest",record.getString("digest")).put("content",content.substring(offset,end)).put("nextOffset",end)
                    .put("hasMore",end<content.length()).put("active",active(record,now)).put("untrustedReference",true).put("executable",false).put("requiresHostAuthorization",true)
                    .put("source",record.getString("source")).put("sourceVersion",record.getString("sourceVersion")).put("sourcePolicy",SkillSourcePolicy.metadata(record)).put("executionGeneration",record.optLong("executionGeneration",0));
            if(record.has("origin"))result.put("origin",ExperienceOrigin.fromJson(record.getJSONObject("origin")).toJson());return result;
    }
    /** This method is for the native supervisor; it is deliberately absent from feature commands. */
    public JSONObject loadForHost(JSONObject scope,String id,String expectedDigest,Set<String> hostCapabilities,long now)throws Exception {
        synchronized(lock){JSONObject record=required(state().getJSONArray("skills"),scope(scope),id);JSONObject files=assertHostRead(record,scope,expectedDigest,hostCapabilities,now);
            return new JSONObject().put("skill",present(record,now).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(scope))).put("instructions",files.getString("SKILL.md"))
                    .put("untrustedReference",true).put("allowedToolsAreAdvisory",true).put("grantsAdded",new JSONArray()).put("scriptsExecutable",false);
        }
    }
    public JSONObject readForHost(JSONObject scope,String id,String path,int offset,int length,String expectedDigest,Set<String> hostCapabilities,long now)throws Exception {
        synchronized(lock){JSONObject record=required(state().getJSONArray("skills"),scope(scope),id);return readVerified(record,assertHostRead(record,scope,expectedDigest,hostCapabilities,now),path,offset,length,now).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(scope));}
    }
    /** Discovery consumers can additionally pin the revision, including same-content ZIP restores. */
    public JSONObject readForHost(JSONObject scope,String id,String path,int offset,int length,int expectedRevision,String expectedDigest,Set<String> hostCapabilities,long now)throws Exception {
        if(expectedRevision<1)throw new IllegalArgumentException("技能版本必须为正整数");
        synchronized(lock){JSONObject record=required(state().getJSONArray("skills"),scope(scope),id);
            if(record.getInt("revision")!=expectedRevision)throw new SecurityException("技能版本已改变，请重新发现");
            return readVerified(record,assertHostRead(record,scope,expectedDigest,hostCapabilities,now),path,offset,length,now).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(scope));}
    }
    private JSONObject assertHostRead(JSONObject record,JSONObject nativeScope,String expectedDigest,Set<String> hostCapabilities,long now)throws Exception {
        SkillSourcePolicy.requireApplicable(record,nativeScope);
        if(!SkillSourcePolicy.modelAllowed(record))throw new SecurityException("此技能仅供主人手动使用，模型不能读取、采用或执行");
        if(!preferred(record,preferredSources(state().getJSONArray("skills"),nativeScope,now)))throw new SecurityException("同名技能已由更高优先级或稳定首选来源覆盖，请重新发现");
        return assertApprovedRead(record,nativeScope,expectedDigest,hostCapabilities,now);
    }
    private JSONObject assertApprovedRead(JSONObject record,JSONObject nativeScope,String expectedDigest,Set<String> hostCapabilities,long now)throws Exception {
        SkillSourcePolicy.requireApplicable(record,nativeScope);
        Set<String> capabilities=hostCapabilities(hostCapabilities);
        if(!active(record,now)||!record.getString("digest").equals(expectedDigest))throw new SecurityException("技能未启用、已失效或版本已改变");JSONArray required=record.getJSONArray("requiredCapabilities");
        for(int i=0;i<required.length();i++)if(!capabilities.contains(required.getString(i)))throw new SecurityException("宿主未授予技能所需能力："+required.getString(i));
        return verifiedFiles(record);
    }
    /** Only the native foreground owner flow can retain this value. It is not a model read grant. */
    static final class OwnerExecutionSelection {
        private final SkillBook issuer;private final String scope,id,digest;private final int revision;private final long generation;private final Set<String> capabilities;
        private OwnerExecutionSelection(SkillBook issuer,JSONObject scope,JSONObject row,Set<String> capabilities)throws Exception {
            this.issuer=issuer;this.scope=scope.toString();id=row.getString("id");revision=row.getInt("revision");digest=row.getString("digest");generation=row.optLong("executionGeneration",0);this.capabilities=Set.copyOf(capabilities);
        }
        void checkCurrent()throws Exception{issuer.ownerExecutionSnapshot(this,capabilities,System.currentTimeMillis());}
        void requireRequest(JSONObject requestedScope,String requestedId,int requestedRevision,String requestedDigest)throws Exception {
            if(!SkillSourcePolicy.scopeFingerprint(new JSONObject(scope)).equals(SkillSourcePolicy.scopeFingerprint(requestedScope))||!id.equals(requestedId)||revision!=requestedRevision||!digest.equals(requestedDigest))throw new SecurityException("执行请求不是主人明确选择的准确技能与原项目");
        }
        JSONObject read(String path,int offset,int length,long now)throws Exception {
            synchronized(issuer.lock){JSONObject nativeScope=new JSONObject(scope),row=required(issuer.state().getJSONArray("skills"),scope(nativeScope),id);require(row);return issuer.readVerified(row,issuer.assertApprovedRead(row,nativeScope,digest,capabilities,now),path,offset,length,now).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(nativeScope));}
        }
        private void require(JSONObject row)throws Exception {
            if(row.getInt("revision")!=revision||!digest.equals(row.getString("digest"))||row.optLong("executionGeneration",0)!=generation||!SkillSourcePolicy.ownerInvocable(row))throw new SecurityException("主人所选技能已变化、已撤回或清单不允许直接调用");
        }
    }
    OwnerExecutionSelection selectExecutionForOwner(JSONObject scope,String id,int revision,String digest,long generation,Set<String> capabilities,long now)throws Exception {
        synchronized(lock){JSONObject row=required(state().getJSONArray("skills"),scope(scope),id);assertApprovedRead(row,scope,digest,capabilities,now);
            if(row.getInt("revision")!=revision||row.optLong("executionGeneration",0)!=generation||!SkillSourcePolicy.ownerInvocable(row))throw new SecurityException("主人所选技能版本或批准已变化，或清单禁止直接调用");
            return new OwnerExecutionSelection(this,scope,row,capabilities);
        }
    }
    private ExecutionSnapshot ownerExecutionSnapshot(OwnerExecutionSelection selected,Set<String> capabilities,long now)throws Exception {
        synchronized(lock){return ownerExecutionSnapshotLocked(selected,capabilities,now);}
    }
    <T>T withOwnerExecutionSnapshot(OwnerExecutionSelection selected,Set<String> capabilities,long now,ExecutionAction<T> action)throws Exception {
        synchronized(lock){return action.run(ownerExecutionSnapshotLocked(selected,capabilities,now));}
    }
    private ExecutionSnapshot ownerExecutionSnapshotLocked(OwnerExecutionSelection selected,Set<String> capabilities,long now)throws Exception {
        if(selected==null||selected.issuer!=this)throw new SecurityException("没有原主人技能选择");
        JSONObject row=required(state().getJSONArray("skills"),scope(new JSONObject(selected.scope)),selected.id);selected.require(row);
        JSONObject nativeScope=new JSONObject(selected.scope);
        return executionSnapshot(row,assertApprovedRead(row,nativeScope,selected.digest,capabilities,now),"owner_selection",nativeScope);
    }
    /** Exact current bytes for the native execution leaf; never exposes a private filesystem path. */
    ExecutionSnapshot executionSnapshot(JSONObject scope,String id,int revision,String digest,Set<String> capabilities,long now)throws Exception {
        synchronized(lock){return executionSnapshotLocked(scope,id,revision,digest,capabilities,now);}
    }
    interface ExecutionAction<T>{T run(ExecutionSnapshot snapshot)throws Exception;}
    /** Keep current-package validation and the bounded submit in the same registry critical section. */
    <T>T withExecutionSnapshot(JSONObject scope,String id,int revision,String digest,Set<String> capabilities,long now,ExecutionAction<T> action)throws Exception {
        synchronized(lock){return action.run(executionSnapshotLocked(scope,id,revision,digest,capabilities,now));}
    }
    private ExecutionSnapshot executionSnapshotLocked(JSONObject scope,String id,int revision,String digest,Set<String> capabilities,long now)throws Exception {
        JSONObject record=required(state().getJSONArray("skills"),scope(scope),id);
        if(record.getInt("revision")!=revision)throw new SecurityException("技能版本已改变，请重新审阅执行计划");
        JSONObject files=assertHostRead(record,scope,digest,capabilities,now);
        return executionSnapshot(record,files,"model_request",scope);
    }
    private ExecutionSnapshot executionSnapshot(JSONObject record,JSONObject files,String invocation,JSONObject nativeScope)throws Exception {
        JSONObject binding=new JSONObject().put("id",record.getString("id")).put("revision",record.getInt("revision")).put("digest",record.getString("digest")).put("invocationOrigin",invocation)
                .put("executionGeneration",record.optLong("executionGeneration",0)).put("approvedDigest",record.getString("approvedDigest"))
                .put("source",record.getString("source")).put("sourceVersion",record.getString("sourceVersion")).put("sourcePolicy",SkillSourcePolicy.metadata(record)).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(nativeScope))
                .put("requiredCapabilities",new JSONArray(record.getJSONArray("requiredCapabilities").toString()))
                .put("expiresAt",record.optLong("expiresAt",0));
        if(record.has("origin"))binding.put("origin",ExperienceOrigin.fromJson(record.getJSONObject("origin")).toJson());
        if(record.has("mcpSource"))binding.put("mcpSource",new JSONObject(record.getJSONObject("mcpSource").toString()));
        return new ExecutionSnapshot(binding,files);
    }
    static final class ExecutionSnapshot {
        final JSONObject binding;
        final java.util.Map<String,byte[]> files;
        ExecutionSnapshot(JSONObject binding,JSONObject files)throws Exception {
            this.binding=new JSONObject(binding.toString());java.util.Map<String,byte[]> bytes=new java.util.TreeMap<>();
            java.util.Iterator<String> names=files.keys();while(names.hasNext()){String name=names.next();bytes.put(name,files.getString(name).getBytes(StandardCharsets.UTF_8));}
            this.files=java.util.Collections.unmodifiableMap(bytes);
        }
    }
    public enum SourceChoice { REPLACE, FORK }
    /** Opaque reviewed content on this exact original book/scope/target. JSON is presentation only. */
    public static final class SourceReview {
        private final SkillBook issuer;
        private final String owner,space,targetPin,files,git,restore,mcp,view;
        private final dev.pocketengine.features.plugins.McpSkillSource.Fetched mcpRead;
        private final long expiresAt;
        private volatile boolean applied;
        private SourceReview(SkillBook issuer,String[] scope,String targetPin,JSONObject files,JSONObject git,JSONObject restore,JSONObject view,long now)throws Exception {
            this(issuer,scope,targetPin,files,git,restore,null,null,view,now);
        }
        private SourceReview(SkillBook issuer,String[] scope,String targetPin,JSONObject files,JSONObject git,JSONObject restore,JSONObject mcp,dev.pocketengine.features.plugins.McpSkillSource.Fetched mcpRead,JSONObject view,long now)throws Exception {
            this.issuer=issuer;owner=scope[0];space=scope[1];this.targetPin=targetPin;
            this.files=files.toString();this.git=git==null?null:git.toString();this.restore=restore==null?null:restore.toString();this.mcp=mcp==null?null:mcp.toString();this.mcpRead=mcpRead;this.view=view.toString();
            expiresAt=Math.addExact(now,600_000L);
        }
        public JSONObject view()throws Exception { return new JSONObject(view); }
    }
    public static final class SourceUnavailable extends Exception {
        public final String code;
        SourceUnavailable(String code) {
            super(switch(code) {
                case "local_unknown" -> "本地内容或基线无法完整校验，已保留原技能；请先核对文件或从已知备份修复。";
                case "stale_target" -> "技能内容、策略或启停已变化，旧预览未应用；请重新检查。";
                case "origin_bound" -> "经验来源型技能保留原来源审阅流程，不能用 Git 内容覆盖。";
                case "discard_confirmation_required" -> "请明确确认替换本地修改，或选择保留、另存为新技能。";
                default -> "保留版本暂不可核对；没有猜测来源版本或恢复旧授权。";
            });
            this.code=code;
        }
    }
    private static String readSourceText(File file,int limit)throws Exception {
        try(InputStream input=Files.newInputStream(file.toPath());java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream()) {
            byte[] part=new byte[8192];int count;
            while((count=input.read(part,0,Math.min(part.length,limit-body.size()+1)))!=-1) {
                if(count==0)continue;body.write(part,0,count);if(body.size()>limit)throw new SourceUnavailable("local_unknown");
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body.toByteArray())).toString();
        }
    }
    private static final class LocalPackage {
        final JSONObject record,files;
        final String digest;
        final boolean registeredContent;
        LocalPackage(JSONObject record,JSONObject files)throws Exception {
            this.record=record;this.files=files;digest=SkillBook.digest(files);registeredContent=digest.equals(record.getString("digest"));
        }
    }
    /** Complete bounded local package scan, including newly added files; never follows links. */
    private LocalPackage localPackage(String[] owner,String id)throws Exception {
        if(id==null||id.isEmpty())return null;
        JSONObject record=required(state().getJSONArray("skills"),owner,id);
        if(record.has("origin"))throw new SourceUnavailable("origin_bound");
        try {
            File directory=directory(record);java.nio.file.Path base=directory.toPath();
            if(Files.isSymbolicLink(base)||!Files.isDirectory(base))throw new SourceUnavailable("local_unknown");
            JSONObject files=new JSONObject();long bytes=0;int directories=0;
            try(java.util.stream.Stream<java.nio.file.Path> walk=Files.walk(base,13)) {
                Iterator<java.nio.file.Path> paths=walk.iterator();
                while(paths.hasNext()) {
                    java.nio.file.Path entry=paths.next();
                    if(Files.isSymbolicLink(entry))throw new SourceUnavailable("local_unknown");
                    if(Files.isDirectory(entry)){if(!entry.equals(base)&&base.relativize(entry).getNameCount()>=13)throw new SourceUnavailable("local_unknown");if(++directories>32)throw new SourceUnavailable("local_unknown");continue;}
                    if(!Files.isRegularFile(entry)||files.length()>=41||Files.size(entry)>256000)throw new SourceUnavailable("local_unknown");
                    String name=path(base.relativize(entry).toString().replace(File.separatorChar,'/'));
                    String body=readSourceText(entry.toFile(),256000);bytes+=body.getBytes(StandardCharsets.UTF_8).length;
                    if(body.length()>64000||bytes>1_000_000)throw new SourceUnavailable("local_unknown");
                    files.put(name,body);
                }
            }
            // The original archive rules validate the complete snapshot, but do not approve it.
            JSONObject checked=SkillArchive.read(new java.io.ByteArrayInputStream(SkillArchive.write(record.getString("name"),files)));
            return new LocalPackage(record,checked);
        } catch(SourceUnavailable known) { throw known; }
        catch(Exception invalid) { throw new SourceUnavailable("local_unknown"); }
    }
    private static String targetPin(LocalPackage local)throws Exception {
        if(local==null)return "";JSONObject record=local.record;
        JSONObject values=new JSONObject().put("id",record.getString("id")).put("revision",Integer.toString(record.getInt("revision"))).put("digest",record.getString("digest"))
                .put("actualDigest",local.digest).put("executionGeneration",Long.toString(record.optLong("executionGeneration",0))).put("sourcePolicy",SkillSourcePolicy.current(record).toString())
                .put("enabled",Boolean.toString(record.optBoolean("enabled"))).put("approvedDigest",record.optString("approvedDigest"))
                .put("requiredCapabilities",record.getJSONArray("requiredCapabilities").toString()).put("expiresAt",String.valueOf(record.opt("expiresAt")));
        JSONObject git=record.optJSONObject("gitSource");
        if(git!=null)for(String key:new String[]{"source","commit","baseDigest","baseRevision"})values.put("git:"+key,String.valueOf(git.get(key)));
        if(record.has("mcpSource"))values.put("mcpSource",record.getJSONObject("mcpSource").toString());
        return digest(values);
    }
    private JSONObject inspectSourceFiles(String[] owner,JSONObject files,String id,LocalPackage current,long now)throws Exception {
        JSONObject inspection=inspectFiles(owner,files,id,now);
        if(current!=null) {
            JSONArray added=new JSONArray(),removed=new JSONArray(),modified=new JSONArray();
            for(String name:jsonKeys(files)) {
                if(!current.files.has(name))added.put(name);
                else if(!current.files.getString(name).equals(files.getString(name)))modified.put(name);
            }
            for(String name:jsonKeys(current.files))if(!files.has(name))removed.put(name);
            inspection.getJSONObject("comparison").put("added",added).put("removed",removed).put("modified",modified)
                    .put("currentContentVerified",current.registeredContent).put("actualCurrentDigest",current.digest);
        }
        return inspection;
    }
    /** Git download occurs outside the existing registry lock; current content is rehashed at both ends. */
    public SourceReview inspectGitSource(JSONObject scope,String id,SkillGitSource source,SkillGitClient client,dev.pocketengine.core.RunControl control,long now)throws Exception {
        String[] owner=scope(scope);String pin;
        synchronized(lock){pin=targetPin(localPackage(owner,id));}
        SkillGitSource.Fetched fetched=client.fetch(source,control);control.check();
        synchronized(lock) {
            LocalPackage local=localPackage(owner,id);if(!pin.equals(targetPin(local)))throw new SourceUnavailable("stale_target");
            JSONObject current=local==null?null:local.record,files=fetched.files(),inspection=inspectSourceFiles(owner,files,id,local,now);
            JSONObject git=current==null?null:current.optJSONObject("gitSource");
            boolean tracked=git!=null&&source.equals(SkillGitSource.fromJson(git.getJSONObject("source"))),localChanged=false,upstream=false;
            String classification="untracked";
            if(tracked) {
                try {
                    JSONObject baseline=retainedRecord(owner,current.getString("id"),git.getInt("baseRevision"));
                    // A native recorded base hash remains known even if its directory was edited.
                    // Re-reading that mutated directory would incorrectly turn a known local edit into unknown.
                    if(!git.getString("baseDigest").equals(baseline.getString("digest")))throw new SourceUnavailable("local_unknown");
                } catch(Exception unavailable) { throw new SourceUnavailable("local_unknown"); }
                localChanged=!local.digest.equals(git.getString("baseDigest"));upstream=!fetched.digest.equals(git.getString("baseDigest"));
                classification=localChanged?(upstream?"both_changed":"local_changed"):(upstream?"upstream_changed":"current");
            }
            JSONObject nativeGit=new JSONObject().put("source",source.toJson()).put("commit",fetched.commit).put("baseDigest",fetched.digest).put("observedAt",fetched.observedAt).put("newBase",true);
            inspection.put("kind","git_update").put("targetId",id==null?"":id).put("source",fetched.view()).put("classification",classification)
                    .put("localChanged",tracked?localChanged:JSONObject.NULL).put("upstreamChanged",tracked?upstream:JSONObject.NULL)
                    .put("requiresDiscardConfirmation",current!=null&&(!tracked||localChanged)).put("base",git==null?JSONObject.NULL:new JSONObject(git.toString())).put("executionAuthorized",false);
            return new SourceReview(this,owner,pin,files,nativeGit,null,inspection,now);
        }
    }
    /** A fetched native resource becomes the same reviewed, disabled package flow as other sources. */
    public SourceReview inspectMcpSource(JSONObject scope,String id,dev.pocketengine.features.plugins.McpSkillSource.Fetched fetched,long now)throws Exception {
        if(fetched==null)throw new IllegalArgumentException("必须先从原MCP安装读取技能资源");fetched.checkCurrent();String[] owner=scope(scope);
        synchronized(lock){
            requireMcpSource(fetched.binding());reconcileMcpSources(scope,now);LocalPackage local=localPackage(owner,id);
            JSONObject files=new JSONObject().put("SKILL.md",fetched.text()),source=fetched.binding().put("baseDigest",digest(new JSONObject().put("SKILL.md",fetched.text())));
            JSONObject inspection=inspectSourceFiles(owner,files,id,local,now),previous=local==null?null:local.record.optJSONObject("mcpSource");
            boolean tracked=previous!=null&&previous.getString("installationId").equals(source.getString("installationId"))&&previous.getString("resourceURI").equals(source.getString("resourceURI"));
            boolean upstream=tracked&&!previous.getString("contentDigest").equals(source.getString("contentDigest"));
            boolean localChanged=tracked&&!local.digest.equals(previous.optString("baseDigest"));
            String classification=local==null?"untracked":tracked?(localChanged?(upstream?"both_changed":"local_changed"):upstream?"upstream_changed":"current"):"source_changed";
            JSONArray conflicts=new JSONArray();JSONArray records=state().getJSONArray("skills");String name=inspection.getJSONObject("package").getString("name");
            for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);if(matches(record,owner)&&name.equals(record.getString("name"))&&(id==null||!id.equals(record.getString("id"))))conflicts.put(new JSONObject().put("id",record.getString("id")).put("revision",record.getInt("revision")).put("source",record.getString("source")));}
            inspection.put("kind","mcp_resource_update").put("targetId",id==null?"":id).put("source",source).put("classification",classification)
                    .put("requiresDiscardConfirmation",local!=null&&(!tracked||localChanged)).put("nameConflicts",conflicts)
                    .put("localRootAvailable",false).put("relativeResourceResolver",false).put("hooksAllowed",false).put("executionAuthorized",false);
            return new SourceReview(this,owner,targetPin(local),files,null,null,source,fetched,inspection,now);
        }
    }
    /** Source text cannot set permissions. Fork preserves target restrictions, never its approval. */
    public JSONObject applySourceReview(JSONObject scope,SourceReview review,SourceChoice choice,boolean discardLocal,long now)throws Exception {
        String[] owner=scope(scope);
        synchronized(lock) {
            requireReview(owner,review,now);JSONObject view=review.view();LocalPackage local=localPackage(owner,view.getString("targetId"));
            if(!review.targetPin.equals(targetPin(local)))throw new SourceUnavailable("stale_target");
            JSONObject current=local==null?null:local.record;
            if(choice==null||review.restore!=null&&choice==SourceChoice.FORK)throw new IllegalArgumentException("请选择明确的替换操作");
            if(choice==SourceChoice.REPLACE&&view.optBoolean("requiresDiscardConfirmation")&&!discardLocal)throw new SourceUnavailable("discard_confirmation_required");
            JSONObject files=new JSONObject(review.files),options=new JSONObject();
            if(current!=null) {
                options.put("requiredCapabilities",new JSONArray(current.getJSONArray("requiredCapabilities").toString())).put("expiresAt",current.get("expiresAt"));
                if(choice==SourceChoice.REPLACE)options.put("id",current.getString("id")).put("revision",current.getInt("revision"));
            }
            JSONObject git=review.git==null?null:new JSONObject(review.git),restored=review.restore==null?null:new JSONObject(review.restore);
            JSONObject mcp=review.mcp==null?null:new JSONObject(review.mcp);if(mcp!=null){if(review.mcpRead!=null)review.mcpRead.checkCurrent();requireMcpSource(mcp);}
            options.put("source",mcp!=null?"mcp:"+mcp.getString("pluginId"):restored==null?git.getJSONObject("source").getString("repository"):view.getString("historicalSource"))
                    .put("sourceVersion",mcp!=null?mcp.getString("contentDigest"):restored==null?git.getString("commit"):view.getString("historicalSourceVersion"));
            options.put("manifest",files.getString("SKILL.md"));files.remove("SKILL.md");options.put("resources",files);
            JSONObject installed=installRetained(scope,options,now,null,git,restored,mcp,current==null?null:SkillSourcePolicy.current(current));review.applied=true;return installed;
        }
    }
    private void requireReview(String[] owner,SourceReview review,long now)throws SourceUnavailable {
        if(review==null||review.applied||review.issuer!=this||!owner[0].equals(review.owner)||!owner[1].equals(review.space)||now>review.expiresAt)throw new SourceUnavailable("stale_target");
    }
    /** Frozen revision metadata only; omits enable/approval/execution policy. Same original directories. */
    private void retainRevision(JSONObject record,long now)throws Exception {
        File target=child(root,record.getString("id")+"/"+record.getInt("revision")+"/retained.json");if(target.exists())return;
        JSONObject copy=new JSONObject(record.toString());
        for(String key:new String[]{"enabled","approvedDigest","executionGeneration","requiredCapabilities","expiresAt","sourcePolicy"})copy.remove(key);
        copy.put("retainedFormat",1).put("metadataObservedAt",now);byte[] bytes=copy.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>512000)throw new IllegalStateException("技能保留版本元数据过大");writeBytes(target,bytes);
    }
    private JSONObject retainedRecord(String[] owner,String id,int revision)throws Exception {
        if(!id.matches("[a-f0-9-]{36}")||revision<1)throw new SourceUnavailable("revision_unknown");
        File file=child(root,id+"/"+revision+"/retained.json");
        if(!file.isFile()||file.length()>512000)throw new SourceUnavailable("revision_unknown");JSONObject record=new JSONObject(readSourceText(file,512000));
        if(record.optInt("retainedFormat")!=1||!id.equals(record.optString("id"))||revision!=record.optInt("revision")||!matches(record,owner)||record.has("origin"))throw new SourceUnavailable("revision_unknown");
        return record;
    }
    /** At most twenty metadata rows, stable beforeRevision cursor; missing old provenance stays unknown. */
    public JSONObject retainedRevisions(JSONObject scope,String id,int beforeRevision,int limit,long now)throws Exception {
        if(id==null||id.isEmpty()||limit<1||limit>20||beforeRevision<0)throw new IllegalArgumentException("请选择技能，版本窗口必须为1至20条");
        synchronized(lock) {
            String[] owner=scope(scope);LocalPackage local=localPackage(owner,id);JSONObject current=local.record;
            if(local.registeredContent)retainRevision(current,now);
            int start=beforeRevision==0?current.getInt("revision"):Math.min(current.getInt("revision"),beforeRevision-1);JSONArray rows=new JSONArray();
            for(int revision=start;revision>0&&rows.length()<limit;revision--) {
                JSONObject row=new JSONObject().put("revision",revision).put("available",false).put("sourceVersion",JSONObject.NULL).put("metadataObservedAt",JSONObject.NULL);
                try {
                    JSONObject original=retainedRecord(owner,id,revision);verifiedFiles(original);
                    row.put("available",true).put("digest",original.getString("digest")).put("source",original.getString("source")).put("sourceVersion",original.getString("sourceVersion"))
                            .put("metadataObservedAt",original.getLong("metadataObservedAt")).put("gitSource",original.optJSONObject("gitSource")==null?JSONObject.NULL:original.getJSONObject("gitSource"));
                } catch(Exception unknown) { row.put("reason","retained_metadata_or_content_unavailable"); }
                rows.put(row);
            }
            int last=rows.length()==0?0:rows.getJSONObject(rows.length()-1).getInt("revision");
            return new JSONObject().put("id",id).put("revisions",rows).put("beforeRevision",last>1?last:JSONObject.NULL).put("currentRevision",current.getInt("revision"))
                    .put("executionAuthorized",false).put("unknownMeaning","older_metadata_not_backfilled");
        }
    }
    public SourceReview inspectRetainedRevision(JSONObject scope,String id,int revision,long now)throws Exception {
        if(id==null||id.isEmpty())throw new IllegalArgumentException("请选择要恢复的技能");
        synchronized(lock) {
            String[] owner=scope(scope);LocalPackage local=localPackage(owner,id);JSONObject current=local.record;
            if(revision>current.getInt("revision"))throw new SourceUnavailable("revision_unknown");
            if(local.registeredContent)retainRevision(current,now);
            JSONObject retained=retainedRecord(owner,id,revision),files=verifiedFiles(retained),inspection=inspectSourceFiles(owner,files,id,local,now);
            JSONObject restored=new JSONObject().put("revision",revision).put("digest",retained.getString("digest"));
            inspection.put("kind","restore_revision").put("targetId",id).put("requiresDiscardConfirmation",true).put("historicalSource",retained.getString("source"))
                    .put("historicalSourceVersion",retained.getString("sourceVersion")).put("restoredFrom",restored).put("executionAuthorized",false);
            return new SourceReview(this,owner,targetPin(local),files,retained.optJSONObject("gitSource"),restored,retained.optJSONObject("mcpSource"),null,inspection,now);
        }
    }
    public JSONObject readSourceReview(JSONObject scope,SourceReview review,String path,int offset,int length,long now)throws Exception {
        String[] owner=scope(scope);requireReview(owner,review,now);JSONObject files=new JSONObject(review.files);String name=path(path);
        if(!files.has(name))throw new IllegalArgumentException("候选版本没有该文件");String body=files.getString(name);
        if(offset<0||offset>body.length()||length<1||length>12000||offset>0&&offset<body.length()&&Character.isLowSurrogate(body.charAt(offset)))throw new IllegalArgumentException("候选读取范围无效");
        int end=(int)Math.min(body.length(),(long)offset+length);if(end<body.length()&&end>offset&&Character.isHighSurrogate(body.charAt(end-1)))end--;
        return new JSONObject().put("path",name).put("content",body.substring(offset,end)).put("nextOffset",end).put("hasMore",end<body.length()).put("untrustedReference",true).put("executionAuthorized",false);
    }

    /** Native package ownership cleanup only. Exact current content and approval-generation CAS share this original lock. */
    public JSONObject deletePackageReviewed(JSONObject scope,String id,int revision,String digest,long executionGeneration)throws Exception {
        synchronized(lock){JSONObject record=required(state().getJSONArray("skills"),scope(scope),id);
            if(record.has("origin")||record.getInt("revision")!=revision||!record.getString("digest").equals(digest)||record.optLong("executionGeneration",0)!=executionGeneration)throw new SecurityException("技能已被更新或审批改变，包卸载未删除此技能");
            delete(scope,id);return new JSONObject().put("deleted",true).put("id",id).put("revision",revision).put("digest",digest);
        }
    }
    public void delete(JSONObject scope,String id)throws Exception {
        synchronized(lock){JSONObject state=state();JSONObject record=required(state.getJSONArray("skills"),scope(scope),id);remove(state.getJSONArray("skills"),id);
            if(record.has("origin")){cleanups(state).put(cleanupReceipt(id,0,scope(scope)));save(state);
                if(retryCleanups(state,scope(scope))>0)throw new IOException("技能已从登记移除，但来源内容清理未完成；请恢复存储后重试经验对账");}
            else{save(state);erase(new File(root,record.getString("id")));}}
    }
    private JSONObject state()throws Exception {if(!index.isFile())return new JSONObject().put("version",1).put("skills",new JSONArray());if(index.length()>2_000_000)throw new IllegalStateException("技能索引过大");JSONObject value=new JSONObject(readText(index));if(value.optJSONArray("skills")==null)throw new IllegalStateException("技能索引损坏，未覆盖原数据");return value;}
    private void save(JSONObject value)throws Exception {if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("无法创建技能目录");File pending=new File(root,"index.pending");writeBytes(pending,value.toString().getBytes(StandardCharsets.UTF_8));try{Files.move(pending.toPath(),index.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(pending.toPath(),index.toPath(),StandardCopyOption.REPLACE_EXISTING);}}
    private File directory(JSONObject record)throws Exception{return new File(root,record.getString("id")+"/"+record.getInt("revision")+"/"+record.getString("name"));}
    private void verify(JSONObject record)throws Exception {verifiedFiles(record);}
    private JSONObject verifiedFiles(JSONObject record)throws Exception {
        requireRecordOrigin(record);
        SkillSourcePolicy.current(record);
        JSONObject files=new JSONObject();JSONArray paths=record.getJSONArray("resources");for(int i=0;i<paths.length();i++){String name=path(paths.getString(i));File file=child(directory(record),name);if(!file.isFile()||file.length()>256000)throw new IllegalStateException("技能资源已丢失或改变，请重新导入");files.put(name,readText(file));}
        if(!digest(files).equals(record.getString("digest")))throw new IllegalStateException("技能内容已改变，请重新导入并确认新版本");
        JSONObject metadata=SkillManifest.parse(files.getString("SKILL.md"));
        for(String key:new String[]{"name","description","license","compatibility","allowed-tools"})
            if(metadata.has(key)!=record.has(key)||!metadata.optString(key).equals(record.optString(key)))throw new IllegalStateException("技能索引元数据与已批准内容不一致，请重新导入");
        for(String key:new String[]{"disable-model-invocation","user-invocable"})
            if(metadata.has(key)!=record.has(key)||metadata.has(key)&&!metadata.get(key).equals(record.get(key)))throw new IllegalStateException("技能调用策略与已批准内容不一致，请重新导入");
        JSONObject expected=metadata.getJSONObject("metadata"),actual=record.getJSONObject("metadata");
        if(expected.length()!=actual.length())throw new IllegalStateException("技能索引元数据与已批准内容不一致，请重新导入");
        java.util.Iterator<String> keys=expected.keys();while(keys.hasNext()){String key=keys.next();if(!expected.getString(key).equals(actual.opt(key)))throw new IllegalStateException("技能索引元数据与已批准内容不一致，请重新导入");}
        requireRecordOrigin(record);return files;
    }
    static String digest(JSONObject files)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");List<String> names=new ArrayList<>();files.keys().forEachRemaining(names::add);names.sort(Comparator.naturalOrder());for(String name:names){digest.update(name.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(files.getString(name).getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}StringBuilder text=new StringBuilder();for(byte b:digest.digest())text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    static JSONArray resourceNames(JSONObject files){List<String> names=new ArrayList<>();files.keys().forEachRemaining(names::add);names.sort(Comparator.naturalOrder());return new JSONArray(names);}
    private static JSONArray permissions(JSONArray input)throws Exception {JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();if(input==null)return result;if(input.length()>32)throw new IllegalArgumentException("最多声明32项能力");for(int i=0;i<input.length();i++){String permission=input.getString(i);if(!permission.matches("[a-z][a-z0-9_.:-]{0,79}"))throw new IllegalArgumentException("能力声明格式无效");if(seen.add(permission))result.put(permission);}return result;}
    private static String[] scope(JSONObject value){if(value==null)throw new IllegalArgumentException("必须指定技能空间");String owner=value.optString("ownerId"),space=value.optString("spaceId");if(!owner.matches("[\\p{L}\\p{N}_.:/@-]{1,160}")||!space.matches("[\\p{L}\\p{N}_.:/@-]{1,200}"))throw new IllegalArgumentException("技能空间无效");return new String[]{owner,space};}
    private static boolean matches(JSONObject record,String[] scope){return scope[0].equals(record.optString("ownerId"))&&scope[1].equals(record.optString("spaceId"));}
    private static JSONObject required(JSONArray records,String[] scope,String id)throws Exception {if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("技能标识无效");for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);if(id.equals(record.optString("id"))&&matches(record,scope))return record;}throw new IllegalArgumentException("当前空间没有此技能");}
    private static boolean active(JSONObject record,long now){try{return !SkillSourcePolicy.bindingRequired(record)&&record.optBoolean("enabled")&&!expired(record,now)&&record.optString("digest").equals(record.optString("approvedDigest"));}catch(Exception invalid){return false;}}
    private static boolean expired(JSONObject record,long now){return !record.isNull("expiresAt")&&record.optLong("expiresAt",0)<=now;}
    private static JSONObject present(JSONObject record,long now)throws Exception{JSONObject result=new JSONObject(record.toString()).put("active",active(record,now)).put("requiresHostAuthorization",true).put("scriptsExecutable",false)
            .put("sourceKind",SkillSourcePolicy.kind(record)).put("sourcePolicy",SkillSourcePolicy.metadata(record)).put("executionGeneration",record.optLong("executionGeneration",0))
            .put("sourceSelectionRule","project_then_user_then_bundled_then_stable_id");
        if(record.has("origin"))result.put("origin",ExperienceOrigin.fromJson(record.getJSONObject("origin")).toJson());return result;}
    static String path(String name){if(name==null||name.length()>160||name.startsWith("/")||!name.matches("[A-Za-z0-9_./-]+"))throw new IllegalArgumentException("资源路径无效");for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("资源路径不能越出技能目录");return name;}
    private static File child(File directory,String path)throws Exception {File child=new File(directory,path);if(!child.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator))throw new SecurityException("资源路径越界");return child;}
    private static long expiry(Object value){if(value==null||value==JSONObject.NULL)return 0;if(!(value instanceof Number)||((Number)value).longValue()<=0||((Number)value).doubleValue()!=((Number)value).longValue())throw new IllegalArgumentException("有效期必须为正整数时间戳或null");return ((Number)value).longValue();}
    private static String text(String value,int min,int max,String label){String text=value.trim();if(text.length()<min||text.length()>max)throw new IllegalArgumentException(label+"长度应为"+min+"至"+max);return text;}
    private static boolean contains(JSONArray list,String text){for(int i=0;i<list.length();i++)if(text.equals(list.optString(i)))return true;return false;}
    private static void remove(JSONArray rows,String id)throws Exception{for(int i=rows.length()-1;i>=0;i--)if(id.equals(rows.getJSONObject(i).optString("id")))rows.remove(i);}
    private static void writeBytes(File file,byte[] bytes)throws Exception{try(FileOutputStream output=new FileOutputStream(file)){output.write(bytes);output.getFD().sync();}}
    private static String readText(File file)throws Exception{
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();}
        catch(CharacterCodingException invalid){throw new IllegalStateException("技能存储文本已损坏，不能验证当前内容",invalid);}
    }
    private static void erase(File root)throws Exception {if(!root.exists())return;try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){java.util.Iterator<java.nio.file.Path> entries=paths.sorted(Comparator.reverseOrder()).iterator();while(entries.hasNext())Files.delete(entries.next());}}
}
