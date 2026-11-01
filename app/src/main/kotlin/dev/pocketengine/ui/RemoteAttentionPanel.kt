package dev.pocketengine.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun RemoteAttentionPanel(snapshot:JSONObject,enabled:Boolean,controller:PocketWorldController,send:(String,JSONObject)->Unit){
    val attention=snapshot.optJSONObject("attention")?:JSONObject()
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row { Checkbox(attention.optBoolean("enabled"),{send("setAttention",JSONObject().put("enabled",it))},enabled=enabled);Text("这个电脑任务有问题、审批或结束时提醒我",fontSize=14.sp,modifier=Modifier.weight(1f).padding(top=12.dp)) }
        if(attention.optBoolean("enabled")){
            Text(when(attention.optString("postState")){"posted_to_system"->"已交给手机系统显示；不等于你已读或业务完成。";"prepared","unknown"->"原提醒提交尚未确认，不自动重复提示；可明确关闭再开启并重新核对。";"blocked"->"系统未允许提交提醒，请检查通知设置。";else->"已开启；等待收到原电脑的新状态。"},fontSize=13.sp)
            Text("仅在本机进程收到或重新查询到原状态时提醒；离线/进程结束期间没有远端推送保证。通知点击后重新读取当前原任务，不能直接批准旧请求。锁屏不展示问题/文件正文。",fontSize=13.sp)
            if(!controller.state.notificationsEnabled)OutlinedButton(onClick=controller::openNotifications){Text("允许/检查本机通知")}
        }
    }
}
