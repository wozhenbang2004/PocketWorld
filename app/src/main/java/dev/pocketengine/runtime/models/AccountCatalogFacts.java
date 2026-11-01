package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.pocketengine.runtime.models.ModelFacts.*;

/** Selected-token catalog data, not account eligibility, inference success, or a tool grant. */
public final class AccountCatalogFacts {
    public static final String FORMAT="pocketworld.account-catalog-facts.v1";
    public static final String SOURCE="https://api.openai.com/v1/models";
    public final String modelId;
    public final ModelFacts facts;
    public final Fact<List<String>> inputModalities;
    public final long observedAtMillis;

    private AccountCatalogFacts(String modelId,ModelFacts facts,Fact<List<String>> modalities,long at){
        if(modelId==null||!modelId.matches("[A-Za-z0-9_.:-]{1,160}")||at<1)throw new IllegalArgumentException("invalid_account_catalog_facts");
        this.modelId=modelId;this.facts=facts;inputModalities=modalities;observedAtMillis=at;
    }

    /** Optional fields are recognized only when actually returned. No slug table or SDK defaults. */
    public static AccountCatalogFacts parse(JSONObject row,String modelId,long at){
        String ref=SOURCE+"#models/"+modelId;
        EnumMap<Field,Fact<?>> out=new EnumMap<>(Field.class);
        for(Field field:Field.values())out.put(field,Fact.unknown(Source.PROVIDER_CATALOG,ref,at,"not_returned_by_catalog"));
        number(out,Field.CONTEXT_WINDOW,row.opt("context_window"),ref+"/context_window",at);
        number(out,Field.OUTPUT_TOKENS,row.opt("max_output_tokens"),ref+"/max_output_tokens",at);
        List<String> modalities=names(row.opt("input_modalities"),false);
        Fact<List<String>> inputs=modalities==null?Fact.unknown(Source.PROVIDER_CATALOG,ref+"/input_modalities",at,
                absent(row.opt("input_modalities"))?"not_returned_by_catalog":"invalid_catalog_value"):
                Fact.known(modalities,Source.PROVIDER_CATALOG,ref+"/input_modalities",at);
        JSONObject caps=row.optJSONObject("capabilities");
        Object image=supported(caps,"image_input");
        Boolean declaredImage=modalities==null?null:modalities.contains("image");
        if(declaredImage!=null&&image instanceof Boolean&&!declaredImage.equals(image))
            out.put(Field.IMAGE_INPUT,Fact.unknown(Source.PROVIDER_CATALOG,ref,at,"conflicting_catalog_values"));
        else if((row.has("input_modalities")&&modalities==null)||(row.has("capabilities")&&caps==null)||(caps!=null&&caps.has("image_input")&&!(image instanceof Boolean)))
            out.put(Field.IMAGE_INPUT,Fact.unknown(Source.PROVIDER_CATALOG,ref,at,"invalid_catalog_value"));
        else if(declaredImage!=null||image instanceof Boolean)out.put(Field.IMAGE_INPUT,Fact.known(declaredImage==null?(Boolean)image:declaredImage,Source.PROVIDER_CATALOG,ref+"/input_modalities",at));
        booleanFact(out,Field.TOOL_CALLS,supported(caps,"tool_calls"),ref+"/capabilities/tool_calls/supported",at);
        List<String> levels=names(row.opt("supported_reasoning_levels"),true);
        if(levels!=null){
            out.put(Field.REASONING_LEVELS,Fact.known(levels,Source.PROVIDER_CATALOG,ref+"/supported_reasoning_levels",at));
            out.put(Field.REASONING,Fact.known(!levels.isEmpty(),Source.PROVIDER_CATALOG,ref+"/supported_reasoning_levels",at));
        }else if(row.has("supported_reasoning_levels")){
            out.put(Field.REASONING_LEVELS,Fact.unknown(Source.PROVIDER_CATALOG,ref+"/supported_reasoning_levels",at,"invalid_catalog_value"));
        }
        out.put(Field.CHAT_OUTPUT_PARAMETER,Fact.known(ChatOutputParameter.NOT_APPLICABLE,Source.PROVIDER_CATALOG,ref+"/responses",at));
        return new AccountCatalogFacts(modelId,new ModelFacts(out),inputs,at);
    }

