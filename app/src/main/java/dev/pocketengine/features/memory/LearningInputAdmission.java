package dev.pocketengine.features.memory;

import dev.pocketengine.features.KnowledgeLearningWorkflow;
import dev.pocketengine.runtime.usage.InferenceCapture;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native proof of the actual shortcut model input. Runs outside the original journal monitor. */
public final class LearningInputAdmission {
    private LearningInputAdmission(){}
    public interface SourceCheck {void check(SourceRef source)throws Exception;}
    public static InferenceCapture.Admission source(MemoryModule memory,KnowledgeLearningWorkflow.SourceReader reader,
            JSONObject scope,SourceRef expected,JSONObject input)throws Exception {
        JSONObject selectedScope=new JSONObject(scope.toString());String title=input.getString("title"),text=input.getString("text");
        return ()->{
            if(memory.isSourceForgotten(selectedScope,expected))throw new SecurityException("原学习来源已遗忘，未发送旧正文");
            KnowledgeLearningWorkflow.SourceDocument current=reader.read(expected);
            if(memory.isSourceForgotten(selectedScope,expected))throw new SecurityException("原学习来源已遗忘，未发送旧正文");
            if(current==null||!expected.equals(current.ref)||!title.equals(current.title)||!text.equals(current.text))
                throw new SecurityException("原学习来源已变化、退役或不可读，未发送旧正文");
        };
    }
    public static InferenceCapture.Admission consolidation(MemoryModule memory,JSONObject scope,JSONArray selections,JSONObject input)throws Exception {
        return consolidation(memory,scope,selections,input,source->{});
    }
    public static InferenceCapture.Admission consolidation(MemoryModule memory,JSONObject scope,JSONArray selections,JSONObject input,SourceCheck eligibility)throws Exception {
        JSONObject selectedScope=new JSONObject(scope.toString());JSONArray selected=new JSONArray(selections.toString()),sources=new JSONArray(input.getJSONArray("sourceRefs").toString());
        String digest=InferenceCapture.historyDigest(input);
        return ()->{for(int i=0;i<sources.length();i++)eligibility.check(SourceRef.fromJson(sources.getJSONObject(i)));if(!digest.equals(InferenceCapture.historyDigest(memory.consolidationSnapshot(selectedScope,selected))))
            throw new SecurityException("原归纳父记忆或来源已变化，未发送旧正文");};
    }
}
