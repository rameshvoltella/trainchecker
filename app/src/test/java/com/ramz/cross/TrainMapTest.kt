package com.ramz.cross

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainMapTest {

    private val report = RailYatriClient().parse(
        Jsoup.parse(javaClass.classLoader!!.getResourceAsStream("ry_16650.html"), "UTF-8", "")
    ).report!!

    @Test
    fun trainHalfwayBetweenStationsIsDrawnHalfwayBetweenThem() {
        // 16650's route: KRPP at 265 km, KDTY at 268.1 km
        val kmHalfway = (report.routeKm.getValue("KRPP") + report.routeKm.getValue("KDTY")) / 2
        val p = TrainMap.positionOf(report.copy(code = "KRPP", kmNow = kmHalfway))!!
        val krpp = TrainMap.stations.getValue("KRPP")
        assertEquals((krpp.lat + TrainMap.KDTY.lat) / 2, p.lat, 1e-6)
        assertEquals((krpp.lng + TrainMap.KDTY.lng) / 2, p.lng, 1e-6)
    }

    @Test
    fun trainAtAStationIsDrawnOnIt() {
        val p = TrainMap.positionOf(report.copy(code = "ETM", kmNow = report.routeKm.getValue("ETM")))
        assertEquals(TrainMap.stations.getValue("ETM"), p)
    }

    @Test
    fun trainOutsideTheStretchIsNotDrawn() {
        assertNull(TrainMap.positionOf(report))   // near Thrissur, beyond Ernakulam
    }
}
