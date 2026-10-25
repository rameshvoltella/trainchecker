package com.ramz.cross

data class LatLng(val lat: Double, val lng: Double)

/** A nearby train placed on the map. [level] is null when it doesn't affect the gate. */
data class MapTrain(
    val train: Train,
    val report: LiveReport,
    val approach: Approach,
    val level: GateLevel?,
    val estimated: Boolean,
    val position: LatLng
)

/**
 * Where trains are drawn on the map, for the Kottayam–Ernakulam stretch around KDTY.
 *
 * Coordinates are from RailYatri's timetable data, except KDTY: the gate itself, measured on site
 * (90 m from RailYatri's point). Trains are placed between the two stations around their
 * estimated km, in a straight line — on curves the marker can sit slightly off the track.
 */
object TrainMap {

    val KDTY = LatLng(9.75668157586227, 76.49224472200433)

    val stations: Map<String, LatLng> = linkedMapOf(
        "KTYM" to LatLng(9.594487, 76.532478),
        "KFQ" to LatLng(9.617506, 76.532049),
        "ETM" to LatLng(9.676526, 76.545482),
        "KRPP" to LatLng(9.734775, 76.510849),
        "KDTY" to KDTY,
        "VARD" to LatLng(9.777408, 76.474371),
        "PVRD" to LatLng(9.829169, 76.455231),
        "KPTM" to LatLng(9.861981, 76.410599),
        "MNTT" to LatLng(9.901216, 76.389313),
        "KFE" to LatLng(9.923537, 76.37661),
        "TRTR" to LatLng(9.94983, 76.352491),
        "ERN" to LatLng(9.992012, 76.28653)
    )

    val stationNames = mapOf(
        "KTYM" to "Kottayam", "KFQ" to "Kumaranallur", "ETM" to "Ettumanur", "KRPP" to "Kuruppanthara",
        "KDTY" to "Kaduthuruthy", "VARD" to "Vaikom Road", "PVRD" to "Piravam Road", "KPTM" to "Kanjiramittam",
        "MNTT" to "Mulanthuruthy", "KFE" to "Chottanikkara Road", "TRTR" to "Tripunithura", "ERN" to "Ernakulam Town"
    )

    /**
     * km along the line from Kottayam towards Ernakulam (from RailYatri's timetables). ERS is
     * approximate; it is only used to tell the direction and is beyond the gate's stretch.
     */
    private val lineKm = mapOf(
        "KTYM" to 0.0, "KFQ" to 3.2, "ETM" to 10.0, "KRPP" to 18.0, "KDTY" to 21.1, "VARD" to 25.0,
        "PVRD" to 31.0, "KPTM" to 37.4, "MNTT" to 42.7, "KFE" to 46.1, "TRTR" to 50.0, "ERS" to 57.0, "ERN" to 62.0
    )

    /**
     * A route (km growing in the direction of travel) for a train whose stops are listed in
     * travel order, built from [lineKm] — so a source without distances can be judged on its own.
     * Null if fewer than two of its stops are on this line.
     */
    fun lineRoute(stopCodes: List<String>): Map<String, Double>? {
        val onLine = stopCodes.filter { it in lineKm }
        if (onLine.size < 2) return null
        val towardsErnakulam = lineKm.getValue(onLine.last()) > lineKm.getValue(onLine.first())
        val end = lineKm.values.max()
        return lineKm.mapValues { (_, km) -> if (towardsErnakulam) km else end - km }
    }

    /**
     * Where the train is on the map: between the two mapped stations around its km now (or at the
     * station it was last reported at). Null if it's outside the mapped stretch.
     */
    fun positionOf(r: LiveReport): LatLng? {
        val mapped = r.routeKm.filterKeys { it in stations }.entries.sortedBy { it.value }
        if (mapped.size < 2) return stations[r.code]
        val km = r.kmNow ?: r.routeKm[r.code] ?: return stations[r.code]
        if (km < mapped.first().value || km > mapped.last().value) return null
        val after = mapped.indexOfFirst { it.value >= km }.coerceAtLeast(1)
        val (codeA, kmA) = mapped[after - 1]
        val (codeB, kmB) = mapped[after]
        val f = if (kmB > kmA) ((km - kmA) / (kmB - kmA)).coerceIn(0.0, 1.0) else 0.0
        val a = stations.getValue(codeA)
        val b = stations.getValue(codeB)
        return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
    }
}
