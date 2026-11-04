package dev.pocketengine.store;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed native database maintenance entry. Atomic repair records never carry business authority. */
public final class TaskDatabaseMaintenance {
    public interface Host {
        long generation();
        /** Closes admission and uses original resource stops. False means no byte replacement. */
        boolean quiesce()throws Exception;
    }
    private static final String FORMAT="pocketworld.database-maintenance.v1";
    private static final Set<String> INCOMPLETE=Set.of("preserving","reviewing","switching","verifying","original_retry_prepared","unknown");
    private static final ThreadLocal<OriginalRetry> ORIGINAL_RETRY=new ThreadLocal<>();
    private final Context context;
    private final Host host;
    private final File database,root;
    private final AtomicFile state;
    private final String process=UUID.randomUUID().toString();
    private JSONObject record;
    private volatile String visible="null";
    private String failureCode="unknown",failureMessage="";
    private Integer observedSchema;
    private volatile Review review;
    private OriginalRetry preparedOriginalRetry;
    private static final class OriginalRetry {
        final TaskDatabaseMaintenance coordinator;
        final String repair,source,backup,databasePath,recordPath;
        final long previousGeneration,deadlineNanos=System.nanoTime()+300_000_000_000L;
        final BooleanSupplier owner;
        long generation;
        boolean started,opened,completed;
        OriginalRetry(TaskDatabaseMaintenance coordinator,long generation,BooleanSupplier owner)throws Exception{this.coordinator=coordinator;this.previousGeneration=generation;this.owner=owner;repair=coordinator.record.getString("repairId");source=coordinator.record.getString("sourceFingerprint");backup=coordinator.record.getString("backupFingerprint");databasePath=coordinator.database.getCanonicalPath();recordPath=coordinator.state.getBaseFile().getCanonicalPath();}
        boolean current(){return !Thread.currentThread().isInterrupted()&&started&&coordinator.host.generation()==generation&&System.nanoTime()-deadlineNanos<0&&owner.getAsBoolean();}
    }
    private static final class Review {
        final String id,repair,source,backup,mode,staging,extraction;
        final long generation,expires,deadlineNanos;
        final BooleanSupplier owner;
        Review(String id,JSONObject record,long generation,BooleanSupplier owner)throws Exception{this.id=id;repair=record.getString("repairId");source=record.getString("sourceFingerprint");backup=record.getString("backupFingerprint");mode=record.getString("mode");staging=record.getString("stagingFingerprint");extraction=record.optJSONObject("restoration")==null?"":record.getJSONObject("restoration").getString("manifestSha256");this.generation=generation;this.owner=owner;expires=System.currentTimeMillis()+300000;deadlineNanos=System.nanoTime()+300_000_000_000L;}
        boolean current(long generation){return this.generation==generation&&System.nanoTime()-deadlineNanos<0&&owner.getAsBoolean();}
    }
    public TaskDatabaseMaintenance(Context context,Host host)throws Exception {
        this.context=context.getApplicationContext();this.host=host;database=nativeDatabase(context);root=new File(context.getNoBackupFilesDir().getCanonicalFile(),"database-maintenance");state=new AtomicFile(new File(root,"current.json"));
        record=readRecord(state);if(record!=null){failureCode=record.optString("cause","unknown");if(record.has("observedSchema")&&!record.isNull("observedSchema"))observedSchema=record.getInt("observedSchema");}refresh();
    }
    /** Runs before every original TaskStore open, including cold startup. Never repairs by opening. */
    public static void requireBusinessOpen(Context context) {
        try{File current=new File(context.getNoBackupFilesDir().getCanonicalFile(),"database-maintenance/current.json");JSONObject record=readRecord(new AtomicFile(current));
            if(record!=null&&!Set.of("completed","original_retry_completed").contains(record.getString("phase"))){
                File database=nativeDatabase(context);OriginalRetry permit=ORIGINAL_RETRY.get();
                if(!originalPermitMatches(permit,record,database,current)||permit.opened)throw new SecurityException("database_maintenance_record_requires_owner_read");
                DatabaseFilePreservation.Snapshot expected=DatabaseFilePreservation.fromManifest(TaskStore.DATABASE_NAME,record.getJSONArray("files"),permit.source);
                DatabaseFilePreservation.verify(database,expected);DatabaseFilePreservation.verify(new File(new File(new File(current.getParentFile(),permit.repair),"preserved"),TaskStore.DATABASE_NAME),expected);
                DatabaseFilePreservation.requireCurrent(permit::current);permit.opened=true;
            }
        }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("database_maintenance_record_unreadable",failure);}
    }
    private static File nativeDatabase(Context context)throws Exception{File supplied=context.getDatabasePath(TaskStore.DATABASE_NAME).getAbsoluteFile();return new File(supplied.getParentFile().getCanonicalFile(),supplied.getName());}
    public boolean blocksBusinessOpen(){JSONObject shown=snapshot();if(shown==null||Set.of("completed","original_retry_completed","not_started").contains(shown.optString("phase")))return false;try{return !originalPermitMatches(ORIGINAL_RETRY.get(),shown,database,state.getBaseFile());}catch(Exception unavailable){return true;}}
    private static boolean originalPermitMatches(OriginalRetry permit,JSONObject record,File database,File state)throws Exception{return permit!=null&&permit.current()&&"original_retry_prepared".equals(record.getString("phase"))&&permit.databasePath.equals(database.getCanonicalPath())&&permit.recordPath.equals(state.getCanonicalPath())&&permit.repair.equals(record.getString("repairId"))&&permit.source.equals(record.getString("sourceFingerprint"))&&permit.backup.equals(record.getString("backupFingerprint"));}
    public JSONObject snapshot(){try{if("null".equals(visible))return null;JSONObject shown=new JSONObject(visible);Review exact=review;if(exact==null||!exact.current(host.generation())){shown.put("review",JSONObject.NULL);JSONArray commands=shown.optJSONArray("allowedCommands"),filtered=new JSONArray();for(int i=0;commands!=null&&i<commands.length();i++)if(!"repair.confirm".equals(commands.getString(i)))filtered.put(commands.getString(i));shown.put("allowedCommands",filtered);}return shown;}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    /** The corruption callback never returns to Android. This best-effort record is not a backup claim. */
    public synchronized void corruptionObserved() {
        try{failureCode="suspected_corruption";failureMessage="原数据库疑似损坏；默认删除与自动重开已阻止";if(record==null||Set.of("completed","original_retry_completed").contains(record.optString("phase")))newRepair();save();}
        catch(Exception unavailable){throw new IllegalStateException("corruption_record_unconfirmed",unavailable);}
    }
    public synchronized void failure(String code,String message,Integer observed)throws Exception {
        failureCode=code;failureMessage=message;observedSchema=observed;
        if("suspected_corruption".equals(code)&&(record==null||Set.of("completed","original_retry_completed").contains(record.optString("phase")))){newRepair();save();}else refresh();
    }
    /** A repeated bootstrap request only observes the recorded attempt; it never opens again. */
    public synchronized boolean originalRetryRecorded(JSONObject input)throws Exception {
        if(record==null)return false;String request=input.getString("requestId");dev.pocketengine.bootstrap.BootstrapStatus.request(request);
        return duplicate("bootstrap.retry",request,input);
    }
    /** Owner-selected reopening of the same preserved, non-corrupt original. No database replacement. */
    public synchronized String prepareOriginalRetry(JSONObject input,BooleanSupplier owner)throws Exception {
        long generation=exactGeneration(input);DatabaseFilePreservation.requireCurrent(owner);if(generation!=host.generation())throw new SecurityException("原启动代次已变化");
        requireRecord(input);requireFingerprints(input);if(!originalRetryAvailable(record))throw new SecurityException("原数据库当前不能重新打开；只读取同一维护记录");
        String request=input.getString("requestId");dev.pocketengine.bootstrap.BootstrapStatus.request(request);if(duplicate("bootstrap.retry",request,input))throw new SecurityException("原重新打开请求已记录，不能重放");
        verifyOriginalAndBackup();DatabaseFilePreservation.requireCurrent(owner);
        OriginalRetry prepared=new OriginalRetry(this,generation,owner);remember("bootstrap.retry",request,input);review=null;record.remove("review");
        record.put("phase","original_retry_prepared").put("originalRetryRequestId",request).put("originalRetryPreviousGeneration",generation);save();preparedOriginalRetry=prepared;return prepared.repair;
    }
    /** Runs only on the existing bootstrap worker. The permit cannot survive a process or thread change. */
    public void runPreparedOriginalRetry(String repairId,long bootstrapGeneration,BooleanSupplier owner,Runnable initialize)throws Exception {
        OriginalRetry prepared;
        synchronized(this){
            prepared=preparedOriginalRetry;
            if(prepared==null||prepared.started||prepared.owner!=owner||!prepared.repair.equals(repairId)||bootstrapGeneration!=Math.addExact(prepared.previousGeneration,1)||bootstrapGeneration!=host.generation()||ORIGINAL_RETRY.get()!=null)throw new SecurityException("原数据库重新打开许可不是当前一次原请求");
            DatabaseFilePreservation.requireCurrent(owner);prepared.started=true;prepared.generation=bootstrapGeneration;
        }
        try{
            DatabaseFilePreservation.requireCurrent(prepared::current);synchronized(this){verifyOriginalAndBackup();}
            ORIGINAL_RETRY.set(prepared);initialize.run();
            if(!prepared.completed)throw new IllegalStateException("original_retry_unconfirmed");
        }catch(Exception|LinkageError failure){
            if(!prepared.completed)synchronized(this){review=null;record.put("interruptedPhase",record.optString("phase")).put("phase","unknown").put("reasonCode","original_retry_unconfirmed");try{save();}catch(Exception uncertain){failure.addSuppressed(uncertain);}}
            throw failure;
        }finally{ORIGINAL_RETRY.remove();}
    }
    /** Original initializer calls this after its normal readback, before it publishes ready. */
    public synchronized void completeOriginalRetryIfPrepared()throws Exception {
        OriginalRetry prepared=ORIGINAL_RETRY.get();if(prepared==null||prepared.completed)return;
        if(!originalPermitMatches(prepared,record,database,state.getBaseFile())||!prepared.opened)throw new SecurityException("原数据库打开与当前维护许可不能核对");
        DatabaseFilePreservation.verify(new File(new File(repairDirectory(),"preserved"),TaskStore.DATABASE_NAME),manifest());DatabaseFilePreservation.requireCurrent(prepared::current);
        record.put("phase","original_retry_completed").put("completedAt",System.currentTimeMillis()).put("result",new JSONObject().put("mode","original_database").put("summary","已核对并重新打开同一原数据库；原保全副本仍保留，没有替换数据库或导入旧执行授权").put("replacedDatabase",false).put("restoredHistory",false).put("restoredExecutionAuthority",false));
        record.remove("reasonCode");save();prepared.completed=true;
    }
    private static boolean originalRetryAvailable(JSONObject value){return value!=null&&"preserved".equals(value.optString("phase"))&&!"suspected_corruption".equals(value.optString("cause"))&&Boolean.TRUE.equals(value.opt("preservationVerified"));}
    private void newRepair()throws Exception {
        if(record!=null)archiveRecord();
        record=new JSONObject().put("format",FORMAT).put("repairId",UUID.randomUUID().toString()).put("phase","detected").put("cause",failureCode)
            .put("observedSchema",observedSchema==null?JSONObject.NULL:observedSchema).put("sourceFingerprint","").put("backupFingerprint","")
            .put("preservationVerified",false).put("files",new JSONArray()).put("createdAt",System.currentTimeMillis()).put("requests",new JSONObject());review=null;
    }
    public JSONObject command(String command,JSONObject input,BooleanSupplier owner)throws Exception {
        if("repair.read".equals(command)){
            JSONObject shown;String basis;synchronized(this){basis=visible;shown=snapshot();}
            if(shown==null||shown.isNull("repairId")){if(!input.optString("repairId").isEmpty())throw new SecurityException("原维护编号未找到；不能用空状态代替未知结果");return shown;}
            if(!shown.getString("repairId").equals(input.getString("repairId")))throw new SecurityException("查询编号不是当前原维护记录；未用另一记录替代未知结果");
            JSONObject observed=readPhysicalState(shown);
            synchronized(this){if(visible.equals(basis))visible=observed.toString();return snapshot();}
        }
        synchronized(this){
            long generation=exactGeneration(input);DatabaseFilePreservation.requireCurrent(owner);if(generation!=host.generation())throw new SecurityException("原启动代次已变化");
            String request=input.getString("requestId");dev.pocketengine.bootstrap.BootstrapStatus.request(request);
            if(!Set.of("repair.preserve","repair.review","repair.confirm","repair.discard").contains(command))throw new SecurityException("不是原数据库维护命令");
            BooleanSupplier current=()->generation==host.generation()&&owner.getAsBoolean();
            if(record!=null&&duplicate(command,request,input))return snapshot();
            if(command.equals("repair.preserve")){preserve(input,current,request);return snapshot();}
            requireRecord(input);String phase=record.getString("phase");if(INCOMPLETE.contains(phase))throw new SecurityException("原维护动作结果未知；只能查询同一记录，不能重放");
            switch(command){
                case "repair.review":prepareReview(input,owner,current,generation,request);break;
                case "repair.confirm":confirm(input,owner,current,generation,request);break;
                case "repair.discard":if(!Set.of("detected","preserved","staged","review_failed","preservation_failed","quiescence_unconfirmed").contains(phase))throw new SecurityException("当前维护记录不能撤销审阅");remember(command,request,input);review=null;record.remove("review");record.put("reviewDiscarded",true);save();break;
                default:throw new AssertionError();
            }
            return snapshot();
        }
    }
    private void preserve(JSONObject input,BooleanSupplier current,String request)throws Exception {
        if(record==null){if(!input.has("repairId")||!input.isNull("repairId"))throw new SecurityException("保全必须核对当前尚未创建的维护身份");newRepair();}
        else requireRecord(input);
        if(!Set.of("detected","quiescence_unconfirmed").contains(record.getString("phase")))throw new SecurityException("原保全动作已经记录；请只读取，不重新复制");
        if("quiescence_unconfirmed".equals(record.optString("phase"))&&process.equals(record.optString("uncertainProcess")))throw new SecurityException("原资源停止未确认；请关闭并重新打开应用后查询此记录");
        remember("repair.preserve",request,input);record.put("phase","quiescence_unconfirmed").put("uncertainProcess",process);save();
        if(!host.quiesce()){record.put("reasonCode","quiescence_unconfirmed");save();return;}
        DatabaseFilePreservation.requireCurrent(current);DatabaseFilePreservation.Snapshot before=DatabaseFilePreservation.inspect(database,current);
        if(before.members().stream().noneMatch(member->member.name().equals(TaskStore.DATABASE_NAME)&&member.exists()))throw new IllegalStateException("原数据库文件不存在，不能将缺失当作损坏重置");
        record.put("sourceFingerprint",before.fingerprint()).put("files",before.files()).put("phase","preserving")
            .put("consistency",new JSONObject().put("quiescence","original_business_handles_closed_before_capture").put("sqliteLogicalIntegrity","not_claimed").put("sourceBefore",before.fingerprint()).put("sourceAfter",JSONObject.NULL).put("preCorruptionCallbackBytes","not_claimed"))
            .put("originalDirectoryReferences",new JSONArray(new String[]{"files/artifacts","files/tasks.json","files/assistant","files/workspace","files/memory"}));save();
        try {
            File directory=repairDirectory(),backup=new File(directory,"preserved");DatabaseFilePreservation.Snapshot copy=DatabaseFilePreservation.preserve(database,backup,before,current);
            JSONObject consistency=record.getJSONObject("consistency");consistency.put("sourceAfter",DatabaseFilePreservation.inspect(database).fingerprint()).put("copiesSha256AndLengthVerified",true);
            JSONArray files=before.files();for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);row.put("verified",true).put("copiedLength",row.getLong("length")).put("copiedSha256",row.getString("sha256"));}
            record.put("files",files).put("backupFingerprint",copy.fingerprint()).put("preservationVerified",true).put("phase","preserved");record.remove("reasonCode");save();
        } catch(Exception failure) {record.put("phase","preservation_failed").put("preservationVerified",false).put("reasonCode",safeFailure(failure));save();}
    }
    private void prepareReview(JSONObject input,BooleanSupplier owner,BooleanSupplier current,long generation,String request)throws Exception {
        requireSuspectedCorruption();requireFingerprints(input);if(!Set.of("preserved","staged","review_failed").contains(record.getString("phase")))throw new SecurityException("保全尚未验证，不开放替换审阅");
        String mode=input.getString("mode");if(!Set.of("empty_database","verified_copy").contains(mode))throw new IllegalArgumentException("未知维护模式，没有自动改为建立空库");
        if(mode.equals("verified_copy")){JSONArray sections=input.getJSONArray("sections");if(!Boolean.TRUE.equals(input.opt("includeConversationContent"))||sections.length()!=1||!"conversations".equals(sections.getString(0)))throw new SecurityException("请明确选择包含原对话文字的本机恢复范围");}
        verifyOriginalAndBackup();DatabaseFilePreservation.requireCurrent(current);
        boolean reuse="staged".equals(record.getString("phase"))&&mode.equals(record.optString("mode"));String reviewId=UUID.randomUUID().toString();remember("repair.review",request,input);review=null;
        try {
            if(!reuse){
                record.put("phase","reviewing").put("mode",mode).put("stagingId",reviewId);record.remove("stagingFingerprint");record.remove("stagingFiles");record.remove("restoration");record.remove("review");save();
                File directory=new File(repairDirectory(),"staging-"+reviewId);createPrivateDirectory(directory);
                File candidate=new File(directory,TaskStore.DATABASE_NAME);TaskStore.createMaintenanceStaging(context,candidate,record.getString("repairId"));
                if(mode.equals("verified_copy"))record.put("restoration",TaskDatabaseRecoveryCopy.restoreConversations(new File(new File(repairDirectory(),"preserved"),TaskStore.DATABASE_NAME),manifest(),new File(repairDirectory(),"read-only-copy-"+reviewId),candidate,record.getString("repairId"),current));
            }
            File candidate=stagedDatabase();TaskStore.verifyMaintenanceStaging(candidate,record.getString("repairId"));if(mode.equals("verified_copy"))verifyExtraction(current);DatabaseFilePreservation.Snapshot staged=DatabaseFilePreservation.inspect(candidate,current);DatabaseFilePreservation.requireCurrent(current);verifyOriginalAndBackup();
            record.put("phase","staged").put("stagingFingerprint",staged.fingerprint()).put("stagingFiles",staged.files());record.remove("reasonCode");record.remove("reviewDiscarded");
            JSONObject shown=new JSONObject().put("reviewId",reviewId).put("mode",mode).put("summary",mode.equals("empty_database")?"保全原件后建立空库；原历史不会恢复。新库保持执行停用，旧运行、授权、租约和配对不会迁入。":"保全原件后，仅以新身份恢复已核副本的对话文字；原任务历史、附件原件与授权、密钥和配对不迁入，执行保持停用。")
                .put("restoresHistory",false).put("restoresConversationContent",mode.equals("verified_copy")).put("restoresExecutionAuthority",false).put("executionEnabled",false);
            if(mode.equals("verified_copy"))shown.put("restoration",record.getJSONObject("restoration"));
            record.put("review",shown);review=new Review(reviewId,record,generation,owner);save();
        }catch(Exception failure){review=null;record.remove("review");record.put("reasonCode",safeFailure(failure));
            try{verifyOriginalAndBackup();record.put("phase","review_failed");}catch(Exception changed){record.put("phase","unknown");failure.addSuppressed(changed);}
            try{save();}catch(Exception uncertain){failure.addSuppressed(uncertain);}throw new IllegalStateException(reviewFailureMessage(safeFailure(failure)),failure);
        }
    }
    private void confirm(JSONObject input,BooleanSupplier owner,BooleanSupplier current,long generation,String request)throws Exception {
        requireSuspectedCorruption();requireFingerprints(input);Review exact=review;
        if(!"staged".equals(record.getString("phase"))||exact==null||exact.owner!=owner||!exact.current(generation)||!Boolean.TRUE.equals(input.opt("confirmed"))||!exact.id.equals(input.getString("reviewId"))||!exact.repair.equals(record.getString("repairId"))||!exact.source.equals(record.getString("sourceFingerprint"))||!exact.backup.equals(record.getString("backupFingerprint"))||!exact.mode.equals(record.getString("mode"))||!exact.staging.equals(record.getString("stagingFingerprint")))throw new SecurityException("需要当前窗口对准确保全身份重新审阅确认");
        if(exact.mode.equals("verified_copy")){if(!exact.extraction.equals(record.getJSONObject("restoration").getString("manifestSha256")))throw new SecurityException("原恢复提取清单已变化");verifyExtraction(current);}
        DatabaseFilePreservation.requireCurrent(current);if(!host.quiesce())throw new SecurityException("原资源停止未确认，未替换原库");verifyOriginalAndBackup();
        File candidate=stagedDatabase();TaskStore.verifyMaintenanceStaging(candidate,exact.repair);DatabaseFilePreservation.Snapshot staged=DatabaseFilePreservation.inspect(candidate,current);
        if(!staged.fingerprint().equals(record.getString("stagingFingerprint")))throw new SecurityException("原隔离暂存字节已变化");
        File retained=new File(repairDirectory(),"original-at-cutover");
        remember("repair.confirm",request,input);review=null;record.put("phase","switching").put("movedOriginalFiles",new JSONArray()).put("installedFiles",new JSONArray());save();
        try {
            createPrivateDirectory(retained);
            DatabaseFilePreservation.Snapshot source=manifest();
            for(DatabaseFilePreservation.Member member:source.members()){if(!member.exists())continue;DatabaseFilePreservation.requireCurrent(current);record.put("nextOriginalFile",member.name());save();
                moveWithoutReplacing(new File(database.getParentFile(),member.name()).toPath(),new File(retained,member.name()).toPath());DatabaseFilePreservation.syncDirectory(database.toPath().getParent());DatabaseFilePreservation.syncDirectory(retained.toPath());record.getJSONArray("movedOriginalFiles").put(member.name());save();}
            record.remove("nextOriginalFile");
            for(DatabaseFilePreservation.Member member:staged.members()){if(!member.exists())continue;DatabaseFilePreservation.requireCurrent(current);record.put("nextInstalledFile",member.name());save();
                moveWithoutReplacing(new File(candidate.getParentFile(),member.name()).toPath(),new File(database.getParentFile(),member.name()).toPath());DatabaseFilePreservation.syncDirectory(database.toPath().getParent());DatabaseFilePreservation.syncDirectory(candidate.toPath().getParent());record.getJSONArray("installedFiles").put(member.name());save();}
            record.remove("nextInstalledFile");record.put("phase","verifying");save();
            DatabaseFilePreservation.verify(database,staged);DatabaseFilePreservation.verify(new File(retained,TaskStore.DATABASE_NAME),source);TaskStore.verifyMaintenanceStaging(database,exact.repair);DatabaseFilePreservation.requireCurrent(current);
            if(exact.mode.equals("verified_copy"))verifyExtraction(current);
            JSONObject result=new JSONObject().put("mode",exact.mode).put("summary",exact.mode.equals("empty_database")?"已保全原件并建立空库；原历史未恢复":"已保全原件并以新身份恢复已核对的对话文字；请查看遗漏清单，原任务历史与执行授权未恢复").put("restoredHistory",false).put("restoredConversationContent",exact.mode.equals("verified_copy")).put("restoredActiveGrants",false).put("executionEnabled",false).put("businessRestarted",false);
            if(exact.mode.equals("verified_copy"))result.put("restoration",record.getJSONObject("restoration"));
            record.put("phase","completed").put("completedAt",System.currentTimeMillis()).put("result",result);save();
        }catch(Exception failure){record.put("interruptedPhase",record.optString("phase")).put("phase","unknown").put("reasonCode",safeFailure(failure));try{save();}catch(Exception persistence){failure.addSuppressed(persistence);refresh();}throw new IllegalStateException("原维护切换结果未确认；只查询同一repairId，不重新派发",failure);}
    }
    private void requireSuspectedCorruption()throws Exception{if(!"suspected_corruption".equals(record.getString("cause"))||!record.getBoolean("preservationVerified"))throw new SecurityException("只有疑似损坏且保全完整验证后才能审阅替换；版本、锁和迁移失败不清空");}
    private void requireRecord(JSONObject input)throws Exception{if(record==null||!record.getString("repairId").equals(input.getString("repairId")))throw new SecurityException("原维护编号已变化");}
    private void requireFingerprints(JSONObject input)throws Exception{if(!record.getString("sourceFingerprint").equals(input.getString("sourceFingerprint"))||!record.getString("backupFingerprint").equals(input.getString("backupFingerprint")))throw new SecurityException("原件或保全副本身份已变化");}
    private DatabaseFilePreservation.Snapshot manifest()throws Exception{return DatabaseFilePreservation.fromManifest(TaskStore.DATABASE_NAME,record.getJSONArray("files"),record.getString("sourceFingerprint"));}
    private void verifyOriginalAndBackup()throws Exception{DatabaseFilePreservation.Snapshot expected=manifest();DatabaseFilePreservation.verify(database,expected);DatabaseFilePreservation.verify(new File(new File(repairDirectory(),"preserved"),TaskStore.DATABASE_NAME),expected);}
    private void verifyExtraction(BooleanSupplier current)throws Exception{String stage=record.getString("stagingId");if(!stage.matches("[a-f0-9-]{36}"))throw new SecurityException("原提取暂存身份不可核对");TaskDatabaseRecoveryCopy.verifyExtraction(new File(repairDirectory(),"read-only-copy-"+stage),record.getJSONObject("restoration"),record.getString("repairId"),record.getString("sourceFingerprint"),current);}
    private File stagedDatabase()throws Exception {String id=record.getString("stagingId");if(!id.matches("[a-f0-9-]{36}"))throw new SecurityException("原暂存身份不可核对");return new File(new File(repairDirectory(),"staging-"+id),TaskStore.DATABASE_NAME);}
    private File repairDirectory()throws Exception {String id=record.getString("repairId");if(!id.matches("[a-f0-9-]{36}"))throw new SecurityException("原维护身份不可核对");ensureRoot();File directory=new File(root,id);if(!directory.exists())createPrivateDirectory(directory);if(Files.isSymbolicLink(directory.toPath())||!directory.getCanonicalFile().equals(directory.getAbsoluteFile()))throw new SecurityException("原维护目录不可核对");DatabaseFilePreservation.privateDirectory(directory.toPath());return directory;}
    private void ensureRoot()throws Exception{if(!root.exists())createPrivateDirectory(root);if(Files.isSymbolicLink(root.toPath())||!root.getCanonicalFile().equals(root.getAbsoluteFile()))throw new SecurityException("原维护目录不可核对");DatabaseFilePreservation.privateDirectory(root.toPath());}
    private static void createPrivateDirectory(File directory)throws Exception{DatabaseFilePreservation.requireDirectory(directory.toPath().getParent());Files.createDirectory(directory.toPath());DatabaseFilePreservation.privateDirectory(directory.toPath());DatabaseFilePreservation.syncDirectory(directory.toPath().getParent());}
    private static void moveWithoutReplacing(Path source,Path target)throws Exception {
        DatabaseFilePreservation.requireDirectory(source.toAbsolutePath().getParent());DatabaseFilePreservation.requireDirectory(target.toAbsolutePath().getParent());
        BasicFileAttributes original=DatabaseFilePreservation.optionalAttributes(source);
        if(original==null||!original.isRegularFile()||original.isSymbolicLink()||DatabaseFilePreservation.optionalAttributes(target)!=null)throw new SecurityException("maintenance_move_identity_unconfirmed");
        // ATOMIC_MOVE leaves replacement of an existing destination implementation-specific.
        // Each move is instead recorded before dispatch, forbids replacement and is read back.
        Files.move(source,target);
    }
    private static JSONObject readRecord(AtomicFile state)throws Exception {
        File base=state.getBaseFile();JSONObject selected=null;java.util.Set<String> identities=new java.util.HashSet<>();boolean found=false,uncertain=false;Exception invalid=null;
        BasicFileAttributes parent=DatabaseFilePreservation.optionalAttributes(base.toPath().getParent());if(parent==null)return null;DatabaseFilePreservation.requireDirectory(base.toPath().getParent());
        String[] suffixes={"",".bak",".new"};
        for(String suffix:suffixes){
            Path path=new File(base+suffix).toPath();BasicFileAttributes attributes=recordAttributes(path);if(attributes==null)continue;found=true;if(!suffix.isEmpty())uncertain=true;
            try{
                byte[] bytes=DatabaseFilePreservation.readBounded(path,1048576,()->true);
                JSONObject value=new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
                validateRecord(value);identities.add(value.getString("repairId"));if(selected==null)selected=value;
            }catch(Exception failure){uncertain=true;if(invalid==null)invalid=failure;else invalid.addSuppressed(failure);}
        }
        if(!found)return null;if(selected==null)throw new java.io.IOException("maintenance_record_unreadable",invalid);
        if(identities.size()!=1)throw new SecurityException("maintenance_record_repair_identity_ambiguous");
        // AtomicFile.openRead may delete .new or rename .bak. Cold maintenance observes them
        // without mutation; any interrupted record commit remains query-only for this repair.
        if(uncertain)selected.put("interruptedPhase",selected.getString("phase")).put("phase","unknown").put("reasonCode","maintenance_record_commit_unconfirmed");
        return selected;
    }
    private static BasicFileAttributes recordAttributes(Path path)throws Exception{BasicFileAttributes value=DatabaseFilePreservation.optionalAttributes(path);if(value!=null&&(!value.isRegularFile()||value.isSymbolicLink()||value.size()>1048576))throw new SecurityException("maintenance_record_file_unconfirmed");return value;}
    private static void validateRecord(JSONObject value)throws Exception {
        if(!FORMAT.equals(value.getString("format"))||!value.getString("repairId").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||!Set.of("detected","quiescence_unconfirmed","preserving","preserved","preservation_failed","reviewing","review_failed","staged","switching","verifying","original_retry_prepared","original_retry_completed","unknown","completed").contains(value.getString("phase"))||!(value.get("preservationVerified") instanceof Boolean)||!(value.get("sourceFingerprint") instanceof String)||!(value.get("backupFingerprint") instanceof String))throw new SecurityException("原维护记录格式不可核对");
        value.getJSONObject("requests");
        if(!value.getString("sourceFingerprint").isEmpty())DatabaseFilePreservation.fromManifest(TaskStore.DATABASE_NAME,value.getJSONArray("files"),value.getString("sourceFingerprint"));
        else if(value.getJSONArray("files").length()!=0)throw new SecurityException("原保全清单缺少指纹");
        if(value.getBoolean("preservationVerified")&&(!value.getString("sourceFingerprint").equals(value.getString("backupFingerprint"))||value.getString("backupFingerprint").isEmpty()))throw new SecurityException("原保全指纹不可核对");
        if(value.has("stagingFiles"))DatabaseFilePreservation.fromManifest(TaskStore.DATABASE_NAME,value.getJSONArray("stagingFiles"),value.getString("stagingFingerprint"));
        if("completed".equals(value.getString("phase"))&&(!value.getBoolean("preservationVerified")||!value.has("stagingFiles")||!Set.of("empty_database","verified_copy").contains(value.getString("mode"))||!Boolean.FALSE.equals(value.getJSONObject("result").get("executionEnabled"))))throw new SecurityException("原维护完成证据不可核对");
        if("original_retry_completed".equals(value.getString("phase"))&&(!value.getBoolean("preservationVerified")||!"original_database".equals(value.getJSONObject("result").getString("mode"))||!Boolean.FALSE.equals(value.getJSONObject("result").get("replacedDatabase"))))throw new SecurityException("原数据库重新打开完成证据不可核对");
    }
    private static void atomicWrite(AtomicFile file,JSONObject value)throws Exception {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>1048576)throw new SecurityException("maintenance_record_size_unconfirmed");
        Path base=file.getBaseFile().toPath();DatabaseFilePreservation.requireDirectory(base.toAbsolutePath().getParent());recordAttributes(base);
        if(recordAttributes(Path.of(base+".bak"))!=null||recordAttributes(Path.of(base+".new"))!=null)throw new SecurityException("maintenance_record_commit_unconfirmed");
        FileOutputStream output=null;
        try{output=file.startWrite();recordAttributes(Path.of(base+".new"));output.write(bytes);output.flush();output.getFD().sync();file.finishWrite(output);output=null;DatabaseFilePreservation.privateFile(base);if(!java.util.Arrays.equals(bytes,DatabaseFilePreservation.readBounded(base,1048576,()->true)))throw new java.io.IOException("maintenance_record_readback_mismatch");}
        catch(Exception failure){if(output!=null)file.failWrite(output);throw failure;}
    }
    private void save()throws Exception {
        try{ensureRoot();record.put("updatedAt",System.currentTimeMillis());atomicWrite(state,record);DatabaseFilePreservation.syncDirectory(root.toPath());refresh();}
        catch(Exception uncertain){review=null;record.put("interruptedPhase",record.optString("phase")).put("phase","unknown").put("reasonCode","maintenance_record_commit_unconfirmed");refresh();throw uncertain;}
    }
    private void archiveRecord()throws Exception {File directory=repairDirectory();atomicWrite(new AtomicFile(new File(directory,"last-record.json")),record);}
    private boolean duplicate(String command,String request,JSONObject input)throws Exception {JSONObject previous=record.getJSONObject("requests").optJSONObject(request);if(previous==null)return false;if(!command.equals(previous.getString("command"))||!payloadHash(input).equals(previous.getString("payload")))throw new SecurityException("同一请求编号不能改为其他维护操作");return true;}
    private void remember(String command,String request,JSONObject input)throws Exception {JSONObject requests=record.getJSONObject("requests");if(requests.length()>=64)throw new IllegalStateException("此原维护记录已达请求上限，请保留原记录并人工核对");requests.put(request,new JSONObject().put("command",command).put("payload",payloadHash(input)));}
    private static String payloadHash(JSONObject input)throws Exception{return DatabaseFilePreservation.sha256(input.toString().getBytes(StandardCharsets.UTF_8));}
    private static long exactGeneration(JSONObject input)throws Exception{Object value=input.get("expectedGeneration");if(!(value instanceof Long||value instanceof Integer)||((Number)value).longValue()<0)throw new SecurityException("缺少准确启动代次");return ((Number)value).longValue();}
    private static String safeFailure(Exception failure){String message=failure.getMessage();if(message!=null&&message.matches("[a-z_]{4,90}"))return message;return failure instanceof SecurityException?"original_owner_or_source_unavailable":"maintenance_io_unconfirmed";}
    private static String reviewFailureMessage(String code){return switch(code){
        case "verified_copy_schema_incompatible"->"已核副本的schema与当前包不同，未导入或切换；请保留原件并使用兼容版本";
        case "verified_copy_conversation_limit"->"已核副本超过本次200个对话的完整读取范围，未截断或改为空库；请保留原副本并核对可分区恢复方案";
        case "verified_copy_content_byte_limit","conversation_export_byte_budget","conversation_export_row_or_time_budget"->"所选对话超过本次完整读取范围，未截断或切换；请保留原副本并核对分区恢复方案";
        case "verified_copy_original_record_unconfirmed"->"副本中的原消息校验或读取未确认，未采用替代正文，也未切换为空库";
        case "verified_copy_no_recoverable_messages"->"已核副本未提取到可验证的对话消息，未将空结果算作恢复，也未切换为空库";
        case "verified_copy_history_authority_unavailable","verified_copy_history_generation_unavailable"->"副本的原对话代次不能核对，未借用旧文件或其他历史恢复";
        default->"所选维护暂存未通过核验；原件和已核保全仍保留，未自动切换为空库。请核对存储与当前窗口后重新审阅";
    };}
    /** Cold recovery observes only fixed native paths of this repair. It never mkdirs, opens SQLite or replays a rename. */
    private JSONObject readPhysicalState(JSONObject shown)throws Exception {
        String repair=shown.getString("repairId");if(!repair.matches("[a-f0-9-]{36}"))throw new SecurityException("原维护编号不可核对");File directory=new File(root,repair);
        JSONObject actual=new JSONObject().put("readOnly",true).put("readAt",System.currentTimeMillis()).put("original",fileObservation(database))
            .put("preserved",fileObservation(new File(new File(directory,"preserved"),TaskStore.DATABASE_NAME)))
            .put("retainedAtCutover",fileObservation(new File(new File(directory,"original-at-cutover"),TaskStore.DATABASE_NAME)));
        if(shown.has("stagingId")){String stage=shown.getString("stagingId");if(!stage.matches("[a-f0-9-]{36}"))throw new SecurityException("原暂存编号不可核对");actual.put("staging",fileObservation(new File(new File(directory,"staging-"+stage),TaskStore.DATABASE_NAME)));}
        String expected=shown.optString("sourceFingerprint");boolean preserved=shown.optBoolean("preservationVerified")&&!expected.isEmpty()&&expected.equals(actual.getJSONObject("preserved").optString("fingerprint"));
        actual.put("preservedMatchesCaptured",preserved).put("liveMatchesCaptured",!expected.isEmpty()&&expected.equals(actual.getJSONObject("original").optString("fingerprint")))
            .put("liveMatchesStaging",!shown.optString("stagingFingerprint").isEmpty()&&shown.getString("stagingFingerprint").equals(actual.getJSONObject("original").optString("fingerprint")));
        shown.put("currentReadback",actual);
        if(shown.optBoolean("preservationVerified")&&!preserved){shown.put("preservationVerified",false).put("originalRetryAvailable",false).put("allowedCommands",new JSONArray().put("repair.read")).put("reviewModes",new JSONArray()).put("review",JSONObject.NULL).put("reason",reason("preserved_bytes_unconfirmed","原保全副本的当前字节未通过核对，替换保持关闭","保留同一repairId并核对私有原件，不重复确认"));}
        return shown;
    }
    private static JSONObject fileObservation(File file)throws Exception {
        try{if(DatabaseFilePreservation.optionalAttributes(file.toPath().getParent())==null)return new JSONObject().put("state","absent");var bytes=DatabaseFilePreservation.inspect(file);return new JSONObject().put("state",Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)?"present":"main_absent").put("fingerprint",bytes.fingerprint()).put("files",bytes.files());}
        catch(Exception unavailable){return new JSONObject().put("state","unconfirmed");}
    }
    private void refresh()throws Exception {
        if(record==null){visible=new JSONObject().put("repairId",JSONObject.NULL).put("phase","not_started").put("sourceFingerprint","").put("backupFingerprint","").put("preservationVerified",false).put("originalRetryAvailable",false).put("files",new JSONArray()).put("review",JSONObject.NULL).put("allowedCommands",new JSONArray(new String[]{"repair.read","repair.preserve"})).put("reason",reason(failureCode,failureMessage,"可先保全本机原件；保全不会恢复或清空历史")).toString();return;}
        JSONObject shown=new JSONObject(record.toString());shown.remove("requests");shown.remove("uncertainProcess");JSONArray allowed=new JSONArray().put("repair.read");String phase=record.getString("phase");
        if(phase.equals("detected")||phase.equals("quiescence_unconfirmed")&&!process.equals(record.optString("uncertainProcess")))allowed.put("repair.preserve");
        if(Set.of("preserved","staged","review_failed").contains(phase)&&"suspected_corruption".equals(record.optString("cause"))&&record.optBoolean("preservationVerified"))allowed.put("repair.review");
        if(phase.equals("staged")&&review!=null&&review.current(host.generation()))allowed.put("repair.confirm");
        if(Set.of("detected","preserved","staged","review_failed","preservation_failed","quiescence_unconfirmed").contains(phase))allowed.put("repair.discard");
        String explanation=INCOMPLETE.contains(phase)?"原维护动作尚未确认完成，保留所有原件，仅查询同一记录":phase.equals("quiescence_unconfirmed")?"原资源停止尚未证实，未复制或替换数据库":phase.equals("preservation_failed")?"保全未完成验证，替换保持禁用":phase.equals("review_failed")?reviewFailureMessage(record.optString("reasonCode")):Set.of("completed","original_retry_completed").contains(phase)?record.getJSONObject("result").getString("summary"):"原数据库与保全身份可供本机主人核对";
        shown.put("originalRetryAvailable",originalRetryAvailable(record));
        shown.put("reviewModes",Set.of("preserved","staged","review_failed").contains(phase)&&"suspected_corruption".equals(record.optString("cause"))&&record.optBoolean("preservationVerified")?new JSONArray(new String[]{"empty_database","verified_copy"}):new JSONArray());
        shown.put("allowedCommands",allowed).put("review",review==null?JSONObject.NULL:record.opt("review")).put("reason",reason(record.optString("reasonCode",phase),explanation,INCOMPLETE.contains(phase)?"请保留原repairId并核对原文件状态；不要重复确认":phase.equals("completed")?"明确重试启动；原任务恢复器会重新读取，执行仍停用":"按当前可用操作继续，离开窗口后需重新审阅"));visible=shown.toString();
    }
    private static JSONObject reason(String code,String message,String next)throws Exception{return new JSONObject().put("code",code).put("message",message).put("nextStep",next);}
}
