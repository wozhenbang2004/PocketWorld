package dev.pocketengine.browser;

import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adapter to the one existing SkillBook. Packages are text configuration, never executable scripts or grants. */
public final class BrowserReadRecipeCatalog {
    private final SkillsModule skills;
    private static final Set<String> CAPABILITIES=Set.of("browser_task_observe");
    public BrowserReadRecipeCatalog(SkillsModule skills){this.skills=java.util.Objects.requireNonNull(skills);}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject read(JSONObject ref)throws Exception {
        BrowserReadRecipe.reference(ref);JSONObject result=skills.readForHost(scope(),ref.getString("id"),BrowserReadRecipe.RESOURCE,0,12000,ref.getInt("revision"),ref.getString("digest"),CAPABILITIES);
        if(result.getBoolean("hasMore")||!result.getBoolean("active")||result.getInt("revision")!=ref.getInt("revision")||!result.getString("digest").equals(ref.getString("digest")))throw new SecurityException("原读取配方已停用、更新或超出预算");JSONObject recipe=new JSONObject(result.getString("content"));BrowserReadRecipe.validate(recipe);return recipe;
    }
    public final class Prepared {
        private final JSONObject reference,recipe;private final String fingerprint;
        private Prepared(JSONObject reference,JSONObject recipe)throws Exception {this.reference=new JSONObject(reference.toString());this.recipe=new JSONObject(recipe.toString());fingerprint=TaskStore.capabilityFingerprint(recipe);}
        public JSONObject identity()throws Exception{return new JSONObject(reference.toString()).put("configFingerprint",fingerprint).put("origin",recipe.getString("origin")).put("path",recipe.getString("path")).put("mode",recipe.getString("mode")).put("query",recipe.getString("query")).put("outputFields",new JSONArray(recipe.getJSONArray("outputFields").toString()));}
        public void requireCurrent()throws Exception {if(!fingerprint.equals(TaskStore.capabilityFingerprint(read(reference))))throw new SecurityException("读取配方内容已变化，请重新取得当前版本");}
        public void requirePage(String profileId,String actualOrigin,String url,boolean child)throws Exception {BrowserReadRecipe.pagePrecondition(recipe,profileId,actualOrigin,url,child);}
        public JSONObject project(JSONObject actual)throws Exception {JSONObject result=BrowserReadRecipe.project(recipe,reference,actual);try{requireCurrent();}catch(SecurityException changed){result.remove("recipeOutput");result.getJSONObject("readRecipe").put("status","drift").put("reason","recipe_version_changed").put("ordinaryObserveFallbackRequired",true);result.put("message","读取期间配方版本已变化；仅返回这次真实普通观察，旧配置不再作为当前有效配方，未重试或增加权限。");}return result;}
    }
    public Prepared prepare(String profileId,JSONObject reference,JSONObject request)throws Exception {JSONObject recipe=read(reference);if(!recipe.getString("profileId").equals(profileId))throw new SecurityException("读取配方属于另一份登录资料，未继承其权限");BrowserReadRecipe.matchesRequest(recipe,request);return new Prepared(reference,recipe);}
    public void requireEvidenceCurrent(String profileId,JSONObject evidence)throws Exception {
        BrowserReadRecipe.validateEvidence(evidence);JSONObject ref=new JSONObject().put("id",evidence.getString("id")).put("revision",evidence.getInt("revision")).put("digest",evidence.getString("digest")),recipe=read(ref);
        if(!profileId.equals(recipe.getString("profileId"))||!evidence.getString("configFingerprint").equals(TaskStore.capabilityFingerprint(recipe)))throw new SecurityException("读取配方已变化，未恢复旧配置");
    }
    public JSONArray forHost(String profileId)throws Exception {
        JSONArray rows=skills.catalogueForHost(scope()).getJSONArray("skills"),out=new JSONArray();for(int i=0;i<rows.length()&&out.length()<8;i++){JSONObject skill=rows.getJSONObject(i);JSONObject metadata=skill.optJSONObject("metadata");if(metadata==null||!metadata.optString("browser-read-recipe").equals("1"))continue;JSONObject ref=new JSONObject().put("id",skill.getString("id")).put("revision",skill.getInt("revision")).put("digest",skill.getString("digest"));
            try{JSONObject recipe=read(ref);if(!profileId.equals(recipe.getString("profileId")))continue;out.put(ref.put("mode",recipe.getString("mode")).put("query",recipe.getString("query")).put("origin",recipe.getString("origin")).put("path",recipe.getString("path")).put("outputFields",recipe.getJSONArray("outputFields")).put("readOnly",true).put("grantsAdded",new JSONArray()));}catch(Exception notCurrentRecipe){/* One disabled/drifted package does not turn another resource into executable authority. */}}
        return out;
    }
    /** Visible native owner confirmation only. Install retains original SkillBook revision/digest, disabled by default. */
    public JSONObject saveForOwner(JSONObject actualSavedSource,String literalQuery,boolean enable)throws Exception {
        JSONObject recipe=BrowserReadRecipe.fromSource(actualSavedSource,literalQuery);String name="web-read-"+UUID.randomUUID().toString().substring(0,8),origin=recipe.getString("origin");
        String manifest="---\nname: "+name+"\ndescription: "+JSONObject.quote("Read-only website recipe for "+origin+"; exact current Profile and schema, no cached permissions.")+"\nallowed-tools: browser_task_observe\nmetadata:\n  version: 1\n  browser-read-recipe: 1\n---\nUse references/browser-read.json as non-executable native read configuration. Run only the original browser_task_observe with its current prepared operation, approval/read scope, deadline and source policy. Drift requires ordinary observation; never navigate, submit, accept dialogs or replay writes.\n";
        JSONObject input=new JSONObject().put("scope",scope()).put("manifest",manifest).put("source",recipe.getJSONObject("createdFrom").getString("sourceRef")).put("sourceVersion",recipe.getJSONObject("createdFrom").getString("structuredRevision"))
                .put("requiredCapabilities",new JSONArray().put("browser_task_observe")).put("resources",new JSONObject().put(BrowserReadRecipe.RESOURCE,recipe.toString()));
        JSONObject installed=skills.handle("import",input).getJSONObject("skill");if(enable)installed=skills.handle("setEnabled",new JSONObject().put("scope",scope()).put("id",installed.getString("id")).put("enabled",true).put("acceptedDigest",installed.getString("digest")).put("confirmed",true)).getJSONObject("skill");return new JSONObject().put("skill",installed).put("recipe",recipe).put("executionAuthorized",false);
    }
}
