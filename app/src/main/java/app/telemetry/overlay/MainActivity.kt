package app.telemetry.overlay

import android.net.Uri
import android.os.Bundle
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

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{App()}}
}

@Composable private fun App(){
    val context=androidx.compose.ui.platform.LocalContext.current
    var video by remember{mutableStateOf<Uri?>(null)}; var track by remember{mutableStateOf<TelemetryTrack?>(null)}
    var error by remember{mutableStateOf<String?>(null)}; var offsetTenths by remember{mutableIntStateOf(0)}
    var exporter by remember{mutableStateOf<VideoExporter?>(null)}
    var exportProgress by remember{mutableIntStateOf(-1)}
    var exportDone by remember{mutableStateOf(false)}
    val player=remember{ExoPlayer.Builder(context).build()}
    DisposableEffect(Unit){onDispose{player.release()}}
    var position by remember{mutableLongStateOf(0)}
    LaunchedEffect(player){while(true){position=player.currentPosition;delay(100)}}
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{video=it;player.setMediaItem(MediaItem.fromUri(it));player.prepare()}}
    val dataPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        uri?.let{runCatching{
            val bytes=context.contentResolver.openInputStream(it)!!.use{stream->stream.readBytes()}
            val isFit=bytes.size>=12 && String(bytes,8,4,Charsets.US_ASCII)==".FIT"
            if(isFit) FitParser.parse(ByteArrayInputStream(bytes)) else GpxParser.parse(ByteArrayInputStream(bytes))
        }.onSuccess{track=it;error=null}.onFailure{e->error=e.message}}
    }
    val savePicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")){destination->
        if(destination!=null && video!=null && track!=null){
            exportDone=false; exportProgress=0; error=null
            exporter=VideoExporter(context,video!!,destination,track!!,offsetTenths*100L,
                onProgress={exportProgress=it},
                onComplete={exportDone=true;exportProgress=-1;exporter=null},
                onError={error=it;exportProgress=-1;exporter=null}).also{it.start()}
        }
    }
    LaunchedEffect(exporter){while(exporter!=null){exporter?.updateProgress();delay(500)}}
    val point=track?.atVideoTime(position,offsetTenths*100L)
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xff75e6a4),background=Color(0xff0b0d10))){
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("TELEMETRY OVERLAY",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({videoPicker.launch(arrayOf("video/*"))}){Text("Выбрать видео")};Button({dataPicker.launch(arrayOf("*/*"))}){Text("GPX / FIT")}}
            if(video!=null) AndroidView({PlayerView(it).apply{this.player=player;useController=true}},Modifier.fillMaxWidth().aspectRatio(16/9f))
            if(track!=null){TelemetryPanel(point);Text("Сдвиг телеметрии: ${fmt(offsetTenths/10.0)} с")
                Slider(offsetTenths.toFloat(),{offsetTenths=it.toInt()},valueRange=-600f..600f,steps=1199)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){listOf(-10,-1,1,10).forEach{d->OutlinedButton({offsetTenths+=d}){Text(if(d>0)"+${d/10.0}" else "${d/10.0}")}}}
                Text("${track!!.source}: ${track!!.points.size} точек • точная настройка 0,1 с",color=Color.Gray)
                Button(
                    onClick={savePicker.launch("telemetry-${System.currentTimeMillis()}.mp4")},
                    enabled=video!=null && exportProgress<0,
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
