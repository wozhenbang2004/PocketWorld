package dev.pocketengine.features.controlplane;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.SettingsStore;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner management/preflight and original-run route sources. Import/save/select creates no run, approval, or tool effect. */
public final class NativeWorkProfileHost {
    private final SettingsStore settings;
    private final WorkProfileResolver.Sources sources;
    public NativeWorkProfileHost(SettingsStore settings, WorkProfileResolver.Sources sources) {
        this.settings = java.util.Objects.requireNonNull(settings); this.sources = java.util.Objects.requireNonNull(sources);
    }
    public Page newOwnerPage() { return new Page(); }
    public void requireOwnedSelection(Selection selected, RunControl original) throws Exception {
        if (selected == null || selected.issuer != this) throw new SecurityException("original_native_work_profile_owner_required"); selected.checkCurrent(original);
    }
    public void claimOwnerExecutionStart(Selection selected,RunControl original)throws Exception {
        if(selected==null||selected.issuer!=this)throw new SecurityException("original_native_work_profile_owner_required");selected.claimOwnerStart(original);selected.checkCurrent(original);
    }
    /** Reuses the original run's frozen metadata. This only removes tools; it never starts/resumes a run. */
    public WorkProfileToolSelection toolSelectionForRun(dev.pocketengine.store.TaskStore tasks, String id, String run, int epoch, RunControl original) throws Exception {
        JSONObject frozen = tasks.workProfileRunSnapshot(id, run, epoch); String digest = frozen.getString("resolutionDigest");
        dev.pocketengine.core.CapabilitySnapshot.CurrentCheck full = () -> tasks.checkWorkProfileRun(id, run, epoch, digest, original);
        dev.pocketengine.core.CapabilitySnapshot.CurrentCheck binding = () -> tasks.checkWorkProfileCapabilityBinding(id,run,epoch,digest,original);
        dev.pocketengine.core.CapabilitySnapshot.CurrentCheck source = () -> WorkProfileResolver.checkStoredAssetSourcesCurrent(frozen,sources,original);
        WorkProfileToolSelection policy = new WorkProfileToolSelection(frozen, () -> { full.check(); source.check(); }, binding,source,
            (tool,operation,args,supplied)->{if(supplied!=original)throw new SecurityException("original_profile_control_required");tasks.checkWorkProfilePreparedTool(id,run,epoch,digest,tool,operation,args,original);});
        policy.checkCurrent(); return policy;
    }
    /** Active original run only. This chooses a route, never a gateway/target approval or new run. */
    public RelaySource relayForRun(dev.pocketengine.store.TaskStore tasks,String task,String run,int epoch,RunControl original)throws Exception {
        if(original==null||!original.hasHostRun()||!original.matchesHostRun(task,run,epoch))throw new SecurityException("original_work_profile_relay_control_required");
        original.check();JSONObject frozen=tasks.workProfileRunSnapshot(task,run,epoch);String digest=frozen.getString("resolutionDigest");tasks.checkWorkProfileRun(task,run,epoch,digest,original);
        JSONObject target=frozen.getJSONObject("definition").getJSONObject("modelPolicies").optJSONObject("RELAY");if(target==null)return null;
        ModelConfig captured=sources.model(dev.pocketengine.runtime.models.ModelTargetRef.fromJson(target),"RELAY",original);
        WorkProfileResolver.requireStoredModelIdentity(frozen,"RELAY",captured);
        if(captured.usesAccount())throw new SecurityException("CHATGPT_PLAN_APP_ONLY");
        RelaySource selected=new RelaySource(this,tasks,task,run,epoch,original,frozen,captured);selected.checkCurrent();return selected;
    }
    /** Read-only source review for the existing native gateway-only continuation. No fake owner
     * Page, new task or execution authority is created; selection requires its actual native approval. */
    public GatewayReview reviewGatewayParent(dev.pocketengine.store.TaskStore tasks,dev.pocketengine.store.TaskStore.GatewayParentRunReview review)throws Exception {
        if(review==null||!review.issuedBy(tasks))throw new SecurityException("original_gateway_profile_review_required");review.checkCurrent();JSONObject scope=review.view(),task=tasks.get(scope.getString("parentTaskId"),false);
        if(task.optJSONObject("workProfileRef")==null){if(task.optJSONObject("workProfileIntent")!=null)throw new SecurityException("待启动工作配置须先回原任务入口审阅；仅网关恢复不能创建首次授权");return null;}
        JSONObject frozen=tasks.workProfileRunSnapshot(scope.getString("parentTaskId"),scope.getString("previousParentRunId"),scope.getInt("previousParentEpoch"));RunControl observation=new RunControl();
        GatewayReview inspected=new GatewayReview(this,tasks,review,frozen,WorkProfileResolver.resolveStored(frozen,sources,observation),observation);inspected.checkCurrent();return inspected;
    }
    public static final class GatewayReview {
        private final NativeWorkProfileHost issuer;private final dev.pocketengine.store.TaskStore tasks;private final dev.pocketengine.store.TaskStore.GatewayParentRunReview review;
        private final String frozen,fingerprint;private final WorkProfileResolver.Resolution resolution;private final RunControl observation;private boolean selected;
        private GatewayReview(NativeWorkProfileHost issuer,dev.pocketengine.store.TaskStore tasks,dev.pocketengine.store.TaskStore.GatewayParentRunReview review,JSONObject frozen,WorkProfileResolver.Resolution resolution,RunControl observation)throws Exception{this.issuer=issuer;this.tasks=tasks;this.review=review;this.frozen=frozen.toString();fingerprint=WorkProfile.fingerprint(frozen);this.resolution=resolution;this.observation=observation;}
        public void checkCurrent()throws Exception {
            review.checkCurrent();JSONObject saved=new JSONObject(frozen);JSONObject current=tasks.workProfileRunSnapshot(saved.getString("taskId"),saved.getString("runId"),saved.getInt("epoch"));
            if(!fingerprint.equals(WorkProfile.fingerprint(current)))throw new SecurityException("original_gateway_profile_snapshot_changed");resolution.checkCurrent(observation);
        }
        public ModelConfig relayModel()throws Exception{checkCurrent();return resolution.models().get("RELAY");}
        public JSONObject view()throws Exception{checkCurrent();JSONObject saved=new JSONObject(frozen);return new JSONObject().put("profileId",saved.getString("profileId")).put("profileRevision",saved.getLong("profileRevision")).put("resolutionDigest",saved.getString("resolutionDigest")).put("relaySourceKind",resolution.models().containsKey("RELAY")?"frozen_original_work_profile_RELAY":"original_host_RELAY").put("configurationApplied",false).put("executionAuthorized",false);}
        public Selection select(dev.pocketengine.features.RemoteOwnerWorkflows.GatewayParentRunApproval approval,RunControl original)throws Exception {
            if(approval==null||!approval.issuedBy(tasks)||approval.review()!=review||original==null||original.hasHostRun())throw new SecurityException("native_gateway_profile_approval_required");
            approval.checkCurrent();checkCurrent();original.check();JSONObject saved=new JSONObject(frozen);
            synchronized(this){if(selected)throw new SecurityException("gateway_profile_review_already_selected");selected=true;return new Selection(issuer,resolution,original,new TaskReview(saved.getString("taskId"),saved.getString("runId"),saved.getInt("epoch"),fingerprint));}
        }
    }
    /** Opaque source from this host + verified original TaskStore snapshot. No constructor or JSON
     * factory is exposed; the original gateway owner still approves the exact child/target scope. */
    public static final class RelaySource implements dev.pocketengine.features.proxy.LoopbackRelay.ConfigSource {
        private final NativeWorkProfileHost issuer;private final dev.pocketengine.store.TaskStore tasks;private final RunControl original;
        private final String task,run,frozen,digest;private final int epoch;private final long revision,pause;private final ModelConfig captured;
        private RelaySource(NativeWorkProfileHost issuer,dev.pocketengine.store.TaskStore tasks,String task,String run,int epoch,RunControl original,JSONObject frozen,ModelConfig captured)throws Exception {
            this.issuer=issuer;this.tasks=tasks;this.task=task;this.run=run;this.epoch=epoch;this.original=original;this.frozen=frozen.toString();this.captured=captured;
            digest=frozen.getString("resolutionDigest");revision=frozen.getLong("profileRevision");pause=original.pauseRevision();
        }
        private void bindingCurrent()throws Exception {
            original.check();if(original.isPaused()||original.pauseRevision()!=pause||!original.matchesHostRun(task,run,epoch))throw new SecurityException("original_work_profile_relay_run_changed");
            tasks.checkWorkProfileRun(task,run,epoch,digest,original);
        }
        public void requireOriginal(dev.pocketengine.store.TaskStore actual,RunControl control)throws Exception {
            if(actual!=tasks||control!=original)throw new SecurityException("original_work_profile_relay_source_mismatch");bindingCurrent();
        }
        public void checkCurrent()throws Exception {
            bindingCurrent();issuer.sources.checkModel(captured,"RELAY",original);
            WorkProfileResolver.checkStoredAssetSourcesCurrent(new JSONObject(frozen),issuer.sources,original);bindingCurrent();
        }
        @Override public ModelConfig get()throws Exception{checkCurrent();return captured;}
        @Override public long selectionRevision()throws Exception{bindingCurrent();return revision;}
        public JSONObject view()throws Exception {
            bindingCurrent();JSONObject saved=new JSONObject(frozen);
            return new JSONObject().put("sourceKind","frozen_original_work_profile_RELAY").put("profileId",saved.getString("profileId")).put("profileRevision",revision)
                .put("resolutionDigest",digest).put("model",saved.getJSONObject("modelPolicies").getJSONObject("RELAY"))
                .put("purpose","RELAY").put("audience","EXTERNAL_RELAY").put("configurationApplied",false).put("runtimeVerified",false);
        }
    }
    /** Planning-only defaults for an actual native team proposal. The opaque plan supplies the
     * original parent and selected slots; this is not a start token, grant or second team plan.
     * The existing TeamParentPlan/owner approval must still pin these facts before execution. */
    public MemberPlanModels memberModelsForPlan(dev.pocketengine.store.TaskStore tasks,
            dev.pocketengine.features.teams.TeamParentPlan plan, RunControl original) throws Exception {
        if (plan == null || original == null || !plan.origin().issuedBy(tasks)) throw new SecurityException("original_work_profile_member_plan_required");
        plan.checkCurrent();
        JSONObject parent = plan.origin().view();
        String task = parent.getString("parentTaskId"), run = parent.getString("parentRunId");
        int epoch = parent.getInt("parentEpoch");
        if (!original.matchesHostRun(task, run, epoch)) throw new SecurityException("original_work_profile_member_control_required");
        original.check();
        JSONObject frozen = tasks.workProfileRunSnapshot(task, run, epoch);
        tasks.checkTeamWorkProfilePlanBinding(plan.origin(), frozen.getString("resolutionDigest"), original);
        MemberPlanModels captured = new MemberPlanModels(this, tasks, plan, original, frozen);
        captured.checkCurrent();
        return captured;
    }

