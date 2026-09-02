package app.telemetry.overlay

import android.net.Uri
import android.os.Bundle
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
        }.onSuccess{track=it;error=null}.onFailure{e->error=e.message}}
    }
    LaunchedEffect(videos,track){
        val telemetryTime=track?.startTimeMs
        if(videos.isNotEmpty()&&telemetryTime!=null){
            val times=videos.mapNotNull{it.startTimeMs}
            syncStatus=if(times.size==videos.size&&times.all{abs(it-telemetryTime)<=6*60*60*1000L})
                "Автосинхронизация включена: ${videos.size} видео по времени съёмки"
            else "Точное время найдено не во всех видео — используется последовательная склейка и ручная поправка"
            offsetTenths=0
        }
    }
    val savePicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")){destination->
        if(destination!=null && videos.isNotEmpty() && track!=null){
            exportDone=false; exportProgress=0; error=null
            exporter=VideoExporter(context,videos,destination,track!!,offsetTenths*100L,
                onProgress={exportProgress=it},
                onComplete={exportDone=true;exportProgress=-1;exporter=null},
                onError={error=it;exportProgress=-1;exporter=null}).also{it.start()}
        }
    }
    LaunchedEffect(exporter){while(exporter!=null){exporter?.updateProgress();delay(500)}}
    val selectedClip=videos.getOrNull(clipIndex)
    val elapsedBefore=videos.take(clipIndex).sumOf{it.durationMs}
    val telemetryStart=track?.startTimeMs
    val validClipStart=selectedClip?.startTimeMs?.takeIf{telemetryStart!=null&&abs(it-telemetryStart)<=6*60*60*1000L}
    val automaticOffset=validClipStart?.let{telemetryStart!!-it} ?: -elapsedBefore
    val point=track?.atVideoTime(position,automaticOffset+offsetTenths*100L)
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xff75e6a4),background=Color(0xff0b0d10))){
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
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
            if(track!=null){TelemetryPanel(point);syncStatus?.let{Text(it,color=MaterialTheme.colorScheme.primary)};Text("Ручная поправка: ${fmt(offsetTenths/10.0)} с")
                Slider(offsetTenths.toFloat().coerceIn(-216000f,216000f),{offsetTenths=it.roundToInt()},valueRange=-216000f..216000f)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){listOf(-10,-1,1,10).forEach{d->OutlinedButton({offsetTenths+=d}){Text(if(d>0)"+${d/10.0}" else "${d/10.0}")}}}
                Text("${track!!.source}: ${track!!.points.size} точек • точная настройка 0,1 с",color=Color.Gray)
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
@Composable private fun Gauge(name:String,value:String?,unit:String){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(name,color=Color.Gray,style=MaterialTheme.typography.labelSmall);Text(value?:"—",style=MaterialTheme.typography.headlineSmall);Text(unit,color=Color.Gray,style=MaterialTheme.typography.labelSmall)}}
private fun fmt(v:Double)=String.format(Locale.US,"%.1f",v)

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
