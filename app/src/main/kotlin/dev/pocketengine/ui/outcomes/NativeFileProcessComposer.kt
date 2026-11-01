package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.attachments.OwnerFilePreviewSource
import org.json.JSONObject

@Composable
internal fun NativeFileProcessComposer(state:AttachmentOutcomeState,flow:AttachmentOutcomeCoordinator){
    val source=state.processingSource?:return;val info=state.processingInfo?:return;val image=source.mimeType.startsWith("image/")
    val defaults=remember(source.identity){val w=info.optInt("width",1);val h=info.optInt("height",1);val scale=minOf(1.0,2000.0/w,2000.0/h);listOf("0","0",w.toString(),h.toString(),(w*scale).toInt().coerceAtLeast(1).toString(),(h*scale).toInt().coerceAtLeast(1).toString())}
    var crop by remember(source.identity){mutableStateOf(defaults)};var rotation by remember(source.identity){mutableIntStateOf(0)};var format by remember(source.identity){mutableStateOf("png")};var quality by remember(source.identity){mutableStateOf("90")};var start by remember(source.identity){mutableStateOf("0")};var end by remember(source.identity){mutableStateOf(minOf(10000,info.optLong("durationMillis",1000)).toString())}
    var localError by remember(source.identity){mutableStateOf<String?>(null)};var confirm by remember(source.identity){mutableStateOf(false)}
    BackHandler(enabled=!state.busy,onBack=flow::closeProcessing)
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row{Text(if(image)"原图加工"else "截取原媒体",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=flow::closeProcessing,enabled=!state.busy){Text("返回原件")}}
        Text(source.name,style=MaterialTheme.typography.titleMedium)
        Text("保存为新的独立文件，父原件不会覆盖。每次核对同一完整SHA后才加工，不自动导出或分享。",style=MaterialTheme.typography.bodySmall)
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        (localError?:state.error)?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(image){
                Text("原方向尺寸 ${info.optInt("width")} × ${info.optInt("height")} · 输出最大400万像素",style=MaterialTheme.typography.bodySmall)
                for((i,label)in listOf("左边界像素","上边界像素","右边界（不含）","下边界（不含）","输出宽度","输出高度").withIndex())OutlinedTextField(crop[i],{crop=crop.toMutableList().also{v->v[i]=it.filter(Char::isDigit).take(5)};flow.forgetProcessingReview()},label={Text(label)},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                TextButton(onClick={rotation=(rotation+90)%360;flow.forgetProcessingReview()},enabled=!state.busy){Text("顺时针旋转：$rotation°")}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){for(kind in listOf("png","jpeg"))FilterChip(format==kind,{format=kind;flow.forgetProcessingReview()},enabled=!state.busy,label={Text(kind.uppercase())})}
                if(format=="jpeg"){OutlinedTextField(quality,{quality=it.filter(Char::isDigit).take(2);flow.forgetProcessingReview()},label={Text("JPEG质量50–95")},singleLine=true,enabled=!state.busy);Text("JPEG有损且透明区域变白；PNG保留透明。宽高可分别设置，非等比例可能改变图像比例。",style=MaterialTheme.typography.bodySmall)}
            }else{
                Text("原时长 ${info.optLong("durationMillis")}ms。起点向前对齐实际同步帧，结束按样本边界；无损重封装，不是帧精确转码。",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(start,{start=it.filter(Char::isDigit).take(6);flow.forgetProcessingReview()},label={Text("请求开始毫秒")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(end,{end=it.filter(Char::isDigit).take(6);flow.forgetProcessingReview()},label={Text("请求结束毫秒")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick={try{localError=null;val args=if(image)JSONObject().put("left",crop[0].toInt()).put("top",crop[1].toInt()).put("right",crop[2].toInt()).put("bottom",crop[3].toInt()).put("width",crop[4].toInt()).put("height",crop[5].toInt()).put("rotation",rotation).put("format",format).put("quality",quality.toInt())else JSONObject().put("startMillis",start.toLong()).put("endMillis",end.toLong());flow.reviewProcessing(args)}catch(_:Exception){localError="请填写范围内的完整整数"}},enabled=!state.busy&&state.pendingProcessingTask==null,modifier=Modifier.fillMaxWidth()){Text("核对实际加工范围")}
            state.processingReview?.let{review->val actual=review.metadata();Text(if(image)"输出 ${actual.optInt("width")} × ${actual.optInt("height")}，旋转 ${actual.optInt("rotation")}°，${actual.optString("outputFormat").uppercase()}"else "实际同步帧起点 ${actual.optLong("actualStartUs")/1000}ms；请求结束 ${actual.optLong("requestedEndUs")/1000}ms。",style=MaterialTheme.typography.bodyMedium)}
            state.pendingProcessingTask?.let{Text("原动作 ${it.take(12)} 待核，不重复加工。",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=flow::queryProcessing,enabled=!state.busy){Text("核对原加工结果")}}
        }
        Button(onClick={confirm=true},enabled=!state.busy&&state.processingReview!=null&&state.pendingProcessingTask==null,modifier=Modifier.fillMaxWidth()){Text("保存新的加工原件")}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("保存独立新版本？")},text={Text("只执行刚核对的这份固定原件和范围。原件保留；取消等待不等于已撤销保存，结果待核时只查询原动作。")},confirmButton={TextButton(onClick={confirm=false;flow.applyProcessing()}){Text("确认加工并保存")}},dismissButton={TextButton(onClick={confirm=false}){Text("继续核对")}})
}

@Composable
internal fun NativeImageVersionComparison(pair:Pair<OwnerFilePreviewSource,OwnerFilePreviewSource>,back:()->Unit){
    var selected by remember(pair.first.identity,pair.second.identity){mutableIntStateOf(1)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().statusBarsPadding(),horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick=back){Text("返回成果")};FilterChip(selected==0,{selected=0},label={Text("父原图")});FilterChip(selected==1,{selected=1},label={Text("这个版本")})}
        Text("两份固定原图 · 切换查看，不用最新或缩略图冒选定版本",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=16.dp))
        Box(Modifier.weight(1f)){NativeOriginalFilePage(if(selected==0)pair.first else pair.second,back)}
    }
}
