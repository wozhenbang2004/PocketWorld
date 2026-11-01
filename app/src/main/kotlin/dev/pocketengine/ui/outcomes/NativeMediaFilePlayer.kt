package dev.pocketengine.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.*
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pocketengine.features.attachments.NativeOriginalFileSession
import dev.pocketengine.features.attachments.OwnerFilePreviewSource
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** File playback only: original local descriptor, no URL, live session, autoplay or background service. */
@Composable
internal fun NativeMediaFilePlayer(session:NativeOriginalFileSession,source:OwnerFilePreviewSource,modifier:Modifier=Modifier){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val player=remember(session){MediaPlayer()};val released=remember(session){AtomicBoolean(false)}
    val preferences=remember{context.getSharedPreferences("owner-file-reading-position-v1",Context.MODE_PRIVATE)}
    val audio=remember{context.getSystemService(Context.AUDIO_SERVICE) as AudioManager}
    var descriptor by remember(session){mutableStateOf<ParcelFileDescriptor?>(null)}
    var surface by remember(session){mutableStateOf<Surface?>(null)}
    var ready by remember(session){mutableStateOf(false)};var playing by remember(session){mutableStateOf(false)}
    var duration by remember(session){mutableIntStateOf(0)};var position by remember(session){mutableIntStateOf(0)}
    var seeking by remember(session){mutableStateOf<Float?>(null)};var failure by remember(session){mutableStateOf<String?>(null)}
    var ratio by remember(session){mutableFloatStateOf(16f/9f)}
    val attributes=remember{AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(if(source.mimeType.startsWith("video/"))AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC).build()}
    val focus=remember(player){AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener{change->
        if(change<0&&!released.get())runCatching{if(player.isPlaying)player.pause();playing=false}
    }.build()}
    DisposableEffect(player){
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener{if(!released.get()){duration=it.duration.coerceAtLeast(0);position=preferences.getInt(source.identity+".mediaMs",0).coerceIn(0,duration);if(position>0)it.seekTo(position);ready=true}}
        player.setOnCompletionListener{if(!released.get()){playing=false;position=duration;audio.abandonAudioFocusRequest(focus)}}
        player.setOnErrorListener{_,what,extra->if(!released.get()){failure="当前设备不能播放这份媒体（$what/$extra）；原件可导出，未重新生成。";ready=false;playing=false};true}
        player.setOnVideoSizeChangedListener{_,width,height->if(width>0&&height>0&&!released.get())ratio=(width.toFloat()/height).coerceIn(.1f,10f)}
        onDispose{
            if(released.compareAndSet(false,true)){
                if(ready)runCatching{preferences.edit().putInt(source.identity+".mediaMs",player.currentPosition.coerceAtLeast(0)).apply()}
                runCatching{audio.abandonAudioFocusRequest(focus)};runCatching{player.setSurface(null)};runCatching{player.release()};surface?.release();surface=null;descriptor?.close();descriptor=null
            }
        }
    }
    LaunchedEffect(session){
        try{val fd=withContext(Dispatchers.IO){session.openMediaDescriptor()};if(released.get()){fd.close();return@LaunchedEffect};descriptor=fd;player.setDataSource(fd.fileDescriptor,0,source.sizeBytes);player.prepareAsync()}
        catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){failure="本机播放器未能读取这份固定原件；可以返回后导出，不会替换文件。"}
    }
    LaunchedEffect(player,ready){if(!ready)return@LaunchedEffect;var lastSaved=0L
        while(isActive&&!released.get()){delay(500);runCatching{position=player.currentPosition.coerceIn(0,duration);playing=player.isPlaying;if(android.os.SystemClock.elapsedRealtime()-lastSaved>2000){preferences.edit().putInt(source.identity+".mediaMs",position).apply();lastSaved=android.os.SystemClock.elapsedRealtime()}}}
    }
    fun originalAction(call:()->Unit){scope.launch{try{withContext(Dispatchers.IO){session.current()};if(ready&&!released.get())call()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){failure="原文件或查看权限已失效，播放已停止。";runCatching{player.pause()};playing=false}}}
    Column(modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(source.mimeType.startsWith("video/"))AndroidView(factory={android->TextureView(android).also{view->view.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(texture:SurfaceTexture,width:Int,height:Int){if(!released.get()){surface?.release();surface=Surface(texture);player.setSurface(surface)}}
            override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,width:Int,height:Int){}
            override fun onSurfaceTextureUpdated(texture:SurfaceTexture){}
            override fun onSurfaceTextureDestroyed(texture:SurfaceTexture):Boolean{if(!released.get())runCatching{player.setSurface(null)};surface?.release();surface=null;return true}
        }}},modifier=Modifier.fillMaxWidth().aspectRatio(ratio))
        Text(if(ready)"${mediaTime(position)} / ${mediaTime(duration)}"else "正在读取本机原件…",style=MaterialTheme.typography.bodyMedium)
        Slider(value=(seeking?:position.toFloat()).coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={seeking=it},onValueChangeFinished={val at=seeking?.toInt();seeking=null;if(at!=null)originalAction{player.seekTo(at);position=at}},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=ready)
        Button(onClick={originalAction{
            if(player.isPlaying){player.pause();playing=false;audio.abandonAudioFocusRequest(focus)}
            else if(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){player.start();playing=true}
            else failure="当前音频焦点不可用，请稍后明确重试播放。"
        }},enabled=ready,modifier=Modifier.fillMaxWidth()){Text(if(playing)"暂停"else "播放原件")}
        failure?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        Text("阅读位置只关联这份固定文件。离开前台即关闭播放器和文件句柄，不在后台继续播放。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
private fun mediaTime(ms:Int):String {val seconds=ms.coerceAtLeast(0)/1000;return "%d:%02d".format(java.util.Locale.ROOT,seconds/60,seconds%60)}
