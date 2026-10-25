package com.ramz.cross

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

data class TrainPass(val train: Train, val estimate: CrossingEstimate)

/** A train near the gate on its side: [level] POSSIBLE (yellow) or CLOSED (red). */
data class ZoneHit(val train: Train, val report: LiveReport, val level: GateLevel, val estimated: Boolean)

enum class SideStatus { NOT_CHECKED, CHECKING, CLEAR, POSSIBLE, CLOSED }

data class SideState(val status: SideStatus = SideStatus.NOT_CHECKED, val hit: ZoneHit? = null)

data class GateUiState(
    val running: Boolean = false,
    val monitoring: Boolean = false,              // running repeatedly, not a one-off quick check
    val fromKottayam: SideState = SideState(),
    val fromErnakulam: SideState = SideState(),
    val upcoming: List<TrainPass> = emptyList(),  // rough KDTY times, sorted
    val mapTrains: List<MapTrain> = emptyList(),   // nearby trains seen in the last check
    val lastChecked: Long? = null,
    val checking: Boolean = false,
    val phase: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val failedTrains: List<String> = emptyList(),
    val error: String? = null
) {
    private val sides get() = listOf(fromKottayam.status, fromErnakulam.status)
    val closed get() = SideStatus.CLOSED in sides
    val possible get() = !closed && SideStatus.POSSIBLE in sides
    val open get() = sides.all { it == SideStatus.CLEAR }
}

/**
 * Watches the KDTY gate while [run] is active (driven by [GateMonitorService]).
 *
 * Positions come from RailYatri, NTES and Where is my Train; whichever puts a train furthest
 * along wins (each lags behind the others at times).
 *
 * Every [CYCLE]:
 *  • Trains due at KDTY between [CANDIDATE_BEFORE] ago and [CANDIDATE_AHEAD] ahead are checked,
 *    nearest first, on RailYatri and Where is my Train. NTES (a ~1.2 MB page) is asked every
 *    check for trains within [NEAR_KDTY] of KDTY, otherwise at most every [NTES_REFRESH] unless
 *    RailYatri has nothing fresh. See [checkTrain].
 *    Once one side has a train in its stretch, that side's remaining trains are skipped.
 *  • Every [ETA_REFRESH], RailYatri refreshes the rough KDTY times of trains due in the next 3 h.
 *  • Every [FULL_SCAN_EVERY], every Kottayam–Ernakulam train is looked up on RailYatri (runs that
 *    started today, yesterday or the day before) to find which are coming at all; NTES is asked
 *    only about trains RailYatri can't place (e.g. some MEMUs have a wrong RailYatri timetable).
 */
class GateMonitor private constructor(context: Context) {

    companion object {
        private const val TAG = "GateCheck"
        const val CYCLE = 60_000L
        private const val WORKERS = 4
        private const val CANDIDATE_BEFORE = 30 * MIN
        private const val CANDIDATE_AHEAD = 45 * MIN
        private const val NTES_REFRESH = 3 * MIN
        private const val NTES_REFRESH_CLOSE = 1 * MIN      // trains within NEAR_KDTY: every check
        private const val NEAR_KDTY = 25 * MIN
        private const val WIMT_MAX_KM_FROM_KDTY = 100.0
        private const val RAILYATRI_STALE = 5 * MIN
        private val START_DAYS = 0..2   // runs that started today, yesterday, the day before
        private const val ETA_REFRESH = 10 * MIN
        private const val ETA_AHEAD = 3 * 60 * MIN
        private const val FULL_SCAN_EVERY = 2 * 60 * MIN
        private const val TRAIN_LIST_TTL = 7 * 24 * 60 * MIN

        @Volatile private var instance: GateMonitor? = null

        fun get(context: Context): GateMonitor =
            instance ?: synchronized(this) { instance ?: GateMonitor(context.applicationContext).also { instance = it } }
    }

    val config = CrossingConfig()

    private val trainRepo = TrainRepository()
    private val railYatri = RailYatriClient()
    private val whereIsMyTrain = WhereIsMyTrainClient()
    private val ntes = NtesClient()
    private val cacheFile = File(context.filesDir, "gate_cache.json")

