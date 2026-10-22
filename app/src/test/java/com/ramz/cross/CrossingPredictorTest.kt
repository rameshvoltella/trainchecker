package com.ramz.cross

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Real NTES pages:
 *  • 16301 (comes from the Ernakulam side): runs of 21 Oct (finished) and 22 Oct (not started)
 *  • 16302 Venad (from the Kottayam side), 22 Oct, already past the gate at Tripunittura
 *  • 16650 Parasuram (from the Kottayam side), 22 Oct, "Departed from KURUPPANTARA(KRPP) at 13:52"
 *  • 66308 QLN–ERS MEMU (from the Kottayam side), 22 Oct, "Arrived at PIRAVAM ROAD(PVRD) at 13:43"
 */
class CrossingPredictorTest {

    private fun load(name: String) = RunningStatusParser.parse(
        Jsoup.parse(javaClass.classLoader!!.getResourceAsStream(name), "UTF-8", "")
    )

    private fun today(name: String) = load(name).first { it.startDate == "22-oct-2026" }

    private fun at(s: String) =
        SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).apply { timeZone = IST }.parse(s)!!.time

    private val runs16301 = load("ntes_16301.html")
    private fun run16301(date: String) = runs16301.first { it.startDate == date }

    // ── Parsing ─────────────────────────────────────────────────────────────────

    @Test
    fun parsesStopsNonStopsAndTimes() {
        assertEquals(listOf("22-oct-2026", "21-oct-2026"), runs16301.map { it.startDate })
        val rows = run16301("21-oct-2026").rows
        val pvrd = rows.first { it.code == "PVRD" }
        assertEquals(134, pvrd.km)
        assertEquals(StationTime(at("21-Oct-2026 18:16:00"), true), pvrd.departure)
        val kdty = rows.first { it.code == "KDTY" }
        assertEquals(144, kdty.km)
        assertFalse(kdty.isStop)
        assertNull(rows.first { it.code == "CYN" }.arrival)   // non-reporting halt
        assertFalse(run16301("22-oct-2026").rows.first { it.code == "KTYM" }.arrival!!.isActual)
    }

    @Test
    fun parsesCurrentPositionOnlyWhileRunning() {
        assertEquals(
            CurrentPosition("KRPP", "KURUPPANTARA", departed = true, millis = at("22-Oct-2026 13:52:00")),
            today("ntes_16650_live.html").position
        )
        assertEquals(
            CurrentPosition("PVRD", "PIRAVAM ROAD", departed = false, millis = at("22-Oct-2026 13:43:00")),
            today("ntes_66308_live.html").position
        )
        assertNull(run16301("22-oct-2026").position)   // yet to start
        assertNull(run16301("21-oct-2026").position)   // reached destination
    }

    // ── Gate rule ───────────────────────────────────────────────────────────────

    private val K = Approach.FROM_KOTTAYAM_SIDE
    private val E = Approach.FROM_ERNAKULAM_SIDE
    private val POSSIBLE = GateLevel.POSSIBLE
    private val CLOSED = GateLevel.CLOSED

    private fun verdict(run: RunInstance, now: Long = run.position?.millis ?: 0L) =
        run.liveReport(now)?.let { GateRule.evaluate(it, now) }

    private fun RunInstance.at(code: String, millis: Long = 0L, departed: Boolean = true) =
        copy(position = CurrentPosition(code, code, departed = departed, millis = millis))

    @Test
    fun trainFromKottayamAtKuruppantharaClosesTheGate() {
        val run = today("ntes_16650_live.html")
        assertEquals(K, GateRule.approach(run.routeKm()))
        assertEquals(Verdict(K, CLOSED, estimated = false), verdict(run))
    }

    @Test
    fun trainFromKottayamAlreadyPastTheGateDoesNotCloseIt() {
        // At Piravam Road heading to Ernakulam: PVRD only counts for trains coming from Ernakulam.
        val memu = today("ntes_66308_live.html")
        assertEquals(K, GateRule.approach(memu.routeKm()))
        assertNull(verdict(memu))
        assertNull(verdict(today("ntes_16302.html")))   // at Tripunittura
    }

    @Test
    fun notRunningTrainsDoNotCloseTheGate() {
        assertNull(verdict(run16301("22-oct-2026")))
        assertNull(verdict(run16301("21-oct-2026")))
    }

    @Test
    fun fromErnakulamArrivedAtPiravamIsPossibleLeavingItIsClosed() {
        val base = run16301("21-oct-2026")
        assertEquals(E, GateRule.approach(base.routeKm()))
        assertNull(verdict(base.at("TRTR")))
        assertEquals(POSSIBLE, verdict(base.at("PVRD", departed = false))?.level)
        assertEquals(CLOSED, verdict(base.at("PVRD", departed = true))?.level)
        assertEquals(CLOSED, verdict(base.at("VARD"))?.level)
        assertEquals(CLOSED, verdict(base.at("KDTY"))?.level)
        assertNull(verdict(base.at("KRPP")))   // past the gate
        assertNull(verdict(base.at("ETM")))
    }

    @Test
    fun fromKottayamEttumanurIsPossibleKuruppantharaIsClosed() {
        val base = today("ntes_16650_live.html")
        assertNull(verdict(base.at("KTYM")))
        assertNull(verdict(base.at("KFQ")))                                   // no longer counts
        assertEquals(POSSIBLE, verdict(base.at("ETM", departed = false))?.level)   // reached ETM
        assertEquals(POSSIBLE, verdict(base.at("ETM", departed = true))?.level)    // just passed ETM
        assertEquals(CLOSED, verdict(base.at("KRPP"))?.level)
        assertEquals(CLOSED, verdict(base.at("KDTY"))?.level)
        assertNull(verdict(base.at("VARD")))   // past the gate
        assertNull(verdict(base.at("PVRD")))
    }

    @Test
    fun trainThatLeftKottayamIsEstimatedAtEttumanurAsPossibleOnly() {
        // After "Departed from KTYM" the train is placed between KTYM and the next timed station
        // (ETM) by elapsed time. Reaching ETM that way is only ever a POSSIBLE.
        val base = today("ntes_16650_live.html")
        val left = base.rows.first { it.code == "KTYM" }.departure!!.millis
        val reachesEtm = base.rows.first { it.code == "ETM" }.arrival!!.millis
        val run = base.at("KTYM", millis = left)

        assertNull(verdict(run, now = left + (reachesEtm - left) * 9 / 10))       // not at ETM yet
        assertEquals(Verdict(K, POSSIBLE, estimated = true), verdict(run, now = reachesEtm))
        assertNull(verdict(run, now = left + 16 * MIN))                           // report too old to trust
        assertNull(verdict(base.at("KTYM", millis = left, departed = false), now = reachesEtm))  // standing at KTYM
    }

    // ── Rough KDTY time (only used to choose which trains to ask about) ──────────

    @Test
    fun roughTimeInterpolatesAndIsPushedBackWhenOverdue() {
        // PVRD (134 km) left 18:16, ETM (154 km) reached 18:35 → KDTY (144 km) halfway: 18:25:30
        val done = CrossingPredictor.estimate(run16301("21-oct-2026"), at("21-Oct-2026 18:20:00"))!!
        assertEquals(at("21-Oct-2026 18:25:30"), done.etaMillis)
        assertTrue(done.passed)
        // Forecast PVRD dep 17:57*, ETM arr 18:16* → 18:06:30; at 18:00 it is 3 min overdue
        assertEquals(at("22-Oct-2026 18:06:30"), CrossingPredictor.estimate(run16301("22-oct-2026"), at("22-Oct-2026 12:00:00"))!!.etaMillis)
        assertEquals(at("22-Oct-2026 18:09:30"), CrossingPredictor.estimate(run16301("22-oct-2026"), at("22-Oct-2026 18:00:00"))!!.etaMillis)
    }
}
