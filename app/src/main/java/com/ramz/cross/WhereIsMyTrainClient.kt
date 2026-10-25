package com.ramz.cross

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One station card on whereismytrain.org.in. Times are as shown ("07:16 AM"): actual or expected. */
data class WimtStop(val code: String, val name: String, val reached: Boolean, val arrival: String?, val departure: String?)

/**
 * Live status from https://whereismytrain.org.in/?trainNo={train} (server-rendered HTML).
 *
 * Each station is a card `div.flex-grow.rounded-lg` with an `<h3>` "Name (CODE)". Stations the train
 * has reached have a green card (`bg-green-50`); upcoming ones are white. Under ARRIVAL and
 * DEPARTURE, the bold time is actual/expected and the grey one scheduled.
 *
 * The page has no km or run date, so the train is placed on another source's route ([report]).
 */
class WhereIsMyTrainClient {

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
        private val TITLE = Regex("""^(.*?)\s*\(([A-Z0-9]+)\)$""")
        private val TIME = Regex("""^\d{1,2}:\d{2}\s*[AP]M$""", RegexOption.IGNORE_CASE)
    }

    fun stops(trainNumber: String): List<WimtStop> =
        parse(Jsoup.connect("https://whereismytrain.org.in/?trainNo=$trainNumber").userAgent(UA).timeout(20_000).get())

    fun parse(doc: Document): List<WimtStop> =
        doc.select("div.flex-grow.rounded-lg").mapNotNull { card ->
            val title = TITLE.find(card.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null) ?: return@mapNotNull null
            val cols = card.select("div.grid > div")
            fun time(i: Int) = cols.getOrNull(i)?.selectFirst("div.font-semibold")?.text()?.trim()?.takeIf { TIME.matches(it) }
            WimtStop(title.groupValues[2], title.groupValues[1], card.hasClass("bg-green-50"), time(0), time(1))
        }

    /**
     * The train's position as a [LiveReport] on [routeKm] (another source's route for the same run):
     * the last station it reached; after it departed, placed towards the next station by the
     * expected arrival there. Null if it isn't running, or a station is missing from [routeKm].
     */
    fun report(stops: List<WimtStop>, routeKm: Map<String, Double>, startDate: String, now: Long): LiveReport? {
        val i = stops.indexOfLast { it.reached }
        if (i < 0 || i == stops.lastIndex) return null          // not started, or at the destination
        val here = stops[i]
        val km = routeKm[here.code] ?: return null
        val departed = here.departure?.let { past(it, now) }?.takeIf { it <= now }
        val event = departed ?: here.arrival?.let { past(it, now) } ?: return null

        val next = stops[i + 1]
        val nextKm = routeKm[next.code]
        val nextArrival = next.arrival?.let { future(it, now) }
        val kmNow = if (departed != null && nextKm != null && nextArrival != null && nextArrival > departed) {
            km + (nextKm - km) * ((now - departed).toDouble() / (nextArrival - departed)).coerceIn(0.0, 1.0)
        } else km

        return LiveReport(
            source = Source.WIMT, startDate = startDate, code = here.code, name = here.name,
            event = if (departed != null) "Departed from" else "Arrived at", eventMillis = event,
            moving = departed != null, routeKm = routeKm, kmNow = kmNow
        )
    }

    /** Where the train is, in words, straight from the page (no route needed). */
    fun summary(stops: List<WimtStop>, now: Long): String {
        val i = stops.indexOfLast { it.reached }
        if (stops.isEmpty()) return "No running status"
        if (i < 0) return "Not started yet"
        val here = stops[i]
        val where = "${here.name} (${here.code})"
        if (i == stops.lastIndex) return "Reached $where" + (here.arrival?.let { " at ${hhmm(past(it, now))}" } ?: "")
        val departed = here.departure?.let { past(it, now) }?.takeIf { it <= now }
        return if (departed != null) "Departed $where at ${hhmm(departed)} · next ${stops[i + 1].code}"
        else "At $where" + (here.arrival?.let { " since ${hhmm(past(it, now))}" } ?: "")
    }

    private fun hhmm(millis: Long) = SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = IST }.format(millis)

    // The page shows clock times only: take the most recent such time (it has happened) …
    private fun past(clock: String, now: Long): Long {
        val t = today(clock, now)
        return if (t > now + 30 * MIN) t - 24 * 60 * MIN else t
    }

    // … or the next one (it is expected).
    private fun future(clock: String, now: Long): Long {
        val t = today(clock, now)
        return if (t < now - 60 * MIN) t + 24 * 60 * MIN else t
    }

    private fun today(clock: String, now: Long): Long {
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).apply { timeZone = IST }.parse(clock.uppercase())!!
        val c = Calendar.getInstance(IST).apply { this.time = time }
        return Calendar.getInstance(IST).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, c.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, c.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
