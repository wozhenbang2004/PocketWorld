package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteOriginalCancellationTest {
    private JSONObject capability()throws Exception{return new JSONObject().put("supported",true).put("protocol",RemoteOriginalCancellation.PROTOCOL).put("endpoint","/v1/jobs/{jobId}/cancel-original")
            .put("identityCheck","locked_job_operation_before_mutation").put("scope","job_manager_cli_jobs_only").put("requiredFields",new JSONArray().put("jobOperationId"));}
    private JSONObject target()throws Exception{return new JSONObject().put("jobId","job-original").put("jobOperationId","operation-original").put("serviceBase","http://127.0.0.1:8766");}
    private JSONObject receipt()throws Exception{return new JSONObject().put("id","job-original").put("operationId","operation-original").put("serviceBase","http://127.0.0.1:8766").put("status","cancelled")
            .put("originalCancellation",new JSONObject().put("protocol",RemoteOriginalCancellation.PROTOCOL).put("jobId","job-original").put("jobOperationId","operation-original").put("identityMatched",true).put("effectsReversed",false));}
    @Test public void capabilityRequiresTheExactServerMutationContract()throws Exception {
        RemoteOriginalCancellation.requireSupport(new JSONObject().put("originalJobCancellation",capability()));
        for(String field:new String[]{"supported","protocol","endpoint","identityCheck","scope","requiredFields"}){
            JSONObject missing=capability();missing.remove(field);assertThrows(field,SecurityException.class,()->RemoteOriginalCancellation.requireSupport(new JSONObject().put("originalJobCancellation",missing)));
        }
        for(JSONObject bad:new JSONObject[]{capability().put("supported","true"),capability().put("endpoint","/v1/jobs/{jobId}/cancel"),capability().put("requiredFields",new JSONArray()),capability().put("requiredFields",new JSONArray().put("jobOperationId").put("profileId"))})
            assertThrows(SecurityException.class,()->RemoteOriginalCancellation.requireSupport(new JSONObject().put("originalJobCancellation",bad)));
    }
    @Test public void proofMustMatchTheOriginalJobOperationAndOriginWithStrictBooleans()throws Exception {
        RemoteOriginalCancellation.requireReceipt(target(),receipt());
        for(String field:new String[]{"id","operationId","serviceBase"}){
            JSONObject wrong=receipt().put(field,"successor");assertThrows(field,SecurityException.class,()->RemoteOriginalCancellation.requireReceipt(target(),wrong));
        }
        for(String field:new String[]{"protocol","jobId","jobOperationId","identityMatched","effectsReversed"}){
            JSONObject missing=receipt();missing.getJSONObject("originalCancellation").remove(field);assertThrows(field,SecurityException.class,()->RemoteOriginalCancellation.requireReceipt(target(),missing));
        }
        for(String field:new String[]{"identityMatched","effectsReversed"}){
            JSONObject coerced=receipt();coerced.getJSONObject("originalCancellation").put(field,field.equals("identityMatched")?"true":"false");assertThrows(SecurityException.class,()->RemoteOriginalCancellation.requireReceipt(target(),coerced));
        }
    }
}
