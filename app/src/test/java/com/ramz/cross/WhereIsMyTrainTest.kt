package com.ramz.cross

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** whereismytrain.org.in for 66322 (Kollam → Ernakulam MEMU) at 07:22 on 25 Oct 2026: just left Piravam Road. */
class WhereIsMyTrainTest {

    private val client = WhereIsMyTrainClient()
    private val stops = client.parse(Jsoup.parse(javaClass.classLoader!!.getResourceAsStream("wimt_66322.html"), "UTF-8", ""))

    private fun at(s: String) =
        SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.ENGLISH).apply { timeZone = IST }.parse(s)!!.time

    // km on the run's route, as NTES / RailYatri would give it (only the order and spacing matter)
    private val route = mapOf("KTYM" to 0.0, "ETM" to 11.0, "KRPP" to 18.0, "KDTY" to 21.0, "VARD" to 24.0,
        "PVRD" to 30.0, "KPTM" to 37.0, "MNTT" to 42.0)

    @Test
    fun readsReachedAndUpcomingStations() {
        val pvrd = stops.first { it.code == "PVRD" }
        assertTrue(pvrd.reached)
        assertEquals("07:16 AM", pvrd.arrival)
        assertEquals("Piravom Road", pvrd.name)
        val kptm = stops.first { it.code == "KPTM" }
        assertEquals(false, kptm.reached)
        assertEquals("07:22 AM", kptm.arrival)          // expected
        assertEquals("PVRD", stops.last { it.reached }.code)
    }

    @Test
    fun placesTheTrainAfterTheLastStationItLeft() {
        val r = client.report(stops, route, "2026-10-25", at("25-Oct-2026 07:21"))!!
        assertEquals("PVRD", r.code)
        assertEquals("Departed from", r.event)
        assertTrue(r.moving)
        assertEquals(at("25-Oct-2026 07:20"), r.eventMillis)
        // left PVRD 07:20, due at KPTM 07:22 → halfway at 07:21
        assertEquals(33.5, r.kmNow!!, 0.01)
    }

    @Test
    fun furthestAlongWins() {
        val wimt = client.report(stops, route, "2026-10-25", at("25-Oct-2026 07:22"))!!
        val railYatri = wimt.copy(source = Source.RAILYATRI, code = "KRPP", eventMillis = at("25-Oct-2026 06:59"))
        val ntesAtEtm = wimt.copy(source = Source.NTES, code = "ETM", eventMillis = at("25-Oct-2026 07:30"))
        assertEquals(Source.WIMT, GateRule.furthestAlong(listOf(ntesAtEtm, wimt, railYatri), route)!!.source)

        // Same station: "departed" is further than "arrived"
        val arrived = wimt.copy(source = Source.NTES, moving = false, event = "Arrived at")
        assertEquals(Source.WIMT, GateRule.furthestAlong(listOf(arrived, wimt), route)!!.source)
        // Exactly level: NTES wins
        assertEquals(Source.NTES, GateRule.furthestAlong(listOf(wimt, wimt.copy(source = Source.NTES)), route)!!.source)
    }
}
