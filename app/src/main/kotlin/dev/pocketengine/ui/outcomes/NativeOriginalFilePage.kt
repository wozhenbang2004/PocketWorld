package dev.pocketengine.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.*
import dev.pocketengine.features.attachments.documents.DocumentContent
import kotlinx.coroutines.*
import org.json.JSONObject

/** Actual original-format readers, not screenshots or an HTML re-creation of the file.
 * State stores only this selected-version position; parsers/FD/player stop on leaving foreground. */
@Composable
internal fun NativeOriginalFilePage(source:OwnerFilePreviewSource,back:()->Unit) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    val preferences=remember{context.getSharedPreferences("owner-file-reading-position-v1",android.content.Context.MODE_PRIVATE)}
    val lifetime=remember(source.identity){RunControl()};val scope=rememberCoroutineScope()
    var session by remember(source.identity){mutableStateOf<NativeOriginalFileSession?>(null)}
    var closed by remember(source.identity){mutableStateOf(false)}
    var busy by remember(source.identity){mutableStateOf(true)}
    var error by remember(source.identity){mutableStateOf<String?>(null)}
    var image by remember(source.identity){mutableStateOf<Bitmap?>(null)}
    var imageInfo by remember(source.identity){mutableStateOf<String?>(null)}
    var pdfPages by remember(source.identity){mutableIntStateOf(0)}
    var page by remember(source.identity){mutableIntStateOf(preferences.getInt(source.identity+".page",1).coerceAtLeast(1))}
    var offset by remember(source.identity){mutableIntStateOf(preferences.getInt(source.identity+".offset",0).coerceAtLeast(0))}
    var encoding by remember(source.identity){mutableStateOf(preferences.getString(source.identity+".encoding","UTF-8").orEmpty().takeIf{it in OwnerTextDocument.ENCODINGS}?:"UTF-8")}
    var text by remember(source.identity){mutableStateOf<JSONObject?>(null)}
    var document by remember(source.identity){mutableStateOf<DocumentContent.Result?>(null)}
    var unit by remember(source.identity){mutableIntStateOf(preferences.getInt(source.identity+".unit",1).coerceAtLeast(1))}
    var chunk by remember(source.identity){mutableIntStateOf(0)}
    var query by remember(source.identity){mutableStateOf("")}
    var results by remember(source.identity){mutableStateOf<JSONObject?>(null)}
    var action by remember(source.identity){mutableStateOf<RunControl?>(null)}
    var render by remember(source.identity){mutableIntStateOf(0)}
    val mime=source.mimeType;val pdf=mime=="application/pdf";val picture=mime.startsWith("image/");val media=mime.startsWith("audio/")||mime.startsWith("video/")
    val office=mime.contains("officedocument")||source.name.endsWith(".docx",true)||source.name.endsWith(".xlsx",true)||source.name.endsWith(".pptx",true)
    BackHandler(onBack=back)
    DisposableEffect(lifecycle,source.identity){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP){action?.cancel();lifetime.cancel();session?.close();session=null;closed=true;busy=false;error="查看已在离开前台时关闭。返回原成果后可重新打开，原件与阅读位置保留。"}}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);action?.cancel();lifetime.cancel();session?.close()}
    }
    DisposableEffect(image){val old=image;onDispose{old?.takeUnless{it.isRecycled}?.recycle()}}
    LaunchedEffect(source.identity){
        try{
            val opened=withContext(Dispatchers.IO){NativeOriginalFileSession.open(context,source,lifetime)}
            if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){opened.close();return@LaunchedEffect};session=opened
            if(pdf){pdfPages=withContext(Dispatchers.IO){opened.pdfPages()};page=page.coerceIn(1,pdfPages.coerceAtLeast(1))}
            if(picture){var pending:Bitmap?=null;try{val actual=withContext(Dispatchers.IO){opened.image().also{pending=it.bitmap}};image=actual.bitmap;pending=null;imageInfo="原尺寸 ${actual.originalWidth} × ${actual.originalHeight}"+(if(actual.sampled)" · 为显示内存已采样；导出保留全部原字节"else " · 原像素显示")}finally{pending?.recycle()}}
            busy=false;render++
        }catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){error=failure.message?.take(260)?:"原件暂不能打开；未改写或替换文件。";busy=false}
    }
    LaunchedEffect(session,page,offset,encoding,unit,chunk,render){
        val original=session?:return@LaunchedEffect;if(closed||picture||media)return@LaunchedEffect
        busy=true;error=null;val control=RunControl();action?.cancel();action=control
        try{
            if(pdf){var pending:Bitmap?=null;try{val actual=withContext(Dispatchers.IO){original.pdfPage(page,1536).also{pending=it}};image=actual;pending=null;preferences.edit().putInt(source.identity+".page",page).apply()}finally{pending?.recycle()}}
            else if(office){document=withContext(Dispatchers.IO){original.document(unit,chunk,control)};preferences.edit().putInt(source.identity+".unit",unit).apply()}
            else{text=withContext(Dispatchers.IO){original.text(encoding,offset,12000,control)};preferences.edit().putInt(source.identity+".offset",offset).putString(source.identity+".encoding",encoding).apply()}
        }catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){error=failure.message?.take(260);text=null;document=null}
        finally{if(action===control){action=null;busy=false};control.cancel()}
    }
    LaunchedEffect(session){val original=session?:return@LaunchedEffect
        while(isActive&&!closed){delay(1500);try{withContext(Dispatchers.IO){original.current()}}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){closed=true;action?.cancel();lifetime.cancel();original.close();session=null;image=null;error="原来源、受众或固定版本已失效，查看已关闭；未换成其他文件。";break}}
    }
    fun find(next:Boolean=false){
        val original=session?:return;if(closed||query.isEmpty()||busy)return
        action?.cancel();val control=RunControl();action=control;busy=true;error=null;val actualQuery=query;val actualEncoding=encoding
        val cursor=if(next)results?.optInt(if(pdf)"nextPage"else "nextOffset",if(pdf)1 else 0)?:if(pdf)1 else 0 else if(pdf)1 else 0
        scope.launch{try{val found=withContext(Dispatchers.IO){if(pdf)original.pdfSearch(actualQuery,cursor,control)else original.searchText(actualEncoding,actualQuery,cursor,control)};if(!closed&&action===control&&query==actualQuery&&encoding==actualEncoding)results=found}
            catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){error=failure.message?.take(260)}finally{if(action===control){action=null;busy=false};control.cancel()}}
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()){
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick=back){Text("返回成果")};Column(Modifier.weight(1f)){Text(source.name,style=MaterialTheme.typography.titleMedium,maxLines=2);Text("选定的固定原件",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{Text(it,modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        if(!closed){
            if(pdf){
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={page--},enabled=page>1&&!busy){Text("上一页")};Text("$page / $pdfPages 页",style=MaterialTheme.typography.labelMedium);TextButton(onClick={page++},enabled=page<pdfPages&&!busy){Text("下一页")}
                    var input by remember(page){mutableStateOf(page.toString())};OutlinedTextField(input,{input=it.filter(Char::isDigit).take(7)},singleLine=true,label={Text("页码")},modifier=Modifier.width(100.dp));TextButton(onClick={input.toIntOrNull()?.takeIf{it in 1..pdfPages}?.let{page=it}},enabled=!busy){Text("跳转")}}
            }
            if(pdf||!picture&&!media&&!office){
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(query,{query=it.take(256);results=null},singleLine=true,label={Text(if(pdf)"搜索PDF原文层"else "字面搜索（区分大小写）")},modifier=Modifier.weight(1f));TextButton(onClick={find()},enabled=!busy&&query.isNotEmpty()&&(!pdf||session?.pdfSearchAvailable()==true)){Text("查找")}}
                if(pdf&&session?.pdfSearchAvailable()==false)Text("此设备没有原文搜索API；原页仍可缩放，也可回原入口明确选择OCR。",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=16.dp))
                results?.let{found->Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){
                    val hits=found.optJSONArray("matches");if(hits!=null)for(i in 0 until hits.length()){val hit=hits.getJSONObject(i);TextButton(onClick={if(pdf)page=hit.getInt("page")else offset=hit.getInt("offset")},enabled=!busy){Text(if(pdf)"第${hit.getInt("page")}页"else "位置${hit.getInt("offset")+1}")}}
                    if(!found.isNull(if(pdf)"nextPage"else "nextOffset"))TextButton(onClick={find(true)},enabled=!busy){Text("继续后续范围")}
                };if(found.optBoolean("pageMatchesOmitted"))Text("某页命中过多，仅显示前100处，请缩小关键词；未把省略结果当作完整。",style=MaterialTheme.typography.labelSmall)}
            }
            when{
                media&&session!=null->NativeMediaFilePlayer(session!!,source,Modifier.weight(1f))
                picture||pdf->{imageInfo?.let{Text(it,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=16.dp))};image?.let{OriginalZoomableImage(it,Modifier.weight(1f),picture)}?:Spacer(Modifier.weight(1f))}
                office->{
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){var chosen by remember(unit){mutableStateOf(unit.toString())};OutlinedTextField(chosen,{chosen=it.filter(Char::isDigit).take(7)},singleLine=true,label={Text(if(document?.format=="xlsx")"工作表编号"else if(document?.format=="pptx")"幻灯片编号"else "段落起点")},modifier=Modifier.width(160.dp));TextButton(onClick={chosen.toIntOrNull()?.takeIf{it>0}?.let{unit=it;chunk=0}},enabled=!busy){Text("定位")}}
                    Text("结构与版式投影：原字体、分页、图表、合并格和公式计算可能不保留；原件未改动。",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=16.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()){document?.let{OriginalOfficeProjection(it)}}
                    document?.let{actual->if(actual.nextUnit>0)TextButton(onClick={unit=actual.nextUnit;chunk=actual.nextChunk},enabled=!busy){Text(if(actual.nextUnit==unit)"读取本单元后续内容"else "下一单元")}}
                }
                else->{
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){for(value in OwnerTextDocument.ENCODINGS.sorted())FilterChip(selected=encoding==value,onClick={encoding=value;offset=0;results=null},label={Text(value)},enabled=!busy)}
                    var markdown by remember(source.identity){mutableStateOf(source.name.endsWith(".md",true))}
                    if(source.name.endsWith(".md",true))Row{FilterChip(markdown,{markdown=true},label={Text("Markdown")});FilterChip(!markdown,{markdown=false},label={Text("原文")})}
                    val content=text?.optString("content").orEmpty()
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)){
                        if(markdown)RichConversationMessageBody(content)else SelectionContainer{Text(content,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium)}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={offset=0},enabled=!busy&&offset>0){Text("从开头")};if(text?.isNull("nextOffset")==false)TextButton(onClick={offset=text!!.getInt("nextOffset")},enabled=!busy){Text("读取后续")};Text("字符位置 ${offset+1}",style=MaterialTheme.typography.labelSmall)}
                }
            }
        }else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun OriginalZoomableImage(bitmap:Bitmap,modifier:Modifier,allowTurn:Boolean){
    var zoom by remember(bitmap){mutableFloatStateOf(1f)};var pan by remember(bitmap){mutableStateOf(Offset.Zero)};var turn by remember(bitmap){mutableFloatStateOf(0f)}
    val transformation=rememberTransformableState{factor,move,_->zoom=(zoom*factor).coerceIn(1f,8f);pan=if(zoom<=1f)Offset.Zero else Offset((pan.x+move.x).coerceIn(-8192f,8192f),(pan.y+move.y).coerceIn(-8192f,8192f))}
    Column(modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){TextButton(onClick={zoom=(zoom/1.5f).coerceAtLeast(1f);if(zoom==1f)pan=Offset.Zero}){Text("缩小")};TextButton(onClick={zoom=1f;pan=Offset.Zero;turn=0f}){Text("适合页面")};TextButton(onClick={zoom=(zoom*1.5f).coerceAtMost(8f)}){Text("放大")};if(allowTurn)TextButton(onClick={turn=(turn+90f)%360f}){Text("旋转查看")}}
        Box(Modifier.weight(1f).fillMaxWidth().transformable(transformation),contentAlignment=Alignment.Center){Image(bitmap.asImageBitmap(),"这份固定原件",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y;rotationZ=turn})}
    }
}

