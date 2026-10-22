package com.ramz.cross

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * When the gate at Kaduthuruthy (KDTY) goes down, for one direction of travel:
 *  • [possibleAt]: the train is here → POSSIBLE (gate may close soon). If [closedWhenLeaving],
 *    once it has left this station it is CLOSED instead.
 *  • [closedAt]: the train is here → CLOSED, until it is reported past KDTY.
 */
data class SideRule(val possibleAt: String, val closedWhenLeaving: Boolean, val closedAt: Set<String>)

data class CrossingConfig(
    val stationCode: String = "KDTY",
    val refStationCode: String = "KTYM",
    // Heading to Ernakulam: at or just past Ettumanur = possible; from Kuruppanthara = closed.
    val fromKottayam: SideRule = SideRule("ETM", closedWhenLeaving = false, closedAt = setOf("KRPP", "KDTY")),
    // Heading to Kottayam: arrived at Piravam Road = possible; once it leaves PVRD = closed.
    val fromErnakulam: SideRule = SideRule("PVRD", closedWhenLeaving = true, closedAt = setOf("VARD", "KDTY")),
    // Trust a position worked out from time and distance only this long after the last report.
    val estimateMaxAgeMin: Int = 15
) {
    fun rule(side: Approach) = if (side == Approach.FROM_KOTTAYAM_SIDE) fromKottayam else fromErnakulam
}

enum class Approach { FROM_KOTTAYAM_SIDE, FROM_ERNAKULAM_SIDE }

/** Red = the gate is definitely down; yellow = a train is close, it may go down soon. */
enum class GateLevel { POSSIBLE, CLOSED }

const val MIN = 60_000L

enum class Source(val label: String) { NTES("NTES"), RAILYATRI("RailYatri") }

/**
 * The latest known location of one run of a train, from either source.
 * Distances are on that source's own route (km from the train's origin, growing along the route).
 */
data class LiveReport(
    val source: Source,
    val startDate: String,             // yyyy-MM-dd the run left its origin
    val code: String,                  // last station reported, including ones it runs through
    val name: String,
    val event: String,                 // "Arrived at", "Departed from", "Crossed"
    val eventMillis: Long,
    val moving: Boolean,               // false while standing at [code]
    val routeKm: Map<String, Double>,
    val kmNow: Double?                 // where the train should be now
)

/** [estimated] = worked out from time and distance, not reported (never more than POSSIBLE). */
data class Verdict(val side: Approach, val level: GateLevel, val estimated: Boolean)

object GateRule {

    /** Which side of KDTY a train comes from, or null if its route doesn't pass KDTY. */
    fun approach(routeKm: Map<String, Double>, cfg: CrossingConfig = CrossingConfig()): Approach? {
        val kdty = routeKm[cfg.stationCode] ?: return null
        val ktym = routeKm[cfg.refStationCode] ?: return null
        return if (ktym < kdty) Approach.FROM_KOTTAYAM_SIDE else Approach.FROM_ERNAKULAM_SIDE
    }

    /**
     * Judges where the train was last reported against its side's [SideRule]. If it was last
     * reported before [SideRule.possibleAt] a few minutes ago but by time and distance should be
     * there now, that is a POSSIBLE (estimated).
     */
    fun evaluate(r: LiveReport, now: Long, cfg: CrossingConfig = CrossingConfig()): Verdict? {
        val side = approach(r.routeKm, cfg) ?: return null
        val rule = cfg.rule(side)
        when (r.code) {
            in rule.closedAt -> return Verdict(side, GateLevel.CLOSED, estimated = false)
            rule.possibleAt -> return Verdict(
                side, if (rule.closedWhenLeaving && r.moving) GateLevel.CLOSED else GateLevel.POSSIBLE, estimated = false
            )
        }

        val kdty = r.routeKm.getValue(cfg.stationCode)
        val possibleKm = r.routeKm[rule.possibleAt] ?: return null
        val reportedKm = r.routeKm[r.code] ?: return null
        val km = r.kmNow ?: return null
        val fresh = now - r.eventMillis <= cfg.estimateMaxAgeMin * MIN
        return if (r.moving && fresh && reportedKm < possibleKm && km >= possibleKm && km <= kdty) {
            Verdict(side, GateLevel.POSSIBLE, estimated = true)
        } else null
    }
}

data class CrossingEstimate(
    val etaMillis: Long,      // roughly when the train is at KDTY
    val passed: Boolean,      // already past KDTY
    val approach: Approach,
    val startDate: String     // yyyy-MM-dd of the run this is for
)

/**
 * Rough time each NTES run is at KDTY. Not used to decide whether the gate is closed — only to
 * pick which trains are worth asking about now, and to show what is due next.
 */
object CrossingPredictor {

    fun estimate(run: RunInstance, now: Long, cfg: CrossingConfig = CrossingConfig()): CrossingEstimate? {
        val rows = run.rows
        val crossKm = rows.firstOrNull { it.code == cfg.stationCode }?.km?.toDouble() ?: return null
        val approach = GateRule.approach(run.routeKm(), cfg) ?: return null

        val before = rows.lastOrNull { it.km < crossKm && (it.departure ?: it.arrival) != null } ?: return null
        val after = rows.firstOrNull { it.km > crossKm && (it.arrival ?: it.departure) != null } ?: return null
        val tA = before.departure ?: before.arrival!!
        val tB = after.arrival ?: after.departure!!

        val fraction = (crossKm - before.km) / (after.km - before.km)
        var eta = tA.millis + ((tB.millis - tA.millis) * fraction).toLong()
        // NTES expected it to have left the earlier station by now, but it hasn't reported that.
        if (!tA.isActual && now > tA.millis) eta += now - tA.millis
        return CrossingEstimate(eta, passed = tB.isActual, approach = approach, startDate = run.isoStartDate())
    }
}

// ── NTES run → shared types ─────────────────────────────────────────────────────

fun RunInstance.routeKm(): Map<String, Double> =
    LinkedHashMap<String, Double>().also { m -> rows.forEach { m.putIfAbsent(it.code, it.km.toDouble()) } }

fun RunInstance.isoStartDate(): String {
    val d = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).apply { timeZone = IST }.parse(startDate)!!
    return SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = IST }.format(d)
}

/**
 * NTES report for a running train. After "Departed from X" the train is placed between X and the
 * next station with a time, in proportion to the time elapsed (never beyond that station).
 */
fun RunInstance.liveReport(now: Long): LiveReport? {
    val p = position ?: return null
    val routeKm = routeKm()
    val idx = rows.indexOfFirst { it.code == p.code }
    val kmNow = if (idx < 0) null else {
        val here = rows[idx].km.toDouble()
        val next = rows.drop(idx + 1).firstOrNull { (it.arrival ?: it.departure) != null }
        val tNext = next?.let { it.arrival ?: it.departure!! }
        when {
            !p.departed || next == null -> here
            tNext!!.millis <= p.millis -> next.km.toDouble()
            else -> here + (next.km - here) *
                ((now - p.millis).toDouble() / (tNext.millis - p.millis)).coerceIn(0.0, 1.0)
        }
    }
    return LiveReport(
        source = Source.NTES, startDate = isoStartDate(), code = p.code, name = p.name,
        event = if (p.departed) "Departed from" else "Arrived at", eventMillis = p.millis,
        moving = p.departed, routeKm = routeKm, kmNow = kmNow
    )
}
