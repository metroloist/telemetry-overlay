package app.telemetry.overlay

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

@OptIn(UnstableApi::class)
class VideoExporter(
    private val context: Context,
    private val sources: List<VideoClip>,
    private val destination: Uri,
    track: TelemetryTrack,
    offsetMs: Long,
    private val onProgress: (Int) -> Unit,
    private val onComplete: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val temporary = File(context.cacheDir, "telemetry-export-${System.currentTimeMillis()}.mp4")
    private val telemetry = track
    private val manualOffsetMs = offsetMs
    private val transformer: Transformer

    init {
        transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    Thread {
                        val result = runCatching {
                            context.contentResolver.openOutputStream(destination, "w")!!.use { output ->
                                temporary.inputStream().use { input -> input.copyTo(output) }
                            }
                        }
                        temporary.delete()
                        Handler(Looper.getMainLooper()).post {
                            result.onSuccess { onProgress(100); onComplete() }
                                .onFailure { onError(it.message ?: "Не удалось сохранить видео") }
                        }
                    }.start()
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    temporary.delete()
                    onError(exportException.message ?: "Ошибка экспорта видео")
                }
            }).build()
    }

    fun start() {
        var elapsedMs=0L
        val items=sources.map{source->
            val telemetryStart=telemetry.startTimeMs
            val validStart=source.startTimeMs?.takeIf{telemetryStart!=null&&abs(it-telemetryStart)<=6*60*60*1000L}
            val automaticOffset=validStart?.let{telemetryStart!!-it} ?: -elapsedMs
            val overlay=TelemetryCanvasOverlay(telemetry,automaticOffset+manualOffsetMs)
            val videoEffects:List<Effect> = listOf(OverlayEffect(listOf(overlay)))
            elapsedMs+=source.durationMs
            EditedMediaItem.Builder(MediaItem.fromUri(source.uri))
                .setEffects(Effects(emptyList(),videoEffects)).build()
        }
        val sequence=EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_VIDEO))
            .addItems(items).build()
        transformer.start(Composition.Builder(listOf(sequence)).build(),temporary.absolutePath)
    }

    fun updateProgress() {
        val holder = ProgressHolder()
        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
    }

    fun cancel() {
        transformer.cancel()
        temporary.delete()
    }
}

data class VideoClip(val uri:Uri,val startTimeMs:Long?,val durationMs:Long)
