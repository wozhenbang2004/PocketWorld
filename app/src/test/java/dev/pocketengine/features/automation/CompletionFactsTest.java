package dev.pocketengine.features.automation;

import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CompletionFactsTest {
    @Test public void stableFactIdentityChangesWithExactOriginalCompletion()throws Exception{
        String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),h="a".repeat(64);
        assertEquals(CompletionFacts.id(task,run,1,h),CompletionFacts.id(task,run,1,h));
        assertNotEquals(CompletionFacts.id(task,run,1,h),CompletionFacts.id(task,run,2,h));
        assertNotEquals(CompletionFacts.id(task,run,1,h),CompletionFacts.id(task,run,1,"b".repeat(64)));
    }
    @Test public void summaryBoundNeverSplitsUtf16PairOrAppendsUnreviewedText(){
        String text="x".repeat(2999)+"😀"+"tail";String actual=CompletionFacts.summary(text);
        assertEquals(2999,actual.length());assertEquals(text.substring(0,2999),actual);
    }
    @Test public void noVerifiedArtifactIsEmptyNotFabricatedRevision()throws Exception{
        assertEquals(0,CompletionFacts.artifactRefs(null).length());
        JSONObject proof=new JSONObject().put("kind","workspace_artifact").put("artifactId",UUID.randomUUID().toString()).put("operationId",UUID.randomUUID().toString())
                .put("projectId",UUID.randomUUID().toString()).put("path","output.txt").put("sha256","a".repeat(64)).put("sourceVersion",1).put("sourceRevisionId",UUID.randomUUID().toString()).put("sizeBytes",12);
        JSONObject ref=CompletionFacts.artifactRefs(proof).getJSONObject(0);assertEquals(1,ref.getInt("version"));assertFalse(ref.has("content"));
        proof.put("sourceVersion",0);assertThrows(SecurityException.class,()->CompletionFacts.artifactRefs(proof));
        proof.put("sourceVersion",1).put("path","../private");assertThrows(SecurityException.class,()->CompletionFacts.artifactRefs(proof));
    }
}
