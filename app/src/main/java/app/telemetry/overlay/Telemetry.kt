package app.telemetry.overlay

data class TelemetryPoint(
    val timeMs: Long,
    val speedKmh: Double? = null,
    val distanceM: Double? = null,
    val altitudeM: Double? = null,
    val ascentM: Double? = null,
    val heartRate: Int? = null,
    val powerW: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class TelemetryTrack(val points: List<TelemetryPoint>, val source: String) {
    val startTimeMs: Long? get() = points.firstOrNull()?.timeMs

    fun atVideoTime(videoTimeMs: Long, offsetMs: Long): TelemetryPoint? {
        if (points.isEmpty()) return null
        val target = (startTimeMs ?: return null) + videoTimeMs - offsetMs
        if (target < points.first().timeMs || target > points.last().timeMs) return null
        val index = points.binarySearchBy(target) { it.timeMs }
        if (index >= 0) return points[index]
        val right = (-index - 1).coerceIn(0, points.lastIndex)
        val left = (right - 1).coerceAtLeast(0)
        val a = points[left]
        val b = points[right]
        if (a === b || b.timeMs == a.timeMs) return a
        val t = ((target - a.timeMs).toDouble() / (b.timeMs - a.timeMs)).coerceIn(0.0, 1.0)
        fun d(x: Double?, y: Double?) = when { x == null -> y; y == null -> x; else -> x + (y - x) * t }
        fun i(x: Int?, y: Int?) = d(x?.toDouble(), y?.toDouble())?.toInt()
        return TelemetryPoint(target, d(a.speedKmh,b.speedKmh), d(a.distanceM,b.distanceM),
            d(a.altitudeM,b.altitudeM), d(a.ascentM,b.ascentM), i(a.heartRate,b.heartRate),
            i(a.powerW,b.powerW), d(a.latitude,b.latitude), d(a.longitude,b.longitude))
    }
}

data class SyncAnchor(val videoMs:Long,val telemetryMs:Long)

fun mappedTelemetryMs(videoMs:Long,anchors:List<SyncAnchor>):Long=when{
    anchors.isEmpty()->videoMs
    anchors.size==1->videoMs+anchors[0].telemetryMs-anchors[0].videoMs
    else->{
        val a=anchors.first();val b=anchors.last()
        if(b.videoMs==a.videoMs)videoMs+a.telemetryMs-a.videoMs
        else (a.telemetryMs+(videoMs-a.videoMs).toDouble()*(b.telemetryMs-a.telemetryMs)/(b.videoMs-a.videoMs)).toLong()
    }
}
