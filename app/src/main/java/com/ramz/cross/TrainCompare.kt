package com.ramz.cross

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** What one source says about the train: its report, or why there is none. */
data class SourceAnswer(val source: Source, val report: LiveReport?, val problem: String?)

data class Comparison(
    val trainNumber: String,
    val startDate: String?,             // yyyy-MM-dd of the run compared, if known
    val answers: List<SourceAnswer>,    // NTES, Where is my Train, RailYatri
    val furthest: Source?,              // the one the gate check would use
    val route: Map<String, Double>      // km used to compare them
)

/**
 * Asks NTES, Where is my Train and RailYatri where one train is, for comparing them side by side.
 *
 * NTES decides which run (start date) is compared: if several runs are on their way, the one
 * closest to KDTY. RailYatri is asked for that run; Where is my Train has no run date and is
 * placed on NTES's route (on RailYatri's if NTES has nothing).
 */
object TrainCompare {

    suspend fun compare(trainNumber: String, cfg: CrossingConfig = CrossingConfig()): Comparison = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        coroutineScope {
            val ntesCall = async { runCatching { NtesClient().runningStatus(trainNumber) } }
            val wimtCall = async { runCatching { WhereIsMyTrainClient().stops(trainNumber) } }

            val runs = ntesCall.await()
            val run = runs.getOrNull()?.filter { it.position != null }?.minByOrNull { r ->
                val route = r.routeKm()
                val kdty = route[cfg.stationCode] ?: return@minByOrNull Double.MAX_VALUE
                abs((route[r.position!!.code] ?: return@minByOrNull Double.MAX_VALUE) - kdty)
            }
            val ntes = run?.liveReport(now)

            val day = run?.let { startDay(it.isoStartDate(), now) } ?: 0
            val ry = runCatching { RailYatriClient().status(trainNumber, day, cfg) }
            val railYatri = ry.getOrNull()?.report

            val route = run?.routeKm() ?: railYatri?.routeKm ?: emptyMap()
            val startDate = run?.isoStartDate() ?: railYatri?.startDate
            val stops = wimtCall.await()
            val wimt = stops.getOrNull()?.let { s ->
                if (route.isEmpty() || startDate == null) null else WhereIsMyTrainClient().report(s, route, startDate, now)
            }

            val answers = listOf(
                SourceAnswer(Source.NTES, ntes, when {
                    runs.isFailure -> "Couldn't reach NTES: ${runs.exceptionOrNull()?.message}"
                    ntes == null -> "Not running now (no current position)"
                    else -> null
                }),
                SourceAnswer(Source.WIMT, wimt, when {
                    stops.isFailure -> "Couldn't reach Where is my Train: ${stops.exceptionOrNull()?.message}"
                    stops.getOrNull().isNullOrEmpty() -> "No running status on the page"
                    wimt == null && route.isEmpty() -> "Can't place it: no route from NTES or RailYatri"
                    wimt == null -> "Not running now, or its station isn't on the route"
                    else -> null
                }),
                SourceAnswer(Source.RAILYATRI, railYatri, when {
                    ry.isFailure -> "Couldn't reach RailYatri: ${ry.exceptionOrNull()?.message}"
                    railYatri == null -> "No live position for this run"
                    else -> null
                })
            )
            val reports = answers.mapNotNull { it.report }
            Comparison(trainNumber, startDate, answers, GateRule.furthestAlong(reports, route)?.source, route)
        }
    }

    private fun startDay(startDate: String, now: Long): Int {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = IST }
        return ((fmt.parse(fmt.format(now))!!.time - fmt.parse(startDate)!!.time) / (24 * 60 * MIN)).toInt().coerceAtLeast(0)
    }
}