    private var cacheLoaded = false
    private var activeRun: Job? = null
    private var trains: List<Train> = emptyList()
    private var trainsFetchedAt = 0L
    private var lastFullScan = 0L
    private var lastEtaRefresh = 0L
    private val estimates = mutableMapOf<String, List<CrossingEstimate>>()
    private val ntesRuns = mutableMapOf<String, Pair<Long, List<RunInstance>>>()  // fetched at, runs
    private val failed = mutableSetOf<String>()
    private val seenThisCycle = mutableMapOf<String, MapTrain>()

    private val _state = MutableStateFlow(GateUiState())
    val state: StateFlow<GateUiState> = _state

    /**
     * Checks every [CYCLE] until cancelled, or just once if not [continuous] (quick check —
     * its result stays on screen afterwards). [checkAll] makes the first check look up every
     * train again instead of only the nearby ones.
     */
    suspend fun run(continuous: Boolean, checkAll: Boolean = false) {
        // Only one run at a time, however it was started.
        val me = currentCoroutineContext()[Job]
        synchronized(this) {
            activeRun?.takeIf { it != me }?.cancel()
            activeRun = me
        }
        _state.update {
            it.copy(running = true, monitoring = continuous, error = null,
                fromKottayam = SideState(SideStatus.CHECKING), fromErnakulam = SideState(SideStatus.CHECKING))
        }
        try {
            withContext(Dispatchers.IO) { if (!cacheLoaded) { loadCache(); cacheLoaded = true } }
            publishUpcoming()
            var lookUpAll = checkAll
            do {
                cycle(lookUpAll)
                lookUpAll = false
                if (continuous) delay(CYCLE)
            } while (continuous)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { saveCache() }
            synchronized(this) {
                // A newer run may already have taken over; then the state is its business.
                if (activeRun == me) {
                    activeRun = null
                    showStopped()
                }
            }
        }
    }

    /**
     * Stops whatever run is active. The screen shows "stopped" straight away; a network request
     * already under way finishes in the background and its result is dropped.
     */
    fun stop() = synchronized(this) {
        activeRun?.cancel()
        activeRun = null
        showStopped()
    }

    private fun showStopped() = _state.update {
        it.copy(running = false, monitoring = false, checking = false, phase = null,
            fromKottayam = it.fromKottayam.unlessUnfinished(), fromErnakulam = it.fromErnakulam.unlessUnfinished())
    }

    // A side still being checked when we stop has no answer.
    private fun SideState.unlessUnfinished() = if (status == SideStatus.CHECKING) SideState() else this

