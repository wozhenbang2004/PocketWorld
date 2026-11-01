package dev.pocketengine.features.local.project;

import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import dev.pocketengine.features.local.TermuxBridge;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Runs the real callback decoder against SDK-35 Android classes and app-private fixture files. */
public final class ProjectTermuxReceiptTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File root;
    private ContextWrapper context()throws Exception {root=temp.newFolder();return new ContextWrapper(null){@Override public File getFilesDir(){return root;}};}
    private File receipt(String id){return new File(root,"termux-receipts/"+id+".json");}
    private JSONObject initial(String id)throws Exception {return new JSONObject().put("id",id).put("operationId",id).put("kind","command").put("nonce","private_nonce").put("status","awaiting_result").put("sourceDigest","a".repeat(64)).put("sourceGuardRequired",true);}
    private Intent callback(String id,String nonce,String stdout,String stderr,int exit)throws Exception {
        Bundle result=new Bundle();result.putInt("err",-1);result.putInt("exitCode",exit);result.putString("stdout",stdout);result.putString("stderr",stderr);
        return new Intent().setData(Uri.parse("pocketworld-local://result/"+id+"/"+nonce)).putExtra("result",result);
    }
    @Test public void actualNonceAndPreExecMarkerAreRequiredAndConsumedOnce()throws Exception {
        ContextWrapper app=context();JsonFiles.write(receipt("actual"),initial("actual"));String marker=ProjectSources.marker("a".repeat(64))+"\n";
        assertNull(TermuxBridge.receive(app,callback("actual","wrong",marker+"ok\n","",0)));
        JSONObject done=TermuxBridge.receive(app,callback("actual","private_nonce",marker+"ok\n","",0));assertEquals("completed",done.getString("status"));assertEquals("pre_exec_manifest_verified",done.getString("sourceCheckStatus"));assertEquals("ok\n",done.getString("stdout"));assertFalse(done.has("nonce"));assertNull(TermuxBridge.receive(app,callback("actual","private_nonce",marker+"duplicate","",0)));
    }
    @Test public void successfulExitWithoutMarkerCannotClaimVerifiedSource()throws Exception {
        ContextWrapper app=context();JsonFiles.write(receipt("missing"),initial("missing"));JSONObject result=TermuxBridge.receive(app,callback("missing","private_nonce","tool output","",0));assertEquals("completed",result.getString("status"));assertEquals("unverified_or_mismatch",result.getString("sourceCheckStatus"));assertEquals("tool output",result.getString("stdout"));
    }
    @Test public void guardMismatchAndTransportErrorRemainActualFailureOrUnknown()throws Exception {
        ContextWrapper app=context();JsonFiles.write(receipt("mismatch"),initial("mismatch"));JSONObject mismatch=TermuxBridge.receive(app,callback("mismatch","private_nonce","","POCKETWORLD_SOURCE_MISMATCH\n",73));assertEquals("failed",mismatch.getString("status"));assertEquals(73,mismatch.getInt("exitCode"));assertEquals("unverified_or_mismatch",mismatch.getString("sourceCheckStatus"));
        JsonFiles.write(receipt("error"),initial("error"));Intent error=callback("error","private_nonce","","",0);error.getBundleExtra("result").putInt("err",3);assertEquals("unknown",TermuxBridge.receive(app,error).getString("status"));
    }
}
