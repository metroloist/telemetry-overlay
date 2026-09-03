package app.telemetry.overlay

import android.net.Uri
import android.os.Bundle
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{App()}}
}

@Composable private fun App(){
    val context=androidx.compose.ui.platform.LocalContext.current
    var videos by remember{mutableStateOf<List<VideoClip>>(emptyList())}; var track by remember{mutableStateOf<TelemetryTrack?>(null)}
    var error by remember{mutableStateOf<String?>(null)}; var offsetTenths by remember{mutableIntStateOf(0)}
    var anchors by remember{mutableStateOf<List<SyncAnchor>>(emptyList())}
    var fitCursorMs by remember{mutableLongStateOf(0L)}
    var syncStatus by remember{mutableStateOf<String?>(null)}
    var exporter by remember{mutableStateOf<VideoExporter?>(null)}
    var exportProgress by remember{mutableIntStateOf(-1)}
    var exportDone by remember{mutableStateOf(false)}
    val player=remember{ExoPlayer.Builder(context).build()}
    DisposableEffect(Unit){onDispose{player.release()}}
    var position by remember{mutableLongStateOf(0)};var clipIndex by remember{mutableIntStateOf(0)}
    LaunchedEffect(player){while(true){position=player.currentPosition;clipIndex=player.currentMediaItemIndex.coerceAtLeast(0);delay(100)}}
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        if(uris.isNotEmpty()){
            videos=uris.map{uri->readVideoInfo(context,uri)}.sortedWith(compareBy(nullsLast()){it.startTimeMs})
            player.setMediaItems(videos.map{MediaItem.fromUri(it.uri)});player.prepare()
        }
    }
    val addVideoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        uri?.let{
            videos=(videos+readVideoInfo(context,it)).distinctBy{clip->clip.uri}
                .sortedWith(compareBy(nullsLast()){clip->clip.startTimeMs})
            player.setMediaItems(videos.map{clip->MediaItem.fromUri(clip.uri)});player.prepare()
        }
    }
    val dataPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        uri?.let{runCatching{
            val bytes=context.contentResolver.openInputStream(it)!!.use{stream->stream.readBytes()}
            val isFit=bytes.size>=12 && String(bytes,8,4,Charsets.US_ASCII)==".FIT"
            if(isFit) FitParser.parse(ByteArrayInputStream(bytes)) else GpxParser.parse(ByteArrayInputStream(bytes))
        }.onSuccess{track=it;fitCursorMs=0;anchors=emptyList();error=null}.onFailure{e->error=e.message}}
    }
    LaunchedEffect(videos,track){
        val telemetry=track
        if(videos.isNotEmpty()&&telemetry!=null){
            syncStatus="Выберите кадр видео и соответствующую точку FIT"
            offsetTenths=0
        }
    }
    val savePicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")){destination->
        if(destination!=null && videos.isNotEmpty() && track!=null){
            exportDone=false; exportProgress=0; error=null
            exporter=VideoExporter(context,videos,destination,track!!,anchors,offsetTenths*100L,
                onProgress={exportProgress=it},
                onComplete={exportDone=true;exportProgress=-1;exporter=null},
                onError={error=it;exportProgress=-1;exporter=null}).also{it.start()}
        }
    }
    LaunchedEffect(exporter){while(exporter!=null){exporter?.updateProgress();delay(500)}}
    val videoGlobalMs=videos.take(clipIndex).sumOf{it.durationMs}+position
    // Before the first anchor, show the FIT point selected with the slider.
    // Once linked, follow the playing video through the established mapping.
    val previewPoint=if(anchors.isEmpty()) track?.atVideoTime(fitCursorMs,0L)
        else track?.atVideoTime(mappedTelemetryMs(videoGlobalMs,anchors),0L)
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xff75e6a4),background=Color(0xff0b0d10))){
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("TELEMETRY OVERLAY",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({videoPicker.launch(arrayOf("video/*"))}){Text("Выбрать видео")};Button({dataPicker.launch(arrayOf("*/*"))}){Text("GPX / FIT")}}
            if(videos.isNotEmpty()){
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton({addVideoPicker.launch(arrayOf("video/*"))}){Text("Добавить ещё видео")}
                    TextButton({videos=emptyList();player.clearMediaItems();syncStatus=null}){Text("Очистить")}
                }
                Text("Выбрано видео: ${videos.size}",color=Color.Gray)
                AndroidView({PlayerView(it).apply{this.player=player;useController=true}},Modifier.fillMaxWidth().aspectRatio(16/9f))
            }
            if(track!=null){
                Text(if(anchors.isEmpty())"ДАННЫЕ В ВЫБРАННОЙ ТОЧКЕ FIT" else "ТЕЛЕМЕТРИЯ ПО ВРЕМЕНИ ВИДЕО",color=Color.Gray,style=MaterialTheme.typography.labelMedium)
                TelemetryPanel(previewPoint);syncStatus?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
                val fitDuration=(track!!.points.last().timeMs-track!!.points.first().timeMs).coerceAtLeast(1L)
                Text("Точка FIT: ${formatTime(fitCursorMs)} • кадр видео: ${formatTime(videoGlobalMs)}")
                Slider(fitCursorMs.toFloat(),{fitCursorMs=it.toLong()},valueRange=0f..fitDuration.toFloat())
                RoutePreview(track!!,fitCursorMs)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button({anchors=(anchors+SyncAnchor(videoGlobalMs,fitCursorMs)).takeLast(2);syncStatus="Связано точек: ${anchors.size}"}){Text(if(anchors.isEmpty())"Связать точку 1" else "Связать точку 2")}
                    TextButton({anchors=emptyList();syncStatus="Привязки сброшены"}){Text("Сбросить")}
                }
                Text("Сдвиг выбранной точки FIT")
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    listOf(-1000L to "−1 с",-500L to "−0,5 с",500L to "+0,5 с",1000L to "+1 с").forEach{(delta,label)->
                        OutlinedButton({fitCursorMs=(fitCursorMs+delta).coerceIn(0L,fitDuration)}){Text(label)}
                    }
                }
                Text("${track!!.source}: ${track!!.points.size} точек • точная настройка 0,5 с",color=Color.Gray)
                Button(
                    onClick={savePicker.launch("telemetry-${System.currentTimeMillis()}.mp4")},
                    enabled=videos.isNotEmpty() && exportProgress<0,
                    modifier=Modifier.fillMaxWidth()
                ){Text("Экспортировать MP4")}
                if(exportProgress>=0 && !exportDone){
                    LinearProgressIndicator(progress={exportProgress/100f},Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text("Экспорт: $exportProgress%")
                        TextButton({exporter?.cancel();exporter=null;exportProgress=-1}){Text("Отмена")}
                    }
                }
                if(exportDone) Text("Видео сохранено",color=MaterialTheme.colorScheme.primary)
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}

