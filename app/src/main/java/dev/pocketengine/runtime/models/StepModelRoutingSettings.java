package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.store.SettingsStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Public exact target opt-in. No credentials, tasks, usage or global model replacement. */
final class StepModelRoutingSettings {
    private final Path file;private final SettingsStore settings;
    StepModelRoutingSettings(SettingsStore settings,Path file){this.settings=settings;this.file=file;}
    synchronized JSONObject snapshot()throws Exception {
        if(!Files.exists(file))return new JSONObject().put("enabled",false).put("target",JSONObject.NULL);
        if(Files.size(file)>4096)throw new IllegalStateException("轻量步骤配置无法核对");JSONObject value=new JSONObject(new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8));
        if(!"pocketworld.step-routing.v1".equals(value.getString("format"))||!(value.opt("enabled") instanceof Boolean))throw new IllegalStateException("轻量步骤配置无法核对");
        if(value.getBoolean("enabled"))ModelTargetRef.fromJson(value.getJSONObject("target"));return value;
    }
    synchronized void save(boolean enabled,ModelTargetRef target)throws Exception {
        if(enabled&&(target==null||target.kind()!=ModelTargetRef.Kind.EXACT))throw new IllegalArgumentException("请为轻量步骤明确选择一个原连接的精确模型");
        if(enabled){ModelConfig config=settings.captureModelTarget(target,"MAIN","APP_OWNER",settings.get());if(config.usesAccount())throw new IllegalArgumentException("轻量步骤首版只选择API模型，账号仍使用原主模型");ModelCatalog.View view=ModelRequestPolicy.resolve(config);if(view==null||!view.facts().toolCalls().known||!view.facts().toolCalls().value)throw new IllegalArgumentException("请先在原模型页核对工具能力，未知能力不启用轻量步骤");}
        JSONObject value=new JSONObject().put("format","pocketworld.step-routing.v1").put("enabled",enabled).put("target",target==null?JSONObject.NULL:target.toJson());
        Path parent=file.toAbsolutePath().getParent();Files.createDirectories(parent);Path temporary=Files.createTempFile(parent,"step-route-",".tmp");
        try{Files.write(temporary,value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.move(temporary,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temporary);}
    }
    ModelConfig capture(ModelConfig main)throws Exception {
        if(main.routePlan!=null&&(!"MAIN".equals(main.routePlan.purpose)||!"APP_OWNER".equals(main.routePlan.audience)))return null;
        JSONObject choice=snapshot();if(!choice.optBoolean("enabled"))return null;
        ModelConfig selected=settings.captureModelTarget(ModelTargetRef.fromJson(choice.getJSONObject("target")),"MAIN","APP_OWNER",main);
        ModelRoutePlan.Candidate member=null;
        if(main.routePlan!=null&&main.routePlan.groupRevision>0){
            for(ModelRoutePlan.Candidate candidate:main.routePlan.candidates)if(candidate.config()!=null&&candidate.exclusion().isEmpty()&&candidate.config().model.equals(selected.model)&&java.util.Objects.equals(candidate.config().capabilityBinding,selected.capabilityBinding)){member=candidate;break;}
            if(member==null)return null;
            selected=member.config().withRoutePlan(null).withLocalObservation(main.localCaptureAllowed,main.localOcrAllowed);
        }
        ModelCatalog.View view=ModelRequestPolicy.resolve(selected);if(selected.usesAccount()||view==null||!view.facts().toolCalls().known||!view.facts().toolCalls().value)return null;
        if(main.sendScreenshots&&(!view.facts().imageInput().known||!view.facts().imageInput().value))return null;
        ModelConfig light=selected.withPurposePlans(main.purposePlans);
        if(member!=null)light=light.withRoutePlan(new ModelRoutePlan(main.routePlan.requestedAlias,main.routePlan.groupRevision,main.routePlan.purpose,main.routePlan.audience,
            java.util.List.of(new ModelRoutePlan.Candidate(member.candidateId(),light.withRoutePlan(null),"")),1,0));
        return light;
    }
}
