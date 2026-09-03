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
    private val clipStartMs:Long,
    private val anchors:List<SyncAnchor>,
    private val fineOffsetMs: Long,
) : CanvasOverlay(true) {
    private val background = Paint().apply { color = Color.argb(190, 12, 15, 18) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val telemetryMs=mappedTelemetryMs(clipStartMs+presentationTimeUs/1000L,anchors)-fineOffsetMs
        val point = track.atVideoTime(telemetryMs,0L) ?: return
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
        drawRoute(canvas,point,scale)
    }

    private fun drawRoute(canvas:Canvas,point:TelemetryPoint,scale:Float){
        val gps=track.points.filter{it.latitude!=null&&it.longitude!=null};if(gps.size<2)return
        val minLat=gps.minOf{it.latitude!!};val maxLat=gps.maxOf{it.latitude!!};val minLon=gps.minOf{it.longitude!!};val maxLon=gps.maxOf{it.longitude!!}
        val width=330f*scale;val height=250f*scale;val margin=45f*scale
        val left=canvas.width-width-margin;val top=margin
        val bg=Paint().apply{color=Color.argb(175,12,15,18)};canvas.drawRoundRect(left,top,left+width,top+height,18f*scale,18f*scale,bg)
        fun x(lon:Double)=left+20f*scale+((lon-minLon)/(maxLon-minLon).coerceAtLeast(1e-9)*(width-40f*scale)).toFloat()
        fun y(lat:Double)=top+height-20f*scale-((lat-minLat)/(maxLat-minLat).coerceAtLeast(1e-9)*(height-40f*scale)).toFloat()
        fun path(until:Long,color:Int,stroke:Float){val p=android.graphics.Path();var begun=false;for(g in gps){if(g.timeMs>until)break;val xx=x(g.longitude!!);val yy=y(g.latitude!!);if(!begun){p.moveTo(xx,yy);begun=true}else p.lineTo(xx,yy)};canvas.drawPath(p,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.STROKE;strokeWidth=stroke*scale;strokeCap=Paint.Cap.ROUND})}
        path(Long.MAX_VALUE,Color.GRAY,5f);path(point.timeMs,Color.rgb(117,230,164),7f)
        if(point.latitude!=null&&point.longitude!=null)canvas.drawCircle(x(point.longitude),y(point.latitude),9f*scale,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE})
    }

    private fun decimal(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    private fun integer(value: Int?) = value?.toString() ?: "—"
}