    private static boolean absent(Object raw){return raw==null||raw==JSONObject.NULL;}
    private static Object supported(JSONObject caps,String key){
        if(caps==null)return null;Object value=caps.opt(key);
        return value instanceof JSONObject?((JSONObject)value).opt("supported"):value;
    }
    private static void number(Map<Field,Fact<?>> out,Field field,Object raw,String ref,long at){
        try{long value=positiveInteger(raw);out.put(field,Fact.known(field==Field.CONTEXT_WINDOW?new TokenWindow(value,WindowKind.INPUT_AND_OUTPUT):value,Source.PROVIDER_CATALOG,ref,at));}
        catch(IllegalArgumentException invalid){out.put(field,Fact.unknown(Source.PROVIDER_CATALOG,ref,at,absent(raw)?"not_returned_by_catalog":"invalid_catalog_value"));}
    }
    private static void booleanFact(Map<Field,Fact<?>> out,Field field,Object raw,String ref,long at){
        if(raw instanceof Boolean)out.put(field,Fact.known(raw,Source.PROVIDER_CATALOG,ref,at));
        else if(!absent(raw))out.put(field,Fact.unknown(Source.PROVIDER_CATALOG,ref,at,"invalid_catalog_value"));
    }
    private static List<String> names(Object raw,boolean effortObjects){
        if(!(raw instanceof JSONArray))return null;JSONArray array=(JSONArray)raw;
        if(array.length()>32||!effortObjects&&array.length()==0)return null;
        List<String> values=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        for(int i=0;i<array.length();i++){
            Object value=array.opt(i);if(effortObjects)value=value instanceof JSONObject?((JSONObject)value).opt("effort"):null;
            if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9_-]{1,64}")||!seen.add((String)value))return null;
            values.add((String)value);
        }
        return Collections.unmodifiableList(values);
    }

    public void validateOptions(ReasoningOptions options)throws ModelRequestPolicy.Rejected {
        if(options.mode()==ReasoningOptions.Mode.DEFAULT)return;
        if(options.mode()!=ReasoningOptions.Mode.OPENAI_EFFORT||!facts.reasoning().known||!facts.reasoning().value||
                !facts.reasoningLevels().known||!facts.reasoningLevels().value.contains(options.level()))
            throw new ModelRequestPolicy.Rejected("account_reasoning_option_unavailable","此账号当前模型目录未声明该推理选项，请刷新目录或保留服务默认；本次未发送。");
    }

    /** Local admission reserve only. SIWC does not accept max_output_tokens; never imply a wire cap. */
    public long outputReserve(ModelConfig selected){return facts.outputTokens().known?Math.max(selected.maxOutputTokens,facts.outputTokens().value):selected.maxOutputTokens;}
    public ContextBudgetPolicy.RouteBudget budget(ModelConfig selected)throws Exception {
        validateOptions(selected.reasoning);
        if(!facts.contextWindow().known)return null;
        long reserve=outputReserve(selected),window=facts.contextWindow().value.tokens();
        if(reserve>=window){
            boolean providerExhausts=facts.outputTokens().known&&facts.outputTokens().value>=window;
            throw new ModelRequestPolicy.Rejected("account_output_exhausts_context",providerExhausts
                    ?"当前提供方目录的最大输出已达到或超过其上下文，无法分配输入预算；请刷新目录核对或选择其他模型。没有发送输出限制字段。"
                    :"本地输出预留已占满该账号模型的已知上下文，请降低本地输出预留或选择更大模型；没有发送输出限制字段。");
        }
        return ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(window,reserve,0,ContextBudgetPolicy.REQUEST_BYTES,0,selected.imageTokenReserve),
                ContextBudgetPolicy.SourceKind.PROVIDER_METADATA,selected.accountModel.accountVersion,observedAtMillis);
    }
    public JSONObject toJson()throws Exception {
        return new JSONObject().put("format",FORMAT).put("modelId",modelId).put("observedAtMillis",observedAtMillis)
                .put("facts",facts.toJson()).put("inputModalities",inputModalities.toJson());
    }
    /** Private native metadata decoding, never a model/request capability declaration. */
    public static AccountCatalogFacts fromJson(JSONObject value)throws Exception {
        if(!FORMAT.equals(value.getString("format")))throw new IllegalArgumentException("invalid_account_catalog_facts");
        JSONObject inputs=value.getJSONObject("inputModalities");
        if(!Source.PROVIDER_CATALOG.name().equals(inputs.getString("source"))||!(inputs.get("known") instanceof Boolean))throw new IllegalArgumentException("invalid_account_catalog_facts");
        long at=positiveInteger(value.get("observedAtMillis"));Fact<List<String>> modalities;
        if(inputs.getBoolean("known")){
            List<String> names=names(inputs.get("value"),false);if(names==null)throw new IllegalArgumentException("invalid_account_catalog_facts");
            modalities=Fact.known(names,Source.PROVIDER_CATALOG,inputs.getString("sourceRef"),positiveInteger(inputs.get("observedAtMillis")));
        }else{
            if(!inputs.isNull("value"))throw new IllegalArgumentException("invalid_account_catalog_facts");
            modalities=Fact.unknown(Source.PROVIDER_CATALOG,inputs.getString("sourceRef"),positiveInteger(inputs.get("observedAtMillis")),inputs.getString("reason"));
        }
        ModelFacts facts=new ModelFacts(ModelFacts.readFields(value.getJSONObject("facts"),Source.PROVIDER_CATALOG));
        String id=value.getString("modelId"),prefix=SOURCE+"#models/"+id;
        for(Field field:Field.values()){
            Fact<?> fact=facts.fact(field);
            if(fact.observedAtMillis!=at||!(fact.sourceRef.equals(prefix)||fact.sourceRef.startsWith(prefix+"/")))throw new IllegalArgumentException("invalid_account_catalog_facts");
        }
        if(modalities.observedAtMillis!=at||!modalities.sourceRef.equals(prefix+"/input_modalities"))throw new IllegalArgumentException("invalid_account_catalog_facts");
        return new AccountCatalogFacts(id,facts,modalities,at);
    }
}
