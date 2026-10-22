package com.ramz.cross

import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.IOException

/**
 * One NTES browsing session (cookie jar). Not thread-safe: use one instance per worker.
 *
 * NTES CSRF tokens are single-use — a second POST with the same token returns an empty
 * body — so every request fetches a fresh token first.
 */
class NtesClient {

    companion object {
        private const val BASE = "https://enquiry.indianrail.gov.in/mntes/"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        private const val SESSION_TTL_MS = 10 * 60 * 1000L
    }

    private var cookies = mutableMapOf<String, String>()
    private var sessionExpiry = 0L

    fun runningStatus(trainNumber: String): List<RunInstance> {
        repeat(2) {
            if (cookies.isEmpty() || System.currentTimeMillis() > sessionExpiry) openSession()
            val (field, value) = csrfToken()
            val resp = Jsoup.connect("${BASE}tr?opt=TrainRunning&subOpt=FindRunningInstance")
                .userAgent(UA).referrer(BASE).cookies(cookies)
                .method(Connection.Method.POST)
                .data("trainNo", trainNumber)
                .data(field, value)
                .timeout(45_000)
                .maxBodySize(0)
                .execute()
            cookies.putAll(resp.cookies())
            if (resp.bodyAsBytes().size > 500) return RunningStatusParser.parse(resp.parse())
            cookies.clear()   // session went stale; start over once
        }
        throw IOException("NTES returned an empty page for $trainNumber")
    }

    private fun openSession() {
        cookies = Jsoup.connect(BASE).userAgent(UA).timeout(30_000).execute().cookies().toMutableMap()
        sessionExpiry = System.currentTimeMillis() + SESSION_TTL_MS
    }

    private fun csrfToken(): Pair<String, String> {
        val resp = Jsoup.connect("${BASE}GetCSRFToken?t=${System.currentTimeMillis()}")
            .userAgent(UA).referrer(BASE).cookies(cookies).timeout(20_000).execute()
        cookies.putAll(resp.cookies())
        val input = Jsoup.parse(resp.body()).selectFirst("input[name]")
            ?: throw IOException("No CSRF token in NTES response")
        return input.attr("name") to input.attr("value")
    }
}
