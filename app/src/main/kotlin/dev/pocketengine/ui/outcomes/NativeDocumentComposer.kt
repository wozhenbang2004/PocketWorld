package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Editable source, explicit local generation and measured format feedback. No arbitrary shell,
 * automatic share, conversion of a filename into a format, or latest-version substitution. */
@Composable
internal fun NativeDocumentComposer(original:JSONObject,busy:Boolean,error:String?,pending:String?,generate:(JSONObject)->Unit,query:()->Unit,back:()->Unit){
    var name by remember(original){mutableStateOf(original.optString("name","文档"))}
    var title by remember(original){mutableStateOf(original.optString("title"))}
    var body by remember(original){mutableStateOf(original.optString("body"))}
    var format by remember(original){mutableStateOf(original.optString("format","docx"))}
    var size by remember(original){mutableIntStateOf(original.optInt("fontPoints",12))}
    var confirm by remember(original){mutableStateOf(false)}
    BackHandler { nativeBusyBack(busy, back) }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(Modifier.fillMaxWidth()){Text("可编辑文档",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=back,enabled=!busy){Text("返回")}}
        Text(if(original.has("parentArtifactId"))"从已选原成果的固定源继续；生成新的独立版本，原文件保留。"else "在本机生成真实Word或PDF，并保留可继续修改的原始内容。",style=MaterialTheme.typography.bodySmall)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        if(pending!=null){Text("原保存动作 ${pending.take(12)} 待核，草稿保留；不会重复生成。",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=query,enabled=!busy){Text("核对原文档结果")}}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            OutlinedTextField(name,{if(it.length<=80)name=it},label={Text("文件名")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(title,{if(it.length<=200)title=it},label={Text("标题（可选）")},enabled=!busy,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){for(kind in listOf("docx","pdf"))FilterChip(selected=format==kind,onClick={format=kind},enabled=!busy,label={Text(if(kind=="docx")"Word · 可编辑"else "PDF · 固定页面")})}
            Text("字号",style=MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){for(points in listOf(10,11,12,14,16))FilterChip(selected=size==points,onClick={size=points},enabled=!busy,label={Text("$points")})}
            OutlinedTextField(body,{if(it.length<=16000)body=it},label={Text("正文 · ${body.length}/16000")},enabled=!busy,minLines=10,modifier=Modifier.fillMaxWidth())
            Text("# 标题、空行分段；制表符分隔简单表格。A4、48pt页边距；不支持宏、外部模板或高保真Office转换。生成后可在应用内查看实际原件核对。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick={confirm=true},enabled=!busy&&pending==null&&name.isNotBlank()&&body.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("生成新的${if(format=="pdf")"PDF"else "Word"}文件")}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("生成独立文档？")},text={Text("将保存这份可编辑源和一个新的${format.uppercase()}原件。不会覆盖父文件，不导出、不分享；版式检查与业务正确性分开。")},confirmButton={TextButton(onClick={confirm=false;generate(JSONObject(original.toString()).put("name",name).put("title",title).put("body",body).put("format",format).put("fontPoints",size))}){Text("确认生成")}},dismissButton={TextButton(onClick={confirm=false}){Text("继续编辑")}})
}
