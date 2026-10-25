package com.ramz.cross

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class SpotTest {

    @Test
    fun choiceGivesTheSitesToAsk() {
        assertEquals(Source.entries.toSet(), SpotChoice(all = true).sources)
        assertEquals(setOf(Source.WIMT), SpotChoice().sources)                       // default
        assertEquals(setOf(Source.NTES), SpotChoice(ntes = true, wimt = false).sources)
        assertEquals(setOf(Source.NTES, Source.WIMT), SpotChoice(ntes = true, wimt = true).sources)
        assertEquals(setOf(Source.WIMT), SpotChoice(ntes = false, wimt = false).sources)  // never empty
    }

    @Test
    fun lineRouteFollowsTheDirectionOfTravel() {
        val toErnakulam = TrainMap.lineRoute(listOf("CGY", "KTYM", "ETM", "VARD", "PVRD", "ERS"))!!
        assertEquals(Approach.FROM_KOTTAYAM_SIDE, GateRule.approach(toErnakulam))
        val toKottayam = TrainMap.lineRoute(listOf("ERS", "TRTR", "PVRD", "VARD", "KTYM", "CGY"))!!
        assertEquals(Approach.FROM_ERNAKULAM_SIDE, GateRule.approach(toKottayam))
        assertNull(TrainMap.lineRoute(listOf("TVC", "QLN", "KTYM")))                // only one stop on this line
    }

    @Test
    fun whereIsMyTrainAloneJudgesTheGate() {
        // 66322 just left Piravam Road heading to Ernakulam: past the gate, so nothing on its side
        val client = WhereIsMyTrainClient()
        val stops = client.parse(Jsoup.parse(javaClass.classLoader!!.getResourceAsStream("wimt_66322.html"), "UTF-8", ""))
        val route = TrainMap.lineRoute(stops.map { it.code })!!
        val now = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.ENGLISH).apply { timeZone = IST }.parse("25-Oct-2026 07:21")!!.time
        val r = client.report(stops, route, "2026-10-25", now)!!
        assertEquals(Approach.FROM_KOTTAYAM_SIDE, GateRule.approach(r.routeKm))
        assertNull(GateRule.evaluate(r, now))
        // At KRPP the same train closes the gate
        assertEquals(GateLevel.CLOSED, GateRule.evaluate(r.copy(code = "KRPP", moving = true), now)?.level)
    }
}