@Composable private fun TelemetryPanel(p:TelemetryPoint?){
    Row(Modifier.fillMaxWidth().background(Color(0xaa11161d)).padding(12.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){
        Gauge("СКОРОСТЬ",p?.speedKmh?.let{fmt(it)},"км/ч"); Gauge("ПУЛЬС",p?.heartRate?.toString(),"уд/мин"); Gauge("МОЩНОСТЬ",p?.powerW?.toString(),"Вт")
    }
    Row(Modifier.fillMaxWidth().background(Color(0xaa11161d)).padding(12.dp),horizontalArrangement=Arrangement.SpaceEvenly){
        Gauge("ДИСТАНЦИЯ",p?.distanceM?.let{fmt(it/1000)},"км");Gauge("НАБОР",p?.ascentM?.let{fmt(it)},"м");Gauge("ВЫСОТА",p?.altitudeM?.let{fmt(it)},"м")
    }
}
@Composable private fun Gauge(name:String,value:String?,unit:String){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(name,color=Color(0xffb8bdc7),style=MaterialTheme.typography.labelSmall);Text(value?:"—",color=Color.White,style=MaterialTheme.typography.headlineSmall);Text(unit,color=Color(0xffb8bdc7),style=MaterialTheme.typography.labelSmall)}}
private fun fmt(v:Double)=String.format(Locale.US,"%.1f",v)
private fun formatTime(ms:Long)=String.format(Locale.US,"%02d:%02d:%02d",ms/3_600_000,(ms/60_000)%60,(ms/1000)%60)

@Composable private fun RoutePreview(track:TelemetryTrack,cursorMs:Long){
    val gps=track.points.filter{it.latitude!=null&&it.longitude!=null};if(gps.size<2)return
    val now=track.points.first().timeMs+cursorMs;val minLat=gps.minOf{it.latitude!!};val maxLat=gps.maxOf{it.latitude!!};val minLon=gps.minOf{it.longitude!!};val maxLon=gps.maxOf{it.longitude!!}
    Canvas(Modifier.fillMaxWidth().height(150.dp).background(Color(0xff11161d)).padding(12.dp)){
        fun px(v:Double):Float=((v-minLon)/(maxLon-minLon).coerceAtLeast(1e-9)*size.width).toFloat()
        fun py(v:Double):Float=(size.height-(v-minLat)/(maxLat-minLat).coerceAtLeast(1e-9)*size.height).toFloat()
        fun route(until:Long,color:Color){
            val path=androidx.compose.ui.graphics.Path();var first=true
            gps.forEach{g->if(g.timeMs<=until){if(first){path.moveTo(px(g.longitude!!),py(g.latitude!!));first=false}else path.lineTo(px(g.longitude!!),py(g.latitude!!))}}
            drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        }
        route(Long.MAX_VALUE,Color.Gray);route(now,Color(0xff75e6a4))
    }
}

private fun readVideoInfo(context:android.content.Context,uri:Uri):VideoClip{
    val retriever=MediaMetadataRetriever()
    return try{
        retriever.setDataSource(context,uri)
        val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val raw=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        val patterns=listOf("yyyyMMdd'T'HHmmss.SSS'Z'","yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'","yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        val start=raw?.let{value->patterns.firstNotNullOfOrNull{pattern->runCatching{
            SimpleDateFormat(pattern,Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC");isLenient=false}.parse(value)?.time
        }.getOrNull()}}
        VideoClip(uri,start,duration)
    }finally{retriever.release()}
}
