package dev.pocketengine.runtime.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only labels for the original task's native receipts. No counter, quota fetch or task authority. */
public final class TaskInferencePresentation {
    private TaskInferencePresentation(){}
    public record Counter(String name,String text){}
    public record Group(String title,String route,String semantics,List<Counter> counters){}
    public record Call(String id,String purpose,String status,String route,String binding,long at,List<Counter> counters,List<String> pricing,List<String> failure,boolean apiKey){}
    public record Context(String title,List<String> lines,long at,boolean attention){}
    public record Snapshot(String headline,String coverage,List<String> money,List<String> warnings,List<Group> groups,List<Call> calls,Context context,boolean hasAccount){}

    public static Snapshot fromTask(JSONObject task)throws Exception {
        String taskId=task.optString("id");if(blank(taskId))throw new IllegalArgumentException("missing_task_identity");
        JSONArray events=task.optJSONArray("events"),own=new JSONArray();int foreign=0;
        for(int i=0;events!=null&&i<events.length();i++){
            JSONObject event=events.optJSONObject(i);if(event==null||!"inference_receipt".equals(event.optString("type")))continue;
            JSONObject context=event.optJSONObject("context");
            if(context!=null&&!taskId.equals(context.optString("taskId"))){foreign++;continue;}
            own.put(event);
        }
        // Reuse the same dedup/conflict/partial rules as TaskStore.inferenceUsage, not a second sum.
        return present(task,InferenceProjection.fromEvents(own),foreign,"当前展示窗口中的模型调用；这是窗口小计，不是原任务累计。",0);
    }
    public static Snapshot fromNativeUsage(JSONObject task,JSONObject nativeUsage)throws Exception {
        String taskId=task.optString("id");
        if(blank(taskId)||nativeUsage==null||!"pocketworld.task-inference-usage.v1".equals(nativeUsage.optString("format"))
                ||!taskId.equals(nativeUsage.optString("taskId"))||!Boolean.TRUE.equals(nativeUsage.opt("allRetainedRuns"))
                ||!Boolean.TRUE.equals(nativeUsage.opt("readOnly"))||!Boolean.FALSE.equals(nativeUsage.opt("executionAuthorized"))
                ||!(nativeUsage.opt("queryLimited") instanceof Boolean))throw new SecurityException("不是这个原任务的累计用量读取回执");
        JSONObject projection=nativeUsage.getJSONObject("projection");JSONArray receipts=projection.getJSONArray("receipts");
        for(int i=0;i<receipts.length();i++)if(!taskId.equals(receipts.getJSONObject(i).getJSONObject("context").optString("taskId")))throw new SecurityException("累计调用混入其他任务");
        String coverage=nativeUsage.getBoolean("queryLimited")?"原任务累计读取达到行数／字节预算，或含不可读取记录；以下仍是部分小计，不能视作完整累计。":"此原任务所有当前保留 run 的累计调用，包含主模型、摘要与失败／取消调用。";
        return present(task,projection,0,coverage,nativeUsage.optLong("sourceReadAt"));
    }
    private static Snapshot present(JSONObject task,JSONObject projection,int foreign,String scope,long readAt)throws Exception {
        JSONArray events=task.optJSONArray("events");List<String> warnings=new ArrayList<>();
        int attempts=projection.getInt("attempts"),conflicts=projection.getInt("conflictingCalls"),invalid=projection.getInt("invalidReceipts");
        String headline=attempts==0?"暂无保留的模型调用记录":attempts+" 次调用 · 完成 "+projection.getInt("completed")+" · 失败 "+projection.getInt("failed")+" · 取消 "+projection.getInt("cancelled");
        if(projection.getInt("unfinished")>0)warnings.add(projection.getInt("unfinished")+" 次未见终态：可能已发出，不代表仍在运行，也不会在此自动重试。");
        if(conflicts>0)warnings.add(conflicts+" 次记录冲突：未计入分组小计，请核对原调用。");
        if(invalid>0||foreign>0)warnings.add("另有 "+(invalid+foreign)+" 条格式或任务归属无法核对的记录，未计入。");
        List<Group> groups=new ArrayList<>();List<Call> calls=new ArrayList<>();boolean account=false;
        JSONArray projectedGroups=projection.getJSONArray("groups");
        for(int i=0;i<projectedGroups.length();i++){
            JSONObject group=projectedGroups.getJSONObject(i),route=group.getJSONObject("route");account|="account".equals(route.optString("kind"));
            List<Counter> counters=new ArrayList<>();JSONObject counts=group.getJSONObject("counters");
            for(ProviderUsage.Counter key:ProviderUsage.Counter.values())counters.add(new Counter(counterName(key),sum(counts.getJSONObject(key.name()))));
            String semantics="openai_realtime_transcription".equals(group.optString("protocol"))?"这是独立ASR转录，不与语音回复并成一次推理；时长不换算tokens或费用。":"openai_realtime".equals(group.optString("protocol"))?"实时语音总输入/输出来自服务回执；文字与音频是其明细，不再相加。":(group.optBoolean("inputIncludesCacheRead")?"输入已含缓存读取，不再相加。":"缓存读取/写入与输入分列，不在此推算总量。")
                    +(group.optBoolean("outputIncludesReasoning")?" 输出已含推理，不再相加。":" 输出与推理分列，不在此推算总量。");
            groups.add(new Group(purpose(group.optString("purpose"),group.optString("memberId"))+" · "+group.getInt("attempts")+" 次",route(route),semantics,immutable(counters)));
        }
        JSONArray receipts=projection.getJSONArray("receipts");
        for(int i=receipts.length()-1;i>=0;i--){
            JSONObject receipt=receipts.getJSONObject(i),context=receipt.getJSONObject("context"),route=receipt.getJSONObject("route"),usage=receipt.getJSONObject("usage");account|="account".equals(route.optString("kind"));
            List<Counter> counters=new ArrayList<>();JSONObject counts=usage.optJSONObject("counters");
            for(ProviderUsage.Counter key:ProviderUsage.Counter.values()){
                JSONObject count=counts==null?null:counts.optJSONObject(key.name());Long value=count==null||!"provider_reported".equals(count.optString("source"))?null:nonnegative(count.opt("value"));
                String text=receipt.optBoolean("projectionConflict")?"未知（该调用记录冲突，未计入小计）":value==null?"未知（服务未返回或不可核对）":value+" · "+("FINAL".equals(count.optString("stage"))?"最终返回":"部分返回，非最终总量")+" · "+label(count.optString("sourcePath"),"来源路径未知");
                counters.add(new Counter(counterName(key),text));
            }
            JSONObject media=usage.optJSONObject("mediaCounters");if(media!=null)for(String key:new String[]{"INPUT_TEXT","INPUT_AUDIO","OUTPUT_TEXT","OUTPUT_AUDIO"}){JSONObject count=media.optJSONObject(key);if(count==null)continue;Long value="provider_reported".equals(count.optString("source"))?nonnegative(count.opt("value")):null;counters.add(new Counter(switch(key){case "INPUT_TEXT"->"输入文字明细";case "INPUT_AUDIO"->"输入音频明细";case "OUTPUT_TEXT"->"输出文字明细";default->"输出音频明细";},value==null?"未知（未返回，不按0计算）":value+" · "+label(count.optString("sourcePath"),"来源未知")));}
            if("server_response_observed".equals(route.optString("requestOrigin"))&&!warnings.contains("实时语音由服务端自动触发；开始记录来自实际 response.created，不冒充客户端发模前证明。未到达回执仍为未知。"))warnings.add("实时语音由服务端自动触发；开始记录来自实际 response.created，不冒充客户端发模前证明。未到达回执仍为未知。");
            JSONObject seconds=object(usage,"providerMeasures").optJSONObject("AUDIO_SECONDS");if(seconds!=null&&"provider_reported".equals(seconds.optString("source")))counters.add(new Counter("转录音频时长",seconds.get("value")+" 秒 · 服务实际报告，不换算tokens或账单"));
            if("server_transcription_observed".equals(route.optString("requestOrigin"))&&!warnings.contains("输入转录来自独立ASR的实际服务事件；与语音回复分开，缺失用量仍未知。"))warnings.add("输入转录来自独立ASR的实际服务事件；与语音回复分开，缺失用量仍未知。");
            String status=receipt.optBoolean("projectionConflict")?"记录冲突，未计入小计":outcome(receipt.optString("outcome"));
            calls.add(new Call(receipt.getString("callId"),purpose(context.optString("purpose"),context.optString("memberId")),status,route(route),
                    "run "+label(context.optString("runId"),"未知")+" · epoch "+context.optInt("epoch"),positive(receipt.opt("finishedAt"),receipt.opt("startedAt")),immutable(counters),pricing(receipt),failure(receipt),"api_key".equals(route.optString("kind"))));
        }
        String coverage=scope+" 仅此原任务当前保留的原生调用记录，不是全应用或服务账单；无记录不等于实际用量为 0。token来自实际原回执，估价只用冻结价目；实际账单和套餐剩余量：未知。"+(readAt>0?" 核对时间："+priceTime(readAt)+"。之后的新调用需刷新。":"");
        return new Snapshot(headline,coverage,money(projection.getJSONObject("money")),immutable(warnings),immutable(groups),immutable(calls),context(task,events),account);
    }
    private static List<String> failure(JSONObject receipt){
        if("COMPLETED".equals(receipt.optString("outcome")))return List.of();
        List<String> lines=new ArrayList<>();
        if(receipt.optBoolean("projectionConflict")){lines.add("同一callId记录冲突，失败细节未据此判定；请核对原调用。");return immutable(lines);}
        JSONObject value=receipt.optJSONObject("failure");
        if(value==null){lines.add("失败或取消原因未保留；没有观察到可核对的冷却时间，不代表连接可用。");return immutable(lines);}
        InferenceFailurePresentation.View failure=InferenceFailurePresentation.fromFailure(value,System.currentTimeMillis());lines.add(failure.title());lines.addAll(failure.lines());return immutable(lines);
    }
    private static List<String> money(JSONObject projection)throws Exception{
        List<String> lines=new ArrayList<>();JSONArray currencies=projection.getJSONArray("currencies");
        if(currencies.length()==0)lines.add("估价未知：当前原回执没有可核对的冻结价目/计数，不是免费或0。");
        for(int i=0;i<currencies.length();i++){JSONObject row=currencies.getJSONObject(i);lines.add(row.getString("currency")+" "+row.getString("knownTokenSubtotal")+" · 已知token分项估算小计，"+row.getInt("pricedCalls")+"次分项齐全、"+row.getInt("partlyPricedCalls")+"次仅部分可估；"+row.getInt("nonfinalCalls")+"次含非最终/不完整计数。");}
        if(projection.optInt("unknownCalls")>0)lines.add(projection.getInt("unknownCalls")+"次没有可估金额，未作为0加入小计。");
        lines.add("币种不混加；未包含请求费、媒体/工具费、税费或折扣，不是账单、退款或金额硬上限。每次只使用派发当时冻结的价目，不自动重估历史。");return immutable(lines);
    }
    private static List<String> pricing(JSONObject receipt)throws Exception{
        List<String> lines=new ArrayList<>();JSONObject usage=receipt.getJSONObject("usage"),frozen=usage.optJSONObject("pricing"),basis=frozen==null?null:frozen.optJSONObject("basis"),estimate=usage.optJSONObject("estimate");
        if(InferenceMoneyProjection.trustedPrice(receipt)==null||basis==null||estimate==null){lines.add("本次估价未知；未将未知价格视为免费。");return immutable(lines);}
        lines.add("冻结价目："+label(basis.optString("priceVersion"),"未知版本")+" · "+label(basis.optString("currency"),"币种未知"));
        lines.add(("OWNER_REVIEWED".equals(basis.optString("source"))?"机主明确价目，不是提供方账单":"提供方目录观察，不是账单")+" · "+label(basis.optString("sourceVersion"),"来源版本未知"));
        lines.add("来源："+basis.getString("sourceRef")+"；观察时间 "+priceTime(basis.optLong("observedAt"))+"；生效时间 "+(basis.isNull("effectiveFrom")?"未报告":priceTime(basis.optLong("effectiveFrom")))+"；失效时间 "+(basis.isNull("effectiveUntil")?"未报告":priceTime(basis.optLong("effectiveUntil")))+"。");
        JSONArray rows=estimate.optJSONArray("lines");for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);lines.add(label(row.optString("label"),"分项")+"："+(row.isNull("estimatedCost")?"未知":basis.optString("currency")+" "+row.getString("estimatedCost"))+" · "+(row.isNull("tokens")?"计数未知":row.getLong("tokens")+" tokens"));}
        return immutable(lines);
    }
    private static String priceTime(long at){return at<1?"未报告":java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new java.util.Date(at));}
    private static String sum(JSONObject count){
        Long value=nonnegative(count.opt("knownSubtotal"));int partial=count.optInt("partialCalls"),unknown=count.optInt("unknownCalls");
        if(count.optBoolean("overflow"))return "无法安全合计（数值溢出），不是 0";
        String text=value==null?"未知":value+"（已返回小计）";
        if(partial>0)text+=" · 含 "+partial+" 次部分返回";
        if(unknown>0)text+=" · "+unknown+" 次未知";
        return text;
    }
    private static Context context(JSONObject task,JSONArray events)throws Exception {
        JSONObject latest=null,event=null;for(int i=events==null?-1:events.length()-1;i>=0;i--){JSONObject row=events.optJSONObject(i);
            if(row!=null&&"context_budget".equals(row.optString("type"))&&task.optString("id").equals(row.optString("taskId"))&&row.optJSONObject("receipt")!=null){event=row;latest=row.getJSONObject("receipt");break;}}
        JSONObject evidence=task.optJSONObject("evidence"),unavailable=evidence==null?null:evidence.optJSONObject("contextCompaction");
        JSONObject blocked=evidence==null?null:evidence.optJSONObject("contextBudget");List<String> lines=new ArrayList<>();
        // A final blocked result is newer than the last successfully sent request.
        if("awaiting_user".equals(task.optString("status"))&&unavailable!=null){
            lines.add("整理未能安全发布；没有自动重试。原件能否读取仍须按当前来源核对。");lines.add("原因："+label(unavailable.optString("status"),"未知"));
            return new Context("上下文整理等待核对",immutable(lines),0,true);
        }
        if("awaiting_user".equals(task.optString("status"))&&blocked!=null)latest=blocked;
        if(latest==null)return new Context("尚无保留的上下文检查记录",List.of("不从累计服务用量推断当前占窗，也不猜模型窗口。"),0,false);
        Long bytes=nonnegative(object(latest,"after").opt("canonicalUtf8Bytes")),limit=nonnegative(latest.opt("maxCanonicalBytes"));
        if(bytes!=null&&limit!=null)lines.add("本地请求序列化："+bytes+" / "+limit+" 字节。");
        boolean known=Boolean.TRUE.equals(latest.opt("modelWindowKnown"));
        Long estimate=nonnegative(object(latest,"after").opt("estimatedInputTokens")),input=nonnegative(latest.opt("inputLimitTokens")),window=nonnegative(latest.opt("modelContextTokens")),output=nonnegative(latest.opt("outputReserveTokens"));
        if(known&&estimate!=null&&input!=null&&window!=null&&output!=null)lines.add("本地保守估算 "+estimate+" tokens；输入预算 "+input+"，模型窗口 "+window+"，输出预留 "+output+"。不是服务实际计数或精确 tokenizer。");
        else lines.add("模型窗口或图片预算未知：只显示已有本地安全检查，不显示占窗百分比。");
        if(Boolean.TRUE.equals(latest.opt("summaryProjectionUsed"))){JSONArray covered=latest.optJSONArray("coveredIndexes");
            lines.add("该次请求曾使用单层低信任摘要，覆盖 "+(covered==null?"未知":covered.length())+" 条可整理记录；主人原话、未决项和最新尾部单独保留。");
            if(covered!=null){List<String> indexes=new ArrayList<>();boolean exact=covered.length()<=100;
                for(int i=0;i<Math.min(covered.length(),100);i++){Long index=nonnegative(covered.opt(i));if(index==null){exact=false;break;}indexes.add(index.toString());}
                lines.add(exact?"原检查点消息索引："+String.join(", ",indexes):"原检查点索引未能完整显示，请核对原证据。");
            }
            String summaryCall=latest.optString("summaryCallId");if(summaryCall.matches("[a-f0-9-]{36}"))lines.add("对应 SUMMARY call："+summaryCall);
            lines.add("不是正式记忆或当前读取授权；原件与来源有效性仍从本任务原证据核对。");
        }else lines.add("此检查未证明使用了历史摘要；已整理与仍可回读不能互相替代。");
        lines.add("这是当时请求的检查记录，不证明当前路由、摘要或来源仍然有效。");
        String status=latest.optString("status");boolean attention=!status.isEmpty()&&!"ready".equals(status);
        if(attention)lines.add("检查状态："+label(status,"未知")+"；不会在此继续执行或重试。");
        return new Context(attention?"上下文检查需处理":"最近一次请求的本地检查",immutable(lines),event==null||latest==blocked?0:positive(event.opt("at"),event.opt("timestamp")),attention);
    }
    private static JSONObject object(JSONObject value,String key){JSONObject result=value.optJSONObject(key);return result==null?new JSONObject():result;}
    private static Long nonnegative(Object value){try{if(!(value instanceof Number))return null;long number=new BigDecimal(value.toString()).longValueExact();return number<0?null:number;}catch(ArithmeticException|NumberFormatException invalid){return null;}}
    private static long positive(Object first,Object second){Long value=nonnegative(first);if(value!=null&&value>0)return value;value=nonnegative(second);return value==null?0:value;}
    private static String route(JSONObject route){
        String kind="account".equals(route.optString("kind"))?"ChatGPT registration "+label(route.optString("accountId"),"未知")+" · v"+label(route.optString("accountVersion"),"未知")
                :"api_key".equals(route.optString("kind"))?"API-key · 连接 "+label(route.optString("connectionId"),"身份未保留")+" · 凭据版本 "+label(route.optString("credentialRevision"),"未知"):"调用路由未知";
        String requested=label(route.optString("requestedModel"),"请求模型未知"),response=label(route.optString("responseModel"),"服务未返回模型标识");
        return kind+"\n请求："+requested+" · 返回："+response+(route.optString("logicalRequestId").isEmpty()?"":"\n逻辑请求 "+route.optString("logicalRequestId")+" · 尝试 "+route.optInt("attemptNo")+" · 候选 "+route.optString("candidateId")+"\n别名 "+route.optString("requestedAlias")+" · 原因 "+route.optString("retryReason"));
    }
    private static String purpose(String purpose,String member){String label=switch(purpose){case "MAIN"->"主请求 MAIN";case "SUMMARY"->"上下文整理 SUMMARY";case "MEMBER"->"成员请求 MEMBER";case "TOOL"->"工具模型 TOOL";case "CONNECTION_TEST"->"连接测试";case "MODEL_CAPABILITY_PROBE"->"合成能力实测";case "VOICE_TURN"->"语音文字对话";case "REALTIME"->"自然语音回复";case "REALTIME_TRANSCRIPTION"->"语音输入转录（独立ASR）";case "RELAY"->"本机模型服务";case "SKILL_DRAFT"->"经验技能草稿整理";case "CHANNEL_CHAT"->"消息渠道问答";default->"用途未核对";};return member.isEmpty()?label:label+" · 成员 "+label(member,"未知");}
    private static String outcome(String value){return switch(value){case "COMPLETED"->"调用完成（不是任务验收）";case "FAILED"->"调用失败（不代表未计费）";case "CANCELLED"->"已取消本地等待（不代表退款或服务未处理）";default->"未见终态，派发结果待核对";};}
    private static String counterName(ProviderUsage.Counter value){return switch(value){case INPUT->"输入";case OUTPUT->"输出";case TOTAL->"服务总计";case CACHE_READ->"缓存读取";case CACHE_WRITE->"缓存写入";case CACHE_WRITE_5M->"缓存写入 5m";case CACHE_WRITE_1H->"缓存写入 1h";case REASONING->"推理";case TOOL_PROMPT->"工具提示";};}
    private static <T> List<T> immutable(List<T> values){return java.util.Collections.unmodifiableList(new ArrayList<>(values));}
    /** String.isBlank is API33; preserve its code-point whitespace semantics on minSdk30. */
    private static boolean blank(String value){
        if(value==null)return true;
        for(int offset=0;offset<value.length();){int point=value.codePointAt(offset);if(!Character.isWhitespace(point))return false;offset+=Character.charCount(point);}
        return true;
    }
    private static String label(String value,String fallback){if(value==null||blank(value)||value.equals("null"))return fallback;return value.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(160,value.length()));}
}
