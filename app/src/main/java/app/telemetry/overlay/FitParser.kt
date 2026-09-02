package app.telemetry.overlay

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal offline FIT decoder for Garmin record messages (global message 20). */
object FitParser {
    private const val FIT_EPOCH_MS = 631065600000L
    private data class Field(val num:Int,val size:Int,val type:Int)
    private data class Definition(val global:Int,val endian:ByteOrder,val fields:List<Field>,val developerBytes:Int)

    fun parse(input: InputStream): TelemetryTrack {
        val bytes=input.readBytes(); require(bytes.size >= 14) { "FIT file is too short" }
        val headerSize=bytes[0].toInt() and 0xff
        require(String(bytes,8,4,Charsets.US_ASCII)==".FIT") { "Not a FIT file" }
        val dataSize=u32(bytes,4,ByteOrder.LITTLE_ENDIAN).toInt()
        val end=(headerSize+dataSize).coerceAtMost(bytes.size)
        val defs=mutableMapOf<Int,Definition>(); val result=mutableListOf<TelemetryPoint>(); var pos=headerSize
        var distance=0.0; var ascent=0.0; var lastAlt:Double?=null; var lastTimestamp:Long?=null
        while(pos<end){
            val header=bytes[pos++].toInt() and 0xff
            if(header and 0x80 != 0) { // compressed timestamp data header
                val local=(header shr 5) and 0x03; val def=defs[local] ?: break
                val values=readMessage(bytes,pos,def); pos=values.second
                if(def.global==20){
                    val offset=header and 0x1f; val prev=lastTimestamp ?: 0L
                    var seconds=(prev-FIT_EPOCH_MS)/1000; seconds=(seconds and 0xffffffe0L)+offset
                    if(seconds <= (prev-FIT_EPOCH_MS)/1000) seconds+=32
                    val point=record(values.first, FIT_EPOCH_MS+seconds*1000, distance, ascent, lastAlt)
                    distance=point.distanceM?:distance; ascent=point.ascentM?:ascent; lastAlt=point.altitudeM; lastTimestamp=point.timeMs; result+=point
                }
                continue
            }
            val local=header and 0x0f
            if(header and 0x40 != 0){
                pos++ // reserved
                val endian=if(bytes[pos++].toInt()==0) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val global=u16(bytes,pos,endian); pos+=2; val count=bytes[pos++].toInt() and 0xff
                val fields=ArrayList<Field>(count)
                repeat(count){ fields+=Field(bytes[pos++].toInt()and 0xff,bytes[pos++].toInt()and 0xff,bytes[pos++].toInt()and 0xff) }
                var devBytes=0
                if(header and 0x20 != 0){ val dc=bytes[pos++].toInt()and 0xff; repeat(dc){ pos++; devBytes+=bytes[pos++].toInt()and 0xff; pos++ } }
                defs[local]=Definition(global,endian,fields,devBytes)
            } else {
                val def=defs[local] ?: break; val values=readMessage(bytes,pos,def); pos=values.second
                if(def.global==20){
                    val ts=(values.first[253] as? Number)?.toLong()?.let { FIT_EPOCH_MS+it*1000 } ?: lastTimestamp ?: continue
                    val point=record(values.first,ts,distance,ascent,lastAlt)
                    distance=point.distanceM?:distance; ascent=point.ascentM?:ascent; lastAlt=point.altitudeM; lastTimestamp=ts; result+=point
                }
            }
        }
        require(result.isNotEmpty()) { "No record messages found in FIT" }
        return TelemetryTrack(result.sortedBy{it.timeMs},"FIT")
    }

    private fun readMessage(b:ByteArray,start:Int,d:Definition):Pair<Map<Int,Number>,Int>{
        var p=start; val out=mutableMapOf<Int,Number>()
        for(f in d.fields){ if(p+f.size>b.size) break; scalar(b,p,f.size,f.type,d.endian)?.let{out[f.num]=it}; p+=f.size }
        return out to (p+d.developerBytes)
    }
    private fun scalar(b:ByteArray,p:Int,size:Int,type:Int,o:ByteOrder):Number? {
        val base=type and 0x1f
        return when(base){
            0,2,10,13 -> if(size==1)(b[p].toInt()and 0xff).takeUnless{it==0xff} else null
            1 -> b[p].toInt().takeUnless{it==0x7f}
            3 -> i16(b,p,o).takeUnless{it==0x7fff}
            4,11 -> u16(b,p,o).takeUnless{it==0xffff}
            5 -> i32(b,p,o).takeUnless{it==0x7fffffff}
            6,12 -> u32(b,p,o).takeUnless{it==0xffffffffL}
            else -> null
        }
    }
    private fun record(v:Map<Int,Number>,time:Long,previousDistance:Double,previousAscent:Double,previousAlt:Double?):TelemetryPoint{
        val alt=((v[78]?.toDouble() ?: v[2]?.toDouble())?.div(5.0))?.minus(500.0)
        val speed=(v[73]?.toDouble() ?: v[6]?.toDouble())?.div(1000.0)?.times(3.6)
        val distance=v[5]?.toDouble()?.div(100.0) ?: previousDistance
        val ascent=previousAscent + if(alt!=null&&previousAlt!=null&&(alt-previousAlt)>0.8) alt-previousAlt else 0.0
        return TelemetryPoint(time,speed,distance,alt,ascent,v[3]?.toInt(),v[7]?.toInt(),
            v[0]?.toLong()?.let{it*180.0/2147483648.0},v[1]?.toLong()?.let{it*180.0/2147483648.0})
    }
    private fun u16(b:ByteArray,p:Int,o:ByteOrder)=ByteBuffer.wrap(b,p,2).order(o).short.toInt() and 0xffff
    private fun i16(b:ByteArray,p:Int,o:ByteOrder)=ByteBuffer.wrap(b,p,2).order(o).short.toInt()
    private fun i32(b:ByteArray,p:Int,o:ByteOrder)=ByteBuffer.wrap(b,p,4).order(o).int
    private fun u32(b:ByteArray,p:Int,o:ByteOrder)=i32(b,p,o).toLong() and 0xffffffffL
}
