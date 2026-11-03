package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.attachments.creation.DocumentDraft
import dev.pocketengine.features.attachments.creation.StructuredOfficeDraft
import org.json.JSONObject

/** Editable source, explicit local generation and measured format feedback. No arbitrary shell,
 * automatic share, conversion of a filename into a format, or latest-version substitution. */
@Composable
internal fun NativeDocumentComposer(original:JSONObject,busy:Boolean,error:String?,pending:String?,generate:(JSONObject)->Unit,query:()->Unit,back:()->Unit,draftChanged:(JSONObject)->Unit={}){
    val presentation=original.optString("format")=="pptx"
    val textDocument=original.optString("format") in setOf("docx","pdf")
    val sourceKey=listOf(if(presentation)"pptx"else if(textDocument)"text"else original.optString("format"),original.optString("parentArtifactId"),original.optString("parentSha256"),original.optString("editableSourceId"),original.optString("editableSourceSha256")).joinToString(":")
    var draft by remember(sourceKey){mutableStateOf(JSONObject(original.toString()))}
    var edited by remember(sourceKey){mutableStateOf(false)}
    var editError by remember(sourceKey){mutableStateOf<String?>(null)}
    var confirm by remember(sourceKey){mutableStateOf(false)}
    val originalPresentation=remember(sourceKey){if(presentation&&original.has("parentArtifactId"))runCatching{DocumentDraft(original)}else null}
    val supported=textDocument||presentation&&originalPresentation?.isSuccess==true
    val validation=remember(draft){runCatching{DocumentDraft(draft)}}
    val enabled=!busy&&pending==null
    val canGenerate=supported&&validation.isSuccess&&draft.optString("name").isNotBlank()
    fun change(edit:(JSONObject)->Unit){
        if(!enabled)return
        try{
            val next=JSONObject(draft.toString());edit(next)
            check(next.toString().toByteArray(Charsets.UTF_8).size<=StructuredOfficeDraft.MAX_SOURCE_BYTES){"文档结构源最多256KiB；这次输入未写入，请缩短后再试。"}
            draft=next;edited=true;editError=null
            // Incomplete field edits stay visible here. Only a complete bounded draft is a
            // restorable owner draft; it is still not an approved/native save operation.
            if(runCatching{DocumentDraft(next)}.isSuccess)draftChanged(JSONObject(next.toString()))
        }catch(failure:Exception){editError=failure.message?.take(240)?:"这次编辑未写入，请核对内容。"}
    }
    fun textEdit(value:String,limit:Int,edit:(JSONObject)->Unit){
        if(value.length>limit){editError="此项最多${limit}字；超出的输入未写入。";return}
        change(edit)
    }
    BackHandler(enabled=!busy,onBack=back)
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(presentation)"编辑原演示文稿"else "可编辑文档",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=back,enabled=!busy){Text("返回")}}
        Text(if(original.has("parentArtifactId"))"从已选原成果的固定源继续；保存新的独立版本，原文件保留。"else "在本机生成真实Word或PDF，并保留可继续修改的原始内容。",style=MaterialTheme.typography.bodySmall)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        editError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        if(edited&&validation.isFailure)Text((validation.exceptionOrNull()?.message?:"内容暂不符合文档格式要求")+"；请修正后保存，当前不完整编辑尚未进入可恢复草稿。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        if(pending!=null){Text("原保存动作 ${pending.take(12)} 待核；编辑与再次保存已暂停。",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=query,enabled=!busy){Text("核对原文档结果")}}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(supported){
                OutlinedTextField(draft.optString("name"),{value->textEdit(value,80){it.put("name",value)}},label={Text("文件名")},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth())
                if(presentation){
                    PresentationTextEditor(draft,sourceKey,enabled){slideIndex,field,index,value,limit->
                        textEdit(value,limit){next->
                            val slide=next.getJSONObject("presentation").getJSONArray("slides").getJSONObject(slideIndex)
                            when(field){
                                "paragraphs"->slide.getJSONArray("paragraphs").put(index,value)
                                "shapes"->slide.getJSONArray("shapes").getJSONObject(index).put("text",value)
                                else->slide.put(field,value)
                            }
                        }
                    }
                    Text("修改本机结构源中已有页面的文字、讲稿备注与文本对象；原页顺序、对象位置和大小保留。保存会核对实际PPTX文本结构，完整视觉排版仍需打开原件查看。当前不编辑图片、图表、动画或任意导入的PPTX。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }else{
                    OutlinedTextField(draft.optString("title"),{value->textEdit(value,200){it.put("title",value)}},label={Text("标题（可选）")},enabled=enabled,modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){for(kind in listOf("docx","pdf"))FilterChip(selected=draft.optString("format")==kind,onClick={change{it.put("format",kind)}},enabled=enabled,label={Text(if(kind=="docx")"Word · 可编辑"else "PDF · 固定页面")})}
                    Text("字号",style=MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){for(points in listOf(10,11,12,14,16))FilterChip(selected=draft.optInt("fontPoints",12)==points,onClick={change{it.put("fontPoints",points)}},enabled=enabled,label={Text("$points")})}
                    OutlinedTextField(draft.optString("body"),{value->textEdit(value,16000){it.put("body",value)}},label={Text("正文 · ${draft.optString("body").length}/16000")},enabled=enabled,minLines=10,modifier=Modifier.fillMaxWidth())
                    Text("# 标题、空行分段；制表符分隔简单表格。A4、48pt页边距；不支持宏、外部模板或高保真Office转换。生成后可在应用内查看实际原件核对。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }else Text(if(presentation)"这份演示文稿尚无可核对的固定原成果结构源，当前不能编辑。请返回原文件核对；没有转成Word。"else "当前手机编辑页不支持修改${original.optString("format").uppercase()}结构源。请返回打开或导出原件；原可编辑源保留。",style=MaterialTheme.typography.bodyMedium)
        }
        if(supported)Button(onClick={confirm=true},enabled=enabled&&canGenerate,modifier=Modifier.fillMaxWidth()){Text(if(presentation)"保存为新的PPTX文件"else "生成新的${if(draft.optString("format")=="pdf")"PDF"else"Word"}文件")}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(if(presentation)"保存新的PPTX版本？"else "生成独立文档？")},text={Text("将保存这份可编辑源和一个新的${draft.optString("format").uppercase()}原件。不会覆盖父文件，不导出、不分享；结构检查与实际排版核对分别保留。")},confirmButton={TextButton(onClick={confirm=false;generate(JSONObject(draft.toString()))},enabled=enabled&&canGenerate){Text("确认生成")}},dismissButton={TextButton(onClick={confirm=false}){Text("继续编辑")}})
}