    private suspend fun cycle(lookUpAll: Boolean) {
        _state.update { it.copy(checking = true, error = null) }
        try {
            val now = System.currentTimeMillis()
            if (trains.isEmpty() || now - trainsFetchedAt > TRAIN_LIST_TTL) {
                _state.update { it.copy(phase = "Loading train list…", done = 0, total = 0) }
                trains = withContext(Dispatchers.IO) { trainRepo.fetchTrains() }
                trainsFetchedAt = now
            }
            val found = mutableMapOf<Approach, GateLevel>()
            synchronized(seenThisCycle) { seenThisCycle.clear() }
            if (lookUpAll || now - lastFullScan > FULL_SCAN_EVERY) {
                if (fullScan(now, found)) lastFullScan = now
            } else if (now - lastEtaRefresh > ETA_REFRESH) {
                refreshEtas(now)
                lastEtaRefresh = now
            }
            checkNearby(System.currentTimeMillis(), found)
            _state.update { it.copy(mapTrains = synchronized(seenThisCycle) { seenThisCycle.values.toList() }) }
            _state.update { it.copy(lastChecked = System.currentTimeMillis()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "cycle failed", e)
            _state.update { it.copy(error = e.message ?: "Check failed") }
        } finally {
            publishUpcoming()
            _state.update { it.copy(checking = false, phase = null) }
            withContext(NonCancellable + Dispatchers.IO) { saveCache() }
        }
    }

    // ── The gate check ──────────────────────────────────────────────────────────

    /**
     * [found]: sides that already have a train (from a full lookup in this same check).
     * A side is no longer checked once it is CLOSED; while only POSSIBLE, its other trains are
     * still checked in case one of them is already closer.
     */
    private suspend fun checkNearby(now: Long, found: MutableMap<Approach, GateLevel>) {
        val candidates = trains.mapNotNull { train ->
            estimates[train.number].orEmpty()
                .filter { !it.passed && it.etaMillis in (now - CANDIDATE_BEFORE)..(now + CANDIDATE_AHEAD) }
                .minByOrNull { abs(it.etaMillis - now) }
                ?.let { train to it }
        }.sortedBy { abs(it.second.etaMillis - now) }

        _state.update { it.copy(phase = "Checking ${candidates.size} nearby trains", done = 0, total = candidates.size) }
        withContext(Dispatchers.IO) {
            for ((train, est) in candidates) {
                ensureActive()   // stop between trains when stopped
                if (found[est.approach] != GateLevel.CLOSED) {
                    checkTrain(train, est, now)?.let { hit -> record(found, hit) }
                }
                _state.update { it.copy(done = it.done + 1) }
            }
        }
        _state.update {
            it.copy(
                fromKottayam = if (Approach.FROM_KOTTAYAM_SIDE in found) it.fromKottayam else SideState(SideStatus.CLEAR),
                fromErnakulam = if (Approach.FROM_ERNAKULAM_SIDE in found) it.fromErnakulam else SideState(SideStatus.CLEAR)
            )
        }
    }

    /**
     * Where this run is, by RailYatri, NTES and Where is my Train — whichever puts it furthest
     * along — judged by the gate rule. NTES (a large page) is asked every check for trains close
     * to KDTY, otherwise at most every [NTES_REFRESH] or when RailYatri has nothing fresh.
     */
    private fun checkTrain(train: Train, est: CrossingEstimate, now: Long): ZoneHit? {
        // Reports whose route doesn't pass KDTY from this side are ignored
        // (RailYatri has wrong timetables for some MEMUs).
        fun usable(r: LiveReport) = r.startDate == est.startDate && GateRule.approach(r.routeKm, config) == est.approach

        var railYatri: LiveReport? = null
        try {
            val ry = this.railYatri.status(train.number, startDay(est.startDate, now), config)
            railYatri = ry.report?.takeIf(::usable)
            ry.estimate?.let { updateEstimate(train.number, it) }
        } catch (e: Exception) {
            Log.w(TAG, "[${train.number}] RailYatri: ${e.javaClass.simpleName}: ${e.message}")
        }

        val close = abs(est.etaMillis - now) <= NEAR_KDTY
        val railYatriFresh = railYatri != null && now - railYatri.eventMillis <= RAILYATRI_STALE
        val cached = synchronized(estimates) { ntesRuns[train.number] }
        val ntesAge = cached?.let { now - it.first } ?: Long.MAX_VALUE
        if (ntesAge > (if (close || !railYatriFresh) NTES_REFRESH_CLOSE else NTES_REFRESH)) fetchNtes(train, now)
        val ntesRun = synchronized(estimates) { ntesRuns[train.number] }?.second
            ?.firstOrNull { it.isoStartDate() == est.startDate }
        val ntes = ntesRun?.liveReport(now)?.takeIf(::usable)

        // Where is my Train has no km or run date: place it on NTES's (or RailYatri's) route, and
        // only trust it near KDTY, so a different run of the same train can't be mistaken for this one.
        val route = ntesRun?.routeKm() ?: railYatri?.routeKm
        val wimt = route?.let { r ->
            try {
                whereIsMyTrain.report(whereIsMyTrain.stops(train.number), r, est.startDate, now)
                    ?.takeIf { abs(r.getValue(it.code) - (r[config.stationCode] ?: Double.MAX_VALUE)) <= WIMT_MAX_KM_FROM_KDTY }
                    ?.takeIf(::usable)
            } catch (e: Exception) {
                Log.w(TAG, "[${train.number}] Where is my Train: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        val reports = listOfNotNull(ntes, wimt, railYatri)
        val best = GateRule.furthestAlong(reports, route ?: return null) ?: return null
        Log.d(TAG, "[${train.number}] " + reports.joinToString { "${it.source.label}: ${it.event} ${it.code} ${hhmm(it.eventMillis)}" } +
            " → using ${best.source.label}")
        val verdict = GateRule.evaluate(best, now, config)
        seen(train, best, verdict)
        if (verdict == null) return null
        return ZoneHit(train, best, verdict.level, verdict.estimated)
    }

    private fun hhmm(millis: Long) = SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = IST }.format(millis)

    /** Remembers where [train] is, for the map, if it's on the mapped stretch. */
    private fun seen(train: Train, r: LiveReport, verdict: Verdict?) {
        val approach = GateRule.approach(r.routeKm, config) ?: return
        val position = TrainMap.positionOf(r) ?: return
        val entry = MapTrain(train, r, approach, verdict?.level, verdict?.estimated == true, position)
        synchronized(seenThisCycle) {
            val old = seenThisCycle[train.number]
            if (old == null || r.eventMillis >= old.report.eventMillis) seenThisCycle[train.number] = entry
        }
    }

    /** Shows [hit] if its side has nothing yet, or only a POSSIBLE and this one is CLOSED. */
    private fun record(found: MutableMap<Approach, GateLevel>, hit: ZoneHit) {
        val side = GateRule.approach(hit.report.routeKm, config) ?: return
        val stronger = synchronized(found) {
            val current = found[side]
            (current == null || (current == GateLevel.POSSIBLE && hit.level == GateLevel.CLOSED))
                .also { if (it) found[side] = hit.level }
        }
        if (stronger) showHit(side, hit)
    }

    private fun showHit(side: Approach, hit: ZoneHit) {
        val s = SideState(if (hit.level == GateLevel.CLOSED) SideStatus.CLOSED else SideStatus.POSSIBLE, hit)
        _state.update { if (side == Approach.FROM_KOTTAYAM_SIDE) it.copy(fromKottayam = s) else it.copy(fromErnakulam = s) }
    }

    // ── Keeping the rough KDTY times current ────────────────────────────────────

    /**
     * Looks up every train (RailYatri, NTES for the ones it can't answer). Any train whose fresh
     * report already puts it in a closing stretch is shown straight away and added to [found].
     */
    private suspend fun fullScan(now: Long, found: MutableMap<Approach, GateLevel>): Boolean {
        _state.update { it.copy(phase = "Looking up all ${trains.size} trains", done = 0, total = trains.size) }
        val queue = Channel<Train>(Channel.UNLIMITED).apply { trains.forEach { trySend(it) }; close() }
        withContext(Dispatchers.IO) {
            coroutineScope {
                repeat(WORKERS) {
                    launch {
                        val client = NtesClient()
                        for (train in queue) {
                            val reports = lookUpOnRailYatri(train, now)
                                ?: fetchNtes(train, now, client)?.mapNotNull { it.liveReport(now) }.orEmpty()
                            for (r in reports.filter { now - it.eventMillis <= RAILYATRI_STALE }) {
                                val verdict = GateRule.evaluate(r, now, config)
                                seen(train, r, verdict)
                                if (verdict == null) continue
                                record(found, ZoneHit(train, r, verdict.level, verdict.estimated))
                            }
                            _state.update { it.copy(done = it.done + 1) }
                        }
                    }
                }
            }
        }
        publishUpcoming()
        return synchronized(estimates) { failed.size } < trains.size / 2
    }

    /**
     * Rough KDTY times of this train's recent runs from RailYatri, returning their live reports.
     * Null if RailYatri can't answer for it (request failed, or its timetable doesn't pass KDTY) —
     * then NTES has to be asked. A train RailYatri knows but that has no run around now (weekly
     * specials) needs nothing more.
     */
    private fun lookUpOnRailYatri(train: Train, now: Long): List<LiveReport>? {
        val answers = START_DAYS.map { day ->
            try {
                railYatri.status(train.number, day, config)
            } catch (e: Exception) {
                Log.w(TAG, "[${train.number}] RailYatri day $day: ${e.message}")
                return null
            }
        }
        if (answers.none { it.knowsRoute }) return null
        val found = answers.mapNotNull { it.estimate }
            .filter { it.etaMillis in (now - 12 * 60 * MIN)..(now + 24 * 60 * MIN) }
        synchronized(estimates) {
            estimates[train.number] = found.sortedBy { it.etaMillis }
            failed.remove(train.number)
        }
        return answers.mapNotNull { it.report }
    }

    private suspend fun refreshEtas(now: Long) {
        val due = trains.mapNotNull { train ->
            estimates[train.number].orEmpty()
                .firstOrNull { !it.passed && it.etaMillis in now..(now + ETA_AHEAD) }?.let { train to it }
        }
        _state.update { it.copy(phase = "Updating times of ${due.size} trains", done = 0, total = due.size) }
        withContext(Dispatchers.IO) {
            for ((train, est) in due) {
                ensureActive()   // stop between trains when stopped
                try {
                    railYatri.status(train.number, startDay(est.startDate, now), config).estimate
                        ?.let { updateEstimate(train.number, it) }
                } catch (e: Exception) {
                    Log.w(TAG, "[${train.number}] RailYatri: ${e.message}")
                }
                _state.update { it.copy(done = it.done + 1) }
            }
        }
    }

    /** Refreshes this train's NTES runs and rough KDTY times; returns the runs, or null on failure. */
    private fun fetchNtes(train: Train, now: Long, client: NtesClient = ntes): List<RunInstance>? {
        return try {
            val runs = client.runningStatus(train.number)
            val found = runs.mapNotNull { CrossingPredictor.estimate(it, now, config) }
                .filter { it.etaMillis in (now - 12 * 60 * MIN)..(now + 24 * 60 * MIN) }
                .sortedBy { it.etaMillis }
            synchronized(estimates) {
                estimates[train.number] = found
                ntesRuns[train.number] = now to runs
                failed.remove(train.number)
            }
            runs
        } catch (e: Exception) {
            Log.w(TAG, "[${train.number}] NTES: ${e.javaClass.simpleName}: ${e.message}")
            synchronized(estimates) { failed.add(train.number) }
            null
        }
    }

    private fun updateEstimate(trainNo: String, e: CrossingEstimate) = synchronized(estimates) {
        estimates[trainNo] = (estimates[trainNo].orEmpty().filter { it.startDate != e.startDate } + e)
            .sortedBy { it.etaMillis }
    }

    private fun startDay(startDate: String, now: Long): Int {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = IST }
        val today = fmt.parse(fmt.format(now))!!.time
        return ((today - fmt.parse(startDate)!!.time) / (24 * 60 * MIN)).toInt().coerceAtLeast(0)
    }

    private fun publishUpcoming() {
        val now = System.currentTimeMillis()
        val byNumber = trains.associateBy { it.number }
        val (upcoming, failedNow) = synchronized(estimates) {
            estimates.flatMap { (no, list) ->
                list.filter { !it.passed && it.etaMillis > now - 10 * MIN }
                    .map { TrainPass(byNumber[no] ?: Train(no, ""), it) }
            } to failed.sorted()
        }
        _state.update { it.copy(upcoming = upcoming.sortedBy { p -> p.estimate.etaMillis }, failedTrains = failedNow) }
    }

    // ── Cache (so a restart doesn't need the 2-minute full check) ───────────────

    private fun saveCache() {
        val json = JSONObject()
            .put("trainsFetchedAt", trainsFetchedAt)
            .put("lastFullScan", lastFullScan)
            .put("trains", JSONArray(trains.map { JSONObject().put("n", it.number).put("name", it.name) }))
        synchronized(estimates) {
            json.put("estimates", JSONObject().apply {
                estimates.forEach { (no, list) -> put(no, JSONArray(list.map { it.toJson() })) }
            })
        }
        cacheFile.writeText(json.toString())
    }

    private fun loadCache() {
        if (!cacheFile.exists()) return
        try {
            val json = JSONObject(cacheFile.readText())
            trainsFetchedAt = json.getLong("trainsFetchedAt")
            lastFullScan = json.getLong("lastFullScan")
            val t = json.getJSONArray("trains")
            trains = (0 until t.length()).map { t.getJSONObject(it).run { Train(getString("n"), getString("name")) } }
            val e = json.getJSONObject("estimates")
            e.keys().forEach { no ->
                val arr = e.getJSONArray(no)
                estimates[no] = (0 until arr.length()).map { arr.getJSONObject(it).toEstimate() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable cache", e)
            lastFullScan = 0L
            estimates.clear()
        }
    }

    private fun CrossingEstimate.toJson() = JSONObject()
        .put("eta", etaMillis).put("passed", passed).put("approach", approach.name).put("start", startDate)

    private fun JSONObject.toEstimate() = CrossingEstimate(
        etaMillis = getLong("eta"), passed = getBoolean("passed"),
        approach = Approach.valueOf(getString("approach")), startDate = getString("start")
    )
}
