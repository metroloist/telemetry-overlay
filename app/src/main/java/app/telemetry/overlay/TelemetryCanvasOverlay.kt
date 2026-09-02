package app.telemetry.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import java.util.Locale

/** Draws the approved two-row panel directly onto every exported video frame. */
@OptIn(UnstableApi::class)
class TelemetryCanvasOverlay(
    private val track: TelemetryTrack,
    private val offsetMs: Long,
) : CanvasOverlay(true) {
    private val background = Paint().apply { color = Color.argb(190, 12, 15, 18) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val point = track.atVideoTime(presentationTimeUs / 1000L, offsetMs) ?: return
        val scale = canvas.width / 1920f
        val left = 58f * scale
        val bottom = canvas.height - 43f * scale
        val panelHeight = 135f * scale
        val panelWidth = minOf(canvas.width - left * 2, 1180f * scale)
        canvas.drawRect(left, bottom - panelHeight, left + panelWidth, bottom, background)

        text.textSize = 39f * scale
        val x = left + 15f * scale
        val row1 = bottom - 76f * scale
        val row2 = bottom - 25f * scale
        canvas.drawText(
            "СКОРОСТЬ  ${decimal(point.speedKmh)} км/ч     ПУЛЬС  ${integer(point.heartRate)}     МОЩНОСТЬ  ${integer(point.powerW)} Вт",
            x, row1, text
        )
        canvas.drawText(
            "ДИСТАНЦИЯ  ${decimal(point.distanceM?.div(1000.0))} км     НАБОР  ${integer(point.ascentM?.toInt())} м     ВЫСОТА  ${integer(point.altitudeM?.toInt())} м",
            x, row2, text
        )
    }

    private fun decimal(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    private fun integer(value: Int?) = value?.toString() ?: "—"
}
