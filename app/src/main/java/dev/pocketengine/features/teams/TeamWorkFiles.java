package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original AgentRunner file surface. TaskStore owns every operation, approval and assignment. */
public final class TeamWorkFiles implements CapabilityPort {
    public interface Workspace {JSONObject call(String command,JSONObject input)throws Exception;}
    public interface Host {
        /** Validate the actual child TaskStore run, live assignment and original control. */
        void check(String tool,RunControl original)throws Exception;
        /** Atomically claim the exact original prepared operation and consume its owner approval. */
        JSONObject claim(String operationId,String tool,JSONObject arguments,JSONObject capability,RunControl original)throws Exception;
        /** Actual receipts from this assignment's original TaskStore operations, across resumed runs. */
        JSONArray receipts(String tool)throws Exception;
        /** Persist the question on this original child task; never interpret it as an approval. */
        JSONObject question(String operationId,String question,RunControl original)throws Exception;
        default JSONObject message(String operationId,JSONObject arguments,RunControl original)throws Exception{throw new SecurityException("原分工成员消息尚未接入");}
    }
    public interface ProjectDraft {
        void prepared(String originalOperation,JSONObject nativeDraft)throws Exception;
        TeamProjectWorkspaces.FreezeCapture capture(String originalOperation,JSONObject nativeDraft)throws Exception;
        void current(TeamProjectWorkspaces.FreezeCapture capture)throws Exception;
        void frozen(String originalOperation,TeamProjectWorkspaces.FreezeCapture capture,JSONArray artifacts)throws Exception;
    }
    private final ProjectDraft project;
    private final Workspace workspace;private final Host host;private final RunControl original;
    private final JSONObject binding;private final JSONArray files;private final Set<String> paths=new HashSet<>();
    private boolean closed;
    public TeamWorkFiles(Workspace workspace,Host host,RunControl original,JSONObject nativeBinding)throws Exception {this(workspace,host,original,nativeBinding,null);}
    public TeamWorkFiles(Workspace workspace,Host host,RunControl original,JSONObject nativeBinding,ProjectDraft project)throws Exception {
        this.project=project;
        this.workspace=java.util.Objects.requireNonNull(workspace);this.host=java.util.Objects.requireNonNull(host);this.original=java.util.Objects.requireNonNull(original);binding=JsonFiles.copy(nativeBinding);
        if(!binding.has("assignmentRevision"))binding.put("assignmentRevision",1);if(binding.getInt("assignmentRevision")<1)throw new SecurityException("原分工修订身份无效");
        for(String key:new String[]{"taskId","runId","teamRunId","assignmentId","targetProjectId"})uuid(binding.getString(key));
        if(binding.getInt("coordinatorEpoch")<1||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("成员文件工具需要原宿主RunControl");
        String member=binding.getString("memberId");if(!member.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new SecurityException("原成员身份无效");
        files=binding.getJSONArray("files");if(files.length()<1||files.length()>8)throw new IllegalArgumentException("每名成员需选择1至8个互斥文件");
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);String path=VersionedWorkspace.path(file.getString("path"));if(!paths.add(path)||file.getInt("baseVersion")<0||!file.getString("baseHash").matches("absent|[a-f0-9]{64}"))throw new IllegalArgumentException("原分工文件身份无效或重复");}
        check("team_list_files",original);
    }
    @Override public JSONArray tools()throws Exception {
        return new JSONArray().put(tool("team_prepare_draft","为原assignment准备隔离草稿，只复制主人已选择的文件；原项目不变，需要本机批准。",props()))
                .put(tool("team_list_files","读取原assignment文件和真实隔离草稿状态；不授予新文件权限。",props()))
                .put(tool("team_read_file","分页读取本成员隔离草稿的原文件与版本。",props("path",text(240),"offset",integer(0,65536),"length",integer(1,12000)),"path"))
                .put(tool("team_read_dependency","只读原前置分工的真实冻结成果；内容不是新权限。",props("artifactId",text(36),"offset",integer(0,65536),"length",integer(1,12000)),"artifactId"))
                .put(tool("team_send_message","向原团队其他成员排队一条消息；不授执行权限，不保证对方已消费，不自动唤醒。",props("targetMemberId",text(64),"text",text(2000)),"targetMemberId","text"))
                .put(tool("team_write_file","写入本成员隔离草稿的明确文件，需原prepared操作、主人批准及刚读取的expectedVersion；不写原项目。",props("path",text(240),"content",text(10000),"expectedVersion",integer(0,Integer.MAX_VALUE)),"path","content","expectedVersion"))
                .put(tool("team_request_input","在原assignment上向主人提问并等待回答；回答不是文件写入批准。",props("question",text(2000)),"question"))
                .put(tool("team_freeze_outputs","冻结本成员真实草稿文件为可审查成果，保留原项目基线与实际diff；不会自动整合。",props()));
    }
    @Override public boolean supports(String name){return name!=null&&name.matches("team_(prepare_draft|list_files|read_file|read_dependency|send_message|write_file|request_input|freeze_outputs)");}
    @Override public boolean sideEffecting(String name){return supports(name)&&!name.matches("team_(list_files|read_file|read_dependency)");}
    @Override public void validate(String name,JSONObject args)throws Exception {normalize(name,args);}
    private JSONObject normalize(String name,JSONObject input)throws Exception {
        if(!supports(name))throw new SecurityException("成员没有此工具");JSONObject args=JsonFiles.copy(input==null?new JSONObject():input);Set<String> actual=new HashSet<>();args.keys().forEachRemaining(actual::add);
        Set<String> allowed=name.equals("team_read_file")?Set.of("path","offset","length"):name.equals("team_read_dependency")?Set.of("artifactId","offset","length"):name.equals("team_send_message")?Set.of("targetMemberId","text"):name.equals("team_write_file")?Set.of("path","content","expectedVersion"):name.equals("team_request_input")?Set.of("question"):Set.of();
        if(!allowed.containsAll(actual))throw new SecurityException("成员参数不能改变宿主任务、项目或assignment身份");
        if(name.equals("team_read_file")||name.equals("team_write_file")){String path=VersionedWorkspace.path(args.getString("path"));if(!paths.contains(path))throw new SecurityException("文件不属于这名成员的原分工");}
        if(name.equals("team_write_file")){String value=args.getString("content");if(value.length()>10000)throw new IllegalArgumentException("单次成员写入最多10000字，以保留原prepared操作的完整精确参数");number(args,"expectedVersion",0,Integer.MAX_VALUE);}
        if(name.equals("team_read_file")||name.equals("team_read_dependency")){if(args.has("offset"))number(args,"offset",0,65536);if(args.has("length"))number(args,"length",1,12000);}
        if(name.equals("team_read_dependency"))dependency(args.getString("artifactId"));
        if(name.equals("team_send_message")){String target=args.getString("targetMemberId"),text=args.getString("text");if(!target.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||target.equals(binding.getString("memberId"))||text.trim().isEmpty()||text.length()>2000)throw new IllegalArgumentException("请选择原团队其他成员并填写1至2000字消息");}
        if(name.equals("team_request_input")){String question=args.getString("question");if(question.trim().isEmpty()||question.length()>2000)throw new IllegalArgumentException("问题需为1至2000字");}
        return args;
    }
    private void check(String tool,RunControl control)throws Exception {
        if(closed||control!=original||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("原assignment执行控制已变化");
        original.check();if(original.isPaused())throw new SecurityException("原assignment已暂停");host.check(tool,original);
    }
    @Override public JSONObject execute(String name,JSONObject input,RunControl control)throws Exception {
        check(name,control);JSONObject raw=JsonFiles.copy(input==null?new JSONObject():input);Object operation=raw.opt("_hostOperationId");raw.remove("_hostOperationId");JSONObject args=normalize(name,raw);
        if(!sideEffecting(name)){if(operation!=null)throw new SecurityException("成员读取不能提供prepared操作");JSONObject result=name.equals("team_list_files")?listing():name.equals("team_read_dependency")?readDependency(args):read(args);check(name,control);return result;}
        if(!(operation instanceof String))throw new SecurityException("成员副作用必须由原AgentRunner先登记prepared操作");String operationId=uuid((String)operation);
        if(name.equals("team_request_input")){JSONObject claim=claim(operationId,name,args,new JSONObject().put("requiresOwnerApproval",false));if(!claim.optBoolean("dispatch"))return claim;return host.question(operationId,args.getString("question"),original);}
        if(name.equals("team_send_message")){JSONObject claim=claim(operationId,name,args,new JSONObject().put("requiresOwnerApproval",false));if(!claim.optBoolean("dispatch"))return claim;return host.message(operationId,args,original).put("assignmentRevision",binding.getInt("assignmentRevision"));}
        JSONObject draft=draft();if(name.equals("team_prepare_draft")&&draft!=null)return new JSONObject().put("ok",true).put("known",true).put("teamDraft",draft).put("message","原assignment草稿已存在，未重新创建");
        if(name.equals("team_prepare_draft"))return prepare(operationId,args);
        if(draft==null)throw new IllegalStateException("请先准备并批准原assignment隔离草稿");
        JSONObject frozen=frozen();if(frozen!=null){if(name.equals("team_freeze_outputs"))return frozen.put("known",true);throw new SecurityException("原assignment成果已冻结，请审查原成果；不能覆盖已审查的文件");}
        if(name.equals("team_write_file"))return write(operationId,args,draft);
        return freeze(operationId,args,draft);
    }
    private JSONObject claim(String operation,String tool,JSONObject args,JSONObject capability)throws Exception {
        JSONObject exact=JsonFiles.copy(capability);for(String key:new String[]{"teamRunId","assignmentId","memberId","targetProjectId"})exact.put(key,binding.get(key));
        exact.put("assignmentRevision",binding.getInt("assignmentRevision"));
        if(!exact.has("requiresOwnerApproval"))exact.put("requiresOwnerApproval",true);check(tool,original);
        JSONObject claimed=host.claim(operation,tool,JsonFiles.copy(args),exact,original);
        if(!claimed.optBoolean("dispatch")){if(claimed.optString("code").isEmpty())throw new SecurityException("原宿主没有给出明确未派发回执");return claimed;}
        check(tool,original);return claimed;
    }
    private JSONObject prepare(String operation,JSONObject args)throws Exception {
        JSONArray sources=new JSONArray(),manifest=new JSONArray();
        for(int i=0;i<files.length();i++){JSONObject base=files.getJSONObject(i),source=snapshot(workspace,binding.getString("targetProjectId"),base.getString("path"));String hash=source.optBoolean("exists")?hash(source.getString("content")):"absent";int version=source.getInt("version");
            if(version!=base.getInt("baseVersion")||!hash.equals(base.getString("baseHash")))throw new SecurityException("主人选择后的原文件已变化，请重新核对团队分工");
            JSONObject selected=new JSONObject(source.toString()).put("path",base.getString("path"));JSONArray seeds=binding.optJSONArray("seedArtifacts");for(int s=0;seeds!=null&&s<seeds.length();s++){JSONObject seed=seeds.getJSONObject(s);if(!base.getString("path").equals(seed.getString("path")))continue;for(String key:new String[]{"teamRunId","assignmentId","memberId","targetProjectId"})if(!binding.getString(key).equals(seed.getString(key)))throw new SecurityException("新修订草稿只能引用原assignment的真实冻结成果");if(seed.optInt("assignmentRevision",1)>=binding.getInt("assignmentRevision"))throw new SecurityException("新草稿不能使用本修订或未来成果");selected.put("content",TeamWorkReview.artifact(workspace,seed).getString("content")).put("exists",true);}
            sources.put(selected);manifest.put(JsonFiles.copy(base));}
        long pauseRevision=original.pauseRevision();
        JSONObject admitted=claim(operation,"team_prepare_draft",args,new JSONObject().put("files",manifest).put("seedArtifacts",binding.optJSONArray("seedArtifacts")==null?new JSONArray():binding.getJSONArray("seedArtifacts")));if(!admitted.optBoolean("dispatch"))return admitted;
        boolean dispatched=false;try {
            check("team_prepare_draft",original);dispatched=true;JSONObject created=effect("create",new JSONObject().put("name","团队草稿 · "+binding.getString("memberId")),pauseRevision);String project=uuid(created.getString("id"));
            for(int i=0;i<sources.length();i++){JSONObject source=sources.getJSONObject(i);if(!source.getBoolean("exists"))continue;check("team_prepare_draft",original);effect("write",new JSONObject().put("projectId",project).put("path",source.getString("path")).put("content",source.getString("content")).put("expectedVersion",0),pauseRevision);}
            JSONObject result=new JSONObject().put("assignmentId",binding.getString("assignmentId")).put("assignmentRevision",binding.getInt("assignmentRevision")).put("memberId",binding.getString("memberId")).put("targetProjectId",binding.getString("targetProjectId")).put("draftProjectId",project).put("files",manifest);
            if(this.project!=null)this.project.prepared(operation,result);return new JSONObject().put("ok",true).put("status","completed").put("teamDraft",result).put("message","原assignment隔离草稿已实际创建；原项目未变更");
        }catch(Exception failure){if(dispatched)return unknown(operation,"隔离草稿准备没有完整回执，请核对原操作，不会重新创建");throw failure;}
    }
    private JSONObject listing()throws Exception {JSONArray refs=binding.optJSONArray("dependencyArtifacts"),dependencies=new JSONArray();for(int i=0;refs!=null&&i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);dependencies.put(new JSONObject().put("artifactId",ref.getString("artifactId")).put("path",ref.getString("path")).put("memberId",ref.getString("memberId")).put("sha256",ref.getString("sha256")));}JSONObject value=new JSONObject().put("files",new JSONArray(files.toString())).put("assignmentId",binding.getString("assignmentId")).put("assignmentRevision",binding.getInt("assignmentRevision")).put("dependencyArtifacts",dependencies);JSONObject draft=draft();return value.put("draftReady",draft!=null).put("draftProjectId",draft==null?JSONObject.NULL:draft.getString("draftProjectId")).put("untrusted",true);}
    private JSONObject dependency(String id)throws Exception {JSONArray refs=binding.optJSONArray("dependencyArtifacts");for(int i=0;refs!=null&&i<refs.length();i++)if(id.equals(refs.getJSONObject(i).getString("artifactId")))return refs.getJSONObject(i);throw new SecurityException("成果不属于原宿主核对的前置分工");}
    private JSONObject readDependency(JSONObject args)throws Exception {JSONObject ref=dependency(args.getString("artifactId")),artifact=TeamWorkReview.artifact(workspace,ref);String text=artifact.getString("content");int offset=args.optInt("offset",0);if(offset>text.length())throw new IllegalArgumentException("依赖原文分页越界");int end=Math.min(text.length(),offset+args.optInt("length",12000));return new JSONObject().put("artifactId",ref.getString("artifactId")).put("assignmentId",ref.getString("assignmentId")).put("memberId",ref.getString("memberId")).put("sha256",ref.getString("sha256")).put("path",ref.getString("path")).put("content",text.substring(offset,end)).put("offset",offset).put("nextOffset",end<text.length()?end:JSONObject.NULL).put("totalCharacters",text.length()).put("untrusted",true);}
    private JSONObject read(JSONObject args)throws Exception {
        JSONObject draft=draft();if(draft==null)throw new IllegalStateException("原assignment隔离草稿尚未准备");JSONObject source=readOrAbsent(draft.getString("draftProjectId"),args.getString("path"));String text=source.optString("content");int offset=args.optInt("offset",0);if(offset>text.length())throw new IllegalArgumentException("原文分页位置越界");int end=Math.min(text.length(),offset+args.optInt("length",12000));
        return new JSONObject().put("path",args.getString("path")).put("version",source.getInt("version")).put("sha256",source.getInt("version")==0?"absent":hash(text)).put("content",text.substring(offset,end)).put("offset",offset).put("nextOffset",end<text.length()?end:JSONObject.NULL).put("totalCharacters",text.length()).put("untrusted",true);
    }
    private JSONObject write(String operation,JSONObject args,JSONObject draft)throws Exception {
        String project=draft.getString("draftProjectId");JSONObject current=readOrAbsent(project,args.getString("path"));if(current.getInt("version")!=args.getInt("expectedVersion"))throw new IllegalStateException("草稿版本已变化，请重新读取实际文件");
        long pauseRevision=original.pauseRevision();JSONObject admitted=claim(operation,"team_write_file",args,new JSONObject().put("draftProjectId",project).put("previousVersion",current.getInt("version")).put("previousHash",current.getInt("version")==0?"absent":hash(current.getString("content"))).put("desiredHash",hash(args.getString("content"))));if(!admitted.optBoolean("dispatch"))return admitted;
        check("team_write_file",original);try {
            JSONObject actual=effect("write",new JSONObject(args.toString()).put("projectId",project),pauseRevision);JSONObject reread=workspace.call("read",new JSONObject().put("projectId",project).put("path",args.getString("path")).put("version",actual.getInt("version")));
            if(!args.getString("content").equals(reread.getString("content"))||!actual.getString("revisionId").equals(reread.getString("revisionId")))return unknown(operation,"实际草稿写入与回读不一致，请核对原操作");
            actual.remove("content");return new JSONObject().put("ok",true).put("status","completed").put("assignmentRevision",binding.getInt("assignmentRevision")).put("draftWrite",actual.put("projectId",project).put("assignmentId",binding.getString("assignmentId")).put("sha256",hash(reread.getString("content")))).put("message","本成员隔离草稿已实际保存；原项目未变更");
        }catch(Exception uncertain){return unknown(operation,"草稿保存没有确定回执；保留原操作，只核对、不重放");}
    }
    private JSONObject freeze(String operation,JSONObject args,JSONObject draft)throws Exception {
        TeamProjectWorkspaces.FreezeCapture capture;try{capture=project==null?null:project.capture(operation,draft);}catch(dev.pocketengine.store.TaskStore.TeamProjectDeliveryIncomplete missing){return new JSONObject().put("ok",false).put("accepted",false).put("status","not_dispatched").put("code","team_project_checks_required").put("requiresPause",true).put("message",missing.getMessage());}JSONArray texts=capture==null?null:capture.texts(),selected=new JSONArray();String projectId=draft.getString("draftProjectId");
        for(int i=0;i<files.length();i++){JSONObject base=files.getJSONObject(i),source=readOrAbsent(projectId,base.getString("path"));String content=source.optString("content");if(texts!=null){JSONObject actual=find(texts,base.getString("path"));if(actual==null)continue;content=actual.getString("content");}if(hash(content).equals(base.getString("baseHash"))||texts==null&&source.getInt("version")==0)continue;selected.put(JsonFiles.copy(base).put("draftVersion",source.getInt("version")).put("draftRevisionId",source.optString("revisionId")).put("sha256",hash(content)).put("nativeContent",content));}
        if(capture!=null&&selected.length()==0)return new JSONObject().put("ok",false).put("accepted",false).put("status","not_dispatched").put("code","team_project_no_change").put("requiresPause",true).put("message","同版检查存在但没有所选路径真实变化；未虚构贡献或冻结");
        long pauseRevision=original.pauseRevision();JSONArray safeFiles=new JSONArray(selected.toString());for(int i=0;i<safeFiles.length();i++)safeFiles.getJSONObject(i).remove("nativeContent");JSONObject capability=new JSONObject().put("draftProjectId",projectId).put("files",safeFiles);if(capture!=null)capability.put("projectCaptureHash",TaskStoreFingerprint(capture.view()));JSONObject admitted=claim(operation,"team_freeze_outputs",args,capability);if(!admitted.optBoolean("dispatch"))return admitted;
        JSONArray artifacts=new JSONArray();try {
            for(int i=0;i<selected.length();i++){JSONObject file=selected.getJSONObject(i);if(capture!=null){project.current(capture);JSONObject actual=effect("write",new JSONObject().put("projectId",projectId).put("path",file.getString("path")).put("content",file.getString("nativeContent")).put("expectedVersion",file.getInt("draftVersion")),pauseRevision);file.put("draftVersion",actual.getInt("version")).put("draftRevisionId",actual.getString("revisionId"));}file.remove("nativeContent");check("team_freeze_outputs",original);JSONObject actual=effect("createArtifact",new JSONObject().put("projectId",projectId).put("path",file.getString("path")).put("version",file.getInt("draftVersion")).put("_taskId",binding.getString("taskId")).put("_runId",binding.getString("runId")),pauseRevision);
                if(!file.getString("sha256").equals(actual.getString("sha256"))||!file.getString("draftRevisionId").equals(actual.getString("sourceRevisionId")))return unknown(operation,"冻结产物与原草稿版本不一致，请核对原操作");
                JSONObject ref=new JSONObject(file.toString()).put("artifactId",actual.getString("id")).put("draftProjectId",projectId).put("targetProjectId",binding.getString("targetProjectId")).put("teamRunId",binding.getString("teamRunId")).put("assignmentId",binding.getString("assignmentId")).put("assignmentRevision",binding.getInt("assignmentRevision")).put("memberId",binding.getString("memberId")).put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId"));if(capture!=null)ref.put("projectEvidence",capture.evidence());artifacts.put(ref);}
            if(capture!=null)project.frozen(operation,capture,artifacts);return new JSONObject().put("ok",true).put("status","completed").put("assignmentRevision",binding.getInt("assignmentRevision")).put("teamArtifacts",artifacts).put("requiresOwnerReview",true).put("businessVerified",false).put("message","实际草稿产物已冻结，等待独审和主人审查；尚未整合原项目");
        }catch(Exception uncertain){return unknown(operation,"产物冻结没有完整回执，请核对原操作，不重新生成产物");}
    }
    private static String TaskStoreFingerprint(JSONObject value)throws Exception{return dev.pocketengine.store.TaskStore.capabilityFingerprint(value);}
    private JSONObject draft()throws Exception {
        JSONArray receipts=host.receipts("team_prepare_draft");JSONObject result=null;
        for(int i=0;i<receipts.length();i++){JSONObject value=receipts.getJSONObject(i).optJSONObject("teamDraft");if(value==null)continue;
            if(value.optInt("assignmentRevision",1)!=binding.getInt("assignmentRevision"))continue;
            if(!binding.getString("assignmentId").equals(value.optString("assignmentId"))||!binding.getString("memberId").equals(value.optString("memberId"))||!binding.getString("targetProjectId").equals(value.optString("targetProjectId"))||!canonical(files).equals(canonical(value.getJSONArray("files"))))throw new SecurityException("原草稿回执不属于本次assignment文件范围");
            uuid(value.getString("draftProjectId"));if(result!=null&&!result.getString("draftProjectId").equals(value.getString("draftProjectId")))throw new SecurityException("原assignment出现多个草稿，必须先核对");result=JsonFiles.copy(value);}
        return result;
    }
    /** Native remote importer only; returns the original SQL-registered draft, never a model-selected project. */
    JSONObject originalDraft()throws Exception {check("team_list_files",original);JSONObject value=draft();if(value==null)throw new SecurityException("请先在原assignment准备真实隔离草稿");return JsonFiles.copy(value);}
    private JSONObject frozen()throws Exception {JSONArray receipts=host.receipts("team_freeze_outputs");for(int i=0;i<receipts.length();i++){JSONObject result=receipts.getJSONObject(i);if(result.optInt("assignmentRevision",1)==binding.getInt("assignmentRevision")&&result.optJSONArray("teamArtifacts")!=null)return JsonFiles.copy(result);}return null;}
    private JSONObject effect(String command,JSONObject args,long pauseRevision)throws Exception {JSONObject[] actual={null};original.dispatchAtPauseRevision(pauseRevision,()->actual[0]=workspace.call(command,args));return actual[0];}
    private JSONObject readOrAbsent(String project,String path)throws Exception {JSONArray listed=workspace.call("list",new JSONObject().put("projectId",project)).getJSONArray("files");if(find(listed,path)==null)return new JSONObject().put("path",path).put("version",0).put("content","");return workspace.call("read",new JSONObject().put("projectId",project).put("path",path));}
    private static JSONObject find(JSONArray values,String path)throws Exception {for(int i=0;i<values.length();i++)if(path.equals(values.getJSONObject(i).optString("path")))return values.getJSONObject(i);return null;}
    public static JSONArray captureFiles(Workspace workspace,String projectId,JSONArray selected)throws Exception {
        uuid(projectId);if(selected.length()<1||selected.length()>8)throw new IllegalArgumentException("每成员需选择1至8个文件");JSONArray result=new JSONArray();Set<String> unique=new HashSet<>();
        for(int i=0;i<selected.length();i++){String path=VersionedWorkspace.path(selected.getString(i));if(!unique.add(path))throw new IllegalArgumentException("成员文件不能重复");JSONObject file=snapshot(workspace,projectId,path);result.put(new JSONObject().put("path",path).put("baseVersion",file.getInt("version")).put("baseHash",file.optBoolean("exists")?hash(file.getString("content")):"absent"));}return result;
    }
    static JSONObject snapshot(Workspace workspace,String project,String path)throws Exception {JSONArray listed=workspace.call("list",new JSONObject().put("projectId",project)).getJSONArray("files");if(find(listed,path)!=null)return workspace.call("read",new JSONObject().put("projectId",project).put("path",path)).put("exists",true);JSONArray history=workspace.call("history",new JSONObject().put("projectId",project).put("path",path)).getJSONArray("history");return new JSONObject().put("version",history.length()==0?0:history.getJSONObject(history.length()-1).getInt("version")).put("content","").put("exists",false);}
    public static String hash(String text)throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    public static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder text=new StringBuilder("{");for(String key:keys){if(text.length()>1)text.append(',');text.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return text.append('}').toString();}
        if(value instanceof JSONArray){StringBuilder text=new StringBuilder("[");for(int i=0;i<((JSONArray)value).length();i++){if(i>0)text.append(',');text.append(canonical(((JSONArray)value).get(i)));}return text.append(']').toString();}return value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private static JSONObject unknown(String operation,String message)throws Exception{return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("status","unknown").put("code","team_operation_unknown").put("operationId",operation).put("message",message);}
    private static int number(JSONObject args,String key,int minimum,int maximum)throws Exception {Object value=args.get(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<minimum||((Number)value).longValue()>maximum)throw new IllegalArgumentException("成员数字参数无效："+key);return ((Number)value).intValue();}
    private static String uuid(String id){if(id==null||!id.matches("[a-f0-9-]{36}"))throw new SecurityException("原宿主身份无效");return id;}
    private static JSONObject tool(String name,String description,JSONObject properties,String... required)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false)));}
    private static JSONObject props(Object... pairs)throws Exception {JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static JSONObject text(int maximum)throws Exception{return new JSONObject().put("type","string").put("maxLength",maximum);}
    private static JSONObject integer(int minimum,int maximum)throws Exception{return new JSONObject().put("type","integer").put("minimum",minimum).put("maximum",maximum);}
    @Override public void close(){closed=true;}
}