    /** Credential-bearing captures stay transient. It is intentionally valid only while the
     * original planning invocation is current, not after its operation receipts or a new epoch.
     * No model JSON can construct this object or turn its public view into approval. */
    public static final class MemberPlanModels {
        private final NativeWorkProfileHost issuer;
        private final dev.pocketengine.store.TaskStore tasks;
        private final dev.pocketengine.features.teams.TeamParentPlan plan;
        private final RunControl original;
        private final String task, run, frozen, digest, teamId, teamDigest, publicView;
        private final int epoch;
        private final long pause;
        private final Map<String, ModelConfig> models;
        private final Map<String, String> purposes;
        private final ModelConfig baseModel;

        private MemberPlanModels(NativeWorkProfileHost issuer, dev.pocketengine.store.TaskStore tasks,
                dev.pocketengine.features.teams.TeamParentPlan plan, RunControl original,
                JSONObject frozen) throws Exception {
            this.issuer = issuer; this.tasks = tasks; this.plan = plan; this.original = original;
            this.frozen = frozen.toString(); digest = frozen.getString("resolutionDigest");
            JSONObject parent = plan.origin().view(), proposed = plan.view();
            task = parent.getString("parentTaskId"); run = parent.getString("parentRunId");
            epoch = parent.getInt("parentEpoch"); pause = original.pauseRevision();
            JSONObject selected = proposed.getJSONObject("definition"); teamId = selected.getString("id");
            JSONObject actual = issuer.sources.team(teamId, original);
            if (actual == null || !teamId.equals(actual.optString("id"))) throw new SecurityException("original_work_profile_member_team_missing");
            teamDigest = WorkProfile.fingerprint(actual);
            if (!teamDigest.equals(proposed.getString("selectedDefinitionHash"))) throw new SecurityException("original_work_profile_member_team_changed");
            Map<String,ModelConfig> frozenModels = WorkProfileResolver.resolveStored(frozen,issuer.sources,original).models();
            ModelConfig global = frozenModels.get("_GLOBAL"), summary = frozenModels.get("SUMMARY");
            if(global==null||summary==null)throw new SecurityException("原工作配置没有启动默认模型快照，请重新在原任务审阅；未借用MAIN或当前默认");
            baseModel=global.withPurposePlans(Map.of("SUMMARY",summary.routePlan==null?dev.pocketengine.runtime.models.ModelRoutePlan.exact(summary,"SUMMARY","APP_OWNER"):summary.routePlan));
            JSONObject definition = frozen.getJSONObject("definition"), template = definition.optJSONObject("teamTemplateRef");
            if (template != null) {
                boolean included = false;
                JSONArray items = frozen.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if ("team".equals(item.optString("kind")) && template.getString("id").equals(item.optString("id"))) included = item.optBoolean("included");
                }
                if (!included || !teamId.equals(template.getString("id")) || !teamDigest.equals(template.getString("digest"))) throw new SecurityException("original_work_profile_member_template_not_selected");
            }
            JSONObject policies = definition.getJSONObject("modelPolicies");
            Map<String, ModelConfig> captures = new java.util.LinkedHashMap<>();
            Map<String, String> selectedPurposes = new java.util.LinkedHashMap<>();
            java.util.Set<String> used = new java.util.HashSet<>(), members = new java.util.HashSet<>();
            JSONArray rows = new JSONArray(), selectedMembers = selected.getJSONArray("members");
            for (int i = 0; i < selectedMembers.length(); i++) {
                JSONObject member = selectedMembers.getJSONObject(i);
                String id = member.getString("id");
                if (!members.add(id) || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new SecurityException("original_work_profile_member_slot_changed");
                JSONObject nativeMember = dev.pocketengine.features.teams.AgentSlot.normalize(
                        dev.pocketengine.features.teams.TeamWorkPlan.find(actual.getJSONArray("members"), id), i);
                if (!"model".equals(nativeMember.optString("runner", "model")) || !"phone".equals(nativeMember.opt("device"))) throw new SecurityException("work_profile_member_requires_original_local_model_slot");
                // Assignment/path fields are deliberately changed by the native plan. Model,
                // runner and device identity must still be the actual saved slot, not new JSON.
                for (String key : new String[]{"runner", "device", "model", "modelTarget"}) {
                    if (!WorkProfile.fingerprint(nativeMember.opt(key)).equals(WorkProfile.fingerprint(member.opt(key)))) throw new SecurityException("original_work_profile_member_slot_changed:" + key);
                }
                String purpose = id.length() <= 60 && policies.has("MEMBER." + id) ? "MEMBER." + id
                        : policies.has("MEMBER") ? "MEMBER" : null;
                boolean explicit = nativeMember.optJSONObject("modelTarget") != null || !nativeMember.optString("model").isEmpty();
                JSONObject row = new JSONObject().put("memberId", id).put("profileDefaultApplied", false);
                if (explicit) row.put("sourceKind", "original_explicit_member");
                else if (purpose == null) row.put("sourceKind", "original_task_or_captured_global");
                else {
                    ModelConfig model = frozenModels.get(purpose);
                    WorkProfileResolver.requireStoredModelIdentity(frozen, purpose, model);
                    captures.put(id, model); selectedPurposes.put(id, purpose); used.add(purpose);
                    row.put("sourceKind", "frozen_work_profile_member_default").put("purpose", purpose)
                            .put("modelIdentity", new JSONObject(frozen.getJSONObject("modelPolicies").getJSONObject(purpose).toString()));
                }
                rows.put(row);
            }
            JSONArray unused = new JSONArray();
            for (String purpose : WorkProfile.keys(policies)) if ((purpose.equals("MEMBER") || purpose.startsWith("MEMBER.")) && !used.contains(purpose)) unused.put(purpose);
            models = Map.copyOf(captures); purposes = Map.copyOf(selectedPurposes);
            publicView = new JSONObject().put("sourceKind", "native_team_plan_work_profile_defaults")
                    .put("profileId", frozen.getString("profileId")).put("profileRevision", frozen.getLong("profileRevision"))
                    .put("resolutionDigest", digest).put("teamId", teamId).put("selectedDefinitionHash", teamDigest)
                    .put("members", rows).put("unusedProfilePurposes", unused).put("globalModelDefault",frozen.getJSONObject("modelPolicies").getJSONObject("_GLOBAL")).put("summaryModel",frozen.getJSONObject("modelPolicies").getJSONObject("SUMMARY")).put("planningOnly", true)
                    .put("configurationApplied", false).put("executionAuthorized", false).toString();
        }

