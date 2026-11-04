package dev.pocketengine.core;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ExecutionPlanTest {
    private static JSONObject step(String id)throws Exception{return new JSONObject().put("id",id).put("title","Read source").put("detail","Inspect the original file; request approval before editing.");}
    private static JSONObject plan()throws Exception{return new JSONObject().put("format",ExecutionPlan.FORMAT).put("goal","Prepare a change").put("steps",new JSONArray().put(step("read-source")));}

    @Test public void completePlanRoundTripAndDefensiveCopyPreserveAllText()throws Exception {
        JSONObject original=plan().put("sources",new JSONArray().put("原项目文档\n第二行").put("https://example.test/reference"));
        original.getJSONArray("steps").getJSONObject(0).put("detail","原内容：\"甲\"\\乙\n\t🙂");
        JSONObject copy=ExecutionPlan.checked(original);String frozen=ExecutionPlan.canonical(copy);
        assertEquals(frozen,ExecutionPlan.canonical(new JSONObject(copy.toString())));
        copy.getJSONArray("steps").getJSONObject(0).put("detail","changed");copy.getJSONArray("sources").put(0,"changed");
        assertEquals(frozen,ExecutionPlan.canonical(original));assertNotEquals(frozen,ExecutionPlan.canonical(copy));
    }

    @Test public void canonicalOrderingIsStableAndArrayOrderRemainsMeaningful()throws Exception {
        JSONObject one=plan(),reordered=new JSONObject().put("steps",new JSONArray().put(new JSONObject().put("detail",step("x").getString("detail")).put("title","Read source").put("id","read-source"))).put("goal","Prepare a change").put("format",ExecutionPlan.FORMAT);
        assertEquals(ExecutionPlan.canonical(one),ExecutionPlan.canonical(reordered));assertEquals(ExecutionPlan.hash(one),ExecutionPlan.hash(reordered));
        one.getJSONArray("steps").put(step("review"));JSONObject reversed=new JSONObject(one.toString());JSONArray steps=reversed.getJSONArray("steps");Object first=steps.get(0);steps.put(0,steps.get(1));steps.put(1,first);
        assertNotEquals(ExecutionPlan.hash(one),ExecutionPlan.hash(reversed));
        assertTrue(ExecutionPlan.hash(one).matches("[a-f0-9]{64}"));
    }

    @Test public void everyUnknownOrSecretFieldIsRejectedWithoutDroppingIt()throws Exception {
        for(String key:new String[]{"apiKey","authorization","token","secret","imageBase64","imageDataUrl","path","approved","tools","budget","extra"}){
            JSONObject root=plan().put(key,"not-public");assertThrows(SecurityException.class,()->ExecutionPlan.checked(root));assertTrue(root.has(key));
            JSONObject nested=plan();nested.getJSONArray("steps").getJSONObject(0).put(key,"not-public");assertThrows(SecurityException.class,()->ExecutionPlan.checked(nested));
        }
    }

    @Test public void requiredFieldsAndTypesNeverCoerceNumbersNullOrObjects()throws Exception {
        for(String key:new String[]{"format","goal","steps"}){JSONObject value=plan();value.remove(key);assertThrows(SecurityException.class,()->ExecutionPlan.checked(value));}
        for(Object bad:new Object[]{JSONObject.NULL,12,true,new JSONObject(),new JSONArray()}){
            JSONObject value=plan().put("goal",bad);assertThrows(SecurityException.class,()->ExecutionPlan.checked(value));
        }
        for(String key:new String[]{"id","title","detail"}){JSONObject value=plan();value.getJSONArray("steps").getJSONObject(0).remove(key);assertThrows(SecurityException.class,()->ExecutionPlan.checked(value));}
        assertThrows(SecurityException.class,()->ExecutionPlan.checked(plan().put("format","pocketworld.execution-plan.v2")));
        assertThrows(SecurityException.class,()->ExecutionPlan.checked(plan().put("goal"," \n\t")));
    }

    @Test public void stepIdentitiesMustBeUniqueStableAndBounded()throws Exception {
        JSONObject duplicate=plan();duplicate.getJSONArray("steps").put(step("read-source"));assertThrows(SecurityException.class,()->ExecutionPlan.checked(duplicate));
        for(String id:new String[]{""," ","step\nnext","a".repeat(65)}){
            JSONObject value=plan().put("steps",new JSONArray().put(step(id)));assertThrows(SecurityException.class,()->ExecutionPlan.checked(value));
        }
        JSONObject maximum=plan().put("steps",new JSONArray().put(step("a".repeat(64))));assertEquals("a".repeat(64),ExecutionPlan.checked(maximum).getJSONArray("steps").getJSONObject(0).getString("id"));
    }

    @Test public void emptyOrOversizedStepListsCannotBePartiallyAccepted()throws Exception {
        assertThrows(SecurityException.class,()->ExecutionPlan.checked(plan().put("steps",new JSONArray())));
        JSONArray steps=new JSONArray();for(int i=0;i<32;i++)steps.put(step("step-"+i));assertEquals(32,ExecutionPlan.checked(plan().put("steps",steps)).getJSONArray("steps").length());
        steps.put(step("step-32"));assertThrows(SecurityException.class,()->ExecutionPlan.checked(plan().put("steps",steps)));assertEquals(33,steps.length());
    }

    @Test public void individualTextBoundsAreEnforcedWithoutTruncation()throws Exception {
        JSONObject maximum=plan().put("goal","g".repeat(4000));maximum.getJSONArray("steps").getJSONObject(0).put("title","t".repeat(200)).put("detail","d".repeat(2000));
        JSONObject checked=ExecutionPlan.checked(maximum);assertEquals(4000,checked.getString("goal").length());assertEquals(2000,checked.getJSONArray("steps").getJSONObject(0).getString("detail").length());
        assertThrows(SecurityException.class,()->ExecutionPlan.checked(plan().put("goal","g".repeat(4001))));
        for(String key:new String[]{"title","detail"}){JSONObject value=plan();value.getJSONArray("steps").getJSONObject(0).put(key,"x".repeat(key.equals("title")?201:2001));assertThrows(SecurityException.class,()->ExecutionPlan.checked(value));}
    }

    @Test public void sourcesAreOptionalRootTextOnlyWithExactBounds()throws Exception {
        assertFalse(ExecutionPlan.checked(plan()).has("sources"));assertEquals(0,ExecutionPlan.checked(plan().put("sources",new JSONArray())).getJSONArray("sources").length());
        JSONArray sources=new JSONArray();for(int i=0;i<8;i++)sources.put("s".repeat(1000));assertEquals(8,ExecutionPlan.checked(plan().put("sources",sources)).getJSONArray("sources").length());
        sources.put("ninth");assertThrows(SecurityException.class,()->ExecutionPlan.checked(plan().put("sources",sources)));
        for(Object value:new Object[]{new JSONObject().put("path","private"),new JSONArray(),JSONObject.NULL,12,"s".repeat(1001)})
            assertThrows(SecurityException.class,()->ExecutionPlan.checked(plan().put("sources",new JSONArray().put(value))));
        JSONObject nested=plan();nested.getJSONArray("steps").getJSONObject(0).put("sources",new JSONArray().put("source"));assertThrows(SecurityException.class,()->ExecutionPlan.checked(nested));
    }

    @Test public void exactAggregateByteCeilingIncludesTheCompleteJsonEnvelope()throws Exception {
        JSONArray steps=new JSONArray();for(int i=0;i<17;i++)steps.put(step("step-"+i).put("title","").put("detail",""));JSONObject exact=plan().put("goal","g").put("steps",steps);
        int remaining=ExecutionPlan.MAX_UTF8_BYTES-ExecutionPlan.canonical(exact).getBytes(StandardCharsets.UTF_8).length,last=-1;
        for(int i=0;i<steps.length()&&remaining>0;i++){int count=Math.min(2000,remaining);steps.getJSONObject(i).put("detail","x".repeat(count));remaining-=count;last=i;}
        assertEquals(0,remaining);assertEquals(32000,ExecutionPlan.canonical(exact).getBytes(StandardCharsets.UTF_8).length);
        JSONObject lastStep=steps.getJSONObject(last);lastStep.put("detail",lastStep.getString("detail")+"x");assertTrue(lastStep.getString("detail").length()<=2000);
        assertThrows(SecurityException.class,()->ExecutionPlan.checked(exact));
    }

    @Test public void utf8BytesAndEscapingCannotEvadeTheAggregateBound()throws Exception {
        JSONArray steps=new JSONArray();for(int i=0;i<6;i++)steps.put(step("step-"+i).put("detail","汉".repeat(2000)));
        JSONObject multibyte=plan().put("steps",steps);assertTrue(multibyte.toString().length()<32000);assertThrows(SecurityException.class,()->ExecutionPlan.checked(multibyte));
        steps=new JSONArray();for(int i=0;i<9;i++)steps.put(step("step-"+i).put("detail","\"".repeat(2000)));
        JSONObject escaped=plan().put("steps",steps);assertThrows(SecurityException.class,()->ExecutionPlan.checked(escaped));
    }

    @Test public void malformedUnicodeIsRejectedBeforeHashingInsteadOfReplacingOriginalText()throws Exception {
        for(String text:new String[]{"\uD800","\uDC00","valid\uD800tail"}){
            JSONObject value=plan().put("goal",text);assertThrows(SecurityException.class,()->ExecutionPlan.checked(value));assertThrows(SecurityException.class,()->ExecutionPlan.hash(value));
        }
    }

    @Test public void submitToolAcceptsOnlyTheCompletePlanAndCannotCarryAnApprovalOrOperation()throws Exception {
        JSONObject arguments=new JSONObject().put("plan",plan());ExecutionPlan.validateTool(ExecutionPlanning.SUBMIT,arguments);
        for(String key:new String[]{"approved","operationId","_hostOperationId","path","executionPhase"}){
            JSONObject forged=new JSONObject(arguments.toString()).put(key,"forged");assertThrows(SecurityException.class,()->ExecutionPlan.validateTool(ExecutionPlanning.SUBMIT,forged));
        }
        assertThrows(SecurityException.class,()->ExecutionPlan.validateTool(ExecutionPlanning.SUBMIT,new JSONObject().put("plan",plan().toString())));
        assertThrows(SecurityException.class,()->ExecutionPlan.validateTool(ExecutionPlanning.REVIEW,arguments));
    }

    @Test public void phaseToolIsABoundedRequestNotAnAttestation()throws Exception {
        for(String phase:new String[]{"planning","executing"})ExecutionPlan.validateTool(ExecutionPlanning.REQUEST,new JSONObject().put("phase",phase).put("reason","r".repeat(2000)));
        for(Object reason:new Object[]{""," \n", "r".repeat(2001),JSONObject.NULL,12,new JSONObject()}){
            JSONObject invalid=new JSONObject().put("phase","executing").put("reason",reason);assertThrows(SecurityException.class,()->ExecutionPlan.validateTool(ExecutionPlanning.REQUEST,invalid));
        }
        for(String phase:new String[]{"execute","approved","executing "})assertThrows(SecurityException.class,()->ExecutionPlan.validateTool(ExecutionPlanning.REQUEST,new JSONObject().put("phase",phase).put("reason","ask owner")));
        assertThrows(SecurityException.class,()->ExecutionPlan.validateTool(ExecutionPlanning.REQUEST,new JSONObject().put("phase","executing").put("reason","ask owner").put("approvalId","forged")));
    }

    @Test public void nativeCatalogueOffersSubmitOnlyDuringPlanningAndSchemasRejectUnknownKeys()throws Exception {
        JSONArray planning=ExecutionPlan.tools(true),executing=ExecutionPlan.tools(false);
        assertEquals(2,planning.length());assertEquals(1,executing.length());
        assertEquals(ExecutionPlanning.SUBMIT,planning.getJSONObject(0).getJSONObject("function").getString("name"));
        assertEquals(ExecutionPlanning.REQUEST,executing.getJSONObject(0).getJSONObject("function").getString("name"));
        JSONObject submit=planning.getJSONObject(0).getJSONObject("function").getJSONObject("parameters");
        assertFalse(submit.getBoolean("additionalProperties"));JSONObject plan=submit.getJSONObject("properties").getJSONObject("plan");
        assertFalse(plan.getBoolean("additionalProperties"));JSONObject steps=plan.getJSONObject("properties").getJSONObject("steps");
        assertEquals(1,steps.getInt("minItems"));assertEquals(32,steps.getInt("maxItems"));assertFalse(steps.getJSONObject("items").getBoolean("additionalProperties"));
        assertEquals(8,plan.getJSONObject("properties").getJSONObject("sources").getInt("maxItems"));
    }
}
