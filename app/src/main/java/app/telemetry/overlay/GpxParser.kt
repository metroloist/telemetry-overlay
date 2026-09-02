package app.telemetry.overlay

import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*

object GpxParser {
    fun parse(input: InputStream): TelemetryTrack {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(input)
        val nodes = doc.getElementsByTagNameNS("*", "trkpt")
        val raw = mutableListOf<Raw>()
        for (n in 0 until nodes.length) {
            val e = nodes.item(n) as org.w3c.dom.Element
            fun text(name: String): String? {
                val list = e.getElementsByTagNameNS("*", name)
                return if (list.length > 0) list.item(0).textContent.trim() else null
            }
            val time = text("time")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: continue
            raw += Raw(time, e.getAttribute("lat").toDoubleOrNull(), e.getAttribute("lon").toDoubleOrNull(),
                text("ele")?.toDoubleOrNull(), text("hr")?.toIntOrNull(),
                text("power")?.toIntOrNull() ?: text("watts")?.toIntOrNull(), text("speed")?.toDoubleOrNull())
        }
        var distance = 0.0
        var ascent = 0.0
        var previous: Raw? = null
        val points = raw.sortedBy { it.time }.map { r ->
            previous?.let { p ->
                if (p.lat != null && p.lon != null && r.lat != null && r.lon != null) distance += haversine(p.lat,p.lon,r.lat,r.lon)
                val climb = if (p.alt != null && r.alt != null) r.alt - p.alt else 0.0
                if (climb > 0.8) ascent += climb
            }
            val dt = previous?.let { (r.time - it.time) / 1000.0 }
            val segment = previous?.let { p -> if (p.lat != null && p.lon != null && r.lat != null && r.lon != null) haversine(p.lat,p.lon,r.lat,r.lon) else null }
            val calculatedSpeed = if (segment != null && dt != null && dt > 0) segment / dt * 3.6 else null
            previous = r
            TelemetryPoint(r.time, r.speedMs?.times(3.6) ?: calculatedSpeed, distance, r.alt, ascent, r.hr, r.power, r.lat, r.lon)
        }
        return TelemetryTrack(points, "GPX")
    }

    private data class Raw(val time:Long,val lat:Double?,val lon:Double?,val alt:Double?,val hr:Int?,val power:Int?,val speedMs:Double?)
    private fun haversine(a:Double,b:Double,c:Double,d:Double):Double {
        val r=6371000.0; val p1=Math.toRadians(a); val p2=Math.toRadians(c)
        val dp=Math.toRadians(c-a); val dl=Math.toRadians(d-b)
        val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*r*asin(sqrt(h))
    }
}
