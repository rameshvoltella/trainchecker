package com.ramz.cross

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RailYatri pages saved on 22 Oct 2026 ~16:30 (trimmed to the fields we read):
 *  • 16650 Parasuram, run of 22 Oct: "Crossed THRISSUR OLLUR HALT at 16:19", 7 km ahead, 231 min late
 *  • 12625 Kerala Exp, run of 21 Oct (start_day=1): "Crossed ASIFABAD ROAD at 16:24" on 22 Oct
 *  • 66304 ERS–QLN MEMU: RailYatri has no live data, and its timetable is wrong (QLN → CAPE)
 */
class RailYatriTest {

    private fun parse(name: String) = RailYatriClient().parse(
        Jsoup.parse(javaClass.classLoader!!.getResourceAsStream(name), "UTF-8", "")
    )

    private fun at(s: String) =
        SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.ENGLISH).apply { timeZone = IST }.parse(s)!!.time

    @Test
    fun readsCurrentStationTimeAndPosition() {
        val (report, estimate) = parse("ry_16650.html")
        report!!
        assertEquals("2026-10-22", report.startDate)
        assertEquals("OLR", report.code)
        assertEquals("THRISSUR OLLUR HALT", report.name)
        assertEquals("Crossed", report.event)
        assertTrue(report.moving)
        assertEquals(at("22-Oct-2026 16:19"), report.eventMillis)
        assertEquals(report.routeKm.getValue("OLR") + 7, report.kmNow!!, 0.01)
        assertEquals(268.1, report.routeKm.getValue("KDTY"), 0.01)

        // Scheduled at KDTY 09:45 (585 min) + 231 min late = 13:36, and it is long past it now
        estimate!!
        assertEquals(Approach.FROM_KOTTAYAM_SIDE, estimate.approach)
        assertEquals(at("22-Oct-2026 13:36"), estimate.etaMillis)
        assertTrue(estimate.passed)
    }

    @Test
    fun runThatStartedYesterdayUsesItsOwnStartDate() {
        val report = parse("ry_12625_d1.html").report!!
        assertEquals("2026-10-21", report.startDate)
        assertEquals(at("22-Oct-2026 16:24"), report.eventMillis)   // a_min 2424 = day 2, 16:24
    }

    @Test
    fun noLiveDataGivesNoReport() {
        val (report, estimate) = parse("ry_66304.html")
        assertNull(report)
        assertNull(estimate)   // its RailYatri route doesn't pass KDTY at all
    }

    @Test
    fun gateRuleWorksOnRailYatriReports() {
        val report = parse("ry_16650.html").report!!
        val now = report.eventMillis
        val k = Approach.FROM_KOTTAYAM_SIDE
        assertNull(GateRule.evaluate(report, now))                                                  // at Ollur: far away
        assertEquals(Verdict(k, GateLevel.CLOSED, false), GateRule.evaluate(report.copy(code = "KRPP"), now))
        assertEquals(Verdict(k, GateLevel.POSSIBLE, false), GateRule.evaluate(report.copy(code = "ETM"), now))
        // Crossed Kottayam a minute ago and RailYatri puts it just past Ettumanur
        val justPastEtm = report.copy(code = "KTYM", kmNow = report.routeKm.getValue("ETM") + 0.5)
        assertEquals(Verdict(k, GateLevel.POSSIBLE, true), GateRule.evaluate(justPastEtm, now + MIN))
    }
}
