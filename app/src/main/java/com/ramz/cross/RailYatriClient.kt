package com.ramz.cross

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

/** [knowsRoute]: RailYatri's timetable for this train passes KDTY (false for some MEMUs). */
data class RailYatriStatus(val report: LiveReport?, val estimate: CrossingEstimate?, val knowsRoute: Boolean = false)

/**
 * RailYatri live status: https://www.railyatri.in/live-train-status/{train}?start_day={n}
 * where n = how many days ago the run left its origin (0 = today).
 *
 * The page embeds its data as JSON in `<script id="__NEXT_DATA__">`, under `props.pageProps`:
 *  • `ltsData`: `current_station_code/_name`, `status` (A = arrived, D = departed, T = crossed),
 *    `a_min` (minutes after midnight of the start date), `ahead_distance` (km past that station,
 *    RailYatri's own estimate), `delay` (minutes), `at_src` / `at_dstn`, `is_run_day`.
 *  • `timeTableData[0].route`: every station, including ones run through, with
 *    `distance_from_source` and scheduled `sta_min` (minutes after midnight of the start date).
 * About 28 KB per request, against ~1.2 MB for NTES.
 */
class RailYatriClient {

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    }

    fun status(trainNumber: String, startDay: Int, cfg: CrossingConfig = CrossingConfig()): RailYatriStatus {
        val doc = Jsoup.connect("https://www.railyatri.in/live-train-status/$trainNumber?start_day=$startDay")
            .userAgent(UA).timeout(20_000).maxBodySize(0).get()
        return parse(doc, cfg)
    }

    fun parse(doc: Document, cfg: CrossingConfig = CrossingConfig()): RailYatriStatus {
        val json = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return RailYatriStatus(null, null)
        val props = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
        val lts = props.optJSONObject("ltsData") ?: return RailYatriStatus(null, null)
        val startDate = lts.optString("train_start_date").takeIf { it.isNotEmpty() } ?: return RailYatriStatus(null, null)
        val midnight = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = IST }.parse(startDate)!!.time

        val route = LinkedHashMap<String, Double>()
        val staMin = HashMap<String, Int>()
        props.optJSONArray("timeTableData")?.optJSONObject(0)?.optJSONArray("route")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val code = s.optString("station_code")
                route.putIfAbsent(code, s.optDouble("distance_from_source"))
                staMin.putIfAbsent(code, s.optInt("sta_min"))
            }
        }

        val code = lts.optString("current_station_code").takeIf { it.isNotEmpty() && it != "null" }
        val running = code != null && !lts.optBoolean("at_src") && !lts.optBoolean("at_dstn")
        val delay = lts.optInt("delay")
        val kmNow = code?.let { route[it] }?.plus(lts.optDouble("ahead_distance", 0.0))

        val report = if (!running) null else {
            val status = lts.optString("status")
            LiveReport(
                source = Source.RAILYATRI, startDate = startDate, code = code!!,
                name = lts.optString("current_station_name").trimEnd('~').trim(),
                event = when (status) { "A" -> "Arrived at"; "D" -> "Departed from"; else -> "Crossed" },
                eventMillis = midnight + lts.optLong("a_min") * MIN,
                moving = status != "A",
                routeKm = route, kmNow = kmNow
            )
        }

        val estimate = if (!lts.optBoolean("is_run_day", true)) null else GateRule.approach(route, cfg)?.let { approach ->
            val kdtyKm = route.getValue(cfg.stationCode)
            val sta = staMin[cfg.stationCode] ?: return@let null
            CrossingEstimate(
                etaMillis = midnight + (sta + delay) * MIN,
                passed = lts.optBoolean("at_dstn") || (running && (kmNow ?: 0.0) > kdtyKm),
                approach = approach,
                startDate = startDate
            )
        }
        return RailYatriStatus(report, estimate, knowsRoute = GateRule.approach(route, cfg) != null)
    }
}
