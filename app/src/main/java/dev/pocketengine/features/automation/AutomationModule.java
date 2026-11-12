package dev.pocketengine.features.automation;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Inexact reminders and reviewed plan state; actual task execution belongs to the native host. */
public final class AutomationModule implements EngineModule {
    public static final String ALARM_ACTION="dev.pocketengine.AUTOMATION_DUE";
    private static final String CHANNEL="personal_reminders";
    private static final Object RESTORE_LOCK=new Object();
    private static boolean restored;
    private static final AutomationHostGate HOST_GATE=new AutomationHostGate();
    // Readback ordering only. Never persisted or consulted by scheduling/admission/authorization.
    private static final Object READBACK_LOCK=new Object();
    private static final String OBSERVATION_GENERATION=java.util.UUID.randomUUID().toString();
    private static long observationSequence;
    private static final Object SCHEDULE_LOCK=new Object();
    private static long scheduleGeneration;
    private static long lastHostNext=-1;
    private static long lastMaintenanceNext=-1;
    private static final long HOST_QUERY_TIMEOUT=1000,HOST_DUE_TIMEOUT=3000,MIN_HOST_WAKE_DELAY=60000;

    /** Implement on the Application; return null until its dependencies are fully initialized. */
    public interface AlarmHostProvider { AlarmHost automationAlarmHost(); default AutomationModule originalReminderModule(){return null;} default boolean executionAllowed(){return true;} }
    /**
     * Existing-host hook, not another scheduler or an authorization source. No model/network/
     * phone work here. Both methods run on a bounded worker, outside all book locks. Derive
     * authenticated scope yourself. next is read-only (-1=no work); due must obey maxCandidates,
     * check interruption, and use durable claim/begin/receipt before any notification effect.
     */
    public interface AlarmHost {
        long nextAutomationAt(long now)throws Exception;
        void onAutomationDue(long now,int maxCandidates)throws Exception;
        /** Privacy expiry only. This does not admit task, model, tool, or notification work. */
        default long nextMaintenanceAt(long now)throws Exception{return -1;}
        default void onMaintenanceDue(long now)throws Exception{}
    }
    private final FeatureContext context;
    private final AutomationBook book;
    private final AutomationBook.GoalTaskResolver nativeScheduleSources;
    private final CommitmentBook commitments;
    private final AutomationConditions.Source conditions;
    public AutomationModule(FeatureContext context)throws Exception {this(context,null);}
    /** The optional adapter only resolves durable evidence; it grants no execution authority. */
    public AutomationModule(FeatureContext context,CommitmentBook.EvidenceResolver evidenceResolver)throws Exception {
        this(context,evidenceResolver,null);
    }
    public AutomationModule(FeatureContext context,CommitmentBook.EvidenceResolver evidenceResolver,AutomationBook.GoalTaskResolver goalTasks)throws Exception {
        this(context,evidenceResolver,goalTasks,new AndroidAutomationConditions(context.android));
    }
    public AutomationModule(FeatureContext context,CommitmentBook.EvidenceResolver evidenceResolver,AutomationBook.GoalTaskResolver goalTasks,AutomationConditions.Source conditions)throws Exception {
        this.context=context;this.conditions=conditions;this.nativeScheduleSources=goalTasks;book=new AutomationBook(new File(context.android.getFilesDir(),"automation/rules.json"),goalTasks,conditions);
        commitments=new CommitmentBook(new File(context.android.getFilesDir(),"automation/commitments.json"),evidenceResolver,conditions,goalTasks);
        // Do not enter Application callbacks while its module field is still being assigned.
        restore(context.android,false);
    }
    @Override public String id(){return "automation";}
    @Override public JSONObject snapshot()throws Exception {
        reconcileNativeScheduleSources();
        JSONObject projection=ruleProjection();
        NotificationManager manager=context.android.getSystemService(NotificationManager.class);
        NotificationChannel channel=manager==null?null:manager.getNotificationChannel(CHANNEL);
        return projection.put("available",true)
                .put("notificationsEnabled",manager!=null&&manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE))
                .put("timing","inexact_best_effort").put("autonomousPhoneExecution",false)
                .put("commitmentsSupported",true).put("commitmentFollowUpMode","read_only_candidates")
                .put("completionEvidenceAvailable",commitments.completionEvidenceAvailable())
                .put("rulePolicySupported",true).put("ruleBudgetUnit","claimed_notification_occurrences").put("commitmentPlansSupported",true)
                .put("goalResultAuthority","TaskStore").put("followUpBudgetUnit","notification_admissions_per_local_day")
                .put("executionPlansSupported",true).put("executionPlanControl","native_owner_review_only").put("executionPlanTaskAuthority","existing_TaskStore")
                .put("planChangeNotificationsSupported",true).put("planChangeNotificationsDefault","explicit_owner_choice")
                .put("existingRemoteCommandWaitSupported",true).put("remoteWaitResultScope","exact_original_command_exit_zero_only")
                .put("runConditionsSupported",true).put("conditionSnapshot","native_read_only").put("conditionRecheckMillis",AutomationConditions.RECHECK_MILLIS)
                .put("scheduleTypes",new JSONArray(new String[]{"once","interval","daily","weekly","monthly","cron"})).put("schedulePreview","native_read_only")
                .put("scheduleVersions",new JSONArray().put(AutomationSchedule.SCHEDULE_VERSION))
                .put("cronSubset",new JSONObject().put("fields","minute hour day month weekday").put("sunday",0).put("localTimeResolution","minute")
                        .put("operators",new JSONArray(new String[]{"*","number","ascending_range","comma_list","*/step","range/step"}))
                        .put("dayAndWeekday","at_least_one_unrestricted").put("nextSearchYears",AutomationSchedule.CRON_LOOKAHEAD_YEARS))
                .put("followUpDeliveryPolicySupported",true).put("followUpDeliveryDefault","disabled").put("followUpDeliveryDriver","host_only")
                .put("commands",new JSONArray(new String[]{"list","save","setEnabled","delete","receipts","checkDue","prepareTask",
                        "createCommitment","listCommitments","getCommitment","updateCommitment","snoozeCommitment","cancelCommitment","completeCommitment","dueCommitments","getFollowUpPolicy","evaluateFollowUp",
                        "createPlan","listPlans","getPlan","cancelPlan","getProactivityState","getGoalState"}));
    }
    private JSONObject ruleProjection()throws Exception {
        synchronized(READBACK_LOCK){
            // No source resolver, host callback, notification or alarm API under this lock.
            JSONArray rules=book.rules();long next=book.nextAt();
            return new JSONObject().put("rules",rules).put("nextAt",next)
                    .put("observationGeneration",OBSERVATION_GENERATION).put("observationSequence",++observationSequence);
        }
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        if(command.equals("getFollowUpPolicy"))return new JSONObject().put("policy",getFollowUpPolicy(input.getJSONObject("scope")));
        if(command.equals("evaluateFollowUp"))return evaluateFollowUp(input.getJSONObject("scope"),input.getString("id"),positiveInteger(input,"revision"),input.getString("candidateKey"));
        if(command.equals("getProactivityState"))return proactivityState(input.getJSONObject("scope"));
        if(command.equals("getGoalState"))return goalState(input.getString("occurrenceId"));
        if(command.equals("createPlan")||command.equals("listPlans")||command.equals("getPlan")||command.equals("cancelPlan"))return handlePlan(command,input);
        if(command.endsWith("Commitment")||command.equals("listCommitments")||command.equals("dueCommitments"))return handleCommitment(command,input);
        if(command.matches("list|receipts|save|setEnabled|delete|checkDue"))reconcileNativeScheduleSources();
        JSONObject result;
        switch(command){
            case "list":return ruleProjection();
            case "receipts":return new JSONObject().put("receipts",book.receipts());
            case "prepareTask":return taskRequest(input.getString("occurrenceId"));
            case "save":return savedRuleResult(book.save(input.getJSONObject("rule"),System.currentTimeMillis()));
            case "setEnabled":result=new JSONObject().put("rule",book.setEnabled(input.getString("id"),input.getBoolean("enabled"),System.currentTimeMillis(),input.has("revision")?positiveInteger(input,"revision"):-1));break;
            case "delete":{
                JSONArray removed=book.delete(input.getString("id"),input.has("revision")?positiveInteger(input,"revision"):-1);NotificationManager manager=context.android.getSystemService(NotificationManager.class);
                if(manager!=null)for(int i=0;i<removed.length();i++)manager.cancel(notificationId(removed.getJSONObject(i).getString("id")));
                result=new JSONObject().put("deleted",true);break;
            }
            case "checkDue":result=new JSONObject().put("receipts",deliver(context.android,book,this));break;
            default:throw new IllegalArgumentException("未知定时操作");
        }
        schedule(context.android,book);emitScheduleChange(command,result.optJSONObject("rule"),input.optString("id"));return result;
    }
    private void emitScheduleChange(String command,JSONObject rule,String originalId)throws Exception {
        context.emit(id(),scheduleChangeEvent(command,rule,originalId,snapshot()));
    }
    private static JSONObject scheduleChangeEvent(String command,JSONObject rule,String originalId,JSONObject snapshot)throws Exception {
        JSONObject event=new JSONObject().put("event","automation_changed").put("command",command).put("snapshot",snapshot);
        if(rule!=null)event.put("ruleId",rule.getString("id")).put("ruleRevision",rule.getInt("revision")).put("scheduleVersion",rule.getJSONObject("schedule").opt("scheduleVersion")==null?AutomationSchedule.SCHEDULE_VERSION:rule.getJSONObject("schedule").opt("scheduleVersion"));
        else if(!originalId.isBlank())event.put("ruleId",originalId);
        return event;
    }
    /** Book's atomic writer returned. A later alarm/readback/event failure must never turn
     * that original save into permission to create another UUID. This is a receipt, not a ledger. */
    private JSONObject savedRuleResult(JSONObject saved)throws Exception {
        String originalId=saved.getString("id");int revision=saved.getInt("revision");
        JSONObject receipt=new JSONObject().put("state","saved").put("ruleId",originalId).put("ruleRevision",revision)
                .put("alarmReplan","unconfirmed").put("changePublication","not_emitted").put("ruleReadback","unconfirmed")
                .put("sourceState",saved.optJSONObject("nativeOrigin")==null?"not_applicable":"unconfirmed").put("executionAuthorized",false);
        JSONObject result=new JSONObject().put("saveReceipt",receipt);
        try{if(schedule(context.android,book))receipt.put("alarmReplan","api_accepted");}catch(Exception unconfirmed){/* The saved identity survives; no save retry. */}
        JSONObject snapshot=null,current=null;
        try{snapshot=snapshot();}catch(Exception unconfirmed){/* Source or snapshot availability is not inferred from the old saved copy. */}
        try{
            JSONArray rules=snapshot==null?book.rules():snapshot.getJSONArray("rules");
            for(int i=0;i<rules.length();i++)if(originalId.equals(rules.getJSONObject(i).optString("id"))){current=rules.getJSONObject(i);break;}
            if(current==null)receipt.put("ruleReadback","not_found");
            else{
                receipt.put("ruleReadback",current.getInt("revision")==revision?"same_revision":"changed_revision").put("currentRuleRevision",current.getInt("revision"));
                String source=current.optBoolean("sourceInvalidated")?"invalidated":current.optBoolean("sourceCheckPending")?"check_pending":current.optJSONObject("nativeOrigin")==null?"not_applicable":snapshot==null?"unconfirmed":"current";
                receipt.put("sourceState",source);
                // Never return stale source text just because the original write succeeded.
                if(source.equals("current")||source.equals("not_applicable")||source.equals("invalidated"))result.put("rule",new JSONObject(current.toString()));
            }
        }catch(Exception unconfirmed){receipt.put("ruleReadback","unconfirmed");result.remove("rule");}
        if(snapshot!=null){
            try{context.emit(id(),scheduleChangeEvent("save",current,originalId,snapshot));receipt.put("changePublication","emitted");}
            catch(Exception unconfirmed){receipt.put("changePublication","unconfirmed");}
        }
        return result;
    }
    /** Native private conversation/owner glue only, not exposed by generic module tools. */
    public JSONObject proposeScheduleForTask(JSONObject nativeOrigin,String operation,JSONObject draft)throws Exception{return book.proposeSchedule(nativeOrigin,operation,draft,System.currentTimeMillis());}
    /** The original book is the draft receipt; accepting this tool never creates a task or
     * reschedules an alarm. Publication happens only after the original SQL admission ends. */
    public JSONObject receiveDeviceScheduleDraft(dev.pocketengine.features.devices.DeviceScheduleDrafts.Admission original,dev.pocketengine.store.TaskStore tasks)throws Exception{
        final boolean[] entered={false};JSONObject result;
        try{result=tasks.withDeviceScheduleAdmission(original,()->{entered[0]=true;JSONObject row=original.queryOnly()?book.deviceScheduleProposal(original.origin()):book.proposeDeviceSchedule(original.origin(),original.draft(),System.currentTimeMillis());return original.receipt(row);});}
        catch(AutomationBook.DeviceDraftRefused rejected){return original.notAccepted(rejected.code,false);}
        catch(Exception unknown){if(original.queryOnly()||!entered[0])throw unknown;return original.notAccepted("draft_save_unconfirmed",true);}
        if(!original.queryOnly())try{context.emit(id(),new JSONObject().put("event","schedule_proposal_received").put("executionAuthorized",false));}catch(Exception publicationUnknown){/* The original persisted draft receipt remains authoritative. */}
        return result;
    }
    public JSONArray scheduleProposalsForTask(String task)throws Exception{return book.scheduleProposals(task);}
    @FunctionalInterface public interface OriginalProposalSource {JSONObject call(java.util.concurrent.Callable<JSONObject> originalBookAction)throws Exception;}
    /** Source/Tasks scopes end before touching alarms or the native scheduling host. */
    public JSONObject approveScheduleProposalForOwner(String id,String digest,OriginalProposalSource source)throws Exception{
        JSONObject result=java.util.Objects.requireNonNull(source).call(()->{
            // Only the actual current-source gate may release a retained quarantine.
            // An old displayed row cannot clear it by calling the Book directly.
            JSONArray rows=book.scheduleProposals(null);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(id.equals(row.getString("id"))){book.nativeScheduleSourcePending(row.getJSONObject("origin"),false,System.currentTimeMillis());break;}}
            return book.approveScheduleProposal(id,digest,System.currentTimeMillis());
        });reschedule(context.android);if(!result.optBoolean("duplicate"))emitScheduleChange("approveSchedule",result.getJSONObject("rule"),id);return result;
    }
    /** Native supervisor validates this exact occurrence before admitting its own persistent task. */
    public JSONObject taskRequest(String occurrenceId)throws Exception {
        JSONObject origin=book.receiptNativeOrigin(occurrenceId);if(origin==null)return book.taskRequest(occurrenceId,System.currentTimeMillis());final Exception[] bookFailure={null};
        try{return withNativeScheduleSource(origin,()->{book.nativeScheduleSourcePending(origin,false,System.currentTimeMillis());try{return book.taskRequest(occurrenceId,System.currentTimeMillis());}catch(Exception rejected){bookFailure[0]=rejected;throw rejected;}});}
        catch(Exception unavailable){if(unavailable!=bookFailure[0]){if(unavailable instanceof ScheduleSourceState.Invalidated)book.revokeNativeScheduleOrigin(origin,System.currentTimeMillis());else book.nativeScheduleSourcePending(origin,true,System.currentTimeMillis());clearSourceNotifications();}throw unavailable;}
    }
    private JSONObject withNativeScheduleSource(JSONObject origin,java.util.concurrent.Callable<JSONObject> action)throws Exception {if(nativeScheduleSources==null)throw new ScheduleSourceState.Unavailable();return nativeScheduleSources.withOriginalScheduleSource(origin,action);}
    /** Native source reconciliation only; no scheduler, model, helper or execution grant. */
    public JSONObject reconcileNativeScheduleSources()throws Exception {
        int revoked=0,pending=0;JSONArray origins=book.nativeScheduleOrigins();for(int i=0;i<origins.length();i++){JSONObject origin=origins.getJSONObject(i);try{withNativeScheduleSource(origin,()->new JSONObject());book.nativeScheduleSourcePending(origin,false,System.currentTimeMillis());}
            catch(ScheduleSourceState.Invalidated known){revoked+=book.revokeNativeScheduleOrigin(origin,System.currentTimeMillis()).getInt("revokedRows");}
            catch(Exception unavailable){book.nativeScheduleSourcePending(origin,true,System.currentTimeMillis());pending++;}}
        JSONObject cleared=clearSourceNotifications();return cleared.put("revokedRows",revoked).put("sourceChecksPending",pending).put("executionAuthorized",false);
    }

    private JSONObject clearSourceNotifications()throws Exception {return clearSourceNotifications(context.android,book);}
    private static JSONObject clearSourceNotifications(Context context,AutomationBook book)throws Exception {
        JSONArray pending=book.sourceNotificationClears(),cleared=new JSONArray();NotificationManager manager=context.getSystemService(NotificationManager.class);
        for(int i=0;i<pending.length();i++){String id=pending.getString(i);try{if(manager==null)continue;manager.cancel(notificationId(id));PendingIntent old=PendingIntent.getActivity(context,notificationId(id),notificationIntent(context,id),PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);if(old!=null)old.cancel();cleared.put(id);}catch(RuntimeException unknown){/* Original body-free pending row is retained; never claim a confirmed clear. */}}
        if(cleared.length()>0)book.sourceNotificationsCleared(cleared);return new JSONObject().put("notificationCancelApiAccepted",cleared.length()).put("notificationClearPending",pending.length()-cleared.length()).put("visualRemovalVerified",false);
    }

    /** Read-only original-task projection; it cannot start or authorize execution. */
    public JSONObject goalState(String occurrenceId)throws Exception{reconcileNativeScheduleSources();return book.goalState(occurrenceId);}
    /** Native owner UI hook. Observation does not authorize a phone/model/tool action. */
    public JSONObject conditionsSnapshot()throws Exception{return AutomationConditions.read(conditions,System.currentTimeMillis()).json();}
    /** Draft calendar preview only; no rule write, alarm, reservation, TaskStore or event. */
    public JSONObject previewSchedule(JSONObject schedule,long after,int limit)throws Exception{return AutomationSchedule.preview(schedule,after,limit);}
    /** Optional native signal/user refresh; replan the existing alarm without dispatching work. */
    public void conditionsChanged()throws Exception {book.recheckConditions(System.currentTimeMillis());reschedule(context.android);}
    public JSONObject proactivityState(JSONObject scope)throws Exception {
        long now=System.currentTimeMillis();JSONObject result=commitments.proactivityState(scope,now);
        // Legacy reminder rules belong only to the device owner's private space.
        if("owner".equals(scope.optString("ownerId"))&&"private".equals(scope.optString("spaceId")))result.put("schedules",book.proactivityState(now));
        return result;
    }

    /** Read-only due data for the existing host. No alarm, claim, notification or task is created. */
    public JSONObject dueCommitments(JSONObject scope,int limit)throws Exception {
        return commitments.due(scope,System.currentTimeMillis(),limit);
    }
    /** Read-only policy/decisions may inform the UI; they never send or claim anything. */
    public JSONObject getFollowUpPolicy(JSONObject scope)throws Exception {return commitments.getFollowUpPolicy(scope);}
    public JSONObject evaluateFollowUp(JSONObject scope,String id,long revision,String candidateKey)throws Exception {
        return commitments.evaluateFollowUp(scope,id,revision,candidateKey,System.currentTimeMillis());
    }
    public JSONObject exportBackupForOwner(dev.pocketengine.core.RunControl original)throws Exception{return book.exportBackupForOwner(original);}
    public dev.pocketengine.features.workspace.OwnerConfigurationBackupSections.Review reviewBackupForOwner(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{return book.reviewBackupForOwner(section,action,original);}
    public JSONObject queryBackupForOwner(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{return book.queryBackupForOwner(section,action,original);}
    /** Native explicit user-settings hook only; do not route model-generated settings here. */
    public JSONObject setFollowUpPolicy(JSONObject scope,long revision,JSONObject policy)throws Exception {
        JSONObject result=commitments.setFollowUpPolicy(scope,revision,policy,System.currentTimeMillis());commitmentsChanged();return result;
    }
    /** Native existing-host hooks. Only replan the existing alarm; never send or execute. */
    public JSONObject claimFollowUp(JSONObject scope,String id,long revision,String candidateKey,long leaseMillis)throws Exception {
        JSONObject result=commitments.claimFollowUp(scope,id,revision,candidateKey,leaseMillis,System.currentTimeMillis());
        if(result.getBoolean("claimed"))reschedule(context.android);return result;
    }
    public JSONObject beginFollowUpDelivery(JSONObject scope,String claimId)throws Exception {
        JSONObject result=commitments.beginFollowUpDelivery(scope,claimId,System.currentTimeMillis());
        if(result.getBoolean("dispatchGranted"))reschedule(context.android);return result;
    }
    public JSONObject recordFollowUpDelivery(JSONObject scope,String claimId,String outcome)throws Exception {
        JSONObject result=commitments.recordFollowUpDelivery(scope,claimId,outcome,System.currentTimeMillis());reschedule(context.android);return result;
    }
    public JSONObject getFollowUpReceipt(JSONObject scope,String claimId)throws Exception {return commitments.getFollowUpReceipt(scope,claimId);}

    public JSONArray listFollowUpReceipts(JSONObject scope,String commitmentId)throws Exception {return commitments.listFollowUpReceipts(scope,commitmentId);}

    /** Only an authoritative native task event may enter here; not a JSON feature command. */
    public JSONObject onCommitmentTaskCompleted(JSONObject scope,String id,long revision,String eventId,String taskId,JSONObject evidenceRef)throws Exception {
        JSONObject result=commitments.onTaskCompleted(scope,id,revision,eventId,taskId,evidenceRef,System.currentTimeMillis());
        if(result.getBoolean("applied"))commitmentsChanged();return result;
    }
    /** Native plan controls only. They never manufacture a grant or run another TaskStore. */
    public JSONObject approvePlanExecution(JSONObject scope,String id,long revision,JSONObject settings)throws Exception {JSONObject result=commitments.approvePlanExecution(scope,id,revision,settings,System.currentTimeMillis());commitmentsChanged();return result;}
    public JSONObject startPlanExecution(JSONObject scope,String id,long revision)throws Exception {JSONObject result=commitments.startPlanExecution(scope,id,revision,System.currentTimeMillis());commitmentsChanged();return result;}
    public JSONObject setPlanPaused(JSONObject scope,String id,long revision,boolean paused)throws Exception {JSONObject result=commitments.setPlanPaused(scope,id,revision,paused,System.currentTimeMillis());commitmentsChanged();return result;}
    public JSONObject cancelPlanExecution(JSONObject scope,String id,long revision)throws Exception {JSONObject result=commitments.cancelPlanExecution(scope,id,revision,System.currentTimeMillis());commitmentsChanged();return result;}
    public JSONObject editPlanStep(JSONObject scope,String planId,long planRevision,String stepId,long stepRevision,JSONObject patch)throws Exception {JSONObject result=commitments.editPlanStep(scope,planId,planRevision,stepId,stepRevision,patch,System.currentTimeMillis());commitmentsChanged();return result;}
    public JSONObject retryPlanStep(JSONObject scope,String planId,long planRevision,String stepId,long stepRevision)throws Exception {JSONObject result=commitments.retryPlanStep(scope,planId,planRevision,stepId,stepRevision,System.currentTimeMillis());commitmentsChanged();return result;}
    public JSONObject readyPlanTasks(JSONObject scope,int limit)throws Exception {return commitments.readyPlanTasks(scope,System.currentTimeMillis(),limit);}
    public JSONObject readyPlanTasks(JSONObject scope,String planId,int limit)throws Exception {return commitments.readyPlanTasks(scope,planId,System.currentTimeMillis(),limit);}
    public JSONObject inspectPlanTask(JSONObject scope,String planId,String stepId)throws Exception{return commitments.inspectPlanTask(scope,planId,stepId,System.currentTimeMillis());}
    public JSONObject planProgress(JSONObject scope,String planId)throws Exception{return commitments.planProgress(scope,planId,System.currentTimeMillis());}
    public JSONObject evaluatePlanChange(JSONObject scope,String planId)throws Exception{return commitments.evaluatePlanChange(scope,planId,System.currentTimeMillis());}
    public JSONObject claimPlanChange(JSONObject scope,String planId,String candidateKey,long leaseMillis)throws Exception {
        JSONObject result=commitments.claimPlanChange(scope,planId,candidateKey,leaseMillis,System.currentTimeMillis());if(result.optBoolean("claimed"))reschedule(context.android);return result;
    }
    public void remoteTaskChanged(JSONObject scope,String taskId)throws Exception {if(commitments.waitsForRemoteTask(scope,taskId))commitmentsChanged();}
    public void authenticatedBusinessEventChanged(JSONObject scope)throws Exception{if(commitments.waitsForBusinessEvents(scope))commitmentsChanged();}
    public long nextPlanTaskAt(JSONObject scope,long now)throws Exception {return commitments.nextPlanTaskAt(scope,now);}
    public JSONObject preparePlanTask(JSONObject scope,String planId,long planRevision,String stepId)throws Exception {JSONObject result=commitments.preparePlanTask(scope,planId,planRevision,stepId,System.currentTimeMillis());commitmentsChanged();return result;}
    public JSONObject planTaskRequest(JSONObject scope,String planId,String stepId,String occurrenceId)throws Exception {return commitments.planTaskRequest(scope,planId,stepId,occurrenceId,System.currentTimeMillis());}
    public JSONObject refreshPlanTasks(JSONObject scope,String planId)throws Exception {JSONObject result=commitments.refreshPlanTasks(scope,planId,System.currentTimeMillis());commitmentsChanged();return result;}
    public JSONObject bindPlanTask(JSONObject scope,String planId,String stepId,String occurrenceId)throws Exception {JSONObject result=commitments.bindPlanTask(scope,planId,stepId,occurrenceId,System.currentTimeMillis());commitmentsChanged();return result;}
    private JSONObject handleCommitment(String command,JSONObject input)throws Exception {
        // New commitment APIs require an explicit host-derived scope; no private fallback.
        JSONObject scope=input.getJSONObject("scope");long now=System.currentTimeMillis();JSONObject record;
        switch(command){
            case "listCommitments":return new JSONObject().put("commitments",commitments.list(scope,now));
            case "getCommitment":return new JSONObject().put("commitment",commitments.get(scope,input.getString("id"),now));
            case "dueCommitments":{
                long limit=input.has("limit")?positiveInteger(input,"limit"):20;
                if(limit>100)throw new IllegalArgumentException("跟进候选数量应为1至100");
                return commitments.due(scope,now,(int)limit);
            }
            case "createCommitment":record=commitments.create(scope,input.getJSONObject("commitment"),now);break;
            case "updateCommitment":record=commitments.update(scope,input.getString("id"),positiveInteger(input,"revision"),input.getJSONObject("patch"),now);break;
            case "snoozeCommitment":record=commitments.snooze(scope,input.getString("id"),positiveInteger(input,"revision"),positiveInteger(input,"until"),now);break;
            case "cancelCommitment":record=commitments.cancel(scope,input.getString("id"),positiveInteger(input,"revision"),now);break;
            case "completeCommitment":record=commitments.complete(scope,input.getString("id"),positiveInteger(input,"revision"),input.getJSONObject("evidenceRef"),now);break;
            default:throw new IllegalArgumentException("未知承诺操作");
        }
        commitmentsChanged();return new JSONObject().put("commitment",record);
    }
    private JSONObject handlePlan(String command,JSONObject input)throws Exception {
        JSONObject scope=input.getJSONObject("scope");long now=System.currentTimeMillis();
        if(command.equals("listPlans"))return new JSONObject().put("plans",commitments.listPlans(scope,now));
        if(command.equals("getPlan"))return new JSONObject().put("plan",commitments.getPlan(scope,input.getString("id"),now));
        JSONObject plan=command.equals("createPlan")?commitments.createPlan(scope,input.getJSONObject("plan"),now):
                commitments.cancelPlan(scope,input.getString("id"),positiveInteger(input,"revision"),now);
        commitmentsChanged();return new JSONObject().put("plan",plan);
    }
    private void commitmentsChanged()throws Exception {
        // Do not broadcast private commitment text or another owner's scope in global snapshots.
        context.emit(id(),new JSONObject().put("event","commitments_changed"));reschedule(context.android);
    }
    private static long positiveInteger(JSONObject input,String key){
        Object value=input.opt(key);
        if(!(value instanceof Number)||((Number)value).longValue()<=0||((Number)value).doubleValue()!=((Number)value).longValue())
            throw new IllegalArgumentException(key+"必须为正整数");
        return ((Number)value).longValue();
    }

    /** Worker-thread startup/boot/time-change restore. Only reads host next time; never runs due. */
    public static void restore(Context context)throws Exception {restore(context,true);}
    private static void restore(Context context,boolean queryHost)throws Exception {
        Context app=context.getApplicationContext();AutomationBook book=open(app);recover(book);
        ensureChannel(app);schedule(app,book,queryHost);
    }
    private static void recover(AutomationBook book)throws Exception {
        synchronized(RESTORE_LOCK){if(!restored){book.recoverInterrupted(System.currentTimeMillis());restored=true;}}
    }
    /**
     * Call on a host worker AFTER Application fields/provider are ready, on every process start.
     * This replans the same alarm. Calls from a host callback are deferred to its enclosing pass.
     */
    public static void reschedule(Context context)throws Exception {schedule(context.getApplicationContext(),open(context.getApplicationContext()));}
    static JSONArray deliver(Context context)throws Exception {
        Context app=context.getApplicationContext();AutomationModule mounted=app instanceof AlarmHostProvider?((AlarmHostProvider)app).originalReminderModule():null;return deliver(app,mounted==null?open(app):mounted.book,mounted);
    }
    private static JSONArray deliver(Context context,AutomationBook book,AutomationModule mounted)throws Exception {
        Context app=context.getApplicationContext();long maintenanceAt=System.currentTimeMillis();
        HOST_GATE.call(()->{AlarmHost host=alarmHost(app);if(host!=null)host.onMaintenanceDue(maintenanceAt);return Boolean.TRUE;},maintenanceAt,HOST_DUE_TIMEOUT);
        if(!executionAllowed(app)){schedule(app,book);return new JSONArray();}
        recover(book);ensureChannel(app);if(mounted!=null)mounted.reconcileNativeScheduleSources();
        JSONArray results=new JSONArray();
        try{
            book.recheckConditions(System.currentTimeMillis());
            JSONArray claimed=book.claimDue(System.currentTimeMillis(),10);
            for(int i=0;i<claimed.length();i++){
                if(!executionAllowed(app))break;
                String id=claimed.getJSONObject(i).getString("id");
                JSONObject origin=book.receiptNativeOrigin(id);
                if(origin==null)book.dispatchReceipt(id,receipt->postNotification(app,receipt),System.currentTimeMillis());
                else{
                    // Enter the existing source domain BEFORE the Book lock. The original
                    // source/Tasks transaction covers the actual local notification, not
                    // just an empty preflight which could outlive a completed deletion.
                    final boolean[] callbackEntered={false},notificationEntered={false};
                    try{
                        if(mounted==null)throw new ScheduleSourceState.Unavailable();
                        mounted.withNativeScheduleSource(origin,()->{
                            callbackEntered[0]=true;
                            return book.dispatchReceipt(id,receipt->{notificationEntered[0]=true;return postNotification(app,receipt);},System.currentTimeMillis());
                        });
                    }catch(Exception unavailable){
                        if(callbackEntered[0]){
                            // A postcheck or final Book write can fail AFTER notify. Never
                            // defer that occurrence. Keep a saved native result, or retain
                            // unknown; an unwriteable dispatching row cold-recovers unknown.
                            if(notificationEntered[0])try{book.delivered(id,"delivery_unknown","原通知可能已提交；只核对本次原发生，未重新派发",System.currentTimeMillis());}catch(Exception unrecorded){unavailable.addSuppressed(unrecorded);}
                            throw unavailable;
                        }
                        if(unavailable instanceof ScheduleSourceState.Invalidated)book.revokeNativeScheduleOrigin(origin,System.currentTimeMillis());
                        else{book.nativeScheduleSourcePending(origin,true,System.currentTimeMillis());book.deferSourceUnavailableReceipt(id,System.currentTimeMillis());}
                        clearSourceNotifications(app,book);continue;
                    }
                }
                if(mounted!=null)mounted.reconcileNativeScheduleSources();
                JSONArray retained=book.receipts();for(int j=0;j<retained.length();j++)if(id.equals(retained.getJSONObject(j).getString("id"))){results.put(retained.getJSONObject(j));break;}
            }
        }finally{
            // Legacy notification failures must not bypass the host pass or final reschedule.
            long now=System.currentTimeMillis();
            if(executionAllowed(app))HOST_GATE.call(()->{AlarmHost host=alarmHost(app);if(host!=null&&executionAllowed(app))host.onAutomationDue(now,20);return Boolean.TRUE;},now,HOST_DUE_TIMEOUT);
            schedule(app,book);
        }
        return results;
    }
    private static AlarmHost alarmHost(Context app){return app instanceof AlarmHostProvider?((AlarmHostProvider)app).automationAlarmHost():null;}
    private static boolean executionAllowed(Context context){Context app=context.getApplicationContext();return !(app instanceof AlarmHostProvider)||((AlarmHostProvider)app).executionAllowed();}
    static AutomationBook open(Context context)throws Exception{return new AutomationBook(new File(context.getFilesDir(),"automation/rules.json"),null,new AndroidAutomationConditions(context));}
    private static PendingIntent alarmIntent(Context context){
        Intent intent=new Intent(context,AutomationReceiver.class).setAction(ALARM_ACTION);
        return PendingIntent.getBroadcast(context,27031,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static boolean schedule(Context context,AutomationBook book)throws Exception {return schedule(context,book,true);}
    private static boolean schedule(Context context,AutomationBook book,boolean queryHost)throws Exception {
        if(HOST_GATE.onCallbackThread())return false; // Never self-wait or recursively enter the host.
        AlarmManager manager=context.getSystemService(AlarmManager.class);if(manager==null)return false;
        final long generation;
        synchronized(SCHEDULE_LOCK){generation=++scheduleGeneration;}
        long now=System.currentTimeMillis(),reminderNext=executionAllowed(context)?book.nextAt():-1,hostNext,maintenanceNext;
        if(queryHost){
            long[] read=HOST_GATE.call(()->{AlarmHost host=alarmHost(context);return host==null?new long[]{-1,-1}:new long[]{executionAllowed(context)?host.nextAutomationAt(now):-1,host.nextMaintenanceAt(now)};},now,HOST_QUERY_TIMEOUT);
            hostNext=read==null?HOST_GATE.retryAt(now):read[0];maintenanceNext=read==null?HOST_GATE.retryAt(now):read[1];
            if(hostNext < -1)hostNext=HOST_GATE.retryAt(now);
            if(maintenanceNext < -1)maintenanceNext=HOST_GATE.retryAt(now);
            // An unchanged overdue candidate cannot generate an immediate busy alarm loop.
            if(hostNext>=0)hostNext=Math.max(now+MIN_HOST_WAKE_DELAY,hostNext);
            if(maintenanceNext>=0)maintenanceNext=Math.max(now+MIN_HOST_WAKE_DELAY,maintenanceNext);
        }else synchronized(SCHEDULE_LOCK){hostNext=lastHostNext;maintenanceNext=lastMaintenanceNext;}
        synchronized(SCHEDULE_LOCK){
            if(generation!=scheduleGeneration)return false; // A newer policy/state replan owns the alarm.
            if(queryHost){lastHostNext=hostNext;lastMaintenanceNext=maintenanceNext;}
            long next=executionAllowed(context)?(reminderNext<0?hostNext:hostNext<0?reminderNext:Math.min(reminderNext,hostNext)):-1;
            if(maintenanceNext>=0)next=next<0?maintenanceNext:Math.min(next,maintenanceNext);
            PendingIntent intent=alarmIntent(context);manager.cancel(intent);
            if(next>=0)manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Math.max(System.currentTimeMillis()+1000,next),intent);
            return true;
        }
    }
    private static void ensureChannel(Context context){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager!=null)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"私人提醒",NotificationManager.IMPORTANCE_DEFAULT));
    }
    private static Intent notificationIntent(Context context,String id){Intent open=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());if(open==null)open=new Intent().setClassName(context.getPackageName(),context.getPackageName()+".MainActivity");return open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).setData(Uri.parse("pocketengine://automation/"+id));}
    private static JSONObject postNotification(Context context,JSONObject receipt)throws Exception {
        if(!executionAllowed(context))throw new SecurityException("总停用已禁止本次新通知；原回执保留待核对");
        ensureChannel(context);NotificationManager manager=context.getSystemService(NotificationManager.class);
        NotificationChannel channel=manager==null?null:manager.getNotificationChannel(CHANNEL);
        if(manager==null||!manager.areNotificationsEnabled()||channel==null||channel.getImportance()==NotificationManager.IMPORTANCE_NONE)
            return new JSONObject().put("status","blocked_notifications").put("message","通知权限或提醒渠道已关闭；提醒已记入应用，未执行手机目标");
        String id=receipt.getString("id"),kind=receipt.getString("kind");
        Intent open=notificationIntent(context,id).putExtra("automationRuleId",receipt.getString("ruleId")).putExtra("automationOccurrence",id);
        PendingIntent tap=PendingIntent.getActivity(context,notificationId(id),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean phoneGoal=kind.equals("phone_goal");String text=phoneGoal?"目标已到时间。点此查看并由你开始手机操作。":"点击回到私人助手查看提醒。";
        Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle(receipt.getString("title")).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        try{manager.notify(notificationId(id),notification);}catch(SecurityException denied){return new JSONObject().put("status","blocked_notifications").put("message","系统未允许发送提醒；未执行手机目标");}
        return new JSONObject().put("status",phoneGoal?"awaiting_user":"notified").put("message",phoneGoal?"已提交提醒通知，等待机主打开并开始目标":"已向系统提交通知；是否被用户阅读尚未确认");
    }
    private static int notificationId(String id){return (id.hashCode()&0x3fffffff)|0x40000000;}
}
