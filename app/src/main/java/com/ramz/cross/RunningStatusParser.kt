package com.ramz.cross

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

/** A time shown by NTES. [isActual] = reported by the station; false = NTES forecast (shown with `*`). */
data class StationTime(val millis: Long, val isActual: Boolean)

data class RouteRow(
    val code: String,
    val name: String,
    val km: Int,             // distance from the train's origin
    val isStop: Boolean,     // false = non-stopping station (no times available)
    val arrival: StationTime?,
    val departure: StationTime?
)

/** NTES "Current Position", e.g. "Departed from KURUPPANTARA(KRPP) at 13:52 22-Oct". */
data class CurrentPosition(val code: String, val name: String, val departed: Boolean, val millis: Long)

/**
 * One running instance of a train, identified by the date it left its origin.
 * [position] is set only while the train is on its way (not before the start or after arrival).
 */
data class RunInstance(val startDate: String, val rows: List<RouteRow>, val position: CurrentPosition? = null)

/**
 * Parses the NTES "FindRunningInstance" page.
 *
 * The page has one `div.tab-pane#trainDD-mon-YYYY` per running instance (usually yesterday,
 * today, tomorrow and two older days). Every station is a `div.w3-card-2`:
 *   • `.stopRow`    – a halt: left column = arrival, right column = departure.
 *                     Each column holds the scheduled time then the actual/expected time;
 *                     a trailing `*` marks a forecast. "Non-Reporting Station" has no time.
 *   • `.nonStopRow` – a station the train runs through: "NAME - CODE" and km only.
 * Rows are in travel order and km grows along the route.
 *
 * While the train runs, the pane's `<h4>` says "Current Position" and the `<h6>` below it says
 * where: "Arrived at NAME(CODE) at …" or "Departed from NAME(CODE) at …". This includes
 * stations the train runs through without stopping (e.g. KRPP), which have no row times.
 */
object RunningStatusParser {

    private val TIME = Regex("""(\d{1,2}:\d{2})\s+(\d{1,2}-[A-Za-z]{3})(\*?)""")
    private val KM = Regex("""(\d+)\s*KMs""")
    private val NON_STOP_LABEL = Regex("""^(.+?)\s+-\s+([A-Z0-9]+)$""")
    private val POSITION = Regex("""(Arrived at|Departed from)\s+(.+?)\s*\(([A-Z0-9]+)\)\s+at\s+(\d{1,2}:\d{2})\s+(\d{1,2}-[A-Za-z]{3})""")

    fun parse(doc: Document): List<RunInstance> =
        doc.select("div.tab-pane[id^=train]").map { pane ->
            val startDate = pane.id().removePrefix("train")      // "21-oct-2026"
            val startCal = Calendar.getInstance(IST).apply {
                time = dateFormat("dd-MMM-yyyy").parse(startDate)!!
            }
            val rows = pane.select("div.w3-card-2.stopRow, div.w3-card-2.nonStopRow")
                .mapNotNull { if (it.hasClass("nonStopRow")) parseNonStop(it) else parseStop(it, startCal) }
            RunInstance(startDate, rows, parsePosition(pane, startCal))
        }

    private fun parsePosition(pane: Element, start: Calendar): CurrentPosition? {
        if (pane.selectFirst("h4")?.text()?.contains("Current Position") != true) return null
        val m = POSITION.find(pane.selectFirst("h6")?.text() ?: return null) ?: return null
        val (verb, name, code, hhmm, dayMon) = m.destructured
        return CurrentPosition(code, name.trim(), verb.startsWith("Departed"), toMillis(hhmm, dayMon, start))
    }

    private fun parseStop(row: Element, start: Calendar): RouteRow? {
        val info = row.selectFirst("div[style*='float:left;flex:1']") ?: return null
        val name = info.selectFirst("font > b")?.text()?.trim().orEmpty()
        val code = info.selectFirst("div[style*='float:left;padding'] > b")?.ownText()?.trim().orEmpty()
        if (code.isEmpty()) return null
        val km = KM.find(info.text())?.groupValues?.get(1)?.toInt() ?: 0   // source shows "SRC"
        val arrCol = row.selectFirst("div[style*='float:left;width:100px']")
        val depCol = row.selectFirst("div[style*='float:right;text-align:right;width:100px']")
        return RouteRow(code, name, km, true, actualTime(arrCol, start), actualTime(depCol, start))
    }

    private fun parseNonStop(row: Element): RouteRow? {
        val label = row.select("b").map { it.text().trim() }
            .firstNotNullOfOrNull { NON_STOP_LABEL.find(it) } ?: return null
        val km = KM.find(row.text())?.groupValues?.get(1)?.toInt() ?: return null
        return RouteRow(label.groupValues[2], label.groupValues[1], km, false, null, null)
    }

    /** Second `<span>` of a time column is the actual/expected time. */
    private fun actualTime(col: Element?, start: Calendar): StationTime? {
        val text = col?.children()?.filter { it.tagName() == "span" }?.getOrNull(1)?.text() ?: return null
        val m = TIME.find(text) ?: return null
        val (hhmm, dayMon, star) = m.destructured
        return StationTime(toMillis(hhmm, dayMon, start), isActual = star.isEmpty())
    }

    // NTES shows "18:47 21-Oct" without a year; take it from the start date, handling Dec→Jan.
    private fun toMillis(hhmm: String, dayMon: String, start: Calendar): Long {
        val startYear = start.get(Calendar.YEAR)
        val cal = Calendar.getInstance(IST).apply {
            time = dateFormat("HH:mm dd-MMM yyyy").parse("$hhmm $dayMon $startYear")!!
        }
        if (cal.get(Calendar.MONTH) < start.get(Calendar.MONTH) - 6) cal.add(Calendar.YEAR, 1)
        return cal.timeInMillis
    }

    private fun dateFormat(pattern: String) =
        SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = IST }
}
