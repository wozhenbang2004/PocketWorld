package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Draft boundary errors must be actionable, and must never silently remove a native DAG edge. */
class ExecutionPlanDagDraftTest {
    @Test fun invalidForeignDuplicateAndForwardEdgesStayErrorsInsteadOfBecomingIndependentWork(){
        val definition=executionPlanDefinition("完整目标",listOf(ExecutionStepDraft("甲"),ExecutionStepDraft("乙"),ExecutionStepDraft("汇合",dependencies=listOf(ExecutionDependencyDraft(0),ExecutionDependencyDraft(1)))) ,"0",1000)
        assertEquals(2,definition.getJSONArray("steps").getJSONObject(2).getJSONArray("dependsOn").length())
        assertTrue(assertThrows(IllegalArgumentException::class.java){executionPlanDefinition("目标",listOf(ExecutionStepDraft("甲"),ExecutionStepDraft("乙",dependencies=listOf(ExecutionDependencyDraft(0),ExecutionDependencyDraft(0)))) ,"0",1000)}.message!!.contains("重复"))
        assertTrue(assertThrows(IllegalArgumentException::class.java){executionPlanDefinition("目标",listOf(ExecutionStepDraft("甲",dependencies=listOf(ExecutionDependencyDraft(0)))) ,"0",1000)}.message!!.contains("更早"))
        val rows=JSONArray().put(JSONObject().put("id","a")).put(JSONObject().put("id","b")).put(JSONObject().put("id","c"));val step=JSONObject().put("id","c").put("goal","汇合").put("dueAt",1000).put("dependencies",JSONArray().put(JSONObject().put("stepId","a").put("outcome","completed")).put(JSONObject().put("stepId","outside").put("outcome","completed")));val before=step.toString()
        assertTrue(assertThrows(IllegalArgumentException::class.java){executionStepDraft(JSONObject().put("steps",rows),step)}.message!!.contains("未删除"));assertEquals(before,step.toString())
    }
}
