package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
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
    public interface Workspace {
        JSONObject call(String command,JSONObject input)throws Exception;
        default boolean byteFilesSupported(){return false;}
        default VersionedWorkspace.FileSnapshot nativeFile(String project,String path,int version)throws Exception{throw new SecurityException("原工作区尚未提供字节版本引用");}
        default VersionedWorkspace.FileSnapshot writeBytes(VersionedWorkspace.FileSnapshot expected,byte[] bytes)throws Exception{throw new SecurityException("原工作区尚未提供字节CAS");}
        default VersionedWorkspace.FileSnapshot delete(VersionedWorkspace.FileSnapshot expected)throws Exception{throw new SecurityException("原工作区尚未提供删除CAS");}
        default WorkspaceArtifacts.ArtifactRef freeze(VersionedWorkspace.FileSnapshot selected,String operation,String task,String run)throws Exception{throw new SecurityException("原工作区尚未提供原件冻结");}
        default WorkspaceArtifacts.ArtifactRef nativeArtifact(String id,String sha)throws Exception{throw new SecurityException("原工作区尚未提供原件目录");}
        default byte[] artifactBytes(WorkspaceArtifacts.ArtifactRef selected)throws Exception{throw new SecurityException("原工作区尚未提供原件读取");}
        default dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen projectPackage(String id,String sha)throws Exception{throw new SecurityException("原工作区尚未提供固定完整包原件");}
    }
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
        /** Same unique-integration child only; native caller retains current scope/read-budget admission. */
        default JSONObject integrationHistory(JSONObject args,RunControl original)throws Exception{throw new SecurityException("原整合审计读取尚未挂载");}
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
                .put(tool("team_read_file","分页读取本成员隔离草稿的原文件与版本；文字UTF-16，二进制byte/base64，删除无正文。",props("path",text(240),"offset",integer(0,2*1024*1024),"length",integer(1,12000)),"path"))
                .put(tool("team_read_dependency","只读原前置分工的真实冻结成果；二进制分页为byte/base64，删除无正文；内容不是新权限。",props("artifactId",text(36),"offset",integer(0,2*1024*1024),"length",integer(1,12000)),"artifactId"))
                .put(tool("team_send_message","向原团队其他成员排队一条消息；不授执行权限，不保证对方已消费，不自动唤醒。",props("targetMemberId",text(64),"text",text(2000)),"targetMemberId","text"))
                .put(tool("team_write_file","写入本成员隔离草稿的明确文件，需原prepared操作、主人批准及刚读取的expectedVersion；不写原项目。",props("path",text(240),"content",text(10000),"expectedVersion",integer(0,Integer.MAX_VALUE)),"path","content","expectedVersion"))
                .put(tool("team_delete_file","删除本成员所选隔离草稿路径，按刚读取的准确版本及SHA CAS；不删除原项目。重命名需分别批准新旧两路径。",props("path",text(240),"expectedVersion",integer(1,Integer.MAX_VALUE),"expectedHash",text(64)),"path","expectedVersion","expectedHash"))
                .put(tool("team_request_input","在原assignment上向主人提问并等待回答；回答不是文件写入批准。",props("question",text(2000)),"question"))
                .put(tool("team_freeze_outputs","冻结本成员真实草稿文件为可审查成果，保留原项目基线与实际diff；不会自动整合。",props()));
    }
    @Override public boolean supports(String name){return name!=null&&name.matches("team_(prepare_draft|list_files|read_file|read_dependency|send_message|write_file|delete_file|request_input|freeze_outputs)");}
    @Override public boolean sideEffecting(String name){return supports(name)&&!name.matches("team_(list_files|read_file|read_dependency)");}
    @Override public void validate(String name,JSONObject args)throws Exception {normalize(name,args);}
    private JSONObject normalize(String name,JSONObject input)throws Exception {
        if(!supports(name))throw new SecurityException("成员没有此工具");JSONObject args=JsonFiles.copy(input==null?new JSONObject():input);Set<String> actual=new HashSet<>();args.keys().forEachRemaining(actual::add);
        Set<String> allowed=name.equals("team_read_file")?Set.of("path","offset","length"):name.equals("team_read_dependency")?Set.of("artifactId","offset","length"):name.equals("team_send_message")?Set.of("targetMemberId","text"):name.equals("team_write_file")?Set.of("path","content","expectedVersion"):name.equals("team_delete_file")?Set.of("path","expectedVersion","expectedHash"):name.equals("team_request_input")?Set.of("question"):Set.of();
        if(!allowed.containsAll(actual))throw new SecurityException("成员参数不能改变宿主任务、项目或assignment身份");
        if(name.equals("team_read_file")||name.equals("team_write_file")||name.equals("team_delete_file")){String path=VersionedWorkspace.path(args.getString("path"));if(!paths.contains(path))throw new SecurityException("文件不属于这名成员的原分工");}
        if(name.equals("team_write_file")){String value=args.getString("content");if(value.length()>10000)throw new IllegalArgumentException("单次成员写入最多10000字，以保留原prepared操作的完整精确参数");number(args,"expectedVersion",0,Integer.MAX_VALUE);}
        if(name.equals("team_delete_file")){number(args,"expectedVersion",1,Integer.MAX_VALUE);if(!args.getString("expectedHash").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("删除需要原准确文件SHA");if(!workspace.byteFilesSupported())throw new SecurityException("原工作区尚无原生删除CAS与tombstone成果能力，未用空文件替代");}
        if(name.equals("team_read_file")||name.equals("team_read_dependency")){if(args.has("offset"))number(args,"offset",0,2*1024*1024);if(args.has("length"))number(args,"length",1,12000);}
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
        if(name.equals("team_delete_file"))return delete(operationId,args,draft);
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
        if(workspace.byteFilesSupported())return prepareBytes(operation,args);
        JSONArray sources=new JSONArray(),manifest=new JSONArray();
        for(int i=0;i<files.length();i++){JSONObject base=files.getJSONObject(i),source=snapshot(workspace,binding.getString("targetProjectId"),base.getString("path"));String hash=source.optBoolean("exists")?hash(source.getString("content")):"absent";int version=source.getInt("version");
            if(version!=base.getInt("baseVersion")||!hash.equals(base.getString("baseHash")))throw new SecurityException("主人选择后的原文件已变化，请重新核对团队分工");
            JSONObject selected=new JSONObject(source.toString()).put("path",base.getString("path"));JSONArray seeds=binding.optJSONArray("seedArtifacts");for(int s=0;seeds!=null&&s<seeds.length();s++){JSONObject seed=seeds.getJSONObject(s);if(!base.getString("path").equals(seed.getString("path")))continue;for(String key:new String[]{"teamRunId","assignmentId","memberId","targetProjectId"})if(!binding.getString(key).equals(seed.getString(key)))throw new SecurityException("新修订草稿只能引用原assignment的真实冻结成果");if(seed.optInt("assignmentRevision",1)>=binding.getInt("assignmentRevision"))throw new SecurityException("新草稿不能使用本修订或未来成果");selected.put("content",TeamWorkReview.artifact(workspace,seed).getString("content")).put("exists",true);}
            JSONObject handoff=binding.optJSONObject("handoff");if(handoff!=null&&handoff.has("draftProjectId")){JSONArray preserved=handoff.getJSONArray("files");JSONObject expected=find(preserved,base.getString("path"));if(expected==null)throw new SecurityException("原接手范围缺少准确草稿路径");JSONObject prior=snapshot(workspace,handoff.getString("draftProjectId"),base.getString("path"));if(prior.getInt("version")!=expected.getInt("version")||prior.getBoolean("exists")!=expected.getBoolean("exists")||!expected.getString("sha256").equals(prior.getBoolean("exists")?hash(prior.getString("content")):"absent"))throw new SecurityException("原失败草稿已离开主人批准的准确版本；不猜旧内容");selected.put("content",prior.getString("content")).put("exists",prior.getBoolean("exists"));}
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
    private JSONObject prepareBytes(String operation,JSONObject args)throws Exception {
        java.util.Map<String,byte[]> baseline=new java.util.LinkedHashMap<>(),desired=new java.util.LinkedHashMap<>();JSONArray manifest=new JSONArray();
        for(int i=0;i<files.length();i++){
            JSONObject base=files.getJSONObject(i);String path=base.getString("path");VersionedWorkspace.FileSnapshot source=workspace.nativeFile(binding.getString("targetProjectId"),path,0);
            if(source.version!=base.getInt("baseVersion")||!source.sha256.equals(base.getString("baseHash")))throw new SecurityException("主人选择后的原文件已变化，请重新核对团队分工");
            byte[] originalBytes=source.tombstone?null:source.bytes();baseline.put(path,originalBytes);byte[] chosen=originalBytes;
            JSONArray seeds=binding.optJSONArray("seedArtifacts");for(int s=0;seeds!=null&&s<seeds.length();s++){JSONObject seed=seeds.getJSONObject(s);if(!path.equals(seed.getString("path")))continue;for(String key:new String[]{"teamRunId","assignmentId","memberId","targetProjectId"})if(!binding.getString(key).equals(seed.getString(key)))throw new SecurityException("seed不属于原assignment");if(seed.optInt("assignmentRevision",1)>=binding.getInt("assignmentRevision"))throw new SecurityException("seed并非过去修订的原成果");WorkspaceArtifacts.ArtifactRef exact=TeamWorkReview.artifactRef(workspace,seed);chosen=exact.tombstone?null:workspace.artifactBytes(exact);}
            JSONObject handoff=binding.optJSONObject("handoff");if(handoff!=null&&handoff.has("draftProjectId")){JSONObject expected=find(handoff.getJSONArray("files"),path);VersionedWorkspace.FileSnapshot prior=workspace.nativeFile(handoff.getString("draftProjectId"),path,0);if(expected==null||prior.version!=expected.getInt("version")||(!prior.tombstone)!=expected.getBoolean("exists")||!prior.sha256.equals(expected.getString("sha256")))throw new SecurityException("原失败草稿已离开批准的准确字节版本");chosen=prior.tombstone?null:prior.bytes();}
            desired.put(path,chosen);manifest.put(JsonFiles.copy(base));
        }
        long pause=original.pauseRevision();JSONObject admitted=claim(operation,"team_prepare_draft",args,new JSONObject().put("files",manifest).put("seedArtifacts",binding.optJSONArray("seedArtifacts")==null?new JSONArray():binding.getJSONArray("seedArtifacts")));if(!admitted.optBoolean("dispatch"))return admitted;
        boolean entered=false;try{
            check("team_prepare_draft",original);entered=true;String projectId=uuid(effect("create",new JSONObject().put("name","团队草稿 · "+binding.getString("memberId")),pause).getString("id"));
            for(String path:desired.keySet()){
                byte[] bytes=desired.get(path),before=baseline.get(path);VersionedWorkspace.FileSnapshot expected=workspace.nativeFile(projectId,path,0);
                if(bytes==null&&before==null)continue;
                check("team_prepare_draft",original);byte[] copy=bytes==null?before:bytes;VersionedWorkspace.FileSnapshot[] written={null};original.dispatchAtPauseRevision(pause,()->written[0]=workspace.writeBytes(expected,copy));
                if(bytes==null){VersionedWorkspace.FileSnapshot actual=written[0];original.dispatchAtPauseRevision(pause,()->workspace.delete(actual));}
            }
            JSONObject value=new JSONObject().put("assignmentId",binding.getString("assignmentId")).put("assignmentRevision",binding.getInt("assignmentRevision")).put("memberId",binding.getString("memberId")).put("targetProjectId",binding.getString("targetProjectId")).put("draftProjectId",projectId).put("files",manifest);
            if(project!=null)project.prepared(operation,value);return new JSONObject().put("ok",true).put("status","completed").put("teamDraft",value).put("message","所选真实字节已复制到独立草稿；原项目未改，未把二进制转成文字");
        }catch(Exception failure){if(entered)return unknown(operation,"原字节草稿准备没有完整回执，只核对原操作，不重新创建");throw failure;}
    }
    private JSONObject delete(String operation,JSONObject args,JSONObject draft)throws Exception {
        String projectId=draft.getString("draftProjectId");VersionedWorkspace.FileSnapshot expected=workspace.nativeFile(projectId,args.getString("path"),0);
        if(expected.tombstone||expected.version!=args.getInt("expectedVersion")||!expected.sha256.equals(args.getString("expectedHash")))throw new SecurityException("原草稿版本或SHA已变化，未删除");
        long pause=original.pauseRevision();JSONObject admitted=claim(operation,"team_delete_file",args,new JSONObject().put("draftProjectId",projectId).put("previousVersion",expected.version).put("previousHash",expected.sha256).put("desiredHash","absent"));if(!admitted.optBoolean("dispatch"))return admitted;
        check("team_delete_file",original);try{VersionedWorkspace.FileSnapshot[] deleted={null};original.dispatchAtPauseRevision(pause,()->deleted[0]=workspace.delete(expected));VersionedWorkspace.FileSnapshot actual=workspace.nativeFile(projectId,expected.path,deleted[0].version);if(!actual.tombstone||actual.version<=expected.version||!actual.revisionId.equals(deleted[0].revisionId))throw new IllegalStateException("原删除回读不一致");return new JSONObject().put("ok",true).put("status","completed").put("assignmentRevision",binding.getInt("assignmentRevision")).put("draftDelete",actual.json()).put("originalProjectModified",false);}
        catch(Exception uncertain){return unknown(operation,"原草稿删除没有确定回执；原prepared保留，只核对，不重放");}
    }
    private JSONObject freezeBytes(String operation,JSONObject args,JSONObject draft)throws Exception {
        TeamProjectWorkspaces.FreezeCapture capture;try{capture=project==null?null:project.capture(operation,draft);}catch(dev.pocketengine.store.TaskStore.TeamProjectDeliveryIncomplete missing){return new JSONObject().put("ok",false).put("accepted",false).put("status","not_dispatched").put("code","team_project_checks_required").put("requiresPause",true).put("message",missing.getMessage());}
        String projectId=draft.getString("draftProjectId");JSONArray selected=new JSONArray();java.util.Map<String,VersionedWorkspace.FileSnapshot> prior=new java.util.LinkedHashMap<>();java.util.Map<String,byte[]> desired=new java.util.LinkedHashMap<>();
        JSONArray captured=capture==null?null:capture.files();
        for(int i=0;i<files.length();i++){
            JSONObject base=files.getJSONObject(i);String path=base.getString("path");VersionedWorkspace.FileSnapshot head=workspace.nativeFile(projectId,path,0);JSONObject output=captured==null?null:find(captured,path);if(captured!=null&&output==null)continue;
            String sha=output==null?head.sha256:output.getString("sha256");if(sha.equals(base.getString("baseHash")))continue;boolean deletion="absent".equals(sha);
            if(deletion&&head.version==0)throw new SecurityException("真实删除需要原草稿版本，未把从未存在的路径伪造成删除成果");
            byte[] bytes=deletion?null:capture==null?head.bytes():capture.bytes(path);if(bytes!=null&&!hash(bytes).equals(sha))throw new SecurityException("实际输出字节与原捕获摘要不一致");
            selected.put(JsonFiles.copy(base).put("draftVersion",head.version).put("draftRevisionId",head.revisionId).put("sha256",sha).put("bytes",bytes==null?0:bytes.length).put("deletion",deletion));prior.put(path,head);desired.put(path,bytes);
        }
        if(selected.length()==0)return new JSONObject().put("ok",false).put("accepted",false).put("status","not_dispatched").put("code","team_project_no_change").put("requiresPause",true).put("message","没有所选路径的真实变化，未生成伪成果");
        long pause=original.pauseRevision();JSONObject capability=new JSONObject().put("draftProjectId",projectId).put("files",selected);if(capture!=null)capability.put("projectCaptureHash",TaskStoreFingerprint(capture.view()));JSONObject admitted=claim(operation,"team_freeze_outputs",args,capability);if(!admitted.optBoolean("dispatch"))return admitted;
        JSONArray artifacts=new JSONArray();try{
            for(int i=0;i<selected.length();i++){
                JSONObject file=selected.getJSONObject(i);String path=file.getString("path");VersionedWorkspace.FileSnapshot expected=prior.get(path);byte[] bytes=desired.get(path);check("team_freeze_outputs",original);if(capture!=null)project.current(capture);
                VersionedWorkspace.FileSnapshot[] actual={expected};if(capture!=null&&!expected.sha256.equals(file.getString("sha256"))){original.dispatchAtPauseRevision(pause,()->actual[0]=bytes==null?workspace.delete(expected):workspace.writeBytes(expected,bytes));}
                VersionedWorkspace.FileSnapshot reread=workspace.nativeFile(projectId,path,actual[0].version);if(!reread.revisionId.equals(actual[0].revisionId)||!reread.sha256.equals(file.getString("sha256")))throw new SecurityException("原草稿CAS与实际冻结字节不一致");
                WorkspaceArtifacts.ArtifactRef[] frozen={null};original.dispatchAtPauseRevision(pause,()->frozen[0]=workspace.freeze(reread,operation,binding.getString("taskId"),binding.getString("runId")));WorkspaceArtifacts.ArtifactRef exact=frozen[0];if(!exact.sha256.equals(reread.sha256)||exact.version!=reread.version||!exact.revisionId.equals(reread.revisionId)||exact.tombstone!=reread.tombstone)throw new SecurityException("原catalog冻结不是本次准确字节/删除版本");
                JSONObject ref=JsonFiles.copy(file).put("artifactId",exact.artifactId).put("artifactKind",exact.kind).put("mimeType",exact.mimeType).put("bytes",exact.sizeBytes).put("draftVersion",exact.version).put("draftRevisionId",exact.revisionId).put("draftProjectId",projectId).put("targetProjectId",binding.getString("targetProjectId")).put("teamRunId",binding.getString("teamRunId")).put("assignmentId",binding.getString("assignmentId")).put("assignmentRevision",binding.getInt("assignmentRevision")).put("memberId",binding.getString("memberId")).put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId"));if(capture!=null)ref.put("projectEvidence",capture.evidence());artifacts.put(ref);
            }
            if(capture!=null)project.frozen(operation,capture,artifacts);return new JSONObject().put("ok",true).put("status","completed").put("assignmentRevision",binding.getInt("assignmentRevision")).put("teamArtifacts",artifacts).put("requiresOwnerReview",true).put("businessVerified",false).put("message","真实字节/删除修订已冻结，仍须独审、主人批准和唯一CAS整合");
        }catch(Exception uncertain){return unknown(operation,"原字节成果冻结没有完整回执，只核对原操作，不生成替代成果");}
    }
    private JSONObject listing()throws Exception {JSONArray refs=binding.optJSONArray("dependencyArtifacts"),dependencies=new JSONArray();for(int i=0;refs!=null&&i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);dependencies.put(new JSONObject().put("artifactId",ref.getString("artifactId")).put("path",ref.getString("path")).put("memberId",ref.getString("memberId")).put("sha256",ref.getString("sha256")));}JSONObject value=new JSONObject().put("files",new JSONArray(files.toString())).put("assignmentId",binding.getString("assignmentId")).put("assignmentRevision",binding.getInt("assignmentRevision")).put("dependencyArtifacts",dependencies);JSONObject draft=draft();return value.put("draftReady",draft!=null).put("draftProjectId",draft==null?JSONObject.NULL:draft.getString("draftProjectId")).put("untrusted",true);}
    private JSONObject dependency(String id)throws Exception {JSONArray refs=binding.optJSONArray("dependencyArtifacts");for(int i=0;refs!=null&&i<refs.length();i++)if(id.equals(refs.getJSONObject(i).getString("artifactId")))return refs.getJSONObject(i);throw new SecurityException("成果不属于原宿主核对的前置分工");}
    private JSONObject readDependency(JSONObject args)throws Exception {
        JSONObject ref=dependency(args.getString("artifactId")),artifact=TeamWorkReview.artifact(workspace,ref);JSONObject result;
        if(TeamWorkReview.isDeletion(ref))result=deletedView(ref.getString("path"),ref.getInt("draftVersion"));
        else result=page(ref.getString("path"),artifact.optString("kind","text_file"),artifact.optString("content",null),workspace.byteFilesSupported()?TeamWorkReview.artifactBytes(workspace,ref):artifact.getString("content").getBytes(StandardCharsets.UTF_8),args.optInt("offset",0),args.optInt("length",12000));
        return result.put("artifactId",ref.getString("artifactId")).put("assignmentId",ref.getString("assignmentId")).put("memberId",ref.getString("memberId")).put("sha256",ref.getString("sha256")).put("untrusted",true);
    }
    private JSONObject read(JSONObject args)throws Exception {
        JSONObject draft=draft();if(draft==null)throw new IllegalStateException("原assignment隔离草稿尚未准备");String projectId=draft.getString("draftProjectId"),path=args.getString("path");
        if(workspace.byteFilesSupported()){VersionedWorkspace.FileSnapshot exact=workspace.nativeFile(projectId,path,0);JSONObject snapshot=snapshot(exact),result=exact.tombstone?deletedView(path,exact.version):page(path,exact.kind,snapshot.optString("content",null),exact.bytes(),args.optInt("offset",0),args.optInt("length",12000));return result.put("version",exact.version).put("revisionId",exact.revisionId).put("sha256",exact.sha256).put("untrusted",true);}
        JSONObject source=readOrAbsent(projectId,path);String text=source.optString("content");return page(path,"text",text,text.getBytes(StandardCharsets.UTF_8),args.optInt("offset",0),args.optInt("length",12000)).put("version",source.getInt("version")).put("sha256",source.getInt("version")==0?"absent":hash(text)).put("untrusted",true);
    }
    static JSONObject deletedView(String path,int version)throws Exception{return new JSONObject().put("path",path).put("version",version).put("tombstone",true).put("bodyAvailable",false).put("exists",false).put("totalBytes",0).put("complete",true).put("executionAuthorized",false);}
    static JSONObject page(String path,String kind,String text,byte[] bytes,int start,int length)throws Exception {
        JSONObject result=new JSONObject().put("path",path).put("tombstone",false).put("bodyAvailable",true).put("kind",kind).put("totalBytes",bytes.length).put("executionAuthorized",false);
        if(text==null){if(start>bytes.length)throw new IllegalArgumentException("原字节读取位置越界");int end=Math.min(bytes.length,start+Math.min(length,8192));return result.put("contentBase64",java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(bytes,start,end))).put("unit","byte").put("offset",start).put("nextOffset",end).put("complete",end==bytes.length).put("rangeSha256",hash(java.util.Arrays.copyOfRange(bytes,start,end)));}
        if(start>text.length()||start>0&&start<text.length()&&Character.isLowSurrogate(text.charAt(start)))throw new IllegalArgumentException("原文字分页起点越界或拆开字符");int end=Math.min(text.length(),start+length);if(end<text.length()&&end>start&&Character.isHighSurrogate(text.charAt(end-1)))end--;if(end==start&&start<text.length())throw new IllegalArgumentException("分页长度不足以容纳完整Unicode字符，请增加length");return result.put("content",text.substring(start,end)).put("unit","UTF-16").put("offset",start).put("nextOffset",end).put("totalCharacters",text.length()).put("totalChars",text.length()).put("complete",end==text.length());
    }
    private JSONObject write(String operation,JSONObject args,JSONObject draft)throws Exception {
        if(workspace.byteFilesSupported())return writeBytes(operation,args,draft);
        String project=draft.getString("draftProjectId");JSONObject current=readOrAbsent(project,args.getString("path"));if(current.getInt("version")!=args.getInt("expectedVersion"))throw new IllegalStateException("草稿版本已变化，请重新读取实际文件");
        long pauseRevision=original.pauseRevision();JSONObject admitted=claim(operation,"team_write_file",args,new JSONObject().put("draftProjectId",project).put("previousVersion",current.getInt("version")).put("previousHash",current.getInt("version")==0?"absent":hash(current.getString("content"))).put("desiredHash",hash(args.getString("content"))));if(!admitted.optBoolean("dispatch"))return admitted;
        check("team_write_file",original);try {
            JSONObject actual=effect("write",new JSONObject(args.toString()).put("projectId",project),pauseRevision);JSONObject reread=workspace.call("read",new JSONObject().put("projectId",project).put("path",args.getString("path")).put("version",actual.getInt("version")));
            if(!args.getString("content").equals(reread.getString("content"))||!actual.getString("revisionId").equals(reread.getString("revisionId")))return unknown(operation,"实际草稿写入与回读不一致，请核对原操作");
            actual.remove("content");return new JSONObject().put("ok",true).put("status","completed").put("assignmentRevision",binding.getInt("assignmentRevision")).put("draftWrite",actual.put("projectId",project).put("assignmentId",binding.getString("assignmentId")).put("sha256",hash(reread.getString("content")))).put("message","本成员隔离草稿已实际保存；原项目未变更");
        }catch(Exception uncertain){return unknown(operation,"草稿保存没有确定回执；保留原操作，只核对、不重放");}
    }
    private JSONObject writeBytes(String operation,JSONObject args,JSONObject draft)throws Exception {
        VersionedWorkspace.FileSnapshot expected=workspace.nativeFile(draft.getString("draftProjectId"),args.getString("path"),0);if(expected.version!=args.getInt("expectedVersion"))throw new SecurityException("草稿版本已变，请重新读取实际文件");byte[] bytes=utf8(args.getString("content"));long pause=original.pauseRevision();JSONObject admitted=claim(operation,"team_write_file",args,new JSONObject().put("draftProjectId",expected.projectId).put("previousVersion",expected.version).put("previousHash",expected.sha256).put("desiredHash",hash(bytes)));if(!admitted.optBoolean("dispatch"))return admitted;
        check("team_write_file",original);try{VersionedWorkspace.FileSnapshot[] saved={null};original.dispatchAtPauseRevision(pause,()->saved[0]=workspace.writeBytes(expected,bytes));VersionedWorkspace.FileSnapshot actual=workspace.nativeFile(expected.projectId,expected.path,saved[0].version);if(actual.tombstone||!actual.sha256.equals(hash(bytes))||!actual.revisionId.equals(saved[0].revisionId))throw new SecurityException("原草稿CAS回读不符");return new JSONObject().put("ok",true).put("status","completed").put("assignmentRevision",binding.getInt("assignmentRevision")).put("draftWrite",actual.json().put("assignmentId",binding.getString("assignmentId"))).put("originalProjectModified",false);}
        catch(Exception uncertain){return unknown(operation,"草稿保存没有确定回执；原版本/操作保留，只核对不重放");}
    }
    private JSONObject freeze(String operation,JSONObject args,JSONObject draft)throws Exception {
        if(workspace.byteFilesSupported())return freezeBytes(operation,args,draft);
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
        for(int i=0;i<selected.length();i++){String path=VersionedWorkspace.path(selected.getString(i));if(!unique.add(path))throw new IllegalArgumentException("成员文件不能重复");JSONObject file=snapshot(workspace,projectId,path);result.put(new JSONObject().put("path",path).put("baseVersion",file.getInt("version")).put("baseHash",snapshotHash(file)));}return result;
    }
    static JSONObject snapshot(Workspace workspace,String project,String path)throws Exception {
        if(workspace.byteFilesSupported())return snapshot(workspace.nativeFile(project,path,0));
        JSONArray listed=workspace.call("list",new JSONObject().put("projectId",project)).getJSONArray("files");if(find(listed,path)!=null)return workspace.call("read",new JSONObject().put("projectId",project).put("path",path)).put("exists",true);JSONArray history=workspace.call("history",new JSONObject().put("projectId",project).put("path",path)).getJSONArray("history");return new JSONObject().put("version",history.length()==0?0:history.getJSONObject(history.length()-1).getInt("version")).put("content","").put("exists",false);
    }
    static JSONObject snapshot(VersionedWorkspace.FileSnapshot nativeFile)throws Exception {
        JSONObject value=nativeFile.json().put("exists",!nativeFile.tombstone).put("nativeByteSnapshot",true).put("contentAvailable",false);
        if(nativeFile.tombstone)return value.put("content","");
        if("text".equals(nativeFile.kind))try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(nativeFile.bytes())).toString();if(text.length()<=65536)value.put("content",text).put("contentAvailable",true);}catch(java.nio.charset.CharacterCodingException invalid){throw new SecurityException("原工作区文字类型与真实字节编码不一致",invalid);}
        return value;
    }
    static String snapshotHash(JSONObject value)throws Exception{return value.optBoolean("nativeByteSnapshot")?value.getString("sha256"):value.optBoolean("exists")?hash(value.getString("content")):"absent";}
    public static String hash(String text)throws Exception{return hash(utf8(text));}
    private static byte[] utf8(String text)throws Exception{java.nio.ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;}
    public static String hash(byte[] bytes)throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
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