@Composable
private fun OriginalOfficeProjection(result:DocumentContent.Result){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(result.status !in setOf("ok","partial","truncated","empty_range"))Text("此文档未能形成可用预览（${result.status}）；原件保留，可返回后导出或用支持的阅读器打开。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        if(result.chunks.isEmpty())Text("当前单元没有可投影的文字或单元格；不代表原文件为空。")
        if(result.format=="xlsx"){
            val cells=result.chunks.filter{it.kind=="table_cell"||it.kind=="formula"}
            cells.firstOrNull()?.locator?.get("sheet")?.let{Text(it,style=MaterialTheme.typography.titleMedium)}
            OfficeProjectionGrid(cells,true)
            if(cells.any{it.kind=="formula"||it.locator["valueSource"]=="cached_formula"})Text("公式只显示原文件已存值或未计算的公式文字，未重新计算。",style=MaterialTheme.typography.labelSmall)
        }else{
            val groups=result.chunks.groupBy{block->block.locator["table"]?.let{"table:$it:${if(result.format=="pptx")block.unit else "document"}"}?:"paragraph:${block.unit}:${block.locator["paragraph"].orEmpty()}"}
            for((key,blocks) in groups){
                if(key.startsWith("table:")){Text("原文档表格 · 当前读取范围",style=MaterialTheme.typography.labelMedium);OfficeProjectionGrid(blocks,false)}
                else Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.padding(16.dp)){Text(if(result.format=="pptx")"幻灯片 ${blocks.first().unit}"else "段落 ${blocks.first().unit}",style=MaterialTheme.typography.labelSmall);SelectionContainer{Text(blocks.joinToString(""){it.text},style=MaterialTheme.typography.bodyLarge)}}}
            }
        }
        var details by remember(result){mutableStateOf(false)}
        if(result.warnings.isNotEmpty())TextButton(onClick={details=!details}){Text(if(details)"收起读取说明"else "格式与读取说明")}
        if(details)result.warnings.forEach{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable
private fun OfficeProjectionGrid(chunks:List<DocumentContent.Chunk>,spreadsheet:Boolean){
    fun row(value:DocumentContent.Chunk)=if(spreadsheet)value.locator["cell"]?.filter(Char::isDigit).orEmpty()else value.locator["row"].orEmpty()
    fun column(value:DocumentContent.Chunk)=if(spreadsheet)value.locator["cell"]?.filter(Char::isLetter).orEmpty()else value.locator["column"].orEmpty()
    val cells=chunks.filter{row(it).isNotEmpty()&&column(it).isNotEmpty()}
    val rowKeys=cells.map(::row).distinct().sortedWith(compareBy<String>{it.length}.thenBy{it});val columns=cells.map(::column).distinct().sortedWith(compareBy<String>{it.length}.thenBy{it})
    var rowStart by remember(chunks){mutableIntStateOf(0)};var columnStart by remember(chunks){mutableIntStateOf(0)}
    val visibleRows=rowKeys.drop(rowStart).take(80);val visibleColumns=columns.drop(columnStart).take(32)
    Column{
        if(rowKeys.size>80||columns.size>32)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){
            TextButton(onClick={rowStart=(rowStart-80).coerceAtLeast(0)},enabled=rowStart>0){Text("前80行")};TextButton(onClick={rowStart+=80},enabled=rowStart+80<rowKeys.size){Text("后80行")}
            TextButton(onClick={columnStart=(columnStart-32).coerceAtLeast(0)},enabled=columnStart>0){Text("前32列")};TextButton(onClick={columnStart+=32},enabled=columnStart+32<columns.size){Text("后32列")}
        }
        Column(Modifier.horizontalScroll(rememberScrollState())){
            Row{Text("行",modifier=Modifier.width(48.dp));for(label in visibleColumns)Text(label,modifier=Modifier.width(144.dp),style=MaterialTheme.typography.labelLarge)}
            for(label in visibleRows)Row{
                Text(label,modifier=Modifier.width(48.dp));for(col in visibleColumns){
                    val original=cells.filter{row(it)==label&&column(it)==col};val text=buildString{var previous:String?=null;for(block in original){val paragraph="${block.unit}:${block.locator["paragraph"].orEmpty()}";if(isNotEmpty()&&previous!=paragraph)append('\n');append(block.text);previous=paragraph}}
                    Surface(Modifier.width(144.dp).heightIn(min=44.dp),border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){SelectionContainer{Text(text,modifier=Modifier.padding(6.dp),style=MaterialTheme.typography.bodySmall)}}
                }
            }
        }
    }
}
