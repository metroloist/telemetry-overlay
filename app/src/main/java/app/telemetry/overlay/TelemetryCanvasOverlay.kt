package app.telemetry.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import java.util.Locale

/** Draws telemetry, training zones, route and watermark onto every exported frame. */
@OptIn(UnstableApi::class)
class TelemetryCanvasOverlay(
    private val track: TelemetryTrack,
    private val anchors:List<SyncAnchor>,
    private val fineOffsetMs: Long,
) : CanvasOverlay(true) {
    private val background=Paint().apply{color=Color.argb(190,12,15,18)}
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.WHITE
        typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
    }
    private val zoneColors=intArrayOf(
        Color.rgb(68,199,105),
        Color.rgb(250,210,61),
        Color.rgb(255,143,31),
        Color.rgb(235,54,54),
        Color.rgb(139,24,75),
    )

    override fun onDraw(canvas:Canvas,presentationTimeUs:Long){
        canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
        val scale=canvas.width/1920f
        drawWatermark(canvas,scale)

        val telemetryMs=mappedTelemetryMs(presentationTimeUs/1000L,anchors)-fineOffsetMs
        val point=track.atVideoTime(telemetryMs,0L)?:return
        val power10s=track.averagePowerAt(telemetryMs,0L,10_000L)

        val left=58f*scale
        val bottom=canvas.height-43f*scale
        val panelHeight=245f*scale
        val panelWidth=minOf(canvas.width-left*2,1180f*scale)
        canvas.drawRoundRect(left,bottom-panelHeight,left+panelWidth,bottom,18f*scale,18f*scale,background)

        val x=left+18f*scale
        text.textSize=34f*scale
        val row1=bottom-190f*scale
        val row2=bottom-135f*scale
        val row3=bottom-80f*scale
        val row4=bottom-25f*scale

        canvas.drawText("СКОРОСТЬ  ${decimal(point.speedKmh)} км/ч",x,row1,text)
        canvas.drawText("МОЩНОСТЬ 10 с  ${integer(power10s)} Вт",x+455f*scale,row1,text)
        drawZoneIndicator(canvas,"ПУЛЬС",point.heartRate,"уд/мин",heartRateZone(point.heartRate),x,row2,scale)
        drawZoneIndicator(canvas,"МГН. МОЩНОСТЬ",point.powerW,"Вт",powerZone(point.powerW),x,row3,scale)
        canvas.drawText(
            "ДИСТАНЦИЯ  ${decimal(point.distanceM?.div(1000.0))} км     НАБОР  ${integer(point.ascentM?.toInt())} м     ВЫСОТА  ${integer(point.altitudeM?.toInt())} м",
            x,row4,text
        )
        drawRoute(canvas,point,scale)
    }

    private fun drawZoneIndicator(canvas:Canvas,label:String,value:Int?,unit:String,zone:Int,left:Float,baseline:Float,scale:Float){
        text.textSize=31f*scale
        text.color=Color.WHITE
        canvas.drawText(label,left,baseline,text)

        val segmentsLeft=left+265f*scale
        val segmentWidth=34f*scale
        val gap=8f*scale
        val top=baseline-28f*scale
        val bottom=baseline+5f*scale
        for(index in 0..4){
            val base=zoneColors[index]
            val active=zone==index+1
            val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                color=Color.argb(if(active)255 else 105,Color.red(base),Color.green(base),Color.blue(base))
                style=Paint.Style.FILL
            }
            val segmentLeft=segmentsLeft+index*(segmentWidth+gap)
            canvas.drawRoundRect(segmentLeft,top,segmentLeft+segmentWidth,bottom,7f*scale,7f*scale,fill)
            if(active){
                canvas.drawRoundRect(segmentLeft,top,segmentLeft+segmentWidth,bottom,7f*scale,7f*scale,Paint(Paint.ANTI_ALIAS_FLAG).apply{
                    color=Color.WHITE
                    style=Paint.Style.STROKE
                    strokeWidth=3f*scale
                })
            }
        }

        text.color=if(zone in 1..5)zoneColors[zone-1] else Color.WHITE
        val shown=value?.toString()?:"—"
        canvas.drawText("$shown $unit  Z${if(zone==0)"—" else zone}",left+500f*scale,baseline,text)
        text.color=Color.WHITE
    }

    private fun heartRateZone(value:Int?)=when{
        value==null->0
        value<115->1
        value<135->2
        value<162->3
        value<173->4
        else->5
    }

    private fun powerZone(value:Int?)=when{
        value==null->0
        value<190->1
        value<260->2
        value<290->3
        value<330->4
        else->5
    }

    private fun drawWatermark(canvas:Canvas,scale:Float){
        val label="Велоблог от Макса"
        val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.argb(205,255,255,255)
            textSize=30f*scale
            typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
        }
        val stroke=Paint(fill).apply{
            color=Color.argb(180,0,0,0)
            style=Paint.Style.STROKE
            strokeWidth=4f*scale
        }
        val x=canvas.width-fill.measureText(label)-28f*scale
        val y=canvas.height-14f*scale
        canvas.drawText(label,x,y,stroke)
        canvas.drawText(label,x,y,fill)
    }

    private fun drawRoute(canvas:Canvas,point:TelemetryPoint,scale:Float){
        val gps=track.points.filter{it.latitude!=null&&it.longitude!=null};if(gps.size<2)return
        val minLat=gps.minOf{it.latitude!!};val maxLat=gps.maxOf{it.latitude!!};val minLon=gps.minOf{it.longitude!!};val maxLon=gps.maxOf{it.longitude!!}
        val width=330f*scale;val height=250f*scale;val margin=45f*scale
        val left=canvas.width-width-margin;val top=margin
        val bg=Paint().apply{color=Color.argb(175,12,15,18)};canvas.drawRoundRect(left,top,left+width,top+height,18f*scale,18f*scale,bg)
        fun x(lon:Double)=left+20f*scale+((lon-minLon)/(maxLon-minLon).coerceAtLeast(1e-9)*(width-40f*scale)).toFloat()
        fun y(lat:Double)=top+height-20f*scale-((lat-minLat)/(maxLat-minLat).coerceAtLeast(1e-9)*(height-40f*scale)).toFloat()
        fun path(from:Long,until:Long,color:Int,strokeWidth:Float){val p=android.graphics.Path();var begun=false;for(g in gps){if(g.timeMs in from..until){val xx=x(g.longitude!!);val yy=y(g.latitude!!);if(!begun){p.moveTo(xx,yy);begun=true}else p.lineTo(xx,yy)}};canvas.drawPath(p,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.STROKE;this.strokeWidth=strokeWidth*scale;strokeCap=Paint.Cap.ROUND})}
        path(Long.MIN_VALUE,Long.MAX_VALUE,Color.GRAY,5f)
        path(point.timeMs-45_000L,point.timeMs,Color.rgb(117,230,164),7f)
        if(point.latitude!=null&&point.longitude!=null){
            canvas.drawCircle(x(point.longitude),y(point.latitude),12f*scale,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE})
            canvas.drawCircle(x(point.longitude),y(point.latitude),8f*scale,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(255,159,67)})
        }
    }

    private fun decimal(value:Double?)=value?.let{String.format(Locale.US,"%.1f",it)}?:"—"
    private fun integer(value:Int?)=value?.toString()?:"—"
}
