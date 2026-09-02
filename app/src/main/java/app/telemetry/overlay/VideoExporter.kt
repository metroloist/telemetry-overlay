package app.telemetry.overlay

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import android.os.Handler
import android.os.Looper

@OptIn(UnstableApi::class)
class VideoExporter(
    private val context: Context,
    private val source: Uri,
    private val destination: Uri,
    track: TelemetryTrack,
    offsetMs: Long,
    private val onProgress: (Int) -> Unit,
    private val onComplete: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val temporary = File(context.cacheDir, "telemetry-export-${System.currentTimeMillis()}.mp4")
    private val overlay = TelemetryCanvasOverlay(track, offsetMs)
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
        val videoEffects: List<Effect> = listOf(OverlayEffect(listOf(overlay)))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
        transformer.start(item, temporary.absolutePath)
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