/** The current writer has text-only slides and existing positioned text objects. Keep every
 * source ID, array boundary and geometry; never join/split paragraphs or invent object IDs. */
@Composable
private fun PresentationTextEditor(draft:JSONObject,sourceKey:String,enabled:Boolean,edit:(Int,String,Int,String,Int)->Unit){
    val slides=draft.getJSONObject("presentation").getJSONArray("slides")
    var selectedPage by rememberSaveable(sourceKey){mutableIntStateOf(0)}
    var pageMenu by remember(sourceKey){mutableStateOf(false)}
    val page=selectedPage.coerceIn(0,slides.length()-1)
    val slide=slides.getJSONObject(page);val pageId=slide.getString("id")
    val paragraphs=slide.optJSONArray("paragraphs");val count=paragraphs?.length()?:0
    var selectedParagraph by rememberSaveable(sourceKey,pageId){mutableIntStateOf(0)}
    var notesOpen by rememberSaveable(sourceKey,pageId){mutableStateOf(false)}
    var shapesOpen by rememberSaveable(sourceKey,pageId){mutableStateOf(false)}
    var selectedShape by rememberSaveable(sourceKey,pageId){mutableIntStateOf(0)}
    val shapes=slide.optJSONArray("shapes");val shapeCount=shapes?.length()?:0
    Box{
        OutlinedButton(onClick={pageMenu=true},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text("第 ${page+1} / ${slides.length()} 页 ▾")}
        DropdownMenu(expanded=pageMenu&&enabled,onDismissRequest={pageMenu=false},modifier=Modifier.heightIn(max=320.dp)){
            for(i in 0 until slides.length())DropdownMenuItem(text={Text("${i+1}. ${slides.getJSONObject(i).optString("title")}",maxLines=2,overflow=TextOverflow.Ellipsis)},onClick={selectedPage=i;pageMenu=false})
        }
    }
    OutlinedTextField(slide.getString("title"),{edit(page,"title",-1,it,200)},label={Text("第 ${page+1} 页标题 · ${slide.getString("title").length}/200")},enabled=enabled,isError=slide.getString("title").isBlank(),maxLines=3,modifier=Modifier.fillMaxWidth())
    Text("正文段落 · $count 段",style=MaterialTheme.typography.titleSmall)
    if(count>0){
        val paragraph=selectedParagraph.coerceIn(0,count-1)
        if(count>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={selectedParagraph=paragraph-1},enabled=enabled&&paragraph>0){Text("上一段")}
            Text("${paragraph+1} / $count",style=MaterialTheme.typography.labelMedium)
            TextButton(onClick={selectedParagraph=paragraph+1},enabled=enabled&&paragraph+1<count){Text("下一段")}
        }
        val value=paragraphs!!.getString(paragraph)
        OutlinedTextField(value,{edit(page,"paragraphs",paragraph,it,2000)},label={Text("第 ${paragraph+1} 段正文 · ${value.length}/2000")},enabled=enabled,minLines=4,maxLines=8,modifier=Modifier.fillMaxWidth())
    }else Text("这页没有正文段落；仍可修改标题、备注和已有文本对象。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    val notes=slide.optString("notes")
    TextButton(onClick={notesOpen=!notesOpen},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text("讲稿备注 · ${notes.length} 字 ${if(notesOpen)"▴"else"▾"}")}
    if(notesOpen)OutlinedTextField(notes,{edit(page,"notes",-1,it,8000)},label={Text("本页讲稿备注 · ${notes.length}/8000")},enabled=enabled,minLines=4,maxLines=8,modifier=Modifier.fillMaxWidth())
    if(shapeCount>0){
        TextButton(onClick={shapesOpen=!shapesOpen},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text("文本对象 · $shapeCount 个 ${if(shapesOpen)"▴"else"▾"}")}
        if(shapesOpen){
            val shape=selectedShape.coerceIn(0,shapeCount-1)
            if(shapeCount>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={selectedShape=shape-1},enabled=enabled&&shape>0){Text("上一个对象")}
                Text("${shape+1} / $shapeCount",style=MaterialTheme.typography.labelMedium)
                TextButton(onClick={selectedShape=shape+1},enabled=enabled&&shape+1<shapeCount){Text("下一个对象")}
            }
            val value=shapes!!.getJSONObject(shape).getString("text")
            OutlinedTextField(value,{edit(page,"shapes",shape,it,4000)},label={Text("文本对象 ${shape+1} · ${value.length}/4000")},enabled=enabled,minLines=3,maxLines=6,modifier=Modifier.fillMaxWidth())
            Text("只修改此对象的文字，原位置和大小保留。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