        public void checkCurrent() throws Exception {
            original.check(); plan.checkCurrent();
            if (original.isPaused() || original.pauseRevision() != pause || !original.matchesHostRun(task, run, epoch)) throw new SecurityException("original_work_profile_member_plan_changed");
            tasks.checkTeamWorkProfilePlanBinding(plan.origin(), digest, original);
            JSONObject actual = issuer.sources.team(teamId, original);
            if (actual == null || !teamDigest.equals(WorkProfile.fingerprint(actual))) throw new SecurityException("original_work_profile_member_team_changed");
            for (String id : models.keySet()) issuer.sources.checkModel(models.get(id), purposes.get(id), original);
            issuer.sources.checkModel(baseModel,"DEFAULT",original);issuer.sources.checkModel(baseModel.forPurpose("SUMMARY"),"SUMMARY",original);
            WorkProfileResolver.checkStoredAssetSourcesCurrent(new JSONObject(frozen), issuer.sources, original);
            original.check(); plan.checkCurrent();
            tasks.checkTeamWorkProfilePlanBinding(plan.origin(), digest, original);
        }
        public void requirePlan(dev.pocketengine.features.teams.TeamParentPlan exactPlan)throws Exception {
            if(exactPlan!=plan)throw new SecurityException("original_work_profile_member_plan_source_mismatch");checkCurrent();
        }
        public void requireOriginal(dev.pocketengine.store.TaskStore actual,
                dev.pocketengine.features.teams.TeamParentPlan exactPlan, RunControl control) throws Exception {
            if (actual != tasks || exactPlan != plan || control != original) throw new SecurityException("original_work_profile_member_plan_source_mismatch");
            checkCurrent();
        }
        /** Native TeamParentPlan only; no arbitrary member JSON may retain or inject a pin. */
        public ModelConfig defaultForMember(JSONObject member) throws Exception {
            checkCurrent();String id=member.getString("id");
            JSONObject actual=dev.pocketengine.features.teams.TeamWorkPlan.find(plan.view().getJSONObject("definition").getJSONArray("members"),id);
            for(String key:new String[]{"id","runner","device","model","modelTarget"})if(!WorkProfile.fingerprint(actual.opt(key)).equals(WorkProfile.fingerprint(member.opt(key))))throw new SecurityException("original_work_profile_member_slot_changed:"+key);
            if(member.has("memberModelSelection"))throw new SecurityException("原planning成员不能夹带模型pin");
            return models.get(id);
        }
        public dev.pocketengine.features.teams.TeamMemberModels.Selection captureMember(JSONObject member)throws Exception {
            checkCurrent();return dev.pocketengine.features.teams.TeamMemberModels.capture(member,baseModel,(ref,purpose,global)->issuer.sources.model(ref,purpose,original),this);
        }
        public ModelConfig baseModel()throws Exception{checkCurrent();return baseModel;}
        public Map<String, ModelConfig> models() throws Exception { checkCurrent(); return models; }
        public JSONObject view() throws Exception { checkCurrent(); return new JSONObject(publicView); }
    }
    /** Original owner-approved start only. Reconstruct every native model pin before the existing
     * Coordinator dispatch; after that the original member/control/pool and Gateway remain authority. */
    public MemberStartModels memberModelsForStart(dev.pocketengine.store.TaskStore tasks,
            dev.pocketengine.store.TaskStore.TeamParentStart approved,RunControl original)throws Exception {
        if(approved==null||original==null||!original.hasHostRun()||!approved.issuedBy(tasks))throw new SecurityException("original_work_profile_member_start_required");
        approved.checkCurrent();return new MemberStartModels(this,tasks,approved,original);
    }
    public static final class MemberStartModels {
        private final NativeWorkProfileHost issuer;private final dev.pocketengine.store.TaskStore tasks;
        private final dev.pocketengine.store.TaskStore.TeamParentStart approved;private final RunControl original;
        private final String frozen,digest,teamId,teamDigest,actualTeam;private final ModelConfig base;
        private final Map<String,ModelConfig> defaults;private final Map<String,String> purposes;
        private MemberStartModels(NativeWorkProfileHost issuer,dev.pocketengine.store.TaskStore tasks,
                dev.pocketengine.store.TaskStore.TeamParentStart approved,RunControl original)throws Exception {
            this.issuer=issuer;this.tasks=tasks;this.approved=approved;this.original=original;
            JSONObject start=approved.view(),scope=start.getJSONObject("scope"),definition=start.getJSONObject("definition");
            JSONObject marker=definition.getJSONObject("nativeWorkProfileModels");
            JSONObject saved=tasks.workProfileRunSnapshot(scope.getString("parentTaskId"),scope.getString("parentRunId"),original.hostRunIdentity().epoch());
            frozen=saved.toString();digest=saved.getString("resolutionDigest");
            if(!digest.equals(marker.getString("resolutionDigest"))||!saved.getString("profileId").equals(marker.getString("profileId"))||saved.getLong("profileRevision")!=marker.getLong("profileRevision"))throw new SecurityException("原团队计划不属于本次冻结工作配置");
            tasks.checkTeamWorkProfileStartBinding(approved,digest,original);
            teamId=marker.getString("teamId");teamDigest=marker.getString("selectedDefinitionHash");
            JSONObject team=issuer.sources.team(teamId,original);
            if(team==null||!teamDigest.equals(WorkProfile.fingerprint(team)))throw new SecurityException("原团队模板已改变，请重新提出准确计划");
            actualTeam=team.toString();Map<String,ModelConfig> captured=WorkProfileResolver.resolveStored(saved,issuer.sources,original).models();
            ModelConfig global=captured.get("_GLOBAL"),summary=captured.get("SUMMARY");if(global==null||summary==null)throw new SecurityException("原计划没有启动时默认模型，不使用当前默认或MAIN替代");
            base=global.withPurposePlans(Map.of("SUMMARY",summary.routePlan==null?dev.pocketengine.runtime.models.ModelRoutePlan.exact(summary,"SUMMARY","APP_OWNER"):summary.routePlan));
            if(!WorkProfile.fingerprint(marker.getJSONObject("globalModelDefault")).equals(WorkProfile.fingerprint(saved.getJSONObject("modelPolicies").getJSONObject("_GLOBAL")))||!WorkProfile.fingerprint(marker.getJSONObject("summaryModel")).equals(WorkProfile.fingerprint(saved.getJSONObject("modelPolicies").getJSONObject("SUMMARY"))))throw new SecurityException("原团队默认或摘要模型pin已改变");
            Map<String,ModelConfig> selectedDefaults=new java.util.LinkedHashMap<>();Map<String,String> selectedPurposes=new java.util.LinkedHashMap<>();
            JSONObject policies=saved.getJSONObject("definition").getJSONObject("modelPolicies");JSONArray members=definition.getJSONArray("members");
            for(int i=0;i<members.length();i++){
                JSONObject member=members.getJSONObject(i),nativeMember=dev.pocketengine.features.teams.AgentSlot.normalize(dev.pocketengine.features.teams.TeamWorkPlan.find(team.getJSONArray("members"),member.getString("id")),i);
                if(!"model".equals(nativeMember.getString("runner"))||!"phone".equals(nativeMember.opt("device")))throw new SecurityException("工作配置仅接原本机模型成员");
                for(String key:new String[]{"runner","device","modelTarget"})if(!WorkProfile.fingerprint(nativeMember.opt(key)).equals(WorkProfile.fingerprint(member.opt(key))))throw new SecurityException("原已批slot身份已改变:"+key);
                String id=member.getString("id"),purpose=id.length()<=60&&policies.has("MEMBER."+id)?"MEMBER."+id:policies.has("MEMBER")?"MEMBER":null;
                if(nativeMember.optJSONObject("modelTarget")==null&&nativeMember.optString("model").isEmpty()&&purpose!=null){selectedDefaults.put(id,captured.get(purpose));selectedPurposes.put(id,purpose);}
            }
            defaults=Map.copyOf(selectedDefaults);purposes=Map.copyOf(selectedPurposes);
            for(int i=0;i<members.length();i++){
                JSONObject member=members.getJSONObject(i),nativeMember=dev.pocketengine.features.teams.AgentSlot.normalize(dev.pocketengine.features.teams.TeamWorkPlan.find(team.getJSONArray("members"),member.getString("id")),i);
                var selected=dev.pocketengine.features.teams.TeamMemberModels.capture(nativeMember,base,(ref,purpose,fallback)->issuer.sources.model(ref,purpose,original),this);
                dev.pocketengine.features.teams.TeamMemberModels.requireSame(selected,member.getJSONObject("memberModelSelection"));
                if(!selected.config.model.equals(member.optString("model")))throw new SecurityException("原成员可见模型与冻结pin不符");
            }
            checkCurrent();
        }
        public ModelConfig defaultForMember(JSONObject member)throws Exception {
            checkCurrent();JSONObject expected=dev.pocketengine.features.teams.AgentSlot.normalize(dev.pocketengine.features.teams.TeamWorkPlan.find(new JSONObject(actualTeam).getJSONArray("members"),member.getString("id")),0);
            for(String key:new String[]{"id","runner","device","model","modelTarget"})if(!WorkProfile.fingerprint(expected.opt(key)).equals(WorkProfile.fingerprint(member.opt(key))))throw new SecurityException("original_work_profile_member_slot_changed:"+key);
            if(member.has("memberModelSelection"))throw new SecurityException("原source成员不能夹带模型pin");return defaults.get(member.getString("id"));
        }
        public void checkCurrent()throws Exception {
            original.check();tasks.checkTeamWorkProfileStartBinding(approved,digest,original);
            JSONObject current=issuer.sources.team(teamId,original);if(current==null||!teamDigest.equals(WorkProfile.fingerprint(current)))throw new SecurityException("原团队模板已改变");
            issuer.sources.checkModel(base,"DEFAULT",original);issuer.sources.checkModel(base.forPurpose("SUMMARY"),"SUMMARY",original);
            for(String id:defaults.keySet())issuer.sources.checkModel(defaults.get(id),purposes.get(id),original);
            WorkProfileResolver.checkStoredAssetSourcesCurrent(new JSONObject(frozen),issuer.sources,original);
            approved.checkCurrent();tasks.checkTeamWorkProfileStartBinding(approved,digest,original);
        }
        public ModelConfig baseModel()throws Exception{checkCurrent();return base;}
    }
    private WorkProfile stored(String id, long revision) throws Exception {
        JSONArray rows = settings.workProfilesSnapshot().getJSONArray("profiles");
        for (int i = 0; i < rows.length(); i++) {
            WorkProfile row = WorkProfile.fromJson(rows.getJSONObject(i));
            if (id.equals(row.id)) { if (row.revision != revision) throw new SecurityException("work_profile_definition_changed"); return row; }
        }
        throw new SecurityException("work_profile_definition_missing");
    }
    public final class Page implements AutoCloseable {
        private boolean alive = true, foreground;
        private Review pending;
        private long generation;
        private Page() { }
        public synchronized void setForeground(boolean value) { if (alive && foreground != value) { foreground = value; generation++; pending = null; } }
        private void current(boolean editing) { if (!alive || editing && !foreground) throw new SecurityException("work_profile_owner_page_not_current"); }
        public synchronized JSONObject snapshot() throws Exception { current(false); return settings.workProfilesSnapshot().put("executionAuthorized", false).put("permissionsChanged", false); }
        /** Read-through owner choices, never a second asset/credential store or an implicit approval. */
        public JSONObject choices(RunControl observation) throws Exception {
            final long observed; synchronized(this) { current(false); observed = generation; }
            JSONObject data = sources.choices(observation); synchronized(this) { current(false); if(observed != generation) throw new SecurityException("work_profile_owner_page_changed"); return data; }
        }
        public synchronized JSONObject save(String id, long revision, String name, JSONObject definition) throws Exception {
            current(true); generation++; pending = null; return settings.saveWorkProfile(id, revision, name, definition);
        }
        public synchronized JSONObject remove(String id, long revision) throws Exception {
            current(true); generation++; pending = null; return settings.removeWorkProfile(id, revision);
        }
        public Review review(String id, long revision, RunControl observation) throws Exception {
            final long request; synchronized (this) { current(true); pending = null; request = ++generation; }
            WorkProfile row = stored(id, revision); WorkProfileResolver.Resolution resolution = WorkProfileResolver.resolve(row, sources, observation);
            synchronized (this) { current(true); if(request != generation) throw new SecurityException("work_profile_review_superseded"); if (!row.digest().equals(stored(id, revision).digest())) throw new SecurityException("work_profile_definition_changed"); pending = new Review(this, generation, resolution, () -> { if (!row.digest().equals(stored(id, revision).digest())) throw new SecurityException("work_profile_definition_changed"); }); return pending; }
        }
        /** Explicit recovery of the original run's exact references, even if its reusable definition was removed.
         * Missing/changed sources remain visible blockers; this does not restart or replay the old run. */
        public Review reviewForTask(dev.pocketengine.store.TaskStore tasks, String taskId, String runId, int epoch, RunControl observation) throws Exception {
            final long request; synchronized (this) { current(true); pending = null; request = ++generation; }
            JSONObject original = runId.isEmpty()?tasks.pendingWorkProfileTaskSnapshot(taskId):tasks.workProfileRunSnapshot(taskId, runId, epoch);
            WorkProfileResolver.Resolution resolution = WorkProfileResolver.resolveStored(original, sources, observation);
            String retained = WorkProfile.fingerprint(original);
            synchronized (this) {
                current(true); if (request != generation) throw new SecurityException("work_profile_review_superseded");
                pending = new Review(this, generation, resolution, () -> { if (!retained.equals(WorkProfile.fingerprint(runId.isEmpty()?tasks.pendingWorkProfileTaskSnapshot(taskId):tasks.workProfileRunSnapshot(taskId, runId, epoch)))) throw new SecurityException("original_work_profile_run_changed"); }, new TaskReview(taskId,runId,epoch,retained)); return pending;
            }
        }
        /** Opaque native result. The original TaskStore must separately bind it to the real task/run. */
        public Selection select(Review review, String digest, RunControl observation) throws Exception {
            synchronized (this) {
                current(true); if (review == null || review.page != this || review != pending || review.generation != generation || review.consumed || !review.digest.equals(digest)) throw new SecurityException("current_work_profile_review_required");
            }
            review.resolution.requireReady(); review.resolution.checkCurrent(observation);
            synchronized (this) {
                current(true); if (pending != review || review.generation != generation || review.consumed) throw new SecurityException("current_work_profile_review_required");
                review.currentDefinition.check();
                review.consumed = true; pending = null; return new Selection(NativeWorkProfileHost.this, review.resolution, observation, review.taskReview);
            }
        }
        @Override public synchronized void close() { alive = false; foreground = false; generation++; pending = null; }
    }
    private record TaskReview(String taskId,String runId,int epoch,String snapshotDigest) { }
    @FunctionalInterface private interface CurrentDefinition { void check() throws Exception; }
    public static final class Review {
        public final String digest;
        private final Page page;
        private final long generation;
        private final WorkProfileResolver.Resolution resolution;
        private final CurrentDefinition currentDefinition;
        private final TaskReview taskReview;
        private boolean consumed;
        private Review(Page page, long generation, WorkProfileResolver.Resolution resolution, CurrentDefinition currentDefinition) throws Exception { this(page,generation,resolution,currentDefinition,null); }
        private Review(Page page,long generation,WorkProfileResolver.Resolution resolution,CurrentDefinition currentDefinition,TaskReview taskReview)throws Exception { this.page=page;this.generation=generation;this.resolution=resolution;this.currentDefinition=currentDefinition;this.taskReview=taskReview;digest=resolution.view().getString("resolutionDigest"); }
        public JSONObject view() throws Exception { return resolution.view(); }
    }
    /** Transient exact model configs stay in memory; public snapshot contains no key, secret, or grant. */
    public static final class Selection {
        private final NativeWorkProfileHost issuer;
        private final WorkProfileResolver.Resolution resolution;
        private final RunControl original;
        private final long admissionPauseRevision;
        private String reservedTask, boundRun, intentTask;
        private final TaskReview taskReview;
        private boolean ownerStartClaimed;
        private int boundEpoch;
        private Selection(NativeWorkProfileHost issuer, WorkProfileResolver.Resolution resolution, RunControl original,TaskReview taskReview) { this.issuer = issuer; this.resolution = resolution; this.original = original;this.taskReview=taskReview;admissionPauseRevision = original.pauseRevision(); }
        public JSONObject snapshot() throws Exception { return resolution.view().put("definition", profile().toJson()); }
        public WorkProfile profile() { return resolution.profile(); }
        public Map<String, ModelConfig> models() { return resolution.models(); }
        public void checkCurrent(RunControl observation) throws Exception { if (observation != original) throw new SecurityException("work_profile_control_mismatch"); observation.check(); resolution.checkCurrent(observation); }
        private synchronized void claimOwnerStart(RunControl control)throws Exception {
            if(control!=original||ownerStartClaimed||control.hasHostRun()||control.isPaused()||control.pauseRevision()!=admissionPauseRevision)throw new SecurityException("work_profile_owner_start_already_used_or_changed");control.check();ownerStartClaimed=true;
        }
        /** Non-authority pending-goal metadata; no run/operation is created by reading this. */
        public synchronized JSONObject metadataForNewOwnerIntent(String taskId,RunControl control)throws Exception {
            if(control!=original||intentTask!=null||taskReview!=null||reservedTask!=null||boundRun!=null||control.hasHostRun()||control.isPaused()||control.pauseRevision()!=admissionPauseRevision)throw new SecurityException("work_profile_pending_goal_control_changed");control.check();if(taskId==null||!taskId.matches("[a-f0-9-]{36}"))throw new SecurityException("original_work_profile_task_required");intentTask=taskId;return snapshot();
        }
        /** Pure metadata check inside original SQL admission; existing Profile tasks require that exact task review. */
        public synchronized void requireTaskAdmission(String taskId,String runId,int epoch,String retainedDigest)throws Exception {
            if(intentTask!=null&&!intentTask.equals(taskId))throw new SecurityException("work_profile_pending_goal_mismatch");
            if(taskReview!=null){if(!taskReview.taskId.equals(taskId)||!taskReview.runId.equals(runId)||taskReview.epoch!=epoch||!taskReview.snapshotDigest.equals(retainedDigest))throw new SecurityException("work_profile_original_task_review_changed");}
            else if(retainedDigest!=null&&!(taskId.equals(intentTask)&&runId.isEmpty()))throw new SecurityException("work_profile_original_task_review_required");
        }
        /** Native TaskStore admission only: consumes this owner selection even if admission later fails. */
        public synchronized void reserveForTask(String taskId, RunControl control) throws Exception {
            if (control != original || reservedTask != null || control.hasHostRun() || control.isPaused() || control.pauseRevision() != admissionPauseRevision) throw new SecurityException("work_profile_selection_already_used_or_wrong_control");
            control.check(); if (taskId == null || !taskId.matches("[a-f0-9-]{36}")) throw new SecurityException("original_work_profile_task_required"); reservedTask = taskId;
        }
        /** Immutable data under TaskStore's monitor, with no source lookup or module lock. */
        public synchronized JSONObject metadataForReservedTask(String taskId) throws Exception {
            requireAdmissionCurrent(taskId, original); return snapshot();
        }
        public synchronized void requireAdmissionCurrent(String taskId, RunControl control) throws Exception {
            if (control != original || !taskId.equals(reservedTask) || boundRun != null || control.hasHostRun() || control.isPaused() || control.pauseRevision() != admissionPauseRevision) throw new SecurityException("work_profile_selection_admission_changed"); control.check();
        }
        public synchronized void bindNativeRun(String taskId, String runId, int epoch, RunControl control) throws Exception {
            control.check(); if (control != original || !taskId.equals(reservedTask) || boundRun != null || control.isPaused() || control.pauseRevision() != admissionPauseRevision || !control.matchesHostRun(taskId, runId, epoch)) throw new SecurityException("work_profile_native_run_binding_required");
            boundRun = runId; boundEpoch = epoch;
        }
        public synchronized void requireBoundRun(String taskId, String runId, int epoch, RunControl control) throws Exception {
            if (control != original || !taskId.equals(reservedTask) || !runId.equals(boundRun) || epoch != boundEpoch || !control.matchesHostRun(taskId, runId, epoch)) throw new SecurityException("work_profile_not_original_bound_run");
        }
    }
}
