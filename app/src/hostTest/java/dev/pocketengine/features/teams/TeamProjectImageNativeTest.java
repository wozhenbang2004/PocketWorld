package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Original reviewed work plan, actual team checkout/SQL budget, Runner and loopback wire.
 * Provider messages are fixtures; real codec/JNI prerequisites are never substituted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamProjectImageNativeTest {
    enum Mode { CHECKOUT, PRIVATE_ARTIFACT, MODEL_FORGED_ID, PAUSE_RESUME, REVOKED }
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject answer(String text)throws Exception{return new JSONObject().put("role","assistant").put("content",text);}
    private static JSONObject call(String callId,String tool,JSONObject args)throws Exception{return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",callId).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",args.toString()))));}
    private static JSONObject draft(String member,String path)throws Exception{return new JSONObject().put("workItemId",id()).put("memberId",member).put("title","原完整项目图片读取 "+member).put("instruction","按当前已批完整副本核对图片，不能借私人图片成果范围").put("acceptance","只依据实际收到的图片；本回归不声称完成文件交付").put("paths",new JSONArray().put(path)).put("dependsOn",new JSONArray());}
    private static byte[] png()throws Exception{Bitmap bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);try{ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));return out.toByteArray();}finally{bitmap.recycle();}}
    private static int verifyImages(JSONObject request,byte[] expected)throws Exception{
        int images=0;JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONArray parts=messages.getJSONObject(i).optJSONArray("content");for(int n=0;parts!=null&&n<parts.length();n++){JSONObject part=parts.getJSONObject(n);if(!"image_url".equals(part.optString("type")))continue;String url=part.getJSONObject("image_url").getString("url");assertTrue(url.startsWith("data:image/png;base64,"));assertArrayEquals(expected,Base64.getDecoder().decode(url.substring(url.indexOf(',')+1)));images++;}}return images;
    }
    private static JSONObject toolResult(JSONObject request,String callId)throws Exception{JSONArray rows=request.getJSONArray("messages");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if("tool".equals(row.optString("role"))&&callId.equals(row.optString("tool_call_id")))return new JSONObject(row.getString("content"));}return null;}
    private static JSONArray currentGrantTools(TaskStore tasks,String task)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var sql=(android.database.sqlite.SQLiteDatabase)field.get(tasks);String run=tasks.get(task,false).getString("runId");try(var cursor=sql.rawQuery("SELECT grant_json FROM runs WHERE task_id=? AND run_id=?",new String[]{task,run})){assertTrue(cursor.moveToFirst());return new JSONObject(cursor.getString(0)).getJSONArray("tools");}}
    private void journey(Mode mode)throws Exception{
        try(TeamProjectRunnerDeliveryJourneyTest.Fixture f=new TeamProjectRunnerDeliveryJourneyTest.Fixture(false,false,true)){
            byte[] pixels=png();String sha=ProjectSources.sha256(pixels);f.workspace.writeBytesForHost(f.workspace.fileSnapshotForHost(f.project,"image.png",0),pixels);Files.write(f.active(f.parentCheckout).resolve("image.png"),pixels);
            JSONObject privateArtifact=f.workspace.freezeFileForHost(f.workspace.fileSnapshotForHost(f.project,"image.png",0),id(),null,null).json();
            AtomicReference<String> selectedTask=new AtomicReference<>(),selectedRun=new AtomicReference<>(),visualCall=new AtomicReference<>();AtomicReference<RunControl> selectedControl=new AtomicReference<>();AtomicReference<Throwable> observerFailure=new AtomicReference<>();AtomicBoolean sent=new AtomicBoolean(),mutation=new AtomicBoolean(),sawRejection=new AtomicBoolean();AtomicInteger actualImages=new AtomicInteger();
            var observerField=InferenceCapture.class.getDeclaredField("presentationObserver");observerField.setAccessible(true);Object priorObserver=observerField.get(null);
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt)throws Exception{
                    if(mode!=Mode.PAUSE_RESUME&&mode!=Mode.REVOKED||!receipt.context.taskId.equals(selectedTask.get())||mutation.get())return;
                    try{JSONObject task=f.tasks.get(selectedTask.get(),false);if(f.lastRead(task,"project_view_image")==null)return;assertTrue(mutation.compareAndSet(false,true));RunControl original=selectedControl.get();assertSame(original,f.host.currentControl(selectedTask.get()));
                        if(mode==Mode.PAUSE_RESUME){original.pause();original.resume();}
                        else{JSONArray before=currentGrantTools(f.tasks,selectedTask.get()),after=new JSONArray();for(int i=0;i<before.length();i++)if(!"project_view_image".equals(before.getString(i)))after.put(before.getString(i));f.tasks.authorizeTools(selectedTask.get(),selectedRun.get(),after);}
                    }catch(Throwable failure){observerFailure.compareAndSet(null,failure);}
                }
                public void finished(InferenceReceipt ignored){}
            });
            try(TaskStoreTeamWriteHost.WorkPlanPage page=f.host.newWorkPlanPage(f.coordinator,f.run,()->f.model)){
                f.compactionTextAuthors=true;f.extraModelResponse=request->{
                    int count=verifyImages(request,pixels);if(!"author-a".equals(request.getString("model"))){assertEquals(0,count);return answer("本成员等待主人核对，未请求图片或制作成果。");}
                    JSONObject task=f.activeTask("a");assertNotNull("Actual original author must own this request",task);assertEquals("team_assignment",task.getJSONObject("origin").getString("kind"));
                    if(task.optJSONObject("teamProject")==null){assertEquals(0,count);return call(id(),"team_prepare_draft",new JSONObject());}
                    String taskId=task.getString("id"),run=task.getString("runId"),checkout=task.getJSONObject("teamProject").getString("checkoutId");assertNotEquals(f.parentCheckout,checkout);
                    if(sent.compareAndSet(false,true)){
                        assertEquals(0,count);selectedTask.set(taskId);selectedRun.set(run);selectedControl.set(f.host.currentControl(taskId));assertNotNull(selectedControl.get());String originalCall=id();visualCall.set(originalCall);
                        JSONObject args=mode==Mode.PRIVATE_ARTIFACT?new JSONObject().put("artifactId",privateArtifact.getString("id")).put("sourceSha",sha):new JSONObject().put("checkout",checkout).put("path","image.png").put("expectedSha",sha);
                        if(mode==Mode.MODEL_FORGED_ID)args.put("_hostOperationId",id());return call(originalCall,"project_view_image",args);
                    }
                    JSONObject result=toolResult(request,visualCall.get());assertNotNull(result);assertFalse(result.has("imageBase64"));assertFalse(result.has("image_url"));
                    if(mode==Mode.CHECKOUT){assertEquals(1,count);assertEquals("captured",result.getString("status"));assertEquals(visualCall.get(),result.getString("operationId"));assertFalse(result.getBoolean("actualImageAttached"));actualImages.addAndGet(count);}
                    else{assertEquals("Late revoked/paused images cannot reach the model",0,count);assertTrue("Only input/permission rejections may produce a later text request",mode==Mode.PRIVATE_ARTIFACT||mode==Mode.MODEL_FORGED_ID);sawRejection.set(true);if(mode==Mode.MODEL_FORGED_ID)assertEquals("invalid_arguments",result.getString("code"));}
                    return answer("已按真实回执核对本成员图片输入，未冻结或交付项目修改。");
                };
                page.setForeground(true);JSONObject before=f.tasks.teamBudgetSnapshot(f.run);TaskStore.TeamWorkReview review=page.reviewProject(new JSONArray().put(draft("a","a.js")).put(draft("b","b.js")),f.options());
                if(review.view().getBoolean("parentBudgetIncreaseRequired"))review=page.approveParentBudget(review,review.view().getString("digest"));
                JSONObject approvedBudget=f.tasks.teamBudgetSnapshot(f.run);assertEquals(before.getInt("modelRequestsReservedOrUsed"),approvedBudget.getInt("modelRequestsReservedOrUsed"));assertEquals(before.getInt("toolOperationsReservedOrUsed"),approvedBudget.getInt("toolOperationsReservedOrUsed"));
                page.approve(review,review.view().getString("digest"));f.settled();assertNull(f.failure.get());assertNull(observerFailure.get());assertTrue("The real original author must reach its image call",sent.get());
                JSONObject task=f.tasks.get(selectedTask.get(),false);assertEquals(selectedRun.get(),task.getString("runId"));assertEquals("team_assignment",task.getJSONObject("origin").getString("kind"));JSONArray operations=task.getJSONArray("operations");int reads=0;
                for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);if(!"project_view_image".equals(row.getString("tool")))continue;reads++;assertEquals(selectedRun.get(),row.getString("runId"));assertFalse("Native dispatch ID is not part of original metered source arguments",row.getJSONObject("arguments").has("_hostOperationId"));}
                assertEquals(mode==Mode.MODEL_FORGED_ID?0:1,reads);assertEquals(mode==Mode.CHECKOUT?1:0,actualImages.get());if(mode==Mode.MODEL_FORGED_ID)assertTrue(sawRejection.get());
                if(mode==Mode.PAUSE_RESUME||mode==Mode.REVOKED)assertTrue("Revocation must happen inside physical STARTED before the second admission",mutation.get());
                JSONObject after=f.tasks.teamBudgetSnapshot(f.run);assertEquals(approvedBudget.getInt("modelRequestLimit"),after.getInt("modelRequestLimit"));assertEquals(approvedBudget.getInt("toolOperationLimit"),after.getInt("toolOperationLimit"));assertTrue(after.getInt("modelRequestsReservedOrUsed")>approvedBudget.getInt("modelRequestsReservedOrUsed"));assertTrue(after.getInt("toolOperationsReservedOrUsed")>approvedBudget.getInt("toolOperationsReservedOrUsed"));
            }finally{observerField.set(null,priorObserver);}
        }
    }
    @Test public void originalTeamCheckoutUsesItsExactNativeIdAndOriginalReadBudget()throws Exception{journey(Mode.CHECKOUT);}
    @Test public void teamCannotBorrowPrivateArtifactOrModelSuppliedNativeCallId()throws Exception{journey(Mode.PRIVATE_ARTIFACT);journey(Mode.MODEL_FORGED_ID);}
    @Test public void teamPauseAndGrantWithdrawalAfterStartedBlockTheActualImageRequest()throws Exception{journey(Mode.PAUSE_RESUME);journey(Mode.REVOKED);}
}
